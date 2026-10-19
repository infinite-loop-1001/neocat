<template>
  <span class="password-field">
    <input
      :id="id"
      class="field"
      :type="visible ? 'text' : 'password'"
      :value="modelValue"
      :autocomplete="autocomplete"
      :required="required"
      :minlength="minlength"
      :disabled="disabled"
      :placeholder="placeholder"
      @input="onInput"
    />
    <!-- type=button：切换明文不能触发表单提交 -->
    <button
      class="password-toggle"
      type="button"
      :disabled="disabled"
      :aria-label="visible ? '隐藏密码' : '显示密码'"
      @click="toggle"
    >
      {{ visible ? "隐藏" : "显示" }}
    </button>
  </span>
</template>

<script setup lang="ts">
import { ref } from "vue";

// 密码默认保持掩码；只有用户显式点击才显示当前输入的明文。
defineProps<{
  modelValue: string;
  id?: string;
  autocomplete?: string;
  required?: boolean;
  minlength?: number | string;
  disabled?: boolean;
  placeholder?: string;
}>();

const emit = defineEmits<{ "update:modelValue": [string] }>();

const visible = ref(false);

function toggle() {
  visible.value = !visible.value;
}

function onInput(event: Event) {
  emit("update:modelValue", (event.target as HTMLInputElement).value);
}
</script>
