package com.neocat.common.http.error;

import com.neocat.common.error.ErrorCode;

/**
 * API 错误响应体（技术方案 03-api-contract.md §1.1）。
 *
 * @param code    稳定错误码
 * @param message 面向用户的可读消息
 */
@org.springframework.modulith.NamedInterface("http")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class ApiError {
    private final int code;

    private final String message;

    public ApiError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.code(), message);
    }
}
