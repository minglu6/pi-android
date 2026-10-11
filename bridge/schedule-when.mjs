/**
 * Schedule time parsing — port of Pi Pocket `when.ts` (subset used by /schedule).
 * Supports: in 2h, tomorrow 9am, every weekday 8:00, every 2h, fri 17:30, 7pm, …
 */

const MIN_INTERVAL_MINUTES = 10;
const MAX_AHEAD_MS = 366 * 24 * 60 * 60_000;
const DAY_NAMES = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];
const WEEKDAYS = [1, 2, 3, 4, 5];
const FULL_DAY_NAMES = [
  "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
];
const UNITS = {
  m: 1, min: 1, mins: 1, minute: 1, minutes: 1,
  h: 60, hr: 60, hrs: 60, hour: 60, hours: 60,
  d: 1440, day: 1440, days: 1440,
};

export const WHEN_HELP =
  "请写时间：in 30m、7:00、tomorrow 9:00、fri 17:30，或 every 2h、every day 8:00、every weekday 8:00、every mon,thu 9:00。";

export function knownZone(zone) {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

function wallClock(at, zone) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: zone,
    hourCycle: "h23",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    second: "numeric",
    weekday: "short",
  }).formatToParts(new Date(at));
  const part = (type) => parts.find((each) => each.type === type)?.value ?? "";
  return {
    year: Number(part("year")),
    month: Number(part("month")),
    day: Number(part("day")),
    hour: Number(part("hour")),
    minute: Number(part("minute")),
    second: Number(part("second")),
    weekday: DAY_NAMES.indexOf(part("weekday").toLowerCase().slice(0, 3)),
  };
}

function offsetAt(moment, zone) {
  const clock = wallClock(moment, zone);
  return (
    Date.UTC(clock.year, clock.month - 1, clock.day, clock.hour, clock.minute, clock.second)
    - Math.floor(moment / 1000) * 1000
  );
}

const HALF_DAY_MS = 12 * 60 * 60_000;

export function zonedMoment(year, month, day, hour, minute, zone) {
  const wanted = Date.UTC(year, month - 1, day, hour, minute);
  const candidates = [
    wanted - offsetAt(wanted - HALF_DAY_MS, zone),
    wanted - offsetAt(wanted + HALF_DAY_MS, zone),
  ].sort((a, b) => a - b);
  const shows = (moment) => {
    const clock = wallClock(moment, zone);
    return Date.UTC(clock.year, clock.month - 1, clock.day, clock.hour, clock.minute) === wanted;
  };
  return candidates.find(shows) ?? candidates[1];
}

export function nextClockTime(after, at, days, zone) {
  const [hour = 0, minute = 0] = at.split(":").map(Number);
  const today = wallClock(after, zone);
  for (let ahead = 0; ahead <= 8; ahead++) {
    const moment = zonedMoment(today.year, today.month, today.day + ahead, hour, minute, zone);
    if (moment > after && (days === undefined || days.includes(wallClock(moment, zone).weekday))) {
      return moment;
    }
  }
  throw new Error(`No time matches ${at}`);
}

export function nextRepeat(repeat, after, zone) {
  return "minutes" in repeat
    ? after + repeat.minutes * 60_000
    : nextClockTime(after, repeat.at, repeat.days, zone);
}

