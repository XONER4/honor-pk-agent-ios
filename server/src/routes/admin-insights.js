// Админка, «аналитика и управление»: метрики, глобальный ИИ и расписание, отчёты об ошибках,
// история версий, заметки, журнал действий, персональные ограничения устройства.
import { randomUUID } from 'node:crypto';
import { badRequest, notFound } from '../errors.js';
import { iso, json } from '../db.js';
import { isValidTimezone } from '../ai-control.js';
import { logAdminAction, toAdminAction, toDeviceEvent } from '../device-events.js';
import { deviceOverrides } from './devices.js';
import { uuid } from './schemas.js';
import { ensure } from '../permissions.js';

/** Снимок метрик для GET /v1/admin/metrics и WS-кадра {"t":"metrics"}. */
export function createMetricsSnapshot({ db, hub, metrics, usage, aiControl, config }) {
  return async function snapshot() {
    const startOfDay = new Date();
    startOfDay.setUTCHours(0, 0, 0, 0);
    const { online, inBackground } = hub.counts();
    const live = metrics.snapshot();
    const tokens = await usage.totals();
    const reports = await db.one('SELECT count(*)::int AS c FROM client_reports WHERE created_at >= $1', [startOfDay]);
    const reportsToday = Number(reports?.c) || 0;
    const ai = aiControl.view();
    return {
      at: new Date().toISOString(),
      online,
      inBackground,
      rps: live.rps,
      requests1m: live.requests1m,
      errors1m: live.errors1m,
      tokensToday: tokens.today.total,
      tokensTotal: tokens.total.total,
      tokens,
      aiRequestsToday: tokens.today.requests,
      aiErrorsToday: tokens.today.errors,
      reportsToday,
      errorsToday: tokens.today.errors + reportsToday,
      aiLatencyMs: live.aiLatencyMs,
      apiLatencyMs: live.apiLatencyMs,
      model: { enabled: ai.effective && ai.configured, switchedOn: ai.enabled, scheduled: ai.schedule.length > 0, configured: ai.configured, name: config.aiGroupModel },
    };
  };
}

const deviceParams = { type: 'object', required: ['deviceId'], properties: { deviceId: uuid } };
const pageQuery = {
  limit: { type: 'integer', minimum: 1, maximum: 200, default: 50 },
  before: { type: 'string', format: 'date-time' },
};

