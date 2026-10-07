package com.neocat.query.infra.port;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.common.time.bucket.Granularity;
import com.neocat.query.domain.series.Series;
import com.neocat.query.domain.stat.Stat;

import java.time.Instant;
import java.util.List;

/**
 * 报表数据读取口（技术方案 01-architecture.md §6、06）。
 *
 * <p>查询层只依赖该抽象，因此同一套统计、质量标记、分位合并逻辑
 * 既能跑在「当前小时内存报表」上，也能跑在 ClickHouse 桶表上，无需分支。
 *
 * <p>实现约定：
 * <ul>
 *   <li><b>返回行的桶起点必须按调用方给定的 {@code granularity} 对齐</b>。
 *       调用方（{@code ReportController}）按桶起点与自己的桶序列逐一对齐，
 *       粒度不一致会让大部分行取不到，表现为「趋势图缺一大片」；</li>
 *   <li>跨多个源桶的行必须**合并分子与分布**后再返回，不能只取其中一个桶
 *       （PRD 03 §3 禁止平均子桶分位）；</li>
 *   <li>返回的每行都带上 {@code coveredSeconds}（QPS 分母的实际覆盖秒数）；</li>
 *   <li>无数据的桶**不返回行**（缺口由查询层用 {@code QualityResolver} 判定，
 *       绝不用 count=0 的行冒充「缺数据」）。</li>
 * </ul>
 */
public interface ReportDataPort {

    /** Metric counts need original source timestamps to interpret hour-specific other ownership. */
    default List<AggregatedRow> metricSourceRows(String service, String metric, Instant from, Instant to,
                                                Granularity granularity) {
        return rows("METRIC", service, metric, null, from, to, Granularity.MINUTE_1, List.of());
    }
    /**
     * 读取某序列在给定时间范围内的行。
     *
     * @param kind        报表类型
     * @param service     服务
     * @param type        分类（URL / SQL / business / 指标名 …）
     * @param name        名称；Metric 场景为标签串
     * @param from        起点（含）
     * @param to          终点（不含）
     * @param granularity 期望的桶长；返回行的 {@code bucketStart} 必须按它对齐
     * @param instances   实例筛选；空表示全机器聚合行
     */
    List<AggregatedRow> rows(String kind, String service, String type, String name,
                             Instant from, Instant to, Granularity granularity,
                             List<String> instances);

    /**
     * 该服务在某时间范围内有数据的实例列表（用于机器维度与目录过滤）。
     */
    List<String> instancesWithData(String kind, String service, Instant from, Instant to);

    /**
     * 某分类下出现过的名称列表（Type → Name 钻取用）。
     */
    List<String> namesOf(String kind, String service, String type, Instant from, Instant to);

    /**
     * 某报表类型下出现过的分类列表（Type 层用）。
     */
    List<String> typesOf(String kind, String service, Instant from, Instant to);

    /**
     * 某桶是否存在队列满丢弃质量事件（用于把该桶标为 DROPPED 缺口）。
     */
    boolean droppedAt(String kind, String service, String type, String name, Instant bucketStart);

    default boolean droppedBetween(String kind, String service, String type, String name, Instant from, Instant to) {
        for (Instant minute = from; minute.isBefore(to); minute = minute.plusSeconds(60)) {
            if (droppedAt(kind, service, type, name, minute)) return true;
        }
        return false;
    }
    /**
     * 某 Metric 具体标签串在某小时是否被并入 other（用于 MERGED_OTHER 缺口）。
     */
    boolean mergedIntoOther(String service, String metricName, String labels, Instant hourStart);
}
