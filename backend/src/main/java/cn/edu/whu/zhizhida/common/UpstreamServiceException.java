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

    public ErrorCode errorCode() {
        if (code == null) return ErrorCode.UPSTREAM_FAILURE;
        return switch (code) {
            // 保留 Run 账本的 MODEL_NETWORK 分类，不改变已有重试策略。
            case "MODEL_NETWORK" -> getCause() instanceof java.net.http.HttpTimeoutException
                    || getCause() instanceof java.io.IOException io
                    && io.getCause() instanceof java.net.http.HttpTimeoutException
                    ? ErrorCode.AI_TIMEOUT : ErrorCode.AI_NETWORK_ERROR;
            case "MODEL_HTTP_408", "MODEL_HTTP_504" -> ErrorCode.AI_TIMEOUT;
            case "MODEL_HTTP_401", "MODEL_HTTP_403" -> ErrorCode.AI_AUTH_FAILED;
            case "MODEL_CONFIGURATION" -> ErrorCode.AI_CONFIGURATION_ERROR;
            case "MODEL_HTTP_429" -> ErrorCode.AI_RATE_LIMITED;
            case "MODEL_PROTOCOL", "MODEL_RESPONSE_LIMIT" -> ErrorCode.AI_INVALID_RESPONSE;
            default -> ErrorCode.UPSTREAM_FAILURE;
        };
    }
}
