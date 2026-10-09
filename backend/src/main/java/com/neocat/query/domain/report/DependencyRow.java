package com.neocat.query.domain.report;

import java.math.BigDecimal;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 依赖列表行。
 */
@NamedInterface("query")
@Getter
@EqualsAndHashCode
@ToString
public class DependencyRow {
    private final String peerService;

    private final long calls;

    private final long failures;

    private final BigDecimal failureRate;

    private final BigDecimal avgDuration;

    private final BigDecimal tp99;

    private final String sampleMessageId;

    public DependencyRow(String peerService, long calls, long failures, BigDecimal failureRate, BigDecimal avgDuration, BigDecimal tp99, String sampleMessageId) {
        this.peerService = peerService;
        this.calls = calls;
        this.failures = failures;
        this.failureRate = failureRate;
        this.avgDuration = avgDuration;
        this.tp99 = tp99;
        this.sampleMessageId = sampleMessageId;
    }
}
