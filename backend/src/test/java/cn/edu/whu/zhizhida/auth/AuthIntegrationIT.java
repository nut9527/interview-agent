package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationIT extends IntegrationTestBase {

    private String uniqueUsername() {
        return "testuser_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Nested
    @DisplayName("注册接口测试")
    class RegisterTests {

        @Test
        @DisplayName("注册成功 - 返回Token和用户信息")
        void register_Success_ReturnsTokenAndUser() throws Exception {
            String username = uniqueUsername();
            MvcResult result = registerUser(username, "password123", "测试用户");

            assertEquals(200, result.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"), "success字段应为true");
            assertEquals("ok", response.get("message"), "message字段应为ok");

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertNotNull(data.get("token"), "token不应为空");
            assertNotNull(data.get("expiresAt"), "expiresAt不应为空");

            Map<String, Object> user = (Map<String, Object>) data.get("user");
            assertNotNull(user.get("id"), "user.id不应为空");
            assertEquals(username, user.get("username"), "username应匹配");
            assertEquals("测试用户", user.get("displayName"), "displayName应为测试用户");
        }

        @Test
        @DisplayName("注册失败 - 用户名已存在")
        void register_Failure_UsernameExists() throws Exception {
            registerUser("duplicate", "password123", "用户1");
            MvcResult result = registerUser("duplicate", "password456", "用户2");

            assertEquals(409, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("注册失败 - 用户名为空")
        void register_Failure_UsernameBlank() throws Exception {
            MvcResult result = performPost("/api/auth/register", Map.of(
                    "username", "",
                    "password", "password123",
                    "displayName", "测试用户"
            ));

            assertEquals(400, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("注册失败 - 密码过短")
        void register_Failure_PasswordTooShort() throws Exception {
            MvcResult result = performPost("/api/auth/register", Map.of(
                    "username", "shortpass",
                    "password", "123",
                    "displayName", "测试用户"
            ));

            assertEquals(400, result.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("登录接口测试")
    class LoginTests {

        @Test
        @DisplayName("登录成功 - 返回Token和用户信息")
        void login_Success_ReturnsTokenAndUser() throws Exception {
            registerUser("loginuser", "password123", "登录测试");

            MvcResult result = loginUser("loginuser", "password123");

            assertEquals(200, result.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));
            assertNotNull(response.get("data"));

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertNotNull(data.get("token"));
        }

        @Test
        @DisplayName("登录失败 - 用户名不存在")
        void login_Failure_UserNotFound() throws Exception {
            MvcResult result = loginUser("nonexistent", "password123");

            assertEquals(401, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("登录失败 - 密码错误")
        void login_Failure_WrongPassword() throws Exception {
            registerUser("wrongpass", "password123", "密码测试");

            MvcResult result = loginUser("wrongpass", "wrongpassword");

            assertEquals(401, result.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("独立演示空间测试")
    class DemoSandboxTests {

        @Test
        @DisplayName("两次演示登录创建不同用户且修改互不影响")
        void demoLogin_CreatesIsolatedUserData() throws Exception {
            MvcResult firstLogin = performPost("/api/auth/demo", null);
            MvcResult secondLogin = performPost("/api/auth/demo", null);

            assertEquals(200, firstLogin.getResponse().getStatus());
            assertEquals(200, secondLogin.getResponse().getStatus());

            Map<String, Object> firstAuth = (Map<String, Object>) parseJsonResult(firstLogin).get("data");
            Map<String, Object> secondAuth = (Map<String, Object>) parseJsonResult(secondLogin).get("data");
            Map<String, Object> firstUser = (Map<String, Object>) firstAuth.get("user");
            Map<String, Object> secondUser = (Map<String, Object>) secondAuth.get("user");

            assertNotEquals(((Number) firstUser.get("id")).longValue(),
                    ((Number) secondUser.get("id")).longValue());
            assertEquals("demo", firstUser.get("username"));
            assertEquals(Boolean.TRUE, firstUser.get("demo"));
            assertEquals(Boolean.TRUE, secondUser.get("demo"));

            String firstToken = (String) firstAuth.get("token");
            String secondToken = (String) secondAuth.get("token");
            MvcResult firstResumesResult = performGet("/api/resumes", firstToken);
            MvcResult secondResumesResult = performGet("/api/resumes", secondToken);
            List<Map<String, Object>> firstResumes = (List<Map<String, Object>>)
                    parseJsonResult(firstResumesResult).get("data");
            List<Map<String, Object>> secondResumes = (List<Map<String, Object>>)
                    parseJsonResult(secondResumesResult).get("data");

            assertFalse(firstResumes.isEmpty(), "演示空间应复制模板简历");
            assertEquals(firstResumes.size(), secondResumes.size());
            assertNotEquals(((Number) firstResumes.get(0).get("id")).longValue(),
                    ((Number) secondResumes.get(0).get("id")).longValue());

            Map<String, Object> firstResume = firstResumes.get(0);
            long firstResumeId = ((Number) firstResume.get("id")).longValue();
            String isolatedSummary = "仅访客 A 可见的修改";
            MvcResult updateResult = performPut("/api/resumes/" + firstResumeId, Map.of(
                    "ownerName", firstResume.get("ownerName"),
                    "targetRole", firstResume.get("targetRole"),
                    "summary", isolatedSummary,
                    "skills", firstResume.get("skills"),
                    "educationExperiences", firstResume.get("educationExperiences"),
                    "projectExperiences", firstResume.get("projectExperiences"),
                    "internshipExperiences", firstResume.get("internshipExperiences"),
                    "certificates", firstResume.get("certificates"),
                    "templateKey", firstResume.get("templateKey")
            ), firstToken);
            assertEquals(200, updateResult.getResponse().getStatus());

            List<Map<String, Object>> secondAfterUpdate = (List<Map<String, Object>>)
                    parseJsonResult(performGet("/api/resumes", secondToken)).get("data");
            assertNotEquals(isolatedSummary, secondAfterUpdate.get(0).get("summary"));
        }

        @Test
        @DisplayName("公共模板不能通过共享密码直接登录")
        void demoTemplate_DoesNotAllowPasswordLogin() throws Exception {
            MvcResult result = loginUser("demo", "demo123");
            assertEquals(401, result.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("登出接口测试")
    class LogoutTests {

        @Test
        @DisplayName("登出成功 - Token失效")
        void logout_Success_TokenInvalidated() throws Exception {
            String token = registerAndLogin("logoutuser", "password123", "登出测试");

            MvcResult logoutResult = performPost("/api/auth/logout", null, token);
            assertEquals(200, logoutResult.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("API契约测试 - JSON结构验证")
    class ContractTests {

        @Test
        @DisplayName("注册响应结构符合前端TypeScript定义")
        void registerResponse_MatchesFrontendContract() throws Exception {
            MvcResult result = registerUser(uniqueUsername(), "password123", "契约测试");

            Map<String, Object> response = parseJsonResult(result);

            assertTrue(response.containsKey("success"), "缺少success字段");
            assertTrue(response.containsKey("message"), "缺少message字段");
            assertTrue(response.containsKey("data"), "缺少data字段");

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertTrue(data.containsKey("token"), "data缺少token字段");
            assertTrue(data.containsKey("expiresAt"), "data缺少expiresAt字段");
            assertTrue(data.containsKey("user"), "data缺少user字段");

            Map<String, Object> user = (Map<String, Object>) data.get("user");
            assertTrue(user.containsKey("id"), "user缺少id字段");
            assertTrue(user.containsKey("username"), "user缺少username字段");
            assertTrue(user.containsKey("displayName"), "user缺少displayName字段");
            assertTrue(user.containsKey("targetRole"), "user缺少targetRole字段");
            assertTrue(user.containsKey("skillTags"), "user缺少skillTags字段");
            assertTrue(user.containsKey("demo"), "user缺少demo字段");
        }
    }
}
