import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { compileTemplate } from "@vue/compiler-sfc";
import { problemLabel, problemSupportsDuration } from "../src/api/problem.ts";
import { comparisonChartSeries } from "../src/api/comparison.ts";
import { momPoints, seriesWindow, seriesInWindow } from "../src/mock/dataset.ts";
import * as timeRange from "../src/api/time-range.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";

const now = new Date(2026, 9, 2, 10, 30).getTime();
const window = seriesWindow("RECENT_1H", 60, now);
const current = seriesInWindow(now, window, 55);

/** 以 kind=PROBLEM 执行真实趋势页，`type` 用后端原始类别枚举。 */
function seriesViewSetup(type, requests) {
  return setupComponent(
    "../src/views/SeriesView.vue",
    { kind: "PROBLEM" },
    {
      "vue-router": {
        useRoute: () => ({ params: { service: "order", type, name: "target" } }),
        useRouter: () => ({}),
      },
      "../api/comparison": { comparisonChartSeries },
      "../api/problem": { problemLabel, problemSupportsDuration },
      "../api/time-range": timeRange,
      "../api": {
        api: async (path, options) => {
          if (path === "/reports/series") {
            requests.push(options.query);
            return { points: current, bucketSeconds: 60, mom: null };
          }
          return [];
        },
      },
    },
    "problem-drilldown-test"
  );
}

test("五类标签与分位能力符合 PRD 03 §9", () => {
  const categories = ["EXCEPTION", "SLOW_URL", "SLOW_SQL", "SLOW_CALL", "SLOW_CACHE"];
  assert.deepEqual(
    categories.map(problemLabel),
    ["exception", "long-url", "long-sql", "long-call", "long-cache"]
  );
  assert.equal(problemSupportsDuration("EXCEPTION"), false);
  for (const category of categories.slice(1)) assert.equal(problemSupportsDuration(category), true);
  assert.equal(problemSupportsDuration("SLOW_SQL"), problemSupportsDuration("slow_sql"));
  // 未收录类别不被隐藏，回落为小写原名
  assert.equal(problemLabel("SLOW_NEW"), "slow_new");
});

for (const [type, expectsDuration] of [["EXCEPTION", false], ["SLOW_SQL", true], ["SLOW_CALL", true]]) {
  test(`Problem 趋势页: ${type} ${expectsDuration ? "显示" : "隐藏"}耗时与分位`, async () => {
    const requests = [];
    const state = seriesViewSetup(type, requests);
    assert.equal(state.showsDuration.value, expectsDuration);
    assert.equal(state.title.value, "Problem");
    assert.equal(state.displayType.value, problemLabel(type));
    assert.equal(state.backTo.value, "/svc/order/problem");
    assert.equal(state.backLabel.value, "Problem");
    await state.load();
    assert.equal(requests.at(-1).kind, "PROBLEM");
    assert.equal(requests.at(-1).type, type);
    assert.equal(requests.at(-1).name, "target");
  });
}

test("Problem 页面用同一套标签，下钻用后端原始枚举，取样点击不触发行跳转", () => {
  const source = readFileSync(new URL("../src/views/ProblemView.vue", import.meta.url), "utf8");
  assert.match(source, /problemLabel\(c\.category\)/, "标签必须复用 api/problem，避免两处漂移");
  assert.match(source, /problem\/\$\{encodeURIComponent\(category\)\}/);

  const { source: template, compiled } = compileComponentTemplate("../src/views/ProblemView.vue");
  assert.match(template, /@click="openSeries\(group\.rawCategory, row\.name\)"/);
  assert.match(template, /@click\.stop="openTrace\(sample\.messageId\)"/);
  assert.equal(compiled.errors.length, 0);
});

test("Problem 趋势路由注册在 Problem 页之后且带 kind 属性", () => {
  const source = readFileSync(new URL("../src/router.ts", import.meta.url), "utf8");
  assert.match(source, /path: "svc\/:service\/problem\/:type\/:name"[^}]*props: \{ kind: "PROBLEM" \}/);
  const listIndex = source.indexOf('path: "svc/:service/problem"');
  const seriesIndex = source.indexOf('path: "svc/:service/problem/:type/:name"');
  assert.ok(listIndex > 0 && seriesIndex > listIndex, "下钻路由应在列表路由之后");
});

test("Problem 环比复用同一套多选叠图", () => {
  const comparisons = ["DAY", "WEEK", "MONTH"].map((kind) => ({ kind, points: momPoints(window, kind, 55) }));
  const lines = comparisonChartSeries("HITS", current, comparisons, 60);
  assert.equal(lines.length, 4);
  assert.equal(new Set(lines.map((line) => line.color)).size, 4);
  assert.ok(lines.every((line) => line.lineType === "solid"));
});

test("趋势页模板：Problem 返回入口指向 Problem 页", () => {
  const { compiled } = compileComponentTemplate("../src/views/SeriesView.vue");
  assert.equal(compiled.errors.length, 0);
  assert.match(compiled.code, /backTo/);
  assert.match(compiled.code, /backLabel/);
});
