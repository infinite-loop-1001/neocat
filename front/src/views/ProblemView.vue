<template>
  <section class="workspace-panel problem-panel">
    <header class="panel-head">
      <h2>Problem</h2>
      <TimeScope :bucket-seconds="600" @change="onRange" />
    </header>

    <div class="problem-matrix">
      <div v-for="group in groups" :key="group.category" class="problem-group">
        <div class="problem-group-title">
          <span class="problem-category">{{ group.category }}</span>
          <span class="problem-meta">
            <span class="num">{{ group.total }}</span>
            <span>hits</span>
          </span>
        </div>

        <div class="problem-group-body">
          <table class="data-table problem-table">
            <colgroup>
              <col />
              <col class="col-total" />
              <col class="col-p99" />
              <col class="col-samples" />
            </colgroup>
            <thead>
              <tr>
                <th>Name</th>
                <th class="num">Total</th>
                <th class="num">P99</th>
                <th>Samples</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in group.rows"
                :key="row.name"
                class="clickable-row"
                role="link"
                tabindex="0"
                :aria-label="`查看 ${row.name} 趋势`"
                @click="openSeries(group.rawCategory, row.name)"
                @keydown.enter="openSeries(group.rawCategory, row.name)"
              >
                <td class="mono strong">{{ row.name }}</td>
                <td class="num">{{ row.total }}</td>
                <td class="num">
                  {{ group.supportsPercentile ? row.tp99 ?? "—" : "—" }}
                </td>
                <td class="problem-samples">
                  <div v-if="row.samples.length" class="problem-log">
                    <button
                      v-for="(sample, index) in row.samples"
                      :key="sample.messageId"
                      class="log-glyph"
                      type="button"
                      :title="tooltip(sample)"
                      @click.stop="openTrace(sample.messageId)"
                    >
                      {{ glyphAt(index, row.samples.length) }}
                    </button>
                  </div>
                  <span v-else class="muted small">—</span>
                </td>
              </tr>
              <tr v-if="!group.rows.length">
                <td colspan="4" class="problem-empty">当前范围内没有数据</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { problemLabel } from "../api/problem";
import { api } from "../api";
import TimeScope from "../components/TimeScope.vue";
import { currentHourScope } from "../api/time-range";

interface Sample {
  messageId: string;
  timestamp: number;
  durationMs: number;
  status: string;
  summary: string;
  traceAvailable: boolean;
}

interface Row {
  name: string;
  total: number;
  tp99: number | null;
  samples: Sample[];
  /** 已上报的取样条数（含原始树已过期的），用于展示「本次可见 / 共上报」。 */
  sampleCount: number;
}

interface Group {
  /** 展示用英文标签，如 `long-sql`。 */
  category: string;
  /** 后端原始枚举值，如 `SLOW_SQL`，用于查阈值。 */
  rawCategory: string;
  total: number;
  supportsPercentile: boolean;
  rows: Row[];
}

const route = useRoute();
const router = useRouter();
const service = computed(() => route.params.service as string);

const groups = ref<Group[]>([]);
// 默认窗口是当前整点小时，与 TimeScope 的默认一致（见 api/time-range）
const range = ref(currentHourScope().range);

/**
 * 条带字符：`l / o / g` 是**三个时间档**，不是状态。
 *
 * 取样按事件时间从新到旧排列，因此把整条切成三个连续区段：
 * 最新一段全是 `l`，中间一段全是 `o`，最旧一段全是 `g`。
 * 这样一眼能看出「这行最旧的取样在哪一段」，同时字符不携带任何状态含义。
 */
const GLYPHS = ["l", "o", "g"] as const;

function glyphAt(index: number, total: number): string {
  if (total <= 0) return GLYPHS[0];
  const band = Math.min(GLYPHS.length - 1, Math.floor((index * GLYPHS.length) / total));
  return GLYPHS[band];
}

function tooltip(sample: Sample): string {
  const time = new Date(sample.timestamp).toLocaleString("zh-CN", { hour12: false });
  const state = sample.status === "0" ? "成功" : "失败";
  return `${time} · ${sample.durationMs} ms · ${state}`;
}

function onRange(payload: { range: string }) {
  range.value = payload.range;
  void load();
}

async function openTrace(messageId: string) {
  await router.push(`/trace/${messageId}`);
}

/** 点 Name 进入 Problem 趋势页；`:type` 用后端原始枚举，与 `/reports/series` 的 type 一致。 */
async function openSeries(category: string, name: string) {
  await router.push(
    `/svc/${service.value}/problem/${encodeURIComponent(category)}/${encodeURIComponent(name)}`
  );
}

/** 拉取五类分组：先取分类，再逐类取聚合名与取样。 */
async function load() {
  const categories = await api<
    { category: string; total: number; supportsPercentile: boolean }[]
  >("/reports/problem/categories", {
    query: { service: service.value, range: range.value },
  });

  groups.value = await Promise.all(
    categories.map(async (c) => {
      const names = await api<{ name: string; total: number; tp99: number | null }[]>(
        "/reports/problem/names",
        { query: { service: service.value, category: c.category, range: range.value } }
      );

      const rows = await Promise.all(
        names.map(async (n) => {
          const fetched = await api<Sample[]>("/reports/samples", {
            query: {
              kind: "PROBLEM",
              service: service.value,
              type: c.category,
              name: n.name,
              range: range.value,
            },
          });
          return {
            name: n.name,
            total: n.total,
            tp99: n.tp99,
            sampleCount: fetched.length,
            // 原始树已过留存期的取样不再展示（不能下钻，留着只会打断条带的连续性）
            samples: fetched.filter((s) => s.traceAvailable),
          };
        })
      );

      return {
        category: problemLabel(c.category),
        rawCategory: c.category,
        total: c.total,
        supportsPercentile: c.supportsPercentile,
        rows,
      };
    })
  );
}

onMounted(load);
watch(service, load);
</script>
