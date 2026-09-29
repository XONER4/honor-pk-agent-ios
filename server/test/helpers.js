// Общие хелперы тестов: сервер на pg-mem + случайный порт, HTTP-клиент, WebSocket-клиент, мок DeepSeek.
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { loadConfig } from '../src/config.js';
import { buildApp } from '../src/app.js';

export const ADMIN_KEY = 'test-admin-key-123';

export async function startServer(env = {}, overrides = {}) {
  const mediaDir = fs.mkdtempSync(path.join(os.tmpdir(), 'honer-media-'));
  const config = loadConfig({
    DB_MODE: 'memory',
    MEDIA_DIR: mediaDir,
    ADMIN_KEY,
    ADMIN_EMAILS: 'Boss@Example.com',
    DEEPSEEK_API_KEY: 'sk-test-key',
    PRESENCE_OFFLINE_MS: '300',
    TYPING_TTL_MS: '2000',
    AI_QUESTION_DELAY_MS: '300',
    LOG_LEVEL: 'silent',
    ...env,
  });
  const app = await buildApp(config, { logger: false, ...overrides });
  await app.listen({ port: 0, host: '127.0.0.1' });
  const base = `http://127.0.0.1:${app.server.address().port}`;

  async function api(method, url, { token, body, headers = {} } = {}) {
    const h = { ...headers };
    if (token) h.authorization = `Bearer ${token}`;
    let payload;
    if (body !== undefined && !(body instanceof FormData)) {
      h['content-type'] = 'application/json';
      payload = JSON.stringify(body);
    } else {
      payload = body;
    }
    const res = await fetch(base + url, { method, headers: h, body: payload });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = text; }
    return { status: res.status, body: json, headers: res.headers };
  }

  async function register(extra = {}) {
    const r = await api('POST', '/v1/devices/register', {
      body: { installId: randomUUID(), platform: 'android', deviceModel: 'vivo V2250', deviceName: 'Phone', osVersion: '14',
        appVersion: '10.44.0', displayName: 'Иван', birthday: '2008-05-01', language: 'ru', licenseAcceptedAt: new Date().toISOString(),
        pushToken: null, ...extra },
    });
    if (r.status !== 200) throw new Error(`register failed ${r.status} ${JSON.stringify(r.body)}`);
    return r.body;
  }

  async function adminLogin() {
    const r = await api('POST', '/v1/admin/login', { body: { adminKey: ADMIN_KEY } });
    if (r.status !== 200) throw new Error(`admin login failed ${r.status}`);
    return r.body.token;
  }

  const sockets = [];
  async function connect(token) {
    const c = await wsConnect(base, token);
    sockets.push(c);
    return c;
  }

  async function close() {
    for (const s of sockets) s.close();
    await app.close();
    fs.rmSync(mediaDir, { recursive: true, force: true });
  }

  return { app, base, config, api, register, adminLogin, connect, close, mediaDir };
}

/** WebSocket-клиент (глобальный WebSocket Node 22+). Собирает кадры, умеет ждать нужный. */
export function wsConnect(base, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base.replace('http', 'ws')}/v1/ws?token=${encodeURIComponent(token)}`);
    const frames = [];
    const waiters = [];
    let closed = null;
    ws.addEventListener('message', (ev) => {
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
    ws.addEventListener('close', (ev) => { closed = { code: ev.code }; });
    ws.addEventListener('error', (e) => reject(new Error(`ws error ${e.message || ''}`)));
    const client = {
      ws,
      frames,
      get closed() { return closed; },
      send: (obj) => ws.send(JSON.stringify(obj)),
      close: () => { try { ws.close(); } catch { /* ignore */ } },
      /** Ждёт кадр, удовлетворяющий pred (в т.ч. уже полученный). */
      waitFor(pred, timeoutMs = 3000) {
        const found = frames.find(pred);
        if (found) return Promise.resolve(found);
        return new Promise((res, rej) => {
          const w = { pred, resolve: res };
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
          const t = setTimeout(() => rej(new Error('timeout waiting close')), timeoutMs);
          ws.addEventListener('close', (ev) => { clearTimeout(t); res({ code: ev.code }); });
        });
      },
    };
    ws.addEventListener('open', () => resolve(client), { once: true });
  });
}

/** Локальный мок DeepSeek. handler(req, res, body) — своя логика ответа. */
export async function startMockUpstream(handler) {
  const requests = [];
  const server = http.createServer((req, res) => {
    let data = '';
    req.on('data', (c) => { data += c; });
    req.on('end', () => {
      const body = data ? JSON.parse(data) : null;
      requests.push({ url: req.url, headers: req.headers, body });
      handler(req, res, body);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}`;
  return {
    url,
    requests,
    close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }),
  };
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const newId = () => randomUUID();
