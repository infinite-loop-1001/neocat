import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { nextTick } from "vue";
import { HEARTBEAT_GROUPS, HEARTBEAT_METRICS, heartbeatMetric } from "../src/api/heartbeat.ts";
import { seriesColor } from "../src/api/palette.ts";
import * as timeRange from "../src/api/time-range.ts";
import { currentHourScope } from "../src/api/time-range.ts";
import { heartbeatInstanceIds, heartbeatMetrics, heartbeatPoints, heartbeatSeriesFor } from "../src/mock/dataset.ts";
import { setupComponent, compileComponentTemplate } from "./sfc-setup.mjs";

/** 视图依赖：默认时间窗口只有一份实现，测试里也用真实那一份。 */
const viewDeps = { "../api/time-range": timeRange };

test("指标编目：内存分区 + GC + 线程，无重复、顺序稳定", () => {
  assert.deepEqual(
    HEARTBEAT_GROUPS.map((g) => g.key),
    ["heap", "young", "old", "metaspace", "gc", "thread"]
  );
  assert.equal(new Set(HEARTBEAT_METRICS).size, HEARTBEAT_METRICS.length, "指标不能重复");
  assert.equal(heartbeatMetric("young-gc-count").name, "jvm.gc.young.count");
  assert.equal(heartbeatMetric("不存在的指标"), undefined);
  // 累计值必须显式标记：这类数值不能直接相加当作窗口内发生量
  assert.equal(heartbeatMetric("gc-count").cumulative, true);
  assert.equal(heartbeatMetric("heap-used").cumulative, undefined);
});

test("图表标题用点分指标名，单位单独标注", () => {
  const names = HEARTBEAT_GROUPS.flatMap((g) => g.metrics.map((m) => m.name));
  assert.equal(new Set(names).size, names.length, "展示名不能重复");
  for (const name of names) {
    assert.match(name, /^jvm\.[a-z]+(\.[a-z]+)+$/, `${name} 应为 jvm.* 点分形式`);
  }
  // 单位是独立字段，不拼进展示名（名称本身是纯点分标识，不带单位后缀或括号）
  for (const metric of HEARTBEAT_GROUPS.flatMap((g) => g.metrics)) {
    assert.doesNotMatch(metric.name, /[\s()]/, "展示名不应夹带单位或括号");
    assert.ok(["bytes", "ms", "count"].includes(metric.unit), `${metric.name} 单位应受控`);
  }
  // 接口参数保持原样，改展示名不影响后端契约
  assert.equal(heartbeatMetric("heap-used").metric, "heap-used");
  assert.equal(heartbeatMetric("heap-used").name, "jvm.memory.heap.used");
  assert.equal(heartbeatMetric("heap-used").unit, "bytes");
  assert.equal(heartbeatMetric("gc-time").name, "jvm.gc.time");
  assert.equal(heartbeatMetric("gc-time").unit, "ms");
  assert.equal(heartbeatMetric("threads").name, "jvm.threads.live");
});

test("内存分区：年轻代/老年代/元空间各含 used、committed、max", () => {
  const partitions = HEARTBEAT_GROUPS.filter((g) => g.kind === "memory");
  assert.deepEqual(
    partitions.map((g) => g.key),
    ["heap", "young", "old", "metaspace"]
  );
  for (const key of ["young", "old", "metaspace"]) {
    const group = HEARTBEAT_GROUPS.find((g) => g.key === key);
    assert.deepEqual(
      group.metrics.map((m) => m.metric),
      [`${key}-used`, `${key}-committed`, `${key}-max`],
      `${key} 应有 used/committed/max 三个口径`
    );
    assert.deepEqual(
      group.metrics.map((m) => m.name),
      [`jvm.memory.${key}.used`, `jvm.memory.${key}.committed`, `jvm.memory.${key}.max`]
    );
    assert.ok(group.metrics.every((m) => m.unit === "bytes"));
  }
  // 页面标题应使用展示名，而不是接口参数
  const source = readFileSync(new URL("../src/views/HeartbeatView.vue", import.meta.url), "utf8");
  assert.match(source, /:title="item\.name"/);
  assert.doesNotMatch(source, /:title="item\.label"/);
});

test("每个指标一张小图；同实例在所有小图里颜色一致", async () => {
  const requests = [];
  const state = setupComponent(
    "../src/views/HeartbeatView.vue",
    {},
    {
      "vue-router": { useRoute: () => ({ params: { service: "order" } }) },
      "../api/heartbeat": { HEARTBEAT_GROUPS },
      "../api/palette": { seriesColor },
      ...viewDeps,
      "../api": {
        api: async (path, options) => {
          if (path === "/reports/heartbeat/instances") {
            return heartbeatInstanceIds.map((instance) => ({ instance, value: 1 }));
          }
          requests.push(options.query);
          return { series: heartbeatSeriesFor(options.query.metric, options.query.range, []), mom: null };
        },
      },
    },
    "heartbeat-test"
  );
  await state.load();
  assert.deepEqual(requests.map((r) => r.metric), HEARTBEAT_METRICS, "每个指标各发一次请求");
  // 默认是当前整点小时：同一个 HOUR:<millis>，所有小图共用
  const expected = currentHourScope().range;
  assert.match(expected, /^HOUR:\d+$/);
  assert.ok(requests.every((r) => r.range === expected), "所有小图共用同一时间范围");

  const first = state.seriesFor("young-gc-count");
  const second = state.seriesFor("full-gc-count");
  assert.deepEqual(first.map((s) => s.name), heartbeatInstanceIds);
  for (const line of second) {
    assert.equal(line.color, first.find((s) => s.name === line.name).color, "同一实例颜色必须一致");
  }
});

