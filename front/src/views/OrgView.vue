<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>组织树</h2>
      <span class="status">只有叶子组织可以挂载大盘</span>
    </header>

    <div class="admin-toolbar">
      <input v-model="newRootName" class="field org-root-name" placeholder="新根节点名称" />
      <button class="btn btn-primary" type="button" @click="createRoot">创建根节点</button>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>

    <ul class="org-tree">
      <li
        v-for="row in treeRows"
        :key="row.node.id"
        class="org-row"
        :style="{ '--org-depth': row.depth }"
      >
        <div class="org-node" :class="{ 'is-leaf': row.node.leaf }">
          <button
            class="org-toggle"
            type="button"
            :aria-expanded="isOpen(row.node.id)"
            :aria-label="`${isOpen(row.node.id) ? '收起' : '展开'} ${row.node.name} 的成员`"
            @click="toggle(row.node.id)"
          >
            {{ isOpen(row.node.id) ? "▾" : "▸" }}
          </button>
          <span class="strong">{{ row.node.name }}</span>
          <span class="status" :class="row.node.leaf ? 'success' : ''">
            {{ row.node.leaf ? "叶子" : "非叶子" }}
          </span>
          <span class="field-note">成员 {{ membersOf(row.node.id).length }}</span>

          <span class="org-node-actions">
            <span class="node-menu">
              <button
                class="icon-button"
                type="button"
                :aria-label="`${row.node.name} 操作`"
                :aria-expanded="menuFor === row.node.id"
                title="操作"
                @click="toggleMenu(row.node.id)"
              >
                ＋
              </button>
              <span v-if="menuFor === row.node.id" class="node-menu-panel">
                <button type="button" @click="openChildDialog(row.node.id)">新增子节点</button>
                <button type="button" @click="openMemberDialog(row.node.id)">添加成员</button>
              </span>
            </span>
            <button
              v-if="row.node.leaf"
              class="icon-button icon-danger"
              type="button"
              :aria-label="`删除 ${row.node.name}`"
              title="删除"
              @click="openDeleteDialog(row.node.id)"
            >
              －
            </button>
          </span>
        </div>

        <!-- 成员列表抽屉：展开才查询结果，避免默认铺满页面 -->
        <div v-if="isOpen(row.node.id)" class="org-drawer">
          <p v-if="!membersOf(row.node.id).length" class="field-note">该节点还没有直接成员。</p>
          <ul v-else class="member-list">
            <li v-for="account in membersOf(row.node.id)" :key="account">
              <span class="mono">{{ account }}</span>
              <span class="status" :class="accountStatus(account) === 'ENABLED' ? 'success' : 'danger'">
                {{ accountStatus(account) === "ENABLED" ? "启用" : "禁用" }}
              </span>
              <button
                class="btn btn-ghost member-remove"
                type="button"
                :aria-label="`${accountStatus(account) === 'ENABLED' ? '禁用' : '启用'}账号 ${account}`"
                @click="toggleAccountStatus(account)"
              >
                {{ accountStatus(account) === "ENABLED" ? "禁用" : "启用" }}
              </button>
              <button
                class="btn btn-ghost member-remove"
                type="button"
                :aria-label="`移除成员 ${account}`"
                @click="removeMember(row.node.id, account)"
              >
                移除
              </button>
            </li>
          </ul>
        </div>
      </li>
    </ul>

    <p class="hint">
      叶子已有大盘或组织告警时不能新增子节点；必须先清理。删除叶子会原子级联删除其大盘、卡片与组织告警。
    </p>

    <!-- 弹窗：新增子节点 / 添加成员 / 删除确认 -->
    <div v-if="dialog" class="modal-backdrop" @click.self="closeDialog">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        :aria-label="dialogTitle"
        @keydown.esc="closeDialog"
      >
        <header class="panel-head">
          <h3>{{ dialogTitle }}</h3>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeDialog">×</button>
        </header>

        <template v-if="dialog.kind === 'child'">
          <label class="field-label" for="child-name">子节点名称</label>
          <input
            id="child-name"
            v-model="childName"
            class="field"
            :placeholder="`挂在 ${nameOf(dialog.orgId)} 下`"
            @keyup.enter="submitChild"
          />
        </template>

        <template v-else-if="dialog.kind === 'member'">
          <input
            v-model="memberQuery"
            class="field"
            placeholder="搜索账号"
            aria-label="搜索账号"
          />
          <p v-if="!selectableAccounts.length" class="field-note">没有可加入的账号。</p>
          <ul v-else class="account-picker">
            <li v-for="account in selectableAccounts" :key="account.userAccount">
              <label>
                <input
                  type="checkbox"
                  :value="account.userAccount"
                  :checked="selected.includes(account.userAccount)"
                  @change="toggleSelected(account.userAccount)"
                />
                <span class="mono">{{ account.userAccount }}</span>
                <span class="status" :class="account.status === 'ENABLED' ? 'success' : 'danger'">
                  {{ account.status === "ENABLED" ? "启用" : "禁用" }}
                </span>
              </label>
            </li>
          </ul>
        </template>

        <template v-else>
          <dl v-if="preview" class="preview-list">
            <dt>组织</dt>
            <dd>{{ preview.orgName }}</dd>
            <dt>大盘</dt>
            <dd>{{ preview.dashboards.length }} 块（卡片共 {{ totalCards }} 张）</dd>
            <dt>组织告警规则</dt>
            <dd>{{ preview.alertRuleCount }} 条</dd>
            <dt>成员</dt>
            <dd>{{ preview.memberCount }} 人</dd>
          </dl>
          <label class="field-label" for="confirm-name">
            输入组织名 {{ preview?.orgName }} 以确认
          </label>
          <input id="confirm-name" v-model="confirmName" class="field" @keyup.enter="submitDelete" />
        </template>

        <p v-if="dialogError" class="form-error" role="alert">{{ dialogError }}</p>

        <footer class="modal-actions">
          <button class="btn btn-ghost" type="button" @click="closeDialog">取消</button>
          <button
            v-if="dialog.kind === 'child'"
            class="btn btn-primary"
            type="button"
            :disabled="busy"
            @click="submitChild"
          >
            创建
          </button>
          <button
            v-else-if="dialog.kind === 'member'"
            class="btn btn-primary"
            type="button"
            :disabled="busy || !selected.length"
            @click="submitMembers"
          >
            加入成员{{ selected.length ? `（${selected.length}）` : "" }}
          </button>
          <button v-else class="btn btn-danger" type="button" :disabled="busy" @click="submitDelete">
            确认删除
          </button>
        </footer>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, ApiError } from "../api";
