package cn.edu.whu.zhizhida.concurrency;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyIntegrationIT extends IntegrationTestBase {

    @Test
    void persistsEveryConcurrentResumeCreation() throws Exception {
        String token = registerUniqueUserToken("password123", "并发简历用户");
        var executor = Executors.newFixedThreadPool(5);
        var createdIds = new HashSet<Long>();
        try {
            var futures = new ArrayList<Future<MvcResult>>();
            for (int i = 0; i < 5; i++) {
                String role = "岗位" + i;
                futures.add(executor.submit(() -> performPost("/api/resumes", Map.of(
                        "ownerName", "并发用户", "targetRole", role, "skills", List.of("Java")), token)));
            }
            for (var future : futures) {
                var result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                var resume = (Map<?, ?>) parseJsonResult(result).get("data");
                createdIds.add(((Number) resume.get("id")).longValue());
            }
        } finally {
            executor.shutdownNow();
        }

        var listResult = performGet("/api/resumes", token);
        assertThat(listResult.getResponse().getStatus()).isEqualTo(200);
        var resumes = (List<?>) parseJsonResult(listResult).get("data");
        assertThat(createdIds).hasSize(5);
        assertThat(resumes).hasSize(5).extracting(item -> ((Number) ((Map<?, ?>) item).get("id")).longValue())
                .containsExactlyInAnyOrderElementsOf(createdIds);
    }
}
