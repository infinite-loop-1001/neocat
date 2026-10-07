<template>
  <li class="trace-node">
    <div class="trace-head" :class="statusClass">
      <span class="mono strong">{{ node.service }}</span>
      <span v-if="node.instance" class="mono muted">{{ node.instance }}</span>
      <span v-if="node.availability === 'PRESENT'" class="status success">已收到</span>
      <span v-else-if="node.availability === 'MISSING'" class="status danger">缺失节点</span>
      <span v-else class="status warn">已过期</span>
      <span class="mono muted small">{{ node.messageId }}</span>
    </div>

    <p v-if="node.reason" class="trace-reason">{{ node.reason }}</p>

    <table v-if="node.spans.length" class="data-table compact">
      <thead>
        <tr>
          <th>类型</th>
          <th>名称</th>
          <th class="num">耗时</th>
          <th>状态</th>
          <th>详情</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="span in node.spans" :key="span.nodeId">
          <td class="mono">{{ span.category }}</td>
          <td>{{ span.name }}</td>
          <td class="num">{{ span.durationMs }} ms</td>
          <td>
            <span class="status" :class="span.status === '0' ? 'success' : 'danger'">
              {{ span.status === "0" ? "成功" : "失败" }}
            </span>
          </td>
          <td class="mono muted">{{ span.detail || "—" }}</td>
        </tr>
      </tbody>
    </table>

    <ul v-if="node.children.length" class="trace-children">
      <TraceTreeNode v-for="child in node.children" :key="child.messageId" :node="child" :depth="depth + 1" />
    </ul>
  </li>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { TraceNodeModel } from "../mock/model";

const props = defineProps<{ node: TraceNodeModel; depth: number }>();

const statusClass = computed(() => {
  switch (props.node.availability) {
    case "MISSING":
      return "is-missing";
    case "EXPIRED":
      return "is-expired";
    default:
      return "is-present";
  }
});
</script>
