/**
 * 动线自检（技术方案 07-config-and-testing.md §3.5）。
 *
 * 用 Node 直接驱动 mock 路由，逐个走通 PRD 的两条主链路与管理链路：
 *   1. 登录 → 首登改密 → 服务列表 → Transaction Type → Name → 趋势 → 取样 → Trace
 *   2. 登录 → 叶子大盘 → 建卡片 → 公式校验 → 阈值线 → 一键创建组织告警 → 预告警三态 → 启用
 *   3. 管理员 → 账号管理 → 组织树 → 删除预览 → 平台配置
 *
 * 该脚本是「前端全流程可用」的可执行证据：任何一步接口或语义被改坏都会失败。
 */

import { mockRequest, resetMockState } from "../src/mock/router.ts";
import { DEV_PASSWORD } from "../src/mock/dataset.ts";
import { currentHourScope } from "../src/api/time-range.ts";
import { ERROR_CODE } from "../src/api/error-codes.ts";

const steps = [];
function assert(condition, message) {
  if (!condition) throw new Error(`FAIL ${message}`);
}
function ok(name) {
  steps.push(name);
}

const get = (path, query = {}) => {
  const search = new URLSearchParams(query).toString();
  return mockRequest(`/api${path}${search ? `?${search}` : ""}`, { method: "GET" });
};
const post = (path, body, query = {}) => {
  const search = new URLSearchParams(query).toString();
  return mockRequest(`/api${path}${search ? `?${search}` : ""}`, {
    method: "POST",
    body: JSON.stringify(body ?? {}),
  });
};
const put = (path, body) => mockRequest(`/api${path}`, { method: "PUT", body: JSON.stringify(body ?? {}) });
const del = (path, query = {}) => {
  const search = new URLSearchParams(query).toString();
  return mockRequest(`/api${path}${search ? `?${search}` : ""}`, { method: "DELETE" });
};

async function expectError(promise, code, label) {
  try {
    await promise;
  } catch (e) {
    assert(e.code === ERROR_CODE[code], `${label}: 期望错误码 ${ERROR_CODE[code]}，实际 ${e.code}`);
    return;
  }
  throw new Error(`FAIL ${label}: 期望抛出 ${code}，但没有抛出`);
}

