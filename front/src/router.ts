import { createRouter, createWebHistory } from "vue-router";
import { api } from "./api";

/**
 * 路由表严格对应 PRD 能力集（技术方案 03-api-contract.md §8）。
 *
 * 已按 PRD 明确排除项清理：
 * - 应用总览（OverviewView）
 * - Business 漏斗（BusinessView）
 * - Hosts（Heartbeat 为 JVM 实例维度，已改为 heartbeat）
 * - 全局大盘（大盘只挂叶子组织）
 */
export const router = createRouter({
  history: createWebHistory(),
  linkActiveClass: "is-active",
  linkExactActiveClass: "is-active",
  routes: [
    { path: "/login", name: "login", component: () => import("./views/LoginView.vue") },
    { path: "/change-password", name: "change-password", component: () => import("./views/ChangePasswordView.vue") },
    {
      path: "/",
      component: () => import("./views/ShellView.vue"),
      children: [
        { path: "", redirect: "/services" },
        { path: "services", name: "services", component: () => import("./views/ServiceListView.vue") },
        { path: "svc/:service/transaction", name: "transaction", component: () => import("./views/ReportView.vue"), props: { kind: "TRANSACTION" } },
        { path: "svc/:service/transaction/:type", name: "transaction-names", component: () => import("./views/NameMetricsView.vue"), props: { kind: "TRANSACTION" } },
        { path: "svc/:service/transaction/:type/:name", name: "transaction-series", component: () => import("./views/SeriesView.vue"), props: { kind: "TRANSACTION" } },
        { path: "svc/:service/event", name: "event", component: () => import("./views/ReportView.vue"), props: { kind: "EVENT" } },
        { path: "svc/:service/event/:type", name: "event-names", component: () => import("./views/NameMetricsView.vue"), props: { kind: "EVENT" } },
        { path: "svc/:service/event/:type/:name", name: "event-series", component: () => import("./views/SeriesView.vue"), props: { kind: "EVENT" } },
        { path: "svc/:service/problem", name: "problem", component: () => import("./views/ProblemView.vue") },
        { path: "svc/:service/problem/:type/:name", name: "problem-series", component: () => import("./views/SeriesView.vue"), props: { kind: "PROBLEM" } },
        { path: "svc/:service/heartbeat", name: "heartbeat", component: () => import("./views/HeartbeatView.vue") },
        { path: "svc/:service/metric", name: "metric", component: () => import("./views/MetricView.vue") },
        { path: "svc/:service/metric/:metric", name: "metric-detail", component: () => import("./views/MetricView.vue") },
        { path: "svc/:service/dependency", name: "dependency", component: () => import("./views/DependencyView.vue") },
        { path: "trace/:messageId", name: "trace", component: () => import("./views/TraceView.vue"), props: true },
        { path: "dashboards", name: "dashboards", component: () => import("./views/DashboardView.vue") },
        { path: "dashboards/:id", name: "dashboard", component: () => import("./views/DashboardDetailView.vue") },
        { path: "alerts", name: "alerts", component: () => import("./views/AlertsView.vue") },
        { path: "admin/users", name: "admin-users", component: () => import("./views/UsersView.vue") },
        { path: "admin/orgs", name: "admin-orgs", component: () => import("./views/OrgView.vue") },
        { path: "admin/platform", name: "admin-platform", component: () => import("./views/PlatformView.vue") },
      ],
    },
  ],
});

router.beforeEach(async (to) => {
  if (to.path === "/login") return true;
  try {
    const me = await api<{ mustChangePassword: boolean }>("/me");
    // 强制改密会话只能进入改密页（PRD 01 §4.3）
    if (me.mustChangePassword && to.path !== "/change-password") {
      return "/change-password";
    }
    if (!me.mustChangePassword && to.path === "/change-password") {
      return "/services";
    }
    return true;
  } catch {
    return "/login";
  }
});
