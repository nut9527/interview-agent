package cn.edu.whu.zhizhida.common;

import org.junit.jupiter.api.BeforeEach;
import cn.edu.whu.zhizhida.quota.QuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiExceptionHandlerTest {

    private ApiExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ApiExceptionHandler();
    }

    @Test
    void handleBusinessExceptionReturnsCustomStatusCodeAndMessage() {
        BusinessException ex = new BusinessException(HttpStatus.CONFLICT, "资源冲突");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).isEqualTo("资源冲突");
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void quotaRejectionIncludesRetryAfter() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleQuota(
                new QuotaExceededException("额度已用完", 37));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("37");
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    void handleBusinessExceptionDefaultStatusIsBadRequest() {
        BusinessException ex = new BusinessException("参数错误");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("参数错误");
    }

    @Test
    void handleUnsupportedOperationExceptionReturns501() {
        UnsupportedOperationException ex = new UnsupportedOperationException("功能开发中");

        ResponseEntity<ApiResponse<Void>> response = handler.handleManualZone(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).isEqualTo("功能开发中");
    }

    @Test
    void handleMethodArgumentNotValidExceptionReturns400WithFieldName() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("request", "username", "不能为空");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("username 不能为空");
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void handleMethodArgumentNotValidExceptionReturnsDefaultMessageWhenNoFieldErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("request validation failed");
    }

    @Test
    void handleMaxUploadSizeExceededExceptionReturns400With5MbMessage() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(5 * 1024 * 1024);

        ResponseEntity<ApiResponse<Void>> response = handler.handleUploadLimit(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("简历文件不能超过 5MB");
    }

    @Test
    void handleIllegalArgumentExceptionReturns400() {
        IllegalArgumentException ex = new IllegalArgumentException("参数非法");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("参数非法");
    }

    @Test
    void handleIllegalStateExceptionReturns502() {
        IllegalStateException ex = new IllegalStateException("服务异常状态");

        ResponseEntity<ApiResponse<Void>> response = handler.handleServiceState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).isEqualTo("服务异常状态");
    }

    @Test
    void handleGenericExceptionReturns500() {
        Exception ex = new Exception("未知错误");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).contains("X-Request-ID").doesNotContain("未知错误");
    }

    @Test
    void businessCodesSurviveMessageChangesAndDistinguishConflictsWithTheSameHttpStatus() {
        var resume = handler.handleBusiness(new BusinessException(ErrorCode.RESUME_REVISION_CONFLICT, "新版提示"));
        var target = handler.handleBusiness(new BusinessException(ErrorCode.TARGET_JOB_REVISION_CONFLICT, "新版提示"));
        assertThat(resume.getStatusCode()).isEqualTo(target.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resume.getBody().code()).isEqualTo(ErrorCode.RESUME_REVISION_CONFLICT);
        assertThat(target.getBody().code()).isEqualTo(ErrorCode.TARGET_JOB_REVISION_CONFLICT);
        assertThat(ApiResponse.ok("value").code()).isNull();
    }
}
