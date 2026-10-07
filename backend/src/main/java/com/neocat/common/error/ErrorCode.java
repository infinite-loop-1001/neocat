package com.neocat.common.error;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 稳定业务错误码。各业务域占用独立万位区间；HTTP 状态另见 ErrorCodeMapping。
 *
 * <p>错误码只描述「编号 + 消息模板」；错误属于哪一类由 {@link NeocatException} 的具体子类表达，
 * 避免同一分类信息在枚举与异常类中各存一份而需要同步。
 */
public enum ErrorCode {
    // 1xxxx 通用
    BAD_REQUEST(10001, "请求不合法"),
    INVALID_PARAM(10002, "参数不合法：{0}"),
    NOT_FOUND(10003, "资源不存在：{0}"),
    INTERNAL_ERROR(10004, "服务内部错误"),

    // 2xxxx 身份与权限
    UNAUTHENTICATED(20001, "未登录或会话已失效"),
    BAD_CREDENTIALS(20002, "用户名或密码错误"),
    FORBIDDEN(20003, "权限不足：{0}"),
    PASSWORD_CHANGE_REQUIRED(20004, "必须先修改初始密码"),
    USER_EXISTS(20005, "用户名已存在：{0}"),
    USER_NOT_FOUND(20006, "账号不存在：{0}"),
    PASSWORD_TOO_SHORT(20007, "密码至少 {0} 位"),
    PASSWORD_UNCHANGED(20008, "新密码不能与当前密码相同"),
    CANNOT_MODIFY_SELF(20009, "超级管理员不能修改自己的角色"),

    // 3xxxx 组织
    HAS_CHILDREN(30001, "非叶子节点存在子节点，禁止删除"),
    NAME_DUPLICATED(30002, "同一父节点下已存在同名组织：{0}"),
    LEAF_HAS_RESOURCES(30003, "该叶子组织已存在大盘或组织告警，不能新增子节点"),
    NOT_ORG_MEMBER(30004, "不是该组织的有效成员"),
    ORG_NOT_FOUND(30005, "组织节点不存在：{0}"),
    PARENT_ORG_NOT_FOUND(30006, "父节点不存在：{0}"),
    CONFIRM_NAME_MISMATCH(30007, "二次确认名称不匹配，删除已取消"),

    // 4xxxx 大盘/公式
    NOT_LEAF(40001, "只有叶子组织可以挂载大盘"),
    UNIT_MISMATCH(40002, "公式单位不兼容，不能保存"),
    FORMULA_INVALID(40003, "公式不合法：{0}"),
    TARGET_NOT_REFERENCED(40004, "目标未被公式引用"),
    DASHBOARD_NOT_FOUND(40005, "大盘不存在：{0}"),
    CARD_NOT_FOUND(40006, "卡片不存在：{0}"),
    CARD_NOT_IN_DASHBOARD(40007, "卡片不属于该大盘"),
    CARD_TARGET_REQUIRED(40008, "卡片必须绑定一个服务与一个指标对象"),
    CARD_EVALUATION_UNAVAILABLE(40009, "当前部署未启用卡片求值"),

    // 5xxxx 告警
    INVALID_RECIPIENT(50001, "告警接收人无效"),
    CHANNEL_UNAVAILABLE(50002, "通知通道不可用"),
    UNSUPPORTED_EXPRESSION(50003, "不支持的告警表达式"),
    ALERT_ORG_REQUIRED(50004, "组织告警必须指定叶子组织"),

    // 6xxxx 上报异常（非异常结果码 OK/DUPLICATE/QUEUE_FULL 不在此处）
    UNSUPPORTED_VERSION(60001, "不支持的协议版本：{0}"),
    MALFORMED_TREE(60002, "上报数据不合法：{0}"),
    TREE_TOO_LARGE(60003, "单树过大"),
    BATCH_TOO_LARGE(60004, "批次过大"),
    TREE_EXPIRED(60005, "事件时间超出可接收窗口"),
    ID_CONFLICT(60006, "同一 messageId 携带了不同内容"),

    // 7xxxx 平台
    ALREADY_INITIALIZED(70001, "平台已初始化，不能重复执行"),
    NOT_INITIALIZED(70002, "平台尚未初始化"),
    TIMEZONE_IMMUTABLE(70003, "平台时区在初始化后不可修改"),

    // 8xxxx Trace
    TRACE_EXPIRED(80001, "原始树已超过留存期，不可打开"),
    TRACE_NOT_FOUND(80002, "未找到该 MessageTree");

    /** 嵌套 holder：枚举常量构造时本类的静态字段尚未初始化，故模式不能直接作为静态字段。 */
    private static class Placeholder {
        private static final Pattern PATTERN = Pattern.compile("\\{(\\d+)\\}");
    }
    private static final Map<Integer, ErrorCode> BY_CODE = Map.copyOf(Arrays.stream(values())
            .collect(Collectors.toMap(ErrorCode::code, Function.identity(), (left, right) -> {
                throw new IllegalStateException("重复错误码：" + left.code());
            })));

    private final int code;

    private final String template;

    private final int parameterCount;

    ErrorCode(int code, String template) {
        this.code = code;
        this.template = template;
        this.parameterCount = placeholderCount(template);
    }
    private static int placeholderCount(String template) {
        Matcher matcher = Placeholder.PATTERN.matcher(template);
        int count = 0;
        while (matcher.find()) {
            count = Math.max(count, Integer.parseInt(matcher.group(1)) + 1);
        }
        return count;
    }
    public int code() {
        return code;
    }
    @org.springframework.lang.Nullable
    public static ErrorCode fromCode(int code) {
        return BY_CODE.get(code);
    }
    /** 用参数填充消息模板；参数个数必须与模板占位符一致。 */
    public String message(Object... parameters) {
        if (parameters == null || parameters.length != parameterCount) {
            throw new IllegalArgumentException(name() + " 需要 " + parameterCount + " 个模板参数");
        }
        Matcher matcher = Placeholder.PATTERN.matcher(template);
        StringBuffer message = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(
                    message,
                    Matcher.quoteReplacement(String.valueOf(parameters[Integer.parseInt(matcher.group(1))])));
        }
        matcher.appendTail(message);
        return message.toString();
    }
}
