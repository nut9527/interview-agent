package cn.edu.whu.zhizhida.quota;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.springframework.http.HttpStatus;

public class QuotaExceededException extends BusinessException {
    private final long retryAfterSeconds;

    public QuotaExceededException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
