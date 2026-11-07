package cn.edu.whu.zhizhida.quota;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Comparator;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 基于数据库固定时间窗口的共享额度服务，同时约束请求次数与业务成本。
 * 一次准入的所有计费维度原子提交；任一维度超限或存储失败则整体回滚。
 */
@Service
public class RequestQuotaService {
    private static final Logger log = LoggerFactory.getLogger(RequestQuotaService.class);
    private final JdbcTemplate jdbc;
    private final BusinessMetrics metrics;

    public RequestQuotaService(JdbcTemplate jdbc) {
        this(jdbc, BusinessMetrics.disabled());
    }

    @Autowired
    public RequestQuotaService(JdbcTemplate jdbc, BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    // 一次准入的所有额度变更统一提交或回滚，条件更新负责跨实例的原子限额。
    // 插入或更新操作直接取得已有计数桶的排他锁，避免后续升级锁。
    // 忽略重复插入后再更新可能触发并发共享锁升级，从而形成死锁。
    @Transactional
    public void admit(List<Charge> charges) {
        long started = System.nanoTime();
        try {
            // 使用数据库时间对齐窗口，避免多个应用实例的本地时钟偏差导致分桶不一致。
            long now = Objects.requireNonNull(jdbc.queryForObject("SELECT UNIX_TIMESTAMP()", Long.class));
            // 各额度维度遵循一致的加锁顺序，避免形成循环等待。
            for (Charge charge : charges.stream().sorted(Comparator.comparing(
                    (Charge c) -> hash(c.scope() + ":" + c.subject())).thenComparingLong(Charge::windowSeconds)).toList()) {
                long start = Math.floorDiv(now, charge.windowSeconds()) * charge.windowSeconds();
                String key = hash(charge.scope() + ":" + charge.subject());
                jdbc.update("INSERT INTO request_quotas "
                                + "(scope_key, window_start, used_hits, used_units, expires_at) VALUES (?, ?, 0, 0, ?) "
                                + "ON DUPLICATE KEY UPDATE scope_key = scope_key",
                        key, start, Timestamp.from(Instant.ofEpochSecond(start + charge.windowSeconds() + 86400)));
                int updated = jdbc.update("UPDATE request_quotas SET used_hits = used_hits + 1, "
                                + "used_units = used_units + ? WHERE scope_key = ? AND window_start = ? "
                                + "AND used_hits < ? AND used_units <= ? - ?",
                        charge.units(), key, start, charge.maxHits(), charge.maxUnits(), charge.units());
                if (updated != 1) {
                    long retry = start + charge.windowSeconds() - now;
                    log.info("Quota rejected; scope={} retryAfterSeconds={}", charge.scope(), retry);
                    throw new QuotaExceededException(charge.message(), retry);
                }
            }
            metrics.record(BusinessMetrics.Operation.QUOTA, BusinessMetrics.Outcome.SUCCESS, System.nanoTime() - started);
        } catch (QuotaExceededException ex) {
            metrics.record(BusinessMetrics.Operation.QUOTA, BusinessMetrics.Outcome.REJECTED, System.nanoTime() - started);
            throw ex;
        } catch (DataAccessException ex) {
            metrics.record(BusinessMetrics.Operation.QUOTA, BusinessMetrics.Outcome.FAILURE, System.nanoTime() - started);
            log.error("Quota store unavailable; type={}", ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "额度服务暂不可用，请稍后重试");
        }
    }

    public int deleteExpired(int batchSize) {
        return jdbc.update("DELETE FROM request_quotas WHERE expires_at < NOW() LIMIT ?", batchSize);
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record Charge(String scope, String subject, long windowSeconds, int maxHits,
                         int maxUnits, int units, String message) {
        public Charge {
            if (windowSeconds < 1 || maxHits < 1 || maxUnits < 1 || units < 1 || units > maxUnits) {
                throw new IllegalArgumentException("额度配置必须为正且单次成本不得超过总额度");
            }
        }
    }
}
