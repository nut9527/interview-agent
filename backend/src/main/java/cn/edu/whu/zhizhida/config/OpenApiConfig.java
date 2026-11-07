package cn.edu.whu.zhizhida.config;

import cn.edu.whu.zhizhida.common.ErrorCode;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Configuration
@PropertySource("classpath:openapi.properties")
public class OpenApiConfig {
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/register", "/api/auth/login",
            "/api/auth/demo", "/api/auth/logout", "/api/health", "/api/health/ready");

    @Bean
    ModelResolver stableSchemaNames() {
        // 对外契约沿用二进制类名，避免生成器升级将内部类的 $ 改为 .。
        var names = new TypeNameResolver() {
            @Override
            protected String getNameOfClass(Class<?> type) {
                return type.getName();
            }
        };
        names.setUseFqn(true);
        return new ModelResolver(Json.mapper(), names) {
            @Override
            public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
                if (type.getType() != null && objectMapper().constructType(type.getType())
                        .hasRawClass(tools.jackson.databind.JsonNode.class)) {
                    // 历史 diagnostic 是任意 JSON，不应导出 Jackson 3 的内部访问器。
                    String name = "com.fasterxml.jackson.databind.JsonNode";
                    context.defineModel(name, new ObjectSchema());
                    return new Schema<>().$ref("#/components/schemas/" + name);
                }
                return super.resolve(type, context, chain);
            }
        };
    }

    @Bean
    OpenAPI applicationOpenApi() {
        return new OpenAPI().info(new Info().title("职智达 API").version("1"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer")));
    }

    @Bean
    OpenApiCustomizer responseContracts() {
        return api -> {
            var code = new StringSchema();
            code.setEnum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
            api.getComponents().addSchemas("ErrorCode", code);
            api.getComponents().addSchemas("ErrorResponse", new ObjectSchema()
                    .addProperty("success", new BooleanSchema()._enum(List.of(false)))
                    .addProperty("message", new StringSchema())
                    .addProperty("data", new Schema<>().nullable(true))
                    .addProperty("code", new Schema<>().$ref("#/components/schemas/ErrorCode"))
                    .required(List.of("success", "message", "data", "code")));
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                if (!PUBLIC_PATHS.contains(path)) operation.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
                for (String status : List.of("400", "401", "403", "404", "405", "409", "415", "429", "500", "501", "502", "503")) {
                    if (!operation.getResponses().containsKey(status)) operation.getResponses().addApiResponse(status,
                            new ApiResponse().description("统一错误响应；code 稳定，message 用于展示")
                                    .content(new Content().addMediaType("application/json", new MediaType()
                                            .schema(new Schema<>().$ref("#/components/schemas/ErrorResponse")))));
                }
            }));
        };
    }
}
