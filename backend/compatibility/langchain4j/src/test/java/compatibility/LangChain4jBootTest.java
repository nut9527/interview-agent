package compatibility;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class LangChain4jBootTest {
    @Test void explicitLowLevelBeansDoNotReplaceTheApplicationMapperOrLoadAgentServices() {
        new ApplicationContextRunner().withUserConfiguration(Models.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class).hasSingleBean(StreamingChatModel.class)
                    .hasSingleBean(ObjectMapper.class);
            assertThat(context.getBean(ObjectMapper.class)).isSameAs(Models.APPLICATION_JSON);
            assertThat(SpringBootVersion.getVersion()).isEqualTo("4.1.2");
            assertThat(Runtime.version().feature()).isEqualTo(21);
            assertThatThrownBy(() -> Class.forName("dev.langchain4j.service.AiServices"))
                    .isInstanceOf(ClassNotFoundException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Models {
        static final ObjectMapper APPLICATION_JSON = new JsonMapper();
        @Bean ObjectMapper applicationJson() { return APPLICATION_JSON; }
        // 构造 Bean 不应发送请求，此处保留的本机端口不会被访问。
        @Bean ChatModel chatModel() { return OpenAiChatModel.builder().baseUrl("http://127.0.0.1:1")
                .apiKey("synthetic-only").modelName("deepseek-chat").maxRetries(0).build(); }
        @Bean StreamingChatModel streamingChatModel() { return OpenAiStreamingChatModel.builder()
                .baseUrl("http://127.0.0.1:1").apiKey("synthetic-only").modelName("deepseek-chat").build(); }
    }
}
