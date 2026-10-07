/**
 * Mock 数据集（技术方案 03-api-contract.md §9、07 §3.4）。
 *
 * 硬要求：
 * 1. 报表数据**必须包含 null 缺口与部分覆盖桶**——否则前端会把「缺数≠0」做错；
 * 2. 覆盖 PRD 的两条主链路：服务报表链路与叶子大盘/组织告警链路；
 * 3. 覆盖管理员链路：账号、组织、平台配置。
 */

import type { Point, Quality } from "../api/types";
import { HEARTBEAT_METRICS } from "../api/heartbeat";
import type {
  Account,
  AlertRule,
  Card,
  Dashboard,
  DependencyRow,
  MetricSeriesRow,
  OrgNode,
  PlatformProfile,
  Sample,
  ServiceRow,
  TraceView,
} from "./model";

const HOUR = 3_600_000;

function iso(ms: number): string {
  return new Date(ms).toISOString();
}

function point(start: number, value: number | null, quality: Quality, bucketSeconds = 600): Point {
  return {
    bucketStart: start,
    bucketEnd: start + bucketSeconds * 1000,
    value,
    quality,
    coveredSeconds: quality === "PARTIAL" ? Math.floor(bucketSeconds / 2) : bucketSeconds,
    realtime: quality === "REALTIME",
    partial: quality === "PARTIAL",
  };
}

/** 一条含缺口的趋势：正常 → 缺口 → 正常 → 实时。 */
function seriesWithGaps(now: number, base: number, bucketSeconds: number, count: number): Point[] {
  const points: Point[] = [];
  for (let i = count - 1; i >= 0; i -= 1) {
    const start = now - i * bucketSeconds * 1000;
    if (i === count - 1) {
      points.push(point(start, base, "REALTIME", bucketSeconds));
    } else if (i === 4) {
      // 队列满丢弃：缺口，绝不写 0
      points.push(point(start, null, "DROPPED", bucketSeconds));
    } else if (i === 3) {
      // 部分覆盖桶
      points.push(point(start, base * 0.6, "PARTIAL", bucketSeconds));
    } else if (i === 7) {
      points.push(point(start, null, "NO_DATA", bucketSeconds));
    } else {
      points.push(point(start, base + ((i * 7) % 23), "OK", bucketSeconds));
    }
  }
  return points;
}

/**
 * 按桶长把时刻对齐到桶起点。
 *
 * 与后端一致：粒度 >= 1 天时按**平台时区自然日**对齐，其余粒度落在整分钟/整小时上
 * （这些桶长都能整除 1 小时，与本地时区偏移为整小时一致）。
 * 若按 UTC 取整，Asia/Shanghai 的月初会被算到前一天，横轴因此少一天。
 */
function alignBucket(ms: number, bucketSeconds: number): number {
  if (bucketSeconds >= 86_400) {
    const d = new Date(ms);
    d.setHours(0, 0, 0, 0);
    // 天数可能不是整倍数（如 86400 的整数倍仍有跨月差异），逐日往前收敛到自然对齐
    const step = bucketSeconds * 1000;
    return Math.floor((ms - d.getTime()) / step) * step + d.getTime();
  }
  const step = bucketSeconds * 1000;
  return Math.floor(ms / step) * step;
}

/**
 * 区间内、按桶长对齐且含缺口的趋势（技术方案 03 §4.1、§4.2）。
 *
 * 与后端一致：
 * - 桶起点按桶长对齐；无数据的桶是缺口而不是 0；
 * - **只有包含「当前时刻」的桶才是实时桶**，翻页到历史窗口时不再有实时桶
 *   （对应后端 `ReportController.isCurrentBucket`）；
 * - **还没到的桶是缺口**。固定周期（如当前整点小时）的窗口右端在 `now` 之后，
 *   这些桶尚未发生，后端没有对应行→NO_DATA。给它们编造数值会让人以为
 *   未来已有数据，正是「缺数据不等于零」要防的错误。
 *
 * 缺口位置取自「距离区间末尾」的固定序号，因此翻页后缺口随窗口移动，
 * 便于肉眼确认箭头确实改变了窗口。
 */
