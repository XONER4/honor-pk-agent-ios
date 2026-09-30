// AI-прокси: прячет ключ DeepSeek от клиентов. Тело запроса пересылается как есть (включая stream:true и tools);
// единственное добавление — stream_options.include_usage для потоковых запросов (usage в последнем чанке).
// Ответ апстрима (SSE или JSON) передаётся клиенту байт-в-байт без буферизации; по дороге из него
// читается usage (токены) — для статистики. Ключ и тела запросов никогда не логируются.
import { Transform } from 'node:stream';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { ApiError, rateLimited } from '../errors.js';
import { NO_DEVICE, UsageSniffer } from '../metrics.js';

export const aiDisabledError = () =>
  new ApiError(503, 'ai_disabled', 'ИИ временно отключён администратором.', { code: 'ai_disabled' });

export default async function aiProxyRoutes(app) {
  const { auth, config, limits, metrics, usage, aiControl } = app.ctx;
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
    if (!aiControl.isEnabled()) throw aiDisabledError();
    if (!config.deepseekApiKey) throw new ApiError(503, 'ai_unavailable', 'ИИ временно недоступен');
    if (!allowed.has(req.body.model)) throw new ApiError(400, 'model_not_allowed', `Модель не разрешена: ${[...allowed].join(', ')}`);
    const key = req.principal.kind === 'device' ? `d:${req.device.id}` : `a:${req.admin.id}`;
    if (!limits.ai.take(key)) throw rateLimited('Лимит запросов к ИИ: попробуйте через несколько минут');

    const deviceId = req.principal.kind === 'device' ? req.device.id : NO_DEVICE;
    const started = Date.now();
    const stream = req.body.stream === true;
    const body = stream
      ? { ...req.body, stream_options: { ...(req.body.stream_options || {}), include_usage: true } }
      : req.body;

    /** Итог запроса: метрики в памяти + расход в БД (не ждём записи). */
    const finish = ({ error, tokens = null }) => {
      metrics.recordAi({ ms: Date.now() - started, error });
      usage.add(deviceId, { prompt: tokens?.prompt, completion: tokens?.completion, requests: 1, errors: error ? 1 : 0 });
    };

    // Если клиент отключился — прерываем запрос к DeepSeek (экономим токены).
    const abort = new AbortController();
    reply.raw.on('close', () => { if (!reply.raw.writableFinished) abort.abort(); });

    // Таймаут на ПОЛУЧЕНИЕ ответа от DeepSeek (заголовков). Если апстрим не ответил за 60 с —
    // прерываем, чтобы не держать запрос вечно. Сам поток ответа после этого не ограничиваем по времени
    // (длинные ответы идут сколько нужно; зависший посреди поток обрывается по таймауту чтения у клиента).
    let headerTimedOut = false;
    const headerTimer = setTimeout(() => { headerTimedOut = true; abort.abort(); }, 60_000);

    let upstream;
    try {
      upstream = await fetch(`${config.deepseekBaseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${config.deepseekApiKey}`,
          'content-type': 'application/json',
          accept: stream ? 'text/event-stream' : 'application/json',
        },
        body: JSON.stringify(body),
        signal: abort.signal,
      });
    } catch (err) {
      if (abort.signal.aborted && !headerTimedOut) {
        // Клиент ушёл раньше, чем ответил апстрим — отвечать некому.
        finish({ error: false });
        reply.hijack();
        reply.raw.destroy();
        return;
      }
      finish({ error: true });
      req.log.warn({ err: { message: err.message } }, headerTimedOut ? 'deepseek header timeout' : 'deepseek unreachable');
      throw new ApiError(504, 'upstream_timeout', 'Сервис ИИ не ответил вовремя. Повторите запрос.');
    } finally {
      clearTimeout(headerTimer);
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
    const upstreamFailed = upstream.status >= 400;
    if (!upstream.body) {
      res.end();
      finish({ error: upstreamFailed });
      return;
    }
    const sniffer = new UsageSniffer(stream);
    const tap = new Transform({
      transform(chunk, _enc, cb) {
        try { sniffer.push(chunk); } catch { /* статистика не должна ломать ответ */ }
        cb(null, chunk);
      },
    });
    try {
      await pipeline(Readable.fromWeb(upstream.body), tap, res);
      finish({ error: upstreamFailed, tokens: sniffer.finish() });
    } catch (err) {
      const clientGone = abort.signal.aborted;
      if (!clientGone) req.log.warn({ err: { message: err.message } }, 'ai proxy stream interrupted');
      finish({ error: !clientGone, tokens: sniffer.finish() });
      res.destroy();
    }
  });
}
