import { assert, assertEquals } from "./assert.ts";
import { randomUUID } from "node:crypto";
import { sleep, startServer } from "./helpers.ts";

Deno.test({ name: "notifications & push", sanitizeOps: false, sanitizeResources: false }, async (t) => {
  const pushed: { token: string; data: Record<string, string> }[] = [];
  const s = await startServer({}, {
    pushTransport: {
      send(token: string, data: Record<string, string>) {
        pushed.push({ token, data });
        return Promise.resolve(token === "dead-token" ? "invalid_token" : "ok");
      },
    },
  });
  const admin = await s.adminLogin();

  try {
    await t.step("personal and broadcast: WS frame, list, after, read", async () => {
      const a = await s.register();
      const b = await s.register();
      const aws = await s.connect(a.token);
      let r = await s.api("POST", "/v1/admin/notifications", { token: admin, body: { deviceId: a.deviceId, title: "Лично", body: "Только тебе" } });
      assertEquals(r.status, 200);
      assertEquals(r.body.count, 1);
      const frame = await aws.waitFor((f) => f.t === "notification");
      assertEquals(frame.notification.title, "Лично");
      assertEquals(frame.notification.kind, "admin");
      assertEquals(frame.notification.read, false);
      assertEquals(frame.notification.chatId, null);

      await sleep(5);
      r = await s.api("POST", "/v1/admin/notifications", { token: admin, body: { deviceId: null, title: "Всем", body: "Обновление" } });
      assertEquals(r.status, 200);
      assert(r.body.count >= 2);

      const la = (await s.api("GET", "/v1/notifications", { token: a.token })).body;
      assertEquals(la.map((n: { title: string }) => n.title), ["Лично", "Всем"]);
      const lb = (await s.api("GET", "/v1/notifications", { token: b.token })).body;
      assertEquals(lb.map((n: { title: string }) => n.title), ["Всем"]);

      const afterFirst = (await s.api("GET", `/v1/notifications?after=${encodeURIComponent(la[0].createdAt)}`, { token: a.token })).body;
      assertEquals(afterFirst.map((n: { title: string }) => n.title), ["Всем"]);
      const afterLast = (await s.api("GET", `/v1/notifications?after=${encodeURIComponent(la[1].createdAt)}`, { token: a.token })).body;
      assertEquals(afterLast.length, 0);

      r = await s.api("POST", "/v1/notifications/read", { token: a.token, body: { ids: [la[0].id, lb[0].id] } });
      assertEquals(r.status, 200);
      const la2 = (await s.api("GET", "/v1/notifications", { token: a.token })).body;
      assertEquals(la2.map((n: { read: boolean }) => n.read), [true, false]);
      const lb2 = (await s.api("GET", "/v1/notifications", { token: b.token })).body;
      assertEquals(lb2[0].read, false);

      assertEquals((await s.api("POST", "/v1/admin/notifications", { token: admin, body: { deviceId: randomUUID(), title: "x", body: "y" } })).status, 404);
      assertEquals((await s.api("POST", "/v1/admin/notifications", { token: a.token, body: { title: "x", body: "y" } })).status, 403);
      assertEquals((await s.api("GET", "/v1/notifications?after=yesterday", { token: a.token })).status, 400);
      aws.close();
    });

    await t.step("push goes only to non-foreground; invalid tokens cleared", async () => {
      const dev = await s.register({ pushToken: "tok-1" });
      const ws = await s.connect(dev.token);
      ws.send({ t: "presence", state: "foreground" });
      await sleep(50);
      pushed.length = 0;
      await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: "fg" } });
      await sleep(50);
      assertEquals(pushed.length, 0);

      ws.send({ t: "presence", state: "background" });
      await sleep(50);
      await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: "Ответ админа" } });
      await sleep(50);
      assertEquals(pushed.length, 1);
      assertEquals(pushed[0], { token: "tok-1", data: { type: "message", chatId: dev.adminChatId, title: "Администратор", body: "Ответ админа" } });

      await s.api("POST", `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: "моё" } });
      await sleep(50);
      assertEquals(pushed.length, 1);

      await s.api("POST", "/v1/admin/notifications", { token: admin, body: { deviceId: dev.deviceId, title: "T", body: "B" } });
      await sleep(50);
      assertEquals(pushed[1].data, { type: "notification", chatId: "", title: "T", body: "B" });
      ws.close();

      const dead = await s.register({ pushToken: "dead-token" });
      await s.api("POST", `/v1/admin/chats/${dead.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: "x" } });
      await sleep(50);
      const pushedBefore = pushed.length;
      await s.api("POST", `/v1/admin/chats/${dead.adminChatId}/messages`, { token: admin, body: { clientId: randomUUID(), text: "y" } });
      await sleep(50);
      assertEquals(pushed.length, pushedBefore);
    });
  } finally {
    await s.close();
  }
});
