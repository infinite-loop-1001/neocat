<template>
  <section class="workspace-panel service-report-panel">
    <header class="panel-head">
      <h2>{{ title }}</h2>
      <router-link class="btn btn-ghost" :to="backTo">← 返回 {{ backLabel }}</router-link>
    </header>

    <div class="series-meta">
      <span class="mono">{{ service }} · {{ displayType }} · {{ name }}</span>
    </div>

    <div class="series-layout">
      <div class="series-main">
        <ReportChart :series="chartSeries" :caption="caption" />

        <MachinePicker v-model="selectedInstances" :machines="machines" />

        <section class="samples">
          <header class="panel-head">
            <h3>调用取样</h3>
            <span class="status">最近 {{ samples.length }} 条 · 按事件时间倒序</span>
          </header>
          <table class="data-table">
            <thead>
              <tr>
                <th>事件时间</th>
                <th>摘要</th>
                <th class="num">耗时</th>
                <th>状态</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="sample in samples" :key="sample.messageId">
                <td class="mono">{{ formatTime(sample.timestamp) }}</td>
                <td>{{ sample.summary }}</td>
                <td class="num">{{ sample.durationMs }} ms</td>
                <td>
                  <span class="status" :class="sample.status === '0' ? 'success' : 'danger'">
                    {{ sample.status === "0" ? "成功" : "失败" }}
                  </span>
                </td>
                <td>
                  <button
                    v-if="sample.traceAvailable"
                    class="btn btn-ghost"
                    type="button"
                    @click="openTrace(sample.messageId)"
                  >
                    查看 Trace
                  </button>
                  <span v-else class="status warn">原始树已过期</span>
                </td>
              </tr>
              <tr v-if="!samples.length">
                <td colspan="5" class="empty">当前范围内没有取样</td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>

      <aside class="series-filters" aria-label="筛选项">
        <div>
          <span id="comparison-label" class="field-label">环比</span>
          <div class="comparison-options" role="group" aria-labelledby="comparison-label">
            <label
              v-for="option in MOM_OPTIONS"
              :key="option.value"
              class="comparison-option"
              :title="option.title"
            >
              <input
                type="checkbox"
                :checked="selectedMom.includes(option.value)"
                @change="toggleMom(option.value)"
              />
              {{ option.label }}
            </label>
          </div>
        </div>

        <div>
          <TimeScope :bucket-seconds="bucketSeconds" @change="onRange" />
        </div>

        <div>
          <label class="field-label" for="stat">统计项</label>
          <select id="stat" v-model="stat" class="select-field" @change="load">
            <option value="HITS">Hits（桶内总次数）</option>
            <option value="FAILURES">Failures</option>
            <option value="FAILURE_RATE">Failure Rate</option>
            <option value="QPS">QPS</option>
            <template v-if="showsDuration">
              <option value="AVG">Avg Duration</option>
              <option value="MIN">Min</option>
              <option value="MAX">Max</option>
              <option value="TP50">tp50</option>
              <option value="TP90">tp90</option>
              <option value="TP95">tp95</option>
              <option value="TP99">tp99</option>
              <option value="TP999">tp999</option>
              <option value="TP9999">tp9999</option>
            </template>
          </select>
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api";
import type { ComparisonSeries, MomKind, Point } from "../api/types";
import { comparisonChartSeries } from "../api/comparison";
import { problemLabel, problemSupportsDuration } from "../api/problem";
import ReportChart from "../components/ReportChart.vue";
import MachinePicker, { type MachineOption } from "../components/MachinePicker.vue";
import TimeScope from "../components/TimeScope.vue";
import { currentHourScope } from "../api/time-range";

/** 复用同一个趋势页：三条报表链路的差异全部由 kind + type 决定。 */
type SeriesKind = "TRANSACTION" | "EVENT" | "PROBLEM";

const props = defineProps<{ kind: SeriesKind }>();
const route = useRoute();
const router = useRouter();

interface Sample {
  messageId: string;
  timestamp: number;
  durationMs: number;
  status: string;
  summary: string;
  traceAvailable: boolean;
}

interface SeriesResponse {
  points: Point[];
  bucketSeconds: number;
  mom?: ComparisonSeries | null;
}

