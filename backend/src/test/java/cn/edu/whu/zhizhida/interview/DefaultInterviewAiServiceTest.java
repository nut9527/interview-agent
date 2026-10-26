package cn.edu.whu.zhizhida.interview;
import cn.edu.whu.zhizhida.interview.api.InterviewAiService;

import cn.edu.whu.zhizhida.interview.api.InterviewAiReply;

import cn.edu.whu.zhizhida.interview.api.InterviewTurn;

import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultInterviewAiServiceTest {

    @Mock
    private JsonChatClient chatClient;

    private ObjectMapper objectMapper;
    private DefaultInterviewAiService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new DefaultInterviewAiService(chatClient, objectMapper, BusinessMetrics.disabled());
    }

    @Test
    void shouldGenerateOpeningQuestionFromStructuredDeepSeekOutput() throws Exception {
        when(chatClient.completeJson(contains("合法 json"), contains("Java 后端工程师"), anyDouble(), anyInt()))
                .thenReturn(objectMapper.readTree("{\"question\":\"请介绍你负责的订单接口优化，并说明最终结果。\"}"));

        String question = service.openingQuestion(
                "Java 后端工程师",
                "负责 Spring Boot 项目"
        );

        assertThat(question).isEqualTo("请介绍你负责的订单接口优化，并说明最终结果。");
    }

    @Test
    void shouldReturnFeedbackAndFollowUpBeforeFinalRound() throws Exception {
        when(chatClient.completeJson(contains("完整面试记录"), contains("接口耗时降低"), anyDouble(), anyInt()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "feedback": "优点是给出了结果；建议补充个人决策过程。",
                          "nextQuestion": "你是如何定位性能瓶颈的？",
                          "finalSummary": ""
                        }
                        """));

        InterviewAiReply reply = service.reviewAnswer(
                "Java 后端工程师",
                "项目经历：订单接口优化",
                List.of(new InterviewTurn("user", "接口耗时降低了 30%")),
                1,
                false
        );

        assertThat(reply.feedback()).contains("给出了结果");
        assertThat(reply.nextQuestion()).isEqualTo("你是如何定位性能瓶颈的？");
        assertThat(reply.finalSummary()).isEmpty();
    }

    @Test
    void shouldRejectMalformedFinalRoundOutput() throws Exception {
        when(chatClient.completeJson(contains("完整面试记录"), contains("finalRound"), anyDouble(), anyInt()))
                .thenReturn(objectMapper.readTree("{\"feedback\":\"回答较完整\",\"finalSummary\":\"\"}"));

        assertThatThrownBy(() -> service.reviewAnswer(
                "Java 后端工程师",
                "技能：Java；MySQL",
                List.of(new InterviewTurn("user", "我会先查看监控指标并复现问题。")),
                3,
                true
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finalSummary");
    }

    // 模拟供应商测试

    @Nested
    @DisplayName("Mock provider")
    class MockProviderTests {

        private InterviewAiService mockService;

        @BeforeEach
        void setUp() {
            mockService = new MockInterviewAiService(new ObjectMapper(), BusinessMetrics.disabled());
        }

        @Test
        @DisplayName("openingQuestion with resume text should include resume reference")
        void shouldGenerateOpeningWithResume() {
            String question = mockService.openingQuestion("Java 后端工程师",
                    "负责 Spring Boot 项目开发");

            assertThat(question).contains("结合你提供的简历");
            assertThat(question).contains("Java 后端工程师");
            assertThat(question).contains("具体职责");
        }

        @Test
        @DisplayName("openingQuestion with blank resume should use generic intro format")
        void shouldGenerateOpeningWithoutResume() {
            String question = mockService.openingQuestion("前端开发工程师", "");

            assertThat(question).contains("前端开发工程师");
            assertThat(question).startsWith("请用 1 分钟介绍");
            assertThat(question).doesNotContain("结合你提供的简历");
        }

        @Test
        @DisplayName("openingQuestion with null resume should use generic intro format")
        void shouldGenerateOpeningWithNullResume() {
            String question = mockService.openingQuestion("AI算法工程师", null);

            assertThat(question).contains("AI算法工程师");
            assertThat(question).startsWith("请用 1 分钟介绍");
            assertThat(question).doesNotContain("简历");
        }

        @Test
        @DisplayName("openingQuestion with whitespace-only resume should use generic intro format")
        void shouldGenerateOpeningWithWhitespaceOnlyResume() {
            String question = mockService.openingQuestion("数据分析师", "   ");

            assertThat(question).contains("数据分析师");
            assertThat(question).startsWith("请用 1 分钟介绍");
        }

        @Test
        @DisplayName("reviewAnswer with empty turns list should still produce feedback")
        void shouldReviewWithEmptyTranscript() {
            InterviewAiReply reply = mockService.reviewAnswer(
                    "Java 后端工程师",
                    "项目经历",
                    List.of(),
                    1,
                    false
            );

            assertThat(reply.feedback()).isNotNull().isNotEmpty();
            assertThat(reply.nextQuestion()).isNotNull().isNotEmpty();
            assertThat(reply.finalSummary()).isEmpty();
        }

        @Test
        @DisplayName("reviewAnswer final round should return finalSummary and empty nextQuestion")
        void shouldReturnFinalSummaryOnLastRound() {
            List<InterviewTurn> transcript = List.of(
                    new InterviewTurn("interviewer", "请介绍你的项目"),
                    new InterviewTurn("user", "我负责开发订单系统，处理日均 1000+ 订单"),
                    new InterviewTurn("interviewer", "遇到的难点是什么"),
                    new InterviewTurn("user", "高并发下的数据一致性保障"),
                    new InterviewTurn("interviewer", "如何改进"),
                    new InterviewTurn("user", "引入分布式事务和缓存预热机制")
            );

            InterviewAiReply reply = mockService.reviewAnswer(
                    "Java 后端工程师",
                    "项目经历",
                    transcript,
                    3,
                    true
            );

            assertThat(reply.feedback()).isNotNull().isNotEmpty();
            assertThat(reply.nextQuestion()).isEmpty();
            assertThat(reply.finalSummary()).isNotNull().isNotEmpty();
            assertThat(reply.finalSummary()).contains("本轮面试结束");
            assertThat(reply.finalSummary()).contains("STAR 结构");
        }

        @Test
        @DisplayName("reviewAnswer first answer should produce specific follow-up question")
        void shouldProduceFollowUpForFirstAnswer() {
            List<InterviewTurn> transcript = List.of(
                    new InterviewTurn("interviewer", "请介绍项目"),
                    new InterviewTurn("user", "完成了 5 个接口的开发与测试。")
            );

            InterviewAiReply reply = mockService.reviewAnswer(
                    "Java 后端工程师",
                    "项目经历",
                    transcript,
                    1,
                    false
            );

            assertThat(reply.nextQuestion()).contains("最大难点");
            assertThat(reply.finalSummary()).isEmpty();
        }

        @Test
        @DisplayName("reviewAnswer second/subsequent answer should produce improvement-focus question")
        void shouldProduceImprovementQuestionForSubsequentAnswer() {
            List<InterviewTurn> transcript = List.of(
                    new InterviewTurn("interviewer", "请介绍项目"),
                    new InterviewTurn("user", "负责订单系统开发"),
                    new InterviewTurn("interviewer", "最大难点是什么"),
                    new InterviewTurn("user", "高并发场景下的数据库锁优化")
            );

            InterviewAiReply reply = mockService.reviewAnswer(
                    "Java 后端工程师",
                    "项目经历",
                    transcript,
                    2,
                    false
            );

            assertThat(reply.nextQuestion()).contains("改进");
            assertThat(reply.finalSummary()).isEmpty();
        }

        @Test
        @DisplayName("mock feedback should detect short answers")
        void shouldDetectShortAnswerInFeedback() {
            List<InterviewTurn> transcript = List.of(
                    new InterviewTurn("user", "好的。")
            );

            InterviewAiReply reply = mockService.reviewAnswer(
                    "Java 后端工程师",
                    "",
                    transcript,
                    1,
                    false
            );

            assertThat(reply.feedback()).contains("信息量偏少");
        }

        @Test
        @DisplayName("mock feedback should detect quantified answers")
        void shouldDetectQuantifiedAnswerInFeedback() {
            List<InterviewTurn> transcript = List.of(
                    new InterviewTurn("user", "完成了12个接口，响应控制在200ms内。")
            );

            InterviewAiReply reply = mockService.reviewAnswer(
                    "Java 后端工程师",
                    "",
                    transcript,
                    1,
                    false
            );

            assertThat(reply.feedback()).contains("使用了量化信息");
        }

        @Test
        @DisplayName("mock feedback should detect action verbs in answer")
        void shouldDetectActionVerbsInFeedback() {
            List<InterviewTurn> transcript = List.of(
                    new InterviewTurn("user", "负责设计并实现了核心模块的优化方案。")
            );

            InterviewAiReply reply = mockService.reviewAnswer(
                    "Java 后端工程师",
                    "",
                    transcript,
                    1,
                    false
            );

            assertThat(reply.feedback()).contains("能够体现个人行动");
        }

        @Test
        @DisplayName("mock final summary should count quantified answers")
        void shouldCountQuantifiedAnswersInFinalSummary() {
            List<InterviewTurn> transcript = List.of(
                    new InterviewTurn("user", "完成了 10 个接口开发"),
                    new InterviewTurn("user", "降低了延迟"),
                    new InterviewTurn("user", "修复了 20 个缺陷")
            );

            InterviewAiReply reply = mockService.reviewAnswer(
                    "Java 后端工程师",
                    "",
                    transcript,
                    3,
                    true
            );

            assertThat(reply.finalSummary()).contains("2 个回答包含量化信息");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"feedback\":123,\"nextQuestion\":\"下一题\"}",
            "{\"feedback\":\"即时反馈：\",\"nextQuestion\":\"下一题\"}",
            "{\"feedback\":\"有动作，补充方法\",\"nextQuestion\":{}}"})
    void rejectsWrongTypesAndEmptyNormalizedFeedback(String json) throws Exception {
        when(chatClient.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(objectMapper.readTree(json));
        assertThatThrownBy(() -> service.reviewAnswer("后端", "简历", List.of(new InterviewTurn("user", "回答")), 1, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsOversizedOpeningAndNumericQuestion() throws Exception {
        when(chatClient.completeJson(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(objectMapper.createObjectNode().put("question", "题".repeat(301)))
                .thenReturn(objectMapper.createObjectNode().put("question", 123));
        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> service.openingQuestion("后端", "简历"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("question");
    }

    // DeepSeek 边界测试

    @Nested
    @DisplayName("DeepSeek edge cases")
    class DeepseekEdgeCaseTests {

        @BeforeEach
        void setUpStub() {
            // 复用外层测试类提供的客户端和业务服务。
        }

        @Test
        @DisplayName("openingQuestion should throw when DeepSeek returns empty question")
        void shouldThrowOnEmptyOpeningQuestion() throws Exception {
            when(chatClient.completeJson(contains("合法 json"), contains("后端"), anyDouble(), anyInt()))
                    .thenReturn(objectMapper.readTree("{\"question\":\"\"}"));

            assertThatThrownBy(() -> service.openingQuestion(
                    "Java 后端工程师",
                    "项目经历"
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("question");
        }

        @Test
        @DisplayName("openingQuestion should throw when DeepSeek returns missing question field")
        void shouldThrowOnMissingQuestionField() throws Exception {
            when(chatClient.completeJson(contains("合法 json"), contains("后端"), anyDouble(), anyInt()))
                    .thenReturn(objectMapper.readTree("{}"));

            assertThatThrownBy(() -> service.openingQuestion(
                    "Java 后端工程师",
                    "项目经历"
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("question");
        }

        @Test
        @DisplayName("reviewAnswer should throw when feedback field is empty")
        void shouldThrowOnEmptyFeedback() throws Exception {
            when(chatClient.completeJson(contains("完整面试记录"), anyString(), anyDouble(), anyInt()))
                    .thenReturn(objectMapper.readTree("""
                            {
                              "feedback": "",
                              "nextQuestion": "请继续阐述",
                              "finalSummary": ""
                            }
                            """));

            assertThatThrownBy(() -> service.reviewAnswer(
                    "Java 后端工程师",
                    "",
                    List.of(new InterviewTurn("user", "回答内容")),
                    1,
                    false
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("feedback");
        }

        @Test
        @DisplayName("reviewAnswer non-final round should throw when nextQuestion is empty")
        void shouldThrowOnEmptyNextQuestionInNonFinalRound() throws Exception {
            when(chatClient.completeJson(contains("完整面试记录"), anyString(), anyDouble(), anyInt()))
                    .thenReturn(objectMapper.readTree("""
                            {
                              "feedback": "整体表现良好，建议补充细节。",
                              "nextQuestion": "",
                              "finalSummary": ""
                            }
                            """));

            assertThatThrownBy(() -> service.reviewAnswer(
                    "Java 后端工程师",
                    "",
                    List.of(new InterviewTurn("user", "回答内容")),
                    1,
                    false
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nextQuestion");
        }

        @Test
        @DisplayName("reviewAnswer non-final round should throw when nextQuestion field is missing")
        void shouldThrowOnMissingNextQuestionField() throws Exception {
            when(chatClient.completeJson(contains("完整面试记录"), anyString(), anyDouble(), anyInt()))
                    .thenReturn(objectMapper.readTree("""
                            {
                              "feedback": "整体表现良好。"
                            }
                            """));

            assertThatThrownBy(() -> service.reviewAnswer(
                    "Java 后端工程师",
                    "",
                    List.of(new InterviewTurn("user", "回答内容")),
                    1,
                    false
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nextQuestion");
        }

        @Test
        @DisplayName("feedback field should strip '即时反馈：' prefix")
        void shouldStripFeedbackPrefix() throws Exception {
            when(chatClient.completeJson(contains("完整面试记录"), anyString(), anyDouble(), anyInt()))
                    .thenReturn(objectMapper.readTree("""
                            {
                              "feedback": "即时反馈：优点是结构清晰；建议补充量化结果。",
                              "nextQuestion": "请继续说明细节。",
                              "finalSummary": ""
                            }
                            """));

            InterviewAiReply reply = service.reviewAnswer(
                    "Java 后端工程师",
                    "",
                    List.of(new InterviewTurn("user", "回答内容")),
                    1,
                    false
            );

            assertThat(reply.feedback()).doesNotStartWith("即时反馈");
            assertThat(reply.feedback()).startsWith("优点是结构清晰");
        }
    }

}
