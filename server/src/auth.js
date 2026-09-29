// Токены и проверка авторизации.
// Токен = префикс (d_ устройство / a_ админ) + 32 случайных байта base64url. В БД хранится только SHA-256.
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { blocked, forbidden, unauthorized } from './errors.js';

export const newToken = (prefix) => `${prefix}_${randomBytes(32).toString('base64url')}`;
export const hashToken = (token) => createHash('sha256').update(String(token)).digest('hex');

/** Сравнение секретов за постоянное время (длины выравниваются хешированием). */
export function safeEqual(a, b) {
  const ha = createHash('sha256').update(String(a)).digest();
  const hb = createHash('sha256').update(String(b)).digest();
  return timingSafeEqual(ha, hb) && String(a).length === String(b).length;
}

const TOKEN_RE = /^[da]_[A-Za-z0-9_-]{20,100}$/;

/** Достаёт токен из заголовка Authorization (или ?token= там, где это разрешено: WebSocket, медиа). */
export function tokenFromRequest(request, { allowQuery = false } = {}) {
  const h = request.headers.authorization;
  if (typeof h === 'string' && /^Bearer\s+/i.test(h)) return h.replace(/^Bearer\s+/i, '').trim();
  if (allowQuery && typeof request.query?.token === 'string') return request.query.token;
  return null;
}

export function createAuth({ db }) {
  /** Возвращает { kind: 'device', device } | { kind: 'admin', admin } | null. */
  async function resolve(token) {
    if (!token || !TOKEN_RE.test(token)) return null;
    const row = await db.one('SELECT kind, subject_id, expires_at FROM tokens WHERE hash = $1', [hashToken(token)]);
    if (!row) return null;
    if (row.expires_at && new Date(row.expires_at).getTime() < Date.now()) return null;
    if (row.kind === 'device' && token.startsWith('d_')) {
      const device = await db.one('SELECT * FROM devices WHERE id = $1', [row.subject_id]);
      return device ? { kind: 'device', device } : null;
    }
    if (row.kind === 'admin' && token.startsWith('a_')) {
      const admin = await db.one('SELECT * FROM admins WHERE id = $1', [row.subject_id]);
      return admin ? { kind: 'admin', admin } : null;
    }
    return null;
  }

  async function issueDeviceToken(deviceId) {
    // Повторная регистрация отзывает все старые токены устройства.
    await db.query(`DELETE FROM tokens WHERE subject_id = $1 AND kind = 'device'`, [deviceId]);
    const token = newToken('d');
    await db.query('INSERT INTO tokens (hash, kind, subject_id, created_at) VALUES ($1, $2, $3, $4)',
      [hashToken(token), 'device', deviceId, new Date()]);
    return token;
  }

  async function issueAdminToken(adminId, ttlMs) {
    const token = newToken('a');
    const now = new Date();
    await db.query('INSERT INTO tokens (hash, kind, subject_id, created_at, expires_at) VALUES ($1, $2, $3, $4, $5)',
      [hashToken(token), 'admin', adminId, now, new Date(now.getTime() + ttlMs)]);
    return token;
  }

  // preHandler-хуки для маршрутов.
  const make = (accept, opts = {}) => async function authHook(request) {
    const who = await resolve(tokenFromRequest(request, opts));
    if (!who) throw unauthorized();
    if (!accept.includes(who.kind)) throw forbidden();
    if (who.kind === 'device') {
      if (who.device.blocked) throw blocked(who.device.block_reason);
      request.device = who.device;
    } else {
      request.admin = who.admin;
    }
    request.principal = who;
  };

  return {
    resolve,
    issueDeviceToken,
    issueAdminToken,
    requireDevice: make(['device']),
    requireAdmin: make(['admin']),
    requireAny: make(['device', 'admin']),
    requireAnyQueryToken: make(['device', 'admin'], { allowQuery: true }),
  };
}
