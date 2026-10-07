<template>
  <div class="login-page">
    <form class="login-panel" @submit.prevent="submit">
      <header class="login-head">
        <h1>修改密码</h1>
        <p class="login-sub">首次登录或密码重置后必须先修改密码</p>
      </header>

      <label class="field-label" for="old">当前密码</label>
      <input id="old" v-model="oldPassword" class="field" type="password" required />

      <label class="field-label" for="next">新密码（至少 8 位）</label>
      <input id="next" v-model="newPassword" class="field" type="password" required />

      <label class="field-label" for="confirm">确认新密码</label>
      <input id="confirm" v-model="confirmPassword" class="field" type="password" required />

      <p v-if="error" class="form-error" role="alert">{{ error }}</p>

      <button class="btn btn-primary login-submit" type="submit" :disabled="busy">
        {{ busy ? "提交中…" : "提交" }}
      </button>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { api, ApiError } from "../api";

const router = useRouter();
const oldPassword = ref("");
const newPassword = ref("");
const confirmPassword = ref("");
const error = ref("");
const busy = ref(false);

async function submit() {
  error.value = "";
  if (newPassword.value !== confirmPassword.value) {
    error.value = "两次输入的新密码不一致";
    return;
  }
  busy.value = true;
  try {
    await api("/me/password", {
      method: "POST",
      body: { oldPassword: oldPassword.value, newPassword: newPassword.value },
    });
    await router.push("/services");
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "修改失败";
  } finally {
    busy.value = false;
  }
}
</script>
