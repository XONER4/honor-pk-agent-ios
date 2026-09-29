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
  const groupAi = createGroupAi({ db, hub, chats, config, logger: app.log });
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
  };

  app.decorate('ctx', {
    config, db, hub, media, push, chats, groupAi, limits, verifyGoogleIdToken, auth: createAuth({ db }),
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
  app.get('/health', async () => ({ ok: true, ai: Boolean(config.deepseekApiKey) }));

  await app.register(wsRoutes);
  await app.register(deviceRoutes);
  await app.register(chatRoutes({ prefix: '/v1/chats', side: 'user' }));
  await app.register(chatRoutes({ prefix: '/v1/admin/chats', side: 'admin' }));
  await app.register(mediaRoutes);
  await app.register(notificationRoutes);
  await app.register(aiProxyRoutes);
  await app.register(adminRoutes);

  app.addHook('onClose', async () => {
    groupAi.stop();
    hub.stop();
    for (const l of Object.values(limits)) l.stop();
    await db.close();
  });

  return app;
}
