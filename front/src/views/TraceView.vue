<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>Trace</h2>
      <span class="mono">{{ messageId }}</span>
    </header>

    <p v-if="expired" class="banner-danger">
      该原始树已超过 7 天留存期，汇总仍可查，但不可打开。
    </p>

    <template v-else>
      <p class="hint">
        已知父子关系但子树未收到时显示为缺失节点；曾收到但超期显示为过期节点。二者语义不同。
      </p>
      <ul class="trace-tree">
        <TraceTreeNode v-for="child in trace?.children ?? []" :key="child.messageId" :node="child" :depth="0" />
      </ul>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, ApiError } from "../api";
import TraceTreeNode from "../components/TraceTreeNode.vue";
import type { TraceNodeModel } from "../mock/model";

const props = defineProps<{ messageId: string }>();

const trace = ref<{ children: TraceNodeModel[] } | null>(null);
const expired = ref(false);

onMounted(async () => {
  try {
    trace.value = await api<{ children: TraceNodeModel[] }>(`/traces/${props.messageId}`);
  } catch (e) {
    // 超期：后端返回 TRACE_EXPIRED（410）
    if (e instanceof ApiError && e.status === 410) {
      expired.value = true;
    }
  }
});
</script>
