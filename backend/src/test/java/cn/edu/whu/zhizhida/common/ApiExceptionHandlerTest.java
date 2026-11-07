package cn.edu.whu.zhizhida.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.whu.zhizhida.quota.QuotaExceededException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

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
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleQuota(new QuotaExceededException("额度已用完", 37));
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
        UnsupportedOperationException ex = new UnsupportedOperationException("此操作不受支持");

        ResponseEntity<ApiResponse<Void>> response = handler.handleManualZone(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).isEqualTo("此操作不受支持");
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
    void handleMaxUploadSizeExceededExceptionReturns400WithoutDuplicatingEndpointLimits() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(5 * 1024 * 1024);

        ResponseEntity<ApiResponse<Void>> response = handler.handleUploadLimit(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.FILE_TOO_LARGE);
        assertThat(response.getBody().message()).isEqualTo("上传文件超过接口允许的大小，请减小文件后重试。");
    }

    @Test
    void handleIllegalArgumentExceptionReturns400() {
        IllegalArgumentException ex =
                new IllegalArgumentException("内部路径 /private/token.json；模型正文 secret");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("请求参数不正确，请检查后重试。");
    }

    @Test
    void reviewedUserMessageSurvivesWithoutExposingItsCause() {
        var ex =
                new UserInputException(
                        "文件须为有效 UTF-8 文本。",
                        new IllegalArgumentException("private uploaded contents"));

        var response = handler.handleUserInput(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("文件须为有效 UTF-8 文本。");
    }

    @Test
    void mvcSeparatesReviewedInputFailuresFromInternalArgumentsAndModelFeedback() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new FailureController())
                        .setControllerAdvice(handler)
                        .build();
        mvc.perform(get("/failure/page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.message").value("page 必须为 1–1000000，size 必须为 1–100"));
        for (String path : List.of("argument", "tool")) {
            mvc.perform(get("/failure/" + path))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()))
                    .andExpect(jsonPath("$.message").value("请求参数不正确，请检查后重试。"));
        }
    }

    @Test
    void handleUpstreamFailureReturns502WithoutInternalReason() {
        UpstreamServiceException ex =
                new UpstreamServiceException("MODEL_HTTP_401 private provider response");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUpstream(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.UPSTREAM_FAILURE);
        assertThat(response.getBody().message()).isEqualTo("AI 服务暂时不可用或返回内容无效，请稍后重试。");
    }

    @Test
    void mvcDistinguishesUpstreamFailuresFromInternalStateErrors() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new FailureController())
                        .setControllerAdvice(handler)
                        .build();
        for (String path : List.of("upstream", "output", "field")) {
            mvc.perform(get("/failure/" + path))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UPSTREAM_FAILURE.name()))
                    .andExpect(jsonPath("$.message").value("AI 服务暂时不可用或返回内容无效，请稍后重试。"));
        }
        mvc.perform(get("/failure/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.name()))
                .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试，并提供响应头 X-Request-ID 以便排查。"));
    }

    @RestController
    static class FailureController {
        @GetMapping("/failure/upstream")
        void upstream() {
            throw new UpstreamServiceException("private provider response");
        }

        @GetMapping("/failure/output")
        void output() {
            throw new InvalidModelOutputException("private generated content");
        }

        @GetMapping("/failure/field")
        void field() {
            throw StructuredOutput.invalid("privateField");
        }

        @GetMapping("/failure/internal")
        void internal() {
            throw new IllegalStateException("private database state");
        }

        @GetMapping("/failure/page")
        void page() {
            new PageRequest(0, 10);
        }

        @GetMapping("/failure/argument")
        void argument() {
            throw new IllegalArgumentException("/private/token.json: private model content");
        }

        @GetMapping("/failure/tool")
        void tool() {
            throw new IllegalArgumentException("TOOL_ARGUMENTS");
        }
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
        var resume =
                handler.handleBusiness(
                        new BusinessException(ErrorCode.RESUME_REVISION_CONFLICT, "新版提示"));
        var target =
                handler.handleBusiness(
                        new BusinessException(ErrorCode.TARGET_JOB_REVISION_CONFLICT, "新版提示"));
        assertThat(resume.getStatusCode())
                .isEqualTo(target.getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(resume.getBody().code()).isEqualTo(ErrorCode.RESUME_REVISION_CONFLICT);
        assertThat(target.getBody().code()).isEqualTo(ErrorCode.TARGET_JOB_REVISION_CONFLICT);
        assertThat(ApiResponse.ok("value").code()).isNull();
    }
}
