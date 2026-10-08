package com.neocat.common.http.error;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.NeocatException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;
import java.util.NoSuchElementException;
import org.springframework.modulith.NamedInterface;

/**
 * 统一异常处理（技术方案 03-api-contract.md §1.1）。
 *
 * <p>把 {@link NeocatException} 映射为「HTTP 状态 + code + message」，
 * 状态码由 {@link ErrorCodeMapping} 集中决定，控制器不自行选择状态码。
 */
@RestControllerAdvice
@NamedInterface("http")
public class ApiExceptionHandler {

    @ExceptionHandler(NeocatException.class)
    public ResponseEntity<ApiError> handleBusiness(NeocatException error) {
        return ResponseEntity.status(ErrorCodeMapping.statusOf(error.code()))
                .body(ApiError.of(error.code(), error.getMessage()));
    }
    /** 框架参数与状态错误归为 400，不泄露堆栈。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> handleBadRequest(RuntimeException error) {
        return ResponseEntity.status(400)
                .body(ApiError.of(ErrorCode.INVALID_PARAM,
                        ErrorCode.INVALID_PARAM.message(Objects.isNull(error.getMessage()) ? "请求参数" : error.getMessage())));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException error) {
        return ResponseEntity.status(404)
                .body(ApiError.of(ErrorCode.NOT_FOUND,
                        ErrorCode.NOT_FOUND.message(Objects.isNull(error.getMessage()) ? "资源" : error.getMessage())));
    }
    /** 兜底：不把内部细节返回给调用方。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception error) {
        return ResponseEntity.status(500)
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message()));
    }
}
