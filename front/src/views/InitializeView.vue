<template>
  <div class="login-page">
    <form class="login-panel" :aria-busy="busy" @submit.prevent="submit">
      <header class="login-head">
        <h1>首次开通 NeoCat</h1>
        <p class="login-sub">设置平台时区和第一个超级管理员账号</p>
      </header>

      <label class="field-label" for="timezone">平台时区</label>
      <select id="timezone" v-model="timezone" class="field" required :disabled="busy" aria-describedby="timezone-help">
        <option v-for="zone in timezones" :key="zone" :value="zone">{{ zone }}</option>
      </select>
      <p id="timezone-help" class="login-sub">时区一旦初始化，一期不可修改。所有报表和告警使用此时区。</p>

      <label class="field-label" for="admin-account">超级管理员账号</label>
      <input id="admin-account" v-model="adminUsername" class="field" autocomplete="username" required :disabled="busy" />

      <label class="field-label" for="admin-password">初始密码（至少 8 位）</label>
      <PasswordField id="admin-password" v-model="adminPassword" autocomplete="new-password"
        minlength="8" required :disabled="busy" />

      <label class="field-label" for="confirm-password">确认初始密码</label>
      <PasswordField id="confirm-password" v-model="confirmPassword" autocomplete="new-password"
        minlength="8" required :disabled="busy" />

      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <button class="btn btn-primary login-submit" type="submit" :disabled="busy">
        {{ busy ? "正在确认并开通…" : "确认初始化" }}
      </button>
      <p class="login-sub">仅首次部署需要开通。完成后请登录，并按提示修改初始密码。</p>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { api, ApiError } from "../api";
import PasswordField from "../components/PasswordField.vue";
import { ERROR_CODE } from "../api/error-codes";
import { loadInitializationStatus } from "../api/platform-initialization";

const router = useRouter();
const timezones = ["Asia/Shanghai", "UTC", "Asia/Tokyo", "Europe/London", "America/New_York"];
const timezone = ref("Asia/Shanghai");
const adminUsername = ref("");
const adminPassword = ref("");
const confirmPassword = ref("");
const error = ref("");
const busy = ref(false);

async function goToLogin(setup: "complete" | "already") {
  adminPassword.value = "";
  confirmPassword.value = "";
  await router.replace({ path: "/login", query: { setup } });
}

async function submit() {
  if (busy.value) return;
  error.value = "";
  if (!adminUsername.value.trim() || !timezones.includes(timezone.value)) {
    error.value = "请填写超级管理员账号并选择有效时区";
    return;
  }
  if (adminPassword.value.length < 8) {
    error.value = "初始密码至少 8 位";
    return;
  }
  if (adminPassword.value !== confirmPassword.value) {
    error.value = "两次输入的初始密码不一致";
    return;
  }
  busy.value = true;
  try {
    // 每次提交前重新确认，涵盖其他窗口已开通以及上次提交结果不确定的情况。
    let initialized: boolean;
    try {
      initialized = await loadInitializationStatus(api);
    } catch {
      error.value = "无法确认平台初始化状态，未提交开通请求。请检查连接后重试。";
      return;
    }
    if (initialized) {
      await goToLogin("already");
      return;
    }
    const profile = await api<{ initialized: boolean }>("/platform/initialize", {
      method: "POST",
      body: { timezone: timezone.value, adminUsername: adminUsername.value.trim(), adminPassword: adminPassword.value },
    });
    if (profile?.initialized !== true) throw new Error("初始化未确认完成");
    await goToLogin("complete");
  } catch (e) {
    if (e instanceof ApiError && e.status === 409 && e.code === ERROR_CODE.ALREADY_INITIALIZED) {
      await goToLogin("already");
    } else {
      error.value = e instanceof ApiError ? e.message
        : "未能确认开通结果。请检查连接后重试；再次提交前会重新查询状态，不会自动重复开通。";
    }
  } finally {
    busy.value = false;
  }
}
</script>
