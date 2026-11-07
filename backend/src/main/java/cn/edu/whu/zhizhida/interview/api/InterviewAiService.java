package cn.edu.whu.zhizhida.interview.api;

import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import java.util.List;

/**
 * 使用服务端准备的岗位、材料和对话生成问题或反馈；不负责账号授权与持久提交。
 * context 接受普通材料文本，或服务端生成的 INTERVIEW_AGENT_V1 前缀加 JSON 上下文
 * （含 materials、checkpoint、routing）；不能将后者直接当作简历正文。
 */
public interface InterviewAiService {

    default String executionMode() { return "model"; }
    default boolean supportsAgentDecisions() { return false; }

    String openingQuestion(String targetRole, String context);

    InterviewAiReply reviewAnswer(String targetRole,
                                  String context,
                                  List<InterviewTurn> transcript,
                                  int answeredCount,
                                  boolean finalRound);

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
}
