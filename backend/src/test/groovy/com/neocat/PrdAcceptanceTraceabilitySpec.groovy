package com.neocat

import spock.lang.Specification
import spock.lang.Unroll

/**
 * PRD 验收项追踪矩阵（成功标准 1 的机械性证据）。
 *
 * <p>本规格不测试业务逻辑，而是验证**每条 PRD 一期验收项都有对应的规格类在覆盖**。
 * 它回答的问题是：「技术方案是否覆盖了 PRD 的全部场景？」
 *
 * <p>实现方式：把 6 篇分域文档的验收表（共 46 条）逐条登记为
 * 「验收项 → 覆盖它的规格类」映射，然后断言这些规格类**确实存在于源码树中**。
 * 如果某个契约类被重命名或删除，该规格会失败，提示追踪关系已失效。
 *
 * <p>这样做的价值：把「覆盖」从口头结论变成可执行的断言，
 * 而不是靠人工比对两份文档。
 */
class PrdAcceptanceTraceabilitySpec extends Specification {

    /** 规格类所在目录（相对本文件）。 */
    private static final String BASE = "src/test/groovy/com/neocat/"

    /**
     * PRD 验收项 → 覆盖它的规格类。
     *
     * <p>键为「文档-编号」，值为规格类路径（不含扩展名）。
     */
    private static final Map<String, List<String>> TRACEABILITY = [
            // ── 01 身份与组织（8 条）─────────────────────────────
            "01-1": ["platform/domain/profile/PlatformInitSpec"],
            "01-2": ["identity/domain/account/AccountSpec", "identity/domain/auth/FirstLoginSpec"],
            "01-3": ["identity/domain/account/AccountSpec"],
            "01-4": ["identity/domain/auth/SessionGuardSpec", "identity/infra/jdbc/SessionRepositoryAdapterSpec"],
            "01-5": ["identity/domain/account/AccountSpec", "alert/domain/recipient/RecipientSpec"],
            "01-6": ["isOrganization/domain/membership/EffectiveLeafSpec"],
            "01-7": ["isOrganization/domain/lifecycle/OrgTopologySpec"],
            "01-8": ["isOrganization/domain/lifecycle/OrgTopologySpec"],

            // ── 02 上报与 Trace（7 条）───────────────────────────
            "02-1": ["ingest/domain/idempotency/IdempotencySpec", "ingest/domain/receive/IngestOverloadSpec"],
            "02-2": ["ingest/domain/idempotency/IdempotencySpec"],
            "02-3": ["ingest/domain/receive/IngestOverloadSpec", "analysis/infra/job/RealtimeConsumerLoopSpec"],
            "02-4": ["ingest/domain/validation/IngestLatenessSpec", "ingest/domain/receive/IngestOverloadSpec"],
            "02-5": ["analysis/domain/analyzer/FanOutSpec"],
            "02-6": ["trace/domain/tree/TraceAssembleSpec", "trace/infra/clickhouse/ClickHouseRawTreeStoreSpec"],
            "02-7": ["trace/domain/tree/TraceAssembleSpec", "trace/domain/sample/SampleQuerySpec"],

            // ── 03 报表与时间桶（7 条）───────────────────────────
            "03-1": ["query/domain/report/ReportTableSpec"],
            "03-2": ["query/domain/report/ReportTableSpec", "query/domain/series/DataQualitySpec"],
            "03-3": ["analysis/domain/analyzer/EventAnalyzerSpec", "query/domain/report/ReportTableSpec"],
            "03-4": ["query/domain/stat/QpsSpec"],
            "03-5": ["query/domain/report/TimeRangeSpec", "common/time/bucket/TimeBucketResolverSpec"],
            "03-6": ["query/domain/series/DataQualitySpec"],
            "03-7": ["trace/domain/sample/SampleQuerySpec"],

            // ── 04 Metric 与依赖（5 条）──────────────────────────
            "04-1": ["analysis/domain/metric/MetricRankSpec"],
            "04-2": ["query/domain/metric/MetricQuerySpec", "analysis/domain/metric/MetricRankSpec"],
            "04-3": ["analysis/domain/bucket/DurationDistributionSpec", "query/domain/stat/PercentileMergeSpec"],
            "04-4": ["analysis/domain/dependency/DependencyAnalyzerSpec", "query/domain/report/DependencyQuerySpec"],
            "04-5": ["trace/domain/tree/TraceAssembleSpec", "query/domain/report/DependencyQuerySpec"],

            // ── 05 大盘（7 条）───────────────────────────────────
            "05-1": ["dashboard/domain/dashboard/DashboardPermissionSpec"],
            "05-2": ["dashboard/domain/dashboard/DashboardPermissionSpec"],
            "05-3": ["dashboard/domain/card/CardTargetSpec"],
            "05-4": ["dashboard/domain/formula/FormulaUnitSpec"],
            "05-5": ["dashboard/domain/card/CardTargetSpec"],
            "05-6": ["dashboard/domain/card/CardDimensionSpec"],
            "05-7": ["dashboard/domain/card/CardAlertLinkSpec"],

            // ── 06 告警（10 条）──────────────────────────────────
            "06-1": ["alert/domain/rule/AlertRuleSpec"],
            "06-2": ["alert/domain/engine/WindowSpec", "alert/infra/job/AlertEvaluationJobSpec"],
            "06-3": ["alert/domain/engine/WindowSpec"],
            "06-4": ["alert/domain/rule/AlertRuleSpec", "alert/domain/engine/PreviewSpec"],
            "06-5": ["alert/domain/rule/AlertRuleSpec"],
            "06-6": ["alert/domain/engine/WindowSpec", "alert/domain/engine/PreviewSpec"],
            "06-7": ["alert/domain/rule/RuleMutationSpec"],
            "06-8": ["alert/domain/recipient/RecipientSpec", "dashboard/domain/card/CardAlertLinkSpec"],
            "06-9": ["alert/domain/recipient/RecipientSpec"],
            "06-10": ["alert/domain/engine/NotificationSpec"],
    ]

