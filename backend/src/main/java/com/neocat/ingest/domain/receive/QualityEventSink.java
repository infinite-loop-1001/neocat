package com.neocat.ingest.domain.receive;

import java.time.Instant;

/**
 * 数据质量事件发布（PRD 02 §7、§8；技术方案 03 §4 / 06 §7）。
 *
 * <p>用于记录 EXPIRED / ID_CONFLICT / QUEUE_FULL / MALFORMED / DOMAIN_FAILURE。
 */
@org.springframework.modulith.NamedInterface("tree")
public interface QualityEventSink {

    void record(QualityType type, String serviceName, String messageId, String detail, Instant at);

    static QualityEventSink noop() {
        return (type, serviceName, messageId, detail, at) -> {
        };
    }
}
