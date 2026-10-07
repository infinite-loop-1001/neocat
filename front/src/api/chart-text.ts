/** ECharts HTML tooltips must not interpret user-defined metric/label text as markup. */
export function escapeChartText(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[char]!));
}
