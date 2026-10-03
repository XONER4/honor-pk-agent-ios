// Админские эндпоинты: аккаунт (setup/login), обзор, устройства, блокировка, уведомления.
// Админские маршруты чатов регистрируются из routes/chats.js с префиксом /v1/admin/chats;
// метрики, ИИ, отчёты, заметки, журнал и ограничения — в routes/admin-insights.js.
import { randomUUID } from 'node:crypto';
import { blockExpired, safeEqual } from '../auth.js';
import { ApiError, badRequest, conflict, notFound, rateLimited, unauthorized } from '../errors.js';
import { iso } from '../db.js';
import { logAdminAction, recordAdminLogin, recordProfileView, toAdminLogin, toProfileView } from '../device-events.js';
import { hashPassword, LOGIN_PATTERN, normalizeLogin, verifyPassword } from '../passwords.js';
import { toNotification } from './notifications.js';
import { deviceOverrides } from './devices.js';
import { uuid } from './schemas.js';
import { ensure, normalizePermissions, permissionMap, PERMISSION_KEYS } from '../permissions.js';

const PRESENCE_ORDER = { foreground: 0, background: 1, offline: 2 };
const LOGIN_RE = new RegExp(LOGIN_PATTERN);
/** Роль админа: только 'admin' | 'developer' (иначе 'admin'). */
const normalizeRole = (r) => (r === 'developer' ? 'developer' : 'admin');

