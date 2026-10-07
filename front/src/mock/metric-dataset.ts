import type { MetricFilters, MetricInfo, MetricLabel, MetricCount } from "../api/metric";
import { matchesFilters } from "../api/metric-filters";
import { alignHour } from "../api/time-range";
import { seriesWindow } from "./dataset";
import type { Point } from "../api/types";
import { normalizeMetricMockScenario } from "../api/metric-scenario";

interface RawCombination {
  labels: Record<string, string>;
  perMinute: number;
  /** Mock an original combination whose identity is lost in other in some hours. */
  merged?: boolean;
}

const rawMetrics: Record<string, RawCombination[]> = {
  "order.amount": [
    { labels: { channel: "app", city: "上海" }, perMinute: 43 },
    { labels: { channel: "app", city: "北京" }, perMinute: 27 },
    { labels: { channel: "web", city: "上海" }, perMinute: 19 },
    { labels: { channel: "partner", city: "上海" }, perMinute: 11, merged: true },
  ],
  "payment.latency": [
    { labels: { provider: "alipay", result: "success" }, perMinute: 35 },
    { labels: { provider: "wechat", result: "success" }, perMinute: 24 },
    { labels: { provider: "wechat", result: "failed" }, perMinute: 3, merged: true },
  ],
  "queue.depth": [
    { labels: { queue: "orders", region: "east" }, perMinute: 18 },
    { labels: { queue: "notifications", region: "east" }, perMinute: 9 },
    { labels: { queue: "orders", region: "west" }, perMinute: 12, merged: true },
  ],
};

export const metricCatalog: MetricInfo[] = Object.keys(rawMetrics).map((name) => ({ name }));

export function metricsForService(service: string): MetricInfo[] {
  if (service === "order") return metricCatalog;
  if (service === "pay") return metricCatalog.filter((metric) => metric.name !== "order.amount");
  return [];
}

export function metricLabels(metric: string): MetricLabel[] {
  const values = new Map<string, Set<string>>();
  for (const combination of Object.hasOwn(rawMetrics, metric) ? rawMetrics[metric] : []) {
    for (const [key, value] of Object.entries(combination.labels)) {
      if (!values.has(key)) values.set(key, new Set());
      values.get(key)!.add(value);
    }
  }
  return [...values].sort(([a], [b]) => a.localeCompare(b)).map(([key, values]) => ({ key, values: [...values].sort() }));
}

/**
 * One underlying raw minute-count source for both total and filtered counts.
 * Total includes other once. Filtering cannot reconstruct identities from other;
 * if any matching combination is merged, do not return a misleading partial sum.
 */
export function metricCount(metric: string, range: string, filters: MetricFilters = {}, clock = Date.now(), scenario?: string): MetricCount {
  const window = seriesWindow(range, 0, clock);
  const all = Object.hasOwn(rawMetrics, metric) ? rawMetrics[metric] : undefined;
  if (!all) return { metric, bucketSeconds: window.bucketSeconds, points: [] };
  const filtered = Object.values(filters).some((values) => values.length);
  const matching = all.filter((combination) => matchesFilters(combination.labels, filters));
  const step = window.bucketSeconds * 1000;
  const points: Point[] = [];
  const aligned = window.bucketSeconds >= 86_400
    ? new Date(new Date(window.from).setHours(0, 0, 0, 0)).getTime()
    : Math.floor(window.from / step) * step;
  for (let start = aligned; start < window.to; start += step) {
    const end = start + step;
    const coveredFrom = Math.max(start, window.from);
    const coveredEnd = Math.min(end, window.to, clock);
    let value = 0;
    let quality: Point["quality"] = "OK";
    if (start > clock) {
      points.push({ bucketStart: start, bucketEnd: end, value: null, quality: "NO_DATA", coveredSeconds: 0 });
      continue;
    }
    for (let minute = Math.floor(coveredFrom / 60_000) * 60_000; minute < coveredEnd; minute += 60_000) {
      const hour = new Date(alignHour(minute)).getHours();
      const merged = filtered && matching.some((combination) => combination.merged && hour % 2 === 0);
      // A known dropped minute makes the entire coarser bucket uncertain.
      const dropped = alignHour(minute) === alignHour(clock) - 2 * 3_600_000 && new Date(minute).getMinutes() === 7;
      if (dropped) quality = "DROPPED";
      else if (merged && quality !== "DROPPED") quality = "MERGED_OTHER";
      const overlap = Math.max(0, Math.min(minute + 60_000, coveredEnd) - Math.max(coveredFrom, minute)) / 60_000;
      value += matching.reduce((sum, combination) => sum +
        Math.round((combination.perMinute + ((Math.floor(minute / 60_000) + all.indexOf(combination) * 3) % 7)) * overlap), 0);
    }
    const realtime = start <= clock && clock < end;
    const gap = quality === "DROPPED" || quality === "MERGED_OTHER";
    const partial = coveredFrom > start || coveredEnd < end;
    points.push({ bucketStart: start, bucketEnd: end, value: gap ? null : value,
      quality: gap ? quality : realtime ? "REALTIME" : partial ? "PARTIAL" : value === 0 ? "ZERO" : "OK",
      coveredSeconds: Math.max(0, coveredEnd - coveredFrom) / 1000, realtime, partial });
  }
  const mode = normalizeMetricMockScenario(scenario);
  if (mode === "empty-points") return { metric, bucketSeconds: window.bucketSeconds, points: [] };
  if (mode === "all-null" || (mode === "partial" && metric !== "queue.depth")) {
    return { metric, bucketSeconds: window.bucketSeconds,
      points: points.map((point) => ({ ...point, value: null, quality: "NO_DATA" })) };
  }
  return { metric, bucketSeconds: window.bucketSeconds, points };
}
