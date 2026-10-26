package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiIntegrationIT extends IntegrationTestBase {

    @Nested
    @DisplayName("AI优化接口测试")
    class AiOptimizeTests {

        @Test
        @DisplayName("AI优化 - Mock模式正常工作")
        void aiOptimize_Success_MockMode() throws Exception {
            String token = registerAndLogin("aiuser", "password123", "AI测试用户");

            MvcResult result = performPost("/api/ai/resume/optimize", Map.of(
                    "rawText", "负责后端开发",
                    "targetRole", "Java开发工程师"
            ), token);

            assertEquals(200, result.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertNotNull(data.get("optimizedText"), "优化文本不应为空");
            assertNotNull(data.get("suggestions"), "建议列表不应为空");
            assertNotNull(data.get("improvements"), "改进项列表不应为空");
            assertNotNull(data.get("targetKeywords"), "目标关键词不应为空");
            assertNotNull(data.get("optimizationSummary"), "优化摘要不应为空");
        }

        @Test
        @DisplayName("AI优化 - 请求参数验证")
        void aiOptimize_Failure_BadRequest() throws Exception {
            String token = registerAndLogin("aibaduser", "password123", "AI错误用户");

            MvcResult result = performPost("/api/ai/resume/optimize", Map.of(
                    "rawText", "",
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

            MvcResult result = performPost("/api/ai/resume/optimize", Map.of(
                    "rawText", longText,
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

            MvcResult result = performPost("/api/ai/resume/optimize", Map.of(
                    "rawText", "测试文本",
                    "targetRole", "测试岗位"
            ), token);

            Map<String, Object> response = parseJsonResult(result);
            Map<String, Object> data = (Map<String, Object>) response.get("data");

            assertTrue(data.containsKey("optimizedText"), "缺少optimizedText字段");
            assertTrue(data.containsKey("suggestions"), "缺少suggestions字段");
            assertTrue(data.containsKey("improvements"), "缺少improvements字段");
            assertTrue(data.containsKey("targetKeywords"), "缺少targetKeywords字段");
            assertTrue(data.containsKey("optimizationSummary"), "缺少optimizationSummary字段");

            assertTrue(data.get("suggestions") instanceof java.util.List, "suggestions应为数组类型");
            assertTrue(data.get("improvements") instanceof java.util.List, "improvements应为数组类型");
            assertTrue(data.get("targetKeywords") instanceof java.util.List, "targetKeywords应为数组类型");
        }
    }
}
