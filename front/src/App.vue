<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" v-if="Component" />
    <div v-else class="login-page">
      <section class="login-panel" role="status" aria-live="polite">
        <h1>NeoCat</h1>
        <p class="login-sub">正在检查平台状态…</p>
      </section>
    </div>
  </router-view>
</template>
