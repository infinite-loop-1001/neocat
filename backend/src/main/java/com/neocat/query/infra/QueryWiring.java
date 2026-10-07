package com.neocat.query.infra;

import com.neocat.query.infra.datasource.ClickHouseReportDataPort;
import com.neocat.query.infra.datasource.ClickHouseReportQuery;
import com.neocat.query.infra.datasource.HourlyReportDataPort;
import com.neocat.query.infra.datasource.JdbcClickHouseReportQuery;
import com.neocat.query.infra.datasource.JdbcMetricMetadataPort;
import com.neocat.query.infra.datasource.ReportDataPortRouter;
import com.neocat.query.infra.port.MetricMetadataPort;
import com.neocat.query.infra.port.ReportDataPort;
import com.neocat.query.infra.port.SamplePort;

import com.neocat.query.domain.series.MomAligner;
import com.neocat.query.domain.series.QualityResolver;
import com.neocat.query.domain.report.ReportTableService;
import com.neocat.query.domain.stat.StatCalculator;

import com.neocat.trace.domain.sample.SampleService;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
@org.springframework.context.annotation.DependsOn("traceConfig")
public class QueryWiring {

    @Bean
    public MetricMetadataPort metricMetadataPort(
            @org.springframework.beans.factory.annotation.Qualifier("clickHouseDataSource") javax.sql.DataSource source,
            com.fasterxml.jackson.databind.ObjectMapper json, com.neocat.analysis.domain.metric.MetricLabelMetadata memory) {
        return new JdbcMetricMetadataPort(source, json, memory);
    }
    @Bean
    public SamplePort samplePort(SampleService sampleService, java.time.Clock clock) {
        return SamplePort.of(sampleService, clock);
    }
    @Bean
    public ClickHouseReportQuery clickHouseReportQuery(
            @org.springframework.beans.factory.annotation.Qualifier("clickHouseDataSource")
            javax.sql.DataSource dataSource,
            java.util.function.Supplier<java.time.ZoneId> platformZone) {
        return new JdbcClickHouseReportQuery(dataSource, platformZone);
    }
    // ── 查询 ─────────────────────────────────────────────────

    /**
     * 报表数据口：ClickHouse 负责历史，内存实现负责当前小时。
     *
     * <p>路由将当前小时交由内存实现，已结束的小时交由 ClickHouse 实现。
     */
    @Bean
    public ReportDataPort reportDataPort(ClickHouseReportQuery query,
            com.neocat.analysis.domain.bucket.HourlyReportStore store,
            com.neocat.common.time.bucket.TimeBucketResolver buckets,
            java.util.function.Supplier<java.time.ZoneId> platformZone, java.time.Clock clock,
            com.neocat.analysis.domain.metric.MetricLabelMetadata metadata) {
        return new ReportDataPortRouter(new ClickHouseReportDataPort(query, buckets, platformZone, clock),
                new HourlyReportDataPort(store, buckets, platformZone, metadata), clock, platformZone);
    }

}
