// admin2: аккаунт админа (логин/пароль), публичный ID, метрики и токены, события, отчёты,
// блокировка на срок, ограничения, заметки, журнал, глобальный выключатель ИИ.
import { test, before, after, describe } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startServer, startMockUpstream, sleep, ADMIN_KEY } from './helpers.js';
import { pickPublicId, backfillPublicIds } from '../src/public-id.js';
import { hashPassword, verifyPassword } from '../src/passwords.js';
import { inSchedule } from '../src/ai-control.js';
import { percentile, UsageSniffer } from '../src/metrics.js';

// ---------- чистые функции ----------

test('passwords: scrypt with random salt, verify, dummy', async () => {
  const a = await hashPassword('секрет-123');
  const b = await hashPassword('секрет-123');
  assert.match(a, /^scrypt\$16384\$8\$1\$/);
  assert.notEqual(a, b, 'random salt');
  assert.equal(await verifyPassword('секрет-123', a), true);
  assert.equal(await verifyPassword('секрет-124', a), false);
  assert.equal(await verifyPassword('секрет-123', null), false);
  assert.equal(await verifyPassword('x', 'garbage'), false);
});

test('pickPublicId: 4 digits, then 5 when the range is full', () => {
  const id = pickPublicId(new Set());
  assert.match(id, /^\d{4}$/);
  const full = new Set(Array.from({ length: 10_000 }, (_, i) => String(i).padStart(4, '0')));
  assert.match(pickPublicId(full), /^\d{5}$/);
  const almost = new Set(full);
  almost.delete('0042');
  assert.equal(pickPublicId(almost), '0042');
});

test('inSchedule: days, windows, overnight, timezone', () => {
  const tz = 'Europe/Moscow'; // UTC+3
  const mon10 = new Date('2026-09-28T07:00:00Z'); // пн 10:00 МСК
  const mon23 = new Date('2026-09-28T20:30:00Z'); // пн 23:30 МСК
  const tue02 = new Date('2026-09-28T23:30:00Z'); // вт 02:30 МСК
  assert.equal(inSchedule([], mon10, tz), true);
  assert.equal(inSchedule([{ days: [1], from: '09:00', to: '18:00' }], mon10, tz), true);
  assert.equal(inSchedule([{ days: [2], from: '09:00', to: '18:00' }], mon10, tz), false);
  assert.equal(inSchedule([{ days: [1], from: '09:00', to: '18:00' }], mon23, tz), false);
  const night = [{ days: [1], from: '22:00', to: '03:00' }];
  assert.equal(inSchedule(night, mon23, tz), true);
  assert.equal(inSchedule(night, tue02, tz), true, 'overnight window continues into the next day');
  assert.equal(inSchedule(night, mon10, tz), false);
  assert.equal(inSchedule([{ from: '00:00', to: '00:00' }], mon10, tz), true, 'from == to → whole day');
});

test('percentile and usage sniffer (JSON and SSE)', () => {
  assert.equal(percentile([], 50), null);
  assert.equal(percentile([5, 1, 3, 2, 4], 50), 3);
  assert.equal(percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 95), 10);

  const j = new UsageSniffer(false);
  j.push(Buffer.from('{"choices":[],"usage":{"prompt_tokens":'));
  j.push(Buffer.from('12,"completion_tokens":5}}'));
  assert.deepEqual(j.finish(), { prompt: 12, completion: 5 });

  const s = new UsageSniffer(true);
  s.push(Buffer.from('data: {"choices":[{"delta":{"content":"a"}}]}\n\ndata: {"choices":[],"us'));
  s.push(Buffer.from('age":{"prompt_tokens":7,"completion_tokens":3}}\n\ndata: [DONE]\n\n'));
  assert.deepEqual(s.finish(), { prompt: 7, completion: 3 });
});

// ---------- аккаунт администратора ----------

