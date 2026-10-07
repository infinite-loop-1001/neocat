<template>
  <figure class="chart">
    <figcaption v-if="caption" class="chart-caption">{{ caption }}</figcaption>
    <v-chart v-if="series.length" class="chart-canvas" :option="option" :update-options="{ notMerge: true }" autoresize />
    <p v-else class="empty">没有可展示的数据</p>
    <p class="chart-note">缺口以断线表示。</p>
  </figure>
</template>

<script setup lang="ts">
import { computed } from "vue";
import VChart from "vue-echarts";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent, MarkLineComponent } from "echarts/components";
import { toChartValue, type Point } from "../api/types";

use([CanvasRenderer, LineChart, GridComponent, LegendComponent, TooltipComponent, MarkLineComponent]);

export interface ChartSeries {
  name: string;
  points: Point[];
  color?: string;
  lineType?: "solid" | "dashed";
  /** 多曲线提示的区分前缀（如「日环比」）；不传则该行只有数值。 */
  tooltipLabel?: string;
}

const props = defineProps<{
  series: ChartSeries[];
  caption?: string;
  /** Empty-response axis only; never creates data points. */
  axisTimes?: number[];
  thresholdLines?: { direction: string; value: number }[];
}>();

function formatTime(ms: number): string {
  return new Date(ms).toLocaleString("zh-CN", { hour12: false });
}

/** 顶部时间使用当前横轴的桶起点，格式不依赖 locale 的分隔符。 */
function formatTooltipTime(ms: number): string {
  const date = new Date(ms);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${String(date.getFullYear()).padStart(4, "0")}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

const option = computed(() => ({
  grid: { left: 56, right: 24, top: props.thresholdLines?.length || props.series.length > 1 ? 40 : 24, bottom: 44 },
  tooltip: {
    trigger: "axis",
    formatter: (params: { seriesName: string; dataIndex: number }[]) => {
      if (!params.length) return "";
      // params 可能只含历史线（当前线被图例隐藏），不能拿历史桶日期作标题。
      const index = params[0].dataIndex;
      const axisTime = props.series[0]?.points[index]?.bucketStart ?? props.axisTimes?.[index];
      const rows = params.map((p) => {
        const source = props.series.find((s) => s.name === p.seriesName);
        const point = source?.points[p.dataIndex];
        const prefix = source?.tooltipLabel ? `${source.tooltipLabel}：` : "";
        const valueText = point && point.value !== null ? String(point.value) : "缺口";
        // 统计项名与质量说明不进提示：图例已给上下文，缺口语义由断线表达。
        return `${prefix}${valueText}`;
      });
      if (axisTime !== undefined) rows.unshift(formatTooltipTime(axisTime));
      return rows.join("<br/>");
    },
  },
  legend: props.series.length > 1
    ? { type: "scroll", left: 56, right: 24, itemGap: 12, textStyle: { fontSize: 11 } }
    : undefined,
  xAxis: {
    type: "category",
    data: (props.series[0]?.points.length
      ? props.series[0].points.map((p) => p.bucketStart) : props.axisTimes ?? []).map(formatTime),
    axisLabel: { fontSize: 11, hideOverlap: true },
  },
  yAxis: { type: "value", scale: true, axisLabel: { fontSize: 11 },
    ...(props.axisTimes && !props.series.some((s) => s.points.some((p) => toChartValue(p) !== null))
      ? { min: 0, max: 1, interval: 0.25, axisLine: { show: true } } : {}) },
  series: props.series.map((s) => ({
    name: s.name,
    type: "line",
    // 缺口保持 null，使折线断开而不是落到 0（PRD 03 §5）
    data: s.points.map(toChartValue),
    connectNulls: false,
    symbol: "circle",
    symbolSize: 4,
    showSymbol: true,
    itemStyle: { color: s.color },
    lineStyle: { width: 2, color: s.color, type: s.lineType ?? "solid" },
    markLine: props.thresholdLines?.length
      ? {
          silent: true,
          symbol: "none",
          data: props.thresholdLines.map((line) => ({ yAxis: line.value, name: line.direction })),
        }
      : undefined,
  })),
}));
</script>
