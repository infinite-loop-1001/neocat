<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>账号管理</h2>
      <span class="status">管理员可创建普通账号；授予 ADMIN 仅超级管理员</span>
    </header>

    <div class="admin-toolbar">
      <input v-model="newUsername" class="field" placeholder="用户名" />
      <input v-model="newPassword" class="field" type="password" placeholder="临时密码（至少 8 位）" />
      <button class="btn btn-primary" type="button" @click="create">创建账号</button>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>

    <table class="data-table">
      <thead>
        <tr>
          <th>用户名</th>
          <th>角色</th>
          <th>状态</th>
          <th>首次改密</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="account in accounts" :key="account.id">
          <td class="strong">{{ account.username }}</td>
          <td>
            <span class="status" :class="account.role === 'USER' ? '' : 'success'">{{ roleLabel(account.role) }}</span>
          </td>
          <td>
            <span class="status" :class="account.status === 'ENABLED' ? 'success' : 'danger'">
              {{ account.status === "ENABLED" ? "启用" : "禁用" }}
            </span>
          </td>
          <td>{{ account.mustChangePassword ? "需要" : "否" }}</td>
          <td class="actions">
            <button class="btn btn-ghost" type="button" @click="resetPassword(account.id)">重置密码</button>
            <button
              v-if="isSuperAdmin && account.role !== 'SUPER_ADMIN'"
              class="btn btn-ghost"
              type="button"
              @click="toggleRole(account)"
            >
              {{ account.role === "ADMIN" ? "取消管理员" : "授予管理员" }}
            </button>
            <button
              class="btn btn-ghost"
              type="button"
              :disabled="account.role === 'SUPER_ADMIN'"
              @click="toggleStatus(account)"
            >
              {{ account.status === "ENABLED" ? "禁用" : "启用" }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <p class="hint">
      禁用账号会立即失效其全部会话，并从告警收件人中移除，但保留组织成员关系；
      启用后可重新登录，<strong>不恢复</strong>告警收件关系。一期不支持删除账号与修改用户名。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, ApiError } from "../api";
import type { Account } from "../mock/model";

const accounts = ref<Account[]>([]);
const newUsername = ref("");
const newPassword = ref("");
const error = ref("");
const currentRole = ref("");

const isSuperAdmin = computed(() => currentRole.value === "SUPER_ADMIN");

function roleLabel(role: string): string {
  switch (role) {
    case "SUPER_ADMIN":
      return "超级管理员";
    case "ADMIN":
      return "管理员";
    default:
      return "普通用户";
  }
}

async function load() {
  accounts.value = await api<Account[]>("/users");
  const me = await api<{ role: string }>("/me");
  currentRole.value = me.role;
}

async function create() {
  error.value = "";
  try {
    await api("/users", { method: "POST", body: { username: newUsername.value, password: newPassword.value } });
    newUsername.value = "";
    newPassword.value = "";
    await load();
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "创建失败";
  }
}

async function resetPassword(id: number) {
  error.value = "";
  const password = window.prompt("请输入新的临时密码（至少 8 位）");
  if (!password) return;
  try {
    await api(`/users/${id}/password/reset`, { method: "POST", body: { password } });
    await load();
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "重置失败";
  }
}

async function toggleRole(account: Account) {
  error.value = "";
  try {
    await api(`/users/${account.id}/role`, {
      method: "POST",
      body: { role: account.role === "ADMIN" ? "USER" : "ADMIN" },
    });
    await load();
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "操作失败";
  }
}

async function toggleStatus(account: Account) {
  const action = account.status === "ENABLED" ? "disable" : "enable";
  await api(`/users/${account.id}/${action}`, { method: "POST" });
  await load();
}

onMounted(load);
</script>