function seriesInRange(from: number, to: number, bucketSeconds: number, base: number, now: number): Point[] {
  const step = bucketSeconds * 1000;
  const alignedFrom = alignBucket(from, bucketSeconds);
  const points: Point[] = [];
  const total = Math.max(1, Math.ceil((to - alignedFrom) / step));

  for (let i = 0; i < total; i += 1) {
    const start = alignedFrom + i * step;
    if (start >= to) break;
    const index = total - 1 - i;

    if (now >= start && now < start + step) {
      points.push(point(start, base, "REALTIME", bucketSeconds));
    } else if (start > now) {
      points.push(point(start, null, "NO_DATA", bucketSeconds));
    } else if (index === 4) {
      points.push(point(start, null, "DROPPED", bucketSeconds));
    } else if (index === 3) {
      points.push(point(start, base * 0.6, "PARTIAL", bucketSeconds));
    } else if (index === 7) {
      points.push(point(start, null, "NO_DATA", bucketSeconds));
    } else {
      points.push(point(start, base + ((i * 7) % 23), "OK", bucketSeconds));
    }
  }
  return points;
}

/** 全部机器聚合序列（含缺口）与实例明细序列。 */
export function buildSeries(now: number, type: string, name: string, stat: string): Point[] {
  const seed = `${type}${name}${stat}`.length;
  const base = 40 + (seed % 60);
  return seriesWithGaps(now, base, 600, 12);
}

/** 快捷范围窗口（与后端 RangeQuick 对齐）。 */
export function quickWindow(range: string, now: number): { from: number; to: number; bucketSeconds: number } {
  const HOUR = 3_600_000;
  switch (range) {
    case "RECENT_3H":
      return { from: now - 3 * HOUR, to: now, bucketSeconds: 300 };
    case "RECENT_6H":
      return { from: now - 6 * HOUR, to: now, bucketSeconds: 600 };
    case "RECENT_12H":
      return { from: now - 12 * HOUR, to: now, bucketSeconds: 1200 };
    case "RECENT_24H":
      return { from: now - 24 * HOUR, to: now, bucketSeconds: 3600 };
    case "THIS_WEEK": {
      const d = new Date(now);
      d.setHours(0, 0, 0, 0);
      d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
      return { from: d.getTime(), to: now, bucketSeconds: 3600 };
    }
    case "TODAY": {
      const d = new Date(now);
      d.setHours(0, 0, 0, 0);
      return { from: d.getTime(), to: now, bucketSeconds: 600 };
    }
    default:
      return { from: now - HOUR, to: now, bucketSeconds: 60 };
  }
}

/**
 * 把 `range` 解析为窗口（技术方案 03 §4.1）。
 *
 * 支持快捷范围与固定周期两种写法；`bucket` 只覆盖粒度，不改变窗口，
 * 与后端 `ReportController.series` 的口径保持一致。
 */
export function seriesWindow(
  range: string,
  bucket: number,
  now: number
): { from: number; to: number; bucketSeconds: number } {
  const HOUR = 3_600_000;
  const DAY = 86_400_000;

  const fixed = /^([A-Z]+):(.+)$/.exec(range);
  if (fixed) {
    const [, head, tail] = fixed;
    if (head === "HOUR") {
      const from = Number(tail);
      if (Number.isFinite(from)) return { from, to: from + HOUR, bucketSeconds: 60 };
    }
    if (head === "WEEK") {
      const d = new Date(`${tail}T00:00:00`);
      if (!Number.isNaN(d.getTime())) {
        return { from: d.getTime(), to: d.getTime() + 7 * DAY, bucketSeconds: 3600 };
      }
    }
    if (head === "MONTH") {
      const [year, month] = tail.split("-").map(Number);
      if (Number.isFinite(year) && Number.isFinite(month)) {
        const from = new Date(year, month - 1, 1).getTime();
        const to = new Date(year, month, 1).getTime();
        return { from, to, bucketSeconds: DAY / 1000 };
      }
    }
  }

  const quick = quickWindow(range, now);
  return bucket > 0 ? { ...quick, bucketSeconds: bucket } : quick;
}

/**
 * 在给定窗口内生成趋势点。
 *
 * `now` 用于判定哪个桶是实时桶（包含当前时刻的那个）。
 */
