package com.neocat.common.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

// rules: 特定领域的配置需要放在各自领域下, 而不是放到公共包里
@Configuration(proxyBeanMethods = false)
public class AlertConfig {

    @ApolloStaticValue("${neocat.alert.evaluate-delay-seconds}")
    public static volatile int EVALUATE_DELAY_SECONDS;

    @ApolloStaticValue("${neocat.alert.notify-timeout-ms}")
    public static volatile int NOTIFY_TIMEOUT_MS;

    @ApolloStaticValue("${neocat.alert.dedup-per-minute}")
    public static volatile boolean DEDUP_PER_MINUTE;
}
