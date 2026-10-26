package cn.edu.whu.zhizhida.common;

public record ApiResponse<T>(boolean success, String message, T data,
                             @io.swagger.v3.oas.annotations.media.Schema(nullable = true) ErrorCode code) {

    public ApiResponse(boolean success, String message, T data) {
        this(success, message, data, success ? null : ErrorCode.INTERNAL_ERROR);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "ok", data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return fail(ErrorCode.INVALID_REQUEST, message);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message) {
        return new ApiResponse<>(false, message, null, java.util.Objects.requireNonNull(code));
    }
}
