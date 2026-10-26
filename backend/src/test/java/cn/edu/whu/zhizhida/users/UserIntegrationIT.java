package cn.edu.whu.zhizhida.users;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserIntegrationIT extends IntegrationTestBase {

    @Nested
    @DisplayName("用户信息测试")
    class UserProfileTests {

        @Test
        @DisplayName("获取当前用户信息 - 成功返回")
        void getCurrentUser_Success_ReturnsProfile() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "当前用户");

            MvcResult result = performGet("/api/users/me", token);
            assertEquals(200, result.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertNotNull(data.get("id"), "用户ID不应为空");
            assertNotNull(data.get("displayName"), "显示名不应为空");
            assertNotNull(data.get("targetRole"), "目标岗位不应为空");
        }

        @Test
        @DisplayName("更新用户信息 - 成功更新")
        void updateUserProfile_Success_Updated() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "更新用户");

            MvcResult updateResult = performPut("/api/users/me", Map.of(
                    "displayName", "新显示名",
                    "targetRole", "后端开发工程师",
                    "skillTags", List.of("Java", "Spring Boot", "MySQL")
            ), token);

            assertEquals(200, updateResult.getResponse().getStatus());

            Map<String, Object> updateResponse = parseJsonResult(updateResult);
            assertTrue((Boolean) updateResponse.get("success"));

            Map<String, Object> data = (Map<String, Object>) updateResponse.get("data");
            assertEquals("新显示名", data.get("displayName"));
            assertEquals("后端开发工程师", data.get("targetRole"));
            assertTrue(((List<?>) data.get("skillTags")).contains("Java"));
        }

        @Test
        @DisplayName("更新用户信息 - 参数校验失败")
        void updateUserProfile_Failure_Validation() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "更新用户");

            MvcResult updateResult = performPut("/api/users/me", Map.of(
                    "displayName", "a",
                    "targetRole", ""
            ), token);

            assertEquals(400, updateResult.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("密码修改测试")
    class PasswordChangeTests {

        @Test
        @DisplayName("修改密码 - 成功修改")
        void changePassword_Success_Changed() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "密码用户");

            MvcResult changeResult = performPut("/api/users/me/password", Map.of(
                    "currentPassword", "password123",
                    "newPassword", "newPassword456"
            ), token);

            assertEquals(200, changeResult.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(changeResult);
            assertTrue((Boolean) response.get("success"));
        }

        @Test
        @DisplayName("修改密码 - 旧密码不正确")
        void changePassword_Failure_WrongCurrentPassword() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "密码用户");

            MvcResult changeResult = performPut("/api/users/me/password", Map.of(
                    "currentPassword", "wrongPassword",
                    "newPassword", "newPassword456"
            ), token);

            assertEquals(400, changeResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("修改密码 - 新旧密码相同")
        void changePassword_Failure_SameAsCurrent() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "密码用户");

            MvcResult changeResult = performPut("/api/users/me/password", Map.of(
                    "currentPassword", "password123",
                    "newPassword", "password123"
            ), token);

            assertEquals(400, changeResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("修改密码 - 新密码不符合规则")
        void changePassword_Failure_InvalidNewPassword() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "密码用户");

            MvcResult changeResult = performPut("/api/users/me/password", Map.of(
                    "currentPassword", "password123",
                    "newPassword", "short"
            ), token);

            assertEquals(400, changeResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("修改密码后旧Token失效")
        void changePassword_Success_OldTokenInvalidated() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "密码失效用户");

            MvcResult changeResult = performPut("/api/users/me/password", Map.of(
                    "currentPassword", "password123",
                    "newPassword", "newPassword456"
            ), token);
            assertEquals(200, changeResult.getResponse().getStatus());

            MvcResult getResult = performGet("/api/users/me", token);
            assertEquals(401, getResult.getResponse().getStatus());

            MvcResult loginResult = loginUser("u" + System.currentTimeMillis() + "0", "newPassword456");
            assertEquals(401, loginResult.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("账户删除测试")
    class AccountDeletionTests {

        @Test
        @DisplayName("删除账户 - 成功删除")
        void deleteAccount_Success_Deleted() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "删除用户");

            MvcResult deleteResult = performDeleteWithBody("/api/users/me", Map.of(
                    "currentPassword", "password123"
            ), token);
            assertEquals(200, deleteResult.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(deleteResult);
            assertTrue((Boolean) response.get("success"));
        }

        @Test
        @DisplayName("删除账户后Token失效")
        void deleteAccount_Success_TokenInvalidated() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "删除失效用户");

            MvcResult deleteResult = performDeleteWithBody("/api/users/me", Map.of(
                    "currentPassword", "password123"
            ), token);
            assertEquals(200, deleteResult.getResponse().getStatus());

            MvcResult getResult = performGet("/api/users/me", token);
            assertEquals(401, getResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("删除账户 - 密码错误")
        void deleteAccount_Failure_WrongPassword() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "删除用户");

            MvcResult deleteResult = performDeleteWithBody("/api/users/me", Map.of(
                    "currentPassword", "wrongPassword"
            ), token);
            assertEquals(400, deleteResult.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("用户管理完整流程")
    class UserManagementFlow {

        @Test
        @DisplayName("完整用户管理流程 - 获取→更新→改密码→删除")
        void fullUserManagementFlow_Success_Complete() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "完整流程用户");

            MvcResult getResult = performGet("/api/users/me", token);
            assertEquals(200, getResult.getResponse().getStatus());

            Map<String, Object> getResponse = parseJsonResult(getResult);
            Map<String, Object> userData = (Map<String, Object>) getResponse.get("data");
            assertNotNull(userData.get("id"));

            MvcResult updateResult = performPut("/api/users/me", Map.of(
                    "displayName", "流程测试用户",
                    "targetRole", "全栈工程师",
                    "skillTags", List.of("Java", "React")
            ), token);
            assertEquals(200, updateResult.getResponse().getStatus());

            MvcResult passwordResult = performPut("/api/users/me/password", Map.of(
                    "currentPassword", "password123",
                    "newPassword", "newPass789"
            ), token);
            assertEquals(200, passwordResult.getResponse().getStatus());

            MvcResult getAfterPasswordChange = performGet("/api/users/me", token);
            assertEquals(401, getAfterPasswordChange.getResponse().getStatus());
        }
    }
}