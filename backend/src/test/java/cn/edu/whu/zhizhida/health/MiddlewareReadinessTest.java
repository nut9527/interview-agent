package cn.edu.whu.zhizhida.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import cn.edu.whu.zhizhida.messaging.RocketTransport;

class MiddlewareReadinessTest {
    final ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
    final ObjectProvider<StringRedisTemplate> submissions = mock(ObjectProvider.class);
    final ObjectProvider<RocketTransport> transport = mock(ObjectProvider.class);

    @Test
    void defaultModeRequiresCoordinationRedis() {
        healthy(submissions);
        var status = new MiddlewareReadiness(redis, submissions, transport, new MockEnvironment())
                .inspect();
        assertThat(status.acceptsRequests()).isTrue();
        assertThat(status.degraded()).isFalse();
        assertThat(status.publisherEnabled()).isFalse();
        verifyNoInteractions(redis, transport);
        verify(submissions).getObject();
    }

    @Test
    void redisWriteFailureClosesSubmissionProtectionAndFallsBackForCache() {
        var env = new MockEnvironment()
                .withProperty("app.cache.questions.enabled", "true")
                .withProperty("app.rate-limit.redis.enabled", "true");
        when(redis.getObject()).thenThrow(new IllegalStateException("OOM secret-address"));
        when(submissions.getObject()).thenThrow(new IllegalStateException("OOM secret-address"));
        var status = new MiddlewareReadiness(redis, submissions, transport, env).inspect();
        assertThat(status.cache()).isEqualTo("fallback");
        assertThat(status.submissions()).isEqualTo("unavailable");
        assertThat(status.acceptsRequests()).isFalse();
        assertThat(status.toString()).doesNotContain("secret-address");
    }

    @Test
    void missingBrokerIsBufferingAndKeepsRecoveryConfigurationVisible() {
        healthy(submissions);
        when(transport.getObject()).thenThrow(new IllegalStateException());
        var status = new MiddlewareReadiness(
                redis,
                submissions,
                transport,
                new MockEnvironment()
                        .withProperty("app.run-dispatch.mode", "rocketmq")
                        .withProperty("interview.runs.worker-enabled", "false"))
                .inspect();
        assertThat(status.dispatch()).isEqualTo("buffering");
        assertThat(status.acceptsRequests()).isTrue();
        assertThat(status.workerEnabled()).isFalse();
        assertThat(status.recoveryEnabled()).isTrue();
    }

    @Test
    void cacheFailureDoesNotCloseHealthySubmissionProtection() {
        healthy(submissions);
        when(redis.getObject()).thenThrow(new IllegalStateException("cache OOM"));
        var status = new MiddlewareReadiness(redis, submissions, transport, enabled()).inspect();
        assertThat(status.cache()).isEqualTo("fallback");
        assertThat(status.submissions()).isEqualTo("up");
        assertThat(status.acceptsRequests()).isTrue();
        assertThat(status.degraded()).isTrue();
    }

    @Test
    void submissionFailureDoesNotDisableHealthyCache() {
        healthy(redis);
        when(submissions.getObject()).thenThrow(new IllegalStateException("rate OOM"));
        var status = new MiddlewareReadiness(redis, submissions, transport, enabled()).inspect();
        assertThat(status.cache()).isEqualTo("up");
        assertThat(status.submissions()).isEqualTo("unavailable");
        assertThat(status.acceptsRequests()).isFalse();
    }

    private MockEnvironment enabled() {
        return new MockEnvironment()
                .withProperty("app.cache.questions.enabled", "true")
                .withProperty("app.rate-limit.redis.enabled", "true");
    }

    private void healthy(ObjectProvider<StringRedisTemplate> provider) {
        var client = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(provider.getObject()).thenReturn(client);
        when(client.opsForValue().get(anyString())).thenReturn("ready");
    }
}
