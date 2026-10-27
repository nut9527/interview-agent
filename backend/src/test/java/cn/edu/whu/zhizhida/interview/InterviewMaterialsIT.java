package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class InterviewMaterialsIT extends IntegrationTestBase {
    @Test void customJdAndDifficultySurviveAnswersReloadAndSourceChanges() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "自定义面试");
        String jd = "Java、MySQL；负责订单一致性与接口设计。".repeat(300);
        var request = objectMapper.createObjectNode().put("targetRole", "后端工程师").put("jobDescription", jd)
                .put("difficulty", "ADVANCED").put("totalRounds", 2);
        var session = data(performPost("/api/interviews/sessions", request, token));
        String url = "/api/interviews/sessions/" + session.path("sessionId").asText();
        assertThat(session.path("difficulty").asText()).isEqualTo("ADVANCED");
        assertThat(session.path("jobSnapshot").asText()).contains(jd);
        request.put("jobDescription", "客户端后来修改的 JD");
        for (int turn = 1; turn <= 2; turn++) {
            var answered = data(performPost(url + "/answers", Map.of("answer", "我负责接口设计与对照测试", "expectedTurn", turn), token));
            assertThat(answered.path("difficulty").asText()).isEqualTo("ADVANCED");
        }
        var stored = data(performGet(url, token));
        assertThat(stored.path("jobSnapshot").asText()).contains(jd).doesNotContain("客户端后来修改");
        assertThat(stored.path("difficulty").asText()).isEqualTo("ADVANCED");
        assertThat(stored.path("state").asText()).isEqualTo("FINISHED");
        request.remove("difficulty");
        assertThat(data(performPost("/api/interviews/sessions", request, token)).path("difficulty").asText()).isEqualTo("STANDARD");
        request.put("difficulty", "INVALID");
        assertThat(performPost("/api/interviews/sessions", request, token).getResponse().getStatus()).isEqualTo(400);
        request.put("difficulty", "BEGINNER").put("jobDescription", "原".repeat(20001));
        assertThat(performPost("/api/interviews/sessions", request, token).getResponse().getStatus()).isEqualTo(400);
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    private ObjectNode jobRequest() {
        var body = objectMapper.createObjectNode().put("revision", 0).put("title", "Java 后端实习")
                .put("company", "合成公司").put("jdText", "负责 Java 接口开发、MySQL 查询与 Redis 缓存验证。" + "完整岗位职责。".repeat(800));
        body.putArray("requirements"); return body;
    }

    @Test void importsResumeAndPrivateJobThenInterviewsUsingImmutableOwnedSnapshots() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "面试材料用户");
        byte[] bytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            for (String line : new String[]{"姓名：候选人", "求职意向：Java 后端", "项目经历：我负责订单接口设计与对照测试"})
                document.createParagraph().createRun().setText(line);
            document.write(output); bytes = output.toByteArray();
        }
        var parsed = data(mockMvc.perform(multipart("/api/resumes/import/parse").file(new MockMultipartFile("file", "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes))
                .header("Authorization", "Bearer " + token)).andReturn());
        assertThat(data(performGet("/api/resumes", token))).isEmpty();
        var corrected = (ObjectNode) parsed.deepCopy(); corrected.put("ownerName", "核对后的候选人");
        var project = corrected.putObject("details").putArray("projects").addObject()
                .put("name", "订单履约服务").put("role", "接口负责人").put("background", "订单交付");
        project.putArray("contributions").add("我负责订单接口设计与对照测试");
        project.putArray("techStack").add("Java").add("MySQL");
        var resume = data(performPost("/api/resumes", corrected, token));
        var job = data(performPost("/api/target-jobs", jobRequest(), token));
        var request = objectMapper.createObjectNode().put("targetRole", "客户端不能覆盖岗位名称")
                .put("resumeId", resume.path("id").asLong()).put("targetJobId", job.path("id").asText())
                .put("targetJobRevision", 0).put("mode", "GENERAL").put("totalRounds", 6);
        var session = data(performPost("/api/interviews/sessions", request, token));
        assertThat(session.path("targetRole").asText()).isEqualTo(job.path("title").asText());
        assertThat(session.path("jobSnapshot").asText()).contains(job.path("jdText").asText(), job.path("id").asText(), "修订 0");
        assertThat(session.path("resumeSnapshot").asText()).contains("核对后的候选人", "我负责订单接口设计与对照测试");
        assertThat(session.path("agent").path("plan")).hasSize(5);
        assertThat(session.path("agent").path("plan").toString())
                .contains("订单履约服务", "接口负责人", "订单接口设计与对照测试", "materialAnchors");
        String url = "/api/interviews/sessions/" + session.path("sessionId").asText();
        var changed = jobRequest().put("title", "更新后的岗位"); changed.put("jdText", "已修改的 JD");
        data(performPut("/api/target-jobs/" + job.path("id").asText(), changed, token));
        assertThat(performPost("/api/interviews/sessions", request, token).getResponse().getStatus()).isEqualTo(409);
        data(performDelete("/api/target-jobs/" + job.path("id").asText() + "?revision=1", token));
        for (int round = 1; round <= 6; round++) {
            session = data(performPost(url + "/answers", Map.of("answer", "我负责接口开发与真实对照验证", "requestId", "materials-answer-" + round, "expectedTurn", round), token));
        }
        assertThat(session.path("state").asText()).isEqualTo("FINISHED");
        assertThat(session.path("agent").path("evaluations")).hasSize(6);
        assertThat(data(performGet(url, token)).path("jobSnapshot").asText()).contains(job.path("jdText").asText());
    }

    @Test void rejectsForeignOrMixedJobSourcesBeforeCreatingSession() throws Exception {
        String owner = registerAndLoginWithUniqueUsername("password123", "材料归属用户");
        String other = registerAndLoginWithUniqueUsername("password123", "其他材料用户");
        var job = data(performPost("/api/target-jobs", jobRequest(), owner));
        var request = objectMapper.createObjectNode().put("targetRole", "Java").put("targetJobId", job.path("id").asText()).put("targetJobRevision", 0);
        assertThat(performPost("/api/interviews/sessions", request, other).getResponse().getStatus()).isEqualTo(404);
        request.put("jobDescription", "另一份 JD");
        assertThat(performPost("/api/interviews/sessions", request, owner).getResponse().getStatus()).isEqualTo(400);
        request.remove("jobDescription"); request.remove("targetJobRevision");
        assertThat(performPost("/api/interviews/sessions", request, owner).getResponse().getStatus()).isEqualTo(400);
        request.put("targetJobRevision", -1);
        assertThat(performPost("/api/interviews/sessions", request, owner).getResponse().getStatus()).isEqualTo(400);
        assertThat(data(performGet("/api/interviews/sessions", owner)).path("total").asInt()).isZero();
    }
}
