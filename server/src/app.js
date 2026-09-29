// Сборка приложения: БД → миграции → сервисы → плагины Fastify → маршруты.
// buildApp не слушает порт — это делает server.js (а тесты — сами, на случайном порту).
import Fastify from 'fastify';
import cors from '@fastify/cors';
import multipart from '@fastify/multipart';
import websocket from '@fastify/websocket';
import { OAuth2Client } from 'google-auth-library';

import { createDb } from './db.js';
import { migrate } from './migrations.js';
import { createAuth } from './auth.js';
import { Hub } from './hub.js';
import { createPush } from './push.js';
import { createMediaStore } from './media.js';
import { createChatService } from './chat-service.js';
import { createGroupAi } from './ai.js';
import { RateLimiter } from './ratelimit.js';
import { errorHandler, notFoundHandler } from './errors.js';
import wsRoutes from './ws.js';
import deviceRoutes from './routes/devices.js';
import { chatRoutes } from './routes/chats.js';
import mediaRoutes from './routes/media.js';
import notificationRoutes from './routes/notifications.js';
import aiProxyRoutes from './routes/ai.js';
import adminRoutes from './routes/admin.js';
import adminInsightsRoutes, { createMetricsSnapshot } from './routes/admin-insights.js';
import { LiveMetrics, createUsage } from './metrics.js';
import { createAiControl } from './ai-control.js';
import { backfillPublicIds } from './public-id.js';
import { logAdminAction } from './device-events.js';

/**
 * @param {object} config — результат loadConfig()
 * @param {object} [overrides] — для тестов: { db, pushTransport, verifyGoogleIdToken, logger }
 */
