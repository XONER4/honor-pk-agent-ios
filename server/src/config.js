// Конфигурация из переменных окружения. Все остальные модули получают готовый объект config
// и никогда не читают process.env напрямую (так тесты могут подставить свои значения).
import path from 'node:path';

const list = (v) => String(v || '').split(',').map((s) => s.trim()).filter(Boolean);
const int = (v, def) => {
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) && n >= 0 ? n : def;
};
const bool = (v, def = false) => (v === undefined || v === '' ? def : /^(1|true|yes|on)$/i.test(String(v)));

function parseServiceAccount(raw) {
  if (!raw) return null;
  let text = String(raw).trim();
  // Разрешаем и «сырой» JSON, и base64 (удобно для панели Railway).
  if (!text.startsWith('{')) text = Buffer.from(text, 'base64').toString('utf8');
  const sa = JSON.parse(text);
  if (!sa.client_email || !sa.private_key || !sa.project_id) {
    throw new Error('FIREBASE_SERVICE_ACCOUNT must contain client_email, private_key and project_id');
  }
  return sa;
}

export function loadConfig(env = process.env) {
  const dbMode = (env.DB_MODE || 'postgres').toLowerCase();
  if (dbMode !== 'memory' && !env.DATABASE_URL) {
    throw new Error('DATABASE_URL is required (or set DB_MODE=memory for local development)');
  }
  return {
    port: int(env.PORT, 3000),
    host: env.HOST || '0.0.0.0',
    logLevel: env.LOG_LEVEL || 'info',

    dbMode,
    databaseUrl: env.DATABASE_URL || null,
    databaseSsl: bool(env.DATABASE_SSL, false),

    mediaDir: path.resolve(env.MEDIA_DIR || './media'),
    maxUploadBytes: int(env.MAX_UPLOAD_BYTES, 100 * 1024 * 1024),

    deepseekApiKey: env.DEEPSEEK_API_KEY || '',
    deepseekBaseUrl: (env.DEEPSEEK_BASE_URL || 'https://api.deepseek.com').replace(/\/+$/, ''),
    aiAllowedModels: list(env.AI_ALLOWED_MODELS || 'deepseek-chat,deepseek-reasoner'),
    aiGroupModel: env.AI_GROUP_MODEL || 'deepseek-chat',
    aiRateMax: int(env.AI_RATE_MAX, 60),
    aiRateWindowMs: int(env.AI_RATE_WINDOW_MS, 10 * 60 * 1000),
    aiQuestionDelayMs: int(env.AI_QUESTION_DELAY_MS, 20_000),
    aiHistoryLimit: int(env.AI_HISTORY_LIMIT, 30),
    aiTimeoutMs: int(env.AI_TIMEOUT_MS, 90_000),

    adminKey: env.ADMIN_KEY || '',
    adminEmails: list(env.ADMIN_EMAILS).map((e) => e.toLowerCase()),
    googleClientIds: list(env.GOOGLE_CLIENT_IDS),
    adminTokenTtlMs: int(env.ADMIN_TOKEN_TTL_MS, 180 * 24 * 3600 * 1000),
    loginRateMax: int(env.LOGIN_RATE_MAX, 10),
    loginRateWindowMs: int(env.LOGIN_RATE_WINDOW_MS, 15 * 60 * 1000),
    registerRateMax: int(env.REGISTER_RATE_MAX, 60),
    registerRateWindowMs: int(env.REGISTER_RATE_WINDOW_MS, 10 * 60 * 1000),

    firebaseServiceAccount: parseServiceAccount(env.FIREBASE_SERVICE_ACCOUNT),

    presenceOfflineMs: int(env.PRESENCE_OFFLINE_MS, 40_000),
    typingTtlMs: int(env.TYPING_TTL_MS, 8_000),
    wsIdleMs: int(env.WS_IDLE_MS, 75_000),

    corsOrigins: list(env.CORS_ORIGINS),
    trustProxy: bool(env.TRUST_PROXY, true),
  };
}
