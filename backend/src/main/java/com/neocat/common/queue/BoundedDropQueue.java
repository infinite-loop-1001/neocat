package com.neocat.common.queue;
import java.util.List;

/**
 * 有界丢队列：接收侧只做 {@code offer}，永不阻塞业务。
 *
 * <p>契约（PRD 02 §8）：
 * <ul>
 *   <li>{@link #offer} 在队列已满时立即返回 {@code false}，不等待、不抛出。</li>
 *   <li>丢弃必须被计数，供容量观测与降级决策使用。</li>
 *   <li>消费者通过 {@link #pollBatch} 批量取走数据。</li>
 * </ul>
 */
public interface BoundedDropQueue<T> {

    /** 尝试入队；满则丢弃并返回 false。 */
    boolean offer(T item);

    /**
     * 批量取走最多 {@code maxItems} 条；队列为空时最多等待 {@code timeoutMillis}。
     *
     * @return 取到的条目，可能为空列表
     */
    List<T> pollBatch(int maxItems, long timeoutMillis) throws InterruptedException;

    long droppedCount();

    int size();

    int capacity();

    /** 当前队列水位占总容量的比例（0–1），用于观测。 */
    double watermark();
}