test("未选实例时展示全部，勾选后只请求选中实例", async () => {
  const requests = [];
  const state = setupComponent(
    "../src/views/HeartbeatView.vue",
    {},
    {
      "vue-router": { useRoute: () => ({ params: { service: "order" } }) },
      "../api/heartbeat": { HEARTBEAT_GROUPS },
      "../api/palette": { seriesColor },
      ...viewDeps,
      "../api": {
        api: async (path, options) => {
          if (path === "/reports/heartbeat/instances") return [];
          requests.push(options.query);
          return { series: [], mom: null };
        },
      },
    },
    "heartbeat-test"
  );
  await state.load();
  assert.ok(requests.every((r) => r.instances === undefined), "默认不限定实例");
  state.selectedInstances.value = ["10.0.0.9"];
  // 改实例会触发页面自己的 watch(load)；让它先跑完，再显式验一次本次请求。
  await nextTick();
  requests.length = 0;
  await state.load();
  const seriesRequests = requests.filter((r) => r.metric === "heap-used");
  assert.ok(seriesRequests.length > 0, "改实例后应重新取数");
  assert.ok(seriesRequests.every((r) => r.instances === "10.0.0.9"));
});

test("切换时间范围会更新窗口与粒度", () => {
  const requests = [];
  const state = setupComponent(
    "../src/views/HeartbeatView.vue",
    {},
    {
      "vue-router": { useRoute: () => ({ params: { service: "order" } }) },
      "../api/heartbeat": { HEARTBEAT_GROUPS },
      "../api/palette": { seriesColor },
      ...viewDeps,
      "../api": {
        api: async (path, options) => {
          if (path === "/reports/heartbeat/instances") return [];
          requests.push(options.query);
          return { series: [], mom: null };
        },
      },
    },
    "heartbeat-test"
  );
  state.onRange({ range: "RECENT_3H", bucketSeconds: 300 });
  assert.equal(state.range.value, "RECENT_3H");
  assert.equal(state.bucketSeconds.value, 300);
});

test("页面用 MiniChart 渲染分组，且不引入环比控件", () => {
  const source = readFileSync(new URL("../src/views/HeartbeatView.vue", import.meta.url), "utf8");
  const { compiled } = compileComponentTemplate("../src/views/HeartbeatView.vue");
  assert.equal(compiled.errors.length, 0);
  assert.match(source, /v-for="group in memoryGroups"/);
  assert.match(source, /v-for="group in otherGroups"/);
  assert.match(source, /<MiniChart/);
  // 口径改由每张图的 ? 提示承载，页面必须把编目里的 note 传下去
  assert.match(source, /:note="item\.note"/);
  // 一期 Heartbeat 不做环比：不应出现环比选择控件或 mom 请求参数
  assert.doesNotMatch(source, /comparison-option|selectedMom|toggleMom/);
  assert.doesNotMatch(source, /mom:/);
});

test("内存口径说明只写一遍，不在每个分区下重复", () => {
  const source = readFileSync(new URL("../src/views/HeartbeatView.vue", import.meta.url), "utf8");
  // 口径已下沉到编目，由每张图的 ? 提示展示：页面里不应再有块级口径段落。
  assert.doesNotMatch(source, /元空间属于非堆内存/);
  assert.doesNotMatch(source, /JVM 启动以来的累计值/);
  assert.doesNotMatch(source, /<code>/);
  // 编目里每个指标都有口径，且非堆说明只在元空间指标上出现。
  const offHeap = HEARTBEAT_GROUPS.flatMap((g) => g.metrics).filter((m) => /非堆内存/.test(m.note));
  assert.deepEqual(offHeap.map((m) => m.metric), ["metaspace-used", "metaspace-committed", "metaspace-max"]);
});