async function userJourney() {
  resetMockState();

  // 未登录被拒
  await expectError(get("/me"), "UNAUTHENTICATED", "未登录访问 /me");
  ok("未登录被拒（401）");

  // 错误凭据使用统一错误码
  await expectError(post("/login", { username: "root", password: "wrong" }), "BAD_CREDENTIALS", "错误密码");
  ok("错误凭据返回统一错误（不暴露账号是否存在）");

  // 首登改密链路：bob 需要改密
  const firstLogin = await post("/login", { username: "bob", password: DEV_PASSWORD });
  assert(firstLogin.mustChangePassword === true, "bob 首次登录应要求改密");
  await expectError(get("/services"), "PASSWORD_CHANGE_REQUIRED", "强制改密会话访问业务接口");
  ok("强制改密会话被限制在改密流程");

  await expectError(
    post("/me/password", { oldPassword: DEV_PASSWORD, newPassword: DEV_PASSWORD }),
    "PASSWORD_UNCHANGED",
    "新旧密码相同"
  );
  await expectError(
    post("/me/password", { oldPassword: DEV_PASSWORD, newPassword: "short" }),
    "PASSWORD_TOO_SHORT",
    "新密码过短"
  );
  await post("/me/password", { oldPassword: DEV_PASSWORD, newPassword: "newpass123" });
  const services = await get("/services");
  assert(services.some((s) => s.name === "order"), "服务列表应包含 order");
  ok("改密成功后进入服务列表");

  // 服务报表链路：Type → Name → 趋势 → 取样 → Trace
  const types = await get("/reports/transaction/types", { service: "order" });
  assert(types.some((t) => t.type === "URL"), "Transaction Type 应包含 URL");
  ok("Transaction Type 列表");

  const names = await get("/reports/transaction/names", { service: "order", type: "URL" });
  assert(names[0].name === "POST /orders", "URL 下应有 POST /orders");
  ok("Type → Name 钻取");

  for (const [kind, type] of [["transaction", "URL"], ["event", "business"]]) {
    const path = `/reports/${kind}/names`;
    const query = { service: "order", type, range: "RECENT_3H" };
    const longer = await get(path, query);
    const shorter = await get(path, { ...query, range: "RECENT_1H" });
    const repeated = await get(path, query);
    assert(longer.length > 0, `${kind} Name 应有数据`);
    assert(longer[0].total > shorter[0].total, `${kind} Name 应随时间范围变化`);
    assert(JSON.stringify(longer) === JSON.stringify(repeated), `${kind} 同一窗口数据应稳定`);
    assert(longer.every((row) => row.qps === row.total / 10800), `${kind} QPS 应按窗口秒数计算`);
    if (kind === "event") {
      assert(longer.every((row) => row.avg === undefined && row.tp99 === undefined), "Event Name 无耗时和分位");
    }
  }
  ok("Transaction / Event Name mock 随窗口变化且稳定，QPS 口径正确");

  const series = await get("/reports/series", {
    service: "order",
    kind: "TRANSACTION",
    type: "URL",
    name: "POST /orders",
    stat: "HITS",
  });
  assert(series.points.length > 0, "趋势应有数据点");
  assert(
    series.points.some((p) => p.value === null),
    "趋势必须包含 null 缺口点（缺数不等于零）"
  );
  assert(
    series.points.some((p) => p.quality === "PARTIAL"),
    "趋势必须包含部分覆盖桶"
  );
  assert(
    series.points.some((p) => p.quality === "REALTIME"),
    "趋势必须包含实时桶"
  );
  ok("趋势默认 Hits，且含缺口 / 部分覆盖 / 实时桶");

  // 环比（PRD 03 §6）：日/周/月三种基准，按整日偏移对齐
  for (const mom of ["DAY", "WEEK", "MONTH"]) {
    const compared = await get("/reports/series", {
      service: "order",
      kind: "TRANSACTION",
      type: "URL",
      name: "POST /orders",
      stat: "HITS",
      mom,
    });
    assert(compared.mom?.kind === mom, `环比应回传 kind=${mom}`);
    assert(
      compared.mom.points.length === compared.points.length,
      `${mom} 环比的对比桶数应与当前窗口一致（按桶序号对齐）`
    );
    const days = mom === "MONTH" ? 30 : mom === "WEEK" ? 7 : 1;
    assert(compared.mom.points.every((point, i) =>
      compared.points[i].bucketStart - point.bucketStart === days * 86_400_000
    ), `${mom} 应保留真实对比时段`);
    assert(compared.mom.points.some((point) => point.value === null), `${mom} mock 应包含历史缺口`);
  }
  ok("环比支持日 / 周 / 月三种基准");

  // Problem 下钻复用通用 series：EXCEPTION 只有次数类，慢类支持分位（PRD 03 §9）
  const problemSeries = await get("/reports/series", {
    service: "order",
    kind: "PROBLEM",
    type: "EXCEPTION",
    name: "java.lang.NullPointerException",
    stat: "HITS",
    range: "RECENT_1H",
    mom: "DAY",
  });
  assert(problemSeries.points.length > 0, "Problem 应有趋势点");
  assert(problemSeries.mom?.kind === "DAY", "Problem 应支持环比");
  assert(
    problemSeries.mom.points.length === problemSeries.points.length,
    "Problem 环比应按桶序号对齐"
  );
  ok("Problem 下钻复用通用 series 且支持环比");

  // 时间窗口按粒度对齐：请求固定小时窗口时要返回整点区间
  const hourWindow = await get("/reports/series", {
    service: "order",
    kind: "TRANSACTION",
    type: "URL",
    name: "POST /orders",
    stat: "HITS",
    range: "HOUR:1790696400000",
    bucket: 60,
  });
  assert(hourWindow.bucketSeconds === 60, "显式 bucket 只应覆盖粒度");
  assert(hourWindow.points.length === 60, "整点小时应按 1 分钟粒度返回 60 个点");
  ok("窗口导航的固定小时 range 生效（bucket 不改变窗口）");

  // 默认窗口是当前整点小时：还没到的桶必须是缺口，不能预填数值
  const currentHour = currentHourScope().range;
  assert(/^HOUR:\d+$/.test(currentHour), "默认 range 应是整点小时");
  const currentWindow = await get("/reports/series", {
    service: "order",
    kind: "TRANSACTION",
    type: "URL",
    name: "POST /orders",
    stat: "HITS",
    range: currentHour,
    bucket: 60,
  });
  const nowMs = Date.now();
  const future = currentWindow.points.filter((point) => point.bucketStart > nowMs);
  assert(future.length > 0, "当前小时内应包含尚未发生的桶");
  assert(
    future.every((point) => point.value === null && point.quality === "NO_DATA"),
    "未来桶必须是缺口，不能编造数值"
  );
  assert(
    currentWindow.points.some((point) => point.quality === "REALTIME"),
    "当前小时应包含一个实时桶"
  );
  ok("默认窗口为当前整点小时，未来桶为空缺、含实时桶");

  // Event 无耗时字段
  const eventTypes = await get("/reports/event/types", { service: "order" });
  assert(
    eventTypes.every((row) => row.avg === undefined && row.tp99 === undefined),
    "Event 不应提供耗时与分位"
  );
  ok("Event 不提供耗时与分位");

  // Problem 五类
  const categories = await get("/reports/problem/categories", { service: "order" });
  assert(categories.length === 5, "Problem 应有五类");
  assert(
    categories.find((c) => c.category === "EXCEPTION").supportsPercentile === false,
    "异常类不支持分位"
  );
  assert(
    categories.find((c) => c.category === "SLOW_SQL").supportsPercentile === true,
    "慢类支持分位"
  );
  ok("Problem 五类且异常无分位");

  // Heartbeat：指标编目、按实例分序列、无数据是空缺而不是 0
  const hbMetrics = await get("/reports/heartbeat/metrics", { service: "order" });
  assert(hbMetrics.includes("young-gc-count") && hbMetrics.includes("full-gc-time"), "Heartbeat 应含 GC 分项");
  for (const partition of ["young", "old", "metaspace"]) {
    for (const scope of ["used", "committed", "max"]) {
      assert(hbMetrics.includes(`${partition}-${scope}`), `Heartbeat 应含 ${partition}-${scope}`);
    }
  }
  const hbSeries = await get("/reports/heartbeat/series", { service: "order", metric: "young-gc-count" });
  assert(hbSeries.series.length > 0, "Heartbeat 应按实例返回序列");
  assert(hbSeries.series[0].instance, "每条序列应带实例");
  assert(hbSeries.mom === null, "一期 Heartbeat 不做环比");
  const hbFull = await get("/reports/heartbeat/series", { service: "order", metric: "full-gc-count" });
  const undetectable = hbFull.series.find((s) => s.instance === "10.0.0.9");
  assert(
    undetectable.points.every((p) => p.value === null && p.quality === "NO_DATA"),
    "无法区分 Full GC 的实例应是空缺，不能填 0"
  );
  // 元空间上限默认不存在，同样必须是空缺而不是 0
  const hbMetaMax = await get("/reports/heartbeat/series", { service: "order", metric: "metaspace-max" });
  assert(
    hbMetaMax.series.every((s) => s.points.every((p) => p.value === null && p.quality === "NO_DATA")),
    "元空间未设上限时是空缺，不能报 0"
  );
  // 分区内存三口径各自独立成序列
  const hbYoung = await get("/reports/heartbeat/series", { service: "order", metric: "young-used" });
  assert(hbYoung.series.length > 0 && hbYoung.series[0].points.length > 1, "年轻代已用应有趋势");
  const hbInstances = await get("/reports/heartbeat/instances", { service: "order", metric: "heap-used" });
  assert(hbInstances.length > 0, "Heartbeat 应有实例明细");
  ok("Heartbeat 内存分区三口径、按实例分序列，无法区分/无上限的指标为空缺");

  // Metric Top1000 + other
  const metricList = await get("/reports/metric/list", { service: "order" });
  assert(
    metricList.some((m) => m.labels === "__OTHER__"),
    "Metric 列表应包含 other 序列"
  );
  ok("Metric 含 other 序列");

  const catalog = await get("/reports/metric/metrics", { service: "order", range: "RECENT_1H" });
  assert(catalog.length >= 3 && catalog.some((m) => m.name === "order.amount"), "Metric 应按名称列出多个指标");
  const metricQuery = { service: "order", metric: "order.amount", range: "RECENT_1H" };
  const metricTotal = await get("/reports/metric/count", metricQuery);
  const metricFiltered = await get("/reports/metric/count", { ...metricQuery, filters: JSON.stringify({ channel: ["app"], city: ["上海"] }) });
  const metricRestored = await get("/reports/metric/count", metricQuery);
  assert(JSON.stringify(metricTotal) === JSON.stringify(metricRestored), "清空标签后全量应稳定恢复");
  assert(metricTotal.points.some((p, i) => p.value !== null && metricFiltered.points[i].value !== null && p.value > metricFiltered.points[i].value), "筛选后的聚合 count 应减少，不是固定假曲线");
  ok("Metric 多指标总览，全量 count、标签聚合与清空恢复");

  const queueLabels = await get("/reports/metric/labels", { service: "order", metric: "queue.depth" });
  assert(queueLabels.some((label) => label.key === "queue") && !queueLabels.some((label) => label.key === "city"), "维度候选只属于当前 Metric");
  await expectError(get("/reports/metric/count", { ...metricQuery, metric: "missing" }), "NOT_FOUND", "未知 Metric");
  ok("Metric 标签不串指标，未知指标明确报错");

  // 依赖上下游
  const downstream = await get("/reports/dependency/downstream", { service: "order" });
  assert(downstream.some((d) => d.peer === "pay"), "下游应包含 pay");
  ok("依赖上下游列表");

  // 取样 30 条 → Trace
  const samples = await get("/reports/samples", { service: "order", kind: "TRANSACTION" });
  assert(samples.length === 30, "取样应为最近 30 条");
  const timestamps = samples.map((s) => s.timestamp);
  assert(
    timestamps.every((t, i) => i === 0 || timestamps[i - 1] >= t),
    "取样应按事件时间倒序"
  );
  ok("取样 30 条且按时间倒序");

  const trace = await get(`/traces/${samples[0].messageId}`);
  const missing = findAvailability(trace.children, "MISSING");
  assert(missing, "Trace 应包含缺失节点");
  ok("Trace 组装含缺失节点");

  const expiring = samples.find((s) => !s.traceAvailable);
  await expectError(get(`/traces/${expiring.messageId}`), "TRACE_EXPIRED", "过期 Trace");
  ok("过期 Trace 返回 410 不可打开");
}

