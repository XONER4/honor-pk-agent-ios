# Honer AI Cloud — API contract (v1)

One server (Node.js, Railway) + Postgres. Shared by the user app (`com.honerai.app`),
the admin app (`com.honerai.admin`), and later the iPhone app. All JSON, UTF-8, timestamps ISO-8601 UTC strings.
Base URL is configured at build time (`HONER_CLOUD_URL`, e.g. `https://honer-cloud.up.railway.app`).
Every endpoint lives under `/v1`. Errors: HTTP status + `{"error":"code","message":"human text (ru)"}`.

## Auth

- **Device token** (user app): `Authorization: Bearer d_<token>` — issued by `POST /v1/devices/register`.
- **Admin token**: `Authorization: Bearer a_<token>` — issued by `POST /v1/admin/login`.
- Tokens are random 32-byte base64url; server stores only SHA-256 hashes.
- Blocked device → every device endpoint returns `403 {"error":"blocked","message":"<reason or empty>"}`.

## Device endpoints (user app)

### POST /v1/devices/register
Body: `{ "installId": "UUID generated once per install", "platform": "android|ios", "deviceModel": "vivo V2250",
"deviceName": "user-visible device name", "osVersion": "14", "appVersion": "10.44.0", "displayName": "Имя",
"birthday": "2008-05-01" | null, "language": "ru|en", "licenseAcceptedAt": ISO | null, "pushToken": "fcm token" | null }`
Response: `{ "deviceId": "uuid", "token": "d_…", "userId": "uuid", "adminChatId": "uuid" }`.
Idempotent per installId: re-register returns a NEW token for the same deviceId (old one revoked) and updates fields.
Each register with a new installId counts as a download ("installs" counter).

### PATCH /v1/devices/me
Body: any subset of register fields (displayName, birthday, pushToken, appVersion, language, licenseAcceptedAt).

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
- `{"t":"blocked","message":"reason"}` (device) — app shows the blocked screen.
- `{"t":"notification","notification":Notification}`
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
  Allowed models: `deepseek-chat`, `deepseek-reasoner`. Blocked devices get 403.

## Admin endpoints

### POST /v1/admin/login
Body either `{ "googleIdToken": "…" }` (verified against `GOOGLE_CLIENT_IDS`; email must be in `ADMIN_EMAILS`)
or `{ "adminKey": "…" }` (equals env `ADMIN_KEY`; fallback until Google sign-in is configured).
Response `{ "token": "a_…", "email": "…", "name": "…" }`.

### GET /v1/admin/overview
`{ "users": int, "installs": int, "online": int, "inBackground": int, "blocked": int, "messagesToday": int }`

### GET /v1/admin/devices?query=&status=all|online|blocked
`[DeviceSummary]` — `{ deviceId, userId, displayName, deviceModel, deviceName, platform, appVersion, installedAt,
lastSeen, presence: "foreground|background|offline", typingIn: chatId|null, blocked: bool, messagesSent, secondsInApp,
unreadForAdmin: int, adminChatId }`

### GET /v1/admin/devices/:deviceId → DeviceSummary + `{ birthday, language, osVersion, licenseAcceptedAt, blockReason, installs: int }`
### POST /v1/admin/devices/:deviceId/block body `{ "blocked": bool, "reason": "optional" }`
### Admin chat endpoints: same as device chats under `/v1/admin/chats/...` (list: `GET /v1/admin/chats`), plus
- `POST /v1/admin/chats/:chatId/ai` body `{ "enabled": bool }` — "Добавить ИИ в чат" (group chat admin + user + AI).
- `POST /v1/admin/notifications` body `{ "deviceId": uuid | null (all), "title", "body" }` — broadcast/personal notification.

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
```

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
