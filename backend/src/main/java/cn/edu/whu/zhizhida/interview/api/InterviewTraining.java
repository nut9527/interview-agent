package cn.edu.whu.zhizhida.interview.api;

/** Immutable training scope and server-derived retry evidence, persisted with the checkpoint. */
public record InterviewTraining(Topic topic, Retry retry) {
    public enum Topic { DATABASE, CONCURRENCY, PERFORMANCE, PROJECT }
    public record Retry(String sourceSessionId, int sourceRound, String competencyId, String competencyName,
                        String question, String answer, InterviewAgentState.Level level, String evidence, String gap) { }
}
