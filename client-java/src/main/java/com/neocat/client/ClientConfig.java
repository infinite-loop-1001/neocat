package com.neocat.client;

/**
 * SDK 运行参数（对应 Apollo 键 {@code neocat.ingest.*}，见技术方案 07 §1.2）。
 *
 * <p>所有方法都可被动态实现（例如委托 Apollo 客户端），
 * 因此 SDK 无需重启即可响应配置变化。
 */
public interface ClientConfig {

    /** 本地待发送队列容量。满则丢弃，绝不阻塞业务（PRD 02 §8）。 */
    int queueCapacity();

    /** 单批发送的树数上限。 */
    default int batchSize() {
        return 100;
    }

    /** 定时刷新间隔（毫秒）。 */
    default long flushIntervalMillis() {
        return 1000L;
    }

    /** 单次 HTTP 超时（毫秒）。 */
    default long sendTimeoutMillis() {
        return 3000L;
    }
}
