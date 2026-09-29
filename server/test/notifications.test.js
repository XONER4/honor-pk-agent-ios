import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startServer, sleep } from './helpers.js';

let s;
let admin;
const pushed = [];
before(async () => {
  // Подменяем FCM-транспорт: проверяем, кому и что отправилось бы.
  s = await startServer({}, {
    pushTransport: {
      async send(token, data) {
        pushed.push({ token, data });
        return token === 'dead-token' ? 'invalid_token' : 'ok';
      },
    },
  });
  admin = await s.adminLogin();
});
after(() => s.close());

test('personal and broadcast notifications: WS frame, list, after, read', async () => {
  const a = await s.register();
  const b = await s.register();
  const aws = await s.connect(a.token);

  let r = await s.api('POST', '/v1/admin/notifications', { token: admin, body: { deviceId: a.deviceId, title: 'Лично', body: 'Только тебе' } });
  assert.equal(r.status, 200);
  assert.equal(r.body.count, 1);
  const frame = await aws.waitFor((f) => f.t === 'notification');
  assert.equal(frame.notification.title, 'Лично');
  assert.equal(frame.notification.kind, 'admin');
  assert.equal(frame.notification.read, false);
  assert.equal(frame.notification.chatId, null);

  await sleep(5);
  r = await s.api('POST', '/v1/admin/notifications', { token: admin, body: { deviceId: null, title: 'Всем', body: 'Обновление' } });
  assert.equal(r.status, 200);
  assert.ok(r.body.count >= 2);

  const la = (await s.api('GET', '/v1/notifications', { token: a.token })).body;
  assert.deepEqual(la.map((n) => n.title), ['Лично', 'Всем'], 'oldest first');
  const lb = (await s.api('GET', '/v1/notifications', { token: b.token })).body;
  assert.deepEqual(lb.map((n) => n.title), ['Всем']);

  const afterFirst = (await s.api('GET', `/v1/notifications?after=${encodeURIComponent(la[0].createdAt)}`, { token: a.token })).body;
  assert.deepEqual(afterFirst.map((n) => n.title), ['Всем']);
  const afterLast = (await s.api('GET', `/v1/notifications?after=${encodeURIComponent(la[1].createdAt)}`, { token: a.token })).body;
  assert.equal(afterLast.length, 0, 'cursor does not repeat the last item');

  r = await s.api('POST', '/v1/notifications/read', { token: a.token, body: { ids: [la[0].id, lb[0].id] } });
  assert.equal(r.status, 200);
  const la2 = (await s.api('GET', '/v1/notifications', { token: a.token })).body;
  assert.deepEqual(la2.map((n) => n.read), [true, false]);
  const lb2 = (await s.api('GET', '/v1/notifications', { token: b.token })).body;
  assert.equal(lb2[0].read, false, 'cannot mark notifications of another device');

  assert.equal((await s.api('POST', '/v1/admin/notifications', { token: admin, body: { deviceId: randomUUID(), title: 'x', body: 'y' } })).status, 404);
  assert.equal((await s.api('POST', '/v1/admin/notifications', { token: a.token, body: { title: 'x', body: 'y' } })).status, 403);
  assert.equal((await s.api('GET', '/v1/notifications?after=yesterday', { token: a.token })).status, 400);
  aws.close();
});

test('push goes only to devices not in foreground; invalid tokens are cleared', async () => {
  const dev = await s.register({ pushToken: 'tok-1' });
  const ws = await s.connect(dev.token);
  ws.send({ t: 'presence', state: 'foreground' });
  await sleep(50);
  pushed.length = 0;

  await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: 'fg' } });
  await sleep(50);
  assert.equal(pushed.length, 0, 'foreground → no push');

  ws.send({ t: 'presence', state: 'background' });
  await sleep(50);
  await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: 'Ответ админа' } });
  await sleep(50);
  assert.equal(pushed.length, 1);
  assert.deepEqual(pushed[0], { token: 'tok-1', data: { type: 'message', chatId: dev.adminChatId, title: 'Администратор', body: 'Ответ админа' } });

  // Сообщения самого пользователя не пушатся ему же.
  await s.api('POST', `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: 'моё' } });
  await sleep(50);
  assert.equal(pushed.length, 1);

  // Уведомление админа → push type notification.
  await s.api('POST', '/v1/admin/notifications', { token: admin, body: { deviceId: dev.deviceId, title: 'T', body: 'B' } });
  await sleep(50);
  assert.deepEqual(pushed[1].data, { type: 'notification', chatId: '', title: 'T', body: 'B' });
  ws.close();

  // Протухший токен удаляется.
  const dead = await s.register({ pushToken: 'dead-token' });
  await s.api('POST', `/v1/admin/chats/${dead.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: 'x' } });
  await sleep(50);
  const pushedBefore = pushed.length;
  await s.api('POST', `/v1/admin/chats/${dead.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: 'y' } });
  await sleep(50);
  assert.equal(pushed.length, pushedBefore, 'token cleared after invalid_token');
});
