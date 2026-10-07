<template>
  <div class="machine-picker">
    <div class="machine-head">
      <input v-model="q" class="field" placeholder="搜索实例" aria-label="搜索实例" />
      <span class="field-note">已选 {{ selected.length }} 台</span>
      <button class="btn btn-ghost" type="button" @click="clear">返回全部机器聚合</button>
    </div>

    <ul class="machine-list">
      <li v-for="row in filtered" :key="row.instance">
        <label class="machine-item">
          <input type="checkbox" :checked="selected.includes(row.instance)" @change="toggle(row.instance)" />
          <span class="mono">{{ row.instance }}</span>
          <span v-if="row.value !== undefined" class="num machine-value">{{ row.value }}</span>
        </label>
      </li>
      <li v-if="!filtered.length" class="empty">没有匹配的实例</li>
    </ul>

    <p class="hint">
      {{ hint ?? "勾选后只展示选中机器，不再自动加入 other（PRD 03 §7.3）。" }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";

export interface MachineOption {
  instance: string;
  /** 该实例的参考数值（如窗口内合计）；没有这个概念时不传，不显示数字。 */
  value?: number;
}

const props = defineProps<{
  machines: MachineOption[];
  modelValue: string[];
  /** 不传时用 Transaction 的机器聚合口径。 */
  hint?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [string[]] }>();

const q = ref("");
const selected = computed(() => props.modelValue);

const filtered = computed(() => {
  const keyword = q.value.trim().toLowerCase();
  return keyword ? props.machines.filter((m) => m.instance.toLowerCase().includes(keyword)) : props.machines;
});

function toggle(instance: string) {
  const next = selected.value.includes(instance)
    ? selected.value.filter((x) => x !== instance)
    : [...selected.value, instance];
  emit("update:modelValue", next);
}

function clear() {
  emit("update:modelValue", []);
}
</script>
