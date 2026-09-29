// Простой in-memory лимитер «N событий за окно» по ключу (скользящее окно по отметкам времени).
// Одного инстанса сервера достаточно для Railway; при горизонтальном масштабировании — вынести в Redis/БД.

export class RateLimiter {
  constructor({ max, windowMs }) {
    this.max = max;
    this.windowMs = windowMs;
    this.hits = new Map();
    // Периодическая чистка, чтобы Map не рос бесконечно.
    this.timer = setInterval(() => this.sweep(), Math.max(windowMs, 60_000));
    this.timer.unref();
  }

  /** Возвращает true, если событие разрешено (и учитывает его). */
  take(key) {
    const now = Date.now();
    const arr = (this.hits.get(key) || []).filter((t) => now - t < this.windowMs);
    if (arr.length >= this.max) {
      this.hits.set(key, arr);
      return false;
    }
    arr.push(now);
    this.hits.set(key, arr);
    return true;
  }

  /** Проверка без учёта события. */
  blocked(key) {
    const now = Date.now();
    const arr = (this.hits.get(key) || []).filter((t) => now - t < this.windowMs);
    return arr.length >= this.max;
  }

  /** Учитывает событие без проверки (например, неудачную попытку входа). */
  record(key) {
    const arr = this.hits.get(key) || [];
    arr.push(Date.now());
    this.hits.set(key, arr);
  }

  sweep() {
    const now = Date.now();
    for (const [k, arr] of this.hits) {
      const fresh = arr.filter((t) => now - t < this.windowMs);
      if (fresh.length) this.hits.set(k, fresh);
      else this.hits.delete(k);
    }
  }

  stop() {
    clearInterval(this.timer);
  }
}
