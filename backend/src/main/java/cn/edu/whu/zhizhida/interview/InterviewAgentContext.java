package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 调用边界的内部上下文，将服务端策略与不可信的候选人材料分离。
 * 编码结果是 INTERVIEW_AGENT_V1 前缀加 JSON，包含 materials、checkpoint、routing 等字段，
 * 不是纯简历文本；它不会替代会话中持久化的 resumeSnapshot 材料快照。
 */
record InterviewAgentContext(String materials, InterviewAgentState checkpoint, InterviewAgentPolicy.Routing routing,
        boolean projectFocused, cn.edu.whu.zhizhida.interview.api.InterviewDifficulty difficulty,
        java.util.List<cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot.Reference> knowledgeReferences) {
    public InterviewAgentContext {
        difficulty = difficulty == null ? cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.STANDARD : difficulty;
    }

    private static final String PREFIX = "INTERVIEW_AGENT_V1\n";
    private static final ObjectMapper MAPPER = new tools.jackson.databind.json.JsonMapper();

    static String encode(String materials, InterviewAgentState state, InterviewAgentPolicy.Routing routing) {
        return encode(materials, state, routing, false);
    }

    static String encode(String materials, InterviewAgentState state, InterviewAgentPolicy.Routing routing,
            boolean projectFocused) {
        return encode(materials, state, routing, projectFocused,
                cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.STANDARD);
    }

    static String encode(String materials, InterviewAgentState state, InterviewAgentPolicy.Routing routing,
            boolean projectFocused, cn.edu.whu.zhizhida.interview.api.InterviewDifficulty difficulty) {
        return encode(materials, state, routing, projectFocused, difficulty, null);
    }

    static String encode(String materials, InterviewAgentState state, InterviewAgentPolicy.Routing routing,
            boolean projectFocused, cn.edu.whu.zhizhida.interview.api.InterviewDifficulty difficulty,
            java.util.List<cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot.Reference> references) {
        try {
            return PREFIX + MAPPER.writeValueAsString(
                    new InterviewAgentContext(materials, state, routing, projectFocused, difficulty, references));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Cannot encode interview checkpoint", ex);
        }
    }

    static InterviewAgentContext decode(String input) {
        // 无前缀的旧调用仍按普通材料处理；带前缀却无法解码时必须报错，不能丢失策略后降级。
        if (input == null || !input.startsWith(PREFIX))
            return null;
        try {
            return MAPPER.readValue(input.substring(PREFIX.length()), InterviewAgentContext.class);
        } catch (JacksonException ex) {
            throw StructuredOutput.invalid("agentContext");
        }
    }
}