export function seriesInWindow(
  now: number,
  window: { from: number; to: number; bucketSeconds: number },
  base: number
): Point[] {
  return seriesInRange(window.from, window.to, window.bucketSeconds, base, now);
}

/** 环比适用的报表类型（PRD 03 §6、§10：Heartbeat 不做环比）。 */
export function momSupported(kind: string): boolean {
  return ["TRANSACTION", "EVENT", "PROBLEM", "METRIC"].includes(kind.toUpperCase());
}

/**
 * 环比对比序列（PRD 03 §6）。
 *
 * 按**整日偏移**生成对比窗口，桶数与当前窗口一一对应 —— 这就是契约里的
 * 「按桶序号对齐」：当前 10:20–10:30 平移到昨天仍是 10:20–10:30。
 * 月环比是 30 天前同时段，不是上一个自然月。
 *
 * 桶数按与 {@link seriesInRange} 相同的规则推导（起点向下对齐桶长），
 * 保证两条序列逐点对齐、前端叠图时不会错位。
 */
export function momPoints(
  window: { from: number; to: number; bucketSeconds: number },
  mom: string,
  base: number
): { bucketStart: number; value: number | null }[] {
  const upper = mom.toUpperCase();
  const daysOffset = upper === "MONTH" ? 30 : upper === "WEEK" ? 7 : 1;
  const offsetMs = daysOffset * 86_400_000;
  const step = window.bucketSeconds * 1000;
  const alignedFrom = alignBucket(window.from, window.bucketSeconds);
  const count = Math.max(1, Math.ceil((window.to - alignedFrom) / step));
  // 三种基准使用不同水平及波形；重复查询同一窗口得到相同演示数据。
  const profile = upper === "MONTH"
    ? { scale: 1.15, amplitude: 11, period: 9, phase: 2 }
    : upper === "WEEK"
      ? { scale: 0.72, amplitude: 9, period: 7, phase: 1 }
      : { scale: 0.9, amplitude: 7, period: 5, phase: 0 };

  return Array.from({ length: count }, (_, i) => ({
    bucketStart: alignedFrom + i * step - offsetMs,
    // 刻意留一个缺口，不能用 0 假装历史取样完整。
    value: i === Math.floor(count / 3)
      ? null
      : Math.round((base * profile.scale + profile.amplitude *
        Math.sin((i + profile.phase) * Math.PI * 2 / profile.period)) * 100) / 100,
  }));
}

export const now = Date.now();

export const platform: PlatformProfile = {
  timezone: "Asia/Shanghai",
  initialized: true,
  slow: { url: 1000, sql: 100, call: 1000, cache: 50 },
  channels: { email: true, dingtalk: false, feishu: false },
};

/**
 * 演示账号。口令只用于本地 mock 登录校验（真实后端保存哈希，不会下发口令）。
 * 约定：初始口令为 NeoCat@2026；bob 处于「需要首次改密」状态。
 */
export const DEV_PASSWORD = "NeoCat@2026";

export const accounts: Account[] = [
  { id: 1, username: "root", password: DEV_PASSWORD, role: "SUPER_ADMIN", status: "ENABLED", mustChangePassword: false },
  { id: 2, username: "alice", password: DEV_PASSWORD, role: "ADMIN", status: "ENABLED", mustChangePassword: false },
  { id: 3, username: "bob", password: DEV_PASSWORD, role: "USER", status: "ENABLED", mustChangePassword: true },
  { id: 4, username: "carol", password: DEV_PASSWORD, role: "USER", status: "DISABLED", mustChangePassword: false },
];

export const orgs: OrgNode[] = [
  { id: 1, name: "总部", parentId: null, leaf: false, memberCount: 2 },
  { id: 2, name: "支付部", parentId: 1, leaf: false, memberCount: 1 },
  { id: 3, name: "支付组", parentId: 2, leaf: true, memberCount: 1 },
  { id: 4, name: "订单组", parentId: 1, leaf: true, memberCount: 1 },
];

/** 当前用户是叶子 3 与 4 的有效成员（3 通过祖先 2 继承）。 */
export const myLeaves: number[] = [3, 4];

export const services: ServiceRow[] = [
  { name: "order", instances: ["10.0.0.8", "10.0.0.9"] },
  { name: "pay", instances: ["10.0.1.1"] },
];

