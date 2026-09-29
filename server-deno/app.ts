// Сборка приложения: KV → сервисы → маршруты → таймеры. buildApp не слушает порт (это делает main.ts / тест-хелпер).
import type { Config } from "./config.ts";
import { backfillPublicIds } from "./public-id.ts";
import { type Auth, createAuth, type Principal } from "./auth.ts";
import { blocked, forbidden, unauthorized } from "./errors.ts";
import { getSetting, type Kv, openKv } from "./kv.ts";
import { Hub } from "./hub.ts";
import { createMediaStore, type MediaStore } from "./media.ts";
import { createPush, type Push, type Transport } from "./push.ts";
import { type ChatService, createChatService } from "./chat-service.ts";
import { createGroupAi, type GroupAi } from "./ai.ts";
import { RateLimiter } from "./ratelimit.ts";
import { createUsage, dayKey, LiveMetrics, type Usage } from "./metrics.ts";
import { type AiControl, createAiControl } from "./ai-control.ts";
import { logAdminAction } from "./device-events.ts";
import { type Handler, jsonResponse, ReqCtx, Router } from "./http.ts";
import { registerDeviceRoutes } from "./routes_devices.ts";
import { registerChatRoutes } from "./routes_chats.ts";
import { registerMediaRoutes } from "./routes_media.ts";
import { registerNotificationRoutes } from "./routes_notifications.ts";
import { registerAiProxyRoutes } from "./routes_ai.ts";
import { registerAdminRoutes } from "./routes_admin.ts";
import { registerAdminInsightRoutes } from "./routes_admin_insights.ts";
import { handleWs } from "./ws.ts";

export type VerifyGoogle = (idToken: string, audience: string[]) => Promise<{ email?: string; email_verified?: boolean; name?: string }>;

export interface AppCtx {
  config: Config;
  kv: Kv;
  auth: Auth;
  hub: Hub;
  media: MediaStore;
  push: Push;
  chats: ChatService;
  groupAi: GroupAi;
  metrics: LiveMetrics;
  usage: Usage;
  aiControl: AiControl;
  limits: { ai: RateLimiter; login: RateLimiter; register: RateLimiter; report: RateLimiter };
  verifyGoogleIdToken: VerifyGoogle;
  metricsSnapshot: () => Promise<Record<string, unknown>>;
}

export interface BuildOverrides {
  kv?: Kv;
  pushTransport?: Transport | null;
  verifyGoogleIdToken?: VerifyGoogle;
  fetchImpl?: typeof fetch;
}

function tokenFromRequest(ctx: ReqCtx, allowQuery = false): string | null {
  const h = ctx.header("authorization");
  if (h && /^Bearer\s+/i.test(h)) return h.replace(/^Bearer\s+/i, "").trim();
  if (allowQuery) return ctx.query.get("token");
  return null;
}

/** preHandler-аналог: проверяет токен, наполняет ctx.principal/device/admin. */
export async function guard(
  app: AppCtx,
  ctx: ReqCtx,
  accept: ("device" | "admin")[],
  { allowQuery = false } = {},
): Promise<Principal> {
  const who = await app.auth.resolve(tokenFromRequest(ctx, allowQuery));
  if (!who) throw unauthorized();
  if (!accept.includes(who.kind)) throw forbidden();
  if (who.kind === "device") {
    if (who.device.blocked) throw blocked(who.device.blockReason, who.device.blockedUntil);
    ctx.device = who.device;
  } else {
    ctx.admin = who.admin;
  }
  ctx.principal = who;
  return who;
}

function createMetricsSnapshot(app: Pick<AppCtx, "kv" | "hub" | "metrics" | "usage" | "aiControl" | "config">) {
  return async function snapshot() {
    const startOfDay = new Date();
    startOfDay.setUTCHours(0, 0, 0, 0);
    const { online, inBackground } = app.hub.counts();
    const live = app.metrics.snapshot();
    const tokens = await app.usage.totals();
    let reportsToday = 0;
    for await (const e of app.kv.list<{ at: number }>({ prefix: ["report"] })) if (e.value.at >= startOfDay.getTime()) reportsToday++;
    const ai = app.aiControl.view();
    return {
      at: new Date().toISOString(),
      online,
      inBackground,
      rps: live.rps,
      requests1m: live.requests1m,
      errors1m: live.errors1m,
      tokensToday: tokens.today.total,
      tokensTotal: tokens.total.total,
      tokens,
      aiRequestsToday: tokens.today.requests,
      aiErrorsToday: tokens.today.errors,
      reportsToday,
      errorsToday: tokens.today.errors + reportsToday,
      aiLatencyMs: live.aiLatencyMs,
      apiLatencyMs: live.apiLatencyMs,
      model: {
        enabled: ai.effective && ai.configured,
        switchedOn: ai.enabled,
        scheduled: ai.schedule.length > 0,
        configured: ai.configured,
        name: app.config.aiGroupModel,
      },
    };
  };
}

export interface BuiltApp {
  ctx: AppCtx;
  handler: (req: Request, info?: Deno.ServeHandlerInfo) => Promise<Response>;
  stop: () => Promise<void>;
}

