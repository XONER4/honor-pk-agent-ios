import { assert, assertEquals, assertMatch } from "./assert.ts";
import { randomUUID } from "node:crypto";
import { sleep, startMockUpstream, startServer } from "./helpers.ts";

// Мок DeepSeek: ветвит по телу запроса. releaseStream отпускает вторую половину SSE.
let releaseStream: (() => void) | null = null;

Deno.test({ name: "ai proxy & group ai", sanitizeOps: false, sanitizeResources: false }, async (t) => {
  const upstream = await startMockUpstream((_req, body) => {
    if (body?.messages && (body.messages as { content: string }[])[0]?.content === "upstream-error") {
      return new Response(JSON.stringify({ error: { message: "bad request from deepseek" } }), { status: 400, headers: { "content-type": "application/json" } });
    }
    if (body?.stream) {
      const stream = new ReadableStream({
        start(controller) {
          const enc = new TextEncoder();
          controller.enqueue(enc.encode('data: {"choices":[{"delta":{"content":"При"}}]}\n\n'));
          new Promise<void>((r) => {
            releaseStream = r;
          }).then(() => {
            controller.enqueue(enc.encode('data: {"choices":[{"delta":{"content":"вет"}}]}\n\n'));
            controller.enqueue(enc.encode("data: [DONE]\n\n"));
            controller.close();
          });
        },
      });
      return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-cache" } });
    }
    return new Response(JSON.stringify({ id: "x", choices: [{ index: 0, message: { role: "assistant", content: "Ответ Honer AI" } }] }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  });

  const s = await startServer({ DEEPSEEK_BASE_URL: upstream.url, AI_RATE_MAX: "5", AI_QUESTION_DELAY_MS: "300" });
  const admin = await s.adminLogin();

  try {
    await t.step("streams SSE bytes without buffering + injects key", async () => {
      const dev = await s.register();
      const body = {
        model: "deepseek-chat",
        stream: true,
        temperature: 0.3,
        messages: [{ role: "user", content: "Привет" }],
        tools: [{ type: "function", function: { name: "get_time", parameters: { type: "object", properties: {} } } }],
      };
      const res = await fetch(`${s.base}/v1/ai/chat/completions`, {
        method: "POST",
        headers: { authorization: `Bearer ${dev.token}`, "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      assertEquals(res.status, 200);
      assertMatch(res.headers.get("content-type") || "", /text\/event-stream/);
      assertEquals(res.headers.get("x-accel-buffering"), "no");
      assertEquals(res.headers.get("content-encoding"), null);
      const reader = res.body!.getReader();
      const dec = new TextDecoder();
      const first = dec.decode((await reader.read()).value);
      assertEquals(first, 'data: {"choices":[{"delta":{"content":"При"}}]}\n\n');
      releaseStream!();
      let rest = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        rest += dec.decode(value, { stream: true });
      }
      assertEquals(rest, 'data: {"choices":[{"delta":{"content":"вет"}}]}\n\ndata: [DONE]\n\n');
      const seen = upstream.requests.at(-1)!;
      assertEquals(seen.url, "/chat/completions");
      assertEquals(seen.headers.authorization, "Bearer sk-test-key");
      assertEquals(seen.body, { ...body, stream_options: { include_usage: true } });
    });

    await t.step("non-streaming JSON + upstream errors pass through", async () => {
      const dev = await s.register();
      let r = await s.api("POST", "/v1/ai/chat/completions", { token: dev.token, body: { model: "deepseek-reasoner", messages: [{ role: "user", content: "hi" }] } });
      assertEquals(r.status, 200);
      assertEquals(r.body.choices[0].message.content, "Ответ Honer AI");
      r = await s.api("POST", "/v1/ai/chat/completions", { token: dev.token, body: { model: "deepseek-chat", messages: [{ role: "user", content: "upstream-error" }] } });
      assertEquals(r.status, 400);
      assertEquals(r.body.error.message, "bad request from deepseek");
    });

    await t.step("model allow-list, auth, rate limit", async () => {
      const dev = await s.register();
      let r = await s.api("POST", "/v1/ai/chat/completions", { token: dev.token, body: { model: "gpt-4o", messages: [{ role: "user", content: "x" }] } });
      assertEquals(r.status, 400);
      assertEquals(r.body.error, "model_not_allowed");
      r = await s.api("POST", "/v1/ai/chat/completions", { body: { model: "deepseek-chat", messages: [{ role: "user", content: "x" }] } });
      assertEquals(r.status, 401);
      const d2 = await s.register();
      const ok = { model: "deepseek-chat", messages: [{ role: "user", content: "x" }] };
      for (let i = 0; i < 5; i++) assertEquals((await s.api("POST", "/v1/ai/chat/completions", { token: d2.token, body: ok })).status, 200);
      r = await s.api("POST", "/v1/ai/chat/completions", { token: d2.token, body: ok });
      assertEquals(r.status, 429);
      assertEquals(r.body.error, "rate_limited");
      const d3 = await s.register();
      assertEquals((await s.api("POST", "/v1/ai/chat/completions", { token: d3.token, body: ok })).status, 200);
    });

    await t.step("503 when key not configured", async () => {
      const s2 = await startServer({ DEEPSEEK_BASE_URL: upstream.url, DEEPSEEK_API_KEY: "" });
      const d = await s2.register();
      const r = await s2.api("POST", "/v1/ai/chat/completions", { token: d.token, body: { model: "deepseek-chat", messages: [{ role: "user", content: "x" }] } });
      assertEquals(r.status, 503);
      assertEquals(r.body.error, "ai_unavailable");
      await s2.close();
    });

    // ---------- групповой ИИ ----------
    const send = (token: string, chatId: string, text: string, prefix = "/v1/chats") => s.api("POST", `${prefix}/${chatId}/messages`, { token, body: { clientId: randomUUID(), text } });
    const aiMessages = async (token: string, chatId: string) => (await s.api("GET", `/v1/chats/${chatId}/messages`, { token })).body.filter((m: { sender: string }) => m.sender === "ai");
    async function setup() {
      const dev = await s.register({ displayName: "Маша" });
      const ws = await s.connect(dev.token);
      const r = await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/ai`, { token: admin, body: { enabled: true } });
      assertEquals(r.status, 200);
      assertEquals(r.body.aiEnabled, true);
      await ws.waitFor((f) => f.t === "message" && f.message.sender === "ai");
      return { dev, ws };
    }

    await t.step("enable visible; mention triggers answer with typing who:ai", async () => {
      const { dev, ws } = await setup();
      assertEquals((await s.api("GET", "/v1/chats", { token: dev.token })).body[0].aiEnabled, true);
      const n = upstream.requests.length;
      await send(dev.token, dev.adminChatId, "Honer, как поменять аватар");
      await ws.waitFor((f) => f.t === "typing" && f.who === "ai" && f.typing === true);
      const msg = await ws.waitFor((f) => f.t === "message" && f.message.sender === "ai" && f.message.text === "Ответ Honer AI");
      assertEquals(msg.chatId, dev.adminChatId);
      await ws.waitFor((f) => f.t === "typing" && f.who === "ai" && f.typing === false);
      assertEquals(upstream.requests.length, n + 1);
      const req = upstream.requests.at(-1)!;
      assertEquals(req.headers.authorization, "Bearer sk-test-key");
      assertEquals((req.body as { model: string }).model, "deepseek-flash");
      const messages = (req.body as { messages: { role: string; content: string }[] }).messages;
      assertEquals(messages[0].role, "system");
      assertMatch(messages[0].content, /Honer AI/);
      assertMatch(messages[0].content, /Маша/);
      const last = messages.at(-1)!;
      assertEquals(last.role, "user");
      assertEquals(last.content, "[Пользователь] Honer, как поменять аватар");
      assert(messages.some((m) => m.role === "assistant"));
      ws.close();
    });

    await t.step("plain statement → silence; admin mention → answer", async () => {
      const { dev, ws } = await setup();
      const n = upstream.requests.length;
      await send(dev.token, dev.adminChatId, "Ок, спасибо");
      await sleep(600);
      assertEquals(upstream.requests.length, n);
      assertEquals((await aiMessages(dev.token, dev.adminChatId)).length, 1);
      await send(admin, dev.adminChatId, "ИИ, помоги пользователю", "/v1/admin/chats");
      await ws.waitFor((f) => f.t === "message" && f.message.text === "Ответ Honer AI");
      assertEquals((upstream.requests.at(-1)!.body as { messages: { content: string }[] }).messages.at(-1)!.content, "[Администратор] ИИ, помоги пользователю");
      ws.close();
    });

    await t.step("unanswered direct question → AI answers after delay", async () => {
      const { dev, ws } = await setup();
      const t0 = Date.now();
      await send(dev.token, dev.adminChatId, "Сколько стоит подписка?");
      await sleep(150);
      assertEquals((await aiMessages(dev.token, dev.adminChatId)).length, 1);
      await ws.waitFor((f) => f.t === "message" && f.message.text === "Ответ Honer AI", 3000);
      assert(Date.now() - t0 >= 300);
      ws.close();
    });

    await t.step("question answered by admin within delay → silence", async () => {
      const { dev, ws } = await setup();
      const n = upstream.requests.length;
      await send(dev.token, dev.adminChatId, "Где настройки уведомлений?");
      await sleep(100);
      await send(admin, dev.adminChatId, "В профиле, раздел «Уведомления»", "/v1/admin/chats");
      await sleep(700);
      assertEquals(upstream.requests.length, n);
      assertEquals((await aiMessages(dev.token, dev.adminChatId)).length, 1);
      ws.close();
    });

    await t.step("AI disabled → no answers even on mention", async () => {
      const { dev, ws } = await setup();
      const r = await s.api("POST", `/v1/admin/chats/${dev.adminChatId}/ai`, { token: admin, body: { enabled: false } });
      assertEquals(r.body.aiEnabled, false);
      const n = upstream.requests.length;
      await send(dev.token, dev.adminChatId, "Honer, ты тут?");
      await sleep(600);
      assertEquals(upstream.requests.length, n);
      ws.close();
    });
  } finally {
    await s.close();
    await upstream.close();
  }
});
