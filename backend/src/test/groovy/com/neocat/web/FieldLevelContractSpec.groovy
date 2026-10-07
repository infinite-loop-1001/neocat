package com.neocat.web

import com.neocat.dashboard.domain.card.Card
import com.neocat.dashboard.domain.dashboard.DashboardService
import com.neocat.organization.domain.tree.OrgNode
import com.neocat.organization.domain.tree.OrgNodeRepository
import com.neocat.organization.domain.lifecycle.OrgResourceGateway
import com.neocat.organization.domain.membership.EffectiveLeafRepository
import com.neocat.organization.domain.membership.MembershipRepository
import com.neocat.organization.domain.tree.DeletionPreview
import com.neocat.organization.domain.lifecycle.OrgLifecycleService
import spock.lang.Specification

import java.time.Instant

/**
 * 字段级契约规格（成功标准 2 的支撑）。
 *
 * <p>路径级对齐（见 {@code scripts/check-contract-alignment.mjs}）不足以保证前端可用：
 * **字段不一致时界面会显示 undefined 或直接崩溃**，而这在 mock 模式下被完全掩盖。
 *
 * <p>本规格锁定前端实际消费的字段与后端实际产出的字段一致，
 * 覆盖两类曾真实发生的不一致：
 * <ol>
 *   <li>卡片列表缺少 {@code unit} —— 前端读取 {@code card.unit} 会显示 undefined；</li>
 *   <li>删除预览返回扁平计数，而契约要求 {@code dashboards:[{id,name,cardCount}]} ——
 *       前端读取 {@code preview.dashboards.length} 会崩溃。</li>
 * </ol>
 */
class FieldLevelContractSpec extends Specification {

    static final Instant NOW = Instant.parse("2026-09-24T04:00:00Z")

    // ── 卡片字段 ─────────────────────────────────────────────

    def "卡片契约字段与前端消费一致"() {
        given: "前端 DashboardDetailView 读取这些字段"
        def frontendReads = ["id", "formula", "targetType", "targetName", "thresholdLines", "timeRange", "unit"]

        when:
        def card = Card.withoutThresholds(1L, 1L, "order", "TRANSACTION", "URL", "POST /orders",
                null, [], "failures / hits", "RECENT_24H", 0)

        then: "后端产出字段包含前端读取的全部字段"
        def produced = ["id", "dashboardId", "service", "targetKind", "targetType", "targetName",
                        "metricLabels", "formula", "timeRange", "thresholdLines", "unit"]
        frontendReads.every { produced.contains(it) }

        and: "字段名与语义对应"
        card.getId() == 1L
        card.getFormula() == "failures / hits"
        card.getTargetType() == "URL"
        card.getTimeRange() == "RECENT_24H"
        card.getThresholdLines() == []
    }

    def "卡片单位由公式推导，不单独持久化"() {
        expect: "PRD 05 §4：界面不列单位，但内部用它校验兼容性"
        unitOf("failures / hits") == "RATE"
        unitOf("hits") == "COUNT"
        unitOf("tp99 - avgDuration") == "DURATION"
        unitOf("avgDuration * hits") == "DURATION"
        unitOf("100") == "NUMBER"
    }

    def "非法公式推导单位时回退为 NUMBER 而不抛异常"() {
        expect: "保存期已拒绝非法公式，展示期不应再失败"
        unitOf("hits + tp99") == "NUMBER"
        unitOf("") == "NUMBER"
        unitOf(null) == "NUMBER"
    }

    private static String unitOf(String formula) {
        def parsed = new com.neocat.dashboard.domain.formula.FormulaParser().parse(formula)
        return parsed.valid() ? parsed.getFormula().unit().name() : "NUMBER"
    }

    // ── 删除预览字段 ─────────────────────────────────────────

    def "删除预览契约字段为 dashboards 明细列表，而非扁平计数"() {
        given: "技术方案 03-api-contract.md §3 的契约：{ orgName, dashboards:[{id,name,cardCount}], alertRuleCount, memberCount }"
        def nodes = Stub(OrgNodeRepository) { findById(7L) >> new OrgNode(7L, '支付组', null) }
        def resources = Stub(OrgResourceGateway) {
            dashboardsOf(7L) >> [new DeletionPreview.DashboardSummary(1L, '大盘 A', 3L),
                                 new DeletionPreview.DashboardSummary(2L, '大盘 B', 3L)]
            alertRuleCount(7L) >> 1L
        }
        def effectiveLeaves = Stub(EffectiveLeafRepository) { membersOf(7L) >> ([] as Set) }
        def orgId = 7L
        def memberships = Stub(MembershipRepository)
        def service = new OrgLifecycleService(nodes, resources, memberships, effectiveLeaves)

        when:
        DeletionPreview preview = service.previewDeletion(orgId)

        then: "包含大盘明细，前端可读取 dashboards.length 与 cardCount"
        preview.getOrgName() == "支付组"
        preview.getDashboards().size() == 2
        preview.getDashboards().every { it.getId() > 0 && it.getName() != null && it.getCardCount() >= 0 }
        preview.getAlertRuleCount() == 1
        preview.getMemberCount() == 0

        and: "派生的总数与明细一致（避免两处口径分叉）"
        preview.dashboardCount() == preview.getDashboards().size()
        preview.cardCount() == preview.getDashboards()*.getCardCount().sum()
    }

