// Эндпоинты устройства: регистрация, профиль, статистика, отчёты об ошибках.
import { randomUUID } from 'node:crypto';
import { blocked, rateLimited } from '../errors.js';
import { unblockIfExpired } from '../auth.js';
import { json } from '../db.js';
import { recordDeviceEvent } from '../device-events.js';
import { ensurePublicId } from '../public-id.js';
import { deviceProfileProps } from './schemas.js';

// API-поле → колонка devices (только из этого словаря).
const PROFILE_COLUMNS = {
  platform: 'platform',
  deviceModel: 'device_model',
  deviceName: 'device_name',
  osVersion: 'os_version',
  appVersion: 'app_version',
  displayName: 'display_name',
  birthday: 'birthday',
  language: 'language',
  licenseAcceptedAt: 'license_accepted_at',
  pushToken: 'push_token',
};

/** Персональные ограничения устройства (как хранятся в devices.overrides). */
export const deviceOverrides = (device) => {
  const o = json(device?.overrides, null);
  return o && typeof o === 'object' && !Array.isArray(o) ? o : {};
};

/** Обновляет присланные поля профиля устройства; смена appVersion пишет событие update (from → to). */
async function updateProfile(db, deviceId, body, extraSets = []) {
  const before = body.appVersion !== undefined
    ? await db.one('SELECT app_version FROM devices WHERE id = $1', [deviceId])
    : null;
  const sets = [...extraSets];
  const params = [deviceId];
  for (const [field, col] of Object.entries(PROFILE_COLUMNS)) {
    if (body[field] === undefined) continue;
    params.push(body[field]);
    sets.push(`${col} = $${params.length}`);
  }
  params.push(new Date());
  sets.push(`updated_at = $${params.length}`);
  await db.query(`UPDATE devices SET ${sets.join(', ')} WHERE id = $1`, params);
  // Один push-токен — одно устройство (после переустановки токен может «переехать»).
  if (body.pushToken) {
    await db.query('UPDATE devices SET push_token = NULL WHERE push_token = $1 AND id <> $2', [body.pushToken, deviceId]);
  }
  if (before?.app_version && body.appVersion && before.app_version !== body.appVersion) {
    await recordDeviceEvent(db, deviceId, 'update', { from: before.app_version, to: body.appVersion });
  }
}

export default async function deviceRoutes(app) {
  const { db, auth, chats, limits } = app.ctx;

  app.post('/v1/devices/register', {
    schema: {
      body: {
        type: 'object',
        required: ['installId', 'platform'],
        properties: { installId: { type: 'string', minLength: 8, maxLength: 128 }, ...deviceProfileProps },
      },
    },
  }, async (req) => {
    if (!limits.register.take(req.ip)) throw rateLimited();
    const b = req.body;
    let device = await db.one('SELECT * FROM devices WHERE install_id = $1', [b.installId]);
    let created = false;

    if (!device) {
      const now = new Date();
      const userId = randomUUID();
      const deviceId = randomUUID();
      await db.query('INSERT INTO users (id, created_at) VALUES ($1, $2)', [userId, now]);
      const inserted = await db.one(
        `INSERT INTO devices (id, user_id, install_id, platform, installed_at, updated_at, register_count)
         VALUES ($1, $2, $3, $4, $5, $5, 0) ON CONFLICT (install_id) DO NOTHING RETURNING id`,
        [deviceId, userId, b.installId, b.platform, now]);
      if (inserted && inserted.id === deviceId) {
        // Новый installId = новая установка (счётчик installs).
        await db.query(
          `INSERT INTO counters (name, value) VALUES ('installs', 1)
           ON CONFLICT (name) DO UPDATE SET value = counters.value + 1`);
        created = true;
      } else {
        await db.query('DELETE FROM users WHERE id = $1', [userId]); // проиграли гонку параллельной регистрации
      }
      device = await db.one('SELECT * FROM devices WHERE install_id = $1', [b.installId]);
    }
    device = await unblockIfExpired(db, device);
    if (device.blocked) throw blocked(device.block_reason, device.blocked_until);

    await updateProfile(db, device.id, b, ['register_count = register_count + 1']);
    if (created) await recordDeviceEvent(db, device.id, 'install', { to: b.appVersion });
    const chat = (await chats.getDeviceChat(device.id)) || (await chats.createChatForDevice(device.id));
    const token = await auth.issueDeviceToken(device.id);
    const publicId = await ensurePublicId(db, device.user_id);
    return {
      deviceId: device.id, token, userId: device.user_id, adminChatId: chat.id, publicId, overrides: deviceOverrides(device),
    };
  });

  app.patch('/v1/devices/me', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', properties: deviceProfileProps } },
  }, async (req) => {
    await updateProfile(db, req.device.id, req.body || {});
    const publicId = await ensurePublicId(db, req.device.user_id);
    return { ok: true, publicId, overrides: deviceOverrides(req.device) };
  });

  app.post('/v1/devices/me/stats', {
    preHandler: auth.requireDevice,
    schema: {
      body: {
        type: 'object',
        properties: {
          messagesSent: { type: 'integer', minimum: 0, maximum: 1e12 },
          secondsInApp: { type: 'integer', minimum: 0, maximum: 1e12 },
        },
      },
    },
  }, async (req) => {
    // Абсолютные счётчики: сервер хранит максимум из присланного.
    const { messagesSent = 0, secondsInApp = 0 } = req.body || {};
    await db.query(
      `UPDATE devices SET
         messages_sent = CASE WHEN $2::bigint > messages_sent THEN $2::bigint ELSE messages_sent END,
         seconds_in_app = CASE WHEN $3::bigint > seconds_in_app THEN $3::bigint ELSE seconds_in_app END
       WHERE id = $1`,
      [req.device.id, messagesSent, secondsInApp]);
    return { ok: true };
  });

  // Ошибка или падение приложения. Длинные тексты обрезаются, частота ограничена на устройство.
  app.post('/v1/devices/me/report', {
    preHandler: auth.requireDevice,
    bodyLimit: 256 * 1024,
    schema: {
      body: {
        type: 'object',
        required: ['kind', 'message'],
        properties: {
          kind: { type: 'string', enum: ['error', 'crash'] },
          message: { type: 'string', minLength: 1, maxLength: 20_000 },
          stack: { type: ['string', 'null'], maxLength: 200_000 },
          appVersion: { type: ['string', 'null'], maxLength: 50 },
          at: { type: ['string', 'null'], format: 'date-time' },
        },
      },
    },
  }, async (req) => {
    if (!limits.report.take(req.device.id)) throw rateLimited('Слишком много отчётов, попробуйте позже');
    const b = req.body;
    // Время события — с телефона (падение могло случиться давно), но не из будущего.
    const at = b.at && new Date(b.at).getTime() <= Date.now() ? new Date(b.at) : new Date();
    const id = randomUUID();
    await db.query(
      `INSERT INTO client_reports (id, device_id, kind, message, stack, app_version, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [id, req.device.id, b.kind, b.message.slice(0, 2000), b.stack ? b.stack.slice(0, 16_000) : null,
        b.appVersion || req.device.app_version || null, at]);
    return { ok: true, id };
  });
}
