// Маршруты чатов. Регистрируются дважды: /v1/chats (side=user) и /v1/admin/chats (side=admin).
import { badRequest, notFound } from "./errors.ts";
import { getDevice, type Kv, listAll } from "./kv.ts";
import type { Chat } from "./chat-service.ts";
import { guard, type AppCtx } from "./app.ts";
import { enumQuery, intQuery, isUuid, jsonResponse, type ReqCtx, reqUuidParam, Router } from "./http.ts";

type Side = "user" | "admin";

function validateAttachments(atts: unknown): void {
  if (atts === undefined) return;
  if (!Array.isArray(atts) || atts.length > 10) throw badRequest("attachments");
  for (const a of atts) {
    if (!a || typeof a !== "object" || !isUuid((a as { id: unknown }).id)) throw badRequest("attachment.id");
    const kind = (a as { kind?: unknown }).kind;
    if (kind !== undefined && !["image", "video", "audio", "voice", "file"].includes(String(kind))) throw badRequest("attachment.kind");
  }
}

export function registerChatRoutes(app: AppCtx, router: Router, prefix: string, side: Side) {
  const { kv, chats } = app as { kv: Kv } & AppCtx;
  const need: ("device" | "admin")[] = side === "admin" ? ["admin"] : ["device"];

  async function loadChat(ctx: ReqCtx): Promise<Chat> {
    const chatId = reqUuidParam(ctx, "chatId");
    const chat = await chats.getChat(chatId);
    if (!chat || (side === "user" && chat.deviceId !== ctx.device!.id)) throw notFound("Чат не найден");
    return chat;
  }
  const uploader = (ctx: ReqCtx) => (side === "admin" ? { kind: "admin", id: ctx.admin!.id } : { kind: "device", id: ctx.device!.id });

  router.get(prefix, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    if (side === "user") {
      const chat = (await chats.getDeviceChat(ctx.device!.id)) || (await chats.createChatForDevice(ctx.device!.id));
      return jsonResponse([await chats.serializeChat(chat, "user")]);
    }
    const rows = await listAll<Chat>(kv, ["chat"]);
    const withDevice = await Promise.all(rows.map(async (c) => ({ c, d: await getDevice(kv, c.deviceId) })));
    withDevice.sort((a, b) => (b.c.lastMessageAt || b.c.createdAt) - (a.c.lastMessageAt || a.c.createdAt));
    const out = [];
    for (const { c, d } of withDevice) out.push(await chats.serializeChat(c, "admin", d));
    return jsonResponse(out);
  });

  router.get(`${prefix}/:chatId/messages`, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    const chat = await loadChat(ctx);
    const before = ctx.query.get("before");
    if (before !== null && !isUuid(before)) throw badRequest("before");
    const limit = intQuery(ctx, "limit", 50, 1, 200);
    return jsonResponse(await chats.listMessages(chat, side, { before: before || null, limit }));
  });

  router.post(`${prefix}/:chatId/messages`, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    const chat = await loadChat(ctx);
    const b = await ctx.json();
    if (typeof b.clientId !== "string" || b.clientId.length < 1 || b.clientId.length > 100) throw badRequest("clientId");
    if (b.text !== undefined && (typeof b.text !== "string" || b.text.length > 10_000)) throw badRequest("text");
    validateAttachments(b.attachments);
    if (b.replyTo !== undefined && b.replyTo !== null && !isUuid(b.replyTo)) throw badRequest("replyTo");
    const { message } = await chats.sendMessage(chat, {
      sender: side,
      clientId: b.clientId,
      text: (b.text as string) ?? "",
      attachments: (b.attachments ?? []) as { id: string; kind?: string; durationMs?: number | null; width?: number | null; height?: number | null }[],
      replyTo: (b.replyTo as string) || null,
      uploader: uploader(ctx),
    });
    return jsonResponse(message);
  });

  router.patch(`${prefix}/:chatId/messages/:id`, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    const chat = await loadChat(ctx);
    const id = reqUuidParam(ctx, "id");
    const b = await ctx.json();
    if (typeof b.text !== "string" || b.text.length > 10_000) throw badRequest("text");
    return jsonResponse(await chats.editMessage(chat, side, id, b.text));
  });

  router.post(`${prefix}/:chatId/messages/:id/reaction`, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    const chat = await loadChat(ctx);
    const id = reqUuidParam(ctx, "id");
    const b = await ctx.json();
    if (b.emoji !== null && (typeof b.emoji !== "string" || b.emoji.length < 1 || b.emoji.length > 32)) throw badRequest("emoji");
    return jsonResponse(await chats.react(chat, side, id, b.emoji || null));
  });

  router.post(`${prefix}/:chatId/messages/:id/pin`, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    const chat = await loadChat(ctx);
    const id = reqUuidParam(ctx, "id");
    const b = await ctx.json();
    if (typeof b.pinned !== "boolean") throw badRequest("pinned");
    return jsonResponse(await chats.pin(chat, id, b.pinned));
  });

  router.delete(`${prefix}/:chatId/messages/:id`, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    const chat = await loadChat(ctx);
    const id = reqUuidParam(ctx, "id");
    const scope = enumQuery(ctx, "scope", ["me", "everyone"], "me");
    await chats.deleteMessage(chat, side, id, scope);
    return jsonResponse({ ok: true });
  });

  router.post(`${prefix}/:chatId/clear`, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    const chat = await loadChat(ctx);
    const scope = enumQuery(ctx, "scope", ["me", "everyone"], "me");
    await chats.clearChat(chat, side, scope);
    return jsonResponse({ ok: true });
  });

  router.post(`${prefix}/:chatId/read`, async (ctx: ReqCtx) => {
    await guard(app, ctx, need);
    const chat = await loadChat(ctx);
    const b = await ctx.json();
    if (!isUuid(b.messageId)) throw badRequest("messageId");
    await chats.markRead(chat, side, b.messageId);
    return jsonResponse({ ok: true });
  });

  if (side === "admin") {
    router.post(`${prefix}/:chatId/ai`, async (ctx: ReqCtx) => {
      await guard(app, ctx, need);
      const chat = await loadChat(ctx);
      const b = await ctx.json();
      if (typeof b.enabled !== "boolean") throw badRequest("enabled");
      const updated = await chats.setAiEnabled(chat, b.enabled);
      return jsonResponse(await chats.serializeChat(updated, "admin"));
    });
  }
}
