package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.resume.api.ResumeImportResult;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResumeAiConfiguration {
    @Bean
    ResumeImportRecognizer resumeImportRecognizer(AiMode mode, JsonChatClient client, ResumeTextParser parser) {
        return mode == AiMode.MOCK
                ? (text, fileName, role) -> new ResumeImportResult(parser.parse(text, fileName, role), List.of(),
                        ResumeImportResult.RecognitionMode.LOCAL)
                : new AiResumeImportRecognizer(client, parser);
    }
}
