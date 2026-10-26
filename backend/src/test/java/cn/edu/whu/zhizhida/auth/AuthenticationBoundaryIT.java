package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationBoundaryIT extends IntegrationTestBase {

    @Nested
    @DisplayName("认证边界测试")
    class AuthBoundaryTests {

        @Test
        @DisplayName("未登录访问需要认证的接口 - 返回401")
        void accessProtectedEndpoint_NoAuth_Returns401() throws Exception {
            MvcResult result = performGet("/api/users/me");
            assertEquals(401, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("未登录访问简历列表 - 返回401")
        void accessResumes_NoAuth_Returns401() throws Exception {
            MvcResult result = performGet("/api/resumes");
            assertEquals(401, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("未登录访问面试会话 - 返回401")
        void accessInterviews_NoAuth_Returns401() throws Exception {
            MvcResult result = performGet("/api/interviews/sessions");
            assertEquals(401, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("无效Token访问 - 返回401")
        void accessWithInvalidToken_Returns401() throws Exception {
            MvcResult result = performGet("/api/users/me", "invalid.token.here");
            assertEquals(401, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("空Token访问 - 返回401")
        void accessWithEmptyToken_Returns401() throws Exception {
            MvcResult result = performGet("/api/users/me", "");
            assertEquals(401, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("越权访问他人简历 - 返回403或404")
        void accessOthersResume_Forbidden_ReturnsRestricted() throws Exception {
            String token1 = registerAndLoginWithUniqueUsername("password123", "用户1");
            String token2 = registerAndLoginWithUniqueUsername("password456", "用户2");

            MvcResult createResult = performPost("/api/resumes", Map.of(
                    "ownerName", "用户1的简历",
                    "targetRole", "Java开发工程师",
                    "skills", List.of("Java")
            ), token1);

            Map<String, Object> createResponse = parseJsonResult(createResult);
            Map<String, Object> data = (Map<String, Object>) createResponse.get("data");
            Long resumeId = ((Number) data.get("id")).longValue();

            MvcResult getResult = performGet("/api/resumes/" + resumeId, token2);
            assertTrue(getResult.getResponse().getStatus() == 403 || getResult.getResponse().getStatus() == 404,
                    "应返回403或404");
        }

        @Test
        @DisplayName("越权删除他人简历 - 返回403或404")
        void deleteOthersResume_Forbidden_ReturnsRestricted() throws Exception {
            String token1 = registerAndLoginWithUniqueUsername("password123", "用户1");
            String token2 = registerAndLoginWithUniqueUsername("password456", "用户2");

            MvcResult createResult = performPost("/api/resumes", Map.of(
                    "ownerName", "用户1的简历",
                    "targetRole", "Java开发工程师",
                    "skills", List.of("Java")
            ), token1);

            Map<String, Object> createResponse = parseJsonResult(createResult);
            Map<String, Object> data = (Map<String, Object>) createResponse.get("data");
            Long resumeId = ((Number) data.get("id")).longValue();

            MvcResult deleteResult = performDelete("/api/resumes/" + resumeId, token2);
            assertTrue(deleteResult.getResponse().getStatus() == 403 || deleteResult.getResponse().getStatus() == 404,
                    "应返回403或404");
        }

        @Test
        @DisplayName("修改密码后旧Token失效")
        void passwordChange_OldTokenInvalidated_Returns401() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "密码测试用户");

            MvcResult changeResult = performPut("/api/users/me/password", Map.of(
                    "currentPassword", "password123",
                    "newPassword", "newPassword789"
            ), token);
            assertEquals(200, changeResult.getResponse().getStatus());

            MvcResult getResult = performGet("/api/users/me", token);
            assertEquals(401, getResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("删除账户后Token失效")
        void accountDelete_TokenInvalidated_Returns401() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "删除测试用户");

            MvcResult deleteResult = performDeleteWithBody("/api/users/me", Map.of(
                    "currentPassword", "password123"
            ), token);
            assertEquals(200, deleteResult.getResponse().getStatus());

            MvcResult getResult = performGet("/api/users/me", token);
            assertEquals(401, getResult.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("公共接口测试")
    class PublicEndpointTests {

        @Test
        @DisplayName("健康检查接口无需认证")
        void healthCheck_NoAuth_Accessible() throws Exception {
            MvcResult result = performGet("/api/health");
            assertEquals(200, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("数据库就绪接口无需认证")
        void readiness_NoAuth_Accessible() throws Exception {
            assertEquals(200, performGet("/api/health/ready").getResponse().getStatus());
        }

        @Test
        @DisplayName("注册接口无需认证")
        void register_NoAuth_Accessible() throws Exception {
            String uniqueUsername = "public_reg_" + System.currentTimeMillis();
            MvcResult result = performPost("/api/auth/register", Map.of(
                    "username", uniqueUsername,
                    "password", "password123",
                    "displayName", "公共注册用户"
            ));
            assertEquals(200, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("登录接口无需认证")
        void login_NoAuth_Accessible() throws Exception {
            String uniqueUsername = "publog" + (int)(Math.random() * 10000);
            registerUser(uniqueUsername, "password123", "公共登录用户");

            MvcResult result = performPost("/api/auth/login", Map.of(
                    "username", uniqueUsername,
                    "password", "password123"
            ));
            assertEquals(200, result.getResponse().getStatus());
        }
    }
}
