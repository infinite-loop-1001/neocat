<template>
  <section class="workspace-panel service-report-panel">
    <header class="panel-head">
      <h2>{{ kind === "TRANSACTION" ? "Transaction" : "Event" }} · {{ type }} · Name</h2>
      <div class="panel-head-tools">
        <TimeScope :bucket-seconds="600" @change="onRange" />
        <router-link class="btn btn-ghost" :to="`/svc/${service}/${segment}`">← 返回 Type</router-link>
      </div>
    </header>

    <p class="hint">{{ hint }}</p>

    <table class="data-table">
      <thead>
        <tr>
          <th>Name</th>
          <th class="num">Total</th>
          <th class="num">Fail</th>
          <th class="num">Avg</th>
          <template v-if="showsDuration">
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
          :key="row.name"
          class="clickable-row"
          role="link"
          tabindex="0"
          :aria-label="`查看 ${row.name} 趋势`"
          @click="open(row.name)"
          @keydown.enter="open(row.name)"
        >
          <td class="strong">{{ row.name }}</td>
          <td class="num">{{ row.total }}</td>
          <td class="num">{{ row.failures }}</td>
          <td class="num">{{ showsDuration ? row.avg ?? "—" : "—" }}</td>
          <template v-if="showsDuration">
            <td class="num">{{ row.tp90 ?? "—" }}</td>
            <td class="num">{{ row.tp95 ?? "—" }}</td>
            <td class="num">{{ row.tp99 ?? "—" }}</td>
            <td class="num">{{ row.tp999 ?? "—" }}</td>
          </template>
          <td class="num">{{ row.qps?.toFixed(2) ?? "—" }}</td>
        </tr>
        <tr v-if="!rows.length">
          <td :colspan="showsDuration ? 9 : 5" class="empty">该 Type 在当前范围内没有数据</td>
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
  name: string;
  total: number;
  failures: number;
  avg: number;
  tp90: number | null;
  tp95: number | null;
  tp99: number | null;
  tp999: number | null;
  qps: number;
}

const rows = ref<Row[]>([]);
// 默认窗口是当前整点小时，与 TimeScope 的默认一致（见 api/time-range）
const range = ref(currentHourScope().range);
const service = computed(() => route.params.service as string);
const type = computed(() => route.params.type as string);
const segment = computed(() => (props.kind === "TRANSACTION" ? "transaction" : "event"));
const showsDuration = computed(() => props.kind === "TRANSACTION");
const hint = computed(() =>
  props.kind === "TRANSACTION"
    ? "点击 Name 进入趋势详情：默认全部机器聚合、默认显示 Hits 总次数。"
    : "Event 无耗时与分位；点击 Name 查看 Hits/QPS 趋势。"
);

function onRange(payload: { range: string }) {
  range.value = payload.range;
  void load();
}

async function load() {
  const path = props.kind === "TRANSACTION" ? "/reports/transaction/names" : "/reports/event/names";
  rows.value = await api<Row[]>(path, {
    query: { service: service.value, type: type.value, range: range.value },
  });
}

async function open(name: string) {
  await router.push(`/svc/${service.value}/${segment.value}/${encodeURIComponent(type.value)}/${encodeURIComponent(name)}`);
}

onMounted(load);
watch(() => [route.params.service, route.params.type], load);
</script>
