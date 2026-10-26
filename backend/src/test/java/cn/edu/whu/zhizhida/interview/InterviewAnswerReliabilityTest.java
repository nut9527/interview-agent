package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewAiReply;

import cn.edu.whu.zhizhida.interview.api.InterviewAiService;

import cn.edu.whu.zhizhida.interview.api.InterviewAnswerRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewAnswerReliabilityTest {
    private final InterviewSessionRepository repository = mock(InterviewSessionRepository.class);
    private final InterviewAiService ai = mock(InterviewAiService.class);
    private final AiRequestQuota quota = mock(AiRequestQuota.class);
    private final AtomicReference<InterviewSession> stored = new AtomicReference<>();
    private final AtomicReference<String> lease = new AtomicReference<>();
    private final Object databaseRow = new Object();
    private InterviewStateMachine service;

    @BeforeEach
    void setUp() {
        stored.set(new InterviewSession("session", "Java", null, "", InterviewState.WAITING_ANSWER, 1,
                List.of(new InterviewTurn("system", "开始"), new InterviewTurn("interviewer", "介绍项目"))));
        service = new InterviewStateMachine(repository, () -> 42L, ai, mock(ResumeSnapshotReader.class), 30, quota, BusinessMetrics.disabled());
        lenient().when(repository.findById(42L, "session")).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        lenient().when(repository.tryAcquireAnswer(eq(42L), eq("session"), anyInt(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    synchronized (databaseRow) {
                        return stored.get() != null && stored.get().state() == InterviewState.WAITING_ANSWER
                                && stored.get().turnCount() == invocation.<Integer>getArgument(2)
                                && lease.compareAndSet(null, invocation.getArgument(3));
                    }
                });
        lenient().when(repository.completeAnswer(eq(42L), any(), anyInt(), anyString())).thenAnswer(invocation -> {
            synchronized (databaseRow) {
                if (stored.get() == null || stored.get().turnCount() != invocation.<Integer>getArgument(2)
                        || !invocation.getArgument(3).equals(lease.get())) return false;
                stored.set(invocation.getArgument(1));
                lease.set(null);
                return true;
            }
        });
        lenient().doAnswer(invocation -> {
            lease.compareAndSet(invocation.getArgument(2), null);
            return null;
        }).when(repository).releaseAnswer(eq(42L), eq("session"), anyString());
        lenient().when(ai.reviewAnswer(anyString(), anyString(), anyList(), anyInt(), anyBoolean()))
                .thenReturn(new InterviewAiReply("反馈", "下一题", "最终总结"));
    }

    @Test
    void completedRequestReplaysOriginalResponseEvenAfterLaterRounds() {
        var first = service.answer("session", request("request-1", 1, "第一答"));
        service.answer("session", request("request-2", 2, "第二答"));
        service.answer("session", request("request-3", 3, "第三答"));
        assertThat(service.answer("session", request("request-1", 1, "第一答"))).isEqualTo(first);
        assertThat(stored.get().state()).isEqualTo(InterviewState.FINISHED);
        verify(ai, times(3)).reviewAnswer(any(), any(), anyList(), anyInt(), anyBoolean());
        verify(quota, times(3)).admit("42", 2);
    }

    @Test
    void quotaFailureReleasesLeaseAndReplayDoesNotNeedRemainingBudget() {
        var first = service.answer("session", request("request-1", 1, "第一答"));
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "limit")).when(quota).admit("42", 2);
        assertThat(service.answer("session", request("request-1", 1, "第一答"))).isEqualTo(first);
        assertThatThrownBy(() -> service.answer("session", request("request-2", 2, "第二答")))
                .isInstanceOf(BusinessException.class);
        assertThat(lease.get()).isNull();
        assertThat(stored.get().turnCount()).isEqualTo(2);
        verify(ai, times(1)).reviewAnswer(any(), any(), anyList(), anyInt(), anyBoolean());
    }

    @Test
    void rejectsReusedKeyWithDifferentPayloadAndStaleRound() {
        service.answer("session", request("request-1", 1, "第一答"));
        assertConflict(() -> service.answer("session", request("request-1", 1, "另一答")));
        assertConflict(() -> service.answer("session", request("request-1", 2, "第一答")));
        assertConflict(() -> service.answer("session", request("request-2", 1, "第一答")));
        verify(ai, times(1)).reviewAnswer(any(), any(), anyList(), anyInt(), anyBoolean());
    }

    @Test
    void simultaneousRequestsAcrossServiceInstancesOnlyGenerateOneAnswer() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(ai.reviewAnswer(any(), any(), anyList(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> blocked(entered, release));
        var secondInstance = new InterviewStateMachine(repository, () -> 42L, ai, mock(ResumeSnapshotReader.class));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var first = worker.submit(() -> service.answer("session", request("request-1", 1, "第一答")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertConflict(() -> secondInstance.answer("session", request("request-2", 1, "并发回答")));
            assertThat(stored.get().turns()).hasSize(2);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).turnCount()).isEqualTo(2);
            assertThat(stored.get().turns()).filteredOn(turn -> "user".equals(turn.speaker())).hasSize(1);
            verify(ai, times(1)).reviewAnswer(any(), any(), anyList(), anyInt(), anyBoolean());
            verify(repository, never()).findByIdForUpdate(any(), any());
            verify(repository, never()).save(any(), any());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void expiredWorkerCannotOverwriteNewWorkerResult() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(ai.reviewAnswer(any(), any(), anyList(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> blocked(entered, release))
                .thenReturn(new InterviewAiReply("新反馈", "新问题", ""));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var old = worker.submit(() -> service.answer("session", request("request-old", 1, "旧答")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            lease.set(null); // 模拟数据库时钟判定租约已过期。
            var winner = service.answer("session", request("request-new", 1, "新答"));
            release.countDown();
            assertThatThrownBy(() -> old.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(stored.get()).isEqualTo(winner);
            assertThat(stored.get().turns()).extracting(InterviewTurn::content).doesNotContain("旧答");
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void aiFailureReleasesLeaseAndAllowsSameRequestToRetry() {
        when(ai.reviewAnswer(any(), any(), anyList(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("timeout"))
                .thenReturn(new InterviewAiReply("反馈", "下一题", ""));
        assertThatThrownBy(() -> service.answer("session", request("request-1", 1, "第一答")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lease.get()).isNull();
        assertThat(stored.get().turns()).hasSize(2);
        assertThat(service.answer("session", request("request-1", 1, "第一答")).turnCount()).isEqualTo(2);
    }

    @Test
    void failedConditionalCommitNeverReturnsUnsavedAnswer() {
        when(repository.completeAnswer(eq(42L), any(), anyInt(), any())).thenReturn(false);
        assertConflict(() -> service.answer("session", request("request-1", 1, "第一答")));
        assertThat(stored.get().turns()).hasSize(2);
        assertThat(lease.get()).isNull();
    }

    @Test
    void losingLeaseRaceReplaysJustCommittedAnswerWithoutQuotaOrModelCall() {
        InterviewSession original = stored.get();
        var request = request("request-1", 1, "第一答");
        InterviewSession committed = service.answer("session", request);
        clearInvocations(ai, quota);
        when(repository.findById(42L, "session"))
                .thenReturn(Optional.of(original), Optional.of(committed));
        when(repository.tryAcquireAnswer(eq(42L), eq("session"), eq(1), anyString(), anyInt()))
                .thenReturn(false);

        assertThat(service.answer("session", request)).isEqualTo(committed);

        verifyNoInteractions(ai, quota);
        assertThat(stored.get()).isEqualTo(committed);
    }

    @Test
    void ruleConflictsNeverAcquireLeaseOrConsumeQuota() {
        var request = request("request-1", 1, "第一答");
        service.answer("session", request);
        clearInvocations(repository, ai, quota);

        assertConflict(() -> service.answer("session", request("request-1", 1, "不同回答")));
        assertConflict(() -> service.answer("session", request("request-2", 1, "过期回答")));

        verify(repository, never()).tryAcquireAnswer(any(), any(), anyInt(), any(), anyInt());
        verifyNoInteractions(ai, quota);
    }

    @Test
    void alternativeModelAdapterCannotCommitInvalidOutputAndRetryKeepsStateAndQuotaRules() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var response = new AtomicReference<>(mapper.readTree("{\"feedback\":42}"));
        cn.edu.whu.zhizhida.ai.api.JsonChatClient replacement = (system, user, temperature, budget) ->
                new cn.edu.whu.zhizhida.ai.api.JsonChatResponse(response.get(),
                        cn.edu.whu.zhizhida.ai.api.ModelUsage.unknown(cn.edu.whu.zhizhida.ai.api.ModelUsage.Status.MISSING));
        var adapter = new DefaultInterviewAiService(replacement, mapper, BusinessMetrics.disabled());
        service = new InterviewStateMachine(repository, () -> 42L, adapter, mock(ResumeSnapshotReader.class), 30, quota, BusinessMetrics.disabled());
        var request = request("same-request", 1, "负责开发");
        assertThatThrownBy(() -> service.answer("session", request)).isInstanceOf(IllegalStateException.class);
        assertThat(lease.get()).isNull();
        assertThat(stored.get().turnCount()).isEqualTo(1);
        assertThat(stored.get().turns()).hasSize(2);
        verify(repository, never()).completeAnswer(any(), any(), anyInt(), any());
        response.set(mapper.readTree("{\"feedback\":\"有具体职责，建议补充方法\",\"nextQuestion\":\"怎样测试？\"}"));
        var committed = service.answer("session", request);
        assertThat(committed.turnCount()).isEqualTo(2);
        assertThat(service.answer("session", request)).isEqualTo(committed);
        verify(quota, times(2)).admit("42", AiRequestQuota.INTERVIEW_REVIEW_UNITS);
        verify(repository, times(1)).completeAnswer(any(), any(), anyInt(), any());
    }

    private InterviewAnswerRequest request(String key, int turn, String answer) {
        return new InterviewAnswerRequest(answer, key, turn);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    private InterviewAiReply blocked(CountDownLatch entered, CountDownLatch release) throws InterruptedException {
        entered.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker timed out");
        return new InterviewAiReply("旧反馈", "旧问题", "");
    }
}