export async function buildApp(config: Config, overrides: BuildOverrides = {}): Promise<BuiltApp> {
  const kv = overrides.kv || (await openKv(config));
  await backfillPublicIds(kv);

  const auth = createAuth(kv);
  const hub = new Hub({
    config,
    persistDeviceSeen: (id, at) => {
      (async () => {
        const r = await kv.get<Record<string, unknown>>(["device", id]);
        if (r.value) await kv.set(["device", id], { ...r.value, lastSeenAt: at.getTime() });
      })().catch(() => {});
    },
    persistAdminSeen: (id, at) => {
      (async () => {
        const r = await kv.get<Record<string, unknown>>(["admin", id]);
        if (r.value) await kv.set(["admin", id], { ...r.value, lastSeenAt: at.getTime() });
      })().catch(() => {});
    },
  });
  const media = createMediaStore({ kv, maxUploadBytes: config.maxUploadBytes });
  const push = createPush({ kv, hub, config, transport: overrides.pushTransport });
  const chats = createChatService({ kv, hub, push, media });
  const metrics = new LiveMetrics();
  const usage = createUsage(kv);
  const aiControl = createAiControl({ kv, config });
  await aiControl.load();
  const groupAi = createGroupAi({ kv, hub, chats, config, aiControl, metrics, usage, fetchImpl: overrides.fetchImpl });
  chats.listeners.push(groupAi.onMessage);

  const verifyGoogleIdToken: VerifyGoogle = overrides.verifyGoogleIdToken || (async () => {
    throw new Error("google verification not configured");
  });

  const limits = {
    ai: new RateLimiter({ max: config.aiRateMax, windowMs: config.aiRateWindowMs }),
    login: new RateLimiter({ max: config.loginRateMax, windowMs: config.loginRateWindowMs }),
    register: new RateLimiter({ max: config.registerRateMax, windowMs: config.registerRateWindowMs }),
    report: new RateLimiter({ max: config.reportRateMax, windowMs: config.reportRateWindowMs }),
  };

  const ctx = {} as AppCtx;
  Object.assign(ctx, {
    config,
    kv,
    auth,
    hub,
    media,
    push,
    chats,
    groupAi,
    metrics,
    usage,
    aiControl,
    limits,
    verifyGoogleIdToken,
  });
  ctx.metricsSnapshot = createMetricsSnapshot(ctx);

  // ---------- маршруты ----------
  const router = new Router();
  registerDeviceRoutes(ctx, router);
  registerChatRoutes(ctx, router, "/v1/chats", "user");
  registerChatRoutes(ctx, router, "/v1/admin/chats", "admin");
  registerMediaRoutes(ctx, router);
  registerNotificationRoutes(ctx, router);
  registerAiProxyRoutes(ctx, router);
  registerAdminRoutes(ctx, router);
  registerAdminInsightRoutes(ctx, router);

  const health: Handler = () => jsonResponse({ ok: true, ai: Boolean(config.deepseekApiKey), aiEnabled: aiControl.isEnabled() });
  router.get("/health", health);

  // ---------- таймеры ----------
  let pushing = false;
  const metricsTimer = setInterval(async () => {
    if (pushing || hub.admin.sockets.size === 0) return;
    pushing = true;
    try {
      hub.sendToAdmins({ t: "metrics", ...(await ctx.metricsSnapshot()) });
    } catch { /* ignore */ } finally {
      pushing = false;
    }
  }, Math.max(500, config.metricsPushMs));
  Deno.unrefTimer?.(metricsTimer);

  const blockTimer = setInterval(async () => {
    try {
      const now = Date.now();
      for await (const e of kv.list<{ id: string; blocked: boolean; blockedUntil: number | null }>({ prefix: ["device"] })) {
        const d = e.value;
        if (d.blocked && d.blockedUntil && d.blockedUntil <= now) {
          const fresh = await kv.get<Record<string, unknown>>(["device", d.id]);
          if (fresh.value && (fresh.value as { blocked: boolean }).blocked) {
            await kv.set(["device", d.id], { ...fresh.value, blocked: false, blockReason: null, blockedUntil: null });
            await logAdminAction(kv, { deviceId: d.id, action: "unblock_auto" });
          }
        }
      }
    } catch { /* ignore */ }
  }, Math.max(1000, config.blockSweepMs));
  Deno.unrefTimer?.(blockTimer);

  const handler = async (req: Request, info?: Deno.ServeHandlerInfo): Promise<Response> => {
    const ctxReq = new ReqCtx(req, info);
    const path = ctxReq.url.pathname;

    // WebSocket upgrade
    if (path === "/v1/ws" && req.headers.get("upgrade")?.toLowerCase() === "websocket") {
      return await handleWs(ctx, ctxReq);
    }

    if (path !== "/v1/ws" && !path.startsWith("/v1/ai/")) metrics.recordRequest();

    const started = Date.now();
    const match = router.match(ctxReq.method, path);
    let res: Response;
    if (!match) {
      res = jsonResponse({ error: "not_found", message: "Не найдено" }, 404);
    } else {
      ctxReq.params = match.params;
      try {
        res = await match.handler(ctxReq);
      } catch (err) {
        const { errorResponse } = await import("./http.ts");
        res = errorResponse(err);
      }
    }
    if (path !== "/v1/ws" && !path.startsWith("/v1/ai/")) metrics.recordResponse(res.status, Date.now() - started);
    return res;
  };

  const stop = async () => {
    clearInterval(metricsTimer);
    clearInterval(blockTimer);
    groupAi.stop();
    hub.stop();
    for (const l of Object.values(limits)) l.stop();
    if (!overrides.kv) kv.close();
  };

  return { ctx, handler, stop };
}

export { dayKey, getSetting };
