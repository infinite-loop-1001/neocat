package com.neocat.ingest.domain.tree;

/**
 * JVM Heartbeat 载荷（PRD 03 §10）：堆内存、GC、线程数。
 * 一期仅覆盖 JVM，非 JVM 服务没有 Heartbeat 不影响其他报表。
 */
@org.springframework.modulith.NamedInterface("tree")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class HeartbeatValue {
    private final java.util.Map<String, Long> values;

    public HeartbeatValue(java.util.Map<String, Long> values) {
        this.values = values;
    }

    public HeartbeatValue(long heapUsedBytes, long heapMaxBytes, long gcCount, long gcTimeMs, long threadCount) {
        this(java.util.Map.of("heap-used", heapUsedBytes, "heap-max", heapMaxBytes,
                "gc-count", gcCount, "gc-time", gcTimeMs, "threads", threadCount));
    }

    // Retained source compatibility for legacy domain callers.
    public long heapUsedBytes() { return values.getOrDefault("heap-used", 0L); }
    public long heapMaxBytes() { return values.getOrDefault("heap-max", 0L); }
    public long gcCount() { return values.getOrDefault("gc-count", 0L); }
    public long gcTimeMs() { return values.getOrDefault("gc-time", 0L); }
    public long threadCount() { return values.getOrDefault("threads", 0L); }
}
