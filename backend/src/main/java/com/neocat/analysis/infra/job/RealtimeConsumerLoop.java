package com.neocat.analysis.infra.job;

import com.neocat.analysis.domain.analyzer.RealtimeConsumer;
import com.neocat.ingest.config.IngestConfig;
import com.neocat.common.queue.BoundedDropQueue;
import com.neocat.ingest.domain.tree.MessageTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 有界队列的消费循环（技术方案 01-architecture.md §5.1、§6）。
 *
 * <p>职责：持续从队列取树并交给 {@link RealtimeConsumer} 扇出到各分析域。
 * 没有这一步，入队等于丢弃 —— 这是 CAT 的 {@code RealtimeConsumer} 在 NeoCat 中的对应物。
 *
 * <p>设计要点：
 * <ul>
 *   <li>按 {@code consumer-threads} 启动多个守护线程，每个线程独立 drain，吞吐随线程数扩展；</li>
 *   <li>接收端**不等待**消费：本循环与 HTTP 接收线程完全解耦（PRD 02 §8）；</li>
 *   <li>消费过程中任何异常都被吞掉并计数，绝不让循环退出：一旦退出，整个分析链路静默停摆；</li>
 *   <li>停机时（{@link SmartLifecycle}）先停循环再返回，避免关闭期间持续消费。</li>
 * </ul>
 */
@Component
@DependsOn("ingestConfig")
public class RealtimeConsumerLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RealtimeConsumerLoop.class);

    private final BoundedDropQueue<MessageTree> queue;

    private final RealtimeConsumer consumer;

    // 实际运行中的 worker 身份，不保存 consumerThreads 的配置副本。
    private final Set<Integer> activeWorkers;

    private final ExecutorService workers;

    private final AtomicLong consumed;

    private final AtomicLong failed;

    private volatile boolean running;

    public RealtimeConsumerLoop(BoundedDropQueue<MessageTree> queue, RealtimeConsumer consumer) {
        this.queue = queue;
        this.consumer = consumer;
        this.activeWorkers = ConcurrentHashMap.newKeySet();
        this.workers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "neocat-consumer");
            thread.setDaemon(true);
            return thread;
        });
        this.consumed = new AtomicLong();
        this.failed = new AtomicLong();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        start();
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        maintainConsumers();
        log.info("RealtimeConsumer 已启动，消费线程数：{}，队列容量：{}", IngestConfig.CONSUMER_THREADS, queue.capacity());
    }
    /** 增加 worker 即启动；减少 worker 在当前批完成后自然退出，不中断在途树。 */
    @Scheduled(fixedDelay = 1000)
    public synchronized void maintainConsumers() {
        if (!running) return;
        for (int id = 0; id < Math.max(1, IngestConfig.CONSUMER_THREADS); id++) {
            if (activeWorkers.add(id)) {
                final int workerId = id;
                workers.submit(() -> {
                    try { drainLoop(workerId); } finally { activeWorkers.remove(workerId); }
                });
            }
        }
    }
    public int activeWorkerCount() { return activeWorkers.size(); }
    @Override
    public synchronized void stop() {
        running = false;
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    @Override
    public boolean isRunning() {
        return running;
    }
    @Override
    public int getPhase() {
        // 在容器开始接收请求之后启动，在关闭请求之前停止
        return Integer.MAX_VALUE - 100;
    }
    /** 消费循环：取批 → 逐棵扇出。 */
    private void drainLoop(int workerId) {
        while (running && workerId < Math.max(1, IngestConfig.CONSUMER_THREADS)) {
            try {
                List<MessageTree> batch = queue.pollBatch(Math.max(1, IngestConfig.BATCH_SIZE),
                        Math.max(1, IngestConfig.BATCH_TIMEOUT_MS));
                for (MessageTree tree : batch) {
                    consume(tree);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // 循环绝不因异常退出：退出意味着分析彻底停摆
                failed.incrementAndGet();
                log.warn("消费者循环出现异常，已跳过继续运行", t);
            }
        }
    }
    /** 单棵树的扇出；单域失败被 {@link RealtimeConsumer} 记录，不影响其他域。 */
    private void consume(MessageTree tree) {
        try {
            var result = consumer.consume(tree);
            consumed.incrementAndGet();
            if (result.anyFailed()) {
                failed.addAndGet(result.getFailures().size());
                log.warn("树 {} 的部分分析域失败：{}", tree.getMessageId(),
                        result.getFailures().stream().map(f -> f.getDomain() + ":" + f.getReason()).toList());
            }
        } catch (Throwable t) {
            failed.incrementAndGet();
            log.warn("树 {} 扇出失败", tree.getMessageId(), t);
        }
    }
    /** 已消费树数（观测用）。 */
    public long consumedCount() {
        return consumed.get();
    }
    /** 失败域次数（观测用）。 */
    public long failedCount() {
        return failed.get();
    }
}
