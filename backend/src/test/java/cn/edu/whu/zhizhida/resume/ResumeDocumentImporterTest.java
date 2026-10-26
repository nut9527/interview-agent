package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeDocumentImporterTest {

    private final ResumeDocumentImporter importer = new ResumeDocumentImporter(
            new ResumeTextParser(), "target/pdfbox-font-cache");

    @Test
    void importsDocxTextWithoutPersistingOriginalFile() throws Exception {
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

        ResumeProfileRequest result = importer.importDocument(file, null);

        assertThat(result.ownerName()).isEqualTo("Alice");
        assertThat(result.targetRole()).isEqualTo("Backend Engineer");
        assertThat(result.skills()).containsExactly("Java", "Spring Boot", "MySQL");
        assertThat(result.educationExperiences()).containsExactly("Wuhan University - Software Engineering");
    }

    @Test
    void importsTextBasedPdf() throws Exception {
        byte[] content = minimalPdf("Name: Bob", "Target Role: Java Engineer", "Skills: Java, MySQL");
        MockMultipartFile file = new MockMultipartFile("file", "bob.pdf", "application/pdf", content);

        ResumeProfileRequest result = importer.importDocument(file, null);

        assertThat(result.ownerName()).isEqualTo("Bob");
        assertThat(result.targetRole()).isEqualTo("Java Engineer");
        assertThat(result.skills()).containsExactly("Java", "MySQL");
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
