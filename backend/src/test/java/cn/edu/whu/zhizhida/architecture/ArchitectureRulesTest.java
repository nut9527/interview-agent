package cn.edu.whu.zhizhida.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 在应用类路径外编译故意越界的示例，避免被 Spring 扫描。 */
class ArchitectureRulesTest {
    @TempDir
    Path temporary;





    @Test
    void coreRulesHandlePrimitiveArrays() throws Exception {
        var good = compile(Map.of("jobs.domain.ArrayRule", "class ArrayRule { int[] values; int value() { return values[0]; } }"));
        ArchitectureRules.CORE_INFRASTRUCTURE.check(good);
        ArchitectureRules.CALCULATION_RULES.check(good);
    }

    @Test
    void calculationRulesRejectServicesClocksAndSpringEvenWithoutADatabase() throws Exception {
        var bad = compile(Map.of(
                "jobs.domain.BadRule", """
                        class BadRule {
                            cn.edu.whu.zhizhida.resume.api.ResumeService service;
                            long now() { return System.currentTimeMillis(); }
                        }
                        """,
                "jobs.domain.BadExtraction", "@org.springframework.stereotype.Service class BadExtraction {}"));
        assertRejected(ArchitectureRules.CALCULATION_RULES, bad, "ResumeService", "System", "Service");
    }

    @Test
    void crossModuleRepositoryMapperAndEntityReferencesAreRejected() throws Exception {
        JavaClasses types = compile(Map.of(
                "resume.PrivateRepository", "public interface PrivateRepository {}",
                "resume.persistence.PrivateMapper", "public interface PrivateMapper {}",
                "resume.persistence.PrivateEntity", "public class PrivateEntity {}",
                "jobs.BadAccess", """
                        class BadAccess {
                            cn.edu.whu.zhizhida.resume.PrivateRepository repository;
                            cn.edu.whu.zhizhida.resume.persistence.PrivateMapper mapper;
                            java.util.List<cn.edu.whu.zhizhida.resume.persistence.PrivateEntity> rows;
                        }
                        """));
        assertRejected(ArchitectureRules.MODULE_BOUNDARIES, types, "PrivateRepository", "PrivateMapper", "PrivateEntity");
    }

    @Test
    void domainRulesRejectDatabaseHttpAndSecurityDependencies() throws Exception {
        JavaClasses types = compile(Map.of("interview.domain.BadRule", """
                class BadRule {
                    javax.sql.DataSource database;
                    org.springframework.security.core.Authentication identity;
                    Object call() { return java.net.http.HttpClient.newHttpClient(); }
                }
                """));
        assertRejected(ArchitectureRules.CORE_INFRASTRUCTURE, types, "DataSource", "Authentication", "HttpClient");
        assertRejected(ArchitectureRules.INTERVIEW_RULES, types, "DataSource", "Authentication", "HttpClient");
    }

    @Test
    void publicApiReferencesCanStillCreateAForbiddenModuleCycle() throws Exception {
        JavaClasses types = compile(Map.of(
                "resume.api.ResumeContract", "public interface ResumeContract { cn.edu.whu.zhizhida.jobs.api.JobContract jobs(); }",
                "jobs.api.JobContract", "public interface JobContract { cn.edu.whu.zhizhida.resume.api.ResumeContract resume(); }"));
        ArchitectureRules.MODULE_BOUNDARIES.check(types);
        assertRejected(ArchitectureRules.NO_MODULE_CYCLES, types, "Cycle detected", "resume", "jobs");
    }

    @Test
    void controllersAreDetectedByNameAndSpringAnnotation() throws Exception {
        JavaClasses types = compile(Map.of(
                "jobs.LocalRepository", "interface LocalRepository {}",
                "jobs.BadController", "class BadController { LocalRepository repository; }",
                "jobs.HttpEndpoint", """
                        @org.springframework.web.bind.annotation.RestController
                        class HttpEndpoint {
                            javax.sql.DataSource database;
                            org.springframework.jdbc.core.JdbcTemplate jdbc;
                        }
                        """));
        assertRejected(ArchitectureRules.CONTROLLER_LAYERS, types, "LocalRepository", "DataSource", "JdbcTemplate");
    }

