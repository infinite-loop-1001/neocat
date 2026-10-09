package com.neocat.trace.domain.sample;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

import java.util.Objects;

/**
 * 一条调用取样（PRD 03 §11）。
 *
 * <p>每个 Transaction/Event/Problem 行按事件时间倒序展示最近 30 条调用取样，多行展示。
 * {@code traceAvailable} 表示原始树是否仍在留存期内、可下钻。
 */
@NamedInterface("trace")
@Getter
@EqualsAndHashCode
@ToString
public class Sample {
    private final String messageId;

    private final long timestamp;

    private final long durationMs;

    private final String status;

    private final String summary;

    private final boolean traceAvailable;

    public Sample(String messageId, long timestamp, long durationMs, String status, String summary, boolean traceAvailable) {
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
        this.status = status;
        this.summary = summary;
        this.traceAvailable = traceAvailable;
    }

    public boolean failed() {
        return !Objects.equals("0", status);
    }
}