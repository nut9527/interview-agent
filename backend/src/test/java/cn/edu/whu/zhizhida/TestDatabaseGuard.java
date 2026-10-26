package cn.edu.whu.zhizhida;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;

/** 在数据库迁移执行前阻止连接非测试库的应用启动，保护业务数据库。 */
public final class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        requireTestDatabase(context.getEnvironment().getRequiredProperty("spring.datasource.url"));
        if (context.getEnvironment().containsProperty("spring.flyway.url")) {
            throw new IllegalStateException("Integration tests must use the guarded datasource for Flyway");
        }
    }

    static void requireTestDatabase(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("Integration tests require an isolated MySQL schema");
        }
        URI uri = URI.create(jdbcUrl.substring(5));
        if (uri.getUserInfo() != null || uri.getPath() == null
                || !uri.getPath().matches("/zhizhida_test(?:_[a-z0-9]+)?")) {
            throw new IllegalStateException("Integration schema must be named zhizhida_test or zhizhida_test_<run>");
        }
    }
}
