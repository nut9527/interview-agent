package cn.edu.whu.zhizhida.common;

/** 供应商已响应，但生成的业务内容不完整或格式无效，可进入有限输出修正。 */
public final class InvalidModelOutputException extends UpstreamServiceException {
    public enum Reason { TRUNCATED, EMPTY, INVALID_JSON, NON_OBJECT, UNKNOWN }

    private final Reason reason;

    public InvalidModelOutputException(String message) {
        this(Reason.UNKNOWN, message);
    }

    public InvalidModelOutputException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }
}
