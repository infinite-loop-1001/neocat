package com.neocat.common.queue.impl;

import com.neocat.common.queue.BoundedDropQueue;
import com.neocat.common.queue.QueueFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;

/**
 * 基于 {@link ArrayBlockingQueue} 的有界丢队列。
 *
 * <p>{@link #offer} 使用非阻塞写入：满即丢弃并计数，绝不阻塞上报线程（PRD 02 §8）。
 */
public class BoundedDropQueueFactory implements QueueFactory {

    @Override
    public <T> BoundedDropQueue<T> create(int capacity) {
        return new ArrayBlockingQueueAdapter<>(Math.max(1, capacity));
    }

    static class ArrayBlockingQueueAdapter<T> implements BoundedDropQueue<T> {

        private final ArrayBlockingQueue<T> queue;

        private final AtomicLong dropped;

        ArrayBlockingQueueAdapter(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.dropped = new AtomicLong();
        }

        @Override
        public boolean offer(T item) {
            if (queue.offer(item)) {
                return true;
            }
            dropped.incrementAndGet();
            return false;
        }

        @Override
        public List<T> pollBatch(int maxItems, long timeoutMillis) throws InterruptedException {
            List<T> batch = new ArrayList<>(Math.max(1, maxItems));
            T first = timeoutMillis <= 0
                    ? queue.poll()
                    : queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (Objects.isNull(first)) {
                return batch;
            }
            batch.add(first);
            queue.drainTo(batch, maxItems - 1);
            return batch;
        }

        @Override
        public long droppedCount() {
            return dropped.get();
        }

        @Override
        public int size() {
            return queue.size();
        }

        @Override
        public int capacity() {
            return queue.remainingCapacity() + queue.size();
        }

        @Override
        public double watermark() {
            int cap = capacity();
            return cap == 0 ? 0.0d : (double) queue.size() / cap;
        }
    }
}
