package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.common.StructuredOutput.RejectionReason;
import cn.edu.whu.zhizhida.resume.api.ResumeImportResult;
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
        var result = ResumeAiImportOutput.parse(mapper.readTree(output), source, local.parse(source, "test.doc", null), "后端开发").draft();
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
        assertThat(result.ownerName()).isEmpty();
        assertThat(result.targetRole()).isEmpty();
        assertThat(result.details().education()).isEmpty();
        assertThat(result.educationExperiences()).containsExactly("示例大学　软件工程｜本科　2028.6毕业");
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
            "{\"summary\":\"虚构百分比 78%\"}"})
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

    @Test
    void retainsValidFieldsAndArrayItemsWithWarningsForRejectedValues() {
        var result = parseResult("""
                {"ownerName":"杜撰姓名","summary":42,"skills":["Java",42,"C","MySQL"],
                 "projectExperiences":["降低10.5%","完成接口开发"]}
                """, "姓名：张晓\n技能：Java、MySQL、C++\n完成接口开发，降低1.05%。");
        assertThat(result.draft().ownerName()).isEmpty();
        assertThat(result.draft().targetRole()).isEmpty();
        assertThat(result.draft().summary()).isNull();
        assertThat(result.draft().skills()).containsExactly("Java", "MySQL");
        assertThat(result.draft().projectExperiences()).containsExactly("完成接口开发");
        assertThat(result.recognitionMode()).isEqualTo(ResumeImportResult.RecognitionMode.AI);
        assertThat(result.warnings()).extracting(ResumeImportResult.Warning::field)
                .contains("ownerName", "summary", "skills[1]", "skills[2]", "projectExperiences[0]", "targetRole");
        assertThat(result.warnings().toString()).doesNotContain("杜撰姓名", "10.5%", "张晓");
    }

    @Test
    void discardsNamelessProjectsAndOnlyBadItemsWithinNamedProjects() {
        var result = parseResult("""
                {"details":{"projects":[{"name":"不存在的项目","contributions":["完成接口开发"]},
                {"name":"预约工具","background":false,"contributions":["虚构成果","完成接口开发"],
                 "techStack":["Java","Kubernetes"]}]}}
                """, "预约工具\nJava\n完成接口开发");
        assertThat(result.draft().details().projects()).singleElement().satisfies(project -> {
            assertThat(project.name()).isEqualTo("预约工具");
            assertThat(project.background()).isNull();
            assertThat(project.contributions()).containsExactly("完成接口开发");
            assertThat(project.techStack()).containsExactly("Java");
        });
        assertThat(result.warnings()).extracting(ResumeImportResult.Warning::field)
                .contains("details.projects[0].name", "details.projects[1].background",
                        "details.projects[1].contributions[0]", "details.projects[1].techStack[1]");
    }

    @Test
    void keepsValidContactFieldsAndLinksWithoutRestoringRejectedContactValues() {
        var result = parseResult("""
                {"skills":["Java"],"details":{"contact":{"phone":42,"email":"broken@","links":[
                {"url":"https://example.org/log"},{"url":"javascript:alert(1)"},
                {"url":"https://example.org/Log","label":42}]}}}
                """, "Java\n电话：13800000000\n邮箱：broken@\nhttps://example.org/Log javascript:alert(1)");
        assertThat(result.draft().details().contact().phone()).isNull();
        assertThat(result.draft().details().contact().email()).isNull();
        assertThat(result.draft().details().contact().links()).singleElement().satisfies(link -> {
            assertThat(link.url()).isEqualTo("https://example.org/Log");
            assertThat(link.label()).isEqualTo("个人链接");
        });
        assertThat(result.warnings()).extracting(ResumeImportResult.Warning::field)
                .contains("details.contact.phone", "details.contact.email", "details.contact.links[0].url",
                        "details.contact.links[1].url", "details.contact.links[2].label");
    }

    @Test
    void skipsMalformedSectionsWithoutDiscardingOtherFields() {
        var result = parseResult("""
                {"skills":["Java"],"details":{"education":[null],"projects":false,"contact":42}}
                """, "Java\n电话：13800000000");
        assertThat(result.draft().skills()).containsExactly("Java");
        assertThat(result.draft().details().contact()).isNull();
        assertThat(result.warnings()).extracting(ResumeImportResult.Warning::field)
                .contains("details.education[0]", "details.projects", "details.contact");
    }

    @Test
    void doesNotInventAnEducationParagraphByJoiningFieldsFromDifferentLines() {
        var result = parseResult("""
                {"skills":["Java"],"details":{"education":[
                {"school":"示例大学","major":"软件工程","degree":"本科"}]}}
                """, "技能：Java\n示例大学 招聘活动\n软件工程 本科 教材说明");
        assertThat(result.draft().details().education()).isEmpty();
        assertThat(result.draft().educationExperiences()).isEmpty();
        assertThat(result.warnings()).anySatisfy(warning -> {
            assertThat(warning.field()).isEqualTo("details.education[0]");
            assertThat(warning.code()).isEqualTo(RejectionReason.MISSING_FIELD);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"-", "至", "～"})
    void acceptsEquivalentDateRangeSeparatorsOnlyBetweenDates(String separator) {
        var result = parseResult("""
                {"details":{"projects":[{"name":"预约工具","period":"2025-04至2025-08"}]}}
                """, "预约工具 2025.04" + separator + "2025.08");
        assertThat(result.draft().details().projects().getFirst().period()).isEqualTo("2025-04至2025-08");
        assertThat(result.warnings()).noneSatisfy(warning -> assertThat(warning.field()).contains("period"));
    }

    @Test
    void recognizesCurrentDateRangesWithoutTreatingAdjacentDatesAsARange() {
        var current = parseResult("""
                {"details":{"projects":[{"name":"预约工具","period":"2025-04-至今"}]}}
                """, "预约工具 2025.04至今");
        assertThat(current.draft().details().projects().getFirst().period()).isEqualTo("2025-04-至今");
        var adjacent = parseResult("""
                {"details":{"projects":[{"name":"预约工具","period":"2025-04-2025-08"}]}}
                """, "预约工具 2025-042025-08");
        assertThat(adjacent.draft().details().projects().getFirst().period()).isNull();
    }

    @Test
    void limitsArraysWhileRetainingTheirValidPrefix() {
        String values = String.join(",", java.util.Collections.nCopies(31, "\"Java\""));
        var result = parseResult("{\"skills\":[" + values + "]}", "Java");
        assertThat(result.draft().skills()).containsExactly("Java");
        assertThat(result.warnings()).anySatisfy(warning -> {
            assertThat(warning.field()).isEqualTo("skills");
            assertThat(warning.code()).isEqualTo(RejectionReason.LIMIT_EXCEEDED);
        });
    }

    @Test
    void roleOverrideDoesNotTurnEmptyModelOutputIntoSuccess() {
        assertThatThrownBy(() -> ResumeAiImportOutput.parse(mapper.readTree("{}"), "Java",
                local.parse("Java", "模板.docx", null), "后端开发"))
                .isInstanceOf(StructuredOutput.FieldValidationException.class);
        var result = ResumeAiImportOutput.parse(mapper.readTree("{\"skills\":[\"Java\"]}"), "Java",
                local.parse("Java", "模板.docx", null), "后端开发");
        assertThat(result.draft().targetRole()).isEqualTo("后端开发");
        assertThat(result.warnings()).extracting(ResumeImportResult.Warning::field).containsExactly("ownerName");
    }

    private ResumeImportResult.Draft parse(String payload, String source) {
        return parseResult(payload, source).draft();
    }

    private ResumeImportResult parseResult(String payload, String source) {
        return ResumeAiImportOutput.parse(mapper.readTree(payload), source, local.parse(source, "模板.docx", null), null);
    }
}
