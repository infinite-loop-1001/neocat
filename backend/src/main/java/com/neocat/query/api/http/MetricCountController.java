package com.neocat.query.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocat.query.api.http.dto.*;
import com.neocat.query.api.http.convert.ReportConvert;
import com.neocat.query.infra.service.MetricCountQueryService;
import com.neocat.query.infra.port.ReportDataPort;
import com.neocat.query.infra.port.MetricMetadataPort;
import com.neocat.common.time.bucket.TimeBucketResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import com.neocat.query.api.http.dto.MetricCount;
import com.neocat.query.api.http.dto.MetricLabel;
import com.neocat.query.api.http.dto.MetricName;

@Tag(name = "Metric count", description = "Metric 指标目录、标签候选与上报次数曲线")
@RestController
@RequestMapping("/api/reports/metric")
public class MetricCountController {
    private final MetricCountQueryService query;

    private final ReportConvert convert;

    public MetricCountController(ReportDataPort data, MetricMetadataPort metadata, TimeBucketResolver buckets,
                                 Supplier<ZoneId> zone, ObjectMapper json) {
        this(new MetricCountQueryService(data, metadata, buckets, zone, json), new ReportConvert(json));
    }

    @Autowired
    public MetricCountController(MetricCountQueryService query, ReportConvert convert) {
        this.query = query;
        this.convert = convert;
    }

    @Operation(operationId = "metricCountMetrics", summary = "查询服务的指标目录",
            description = "返回该服务在当前时间范围内出现的指标名；当前范围无数据时为空列表。")
    @ApiResponse(responseCode = "200", description = "指标名列表")
    @GetMapping("/metrics")
    public ResponseEntity<List<MetricName>> metrics(
            @Parameter(description = "服务名", required = true) @RequestParam("service") String service,
            @Parameter(description = "时间范围") @RequestParam(value = "range", defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.metrics(service, range), MetricName.class));
    }

    @Operation(operationId = "metricCountLabels", summary = "查询单指标的标签候选",
            description = "返回该指标在该范围内出现过的标签键与取值，供筛选器使用。")
    @ApiResponse(responseCode = "200", description = "标签键与候选值列表")
    @GetMapping("/labels")
    public ResponseEntity<List<MetricLabel>> labels(
            @Parameter(description = "服务名", required = true) @RequestParam("service") String service,
            @Parameter(description = "指标名", required = true) @RequestParam("metric") String metric,
            @Parameter(description = "时间范围") @RequestParam(value = "range", defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.labels(service, metric, range), MetricLabel.class));
    }

    @Operation(operationId = "metricCountSeries", summary = "查询单指标的上报次数曲线",
            description = "次数口径为上报次数（value_count），不是数值总和；"
                    + "筛选匹配数据已并入 other 且无法还原时返回 null / MERGED_OTHER，确认无匹配返回 0 / ZERO。")
    @ApiResponse(responseCode = "200", description = "count 曲线，含 bucketSeconds 与 points")
    @ApiResponse(responseCode = "400", description = "filters 非法（键值数量或 JSON 超限）：INVALID_PARAM")
    @ApiResponse(responseCode = "404", description = "当前范围没有该指标：NOT_FOUND")
    @GetMapping("/count")
    public ResponseEntity<MetricCount> count(
            @Parameter(description = "服务名", required = true) @RequestParam("service") String service,
            @Parameter(description = "指标名", required = true) @RequestParam("metric") String metric,
            @Parameter(description = "时间范围") @RequestParam(value = "range", defaultValue = "RECENT_1H") String range,
            @Parameter(description = "URL 编码后的 JSON 筛选对象，如 {\"channel\":[\"app\",\"web\"]}；同键多值 OR、不同键 AND")
            @RequestParam(value = "filters", required = false) String filters) {
        return ResponseEntity.ok(convert.response(query.count(service, metric, range, filters), MetricCount.class));
    }
}
