package cn.edu.whu.zhizhida.knowledge.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 资料分类、出题与反馈的请求和结果类型集合；候选内容须经过相应确认流程才保存为个人题目。 */
public record KnowledgeLearning() {
    public record Organization(String documentId, String category, List<String> tags) {
    }

    public record Organize(@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 64) String> documentIds,
            @NotNull @Size(max = 40) String category,
            @NotNull @Size(max = 10) List<@NotBlank @Size(max = 24) String> tags) {
    }

    public record Selection(@NotBlank @Size(max = 64) String documentId, @Min(1) int version,
            @NotNull @Size(max = 6) List<@NotBlank @Size(max = 100) String> chunkIds) {
    }

    public enum Intent {
        COMPREHENSIVE, PRACTICAL, FUNDAMENTALS
    }

    public enum Difficulty {
        BASIC, INTERMEDIATE, ADVANCED
    }

    public record Generate(@NotEmpty @Size(max = 5) List<@Valid Selection> sources, @Min(1) @Max(5) int count,
            Intent intent, Difficulty difficulty, @Size(max = 300) String focus) {
        public Generate {
            intent = intent == null ? Intent.COMPREHENSIVE : intent;
            difficulty = difficulty == null ? Difficulty.INTERMEDIATE : difficulty;
            focus = focus == null ? "" : focus.strip();
        }

        public Generate(List<Selection> sources, int count) {
            this(sources, count, null, null, null);
        }
    }

    public record Question(String stem, String point, List<String> referencePoints,
            List<KnowledgeAnswer.Citation> citations) {
    }

    public record SelfTest(String id, Question question, boolean confirmed, String executionMode,
            List<KnowledgeAnswerRecord.Source> sources) {
    }

    public record Confirm(@NotBlank @Size(max = 600) String stem, @NotBlank @Size(max = 200) String point,
            @NotEmpty @Size(max = 6) List<@NotBlank @Size(max = 500) String> referencePoints,
            @AssertTrue boolean confirmed) {
    }

    public enum Reason {
        UNRESOLVED, UNSUPPORTED_CITATION, MISSING_MATERIAL
    }

    public record FeedbackRequest(@NotEmpty @Size(max = 3) List<@NotNull Reason> reasons,
            @NotNull @Size(max = 1000) String comment,
            @NotNull @Size(max = 5) List<@NotBlank @Size(max = 64) String> missingDocumentIds) {
    }

    public record Feedback(String answerId, List<Reason> reasons, String comment,
            List<KnowledgeAnswerRecord.Source> missingSources, String question) {
    }
}
