package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewTrainingTest {
    final Map<String, InterviewSession> stored = new HashMap<>();
    final InterviewSessionRepository repository = mock(InterviewSessionRepository.class);
    final CurrentUserProvider user = mock(CurrentUserProvider.class);
    final MockInterviewAiService ai = spy(new MockInterviewAiService(new tools.jackson.databind.json.JsonMapper(), BusinessMetrics.disabled()));
    final InterviewStateMachine service;

    InterviewTrainingTest() {
        when(user.requireUserId()).thenReturn(7L);
        when(repository.save(eq(7L), any())).thenAnswer(c -> { InterviewSession s = c.getArgument(1); stored.put(s.sessionId(), s); return s; });
        when(repository.findById(anyLong(), anyString())).thenAnswer(c -> Optional.ofNullable(c.getArgument(0).equals(7L) ? stored.get(c.getArgument(1)) : null));
        when(repository.tryAcquireAnswer(eq(7L), anyString(), anyInt(), anyString(), anyInt())).thenReturn(true);
        when(repository.completeAnswer(eq(7L), any(), anyInt(), anyString())).thenAnswer(c -> { InterviewSession s = c.getArgument(1); stored.put(s.sessionId(), s); return true; });
        service = new InterviewStateMachine(repository, user, ai, mock(ResumeSnapshotReader.class));
    }

    InterviewStartRequest request(InterviewTraining.Topic topic, int rounds, String source, Integer round) {
        return new InterviewStartRequest("专项训练", null, null, null, InterviewMode.GENERAL, rounds,
                null, null, InterviewDifficulty.ADVANCED, topic, source, round);
    }

    InterviewSession finish(InterviewSession session) {
        for (int round = 1; round <= session.totalRounds(); round++)
            session = service.answer(session.sessionId(), new InterviewAnswerRequest("我负责测量与方案验证", "round-" + round, round));
        return session;
    }

    @ParameterizedTest @EnumSource(InterviewTraining.Topic.class)
    void allTopicsRunThreeThroughSixRoundsWithoutLeavingScope(InterviewTraining.Topic topic) {
        for (int rounds = 3; rounds <= 6; rounds++) {
            var session = finish(service.start(request(topic, rounds, null, null)));
            assertThat(session.state()).isEqualTo(InterviewState.FINISHED);
            assertThat(session.agent().evaluations()).hasSize(rounds);
            assertThat(session.agent().training().topic()).isEqualTo(topic);
            assertThat(session.difficulty()).isEqualTo(InterviewDifficulty.ADVANCED);
            assertThat(session.agent().plan()).extracting(InterviewAgentState.Competency::id).containsOnly(switch (topic) {
                case DATABASE -> new String[]{"storage"};
                case CONCURRENCY -> new String[]{"concurrency"};
                case PERFORMANCE -> new String[]{"performance"};
                case PROJECT -> new String[]{"ownership", "tradeoffs"};
            });
        }
    }

    @Test void retryUsesServerEvidenceAndInheritedSnapshotsAndSurvivesReplay() {
        var source = finish(service.start(new InterviewStartRequest("后端", "原始简历", null, "数据库事务", InterviewMode.BACKEND_BASICS,
                3, null, null, InterviewDifficulty.BEGINNER)));
        var original = source.agent().evaluations().get(0);
        var retry = service.start(request(null, 6, source.sessionId(), 1));
        assertThat(retry.difficulty()).isEqualTo(InterviewDifficulty.BEGINNER);
        assertThat(retry.resumeSnapshot()).isEqualTo("原始简历");
        assertThat(retry.jobSnapshot()).isEqualTo(source.jobSnapshot());
        assertThat(retry.agent().plan()).extracting(InterviewAgentState.Competency::id).containsExactly(original.competencyId());
        assertThat(retry.agent().training().retry().gap()).isEqualTo(original.decision().gap());
        assertThat(retry.agent().training().retry().answer()).isEqualTo("我负责测量与方案验证");
        assertThat(retry.turns().get(1).content()).contains(original.decision().gap());
        var finished = finish(retry);
        var replay = service.answer(retry.sessionId(), new InterviewAnswerRequest("我负责测量与方案验证", "round-1", 1));
        assertThat(replay.agent().evaluations()).hasSize(1);
        assertThat(replay.agent().training()).isEqualTo(finished.agent().training());
        stored.remove(source.sessionId());
        assertThat(service.findSession(retry.sessionId()).agent().training().retry().answer()).isEqualTo("我负责测量与方案验证");
    }

    @Test void rejectsInvalidRoundsSourcesAndMixedMaterialBeforeCallingModel() {
        for (int rounds : new int[]{0, 2, 7, 12}) assertThatThrownBy(() -> service.start(request(InterviewTraining.Topic.DATABASE, rounds, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        var active = service.start(request(InterviewTraining.Topic.CONCURRENCY, 3, null, null));
        assertThatThrownBy(() -> service.start(request(null, 3, active.sessionId(), 1))).hasMessageContaining("已完成");
        var source = finish(active);
        assertThatThrownBy(() -> service.start(request(null, 3, source.sessionId(), 12))).hasMessageContaining("轮次不存在");
        assertThatThrownBy(() -> service.start(request(InterviewTraining.Topic.DATABASE, 3, source.sessionId(), 1))).hasMessageContaining("新材料或主题");
        when(user.requireUserId()).thenReturn(8L);
        assertThatThrownBy(() -> service.start(request(null, 3, source.sessionId(), 1))).isInstanceOf(cn.edu.whu.zhizhida.common.ResourceNotFoundException.class);
        verify(ai, times(1)).openingQuestion(anyString(), anyString());
    }

    @Test void retryMaterialReachesModelOnlyAsUntrustedEvidence() throws Exception {
        var source = finish(service.start(request(InterviewTraining.Topic.PERFORMANCE, 3, null, null)));
        var retry = service.start(request(null, 3, source.sessionId(), 1));
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var client = mock(cn.edu.whu.zhizhida.ai.api.JsonChatClient.class);
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt())).thenAnswer(call -> {
            var input = mapper.readTree(call.getArgument(1, String.class));
            assertThat(input.path("untrustedEvidence").path("retry").path("gap").asText()).isEqualTo(retry.agent().training().retry().gap());
            assertThat(input.path("agentCheckpoint").has("retry")).isFalse();
            return mapper.createObjectNode().put("question", "请说明性能瓶颈的测量依据？").put("questionCompetencyId", "performance");
        });
        new DefaultInterviewAiService(client, mapper, BusinessMetrics.disabled()).openingQuestion("后端",
                InterviewAgentContext.encode("材料", retry.agent(), new cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy().routing(retry.agent(), 0, 3)));
    }
}
