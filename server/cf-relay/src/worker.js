// Honer Cloud relay на Cloudflare Workers.
// Провайдеры/операторы (МТС и часть домашних сетей) блокируют прямой доступ к Railway.
// Этот воркер живёт на сети Cloudflare (обычно доступной) и прозрачно пересылает всё на Railway:
// обычные запросы, поток ответа ИИ (SSE), WebSocket (онлайн/печатает/новые сообщения). Погоду отдаёт сам.
// Бесплатный тариф Cloudflare Workers — 100 000 запросов в день (много запаса против лимита Deno).

const DEFAULT_UPSTREAM = 'https://honor-pk-agent-ios-production.up.railway.app';

const HOP = new Set([
  'connection', 'keep-alive', 'proxy-connection', 'transfer-encoding', 'upgrade', 'te', 'trailer', 'host',
  'content-length',
]);

function forwardHeaders(source) {
  const out = new Headers();
  for (const [k, v] of source) if (!HOP.has(k.toLowerCase())) out.set(k, v);
  return out;
}

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

async function weather(url) {
  const q = url.searchParams;
  let lat = Number(q.get('lat')), lon = Number(q.get('lon')), place = '';
  const city = q.get('q');
  if (city) {
    const f = await geocodeCity(city);
    if (!f) return Response.json({ error: 'not_found', message: 'Город не найден.' }, { status: 404 });
    lat = f.lat; lon = f.lon; place = f.name;
  }
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return Response.json({ error: 'bad_request', message: 'Нужны q или lat и lon.' }, { status: 400 });
  const days = Math.min(Math.max(Number(q.get('days')) || 7, 1), 16);
  const api = 'https://api.open-meteo.com/v1/forecast'
    + `?latitude=${lat}&longitude=${lon}`
    + '&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m'
    + '&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max'
    + `&timezone=auto&wind_speed_unit=ms&forecast_days=${days}`;
  try {
    const r = await fetch(api, { signal: AbortSignal.timeout(8000) });
    if (!r.ok) return Response.json({ error: 'upstream_error', message: 'Погодный сервис не ответил.' }, { status: 502 });
    const data = await r.json();
    if (place) data.honer_place = place;
    return Response.json(data);
  } catch {
    return Response.json({ error: 'upstream_error', message: 'Погодный сервис не ответил.' }, { status: 502 });
  }
}

async function proxyWebSocket(request, url, upstream) {
  const wsUrl = upstream.replace(/^http/, 'ws') + url.pathname + url.search;
  let resp;
  try {
    resp = await fetch(wsUrl, { headers: request.headers });
  } catch {
    return new Response('relay: upstream ws error', { status: 502 });
  }
  const backend = resp.webSocket;
  if (!backend) return new Response('relay: upstream did not upgrade', { status: 502 });
  backend.accept();
  const pair = new WebSocketPair();
  const client = pair[0], server = pair[1];
  server.accept();
  const closeBoth = (code, reason) => {
    try { server.close(code, reason); } catch { /* */ }
    try { backend.close(code, reason); } catch { /* */ }
  };
  server.addEventListener('message', (e) => { try { backend.send(e.data); } catch { /* */ } });
  backend.addEventListener('message', (e) => { try { server.send(e.data); } catch { /* */ } });
  // Коды закрытия ≥3000 (например 4003 «заблокирован») пробрасываем без изменений.
  server.addEventListener('close', (e) => closeBoth(e.code >= 3000 ? e.code : 1000, e.reason));
  backend.addEventListener('close', (e) => closeBoth(e.code >= 3000 ? e.code : 1000, e.reason));
  server.addEventListener('error', () => closeBoth(1011, 'client error'));
  backend.addEventListener('error', () => closeBoth(1011, 'upstream error'));
  return new Response(null, { status: 101, webSocket: client });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const upstream = (env.UPSTREAM || DEFAULT_UPSTREAM).replace(/\/+$/, '');

    if (url.pathname === '/relay-health') return Response.json({ ok: true, relay: true, cf: true });
    if (url.pathname === '/v1/weather' && request.method === 'GET') return weather(url);
    if ((request.headers.get('Upgrade') || '').toLowerCase() === 'websocket') return proxyWebSocket(request, url, upstream);

    const init = { method: request.method, headers: forwardHeaders(request.headers), redirect: 'manual' };
    // Тело запроса буферизуем целиком. Приложение СЖИМАЕТ большие запросы (Content-Encoding: gzip),
    // чтобы пролезть под лимит оператора на размер загрузки к Cloudflare. Здесь распаковываем и отдаём
    // на Railway обычным телом (Railway ничего менять не нужно). Так большой запрос ИИ проходит без VPN.
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      const enc = (request.headers.get('content-encoding') || '').toLowerCase();
      if (enc.includes('gzip') && request.body) {
        init.body = await new Response(request.body.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer();
        init.headers.delete('content-encoding');
        init.headers.delete('content-length');
      } else {
        init.body = await request.arrayBuffer();
      }
    }
    try {
      const r = await fetch(upstream + url.pathname + url.search, init);
      const headers = new Headers();
      for (const [k, v] of r.headers) if (!HOP.has(k.toLowerCase())) headers.set(k, v);
      // Поток ответа (SSE ИИ, видео) уходит клиенту сразу.
      return new Response(r.body, { status: r.status, statusText: r.statusText, headers });
    } catch {
      return Response.json({ error: 'relay_unavailable', message: 'Сервер Honer AI временно недоступен. Повторите.' }, { status: 502 });
    }
  },
};
