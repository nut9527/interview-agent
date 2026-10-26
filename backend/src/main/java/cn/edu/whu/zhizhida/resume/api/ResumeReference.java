package cn.edu.whu.zhizhida.resume.api;

import jakarta.validation.constraints.NotNull;

public record ResumeReference(
        @NotNull Long resumeId,
        Long versionId
) {
}
