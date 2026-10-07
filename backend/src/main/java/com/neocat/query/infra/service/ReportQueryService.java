package com.neocat.query.infra.service;

import com.neocat.analysis.domain.analyzer.JvmMetric;
import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.AggregationLevel;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.common.time.bucket.Bucket;
import com.neocat.common.time.bucket.Granularity;
import com.neocat.common.time.range.RangeSpec;
import com.neocat.common.time.bucket.TimeBucketResolver;
import com.neocat.query.infra.port.ReportDataPort;
import com.neocat.query.infra.port.SamplePort;
import com.neocat.query.domain.series.MomAligner;
import com.neocat.query.domain.series.MomKind;
import com.neocat.query.domain.series.Point;
import com.neocat.query.domain.series.Quality;
import com.neocat.query.domain.series.QualityInput;
import com.neocat.query.domain.series.QualityResolver;
import com.neocat.query.domain.report.RangeResolver;
import com.neocat.query.domain.report.ReportRow;
import com.neocat.query.domain.report.ReportTableService;
import com.neocat.query.domain.stat.Stat;
import com.neocat.query.domain.stat.StatCalculator;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 报表查询应用服务（技术方案 03-api-contract.md §4）。
 *
 * <p>负责查询编排与既有读模型，HTTP 出口由 Controller 与 Convert 隔离：
 * <ul>
 *   <li>时间范围 → 桶序列（{@link RangeResolver}）；</li>
 *   <li>行聚合 → 统计项（{@link StatCalculator}，含 QPS 三态分母）；</li>
 *   <li>缺口与质量标记（{@link QualityResolver}）；</li>
 *   <li>环比对齐（{@link MomAligner}）。</li>
 * </ul>
 *
 * <p>关键约定：**缺口点位的 {@code value} 为 {@code null}**，绝不写 0；
 * 确认无调用时次数类为 0、耗时与比例类为 {@code null}。
 */
@org.springframework.stereotype.Service
@org.springframework.context.annotation.DependsOn("traceConfig")
public class ReportQueryService {

    private final ReportDataPort data;

    private final TimeBucketResolver buckets;

    private final RangeResolver ranges;

    private final ReportTableService tables;

    private final StatCalculator calculator;

    private final QualityResolver quality;

    private final MomAligner momAligner;

    private final SamplePort samplePort;

    private final Supplier<ZoneId> zone;

    private final java.time.Clock clock;

