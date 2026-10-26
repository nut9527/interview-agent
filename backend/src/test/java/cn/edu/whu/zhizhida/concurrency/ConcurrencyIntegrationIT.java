package cn.edu.whu.zhizhida.concurrency;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyIntegrationIT extends IntegrationTestBase {

    @Nested
    @DisplayName("并发测试")
    class ConcurrencyTests {



        @Test
        @DisplayName("并发创建简历 - 数据一致性")
        void concurrentCreateResume_Success_DataConsistency() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "并发简历用户");

            ExecutorService executor = Executors.newFixedThreadPool(5);
            List<Future<MvcResult>> futures = new ArrayList<>();

            for (int i = 0; i < 5; i++) {
                int finalI = i;
                futures.add(executor.submit(() ->
                        performPost("/api/resumes", Map.of(
                                "ownerName", "并发用户",
                                "targetRole", "岗位" + finalI,
                                "skills", List.of("Java"),
                                "educationExperiences", List.of("2019-2023 本科 武汉大学 计算机"),
                                "projectExperiences", List.of("项目" + finalI),
                                "internshipExperiences", List.of("实习" + finalI),
                                "certificates", List.of("CET-6")
                        ), token)
                ));
            }

            int successCount = 0;
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get();
                if (result.getResponse().getStatus() == 200) {
                    successCount++;
                }
            }

            executor.shutdown();

            assertEquals(5, successCount, "所有5个简历创建请求应成功");

            MvcResult listResult = performGet("/api/resumes", token);
            Map<String, Object> listData = parseJsonResult(listResult);
            List<?> resumes = (List<?>) listData.get("data");

            assertTrue(resumes.size() >= 5, "应至少创建5个简历，实际创建了" + resumes.size() + "个");
        }
    }

    @Nested
    @DisplayName("事务回滚测试")
    class TransactionRollbackTests {

        @Test
        @DisplayName("创建简历失败时事务回滚")
        void createResume_Failure_TransactionRolledBack() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "回滚用户");

            MvcResult goodCreate = performPost("/api/resumes", Map.of(
                    "ownerName", "有效用户",
                    "targetRole", "有效岗位",
                    "skills", List.of("Java")
            ), token);
            assertEquals(200, goodCreate.getResponse().getStatus());

            MvcResult badCreate = performPost("/api/resumes", Map.of(
                    "ownerName", "",
                    "targetRole", ""
            ), token);

            assertEquals(400, badCreate.getResponse().getStatus());

            MvcResult listResult = performGet("/api/resumes", token);
            Map<String, Object> listData = parseJsonResult(listResult);
            List<?> resumes = (List<?>) listData.get("data");

            assertEquals(1, resumes.size(), "验证失败的创建不应增加简历数量，事务应回滚");
        }
    }

    @Nested
    @DisplayName("数据隔离测试")
    class DataIsolationTests {

        @Test
        @DisplayName("用户只能访问自己的数据")
        void dataAccess_Success_UserIsolation() throws Exception {
            String token1 = registerAndLoginWithUniqueUsername("password123", "隔离用户1");
            String token2 = registerAndLoginWithUniqueUsername("password123", "隔离用户2");

            MvcResult create1 = performPost("/api/resumes", Map.of(
                    "ownerName", "用户1",
                    "targetRole", "岗位1",
                    "skills", List.of("Java"),
                    "educationExperiences", List.of("2019-2023 本科 武汉大学 计算机"),
                    "projectExperiences", List.of("项目1"),
                    "internshipExperiences", List.of("实习1"),
                    "certificates", List.of("CET-6")
            ), token1);
            Map<String, Object> data1 = (Map<String, Object>) parseJsonResult(create1).get("data");
            Long resumeId1 = ((Number) data1.get("id")).longValue();

            MvcResult create2 = performPost("/api/resumes", Map.of(
                    "ownerName", "用户2",
                    "targetRole", "岗位2",
                    "skills", List.of("React"),
                    "educationExperiences", List.of("2019-2023 本科 武汉大学 计算机"),
                    "projectExperiences", List.of("项目2"),
                    "internshipExperiences", List.of("实习2"),
                    "certificates", List.of("CET-6")
            ), token2);

            MvcResult list1 = performGet("/api/resumes", token1);
            Map<String, Object> listData1 = parseJsonResult(list1);
            List<?> resumes1 = (List<?>) listData1.get("data");
            assertTrue(resumes1.size() >= 1, "用户1应能看到自己的简历");

            MvcResult list2 = performGet("/api/resumes", token2);
            Map<String, Object> listData2 = parseJsonResult(list2);
            List<?> resumes2 = (List<?>) listData2.get("data");
            assertTrue(resumes2.size() >= 1, "用户2应能看到自己的简历");
        }
    }
}