export const transactionTypes = [
  { type: "URL", total: 1520, failures: 12, failureRate: 0.0079, min: 3, max: 812, avg: 46.2, tp90: 120, tp95: 210, tp99: 640, tp999: 812, qps: 0.42 },
  { type: "SQL", total: 4210, failures: 3, failureRate: 0.0007, min: 1, max: 320, avg: 12.8, tp90: 24, tp95: 40, tp99: 180, tp999: 320, qps: 1.17 },
  { type: "CACHE", total: 9800, failures: 0, failureRate: 0, min: 0, max: 55, avg: 1.4, tp90: 3, tp95: 5, tp99: 40, tp999: 55, qps: 2.72 },
];

export const transactionNames: Record<
  string,
  { name: string; total: number; failures: number; avg: number; qps: number; tp90: number; tp95: number; tp99: number; tp999: number }[]
> = {
  URL: [
    { name: "POST /orders", total: 820, failures: 10, avg: 62.5, qps: 0.23, tp90: 140, tp95: 260, tp99: 640, tp999: 812 },
    { name: "GET /orders/{id}", total: 700, failures: 2, avg: 30.1, qps: 0.19, tp90: 70, tp95: 120, tp99: 210, tp999: 300 },
  ],
  SQL: [
    { name: "select_order", total: 3100, failures: 2, avg: 11.2, qps: 0.86, tp90: 22, tp95: 38, tp99: 180, tp999: 310 },
    { name: "insert_order", total: 1110, failures: 1, avg: 16.4, qps: 0.31, tp90: 32, tp95: 60, tp99: 260, tp999: 320 },
  ],
  CACHE: [{ name: "getUser", total: 9800, failures: 0, avg: 1.4, qps: 2.72, tp90: 3, tp95: 5, tp99: 40, tp999: 55 }],
};

export const eventTypes = [
  { type: "business", total: 5200, failures: 4, failureRate: 0.0008, qps: 1.44 },
  { type: "system", total: 800, failures: 0, failureRate: 0, qps: 0.22 },
];

export const eventNames = {
  business: [
    { name: "order.created", total: 3600, failures: 3, qps: 1 },
    { name: "payment.completed", total: 1600, failures: 1, qps: 0.44 },
  ],
  system: [{ name: "config.reload", total: 800, failures: 0, qps: 0.22 }],
};

/** 以固定小时的稳定流量模拟窗口聚合；未来时段不生成调用。 */
export function namesInWindow(kind: "TRANSACTION" | "EVENT", type: string, range: string) {
  const source: Record<string, {
    name: string; total: number; failures: number; qps: number;
    avg?: number; tp90?: number; tp95?: number; tp99?: number; tp999?: number;
  }[]> = kind === "TRANSACTION" ? transactionNames : eventNames;
  const window = seriesWindow(range, 0, now);
  const end = Math.min(window.to, now);
  if (end <= window.from) return [];

  // 每小时有确定性的流量差异；同一范围重复请求不随机改变数字。
  let weightedHours = 0;
  for (let start = Math.floor(window.from / HOUR) * HOUR; start < end; start += HOUR) {
    const overlap = Math.min(start + HOUR, end) - Math.max(start, window.from);
    const traffic = 0.8 + (Math.abs(Math.floor(start / HOUR)) % 13) * 0.04;
    weightedHours += overlap / HOUR * traffic;
  }
  // QPS 分母按**实际覆盖秒数**：当前小时是从整点到 now（PRD 03 §4），
  // 完整历史窗口才是整段长度。滚动窗口的 to 就是 now，两者一致。
  const seconds = (end - window.from) / 1000;
  const latency = 0.9 + (Math.abs(Math.floor(window.from / HOUR)) % 7) * 0.04;
  return (source[type] ?? []).map((row) => {
    const total = Math.round(row.total * weightedHours);
    const result = {
      ...row,
      total,
      failures: Math.min(total, Math.round(row.failures * weightedHours)),
      qps: total / seconds,
    };
    for (const key of ["avg", "tp90", "tp95", "tp99", "tp999"] as const) {
      if (row[key] !== undefined) result[key] = Math.round(row[key] * latency * 10) / 10;
    }
    return result;
  });
}

