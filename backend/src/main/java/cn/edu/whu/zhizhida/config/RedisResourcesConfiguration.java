package cn.edu.whu.zhizhida.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

/** 缓存与配额协调使用独立客户端及服务端配置；仅分隔数据库编号或键前缀无法隔离内存压力。 */
@Configuration(proxyBeanMethods = false)
public class RedisResourcesConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.cache.questions.enabled", havingValue = "true")
    LettuceConnectionFactory questionCacheRedisConnectionFactory(Environment env) {
        return connection(env, "app.redis.cache", 6380);
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.questions.enabled", havingValue = "true")
    StringRedisTemplate questionCacheRedisTemplate(
            @Qualifier("questionCacheRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    // 协调客户端始终存在；Boot 的可选响应式设施使用它，业务仍通过 Qualifier 显式选择资源。
    @org.springframework.context.annotation.Primary
    LettuceConnectionFactory submissionRedisConnectionFactory(Environment env) {
        return connection(env, "app.redis.rate-limit", 6379);
    }

    @Bean
    StringRedisTemplate submissionRedisTemplate(
            @Qualifier("submissionRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private LettuceConnectionFactory connection(Environment env, String prefix, int defaultPort) {
        String host = env.getProperty(prefix + ".host", "127.0.0.1");
        int port = env.getProperty(prefix + ".port", Integer.class, defaultPort);
        int database = env.getProperty(prefix + ".database", Integer.class, 0);
        Duration connectTimeout = DurationStyle.detectAndParse(env.getProperty(prefix + ".connect-timeout", "500ms"));
        Duration timeout = DurationStyle.detectAndParse(env.getProperty(prefix + ".timeout", "300ms"));
        if (host.isBlank() || port < 1 || port > 65535 || database < 0
                || connectTimeout.isNegative() || connectTimeout.isZero() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid Redis resource configuration: " + prefix);
        }
        var server = new RedisStandaloneConfiguration(host, port);
        server.setDatabase(database);
        String username = env.getProperty(prefix + ".username", "");
        String password = env.getProperty(prefix + ".password", "");
        if (!username.isBlank()) server.setUsername(username);
        if (!password.isEmpty()) server.setPassword(password);
        var client = LettuceClientConfiguration.builder().commandTimeout(timeout)
                .shutdownTimeout(Duration.ofMillis(100))
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                        .connectTimeout(connectTimeout).build()).build());
        if (env.getProperty(prefix + ".ssl", Boolean.class, false)) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }
}
