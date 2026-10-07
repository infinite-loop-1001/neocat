/**
 * Mock 路由（技术方案 03-api-contract.md §9）。
 *
 * 覆盖接口全集，且**报表数据包含 null 缺口与部分覆盖桶**，
 * 以便在 mock 开关打开的状态下验证「缺数≠0」这条 PRD 口径是否被前端正确处理。
 *
 * 状态在模块内可变，因此 mock 模式下可以真实地走完
 * 「登录 → 报表 → 大盘 → 告警」全流程（含创建、启停、删除）。
 */

import { ApiError } from "../api/error";
import { ERROR_CODE as E } from "../api/error-codes";
import * as d from "./dataset";
import { metricCount, metricLabels, metricsForService } from "./metric-dataset";
import { decodeFilters } from "../api/metric-filters";
import { readSession, writeSession } from "./session";
import type {
  Account,
  AlertRule,
  Card,
  Dashboard,
  OrgNode,
  Sample,
} from "./model";

interface MockState {
  session: { username: string; role: string } | null;
  mustChangePassword: boolean;
  accounts: Account[];
  orgs: OrgNode[];
  myLeaves: number[];
  dashboards: Dashboard[];
  cards: Card[];
  alerts: AlertRule[];
  platform: typeof d.platform;
  seq: { account: number; org: number; dashboard: number; card: number; alert: number };
}

const restored = readSession();

const state: MockState = {
  session: restored ? { username: restored.username, role: restored.role } : null,
  mustChangePassword: restored?.mustChangePassword ?? false,
  accounts: [...d.accounts],
  orgs: [...d.orgs],
  myLeaves: [...d.myLeaves],
  dashboards: [...d.dashboards],
  cards: [...d.cards],
  alerts: [...d.alerts],
  platform: { ...d.platform, slow: { ...d.platform.slow }, channels: { ...d.platform.channels } },
  seq: { account: 100, org: 100, dashboard: 100, card: 100, alert: 100 },
};

export function resetMockState(): void {
  state.session = null;
  state.mustChangePassword = false;
  writeSession(null);
  state.accounts = [...d.accounts];
  state.orgs = [...d.orgs];
  state.myLeaves = [...d.myLeaves];
  state.dashboards = [...d.dashboards];
  state.cards = [...d.cards];
  state.alerts = [...d.alerts];
}

/** 对外返回的账号视图：绝不包含口令（即使是 mock）。 */
function publicAccount(account: Account) {
  const { password: _password, ...rest } = account;
  return rest;
}

function requireSession() {
  if (!state.session) {
    throw new ApiError(401, E.UNAUTHENTICATED, "未登录或会话已失效");
  }
}

function requireHasPasswordChanged() {
  if (state.mustChangePassword) {
    throw new ApiError(403, E.PASSWORD_CHANGE_REQUIRED, "必须先修改初始密码");
  }
}

function requireAdmin() {
  if (state.session?.role !== "ADMIN" && state.session?.role !== "SUPER_ADMIN") {
    throw new ApiError(403, E.FORBIDDEN, "需要管理员权限");
  }
}

function leafIds(): number[] {
  return state.orgs.filter((o) => o.leaf).map((o) => o.id);
}

function isLeaf(orgId: number): boolean {
  return leafIds().includes(orgId);
}

function requireLeafMember(orgId: number) {
  requireSession();
  if (!state.myLeaves.includes(orgId)) {
    throw new ApiError(403, E.NOT_ORG_MEMBER, "不是该组织的有效成员");
  }
}

