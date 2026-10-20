import assert from "node:assert/strict";
import { test } from "node:test";
import { mockRequest, resetMockState } from "../src/mock/router.ts";
import { ERROR_CODE } from "../src/api/error-codes.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";

const request = (path, options = {}) => mockRequest(`/api${path}`, { method: "GET", ...options });
const post = (path, body) => request(path, { method: "POST", body: JSON.stringify(body) });
const del = (path) => request(path, { method: "DELETE" });

async function loginAsRoot() {
  resetMockState();
  await post("/login", { userAccount: "root", password: "NeoCat@2026" });
}

test("组织列表只返回 memberCount，成员账号走独立端点", async () => {
  await loginAsRoot();
  const orgs = await request("/orgs");
  const org = orgs.find((node) => node.id === 4);
  assert.equal(org.members, undefined, "组织节点不应内联成员账号");
  assert.equal(typeof org.memberCount, "number");
  assert.deepEqual(await request(`/orgs/${org.id}/members`), ["alice"]);
});

test("组织成员使用真实账号加入并在成员端点回显", async () => {
  await loginAsRoot();
  const before = await request("/orgs");
  const org = before.find((node) => node.id === 4);
  assert.equal((await request(`/orgs/${org.id}/members`)).includes("root"), false);

  await post(`/orgs/${org.id}/members`, { userAccount: "root" });

  assert.deepEqual(await request(`/orgs/${org.id}/members`), ["alice", "root"]);
  const after = await request("/orgs");
  assert.equal(after.find((node) => node.id === org.id).memberCount, 2);
});

test("重复加入同一账号不会重复计数", async () => {
  await loginAsRoot();
  await post("/orgs/4/members", { userAccount: "root" });
  await post("/orgs/4/members", { userAccount: "root" });
  assert.deepEqual(await request("/orgs/4/members"), ["alice", "root"]);
});

test("移除成员后立即从成员端点消失，并同步成员数", async () => {
  await loginAsRoot();
  const res = await del("/orgs/4/members/alice");
  assert.deepEqual(res, { ok: true });
  assert.deepEqual(await request("/orgs/4/members"), []);
  const org = (await request("/orgs")).find((node) => node.id === 4);
  assert.equal(org.memberCount, 0);
});

test("不存在的账号不能创建组织成员关系", async () => {
  await loginAsRoot();
  await assert.rejects(
    post("/orgs/4/members", { userAccount: "missing.account" }),
    (error) => error.code === ERROR_CODE.USER_NOT_FOUND,
  );
  assert.equal((await request("/orgs/4/members")).includes("missing.account"), false);
});

test("新建根节点与子节点都返回空成员列表", async () => {
  await loginAsRoot();
  const root = await post("/orgs", { name: "新根", parentId: null });
  assert.equal(root.memberCount, 0);
  assert.deepEqual(await request(`/orgs/${root.id}/members`), []);
  const child = await post("/orgs", { name: "新子", parentId: root.id });
  assert.equal(child.parentId, root.id);
});

test("重命名走独立端点，同父重名被拒绝", async () => {
  await loginAsRoot();
  const renamed = await post("/orgs/4/rename", { name: "订单组-改名" });
  assert.equal(renamed.name, "订单组-改名");
  // 4 的兄弟节点是 支付部；改成兄弟名必须被拒绝
  await assert.rejects(
    post("/orgs/4/rename", { name: "支付部" }),
    (error) => error.code === ERROR_CODE.NAME_DUPLICATED,
  );
});

class StubApiError extends Error {
  constructor(message) {
    super(message);
    this.name = "ApiError";
  }
}

function orgView(request) {
  return setupComponent("../src/views/OrgView.vue", {}, {
    "../api": { api: request, ApiError: StubApiError },
  });
}

const stubApi = (handlers = {}) => async (path, options = {}) => {
  const method = options.method ?? "GET";
  const key = `${method} ${path}`;
  if (handlers[key]) return handlers[key](options);
  if (key === "GET /orgs") {
    return [
      { id: 1, name: "总部", parentId: null, leaf: false, memberCount: 1 },
      { id: 2, name: "订单组", parentId: 1, leaf: true, memberCount: 1 },
    ];
  }
  if (key === "GET /users") {
    return [
      { userAccount: "alice", role: "ADMIN", status: "ENABLED", mustChangePassword: false },
      { userAccount: "bob", role: "USER", status: "ENABLED", mustChangePassword: false },
      { userAccount: "carol", role: "USER", status: "DISABLED", mustChangePassword: false },
    ];
  }
  if (key === "GET /orgs/1/members") return ["alice"];
  if (key === "GET /orgs/2/members") return ["alice"];
  throw new Error(`unexpected request ${key}`);
};

