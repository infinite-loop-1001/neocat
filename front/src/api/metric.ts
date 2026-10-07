import { api } from "./client";
import type { Point } from "./types";
import { normalizeFilters, type MetricFilters } from "./metric-filters";
import type { MetricMockScenario } from "./metric-scenario";
export * from "./metric-filters";

/** Shared contract with MetricCountController and the frontend mock. */
export interface MetricInfo { name: string }
export interface MetricLabel { key: string; values: string[] }
export interface MetricCount { metric: string; bucketSeconds: number; points: Point[] }

export function loadMetricCatalog(service: string, range: string): Promise<MetricInfo[]> {
  return api("/reports/metric/metrics", { query: { service, range } });
}

export function loadMetricLabels(service: string, metric: string, range: string): Promise<MetricLabel[]> {
  return api("/reports/metric/labels", { query: { service, metric, range } });
}

export function loadMetricCount(service: string, metric: string, range: string, filters: MetricFilters = {}, mockMetric?: MetricMockScenario): Promise<MetricCount> {
  const normalized = normalizeFilters(filters);
  return api("/reports/metric/count", { mockMetric, query: {
    service, metric, range,
    filters: Object.keys(normalized).length ? JSON.stringify(normalized) : undefined,
  } });
}
