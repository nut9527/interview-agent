package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCallGuardTest {
    static final Duration TIMEOUT = Duration.ofSeconds(25);

    @Test void redisOutageOrMalformedAdmissionFailsClosed() {
        for (Object response : List.of(List.of(), List.of(1L), List.of(1L, -1L), List.of(2L, 0L), "failure")) {
            StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation -> {
                if (invocation.getMethod().getName().equals("execute")) {
                    if (response instanceof String) throw new IllegalStateException("Redis down");
                    return response;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            Supplier<String> provider = mock(Supplier.class);
            unavailable(new AiCallGuard(redis, "test", 4, 3, 30, BusinessMetrics.disabled()), provider);
            verifyNoInteractions(provider);
        }
        unavailable(new AiCallGuard(mock(StringRedisTemplate.class), "test", 4, 3, 30, BusinessMetrics.disabled()),
                () -> { throw new AssertionError("provider must not run"); });
    }

    @Test void rejectionDoesNotSettleOrCallProvider() {
        for (long status : List.of(0L, -1L)) {
            var redis = mock(StringRedisTemplate.class, invocation -> {
                if (invocation.getMethod().getName().equals("execute")) return List.of(status, 0L);
                return RETURNS_DEFAULTS.answer(invocation);
            });
            unavailable(new AiCallGuard(redis, "test", 4, 3, 30, BusinessMetrics.disabled()),
                    () -> { throw new AssertionError("provider must not run"); });
            assertThat(mockingDetails(redis).getInvocations()).hasSize(1);
        }
    }

    @Test void eachCallOwnsAUniquePermitWithTransportTimeoutAndSettlementMargin() {
        var arguments = new ArrayList<Object[]>();
        var redis = mock(StringRedisTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("execute")) {
                arguments.add(invocation.getArguments());
                return ((RedisScript<?>) invocation.getArgument(0)).getResultType() == List.class
                        ? List.of(1L, 7L) : 1L;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        var guard = new AiCallGuard(redis, "test", 4, 3, 30, BusinessMetrics.disabled());
        assertThat(guard.execute(TIMEOUT, () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> guard.execute(Duration.ofSeconds(50), () -> { throw new AssertionError("error"); }))
                .isInstanceOf(AssertionError.class);
        assertThat(arguments).hasSize(4);
        assertThat(arguments.get(0)[1]).isEqualTo(List.of("test:{ai-call}:leases", "test:{ai-call}:circuit"));
        assertThat(arguments.get(0)[4]).isEqualTo("55000");
        assertThat(arguments.get(2)[4]).isEqualTo("80000");
        assertThat(arguments.get(0)[2]).isNotEqualTo(arguments.get(2)[2]);
        assertThat(arguments.get(1)[2]).isEqualTo(arguments.get(0)[2]);
        assertThat(arguments.get(1)[3]).isEqualTo("7");
        assertThat(arguments.get(1)[4]).isEqualTo("1");
        assertThat(arguments.get(3)[4]).isEqualTo("0");
    }

    @Test void settlementOutagePreservesSuccessAndOriginalProviderError() {
        IllegalStateException outage = new IllegalStateException("Redis down");
        var redis = mock(StringRedisTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("execute")) {
                if (((RedisScript<?>) invocation.getArgument(0)).getResultType() == List.class) return List.of(1L, 0L);
                throw outage;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        var registry = new SimpleMeterRegistry();
        try {
            var guard = new AiCallGuard(redis, "test", 4, 3, 30, new BusinessMetrics(registry));
            assertThat(guard.execute(TIMEOUT, () -> "paid result")).isEqualTo("paid result");
            var original = new AssertionError("provider error");
            assertThatThrownBy(() -> guard.execute(TIMEOUT, () -> { throw original; })).isSameAs(original);
            assertThat(original.getSuppressed()).containsExactly(outage);
            assertThat(registry.get("zhizhida.business").tags("operation", "ai", "outcome", "failure").timer().count()).isEqualTo(1);
            assertThat(registry.get("zhizhida.business").tags("operation", "ai", "outcome", "success").timer().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test void metricsFailureDoesNotOverwriteProviderErrorOrDoubleSettleSuccess() {
        var metrics = mock(BusinessMetrics.class);
        var failure = new IllegalStateException("metrics unavailable");
        doThrow(failure).when(metrics).record(eq(BusinessMetrics.Operation.AI), any(), anyLong());
        var guard = AiGuardFixtures.guard(metrics);
        var original = new AssertionError("provider error");
        assertThatThrownBy(() -> guard.execute(TIMEOUT, () -> { throw original; })).isSameAs(original);
        assertThat(original.getSuppressed()).containsExactly(failure);
        assertThatThrownBy(() -> guard.execute(TIMEOUT, () -> "ok")).isSameAs(failure);
        verify(metrics, times(1)).record(eq(BusinessMetrics.Operation.AI), eq(BusinessMetrics.Outcome.FAILURE), anyLong());
        verify(metrics, times(1)).record(eq(BusinessMetrics.Operation.AI), eq(BusinessMetrics.Outcome.SUCCESS), anyLong());
    }

    @Test void rejectsInvalidConfigurationAndTimeout() {
        var redis = mock(StringRedisTemplate.class);
        var metrics = BusinessMetrics.disabled();
        assertThatThrownBy(() -> new AiCallGuard(redis, "test", 0, 1, 30, metrics)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiCallGuard(redis, "test", 1, 0, 30, metrics)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiCallGuard(redis, "test", 1, 1, 0, metrics)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiCallGuard(redis, "", 1, 1, 30, metrics)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiGuardFixtures.guard().execute(Duration.ZERO, () -> "bad"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redis);
    }

    private void unavailable(AiCallGuard guard, Supplier<String> provider) {
        assertThatThrownBy(() -> guard.execute(TIMEOUT, provider)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
