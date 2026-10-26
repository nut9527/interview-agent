package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Internal envelope keeps server policy separate from untrusted candidate material. */
record InterviewAgentContext(String materials, InterviewAgentState checkpoint, InterviewAgentPolicy.Routing routing, boolean projectFocused, cn.edu.whu.zhizhida.interview.api.InterviewDifficulty difficulty) {
    public InterviewAgentContext { difficulty = difficulty == null ? cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.STANDARD : difficulty; }
    private static final String PREFIX = "INTERVIEW_AGENT_V1\n";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String encode(String materials, InterviewAgentState state, InterviewAgentPolicy.Routing routing) {
        return encode(materials, state, routing, false);
    }

    static String encode(String materials, InterviewAgentState state, InterviewAgentPolicy.Routing routing, boolean projectFocused) {
        return encode(materials, state, routing, projectFocused, cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.STANDARD);
    }

    static String encode(String materials, InterviewAgentState state, InterviewAgentPolicy.Routing routing,
            boolean projectFocused, cn.edu.whu.zhizhida.interview.api.InterviewDifficulty difficulty) {
        try { return PREFIX + MAPPER.writeValueAsString(new InterviewAgentContext(materials, state, routing, projectFocused, difficulty)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot encode interview checkpoint", ex); }
    }

    static InterviewAgentContext decode(String input) {
        if (input == null || !input.startsWith(PREFIX)) return null;
        try { return MAPPER.readValue(input.substring(PREFIX.length()), InterviewAgentContext.class); }
        catch (JsonProcessingException ex) { throw StructuredOutput.invalid("agentContext"); }
    }
}
