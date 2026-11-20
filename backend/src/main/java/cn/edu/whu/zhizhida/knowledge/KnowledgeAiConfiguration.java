package cn.edu.whu.zhizhida.knowledge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class KnowledgeAiConfiguration {
    @Bean
    DefaultKnowledgeAnswerer knowledgeAnswerer(JsonChatClient client, ObjectMapper json, AiMode mode) {
        return new DefaultKnowledgeAnswerer(client, json, mode);
    }
}
