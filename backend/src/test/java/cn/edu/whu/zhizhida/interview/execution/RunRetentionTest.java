package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import cn.edu.whu.zhizhida.common.MemoryRevokedException;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;

class RunRetentionTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final RunRepository rows = mock(RunRepository.class);
    private final PracticeMemoryAccess memory = mock(PracticeMemoryAccess.class);
    private final RunRetentionCoordinator coordinator = mock(RunRetentionCoordinator.class);
    private final RunRetentionCoordinator.Lease lease =
            new RunRetentionCoordinator.Lease("owner", "saved-cursor");
    private final JsonMapper json = new JsonMapper();
    private final AtomicBoolean inTransaction = new AtomicBoolean();
    private final RunRetention retention =
            new RunRetention(
                    jdbc,
                    tx,
                    rows,
                    json,
                    memory,
                    row -> assertThat(inTransaction).isTrue(),
                    coordinator);

    @BeforeEach
    void configure() {
        when(coordinator.acquire()).thenReturn(lease);
        when(coordinator.renew(lease))
                .thenAnswer(
                        inv -> {
                            assertThat(inTransaction).isFalse();
                            return true;
                        });
        when(coordinator.advance(eq(lease), anyString()))
                .thenAnswer(
                        inv -> {
                            assertThat(inTransaction).isFalse();
                            return true;
                        });
        doAnswer(
                        inv -> {
                            inTransaction.set(true);
                            try {
                                Consumer<TransactionStatus> callback = inv.getArgument(0);
                                callback.accept(mock(TransactionStatus.class));
                            } finally {
                                inTransaction.set(false);
                            }
                            return null;
                        })
                .when(tx)
                .executeWithoutResult(any());
    }

    @Test
    void callerTransactionCannotHoldDatabaseLocksWhileAcquiringRedisLease() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(retention::prune)
                    .hasMessageContaining("outside a database transaction");
            verifyNoInteractions(coordinator, jdbc, rows);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void anotherWorkerHoldingLeasePreventsAllDatabaseWork() {
        when(coordinator.acquire()).thenReturn(null);
        retention.prune();
        verifyNoInteractions(jdbc, rows, memory, tx);
        verify(coordinator, never()).release(any());
    }

    @Test
    void redisFailurePreventsCleanupInsteadOfFallingBackToLocalLock() {
        when(coordinator.acquire()).thenThrow(new IllegalStateException("redis unavailable"));
        assertThatThrownBy(retention::prune).hasMessage("redis unavailable");
        verifyNoInteractions(jdbc, rows, memory, tx);
    }

    @Test
    void completePageAdvancesSharedCursorOnlyAfterAllReads() {
        var page = IntStream.range(0, 100).mapToObj(i -> row("run-" + i)).toList();
        when(rows.memoryRetained("saved-cursor")).thenReturn(page);
        when(coordinator.advance(lease, "run-99"))
                .thenAnswer(
                        inv -> {
                            verify(memory, times(100)).read(eq(7L), any());
                            return true;
                        });
        retention.prune();
        verify(coordinator).advance(lease, "run-99");
        verify(coordinator).release(lease);
    }

    @Test
    void failedPageDoesNotAdvanceAndReleasesLease() {
        var retained = row("run");
        when(rows.memoryRetained("saved-cursor")).thenReturn(List.of(retained));
        when(memory.read(eq(7L), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(retention::prune).hasMessage("database unavailable");
        verify(coordinator, never()).advance(any(), anyString());
        verify(coordinator).release(lease);
        verify(rows, never()).retained();
    }

    @Test
    void lostLeaseAbortsRemainingWorkAndLeavesCursorForReplay() {
        var retained = row("run");
        when(rows.memoryRetained("saved-cursor")).thenReturn(List.of(retained));
        when(coordinator.renew(lease)).thenReturn(true, false);
        retention.prune();
        verifyNoInteractions(memory, tx);
        verify(coordinator, never()).advance(any(), anyString());
        verify(coordinator).release(lease);
        verify(rows, never()).retained();
    }

    @Test
    void withdrawnMemoryCleanupKeepsRedisOutsideTransactionAndRechecksTerminalState() {
        var old = row("withdrawn");
        var live = row("now-running");
        when(live.terminal()).thenReturn(false);
        when(rows.memoryRetained("saved-cursor")).thenReturn(List.of(old));
        when(rows.retained()).thenReturn(List.of(live));
        when(rows.lock(7, old.id())).thenReturn(old);
        when(rows.lock(7, live.id())).thenReturn(live);
        when(memory.read(eq(7L), any())).thenThrow(new MemoryRevokedException());
        retention.prune();
        verify(jdbc)
                .update(
                        "UPDATE interview_runs SET checkpoint_json=NULL,input_json='{}' WHERE id=?",
                        "withdrawn");
        verify(jdbc, never())
                .update(
                        "UPDATE interview_runs SET checkpoint_json=NULL,input_json='{}' WHERE id=?",
                        "now-running");
        verify(coordinator).advance(lease, "");
    }

    private Row row(String id) {
        Row row = mock(Row.class);
        when(row.owner()).thenReturn(7L);
        when(row.id()).thenReturn(id);
        when(row.terminal()).thenReturn(true);
        when(row.checkpoint())
                .thenReturn(
                        json.writeValueAsString(
                                java.util.Map.of(
                                        "practiceMemory", PracticeMemoryAccess.Selection.none())));
        return row;
    }
}
