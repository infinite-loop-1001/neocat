package com.neocat.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.Maps;
import com.neocat.protocol.ingest.v1.ExceptionInfo;
import com.neocat.protocol.ingest.v1.IngestRequest;
import com.neocat.protocol.ingest.v1.Kind;
import com.neocat.protocol.ingest.v1.MessageTree;
import com.neocat.protocol.ingest.v1.MetricValue;
import com.neocat.protocol.ingest.v1.Node;
import com.neocat.protocol.ingest.v1.RemoteCall;

/**
 * NeoCat 客户端 SDK（对应技术方案 04-ingest-protocol.md §8.2）。
 *
 * <p>职责：
 * <ol>
 *   <li>把业务侧记录组装为 {@link MessageTree}；</li>
 *   <li>以有界队列缓冲，满则丢弃并计数，**绝不阻塞业务线程**（PRD 02 §8）；</li>
 *   <li>按批量阈值与定时双触发序列化为 {@link IngestRequest} 并交给 {@link MessageSender}；</li>
 *   <li>吞掉所有发送异常，不向业务传播。</li>
 * </ol>
 *
 * <p>Trace 关联：{@link #attachTrace} 设置后续记录的 root/parent，无上游时
 * {@code rootMessageId} 等于自身 {@code messageId}（PRD 02 §6.2）。
 */
public final class NeoCat {

    private final String serviceName;

    private final String instanceId;

    private final MessageSender sender;

    private final ClientConfig config;

    private final ArrayBlockingQueue<MessageTree> buffer;

    private final AtomicLong dropped;

    private final AtomicInteger sentBatches;

    /** 正在进行中的发送数：用于让 {@link #awaitFlush} 区分「队列已空」与「确实已发出」。 */
    private final AtomicInteger inFlight;

    private final CountDownLatch stopped;

    private final AtomicBoolean shutdown;

    private volatile String traceRoot;

    private volatile String traceParent;

    private final Thread flusher;

    private NeoCat(ClientConfig config, String serviceName, String instanceId,
                   String endpoint, MessageSender sender) {
        this.config = config;
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.sender = sender;
        this.dropped = new AtomicLong();
        this.sentBatches = new AtomicInteger();
        this.inFlight = new AtomicInteger();
        this.stopped = new CountDownLatch(1);
        this.shutdown = new AtomicBoolean(false);
        this.traceRoot = "";
        this.traceParent = "";
        this.buffer = new ArrayBlockingQueue<>(Math.max(1, config.queueCapacity()));
        this.flusher = new Thread(this::run, "neocat-flush");
        this.flusher.setDaemon(true);
        this.flusher.start();
    }

    public static NeoCat create(ClientConfig config, String serviceName, String instanceId,
                                String endpoint, MessageSender sender) {
        return new NeoCat(config, serviceName, instanceId, endpoint, sender);
    }

    // ── 记录 API ─────────────────────────────────────────────

    public Transaction newTransaction(String type, String name) {
        return new Transaction(this, type, name);
    }

    public RemoteCallHandle newRemoteCall(String downstreamService, String callType, String name) {
        return new RemoteCallHandle(this, downstreamService, callType, name);
    }

    public void logEvent(String category, String name, String status) {
        Node node = baseNode(Kind.EVENT, category, name, status)
                .setDurationMs(0L)          // Event 无耗时语义（PRD 03 §8）
                .build();
        enqueue(node);
    }

    public void logMetric(String name, double value, Map<String, String> labels) {
        Node node = baseNode(Kind.METRIC, name, name, "0")
                .setMetric(MetricValue.newBuilder()
                        .setName(name)
                        .setValue(value)
                        .putAllLabels(Objects.isNull(labels) ? Maps.newHashMap() : labels)
                        .build())
                .build();
        enqueue(node);
    }

    public void logHeartbeat(Map<String, String> tags) {
        Map<String, String> t = Objects.isNull(tags) ? Maps.newHashMap() : tags;
        Node node = baseNode(Kind.HEARTBEAT, "jvm", "jvm", "0")
                .setHeartbeat(JvmHeartbeatSampler.payload(t))
                .build();
        enqueue(node);
    }

    /** Explicit sampling only: callers choose their scheduling policy. Never installs a timer. */
    public void logJvmHeartbeat() {
        try { logHeartbeat(JvmHeartbeatSampler.sample()); }
        catch (RuntimeException ignored) { /* Monitoring must not break the business call. */ }
    }

    /** 设置后续记录的 Trace 关联；传空字符串表示清除。 */
    public void attachTrace(String rootMessageId, String parentMessageId) {
        this.traceRoot = Objects.isNull(rootMessageId) ? "" : rootMessageId;
        this.traceParent = Objects.isNull(parentMessageId) ? "" : parentMessageId;
    }

    // ── 观测 ─────────────────────────────────────────────────

    public long dropped() {
        return dropped.get();
    }

    public int sentBatches() {
        return sentBatches.get();
    }

