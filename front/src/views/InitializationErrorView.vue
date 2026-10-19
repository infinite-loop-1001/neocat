<template>
  <div class="login-page">
    <section class="login-panel" :aria-busy="busy">
      <header class="login-head">
        <h1>暂时无法进入 NeoCat</h1>
        <p class="login-sub">需要先确认平台是否已经初始化。</p>
      </header>
      <p class="form-error" role="alert">{{ error }}</p>
      <button class="btn btn-primary login-submit" type="button" :disabled="busy" @click="retry">
        {{ busy ? "正在检查…" : "重试" }}
      </button>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";

const router = useRouter();
const error = ref("初始化状态查询失败，请确认后端服务和网络连接正常后重试。");
const busy = ref(false);

async function retry() {
  if (busy.value) return;
  busy.value = true;
  try {
    // 重新经过统一检查，不在错误页缓存或猜测初始化状态。
    await router.replace("/login");
  } catch {
    error.value = "重试失败，请检查连接后再次重试。";
  } finally {
    busy.value = false;
  }
}
</script>
