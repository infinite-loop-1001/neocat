package com.neocat.ingest.domain.validation;

import com.neocat.common.error.ErrorCode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 校验结果（技术方案 04 §4）。
 */
@NamedInterface("tree")
@Getter
@EqualsAndHashCode
@ToString
public class ValidationOutcome {
    private final boolean valid;

    private final String code;

    private final String message;

    public ValidationOutcome(boolean valid, String code, String message) {
        this.valid = valid;
        this.code = code;
        this.message = message;
    }

    public static final String OK = "OK";

    public static final String UNSUPPORTED_VERSION = ErrorCode.UNSUPPORTED_VERSION.name();

    public static final String BATCH_TOO_LARGE = ErrorCode.BATCH_TOO_LARGE.name();

    public static final String TREE_TOO_LARGE = ErrorCode.TREE_TOO_LARGE.name();

    public static final String MALFORMED_TREE = ErrorCode.MALFORMED_TREE.name();

    public static ValidationOutcome ok() {
        return new ValidationOutcome(true, OK, "");
    }
    public static ValidationOutcome reject(String code, String message) {
        return new ValidationOutcome(false, code, message);
    }
}