<template>
  <section class="workspace-panel service-report-panel">
    <header class="panel-head">
      <h2>Heartbeat（JVM）</h2>
      <TimeScope :bucket-seconds="600" @change="onRange" />
    </header>

    <p class="hint">
      一期只覆盖 JVM：按实例分别展示，<strong>不合并不同 JVM 的值</strong>
      （堆、线程相加没有意义），Top N 之外也不合并为 other。一期不做环比。
    </p>

    <MachinePicker
      v-model="selectedInstances"
      :machines="machines"
      hint="勾选后只展示选中的 JVM 实例；不同实例的值不会被相加。"
    />

    <section class="heartbeat-group">
      <h3 class="heartbeat-group-title">内存信息</h3>
      <section
        v-for="group in memoryGroups"
        :key="group.key"
        class="heartbeat-partition"
      >
        <h4 class="heartbeat-partition-title">{{ group.label }}</h4>
        <div class="mini-chart-grid">
          <MiniChart
            v-for="item in group.metrics"
            :key="item.metric"
            :title="item.name"
            :unit="item.unit"
            :note="item.note"
            :empty-text="item.emptyNote"
            :series="seriesFor(item.metric)"
          />
        </div>
      </section>
    </section>

    <section v-for="group in otherGroups" :key="group.key" class="heartbeat-group">
      <h3 class="heartbeat-group-title">{{ group.label }}</h3>
      <div class="mini-chart-grid">
        <MiniChart
          v-for="item in group.metrics"
          :key="item.metric"
          :title="item.name"
          :unit="item.unit"
          :note="item.note"
          :series="seriesFor(item.metric)"
        />
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api";
import type { Point } from "../api/types";
import { HEARTBEAT_GROUPS } from "../api/heartbeat";
import { seriesColor } from "../api/palette";
import MachinePicker, { type MachineOption } from "../components/MachinePicker.vue";
import MiniChart from "../components/MiniChart.vue";
import TimeScope from "../components/TimeScope.vue";
import { currentHourScope } from "../api/time-range";

const route = useRoute();
const service = computed(() => route.params.service as string);

interface SeriesResponse {
  series: { instance: string; points: Point[] }[];
}

const range = ref(currentHourScope().range);
const bucketSeconds = ref(600);
const machines = ref<MachineOption[]>([]);
const selectedInstances = ref<string[]>([]);
/** metric → 每个实例一条序列。 */
const byMetric = ref<Record<string, { instance: string; points: Point[] }[]>>({});

/**
 * 实例顺序取自实例列表（而不是某个指标的返回顺序），
 * 保证同一实例在所有小图里颜色一致。
 */
const order = computed(() => machines.value.map((m) => m.instance));

/** 内存分区（含堆总览）与其余分组分开渲染：内存用二级标题，口径写在每张图的 ? 提示里。 */
const memoryGroups = computed(() => HEARTBEAT_GROUPS.filter((g) => g.kind === "memory"));
const otherGroups = computed(() => HEARTBEAT_GROUPS.filter((g) => g.kind !== "memory"));

function seriesFor(metric: string) {
  return (byMetric.value[metric] ?? []).map((s) => ({
    name: s.instance,
    points: s.points,
    color: seriesColor(Math.max(0, order.value.indexOf(s.instance))),
  }));
}

function onRange(payload: { range: string; bucketSeconds: number }) {
  range.value = payload.range;
  bucketSeconds.value = payload.bucketSeconds;
  void load();
}

async function load() {
  // 实例列表用 Heartbeat 自己的接口：只列出真正上报过 Heartbeat 的 JVM 实例，
  // 避免把非 JVM 实例也列进选择器。
  const instances = await api<{ instance: string; value: number }[]>("/reports/heartbeat/instances", {
    query: { service: service.value, metric: "heap-used", range: range.value },
  });
  // 后端把「窗口内各桶求和」作为实例的参考值。对 gauge（堆、线程）这个数字
  // 没有意义（堆已用按桶相加会得到数十亿字节），所以只取实例名，不展示数值。
  machines.value = instances.map((row) => ({ instance: row.instance }));

  const metrics = HEARTBEAT_GROUPS.flatMap((g) => g.metrics.map((m) => m.metric));
  const responses = await Promise.all(
    metrics.map((metric) =>
      api<SeriesResponse>("/reports/heartbeat/series", {
        query: {
          service: service.value,
          metric,
          range: range.value,
          instances: selectedInstances.value.join(",") || undefined,
        },
      })
    )
  );
  byMetric.value = Object.fromEntries(
    metrics.map((metric, index) => [metric, responses[index].series ?? []])
  );
}

onMounted(load);
watch(service, load);
watch(selectedInstances, load);
</script>
