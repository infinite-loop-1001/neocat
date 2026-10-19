package com.neocat.common.http.error;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.NeocatException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.NamedInterface;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 统一异常处理（技术方案 03-api-contract.md §1.1）。
 *
 * <p>把 {@link NeocatException} 映射为「HTTP 状态 + code + message」，
 * 状态码由 {@link ErrorCodeMapping} 集中决定，控制器不自行选择状态码。
 */
@Slf4j
@RestControllerAdvice
@NamedInterface("http")
public class ApiExceptionHandler {

    @ExceptionHandler(NeocatException.class)
    public ResponseEntity<ApiError> handleBusiness(NeocatException error) {
        int status = ErrorCodeMapping.statusOf(error.code());
        if (status >= 500) {
            log.error("HTTP business failure: status={}, code={}", status, error.code().code(), error);
        } else {
            log.warn("HTTP business rejection: status={}, code={}, exception={}",
                    status, error.code().code(), ExceptionUtils.getStackTrace(error));
        }
        return ResponseEntity.status(status)
                .body(ApiError.of(error.code(), error.getMessage()));
    }
    /** 框架参数与状态错误归为 400，不泄露堆栈。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> handleBadRequest(RuntimeException error) {
        log.warn("HTTP bad request: status=400, code={}, exception={}",
                ErrorCode.INVALID_PARAM.code(), ExceptionUtils.getStackTrace(error), error);
        return ResponseEntity.status(400)
                .body(ApiError.of(ErrorCode.INVALID_PARAM,
                        ErrorCode.INVALID_PARAM.message(StringUtils.defaultIfBlank(error.getMessage(), "请求参数"))));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException error) {
        log.warn("HTTP resource not found: status=404, code={}, exception={}",
                ErrorCode.NOT_FOUND.code(), ExceptionUtils.getStackTrace(error));
        return ResponseEntity.status(404)
                .body(ApiError.of(ErrorCode.NOT_FOUND,
                        ErrorCode.NOT_FOUND.message(StringUtils.defaultIfBlank(error.getMessage(), "资源"))));
    }
    /** 兜底：服务端保留异常堆栈，不把内部细节返回给调用方。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception error) {
        log.error("Unexpected HTTP failure: status=500, code={}", ErrorCode.INTERNAL_ERROR.code(), error);
        return ResponseEntity.status(500)
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message()));
    }
}
