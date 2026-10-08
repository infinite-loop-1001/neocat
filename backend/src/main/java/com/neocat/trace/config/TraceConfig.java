package com.neocat.trace.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TraceConfig {
    @ApolloStaticValue("${neocat.trace.retention-days}")
    public static volatile int RETENTION_DAYS;

    @ApolloStaticValue("${neocat.trace.sample-rate}")
    public static volatile double SAMPLE_RATE;

    @ApolloStaticValue("${neocat.trace.sample-rows}")
    public static volatile int SAMPLE_ROWS;
}
