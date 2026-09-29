import { assert, assertEquals, assertMatch } from "./assert.ts";
import { randomUUID } from "node:crypto";
import { ADMIN_KEY, sleep, startMockUpstream, startServer } from "./helpers.ts";
import { backfillPublicIds } from "../public-id.ts";

Deno.test({ name: "admin account: setup + login/password", sanitizeOps: false, sanitizeResources: false }, async (t) => {
  const s = await startServer({ LOGIN_RATE_MAX: "50" });
  try {
    await t.step("setup once, login by login/password, key login still works", async () => {
      assertEquals((await s.api("GET", "/v1/admin/setup-status")).body, { hasAccount: false });
      let r = await s.api("POST", "/v1/admin/setup", { body: { login: "boss", password: "Password123" } });
      assertEquals(r.status, 401);
      r = await s.api("POST", "/v1/admin/setup", { body: { login: "boss", password: "Password123" }, headers: { "x-admin-key": "wrong" } });
      assertEquals(r.status, 401);
      r = await s.api("POST", "/v1/admin/setup", { body: { login: "b", password: "Password123" }, headers: { "x-admin-key": ADMIN_KEY } });
      assertEquals(r.status, 400);
      r = await s.api("POST", "/v1/admin/setup", { body: { login: "boss", password: "short" }, headers: { "x-admin-key": ADMIN_KEY } });
      assertEquals(r.status, 400);
      assertMatch(r.body.message, /8/);
      r = await s.api("POST", "/v1/admin/setup", { body: { login: "Boss", password: "Password123" }, headers: { "x-admin-key": ADMIN_KEY } });
      assertEquals(r.status, 200);
      assertMatch(r.body.token, /^a_/);
      assertEquals(r.body.login, "boss");
      assertEquals((await s.api("GET", "/v1/admin/overview", { token: r.body.token })).status, 200);
      assertEquals((await s.api("GET", "/v1/admin/setup-status")).body, { hasAccount: true });
      r = await s.api("POST", "/v1/admin/setup", { body: { login: "other", password: "Password123" }, headers: { "x-admin-key": ADMIN_KEY } });
      assertEquals(r.status, 409);
      r = await s.api("POST", "/v1/admin/login", { body: { login: "BOSS", password: "Password123" } });
      assertEquals(r.status, 200);
      assertEquals(r.body.login, "boss");
      assertEquals((await s.api("GET", "/v1/admin/devices", { token: r.body.token })).status, 200);
      r = await s.api("POST", "/v1/admin/login", { body: { login: "boss", password: "Password124" } });
      assertEquals(r.status, 401);
      assertEquals(r.body.message, "Неверный логин или пароль");
      assertEquals((await s.api("POST", "/v1/admin/login", { body: { login: "nobody", password: "Password123" } })).status, 401);
      assertEquals((await s.api("POST", "/v1/admin/login", { body: { login: "boss" } })).status, 400);
      assertMatch(await s.adminLogin(), /^a_/);
    });

    await t.step("without ADMIN_KEY setup needs no header", async () => {
      const s2 = await startServer({ ADMIN_KEY: "" });
      const r = await s2.api("POST", "/v1/admin/setup", { body: { login: "root", password: "Password123" } });
      assertEquals(r.status, 200);
      assertEquals((await s2.api("POST", "/v1/admin/login", { body: { login: "root", password: "Password123" } })).status, 200);
      await s2.close();
    });
  } finally {
    await s.close();
  }
});

