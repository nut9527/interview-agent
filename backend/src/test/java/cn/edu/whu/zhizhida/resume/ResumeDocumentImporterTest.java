package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.ai.api.AiMode;

import cn.edu.whu.zhizhida.common.BusinessException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeDocumentImporterTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ResumeDocumentImporter.class);
    private Level previousLevel;

    @BeforeEach
    void enableImportLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void restoreLogLevel() {
        logger.setLevel(previousLevel);
    }

    private final ResumeDocumentImporter importer = new ResumeDocumentImporter(
            new ResumeAiConfiguration().resumeImportRecognizer(AiMode.MOCK, null, new ResumeTextParser()), "target/pdfbox-font-cache");

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void importsDocxTextWithoutPersistingOriginalFile(CapturedOutput logs) throws Exception {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Name: Alice");
            document.createParagraph().createRun().setText("Target Role: Backend Engineer");
            document.createParagraph().createRun().setText("Skills: Java, Spring Boot, MySQL");
            document.createParagraph().createRun().setText("Education");
            document.createParagraph().createRun().setText("Wuhan University - Software Engineering");
            document.write(output);
            content = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "alice.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);

        var result = importer.importDocument(file, null).draft();

        assertThat(result.ownerName()).isEqualTo("Alice");
        assertThat(result.targetRole()).isEqualTo("Backend Engineer");
        assertThat(result.skills()).containsExactly("Java", "Spring Boot", "MySQL");
        assertThat(result.educationExperiences()).containsExactly("Wuhan University - Software Engineering");
        assertThat(logs.getAll()).contains("stage=extract outcome=success format=docx textChars=")
                .doesNotContain("Alice", "alice.docx", "Wuhan University");
    }

    @Test
    void importsChineseResumeWithTabbedHeaderAndStyledWordRuns() throws Exception {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var header = document.createParagraph();
            header.createRun().setText("姓名：");
            header.createRun().setText("陈明");
            header.createRun().addTab();
            header.createRun().setText("Java 后端实习生");
            document.createParagraph().createRun().setText("电话：xxxxxxx  |  邮箱：demo@example.org  |  所在城市：武汉市");
            document.createParagraph().createRun().setText("教育背景");
            var education = document.createParagraph();
            education.createRun().setText("示例大学　软件工程｜本科");
            education.createRun().addTab();
            education.createRun().setText("2028.06 毕业");
            document.createParagraph().createRun().setText("项目经历");
            var project = document.createParagraph();
            project.createRun().setText("▣ 示例项目");
            project.createRun().addTab();
            project.createRun().setText("2026.07–2026.09");
            var stack = document.createParagraph();
            stack.createRun().setText("技术栈：");
            stack.createRun().addTab();
            stack.createRun().setText("\u00a0Java\u00a021\u00a0");
            stack.createRun().setText("  ");
            stack.createRun().setText("\u00a0Spring\u00a0Boot\u00a04\u00a0");
            document.createParagraph().createRun().setText("项目简介：支持多轮模拟面试。");
            document.createParagraph().createRun().setText("故障恢复：实现任务重试。");
            document.createParagraph().createRun().setText("专业技能");
            document.createParagraph().createRun().setText("Java、MySQL");
            document.createParagraph().createRun().setText("荣誉与补充信息");
            document.createParagraph().createRun().setText("校级一等奖学金");
            document.write(output);
            content = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "实习简历.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);

        var result = importer.importDocument(file, null).draft();

        assertThat(result.ownerName()).isEqualTo("陈明");
        assertThat(result.targetRole()).isEqualTo("Java 后端实习生");
        assertThat(result.details().contact().phone()).isNull();
        assertThat(result.details().contact().email()).isEqualTo("demo@example.org");
        assertThat(result.details().contact().location()).isEqualTo("武汉市");
        assertThat(String.join(" ", result.educationExperiences())).contains("示例大学", "2028.06");
        assertThat(result.details().graduationDate()).isEqualTo("2028-06");
        assertThat(result.projectExperiences()).isEmpty();
        assertThat(result.details().projects()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("示例项目");
            assertThat(item.period()).contains("2026.07", "2026.09");
            assertThat(item.techStack()).containsExactly("Java 21", "Spring Boot 4");
            assertThat(item.background()).contains("多轮模拟面试");
            assertThat(item.contributions()).anyMatch(line -> line.contains("任务重试"));
        });
        assertThat(result.skills()).contains("Java", "MySQL");
        assertThat(result.certificates()).containsExactly("校级一等奖学金");
    }

    @Test
    void importsTextBasedPdf() throws Exception {
        byte[] content = minimalPdf("Name: Bob  Target Role: Java Engineer", "Email: bob@example.org",
                "Skills: Java, MySQL", "Projects", "Booking System 2025.01-2025.08",
                "Tech Stack: Spring Boot / MySQL", "Implemented booking reminders.");
        MockMultipartFile file = new MockMultipartFile("file", "bob.pdf", "application/pdf", content);

        var result = importer.importDocument(file, null).draft();

        assertThat(result.ownerName()).isEqualTo("Bob");
        assertThat(result.targetRole()).isEqualTo("Java Engineer");
        assertThat(result.skills()).containsExactly("Java", "MySQL");
        assertThat(result.details().contact().email()).isEqualTo("bob@example.org");
        assertThat(result.details().projects()).singleElement().satisfies(project -> {
            assertThat(project.name()).isEqualTo("Booking System");
            assertThat(project.techStack()).containsExactly("Spring Boot", "MySQL");
            assertThat(project.contributions()).containsExactly("Implemented booking reminders.");
        });
    }

    private byte[] minimalPdf(String... lines) {
        String textStream = "BT /F1 12 Tf 60 740 Td "
                + String.join(" 0 -18 Td ", List.of(lines).stream()
                .map(line -> "(" + line.replace("(", "\\(").replace(")", "\\)") + ") Tj")
                .toList())
                + " ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + textStream.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n"
                        + textStream + "\nendstream"
        );
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.size() + 1];
        for (int index = 0; index < objects.size(); index += 1) {
            offsets[index + 1] = pdf.toString().getBytes(StandardCharsets.ISO_8859_1).length;
            pdf.append(index + 1).append(" 0 obj\n").append(objects.get(index)).append("\nendobj\n");
        }
        int xrefOffset = pdf.toString().getBytes(StandardCharsets.ISO_8859_1).length;
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int index = 1; index < offsets.length; index += 1) {
            pdf.append(String.format("%010d 00000 n \n", offsets[index]));
        }
        pdf.append("trailer << /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xrefOffset).append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void rejectsUnsupportedOrUnreadableFiles() {
        MockMultipartFile textFile = new MockMultipartFile("file", "resume.txt", "text/plain", "hello".getBytes());
        MockMultipartFile brokenPdf = new MockMultipartFile("file", "resume.pdf", "application/pdf", "not a pdf".getBytes());

        assertThatThrownBy(() -> importer.importDocument(textFile, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持");
        assertThatThrownBy(() -> importer.importDocument(brokenPdf, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void shouldRejectNullFile() {
        assertThatThrownBy(() -> importer.importDocument(null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择要导入的简历文件");
    }

    @Test
    void shouldRejectEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> importer.importDocument(emptyFile, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择要导入的简历文件");
    }

    @Test
    void shouldRejectOversizedFile() {
        byte[] largeContent = new byte[6_000_000];
        MockMultipartFile largeFile = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", largeContent);

        assertThatThrownBy(() -> importer.importDocument(largeFile, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过 5MB");
    }

    @Test
    void shouldRejectFileWithoutExtension() {
        MockMultipartFile noExtFile = new MockMultipartFile(
                "file", "resume", "application/pdf", "Name: Test".getBytes());

        assertThatThrownBy(() -> importer.importDocument(noExtFile, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    void shouldRejectNullFileName() {
        MockMultipartFile nullNameFile = new MockMultipartFile(
                "file", null, "application/pdf", "Name: Test".getBytes());

        assertThatThrownBy(() -> importer.importDocument(nullNameFile, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    void shouldRejectEmptyPdfDocument() {
        byte[] content = minimalPdf("");
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", content);

        assertThatThrownBy(() -> importer.importDocument(file, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未检测到可导入文本");
    }
}
