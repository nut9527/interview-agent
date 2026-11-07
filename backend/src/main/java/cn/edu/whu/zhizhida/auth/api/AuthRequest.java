package cn.edu.whu.zhizhida.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AuthRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_]{4,24}$", message = "只能包含字母、数字和下划线，长度为 4-24 位")
        String username,
        @NotBlank
        @Size(min = 6, max = 64, message = "长度应为 6-64 位")
        String password,
        @Size(max = 60, message = "不能超过 60 个字符")
        String displayName
) {
}
