// Уведомления устройства. Рассылка создаётся админом (routes/admin.js); у каждого устройства — своя копия
// (так хранится персональный флаг read).
import { placeholders, iso } from '../db.js';
import { uuid } from './schemas.js';

export const toNotification = (r) => ({
  id: r.id,
  title: r.title,
  body: r.body,
  createdAt: iso(r.created_at),
  read: !!r.read_at,
  chatId: r.chat_id || null,
  kind: r.kind,
});

export default async function notificationRoutes(app) {
  const { db, auth } = app.ctx;

  app.get('/v1/notifications', {
    preHandler: auth.requireDevice,
    schema: { querystring: { type: 'object', properties: { after: { type: 'string', format: 'date-time' } } } },
  }, async (req) => {
    const params = [req.device.id];
    let cond = '';
    if (req.query.after) {
      params.push(new Date(req.query.after));
      cond = ' AND created_at > $2';
    }
    const rows = await db.many(
      `SELECT * FROM notifications WHERE device_id = $1${cond} ORDER BY created_at DESC LIMIT 200`, params);
    return rows.reverse().map(toNotification); // старые первыми
  });

  app.post('/v1/notifications/read', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', required: ['ids'], properties: { ids: { type: 'array', maxItems: 500, items: uuid } } } },
  }, async (req) => {
    const ids = [...new Set(req.body.ids)];
    if (ids.length) {
      await db.query(
        `UPDATE notifications SET read_at = $2 WHERE device_id = $1 AND read_at IS NULL AND id IN (${placeholders(ids.length, 3)})`,
        [req.device.id, new Date(), ...ids]);
    }
    return { ok: true };
  });
}
