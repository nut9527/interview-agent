package cn.edu.whu.zhizhida.common;

/** 对外公开且稳定的机器错误码；提示文本可调整，同一错误码不得复用于其他含义。 */
public enum ErrorCode {
    INVALID_REQUEST(400), VALIDATION_FAILED(400), FILE_TOO_LARGE(400),
    AUTH_REQUIRED(401), AUTH_INVALID_CREDENTIALS(401), FORBIDDEN(403), RESOURCE_NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405), CONFLICT(409), UNSUPPORTED_MEDIA_TYPE(415), QUOTA_EXCEEDED(429),
    INTERNAL_ERROR(500), NOT_IMPLEMENTED(501), UPSTREAM_FAILURE(502), SERVICE_UNAVAILABLE(503),
    AUTH_USERNAME_UNAVAILABLE(409), RESUME_REVISION_CONFLICT(409), TARGET_JOB_REVISION_CONFLICT(409),
    INTERVIEW_STATE_CONFLICT(409);

    private final int httpStatus;

    ErrorCode(int httpStatus) { this.httpStatus = httpStatus; }
    public int httpStatus() { return httpStatus; }

    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> AUTH_REQUIRED;
            case 403 -> FORBIDDEN;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> QUOTA_EXCEEDED;
            case 501 -> NOT_IMPLEMENTED;
            case 502 -> UPSTREAM_FAILURE;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> INTERNAL_ERROR;
        };
    }
}
