// Пароли администраторов: node:crypto scrypt со случайной солью, сравнение за постоянное время.
// Формат хранения: "scrypt$N$r$p$<соль base64url>$<хеш base64url>" — параметры внутри строки,
// поэтому их можно усилить позже, не ломая старые хеши.
import { randomBytes, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';

const N = 16384;
const R = 8;
const P = 1;
const KEY_LEN = 64;
const MAX_MEM = 64 * 1024 * 1024;

function scrypt(password, salt, keyLen, opts) {
  return new Promise((resolve, reject) => {
    scryptCb(password, salt, keyLen, opts, (err, key) => (err ? reject(err) : resolve(key)));
  });
}

export async function hashPassword(password) {
  const salt = randomBytes(16);
  const key = await scrypt(String(password).normalize('NFKC'), salt, KEY_LEN, { N, r: R, p: P, maxmem: MAX_MEM });
  return `scrypt$${N}$${R}$${P}$${salt.toString('base64url')}$${key.toString('base64url')}`;
}

// Хеш-«пустышка»: проверяем пароль и для несуществующего логина, чтобы время ответа не выдавало,
// есть ли такой аккаунт.
let dummyHash = null;
async function dummy() {
  if (!dummyHash) dummyHash = await hashPassword(randomBytes(12).toString('hex'));
  return dummyHash;
}

/** true, если пароль подходит к хешу. stored = null → сравнение с пустышкой и false. */
export async function verifyPassword(password, stored) {
  const target = stored || (await dummy());
  const parts = String(target).split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
  const [, n, r, p, saltB64, keyB64] = parts;
  const expected = Buffer.from(keyB64, 'base64url');
  let actual;
  try {
    actual = await scrypt(String(password).normalize('NFKC'), Buffer.from(saltB64, 'base64url'), expected.length,
      { N: Number(n), r: Number(r), p: Number(p), maxmem: MAX_MEM });
  } catch {
    return false;
  }
  return timingSafeEqual(actual, expected) && Boolean(stored);
}

/** Логин: 3–64 символа, латиница/цифры/._@-; хранится в нижнем регистре. */
export const LOGIN_PATTERN = '^[A-Za-z0-9._@-]{3,64}$';
export const normalizeLogin = (login) => String(login || '').trim().toLowerCase();
