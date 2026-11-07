package cn.edu.whu.zhizhida.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeleteAccountRequest(
        @NotBlank(message = "不能为空") @Size(max = 64, message = "不能超过 64 位") String currentPassword
) {
}
