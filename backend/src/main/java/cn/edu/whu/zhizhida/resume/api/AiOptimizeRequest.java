package cn.edu.whu.zhizhida.resume.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiOptimizeRequest(
        @NotBlank @Size(max = 8000) String rawText,
        @NotBlank @Size(max = 120) String targetRole
) {
}
