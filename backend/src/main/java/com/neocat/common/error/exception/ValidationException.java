package com.neocat.common.error.exception;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.NeocatException;

@org.springframework.modulith.NamedInterface("error")

public class ValidationException extends NeocatException {
    public ValidationException(ErrorCode code, Object... parameters) {
        super(code, parameters);
    }
}
