# Heartbeat 指标分组小图

用户已确认：Heartbeat 页按指标分组，每个指标一张小趋势图，而不是「选一个指标看一张表」。

## 布局与筛选

- 顶部保留与其他报表一致的时间控件；实例多选复用 `MachinePicker`。
- 指标分组顺序展示：堆内存、年轻代、老年代、元空间、GC 信息、线程信息。
- 每个指标一张小图（`MiniChart`），图内每个实例一条线。
- 不合并不同 JVM 的值；同一实例在所有小图里颜色一致（按实例列表顺序取色）。
- 缺口保持断线，绝不画成 0；一期不做环比，页面不出现环比控件。

## 指标编目

新增 `api/heartbeat.ts` 作为唯一来源，定义分组、顺序、中文名、单位与是否为累计值。
页面不硬编码指标名，增删指标只改编目。

分组：堆内存、年轻代、老年代、元空间、GC 信息、线程信息。

图表标题用**点分指标名**（`jvm.memory.young.committed`），单位单独标注（`bytes` / `ms` / `count`）。
展示名与接口参数（`heap-used` 等）分开：改展示名不影响后端契约，接口改名才动编目。
这不是某个监控框架的原生指标名——业内没有唯一规范，这里沿用常见 JVM 术语按点分形式展开。

- **内存分区**：年轻代、老年代、元空间各自三个口径 —— `used`、`committed`、`max`，
  与 JVM `MemoryPoolMXBean` 同名。三者量纲相同但数量级差很远，放在同一张图会互相压扁，
  因此每个口径一张小图。
- `committed` 是 JVM 已申请、保证可用的容量，**不是对象实际占用，也不等于进程 RSS**。
- 元空间属于**非堆内存，不计入堆**；未设置 `-XX:MaxMetaspaceSize` 时 `metaspace.max` 未定义，
  显示为空缺而不是 0。
- 年轻代先展示合计，不细拆 Eden / Survivor。
- 整段没有有效值的指标（如元空间上限）展示原因说明，而不是画一张没有曲线的空图——
  否则会被当成页面故障。这与「当前范围内没有上报数据」是两种不同的情况。

指标共 21 个：`heap-used`、`heap-max`、
`young-{used,committed,max}`、`old-{used,committed,max}`、`metaspace-{used,committed,max}`、
`gc-count`、`gc-time`、`young-gc-count`、`young-gc-time`、
`old-gc-count`、`old-gc-time`、`full-gc-count`、`full-gc-time`、`threads`。

## 本轮范围（重要）

**仅有前端与 mock。** 其中 5 个（`heap-used`、`heap-max`、`gc-count`、`gc-time`、`threads`）
已有上报协议、Java 客户端与后端分析支撑；**内存分区与 young/old/full GC 只有前端与 mock**。
真实环境接入需要扩展上报协议、客户端采集与后端分析查询，属于后续工作，
在完成前不得声称真实采集已支持这些指标。

## mock

- `/reports/heartbeat/metrics` 返回全部指标，清单**直接引用前端编目**（`HEARTBEAT_METRICS`），
  不另抄一份，避免 mock 与页面请求的指标集合漂移。
- `/reports/heartbeat/series` 改为与后端一致的 `{series:[{instance,points}]}` 形状
  （原先返回扁平 `points`，与真实后端不一致，会掩盖前端的对接错误）。
- `10.0.0.9` 刻意缺少 old/full GC 数据，用于验证「无法区分时是空缺而不是 0」。
- `metaspace-max` 全部为空缺，用于验证「无上限时是空缺而不是 0」。
- 已用量是 gauge（围绕基准波动，**不随时间线性增长**），只有 GC 次数/耗时这类累计值才逐桶递增。

## 口径提醒

- Young/Old/Full GC 在 JMX 中按收集器事件区分，并非所有收集器都有独立 Full GC。
- GC 次数与耗时是 JVM 启动以来的累计值，重置为 0 表示实例重启；不能直接把累计值相加当作窗口内发生量。
- `used` / `committed` / `max` 三者不可混用：`committed` 是 JVM 已申请、保证可用的容量
  （不是实际占用，也不等于进程 RSS），`max` 是最大可用容量，元空间通常无上限。
- 元空间是非堆内存，与堆、年轻代、老年代不存在求和关系，**不能相加**。

以上口径都作为 `note` 写在 `front/src/api/heartbeat.ts` 编目里，随对应指标小图的 `?`
提示展示，页面上不再有整段口径说明（2026-10-02 调整）。

## 验证

前端单测、mock 动线检查与构建；浏览器功能测试由用户完成。
