// Honer Cloud relay для Deno Deploy.
// Railway недоступен из части российских сетей (МТС блокирует его адреса), а deno.dev открывается.
// Переходник пересылает на сервер Railway всё как есть: обычные запросы, поток ответа ИИ (SSE)
// и WebSocket (онлайн, «печатает», новые сообщения). Своих данных не хранит.

const UPSTREAM = (Deno.env.get("UPSTREAM") ?? "https://honor-pk-agent-ios-production.up.railway.app").replace(/\/+$/, "");
const UPSTREAM_WS = UPSTREAM.replace(/^http/, "ws");

// Заголовки, которые нельзя пересылать между соединениями.
const HOP = new Set([
  "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer", "host",
  "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions", "sec-websocket-accept",
]);

function forwardHeaders(source: Headers, clientIp: string | undefined): Headers {
  const out = new Headers();
  for (const [key, value] of source) if (!HOP.has(key.toLowerCase())) out.set(key, value);
  if (clientIp) out.set("x-forwarded-for", clientIp);
  return out;
}

function relayWebSocket(req: Request, url: URL): Response {
  const { socket: client, response } = Deno.upgradeWebSocket(req);
  const target = new WebSocket(UPSTREAM_WS + url.pathname + url.search);
  const pending: (string | ArrayBuffer)[] = [];
  const closeBoth = (code = 1000, reason = "") => {
    try { if (client.readyState <= 1) client.close(code, reason); } catch { /* уже закрыт */ }
    try { if (target.readyState <= 1) target.close(code, reason); } catch { /* уже закрыт */ }
  };
  target.binaryType = "arraybuffer";
  target.onopen = () => { for (const m of pending.splice(0)) target.send(m); };
  target.onmessage = (e) => { if (client.readyState === 1) client.send(e.data); };
  // 4003 — «заблокирован»: код закрытия приложение должно получить без изменений.
  target.onclose = (e) => closeBoth(e.code >= 3000 ? e.code : 1000, e.reason);
  target.onerror = () => closeBoth(1011, "upstream error");
  client.onmessage = (e) => {
    if (target.readyState === 1) target.send(e.data);
    else if (target.readyState === 0 && pending.length < 100) pending.push(e.data);
  };
  client.onclose = (e) => closeBoth(e.code >= 3000 ? e.code : 1000, e.reason);
  client.onerror = () => closeBoth(1011, "client error");
  return response;
}

Deno.serve(async (req, info) => {
  const url = new URL(req.url);
  const clientIp = (info.remoteAddr as Deno.NetAddr | undefined)?.hostname;
  if (req.headers.get("upgrade")?.toLowerCase() === "websocket") return relayWebSocket(req, url);
  if (url.pathname === "/relay-health") return Response.json({ ok: true, relay: true });

  const init: RequestInit = {
    method: req.method,
    headers: forwardHeaders(req.headers, clientIp),
    redirect: "manual",
  };
  if (req.method !== "GET" && req.method !== "HEAD") {
    init.body = req.body;
    // @ts-ignore: потоковое тело (загрузка фото/видео) без буферизации
    init.duplex = "half";
  }
  try {
    const upstream = await fetch(UPSTREAM + url.pathname + url.search, init);
    const headers = new Headers();
    for (const [key, value] of upstream.headers) if (!HOP.has(key.toLowerCase())) headers.set(key, value);
    // Поток ответа (SSE нейросети, видео по частям) уходит клиенту сразу, по мере прихода.
    return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers });
  } catch (_e) {
    return Response.json({ error: "relay_unavailable", message: "Сервер Honer AI временно недоступен. Повторите запрос." }, { status: 502 });
  }
});
