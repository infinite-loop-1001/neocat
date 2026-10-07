import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { parse, compileTemplate } from "@vue/compiler-sfc";
import { nextTick } from "vue";
import { comparisonChartSeries } from "../src/api/comparison.ts";
import { problemLabel, problemSupportsDuration } from "../src/api/problem.ts";
import { toChartValue } from "../src/api/types.ts";
import { momPoints, seriesWindow, seriesInWindow } from "../src/mock/dataset.ts";
import * as timeRange from "../src/api/time-range.ts";
import { currentHourScope } from "../src/api/time-range.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";

const now = new Date(2026, 9, 2, 10, 30).getTime();
const window = seriesWindow("RECENT_1H", 60, now);
const current = seriesInWindow(now, window, 55);

// 趋势页现在也承载 Problem，两处都复用同一份 setup 依赖。
const seriesDependencies = {
  "../api/comparison": { comparisonChartSeries },
  "../api/problem": { problemLabel, problemSupportsDuration },
  "../api/time-range": timeRange,
};

function componentSetup(file, props, dependencies = {}) {
  return setupComponent(file, props, { ...seriesDependencies, ...dependencies }, "comparison-test");
}

for (const [kind, days] of [["DAY", 1], ["WEEK", 7], ["MONTH", 30]]) {
  test(`${kind}: mock 按桶对齐，历史真实日期保留，null 不变为零`, () => {
    const historical = momPoints(window, kind, 55);
    assert.deepEqual(historical, momPoints(window, kind, 55));
    assert.equal(historical.length, current.length);
    historical.forEach((point, i) => {
      assert.equal(current[i].bucketStart - point.bucketStart, days * 86_400_000);
    });
    const lines = comparisonChartSeries("QPS", current, [{ kind, points: historical }], 60);
    assert.equal(lines.length, 2);
    assert.notEqual(lines[0].color, lines[1].color);
    assert.equal(lines[1].lineType, "solid");
    assert.ok(lines[1].name.includes(`${days} 天前`));
    assert.equal(lines[1].points[0].bucketStart, historical[0].bucketStart);
    assert.equal(lines[1].points[0].bucketEnd, historical[0].bucketStart + 60_000);
    const gap = lines[1].points.find((point) => point.value === null);
    assert.equal(gap.quality, "NO_DATA");
    assert.equal(toChartValue(gap), null);
    assert.ok(!Object.hasOwn(historical[0], "quality"), "转换不得修改接口原始数据");
  });
}

test("日、周、月 mock 走势可明显区分", () => {
  const values = ["DAY", "WEEK", "MONTH"].map((kind) => momPoints(window, kind, 55).map((p) => p.value));
  assert.notDeepEqual(values[0], values[1]);
  assert.notDeepEqual(values[1], values[2]);
  assert.notDeepEqual(values[0], values[2]);
});

test("没有 mom 只画当前线；确认零值及已有质量标记保持原义", () => {
  assert.equal(comparisonChartSeries("HITS", current, [], 60).length, 1);
  const lines = comparisonChartSeries("HITS", current, [{
    kind: "DAY",
    points: [
      { bucketStart: 1, value: 0 },
      { bucketStart: 2, bucketEnd: 3, value: 4, quality: "PARTIAL", coveredSeconds: 20 },
    ],
  }], 60);
  assert.equal(lines[1].points[0].quality, "ZERO");
  assert.equal(toChartValue(lines[1].points[0]), 0);
  assert.equal(lines[1].points[1].quality, "PARTIAL");
  assert.equal(lines[1].points[1].bucketEnd, 3);
  assert.equal(lines[1].points[1].coveredSeconds, 20);
});

