// Админские эндпоинты: вход, обзор, устройства, блокировка, уведомления.
// Админские маршруты чатов регистрируются из routes/chats.js с префиксом /v1/admin/chats.
import { randomUUID } from 'node:crypto';
import { safeEqual } from '../auth.js';
import { ApiError, badRequest, notFound, rateLimited, unauthorized } from '../errors.js';
import { iso } from '../db.js';
import { toNotification } from './notifications.js';
import { uuid } from './schemas.js';

const PRESENCE_ORDER = { foreground: 0, background: 1, offline: 2 };

export default async function adminRoutes(app) {
  const { db, auth, hub, push, config, limits, verifyGoogleIdToken } = app.ctx;

  // ---------- вход ----------

  app.post('/v1/admin/login', {
    schema: {
      body: {
        type: 'object',
        properties: {
          googleIdToken: { type: 'string', minLength: 10, maxLength: 8192 },
          adminKey: { type: 'string', minLength: 1, maxLength: 512 },
        },
      },
    },
  }, async (req) => {
    // Лимит считаем по неудачным попыткам с IP.
    if (limits.login.blocked(req.ip)) throw rateLimited('Слишком много попыток входа, попробуйте позже');
    const { googleIdToken, adminKey } = req.body || {};
    let identity = null;

    if (googleIdToken) {
      if (!config.googleClientIds.length) throw new ApiError(503, 'google_not_configured', 'Вход через Google не настроен');
      try {
        const p = await verifyGoogleIdToken(googleIdToken, config.googleClientIds);
        const email = String(p?.email || '').toLowerCase();
        if (email && p.email_verified !== false && config.adminEmails.includes(email)) identity = { email, name: p.name || email };
      } catch {
        identity = null; // неверный/просроченный токен
      }
    } else if (adminKey) {
      if (config.adminKey && safeEqual(adminKey, config.adminKey)) {
        identity = { email: config.adminEmails[0] || 'admin', name: 'Администратор' };
      }
    } else {
      throw badRequest('Нужен googleIdToken или adminKey');
    }

    if (!identity) {
      limits.login.record(req.ip);
      req.log.warn({ ip: req.ip }, 'admin login failed');
      throw unauthorized('Неверные данные для входа');
    }

    const now = new Date();
    let admin = await db.one('SELECT * FROM admins WHERE email = $1', [identity.email]);
    if (!admin) {
      await db.query('INSERT INTO admins (id, email, name, created_at, last_login_at) VALUES ($1, $2, $3, $4, $4) ON CONFLICT (email) DO NOTHING',
        [randomUUID(), identity.email, identity.name, now]);
      admin = await db.one('SELECT * FROM admins WHERE email = $1', [identity.email]);
    }
    // Имя из Google обновляем; при входе по ключу сохраняем прежнее.
    const name = googleIdToken ? identity.name : admin.name || identity.name;
    await db.query('UPDATE admins SET name = $2, last_login_at = $3 WHERE id = $1', [admin.id, name, now]);
    const token = await auth.issueAdminToken(admin.id, config.adminTokenTtlMs);
    return { token, email: admin.email, name };
  });

  // ---------- обзор ----------

  app.get('/v1/admin/overview', { preHandler: auth.requireAdmin }, async () => {
    const startOfDay = new Date();
    startOfDay.setUTCHours(0, 0, 0, 0);
    const users = await db.one('SELECT count(*)::int AS c FROM users');
    const installs = await db.one(`SELECT value FROM counters WHERE name = 'installs'`);
    const blockedCount = await db.one('SELECT count(*)::int AS c FROM devices WHERE blocked = TRUE');
    const today = await db.one('SELECT count(*)::int AS c FROM messages WHERE created_at >= $1', [startOfDay]);
    const { online, inBackground } = hub.counts();
    return {
      users: Number(users.c),
      installs: Number(installs?.value || 0),
      online,
      inBackground,
      blocked: Number(blockedCount.c),
      messagesToday: Number(today.c),
    };
  });

  // ---------- устройства ----------

  async function unreadByDevice() {
    const rows = await db.many(
      `SELECT c.device_id, count(m.id)::int AS c
         FROM chats c
         JOIN messages m ON m.chat_id = c.id
         LEFT JOIN message_hidden h ON h.message_id = m.id AND h.side = 'admin'
        WHERE m.sender = 'user' AND m.deleted = FALSE AND h.message_id IS NULL
          AND m.seq > c.admin_read_seq AND m.seq > c.admin_cleared_seq
        GROUP BY c.device_id`);
    return new Map(rows.map((r) => [r.device_id, Number(r.c)]));
  }

  function summary(d, unread) {
    const p = hub.devicePresence(d.id, d.last_seen_at);
    return {
      deviceId: d.id,
      userId: d.user_id,
      displayName: d.display_name || null,
      deviceModel: d.device_model || null,
      deviceName: d.device_name || null,
      platform: d.platform,
      appVersion: d.app_version || null,
      installedAt: iso(d.installed_at),
      lastSeen: p.lastSeen,
      presence: p.state,
      typingIn: p.typingIn,
      blocked: !!d.blocked,
      messagesSent: Number(d.messages_sent) || 0,
      secondsInApp: Number(d.seconds_in_app) || 0,
      unreadForAdmin: unread || 0,
      adminChatId: d.chat_id || null,
    };
  }

  const DEVICE_SQL = 'SELECT d.*, c.id AS chat_id FROM devices d LEFT JOIN chats c ON c.device_id = d.id';

  app.get('/v1/admin/devices', {
    preHandler: auth.requireAdmin,
    schema: {
      querystring: {
        type: 'object',
        properties: {
          query: { type: 'string', maxLength: 200 },
          status: { type: 'string', enum: ['all', 'online', 'blocked'], default: 'all' },
        },
      },
    },
  }, async (req) => {
    const rows = await db.many(DEVICE_SQL);
    const unread = await unreadByDevice();
    const q = String(req.query.query || '').trim().toLowerCase();
    let list = rows.map((d) => summary(d, unread.get(d.id)));
    // Поиск по подстроке делаем в JS: устройств немного, а так не нужно экранировать LIKE-шаблоны.
    if (q) {
      list = list.filter((s) => [s.displayName, s.deviceModel, s.deviceName, s.deviceId, s.userId, s.appVersion]
        .some((v) => v && String(v).toLowerCase().includes(q)));
    }
    if (req.query.status === 'online') list = list.filter((s) => s.presence !== 'offline');
    if (req.query.status === 'blocked') list = list.filter((s) => s.blocked);
    list.sort((a, b) => (PRESENCE_ORDER[a.presence] - PRESENCE_ORDER[b.presence])
      || (new Date(b.lastSeen || b.installedAt) - new Date(a.lastSeen || a.installedAt)));
    return list;
  });

  async function deviceDetail(deviceId) {
    const d = await db.one(`${DEVICE_SQL} WHERE d.id = $1`, [deviceId]);
    if (!d) throw notFound('Устройство не найдено');
    const unread = await unreadByDevice();
    // installs: сколько раз приложение устанавливалось на этот физический телефон
    // (эвристика: те же platform + deviceModel + deviceName; новый installId = новая установка).
    const same = await db.many('SELECT platform, device_model, device_name FROM devices WHERE platform = $1', [d.platform]);
    const installs = same.filter((x) => x.device_model === d.device_model && x.device_name === d.device_name).length || 1;
    return {
      ...summary(d, unread.get(d.id)),
      birthday: d.birthday || null,
      language: d.language || null,
      osVersion: d.os_version || null,
      licenseAcceptedAt: iso(d.license_accepted_at),
      blockReason: d.block_reason || null,
      installs,
    };
  }

  const deviceParams = { type: 'object', required: ['deviceId'], properties: { deviceId: uuid } };

  app.get('/v1/admin/devices/:deviceId', { preHandler: auth.requireAdmin, schema: { params: deviceParams } },
    async (req) => deviceDetail(req.params.deviceId));

  app.post('/v1/admin/devices/:deviceId/block', {
    preHandler: auth.requireAdmin,
    schema: {
      params: deviceParams,
      body: { type: 'object', required: ['blocked'], properties: { blocked: { type: 'boolean' }, reason: { type: ['string', 'null'], maxLength: 500 } } },
    },
  }, async (req) => {
    const { blocked, reason } = req.body;
    const d = await db.one('SELECT id FROM devices WHERE id = $1', [req.params.deviceId]);
    if (!d) throw notFound('Устройство не найдено');
    await db.query('UPDATE devices SET blocked = $2, block_reason = $3, updated_at = $4 WHERE id = $1',
      [d.id, blocked, blocked ? reason || null : null, new Date()]);
    if (blocked) hub.kickDevice(d.id, reason || '');
    return deviceDetail(d.id);
  });

  // ---------- уведомления ----------

  app.post('/v1/admin/notifications', {
    preHandler: auth.requireAdmin,
    schema: {
      body: {
        type: 'object',
        required: ['title', 'body'],
        properties: {
          deviceId: { type: ['string', 'null'], format: 'uuid' },
          title: { type: 'string', minLength: 1, maxLength: 200 },
          body: { type: 'string', maxLength: 4000 },
        },
      },
    },
  }, async (req) => {
    const { deviceId = null, title, body } = req.body;
    const targets = deviceId
      ? await db.many('SELECT id FROM devices WHERE id = $1', [deviceId])
      : await db.many('SELECT id FROM devices WHERE blocked = FALSE');
    if (deviceId && !targets.length) throw notFound('Устройство не найдено');

    const now = new Date();
    for (const t of targets) {
      const row = {
        id: randomUUID(), device_id: t.id, title, body, kind: 'admin', chat_id: null, created_at: now, read_at: null,
      };
      await db.query(
        'INSERT INTO notifications (id, device_id, title, body, kind, chat_id, created_at) VALUES ($1, $2, $3, $4, $5, $6, $7)',
        [row.id, row.device_id, row.title, row.body, row.kind, row.chat_id, row.created_at]);
      hub.sendToDevice(t.id, { t: 'notification', notification: toNotification(row) });
      push.notifyDevice(t.id, { type: 'notification', chatId: null, title, body });
    }
    return { ok: true, count: targets.length };
  });
}
