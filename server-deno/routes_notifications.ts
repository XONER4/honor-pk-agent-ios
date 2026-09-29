// Уведомления устройства. Рассылка создаётся админом (routes_admin.ts); у каждого устройства своя копия.
import { badRequest } from "./errors.ts";
import { iso, type Kv } from "./kv.ts";
import { guard, type AppCtx } from "./app.ts";
import { dateTimeQuery, isUuid, jsonResponse, type ReqCtx, Router } from "./http.ts";

export interface NotifRow {
  id: string;
  deviceId: string;
  title: string;
  body: string;
  kind: string;
  chatId: string | null;
  createdAt: number;
  readAt: number | null;
}

export const toNotification = (r: NotifRow) => ({
  id: r.id,
  title: r.title,
  body: r.body,
  createdAt: iso(r.createdAt),
  read: !!r.readAt,
  chatId: r.chatId || null,
  kind: r.kind,
});

export function registerNotificationRoutes(app: AppCtx, router: Router) {
  const { kv } = app as { kv: Kv } & AppCtx;

  router.get("/v1/notifications", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["device"]);
    const afterMs = dateTimeQuery(ctx, "after");
    const rows: NotifRow[] = [];
    for await (const e of kv.list<NotifRow>({ prefix: ["notif", ctx.device!.id] })) {
      if (afterMs !== null && e.value.createdAt <= afterMs) continue;
      rows.push(e.value);
    }
    rows.sort((a, b) => a.createdAt - b.createdAt);
    return jsonResponse(rows.slice(-200).map(toNotification));
  });

  router.post("/v1/notifications/read", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["device"]);
    const b = await ctx.json<{ ids?: unknown }>();
    if (!Array.isArray(b.ids) || b.ids.length > 500 || !b.ids.every((x) => isUuid(x))) throw badRequest("ids");
    const ids = [...new Set(b.ids as string[])];
    const now = Date.now();
    for (const id of ids) {
      const ref = (await kv.get<{ deviceId: string; createdAt: number }>(["notifById", id])).value;
      if (!ref || ref.deviceId !== ctx.device!.id) continue;
      const key = ["notif", ref.deviceId, ref.createdAt, id];
      const cur = (await kv.get<NotifRow>(key)).value;
      if (cur && !cur.readAt) await kv.set(key, { ...cur, readAt: now });
    }
    return jsonResponse({ ok: true });
  });
}