for (const kind of ["TRANSACTION", "EVENT"]) {
  test(`${kind}: 真实趋势页读取 mom 并在“不对比”时回到单线`, async () => {
    const requests = [];
    const state = componentSetup("../src/views/SeriesView.vue", { kind }, {
      "vue-router": {
        useRoute: () => ({ params: { service: "order", type: "CACHE", name: "getUser" } }),
        useRouter: () => ({}),
      },
      "../api/comparison": { comparisonChartSeries },
      "../api": {
        api: async (path, options) => {
          if (path === "/reports/series") {
            const query = options.query;
            requests.push(query);
            return {
              points: current, bucketSeconds: 60,
              mom: query.mom ? { kind: query.mom, points: momPoints(window, query.mom, 55) } : null,
            };
          }
          return [];
        },
      },
    });
    for (const mode of ["MONTH", "DAY", "WEEK", "DAY", "MONTH", "WEEK"]) {
      requests.length = 0;
      await state.toggleMom(mode);
      const selected = [...state.selectedMom.value];
      assert.deepEqual(requests.map((request) => request.mom), selected.length ? selected : [undefined]);
      assert.ok(requests.every((request) => request.kind === kind));
      assert.equal(state.chartSeries.value.length, selected.length + 1);
      assert.equal(state.bucketSeconds.value, 60);
      if (selected.length) assert.ok(state.caption.value.includes("环比"));
    }
    assert.equal(state.selectedMom.value.length, 0);
  });
}

