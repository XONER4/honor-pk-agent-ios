// Минимальные ассерты (без внешних зависимостей), чтобы `deno test` не требовал сети.
export function assert(cond: unknown, msg = "assertion failed"): asserts cond {
  if (!cond) throw new Error(msg);
}

function eq(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (a === null || b === null || typeof a !== "object" || typeof b !== "object") return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((v, i) => eq(v, b[i]));
  }
  const ka = Object.keys(a as object);
  const kb = Object.keys(b as object);
  if (ka.length !== kb.length) return false;
  return ka.every((k) => eq((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]));
}

export function assertEquals(actual: unknown, expected: unknown, msg?: string): void {
  if (!eq(actual, expected)) {
    throw new Error(msg || `not equal:\n  actual:   ${JSON.stringify(actual)}\n  expected: ${JSON.stringify(expected)}`);
  }
}

export function assertNotEquals(actual: unknown, expected: unknown, msg?: string): void {
  if (eq(actual, expected)) throw new Error(msg || `expected values to differ: ${JSON.stringify(actual)}`);
}

export function assertMatch(actual: string, re: RegExp, msg?: string): void {
  if (!re.test(actual)) throw new Error(msg || `"${actual}" does not match ${re}`);
}

export async function assertRejects(fn: () => Promise<unknown>, msg?: string): Promise<void> {
  try {
    await fn();
  } catch {
    return;
  }
  throw new Error(msg || "expected function to reject");
}

export function assertOk(cond: unknown, msg?: string): asserts cond {
  assert(cond, msg);
}