const service = computed(() => route.params.service as string);
const type = computed(() => route.params.type as string);
const name = computed(() => route.params.name as string);
const segment = computed(() => (props.kind === "TRANSACTION" ? "transaction" : props.kind === "EVENT" ? "event" : "problem"));
/** 慢类支持耗时与分位，异常不支持（PRD 03 §9）。 */
const showsDuration = computed(() => props.kind === "TRANSACTION" || (props.kind === "PROBLEM" && problemSupportsDuration(type.value)));
const title = computed(() =>
  props.kind === "TRANSACTION" ? "Transaction" : props.kind === "EVENT" ? "Event" : "Problem"
);
const displayType = computed(() => (props.kind === "PROBLEM" ? problemLabel(type.value) : type.value));
const backTo = computed(() =>
  props.kind === "PROBLEM"
    ? `/svc/${service.value}/problem`
    : `/svc/${service.value}/${segment.value}/${encodeURIComponent(type.value)}`
);
const backLabel = computed(() => (props.kind === "PROBLEM" ? "Problem" : "Name 列表"));

const stat = ref("HITS");
const selectedMom = ref<MomKind[]>([]);
const range = ref(currentHourScope().range);
const bucketSeconds = ref(600);
const points = ref<Point[]>([]);
const comparisons = ref<ComparisonSeries[]>([]);
const samples = ref<Sample[]>([]);
const selectedInstances = ref<string[]>([]);
const machines = ref<MachineOption[]>([]);

/**
 * 环比基准（PRD 03 §6）。
 *
 * `value` 与后端 `mom` 参数一致；全部不勾选表示不对比。
 * 语义是「同一时刻整体前移 N 天」，不是「上一个自然周期」——
 * 月环比是 30 天前同时段，不是上一个自然月。
 */
const MOM_OPTIONS = [
  { value: "DAY", label: "日环比", title: "1 天前同时段（整日偏移、桶序号对齐）" },
  { value: "WEEK", label: "周环比", title: "7 天前同时段" },
  { value: "MONTH", label: "月环比", title: "30 天前同时段（不是上一个自然月）" },
] as const;

function toggleMom(value: MomKind) {
  const next = selectedMom.value.includes(value)
    ? selectedMom.value.filter((kind) => kind !== value)
    : [...selectedMom.value, value];
  // 固定日、周、月顺序，不依赖勾选先后。
  selectedMom.value = MOM_OPTIONS.map((option) => option.value).filter((kind) => next.includes(kind));
  return load();
}

const caption = computed(() =>
  selectedMom.value.length
    ? "与同时段环比（按整日偏移、桶序号对齐）"
    : stat.value === "HITS"
      ? "默认显示桶内 Hits 总次数（不是 count/min，也不是 QPS）"
      : undefined
);

/** Event 请求分位时前端直接隐藏选项，双重保证不越界。 */
const chartSeries = computed(() => comparisonChartSeries(
  stat.value,
  points.value,
  comparisons.value.filter((comparison) => selectedMom.value.includes(comparison.kind)),
  bucketSeconds.value
));

function formatTime(ms: number): string {
  return new Date(ms).toLocaleString("zh-CN", { hour12: false });
}

function onRange(payload: { range: string; bucketSeconds: number }) {
  range.value = payload.range;
  bucketSeconds.value = payload.bucketSeconds;
  void load();
}

let loadVersion = 0;

async function load() {
  const version = ++loadVersion;
  // 所有环比请求共享参数快照，快速改筛选时旧批次不能覆盖新批次。
  const query = {
    service: service.value,
    kind: props.kind,
    type: type.value,
    name: name.value,
    stat: stat.value,
    range: range.value,
    bucket: bucketSeconds.value,
    instances: selectedInstances.value.join(",") || undefined,
  };
  const modes: (MomKind | undefined)[] = selectedMom.value.length ? [...selectedMom.value] : [undefined];
  const [responses, nextMachines, nextSamples] = await Promise.all([
    Promise.all(modes.map((mom) => api<SeriesResponse>("/reports/series", {
      query: { ...query, mom },
    }))),
    loadMachines(query.service),
    api<Sample[]>("/reports/samples", {
      query: {
        kind: query.kind,
        service: query.service,
        type: query.type,
        name: query.name,
        range: query.range,
      },
    }),
  ]);
  if (version !== loadVersion) return;
  points.value = responses[0].points;
  comparisons.value = responses.flatMap((response, index) =>
    response.mom && response.mom.kind === modes[index] ? [response.mom] : []
  );
  bucketSeconds.value = responses[0].bucketSeconds;
  machines.value = nextMachines;
  samples.value = nextSamples;
}

/** 服务实例接口只返回实例名，不包含每台机器的统计数值。 */
async function loadMachines(serviceName: string): Promise<MachineOption[]> {
  const instances = await api<string[]>("/services/" + serviceName + "/instances");
  return instances.map((instance) => ({ instance }));
}

async function openTrace(messageId: string) {
  await router.push(`/trace/${messageId}`);
}

onMounted(load);
watch(() => [route.params.service, route.params.type, route.params.name], load);
watch(selectedInstances, load);
</script>
