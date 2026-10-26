package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 将 PDF、DOC 和 DOCX 提取为待确认的简历字段，不在此处持久化简历或原始文件。
 * 限制上传体积、PDF 页数与送入文本解析器的长度；扫描图片不做 OCR。
 */
@Component
public class ResumeDocumentImporter {

    static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 30;
    private static final int MAX_TEXT_LENGTH = 100_000;

    private final ResumeTextParser textParser;

    public ResumeDocumentImporter(ResumeTextParser textParser,
                                  @Value("${resume.import.font-cache-dir}") String fontCacheDir) {
        this.textParser = textParser;
        configurePdfFontCache(fontCacheDir);
    }

    public ResumeProfileRequest importDocument(MultipartFile file, String fallbackTargetRole) {
        validateFile(file);
        String fileName = file.getOriginalFilename();
        String extension = extension(fileName);
        try {
            byte[] content = file.getBytes();
            String text = switch (extension) {
                case "pdf" -> extractPdf(content);
                case "docx" -> extractDocx(content);
                case "doc" -> extractDoc(content);
                default -> throw unsupportedFormat();
            };
            String normalized = text == null ? "" : text.trim();
            if (normalized.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "文档中未检测到可导入文本，请确认文件不是扫描图片");
            }
            return textParser.parse(normalized.substring(0, Math.min(normalized.length(), MAX_TEXT_LENGTH)),
                    fileName, fallbackTargetRole);
        } catch (BusinessException exception) {
            throw exception;
        // 文件解析异常统一转为可展示的提示，避免向客户端暴露文档库内部错误。
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "简历文档解析失败，请确认文件未损坏且格式正确");
        }
    }

    private String extractPdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "PDF 页数不能超过 " + MAX_PDF_PAGES + " 页");
            }
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractDoc(byte[] content) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content));
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择要导入的简历文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "简历文件不能超过 5MB");
        }
        if (!extension(file.getOriginalFilename()).matches("pdf|doc|docx")) {
            throw unsupportedFormat();
        }
    }

    private String extension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private BusinessException unsupportedFormat() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "仅支持 PDF、DOC 和 DOCX 格式的简历");
    }

    private void configurePdfFontCache(String fontCacheDir) {
        try {
            Path directory = Path.of(fontCacheDir).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            System.setProperty("pdfbox.fontcache", directory.toString());
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化 PDF 字体缓存目录", exception);
        }
    }
}
