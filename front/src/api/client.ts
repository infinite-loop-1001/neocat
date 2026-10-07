/**
 * 统一 API 客户端（技术方案 03-api-contract.md §9）。
 *
 * 设计要点：
 * 1. **唯一入口**：所有页面通过 `api()` 访问后端，不直接使用 fetch；
 * 2. **单一开关**：mock 与真实请求的分支只在这里判断一次；
 * 3. **缺口语义**：后端以 `value: null` 表示缺口，故响应类型允许 null 值，
 *    前端不得把 null 当作 0 渲染（PRD 03 §5）。
 */

import { USE_MOCK, API_BASE } from "../config";
import { mockRequest, registerMockWorker } from "../mock";
import { ApiError } from "./error";
import { ERROR_CODE } from "./error-codes";

export { ApiError };

/** 启动时决定是否注册 mock worker；仅在 mock 模式下注册。 */
export async function initApi(): Promise<void> {
  if (USE_MOCK) {
    await registerMockWorker();
  }
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined | null>;
  /** Acceptance-only option. Never included in real HTTP requests. */
  mockMetric?: string;
}

function buildQuery(query?: RequestOptions["query"]): string {
  if (!query) return "";
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === "") return;
    params.append(key, String(value));
  });
  const encoded = params.toString();
  return encoded ? `?${encoded}` : "";
}

/** 发起一次 API 调用；路径不含 `/api` 前缀。 */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const url = `${API_BASE}${path}${buildQuery(options.query)}`;
  const method = options.method ?? "GET";

  if (USE_MOCK) {
    const mockUrl = options.mockMetric
      ? `${API_BASE}${path}${buildQuery({ ...options.query, mockMetric: options.mockMetric })}` : url;
    return mockRequest<T>(mockUrl, {
      method,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  }

  const response = await fetch(url, {
    method,
    credentials: "include",
    headers: options.body === undefined ? undefined : { "Content-Type": "application/json" },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });

  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;

  if (!response.ok) {
    const code = typeof payload?.code === "number" ? payload.code : ERROR_CODE.INTERNAL_ERROR;
    const message = payload?.message ?? `请求失败（${response.status}）`;
    throw new ApiError(response.status, code, message);
  }
  return payload as T;
}
