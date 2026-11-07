package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile.ResumeProfileRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeAiImportOutputTest {
    private final JsonMapper mapper = new JsonMapper();
    private final ResumeTextParser local = new ResumeTextParser();

    @Test
    void extractsStructuredProjectWithGroundedFactsAndRespectsUserRole() {
        String source = """
                张晓｜Java 后端实习
                所在城市：武汉
                专业方向
                预约工具 2025年4月–2025年8月
                Spring   Boot 3；MySQL
                负责预约提醒，降低重复发送。
                """;
        String output = """
                {"ownerName":"张晓","targetRole":"Java 后端实习","skills":["Spring Boot 3"],
                 "details":{"projects":[{"name":"预约工具","period":"2025-04-2025-08","techStack":["MySQL"],
                   "contributions":["负责预约提醒，降低重复发送。"]}]}, "templateKey":"untrusted","revision":27}
                """;
        var result = ResumeAiImportOutput.parse(mapper.readTree(output), source, local.parse(source, "test.doc", null), "后端开发");
        assertThat(result.targetRole()).isEqualTo("后端开发");
        assertThat(result.ownerName()).isEqualTo("张晓");
        assertThat(result.summary()).isNull();
        assertThat(result.projectExperiences()).isEmpty();
        assertThat(result.details().projects()).singleElement().satisfies(project -> {
            assertThat(project.techStack()).containsExactly("MySQL");
            assertThat(project.contributions()).containsExactly("负责预约提醒，降低重复发送。");
        });
        assertThat(result.details().contact().location()).isEqualTo("武汉");
        assertThat(result.templateKey()).isEqualTo("classic");
        assertThat(result.revision()).isNull();
    }

    @Test
    void keepsIncompleteEducationInTextAndLeavesUnstatedIdentityForReview() {
        String source = "教育背景\n示例大学　软件工程｜本科　2028.6毕业";
        String output = """
                {"details":{"graduationDate":"2028-06","education":[
                  {"school":"示例大学","major":"软件工程","degree":"本科","endDate":"2028-06"}
                ]}}
                """;
        var result = parse(output, source);
        assertThat(result.ownerName()).isEqualTo("待补充姓名");
        assertThat(result.targetRole()).isEqualTo("待补充目标岗位");
        assertThat(result.details().education()).isEmpty();
        assertThat(result.educationExperiences()).containsExactly("示例大学 软件工程 本科 2028-06");
        assertThat(result.details().graduationDate()).isEqualTo("2028-06");
    }

    @Test
    void acceptsValidEducationAndContactOnlyFromDocument() {
        String source = """
                demo@example.org 13800000000 https://example.org/work
                示例大学 软件  本科 2024.09–2028.06
                """;
        var result = parse("""
                {"details":{"contact":{"phone":"13800000000","email":"demo@example.org","links":[
                {"label":"作品","url":"https://example.org/work"}]},"education":[
                {"school":"示例大学","major":"软件","degree":"本科","startDate":"2024-09","endDate":"2028-06"}]}}
                """, source);
        assertThat(result.details().contact().email()).isEqualTo("demo@example.org");
        assertThat(result.details().contact().links()).hasSize(1);
        assertThat(result.details().contact().links().getFirst().label()).isEqualTo("个人链接");
        assertThat(result.details().education()).hasSize(1);
        assertThat(result.educationExperiences()).isEmpty();
    }

    @Test
    void acceptsFormattingDifferencesWhenWordColumnsAreRecombined() {
        String source = "教育背景\n示例大学　软件工程｜本科\t2028.06 毕业\n负责开发：稳定性提升 20%。";
        var result = parse("""
                {"educationExperiences":["示例大学 软件工程 本科 2028-06 毕业。"],
                 "projectExperiences":["负责开发，稳定性提升20%"]}
                """, source);
        assertThat(result.educationExperiences()).containsExactly("示例大学 软件工程 本科 2028-06 毕业。");
        assertThat(result.projectExperiences()).containsExactly("负责开发，稳定性提升20%");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"skills\":[\"C\"]}", "{\"skills\":[\"C#\"]}", "{\"skills\":[\"Java\"]}",
            "{\"details\":{\"contact\":{\"links\":[{\"url\":\"https://example.org/log\"}]}}}",
            "{\"projectExperiences\":[\"降低10.5%\"]}"})
    void keepsSignificantSymbolsNumbersAndUrlPathCase(String payload) {
        assertThatThrownBy(() -> parse(payload, "C++、JavaScript；降低 1.05%。https://example.org/Log"))
                .isInstanceOf(StructuredOutput.FieldValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{\"skills\":[42]}", "{\"details\":false}", "{\"details\":{\"projects\":[null]}}",
            "{\"details\":{\"projects\":[{}]}}", "{\"skills\":[\"Kubernetes\"]}", "{\"ownerName\":\"伪造姓名\"}",
            "{\"ownerName\":\"张晓\"}", "{\"summary\":\"虚构百分比 78%\"}"})
    void rejectsWrongShapeInventedFactsAndEmptyRecognition(String output) {
        assertThatThrownBy(() -> parse(output, "张晓\n技能：Java")).isInstanceOf(StructuredOutput.FieldValidationException.class);
    }

    @Test
    void rejectsUnusableContactAndOversizedOrUntrustedLinks() {
        assertThatThrownBy(() -> parse("{\"details\":{\"contact\":{\"email\":\"broken@\"}}}", "邮箱：broken@"))
                .isInstanceOf(StructuredOutput.FieldValidationException.class);
        assertThatThrownBy(() -> parse("{\"details\":{\"contact\":{\"links\":[{\"url\":\"javascript:alert(1)\"}]}}}", "javascript:alert(1)"))
                .isInstanceOf(StructuredOutput.FieldValidationException.class);
        assertThatThrownBy(() -> parse("{\"skills\":[\"" + "A".repeat(81) + "\"]}", "A".repeat(100)))
                .isInstanceOf(StructuredOutput.FieldValidationException.class);
    }

    private ResumeProfileRequest parse(String payload, String source) {
        return ResumeAiImportOutput.parse(mapper.readTree(payload), source, local.parse(source, "模板.docx", null), null);
    }
}