import type { Account, OrgNode } from "../mock/model";

interface DeletionPreview {
  orgName: string;
  dashboards: { id: number; name: string; cardCount: number }[];
  alertRuleCount: number;
  memberCount: number;
}

type Dialog =
  | { kind: "child"; orgId: number }
  | { kind: "member"; orgId: number }
  | { kind: "delete"; orgId: number };

const orgs = ref<OrgNode[]>([]);
const accounts = ref<Account[]>([]);
const members = ref<Record<number, string[]>>({});
const expanded = ref<Record<number, boolean>>({});
const menuFor = ref<number | null>(null);
const newRootName = ref("");
const error = ref("");
const dialog = ref<Dialog | null>(null);
const dialogError = ref("");
const busy = ref(false);
const childName = ref("");
const memberQuery = ref("");
const selected = ref<string[]>([]);
const preview = ref<DeletionPreview | null>(null);
const confirmName = ref("");

const tree = computed(() => orgs.value.filter((o) => o.parentId === null));
const treeRows = computed(() => {
  const rows: { node: OrgNode; depth: number }[] = [];
  const visit = (node: OrgNode, depth: number) => {
    rows.push({ node, depth });
    childrenOf(node.id).forEach((child) => visit(child, depth + 1));
  };
  tree.value.forEach((node) => visit(node, 0));
  return rows;
});
const currentOrgId = computed(() => dialog.value?.orgId ?? null);
const dialogTitle = computed(() => {
  if (!dialog.value) return "";
  if (dialog.value.kind === "child") return `在 ${nameOf(dialog.value.orgId)} 下新增子节点`;
  if (dialog.value.kind === "member") return `向 ${nameOf(dialog.value.orgId)} 添加成员`;
  return `删除 ${nameOf(dialog.value.orgId)}`;
});
const totalCards = computed(() =>
  preview.value ? preview.value.dashboards.reduce((sum, d) => sum + d.cardCount, 0) : 0
);
/** 已在该节点的人不再列出，避免重复提交同一关系。 */
const selectableAccounts = computed(() => {
  const keyword = memberQuery.value.trim().toLowerCase();
  const orgId = currentOrgId.value;
  const existing = orgId === null ? [] : membersOf(orgId);
  return accounts.value.filter((account) => {
    if (existing.includes(account.userAccount)) return false;
    return keyword ? account.userAccount.toLowerCase().includes(keyword) : true;
  });
});

function childrenOf(id: number): OrgNode[] {
  return orgs.value.filter((o) => o.parentId === id);
}

function nameOf(orgId: number): string {
  return orgs.value.find((o) => o.id === orgId)?.name ?? "";
}

function membersOf(orgId: number): string[] {
  return members.value[orgId] ?? [];
}

function accountStatus(userAccount: string): string {
  return accounts.value.find((a) => a.userAccount === userAccount)?.status ?? "ENABLED";
}

