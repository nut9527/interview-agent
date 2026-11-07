package cn.edu.whu.zhizhida.knowledge;
import cn.edu.whu.zhizhida.ai.api.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class KnowledgeAiConfiguration {
    @Bean DefaultKnowledgeAnswerer knowledgeAnswerer(JsonChatClient client,ObjectMapper json,AiMode mode) { return new DefaultKnowledgeAnswerer(client,json,mode); }
}
