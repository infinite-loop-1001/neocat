package com.neocat.common.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReportConfig {
    @ApolloStaticValue("${neocat.report.exact-values.max}")
    public static volatile int EXACT_VALUES_MAX;

    @ApolloStaticValue("${neocat.report.distribution-buckets}")
    public static volatile int DISTRIBUTION_BUCKETS;

    @ApolloStaticValue("${neocat.report.minute.retention-days}")
    public static volatile int MINUTE_RETENTION_DAYS;

    @ApolloStaticValue("${neocat.report.hour.retention-days}")
    public static volatile int HOUR_RETENTION_DAYS;

    @ApolloStaticValue("${neocat.report.long-term.retention-months}")
    public static volatile int LONG_TERM_RETENTION_MONTHS;

    @ApolloStaticValue("${neocat.report.minute-flush-delay-seconds}")
    public static volatile int MINUTE_FLUSH_DELAY_SECONDS;

    @ApolloStaticValue("${neocat.report.bucket-cache-seconds}")
    public static volatile int BUCKET_CACHE_SECONDS;
}
