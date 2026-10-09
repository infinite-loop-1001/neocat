package com.neocat.trace.infra.clickhouse;

import java.time.Instant;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Trace 关系行（{@code nc_trace_relation}）。
 */
@Getter
@EqualsAndHashCode
@ToString
public class TraceRelationRow {
    private final String messageId;

    private final String rootMessageId;

    private final String parentMessageId;

    private final String service;

    private final String instance;

    private final Instant treeTimestamp;

    public TraceRelationRow(String messageId, String rootMessageId,
                            String parentMessageId, String service, String instance, Instant treeTimestamp) {
        this.messageId = messageId;
        this.rootMessageId = rootMessageId;
        this.parentMessageId = parentMessageId;
        this.service = service;
        this.instance = instance;
        this.treeTimestamp = treeTimestamp;
    }

}
