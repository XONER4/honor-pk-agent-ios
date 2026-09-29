// Метрики реального времени (в памяти процесса) и расход ИИ по дням (в БД, таблица usage_daily).
// В памяти: запросы в секунду и ошибки за последние 60 с, задержки AI-прокси и API (p50/p95/среднее).
// Суммы токенов/запросов/ошибок ИИ пишутся в usage_daily — переживают перезапуск.

export const NO_DEVICE = '00000000-0000-0000-0000-000000000000'; // запросы админа и прочие без устройства

/** "YYYY-MM-DD" по UTC. */
export const dayKey = (d = new Date()) => new Date(d).toISOString().slice(0, 10);

/** Перцентиль (0..100) по массиву чисел; пустой массив → null. */
export function percentile(values, p) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

/** Счётчик по секундам за последние `size` секунд (кольцевой буфер). */
class SecondCounter {
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

  /** Сумма за последние `size` секунд. */
  total(nowMs = Date.now()) {
    const sec = Math.floor(nowMs / 1000);
    let sum = 0;
    for (let i = 0; i < this.size; i++) if (this.stamps[i] > sec - this.size) sum += this.counts[i];
    return sum;
  }
}

/** Скользящее окно замеров времени (мс) — для перцентилей. */
class LatencyWindow {
  constructor({ windowMs, max }) {
    this.windowMs = windowMs;
    this.max = max;
    this.samples = []; // [at, ms]
  }

  add(ms, nowMs = Date.now()) {
    this.samples.push([nowMs, ms]);
    if (this.samples.length > this.max) this.samples.splice(0, this.samples.length - this.max);
  }

  stats(nowMs = Date.now()) {
    const from = nowMs - this.windowMs;
    while (this.samples.length && this.samples[0][0] < from) this.samples.shift();
    const values = this.samples.map((s) => s[1]);
    const avg = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
    const round = (v) => (v == null ? null : Math.round(v));
    return { p50: round(percentile(values, 50)), p95: round(percentile(values, 95)), avg: round(avg), count: values.length };
  }
}

export class LiveMetrics {
  constructor({ latencyWindowMs = 15 * 60_000 } = {}) {
    this.requests = new SecondCounter(60);
    this.errors = new SecondCounter(60);
    this.aiLatency = new LatencyWindow({ windowMs: latencyWindowMs, max: 2000 });
    this.apiLatency = new LatencyWindow({ windowMs: 5 * 60_000, max: 5000 });
  }

  /** Пришёл HTTP-запрос (для «запросов в секунду»). */
  recordRequest() {
    this.requests.add(1);
  }

  /** Обычный API-ответ: время обработки и 5xx как ошибка. */
  recordResponse(status, ms) {
    if (Number.isFinite(ms)) this.apiLatency.add(ms);
    if (status >= 500) this.errors.add(1);
  }

