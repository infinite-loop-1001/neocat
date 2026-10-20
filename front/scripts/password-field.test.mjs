import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";

const VIEWS_WITH_PASSWORDS = [
  "../src/views/LoginView.vue",
  "../src/views/ChangePasswordView.vue",
  "../src/views/InitializeView.vue",
];

function field(props = {}) {
  return setupComponent("../src/components/PasswordField.vue", { modelValue: "", ...props });
}

test("密码输入默认掩码，点击后显示明文，再次点击恢复掩码", () => {
  const state = field();
  assert.equal(state.visible.value, false);
  state.toggle();
  assert.equal(state.visible.value, true);
  state.toggle();
  assert.equal(state.visible.value, false);
});

test("切换明文只改显示方式，不回传也不丢当前输入", () => {
  const state = field({ modelValue: "secret-123" });
  state.toggle();
  assert.equal(state.visible.value, true);
  assert.equal(state.__emitted.length, 0, "切换不能触发 v-model 回写");
  const target = { value: "secret-456" };
  state.onInput({ target });
  assert.deepEqual(JSON.parse(JSON.stringify(state.__emitted)), [["update:modelValue", "secret-456"]]);
});

test("密码框透传 id、autocomplete、长度、禁用与占位等约束", () => {
  const template = compileComponentTemplate("../src/components/PasswordField.vue");
  assert.deepEqual(template.compiled.errors, []);
  for (const binding of ["id", "autocomplete", "required", "minlength", "disabled", "placeholder"]) {
    assert.ok(template.source.includes(`:${binding}="`), `模板应透传 ${binding}`);
  }
  assert.match(template.source, /:type="visible \? 'text' : 'password'"/);
  assert.match(template.source, /:value="modelValue"/, "受控 value，切换类型不会清空");
});

test("显示/隐藏按钮是 type=button 且带可读标签，不会提交表单", () => {
  const template = compileComponentTemplate("../src/components/PasswordField.vue");
  assert.match(template.source, /type="button"/);
  assert.match(template.source, /:aria-label="visible \? '隐藏密码' : '显示密码'"/);
  assert.match(template.source, /@click="toggle"/);
});

test("显隐按钮用图标而不是汉字，宽度不随文案变化", () => {
  const template = compileComponentTemplate("../src/components/PasswordField.vue");
  assert.deepEqual(template.compiled.errors, []);
  assert.match(template.source, /<svg[\s\S]*?<\/svg>/, "按钮内是矢量图标");
  assert.doesNotMatch(template.source, /\{\{\s*visible\s*\?\s*"隐藏"\s*:\s*"显示"\s*\}\}/, "不再渲染中文文案");
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  assert.match(css, /\.password-toggle\s*\{[^}]*display:\s*inline-flex/);
  assert.match(css, /\.password-toggle\s*\{[^}]*justify-content:\s*center/);
});

test("禁用时按钮一并禁用，避免与提交中的表单状态不一致", () => {
  const template = compileComponentTemplate("../src/components/PasswordField.vue");
  assert.match(template.source, /:disabled="disabled"/);
});

test("所有密码输入都改用可切换明文的 PasswordField", () => {
  for (const view of VIEWS_WITH_PASSWORDS) {
    const template = compileComponentTemplate(view);
    assert.deepEqual(template.compiled.errors, []);
    assert.ok(!/type="password"/.test(template.source), `${view} 不应再手写掩码输入`);
    assert.match(template.source, /<PasswordField\b/, `${view} 应使用 PasswordField`);
  }
  const users = compileComponentTemplate("../src/views/UsersView.vue");
  assert.deepEqual(users.compiled.errors, []);
  assert.ok(!/type="password"/.test(users.source));
  assert.match(users.source, /<PasswordField\b/);
});

test("各密码字段保留原有 id、自动填充与约束，方便浏览器和用例定位", () => {
  const expectations = [
    ["../src/views/LoginView.vue", [/id="password"/, /autocomplete="current-password"/, /required/]],
    ["../src/views/ChangePasswordView.vue", [/id="old"/, /id="next"/, /id="confirm"/]],
    ["../src/views/InitializeView.vue", [/id="admin-password"/, /id="confirm-password"/, /minlength="8"/, /:disabled="busy"/]],
  ];
  for (const [view, patterns] of expectations) {
    const { source } = compileComponentTemplate(view);
    for (const pattern of patterns) assert.match(source, pattern, `${view} 缺少 ${pattern}`);
  }
  const change = compileComponentTemplate("../src/views/ChangePasswordView.vue");
  assert.equal((change.source.match(/autocomplete="new-password"/g) ?? []).length, 2);
  const initialize = compileComponentTemplate("../src/views/InitializeView.vue");
  assert.equal((initialize.source.match(/autocomplete="new-password"/g) ?? []).length, 2);
});

test("密码按钮样式存在且不撑破窄容器", () => {
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  assert.match(css, /\.password-field\s*\{[^}]*display: flex/);
  assert.match(css, /\.password-field \.field\s*\{[^}]*min-width: 0/);
  assert.match(css, /\.password-toggle\s*\{[^}]*white-space: nowrap/);
});
