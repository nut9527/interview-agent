package cn.edu.whu.zhizhida.quota;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequestQuotaServiceTest {
    private static final RequestQuotaService.Charge CHARGE = new RequestQuotaService.Charge(
            "test", "subject", 60, 2, 2, 1, "额度已用完");

    @Test
    void exhaustedBucketProduces429WithRetryWindow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT UNIX_TIMESTAMP()", Long.class))
                .thenReturn(Instant.now().getEpochSecond());
        when(jdbc.update(startsWith("UPDATE request_quotas"), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        RequestQuotaService service = new RequestQuotaService(jdbc);

        assertThatThrownBy(() -> service.admit(List.of(CHARGE)))
                .isInstanceOfSatisfying(QuotaExceededException.class, ex -> {
                    assertThat(ex.retryAfterSeconds()).isBetween(1L, 60L);
                    assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
    }

    @Test
    void unavailableStoreFailsClosedWith503() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT UNIX_TIMESTAMP()", Long.class))
                .thenReturn(Instant.now().getEpochSecond());
        when(jdbc.update(startsWith("INSERT INTO"), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> new RequestQuotaService(jdbc).admit(List.of(CHARGE)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
