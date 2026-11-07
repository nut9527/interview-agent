package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiIntegrationIT extends IntegrationTestBase {

    @Nested
    @DisplayName("AI优化接口测试")
    class AiOptimizeTests {

        @Test
        @DisplayName("AI优化 - Mock模式正常工作")
        void aiOptimize_Success_MockMode() throws Exception {
            String token = registerAndLogin("aiuser", "password123", "AI测试用户");

            MvcResult result = performPost("/api/ai/resume/optimize-draft", Map.of(
                    "entries", List.of(Map.of("kind", "summary", "text", "负责后端开发")),
                    "targetRole", "Java开发工程师"
            ), token);

            assertEquals(200, result.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertEquals(List.of(Map.of("index", 0, "text", "负责后端开发。")), data.get("changes"));
        }

        @Test
        @DisplayName("AI优化 - 请求参数验证")
        void aiOptimize_Failure_BadRequest() throws Exception {
            String token = registerAndLogin("aibaduser", "password123", "AI错误用户");

            MvcResult result = performPost("/api/ai/resume/optimize-draft", Map.of(
                    "entries", List.of(Map.of("kind", "summary", "text", "")),
                    "targetRole", ""
            ), token);

            assertEquals(400, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("AI优化 - 长文本处理")
        void aiOptimize_Success_LongText() throws Exception {
            String token = registerAndLogin("ailonguser", "password123", "AI长文本用户");

            String longText = "我在大三的时候参加了学校的软件开发比赛，负责后端开发工作。" +
                    "使用Java语言和Spring Boot框架搭建了一个在线商城系统的后端服务。" +
                    "主要实现了用户注册登录、商品管理、订单处理等功能模块。" +
                    "通过这个项目，我学会了如何设计RESTful API，如何使用MySQL数据库进行数据持久化，" +
                    "以及如何进行单元测试和集成测试。";

            MvcResult result = performPost("/api/ai/resume/optimize-draft", Map.of(
                    "entries", List.of(Map.of("kind", "summary", "text", longText)),
                    "targetRole", "Java后端开发工程师"
            ), token);

            assertEquals(200, result.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));
            assertNotNull(response.get("data"));
        }
    }

    @Nested
    @DisplayName("AI优化响应结构验证")
    class AiResponseStructureTests {

        @Test
        @DisplayName("AI优化响应结构符合前端TypeScript定义")
        void aiResponse_MatchesFrontendContract() throws Exception {
            String token = registerAndLogin("aicontractuser", "password123", "AI契约用户");

            MvcResult result = performPost("/api/ai/resume/optimize-draft", Map.of(
                    "entries", List.of(Map.of("kind", "summary", "text", "测试文本")),
                    "targetRole", "测试岗位"
            ), token);

            Map<String, Object> response = parseJsonResult(result);
            Map<String, Object> data = (Map<String, Object>) response.get("data");

            assertEquals(java.util.Set.of("changes"), data.keySet());
            assertTrue(data.get("changes") instanceof List, "changes应为数组类型");
            var changes = (List<Map<String, Object>>) data.get("changes");
            assertEquals(java.util.Set.of("index", "text"), changes.getFirst().keySet());
        }
    }
}
