package com.neocat.common.error.exception;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.NeocatException;
import org.springframework.modulith.NamedInterface;

@NamedInterface("error")

public class AuthenticationException extends NeocatException {
    public AuthenticationException(ErrorCode code, Object... parameters) {
        super(code, parameters);
    }
}
