import assert from "node:assert/strict";
import { test } from "node:test";
import { metricCatalog, metricCount, metricLabels } from "../src/mock/metric-dataset.ts";
import { normalizeFilters, matchesFilters, decodeFilters } from "../src/api/metric.ts";

const clock = new Date(2026, 9, 2, 10, 30, 15).getTime();
const range = `HOUR:${new Date(2026, 9, 2, 9).getTime()}`;

test("Metric 目录按名称区分，标签候选不串指标", () => {
  assert.ok(metricCatalog.length >= 3);
  assert.equal(new Set(metricCatalog.map((m) => m.name)).size, metricCatalog.length);
  assert.ok(metricLabels("order.amount").some((label) => label.key === "city"));
  assert.ok(!metricLabels("queue.depth").some((label) => label.key === "city"));
  assert.deepEqual(metricLabels("missing"), []);
});

test("标签同键 OR、异键 AND；规范化无空条件且顺序稳定", () => {
  const filters = normalizeFilters({ city: ["上海"], channel: ["web", "app", "app"], empty: [] });
  assert.deepEqual(filters, { channel: ["app", "web"], city: ["上海"] });
  assert.ok(matchesFilters({ channel: "app", city: "上海" }, filters));
  assert.ok(matchesFilters({ channel: "web", city: "上海" }, filters));
  assert.ok(!matchesFilters({ channel: "app", city: "北京" }, filters));
  assert.ok(!matchesFilters({ city: "上海" }, filters));
  assert.ok(matchesFilters({}, {}));
});

test("默认 count 来自全量包含 other，筛选单曲线，清空恢复，不是组合数量", () => {
  const total = metricCount("order.amount", range, {}, clock);
  const app = metricCount("order.amount", range, { channel: ["app"] }, clock);
  const web = metricCount("order.amount", range, { channel: ["web"] }, clock);
  const other = metricCount("order.amount", range, { channel: ["partner"] }, clock);
  assert.equal(total.points.length, 60);
  let compared = 0;
  for (let i = 0; i < total.points.length; i++) {
    if ([total, app, web, other].every((s) => s.points[i].value !== null)) {
      assert.equal(total.points[i].value, app.points[i].value + web.points[i].value + other.points[i].value);
      assert.ok(total.points[i].value > app.points[i].value);
      compared++;
    }
  }
  assert.ok(compared > 0);
  assert.deepEqual(metricCount("order.amount", range, {}, clock), total);
});

test("匹配组合合入 other 的小时保持缺口，不显示可见子集或假零", () => {
  // mock 中 partner 组合在偶数小时合入 other；全量仍含其原始 count。
  const mergedRange = `HOUR:${new Date(2026, 9, 2, 8).getTime()}`;
  const total = metricCount("order.amount", mergedRange, {}, clock);
  const filtered = metricCount("order.amount", mergedRange, { channel: ["partner"] }, clock);
  assert.ok(total.points.some((p) => p.value > 0));
  assert.ok(filtered.points.some((p) => p.quality === "MERGED_OTHER"));
  assert.ok(filtered.points.every((p) => p.value === null));
});

test("确认无匹配是 ZERO，未来桶是 NO_DATA，当前桶是 REALTIME", () => {
  const currentRange = `HOUR:${new Date(2026, 9, 2, 10).getTime()}`;
  const result = metricCount("order.amount", currentRange, {}, clock);
  assert.equal(result.points.filter((p) => p.quality === "REALTIME").length, 1);
  assert.ok(result.points.some((p) => p.bucketStart > clock));
  assert.ok(result.points.filter((p) => p.bucketStart > clock).every((p) => p.value === null && p.quality === "NO_DATA"));
  const absent = metricCount("order.amount", range, { city: ["不存在"] }, clock);
  assert.ok(absent.points.some((p) => p.value === 0 && p.quality === "ZERO"));
  assert.deepEqual(metricCount("unknown", range, {}, clock).points, []);
});

test("同键 OR 合并只计一次，异键 AND 收窄聚合，包含未知条件的 URL 安全恢复", () => {
  const both = metricCount("order.amount", range, { channel: ["app", "web"] }, clock);
  const app = metricCount("order.amount", range, { channel: ["app"] }, clock);
  const web = metricCount("order.amount", range, { channel: ["web"] }, clock);
  const narrow = metricCount("order.amount", range, { channel: ["app", "web"], city: ["上海"] }, clock);
  for (let i = 0; i < both.points.length; i++) {
    assert.equal(both.points[i].value, app.points[i].value + web.points[i].value);
    assert.ok(narrow.points[i].value < both.points[i].value);
  }
  assert.deepEqual(decodeFilters('{"city":["上海"],"channel":["web","app","web"]}'), { channel: ["app", "web"], city: ["上海"] });
  for (const invalid of ["bad", "null", "[]", '{"city":"上海"}']) assert.deepEqual(decodeFilters(invalid), {});
  assert.deepEqual(metricLabels("constructor"), []);
  assert.deepEqual(metricCount("__proto__", range, {}, clock).points, []);
  assert.equal(matchesFilters({}, { constructor: ["Object"] }), false);
});

test("粗粒度 count 是同源分钟桶之和，不平均 count；不确定分钟让整个桶断线", () => {
  const weekly = metricCount("order.amount", "WEEK:2026-09-28", {}, clock);
  const hourStart = new Date(2026, 9, 2, 9).getTime();
  const hour = weekly.points.find((p) => p.bucketStart === hourStart);
  const minutes = metricCount("order.amount", `HOUR:${hourStart}`, {}, clock);
  assert.equal(hour.value, minutes.points.reduce((sum, p) => sum + p.value, 0));
  const droppedHour = weekly.points.find((p) => p.bucketStart === new Date(2026, 9, 2, 8).getTime());
  assert.equal(droppedHour.quality, "DROPPED");
  assert.equal(droppedHour.value, null);
  const monthly = metricCount("order.amount", "MONTH:2026-10", {}, clock);
  assert.equal(monthly.bucketSeconds, 86400);
  assert.ok(monthly.points.some((p) => p.value > 0));
  assert.equal(monthly.points.find((p) => p.bucketStart === new Date(2026, 9, 2).getTime()).value, null);
  assert.ok(monthly.points.filter((p) => p.bucketStart > clock).every((p) => p.value === null));
});

test("滚动窗口对齐桶，保留部分覆盖与实时桶；整点及最后一分钟未来语义稳定", () => {
  const rolling = metricCount("order.amount", "RECENT_3H", {}, clock);
  assert.equal(rolling.points[0].bucketStart % 300000, 0);
  assert.equal(rolling.points[0].quality, "PARTIAL");
  assert.equal(rolling.points.at(-1).quality, "REALTIME");
  assert.equal(rolling.points.at(-1).bucketEnd - rolling.points.at(-1).bucketStart, 300000);
  for (const minute of [0, 59]) {
    const at = new Date(2026, 9, 2, 10, minute).getTime();
    const result = metricCount("order.amount", `HOUR:${new Date(2026, 9, 2, 10).getTime()}`, {}, at);
    assert.equal(result.points.length, 60);
    assert.equal(result.points[minute].quality, "REALTIME");
    assert.ok(result.points.slice(minute + 1).every((p) => p.value === null && p.quality === "NO_DATA"));
  }
});
