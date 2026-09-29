// События устройства (device_events) и журнал действий админов (admin_actions).
import { counterAdd, getAdmin, iso, type Kv, nextSeq } from "./kv.ts";

export interface DeviceEvent {
  id: string;
  deviceId: string;
  kind: string; // install | update | uninstall | open
  fromVersion: string | null;
  toVersion: string | null;
  at: number;
}

export async function recordDeviceEvent(
  kv: Kv,
  deviceId: string,
  kind: string,
  { from = null, to = null, at = Date.now() }: { from?: string | null; to?: string | null; at?: number } = {},
): Promise<void> {
  const ev: DeviceEvent = { id: crypto.randomUUID(), deviceId, kind, fromVersion: from || null, toVersion: to || null, at };
  await kv.set(["event", deviceId, at, ev.id], ev);
  if (kind === "update" || kind === "uninstall") await counterAdd(kv, kind === "update" ? "updates" : "uninstalls", 1);
}

export const toDeviceEvent = (r: DeviceEvent) => ({
  id: r.id,
  deviceId: r.deviceId,
  kind: r.kind,
  fromVersion: r.fromVersion || null,
  toVersion: r.toVersion || null,
  at: iso(r.at),
});

export interface AdminAction {
  id: string;
  adminId: string | null;
  deviceId: string | null;
  action: string;
  detail: Record<string, unknown> | null;
  at: number;
}

export async function logAdminAction(
  kv: Kv,
  { adminId = null, deviceId = null, action, detail = null }: {
    adminId?: string | null;
    deviceId?: string | null;
    action: string;
    detail?: Record<string, unknown> | null;
  },
): Promise<void> {
  const at = Date.now();
  const act: AdminAction = { id: crypto.randomUUID(), adminId, deviceId, action, detail: detail == null ? null : detail, at };
  try {
    await kv.set(["action", nextSeq(), act.id], act);
  } catch {
    /* журнал не должен ломать действие */
  }
}

export async function toAdminAction(kv: Kv, r: AdminAction) {
  let adminName: string | null = r.adminId ? null : "Сервер";
  if (r.adminId) {
    const a = await getAdmin(kv, r.adminId);
    adminName = a?.name || a?.login || a?.email || null;
  }
  return {
    id: r.id,
    adminId: r.adminId || null,
    adminName,
    deviceId: r.deviceId || null,
    action: r.action,
    detail: r.detail ?? null,
    at: iso(r.at),
  };
}
