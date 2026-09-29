import { assert, assertEquals, assertMatch } from "./assert.ts";
import { randomUUID } from "node:crypto";
import { ADMIN_KEY, startServer } from "./helpers.ts";

Deno.test({ name: "devices & admin auth", sanitizeOps: false, sanitizeResources: false }, async (t) => {
  const s = await startServer({ LOGIN_RATE_MAX: "3", GOOGLE_CLIENT_IDS: "client-1.apps.googleusercontent.com" }, {
    verifyGoogleIdToken: (idToken: string, audience: string[]) => {
      assertEquals(audience, ["client-1.apps.googleusercontent.com"]);
      if (!idToken.startsWith("good-token-")) return Promise.reject(new Error("bad token"));
      return Promise.resolve({ email: idToken.slice("good-token-".length), email_verified: true, name: "Big Boss" });
    },
  });
  try {
    await t.step("health", async () => {
      const r = await s.api("GET", "/health");
      assertEquals(r.status, 200);
      assertEquals(r.body.ok, true);
      assertEquals(typeof r.body.ai, "boolean");
      assertEquals(r.headers.get("x-content-type-options"), "nosniff");
    });

    await t.step("unknown route and missing token use the error format", async () => {
      const r = await s.api("GET", "/v1/nope");
      assertEquals(r.status, 404);
      assertEquals(r.body.error, "not_found");
      const u = await s.api("GET", "/v1/chats");
      assertEquals(u.status, 401);
      assertEquals(u.body.error, "unauthorized");
      const bad = await s.api("GET", "/v1/chats", { token: "d_notarealtokennotarealtoken" });
      assertEquals(bad.status, 401);
    });

    await t.step("register validates input", async () => {
      const r = await s.api("POST", "/v1/devices/register", { body: { installId: "x", platform: "android" } });
      assertEquals(r.status, 400);
      assertEquals(r.body.error, "bad_request");
      const p = await s.api("POST", "/v1/devices/register", { body: { installId: randomUUID(), platform: "windows" } });
      assertEquals(p.status, 400);
    });

    await t.step("register and re-register", async () => {
      const installId = randomUUID();
      const admin = await s.adminLogin();
      const before = (await s.api("GET", "/v1/admin/overview", { token: admin })).body;

      const a = await s.register({ installId, displayName: "Первый" });
      assertMatch(a.token, /^d_[A-Za-z0-9_-]{43}$/);
      assert(a.deviceId && a.userId && a.adminChatId);

      const b = await s.register({ installId, displayName: "Второй", appVersion: "10.45.0" });
      assertEquals(b.deviceId, a.deviceId);
      assertEquals(b.userId, a.userId);
      assertEquals(b.adminChatId, a.adminChatId);
      assert(b.token !== a.token);

      assertEquals((await s.api("GET", "/v1/chats", { token: a.token })).status, 401);
      assertEquals((await s.api("GET", "/v1/chats", { token: b.token })).status, 200);

      const after = (await s.api("GET", "/v1/admin/overview", { token: admin })).body;
      assertEquals(after.installs - before.installs, 1);
      assertEquals(after.users - before.users, 1);

      const d = (await s.api("GET", `/v1/admin/devices/${a.deviceId}`, { token: admin })).body;
      assertEquals(d.displayName, "Второй");
      assertEquals(d.appVersion, "10.45.0");
      assertEquals(d.birthday, "2008-05-01");
      assertEquals(d.language, "ru");
      assertEquals(d.osVersion, "14");
      assert(d.licenseAcceptedAt);
      assertEquals(d.adminChatId, a.adminChatId);
    });

    await t.step("PATCH me and stats keep max", async () => {
      const dev = await s.register();
      const admin = await s.adminLogin();
      let r = await s.api("PATCH", "/v1/devices/me", { token: dev.token, body: { displayName: "Новое имя", pushToken: "fcm-1", birthday: null } });
      assertEquals(r.status, 200);
      r = await s.api("POST", "/v1/devices/me/stats", { token: dev.token, body: { messagesSent: 10, secondsInApp: 500 } });
      assertEquals(r.status, 200);
      await s.api("POST", "/v1/devices/me/stats", { token: dev.token, body: { messagesSent: 7, secondsInApp: 900 } });
      const d = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(d.displayName, "Новое имя");
      assertEquals(d.birthday, null);
      assertEquals(d.messagesSent, 10);
      assertEquals(d.secondsInApp, 900);
      const bad = await s.api("POST", "/v1/devices/me/stats", { token: dev.token, body: { messagesSent: -1 } });
      assertEquals(bad.status, 400);
    });

    await t.step("admin login adminKey / wrong / device token", async () => {
      const ok = await s.api("POST", "/v1/admin/login", { body: { adminKey: ADMIN_KEY } });
      assertEquals(ok.status, 200);
      assertMatch(ok.body.token, /^a_/);
      assertEquals(ok.body.email, "boss@example.com");
      assert(ok.body.name);
      const dev = await s.register();
      assertEquals((await s.api("GET", "/v1/admin/overview", { token: dev.token })).status, 403);
      assertEquals((await s.api("GET", "/v1/chats", { token: ok.body.token })).status, 403);
      assertEquals((await s.api("POST", "/v1/admin/login", { body: {} })).status, 400);
    });

    await t.step("admin login via Google ID token", async () => {
      const ok = await s.api("POST", "/v1/admin/login", { body: { googleIdToken: "good-token-BOSS@example.com" } });
      assertEquals(ok.status, 200);
      assertEquals(ok.body.email, "boss@example.com");
      assertEquals(ok.body.name, "Big Boss");
      assertEquals((await s.api("GET", "/v1/admin/overview", { token: ok.body.token })).status, 200);
      const stranger = await s.api("POST", "/v1/admin/login", { body: { googleIdToken: "good-token-evil@example.com" } });
      assertEquals(stranger.status, 401);
    });

    await t.step("admin login rate limited", async () => {
      const hdr = { "x-forwarded-for": "10.9.9.9" };
      for (let i = 0; i < 3; i++) {
        const r = await s.api("POST", "/v1/admin/login", { body: { adminKey: "wrong" }, headers: hdr });
        assertEquals(r.status, 401);
      }
      const r = await s.api("POST", "/v1/admin/login", { body: { adminKey: ADMIN_KEY }, headers: hdr });
      assertEquals(r.status, 429);
      assertEquals(r.body.error, "rate_limited");
    });

    await t.step("block → 403 everywhere incl re-register", async () => {
      const admin = await s.adminLogin();
      const installId = randomUUID();
      const dev = await s.register({ installId });
      const r = await s.api("POST", `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: true, reason: "Спам" } });
      assertEquals(r.status, 200);
      assertEquals(r.body.blocked, true);
      assertEquals(r.body.blockReason, "Спам");
      for (
        const [m, u, body] of [
          ["GET", "/v1/chats"],
          ["GET", `/v1/chats/${dev.adminChatId}/messages`],
          ["PATCH", "/v1/devices/me", {}],
          ["GET", "/v1/notifications"],
          ["POST", "/v1/ai/chat/completions", { model: "deepseek-chat", messages: [{ role: "user", content: "x" }] }],
        ] as [string, string, unknown?][]
      ) {
        const x = await s.api(m, u, { token: dev.token, body });
        assertEquals(x.status, 403, `${m} ${u}`);
        assertEquals(x.body, { error: "blocked", message: "Спам", until: null });
      }
      const re = await s.api("POST", "/v1/devices/register", { body: { installId, platform: "android" } });
      assertEquals(re.status, 403);
      assertEquals(re.body.error, "blocked");

      const list = (await s.api("GET", "/v1/admin/devices?status=blocked", { token: admin })).body;
      assert(list.some((d: { deviceId: string }) => d.deviceId === dev.deviceId));
      const ov = (await s.api("GET", "/v1/admin/overview", { token: admin })).body;
      assert(ov.blocked >= 1);
      await s.api("POST", `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: false } });
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).status, 200);
    });

    await t.step("admin devices list: search, fields, unreadForAdmin", async () => {
      const admin = await s.adminLogin();
      const dev = await s.register({ displayName: "Уникальный Пётр", deviceModel: "Pixel 9" });
      await s.api("POST", `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: "привет" } });
      await s.api("POST", `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: "есть кто?" } });
      const r = await s.api("GET", `/v1/admin/devices?query=${encodeURIComponent("уникальный")}`, { token: admin });
      assertEquals(r.status, 200);
      assertEquals(r.body.length, 1);
      const d = r.body[0];
      for (
        const k of ["deviceId", "userId", "displayName", "deviceModel", "deviceName", "platform", "appVersion", "installedAt", "lastSeen", "presence", "typingIn", "blocked", "messagesSent", "secondsInApp", "unreadForAdmin", "adminChatId"]
      ) assert(k in d, `missing ${k}`);
      assertEquals(d.unreadForAdmin, 2);
      assertEquals(d.presence, "offline");
      assertEquals(d.deviceModel, "Pixel 9");
      const detail = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(detail.installs, 1);
      assertEquals((await s.api("GET", `/v1/admin/devices/${randomUUID()}`, { token: admin })).status, 404);
      assertEquals((await s.api("GET", "/v1/admin/devices/not-a-uuid", { token: admin })).status, 400);
      const ov = (await s.api("GET", "/v1/admin/overview", { token: admin })).body;
      for (const k of ["users", "installs", "online", "inBackground", "blocked", "messagesToday"]) assertEquals(typeof ov[k], "number");
      assert(ov.messagesToday >= 2);
    });
  } finally {
    await s.close();
  }
});
