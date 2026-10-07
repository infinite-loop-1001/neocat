package com.neocat.common.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MetricConfig {
    @ApolloStaticValue("${neocat.metric.top-n}")
    public static volatile int TOP_N;
}
