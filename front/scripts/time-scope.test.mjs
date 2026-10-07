import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { runInNewContext } from "node:vm";
import { parse, compileScript, compileTemplate } from "@vue/compiler-sfc";
import { transformSync } from "esbuild";
import { computed, defineComponent, ref } from "vue";
import { namesInWindow, now } from "../src/mock/dataset.ts";
import { alignDay, alignHour, alignMonth, alignTo, alignWeek, currentHourScope, isoDate, isoMonth, rangeAt, stepForward, initialTimeSelection, quickTimeBounds } from "../src/api/time-range.ts";

// 编译真实组件并执行 setup，无浏览器、无额外测试依赖。
const source = readFileSync(new URL("../src/components/TimeScope.vue", import.meta.url), "utf8");
const { descriptor } = parse(source);
const script = compileScript(descriptor, { id: "time-scope-test" });
const code = transformSync(script.content, { loader: "ts", format: "cjs" }).code;

function setup(initialTime, props = {}) {
  let time = initialTime;
  let tick;
  let unmount;
  const events = [];
  const module = { exports: {} };
  class Clock extends Date {
    static now() { return time; }
  }
  runInNewContext(code, {
    module,
    exports: module.exports,
    Date: Clock,
    require: (name) => {
      if (name === "vue") {
        return {
          computed, defineComponent, ref,
          onMounted: (fn) => fn(),
          onUnmounted: (fn) => { unmount = fn; },
        };
      }
      // 依赖必须显式给出：时间对齐与默认范围只有一份实现，
      // 测试要验证的是真实那一份，而不是另抄的值。
      if (name === "../api/time-range") {
        return { alignDay, alignHour, alignMonth, alignTo, alignWeek, isoDate, isoMonth, rangeAt, stepForward, initialTimeSelection, quickTimeBounds };
      }
      throw new Error(`Unexpected dependency: ${name}`);
    },
    setInterval: (fn) => { tick = fn; return 1; },
    clearInterval: () => { tick = undefined; },
  });
  const state = module.exports.default.setup(props, {
    expose() {},
    emit: (name, payload) => events.push({ name, ...payload }),
  });
  return { state, events, advance: (ms) => { time += ms; tick(); }, unmount: () => unmount() };
}

const initial = new Date(2026, 9, 2, 10, 30).getTime();

test("显式历史范围初始化控件与请求一致，且不影响默认值", () => {
  const historical = `HOUR:${new Date(2026, 9, 1, 9).getTime()}`;
  const { state } = setup(initial, { initialRange: historical });
  assert.equal(state.current().range, historical);
  assert.equal(state.mode.value, "window");
  assert.equal(state.windowBounds.value.from, new Date(2026, 9, 1, 9).getTime());
  assert.equal(state.canForward.value, true);
  assert.equal(setup(initial).state.current().range, currentHourScope(initial).range);
  const restoredCurrent = setup(initial, { initialRange: currentHourScope(initial).range }).state;
  assert.equal(restoredCurrent.quickProxy.value, "CURRENT_HOUR");
  assert.equal(restoredCurrent.current().range, currentHourScope(initial).range);
});

test("恢复周/月窗口和快捷范围，未来或非法 URL 不能进入未来", () => {
  for (const range of ["WEEK:2026-09-28", "MONTH:2026-09", "RECENT_3H", "RECENT_24H"]) {
    const { state } = setup(initial, { initialRange: range });
    assert.equal(state.current().range, range);
  }
  for (const raw of ["HOUR:9999999999999", "MONTH:2026-13", "WEEK:2026-02-31", "garbage"]) {
    assert.equal(initialTimeSelection(raw, initial).range, currentHourScope(initial).range);
  }
  const { state } = setup(initial, { initialRange: "RECENT_3H" });
  assert.equal(state.windowBounds.value.to - state.windowBounds.value.from, 3 * 3_600_000);
  assert.equal(state.current().bucketSeconds, 300);
});

test("默认是当前整点小时，不是带零头的滚动窗口", () => {
  const { state, events } = setup(initial);
  assert.equal(state.mode.value, "quick");
  // 控件显示的就是整点区间，没有 18:59–19:59 这种零头
  // （逐字段比：vm 里构造的对象与宿主 Object 不同源，deepStrictEqual 会因原型不同而失败）
  assert.equal(state.windowBounds.value.from, new Date(2026, 9, 2, 10).getTime());
  assert.equal(state.windowBounds.value.to, new Date(2026, 9, 2, 11).getTime());
  state.onQuick();
  assert.equal(state.current().range, `HOUR:${new Date(2026, 9, 2, 10).getTime()}`);
  assert.equal(state.current().bucketSeconds, 60);
  assert.ok(events.every((event) => event.range === state.current().range));
});

test("默认范围与报表页初始 range 同源，控件与取数不会各算一份", () => {
  const { state } = setup(initial);
  const scope = currentHourScope(initial);
  assert.equal(state.current().range, scope.range);
  assert.equal(state.current().bucketSeconds, scope.bucketSeconds);
  // 报表页就是拿这个字符串去取数的（见各 View 的 range 初始化）
  const view = readFileSync(new URL("../src/views/HeartbeatView.vue", import.meta.url), "utf8");
  assert.match(view, /const range = ref\(currentHourScope\(\)\.range\)/);
});

