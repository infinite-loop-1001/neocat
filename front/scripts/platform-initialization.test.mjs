import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { test } from "node:test";
import { transformSync } from "esbuild";
import * as vueRouter from "vue-router";
import { createAccessGuard } from "../src/access-guard.ts";
import { loadInitializationStatus } from "../src/api/platform-initialization.ts";
import { ApiError } from "../src/api/error.ts";
import { ERROR_CODE } from "../src/api/error-codes.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";

// 运行生产路由注册和守卫，只替换浏览器history与页面加载，不复制路由/权限判断。
function makeRouter(request) {
  const source = readFileSync(new URL("../src/router.ts", import.meta.url), "utf8");
  const module = { exports: {} };
  const routesWithoutPages = (routes) => routes.map((route) => ({
    ...route,
    ...(route.component ? { component: {} } : {}),
    ...(route.children ? { children: routesWithoutPages(route.children) } : {}),
  }));
  runInNewContext(transformSync(source, { loader: "ts", format: "cjs" }).code, {
    module, exports: module.exports,
    require: (name) => {
      if (name === "./api") return { api: request };
      if (name === "./access-guard") return { createAccessGuard };
      if (name === "vue-router") return {
        ...vueRouter,
        createWebHistory: vueRouter.createMemoryHistory,
        createRouter: (options) => vueRouter.createRouter({ ...options, routes: routesWithoutPages(options.routes) }),
      };
      throw new Error(`Unexpected dependency: ${name}`);
    },
  });
  return module.exports.router;
}

function makeSetup(request, replace = async () => {}) {
  const navigations = [];
  const state = setupComponent("../src/views/InitializeView.vue", {}, {
    "vue-router": { useRouter: () => ({ replace: async (to) => { navigations.push(to); return replace(to); } }) },
    "../api": { api: request, ApiError },
    "../api/error-codes": { ERROR_CODE },
    "../api/platform-initialization": { loadInitializationStatus },
  });
  state.adminUsername.value = "it.super";
  state.adminPassword.value = "temporary-password";
  state.confirmPassword.value = "temporary-password";
  return { state, navigations };
}

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

for (const path of ["/login", "/", "/services", "/admin/users", "/change-password", "/initialize"]) {
  test(`未初始化：${path} 进入开通页，不查询会话`, async () => {
    const router = makeRouter(async (url) => {
      assert.equal(url, "/platform/init-status");
      return { initialized: false };
    });
    await router.push(path);
    assert.equal(router.currentRoute.value.path, "/initialize");
  });
}

test("已初始化访问开通页进入登录；正常登录页不要求会话", async () => {
  const router = makeRouter(async (url) => {
    assert.equal(url, "/platform/init-status");
    return { initialized: true };
  });
  await router.push("/initialize");
  assert.equal(router.currentRoute.value.path, "/login");
});

for (const [me, path, expected] of [
  [{ mustChangePassword: false }, "/services", "/services"],
  [{ mustChangePassword: true }, "/services", "/change-password"],
  [{ mustChangePassword: true }, "/change-password", "/change-password"],
  [{ mustChangePassword: false }, "/change-password", "/services"],
  [null, "/admin/users", "/login"],
]) {
  test(`初始化后保留会话/改密逻辑：${path} → ${expected} (${JSON.stringify(me)})`, async () => {
    const router = makeRouter(async (url) => {
      if (url === "/platform/init-status") return { initialized: true };
      assert.equal(url, "/me");
      if (!me) throw new ApiError(401, ERROR_CODE.UNAUTHENTICATED, "未登录");
      return me;
    });
    await router.push(path);
    assert.equal(router.currentRoute.value.path, expected);
  });
}

for (const response of [null, {}, { initialized: "false" }, { initialized: 1 }]) {
  test(`非法初始化状态 ${JSON.stringify(response)} 进入错误页而不是放行`, async () => {
    let calls = 0;
    const router = makeRouter(async (url) => {
      calls++;
      assert.equal(url, "/platform/init-status");
      return response;
    });
    await router.push("/login");
    assert.equal(router.currentRoute.value.path, "/initialization-error");
    assert.equal(calls, 1, "错误页不能再次检查导致循环");
  });
}

for (const initialized of [true, false]) {
  test(`状态查询失败后错误页重试，恢复到${initialized ? "登录" : "开通"}页`, async () => {
    let failed = true;
    const router = makeRouter(async () => {
      if (failed) throw new TypeError("network unavailable");
      return { initialized };
    });
    await router.push("/services");
    assert.equal(router.currentRoute.value.path, "/initialization-error");
    const state = setupComponent("../src/views/InitializationErrorView.vue", {}, {
      "vue-router": { useRouter: () => router },
    });
    await state.retry();
    assert.equal(router.currentRoute.value.path, "/initialization-error");
    assert.equal(state.busy.value, false);
    failed = false;
    await state.retry();
    assert.equal(router.currentRoute.value.path, initialized ? "/login" : "/initialize");
  });
}

