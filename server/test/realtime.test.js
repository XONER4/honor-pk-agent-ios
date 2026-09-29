import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startServer, sleep } from './helpers.js';

let s;
let admin;
before(async () => {
  s = await startServer({ PRESENCE_OFFLINE_MS: '300', TYPING_TTL_MS: '400' });
  admin = await s.adminLogin();
});
after(() => s.close());

const send = (token, chatId, text, prefix = '/v1/chats') =>
  s.api('POST', `${prefix}/${chatId}/messages`, { token, body: { clientId: randomUUID(), text } });

test('WS rejects missing/invalid token', async () => {
  await assert.rejects(s.connect('d_invalidinvalidinvalidinvalid'));
});

test('ping/pong', async () => {
  const dev = await s.register();
  const ws = await s.connect(dev.token);
  ws.send({ t: 'ping' });
  await ws.waitFor((f) => f.t === 'pong');
  ws.close();
});

test('message frames reach device and every admin socket; read receipts both ways', async () => {
  const dev = await s.register();
  const chatId = dev.adminChatId;
  const dws = await s.connect(dev.token);
  const aws = await s.connect(admin);

  const m1 = (await send(dev.token, chatId, 'вопрос')).body;
  const fAdmin = await aws.waitFor((f) => f.t === 'message' && f.message.id === m1.id);
  assert.equal(fAdmin.chatId, chatId);
  await dws.waitFor((f) => f.t === 'message' && f.message.id === m1.id);

  // Админ читает → устройство получает read who:admin, readByPeer становится true.
  aws.send({ t: 'read', chatId, messageId: m1.id });
  const rf = await dws.waitFor((f) => f.t === 'read' && f.who === 'admin');
  assert.deepEqual(rf, { t: 'read', chatId, who: 'admin', messageId: m1.id });
  const msgs = (await s.api('GET', `/v1/chats/${chatId}/messages`, { token: dev.token })).body;
  assert.equal(msgs[0].readByPeer, true);
  const devChat = (await s.api('GET', '/v1/chats', { token: dev.token })).body[0];
  assert.equal(devChat.peerReadUpTo, m1.id);
  const adminChat = (await s.api('GET', '/v1/admin/chats', { token: admin })).body.find((c) => c.id === chatId);
  assert.equal(adminChat.unread, 0);

  // Админ отвечает → устройство читает → админ получает read who:user.
  const m2 = (await send(admin, chatId, 'ответ', '/v1/admin/chats')).body;
  await dws.waitFor((f) => f.t === 'message' && f.message.id === m2.id);
  assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).body[0].unread, 1);
  dws.send({ t: 'read', chatId, messageId: m2.id });
  await aws.waitFor((f) => f.t === 'read' && f.who === 'user' && f.messageId === m2.id);
  assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).body[0].unread, 0);
  const adminMsgs = (await s.api('GET', `/v1/admin/chats/${chatId}/messages`, { token: admin })).body;
  assert.equal(adminMsgs.find((m) => m.id === m2.id).readByPeer, true);

  // Устройство не может слать read в чужой чат.
  const other = await s.register();
  const before = aws.frames.length;
  dws.send({ t: 'read', chatId: other.adminChatId, messageId: m2.id });
  await sleep(100);
  assert.equal(aws.frames.slice(before).filter((f) => f.t === 'read').length, 0);

  // message.updated и chat.cleared тоже приходят.
  await s.api('POST', `/v1/chats/${chatId}/messages/${m2.id}/reaction`, { token: dev.token, body: { emoji: '🔥' } });
  const upd = await aws.waitFor((f) => f.t === 'message.updated' && f.message.id === m2.id);
  assert.deepEqual(upd.message.reactions, { '🔥': ['user'] });
  await s.api('DELETE', `/v1/chats/${chatId}/messages/${m1.id}?scope=everyone`, { token: dev.token });
  const del = await aws.waitFor((f) => f.t === 'message.updated' && f.message.id === m1.id);
  assert.equal(del.message.deleted, true);
  await s.api('POST', `/v1/admin/chats/${chatId}/clear?scope=everyone`, { token: admin });
  await dws.waitFor((f) => f.t === 'chat.cleared' && f.chatId === chatId);
  dws.close();
  aws.close();
});