export const problemCategories = [
  { category: "EXCEPTION", total: 42, supportsPercentile: false },
  { category: "SLOW_URL", total: 18, supportsPercentile: true },
  { category: "SLOW_SQL", total: 7, supportsPercentile: true },
  { category: "SLOW_CALL", total: 3, supportsPercentile: true },
  { category: "SLOW_CACHE", total: 1, supportsPercentile: true },
];

export const problemNames: Record<string, { name: string; total: number; tp99: number | null }[]> = {
  EXCEPTION: [
    { name: "java.lang.NullPointerException", total: 30, tp99: null },
    { name: "java.sql.SQLTimeoutException", total: 12, tp99: null },
  ],
  SLOW_URL: [{ name: "POST /orders", total: 18, tp99: 1240 }],
  SLOW_SQL: [{ name: "select_order", total: 7, tp99: 380 }],
  SLOW_CALL: [{ name: "pay", total: 3, tp99: 2100 }],
  SLOW_CACHE: [{ name: "getUser", total: 1, tp99: 90 }],
};

/**
 * Heartbeat 指标清单。
 *
 * 直接从前端编目推导，而不是再抄一份：抄一份的话两边迟早会不一致，
 * 而「mock 有、页面请求不到」或反过来的偏差，恰恰是最难发现的一类 bug。
 *
 * **注意事项**：`heap-*`、`gc-count/time`、`threads` 已有上报协议、客户端与后端分析支撑；
 * 内存分区与 young/old/full GC 目前只有前端与 mock 支持，真实环境需要扩展上报协议后才能产出数据。
 * 详见 docs/superpowers/specs/2026-10-02-heartbeat-metric-grid-design.md。
 */
export const heartbeatMetrics = HEARTBEAT_METRICS;

/** 上报过 Heartbeat 的 JVM 实例。 */
export const heartbeatInstanceIds = ["10.0.0.8", "10.0.0.9", "10.0.0.10"];

/**
 * 每个指标的形态。
 *
 * - `gauge`：瞬时值（已用量、线程数），逐桶围绕基准上下波动；
 * - 非 gauge：JVM 启动以来的累计值，逐桶单调递增，重启才归零；
 * - `constant`：同一实例内基本不变（如各分区上限），只有实例之间不同；
 * - `unavailable`：该指标在该运行时下**根本不存在**，整条为空缺。元空间默认不设上限，
 *   报 0 或最大值都是假的；
 * - `spread`：各实例之间的倍率差异，否则三条线会完全重合。
 */
const HEARTBEAT_PROFILE: Record<
  string,
  {
    base: number;
    perBucket: number;
    gauge?: boolean;
    constant?: boolean;
    unavailable?: boolean;
    spread?: number;
  }
> = {
  "heap-used": { base: 430_000_000, perBucket: 6_000_000, gauge: true, spread: 0.35 },
  // 堆上限对同一实例是常量（-Xmx），实例之间因配置不同才有差异
  "heap-max": { base: 2_147_483_648, perBucket: 0, constant: true, spread: 0.5 },

  // 年轻代：容量小、波动快，committed 略高于 used，max 由 -Xmn 决定
  "young-used": { base: 120_000_000, perBucket: 9_000_000, gauge: true, spread: 0.35 },
  "young-committed": { base: 190_000_000, perBucket: 4_000_000, gauge: true, spread: 0.3 },
  "young-max": { base: 402_653_184, perBucket: 0, constant: true, spread: 0.4 },

  // 老年代：留存对象多，已用高且增长慢
  "old-used": { base: 258_000_000, perBucket: 3_000_000, gauge: true, spread: 0.35 },
  "old-committed": { base: 318_000_000, perBucket: 1_500_000, gauge: true, spread: 0.3 },
  "old-max": { base: 1_610_612_736, perBucket: 0, constant: true, spread: 0.5 },

  // 元空间：类元数据，非堆，增长慢
  "metaspace-used": { base: 92_000_000, perBucket: 400_000, gauge: true, spread: 0.25 },
  "metaspace-committed": { base: 108_000_000, perBucket: 300_000, gauge: true, spread: 0.25 },
  // 元空间默认不设上限（-XX:MaxMetaspaceSize 未开启即无上限），没有可信数值可报
  "metaspace-max": { base: 0, perBucket: 0, unavailable: true },

  "gc-count": { base: 1_240, perBucket: 6, spread: 0.3 },
  "gc-time": { base: 41_000, perBucket: 220, spread: 0.3 },
  "young-gc-count": { base: 9_800, perBucket: 90, spread: 0.3 },
  "young-gc-time": { base: 305_000, perBucket: 2_600, spread: 0.3 },
  "old-gc-count": { base: 46, perBucket: 1, spread: 0.3 },
  "old-gc-time": { base: 12_400, perBucket: 160, spread: 0.3 },
  "full-gc-count": { base: 7, perBucket: 0.3, spread: 0.3 },
  "full-gc-time": { base: 4_200, perBucket: 90, spread: 0.3 },
  threads: { base: 168, perBucket: 3, gauge: true, spread: 0.4 },
};

