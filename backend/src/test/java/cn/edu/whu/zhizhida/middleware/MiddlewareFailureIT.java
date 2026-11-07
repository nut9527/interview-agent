package cn.edu.whu.zhizhida.middleware;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 启动真实应用与可丢弃的 MySQL；以不可达的 Redis 地址验证真实客户端的故障处理。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.cache.questions.enabled=true", "app.rate-limit.redis.enabled=true",
        "app.redis.cache.host=127.0.0.1", "app.redis.cache.port=1",
        "app.redis.rate-limit.host=127.0.0.1", "app.redis.rate-limit.port=1",
        "app.redis.cache.connect-timeout=100ms", "app.redis.cache.timeout=100ms",
        "app.redis.rate-limit.connect-timeout=100ms", "app.redis.rate-limit.timeout=100ms"})
class MiddlewareFailureIT extends IntegrationTestBase {
    @Autowired QuestionCatalog catalog;
    @Autowired MeterRegistry metrics;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void cacheFallsBackWhileSubmissionReturns503BeforeCreatingRun() throws Exception {
        String token = registerUniqueUserToken("password123", "中间件故障");
        try {
            double before = metrics.counter("zhizhida.question.cache", "outcome", "source").count();
            var me = (Map<?, ?>) parseJsonResult(performGet("/api/users/me", token)).get("data");
            long owner = ((Number) me.get("id")).longValue();
            assertThat(catalog.requirePublishedForExecution(owner, "qb-java-001", null).id()).isEqualTo("qb-java-001");
            assertThat(metrics.counter("zhizhida.question.cache", "outcome", "source").count()).isEqualTo(before + 1);
            assertThat(performGet("/api/questions/qb-java-001", token).getResponse().getStatus()).isEqualTo(200);
            var result = performPost("/api/interviews/start-commands", Map.of("requestId", UUID.randomUUID().toString(),
                    "configuration", Map.of("targetRole", "Java 后端", "totalRounds", 3, "experienceMode", "FORMAL")), token);
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(parseJsonResult(result).get("code")).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_runs WHERE owner_id=?", Long.class, owner)).isZero();
            var readiness = performGet("/api/health/ready");
            assertThat(readiness.getResponse().getStatus()).isEqualTo(503);
            assertThat(readiness.getResponse().getContentAsString()).contains("fallback", "unavailable");
        } finally { performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token); }
    }
}
