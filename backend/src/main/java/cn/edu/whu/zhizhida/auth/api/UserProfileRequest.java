package cn.edu.whu.zhizhida.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserProfileRequest(
        @NotBlank(message = "不能为空") @Size(min = 2, max = 32, message = "长度必须为 2-32 位") String displayName,
        @NotBlank(message = "不能为空") @Size(min = 2, max = 64, message = "长度必须为 2-64 位") String targetRole,
        @Size(max = 12, message = "最多添加 12 个")
        List<@NotBlank(message = "不能为空") @Size(max = 24, message = "单个标签不能超过 24 位") String> skillTags
) {
}
