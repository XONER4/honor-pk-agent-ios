// Слой данных поверх Deno KV — замена Postgres из Node-версии.
// Ключи-схемы (см. README-DENO.md):
//   ["user", userId] → User; ["publicId", pid] → userId
//   ["device", deviceId] → Device; ["deviceByInstall", installId] → deviceId
//   ["admin", adminId] → Admin; ["adminByEmail", email] → adminId; ["adminByLogin", login] → adminId
//   ["token", sha256] → { kind, subjectId, createdAt, expiresAt }; ["deviceToken", deviceId, hash] → 1
//   ["chat", chatId] → Chat; ["chatByDevice", deviceId] → chatId
//   ["msg", messageId] → Message; ["chatMsg", chatId, seq] → messageId; ["chatMsgClient", chatId, clientId] → messageId
//   ["reaction", messageId, who] → { emoji, chatId, createdAt }
//   ["hidden", chatId, side, messageId] → 1
//   ["media", id] → Media; ["mediaChunk", id, index] → Uint8Array; ["mediaLink", chatId, mediaId] / ["mediaLinkByMedia", mediaId, chatId]
//   ["notif", deviceId, createdAt, id] → Notification; ["notifById", id] → { deviceId, createdAt }
//   ["counter", name] → number
//   ["event", deviceId, at, id] → DeviceEvent
//   ["usage", day, deviceId] → { prompt, completion, requests, errors }
//   ["report", at, id] → Report
//   ["action", at, id] → AdminAction
//   ["note", noteId] → Note; ["noteByDevice", deviceId, createdAt, noteId] → 1
//   ["settings", key] → value
import type { Config } from "./config.ts";

export type Kv = Deno.Kv;

export const nowMs = () => Date.now();

// Монотонная возрастающая последовательность для устойчивого порядка записей с одинаковым временем
// (отчёты, действия). База — Date.now()*1000, поэтому порядок сохраняется и после перезапуска.
let _seq = Date.now() * 1000;
export const nextSeq = (): number => ++_seq;
export const iso = (ms: number | null | undefined): string | null => (ms == null ? null : new Date(ms).toISOString());

export async function openKv(config: Config): Promise<Kv> {
  return await Deno.openKv(config.kvPath);
}

// ---------- generic ----------

export async function get<T = unknown>(kv: Kv, key: Deno.KvKey): Promise<T | null> {
  const r = await kv.get<T>(key);
  return r.value ?? null;
}

export async function listAll<T = unknown>(kv: Kv, prefix: Deno.KvKey): Promise<T[]> {
  const out: T[] = [];
  for await (const e of kv.list<T>({ prefix })) out.push(e.value);
  return out;
}

// counters (простые числа; single-instance)
export async function counterGet(kv: Kv, name: string): Promise<number> {
  return Number((await kv.get<number>(["counter", name])).value || 0);
}
export async function counterAdd(kv: Kv, name: string, delta = 1): Promise<void> {
  const cur = await counterGet(kv, name);
  await kv.set(["counter", name], cur + delta);
}

// ---------- users ----------

export interface User {
  id: string;
  createdAt: number;
  publicId: string | null;
}
export const getUser = (kv: Kv, id: string) => get<User>(kv, ["user", id]);
export const listUsers = (kv: Kv) => listAll<User>(kv, ["user"]);

// ---------- devices ----------

export interface Device {
  id: string;
  userId: string;
  installId: string;
  platform: string;
  deviceModel: string | null;
  deviceName: string | null;
  osVersion: string | null;
  appVersion: string | null;
  displayName: string | null;
  birthday: string | null;
  language: string | null;
  licenseAcceptedAt: number | null;
  pushToken: string | null;
  blocked: boolean;
  blockReason: string | null;
  blockedUntil: number | null;
  overrides: Record<string, unknown> | null;
  messagesSent: number;
  secondsInApp: number;
  registerCount: number;
  installedAt: number;
  updatedAt: number;
  lastSeenAt: number | null;
}

export const getDevice = (kv: Kv, id: string) => get<Device>(kv, ["device", id]);
export async function getDeviceByInstall(kv: Kv, installId: string): Promise<Device | null> {
  const id = (await kv.get<string>(["deviceByInstall", installId])).value;
  return id ? getDevice(kv, id) : null;
}
export async function saveDevice(kv: Kv, d: Device): Promise<void> {
  await kv.set(["device", d.id], d);
  await kv.set(["deviceByInstall", d.installId], d.id);
}
export const listDevices = (kv: Kv) => listAll<Device>(kv, ["device"]);

// ---------- admins ----------

export interface Admin {
  id: string;
  email: string;
  name: string | null;
  login: string | null;
  passwordHash: string | null;
  createdAt: number;
  lastLoginAt: number | null;
  lastSeenAt: number | null;
}
export const getAdmin = (kv: Kv, id: string) => get<Admin>(kv, ["admin", id]);
export async function getAdminByEmail(kv: Kv, email: string): Promise<Admin | null> {
  const id = (await kv.get<string>(["adminByEmail", email])).value;
  return id ? getAdmin(kv, id) : null;
}
export async function getAdminByLogin(kv: Kv, login: string): Promise<Admin | null> {
  const id = (await kv.get<string>(["adminByLogin", login])).value;
  return id ? getAdmin(kv, id) : null;
}
export async function saveAdmin(kv: Kv, a: Admin): Promise<void> {
  await kv.set(["admin", a.id], a);
  await kv.set(["adminByEmail", a.email], a.id);
  if (a.login) await kv.set(["adminByLogin", a.login], a.id);
}
export const listAdmins = (kv: Kv) => listAll<Admin>(kv, ["admin"]);
export async function hasAdminAccount(kv: Kv): Promise<boolean> {
  for await (const e of kv.list<Admin>({ prefix: ["admin"] })) {
    if (e.value.passwordHash) return true;
  }
  return false;
}

// ---------- settings ----------

export const getSetting = <T = unknown>(kv: Kv, key: string) => get<T>(kv, ["settings", key]);
export const setSetting = (kv: Kv, key: string, value: unknown) => kv.set(["settings", key], value);
