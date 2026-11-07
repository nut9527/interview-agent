package cn.edu.whu.zhizhida.knowledge;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeDocumentImporterTest {
    final KnowledgeDocumentImporter importer = new KnowledgeDocumentImporter();
    @org.junit.jupiter.api.io.TempDir Path directory;
    @BeforeAll static void fontCache() throws Exception {
        var path = Path.of("target/pdfbox-font-cache").toAbsolutePath(); Files.createDirectories(path);
        System.setProperty("pdfbox.fontcache", path.toString());
    }
    MockMultipartFile file(String name, byte[] bytes) { return new MockMultipartFile("file",name,"application/octet-stream",bytes); }
    cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport parseUpload(MockMultipartFile file) throws IOException {
        KnowledgeDocumentImporter.validate(file);
        Path source=Files.createTempFile(directory,"source-",".upload");
        try {
            file.transferTo(source);
            return importer.parse(source,KnowledgeDocumentImporter.sourceName(file.getOriginalFilename()),(done,total)->{},()->{});
        } finally { Files.deleteIfExists(source); }
    }
    void fails(String name, byte[] bytes, String message) {
        assertThatThrownBy(() -> parseUpload(file(name,bytes))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
    @Test void extractsPdfPagesInOrderAndDocxBodyAroundTables() throws Exception {
        var pdf=parseUpload(file("C:\\fakepath\\文字.PDF",KnowledgeImportFixtures.pdf(false,false,2)));
        assertThat(pdf.sourceName()).isEqualTo("文字.PDF");
        assertThat(pdf.content()).containsSubsequence("Page 1","Page 2"); assertThat(pdf.warnings()).isNotEmpty();
        var docx=parseUpload(file("资料.docx",KnowledgeImportFixtures.docx("第一段：缓存策略。",true)));
        assertThat(docx.content()).containsSubsequence("第一段","中间表格","第二列","表后正文");
    }
    @Test void preservesTextExactlyExceptBom() throws IOException {
        String content="# 标题\r\n\r\n缓存🙂正文\r\n";
        assertThat(parseUpload(file("笔记.md",("\uFEFF"+content).getBytes(StandardCharsets.UTF_8))).content()).isEqualTo(content);
    }
    @Test void rejectsScansEncryptionEmptyCorruptAndWrongFormat() throws Exception {
        fails("scan.pdf",KnowledgeImportFixtures.pdf(true,false,1),"OCR");
        fails("locked.pdf",KnowledgeImportFixtures.pdf(false,true,1),"加密");
        fails("locked.docx",KnowledgeImportFixtures.encryptedDocx(),"加密");
        fails("empty.docx",KnowledgeImportFixtures.docx("",false),"未提取到正文");
        fails("empty.pdf",KnowledgeImportFixtures.pdf(false,false,0),"未提取到正文");
        fails("broken.pdf",new byte[]{1,2,3},"解析失败");
        fails("broken.docx",new byte[]{1,2,3},"有效 DOCX");
        fails("empty.txt",new byte[0],"为空");
        fails("bad.txt",new byte[]{(byte)0xff},"UTF-8");
        fails("bad.md","a\0b".getBytes(StandardCharsets.UTF_8),"无效字符");
        fails("notes.doc",new byte[]{1},"请上传");
        fails("bad\n.pdf",new byte[]{1},"文件名");
    }
    @Test void rejectsEveryLimitWithoutTruncation() throws Exception {
        fails("large.txt",new byte[400001],"400 KB");
        fails("long.md","x".repeat(2000001).getBytes(StandardCharsets.UTF_8),"400 KB");
        fails("long.docx",KnowledgeImportFixtures.docx("x".repeat(2000001),false),"2000000");
        fails("pages.pdf",KnowledgeImportFixtures.pdf(false,false,1001),"1000 页");
        try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] block=new byte[8192]; for(int i=0;i<16385;i++) zip.write(block);
            zip.close(); fails("expanded.docx",bytes.toByteArray(),"128 MB");
        }
    }
    @Test void mixedPdfWarnsAboutMissingImagePages() throws Exception {
        try(var text=org.apache.pdfbox.Loader.loadPDF(KnowledgeImportFixtures.pdf(false,false,1));
            var image=org.apache.pdfbox.Loader.loadPDF(KnowledgeImportFixtures.pdf(true,false,1));var output=new ByteArrayOutputStream()) {
            text.importPage(image.getPage(0)); text.save(output);
            var result=parseUpload(file("mixed.pdf",output.toByteArray()));
            assertThat(result.content()).contains("TTL"); assertThat(result.warnings()).anyMatch(w->w.contains("1 页仅包含图片"));
        }
    }
    @Test void exportBrowserFixturesWhenRequested() throws Exception {
        if (!Boolean.getBoolean("knowledge.import.exportFixtures")) return;
        var directory=Path.of("target/knowledge-import-fixtures"); Files.createDirectories(directory);
        Files.write(directory.resolve("text.pdf"),KnowledgeImportFixtures.pdf(false,false,2));
        Files.write(directory.resolve("notes.docx"),KnowledgeImportFixtures.docx("缓存笔记：原始顺序待核对。",true));
        Files.write(directory.resolve("scan.pdf"),KnowledgeImportFixtures.pdf(true,false,1));
        Files.write(directory.resolve("encrypted.pdf"),KnowledgeImportFixtures.pdf(false,true,1));
        Files.write(directory.resolve("encrypted.docx"),KnowledgeImportFixtures.encryptedDocx());
        Files.write(directory.resolve("empty.docx"),KnowledgeImportFixtures.docx("",false));
        Files.write(directory.resolve("thousand.pdf"),KnowledgeImportFixtures.pdf(false,false,1000));
        Files.write(directory.resolve("long.docx"),KnowledgeImportFixtures.docx("缓存 TTL 90 秒。\n\n".repeat(16000),true));
        Files.write(directory.resolve("large.pdf"),KnowledgeImportFixtures.largePdf());
    }
    @Test void acceptsThousandPagesLargeBinaryAndLongChineseBody() throws Exception {
        var thousand=parseUpload(file("book.pdf",KnowledgeImportFixtures.pdf(false,false,1000)));
        assertThat(thousand.content()).contains("Page 1000");
        String chinese="缓存🙂正文\r\n".repeat(20000);
        assertThat(parseUpload(file("long.docx",KnowledgeImportFixtures.docx(chinese,false))).content()).contains(chinese);
        byte[] large=KnowledgeImportFixtures.largePdf();assertThat(large.length).isBetween(25*1024*1024,30*1024*1024);
        assertThat(parseUpload(file("large.pdf",large)).content()).contains("TTL","Page 1000");
        KnowledgeDocumentImporter.validate(new MockMultipartFile("file","boundary.pdf","application/pdf",new byte[]{1}) {
            @Override public long getSize() { return KnowledgeDocumentImporter.MAX_PDF; }
        });
    }
    @Test void enforcesSeparatePdfDocxAndTextUploadBoundaries() {
        var limits = java.util.Map.of("book.PDF", 100L * 1024 * 1024, "notes.docx", 30L * 1024 * 1024, "notes.md", 400000L);
        for (var entry : limits.entrySet()) {
            var boundary = new MockMultipartFile("file", entry.getKey(), "application/octet-stream", new byte[]{1}) {
                @Override public long getSize() { return entry.getValue(); }
            };
            assertThatCode(() -> KnowledgeDocumentImporter.validate(boundary)).doesNotThrowAnyException();
            var oversized = new MockMultipartFile("file", entry.getKey(), "application/octet-stream", new byte[]{1}) {
                @Override public long getSize() { return entry.getValue() + 1; }
            };
            assertThatThrownBy(() -> KnowledgeDocumentImporter.validate(oversized))
                    .isInstanceOf(cn.edu.whu.zhizhida.common.UserInputException.class).hasMessageContaining("不能超过");
        }
    }
    @Test void interruptionAndExternalXmlEntitiesNeverBecomeText() throws Exception {
        Path source=Files.createTempFile(Path.of("target"),"cancel-",".docx");
        try {
            Files.write(source,KnowledgeImportFixtures.docx("valid",false));
            assertThatThrownBy(()->importer.parse(source,"x.docx",(a,b)->{},()->{throw new java.util.concurrent.CancellationException();})).isInstanceOf(java.util.concurrent.CancellationException.class);
            try(var output=new ZipOutputStream(Files.newOutputStream(source))) {
                output.putNextEntry(new ZipEntry("word/document.xml"));
                output.write("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///nonexistent-secret'>]><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:p><w:r><w:t>&secret;</w:t></w:r></w:p></w:document>".getBytes(StandardCharsets.UTF_8));
            }
            assertThatThrownBy(()->importer.parse(source,"x.docx",(a,b)->{},()->{})).isInstanceOf(IllegalArgumentException.class);
        } finally { Files.deleteIfExists(source); }
    }
}
