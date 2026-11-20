package cn.edu.whu.zhizhida.knowledge.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** 资料问答结果及逐条引用；资料不足时返回说明，不将生成内容视为已验证事实。 */
public record KnowledgeAnswer(boolean sufficient, String message, List<Claim> claims, String executionMode,
        String historyId) {
    public KnowledgeAnswer(boolean sufficient, String message, List<Claim> claims, String executionMode) {
        this(sufficient, message, claims, executionMode, null);
    }

    public record Claim(String text, List<Citation> citations) {
    }

    public record Citation(String documentId, String title, int version, String chunkId, int startLine, int endLine,
            String quote) {
    }

    public record Request(@NotBlank @Size(max = 300) String question,
            @NotEmpty @Size(max = 5) List<@NotBlank @Size(max = 64) String> documentIds,
            @Size(min = 1, max = 64) String parentAnswerId) {
        public Request(String question, List<String> documentIds) {
            this(question, documentIds, null);
        }
    }
}
