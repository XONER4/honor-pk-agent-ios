// AI-прокси: прячет ключ DeepSeek. Тело пересылается как есть (+ stream_options.include_usage для потоков),
// ответ апстрима (SSE/JSON) идёт клиенту без буферизации; по дороге читается usage (токены).
import { ApiError, badRequest, rateLimited } from "./errors.ts";
import { NO_DEVICE, UsageSniffer } from "./metrics.ts";
import { guard, type AppCtx } from "./app.ts";
import { jsonResponse, type ReqCtx, Router, SECURITY_HEADERS } from "./http.ts";

export const aiDisabledError = () => new ApiError(503, "ai_disabled", "ИИ временно отключён администратором.", { code: "ai_disabled" });

export function registerAiProxyRoutes(app: AppCtx, router: Router) {
  const { config, limits, metrics, usage, aiControl } = app;
  const allowed = new Set(config.aiAllowedModels);
  const fetchImpl = fetch;

  router.post("/v1/ai/chat/completions", async (ctx: ReqCtx) => {
    await guard(app, ctx, ["device", "admin"]);
    const body = await ctx.json<Record<string, unknown>>();
    if (typeof body.model !== "string" || body.model.length > 100) throw badRequest("model");
    if (!Array.isArray(body.messages) || body.messages.length < 1 || body.messages.length > 1000) throw badRequest("messages");
    if (!aiControl.isEnabled()) throw aiDisabledError();
    if (!config.deepseekApiKey) throw new ApiError(503, "ai_unavailable", "ИИ временно недоступен");
    if (!allowed.has(body.model)) throw new ApiError(400, "model_not_allowed", `Модель не разрешена: ${[...allowed].join(", ")}`);
    const key = ctx.principal!.kind === "device" ? `d:${ctx.device!.id}` : `a:${ctx.admin!.id}`;
    if (!limits.ai.take(key)) throw rateLimited("Лимит запросов к ИИ: попробуйте через несколько минут");

    const deviceId = ctx.principal!.kind === "device" ? ctx.device!.id : NO_DEVICE;
    const started = Date.now();
    const stream = body.stream === true;
    const outBody = stream
      ? { ...body, stream_options: { ...((body.stream_options as Record<string, unknown>) || {}), include_usage: true } }
      : body;

    let finished = false;
    const finish = ({ error, tokens = null }: { error: boolean; tokens?: { prompt: number; completion: number } | null }) => {
      if (finished) return;
      finished = true;
      metrics.recordAi({ ms: Date.now() - started, error });
      usage.add(deviceId, { prompt: tokens?.prompt, completion: tokens?.completion, requests: 1, errors: error ? 1 : 0 });
    };

    let upstream: Response;
    try {
      upstream = await fetchImpl(`${config.deepseekBaseUrl}/chat/completions`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${config.deepseekApiKey}`,
          "content-type": "application/json",
          accept: stream ? "text/event-stream" : "application/json",
        },
        body: JSON.stringify(outBody),
        signal: ctx.req.signal,
      });
    } catch (err) {
      if (ctx.req.signal.aborted) {
        finish({ error: false });
        return new Response(null, { status: 499 });
      }
      finish({ error: true });
      throw new ApiError(502, "upstream_error", "Сервис ИИ недоступен");
    }

    const headers: Record<string, string> = {
      ...SECURITY_HEADERS,
      "content-type": upstream.headers.get("content-type") || "application/json",
      "cache-control": "no-cache, no-transform",
      "x-accel-buffering": "no",
    };
    const upstreamFailed = upstream.status >= 400;
    if (!upstream.body) {
      finish({ error: upstreamFailed });
      return new Response(null, { status: upstream.status, headers });
    }
    const sniffer = new UsageSniffer(stream);
    const ts = new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        try {
          sniffer.push(chunk);
        } catch { /* статистика не должна ломать ответ */ }
        controller.enqueue(chunk);
      },
      flush() {
        finish({ error: upstreamFailed, tokens: sniffer.finish() });
      },
    });
    ctx.req.signal.addEventListener("abort", () => finish({ error: false, tokens: sniffer.finish() }), { once: true });
    return new Response(upstream.body.pipeThrough(ts), { status: upstream.status, headers });
  });

  void jsonResponse;
}
