import assert from "node:assert/strict";
import { test } from "node:test";
import { setupComponent } from "./sfc-setup.mjs";

test("报表导航首字母大写，路由仍使用小写路径", () => {
  const state = setupComponent("../src/views/ShellView.vue", {}, {
    "vue-router": {
      useRoute: () => ({ params: { service: "order" }, path: "/svc/order/heartbeat" }),
      useRouter: () => ({}),
    },
    "../api": { api: async () => [] },
  });

  assert.deepEqual(Array.from(state.domains, (domain) => domain.label), [
    "Transaction", "Event", "Problem", "Heartbeat", "Metric", "Dependency",
  ]);
  for (const domain of state.domains) {
    assert.equal(state.domainPath(domain.kind), `/svc/order/${domain.kind.toLowerCase()}`);
  }
  assert.equal(state.kind.value, "HEARTBEAT");
});
