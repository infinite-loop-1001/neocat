import assert from "node:assert/strict";
import { test } from "node:test";
import { emptyChartAxis } from "../src/api/chart-axis.ts";
import { normalizeMetricMockScenario } from "../src/api/metric-scenario.ts";
import { metricCount, metricsForService, metricLabels } from "../src/mock/metric-dataset.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";
import { toChartValue, qualityLabel } from "../src/api/types.ts";
import { seriesColor } from "../src/api/palette.ts";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { transformSync } from "esbuild";
import { mockRequest, resetMockState } from "../src/mock/router.ts";
import { ApiError } from "../src/api/error.ts";
import { ERROR_CODE } from "../src/api/error-codes.ts";

const clock = new Date(2026, 9, 2, 10, 30, 15).getTime();
const start = new Date(2026, 9, 2, 9).getTime();
const range = `HOUR:${start}`;
const point = (value, quality = "OK") => ({ bucketStart: start, bucketEnd: start + 60000, value, quality });

test("空图轴只有时间刻度，不生成业务 Point；小时/周/月/滚动范围对齐", () => {
  const hourly = emptyChartAxis(range, clock);
  assert.equal(hourly.length, 60);
  assert.equal(hourly[0], start);
  assert.equal(hourly.at(-1), start + 59 * 60000);
  assert.ok(hourly.every((time) => typeof time === "number"));
  assert.equal(emptyChartAxis("WEEK:2026-09-28", clock).length, 168);
  assert.equal(emptyChartAxis("MONTH:2026-10", clock).length, 31);
  const rolling = emptyChartAxis("RECENT_3H", clock);
  assert.equal(rolling[0] % 300000, 0);
  assert.ok(rolling.at(-1) <= clock);
  assert.equal(emptyChartAxis(`HOUR:${clock + 86400000}`, clock)[0], new Date(2026, 9, 2, 10).getTime());
  assert.ok(emptyChartAxis("TODAY", clock).length > 0);
  assert.ok(emptyChartAxis("THIS_WEEK", clock).length > 0);
});

test("三个空数据 mock 保留目录标签且不改变默认数据；未知场景正常", () => {
  for (const invalid of [undefined, "", "unknown", ["all-null"], "constructor"]) {
    assert.equal(normalizeMetricMockScenario(invalid), undefined);
  }
  const normal = metricCount("order.amount", range, {}, clock);
  assert.ok(normal.points.some((p) => p.value > 0));
  for (const scenario of ["all-null", "empty-points", "partial"]) {
    assert.equal(normalizeMetricMockScenario(scenario), scenario);
    const empty = metricCount("order.amount", range, {}, clock, scenario);
    assert.equal(empty.bucketSeconds, normal.bucketSeconds);
    assert.ok(empty.points.every((p) => p.value === null && p.quality === "NO_DATA"));
    assert.equal(empty.points.length, scenario === "empty-points" ? 0 : normal.points.length);
  }
  assert.ok(metricCount("queue.depth", range, {}, clock, "partial").points.some((p) => p.value > 0));
  assert.deepEqual(metricCount("order.amount", range, {}, clock, "unknown"), normal);
  assert.deepEqual(metricCount("order.amount", range, {}, clock), normal);
  assert.equal(metricsForService("order").length, 3);
  assert.ok(metricLabels("order.amount").length > 0);
});

test("Metric 小图显式启用空轴；共享组件默认仍为旧空态，0 仍是真实点", () => {
  const dependencies = { "../api/types": { toChartValue, qualityLabel }, "../api/palette": { seriesColor } };
  const create = (extra = {}) => setupComponent("../src/components/MiniChart.vue", {
    title: "order.amount", series: [{ name: "总量", points: [] }], ...extra,
  }, dependencies);
  const original = create();
  assert.equal(original.showChart.value, false);
  const empty = create({ showEmptyChart: true, axisTimes: [start, start + 60000] });
  assert.equal(empty.showChart.value, true);
  assert.equal(empty.option.value.xAxis.data.length, 2);
  assert.equal(empty.option.value.series[0].data.length, 0);
  assert.equal(empty.option.value.yAxis.min, 0);
  assert.equal(empty.option.value.yAxis.max, 1);
  assert.equal(empty.legendRows.value[0].name, "总量");
  const gap = create({ showEmptyChart: true, axisTimes: [0], series: [{ name: "总量", points: [point(null)] }] });
  assert.equal(gap.option.value.xAxis.data.length, 1); // Real bucket takes precedence.
  assert.deepEqual(Array.from(gap.option.value.series[0].data), [null]);
  const zero = create({ series: [{ name: "总量", points: [point(0, "ZERO")] }] });
  assert.equal(zero.hasValue.value, true);
  assert.deepEqual(Array.from(zero.option.value.series[0].data), [0]);
  const template = compileComponentTemplate("../src/components/MiniChart.vue");
  assert.equal(template.compiled.errors.length, 0);
  assert.match(template.source, /v-if="showChart"/);
});