describe('admin account: setup + login/password', () => {
  let s;
  before(async () => { s = await startServer({ LOGIN_RATE_MAX: '50' }); });
  after(() => s.close());

  test('setup requires x-admin-key, only once; login with login/password; key login still works', async () => {
    assert.deepEqual((await s.api('GET', '/v1/admin/setup-status')).body, { hasAccount: false });

    let r = await s.api('POST', '/v1/admin/setup', { body: { login: 'boss', password: 'Password123' } });
    assert.equal(r.status, 401, 'no key');
    r = await s.api('POST', '/v1/admin/setup', { body: { login: 'boss', password: 'Password123' }, headers: { 'x-admin-key': 'wrong' } });
    assert.equal(r.status, 401, 'wrong key');
    r = await s.api('POST', '/v1/admin/setup', { body: { login: 'b', password: 'Password123' }, headers: { 'x-admin-key': ADMIN_KEY } });
    assert.equal(r.status, 400, 'short login');
    r = await s.api('POST', '/v1/admin/setup', { body: { login: 'boss', password: 'short' }, headers: { 'x-admin-key': ADMIN_KEY } });
    assert.equal(r.status, 400, 'short password');
    assert.match(r.body.message, /8/);

    r = await s.api('POST', '/v1/admin/setup', { body: { login: 'Boss', password: 'Password123' }, headers: { 'x-admin-key': ADMIN_KEY } });
    assert.equal(r.status, 200);
    assert.match(r.body.token, /^a_/);
    assert.equal(r.body.login, 'boss');
    assert.equal((await s.api('GET', '/v1/admin/overview', { token: r.body.token })).status, 200);
    assert.deepEqual((await s.api('GET', '/v1/admin/setup-status')).body, { hasAccount: true });

    r = await s.api('POST', '/v1/admin/setup', { body: { login: 'other', password: 'Password123' }, headers: { 'x-admin-key': ADMIN_KEY } });
    assert.equal(r.status, 409, 'second setup refused');

    r = await s.api('POST', '/v1/admin/login', { body: { login: 'BOSS', password: 'Password123' } });
    assert.equal(r.status, 200);
    assert.equal(r.body.login, 'boss');
    assert.equal((await s.api('GET', '/v1/admin/devices', { token: r.body.token })).status, 200);

    r = await s.api('POST', '/v1/admin/login', { body: { login: 'boss', password: 'Password124' } });
    assert.equal(r.status, 401);
    assert.equal(r.body.message, 'Неверный логин или пароль');
    r = await s.api('POST', '/v1/admin/login', { body: { login: 'nobody', password: 'Password123' } });
    assert.equal(r.status, 401);
    r = await s.api('POST', '/v1/admin/login', { body: { login: 'boss' } });
    assert.equal(r.status, 400);

    // Запасной вход по ключу работает по-прежнему.
    assert.match(await s.adminLogin(), /^a_/);
  });

  test('without ADMIN_KEY configured setup needs no header', async () => {
    const s2 = await startServer({ ADMIN_KEY: '' });
    const r = await s2.api('POST', '/v1/admin/setup', { body: { login: 'root', password: 'Password123' } });
    assert.equal(r.status, 200);
    assert.equal((await s2.api('POST', '/v1/admin/login', { body: { login: 'root', password: 'Password123' } })).status, 200);
    await s2.close();
  });
});

// ---------- остальное ----------

