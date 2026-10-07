package com.neocat.analysis.domain.bucket

import spock.lang.Specification
import spock.lang.Unroll

/**
 * 耗时/数值分布与分位估算的规格（PRD 03 §3、§5，PRD 04 §9）。
 *
 * <p>这是全项目**最关键的口径实现**之一，因为它决定了所有分位值的正确性：
 * <ul>
 *   <li>分箱定义：第 i 段覆盖 [2^i, 2^(i+1)) ms，i=0..15；</li>
 *   <li>合并语义：**逐段相加**，从根上杜绝「平均分位」（PRD 03 §3）；</li>
 *   <li>双轨策略：低基数存精确值（分位零误差），高基数退化为分箱估算；</li>
 *   <li>无样本时分位为 {@code null}，绝不用 0 冒充（PRD 03 §5）。</li>
 * </ul>
 */
class DurationDistributionSpec extends Specification {

    // ── 分箱定义 ─────────────────────────────────────────────

    def "分箱共 16 段"() {
        expect:
        DurationDistribution.SEGMENTS == 16
        new DurationDistribution().segments().length == 16
    }

    @Unroll
    def "样本 #value ms 落在第 #segment 段（段 i 覆盖 [2^i, 2^(i+1))）"() {
        given:
        def distribution = new DurationDistribution()
        distribution.record(value)

        when:
        def segments = distribution.segments()

        then:
        segments[segment] == 1L
        segments.sum() == 1L

        where:
        value | segment
        0L    | 0        // 0 归入第一段
        1L    | 0        // [1, 2)
        2L    | 1        // [2, 4)
        7L    | 2        // [4, 8)
        100L  | 6        // [64, 128)
        1024L | 10       // [1024, 2048)
        65535L| 15       // 超过上界归入最后一段
    }

    def "超出上界的样本归入最后一段，不丢样本"() {
        given:
        def distribution = new DurationDistribution()
        distribution.record(999_999L)

        expect:
        distribution.segments()[15] == 1L
        distribution.count() == 1
    }

    // ── 计数与模式 ───────────────────────────────────────────

    def "样本总数等于 record 次数"() {
        given:
        def distribution = new DurationDistribution()

        when:
        (1..50).each { distribution.record(it * 10L) }

        then:
        distribution.count() == 50
        distribution.segments().sum() == 50
    }

    def "样本数在精确上限内时保持精确模式"() {
        given:
        def distribution = new DurationDistribution(200)

        when:
        (1..100).each { distribution.record(it) }

        then: "精确模式下分位完全准确"
        distribution.exact()
        distribution.exactValues().length == 100
    }

    def "样本数超过精确上限后退化为分箱模式"() {
        given:
        def distribution = new DurationDistribution(10)

        when:
        (1..11).each { distribution.record(it * 10L) }

        then: "超过上限即放弃精确值，避免用部分精确值冒充全局精确"
        !distribution.exact()
        distribution.exactValues().length == 0
        and: "但样本总数仍准确"
        distribution.count() == 11
    }

    def "上限为 0 时始终使用分箱模式"() {
        given:
        def distribution = new DurationDistribution(0)

        when:
        distribution.record(50L)

        then:
        !distribution.exact()
        distribution.count() == 1
    }

    // ── 分位：精确模式 ───────────────────────────────────────

    def "精确模式下分位取排序后的真实值"() {
        given:
        def distribution = new DurationDistribution()
        (1..100).each { distribution.record(it * 10L) }   // 10, 20, ..., 1000

        expect: "p50 = 第 50 个 = 500；p90 = 900；p99 = 990"
        distribution.percentile(0.50d) == 500.0d
        distribution.percentile(0.90d) == 900.0d
        distribution.percentile(0.99d) == 990.0d
    }

    def "单值样本的各分位都等于该值"() {
        given:
        def distribution = new DurationDistribution()
        distribution.record(42L)

        expect: "PRD 04 §4：同一时间桶只有一个值时各分位与该值相同"
        distribution.percentile(0.50d) == 42.0d
        distribution.percentile(0.99d) == 42.0d
        distribution.percentile(0.9999d) == 42.0d
    }

