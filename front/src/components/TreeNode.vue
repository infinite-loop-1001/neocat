<template>
  <div class="tree">
    <span :class="{ missing: node.missing }">{{ node.domain }} / {{ node.messageId }}{{ node.missing ? "（缺失）" : "" }}</span>
    <TreeNode v-for="c in node.children" :key="c.messageId" :node="c" />
  </div>
</template>
<script setup lang="ts">
export type CallSpan = { name: string; type: string; duration: number; left: number; width: number; status: string; detail: string };
export type CallNode = { messageId: string; domain: string; missing: boolean; children: CallNode[]; spans?: CallSpan[] };
defineOptions({ name: "TreeNode" });
defineProps<{ node: CallNode }>();
</script>
