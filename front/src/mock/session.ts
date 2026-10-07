/**
 * Mock 会话存储（技术方案 03-api-contract.md §1.2）。
 *
 * 真实后端使用 30 分钟滑动的服务端会话，浏览器刷新不会丢失登录态。
 * mock 若只放在模块内存里，刷新即登出，会让「全流程可用」的演示体验与 PRD 描述不符。
 *
 * 因此在浏览器环境用 sessionStorage 持久化会话，在 Node 环境（动线自检脚本）
 * 退化为内存存储。两条路径都不依赖任何框架 API。
 */

interface SessionData {
  username: string;
  role: string;
  mustChangePassword: boolean;
}

const KEY = "neocat.mock.session";

interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function backing(): StorageLike {
  const globalWithStorage = globalThis as unknown as { sessionStorage?: StorageLike };
  if (globalWithStorage.sessionStorage) {
    return globalWithStorage.sessionStorage;
  }
  const memory = new Map<string, string>();
  return {
    getItem: (key) => memory.get(key) ?? null,
    setItem: (key, value) => {
      memory.set(key, value);
    },
    removeItem: (key) => {
      memory.delete(key);
    },
  };
}

export function readSession(): SessionData | null {
  const raw = backing().getItem(KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as SessionData;
  } catch {
    return null;
  }
}

export function writeSession(data: SessionData | null): void {
  if (data === null) {
    backing().removeItem(KEY);
    return;
  }
  backing().setItem(KEY, JSON.stringify(data));
}
