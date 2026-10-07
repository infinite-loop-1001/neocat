package com.neocat;

import com.neocat.common.config.*;
import com.neocat.common.config.impl.ApolloConfigGuard;
import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.core.convert.support.DefaultConversionService;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** 离线测试专用；生产不存在默认值工厂或配置快照。 */
public final class StaticConfigFixture {
    public static final Class<?>[] TYPES = ApolloConfigGuard.DYNAMIC_CONFIG_TYPES.toArray(Class[]::new);
    private StaticConfigFixture() { }

    public static Map<Field, Object> snapshot() {
        Map<Field, Object> result = new LinkedHashMap<>();
        try {
            for (Class<?> type : TYPES) for (Field field : type.getDeclaredFields()) result.put(field, field.get(null));
        } catch (IllegalAccessException e) { throw new AssertionError(e); }
        return result;
    }
    public static void restore(Map<Field, Object> values) {
        try { for (var entry : values.entrySet()) entry.getKey().set(null, entry.getValue()); }
        catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    public static void defaults() {
        IngestConfig.QUEUE_CAPACITY = 65536;
        IngestConfig.CONSUMER_THREADS = 4;
        IngestConfig.BATCH_SIZE = 200;
        IngestConfig.BATCH_TIMEOUT_MS = 200;
        IngestConfig.MAX_TREES_PER_BATCH = 200;
        IngestConfig.MAX_BATCH_BYTES = 1048576;
        IngestConfig.MAX_NODES_PER_TREE = 3000;
        IngestConfig.IDEMPOTENCY_WINDOW_MINUTES = 120;
        IngestConfig.AUTH_TOKEN = "test-only-token";
        IngestConfig.ACCEPT_LATE_HOURS = 2;
        AnalysisConfig.ANALYZER_TIMEOUT_MS = 5000;
        ReportConfig.EXACT_VALUES_MAX = 200;
        ReportConfig.DISTRIBUTION_BUCKETS = 16;
        ReportConfig.MINUTE_RETENTION_DAYS = 30;
        ReportConfig.HOUR_RETENTION_DAYS = 30;
        ReportConfig.LONG_TERM_RETENTION_MONTHS = 13;
        ReportConfig.MINUTE_FLUSH_DELAY_SECONDS = 5;
        ReportConfig.BUCKET_CACHE_SECONDS = 60;
        MetricConfig.TOP_N = 1000;
        TraceConfig.RETENTION_DAYS = 7;
        TraceConfig.SAMPLE_RATE = 1.0;
        TraceConfig.SAMPLE_ROWS = 30;
        AlertConfig.EVALUATE_DELAY_SECONDS = 5;
        AlertConfig.NOTIFY_TIMEOUT_MS = 3000;
        AlertConfig.DEDUP_PER_MINUTE = true;
        QueryConfig.MAX_BUCKETS = 2000;
        QueryConfig.MAX_INSTANCES_TOP_N = 20;
        HeartbeatConfig.TOP_N = 10;
    }
    public static void overrides(Map<String, String> values) {
        try {
            for (Class<?> type : TYPES) for (Field field : type.getDeclaredFields()) {
                String placeholder = field.getAnnotation(ApolloStaticValue.class).value();
                String key = placeholder.substring(2, placeholder.length() - 1);
                if (values.containsKey(key)) field.set(null,
                        DefaultConversionService.getSharedInstance().convert(values.get(key), field.getType()));
            }
        } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
}
