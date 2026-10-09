package com.neocat.common.queue.impl;

import com.neocat.common.queue.BoundedDropQueue;
import com.neocat.common.queue.QueueFactory;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * 基于 {@link ArrayBlockingQueue} 的有界丢队列。
 *
 * <p>{@link #offer} 使用非阻塞写入：满即丢弃并计数，绝不阻塞上报线程（PRD 02 §8）。
 */
public class BoundedDropQueueFactory implements QueueFactory {

    @Override
    public <T> BoundedDropQueue<T> create(int capacity) {
        return new ArrayBoundedDropQueue<>(Math.max(1, capacity));
    }

}
