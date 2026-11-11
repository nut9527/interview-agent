package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.interview.api.QuestionSnapshot;
import cn.edu.whu.zhizhida.questionbank.domain.QuestionContentRules;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** 只缓存不可变归档；实时身份、上架与版本检查仍由 catalog 完成。 */
@Component
@ConditionalOnProperty(name="app.cache.questions.enabled", havingValue="true")
public class QuestionSnapshotCache {
    record Entry(int schemaVersion, QuestionSnapshot snapshot) { }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final String prefix;
    private final ConcurrentHashMap<String, CompletableFuture<QuestionSnapshot>> loads = new ConcurrentHashMap<>();

    public QuestionSnapshotCache(@org.springframework.beans.factory.annotation.Qualifier("questionCacheRedisTemplate")
            StringRedisTemplate redis, ObjectMapper json, MeterRegistry metrics,
            @Value("${app.redis.namespace:zhizhida:dev}") String namespace) {
        this.redis=redis; this.json=json; this.metrics=metrics;
        this.prefix=namespace+":question:snapshot:s1:";
    }

    public QuestionSnapshot read(String id, int version, Supplier<QuestionSnapshot> source) {
        String key=prefix+id+":v"+version;
        var cached = cached(key, id, version);
        if (cached != null) return cached;

        var load = new CompletableFuture<QuestionSnapshot>();
        var active = loads.putIfAbsent(key, load);
        if (active != null) {
            // 已持有数据库连接的调用不能等待另一个可能正在借连接的回源线程。
            if (!active.isDone() && TransactionSynchronizationManager.isActualTransactionActive())
                return fromSource(id, version, source);
            try {
                return active.join();
            } catch (CompletionException ex) {
                if (ex.getCause() instanceof RuntimeException cause) throw cause;
                if (ex.getCause() instanceof Error cause) throw cause;
                throw ex;
            }
        }
        try {
            var snapshot = fromSource(id, version, source);
            load.complete(snapshot);
            String encoded = json.writeValueAsString(new Entry(1,snapshot));
            try {
                redis.opsForValue().set(key,encoded,
                        Duration.ofSeconds(1200+ThreadLocalRandom.current().nextInt(121)));
            } catch(DataAccessException ex) { count("write_error"); }
            return snapshot;
        } catch (RuntimeException | Error ex) {
            load.completeExceptionally(ex);
            throw ex;
        } finally {
            loads.remove(key, load);
        }
    }
    private QuestionSnapshot cached(String key, String id, int version) {
        String value;
        try { value = redis.opsForValue().get(key); }
        catch (DataAccessException ex) { count("read_error"); return null; }
        if (value == null) { count("miss"); return null; }
        Entry entry;
        try {
            entry = json.readValue(value, Entry.class);
            if (entry == null || entry.schemaVersion() != 1) throw new IllegalArgumentException("CACHE_SCHEMA");
            validate(entry.snapshot(), id, version);
        } catch (JacksonException | IllegalArgumentException ex) { count("corrupt"); return null; }
        count("hit");
        return entry.snapshot();
    }
    private QuestionSnapshot fromSource(String id, int version, Supplier<QuestionSnapshot> source) {
        count("source");
        var snapshot = source.get();
        validate(snapshot, id, version);
        return snapshot;
    }
    private static void validate(QuestionSnapshot snapshot,String id,int version) {
        if(snapshot==null || !id.equals(snapshot.id()) || version!=snapshot.version()
                || snapshot.topic()==null || snapshot.sourceType()==null)
            throw new IllegalArgumentException("CACHE_IDENTITY");
        QuestionContentRules.validate(snapshot);
    }
    private void count(String outcome) { metrics.counter("zhizhida.question.cache","outcome",outcome).increment(); }
}
