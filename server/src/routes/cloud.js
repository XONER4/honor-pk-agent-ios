// Маршруты облачного браузера. Всё под авторизацией устройства. Если Playwright/Chromium на сервере
// недоступны — возвращаем 503 с понятным текстом, не роняя остальной сервер.
import { ApiError } from '../errors.js';
import * as cloud from '../cloud-browser.js';

function wrap(err) {
  if (err instanceof cloud.CloudUnavailable) return new ApiError(503, 'cloud_unavailable', err.message);
  return err;
}

export default async function cloudRoutes(app) {
  const { auth } = app.ctx;

  // Диагностика без запуска браузера.
  app.get('/v1/cloud/status', { preHandler: auth.requireAny }, async () => cloud.status());

  // Проверка: проходит ли облачный браузер анти-бот сервиса С ЭТОГО СЕРВЕРА (датацентр-IP).
  app.post('/v1/cloud/probe', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', properties: { url: { type: 'string', maxLength: 300 } } } },
  }, async (req) => {
    try { return await cloud.probe(req.body?.url); } catch (e) { throw wrap(e); }
  });

  // Открыть/переиспользовать сессию сервиса. Возвращает снимок экрана (data:image/jpeg;base64,...).
  app.post('/v1/cloud/open', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', required: ['service'], properties: { service: { type: 'string', maxLength: 40 } } } },
  }, async (req) => {
    try { return await cloud.openSession({ deviceId: req.device.id, service: req.body.service }); }
    catch (e) { throw wrap(e); }
  });

  // Ввод пользователя (для входа) или шаг агента: тап/текст/клавиша/скролл/переход/назад.
  app.post('/v1/cloud/input', {
    preHandler: auth.requireDevice,
    schema: {
      body: {
        type: 'object', required: ['sessionId', 'type'],
        properties: {
          sessionId: { type: 'string', maxLength: 64 }, type: { type: 'string', maxLength: 20 },
          x: { type: 'number' }, y: { type: 'number' }, dy: { type: 'number' },
          text: { type: 'string', maxLength: 2000 }, key: { type: 'string', maxLength: 20 },
          url: { type: 'string', maxLength: 300 }, settle: { type: 'number' },
        },
      },
    },
  }, async (req) => {
    if (cloud.sessionDevice(req.body.sessionId) !== req.device.id) throw new ApiError(404, 'not_found', 'Сессия не найдена');
    try { return await cloud.input(req.body.sessionId, req.body); } catch (e) { throw wrap(e); }
  });

  // Текстовый слепок страницы для нейросети.
  app.post('/v1/cloud/read', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', required: ['sessionId'], properties: { sessionId: { type: 'string', maxLength: 64 } } } },
  }, async (req) => {
    if (cloud.sessionDevice(req.body.sessionId) !== req.device.id) throw new ApiError(404, 'not_found', 'Сессия не найдена');
    try { return await cloud.readPage(req.body.sessionId); } catch (e) { throw wrap(e); }
  });

  app.post('/v1/cloud/close', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', required: ['sessionId'], properties: { sessionId: { type: 'string', maxLength: 64 } } } },
  }, async (req) => {
    if (cloud.sessionDevice(req.body.sessionId) !== req.device.id) return { ok: true };
    await cloud.closeSession(req.body.sessionId);
    return { ok: true };
  });
}
