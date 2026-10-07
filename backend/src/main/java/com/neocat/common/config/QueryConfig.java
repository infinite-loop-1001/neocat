package com.neocat.common.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QueryConfig {
    @ApolloStaticValue("${neocat.query.max-buckets}")
    public static volatile int MAX_BUCKETS;

    @ApolloStaticValue("${neocat.query.max-instances-topn}")
    public static volatile int MAX_INSTANCES_TOP_N;
}
