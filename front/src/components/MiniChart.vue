<template>
  <figure class="mini-chart">
    <figcaption class="mini-chart-title">
      <router-link v-if="detailTo" :to="detailTo">{{ title }}</router-link>
      <template v-else>{{ title }}</template>
      <span v-if="note" class="mini-chart-note-anchor" @mouseenter="onNoteEnter" @mouseleave="onNoteLeave">
        <button
          ref="noteTrigger"
          class="mini-chart-note-trigger"
          type="button"
          :aria-expanded="noteOpen"
          :aria-describedby="noteOpen ? noteId : undefined"
          :aria-label="`${title} 指标口径`"
          @focus="onNoteFocus"
          @blur="onNoteBlur"
          @click="toggleNote"
        >?</button>
        <span v-if="note && noteOpen" :id="noteId" ref="notePanel" class="mini-chart-note" role="tooltip">{{ note }}</span>
      </span>
      <span v-if="unit" class="mini-chart-unit">{{ unit }}</span>
    </figcaption>
    <v-chart v-if="showChart" class="mini-chart-canvas" :option="option" :update-options="{ notMerge: true }" autoresize />
    <p v-else class="mini-chart-empty">{{ emptyText }}</p>
    <p v-if="showEmptyChart && !hasValue" class="mini-chart-empty-note">{{ emptyText }}</p>
    <ul v-if="legendRows.length" class="mini-chart-legend" :aria-label="`${title} ${legendLabel ?? '机器图例'}`">
      <li v-for="row in legendRows" :key="row.name">
        <button
          class="mini-chart-legend-row"
          :class="{ 'is-hidden': hiddenNames.includes(row.name) }"
          type="button"
          :aria-pressed="!hiddenNames.includes(row.name)"
          :aria-label="`${hiddenNames.includes(row.name) ? '显示' : '隐藏'} ${row.name} 曲线`"
          @click="toggleSeries(row.name)"
        >
          <span class="mini-chart-legend-swatch" :style="{ backgroundColor: row.color }" aria-hidden="true"></span>
          <span class="mono">{{ row.name }}</span>
        </button>
      </li>
    </ul>
    <router-link v-if="detailTo" class="mini-chart-detail" :to="detailTo">查看详情 →</router-link>
  </figure>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, useId, watch } from "vue";
import VChart from "vue-echarts";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { LineChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { toChartValue, qualityLabel, type Point } from "../api/types";
import { seriesColor } from "../api/palette";
import type { RouteLocationRaw } from "vue-router";

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent]);

/**
 * 指标小图（一对多）。
 *
 * 小图密排：图下 HTML 图例逐行展示实例，点击仅控制本图的曲线可见性。
 * 一条线一个实例。**缺口同样是断线，绝不画成 0**（PRD 03 §5）。
 */
export interface MiniSeries {
  name: string;
  points: Point[];
  color?: string;
}

const props = defineProps<{
  title: string;
  unit?: string;
  series: MiniSeries[];
  /** 全窗口都没有一个有效值时的说明文字。 */
  emptyText?: string;
  /** Opt in for Metric only; other consumers keep their existing empty state. */
  showEmptyChart?: boolean;
  /** Used only when the response contains no bucket timestamps. */
  axisTimes?: number[];
  detailTo?: RouteLocationRaw;
  legendLabel?: string;
  /** 指标口径，展示在标题右侧的 `?` 提示里；不传则不显示入口。 */
  note?: string;
}>();

const points = computed(() => props.series[0]?.points ?? []);
const hiddenNames = ref<string[]>([]);
const noteId = `metric-note-${useId()}`;
const noteHovered = ref(false);
const noteFocused = ref(false);
const notePinned = ref(false);
const noteDismissed = ref(false);
const noteOpen = computed(() => !!props.note && !noteDismissed.value &&
  (noteHovered.value || noteFocused.value || notePinned.value));
const noteTrigger = ref<HTMLButtonElement | null>(null);
const notePanel = ref<HTMLElement | null>(null);

/**
 * 悬停/聚焦显示浮层，点击可固定显示（触屏也可用）。
 * Esc/外部点击后抑制当前悬停和焦点，直到用户重新进入或点击。
 */
function onNoteEnter() { noteHovered.value = true; noteDismissed.value = false; }
function onNoteLeave() { noteHovered.value = false; }
function onNoteFocus() { noteFocused.value = true; noteDismissed.value = false; }
function onNoteBlur() { noteFocused.value = false; notePinned.value = false; }
function dismissNote() { notePinned.value = false; noteDismissed.value = true; }
function toggleNote() {
  if (notePinned.value) dismissNote();
  else { notePinned.value = true; noteDismissed.value = false; }
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key !== "Escape" || !noteOpen.value) return;
  dismissNote();
  // 浮层本身不接收焦点，关闭不移动焦点，避免多张悬停/固定浮层抢焦点。
}

