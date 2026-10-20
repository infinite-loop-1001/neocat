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
      :title="visible ? '隐藏密码' : '显示密码'"
      @click="toggle"
    >
      <!-- 图标代替文字，避免按钮宽度随文案变化；睁眼=当前明文可见 -->
      <svg v-if="visible" class="icon" viewBox="0 0 20 20" width="18" height="18" aria-hidden="true" focusable="false">
        <path
          d="M2 10c2.4-3.4 5.1-5.1 8-5.1s5.6 1.7 8 5.1c-2.4 3.4-5.1 5.1-8 5.1S4.4 13.4 2 10z"
          fill="none"
          stroke="currentColor"
          stroke-width="1.3"
          stroke-linejoin="round"
        />
        <circle cx="10" cy="10" r="2.4" fill="none" stroke="currentColor" stroke-width="1.3" />
      </svg>
      <svg v-else class="icon" viewBox="0 0 20 20" width="18" height="18" aria-hidden="true" focusable="false">
        <path
          d="M2 10c2.4-3.4 5.1-5.1 8-5.1s5.6 1.7 8 5.1c-2.4 3.4-5.1 5.1-8 5.1S4.4 13.4 2 10z"
          fill="none"
          stroke="currentColor"
          stroke-width="1.3"
          stroke-linejoin="round"
        />
        <circle cx="10" cy="10" r="2.4" fill="none" stroke="currentColor" stroke-width="1.3" />
        <path d="M3.5 3.5l13 13" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
      </svg>
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
