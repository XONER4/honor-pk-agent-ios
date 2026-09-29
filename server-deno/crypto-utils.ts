// Криптопримитивы на встроенном Web Crypto (без зависимостей от Node-модулей) — работает на Deno Deploy.

/** hex-строка из байтов. */
export function toHex(buf: ArrayBuffer | Uint8Array): string {
  const arr = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let s = "";
  for (let i = 0; i < arr.length; i++) s += arr[i].toString(16).padStart(2, "0");
  return s;
}

/** base64url (без padding) из байтов. */
export function toBase64Url(buf: ArrayBuffer | Uint8Array): string {
  const arr = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let bin = "";
  for (let i = 0; i < arr.length; i++) bin += String.fromCharCode(arr[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Байты из base64url. */
export function fromBase64Url(str: string): Uint8Array {
  const b64 = str.replace(/-/g, "+").replace(/_/g, "/");
  const pad = b64.length % 4 === 0 ? "" : "=".repeat(4 - (b64.length % 4));
  const bin = atob(b64 + pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Случайные байты. */
export const randomBytes = (n: number): Uint8Array => crypto.getRandomValues(new Uint8Array(n));

/** Равномерное целое в [min, max) без смещения (rejection sampling). */
export function randomInt(min: number, max: number): number {
  const range = max - min;
  if (range <= 0) throw new Error("randomInt: max must be > min");
  const maxUint = 0x1_0000_0000; // 2^32
  const limit = Math.floor(maxUint / range) * range;
  const buf = new Uint32Array(1);
  let x: number;
  do {
    crypto.getRandomValues(buf);
    x = buf[0];
  } while (x >= limit);
  return min + (x % range);
}

const enc = new TextEncoder();

/** SHA-256 в байтах. */
export async function sha256Bytes(input: string | Uint8Array): Promise<Uint8Array> {
  const data = typeof input === "string" ? enc.encode(input) : input;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data as BufferSource));
}

/** SHA-256 в hex. */
export const sha256Hex = async (input: string | Uint8Array): Promise<string> => toHex(await sha256Bytes(input));

/** Сравнение байтов за постоянное время (длина сравнивается отдельно). */
export function timingSafeEqualBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** Сравнение секретов за (примерно) постоянное время: хешируем оба, сверяем + отдельно длину. */
export async function safeEqual(a: string, b: string): Promise<boolean> {
  const ha = await sha256Bytes(String(a));
  const hb = await sha256Bytes(String(b));
  return timingSafeEqualBytes(ha, hb) && String(a).length === String(b).length;
}
