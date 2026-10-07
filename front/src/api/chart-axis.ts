import { initialTimeSelection, quickTimeBounds, stepForward } from "./time-range";

/** Axis-only timestamps for an empty response. Never creates observations or values. */
export function emptyChartAxis(range: string, now: number): number[] {
  const selection = initialTimeSelection(range, now);
  const fixed = /^(HOUR|WEEK|MONTH):/.test(selection.range);
  const bounds = fixed
    ? { from: selection.start, to: stepForward(selection.start, selection.step, 1) }
    : quickTimeBounds(selection.range, now);
  const step = selection.bucketSeconds * 1000;
  const from = step >= 86_400_000
    ? new Date(new Date(bounds.from).setHours(0, 0, 0, 0)).getTime()
    : Math.floor(bounds.from / step) * step;
  const count = Math.max(1, Math.ceil((bounds.to - from) / step));
  return Array.from({ length: count }, (_, index) => from + index * step);
}
