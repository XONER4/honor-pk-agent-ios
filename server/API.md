# Honer AI Cloud — API contract (v1)

One server (Node.js, Railway) + Postgres. Shared by the user app (`com.honerai.app`),
the admin app (`com.honerai.admin`), and later the iPhone app. All JSON, UTF-8, timestamps ISO-8601 UTC strings.
Base URL is configured at build time (`HONER_CLOUD_URL`, e.g. `https://honer-cloud.up.railway.app`).
Every endpoint lives under `/v1`. Errors: HTTP status + `{"error":"code","message":"human text (ru)"}`.

## Auth

- **Device token** (user app): `Authorization: Bearer d_<token>` — issued by `POST /v1/devices/register`.
- **Admin token**: `Authorization: Bearer a_<token>` — issued by `POST /v1/admin/login`.
- Tokens are random 32-byte base64url; server stores only SHA-256 hashes.
- Blocked device → every device endpoint returns `403 {"error":"blocked","message":"<reason or empty>","until":ISO|null}`
  (`until` — end of a temporary block, `null` = permanent; the server lifts expired blocks by itself).

## Device endpoints (user app)

### POST /v1/devices/register
Body: `{ "installId": "UUID generated once per install", "platform": "android|ios", "deviceModel": "vivo V2250",
"deviceName": "user-visible device name", "osVersion": "14", "appVersion": "10.44.0", "displayName": "Имя",
"birthday": "2008-05-01" | null, "language": "ru|en", "licenseAcceptedAt": ISO | null, "pushToken": "fcm token" | null }`
Response: `{ "deviceId": "uuid", "token": "d_…", "userId": "uuid", "adminChatId": "uuid", "publicId": "0427",
"overrides": Overrides }` (`publicId` — the user's short public ID, see Models).
Idempotent per installId: re-register returns a NEW token for the same deviceId (old one revoked) and updates fields.
Each register with a new installId counts as a download ("installs" counter).

### PATCH /v1/devices/me
Body: any subset of register fields (displayName, birthday, pushToken, appVersion, language, licenseAcceptedAt).
Response `{ "ok": true, "publicId": "0427", "overrides": Overrides }`. A changed `appVersion` is recorded as an `update` event.

### POST /v1/devices/me/stats
Body: `{ "messagesSent": int (total so far), "secondsInApp": int (total so far) }` — absolute counters, server keeps max.

### WebSocket /v1/ws?token=<device or admin token>
Client → server frames (JSON):
- `{"t":"presence","state":"foreground|background"}` — sent on connect and whenever app state changes.
- `{"t":"typing","chatId":"…","typing":true|false}`
- `{"t":"read","chatId":"…","messageId":"…"}` — everything up to and including messageId is read.
- `{"t":"ping"}` every 25 s (server replies `{"t":"pong"}`).
Server → client frames:
- `{"t":"message","chatId","message":Message}` (new message)
- `{"t":"message.updated","chatId","message":Message}` (edit, reaction, pin, delete-for-everyone → `deleted:true`)
- `{"t":"chat.cleared","chatId"}`
- `{"t":"typing","chatId","who":"admin|user|ai","typing":bool}`
- `{"t":"read","chatId","who":"admin|user","messageId"}`
- `{"t":"presence","deviceId","state":"foreground|background|offline","typingIn":chatId|null,"lastSeen":ISO}` (admins only)
- `{"t":"blocked","message":"reason","until":ISO|null}` (device) — app shows the blocked screen.
- `{"t":"notification","notification":Notification}`
- `{"t":"overrides","overrides":Overrides}` (device) — personal restrictions changed by the admin.
- `{"t":"metrics", …Metrics}` (admins, every 5 s) and `{"t":"ai", …AiSettings}` (admins, when AI settings change).
Presence = `offline` when no socket for 40 s. `lastSeen` = last time the socket was alive.

### Chats (device)
- `GET /v1/chats` → `[Chat]` (the admin chat always exists; group chats appear when the admin adds AI → still the same chat, `aiEnabled:true`).
- `GET /v1/chats/:chatId/messages?before=<messageId>&limit=50` → `[Message]` newest last.
- `POST /v1/chats/:chatId/messages` body `{ "clientId": "uuid", "text": "…", "attachments": [AttachmentRef], "replyTo": messageId|null }` → `Message`. Idempotent by clientId.
- `POST /v1/chats/:chatId/messages/:id/reaction` body `{ "emoji": "👍" | null }`.
- `POST /v1/chats/:chatId/messages/:id/pin` body `{ "pinned": bool }`.
- `DELETE /v1/chats/:chatId/messages/:id?scope=me|everyone` (everyone only for own messages; admin may delete any).
- `POST /v1/chats/:chatId/clear?scope=me|everyone` (everyone only for admin).
- `POST /v1/media` multipart field `file` (≤ 100 MB) → `AttachmentRef`. `GET /v1/media/:id` (auth required; supports Range) streams the file.

### Notifications (device)
- `GET /v1/notifications?after=<ISO>` → `[Notification]`; `POST /v1/notifications/read` body `{ "ids": [..] }`.

### AI proxy (device) — hides the DeepSeek key
- `POST /v1/ai/chat/completions` — body is the DeepSeek/OpenAI chat-completions body unchanged (incl. `stream:true`, tools).
  The server injects the key, forwards to `https://api.deepseek.com/chat/completions`, and pipes the response bytes back
  unchanged (SSE when streaming) with no buffering. Rate limit: 60 requests / 10 min / device (429 `rate_limited`).
  Allowed models: `deepseek-flash` (used by the apps), `deepseek-chat`, `deepseek-reasoner`. Blocked devices get 403.
  For `stream:true` the server adds `stream_options.include_usage:true`, so the last SSE chunk is
  `{"choices":[],"usage":{…}}` (clients must skip chunks with empty `choices`). Token usage is counted per device.
  When the admin has switched the AI off (globally or by schedule): `503 {"error":"ai_disabled","code":"ai_disabled",
  "message":"ИИ временно отключён администратором."}` — clients show the message and must NOT fall back to a direct key.

## Admin endpoints

### GET /v1/admin/setup-status → `{ "hasAccount": bool }` (no auth)
Whether an admin account with login/password exists.

### POST /v1/admin/setup (no auth token)
Body `{ "login": "boss", "password": "…" }`, header `x-admin-key: <ADMIN_KEY>` (not needed only when `ADMIN_KEY`
is not configured). Allowed ONLY while no account exists (`409 conflict` afterwards). Login: 3–64 chars `[A-Za-z0-9._@-]`,
stored lowercase; password ≥ 8 chars, stored as scrypt with a random salt. Wrong/missing key → `401`. Response = login response.

### POST /v1/admin/login
Body one of: `{ "login": "…", "password": "…" }` (main way), `{ "googleIdToken": "…" }` (verified against
`GOOGLE_CLIENT_IDS`; email must be in `ADMIN_EMAILS`) or `{ "adminKey": "…" }` (equals env `ADMIN_KEY`; spare way).
Response `{ "token": "a_…", "email": "…", "name": "…", "login": "boss"|null }`.
Wrong login/password → `401 {"error":"unauthorized","message":"Неверный логин или пароль"}`; 10 failures / 15 min / IP → 429.

### GET /v1/admin/overview
`{ "users": int, "installs": int, "online": int, "inBackground": int, "blocked": int, "messagesToday": int,
"updates": int, "uninstalls": int, "inactive": int, "inactiveDays": 7 }` — `updates` = app updates seen, `uninstalls` = push
tokens rejected by FCM (a hint, not proof), `inactive` = devices with no contact for more than `inactiveDays` days.

### GET /v1/admin/devices?query=&status=all|online|blocked&sort=activity|tokens
`[DeviceSummary]` — `{ deviceId, userId, publicId, displayName, deviceModel, deviceName, platform, appVersion, installedAt,
lastSeen, presence: "foreground|background|offline", typingIn: chatId|null, blocked: bool, blockedUntil: ISO|null,
messagesSent, secondsInApp, unreadForAdmin: int, adminChatId, aiTokens: int (all time), reports: int }`.
`query` matches name, device model/name, public ID (`0427` or `#0427`), ids, version. `sort=tokens` — by `aiTokens` desc.

### GET /v1/admin/devices/:deviceId → DeviceSummary + `{ birthday, language, osVersion, licenseAcceptedAt, blockReason, installs: int,
  overrides: Overrides, usage: { today: Usage, total: Usage } }`
### POST /v1/admin/devices/:deviceId/block body `{ "blocked": bool, "reason": "optional", "until": ISO | null }`
`until` — temporary block (must be in the future, else 400); `null`/absent = permanent. Returns the device card.
### Admin chat endpoints: same as device chats under `/v1/admin/chats/...` (list: `GET /v1/admin/chats`), plus
- `POST /v1/admin/chats/:chatId/ai` body `{ "enabled": bool }` — "Добавить ИИ в чат" (group chat admin + user + AI).
- `POST /v1/admin/notifications` body `{ "deviceId": uuid | null (all), "title", "body" }` — broadcast/personal notification.

## Admin: metrics, AI switch, reports, history, notes, overrides (admin2)

### GET /v1/admin/metrics
```
{ at, online, inBackground, rps, requests1m, errors1m,
  tokensToday: int, tokensTotal: int, tokens: { today: Usage, total: Usage },
  aiRequestsToday, aiErrorsToday, reportsToday, errorsToday,          // errorsToday = aiErrorsToday + reportsToday (UTC day)
  aiLatencyMs: { p50, p95, avg, count }, apiLatencyMs: { p50, p95, avg, count },   // ms, null when there are no samples
  model: { enabled: bool, switchedOn: bool, scheduled: bool, configured: bool, name } }
