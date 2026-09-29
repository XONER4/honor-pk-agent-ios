import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startServer, ADMIN_KEY } from './helpers.js';

let s;
before(async () => {
  s = await startServer({ LOGIN_RATE_MAX: '3', GOOGLE_CLIENT_IDS: 'client-1.apps.googleusercontent.com' }, {
    // Подмена проверки Google ID token: "good-<email>" → payload с этим email.
    verifyGoogleIdToken: async (idToken, audience) => {
      assert.deepEqual(audience, ['client-1.apps.googleusercontent.com']);
      if (!idToken.startsWith('good-token-')) throw new Error('bad token');
      return { email: idToken.slice('good-token-'.length), email_verified: true, name: 'Big Boss' };
    },
  });
});
after(() => s.close());

test('health', async () => {
  const r = await s.api('GET', '/health');
  assert.equal(r.status, 200);
  assert.equal(r.body.ok, true);
  assert.equal(typeof r.body.ai, 'boolean'); // есть ли ключ нейросети на сервере
  assert.equal(r.headers.get('x-content-type-options'), 'nosniff');
});

test('unknown route and missing token use the error format', async () => {
  const r = await s.api('GET', '/v1/nope');
  assert.equal(r.status, 404);
  assert.equal(r.body.error, 'not_found');
  const u = await s.api('GET', '/v1/chats');
  assert.equal(u.status, 401);
  assert.equal(u.body.error, 'unauthorized');
  const bad = await s.api('GET', '/v1/chats', { token: 'd_notarealtokennotarealtoken' });
  assert.equal(bad.status, 401);
});

test('register validates input', async () => {
  const r = await s.api('POST', '/v1/devices/register', { body: { installId: 'x', platform: 'android' } });
  assert.equal(r.status, 400);
  assert.equal(r.body.error, 'bad_request');
  const p = await s.api('POST', '/v1/devices/register', { body: { installId: randomUUID(), platform: 'windows' } });
  assert.equal(p.status, 400);
});

test('register and re-register: same device, new token, old token revoked, installs counted once', async () => {
  const installId = randomUUID();
  const admin = await s.adminLogin();
  const before = (await s.api('GET', '/v1/admin/overview', { token: admin })).body;

  const a = await s.register({ installId, displayName: 'Первый' });
  assert.match(a.token, /^d_[A-Za-z0-9_-]{43}$/);
  assert.ok(a.deviceId && a.userId && a.adminChatId);

  const b = await s.register({ installId, displayName: 'Второй', appVersion: '10.45.0' });
  assert.equal(b.deviceId, a.deviceId);
  assert.equal(b.userId, a.userId);
  assert.equal(b.adminChatId, a.adminChatId);
  assert.notEqual(b.token, a.token);

  assert.equal((await s.api('GET', '/v1/chats', { token: a.token })).status, 401, 'old token revoked');
  assert.equal((await s.api('GET', '/v1/chats', { token: b.token })).status, 200);

  const after = (await s.api('GET', '/v1/admin/overview', { token: admin })).body;
  assert.equal(after.installs - before.installs, 1);
  assert.equal(after.users - before.users, 1);

  const d = (await s.api('GET', `/v1/admin/devices/${a.deviceId}`, { token: admin })).body;
  assert.equal(d.displayName, 'Второй');
  assert.equal(d.appVersion, '10.45.0');
  assert.equal(d.birthday, '2008-05-01');
  assert.equal(d.language, 'ru');
  assert.equal(d.osVersion, '14');
  assert.ok(d.licenseAcceptedAt);
  assert.equal(d.adminChatId, a.adminChatId);
});

test('PATCH /v1/devices/me and stats keep max', async () => {
  const dev = await s.register();
  const admin = await s.adminLogin();
  let r = await s.api('PATCH', '/v1/devices/me', { token: dev.token, body: { displayName: 'Новое имя', pushToken: 'fcm-1', birthday: null } });
  assert.equal(r.status, 200);
  r = await s.api('POST', '/v1/devices/me/stats', { token: dev.token, body: { messagesSent: 10, secondsInApp: 500 } });
  assert.equal(r.status, 200);
  await s.api('POST', '/v1/devices/me/stats', { token: dev.token, body: { messagesSent: 7, secondsInApp: 900 } });
  const d = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
  assert.equal(d.displayName, 'Новое имя');
  assert.equal(d.birthday, null);
  assert.equal(d.messagesSent, 10);
  assert.equal(d.secondsInApp, 900);
  const bad = await s.api('POST', '/v1/devices/me/stats', { token: dev.token, body: { messagesSent: -1 } });
  assert.equal(bad.status, 400);
});

