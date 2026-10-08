package com.neocat.analysis.domain.analyzer;
import org.springframework.modulith.NamedInterface;

/**
 * 固定的 20 项 JVM 指标（PRD 02 §9、PRD 03 §10）。
 *
 * <p>与前端编目一致的接口 key，展示名称与单位由前端维护。
 */
@NamedInterface("analysis")
public enum JvmMetric {
    HEAP_USED("heap-used"),
    HEAP_MAX("heap-max"),
    GC_COUNT("gc-count"),
    GC_TIME("gc-time"),
    THREADS("threads"),
    YOUNG_USED("young-used"), YOUNG_COMMITTED("young-committed"), YOUNG_MAX("young-max"),
    OLD_USED("old-used"), OLD_COMMITTED("old-committed"), OLD_MAX("old-max"),
    METASPACE_USED("metaspace-used"), METASPACE_COMMITTED("metaspace-committed"), METASPACE_MAX("metaspace-max"),
    YOUNG_GC_COUNT("young-gc-count"), YOUNG_GC_TIME("young-gc-time"),
    OLD_GC_COUNT("old-gc-count"), OLD_GC_TIME("old-gc-time"),
    FULL_GC_COUNT("full-gc-count"), FULL_GC_TIME("full-gc-time");

    private final String seriesName;

    JvmMetric(String seriesName) {
        this.seriesName = seriesName;
    }
    public String seriesName() {
        return seriesName;
    }
}
