// Бизнес-логика чатов на Deno KV: сообщения, реакции, закрепы, удаление/очистка, прочтение, сериализация.
import { badRequest, conflict, forbidden, notFound } from "./errors.ts";
import { getDevice, iso, type Kv, listAdmins } from "./kv.ts";
import type { Hub } from "./hub.ts";
import type { MediaStore, MediaRow } from "./media.ts";

export const ADMIN_CHAT_TITLE = "Администратор";

const COL = {
  user: { read: "userReadSeq", readId: "userReadMessageId", cleared: "userClearedSeq" },
  admin: { read: "adminReadSeq", readId: "adminReadMessageId", cleared: "adminClearedSeq" },
} as const;
const ATTACHMENT_KINDS = new Set(["image", "video", "audio", "voice", "file"]);

type Side = "user" | "admin";
type Sender = "user" | "admin" | "ai";

export interface Chat {
  id: string;
  deviceId: string;
  kind: string;
  aiEnabled: boolean;
  pinnedMessageId: string | null;
  userReadSeq: number;
  adminReadSeq: number;
  userReadMessageId: string | null;
  adminReadMessageId: string | null;
  userClearedSeq: number;
  adminClearedSeq: number;
  createdAt: number;
  lastMessageAt: number | null;
  seq: number;
}
export interface Message {
  id: string;
  clientId: string;
  chatId: string;
  seq: number;
  sender: Sender;
  text: string;
  attachments: unknown[];
  replyTo: string | null;
  createdAt: number;
  editedAt: number | null;
  deleted: boolean;
}

