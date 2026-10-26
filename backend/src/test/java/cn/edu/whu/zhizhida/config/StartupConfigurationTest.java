package cn.edu.whu.zhizhida.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class StartupConfigurationTest {
    private MockEnvironment environment;

    @BeforeEach
    void loadPackagedConfigurationWithoutPersonalEnv() throws Exception {
        environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
    }

    @Test
    void dockerIsOptInAndDoesNotStopSharedDatabaseOnExit() {
        assertThat(environment.getProperty("spring.docker.compose.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.docker.compose.lifecycle-management")).isEqualTo("start-only");
    }

    @Test
    void composeCredentialsAndPortAlsoConfigureLocalBackend() {
        environment.withProperty("MYSQL_PORT", "3310").withProperty("MYSQL_DATABASE", "local_test")
                .withProperty("MYSQL_USER", "local_user").withProperty("MYSQL_PASSWORD", "local-test-value");
        assertThat(environment.getProperty("spring.datasource.url")).contains("127.0.0.1:3310/local_test?");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("local_user");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("local-test-value");
    }

    @Test
    void externalDatabaseOverridesComposeDefaults() {
        environment.withProperty("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/external")
                .withProperty("SPRING_DATASOURCE_USERNAME", "external_user");
        assertThat(environment.getProperty("spring.datasource.url")).endsWith("3306/external");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("external_user");
    }

    @Test
    void backendPortCanBeConfiguredAndPoolWaitIsBounded() {
        environment.withProperty("BACKEND_PORT", "8181");
        assertThat(environment.getProperty("server.port")).isEqualTo("8181");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
    }

    @Test
    void versionComesFromMavenResourceFiltering() {
        assertThat(environment.getProperty("application.version")).isNotBlank().doesNotContain("@project.version@");
    }
}
