package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewAiReply;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.execution.*;
import cn.edu.whu.zhizhida.knowledge.KnowledgeRepository;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewRecoveryIT {

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "interview.runs.worker-enabled=false")
    @Nested
    class DurableRuns extends InterviewRunScenario {
        @Autowired
        cn.edu.whu.zhizhida.interview.execution.RunDispatchOutbox outbox;
        @Autowired
        InterviewSessionRepository sessions;
        @Autowired
        JdbcTemplate jdbc;
        @Autowired
        io.micrometer.core.instrument.MeterRegistry meterRegistry;
        @Autowired
        org.springframework.transaction.support.TransactionTemplate transaction;
        @org.springframework.boot.test.web.server.LocalServerPort
        int port;

        Map<String, Object> config() {
            return Map.of("targetRole", "Java 后端", "totalRounds", 3, "experienceMode", "FORMAL");
        }

        String start(String key) throws Exception {
            return acceptStart(key, config());
        }

        String modelCheckpoint(InterviewRunStore.Claim claim, String phase, boolean repaired) {
            var cp = objectMapper.createObjectNode().put("stage", "MODEL").put("outputPhase", phase)
                    .put("repaired", repaired).put("actionRepaired", false);
            cp.putArray("sources");
            cp.putArray("messages").addObject().put("role", "system").put("content", phase + " fixture");
            String saved = cp.toString();
            if (repaired) store.repair(claim, saved);
            else store.checkpoint(claim, saved, "MODEL", null, null);
            return saved;
        }

        String opened() throws Exception {
            String id = start(UUID.randomUUID().toString());
            run(id);
            assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            return store.get(owner, id).sessionId();
        }

        String answer(String session, String key) throws Exception {
            var result = performPost("/api/interviews/sessions/" + session + "/answer-commands",
                    Map.of("requestId", key, "expectedTurn", 1, "answer", "我用 Redis 保存请求号，数据库异常后的状态还没有验证。"), token);
            assertThat(result.getResponse().getStatus()).isEqualTo(202);
            return (String) dataMap(result).get("runId");
        }

        @Test
        void outboxIsAtomicWithAcceptanceAndCommandReplay() throws Exception {
            String id=start("outbox_atomic_001");
            assertThat(start("outbox_atomic_001")).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_outbox WHERE run_id=?",Integer.class,id)).isEqualTo(1);
            assertThat(store.claim(store.get(owner, id))).isNotNull();
            transaction.executeWithoutResult(status -> {
                try { start("outbox_rollback_001"); } catch(Exception ex) { throw new RuntimeException(ex); }
                status.setRollbackOnly();
            });
            assertThat(store.command(owner,"START","outbox_rollback_001")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_outbox o JOIN interview_runs r ON r.id=o.run_id WHERE r.owner_id=?",Integer.class,owner)).isEqualTo(1);
        }

        @Test
        void publisherLeaseFencesLateCallbacksAndRecoveryKeepsBudget() throws Exception {
            String id=start("outbox_lease_001");
            // 使本测试事件先于其他测试保留的 outbox 被领取。
            jdbc.update("UPDATE interview_run_outbox SET created_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)) WHERE run_id=?",id);
            assertThat(outbox.oldestPendingSeconds()).isGreaterThanOrEqualTo(86400);
            var first=outbox.claim().orElseThrow();
            assertThat(first.message().runId()).isEqualTo(id);
            jdbc.update("UPDATE interview_run_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE run_id=?",id);
            var second=outbox.claim().orElseThrow();
            assertThat(second.message().eventId()).isEqualTo(first.message().eventId());
            assertThat(second.token()).isNotEqualTo(first.token());
            outbox.published(first);
            assertThat(jdbc.queryForObject("SELECT status FROM interview_run_outbox WHERE run_id=?",String.class,id)).isEqualTo("SENDING");
            outbox.published(second);
            outbox.retry(first,"LATE_CALLBACK");
            assertThat(outbox.oldestPendingSeconds()).isLessThan(86400);
            assertThat(jdbc.queryForObject("SELECT status FROM interview_run_outbox WHERE run_id=?",String.class,id)).isEqualTo("PUBLISHED");
            var before=store.get(owner,id);
            jdbc.update("UPDATE interview_runs SET next_dispatch_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            store.recoverDispatches(); store.recoverDispatches();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_outbox WHERE run_id=?",Integer.class,id)).isEqualTo(2);
            assertThat(store.get(owner,id).deadline()).isEqualTo(before.deadline());
            assertThat(store.get(owner,id).attempts()).isEqualTo(before.attempts());
            store.deleteOwned(owner);
            assertThat(store.needsDispatch(id)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_outbox WHERE run_id=?",Integer.class,id)).isZero();
        }

        @Test
        void recoveryExpiresQueuedWorkWithoutModelCallsAndRespectsActiveLeases() throws Exception {
            String id=start("outbox_expired_001");
            assertThat(store.needsDispatch(id)).isTrue();
            var claim=store.claim(store.get(owner,id));
            assertThat(store.needsDispatch(id)).isFalse();
            jdbc.update("UPDATE interview_runs SET next_dispatch_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            store.recoverDispatches();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_outbox WHERE run_id=?",Integer.class,id)).isEqualTo(1);
            jdbc.update("UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            assertThat(store.needsDispatch(id)).isTrue();
            store.recoverDispatches();
            assertThat(store.get(owner,id).status()).isEqualTo("EXPIRED");
            assertThat(store.needsDispatch(id)).isFalse();
            assertThat(store.get(owner,id).attempts()).isZero();
            assertThat(store.heartbeat(claim)).isFalse();
        }

        @Test
        void retryReleaseRollsBackAtomicallyAndFencesDuplicateSettlement() throws Exception {
            String id = start("retry_atomic_001");
            var claim = store.claim(store.get(owner,id));
            String savedCheckpoint = modelCheckpoint(claim, "ACTION", false);
            String attempt = store.reserveModelAttempt(claim, prepared("input-hash")).id();
            transaction.executeWithoutResult(status -> {
                assertThat(store.deferModelFailure(claim,attempt,"MODEL_NETWORK",12,store.get(claim.owner(),claim.id()).checkpoint())).isTrue();
                assertThat(store.retryScheduled(claim)).isTrue();
                status.setRollbackOnly();
            });
            assertThat(store.get(owner,id).status()).isEqualTo("RUNNING");
            assertThat(store.get(owner,id).checkpoint()).isEqualTo(savedCheckpoint);
            assertThat(store.retryScheduled(claim)).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM interview_model_attempts WHERE attempt_id=?",String.class,attempt)).isEqualTo("STARTED");
            assertThat(store.events(owner,id,0)).noneMatch(event -> event.type().equals("model.retry_scheduled"));
            assertThat(store.deferModelFailure(claim,attempt,"MODEL_NETWORK",12,store.get(claim.owner(),claim.id()).checkpoint())).isTrue();
            assertThat(store.needsDispatch(id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM interview_model_attempts WHERE attempt_id=?",String.class,attempt)).isEqualTo("UNKNOWN");
            assertThatThrownBy(() -> store.deferModelFailure(claim,attempt,"MODEL_NETWORK",12,store.get(claim.owner(),claim.id()).checkpoint()))
                    .hasMessage("RUN_FENCED");
            store.fail(claim,"LATE_FAILURE");
            assertThat(store.get(owner,id).status()).isEqualTo("ACCEPTED");
            assertThat(store.get(owner,id).attempts()).isEqualTo(1);
        }

        @Test
        void deferredAnswerKeepsSessionMutexAndProductQuotaUntilCancelled() throws Exception {
            String session = opened(), id = answer(session,"retry_answer_001");
            String quotaKey = InterviewRunStore.sha("ai-day:" + owner);
            assertThat(jdbc.queryForObject("SELECT SUM(used_units) FROM request_quotas WHERE scope_key=?",Integer.class,quotaKey)).isEqualTo(5);
            var claim = store.claim(store.get(owner,id));
            String savedCheckpoint = modelCheckpoint(claim, "ACTION", false);
            String attempt = store.reserveModelAttempt(claim, prepared("hash")).id();
            assertThat(store.deferModelFailure(claim,attempt,"MODEL_HTTP_429",1,store.get(claim.owner(),claim.id()).checkpoint())).isTrue();
            assertThat(answer(session,"retry_answer_001")).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT active_run_id FROM interview_sessions WHERE session_id=?",String.class,session)).isEqualTo(id);
            assertThat(performPost("/api/interviews/sessions/" + session + "/answer-commands",
                    Map.of("requestId","other_answer_001","expectedTurn",1,"answer","另一个回答"),token).getResponse().getStatus()).isEqualTo(409);
            assertThat(store.get(owner,id).attempts()).isEqualTo(1);
            store.cancel(owner,id);
            assertThat(jdbc.queryForObject("SELECT active_run_id FROM interview_sessions WHERE user_id=? AND session_id=?",String.class,owner,session)).isNull();
            assertThat(jdbc.queryForObject("SELECT SUM(used_units) FROM request_quotas WHERE scope_key=?",Integer.class,quotaKey)).isEqualTo(5);
            assertThat(store.retryScheduled(claim)).isFalse();
        }

        @Test
        void retryRemindersWaitUntilDueAndConcurrentClaimsHaveOneWinner() throws Exception {
            String id = start("retry_dispatch_001");
            var initial = store.claim(store.get(owner,id));
            String savedCheckpoint = modelCheckpoint(initial, "ACTION", false);
            String attempt = store.reserveModelAttempt(initial, prepared("hash")).id();
            assertThat(store.deferModelFailure(initial,attempt,"MODEL_HTTP_503",1,store.get(initial.owner(),initial.id()).checkpoint())).isTrue();
            jdbc.update("UPDATE interview_run_outbox SET status='PUBLISHED' WHERE run_id=?",id);
            jdbc.update("UPDATE interview_runs SET not_before=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),"
                    + "next_dispatch_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            store.recoverDispatches();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_outbox WHERE run_id=?",Integer.class,id)).isEqualTo(1);
            assertThat(store.claim(store.get(owner,id))).isNull();
            jdbc.update("UPDATE interview_runs SET not_before=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            store.recoverDispatches(); store.recoverDispatches();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_outbox WHERE run_id=?",Integer.class,id)).isEqualTo(2);
            var candidate = store.get(owner,id);
            var gate = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { gate.await(); return store.claim(candidate); });
                var second = pool.submit(() -> { gate.await(); return store.claim(candidate); });
                gate.countDown();
                var a = first.get(10,TimeUnit.SECONDS); var b = second.get(10,TimeUnit.SECONDS);
                assertThat((a == null ? 0 : 1) + (b == null ? 0 : 1)).isEqualTo(1);
                assertThat(a == null ? b.epoch() : a.epoch()).isGreaterThan(initial.epoch());
            }
            assertThat(store.retryScheduled(initial)).isFalse();
            assertThat(store.get(owner,id).attempts()).isEqualTo(1);
        }

        @Test
        void retryCannotExtendDeadlineOrExceedAttemptLimit() throws Exception {
            String id = start("retry_budget_001");
            var claim = store.claim(store.get(owner,id));
            String savedCheckpoint = modelCheckpoint(claim, "ACTION", false);
            String attempt = store.reserveModelAttempt(claim, prepared("hash")).id();
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(MICROSECOND,6500000,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            assertThat(store.deferModelFailure(claim,attempt,"MODEL_HTTP_429",1,store.get(claim.owner(),claim.id()).checkpoint())).isFalse();
            assertThat(store.retryScheduled(claim)).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM interview_model_attempts WHERE attempt_id=?",String.class,attempt)).isEqualTo("FAILED");
            store.fail(claim,"MODEL_HTTP_429");
            String limited = start("retry_limit_001");
            var last = store.claim(store.get(owner,limited));
            String lastAttempt = null;
            for (int i=0;i<4;i++) {
                modelCheckpoint(last, i < 2 ? "ACTION" : "FINAL", i == 3);
                lastAttempt = store.reserveModelAttempt(last, prepared("hash")).id();
                if (i<3) store.attemptFailure(last,lastAttempt,"MODEL_HTTP_503",1);
            }
            assertThat(store.deferModelFailure(last,lastAttempt,"MODEL_HTTP_503",1,store.get(last.owner(),last.id()).checkpoint())).isFalse();
            assertThat(store.get(owner,limited).attempts()).isEqualTo(4);
            assertThatThrownBy(() -> store.reserveModelAttempt(last, prepared("fifth")).id()).hasMessage("RUN_BUDGET");
        }

        @Test
        void expiredRetryIsReapedEvenBeforeItsEligibilityOrReminderTime() throws Exception {
            String id = start("retry_expiry_001");
            var claim = store.claim(store.get(owner,id));
            String savedCheckpoint = modelCheckpoint(claim, "ACTION", false);
            String attempt = store.reserveModelAttempt(claim, prepared("hash")).id();
            assertThat(store.deferModelFailure(claim,attempt,"MODEL_HTTP_429",1,store.get(claim.owner(),claim.id()).checkpoint())).isTrue();
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),"
                    + "not_before=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),"
                    + "next_dispatch_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            assertThat(store.candidates()).extracting(InterviewRunStore.Row::id).contains(id);
            store.recoverDispatches();
            assertThat(store.get(owner,id).status()).isEqualTo("EXPIRED");
            assertThat(store.get(owner,id).attempts()).isEqualTo(1);
            assertThat(store.claim(store.get(owner,id))).isNull();
        }

        @Test
        void phasedRetryPreservesRepairTimeAndRejectsUnreservedOrPermanentFailure() throws Exception {
            String id = start("retry_reserve_001");
            var claim = store.claim(store.get(owner,id));
            String savedCheckpoint = modelCheckpoint(claim, "ACTION", false);
            String attempt = store.reserveModelAttempt(claim, prepared("hash")).id();
            assertThatThrownBy(() -> store.deferModelFailure(claim,attempt,"MODEL_HTTP_401",1,store.get(claim.owner(),claim.id()).checkpoint()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.deferModelFailure(claim,"not-reserved","MODEL_HTTP_503",1,store.get(claim.owner(),claim.id()).checkpoint()))
                    .hasMessage("RUN_FENCED");
            assertThat(jdbc.queryForObject("SELECT status FROM interview_model_attempts WHERE attempt_id=?",String.class,attempt)).isEqualTo("STARTED");
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            assertThat(store.deferModelFailure(claim,attempt,"MODEL_HTTP_503",1,savedCheckpoint)).isFalse();
            assertThat(store.get(owner,id).status()).isEqualTo("RUNNING");
            assertThat(store.get(owner,id).checkpoint()).isEqualTo(savedCheckpoint);
            assertThat(store.get(owner,id).attempts()).isEqualTo(1);
        }

        @Test
        void terminalMetricsOnlyCountCommittedTransitionOnce() throws Exception {
            String id = start("terminal_metrics_001");
            double before = cancelledCount();
            transaction.executeWithoutResult(status -> {
                store.cancel(owner, id);
                assertThat(cancelledCount()).isEqualTo(before);
                status.setRollbackOnly();
            });
            assertThat(store.get(owner, id).status()).isEqualTo("ACCEPTED");
            assertThat(cancelledCount()).isEqualTo(before);
            store.cancel(owner, id);
            assertThat(cancelledCount()).isEqualTo(before + 1);
            store.cancel(owner, id);
            assertThat(cancelledCount()).isEqualTo(before + 1);
            assertThat(meterRegistry.get("zhizhida.run.duration").tag("status", "cancelled").timer().count())
                    .isEqualTo((long) cancelledCount());
            assertThat(meterRegistry.get("zhizhida.run.completed").tag("status", "cancelled").counter().getId().getTags())
                    .extracting(io.micrometer.core.instrument.Tag::getKey).doesNotContain("runId", "owner", "requestId");
        }

        private double cancelledCount() {
            var counter = meterRegistry.find("zhizhida.run.completed").tag("status", "cancelled").counter();
            return counter == null ? 0 : counter.count();
        }

        @Test
        void startReplayFreezesInputsAndChargesOnce() throws Exception {
            String id = start("start_replay_001");
            assertThat(start("start_replay_001")).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_sessions WHERE user_id=?", Integer.class, owner))
                    .isZero();
            var conflict = performPost("/api/interviews/start-commands",
                    Map.of("requestId", "start_replay_001", "configuration", Map.of("targetRole", "另一岗位")), token);
            assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
            run(id);
            assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            assertThat(start("start_replay_001")).isEqualTo(id);
            assertThat(
                    jdbc.queryForObject("SELECT COUNT(*) FROM interview_model_attempts WHERE run_id=?", Integer.class, id))
                    .isEqualTo(2);
        }

        @Test
        void answerRunBlocksOtherCommandsAndCommitsOnce() throws Exception {
            String session = opened(), id = answer(session, "answer_run_001");
            assertThat(answer(session, "answer_run_001")).isEqualTo(id);
            assertThat(performPost("/api/interviews/sessions/" + session + "/answer-commands",
                    Map.of("requestId", "answer_run_002", "expectedTurn", 1, "answer", "另一份回答"), token).getResponse()
                    .getStatus()).isEqualTo(409);
            run(id);
            assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            var saved = sessions.findById(owner, session).orElseThrow();
            assertThat(saved.turns().stream().filter(t -> t.speaker().equals("user"))).hasSize(1);
            assertThat(saved.publicView().agent()).isNull();
            assertThat(saved.publicView().turns().stream().anyMatch(t -> t.content().startsWith("即时反馈："))).isFalse();
            assertThat(answer(session, "answer_run_001")).isEqualTo(id);
            var events = store.events(owner, id, 0);
            assertThat(events).extracting(InterviewRun.Event::seq).isSorted().doesNotHaveDuplicates();
            assertThat(events).extracting(InterviewRun.Event::type).contains("turn.committed");
            assertThat(objectMapper.writeValueAsString(events)).doesNotContain("Redis", "agentDecision",
                    "reasoning_content", "feedback");
            assertThat(store.events(owner, id, events.getLast().seq())).isEmpty();
        }

        @Test
        void recoveryKeepsUnknownAttemptAndFencesEveryOldWrite() throws Exception {
            String id = start("recover_epoch_001");
            var first = store.claim(store.get(owner, id));
            modelCheckpoint(first, "ACTION", false);
            var old = store.reserveModelAttempt(first, prepared("f".repeat(64)));
            // 模拟出站投影字段启用前已发送、尚未写回结果的旧账本。
            assertThat(jdbc.update("UPDATE interview_model_attempts SET context_version=NULL,input_chars=NULL,"
                    + "tool_chars=NULL,omitted_tool_results=NULL WHERE attempt_id=?", old.id())).isOne();
            jdbc.update("UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                    id);
            assertThat(store.heartbeat(first)).isFalse();
            var next = store.claim(store.get(owner, id));
            assertThat(next.epoch()).isGreaterThan(first.epoch());
            assertThat(jdbc.queryForObject("SELECT status FROM interview_model_attempts WHERE run_id=?", String.class, id))
                    .isEqualTo("UNKNOWN");
            assertThatThrownBy(() -> store.checkpoint(first, "{}", "MODEL", "model.started", "stale"))
                    .hasMessage("RUN_FENCED");
            assertThatThrownBy(() -> store.reserveModelAttempt(first, prepared("x")).id()).hasMessage("RUN_FENCED");
            assertThatThrownBy(() -> store.receipt(first, "x", "old", "search_knowledge", "{}", "x", "{}", "{}"))
                    .hasMessage("RUN_FENCED");
            worker.execute(next);
            assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            assertThat(store.get(owner, id).attempts()).isEqualTo(3);
        }

        @Test
        void cancelWinsAndTerminalKeyNeverRestarts() throws Exception {
            String session = opened(), id = answer(session, "cancel_answer_001");
            var claim = store.claim(store.get(owner, id));
            assertThat(store.cancel(owner, id).status()).isEqualTo("CANCELLED");
            worker.execute(claim);
            assertThat(store.get(owner, id).status()).isEqualTo("CANCELLED");
            assertThat(sessions.findById(owner, session).orElseThrow().turnCount()).isEqualTo(1);
            assertThat(answer(session, "cancel_answer_001")).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT active_run_id FROM interview_sessions WHERE user_id=? AND session_id=?",String.class,owner,session)).isNull();
            assertThat(answer(session, "after_cancel_001")).isNotEqualTo(id);
        }

        @Test
        void expiredRunReleasesTurnAndDoesNotCallModel() throws Exception {
            String id = start("deadline_start_001");
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", id);
            assertThat(store.claim(store.get(owner, id))).isNull();
            assertThat(store.get(owner, id).status()).isEqualTo("EXPIRED");
            assertThat(store.get(owner, id).attempts()).isZero();
        }

        @Test
        void queueExpiryReleasesAnswerMutexWithoutAdvancingTurnOrRefundingAcceptedOperation() throws Exception {
            String session = opened(), id = answer(session, "queue_timeout_answer");
            jdbc.update("UPDATE interview_runs SET queue_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", id);
            store.expireWaiting();
            assertThat(store.get(owner, id).error()).isEqualTo("RUN_QUEUE_TIMEOUT");
            assertThat(jdbc.queryForObject("SELECT active_run_id FROM interview_sessions WHERE session_id=?", String.class, session)).isNull();
            assertThat(sessions.findById(owner, session).orElseThrow().turnCount()).isEqualTo(1);
            assertThat(store.get(owner, id).attempts()).isZero();
            assertThat(jdbc.queryForObject("SELECT SUM(used_units) FROM request_quotas WHERE scope_key=?", Integer.class,
                    InterviewRunStore.sha("ai-day:" + owner))).isEqualTo(5);
            assertThat(answer(session, "queue_timeout_answer")).isEqualTo(id);
            assertThat(answer(session, "queue_answer_resubmit")).isNotEqualTo(id);
        }

        @Test
        void foreignIdentityAndDeletedSessionCannotRecoverEvidence() throws Exception {
            String session = opened(), id = answer(session, "deleted_answer_001");
            var claim = store.claim(store.get(owner, id));
            String foreign = account("foreign run");
            assertThat(performGet("/api/interview-runs/" + id, foreign).getResponse().getStatus()).isEqualTo(404);
            assertThat(performDelete("/api/interviews/sessions/" + session, token).getResponse().getStatus())
                    .isEqualTo(200);
            worker.execute(claim);
            assertThat(performGet("/api/interview-runs/" + id, token).getResponse().getStatus()).isEqualTo(404);
            assertThat(sessions.findById(owner, session)).isEmpty();
        }

        @Test
        void concurrentSameKeyProducesOneCommand() throws Exception {
            var pool = Executors.newFixedThreadPool(2);
            var gate = new CountDownLatch(1);
            try {
                var a = pool.submit(() -> {
                    gate.await();
                    return start("concurrent_start_001");
                });
                var b = pool.submit(() -> {
                    gate.await();
                    return start("concurrent_start_001");
                });
                gate.countDown();
                assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void repairAndAttemptBudgetSurviveRecovery() throws Exception {
            String id = start("budget_start_001");
            var claim = store.claim(store.get(owner, id));
            for (int i = 0; i < 4; i++) {
                modelCheckpoint(claim, i < 2 ? "ACTION" : "FINAL", i == 3);
                store.reserveModelAttempt(claim, prepared("hash")).id();
            }
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("fifth")).id()).hasMessage("RUN_BUDGET");
            assertThatThrownBy(() -> store.batch(claim, 1, "{}")).hasMessage("RUN_TOOL_BUDGET");
            assertThat(store.get(owner, id).attempts()).isEqualTo(4);
        }

        @Test
        void retentionErasesPrivateEvidenceButPreservesTerminalIdempotency() throws Exception {
            String id = start("retention_start_001");
            run(id);
            jdbc.update("UPDATE interview_runs SET updated_at=TIMESTAMPADD(DAY,-8,CURRENT_TIMESTAMP(6)) WHERE id=?", id);
            store.prune();
            assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            assertThat(store.get(owner, id).input()).isEqualTo("{}");
            assertThat(store.get(owner, id).checkpoint()).isNull();
            assertThat(start("retention_start_001")).isEqualTo(id);
            assertThat(
                    jdbc.queryForObject("SELECT COUNT(*) FROM interview_model_attempts WHERE run_id=?", Integer.class, id))
                    .isEqualTo(2);
        }

        @Test
        void trainingTaskUsesOneDurableStartAndCancelledKeyDoesNotRestartIt() throws Exception {
            String source = opened();
            for (int turn = 1; turn <= 3; turn++) {
                var accepted = performPost("/api/interviews/sessions/" + source + "/answer-commands", Map.of("requestId",
                        "training_source_" + turn, "expectedTurn", turn, "answer", "我负责通过数据库事务和条件更新验证本人修改，失败后重新读取并说明限制。"),
                        token);
                assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
                run((String) dataMap(accepted).get("runId"));
            }
            var task = dataMap(performPost("/api/interviews/training-tasks",
                    Map.of("sourceSessionId", source, "sourceRound", 1), token));
            String taskId = (String) task.get("id"), path = "/api/interviews/training-tasks/" + taskId + "/start-commands";
            var first = performPost(path, Map.of("requestId", "task_start_001"), token);
            assertThat(first.getResponse().getStatus()).isEqualTo(202);
            String id = (String) dataMap(first).get("runId");
            assertThat(dataMap(performPost(path, Map.of("requestId", "task_start_002"), token)).get("runId")).isEqualTo(id);
            assertThat(performPost("/api/interviews/training-tasks/" + taskId + "/start", Map.of(), token).getResponse()
                    .getStatus()).isEqualTo(404);
            store.cancel(owner, id);
            assertThat(dataMap(performPost(path, Map.of("requestId", "task_start_001"), token)).get("status"))
                    .isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT status FROM interview_training_tasks WHERE id=?", String.class, taskId))
                    .isEqualTo("TODO");
            String fresh = (String) dataMap(performPost(path, Map.of("requestId", "task_start_003"), token)).get("runId");
            run(fresh);
            assertThat(store.get(owner, fresh).status()).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT active_session_id FROM interview_training_tasks WHERE id=?",
                    String.class, taskId)).isEqualTo(store.get(owner, fresh).sessionId());
        }

        @Test
        void databaseCommitFailureRollsBackSessionRunAndFinalEventTogether() throws Exception {
            String session = opened(), id = answer(session, "atomic_answer_001");
            var claim = store.claim(store.get(owner, id));
            var original = sessions.findById(owner, session).orElseThrow();
            var pending = (cn.edu.whu.zhizhida.interview.domain.InterviewTransitions.PendingAnswer) new cn.edu.whu.zhizhida.interview.domain.InterviewTransitions()
                    .answer(original, "合成回答", "atomic_answer_001", 1);
            var candidate = pending.complete("反馈", "下一问题", "");
            String trigger = "agent_commit_failure_" + owner;
            jdbc.execute("CREATE TRIGGER " + trigger
                    + " BEFORE UPDATE ON interview_sessions FOR EACH ROW BEGIN IF NEW.session_id='" + session
                    + "' AND NEW.turn_count=2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic commit fault'; END IF; END");
            try {
                assertThatThrownBy(() -> store.commit(claim, candidate, false)).isInstanceOf(RuntimeException.class);
                assertThat(store.get(owner, id).status()).isEqualTo("RUNNING");
                assertThat(sessions.findById(owner, session).orElseThrow().turnCount()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT active_run_id FROM interview_sessions WHERE session_id=?",
                        String.class, session)).isEqualTo(id);
                assertThat(store.events(owner, id, 0)).extracting(InterviewRun.Event::type)
                        .doesNotContain("turn.committed");
            } finally {
                jdbc.execute("DROP TRIGGER " + trigger);
                store.cancel(owner, id);
            }
        }

        private java.net.http.HttpResponse<java.io.InputStream> stream(String id, String suffix, String identity, Long last)
                throws Exception {
            var request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI
                            .create("http://127.0.0.1:" + port + "/api/interview-runs/" + id + "/events" + suffix))
                    .timeout(java.time.Duration.ofSeconds(25));
            if (identity != null)
                request.header("Authorization", "Bearer " + identity);
            if (last != null)
                request.header("Last-Event-ID", Long.toString(last));
            return java.net.http.HttpClient.newHttpClient().send(request.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        }

        private String frame(java.io.BufferedReader reader) throws Exception {
            var lines = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty())
                    break;
                lines.append(line).append("\n");
            }
            return lines.toString();
        }

        @Test
        void realHttpSseHeartbeatCancellationAndCursorReplayUseOnlyPublicEvents() throws Exception {
            String id = start("http_sse_heartbeat_001");
            var response = stream(id, "?afterSeq=0", token, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                assertThat(frame(reader)).contains("run.accepted", "id:1").doesNotContain("resumeSnapshot",
                        "agentDecision");
                long started = System.nanoTime();
                assertThat(frame(reader)).contains("heartbeat");
                assertThat((System.nanoTime() - started) / 1000000).isBetween(13000L, 22000L);
                store.cancel(owner, id);
                assertThat(frame(reader)).contains("run.cancelled", "id:2");
            }
            var replay = stream(id, "?afterSeq=0", token, 1L);
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(replay.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                assertThat(frame(reader)).contains("run.cancelled").doesNotContain("run.accepted");
            }
            for (var args : List.of("?afterSeq=-1", "?afterSeq=0")) {
                var invalid = stream(id, args, token, args.equals("?afterSeq=0") ? -1L : null);
                try (var body = invalid.body()) {
                    assertThat(invalid.statusCode()).isEqualTo(400);
                }
            }
            var missingIdentity = stream(id, "", null, null);
            try (var body = missingIdentity.body()) {
                assertThat(missingIdentity.statusCode()).isEqualTo(401);
            }
        }

        @Test
        void subscriptionLimitsDoNotStartWorkAndReleaseAfterCancellation() throws Exception {
            String id = start("http_sse_limit_001");
            var streams = new java.util.ArrayList<java.net.http.HttpResponse<java.io.InputStream>>();
            try {
                for (int i = 0; i < 3; i++) {
                    var r = stream(id, "", token, null);
                    assertThat(r.statusCode()).isEqualTo(200);
                    streams.add(r);
                }
                var overflow = stream(id, "", token, null);
                try (var body = overflow.body()) {
                    assertThat(overflow.statusCode()).isEqualTo(429);
                }
                assertThat(store.get(owner, id).attempts()).isZero();
                store.cancel(owner, id);
                for (var r : streams)
                    try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(r.body()))) {
                        String first = frame(reader);
                        String second = first.contains("run.cancelled") ? "" : frame(reader);
                        assertThat(first + second).contains("run.cancelled");
                    }
            } finally {
                for (var r : streams)
                    r.body().close();
                store.cancel(owner, id);
            }
        }

        @Test
        void sharedToolAndRepairBudgetsRejectBeforeAnyExternalAttempt() throws Exception {
            String id = start("tool_budget_001");
            var claim = store.claim(store.get(owner, id));
            assertThat(store.claim(store.get(owner, id))).isNull();
            assertThat(store.heartbeat(claim)).isTrue();
            assertThatThrownBy(() -> store.batch(claim, 0, "{}")).hasMessage("RUN_TOOL_BUDGET");
            store.batch(claim, 2, "{}");
            assertThatThrownBy(() -> store.batch(claim, Integer.MAX_VALUE, "{}")).hasMessage("RUN_TOOL_BUDGET");
            assertThatThrownBy(() -> store.batch(claim, 2, "{}")).hasMessage("RUN_TOOL_BUDGET");
            store.batch(claim, 1, "{}");
            assertThatThrownBy(() -> store.batch(claim, 1, "{}")).hasMessage("RUN_TOOL_BUDGET");
            store.repair(claim, "{}");
            assertThatThrownBy(() -> store.repair(claim, "{}")).hasMessage("RUN_REPAIR_LIMIT");
            assertThatThrownBy(() -> store.response(claim, "nonexistent", "{}", "{}", 1, "{}", "MODEL"))
                    .hasMessage("RUN_FENCED");
            assertThat(store.get(owner, id).attempts()).isZero();
            store.cancel(owner, id);
            assertThat(store.claim(store.get(owner, id))).isNull();
            String deadline = start("call_deadline_001");
            var late = store.claim(store.get(owner, deadline));
            modelCheckpoint(late, "ACTION", false);
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,4,CURRENT_TIMESTAMP(6)) WHERE id=?",
                    deadline);
            assertThatThrownBy(() -> store.reserveModelAttempt(late, prepared("hash")).id()).hasMessage("RUN_BUDGET");
            store.cancel(owner, deadline);
        }
    }

    @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="interview.runs.worker-enabled=false")
    @Nested
    class ConcurrentCommit extends InterviewScenario {
        @Autowired InterviewRunStore store;
        @Autowired InterviewRunWorker worker;
        @MockitoSpyBean InterviewSessionRepository sessions;

        InterviewRunStore.Claim accept() throws Exception {
            String token=account("并发提交");
            long owner=ownerId(token);
            var accepted=performPost("/api/interviews/start-commands",Map.of("requestId",UUID.randomUUID().toString(),
                    "configuration",Map.of("targetRole","Java 后端","totalRounds",3,"experienceMode","FORMAL")),token);
            assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
            String id=objectMapper.readTree(accepted.getResponse().getContentAsString()).at("/data/runId").asText();
            return store.claim(store.get(owner,id));
        }

        @Test void rolledBackSessionInsertRetriesWithoutAnotherModelAttemptOrDuplicateEvent() throws Exception {
            var claim=accept();
            var saves=new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(invocation -> {
                var saved=invocation.callRealMethod();
                if(saves.incrementAndGet()==1) throw new org.springframework.dao.CannotAcquireLockException("injected after insert");
                return saved;
            }).when(AopTestUtils.<InterviewSessionRepository>getUltimateTargetObject(sessions)).save(anyLong(),any(InterviewSession.class));
            worker.execute(claim);
            assertThat(saves.get()).isEqualTo(2);
            assertThat(store.get(claim.owner(),claim.id()).status()).isEqualTo("SUCCEEDED");
            assertThat(store.get(claim.owner(),claim.id()).attempts()).isEqualTo(2);
            assertThat(sessions.page(claim.owner(), new PageRequest(1, 1)).total()).isEqualTo(1);
            assertThat(store.events(claim.owner(),claim.id(),0)).filteredOn(e -> e.type().equals("turn.committed")).hasSize(1);
        }

        @Test void concurrentOpeningsCommitWithoutLockingMissingSessionGaps() throws Exception {
            var claims=new ArrayList<InterviewRunStore.Claim>();
            for(int i=0;i<2;i++) claims.add(accept());
            var entered=new CountDownLatch(2);
            var saves=new java.util.concurrent.atomic.AtomicInteger();
            // 两个已校验执行权的事务都到达 INSERT 边界后才允许创建会话，确保覆盖并发插入。
            doAnswer(invocation -> {
                saves.incrementAndGet();
                entered.countDown();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                return invocation.callRealMethod();
            }).when(AopTestUtils.<InterviewSessionRepository>getUltimateTargetObject(sessions)).save(anyLong(),any(InterviewSession.class));
            try(var pool=Executors.newFixedThreadPool(2)) {
                var tasks=claims.stream().map(c -> pool.submit(() -> worker.execute(c))).toList();
                for(var task:tasks) task.get(15,TimeUnit.SECONDS);
            }
            assertThat(saves.get()).isEqualTo(2);
            for(var c:claims) {
                var row=store.get(c.owner(),c.id());
                assertThat(row.status()).as("error=%s",row.error()).isEqualTo("SUCCEEDED");
                assertThat(row.attempts()).isEqualTo(2);
                assertThat(sessions.findById(c.owner(),c.sessionId())).isPresent();
                assertThat(store.events(c.owner(),c.id(),0)).filteredOn(e -> e.type().equals("turn.committed")).hasSize(1);
            }
        }
    }

    @MockitoSpyBean(types = InterviewRunStore.class)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "interview.runs.worker-enabled=false")
    @Nested
    class SourceRevocation extends InterviewRunScenario {
        @MockitoSpyBean InterviewRunModel model;
        @MockitoSpyBean KnowledgeRepository documents;
        @Autowired InterviewSessionRepository sessions;
        @Autowired InterviewSourceGuard guard;
        @Autowired TransactionTemplate tx;
        @Autowired JdbcTemplate jdbc;
        String doc;
        static final String ANSWER = "我会使用请求标识核对事务结果，尚未验证所有故障窗口。";

        @BeforeEach void setup() throws Exception {
            doc = data(performPost("/api/knowledge/documents",Map.of("title","事务资料","content","事务的唯一约束需要核对请求标识及冲突后的原结果。"),token)).path("id").asText();
        }
        tools.jackson.databind.JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
            return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        }
        Map<String,Object> configuration(String format) {
            return Map.of("targetRole","Java 后端","trainingTopic","DATABASE","totalRounds",3,"knowledgeDocumentIds",List.of(doc),"experienceMode",format);
        }
        String opened(String format) throws Exception {
            var result = startInterview(configuration(format),token);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            return data(result).path("sessionId").asText();
        }
        Map<String,Object> answer(int round,String key) { return Map.of("requestId",key,"expectedTurn",round,"answer",ANSWER); }
        String accepted(String sid,String key) throws Exception {
            var result=performPost("/api/interviews/sessions/"+sid+"/answer-commands",answer(1,key),token);
            assertThat(result.getResponse().getStatus()).isEqualTo(202);
            return data(result).path("runId").asText();
        }
        void deleteDoc() throws Exception { assertThat(performDelete("/api/knowledge/documents/"+doc,token).getResponse().getStatus()).isEqualTo(200); }
        static void await(CountDownLatch latch) throws InterruptedException { assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue(); }

        @Test void deletionAfterAcceptanceFailsBeforeAnyModelAttemptAndReplayDoesNotRestart() throws Exception {
            String sid=opened("TEACHING"), id=accepted(sid,"queued_revocation_001");
            deleteDoc(); run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("FAILED");
            assertThat(store.get(owner,id).error()).isEqualTo("RUN_SOURCE_REVOKED");
            assertThat(store.get(owner,id).attempts()).isZero();
            var replay=performPost("/api/interviews/sessions/"+sid+"/answer-commands",answer(1,"queued_revocation_001"),token);
            assertThat(data(replay).path("runId").asText()).isEqualTo(id);
            assertThat(performPost("/api/interviews/sessions/"+sid+"/answer-commands",answer(1,"new_revoked_request"),token).getResponse().getStatus()).isEqualTo(409);
            assertThat(sessions.findById(owner,sid).orElseThrow().turns()).noneMatch(t -> t.speaker().equals("user"));
        }
        @Test void deletionDuringGenerationBlocksFinalCommitAndReleasesRun() throws Exception {
            String sid=opened("TEACHING"), id=accepted(sid,"generation_revocation_001");
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var result=invocation.callRealMethod(); entered.countDown(); await(release); return result;
            }).when(model).call(anyString(),anyList(),anyList(),anyBoolean(),anyLong(),anyString(),
                    any(),any(),anyString(),any());
            try (var executor=Executors.newSingleThreadExecutor()) {
                var execution=executor.submit(() -> run(id));
                try { await(entered); deleteDoc(); } finally { release.countDown(); }
                execution.get(5,TimeUnit.SECONDS);
            }
            assertThat(store.get(owner,id).status()).isEqualTo("FAILED");
            assertThat(store.get(owner,id).error()).isEqualTo("RUN_SOURCE_REVOKED");
            var session=sessions.findById(owner,sid).orElseThrow();
            assertThat(session.turns()).noneMatch(t -> t.speaker().equals("user"));
            assertThat(jdbc.queryForObject("SELECT active_run_id FROM interview_sessions WHERE user_id=? AND session_id=?",String.class,owner,sid)).isNull();
        }
        @Test void deletionBetweenSavedModelReplyAndRunCommitWinsAgainstArchivedCheckpoint() throws Exception {
            String sid=opened("FORMAL"), id=accepted(sid,"reply_revocation_001");
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
            doAnswer(invocation -> { entered.countDown(); await(release); return invocation.callRealMethod(); })
                    .when(store).commit(any(),any(),eq(false));
            try (var executor=Executors.newSingleThreadExecutor()) {
                var execution=executor.submit(() -> run(id));
                try { await(entered); deleteDoc(); } finally { release.countDown(); }
                execution.get(5,TimeUnit.SECONDS);
            }
            assertThat(store.get(owner,id).status()).isEqualTo("FAILED");
            assertThat(store.get(owner,id).error()).isEqualTo("RUN_SOURCE_REVOKED");
            assertThat(store.get(owner,id).attempts()).isPositive();
            assertThat(sessions.findById(owner,sid).orElseThrow().turns()).noneMatch(t -> t.speaker().equals("user"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_events WHERE run_id=? AND type='turn.committed'",Integer.class,id)).isZero();
            var view=data(performGet("/api/interviews/sessions/"+sid,token));
            assertThat(view.path("sourceRevoked").asBoolean()).isTrue(); assertThat(view.path("agent").isNull()).isTrue();
        }
        @Test void finalSaveHoldsSharedSourceLockUntilCommitSoDeletionWaits() throws Exception {
            String sid=opened("TEACHING");
            var held=new CountDownLatch(1); var release=new CountDownLatch(1); var deleting=new CountDownLatch(1);
            doAnswer(invocation -> { deleting.countDown(); return invocation.callRealMethod(); }).when(documents).delete(eq(owner),eq(doc));
            try (var executor=Executors.newFixedThreadPool(2)) {
                var save=executor.submit(() -> tx.execute(status -> {
                    sessions.save(owner,sessions.findById(owner,sid).orElseThrow()); held.countDown();
                    try { await(release); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return null;
                }));
                await(held);
                var deletion=executor.submit(() -> { deleteDoc(); return null; });
                try { await(deleting); assertThatThrownBy(() -> deletion.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                finally { release.countDown(); }
                save.get(5,TimeUnit.SECONDS); deletion.get(5,TimeUnit.SECONDS);
            }
            assertThat(data(performGet("/api/interviews/sessions/"+sid,token)).path("sourceRevoked").asBoolean()).isTrue();
        }
        @Test void withdrawnHistoryIsReadOnlyAndExcludedFromSubsequentMemoryAndTasks() throws Exception {
            String sid=opened("TEACHING");
            for(int i=1;i<=3;i++) assertThat(answerInterview(sid,answer(i,"history_revocation_"+i),token).getResponse().getStatus()).isEqualTo(200);
            deleteDoc();
            var view=data(performGet("/api/interviews/sessions/"+sid,token));
            assertThat(view.path("sourceRevoked").asBoolean()).isTrue();
            assertThat(view.path("agent").path("training").path("knowledge").get(0).path("content").asText()).isEmpty();
            assertThat(view.path("turns").toString()).contains(ANSWER);
            assertThat(performPost("/api/interviews/training-tasks",Map.of("sourceSessionId",sid,"sourceRound",1),token).getResponse().getStatus()).isEqualTo(409);
            assertThat(data(performGet("/api/interviews/sessions/"+sid+"/resources",token)).isEmpty()).isTrue();
            var replay=answerInterview(sid,answer(1,"history_revocation_1"),token);
            assertThat(replay.getResponse().getStatus()).isEqualTo(200);
            assertThat(data(replay).path("sourceRevoked").asBoolean()).isTrue();
            assertThat(performPost("/api/interviews/start-commands",Map.of("requestId","retry_revocation_001","configuration",
                    Map.of("targetRole","复练","totalRounds",3,"retrySessionId",sid,"retryRound",1)),token).getResponse().getStatus()).isEqualTo(409);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/revoked-interview-session.json"),view.toPrettyString());
        }
        @Test void anotherAccountsDeleteCannotWithdrawOwnedSourceAndLockPortRequiresTransaction() throws Exception {
            String sid=opened("TEACHING"); String other=account("其他账号");
            assertThat(performDelete("/api/knowledge/documents/"+doc,other).getResponse().getStatus()).isEqualTo(404);
            assertThat(answerInterview(sid,answer(1,"owned_source_001"),token).getResponse().getStatus()).isEqualTo(200);
            assertThat(data(performGet("/api/interviews/sessions/"+sid,token)).path("sourceRevoked").asBoolean()).isFalse();
            assertThatThrownBy(() -> guard.lock(owner,sessions.findById(owner,sid).orElseThrow()))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        }
        @Test void deadlineBecomingInvalidDuringFinalSourceAuthorizationRollsBackTheWholeCommit() throws Exception {
            String sid=opened("TEACHING"), id=accepted(sid,"source_wait_deadline_001");
            doAnswer(invocation -> {
                var rows=invocation.callRealMethod();
                // 在同一最终提交事务中，首次运行有效性检查通过后定点注入故障。
                jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
                return rows;
            }).when(documents).ownedIds(eq(owner),anyList(),eq(true));
            run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("FAILED");
            assertThat(store.get(owner,id).error()).isEqualTo("RUN_FENCED");
            assertThat(sessions.findById(owner,sid).orElseThrow().turns()).noneMatch(t -> t.speaker().equals("user"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_events WHERE run_id=? AND type='turn.committed'",Integer.class,id)).isZero();
        }
    }
}
