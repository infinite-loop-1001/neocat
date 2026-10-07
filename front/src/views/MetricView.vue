<template>
  <section class="workspace-panel metric-panel">
    <header class="panel-head">
      <h2>{{ metric || 'Metric' }}</h2>
      <div class="panel-head-tools">
        <router-link v-if="metric" :to="overviewTo">返回 Metric</router-link>
        <TimeScope :key="`${service}:${metric}:${range}`" :initial-range="range" @change="onRange" />
      </div>
    </header>

    <template v-if="metric">
      <section class="metric-filters" aria-label="标签筛选">
        <div class="metric-filter-head">
          <strong>标签筛选</strong>
          <span class="muted small">同标签多值 OR，不同标签 AND</span>
          <button type="button" class="button" :disabled="!hasFilters" @click="clearFilters">清空标签</button>
        </div>
        <label class="metric-search">
          <span class="field-label">搜索标签</span>
          <input v-model="search" class="field" type="search" placeholder="标签名或标签值" />
        </label>
        <div class="metric-filter-options">
          <fieldset v-for="label in visibleLabels" :key="label.key" class="metric-label-group">
            <legend class="mono">{{ label.key }}</legend>
            <label v-for="value in label.values" :key="value" class="metric-label-value">
              <input type="checkbox" :checked="selectedValues(label.key).includes(value)"
                @change="toggleLabel(label.key, value)" />
              <span>{{ value }}</span>
            </label>
          </fieldset>
          <span v-if="!loading && !visibleLabels.length" class="muted small">没有匹配的标签</span>
        </div>
        <p class="metric-selection">当前曲线：<strong>{{ selectionCaption }}</strong><span class="muted"> · count（上报次数）</span></p>
      </section>
      <p v-if="loading" role="status" class="empty">加载中…</p>
      <div v-else-if="error" role="alert" class="metric-error">{{ error }} <button type="button" class="button" @click="load">重试</button></div>
      <template v-else>
        <ReportChart :series="chartSeries" :axis-times="axisTimes" :caption="`${selectionCaption} · count（上报次数）`" />
        <p v-if="!hasValue || hasMergedOther" class="chart-note" role="status">{{ emptyMessage }}</p>
      </template>
    </template>

    <template v-else>
      <p v-if="loading" role="status" class="empty">加载中…</p>
      <div v-else-if="error" role="alert" class="metric-error">{{ error }} <button type="button" class="button" @click="load">重试</button></div>
      <p v-else-if="!cards.length" class="empty">当前时间范围没有 Metric</p>
      <div v-else class="mini-chart-grid metric-chart-grid">
        <div v-for="card in cards" :key="card.name" class="metric-card">
          <MiniChart :title="card.name" unit="count" :series="card.error ? [] : [{ name: '总量', points: card.points }]"
            :detail-to="detailTo(card.name)" legend-label="总量图例"
            :show-empty-chart="!card.error" :axis-times="axisTimes"
            :empty-text="card.error || '当前范围没有 count 数据'" />
          <button v-if="card.error" type="button" class="button" @click="load">重试加载</button>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { decodeFilters, filterCaption, loadMetricCatalog, loadMetricCount, loadMetricLabels, normalizeFilters,
  type MetricFilters, type MetricLabel } from "../api/metric";
import { initialTimeSelection } from "../api/time-range";
import { emptyChartAxis } from "../api/chart-axis";
import { normalizeMetricMockScenario } from "../api/metric-scenario";
import { toChartValue, type Point } from "../api/types";
import MiniChart from "../components/MiniChart.vue";
import ReportChart from "../components/ReportChart.vue";
import TimeScope from "../components/TimeScope.vue";

const route = useRoute();
const router = useRouter();
const service = computed(() => String(route.params.service ?? ""));
const metric = computed(() => String(route.params.metric ?? ""));
const range = ref(initialTimeSelection(route.query.range).range);
const filters = ref<MetricFilters>(metric.value ? decodeFilters(route.query.filters) : {});
const labels = ref<MetricLabel[]>([]);
const search = ref("");
const cards = ref<{ name: string; points: Point[]; error?: string }[]>([]);
const points = ref<Point[]>([]);
const loading = ref(false);
const error = ref("");
const axisTimes = ref<number[]>([]);
const mockScenario = computed(() => normalizeMetricMockScenario(route.query.mockMetric));
const navigationQuery = computed(() => ({ range: range.value,
  ...(mockScenario.value ? { mockMetric: mockScenario.value } : {}) }));
