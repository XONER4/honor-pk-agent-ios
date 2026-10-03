// Права администратора (план админка п.3). Разработчик может всё; остальным разработчик выдаёт флаги.
import { forbidden } from './errors.js';

/** Набор прав. Ключи совпадают с переключателями в админке. */
export const PERMISSION_KEYS = ['reply', 'assign', 'block', 'broadcast', 'manageAi'];

/** Разбор JSONB-поля permissions (pg отдаёт объект, память — строку/объект). */
function perms(admin) {
  let p = admin && admin.permissions;
  if (typeof p === 'string') { try { p = JSON.parse(p); } catch { p = {}; } }
  return (p && typeof p === 'object') ? p : {};
}

/** Может ли админ выполнить действие key. Разработчик — всегда да. */
export function can(admin, key) {
  if (!admin) return false;
  if ((admin.role || 'admin') === 'developer') return true;
  return perms(admin)[key] === true;
}

/**
 * Бросает 403, если у админа нет права key. Бутстрап: пока в системе нет НИ ОДНОГО разработчика,
 * любой админ может всё (иначе свежую установку/вход по ключу не поднять). Как только появился
 * разработчик — не-разработчики ограничены своими флагами.
 */
export async function ensure(db, admin, key) {
  if (can(admin, key)) return;
  const dev = await db.one("SELECT 1 AS x FROM admins WHERE role = 'developer' LIMIT 1");
  if (!dev) return;
  throw forbidden('Недостаточно прав для этого действия');
}

/** Оставляет только известные булевы флаги (защита от мусора в запросе). */
export function normalizePermissions(value) {
  const out = {};
  if (value && typeof value === 'object') {
    for (const k of PERMISSION_KEYS) if (value[k] === true) out[k] = true;
  }
  return out;
}

/** Карта прав для ответа клиенту: явные флаги, у разработчика — все true. */
export function permissionMap(admin) {
  const developer = (admin && (admin.role || 'admin') === 'developer');
  const p = perms(admin);
  const out = {};
  for (const k of PERMISSION_KEYS) out[k] = developer ? true : p[k] === true;
  return out;
}
