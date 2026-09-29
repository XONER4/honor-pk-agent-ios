// Push через FCM HTTP v1 (без firebase-admin): OAuth2-токен сервисного аккаунта получаем через google-auth-library JWT.
// Отправляем data-сообщения { type, chatId, title, body } только устройствам, которые сейчас НЕ в foreground.
import { JWT } from 'google-auth-library';
import { recordDeviceEvent } from './device-events.js';

const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

/** Транспорт FCM: send(token, data) → 'ok' | 'invalid_token' | 'error'. */
export function createFcmTransport(serviceAccount, logger) {
  const jwt = new JWT({ email: serviceAccount.client_email, key: serviceAccount.private_key, scopes: [FCM_SCOPE] });
  const url = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(serviceAccount.project_id)}/messages:send`;
  return {
    async send(token, data) {
      const { token: accessToken } = await jwt.getAccessToken(); // кешируется библиотекой до истечения
      const res = await fetch(url, {
        method: 'POST',
        headers: { authorization: `Bearer ${accessToken}`, 'content-type': 'application/json' },
        body: JSON.stringify({ message: { token, data, android: { priority: 'HIGH', ttl: '86400s' } } }),
        signal: AbortSignal.timeout(15_000),
      });
      if (res.ok) return 'ok';
      const text = await res.text().catch(() => '');
      if (res.status === 404 || /UNREGISTERED|registration-token-not-registered|INVALID_ARGUMENT/.test(text)) return 'invalid_token';
      logger?.warn({ status: res.status }, 'fcm send failed');
      return 'error';
    },
  };
}

/**
 * @param {object} o
 * @param {{send:(token:string,data:object)=>Promise<string>}|null} o.transport — в тестах подменяется
 */
export function createPush({ db, hub, config, logger, transport }) {
  const t = transport !== undefined ? transport
    : config.firebaseServiceAccount ? createFcmTransport(config.firebaseServiceAccount, logger) : null;

  async function deliver(deviceId, payload) {
    if (hub.deviceState(deviceId) === 'foreground') return false;
    const row = await db.one('SELECT push_token, blocked FROM devices WHERE id = $1', [deviceId]);
    if (!row?.push_token || row.blocked) return false;
    // FCM data допускает только строковые значения.
    const data = {
      type: String(payload.type),
      chatId: payload.chatId ? String(payload.chatId) : '',
      title: String(payload.title || '').slice(0, 200),
      body: String(payload.body || '').slice(0, 1000),
    };
    const result = await t.send(row.push_token, data);
    if (result === 'invalid_token') {
      const r = await db.query('UPDATE devices SET push_token = NULL WHERE id = $1 AND push_token = $2', [deviceId, row.push_token]);
      // FCM не знает токен (UNREGISTERED) — приложение, скорее всего, удалено. Это лишь признак: устройство может вернуться.
      if (r.rowCount) await recordDeviceEvent(db, deviceId, 'uninstall');
    }
    return result === 'ok';
  }

  return {
    enabled: !!t,
    /** Fire-and-forget: ошибки только логируются. */
    notifyDevice(deviceId, payload) {
      if (!t) return;
      deliver(deviceId, payload).catch((err) => logger?.warn({ err: { message: err.message } }, 'push failed'));
    },
    deliver,
  };
}
