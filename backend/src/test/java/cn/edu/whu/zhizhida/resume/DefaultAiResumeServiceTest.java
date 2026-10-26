package cn.edu.whu.zhizhida.resume;
import cn.edu.whu.zhizhida.resume.api.AiResumeService;

import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import cn.edu.whu.zhizhida.resume.api.ResumeImprovement;

import cn.edu.whu.zhizhida.resume.api.AiOptimizeResponse;

import cn.edu.whu.zhizhida.resume.api.AiOptimizeRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;



import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultAiResumeService")
class DefaultAiResumeServiceTest {

    @Mock
    private JsonChatClient chatClient;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private AiResumeService mockService() {
        return new MockAiResumeService(objectMapper, BusinessMetrics.disabled());
    }

    private DefaultAiResumeService deepseekService() {
        return new DefaultAiResumeService(chatClient, BusinessMetrics.disabled());
    }


    // 模拟模式测试

    @Nested
    @DisplayName("Mock provider optimize")
    class MockProviderTests {

        @Test
        @DisplayName("should return full response structure with optimizedText, suggestions, improvements, targetKeywords, optimizationSummary")
        void shouldReturnFullResponseStructure() {
            AiOptimizeRequest request = new AiOptimizeRequest(
                    "负责用户报名与签到模块，使用 Java 完成接口设计开发和联调。",
                    "Java 后端工程师"
            );

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.optimizedText()).isNotNull().isNotEmpty();
            assertThat(response.optimizedText()).isEqualTo(request.rawText());
            assertThat(response.optimizedText()).doesNotContain("Spring Boot", "MySQL");

            assertThat(response.suggestions()).isNotNull().hasSize(3);
            assertThat(response.suggestions().get(0)).contains("不要虚构");
            assertThat(response.suggestions().get(1)).contains("真实结果");
            assertThat(response.suggestions().get(2)).contains("mock 优化模式");

            assertThat(response.improvements()).isNotNull().hasSizeGreaterThanOrEqualTo(2);
            assertThat(response.improvements().get(0).category()).isEqualTo("表达结构");
            assertThat(response.improvements())
                    .allSatisfy(improvement -> assertThat(improvement.example())
                            .doesNotContain("Spring Boot", "MySQL", "REST 接口"));

            assertThat(response.targetKeywords()).isNotNull()
                    .containsExactly("Java", "接口设计", "联调");

            assertThat(response.optimizationSummary()).isNotNull().contains("未补充原文未出现");
        }

