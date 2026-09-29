# MIGRATION-NOTES — развёртывание на Deno Deploy

Что нужно знать интегратору (тебе) при переезде «Honer Cloud» на Deno Deploy.

## 1. Данные НЕ переносятся — это чистый старт

Old Railway/Postgres данные в новую Deno KV **не мигрируют**. Это допустимо и заложено:
- пользователи просто **перерегистрируются** (новый `installId` → новый `deviceId`, новый 4-значный `publicId`);
- админ **заново создаёт аккаунт** через `POST /v1/admin/setup` (гейт по `x-admin-key: $ADMIN_KEY`),
  либо сразу входит по `adminKey`.

Контракт API идентичен, поэтому Android-приложения и релей менять не требуется.

## 2. Deno KV на Deploy — автоматически

- В коде `Deno.openKv()` вызывается **без пути**, если `KV_PATH` не задан. На Deno Deploy это подключает
  управляемую (реплицируемую) KV автоматически — ничего провиженить не нужно.
- Локально/в CI задавай `KV_PATH=./honer.kv` (файл SQLite) или тесты используют `:memory:`.
- Запуск требует флага `--unstable-kv` (в `deno.json` уже прописан `"unstable": ["kv"]`; для явного `deno run`
  используй `deno run -A --unstable-kv main.ts`).

## 3. Переменные окружения (Deploy → Settings → Environment Variables)

Обязательные/важные: `DEEPSEEK_API_KEY`, `ADMIN_KEY`, `ADMIN_EMAILS`.
Опциональные: `GOOGLE_CLIENT_IDS`, `DEEPSEEK_BASE_URL`, `FIREBASE_SERVICE_ACCOUNT` (JSON или base64), плюс тюнинг
(`AI_RATE_MAX`, `PRESENCE_OFFLINE_MS`, `METRICS_PUSH_MS`, `BLOCK_SWEEP_MS`, `INACTIVE_DAYS`, …).
**Удалены**: `DATABASE_URL`, `MEDIA_DIR`, `DB_MODE` — они больше не нужны. `PORT` на Deploy назначается платформой.

## 4. Медиа хранятся в KV чанками — помни о размерах

- Значение Deno KV ограничено ~64 КБ, поэтому файл режется на чанки по **60 КБ**
  (`["mediaChunk", id, index]`), метаданные — в `["media", id]`. Range-запросы собирают нужные чанки.
- Лимит загрузки — `MAX_UPLOAD_BYTES` (по умолчанию 100 МБ). Учитывай квоты Deno KV: большие/многочисленные
  видео заметно расходуют объём KV. Если ожидается тяжёлое видео — рассмотри вынос медиа в объектное хранилище
  (S3/R2) отдельным шагом; на текущем контракте это прозрачно (клиент видит только `/v1/media/:id`).

## 5. Один инстанс (single-instance assumptions)

- Presence, «печатает…», rate-limit и live-метрики (rps/задержки) живут **в памяти процесса**.
  Держи **одну** реплику (isolate). При нескольких изолятах эти состояния разъедутся: WebSocket-рассылки,
  presence и лимиты станут неполными. Суммы токенов и все персистентные данные — в KV и переживут перезапуск,
  но in-memory метрики после рестарта обнуляются (как и в Node-версии).
- Таймеры (пуш метрик каждые `METRICS_PUSH_MS`, снятие просроченных блокировок каждые `BLOCK_SWEEP_MS`)
  работают внутри процесса; просроченная блокировка снимается также при любом обращении устройства.

## 6. Push (FCM)

- Если задан `FIREBASE_SERVICE_ACCOUNT`, сервер сам подписывает JWT (`node:crypto`, RS256), берёт OAuth-токен
  у Google и шлёт FCM HTTP v1 (без `firebase-admin`). Без переменной push просто отключается — приложение
  работает по WebSocket. Data-сообщения и логика доставки идентичны Node-версии.

## 7. Проверка после деплоя

```sh
curl https://<app>.deno.dev/health              # {"ok":true,"ai":...,"aiEnabled":...}
curl https://<app>.deno.dev/v1/admin/setup-status
# затем POST /v1/admin/setup с заголовком x-admin-key
```

## 8. Что НЕ портировано намеренно

- `/v1/weather` — обслуживается релеем (по ТЗ здесь не нужен).
- Проверка `googleIdToken` реализована через Google `tokeninfo` (в Node было `google-auth-library`);
  поведение эквивалентно, путь остаётся опциональным.
