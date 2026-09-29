// Точка входа. На Deno Deploy сервер должен регистрироваться синхронно: Deno.serve вызывается
// сразу, а тяжёлая инициализация (KV, конфиг) — лениво, при первом запросе. Локально слушаем порт.
import { loadConfig, type Config } from "./config.ts";
import { buildApp } from "./app.ts";

let config: Config;
try {
  config = loadConfig();
} catch (err) {
  console.error(`[config] ${(err as Error).message}`);
  Deno.exit(1);
}

const verifyGoogleIdToken = config.googleClientIds.length
  ? async (idToken: string, audience: string[]) => {
    // Минимальная проверка Google ID token через tokeninfo (без внешних зависимостей).
    const res = await fetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`, {
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) throw new Error("google tokeninfo failed");
    const p = await res.json();
    if (!audience.includes(p.aud)) throw new Error("aud mismatch");
    return { email: p.email, email_verified: p.email_verified === "true" || p.email_verified === true, name: p.name };
  }
  : undefined;

if (!config.deepseekApiKey) console.warn("DEEPSEEK_API_KEY не задан: AI-прокси и групповой ИИ отключены");
if (!config.adminKey && !config.googleClientIds.length) console.warn("Не задан ни ADMIN_KEY, ни GOOGLE_CLIENT_IDS: вход админа невозможен");

// Ленивая сборка: buildApp (открывает Deno KV) вызывается один раз при первом запросе, а не на этапе
// сборки Deploy — иначе анализ билда падает, и ревизия не поднимается.
type App = Awaited<ReturnType<typeof buildApp>>;
let built: App | null = null;
let building: Promise<App> | null = null;
function app(): Promise<App> {
  if (built) return Promise.resolve(built);
  building ??= buildApp(config, { verifyGoogleIdToken }).then((a) => (built = a));
  return building;
}

const serveHandler = async (req: Request, info: Deno.ServeHandlerInfo) => {
  const { handler } = await app();
  return handler(req, info);
};

const onDeploy = Boolean(Deno.env.get("DENO_DEPLOYMENT_ID"));
const server = onDeploy
  ? Deno.serve(serveHandler)
  : Deno.serve({ port: config.port, hostname: config.host }, serveHandler);

let shuttingDown = false;
async function shutdown(signal: string) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`shutting down (${signal})`);
  try {
    await server.shutdown();
    if (built) await built.stop();
  } catch (err) {
    console.error("shutdown error", err);
  }
  Deno.exit(0);
}
try {
  Deno.addSignalListener("SIGINT", () => shutdown("SIGINT"));
  // SIGTERM не поддерживается на Windows — оборачиваем в try.
  try {
    Deno.addSignalListener("SIGTERM", () => shutdown("SIGTERM"));
  } catch { /* windows */ }
} catch { /* ignore */ }
