package com.neocat.common.error.exception;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.NeocatException;
import org.springframework.modulith.NamedInterface;

@NamedInterface("error")

public class ConflictException extends NeocatException {
    public ConflictException(ErrorCode code, Object... parameters) {
        super(code, parameters);
    }
}
