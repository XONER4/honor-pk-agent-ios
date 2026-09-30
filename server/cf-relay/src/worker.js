// Honer Cloud relay на Cloudflare Workers.
// Провайдеры/операторы (МТС и часть домашних сетей) блокируют прямой доступ к Railway.
// Этот воркер живёт на сети Cloudflare (обычно доступной) и прозрачно пересылает всё на Railway:
// обычные запросы, поток ответа ИИ (SSE), WebSocket (онлайн/печатает/новые сообщения). Погоду отдаёт сам.
// Бесплатный тариф Cloudflare Workers — 100 000 запросов в день (много запаса против лимита Deno).

const DEFAULT_UPSTREAM = 'https://honor-pk-agent-ios-production.up.railway.app';

// Политика конфиденциальности для магазинов приложений (RuStore и др.). Контакт замените на свой.
const PRIVACY_HTML = `<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Политика конфиденциальности — Honer AI</title>
<style>body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:760px;margin:0 auto;padding:24px;line-height:1.6;color:#1a1a1a;background:#fff}h1{font-size:24px}h2{font-size:18px;margin-top:28px}code{background:#f0f0f0;padding:1px 5px;border-radius:4px}small{color:#666}</style></head><body>
<h1>Политика конфиденциальности приложения «Honer AI»</h1>
<p><small>Дата последнего обновления: 30.09.2026</small></p>
<p>Настоящая Политика описывает, какие данные обрабатывает мобильное приложение «Honer AI» (далее — «Приложение») и как они используются. Устанавливая и используя Приложение, вы соглашаетесь с настоящей Политикой.</p>

<h2>1. Какие данные мы обрабатываем</h2>
<ul>
<li><b>Технические данные устройства:</b> модель, название и версия ОС устройства, версия приложения, стабильный идентификатор устройства — чтобы отличать устройства, обеспечивать работу и обновления, не создавая дублей.</li>
<li><b>Профиль (по желанию):</b> отображаемое имя и дата рождения, если вы их указали.</li>
<li><b>Сообщения:</b> текст и вложения, которые вы отправляете нейросети и в чат поддержки, — чтобы формировать ответы и оказывать поддержку.</li>
<li><b>Статистика использования:</b> число отправленных сообщений и время в приложении — для работы сервиса и статистики.</li>
<li><b>Токен уведомлений</b> (если доступно) — чтобы присылать push-уведомления.</li>
</ul>
<p>Приложение <b>не собирает</b> геолокацию для передачи третьим лицам, не читает ваши контакты без вашего действия и не отслеживает вас в других приложениях.</p>

<h2>2. Как используются данные</h2>
<p>Данные используются исключительно для работы Приложения: ответы нейросети, чат с поддержкой, уведомления, обновления и техническая стабильность. Мы <b>не продаём</b> ваши данные и не передаём их для рекламы.</p>

<h2>3. Обработка нейросетью</h2>
<p>Текст ваших запросов к нейросети передаётся на наш сервер и провайдеру модели ИИ (DeepSeek) для формирования ответа. Не отправляйте нейросети данные, которые считаете строго конфиденциальными (пароли, банковские реквизиты и т.п.).</p>

<h2>4. Хранение и передача</h2>
<p>Данные хранятся на серверах, используемых для работы сервиса. Соединение защищено шифрованием (TLS). Мы принимаем разумные меры для защиты данных от несанкционированного доступа.</p>

<h2>5. Ваши права</h2>
<p>Вы можете очистить историю чата в приложении, а также запросить удаление ваших данных, написав нам. После удаления приложения и данных ваши сведения перестают обрабатываться.</p>

<h2>6. Дети</h2>
<p>Приложение не предназначено для детей младше возраста, установленного возрастным рейтингом в магазине приложений.</p>

<h2>7. Изменения</h2>
<p>Мы можем обновлять эту Политику. Актуальная версия всегда доступна по этому адресу.</p>

<h2>8. Контакты</h2>
<p>По вопросам обработки данных: <b>[укажите ваш контактный email]</b>.</p>
</body></html>`;

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

    // Политика конфиденциальности (публичная страница для магазинов приложений: RuStore и др.).
    if (url.pathname === '/privacy') {
      return new Response(PRIVACY_HTML, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'public, max-age=3600' } });
    }

    // Обнаружение адреса ретранслятора: приложение спрашивает здесь текущий адрес RunPod-релея.
    // Так смена адреса пода (перезапуск) не ломает приложение — оно всегда узнаёт актуальный адрес.
    if (url.pathname === '/relay-endpoint' && request.method === 'GET') {
      let relay = null;
      try { relay = env.RELAY_KV ? await env.RELAY_KV.get('relay') : null; } catch { /* KV недоступен */ }
      return Response.json({ url: relay || env.RELAY_FALLBACK || '' }, {
        headers: { 'cache-control': 'no-store' },
      });
    }
    // Ретранслятор публикует сюда свой адрес при старте (защищено общим секретом).
    if (url.pathname === '/relay-register' && request.method === 'POST') {
      if (!env.RELAY_SECRET || request.headers.get('x-relay-secret') !== env.RELAY_SECRET) {
        return Response.json({ error: 'forbidden' }, { status: 403 });
      }
      let body = {};
      try { body = await request.json(); } catch { /* пусто */ }
      const u = String(body.url || '').trim();
      if (!/^https:\/\/[\w.-]+:\d+$/.test(u)) return Response.json({ error: 'bad_url' }, { status: 400 });
      try { await env.RELAY_KV.put('relay', u); } catch { return Response.json({ error: 'kv' }, { status: 500 }); }
      return Response.json({ ok: true, url: u });
    }

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
