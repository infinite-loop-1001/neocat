package com.neocat.catalog.api.http;

import com.neocat.catalog.api.http.dto.ServiceResponse;
import com.neocat.catalog.api.http.convert.CatalogConvert;

import com.neocat.catalog.domain.service.CatalogService;
import com.neocat.catalog.domain.report.ReportKind;
import com.neocat.catalog.domain.report.TimeRange;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 服务目录接口（技术方案 03-api-contract.md §4.1）。
 *
 * <p>关键约束（PRD 02 §5、PRD 00 §4.1）：服务与实例列表按
 * 「当前报表类型 + 当前时间范围」动态过滤，**无数据的服务/实例不展示**。
 * 该过滤由 {@link CatalogService#servicesWithData} 完成，
 * 数据来源是报表侧（当前小时读内存报表，历史读 ClickHouse）。
 */
@RestController
@RequestMapping("/api/services")
public class CatalogController {

    private final CatalogService catalog;

    private final java.time.Clock clock;

    public CatalogController(CatalogService catalog, java.time.Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }
    @GetMapping
    public ResponseEntity<List<ServiceResponse>> services(
            @RequestParam(defaultValue = "TRANSACTION") String kind,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        TimeRange range = range(from, to);
        ReportKind reportKind = parseKind(kind);

        List<ServiceResponse> result = catalog.servicesWithData(reportKind, range).stream()
                .map(name -> CatalogConvert.service(name, catalog.instancesWithData(name, reportKind, range)))
                .toList();
        return ResponseEntity.ok(result);
    }
    @GetMapping("/{service}/instances")
    public ResponseEntity<List<String>> instances(
            @PathVariable String service,
            @RequestParam(defaultValue = "TRANSACTION") String kind,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return ResponseEntity.ok(catalog.instancesWithData(service, parseKind(kind), range(from, to)));
    }
    private TimeRange range(Long from, Long to) {
        Instant end = to == null ? clock.instant() : Instant.ofEpochMilli(to);
        Instant start = from == null ? end.minusSeconds(3600) : Instant.ofEpochMilli(from);
        return new TimeRange(start, end);
    }
    private ReportKind parseKind(String kind) {
        try {
            return ReportKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ReportKind.TRANSACTION;
        }
    }
}
