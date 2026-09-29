// WebSocket /v1/ws?token=<device|admin token>: presence, typing, read receipts, ping/pong.
// Рассылки (message, message.updated, typing, read, presence, …) делает Hub.
import { tokenFromRequest } from './auth.js';
import { iso } from './db.js';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STATES = new Set(['foreground', 'background']);

export default async function wsRoutes(app) {
  const { auth, hub, chats } = app.ctx;

  app.get('/v1/ws', {
    websocket: true,
    // Аутентификация до апгрейда: без валидного токена рукопожатие получает 401.
    preValidation: async (req, reply) => {
      const who = await auth.resolve(tokenFromRequest(req, { allowQuery: true }));
      if (!who) return reply.code(401).send({ error: 'unauthorized', message: 'Требуется авторизация' });
      req.principal = who;
    },
  }, async (socket, req) => {
    const who = req.principal;

    if (who.kind === 'device' && who.device.blocked) {
      // Заблокированное устройство: сообщаем и закрываем (приложение покажет экран блокировки).
      socket.send(JSON.stringify({ t: 'blocked', message: who.device.block_reason || '', until: iso(who.device.blocked_until) }));
      socket.close(4003, 'blocked');
      return;
    }

    // Обработчики вешаем синхронно, иначе первые кадры (presence сразу после connect) потеряются.
    const deviceChatId = who.kind === 'device'
      ? chats.getDeviceChat(who.device.id).then((c) => c?.id || null, () => null)
      : null;
    if (who.kind === 'device') hub.addDeviceSocket(who.device.id, socket);
    else hub.addAdminSocket(who.admin.id, socket);

    let queue = Promise.resolve(); // кадры одного сокета обрабатываем строго по порядку
    socket.on('close', () => hub.removeSocket(socket));
    socket.on('error', () => hub.removeSocket(socket));
    socket.on('message', (raw, isBinary) => {
      if (isBinary) return;
      let frame;
      try {
        frame = JSON.parse(raw.toString('utf8'));
      } catch {
        return;
      }
      if (!frame || typeof frame !== 'object' || typeof frame.t !== 'string') return;
      hub.touch(socket);
      queue = queue.then(() => handle(frame))
        .catch((err) => req.log.debug({ err: { message: err.message } }, 'ws frame failed'));
    });

    /** Возвращает чат, если сторона имеет к нему доступ. */
    async function chatFor(chatId) {
      if (typeof chatId !== 'string' || !UUID_RE.test(chatId)) return null;
      if (who.kind === 'device' && chatId !== (await deviceChatId)) return null;
      return chats.getChat(chatId);
    }

    async function handle(frame) {
      const side = who.kind === 'device' ? 'user' : 'admin';
      switch (frame.t) {
        case 'ping':
          hub.send(socket, { t: 'pong' });
          break;
        case 'presence':
          if (!STATES.has(frame.state)) return;
          if (who.kind === 'device') hub.setDeviceState(who.device.id, frame.state);
          else hub.setAdminState(socket, frame.state);
          break;
        case 'typing': {
          const chat = await chatFor(frame.chatId);
          if (chat) hub.setTyping(chat, side, frame.typing === true);
          break;
        }
        case 'read': {
          const chat = await chatFor(frame.chatId);
          if (chat && typeof frame.messageId === 'string' && UUID_RE.test(frame.messageId)) {
            await chats.markRead(chat, side, frame.messageId);
          }
          break;
        }
        default:
          break; // неизвестные кадры игнорируем (совместимость вперёд)
      }
    }
  });
}
