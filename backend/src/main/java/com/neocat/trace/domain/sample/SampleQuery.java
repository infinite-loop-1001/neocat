package com.neocat.trace.domain.sample;

/**
 * 取样查询条件（PRD 03 §11）。
 *
 * <p>取样查询受当前服务、时间范围、实例、Type/Name/Problem 筛选约束。
 *
 * @param service      服务名
 * @param category     分类（URL / SQL / …）；Problem 查询时传分类名
 * @param name         Name（Transaction Name / Event Name / Problem 聚合键）
 * @param instance     实例筛选；null 表示不限
 * @param from         时间范围起点（含）
 * @param to           时间范围终点（不含）
 * @param problemCategory Problem 分类；非 Problem 查询为 null
 * @param limit        返回条数上限；默认 30
 */
@org.springframework.modulith.NamedInterface("trace")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class SampleQuery {
    private final String service;

    private final String category;

    private final String name;

    private final String instance;

    private final long from;

    private final long to;

    private final String problemCategory;

    private final int limit;

    public SampleQuery(String service, String category, String name, String instance, long from, long to, String problemCategory, int limit) {
        this.service = service;
        this.category = category;
        this.name = name;
        this.instance = instance;
        this.from = from;
        this.to = to;
        this.problemCategory = problemCategory;
        this.limit = limit;
    }

    /** 一期默认取样条数（PRD 00 §12：每行最近 30 条）。 */
    public static final int DEFAULT_LIMIT = 30;

    public static SampleQuery of(String service, String category, String name, long from, long to) {
        return new SampleQuery(service, category, name, null, from, to, null, DEFAULT_LIMIT);
    }
    public boolean matches(com.neocat.trace.domain.tree.TraceTree tree, com.neocat.trace.domain.tree.TraceNode node) {
        if (service != null && !service.equals(tree.getServiceName())) {
            return false;
        }
        if (instance != null && !instance.equals(tree.getInstanceId())) {
            return false;
        }
        if (node.getTimestamp() < from || node.getTimestamp() >= to) {
            return false;
        }
        if (problemCategory != null) {
            return problemCategory.equalsIgnoreCase(node.getCategory())
                    || problemCategory.equalsIgnoreCase(node.getKind());
        }
        if (category != null && !category.equalsIgnoreCase(node.getCategory())) {
            return false;
        }
        return name == null || name.equals(node.getName());
    }
}





