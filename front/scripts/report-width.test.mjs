import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { compileComponentTemplate } from "./sfc-setup.mjs";

test("服务报表 Type/Name/趋势/Heartbeat/Dependency 显式全宽，其他页面不受影响", () => {
  for (const name of ["ReportView", "NameMetricsView", "SeriesView", "HeartbeatView", "DependencyView"]) {
    const { source, compiled } = compileComponentTemplate(`../src/views/${name}.vue`);
    assert.equal(compiled.errors.length, 0, name);
    assert.match(source, /<section class="workspace-panel service-report-panel">/, name);
  }
  for (const name of ["ServiceListView", "UsersView", "PlatformView", "OrgView", "AlertsView", "DashboardView", "DashboardDetailView", "TraceView", "LoginView"]) {
    const { source } = compileComponentTemplate(`../src/views/${name}.vue`);
    assert.doesNotMatch(source, /service-report-panel/, name);
  }
});

test("仅报表移除最大宽度，保留工作区边距、通用面板限宽与固定筛选栏", () => {
  const styles = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  const fullWidth = /\.workspace-panel\.service-report-panel[^{}]*\{([^}]+)\}/.exec(styles)?.[1];
  assert.ok(fullWidth, "需要显式报表全宽规则");
  assert.match(fullWidth, /width:\s*100%/);
  assert.match(fullWidth, /max-width:\s*none/);
  assert.match(fullWidth, /min-width:\s*0/);
  assert.match(styles, /\.workspace-panel \{[^}]*max-width:\s*1280px/);
  assert.match(styles, /\.workspace \{[^}]*padding:\s*var\(--space-6\)/);
  assert.match(styles, /\.series-layout \{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) 240px/);
  for (const [view, className] of [["MetricView", "metric-panel"], ["ProblemView", "problem-panel"]]) {
    assert.ok(compileComponentTemplate(`../src/views/${view}.vue`).source.includes(className));
    assert.ok(styles.includes(`.workspace-panel.${className}`));
  }
});
