package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.execution.InterviewEvidenceTools;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InterviewPromptConfigurationTest {
        private final JsonMapper json = new JsonMapper();
        private final ApplicationContextRunner context = new ApplicationContextRunner()
                        .withUserConfiguration(InterviewAiConfiguration.class, InterviewRunModel.class)
                        .withBean(AiMode.class, () -> AiMode.MODEL)
                        .withBean(ToolCallingChatClient.class, () -> mock(ToolCallingChatClient.class))
                        .withBean(ObjectMapper.class, () -> json)
                        .withBean(BusinessMetrics.class, BusinessMetrics::disabled)
                        .withBean(
                                        InterviewEvidenceTools.class, () -> mock(InterviewEvidenceTools.class));

        @Test
        void defaultConfigurationLoadsPackagedTemplates() {
                context.run(
                                c -> {
                                        assertThat(c).hasNotFailed();
                                        var service = new InterviewPromptBuilder(
                                                        json, c.getBean(InterviewPromptTemplates.class));
                                        assertThat(service.templateManifest().path("templateVersion").asString())
                                                        .isEqualTo("interview-prompts-v6");
                                        assertThat(service.openingInput("Java", "材料", null).system())
                                                        .startsWith(InterviewPromptTemplates.defaults().get("opening"));
                                });
        }

        @Test
        void externalBundleFeedsRuntimeAndFencesChangedRuns(@TempDir Path directory) throws Exception {
                writeBundle(directory);
                Files.writeString(directory.resolve("version.txt"), "test-v2\n");
                Files.writeString(directory.resolve("opening.txt"), "外部模板首问\n");
                String[] saved = new String[1];
                var configured = context.withPropertyValues("interview.prompts.directory=" + directory);
                configured.run(
                                c -> {
                                        assertThat(c).hasNotFailed();
                                        var service = new InterviewPromptBuilder(
                                                        json, c.getBean(InterviewPromptTemplates.class));
                                        var model = c.getBean(InterviewRunModel.class);
                                        var session = mock(InterviewSession.class);
                                        when(session.targetRole()).thenReturn("Java");
                                        assertThat(service.templateManifest().path("templateVersion").asString())
                                                        .isEqualTo("test-v2");
                                        assertThat(service.openingInput("Java", "材料", null).system())
                                                        .startsWith("外部模板首问\n");
                                        assertThat(
                                                        model.messages(session, null, "材料", json.createObjectNode())
                                                                        .getFirst()
                                                                        .path("content")
                                                                        .asString())
                                                        .startsWith("外部模板首问\n");
                                        saved[0] = model.configuration();
                                        model.requireConfiguration(saved[0]);
                                        // 磁盘改动只在下一次启动加载，当前实例的提示词及其指纹不能分离。
                                        Files.writeString(directory.resolve("opening.txt"), "更新后的首问\n");
                                        assertThat(model.configuration()).isEqualTo(saved[0]);
                                        assertThat(service.openingInput("Java", "材料", null).system())
                                                        .startsWith("外部模板首问\n");
                                });
                // 即使维护者忘记修改版本，实际正文变化也必须触发旧任务的配置保护。
                configured.run(
                                c -> {
                                        assertThat(c).hasNotFailed();
                                        var model = c.getBean(InterviewRunModel.class);
                                        assertThatThrownBy(() -> model.requireConfiguration(saved[0]))
                                                        .hasMessage("MODEL_CONFIG_CHANGED");
                                        verify(c.getBean(ToolCallingChatClient.class), never()).complete(any());
                                });
        }

        @Test
        void incompleteExternalBundleFailsAtStartup(@TempDir Path directory) {
                context.withPropertyValues("interview.prompts.directory=" + directory)
                                .run(c -> assertThat(c).hasFailed());
        }

        private void writeBundle(Path directory) throws Exception {
                var templates = new InterviewPromptBuilder(json).templateManifest().path("templates");
                for (var entry : templates.properties())
                        Files.writeString(
                                        directory.resolve(entry.getKey() + ".txt"), entry.getValue().asString());
                Files.writeString(directory.resolve("version.txt"), "interview-prompts-v2\n");
        }
}
