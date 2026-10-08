package com.neocat.query.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HeartbeatConfig {
    @ApolloStaticValue("${neocat.heartbeat.topn}")
    public static volatile int TOP_N;
}
