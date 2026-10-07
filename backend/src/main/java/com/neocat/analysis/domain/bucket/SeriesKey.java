package com.neocat.analysis.domain.bucket;

/**
 * 序列身份（技术方案 01 §3「目标序列」）。
 *
 * <p>{@code 目标序列 = 服务 + 报表类型 + 指标对象(type/name) + 维度范围(instance)}
 *
 * @param service         服务名
 * @param kind            报表类型
 * @param type            分类，如 URL / SQL / business / 指标名
 * @param name            名称，如 POST /orders / order-created
 * @param instance        实例；{@code ALL} 表示全机器聚合行
 * @param problemCategory Problem 分类（EXCEPTION / SLOW_URL / …），非 Problem 为空串
 * @param metricLabels    Metric 规范化标签串；被并入 other 时为 {@link #OTHER_LABELS}
 */
@org.springframework.modulith.NamedInterface("analysis")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class SeriesKey {
    private final String service;

    private final SeriesKind kind;

    private final String type;

    private final String name;

    private final String instance;

    private final String problemCategory;

    private final String metricLabels;

    public SeriesKey(String service, SeriesKind kind, String type, String name, String instance, String problemCategory, String metricLabels) {
        this.service = service;
        this.kind = kind;
        this.type = type;
        this.name = name;
        this.instance = instance;
        this.problemCategory = problemCategory;
        this.metricLabels = metricLabels;
    }

    /** 全机器聚合行的实例标识。 */
    public static final String ALL = "all";

    /** Metric 中被并入 other 的序列标签标识。 */
    public static final String OTHER_LABELS = "__OTHER__";

    public static SeriesKey of(String service, SeriesKind kind, String type, String name) {
        return new SeriesKey(service, kind, type, name, ALL, "", "");
    }
    public static SeriesKey of(String service, SeriesKind kind, String type, String name, String instance) {
        return new SeriesKey(service, kind, type, name, instance, "", "");
    }
    public static SeriesKey problem(String service, String category, String name) {
        return new SeriesKey(service, SeriesKind.PROBLEM, category, name, ALL, category, "");
    }
    public static SeriesKey metric(String service, String metricName, String labels) {
        return new SeriesKey(service, SeriesKind.METRIC, metricName, "", ALL, "", labels);
    }
    public static SeriesKey dependency(String service, String peerService) {
        return new SeriesKey(service, SeriesKind.DEPENDENCY, peerService, "", ALL, "", "");
    }
}




