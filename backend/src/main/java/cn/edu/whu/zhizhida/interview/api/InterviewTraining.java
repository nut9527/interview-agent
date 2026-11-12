package cn.edu.whu.zhizhida.interview.api;

/** 不可变的训练范围与服务端提取的重练证据，随检查点一起持久化。 */
public record InterviewTraining(Topic topic, Retry retry, QuestionSnapshot question, java.util.List<KnowledgeSnapshot> knowledge,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        String objective) {
    public InterviewTraining(Topic topic, Retry retry, QuestionSnapshot question, java.util.List<KnowledgeSnapshot> knowledge) {
        this(topic, retry, question, knowledge, null);
    }
    public InterviewTraining withObjective(String value) { return new InterviewTraining(topic, retry, question, knowledge, value); }
    public InterviewTraining(Topic topic, Retry retry, QuestionSnapshot question) { this(topic, retry, question, null); }
    public InterviewTraining(Topic topic, Retry retry) { this(topic, retry, null); }
    public enum Topic { DATABASE, CONCURRENCY, PERFORMANCE, PROJECT }
    public record Retry(String sourceSessionId, int sourceRound, String competencyId, String competencyName,
                        String question, String answer, InterviewAgentState.Level level, String evidence, String gap) { }
}
