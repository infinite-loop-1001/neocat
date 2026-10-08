package com.neocat.alert.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AlertConfig {

    @ApolloStaticValue("${neocat.alert.evaluate-delay-seconds}")
    public static volatile int EVALUATE_DELAY_SECONDS;

    @ApolloStaticValue("${neocat.alert.notify-timeout-ms}")
    public static volatile int NOTIFY_TIMEOUT_MS;

    @ApolloStaticValue("${neocat.alert.dedup-per-minute}")
    public static volatile boolean DEDUP_PER_MINUTE;
}
