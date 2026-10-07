<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>组织树</h2>
      <span class="status">只有叶子组织可以挂载大盘</span>
    </header>

    <div class="admin-toolbar">
      <input v-model="newName" class="field" placeholder="组织名称" />
      <select v-model.number="parentId" class="select-field">
        <option :value="0">作为根节点</option>
        <option v-for="org in orgs" :key="org.id" :value="org.id">挂在 {{ org.name }} 下</option>
      </select>
      <button class="btn btn-primary" type="button" @click="create">创建节点</button>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>

    <ul class="org-tree">
      <li v-for="node in tree" :key="node.id">
        <div class="org-node" :class="{ 'is-leaf': node.leaf }">
          <span class="strong">{{ node.name }}</span>
          <span class="status" :class="node.leaf ? 'success' : ''">{{ node.leaf ? "叶子" : "非叶子" }}</span>
          <span class="field-note">成员 {{ node.memberCount }}</span>
          <span class="actions">
            <button class="btn btn-ghost" type="button" @click="addMember(node.id)">加入成员</button>
            <button v-if="node.leaf" class="btn btn-ghost" type="button" @click="previewDeletion(node.id)">
              删除预览
            </button>
          </span>
        </div>
        <ul v-if="childrenOf(node.id).length" class="org-children">
          <li v-for="child in childrenOf(node.id)" :key="child.id">
            <div class="org-node" :class="{ 'is-leaf': child.leaf }">
              <span>{{ child.name }}</span>
              <span class="status" :class="child.leaf ? 'success' : ''">{{ child.leaf ? "叶子" : "非叶子" }}</span>
              <span class="field-note">成员 {{ child.memberCount }}</span>
              <span class="actions">
                <button class="btn btn-ghost" type="button" @click="addMember(child.id)">加入成员</button>
                <button v-if="child.leaf" class="btn btn-ghost" type="button" @click="previewDeletion(child.id)">
                  删除预览
                </button>
              </span>
            </div>
          </li>
        </ul>
      </li>
    </ul>

    <section v-if="preview" class="preview-panel">
      <header class="panel-head">
        <h3>删除影响预览</h3>
        <span class="status danger">删除后不可恢复</span>
      </header>
      <dl class="preview-list">
        <dt>组织</dt>
        <dd>{{ preview.orgName }}</dd>
        <dt>大盘</dt>
        <dd>{{ preview.dashboards.length }} 块（卡片共 {{ totalCards }} 张）</dd>
        <dt>组织告警规则</dt>
        <dd>{{ preview.alertRuleCount }} 条</dd>
        <dt>成员</dt>
        <dd>{{ preview.memberCount }} 人</dd>
      </dl>
      <div class="admin-toolbar">
        <input v-model="confirmName" class="field" :placeholder="`输入组织名 ${preview.orgName} 以确认`" />
        <button class="btn btn-danger" type="button" @click="doDelete">确认删除</button>
      </div>
    </section>

    <p class="hint">
      叶子已有大盘或组织告警时不能新增子节点；必须先清理。删除叶子会原子级联删除其大盘、卡片与组织告警。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, ApiError } from "../api";
import type { OrgNode } from "../mock/model";

interface DeletionPreview {
  orgName: string;
  dashboards: { id: number; name: string; cardCount: number }[];
  alertRuleCount: number;
  memberCount: number;
}

const orgs = ref<OrgNode[]>([]);
const newName = ref("");
const parentId = ref(0);
const error = ref("");
const preview = ref<DeletionPreview | null>(null);
const previewOrgId = ref<number | null>(null);
const confirmName = ref("");

const tree = computed(() => orgs.value.filter((o) => o.parentId === null));
const totalCards = computed(() =>
  preview.value ? preview.value.dashboards.reduce((sum, d) => sum + d.cardCount, 0) : 0
);

function childrenOf(id: number): OrgNode[] {
  return orgs.value.filter((o) => o.parentId === id);
}

async function load() {
  orgs.value = await api<OrgNode[]>("/orgs");
}

async function create() {
  error.value = "";
  try {
    await api("/orgs", {
      method: "POST",
      body: { name: newName.value, parentId: parentId.value === 0 ? null : parentId.value },
    });
    newName.value = "";
    await load();
  } catch (e) {
    // 叶子有资源时会被拒绝（LEAF_HAS_RESOURCES）
    error.value = e instanceof ApiError ? e.message : "创建失败";
  }
}

async function addMember(orgId: number) {
  await api(`/orgs/${orgId}/members`, { method: "POST", body: { userId: 3 } });
  await load();
}

async function previewDeletion(orgId: number) {
  previewOrgId.value = orgId;
  confirmName.value = "";
  preview.value = await api<DeletionPreview>(`/orgs/${orgId}/deletion-preview`);
}

async function doDelete() {
  if (!preview.value || previewOrgId.value === null) return;
  error.value = "";
  try {
    await api(`/orgs/${previewOrgId.value}`, {
      method: "DELETE",
      query: { confirmName: confirmName.value },
    });
    preview.value = null;
    previewOrgId.value = null;
    await load();
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "删除失败";
  }
}

onMounted(load);
</script>
