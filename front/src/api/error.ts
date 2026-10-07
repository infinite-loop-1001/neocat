/**
 * API 错误类型（独立模块）。
 *
 * 单独成文件的原因：`mock/router.ts` 需要抛出 ApiError，而 `api/client.ts` 需要调用 mock。
 * 若 ApiError 定义在 client.ts 中就会形成循环导入，导致 `instanceof` 判定在不同模块实例间失效。
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: number;

  constructor(status: number, code: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }

  static is(error: unknown): error is ApiError {
    return error instanceof ApiError || (typeof error === "object" && error !== null && "code" in error);
  }
}
