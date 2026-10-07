package com.neocat.query.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocat.query.api.http.dto.ReportDtos.*;
import com.neocat.query.api.http.convert.ReportConvert;
import com.neocat.query.infra.service.MetricCountQueryService;
import com.neocat.query.infra.port.ReportDataPort;
import com.neocat.query.infra.port.MetricMetadataPort;
import com.neocat.common.time.bucket.TimeBucketResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/reports/metric")
public class MetricCountController {
    private final MetricCountQueryService query;

    private final ReportConvert convert;

    public MetricCountController(ReportDataPort data, MetricMetadataPort metadata, TimeBucketResolver buckets,
                                 Supplier<ZoneId> zone, Clock clock, ObjectMapper json) {
        this(new MetricCountQueryService(data, metadata, buckets, zone, clock, json), new ReportConvert(json));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MetricCountController(MetricCountQueryService query, ReportConvert convert) {
        this.query = query;
        this.convert = convert;
    }

    @GetMapping("/metrics")
    public ResponseEntity<List<MetricName>> metrics(@RequestParam("service") String service,
            @RequestParam(value = "range", defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.metrics(service, range), MetricName.class));
    }

    @GetMapping("/labels")
    public ResponseEntity<List<MetricLabel>> labels(@RequestParam("service") String service,
            @RequestParam("metric") String metric,
            @RequestParam(value = "range", defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.labels(service, metric, range), MetricLabel.class));
    }

    @GetMapping("/count")
    public ResponseEntity<MetricCount> count(@RequestParam("service") String service,
            @RequestParam("metric") String metric,
            @RequestParam(value = "range", defaultValue = "RECENT_1H") String range,
            @RequestParam(value = "filters", required = false) String filters) {
        return ResponseEntity.ok(convert.response(query.count(service, metric, range, filters), MetricCount.class));
    }
}
