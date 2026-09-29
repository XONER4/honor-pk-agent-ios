// Realtime-хаб: реестр WebSocket-соединений, присутствие, «печатает…» и рассылка кадров. Состояние — в памяти.
import type { Config } from "./config.ts";
import { type TimerId, unref } from "./timers.ts";

const OPEN = 1;

interface ChatRef {
  id: string;
  deviceId: string;
}
interface SockMeta {
  kind: "device" | "admin";
  id: string;
  state?: string;
  lastFrameAt: number;
  lastPersist: number;
}
interface DeviceEntry {
  sockets: Set<WebSocket>;
  state: string;
  lastSeen: Date | null;
  typingIn: string | null;
  typingTimer: TimerId | null;
  offlineTimer: TimerId | null;
  typingChat: ChatRef | null;
}

export class Hub {
  cfg: Config;
  persistDeviceSeen: (id: string, at: Date) => void;
  persistAdminSeen: (id: string, at: Date) => void;
  sockets = new Map<WebSocket, SockMeta>();
  devices = new Map<string, DeviceEntry>();
  admin: { sockets: Set<WebSocket>; state: string; lastSeen: Date | null; offlineTimer: TimerId | null };
  adminTyping = new Map<string, { timer: TimerId; chat: ChatRef }>();
  aiTyping = new Set<string>();
  idleTimer: TimerId;

  constructor(
    { config, persistDeviceSeen, persistAdminSeen }: {
      config: Config;
      persistDeviceSeen?: (id: string, at: Date) => void;
      persistAdminSeen?: (id: string, at: Date) => void;
    },
  ) {
    this.cfg = config;
    this.persistDeviceSeen = persistDeviceSeen || (() => {});
    this.persistAdminSeen = persistAdminSeen || (() => {});
    this.admin = { sockets: new Set(), state: "offline", lastSeen: null, offlineTimer: null };
    this.idleTimer = setInterval(() => this.reapIdle(), Math.max(1000, Math.floor(config.wsIdleMs / 3)));
    unref(this.idleTimer);
  }

  send(ws: WebSocket, frame: unknown) {
    if (ws.readyState === OPEN) {
      try {
        ws.send(typeof frame === "string" ? frame : JSON.stringify(frame));
      } catch { /* ignore */ }
    }
  }
  sendToDevice(deviceId: string, frame: unknown) {
    const e = this.devices.get(deviceId);
    if (!e) return;
    const data = JSON.stringify(frame);
    for (const ws of e.sockets) this.send(ws, data);
  }
  sendToAdmins(frame: unknown) {
    const data = JSON.stringify(frame);
    for (const ws of this.admin.sockets) this.send(ws, data);
  }
  sendToChat(chat: ChatRef, frame: unknown) {
    this.sendToDevice(chat.deviceId, frame);
    this.sendToAdmins(frame);
  }

  deviceEntry(deviceId: string): DeviceEntry {
    let e = this.devices.get(deviceId);
    if (!e) {
      e = { sockets: new Set(), state: "offline", lastSeen: null, typingIn: null, typingTimer: null, offlineTimer: null, typingChat: null };
      this.devices.set(deviceId, e);
    }
    return e;
  }

  addDeviceSocket(deviceId: string, ws: WebSocket) {
    const e = this.deviceEntry(deviceId);
    const now = new Date();
    this.sockets.set(ws, { kind: "device", id: deviceId, lastFrameAt: Date.now(), lastPersist: Date.now() });
    e.sockets.add(ws);
    if (e.offlineTimer) clearTimeout(e.offlineTimer);
    e.offlineTimer = null;
    e.lastSeen = now;
    if (e.state === "offline") e.state = "background";
    this.persistDeviceSeen(deviceId, now);
    this.emitPresence(deviceId);
  }

  removeSocket(ws: WebSocket) {
    const meta = this.sockets.get(ws);
    if (!meta) return;
    this.sockets.delete(ws);
    if (meta.kind === "device") this.removeDeviceSocket(meta.id, ws);
    else this.removeAdminSocket(ws, meta.id);
  }

