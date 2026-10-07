import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { comparisonChartSeries } from "../src/api/comparison.ts";
import { toChartValue } from "../src/api/types.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";

const dependencies = { "../api/types": { toChartValue } };
/** 用本地时间构造桶起点，断言不受运行机器时区影响。 */
const at = (hour, minute) => new Date(2026, 9, 3, hour, minute).getTime();
const point = (value, quality = "OK") => ({ bucketStart: at(1, 4), bucketEnd: at(1, 5), value, quality });

function option(series, extra = {}) {
  return setupComponent("../src/components/ReportChart.vue", { series, ...extra }, dependencies).option.value;
}

test("单曲线提示顶部一次显示完整时间，下一行只显示数值", () => {
  const chart = option([{ name: "总量", points: [point(59, "REALTIME"), point(12)] }]);
  const tooltip = (index) => chart.tooltip.formatter([{ seriesName: "总量", dataIndex: index }]);
  assert.equal(tooltip(0), "2026-10-03 01:04:00<br/>59");
  assert.equal(tooltip(1), "2026-10-03 01:04:00<br/>12");
  for (const text of [tooltip(0), tooltip(1)]) {
    assert.doesNotMatch(text, /HITS|总量|实时|未完成|有数据|\(/);
  }
});

test("缺口仍提示缺口，但不写质量原因", () => {
  for (const quality of ["NO_DATA", "DROPPED", "MERGED_OTHER"]) {
    const chart = option([{ name: "总量", points: [point(null, quality)] }]);
    assert.equal(chart.tooltip.formatter([{ seriesName: "总量", dataIndex: 0 }]), "2026-10-03 01:04:00<br/>缺口");
  }
});

test("环比多线逐行标基准且不重复统计项名；无环比不加前缀", () => {
  const series = comparisonChartSeries("HITS", [point(59)], [
    { kind: "DAY", points: [{ ...point(52), bucketStart: at(1, 4) - 86400000 }] },
    { kind: "WEEK", points: [{ ...point(48), bucketStart: at(1, 4) - 7 * 86400000 }] },
    { kind: "MONTH", points: [{ ...point(71.61), bucketStart: at(1, 4) - 30 * 86400000 }] },
  ], 60);
  assert.deepEqual(series.map((line) => line.tooltipLabel), ["当前", "日环比", "周环比", "月环比"]);
  const chart = option(series);
  const tooltip = chart.tooltip.formatter(series.map((line) => ({ seriesName: line.name, dataIndex: 0 })));
  assert.equal(tooltip, "2026-10-03 01:04:00<br/>当前：59<br/>日环比：52<br/>周环比：48<br/>月环比：71.61");
  assert.doesNotMatch(tooltip, /HITS|\(/);
  // 即使当前线隐藏、历史线在 params 中排第一，标题也必须取当前横轴。
  assert.equal(chart.tooltip.formatter([{ seriesName: series[2].name, dataIndex: 0 }]),
    "2026-10-03 01:04:00<br/>周环比：48");
  const single = comparisonChartSeries("HITS", [point(59)], [], 60);
  assert.deepEqual(single.map((line) => line.tooltipLabel), [undefined]);
  assert.equal(option(single).tooltip.formatter([{ seriesName: single[0].name, dataIndex: 0 }]), "2026-10-03 01:04:00<br/>59");
});

test("提示只输出时间与数值，用户自定义名称不再进入 HTML", () => {
  const name = '<img onerror="alert(1)">';
  const chart = option([{ name, points: [point(12)] }]);
  const tooltip = chart.tooltip.formatter([{ seriesName: name, dataIndex: 0 }]);
  assert.equal(tooltip, "2026-10-03 01:04:00<br/>12");
  assert.doesNotMatch(tooltip, /img/);
});

test("时间固定补齐年月日时分秒，轴刻度兜底不使用历史日期，空参数不造提示", () => {
  const start = new Date(2026, 0, 2, 3, 4, 5).getTime();
  const chart = option([{ name: "总量", points: [{ ...point(0, "ZERO"), bucketStart: start }] }]);
  assert.equal(chart.tooltip.formatter([{ seriesName: "总量", dataIndex: 0 }]),
    "2026-01-02 03:04:05<br/>0");
  assert.equal(chart.tooltip.formatter([]), "");
  const emptyCurrent = option([
    { name: "当前", points: [] },
    { name: "日环比", tooltipLabel: "日环比", points: [point(52)] },
  ], { axisTimes: [start] });
  assert.equal(emptyCurrent.tooltip.formatter([{ seriesName: "日环比", dataIndex: 0 }]),
    "2026-01-02 03:04:05<br/>日环比：52");
});

test("图下说明不再声称悬停可看质量标记，大图不再引入质量文案", () => {
  const { source, compiled } = compileComponentTemplate("../src/components/ReportChart.vue");
  assert.equal(compiled.errors.length, 0);
  assert.match(source, /缺口以断线表示。/);
  assert.doesNotMatch(source, /质量标记/);
  const script = readFileSync(new URL("../src/components/ReportChart.vue", import.meta.url), "utf8");
  assert.doesNotMatch(script, /qualityLabel/);
  assert.match(script, /tooltipLabel/);
});