    public ReportQueryService(ReportDataPort data, TimeBucketResolver buckets, ReportTableService tables,
                            StatCalculator calculator, QualityResolver quality, MomAligner momAligner,
                            SamplePort samplePort, Supplier<ZoneId> zone) {
        this(data, buckets, tables, calculator, quality, momAligner, samplePort, zone, java.time.Clock.systemUTC());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ReportQueryService(ReportDataPort data, TimeBucketResolver buckets, ReportTableService tables,
                            StatCalculator calculator, QualityResolver quality, MomAligner momAligner,
                            SamplePort samplePort, Supplier<ZoneId> zone, java.time.Clock clock) {
        this.data = data;
        this.buckets = buckets;
        this.ranges = new RangeResolver(buckets);
        this.tables = tables;
        this.calculator = calculator;
        this.quality = quality;
        this.momAligner = momAligner;
        this.samplePort = samplePort;
        this.zone = zone;
        this.clock = clock;
    }

    // ── Transaction / Event：Type 与 Name 层 ─────────────────

    public List<Map<String, Object>> transactionTypes(String service, String range) {
        return typeTable("TRANSACTION", service, range);
    }
    public List<Map<String, Object>> transactionNames(String service, String type, String range) {
        return nameTable("TRANSACTION", service, type, range);
    }
    public List<Map<String, Object>> eventTypes(String service, String range) {
        return typeTable("EVENT", service, range);
    }
    public List<Map<String, Object>> eventNames(String service, String type, String range) {
        return nameTable("EVENT", service, type, range);
    }

    // ── Problem ──────────────────────────────────────────────

    /**
     * Problem 五类（PRD 03 §9）。
     *
     * <p>异常类不支持分位，慢类支持；前端据此隐藏分位列。
     */
    public List<Map<String, Object>> problemCategories(String service, String range) {
        var resolved = ranges.resolve(parseRange(range), zone.get());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String category : data.typesOf("PROBLEM", service, resolved.getFrom(), resolved.getTo())) {
            var rows = fetchRows("PROBLEM", service, category, null, resolved);
            long total = rows.stream().mapToLong(AggregatedRow::count).sum();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", category);
            item.put("total", total);
            item.put("supportsPercentile", !"EXCEPTION".equalsIgnoreCase(category));
            result.add(item);
        }
        return result;
    }
    public List<Map<String, Object>> problemNames(String service, String category, String range) {
        var resolved = ranges.resolve(parseRange(range), zone.get());
        boolean percentileVisible = !"EXCEPTION".equalsIgnoreCase(category);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : data.namesOf("PROBLEM", service, category, resolved.getFrom(), resolved.getTo())) {
            var rows = fetchRows("PROBLEM", service, category, name, resolved);
            long total = rows.stream().mapToLong(AggregatedRow::count).sum();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("total", total);
            // 异常类不支持分位：显式返回 null，前端不渲染该列
            item.put("tp99", percentileVisible ? calculator.compute(rows, Stat.TP99, 3600) : null);
            result.add(item);
        }
        return result;
    }

    // ── 趋势 ─────────────────────────────────────────────────

    /**
     * 趋势序列（PRD 03 §7.2、§8）。
     *
     * <p>默认统计项为 Hits（桶内总次数），不是 count/min 也不是 QPS。
     */
    public Map<String, Object> series(String service, String kind, String type, String name,
                                     String stat, String range, Integer bucket, String mom, String instances) {
        Stat target = Stat.parse(stat);
        // range 决定窗口，bucket 只覆盖粒度（技术方案 03 §4.2：bucket 可省略，省略时按 range 的默认粒度）。
        var base = ranges.resolve(parseRange(range), zone.get());
        if (base.getBuckets().isEmpty()) {
            return emptySeries(service, kind, type, name, target, base);
        }
        Granularity granularity = Granularity.fromSeconds(base.bucketSeconds());
        if (bucket != null) {
            Granularity requested = Granularity.fromSeconds(bucket);
            // bucket 只接受已定义的粒度档位；非法值忽略，回落到 range 的默认粒度
            if (requested != null) {
                granularity = requested;
            }
        }
        var resolved = ranges.resolve(new RangeSpec.Explicit(base.getFrom(), base.getTo(), granularity), zone.get());

        List<String> instanceList = instances == null || instances.isBlank()
                ? List.of()
                : List.of(instances.split(","));

        List<AggregatedRow> rows = data.rows(kind, service, type, name,
                resolved.getFrom(), resolved.getTo(), granularity, instanceList);

        Map<Long, Double> byBucket = new LinkedHashMap<>();
        for (AggregatedRow row : rows) {
            long start = row.bucketStart().toEpochMilli();
            byBucket.merge(start, safeValue(row, target), Double::sum);
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (Bucket b : resolved.getBuckets()) {
            long start = b.getStart().toEpochMilli();
            Double value = byBucket.get(start);
            Quality q = quality.resolve(new QualityInput(
                    value != null, value == null ? 0 : 1,
                    data.droppedAt(kind, service, type, name, b.getStart()),
                    "METRIC".equalsIgnoreCase(kind)
                            && data.mergedIntoOther(service, type, name, b.getStart()),
                    b.isPartial(), isCurrentBucket(b), b.getCoveredSeconds()));
            // 缺口：value 为 null；ZERO 时按统计项决定是否呈现 0
            Double rendered = value == null ? null : value;
            if (q == Quality.ZERO && !quality.hasValue(q, target)) {
                rendered = null;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucketStart", start);
            point.put("bucketEnd", b.getEnd().toEpochMilli());
            point.put("value", rendered);
            point.put("quality", q.name());
            point.put("coveredSeconds", b.getCoveredSeconds());
            point.put("realtime", isCurrentBucket(b));
            point.put("partial", b.isPartial());
            points.add(point);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("kind", kind);
        result.put("type", type);
        result.put("name", name);
        result.put("stat", target.name());
        result.put("unit", target.unit().name());
        result.put("bucketSeconds", resolved.bucketSeconds());
        result.put("range", Map.of("from", resolved.getFrom().toEpochMilli(),
                "to", resolved.getTo().toEpochMilli()));
        result.put("points", points);
        result.put("mom", momInfo(mom, service, kind, type, name, target, resolved, instanceList));
        return result;
    }

    // ── Heartbeat ────────────────────────────────────────────

    /** Heartbeat 指标固定五个 JVM 项，且一期不做环比（PRD 03 §10）。 */
    public List<String> heartbeatMetrics() {
        return java.util.Arrays.stream(JvmMetric.values())
                .map(JvmMetric::seriesName).toList();
    }
    public List<Map<String, Object>> heartbeatInstances(String service, String metric, String range) {
        requireHeartbeatMetric(metric);
        var resolved = ranges.resolve(parseRange(range), zone.get());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String instance : data.instancesWithData("HEARTBEAT", service, resolved.getFrom(), resolved.getTo())) {
            var rows = data.rows("HEARTBEAT", service, "jvm", metric, resolved.getFrom(), resolved.getTo(),
                    Granularity.fromSeconds(resolved.bucketSeconds()), List.of(instance));
            AggregatedRow last = new AggregatedRow(SeriesKey.of(service, com.neocat.analysis.domain.bucket.SeriesKind.HEARTBEAT,
                    "jvm", metric, instance), resolved.getFrom(), AggregationLevel.MINUTE, 0);
            rows.forEach(row -> last.mergeLastValue(row.valueLast(), row.valueLastTime()));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instance", instance); item.put("value", last.valueLast()); result.add(item);
        }
        return result;
    }
    /**
     * Heartbeat 趋势（PRD 03 §10）。
     *
     * <p>**按实例分别展示，Top N 之外不合并为 other，也不把不同 JVM 的值相加**。
     * 因此每个已选实例各自形成一条序列。
     */
    public Map<String, Object> heartbeatSeries(String service, String metric, String range, String instances) {
        requireHeartbeatMetric(metric);
        var resolved = ranges.resolve(parseRange(range), zone.get());
        List<String> targets = instances == null || instances.isBlank()
                ? data.instancesWithData("HEARTBEAT", service, resolved.getFrom(), resolved.getTo())
                : List.of(instances.split(","));

        List<Map<String, Object>> seriesList = new ArrayList<>();
        Instant heartbeatNow = clock.instant();
        Map<Instant, Boolean> droppedBuckets = new LinkedHashMap<>();
        for (Bucket bucket : resolved.getBuckets()) {
            droppedBuckets.put(bucket.getStart(), bucket.getStart().isBefore(heartbeatNow)
                    && data.droppedBetween("HEARTBEAT", service, "jvm", metric, bucket.getStart(),
                    bucket.getEnd().isAfter(heartbeatNow) ? heartbeatNow : bucket.getEnd()));
        }
        for (String instance : targets) {
            var rows = data.rows("HEARTBEAT", service, "jvm", metric,
                    resolved.getFrom(), resolved.getTo(),
                    Granularity.fromSeconds(resolved.bucketSeconds()), List.of(instance));
            Map<Long, AggregatedRow> byBucket = new LinkedHashMap<>();
            for (AggregatedRow row : rows) {
                byBucket.merge(row.bucketStart().toEpochMilli(), row, (a, b) -> {
                    a.mergeLastValue(b.valueLast(), b.valueLastTime());
                    return a;
                });
            }
            List<Map<String, Object>> points = new ArrayList<>();
            for (Bucket b : resolved.getBuckets()) {
                AggregatedRow row = byBucket.get(b.getStart().toEpochMilli());
                Double value = row == null ? null : row.valueLast();
                Instant now = heartbeatNow;
                boolean future = !b.getStart().isBefore(now);
                boolean dropped = droppedBuckets.get(b.getStart());
                if (future || dropped) value = null;
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("bucketStart", b.getStart().toEpochMilli());
                point.put("bucketEnd", b.getEnd().toEpochMilli());
                // 缺口保持 null：不把无数据当作 0
                point.put("value", value);
                point.put("quality", dropped && !future ? Quality.DROPPED.name() : value == null ? Quality.NO_DATA.name()
                        : isCurrentBucket(b) ? Quality.REALTIME.name() : b.isPartial() ? Quality.PARTIAL.name() : Quality.OK.name());
                point.put("coveredSeconds", Math.max(0, Math.min(b.getCoveredSeconds(), java.time.Duration.between(b.getStart(), now).getSeconds())));
                points.add(point);
            }
            seriesList.add(Map.of("instance", instance, "points", points));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("metric", metric);
        result.put("bucketSeconds", resolved.bucketSeconds());
        result.put("series", seriesList);
        // 一期不做 Heartbeat 环比（PRD 03 §10）
        result.put("mom", null);
        return result;
    }
    private void requireHeartbeatMetric(String metric) {
        if (!heartbeatMetrics().contains(metric)) throw new com.neocat.common.error.exception.ValidationException(
                com.neocat.common.error.ErrorCode.INVALID_PARAM, "未知 Heartbeat 指标 " + metric);
    }

    // ── Metric ───────────────────────────────────────────────

    public List<Map<String, Object>> metricList(String service, Long hour) {
        var resolved = hour == null
                ? ranges.resolve(parseRange("RECENT_1H"), zone.get())
                : ranges.resolve(new RangeSpec.Hour(Instant.ofEpochMilli(hour)), zone.get());

        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : data.namesOf("METRIC", service, null, resolved.getFrom(), resolved.getTo())) {
            var rows = data.rows("METRIC", service, null, name, resolved.getFrom(), resolved.getTo(),
                    Granularity.fromSeconds(resolved.bucketSeconds()), List.of());
            long reportCount = rows.stream().mapToLong(AggregatedRow::valueCount).sum();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("labels", name);
            item.put("reportCount", reportCount);
            result.add(item);
        }
        result.sort(java.util.Comparator.<Map<String, Object>>comparingLong(item -> (Long) item.get("reportCount"))
                .reversed().thenComparing(item -> (String) item.get("labels")));
        for (int i = 0; i < result.size(); i++) result.get(i).put("rank", i + 1);
        return result;
    }

    // ── 依赖 ─────────────────────────────────────────────────

    public List<Map<String, Object>> downstream(String service, String range) {
        return dependencyList(service, "DOWNSTREAM", range);
    }
    public List<Map<String, Object>> upstream(String service, String range) {
        return dependencyList(service, "UPSTREAM", range);
    }

    // ── 取样 ─────────────────────────────────────────────────

    /**
     * 调用取样（PRD 03 §11）：按事件时间倒序返回最近 N 条（默认 30）。
     *
     * <p>该端点依赖原始树存储；无原始树时返回空列表（汇总仍可查，只有下钻不可用）。
     */
    public List<Map<String, Object>> samples(String service, String kind, String type, String name,
                                            String range, Integer limit) {
        var resolved = ranges.resolve(parseRange(range), zone.get());
        return samplePort.samples(service, type, name, resolved.getFrom(), resolved.getTo(),
                        limit == null ? com.neocat.common.config.TraceConfig.SAMPLE_ROWS : limit).stream()
                 .map(ReportQueryService::sampleToMap)
                .toList();
    }
    private static Map<String, Object> sampleToMap(com.neocat.trace.domain.sample.Sample sample) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("messageId", sample.getMessageId());
        map.put("timestamp", sample.getTimestamp());
        map.put("durationMs", sample.getDurationMs());
        map.put("status", sample.getStatus());
        map.put("summary", sample.getSummary());
        map.put("traceAvailable", sample.isTraceAvailable());
        return map;
    }

    // ── 内部 ─────────────────────────────────────────────────

    /**
     * 空范围（无桶）时返回结构完整的空序列。
     *
     * <p>解析不出桶时不构造 `Granularity` 与逐桶循环，避免 `fromSeconds(0)` 返回 null
     * 后在后续取值处抛 NPE —— 时间范围写错不该让接口 500。
     */
    private Map<String, Object> emptySeries(String service, String kind, String type, String name,
                                            Stat target, RangeResolver.ResolvedRange base) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("kind", kind);
        result.put("type", type);
        result.put("name", name);
        result.put("stat", target.name());
        result.put("unit", target.unit().name());
        result.put("bucketSeconds", 0);
        result.put("range", Map.of("from", base.getFrom().toEpochMilli(), "to", base.getTo().toEpochMilli()));
        result.put("points", List.of());
        result.put("mom", null);
        return result;
    }

    private List<Map<String, Object>> typeTable(String kind, String service, String range) {
        var resolved = ranges.resolve(parseRange(range), zone.get());
        List<ReportRow> rows = new ArrayList<>();
        for (String type : data.typesOf(kind, service, resolved.getFrom(), resolved.getTo())) {
            var aggregated = fetchRows(kind, service, type, null, resolved);
            rows.addAll(tables.typeTable(kind, aggregated, resolved.bucketSeconds()));
        }
        return rows.stream().map(row -> reportRowToMap(row, kind)).toList();
    }
    private List<Map<String, Object>> nameTable(String kind, String service, String type, String range) {
        var resolved = ranges.resolve(parseRange(range), zone.get());
        var aggregated = fetchRows(kind, service, type, null, resolved);
        return tables.nameTable(kind, type, aggregated, resolved.bucketSeconds()).stream()
                .map(row -> reportRowToMap(row, kind))
                .toList();
    }
    private List<AggregatedRow> fetchRows(String kind, String service, String type, String name,
                                         RangeResolver.ResolvedRange resolved) {
        return data.rows(kind, service, type, name, resolved.getFrom(), resolved.getTo(),
                Granularity.fromSeconds(resolved.bucketSeconds()), List.of());
    }
    private List<Map<String, Object>> dependencyList(String service, String direction, String range) {
        var resolved = ranges.resolve(parseRange(range), zone.get());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String peer : data.namesOf("DEPENDENCY", service, direction, resolved.getFrom(), resolved.getTo())) {
            var rows = fetchRows("DEPENDENCY", service, direction, peer, resolved);
            long calls = rows.stream().mapToLong(AggregatedRow::count).sum();
            long failures = rows.stream().mapToLong(AggregatedRow::failCount).sum();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("peer", peer);
            item.put("calls", calls);
            item.put("failureRate", calls == 0 ? null : (double) failures / calls);
            item.put("avg", calculator.compute(rows, Stat.AVG, resolved.bucketSeconds()));
            item.put("tp99", calculator.compute(rows, Stat.TP99, resolved.bucketSeconds()));
            result.add(item);
        }
        result.sort((a, b) -> Long.compare((Long) b.get("calls"), (Long) a.get("calls")));
        return result;
    }
    private Map<String, Object> reportRowToMap(ReportRow row, String kind) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", row.getType());
        map.put("name", row.getName());
        map.put("total", row.getTotal());
        map.put("failures", row.getFailures());
        map.put("failureRate", row.getFailureRate());
        map.put("qps", row.getQps());
        // Event 与异常类 Problem 不提供耗时与分位（PRD 03 §8、§9）
        map.put("min", row.getMinDuration() == 0 && !row.hasDurationMetrics() ? null : row.getMinDuration());
        map.put("max", row.getMaxDuration() == 0 && !row.hasDurationMetrics() ? null : row.getMaxDuration());
        map.put("avg", row.getAvgDuration());
        map.put("tp50", row.getTp50());
        map.put("tp90", row.getTp90());
        map.put("tp95", row.getTp95());
        map.put("tp99", row.getTp99());
        map.put("tp999", row.getTp999());
        map.put("tp9999", row.getTp9999());
        return map;
    }
    /** 环比：按整日偏移、桶序号对齐（PRD 03 §6）；不支持的类型返回 null。 */
    private Map<String, Object> momInfo(String mom, String service, String kind, String type, String name,
                                        Stat stat, RangeResolver.ResolvedRange resolved,
                                        List<String> instances) {
        if (mom == null || mom.isBlank() || !momAligner.supported(kind)) {
            return null;
        }
        MomKind momKind = MomKind.valueOf(mom.toUpperCase(java.util.Locale.ROOT));
        List<Bucket> shifted = momAligner.shift(resolved.getBuckets(), momKind, zone.get());

        Map<Long, Double> byShiftedBucket = new LinkedHashMap<>();
        // 对比窗口的桶长必须与当前窗口一致，否则平移后的桶起点对不上
        List<AggregatedRow> rows = data.rows(kind, service, type, name,
                shifted.get(0).getStart(), shifted.get(shifted.size() - 1).getEnd(),
                Granularity.fromSeconds(resolved.bucketSeconds()), instances);
        for (AggregatedRow row : rows) {
            byShiftedBucket.merge(row.bucketStart().toEpochMilli(), safeValue(row, stat), Double::sum);
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (Bucket b : shifted) {
            Double value = byShiftedBucket.get(b.getStart().toEpochMilli());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucketStart", b.getStart().toEpochMilli());
            point.put("value", value);
            points.add(point);
        }
        return Map.of("kind", momKind.name(), "points", points);
    }
    /** 取行的统计值；null 表示缺数，不参与相加时按「无值」处理。 */
    private Double safeValue(AggregatedRow row, Stat stat) {
        Double value = calculator.compute(List.of(row), stat, row.coveredSeconds());
        return value == null ? null : value;
    }
    private boolean isCurrentBucket(Bucket bucket) {
        Instant now = clock.instant();
        return !now.isBefore(bucket.getStart()) && now.isBefore(bucket.getEnd());
    }
    /**
     * 解析 `range` 查询参数（技术方案 03 §4.1）。
     *
     * <p>解析规则集中在 {@link com.neocat.common.time.range.RangeParams}，本方法只提供当前时钟。
     */
    private RangeSpec parseRange(String range) {
        return com.neocat.common.time.range.RangeParams.parse(range, clock.instant());
    }



}





