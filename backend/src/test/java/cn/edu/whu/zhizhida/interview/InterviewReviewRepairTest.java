package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.*;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewReviewRepairTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonChatClient client = mock(JsonChatClient.class);
    private final InterviewSessionRepository repository = mock(InterviewSessionRepository.class);
    private final AiRequestQuota quota = mock(AiRequestQuota.class);
    private final AtomicReference<InterviewSession> stored = new AtomicReference<>();
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private InterviewStateMachine service;
    private static final String ANSWER = "我负责接口设计并完成了实际对照验证。".repeat(100);

    @BeforeEach void setUp() {
        var state = new InterviewAgentPolicy().plan(InterviewMode.GENERAL, 12, "", "model");
        stored.set(new InterviewSession("session", "Java", null, "", null, null, "", InterviewMode.GENERAL,
                12, null, InterviewState.WAITING_ANSWER, 1, List.of(new InterviewTurn("interviewer", "请介绍个人职责")), null, state));
        when(repository.findById(7L, "session")).thenAnswer(c -> Optional.of(stored.get()));
        when(repository.tryAcquireAnswer(eq(7L), eq("session"), anyInt(), anyString(), anyInt())).thenReturn(true);
        when(repository.completeAnswer(eq(7L), any(), anyInt(), anyString())).thenAnswer(c -> { stored.set(c.getArgument(1)); return true; });
        var metrics = new BusinessMetrics(registry);
        var ai = new DefaultInterviewAiService(client, mapper, metrics);
        service = new InterviewStateMachine(repository, () -> 7L, ai, mock(ResumeSnapshotReader.class), 30, quota, metrics);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "enum", "evidence", "action", "target", "probe", "intent", "question", "questionTarget"})
    void repairsExactlyOnceWithReasonAndOriginalAnswerThenCommitsAndReplays(String defect) throws Exception {
        ObjectNode invalid = valid();
        ObjectNode decision = (ObjectNode) invalid.path("agentDecision");
        switch (defect) {
            case "missing" -> decision.remove("gap");
            case "enum" -> decision.put("level", "EXCELLENT");
            case "evidence" -> decision.put("evidence", "候选人未提供的原文");
            case "action" -> decision.put("action", "FINISH");
            case "target" -> decision.put("nextCompetencyId", "reflection");
            case "probe" -> decision.put("probe", "NONE");
            case "intent" -> decision.put("intent", "意".repeat(201));
            case "question" -> invalid.put("nextQuestion", "请介绍个人职责！");
            case "questionTarget" -> decision.put("questionCompetencyId", "storage");
            default -> throw new AssertionError(defect);
        }
        var calls = new AtomicInteger();
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenAnswer(c -> {
            var input = mapper.readTree(c.<String>getArgument(1));
            assertThat(input.path("transcript").get(1).path("content").asText()).isEqualTo(ANSWER);
            assertThat(stored.get().agent().evaluations()).isEmpty();
            if (calls.incrementAndGet() == 1) return invalid;
            assertThat(input.path("validationFailure").asText()).isNotBlank();
            assertThat(c.<String>getArgument(0)).contains("重新生成完整、自洽的 JSON");
            return valid();
        });
        var request = new InterviewAnswerRequest(ANSWER, "request-repair", 1);
        var result = service.answer("session", request);
        assertThat(result.agent().evaluations()).hasSize(1);
        assertThat(result.turns()).filteredOn(t -> "user".equals(t.speaker())).extracting(InterviewTurn::content).containsExactly(ANSWER);
        assertThat(service.answer("session", request)).isEqualTo(result);
        verify(client, times(2)).completeJson(anyString(), anyString(), anyDouble(), eq(2000));
        verify(quota, times(1)).admit("7", 2);
        verify(quota).admitInterviewRepair();
        verify(repository, times(1)).completeAnswer(eq(7L), any(), eq(1), anyString());
        verify(repository).tryAcquireAnswer(eq(7L), eq("session"), eq(1), anyString(), eq(75));
        assertThat(registry.get("zhizhida.business").tags("operation", "interview_repair", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("zhizhida.business").tag("operation", "interview_review").timers()).hasSize(1);
    }

    @Test void doubleFailureReleasesLeaseAndAllowsSameAnswerToBeResubmitted() {
        var invalid = valid(); ((ObjectNode) invalid.path("agentDecision")).remove("intent");
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(invalid, invalid, valid());
        var original = stored.get();
        var request = new InterviewAnswerRequest(ANSWER, "request-retry", 1);
        assertThatThrownBy(() -> service.answer("session", request)).isInstanceOf(InterviewReviewRejected.class).hasMessageContaining("intent");
        assertThat(stored.get()).isSameAs(original);
        assertThat(registry.get("zhizhida.business").tags("operation", "interview_repair", "outcome", "failure").timer().count()).isEqualTo(1);
        verify(repository, never()).completeAnswer(anyLong(), any(), anyInt(), anyString());
        verify(repository).releaseAnswer(eq(7L), eq("session"), anyString());
        assertThat(service.answer("session", request).agent().evaluations()).hasSize(1);
        verify(client, times(3)).completeJson(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test void providerFailureDoesNotTriggerRepair() {
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenThrow(new IllegalStateException("provider timeout"));
        assertThatThrownBy(() -> service.answer("session", new InterviewAnswerRequest(ANSWER, "request-timeout", 1))).hasMessage("provider timeout");
        verify(client, times(1)).completeJson(anyString(), anyString(), anyDouble(), anyInt());
        verify(quota, times(1)).admit("7", 2);
        verify(repository, never()).completeAnswer(anyLong(), any(), anyInt(), anyString());
        verify(repository).releaseAnswer(eq(7L), eq("session"), anyString());
    }

    @Test void repairQuotaFailureAndLateCommitCannotSavePartialResult() {
        var invalid = valid(); ((ObjectNode) invalid.path("agentDecision")).put("evidence", "不是原文");
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(invalid);
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "全局额度不足")).when(quota).admitInterviewRepair();
        assertThatThrownBy(() -> service.answer("session", new InterviewAnswerRequest(ANSWER, "request-quota", 1))).isInstanceOf(BusinessException.class);
        verify(client, times(1)).completeJson(anyString(), anyString(), anyDouble(), anyInt());
        verify(repository, never()).completeAnswer(anyLong(), any(), anyInt(), anyString());
        assertThat(stored.get().agent().evaluations()).isEmpty();
        reset(quota);
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(invalid, valid());
        when(repository.completeAnswer(eq(7L), any(), anyInt(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.answer("session", new InterviewAnswerRequest(ANSWER, "request-late", 1))).isInstanceOf(BusinessException.class);
        assertThat(stored.get().agent().evaluations()).isEmpty();
        verify(repository, times(1)).completeAnswer(eq(7L), any(), eq(1), anyString());
    }

    private ObjectNode valid() {
        var payload = mapper.createObjectNode().put("feedback", "说明了个人行动，请补充可核对的测量结果。")
                .put("nextQuestion", "请说明验证过程中保留了哪些原始证据？");
        payload.set("agentDecision", mapper.valueToTree(new Decision(Level.PARTIAL, "我负责接口设计", "缺少测量依据", Action.FOLLOW_UP,
                "experience", "核对实际贡献", "练习解释验证过程", Probe.EVIDENCE, "验证个人贡献是否有具体证据", "experience")));
        return payload;
    }
}
