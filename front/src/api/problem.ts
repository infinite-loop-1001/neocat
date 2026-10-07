/**
 * 五类 Problem 的展示标签（PRD 03 §9）。
 *
 * 后端返回的 category 是 `EXCEPTION` / `SLOW_SQL` 这类枚举值，
 * 直接展示会带下划线与全大写；这里映射成与 URL 片段一致的短横线写法。
 */
export const PROBLEM_LABELS: Record<string, string> = {
  EXCEPTION: "exception",
  SLOW_URL: "long-url",
  SLOW_SQL: "long-sql",
  SLOW_CALL: "long-call",
  SLOW_CACHE: "long-cache",
};

/**
 * 慢类支持耗时与分位，异常不支持（PRD 03 §9）。
 *
 * 只按类别判断，不依赖接口是否恰好返回了耗时字段。
 */
export function problemSupportsDuration(category: string): boolean {
  return category.toUpperCase() !== "EXCEPTION";
}

/** 未收录的类别回落为小写原名，不隐藏未知值。 */
export function problemLabel(category: string): string {
  return PROBLEM_LABELS[category.toUpperCase()] ?? category.toLowerCase();
}
