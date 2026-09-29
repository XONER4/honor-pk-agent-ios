// Метрики реального времени (в памяти) и расход ИИ по дням (в KV, ["usage", day, deviceId]).
import type { Kv } from "./kv.ts";

export const NO_DEVICE = "00000000-0000-0000-0000-000000000000";

export const dayKey = (d: number | Date = Date.now()) => new Date(d).toISOString().slice(0, 10);

export function percentile(values: number[], p: number): number | null {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

class SecondCounter {
  size: number;
  counts: number[];
  stamps: number[];
  constructor(size = 60) {
    this.size = size;
    this.counts = new Array(size).fill(0);
    this.stamps = new Array(size).fill(-1);
  }
  add(n = 1, nowMs = Date.now()) {
    const sec = Math.floor(nowMs / 1000);
    const i = sec % this.size;
    if (this.stamps[i] !== sec) {
      this.stamps[i] = sec;
      this.counts[i] = 0;
    }
    this.counts[i] += n;
  }
  total(nowMs = Date.now()) {
    const sec = Math.floor(nowMs / 1000);
    let sum = 0;
    for (let i = 0; i < this.size; i++) if (this.stamps[i] > sec - this.size) sum += this.counts[i];
    return sum;
  }
}

class LatencyWindow {
  windowMs: number;
  max: number;
  samples: [number, number][];
  constructor({ windowMs, max }: { windowMs: number; max: number }) {
    this.windowMs = windowMs;
    this.max = max;
    this.samples = [];
  }
  add(ms: number, nowMs = Date.now()) {
    this.samples.push([nowMs, ms]);
    if (this.samples.length > this.max) this.samples.splice(0, this.samples.length - this.max);
  }
  stats(nowMs = Date.now()) {
    const from = nowMs - this.windowMs;
    while (this.samples.length && this.samples[0][0] < from) this.samples.shift();
    const values = this.samples.map((s) => s[1]);
    const avg = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
    const round = (v: number | null) => (v == null ? null : Math.round(v));
    return { p50: round(percentile(values, 50)), p95: round(percentile(values, 95)), avg: round(avg), count: values.length };
  }
}

export class LiveMetrics {
  requests = new SecondCounter(60);
  errors = new SecondCounter(60);
  aiLatency: LatencyWindow;
  apiLatency: LatencyWindow;
  constructor({ latencyWindowMs = 15 * 60_000 }: { latencyWindowMs?: number } = {}) {
    this.aiLatency = new LatencyWindow({ windowMs: latencyWindowMs, max: 2000 });
    this.apiLatency = new LatencyWindow({ windowMs: 5 * 60_000, max: 5000 });
  }
  recordRequest() {
    this.requests.add(1);
  }
  recordResponse(status: number, ms: number) {
    if (Number.isFinite(ms)) this.apiLatency.add(ms);
    if (status >= 500) this.errors.add(1);
  }
  recordAi({ ms, error = false }: { ms: number; error?: boolean }) {
    if (Number.isFinite(ms) && !error) this.aiLatency.add(ms);
    if (error) this.errors.add(1);
  }
  snapshot() {
    const requests1m = this.requests.total();
    return {
      rps: Math.round((requests1m / 60) * 100) / 100,
      requests1m,
      errors1m: this.errors.total(),
      aiLatencyMs: this.aiLatency.stats(),
      apiLatencyMs: this.apiLatency.stats(),
    };
  }
}

interface UsageRow {
  prompt: number;
  completion: number;
  requests: number;
  errors: number;
}
const shape = (r: Partial<UsageRow> | null) => {
  const prompt = Number(r?.prompt) || 0;
  const completion = Number(r?.completion) || 0;
  return { prompt, completion, total: prompt + completion, requests: Number(r?.requests) || 0, errors: Number(r?.errors) || 0 };
};

export function createUsage(kv: Kv) {
  async function add(
    deviceId: string | null,
    { prompt = 0, completion = 0, requests = 0, errors = 0 }: Partial<{ prompt: number; completion: number; requests: number; errors: number }> = {},
  ) {
    const p = Math.max(0, Math.round(Number(prompt) || 0));
    const c = Math.max(0, Math.round(Number(completion) || 0));
    const key = ["usage", dayKey(), deviceId || NO_DEVICE];
    try {
      // Read-modify-write с одной атомарной проверкой (single-instance — гонок практически нет).
      for (let i = 0; i < 5; i++) {
        const cur = await kv.get<UsageRow>(key);
        const v = cur.value || { prompt: 0, completion: 0, requests: 0, errors: 0 };
        const next = {
          prompt: v.prompt + p,
          completion: v.completion + c,
          requests: v.requests + requests,
          errors: v.errors + errors,
        };
        const res = await kv.atomic().check(cur).set(key, next).commit();
        if (res.ok) return;
      }
    } catch {
      /* статистика не должна ломать ответ */
    }
  }

  async function sumRows(filter: (day: string, deviceId: string) => boolean): Promise<UsageRow> {
    const acc = { prompt: 0, completion: 0, requests: 0, errors: 0 };
    for await (const e of kv.list<UsageRow>({ prefix: ["usage"] })) {
      const [, day, deviceId] = e.key as [string, string, string];
      if (!filter(day, deviceId)) continue;
      acc.prompt += e.value.prompt;
      acc.completion += e.value.completion;
      acc.requests += e.value.requests;
      acc.errors += e.value.errors;
    }
    return acc;
  }

  async function totals() {
    const day = dayKey();
    return { today: shape(await sumRows((d) => d === day)), total: shape(await sumRows(() => true)) };
  }
  async function forDevice(deviceId: string) {
    const day = dayKey();
    return {
      today: shape(await sumRows((d, id) => d === day && id === deviceId)),
      total: shape(await sumRows((_d, id) => id === deviceId)),
    };
  }
  async function totalsByDevice(): Promise<Map<string, number>> {
    const m = new Map<string, number>();
    for await (const e of kv.list<UsageRow>({ prefix: ["usage"] })) {
      const id = String(e.key[2]);
      m.set(id, (m.get(id) || 0) + e.value.prompt + e.value.completion);
    }
    return m;
  }

  return { add, totals, forDevice, totalsByDevice };
}

export type Usage = ReturnType<typeof createUsage>;

/** Выбирает usage из ответа DeepSeek на лету: JSON целиком или SSE-чанки. */
export class UsageSniffer {
  stream: boolean;
  usage: { prompt_tokens?: number; completion_tokens?: number } | null = null;
  buf = "";
  decoder = new TextDecoder();
  overflow = false;
  constructor(stream: boolean) {
    this.stream = stream;
  }
  push(chunk: Uint8Array) {
    const text = this.decoder.decode(chunk, { stream: true });
    if (this.stream) {
      this.buf += text;
      let nl: number;
      while ((nl = this.buf.indexOf("\n")) >= 0) {
        this.line(this.buf.slice(0, nl));
        this.buf = this.buf.slice(nl + 1);
      }
      if (this.buf.length > 1024 * 1024) this.buf = "";
    } else if (!this.overflow) {
      this.buf += text;
      if (this.buf.length > 8 * 1024 * 1024) {
        this.overflow = true;
        this.buf = "";
      }
    }
  }
  line(raw: string) {
    const line = raw.trim();
    if (!line.startsWith("data:") || !line.includes('"usage"')) return;
    const data = line.slice(5).trim();
    try {
      const obj = JSON.parse(data);
      if (obj?.usage) this.usage = obj.usage;
    } catch { /* неполный чанк */ }
  }
  finish(): { prompt: number; completion: number } | null {
    if (this.stream) {
      if (this.buf) this.line(this.buf);
    } else if (!this.overflow && this.buf) {
      try {
        const obj = JSON.parse(this.buf + this.decoder.decode());
        if (obj?.usage) this.usage = obj.usage;
      } catch { /* не JSON */ }
    }
    this.buf = "";
    const u = this.usage;
    return u ? { prompt: Number(u.prompt_tokens) || 0, completion: Number(u.completion_tokens) || 0 } : null;
  }
}
