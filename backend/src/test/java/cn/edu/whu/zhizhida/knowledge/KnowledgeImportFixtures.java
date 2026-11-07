package cn.edu.whu.zhizhida.knowledge;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.poifs.crypt.*;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.openxml4j.opc.OPCPackage;
import java.awt.image.BufferedImage;
import java.io.*;

final class KnowledgeImportFixtures {
    private KnowledgeImportFixtures() { }
    static byte[] pdf(boolean image, boolean encrypted, int pages) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i=0; i<pages; i++) {
                var page = new PDPage(); document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    if (image) stream.drawImage(LosslessFactory.createFromImage(document, new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)), 20, 20);
                    else {
                        stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(40, 700); stream.showText("Cache TTL 90 seconds. Page " + (i+1)); stream.endText();
                    }
                }
            }
            if (encrypted) document.protect(new StandardProtectionPolicy("owner", "secret", new AccessPermission()));
            document.save(output); return output.toByteArray();
        }
    }
    static byte[] docx(String first, boolean table) throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(first);
            if (table) {
                var row = document.createTable(1, 2).getRow(0);
                row.getCell(0).setText("中间表格：缓存 TTL 90 秒。"); row.getCell(1).setText("第二列：保留顺序。");
                document.createParagraph().createRun().setText("表后正文：数据库请求合并。");
            }
            document.write(output); return output.toByteArray();
        }
    }
    static byte[] encryptedDocx() throws Exception {
        try (var fs = new POIFSFileSystem(); var output = new ByteArrayOutputStream();
             var pack = OPCPackage.open(new ByteArrayInputStream(docx("secret", false)))) {
            var encryptor = new EncryptionInfo(EncryptionMode.agile).getEncryptor(); encryptor.confirmPassword("secret");
            try (var stream = encryptor.getDataStream(fs)) { pack.save(stream); }
            fs.writeFilesystem(output); return output.toByteArray();
        }
    }
    static byte[] largePdf() throws Exception {
        try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf(false,false,1000));var output=new ByteArrayOutputStream()) {
            var padding=doc.getDocument().createCOSStream();
            try(var stream=padding.createRawOutputStream()) {
                byte[] block=new byte[8192];new java.util.Random(42).nextBytes(block);
                for(int i=0;i<3400;i++) stream.write(block);
            }
            doc.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("Padding"),padding);
            doc.save(output);return output.toByteArray();
        }
    }
}