export function createChatService(
  { kv, hub, push, media }: {
    kv: Kv;
    hub: Hub;
    push: { notifyDevice: (id: string, p: { type: string; chatId: string | null; title?: string; body?: string }) => void } | null;
    media: MediaStore | null;
  },
) {
  // deno-lint-ignore no-explicit-any
  const listeners: ((chat: Chat, message: any, row: Message) => void)[] = [];
  let reactionOrd = Date.now() * 1000; // монотонный порядок реакций (устойчив к одинаковому createdAt в одну мс)

  const getChat = (chatId: string) => kv.get<Chat>(["chat", chatId]).then((r) => r.value);
  async function getDeviceChat(deviceId: string): Promise<Chat | null> {
    const id = (await kv.get<string>(["chatByDevice", deviceId])).value;
    return id ? getChat(id) : null;
  }

  async function createChatForDevice(deviceId: string): Promise<Chat> {
    const existing = await getDeviceChat(deviceId);
    if (existing) return existing;
    const chat: Chat = {
      id: crypto.randomUUID(),
      deviceId,
      kind: "admin",
      aiEnabled: false,
      pinnedMessageId: null,
      userReadSeq: 0,
      adminReadSeq: 0,
      userReadMessageId: null,
      adminReadMessageId: null,
      userClearedSeq: 0,
      adminClearedSeq: 0,
      createdAt: Date.now(),
      lastMessageAt: null,
      seq: 0,
    };
    const res = await kv.atomic()
      .check({ key: ["chatByDevice", deviceId], versionstamp: null })
      .set(["chat", chat.id], chat)
      .set(["chatByDevice", deviceId], chat.id)
      .commit();
    if (!res.ok) return (await getDeviceChat(deviceId))!;
    return chat;
  }

  const getMessageById = (id: string) => kv.get<Message>(["msg", id]).then((r) => r.value);

  async function getMessageRow(chat: Chat, messageId: string): Promise<Message> {
    const row = await getMessageById(messageId);
    if (!row || row.chatId !== chat.id) throw notFound("Сообщение не найдено");
    return row;
  }

  async function isHidden(chatId: string, side: Side, messageId: string): Promise<boolean> {
    return Boolean((await kv.get(["hidden", chatId, side, messageId])).value);
  }

  /** Видимые стороне сообщения (учёт «очистить у себя» и «удалить у себя»), новые первыми. */
  async function visibleRows(chat: Chat, side: Side, { beforeSeq = null, limit = 50 }: { beforeSeq?: number | null; limit?: number } = {}): Promise<Message[]> {
    const cleared = Number(chat[COL[side].cleared]) || 0;
    const selector: Deno.KvListSelector = beforeSeq !== null
      ? { prefix: ["chatMsg", chat.id], end: ["chatMsg", chat.id, beforeSeq] }
      : { prefix: ["chatMsg", chat.id] };
    const out: Message[] = [];
    for await (const e of kv.list<string>(selector, { reverse: true })) {
      const seq = Number(e.key[2]);
      if (seq <= cleared) break;
      if (await isHidden(chat.id, side, e.value)) continue;
      const m = await getMessageById(e.value);
      if (m) out.push(m);
      if (out.length >= limit) break;
    }
    return out;
  }

  async function unreadCount(chat: Chat, side: Side): Promise<number> {
    const from = Math.max(Number(chat[COL[side].read]) || 0, Number(chat[COL[side].cleared]) || 0);
    let c = 0;
    for await (const e of kv.list<string>({ prefix: ["chatMsg", chat.id] }, { reverse: true })) {
      const seq = Number(e.key[2]);
      if (seq <= from) break;
      const m = await getMessageById(e.value);
      if (!m || m.deleted) continue;
      if (await isHidden(chat.id, side, m.id)) continue;
      const match = side === "user" ? m.sender !== "user" : m.sender === "user";
      if (match) c += 1;
    }
    return c;
  }

  async function reactionsFor(messageId: string): Promise<Record<string, string[]>> {
    const entries: { who: string; emoji: string; ord: number }[] = [];
    for await (const e of kv.list<{ emoji: string; createdAt: number; ord?: number }>({ prefix: ["reaction", messageId] })) {
      entries.push({ who: String(e.key[2]), emoji: e.value.emoji, ord: e.value.ord ?? e.value.createdAt });
    }
    entries.sort((a, b) => a.ord - b.ord);
    const map: Record<string, string[]> = {};
    for (const r of entries) (map[r.emoji] ||= []).push(r.who);
    return map;
  }

  function toMessage(chat: Chat, r: Message, reactions: Record<string, string[]>) {
    const seq = Number(r.seq);
    const readByPeer = r.sender === "user" ? seq <= Number(chat.adminReadSeq) : seq <= Number(chat.userReadSeq);
    return {
      id: r.id,
      clientId: r.clientId,
      chatId: r.chatId,
      sender: r.sender,
      text: r.text || "",
      attachments: r.deleted ? [] : (r.attachments || []),
      replyTo: r.replyTo || null,
      createdAt: iso(r.createdAt),
      editedAt: iso(r.editedAt),
      deleted: !!r.deleted,
      reactions,
      pinned: !!chat.pinnedMessageId && chat.pinnedMessageId === r.id,
      readByPeer,
    };
  }

  async function serializeMessages(chat: Chat, rows: Message[]) {
    const out = [];
    for (const r of rows) out.push(toMessage(chat, r, await reactionsFor(r.id)));
    return out;
  }
  async function serializeOne(chat: Chat, row: Message) {
    return (await serializeMessages(chat, [row]))[0];
  }

  async function serializeChat(chat: Chat, side: Side, device: { displayName?: string | null; deviceName?: string | null; deviceModel?: string | null; lastSeenAt?: number | null } | null = null) {
    const [last] = await visibleRows(chat, side, { limit: 1 });
    const lastMessage = last ? await serializeOne(chat, last) : null;
    let title = ADMIN_CHAT_TITLE;
    let peer: { state: string; lastSeen: string | null };
    if (side === "admin") {
      const d = device || (await getDevice(kv, chat.deviceId));
      title = d?.displayName || d?.deviceName || d?.deviceModel || "Пользователь";
      peer = hub.devicePresence(chat.deviceId, d?.lastSeenAt ?? null);
    } else {
      let maxSeen: number | null = null;
      for (const a of await listAdmins(kv)) if (a.lastSeenAt && (maxSeen == null || a.lastSeenAt > maxSeen)) maxSeen = a.lastSeenAt;
      peer = hub.adminPresence(maxSeen);
    }
    return {
      id: chat.id,
      kind: chat.kind || "admin",
      deviceId: chat.deviceId,
      title,
      aiEnabled: !!chat.aiEnabled,
      pinnedMessageId: chat.pinnedMessageId || null,
      lastMessage,
      unread: await unreadCount(chat, side),
      peerTyping: hub.peerTyping(chat, side),
      peerReadUpTo: (side === "user" ? chat.adminReadMessageId : chat.userReadMessageId) || null,
      peerLastSeen: peer.lastSeen,
      peerPresence: peer.state,
    };
  }

  async function listMessages(chat: Chat, side: Side, { before = null, limit = 50 }: { before?: string | null; limit?: number } = {}) {
    let beforeSeq: number | null = null;
    if (before) {
      const b = await getMessageById(before);
      if (!b || b.chatId !== chat.id) throw notFound("Сообщение before не найдено");
      beforeSeq = Number(b.seq);
    }
    const rows = await visibleRows(chat, side, { beforeSeq, limit });
    rows.reverse();
    return serializeMessages(chat, rows);
  }

  async function resolveAttachments(chat: Chat, refs: { id: string; kind?: string; durationMs?: number | null; width?: number | null; height?: number | null }[], uploader: { kind: string; id: string | null }) {
    const out = [];
    for (const ref of refs || []) {
      const m = (await kv.get<MediaRow>(["media", ref.id])).value;
      if (!m) throw badRequest("Вложение не найдено");
      const own = m.uploaderKind === uploader.kind && (uploader.kind === "admin" || m.uploaderId === uploader.id);
      const linked = own || Boolean((await kv.get(["mediaLink", chat.id, m.id])).value);
      if (!linked) throw forbidden("Нет доступа к вложению");
      out.push({
        id: m.id,
        kind: ATTACHMENT_KINDS.has(ref.kind || "") ? ref.kind : m.kind,
        name: m.name,
        mime: m.mime,
        size: Number(m.size),
        durationMs: ref.durationMs ?? m.durationMs ?? null,
        width: ref.width ?? m.width ?? null,
        height: ref.height ?? m.height ?? null,
        url: `/v1/media/${m.id}`,
      });
    }
    return out;
  }

  async function sendMessage(
    chat: Chat,
    { sender, clientId, text = "", attachments = [], replyTo = null, uploader = null }: {
      sender: Sender;
      clientId: string;
      text?: string;
      attachments?: { id: string; kind?: string; durationMs?: number | null; width?: number | null; height?: number | null }[];
      replyTo?: string | null;
      uploader?: { kind: string; id: string | null } | null;
    },
  // deno-lint-ignore no-explicit-any
  ): Promise<{ message: any; created: boolean }> {
    text = String(text || "");
    if (!text.trim() && !(attachments && attachments.length)) throw badRequest("Пустое сообщение");

    const existingId = (await kv.get<string>(["chatMsgClient", chat.id, clientId])).value;
    if (existingId) {
      const existing = await getMessageById(existingId);
      if (existing) {
        if (existing.sender !== sender) throw conflict("clientId уже используется");
        return { message: await serializeOne(chat, existing), created: false };
      }
    }
    if (replyTo) await getMessageRow(chat, replyTo);
    const atts = await resolveAttachments(chat, attachments, uploader || { kind: sender === "user" ? "device" : "admin", id: null });

    const id = crypto.randomUUID();
    const now = Date.now();
    // Атомарно занимаем seq и создаём сообщение (идемпотентность по clientId).
    const fresh = (await kv.get<Chat>(["chat", chat.id]));
    const chatVal = fresh.value!;
    const seq = Number(chatVal.seq) + 1;
    const msg: Message = { id, clientId, chatId: chat.id, seq, sender, text, attachments: atts, replyTo: replyTo || null, createdAt: now, editedAt: null, deleted: false };
    const res = await kv.atomic()
      .check(fresh)
      .check({ key: ["chatMsgClient", chat.id, clientId], versionstamp: null })
      .set(["chat", chat.id], { ...chatVal, seq, lastMessageAt: now })
      .set(["msg", id], msg)
      .set(["chatMsg", chat.id, seq], id)
      .set(["chatMsgClient", chat.id, clientId], id)
      .commit();
    if (!res.ok) {
      // Гонка: либо clientId занят, либо чат изменился — отдаём победителя, если он есть, иначе повторяем.
      const winnerId = (await kv.get<string>(["chatMsgClient", chat.id, clientId])).value;
      if (winnerId) {
        const row = await getMessageById(winnerId);
        return { message: await serializeOne(chat, row!), created: false };
      }
      return sendMessage(chat, { sender, clientId, text, attachments, replyTo, uploader });
    }
    for (const a of atts) {
      await kv.set(["mediaLink", chat.id, a.id], 1);
      await kv.set(["mediaLinkByMedia", a.id, chat.id], 1);
    }

    const updatedChat = { ...chatVal, seq, lastMessageAt: now };
    const message = await serializeOne(updatedChat, msg);
    if (sender !== "ai") hub.setTyping(updatedChat, sender, false);
    hub.sendToChat(updatedChat, { t: "message", chatId: chat.id, message });

    if (sender !== "user") {
      push?.notifyDevice(chat.deviceId, {
        type: "message",
        chatId: chat.id,
        title: sender === "ai" ? "Honer AI" : ADMIN_CHAT_TITLE,
        body: previewText(message as { text?: string; attachments?: { kind?: string }[] }),
      });
    }
    for (const fn of listeners) {
      try {
        fn(updatedChat, message, msg);
      } catch { /* listener error */ }
    }
    return { message, created: true };
  }

  async function broadcastUpdated(chatId: string, messageId: string) {
    const chat = await getChat(chatId);
    const row = await getMessageById(messageId);
    if (!chat || !row) return null;
    const message = await serializeOne(chat, row);
    hub.sendToChat(chat, { t: "message.updated", chatId, message });
    return message;
  }

  async function editMessage(chat: Chat, side: Side, messageId: string, text: string) {
    const row = await getMessageRow(chat, messageId);
    if (row.sender !== side) throw forbidden("Можно редактировать только свои сообщения");
    if (row.deleted) throw badRequest("Сообщение удалено");
    if (!String(text).trim() && !(row.attachments || []).length) throw badRequest("Пустое сообщение");
    await kv.set(["msg", row.id], { ...row, text, editedAt: Date.now() });
    return broadcastUpdated(chat.id, row.id);
  }

  async function react(chat: Chat, side: Side, messageId: string, emoji: string | null) {
    const row = await getMessageRow(chat, messageId);
    if (row.deleted) throw badRequest("Сообщение удалено");
    if (emoji) await kv.set(["reaction", row.id, side], { emoji, chatId: chat.id, createdAt: Date.now(), ord: reactionOrd++ });
    else await kv.delete(["reaction", row.id, side]);
    return broadcastUpdated(chat.id, row.id);
  }

  async function pin(chat: Chat, messageId: string, pinned: boolean) {
    const row = await getMessageRow(chat, messageId);
    if (row.deleted && pinned) throw badRequest("Сообщение удалено");
    const prev = chat.pinnedMessageId;
    if (pinned) {
      await kv.set(["chat", chat.id], { ...chat, pinnedMessageId: row.id });
      if (prev && prev !== row.id) await broadcastUpdated(chat.id, prev);
    } else if (prev === row.id) {
      await kv.set(["chat", chat.id], { ...chat, pinnedMessageId: null });
    }
    return broadcastUpdated(chat.id, row.id);
  }

  async function deleteMessage(chat: Chat, side: Side, messageId: string, scope: string) {
    const row = await getMessageRow(chat, messageId);
    if (scope === "me") {
      await kv.set(["hidden", chat.id, side, row.id], 1);
      return;
    }
    if (side === "user" && row.sender !== "user") throw forbidden("Удалить у всех можно только свои сообщения");
    if (row.deleted) return;
    await kv.set(["msg", row.id], { ...row, deleted: true, text: "", attachments: [] });
    for await (const e of kv.list({ prefix: ["reaction", row.id] })) await kv.delete(e.key);
    if (chat.pinnedMessageId === row.id) await kv.set(["chat", chat.id], { ...(await getChat(chat.id))!, pinnedMessageId: null });
    await broadcastUpdated(chat.id, row.id);
  }

  async function clearChat(chat: Chat, side: Side, scope: string) {
    if (scope === "me") {
      let maxSeq = 0;
      for await (const e of kv.list<string>({ prefix: ["chatMsg", chat.id] }, { reverse: true, limit: 1 })) maxSeq = Number(e.key[2]);
      await kv.set(["chat", chat.id], { ...(await getChat(chat.id))!, [COL[side].cleared]: maxSeq });
      const frame = { t: "chat.cleared", chatId: chat.id };
      if (side === "user") hub.sendToDevice(chat.deviceId, frame);
      else hub.sendToAdmins(frame);
      return;
    }
    if (side !== "admin") throw forbidden("Очистить чат у всех может только администратор");
    const mediaIds: string[] = [];
    for await (const e of kv.list({ prefix: ["mediaLink", chat.id] })) {
      const mediaId = String(e.key[2]);
      mediaIds.push(mediaId);
      await kv.delete(e.key);
      await kv.delete(["mediaLinkByMedia", mediaId, chat.id]);
    }
    for await (const e of kv.list<string>({ prefix: ["chatMsg", chat.id] })) {
      const mid = e.value;
      for await (const r of kv.list({ prefix: ["reaction", mid] })) await kv.delete(r.key);
      await kv.delete(["msg", mid]);
      await kv.delete(e.key);
    }
    for await (const e of kv.list({ prefix: ["chatMsgClient", chat.id] })) await kv.delete(e.key);
    for await (const e of kv.list({ prefix: ["hidden", chat.id] })) await kv.delete(e.key);
    await kv.set(["chat", chat.id], { ...(await getChat(chat.id))!, pinnedMessageId: null, lastMessageAt: null });
    if (media) await media.removeOrphans(mediaIds);
    hub.sendToChat(chat, { t: "chat.cleared", chatId: chat.id });
  }

  async function markRead(chat: Chat, side: Side, messageId: string): Promise<boolean> {
    const row = await getMessageRow(chat, messageId);
    const seq = Number(row.seq);
    const fresh = (await getChat(chat.id))!;
    if (seq <= Number(fresh[COL[side].read])) return false;
    await kv.set(["chat", chat.id], { ...fresh, [COL[side].read]: seq, [COL[side].readId]: row.id });
    hub.sendToChat(chat, { t: "read", chatId: chat.id, who: side, messageId: row.id });
    return true;
  }

  async function setAiEnabled(chat: Chat, enabled: boolean): Promise<Chat> {
    if (!!chat.aiEnabled === enabled) return chat;
    await kv.set(["chat", chat.id], { ...chat, aiEnabled: enabled });
    const updated = (await getChat(chat.id))!;
    await sendMessage(updated, {
      sender: "ai",
      clientId: `ai-${crypto.randomUUID()}`,
      text: enabled
        ? "Honer AI присоединился к чату. Упомяните «Honer» или «ИИ», чтобы задать мне вопрос."
        : "Honer AI покинул чат.",
    });
    return (await getChat(chat.id))!;
  }

  return {
    listeners,
    getChat,
    getDeviceChat,
    createChatForDevice,
    getMessageRow,
    visibleRows,
    listMessages,
    serializeChat,
    serializeMessages,
    sendMessage,
    editMessage,
    react,
    pin,
    deleteMessage,
    clearChat,
    markRead,
    setAiEnabled,
    unreadCount,
  };
}

export type ChatService = ReturnType<typeof createChatService>;

export function previewText(message: { text?: string; attachments?: { kind?: string }[] }): string {
  if (message.text && message.text.trim()) return message.text.length > 200 ? `${message.text.slice(0, 200)}…` : message.text;
  const a = message.attachments?.[0];
  const labels: Record<string, string> = { image: "📷 Фото", video: "🎬 Видео", audio: "🎵 Аудио", voice: "🎤 Голосовое сообщение", file: "📎 Файл" };
  return a ? labels[a.kind || ""] || "📎 Вложение" : "";
}
