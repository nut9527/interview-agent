package cn.edu.whu.zhizhida.interview.api;

import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewStartRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 对外视图仅展示运行信息，不包含模型消息、工具结果或未提交的候选输出。 */
public record InterviewRun(String runId, String status, String phase, String sessionId,
                           int expectedTurn, String errorCode, long lastEventSeq, Instant deadline,
                           String statusUrl, String eventsUrl) {
    public record TaskStart(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId) { }
    public record Start(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId,
                        @NotNull @Valid InterviewStartRequest configuration) { }
    public record Answer(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId,
                         @NotNull @Min(1) @Max(12) Integer expectedTurn,
                         @NotBlank @Size(max=8000) String answer) { }
    public record Event(String runId, long seq, String type, String phase, String message, String sessionId) { }

    /** 仅包含可公开的元数据，不包含引文、查询、原始回执、候选输出或模型私有状态。 */
    @Schema(name = "cn.edu.whu.zhizhida.interview.api.InterviewRunTrace")
    public record InterviewRunTrace(String runId, String policyVersion, String status, boolean hidden,
            String resultCompleteness, String stopReason, int actionSteps, int actualExecutions, int cacheHits,
            int unknownExecutions, List<Step> steps) {
        @Schema(name = "cn.edu.whu.zhizhida.interview.api.InterviewRunTrace$Step")
        public record Step(int stepNo, String decisionType, String tool, String observation, int newEvidence,
                boolean cacheHit, String stopReason) { }
    }
}
