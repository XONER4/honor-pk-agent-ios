// Загрузка и выдача медиа. POST /v1/media (multipart, поле file ≤ MAX_UPLOAD_BYTES) → AttachmentRef.
// GET /v1/media/:id — только с авторизацией (заголовок или ?token=), поддерживает Range (перемотка видео).
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { ApiError, badRequest, forbidden, notFound } from '../errors.js';
import { kindFromMime, parseRange, sanitizeMime, sanitizeName } from '../media.js';
import { uuid } from './schemas.js';

const KINDS = new Set(['image', 'video', 'audio', 'voice', 'file']);
const optInt = (v, max) => {
  if (v === undefined || v === null || v === '') return null;
  const n = Number(v);
  return Number.isInteger(n) && n >= 0 && n <= max ? n : null;
};

export default async function mediaRoutes(app) {
  const { db, auth, media, chats } = app.ctx;

  app.post('/v1/media', { preHandler: auth.requireAny }, async (req, reply) => {
    if (!req.isMultipart()) throw new ApiError(415, 'unsupported_media_type', 'Ожидается multipart/form-data');
    const id = randomUUID();
    const fields = {};
    let file = null;
    try {
      for await (const part of req.parts()) {
        if (part.type === 'file') {
          if (part.fieldname !== 'file' || file) {
            part.file.resume(); // лишние файлы пропускаем
            continue;
          }
          const size = await media.writeStream(id, part.file);
          file = { size, name: sanitizeName(part.filename), mime: sanitizeMime(part.mimetype) };
        } else if (typeof part.value === 'string' && part.value.length <= 200) {
          fields[part.fieldname] = part.value;
        }
      }
    } catch (err) {
      await media.remove(id).catch(() => {});
      if (err.statusCode === 413 || err.code === 'FST_REQ_FILE_TOO_LARGE') {
        throw new ApiError(413, 'payload_too_large', 'Файл больше допустимого размера');
      }
      throw err;
    }
    if (!file) throw badRequest('Нет файла в поле file');

    // Необязательные поля формы (метаданные, которые знает только клиент).
    const kind = KINDS.has(fields.kind) ? fields.kind : kindFromMime(file.mime);
    const row = {
      id,
      kind,
      name: fields.name ? sanitizeName(fields.name) : file.name,
      mime: file.mime,
      size: file.size,
      durationMs: optInt(fields.durationMs, 86_400_000),
      width: optInt(fields.width, 100_000),
      height: optInt(fields.height, 100_000),
    };
    const uploaderKind = req.principal.kind;
    const uploaderId = uploaderKind === 'device' ? req.device.id : req.admin.id;
    await db.query(
      `INSERT INTO media (id, uploader_kind, uploader_id, kind, name, mime, size, duration_ms, width, height, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
      [id, uploaderKind, uploaderId, row.kind, row.name, row.mime, row.size, row.durationMs, row.width, row.height, new Date()]);
    reply.code(201);
    return { ...row, url: `/v1/media/${id}` };
  });

  app.get('/v1/media/:id', {
    preHandler: auth.requireAnyQueryToken,
    schema: { params: { type: 'object', required: ['id'], properties: { id: uuid } } },
  }, async (req, reply) => {
    const m = await db.one('SELECT * FROM media WHERE id = $1', [req.params.id]);
    if (!m) throw notFound('Файл не найден');
    if (req.principal.kind === 'device') {
      const own = m.uploader_kind === 'device' && m.uploader_id === req.device.id;
      if (!own) {
        const chat = await chats.getDeviceChat(req.device.id);
        const link = chat && (await db.one('SELECT 1 AS x FROM media_links WHERE media_id = $1 AND chat_id = $2', [m.id, chat.id]));
        if (!link) throw forbidden('Нет доступа к файлу');
      }
    }

    const filePath = media.filePath(m.id);
    let size;
    try {
      size = (await fs.promises.stat(filePath)).size;
    } catch {
      throw notFound('Файл не найден');
    }
    const range = parseRange(req.headers.range, size);
    if (range === 'unsatisfiable') {
      reply.header('Content-Range', `bytes */${size}`);
      throw new ApiError(416, 'range_not_satisfiable', 'Некорректный диапазон');
    }
    const safeName = encodeURIComponent(m.name);
    reply.header('Accept-Ranges', 'bytes');
    reply.header('Content-Type', m.mime);
    reply.header('Content-Disposition', `inline; filename*=UTF-8''${safeName}`);
    reply.header('Cache-Control', 'private, max-age=31536000, immutable');
    reply.header('ETag', `"${m.id}"`);

    if (range) {
      reply.code(206);
      reply.header('Content-Range', `bytes ${range.start}-${range.end}/${size}`);
      reply.header('Content-Length', range.end - range.start + 1);
      return reply.send(fs.createReadStream(filePath, { start: range.start, end: range.end }));
    }
    reply.header('Content-Length', size);
    return reply.send(fs.createReadStream(filePath));
  });
}
