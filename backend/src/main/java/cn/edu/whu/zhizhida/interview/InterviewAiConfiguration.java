package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.ModelUsage;
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
    InterviewDecisionSource interviewDecisionSource(AiMode mode, ObjectMapper mapper) {
        return mode == AiMode.MOCK
                ? InterviewDecisionSource.local(new MockInterviewAiService(mapper), mapper,
                        mapper.valueToTree(ModelUsage.unknown(ModelUsage.Status.MISSING)))
                : (transport, session, pending, context, phase) -> transport.get();
    }
}
