// Админские эндпоинты: аккаунт (setup/login), обзор, устройства, блокировка, уведомления.
// Админские маршруты чатов регистрируются из routes/chats.js с префиксом /v1/admin/chats;
// метрики, ИИ, отчёты, заметки, журнал и ограничения — в routes/admin-insights.js.
import { randomUUID } from 'node:crypto';
import { blockExpired, safeEqual } from '../auth.js';
import { ApiError, badRequest, conflict, notFound, rateLimited, unauthorized } from '../errors.js';
import { iso } from '../db.js';
import { logAdminAction } from '../device-events.js';
import { hashPassword, LOGIN_PATTERN, normalizeLogin, verifyPassword } from '../passwords.js';
import { toNotification } from './notifications.js';
import { deviceOverrides } from './devices.js';
import { uuid } from './schemas.js';

const PRESENCE_ORDER = { foreground: 0, background: 1, offline: 2 };
const LOGIN_RE = new RegExp(LOGIN_PATTERN);

export default async function adminRoutes(app) {
  const { db, auth, hub, push, config, limits, usage, verifyGoogleIdToken } = app.ctx;

  // ---------- аккаунт администратора (логин + пароль) ----------

  const hasAccount = async () => Boolean(await db.one('SELECT id FROM admins WHERE password_hash IS NOT NULL LIMIT 1'));

  const loginResponse = (admin, token) => ({
    token, email: admin.email, name: admin.name || admin.login || 'Администратор', login: admin.login || null,
  });

  app.get('/v1/admin/setup-status', async () => ({ hasAccount: await hasAccount() }));

  // Создание первого (и единственного через этот путь) аккаунта. Параллельные вызовы выстраиваются в очередь.
  let setupQueue = Promise.resolve();
  app.post('/v1/admin/setup', {
    schema: {
      body: {
        type: 'object',
        required: ['login', 'password'],
        properties: { login: { type: 'string', maxLength: 200 }, password: { type: 'string', maxLength: 200 } },
      },
    },
  }, async (req) => {
    if (limits.login.blocked(req.ip)) throw rateLimited('Слишком много попыток входа, попробуйте позже');
    if (config.adminKey) {
      const key = req.headers['x-admin-key'];
      if (typeof key !== 'string' || !safeEqual(key, config.adminKey)) {
        limits.login.record(req.ip);
        req.log.warn({ ip: req.ip }, 'admin setup: bad admin key');
        throw unauthorized('Неверный ключ администратора');
      }
    }
    const login = normalizeLogin(req.body.login);
    const { password } = req.body;
    if (!LOGIN_RE.test(login)) throw badRequest('Логин: от 3 до 64 символов — латинские буквы, цифры и . _ @ -');
    if (password.length < 8) throw badRequest('Пароль должен быть не короче 8 символов');

    const run = setupQueue.then(async () => {
      if (await hasAccount()) throw conflict('Аккаунт администратора уже создан — войдите по логину и паролю');
      const hash = await hashPassword(password);
      const now = new Date();
      // Строка, созданная входом по ключу/Google с тем же e-mail, становится этим аккаунтом (история сохраняется).
      const existing = await db.one('SELECT * FROM admins WHERE email = $1', [login]);
      if (existing) {
        await db.query('UPDATE admins SET login = $2, password_hash = $3, last_login_at = $4 WHERE id = $1',
          [existing.id, login, hash, now]);
      } else {
        await db.query(
          `INSERT INTO admins (id, email, name, login, password_hash, created_at, last_login_at)
           VALUES ($1, $2, $3, $4, $5, $6, $6)`,
          [randomUUID(), login, login, login, hash, now]);
      }
      return db.one('SELECT * FROM admins WHERE login = $1', [login]);
    });
    setupQueue = run.catch(() => {});
    const admin = await run;
    await logAdminAction(db, { adminId: admin.id, action: 'admin_setup', detail: { login } }, req.log);
    const token = await auth.issueAdminToken(admin.id, config.adminTokenTtlMs);
    return loginResponse(admin, token);
  });

  // ---------- вход ----------

  app.post('/v1/admin/login', {
    schema: {
      body: {
        type: 'object',
        properties: {
          login: { type: 'string', maxLength: 200 },
          password: { type: 'string', maxLength: 200 },
          googleIdToken: { type: 'string', minLength: 10, maxLength: 8192 },
          adminKey: { type: 'string', minLength: 1, maxLength: 512 },
        },
      },
    },
  }, async (req) => {
    // Лимит считаем по неудачным попыткам с IP.
    if (limits.login.blocked(req.ip)) throw rateLimited('Слишком много попыток входа, попробуйте позже');
    const { login, password, googleIdToken, adminKey } = req.body || {};
    const fail = (msg = 'Неверные данные для входа') => {
      limits.login.record(req.ip);
      req.log.warn({ ip: req.ip }, 'admin login failed');
      return unauthorized(msg);
    };
    const now = new Date();

    if (login !== undefined || password !== undefined) {
      if (!login || !password) throw badRequest('Введите логин и пароль');
      const row = await db.one('SELECT * FROM admins WHERE login = $1', [normalizeLogin(login)]);
      // Проверяем и для несуществующего логина (с хешем-пустышкой): время ответа одинаковое.
      const ok = await verifyPassword(password, row?.password_hash || null);
      if (!ok || !row) throw fail('Неверный логин или пароль');
      await db.query('UPDATE admins SET last_login_at = $2 WHERE id = $1', [row.id, now]);
      const token = await auth.issueAdminToken(row.id, config.adminTokenTtlMs);
      return loginResponse(row, token);
    }

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
      throw badRequest('Нужны логин и пароль, googleIdToken или adminKey');
    }

    if (!identity) throw fail();

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
    return { token, email: admin.email, name, login: admin.login || null };
  });

  // ---------- обзор ----------

  /** Устройства, не выходившие на связь дольше inactiveDays (и не подключённые сейчас). */
  async function inactiveCount() {
    const cutoff = Date.now() - config.inactiveDays * 24 * 3600 * 1000;
    const rows = await db.many('SELECT id, last_seen_at, updated_at, installed_at FROM devices');
    return rows.filter((d) => {
      if (hub.deviceState(d.id) !== 'offline') return false;
      const last = d.last_seen_at || d.updated_at || d.installed_at;
      return last && new Date(last).getTime() < cutoff;
    }).length;
  }

  app.get('/v1/admin/overview', { preHandler: auth.requireAdmin }, async () => {
    const startOfDay = new Date();
    startOfDay.setUTCHours(0, 0, 0, 0);
    const users = await db.one('SELECT count(*)::int AS c FROM users');
    const installs = await db.one(`SELECT value FROM counters WHERE name = 'installs'`);
    const blockedCount = await db.one(
      'SELECT count(*)::int AS c FROM devices WHERE blocked = TRUE AND (blocked_until IS NULL OR blocked_until > $1)', [new Date()]);
    const today = await db.one('SELECT count(*)::int AS c FROM messages WHERE created_at >= $1', [startOfDay]);
    const updates = await db.one(`SELECT count(*)::int AS c FROM device_events WHERE kind = 'update'`);
    const uninstalls = await db.one(`SELECT count(*)::int AS c FROM device_events WHERE kind = 'uninstall'`);
    const { online, inBackground } = hub.counts();
    return {
      users: Number(users.c),
      installs: Number(installs?.value || 0),
      online,
      inBackground,
      blocked: Number(blockedCount.c),
      messagesToday: Number(today.c),
      updates: Number(updates.c),
      uninstalls: Number(uninstalls.c),
      inactive: await inactiveCount(),
      inactiveDays: config.inactiveDays,
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

  async function reportsByDevice() {
    const rows = await db.many('SELECT device_id, count(*)::int AS c FROM client_reports GROUP BY device_id');
    return new Map(rows.map((r) => [r.device_id, Number(r.c)]));
  }

  /** Доп. сведения для строк списка: непрочитанные, токены, число отчётов. */
  async function listExtras() {
    const [unread, tokens, reports] = await Promise.all([unreadByDevice(), usage.totalsByDevice(), reportsByDevice()]);
    return { unread, tokens, reports };
  }

  function summary(d, extras) {
    const p = hub.devicePresence(d.id, d.last_seen_at);
    // Истёкшая блокировка уже не действует (в БД её снимет ближайшая проверка).
    const isBlocked = !!d.blocked && !blockExpired(d);
    return {
      deviceId: d.id,
      userId: d.user_id,
      publicId: d.public_id || null,
      displayName: d.display_name || null,
      deviceModel: d.device_model || null,
      deviceName: d.device_name || null,
      platform: d.platform,
      appVersion: d.app_version || null,
      installedAt: iso(d.installed_at),
      lastSeen: p.lastSeen,
      presence: p.state,
      typingIn: p.typingIn,
      blocked: isBlocked,
      blockedUntil: isBlocked ? iso(d.blocked_until) : null,
      messagesSent: Number(d.messages_sent) || 0,
      secondsInApp: Number(d.seconds_in_app) || 0,
      unreadForAdmin: extras.unread.get(d.id) || 0,
      adminChatId: d.chat_id || null,
      aiTokens: extras.tokens.get(d.id) || 0,
      reports: extras.reports.get(d.id) || 0,
    };
  }

  const DEVICE_SQL = `SELECT d.*, c.id AS chat_id, u.public_id FROM devices d
    LEFT JOIN chats c ON c.device_id = d.id LEFT JOIN users u ON u.id = d.user_id`;

  app.get('/v1/admin/devices', {
    preHandler: auth.requireAdmin,
    schema: {
      querystring: {
        type: 'object',
        properties: {
          query: { type: 'string', maxLength: 200 },
          status: { type: 'string', enum: ['all', 'online', 'blocked'], default: 'all' },
          sort: { type: 'string', enum: ['activity', 'tokens'], default: 'activity' },
        },
      },
    },
  }, async (req) => {
    const rows = await db.many(DEVICE_SQL);
    const extras = await listExtras();
    const q = String(req.query.query || '').trim().toLowerCase();
    let list = rows.map((d) => summary(d, extras));
    // Поиск по подстроке делаем в JS: устройств немного, а так не нужно экранировать LIKE-шаблоны.
    if (q) {
      const idQuery = q.replace(/^#/, '');
      list = list.filter((s) => (s.publicId && /^\d+$/.test(idQuery) && s.publicId.includes(idQuery))
        || [s.displayName, s.deviceModel, s.deviceName, s.deviceId, s.userId, s.appVersion]
          .some((v) => v && String(v).toLowerCase().includes(q)));
    }
    if (req.query.status === 'online') list = list.filter((s) => s.presence !== 'offline');
    if (req.query.status === 'blocked') list = list.filter((s) => s.blocked);
    if (req.query.sort === 'tokens') {
      list.sort((a, b) => (b.aiTokens - a.aiTokens) || (PRESENCE_ORDER[a.presence] - PRESENCE_ORDER[b.presence]));
    } else {
      list.sort((a, b) => (PRESENCE_ORDER[a.presence] - PRESENCE_ORDER[b.presence])
        || (new Date(b.lastSeen || b.installedAt) - new Date(a.lastSeen || a.installedAt)));
    }
    return list;
  });

  async function deviceDetail(deviceId) {
    const d = await db.one(`${DEVICE_SQL} WHERE d.id = $1`, [deviceId]);
    if (!d) throw notFound('Устройство не найдено');
    const extras = await listExtras();
    // installs: сколько раз приложение устанавливалось на этот физический телефон
    // (эвристика: те же platform + deviceModel + deviceName; новый installId = новая установка).
    const same = await db.many('SELECT platform, device_model, device_name FROM devices WHERE platform = $1', [d.platform]);
    const installs = same.filter((x) => x.device_model === d.device_model && x.device_name === d.device_name).length || 1;
    const s = summary(d, extras);
    return {
      ...s,
      birthday: d.birthday || null,
      language: d.language || null,
      osVersion: d.os_version || null,
      licenseAcceptedAt: iso(d.license_accepted_at),
      blockReason: s.blocked ? d.block_reason || null : null,
      installs,
      overrides: deviceOverrides(d),
      usage: await usage.forDevice(d.id),
    };
  }

  const deviceParams = { type: 'object', required: ['deviceId'], properties: { deviceId: uuid } };

  app.get('/v1/admin/devices/:deviceId', { preHandler: auth.requireAdmin, schema: { params: deviceParams } },
    async (req) => deviceDetail(req.params.deviceId));

  app.post('/v1/admin/devices/:deviceId/block', {
    preHandler: auth.requireAdmin,
    schema: {
      params: deviceParams,
      body: {
        type: 'object',
        required: ['blocked'],
        properties: {
          blocked: { type: 'boolean' },
          reason: { type: ['string', 'null'], maxLength: 500 },
          until: { type: ['string', 'null'], format: 'date-time' },
        },
      },
    },
  }, async (req) => {
    const { blocked, reason } = req.body;
    const until = blocked && req.body.until ? new Date(req.body.until) : null;
    if (until && until.getTime() <= Date.now()) throw badRequest('Срок блокировки должен быть в будущем');
    const d = await db.one('SELECT id FROM devices WHERE id = $1', [req.params.deviceId]);
    if (!d) throw notFound('Устройство не найдено');
    await db.query('UPDATE devices SET blocked = $2, block_reason = $3, blocked_until = $4, updated_at = $5 WHERE id = $1',
      [d.id, blocked, blocked ? reason || null : null, until, new Date()]);
    if (blocked) hub.kickDevice(d.id, reason || '', until);
    await logAdminAction(db, {
      adminId: req.admin.id, deviceId: d.id, action: blocked ? 'block' : 'unblock',
      detail: blocked ? { reason: reason || null, until: iso(until) } : null,
    }, req.log);
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
    await logAdminAction(db, {
      adminId: req.admin.id, deviceId, action: deviceId ? 'notify' : 'broadcast', detail: { title, count: targets.length },
    }, req.log);
    return { ok: true, count: targets.length };
  });
}
