package com.neocat.common.http.error;

import com.neocat.common.error.ErrorCode;

import java.util.Map;

/**
 * 错误码 → HTTP 状态映射（技术方案 03-api-contract.md §1.1）。
 *
 * <p>集中在一处映射，避免各控制器各自决定状态码而产生不一致；
 * 测试校验新增错误码必须显式归类。
 */
@org.springframework.modulith.NamedInterface("http")
public class ErrorCodeMapping {

    private static final Map<ErrorCode, Integer> STATUS = Map.ofEntries(
            // 400 请求有误
            Map.entry(ErrorCode.BAD_REQUEST, 400),
            Map.entry(ErrorCode.INVALID_PARAM, 400),
            Map.entry(ErrorCode.UNIT_MISMATCH, 400),
            Map.entry(ErrorCode.FORMULA_INVALID, 400),
            Map.entry(ErrorCode.PASSWORD_TOO_SHORT, 400),
            Map.entry(ErrorCode.PASSWORD_UNCHANGED, 400),
            Map.entry(ErrorCode.UNSUPPORTED_EXPRESSION, 400),
            Map.entry(ErrorCode.UNSUPPORTED_VERSION, 400),
            Map.entry(ErrorCode.MALFORMED_TREE, 400),
            Map.entry(ErrorCode.TREE_TOO_LARGE, 400),
            Map.entry(ErrorCode.BATCH_TOO_LARGE, 400),
            Map.entry(ErrorCode.NOT_INITIALIZED, 400),
            Map.entry(ErrorCode.CARD_NOT_IN_DASHBOARD, 400),
            Map.entry(ErrorCode.CARD_TARGET_REQUIRED, 400),
            Map.entry(ErrorCode.ALERT_ORG_REQUIRED, 400),

            // 401 未认证
            Map.entry(ErrorCode.UNAUTHENTICATED, 401),
            Map.entry(ErrorCode.BAD_CREDENTIALS, 401),

            // 403 无权限 / 需先改密
            Map.entry(ErrorCode.FORBIDDEN, 403),
            Map.entry(ErrorCode.NOT_ORG_MEMBER, 403),
            Map.entry(ErrorCode.INVALID_RECIPIENT, 403),
            Map.entry(ErrorCode.CHANNEL_UNAVAILABLE, 403),
            Map.entry(ErrorCode.CANNOT_MODIFY_SELF, 403),
            Map.entry(ErrorCode.PASSWORD_CHANGE_REQUIRED, 403),

            // 404 不存在
            Map.entry(ErrorCode.NOT_FOUND, 404),
            Map.entry(ErrorCode.USER_NOT_FOUND, 404),
            Map.entry(ErrorCode.ORG_NOT_FOUND, 404),
            Map.entry(ErrorCode.PARENT_ORG_NOT_FOUND, 404),
            Map.entry(ErrorCode.DASHBOARD_NOT_FOUND, 404),
            Map.entry(ErrorCode.CARD_NOT_FOUND, 404),
            Map.entry(ErrorCode.TRACE_NOT_FOUND, 404),

            // 409 冲突
            Map.entry(ErrorCode.USER_EXISTS, 409),
            Map.entry(ErrorCode.HAS_CHILDREN, 409),
            Map.entry(ErrorCode.NAME_DUPLICATED, 409),
            Map.entry(ErrorCode.ID_CONFLICT, 409),
            Map.entry(ErrorCode.NOT_LEAF, 409),
            Map.entry(ErrorCode.ALREADY_INITIALIZED, 409),
            Map.entry(ErrorCode.CONFIRM_NAME_MISMATCH, 409),

            // 410 已过期
            Map.entry(ErrorCode.TRACE_EXPIRED, 410),

            // 422 语义拒绝
            Map.entry(ErrorCode.LEAF_HAS_RESOURCES, 422),
            Map.entry(ErrorCode.TREE_EXPIRED, 422),
            Map.entry(ErrorCode.TARGET_NOT_REFERENCED, 422),
            Map.entry(ErrorCode.TIMEZONE_IMMUTABLE, 422),
            Map.entry(ErrorCode.CARD_EVALUATION_UNAVAILABLE, 422),

            // 500 内部错误
            Map.entry(ErrorCode.INTERNAL_ERROR, 500));

    private ErrorCodeMapping() {
    }
    public static int statusOf(ErrorCode code) {
        if (code == null) {
            return 500;
        }
        Integer status = STATUS.get(code);
        if (status == null) {
            throw new IllegalStateException("未映射错误码：" + code);
        }
        return status;
    }
    /** 校验映射表覆盖全部错误码（供单测使用）。 */
    public static boolean covers(ErrorCode code) {
        return STATUS.containsKey(code);
    }
}
