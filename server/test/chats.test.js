import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startServer } from './helpers.js';

let s;
let admin;
before(async () => {
  s = await startServer();
  admin = await s.adminLogin();
});
after(() => s.close());

const send = (token, chatId, body, prefix = '/v1/chats') =>
  s.api('POST', `${prefix}/${chatId}/messages`, { token, body: { clientId: randomUUID(), ...body } });
const list = async (token, chatId, prefix = '/v1/chats', qs = '') =>
  (await s.api('GET', `${prefix}/${chatId}/messages${qs}`, { token })).body;

test('GET /v1/chats returns the admin chat with the full Chat model', async () => {
  const dev = await s.register();
  const r = await s.api('GET', '/v1/chats', { token: dev.token });
  assert.equal(r.status, 200);
  assert.equal(r.body.length, 1);
  const c = r.body[0];
  assert.equal(c.id, dev.adminChatId);
  assert.equal(c.kind, 'admin');
  assert.equal(c.deviceId, dev.deviceId);
  assert.equal(c.aiEnabled, false);
  assert.equal(c.pinnedMessageId, null);
  assert.equal(c.lastMessage, null);
  assert.equal(c.unread, 0);
  assert.equal(c.peerTyping, false);
  assert.equal(c.peerReadUpTo, null);
  assert.equal(c.peerPresence, 'offline');
  assert.ok('peerLastSeen' in c && 'title' in c);
});

test('send, list, idempotent clientId, reply, validation', async () => {
  const dev = await s.register();
  const chatId = dev.adminChatId;
  const clientId = randomUUID();
  const r1 = await s.api('POST', `/v1/chats/${chatId}/messages`, { token: dev.token, body: { clientId, text: 'Привет!' } });
  assert.equal(r1.status, 200);
  const m = r1.body;
  assert.equal(m.sender, 'user');
  assert.equal(m.clientId, clientId);
  assert.equal(m.chatId, chatId);
  assert.equal(m.text, 'Привет!');
  assert.deepEqual(m.attachments, []);
  assert.deepEqual(m.reactions, {});
  assert.equal(m.deleted, false);
  assert.equal(m.pinned, false);
  assert.equal(m.readByPeer, false);
  assert.equal(m.editedAt, null);
  assert.equal(m.replyTo, null);
  assert.ok(!Number.isNaN(Date.parse(m.createdAt)));

  const r2 = await s.api('POST', `/v1/chats/${chatId}/messages`, { token: dev.token, body: { clientId, text: 'Привет!' } });
  assert.equal(r2.status, 200);
  assert.equal(r2.body.id, m.id, 'same clientId → same message');
  assert.equal((await list(dev.token, chatId)).length, 1);

  const reply = await send(admin, chatId, { text: 'Здравствуйте', replyTo: m.id }, '/v1/admin/chats');
  assert.equal(reply.status, 200);
  assert.equal(reply.body.sender, 'admin');
  assert.equal(reply.body.replyTo, m.id);

  const msgs = await list(dev.token, chatId);
  assert.deepEqual(msgs.map((x) => x.text), ['Привет!', 'Здравствуйте'], 'newest last');

  assert.equal((await send(dev.token, chatId, { text: '   ' })).status, 400, 'empty message');
  assert.equal((await send(dev.token, chatId, { text: 'x', replyTo: randomUUID() })).status, 404, 'unknown replyTo');
  assert.equal((await send(dev.token, chatId, { text: 'x'.repeat(10_001) })).status, 400, 'too long');

  // Чужой чат недоступен.
  const other = await s.register();
  assert.equal((await send(other.token, chatId, { text: 'hack' })).status, 404);
  assert.equal((await s.api('GET', `/v1/chats/${chatId}/messages`, { token: other.token })).status, 404);

  // Чат с точки зрения админа.
  const chats = (await s.api('GET', '/v1/admin/chats', { token: admin })).body;
  const c = chats.find((x) => x.id === chatId);
  assert.equal(c.title, 'Иван');
  assert.equal(c.unread, 1);
  assert.equal(c.lastMessage.text, 'Здравствуйте');
  const devChat = (await s.api('GET', '/v1/chats', { token: dev.token })).body[0];
  assert.equal(devChat.unread, 1);
});