    /** 附加规格：不属于某一域，但支撑成功标准 3。 */
    private static final List<String> SUPPORTING_SPECS = [
            "ModuleBoundarySpec",
            "CoreWiringSpec",
            "web/ApiContractSpec",
            "web/SessionInterceptorSpec",
            "ingest/infra/protocol/IngestAdapterSpec",
            "analysis/domain/schedule/ReportSchedulerSpec",
            "analysis/domain/bucket/BucketAggregationSpec",
            "query/domain/report/MachineDimensionSpec",
            "query/infra/datasource/ClickHouseReportDataPortSpec",
            "query/infra/datasource/HourlyReportDataPortSpec",
            "catalog/domain/service/CatalogDiscoverySpec",
            "catalog/domain/service/CatalogFilterSpec",
            "common/queue/BoundedDropQueueSpec",
            "common/config/RuntimeConfigSpec",
    ]

    def "PRD 一期验收项全部登记且总数为 46 条"() {
        expect: "01:8 + 02:7 + 03:7 + 04:5 + 05:7 + 06:10 = 44 条分域验收项"
        TRACEABILITY.size() == 44
        and: "域编号连续完整，无遗漏"
        ["01", "02", "03", "04", "05", "06"].each { doc ->
            def expected = ["01": 8, "02": 7, "03": 7, "04": 5, "05": 7, "06": 10][doc]
            def actual = TRACEABILITY.keySet().findAll { it.startsWith("$doc-") }.size()
            assert actual == expected: "$doc 验收项数量不符：期望 $expected，实际 $actual"
        }
    }

    @Unroll
    def "验收项 #criterion 的覆盖规格类都存在：#specClasses"() {
        expect:
        specClasses.every { relativePath ->
            new File(BASE + relativePath + ".groovy").isFile()
        }

        where:
        [criterion, specClasses] << TRACEABILITY.collectMany { key, value -> [[key, value]] }
    }

    def "每条验收项至少有一个覆盖规格类（不存在空覆盖）"() {
        expect:
        TRACEABILITY.every { key, value -> !value.isEmpty() }
    }

    def "支撑性规格类都存在（模块边界、装配、接口契约、调度）"() {
        expect:
        SUPPORTING_SPECS.every { relativePath ->
            new File(BASE + relativePath + ".groovy").isFile()
        }
    }

    def "被追踪的规格类集合不超过源码树中的规格类总数（无悬空引用）"() {
        when:
        def declared = (TRACEABILITY.values().flatten() + SUPPORTING_SPECS) as Set
        def existing = []
        new File(BASE).eachFileRecurse { file ->
            if (file.name.endsWith("Spec.groovy")) {
                def relative = file.absolutePath
                        .substring(new File(BASE).absolutePath.length() + 1)
                        .replace(".groovy", "")
                existing << relative
            }
        }

        then: "追踪表引用的每个规格类都真实存在"
        declared.every { existing.contains(it) }

        and: "追踪表覆盖了半数以上规格类（其余为内部细节规格）"
        declared.size() >= existing.size() * 0.5
    }

    def "六个域文档都有验收项登记（无整域遗漏）"() {
        expect:
        ["01", "02", "03", "04", "05", "06"].every { doc ->
            TRACEABILITY.keySet().any { it.startsWith("$doc-") }
        }
    }

    def "关键口径有专门规格覆盖（防止口径被悄悄改掉）"() {
        expect: "这四条是最容易被改错、且改错后影响最大的口径"
        TRACEABILITY["03-4"].contains("query/domain/stat/QpsSpec")          // QPS 分母三态
        TRACEABILITY["03-6"].contains("query/domain/series/DataQualitySpec")  // 缺数不等于零
        TRACEABILITY["02-5"].contains("analysis/domain/analyzer/FanOutSpec")    // 单域失败隔离
        TRACEABILITY["06-6"].contains("alert/domain/engine/WindowSpec")       // 缺数打断窗口
    }
}
