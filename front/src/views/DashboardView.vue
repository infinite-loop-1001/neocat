<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>组织大盘</h2>
      <span class="status">仅展示我有成员资格的叶子组织</span>
    </header>

    <p class="hint">
      大盘只挂在<strong>叶子组织</strong>上；非成员看不到入口，管理员也没有权限旁路。
    </p>

    <div v-if="!leafOrgs.length" class="empty-state">
      我没有可访问的叶子组织大盘。请联系管理员将账号加入某个叶子组织（或其祖先组织）。
    </div>

    <template v-else>
      <div class="dashboard-toolbar">
        <label class="field-label" for="leaf">叶子组织</label>
        <select id="leaf" v-model.number="orgId" class="select-field">
          <option v-for="org in leafOrgs" :key="org.id" :value="org.id">{{ org.name }}</option>
        </select>
        <input v-model="newName" class="field" placeholder="新大盘名称" />
        <button class="btn btn-primary" type="button" @click="create">创建大盘</button>
      </div>

      <p v-if="error" class="form-error" role="alert">{{ error }}</p>

      <table class="data-table">
        <thead>
          <tr>
            <th>大盘</th>
            <th class="num">卡片数</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="board in boards" :key="board.id">
            <td class="strong">{{ board.name }}</td>
            <td class="num">{{ countOf(board.id) }}</td>
            <td class="actions">
              <router-link class="btn btn-ghost" :to="`/dashboards/${board.id}`">编辑卡片</router-link>
              <button class="btn btn-ghost" type="button" @click="remove(board.id)">删除</button>
            </td>
          </tr>
          <tr v-if="!boards.length">
            <td colspan="3" class="empty">该叶子还没有大盘</td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api, ApiError } from "../api";
import type { Card, Dashboard, OrgNode } from "../mock/model";

const boards = ref<Dashboard[]>([]);
const leafOrgs = ref<OrgNode[]>([]);
const cards = ref<Card[]>([]);
const orgId = ref<number | null>(null);
const newName = ref("");
const error = ref("");

function countOf(dashboardId: number): number {
  return cards.value.filter((c) => c.dashboardId === dashboardId).length;
}

async function load() {
  const allOrgs = await api<OrgNode[]>("/orgs");
  const mine = await api<number[]>("/orgs/mine");
  leafOrgs.value = allOrgs.filter((o) => o.leaf && mine.includes(o.id));
  if (orgId.value === null && leafOrgs.value.length) {
    orgId.value = leafOrgs.value[0].id;
  }
  boards.value = await api<Dashboard[]>("/dashboards");
}

async function create() {
  error.value = "";
  if (orgId.value === null) {
    error.value = "请选择叶子组织";
    return;
  }
  if (!newName.value.trim()) {
    error.value = "请输入大盘名称";
    return;
  }
  try {
    await api("/dashboards", { method: "POST", body: { orgId: orgId.value, name: newName.value } });
    newName.value = "";
    await load();
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "创建失败";
  }
}

async function remove(id: number) {
  await api(`/dashboards/${id}`, { method: "DELETE" });
  await load();
}

onMounted(load);
watch(orgId, load);
</script>
