/** Label conditions: OR within a key, AND across keys. Shared by URL state and mock aggregation. */
export type MetricFilters = Record<string, string[]>;

export function normalizeFilters(filters: MetricFilters): MetricFilters {
  return Object.fromEntries(Object.keys(filters).sort().flatMap((key) => {
    const values = [...new Set(filters[key])].filter((value) => typeof value === "string").sort();
    return values.length ? [[key, values]] : [];
  }));
}

export function decodeFilters(raw: unknown): MetricFilters {
  if (typeof raw !== "string") return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    const entries = Object.entries(parsed).filter(([, values]) =>
      Array.isArray(values) && values.every((value) => typeof value === "string")
    );
    return normalizeFilters(Object.fromEntries(entries));
  } catch { return {}; }
}

export function matchesFilters(labels: Record<string, string>, filters: MetricFilters): boolean {
  return Object.entries(normalizeFilters(filters)).every(([key, values]) => Object.hasOwn(labels, key) && values.includes(labels[key]));
}

export function filterCaption(filters: MetricFilters): string {
  const entries = Object.entries(normalizeFilters(filters));
  return entries.length ? entries.map(([key, values]) => `${key}=${values.join(" / ")}`).join(" · ") : "总量";
}
