// Эндпоинты устройства: регистрация, профиль, статистика.
import { randomUUID } from 'node:crypto';
import { blocked, rateLimited } from '../errors.js';
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

/** Обновляет присланные поля профиля устройства. */
async function updateProfile(db, deviceId, body, extraSets = []) {
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
      } else {
        await db.query('DELETE FROM users WHERE id = $1', [userId]); // проиграли гонку параллельной регистрации
      }
      device = await db.one('SELECT * FROM devices WHERE install_id = $1', [b.installId]);
    }
    if (device.blocked) throw blocked(device.block_reason);

    await updateProfile(db, device.id, b, ['register_count = register_count + 1']);
    const chat = (await chats.getDeviceChat(device.id)) || (await chats.createChatForDevice(device.id));
    const token = await auth.issueDeviceToken(device.id);
    return { deviceId: device.id, token, userId: device.user_id, adminChatId: chat.id };
  });

  app.patch('/v1/devices/me', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', properties: deviceProfileProps } },
  }, async (req) => {
    await updateProfile(db, req.device.id, req.body || {});
    return { ok: true };
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
}
