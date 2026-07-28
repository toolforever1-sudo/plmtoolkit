package com.sandisk.plm.tracker.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Extracts plain text from a document attachment's bytes so the Agent API can
 * hand readable content to a calling agent (which does its own summarization).
 * Supports PDF (PDFBox) and Word .docx (POI XWPF) — the formats the active
 * policy documents use — plus plain text. Other formats report
 * {@code status=unsupported} (download the raw file instead). Output is capped
 * at {@link #MAX_CHARS} with a truncation flag. Never throws — extraction
 * failures come back as {@code status=error}.
 */
@Service
public class AttachmentTextExtractor {

    public static final int MAX_CHARS = 400_000;

    public static final class Result {
        public final String status;    // ok | empty | unsupported | error
        public final String text;      // possibly truncated
        public final boolean truncated;
        public final String message;   // set for unsupported/error
        public Result(String status, String text, boolean truncated, String message) {
            this.status = status; this.text = text; this.truncated = truncated; this.message = message;
        }
    }

    public Result extract(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) return new Result("empty", "", false, "no file bytes");
        String ext = ext(fileName);
        String text;
        try {
            switch (ext) {
                case "pdf":  text = pdf(bytes);  break;
                case "docx": text = docx(bytes); break;
                case "txt":
                case "csv":  text = new String(bytes, StandardCharsets.UTF_8); break;
                default:
                    return new Result("unsupported", "", false,
                        "text extraction not supported for '." + ext + "' — download the file and extract client-side");
            }
        } catch (Exception e) {
            return new Result("error", "", false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (text == null) text = "";
        boolean truncated = text.length() > MAX_CHARS;
        if (truncated) text = text.substring(0, MAX_CHARS);
        String status = text.trim().isEmpty() ? "empty" : "ok";
        return new Result(status, text, truncated, null);
    }

    private static String pdf(byte[] bytes) throws Exception {
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private static String docx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return ex.getText();
        }
    }

    static String ext(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash < 0 ? name : name.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1).toLowerCase().trim();
    }
}
