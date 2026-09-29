// Точка входа: читает конфиг, поднимает Deno.serve, корректно завершается по SIGTERM/SIGINT.
// Запуск: deno run -A --unstable-kv main.ts
import { loadConfig } from "./config.ts";
import { buildApp } from "./app.ts";

let config;
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

const { handler, stop } = await buildApp(config, { verifyGoogleIdToken });

if (!config.deepseekApiKey) console.warn("DEEPSEEK_API_KEY не задан: AI-прокси и групповой ИИ отключены");
if (!config.adminKey && !config.googleClientIds.length) console.warn("Не задан ни ADMIN_KEY, ни GOOGLE_CLIENT_IDS: вход админа невозможен");

const server = Deno.serve({ port: config.port, hostname: config.host }, handler);

let shuttingDown = false;
async function shutdown(signal: string) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`shutting down (${signal})`);
  try {
    await server.shutdown();
    await stop();
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
