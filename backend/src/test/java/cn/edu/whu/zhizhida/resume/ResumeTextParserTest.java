package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;

import org.junit.jupiter.api.Test;

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
}
