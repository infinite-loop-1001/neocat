<template>
  <section class="workspace-panel service-report-panel">
    <header class="panel-head">
      <h2>依赖</h2>
      <TimeScope :bucket-seconds="600" @change="onRange" />
    </header>

    <p class="hint">
      默认布局为上下游列表（不以拓扑图作为一期主入口）。
      被调用方 MessageTree 未到达时依赖边<strong>仍计入调用次数</strong>。
    </p>

    <div class="problem-split">
      <div>
        <header class="panel-head"><h3>下游（本服务调用的服务）</h3></header>
        <table class="data-table">
          <thead>
            <tr>
              <th>下游服务</th>
              <th class="num">调用次数</th>
              <th class="num">失败率</th>
              <th class="num">平均耗时</th>
              <th class="num">tp99</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in downstream" :key="row.peer">
              <td class="mono strong">{{ row.peer }}</td>
              <td class="num">{{ row.calls }}</td>
              <td class="num">{{ (row.failureRate * 100).toFixed(2) }}%</td>
              <td class="num">{{ row.avg.toFixed(1) }}</td>
              <td class="num">{{ row.tp99 }}</td>
            </tr>
            <tr v-if="!downstream.length">
              <td colspan="5" class="empty">当前范围内没有下游依赖</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div>
        <header class="panel-head"><h3>上游（调用本服务的服务）</h3></header>
        <table class="data-table">
          <thead>
            <tr>
              <th>上游服务</th>
              <th class="num">调用次数</th>
              <th class="num">失败率</th>
              <th class="num">平均耗时</th>
              <th class="num">tp99</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in upstream" :key="row.peer">
              <td class="mono strong">{{ row.peer }}</td>
              <td class="num">{{ row.calls }}</td>
              <td class="num">{{ (row.failureRate * 100).toFixed(2) }}%</td>
              <td class="num">{{ row.avg.toFixed(1) }}</td>
              <td class="num">{{ row.tp99 }}</td>
            </tr>
            <tr v-if="!upstream.length">
              <td colspan="5" class="empty">当前范围内没有上游依赖</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api";
import TimeScope from "../components/TimeScope.vue";
import { currentHourScope } from "../api/time-range";

const route = useRoute();
const service = computed(() => route.params.service as string);

interface Row {
  peer: string;
  calls: number;
  failureRate: number;
  avg: number;
  tp99: number;
}

const downstream = ref<Row[]>([]);
const upstream = ref<Row[]>([]);
// 默认窗口是当前整点小时，与 TimeScope 的默认一致（见 api/time-range）
const range = ref(currentHourScope().range);

function onRange(payload: { range: string }) {
  range.value = payload.range;
  void load();
}

async function load() {
  downstream.value = await api<Row[]>("/reports/dependency/downstream", {
    query: { service: service.value, range: range.value },
  });
  upstream.value = await api<Row[]>("/reports/dependency/upstream", {
    query: { service: service.value, range: range.value },
  });
}

onMounted(load);
watch(service, load);
</script>
