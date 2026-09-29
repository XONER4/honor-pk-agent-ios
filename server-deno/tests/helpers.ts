// Тест-хелперы: сервер на Deno KV (:memory:) + случайный порт, HTTP-клиент, WebSocket-клиент, мок DeepSeek.
import { randomUUID } from "node:crypto";
import { loadConfig } from "../config.ts";
import { buildApp, type BuildOverrides } from "../app.ts";

export const ADMIN_KEY = "test-admin-key-123";

export async function startServer(env: Record<string, string> = {}, overrides: BuildOverrides = {}) {
  const config = loadConfig({
    ADMIN_KEY,
    ADMIN_EMAILS: "Boss@Example.com",
    DEEPSEEK_API_KEY: "sk-test-key",
    PRESENCE_OFFLINE_MS: "300",
    TYPING_TTL_MS: "2000",
    AI_QUESTION_DELAY_MS: "300",
    ...env,
  });
  const kv = await Deno.openKv(":memory:");
  const built = await buildApp(config, { kv, ...overrides });
  const server = Deno.serve({ port: 0, hostname: "127.0.0.1", onListen: () => {} }, built.handler);
  const port = server.addr.port;
  const base = `http://127.0.0.1:${port}`;

  async function api(method: string, url: string, { token, body, headers = {} }: { token?: string; body?: unknown; headers?: Record<string, string> } = {}) {
    const h: Record<string, string> = { ...headers };
    if (token) h.authorization = `Bearer ${token}`;
    let payload: BodyInit | undefined;
    if (body !== undefined && !(body instanceof FormData)) {
      h["content-type"] = "application/json";
      payload = JSON.stringify(body);
    } else {
      payload = body as BodyInit | undefined;
    }
    const res = await fetch(base + url, { method, headers: h, body: payload });
    const text = await res.text();
    let json: unknown = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = text;
    }
    // deno-lint-ignore no-explicit-any
    return { status: res.status, body: json as any, headers: res.headers };
  }

  async function register(extra: Record<string, unknown> = {}) {
    const r = await api("POST", "/v1/devices/register", {
      body: {
        installId: randomUUID(),
        platform: "android",
        deviceModel: "vivo V2250",
        deviceName: "Phone",
        osVersion: "14",
        appVersion: "10.44.0",
        displayName: "Иван",
        birthday: "2008-05-01",
        language: "ru",
        licenseAcceptedAt: new Date().toISOString(),
        pushToken: null,
        ...extra,
      },
    });
    if (r.status !== 200) throw new Error(`register failed ${r.status} ${JSON.stringify(r.body)}`);
    return r.body;
  }

  async function adminLogin() {
    const r = await api("POST", "/v1/admin/login", { body: { adminKey: ADMIN_KEY } });
    if (r.status !== 200) throw new Error(`admin login failed ${r.status}`);
    return r.body.token as string;
  }

  const sockets: WsClient[] = [];
  async function connect(token: string) {
    const c = await wsConnect(base, token);
    sockets.push(c);
    return c;
  }

  async function close() {
    for (const s of sockets) s.close();
    await server.shutdown();
    await built.stop();
    try {
      kv.close();
    } catch { /* ignore */ }
  }

  return { ctx: built.ctx, base, config, api, register, adminLogin, connect, close, kv };
}

export interface WsClient {
  ws: WebSocket;
  // deno-lint-ignore no-explicit-any
  frames: any[];
  readonly closed: { code: number } | null;
  send: (obj: unknown) => void;
  close: () => void;
  // deno-lint-ignore no-explicit-any
  waitFor: (pred: (f: any) => boolean, timeoutMs?: number) => Promise<any>;
  waitClose: (timeoutMs?: number) => Promise<{ code: number }>;
}

export function wsConnect(base: string, token: string): Promise<WsClient> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base.replace("http", "ws")}/v1/ws?token=${encodeURIComponent(token)}`);
    // deno-lint-ignore no-explicit-any
    const frames: any[] = [];
    // deno-lint-ignore no-explicit-any
    const waiters: { pred: (f: any) => boolean; resolve: (f: any) => void; timer: ReturnType<typeof setTimeout> }[] = [];
    let closed: { code: number } | null = null;
    ws.addEventListener("message", (ev) => {
      const f = JSON.parse(ev.data);
      frames.push(f);
      for (const w of [...waiters]) {
        if (w.pred(f)) {
          waiters.splice(waiters.indexOf(w), 1);
          clearTimeout(w.timer);
          w.resolve(f);
        }
      }
    });
    ws.addEventListener("close", (ev) => {
      closed = { code: ev.code };
    });
    ws.addEventListener("error", () => reject(new Error("ws error")));
    const client: WsClient = {
      ws,
      frames,
      get closed() {
        return closed;
      },
      send: (obj) => ws.send(JSON.stringify(obj)),
      close: () => {
        try {
          ws.close();
        } catch { /* ignore */ }
      },
      waitFor(pred, timeoutMs = 3000) {
        const found = frames.find(pred);
        if (found) return Promise.resolve(found);
        return new Promise((res, rej) => {
          const w = { pred, resolve: res, timer: 0 as unknown as ReturnType<typeof setTimeout> };
          w.timer = setTimeout(() => {
            waiters.splice(waiters.indexOf(w), 1);
            rej(new Error(`timeout waiting for frame; got: ${JSON.stringify(frames).slice(0, 800)}`));
          }, timeoutMs);
          waiters.push(w);
        });
      },
      waitClose(timeoutMs = 3000) {
        return new Promise((res, rej) => {
          if (closed) return res(closed);
          const t = setTimeout(() => rej(new Error("timeout waiting close")), timeoutMs);
          ws.addEventListener("close", (ev) => {
            clearTimeout(t);
            res({ code: ev.code });
          });
        });
      },
    };
    ws.addEventListener("open", () => resolve(client), { once: true });
  });
}

/** Локальный мок DeepSeek на отдельном порту (Deno.serve). handler(body) → Response. */
export async function startMockUpstream(handler: (req: Request, body: Record<string, unknown> | null) => Response | Promise<Response>) {
  const requests: { url: string; headers: Record<string, string>; body: Record<string, unknown> | null }[] = [];
  const server = Deno.serve({ port: 0, hostname: "127.0.0.1", onListen: () => {} }, async (req) => {
    const text = await req.text();
    const body = text ? JSON.parse(text) : null;
    const headers: Record<string, string> = {};
    req.headers.forEach((v, k) => (headers[k] = v));
    requests.push({ url: new URL(req.url).pathname, headers, body });
    return await handler(req, body);
  });
  const url = `http://127.0.0.1:${server.addr.port}`;
  return {
    url,
    requests,
    close: () => server.shutdown(),
  };
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
export const newId = () => randomUUID();
