<template>
  <div class="app-shell">
    <header class="masthead">
      <router-link class="brand" to="/services" aria-label="NeoCat">
        <span class="brand-mark" aria-hidden="true"><span /><span /><span /><span /></span>
        <span>NeoCat</span>
      </router-link>

      <nav class="product-nav" aria-label="系统导航">
        <router-link to="/services">服务报表</router-link>
        <router-link to="/dashboards">组织大盘</router-link>
        <router-link to="/alerts">告警</router-link>
      </nav>

      <div class="app-context">
        <span class="app-context-label">当前服务</span>
        <strong>{{ service || "未选择" }}</strong>
        <span class="num">tz {{ timezone }}</span>
      </div>

      <div class="masthead-actions">
        <div v-if="isAdmin" class="admin-pop">
          <button class="icon-button" type="button" aria-label="管理" @click="adminOpen = !adminOpen">⚙</button>
          <div v-if="adminOpen" class="admin-menu" @click="adminOpen = false">
            <router-link to="/admin/users">账号管理</router-link>
            <router-link to="/admin/orgs">组织树</router-link>
            <router-link to="/admin/platform">平台配置</router-link>
          </div>
        </div>
        <span class="app-context-label user-label">{{ me }}</span>
        <button class="icon-button" type="button" aria-label="退出" @click="logout">⎋</button>
      </div>
    </header>

    <div class="page">
      <aside class="side-rail" aria-label="监控域导航">
        <p class="side-title">MONITOR DOMAIN</p>
        <div class="rail-list">
          <router-link
            v-for="domain in domains"
            :key="domain.kind"
            class="rail-link"
            :class="{ 'is-active': kind === domain.kind }"
            :to="domainPath(domain.kind)"
          >
            <span>{{ domain.label }}</span>
          </router-link>
        </div>

        <p class="side-title" style="margin-top: var(--space-6)">SERVICE</p>
        <input v-model="q" class="field app-search" placeholder="搜索服务" />
        <div class="rail-apps">
          <router-link
            v-for="s in filteredServices"
            :key="s.name"
            class="rail-link"
            :class="{ 'is-active': s.name === service }"
            :to="`/svc/${s.name}/${kind.toLowerCase()}`"
          >
            <span>{{ s.name }}</span>
            <span class="num">{{ s.instances.length }}</span>
          </router-link>
        </div>
      </aside>

      <main class="workspace">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api";

interface ServiceRow {
  name: string;
  instances: string[];
}

const route = useRoute();
const router = useRouter();
const me = ref("");
const role = ref("");
const q = ref("");
const adminOpen = ref(false);
const services = ref<ServiceRow[]>([]);
const timezone = ref("");

/** PRD 支持的服务报表域（不含已排除的应用总览、Business、Hosts）。 */
const domains = [
  { kind: "TRANSACTION", label: "Transaction" },
  { kind: "EVENT", label: "Event" },
  { kind: "PROBLEM", label: "Problem" },
  { kind: "HEARTBEAT", label: "Heartbeat" },
  { kind: "METRIC", label: "Metric" },
  { kind: "DEPENDENCY", label: "Dependency" },
];

const service = computed(() => (route.params.service as string) ?? "");
const kind = computed(() => {
  const segments = route.path.split("/");
  const found = domains.find((d) => d.kind.toLowerCase() === segments[3]);
  return found?.kind ?? "TRANSACTION";
});

const isAdmin = computed(() => role.value === "ADMIN" || role.value === "SUPER_ADMIN");

const filteredServices = computed(() => {
  const keyword = q.value.trim().toLowerCase();
  return keyword ? services.value.filter((s) => s.name.toLowerCase().includes(keyword)) : services.value;
});

function domainPath(domainKind: string): string {
  const target = service.value || services.value[0]?.name;
  if (!target) return "/services";
  return `/svc/${target}/${domainKind.toLowerCase()}`;
}

async function load() {
  try {
    const account = await api<{ username: string; role: string }>("/me");
    me.value = account.username;
    role.value = account.role;
  } catch {
    await router.push("/login");
    return;
  }
  services.value = await api<ServiceRow[]>("/services");
  const profile = await api<{ timezone: string }>("/platform");
  timezone.value = profile.timezone;
}

async function logout() {
  try {
    await api("/logout", { method: "POST" });
  } finally {
    await router.push("/login");
  }
}

onMounted(load);
watch(() => route.path, load);
</script>