```
`rps`/`errors1m` — last 60 s (in memory); AI latency — AI proxy + group AI over the last 15 min; API latency — last 5 min.
Token sums are persisted in `usage_daily`. Admin WebSockets also receive the same object every 5 s as
`{"t":"metrics", …}` (only while some admin is connected). `model.enabled` — AI effectively on and the key is configured.

### GET /v1/admin/ai → AiSettings; POST /v1/admin/ai body `{ "enabled"?: bool, "schedule"?: [Window], "timezone"?: "Europe/Moscow" }`
`AiSettings { enabled, schedule: [Window], timezone, effective: bool, configured: bool }`,
`Window { days: [1..7] (1 = Mon … 7 = Sun; empty = every day), from: "HH:MM", to: "HH:MM" }`.
`enabled` is the master switch. With a non-empty schedule the AI works only inside its windows (`to < from` — crosses
midnight, `from == to` — whole day). `effective` — AI allowed right now. When not effective: the proxy answers 503
`ai_disabled`, the group AI stays silent, `/health` → `aiEnabled:false`. Admins get `{"t":"ai", …AiSettings}` on change.

### Client reports
- Device: `POST /v1/devices/me/report` body `{ "kind": "error"|"crash", "message", "stack"?, "appVersion"?, "at"?: ISO }` →
  `{ "ok": true, "id" }`. Limit 30 / 10 min / device (429). The first 2000 chars of message and 16000 of stack are kept.
- Admin: `GET /v1/admin/reports?deviceId=&kind=error|crash&limit=50&before=ISO` → `[Report]`, newest first.
  `Report { id, deviceId, publicId, displayName, deviceModel, kind, message, stack|null, appVersion|null, at }`.

### Device history, notes, action log, overrides
- `GET /v1/admin/devices/:deviceId/events` → `[DeviceEvent]` newest first,
  `DeviceEvent { id, deviceId, kind: "install|update|uninstall|open", fromVersion|null, toVersion|null, at }`
  (`install` — first registration of an installId; `update` — appVersion changed; `uninstall` — FCM rejected the push token;
  `open` — reserved).
- Notes: `GET /v1/admin/devices/:deviceId/notes` → `[Note]`; `POST …/notes` `{ "text" }` → 201 Note;
  `PATCH /v1/admin/notes/:noteId` `{ "text" }` → Note; `DELETE /v1/admin/notes/:noteId` → `{ "ok": true }`.
  `Note { id, deviceId, adminId, adminName, text, createdAt, updatedAt|null }`.
- `GET /v1/admin/actions?deviceId=&limit=50&before=ISO` → `[AdminAction]`, newest first,
  `AdminAction { id, adminId|null, adminName, deviceId|null, action, detail: object|null, at }`; actions: `block`, `unblock`,
  `unblock_auto` (server, term expired), `notify`, `broadcast`, `overrides`, `ai_settings`, `admin_setup`.
- `PATCH /v1/admin/devices/:deviceId/overrides` body — any of `{ "forceLanguage": "ru"|"en"|null, "disableSearch": bool|null,
  "maxMessagesPerDay": 1..100000|null }`; `null`/`false` removes the key → `{ "overrides": Overrides }`. The device gets
  `{"t":"overrides","overrides":{…}}` over WebSocket and the same object in register/PATCH responses.
  The server does NOT enforce them — the app applies them.

## Models

```
Chat { id, kind: "admin", deviceId, title, aiEnabled: bool, pinnedMessageId: string|null,
       lastMessage: Message|null, unread: int, peerTyping: bool, peerReadUpTo: messageId|null, peerLastSeen: ISO|null,
       peerPresence: "foreground|background|offline" }
