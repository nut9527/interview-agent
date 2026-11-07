package cn.edu.whu.zhizhida.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    @Test
    void rootAndBackendWorkingDirectoriesLoadTheSameEnv(@TempDir Path fixture) throws Exception {
        var root = Files.createDirectory(fixture.resolve("project"));
        var backend = Files.createDirectory(root.resolve("backend"));
        Files.writeString(root.resolve(".env"), "BACKEND_PORT=8182\n");

        assertLoadedPort(root, Map.of(), "8182");
        assertLoadedPort(backend, Map.of(), "8182");
        Files.writeString(backend.resolve(".env"), "BACKEND_PORT=8183\n");
        assertLoadedPort(backend, Map.of(), "8183");
    }

    @Test
    void missingEnvIsOptionalAndEnvironmentVariablesStillWin(@TempDir Path fixture) throws Exception {
        var root = Files.createDirectory(fixture.resolve("project"));
        assertLoadedPort(root, Map.of(), "8080");
        Files.writeString(root.resolve(".env"), "BACKEND_PORT=8182\n");
        assertLoadedPort(root, Map.of("BACKEND_PORT", "8184"), "8184");
    }

    @Test
    void explicitAbsoluteImportWorksFromAnUnrelatedDirectory(@TempDir Path fixture) throws Exception {
        var root = Files.createDirectory(fixture.resolve("project"));
        var config = Files.writeString(root.resolve(".env"), "BACKEND_PORT=8185\n");
        var unrelated = Files.createDirectory(fixture.resolve("service"));
        Files.writeString(unrelated.resolve(".env"), "BACKEND_PORT=8186\n");
        assertLoadedPort(unrelated, Map.of("SPRING_CONFIG_IMPORT", config.toUri() + "[.properties]"), "8185");
    }

    private void assertLoadedPort(Path workingDirectory, Map<String, String> variables, String expected) throws Exception {
        // A fresh JVM changes the real working directory without touching the user's .env or starting any beans.
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var arguments = Files.createTempFile(workingDirectory, "config-probe-", ".args");
        var output = Files.createTempFile(workingDirectory, "config-probe-", ".log");
        Files.writeString(arguments, "-XX:-UsePerfData\n\"-Djava.io.tmpdir=" + workingDirectory.toString().replace('\\', '/') + "\"\n-cp\n\""
                + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n" + ConfigProbe.class.getName() + "\n");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
                .directory(workingDirectory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().clear();
        if (System.getenv("SystemRoot") != null) builder.environment().put("SystemRoot", System.getenv("SystemRoot"));
        builder.environment().putAll(variables);
        builder.environment().put("TEMP", workingDirectory.toString());
        builder.environment().put("TMP", workingDirectory.toString());
        var process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("configuration probe finished").isTrue();
            String text = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage(text).isZero();
            assertThat(text.trim()).isEqualTo(expected);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static class ConfigProbe {
        public static void main(String[] args) {
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("probeLocation",
                    Map.of("spring.config.location", "classpath:/application.yml")));
            ConfigDataEnvironmentPostProcessor.applyTo(environment);
            System.out.println(environment.getProperty("server.port"));
        }
    }
}