  removeDeviceSocket(deviceId: string, ws: WebSocket) {
    const e = this.devices.get(deviceId);
    if (!e) return;
    e.sockets.delete(ws);
    if (e.sockets.size > 0) return;
    const now = new Date();
    e.lastSeen = now;
    this.persistDeviceSeen(deviceId, now);
    if (e.typingChat) this.setTyping(e.typingChat, "user", false);
    if (e.offlineTimer) clearTimeout(e.offlineTimer);
    e.offlineTimer = setTimeout(() => {
      e.offlineTimer = null;
      if (e.sockets.size > 0) return;
      e.state = "offline";
      e.typingIn = null;
      this.emitPresence(deviceId);
    }, this.cfg.presenceOfflineMs);
    unref(e.offlineTimer!);
  }

  setDeviceState(deviceId: string, state: string) {
    const e = this.deviceEntry(deviceId);
    e.lastSeen = new Date();
    if (e.state === state) return;
    e.state = state;
    this.emitPresence(deviceId);
  }

  touch(ws: WebSocket) {
    const meta = this.sockets.get(ws);
    if (!meta) return;
    const now = Date.now();
    meta.lastFrameAt = now;
    if (meta.kind === "device") {
      const e = this.devices.get(meta.id);
      if (e) e.lastSeen = new Date(now);
    } else {
      this.admin.lastSeen = new Date(now);
    }
    if (now - meta.lastPersist > 60_000) {
      meta.lastPersist = now;
      if (meta.kind === "device") this.persistDeviceSeen(meta.id, new Date(now));
      else this.persistAdminSeen(meta.id, new Date(now));
    }
  }

  devicePresence(deviceId: string, dbLastSeen: number | null = null) {
    const e = this.devices.get(deviceId);
    if (!e) return { state: "offline", typingIn: null, lastSeen: dbLastSeen ? new Date(dbLastSeen).toISOString() : null };
    const last = e.sockets.size > 0 ? new Date() : e.lastSeen || (dbLastSeen ? new Date(dbLastSeen) : null);
    return { state: e.state, typingIn: e.typingIn, lastSeen: last ? last.toISOString() : null };
  }
  deviceState(deviceId: string): string {
    return this.devices.get(deviceId)?.state || "offline";
  }
  emitPresence(deviceId: string) {
    const p = this.devicePresence(deviceId);
    this.sendToAdmins({ t: "presence", deviceId, state: p.state, typingIn: p.typingIn, lastSeen: p.lastSeen });
  }
  presenceSnapshot() {
    const out: unknown[] = [];
    for (const [deviceId, e] of this.devices) {
      if (e.state !== "offline") out.push({ t: "presence", deviceId, ...this.devicePresence(deviceId) });
    }
    return out;
  }
  counts() {
    let online = 0;
    let inBackground = 0;
    for (const e of this.devices.values()) {
      if (e.state === "foreground") online += 1;
      else if (e.state === "background") inBackground += 1;
    }
    return { online, inBackground };
  }

  kickDevice(deviceId: string, reason: string, until: number | null = null) {
    const e = this.devices.get(deviceId);
    if (!e) return;
    for (const ws of [...e.sockets]) {
      this.send(ws, { t: "blocked", message: reason || "", until: until ? new Date(until).toISOString() : null });
      try {
        ws.close(4003, "blocked");
      } catch { /* ignore */ }
    }
  }

  addAdminSocket(adminId: string, ws: WebSocket) {
    const now = new Date();
    this.sockets.set(ws, { kind: "admin", id: adminId, state: "background", lastFrameAt: Date.now(), lastPersist: Date.now() });
    this.admin.sockets.add(ws);
    if (this.admin.offlineTimer) clearTimeout(this.admin.offlineTimer);
    this.admin.offlineTimer = null;
    this.admin.lastSeen = now;
    this.recomputeAdminState();
    this.persistAdminSeen(adminId, now);
    for (const frame of this.presenceSnapshot()) this.send(ws, frame);
  }

