// WebSocket /v1/ws?token=<device|admin>: presence, typing, read receipts, ping/pong. Рассылки делает Hub.
import { iso } from "./kv.ts";
import { isUuid, jsonResponse, type ReqCtx } from "./http.ts";
import type { AppCtx } from "./app.ts";
import type { Principal } from "./auth.ts";

const STATES = new Set(["foreground", "background"]);

export async function handleWs(app: AppCtx, ctx: ReqCtx): Promise<Response> {
  const token = ctx.query.get("token") || (ctx.header("authorization")?.replace(/^Bearer\s+/i, "").trim() ?? null);
  const resolved = await app.auth.resolve(token);
  if (!resolved) return jsonResponse({ error: "unauthorized", message: "Требуется авторизация" }, 401);
  const who: Principal = resolved;

  const { socket, response } = Deno.upgradeWebSocket(ctx.req);
  const { hub, chats } = app;

  const deviceChatId = who.kind === "device" ? chats.getDeviceChat(who.device.id).then((c) => c?.id || null, () => null) : Promise.resolve(null);

  let queue: Promise<unknown> = Promise.resolve();

  socket.onopen = () => {
    if (who.kind === "device" && who.device.blocked) {
      try {
        socket.send(JSON.stringify({ t: "blocked", message: who.device.blockReason || "", until: iso(who.device.blockedUntil) }));
      } catch { /* ignore */ }
      socket.close(4003, "blocked");
      return;
    }
    if (who.kind === "device") hub.addDeviceSocket(who.device.id, socket);
    else hub.addAdminSocket(who.admin.id, socket);
  };

  socket.onclose = () => hub.removeSocket(socket);
  socket.onerror = () => hub.removeSocket(socket);

  socket.onmessage = (ev: MessageEvent) => {
    if (typeof ev.data !== "string") return;
    if (ev.data.length > 64 * 1024) return;
    let frame: { t?: unknown; [k: string]: unknown };
    try {
      frame = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (!frame || typeof frame !== "object" || typeof frame.t !== "string") return;
    hub.touch(socket);
    queue = queue.then(() => handle(frame as { t: string; [k: string]: unknown })).catch(() => {});
  };

  async function chatFor(chatId: unknown) {
    if (typeof chatId !== "string" || !isUuid(chatId)) return null;
    if (who.kind === "device" && chatId !== (await deviceChatId)) return null;
    return chats.getChat(chatId);
  }

  async function handle(frame: { t: string; [k: string]: unknown }) {
    const side = who.kind === "device" ? "user" : "admin";
    switch (frame.t) {
      case "ping":
        hub.send(socket, { t: "pong" });
        break;
      case "presence":
        if (!STATES.has(String(frame.state))) return;
        if (who.kind === "device") hub.setDeviceState(who.device.id, String(frame.state));
        else hub.setAdminState(socket, String(frame.state));
        break;
      case "typing": {
        const chat = await chatFor(frame.chatId);
        if (chat) hub.setTyping(chat, side, frame.typing === true);
        break;
      }
      case "read": {
        const chat = await chatFor(frame.chatId);
        if (chat && typeof frame.messageId === "string" && isUuid(frame.messageId)) {
          await chats.markRead(chat, side, frame.messageId);
        }
        break;
      }
      default:
        break;
    }
  }

  return response;
}