test('admin login: adminKey, wrong key, device token cannot use admin API', async () => {
  const ok = await s.api('POST', '/v1/admin/login', { body: { adminKey: ADMIN_KEY } });
  assert.equal(ok.status, 200);
  assert.match(ok.body.token, /^a_/);
  assert.equal(ok.body.email, 'boss@example.com');
  assert.ok(ok.body.name);

  const dev = await s.register();
  const f = await s.api('GET', '/v1/admin/overview', { token: dev.token });
  assert.equal(f.status, 403);
  const f2 = await s.api('GET', '/v1/chats', { token: ok.body.token });
  assert.equal(f2.status, 403, 'admin token is not a device token');
  const empty = await s.api('POST', '/v1/admin/login', { body: {} });
  assert.equal(empty.status, 400);
});

test('admin login via Google ID token (email allow-list, case-insensitive)', async () => {
  const ok = await s.api('POST', '/v1/admin/login', { body: { googleIdToken: 'good-token-BOSS@example.com' } });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.email, 'boss@example.com');
  assert.equal(ok.body.name, 'Big Boss');
  const ov = await s.api('GET', '/v1/admin/overview', { token: ok.body.token });
  assert.equal(ov.status, 200);
  const stranger = await s.api('POST', '/v1/admin/login', { body: { googleIdToken: 'good-token-evil@example.com' } });
  assert.equal(stranger.status, 401);
});

test('admin login is rate limited after repeated failures', async () => {
  const hdr = { 'x-forwarded-for': '10.9.9.9' };
  for (let i = 0; i < 3; i++) {
    const r = await s.api('POST', '/v1/admin/login', { body: { adminKey: 'wrong' }, headers: hdr });
    assert.equal(r.status, 401);
  }
  const r = await s.api('POST', '/v1/admin/login', { body: { adminKey: ADMIN_KEY }, headers: hdr });
  assert.equal(r.status, 429);
  assert.equal(r.body.error, 'rate_limited');
});

test('block → 403 on every device endpoint (incl. re-register), unblock restores access', async () => {
  const admin = await s.adminLogin();
  const installId = randomUUID();
  const dev = await s.register({ installId });
  const r = await s.api('POST', `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: true, reason: 'Спам' } });
  assert.equal(r.status, 200);
  assert.equal(r.body.blocked, true);
  assert.equal(r.body.blockReason, 'Спам');

  for (const [m, u, body] of [
    ['GET', '/v1/chats'], ['GET', `/v1/chats/${dev.adminChatId}/messages`], ['PATCH', '/v1/devices/me', {}],
    ['GET', '/v1/notifications'], ['POST', '/v1/ai/chat/completions', { model: 'deepseek-chat', messages: [{ role: 'user', content: 'x' }] }],
  ]) {
    const x = await s.api(m, u, { token: dev.token, body });
    assert.equal(x.status, 403, `${m} ${u}`);
    assert.deepEqual(x.body, { error: 'blocked', message: 'Спам' });
  }
  const re = await s.api('POST', '/v1/devices/register', { body: { installId, platform: 'android' } });
  assert.equal(re.status, 403);
  assert.equal(re.body.error, 'blocked');

  const list = (await s.api('GET', '/v1/admin/devices?status=blocked', { token: admin })).body;
  assert.ok(list.some((d) => d.deviceId === dev.deviceId));
  const ov = (await s.api('GET', '/v1/admin/overview', { token: admin })).body;
  assert.ok(ov.blocked >= 1);

  await s.api('POST', `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: false } });
  assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).status, 200);
});

test('admin devices list: search, fields, unreadForAdmin', async () => {
  const admin = await s.adminLogin();
  const dev = await s.register({ displayName: 'Уникальный Пётр', deviceModel: 'Pixel 9' });
  await s.api('POST', `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: 'привет' } });
  await s.api('POST', `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: 'есть кто?' } });

  const r = await s.api('GET', `/v1/admin/devices?query=${encodeURIComponent('уникальный')}`, { token: admin });
  assert.equal(r.status, 200);
  assert.equal(r.body.length, 1);
  const d = r.body[0];
  for (const k of ['deviceId', 'userId', 'displayName', 'deviceModel', 'deviceName', 'platform', 'appVersion', 'installedAt',
    'lastSeen', 'presence', 'typingIn', 'blocked', 'messagesSent', 'secondsInApp', 'unreadForAdmin', 'adminChatId']) {
    assert.ok(k in d, `missing ${k}`);
  }
  assert.equal(d.unreadForAdmin, 2);
  assert.equal(d.presence, 'offline');
  assert.equal(d.deviceModel, 'Pixel 9');

  const detail = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
  assert.equal(detail.installs, 1);
  const missing = await s.api('GET', `/v1/admin/devices/${randomUUID()}`, { token: admin });
  assert.equal(missing.status, 404);
  const badId = await s.api('GET', '/v1/admin/devices/not-a-uuid', { token: admin });
  assert.equal(badId.status, 400);

  const ov = (await s.api('GET', '/v1/admin/overview', { token: admin })).body;
  for (const k of ['users', 'installs', 'online', 'inBackground', 'blocked', 'messagesToday']) assert.equal(typeof ov[k], 'number');
  assert.ok(ov.messagesToday >= 2);
});
