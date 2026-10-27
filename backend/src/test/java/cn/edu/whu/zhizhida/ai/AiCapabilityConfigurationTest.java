package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.*;
import cn.edu.whu.zhizhida.resume.*;
import cn.edu.whu.zhizhida.resume.api.AiResumeService;
import cn.edu.whu.zhizhida.interview.*;
import cn.edu.whu.zhizhida.interview.api.InterviewAiService;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiCapabilityConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiProviderConfiguration.class, ResumeAiConfiguration.class,
                    InterviewAiConfiguration.class)
            .withBean(JsonChatClient.class, () -> mock(JsonChatClient.class))
            .withBean(ObjectMapper.class, tools.jackson.databind.json.JsonMapper::new)
            .withBean(BusinessMetrics.class, BusinessMetrics::disabled);

    @ParameterizedTest
    @ValueSource(strings = {"mock", "MOCK", "deepseek", " DEEPSEEK "})
    void wiresExactlyOneImplementationPerBusinessPort(String provider) {
        runner.withPropertyValues("ai.provider=" + provider).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AiResumeService.class)
                    .hasSingleBean(InterviewAiService.class);
            boolean mock = provider.trim().equalsIgnoreCase("mock");
            assertThat(context.getBean(AiResumeService.class)).isInstanceOf(mock ? MockAiResumeService.class : DefaultAiResumeService.class);
            assertThat(context.getBean(InterviewAiService.class)).isInstanceOf(mock ? MockInterviewAiService.class : DefaultInterviewAiService.class);
            verifyNoInteractions(context.getBean(JsonChatClient.class));
        });
    }

    @Test
    void unsupportedProviderFailsAtWiringInsteadOfDuringBusinessTransition() {
        runner.withPropertyValues("ai.provider=unknown").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage("不支持的 AI_PROVIDER，请设置为 mock 或 deepseek。"));
    }
}
