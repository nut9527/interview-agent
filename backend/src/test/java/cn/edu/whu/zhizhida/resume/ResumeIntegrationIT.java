package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResumeIntegrationIT extends IntegrationTestBase {

    @Nested
    @DisplayName("简历CRUD测试")
    class ResumeCrudTests {

        @Test
        @DisplayName("创建简历 - 数据正确持久化")
        void createResume_Success_DataPersisted() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "简历用户");

            MvcResult createResult = performPost("/api/resumes", Map.of(
                    "ownerName", "张三",
                    "targetRole", "Java开发工程师",
                    "summary", "3年Java开发经验",
                    "skills", List.of("Java", "Spring Boot", "MySQL"),
                    "educationExperiences", List.of("2018-2022 本科 武汉大学 计算机"),
                    "projectExperiences", List.of("电商平台后端开发"),
                    "internshipExperiences", List.of("字节跳动实习"),
                    "certificates", List.of("CET-6"),
                    "templateKey", "classic"
            ), token);

            assertEquals(200, createResult.getResponse().getStatus());

            Map<String, Object> createResponse = parseJsonResult(createResult);
            assertTrue((Boolean) createResponse.get("success"));
            Map<String, Object> resumeData = (Map<String, Object>) createResponse.get("data");
            Long resumeId = ((Number) resumeData.get("id")).longValue();

            MvcResult getResult = performGet("/api/resumes/" + resumeId, token);
            assertEquals(200, getResult.getResponse().getStatus());

            Map<String, Object> getResponse = parseJsonResult(getResult);
            Map<String, Object> fetchedResume = (Map<String, Object>) getResponse.get("data");
            assertEquals("张三", fetchedResume.get("ownerName"));
            assertEquals("Java开发工程师", fetchedResume.get("targetRole"));
            assertEquals("3年Java开发经验", fetchedResume.get("summary"));
            assertEquals("classic", fetchedResume.get("templateKey"));
        }

        @Test
        @DisplayName("创建简历失败 - 必填字段缺失")
        void createResume_Failure_MissingRequiredFields() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "错误简历");

            MvcResult result = performPost("/api/resumes", Map.of(
                    "ownerName", "",
                    "targetRole", ""
            ), token);

            assertEquals(400, result.getResponse().getStatus());
        }

        @Test
        @DisplayName("更新简历 - 数据正确更新")
        void updateResume_Success_DataUpdated() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "更新用户");

            MvcResult createResult = performPost("/api/resumes", Map.of(
                    "ownerName", "李四",
                    "targetRole", "前端开发",
                    "skills", List.of("React")
            ), token);
            Map<String, Object> createData = (Map<String, Object>) parseJsonResult(createResult).get("data");
            Long resumeId = ((Number) createData.get("id")).longValue();

            MvcResult updateResult = performPut("/api/resumes/" + resumeId, Map.of(
                    "ownerName", "李四",
                    "targetRole", "全栈开发",
                    "skills", List.of("React", "Spring Boot")
            ), token);

            assertEquals(200, updateResult.getResponse().getStatus());

            MvcResult getResult = performGet("/api/resumes/" + resumeId, token);
            Map<String, Object> fetchedResume = (Map<String, Object>) parseJsonResult(getResult).get("data");
            assertEquals("全栈开发", fetchedResume.get("targetRole"));
            assertTrue(((List<?>) fetchedResume.get("skills")).contains("Spring Boot"));
        }

        @Test
        @DisplayName("删除简历 - 数据正确删除")
        void deleteResume_Success_DataDeleted() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "删除用户");

            MvcResult createResult = performPost("/api/resumes", Map.of(
                    "ownerName", "王五",
                    "targetRole", "测试工程师"
            ), token);
            Map<String, Object> createData = (Map<String, Object>) parseJsonResult(createResult).get("data");
            Long resumeId = ((Number) createData.get("id")).longValue();

            MvcResult deleteResult = performDelete("/api/resumes/" + resumeId, token);
            assertEquals(200, deleteResult.getResponse().getStatus());

            MvcResult getResult = performGet("/api/resumes/" + resumeId, token);
            assertEquals(404, getResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("获取简历列表 - 返回当前用户所有简历")
        void listResumes_Success_ReturnsUserResumes() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "列表用户");

            performPost("/api/resumes", Map.of(
                    "ownerName", "赵六",
                    "targetRole", "产品经理"
            ), token);

            performPost("/api/resumes", Map.of(
                    "ownerName", "赵六",
                    "targetRole", "运营"
            ), token);

            MvcResult listResult = performGet("/api/resumes", token);
            assertEquals(200, listResult.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(listResult);
            List<?> resumes = (List<?>) response.get("data");
            assertEquals(2, resumes.size());
        }
    }

    @Nested
    @DisplayName("简历导入测试")
    class ResumeImportTests {

        @Test
        @DisplayName("导入简历文档 - 不支持的文件格式")
        void importResume_Failure_UnsupportedFormat() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "导入用户");

            MvcResult importResult = mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/resumes/import")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", "resume.txt", "text/plain", "test content".getBytes()))
                            .param("targetRole", "Java开发工程师")
                            .header("Authorization", "Bearer " + token)
            ).andReturn();

            assertEquals(400, importResult.getResponse().getStatus());
        }

        @Test
        @DisplayName("导入简历文档 - 缺少文件")
        void importResume_Failure_NoFile() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "导入用户");

            MvcResult importResult = mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/resumes/import")
                            .param("targetRole", "Java开发工程师")
                            .header("Authorization", "Bearer " + token)
            ).andReturn();

            assertTrue(importResult.getResponse().getStatus() == 400 || importResult.getResponse().getStatus() == 500,
                    "应返回400或500");
        }
    }

    @Nested
    @DisplayName("简历比较测试")
    class ResumeCompareTests {

        @Test
        @DisplayName("比较两个简历 - 成功返回差异")
        void compareResumes_Success_ReturnsDiff() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "比较用户");

            MvcResult create1 = performPost("/api/resumes", Map.of(
                    "ownerName", "张三",
                    "targetRole", "Java开发工程师",
                    "skills", List.of("Java", "Spring Boot")
            ), token);
            Map<String, Object> data1 = (Map<String, Object>) parseJsonResult(create1).get("data");
            Long resumeId1 = ((Number) data1.get("id")).longValue();

            MvcResult create2 = performPost("/api/resumes", Map.of(
                    "ownerName", "张三",
                    "targetRole", "前端开发工程师",
                    "skills", List.of("React", "TypeScript")
            ), token);
            Map<String, Object> data2 = (Map<String, Object>) parseJsonResult(create2).get("data");
            Long resumeId2 = ((Number) data2.get("id")).longValue();

            MvcResult compareResult = performPost("/api/resumes/compare", Map.of(
                    "left", Map.of("resumeId", resumeId1),
                    "right", Map.of("resumeId", resumeId2)
            ), token);

            assertEquals(200, compareResult.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(compareResult);
            assertTrue((Boolean) response.get("success"));

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertNotNull(data.get("left"), "左简历不应为空");
            assertNotNull(data.get("right"), "右简历不应为空");
            assertNotNull(data.get("changedFieldCount"), "变更字段数不应为空");
        }

        @Test
        @DisplayName("比较两个简历 - 参数校验失败")
        void compareResumes_Failure_Validation() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "比较用户");

            MvcResult compareResult = performPost("/api/resumes/compare", Map.of(
                    "left", Map.of(),
                    "right", Map.of()
            ), token);

            assertEquals(400, compareResult.getResponse().getStatus());
        }
    }

    @Nested
    @DisplayName("版本管理测试")
    class VersionManagementTests {

        @Test
        @DisplayName("创建简历后自动生成初始版本")
        void createResume_Success_CreatesInitialVersion() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "版本用户");

            MvcResult createResult = performPost("/api/resumes", Map.of(
                    "ownerName", "孙七",
                    "targetRole", "数据工程师"
            ), token);
            Map<String, Object> createData = (Map<String, Object>) parseJsonResult(createResult).get("data");
            Long resumeId = ((Number) createData.get("id")).longValue();

            MvcResult versionsResult = performGet("/api/resumes/" + resumeId + "/versions", token);
            assertEquals(200, versionsResult.getResponse().getStatus());

            Map<String, Object> response = parseJsonResult(versionsResult);
            List<?> versions = (List<?>) ((Map<?, ?>) response.get("data")).get("items");
            assertTrue(versions.size() >= 1, "应至少有一个版本");
        }
    }

    @Nested
    @DisplayName("API契约测试")
    class ContractTests {

        @Test
        @DisplayName("简历响应结构符合前端TypeScript定义")
        void resumeResponse_MatchesFrontendContract() throws Exception {
            String token = registerAndLoginWithUniqueUsername("password123", "契约测试");

            MvcResult createResult = performPost("/api/resumes", Map.of(
                    "ownerName", "周八",
                    "targetRole", "Java开发"
            ), token);

            Map<String, Object> response = parseJsonResult(createResult);
            Map<String, Object> data = (Map<String, Object>) response.get("data");

            assertTrue(data.containsKey("id"), "缺少id字段");
            assertTrue(data.containsKey("ownerName"), "缺少ownerName字段");
            assertTrue(data.containsKey("targetRole"), "缺少targetRole字段");
            assertTrue(data.containsKey("summary"), "缺少summary字段");
            assertTrue(data.containsKey("skills"), "缺少skills字段");
            assertTrue(data.containsKey("educationExperiences"), "缺少educationExperiences字段");
            assertTrue(data.containsKey("projectExperiences"), "缺少projectExperiences字段");
            assertTrue(data.containsKey("internshipExperiences"), "缺少internshipExperiences字段");
            assertTrue(data.containsKey("certificates"), "缺少certificates字段");
            assertTrue(data.containsKey("templateKey"), "缺少templateKey字段");

            assertTrue(data.get("skills") instanceof List, "skills应为数组类型");
            assertTrue(data.get("educationExperiences") instanceof List, "educationExperiences应为数组类型");
        }
    }
}
