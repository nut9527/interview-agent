package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.*;
import cn.edu.whu.zhizhida.resume.*;
import cn.edu.whu.zhizhida.resume.api.*;
import cn.edu.whu.zhizhida.interview.*;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.jobs.*;
import cn.edu.whu.zhizhida.jobs.api.*;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 对每个业务接口的两种实现运行相同的调用方断言。 */
class AiBusinessContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new BusinessMetrics(registry);

    private JsonChatClient fixture(String json) throws Exception {
        var payload = mapper.readTree(json);
        // 接入第二种供应商仅需实现通用协议，调用方不依赖 DeepSeek 具体类型。
        return (system, user, temperature, budget) -> new JsonChatResponse(payload, ModelUsage.unknown(ModelUsage.Status.MISSING));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resumeOutputSatisfiesTheSameContract(boolean mock) throws Exception {
        AiResumeService service = mock ? new MockAiResumeService(mapper, metrics) : new DefaultAiResumeService(fixture("""
                {"optimizedText":"使用 Java 实现接口并编写测试。","suggestions":["补充真实结果"],
                 "improvements":[{"category":"表达","issue":"缺少验证细节","action":"补充测试方法","example":"为接口编写测试"}],
                 "targetKeywords":["Java"],"optimizationSummary":"保留原文事实"}
                """), metrics);
        var response = service.optimize(new AiOptimizeRequest("使用 Java 实现接口并编写测试", "后端开发"));
        assertThat(response.optimizedText()).isNotBlank().hasSizeLessThanOrEqualTo(12000);
        assertThat(response.suggestions()).isNotEmpty().hasSizeLessThanOrEqualTo(20)
                .allSatisfy(value -> assertThat(value).isNotBlank().hasSizeLessThanOrEqualTo(1000));
        assertThat(response.improvements()).allSatisfy(item -> {
            assertThat(item.category()).isNotBlank().hasSizeLessThanOrEqualTo(120);
            assertThat(item.issue()).isNotBlank().hasSizeLessThanOrEqualTo(1000);
            assertThat(item.action()).isNotBlank().hasSizeLessThanOrEqualTo(1000);
            assertThat(item.example()).isNotBlank().hasSizeLessThanOrEqualTo(2000);
        });
        assertThat(response.targetKeywords()).allSatisfy(value -> assertThat(value).isNotBlank().hasSizeLessThanOrEqualTo(80));
        assertThat(response.optimizationSummary()).isNotBlank().hasSizeLessThanOrEqualTo(1000);
        assertThatThrownBy(() -> response.suggestions().add("change")).isInstanceOf(UnsupportedOperationException.class);
        assertSuccess("resume_optimize", 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void interviewOutputFollowsTheSameRoundContract(boolean mock) throws Exception {
        JsonChatClient client = fixture("""
                {"question":"请介绍你负责的接口。","feedback":"说明了个人动作，建议补充验证方法。",
                 "nextQuestion":"如何验证正确性？","finalSummary":"已完成回答，请继续练习表达。"}
                """);
        InterviewAiService service = mock ? new MockInterviewAiService(mapper, metrics)
                : new DefaultInterviewAiService(client, mapper, metrics);
        assertThat(service.openingQuestion("后端开发", "使用 Java 开发接口")).isNotBlank().hasSizeLessThanOrEqualTo(300);
        var transcript = List.of(new InterviewTurn("user", "负责接口实现和 3 个测试。"));
        for (boolean finalRound : new boolean[]{false, true}) {
            var reply = service.reviewAnswer("后端开发", "简历", transcript, 1, finalRound);
            assertThat(reply.feedback()).isNotBlank().hasSizeLessThanOrEqualTo(1000);
            if (finalRound) {
                assertThat(reply.nextQuestion()).isEmpty();
                assertThat(reply.finalSummary()).isNotBlank().hasSizeLessThanOrEqualTo(2000);
            } else {
                assertThat(reply.nextQuestion()).isNotBlank().hasSizeLessThanOrEqualTo(600);
                assertThat(reply.finalSummary()).isEmpty();
            }
        }
        assertSuccess("interview_opening", 1);
        assertSuccess("interview_review", 2);
    }



    private void assertSuccess(String operation, int count) {
        assertThat(registry.get("zhizhida.business").tags("operation", operation, "outcome", "success").timer().count()).isEqualTo(count);
        assertThat(registry.find("zhizhida.ai.tokens").counters()).isEmpty();
    }
}
