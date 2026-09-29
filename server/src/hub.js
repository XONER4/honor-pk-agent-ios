// Realtime-хаб: реестр WebSocket-соединений, присутствие (presence), «печатает…» и рассылка кадров.
// Состояние живёт в памяти процесса; lastSeen периодически сохраняется в БД через колбэки.

const OPEN = 1;

export class Hub {
  /**
   * @param {object} o
   * @param {object} o.config  presenceOfflineMs, typingTtlMs, wsIdleMs
   * @param {(deviceId:string, at:Date)=>void} o.persistDeviceSeen
   * @param {(adminId:string, at:Date)=>void} o.persistAdminSeen
   */
  constructor({ config, logger, persistDeviceSeen, persistAdminSeen }) {
    this.cfg = config;
    this.log = logger;
    this.persistDeviceSeen = persistDeviceSeen || (() => {});
    this.persistAdminSeen = persistAdminSeen || (() => {});
    this.sockets = new Map(); // ws -> { kind: 'device'|'admin', id, state, lastFrameAt, lastPersist }
    this.devices = new Map(); // deviceId -> { sockets:Set, state, lastSeen:Date, typingIn, typingTimer, offlineTimer }
    this.admin = { sockets: new Set(), state: 'offline', lastSeen: null, offlineTimer: null };
    this.adminTyping = new Map(); // chatId -> { timer, chat }
    this.aiTyping = new Set(); // chatId
    // Сторож «зависших» соединений: клиент шлёт ping каждые 25 с.
    this.idleTimer = setInterval(() => this.reapIdle(), Math.max(1000, Math.floor(config.wsIdleMs / 3)));
    this.idleTimer.unref();
  }

  // ---------- отправка ----------

  send(ws, frame) {
    if (ws.readyState === OPEN) ws.send(typeof frame === 'string' ? frame : JSON.stringify(frame));
  }

  sendToDevice(deviceId, frame) {
    const e = this.devices.get(deviceId);
    if (!e) return;
    const data = JSON.stringify(frame);
    for (const ws of e.sockets) this.send(ws, data);
  }

  sendToAdmins(frame) {
    const data = JSON.stringify(frame);
    for (const ws of this.admin.sockets) this.send(ws, data);
  }

  /** Кадр для обоих участников чата: устройство-владелец + все подключённые админы. */
  sendToChat(chat, frame) {
    this.sendToDevice(chat.device_id, frame);
    this.sendToAdmins(frame);
  }

  // ---------- устройства ----------

  deviceEntry(deviceId) {
    let e = this.devices.get(deviceId);
    if (!e) {
      e = { sockets: new Set(), state: 'offline', lastSeen: null, typingIn: null, typingTimer: null, offlineTimer: null, typingChat: null };
      this.devices.set(deviceId, e);
    }
    return e;
  }

  addDeviceSocket(deviceId, ws) {
    const e = this.deviceEntry(deviceId);
    const now = new Date();
    this.sockets.set(ws, { kind: 'device', id: deviceId, lastFrameAt: Date.now(), lastPersist: Date.now() });
    e.sockets.add(ws);
    clearTimeout(e.offlineTimer);
    e.offlineTimer = null;
    e.lastSeen = now;
    // До первого кадра presence считаем приложение фоновым (консервативно для push).
    if (e.state === 'offline') e.state = 'background';
    this.persistDeviceSeen(deviceId, now);
    this.emitPresence(deviceId);
  }

  removeSocket(ws) {
    const meta = this.sockets.get(ws);
    if (!meta) return;
    this.sockets.delete(ws);
    if (meta.kind === 'device') this.removeDeviceSocket(meta.id, ws);
    else this.removeAdminSocket(ws, meta.id);
  }

