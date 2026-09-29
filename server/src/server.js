// Точка входа: читает конфиг, поднимает сервер, корректно завершается по SIGTERM/SIGINT (Railway шлёт SIGTERM).
import { loadConfig } from './config.js';
import { buildApp } from './app.js';

let config;
try {
  config = loadConfig();
} catch (err) {
  console.error(`[config] ${err.message}`);
  process.exit(1);
}

const app = await buildApp(config);

if (config.dbMode === 'memory') app.log.warn('DB_MODE=memory: данные хранятся в памяти и пропадут при перезапуске');
if (!config.deepseekApiKey) app.log.warn('DEEPSEEK_API_KEY не задан: AI-прокси и групповой ИИ отключены');
if (!config.adminKey && !config.googleClientIds.length) app.log.warn('Не задан ни ADMIN_KEY, ни GOOGLE_CLIENT_IDS: вход админа невозможен');

try {
  await app.listen({ port: config.port, host: config.host });
} catch (err) {
  app.log.error({ err: { message: err.message } }, 'listen failed');
  process.exit(1);
}

let shuttingDown = false;
async function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  app.log.info({ signal }, 'shutting down');
  const force = setTimeout(() => process.exit(1), 10_000);
  force.unref();
  try {
    await app.close(); // закрывает WebSocket-соединения, таймеры хаба и пул БД
    process.exit(0);
  } catch (err) {
    app.log.error({ err: { message: err.message } }, 'shutdown error');
    process.exit(1);
  }
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('unhandledRejection', (err) => app.log.error({ err: { message: err?.message } }, 'unhandled rejection'));
