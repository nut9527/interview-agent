package cn.edu.whu.zhizhida.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

class RedisResourcesConfigurationTest {
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, DataRedisReactiveAutoConfiguration.class))
            .withUserConfiguration(RedisResourcesConfiguration.class);

    @Test void coordinationClientIsRequiredEvenWithOptionalFeaturesDisabled() {
        context.run(c -> assertThat(c).doesNotHaveBean("questionCacheRedisTemplate")
                .hasBean("submissionRedisTemplate"));
    }

    @Test void resourcesUseSeparateFactoriesAndIndependentSettings() {
        context.withPropertyValues("app.cache.questions.enabled=true", "app.rate-limit.redis.enabled=true",
                "app.redis.cache.port=16380", "app.redis.cache.timeout=120ms",
                "app.redis.cache.password=cache-test", "app.redis.rate-limit.port=16379",
                "app.redis.rate-limit.timeout=250ms", "app.redis.rate-limit.password=rate-test")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    var cache = c.getBean("questionCacheRedisConnectionFactory", LettuceConnectionFactory.class);
                    var rate = c.getBean("submissionRedisConnectionFactory", LettuceConnectionFactory.class);
                    assertThat(cache).isNotSameAs(rate);
                    assertThat(cache.getPort()).isEqualTo(16380);
                    assertThat(rate.getPort()).isEqualTo(16379);
                    assertThat(cache.getTimeout()).isEqualTo(120);
                    assertThat(rate.getTimeout()).isEqualTo(250);
                    assertThat(cache.getStandaloneConfiguration().getPassword().get()).isEqualTo("cache-test".toCharArray());
                    assertThat(rate.getStandaloneConfiguration().getPassword().get()).isEqualTo("rate-test".toCharArray());
                    assertThat(c.getBean("questionCacheRedisTemplate", StringRedisTemplate.class).getConnectionFactory()).isSameAs(cache);
                    assertThat(c.getBean("submissionRedisTemplate", StringRedisTemplate.class).getConnectionFactory()).isSameAs(rate);
                    assertThat(c.getBeansOfType(StringRedisTemplate.class)).hasSize(2);
                });
    }

    @Test void singleEnabledResourceDoesNotCreateTheOtherClient() {
        context.withPropertyValues("app.cache.questions.enabled=true").run(c -> {
            assertThat(c).hasNotFailed().hasBean("questionCacheRedisTemplate").hasBean("submissionRedisTemplate");
            assertThat(c.getBean("questionCacheRedisConnectionFactory", LettuceConnectionFactory.class).getPort()).isEqualTo(6380);
        });
        context.withPropertyValues("app.rate-limit.redis.enabled=true").run(c -> {
            assertThat(c).hasNotFailed().hasBean("submissionRedisTemplate").doesNotHaveBean("questionCacheRedisTemplate");
            assertThat(c.getBean("submissionRedisConnectionFactory", LettuceConnectionFactory.class).getPort()).isEqualTo(6379);
        });
    }

    @Test void invalidTimeoutFailsStartupWithoutLeakingCredentials() {
        context.withPropertyValues("app.cache.questions.enabled=true", "app.redis.cache.timeout=0ms",
                "app.redis.cache.password=do-not-print").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasStackTraceContaining("Invalid Redis resource configuration: app.redis.cache")
                    .hasStackTraceContaining("app.redis.cache");
            assertThat(c.getStartupFailure().getMessage()).doesNotContain("do-not-print");
        });
    }
}
