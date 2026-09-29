import { assert, assertEquals, assertRejects } from "./assert.ts";
import { randomUUID } from "node:crypto";
import { sleep, startServer } from "./helpers.ts";

Deno.test({ name: "realtime", sanitizeOps: false, sanitizeResources: false }, async (t) => {
  const s = await startServer({ PRESENCE_OFFLINE_MS: "300", TYPING_TTL_MS: "400" });
  const admin = await s.adminLogin();
  const send = (token: string, chatId: string, text: string, prefix = "/v1/chats") => s.api("POST", `${prefix}/${chatId}/messages`, { token, body: { clientId: randomUUID(), text } });

  try {
    await t.step("WS rejects invalid token", async () => {
      await assertRejects(() => s.connect("d_invalidinvalidinvalidinvalid"));
    });

    await t.step("ping/pong", async () => {
      const dev = await s.register();
      const ws = await s.connect(dev.token);
      ws.send({ t: "ping" });
      await ws.waitFor((f) => f.t === "pong");
      ws.close();
    });

    await t.step("message frames + read receipts both ways", async () => {
      const dev = await s.register();
      const chatId = dev.adminChatId;
      const dws = await s.connect(dev.token);
      const aws = await s.connect(admin);
      const m1 = (await send(dev.token, chatId, "вопрос")).body;
      const fAdmin = await aws.waitFor((f) => f.t === "message" && f.message.id === m1.id);
      assertEquals(fAdmin.chatId, chatId);
      await dws.waitFor((f) => f.t === "message" && f.message.id === m1.id);

      aws.send({ t: "read", chatId, messageId: m1.id });
      const rf = await dws.waitFor((f) => f.t === "read" && f.who === "admin");
      assertEquals(rf, { t: "read", chatId, who: "admin", messageId: m1.id });
      const msgs = (await s.api("GET", `/v1/chats/${chatId}/messages`, { token: dev.token })).body;
      assertEquals(msgs[0].readByPeer, true);
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].peerReadUpTo, m1.id);
      assertEquals((await s.api("GET", "/v1/admin/chats", { token: admin })).body.find((c: { id: string }) => c.id === chatId).unread, 0);

      const m2 = (await send(admin, chatId, "ответ", "/v1/admin/chats")).body;
      await dws.waitFor((f) => f.t === "message" && f.message.id === m2.id);
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].unread, 1);
      dws.send({ t: "read", chatId, messageId: m2.id });
      await aws.waitFor((f) => f.t === "read" && f.who === "user" && f.messageId === m2.id);
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].unread, 0);
      const adminMsgs = (await s.api("GET", `/v1/admin/chats/${chatId}/messages`, { token: admin })).body;
      assertEquals(adminMsgs.find((m: { id: string }) => m.id === m2.id).readByPeer, true);

      const other = await s.register();
      const before = aws.frames.length;
      dws.send({ t: "read", chatId: other.adminChatId, messageId: m2.id });
      await sleep(100);
      assertEquals(aws.frames.slice(before).filter((f) => f.t === "read").length, 0);

      await s.api("POST", `/v1/chats/${chatId}/messages/${m2.id}/reaction`, { token: dev.token, body: { emoji: "🔥" } });
      const upd = await aws.waitFor((f) => f.t === "message.updated" && f.message.id === m2.id);
      assertEquals(upd.message.reactions, { "🔥": ["user"] });
      await s.api("DELETE", `/v1/chats/${chatId}/messages/${m1.id}?scope=everyone`, { token: dev.token });
      const del = await aws.waitFor((f) => f.t === "message.updated" && f.message.id === m1.id);
      assertEquals(del.message.deleted, true);
      await s.api("POST", `/v1/admin/chats/${chatId}/clear?scope=everyone`, { token: admin });
      await dws.waitFor((f) => f.t === "chat.cleared" && f.chatId === chatId);
      dws.close();
      aws.close();
    });

    await t.step("typing frames, TTL, peerTyping", async () => {
      const dev = await s.register();
      const chatId = dev.adminChatId;
      const dws = await s.connect(dev.token);
      const aws = await s.connect(admin);
      dws.send({ t: "typing", chatId, typing: true });
      await aws.waitFor((f) => f.t === "typing" && f.chatId === chatId && f.who === "user" && f.typing === true);
      await aws.waitFor((f) => f.t === "presence" && f.deviceId === dev.deviceId && f.typingIn === chatId);
      assertEquals((await s.api("GET", "/v1/admin/chats", { token: admin })).body.find((c: { id: string }) => c.id === chatId).peerTyping, true);
      await aws.waitFor((f) => f.t === "typing" && f.who === "user" && f.typing === false, 2000);

      aws.send({ t: "typing", chatId, typing: true });
      await dws.waitFor((f) => f.t === "typing" && f.who === "admin" && f.typing === true);
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].peerTyping, true);
      await send(admin, chatId, "готово", "/v1/admin/chats");
      await dws.waitFor((f) => f.t === "typing" && f.who === "admin" && f.typing === false);
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].peerTyping, false);
      dws.close();
      aws.close();
    });

    await t.step("presence lifecycle", async () => {
      const dev = await s.register();
      const aws = await s.connect(admin);
      const dws = await s.connect(dev.token);
      await aws.waitFor((f) => f.t === "presence" && f.deviceId === dev.deviceId && f.state === "background");
      dws.send({ t: "presence", state: "foreground" });
      const fg = await aws.waitFor((f) => f.t === "presence" && f.deviceId === dev.deviceId && f.state === "foreground");
      assert(fg.lastSeen);
      assertEquals(fg.typingIn, null);
      let d = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(d.presence, "foreground");
      assert((await s.api("GET", "/v1/admin/overview", { token: admin })).body.online >= 1);
      const online = (await s.api("GET", "/v1/admin/devices?status=online", { token: admin })).body;
      assert(online.some((x: { deviceId: string }) => x.deviceId === dev.deviceId));
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].peerPresence, "background");

      dws.send({ t: "presence", state: "background" });
      await aws.waitFor((f) => f.t === "presence" && f.deviceId === dev.deviceId && f.state === "background" && aws.frames.indexOf(f) > aws.frames.indexOf(fg));

      const closedAt = Date.now();
      dws.close();
      await sleep(100);
      d = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(d.presence, "background");
      const off = await aws.waitFor((f) => f.t === "presence" && f.deviceId === dev.deviceId && f.state === "offline", 2000);
      assert(Date.now() - closedAt >= 250);
      assert(Math.abs(Date.parse(off.lastSeen) - closedAt) < 1000);
      d = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(d.presence, "offline");
      assertEquals(d.lastSeen, off.lastSeen);

      const dws2 = await s.connect(dev.token);
      dws2.send({ t: "presence", state: "foreground" });
      dws2.close();
      await sleep(100);
      const dws3 = await s.connect(dev.token);
      await sleep(400);
      assert((await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body.presence !== "offline");
      dws3.close();
      aws.close();
    });

    await t.step("admin presence from device side", async () => {
      const dev = await s.register();
      const aws = await s.connect(admin);
      aws.send({ t: "presence", state: "foreground" });
      await sleep(50);
      const c = (await s.api("GET", "/v1/chats", { token: dev.token })).body[0];
      assertEquals(c.peerPresence, "foreground");
      assert(c.peerLastSeen);
      aws.close();
    });

    await t.step("blocking kicks socket with 4003", async () => {
      const dev = await s.register();
      const dws = await s.connect(dev.token);
      await s.api("POST", `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: true, reason: "Нарушение" } });
      const f = await dws.waitFor((x) => x.t === "blocked");
      assertEquals(f.message, "Нарушение");
      const c = await dws.waitClose();
      assertEquals(c.code, 4003);
      const again = await s.connect(dev.token);
      const f2 = await again.waitFor((x) => x.t === "blocked");
      assertEquals(f2.message, "Нарушение");
      await again.waitClose();
    });
  } finally {
    await s.close();
  }
});
