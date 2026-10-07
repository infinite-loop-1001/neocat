package com.neocat.client;

import com.neocat.protocol.ingest.v1.Heartbeat;
import java.lang.management.*;
import java.util.*;
import java.util.Objects;

/** Conservative MXBean sampling. Unknown pools/collectors remain absent, not guessed zeroes. */
public final class JvmHeartbeatSampler {
    private JvmHeartbeatSampler() { }

    public static Heartbeat payload(Map<String, String> values) {
        var builder = Heartbeat.newBuilder().setPresenceAware(true);
        for (var field : Heartbeat.getDescriptor().getFields()) {
            if (field.getNumber() > 20) continue;
            String key = alias(field.getName());
            String raw = values.get(key);
            if (Objects.isNull(raw)) continue;
            try {
                long value = Long.parseLong(raw);
                if (value >= 0) builder.setField(field, value);
            } catch (NumberFormatException ignored) { }
        }
        return builder.build();
    }

    private static String alias(String field) {
        if (Objects.equals(field, "thread_count")) return "threads";
        String[] words = field.replace("_bytes", "").replace("_ms", "").split("_");
        StringBuilder key = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) key.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        return key.toString();
    }

    public static Map<String, String> sample() {
        return sample(ManagementFactory.getMemoryMXBean(), ManagementFactory.getThreadMXBean(),
                ManagementFactory.getMemoryPoolMXBeans(), ManagementFactory.getGarbageCollectorMXBeans());
    }

    public static Map<String, String> sample(MemoryMXBean memory, ThreadMXBean threads,
                                            List<MemoryPoolMXBean> pools, List<GarbageCollectorMXBean> collectors) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            MemoryUsage heap = memory.getHeapMemoryUsage();
            put(result, "heapUsed", heap.getUsed()); put(result, "heapMax", heap.getMax());
        } catch (RuntimeException ignored) { }
        try { put(result, "threads", threads.getThreadCount()); } catch (RuntimeException ignored) { }
        Map<String, List<MemoryUsage>> partitions = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : pools) {
            try {
                String partition = partition(pool.getName());
                if (Objects.isNull(partition) || !pool.isValid()) continue;
                MemoryUsage usage = pool.getUsage();
                if (Objects.nonNull(usage)) partitions.computeIfAbsent(partition, k -> new ArrayList<>()).add(usage);
            } catch (RuntimeException ignored) { }
        }
        partitions.forEach((key, usages) -> {
            put(result, key + "Used", usages.stream().mapToLong(MemoryUsage::getUsed).sum());
            put(result, key + "Committed", usages.stream().mapToLong(MemoryUsage::getCommitted).sum());
            if (usages.stream().allMatch(u -> u.getMax() >= 0)) put(result, key + "Max", usages.stream().mapToLong(MemoryUsage::getMax).sum());
        });
        long totalCount = 0, totalTime = 0;
        boolean validCount = !collectors.isEmpty(), validTime = !collectors.isEmpty();
        for (GarbageCollectorMXBean collector : collectors) {
            try {
                long count = collector.getCollectionCount(), time = collector.getCollectionTime();
                validCount &= count >= 0; validTime &= time >= 0;
                if (count >= 0) totalCount += count;
                if (time >= 0) totalTime += time;
                String kind = collectorKind(collector.getName());
                if (Objects.nonNull(kind)) {
                    if (count >= 0) result.merge(kind + "GcCount", Long.toString(count), JvmHeartbeatSampler::sum);
                    if (time >= 0) result.merge(kind + "GcTime", Long.toString(time), JvmHeartbeatSampler::sum);
                }
            } catch (RuntimeException ignored) { validCount = false; validTime = false; }
        }
        if (validCount) put(result, "gcCount", totalCount);
        if (validTime) put(result, "gcTime", totalTime);
        // Standard MXBeans do not distinguish full events from old collector events.
        return Map.copyOf(result);
    }

    private static String sum(String a, String b) { return Long.toString(Long.parseLong(a) + Long.parseLong(b)); }
    private static void put(Map<String, String> result, String key, long value) {
        if (value >= 0) result.put(key, Long.toString(value));
    }
    public static String partition(String name) {
        return switch (name) {
            case "PS Eden Space", "PS Survivor Space", "Par Eden Space", "Par Survivor Space", "G1 Eden Space", "G1 Survivor Space", "Eden Space", "Survivor Space" -> "young";
            case "PS Old Gen", "CMS Old Gen", "G1 Old Gen", "Tenured Gen" -> "old";
            case "Metaspace" -> "metaspace";
            default -> null;
        };
    }
    public static String collectorKind(String name) {
        return switch (name) {
            case "PS Scavenge", "ParNew", "G1 Young Generation", "Copy" -> "young";
            case "PS MarkSweep", "ConcurrentMarkSweep", "G1 Old Generation", "MarkSweepCompact" -> "old";
            default -> null;
        };
    }
}
