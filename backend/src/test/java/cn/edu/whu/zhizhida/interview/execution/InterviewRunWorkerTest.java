package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.edu.whu.zhizhida.common.MemoryRevokedException;
import cn.edu.whu.zhizhida.common.SourceRevokedException;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.common.execution.RunExecutor;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.interview.InterviewRunModel;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.InterviewSourceGuard;
import cn.edu.whu.zhizhida.interview.InterviewStateMachine;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import tools.jackson.databind.json.JsonMapper;

class InterviewRunWorkerTest {
    private final InterviewRunStore store = mock(InterviewRunStore.class);
    private final InterviewStateMachine machine = mock(InterviewStateMachine.class);
    private final InterviewRunModel model = mock(InterviewRunModel.class);
    private final InterviewEvidenceTools evidence = mock(InterviewEvidenceTools.class);
    private final InterviewSourceGuard sourceGuard = mock(InterviewSourceGuard.class);
    private final PracticeMemoryAccess memory = mock(PracticeMemoryAccess.class);
    private final cn.edu.whu.zhizhida.common.execution.RunExecutorRegistry executors = mock(
            cn.edu.whu.zhizhida.common.execution.RunExecutorRegistry.class);
    private final InterviewRunWorker worker = new InterviewRunWorker(
            store,
            mock(InterviewSessionRepository.class),
            machine,
            model,
            evidence,
            new JsonMapper(),
            executors,
            false,
            new RunExecutionSlots(4),
            new cn.edu.whu.zhizhida.observability.AgentTelemetry(
                    io.micrometer.tracing.Tracer.NOOP),
            sourceGuard,
            memory,
            mock(QuestionCatalog.class),
            "database");
    private final InterviewRunStore.Claim claim = new InterviewRunStore.Claim("run-id", 42, "subject-id", 3);
    private final Logger logger = (Logger) LoggerFactory.getLogger(InterviewRunWorker.class);
    private final Level previousLevel = logger.getLevel();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    InterviewRunWorkerTest() {
        when(store.remainingMillis("run-id")).thenReturn(90000L);
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void close() {
        worker.close();
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MODEL_HTTP_503",
            "RUN_FENCED",
            "TOOL_ARGUMENTS",
            "Bearer secret\nresume text"
    })
    void ordinaryExceptionWordingCannotForgeProtocolCodes(String message) {
        assertThat(InterviewRunWorker.failureCode(new IllegalStateException(message)))
                .isEqualTo("RUN_EXECUTION_FAILED");
        assertThat(InterviewRunWorker.modelFailureCode(new IllegalStateException(message)))
                .isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    void typedCodesAndRevocationsRemainStableRegardlessOfCauseWording() {
        assertThat(
                InterviewRunWorker.failureCode(
                        new RunFailureException(
                                "RUN_BUDGET",
                                new RuntimeException("changed internal explanation"))))
                .isEqualTo("RUN_BUDGET");
        assertThat(InterviewRunWorker.failureCode(new MemoryRevokedException()))
                .isEqualTo("RUN_MEMORY_REVOKED");
        assertThat(InterviewRunWorker.failureCode(new SourceRevokedException()))
                .isEqualTo("RUN_SOURCE_REVOKED");
        assertThat(
                InterviewRunWorker.modelFailureCode(
                        UpstreamServiceException.coded("MODEL_HTTP_503")))
                .isEqualTo("MODEL_HTTP_503");
        assertThat(
                InterviewRunWorker.modelFailureCode(
                        new UpstreamServiceException("MODEL_HTTP_503")))
                .isEqualTo("MODEL_UNAVAILABLE");
        assertThat(InterviewRunWorker.modelFailureCode(new RunFailureException("RUN_FENCED")))
                .isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    void failedDispatchRecordsSafeCoordinatesWithoutMessageCauseOrSuppressedContent() {
        var failure = new IllegalStateException(
                "secret-answer", new RuntimeException("Bearer secret-token"));
        failure.addSuppressed(new RuntimeException("secret-resume"));
        failDispatch(failure);

        worker.execute(claim);

        verify(store).fail(claim, "RUN_EXECUTION_FAILED");
        assertThat(logs.list).hasSize(1);
        var event = logs.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains(
                        "runId=run-id",
                        "epoch=3",
                        "phase=DISPATCH",
                        "code=RUN_EXECUTION_FAILED",
                        "exceptionType=java.lang.IllegalStateException",
                        "location=")
                .doesNotContain("secret-answer", "secret-token", "secret-resume", "Bearer");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void expectedFenceIsDebugWhileFailureToPersistTerminalStateIsVisible() {
        failDispatch(new RunFailureException("RUN_FENCED"));
        worker.execute(claim);
        verify(store).fail(claim, "RUN_FENCED");
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.DEBUG);
        logs.list.clear();

        var databaseFailure = new IllegalStateException("sensitive SQL input");
        doThrow(databaseFailure).when(store).fail(claim, "RUN_FENCED");
        assertThatThrownBy(() -> worker.execute(claim)).isSameAs(databaseFailure);
        assertThat(logs.list.getLast().getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.getLast().getFormattedMessage())
                .contains("phase=FAIL_COMMIT")
                .doesNotContain("sensitive SQL input");
        assertThat(logs.list).allMatch(event -> event.getThrowableProxy() == null);
    }

    @Test
    void expiredQueueEntryNeverEntersExecutor() {
        when(store.remainingMillis("run-id")).thenReturn(0L);
        worker.execute(claim);
        verify(store).fail(claim, "RUN_BUDGET");
        verify(store, never()).get(42, "run-id");
    }

    @Test
    void onlyRolledBackLockFailuresRetryTheSameCandidate() {
        var candidate = mock(cn.edu.whu.zhizhida.interview.api.InterviewSession.class);
        org.mockito.Mockito.doThrow(
                new org.springframework.dao.CannotAcquireLockException("private SQL"))
                .doNothing()
                .when(store)
                .commit(claim, candidate, true);
        worker.commit(claim, candidate, true);
        verify(store, org.mockito.Mockito.times(2)).commit(claim, candidate, true);
        verify(store, never()).reserveModelAttempt(any(), any(RunContextAssembler.Prepared.class));
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
                .contains("run.commit_retry")
                .doesNotContain("private SQL");
    }

    @Test
    void lockRetriesAreBoundedAndFencingOrUnknownCommitResultsNeverRetry() {
        var candidate = mock(cn.edu.whu.zhizhida.interview.api.InterviewSession.class);
        var lock = new org.springframework.dao.CannotAcquireLockException("lock");
        doThrow(lock).when(store).commit(claim, candidate, true);
        assertThatThrownBy(() -> worker.commit(claim, candidate, true)).isSameAs(lock);
        verify(store, org.mockito.Mockito.times(3)).commit(claim, candidate, true);
        for (var failure : java.util.List.of(
                new RunFailureException("RUN_FENCED"),
                new org.springframework.dao.DataAccessResourceFailureException(
                        "unknown commit"))) {
            org.mockito.Mockito.clearInvocations(store);
            doThrow(failure).when(store).commit(claim, candidate, true);
            assertThatThrownBy(() -> worker.commit(claim, candidate, true)).isSameAs(failure);
            verify(store).commit(claim, candidate, true);
        }
    }

    @Test
    void lockRetryCannotRestartAnOuterTransactionAndInterruptionStopsCommit() {
        var candidate = mock(cn.edu.whu.zhizhida.interview.api.InterviewSession.class);
        var failure = new org.springframework.dao.CannotAcquireLockException("lock");
        doThrow(failure).when(store).commit(claim, candidate, true);
        try {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(true);
            assertThatThrownBy(() -> worker.commit(claim, candidate, true)).isSameAs(failure);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
        verify(store).commit(claim, candidate, true);
        org.mockito.Mockito.clearInvocations(store);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> worker.commit(claim, candidate, true))
                    .isInstanceOf(RunFailureException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(store, never()).commit(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void codeConstructionRejectsInvalidValuesWithoutEchoingThem() {
        assertThatThrownBy(() -> new RunFailureException("secret\nMODEL_FAILED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid execution error code");
        assertThatThrownBy(() -> new RunFailureException(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid execution error code");
    }

    @Test
    void damagedCheckpointFailsBeforeAnyModelAttemptAndLogsOnlyFixedMetadata() {
        var row = mock(InterviewRunStore.Row.class);
        when(row.subjectType()).thenReturn("INTERVIEW");
        when(row.route()).thenReturn("TOOL");
        when(row.checkpoint()).thenReturn("{\"stage\":\"Bearer secret-token\\nprivate material\"}");
        when(store.get(42, "run-id")).thenReturn(row);

        worker.execute(claim);

        verify(store).fail(claim, "RUN_CHECKPOINT_INVALID");
        verify(store, never()).reserveModelAttempt(any(), any(RunContextAssembler.Prepared.class));
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
                .contains(
                        "runId=run-id",
                        "epoch=3",
                        "phase=CHECKPOINT",
                        "code=RUN_CHECKPOINT_INVALID",
                        "reason=INVALID_VALUE",
                        "fieldPath=stage")
                .doesNotContain("secret-token", "private material", "Bearer");
        assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void fixedOpeningCommitsAfterSourceAndMemoryChecksWithoutModelOrEvidenceCalls() {
        var session = queuedOpening();
        var opened = mock(InterviewSession.class);
        when(machine.fixedOpening(session)).thenReturn("固定题目");
        when(machine.opened(session, "固定题目")).thenReturn(opened);

        worker.execute(claim);

        var order = inOrder(sourceGuard, memory, machine, store);
        order.verify(sourceGuard).require(42, session);
        order.verify(memory).read(42, PracticeMemoryAccess.Selection.none());
        order.verify(machine).opened(session, "固定题目");
        order.verify(store).commit(claim, opened, true);
        verify(store, never()).reserveModelAttempt(any(), any(RunContextAssembler.Prepared.class));
        verifyNoInteractions(evidence);
    }

    @Test
    void revokedSourceStopsFixedOpeningBeforeCommit() {
        var session = queuedOpening();
        doThrow(new SourceRevokedException()).when(sourceGuard).require(42, session);

        worker.execute(claim);

        verify(store).fail(claim, "RUN_SOURCE_REVOKED");
        verifyNoInteractions(machine, memory, evidence);
        verify(store, never()).commit(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private InterviewSession queuedOpening() {
        var session = new InterviewSession(
                "subject-id",
                "后端开发",
                null,
                "",
                InterviewState.CREATED,
                0,
                java.util.List.of());
        var json = new JsonMapper();
        var input = json.createObjectNode().set("session", json.valueToTree(session));
        var row = mock(InterviewRunStore.Row.class);
        when(row.subjectType()).thenReturn("INTERVIEW");
        when(row.operation()).thenReturn("START");
        when(row.route()).thenReturn("FIXED");
        when(row.input()).thenReturn(input.toString());
        when(store.get(42, "run-id")).thenReturn(row);
        return session;
    }

    @Test
    void slowRunsAreBoundedBeforeClaimAndDatabaseAndMqShareSlots() throws Exception {
        var slots = new RunExecutionSlots(4);
        var entered = new java.util.concurrent.CountDownLatch(4);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(4);
        var virtual = new java.util.concurrent.atomic.AtomicInteger();
        var rows = new java.util.ArrayList<InterviewRunStore.Row>();
        for (int i = 0; i < 6; i++) {
            var row = mock(InterviewRunStore.Row.class);
            when(row.id()).thenReturn("slow-" + i);
            when(row.owner()).thenReturn((long) (i == 1 ? 0 : i));
            when(row.subjectType()).thenReturn("GOAL");
            when(row.subjectId()).thenReturn("subject");
            when(row.operation()).thenReturn("PLAN");
            when(row.input()).thenReturn("{}");
            var c = new InterviewRunStore.Claim(row.id(), row.owner(), null, 1);
            when(store.claim(row)).thenReturn(c);
            when(store.get(row.owner(), row.id())).thenReturn(row);
            when(store.remainingMillis(row.id())).thenReturn(90000L);
            var done = mock(InterviewRunStore.Row.class);
            when(done.terminal()).thenReturn(true);
            when(store.dispatchCandidate(row.id()))
                    .thenAnswer(
                            inv -> {
                                finished.countDown();
                                return java.util.Optional.of(done);
                            });
            rows.add(row);
        }
        var executor = mock(RunExecutor.class);
        when(executors.require("GOAL")).thenReturn(executor);
        org.mockito.Mockito.doAnswer(
                inv -> {
                    if (Thread.currentThread().isVirtual())
                        virtual.incrementAndGet();
                    entered.countDown();
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("timeout");
                    return null;
                })
                .when(executor)
                .execute(any(), anyString(), anyString(), anyString());
        when(store.candidates()).thenReturn(rows);
        var scanning = new InterviewRunWorker(
                store,
                mock(InterviewSessionRepository.class),
                mock(InterviewStateMachine.class),
                mock(InterviewRunModel.class),
                mock(InterviewEvidenceTools.class),
                new JsonMapper(),
                executors,
                true,
                slots,
                new cn.edu.whu.zhizhida.observability.AgentTelemetry(
                        io.micrometer.tracing.Tracer.NOOP),
                mock(InterviewSourceGuard.class),
                mock(PracticeMemoryAccess.class),
                mock(QuestionCatalog.class),
                "database");
        try {
            scanning.scan();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(virtual.get()).isEqualTo(4);
            assertThat(scanning.executeCandidate(rows.get(5))).isFalse();
            verify(store, never()).claim(rows.get(1));
            verify(store, never()).claim(rows.get(5));
            verify(store, never()).needsDispatch(rows.get(5).id());
            release.countDown();
            assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            scanning.close();
        }
    }

    @Test
    void claimFailureAlwaysReleasesLocalSlot() {
        var row = mock(InterviewRunStore.Row.class);
        when(row.id()).thenReturn("run-id");
        when(row.owner()).thenReturn(42L);
        when(store.claim(row))
                .thenThrow(new IllegalStateException("db unavailable"))
                .thenReturn(null);
        assertThatThrownBy(() -> worker.executeCandidate(row))
                .isInstanceOf(IllegalStateException.class);
        assertThat(worker.executeCandidate(row)).isTrue();
        verify(store, org.mockito.Mockito.times(2)).claim(row);
        verify(store).needsDispatch("run-id");
    }

    @Test
    void rejectedClaimRetriesWaitingRunsButAcknowledgesLiveLeasesOrTerminalRuns() {
        var row = mock(InterviewRunStore.Row.class);
        when(row.id()).thenReturn("run-id");
        when(row.owner()).thenReturn(42L);
        when(store.claim(row)).thenReturn(null);
        when(store.needsDispatch("run-id")).thenReturn(true, false);
        assertThat(worker.executeCandidate(row)).isFalse();
        assertThat(worker.executeCandidate(row)).isTrue();
        verifyNoInteractions(executors);
    }

    @Test
    void persistedModelBackoffKeepsBrokerNotificationUntilTerminal() {
        var row = mock(InterviewRunStore.Row.class);
        when(row.id()).thenReturn("run-id");
        when(row.owner()).thenReturn(42L);
        when(row.subjectType()).thenReturn("GOAL");
        when(store.claim(row)).thenReturn(claim);
        when(store.get(42, "run-id")).thenReturn(row);
        when(executors.require("GOAL")).thenReturn(mock(RunExecutor.class));
        when(store.dispatchCandidate("run-id")).thenReturn(java.util.Optional.of(row));
        when(store.retryScheduled(claim)).thenReturn(true);
        assertThat(worker.executeCandidate(row)).isFalse();

        when(row.terminal()).thenReturn(true);
        assertThat(worker.executeCandidate(row)).isTrue();
    }

    @Test
    void expiredRunTerminatesBrokerRetryWithoutCallingModel() {
        var row = mock(InterviewRunStore.Row.class);
        when(row.id()).thenReturn("run-id");
        when(row.owner()).thenReturn(42L);
        when(row.terminal()).thenReturn(true);
        when(store.claim(row)).thenReturn(claim);
        when(store.remainingMillis("run-id")).thenReturn(0L);
        when(store.dispatchCandidate("run-id")).thenReturn(java.util.Optional.of(row));
        assertThat(worker.executeCandidate(row)).isTrue();
        verify(store).fail(claim, "RUN_BUDGET");
        verifyNoInteractions(executors, model);
    }

    @Test
    void rocketDeliveryLeavesScanningAndExpiryToRecovery() {
        var scanning = enabledWorker("rocketmq");
        try {
            scanning.scan();
            scanning.expireWaiting();
            verify(store, never()).candidates();
            verify(store, never()).expireWaiting();
        } finally {
            scanning.close();
        }
    }

    @Test
    void databaseDeliveryStillScansAndExpiresWithoutMqRecovery() {
        var scanning = enabledWorker("database");
        when(store.candidates()).thenReturn(java.util.List.of());
        try {
            scanning.scan();
            scanning.expireWaiting();
            verify(store).candidates();
            verify(store).expireWaiting();
        } finally {
            scanning.close();
        }
    }

    private InterviewRunWorker enabledWorker(String dispatchMode) {
        return new InterviewRunWorker(
                store,
                mock(InterviewSessionRepository.class),
                mock(InterviewStateMachine.class),
                mock(InterviewRunModel.class),
                mock(InterviewEvidenceTools.class),
                new JsonMapper(),
                executors,
                true,
                new RunExecutionSlots(4),
                new cn.edu.whu.zhizhida.observability.AgentTelemetry(
                        io.micrometer.tracing.Tracer.NOOP),
                mock(InterviewSourceGuard.class),
                mock(PracticeMemoryAccess.class),
                mock(QuestionCatalog.class),
                dispatchMode);
    }

    private void failDispatch(RuntimeException failure) {
        var row = mock(InterviewRunStore.Row.class);
        when(row.subjectType()).thenReturn("GOAL");
        when(row.subjectId()).thenReturn("subject-id");
        when(row.operation()).thenReturn("GOAL_PLAN");
        when(row.input()).thenReturn("secret-goal-input");
        when(store.get(42, "run-id")).thenReturn(row);
        var executor = mock(RunExecutor.class);
        when(executors.require("GOAL")).thenReturn(executor);
        doThrow(failure)
                .when(executor)
                .execute(any(), eq("subject-id"), eq("GOAL_PLAN"), anyString());
    }
}
