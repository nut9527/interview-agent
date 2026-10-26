package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewAiReply;

import cn.edu.whu.zhizhida.interview.api.InterviewAiService;

import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 在已应用 V11 迁移的真实 MySQL 中验证租约语句，不使用内存数据库替代。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jobs.remote.enabled=false", "demo.enabled=false", "ai.provider=mock"})
class InterviewAnswerIntegrationIT extends IntegrationTestBase {
    @MockBean private InterviewAiService ai;
    @Autowired private InterviewSessionRepository repository;
    @Autowired private JdbcTemplate jdbc;
    private String token;
    private String sessionId;
    private Long userId;

    @BeforeEach
    void createSession() throws Exception {
        when(ai.openingQuestion(anyString(), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "请介绍项目。";
        });
        token = registerAndLoginWithUniqueUsername("password123", "面试并发测试");
        MvcResult created = performPost("/api/interviews/sessions", Map.of("targetRole", "Java 后端"), token);
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        sessionId = (String) data(created).get("sessionId");
        userId = jdbc.queryForObject("SELECT user_id FROM interview_sessions WHERE session_id = ?", Long.class, sessionId);
    }

    @AfterEach
    void deleteOnlyCreatedAccount() throws Exception {
        if (token != null) {
            assertThat(performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token)
                    .getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void slowModelHasNoOpenTransactionAndOnlyOneAnswerCommits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(ai.reviewAnswer(anyString(), anyString(), anyList(), anyInt(), anyBoolean())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(finish.await(15, TimeUnit.SECONDS)).isTrue();
            return new InterviewAiReply("有明确职责", "如何处理并发？", "");
        });
        var workers = Executors.newSingleThreadExecutor();
        String path = "/api/interviews/sessions/" + sessionId + "/answers";
        Map<String, Object> request = Map.of("answer", "使用条件更新保证一致性", "requestId", "answer_001", "expectedTurn", 1);
        try {
            var first = workers.submit(() -> performPost(path, request, token));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT answer_lease_token FROM interview_sessions WHERE session_id = ?",
                    String.class, sessionId)).isNotBlank();
            assertThat(performPost(path, Map.of("answer", "第二份回答", "requestId", "answer_002", "expectedTurn", 1), token)
                    .getResponse().getStatus()).isEqualTo(409);
            finish.countDown();
            MvcResult result = first.get(15, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            MvcResult replay = performPost(path, request, token);
            assertThat(replay.getResponse().getStatus()).isEqualTo(200);
            assertThat(data(replay)).isEqualTo(data(result));
            InterviewSession saved = repository.findById(userId, sessionId).orElseThrow();
            assertThat(saved.turnCount()).isEqualTo(2);
            assertThat(saved.turns().stream().filter(turn -> "user".equals(turn.speaker())).toList()).hasSize(1);
            verify(ai, times(1)).reviewAnswer(anyString(), anyString(), anyList(), anyInt(), anyBoolean());
        } finally {
            finish.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void databaseExpiryFencesOldWorkerAndAllWritesAreUserScoped() {
        InterviewSession original = repository.findById(userId, sessionId).orElseThrow();
        assertThat(repository.tryAcquireAnswer(userId + 1, sessionId, 1, "foreign", 30)).isFalse();
        assertThat(repository.tryAcquireAnswer(userId, sessionId, 1, "old", 30)).isTrue();
        assertThat(repository.tryAcquireAnswer(userId, sessionId, 1, "new", 30)).isFalse();
        jdbc.update("UPDATE interview_sessions SET answer_lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) "
                + "WHERE session_id = ? AND user_id = ?", sessionId, userId);
        assertThat(repository.completeAnswer(userId, original, 1, "old")).isFalse();
        assertThat(repository.tryAcquireAnswer(userId, sessionId, 1, "new", 30)).isTrue();
        InterviewSession updated = new InterviewSession(sessionId, original.targetRole(), original.resumeId(),
                original.resumeSnapshot(), InterviewState.WAITING_ANSWER, 2, List.of(
                original.turns().get(0), original.turns().get(1), new InterviewTurn("user", "回答", "answer_001"),
                new InterviewTurn("system", "反馈"), new InterviewTurn("interviewer", "第二题")));
        assertThat(repository.completeAnswer(userId, updated, 1, "old")).isFalse();
        repository.releaseAnswer(userId, sessionId, "old");
        assertThat(repository.completeAnswer(userId + 1, updated, 1, "new")).isFalse();
        assertThat(repository.completeAnswer(userId, updated, 1, "new")).isTrue();
        assertThat(repository.completeAnswer(userId, updated, 1, "new")).isFalse();
        assertThat(repository.findById(userId, sessionId)).contains(updated);
        assertThat(jdbc.queryForObject("SELECT answer_lease_token FROM interview_sessions WHERE session_id = ?",
                String.class, sessionId)).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MvcResult result) throws Exception {
        return (Map<String, Object>) parseJsonResult(result).get("data");
    }
}