export default async function adminRoutes(app) {
  const { db, auth, hub, push, config, limits, usage, verifyGoogleIdToken } = app.ctx;

  // ---------- аккаунт администратора (логин + пароль) ----------

  const hasAccount = async () => Boolean(await db.one('SELECT id FROM admins WHERE password_hash IS NOT NULL LIMIT 1'));

  const loginResponse = (admin, token) => ({
    token, email: admin.email, name: admin.name || admin.login || 'Администратор', login: admin.login || null,
    role: normalizeRole(admin.role), adminId: admin.id,
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
        // Владелец (первый аккаунт по логину/паролю) — разработчик.
        await db.query(`UPDATE admins SET login = $2, password_hash = $3, last_login_at = $4, role = 'developer' WHERE id = $1`,
          [existing.id, login, hash, now]);
      } else {
        await db.query(
          `INSERT INTO admins (id, email, name, login, password_hash, role, created_at, last_login_at)
           VALUES ($1, $2, $3, $4, $5, 'developer', $6, $6)`,
          [randomUUID(), login, login, login, hash, now]);
      }
      return db.one('SELECT * FROM admins WHERE login = $1', [login]);
    });
    setupQueue = run.catch(() => {});
    const admin = await run;
    await logAdminAction(db, { adminId: admin.id, action: 'admin_setup', detail: { login } }, req.log);
    const token = await auth.issueAdminToken(admin.id, config.adminTokenTtlMs);
    await recordAdminLogin(db, { adminId: admin.id, loginTried: login, success: true, method: 'setup', ip: req.ip, userAgent: req.headers['user-agent'] }, req.log);
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
    const ua = req.headers['user-agent'];
    const fail = (msg = 'Неверные данные для входа', tried = null, method = null) => {
      limits.login.record(req.ip);
      req.log.warn({ ip: req.ip }, 'admin login failed');
      recordAdminLogin(db, { adminId: null, loginTried: tried, success: false, method, ip: req.ip, userAgent: ua }, req.log);
      return unauthorized(msg);
    };
    const now = new Date();

    if (login !== undefined || password !== undefined) {
      if (!login || !password) throw badRequest('Введите логин и пароль');
      const row = await db.one('SELECT * FROM admins WHERE login = $1', [normalizeLogin(login)]);
      // Проверяем и для несуществующего логина (с хешем-пустышкой): время ответа одинаковое.
      const ok = await verifyPassword(password, row?.password_hash || null);
      if (!ok || !row) throw fail('Неверный логин или пароль', normalizeLogin(login), 'password');
      await db.query('UPDATE admins SET last_login_at = $2 WHERE id = $1', [row.id, now]);
      const token = await auth.issueAdminToken(row.id, config.adminTokenTtlMs);
      await recordAdminLogin(db, { adminId: row.id, loginTried: row.login, success: true, method: 'password', ip: req.ip, userAgent: ua }, req.log);
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

    const method = googleIdToken ? 'google' : 'key';
    if (!identity) throw fail('Неверные данные для входа', null, method);

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
    await recordAdminLogin(db, { adminId: admin.id, loginTried: identity.email, success: true, method, ip: req.ip, userAgent: ua }, req.log);
    return { ...loginResponse(admin, token), name };
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
    // Реальные пользователи = различные физические устройства, активные за последние inactiveDays.
    // Различаем по hardware_id (Android ID), а для старых строк без него — по «отпечатку» телефона
    // (платформа+модель+имя). Так одна и та же трубка, переустановленная много раз, считается как одна.
    const activeCutoff = new Date(Date.now() - config.inactiveDays * 24 * 3600 * 1000);
    const users = await db.one(
      `SELECT count(*)::int AS c FROM (
         SELECT DISTINCT COALESCE(NULLIF(hardware_id, ''),
                                  platform || '|' || COALESCE(device_model, '') || '|' || COALESCE(device_name, '')) AS k
         FROM devices
         WHERE COALESCE(last_seen_at, updated_at, installed_at) >= $1
       ) t`, [activeCutoff]);
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

  /** Устройства, у которых последнее событие — удаление (FCM-токен стал невалидным и приложение не вернулось). */
  async function deletedDevices() {
    const rows = await db.many(
      `SELECT device_id FROM (
         SELECT DISTINCT ON (device_id) device_id, kind
           FROM device_events ORDER BY device_id, at DESC
       ) t WHERE kind = 'uninstall'`);
    return new Set(rows.map((r) => r.device_id));
  }

  /** Доп. сведения для строк списка: непрочитанные, токены, число отчётов, удалённые. */
  async function listExtras() {
    const [unread, tokens, reports, deleted] = await Promise.all([
      unreadByDevice(), usage.totalsByDevice(), reportsByDevice(), deletedDevices()]);
    return { unread, tokens, reports, deleted };
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
      country: d.country || null,
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
      deleted: extras.deleted?.has(d.id) || false,
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
          status: { type: 'string', enum: ['all', 'online', 'blocked', 'deleted'], default: 'all' },
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
    if (req.query.status === 'online') list = list.filter((s) => s.presence !== 'offline' && !s.deleted);
    if (req.query.status === 'blocked') list = list.filter((s) => s.blocked);
    if (req.query.status === 'deleted') list = list.filter((s) => s.deleted);
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
      timezone: d.timezone || null,
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
    async (req) => {
      const detail = await deviceDetail(req.params.deviceId);
      recordProfileView(db, { adminId: req.admin.id, deviceId: req.params.deviceId }, req.log);
      return detail;
    });

  // Кто из админов/разрабов смотрел эту карточку и когда (история просмотров профиля).
  app.get('/v1/admin/devices/:deviceId/profile-views', {
    preHandler: auth.requireAdmin,
    schema: { params: deviceParams, querystring: { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 200, default: 50 } } } },
  }, async (req) => {
    const rows = await db.many(
      `SELECT v.*, a.name AS admin_name, a.login AS admin_login, a.email AS admin_email, a.role AS admin_role
         FROM profile_views v LEFT JOIN admins a ON a.id = v.admin_id
        WHERE v.device_id = $1 ORDER BY v.at DESC LIMIT $2`, [req.params.deviceId, req.query.limit]);
    return rows.map(toProfileView);
  });

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
    await ensure(db, req.admin, 'block'); // право блокировать пользователей (п.3)
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

  // Изменение профиля пользователя администратором (имя). Страна определяется автоматически и не меняется.
  app.patch('/v1/admin/devices/:deviceId/profile', {
    preHandler: auth.requireAdmin,
    schema: {
      params: deviceParams,
      body: {
        type: 'object',
        additionalProperties: false,
        properties: { displayName: { type: ['string', 'null'], maxLength: 200 } },
      },
    },
  }, async (req) => {
    const d = await db.one('SELECT id FROM devices WHERE id = $1', [req.params.deviceId]);
    if (!d) throw notFound('Устройство не найдено');
    if (req.body.displayName !== undefined) {
      const name = req.body.displayName ? String(req.body.displayName).trim() : null;
      await db.query('UPDATE devices SET display_name = $2, updated_at = $3 WHERE id = $1', [d.id, name, new Date()]);
      hub.sendToDevice(d.id, { t: 'profile', displayName: name });
    }
    await logAdminAction(db, { adminId: req.admin.id, deviceId: d.id, action: 'edit_profile', detail: req.body }, req.log);
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
    await ensure(db, req.admin, 'broadcast'); // право на рассылки/уведомления (п.3)
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

  // ---------- аккаунт администратора: профиль, смена пароля ----------

  const adminView = (a) => ({
    adminId: a.id, email: a.email, name: a.name || a.login || 'Администратор', login: a.login || null,
    role: normalizeRole(a.role), hasPassword: !!a.password_hash,
    lastLoginAt: iso(a.last_login_at), createdAt: iso(a.created_at),
    permissions: permissionMap(a), // права (п.3): у разработчика все true
  });

  app.get('/v1/admin/account', { preHandler: auth.requireAdmin }, async (req) =>
    adminView(await db.one('SELECT * FROM admins WHERE id = $1', [req.admin.id])));

  // Смена собственного пароля прямо в приложении. Если пароль уже задан — нужен текущий.
  app.post('/v1/admin/account/password', {
    preHandler: auth.requireAdmin,
    schema: {
      body: {
        type: 'object',
        required: ['newPassword'],
        properties: {
          currentPassword: { type: 'string', maxLength: 200 },
          newPassword: { type: 'string', minLength: 8, maxLength: 200 },
        },
      },
    },
  }, async (req) => {
    const me = await db.one('SELECT * FROM admins WHERE id = $1', [req.admin.id]);
    if (me.password_hash) {
      const ok = await verifyPassword(req.body.currentPassword || '', me.password_hash);
      if (!ok) throw unauthorized('Неверный текущий пароль');
    }
    const hash = await hashPassword(req.body.newPassword);
    // Если логина ещё не было (вход по ключу/Google) — заводим логин из e-mail, чтобы вход по паролю заработал.
    const login = me.login || normalizeLogin(me.email);
    await db.query('UPDATE admins SET password_hash = $2, login = COALESCE(login, $3) WHERE id = $1', [me.id, hash, login]);
    await logAdminAction(db, { adminId: me.id, action: 'change_password' }, req.log);
    return { ok: true };
  });

  // ---------- входы в админку (кто пытался/вошёл) ----------

  app.get('/v1/admin/logins', {
    preHandler: auth.requireAdmin,
    schema: {
      querystring: {
        type: 'object',
        properties: {
          limit: { type: 'integer', minimum: 1, maximum: 200, default: 50 },
          before: { type: 'string', format: 'date-time' },
          success: { type: 'boolean' },
        },
      },
    },
  }, async (req) => {
    const where = [];
    const params = [];
    if (req.query.before) { params.push(new Date(req.query.before)); where.push(`l.at < $${params.length}`); }
    if (req.query.success !== undefined) { params.push(req.query.success); where.push(`l.success = $${params.length}`); }
    params.push(req.query.limit);
    const rows = await db.many(
      `SELECT l.*, a.name AS admin_name, a.login AS admin_login, a.email AS admin_email
         FROM admin_logins l LEFT JOIN admins a ON a.id = l.admin_id
        ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
        ORDER BY l.at DESC LIMIT $${params.length}`, params);
    return rows.map(toAdminLogin);
  });

  // ---------- администраторы и роли ----------

  app.get('/v1/admin/admins', { preHandler: auth.requireAdmin }, async () => {
    const rows = await db.many('SELECT * FROM admins ORDER BY created_at ASC');
    return rows.map((a) => ({ ...adminView(a), presence: hub.adminState ? hub.adminState(a.id) : undefined }));
  });

  // Смена роли другого администратора — только разработчик. Нельзя снять последнего разработчика.
  app.patch('/v1/admin/admins/:adminId/role', {
    preHandler: auth.requireAdmin,
    schema: {
      params: { type: 'object', required: ['adminId'], properties: { adminId: uuid } },
      body: { type: 'object', required: ['role'], properties: { role: { type: 'string', enum: ['admin', 'developer'] } } },
    },
  }, async (req) => {
    if (normalizeRole(req.admin.role) !== 'developer') throw unauthorized('Менять роли может только разработчик');
    const target = await db.one('SELECT * FROM admins WHERE id = $1', [req.params.adminId]);
    if (!target) throw notFound('Администратор не найден');
    const role = normalizeRole(req.body.role);
    if (normalizeRole(target.role) === 'developer' && role === 'admin') {
      const devs = await db.one(`SELECT count(*)::int AS c FROM admins WHERE role = 'developer'`);
      if (Number(devs.c) <= 1) throw badRequest('Нельзя снять роль у последнего разработчика');
    }
    await db.query('UPDATE admins SET role = $2 WHERE id = $1', [target.id, role]);
    await logAdminAction(db, { adminId: req.admin.id, action: 'set_role', detail: { target: target.id, role } }, req.log);
    return adminView(await db.one('SELECT * FROM admins WHERE id = $1', [target.id]));
  });

  // Права администратора (план п.3) — выдаёт/снимает только разработчик. Значение — карта флагов.
  app.patch('/v1/admin/admins/:adminId/permissions', {
    preHandler: auth.requireAdmin,
    schema: {
      params: { type: 'object', required: ['adminId'], properties: { adminId: uuid } },
      body: {
        type: 'object',
        required: ['permissions'],
        properties: {
          permissions: {
            type: 'object',
            additionalProperties: { type: 'boolean' },
            properties: Object.fromEntries(PERMISSION_KEYS.map((k) => [k, { type: 'boolean' }])),
          },
        },
      },
    },
  }, async (req) => {
    if (normalizeRole(req.admin.role) !== 'developer') throw unauthorized('Менять права может только разработчик');
    const target = await db.one('SELECT * FROM admins WHERE id = $1', [req.params.adminId]);
    if (!target) throw notFound('Администратор не найден');
    const next = normalizePermissions(req.body.permissions);
    await db.query('UPDATE admins SET permissions = $2 WHERE id = $1', [target.id, JSON.stringify(next)]);
    await logAdminAction(db, { adminId: req.admin.id, action: 'set_permissions', detail: { target: target.id, permissions: next } }, req.log);
    return adminView(await db.one('SELECT * FROM admins WHERE id = $1', [target.id]));
  });

  // ---------- общая лента действий (админы + пользователи) ----------

  app.get('/v1/admin/activity', {
    preHandler: auth.requireAdmin,
    schema: { querystring: { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 200, default: 80 } } } },
  }, async (req) => {
    const lim = req.query.limit;
    const [actions, events, logins] = await Promise.all([
      db.many(`SELECT x.*, a.name AS admin_name, a.login AS admin_login, a.email AS admin_email,
                      d.display_name AS device_name, u.public_id
                 FROM admin_actions x
                 LEFT JOIN admins a ON a.id = x.admin_id
                 LEFT JOIN devices d ON d.id = x.device_id
                 LEFT JOIN users u ON u.id = d.user_id
                ORDER BY x.at DESC LIMIT $1`, [lim]),
      db.many(`SELECT e.*, d.display_name AS device_name, u.public_id
                 FROM device_events e
                 LEFT JOIN devices d ON d.id = e.device_id
                 LEFT JOIN users u ON u.id = d.user_id
                ORDER BY e.at DESC LIMIT $1`, [lim]),
      db.many(`SELECT l.*, a.name AS admin_name, a.login AS admin_login, a.email AS admin_email
                 FROM admin_logins l LEFT JOIN admins a ON a.id = l.admin_id
                ORDER BY l.at DESC LIMIT $1`, [lim]),
    ]);
    const feed = [
      ...actions.map((r) => ({
        kind: 'admin', at: iso(r.at), action: r.action,
        who: r.admin_name || r.admin_login || r.admin_email || 'Сервер',
        deviceId: r.device_id || null, publicId: r.public_id || null,
        target: r.device_name || (r.public_id ? `#${r.public_id}` : null),
        detail: r.detail ? (() => { try { return JSON.parse(r.detail); } catch { return r.detail; } })() : null,
      })),
      ...events.map((r) => ({
        kind: 'device', at: iso(r.at), action: r.kind,
        who: r.device_name || (r.public_id ? `#${r.public_id}` : 'Пользователь'),
        deviceId: r.device_id, publicId: r.public_id || null, target: null,
        detail: { fromVersion: r.from_version || null, toVersion: r.to_version || null },
      })),
      ...logins.map((r) => ({
        kind: 'login', at: iso(r.at), action: r.success ? 'login' : 'login_failed',
        who: r.admin_name || r.admin_login || r.admin_email || r.login_tried || 'неизвестно',
        deviceId: null, publicId: null, target: r.ip || null,
        detail: { method: r.method || null, success: !!r.success },
      })),
    ];
    feed.sort((a, b) => new Date(b.at) - new Date(a.at));
    return feed.slice(0, lim);
  });
}