function clockTime(word) {
  const match = /^(\d{1,2})(?::(\d{2}))?(am|pm)?$/.exec(word);
  if (match === null || (match[2] === undefined && match[3] === undefined)) return undefined;
  let hour = Number(match[1]);
  const minute = Number(match[2] ?? 0);
  if (match[3] !== undefined) {
    if (hour < 1 || hour > 12) return undefined;
    hour = (hour % 12) + (match[3] === "pm" ? 12 : 0);
  }
  if (hour > 23 || minute > 59) return undefined;
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function dayOfWeek(name) {
  return name.length < 3 ? -1 : FULL_DAY_NAMES.findIndex((day) => day.startsWith(name));
}

function dayList(word) {
  if (word === "day" || word === "daily") return "every";
  if (word === "weekday" || word === "weekdays") return WEEKDAYS;
  if (word === "weekend" || word === "weekends") return [0, 6];
  const days = word.split(",").map(dayOfWeek);
  return days.every((day) => day !== -1) ? [...new Set(days)].sort((a, b) => a - b) : undefined;
}

function duration(words) {
  const joined = /^(\d+(?:\.\d+)?)([a-z]+)$/.exec(words[0] ?? "");
  const [amount, unit, used] =
    joined !== null ? [joined[1], joined[2], 1] : [words[0], words[1], 2];
  const minutes = Math.round(Number(amount) * (UNITS[unit ?? ""] ?? Number.NaN));
  return Number.isFinite(minutes) && minutes >= 1 ? { minutes, used } : undefined;
}

/** @returns {{ next: number, every?: object, rest: string }} */
export function parseWhen(text, now, zone) {
  const words = text.trim().split(/\s+/);
  const lower = words.map((word) => word.toLowerCase());
  const rest = (used) => words.slice(used).join(" ");

  if (lower[0] === "every") {
    const interval = duration(lower.slice(1));
    if (interval !== undefined) {
      if (interval.minutes < MIN_INTERVAL_MINUTES) {
        throw new Error(`重复间隔至少 ${MIN_INTERVAL_MINUTES} 分钟`);
      }
      const next = lower[1 + interval.used] ?? "";
      if (next === "at" || clockTime(next) !== undefined) {
        throw new Error("请二选一：every 2h，或 every day 8:00，不能混用");
      }
      const every = { minutes: interval.minutes };
      return { next: nextRepeat(every, now, zone), every, rest: rest(1 + interval.used) };
    }
    const days = dayList(lower[1] ?? "");
    const at = clockTime(lower[days === undefined ? 1 : 2] ?? "");
    if (at === undefined) throw new Error(WHEN_HELP);
    const every = days === undefined || days === "every" ? { at } : { at, days };
    return {
      next: nextRepeat(every, now, zone),
      every,
      rest: rest(days === undefined ? 2 : 3),
    };
  }

  if (lower[0] === "in") {
    const delay = duration(lower.slice(1));
    if (delay === undefined) throw new Error(WHEN_HELP);
    const next = now + delay.minutes * 60_000;
    if (next - now > MAX_AHEAD_MS) throw new Error("最多提前一年");
    return { next, rest: rest(1 + delay.used) };
  }

  if (lower[0] === "today" || lower[0] === "tomorrow") {
    const at = clockTime(lower[1] ?? "");
    if (at === undefined) throw new Error(WHEN_HELP);
    const clock = wallClock(now, zone);
    const [hour = 0, minute = 0] = at.split(":").map(Number);
    const next = zonedMoment(
      clock.year, clock.month,
      clock.day + (lower[0] === "tomorrow" ? 1 : 0),
      hour, minute, zone,
    );
    if (next <= now) throw new Error(`今天的 ${at} 已过`);
    return { next, rest: rest(2) };
  }

  const day = dayList(lower[0] ?? "");
  if (Array.isArray(day) && day.length === 1) {
    const at = clockTime(lower[1] ?? "");
    if (at === undefined) throw new Error(WHEN_HELP);
    return { next: nextClockTime(now, at, day, zone), rest: rest(2) };
  }

  const at = clockTime(lower[0] ?? "");
  if (at === undefined) throw new Error(WHEN_HELP);
  return { next: nextClockTime(now, at, undefined, zone), rest: rest(1) };
}

export function describeRepeat(repeat) {
  if ("minutes" in repeat) {
    const { minutes } = repeat;
    if (minutes % 1440 === 0) {
      return minutes === 1440 ? "每天" : `每 ${minutes / 1440} 天`;
    }
    if (minutes % 60 === 0) {
      return minutes === 60 ? "每小时" : `每 ${minutes / 60} 小时`;
    }
    return `每 ${minutes} 分钟`;
  }
  const days = repeat.days;
  if (days === undefined) return `每天 ${repeat.at}`;
  if (days.join() === WEEKDAYS.join()) return `工作日 ${repeat.at}`;
  if (days.join() === "0,6") return `周末 ${repeat.at}`;
  return `${days.map((d) => DAY_NAMES[d]).join(",")} ${repeat.at}`;
}

export function describeMoment(at, zone, now = Date.now()) {
  const soon = at - now < 6 * 24 * 60 * 60_000;
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: zone,
    hourCycle: "h23",
    hour: "2-digit",
    minute: "2-digit",
    ...(soon ? { weekday: "short" } : { month: "short", day: "numeric" }),
  }).format(new Date(at)).replace(",", "");
}
