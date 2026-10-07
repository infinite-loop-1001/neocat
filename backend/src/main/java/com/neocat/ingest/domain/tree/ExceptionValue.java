package com.neocat.ingest.domain.tree;

/**
 * 异常载荷（PRD 03 §9）。
 *
 * <p>{@code exceptionName} 是 Problem 异常分类的聚合键；
 * {@code exceptionMessage} 与 {@code stackTrace} 只保留在取样与原始树中，不参与聚合。
 */
@org.springframework.modulith.NamedInterface("tree")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class ExceptionValue {
    private final String exceptionName;

    private final String exceptionMessage;

    private final String stackTrace;

    public ExceptionValue(String exceptionName, String exceptionMessage, String stackTrace) {
        this.exceptionName = exceptionName;
        this.exceptionMessage = exceptionMessage;
        this.stackTrace = stackTrace;
    }

}
