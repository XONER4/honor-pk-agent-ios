import { assert, assertEquals } from "./assert.ts";
import { randomUUID } from "node:crypto";
import { startServer } from "./helpers.ts";

Deno.test({ name: "chats", sanitizeOps: false, sanitizeResources: false }, async (t) => {
  const s = await startServer();
  const admin = await s.adminLogin();
  const send = (token: string, chatId: string, body: Record<string, unknown>, prefix = "/v1/chats") =>
    s.api("POST", `${prefix}/${chatId}/messages`, { token, body: { clientId: randomUUID(), ...body } });
  const list = async (token: string, chatId: string, prefix = "/v1/chats", qs = "") => (await s.api("GET", `${prefix}/${chatId}/messages${qs}`, { token })).body;

  try {
    await t.step("GET /v1/chats returns admin chat with full model", async () => {
      const dev = await s.register();
      const r = await s.api("GET", "/v1/chats", { token: dev.token });
      assertEquals(r.status, 200);
      assertEquals(r.body.length, 1);
      const c = r.body[0];
      assertEquals(c.id, dev.adminChatId);
      assertEquals(c.kind, "admin");
      assertEquals(c.deviceId, dev.deviceId);
      assertEquals(c.aiEnabled, false);
      assertEquals(c.pinnedMessageId, null);
      assertEquals(c.lastMessage, null);
      assertEquals(c.unread, 0);
      assertEquals(c.peerTyping, false);
      assertEquals(c.peerReadUpTo, null);
      assertEquals(c.peerPresence, "offline");
      assert("peerLastSeen" in c && "title" in c);
    });

    await t.step("send, list, idempotent, reply, validation", async () => {
      const dev = await s.register();
      const chatId = dev.adminChatId;
      const clientId = randomUUID();
      const r1 = await s.api("POST", `/v1/chats/${chatId}/messages`, { token: dev.token, body: { clientId, text: "Привет!" } });
      assertEquals(r1.status, 200);
      const m = r1.body;
      assertEquals(m.sender, "user");
      assertEquals(m.clientId, clientId);
      assertEquals(m.text, "Привет!");
      assertEquals(m.attachments, []);
      assertEquals(m.reactions, {});
      assertEquals(m.deleted, false);
      assertEquals(m.pinned, false);
      assertEquals(m.readByPeer, false);
      assertEquals(m.editedAt, null);
      assertEquals(m.replyTo, null);
      assert(!Number.isNaN(Date.parse(m.createdAt)));

      const r2 = await s.api("POST", `/v1/chats/${chatId}/messages`, { token: dev.token, body: { clientId, text: "Привет!" } });
      assertEquals(r2.body.id, m.id);
      assertEquals((await list(dev.token, chatId)).length, 1);

      const reply = await send(admin, chatId, { text: "Здравствуйте", replyTo: m.id }, "/v1/admin/chats");
      assertEquals(reply.status, 200);
      assertEquals(reply.body.sender, "admin");
      assertEquals(reply.body.replyTo, m.id);

      const msgs = await list(dev.token, chatId);
      assertEquals(msgs.map((x: { text: string }) => x.text), ["Привет!", "Здравствуйте"]);

      assertEquals((await send(dev.token, chatId, { text: "   " })).status, 400);
      assertEquals((await send(dev.token, chatId, { text: "x", replyTo: randomUUID() })).status, 404);
      assertEquals((await send(dev.token, chatId, { text: "x".repeat(10_001) })).status, 400);

      const other = await s.register();
      assertEquals((await send(other.token, chatId, { text: "hack" })).status, 404);
      assertEquals((await s.api("GET", `/v1/chats/${chatId}/messages`, { token: other.token })).status, 404);

      const chats = (await s.api("GET", "/v1/admin/chats", { token: admin })).body;
      const c = chats.find((x: { id: string }) => x.id === chatId);
      assertEquals(c.title, "Иван");
      assertEquals(c.unread, 1);
      assertEquals(c.lastMessage.text, "Здравствуйте");
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].unread, 1);
    });

    await t.step("pagination with before & limit", async () => {
      const dev = await s.register();
      const ids = [];
      for (let i = 0; i < 7; i++) ids.push((await send(dev.token, dev.adminChatId, { text: `m${i}` })).body.id);
      const last3 = await list(dev.token, dev.adminChatId, "/v1/chats", "?limit=3");
      assertEquals(last3.map((m: { text: string }) => m.text), ["m4", "m5", "m6"]);
      const prev = await list(dev.token, dev.adminChatId, "/v1/chats", `?limit=3&before=${last3[0].id}`);
      assertEquals(prev.map((m: { text: string }) => m.text), ["m1", "m2", "m3"]);
      assertEquals((await s.api("GET", `/v1/chats/${dev.adminChatId}/messages?limit=1000`, { token: dev.token })).status, 400);
    });

    await t.step("reactions", async () => {
      const dev = await s.register();
      const m = (await send(dev.token, dev.adminChatId, { text: "реакции" })).body;
      let r = await s.api("POST", `/v1/chats/${dev.adminChatId}/messages/${m.id}/reaction`, { token: dev.token, body: { emoji: "👍" } });
      assertEquals(r.status, 200);
      assertEquals(r.body.reactions, { "👍": ["user"] });
      r = await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/messages/${m.id}/reaction`, { token: admin, body: { emoji: "👍" } });
      assertEquals(r.body.reactions, { "👍": ["user", "admin"] });
      r = await s.api("POST", `/v1/chats/${dev.adminChatId}/messages/${m.id}/reaction`, { token: dev.token, body: { emoji: "❤️" } });
      assertEquals(r.body.reactions, { "👍": ["admin"], "❤️": ["user"] });
      r = await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/messages/${m.id}/reaction`, { token: admin, body: { emoji: null } });
      assertEquals(r.body.reactions, { "❤️": ["user"] });
      assertEquals((await list(dev.token, dev.adminChatId))[0].reactions, { "❤️": ["user"] });
    });

    await t.step("pin", async () => {
      const dev = await s.register();
      const a = (await send(dev.token, dev.adminChatId, { text: "A" })).body;
      const b = (await send(dev.token, dev.adminChatId, { text: "B" })).body;
      let r = await s.api("POST", `/v1/chats/${dev.adminChatId}/messages/${a.id}/pin`, { token: dev.token, body: { pinned: true } });
      assertEquals(r.body.pinned, true);
      r = await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/messages/${b.id}/pin`, { token: admin, body: { pinned: true } });
      assertEquals(r.body.pinned, true);
      let msgs = await list(dev.token, dev.adminChatId);
      assertEquals(msgs.map((m: { pinned: boolean }) => m.pinned), [false, true]);
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].pinnedMessageId, b.id);
      await s.api("POST", `/v1/chats/${dev.adminChatId}/messages/${b.id}/pin`, { token: dev.token, body: { pinned: false } });
      msgs = await list(dev.token, dev.adminChatId);
      assertEquals(msgs.map((m: { pinned: boolean }) => m.pinned), [false, false]);
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].pinnedMessageId, null);
    });

    await t.step("edit own message", async () => {
      const dev = await s.register();
      const m = (await send(dev.token, dev.adminChatId, { text: "опечатка" })).body;
      const r = await s.api("PATCH", `/v1/chats/${dev.adminChatId}/messages/${m.id}`, { token: dev.token, body: { text: "исправлено" } });
      assertEquals(r.status, 200);
      assertEquals(r.body.text, "исправлено");
      assert(r.body.editedAt);
      const f = await s.api("PATCH", `/v1/admin/chats/${dev.adminChatId}/messages/${m.id}`, { token: admin, body: { text: "нет" } });
      assertEquals(f.status, 403);
    });

    await t.step("delete for me / everyone", async () => {
      const dev = await s.register();
      const chatId = dev.adminChatId;
      const mine = (await send(dev.token, chatId, { text: "моё" })).body;
      const adm = (await send(admin, chatId, { text: "от админа" }, "/v1/admin/chats")).body;
      const other = (await send(dev.token, chatId, { text: "ещё" })).body;
      let r = await s.api("DELETE", `/v1/chats/${chatId}/messages/${adm.id}?scope=everyone`, { token: dev.token });
      assertEquals(r.status, 403);
      r = await s.api("DELETE", `/v1/chats/${chatId}/messages/${adm.id}?scope=me`, { token: dev.token });
      assertEquals(r.status, 200);
      assertEquals((await list(dev.token, chatId)).map((m: { text: string }) => m.text), ["моё", "ещё"]);
      assertEquals((await list(admin, chatId, "/v1/admin/chats")).length, 3);
      await s.api("POST", `/v1/chats/${chatId}/messages/${mine.id}/reaction`, { token: dev.token, body: { emoji: "👍" } });
      r = await s.api("DELETE", `/v1/chats/${chatId}/messages/${mine.id}?scope=everyone`, { token: dev.token });
      assertEquals(r.status, 200);
      const tomb = (await list(admin, chatId, "/v1/admin/chats")).find((m: { id: string }) => m.id === mine.id);
      assertEquals(tomb.deleted, true);
      assertEquals(tomb.text, "");
      assertEquals(tomb.reactions, {});
      r = await s.api("DELETE", `/v1/admin/chats/${chatId}/messages/${other.id}?scope=everyone`, { token: admin });
      assertEquals(r.status, 200);
      assertEquals((await list(dev.token, chatId)).find((m: { id: string }) => m.id === other.id).deleted, true);
      r = await s.api("DELETE", `/v1/chats/${chatId}/messages/${other.id}?scope=all`, { token: dev.token });
      assertEquals(r.status, 400);
    });

    await t.step("clear for me / everyone", async () => {
      const dev = await s.register();
      const chatId = dev.adminChatId;
      await send(dev.token, chatId, { text: "1" });
      await send(admin, chatId, { text: "2" }, "/v1/admin/chats");
      let r = await s.api("POST", `/v1/chats/${chatId}/clear?scope=everyone`, { token: dev.token });
      assertEquals(r.status, 403);
      r = await s.api("POST", `/v1/chats/${chatId}/clear?scope=me`, { token: dev.token });
      assertEquals(r.status, 200);
      assertEquals((await list(dev.token, chatId)).length, 0);
      assertEquals((await list(admin, chatId, "/v1/admin/chats")).length, 2);
      const devChat = (await s.api("GET", "/v1/chats", { token: dev.token })).body[0];
      assertEquals(devChat.unread, 0);
      assertEquals(devChat.lastMessage, null);
      await send(dev.token, chatId, { text: "3" });
      assertEquals((await list(dev.token, chatId)).map((m: { text: string }) => m.text), ["3"]);
      r = await s.api("POST", `/v1/admin/chats/${chatId}/clear?scope=everyone`, { token: admin });
      assertEquals(r.status, 200);
      assertEquals((await list(admin, chatId, "/v1/admin/chats")).length, 0);
      assertEquals((await list(dev.token, chatId)).length, 0);
    });

    await t.step("empty JSON accepted, malformed / proto rejected", async () => {
      const dev = await s.register();
      let r = await fetch(`${s.base}/v1/chats/${dev.adminChatId}/clear?scope=me`, {
        method: "POST",
        headers: { authorization: `Bearer ${dev.token}`, "content-type": "application/json" },
      });
      assertEquals(r.status, 200);
      await r.body?.cancel();
      r = await fetch(`${s.base}/v1/chats/${dev.adminChatId}/messages`, {
        method: "POST",
        headers: { authorization: `Bearer ${dev.token}`, "content-type": "application/json" },
        body: '{"clientId":',
      });
      assertEquals(r.status, 400);
      assertEquals((await r.json()).error, "bad_request");
      r = await fetch(`${s.base}/v1/chats/${dev.adminChatId}/messages`, {
        method: "POST",
        headers: { authorization: `Bearer ${dev.token}`, "content-type": "application/json" },
        body: '{"clientId":"x","text":"hi","__proto__":{"polluted":true}}',
      });
      assertEquals(r.status, 400);
      await r.body?.cancel();
    });
  } finally {
    await s.close();
  }
});
