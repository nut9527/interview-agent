package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AiProviderConfiguration {
    @Bean
    AiMode aiMode(@Value("${ai.provider:mock}") String provider) {
        return switch (provider.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "mock" -> AiMode.MOCK;
            case "deepseek" -> AiMode.MODEL;
            default -> throw new IllegalStateException("不支持的 AI_PROVIDER，请设置为 mock 或 deepseek。");
        };
    }
}