function findAvailability(nodes, availability) {
  for (const node of nodes) {
    if (node.availability === availability) return node;
    const child = findAvailability(node.children ?? [], availability);
    if (child) return child;
  }
  return null;
}

async function dashboardJourney() {
  resetMockState();
  await post("/login", { username: "root", password: DEV_PASSWORD });

  const mine = await get("/orgs/mine");
  assert(mine.length > 0, "应有可访问的叶子组织");
  ok("只显示我有成员资格的叶子组织");

  const orgs = await get("/orgs");
  const nonLeaf = orgs.find((o) => !o.leaf);
  await expectError(
    post("/dashboards", { orgId: nonLeaf.id, name: "非法大盘" }),
    "NOT_LEAF",
    "非叶子挂大盘"
  );
  ok("非叶子组织不能挂大盘");

  const leaf = mine[0];
  const board = await post("/dashboards", { orgId: leaf, name: "验收大盘" });
  ok("在叶子组织创建大盘");

  // 公式单位校验：耗时 + 次数不兼容
  await expectError(
    post(`/dashboards/${board.id}/cards`, {
      service: "order",
      targetKind: "TRANSACTION",
      targetType: "URL",
      targetName: "POST /orders",
      formula: "tp99 + hits",
    }),
    "UNIT_MISMATCH",
    "单位不兼容的公式"
  );
  ok("单位不兼容的公式不能保存");

  // 空公式
  await expectError(
    post(`/dashboards/${board.id}/cards`, {
      service: "order",
      targetKind: "TRANSACTION",
      targetType: "URL",
      targetName: "POST /orders",
      formula: "",
    }),
    "FORMULA_INVALID",
    "空公式"
  );
  ok("非法公式不能保存");

  const card = await post(`/dashboards/${board.id}/cards`, {
    service: "order",
    targetKind: "TRANSACTION",
    targetType: "URL",
    targetName: "POST /orders",
    formula: "failures / hits",
    unit: "RATE",
    thresholdLines: [{ direction: "ABOVE", value: 0.05 }],
  });
  assert(card.id > 0, "卡片应创建成功");
  ok("创建卡片并配置阈值线");

  const cardSeries = await get(`/cards/${card.id}/series`);
  assert(cardSeries.points.some((p) => p.value === null), "卡片序列应含缺口");
  ok("卡片序列含缺口");

  // 组织告警：目标并集 + 预告警三态 + 保存即关闭 + 启用 + 编辑自动关闭
  const targets = await get("/dashboards/targets", { orgId: leaf });
  assert(
    targets.some((t) => t.kind === "CARD_RESULT"),
    "组织告警可选目标应含卡片结果"
  );
  assert(
    targets.some((t) => t.kind === "RAW_STAT"),
    "组织告警可选目标应含原始统计项"
  );
  ok("组织告警可选目标 = 原始统计项 ∪ 卡片结果");

  for (const scenario of ["TRIGGER", "NO_TRIGGER", "INSUFFICIENT_DATA"]) {
    const preview = await post("/alerts/preview", { scenario });
    assert(preview.result === scenario, `预告警应支持 ${scenario}`);
  }
  ok("预告警三态（会触发 / 不会触发 / 数据不足）");

  const rule = await post("/alerts", {
    scope: "ORGANIZATION",
    orgId: leaf,
    name: "验收组织告警",
    target: { kind: "CARD_RESULT", cardId: card.id, service: "order", reportKind: "TRANSACTION", targetType: "URL", targetName: "POST /orders" },
    combinator: "AND",
    windowPoints: 3,
    conditions: [{ stat: "FAILURE_RATE", comparator: "GT", threshold: 0.05 }],
    recipients: [3],
    channels: ["EMAIL"],
  });
  assert(rule.enabled === false, "保存后必须为关闭状态");
  ok("告警保存后为关闭");

  const enabled = await post(`/alerts/${rule.id}/enable`);
  assert(enabled.enabled === true, "应可手动启用");
  ok("手动启用告警");

  // 卡片公式变更 → 关联规则关闭 + 目标跟随
  await post(`/cards/${card.id}`, { formula: "failures / hits * 100" });
  const rulesAfter = await get("/alerts", { scope: "ORGANIZATION" });
  const updated = rulesAfter.find((r) => r.id === rule.id);
  assert(updated.enabled === false, "卡片公式变更后关联规则应自动关闭");
  ok("卡片公式变更使关联规则关闭并清零窗口");

  // 卡片删除 → 规则失效但保留配置
  await del(`/cards/${card.id}`);
  const rulesAfterDelete = await get("/alerts", { scope: "ORGANIZATION" });
  const invalidated = rulesAfterDelete.find((r) => r.id === rule.id);
  assert(invalidated, "卡片删除后规则应保留");
  assert(invalidated.invalid === true, "卡片删除后规则应标记失效");
  ok("卡片删除使规则失效但保留配置");
}