test("◀ 进入上一个整点小时，▶ 回到当前整点小时", () => {
  const { state, events } = setup(initial);
  state.shift(-1);
  assert.equal(state.windowBounds.value.from, new Date(2026, 9, 2, 9).getTime());
  assert.equal(state.canForward.value, true);
  state.shift(1);
  assert.equal(state.windowBounds.value.from, new Date(2026, 9, 2, 10).getTime());
  assert.equal(state.canForward.value, false);
  // 不能翻到未来
  const count = events.length;
  state.shift(1);
  assert.equal(events.length, count);
  assert.equal(state.windowBounds.value.from, new Date(2026, 9, 2, 10).getTime());
});

test("窗口下拉的「当前小时」是整点窗口，且与默认项一致", () => {
  const { state } = setup(initial);
  state.onQuick();
  // 先切到别的范围，再切回「当前小时」，应回到同一个整点窗口
  state.quick.value = "RECENT_3H";
  state.onQuick();
  assert.equal(state.current().range, "RECENT_3H");
  assert.equal(state.current().bucketSeconds, 300);
  state.quick.value = "CURRENT_HOUR";
  state.onQuick();
  assert.equal(state.current().range, currentHourScope(initial).range);
  assert.equal(state.current().bucketSeconds, 60);
});

test("快捷范围 ▶ 仍可进入当前自然小时", () => {
  const { state, events } = setup(initial);
  assert.equal(state.canForward.value, true);
  state.shift(1);
  assert.equal(state.mode.value, "window");
  assert.equal(state.windowStart.value, new Date(2026, 9, 2, 10).getTime());
  assert.equal(events.length, 1);
  assert.equal(state.canForward.value, false);
  state.onQuick();
  assert.equal(state.canForward.value, true);
});

for (const step of ["h", "w", "m"]) {
  test(`${step}: 历史可回到当前，但未来窗口不发请求`, () => {
    const { state, events } = setup(initial);
    state.setStep(step);
    const current = state.windowStart.value;
    assert.equal(state.canForward.value, false);
    const count = events.length;
    state.shift(1);
    assert.equal(state.windowStart.value, current);
    assert.equal(events.length, count);
    state.shift(-1);
    assert.equal(state.canForward.value, true);
    state.shift(1);
    assert.equal(state.windowStart.value, current);
    assert.equal(state.canForward.value, false);
    state.shift(-1);
    state.goNow();
    assert.equal(state.windowStart.value, current);
    assert.equal(state.canForward.value, false);
  });
}

test("跨小时边界自动恢复 ▶，但时钟刷新不发请求", () => {
  const { state, events, advance, unmount } = setup(initial);
  state.setStep("h");
  const count = events.length;
  advance(30 * 60_000);
  assert.equal(state.canForward.value, true);
  assert.equal(events.length, count);
  state.shift(1);
  assert.equal(state.windowStart.value, new Date(2026, 9, 2, 11).getTime());
  assert.equal(state.canForward.value, false);
  unmount();
});

for (const [step, start, boundary] of [
  ["w", new Date(2026, 9, 4, 23, 59), new Date(2026, 9, 5)],
  ["m", new Date(2026, 9, 31, 23, 59), new Date(2026, 10, 1)],
]) {
  test(`${step}: 跨自然周期后 ▶ 自动恢复可用`, () => {
    const { state, events, advance } = setup(start.getTime());
    state.setStep(step);
    assert.equal(state.canForward.value, false);
    advance(60_000);
    assert.equal(state.canForward.value, true);
    assert.equal(events.length, 1);
    state.shift(1);
    assert.equal(state.windowStart.value, boundary.getTime());
    assert.equal(state.canForward.value, false);
  });
}

test("月翻页保持自然月边界（含年切换和二月）", () => {
  const { state } = setup(new Date(2026, 1, 28, 12).getTime());
  state.setStep("m");
  state.shift(-1);
  state.shift(-1);
  assert.equal(state.windowStart.value, new Date(2025, 11, 1).getTime());
  state.shift(1);
  state.shift(1);
  assert.equal(state.windowStart.value, new Date(2026, 1, 1).getTime());
  assert.equal(state.canForward.value, false);
});

test("模板将 canForward 绑定到原生 disabled", () => {
  const template = compileTemplate({ source: descriptor.template.content, id: "time-scope-test" });
  assert.equal(template.errors.length, 0);
  assert.match(template.code, /disabled: !_ctx.canForward/);
});

for (const [kind, type] of [["TRANSACTION", "URL"], ["EVENT", "business"]]) {
  test(`${kind}: Name 窗口稳定、历史翻页变化、未来无数据`, () => {
    const hour = Math.floor(now / 3_600_000) * 3_600_000;
    const range = `HOUR:${hour - 3_600_000}`;
    const rows = namesInWindow(kind, type, range);
    assert.deepEqual(rows, namesInWindow(kind, type, range));
    assert.notEqual(rows[0].total, namesInWindow(kind, type, `HOUR:${hour - 7_200_000}`)[0].total);
    // 完整的历史小时：分母就是整段 3600 秒
    assert.equal(rows[0].qps, rows[0].total / 3600);
    assert.ok(rows.every((row) => row.failures <= row.total));
    // 当前小时只覆盖「整点 → now」，QPS 分母是已过去的秒数（PRD 03 §4）
    const current = namesInWindow(kind, type, `HOUR:${hour}`);
    assert.equal(current[0].qps, current[0].total / ((now - hour) / 1000));
    const baseline = kind === "TRANSACTION" ? 820 : 3600;
    const traffic = 0.8 + (Math.abs(Math.floor(hour / 3_600_000)) % 13) * 0.04;
    assert.equal(current[0].total, Math.round(baseline * (now - hour) / 3_600_000 * traffic));
    assert.deepEqual(namesInWindow(kind, type, `HOUR:${hour + 3_600_000}`), []);
    assert.deepEqual(namesInWindow(kind, "unknown", range), []);
  });
}
