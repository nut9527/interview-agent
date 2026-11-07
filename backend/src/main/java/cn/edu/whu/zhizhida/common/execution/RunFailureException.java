package cn.edu.whu.zhizhida.common.execution;

/** 执行协议码保持稳定，不依赖异常文案或嵌套供应商错误。 */
public final class RunFailureException extends IllegalStateException {
    private final String code;

    public RunFailureException(String code) {
        this(code, null);
    }

    public RunFailureException(String code, Throwable cause) {
        super(requireCode(code), cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        if (code == null || !code.matches("(RUN|MODEL|TOOL)_[A-Z0-9_]{1,48}")) {
            throw new IllegalArgumentException("Invalid execution error code");
        }
        return code;
    }
}
