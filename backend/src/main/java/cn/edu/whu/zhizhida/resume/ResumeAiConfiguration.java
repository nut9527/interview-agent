package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.AiResumeService;
import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResumeAiConfiguration {
    @Bean
    AiResumeService resumeAiCapability(AiMode mode, JsonChatClient client, ObjectMapper mapper, BusinessMetrics metrics) {
        return mode == AiMode.MOCK ? new MockAiResumeService(mapper, metrics) : new DefaultAiResumeService(client, metrics);
    }
}
