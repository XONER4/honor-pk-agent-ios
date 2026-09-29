// Push через FCM HTTP v1 без firebase-admin: OAuth2-токен сервисного аккаунта получаем JWT-подписью (Web Crypto RS256).
// Отправляем data-сообщения только устройствам, которые сейчас НЕ в foreground. В тестах транспорт подменяется.
import { fromBase64Url, toBase64Url } from "./crypto-utils.ts";
import type { Config, ServiceAccount } from "./config.ts";
import { getDevice, type Kv } from "./kv.ts";
import type { Hub } from "./hub.ts";
import { recordDeviceEvent } from "./device-events.ts";

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const enc = new TextEncoder();

export interface Transport {
  send(token: string, data: Record<string, string>): Promise<"ok" | "invalid_token" | "error">;
}

const b64url = (s: string) => toBase64Url(enc.encode(s));

/** PEM (pkcs8) приватного ключа сервис-аккаунта → DER-байты. */
function pemToDer(pem: string): Uint8Array {
  const body = pem.replace(/-----BEGIN [^-]+-----/, "").replace(/-----END [^-]+-----/, "").replace(/\s+/g, "");
  return fromBase64Url(body.replace(/\+/g, "-").replace(/\//g, "_"));
}

export function createFcmTransport(sa: ServiceAccount): Transport {
  const url = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(sa.project_id)}/messages:send`;
  let cachedToken: { token: string; exp: number } | null = null;
  let signKey: Promise<CryptoKey> | null = null;
  const getSignKey = () =>
    (signKey ??= crypto.subtle.importKey("pkcs8", pemToDer(sa.private_key) as BufferSource, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]));

  async function getAccessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (cachedToken && cachedToken.exp - 60 > now) return cachedToken.token;
    const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claims = b64url(JSON.stringify({
      iss: sa.client_email,
      scope: FCM_SCOPE,
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    }));
    const key = await getSignKey();
    const sigBuf = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claims}`));
    const sig = toBase64Url(sigBuf);
    const assertion = `${header}.${claims}.${sig}`;
    const res = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${assertion}`,
      signal: AbortSignal.timeout(15_000),
    });
    const data = await res.json();
    if (!data.access_token) throw new Error("no access_token from google");
    cachedToken = { token: data.access_token, exp: now + (Number(data.expires_in) || 3600) };
    return cachedToken.token;
  }

  return {
    async send(token, data) {
      const accessToken = await getAccessToken();
      const res = await fetch(url, {
        method: "POST",
        headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
        body: JSON.stringify({ message: { token, data, android: { priority: "HIGH", ttl: "86400s" } } }),
        signal: AbortSignal.timeout(15_000),
      });
      if (res.ok) return "ok";
      const text = await res.text().catch(() => "");
      if (res.status === 404 || /UNREGISTERED|registration-token-not-registered|INVALID_ARGUMENT/.test(text)) return "invalid_token";
      return "error";
    },
  };
}

export function createPush(
  { kv, hub, config, transport }: { kv: Kv; hub: Hub; config: Config; transport?: Transport | null },
) {
  const t: Transport | null = transport !== undefined
    ? transport
    : config.firebaseServiceAccount
    ? createFcmTransport(config.firebaseServiceAccount)
    : null;

  async function deliver(deviceId: string, payload: { type: string; chatId: string | null; title?: string; body?: string }): Promise<boolean> {
    if (hub.deviceState(deviceId) === "foreground") return false;
    const row = await getDevice(kv, deviceId);
    if (!row?.pushToken || row.blocked) return false;
    const data = {
      type: String(payload.type),
      chatId: payload.chatId ? String(payload.chatId) : "",
      title: String(payload.title || "").slice(0, 200),
      body: String(payload.body || "").slice(0, 1000),
    };
    const result = await t!.send(row.pushToken, data);
    if (result === "invalid_token") {
      const fresh = await getDevice(kv, deviceId);
      if (fresh && fresh.pushToken === row.pushToken) {
        await kv.set(["device", deviceId], { ...fresh, pushToken: null });
        await recordDeviceEvent(kv, deviceId, "uninstall");
      }
    }
    return result === "ok";
  }

  return {
    enabled: !!t,
    notifyDevice(deviceId: string, payload: { type: string; chatId: string | null; title?: string; body?: string }) {
      if (!t) return;
      deliver(deviceId, payload).catch(() => {});
    },
    deliver,
  };
}

export type Push = ReturnType<typeof createPush>;
