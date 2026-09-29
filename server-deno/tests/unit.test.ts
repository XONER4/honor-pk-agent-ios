import { assert, assertEquals, assertMatch, assertNotEquals } from "./assert.ts";
import { backfillPublicIds, pickPublicId } from "../public-id.ts";
import { hashPassword, verifyPassword } from "../passwords.ts";
import { inSchedule } from "../ai-control.ts";
import { percentile, UsageSniffer } from "../metrics.ts";
import { isQuestion, mentionsAi } from "../ai.ts";
import { parseRange } from "../media.ts";
import { loadConfig } from "../config.ts";

const enc = (s: string) => new TextEncoder().encode(s);

Deno.test("passwords: scrypt with random salt, verify, dummy", async () => {
  const a = await hashPassword("секрет-123");
  const b = await hashPassword("секрет-123");
  assertMatch(a, /^scrypt\$16384\$8\$1\$/);
  assertNotEquals(a, b);
  assertEquals(await verifyPassword("секрет-123", a), true);
  assertEquals(await verifyPassword("секрет-124", a), false);
  assertEquals(await verifyPassword("секрет-123", null), false);
  assertEquals(await verifyPassword("x", "garbage"), false);
});

Deno.test("pickPublicId: 4 digits, then 5 when the range is full", () => {
  assertMatch(pickPublicId(new Set()), /^\d{4}$/);
  const full = new Set(Array.from({ length: 10_000 }, (_, i) => String(i).padStart(4, "0")));
  assertMatch(pickPublicId(full), /^\d{5}$/);
  const almost = new Set(full);
  almost.delete("0042");
  assertEquals(pickPublicId(almost), "0042");
});

Deno.test("backfillPublicIds assigns to old users", async () => {
  const kv = await Deno.openKv(":memory:");
  const id = crypto.randomUUID();
  await kv.set(["user", id], { id, createdAt: Date.now(), publicId: null });
  await backfillPublicIds(kv);
  const row = (await kv.get<{ publicId: string }>(["user", id])).value;
  assertMatch(row!.publicId, /^\d{4}$/);
  kv.close();
});

Deno.test("inSchedule: days, windows, overnight, timezone", () => {
  const tz = "Europe/Moscow";
  const mon10 = new Date("2026-09-28T07:00:00Z");
  const mon23 = new Date("2026-09-28T20:30:00Z");
  const tue02 = new Date("2026-09-28T23:30:00Z");
  assertEquals(inSchedule([], mon10, tz), true);
  assertEquals(inSchedule([{ days: [1], from: "09:00", to: "18:00" }], mon10, tz), true);
  assertEquals(inSchedule([{ days: [2], from: "09:00", to: "18:00" }], mon10, tz), false);
  assertEquals(inSchedule([{ days: [1], from: "09:00", to: "18:00" }], mon23, tz), false);
  const night = [{ days: [1], from: "22:00", to: "03:00" }];
  assertEquals(inSchedule(night, mon23, tz), true);
  assertEquals(inSchedule(night, tue02, tz), true);
  assertEquals(inSchedule(night, mon10, tz), false);
  assertEquals(inSchedule([{ days: [], from: "00:00", to: "00:00" }], mon10, tz), true);
});

Deno.test("percentile and usage sniffer (JSON and SSE)", () => {
  assertEquals(percentile([], 50), null);
  assertEquals(percentile([5, 1, 3, 2, 4], 50), 3);
  assertEquals(percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 95), 10);

  const j = new UsageSniffer(false);
  j.push(enc('{"choices":[],"usage":{"prompt_tokens":'));
  j.push(enc('12,"completion_tokens":5}}'));
  assertEquals(j.finish(), { prompt: 12, completion: 5 });

  const s = new UsageSniffer(true);
  s.push(enc('data: {"choices":[{"delta":{"content":"a"}}]}\n\ndata: {"choices":[],"us'));
  s.push(enc('age":{"prompt_tokens":7,"completion_tokens":3}}\n\ndata: [DONE]\n\n'));
  assertEquals(s.finish(), { prompt: 7, completion: 3 });
});

Deno.test("mention / question detection", () => {
  for (const t of ["Honer, привет", "эй ИИ помоги", "ask the AI", "Спроси нейросеть", "бот, ты тут?", "Honer AI"]) assert(mentionsAi(t), t);
  for (const t of ["привет", "заботиться", "said", "главный", "ботинки", "Сегодня хорошая погода"]) assert(!mentionsAi(t), t);
  for (const t of ["Сколько стоит?", "как включить тёмную тему", "Where is it"]) assert(isQuestion(t), t);
  for (const t of ["Ок, спасибо", "Понятно.", ""]) assert(!isQuestion(t), t);
});

Deno.test("parseRange unit", () => {
  assertEquals(parseRange(undefined, 100), null);
  assertEquals(parseRange("bytes=0-9", 100), { start: 0, end: 9 });
  assertEquals(parseRange("bytes=90-", 100), { start: 90, end: 99 });
  assertEquals(parseRange("bytes=-10", 100), { start: 90, end: 99 });
  assertEquals(parseRange("bytes=50-500", 100), { start: 50, end: 99 });
  assertEquals(parseRange("bytes=100-", 100), "unsatisfiable");
  assertEquals(parseRange("bytes=5-1", 100), "unsatisfiable");
  assertEquals(parseRange("bytes=0-1,5-6", 100), null);
});

Deno.test("config defaults", () => {
  const c = loadConfig({ ADMIN_EMAILS: "A@b.c, d@E.f", GOOGLE_CLIENT_IDS: "x,y" });
  assertEquals(c.adminEmails, ["a@b.c", "d@e.f"]);
  assertEquals(c.googleClientIds, ["x", "y"]);
  assertEquals(c.presenceOfflineMs, 40_000);
  assertEquals(c.aiQuestionDelayMs, 20_000);
  assertEquals(c.deepseekBaseUrl, "https://api.deepseek.com");
  assertEquals(c.aiAllowedModels, ["deepseek-flash", "deepseek-chat", "deepseek-reasoner"]);
  assertEquals(c.maxUploadBytes, 100 * 1024 * 1024);
});