    /**
     * 阻塞直到**队列清空且进行中的发送已完成**，或超时。
     *
     * <p>必须同时看 {@code inFlight}：刷新线程是「先 drain、后 send」，
     * 因此仅判断 {@code buffer.isEmpty()} 会在 drain 与 send 之间提前返回，
     * 调用方随即断言已发送数据就会间歇性失败（负载下约 8%）。
     */
    public boolean awaitFlush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
        while (System.currentTimeMillis() < deadline) {
            if (buffer.isEmpty() && inFlight.get() == 0) {
                // 再等一个刷新周期，确保后台线程已把数据发出
                try {
                    Thread.sleep(Math.min(20L, Math.max(1L, config.flushIntervalMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (buffer.isEmpty() && inFlight.get() == 0) {
                    return true;
                }
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return buffer.isEmpty() && inFlight.get() == 0;
    }

    /**
     * 停机：尽力发送剩余数据，**超时后立即返回**。
     *
     * <p>实现方式：置停机标志并**中断刷新线程**使其从休眠中醒来完成最后一次发送，
     * 调用方只在有限时间内等待该线程。因此即使 {@link MessageSender} 挂起
     * （例如网络不可达），调用方也不会被拖住——这是「不得拖慢业务」在停机路径上的体现。
     */
    public void shutdown(long timeoutMillis) {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        stopped.countDown();
        flusher.interrupt();
        try {
            flusher.join(Math.max(0, timeoutMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 内部：组装与入队 ─────────────────────────────────────

    Node.Builder baseNode(Kind kind, String category, String name, String status) {
        return Node.newBuilder()
                .setNodeId(UUID.randomUUID().toString())
                .setKind(kind)
                .setCategory(Objects.isNull(category) ? "" : category)
                .setName(Objects.isNull(name) ? "" : name)
                .setStatus(Objects.isNull(status) ? Transaction.SUCCESS : status)
                .setTimestamp(System.currentTimeMillis());
    }

    /** 由 Transaction 完成时调用。 */
    void enqueueTransaction(String type, String name, String status, long durationMs,
                            Throwable exception) {
        Node.Builder builder = baseNode(Kind.TRANSACTION, type, name, status)
                .setDurationMs(Math.max(0L, durationMs));
        if (Objects.nonNull(exception)) {
            builder.setException(ExceptionInfo.newBuilder()
                    .setExceptionName(exception.getClass().getName())
                    .setExceptionMessage(Objects.isNull(exception.getMessage()) ? "" : exception.getMessage())
                    .setStackTrace(stackTraceOf(exception))
                    .build());
        }
        enqueue(builder.build());
    }

    /** 由 RemoteCall 完成时调用。 */
    void enqueueRemoteCall(String downstreamService, String callType, String name,
                           String status, long durationMs) {
        Node node = baseNode(Kind.REMOTE_CALL, callType, name, status)
                .setDurationMs(Math.max(0L, durationMs))
                .setRemoteCall(RemoteCall.newBuilder()
                        .setDownstreamService(downstreamService)
                        .setCallType(Objects.isNull(callType) ? "" : callType)
                        .setStatus(Objects.isNull(status) ? Transaction.SUCCESS : status)
                        .build())
                .build();
        enqueue(node);
    }

    private void enqueue(Node node) {
        if (shutdown.get()) {
            return;
        }
        MessageTree tree = assemble(node);
        if (!buffer.offer(tree)) {
            // 满则丢弃并计数：不等待、不阻塞业务（PRD 02 §8）
            dropped.incrementAndGet();
        }
    }

    private MessageTree assemble(Node node) {
        String messageId = serviceName + "-" + UUID.randomUUID();
        MessageTree.Builder builder = MessageTree.newBuilder()
                .setServiceName(serviceName)
                .setInstanceId(instanceId)
                .setMessageId(messageId)
                .setRootMessageId(Objects.isNull(traceRoot) || traceRoot.isBlank() ? messageId : traceRoot)
                .setParentMessageId(Objects.isNull(traceParent) ? "" : traceParent)
                .setTreeTimestamp(node.getTimestamp())
                .addNodes(node);
        return builder.build();
    }

    // ── 内部：后台刷新 ───────────────────────────────────────

    private void run() {
        while (!shutdown.get()) {
            try {
                flushOnce();
                Thread.sleep(Math.max(1L, config.flushIntervalMillis()));
            } catch (InterruptedException e) {
                // 被 shutdown 唤醒：跳出循环后做最后一次发送
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
                // 后台线程永不因异常退出
            }
        }
        // 停机时的最后发送在刷新线程内完成，调用方的等待受其超时参数约束
        try {
            flushRemaining();
        } catch (Throwable ignored) {
            // 尽力而为
        }
    }

    private void flushOnce() {
        int batchSize = Math.max(1, config.batchSize());
        inFlight.incrementAndGet();
        try {
            List<MessageTree> batch = new ArrayList<>(batchSize);
            buffer.drainTo(batch, batchSize);
            if (!batch.isEmpty()) {
                deliver(batch);
            } else if (buffer.size() >= 1) {
                List<MessageTree> all = new ArrayList<>();
                buffer.drainTo(all);
                deliver(all);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void flushRemaining() {
        inFlight.incrementAndGet();
        try {
            List<MessageTree> remaining = new ArrayList<>();
            buffer.drainTo(remaining);
            if (!remaining.isEmpty()) {
                deliver(remaining);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 序列化并发送；吞掉全部异常（发送失败不抛给业务）。 */
    private void deliver(List<MessageTree> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            IngestRequest request = IngestRequest.newBuilder()
                    .setProtocolVersion("1.0")
                    .addAllTrees(batch)
                    .build();
            sender.send(request.toByteArray());
            sentBatches.incrementAndGet();
        } catch (Throwable ignored) {
            // 不重试、不抛出：监控数据丢失可接受，拖慢业务不可接受
        }
    }

    private long parse(String raw) {
        if (Objects.isNull(raw) || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String stackTraceOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append("\tat ").append(element).append('\n');
        }
        return sb.toString();
    }

}



