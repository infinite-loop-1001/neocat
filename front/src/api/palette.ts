/**
 * 序列配色。
 *
 * 同一张图里要区分多条序列（实例、环比基准），色相必须拉开距离，
 * 且顺序固定——否则同一实例换个页面就变了颜色，用户无法凭颜色记忆。
 */
export const SERIES_COLORS = [
  "#2563eb",
  "#dc2626",
  "#16a34a",
  "#9333ea",
  "#ea580c",
  "#0891b2",
  "#ca8a04",
  "#db2777",
];

export function seriesColor(index: number): string {
  return SERIES_COLORS[index % SERIES_COLORS.length];
}
