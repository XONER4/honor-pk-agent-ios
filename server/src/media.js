// Хранилище медиафайлов на диске (MEDIA_DIR — том Railway) + разбор HTTP Range.
// Файл хранится под именем <uuid> без расширения; метаданные — в таблице media.
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { placeholders } from './db.js';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MIME_RE = /^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,127}$/i;

export function createMediaStore({ db, config, logger }) {
  const dir = config.mediaDir;

  async function init() {
    await fsp.mkdir(dir, { recursive: true });
  }

  const filePath = (id) => {
    if (!UUID_RE.test(id)) throw new Error('bad media id');
    return path.join(dir, id);
  };

  /** Пишет поток во временный файл и атомарно переименовывает. Возвращает размер. Бросает 413, если превышен лимит. */
  async function writeStream(id, stream) {
    const tmp = `${filePath(id)}.part`;
    try {
      await pipeline(stream, fs.createWriteStream(tmp, { flags: 'wx' }));
      if (stream.truncated) {
        const err = new Error('file too large');
        err.statusCode = 413;
        throw err;
      }
      const { size } = await fsp.stat(tmp);
      await fsp.rename(tmp, filePath(id));
      return size;
    } catch (err) {
      await fsp.rm(tmp, { force: true });
      throw err;
    }
  }

  async function remove(id) {
    await fsp.rm(filePath(id), { force: true });
  }

  /** Удаляет файлы и строки media, на которые больше нет ссылок ни из одного чата. */
  async function removeOrphans(ids) {
    if (!ids.length) return;
    const still = new Set((await db.many(
      `SELECT media_id FROM media_links WHERE media_id IN (${placeholders(ids.length)})`, ids)).map((r) => r.media_id));
    for (const id of ids) {
      if (still.has(id)) continue;
      await db.query('DELETE FROM media WHERE id = $1', [id]);
      await remove(id).catch((err) => logger?.warn({ err: { message: err.message } }, 'media remove failed'));
    }
  }

  return { dir, init, filePath, writeStream, remove, removeOrphans };
}

export function sanitizeMime(mime) {
  const m = String(mime || '').toLowerCase().split(';')[0].trim();
  return MIME_RE.test(m) ? m : 'application/octet-stream';
}

export function kindFromMime(mime) {
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('video/')) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  return 'file';
}

export function sanitizeName(name) {
  // Убираем путь и управляющие символы, ограничиваем длину.
  const base = String(name || 'file').split(/[\\/]/).pop().replace(/[\u0000-\u001f\u007f"]/g, '').trim();
  return (base || 'file').slice(0, 200);
}

/**
 * Разбирает заголовок Range (поддерживается один диапазон bytes=a-b | a- | -n).
 * Возвращает null (нет/игнорируем заголовок), { start, end } или 'unsatisfiable'.
 */
export function parseRange(header, size) {
  if (!header) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(String(header).trim());
  if (!m || (m[1] === '' && m[2] === '')) return null; // мульти-диапазоны и мусор игнорируем → 200 целиком
  let start;
  let end;
  if (m[1] === '') {
    const suffix = Number(m[2]);
    if (suffix === 0) return 'unsatisfiable';
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(m[1]);
    end = m[2] === '' ? size - 1 : Math.min(Number(m[2]), size - 1);
  }
  if (start >= size || start > end) return 'unsatisfiable';
  return { start, end };
}
