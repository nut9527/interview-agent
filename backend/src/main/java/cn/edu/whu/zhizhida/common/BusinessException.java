package cn.edu.whu.zhizhida.common;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.code = ErrorCode.forStatus(status.value());
    }

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code);
        this.status = HttpStatus.valueOf(code.httpStatus());
    }

    public ErrorCode code() { return code; }

    public HttpStatus status() {
        return status;
    }
}
