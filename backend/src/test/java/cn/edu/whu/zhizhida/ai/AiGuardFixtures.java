package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.mockito.Mockito.*;

/** 供应商适配器测试不依赖 Redis；分布式状态机由 AiCallGuardRedisTest 验证。 */
public final class AiGuardFixtures {
    private AiGuardFixtures() { }

    public static AiCallGuard guard() { return guard(BusinessMetrics.disabled()); }

    public static AiCallGuard guard(BusinessMetrics metrics) {
        // 使用 default answer，未调用供应商的校验测试也不会出现 unused stubbing。
        StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("execute")) {
                RedisScript<?> script = invocation.getArgument(0);
                return script.getResultType() == List.class ? List.of(1L, 0L) : 1L;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        return new AiCallGuard(redis, "test", 4, 3, 30, metrics);
    }
}
