package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResumeAiConfiguration {
    @Bean
    ResumeImportRecognizer resumeImportRecognizer(AiMode mode, JsonChatClient client, ResumeTextParser parser) {
        return mode == AiMode.MOCK ? parser::parse : new AiResumeImportRecognizer(client, parser);
    }
}
