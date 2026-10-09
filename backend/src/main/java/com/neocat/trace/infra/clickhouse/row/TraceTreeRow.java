package com.neocat.trace.infra.clickhouse.row;

import java.time.Instant;

import com.neocat.trace.infra.clickhouse.TreePayloadCodec;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * 原始树行（{@code nc_raw_tree}）。
 *
 * <p>payload：序列化后的树内容；由 {@link TreePayloadCodec} 编解码。
 */
@Getter
@EqualsAndHashCode
@ToString
public class TraceTreeRow {
    private final String service;

    private final String instance;

    private final String messageId;

    private final String rootMessageId;

    private final String parentMessageId;

    private final Instant treeTimestamp;

    private final String fingerprint;

    private final String payload;

    public TraceTreeRow(String service, String instance, String messageId,
                        String rootMessageId, String parentMessageId,
                        Instant treeTimestamp, String fingerprint, String payload) {
        this.service = service;
        this.instance = instance;
        this.messageId = messageId;
        this.rootMessageId = rootMessageId;
        this.parentMessageId = parentMessageId;
        this.treeTimestamp = treeTimestamp;
        this.fingerprint = fingerprint;
        this.payload = payload;
    }

}
