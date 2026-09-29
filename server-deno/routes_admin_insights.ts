// Админка: метрики, глобальный ИИ и расписание, отчёты, история версий, заметки, журнал, ограничения.
import { badRequest, notFound } from "./errors.ts";
import { getAdmin, getDevice, iso, type Kv, nowMs } from "./kv.ts";
import { isValidTimezone, type Window } from "./ai-control.ts";
import { type AdminAction, type DeviceEvent, logAdminAction, toAdminAction, toDeviceEvent } from "./device-events.ts";
import { deviceOverrides } from "./routes_devices.ts";
import { guard, type AppCtx } from "./app.ts";
import { dateTimeQuery, enumQuery, intQuery, isUuid, jsonResponse, type ReqCtx, reqUuidParam, Router } from "./http.ts";

const HHMM_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

export function registerAdminInsightRoutes(app: AppCtx, router: Router) {
  const { kv, hub, aiControl } = app as { kv: Kv } & AppCtx;

  async function requireDevice(deviceId: string) {
    const d = await getDevice(kv, deviceId);
    if (!d) throw notFound("Устройство не найдено");
    return d;
  }

  router.get("/v1/admin/metrics", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    return jsonResponse(await app.metricsSnapshot());
  });

  router.get("/v1/admin/ai", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    return jsonResponse(aiControl.view());
  });

  router.post("/v1/admin/ai", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const b = await ctx.json<{ enabled?: unknown; schedule?: unknown; timezone?: unknown }>();
    if (b.enabled !== undefined && typeof b.enabled !== "boolean") throw badRequest("enabled");
    if (b.timezone !== undefined) {
      if (typeof b.timezone !== "string" || b.timezone.length > 64 || !isValidTimezone(b.timezone)) throw badRequest("Неизвестный часовой пояс");
    }
    let schedule: Window[] | undefined;
    if (b.schedule !== undefined) {
      if (!Array.isArray(b.schedule) || b.schedule.length > 20) throw badRequest("schedule");
      schedule = b.schedule.map((w: unknown) => {
        const win = w as { days?: unknown; from?: unknown; to?: unknown };
        if (typeof win.from !== "string" || !HHMM_RE.test(win.from)) throw badRequest("from");
        if (typeof win.to !== "string" || !HHMM_RE.test(win.to)) throw badRequest("to");
        let days: number[] = [];
        if (win.days !== undefined) {
          if (!Array.isArray(win.days) || win.days.length > 7) throw badRequest("days");
          for (const d of win.days) if (!Number.isInteger(d) || d < 1 || d > 7) throw badRequest("days");
          days = [...new Set(win.days as number[])].sort((a, z) => a - z);
        }
        return { days, from: win.from, to: win.to };
      });
    }
    const patch: { enabled?: boolean; schedule?: Window[]; timezone?: string } = {};
    if (b.enabled !== undefined) patch.enabled = b.enabled as boolean;
    if (schedule !== undefined) patch.schedule = schedule;
    if (b.timezone !== undefined) patch.timezone = b.timezone as string;
    const view = await aiControl.update(patch);
    await logAdminAction(kv, { adminId: ctx.admin!.id, action: "ai_settings", detail: patch as Record<string, unknown> });
    hub.sendToAdmins({ t: "ai", ...view });
    return jsonResponse(view);
  });

  router.get("/v1/admin/reports", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const deviceId = ctx.query.get("deviceId");
    if (deviceId !== null && !isUuid(deviceId)) throw badRequest("deviceId");
    const kind = ctx.query.get("kind");
    if (kind !== null && kind !== "error" && kind !== "crash") throw badRequest("kind");
    const limit = intQuery(ctx, "limit", 50, 1, 200);
    const beforeMs = dateTimeQuery(ctx, "before");
    const out = [];
    for await (
      const e of kv.list<{ id: string; deviceId: string; kind: string; message: string; stack: string | null; appVersion: string | null; at: number }>(
        { prefix: ["report"] },
        { reverse: true },
      )
    ) {
      const r = e.value;
      if (beforeMs !== null && r.at >= beforeMs) continue;
      if (deviceId && r.deviceId !== deviceId) continue;
      if (kind && r.kind !== kind) continue;
      const d = await getDevice(kv, r.deviceId);
      const u = d ? (await kv.get<{ publicId: string | null }>(["user", d.userId])).value : null;
      out.push({
        id: r.id,
        deviceId: r.deviceId,
        publicId: u?.publicId || null,
        displayName: d?.displayName || null,
        deviceModel: d?.deviceModel || null,
        kind: r.kind,
        message: r.message,
        stack: r.stack || null,
        appVersion: r.appVersion || null,
        at: iso(r.at),
      });
      if (out.length >= limit) break;
    }
    return jsonResponse(out);
  });

  router.get("/v1/admin/devices/:deviceId/events", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const deviceId = reqUuidParam(ctx, "deviceId");
    await requireDevice(deviceId);
    const out: ReturnType<typeof toDeviceEvent>[] = [];
    for await (const e of kv.list<DeviceEvent>({ prefix: ["event", deviceId] }, { reverse: true, limit: 200 })) out.push(toDeviceEvent(e.value));
    return jsonResponse(out);
  });

  router.patch("/v1/admin/devices/:deviceId/overrides", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const deviceId = reqUuidParam(ctx, "deviceId");
    const d = await requireDevice(deviceId);
    const b = await ctx.json<Record<string, unknown>>();
    const allowedKeys = new Set(["forceLanguage", "disableSearch", "maxMessagesPerDay"]);
    for (const k of Object.keys(b)) {
      if (!allowedKeys.has(k)) throw badRequest(`Неизвестное поле ${k}`);
    }
    if (b.forceLanguage !== undefined && b.forceLanguage !== null && !["ru", "en"].includes(String(b.forceLanguage))) throw badRequest("forceLanguage");
    if (b.disableSearch !== undefined && b.disableSearch !== null && typeof b.disableSearch !== "boolean") throw badRequest("disableSearch");
    if (b.maxMessagesPerDay !== undefined && b.maxMessagesPerDay !== null) {
      const n = b.maxMessagesPerDay;
      if (!Number.isInteger(n) || (n as number) < 1 || (n as number) > 100_000) throw badRequest("maxMessagesPerDay");
    }
    const next: Record<string, unknown> = { ...deviceOverrides(d) };
    for (const [k, v] of Object.entries(b)) {
      if (v === null || v === false) delete next[k];
      else next[k] = v;
    }
    await kv.set(["device", d.id], { ...d, overrides: next });
    hub.sendToDevice(d.id, { t: "overrides", overrides: next });
    await logAdminAction(kv, { adminId: ctx.admin!.id, deviceId: d.id, action: "overrides", detail: b });
    return jsonResponse({ overrides: next });
  });

  // ---------- заметки ----------

  interface NoteRow {
    id: string;
    deviceId: string;
    adminId: string | null;
    text: string;
    createdAt: number;
    updatedAt: number | null;
  }
  async function toNote(r: NoteRow) {
    let adminName: string | null = null;
    if (r.adminId) {
      const a = await getAdmin(kv, r.adminId);
      adminName = a?.name || a?.login || a?.email || null;
    }
    return {
      id: r.id,
      deviceId: r.deviceId,
      adminId: r.adminId || null,
      adminName,
      text: r.text,
      createdAt: iso(r.createdAt),
      updatedAt: iso(r.updatedAt),
    };
  }

  router.get("/v1/admin/devices/:deviceId/notes", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const deviceId = reqUuidParam(ctx, "deviceId");
    await requireDevice(deviceId);
    const out = [];
    for await (const e of kv.list<string>({ prefix: ["noteByDevice", deviceId] }, { reverse: true })) {
      const note = (await kv.get<NoteRow>(["note", e.value])).value;
      if (note) out.push(await toNote(note));
    }
    return jsonResponse(out);
  });

  router.post("/v1/admin/devices/:deviceId/notes", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const deviceId = reqUuidParam(ctx, "deviceId");
    await requireDevice(deviceId);
    const b = await ctx.json<{ text?: unknown }>();
    if (typeof b.text !== "string" || b.text.length < 1 || b.text.length > 4000) throw badRequest("text");
    const text = b.text.trim();
    if (!text) throw badRequest("Пустая заметка");
    const id = crypto.randomUUID();
    const now = nowMs();
    const note: NoteRow = { id, deviceId, adminId: ctx.admin!.id, text, createdAt: now, updatedAt: null };
    await kv.set(["note", id], note);
    await kv.set(["noteByDevice", deviceId, now, id], id);
    return jsonResponse(await toNote(note), 201);
  });

  router.patch("/v1/admin/notes/:noteId", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const noteId = reqUuidParam(ctx, "noteId");
    const b = await ctx.json<{ text?: unknown }>();
    if (typeof b.text !== "string" || b.text.length < 1 || b.text.length > 4000) throw badRequest("text");
    const text = b.text.trim();
    if (!text) throw badRequest("Пустая заметка");
    const note = (await kv.get<NoteRow>(["note", noteId])).value;
    if (!note) throw notFound("Заметка не найдена");
    const updated = { ...note, text, updatedAt: nowMs() };
    await kv.set(["note", noteId], updated);
    return jsonResponse(await toNote(updated));
  });

  router.delete("/v1/admin/notes/:noteId", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const noteId = reqUuidParam(ctx, "noteId");
    const note = (await kv.get<NoteRow>(["note", noteId])).value;
    if (!note) throw notFound("Заметка не найдена");
    await kv.delete(["note", noteId]);
    await kv.delete(["noteByDevice", note.deviceId, note.createdAt, noteId]);
    return jsonResponse({ ok: true });
  });

  router.get("/v1/admin/actions", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const deviceId = ctx.query.get("deviceId");
    if (deviceId !== null && !isUuid(deviceId)) throw badRequest("deviceId");
    const limit = intQuery(ctx, "limit", 50, 1, 200);
    const beforeMs = dateTimeQuery(ctx, "before");
    const out = [];
    for await (const e of kv.list<AdminAction>({ prefix: ["action"] }, { reverse: true })) {
      if (beforeMs !== null && e.value.at >= beforeMs) continue;
      if (deviceId && e.value.deviceId !== deviceId) continue;
      out.push(await toAdminAction(kv, e.value));
      if (out.length >= limit) break;
    }
    return jsonResponse(out);
  });

  void enumQuery;
}
