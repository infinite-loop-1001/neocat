package com.neocat.analysis.domain.analyzer;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * <p>domain：失败的分析域。
 * <p>reason：失败原因（异常消息）。
 * <p>messageId：对应的 MessageTree ID。
 */
@NamedInterface("analysis")
@Getter
@EqualsAndHashCode
@ToString
public class DomainFailure {
    private final String domain;

    private final String messageId;

    private final String reason;

    public DomainFailure(String domain, String messageId, String reason) {
        this.domain = domain;
        this.messageId = messageId;
        this.reason = reason;
    }

}
