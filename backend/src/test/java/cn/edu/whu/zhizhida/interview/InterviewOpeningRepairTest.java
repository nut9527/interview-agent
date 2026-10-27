package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.InvalidModelOutputException;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewOpeningRepairTest {
    private final JsonChatClient client = mock(JsonChatClient.class);
    private final InterviewSessionRepository repository = mock(InterviewSessionRepository.class);
    private final AiRequestQuota quota = mock(AiRequestQuota.class);
    private final ObjectMapper mapper = new tools.jackson.databind.json.JsonMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private InterviewStateMachine service;

    @BeforeEach void setUp() {
        var metrics = new BusinessMetrics(registry);
        service = new InterviewStateMachine(repository, () -> 7L, new DefaultInterviewAiService(client, mapper, metrics), mock(ResumeSnapshotReader.class), 30, quota, metrics);
        when(repository.save(eq(7L), any())).thenAnswer(c -> c.getArgument(1));
    }

    @Test void openingSchemaRepairCreatesOneSessionAndChargesUserOnlyOnceWithSeparateMetrics() {
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(
                mapper.createObjectNode().put("question", 42), valid());
        var result = service.start(request());
        assertThat(result.turns().get(1).content()).isEqualTo("请说明你在相关经历中承担的个人职责？");
        verify(client, times(2)).completeJson(anyString(), anyString(), anyDouble(), eq(600));
        verify(client).completeJson(contains("重新生成完整、自洽的 JSON"), contains("validationFailure"), anyDouble(), eq(600));
        verify(quota).admit("7", 3);
        verify(quota).admitInterviewRepair();
        verifyNoMoreInteractions(quota);
        verify(repository).save(eq(7L), any());
        assertThat(registry.get("zhizhida.business").tags("operation", "interview_opening", "outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get("zhizhida.business").tags("operation", "interview_opening_repair", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.find("zhizhida.business").tag("operation", "interview_review").timer()).isNull();
    }

    @Test void malformedGeneratedJsonAndWrongOpeningTargetEachHaveOneRepairPath() {
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new InvalidModelOutputException("模型正文必须是 JSON" )).thenReturn(valid());
        assertThat(service.start(request())).isNotNull();
        reset(client);
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(
                valid().put("questionCompetencyId", "storage"), valid());
        assertThat(service.start(request())).isNotNull();
        verify(client, times(2)).completeJson(anyString(), anyString(), anyDouble(), eq(600));
    }

    @Test void doubleFailureDoesNotSaveAPartialSessionAndProviderTimeoutDoesNotRepair() {
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(mapper.createObjectNode());
        assertThatThrownBy(() -> service.start(request())).isInstanceOf(InterviewReviewRejected.class);
        verify(client, times(2)).completeJson(anyString(), anyString(), anyDouble(), eq(600));
        verify(repository, never()).save(anyLong(), any());
        assertThat(registry.get("zhizhida.business").tags("operation", "interview_opening_repair", "outcome", "failure").timer().count()).isEqualTo(1);
        reset(client, quota);
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenThrow(new IllegalStateException("provider timeout"));
        assertThatThrownBy(() -> service.start(request())).hasMessage("provider timeout");
        verify(client, times(1)).completeJson(anyString(), anyString(), anyDouble(), eq(600));
        verify(quota, never()).admitInterviewRepair();
    }

    private InterviewStartRequest request() { return new InterviewStartRequest("Java", "", null, "", InterviewMode.GENERAL, 6, null, null, null); }
    private tools.jackson.databind.node.ObjectNode valid() {
        return mapper.createObjectNode().put("question", "请说明你在相关经历中承担的个人职责？").put("questionCompetencyId", "experience");
    }
}
