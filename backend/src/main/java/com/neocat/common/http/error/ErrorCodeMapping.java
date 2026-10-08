package com.neocat.common.http.error;

import com.neocat.common.error.ErrorCode;

import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.modulith.NamedInterface;

/**
 * 错误码 → HTTP 状态映射（技术方案 03-api-contract.md §1.1）。
 *
 * <p>集中在一处映射，避免各控制器各自决定状态码而产生不一致；
 * 测试校验新增错误码必须显式归类。
 */
@NamedInterface("http")
public class ErrorCodeMapping {

    private static final Map<ErrorCode, Integer> STATUS = Map.ofEntries(
            // 400 请求有误
            Map.entry(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.INVALID_PARAM, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.UNIT_MISMATCH, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.FORMULA_INVALID, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.PASSWORD_TOO_SHORT, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.PASSWORD_UNCHANGED, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.UNSUPPORTED_EXPRESSION, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.UNSUPPORTED_VERSION, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.MALFORMED_TREE, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.TREE_TOO_LARGE, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.BATCH_TOO_LARGE, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.NOT_INITIALIZED, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.CARD_NOT_IN_DASHBOARD, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.CARD_TARGET_REQUIRED, HttpStatus.BAD_REQUEST.value()),
            Map.entry(ErrorCode.ALERT_ORG_REQUIRED, HttpStatus.BAD_REQUEST.value()),

            // 401 未认证
            Map.entry(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED.value()),
            Map.entry(ErrorCode.BAD_CREDENTIALS, HttpStatus.UNAUTHORIZED.value()),

            // 403 无权限 / 需先改密
            Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN.value()),
            Map.entry(ErrorCode.NOT_ORG_MEMBER, HttpStatus.FORBIDDEN.value()),
            Map.entry(ErrorCode.INVALID_RECIPIENT, HttpStatus.FORBIDDEN.value()),
            Map.entry(ErrorCode.CHANNEL_UNAVAILABLE, HttpStatus.FORBIDDEN.value()),
            Map.entry(ErrorCode.CANNOT_MODIFY_SELF, HttpStatus.FORBIDDEN.value()),
            Map.entry(ErrorCode.PASSWORD_CHANGE_REQUIRED, HttpStatus.FORBIDDEN.value()),

            // 404 不存在
            Map.entry(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND.value()),
            Map.entry(ErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND.value()),
            Map.entry(ErrorCode.ORG_NOT_FOUND, HttpStatus.NOT_FOUND.value()),
            Map.entry(ErrorCode.PARENT_ORG_NOT_FOUND, HttpStatus.NOT_FOUND.value()),
            Map.entry(ErrorCode.DASHBOARD_NOT_FOUND, HttpStatus.NOT_FOUND.value()),
            Map.entry(ErrorCode.CARD_NOT_FOUND, HttpStatus.NOT_FOUND.value()),
            Map.entry(ErrorCode.TRACE_NOT_FOUND, HttpStatus.NOT_FOUND.value()),

            // 409 冲突
            Map.entry(ErrorCode.USER_EXISTS, HttpStatus.CONFLICT.value()),
            Map.entry(ErrorCode.HAS_CHILDREN, HttpStatus.CONFLICT.value()),
            Map.entry(ErrorCode.NAME_DUPLICATED, HttpStatus.CONFLICT.value()),
            Map.entry(ErrorCode.ID_CONFLICT, HttpStatus.CONFLICT.value()),
            Map.entry(ErrorCode.NOT_LEAF, HttpStatus.CONFLICT.value()),
            Map.entry(ErrorCode.ALREADY_INITIALIZED, HttpStatus.CONFLICT.value()),
            Map.entry(ErrorCode.CONFIRM_NAME_MISMATCH, HttpStatus.CONFLICT.value()),

            // 410 已过期
            Map.entry(ErrorCode.TRACE_EXPIRED, HttpStatus.GONE.value()),

            // 422 语义拒绝
            Map.entry(ErrorCode.LEAF_HAS_RESOURCES, HttpStatus.UNPROCESSABLE_ENTITY.value()),
            Map.entry(ErrorCode.TREE_EXPIRED, HttpStatus.UNPROCESSABLE_ENTITY.value()),
            Map.entry(ErrorCode.TARGET_NOT_REFERENCED, HttpStatus.UNPROCESSABLE_ENTITY.value()),
            Map.entry(ErrorCode.TIMEZONE_IMMUTABLE, HttpStatus.UNPROCESSABLE_ENTITY.value()),
            Map.entry(ErrorCode.CARD_EVALUATION_UNAVAILABLE, HttpStatus.UNPROCESSABLE_ENTITY.value()),

            // 500 内部错误
            Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.value()));

    private ErrorCodeMapping() {
    }
    public static int statusOf(ErrorCode code) {
        if (Objects.isNull(code)) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
        Integer status = STATUS.get(code);
        if (Objects.isNull(status)) {
            throw new IllegalStateException("未映射错误码：" + code);
        }
        return status;
    }
    /** 校验映射表覆盖全部错误码（供单测使用）。 */
    public static boolean covers(ErrorCode code) {
        return STATUS.containsKey(code);
    }
}