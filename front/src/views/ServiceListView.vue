<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>服务列表</h2>
      <span class="status">{{ services.length }} 个服务</span>
    </header>

    <p class="hint">
      服务由上报自动发现。列表按「当前报表类型 + 当前时间范围」动态过滤，无数据的服务不展示。
    </p>

    <table class="data-table">
      <thead>
        <tr>
          <th>服务</th>
          <th>实例数</th>
          <th>实例</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="s in services"
          :key="s.name"
          class="clickable-row"
          role="link"
          tabindex="0"
          :aria-label="`查看 ${s.name} 报表`"
          @click="open(s.name)"
          @keydown.enter="open(s.name)"
        >
          <td class="strong">{{ s.name }}</td>
          <td class="num">{{ s.instances.length }}</td>
          <td class="mono muted">{{ s.instances.join("  ") }}</td>
          <td><button class="btn btn-ghost" type="button" @click.stop="open(s.name)">查看报表</button></td>
        </tr>
        <tr v-if="!services.length">
          <td colspan="4" class="empty">当前范围内没有可查询的服务</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { api } from "../api";

const router = useRouter();
const services = ref<{ name: string; instances: string[] }[]>([]);

async function open(name: string) {
  // 登录后的默认入口是 Transaction（PRD 03 §1）
  await router.push(`/svc/${name}/transaction`);
}

onMounted(async () => {
  services.value = await api<{ name: string; instances: string[] }[]>("/services");
});
</script>
