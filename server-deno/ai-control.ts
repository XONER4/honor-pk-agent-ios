// Глобальное включение ИИ и расписание (settings ключ "ai").
import type { Config } from "./config.ts";
import { getSetting, type Kv, setSetting } from "./kv.ts";

export interface Window {
  days: number[];
  from: string;
  to: string;
}
interface AiState {
  enabled: boolean;
  schedule: Window[];
  timezone: string;
}

const DEFAULT: AiState = { enabled: true, schedule: [], timezone: "Europe/Moscow" };
const WEEKDAYS: Record<string, number> = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };

const minutes = (hhmm: string) => {
  const [h, m] = String(hhmm).split(":").map(Number);
  return h * 60 + m;
};

export function localClock(date: Date, timezone: string) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: timezone,
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const get = (type: string) => parts.find((p) => p.type === type)?.value;
  return { day: WEEKDAYS[get("weekday") || "Mon"] || 1, minute: Number(get("hour")) * 60 + Number(get("minute")) };
}

export function inSchedule(schedule: Window[] | undefined, date: Date, timezone: string): boolean {
  if (!schedule?.length) return true;
  const { day, minute } = localClock(date, timezone);
  const prevDay = day === 1 ? 7 : day - 1;
  return schedule.some((w) => {
    const from = minutes(w.from);
    const to = minutes(w.to);
    const days = w.days?.length ? w.days : [1, 2, 3, 4, 5, 6, 7];
    if (from === to) return days.includes(day);
    if (from < to) return days.includes(day) && minute >= from && minute < to;
    return (days.includes(day) && minute >= from) || (days.includes(prevDay) && minute < to);
  });
}

export const isValidTimezone = (tz: string): boolean => {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
};

export function createAiControl({ kv, config }: { kv: Kv; config: Config }) {
  let state: AiState = { ...DEFAULT, timezone: config.aiScheduleTimezone || DEFAULT.timezone };

  async function load() {
    const v = await getSetting<AiState>(kv, "ai");
    if (!v) return;
    state = {
      enabled: v.enabled !== false,
      schedule: Array.isArray(v.schedule) ? v.schedule : [],
      timezone: isValidTimezone(v.timezone) ? v.timezone : state.timezone,
    };
  }

  async function update(patch: Partial<AiState>) {
    const next: AiState = {
      enabled: patch.enabled ?? state.enabled,
      schedule: patch.schedule ?? state.schedule,
      timezone: patch.timezone ?? state.timezone,
    };
    await setSetting(kv, "ai", next);
    state = next;
    return view();
  }

  function isEnabled(now = new Date()): boolean {
    return state.enabled && inSchedule(state.schedule, now, state.timezone);
  }

  function view(now = new Date()) {
    return {
      enabled: state.enabled,
      schedule: state.schedule,
      timezone: state.timezone,
      effective: isEnabled(now),
      configured: Boolean(config.deepseekApiKey),
    };
  }

  return { load, update, isEnabled, view };
}

export type AiControl = ReturnType<typeof createAiControl>;