const hasFilters = computed(() => Object.keys(normalizeFilters(filters.value)).length > 0);
const selectionCaption = computed(() => filterCaption(filters.value));
const hasValue = computed(() => points.value.some((point) => toChartValue(point) !== null));
const hasMergedOther = computed(() => points.value.some((point) => point.quality === "MERGED_OTHER"));
const emptyMessage = computed(() => hasMergedOther.value
  ? "当前条件的部分数据已合入 other，无法还原标签 count；保持缺口，不显示为 0。"
  : "当前条件下没有可展示的数据");
const chartSeries = computed(() => [{ name: selectionCaption.value, points: points.value, color: "#315fcd" }]);
const visibleLabels = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  return labels.value.map((label) => ({ ...label, values: label.key.toLowerCase().includes(keyword)
    ? label.values : label.values.filter((value) => value.toLowerCase().includes(keyword)) }))
    .filter((label) => label.values.length);
});
const overviewTo = computed(() => ({ path: `/svc/${encodeURIComponent(service.value)}/metric`, query: navigationQuery.value }));

function detailTo(name: string) {
  return { path: `/svc/${encodeURIComponent(service.value)}/metric/${encodeURIComponent(name)}`, query: navigationQuery.value };
}

function onRange(payload: { range: string }) {
  if (payload.range === route.query.range) return load();
  invalidate();
  return router.replace({ path: route.path, query: { ...route.query, range: payload.range } });
}

function setFilters(next: MetricFilters) {
  const normalized = normalizeFilters(next);
  // Rapid checkbox clicks build on the latest selection, not the last route update.
  filters.value = normalized;
  const encoded = Object.keys(normalized).length ? JSON.stringify(normalized) : undefined;
  if (encoded === route.query.filters && range.value === route.query.range) return load();
  invalidate();
  return router.replace({ path: route.path, query: { ...route.query, range: range.value,
    filters: encoded } });
}

function toggleLabel(key: string, value: string) {
  const values = selectedValues(key);
  return setFilters({ ...filters.value, [key]: values.includes(value) ? values.filter((item) => item !== value) : [...values, value] });
}

function selectedValues(key: string): string[] {
  return Object.hasOwn(filters.value, key) ? filters.value[key] : [];
}

function clearFilters() { return setFilters({}); }

function errorMessage(reason: unknown): string {
  return reason && typeof reason === "object" && "message" in reason && typeof reason.message === "string"
    ? reason.message : "Metric 数据加载失败，请重试";
}

let loadVersion = 0;
let labelsFor = "";
function invalidate() {
  loadVersion++;
  points.value = [];
  loading.value = true;
}
async function load() {
  const version = ++loadVersion;
  const now = Date.now();
  range.value = initialTimeSelection(route.query.range, now).range;
  axisTimes.value = emptyChartAxis(range.value, now);
  filters.value = metric.value ? decodeFilters(route.query.filters) : {};
  const query = { service: service.value, metric: metric.value, range: range.value,
    filters: normalizeFilters(filters.value), mockMetric: mockScenario.value };
  loading.value = true;
  error.value = "";
  points.value = [];
  cards.value = [];
  const labelIdentity = `${query.service}:${query.metric}`;
  if (labelIdentity !== labelsFor) labels.value = [];
  try {
    if (query.metric) {
      const [nextLabels, response] = await Promise.all([
        loadMetricLabels(query.service, query.metric, query.range),
        loadMetricCount(query.service, query.metric, query.range, query.filters, query.mockMetric),
      ]);
      if (version !== loadVersion) return;
      labels.value = nextLabels;
      labelsFor = labelIdentity;
      points.value = response.points;
    } else {
      const metrics = await loadMetricCatalog(query.service, query.range);
      if (version !== loadVersion) return;
      const nextCards = await Promise.all(metrics.map(async ({ name }) => {
        try {
          const response = await loadMetricCount(query.service, name, query.range, undefined, query.mockMetric);
          return { name, points: response.points };
        } catch (reason) { return { name, points: [], error: errorMessage(reason) }; }
      }));
      if (version !== loadVersion) return;
      cards.value = nextCards;
    }
  } catch (reason) {
    if (version === loadVersion) error.value = errorMessage(reason);
  } finally {
    if (version === loadVersion) loading.value = false;
  }
}

onMounted(load);
onUnmounted(() => { loadVersion++; });
watch(() => [route.params.service, route.params.metric, route.query.range, route.query.filters, route.query.mockMetric], load);
watch(metric, () => { search.value = ""; });
</script>
