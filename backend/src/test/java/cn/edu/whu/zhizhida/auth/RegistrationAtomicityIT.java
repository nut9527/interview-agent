package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationAtomicityIT extends IntegrationTestBase {
    @MockitoSpyBean SessionRepository sessions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sessionFailureRollsBackNewAccountSoRegistrationCanBeRetried() throws Exception {
        String username = "atomic_" + UUID.randomUUID().toString().substring(0, 8);
        doThrow(new DataAccessResourceFailureException("injected session failure"))
                .when(sessions).create(anyString(), anyLong(), any());
        assertThat(registerUser(username, "password123", "atomicity").getResponse().getStatus()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username = ?", Long.class, username)).isZero();
        reset(sessions);
        assertThat(registerUser(username, "password123", "atomicity").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reservedDemoIdentityCannotBeRegistered() throws Exception {
        assertThat(registerUser("DEMO", "password123", "ordinary user").getResponse().getStatus()).isEqualTo(409);
    }
}