test("真实图表使用当前横轴叠图，提示顶部只显示一次当前横轴时间", () => {
  const series = comparisonChartSeries("QPS", current, [{ kind: "DAY", points: momPoints(window, "DAY", 55) }], 60);
  const state = componentSetup("../src/components/ReportChart.vue", { series }, {
    "../api/types": { toChartValue },
  });
  const option = state.option.value;
  assert.ok(option.legend);
  assert.equal(option.legend.type, "scroll");
  assert.equal(option.grid.top, 40);
  assert.equal(option.series.length, 2);
  assert.equal(option.series[1].lineStyle.type, "solid");
  assert.equal(option.series[1].connectNulls, false);
  assert.equal(option.xAxis.data[0], new Date(current[0].bucketStart).toLocaleString("zh-CN", { hour12: false }));
  const tooltip = option.tooltip.formatter(series.map((s) => ({ seriesName: s.name, dataIndex: 0 })));
  assert.match(tooltip.split("<br/>")[0], /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  assert.equal(tooltip.split("<br/>").slice(1).join("<br/>"),
    `当前：${current[0].value}<br/>日环比：${series[1].points[0].value}`);
  assert.doesNotMatch(tooltip, /QPS|\(|<br\/><br\/>/);
  const gapIndex = series[1].points.findIndex((point) => point.value === null);
  assert.equal(option.series[1].data[gapIndex], null);
  assert.match(option.tooltip.formatter([{ seriesName: series[1].name, dataIndex: gapIndex }]),
    /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}<br\/>日环比：缺口$/);
});

test("多选四条线使用固定独立颜色，取消一项不改变其他颜色", () => {
  const comparisons = ["DAY", "WEEK", "MONTH"].map((kind) => ({ kind, points: momPoints(window, kind, 55) }));
  const all = comparisonChartSeries("HITS", current, comparisons, 60);
  assert.equal(all.length, 4);
  assert.equal(new Set(all.map((line) => line.color)).size, 4);
  assert.deepEqual(all.map((line) => line.color), ["#000000", "#C65D00", "#16a34a", "#9333ea"]);
  assert.ok(all.every((line) => line.lineType === "solid"), "环比线不用虚线，与普通指标一致");
  const remaining = comparisonChartSeries("HITS", current, comparisons.filter((c) => c.kind !== "WEEK"), 60);
  assert.equal(remaining.length, 3);
  for (const line of remaining) assert.equal(line.color, all.find((original) => original.name === line.name).color);
  const restored = comparisonChartSeries("HITS", current, [...comparisons].reverse(), 60);
  for (const line of restored) assert.equal(line.color, all.find((original) => original.name === line.name).color);
  const chart = setupComponent("../src/components/ReportChart.vue", { series: all }, {
    "../api/types": { toChartValue },
  });
  chart.option.value.series.forEach((line, index) => {
    assert.equal(line.itemStyle.color, all[index].color);
    assert.equal(line.lineStyle.color, all[index].color);
    assert.equal(line.lineStyle.type, "solid");
  });
});

test("只有当前 HITS 使用黑色，其他统计项保持蓝色，是否环比不改变颜色", () => {
  for (const stat of ["HITS", "QPS", "AVG", "TP95", "TP99", "FAILURES", "FAILURE_RATE"]) {
    for (const comparisons of [[], [{ kind: "DAY", points: momPoints(window, "DAY", 55) }]]) {
      const series = comparisonChartSeries(stat, current, comparisons, 60);
      assert.equal(series[0].color, stat === "HITS" ? "#000000" : "#2563eb");
      assert.equal(series[0].lineType, "solid");
      assert.equal(series[0].points, current);
      if (comparisons.length) assert.equal(series[1].color, "#C65D00");
    }
  }
});

test("趋势机器列表不伪造数值，选择和清空仍按真实实例查询", async () => {
  const requests = [];
  const state = componentSetup("../src/views/SeriesView.vue", { kind: "TRANSACTION" }, {
    "vue-router": {
      useRoute: () => ({ params: { service: "order", type: "URL", name: "POST /orders" } }),
      useRouter: () => ({}),
    },
    "../api": { api: async (path, options) => {
      if (path === "/services/order/instances") return ["10.0.0.8", "10.0.0.9"];
      if (path === "/reports/series") {
        requests.push(options.query);
        return { points: current, bucketSeconds: 60, mom: null };
      }
      return [];
    } },
  });
  await state.load();
  assert.deepEqual(state.machines.value.map((row) => ({ ...row })), [
    { instance: "10.0.0.8" }, { instance: "10.0.0.9" },
  ]);
  assert.equal(requests.at(-1).instances, undefined);
  state.selectedInstances.value = ["10.0.0.9"];
  await nextTick();
  assert.equal(requests.at(-1).instances, "10.0.0.9");
  state.selectedInstances.value = [];
  await nextTick();
  assert.equal(requests.at(-1).instances, undefined);
  const picker = setupComponent("../src/components/MachinePicker.vue", {
    machines: state.machines.value, modelValue: [],
  });
  picker.q.value = "  0.0.9  ";
  assert.deepEqual(picker.filtered.value.map((row) => row.instance), ["10.0.0.9"]);
  picker.q.value = "missing";
  assert.equal(picker.filtered.value.length, 0);
});

test("机器搜索栏可收缩及换行，状态/按钮不拆字，窄屏列表不被最小列宽撑开", () => {
  const styles = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  const rule = (selector) => styles.slice(styles.indexOf(`${selector} {`)).split("}")[0];
  assert.match(rule(".machine-head"), /flex-wrap:\s*wrap/);
  assert.match(rule(".machine-head .field"), /flex:\s*1 1 180px/);
  assert.match(rule(".machine-head .field"), /min-width:\s*0/);
  assert.match(rule(".machine-head .field"), /width:\s*auto/);
  assert.match(rule(".machine-head .btn"), /white-space:\s*nowrap/);
  assert.match(rule(".machine-list"), /minmax\(min\(100%, 200px\), 1fr\)/);
  assert.match(rule(".series-main"), /grid-template-columns:\s*minmax\(0, 1fr\)/);
  const { source, compiled } = compileComponentTemplate("../src/components/MachinePicker.vue");
  assert.equal(compiled.errors.length, 0);
  assert.match(source, /aria-label="搜索实例"/);
  assert.match(source, /@change="toggle\(row.instance\)"/);
  assert.match(source, /@click="clear"/);
});

test("快速切换勾选时，较晚返回的旧请求不会恢复被取消的线", async () => {
  const pending = [];
  const state = componentSetup("../src/views/SeriesView.vue", { kind: "TRANSACTION" }, {
    "vue-router": {
      useRoute: () => ({ params: { service: "order", type: "CACHE", name: "getUser" } }),
      useRouter: () => ({}),
    },
    "../api/comparison": { comparisonChartSeries },
    "../api": { api: (path, options) => {
      if (path !== "/reports/series") return Promise.resolve([]);
      return new Promise((resolve) => pending.push({ query: options.query, resolve }));
    } },
  });
  const old = state.toggleMom("DAY");
  state.range.value = "RECENT_3H";
  const latest = state.toggleMom("DAY");
  assert.equal(pending.length, 2);
  assert.equal(pending[1].query.range, "RECENT_3H");
  assert.equal(pending[1].query.mom, undefined);
  const latestPoints = [{ ...current[0], value: 999 }];
  pending[1].resolve({ points: latestPoints, bucketSeconds: 300, mom: null });
  await latest;
  pending[0].resolve({ points: current, bucketSeconds: 60, mom: { kind: "DAY", points: momPoints(window, "DAY", 55) } });
  await old;
  assert.equal(state.points.value[0].value, 999);
  assert.equal(state.bucketSeconds.value, 300);
  assert.equal(state.comparisons.value.length, 0);
  assert.equal(state.chartSeries.value.length, 1);
});

test("多选请求并行启动且共享筛选快照，缺少某项 mom 时不生成假线", async () => {
  const pending = [];
  const state = componentSetup("../src/views/SeriesView.vue", { kind: "EVENT" }, {
    "vue-router": {
      useRoute: () => ({ params: { service: "order", type: "business", name: "created" } }),
      useRouter: () => ({}),
    },
    "../api/comparison": { comparisonChartSeries },
    "../api": { api: (path, options) => {
      if (path !== "/reports/series") return Promise.resolve([]);
      return new Promise((resolve) => pending.push({ query: options.query, resolve }));
    } },
  });
  state.selectedMom.value = ["DAY", "WEEK", "MONTH"];
  state.selectedInstances.value = ["10.0.0.8"];
  state.stat.value = "QPS";
  // 默认窗口是当前整点小时
  state.range.value = currentHourScope().range;
  // 改实例会触发页面自己的 watch(load)；先让它跑完，再显式加载被测的那一次。
  await nextTick();
  pending.length = 0;
  const loading = state.load();
  assert.equal(pending.length, 3, "必须在首个请求返回前启动全部请求");
  for (const { query } of pending) {
    assert.equal(query.instances, "10.0.0.8");
    assert.equal(query.stat, "QPS");
    assert.equal(query.range, currentHourScope().range);
    assert.equal(query.kind, "EVENT");
  }
  // 乱序返回：最新一轮仍应完整落地，缺失的 WEEK 不生成假线。
  for (const { query, resolve } of pending.toReversed()) {
    resolve({ points: current, bucketSeconds: 60, mom: query.mom === "WEEK" ? null : {
      kind: query.mom, points: momPoints(window, query.mom, 55),
    } });
  }
  await loading;
  assert.deepEqual([...state.comparisons.value].map((c) => c.kind), ["DAY", "MONTH"]);
  assert.equal(state.chartSeries.value.length, 3);
});

test("环比模板使用独立复选框，不再提供不对比按钮", () => {
  const source = readFileSync(new URL("../src/views/SeriesView.vue", import.meta.url), "utf8");
  const { descriptor } = parse(source);
  const compiled = compileTemplate({ source: descriptor.template.content, id: "comparison-test" });
  assert.equal(compiled.errors.length, 0);
  assert.match(compiled.code, /type: "checkbox"/);
  assert.match(compiled.code, /selectedMom.includes/);
  assert.match(compiled.code, /toggleMom/);
  assert.doesNotMatch(descriptor.template.content, /aria-pressed|不对比/);
});

test("切回单线时替换 ECharts 配置，避免残留上一条环比曲线", () => {
  const source = readFileSync(new URL("../src/components/ReportChart.vue", import.meta.url), "utf8");
  const { descriptor } = parse(source);
  const compiled = compileTemplate({ source: descriptor.template.content, id: "comparison-test" });
  assert.equal(compiled.errors.length, 0);
  assert.match(compiled.code, /notMerge: true/);
});