  removeDeviceSocket(deviceId, ws) {
    const e = this.devices.get(deviceId);
    if (!e) return;
    e.sockets.delete(ws);
    if (e.sockets.size > 0) return;
    const now = new Date();
    e.lastSeen = now;
    this.persistDeviceSeen(deviceId, now);
    if (e.typingChat) this.setTyping(e.typingChat, 'user', false);
    // Через presenceOfflineMs без сокета — offline.
    clearTimeout(e.offlineTimer);
    e.offlineTimer = setTimeout(() => {
      e.offlineTimer = null;
      if (e.sockets.size > 0) return;
      e.state = 'offline';
      e.typingIn = null;
      this.emitPresence(deviceId);
    }, this.cfg.presenceOfflineMs);
    e.offlineTimer.unref?.();
  }

  setDeviceState(deviceId, state) {
    const e = this.deviceEntry(deviceId);
    e.lastSeen = new Date();
    if (e.state === state) return;
    e.state = state;
    this.emitPresence(deviceId);
  }

  /** Любой кадр от клиента = «сокет жив». */
  touch(ws) {
    const meta = this.sockets.get(ws);
    if (!meta) return;
    const now = Date.now();
    meta.lastFrameAt = now;
    if (meta.kind === 'device') {
      const e = this.devices.get(meta.id);
      if (e) e.lastSeen = new Date(now);
    } else {
      this.admin.lastSeen = new Date(now);
    }
    if (now - meta.lastPersist > 60_000) {
      meta.lastPersist = now;
      if (meta.kind === 'device') this.persistDeviceSeen(meta.id, new Date(now));
      else this.persistAdminSeen(meta.id, new Date(now));
    }
  }

  /** Текущее присутствие устройства; dbLastSeen — запасное значение из БД (после рестарта сервера). */
  devicePresence(deviceId, dbLastSeen = null) {
    const e = this.devices.get(deviceId);
    if (!e) return { state: 'offline', typingIn: null, lastSeen: dbLastSeen ? new Date(dbLastSeen).toISOString() : null };
    const last = e.sockets.size > 0 ? new Date() : e.lastSeen || (dbLastSeen ? new Date(dbLastSeen) : null);
    return { state: e.state, typingIn: e.typingIn, lastSeen: last ? last.toISOString() : null };
  }

  deviceState(deviceId) {
    return this.devices.get(deviceId)?.state || 'offline';
  }

  emitPresence(deviceId) {
    const p = this.devicePresence(deviceId);
    this.sendToAdmins({ t: 'presence', deviceId, state: p.state, typingIn: p.typingIn, lastSeen: p.lastSeen });
  }

  /** Снимок присутствия всех «живых» устройств — отправляется админу сразу после подключения. */
  presenceSnapshot() {
    const out = [];
    for (const [deviceId, e] of this.devices) {
      if (e.state !== 'offline') out.push({ t: 'presence', deviceId, ...this.devicePresence(deviceId) });
    }
    return out;
  }

  counts() {
    let online = 0;
    let inBackground = 0;
    for (const e of this.devices.values()) {
      if (e.state === 'foreground') online += 1;
      else if (e.state === 'background') inBackground += 1;
    }
    return { online, inBackground };
  }

  kickDevice(deviceId, reason) {
    const e = this.devices.get(deviceId);
    if (!e) return;
    for (const ws of [...e.sockets]) {
      this.send(ws, { t: 'blocked', message: reason || '' });
      try { ws.close(4003, 'blocked'); } catch { /* ignore */ }
    }
  }

  // ---------- админы (одна логическая сторона «admin») ----------

  addAdminSocket(adminId, ws) {
    const now = new Date();
    this.sockets.set(ws, { kind: 'admin', id: adminId, state: 'background', lastFrameAt: Date.now(), lastPersist: Date.now() });
    this.admin.sockets.add(ws);
    clearTimeout(this.admin.offlineTimer);
    this.admin.offlineTimer = null;
    this.admin.lastSeen = now;
    this.recomputeAdminState();
    this.persistAdminSeen(adminId, now);
    for (const frame of this.presenceSnapshot()) this.send(ws, frame);
  }