  /** Запрос к ИИ завершён (прокси или групповой ИИ). */
  recordAi({ ms, error = false }) {
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

/** Расход ИИ в БД. */
export function createUsage({ db, logger }) {
  /** Прибавляет к строке (сегодня, устройство). Ошибки БД не мешают ответу ИИ — только в лог. */
  async function add(deviceId, { prompt = 0, completion = 0, requests = 0, errors = 0 } = {}) {
    const p = Math.max(0, Math.round(Number(prompt) || 0));
    const c = Math.max(0, Math.round(Number(completion) || 0));
    try {
      await db.query(
        `INSERT INTO usage_daily (day, device_id, tokens_prompt, tokens_completion, requests, errors)
         VALUES ($1, $2, $3, $4, $5, $6)
         ON CONFLICT (day, device_id) DO UPDATE SET
           tokens_prompt = usage_daily.tokens_prompt + $3,
           tokens_completion = usage_daily.tokens_completion + $4,
           requests = usage_daily.requests + $5,
           errors = usage_daily.errors + $6`,
        [dayKey(), deviceId || NO_DEVICE, p, c, requests, errors]);
    } catch (err) {
      logger?.warn({ err: { message: err.message } }, 'usage write failed');
    }
  }

  const SUM = `COALESCE(sum(tokens_prompt), 0)::bigint AS prompt, COALESCE(sum(tokens_completion), 0)::bigint AS completion,
               COALESCE(sum(requests), 0)::bigint AS requests, COALESCE(sum(errors), 0)::bigint AS errors`;
  const shape = (r) => {
    const prompt = Number(r?.prompt) || 0;
    const completion = Number(r?.completion) || 0;
    return { prompt, completion, total: prompt + completion, requests: Number(r?.requests) || 0, errors: Number(r?.errors) || 0 };
  };

  /** { today, total } по всем устройствам (и админу). */
  async function totals() {
    const today = await db.one(`SELECT ${SUM} FROM usage_daily WHERE day = $1`, [dayKey()]);
    const total = await db.one(`SELECT ${SUM} FROM usage_daily`);
    return { today: shape(today), total: shape(total) };
  }

  /** { today, total } одного устройства. */
  async function forDevice(deviceId) {
    const today = await db.one(`SELECT ${SUM} FROM usage_daily WHERE day = $1 AND device_id = $2`, [dayKey(), deviceId]);
    const total = await db.one(`SELECT ${SUM} FROM usage_daily WHERE device_id = $1`, [deviceId]);
    return { today: shape(today), total: shape(total) };
  }

  /** Map deviceId → токены всего (для списка пользователей). */
  async function totalsByDevice() {
    const rows = await db.many(
      `SELECT device_id, COALESCE(sum(tokens_prompt), 0)::bigint AS prompt, COALESCE(sum(tokens_completion), 0)::bigint AS completion
         FROM usage_daily GROUP BY device_id`);
    return new Map(rows.map((r) => [r.device_id, (Number(r.prompt) || 0) + (Number(r.completion) || 0)]));
  }

  return { add, totals, forDevice, totalsByDevice };
}

/**
 * Выбирает usage из ответа DeepSeek, пока байты идут клиенту: JSON целиком или SSE-чанки
 * (usage приходит в последнем чанке при stream_options.include_usage). Буфер ограничен.
 */
export class UsageSniffer {
  constructor(stream) {
    this.stream = stream;
    this.usage = null;
    this.buf = '';
    this.decoder = new TextDecoder();
    this.overflow = false;
  }

  push(chunk) {
    const text = this.decoder.decode(chunk, { stream: true });
    if (this.stream) {
      this.buf += text;
      let nl;
      while ((nl = this.buf.indexOf('\n')) >= 0) {
        this.line(this.buf.slice(0, nl));
        this.buf = this.buf.slice(nl + 1);
      }
      if (this.buf.length > 1024 * 1024) this.buf = ''; // одна «строка» без переводов — не SSE
    } else if (!this.overflow) {
      this.buf += text;
      if (this.buf.length > 8 * 1024 * 1024) { this.overflow = true; this.buf = ''; }
    }
  }

  line(raw) {
    const line = raw.trim();
    if (!line.startsWith('data:') || !line.includes('"usage"')) return;
    const data = line.slice(5).trim();
    try {
      const obj = JSON.parse(data);
      if (obj?.usage) this.usage = obj.usage;
    } catch { /* неполный/чужой чанк */ }
  }

  finish() {
    if (this.stream) {
      if (this.buf) this.line(this.buf);
    } else if (!this.overflow && this.buf) {
      try {
        const obj = JSON.parse(this.buf + this.decoder.decode());
        if (obj?.usage) this.usage = obj.usage;
      } catch { /* не JSON */ }
    }
    this.buf = '';
    const u = this.usage;
    return u ? { prompt: Number(u.prompt_tokens) || 0, completion: Number(u.completion_tokens) || 0 } : null;
  }
}