async function adminJourney() {
  resetMockState();
  await post("/login", { username: "root", password: DEV_PASSWORD });

  // 账号管理
  await expectError(post("/users", { username: "x", password: "short" }), "PASSWORD_TOO_SHORT", "短密码");
  ok("创建账号要求密码至少 8 位");

  const created = await post("/users", { username: "dave", password: "password123" });
  assert(created.role === "USER", "管理员只能创建普通用户");
  assert(created.mustChangePassword === true, "新账号首次登录必须改密");
  ok("管理员创建普通账号且需首次改密");

  await expectError(post("/users", { username: "dave", password: "password123" }), "USER_EXISTS", "重名");
  ok("用户名唯一");

  const promoted = await post(`/users/${created.id}/role`, { role: "ADMIN" });
  assert(promoted.role === "ADMIN", "超管可授予 ADMIN");
  ok("超管授予 ADMIN");

  await post("/users/3/disable");
  const rulesAfterDisable = await get("/alerts");
  assert(
    rulesAfterDisable.every((r) => !r.recipients.includes(3)),
    "禁用账号应从告警收件人移除"
  );
  ok("禁用账号即从告警收件人移除");

  await post("/users/3/enable");
  const rulesAfterEnable = await get("/alerts");
  assert(
    rulesAfterEnable.every((r) => !r.recipients.includes(3)),
    "启用账号不恢复告警收件关系"
  );
  ok("启用账号不恢复收件关系");

  // 组织树
  const orgs = await get("/orgs");
  const leafWithDashboards = orgs.find((o) => o.leaf && o.id === 3);
  await expectError(
    post("/orgs", { name: "子节点", parentId: leafWithDashboards.id }),
    "LEAF_HAS_RESOURCES",
    "有资源的叶子新增子节点"
  );
  ok("有资源的叶子不能新增子节点");

  // 新建一个没有任何大盘与告警的干净叶子，用于验证「叶子转非叶子」路径
  const freshLeaf = await post("/orgs", { name: "验收叶子", parentId: null });
  assert(freshLeaf.leaf === true, "新建节点应为叶子");
  const child = await post("/orgs", { name: "验收子组", parentId: freshLeaf.id });
  assert(child.id > 0, "无资源叶子可以新增子节点");
  const afterAdd = await get("/orgs");
  assert(
    afterAdd.find((o) => o.id === freshLeaf.id).leaf === false,
    "新增子节点后父节点应变为非叶子"
  );
  ok("无资源叶子可以新增子节点并转为非叶子");

  await expectError(del(`/orgs/${child.id}`, { confirmName: "错误名称" }), "CONFIRM_NAME_MISMATCH", "错误确认名");
  ok("删除需要输入正确的组织名确认");

  const preview = await get(`/orgs/${child.id}/deletion-preview`);
  assert(typeof preview.orgName === "string", "删除预览应包含组织名");
  assert(typeof preview.alertRuleCount === "number", "删除预览应包含告警规则数");
  ok("删除前展示影响预览");

  // 平台配置
  const profile = await get("/platform");
  assert(profile.timezone === "Asia/Shanghai", "平台时区应固定");
  await put("/platform/slow-thresholds", { slow: { url: 2000, sql: 200, call: 2000, cache: 100 } });
  const afterSlow = await get("/platform");
  assert(afterSlow.slow.url === 2000, "慢阈值应可更新");
  ok("慢阈值可更新");

  await put("/platform/channels", { channels: { email: true, dingtalk: true, feishu: false } });
  const afterChannels = await get("/platform");
  assert(afterChannels.channels.dingtalk === true, "通道配置应可更新");
  ok("通知通道可配置");
}

async function main() {
  await userJourney();
  await dashboardJourney();
  await adminJourney();
  console.log(`\n动线自检通过：${steps.length} 个检查点\n`);
  steps.forEach((s) => console.log(`  ✓ ${s}`));
}

main().catch((e) => {
  console.error(`\n动线自检失败：${e.message}`);
  console.error(`已完成 ${steps.length} 个检查点。`);
  process.exit(1);
});