test('pagination with before & limit', async () => {
  const dev = await s.register();
  const ids = [];
  for (let i = 0; i < 7; i++) ids.push((await send(dev.token, dev.adminChatId, { text: `m${i}` })).body.id);
  const last3 = await list(dev.token, dev.adminChatId, '/v1/chats', '?limit=3');
  assert.deepEqual(last3.map((m) => m.text), ['m4', 'm5', 'm6']);
  const prev = await list(dev.token, dev.adminChatId, '/v1/chats', `?limit=3&before=${last3[0].id}`);
  assert.deepEqual(prev.map((m) => m.text), ['m1', 'm2', 'm3']);
  const bad = await s.api('GET', `/v1/chats/${dev.adminChatId}/messages?limit=1000`, { token: dev.token });
  assert.equal(bad.status, 400);
});

test('reactions: one per side, replace and remove', async () => {
  const dev = await s.register();
  const m = (await send(dev.token, dev.adminChatId, { text: 'реакции' })).body;
  let r = await s.api('POST', `/v1/chats/${dev.adminChatId}/messages/${m.id}/reaction`, { token: dev.token, body: { emoji: '👍' } });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.reactions, { '👍': ['user'] });
  r = await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/messages/${m.id}/reaction`, { token: admin, body: { emoji: '👍' } });
  assert.deepEqual(r.body.reactions, { '👍': ['user', 'admin'] });
  r = await s.api('POST', `/v1/chats/${dev.adminChatId}/messages/${m.id}/reaction`, { token: dev.token, body: { emoji: '❤️' } });
  assert.deepEqual(r.body.reactions, { '👍': ['admin'], '❤️': ['user'] });
  r = await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/messages/${m.id}/reaction`, { token: admin, body: { emoji: null } });
  assert.deepEqual(r.body.reactions, { '❤️': ['user'] });
  const listed = await list(dev.token, dev.adminChatId);
  assert.deepEqual(listed[0].reactions, { '❤️': ['user'] });
});

