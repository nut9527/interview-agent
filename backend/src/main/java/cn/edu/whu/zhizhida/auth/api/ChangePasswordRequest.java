package cn.edu.whu.zhizhida.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "不能为空") @Size(max = 64, message = "不能超过 64 位") String currentPassword,
        @NotBlank(message = "不能为空")
        @Size(min = 8, max = 64, message = "长度必须为 8-64 位")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "必须同时包含字母和数字")
        String newPassword
) {
}
