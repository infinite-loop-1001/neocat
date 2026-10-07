package com.neocat.common.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AnalysisConfig {

    @ApolloStaticValue("${neocat.analysis.analyzer-timeout-ms}")
    public static volatile int ANALYZER_TIMEOUT_MS;

}