test('pin: single pinned message per chat', async () => {
  const dev = await s.register();
  const a = (await send(dev.token, dev.adminChatId, { text: 'A' })).body;
  const b = (await send(dev.token, dev.adminChatId, { text: 'B' })).body;
  let r = await s.api('POST', `/v1/chats/${dev.adminChatId}/messages/${a.id}/pin`, { token: dev.token, body: { pinned: true } });
  assert.equal(r.status, 200);
  assert.equal(r.body.pinned, true);
  r = await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/messages/${b.id}/pin`, { token: admin, body: { pinned: true } });
  assert.equal(r.body.pinned, true);
  let msgs = await list(dev.token, dev.adminChatId);
  assert.deepEqual(msgs.map((m) => m.pinned), [false, true]);
  assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).body[0].pinnedMessageId, b.id);
  await s.api('POST', `/v1/chats/${dev.adminChatId}/messages/${b.id}/pin`, { token: dev.token, body: { pinned: false } });
  msgs = await list(dev.token, dev.adminChatId);
  assert.deepEqual(msgs.map((m) => m.pinned), [false, false]);
  assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).body[0].pinnedMessageId, null);
});

test('edit own message (extension)', async () => {
  const dev = await s.register();
  const m = (await send(dev.token, dev.adminChatId, { text: 'опечатка' })).body;
  const r = await s.api('PATCH', `/v1/chats/${dev.adminChatId}/messages/${m.id}`, { token: dev.token, body: { text: 'исправлено' } });
  assert.equal(r.status, 200);
  assert.equal(r.body.text, 'исправлено');
  assert.ok(r.body.editedAt);
  const f = await s.api('PATCH', `/v1/admin/chats/${dev.adminChatId}/messages/${m.id}`, { token: admin, body: { text: 'нет' } });
  assert.equal(f.status, 403);
});

test('delete for me / for everyone with permissions', async () => {
  const dev = await s.register();
  const chatId = dev.adminChatId;
  const mine = (await send(dev.token, chatId, { text: 'моё' })).body;
  const adm = (await send(admin, chatId, { text: 'от админа' }, '/v1/admin/chats')).body;
  const other = (await send(dev.token, chatId, { text: 'ещё' })).body;

  // Пользователь не может удалить у всех чужое сообщение.
  let r = await s.api('DELETE', `/v1/chats/${chatId}/messages/${adm.id}?scope=everyone`, { token: dev.token });
  assert.equal(r.status, 403);
  // …но может скрыть у себя.
  r = await s.api('DELETE', `/v1/chats/${chatId}/messages/${adm.id}?scope=me`, { token: dev.token });
  assert.equal(r.status, 200);
  assert.deepEqual((await list(dev.token, chatId)).map((m) => m.text), ['моё', 'ещё']);
  assert.equal((await list(admin, chatId, '/v1/admin/chats')).length, 3, 'admin still sees it');

  // Удаление у всех своего сообщения → tombstone deleted:true у обеих сторон.
  await s.api('POST', `/v1/chats/${chatId}/messages/${mine.id}/reaction`, { token: dev.token, body: { emoji: '👍' } });
  r = await s.api('DELETE', `/v1/chats/${chatId}/messages/${mine.id}?scope=everyone`, { token: dev.token });
  assert.equal(r.status, 200);
  const adminView = await list(admin, chatId, '/v1/admin/chats');
  const tomb = adminView.find((m) => m.id === mine.id);
  assert.equal(tomb.deleted, true);
  assert.equal(tomb.text, '');
  assert.deepEqual(tomb.reactions, {});

  // Админ может удалить у всех любое сообщение.
  r = await s.api('DELETE', `/v1/admin/chats/${chatId}/messages/${other.id}?scope=everyone`, { token: admin });
  assert.equal(r.status, 200);
  assert.equal((await list(dev.token, chatId)).find((m) => m.id === other.id).deleted, true);

  r = await s.api('DELETE', `/v1/chats/${chatId}/messages/${other.id}?scope=all`, { token: dev.token });
  assert.equal(r.status, 400, 'invalid scope');
});

test('clear for me / for everyone', async () => {
  const dev = await s.register();
  const chatId = dev.adminChatId;
  await send(dev.token, chatId, { text: '1' });
  await send(admin, chatId, { text: '2' }, '/v1/admin/chats');

  let r = await s.api('POST', `/v1/chats/${chatId}/clear?scope=everyone`, { token: dev.token });
  assert.equal(r.status, 403, 'only admin clears for everyone');

  r = await s.api('POST', `/v1/chats/${chatId}/clear?scope=me`, { token: dev.token });
  assert.equal(r.status, 200);
  assert.equal((await list(dev.token, chatId)).length, 0);
  assert.equal((await list(admin, chatId, '/v1/admin/chats')).length, 2);
  const devChat = (await s.api('GET', '/v1/chats', { token: dev.token })).body[0];
  assert.equal(devChat.unread, 0);
  assert.equal(devChat.lastMessage, null);

  await send(dev.token, chatId, { text: '3' });
  assert.deepEqual((await list(dev.token, chatId)).map((m) => m.text), ['3']);

  r = await s.api('POST', `/v1/admin/chats/${chatId}/clear?scope=everyone`, { token: admin });
  assert.equal(r.status, 200);
  assert.equal((await list(admin, chatId, '/v1/admin/chats')).length, 0);
  assert.equal((await list(dev.token, chatId)).length, 0);
});

test('empty JSON body is accepted, malformed JSON gets the error format', async () => {
  const dev = await s.register();
  let r = await fetch(`${s.base}/v1/chats/${dev.adminChatId}/clear?scope=me`, {
    method: 'POST', headers: { authorization: `Bearer ${dev.token}`, 'content-type': 'application/json' },
  });
  assert.equal(r.status, 200);
  r = await fetch(`${s.base}/v1/chats/${dev.adminChatId}/messages`, {
    method: 'POST', headers: { authorization: `Bearer ${dev.token}`, 'content-type': 'application/json' }, body: '{"clientId":',
  });
  assert.equal(r.status, 400);
  assert.equal((await r.json()).error, 'bad_request');
  r = await fetch(`${s.base}/v1/chats/${dev.adminChatId}/messages`, {
    method: 'POST', headers: { authorization: `Bearer ${dev.token}`, 'content-type': 'application/json' },
    body: '{"clientId":"x","text":"hi","__proto__":{"polluted":true}}',
  });
  assert.equal(r.status, 400, 'prototype poisoning rejected');
});
