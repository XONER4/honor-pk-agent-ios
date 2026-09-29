// Тонкий адаптер БД: единый интерфейс { query, one, many, close } поверх настоящего Postgres (pg.Pool)
// или in-memory эмуляции pg-mem (DB_MODE=memory — для тестов и локальной разработки).
// Весь SQL в проекте параметризован ($1, $2 …); особенности pg-mem изолированы здесь.
import pg from 'pg';

// Postgres отдаёт BIGINT/COUNT как строки — приводим к числам (значения у нас заведомо < 2^53).
pg.types.setTypeParser(20, (v) => Number(v));

export async function createDb(config, logger) {
  if (config.dbMode === 'memory') return createMemoryDb();

  const pool = new pg.Pool({
    connectionString: config.databaseUrl,
    ssl: config.databaseSsl ? { rejectUnauthorized: false } : undefined,
    max: 10,
    idleTimeoutMillis: 30_000,
  });
  // Ошибки простаивающих соединений не должны ронять процесс.
  pool.on('error', (err) => logger?.error({ err: { message: err.message } }, 'postgres pool error'));
  return wrap(pool, 'postgres');
}

async function createMemoryDb() {
  // pg-mem — devDependency; в production-образе его нет, поэтому импорт динамический.
  const { newDb } = await import('pg-mem');
  const mem = newDb({ autoCreateForeignKeyIndices: true });
  const { Pool } = mem.adapters.createPg();
  const pool = new Pool();
  const db = wrap(pool, 'memory');
  const rawQuery = db.query;
  // pg-mem не умеет повторный «CREATE TABLE IF NOT EXISTS» для уже существующей таблицы с ограничениями,
  // поэтому пропускаем такие операторы сами (в Postgres они и так идемпотентны).
  db.query = async (text, params) => {
    const m = /^\s*CREATE TABLE IF NOT EXISTS\s+(\w+)/i.exec(text);
    if (m && mem.public.getTable(m[1], true)) return { rows: [], rowCount: 0 };
    return rawQuery(text, params);
  };
  return db;
}

function wrap(pool, mode) {
  const db = {
    mode,
    async query(text, params = []) {
      return pool.query(text, params);
    },
    async one(text, params) {
      const r = await db.query(text, params);
      return r.rows[0] || null;
    },
    async many(text, params) {
      const r = await db.query(text, params);
      return r.rows;
    },
    async close() {
      await pool.end();
    },
  };
  return db;
}

/** Генерирует список плейсхолдеров "$k, $k+1, …" для IN (...) — значения всё равно передаются параметрами. */
export function placeholders(count, start = 1) {
  return Array.from({ length: count }, (_, i) => `$${start + i}`).join(', ');
}

/** JSONB из pg-mem иногда приходит строкой — нормализуем. */
export function json(v, def) {
  if (v === null || v === undefined) return def;
  if (typeof v === 'string') {
    try { return JSON.parse(v); } catch { return def; }
  }
  return v;
}

export const iso = (d) => (d ? new Date(d).toISOString() : null);
