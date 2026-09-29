// Мини-роутер и контекст запроса для Deno.serve. Заменяет Fastify: маршруты, парсинг тела/квери, заголовки, ошибки.
import { ApiError, badRequest } from "./errors.ts";
import type { Principal } from "./auth.ts";
import type { Device, Admin } from "./kv.ts";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (v: unknown): v is string => typeof v === "string" && UUID_RE.test(v);

export const SECURITY_HEADERS: Record<string, string> = {
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "Cross-Origin-Resource-Policy": "same-site",
  "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
  "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
};

export function jsonResponse(data: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...SECURITY_HEADERS, ...extraHeaders },
  });
}

export function errorResponse(err: unknown): Response {
  if (err instanceof ApiError) return jsonResponse(err.body(), err.statusCode);
  console.error("unhandled error", err);
  return jsonResponse({ error: "internal", message: "Внутренняя ошибка сервера" }, 500);
}

export class ReqCtx {
  req: Request;
  url: URL;
  method: string;
  params: Record<string, string> = {};
  ip: string;
  principal?: Principal;
  device?: Device;
  admin?: Admin;
  private _bodyText: string | null = null;

  constructor(req: Request, info?: Deno.ServeHandlerInfo) {
    this.req = req;
    this.url = new URL(req.url);
    this.method = req.method.toUpperCase();
    const fwd = req.headers.get("x-forwarded-for");
    this.ip = (fwd ? fwd.split(",")[0].trim() : "") ||
      (info?.remoteAddr && "hostname" in info.remoteAddr ? (info.remoteAddr as Deno.NetAddr).hostname : "") || "0.0.0.0";
  }

  get query(): URLSearchParams {
    return this.url.searchParams;
  }

  /** Парсит JSON-тело; пустое тело = {}; __proto__ отклоняется (защита от загрязнения прототипа). */
  async json<T = Record<string, unknown>>(): Promise<T> {
    if (this._bodyText === null) this._bodyText = await this.req.text();
    const text = this._bodyText;
    if (!text || /^\s*$/.test(text)) return {} as T;
    try {
      return JSON.parse(text, (key, val) => {
        if (key === "__proto__") throw new SyntaxError("proto");
        return val;
      });
    } catch {
      throw badRequest("Некорректный JSON");
    }
  }

  header(name: string): string | null {
    return this.req.headers.get(name);
  }
}

export type Handler = (ctx: ReqCtx) => Promise<Response> | Response;

interface Route {
  method: string;
  segs: string[];
  handler: Handler;
}

export class Router {
  routes: Route[] = [];
  add(method: string, path: string, handler: Handler) {
    this.routes.push({ method: method.toUpperCase(), segs: path.split("/").filter(Boolean), handler });
  }
  get(p: string, h: Handler) {
    this.add("GET", p, h);
  }
  post(p: string, h: Handler) {
    this.add("POST", p, h);
  }
  patch(p: string, h: Handler) {
    this.add("PATCH", p, h);
  }
  delete(p: string, h: Handler) {
    this.add("DELETE", p, h);
  }

  match(method: string, pathname: string): { handler: Handler; params: Record<string, string> } | null {
    const parts = pathname.split("/").filter(Boolean);
    for (const r of this.routes) {
      if (r.method !== method) continue;
      if (r.segs.length !== parts.length) continue;
      const params: Record<string, string> = {};
      let ok = true;
      for (let i = 0; i < r.segs.length; i++) {
        const s = r.segs[i];
        if (s.startsWith(":")) params[s.slice(1)] = decodeURIComponent(parts[i]);
        else if (s !== parts[i]) {
          ok = false;
          break;
        }
      }
      if (ok) return { handler: r.handler, params };
    }
    return null;
  }
}

// ---------- валидаторы входных данных ----------

export function reqUuidParam(ctx: ReqCtx, name: string): string {
  const v = ctx.params[name];
  if (!isUuid(v)) throw badRequest(`Некорректный ${name}`);
  return v;
}

export function intQuery(ctx: ReqCtx, name: string, def: number, min: number, max: number): number {
  const raw = ctx.query.get(name);
  if (raw === null) return def;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) throw badRequest(`Некорректный параметр ${name}`);
  return n;
}

export function enumQuery(ctx: ReqCtx, name: string, allowed: string[], def: string): string {
  const raw = ctx.query.get(name);
  if (raw === null) return def;
  if (!allowed.includes(raw)) throw badRequest(`Некорректный параметр ${name}`);
  return raw;
}

export function dateTimeQuery(ctx: ReqCtx, name: string): number | null {
  const raw = ctx.query.get(name);
  if (raw === null) return null;
  const t = Date.parse(raw);
  if (Number.isNaN(t)) throw badRequest(`Некорректная дата ${name}`);
  return t;
}
