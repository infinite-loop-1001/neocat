package com.neocat.common.error;

/**
 * 所有业务异常的抽象基类；消息由错误码模板和参数生成。
 *
 * <p>错误类别由具体子类表达，因此基类不再携带类别字段。
 */
public abstract class NeocatException extends RuntimeException {
    private final ErrorCode code;

    protected NeocatException(ErrorCode code, Object... parameters) {
        super(code.message(parameters));
        this.code = code;
    }
    public final ErrorCode code() {
        return code;
    }
}