export default async function adminInsightsRoutes(app) {
  const { db, auth, hub, aiControl, metricsSnapshot, config } = app.ctx;

  // Статус поддержки: в сети ли (любой админ онлайн) и среднее время ответа админов (план админка п.20).
  // Режим статуса поддержки (план п.20): 'auto' — по присутствию админов, 'online'/'offline' — вручную.
  const supportMode = async () => {
    const row = await db.one(`SELECT value FROM server_settings WHERE key = 'support_status'`);
    const v = row?.value;
    return v === 'online' || v === 'offline' ? v : 'auto';
  };

  app.get('/v1/admin/support-stats', { preHandler: auth.requireAdmin }, async () => {
    const presence = hub.adminPresence();
    const mode = await supportMode();
    const online = mode === 'online' ? true : mode === 'offline' ? false : presence.online;
    let avgResponseSeconds = null;
    let samples = 0;
    try {
      const rows = await db.many(`
        WITH ordered AS (
          SELECT chat_id, sender, created_at,
                 LAG(created_at) OVER (PARTITION BY chat_id ORDER BY seq) AS prev_at,
                 LAG(sender)     OVER (PARTITION BY chat_id ORDER BY seq) AS prev_sender
            FROM messages WHERE deleted = FALSE
        )
        SELECT EXTRACT(EPOCH FROM (created_at - prev_at)) AS secs
          FROM ordered
         WHERE sender = 'admin' AND prev_sender = 'user'
           AND created_at > now() - interval '30 days'
           AND created_at - prev_at < interval '1 day'
         ORDER BY created_at DESC LIMIT 200`);
      const secs = rows.map((r) => Number(r.secs)).filter((n) => Number.isFinite(n) && n >= 0);
      samples = secs.length;
      if (secs.length) avgResponseSeconds = Math.round(secs.reduce((a, b) => a + b, 0) / secs.length);
    } catch { /* window-функции недоступны в memory-режиме — вернём только присутствие */ }
    return {
      online,
      mode,
      lastOnlineAt: presence.lastSeen ? iso(presence.lastSeen) : null,
      avgResponseSeconds,
      samples,
    };
  });

  // Установить режим статуса поддержки (план п.20): auto | online | offline.
  app.patch('/v1/admin/support-status', {
    preHandler: auth.requireAdmin,
    schema: { body: { type: 'object', required: ['mode'], properties: { mode: { type: 'string', enum: ['auto', 'online', 'offline'] } } } },
  }, async (req) => {
    const mode = req.body.mode;
    await db.query(
      `INSERT INTO server_settings (key, value, updated_at) VALUES ('support_status', $1, $2)
         ON CONFLICT (key) DO UPDATE SET value = $1, updated_at = $2`,
      [mode, new Date()]);
    await logAdminAction(db, { adminId: req.admin.id, action: 'support_status', detail: { mode } }, req.log);
    return { mode };
  });

  // ---------- Общий чат команды (админы + разработчик), план админка п.9 ----------
  const toStaffMessage = (myId) => (r) => ({
    id: r.id,
    seq: Number(r.seq),
    adminId: r.admin_id,
    adminName: r.admin_name || r.admin_login || 'Администратор',
    adminRole: r.admin_role || 'admin',
    text: r.text || '',
    attachments: json(r.attachments, []),
    createdAt: iso(r.created_at),
    mine: r.admin_id === myId,
  });

  app.get('/v1/admin/staff/messages', {
    preHandler: auth.requireAdmin,
    schema: { querystring: { type: 'object', properties: {
      before: { type: 'integer' }, limit: { type: 'integer', minimum: 1, maximum: 100 } } } },
  }, async (req) => {
    const limit = Number(req.query.limit) || 50;
    const beforeSeq = req.query.before ? Number(req.query.before) : null;
    const where = beforeSeq ? 'WHERE m.deleted = FALSE AND m.seq < $2' : 'WHERE m.deleted = FALSE';
    const params = beforeSeq ? [limit, beforeSeq] : [limit];
    const rows = await db.many(
      `SELECT m.*, a.name AS admin_name, a.login AS admin_login, a.role AS admin_role
         FROM staff_messages m LEFT JOIN admins a ON a.id = m.admin_id
         ${where} ORDER BY m.seq DESC LIMIT $1`, params);
    rows.reverse();
    return rows.map(toStaffMessage(req.admin.id));
  });

  app.post('/v1/admin/staff/messages', {
    preHandler: auth.requireAdmin,
    schema: { body: { type: 'object', properties: {
      text: { type: 'string', maxLength: 10_000, default: '' },
      attachments: { type: 'array', maxItems: 10, default: [] } } } },
  }, async (req) => {
    const text = (req.body.text || '').trim();
    const attachments = req.body.attachments || [];
    if (!text && attachments.length === 0) throw badRequest('Пустое сообщение');
    const id = randomUUID();
    const r = await db.one(
      `INSERT INTO staff_messages (id, admin_id, text, attachments, created_at)
         VALUES ($1, $2, $3, $4, $5) RETURNING *`,
      [id, req.admin.id, text, JSON.stringify(attachments), new Date()]);
    const a = await db.one('SELECT name, login, role FROM admins WHERE id = $1', [req.admin.id]);
    const msg = toStaffMessage(req.admin.id)({ ...r, admin_name: a?.name, admin_login: a?.login, admin_role: a?.role });
    hub.sendToAdmins({ t: 'staff', message: { ...msg, mine: false } });
    return msg;
  });

  app.get('/v1/admin/staff/unread', { preHandler: auth.requireAdmin }, async (req) => {
    const last = await db.one('SELECT max(seq) AS s FROM staff_messages WHERE deleted = FALSE');
    const read = await db.one('SELECT read_seq FROM staff_reads WHERE admin_id = $1', [req.admin.id]);
    const readSeq = Number(read?.read_seq) || 0;
    const unread = await db.one(
      'SELECT count(*)::int AS c FROM staff_messages WHERE deleted = FALSE AND seq > $1 AND admin_id <> $2',
      [readSeq, req.admin.id]);
    return { unread: Number(unread.c), lastSeq: Number(last?.s) || 0 };
  });

  app.post('/v1/admin/staff/read', { preHandler: auth.requireAdmin }, async (req) => {
    const last = await db.one('SELECT max(seq) AS s FROM staff_messages');
    const seq = Number(last?.s) || 0;
    await db.query(
      `INSERT INTO staff_reads (admin_id, read_seq, at) VALUES ($1, $2, $3)
         ON CONFLICT (admin_id) DO UPDATE SET read_seq = GREATEST(staff_reads.read_seq, EXCLUDED.read_seq), at = EXCLUDED.at`,
      [req.admin.id, seq, new Date()]);
    return { ok: true, readSeq: seq };
  });

  // Перевод сообщения пользователя на русский для поддержки (план админка п.1). Текст, уже в основном
  // на кириллице, не переводится. Перевод через DeepSeek; расход токенов учитывается отдельно.
  app.post('/v1/admin/translate', {
    preHandler: auth.requireAdmin,
    schema: { body: { type: 'object', required: ['text'],
      properties: { text: { type: 'string', maxLength: 8000 } } } },
  }, async (req) => {
    const text = (req.body.text || '').trim();
    if (!text) return { text: '', translated: false };
    const cyr = (text.match(/[а-яё]/gi) || []).length;
    const letters = (text.match(/\p{L}/gu) || []).length;
    if (letters === 0 || cyr / letters > 0.5) return { text, translated: false };
    if (!config.deepseekApiKey) return { text, translated: false };
    try {
      const r = await fetch(`${config.deepseekBaseUrl}/chat/completions`, {
        method: 'POST',
        headers: { authorization: `Bearer ${config.deepseekApiKey}`, 'content-type': 'application/json' },
        body: JSON.stringify({
          model: config.aiAllowedModels?.[0] || 'deepseek-chat',
          messages: [
            { role: 'system', content: 'Ты переводчик. Переведи сообщение пользователя на русский язык. Выдай только перевод — без пояснений, без кавычек, без исходного текста.' },
            { role: 'user', content: text },
          ],
          stream: false, temperature: 0.2,
        }),
        signal: AbortSignal.timeout(20000),
      });
      if (!r.ok) return { text, translated: false };
      const data = await r.json();
      const out = data?.choices?.[0]?.message?.content?.trim();
      return out ? { text: out, translated: true } : { text, translated: false };
    } catch (err) {
      req.log.warn({ err: { message: err.message } }, 'translate failed');
      return { text, translated: false };
    }
  });

  async function requireDevice(deviceId) {
    const d = await db.one('SELECT * FROM devices WHERE id = $1', [deviceId]);
    if (!d) throw notFound('Устройство не найдено');
    return d;
  }

  // ---------- метрики ----------

  app.get('/v1/admin/metrics', { preHandler: auth.requireAdmin }, async () => metricsSnapshot());

  // ---------- ИИ: общий выключатель и расписание ----------

  app.get('/v1/admin/ai', { preHandler: auth.requireAdmin }, async () => aiControl.view());

  const hhmm = { type: 'string', pattern: '^([01]\\d|2[0-3]):[0-5]\\d$' };
  app.post('/v1/admin/ai', {
    preHandler: auth.requireAdmin,
    schema: {
      body: {
        type: 'object',
        properties: {
          enabled: { type: 'boolean' },
          schedule: {
            type: 'array',
            maxItems: 20,
            items: {
              type: 'object',
              required: ['from', 'to'],
              additionalProperties: false,
              properties: {
                days: { type: 'array', maxItems: 7, uniqueItems: true, items: { type: 'integer', minimum: 1, maximum: 7 } },
                from: hhmm,
                to: hhmm,
              },
            },
          },
          timezone: { type: 'string', maxLength: 64 },
        },
      },
    },
  }, async (req) => {
    await ensure(db, req.admin, 'manageAi'); // право управлять ИИ (п.3)
    const { enabled, schedule, timezone } = req.body || {};
    if (timezone !== undefined && !isValidTimezone(timezone)) throw badRequest('Неизвестный часовой пояс');
    const patch = {};
    if (enabled !== undefined) patch.enabled = enabled;
    if (schedule !== undefined) patch.schedule = schedule.map((w) => ({ days: [...(w.days || [])].sort(), from: w.from, to: w.to }));
    if (timezone !== undefined) patch.timezone = timezone;
    const view = await aiControl.update(patch);
    await logAdminAction(db, { adminId: req.admin.id, action: 'ai_settings', detail: patch }, req.log);
    hub.sendToAdmins({ t: 'ai', ...view });
    return view;
  });

  // ---------- отчёты об ошибках и падениях ----------

  app.get('/v1/admin/reports', {
    preHandler: auth.requireAdmin,
    schema: {
      querystring: {
        type: 'object',
        properties: { deviceId: uuid, kind: { type: 'string', enum: ['error', 'crash'] }, ...pageQuery },
      },
    },
  }, async (req) => {
    const where = [];
    const params = [];
    const add = (sql, v) => { params.push(v); where.push(sql.replace('?', `$${params.length}`)); };
    if (req.query.deviceId) add('r.device_id = ?', req.query.deviceId);
    if (req.query.kind) add('r.kind = ?', req.query.kind);
    if (req.query.before) add('r.created_at < ?', new Date(req.query.before));
    params.push(req.query.limit);
    const rows = await db.many(
      `SELECT r.*, d.display_name, d.device_model, u.public_id
         FROM client_reports r
         LEFT JOIN devices d ON d.id = r.device_id
         LEFT JOIN users u ON u.id = d.user_id
        ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
        ORDER BY r.created_at DESC LIMIT $${params.length}`, params);
    return rows.map((r) => ({
      id: r.id,
      deviceId: r.device_id,
      publicId: r.public_id || null,
      displayName: r.display_name || null,
      deviceModel: r.device_model || null,
      kind: r.kind,
      message: r.message,
      stack: r.stack || null,
      appVersion: r.app_version || null,
      at: iso(r.created_at),
    }));
  });

  // ---------- история установок/версий ----------

  app.get('/v1/admin/devices/:deviceId/events', { preHandler: auth.requireAdmin, schema: { params: deviceParams } }, async (req) => {
    await requireDevice(req.params.deviceId);
    const rows = await db.many('SELECT * FROM device_events WHERE device_id = $1 ORDER BY at DESC LIMIT 200', [req.params.deviceId]);
    return rows.map(toDeviceEvent);
  });

  // Очистить историю установок/обновлений устройства (#12).
  app.delete('/v1/admin/devices/:deviceId/events', { preHandler: auth.requireAdmin, schema: { params: deviceParams } }, async (req) => {
    await requireDevice(req.params.deviceId);
    const r = await db.query('DELETE FROM device_events WHERE device_id = $1', [req.params.deviceId]);
    await logAdminAction(db, { adminId: req.admin.id, deviceId: req.params.deviceId, action: 'clear_events', detail: { removed: r.rowCount } }, req.log);
    return { ok: true, removed: r.rowCount };
  });

  // ---------- персональные ограничения ----------

  app.patch('/v1/admin/devices/:deviceId/overrides', {
    preHandler: auth.requireAdmin,
    schema: {
      params: deviceParams,
      body: {
        type: 'object',
        additionalProperties: false,
        properties: {
          forceLanguage: { type: ['string', 'null'], enum: ['ru', 'en', null] },
          disableSearch: { type: ['boolean', 'null'] },
          maxMessagesPerDay: { type: ['integer', 'null'], minimum: 1, maximum: 100_000 },
          // Мут: доступ к нейросети приостановлен (поддержка/избранное доступны). Соблюдается сервером.
          // Либо boolean (true = навсегда), либо {until, reason} (срок + причина).
          muteAi: {
            anyOf: [
              { type: ['boolean', 'null'] },
              { type: 'object', additionalProperties: false, properties: {
                until: { type: ['string', 'null'] }, reason: { type: ['string', 'null'], maxLength: 300 } } },
            ],
          },
          // Запрет писать в поддержку. Соблюдается сервером. Тот же формат.
          blockSupport: {
            anyOf: [
              { type: ['boolean', 'null'] },
              { type: 'object', additionalProperties: false, properties: {
                until: { type: ['string', 'null'] }, reason: { type: ['string', 'null'], maxLength: 300 } } },
            ],
          },
        },
      },
    },
  }, async (req) => {
    await ensure(db, req.admin, 'block'); // мут/запрет поддержки — тоже «блокировка» (п.3)
    const d = await requireDevice(req.params.deviceId);
    const next = { ...deviceOverrides(d) };
    for (const [k, v] of Object.entries(req.body || {})) {
      if (v === null || v === false) delete next[k]; // false/null — ограничение снято
      else next[k] = v;
    }
    await db.query('UPDATE devices SET overrides = $2 WHERE id = $1', [d.id, JSON.stringify(next)]);
    hub.sendToDevice(d.id, { t: 'overrides', overrides: next });
    await logAdminAction(db, { adminId: req.admin.id, deviceId: d.id, action: 'overrides', detail: req.body }, req.log);
    return { overrides: next };
  });

  // ---------- заметки ----------

  const toNote = (r) => ({
    id: r.id,
    deviceId: r.device_id,
    adminId: r.admin_id || null,
    adminName: r.admin_name || r.admin_login || r.admin_email || null,
    text: r.text,
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
  });
  const NOTE_SQL = `SELECT n.*, a.name AS admin_name, a.login AS admin_login, a.email AS admin_email
    FROM admin_notes n LEFT JOIN admins a ON a.id = n.admin_id`;
  const noteBody = {
    type: 'object', required: ['text'], properties: { text: { type: 'string', minLength: 1, maxLength: 4000 } },
  };

  app.get('/v1/admin/devices/:deviceId/notes', { preHandler: auth.requireAdmin, schema: { params: deviceParams } }, async (req) => {
    await requireDevice(req.params.deviceId);
    const rows = await db.many(`${NOTE_SQL} WHERE n.device_id = $1 ORDER BY n.created_at DESC`, [req.params.deviceId]);
    return rows.map(toNote);
  });

  app.post('/v1/admin/devices/:deviceId/notes', {
    preHandler: auth.requireAdmin, schema: { params: deviceParams, body: noteBody },
  }, async (req, reply) => {
    await requireDevice(req.params.deviceId);
    const text = req.body.text.trim();
    if (!text) throw badRequest('Пустая заметка');
    const id = randomUUID();
    await db.query('INSERT INTO admin_notes (id, device_id, admin_id, text, created_at) VALUES ($1, $2, $3, $4, $5)',
      [id, req.params.deviceId, req.admin.id, text, new Date()]);
    reply.code(201);
    return toNote(await db.one(`${NOTE_SQL} WHERE n.id = $1`, [id]));
  });

  const noteParams = { type: 'object', required: ['noteId'], properties: { noteId: uuid } };

  app.patch('/v1/admin/notes/:noteId', {
    preHandler: auth.requireAdmin, schema: { params: noteParams, body: noteBody },
  }, async (req) => {
    const text = req.body.text.trim();
    if (!text) throw badRequest('Пустая заметка');
    const r = await db.query('UPDATE admin_notes SET text = $2, updated_at = $3 WHERE id = $1', [req.params.noteId, text, new Date()]);
    if (!r.rowCount) throw notFound('Заметка не найдена');
    return toNote(await db.one(`${NOTE_SQL} WHERE n.id = $1`, [req.params.noteId]));
  });

  app.delete('/v1/admin/notes/:noteId', { preHandler: auth.requireAdmin, schema: { params: noteParams } }, async (req) => {
    const r = await db.query('DELETE FROM admin_notes WHERE id = $1', [req.params.noteId]);
    if (!r.rowCount) throw notFound('Заметка не найдена');
    return { ok: true };
  });

  // ---------- журнал действий ----------

  app.get('/v1/admin/actions', {
    preHandler: auth.requireAdmin,
    schema: { querystring: { type: 'object', properties: { deviceId: uuid, ...pageQuery } } },
  }, async (req) => {
    const where = [];
    const params = [];
    if (req.query.deviceId) { params.push(req.query.deviceId); where.push(`x.device_id = $${params.length}`); }
    if (req.query.before) { params.push(new Date(req.query.before)); where.push(`x.at < $${params.length}`); }
    params.push(req.query.limit);
    const rows = await db.many(
      `SELECT x.*, a.name AS admin_name, a.login AS admin_login, a.email AS admin_email
         FROM admin_actions x LEFT JOIN admins a ON a.id = x.admin_id
        ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
        ORDER BY x.at DESC LIMIT $${params.length}`, params);
    return rows.map(toAdminAction);
  });
}
