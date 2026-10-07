package com.neocat.ingest.domain.idempotency;

import java.util.Optional;

/**
 * 窗口外的历史指纹兜底查询（PRD 02 §6.1）。
 *
 * <p>由 trace 模块基于 ClickHouse 的 {@code nc_raw_tree.fingerprint} 实现（7 天内精确）。
 * ingest 只依赖该抽象，不直接访问原始树存储。
 */
@FunctionalInterface
@org.springframework.modulith.NamedInterface("tree")
public interface HistoricalFingerprintLookup {

    @org.springframework.lang.Nullable
    String fingerprintOf(String messageId);

    static HistoricalFingerprintLookup empty() {
        return messageId -> null;
    }
}