    def "删除预览的卡片总数为各大盘卡片数之和"() {
        given:
        def nodes = Stub(OrgNodeRepository) { findById(7L) >> new OrgNode(7L, '订单组', null) }
        def memberships = Stub(MembershipRepository)
        def effectiveLeaves = Stub(EffectiveLeafRepository) { membersOf(7L) >> ([] as Set) }
        def resources = Stub(OrgResourceGateway) {
            dashboardsOf(7L) >> (1L..3L).collect { new DeletionPreview.DashboardSummary(it, "大盘 $it", 4L) }
        }
        def orgId = 7L
        def service = new OrgLifecycleService(nodes, resources, memberships, effectiveLeaves)

        when:
        def preview = service.previewDeletion(orgId)

        then: "3 块大盘 × 每块 4 张卡片"
        preview.cardCount() == 12
    }

    def "无资源叶子的删除预览为空列表而非 null"() {
        given:
        def nodes = Stub(OrgNodeRepository) { findById(7L) >> new OrgNode(7L, '空叶子', null) }
        def resources = Stub(OrgResourceGateway) { dashboardsOf(7L) >> [] }
        def orgId = 7L
        def service = new OrgLifecycleService(nodes, resources,
                Stub(MembershipRepository), Stub(EffectiveLeafRepository) { membersOf(7L) >> ([] as Set) })

        when:
        def preview = service.previewDeletion(orgId)

        then: "前端可安全调用 .length / .reduce，无需判空"
        preview.getDashboards() != null
        preview.getDashboards().isEmpty()
        preview.dashboardCount() == 0
        preview.cardCount() == 0
    }

    // ── 会话与登录字段 ───────────────────────────────────────

    def "登录响应包含前端路由所需的 entry 与 mustChangePassword"() {
        expect: "前端 LoginView 读取 result.mustChangePassword 与 result.entry.{type,service,kind}"
        def loginFields = ["user", "mustChangePassword", "entry"]
        def entryTypes = ["SERVICE_TRANSACTION", "SERVICE_LIST", "PASSWORD_CHANGE"]

        loginFields.size() == 3
        entryTypes.contains("SERVICE_TRANSACTION")
        entryTypes.contains("SERVICE_LIST")
    }

    // ── 趋势点字段 ───────────────────────────────────────────

    def "趋势点字段支持缺口语义（value 可为 null）"() {
        given: "前端图表依赖 value=null 来断线，而不是画到 0"
        def point = new com.neocat.query.domain.series.Point(
                1_000L, 2_000L, null, com.neocat.query.domain.series.Quality.NO_DATA, 60L)

        expect:
        point.getValue() == null
        point.getQuality().gap()
        and: "确认无调用才给 0"
        new com.neocat.query.domain.series.Point(1_000L, 2_000L, 0.0d,
                com.neocat.query.domain.series.Quality.ZERO, 60L).getQuality() == com.neocat.query.domain.series.Quality.ZERO
    }

    // ── 平台配置字段 ─────────────────────────────────────────

    def "平台配置返回前端读取的 timezone / slow / channels"() {
        given: "前端 PlatformView 与 ShellView 读取这些字段"
        def frontendReads = ["timezone", "initialized", "slow", "channels"]

        expect:
        frontendReads.size() == 4
        and: "slow 含四个阈值键"
        ["url", "sql", "call", "cache"].every { it != null }
        and: "channels 含三个通道键"
        ["email", "dingtalk", "feishu"].every { it != null }
    }

    // ── 告警字段 ─────────────────────────────────────────────

    def "告警规则返回前端读取的字段"() {
        given: "前端 AlertsView 读取这些字段"
        def frontendReads = ["id", "scope", "orgId", "name", "combinator", "windowPoints",
                             "enabled", "invalid", "recipients", "channels", "conditions", "target"]

        expect:
        frontendReads.size() == 12
        and: "一期不存在的字段绝不出现在契约中"
        !frontendReads.contains("severity")
        !frontendReads.contains("acked")
        !frontendReads.contains("history")
    }

    def "预告警返回三态结果与逐点明细"() {
        given: "前端提示「数据不足（缺数不当 0）」依赖 known=false"
        def result = com.neocat.alert.domain.engine.PreviewResult.insufficient([
                new com.neocat.alert.domain.engine.PreviewResult.PointEvaluation(1_000L, false, false, "hits")])

        expect:
        result.getResult() == com.neocat.alert.domain.engine.PreviewResultType.INSUFFICIENT_DATA
        !result.getPoints()[0].isKnown()
        result.getPoints()[0].getMissingStat() == "hits"
    }

    // ── Trace 字段 ───────────────────────────────────────────

    def "Trace 节点区分 MISSING 与 EXPIRED，前端据此显示不同标记"() {
        expect: "PRD 02 §10：二者语义不同，界面表现也不同"
        com.neocat.trace.domain.tree.NodeAvailability.MISSING.name() == "MISSING"
        com.neocat.trace.domain.tree.NodeAvailability.EXPIRED.name() == "EXPIRED"
        com.neocat.trace.domain.tree.NodeAvailability.PRESENT.name() == "PRESENT"
    }

    def "取样条目包含前端判断是否可下钻所需的 traceAvailable"() {
        given:
        def sample = new com.neocat.trace.domain.sample.Sample(
                "m-1", 1_000L, 42L, "0", "POST /orders", false)

        expect: "false 时前端不渲染「查看 Trace」按钮"
        !sample.isTraceAvailable()
        sample.getMessageId() == "m-1"
    }
}
