// Маршруты чатов. Один и тот же набор регистрируется дважды:
//   /v1/chats/...        — для устройства (side = 'user', доступ только к своему чату)
//   /v1/admin/chats/...  — для админа     (side = 'admin', доступ к любому чату)
import { forbidden, notFound } from '../errors.js';
import { attachmentRef, chatParams, messageParams, scopeQuery, uuid } from './schemas.js';
import { deviceOverrides, restrictionState } from './devices.js';
import { ensure } from '../permissions.js';

export function chatRoutes({ prefix, side }) {
  return async function register(app) {
    const { db, auth, chats } = app.ctx;
    const preHandler = side === 'admin' ? auth.requireAdmin : auth.requireDevice;

    /** Загружает чат и проверяет доступ стороны к нему. */
    async function loadChat(req) {
      const chat = await chats.getChat(req.params.chatId);
      if (!chat || (side === 'user' && chat.device_id !== req.device.id)) throw notFound('Чат не найден');
      return chat;
    }
    const uploader = (req) => (side === 'admin' ? { kind: 'admin', id: req.admin.id } : { kind: 'device', id: req.device.id });

    app.get(prefix, { preHandler }, async (req) => {
      if (side === 'user') {
        const chat = (await chats.getDeviceChat(req.device.id)) || (await chats.createChatForDevice(req.device.id));
        return [await chats.serializeChat(chat, 'user')];
      }
      // Админ: все чаты, свежие сверху.
      const rows = await db.many(
        `SELECT c.*, d.display_name, d.device_name, d.device_model, d.last_seen_at
           FROM chats c JOIN devices d ON d.id = c.device_id`);
      rows.sort((a, b) => (new Date(b.last_message_at || b.created_at)) - (new Date(a.last_message_at || a.created_at)));
      const out = [];
      for (const r of rows) out.push(await chats.serializeChat(r, 'admin', r));
      return out;
    });

    app.get(`${prefix}/:chatId/messages`, {
      preHandler,
      schema: {
        params: chatParams,
        querystring: {
          type: 'object',
          properties: { before: uuid, limit: { type: 'integer', minimum: 1, maximum: 200, default: 50 } },
        },
      },
    }, async (req) => {
      const chat = await loadChat(req);
      return chats.listMessages(chat, side, { before: req.query.before || null, limit: req.query.limit });
    });

    app.post(`${prefix}/:chatId/messages`, {
      preHandler,
      schema: {
        params: chatParams,
        body: {
          type: 'object',
          required: ['clientId'],
          properties: {
            clientId: { type: 'string', minLength: 1, maxLength: 100 },
            text: { type: 'string', maxLength: 10_000, default: '' },
            attachments: { type: 'array', maxItems: 10, items: attachmentRef, default: [] },
            replyTo: { type: ['string', 'null'], format: 'uuid' },
          },
        },
      },
    }, async (req) => {
      const chat = await loadChat(req);
      if (side === 'admin') await ensure(db, req.admin, 'reply'); // право отвечать в поддержку (п.3)
      // Запрет писать в поддержку (ограничение админа). Админская сторона не ограничивается.
      if (side === 'user') {
        const blk = restrictionState(deviceOverrides(req.device).blockSupport);
        if (blk.active) {
          const base = 'Отправка сообщений в поддержку ограничена администратором';
          throw forbidden(blk.reason ? `${base}. Причина: ${blk.reason}` : base);
        }
      }
      const { clientId, text, attachments, replyTo } = req.body;
      const { message } = await chats.sendMessage(chat, { sender: side, clientId, text, attachments, replyTo: replyTo || null, uploader: uploader(req) });
      return message;
    });

    // Расширение контракта: редактирование своего сообщения (Message.editedAt, кадр message.updated).
    app.patch(`${prefix}/:chatId/messages/:id`, {
      preHandler,
      schema: {
        params: messageParams,
        body: { type: 'object', required: ['text'], properties: { text: { type: 'string', maxLength: 10_000 } } },
      },
    }, async (req) => chats.editMessage(await loadChat(req), side, req.params.id, req.body.text));

    app.post(`${prefix}/:chatId/messages/:id/reaction`, {
      preHandler,
      schema: {
        params: messageParams,
        body: { type: 'object', required: ['emoji'], properties: { emoji: { type: ['string', 'null'], minLength: 1, maxLength: 32 } } },
      },
    }, async (req) => chats.react(await loadChat(req), side, req.params.id, req.body.emoji || null));

    app.post(`${prefix}/:chatId/messages/:id/pin`, {
      preHandler,
      schema: {
        params: messageParams,
        body: { type: 'object', required: ['pinned'], properties: { pinned: { type: 'boolean' } } },
      },
    }, async (req) => chats.pin(await loadChat(req), req.params.id, req.body.pinned));

    app.delete(`${prefix}/:chatId/messages/:id`, {
      preHandler,
      schema: { params: messageParams, querystring: scopeQuery },
    }, async (req) => {
      await chats.deleteMessage(await loadChat(req), side, req.params.id, req.query.scope);
      return { ok: true };
    });

    app.post(`${prefix}/:chatId/clear`, {
      preHandler,
      schema: { params: chatParams, querystring: scopeQuery },
    }, async (req) => {
      await chats.clearChat(await loadChat(req), side, req.query.scope);
      return { ok: true };
    });

    // Расширение контракта: REST-аналог WS-кадра {"t":"read"} (удобно для фоновой синхронизации).
    app.post(`${prefix}/:chatId/read`, {
      preHandler,
      schema: { params: chatParams, body: { type: 'object', required: ['messageId'], properties: { messageId: uuid } } },
    }, async (req) => {
      await chats.markRead(await loadChat(req), side, req.body.messageId);
      return { ok: true };
    });

    if (side === 'admin') {
      app.post(`${prefix}/:chatId/ai`, {
        preHandler,
        schema: { params: chatParams, body: { type: 'object', required: ['enabled'], properties: { enabled: { type: 'boolean' } } } },
      }, async (req) => {
        await ensure(db, req.admin, 'manageAi'); // право управлять ИИ в чате (п.3)
        const chat = await chats.setAiEnabled(await loadChat(req), req.body.enabled);
        return chats.serializeChat(chat, 'admin');
      });

      // «Взять» обращение в работу / освободить (план админка п.16).
      // enabled=true → закрепить за собой; false → снять. Снять может любой админ (на случай, если кто-то ушёл).
      app.post(`${prefix}/:chatId/assign`, {
        preHandler,
        schema: { params: chatParams, body: { type: 'object', properties: { assigned: { type: 'boolean', default: true } } } },
      }, async (req) => {
        await ensure(db, req.admin, 'assign'); // право брать обращения в работу (п.3)
        const chat = await loadChat(req);
        const updated = (req.body?.assigned === false)
          ? await chats.releaseChat(chat)
          : await chats.assignChat(chat, req.admin.id);
        return chats.serializeChat(updated, 'admin');
      });
    }
  };
}