    // ── 分位：分箱模式 ───────────────────────────────────────

    def "分箱模式下分位定位到覆盖率超过 p 的段内并插值"() {
        given:
        def distribution = new DurationDistribution(0)
        (1..100).each { distribution.record(1000L) }     // 全部落在 [512, 1024)

        when:
        def p50 = distribution.percentile(0.50d)

        then: "结果落在该段范围内，而不是别的段"
        p50 >= 512.0d
        p50 <= 1024.0d
    }

    def "分位随样本分布单调不减"() {
        given: "一半 10ms、一半 1000ms"
        def distribution = new DurationDistribution(0)
        (1..50).each { distribution.record(10L) }
        (1..50).each { distribution.record(1000L) }

        when:
        def p50 = distribution.percentile(0.50d)
        def p99 = distribution.percentile(0.99d)

        then: "p50 已越过 10ms 段，p99 更高"
        p50 > 10.0d
        p99 >= p50
    }

    @Unroll
    def "分位参数被裁剪到 [0,1]：p=#p"() {
        given:
        def distribution = new DurationDistribution()
        (1..10).each { distribution.record(it * 10L) }

        expect:
        distribution.percentile(p) != null

        where:
        p << [-1.0d, 0.0d, 1.0d, 2.0d]
    }

    // ── 无样本 ───────────────────────────────────────────────

    def "空分布的分位为 null（缺数不显示为 0）"() {
        expect: "PRD 03 §5：无调用时分位显示无值"
        new DurationDistribution().percentile(0.99d) == null
        new DurationDistribution().count() == 0
    }

    def "空分布合并后仍为空"() {
        given:
        def merged = new DurationDistribution().merge(new DurationDistribution())

        expect:
        merged.count() == 0
        merged.percentile(0.50d) == null
    }

    // ── 合并（最关键的不变式）────────────────────────────────

    def "合并时逐段相加，而不是平均分位"() {
        given: "A 有 10 次 10ms、B 有 90 次 1000ms"
        def a = new DurationDistribution()
        (1..10).each { a.record(10L) }
        def b = new DurationDistribution()
        (1..90).each { b.record(1000L) }

        when:
        def merged = a.merge(b)

        then: "样本总数正确相加"
        merged.count() == 100
        merged.segments().sum() == 100

        and: "p50 落在 1000ms 段"
        merged.percentile(0.50d) >= 512.0d

        and: "若错误地平均两个分布的分位会得到约 505，正确结果必须更大"
        merged.percentile(0.50d) > 505.0d
    }

    def "合并是可交换的：结果与合并顺序无关"() {
        given:
        def a = new DurationDistribution()
        (1..10).each { a.record(10L) }
        def b = new DurationDistribution()
        (1..30).each { b.record(500L) }
        def c = new DurationDistribution()
        (1..60).each { c.record(2000L) }

        expect:
        a.merge(b).merge(c).percentile(0.99d) == c.merge(b).merge(a).percentile(0.99d)
    }

    def "两侧都精确时合并结果仍精确（分位零误差）"() {
        given:
        def a = new DurationDistribution()
        (1..10).each { a.record(it * 10L) }
        def b = new DurationDistribution()
        (11..20).each { b.record(it * 10L) }

        when:
        def merged = a.merge(b)

        then:
        merged.exact()
        merged.count() == 20
        merged.percentile(0.50d) == 100.0d
    }

    def "任一侧非精确时合并结果退化为分箱模式"() {
        given: "a 精确、b 已退化"
        def a = new DurationDistribution()
        (1..5).each { a.record(it * 10L) }
        def b = new DurationDistribution(0)
        (1..5).each { b.record(it * 10L) }

        when:
        def merged = a.merge(b)

        then: "绝不用部分精确值冒充全局精确"
        !merged.exact()
        merged.count() == 10
    }

    def "合并后精确值数量为两侧之和"() {
        given:
        def a = new DurationDistribution()
        (1..30).each { a.record(it * 10L) }
        def b = new DurationDistribution()
        (1..40).each { b.record(it * 10L) }

        when:
        def merged = a.merge(b)

        then:
        merged.exactValues().length == 70
    }

