package com.neocat.client

/**
 * 测试替身：可动态调整的运行参数（模拟 Apollo 热更新）。
 */
class MutableClientConfig implements ClientConfig {

    private int capacity
    private int batch = 1000
    private long interval = 60_000L

    MutableClientConfig(int capacity) {
        this.capacity = capacity
    }

    void setBatchSize(int batch) {
        this.batch = batch
    }

    void setFlushInterval(long interval) {
        this.interval = interval
    }

    @Override
    int queueCapacity() {
        return capacity
    }

    @Override
    int batchSize() {
        return batch
    }

    @Override
    long flushIntervalMillis() {
        return interval
    }
}