  removeAdminSocket(ws: WebSocket, adminId: string) {
    this.admin.sockets.delete(ws);
    const now = new Date();
    this.admin.lastSeen = now;
    this.persistAdminSeen(adminId, now);
    if (this.admin.sockets.size > 0) {
      this.recomputeAdminState();
      return;
    }
    for (const { chat } of [...this.adminTyping.values()]) this.setTyping(chat, "admin", false);
    if (this.admin.offlineTimer) clearTimeout(this.admin.offlineTimer);
    this.admin.offlineTimer = setTimeout(() => {
      if (this.admin.sockets.size === 0) this.admin.state = "offline";
    }, this.cfg.presenceOfflineMs);
    unref(this.admin.offlineTimer!);
  }

  setAdminState(ws: WebSocket, state: string) {
    const meta = this.sockets.get(ws);
    if (meta) meta.state = state;
    this.admin.lastSeen = new Date();
    this.recomputeAdminState();
  }
  recomputeAdminState() {
    let state = this.admin.sockets.size > 0 ? "background" : this.admin.state;
    for (const ws of this.admin.sockets) if (this.sockets.get(ws)?.state === "foreground") state = "foreground";
    this.admin.state = state;
  }
  adminPresence(dbLastSeen: number | null = null) {
    const last = this.admin.sockets.size > 0 ? new Date() : this.admin.lastSeen || (dbLastSeen ? new Date(dbLastSeen) : null);
    return { state: this.admin.state, lastSeen: last ? last.toISOString() : null };
  }

  setTyping(chat: ChatRef, who: "user" | "admin" | "ai", typing: boolean) {
    const chatRef: ChatRef = { id: chat.id, deviceId: chat.deviceId };
    let changed = false;
    if (who === "user") {
      const e = this.deviceEntry(chat.deviceId);
      if (e.typingTimer) clearTimeout(e.typingTimer);
      e.typingTimer = null;
      const next = typing ? chat.id : null;
      changed = e.typingIn !== next;
      e.typingIn = next;
      e.typingChat = typing ? chatRef : null;
      if (typing) {
        e.typingTimer = setTimeout(() => this.setTyping(chatRef, "user", false), this.cfg.typingTtlMs);
        unref(e.typingTimer!);
      }
      if (changed) this.emitPresence(chat.deviceId);
    } else if (who === "admin") {
      const prev = this.adminTyping.get(chat.id);
      if (prev) clearTimeout(prev.timer);
      changed = this.adminTyping.has(chat.id) !== typing;
      if (typing) {
        const timer = setTimeout(() => this.setTyping(chatRef, "admin", false), this.cfg.typingTtlMs);
        unref(timer);
        this.adminTyping.set(chat.id, { timer, chat: chatRef });
      } else {
        this.adminTyping.delete(chat.id);
      }
    } else if (who === "ai") {
      changed = this.aiTyping.has(chat.id) !== typing;
      if (typing) this.aiTyping.add(chat.id);
      else this.aiTyping.delete(chat.id);
    }
    if (changed || typing) this.sendToChat(chatRef, { t: "typing", chatId: chat.id, who, typing });
  }

  peerTyping(chat: ChatRef, side: "user" | "admin"): boolean {
    if (side === "admin") return this.devices.get(chat.deviceId)?.typingIn === chat.id;
    return this.adminTyping.has(chat.id) || this.aiTyping.has(chat.id);
  }

  reapIdle() {
    const now = Date.now();
    for (const [ws, meta] of this.sockets) {
      if (now - meta.lastFrameAt > this.cfg.wsIdleMs) {
        try {
          ws.close(1001, "idle");
        } catch { /* ignore */ }
        this.removeSocket(ws);
      }
    }
  }

  stop() {
    clearInterval(this.idleTimer);
    const now = new Date();
    for (const [deviceId, e] of this.devices) {
      if (e.offlineTimer) clearTimeout(e.offlineTimer);
      if (e.typingTimer) clearTimeout(e.typingTimer);
      if (e.sockets.size > 0) this.persistDeviceSeen(deviceId, now);
    }
    for (const { timer } of this.adminTyping.values()) clearTimeout(timer);
    if (this.admin.offlineTimer) clearTimeout(this.admin.offlineTimer);
  }
}
