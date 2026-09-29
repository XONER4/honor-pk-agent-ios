# Honer Cloud

Бэкенд для приложений Honer AI (пользовательское `com.honerai.app`, админское `com.honerai.admin`, позже iPhone).
Контракт API — в [API.md](API.md), уточнения к нему — в [NOTES.md](NOTES.md).

Стек: Node.js ≥ 20 (ESM, без сборки), Fastify 5, `@fastify/websocket`, `@fastify/multipart`, `@fastify/cors`, `pg`,
`google-auth-library` (Google ID token + OAuth-токен для FCM). Схема БД создаётся автоматически при старте.

## Запуск локально

```bash
npm install
npm test                               # 43 теста на pg-mem, без настоящего Postgres
DB_MODE=memory ADMIN_KEY=dev npm start # сервер с БД в памяти → http://localhost:3000/health
```

С настоящим Postgres: `DATABASE_URL=postgres://… npm start`.

## Деплой на Railway

1. Создать проект, добавить сервис **Postgres** и сервис из этой папки (сборка по `Dockerfile`, см. `railway.json`:
   healthcheck `/health`, рестарт при падении, 1 реплика — presence и rate-limit хранятся в памяти процесса).
2. **Подключить Volume** к сервису и смонтировать его в путь `MEDIA_DIR` (по умолчанию в образе — `/data/media`).
   Без тома все загруженные фото/видео/голосовые пропадут при каждом деплое.
3. Задать переменные (шаблон — `.env.example`):

| Переменная | Обязательна | Назначение |
|---|---|---|
| `DATABASE_URL` | да | строка подключения Postgres (`${{Postgres.DATABASE_URL}}`) |
| `DEEPSEEK_API_KEY` | да* | ключ DeepSeek; без него AI-прокси отвечает 503, групповой ИИ молчит |
| `ADMIN_KEY` | да* | вход админа по ключу (длинная случайная строка) |
| `ADMIN_EMAILS` | да* | e-mail'ы админов для входа через Google (через запятую) |
| `GOOGLE_CLIENT_IDS` | да* | OAuth client ID, для которых выпущен Google ID token (через запятую) |
| `MEDIA_DIR` | да | путь монтирования тома Railway (по умолчанию `./media`, в Docker — `/data/media`) |
| `FIREBASE_SERVICE_ACCOUNT` | нет | JSON сервисного аккаунта Firebase (или base64 от него) — включает FCM push |
| `PORT` | нет | Railway задаёт сам |
| `DATABASE_SSL` | нет | `true`, если к Postgres нужно TLS-подключение (публичный прокси) |
| `CORS_ORIGINS` | нет | CORS закрыт по умолчанию; список origin'ов для веб-клиентов |

\* нужен хотя бы один способ входа админа: `ADMIN_KEY` или пара `GOOGLE_CLIENT_IDS` + `ADMIN_EMAILS`.
Тонкие настройки (лимиты, таймауты presence/ИИ) перечислены в `.env.example`.

## Структура

```
src/
  server.js          точка входа, graceful shutdown (SIGTERM/SIGINT)
  app.js             сборка Fastify: плагины, заголовки безопасности, маршруты
  config.js          переменные окружения
  db.js              адаптер БД (pg / pg-mem), migrations.js — идемпотентная схема
  auth.js            токены (SHA-256 в БД), хуки авторизации
  hub.js             WebSocket-реестр, presence, typing, рассылки; ws.js — обработчик /v1/ws
  chat-service.js    сообщения, реакции, закрепы, удаление/очистка, прочтение, модели API
  ai.js              групповой ИИ (правила ответа, вызов DeepSeek)
  push.js            FCM HTTP v1
  media.js           файловое хранилище, HTTP Range
  ratelimit.js       in-memory лимитеры
  errors.js          формат ошибок {"error","message"}
  routes/            devices, chats (устройство + админ), media, notifications, ai (прокси), admin
test/                node:test + pg-mem, мок DeepSeek на локальном HTTP-сервере
```

## Безопасность

Токены — 32 случайных байта, в БД только SHA-256; ключ DeepSeek, токены и тела запросов не логируются
(в логе запросов URL без query-string); весь SQL параметризован; входные данные валидируются JSON-схемами;
лимиты размера тела (1 МБ JSON, 4 МБ для AI-прокси, 100 МБ файл); CORS закрыт; заголовки nosniff/DENY/CSP/HSTS;
ключ админа сравнивается за постоянное время; неудачные входы ограничены по IP.
