package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.common.UserInputException;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.ZipFile;
import javax.xml.stream.*;

/** 通过临时文件提取 PDF，通过流式解析提取 DOCX；原始上传文件仅临时保留。 */
@Component
public class KnowledgeDocumentImporter {
    public static final int MAX_TEXT = 2_000_000;
    static final int MAX_PAGES = 1000;
    static final long MAX_PDF = 100L * 1024 * 1024;
    static final long MAX_DOCX = 30L * 1024 * 1024;
    private static final long MAX_EXPANDED = 128L * 1024 * 1024;
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final java.util.concurrent.Semaphore PARSERS = new java.util.concurrent.Semaphore(2);
    static String sourceName(String original) {
        String name = Optional.ofNullable(original).orElse("").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 255 || name.chars().anyMatch(Character::isISOControl)
                || !name.toLowerCase(Locale.ROOT).matches(".+\\.(pdf|docx|txt|md|markdown)$"))
            throw new UserInputException("请上传 PDF、DOCX、TXT 或 Markdown 文件，文件名不得超过 255 字。");
        return name;
    }
    static void validate(MultipartFile file) {
        String name = sourceName(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        long limit = name.endsWith(".pdf") ? MAX_PDF : name.endsWith(".docx") ? MAX_DOCX : 400000;
        if (file.getSize() > limit)
            throw new UserInputException(name.endsWith(".pdf") ? "PDF 文件不能超过 100 MB。"
                    : name.endsWith(".docx") ? "DOCX 文件不能超过 30 MB。" : "TXT / Markdown 文件不能超过 400 KB。");
        if (file.isEmpty()) throw new UserInputException("文件为空，没有可导入的正文。");
    }
    public KnowledgeImport parse(Path source, String name, BiConsumer<Integer,Integer> progress, Runnable checkpoint) {
        if (!PARSERS.tryAcquire()) throw new UserInputException("解析服务繁忙，请稍后重新上传。");
        try { return extract(source,name,progress,checkpoint); }
        finally { PARSERS.release(); }
    }
    public KnowledgeImport parseQueued(Path source,String name,BiConsumer<Integer,Integer> progress,Runnable checkpoint) {
        try {
            while(!PARSERS.tryAcquire(200,java.util.concurrent.TimeUnit.MILLISECONDS)) checkpoint.run();
        } catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException(); }
        try { checkpoint.run();return extract(source,name,progress,checkpoint); }
        finally { PARSERS.release(); }
    }
    private KnowledgeImport extract(Path source, String name, BiConsumer<Integer,Integer> progress, Runnable checkpoint) {
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        var warnings = new ArrayList<String>();
        try {
            var text = new BoundedText(checkpoint);
            switch (extension) {
                case "pdf" -> pdf(source, text, warnings, progress);
                case "docx" -> docx(source, text, warnings);
                default -> {
                    var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
                    try (var reader = new InputStreamReader(Files.newInputStream(source), decoder)) {
                        char[] buffer = new char[8192]; int count;
                        while ((count = reader.read(buffer)) != -1) text.write(buffer, 0, count);
                    }
                }
            }
            checkpoint.run(); String value = text.value.toString();
            if (value.startsWith("\uFEFF")) value = value.substring(1);
            if (value.isBlank()) throw new UserInputException("未提取到正文，请检查文件内容后重新上传。");
            if (value.indexOf('\0') >= 0) throw new UserInputException("正文含无效字符，请另存为有效文字文档后重试。");
            if (extension.matches("pdf|docx")) warnings.add("请对照原文件核对文序、乱码和缺失内容；图片不做 OCR，引用以确认入库的正文为准。");
            return new KnowledgeImport(name.substring(0, Math.min(name.length(), 160)), name, value, List.copyOf(warnings));
        } catch (InvalidPasswordException | EncryptedDocumentException ex) { throw encrypted(); }
        catch (TextLimitException ex) { throw new UserInputException("解析正文超过 2000000 字，请拆分文件后导入；未保存任何内容。"); }
        catch (CharacterCodingException ex) { throw new UserInputException("TXT / Markdown 文件须为有效 UTF-8 文本。"); }
        catch (UserInputException | java.util.concurrent.CancellationException ex) { throw ex; }
        catch (IOException | XMLStreamException | RuntimeException ex) {
            throw new UserInputException("文件解析失败，请确认文件未损坏、扩展名与实际格式一致后重试。");
        }
    }
    private void pdf(Path source, BoundedText text, List<String> warnings, BiConsumer<Integer,Integer> progress) throws IOException {
        try (var document = Loader.loadPDF(source.toFile(), () -> new ScratchFile(
                MemoryUsageSetting.setupTempFileOnly().setTempDir(source.getParent().toFile())))) {
            if (document.isEncrypted()) throw encrypted();
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) throw new UserInputException("PDF 不能超过 1000 页，请拆分后导入。");
            int[] scanned = {0}; progress.accept(0, pages);
            var stripper = new PDFTextStripper() {
                private int start, completed;
                @Override protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
                    text.checkpoint.run(); start=text.value.length(); super.startPage(page);
                }
                @Override protected void endPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
                    super.endPage(page);
                    if(text.value.substring(start).isBlank() && hasImages(page.getResources(),new HashSet<>())) scanned[0]++;
                    progress.accept(++completed,pages);
                }
            };
            stripper.setSortByPosition(true); stripper.setLineSeparator("\n"); stripper.writeText(document,text);
            if (scanned[0] > 0 && text.value.toString().isBlank())
                throw new UserInputException("这是扫描件或图片型 PDF，无法提取正文。请先进行 OCR 文字识别，再上传文字型 PDF 或 DOCX。");
            if (scanned[0] > 0) warnings.add("有 " + scanned[0] + " 页仅包含图片，未提取文字。请补全这些页的内容后确认。");
        }
    }
    private boolean hasImages(PDResources resources, Set<PDResources> visited) throws IOException {
        if (resources == null || !visited.add(resources) || visited.size() > 100) return false;
        for (var name : resources.getXObjectNames()) {
            if (resources.isImageXObject(name)) return true;
            if (resources.getXObject(name) instanceof PDFormXObject form && hasImages(form.getResources(), visited)) return true;
        }
        return false;
    }
    private void docx(Path source, BoundedText text, List<String> warnings) throws IOException, XMLStreamException {
        byte[] header; try (var input = Files.newInputStream(source)) { header = input.readNBytes(8); }
        if (FileMagic.valueOf(header) == FileMagic.OLE2) {
            try (var compound = new POIFSFileSystem(source.toFile(), true)) {
                if (compound.getRoot().hasEntry("EncryptedPackage")) throw encrypted();
            }
            throw new UserInputException("文件不是有效 DOCX，请用 Word 另存为 DOCX 后上传。");
        }
        try (var zip = new ZipFile(source.toFile())) {
            long expanded = 0; int entries = 0; boolean images = false;
            // 累计实际解压字节数，不仅信任压缩包声明的大小，限制异常文档的资源消耗。
            var all = zip.entries(); byte[] buffer = new byte[8192];
            while (all.hasMoreElements()) {
                text.checkpoint.run(); var entry = all.nextElement();
                if (++entries > 10000) throw new UserInputException("DOCX 条目超过 10000 个，请拆分文件后导入。");
                if (entry.getName().startsWith("word/media/")) images = true;
                try (var input = zip.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        text.checkpoint.run(); expanded += count;
                        if (expanded > MAX_EXPANDED) throw new UserInputException("DOCX 解压内容超过 128 MB，请拆分或移除大图片后导入。");
                    }
                }
            }
            var body = zip.getEntry("word/document.xml");
            if (body == null) throw new UserInputException("文件不是有效 DOCX，请检查格式或重新另存后上传。");
            if (body.getSize() > 32L * 1024 * 1024) throw new UserInputException("DOCX 正文 XML 超过 32 MB，请拆分后导入。");
            // 禁用 DTD 与外部实体，文档正文解析不应触发外部资源访问。
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            factory.setXMLResolver((a,b,c,d) -> { throw new XMLStreamException("不支持外部引用"); });
            try (var input = zip.getInputStream(body)) {
                var xml = factory.createXMLStreamReader(input); int deleted = 0; boolean inText = false;
                try {
                    while (xml.hasNext()) {
                        text.checkpoint.run(); int event = xml.next();
                        if (event == XMLStreamConstants.DTD) throw new XMLStreamException("不支持 DTD");
                        if (event == XMLStreamConstants.START_ELEMENT && WORD.equals(xml.getNamespaceURI())) {
                            String tag = xml.getLocalName(); if (tag.equals("del")) deleted++;
                            if (deleted == 0) {
                                if (tag.equals("t")) inText = true;
                                if (tag.equals("tab")) text.write("\t");
                                if (tag.equals("br") || tag.equals("cr")) text.write("\n");
                            }
                        } else if (event == XMLStreamConstants.CHARACTERS && inText && deleted == 0)
                            text.write(xml.getTextCharacters(), xml.getTextStart(), xml.getTextLength());
                        else if (event == XMLStreamConstants.END_ELEMENT && WORD.equals(xml.getNamespaceURI())) {
                            String tag = xml.getLocalName(); if (tag.equals("t")) inText = false;
                            if (tag.equals("p") && deleted == 0) text.write("\n");
                            if (tag.equals("del")) deleted--;
                        }
                    }
                } finally { xml.close(); }
            }
            if (images) {
                if (text.value.toString().isBlank()) throw new UserInputException("DOCX 仅包含图片，无法提取正文。请先进行 OCR 文字识别后再导入。");
                warnings.add("文档中的图片未转为文字，请核对并补全图片内的必要内容。");
            }
        } catch (java.util.zip.ZipException ex) { throw new UserInputException("文件不是有效 DOCX，请检查格式或重新另存后上传。"); }
    }
    private static UserInputException encrypted() {
        return new UserInputException("文件已加密，暂不支持密码文档。请先解除密码保护，再重新上传。");
    }
    private static class TextLimitException extends IOException { }
    private static class BoundedText extends Writer {
        final StringBuilder value = new StringBuilder(); final Runnable checkpoint;
        BoundedText(Runnable checkpoint) { this.checkpoint = checkpoint; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            checkpoint.run(); if (value.length() + length > MAX_TEXT) throw new TextLimitException();
            value.append(chars, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}

