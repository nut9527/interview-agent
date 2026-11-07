package cn.edu.whu.zhizhida.interview.api;

import jakarta.validation.constraints.*;
import java.time.Instant;

/** 个人训练任务及其进度视图；来源资格与状态流转由服务端校验。 */
public record TrainingTask(String id, String sourceSessionId, int sourceRound, String questionId,
        String title, String gap, String status, String activeSessionId, int answeredCount, int totalRounds, Instant createdAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String goalId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer questionVersion,
        String sourceType, String sourceDocumentId, Integer sourceDocumentVersion, InterviewTraining.Topic trainingTopic) {
    public record Create(@NotBlank @Size(max=64) String sourceSessionId, @Min(1) @Max(12) int sourceRound,
            @Size(max=64) String questionId) { }
    public enum Action { PAUSE, RESUME }
    public record Update(@NotNull Action action) { }
    public enum DirectSource { QUESTION, KNOWLEDGE }
    public record DirectCreate(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId,
            @NotNull DirectSource sourceType, @Size(min=1,max=64) String questionId,
            @Positive Integer questionVersion, @Size(min=1,max=64) String documentId,
            @Positive Integer documentVersion, InterviewTraining.Topic trainingTopic) { }
}
