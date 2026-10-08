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

import com.neocat.trace.domain.sample.SampleService;
import org.springframework.context.annotation.Bean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.metric.MetricLabelMetadata;
import com.neocat.common.time.bucket.TimeBucketResolver;
import java.time.ZoneId;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
@DependsOn("traceConfig")
public class QueryWiring {

    @Bean
    public MetricMetadataPort metricMetadataPort(
            @Qualifier("clickHouseDataSource") DataSource source,
            ObjectMapper json, MetricLabelMetadata memory) {
        return new JdbcMetricMetadataPort(source, json, memory);
    }
    @Bean
    public SamplePort samplePort(SampleService sampleService) {
        return SamplePort.of(sampleService);
    }
    @Bean
    public ClickHouseReportQuery clickHouseReportQuery(
            @Qualifier("clickHouseDataSource")
            DataSource dataSource,
            Supplier<ZoneId> platformZone) {
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
            HourlyReportStore store,
            TimeBucketResolver buckets,
            Supplier<ZoneId> platformZone,
            MetricLabelMetadata metadata) {
        return new ReportDataPortRouter(new ClickHouseReportDataPort(query, buckets, platformZone),
                new HourlyReportDataPort(store, buckets, platformZone, metadata), platformZone);
    }

}
