/**
 * Mock 入口（技术方案 03-api-contract.md §9、07 §3.1–3.3）。
 *
 * 这里**不再使用 MSW Service Worker**：mock 逻辑以普通函数形式被 `client.ts` 调用。
 *
 * 原因（相对原实现的两处缺陷）：
 * 1. 原 `api.ts` 是自写假路由、根本不发 fetch，而 `mock/handlers.ts` 的 MSW 从未被命中，
 *    两套 mock 并存却互不生效；
 * 2. MSW 依赖 Service Worker 注册，在未构建 worker 脚本的环境下会静默失败。
 *
 * 现在只有一个 mock 机制：`USE_MOCK` 为真时 `client.ts` 直接调用 `mockRequest`，
 * 因此「打开开关即可演示全流程」不依赖任何浏览器 API。
 */

export { mockRequest, resetMockState } from "./router";
export * from "./model";

/** 保持与旧调用点兼容；当前无需注册任何 worker。 */
export async function registerMockWorker(): Promise<void> {
  return Promise.resolve();
}
