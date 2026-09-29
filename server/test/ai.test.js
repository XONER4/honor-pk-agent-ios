import { test, before, after, describe } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startServer, startMockUpstream, sleep } from './helpers.js';
import { isQuestion, mentionsAi } from '../src/ai.js';

// ---------- мок DeepSeek ----------
let releaseStream = null;
let upstream;
before(async () => {
  upstream = await startMockUpstream((req, res, body) => {
    if (body?.messages?.[0]?.content === 'upstream-error') {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'bad request from deepseek' } }));
      return;
    }
    if (body?.stream) {
      // SSE: первый чанк сразу, остальное — только когда тест «отпустит» (доказывает отсутствие буферизации).
      res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-cache' });
      res.write('data: {"choices":[{"delta":{"content":"При"}}]}\n\n');
      new Promise((r) => { releaseStream = r; }).then(() => {
        res.write('data: {"choices":[{"delta":{"content":"вет"}}]}\n\n');
        res.end('data: [DONE]\n\n');
      });
      return;
    }
    // Нестриминговый ответ (прокси и групповой ИИ).
    setTimeout(() => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ id: 'x', choices: [{ index: 0, message: { role: 'assistant', content: 'Ответ Honer AI' } }] }));
    }, 80);
  });
});
after(() => upstream.close());

test('mention / question detection (unit)', () => {
  for (const t of ['Honer, привет', 'эй ИИ помоги', 'ask the AI', 'Спроси нейросеть', 'бот, ты тут?', 'Honer AI']) assert.ok(mentionsAi(t), t);
  for (const t of ['привет', 'заботиться', 'said', 'главный', 'ботинки', 'Сегодня хорошая погода']) assert.ok(!mentionsAi(t), t);
  for (const t of ['Сколько стоит?', 'как включить тёмную тему', 'Where is it']) assert.ok(isQuestion(t), t);
  for (const t of ['Ок, спасибо', 'Понятно.', '']) assert.ok(!isQuestion(t), t);
});

describe('AI proxy /v1/ai/chat/completions', () => {
  let s;
  let dev;
  before(async () => {
    s = await startServer({ DEEPSEEK_BASE_URL: upstream.url, AI_RATE_MAX: '5' });
    dev = await s.register();
  });
  after(() => s.close());

  test('streams SSE bytes through without buffering and injects the key', async () => {
    const body = {
      model: 'deepseek-chat', stream: true, temperature: 0.3,
      messages: [{ role: 'user', content: 'Привет' }],
      tools: [{ type: 'function', function: { name: 'get_time', parameters: { type: 'object', properties: {} } } }],
    };
    const res = await fetch(`${s.base}/v1/ai/chat/completions`, {
      method: 'POST', headers: { authorization: `Bearer ${dev.token}`, 'content-type': 'application/json' }, body: JSON.stringify(body),
    });
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /text\/event-stream/);
    assert.equal(res.headers.get('x-accel-buffering'), 'no');
    assert.equal(res.headers.get('content-encoding'), null);

    const reader = res.body.getReader();
    const dec = new TextDecoder();
    const first = dec.decode((await reader.read()).value);
    // Первый чанк получен, пока апстрим ещё НЕ завершил ответ.
    assert.equal(first, 'data: {"choices":[{"delta":{"content":"При"}}]}\n\n');
    releaseStream();
    let rest = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      rest += dec.decode(value, { stream: true });
    }
    assert.equal(rest, 'data: {"choices":[{"delta":{"content":"вет"}}]}\n\ndata: [DONE]\n\n');

    const seen = upstream.requests.at(-1);
    assert.equal(seen.url, '/chat/completions');
    assert.equal(seen.headers.authorization, 'Bearer sk-test-key');
    assert.deepEqual(seen.body, body, 'body forwarded unchanged');
  });

  test('non-streaming JSON and upstream errors are passed through', async () => {
    let r = await s.api('POST', '/v1/ai/chat/completions', { token: dev.token, body: { model: 'deepseek-reasoner', messages: [{ role: 'user', content: 'hi' }] } });
    assert.equal(r.status, 200);
    assert.equal(r.body.choices[0].message.content, 'Ответ Honer AI');
    r = await s.api('POST', '/v1/ai/chat/completions', { token: dev.token, body: { model: 'deepseek-chat', messages: [{ role: 'user', content: 'upstream-error' }] } });
    assert.equal(r.status, 400);
    assert.equal(r.body.error.message, 'bad request from deepseek');
  });

  test('model allow-list, auth, rate limit', async () => {
    let r = await s.api('POST', '/v1/ai/chat/completions', { token: dev.token, body: { model: 'gpt-4o', messages: [{ role: 'user', content: 'x' }] } });
    assert.equal(r.status, 400);
    assert.equal(r.body.error, 'model_not_allowed');
    r = await s.api('POST', '/v1/ai/chat/completions', { body: { model: 'deepseek-chat', messages: [{ role: 'user', content: 'x' }] } });
    assert.equal(r.status, 401);

    const d2 = await s.register();
    const ok = { model: 'deepseek-chat', messages: [{ role: 'user', content: 'x' }] };
    for (let i = 0; i < 5; i++) assert.equal((await s.api('POST', '/v1/ai/chat/completions', { token: d2.token, body: ok })).status, 200);
    r = await s.api('POST', '/v1/ai/chat/completions', { token: d2.token, body: ok });
    assert.equal(r.status, 429);
    assert.equal(r.body.error, 'rate_limited');
    // Лимит — на устройство: другое устройство не затронуто.
    const d3 = await s.register();
    assert.equal((await s.api('POST', '/v1/ai/chat/completions', { token: d3.token, body: ok })).status, 200);
  });

  test('503 when DEEPSEEK_API_KEY is not configured', async () => {
    const s2 = await startServer({ DEEPSEEK_BASE_URL: upstream.url, DEEPSEEK_API_KEY: '' });
    const d = await s2.register();
    const r = await s2.api('POST', '/v1/ai/chat/completions', { token: d.token, body: { model: 'deepseek-chat', messages: [{ role: 'user', content: 'x' }] } });
    assert.equal(r.status, 503);
    assert.equal(r.body.error, 'ai_unavailable');
    await s2.close();
  });
});

