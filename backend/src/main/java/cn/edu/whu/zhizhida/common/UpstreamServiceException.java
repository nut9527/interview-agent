package cn.edu.whu.zhizhida.common;

/**
 * 模型不可用（含缺少凭据）或业务内容不符合协议；与普通持久化、程序状态错误区分。
 * reason 仅供内部分类，HTTP 响应使用固定文案，不透传供应商响应或内部字段。
 */
public class UpstreamServiceException extends IllegalStateException {
    private final String code;

    public UpstreamServiceException(String reason) {
        this(reason, null);
    }

    public UpstreamServiceException(String reason, Throwable cause) {
        this(null, reason, cause);
    }

    private UpstreamServiceException(String code, String reason, Throwable cause) {
        super(reason, cause);
        this.code = code;
    }

    /** 只有适配器显式给出的协议码可落入 Run 账本，普通异常文案永不推导为码。 */
    public static UpstreamServiceException coded(String code) {
        return coded(code, null);
    }

    public static UpstreamServiceException coded(String code, Throwable cause) {
        if (code == null || !code.matches("MODEL_[A-Z0-9_]{1,48}")) {
            throw new IllegalArgumentException("Invalid model failure code");
        }
        return new UpstreamServiceException(code, code, cause);
    }

    public String code() { return code; }
}
