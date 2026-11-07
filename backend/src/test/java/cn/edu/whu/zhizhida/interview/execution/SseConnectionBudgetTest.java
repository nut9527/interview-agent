package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import cn.edu.whu.zhizhida.common.BusinessException;

class SseConnectionBudgetTest {
    @Test void rejectsUnavailableRedisAndCapacityWithoutLeakingDetails() {
        var redis = mock(StringRedisTemplate.class);
        var budget = new SseConnectionBudget(redis, "test", 8, 3);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L, null).thenThrow(new IllegalStateException("private endpoint"));
        assertThatThrownBy(() -> budget.acquire(1)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.status().value()).isEqualTo(429));
        for (int i = 0; i < 2; i++)
            assertThatThrownBy(() -> budget.acquire(1)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.status().value()).isEqualTo(503)).hasMessageNotContaining("private");
    }
}
