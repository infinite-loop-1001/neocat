<template>
  <section class="workspace-panel service-report-panel">
    <header class="panel-head">
      <h2>{{ title }} · Type</h2>
      <TimeScope :bucket-seconds="bucketSeconds" @change="onRange" />
    </header>

    <p class="hint">{{ hint }}</p>

    <table class="data-table">
      <thead>
        <tr>
          <th>Type</th>
          <th class="num">Total</th>
          <th class="num">Fail</th>
          <th class="num">Failure Rate</th>
          <template v-if="showsDuration">
            <th class="num">Min</th>
            <th class="num">Max</th>
            <th class="num">Avg</th>
            <th class="num">TP90</th>
            <th class="num">TP95</th>
            <th class="num">TP99</th>
            <th class="num">TP999</th>
          </template>
          <th class="num">QPS</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in rows"
          :key="row.type"
          class="clickable-row"
          role="link"
          tabindex="0"
          :aria-label="`查看 ${row.type}`"
          @click="open(row.type)"
          @keydown.enter="open(row.type)"
        >
          <td class="strong">{{ row.type }}</td>
          <td class="num">{{ row.total }}</td>
          <td class="num">{{ row.failures }}</td>
          <td class="num">{{ percent(row.failureRate) }}</td>
          <template v-if="showsDuration">
            <td class="num">{{ row.min ?? "—" }}</td>
            <td class="num">{{ row.max ?? "—" }}</td>
            <td class="num">{{ row.avg ?? "—" }}</td>
            <td class="num">{{ row.tp90 ?? "—" }}</td>
            <td class="num">{{ row.tp95 ?? "—" }}</td>
            <td class="num">{{ row.tp99 ?? "—" }}</td>
            <td class="num">{{ row.tp999 ?? "—" }}</td>
          </template>
          <td class="num">{{ row.qps?.toFixed(2) ?? "—" }}</td>
        </tr>
        <tr v-if="!rows.length">
          <td :colspan="showsDuration ? 12 : 5" class="empty">当前范围内没有数据</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api";
import TimeScope from "../components/TimeScope.vue";
import { currentHourScope } from "../api/time-range";

const props = defineProps<{ kind: "TRANSACTION" | "EVENT" }>();
const route = useRoute();
const router = useRouter();

interface Row {
  type: string;
  total: number;
  failures: number;
  failureRate: number;
  min?: number;
  max?: number;
  avg?: number;
  tp90?: number | null;
  tp95?: number | null;
  tp99?: number | null;
  tp999?: number | null;
  qps: number;
}

const rows = ref<Row[]>([]);
const bucketSeconds = ref(60);
// 默认窗口是当前整点小时，与 TimeScope 的默认一致（见 api/time-range）
const range = ref(currentHourScope().range);

const service = computed(() => route.params.service as string);
const title = computed(() => (props.kind === "TRANSACTION" ? "Transaction" : "Event"));

/** Event 不提供耗时与分位（PRD 03 §8）。 */
const showsDuration = computed(() => props.kind === "TRANSACTION");
const hint = computed(() =>
  props.kind === "TRANSACTION"
    ? "点击 Type 进入该 Type 下的 Name 列表。Type 只做分类汇总，不直接进入趋势。"
    : "Event 只提供次数类指标与 QPS，不提供耗时、最小/最大/平均耗时、分布与百分位。"
);

function percent(value: number | null): string {
  return value === null || value === undefined ? "—" : `${(value * 100).toFixed(2)}%`;
}

function onRange(payload: { range: string; bucketSeconds: number }) {
  range.value = payload.range;
  bucketSeconds.value = payload.bucketSeconds;
  void load();
}

async function load() {
  const path = props.kind === "TRANSACTION" ? "/reports/transaction/types" : "/reports/event/types";
  rows.value = await api<Row[]>(path, { query: { service: service.value, range: range.value } });
}

async function open(type: string) {
  const segment = props.kind === "TRANSACTION" ? "transaction" : "event";
  await router.push(`/svc/${service.value}/${segment}/${encodeURIComponent(type)}`);
}

onMounted(load);
watch(() => route.params.service, load);
</script>
