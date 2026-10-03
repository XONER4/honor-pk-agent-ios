// Бизнес-логика чатов: сообщения, реакции, закрепы, удаление/очистка, прочтение, сериализация моделей API.
// Используется HTTP-маршрутами (устройство и админ), WebSocket-обработчиком и групповым ИИ.
//
// Стороны (side): 'user' — владелец устройства, 'admin' — администратор(ы). Отправитель (sender): user | admin | ai.
import { randomUUID } from 'node:crypto';
import { badRequest, conflict, forbidden, notFound } from './errors.js';
import { iso, json, placeholders } from './db.js';

export const ADMIN_CHAT_TITLE = 'Администратор';

// Имена колонок по стороне — только из этого словаря (никогда из пользовательского ввода).
const COL = {
  user: { read: 'user_read_seq', readId: 'user_read_message_id', cleared: 'user_cleared_seq' },
  admin: { read: 'admin_read_seq', readId: 'admin_read_message_id', cleared: 'admin_cleared_seq' },
};
const ATTACHMENT_KINDS = new Set(['image', 'video', 'audio', 'voice', 'file']);

export function createChatService({ db, hub, push, media, logger }) {
  const listeners = []; // (chat, message) => void — новые сообщения (групповой ИИ)

  // ---------- выборки ----------

  const getChat = (chatId) => db.one('SELECT * FROM chats WHERE id = $1', [chatId]);
  const getDeviceChat = (deviceId) => db.one('SELECT * FROM chats WHERE device_id = $1', [deviceId]);

  async function createChatForDevice(deviceId) {
    const id = randomUUID();
    await db.query('INSERT INTO chats (id, device_id, kind, created_at) VALUES ($1, $2, $3, $4) ON CONFLICT (device_id) DO NOTHING',
      [id, deviceId, 'admin', new Date()]);
    return getDeviceChat(deviceId);
  }

  async function getMessageRow(chat, messageId) {
    const row = await db.one('SELECT * FROM messages WHERE id = $1 AND chat_id = $2', [messageId, chat.id]);
    if (!row) throw notFound('Сообщение не найдено');
    return row;
  }

  /** Видимые стороне side сообщения (с учётом «очистить у себя» и «удалить у себя»), новые первыми. */
  async function visibleRows(chat, side, { beforeSeq = null, limit = 50 } = {}) {
    const params = [chat.id, side, Number(chat[COL[side].cleared]) || 0];
    let extra = '';
    if (beforeSeq !== null) {
      params.push(beforeSeq);
      extra = ` AND m.seq < $${params.length}`;
    }
    params.push(limit);
    return db.many(
      `SELECT m.* FROM messages m
         LEFT JOIN message_hidden h ON h.message_id = m.id AND h.side = $2
        WHERE m.chat_id = $1 AND m.seq > $3 AND h.message_id IS NULL${extra}
        ORDER BY m.seq DESC LIMIT $${params.length}`,
      params,
    );
  }

  async function unreadCount(chat, side) {
    const from = Math.max(Number(chat[COL[side].read]) || 0, Number(chat[COL[side].cleared]) || 0);
    const senderCond = side === 'user' ? `m.sender <> 'user'` : `m.sender = 'user'`;
    const row = await db.one(
      `SELECT count(m.id)::int AS c FROM messages m
         LEFT JOIN message_hidden h ON h.message_id = m.id AND h.side = $2
        WHERE m.chat_id = $1 AND m.seq > $3 AND m.deleted = FALSE AND h.message_id IS NULL AND ${senderCond}`,
      [chat.id, side, from],
    );
    return Number(row?.c) || 0;
  }

  // ---------- сериализация ----------

  async function serializeMessages(chat, rows) {
    if (!rows.length) return [];
    const ids = rows.map((r) => r.id);
    const reactions = await db.many(
      `SELECT message_id, who, emoji FROM reactions WHERE message_id IN (${placeholders(ids.length)}) ORDER BY created_at`, ids);
    const byMsg = new Map();
    for (const r of reactions) {
      const map = byMsg.get(r.message_id) || {};
      (map[r.emoji] ||= []).push(r.who);
      byMsg.set(r.message_id, map);
    }
    return rows.map((r) => toMessage(chat, r, byMsg.get(r.id) || {}));
  }

  function toMessage(chat, r, reactions) {
    const seq = Number(r.seq);
    const readByPeer = r.sender === 'user' ? seq <= Number(chat.admin_read_seq) : seq <= Number(chat.user_read_seq);
    return {
      id: r.id,
      clientId: r.client_id,
      chatId: r.chat_id,
      sender: r.sender,
      text: r.text || '',
      attachments: r.deleted ? [] : json(r.attachments, []),
      replyTo: r.reply_to || null,
      createdAt: iso(r.created_at),
      editedAt: iso(r.edited_at),
      deleted: !!r.deleted,
      reactions,
      pinned: !!chat.pinned_message_id && chat.pinned_message_id === r.id,
      readByPeer,
    };
  }

  async function serializeOne(chat, row) {
    return (await serializeMessages(chat, [row]))[0];
  }

  /** Модель Chat с точки зрения стороны side. device — строка устройства (для заголовка у админа). */
  async function serializeChat(chat, side, device = null) {
    const [last] = await visibleRows(chat, side, { limit: 1 });
    const lastMessage = last ? await serializeOne(chat, last) : null;
    let title = ADMIN_CHAT_TITLE;
    let peer;
    let assignedAdminName = null;
    if (side === 'admin') {
      const d = device || (await db.one('SELECT display_name, device_name, device_model, last_seen_at FROM devices WHERE id = $1', [chat.device_id]));
      title = d?.display_name || d?.device_name || d?.device_model || 'Пользователь';
      peer = hub.devicePresence(chat.device_id, d?.last_seen_at);
      // Кто взял обращение в работу (план админка п.16) — имя для подписи в списке/чате.
      if (chat.assigned_admin_id) {
        const assignee = await db.one('SELECT name, login, email FROM admins WHERE id = $1', [chat.assigned_admin_id]);
        assignedAdminName = assignee?.name || assignee?.login || assignee?.email || null;
      }
    } else {
      const a = await db.one('SELECT max(last_seen_at) AS last_seen FROM admins');
      peer = hub.adminPresence(a?.last_seen);
    }
    return {
      id: chat.id,
      kind: chat.kind || 'admin',
      deviceId: chat.device_id,
      title,
      aiEnabled: !!chat.ai_enabled,
      pinnedMessageId: chat.pinned_message_id || null,
      lastMessage,
      unread: await unreadCount(chat, side),
      peerTyping: hub.peerTyping(chat, side),
      peerReadUpTo: (side === 'user' ? chat.admin_read_message_id : chat.user_read_message_id) || null,
      peerLastSeen: peer.lastSeen,
      peerPresence: peer.state,
      assignedAdminId: chat.assigned_admin_id || null,
      assignedAdminName,
      assignedAt: chat.assigned_at ? iso(chat.assigned_at) : null,
    };
  }

  // ---------- сообщения ----------

  async function listMessages(chat, side, { before = null, limit = 50 } = {}) {
    let beforeSeq = null;
    if (before) {
      const b = await db.one('SELECT seq FROM messages WHERE id = $1 AND chat_id = $2', [before, chat.id]);
      if (!b) throw notFound('Сообщение before не найдено');
      beforeSeq = Number(b.seq);
    }
    const rows = await visibleRows(chat, side, { beforeSeq, limit });
    rows.reverse(); // новые — последними
    return serializeMessages(chat, rows);
  }

  /** Проверяет ссылки на вложения и дополняет их метаданными из БД. */
  async function resolveAttachments(chat, refs, uploader) {
    const out = [];
    for (const ref of refs || []) {
      const m = await db.one('SELECT * FROM media WHERE id = $1', [ref.id]);
      if (!m) throw badRequest('Вложение не найдено');
      const own = m.uploader_kind === uploader.kind && (uploader.kind === 'admin' || m.uploader_id === uploader.id);
      const linked = own || (await db.one('SELECT 1 AS x FROM media_links WHERE media_id = $1 AND chat_id = $2', [m.id, chat.id]));
      if (!linked) throw forbidden('Нет доступа к вложению');
      out.push({
        id: m.id,
        kind: ATTACHMENT_KINDS.has(ref.kind) ? ref.kind : m.kind,
        name: m.name,
        mime: m.mime,
        size: Number(m.size),
        durationMs: ref.durationMs ?? m.duration_ms ?? null,
        width: ref.width ?? m.width ?? null,
        height: ref.height ?? m.height ?? null,
        url: `/v1/media/${m.id}`,
      });
    }
    return out;
  }

  /**
   * Отправка сообщения. sender: user | admin | ai. uploader — чьи вложения можно прикладывать.
   * Идемпотентно по (chatId, clientId): повтор возвращает уже созданное сообщение без повторной рассылки.
   */
  async function sendMessage(chat, { sender, clientId, text = '', attachments = [], replyTo = null, uploader = null }) {
    text = String(text || '');
    if (!text.trim() && !(attachments && attachments.length)) throw badRequest('Пустое сообщение');

    const existing = await db.one('SELECT * FROM messages WHERE chat_id = $1 AND client_id = $2', [chat.id, clientId]);
    if (existing) {
      if (existing.sender !== sender) throw conflict('clientId уже используется');
      return { message: await serializeOne(chat, existing), created: false };
    }
    if (replyTo) await getMessageRow(chat, replyTo);
    const atts = await resolveAttachments(chat, attachments, uploader || { kind: sender === 'user' ? 'device' : 'admin', id: null });

    const id = randomUUID();
    const now = new Date();
    const inserted = await db.one(
      `INSERT INTO messages (id, chat_id, client_id, sender, text, attachments, reply_to, created_at)
       VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7, $8)
       ON CONFLICT (chat_id, client_id) DO NOTHING RETURNING *`,
      [id, chat.id, clientId, sender, text, JSON.stringify(atts), replyTo, now],
    );
    if (!inserted || inserted.id !== id) {
      // Гонка двух одинаковых запросов — отдаём победителя.
      const row = await db.one('SELECT * FROM messages WHERE chat_id = $1 AND client_id = $2', [chat.id, clientId]);
      return { message: await serializeOne(chat, row), created: false };
    }
    for (const a of atts) {
      await db.query('INSERT INTO media_links (media_id, chat_id) VALUES ($1, $2) ON CONFLICT (media_id, chat_id) DO NOTHING', [a.id, chat.id]);
    }
    await db.query('UPDATE chats SET last_message_at = $2 WHERE id = $1', [chat.id, now]);

    const message = await serializeOne(chat, inserted);
    // Отправка гасит индикатор «печатает» отправителя (кадр уходит, только если он был включён).
    if (sender !== 'ai') hub.setTyping(chat, sender, false);
    hub.sendToChat(chat, { t: 'message', chatId: chat.id, message });

    if (sender !== 'user') {
      push?.notifyDevice(chat.device_id, {
        type: 'message',
        chatId: chat.id,
        title: sender === 'ai' ? 'Honer AI' : ADMIN_CHAT_TITLE,
        body: previewText(message),
      });
    }
    for (const fn of listeners) {
      try { fn(chat, message, inserted); } catch (err) { logger?.error({ err: { message: err.message } }, 'message listener failed'); }
    }
    return { message, created: true };
  }

  async function broadcastUpdated(chatId, messageId) {
    const chat = await getChat(chatId);
    const row = await db.one('SELECT * FROM messages WHERE id = $1', [messageId]);
    if (!chat || !row) return null;
    const message = await serializeOne(chat, row);
    hub.sendToChat(chat, { t: 'message.updated', chatId, message });
    return message;
  }

  async function editMessage(chat, side, messageId, text) {
    const row = await getMessageRow(chat, messageId);
    if (row.sender !== side) throw forbidden('Можно редактировать только свои сообщения');
    if (row.deleted) throw badRequest('Сообщение удалено');
    if (!String(text).trim() && !json(row.attachments, []).length) throw badRequest('Пустое сообщение');
    await db.query('UPDATE messages SET text = $2, edited_at = $3 WHERE id = $1', [row.id, text, new Date()]);
    return broadcastUpdated(chat.id, row.id);
  }

  async function react(chat, side, messageId, emoji) {
    const row = await getMessageRow(chat, messageId);
    if (row.deleted) throw badRequest('Сообщение удалено');
    if (emoji) {
      await db.query(
        `INSERT INTO reactions (message_id, who, emoji, chat_id, created_at) VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (message_id, who) DO UPDATE SET emoji = EXCLUDED.emoji, created_at = EXCLUDED.created_at`,
        [row.id, side, emoji, chat.id, new Date()]);
    } else {
      await db.query('DELETE FROM reactions WHERE message_id = $1 AND who = $2', [row.id, side]);
    }
    return broadcastUpdated(chat.id, row.id);
  }

  async function pin(chat, messageId, pinned) {
    const row = await getMessageRow(chat, messageId);
    if (row.deleted && pinned) throw badRequest('Сообщение удалено');
    const prev = chat.pinned_message_id;
    if (pinned) {
      await db.query('UPDATE chats SET pinned_message_id = $2 WHERE id = $1', [chat.id, row.id]);
      if (prev && prev !== row.id) await broadcastUpdated(chat.id, prev);
    } else if (prev === row.id) {
      await db.query('UPDATE chats SET pinned_message_id = NULL WHERE id = $1', [chat.id]);
    }
    return broadcastUpdated(chat.id, row.id);
  }

  async function deleteMessage(chat, side, messageId, scope) {
    const row = await getMessageRow(chat, messageId);
    if (scope === 'me') {
      await db.query('INSERT INTO message_hidden (message_id, side, chat_id) VALUES ($1, $2, $3) ON CONFLICT (message_id, side) DO NOTHING',
        [row.id, side, chat.id]);
      return;
    }
    // everyone: пользователь — только свои, админ — любые.
    if (side === 'user' && row.sender !== 'user') throw forbidden('Удалить у всех можно только свои сообщения');
    if (row.deleted) return;
    await db.query(`UPDATE messages SET deleted = TRUE, text = '', attachments = $2::jsonb WHERE id = $1`, [row.id, '[]']);
    await db.query('DELETE FROM reactions WHERE message_id = $1', [row.id]);
    if (chat.pinned_message_id === row.id) await db.query('UPDATE chats SET pinned_message_id = NULL WHERE id = $1', [chat.id]);
    await broadcastUpdated(chat.id, row.id);
  }

  async function clearChat(chat, side, scope) {
    if (scope === 'me') {
      const r = await db.one('SELECT COALESCE(max(seq), 0) AS s FROM messages WHERE chat_id = $1', [chat.id]);
      await db.query(`UPDATE chats SET ${COL[side].cleared} = $2 WHERE id = $1`, [chat.id, Number(r.s) || 0]);
      const frame = { t: 'chat.cleared', chatId: chat.id };
      if (side === 'user') hub.sendToDevice(chat.device_id, frame);
      else hub.sendToAdmins(frame);
      return;
    }
    if (side !== 'admin') throw forbidden('Очистить чат у всех может только администратор');
    const mediaIds = (await db.many('SELECT media_id FROM media_links WHERE chat_id = $1', [chat.id])).map((r) => r.media_id);
    await db.query('DELETE FROM reactions WHERE chat_id = $1', [chat.id]);
    await db.query('DELETE FROM message_hidden WHERE chat_id = $1', [chat.id]);
    await db.query('DELETE FROM messages WHERE chat_id = $1', [chat.id]);
    await db.query('DELETE FROM media_links WHERE chat_id = $1', [chat.id]);
    await db.query('UPDATE chats SET pinned_message_id = NULL, last_message_at = NULL WHERE id = $1', [chat.id]);
    // Файлы, которые больше нигде не используются, удаляем с диска.
    if (media) await media.removeOrphans(mediaIds);
    hub.sendToChat(chat, { t: 'chat.cleared', chatId: chat.id });
  }

  /** Прочитано всё до messageId включительно (указатель только растёт). */
  async function markRead(chat, side, messageId) {
    const row = await getMessageRow(chat, messageId);
    const seq = Number(row.seq);
    if (seq <= Number(chat[COL[side].read])) return false;
    await db.query(`UPDATE chats SET ${COL[side].read} = $2, ${COL[side].readId} = $3 WHERE id = $1 AND ${COL[side].read} < $2`,
      [chat.id, seq, row.id]);
    hub.sendToChat(chat, { t: 'read', chatId: chat.id, who: side, messageId: row.id });
    return true;
  }

  async function setAiEnabled(chat, enabled) {
    if (!!chat.ai_enabled === enabled) return chat;
    await db.query('UPDATE chats SET ai_enabled = $2 WHERE id = $1', [chat.id, enabled]);
    const updated = await getChat(chat.id);
    // Служебное сообщение от ИИ — так обе стороны сразу видят, что состав чата изменился.
    await sendMessage(updated, {
      sender: 'ai',
      clientId: `ai-${randomUUID()}`,
      text: enabled
        ? 'Honer AI присоединился к чату. Упомяните «Honer» или «ИИ», чтобы задать мне вопрос.'
        : 'Honer AI покинул чат.',
    });
    return getChat(chat.id);
  }

  /** «Взять» обращение в работу: закрепляется за админом adminId (план админка п.16). */
  async function assignChat(chat, adminId) {
    const at = new Date();
    await db.query('UPDATE chats SET assigned_admin_id = $2, assigned_at = $3 WHERE id = $1', [chat.id, adminId, at]);
    const updated = await getChat(chat.id);
    hub.sendToAdmins({ t: 'chat.assigned', chatId: chat.id, assignedAdminId: adminId, assignedAt: iso(at) });
    return updated;
  }

  /** Снять обращение (освободить для других админов). */
  async function releaseChat(chat) {
    await db.query('UPDATE chats SET assigned_admin_id = NULL, assigned_at = NULL WHERE id = $1', [chat.id]);
    const updated = await getChat(chat.id);
    hub.sendToAdmins({ t: 'chat.assigned', chatId: chat.id, assignedAdminId: null, assignedAt: null });
    return updated;
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
    assignChat,
    releaseChat,
  };
}

export function previewText(message) {
  if (message.text && message.text.trim()) return message.text.length > 200 ? `${message.text.slice(0, 200)}…` : message.text;
  const a = message.attachments?.[0];
  const labels = { image: '📷 Фото', video: '🎬 Видео', audio: '🎵 Аудио', voice: '🎤 Голосовое сообщение', file: '📎 Файл' };
  return a ? labels[a.kind] || '📎 Вложение' : '';
}
