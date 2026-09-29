# Honer Cloud — Deno KV edition

Полная переработка бэкенда «Honer Cloud» под **Deno Deploy + Deno KV**. Тот же HTTP+WebSocket
контракт, что у Node/Fastify/Postgres версии (см. `API.md` и `NOTES.md`) — приложения (`com.honerai.app`,
`com.honerai.admin`) и релей менять не нужно. Вместо Postgres — Deno KV, вместо файлового хранилища медиа —
чанки в той же Deno KV.

## Запуск

```sh
# локально (KV в файле)
DEEPSEEK_API_KEY=... ADMIN_KEY=... KV_PATH=./honer.kv deno run -A --unstable-kv main.ts
# или через задачу
deno task start
```

Проверка:

```sh
curl localhost:8000/health          # {"ok":true,"ai":true,"aiEnabled":true}
curl localhost:8000/v1/admin/setup-status
```

На **Deno Deploy** переменную `KV_PATH` не задают — `Deno.openKv()` без аргумента подключает управляемую KV.

## Переменные окружения

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `DEEPSEEK_API_KEY` | ключ DeepSeek для AI-прокси и группового ИИ | — (без него ИИ отключён) |
| `ADMIN_KEY` | ключ администратора (setup-гейт и запасной вход) | — |
| `ADMIN_EMAILS` | список e-mail админов (для входа по Google/ключу) | — |
| `GOOGLE_CLIENT_IDS` | список client_id для проверки Google ID token (опц.) | — |
| `DEEPSEEK_BASE_URL` | база DeepSeek | `https://api.deepseek.com` |
| `FIREBASE_SERVICE_ACCOUNT` | JSON или base64 сервис-аккаунта FCM (опц.) | — |
| `KV_PATH` | путь к базе Deno KV (локально); на Deploy не задавать | управляемая KV |
| `PORT` | порт | `8000` |
| прочее (`AI_RATE_MAX`, `PRESENCE_OFFLINE_MS`, `TYPING_TTL_MS`, `WS_IDLE_MS`, `METRICS_PUSH_MS`, `BLOCK_SWEEP_MS`, `INACTIVE_DAYS`, `REPORT_RATE_MAX`, `AI_QUESTION_DELAY_MS`, `AI_GROUP_MODEL`, `AI_ALLOWED_MODELS`, `AI_SCHEDULE_TZ`, `ADMIN_TOKEN_TTL_MS`, `LOGIN_RATE_MAX`, `REGISTER_RATE_MAX`, `MAX_UPLOAD_BYTES`) | как в Node-версии | те же дефолты |

Больше **нет** `DATABASE_URL` и `MEDIA_DIR` — их заменяет Deno KV. Погодный эндпоинт `/v1/weather` здесь
не реализован намеренно (его обслуживает релей).

## Тесты

```sh
deno task test           # = deno test -A --unstable-kv
```

Каждый тест открывает свою `Deno.openKv(":memory:")` (изолированная in-memory база). Мок DeepSeek —
локальный `Deno.serve` на отдельном порту; `DEEPSEEK_BASE_URL` в тестах указывает на него.

## Структура

- `main.ts` — точка входа: `Deno.serve` + graceful shutdown.
- `app.ts` — сборка контекста, роутинг, таймеры (пуш метрик, снятие просроченных блокировок), guard.
- `http.ts` — мини-роутер, контекст запроса, JSON-парсер (пустое тело = `{}`, отказ на `__proto__`), валидаторы.
- `kv.ts` — слой данных Deno KV (устройства, пользователи, админы, токены, счётчики, настройки).
- `chat-service.ts` — чаты/сообщения/реакции/закрепы/удаление/очистка/прочтение.
- `ai.ts` — групповой ИИ; `routes_ai.ts` — AI-прокси (стриминг SSE без буферизации, подсчёт токенов).
- `hub.ts` — presence/typing/рассылки WebSocket (в памяти); `ws.ts` — обработчик `/v1/ws` (`Deno.upgradeWebSocket`).
- `media.ts` — хранение медиа чанками в KV + разбор Range; `push.ts` — FCM HTTP v1 (JWT через `node:crypto`).
- `metrics.ts`, `ai-control.ts`, `auth.ts`, `passwords.ts`, `public-id.ts`, `device-events.ts`, `ratelimit.ts`, `timers.ts`.
- `routes_*.ts` — HTTP-маршруты (devices, chats, media, notifications, ai, admin, admin-insights).

## Отличия от Node-версии (не влияют на контракт приложений)

- **Хранилище** — Deno KV вместо Postgres; **медиа** — чанки KV (по 60 КБ) вместо файлов на диске.
- Один инстанс: presence/typing/лимиты/метрики — в памяти процесса (как и раньше, `numReplicas: 1`).
- Проверка Google ID token в `main.ts` сделана через Google `tokeninfo` (без `google-auth-library`);
  в тестах подменяется. Путь `googleIdToken` необязателен — основной вход по логину/паролю и `adminKey`.
- `/v1/weather` не реализован (обслуживает релей).