/**
 * 该实例能否区分出这个指标。两种情况为空缺：
 *
 * 1. 指标在该运行时下不存在（元空间上限）；
 * 2. 该实例的收集器不单独上报这类事件——这里刻意让 `10.0.0.9` 缺少 old/full GC，
 *    用来验证「无法区分时是空缺而不是 0」。
 *
 * 空缺一律不填 0：0 会被读成「确实发生了 0 次」，与「不知道」是两回事。
 */
function hasDistinguishableData(metric: string, instance: string): boolean {
  const profile = HEARTBEAT_PROFILE[metric];
  if (!profile || profile.unavailable) return false;
  if (instance === "10.0.0.9" && /^(old|full)-gc-/.test(metric)) return false;
  return true;
}

function seedOf(value: string): number {
  let seed = 0;
  for (const ch of value) seed = (seed * 31 + ch.charCodeAt(0)) >>> 0;
  return seed;
}

/** 一个实例在某指标上的趋势点；无可区分数据（或尚未发生）的桶返回空缺。 */
export function heartbeatPoints(metric: string, instance: string, range: string): Point[] {
  const window = seriesWindow(range, 0, now);
  const step = window.bucketSeconds * 1000;
  const alignedFrom = alignBucket(window.from, window.bucketSeconds);
  const count = Math.max(1, Math.ceil((window.to - alignedFrom) / step));
  const profile = HEARTBEAT_PROFILE[metric];
  const distinguishable = hasDistinguishableData(metric, instance);

  return Array.from({ length: count }, (_, i) => {
    const start = alignedFrom + i * step;
    const realtime = now >= start && now < start + step;
    let value: number | null = null;
    if (distinguishable) {
      const seed = seedOf(`${metric}:${instance}`);
      const jitter = ((seed + i * 2654435761) >>> 0) % 1000;
      // 实例之间先拉开倍率，否则三条线会在同一位置完全重合
      const index = heartbeatInstanceIds.indexOf(instance);
      const scale = 1 + (profile.spread ?? 0) * index;
      // gauge 值围绕基准上下波动；累计值才逐桶线性增长；常量值只有实例间差异
      const trend = profile.gauge ? 0 : profile.perBucket * i;
      const drift = profile.gauge ? Math.sin((i + (seed % 7)) / 3.2) * profile.perBucket : 0;
      const noise = profile.constant ? 0 : (jitter - 500) * (profile.gauge ? 40 : 0.2);
      const raw = (profile.base + trend + drift + noise) * scale;
      value = Math.max(0, Math.round(raw));
    }
    // 当前小时内还没到的桶不编造数值：它们尚未发生，后端没有对应行
    if (start > now) value = null;
    return {
      bucketStart: start,
      bucketEnd: start + step,
      value,
      quality: value === null ? "NO_DATA" : realtime ? "REALTIME" : "OK",
    } as Point;
  });
}

/** 实例明细：value 为该指标在窗口内的合计（与后端 valueSum 口径一致）。 */
export function heartbeatInstancesFor(metric: string, range: string): { instance: string; value: number }[] {
  return heartbeatInstanceIds.map((instance) => ({
    instance,
    value: heartbeatPoints(metric, instance, range).reduce((sum, p) => sum + (p.value ?? 0), 0),
  }));
}