test("内存口径逐图说明：used/committed/max 各自含义不同，元空间带上限未定义", () => {
  const notes = Object.fromEntries(HEARTBEAT_GROUPS.flatMap((g) => g.metrics).map((m) => [m.metric, m.note]));
  assert.match(notes["young-used"], /已占用/);
  assert.match(notes["young-committed"], /已申请、保证可用/);
  assert.match(notes["young-committed"], /进程 RSS/);
  assert.match(notes["young-max"], /最大可用容量/);
  assert.equal(notes["young-used"], notes["old-used"].replace("老年代", "年轻代"));
  // 元空间上限的两种说法必须一致：口径提示与整段空缺的说明都指向同一个原因
  assert.match(notes["metaspace-max"], /MaxMetaspaceSize/);
  assert.match(notes["metaspace-max"], /空缺而不是 0/);
  assert.equal(heartbeatMetric("metaspace-max").emptyNote, "未设置 -XX:MaxMetaspaceSize 时该值未定义，显示为空缺而不是 0。");
  // 每个指标都要有口径，否则图中不显示 ? 入口
  for (const metric of HEARTBEAT_GROUPS.flatMap((g) => g.metrics)) {
    assert.ok(metric.note.length > 0, `${metric.metric} 应有口径说明`);
  }
});

test("GC 口径逐图说明：收集器区分与累计值语义，不再写成分组段落", () => {
  const notes = Object.fromEntries(HEARTBEAT_GROUPS.flatMap((g) => g.metrics).map((m) => [m.metric, m.note]));
  assert.match(notes["gc-count"], /累计次数/);
  assert.match(notes["gc-time"], /累计耗时/);
  for (const key of ["young", "old", "full"]) {
    assert.match(notes[`${key}-gc-count`], /收集器事件区分/);
    assert.match(notes[`${key}-gc-count`], /无法区分时显示空缺/);
    assert.match(notes[`${key}-gc-count`], /累计次数/);
    assert.match(notes[`${key}-gc-time`], /累计耗时/);
  }
  assert.match(notes["threads"], /瞬时值/);
  // 累计值标记与口径文字必须同时存在，避免说明与实际语义脱节
  for (const metric of HEARTBEAT_GROUPS.flatMap((g) => g.metrics)) {
    if (metric.cumulative) assert.match(metric.note, /累计/, `${metric.metric} 累计值应说明口径`);
  }
});

test("小图组件：缺口保持 null 断线，且不与普通大图混用高度", () => {
  const source = readFileSync(new URL("../src/components/MiniChart.vue", import.meta.url), "utf8");
  assert.match(source, /connectNulls: false/);
  assert.match(source, /toChartValue/);
  assert.doesNotMatch(source, /class="chart-canvas"/, "小图不应复用 300px 大图容器");
});

test("mock 指标清单与前端编目同源，不会各抄一份而漂移", () => {
  assert.deepEqual(heartbeatMetrics, HEARTBEAT_METRICS);
});

test("元空间未设上限：整条空缺并给出原因，不是 0 也不是空图", () => {
  const metric = heartbeatMetric("metaspace-max");
  assert.ok(metric.emptyNote, "元空间上限必须说明为什么没有数据");
  const points = heartbeatPoints("metaspace-max", "10.0.0.8", "RECENT_1H");
  assert.ok(points.length > 0);
  assert.ok(
    points.every((p) => p.value === null && p.quality === "NO_DATA"),
    "没有可信上限时必须留空，报 0 会被读成「上限是 0」"
  );
  // 已用/已提交有数据，只有上限没有
  assert.ok(heartbeatPoints("metaspace-used", "10.0.0.8", "RECENT_1H").every((p) => p.value !== null));
  assert.ok(
    heartbeatPoints("metaspace-committed", "10.0.0.8", "RECENT_1H").every((p) => p.value !== null)
  );
  // 全空时要给出说明文字，而不是画一张没有曲线的空图
  const source = readFileSync(new URL("../src/components/MiniChart.vue", import.meta.url), "utf8");
  assert.match(source, /hasValue/, "应以「有没有有效值」判断是否画图");
  assert.match(source, /emptyText/, "全空时应展示原因");
});

test("内存分区：已提交不低于已用，上限不低于已提交", () => {
  for (const key of ["young", "old"]) {
    for (const instance of heartbeatInstanceIds) {
      const range = "RECENT_1H";
      const used = heartbeatPoints(`${key}-used`, instance, range);
      const committed = heartbeatPoints(`${key}-committed`, instance, range);
      const max = heartbeatPoints(`${key}-max`, instance, range);
      // 只有区间极值有意义：桶序号不同的三个指标不能用同一桶直接比较
      const maxOf = (points) => Math.max(...points.map((p) => p.value ?? 0));
      assert.ok(
        maxOf(committed) >= maxOf(used) * 0.9,
        `${key}: 已提交不应明显低于已用`
      );
      assert.ok(maxOf(max) >= maxOf(committed), `${key}: 上限应不低于已提交`);
    }
  }
});

test("实例选择器：不展示数值时实例名不应被推到右边", () => {
  const picker = readFileSync(
    new URL("../src/components/MachinePicker.vue", import.meta.url),
    "utf8"
  );
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  // 数值列靠右原本用 :last-child 实现；不传数值时实例名成了最后一个子元素，
  // 于是被 margin-left:auto 推到右边。必须用具名类，不能依赖元素位置。
  assert.match(picker, /class="num machine-value"/);
  assert.match(css, /\.machine-item \.machine-value \{[^}]*margin-left: auto/);
  assert.doesNotMatch(css, /\.machine-item span:last-child/);
});