Message { id, clientId, chatId, sender: "admin|user|ai", text, attachments: [AttachmentRef], replyTo: id|null,
          createdAt, editedAt|null, deleted: bool, reactions: { "👍": ["admin","user"] }, pinned: bool,
          readByPeer: bool }
AttachmentRef { id, kind: "image|video|audio|voice|file", name, mime, size, durationMs|null, width|null, height|null,
                url: "/v1/media/<id>" }
Notification { id, title, body, createdAt, read: bool, chatId: string|null, kind: "admin|system|ai" }
Usage { prompt, completion, total, requests, errors }          // AI tokens / requests / AI errors
Overrides { forceLanguage?: "ru"|"en", disableSearch?: true, maxMessagesPerDay?: int }   // only set keys
```

### Public user ID
Every user gets a random unique `publicId` — 4 digits as a string (`"0000"`…`"9999"`, leading zeros kept); when all
10 000 are taken, 5 digits, and so on. Assigned at registration; existing users get one at server start.

## Group chat with AI (`aiEnabled`)
When enabled, after every new user/admin message the server decides whether the AI should answer:
answer if the message mentions the AI ("Honer", "ИИ", "AI", "нейросеть", "бот") or is a direct question that the
admin did not answer within 20 s; otherwise stay silent. The AI sees the last 30 messages with sender roles and replies
as sender `ai` (it is broadcast with typing `who:"ai"` while generating).

## Push
If env `FIREBASE_SERVICE_ACCOUNT` (JSON) is set, the server sends FCM data messages
`{ type: "message"|"notification", chatId, title, body }` to devices that are not in foreground.
Without FCM the app falls back to its WebSocket (foreground/background service) and a WorkManager poll.

## Env
`DATABASE_URL`, `DEEPSEEK_API_KEY`, `ADMIN_KEY`, `ADMIN_EMAILS` (comma list), `GOOGLE_CLIENT_IDS` (comma list),
`MEDIA_DIR` (Railway volume path, default ./media), `FIREBASE_SERVICE_ACCOUNT` (optional), `PORT`.
Optional (admin2): `AI_SCHEDULE_TZ` (default `Europe/Moscow`), `INACTIVE_DAYS` (7), `METRICS_PUSH_MS` (5000),
`REPORT_RATE_MAX` (30) / `REPORT_RATE_WINDOW_MS` (600000), `BLOCK_SWEEP_MS` (60000).