export async function mockRequest<T>(url: string, init: { method: string; body?: string }): Promise<T> {
  const parsed = new URL(url, "http://mock.local");
  const path = parsed.pathname.replace(/^\/api/, "").replace(/\/$/, "") || "/";
  const method = init.method.toUpperCase();
  const query = parsed.searchParams;
  const body = init.body ? (JSON.parse(init.body) as Record<string, unknown>) : {};

  // ── 平台初始化与登录（匿名） ─────────────────────────────
  if (path === "/platform/init-status" && method === "GET") {
    return { initialized: state.platform.initialized } as T;
  }
  if (path === "/platform/initialize" && method === "POST") {
    if (state.platform.initialized) {
      throw new ApiError(409, E.ALREADY_INITIALIZED, "平台已初始化");
    }
    state.platform.initialized = true;
    return state.platform as T;
  }
  if (path === "/login" && method === "POST") {
    const username = String(body.username ?? "");
    const password = String(body.password ?? "");
    const account = state.accounts.find((a) => a.username === username);
    // 三种失败原因（不存在 / 口令不符 / 已禁用）统一返回同一错误码，
    // 不暴露账号是否存在（PRD 01 §4.1）
    if (!account || account.status !== "ENABLED" || account.password !== password) {
      throw new ApiError(401, E.BAD_CREDENTIALS, "用户名或密码错误");
    }
    state.session = { username: account.username, role: account.role };
    state.mustChangePassword = account.mustChangePassword;
    writeSession({
      username: account.username,
      role: account.role,
      mustChangePassword: account.mustChangePassword,
    });
    return {
      user: { id: account.id, username: account.username, role: account.role },
      mustChangePassword: account.mustChangePassword,
      entry: account.mustChangePassword
        ? { type: "PASSWORD_CHANGE" }
        : { type: "SERVICE_TRANSACTION", service: "order", kind: "TRANSACTION" },
    } as T;
  }
  if (path === "/logout" && method === "POST") {
    state.session = null;
    state.mustChangePassword = false;
    writeSession(null);
    return { ok: true } as T;
  }

  requireSession();

  if (path === "/me" && method === "GET") {
    return {
      username: state.session?.username,
      role: state.session?.role,
      mustChangePassword: state.mustChangePassword,
    } as T;
  }
  if (path === "/me/password" && method === "POST") {
    const oldPassword = String(body.oldPassword ?? "");
    const newPassword = String(body.newPassword ?? "");
    if (oldPassword === newPassword) {
      throw new ApiError(400, E.PASSWORD_UNCHANGED, "新密码不能与当前密码相同");
    }
    if (newPassword.length < 8) {
      throw new ApiError(400, E.PASSWORD_TOO_SHORT, "密码至少 8 位");
    }
    state.mustChangePassword = false;
    if (state.session) {
      writeSession({
        username: state.session.username,
        role: state.session.role,
        mustChangePassword: false,
      });
    }
    return { ok: true } as T;
  }

  // 强制改密会话只允许改密、登出、查询自身
  if (state.mustChangePassword && !["/me", "/logout", "/me/password"].includes(path)) {
    throw new ApiError(403, E.PASSWORD_CHANGE_REQUIRED, "必须先修改初始密码");
  }

  // ── 账号管理 ─────────────────────────────────────────────
  if (path === "/users" && method === "GET") {
    requireAdmin();
    return state.accounts.map(publicAccount) as T;
  }
  if (path === "/users" && method === "POST") {
    requireAdmin();
    const username = String(body.username ?? "");
    const password = String(body.password ?? "");
    if (password.length < 8) {
      throw new ApiError(400, E.PASSWORD_TOO_SHORT, "密码至少 8 位");
    }
    if (state.accounts.some((a) => a.username === username)) {
      throw new ApiError(409, E.USER_EXISTS, "用户名已存在");
    }
    const created: Account = {
      id: ++state.seq.account,
      username,
      password,
      role: "USER",
      status: "ENABLED",
      mustChangePassword: true,
    };
    state.accounts.push(created);
    return publicAccount(created) as T;
  }
  if (/^\/users\/\d+\/password\/reset$/.test(path) && method === "POST") {
    requireAdmin();
    const id = Number(path.split("/")[2]);
    const account = state.accounts.find((a) => a.id === id);
    if (!account) throw new ApiError(404, E.USER_NOT_FOUND, "账号不存在");
    if (String(body.password ?? "").length < 8) {
      throw new ApiError(400, E.PASSWORD_TOO_SHORT, "密码至少 8 位");
    }
    account.mustChangePassword = true;
    return publicAccount(account) as T;
  }
  if (/^\/users\/\d+\/role$/.test(path) && method === "POST") {
    if (state.session?.role !== "SUPER_ADMIN") {
      throw new ApiError(403, E.FORBIDDEN, "只有超级管理员可以授予或取消 ADMIN");
    }
    const id = Number(path.split("/")[2]);
    const account = state.accounts.find((a) => a.id === id);
    if (!account) throw new ApiError(404, E.USER_NOT_FOUND, "账号不存在");
    account.role = body.role === "ADMIN" ? "ADMIN" : "USER";
    return publicAccount(account) as T;
  }
  if (/^\/users\/\d+\/(disable|enable)$/.test(path) && method === "POST") {
    requireAdmin();
    const parts = path.split("/");
    const id = Number(parts[2]);
    const account = state.accounts.find((a) => a.id === id);
    if (!account) throw new ApiError(404, E.USER_NOT_FOUND, "账号不存在");
    account.status = parts[3] === "disable" ? "DISABLED" : "ENABLED";
    if (account.status === "DISABLED") {
      // 禁用即移除告警收件人；启用不恢复
      state.alerts.forEach((rule) => {
        rule.recipients = rule.recipients.filter((r) => r !== id);
      });
    }
    return publicAccount(account) as T;
  }

  // ── 组织 ─────────────────────────────────────────────────
  if (path === "/orgs" && method === "GET") {
    return state.orgs as T;
  }
  if (path === "/orgs/mine" && method === "GET") {
    return state.myLeaves as T;
  }
  if (path === "/orgs" && method === "POST") {
    requireAdmin();
    const name = String(body.name ?? "");
    const parentId = body.parentId == null ? null : Number(body.parentId);
    if (state.orgs.some((o) => o.name === name && o.parentId === parentId)) {
      throw new ApiError(409, E.NAME_DUPLICATED, "同一父节点下已存在同名组织");
    }
    if (parentId != null) {
      const parent = state.orgs.find((o) => o.id === parentId);
      if (!parent) throw new ApiError(404, E.PARENT_ORG_NOT_FOUND, "父节点不存在");
      // 叶子若已有大盘或组织告警，禁止新增子节点
      const hasResources =
        state.dashboards.some((x) => x.orgId === parentId) ||
        state.alerts.some((r) => r.orgId === parentId);
      if (parent.leaf && hasResources) {
        throw new ApiError(422, E.LEAF_HAS_RESOURCES, "该叶子组织已存在大盘或组织告警，不能新增子节点");
      }
      parent.leaf = false;
    }
    const created: OrgNode = { id: ++state.seq.org, name, parentId, leaf: true, memberCount: 0 };
    state.orgs.push(created);
    return created as T;
  }
  if (/^\/orgs\/\d+\/members$/.test(path) && method === "POST") {
    requireAdmin();
    const id = Number(path.split("/")[2]);
    const org = state.orgs.find((o) => o.id === id);
    if (!org) throw new ApiError(404, E.ORG_NOT_FOUND, "组织不存在");
    org.memberCount += 1;
    if (org.leaf) state.myLeaves = Array.from(new Set([...state.myLeaves, org.id]));
    return org as T;
  }
  if (/^\/orgs\/\d+\/deletion-preview$/.test(path) && method === "GET") {
    requireAdmin();
    const id = Number(path.split("/")[2]);
    const org = state.orgs.find((o) => o.id === id);
    if (!org) throw new ApiError(404, E.ORG_NOT_FOUND, "组织不存在");
    const dashboards = state.dashboards.filter((x) => x.orgId === id);
    return {
      orgName: org.name,
      dashboards: dashboards.map((x) => ({
        id: x.id,
        name: x.name,
        cardCount: state.cards.filter((c) => c.dashboardId === x.id).length,
      })),
      alertRuleCount: state.alerts.filter((r) => r.orgId === id).length,
      memberCount: org.memberCount,
    } as T;
  }
  if (/^\/orgs\/\d+$/.test(path) && method === "DELETE") {
    requireAdmin();
    const id = Number(path.split("/")[2]);
    if (state.orgs.some((o) => o.parentId === id)) {
      throw new ApiError(409, E.HAS_CHILDREN, "非叶子节点存在子节点，禁止删除");
    }
    const org = state.orgs.find((o) => o.id === id);
    if (!org) throw new ApiError(404, E.ORG_NOT_FOUND, "组织不存在");
    if (query.get("confirmName") !== org.name) {
      throw new ApiError(409, E.CONFIRM_NAME_MISMATCH, "二次确认名称不匹配");
    }
    // 原子级联删除大盘、卡片与组织告警
    state.dashboards = state.dashboards.filter((x) => x.orgId !== id);
    state.cards = state.cards.filter((c) => state.dashboards.some((x) => x.id === c.dashboardId));
    state.alerts = state.alerts.filter((r) => r.orgId !== id);
    state.orgs = state.orgs.filter((o) => o.id !== id);
    state.myLeaves = state.myLeaves.filter((x) => x !== id);
    return { ok: true } as T;
  }

  // ── 目录 ─────────────────────────────────────────────────
  if (path === "/services" && method === "GET") {
    return d.services as T;
  }
  if (/^\/services\/[^/]+\/instances$/.test(path) && method === "GET") {
    const service = path.split("/")[2];
    const found = d.services.find((s) => s.name === service);
    return (found?.instances ?? []) as T;
  }

  // ── 报表 ─────────────────────────────────────────────────
  if (path === "/reports/transaction/types" && method === "GET") {
    return d.transactionTypes as T;
  }
  if (path === "/reports/transaction/names" && method === "GET") {
    const type = query.get("type") ?? "URL";
    return d.namesInWindow("TRANSACTION", type, query.get("range") ?? "RECENT_1H") as T;
  }
  if (path === "/reports/event/types" && method === "GET") {
    return d.eventTypes as T;
  }
  if (path === "/reports/event/names" && method === "GET") {
    const type = query.get("type") ?? "business";
    return d.namesInWindow("EVENT", type, query.get("range") ?? "RECENT_1H") as T;
  }
  if (path === "/reports/problem/categories" && method === "GET") {
    return d.problemCategories as T;
  }
  if (path === "/reports/problem/names" && method === "GET") {
    const category = query.get("category") ?? "EXCEPTION";
    return (d.problemNames[category] ?? []) as T;
  }
  if (path === "/reports/series" && method === "GET") {
    const type = query.get("type") ?? "URL";
    const name = query.get("name") ?? "POST /orders";
    const stat = query.get("stat") ?? "HITS";
    const range = query.get("range") ?? "RECENT_1H";
    const bucket = query.get("bucket");
    const kind = query.get("kind") ?? "TRANSACTION";
    const mom = query.get("mom");
    const window = d.seriesWindow(range, Number(bucket) || 0, d.now);
    const seed = `${type}${name}${stat}`.length;
    const base = 40 + (seed % 60);
    return {
      service: query.get("service") ?? "order",
      kind,
      type,
      name,
      stat,
      bucketSeconds: window.bucketSeconds,
      points: d.seriesInWindow(d.now, window, base),
      // 环比：按整日偏移生成对比窗口，桶数与当前窗口一致（桶序号对齐）
      mom: mom && d.momSupported(kind) ? { kind: mom, points: d.momPoints(window, mom, base) } : null,
    } as T;
  }
  if (path === "/reports/heartbeat/metrics" && method === "GET") {
    return d.heartbeatMetrics as T;
  }
  if (path === "/reports/heartbeat/series" && method === "GET") {
    const metric = query.get("metric") ?? "heap-used";
    const range = query.get("range") ?? "RECENT_1H";
    const instances = (query.get("instances") ?? "").split(",").filter(Boolean);
    return {
      service: query.get("service") ?? "order",
      metric,
      bucketSeconds: d.seriesWindow(range, 0, d.now).bucketSeconds,
      series: d.heartbeatSeriesFor(metric, range, instances),
      // 一期不做 Heartbeat 环比（PRD 03 §10）
      mom: null,
    } as T;
  }
  if (path === "/reports/heartbeat/instances" && method === "GET") {
    const metric = query.get("metric") ?? "heap-used";
    const range = query.get("range") ?? "RECENT_1H";
    return d.heartbeatInstancesFor(metric, range) as T;
  }
  if (path === "/reports/metric/list" && method === "GET") {
    return d.metricList as T;
  }
  // Frontend/mock-only Metric count contract; no fallback to generic fake series.
  if (path === "/reports/metric/metrics" && method === "GET") {
    return metricsForService(query.get("service") ?? "order") as T;
  }
  if ((path === "/reports/metric/labels" || path === "/reports/metric/count") && method === "GET") {
    const service = query.get("service") ?? "order";
    const metric = query.get("metric") ?? "";
    if (!metricsForService(service).some((item) => item.name === metric)) {
      throw new ApiError(404, E.NOT_FOUND, "当前服务没有该 Metric");
    }
    if (path === "/reports/metric/labels") return metricLabels(metric) as T;
    return metricCount(metric, query.get("range") ?? "RECENT_1H", decodeFilters(query.get("filters")), d.now,
      query.get("mockMetric") ?? undefined) as T;
  }
  if (path === "/reports/dependency/downstream" && method === "GET") {
    return d.dependencies.downstream as T;
  }
  if (path === "/reports/dependency/upstream" && method === "GET") {
    return d.dependencies.upstream as T;
  }
  if (path === "/reports/samples" && method === "GET") {
    // Problem 页要按聚合键展示取样条带：有 name 且 kind=PROBLEM 时按 name 生成，
    // 否则保持固定的 30 条样例（Transaction/Event 趋势页依赖它）。
    // 条数取该聚合名的总次数与 30 的较小值，与真实口径一致。
    const name = query.get("name");
    const kind = query.get("kind");
    const type = query.get("type");
    const scoped = name && kind === "PROBLEM";
    if (scoped) {
      const total = d.problemNames[type ?? ""]?.find((row) => row.name === name)?.total ?? 30;
      return d.samplesForName(name, total) as T;
    }
    return d.samples as T;
  }
  if (/^\/traces\/[^/]+$/.test(path) && method === "GET") {
    const messageId = path.split("/")[2];
    const known = [...d.samples, ...d.problemSampleIndex()].find((s) => s.messageId === messageId);
    if (known && !known.traceAvailable) {
      throw new ApiError(410, E.TRACE_EXPIRED, "原始树已超过留存期，不可打开");
    }
    return { ...d.trace, messageId } as T;
  }

  // ── 大盘与卡片 ───────────────────────────────────────────
  if (path === "/dashboards" && method === "GET") {
    const orgId = query.get("orgId");
    const list = orgId
      ? state.dashboards.filter((x) => x.orgId === Number(orgId))
      : state.dashboards.filter((x) => state.myLeaves.includes(x.orgId));
    return list as T;
  }
  if (path === "/dashboards" && method === "POST") {
    const orgId = Number(body.orgId);
    if (!isLeaf(orgId)) throw new ApiError(409, E.NOT_LEAF, "只有叶子组织可以挂载大盘");
    requireLeafMember(orgId);
    const created: Dashboard = { id: ++state.seq.dashboard, orgId, name: String(body.name ?? "新大盘") };
    state.dashboards.push(created);
    return created as T;
  }
  if (/^\/dashboards\/\d+\/cards$/.test(path) && method === "POST") {
    const dashboardId = Number(path.split("/")[2]);
    const dashboard = state.dashboards.find((x) => x.id === dashboardId);
    if (!dashboard) throw new ApiError(404, E.DASHBOARD_NOT_FOUND, "大盘不存在");
    requireLeafMember(dashboard.orgId);
    const formula = String(body.formula ?? "hits");
    validateFormula(formula);
    const created: Card = {
      id: ++state.seq.card,
      dashboardId,
      service: String(body.service ?? "order"),
      targetKind: String(body.targetKind ?? "TRANSACTION"),
      targetType: String(body.targetType ?? "URL"),
      targetName: String(body.targetName ?? "POST /orders"),
      formula,
      unit: body.unit as string ?? "COUNT",
      timeRange: String(body.timeRange ?? "RECENT_24H"),
      thresholdLines: (body.thresholdLines as Card["thresholdLines"]) ?? [],
    };
    state.cards.push(created);
    return created as T;
  }
  if (path === "/cards" && method === "GET") {
    const dashboardId = query.get("dashboardId");
    const list = dashboardId
      ? state.cards.filter((c) => c.dashboardId === Number(dashboardId))
      : state.cards;
    return list as T;
  }
  if (/^\/cards\/\d+$/.test(path) && method === "POST") {
    const cardId = Number(path.split("/")[2]);
    const card = state.cards.find((c) => c.id === cardId);
    if (!card) throw new ApiError(404, E.CARD_NOT_FOUND, "卡片不存在");
    const dashboard = state.dashboards.find((x) => x.id === card.dashboardId);
    if (dashboard) requireLeafMember(dashboard.orgId);
    const formula = String(body.formula ?? card.formula);
    validateFormula(formula);
    card.formula = formula;
    card.timeRange = String(body.timeRange ?? card.timeRange);
    card.thresholdLines = (body.thresholdLines as Card["thresholdLines"]) ?? card.thresholdLines;
    // 公式变更 → 关联组织告警跟随新公式、关闭、窗口清零
    state.alerts
      .filter((r) => r.target.kind === "CARD_RESULT" && r.target.cardId === cardId)
      .forEach((r) => {
        r.enabled = false;
      });
    return card as T;
  }
  if (/^\/cards\/\d+$/.test(path) && method === "DELETE") {
    const cardId = Number(path.split("/")[2]);
    const card = state.cards.find((c) => c.id === cardId);
    if (!card) throw new ApiError(404, E.CARD_NOT_FOUND, "卡片不存在");
    state.cards = state.cards.filter((c) => c.id !== cardId);
    // 卡片删除 → 关联规则失效但保留配置
    state.alerts
      .filter((r) => r.target.kind === "CARD_RESULT" && r.target.cardId === cardId)
      .forEach((r) => {
        r.enabled = false;
        r.invalid = true;
      });
    return { ok: true } as T;
  }
  if (/^\/cards\/\d+\/series$/.test(path) && method === "GET") {
    const cardId = Number(path.split("/")[2]);
    const card = state.cards.find((c) => c.id === cardId);
    if (!card) throw new ApiError(404, E.CARD_NOT_FOUND, "卡片不存在");
    return {
      cardId,
      formula: card.formula,
      unit: card.unit,
      thresholdLines: card.thresholdLines,
      bucketSeconds: 600,
      points: d.buildSeries(d.now, card.targetType, card.targetName, card.formula),
    } as T;
  }
  if (path === "/dashboards/targets" && method === "GET") {
    const orgId = Number(query.get("orgId"));
    requireLeafMember(orgId);
    const dashboards = state.dashboards.filter((x) => x.orgId === orgId);
    const targets: unknown[] = [];
    dashboards.forEach((x) => {
      state.cards
        .filter((c) => c.dashboardId === x.id)
        .forEach((c) => {
          targets.push({
            kind: "CARD_RESULT",
            cardId: c.id,
            service: c.service,
            targetKind: c.targetKind,
            targetType: c.targetType,
            targetName: c.targetName,
          });
          (c.formula.match(/hits|failures|failureRate|qps|avgDuration|tp\d+/g) ?? []).forEach((stat) => {
            targets.push({
              kind: "RAW_STAT",
              cardId: 0,
              service: c.service,
              targetKind: c.targetKind,
              targetType: c.targetType,
              targetName: c.targetName,
              stat,
            });
          });
        });
    });
    return targets as T;
  }

  // ── 告警 ─────────────────────────────────────────────────
  if (path === "/alerts" && method === "GET") {
    const scope = query.get("scope");
    const orgId = query.get("orgId");
    let list = state.alerts;
    if (scope) list = list.filter((r) => r.scope === scope);
    if (orgId) list = list.filter((r) => r.orgId === Number(orgId));
    return list as T;
  }
  if (path === "/alerts/preview" && method === "POST") {
    const scenario = String(body.scenario ?? "TRIGGER");
    return (d.previewResults[scenario] ?? d.previewResults.TRIGGER) as T;
  }
  if (path === "/alerts" && method === "POST") {
    const created: AlertRule = {
      id: ++state.seq.alert,
      scope: (body.scope as AlertRule["scope"]) ?? "SERVICE",
      orgId: body.orgId == null ? null : Number(body.orgId),
      name: String(body.name ?? "新规则"),
      target: body.target as AlertRule["target"],
      combinator: (body.combinator as AlertRule["combinator"]) ?? "AND",
      windowPoints: Number(body.windowPoints ?? 3),
      conditions: (body.conditions as AlertRule["conditions"]) ?? [],
      recipients: (body.recipients as number[]) ?? [],
      channels: (body.channels as string[]) ?? ["EMAIL"],
      // 保存后一律关闭
      enabled: false,
      invalid: false,
    };
    state.alerts.push(created);
    return created as T;
  }
  if (/^\/alerts\/\d+\/(enable|disable)$/.test(path) && method === "POST") {
    const parts = path.split("/");
    const rule = state.alerts.find((r) => r.id === Number(parts[2]));
    if (!rule) throw new ApiError(404, E.NOT_FOUND, "规则不存在");
    rule.enabled = parts[3] === "enable";
    return rule as T;
  }
  if (/^\/alerts\/\d+$/.test(path) && method === "DELETE") {
    state.alerts = state.alerts.filter((r) => r.id !== Number(path.split("/")[2]));
    return { ok: true } as T;
  }

  // ── 平台配置 ─────────────────────────────────────────────
  if (path === "/platform" && method === "GET") {
    return state.platform as T;
  }
  if (path === "/platform/slow-thresholds" && method === "PUT") {
    requireAdmin();
    const slow = body.slow as typeof state.platform.slow | undefined;
    if (slow) state.platform.slow = { ...state.platform.slow, ...slow };
    return state.platform as T;
  }
  if (path === "/platform/channels" && method === "PUT") {
    requireAdmin();
    const channels = body.channels as typeof state.platform.channels | undefined;
    if (channels) state.platform.channels = { ...state.platform.channels, ...channels };
    return state.platform as T;
  }

  throw new ApiError(404, E.NOT_FOUND, `mock 未实现的接口：${method} ${path}`);
}

/** 公式单位校验（与后端 FormulaParser 同规则的关键子集）。 */
function validateFormula(formula: string): void {
  const trimmed = formula.trim();
  if (!trimmed) {
    throw new ApiError(400, E.FORMULA_INVALID, "公式不能为空");
  }
  if (/[;>]|if\s*\(|\./.test(trimmed)) {
    throw new ApiError(400, E.FORMULA_INVALID, "公式包含不支持的语法");
  }
  if (/^\s*(tp\d+|avgDuration|min|max)\s*[+-]\s*(hits|failures|qps|failureRate)\s*$/.test(trimmed)
      || /^\s*(hits|failures|qps|failureRate)\s*[+-]\s*(tp\d+|avgDuration|min|max)\s*$/.test(trimmed)) {
    throw new ApiError(400, E.UNIT_MISMATCH, "耗时与次数不能直接相加减");
  }
  if (/^[^a-zA-Z0-9(]*$/.test(trimmed)) {
    throw new ApiError(400, E.FORMULA_INVALID, "公式无有效统计项");
  }
}

export type { Sample };
