<template>
  <div class="login-page">
    <form class="login-panel" @submit.prevent="submit">
      <header class="login-head">
        <span class="brand-mark" aria-hidden="true"><span /><span /><span /><span /></span>
        <h1>NeoCat</h1>
        <p class="login-sub">应用监控平台</p>
      </header>

      <label class="field-label" for="username">用户名</label>
      <input id="username" v-model="username" class="field" autocomplete="username" required />

      <label class="field-label" for="password">密码</label>
      <input id="password" v-model="password" class="field" type="password" autocomplete="current-password" required />

      <p v-if="error" class="form-error" role="alert">{{ error }}</p>

      <button class="btn btn-primary login-submit" type="submit" :disabled="busy">
        {{ busy ? "登录中…" : "登录" }}
      </button>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { api, ApiError } from "../api";

const router = useRouter();
const username = ref("");
const password = ref("");
const error = ref("");
const busy = ref(false);

async function submit() {
  error.value = "";
  busy.value = true;
  try {
    const result = await api<{
      mustChangePassword: boolean;
      entry: { type: string; service?: string; kind?: string };
    }>("/login", { method: "POST", body: { username: username.value, password: password.value } });

    // 首次登录 / 重置后必须先改密（PRD 01 §4.3）
    if (result.mustChangePassword) {
      await router.push("/change-password");
      return;
    }
    // 有可用最近访问服务则进入该服务 Transaction，否则进入服务列表（PRD 01 §4.1）
    if (result.entry.type === "SERVICE_TRANSACTION" && result.entry.service) {
      await router.push(`/svc/${result.entry.service}/transaction`);
    } else {
      await router.push("/services");
    }
  } catch (e) {
    // 统一凭据错误：不区分账号是否存在（PRD 01 §4.1）
    error.value = e instanceof ApiError ? e.message : "登录失败";
  } finally {
    busy.value = false;
  }
}
</script>
