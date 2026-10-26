package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterviewIntegrationIT extends IntegrationTestBase {

    @Nested
    @DisplayName("面试会话测试")
    class InterviewSessionTests {

        @Test
        @DisplayName("开始面试会话 - 成功创建")
        void startSession_Success_Created() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "面试用户");

            MvcResult result = performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "Java开发工程师",
                    "resumeSummary", "3年Java开发经验，熟悉Spring Boot"
            ), token);

            assertEquals(200, result.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertNotNull(data.get("sessionId"), "会话ID不应为空");
            assertEquals("Java开发工程师", data.get("targetRole"));
            assertNotNull(data.get("state"), "状态不应为空");
        }

        @Test
        @DisplayName("开始面试会话 - 缺少必填参数")
        void startSession_Failure_MissingRequired() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "面试用户");

            MvcResult result = performPost("/api/interviews/sessions", Map.of(
                    "targetRole", ""
            ), token);

            assertEquals(400, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("获取会话详情 - 返回完整会话信息")
        void getSessionDetail_Success_ReturnsSession() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "会话详情用户");

            MvcResult createResult = performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "前端开发工程师",
                    "resumeSummary", "2年前端开发经验"
            ), token);

            Map<String, Object> createResponse = parseJsonResult(createResult);
            Map<String, Object> createData = (Map<String, Object>) createResponse.get("data");
            String sessionId = (String) createData.get("sessionId");

            MvcResult detailResult = performGet("/api/interviews/sessions/" + sessionId, token);
            assertEquals(200, detailResult.getResponse().getStatus());

            Map<String, Object> detailResponse = parseJsonResult(detailResult);
            assertTrue((Boolean) detailResponse.get("success"));

            Map<String, Object> detailData = (Map<String, Object>) detailResponse.get("data");
            assertEquals(sessionId, detailData.get("sessionId"));
            assertEquals("前端开发工程师", detailData.get("targetRole"));
        }

        @Test
        @DisplayName("获取会话详情 - 会话不存在")
        void getSessionDetail_Failure_NotFound() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "会话详情用户");

            MvcResult detailResult = performGet("/api/interviews/sessions/nonexistent", token);
            assertEquals(404, detailResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("回答问题 - 成功更新会话状态")
        void answerQuestion_Success_UpdatesSession() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "答题用户");

            MvcResult createResult = performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "全栈开发工程师",
                    "resumeSummary", "3年全栈经验"
            ), token);

            Map<String, Object> createResponse = parseJsonResult(createResult);
            Map<String, Object> createData = (Map<String, Object>) createResponse.get("data");
            String sessionId = (String) createData.get("sessionId");

            MvcResult answerResult = performPost("/api/interviews/sessions/" + sessionId + "/answers", Map.of(
                    "answer", "我有丰富的项目经验，能够独立完成功能开发"
            ), token);

            assertEquals(200, answerResult.getResponse().getStatus());

            Map<String, Object> answerResponse = parseJsonResult(answerResult);
            assertTrue((Boolean) answerResponse.get("success"));

            Map<String, Object> answerData = (Map<String, Object>) answerResponse.get("data");
            assertEquals(sessionId, answerData.get("sessionId"));
            assertTrue((Integer) answerData.get("turnCount") >= 1);
        }

        @Test
        @DisplayName("回答问题 - 回答内容为空")
        void answerQuestion_Failure_EmptyAnswer() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "答题用户");

            MvcResult createResult = performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "测试工程师",
                    "resumeSummary", "2年测试经验"
            ), token);

            Map<String, Object> createResponse = parseJsonResult(createResult);
            Map<String, Object> createData = (Map<String, Object>) createResponse.get("data");
            String sessionId = (String) createData.get("sessionId");

            MvcResult answerResult = performPost("/api/interviews/sessions/" + sessionId + "/answers", Map.of(
                    "answer", ""
            ), token);

            assertEquals(400, answerResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("列出历史会话 - 返回当前用户所有会话")
        void listHistory_Success_ReturnsSessions() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "历史用户");

            performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "Java开发工程师",
                    "resumeSummary", "经验1"
            ), token);

            performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "前端开发工程师",
                    "resumeSummary", "经验2"
            ), token);

            MvcResult listResult = performGet("/api/interviews/sessions", token);
            assertEquals(200, listResult.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(listResult);
            List<?> sessions = (List<?>) ((Map<?, ?>) response.get("data")).get("items");
            assertEquals(2, sessions.size());
        }

        @Test
        @DisplayName("删除会话 - 成功删除")
        void deleteSession_Success_Deleted() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "删除用户");

            MvcResult createResult = performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "产品经理",
                    "resumeSummary", "3年产品经验"
            ), token);

            Map<String, Object> createResponse = parseJsonResult(createResult);
            Map<String, Object> createData = (Map<String, Object>) createResponse.get("data");
            String sessionId = (String) createData.get("sessionId");

            MvcResult deleteResult = performDelete("/api/interviews/sessions/" + sessionId, token);
            assertEquals(200, deleteResult.getResponse().getStatus());

            MvcResult getResult = performGet("/api/interviews/sessions/" + sessionId, token);
            assertEquals(404, getResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("完整面试流程 - 创建→答题→完成")
        void fullInterviewFlow_Success_Complete() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "完整流程用户");

            MvcResult startResult = performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "后端开发工程师",
                    "resumeSummary", "3年Java后端开发经验，熟悉Spring Boot、MySQL、Redis"
            ), token);

            Map<String, Object> startResponse = parseJsonResult(startResult);
            Map<String, Object> startData = (Map<String, Object>) startResponse.get("data");
            String sessionId = (String) startData.get("sessionId");
            assertNotNull(sessionId);

            MvcResult answer1Result = performPost("/api/interviews/sessions/" + sessionId + "/answers", Map.of(
                    "answer", "我使用Spring Boot框架开发了多个企业级应用，能够独立完成从需求分析到上线部署的全流程。"
            ), token);
            assertEquals(200, answer1Result.getResponse().getStatus());

            Map<String, Object> answer1Data = (Map<String, Object>) parseJsonResult(answer1Result).get("data");
            assertTrue(((Number) answer1Data.get("turnCount")).intValue() >= 1);

            MvcResult listResult = performGet("/api/interviews/sessions", token);
            assertEquals(200, listResult.getResponse().getStatus());

            Map<String, Object> listResponse = parseJsonResult(listResult);
            List<?> sessions = (List<?>) ((Map<?, ?>) listResponse.get("data")).get("items");
            assertTrue(sessions.size() >= 1);

            boolean found = false;
            for (Object session : sessions) {
                Map<String, Object> s = (Map<String, Object>) session;
                if (sessionId.equals(s.get("sessionId"))) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "会话应出现在历史列表中");
        }
    }
}