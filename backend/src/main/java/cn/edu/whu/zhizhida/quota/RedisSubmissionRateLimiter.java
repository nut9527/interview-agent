package cn.edu.whu.zhizhida.quota;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

/** 入口尝试计数不回滚；费用与执行预算仍由 MySQL 事务维护。 */
@Component
@ConditionalOnProperty(name="app.rate-limit.redis.enabled", havingValue="true")
public class RedisSubmissionRateLimiter {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("redis/ai-submit.lua"));
        SCRIPT.setResultType(List.class);
    }
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final String prefix;
    private final int limit;
    private final long window;
    public RedisSubmissionRateLimiter(@org.springframework.beans.factory.annotation.Qualifier("submissionRedisTemplate")
            StringRedisTemplate redis, MeterRegistry metrics,
            @Value("${app.redis.namespace:zhizhida:dev}") String namespace,
            @Value("${app.rate-limit.redis.limit:10}") int limit,
            @Value("${app.rate-limit.redis.window-ms:10000}") long window) {
        if(limit<1 || window<1) throw new IllegalArgumentException("Invalid submission rate configuration");
        this.redis=redis; this.metrics=metrics; this.prefix=namespace+":rate:ai-submit:";
        this.limit=limit; this.window=window;
    }
    /** 按可信用户编号计入提交尝试；超限抛出携带等待秒数的配额异常，Redis 故障时拒绝请求以免绕过保护。 */
    public void require(long owner) {
        List<?> result;
        try {
            result=redis.execute(SCRIPT,List.of(prefix+owner),Integer.toString(limit),Long.toString(window));
            if(result==null || result.size()!=2 || !(result.get(0) instanceof Long allowed)
                    || !(result.get(1) instanceof Long ttl) || ttl<0 || (allowed!=0 && allowed!=1))
                throw new IllegalStateException("Invalid rate result");
        } catch(RuntimeException ex) {
            metrics.counter("zhizhida.submission.rate","outcome","unavailable").increment();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"请求保护服务暂时不可用，请稍后重试");
        }
        if(((Long)result.get(0))==0) {
            metrics.counter("zhizhida.submission.rate","outcome","rejected").increment();
            throw new QuotaExceededException("AI 命令提交过于频繁，请稍后重试",Math.max(1,((Long)result.get(1)+999)/1000));
        }
        metrics.counter("zhizhida.submission.rate","outcome","allowed").increment();
    }
}
