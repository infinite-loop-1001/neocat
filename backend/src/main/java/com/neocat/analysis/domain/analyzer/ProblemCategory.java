package com.neocat.analysis.domain.analyzer;

/**
 * Problem 分类（PRD 03 §9）。
 *
 * <p>五类：异常、慢请求、慢 SQL、慢调用、慢缓存。
 * 来源：
 * <ul>
 *   <li>Transaction/Event 非成功状态 → {@link #EXCEPTION}；</li>
 *   <li>URL / SQL / CALL / CACHE 类型的 Transaction 超过对应平台阈值 → 对应慢类。</li>
 * </ul>
 *
 * <p>同一次调用可以同时属于异常和慢类。
 */
@org.springframework.modulith.NamedInterface("analysis")
public enum ProblemCategory {
    EXCEPTION("异常"),
    SLOW_URL("慢请求"),
    SLOW_SQL("慢 SQL"),
    SLOW_CALL("慢调用"),
    SLOW_CACHE("慢缓存");

    private final String display;

    ProblemCategory(String display) {
        this.display = display;
    }
    public String display() {
        return display;
    }
    /** 该分类是否为慢类（慢类支持耗时分位，异常不支持）。 */
    public boolean slow() {
        return this != EXCEPTION;
    }
    /**
     * 由 Transaction 节点的 category 推导慢类；非慢类类别返回 {@code null}。
     *
     * <p>类型映射：URL → SLOW_URL，SQL → SLOW_SQL，CALL → SLOW_CALL，CACHE → SLOW_CACHE。
     */
    public static ProblemCategory slowCategoryOf(String transactionCategory) {
        if (transactionCategory == null) {
            return null;
        }
        return switch (transactionCategory.toUpperCase(java.util.Locale.ROOT)) {
            case "URL" -> SLOW_URL;
            case "SQL" -> SLOW_SQL;
            case "CALL" -> SLOW_CALL;
            case "CACHE" -> SLOW_CACHE;
            default -> null;
        };
    }
}
