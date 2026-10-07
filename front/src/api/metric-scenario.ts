/** Frontend acceptance scenarios; only the mock transport consumes these. */
export type MetricMockScenario = "all-null" | "empty-points" | "partial";

export function normalizeMetricMockScenario(raw: unknown): MetricMockScenario | undefined {
  return raw === "all-null" || raw === "empty-points" || raw === "partial" ? raw : undefined;
}
