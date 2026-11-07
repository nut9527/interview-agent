package cn.edu.whu.zhizhida;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TestDatabaseGuardTest {
    @Test
    void rejectsDevelopmentDatabaseAndAlternateJdbcDrivers() {
        assertThatThrownBy(() -> TestDatabaseGuard.requireTestDatabase("jdbc:mysql://localhost:3307/zhizhida"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseGuard.requireTestDatabase("jdbc:h2:mem:zhizhida_test"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseGuard.requireTestDatabase("jdbc:mysql://localhost/zhizhida_test/other"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsLocalAndCiIsolatedSchemas() {
        assertThatCode(() -> TestDatabaseGuard.requireTestDatabase("jdbc:mysql://127.0.0.1:13307/zhizhida_test_a12?useSSL=false"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TestDatabaseGuard.requireTestDatabase("jdbc:mysql://mysql:3306/zhizhida_test"))
                .doesNotThrowAnyException();
    }
}
