package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewAiService;
import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InterviewAiConfiguration {
    @Bean
    InterviewAiService interviewAiCapability(AiMode mode, JsonChatClient client, ObjectMapper mapper, BusinessMetrics metrics) {
        return mode == AiMode.MOCK ? new MockInterviewAiService(mapper, metrics) : new DefaultInterviewAiService(client, mapper, metrics);
    }
}
