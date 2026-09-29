// Пароли администраторов: PBKDF2-SHA256 через Web Crypto (работает на Deno Deploy) со случайной солью,
// сравнение за постоянное время. Формат хранения: "pbkdf2$<iterations>$<соль base64url>$<хеш base64url>".
import { fromBase64Url, randomBytes, timingSafeEqualBytes, toBase64Url } from "./crypto-utils.ts";

const ITERATIONS = 100_000;
const KEY_BITS = 256;
const enc = new TextEncoder();

async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", enc.encode(String(password).normalize("NFKC")), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: salt as BufferSource, iterations, hash: "SHA-256" }, key, KEY_BITS);
  return new Uint8Array(bits);
}

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const key = await pbkdf2(password, salt, ITERATIONS);
  return `pbkdf2$${ITERATIONS}$${toBase64Url(salt)}$${toBase64Url(key)}`;
}

// Хеш-«пустышка»: проверяем пароль и для несуществующего логина, чтобы время ответа не выдавало наличие аккаунта.
let dummyHash: string | null = null;
async function dummy(): Promise<string> {
  if (!dummyHash) dummyHash = await hashPassword(toBase64Url(randomBytes(12)));
  return dummyHash;
}

export async function verifyPassword(password: string, stored: string | null): Promise<boolean> {
  const target = stored || (await dummy());
  const parts = String(target).split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2") return false;
  const [, iter, saltB64, keyB64] = parts;
  const expected = fromBase64Url(keyB64);
  let actual: Uint8Array;
  try {
    actual = await pbkdf2(password, fromBase64Url(saltB64), Number(iter));
  } catch {
    return false;
  }
  return timingSafeEqualBytes(actual, expected) && Boolean(stored);
}

export const LOGIN_PATTERN = "^[A-Za-z0-9._@-]{3,64}$";
export const normalizeLogin = (login: string) => String(login || "").trim().toLowerCase();
