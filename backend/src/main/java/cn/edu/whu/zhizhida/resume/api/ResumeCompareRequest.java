package cn.edu.whu.zhizhida.resume.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record ResumeCompareRequest(
        @Valid @NotNull ResumeReference left,
        @Valid @NotNull ResumeReference right
) {
}
