/**
 * 缺口语义（技术方案 03-api-contract.md §4.10、PRD 00 §6）。
 *
 * 后端以 `value: null` 表示缺口，**绝不写 0**。
 * 前端渲染必须据此判断，否则会把「采集断流」画成「服务正常」。
 */

export type Quality =
  | "OK"
  | "ZERO"
  | "NO_DATA"
  | "DROPPED"
  | "MERGED_OTHER"
  | "PARTIAL"
  | "REALTIME";

export interface Point {
  bucketStart: number;
  bucketEnd: number;
  /** null 表示缺口或无值；绝不表示 0。 */
  value: number | null;
  quality: Quality;
  coveredSeconds?: number;
  realtime?: boolean;
  partial?: boolean;
}

export interface Series {
  service: string;
  kind: string;
  type?: string;
  name?: string;
  stat: string;
  bucketSeconds: number;
  points: Point[];
  gaps?: { bucketStart: number; reason: string }[];
  mom?: ComparisonSeries | null;
}

/** 大盘卡片图表读取字段；isUndefined 是除零点数组，不是布尔值。 */
export interface CardSeries {
  points: Point[];
  isUndefined: { bucketStart: number; reason: "DIVIDE_BY_ZERO" }[];
}

export type MomKind = "DAY" | "WEEK" | "MONTH";

/** 环比接口只保证时间和值，可能不包含当前序列的质量字段。 */
export interface ComparisonSeries {
  kind: MomKind;
  points: (Pick<Point, "bucketStart" | "value"> & Partial<Omit<Point, "bucketStart" | "value">>)[];
}

/** 缺口状态集合：图表应断开而非画 0。 */
const GAP_QUALITIES: Quality[] = ["NO_DATA", "DROPPED", "MERGED_OTHER"];

export function isGap(point: Point): boolean {
  return point.value === null || GAP_QUALITIES.includes(point.quality);
}

/** 确认无调用（可显示 0）。 */
export function isZero(point: Point): boolean {
  return point.quality === "ZERO";
}

/** 部分覆盖桶。 */
export function isPartial(point: Point): boolean {
  return point.quality === "PARTIAL" || point.partial === true;
}

/** 实时（未完成）桶。 */
export function isRealtime(point: Point): boolean {
  return point.quality === "REALTIME" || point.realtime === true;
}

/** 质量标记的中文说明，用于 Tooltip。 */
export function qualityLabel(point: Point): string {
  switch (point.quality) {
    case "OK":
      return "有数据";
    case "ZERO":
      return "确认无调用";
    case "NO_DATA":
      return "缺数据";
    case "DROPPED":
      return "队列满丢弃";
    case "MERGED_OTHER":
      return "该小时已合入 other";
    case "PARTIAL":
      return "部分覆盖";
    case "REALTIME":
      return "实时（未完成）";
    default:
      return "";
  }
}

/** 格式化为图表数值：缺口保持 null，使折线断开。 */
export function toChartValue(point: Point): number | null {
  return isGap(point) ? null : point.value;
}
