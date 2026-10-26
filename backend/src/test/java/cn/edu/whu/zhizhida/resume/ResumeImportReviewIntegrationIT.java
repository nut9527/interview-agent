package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class ResumeImportReviewIntegrationIT extends IntegrationTestBase {
    @Test
    void parseReviewConfirmPersistsOnlyCorrectedDataAndKeepsAccountsIsolated() throws Exception {
        String owner = registerAndLoginWithUniqueUsername("password123", "导入用户");
        String other = registerAndLoginWithUniqueUsername("password123", "其他用户");
        try {
            byte[] bytes;
            try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
                document.createParagraph().createRun().setText("姓名：待核对\n求职意向：Java开发\n个人简介：负责接口开发");
                document.write(output);
                bytes = output.toByteArray();
            }
            var file = new MockMultipartFile("file", "resume.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
            assertThat(mockMvc.perform(multipart("/api/resumes/import/parse").file(file))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            for (int i = 0; i < 2; i++) {
                var parsed = mockMvc.perform(multipart("/api/resumes/import/parse").file(file)
                        .header("Authorization", "Bearer " + owner)).andReturn();
                assertThat(parsed.getResponse().getStatus()).isEqualTo(200);
                assertThat(((Map<?, ?>) parseJsonResult(parsed).get("data")).containsKey("id")).isFalse();
            }
            assertThat((java.util.List<?>) parseJsonResult(performGet("/api/resumes", owner)).get("data")).isEmpty();
            assertThat(performPost("/api/resumes", Map.of("ownerName", "", "targetRole", "Java"), owner)
                    .getResponse().getStatus()).isEqualTo(400);
            var created = performPost("/api/resumes", Map.of("ownerName", "纠正姓名", "targetRole", "Java", "summary", "纠正后的经历"), owner);
            Map<?, ?> data = (Map<?, ?>) parseJsonResult(created).get("data");
            assertThat(data.get("ownerName")).isEqualTo("纠正姓名");
            String path = "/api/resumes/" + data.get("id");
            assertThat(performGet(path, other).getResponse().getStatus()).isEqualTo(404);
            assertThat((java.util.List<?>) parseJsonResult(performGet("/api/resumes", other)).get("data")).isEmpty();
            Map<?, ?> versions = (Map<?, ?>) parseJsonResult(performGet(path + "/versions", owner)).get("data");
            assertThat((java.util.List<?>) versions.get("items")).hasSize(1);
            assertThat(((Map<?, ?>) parseJsonResult(performGet(path, owner)).get("data")).get("summary")).isEqualTo("纠正后的经历");
        } finally {
            performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), owner);
            performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), other);
        }
    }
}