/** 按指标返回每实例一条序列，形状与后端 `/reports/heartbeat/series` 一致。 */
export function heartbeatSeriesFor(
  metric: string,
  range: string,
  instances: string[]
): { instance: string; points: Point[] }[] {
  const targets = instances.length ? instances : heartbeatInstanceIds;
  return targets.map((instance) => ({ instance, points: heartbeatPoints(metric, instance, range) }));
}

export const metricList: MetricSeriesRow[] = [
  { labels: "channel=app;city=上海;", rank: 1, reportCount: 4820 },
  { labels: "channel=app;city=北京;", rank: 2, reportCount: 3100 },
  { labels: "channel=web;city=上海;", rank: 3, reportCount: 2200 },
  { labels: "__OTHER__", rank: 1001, reportCount: 940 },
];

export const dependencies: { upstream: DependencyRow[]; downstream: DependencyRow[] } = {
  upstream: [{ peer: "gateway", calls: 5200, failureRate: 0.001, avg: 12.4, tp99: 120 }],
  downstream: [
    { peer: "pay", calls: 3100, failureRate: 0.02, avg: 48.2, tp99: 620 },
    { peer: "stock", calls: 1400, failureRate: 0.0, avg: 8.1, tp99: 60 },
  ],
};

export const samples: Sample[] = Array.from({ length: 30 }, (_, i) => ({
  messageId: `order-m${i + 1}`,
  timestamp: now - i * 12_000,
  durationMs: 20 + ((i * 17) % 400),
  status: i % 11 === 0 ? "ERROR" : "0",
  summary: i % 3 === 0 ? "POST /orders" : "select_order",
  traceAvailable: i < 28,
}));

/**
 * 按聚合键生成取样条带（PRD 03 §11）。
 *
 * 每个 Name 的最近取样：同一条链路上不同 Name 的取样互不相同，
 * 因此用 name 做种子，保证同一 name 每次刷新结果稳定、不同 name 结果可区分。
 * 仍按事件时间倒序（索引 0 最新）。
 *
 * 取样条数取 `min(30, total)`，与真实口径一致：取样只覆盖实际发生的调用，
 * 上报 7 次的 Name 不可能有 30 条取样。条带长度因此随行变化。
 *
 * @param total 该聚合名在范围内的总次数；缺省按 30 条
 */
export function samplesForName(name: string, total = 30): Sample[] {
  let seed = 0;
  for (const ch of name) seed = (seed * 31 + ch.charCodeAt(0)) >>> 0;

  const count = Math.max(1, Math.min(30, total));
  // 少数「最旧」的取样原始树已过留存期：前端据此把它们从条带中剔除。
  // 只在取样足够多时才淘汰，避免小样本行被削到只剩一格。
  const expired = count >= 10 ? 3 : 0;

  return Array.from({ length: count }, (_, i) => {
    const mixed = (seed + i * 2654435761) >>> 0;
    return {
      messageId: `${slug(name)}-m${i + 1}`,
      timestamp: now - i * 11_000,
      durationMs: 5 + ((mixed >>> 8) % 3000),
      status: mixed % 13 === 0 ? "ERROR" : "0",
      summary: name,
      traceAvailable: i < count - expired,
    };
  });
}

/** 把任意聚合键压成可做 messageId 的短横线片段。 */
export function slug(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 40) || "sample";
}

/**
 * 全部 Problem 取样条带的索引，供 Trace 下钻按 messageId 反查。
 *
 * 条带是按下钻时传入的 Name 即时生成的，因此这里按同一规则把五个分类下的
 * 所有 Name 都生成一遍，保证「点条带 → 进 Trace」能查到对应条目与留存期。
 */
export function problemSampleIndex(): Sample[] {
  return Object.values(problemNames).flatMap((rows) =>
    rows.flatMap((row) => samplesForName(row.name, row.total))
  );
}

