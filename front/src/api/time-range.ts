/**
 * 时间窗口的自然边界对齐与默认范围（PRD 03 §2）。
 *
 * 抽出来的原因：`TimeScope` 与各个报表页都要知道「进页面时默认用哪个窗口」。
 * 两边各算一次的话，控件显示 19:00–20:00、页面却按 `RECENT_1H` 取数的偏差
 * 不会报错，只会让人看到对不上的数字。默认范围因此只有一个定义。
 *
 * 对齐规则与后端 `DefaultTimeBucketResolver` 保持一致，用平台时区：
 * 整点 / 周一 00:00 / 月初 00:00。
 */

export type TimeStep = "h" | "w" | "m";

const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

export function alignHour(ms: number): number {
  const d = new Date(ms);
  d.setMinutes(0, 0, 0);
  return d.getTime();
}

export function alignDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** 自然周起点：周一 00:00。 */
export function alignWeek(ms: number): number {
  const d = new Date(alignDay(ms));
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d.getTime();
}

export function alignMonth(ms: number): number {
  const d = new Date(ms);
  d.setDate(1);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

export function alignTo(ms: number, step: TimeStep): number {
  if (step === "h") return alignHour(ms);
  if (step === "w") return alignWeek(ms);
  return alignMonth(ms);
}

/** `range` 的起点偏移：小时 / 周 / 月各差一个自然周期。 */
export function stepForward(start: number, step: TimeStep, direction: number): number {
  if (step === "h") return start + direction * HOUR_MS;
  if (step === "w") return start + direction * 7 * DAY_MS;
  const d = new Date(start);
  d.setMonth(d.getMonth() + direction);
  return d.getTime();
}

export function isoDate(ms: number): string {
  const d = new Date(ms);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function isoMonth(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

/** 某个窗口起点对应的 `range` 写法与粒度。 */
export function rangeAt(start: number, step: TimeStep): { range: string; bucketSeconds: number } {
  if (step === "h") return { range: `HOUR:${start}`, bucketSeconds: 60 };
  if (step === "w") return { range: `WEEK:${isoDate(start)}`, bucketSeconds: 3600 };
  return { range: `MONTH:${isoMonth(start)}`, bucketSeconds: DAY_MS / 1000 };
}

/**
 * 进入报表页的默认窗口：**当前自然小时**，按整点对齐。
 *
 * 不用「最近 1 小时」那种从当前时刻往回滚的窗口：它的区间带零头
 * （18:59–19:59），既不好读，也没法和整点的历史窗口直接对比。
 * 当前小时是左闭右开 `[整点, 下一整点)`，最后一个桶是实时桶。
 */
export function currentHourScope(now: number = Date.now()): {
  start: number;
  range: string;
  bucketSeconds: number;
} {
  const start = alignHour(now);
  return { start, ...rangeAt(start, "h") };
}

const QUICK_HOURS: Record<string, [number, number]> = {
  RECENT_1H: [1, 60], RECENT_3H: [3, 300], RECENT_6H: [6, 600],
  RECENT_12H: [12, 1200], RECENT_24H: [24, 3600],
};

/** Restore only supported, non-future windows from a report URL. */
export function initialTimeSelection(raw: unknown, now = Date.now()): {
  range: string; bucketSeconds: number; start: number; step: TimeStep; quick: string; mode: "quick" | "window";
} {
  const fallback = { ...currentHourScope(now), step: "h" as TimeStep, quick: "CURRENT_HOUR", mode: "quick" as const };
  if (typeof raw !== "string") return fallback;
  if (Object.hasOwn(QUICK_HOURS, raw) || raw === "TODAY" || raw === "THIS_WEEK") {
    return { ...fallback, range: raw, quick: raw,
      bucketSeconds: QUICK_HOURS[raw]?.[1] ?? (raw === "TODAY" ? 600 : 3600) };
  }
  let start = NaN;
  let step: TimeStep = "h";
  if (/^HOUR:\d+$/.test(raw)) start = alignHour(Number(raw.slice(5)));
  if (/^WEEK:\d{4}-\d{2}-\d{2}$/.test(raw)) {
    step = "w";
    const date = new Date(`${raw.slice(5)}T00:00:00`);
    if (!Number.isNaN(date.getTime()) && isoDate(date.getTime()) === raw.slice(5)) start = alignWeek(date.getTime());
  }
  if (/^MONTH:\d{4}-\d{2}$/.test(raw)) {
    step = "m";
    const date = new Date(`${raw.slice(6)}-01T00:00:00`);
    if (!Number.isNaN(date.getTime()) && isoMonth(date.getTime()) === raw.slice(6)) start = alignMonth(date.getTime());
  }
  if (!Number.isFinite(start) || start > alignTo(now, step)) return fallback;
  if (step === "h" && start === fallback.start) return fallback;
  return { ...rangeAt(start, step), start, step, quick: "CURRENT_HOUR", mode: "window" };
}

export function quickTimeBounds(quick: string, now: number): { from: number; to: number } {
  if (quick === "CURRENT_HOUR") {
    const from = alignHour(now);
    return { from, to: from + HOUR_MS };
  }
  if (quick === "TODAY") return { from: alignDay(now), to: now };
  if (quick === "THIS_WEEK") return { from: alignWeek(now), to: now };
  return { from: now - (QUICK_HOURS[quick]?.[0] ?? 1) * HOUR_MS, to: now };
}
