import type { NavigationGuard } from "vue-router";
import type { api } from "./api";
import { loadInitializationStatus } from "./api/platform-initialization";

/** 登录页保留原目标，刷新或会话过期后回到同一页，而不是统一落回首页。 */
function loginFor(to: { fullPath: string }) {
  return to.fullPath.startsWith("/login") ? "/login" : { path: "/login", query: { redirect: to.fullPath } };
}

/** 初始化检查先于会话检查；错误页本身不再次查询，避免重定向循环。 */
export function createAccessGuard(request: typeof api): NavigationGuard {
  return async (to) => {
    if (to.path === "/initialization-error") return true;
    let initialized: boolean;
    try {
      initialized = await loadInitializationStatus(request);
    } catch {
      return "/initialization-error";
    }
    if (!initialized) return to.path === "/initialize" ? true : "/initialize";
    if (to.path === "/initialize") return "/login";
    if (to.path === "/login") return true;
    try {
      const me = await request<{ mustChangePassword: boolean }>("/me");
      if (me.mustChangePassword && to.path !== "/change-password") {
        return { path: "/change-password", query: { redirect: to.fullPath } };
      }
      if (!me.mustChangePassword && to.path === "/change-password") {
        return "/services";
      }
      return true;
    } catch {
      return loginFor(to);
    }
  };
}
