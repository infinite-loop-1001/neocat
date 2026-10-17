/**
 * 运行时行为开关（技术方案 03-api-contract.md §9）。
 *
 * `VITE_USE_MOCK` 未设置或为 "true" 时使用本地 mock 数据（默认）；
 * 显式设为 "false" 时走真实后端（Vite proxy → localhost:8080）。
 *
 * 该判断只存在于这一处，业务代码通过 `api/client.ts` 统一入口访问接口，对开关无感。
 *
 * 注意：`import.meta.env` 只在 Vite 构建/开发环境中存在。Node 直接运行
 * （例如动线自检脚本 `npm run flow`）时它为空，此时按「使用 mock」处理，
 * 因为 mock 逻辑本身就是纯函数，无需浏览器环境。
 */

interface ViteEnv {
  VITE_USE_MOCK?: string;
}

/**
 * 必须写成字面量 `import.meta.env`：Vite 只在源码中出现该完整表达式时做静态替换。
 * 先赋给中间变量（`const meta = import.meta; meta.env`）会让替换失效，
 * 浏览器里 `import.meta.env` 为 undefined，开关将永远按「使用 mock」处理。
 */
const viteEnv = import.meta.env as unknown as ViteEnv | undefined;

export const USE_MOCK = viteEnv?.VITE_USE_MOCK !== "false";

/** 真实后端基地址；开发期由 Vite proxy 转发，故保持相对路径。 */
export const API_BASE = "/api";
