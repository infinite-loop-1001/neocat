import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { nextTick, reactive } from "vue";
import { qualityLabel, toChartValue } from "../src/api/types.ts";
import { seriesColor } from "../src/api/palette.ts";
import { compileComponentTemplate, setupComponent } from "./sfc-setup.mjs";

function line(name, color, value = 10) {
  return {
    name,
    color,
    points: [
      { bucketStart: 0, value, quality: value === null ? "NO_DATA" : "OK" },
      { bucketStart: 60_000, value: null, quality: "NO_DATA" },
    ],
  };
}

function chart(series, extra = {}) {
  const props = reactive({ title: "jvm.memory.heap.used", series, ...extra });
  const state = setupComponent("../src/components/MiniChart.vue", props, {
    "../api/types": { qualityLabel, toChartValue },
    "../api/palette": { seriesColor },
  });
  return { state, props };
}

/** 假 document：只记录监听器，让组件能在无浏览器环境下走真实的挂载/卸载路径。 */
function fakeDocument() {
  const listeners = new Map();
  return {
    addEventListener: (type, fn) => listeners.set(type, [...(listeners.get(type) ?? []), fn]),
    removeEventListener: (type, fn) => listeners.set(type, (listeners.get(type) ?? []).filter((item) => item !== fn)),
    dispatch: (type, event) => (listeners.get(type) ?? []).forEach((fn) => fn(event)),
    count: (type) => (listeners.get(type) ?? []).length,
  };
}

function noteChart(note, series = [line("10.0.0.8")]) {
  const doc = fakeDocument();
  const props = reactive({ title: "jvm.memory.young.committed", series, note });
  const state = setupComponent(
    "../src/components/MiniChart.vue",
    props,
    { "../api/types": { qualityLabel, toChartValue }, "../api/palette": { seriesColor } },
    "note-test",
    { document: doc }
  );
  state.__mounted();
  return { state, props, doc };
}

test("机器图例在画布及空状态下方，逐行按钮支持键盘和按下状态", () => {
  const { source, compiled } = compileComponentTemplate("../src/components/MiniChart.vue");
  assert.equal(compiled.errors.length, 0);
  assert.ok(source.indexOf('class="mini-chart-legend"') > source.indexOf('class="mini-chart-canvas"'));
  assert.ok(source.indexOf('class="mini-chart-legend"') > source.indexOf('class="mini-chart-empty"'));
  assert.match(source, /<li v-for="row in legendRows"/);
  assert.match(source, /type="button"/);
  assert.match(source, /:aria-pressed=/);
  assert.match(source, /@click="toggleSeries\(row.name\)"/);
  assert.match(source, /notMerge: true/);
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  const legendCss = css.match(/\.mini-chart-legend \{([^}]*)\}/)[1];
  assert.match(legendCss, /grid-template-columns: minmax\(0, 1fr\)/);
  assert.match(legendCss, /max-height: 120px/);
  assert.match(legendCss, /overflow: auto/);
});

test("取消顶部 ECharts 图例，色标与曲线颜色一一对应", () => {
  const { state } = chart([line("10.0.0.8", "#2563eb"), line("10.0.0.9", "#dc2626")]);
  assert.equal(state.option.value.legend, undefined);
  assert.equal(state.option.value.grid.top, 12);
  for (const row of state.legendRows.value) {
    const series = state.option.value.series.find((s) => s.name === row.name);
    assert.equal(series.lineStyle.color, row.color);
    assert.equal(series.lineStyle.type, "solid");
    assert.equal(series.connectNulls, false);
    assert.equal(series.data[1], null);
  }
});

test("点击隐藏和恢复曲线：机器行仍保留，其余机器颜色不变", () => {
  const { state } = chart([line("10.0.0.8"), line("10.0.0.9")]);
  state.toggleSeries("10.0.0.8");
  assert.equal(state.legendRows.value.length, 2);
  assert.equal(state.option.value.series.length, 1);
  assert.equal(state.option.value.series[0].name, "10.0.0.9");
  assert.equal(state.option.value.series[0].lineStyle.color, seriesColor(1));
  state.toggleSeries("10.0.0.8");
  assert.equal(state.option.value.series.length, 2);
  assert.equal(state.option.value.series[0].lineStyle.color, seriesColor(0));
});

test("只有一台机器仍保留图例，全隐藏不触发缺数状态且可恢复", () => {
  const { state } = chart([line("10.0.0.8")]);
  assert.equal(state.legendRows.value.length, 1);
  state.toggleSeries("10.0.0.8");
  assert.equal(state.option.value.series.length, 0);
  assert.equal(state.hasValue.value, true);
  assert.equal(state.option.value.xAxis.data.length, 2);
  state.toggleSeries("10.0.0.8");
  assert.equal(state.option.value.series.length, 1);
});

test("刷新保留仍在列表中的隐藏状态，筛掉再加入实例恢复显示", async () => {
  const { state, props } = chart([line("10.0.0.8"), line("10.0.0.9")]);
  state.toggleSeries("10.0.0.8");
  props.series = [line("10.0.0.8", undefined, 20), line("10.0.0.9", undefined, 30)];
  await nextTick();
  assert.equal(state.option.value.series.length, 1);
  props.series = [line("10.0.0.9")];
  await nextTick();
  assert.equal(state.hiddenNames.value.length, 0);
  props.series = [line("10.0.0.8"), line("10.0.0.9")];
  await nextTick();
  assert.equal(state.option.value.series.length, 2);
});

