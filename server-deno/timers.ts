// Хелпер для таймеров: в Deno setInterval/setTimeout возвращают number, но типы node:* добавляют Timeout.
export type TimerId = ReturnType<typeof setInterval>;

export function unref(t: TimerId): void {
  try {
    // deno-lint-ignore no-explicit-any
    (Deno as any).unrefTimer?.(t as unknown as number);
  } catch { /* ignore */ }
}
