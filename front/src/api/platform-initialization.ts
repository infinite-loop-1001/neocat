/** 不把网络错误、空响应或字符串状态当作已初始化。 */
export async function loadInitializationStatus(request: (path: string) => Promise<unknown>): Promise<boolean> {
  const status = await request("/platform/init-status");
  if (typeof status !== "object" || status === null || !("initialized" in status)
      || typeof status.initialized !== "boolean") {
    throw new Error("平台初始化状态响应无效");
  }
  return status.initialized;
}
