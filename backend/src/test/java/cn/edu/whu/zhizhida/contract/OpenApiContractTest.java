package cn.edu.whu.zhizhida.contract;

import cn.edu.whu.zhizhida.config.OpenApiConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实路由映射、Jackson 模型和文档生成器，仅模拟控制器的业务依赖。 */
@SpringBootTest(classes = OpenApiContractTest.WebApplication.class,
        properties = "spring.config.location=classpath:/application-contract.yml")
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractTest {
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.test.context.TestComponent
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class,
            org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration.class})
    @ComponentScan(basePackages = "cn.edu.whu.zhizhida", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
                    classes = {RestController.class, RestControllerAdvice.class}))
    @Import(OpenApiConfig.class)
    static class WebApplication {
        @Bean
        static BeanFactoryPostProcessor controllerCollaborators() {
            return factory -> {
                // 自动纳入新增控制器，不启动其数据库或模型依赖。
                for (String name : factory.getBeanDefinitionNames()) {
                    String className = factory.getBeanDefinition(name).getBeanClassName();
                    if (className == null || !className.startsWith("cn.edu.whu.zhizhida.")) continue;
                    try {
                        Class<?> type = Class.forName(className);
                        if (!type.isAnnotationPresent(RestController.class)) continue;
                        for (var constructor : type.getConstructors()) for (var parameter : constructor.getParameters()) {
                            if (parameter.isAnnotationPresent(Value.class)) continue;
                            Class<?> dependency = parameter.getType();
                            if (!factory.containsSingleton(dependency.getName()))
                                factory.registerSingleton(dependency.getName(), mock(dependency));
                        }
                    } catch (ClassNotFoundException ex) { throw new IllegalStateException(ex); }
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void servesStandaloneKnife4jUiAndItsOpenApiConfiguration() throws Exception {
        mvc.perform(get("/doc.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    }

    @Test
    void exportsEveryApiOperationAndMatchesReviewedSnapshot() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode document = (ObjectNode) mapper.readTree(json);
        document.remove("servers"); // 请求主机不属于可跨环境复用的接口契约。
        var operations = new java.util.HashSet<String>();
        document.path("paths").properties().forEach(path -> path.getValue().propertyNames()
                .forEach(method -> operations.add(method.toUpperCase() + " " + path.getKey())));
        mappings.getHandlerMethods().forEach((mapping, handler) -> mapping.getPatternValues().stream()
                .filter(path -> path.startsWith("/api/")).forEach(path -> mapping.getMethodsCondition().getMethods()
                        .forEach(method -> assertThat(operations).contains(method.name() + " " + path))));
        assertThat(operations.size()).isGreaterThan(25);
        Path exported = Path.of("target/openapi.json");
        Files.writeString(exported, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(document)) + "\n");
        Path baseline = Path.of("../contracts/openapi.json");
        if (Boolean.getBoolean("contract.update")) {
            Files.createDirectories(baseline.getParent());
            Files.copy(exported, baseline, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        assertThat(Files.exists(baseline)).as("Export once with -Dcontract.update=true; review the snapshot").isTrue();
        assertThat(mapper.readTree(exported.toFile())).as("OpenAPI drift: review and regenerate contracts/openapi.json")
                .isEqualTo(mapper.readTree(baseline.toFile()));
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
            return mapper.valueToTree(fields);
        }
        if (node.isArray()) {
            var array = mapper.createArrayNode();
            node.forEach(value -> array.add(sorted(value)));
            return array;
        }
        return node;
    }
}
