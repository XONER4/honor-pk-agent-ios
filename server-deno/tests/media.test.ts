import { assert, assertEquals } from "./assert.ts";
import { randomUUID } from "node:crypto";
import { startServer } from "./helpers.ts";

const bytes = (n: number) => new Uint8Array(Array.from({ length: n }, (_, i) => i % 256));
function eqBytes(a: Uint8Array, b: Uint8Array) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

Deno.test({ name: "media", sanitizeOps: false, sanitizeResources: false }, async (t) => {
  const s = await startServer({ MAX_UPLOAD_BYTES: String(64 * 1024) });
  const admin = await s.adminLogin();

  async function upload(token: string | null, buf: Uint8Array, { name = "video.mp4", type = "video/mp4", fields = {} }: { name?: string; type?: string; fields?: Record<string, unknown> } = {}) {
    const fd = new FormData();
    for (const [k, v] of Object.entries(fields)) fd.append(k, String(v));
    fd.append("file", new Blob([buf as unknown as BlobPart], { type }), name);
    return s.api("POST", "/v1/media", { token: token || undefined, body: fd });
  }

  try {
    await t.step("upload → ref, attach, range, access control", async () => {
      const dev = await s.register();
      const data = bytes(10_000);
      const up = await upload(dev.token, data, { fields: { durationMs: 1500, width: 640, height: 360 } });
      assertEquals(up.status, 201);
      const ref = up.body;
      assertEquals(ref.kind, "video");
      assertEquals(ref.name, "video.mp4");
      assertEquals(ref.mime, "video/mp4");
      assertEquals(ref.size, 10_000);
      assertEquals(ref.durationMs, 1500);
      assertEquals(ref.width, 640);
      assertEquals(ref.height, 360);
      assertEquals(ref.url, `/v1/media/${ref.id}`);

      let r = await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${dev.token}` } });
      assertEquals(r.status, 200);
      assertEquals(r.headers.get("accept-ranges"), "bytes");
      assertEquals(r.headers.get("content-type"), "video/mp4");
      assert(eqBytes(new Uint8Array(await r.arrayBuffer()), data));

      r = await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${dev.token}`, range: "bytes=100-199" } });
      assertEquals(r.status, 206);
      assertEquals(r.headers.get("content-range"), "bytes 100-199/10000");
      assertEquals(r.headers.get("content-length"), "100");
      assert(eqBytes(new Uint8Array(await r.arrayBuffer()), data.subarray(100, 200)));

      r = await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${dev.token}`, range: "bytes=20000-" } });
      assertEquals(r.status, 416);
      assertEquals(r.headers.get("content-range"), "bytes */10000");
      await r.body?.cancel();

      r = await fetch(`${s.base}${ref.url}?token=${dev.token}`, { headers: { range: "bytes=-5" } });
      assertEquals(r.status, 206);
      assert(eqBytes(new Uint8Array(await r.arrayBuffer()), data.subarray(9995)));

      r = await fetch(s.base + ref.url);
      assertEquals(r.status, 401);
      await r.body?.cancel();
      const stranger = await s.register();
      r = await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${stranger.token}` } });
      assertEquals(r.status, 403);
      await r.body?.cancel();

      const steal = await s.api("POST", `/v1/chats/${stranger.adminChatId}/messages`, { token: stranger.token, body: { clientId: randomUUID(), attachments: [{ id: ref.id }] } });
      assertEquals(steal.status, 403);

      r = await fetch(s.base + ref.url, { headers: { authorization: `Bearer ${admin}` } });
      assertEquals(r.status, 200);
      await r.body?.cancel();

      const msg = await s.api("POST", `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: "", attachments: [{ id: ref.id, kind: "voice" }] } });
      assertEquals(msg.status, 200);
      assertEquals(msg.body.attachments.length, 1);
      assertEquals(msg.body.attachments[0].id, ref.id);
      assertEquals(msg.body.attachments[0].kind, "voice");
      assertEquals(msg.body.attachments[0].size, 10_000);
      assertEquals(msg.body.attachments[0].url, ref.url);

      const aup = await upload(admin, bytes(300), { name: "photo.jpg", type: "image/jpeg" });
      assertEquals(aup.body.kind, "image");
      r = await fetch(s.base + aup.body.url, { headers: { authorization: `Bearer ${dev.token}` } });
      assertEquals(r.status, 403);
      await r.body?.cancel();
      await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: "фото", attachments: [{ id: aup.body.id }] } });
      r = await fetch(s.base + aup.body.url, { headers: { authorization: `Bearer ${dev.token}` } });
      assertEquals(r.status, 200);
      await r.body?.cancel();
      r = await fetch(s.base + aup.body.url, { headers: { authorization: `Bearer ${stranger.token}` } });
      assertEquals(r.status, 403);
      await r.body?.cancel();

      r = await fetch(`${s.base}/v1/media/${randomUUID()}`, { headers: { authorization: `Bearer ${admin}` } });
      assertEquals(r.status, 404);
      await r.body?.cancel();
    });

    await t.step("upload limits and validation", async () => {
      const dev = await s.register();
      const big = await upload(dev.token, bytes(64 * 1024 + 10));
      assertEquals(big.status, 413);
      assertEquals(big.body.error, "payload_too_large");
      const json = await s.api("POST", "/v1/media", { token: dev.token, body: { x: 1 } });
      assertEquals(json.status, 415);
      const noAuth = await upload(null, bytes(10));
      assertEquals(noAuth.status, 401);
      const weird = await upload(dev.token, bytes(10), { name: "../../etc/passwd", type: "bad type<>" });
      assertEquals(weird.status, 201);
      assertEquals(weird.body.name, "passwd");
      assertEquals(weird.body.mime, "application/octet-stream");
      assertEquals(weird.body.kind, "file");
    });
  } finally {
    await s.close();
  }
});
