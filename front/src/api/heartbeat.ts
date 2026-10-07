/**
 * JVM Heartbeat 指标编目（PRD 02 §9、PRD 03 §10）。
 *
 * 指标按「排」分组，页面每组渲染一排小趋势图，与指标下拉框相比能同时看到
 * 全部指标、少一次交互。
 *
 * 内存分区（年轻代／老年代／元空间）各自独立成组：分区的 used、committed、max
 * 量纲相同但数量级差很远，放在同一张图里会互相压扁，分开更可读。
 *
 * 展示名用点分指标名（`jvm.memory.young.committed`），单位单独显示。
 * 注意：这不是某个监控框架的原生指标名。业内没有唯一规范（例如 Micrometer 用
 * `jvm.memory.used` 加内存池标签），这里沿用常见 JVM 术语按点分形式展开，
 * 只作为展示名使用，与接口参数 `metric` 无关。
 *
 * `metric` 是 `/reports/heartbeat/series?metric=` 的参数值，与展示名分开：
 * 改展示名不影响接口，接口改名才需要动这里。
 */

/** 内存分区的三个口径，与 JVM MemoryPoolMXBean 的属性同名。 */
type MemoryScope = "used" | "committed" | "max";

export interface HeartbeatMetric {
  /** 接口参数值，`/reports/heartbeat/series?metric=` 用。 */
  metric: string;
  /** 展示用的点分指标名。 */
  name: string;
  /** 纵轴单位，与展示名分开标注。 */
  unit: "bytes" | "ms" | "count";
  /**
   * 该指标的统计口径，展示在小图标题右侧的 `?` 提示里（PRD 03 §10）。
   *
   * 放在编目而不是页面模板里：同口径的指标（各分区的 committed）只有一份文字，
   * 增删指标只动这里，页面不必再写整段口径说明。
   * 与 `emptyNote` 分工不同——`note` 解释「这个数字是什么」，
   * `emptyNote` 解释「为什么这条曲线整段是空的」。
   */
  note: string;
  /**
   * 累计值：JVM 启动以来的单调计数。
   *
   * 展示时要注意两点——不能把不同时间的累计值直接相加当作「窗口内发生量」，
   * 且 JVM 重启会让计数归零。这里标记出来供图表与说明使用。
   */
  cumulative?: boolean;
  /**
   * 该指标常整段没有数据时的解释（例如元空间默认无上限）。
   *
   * 与「当前范围内没有上报数据」不同：这不是采集缺失，而是该值在多数运行时下
   * 本来就不存在。不解释的话，空图会被当成页面故障。
   */
  emptyNote?: string;
}

export interface HeartbeatGroup {
  key: string;
  label: string;
  /**
   * 分组归属，供视图附加说明用：
   * - `memory` 内存分区（含堆总览）；
   * - `gc` GC 事件；
   * - `thread` 线程。
   */
  kind: "memory" | "gc" | "thread";
  metrics: HeartbeatMetric[];
}

/** 元空间是非堆内存，不计入堆，与堆/年轻代/老年代不存在求和关系。 */
const OFF_HEAP_NOTE = "元空间是非堆内存，不计入堆，与堆、年轻代、老年代不存在求和关系。";

/** 累计值（GC 次数/耗时）只能看增量与重置，不能跨时间相加。 */
const GC_COUNT_NOTE = "JVM 启动以来的累计次数，只能看增量与重置；不同时间的累计值不能相加。";
const GC_TIME_NOTE = "JVM 启动以来的累计耗时，重置换零表示实例重启；不同时间的累计值不能相加。";
const GC_COLLECTOR_NOTE = "按收集器事件区分，无法区分时显示空缺而不是 0。";

/** 元空间上限默认不存在，说明为什么它是空缺而不是 0。 */
const META_MAX_NOTE = "未设置 -XX:MaxMetaspaceSize 时该值未定义，显示为空缺而不是 0。";

