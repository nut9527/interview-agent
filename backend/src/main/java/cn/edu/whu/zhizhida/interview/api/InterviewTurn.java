package cn.edu.whu.zhizhida.interview.api;

import com.fasterxml.jackson.annotation.JsonInclude;

public record InterviewTurn(
        String speaker,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) String requestId
) {
    public InterviewTurn(String speaker, String content) {
        this(speaker, content, null);
    }
}
