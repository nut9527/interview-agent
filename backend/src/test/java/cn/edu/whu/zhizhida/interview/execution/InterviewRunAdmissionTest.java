package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.interview.InterviewRunModel;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.InterviewSourceGuard;
import cn.edu.whu.zhizhida.interview.InterviewStateMachine;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewStartRequest;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import cn.edu.whu.zhizhida.quota.RedisSubmissionRateLimiter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

class InterviewRunAdmissionTest {
    final InterviewRunStore store = mock(InterviewRunStore.class);
    final CurrentUserProvider users = mock(CurrentUserProvider.class);
    final RedisSubmissionRateLimiter limiter = mock(RedisSubmissionRateLimiter.class);
    final ObjectProvider<RedisSubmissionRateLimiter> provider = mock(ObjectProvider.class);
    final JsonMapper json = new JsonMapper();
    final InterviewRunService service =
            new InterviewRunService(
                    users,
                    store,
                    mock(InterviewStateMachine.class),
                    mock(InterviewSessionRepository.class),
                    mock(InterviewRunModel.class),
                    json,
                    "TOOL",
                    mock(InterviewSourceGuard.class),
                    mock(PracticeMemoryAccess.class),
                    provider);
    final InterviewRun.Start request =
            new InterviewRun.Start(
                    "command",
                    json.readValue(
                            "{\"targetRole\":\"Java\",\"totalRounds\":3}",
                            InterviewStartRequest.class));

    InterviewRunAdmissionTest() {
        when(users.requireUserId()).thenReturn(42L);
        when(provider.getIfAvailable()).thenReturn(limiter);
    }

    @Test
    void newCommandUsesLimiterBeforeAcceptance() {
        service.admitStart(request);
        verify(limiter).require(42);
        verify(store, never()).accept(any());
    }

    @Test
    void acceptedReplayRemainsAvailableWithoutRedis() {
        var row = mock(InterviewRunStore.Row.class);
        when(row.requestHash())
                .thenReturn(
                        InterviewRunStore.sha(
                                InterviewRunService.canonical(
                                                json.valueToTree(request.configuration()))
                                        .toString()));
        when(store.command(42, "START", "command")).thenReturn(Optional.of(row));
        service.admitStart(request);
        verifyNoInteractions(limiter);
    }

    @Test
    void reusedKeyWithDifferentInputConflictsBeforeRedis() {
        var row = mock(InterviewRunStore.Row.class);
        when(row.requestHash()).thenReturn("different");
        when(store.command(42, "START", "command")).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.admitStart(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.status().value()).isEqualTo(409));
        verifyNoInteractions(limiter);
    }
}