/** 生成一个内存分区的三个口径指标，保证命名与顺序一致。 */
function memoryPartition(partition: string): HeartbeatMetric[] {
  const label = partition === "young" ? "年轻代" : partition === "old" ? "老年代" : "元空间";
  const scopeNote: Record<MemoryScope, string> = {
    used: `${label}已占用：当前已使用的容量，随对象分配与回收波动，不是上限。`,
    committed: `${label}已申请、保证可用的容量；不是对象实际占用，也不等于进程 RSS，可能高于已用。`,
    max: `${label}最大可用容量；达到上限后继续分配会触发 OOM，而不是继续增长。`,
  };
  return (["used", "committed", "max"] as MemoryScope[]).map((scope) => ({
    metric: `${partition}-${scope}`,
    name: `jvm.memory.${partition}.${scope}`,
    unit: "bytes",
    // 元空间不是堆的一部分：把非堆口径并进同一个提示，避免再出现块级说明。
    note: partition === "metaspace"
      ? `${scopeNote[scope]}${OFF_HEAP_NOTE}${scope === "max" ? META_MAX_NOTE : ""}`
      : scopeNote[scope],
    // 元空间默认不设上限，除非显式开启 -XX:MaxMetaspaceSize
    emptyNote:
      partition === "metaspace" && scope === "max" ? META_MAX_NOTE : undefined,
  }));
}

export const HEARTBEAT_GROUPS: HeartbeatGroup[] = [
  {
    key: "heap",
    label: "堆内存",
    kind: "memory",
    metrics: [
      {
        metric: "heap-used",
        name: "jvm.memory.heap.used",
        unit: "bytes",
        note: "堆已占用：当前已使用的堆容量，随对象分配与回收波动，不是堆上限。",
      },
      {
        metric: "heap-max",
        name: "jvm.memory.heap.max",
        unit: "bytes",
        note: "堆最大可用容量；堆由年轻代与老年代组成，这里不拆分分区。",
      },
    ],
  },
  { key: "young", label: "年轻代", kind: "memory", metrics: memoryPartition("young") },
  { key: "old", label: "老年代", kind: "memory", metrics: memoryPartition("old") },
  { key: "metaspace", label: "元空间", kind: "memory", metrics: memoryPartition("metaspace") },
  {
    key: "gc",
    label: "GC 信息",
    kind: "gc",
    metrics: [
      { metric: "gc-count", name: "jvm.gc.count", unit: "count", note: GC_COUNT_NOTE, cumulative: true },
      { metric: "gc-time", name: "jvm.gc.time", unit: "ms", note: GC_TIME_NOTE, cumulative: true },
      {
        metric: "young-gc-count",
        name: "jvm.gc.young.count",
        unit: "count",
        note: `年轻代 GC ${GC_COLLECTOR_NOTE}${GC_COUNT_NOTE}`,
        cumulative: true,
      },
      {
        metric: "young-gc-time",
        name: "jvm.gc.young.time",
        unit: "ms",
        note: `年轻代 GC 耗时。${GC_COLLECTOR_NOTE}${GC_TIME_NOTE}`,
        cumulative: true,
      },
      {
        metric: "old-gc-count",
        name: "jvm.gc.old.count",
        unit: "count",
        note: `老年代 GC ${GC_COLLECTOR_NOTE}${GC_COUNT_NOTE}`,
        cumulative: true,
      },
      {
        metric: "old-gc-time",
        name: "jvm.gc.old.time",
        unit: "ms",
        note: `老年代 GC 耗时。${GC_COLLECTOR_NOTE}${GC_TIME_NOTE}`,
        cumulative: true,
      },
      {
        metric: "full-gc-count",
        name: "jvm.gc.full.count",
        unit: "count",
        note: `Full GC ${GC_COLLECTOR_NOTE}${GC_COUNT_NOTE}`,
        cumulative: true,
      },
      {
        metric: "full-gc-time",
        name: "jvm.gc.full.time",
        unit: "ms",
        note: `Full GC 耗时。${GC_COLLECTOR_NOTE}${GC_TIME_NOTE}`,
        cumulative: true,
      },
    ],
  },
  {
    key: "thread",
    label: "线程信息",
    kind: "thread",
    metrics: [
      {
        metric: "threads",
        name: "jvm.threads.live",
        unit: "count",
        note: "当前存活线程数（守护与非守护之和），是瞬时值，不是线程创建累计数。",
      },
    ],
  },
];

/** 全部接口参数值，顺序与页面一致。 */
export const HEARTBEAT_METRICS: string[] = HEARTBEAT_GROUPS.flatMap((g) =>
  g.metrics.map((m) => m.metric)
);

/** 全部展示名，供测试与搜索匹配用。 */
export const HEARTBEAT_METRIC_NAMES: string[] = HEARTBEAT_GROUPS.flatMap((g) =>
  g.metrics.map((m) => m.name)
);

const BY_METRIC = new Map(HEARTBEAT_GROUPS.flatMap((g) => g.metrics.map((m) => [m.metric, m])));

export function heartbeatMetric(metric: string): HeartbeatMetric | undefined {
  return BY_METRIC.get(metric);
}
