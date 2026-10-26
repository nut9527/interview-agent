package cn.edu.whu.zhizhida.common;

/** The provider responded, but the generated business content is incomplete or malformed. */
public final class InvalidModelOutputException extends IllegalStateException {
    public InvalidModelOutputException(String reason) { super(reason); }
}
