/**
 * 在 Node 里编译并执行真实 SFC 的 setup。
 *
 * 单测直接跑页面源码，而不是复制一份「与页面无关的绘图逻辑」，
 * 这样组件重构会立刻被测试发现。仅提供 Vue 的响应式 API 与显式声明的依赖，
 * 其余 import 一律报错，避免页面悄悄引入新依赖而测试仍通过。
 */
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { parse, compileScript, compileTemplate } from "@vue/compiler-sfc";
import { transformSync } from "esbuild";
import { computed, defineComponent, nextTick, ref, watch } from "vue";

let nextTestId = 0;

export function setupComponent(file, props, dependencies = {}, id = "sfc-test", globals = {}) {
  const source = readFileSync(new URL(file, import.meta.url), "utf8");
  const { descriptor } = parse(source);
  const script = compileScript(descriptor, { id });
  const code = transformSync(script.content, { loader: "ts", format: "cjs" }).code;
  const module = { exports: {} };
  const mountedHooks = [];
  const unmountedHooks = [];
  runInNewContext(code, {
    module,
    exports: module.exports,
    // 组件里用到的浏览器全局（如 document）默认不存在；
    // 只有测试显式注入才会出现，避免页面悄悄依赖真实 DOM 而测试仍通过。
    ...globals,
    require: (name) => {
      if (name === "vue") {
        return {
          computed, defineComponent, nextTick, ref, watch,
          // 真正 useId 依赖挂载上下文；这里仅为每次真实 setup 提供唯一可访问关联 ID。
          useId: () => `test-${nextTestId++}`,
          // 默认不执行，避免「挂载即取数」的页面在测试里多打一次请求；
          // 需要验证挂载副作用（如事件监听）的测试可显式调用 state.__mounted()。
          onMounted: (fn) => { mountedHooks.push(fn); },
          onUnmounted: (fn) => { unmountedHooks.push(fn); },
        };
      }
      if (Object.hasOwn(dependencies, name)) return dependencies[name];
      if (name.endsWith(".vue") || name === "vue-echarts") return { default: {} };
      if (name === "echarts/core") return { use() {} };
      if (name.startsWith("echarts/")) return {};
      throw new Error(`Unexpected dependency: ${name}`);
    },
  });
  const state = module.exports.default.setup(props, { expose() {}, emit() {} });
  // 把生命周期钩子暴露给测试，同时保留组件 setup 的真实返回值。
  Object.defineProperties(state, {
    __mounted: { value: () => mountedHooks.forEach((fn) => fn()), enumerable: false },
    __unmounted: { value: () => unmountedHooks.forEach((fn) => fn()), enumerable: false },
  });
  return state;
}

/** 编译模板，用于断言模板结构（如事件绑定是否还在）。 */
export function compileComponentTemplate(file, id = "sfc-test") {
  const source = readFileSync(new URL(file, import.meta.url), "utf8");
  const { descriptor } = parse(source);
  return {
    source: descriptor.template.content,
    compiled: compileTemplate({ source: descriptor.template.content, id }),
  };
}
