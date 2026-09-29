// Публичный ID пользователя: строка из 4 цифр ("0000".."9999"), случайная и уникальная.
// Когда все 10 000 четырёхзначных заняты — выдаём 5 цифр, затем 6 и т. д.
import { randomInt } from 'node:crypto';

/** Выбирает случайный свободный ID; used — Set уже занятых строк. Чистая функция (для тестов). */
export function pickPublicId(used, random = randomInt) {
  for (let digits = 4; digits <= 9; digits++) {
    const size = 10 ** digits;
    const taken = [...used].filter((v) => v.length === digits).length;
    if (taken >= size) continue;
    const fmt = (n) => String(n).padStart(digits, '0');
    // Пока диапазон заполнен меньше чем наполовину — случайные попытки почти всегда удачны.
    for (let i = 0; i < 40; i++) {
      const id = fmt(random(0, size));
      if (!used.has(id)) return id;
    }
    // Диапазон почти полон: выбираем среди оставшихся свободных.
    const free = [];
    for (let n = 0; n < size; n++) if (!used.has(fmt(n))) free.push(fmt(n));
    if (free.length) return free[random(0, free.length)];
  }
  throw new Error('public id space exhausted');
}

async function usedIds(db) {
  const rows = await db.many('SELECT public_id FROM users WHERE public_id IS NOT NULL');
  return new Set(rows.map((r) => r.public_id));
}

/** Выдаёт пользователю ID, если его ещё нет. Возвращает ID. */
export async function ensurePublicId(db, userId) {
  const row = await db.one('SELECT public_id FROM users WHERE id = $1', [userId]);
  if (!row) return null;
  if (row.public_id) return row.public_id;
  for (let attempt = 0; attempt < 5; attempt++) {
    const id = pickPublicId(await usedIds(db));
    try {
      await db.query('UPDATE users SET public_id = $2 WHERE id = $1 AND public_id IS NULL', [userId, id]);
    } catch (err) {
      if (err.code === '23505') continue; // параллельная регистрация заняла тот же номер — пробуем другой
      throw err;
    }
    const fresh = await db.one('SELECT public_id FROM users WHERE id = $1', [userId]);
    if (fresh?.public_id) return fresh.public_id;
  }
  throw new Error('could not assign public id');
}

/** Досыпает ID всем существующим пользователям без него (при старте сервера). */
export async function backfillPublicIds(db, logger) {
  const rows = await db.many('SELECT id FROM users WHERE public_id IS NULL');
  if (!rows.length) return 0;
  const used = await usedIds(db);
  let done = 0;
  for (const r of rows) {
    const id = pickPublicId(used);
    try {
      await db.query('UPDATE users SET public_id = $2 WHERE id = $1 AND public_id IS NULL', [r.id, id]);
      used.add(id);
      done += 1;
    } catch (err) {
      if (err.code !== '23505') throw err;
      used.add(id);
    }
  }
  logger?.info({ count: done }, 'public ids backfilled');
  return done;
}
