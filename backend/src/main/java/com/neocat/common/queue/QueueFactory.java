package com.neocat.common.queue;

/**
 * 固定容量队列工厂（用于独立队列及测试）。动态上报队列由 ingest 装配。
 */
public interface QueueFactory {

    <T> BoundedDropQueue<T> create(int capacity);
}
