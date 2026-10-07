import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { reactive, nextTick } from "vue";
import * as metricApi from "../src/api/metric.ts";
import * as timeRange from "../src/api/time-range.ts";
import { emptyChartAxis } from "../src/api/chart-axis.ts";
import { normalizeMetricMockScenario } from "../src/api/metric-scenario.ts";
import { toChartValue } from "../src/api/types.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";
import { escapeChartText } from "../src/api/chart-text.ts";

const range = `HOUR:${new Date(2026, 9, 1, 9).getTime()}`;
const point = (value) => ({ bucketStart: 0, bucketEnd: 60000, value, quality: "OK" });
function setup(metric = "", overrides = {}, query = { range }) {
  const route = reactive({ params: { service: "order", ...(metric ? { metric } : {}) }, path: `/svc/order/metric${metric ? `/${metric}` : ""}`, query });
  const navigations = [];
  const requests = [];
  const api = {
    ...metricApi,
    loadMetricCatalog: async () => [{ name: "order.amount" }, { name: "queue.depth" }],
    loadMetricLabels: async () => [{ key: "city", values: ["上海", "北京"] }, { key: "channel", values: ["app", "web"] }],
    loadMetricCount: async (...args) => { requests.push(args); return { points: [point(10)], bucketSeconds: 60 }; },
    ...overrides,
  };
  const state = setupComponent("../src/views/MetricView.vue", {}, {
    "vue-router": { useRoute: () => route, useRouter: () => ({ replace: async (location) => {
      navigations.push(location); route.query = location.query;
    } }) },
    "../api/metric": api, "../api/time-range": timeRange, "../api/types": { toChartValue },
    "../api/chart-axis": { emptyChartAxis }, "../api/metric-scenario": { normalizeMetricMockScenario },
  });
  return { state, route, requests, navigations };
}

test("总览每个指标一张图，默认请求全量，不预选标签；详情/返回保留时间", async () => {
  const { state, requests } = setup();
  await state.load();
  assert.deepEqual(Array.from(state.cards.value, (card) => card.name), ["order.amount", "queue.depth"]);
  assert.ok(requests.every(([, , selectedRange, filters]) => selectedRange === range && filters === undefined));
  assert.equal(state.detailTo("order.amount").query.range, range);
  assert.equal(state.detailTo("order.amount").path, "/svc/order/metric/order.amount");
  assert.equal(state.overviewTo.value.query.range, range);
});

test("详情默认单条总量；选标签才聚合单曲线；清空恢复总量", async () => {
  const { state, requests } = setup("order.amount");
  await state.load();
  assert.equal(state.selectionCaption.value, "总量");
  assert.deepEqual(requests.at(-1)[3], {});
  assert.equal(state.chartSeries.value.length, 1);
  await state.toggleLabel("channel", "app");
  await state.toggleLabel("city", "上海");
  await nextTick();
  await state.load();
  assert.deepEqual(requests.at(-1)[3], { channel: ["app"], city: ["上海"] });
  assert.equal(state.chartSeries.value.length, 1);
  assert.match(state.chartSeries.value[0].name, /channel=app/);
  await state.clearFilters();
  await nextTick();
  await state.load();
  assert.equal(state.selectionCaption.value, "总量");
  assert.deepEqual(requests.at(-1)[3], {});
});

test("URL 标签恢复与搜索；快速选择同标签多值不丢选择", async () => {
  const { state, route } = setup("order.amount", {}, { range, filters: JSON.stringify({ city: ["上海"] }) });
  await state.load();
  assert.equal(state.hasFilters.value, true);
  state.search.value = "city";
  assert.equal(state.visibleLabels.value.length, 1);
  state.search.value = "app";
  assert.equal(state.visibleLabels.value[0].key, "channel");
  await Promise.all([state.toggleLabel("channel", "app"), state.toggleLabel("channel", "web")]);
  assert.deepEqual(JSON.parse(route.query.filters).channel, ["app", "web"]);
});

test("旧请求不能覆盖新筛选结果或候选标签", async () => {
  const pending = [];
  const { state, route } = setup("order.amount", {
    loadMetricCount: (...args) => new Promise((resolve) => pending.push({ args, resolve })),
  });
  const first = state.load();
  route.params.metric = "queue.depth";
  await nextTick();
  const latest = state.load();
  pending.at(-1).resolve({ points: [point(99)] });
  await latest;
  pending[0].resolve({ points: [point(1)] });
  await first;
  assert.equal(state.points.value[0].value, 99);
  // Resolve the intermediate watcher request; it also must not overwrite latest.
  for (const item of pending.slice(1, -1)) item.resolve({ points: [point(2)] });
  await nextTick();
  assert.equal(state.points.value[0].value, 99);
});

test("接口失败显示错误可重试；目录空态和单图失败互不影响", async () => {
  const detail = setup("order.amount", { loadMetricCount: async () => { throw new Error("网络错误"); } });
  await detail.state.load();
  assert.match(detail.state.error.value, /网络错误/);
  assert.equal(detail.state.loading.value, false);
  const empty = setup("", { loadMetricCatalog: async () => [] });
  await empty.state.load();
  assert.equal(empty.state.cards.value.length, 0);
  const partial = setup("", { loadMetricCount: async (_, name) => {
    if (name === "order.amount") throw new Error("失败");
    return { points: [point(10)] };
  } });
  await partial.state.load();
  assert.equal(partial.state.cards.value[0].error, "失败");
  assert.equal(partial.state.cards.value[1].points[0].value, 10);
});

test("全空合入 other 有明确说明，缺口不画成 0", async () => {
  const { state } = setup("order.amount", { loadMetricCount: async () => ({ points: [{ ...point(null), quality: "MERGED_OTHER" }] }) });
  await state.load();
  assert.equal(state.hasValue.value, false);
  assert.match(state.emptyMessage.value, /other/);
  assert.equal(state.chartSeries.value[0].points[0].value, null);
});