test("详情空数组保留时间轴但无点；MERGED_OTHER 不画点、不补零", () => {
  const dependencies = { "../api/types": { toChartValue } };
  const empty = setupComponent("../src/components/ReportChart.vue", {
    series: [{ name: "总量", points: [] }], axisTimes: [start, start + 60000],
  }, dependencies);
  assert.equal(empty.option.value.xAxis.data.length, 2);
  assert.equal(empty.option.value.series[0].data.length, 0);
  assert.equal(empty.option.value.yAxis.max, 1);
  const merged = setupComponent("../src/components/ReportChart.vue", {
    series: [{ name: "channel=partner", points: [point(null, "MERGED_OTHER")] }], axisTimes: [0, 1],
  }, dependencies);
  assert.equal(merged.option.value.xAxis.data.length, 1);
  assert.deepEqual(Array.from(merged.option.value.series[0].data), [null]);
});

test("真实 transport 不发送场景参数、不回退 mock；mock transport 独立消费场景", async () => {
  const code = transformSync(readFileSync(new URL("../src/api/client.ts", import.meta.url), "utf8"), { loader: "ts", format: "cjs" }).code;
  for (const useMock of [true, false]) {
    const module = { exports: {} };
    const calls = [];
    const failing = { value: false };
    runInNewContext(code, {
      module, exports: module.exports, URLSearchParams,
      require: (name) => {
        if (name === "../config") return { USE_MOCK: useMock, API_BASE: "/api" };
        if (name === "../mock") return { mockRequest: async (url) => { calls.push(["mock", url]); return {}; } };
        if (name === "./error") return { ApiError };
        if (name === "./error-codes") return { ERROR_CODE };
        throw new Error(`Unexpected dependency: ${name}`);
      },
      fetch: async (url, options) => {
        calls.push(["real", url, options.credentials]);
        if (failing.value) throw new Error("offline");
        return { ok: true, text: async () => "{}" };
      },
    });
    const options = { query: { service: "order", metric: "order.amount", range }, mockMetric: "empty-points" };
    await module.exports.api("/reports/metric/count", options);
    assert.equal(calls[0][0], useMock ? "mock" : "real");
    assert.equal(new URL(calls[0][1], "http://localhost").searchParams.has("mockMetric"), useMock);
    if (!useMock) {
      assert.equal(calls[0][2], "include");
      failing.value = true;
      await assert.rejects(module.exports.api("/reports/metric/count", options), /offline/);
      assert.ok(calls.every(([kind]) => kind === "real"));
    }
  }
});

test("mock 路由真正返回场景，标签及未知指标错误仍保留", async () => {
  resetMockState();
  await mockRequest("/api/login", { method: "POST", body: JSON.stringify({ username: "root", password: "NeoCat@2026" }) });
  for (const scenario of ["all-null", "empty-points", "partial"]) {
    const params = new URLSearchParams({ service: "order", metric: "order.amount", range, mockMetric: scenario });
    const result = await mockRequest(`/api/reports/metric/count?${params}`, { method: "GET" });
    assert.ok(result.points.every((p) => p.value === null));
    assert.equal(result.points.length === 0, scenario === "empty-points");
    const labels = await mockRequest(`/api/reports/metric/labels?${params}`, { method: "GET" });
    assert.ok(labels.length > 0);
  }
  await assert.rejects(mockRequest("/api/reports/metric/count?service=order&metric=missing&mockMetric=all-null", { method: "GET" }),
    (error) => error.status === 404);
  resetMockState();
});
