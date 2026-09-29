// AI-прокси: прячет ключ DeepSeek от клиентов. Тело запроса пересылается как есть (включая stream:true и tools),
// ответ апстрима (SSE или JSON) передаётся клиенту байт-в-байт без буферизации.
// Ключ и тела запросов никогда не логируются.
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { ApiError, rateLimited } from '../errors.js';

export default async function aiProxyRoutes(app) {
  const { auth, config, limits } = app.ctx;
  const allowed = new Set(config.aiAllowedModels);

  app.post('/v1/ai/chat/completions', {
    preHandler: auth.requireAny,
    bodyLimit: 4 * 1024 * 1024,
    schema: {
      body: {
        type: 'object',
        required: ['model', 'messages'],
        // Проверяем только необходимое; остальные поля (tools, temperature, …) идут в DeepSeek без изменений.
        properties: { model: { type: 'string', maxLength: 100 }, messages: { type: 'array', minItems: 1, maxItems: 1000 } },
      },
    },
  }, async (req, reply) => {
    if (!config.deepseekApiKey) throw new ApiError(503, 'ai_unavailable', 'ИИ временно недоступен');
    if (!allowed.has(req.body.model)) throw new ApiError(400, 'model_not_allowed', `Модель не разрешена: ${[...allowed].join(', ')}`);
    const key = req.principal.kind === 'device' ? `d:${req.device.id}` : `a:${req.admin.id}`;
    if (!limits.ai.take(key)) throw rateLimited('Лимит запросов к ИИ: попробуйте через несколько минут');

    // Если клиент отключился — прерываем запрос к DeepSeek (экономим токены).
    const abort = new AbortController();
    reply.raw.on('close', () => { if (!reply.raw.writableFinished) abort.abort(); });

    let upstream;
    try {
      upstream = await fetch(`${config.deepseekBaseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${config.deepseekApiKey}`,
          'content-type': 'application/json',
          accept: req.body.stream ? 'text/event-stream' : 'application/json',
        },
        body: JSON.stringify(req.body),
        signal: abort.signal,
      });
    } catch (err) {
      if (abort.signal.aborted) {
        // Клиент ушёл раньше, чем ответил апстрим — отвечать некому.
        reply.hijack();
        reply.raw.destroy();
        return;
      }
      req.log.warn({ err: { message: err.message } }, 'deepseek unreachable');
      throw new ApiError(502, 'upstream_error', 'Сервис ИИ недоступен');
    }

    // Дальше пишем в сырой ответ сами: никакой сериализации/сжатия Fastify.
    reply.hijack();
    const res = reply.raw;
    res.socket?.setNoDelay(true);
    res.writeHead(upstream.status, {
      'content-type': upstream.headers.get('content-type') || 'application/json',
      'cache-control': 'no-cache, no-transform',
      'x-accel-buffering': 'no',
      'x-content-type-options': 'nosniff',
    });
    res.flushHeaders();
    if (!upstream.body) {
      res.end();
      return;
    }
    try {
      await pipeline(Readable.fromWeb(upstream.body), res);
    } catch (err) {
      if (!abort.signal.aborted) req.log.warn({ err: { message: err.message } }, 'ai proxy stream interrupted');
      res.destroy();
    }
  });
}
