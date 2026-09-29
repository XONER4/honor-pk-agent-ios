// Токены и авторизация. Токен = префикс (d_/a_) + 32 случайных байта base64url; в KV хранится только SHA-256.
import { randomBytes, safeEqual as safeEqualStr, sha256Hex, toBase64Url } from "./crypto-utils.ts";
import { blocked, forbidden, unauthorized } from "./errors.ts";
import { type Admin, type Device, getAdmin, getDevice, type Kv, nowMs } from "./kv.ts";
import { logAdminAction } from "./device-events.ts";

export const newToken = (prefix: string) => `${prefix}_${toBase64Url(randomBytes(32))}`;
export const hashToken = (token: string): Promise<string> => sha256Hex(String(token));

/** Сравнение секретов за (примерно) постоянное время. */
export const safeEqual = safeEqualStr;

const TOKEN_RE = /^[da]_[A-Za-z0-9_-]{20,100}$/;

export const blockExpired = (device: Device | null, now = Date.now()): boolean =>
  Boolean(device?.blocked && device.blockedUntil && device.blockedUntil <= now);

/** Снимает истёкшую блокировку; возвращает актуальную запись устройства. */
export async function unblockIfExpired(kv: Kv, device: Device): Promise<Device> {
  if (!blockExpired(device)) return device;
  const fresh = { ...device, blocked: false, blockReason: null, blockedUntil: null };
  await kv.set(["device", device.id], fresh);
  await logAdminAction(kv, { deviceId: device.id, action: "unblock_auto" });
  return fresh;
}

export type Principal = { kind: "device"; device: Device } | { kind: "admin"; admin: Admin };

export interface TokenRow {
  kind: string;
  subjectId: string;
  createdAt: number;
  expiresAt: number | null;
}

export function createAuth(kv: Kv) {
  async function resolve(token: string | null): Promise<Principal | null> {
    if (!token || !TOKEN_RE.test(token)) return null;
    const row = (await kv.get<TokenRow>(["token", await hashToken(token)])).value;
    if (!row) return null;
    if (row.expiresAt && row.expiresAt < Date.now()) return null;
    if (row.kind === "device" && token.startsWith("d_")) {
      const device = await getDevice(kv, row.subjectId);
      return device ? { kind: "device", device: await unblockIfExpired(kv, device) } : null;
    }
    if (row.kind === "admin" && token.startsWith("a_")) {
      const admin = await getAdmin(kv, row.subjectId);
      return admin ? { kind: "admin", admin } : null;
    }
    return null;
  }

  async function issueDeviceToken(deviceId: string): Promise<string> {
    // Повторная регистрация отзывает все старые токены устройства.
    for await (const e of kv.list<number>({ prefix: ["deviceToken", deviceId] })) {
      await kv.delete(["token", String(e.key[2])]);
      await kv.delete(e.key);
    }
    const token = newToken("d");
    const hash = await hashToken(token);
    await kv.set(["token", hash], { kind: "device", subjectId: deviceId, createdAt: nowMs(), expiresAt: null });
    await kv.set(["deviceToken", deviceId, hash], 1);
    return token;
  }

  async function issueAdminToken(adminId: string, ttlMs: number): Promise<string> {
    const token = newToken("a");
    const now = nowMs();
    await kv.set(["token", await hashToken(token)], { kind: "admin", subjectId: adminId, createdAt: now, expiresAt: now + ttlMs });
    return token;
  }

  return { resolve, issueDeviceToken, issueAdminToken };
}

export type Auth = ReturnType<typeof createAuth>;

/** Проверяет принципала для набора допустимых видов; бросает ApiError при отказе. Возвращает принципала. */
export function requirePrincipal(who: Principal | null, accept: ("device" | "admin")[]): Principal {
  if (!who) throw unauthorized();
  if (!accept.includes(who.kind)) throw forbidden();
  if (who.kind === "device" && who.device.blocked) throw blocked(who.device.blockReason, who.device.blockedUntil);
  return who;
}
