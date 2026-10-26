package cn.edu.whu.zhizhida.interview.api;

public record InterviewAiReply(
        String feedback,
        String nextQuestion,
        String finalSummary,
        InterviewAgentState.Decision agentDecision
) {
    public InterviewAiReply(String feedback, String nextQuestion, String finalSummary) {
        this(feedback, nextQuestion, finalSummary, null);
    }
}
