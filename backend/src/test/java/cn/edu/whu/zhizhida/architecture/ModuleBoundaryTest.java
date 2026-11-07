package cn.edu.whu.zhizhida.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 统一导入一次生产代码字节码，所有规则均在常规 Maven 测试阶段执行。 */
class ModuleBoundaryTest {
    @Test void applicationOwnsTheOnlyToolLoop() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("dev.langchain4j.service.AiServices")
                .check(PRODUCTION);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("dev.langchain4j.mcp.McpToolProvider")
                .check(PRODUCTION);
    }
    @Test void langchain4jTypesStayInsideTheModelAdapter() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage(ArchitectureRules.ROOT + ".ai")
                .should().dependOnClassesThat().resideInAnyPackage("dev.langchain4j..")
                .check(PRODUCTION);
    }
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .importPath(productionDirectory());

    private static java.nio.file.Path productionDirectory() {
        try {
            // 直接使用本次编译的生产目录，独立构建目录不依赖 ArchUnit 对 target/test-classes 的路径约定。
            return java.nio.file.Path.of(cn.edu.whu.zhizhida.ZhizhidaApplication.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException ex) { throw new IllegalStateException(ex); }
    }

    @Test
    void productionImportIncludesEveryBusinessModuleAndCoreRules() {
        for (String module : ArchitectureRules.MODULES) {
            assertThat(PRODUCTION.stream().anyMatch(type -> ArchitectureRules.module(type).equals(module)))
                    .as("production module %s must be checked", module).isTrue();
        }
        assertThat(PRODUCTION.contain(ArchitectureRules.ROOT + ".interview.domain.InterviewTransitions")).isTrue();
        assertThat(PRODUCTION.contain(ModuleBoundaryTest.class.getName())).isFalse();
    }

    @Test
    void allowlistsReferenceExistingProductionContractsAndAdapters() {
        for (String name : ArchitectureRules.WORKFLOW_APIS) {
            assertThat(PRODUCTION.stream().filter(type -> type.getSimpleName().equals(name)
                    && type.getPackageName().endsWith(".api")
                    && ArchitectureRules.MODULES.contains(ArchitectureRules.module(type))).count())
                    .as("workflow API %s must still exist in an active module", name).isEqualTo(1);
        }
        for (String name : ArchitectureRules.MODEL_ADAPTER_NAMES) {
            assertThat(PRODUCTION.contain(ArchitectureRules.ROOT + "." + name))
                    .as("model adapter %s must still exist", name).isTrue();
        }
    }

    @Test
    void modulesOnlyUsePublicContracts() {
        ArchitectureRules.MODULE_BOUNDARIES.check(PRODUCTION);
    }

    @Test
    void businessModulesHaveNoCycles() {
        ArchitectureRules.NO_MODULE_CYCLES.check(PRODUCTION);
    }

    @Test
    void exportedTypesAreInterfacesOrDataAndNeverPersistenceTypes() {
        ArchitectureRules.API_TYPES.check(PRODUCTION);
    }

    @Test
    void applicationAndApiDoNotAccessPersistence() {
        ArchitectureRules.APPLICATION_PERSISTENCE.check(PRODUCTION);
    }

    @Test
    void controllersUseServicesInsteadOfPersistenceOrCoreRules() {
        ArchitectureRules.CONTROLLER_LAYERS.check(PRODUCTION);
    }

    @Test
    void coreRulesDoNotDependOnInfrastructure() {
        ArchitectureRules.CORE_INFRASTRUCTURE.check(PRODUCTION);
        ArchitectureRules.CALCULATION_RULES.check(PRODUCTION);
    }

    @Test
    void interviewRulesOnlyDependOnDataAndDeterministicJdkTypes() {
        ArchitectureRules.INTERVIEW_RULES.check(PRODUCTION);
    }

    @Test
    void onlyModelAdaptersAndWiringDependOnGenericModelApi() {
        ArchitectureRules.MODEL_ADAPTERS.check(PRODUCTION);
    }
}