describe('group chat AI', () => {
  let s;
  let admin;
  before(async () => {
    s = await startServer({ DEEPSEEK_BASE_URL: upstream.url, AI_QUESTION_DELAY_MS: '300' });
    admin = await s.adminLogin();
  });
  after(() => s.close());

  const send = (token, chatId, text, prefix = '/v1/chats') =>
    s.api('POST', `${prefix}/${chatId}/messages`, { token, body: { clientId: randomUUID(), text } });
  const aiMessages = async (token, chatId) =>
    (await s.api('GET', `/v1/chats/${chatId}/messages`, { token })).body.filter((m) => m.sender === 'ai');

  async function setup() {
    const dev = await s.register({ displayName: 'Маша' });
    const ws = await s.connect(dev.token);
    const r = await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/ai`, { token: admin, body: { enabled: true } });
    assert.equal(r.status, 200);
    assert.equal(r.body.aiEnabled, true);
    await ws.waitFor((f) => f.t === 'message' && f.message.sender === 'ai'); // «Honer AI присоединился…»
    return { dev, ws };
  }

  test('enabling AI is visible to the device; mention triggers an answer with typing who:ai', async () => {
    const { dev, ws } = await setup();
    assert.equal((await s.api('GET', '/v1/chats', { token: dev.token })).body[0].aiEnabled, true);
    const n = upstream.requests.length;

    await send(dev.token, dev.adminChatId, 'Honer, как поменять аватар');
    await ws.waitFor((f) => f.t === 'typing' && f.who === 'ai' && f.typing === true);
    const msg = await ws.waitFor((f) => f.t === 'message' && f.message.sender === 'ai' && f.message.text === 'Ответ Honer AI');
    assert.equal(msg.chatId, dev.adminChatId);
    await ws.waitFor((f) => f.t === 'typing' && f.who === 'ai' && f.typing === false);

    assert.equal(upstream.requests.length, n + 1);
    const req = upstream.requests.at(-1);
    assert.equal(req.headers.authorization, 'Bearer sk-test-key');
    assert.equal(req.body.model, 'deepseek-flash');
    assert.equal(req.body.messages[0].role, 'system');
    assert.match(req.body.messages[0].content, /Honer AI/);
    assert.match(req.body.messages[0].content, /Маша/);
    const last = req.body.messages.at(-1);
    assert.equal(last.role, 'user');
    assert.equal(last.content, '[Пользователь] Honer, как поменять аватар');
    assert.ok(req.body.messages.some((m) => m.role === 'assistant'), 'AI sees its own previous messages');
    ws.close();
  });

  test('plain statement → silence; admin mention → answer', async () => {
    const { dev, ws } = await setup();
    const n = upstream.requests.length;
    await send(dev.token, dev.adminChatId, 'Ок, спасибо');
    await sleep(600);
    assert.equal(upstream.requests.length, n);
    assert.equal((await aiMessages(dev.token, dev.adminChatId)).length, 1, 'only the join message');

    await send(admin, dev.adminChatId, 'ИИ, помоги пользователю', '/v1/admin/chats');
    await ws.waitFor((f) => f.t === 'message' && f.message.text === 'Ответ Honer AI');
    assert.equal(upstream.requests.at(-1).body.messages.at(-1).content, '[Администратор] ИИ, помоги пользователю');
    ws.close();
  });

  test('unanswered direct question → AI answers after the delay', async () => {
    const { dev, ws } = await setup();
    const t0 = Date.now();
    await send(dev.token, dev.adminChatId, 'Сколько стоит подписка?');
    await sleep(150);
    assert.equal((await aiMessages(dev.token, dev.adminChatId)).length, 1, 'not before the delay');
    await ws.waitFor((f) => f.t === 'message' && f.message.text === 'Ответ Honer AI', 3000);
    assert.ok(Date.now() - t0 >= 300);
    ws.close();
  });

  test('question answered by admin within the delay → AI stays silent', async () => {
    const { dev, ws } = await setup();
    const n = upstream.requests.length;
    await send(dev.token, dev.adminChatId, 'Где настройки уведомлений?');
    await sleep(100);
    await send(admin, dev.adminChatId, 'В профиле, раздел «Уведомления»', '/v1/admin/chats');
    await sleep(700);
    assert.equal(upstream.requests.length, n);
    assert.equal((await aiMessages(dev.token, dev.adminChatId)).length, 1);
    ws.close();
  });

  test('AI disabled → no answers even on mention', async () => {
    const { dev, ws } = await setup();
    const r = await s.api('POST', `/v1/admin/chats/${dev.adminChatId}/ai`, { token: admin, body: { enabled: false } });
    assert.equal(r.body.aiEnabled, false);
    const n = upstream.requests.length;
    await send(dev.token, dev.adminChatId, 'Honer, ты тут?');
    await sleep(600);
    assert.equal(upstream.requests.length, n);
    ws.close();
  });
});
