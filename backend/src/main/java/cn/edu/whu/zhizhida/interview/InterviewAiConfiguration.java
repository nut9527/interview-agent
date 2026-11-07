package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.interview.api.InterviewAiService;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InterviewAiConfiguration {
    @Bean
    InterviewPromptTemplates interviewPromptTemplates(
            @org.springframework.beans.factory.annotation.Value("${interview.prompts.directory:}") String directory) {
        return InterviewPromptTemplates.load(directory);
    }

    @Bean
    InterviewAiService interviewAiCapability(AiMode mode, JsonChatClient client, ObjectMapper mapper,
            BusinessMetrics metrics, InterviewPromptTemplates templates) {
        return mode == AiMode.MOCK ? new MockInterviewAiService(mapper, metrics) : new DefaultInterviewAiService(client, mapper, metrics, templates);
    }

    @Bean
    InterviewDecisionSource interviewDecisionSource(AiMode mode, ObjectMapper mapper,
            BusinessMetrics metrics) {
        return mode == AiMode.MOCK
                ? InterviewDecisionSource.local(new MockInterviewAiService(mapper, metrics), mapper,
                        mapper.valueToTree(ModelUsage.unknown(ModelUsage.Status.MISSING)))
                : (transport, session, pending, context, phase) -> transport.get();
    }
}
