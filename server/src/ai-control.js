// Глобальное включение ИИ и расписание (таблица server_settings, ключ "ai").
// enabled — главный выключатель. Если он включён и расписание непустое, ИИ работает только внутри его окон.
// Окно: { days: [1..7] (1 = пн … 7 = вс), from: "HH:MM", to: "HH:MM" } в часовом поясе timezone;
// to < from — окно через полночь (день — день начала); from == to — весь день.

const DEFAULT = { enabled: true, schedule: [], timezone: 'Europe/Moscow' };
const WEEKDAYS = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };

const minutes = (hhmm) => {
  const [h, m] = String(hhmm).split(':').map(Number);
  return h * 60 + m;
};

/** День недели (1..7) и минуты от полуночи в часовом поясе tz. */
export function localClock(date, timezone) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(date);
  const get = (type) => parts.find((p) => p.type === type)?.value;
  return { day: WEEKDAYS[get('weekday')] || 1, minute: Number(get('hour')) * 60 + Number(get('minute')) };
}

/** Попадает ли момент в одно из окон расписания. Чистая функция (для тестов). */
export function inSchedule(schedule, date, timezone) {
  if (!schedule?.length) return true;
  const { day, minute } = localClock(date, timezone);
  const prevDay = day === 1 ? 7 : day - 1;
  return schedule.some((w) => {
    const from = minutes(w.from);
    const to = minutes(w.to);
    const days = w.days?.length ? w.days : [1, 2, 3, 4, 5, 6, 7];
    if (from === to) return days.includes(day);
    if (from < to) return days.includes(day) && minute >= from && minute < to;
    // Через полночь: вечер дня начала или утро следующего дня.
    return (days.includes(day) && minute >= from) || (days.includes(prevDay) && minute < to);
  });
}

export const isValidTimezone = (tz) => {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz });
    return true;
  } catch {
    return false;
  }
};

export function createAiControl({ db, config, logger }) {
  let state = { ...DEFAULT, timezone: config.aiScheduleTimezone || DEFAULT.timezone };

  async function load() {
    const row = await db.one(`SELECT value FROM server_settings WHERE key = 'ai'`);
    if (!row) return;
    try {
      const v = JSON.parse(row.value);
      state = {
        enabled: v.enabled !== false,
        schedule: Array.isArray(v.schedule) ? v.schedule : [],
        timezone: isValidTimezone(v.timezone) ? v.timezone : state.timezone,
      };
    } catch (err) {
      logger?.warn({ err: { message: err.message } }, 'bad ai settings, using defaults');
    }
  }

  async function update(patch) {
    const next = {
      enabled: patch.enabled ?? state.enabled,
      schedule: patch.schedule ?? state.schedule,
      timezone: patch.timezone ?? state.timezone,
    };
    await db.query(
      `INSERT INTO server_settings (key, value, updated_at) VALUES ('ai', $1, $2)
       ON CONFLICT (key) DO UPDATE SET value = $1, updated_at = $2`,
      [JSON.stringify(next), new Date()]);
    state = next;
    return view();
  }

  /** ИИ сейчас разрешён администратором (без учёта наличия ключа DeepSeek). */
  function isEnabled(now = new Date()) {
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