test("每次导航重新读取状态，不使用持久化的已初始化假设", async () => {
  let initialized = true;
  const router = makeRouter(async (path) => path === "/me" ? { mustChangePassword: false } : { initialized });
  await router.push("/services");
  initialized = false;
  await router.push("/alerts");
  assert.equal(router.currentRoute.value.path, "/initialize");
});

test("等待初始化状态时不能先进入登录或业务路由", async () => {
  const status = deferred();
  const router = makeRouter(async () => status.promise);
  const navigation = router.push("/login");
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(router.currentRoute.value.matched.length, 0);
  status.resolve({ initialized: false });
  await navigation;
  assert.equal(router.currentRoute.value.path, "/initialize");
});

for (const [field, value, message] of [
  ["adminUsername", "   ", "请填写"],
  ["timezone", "Invalid/Zone", "有效时区"],
  ["adminPassword", "1234567", "至少 8 位"],
  ["confirmPassword", "different", "不一致"],
]) {
  test(`表单校验 ${field} 不产生初始化请求`, async () => {
    const { state, navigations } = makeSetup(async () => { assert.fail("非法输入不能请求服务器"); });
    state[field].value = value;
    await state.submit();
    assert.ok(state.error.value.includes(message));
    assert.equal(state.busy.value, false);
    assert.equal(navigations.length, 0);
  });
}

for (const timezone of ["Asia/Shanghai", "UTC"]) {
  test(`合法开通 ${timezone}：扁平请求、密码不裁剪、成功后清密码且不自动登录`, async () => {
    const requests = [];
    const { state, navigations } = makeSetup(async (path, options) => {
      requests.push({ path, options });
      return path === "/platform/init-status" ? { initialized: false } : { initialized: true };
    });
    state.timezone.value = timezone;
    state.adminUsername.value = " it.super ";
    state.adminPassword.value = " 123456 "; // 恰8位，首尾空格属于密码。
    state.confirmPassword.value = state.adminPassword.value;
    await state.submit();
    assert.equal(requests.length, 2);
    assert.deepEqual(JSON.parse(JSON.stringify(requests[1])), {
      path: "/platform/initialize", options: { method: "POST", body: {
        timezone, adminUsername: "it.super", adminPassword: " 123456 ",
      } },
    });
    assert.equal(navigations[0].path, "/login");
    assert.equal(navigations[0].query.setup, "complete");
    assert.equal(state.adminPassword.value, "");
    assert.equal(state.confirmPassword.value, "");
    assert.equal(state.busy.value, false);
  });
}

test("提交检查和初始化期间重复点击都只产生一次POST", async () => {
  const status = deferred();
  const post = deferred();
  const requests = [];
  const { state } = makeSetup(async (path) => {
    requests.push(path);
    return path === "/platform/init-status" ? status.promise : post.promise;
  });
  const pending = state.submit();
  await state.submit();
  assert.equal(requests.length, 1);
  assert.equal(state.busy.value, true);
  status.resolve({ initialized: false });
  await new Promise((resolve) => setImmediate(resolve));
  await state.submit();
  assert.deepEqual(requests, ["/platform/init-status", "/platform/initialize"]);
  post.resolve({ initialized: true });
  await pending;
  assert.equal(state.busy.value, false);
});

test("另一窗口已完成开通：提交前发现则不POST且不声称创建成功", async () => {
  const { state, navigations } = makeSetup(async (path) => {
    assert.equal(path, "/platform/init-status");
    return { initialized: true };
  });
  await state.submit();
  assert.equal(navigations[0].query.setup, "already");
  assert.equal(state.adminPassword.value, "");
});

test("并发开通返回409/70001：清密码，引导已有账号登录", async () => {
  const { state, navigations } = makeSetup(async (path) => {
    if (path === "/platform/init-status") return { initialized: false };
    throw new ApiError(409, ERROR_CODE.ALREADY_INITIALIZED, "平台已初始化");
  });
  await state.submit();
  assert.equal(navigations[0].query.setup, "already");
  assert.equal(state.adminPassword.value, "");
});

