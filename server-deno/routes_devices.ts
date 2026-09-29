// Эндпоинты устройства: регистрация, профиль, статистика, отчёты об ошибках.
import { badRequest, blocked, rateLimited } from "./errors.ts";
import { unblockIfExpired } from "./auth.ts";
import { type Device, getDevice, getDeviceByInstall, iso, type Kv, listDevices, nowMs, saveDevice } from "./kv.ts";
import { recordDeviceEvent } from "./device-events.ts";
import { ensurePublicId } from "./public-id.ts";
import { counterAdd, nextSeq } from "./kv.ts";
import { guard, type AppCtx } from "./app.ts";
import { jsonResponse, type ReqCtx, Router } from "./http.ts";

export const deviceOverrides = (d: Device | null): Record<string, unknown> => {
  const o = d?.overrides;
  return o && typeof o === "object" && !Array.isArray(o) ? o : {};
};

const PLATFORMS = new Set(["android", "ios"]);

function checkStr(v: unknown, max: number, name: string, { minLength = 0, pattern }: { minLength?: number; pattern?: RegExp } = {}): void {
  if (v === undefined || v === null) return;
  if (typeof v !== "string" || v.length > max || v.length < minLength) throw badRequest(`Некорректное поле ${name}`);
  if (pattern && !pattern.test(v)) throw badRequest(`Некорректное поле ${name}`);
}

/** Проверяет и нормализует поля профиля из тела запроса. */
function validateProfile(b: Record<string, unknown>) {
  if (b.platform !== undefined && !PLATFORMS.has(String(b.platform))) throw badRequest("platform: android|ios");
  checkStr(b.deviceModel, 200, "deviceModel");
  checkStr(b.deviceName, 200, "deviceName");
  checkStr(b.osVersion, 50, "osVersion");
  checkStr(b.appVersion, 50, "appVersion");
  checkStr(b.displayName, 100, "displayName");
  if (b.birthday !== undefined && b.birthday !== null) checkStr(b.birthday, 10, "birthday", { pattern: /^\d{4}-\d{2}-\d{2}$/ });
  checkStr(b.language, 16, "language", { pattern: /^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})?$/ });
  if (b.licenseAcceptedAt !== undefined && b.licenseAcceptedAt !== null) {
    if (typeof b.licenseAcceptedAt !== "string" || Number.isNaN(Date.parse(b.licenseAcceptedAt))) throw badRequest("licenseAcceptedAt");
  }
  if (b.pushToken !== undefined && b.pushToken !== null) checkStr(b.pushToken, 4096, "pushToken");
}

const PROFILE_FIELDS: (keyof Device)[] = [
  "platform",
  "deviceModel",
  "deviceName",
  "osVersion",
  "appVersion",
  "displayName",
  "birthday",
  "language",
  "pushToken",
];

/** Обновляет присланные поля профиля; смена appVersion пишет событие update. */
async function updateProfile(kv: Kv, device: Device, body: Record<string, unknown>, extra: Partial<Device> = {}): Promise<Device> {
  const prevVersion = device.appVersion;
  const next: Device = { ...device, ...extra };
  const map: Record<string, keyof Device> = {
    platform: "platform",
    deviceModel: "deviceModel",
    deviceName: "deviceName",
    osVersion: "osVersion",
    appVersion: "appVersion",
    displayName: "displayName",
    birthday: "birthday",
    language: "language",
    pushToken: "pushToken",
  };
  void PROFILE_FIELDS;
  for (const [field, col] of Object.entries(map)) {
    if (body[field] === undefined) continue;
    // deno-lint-ignore no-explicit-any
    (next as any)[col] = body[field];
  }
  if (body.licenseAcceptedAt !== undefined) {
    next.licenseAcceptedAt = body.licenseAcceptedAt ? Date.parse(String(body.licenseAcceptedAt)) : null;
  }
  next.updatedAt = nowMs();
  await saveDevice(kv, next);
  // Один push-токен — одно устройство.
  if (body.pushToken) {
    for (const d of await listDevices(kv)) {
      if (d.id !== next.id && d.pushToken === body.pushToken) await kv.set(["device", d.id], { ...d, pushToken: null });
    }
  }
  if (prevVersion && body.appVersion && prevVersion !== body.appVersion) {
    await recordDeviceEvent(kv, next.id, "update", { from: prevVersion, to: String(body.appVersion) });
  }
  return next;
}