    // ── 防御性 ───────────────────────────────────────────────

    def "负耗时按 0 处理，不污染分箱"() {
        given:
        def distribution = new DurationDistribution()

        when:
        distribution.record(-5L)

        then: "归入第一段，不计入负值"
        distribution.count() == 1
        distribution.segments()[0] == 1L
        distribution.percentile(0.50d) == 0.0d
    }

    def "segments 返回副本，外部修改不影响内部状态"() {
        given:
        def distribution = new DurationDistribution()
        distribution.record(100L)

        when:
        distribution.segments()[6] = 999L

        then:
        distribution.segments()[6] == 1L
    }

    def "exactValues 返回副本，外部修改不影响内部状态"() {
        given:
        def distribution = new DurationDistribution()
        (1..5).each { distribution.record(it * 10L) }

        when:
        def values = distribution.exactValues()
        values[0] = 99999L

        then:
        distribution.percentile(0.10d) == 10.0d
    }

    // ── 复制（跨桶聚合不污染源对象）──────────────────────────

    def "copy 产生独立副本：修改副本不影响原分布"() {
        given:
        def original = new DurationDistribution()
        (1..10).each { original.record(it * 10L) }

        when:
        def copy = original.copy()
        copy.record(99999L)

        then:
        original.count() == 10
        copy.count() == 11
        original.segments() != copy.segments()
    }

    def "copy 保留精确模式与精确值"() {
        given:
        def original = new DurationDistribution()
        (1..10).each { original.record(it * 10L) }

        when:
        def copy = original.copy()

        then:
        copy.exact()
        copy.exactValues().length == 10
        copy.percentile(0.50d) == original.percentile(0.50d)
    }

    // ── 从持久化重建 ─────────────────────────────────────────

    def "fromSegments 从持久化数组重建为分箱模式"() {
        given: "10 个样本在段 3、90 个在段 9"
        def persisted = new long[16]
        persisted[3] = 10L
        persisted[9] = 90L

        when:
        def rebuilt = DurationDistribution.fromSegments(persisted)

        then: "重建后处于分箱模式，因为原始值未随桶保留"
        rebuilt.count() == 100
        !rebuilt.exact()
        rebuilt.segments().sum() == 100
        and: "p50 落在段 9，而不是「10 与 1000 的平均」"
        rebuilt.percentile(0.50d) >= 512.0d
        rebuilt.percentile(0.50d) > 505.0d
    }

    def "fromSegments 对长度异常输入按段数截断，不抛异常"() {
        when: "只有 10 段（历史格式差异）"
        def rebuilt = DurationDistribution.fromSegments(new long[10])

        then:
        noExceptionThrown()
        rebuilt.segments().length == 16
        rebuilt.count() == 0
    }

    def "fromSegments 接收 null 时返回空分布"() {
        when:
        def rebuilt = DurationDistribution.fromSegments(null)

        then:
        noExceptionThrown()
        rebuilt.count() == 0
        rebuilt.percentile(0.99d) == null
    }

    def "重建后的分布可继续参与合并（形成完整链路）"() {
        given:
        def persistedA = new long[16]
        persistedA[3] = 10L
        def persistedB = new long[16]
        persistedB[9] = 90L

        when: "模拟「从 ClickHouse 读回多个桶后合并重算分位」"
        def merged = DurationDistribution.fromSegments(persistedA)
                .merge(DurationDistribution.fromSegments(persistedB))

        then:
        merged.count() == 100
        merged.percentile(0.50d) > 505.0d
    }

    // ── 默认构造 ─────────────────────────────────────────────

    def "默认精确上限与设计文档一致（200）"() {
        expect:
        DurationDistribution.DEFAULT_EXACT_LIMIT == 200
    }

    def "默认构造在样本数不超过 200 时保持精确"() {
        given:
        def distribution = new DurationDistribution()

        when:
        (1..200).each { distribution.record(it * 5L) }

        then:
        distribution.exact()

        when: "第 201 个样本"
        distribution.record(9999L)

        then:
        !distribution.exact()
    }
}
