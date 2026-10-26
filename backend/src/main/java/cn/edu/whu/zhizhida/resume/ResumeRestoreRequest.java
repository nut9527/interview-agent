package cn.edu.whu.zhizhida.resume;

import jakarta.validation.constraints.PositiveOrZero;

public record ResumeRestoreRequest(@PositiveOrZero Long revision) {
}
