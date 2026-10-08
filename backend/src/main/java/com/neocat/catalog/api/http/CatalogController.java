package com.neocat.catalog.api.http;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.catalog.api.http.dto.ServiceResponse;
import com.neocat.catalog.api.http.convert.CatalogConvert;

import com.neocat.catalog.domain.service.CatalogService;
import com.neocat.catalog.domain.report.ReportKind;
import com.neocat.catalog.domain.report.TimeRange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

/**
 * 服务目录接口（技术方案 03-api-contract.md §4.1）。
 *
 * <p>关键约束（PRD 02 §5、PRD 00 §4.1）：服务与实例列表按
 * 「当前报表类型 + 当前时间范围」动态过滤，**无数据的服务/实例不展示**。
 * 该过滤由 {@link CatalogService#servicesWithData} 完成，
 * 数据来源是报表侧（当前小时读内存报表，历史读 ClickHouse）。
 */
@Tag(name = "服务目录", description = "按报表类型与时间范围动态过滤的服务与实例")
@RestController
@RequestMapping("/api/services")
public class CatalogController {

    private final CatalogService catalog;


    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }
    @Operation(operationId = "listServices", summary = "查询有数据的服务列表",
            description = "只返回当前报表类型 + 当前时间范围内有数据的服务，无数据的服务不展示。")
    @ApiResponse(responseCode = "200", description = "服务列表，含各服务的有数据实例")
    @GetMapping
    public ResponseEntity<List<ServiceResponse>> services(
            @Parameter(description = "报表类型，取值 TRANSACTION | EVENT | PROBLEM | METRIC | HEARTBEAT | DEPENDENCY；非法值回落 TRANSACTION")
            @RequestParam(defaultValue = "TRANSACTION") String kind,
            @Parameter(description = "窗口起点（epoch millis），省略表示到终点前 1 小时") @RequestParam(required = false) Long from,
            @Parameter(description = "窗口终点（epoch millis），省略表示当前时刻") @RequestParam(required = false) Long to) {
        TimeRange range = range(from, to);
        ReportKind reportKind = parseKind(kind);

        List<ServiceResponse> result = catalog.servicesWithData(reportKind, range).stream()
                .map(name -> CatalogConvert.service(name, catalog.instancesWithData(name, reportKind, range)))
                .toList();
        return ResponseEntity.ok(result);
    }
    @Operation(operationId = "listServiceInstances", summary = "查询服务的实例列表",
            description = "同样只返回当前类型 + 时间范围内有数据的实例。")
    @ApiResponse(responseCode = "200", description = "实例 ID 列表")
    @GetMapping("/{service}/instances")
    public ResponseEntity<List<String>> instances(
            @Parameter(description = "服务名", required = true)
            @PathVariable String service,
            @Parameter(description = "报表类型，取值同服务列表")
            @RequestParam(defaultValue = "TRANSACTION") String kind,
            @Parameter(description = "窗口起点（epoch millis）")
            @RequestParam(required = false) Long from,
            @Parameter(description = "窗口终点（epoch millis）")
            @RequestParam(required = false) Long to) {
        return ResponseEntity.ok(catalog.instancesWithData(service, parseKind(kind), range(from, to)));
    }
    private TimeRange range(Long from, Long to) {
        Instant end = Objects.isNull(to) ? TimeProvider.now() : Instant.ofEpochMilli(to);
        Instant start = Objects.isNull(from) ? end.minusSeconds(3600) : Instant.ofEpochMilli(from);
        return new TimeRange(start, end);
    }
    private ReportKind parseKind(String kind) {
        try {
            return ReportKind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ReportKind.TRANSACTION;
        }
    }
}
