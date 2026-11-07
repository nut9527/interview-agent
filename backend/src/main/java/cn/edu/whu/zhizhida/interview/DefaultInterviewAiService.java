package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

public final class DefaultInterviewAiService extends StructuredInterviewAiService {
    private final JsonChatClient chatClient;
    private final InterviewPromptBuilder prompts;

    public DefaultInterviewAiService(JsonChatClient chatClient, ObjectMapper objectMapper, BusinessMetrics metrics) {
        this(chatClient, objectMapper, metrics, InterviewPromptTemplates.defaults());
    }

    DefaultInterviewAiService(JsonChatClient chatClient, ObjectMapper objectMapper, BusinessMetrics metrics,
            InterviewPromptTemplates templates) {
        super(metrics);
        this.chatClient = java.util.Objects.requireNonNull(chatClient);
        this.prompts = new InterviewPromptBuilder(objectMapper, templates);
    }

    @Override
    protected JsonNode generateOpening(String targetRole, String resumeSnapshot) {
        var input = openingInput(targetRole, resumeSnapshot, null);
        return chatClient.completeJson(input.system(), input.user(), input.temperature(), input.maxTokens());
    }

    @Override
    protected JsonNode generateReview(String targetRole, String resumeSnapshot, List<InterviewTurn> transcript,
                                      int answeredCount, boolean finalRound) {
        var input = reviewInput(targetRole, resumeSnapshot, transcript, answeredCount, finalRound, null);
        return chatClient.completeJson(input.system(), input.user(), input.temperature(), input.maxTokens());
    }

    public cn.edu.whu.zhizhida.ai.api.ChatInput openingInput(String role, String context, String reason) {
        return prompts.openingInput(role, context, reason);
    }
    public cn.edu.whu.zhizhida.ai.api.ChatInput reviewInput(String role, String context, List<InterviewTurn> turns,
            int answered, boolean finalRound, String reason) {
        return prompts.reviewInput(role, context, turns, answered, finalRound, reason);
    }
    public JsonNode templateManifest() { return prompts.templateManifest(); }
}
