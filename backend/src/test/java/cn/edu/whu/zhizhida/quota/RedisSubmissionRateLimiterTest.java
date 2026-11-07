package cn.edu.whu.zhizhida.quota;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ApiExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class RedisSubmissionRateLimiterTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final RedisSubmissionRateLimiter limiter=new RedisSubmissionRateLimiter(redis,new SimpleMeterRegistry(),"test",10,10000);
    @Test void allowedAndRejectedResponsesHaveExplicitSemantics() {
        when(redis.execute(any(RedisScript.class),eq(List.of("test:rate:ai-submit:42")),eq("10"),eq("10000")))
                .thenReturn(List.of(1L,10000L),List.of(0L,1001L));
        limiter.require(42);
        assertThatThrownBy(()->limiter.require(42)).isInstanceOfSatisfying(QuotaExceededException.class,ex->{
            var response=new ApiExceptionHandler().handleQuota(ex);
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        });
    }
    @Test void redisFailureAndMalformedResultFailClosedAs503() {
        when(redis.execute(any(RedisScript.class),anyList(),anyString(),anyString()))
                .thenThrow(new IllegalStateException("private connection details"));
        assertUnavailable();
        reset(redis);
        when(redis.execute(any(RedisScript.class),anyList(),anyString(),anyString())).thenReturn(List.of(1L,-1L));
        assertUnavailable();
    }
    void assertUnavailable() {
        assertThatThrownBy(()->limiter.require(42)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.status().value()).isEqualTo(503)).hasMessageNotContaining("private");
    }
}
