// Honer Cloud relay на Fly.io (не Cloudflare — провайдеры/МТС не режут большие загрузки к Fly).
// Прозрачно пересылает всё на Railway: обычные запросы, поток ответа ИИ (SSE), WebSocket
// (онлайн/печатает/новые сообщения). Погоду (/v1/weather) и /relay-health отдаёт сам.
// Ключи и тела запросов не логируются.

const http = require('node:http');
const httpProxy = require('http-proxy');

const UPSTREAM = (process.env.UPSTREAM || 'https://honor-pk-agent-ios-production.up.railway.app').replace(/\/+$/, '');
const PORT = Number(process.env.PORT || 8080);

// Прокси: changeOrigin — Host становится апстримовым; ws — проксируем и WebSocket.
// Стриминг (SSE ответа ИИ) идёт как есть — http-proxy не буферизирует тело.
const proxy = httpProxy.createProxyServer({
  target: UPSTREAM,
  changeOrigin: true,
  ws: true,
  secure: true,
  xfwd: true,
  proxyTimeout: 0, // не обрывать долгие потоки (SSE, скачивание)
  timeout: 0,
});

proxy.on('error', (err, req, res) => {
  if (res && res.writeHead && !res.headersSent) {
    res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'relay_unavailable', message: 'Сервер Honer AI временно недоступен. Повторите.' }));
  } else if (res && res.destroy) {
    res.destroy();
  }
});

async function geocodeCity(name) {
  for (const lang of ['ru', 'en']) {
    try {
      const u = `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(name)}&count=5&language=${lang}&format=json`;
      const r = await fetch(u, { signal: AbortSignal.timeout(7000) });
      if (!r.ok) continue;
      const first = (await r.json())?.results?.[0];
      if (first) return { name: [first.name, first.admin1, first.country].filter(Boolean).join(', '), lat: first.latitude, lon: first.longitude };
    } catch { /* след. язык */ }
  }
  return null;
}

async function weather(url, res) {
  const q = url.searchParams;
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)); };
  let lat = Number(q.get('lat')), lon = Number(q.get('lon')), place = '';
  const city = q.get('q');
  if (city) {
    const f = await geocodeCity(city);
    if (!f) return send(404, { error: 'not_found', message: 'Город не найден.' });
    lat = f.lat; lon = f.lon; place = f.name;
  }
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return send(400, { error: 'bad_request', message: 'Нужны q или lat и lon.' });
  const days = Math.min(Math.max(Number(q.get('days')) || 7, 1), 16);
  const api = 'https://api.open-meteo.com/v1/forecast'
    + `?latitude=${lat}&longitude=${lon}`
    + '&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m'
    + '&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max'
    + `&timezone=auto&wind_speed_unit=ms&forecast_days=${days}`;
  try {
    const r = await fetch(api, { signal: AbortSignal.timeout(8000) });
    if (!r.ok) return send(502, { error: 'upstream_error', message: 'Погодный сервис не ответил.' });
    const data = await r.json();
    if (place) data.honer_place = place;
    return send(200, data);
  } catch {
    return send(502, { error: 'upstream_error', message: 'Погодный сервис не ответил.' });
  }
}

// HTTPS, если заданы ключ и сертификат (base64 в переменных окружения сервера — не в коде).
// Приложение доверяет только этому сертификату (закреплён по ключу), поэтому свой сертификат безопасен.
const tlsKey = process.env.TLS_KEY_B64 ? Buffer.from(process.env.TLS_KEY_B64, 'base64') : null;
const tlsCert = process.env.TLS_CERT_B64 ? Buffer.from(process.env.TLS_CERT_B64, 'base64') : null;
const createServer = (handler) => (tlsKey && tlsCert)
  ? require('node:https').createServer({ key: tlsKey, cert: tlsCert }, handler)
  : http.createServer(handler);

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/relay-health') {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({ ok: true, relay: true, fly: true }));
  }
  if (url.pathname === '/v1/weather' && req.method === 'GET') return weather(url, res);
  proxy.web(req, res);
});

// WebSocket (/v1/ws): апгрейд проксируется на Railway.
server.on('upgrade', (req, socket, head) => {
  proxy.ws(req, socket, head);
  socket.on('error', () => { try { socket.destroy(); } catch { /* */ } });
});

server.headersTimeout = 0;
server.requestTimeout = 0;
server.keepAliveTimeout = 75_000;

// Само-публикация адреса: релей сообщает свой публичный адрес в discovery (Cloudflare KV) при старте и
// каждые 5 минут. Приложение читает этот адрес — поэтому смена адреса пода (перезапуск RunPod) НЕ ломает
// приложение и работает даже при выключенном компьютере хозяина.
function publicPort() {
  // RunPod задаёт публичный порт для проброшенного TCP-порта. Имя переменной может отличаться,
  // поэтому берём RUNPOD_TCP_PORT_8080, иначе любой RUNPOD_TCP_PORT_*, иначе PUBLIC_PORT.
  if (process.env.RUNPOD_TCP_PORT_8080) return process.env.RUNPOD_TCP_PORT_8080;
  const key = Object.keys(process.env).find((k) => /^RUNPOD_TCP_PORT_\d+$/.test(k));
  if (key) return process.env[key];
  return process.env.PUBLIC_PORT;
}

async function publishAddress() {
  const ip = process.env.RUNPOD_PUBLIC_IP;
  const port = publicPort();
  const disc = process.env.DISCOVERY_URL;
  const secret = process.env.RELAY_SECRET;
  if (!ip || !port || !disc || !secret) {
    console.log('publish skipped (missing)', { ip: !!ip, port: !!port, disc: !!disc, secret: !!secret });
    return;
  }
  const myUrl = `https://${ip}:${port}`;
  try {
    const r = await fetch(disc.replace(/\/+$/, '') + '/relay-register', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-relay-secret': secret },
      body: JSON.stringify({ url: myUrl }),
      signal: AbortSignal.timeout(10_000),
    });
    console.log('publish relay address', myUrl, r.status);
  } catch (e) { console.log('publish failed:', e.message); }
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`honer relay on :${PORT} (${tlsKey ? 'https' : 'http'}) -> ${UPSTREAM}`);
  publishAddress();
  setInterval(publishAddress, 5 * 60_000);
});
