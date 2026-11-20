package cn.edu.whu.zhizhida.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.mcp.reference.enabled", havingValue = "true")
public class McpReferenceConfiguration {
    @Bean(destroyMethod = "close")
    LangChain4jReferenceClient referenceClient(
            @Value("${agent.mcp.reference.url}") String url) {
        return new LangChain4jReferenceClient(url);
    }
}