export async function buildApp(config, overrides = {}) {
  const app = Fastify({
    logger: overrides.logger ?? {
      level: config.logLevel,
      // Не логируем query-string (там может быть ?token=), заголовки и тела.
      serializers: {
        req: (req) => ({ method: req.method, url: String(req.url).split('?')[0], ip: req.ip }),
        res: (res) => ({ statusCode: res.statusCode }),
      },
    },
    trustProxy: config.trustProxy,
    bodyLimit: 1024 * 1024,
    ajv: { customOptions: { removeAdditional: true, coerceTypes: 'array', useDefaults: true, allErrors: false } },
  });

  const db = overrides.db || (await createDb(config, app.log));
  await migrate(db);
  await backfillPublicIds(db, app.log);

  const hub = new Hub({
    config,
    logger: app.log,
    persistDeviceSeen: (id, at) => db.query('UPDATE devices SET last_seen_at = $2 WHERE id = $1', [id, at]).catch(() => {}),
    persistAdminSeen: (id, at) => db.query('UPDATE admins SET last_seen_at = $2 WHERE id = $1', [id, at]).catch(() => {}),
  });
  const media = createMediaStore({ db, config, logger: app.log });
  await media.init();
  const push = createPush({ db, hub, config, logger: app.log, transport: overrides.pushTransport });
  const chats = createChatService({ db, hub, push, media, logger: app.log });
  const metrics = new LiveMetrics();
  const usage = createUsage({ db, logger: app.log });
  const aiControl = createAiControl({ db, config, logger: app.log });
  await aiControl.load();
  const groupAi = createGroupAi({ db, hub, chats, config, logger: app.log, aiControl, metrics, usage });
  chats.listeners.push(groupAi.onMessage);

  const googleClient = new OAuth2Client();
  const verifyGoogleIdToken = overrides.verifyGoogleIdToken || (async (idToken, audience) => {
    const ticket = await googleClient.verifyIdToken({ idToken, audience });
    return ticket.getPayload();
  });

  const limits = {
    ai: new RateLimiter({ max: config.aiRateMax, windowMs: config.aiRateWindowMs }),
    login: new RateLimiter({ max: config.loginRateMax, windowMs: config.loginRateWindowMs }),
    register: new RateLimiter({ max: config.registerRateMax, windowMs: config.registerRateWindowMs }),
    report: new RateLimiter({ max: config.reportRateMax, windowMs: config.reportRateWindowMs }),
  };
  const metricsSnapshot = createMetricsSnapshot({ db, hub, metrics, usage, aiControl, config });

  app.decorate('ctx', {
    config, db, hub, media, push, chats, groupAi, limits, verifyGoogleIdToken, auth: createAuth({ db }),
    metrics, usage, aiControl, metricsSnapshot,
  });

  // Метрики: каждый входящий запрос (кроме WebSocket) — в «запросы/сек»; время ответа и 5xx — по завершении.
  // AI-прокси считает свою задержку сам (ответ идёт потоком мимо Fastify).
  const skipLatency = (url) => url.startsWith('/v1/ws') || url.startsWith('/v1/ai/');
  app.addHook('onRequest', async (req) => {
    if (!String(req.url).startsWith('/v1/ws')) metrics.recordRequest();
  });
  app.addHook('onResponse', async (req, reply) => {
    if (!skipLatency(String(req.url))) metrics.recordResponse(reply.statusCode, reply.elapsedTime);
  });

  // CORS закрыт по умолчанию: мобильным приложениям он не нужен; веб-клиенты — только из CORS_ORIGINS.
  await app.register(cors, { origin: config.corsOrigins.length ? config.corsOrigins : false });
  await app.register(multipart, {
    limits: { fileSize: config.maxUploadBytes, files: 1, fields: 20, fieldSize: 1024, parts: 25 },
    throwFileSizeLimit: true,
  });
  await app.register(websocket, { options: { maxPayload: 64 * 1024 } });

  // Защитные заголовки (аналог helmet для JSON API).
  app.addHook('onSend', async (req, reply, payload) => {
    reply.header('X-Content-Type-Options', 'nosniff');
    reply.header('X-Frame-Options', 'DENY');
    reply.header('Referrer-Policy', 'no-referrer');
    reply.header('Cross-Origin-Resource-Policy', 'same-site');
    reply.header('Content-Security-Policy', "default-src 'none'; frame-ancestors 'none'");
    reply.header('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
    reply.removeHeader('X-Powered-By');
    return payload;
  });

  // JSON-парсер как стандартный (защита от __proto__/constructor), но пустое тело = {}:
  // мобильные HTTP-клиенты часто шлют Content-Type: application/json без тела (DELETE, POST …/clear).
  const defaultJson = app.getDefaultJsonParser('error', 'error');
  app.removeContentTypeParser('application/json');
  app.addContentTypeParser('application/json', { parseAs: 'string' }, (req, body, done) => {
    if (!body || /^\s*$/.test(body)) return done(null, {});
    return defaultJson(req, body, done);
  });

  app.setErrorHandler(errorHandler);
  app.setNotFoundHandler(notFoundHandler);

  // ai — есть ли на сервере ключ нейросети: без него приложения ходят к ИИ напрямую (запасной путь).
  // aiEnabled — ИИ сейчас разрешён администратором (общий выключатель и расписание).
  app.get('/health', async () => ({ ok: true, ai: Boolean(config.deepseekApiKey), aiEnabled: aiControl.isEnabled() }));

  // Погода: часть операторов (МТС) блокирует open-meteo у пользователя, а сервер ходит к нему
  // свободно. Приложение берёт погоду через этот эндпоинт — по названию города (q) или по координатам.
  const geocodeCity = async (name) => {
    for (const lang of ['ru', 'en']) {
      try {
        const u = `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(name)}&count=5&language=${lang}&format=json`;
        const r = await fetch(u, { signal: AbortSignal.timeout(7000) });
        if (!r.ok) continue;
        const first = (await r.json())?.results?.[0];
        if (first) {
          const parts = [first.name, first.admin1, first.country].filter(Boolean);
          return { name: parts.join(', '), lat: first.latitude, lon: first.longitude };
        }
      } catch { /* пробуем следующий язык */ }
    }
    return null;
  };
  app.get('/v1/weather', async (request, reply) => {
    const q = request.query || {};
    let lat = Number(q.lat), lon = Number(q.lon), place = '';
    if (q.q) {
      const found = await geocodeCity(String(q.q));
      if (!found) return reply.code(404).send({ error: 'not_found', message: 'Город не найден.' });
      lat = found.lat; lon = found.lon; place = found.name;
    }
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
      return reply.code(400).send({ error: 'bad_request', message: 'Нужны параметры q (город) или lat и lon.' });
    }
    const url = 'https://api.open-meteo.com/v1/forecast'
      + `?latitude=${lat}&longitude=${lon}`
      + '&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m'
      + '&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max'
      + `&timezone=auto&wind_speed_unit=ms&forecast_days=${Math.min(Math.max(Number(q.days) || 7, 1), 16)}`;
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(8000) });
      if (!r.ok) return reply.code(502).send({ error: 'upstream_error', message: 'Погодный сервис не ответил.' });
      const data = await r.json();
      if (place) data.honer_place = place; // разрешённое название города для приложения
      reply.header('content-type', 'application/json; charset=utf-8');
      return reply.send(data);
    } catch {
      return reply.code(502).send({ error: 'upstream_error', message: 'Погодный сервис не ответил.' });
    }
  });

  await app.register(wsRoutes);
  await app.register(deviceRoutes);
  await app.register(chatRoutes({ prefix: '/v1/chats', side: 'user' }));
  await app.register(chatRoutes({ prefix: '/v1/admin/chats', side: 'admin' }));
  await app.register(mediaRoutes);
  await app.register(notificationRoutes);
  await app.register(aiProxyRoutes);
  await app.register(adminRoutes);
  await app.register(adminInsightsRoutes);

  // Раз в METRICS_PUSH_MS — кадр {"t":"metrics"} подключённым админам (если они есть).
  let pushing = false;
  const metricsTimer = setInterval(async () => {
    if (pushing || hub.admin.sockets.size === 0) return;
    pushing = true;
    try {
      hub.sendToAdmins({ t: 'metrics', ...(await metricsSnapshot()) });
    } catch (err) {
      app.log.warn({ err: { message: err.message } }, 'metrics push failed');
    } finally {
      pushing = false;
    }
  }, Math.max(500, config.metricsPushMs));
  metricsTimer.unref();

  // Снятие блокировок с истёкшим сроком (для списков админки; при входе устройства проверяется сразу).
  const blockTimer = setInterval(async () => {
    try {
      const now = new Date();
      const rows = await db.many('SELECT id FROM devices WHERE blocked = TRUE AND blocked_until IS NOT NULL AND blocked_until <= $1', [now]);
      for (const r of rows) {
        const res = await db.query(
          'UPDATE devices SET blocked = FALSE, block_reason = NULL, blocked_until = NULL WHERE id = $1 AND blocked = TRUE AND blocked_until <= $2',
          [r.id, now]);
        if (res.rowCount) await logAdminAction(db, { deviceId: r.id, action: 'unblock_auto' }, app.log);
      }
    } catch (err) {
      app.log.warn({ err: { message: err.message } }, 'block sweep failed');
    }
  }, Math.max(1000, config.blockSweepMs));
  blockTimer.unref();

  app.addHook('onClose', async () => {
    clearInterval(metricsTimer);
    clearInterval(blockTimer);
    groupAi.stop();
    hub.stop();
    for (const l of Object.values(limits)) l.stop();
    await db.close();
  });

  return app;
}
