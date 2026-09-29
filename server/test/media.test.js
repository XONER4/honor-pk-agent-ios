import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { startServer } from './helpers.js';
import { parseRange } from '../src/media.js';

let s;
let admin;
before(async () => {
  s = await startServer({ MAX_UPLOAD_BYTES: String(64 * 1024) });
  admin = await s.adminLogin();
});
after(() => s.close());

const bytes = (n) => Buffer.from(Array.from({ length: n }, (_, i) => i % 256));

async function upload(token, buf, { name = 'video.mp4', type = 'video/mp4', fields = {} } = {}) {
  const fd = new FormData();
  for (const [k, v] of Object.entries(fields)) fd.append(k, String(v));
  fd.append('file', new Blob([buf], { type }), name);
  return s.api('POST', '/v1/media', { token, body: fd });
}

test('parseRange unit', () => {
  assert.equal(parseRange(undefined, 100), null);
  assert.deepEqual(parseRange('bytes=0-9', 100), { start: 0, end: 9 });
  assert.deepEqual(parseRange('bytes=90-', 100), { start: 90, end: 99 });
  assert.deepEqual(parseRange('bytes=-10', 100), { start: 90, end: 99 });
  assert.deepEqual(parseRange('bytes=50-500', 100), { start: 50, end: 99 });
  assert.equal(parseRange('bytes=100-', 100), 'unsatisfiable');
  assert.equal(parseRange('bytes=5-1', 100), 'unsatisfiable');
  assert.equal(parseRange('bytes=0-1,5-6', 100), null);
});

test('upload → AttachmentRef, attach to message, range download, access control', async () => {
  const dev = await s.register();
  const data = bytes(10_000);
  const up = await upload(dev.token, data, { fields: { durationMs: 1500, width: 640, height: 360 } });
  assert.equal(up.status, 201);
  const ref = up.body;
  assert.equal(ref.kind, 'video');
  assert.equal(ref.name, 'video.mp4');
  assert.equal(ref.mime, 'video/mp4');
  assert.equal(ref.size, 10_000);
  assert.equal(ref.durationMs, 1500);
  assert.equal(ref.width, 640);
  assert.equal(ref.height, 360);
  assert.equal(ref.url, `/v1/media/${ref.id}`);

  // Полная загрузка (владелец).
  let r = await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${dev.token}` } });
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('accept-ranges'), 'bytes');
  assert.equal(r.headers.get('content-type'), 'video/mp4');
  assert.ok(Buffer.from(await r.arrayBuffer()).equals(data));

  // Range (перемотка видео).
  r = await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${dev.token}`, range: 'bytes=100-199' } });
  assert.equal(r.status, 206);
  assert.equal(r.headers.get('content-range'), 'bytes 100-199/10000');
  assert.equal(r.headers.get('content-length'), '100');
  assert.ok(Buffer.from(await r.arrayBuffer()).equals(data.subarray(100, 200)));
  r = await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${dev.token}`, range: 'bytes=20000-' } });
  assert.equal(r.status, 416);
  assert.equal(r.headers.get('content-range'), 'bytes */10000');

  // ?token= тоже работает (для плееров без заголовков).
  r = await fetch(`${s.base}${ref.url}?token=${dev.token}`, { headers: { range: 'bytes=-5' } });
  assert.equal(r.status, 206);
  assert.ok(Buffer.from(await r.arrayBuffer()).equals(data.subarray(9995)));

  // Без авторизации / чужое устройство — нельзя.
  assert.equal((await fetch(s.base + ref.url)).status, 401);
  const stranger = await s.register();
  assert.equal((await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${stranger.token}` } })).status, 403);
  // Чужое вложение нельзя приложить к своему сообщению.
  const steal = await s.api('POST', `/v1/chats/${stranger.adminChatId}/messages`, {
    token: stranger.token, body: { clientId: randomUUID(), attachments: [{ id: ref.id }] },
  });
  assert.equal(steal.status, 403);

  // Админ видит любое медиа.
  assert.equal((await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${admin}` } })).status, 200);

  // Сообщение с вложением (клиент может уточнить kind, например voice).
  const msg = await s.api('POST', `/v1/chats/${dev.adminChatId}/messages`, {
    token: dev.token, body: { clientId: randomUUID(), text: '', attachments: [{ id: ref.id, kind: 'voice' }] },
  });
  assert.equal(msg.status, 200);
  assert.equal(msg.body.attachments.length, 1);
  assert.equal(msg.body.attachments[0].id, ref.id);
  assert.equal(msg.body.attachments[0].kind, 'voice');
  assert.equal(msg.body.attachments[0].size, 10_000);
  assert.equal(msg.body.attachments[0].url, ref.url);

  // Медиа админа в чате устройства становится доступно этому устройству, но не другим.
  const aup = await upload(admin, bytes(300), { name: 'photo.jpg', type: 'image/jpeg' });
  assert.equal(aup.body.kind, 'image');
  assert.equal((await fetch(s.base + aup.body.url, { headers: { authorization: `Bearer ${dev.token}` } })).status, 403);
  await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/messages`, {
    token: admin, body: { clientId: randomUUID(), text: 'фото', attachments: [{ id: aup.body.id }] },
  });
  assert.equal((await fetch(s.base + aup.body.url, { headers: { authorization: `Bearer ${dev.token}` } })).status, 200);
  assert.equal((await fetch(s.base + aup.body.url, { headers: { authorization: `Bearer ${stranger.token}` } })).status, 403);

  // Неизвестный id.
  assert.equal((await fetch(`${s.base}/v1/media/${randomUUID()}`, { headers: { authorization: `Bearer ${admin}` } })).status, 404);
});

test('upload limits and validation', async () => {
  const dev = await s.register();
  const big = await upload(dev.token, bytes(64 * 1024 + 10));
  assert.equal(big.status, 413);
  assert.equal(big.body.error, 'payload_too_large');
  assert.equal(fs.readdirSync(s.mediaDir).filter((f) => f.endsWith('.part')).length, 0, 'no partial files left');

  const json = await s.api('POST', '/v1/media', { token: dev.token, body: { x: 1 } });
  assert.equal(json.status, 415);

  const noAuth = await upload(null, bytes(10));
  assert.equal(noAuth.status, 401);

  const weird = await upload(dev.token, bytes(10), { name: '../../etc/passwd', type: 'bad type<>' });
  assert.equal(weird.status, 201);
  assert.equal(weird.body.name, 'passwd');
  assert.equal(weird.body.mime, 'application/octet-stream');
  assert.equal(weird.body.kind, 'file');
});
