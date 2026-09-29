// Хранилище медиа в Deno KV: метаданные в ["media", id], байты — чанками по CHUNK_SIZE в ["mediaChunk", id, index]
// (значение KV ограничено ~64 КБ). GET поддерживает Range, собирая нужные чанки.
import { ApiError } from "./errors.ts";
import type { Kv } from "./kv.ts";

export const CHUNK_SIZE = 60 * 1024; // < 64 KiB — лимит значения Deno KV
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MIME_RE = /^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,127}$/i;

export interface MediaRow {
  id: string;
  uploaderKind: string;
  uploaderId: string;
  kind: string;
  name: string;
  mime: string;
  size: number;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  chunks: number;
  createdAt: number;
}

export function createMediaStore({ kv, maxUploadBytes }: { kv: Kv; maxUploadBytes: number }) {
  function checkId(id: string) {
    if (!UUID_RE.test(id)) throw new Error("bad media id");
  }

  /** Пишет поток чанками. Возвращает { size, chunks }. Бросает 413 при превышении лимита. */
  async function writeStream(id: string, stream: ReadableStream<Uint8Array>): Promise<{ size: number; chunks: number }> {
    checkId(id);
    const reader = stream.getReader();
    let index = 0;
    let size = 0;
    let carry = new Uint8Array(0);
    const flushFull = async () => {
      while (carry.length >= CHUNK_SIZE) {
        await kv.set(["mediaChunk", id, index], carry.slice(0, CHUNK_SIZE));
        index += 1;
        carry = carry.slice(CHUNK_SIZE);
      }
    };
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        if (!value || value.length === 0) continue;
        size += value.length;
        if (size > maxUploadBytes) throw new ApiError(413, "payload_too_large", "Файл больше допустимого размера");
        const merged = new Uint8Array(carry.length + value.length);
        merged.set(carry);
        merged.set(value, carry.length);
        carry = merged;
        await flushFull();
      }
      if (carry.length > 0) {
        await kv.set(["mediaChunk", id, index], carry);
        index += 1;
      }
      return { size, chunks: index };
    } catch (err) {
      await removeBytes(id, index);
      throw err;
    } finally {
      reader.releaseLock?.();
    }
  }

  async function removeBytes(id: string, chunks: number) {
    for (let i = 0; i < chunks; i++) await kv.delete(["mediaChunk", id, i]);
    // на всякий случай подчистим и хвосты
    for await (const e of kv.list({ prefix: ["mediaChunk", id] })) await kv.delete(e.key);
  }

  async function remove(id: string) {
    const m = (await kv.get<MediaRow>(["media", id])).value;
    await removeBytes(id, m?.chunks || 0);
    await kv.delete(["media", id]);
  }

  /** Читает диапазон [start, end] включительно как поток. */
  function readRange(id: string, start: number, end: number): ReadableStream<Uint8Array> {
    let pos = start;
    let chunkIndex = Math.floor(start / CHUNK_SIZE);
    return new ReadableStream({
      async pull(controller) {
        if (pos > end) {
          controller.close();
          return;
        }
        const chunk = (await kv.get<Uint8Array>(["mediaChunk", id, chunkIndex])).value;
        if (!chunk) {
          controller.close();
          return;
        }
        const chunkStart = chunkIndex * CHUNK_SIZE;
        const from = Math.max(0, pos - chunkStart);
        const to = Math.min(chunk.length, end - chunkStart + 1);
        if (to > from) {
          controller.enqueue(chunk.subarray(from, to));
          pos = chunkStart + to;
        }
        chunkIndex += 1;
      },
    });
  }

  /** Удаляет медиа, на которые больше нет ссылок ни из одного чата. */
  async function removeOrphans(ids: string[]) {
    for (const id of ids) {
      let linked = false;
      for await (const _e of kv.list({ prefix: ["mediaLinkByMedia", id] }, { limit: 1 })) {
        linked = true;
      }
      if (linked) continue;
      await remove(id);
    }
  }

  return { writeStream, remove, removeOrphans, readRange };
}

export type MediaStore = ReturnType<typeof createMediaStore>;

export function sanitizeMime(mime: string): string {
  const m = String(mime || "").toLowerCase().split(";")[0].trim();
  return MIME_RE.test(m) ? m : "application/octet-stream";
}
export function kindFromMime(mime: string): string {
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime.startsWith("audio/")) return "audio";
  return "file";
}
export function sanitizeName(name: string): string {
  const base = String(name || "file").split(/[\\/]/).pop()!.replace(/[\u0000-\u001f\u007f"]/g, "").trim();
  return (base || "file").slice(0, 200);
}

/** Разбирает Range (один диапазон). null | {start,end} | 'unsatisfiable'. */
export function parseRange(header: string | null | undefined, size: number): null | { start: number; end: number } | "unsatisfiable" {
  if (!header) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(String(header).trim());
  if (!m || (m[1] === "" && m[2] === "")) return null;
  let start: number;
  let end: number;
  if (m[1] === "") {
    const suffix = Number(m[2]);
    if (suffix === 0) return "unsatisfiable";
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(m[1]);
    end = m[2] === "" ? size - 1 : Math.min(Number(m[2]), size - 1);
  }
  if (start >= size || start > end) return "unsatisfiable";
  return { start, end };
}