describe('admin2 features', () => {
  let upstream;
  let s;
  let admin;
  before(async () => {
    upstream = await startMockUpstream((req, res, body) => {
      if (body?.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write('data: {"choices":[{"delta":{"content":"Привет"}}]}\n\n');
        if (body.stream_options?.include_usage) res.write('data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}\n\n');
        res.end('data: [DONE]\n\n');
        return;
      }
      if (body?.messages?.[0]?.content === 'fail') {
        res.writeHead(500, { 'content-type': 'application/json' });
        res.end('{"error":{"message":"boom"}}');
        return;
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ choices: [{ message: { content: 'ok' } }], usage: { prompt_tokens: 20, completion_tokens: 6 } }));
    });
    s = await startServer({ DEEPSEEK_BASE_URL: upstream.url, METRICS_PUSH_MS: '200', BLOCK_SWEEP_MS: '200' });
    admin = await s.adminLogin();
  });
  after(async () => { await s.close(); await upstream.close(); });

  const ai = (token, content = 'hi', extra = {}) =>
    s.api('POST', '/v1/ai/chat/completions', { token, body: { model: 'deepseek-chat', messages: [{ role: 'user', content }], ...extra } });

  test('public id: 4 digits on register, unique, searchable, backfilled for old users', async () => {
    const a = await s.register({ displayName: 'Анна' });
    const b = await s.register({ displayName: 'Борис' });
    assert.match(a.publicId, /^\d{4}$/);
    assert.notEqual(a.publicId, b.publicId);
    const again = await s.register({ installId: randomUUID() });
    assert.match(again.publicId, /^\d{4}$/);

    let list = (await s.api('GET', `/v1/admin/devices?query=${a.publicId}`, { token: admin })).body;
    assert.ok(list.some((d) => d.deviceId === a.deviceId && d.publicId === a.publicId));
    list = (await s.api('GET', `/v1/admin/devices?query=%23${a.publicId}`, { token: admin })).body;
    assert.ok(list.some((d) => d.deviceId === a.deviceId));
    const card = (await s.api('GET', `/v1/admin/devices/${a.deviceId}`, { token: admin })).body;
    assert.equal(card.publicId, a.publicId);
    const me = await s.api('PATCH', '/v1/devices/me', { token: a.token, body: {} });
    assert.equal(me.body.publicId, a.publicId);

    // Старый пользователь без ID получает его при старте (backfill).
    const { db } = s.app.ctx;
    const oldId = randomUUID();
    await db.query('INSERT INTO users (id, created_at) VALUES ($1, $2)', [oldId, new Date()]);
    await backfillPublicIds(db);
    const row = await db.one('SELECT public_id FROM users WHERE id = $1', [oldId]);
    assert.match(row.public_id, /^\d{4}$/);
  });

  test('AI proxy counts tokens (JSON + SSE), latency and errors; metrics endpoint and WS frame', async () => {
    const dev = await s.register();
    assert.equal((await ai(dev.token)).status, 200);
    const res = await fetch(`${s.base}/v1/ai/chat/completions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${dev.token}`, 'content-type': 'application/json' },
      body: JSON.stringify({ model: 'deepseek-chat', stream: true, messages: [{ role: 'user', content: 'x' }] }),
    });
    const text = await res.text();
    assert.match(text, /"usage"/, 'the usage chunk reaches the client too');
    assert.equal((await ai(dev.token, 'fail')).status, 500);
    await sleep(100); // запись usage — асинхронная

    const card = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
    assert.deepEqual(card.usage.today, { prompt: 30, completion: 10, total: 40, requests: 3, errors: 1 });
    assert.equal(card.usage.total.total, 40);
    assert.equal(card.aiTokens, 40);

    const m = (await s.api('GET', '/v1/admin/metrics', { token: admin })).body;
    for (const k of ['online', 'rps', 'tokensToday', 'tokensTotal', 'errorsToday', 'aiLatencyMs', 'model']) assert.ok(k in m, k);
    assert.ok(m.tokensToday >= 40);
    assert.ok(m.tokensTotal >= m.tokensToday);
    assert.ok(m.errorsToday >= 1);
    assert.ok(m.rps > 0);
    assert.equal(typeof m.aiLatencyMs.p50, 'number');
    assert.equal(typeof m.aiLatencyMs.p95, 'number');
    assert.equal(m.model.enabled, true);

    const sorted = (await s.api('GET', '/v1/admin/devices?sort=tokens', { token: admin })).body;
    assert.equal(sorted[0].deviceId, dev.deviceId);

    const ws = await s.connect(admin);
    const frame = await ws.waitFor((f) => f.t === 'metrics', 2000);
    assert.ok(frame.tokensToday >= 40);
    ws.close();
  });

  test('device events: install, update (from → to); overview counters', async () => {
    const before = (await s.api('GET', '/v1/admin/overview', { token: admin })).body;
    const dev = await s.register({ appVersion: '10.44.0' });
    await s.api('PATCH', '/v1/devices/me', { token: dev.token, body: { appVersion: '10.45.0' } });
    await s.api('PATCH', '/v1/devices/me', { token: dev.token, body: { appVersion: '10.45.0' } }); // без изменений
    const events = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}/events`, { token: admin })).body;
    assert.deepEqual(events.map((e) => [e.kind, e.fromVersion, e.toVersion]),
      [['update', '10.44.0', '10.45.0'], ['install', null, '10.44.0']]);
    const after = (await s.api('GET', '/v1/admin/overview', { token: admin })).body;
    assert.equal(after.updates - before.updates, 1);
    assert.equal(typeof after.inactive, 'number');
    assert.equal(after.inactiveDays, 7);
  });

  test('client reports: device posts, admin lists/filters, counts per device', async () => {
    const dev = await s.register();
    let r = await s.api('POST', '/v1/devices/me/report', {
      token: dev.token, body: { kind: 'crash', message: 'NullPointerException', stack: 'at a.b(C.kt:1)', appVersion: '10.45.0' },
    });
    assert.equal(r.status, 200);
    r = await s.api('POST', '/v1/devices/me/report', { token: dev.token, body: { kind: 'error', message: 'AI 503' } });
    assert.equal(r.status, 200);
    r = await s.api('POST', '/v1/devices/me/report', { token: dev.token, body: { kind: 'other', message: 'x' } });
    assert.equal(r.status, 400);

    const all = (await s.api('GET', `/v1/admin/reports?deviceId=${dev.deviceId}`, { token: admin })).body;
    assert.equal(all.length, 2);
    assert.equal(all[0].kind, 'error');
    assert.equal(all[1].stack, 'at a.b(C.kt:1)');
    assert.equal(all[1].appVersion, '10.45.0');
    assert.match(all[1].publicId, /^\d{4}$/);
    const crashes = (await s.api('GET', '/v1/admin/reports?kind=crash', { token: admin })).body;
    assert.ok(crashes.every((x) => x.kind === 'crash'));
    const row = (await s.api('GET', '/v1/admin/devices', { token: admin })).body.find((d) => d.deviceId === dev.deviceId);
    assert.equal(row.reports, 2);
  });

  test('block with reason and term: auto-unblock after `until`', async () => {
    const dev = await s.register();
    let r = await s.api('POST', `/v1/admin/devices/${dev.deviceId}/block`, {
      token: admin, body: { blocked: true, until: new Date(Date.now() - 1000).toISOString() },
    });
    assert.equal(r.status, 400, 'until in the past');
    const until = new Date(Date.now() + 1200).toISOString();
    r = await s.api('POST', `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: true, reason: 'Флуд', until } });
    assert.equal(r.status, 200);
    assert.equal(r.body.blockReason, 'Флуд');
    assert.equal(r.body.blockedUntil, until);
    const x = await s.api('GET', '/v1/chats', { token: dev.token });
    assert.equal(x.status, 403);
    assert.deepEqual(x.body, { error: 'blocked', message: 'Флуд', until });
    await sleep(1500);
    assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).status, 200, 'unblocked automatically');
    const card = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
    assert.equal(card.blocked, false);
    const actions = (await s.api('GET', `/v1/admin/actions?deviceId=${dev.deviceId}`, { token: admin })).body;
    assert.deepEqual(actions.map((a) => a.action).slice(0, 2), ['unblock_auto', 'block']);
    assert.equal(actions[1].detail.reason, 'Флуд');
  });

  test('overrides: admin sets, device gets them in register/PATCH and a WS frame', async () => {
    const installId = randomUUID();
    const dev = await s.register({ installId });
    assert.deepEqual(dev.overrides, {});
    const ws = await s.connect(dev.token);
    let r = await s.api('PATCH', `/v1/admin/devices/${dev.deviceId}/overrides`, {
      token: admin, body: { forceLanguage: 'en', disableSearch: true, maxMessagesPerDay: 50 },
    });
    assert.equal(r.status, 200);
    assert.deepEqual(r.body.overrides, { forceLanguage: 'en', disableSearch: true, maxMessagesPerDay: 50 });
    const f = await ws.waitFor((x) => x.t === 'overrides');
    assert.equal(f.overrides.maxMessagesPerDay, 50);
    r = await s.api('PATCH', `/v1/admin/devices/${dev.deviceId}/overrides`, { token: admin, body: { disableSearch: null } });
    assert.deepEqual(r.body.overrides, { forceLanguage: 'en', maxMessagesPerDay: 50 });
    r = await s.api('PATCH', `/v1/admin/devices/${dev.deviceId}/overrides`, { token: admin, body: { forceLanguage: 'de' } });
    assert.equal(r.status, 400);

    const again = await s.register({ installId });
    assert.deepEqual(again.overrides, { forceLanguage: 'en', maxMessagesPerDay: 50 });
    const me = await s.api('PATCH', '/v1/devices/me', { token: again.token, body: {} });
    assert.deepEqual(me.body.overrides, { forceLanguage: 'en', maxMessagesPerDay: 50 });
    const card = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
    assert.deepEqual(card.overrides, { forceLanguage: 'en', maxMessagesPerDay: 50 });
    ws.close();
  });

  test('notes CRUD and the action log (broadcast, notify)', async () => {
    const dev = await s.register();
    let r = await s.api('POST', `/v1/admin/devices/${dev.deviceId}/notes`, { token: admin, body: { text: '  Постоянный клиент  ' } });
    assert.equal(r.status, 201);
    const note = r.body;
    assert.equal(note.text, 'Постоянный клиент');
    r = await s.api('PATCH', `/v1/admin/notes/${note.id}`, { token: admin, body: { text: 'VIP' } });
    assert.equal(r.body.text, 'VIP');
    assert.ok(r.body.updatedAt);
    r = await s.api('GET', `/v1/admin/devices/${dev.deviceId}/notes`, { token: admin });
    assert.deepEqual(r.body.map((n) => n.text), ['VIP']);
    assert.equal((await s.api('DELETE', `/v1/admin/notes/${note.id}`, { token: admin })).status, 200);
    assert.equal((await s.api('DELETE', `/v1/admin/notes/${note.id}`, { token: admin })).status, 404);
    assert.equal((await s.api('GET', `/v1/admin/devices/${dev.deviceId}/notes`, { token: admin })).body.length, 0);

    await s.api('POST', '/v1/admin/notifications', { token: admin, body: { deviceId: dev.deviceId, title: 'Привет', body: 'Текст' } });
    await s.api('POST', '/v1/admin/notifications', { token: admin, body: { deviceId: null, title: 'Всем', body: 'Текст' } });
    const actions = (await s.api('GET', '/v1/admin/actions?limit=5', { token: admin })).body;
    assert.equal(actions[0].action, 'broadcast');
    assert.equal(actions[0].detail.title, 'Всем');
    assert.equal(actions[1].action, 'notify');
    assert.equal(actions[1].deviceId, dev.deviceId);
    assert.equal(actions[1].adminName, 'Администратор');
  });

  test('global AI switch: proxy → 503 ai_disabled, /health.aiEnabled, schedule validation', async () => {
    const dev = await s.register();
    let r = await s.api('POST', '/v1/admin/ai', { token: admin, body: { enabled: false } });
    assert.equal(r.status, 200);
    assert.equal(r.body.enabled, false);
    assert.equal(r.body.effective, false);
    r = await ai(dev.token);
    assert.equal(r.status, 503);
    assert.equal(r.body.error, 'ai_disabled');
    assert.equal(r.body.code, 'ai_disabled');
    assert.equal(r.body.message, 'ИИ временно отключён администратором.');
    assert.equal((await s.api('GET', '/health')).body.aiEnabled, false);
    assert.equal((await s.api('GET', '/v1/admin/metrics', { token: admin })).body.model.enabled, false);

    r = await s.api('POST', '/v1/admin/ai', { token: admin, body: { schedule: [{ days: [1], from: '25:00', to: '10:00' }] } });
    assert.equal(r.status, 400);
    r = await s.api('POST', '/v1/admin/ai', { token: admin, body: { timezone: 'Mars/Olympus' } });
    assert.equal(r.status, 400);
    r = await s.api('POST', '/v1/admin/ai', {
      token: admin, body: { enabled: true, schedule: [{ days: [7, 1, 2, 3, 4, 5, 6], from: '00:00', to: '00:00' }], timezone: 'UTC' },
    });
    assert.equal(r.body.effective, true);
    assert.deepEqual(r.body.schedule[0].days, [1, 2, 3, 4, 5, 6, 7]);
    assert.equal((await ai(dev.token)).status, 200);
    assert.equal((await s.api('GET', '/health')).body.aiEnabled, true);
    const got = (await s.api('GET', '/v1/admin/ai', { token: admin })).body;
    assert.equal(got.timezone, 'UTC');
    r = await s.api('POST', '/v1/admin/ai', { token: admin, body: { schedule: [] } });
    assert.deepEqual(r.body.schedule, []);
  });

  test('group AI stays silent while the AI is switched off', async () => {
    const dev = await s.register();
    await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/ai`, { token: admin, body: { enabled: true } });
    await s.api('POST', '/v1/admin/ai', { token: admin, body: { enabled: false } });
    const n = upstream.requests.length;
    await s.api('POST', `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: 'Honer, привет' } });
    await sleep(400);
    assert.equal(upstream.requests.length, n, 'no DeepSeek call');
    await s.api('POST', '/v1/admin/ai', { token: admin, body: { enabled: true } });
    await s.api('POST', `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: 'Honer, привет ещё раз' } });
    await sleep(400);
    assert.equal(upstream.requests.length, n + 1, 'answers again once enabled');
    await sleep(100);
    const card = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
    assert.equal(card.usage.today.total, 26, 'group AI tokens are counted for the chat device');
  });
});
