package com.neocat.query.api.http;

import com.neocat.query.api.http.dto.ReportDtos.*;
import com.neocat.query.api.http.convert.ReportConvert;
import com.neocat.query.infra.service.ReportQueryService;
import com.neocat.query.infra.port.ReportDataPort;
import com.neocat.query.infra.port.SamplePort;
import com.neocat.query.domain.report.ReportTableService;
import com.neocat.query.domain.series.MomAligner;
import com.neocat.query.domain.series.QualityResolver;
import com.neocat.query.domain.stat.StatCalculator;
import com.neocat.common.time.bucket.TimeBucketResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

@RestController
@org.springframework.context.annotation.DependsOn("traceConfig")
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportQueryService query;

    private final ReportConvert convert;

    /** 离线规格可直接构造；实际容器使用下方唯一注入构造函数。 */
    public ReportController(ReportDataPort data, TimeBucketResolver buckets, ReportTableService tables,
                            StatCalculator calculator, QualityResolver quality, MomAligner mom,
                            SamplePort samples, Supplier<ZoneId> zone) {
        this(data, buckets, tables, calculator, quality, mom, samples, zone, Clock.systemUTC());
    }

    public ReportController(ReportDataPort data, TimeBucketResolver buckets, ReportTableService tables,
                            StatCalculator calculator, QualityResolver quality, MomAligner mom,
                            SamplePort samples, Supplier<ZoneId> zone, Clock clock) {
        this(new ReportQueryService(data, buckets, tables, calculator, quality, mom, samples, zone, clock),
                new ReportConvert(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReportController(ReportQueryService query, ReportConvert convert) {
        this.query = query;
        this.convert = convert;
    }

    @GetMapping("/transaction/types")
    public ResponseEntity<List<TableRow>> transactionTypes(@RequestParam String service,
            @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.transactionTypes(service, range), TableRow.class));
    }

    @GetMapping("/transaction/names")
    public ResponseEntity<List<TableRow>> transactionNames(@RequestParam String service, @RequestParam String type,
            @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.transactionNames(service, type, range), TableRow.class));
    }

    @GetMapping("/event/types")
    public ResponseEntity<List<TableRow>> eventTypes(@RequestParam String service,
            @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.eventTypes(service, range), TableRow.class));
    }

    @GetMapping("/event/names")
    public ResponseEntity<List<TableRow>> eventNames(@RequestParam String service, @RequestParam String type,
            @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.eventNames(service, type, range), TableRow.class));
    }

    @GetMapping("/problem/categories")
    public ResponseEntity<List<ProblemCategory>> problemCategories(@RequestParam String service,
            @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.problemCategories(service, range), ProblemCategory.class));
    }

    @GetMapping("/problem/names")
    public ResponseEntity<List<ProblemName>> problemNames(@RequestParam String service, @RequestParam String category,
            @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.problemNames(service, category, range), ProblemName.class));
    }

    @GetMapping("/series")
    public ResponseEntity<Series> series(@RequestParam String service, @RequestParam String kind,
            @RequestParam(required = false) String type, @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "HITS") String stat, @RequestParam(defaultValue = "RECENT_1H") String range,
            @RequestParam(required = false) Integer bucket, @RequestParam(required = false) String mom,
            @RequestParam(required = false) String instances) {
        return ResponseEntity.ok(convert.response(query.series(service, kind, type, name, stat, range, bucket, mom, instances), Series.class));
    }

    @GetMapping("/heartbeat/metrics")
    public ResponseEntity<List<String>> heartbeatMetrics() {
        return ResponseEntity.ok(query.heartbeatMetrics());
    }

    @GetMapping("/heartbeat/instances")
    public ResponseEntity<List<HeartbeatInstance>> heartbeatInstances(@RequestParam("service") String service,
            @RequestParam(value = "metric", defaultValue = "heap-used") String metric,
            @RequestParam(value = "range", defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.heartbeatInstances(service, metric, range), HeartbeatInstance.class));
    }

    @GetMapping("/heartbeat/series")
    public ResponseEntity<HeartbeatSeries> heartbeatSeries(@RequestParam("service") String service,
            @RequestParam(value = "metric", defaultValue = "heap-used") String metric,
            @RequestParam(value = "range", defaultValue = "RECENT_1H") String range,
            @RequestParam(value = "instances", required = false) String instances) {
        return ResponseEntity.ok(convert.response(query.heartbeatSeries(service, metric, range, instances), HeartbeatSeries.class));
    }

    @GetMapping("/metric/list")
    public ResponseEntity<List<MetricRank>> metricList(@RequestParam String service, @RequestParam(required = false) Long hour) {
        return ResponseEntity.ok(convert.responses(query.metricList(service, hour), MetricRank.class));
    }

    @GetMapping("/dependency/downstream")
    public ResponseEntity<List<Dependency>> downstream(@RequestParam String service,
            @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.downstream(service, range), Dependency.class));
    }

    @GetMapping("/dependency/upstream")
    public ResponseEntity<List<Dependency>> upstream(@RequestParam String service,
            @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.upstream(service, range), Dependency.class));
    }

    @GetMapping("/samples")
    public ResponseEntity<List<Sample>> samples(@RequestParam String service, @RequestParam(required = false) String kind,
            @RequestParam(required = false) String type, @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "RECENT_1H") String range, @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(convert.responses(query.samples(service, kind, type, name, range, limit), Sample.class));
    }
}
