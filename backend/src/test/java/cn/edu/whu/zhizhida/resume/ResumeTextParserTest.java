package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfile.ResumeProfileRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeTextParserTest {

    private final ResumeTextParser parser = new ResumeTextParser();

    @Test
    void mapsLabeledChineseSectionsIntoStructuredResume() {
        String text = """
                姓名：王小明
                求职意向：Java 后端开发工程师
                个人简介
                熟悉服务端开发，重视测试与可维护性。
                专业技能：Java、Spring Boot、MySQL
                教育经历
                2024-2028 武汉大学 软件工程
                项目经历
                职智达 - 负责简历模块后端
                实习经历
                2026 某科技公司 后端实习生
                证书：CET-6、软件设计师
                """;

        ResumeProfileRequest result = parser.parse(text, "外部简历.docx", null);

        assertThat(result.ownerName()).isEqualTo("王小明");
        assertThat(result.targetRole()).isEqualTo("Java 后端开发工程师");
        assertThat(result.summary()).contains("可维护性");
        assertThat(result.skills()).containsExactly("Java", "Spring Boot", "MySQL");
        assertThat(result.educationExperiences()).containsExactly("2024-2028 武汉大学 软件工程");
        assertThat(result.projectExperiences()).containsExactly("职智达 - 负责简历模块后端");
        assertThat(result.internshipExperiences()).containsExactly("2026 某科技公司 后端实习生");
        assertThat(result.certificates()).containsExactly("CET-6", "软件设计师");
        assertThat(result.templateKey()).isEqualTo("classic");
    }

    @Test
    void usesProvidedTargetRoleAndFileNameFallback() {
        ResumeProfileRequest result = parser.parse("个人简介\n热爱软件工程", "李华的简历.pdf", "测试开发工程师");

        assertThat(result.ownerName()).isEqualTo("李华的简历");
        assertThat(result.targetRole()).isEqualTo("测试开发工程师");
    }

    @Test
    void shouldParseNameSectionVariants() {
        ResumeProfileRequest result = parser.parse("个人姓名：张伟\n技能：Java", "file.pdf", null);
        assertThat(result.ownerName()).isEqualTo("张伟");
    }

    @Test
    void shouldParseTargetSectionVariants() {
        ResumeProfileRequest result = parser.parse("求职意向：前端工程师\n技能：React", "file.pdf", null);
        assertThat(result.targetRole()).isEqualTo("前端工程师");
    }

    @Test
    void separatesNameAndPositionInHeaderAndKeepsCategorizedSkillsTogether() {
        var result = parser.parse("""
                个人简历
                姓名：李明\tJava 后端开发实习
                电话：xxxxxxx | 邮箱：xxxxxx | 所在城市：测试市
                每周可实习四天。
                专业技能
                后端与数据：熟悉事务、索引；实践并发控制。
                后端技术：Java、Spring Boot、MySQL
                荣誉与补充信息
                奖学金：校级一等奖学金。
                """, "样本.docx", null);
        assertThat(result.ownerName()).isEqualTo("李明");
        assertThat(result.targetRole()).isEqualTo("Java 后端开发实习");
        assertThat(result.summary()).isEqualTo("每周可实习四天。");
        assertThat(result.skills()).containsExactly("后端与数据：熟悉事务、索引；实践并发控制。", "Java", "Spring Boot", "MySQL");
        assertThat(result.certificates()).containsExactly("奖学金：校级一等奖学金。");
    }

    @Test
    void marksPlaceholderNameAndPrefersSuppliedTargetRole() {
        var result = parser.parse("姓名：________\tJava 后端开发实习\n项目经历\n接口测试",
                "模板.docx", "测试开发");
        assertThat(result.ownerName()).isEqualTo("待补充姓名");
        assertThat(result.targetRole()).isEqualTo("测试开发");
        assertThat(result.summary()).isEmpty();
    }

    @Test
    void readsIdentityValuesOnFollowingLines() {
        var result = parser.parse("基本信息\n姓名：\n刘明\n求职意向\n软件开发工程师\n专业技能：Java、Kotlin",
                "简历.docx", null);
        assertThat(result.ownerName()).isEqualTo("刘明");
        assertThat(result.targetRole()).isEqualTo("软件开发工程师");
        assertThat(result.summary()).isEmpty();
    }

    @Test
    void preservesSpacesInsideContactValueAndRecognizesSpacedRoleColumn() {
        var result = parser.parse("Name:  Alice  Backend Engineer\nPhone:  +1  555  0100\nSkills: Java",
                "resume.pdf", null);
        assertThat(result.ownerName()).isEqualTo("Alice");
        assertThat(result.targetRole()).isEqualTo("Backend Engineer");
        assertThat(result.details().contact().phone().replace(" ", "")).isEqualTo("+15550100");
        assertThat(result.summary()).isEmpty();
    }

    @Test
    void shouldParseSummarySectionVariants() {
        ResumeProfileRequest result = parser.parse("自我评价\n熟悉后端开发，有3年经验。", "file.pdf", null);
        assertThat(result.summary()).contains("后端开发");
    }

    @Test
    void shouldParseSkillsSectionVariants() {
        ResumeProfileRequest result = parser.parse("技能清单：Python, Django, Flask", "file.pdf", null);
        assertThat(result.skills()).contains("Python", "Django", "Flask");
    }

    @Test
    void shouldParseCertificatesSectionVariants() {
        ResumeProfileRequest result = parser.parse("荣誉证书：CET-6, 软件设计师", "file.pdf", null);
        assertThat(result.certificates()).contains("CET-6", "软件设计师");
    }

    @Test
    void shouldParseInternshipSectionVariants() {
        ResumeProfileRequest result = parser.parse("工作经历\n2025 腾讯 后端实习生", "file.pdf", null);
        assertThat(result.internshipExperiences()).contains("2025 腾讯 后端实习生");
    }

    @Test
    void shouldParseEducationSectionVariants() {
        ResumeProfileRequest result = parser.parse("教育背景\n武汉大学 计算机科学", "file.pdf", null);
        assertThat(result.educationExperiences()).contains("武汉大学 计算机科学");
    }

    @Test
    void shouldInferNameFromFirstLine() {
        ResumeProfileRequest result = parser.parse("张三\n技能：Java", "file.pdf", null);
        assertThat(result.ownerName()).isEqualTo("张三");
    }

    @Test
    void shouldUseFallbackTargetRoleWhenBlank() {
        ResumeProfileRequest result = parser.parse("技能：Java", "file.pdf", null);
        assertThat(result.targetRole()).isEqualTo("待补充目标岗位");
    }

    @Test
    void shouldUseFileNameAsNameWhenFirstLineIsHeading() {
        ResumeProfileRequest result = parser.parse("教育背景\n武汉大学 计算机科学", "王磊的简历.docx", null);
        assertThat(result.ownerName()).isEqualTo("王磊的简历");
    }

    @Test
    void shouldHandleNullFileNameInNameInference() {
        ResumeProfileRequest result = parser.parse("教育背景\n计算机科学", null, null);
        assertThat(result.ownerName()).isEqualTo("待补充姓名");
        assertThat(result.targetRole()).isEqualTo("待补充目标岗位");
    }

    @Test
    void shouldHandleEmptyResumeTextGracefully() {
        ResumeProfileRequest result = parser.parse("", "resume.pdf", "Java开发");
        assertThat(result.ownerName()).isEqualTo("resume");
        assertThat(result.targetRole()).isEqualTo("Java开发");
        assertThat(result.skills()).isEmpty();
    }

    @Test
    void shouldTrimAndNormalizeLinesWithBullets() {
        ResumeProfileRequest result = parser.parse("• 技能：Java, Spring Boot", "file.pdf", null);
        assertThat(result.skills()).contains("Java", "Spring Boot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"张😀三", "\u202eTest\u202c用户", "Ｔｅｓｔ", "<script>alert(1)</script>"})
    void preservesSpecialCharactersAsPlainText(String name) {
        var result = parser.parse("姓名：" + name + "\n技能：Java\u200b、Spring\u200cBoot、\u200dReact", "resume.pdf", null);
        assertThat(result.ownerName()).isEqualTo(name);
        assertThat(result.skills()).isNotEmpty();
    }

    @Test
    void parsesFullWidthSkillsAndMixedPunctuation() {
        var result = parser.parse("技能：Ｊａｖａ、Ｓｐｒｉｎｇ　Ｂｏｏｔ；Python|Django", "resume.pdf", null);
        assertThat(result.skills()).containsExactly("Ｊａｖａ", "Ｓｐｒｉｎｇ　Ｂｏｏｔ", "Python", "Django");
    }

    @Test
    void parsesLargeResumeText() {
        String text = "姓名：测试用户\n目标岗位：Java后端开发\n技能：Java, Spring Boot\n"
                + "项目经历：负责某大型系统开发，使用了Java、Spring Boot、MySQL等技术栈，完成了接口设计和联调测试，性能优化效果显著。\n".repeat(1000);
        var result = parser.parse(text, "long.pdf", null);
        assertThat(result.ownerName()).isEqualTo("测试用户");
        assertThat(result.targetRole()).isEqualTo("Java后端开发");
    }

    @Test
    void keepsConcurrentParsingResultsIndependent() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<ResumeProfileRequest>>();
            for (int i = 0; i < 8; i++) {
                String name = "用户" + i;
                futures.add(executor.submit(() -> parser.parse(
                        "姓名：" + name + "\n求职意向：Java 后端开发\n技能：Java、Spring Boot、MySQL", "resume.pdf", null)));
            }
            for (int i = 0; i < futures.size(); i++) {
                var result = futures.get(i).get(10, TimeUnit.SECONDS);
                assertThat(result.ownerName()).isEqualTo("用户" + i);
                assertThat(result.targetRole()).isEqualTo("Java 后端开发");
                assertThat(result.skills()).containsExactly("Java", "Spring Boot", "MySQL");
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