    @Test
    void apiLeaksAndWorkflowManagementRemainRestricted() throws Exception {
        JavaClasses types = compile(Map.of(
                "resume.PrivateEntity", "public class PrivateEntity {}",
                "resume.api.Leak", "public record Leak(cn.edu.whu.zhizhida.resume.PrivateEntity value) {}",
                "auth.api.AccountLifecycle", "public interface AccountLifecycle {}",
                "jobs.BadAccess", "class BadAccess { cn.edu.whu.zhizhida.auth.api.AccountLifecycle account; }",
                "application.Bypass", "class Bypass { javax.sql.DataSource database; }"));
        assertRejected(ArchitectureRules.MODULE_BOUNDARIES, types, "PrivateEntity", "AccountLifecycle");
        assertRejected(ArchitectureRules.APPLICATION_PERSISTENCE, types, "PrivateEntity", "DataSource");
    }

    @Test
    void modelDependencyDirectionAndNondeterministicInterviewRulesRemainRestricted() throws Exception {
        JavaClasses types = compile(Map.of(
                "ai.api.Client", "public interface Client {}",
                "resume.api.Business", "public interface Business {}",
                "auth.Bypass", "class Bypass { cn.edu.whu.zhizhida.ai.api.Client client; }",
                "jobs.Bypass", "class Bypass { cn.edu.whu.zhizhida.ai.api.Client client; }",
                "interview.Bypass", "class Bypass { cn.edu.whu.zhizhida.ai.api.Client client; }",
                "ai.Provider", "class Provider { cn.edu.whu.zhizhida.resume.api.Business business; }",
                "interview.domain.ClockRule", "class ClockRule { long now() { return System.currentTimeMillis(); } }"));
        assertRejected(ArchitectureRules.MODEL_ADAPTERS, types, "auth.Bypass", "jobs.Bypass", "interview.Bypass", "Provider");
        assertRejected(ArchitectureRules.INTERVIEW_RULES, types, "java.lang.System");
    }

    @Test
    void owningModulePersistenceAndCrossModulePublicContractsAreAllowed() throws Exception {
        JavaClasses types = compile(Map.of(
                "resume.LocalRepository", "interface LocalRepository {}",
                "resume.api.ResumeContract", "public interface ResumeContract {}",
                "resume.LocalService", "class LocalService { LocalRepository repository; }",
                "jobs.ValidController", """
                        class ValidController {
                            cn.edu.whu.zhizhida.resume.api.ResumeContract service;
                            com.fasterxml.jackson.databind.ObjectMapper json;
                            org.springframework.http.ResponseEntity<String> response() {
                                return org.springframework.http.ResponseEntity.ok("ok");
                            }
                        }
                        """));
        ArchitectureRules.MODULE_BOUNDARIES.check(types);
        ArchitectureRules.API_TYPES.check(types);
        ArchitectureRules.CONTROLLER_LAYERS.check(types);
        ArchitectureRules.NO_MODULE_CYCLES.check(types);
    }

    private static void assertRejected(ArchRule rule, JavaClasses types, String... evidence) {
        var result = rule.evaluate(types);
        assertThat(result.hasViolation()).as(rule.getDescription()).isTrue();
        assertThat(result.getFailureReport().toString()).contains(evidence);
    }

    private JavaClasses compile(Map<String, String> sources) throws Exception {
        Path output = Files.createDirectory(temporary.resolve("classes"));
        List<Path> files = new java.util.ArrayList<>();
        for (var source : sources.entrySet()) {
            String name = ArchitectureRules.ROOT + "." + source.getKey();
            Path file = temporary.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + source.getValue());
            files.add(file);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("fixture compilation requires a JDK").isNotNull();
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            StringWriter diagnostics = new StringWriter();
            boolean success = compiler.getTask(diagnostics, manager, null,
                    List.of("--release", "17", "-proc:none", "-classpath", System.getProperty("java.class.path"),
                            "-d", output.toString()), null, manager.getJavaFileObjectsFromPaths(files)).call();
            assertThat(success).as(diagnostics.toString()).isTrue();
        }
        return new ClassFileImporter().importPath(output);
    }
}