        @Test
        @DisplayName("frontend role should not inject frontend technologies absent from raw text")
        void shouldNotInjectFrontendKeywords() {
            AiOptimizeRequest request = new AiOptimizeRequest("开发了公司官网前端页面。", "前端开发工程师");

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.optimizedText()).doesNotContain("Vue", "React", "TypeScript");
            assertThat(response.targetKeywords()).isEmpty();
        }

        @Test
        @DisplayName("targetKeywords should contain only skills explicitly present in raw text")
        void shouldExtractOnlyKeywordsPresentInRawText() {
            AiOptimizeRequest request = new AiOptimizeRequest(
                    "参与AI模型训练，使用Python完成实验。",
                    "AI算法工程师"
            );

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.targetKeywords())
                    .containsExactly("AI", "模型训练", "Python")
                    .allSatisfy(keyword -> assertThat(request.rawText()).containsIgnoringCase(keyword));
        }

        @Test
        @DisplayName("keyword extraction should not treat JavaScript as Java")
        void shouldKeepSimilarTechnologyNamesDistinct() {
            AiOptimizeRequest request = new AiOptimizeRequest(
                    "使用 JavaScript 和 Vue3 开发页面。",
                    "前端开发工程师"
            );

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.targetKeywords())
                    .containsExactly("JavaScript", "Vue3")
                    .doesNotContain("Java");
        }

        @Test
        @DisplayName("unknown role without explicit skills should return no target keywords")
        void shouldReturnNoKeywordsForUnknownRole() {
            AiOptimizeRequest request = new AiOptimizeRequest("完成系统后端开发。", "全栈工程师");

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.targetKeywords()).isEmpty();
            assertThat(response.optimizedText()).doesNotContain("Java", "Spring Boot", "MySQL");
        }

        @Test
        @DisplayName("buildMockOptimizedText should produce well-formed output targeting the role")
        void shouldProduceWellFormedMockOptimizedText() {
            AiOptimizeRequest request = new AiOptimizeRequest(
                    "设计并实现了订单管理系统的接口。",
                    "后端开发工程师"
            );

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.optimizedText())
                    .isEqualTo("设计并实现了订单管理系统的接口。");
            assertThat(response.optimizedText()).doesNotContain("Spring Boot", "MySQL");
        }

        @Test
        @DisplayName("optimizedText should preserve responsibility level from raw text")
        void shouldPreserveResponsibilityLevel() {
            AiOptimizeRequest request = new AiOptimizeRequest(
                    "参与用户注册与登录功能。",
                    "Java 后端工程师"
            );

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.optimizedText()).contains("参与用户注册与登录功能");
            assertThat(response.optimizedText()).doesNotContain("负责参与");
        }

        @Test
        @DisplayName("improvements should include quantification suggestion when no digits in rawText")
        void shouldIncludeQuantificationSuggestionWhenNoDigits() {
            AiOptimizeRequest request = new AiOptimizeRequest(
                    "负责系统功能开发和测试。",
                    "Java 后端工程师"
            );

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.improvements()).hasSize(3);
            assertThat(response.improvements().stream().map(ResumeImprovement::category))
                    .contains("成果量化");
        }

        @Test
        @DisplayName("improvements should skip quantification suggestion when digits present in rawText")
        void shouldSkipQuantificationSuggestionWhenDigitsPresent() {
            AiOptimizeRequest request = new AiOptimizeRequest(
                    "完成了 12 个接口的开发，接口响应时间控制在 200ms 内。",
                    "Java 后端工程师"
            );

            AiOptimizeResponse response = mockService().optimize(request);

            assertThat(response.improvements()).hasSize(2);
            assertThat(response.improvements().stream().map(ResumeImprovement::category))
                    .doesNotContain("成果量化");
        }
    }

    private static final String VALID = """
            {"optimizedText":"优化后的内容。","suggestions":["补充真实成果"],
             "improvements":[{"category":"表达","issue":"不具体","action":"明确动作","example":"负责接口开发"}],
             "targetKeywords":["Java"],"optimizationSummary":"突出职责"}
            """;

    @Test
    void modelAdapterUsesGenericClientAndPreservesBusinessFields() throws Exception {
        when(chatClient.completeJson(anyString(), anyString(), eq(0.3), eq(1800)))
                .thenReturn(objectMapper.readTree(VALID));
        AiOptimizeResponse response = deepseekService().optimize(new AiOptimizeRequest("测试内容。", "Java 后端工程师"));
        assertThat(response.optimizedText()).isEqualTo("优化后的内容。");
        assertThat(response.improvements().get(0).action()).isEqualTo("明确动作");
        assertThat(response.targetKeywords()).containsExactly("Java");
        verify(chatClient).completeJson(anyString(), eq("目标岗位：Java 后端工程师\n原始经历：测试内容。"), eq(0.3), eq(1800));
    }

    @Test
    void preservesClientFailure() {
        when(chatClient.completeJson(anyString(), anyString(), eq(0.3), eq(1800)))
                .thenThrow(new IllegalStateException("模型超时"));
        assertThatThrownBy(() -> deepseekService().optimize(new AiOptimizeRequest("经历", "岗位")))
                .isInstanceOf(IllegalStateException.class).hasMessage("模型超时");
    }

    @ParameterizedTest
    @ValueSource(strings = {"optimizedText", "suggestions", "improvements", "targetKeywords", "optimizationSummary"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(VALID);
        payload.remove(field);
        when(chatClient.completeJson(anyString(), anyString(), eq(0.3), eq(1800))).thenReturn(payload);
        assertThatThrownBy(() -> deepseekService().optimize(new AiOptimizeRequest("经历", "岗位")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"optimizedText\":12}", "{\"suggestions\":{}}", "{\"suggestions\":[]}",
            "{\"suggestions\":[12]}", "{\"suggestions\":[\" \"]}", "{\"improvements\":[{}]}",
            "{\"targetKeywords\":[{}]}", "{\"optimizationSummary\":false}"})
    void rejectsWrongTypesAndIncompleteImprovement(String change) throws Exception {
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(VALID);
        payload.setAll((com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(change));
        when(chatClient.completeJson(anyString(), anyString(), eq(0.3), eq(1800))).thenReturn(payload);
        assertThatThrownBy(() -> deepseekService().optimize(new AiOptimizeRequest("经历", "岗位")))
                .isInstanceOf(IllegalStateException.class);
    }
}
