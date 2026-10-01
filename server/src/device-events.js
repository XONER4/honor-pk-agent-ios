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

/** Записать попытку/факт входа в админ-панель. Ошибка журнала не ломает вход. */
export async function recordAdminLogin(db, { adminId = null, loginTried = null, success, method = null, ip = null, userAgent = null }, logger) {
  try {
    await db.query(
      'INSERT INTO admin_logins (id, admin_id, login_tried, success, method, ip, user_agent, at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)',
      [randomUUID(), adminId, loginTried, success, method, ip, userAgent ? String(userAgent).slice(0, 400) : null, new Date()]);
  } catch (err) {
    logger?.warn({ err: { message: err.message } }, 'admin login log failed');
  }
}

export const toAdminLogin = (r) => ({
  id: r.id,
  adminId: r.admin_id || null,
  adminName: r.admin_name || r.admin_login || r.admin_email || null,
  loginTried: r.login_tried || null,
  success: !!r.success,
  method: r.method || null,
  ip: r.ip || null,
  userAgent: r.user_agent || null,
  at: iso(r.at),
});

/** Записать просмотр карточки пользователя администратором. */
export async function recordProfileView(db, { adminId, deviceId }, logger) {
  try {
    await db.query('INSERT INTO profile_views (id, admin_id, device_id, at) VALUES ($1, $2, $3, $4)',
      [randomUUID(), adminId, deviceId, new Date()]);
  } catch (err) {
    logger?.warn({ err: { message: err.message } }, 'profile view log failed');
  }
}

export const toProfileView = (r) => ({
  id: r.id,
  adminId: r.admin_id,
  adminName: r.admin_name || r.admin_login || r.admin_email || null,
  adminRole: r.admin_role || null,
  deviceId: r.device_id,
  at: iso(r.at),
});

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