Deno.test({ name: "admin2 features", sanitizeOps: false, sanitizeResources: false }, async (t) => {
  const upstream = await startMockUpstream((_req, body) => {
    if (body?.stream) {
      const enc = new TextEncoder();
      const parts = ['data: {"choices":[{"delta":{"content":"Привет"}}]}\n\n'];
      if ((body.stream_options as { include_usage?: boolean })?.include_usage) {
        parts.push('data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}\n\n');
      }
      parts.push("data: [DONE]\n\n");
      return new Response(parts.join(""), { status: 200, headers: { "content-type": "text/event-stream" } });
    }
    if ((body?.messages as { content: string }[])?.[0]?.content === "fail") {
      return new Response('{"error":{"message":"boom"}}', { status: 500, headers: { "content-type": "application/json" } });
    }
    return new Response(JSON.stringify({ choices: [{ message: { content: "ok" } }], usage: { prompt_tokens: 20, completion_tokens: 6 } }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  });
  const s = await startServer({ DEEPSEEK_BASE_URL: upstream.url, METRICS_PUSH_MS: "200", BLOCK_SWEEP_MS: "200" });
  const admin = await s.adminLogin();
  const ai = (token: string, content = "hi", extra: Record<string, unknown> = {}) =>
    s.api("POST", "/v1/ai/chat/completions", { token, body: { model: "deepseek-chat", messages: [{ role: "user", content }], ...extra } });

  try {
    await t.step("public id: 4 digits, unique, searchable, backfill", async () => {
      const a = await s.register({ displayName: "Анна" });
      const b = await s.register({ displayName: "Борис" });
      assertMatch(a.publicId, /^\d{4}$/);
      assert(a.publicId !== b.publicId);
      const again = await s.register({ installId: randomUUID() });
      assertMatch(again.publicId, /^\d{4}$/);
      let list = (await s.api("GET", `/v1/admin/devices?query=${a.publicId}`, { token: admin })).body;
      assert(list.some((d: { deviceId: string; publicId: string }) => d.deviceId === a.deviceId && d.publicId === a.publicId));
      list = (await s.api("GET", `/v1/admin/devices?query=%23${a.publicId}`, { token: admin })).body;
      assert(list.some((d: { deviceId: string }) => d.deviceId === a.deviceId));
      const card = (await s.api("GET", `/v1/admin/devices/${a.deviceId}`, { token: admin })).body;
      assertEquals(card.publicId, a.publicId);
      const me = await s.api("PATCH", "/v1/devices/me", { token: a.token, body: {} });
      assertEquals(me.body.publicId, a.publicId);
      const oldId = randomUUID();
      await s.ctx.kv.set(["user", oldId], { id: oldId, createdAt: Date.now(), publicId: null });
      await backfillPublicIds(s.ctx.kv);
      const row = (await s.ctx.kv.get<{ publicId: string }>(["user", oldId])).value;
      assertMatch(row!.publicId, /^\d{4}$/);
    });

    await t.step("AI proxy counts tokens (JSON+SSE), latency, errors; metrics + WS frame", async () => {
      const dev = await s.register();
      assertEquals((await ai(dev.token)).status, 200);
      const res = await fetch(`${s.base}/v1/ai/chat/completions`, {
        method: "POST",
        headers: { authorization: `Bearer ${dev.token}`, "content-type": "application/json" },
        body: JSON.stringify({ model: "deepseek-chat", stream: true, messages: [{ role: "user", content: "x" }] }),
      });
      const text = await res.text();
      assertMatch(text, /"usage"/);
      assertEquals((await ai(dev.token, "fail")).status, 500);
      await sleep(150);
      const card = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(card.usage.today, { prompt: 30, completion: 10, total: 40, requests: 3, errors: 1 });
      assertEquals(card.usage.total.total, 40);
      assertEquals(card.aiTokens, 40);
      const m = (await s.api("GET", "/v1/admin/metrics", { token: admin })).body;
      for (const k of ["online", "rps", "tokensToday", "tokensTotal", "errorsToday", "aiLatencyMs", "model"]) assert(k in m, k);
      assert(m.tokensToday >= 40);
      assert(m.tokensTotal >= m.tokensToday);
      assert(m.errorsToday >= 1);
      assert(m.rps > 0);
      assertEquals(typeof m.aiLatencyMs.p50, "number");
      assertEquals(m.model.enabled, true);
      const sorted = (await s.api("GET", "/v1/admin/devices?sort=tokens", { token: admin })).body;
      assertEquals(sorted[0].deviceId, dev.deviceId);
      const ws = await s.connect(admin);
      const frame = await ws.waitFor((f) => f.t === "metrics", 2000);
      assert(frame.tokensToday >= 40);
      ws.close();
    });

    await t.step("device events: install, update; overview counters", async () => {
      const before = (await s.api("GET", "/v1/admin/overview", { token: admin })).body;
      const dev = await s.register({ appVersion: "10.44.0" });
      await s.api("PATCH", "/v1/devices/me", { token: dev.token, body: { appVersion: "10.45.0" } });
      await s.api("PATCH", "/v1/devices/me", { token: dev.token, body: { appVersion: "10.45.0" } });
      const events = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}/events`, { token: admin })).body;
      assertEquals(events.map((e: { kind: string; fromVersion: string | null; toVersion: string | null }) => [e.kind, e.fromVersion, e.toVersion]), [
        ["update", "10.44.0", "10.45.0"],
        ["install", null, "10.44.0"],
      ]);
      const after = (await s.api("GET", "/v1/admin/overview", { token: admin })).body;
      assertEquals(after.updates - before.updates, 1);
      assertEquals(typeof after.inactive, "number");
      assertEquals(after.inactiveDays, 7);
    });

    await t.step("client reports: post, list/filter, counts", async () => {
      const dev = await s.register();
      let r = await s.api("POST", "/v1/devices/me/report", { token: dev.token, body: { kind: "crash", message: "NullPointerException", stack: "at a.b(C.kt:1)", appVersion: "10.45.0" } });
      assertEquals(r.status, 200);
      r = await s.api("POST", "/v1/devices/me/report", { token: dev.token, body: { kind: "error", message: "AI 503" } });
      assertEquals(r.status, 200);
      r = await s.api("POST", "/v1/devices/me/report", { token: dev.token, body: { kind: "other", message: "x" } });
      assertEquals(r.status, 400);
      const all = (await s.api("GET", `/v1/admin/reports?deviceId=${dev.deviceId}`, { token: admin })).body;
      assertEquals(all.length, 2);
      assertEquals(all[0].kind, "error");
      assertEquals(all[1].stack, "at a.b(C.kt:1)");
      assertEquals(all[1].appVersion, "10.45.0");
      assertMatch(all[1].publicId, /^\d{4}$/);
      const crashes = (await s.api("GET", "/v1/admin/reports?kind=crash", { token: admin })).body;
      assert(crashes.every((x: { kind: string }) => x.kind === "crash"));
      const row = (await s.api("GET", "/v1/admin/devices", { token: admin })).body.find((d: { deviceId: string }) => d.deviceId === dev.deviceId);
      assertEquals(row.reports, 2);
    });

    await t.step("block with term: auto-unblock after `until`", async () => {
      const dev = await s.register();
      let r = await s.api("POST", `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: true, until: new Date(Date.now() - 1000).toISOString() } });
      assertEquals(r.status, 400);
      const until = new Date(Date.now() + 1200).toISOString();
      r = await s.api("POST", `/v1/admin/devices/${dev.deviceId}/block`, { token: admin, body: { blocked: true, reason: "Флуд", until } });
      assertEquals(r.status, 200);
      assertEquals(r.body.blockReason, "Флуд");
      assertEquals(r.body.blockedUntil, until);
      const x = await s.api("GET", "/v1/chats", { token: dev.token });
      assertEquals(x.status, 403);
      assertEquals(x.body, { error: "blocked", message: "Флуд", until });
      await sleep(1500);
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).status, 200);
      const card = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(card.blocked, false);
      const actions = (await s.api("GET", `/v1/admin/actions?deviceId=${dev.deviceId}`, { token: admin })).body;
      assertEquals(actions.map((a: { action: string }) => a.action).slice(0, 2), ["unblock_auto", "block"]);
      assertEquals(actions[1].detail.reason, "Флуд");
    });

    await t.step("overrides: set, delivered in register/PATCH and WS", async () => {
      const installId = randomUUID();
      const dev = await s.register({ installId });
      assertEquals(dev.overrides, {});
      const ws = await s.connect(dev.token);
      let r = await s.api("PATCH", `/v1/admin/devices/${dev.deviceId}/overrides`, { token: admin, body: { forceLanguage: "en", disableSearch: true, maxMessagesPerDay: 50 } });
      assertEquals(r.status, 200);
      assertEquals(r.body.overrides, { forceLanguage: "en", disableSearch: true, maxMessagesPerDay: 50 });
      const f = await ws.waitFor((x) => x.t === "overrides");
      assertEquals(f.overrides.maxMessagesPerDay, 50);
      r = await s.api("PATCH", `/v1/admin/devices/${dev.deviceId}/overrides`, { token: admin, body: { disableSearch: null } });
      assertEquals(r.body.overrides, { forceLanguage: "en", maxMessagesPerDay: 50 });
      r = await s.api("PATCH", `/v1/admin/devices/${dev.deviceId}/overrides`, { token: admin, body: { forceLanguage: "de" } });
      assertEquals(r.status, 400);
      const again = await s.register({ installId });
      assertEquals(again.overrides, { forceLanguage: "en", maxMessagesPerDay: 50 });
      const me = await s.api("PATCH", "/v1/devices/me", { token: again.token, body: {} });
      assertEquals(me.body.overrides, { forceLanguage: "en", maxMessagesPerDay: 50 });
      const card = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(card.overrides, { forceLanguage: "en", maxMessagesPerDay: 50 });
      ws.close();
    });

    await t.step("notes CRUD + action log", async () => {
      const dev = await s.register();
      let r = await s.api("POST", `/v1/admin/devices/${dev.deviceId}/notes`, { token: admin, body: { text: "  Постоянный клиент  " } });
      assertEquals(r.status, 201);
      const note = r.body;
      assertEquals(note.text, "Постоянный клиент");
      r = await s.api("PATCH", `/v1/admin/notes/${note.id}`, { token: admin, body: { text: "VIP" } });
      assertEquals(r.body.text, "VIP");
      assert(r.body.updatedAt);
      r = await s.api("GET", `/v1/admin/devices/${dev.deviceId}/notes`, { token: admin });
      assertEquals(r.body.map((n: { text: string }) => n.text), ["VIP"]);
      assertEquals((await s.api("DELETE", `/v1/admin/notes/${note.id}`, { token: admin })).status, 200);
      assertEquals((await s.api("DELETE", `/v1/admin/notes/${note.id}`, { token: admin })).status, 404);
      assertEquals((await s.api("GET", `/v1/admin/devices/${dev.deviceId}/notes`, { token: admin })).body.length, 0);
      await s.api("POST", "/v1/admin/notifications", { token: admin, body: { deviceId: dev.deviceId, title: "Привет", body: "Текст" } });
      await s.api("POST", "/v1/admin/notifications", { token: admin, body: { deviceId: null, title: "Всем", body: "Текст" } });
      const actions = (await s.api("GET", "/v1/admin/actions?limit=5", { token: admin })).body;
      assertEquals(actions[0].action, "broadcast");
      assertEquals(actions[0].detail.title, "Всем");
      assertEquals(actions[1].action, "notify");
      assertEquals(actions[1].deviceId, dev.deviceId);
      assertEquals(actions[1].adminName, "Администратор");
    });

    await t.step("global AI switch: 503, health, schedule validation", async () => {
      const dev = await s.register();
      let r = await s.api("POST", "/v1/admin/ai", { token: admin, body: { enabled: false } });
      assertEquals(r.status, 200);
      assertEquals(r.body.enabled, false);
      assertEquals(r.body.effective, false);
      r = await ai(dev.token);
      assertEquals(r.status, 503);
      assertEquals(r.body.error, "ai_disabled");
      assertEquals(r.body.code, "ai_disabled");
      assertEquals(r.body.message, "ИИ временно отключён администратором.");
      assertEquals((await s.api("GET", "/health")).body.aiEnabled, false);
      assertEquals((await s.api("GET", "/v1/admin/metrics", { token: admin })).body.model.enabled, false);
      r = await s.api("POST", "/v1/admin/ai", { token: admin, body: { schedule: [{ days: [1], from: "25:00", to: "10:00" }] } });
      assertEquals(r.status, 400);
      r = await s.api("POST", "/v1/admin/ai", { token: admin, body: { timezone: "Mars/Olympus" } });
      assertEquals(r.status, 400);
      r = await s.api("POST", "/v1/admin/ai", { token: admin, body: { enabled: true, schedule: [{ days: [7, 1, 2, 3, 4, 5, 6], from: "00:00", to: "00:00" }], timezone: "UTC" } });
      assertEquals(r.body.effective, true);
      assertEquals(r.body.schedule[0].days, [1, 2, 3, 4, 5, 6, 7]);
      assertEquals((await ai(dev.token)).status, 200);
      assertEquals((await s.api("GET", "/health")).body.aiEnabled, true);
      assertEquals((await s.api("GET", "/v1/admin/ai", { token: admin })).body.timezone, "UTC");
      r = await s.api("POST", "/v1/admin/ai", { token: admin, body: { schedule: [] } });
      assertEquals(r.body.schedule, []);
    });

    await t.step("group AI silent while switched off; tokens counted on chat device", async () => {
      const dev = await s.register();
      await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/ai`, { token: admin, body: { enabled: true } });
      await s.api("POST", "/v1/admin/ai", { token: admin, body: { enabled: false } });
      const n = upstream.requests.length;
      await s.api("POST", `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: "Honer, привет" } });
      await sleep(400);
      assertEquals(upstream.requests.length, n);
      await s.api("POST", "/v1/admin/ai", { token: admin, body: { enabled: true } });
      await s.api("POST", `/v1/chats/${dev.adminChatId}/messages`, { token: dev.token, body: { clientId: randomUUID(), text: "Honer, привет ещё раз" } });
      await sleep(400);
      assertEquals(upstream.requests.length, n + 1);
      await sleep(150);
      const card = (await s.api("GET", `/v1/admin/devices/${dev.deviceId}`, { token: admin })).body;
      assertEquals(card.usage.today.total, 26);
    });
  } finally {
    await s.close();
    await upstream.close();
  }
});
