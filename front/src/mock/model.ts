/**
 * Mock 数据集类型（与后端接口契约对应，技术方案 03-api-contract.md）。
 */

import type { Point } from "../api/types";

export interface PlatformProfile {
  timezone: string;
  initialized: boolean;
  slow: { url: number; sql: number; call: number; cache: number };
  channels: { email: boolean; dingtalk: boolean; feishu: boolean };
}

export interface Account {
  id: number;
  username: string;
  /** 仅存在于 mock：真实后端只保存口令哈希，绝不会把口令返回给前端。 */
  password?: string;
  role: "USER" | "ADMIN" | "SUPER_ADMIN";
  status: "ENABLED" | "DISABLED";
  mustChangePassword: boolean;
}

export interface OrgNode {
  id: number;
  name: string;
  parentId: number | null;
  leaf: boolean;
  memberCount: number;
}

export interface ServiceRow {
  name: string;
  instances: string[];
}

export interface Sample {
  messageId: string;
  timestamp: number;
  durationMs: number;
  status: string;
  summary: string;
  traceAvailable: boolean;
}

export interface TraceSpan {
  nodeId: string;
  kind: string;
  category: string;
  name: string;
  status: string;
  durationMs: number;
  detail: string;
}

export interface TraceNodeModel {
  messageId: string;
  service: string;
  instance: string;
  availability: "PRESENT" | "MISSING" | "EXPIRED";
  reason?: string;
  spans: TraceSpan[];
  children: TraceNodeModel[];
}

export interface TraceView {
  messageId: string;
  service: string;
  instance: string;
  expired: boolean;
  children: TraceNodeModel[];
}

export interface ThresholdLineModel {
  direction: "ABOVE" | "BELOW";
  value: number;
}

export interface Card {
  id: number;
  dashboardId: number;
  service: string;
  targetKind: string;
  targetType: string;
  targetName: string;
  formula: string;
  unit: string;
  timeRange: string;
  thresholdLines: ThresholdLineModel[];
}

export interface Dashboard {
  id: number;
  orgId: number;
  name: string;
}

export interface AlertConditionModel {
  stat: string;
  comparator: string;
  threshold: number;
}

export interface AlertTargetModel {
  /** 目标来源：原始指标 或 卡片结果。 */
  kind: "RAW_METRIC" | "CARD_RESULT";
  cardId: number;
  service: string;
  reportKind: string;
  /** 指标对象分类，如 URL / SQL。 */
  targetType: string;
  /** 指标对象名称。 */
  targetName: string;
}

export interface AlertRule {
  id: number;
  scope: "SERVICE" | "ORGANIZATION";
  orgId: number | null;
  name: string;
  target: AlertTargetModel;
  combinator: "AND" | "OR";
  windowPoints: number;
  conditions: AlertConditionModel[];
  recipients: number[];
  channels: string[];
  enabled: boolean;
  invalid: boolean;
}

export interface DependencyRow {
  peer: string;
  calls: number;
  failureRate: number;
  avg: number;
  tp99: number;
}

export interface MetricSeriesRow {
  labels: string;
  rank: number;
  reportCount: number;
}

export interface SeriesResponse {
  service: string;
  kind: string;
  type?: string;
  name?: string;
  stat: string;
  bucketSeconds: number;
  points: Point[];
}
