import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { test } from "node:test";
import { transformSync } from "esbuild";
import * as vueRouter from "vue-router";
import { createAccessGuard } from "../src/access-guard.ts";
import { safeRedirect } from "../src/navigation-target.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";

// 只用生产路由表与守卫，替换页面组件与浏览器 history，避免复制一份判断逻辑。
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

const initializedThen = (me) => async (path) => {
  if (path === "/platform/init-status") return { initialized: true };
  assert.equal(path, "/me");
  if (me instanceof Error) throw me;
  return me;
};

test("刷新受保护页面且会话有效时停在原页面，不回到首页", async () => {
  for (const path of ["/admin/orgs", "/dashboards", "/alerts", "/svc/order/metric"]) {
    const router = makeRouter(initializedThen({ mustChangePassword: false }));
    await router.push(path);
    assert.equal(router.currentRoute.value.path, path);
  }
});

test("会话失效时带上原页面地址，而不是静默回到首页", async () => {
  const router = makeRouter(initializedThen(new Error("401")));
  await router.push("/admin/orgs");
  const current = router.currentRoute.value;
  assert.equal(current.path, "/login");
  assert.equal(current.query.redirect, "/admin/orgs");
});

test("保留原页面的 query 与 hash，刷新后回到同一视图", async () => {
  const router = makeRouter(initializedThen(new Error("401")));
  await router.push("/svc/order/metric?range=HOUR:1&filters=city%3D上海#top");
  assert.equal(router.currentRoute.value.path, "/login");
  assert.equal(router.currentRoute.value.query.redirect, "/svc/order/metric?range=HOUR:1&filters=city%3D上海#top");
});

test("强制改密时同样记住原页面，改密完成后可回跳", async () => {
  const router = makeRouter(initializedThen({ mustChangePassword: true }));
  await router.push("/admin/orgs");
  assert.equal(router.currentRoute.value.path, "/change-password");
  assert.equal(router.currentRoute.value.query.redirect, "/admin/orgs");
});

test("登录页自身不追加 redirect，避免自我循环", async () => {
  const router = makeRouter(initializedThen(new Error("401")));
  await router.push("/login");
  assert.equal(router.currentRoute.value.path, "/login");
  assert.equal(router.currentRoute.value.query.redirect, undefined);
});

test("只接受站内路径作为回跳目标，拒绝协议相对与外站地址", () => {
  assert.equal(safeRedirect("/admin/orgs"), "/admin/orgs");
  assert.equal(safeRedirect("/svc/order/transaction?x=1"), "/svc/order/transaction?x=1");
  assert.equal(safeRedirect("//evil.example.com"), null);
  assert.equal(safeRedirect("https://evil.example.com"), null);
  assert.equal(safeRedirect("admin/orgs"), null);
  assert.equal(safeRedirect("/login"), null);
  assert.equal(safeRedirect(["/admin/orgs"]), null);
  assert.equal(safeRedirect(undefined), null);
});

function loginSetup(api, query = { redirect: "/admin/orgs" }, navigations = []) {
  const state = setupComponent("../src/views/LoginView.vue", {}, {
    "vue-router": {
      useRouter: () => ({ push: async (to) => { navigations.push(to); } }),
      useRoute: () => ({ query, path: "/login" }),
    },
    "../api": { api, ApiError: class extends Error {} },
    "../navigation-target": { safeRedirect },
  });
  state.username.value = "it.admin";
  state.password.value = "temporary-password";
  return { state, navigations };
}

const loginSetupWith = (query, api, navigations) => loginSetup(api, query, navigations);

test("登录成功后回到刷新前那一页，不按默认落点进首页", async () => {
  const { state, navigations } = loginSetup(async () => ({
    mustChangePassword: false,
    entry: { type: "SERVICE_TRANSACTION", service: "order", kind: "TRANSACTION" },
  }));
  await state.submit();
  assert.deepEqual(navigations, ["/admin/orgs"]);
});

test("没有回跳目标时仍按最近访问服务落点", async () => {
  const navigations = [];
  const { state } = loginSetupWith({}, async () => ({
    mustChangePassword: false,
    entry: { type: "SERVICE_TRANSACTION", service: "order" },
  }), navigations);
  state.username.value = "it.admin";
  state.password.value = "temporary-password";
  await state.submit();
  assert.equal(navigations.at(-1), "/svc/order/transaction");
});

test("首登改密时把回跳目标传给改密页", async () => {
  const { state, navigations } = loginSetup(async () => ({
    mustChangePassword: true,
    entry: { type: "PASSWORD_CHANGE" },
  }));
  await state.submit();
  assert.deepEqual(JSON.parse(JSON.stringify(navigations)), [
    { path: "/change-password", query: { redirect: "/admin/orgs" } },
  ]);
});

test("改密完成后回到刷新前那一页", async () => {
  const navigations = [];
  const state = setupComponent("../src/views/ChangePasswordView.vue", {}, {
    "vue-router": {
      useRouter: () => ({ push: async (to) => { navigations.push(to); } }),
      useRoute: () => ({ query: { redirect: "/admin/orgs" }, path: "/change-password" }),
    },
    "../api": { api: async () => ({ ok: true }), ApiError: class extends Error {} },
    "../navigation-target": { safeRedirect },
  });
  state.oldPassword.value = "temporary-password";
  state.newPassword.value = "another-password";
  state.confirmPassword.value = "another-password";
  await state.submit();
  assert.deepEqual(navigations, ["/admin/orgs"]);
});

test("登录页与改密页都引用回跳模块，不在页面内各写一份判断", () => {
  for (const view of ["../src/views/LoginView.vue", "../src/views/ChangePasswordView.vue"]) {
    const { compiled } = compileComponentTemplate(view);
    assert.deepEqual(compiled.errors, []);
    const source = readFileSync(new URL(view, import.meta.url), "utf8");
    assert.match(source, /from "\.\.\/navigation-target"/, view);
    assert.match(source, /safeRedirect\(route\.query\.redirect\)/, view);
  }
});
