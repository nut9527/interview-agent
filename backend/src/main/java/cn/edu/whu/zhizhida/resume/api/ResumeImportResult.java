package cn.edu.whu.zhizhida.resume.api;

import cn.edu.whu.zhizhida.common.StructuredOutput.RejectionReason;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile.ResumeProfileRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ResumeImportResult(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Draft draft,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Warning> warnings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecognitionMode recognitionMode) {
    public ResumeImportResult(ResumeProfileRequest draft, List<Warning> warnings, RecognitionMode recognitionMode) {
        this(new Draft(draft.ownerName(), draft.targetRole(), draft.summary(), draft.skills(), draft.educationExperiences(),
                draft.projectExperiences(), draft.internshipExperiences(), draft.certificates(), draft.templateKey(),
                draft.revision(), draft.details()), warnings, recognitionMode);
    }

    public enum RecognitionMode { AI, LOCAL }

    /** 导入结果允许必填信息暂缺，由用户补充后再提交保存。 */
    public record Draft(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ownerName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetRole,
            String summary,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> skills,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> educationExperiences,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> projectExperiences,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> internshipExperiences,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> certificates,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String templateKey,
            Long revision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ResumeDetails details) {}

    public record Warning(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String field,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RejectionReason code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {}
}
