// Конфигурация из переменных окружения. Аналог config.js из Node-версии, но без DATABASE_URL и MEDIA_DIR:
// данные и медиа хранятся в Deno KV. Остальные модули получают готовый объект config.

const list = (v: string | undefined) => String(v || "").split(",").map((s) => s.trim()).filter(Boolean);
const int = (v: string | undefined, def: number) => {
  const n = Number.parseInt(String(v ?? ""), 10);
  return Number.isFinite(n) && n >= 0 ? n : def;
};
const bool = (v: string | undefined, def = false) => (v === undefined || v === "" ? def : /^(1|true|yes|on)$/i.test(String(v)));

export interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id: string;
}

function parseServiceAccount(raw: string | undefined): ServiceAccount | null {
  if (!raw) return null;
  let text = String(raw).trim();
  // Разрешаем и «сырой» JSON, и base64 (удобно для панели Deploy).
  if (!text.startsWith("{")) text = new TextDecoder().decode(Uint8Array.from(atob(text), (c) => c.charCodeAt(0)));
  const sa = JSON.parse(text);
  if (!sa.client_email || !sa.private_key || !sa.project_id) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT must contain client_email, private_key and project_id");
  }
  return sa;
}

export type Config = ReturnType<typeof loadConfig>;

export function loadConfig(env: Record<string, string | undefined> = Deno.env.toObject()) {
  return {
    port: int(env.PORT, 8000),
    host: env.HOST || "0.0.0.0",
    logLevel: env.LOG_LEVEL || "info",

    // Путь к базе Deno KV. На Deno Deploy — пусто (управляемая KV). Локально можно задать файл или ":memory:".
    kvPath: env.KV_PATH || undefined,

    maxUploadBytes: int(env.MAX_UPLOAD_BYTES, 100 * 1024 * 1024),

    deepseekApiKey: env.DEEPSEEK_API_KEY || "",
    deepseekBaseUrl: (env.DEEPSEEK_BASE_URL || "https://api.deepseek.com").replace(/\/+$/, ""),
    aiAllowedModels: list(env.AI_ALLOWED_MODELS || "deepseek-flash,deepseek-chat,deepseek-reasoner"),
    aiGroupModel: env.AI_GROUP_MODEL || "deepseek-flash",
    aiRateMax: int(env.AI_RATE_MAX, 60),
    aiRateWindowMs: int(env.AI_RATE_WINDOW_MS, 10 * 60 * 1000),
    aiQuestionDelayMs: int(env.AI_QUESTION_DELAY_MS, 20_000),
    aiHistoryLimit: int(env.AI_HISTORY_LIMIT, 30),
    aiTimeoutMs: int(env.AI_TIMEOUT_MS, 90_000),

    adminKey: env.ADMIN_KEY || "",
    adminEmails: list(env.ADMIN_EMAILS).map((e) => e.toLowerCase()),
    googleClientIds: list(env.GOOGLE_CLIENT_IDS),
    adminTokenTtlMs: int(env.ADMIN_TOKEN_TTL_MS, 180 * 24 * 3600 * 1000),
    loginRateMax: int(env.LOGIN_RATE_MAX, 10),
    loginRateWindowMs: int(env.LOGIN_RATE_WINDOW_MS, 15 * 60 * 1000),
    registerRateMax: int(env.REGISTER_RATE_MAX, 60),
    registerRateWindowMs: int(env.REGISTER_RATE_WINDOW_MS, 10 * 60 * 1000),

    metricsPushMs: int(env.METRICS_PUSH_MS, 5_000),
    reportRateMax: int(env.REPORT_RATE_MAX, 30),
    reportRateWindowMs: int(env.REPORT_RATE_WINDOW_MS, 10 * 60 * 1000),
    aiScheduleTimezone: env.AI_SCHEDULE_TZ || "Europe/Moscow",
    inactiveDays: int(env.INACTIVE_DAYS, 7),
    blockSweepMs: int(env.BLOCK_SWEEP_MS, 60_000),

    firebaseServiceAccount: parseServiceAccount(env.FIREBASE_SERVICE_ACCOUNT),

    presenceOfflineMs: int(env.PRESENCE_OFFLINE_MS, 40_000),
    typingTtlMs: int(env.TYPING_TTL_MS, 8_000),
    wsIdleMs: int(env.WS_IDLE_MS, 75_000),

    corsOrigins: list(env.CORS_ORIGINS),
  };
}
