package cn.edu.whu.zhizhida.interview.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;

public record InterviewAnswerRequest(
        @NotBlank @Size(max = 4000) String answer,
        @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String requestId,
        @Min(1) @Max(12) Integer expectedTurn
) {
    public InterviewAnswerRequest(String answer) {
        this(answer, null, null);
    }
}
