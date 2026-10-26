package cn.edu.whu.zhizhida.e2e;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EndToEndBusinessFlowIT extends IntegrationTestBase {

    @Nested
    @DisplayName("场景A：完整求职流程")
    class ScenarioA_FullJobSearchFlow {


    }

    @Nested
    @DisplayName("场景B：简历导入流程")
    class ScenarioB_ResumeImportFlow {


    }

    @Nested
    @DisplayName("场景C：面试模拟流程")
    class ScenarioC_InterviewFlow {

        @Test
        @DisplayName("开始面试 -> 回答问题 -> 结束面试")
        void interviewFlow_Success() throws Exception {
            String token = registerAndLogin("interviewuser", "password123", "面试用户");

            MvcResult startResult = performPost("/api/interviews/sessions", Map.of(
                    "targetRole", "Java开发工程师",
                    "resumeSummary", "3年Java开发经验，熟悉Spring Boot"
            ), token);
            assertEquals(200, startResult.getResponse().getStatus());
            Map<String, Object> sessionData = (Map<String, Object>) parseJsonResult(startResult).get("data");
            String sessionId = (String) sessionData.get("sessionId");
            assertNotNull(sessionId, "会话ID不应为空");
            assertEquals("WAITING_ANSWER", sessionData.get("state"), "初始状态应为WAITING_ANSWER");

            List<?> turns = (List<?>) sessionData.get("turns");
            assertTrue(turns.size() >= 1, "应至少有一个对话轮次");

            MvcResult answerResult = performPost("/api/interviews/sessions/" + sessionId + "/answers", Map.of(
                    "answer", "我使用Spring Boot框架进行后端开发，主要负责用户模块和订单模块的设计与实现。"
            ), token);
            assertEquals(200, answerResult.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("跨模块数据一致性验证")
    class CrossModuleConsistency {


    }
}