function onDocumentPointer(event: PointerEvent) {
  if (!noteOpen.value) return;
  const target = event.target as Node;
  if (noteTrigger.value?.contains(target) || notePanel.value?.contains(target)) return;
  dismissNote();
}

onMounted(() => {
  document.addEventListener("keydown", onDocumentKeydown);
  document.addEventListener("pointerdown", onDocumentPointer);
});
onUnmounted(() => {
  document.removeEventListener("keydown", onDocumentKeydown);
  document.removeEventListener("pointerdown", onDocumentPointer);
});
// 先为完整列表取色，再筛可见线，避免隐藏一条线后其余实例变色。
const legendRows = computed(() => props.series.map((s, index) => ({
  ...s,
  color: s.color ?? seriesColor(index),
})));
const visibleSeries = computed(() => legendRows.value.filter((s) => !hiddenNames.value.includes(s.name)));

function toggleSeries(name: string) {
  hiddenNames.value = hiddenNames.value.includes(name)
    ? hiddenNames.value.filter((hidden) => hidden !== name)
    : [...hiddenNames.value, name];
}

// 已移出实例筛选的机器不留隐藏状态；重新加入时默认显示。
watch(() => props.series.map((s) => s.name), (names) => {
  hiddenNames.value = hiddenNames.value.filter((name) => names.includes(name));
});

// 换指标/换图后不保留上一张图的展开态，否则新图的说明会以“已展开”出现。
watch(() => [props.title, props.note], () => {
  noteHovered.value = false;
  noteFocused.value = false;
  dismissNote();
});

/**
 * 有桶、但每个桶都是缺口（NO_DATA），和「当前范围内没有数据」是两回事：
 * 前者说明该指标在该实例上不存在（例如未设上限的元空间），必须给出说明，
 * 而不是画一张没有曲线的空图让人以为图坏了。
 */
const hasValue = computed(() => props.series.some((s) => s.points.some((p) =>
  props.showEmptyChart ? toChartValue(p) !== null : p.value !== null)));
const showChart = computed(() => !!props.showEmptyChart || hasValue.value);

const emptyText = computed(
  () => props.emptyText ?? "当前范围内没有上报数据"
);

function formatTime(ms: number): string {
  return new Date(ms).toLocaleString("zh-CN", { hour12: false, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

const option = computed(() => ({
  grid: { left: 52, right: 12, top: 12, bottom: 28 },
  tooltip: {
    trigger: "axis",
    confine: true,
    formatter: (params: { seriesName: string; dataIndex: number }[]) =>
      params
        .map((p) => {
          const source = props.series.find((s) => s.name === p.seriesName);
          const point = source?.points[p.dataIndex];
          if (!point) return p.seriesName;
          const label = qualityLabel(point);
          const value = point.value === null ? "缺口" : formatValue(point.value);
          return `${p.seriesName}<br/>${formatTime(point.bucketStart)}<br/>${value}${label ? ` (${label})` : ""}`;
        })
        .join("<br/><br/>"),
  },
  xAxis: {
    type: "category",
    data: (points.value.length ? points.value.map((p) => p.bucketStart) : props.axisTimes ?? []).map(formatTime),
    axisLabel: { fontSize: 10, hideOverlap: true },
  },
  yAxis: {
    type: "value",
    scale: true,
    // ECharts has no extent for an all-null series. This is axis scale, not data.
    ...(props.showEmptyChart && !hasValue.value ? { min: 0, max: 1, interval: 0.25, axisLine: { show: true } } : {}),
    axisLabel: { fontSize: 10, formatter: (value: number) => formatAxis(value) },
  },
  series: visibleSeries.value.map((s) => ({
    name: s.name,
    type: "line",
    // 缺口保持 null，使折线断开而不是落到 0（PRD 03 §5）
    data: s.points.map(toChartValue),
    connectNulls: false,
    symbol: "circle",
    symbolSize: 4,
    showSymbol: true,
    itemStyle: { color: s.color },
    lineStyle: { width: 2, color: s.color, type: "solid" },
  })),
}));

/** 字节这类大数值用紧凑写法，否则纵轴标签会互相挤掉。 */
function formatAxis(value: number): string {
  const abs = Math.abs(value);
  if (abs >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(1)}G`;
  if (abs >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (abs >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
  return String(value);
}

function formatValue(value: number): string {
  return value.toLocaleString("zh-CN");
}
</script>
