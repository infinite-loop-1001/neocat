package com.neocat.ingest.config;

import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.context.annotation.Configuration;

/** 上报接收与消费参数。只能在使用点直接读取，禁止保存配置副本。 */
@Configuration(proxyBeanMethods = false)
public class IngestConfig {
    @ApolloStaticValue("${neocat.ingest.queue.capacity}")
    public static volatile int QUEUE_CAPACITY;

    @ApolloStaticValue("${neocat.ingest.consumer-threads}")
    public static volatile int CONSUMER_THREADS;

    @ApolloStaticValue("${neocat.ingest.batch-size}")
    public static volatile int BATCH_SIZE;

    @ApolloStaticValue("${neocat.ingest.batch-timeout-ms}")
    public static volatile int BATCH_TIMEOUT_MS;

    @ApolloStaticValue("${neocat.ingest.max-trees-per-batch}")
    public static volatile int MAX_TREES_PER_BATCH;

    @ApolloStaticValue("${neocat.ingest.max-batch-bytes}")
    public static volatile int MAX_BATCH_BYTES;

    @ApolloStaticValue("${neocat.ingest.max-nodes-per-tree}")
    public static volatile int MAX_NODES_PER_TREE;

    @ApolloStaticValue("${neocat.ingest.idempotency-window-minutes}")
    public static volatile int IDEMPOTENCY_WINDOW_MINUTES;

    @ApolloStaticValue("${neocat.ingest.auth-token}")
    public static volatile String AUTH_TOKEN;

    @ApolloStaticValue("${neocat.ingest.accept-late-hours}")
    public static volatile int ACCEPT_LATE_HOURS;
}