  removeAdminSocket(ws, adminId) {
    this.admin.sockets.delete(ws);
    const now = new Date();
    this.admin.lastSeen = now;
    this.persistAdminSeen(adminId, now);
    if (this.admin.sockets.size > 0) {
      this.recomputeAdminState();
      return;
    }
    for (const { chat } of [...this.adminTyping.values()]) this.setTyping(chat, 'admin', false);
    clearTimeout(this.admin.offlineTimer);
    this.admin.offlineTimer = setTimeout(() => {
      if (this.admin.sockets.size === 0) this.admin.state = 'offline';
    }, this.cfg.presenceOfflineMs);
    this.admin.offlineTimer.unref?.();
  }

  setAdminState(ws, state) {
    const meta = this.sockets.get(ws);
    if (meta) meta.state = state;
    this.admin.lastSeen = new Date();
    this.recomputeAdminState();
  }

  recomputeAdminState() {
    let state = this.admin.sockets.size > 0 ? 'background' : this.admin.state;
    for (const ws of this.admin.sockets) if (this.sockets.get(ws)?.state === 'foreground') state = 'foreground';
    this.admin.state = state;
  }

  adminPresence(dbLastSeen = null) {
    const last = this.admin.sockets.size > 0 ? new Date() : this.admin.lastSeen || (dbLastSeen ? new Date(dbLastSeen) : null);
    return { state: this.admin.state, lastSeen: last ? last.toISOString() : null };
  }

  // ---------- «печатает…» ----------

  /**
   * who: 'user' | 'admin' | 'ai'. Для user/admin действует TTL (typingTtlMs): если клиент не прислал
   * typing:false, индикатор гаснет сам. Для ai индикатор управляется сервером явно.
   */
  setTyping(chat, who, typing) {
    const chatRef = { id: chat.id, device_id: chat.device_id };
    let changed = false;
    if (who === 'user') {
      const e = this.deviceEntry(chat.device_id);
      clearTimeout(e.typingTimer);
      e.typingTimer = null;
      const next = typing ? chat.id : null;
      changed = e.typingIn !== next;
      e.typingIn = next;
      e.typingChat = typing ? chatRef : null;
      if (typing) {
        e.typingTimer = setTimeout(() => this.setTyping(chatRef, 'user', false), this.cfg.typingTtlMs);
        e.typingTimer.unref?.();
      }
      if (changed) this.emitPresence(chat.device_id);
    } else if (who === 'admin') {
      clearTimeout(this.adminTyping.get(chat.id)?.timer);
      changed = this.adminTyping.has(chat.id) !== typing;
      if (typing) {
        const timer = setTimeout(() => this.setTyping(chatRef, 'admin', false), this.cfg.typingTtlMs);
        timer.unref?.();
        this.adminTyping.set(chat.id, { timer, chat: chatRef });
      } else {
        this.adminTyping.delete(chat.id);
      }
    } else if (who === 'ai') {
      changed = this.aiTyping.has(chat.id) !== typing;
      if (typing) this.aiTyping.add(chat.id);
      else this.aiTyping.delete(chat.id);
    }
    // Повторный typing:true тоже пересылаем (клиенты продлевают свой индикатор), typing:false — только при смене.
    if (changed || typing) this.sendToChat(chatRef, { t: 'typing', chatId: chat.id, who, typing });
  }

  /** Печатает ли собеседник стороны side в чате. */
  peerTyping(chat, side) {
    if (side === 'admin') return this.devices.get(chat.device_id)?.typingIn === chat.id;
    return this.adminTyping.has(chat.id) || this.aiTyping.has(chat.id);
  }

  // ---------- обслуживание ----------

  reapIdle() {
    const now = Date.now();
    for (const [ws, meta] of this.sockets) {
      if (now - meta.lastFrameAt > this.cfg.wsIdleMs) {
        try { ws.terminate(); } catch { /* ignore */ }
        this.removeSocket(ws);
      }
    }
  }

  stop() {
    clearInterval(this.idleTimer);
    const now = new Date();
    for (const [deviceId, e] of this.devices) {
      clearTimeout(e.offlineTimer);
      clearTimeout(e.typingTimer);
      if (e.sockets.size > 0) this.persistDeviceSeen(deviceId, now);
    }
    for (const { timer } of this.adminTyping.values()) clearTimeout(timer);
    clearTimeout(this.admin.offlineTimer);
  }
}
