// Простой in-memory лимитер «N событий за окно» по ключу (скользящее окно по отметкам времени).
import { type TimerId, unref } from "./timers.ts";

export class RateLimiter {
  max: number;
  windowMs: number;
  hits: Map<string, number[]>;
  timer: TimerId;
  constructor({ max, windowMs }: { max: number; windowMs: number }) {
    this.max = max;
    this.windowMs = windowMs;
    this.hits = new Map();
    this.timer = setInterval(() => this.sweep(), Math.max(windowMs, 60_000));
    unref(this.timer);
  }
  take(key: string): boolean {
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
  blocked(key: string): boolean {
    const now = Date.now();
    const arr = (this.hits.get(key) || []).filter((t) => now - t < this.windowMs);
    return arr.length >= this.max;
  }
  record(key: string): void {
    const arr = this.hits.get(key) || [];
    arr.push(Date.now());
    this.hits.set(key, arr);
  }
  sweep(): void {
    const now = Date.now();
    for (const [k, arr] of this.hits) {
      const fresh = arr.filter((t) => now - t < this.windowMs);
      if (fresh.length) this.hits.set(k, fresh);
      else this.hits.delete(k);
    }
  }
  stop(): void {
    clearInterval(this.timer);
  }
}
