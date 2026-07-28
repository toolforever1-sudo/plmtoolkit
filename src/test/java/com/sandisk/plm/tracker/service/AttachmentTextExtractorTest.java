package com.sandisk.plm.tracker.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class AttachmentTextExtractorTest {

    private final AttachmentTextExtractor ex = new AttachmentTextExtractor();

    @Test
    void extractsPlainText() {
        AttachmentTextExtractor.Result r = ex.extract("hello policy world".getBytes(StandardCharsets.UTF_8), "note.txt");
        assertEquals("ok", r.status);
        assertTrue(r.text.contains("hello policy world"));
        assertFalse(r.truncated);
    }

    @Test
    void extractsDocx() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Gift policy threshold is seventy five dollars");
            doc.write(out);
            bytes = out.toByteArray();
        }
        AttachmentTextExtractor.Result r = ex.extract(bytes, "policy.docx");
        assertEquals("ok", r.status);
        assertTrue(r.text.contains("Gift policy threshold is seventy five dollars"), "got: " + r.text);
    }

    @Test
    void extractsPdf() throws Exception {
        byte[] bytes;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Compliance document sample text");
                cs.endText();
            }
            doc.save(out);
            bytes = out.toByteArray();
        }
        AttachmentTextExtractor.Result r = ex.extract(bytes, "doc.pdf");
        assertEquals("ok", r.status);
        assertTrue(r.text.contains("Compliance document sample text"), "got: " + r.text);
    }

    @Test
    void unsupportedFormat() {
        AttachmentTextExtractor.Result r = ex.extract(new byte[]{1, 2, 3}, "sheet.xlsx");
        assertEquals("unsupported", r.status);
        assertTrue(r.message.contains("xlsx"));
        assertEquals("", r.text);
    }

    @Test
    void emptyBytes() {
        assertEquals("empty", ex.extract(new byte[0], "x.pdf").status);
        assertEquals("empty", ex.extract(null, "x.pdf").status);
    }

    @Test
    void corruptPdfIsErrorNotThrow() {
        AttachmentTextExtractor.Result r = ex.extract("not a real pdf".getBytes(StandardCharsets.UTF_8), "bad.pdf");
        assertEquals("error", r.status);   // never throws
    }

    @Test
    void extensionParsing() {
        assertEquals("pdf", AttachmentTextExtractor.ext("a/b/c File (EN) - Rev 8.PDF".replace(".PDF", ".pdf")));
        assertEquals("docx", AttachmentTextExtractor.ext("Policy (EN) - Rev 8.docx"));
        assertEquals("", AttachmentTextExtractor.ext("noextension"));
    }
}
