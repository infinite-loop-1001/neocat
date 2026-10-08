package com.neocat.ingest.infra;

import com.neocat.ingest.config.IngestConfig;
import com.neocat.common.queue.BoundedDropQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.collections4.CollectionUtils;

/** 动态上报队列：容量检查直接读配置，缩容不丢弃已经接收的数据。 */
public class IngestDropQueue<T> implements BoundedDropQueue<T> {
    private final ReentrantLock lock;

    private final Condition available;

    private final ArrayDeque<T> items;

    private final AtomicLong dropped;

    public IngestDropQueue() {
        this.lock = new ReentrantLock();
        this.available = lock.newCondition();
        this.items = new ArrayDeque<>();
        this.dropped = new AtomicLong();
    }

    @Override
    public boolean offer(T item) {
        Objects.requireNonNull(item);
        lock.lock();
        try {
            if (items.size() >= Math.max(1, IngestConfig.QUEUE_CAPACITY)) {
                dropped.incrementAndGet();
                return false;
            }
            items.addLast(item);
            available.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<T> pollBatch(int maxItems, long timeoutMillis) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            while (CollectionUtils.isEmpty(items) && remaining > 0) remaining = available.awaitNanos(remaining);
            List<T> batch = new ArrayList<>();
            while (CollectionUtils.isNotEmpty(items) && batch.size() < Math.max(1, maxItems)) batch.add(items.removeFirst());
            return batch;
        } finally {
            lock.unlock();
        }
    }

    @Override public long droppedCount() { return dropped.get(); }
    @Override public int capacity() { return Math.max(1, IngestConfig.QUEUE_CAPACITY); }
    @Override public int size() {
        lock.lock();
        try { return items.size(); } finally { lock.unlock(); }
    }
    @Override public double watermark() { return Math.min(1.0, (double) size() / capacity()); }
}
