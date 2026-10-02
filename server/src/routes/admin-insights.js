// Админка, «аналитика и управление»: метрики, глобальный ИИ и расписание, отчёты об ошибках,
// история версий, заметки, журнал действий, персональные ограничения устройства.
import { randomUUID } from 'node:crypto';
import { badRequest, notFound } from '../errors.js';
import { iso } from '../db.js';
import { isValidTimezone } from '../ai-control.js';
import { logAdminAction, toAdminAction, toDeviceEvent } from '../device-events.js';
import { deviceOverrides } from './devices.js';
import { uuid } from './schemas.js';

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
  const { db, auth, hub, aiControl, metricsSnapshot } = app.ctx;

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