function isOpen(orgId: number): boolean {
  return expanded.value[orgId] === true;
}

function toggle(orgId: number) {
  expanded.value[orgId] = !isOpen(orgId);
}

function toggleMenu(orgId: number) {
  menuFor.value = menuFor.value === orgId ? null : orgId;
}

async function load() {
  const [loadedOrgs, loadedAccounts] = await Promise.all([
    api<OrgNode[]>("/orgs"),
    api<Account[]>("/users"),
  ]);
  const pairs = await Promise.all(
    loadedOrgs.map(async (org) => [org.id, await api<string[]>(`/orgs/${org.id}/members`)] as const)
  );
  orgs.value = loadedOrgs;
  accounts.value = loadedAccounts;
  members.value = Object.fromEntries(pairs);
}

async function createRoot() {
  error.value = "";
  if (!newRootName.value.trim()) {
    error.value = "请输入根节点名称";
    return;
  }
  try {
    await api("/orgs", { method: "POST", body: { name: newRootName.value, parentId: null } });
    newRootName.value = "";
    await load();
  } catch (e) {
    // 叶子有资源时会被拒绝（LEAF_HAS_RESOURCES）
    error.value = e instanceof ApiError ? e.message : "创建失败";
  }
}

function openChildDialog(orgId: number) {
  menuFor.value = null;
  dialogError.value = "";
  childName.value = "";
  dialog.value = { kind: "child", orgId };
}

function openMemberDialog(orgId: number) {
  menuFor.value = null;
  dialogError.value = "";
  memberQuery.value = "";
  selected.value = [];
  dialog.value = { kind: "member", orgId };
}

async function openDeleteDialog(orgId: number) {
  menuFor.value = null;
  dialogError.value = "";
  confirmName.value = "";
  preview.value = null;
  dialog.value = { kind: "delete", orgId };
  try {
    preview.value = await api<DeletionPreview>(`/orgs/${orgId}/deletion-preview`);
  } catch (e) {
    dialogError.value = e instanceof ApiError ? e.message : "读取删除影响失败";
  }
}

function closeDialog() {
  dialog.value = null;
  dialogError.value = "";
  preview.value = null;
  selected.value = [];
  childName.value = "";
  confirmName.value = "";
}

function toggleSelected(userAccount: string) {
  selected.value = selected.value.includes(userAccount)
    ? selected.value.filter((x) => x !== userAccount)
    : [...selected.value, userAccount];
}

async function submitChild() {
  if (!dialog.value || dialog.value.kind !== "child") return;
  dialogError.value = "";
  if (!childName.value.trim()) {
    dialogError.value = "请输入子节点名称";
    return;
  }
  busy.value = true;
  try {
    await api("/orgs", { method: "POST", body: { name: childName.value, parentId: dialog.value.orgId } });
    await load();
    closeDialog();
  } catch (e) {
    dialogError.value = e instanceof ApiError ? e.message : "创建失败";
  } finally {
    busy.value = false;
  }
}

async function submitMembers() {
  if (!dialog.value || dialog.value.kind !== "member" || !selected.value.length) return;
  const orgId = dialog.value.orgId;
  dialogError.value = "";
  busy.value = true;
  try {
    await Promise.all(
      selected.value.map((userAccount) =>
        api(`/orgs/${orgId}/members`, { method: "POST", body: { userAccount } })
      )
    );
    await load();
    expanded.value[orgId] = true;
    closeDialog();
  } catch (e) {
    dialogError.value = e instanceof ApiError ? e.message : "加入成员失败";
  } finally {
    busy.value = false;
  }
}

async function removeMember(orgId: number, userAccount: string) {
  error.value = "";
  try {
    await api(`/orgs/${orgId}/members/${encodeURIComponent(userAccount)}`, { method: "DELETE" });
    await load();
    expanded.value[orgId] = true;
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "移除成员失败";
  }
}

/** 账号启用/禁用属于账号管理，这里只做跳转，避免在组织页重复一套账号状态逻辑。 */
async function toggleAccountStatus(userAccount: string) {
  error.value = "";
  const status = accountStatus(userAccount);
  try {
    await api(`/users/${encodeURIComponent(userAccount)}/${status === "ENABLED" ? "disable" : "enable"}`, {
      method: "POST",
    });
    await load();
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "更新账号状态失败";
  }
}

async function submitDelete() {
  if (!dialog.value || dialog.value.kind !== "delete") return;
  dialogError.value = "";
  busy.value = true;
  try {
    await api(`/orgs/${dialog.value.orgId}`, {
      method: "DELETE",
      query: { confirmName: confirmName.value },
    });
    await load();
    closeDialog();
  } catch (e) {
    dialogError.value = e instanceof ApiError ? e.message : "删除失败";
  } finally {
    busy.value = false;
  }
}

onMounted(load);
</script>