test('typing frames, TTL and Chat.peerTyping', async () => {
  const dev = await s.register();
  const chatId = dev.adminChatId;
  const dws = await s.connect(dev.token);
  const aws = await s.connect(admin);

  dws.send({ t: 'typing', chatId, typing: true });
  await aws.waitFor((f) => f.t === 'typing' && f.chatId === chatId && f.who === 'user' && f.typing === true);
  await aws.waitFor((f) => f.t === 'presence' && f.deviceId === dev.deviceId && f.typingIn === chatId);
  const ac = (await s.api('GET', '/v1/admin/chats', { token: admin })).body.find((c) => c.id === chatId);
  assert.equal(ac.peerTyping, true);
  // TTL: без typing:false индикатор гаснет сам.
  await aws.waitFor((f) => f.t === 'typing' && f.who === 'user' && f.typing === false, 2000);

  aws.send({ t: 'typing', chatId, typing: true });
  await dws.waitFor((f) => f.t === 'typing' && f.who === 'admin' && f.typing === true);
  assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).body[0].peerTyping, true);
  // Отправка сообщения гасит индикатор отправителя.
  await send(admin, chatId, 'готово', '/v1/admin/chats');
  await dws.waitFor((f) => f.t === 'typing' && f.who === 'admin' && f.typing === false);
  assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).body[0].peerTyping, false);
  dws.close();
  aws.close();
});

test('presence: background → foreground → offline after timeout; lastSeen; overview counters', async () => {
  const dev = await s.register();
  const aws = await s.connect(admin);
  const dws = await s.connect(dev.token);

  await aws.waitFor((f) => f.t === 'presence' && f.deviceId === dev.deviceId && f.state === 'background');
  dws.send({ t: 'presence', state: 'foreground' });
  const fg = await aws.waitFor((f) => f.t === 'presence' && f.deviceId === dev.deviceId && f.state === 'foreground');
  assert.ok(fg.lastSeen);
  assert.equal(fg.typingIn, null);

  let d = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
  assert.equal(d.presence, 'foreground');
  const ov = (await s.api('GET', '/v1/admin/overview', { token: admin })).body;
  assert.ok(ov.online >= 1);
  const online = (await s.api('GET', '/v1/admin/devices?status=online', { token: admin })).body;
  assert.ok(online.some((x) => x.deviceId === dev.deviceId));
  const devChat = (await s.api('GET', '/v1/chats', { token: dev.token })).body[0];
  assert.equal(devChat.peerPresence, 'background', 'admin socket connected, no presence frame yet');

  dws.send({ t: 'presence', state: 'background' });
  await aws.waitFor((f) => f.t === 'presence' && f.deviceId === dev.deviceId && f.state === 'background' && aws.frames.indexOf(f) > aws.frames.indexOf(fg));

  // Сокет закрыт → ещё не offline; через PRESENCE_OFFLINE_MS (300 мс) → offline.
  const closedAt = Date.now();
  dws.close();
  await sleep(100);
  d = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
  assert.equal(d.presence, 'background');
  const off = await aws.waitFor((f) => f.t === 'presence' && f.deviceId === dev.deviceId && f.state === 'offline', 2000);
  assert.ok(Date.now() - closedAt >= 250);
  assert.ok(Math.abs(Date.parse(off.lastSeen) - closedAt) < 1000);
  d = (await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
  assert.equal(d.presence, 'offline');
  assert.equal(d.lastSeen, off.lastSeen);

  // Переподключение в пределах таймаута не даёт offline.
  const dws2 = await s.connect(dev.token);
  dws2.send({ t: 'presence', state: 'foreground' });
  dws2.close();
  await sleep(100);
  const dws3 = await s.connect(dev.token);
  await sleep(400);
  assert.notEqual((await s.api('GET', `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body.presence, 'offline');
  dws3.close();
  aws.close();
});

test('admin sees admin-presence from device side', async () => {
  const dev = await s.register();
  const aws = await s.connect(admin);
  aws.send({ t: 'presence', state: 'foreground' });
  await sleep(50);
  const c = (await s.api('GET', '/v1/chats', { token: dev.token })).body[0];
  assert.equal(c.peerPresence, 'foreground');
  assert.ok(c.peerLastSeen);
  aws.close();
});

test('blocking kicks the socket with a blocked frame; blocked device gets blocked frame on connect', async () => {
  const dev = await s.register();
  const dws = await s.connect(dev.token);
  await s.api('POST', `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: true, reason: 'Нарушение' } });
  const f = await dws.waitFor((x) => x.t === 'blocked');
  assert.equal(f.message, 'Нарушение');
  const c = await dws.waitClose();
  assert.equal(c.code, 4003);

  const again = await s.connect(dev.token);
  const f2 = await again.waitFor((x) => x.t === 'blocked');
  assert.equal(f2.message, 'Нарушение');
  await again.waitClose();
});
