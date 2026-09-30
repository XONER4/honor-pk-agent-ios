# Honer Cloud relay (Cloudflare Workers)

Релей обходит блокировку прямого доступа к Railway у операторов (МТС и часть домашних сетей).
Живёт на сети Cloudflare (обычно доступной) и прозрачно пересылает всё на Railway: обычные запросы,
поток ответа ИИ (SSE), WebSocket (онлайн/печатает/новые сообщения). Погоду (`/v1/weather`) отдаёт сам.

Бесплатный тариф Workers — **100 000 запросов в день**. Если лимит исчерпан, Cloudflare отвечает 429;
приложение это распознаёт (`CloudApi.probe` → `HealthStatus.LIMITED`) и либо уходит на прямой адрес
(если у пользователя есть VPN), либо показывает понятную причину и решение (`ConnReason.RELAY_LIMIT`).

## Деплой

Нужен Cloudflare API-токен с правами Workers (шаблон «Edit Cloudflare Workers»).

```bash
cd server/cf-relay
export CLOUDFLARE_API_TOKEN=<токен>
npx wrangler deploy
```

После деплоя wrangler покажет адрес вида `https://honer-relay.<subdomain>.workers.dev`.
Этот адрес прописывается в приложение как основной (`HONER_CLOUD_URL`) — он идёт первым в
`CloudConfig.candidates`, Railway остаётся запасным.

Сменить upstream без правки кода:

```bash
npx wrangler deploy --var UPSTREAM:https://<новый-адрес-railway>
```

## Проверка

```bash
curl https://honer-relay.<subdomain>.workers.dev/relay-health   # {"ok":true,"relay":true,"cf":true}
curl https://honer-relay.<subdomain>.workers.dev/health          # проксируется на Railway
curl "https://honer-relay.<subdomain>.workers.dev/v1/weather?q=Москва"
```
