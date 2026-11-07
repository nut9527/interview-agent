package cn.edu.whu.zhizhida.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.edu.whu.zhizhida.ai.api.AiMode;

/** 将供应商配置映射为业务模式；未知配置在启动时失败，避免静默切换为模拟结果。 */
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
