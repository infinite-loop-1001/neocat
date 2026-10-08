package com.neocat.common.error.exception;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.NeocatException;
import org.springframework.modulith.NamedInterface;

@NamedInterface("error")

public class AuthorizationException extends NeocatException {
    public AuthorizationException(ErrorCode code, Object... parameters) {
        super(code, parameters);
    }
}