test("提交前状态不可用：保留可编辑信息、不POST、不放行", async () => {
  const { state, navigations } = makeSetup(async (path) => {
    assert.equal(path, "/platform/init-status");
    throw new Error("offline");
  });
  await state.submit();
  assert.match(state.error.value, /未提交开通请求/);
  assert.equal(state.adminUsername.value, "it.super");
  assert.equal(state.busy.value, false);
  assert.equal(navigations.length, 0);
});

test("服务端拒绝：显示错误，不自动重发或跳登录", async () => {
  let posts = 0;
  const { state, navigations } = makeSetup(async (path) => {
    if (path === "/platform/init-status") return { initialized: false };
    posts++;
    throw new ApiError(400, ERROR_CODE.INVALID_PARAM, "输入不合法");
  });
  await state.submit();
  assert.equal(posts, 1);
  assert.equal(state.error.value, "输入不合法");
  assert.equal(navigations.length, 0);
});

test("POST结果不确定：用户重试先查状态，已成功则不重复POST", async () => {
  let initialized = false;
  let posts = 0;
  const { state, navigations } = makeSetup(async (path) => {
    if (path === "/platform/init-status") return { initialized };
    posts++;
    initialized = true;
    throw new TypeError("response lost");
  });
  await state.submit();
  assert.match(state.error.value, /未能确认开通结果/);
  assert.equal(navigations.length, 0);
  assert.equal(posts, 1);
  await state.submit();
  assert.equal(posts, 1);
  assert.equal(navigations[0].query.setup, "already");
});

test("初始化返回未完成档案不能误报开通成功", async () => {
  const { state, navigations } = makeSetup(async () => ({ initialized: false }));
  await state.submit();
  assert.match(state.error.value, /未能确认开通结果/);
  assert.equal(navigations.length, 0);
});

test("错误页重试进行中禁止重复操作", async () => {
  const pending = deferred();
  let count = 0;
  const state = setupComponent("../src/views/InitializationErrorView.vue", {}, {
    "vue-router": { useRouter: () => ({ replace: async () => { count++; await pending.promise; } }) },
  });
  const retry = state.retry();
  await state.retry();
  assert.equal(count, 1);
  assert.equal(state.busy.value, true);
  pending.resolve();
  await retry;
  assert.equal(state.busy.value, false);
});

test("开通结果提示不携带凭据，正常登录仍进入强制改密", async () => {
  const calls = [];
  const navigations = [];
  const route = { query: { setup: "complete" } };
  const state = setupComponent("../src/views/LoginView.vue", {}, {
    "vue-router": { useRoute: () => route, useRouter: () => ({ push: async (to) => navigations.push(to) }) },
    "../api": { ApiError, api: async (...args) => { calls.push(args); return { mustChangePassword: true }; } },
  });
  assert.match(state.setupMessage.value, /初始化成功/);
  state.username.value = "it.super";
  state.password.value = "temporary-password";
  await state.submit();
  assert.deepEqual(navigations, ["/change-password"]);
  assert.equal(calls[0][0], "/login");
  assert.equal(calls[0][1].body.userAccount, "it.super");
  const already = setupComponent("../src/views/LoginView.vue", {}, {
    "vue-router": { useRoute: () => ({ query: { setup: "already" } }), useRouter: () => ({}) },
    "../api": { ApiError, api: async () => {} },
  });
  assert.match(already.setupMessage.value, /本次未创建新账号/);
});

test("真实模板包含开通表单、校验、忙状态、错误/重试绑定和启动等待提示", () => {
  const form = compileComponentTemplate("../src/views/InitializeView.vue");
  assert.deepEqual(form.compiled.errors, []);
  assert.match(form.source, /@submit\.prevent="submit"/);
  for (const id of ["timezone", "admin-account", "admin-password", "confirm-password"]) {
    assert.ok(form.source.includes(`for="${id}"`));
    assert.ok(form.source.includes(`id="${id}"`));
  }
  assert.equal((form.source.match(/<PasswordField\b/g) ?? []).length, 2);
  assert.match(form.source, /minlength="8"/);
  assert.match(form.source, /:disabled="busy"/);
  assert.match(form.source, /一期不可修改/);
  const error = compileComponentTemplate("../src/views/InitializationErrorView.vue");
  assert.deepEqual(error.compiled.errors, []);
  assert.match(error.source, /@click="retry"/);
  assert.match(error.source, /role="alert"/);
  const app = compileComponentTemplate("../src/App.vue");
  assert.deepEqual(app.compiled.errors, []);
  assert.match(app.source, /v-if="Component"/);
  assert.match(app.source, /正在检查平台状态/);
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  assert.match(css, /\.login-panel\s*\{[^}]*width: min\(400px, 100%\)/);
});
