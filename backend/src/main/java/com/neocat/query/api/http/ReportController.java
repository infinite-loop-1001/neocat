package com.neocat.query.api.http;

import com.neocat.query.api.http.dto.*;
import com.neocat.query.api.http.convert.ReportConvert;
import com.neocat.query.infra.service.ReportQueryService;
import com.neocat.query.infra.port.ReportDataPort;
import com.neocat.query.infra.port.SamplePort;
import com.neocat.query.domain.report.ReportTableService;
import com.neocat.query.domain.series.MomAligner;
import com.neocat.query.domain.series.QualityResolver;
import com.neocat.query.domain.stat.StatCalculator;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import com.neocat.query.api.http.dto.Dependency;
import com.neocat.query.api.http.dto.HeartbeatInstance;
import com.neocat.query.api.http.dto.HeartbeatSeries;
import com.neocat.query.api.http.dto.MetricRank;
import com.neocat.query.api.http.dto.ProblemCategory;
import com.neocat.query.api.http.dto.ProblemName;
import com.neocat.query.api.http.dto.Sample;
import com.neocat.query.api.http.dto.Series;
import com.neocat.query.api.http.dto.TableRow;

@Tag(name = "报表", description = "Transaction / Event / Problem / Heartbeat / Metric / Dependency 读模型与取样")
@RestController
@DependsOn("traceConfig")
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportQueryService query;

    private final ReportConvert convert;

    /** 离线规格可直接构造；实际容器使用下方唯一注入构造函数。 */
    public ReportController(ReportDataPort data, TimeBucketResolver buckets, ReportTableService tables,
                            StatCalculator calculator, QualityResolver quality, MomAligner mom,
                            SamplePort samples, Supplier<ZoneId> zone) {
        this(new ReportQueryService(data, buckets, tables, calculator, quality, mom, samples, zone),
                new ReportConvert(new ObjectMapper()));
    }

    @Autowired
    public ReportController(ReportQueryService query, ReportConvert convert) {
        this.query = query;
        this.convert = convert;
    }

    @Operation(operationId = "transactionTypes", summary = "Transaction Type 汇总",
            description = "返回各 Type 的计数、耗时、分位与 QPS；先合并分子与分布再计算，不平均各桶。")
    @ApiResponse(responseCode = "200", description = "Type 行列表")
    @GetMapping("/transaction/types")
    public ResponseEntity<List<TableRow>> transactionTypes(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "时间范围，如 RECENT_1H / TODAY / HOUR:<epoch>") @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.transactionTypes(service, range), TableRow.class));
    }

    @Operation(operationId = "transactionNames", summary = "某 Type 下的 Transaction Name 列表")
    @ApiResponse(responseCode = "200", description = "Name 行列表")
    @GetMapping("/transaction/names")
    public ResponseEntity<List<TableRow>> transactionNames(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "Transaction Type，如 URL / SQL", required = true) @RequestParam String type,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.transactionNames(service, type, range), TableRow.class));
    }

    @Operation(operationId = "eventTypes", summary = "Event Type 汇总",
            description = "只含次数、失败数与 QPS，没有耗时字段。")
    @ApiResponse(responseCode = "200", description = "Type 行列表")
    @GetMapping("/event/types")
    public ResponseEntity<List<TableRow>> eventTypes(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.eventTypes(service, range), TableRow.class));
    }

    @Operation(operationId = "eventNames", summary = "某 Event Type 下的 Name 列表")
    @ApiResponse(responseCode = "200", description = "Name 行列表")
    @GetMapping("/event/names")
    public ResponseEntity<List<TableRow>> eventNames(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "Event Type", required = true) @RequestParam String type,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.eventNames(service, type, range), TableRow.class));
    }

    @Operation(operationId = "problemCategories", summary = "Problem 五类汇总",
            description = "固定五类：EXCEPTION、SLOW_URL、SLOW_SQL、SLOW_CALL、SLOW_CACHE；标记是否支持分位。")
    @ApiResponse(responseCode = "200", description = "分类行列表")
    @GetMapping("/problem/categories")
    public ResponseEntity<List<ProblemCategory>> problemCategories(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.problemCategories(service, range), ProblemCategory.class));
    }

    @Operation(operationId = "problemNames", summary = "某 Problem 分类下的 Name 列表",
            description = "异常按异常名、慢类按 Transaction Name 分组。")
    @ApiResponse(responseCode = "200", description = "Name 行列表")
    @GetMapping("/problem/names")
    public ResponseEntity<List<ProblemName>> problemNames(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "Problem 分类，取值同 categories", required = true) @RequestParam String category,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.problemNames(service, category, range), ProblemName.class));
    }

    @Operation(operationId = "reportSeries", summary = "通用趋势序列",
            description = "按 stat 计算趋势并可选环比；缺数点 value 为 null 并带 quality，绝不写 0。")
    @ApiResponse(responseCode = "200", description = "趋势结构，含 points、mom 与 gaps")
    @GetMapping("/series")
    public ResponseEntity<Series> series(
            @Parameter(description = "服务名", required = true)
            @RequestParam String service,
            @Parameter(description = "报表类型：TRANSACTION | EVENT | PROBLEM | METRIC | HEARTBEAT | DEPENDENCY", required = true) @RequestParam String kind,
            @Parameter(description = "维度 Type，按 kind 决定是否必填")
            @RequestParam(required = false) String type,
            @Parameter(description = "维度 Name，按 kind 决定是否必填")
            @RequestParam(required = false) String name,
            @Parameter(description = "统计项：HITS | FAILURES | FAILURE_RATE | QPS | AVG | MIN | MAX | TP50 | TP90 | TP95 | TP99 | TP999 | TP9999")
            @RequestParam(defaultValue = "HITS") String stat,
            @Parameter(description = "时间范围")
            @RequestParam(defaultValue = "RECENT_1H") String range,
            @Parameter(description = "桶粒度（秒），只接受已定义档位；不在这组档位时忽略并回落 range 默认粒度")
            @RequestParam(required = false) Integer bucket,
            @Parameter(description = "环比：DAY | WEEK | MONTH")
            @RequestParam(required = false) String mom,
            @Parameter(description = "逗号分隔实例；缺省表示全部机器聚合")
            @RequestParam(required = false) String instances) {
        return ResponseEntity.ok(convert.response(query.series(service, kind, type, name, stat, range, bucket, mom, instances), Series.class));
    }

    @Operation(operationId = "heartbeatMetrics", summary = "查询 JVM 心跳指标目录",
            description = "返回 20 项 JVM 指标的接口 key，与前端编目一致。")
    @ApiResponse(responseCode = "200", description = "指标 key 列表")
    @GetMapping("/heartbeat/metrics")
    public ResponseEntity<List<String>> heartbeatMetrics() {
        return ResponseEntity.ok(query.heartbeatMetrics());
    }

    @Operation(operationId = "heartbeatInstances", summary = "心跳实例列表",
            description = "每个实例取该窗口内的最后有效采样，不求和、不取最大值。")
    @ApiResponse(responseCode = "200", description = "实例与最后值")
    @GetMapping("/heartbeat/instances")
    public ResponseEntity<List<HeartbeatInstance>> heartbeatInstances(
            @Parameter(description = "服务名", required = true) @RequestParam("service") String service,
            @Parameter(description = "JVM 指标 key，如 heap-used") @RequestParam(value = "metric", defaultValue = "heap-used") String metric,
            @Parameter(description = "时间范围") @RequestParam(value = "range", defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.heartbeatInstances(service, metric, range), HeartbeatInstance.class));
    }

    @Operation(operationId = "heartbeatSeries", summary = "心跳指标趋势",
            description = "按实例返回最后值序列，不合并不同 JVM 的值；一期不支持环比。")
    @ApiResponse(responseCode = "200", description = "按实例分组的趋势")
    @ApiResponse(responseCode = "400", description = "请求环比：INVALID_PARAM")
    @GetMapping("/heartbeat/series")
    public ResponseEntity<HeartbeatSeries> heartbeatSeries(
            @Parameter(description = "服务名", required = true) @RequestParam("service") String service,
            @Parameter(description = "JVM 指标 key") @RequestParam(value = "metric", defaultValue = "heap-used") String metric,
            @Parameter(description = "时间范围") @RequestParam(value = "range", defaultValue = "RECENT_1H") String range,
            @Parameter(description = "逗号分隔实例；省略表示全部") @RequestParam(value = "instances", required = false) String instances) {
        return ResponseEntity.ok(convert.response(query.heartbeatSeries(service, metric, range, instances), HeartbeatSeries.class));
    }

    @Operation(operationId = "metricRankList", summary = "某小时 Metric 排名序列",
            description = "返回该小时排名前 1000 的真实序列与 other 合并项，含 rank 与 reportCount。")
    @ApiResponse(responseCode = "200", description = "排名行列表")
    @GetMapping("/metric/list")
    public ResponseEntity<List<MetricRank>> metricList(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "小时起点（epoch millis）；省略表示当前小时") @RequestParam(required = false) Long hour) {
        return ResponseEntity.ok(convert.responses(query.metricList(service, hour), MetricRank.class));
    }

    @Operation(operationId = "dependencyDownstream", summary = "下游依赖列表")
    @ApiResponse(responseCode = "200", description = "依赖行列表")
    @GetMapping("/dependency/downstream")
    public ResponseEntity<List<Dependency>> downstream(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.downstream(service, range), Dependency.class));
    }

    @Operation(operationId = "dependencyUpstream", summary = "上游依赖列表")
    @ApiResponse(responseCode = "200", description = "依赖行列表")
    @GetMapping("/dependency/upstream")
    public ResponseEntity<List<Dependency>> upstream(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_1H") String range) {
        return ResponseEntity.ok(convert.responses(query.upstream(service, range), Dependency.class));
    }

    @Operation(operationId = "reportSamples", summary = "最近取样",
            description = "返回最近 30 条按事件时间倒序的记录；traceAvailable 表示原始树是否仍可下钻。")
    @ApiResponse(responseCode = "200", description = "取样列表")
    @GetMapping("/samples")
    public ResponseEntity<List<Sample>> samples(
            @Parameter(description = "服务名", required = true) @RequestParam String service,
            @Parameter(description = "报表类型") @RequestParam(required = false) String kind,
            @Parameter(description = "维度 Type") @RequestParam(required = false) String type,
            @Parameter(description = "维度 Name") @RequestParam(required = false) String name,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_1H") String range,
            @Parameter(description = "返回条数上限，默认 30") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(convert.responses(query.samples(service, kind, type, name, range, limit), Sample.class));
    }
}
