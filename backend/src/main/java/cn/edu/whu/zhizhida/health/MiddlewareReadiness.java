package cn.edu.whu.zhizhida.health;

import java.time.Duration;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

/** 协调 Redis 始终必需；其余依赖按配置探测，对外不返回地址、凭据或驱动错误。 */
@Component
public class MiddlewareReadiness {
    public record Status(String cache, String submissions, String dispatch,
                         boolean workerEnabled, boolean publisherEnabled, boolean recoveryEnabled) {
        // 限流不可用时拒绝准入；缓存可回源，消息派发可由持久 outbox 缓冲。
        public boolean acceptsRequests() { return !"unavailable".equals(submissions); }
        public boolean degraded() { return "fallback".equals(cache) || "buffering".equals(dispatch); }
    }
    private final ObjectProvider<StringRedisTemplate> cacheRedis, submissionRedis;
    private final ObjectProvider<RabbitTemplate> rabbit;
    private final boolean cacheEnabled, rateEnabled, worker, publisher, recovery;
    private final String mode, probeKey;
    private final Environment environment;

    public MiddlewareReadiness(
                               @org.springframework.beans.factory.annotation.Qualifier("questionCacheRedisTemplate") ObjectProvider<StringRedisTemplate> cacheRedis,
                               @org.springframework.beans.factory.annotation.Qualifier("submissionRedisTemplate") ObjectProvider<StringRedisTemplate> submissionRedis,
                               ObjectProvider<RabbitTemplate> rabbit,
                               Environment env) {
        this.environment = env;
        this.cacheRedis = cacheRedis;
        this.submissionRedis = submissionRedis;
        this.rabbit = rabbit;
        cacheEnabled = env.getProperty("app.cache.questions.enabled", Boolean.class, false);
        rateEnabled = env.getProperty("app.rate-limit.redis.enabled", Boolean.class, false);
        mode = env.getProperty("app.run-dispatch.mode", "database");
        worker = env.getProperty("interview.runs.worker-enabled", Boolean.class, true);
        publisher = "rabbitmq".equals(mode) && env.getProperty("app.outbox.publisher.enabled", Boolean.class, true);
        recovery = "rabbitmq".equals(mode) && env.getProperty("app.run-recovery.enabled", Boolean.class, true);
        probeKey = env.getProperty("app.redis.namespace", "zhizhida:dev") + ":health:" + UUID.randomUUID();
    }

    public Status inspect() {
        boolean cacheReady = !cacheEnabled || probe(cacheRedis, ":cache");
        boolean submissionsReady = probe(submissionRedis, ":submissions");
        String dispatch = "database";
        if ("rabbitmq".equals(mode)) {
            try {
                boolean reachable = Boolean.TRUE.equals(rabbit.getObject().execute(channel -> channel.isOpen()));
                dispatch = reachable ? "up" : "buffering";
            } catch (RuntimeException ex) { dispatch = "buffering"; }
        }
        return new Status(cacheEnabled ? (cacheReady ? "up" : "fallback") : "disabled",
                submissionsReady ? "up" : "unavailable", dispatch, worker, publisher, recovery);
    }

    private boolean probe(ObjectProvider<StringRedisTemplate> provider, String purpose) {
        try {
            // PING 无法发现内存耗尽或只读副本；分别读写短期探测键以确认各资源可用。
            var client = provider.getObject();
            client.opsForValue().set(probeKey + purpose, "ready", Duration.ofSeconds(5));
            return "ready".equals(client.opsForValue().get(probeKey + purpose));
        } catch (RuntimeException ex) { return false; }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportMode() {
        LoggerFactory.getLogger(MiddlewareReadiness.class).info(
                "Runtime role={} dispatch={} questionCache={} submissionRateLimit={} worker={} outboxPublisher={} recovery={} sharedModelConcurrency={} knowledgeDispatch={} importWorker={} indexWorker={} outboxIntervalMs={} outboxBatchSize={} outboxBatchBudgetMs={}",
                environment.getProperty("app.runtime.role", "all"), mode, cacheEnabled, rateEnabled, worker, publisher, recovery,
                environment.getProperty("ai.resilience.max-concurrent-calls", "4"),
                environment.getProperty("knowledge.dispatch.mode","local"),environment.getProperty("knowledge.import.worker-enabled","true"),
                environment.getProperty("agent.vector.worker-enabled","true"),
                environment.getProperty("app.outbox.publisher.interval-ms","500"),
                environment.getProperty("app.outbox.publisher.batch-size","8"),
                environment.getProperty("app.outbox.publisher.batch-budget-ms","100"));
    }
}
