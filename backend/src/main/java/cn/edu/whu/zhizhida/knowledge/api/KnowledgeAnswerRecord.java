package cn.edu.whu.zhizhida.knowledge.api;

import java.time.Instant;
import java.util.List;

/** 归档问题、来源版本与回答，保存后不以当前资料正文替换历史依据。 */
public record KnowledgeAnswerRecord(String id, String question, List<Source> documents,
        KnowledgeAnswer answer, Instant createdAt, String parentAnswerId) {
    public KnowledgeAnswerRecord(String id, String question, List<Source> documents, KnowledgeAnswer answer,
            Instant createdAt) {
        this(id, question, documents, answer, createdAt, null);
    }

    public record Source(String documentId, String title, String sourceName, int version, boolean available) {
    }

    public record Summary(String id, String question, boolean sufficient, String executionMode, Instant createdAt) {
    }
}
