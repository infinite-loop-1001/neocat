/**
 * 登录/改密完成后回跳的原页面（刷新、直达链接、会话过期都走这条路径）。
 *
 * 只接受站内绝对路径：`//evil.com` 这类协议相对地址会被浏览器当成外站，
 * 必须以排除，避免刷新参数变成开放重定向。
 */
export function safeRedirect(value: unknown): string | null {
  if (typeof value !== "string") return null;
  if (!value.startsWith("/")) return null;
  if (value.startsWith("//")) return null;
  if (value === "/login" || value.startsWith("/login?") || value.startsWith("/login/")) return null;
  return value;
}
