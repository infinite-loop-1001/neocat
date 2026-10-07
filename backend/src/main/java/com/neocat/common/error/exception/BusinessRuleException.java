package com.neocat.common.error.exception;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.NeocatException;

@org.springframework.modulith.NamedInterface("error")

public class BusinessRuleException extends NeocatException {
    public BusinessRuleException(ErrorCode code, Object... parameters) {
        super(code, parameters);
    }
}