test("成员列表默认收起，点击节点后展开成抽屉，不再为每个节点铺一个下拉框", async () => {
  const { source, compiled } = compileComponentTemplate("../src/views/OrgView.vue");
  assert.deepEqual(compiled.errors, []);
  assert.match(source, /class="org-drawer"/);
  assert.match(source, /v-if="isOpen\(row\.node\.id\)"/);
  assert.doesNotMatch(source, /<select[^>]*v-model="memberAccounts/, "不再为每个节点保留账号下拉框");

  const state = orgView(stubApi());
  await state.load();
  assert.equal(state.isOpen(1), false, "默认收起");
  state.toggle(1);
  assert.equal(state.isOpen(1), true);
  assert.deepEqual(state.membersOf(1), ["alice"]);
});

test("节点上的 + 菜单提供新增子节点与添加成员，弹窗内完成提交", async () => {
  const calls = [];
  const state = orgView(stubApi({
    "POST /orgs": (options) => { calls.push(options.body); return { id: 9 }; },
    "POST /orgs/2/members": (options) => { calls.push(options.body); return { ok: true }; },
  }));
  await state.load();

  state.openChildDialog(1);
  assert.equal(state.dialog.value?.kind, "child");
  state.childName.value = "新子组";
  await state.submitChild();
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0])), { name: "新子组", parentId: 1 });
  assert.equal(state.dialog.value, null, "提交成功后关闭弹窗");

  state.openMemberDialog(2);
  assert.equal(state.dialog.value?.kind, "member");
  // 已在该节点的账号不出现在可选列表里
  assert.deepEqual(Array.from(state.selectableAccounts.value, (a) => a.userAccount), ["bob", "carol"]);
  state.toggleSelected("bob");
  state.toggleSelected("carol");
  assert.deepEqual(Array.from(state.selected.value), ["bob", "carol"]);
  await state.submitMembers();
  assert.deepEqual(JSON.parse(JSON.stringify(calls.slice(1))), [{ userAccount: "bob" }, { userAccount: "carol" }]);
});

test("弹窗内保留失败原因，不关闭弹窗也不刷新成空白", async () => {
  const state = orgView(stubApi({
    "POST /orgs/2/members": () => {
      throw new StubApiError("账号不存在");
    },
  }));
  await state.load();
  state.openMemberDialog(2);
  state.toggleSelected("bob");
  await state.submitMembers();
  assert.equal(state.dialogError.value, "账号不存在");
  assert.equal(state.dialog.value?.kind, "member");
});

test("删除走确认弹窗：先取影响预览，再要求输入组织名", async () => {
  const calls = [];
  const state = orgView(stubApi({
    "GET /orgs/2/deletion-preview": () => ({
      orgName: "订单组",
      dashboards: [{ id: 1, name: "大盘", cardCount: 3 }],
      alertRuleCount: 2,
      memberCount: 1,
    }),
    "DELETE /orgs/2": (options) => { calls.push(options.query); return { ok: true }; },
  }));
  await state.load();
  await state.openDeleteDialog(2);
  assert.equal(state.preview.value?.orgName, "订单组");
  assert.equal(state.totalCards.value, 3);
  state.confirmName.value = "订单组";
  await state.submitDelete();
  assert.deepEqual(JSON.parse(JSON.stringify(calls)), [{ confirmName: "订单组" }]);
});

test("抽屉里的状态按钮调用账号启用/禁用端点", async () => {
  const calls = [];
  const state = orgView(stubApi({
    "POST /users/bob/disable": () => { calls.push("bob"); return { userAccount: "bob" }; },
  }));
  await state.load();
  await state.toggleAccountStatus("bob");
  assert.deepEqual(calls, ["bob"]);
});

test("抽屉里的移除按钮按账号调用撤权端点", async () => {
  const calls = [];
  const state = orgView(stubApi({
    "DELETE /orgs/2/members/alice": () => { calls.push("alice"); return { ok: true }; },
  }));
  await state.load();
  await state.removeMember(2, "alice");
  assert.deepEqual(calls, ["alice"]);
  assert.equal(state.isOpen(2), true, "移除后保持抽屉展开，便于继续操作");
});
