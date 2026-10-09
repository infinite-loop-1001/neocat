package com.neocat.analysis.domain.analyzer;

import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 一次扇出的结果（PRD 02 §9：记录失败域而非整体失败）。
 *
 * @param succeededDomains 成功处理的域
 * @param failures         失败域与原因
 */
@NamedInterface("analysis")
@Getter
@EqualsAndHashCode
@ToString
public class FanOutResult {
    private final List<String> succeededDomains;

    private final List<DomainFailure> failures;

    public FanOutResult(List<String> succeededDomains, List<DomainFailure> failures) {
        this.succeededDomains = succeededDomains;
        this.failures = failures;
    }

    public boolean anyFailed() {
        return CollectionUtils.isNotEmpty(failures);
    }
}