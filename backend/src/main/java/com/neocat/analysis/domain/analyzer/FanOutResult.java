package com.neocat.analysis.domain.analyzer;

import java.util.List;

/**
 * 一次扇出的结果（PRD 02 §9：记录失败域而非整体失败）。
 *
 * @param succeededDomains 成功处理的域
 * @param failures         失败域与原因
 */
@org.springframework.modulith.NamedInterface("analysis")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class FanOutResult {
    private final List<String> succeededDomains;

    private final List<DomainFailure> failures;

    public FanOutResult(List<String> succeededDomains, List<DomainFailure> failures) {
        this.succeededDomains = succeededDomains;
        this.failures = failures;
    }

    /**
     * @param domain  失败的分析域
     * @param reason  失败原因（异常消息）
     * @param messageId 对应的 MessageTree ID
     */
    @org.springframework.modulith.NamedInterface("analysis")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static class DomainFailure {
        private final String domain;

        private final String messageId;

        private final String reason;

        public DomainFailure(String domain, String messageId, String reason) {
            this.domain = domain;
            this.messageId = messageId;
            this.reason = reason;
        }

    }
    public boolean anyFailed() {
        return !failures.isEmpty();
    }
}