test("全空指标保留原因说明和机器图例，不生成零值", () => {
  const { state, props } = chart([line("10.0.0.8", undefined, null)]);
  props.emptyText = "未定义或不可用";
  assert.equal(state.hasValue.value, false);
  assert.equal(state.emptyText.value, "未定义或不可用");
  assert.equal(state.legendRows.value.length, 1);
  assert.ok(state.option.value.series[0].data.every((value) => value === null));
  props.series = [];
  assert.equal(state.legendRows.value.length, 0);
  assert.equal(state.hasValue.value, false);
});

test("指标名后紧跟 ? 再显示单位，口径为悬浮 tooltip 而非占一行的说明", () => {
  const { source, compiled } = compileComponentTemplate("../src/components/MiniChart.vue");
  assert.equal(compiled.errors.length, 0);
  assert.match(source, /v-if="note"/);
  assert.match(source, /v-if="note && noteOpen"/);
  assert.match(source, /:aria-expanded="noteOpen"/);
  assert.match(source, /type="button"/);
  assert.match(source, /role="tooltip"/);
  assert.match(source, /:aria-describedby="noteOpen \? noteId : undefined"/);
  assert.ok(source.indexOf('class="mini-chart-note-trigger"') < source.indexOf('class="mini-chart-unit"'));
  assert.ok(source.indexOf('role="tooltip"') < source.indexOf('</figcaption>'));
  assert.match(source, /@mouseenter="onNoteEnter"/);
  assert.match(source, /@mouseleave="onNoteLeave"/);
  assert.match(source, /@focus="onNoteFocus"/);
  assert.match(source, /@blur="onNoteBlur"/);
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  assert.match(css.match(/\.mini-chart-note \{([^}]*)\}/)[1], /position: absolute/);
  // 计数器按钮只能有一个，不能变成链接（否则键盘语义与「可展开」不一致）
  assert.doesNotMatch(source, /<a[^>]*mini-chart-note-trigger/);
  const { state } = noteChart("这里是口径");
  assert.equal(state.noteOpen.value, false, "默认不展开");
  assert.equal(state.props.note, "这里是口径");
});

test("悬停与键盘焦点显示口径，移出浮层后仍在聚焦则保持显示", () => {
  const { state } = noteChart("这里是口径");
  state.onNoteEnter();
  assert.equal(state.noteOpen.value, true);
  state.onNoteLeave();
  assert.equal(state.noteOpen.value, false);
  state.onNoteFocus();
  state.onNoteEnter();
  state.onNoteLeave();
  assert.equal(state.noteOpen.value, true);
  state.onNoteBlur();
  assert.equal(state.noteOpen.value, false);
  state.__unmounted();
});

test("触屏点击可开关；Esc 与外部 pointerdown 收起，卸载后不再监听", () => {
  const { state, doc } = noteChart("这里是口径");
  state.toggleNote();
  const trigger = { contains: (node) => node === "trigger" };
  const panel = { contains: (node) => node === "panel" };
  state.noteTrigger.value = trigger;
  state.notePanel.value = panel;

  // 点击面板或按钮自身不收起，避免点开立刻被关掉
  doc.dispatch("pointerdown", { target: "panel" });
  doc.dispatch("pointerdown", { target: "trigger" });
  assert.equal(state.noteOpen.value, true);
  doc.dispatch("pointerdown", { target: "elsewhere" });
  assert.equal(state.noteOpen.value, false);

  // Esc 收起但不移动焦点（浮层本身不接收焦点）
  const focused = [];
  state.noteTrigger.value = { ...trigger, focus: () => focused.push("trigger") };
  state.onNoteFocus();
  doc.dispatch("keydown", { key: "Enter" });
  assert.equal(state.noteOpen.value, true, "只响应 Escape");
  doc.dispatch("keydown", { key: "Escape" });
  assert.equal(state.noteOpen.value, false);
  assert.deepEqual(focused, []);
  state.onNoteLeave();
  assert.equal(state.noteOpen.value, false, "Escape 后不因按钮仍聚焦而重新弹出");
  state.toggleNote();
  assert.equal(state.noteOpen.value, true);
  state.toggleNote();
  assert.equal(state.noteOpen.value, false, "按钮聚焦时再次点击也可关闭");

  assert.equal(doc.count("keydown"), 1);
  assert.equal(doc.count("pointerdown"), 1);
  state.__unmounted();
  assert.equal(doc.count("keydown"), 0, "卸载后必须移除监听，否则换页会残留");
  assert.equal(doc.count("pointerdown"), 0);
});

test("换指标后不沿用上一张图的展开态", async () => {
  const { state, props } = noteChart("旧口径");
  state.toggleNote();
  props.note = "新口径";
  await nextTick();
  assert.equal(state.noteOpen.value, false);
  assert.equal(props.note, "新口径");
});

test("每图提示 ID 独立，无口径不显示浮层；移除口径重置固定态", async () => {
  const first = noteChart("说明一");
  const second = noteChart("说明二");
  assert.notEqual(first.state.noteId, second.state.noteId);
  first.state.toggleNote();
  first.props.note = undefined;
  await nextTick();
  assert.equal(first.state.noteOpen.value, false);
  assert.equal(first.state.notePinned.value, false);
  const absent = chart([line("10.0.0.8")]);
  absent.state.onNoteEnter();
  assert.equal(absent.state.noteOpen.value, false);
  first.state.__unmounted();
  second.state.__unmounted();
});