export function registerDeviceRoutes(app: AppCtx, router: Router) {
  const { kv, auth, chats, limits } = app;

  router.post("/v1/devices/register", async (ctx: ReqCtx) => {
    if (!limits.register.take(ctx.ip)) throw rateLimited();
    const b = await ctx.json();
    if (typeof b.installId !== "string" || b.installId.length < 8 || b.installId.length > 128) throw badRequest("installId: 8..128 символов");
    if (!PLATFORMS.has(String(b.platform))) throw badRequest("platform: android|ios");
    validateProfile(b);

    let device = await getDeviceByInstall(kv, b.installId);
    let created = false;
    if (!device) {
      const now = nowMs();
      const userId = crypto.randomUUID();
      const deviceId = crypto.randomUUID();
      const res = await kv.atomic()
        .check({ key: ["deviceByInstall", b.installId], versionstamp: null })
        .set(["user", userId], { id: userId, createdAt: now, publicId: null })
        .set(["device", deviceId], {
          id: deviceId,
          userId,
          installId: b.installId,
          platform: String(b.platform),
          deviceModel: null,
          deviceName: null,
          osVersion: null,
          appVersion: null,
          displayName: null,
          birthday: null,
          language: null,
          licenseAcceptedAt: null,
          pushToken: null,
          blocked: false,
          blockReason: null,
          blockedUntil: null,
          overrides: null,
          messagesSent: 0,
          secondsInApp: 0,
          registerCount: 0,
          installedAt: now,
          updatedAt: now,
          lastSeenAt: null,
        } as Device)
        .set(["deviceByInstall", b.installId], deviceId)
        .commit();
      if (res.ok) {
        await counterAdd(kv, "installs", 1);
        created = true;
        device = await getDevice(kv, deviceId);
      } else {
        device = await getDeviceByInstall(kv, b.installId);
      }
    }
    device = await unblockIfExpired(kv, device!);
    if (device.blocked) throw blocked(device.blockReason, device.blockedUntil);

    device = await updateProfile(kv, device, b, { registerCount: device.registerCount + 1 });
    if (created) await recordDeviceEvent(kv, device.id, "install", { to: b.appVersion ? String(b.appVersion) : null });
    const chat = (await chats.getDeviceChat(device.id)) || (await chats.createChatForDevice(device.id));
    const token = await auth.issueDeviceToken(device.id);
    const publicId = await ensurePublicId(kv, device.userId);
    return jsonResponse({
      deviceId: device.id,
      token,
      userId: device.userId,
      adminChatId: chat.id,
      publicId,
      overrides: deviceOverrides(device),
    });
  });

  router.patch("/v1/devices/me", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["device"]);
    const b = await ctx.json();
    validateProfile(b);
    await updateProfile(kv, ctx.device!, b);
    const publicId = await ensurePublicId(kv, ctx.device!.userId);
    return jsonResponse({ ok: true, publicId, overrides: deviceOverrides(ctx.device!) });
  });

  router.post("/v1/devices/me/stats", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["device"]);
    const b = await ctx.json();
    const ms = b.messagesSent, sec = b.secondsInApp;
    for (const [v, name] of [[ms, "messagesSent"], [sec, "secondsInApp"]] as [unknown, string][]) {
      if (v !== undefined && (typeof v !== "number" || !Number.isInteger(v) || v < 0 || v > 1e12)) throw badRequest(name);
    }
    const d = ctx.device!;
    await kv.set(["device", d.id], {
      ...d,
      messagesSent: Math.max(d.messagesSent, Number(ms) || 0),
      secondsInApp: Math.max(d.secondsInApp, Number(sec) || 0),
    });
    return jsonResponse({ ok: true });
  });

  router.post("/v1/devices/me/report", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["device"]);
    const b = await ctx.json();
    if (b.kind !== "error" && b.kind !== "crash") throw badRequest("kind: error|crash");
    if (typeof b.message !== "string" || b.message.length < 1 || b.message.length > 20_000) throw badRequest("message");
    if (b.stack !== undefined && b.stack !== null && (typeof b.stack !== "string" || b.stack.length > 200_000)) throw badRequest("stack");
    if (!limits.report.take(ctx.device!.id)) throw rateLimited("Слишком много отчётов, попробуйте позже");
    const at = b.at && Date.parse(String(b.at)) <= Date.now() ? Date.parse(String(b.at)) : Date.now();
    const id = crypto.randomUUID();
    const report = {
      id,
      deviceId: ctx.device!.id,
      kind: b.kind,
      message: String(b.message).slice(0, 2000),
      stack: b.stack ? String(b.stack).slice(0, 16_000) : null,
      appVersion: b.appVersion ? String(b.appVersion) : ctx.device!.appVersion || null,
      at,
    };
    await kv.set(["report", nextSeq(), id], report);
    return jsonResponse({ ok: true, id });
  });

  void iso;
}
