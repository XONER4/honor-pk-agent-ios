// Загрузка и выдача медиа. POST /v1/media (multipart, поле file) → AttachmentRef. GET /v1/media/:id (auth, Range).
import { ApiError, badRequest, forbidden, notFound } from "./errors.ts";
import { getDevice, type Kv } from "./kv.ts";
import { kindFromMime, type MediaRow, parseRange, sanitizeMime, sanitizeName } from "./media.ts";
import { guard, type AppCtx } from "./app.ts";
import { jsonResponse, type ReqCtx, reqUuidParam, Router, SECURITY_HEADERS } from "./http.ts";

const KINDS = new Set(["image", "video", "audio", "voice", "file"]);
const optInt = (v: string | null, max: number): number | null => {
  if (v === null || v === "") return null;
  const n = Number(v);
  return Number.isInteger(n) && n >= 0 && n <= max ? n : null;
};

export function registerMediaRoutes(app: AppCtx, router: Router) {
  const { kv, media, chats } = app as { kv: Kv } & AppCtx;

  router.post("/v1/media", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["device", "admin"]);
    const ct = ctx.header("content-type") || "";
    if (!/multipart\/form-data/i.test(ct)) throw new ApiError(415, "unsupported_media_type", "Ожидается multipart/form-data");
    let form: FormData;
    try {
      form = await ctx.req.formData();
    } catch {
      throw badRequest("Некорректная форма");
    }
    const file = form.get("file");
    if (!(file instanceof File)) throw badRequest("Нет файла в поле file");
    if (file.size > app.config.maxUploadBytes) throw new ApiError(413, "payload_too_large", "Файл больше допустимого размера");

    const id = crypto.randomUUID();
    const { size, chunks } = await media.writeStream(id, file.stream());
    const mime = sanitizeMime(file.type);
    const fieldKind = form.get("kind");
    const fieldName = form.get("name");
    const kind = typeof fieldKind === "string" && KINDS.has(fieldKind) ? fieldKind : kindFromMime(mime);
    const row: MediaRow = {
      id,
      uploaderKind: ctx.principal!.kind,
      uploaderId: ctx.principal!.kind === "device" ? ctx.device!.id : ctx.admin!.id,
      kind,
      name: typeof fieldName === "string" && fieldName ? sanitizeName(fieldName) : sanitizeName(file.name),
      mime,
      size,
      durationMs: optInt(form.get("durationMs") as string | null, 86_400_000),
      width: optInt(form.get("width") as string | null, 100_000),
      height: optInt(form.get("height") as string | null, 100_000),
      chunks,
      createdAt: Date.now(),
    };
    await kv.set(["media", id], row);
    return jsonResponse({
      id,
      kind: row.kind,
      name: row.name,
      mime: row.mime,
      size: row.size,
      durationMs: row.durationMs,
      width: row.width,
      height: row.height,
      url: `/v1/media/${id}`,
    }, 201);
  });

  router.get("/v1/media/:id", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["device", "admin"], { allowQuery: true });
    const id = reqUuidParam(ctx, "id");
    const m = (await kv.get<MediaRow>(["media", id])).value;
    if (!m) throw notFound("Файл не найден");
    if (ctx.principal!.kind === "device") {
      const own = m.uploaderKind === "device" && m.uploaderId === ctx.device!.id;
      if (!own) {
        const chat = await chats.getDeviceChat(ctx.device!.id);
        const link = chat && (await kv.get(["mediaLink", chat.id, m.id])).value;
        if (!link) throw forbidden("Нет доступа к файлу");
      }
    }
    const size = m.size;
    const range = parseRange(ctx.header("range"), size);
    const baseHeaders: Record<string, string> = {
      ...SECURITY_HEADERS,
      "Accept-Ranges": "bytes",
      "Content-Type": m.mime,
      "Content-Disposition": `inline; filename*=UTF-8''${encodeURIComponent(m.name)}`,
      "Cache-Control": "private, max-age=31536000, immutable",
      "ETag": `"${m.id}"`,
    };
    if (range === "unsatisfiable") {
      return new Response(JSON.stringify({ error: "range_not_satisfiable", message: "Некорректный диапазон" }), {
        status: 416,
        headers: { "content-type": "application/json; charset=utf-8", ...SECURITY_HEADERS, "Content-Range": `bytes */${size}` },
      });
    }
    if (range) {
      return new Response(media.readRange(id, range.start, range.end), {
        status: 206,
        headers: {
          ...baseHeaders,
          "Content-Range": `bytes ${range.start}-${range.end}/${size}`,
          "Content-Length": String(range.end - range.start + 1),
        },
      });
    }
    return new Response(size > 0 ? media.readRange(id, 0, size - 1) : new Uint8Array(0), {
      status: 200,
      headers: { ...baseHeaders, "Content-Length": String(size) },
    });
  });
}
