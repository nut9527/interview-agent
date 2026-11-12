package cn.edu.whu.zhizhida.interview.api;

/** 模型生成的回答反馈、下一问和评价草稿；业务提交前仍须校验结构与来源。 */
public record InterviewAiReply(
        String feedback,
        String nextQuestion,
        String finalSummary,
        InterviewAgentState.Decision agentDecision,
        cn.edu.whu.zhizhida.evaluation.api.AssessmentDraft assessment
) {
    public InterviewAiReply(String feedback, String nextQuestion, String finalSummary, InterviewAgentState.Decision agentDecision) {
        this(feedback, nextQuestion, finalSummary, agentDecision, null);
    }
    public InterviewAiReply(String feedback, String nextQuestion, String finalSummary) {
        this(feedback, nextQuestion, finalSummary, null);
    }
}
