import assert from "node:assert/strict";
import { test } from "node:test";
import { mockRequest, resetMockState } from "../src/mock/router.ts";
import { DEV_PASSWORD } from "../src/mock/dataset.ts";

test("卡片序列输出 isUndefined 数组，不输出旧 undefined 键，缺数仍是 null", async () => {
  resetMockState();
  await mockRequest("/api/login", {
    method: "POST",
    body: JSON.stringify({ username: "alice", password: DEV_PASSWORD }),
  });
  const boards = await mockRequest("/api/dashboards", { method: "GET" });
  const cards = await mockRequest(`/api/cards?dashboardId=${boards[0].id}`, { method: "GET" });
  const response = await mockRequest(`/api/cards/${cards[0].id}/series`, { method: "GET" });

  assert.ok(Object.hasOwn(response, "isUndefined"));
  assert.ok(Array.isArray(response.isUndefined));
  // 现有 mock 只提供报表趋势，不计算公式除零，不伪造除零点。
  assert.deepEqual(response.isUndefined, []);
  assert.equal(Object.hasOwn(response, "undefined"), false);
  assert.ok(response.points.some((point) => point.value === null));
});
