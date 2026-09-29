// Админские эндпоинты: аккаунт (setup/login), обзор, устройства, блокировка, уведомления.
import { blockExpired, safeEqual } from "./auth.ts";
import { ApiError, badRequest, conflict, notFound, rateLimited, unauthorized } from "./errors.ts";
import {
  type Admin,
  type Device,
  counterGet,
  getAdmin,
  getAdminByEmail,
  getAdminByLogin,
  getDevice,
  iso,
  type Kv,
  listDevices,
  listUsers,
  nowMs,
  saveAdmin,
} from "./kv.ts";
import { hasAdminAccount } from "./kv.ts";
import { hashPassword, LOGIN_PATTERN, normalizeLogin, verifyPassword } from "./passwords.ts";
import { logAdminAction } from "./device-events.ts";
import { toNotification } from "./routes_notifications.ts";
import { deviceOverrides } from "./routes_devices.ts";
import { guard, type AppCtx } from "./app.ts";
import { enumQuery, isUuid, jsonResponse, type ReqCtx, reqUuidParam, Router } from "./http.ts";

const PRESENCE_ORDER: Record<string, number> = { foreground: 0, background: 1, offline: 2 };
const LOGIN_RE = new RegExp(LOGIN_PATTERN);

export function registerAdminRoutes(app: AppCtx, router: Router) {
  const { kv, auth, hub, push, config, limits, usage, verifyGoogleIdToken } = app;

  const loginResponse = (admin: Admin, token: string) => ({
    token,
    email: admin.email,
    name: admin.name || admin.login || "Администратор",
    login: admin.login || null,
  });

  router.get("/v1/admin/setup-status", async () => jsonResponse({ hasAccount: await hasAdminAccount(kv) }));

  router.post("/v1/admin/setup", async (ctx: ReqCtx) => {
    if (limits.login.blocked(ctx.ip)) throw rateLimited("Слишком много попыток входа, попробуйте позже");
    const b = await ctx.json<{ login?: unknown; password?: unknown }>();
    if (typeof b.login !== "string" || typeof b.password !== "string") throw badRequest("Введите логин и пароль");
    if (config.adminKey) {
      const keyHdr = ctx.header("x-admin-key");
      if (typeof keyHdr !== "string" || !(await safeEqual(keyHdr, config.adminKey))) {
        limits.login.record(ctx.ip);
        throw unauthorized("Неверный ключ администратора");
      }
    }
    const login = normalizeLogin(b.login);
    const password = b.password;
    if (!LOGIN_RE.test(login)) throw badRequest("Логин: от 3 до 64 символов — латинские буквы, цифры и . _ @ -");
    if (password.length < 8) throw badRequest("Пароль должен быть не короче 8 символов");
    if (await hasAdminAccount(kv)) throw conflict("Аккаунт администратора уже создан — войдите по логину и паролю");

    const hash = await hashPassword(password);
    const now = nowMs();
    const existing = await getAdminByEmail(kv, login);
    let admin: Admin;
    if (existing) {
      admin = { ...existing, login, passwordHash: hash, lastLoginAt: now };
    } else {
      admin = { id: crypto.randomUUID(), email: login, name: login, login, passwordHash: hash, createdAt: now, lastLoginAt: now, lastSeenAt: null };
    }
    await saveAdmin(kv, admin);
    await logAdminAction(kv, { adminId: admin.id, action: "admin_setup", detail: { login } });
    const token = await auth.issueAdminToken(admin.id, config.adminTokenTtlMs);
    return jsonResponse(loginResponse(admin, token));
  });

  router.post("/v1/admin/login", async (ctx: ReqCtx) => {
    if (limits.login.blocked(ctx.ip)) throw rateLimited("Слишком много попыток входа, попробуйте позже");
    const b = await ctx.json<{ login?: unknown; password?: unknown; googleIdToken?: unknown; adminKey?: unknown }>();
    const fail = (msg = "Неверные данные для входа") => {
      limits.login.record(ctx.ip);
      return unauthorized(msg);
    };
    const now = nowMs();

    if (b.login !== undefined || b.password !== undefined) {
      if (!b.login || !b.password || typeof b.login !== "string" || typeof b.password !== "string") throw badRequest("Введите логин и пароль");
      const row = await getAdminByLogin(kv, normalizeLogin(b.login));
      const ok = await verifyPassword(b.password, row?.passwordHash || null);
      if (!ok || !row) throw fail("Неверный логин или пароль");
      await saveAdmin(kv, { ...row, lastLoginAt: now });
      const token = await auth.issueAdminToken(row.id, config.adminTokenTtlMs);
      return jsonResponse(loginResponse(row, token));
    }

    let identity: { email: string; name: string } | null = null;
    if (b.googleIdToken !== undefined) {
      if (typeof b.googleIdToken !== "string") throw badRequest("googleIdToken");
      if (!config.googleClientIds.length) throw new ApiError(503, "google_not_configured", "Вход через Google не настроен");
      try {
        const p = await verifyGoogleIdToken(b.googleIdToken, config.googleClientIds);
        const email = String(p?.email || "").toLowerCase();
        if (email && p.email_verified !== false && config.adminEmails.includes(email)) identity = { email, name: p.name || email };
      } catch {
        identity = null;
      }
    } else if (b.adminKey !== undefined) {
      if (typeof b.adminKey === "string" && config.adminKey && (await safeEqual(b.adminKey, config.adminKey))) {
        identity = { email: config.adminEmails[0] || "admin", name: "Администратор" };
      }
    } else {
      throw badRequest("Нужны логин и пароль, googleIdToken или adminKey");
    }

    if (!identity) throw fail();

    let admin = await getAdminByEmail(kv, identity.email);
    if (!admin) {
      admin = { id: crypto.randomUUID(), email: identity.email, name: identity.name, login: null, passwordHash: null, createdAt: now, lastLoginAt: now, lastSeenAt: null };
    }
    const name = b.googleIdToken !== undefined ? identity.name : admin.name || identity.name;
    admin = { ...admin, name, lastLoginAt: now };
    await saveAdmin(kv, admin);
    const token = await auth.issueAdminToken(admin.id, config.adminTokenTtlMs);
    return jsonResponse({ token, email: admin.email, name, login: admin.login || null });
  });

  // ---------- обзор ----------

  async function inactiveCount(): Promise<number> {
    const cutoff = Date.now() - config.inactiveDays * 24 * 3600 * 1000;
    let c = 0;
    for (const d of await listDevices(kv)) {
      if (hub.deviceState(d.id) !== "offline") continue;
      const last = d.lastSeenAt || d.updatedAt || d.installedAt;
      if (last && last < cutoff) c += 1;
    }
    return c;
  }

  router.get("/v1/admin/overview", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const startOfDay = new Date();
    startOfDay.setUTCHours(0, 0, 0, 0);
    const users = (await listUsers(kv)).length;
    const installs = await counterGet(kv, "installs");
    const now = Date.now();
    let blockedCount = 0;
    for (const d of await listDevices(kv)) if (d.blocked && (d.blockedUntil == null || d.blockedUntil > now)) blockedCount += 1;
    let messagesToday = 0;
    for await (const e of kv.list<{ createdAt: number }>({ prefix: ["msg"] })) if (e.value.createdAt >= startOfDay.getTime()) messagesToday += 1;
    const { online, inBackground } = hub.counts();
    return jsonResponse({
      users,
      installs,
      online,
      inBackground,
      blocked: blockedCount,
      messagesToday,
      updates: await counterGet(kv, "updates"),
      uninstalls: await counterGet(kv, "uninstalls"),
      inactive: await inactiveCount(),
      inactiveDays: config.inactiveDays,
    });
  });

  // ---------- устройства ----------

  async function listExtras() {
    const tokens = await usage.totalsByDevice();
    const unread = new Map<string, number>();
    const reports = new Map<string, number>();
    for await (const e of kv.list<{ deviceId: string }>({ prefix: ["report"] })) {
      reports.set(e.value.deviceId, (reports.get(e.value.deviceId) || 0) + 1);
    }
    // unread считаем по чатам
    for await (const e of kv.list<{ id: string; deviceId: string }>({ prefix: ["chat"] })) {
      unread.set(e.value.deviceId, await app.chats.unreadCount(e.value as never, "admin"));
    }
    return { tokens, unread, reports };
  }

  async function chatIdForDevice(deviceId: string): Promise<string | null> {
    return (await kv.get<string>(["chatByDevice", deviceId])).value;
  }

  async function summary(d: Device, extras: { tokens: Map<string, number>; unread: Map<string, number>; reports: Map<string, number> }, publicId: string | null, chatId: string | null) {
    const p = hub.devicePresence(d.id, d.lastSeenAt);
    const isBlocked = !!d.blocked && !blockExpired(d);
    return {
      deviceId: d.id,
      userId: d.userId,
      publicId: publicId || null,
      displayName: d.displayName || null,
      deviceModel: d.deviceModel || null,
      deviceName: d.deviceName || null,
      platform: d.platform,
      appVersion: d.appVersion || null,
      installedAt: iso(d.installedAt),
      lastSeen: p.lastSeen,
      presence: p.state,
      typingIn: p.typingIn,
      blocked: isBlocked,
      blockedUntil: isBlocked ? iso(d.blockedUntil) : null,
      messagesSent: Number(d.messagesSent) || 0,
      secondsInApp: Number(d.secondsInApp) || 0,
      unreadForAdmin: extras.unread.get(d.id) || 0,
      adminChatId: chatId || null,
      aiTokens: extras.tokens.get(d.id) || 0,
      reports: extras.reports.get(d.id) || 0,
    };
  }

  async function publicIdOf(userId: string): Promise<string | null> {
    const u = (await kv.get<{ publicId: string | null }>(["user", userId])).value;
    return u?.publicId || null;
  }

  router.get("/v1/admin/devices", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const status = enumQuery(ctx, "status", ["all", "online", "blocked"], "all");
    const sort = enumQuery(ctx, "sort", ["activity", "tokens"], "activity");
    const q = String(ctx.query.get("query") || "").trim().toLowerCase();
    const extras = await listExtras();
    const devices = await listDevices(kv);
    let list: Awaited<ReturnType<typeof summary>>[] = [];
    for (const d of devices) {
      list.push(await summary(d, extras, await publicIdOf(d.userId), await chatIdForDevice(d.id)));
    }
    if (q) {
      const idQuery = q.replace(/^#/, "");
      list = list.filter((s) =>
        (s.publicId && /^\d+$/.test(idQuery) && s.publicId.includes(idQuery)) ||
        [s.displayName, s.deviceModel, s.deviceName, s.deviceId, s.userId, s.appVersion].some((v) => v && String(v).toLowerCase().includes(q))
      );
    }
    if (status === "online") list = list.filter((s) => s.presence !== "offline");
    if (status === "blocked") list = list.filter((s) => s.blocked);
    if (sort === "tokens") {
      list.sort((a, b) => (b.aiTokens - a.aiTokens) || (PRESENCE_ORDER[a.presence] - PRESENCE_ORDER[b.presence]));
    } else {
      list.sort((a, b) =>
        (PRESENCE_ORDER[a.presence] - PRESENCE_ORDER[b.presence]) ||
        (new Date(b.lastSeen || b.installedAt || 0).getTime() - new Date(a.lastSeen || a.installedAt || 0).getTime())
      );
    }
    return jsonResponse(list);
  });

  async function deviceDetail(deviceId: string) {
    const d = await getDevice(kv, deviceId);
    if (!d) throw notFound("Устройство не найдено");
    const extras = await listExtras();
    const same = (await listDevices(kv)).filter((x) => x.platform === d.platform && x.deviceModel === d.deviceModel && x.deviceName === d.deviceName).length || 1;
    const s = await summary(d, extras, await publicIdOf(d.userId), await chatIdForDevice(d.id));
    return {
      ...s,
      birthday: d.birthday || null,
      language: d.language || null,
      osVersion: d.osVersion || null,
      licenseAcceptedAt: iso(d.licenseAcceptedAt),
      blockReason: s.blocked ? d.blockReason || null : null,
      installs: same,
      overrides: deviceOverrides(d),
      usage: await usage.forDevice(d.id),
    };
  }

  router.get("/v1/admin/devices/:deviceId", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    return jsonResponse(await deviceDetail(reqUuidParam(ctx, "deviceId")));
  });

  router.post("/v1/admin/devices/:deviceId/block", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const deviceId = reqUuidParam(ctx, "deviceId");
    const b = await ctx.json<{ blocked?: unknown; reason?: unknown; until?: unknown }>();
    if (typeof b.blocked !== "boolean") throw badRequest("blocked");
    if (b.reason !== undefined && b.reason !== null && (typeof b.reason !== "string" || b.reason.length > 500)) throw badRequest("reason");
    const untilMs = b.blocked && b.until ? Date.parse(String(b.until)) : null;
    if (untilMs !== null && Number.isNaN(untilMs)) throw badRequest("until");
    if (untilMs !== null && untilMs <= Date.now()) throw badRequest("Срок блокировки должен быть в будущем");
    const d = await getDevice(kv, deviceId);
    if (!d) throw notFound("Устройство не найдено");
    await kv.set(["device", d.id], {
      ...d,
      blocked: b.blocked,
      blockReason: b.blocked ? (b.reason as string) || null : null,
      blockedUntil: b.blocked ? untilMs : null,
      updatedAt: nowMs(),
    });
    if (b.blocked) hub.kickDevice(d.id, (b.reason as string) || "", untilMs);
    await logAdminAction(kv, {
      adminId: ctx.admin!.id,
      deviceId: d.id,
      action: b.blocked ? "block" : "unblock",
      detail: b.blocked ? { reason: (b.reason as string) || null, until: iso(untilMs) } : null,
    });
    return jsonResponse(await deviceDetail(d.id));
  });

  router.post("/v1/admin/notifications", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["admin"]);
    const b = await ctx.json<{ deviceId?: unknown; title?: unknown; body?: unknown }>();
    if (typeof b.title !== "string" || b.title.length < 1 || b.title.length > 200) throw badRequest("title");
    if (b.body !== undefined && (typeof b.body !== "string" || b.body.length > 4000)) throw badRequest("body");
    if (b.deviceId !== undefined && b.deviceId !== null && !isUuid(b.deviceId)) throw badRequest("deviceId");
    const deviceId = (b.deviceId as string) || null;
    const body = (b.body as string) || "";
    let targets: Device[];
    if (deviceId) {
      const d = await getDevice(kv, deviceId);
      if (!d) throw notFound("Устройство не найдено");
      targets = [d];
    } else {
      targets = (await listDevices(kv)).filter((d) => !d.blocked);
    }
    const now = nowMs();
    for (const t of targets) {
      const id = crypto.randomUUID();
      const row = { id, deviceId: t.id, title: b.title as string, body, kind: "admin", chatId: null, createdAt: now, readAt: null };
      await kv.set(["notif", t.id, now, id], row);
      await kv.set(["notifById", id], { deviceId: t.id, createdAt: now });
      hub.sendToDevice(t.id, { t: "notification", notification: toNotification(row) });
      push.notifyDevice(t.id, { type: "notification", chatId: null, title: b.title as string, body });
    }
    await logAdminAction(kv, {
      adminId: ctx.admin!.id,
      deviceId,
      action: deviceId ? "notify" : "broadcast",
      detail: { title: b.title as string, count: targets.length },
    });
    return jsonResponse({ ok: true, count: targets.length });
  });

  void getAdmin;
}
