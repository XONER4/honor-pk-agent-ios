// Публичный ID пользователя: строка из 4 цифр ("0000".."9999"), случайная и уникальная.
// Когда все 10 000 четырёхзначных заняты — 5 цифр, затем 6 и т. д.
import { randomInt } from "./crypto-utils.ts";
import type { Kv } from "./kv.ts";

export function pickPublicId(used: Set<string>, random: (a: number, b: number) => number = randomInt): string {
  for (let digits = 4; digits <= 9; digits++) {
    const size = 10 ** digits;
    const taken = [...used].filter((v) => v.length === digits).length;
    if (taken >= size) continue;
    const fmt = (n: number) => String(n).padStart(digits, "0");
    for (let i = 0; i < 40; i++) {
      const id = fmt(random(0, size));
      if (!used.has(id)) return id;
    }
    const free: string[] = [];
    for (let n = 0; n < size; n++) if (!used.has(fmt(n))) free.push(fmt(n));
    if (free.length) return free[random(0, free.length)];
  }
  throw new Error("public id space exhausted");
}

async function usedIds(kv: Kv): Promise<Set<string>> {
  const set = new Set<string>();
  for await (const e of kv.list<string>({ prefix: ["publicId"] })) set.add(String(e.key[1]));
  return set;
}

/** Выдаёт пользователю ID, если его ещё нет. Возвращает ID. */
export async function ensurePublicId(kv: Kv, userId: string): Promise<string | null> {
  const user = (await kv.get<{ id: string; publicId: string | null; createdAt: number }>(["user", userId])).value;
  if (!user) return null;
  if (user.publicId) return user.publicId;
  for (let attempt = 0; attempt < 8; attempt++) {
    const id = pickPublicId(await usedIds(kv));
    // Атомарно занимаем номер: индекс publicId должен быть свободен.
    const res = await kv.atomic()
      .check({ key: ["publicId", id], versionstamp: null })
      .check({ key: ["user", userId], versionstamp: (await kv.get(["user", userId])).versionstamp })
      .set(["publicId", id], userId)
      .set(["user", userId], { ...user, publicId: id })
      .commit();
    if (res.ok) return id;
    const fresh = (await kv.get<{ publicId: string | null }>(["user", userId])).value;
    if (fresh?.publicId) return fresh.publicId;
  }
  throw new Error("could not assign public id");
}

/** Досыпает ID всем пользователям без него (при старте сервера). */
export async function backfillPublicIds(kv: Kv): Promise<number> {
  let done = 0;
  const used = await usedIds(kv);
  for await (const e of kv.list<{ id: string; publicId: string | null; createdAt: number }>({ prefix: ["user"] })) {
    if (e.value.publicId) continue;
    const id = pickPublicId(used);
    const res = await kv.atomic()
      .check({ key: ["publicId", id], versionstamp: null })
      .set(["publicId", id], e.value.id)
      .set(["user", e.value.id], { ...e.value, publicId: id })
      .commit();
    if (res.ok) {
      used.add(id);
      done += 1;
    }
  }
  return done;
}
