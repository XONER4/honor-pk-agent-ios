// События устройства (device_events) и журнал действий админов (admin_actions).
import { randomUUID } from 'node:crypto';
import { iso } from './db.js';

/** kind: install | update | uninstall | open. */
export async function recordDeviceEvent(db, deviceId, kind, { from = null, to = null, at = new Date() } = {}) {
  await db.query(
    'INSERT INTO device_events (id, device_id, kind, from_version, to_version, at) VALUES ($1, $2, $3, $4, $5, $6)',
    [randomUUID(), deviceId, kind, from || null, to || null, at]);
}

export const toDeviceEvent = (r) => ({
  id: r.id,
  deviceId: r.device_id,
  kind: r.kind,
  fromVersion: r.from_version || null,
  toVersion: r.to_version || null,
  at: iso(r.at),
});

/**
 * Запись в журнал действий. adminId = null — действие сервера. detail — объект (хранится JSON-строкой).
 * Ошибка журнала не должна ломать само действие.
 */
export async function logAdminAction(db, { adminId = null, deviceId = null, action, detail = null }, logger) {
  try {
    await db.query(
      'INSERT INTO admin_actions (id, admin_id, device_id, action, detail, at) VALUES ($1, $2, $3, $4, $5, $6)',
      [randomUUID(), adminId, deviceId, action, detail == null ? null : JSON.stringify(detail), new Date()]);
  } catch (err) {
    logger?.warn({ err: { message: err.message } }, 'admin action log failed');
  }
}

export function toAdminAction(r) {
  let detail = null;
  if (r.detail) {
    try { detail = JSON.parse(r.detail); } catch { detail = r.detail; }
  }
  return {
    id: r.id,
    adminId: r.admin_id || null,
    adminName: r.admin_name || r.admin_login || r.admin_email || (r.admin_id ? null : 'Сервер'),
    deviceId: r.device_id || null,
    action: r.action,
    detail,
    at: iso(r.at),
  };
}