export const trace: TraceView = {
  messageId: "order-m1",
  service: "order",
  instance: "10.0.0.8",
  expired: false,
  children: [
    {
      messageId: "order-m2",
      service: "order",
      instance: "10.0.0.8",
      availability: "PRESENT",
      spans: [
        { nodeId: "n1", kind: "TRANSACTION", category: "URL", name: "POST /orders", status: "0", durationMs: 120, detail: "" },
        { nodeId: "n2", kind: "TRANSACTION", category: "SQL", name: "select_order", status: "0", durationMs: 24, detail: "select * from orders" },
        { nodeId: "n3", kind: "REMOTE_CALL", category: "CALL", name: "pay", status: "0", durationMs: 42, detail: "POST /pay" },
      ],
      children: [
        {
          messageId: "pay-m1",
          service: "pay",
          instance: "10.0.1.1",
          availability: "PRESENT",
          spans: [
            { nodeId: "n1", kind: "TRANSACTION", category: "SQL", name: "update_payment", status: "0", durationMs: 18, detail: "" },
          ],
          children: [],
        },
        {
          // 调用方已记录但下游树未到达：缺失节点
          messageId: "missing:stock:order-m1",
          service: "stock",
          instance: "",
          availability: "MISSING",
          reason: "调用方已记录该下游调用，但未收到其 MessageTree",
          spans: [],
          children: [],
        },
      ],
    },
  ],
};

export const dashboards: Dashboard[] = [
  { id: 1, orgId: 3, name: "支付核心大盘" },
  { id: 2, orgId: 4, name: "订单大盘" },
];

export const cards: Card[] = [
  {
    id: 11,
    dashboardId: 1,
    service: "pay",
    targetKind: "TRANSACTION",
    targetType: "URL",
    targetName: "POST /pay",
    formula: "failures / hits",
    unit: "RATE",
    timeRange: "RECENT_24H",
    thresholdLines: [{ direction: "ABOVE", value: 0.05 }],
  },
  {
    id: 12,
    dashboardId: 1,
    service: "pay",
    targetKind: "TRANSACTION",
    targetType: "SQL",
    targetName: "update_payment",
    formula: "tp99 - avgDuration",
    unit: "DURATION",
    timeRange: "RECENT_24H",
    thresholdLines: [],
  },
];

export const alerts: AlertRule[] = [
  {
    id: 21,
    scope: "SERVICE",
    orgId: null,
    name: "订单失败率",
    target: {
      kind: "RAW_METRIC",
      cardId: 0,
      service: "order",
      reportKind: "TRANSACTION",
      targetType: "URL",
      targetName: "POST /orders",
    },
    combinator: "AND",
    windowPoints: 3,
    conditions: [{ stat: "FAILURE_RATE", comparator: "GT", threshold: 0.05 }],
    recipients: [3],
    channels: ["EMAIL"],
    enabled: true,
    invalid: false,
  },
  {
    id: 22,
    scope: "ORGANIZATION",
    orgId: 3,
    name: "支付卡 formula 告警",
    target: {
      kind: "CARD_RESULT",
      cardId: 11,
      service: "pay",
      reportKind: "TRANSACTION",
      targetType: "URL",
      targetName: "POST /pay",
    },
    combinator: "OR",
    windowPoints: 5,
    conditions: [
      { stat: "FAILURE_RATE", comparator: "GT", threshold: 0.05 },
      { stat: "HITS", comparator: "LT", threshold: 10 },
    ],
    recipients: [3],
    channels: ["EMAIL", "DINGTALK"],
    enabled: false,
    invalid: false,
  },
];

/** 预告警三态示例。 */
export const previewResults: Record<string, { result: string; points: { minute: number; known: boolean; satisfied: boolean }[] }> = {
  TRIGGER: {
    result: "TRIGGER",
    points: [
      { minute: now, known: true, satisfied: true },
      { minute: now - 60_000, known: true, satisfied: true },
      { minute: now - 120_000, known: true, satisfied: true },
    ],
  },
  NO_TRIGGER: {
    result: "NO_TRIGGER",
    points: [
      { minute: now, known: true, satisfied: false },
      { minute: now - 60_000, known: true, satisfied: true },
      { minute: now - 120_000, known: true, satisfied: true },
    ],
  },
  INSUFFICIENT_DATA: {
    result: "INSUFFICIENT_DATA",
    points: [
      { minute: now, known: false, satisfied: false },
      { minute: now - 60_000, known: true, satisfied: true },
      { minute: now - 120_000, known: true, satisfied: true },
    ],
  },
};

export { iso };
