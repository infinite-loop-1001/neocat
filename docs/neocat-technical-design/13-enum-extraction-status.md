# 嵌套枚举外提状态

日期：2026-10-07。

## 目标与范围

把原先嵌套在领域/接口类型内的 public 枚举外提为顶层类型，保持接口边界（HTTP DTO、查询参数、持久化行）继续以 `String` 传输，不直接在接口上暴露 Java 枚举。

外提的 12 个枚举：

| 原位置 | 顶层类型 | 包 |
|---|---|---|
| `AlertTarget.Kind` | `AlertTargetKind` | `alert.domain.rule` |
| `PreviewResult.Result` | `PreviewResultType` | `alert.domain.engine` |
| `AlertableTarget.Kind` | `AlertableTargetKind` | `dashboard.domain.card` |
| `CardPoint.Outcome` | `CardPointOutcome` | `dashboard.domain.card` |
| `ThresholdLine.Direction` | `ThresholdDirection` | `dashboard.domain.card` |
| `Formula.Binary.Op` | `FormulaOperator` | `dashboard.domain.formula` |
| `Formula.Aggregate.Agg` | `FormulaAggregate` | `dashboard.domain.formula` |
| `HeartbeatAnalyzer.JvmMetric` | `JvmMetric` | `analysis.domain.analyzer` |
| `QualityEventSink.QualityType` | `QualityType` | `ingest.domain.receive` |
| `DependencyQueryService.DependencyDirectionQuery` | `DependencyDirectionQuery` | `query.domain.report` |
| `Stat.Unit` | `StatUnit` | `query.domain.stat` |
| `RangeSpec.Quick` | `RangeQuick` | `common.time.range` |

## 设计决策

- **类型独立**：`AlertTargetKind` 与 `AlertableTargetKind` 是分属 alert / dashboard 两个边界的独立类型，不合并。`DependencyDirectionQuery` 与 `analysis` 域的既有 `DependencyDirection` 同样保持独立：前者是分析期写序列用的方向，后者是查询期读序列的方向，取值相同但边界不同，不互相引用。
- **接口仍用 `String`**：HTTP DTO 不含 Java 枚举字段；`AlertConvert`、`DashboardConvert` 在边界处显式 `name()` / `valueOf`。持久化沿用既有 enum-name 字符串格式（`AlertRuleRepositoryAdapter`、`DashboardRepositoryAdapter`、`JdbcQualityEventSink`），无数据迁移。
- **具名接口随包**：顶层枚举落在原所属领域包，因此自动成为原 `@NamedInterface` 的导出成员，包级 `package-info` 未改。
- **符号保留**：枚举常量、`seriesName()` / `granularity()` / `unit()` / `scale` 等行为方法语义不变，只移动类型声明与限定名。

## 改动面

- 新增 12 个顶层枚举文件；宿主类去掉嵌套枚举、字段与构造器形参改用新类型。
- 生产引用点：`PreviewService`、`AlertRuleRepositoryAdapter`、`CardChangeListener`、`CardService`、`CardEvaluator`、`CardDimensionView`、`DashboardRepositoryAdapter`、`FormulaParser`、`RangeParams`、`DefaultTimeBucketResolver`、`TimeBucketResolver`（Javadoc）、`ReportQueryService`、`IngestService`、`JdbcQualityEventSink`。
- 测试：18 个 Groovy 规格的限定名与静态导入同步；5 个规格补 `import`。
- 基线：`backend/src/test/resources/named-interface-types.properties` 中 12 组具名接口导出集合改为顶层类型名（`AlertTarget$Kind`→`AlertTargetKind` 等）。
- 前后端注释：`front/src/mock/dataset.ts` 的 `RangeSpec.Quick` 注释改为 `RangeQuick`。

## 验证（离线）

在仓库根执行：

```bash
mvn -o -f backend/pom.xml clean test        # backend 1328 项，0 失败/错误/跳过
mvn -o -f client-java/pom.xml test          # client-java 30 项
node scripts/check-coding-standards.mjs     # 446 个手写 Java 文件 + 两个 POM
node --test scripts/check-coding-standards.test.mjs
node scripts/check-contract-alignment.mjs   # 64 后端端点 / 39 前端 mock 端点
node scripts/check-error-codes.mjs          # 44 个错误码一致
mvn -o -f backend/pom.xml -DskipTests package
mvn -o -f client-java/pom.xml -DskipTests package
node scripts/check-protocol-drift.mjs       # 19 个生成类逐字节一致
cd front && npm test                        # 103 项
```

`ModuleBoundarySpec` 13 项通过，具名接口导出集合与基线一致。

## 未验证

真实 Apollo / MySQL / ClickHouse、生产 Spring 启动与外部通知投递仍**未在真实端口上跑过**；上述结果仅离线证据。目录不是 Git 仓库，改动未提交。
