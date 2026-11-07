package cn.edu.whu.zhizhida.questionbank.api;

import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import java.time.Instant;
import java.util.List;

public record Question(String id, String title, String question, QuestionTopic topic, String competencyId,
                       InterviewDifficulty difficulty, List<String> tags, List<String> keyPoints,
                       String referenceAnswer, List<String> commonMistakes, List<String> followUps,
                       SourceType sourceType, String sourceTitle, String sourceUrl, Instant createdAt, Instant updatedAt,
                       int version, boolean published) {
    public enum SourceType { ORIGINAL, EXTERNAL_REFERENCE }
    public record Summary(String id, String title, String questionSummary, QuestionTopic topic,
                          String competencyId, InterviewDifficulty difficulty, List<String> tags, int version) {}
    public record Draw(List<Summary> items, int requested, int roundsPerQuestion) {}
    public record TopicOption(QuestionTopic value, String label, String competencyId) {}
    public record DifficultyOption(InterviewDifficulty value, String label) {}
    public record Metadata(List<TopicOption> topics, List<DifficultyOption> difficulties) {}
    public record ReadingReference(String title, String summary, String url) {}
}