test("部分桶合入 other 时仍在图下说明，已知值与缺口分别保留", async () => {
  const { state } = setup("order.amount", { loadMetricCount: async () => ({
    points: [point(12), { ...point(null), quality: "MERGED_OTHER" }],
  }) });
  await state.load();
  assert.equal(state.hasValue.value, true);
  assert.equal(state.hasMergedOther.value, true);
  assert.match(state.emptyMessage.value, /部分数据已合入 other/);
  assert.equal(state.chartSeries.value[0].points[0].value, 12);
  assert.equal(state.chartSeries.value[0].points[1].value, null);
  assert.match(compileComponentTemplate("../src/views/MetricView.vue").source, /!hasValue \|\| hasMergedOther/);
});

test("空数组及全 null 仍保留图表与时间轴；详情错误不当作无数据", async () => {
  for (const points of [[], [point(null)]]) {
    const { state } = setup("order.amount", { loadMetricCount: async () => ({ points }) });
    await state.load();
    assert.equal(state.hasValue.value, false);
    assert.equal(state.chartSeries.value.length, 1);
    assert.ok(state.axisTimes.value.length > 0);
    assert.equal(state.points.value.length, points.length);
  }
  const template = compileComponentTemplate("../src/views/MetricView.vue");
  assert.doesNotMatch(template.source, /v-else-if="!hasValue"/);
  assert.match(template.source, /:show-empty-chart="!card.error"/);
  assert.match(template.source, /:axis-times="axisTimes"/);
});

test("mock 场景随下钻/返回/时间/标签保留，并参与重新加载；删除恢复默认", async () => {
  const { state, route, requests } = setup("order.amount", {}, { range, mockMetric: "all-null" });
  await state.load();
  assert.equal(requests.at(-1)[4], "all-null");
  assert.equal(state.detailTo("queue.depth").query.mockMetric, "all-null");
  assert.equal(state.overviewTo.value.query.mockMetric, "all-null");
  await state.toggleLabel("city", "上海");
  assert.equal(route.query.mockMetric, "all-null");
  await state.onRange({ range: "RECENT_3H" });
  assert.equal(route.query.mockMetric, "all-null");
  route.query.mockMetric = "empty-points";
  await nextTick();
  assert.equal(requests.at(-1)[4], "empty-points");
  delete route.query.mockMetric;
  await nextTick();
  assert.equal(requests.at(-1)[4], undefined);
  assert.equal(state.overviewTo.value.query.mockMetric, undefined);
});

test("真实模板包含小图/大图、标签复选、下钻路由；Problem 删除说明且局部全宽", () => {
  const metric = compileComponentTemplate("../src/views/MetricView.vue");
  const problem = compileComponentTemplate("../src/views/ProblemView.vue");
  assert.equal(metric.compiled.errors.length, 0);
  assert.equal(problem.compiled.errors.length, 0);
  assert.match(metric.source, /<MiniChart/);
  assert.match(metric.source, /<ReportChart/);
  assert.match(metric.source, /type="checkbox"/);
  assert.doesNotMatch(metric.source, /<table/);
  assert.doesNotMatch(problem.source, /<p class="hint"/);
  assert.match(problem.source, /problem-panel/);
  const router = readFileSync(new URL("../src/router.ts", import.meta.url), "utf8");
  assert.match(router, /svc\/:service\/metric\/:metric/);
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  assert.match(css, /\.workspace-panel\.metric-panel,[\s\S]*?max-width: none/);
  assert.match(css, /\.workspace-panel\.problem-panel/);
  assert.match(css, /\.workspace-panel \{[\s\S]*?max-width: 1280px/);
});

test("大图提示不再把用户自定义名称当 markup 输出", () => {
  assert.equal(escapeChartText('<img onerror="alert(1)">&'), "&lt;img onerror=&quot;alert(1)&quot;&gt;&amp;");
  const state = setupComponent("../src/components/ReportChart.vue", {
    series: [{ name: '<img onerror="alert(1)">', points: [point(12)] }],
  }, { "../api/types": { toChartValue } });
  const tooltip = state.option.value.tooltip.formatter([{ seriesName: '<img onerror="alert(1)">', dataIndex: 0 }]);
  assert.doesNotMatch(tooltip, /<img/);
  assert.match(tooltip, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}<br\/>12$/);
});

test("URL 时间移除或切换服务不沿用旧历史时间/候选；同一范围可重新查询", async () => {
  const { state, route, requests } = setup("order.amount");
  await state.load();
  const count = requests.length;
  await state.onRange({ range });
  assert.ok(requests.length > count);
  assert.equal(state.loading.value, false);
  route.params.service = "pay";
  route.query = {};
  await nextTick();
  await state.load();
  assert.equal(requests.at(-1)[0], "pay");
  assert.equal(requests.at(-1)[2], timeRange.currentHourScope().range);
  assert.deepEqual(requests.at(-1)[3], {});
});

test("上报方自定义的 constructor / __proto__ 标签键可安全选择和清空", async () => {
  const { state, route } = setup("order.amount");
  await state.load();
  assert.equal(state.selectedValues("constructor").length, 0);
  assert.equal(state.selectedValues("__proto__").length, 0);
  await state.toggleLabel("constructor", "value");
  await state.toggleLabel("__proto__", "another");
  await nextTick();
  assert.deepEqual(JSON.parse(route.query.filters), JSON.parse('{"constructor":["value"],"__proto__":["another"]}'));
  assert.equal(state.selectedValues("constructor")[0], "value");
  await state.clearFilters();
  await nextTick();
  assert.equal(state.selectedValues("constructor").length, 0);
});
