package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 在专用 MySQL 测试库中验证真实事务、修订号竞争及版本持久化。 */
@TestPropertySource(properties = {"jobs.remote.enabled=false", "demo.enabled=false", "ai.provider=mock"})
class ResumeRevisionIntegrationIT extends IntegrationTestBase {
    @MockitoSpyBean ResumeVersionRepository versionRepository;
    private String token;
    private long resumeId;

    @BeforeEach
    void createOwnedResume() throws Exception {
        token = registerAndLoginWithUniqueUsername("password123", "修订号测试");
        MvcResult created = performPost("/api/resumes", draft("initial", 0), token);
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        resumeId = ((Number) data(created).get("id")).longValue();
        assertThat(((Number) data(created).get("revision")).longValue()).isZero();
    }

    @AfterEach
    void deleteOnlyCreatedAccount() throws Exception {
        if (token != null) {
            assertThat(performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token)
                    .getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void competingUpdatesHaveOneWinnerAndOneSnapshot() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return performPut("/api/resumes/" + resumeId, draft("first", 0), token);
            });
            var second = workers.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return performPut("/api/resumes/" + resumeId, draft("second", 0), token);
            });
            start.countDown();
            MvcResult a = first.get(15, TimeUnit.SECONDS);
            MvcResult b = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
            Map<String, Object> saved = data(performGet("/api/resumes/" + resumeId, token));
            assertThat(((Number) saved.get("revision")).longValue()).isEqualTo(1);
            assertThat(saved.get("summary")).isEqualTo(data(a.getResponse().getStatus() == 200 ? a : b).get("summary"));
            List<?> versions = (List<?>) ((Map<?, ?>) parseJsonResult(performGet("/api/resumes/" + resumeId + "/versions", token)).get("data")).get("items");
            assertThat(versions).hasSize(2);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void snapshotFailureRollsBackBodyAndRevisionThenSameRequestCanSucceed() throws Exception {
        doThrow(new RuntimeException("injected snapshot storage failure")).when(versionRepository)
                .save(anyLong(), any(), eq("UPDATED"), anyString());
        try {
            assertThat(performPut("/api/resumes/" + resumeId, draft("must rollback", 0), token)
                    .getResponse().getStatus()).isEqualTo(500);
            Map<String, Object> saved = data(performGet("/api/resumes/" + resumeId, token));
            assertThat(saved.get("summary")).isEqualTo("initial");
            assertThat(((Number) saved.get("revision")).longValue()).isZero();
            assertThat((List<?>) ((Map<?, ?>) parseJsonResult(performGet("/api/resumes/" + resumeId + "/versions", token)).get("data")).get("items"))
                    .hasSize(1);
        } finally { reset(versionRepository); }
        assertThat(performPut("/api/resumes/" + resumeId, draft("retry", 0), token)
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void restoreKeepsRevisionMonotonicAndRejectsStaleEditors() throws Exception {
        List<?> versions = (List<?>) ((Map<?, ?>) parseJsonResult(performGet("/api/resumes/" + resumeId + "/versions", token)).get("data")).get("items");
        long versionId = ((Number) ((Map<?, ?>) versions.get(0)).get("id")).longValue();
        assertThat(performPut("/api/resumes/" + resumeId, draft("changed", 0), token)
                .getResponse().getStatus()).isEqualTo(200);
        String path = "/api/resumes/" + resumeId + "/versions/" + versionId + "/restore";
        assertThat(performPost(path, Map.of("revision", 0), token).getResponse().getStatus()).isEqualTo(409);
        MvcResult restored = performPost(path, Map.of("revision", 1), token);
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(restored).get("summary")).isEqualTo("initial");
        assertThat(((Number) data(restored).get("revision")).longValue()).isEqualTo(2);
        assertThat(performPut("/api/resumes/" + resumeId, draft("stale", 1), token)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cannotUpdateAnotherUsersResumeEvenWithCorrectRevision() throws Exception {
        String other = registerAndLoginWithUniqueUsername("password123", "另一个用户");
        try {
            assertThat(performPut("/api/resumes/" + resumeId, draft("foreign", 0), other)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(data(performGet("/api/resumes/" + resumeId, token)).get("summary")).isEqualTo("initial");
        } finally {
            assertThat(performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), other)
                    .getResponse().getStatus()).isEqualTo(200);
        }
    }

    private Map<String, Object> draft(String summary, long revision) {
        return Map.of("ownerName", "修订号测试", "targetRole", "Java", "summary", summary, "revision", revision);
    }



    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MvcResult result) throws Exception {
        return (Map<String, Object>) parseJsonResult(result).get("data");
    }
}
