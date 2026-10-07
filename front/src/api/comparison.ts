import type { ComparisonSeries, Point } from "./types";

const COMPARISON_LABELS = {
  DAY: "日环比（1 天前）",
  WEEK: "周环比（7 天前）",
  MONTH: "月环比（30 天前）",
};

/** 提示框里的短前缀：图例名已被统计项占据，这里只留基准名。 */
const TOOLTIP_LABELS = {
  DAY: "日环比",
  WEEK: "周环比",
  MONTH: "月环比",
};

/** 四条线要在同一张图里互相区分，用高对比、常见色相的纯色。 */
const COMPARISON_COLORS = {
  DAY: "#C65D00",
  WEEK: "#16a34a",
  MONTH: "#9333ea",
};

/** 当前 HITS 用黑色，其他统计项保留蓝色；环比色不随统计项改变。 */
const CURRENT_COLOR = "#2563eb";

/** 按桶序号叠图；保留历史桶时间不变，提示标题统一使用当前横轴时间。 */
export function comparisonChartSeries(
  stat: string,
  points: Point[],
  comparisons: ComparisonSeries[],
  bucketSeconds: number
) {
  const current = {
    name: comparisons.length ? `${stat}（当前）` : stat,
    points,
    color: stat === "HITS" ? "#000000" : CURRENT_COLOR,
    lineType: "solid" as const,
    // 有环比才加前缀，单线提示的数据行只有数值。
    tooltipLabel: comparisons.length ? "当前" : undefined,
  };
  return [current, ...comparisons.map((comparison) => {
    const historical: Point[] = comparison.points.map((point) => ({
      ...point,
      bucketEnd: point.bucketEnd ?? point.bucketStart + bucketSeconds * 1000,
      quality: point.quality ?? (point.value === null ? "NO_DATA" : point.value === 0 ? "ZERO" : "OK"),
    }));
    return {
      // 图例名尽量短：stat 可能很长，名字过长会把图例挤成分页。
      name: `${stat} · ${COMPARISON_LABELS[comparison.kind]}`,
      points: historical,
      color: COMPARISON_COLORS[comparison.kind],
      lineType: "solid" as const,
      tooltipLabel: TOOLTIP_LABELS[comparison.kind],
    };
  })];
}
