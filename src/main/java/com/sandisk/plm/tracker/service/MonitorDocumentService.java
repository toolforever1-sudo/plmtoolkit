package com.sandisk.plm.tracker.service;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.logging.Logger;

/**
 * Generates .docx debug analysis documents for individual scheduled job errors.
 * Each document follows the plm-field-tracker debugging style: root cause,
 * severity, source code context, and step-by-step fix recommendations.
 */
@Service
public class MonitorDocumentService {

    private static final Logger logger = Logger.getLogger(MonitorDocumentService.class.getName());
    private static final SimpleDateFormat TIMESTAMP_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    /**
     * Generate a .docx debug analysis document for a single job error.
     *
     * @param taskName      Name of the scheduled job
     * @param errorMessage  The error/exception message
     * @param stackTrace    Full stack trace text
     * @param aiAnalysis    AI-generated analysis (markdown-ish text)
     * @param sourceCode    Relevant source code snippet (or null)
     * @param repoInfo      Bitbucket repo info string (or null)
     * @param severity      Severity rating from AI (e.g. "HIGH")
     * @param isUnmapped    Whether the repo was unmapped (AI guessed)
     * @return Base64-encoded .docx bytes
     */
    public String generateDebugDocument(String taskName, String errorMessage, String stackTrace,
                                        String aiAnalysis, String sourceCode, String repoInfo,
                                        String severity, boolean isUnmapped) {
        try (XWPFDocument doc = new XWPFDocument()) {

            // --- Title ---
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.LEFT);
            title.setSpacingAfter(200);
            XWPFRun titleRun = title.createRun();
            titleRun.setText("Debug Analysis — " + taskName);
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setFontFamily("Calibri");
            titleRun.setColor("2C3E50");

            // --- Timestamp ---
            XWPFParagraph timestampPara = doc.createParagraph();
            XWPFRun tsRun = timestampPara.createRun();
            tsRun.setText("Generated: " + TIMESTAMP_FMT.format(new Date()));
            tsRun.setFontSize(10);
            tsRun.setColor("666666");
            tsRun.setFontFamily("Calibri");

            // --- Severity ---
            if (severity != null && !severity.isEmpty()) {
                addSectionHeader(doc, "Severity");
                XWPFParagraph sevPara = doc.createParagraph();
                XWPFRun sevRun = sevPara.createRun();
                sevRun.setText(severity.toUpperCase());
                sevRun.setBold(true);
                sevRun.setFontSize(12);
                sevRun.setFontFamily("Calibri");
                String sevUpper = severity.toUpperCase();
                if (sevUpper.contains("CRITICAL") || sevUpper.contains("HIGH")) {
                    sevRun.setColor("C0392B");
                } else if (sevUpper.contains("MEDIUM")) {
                    sevRun.setColor("F39C12");
                } else if (sevUpper.contains("UNKNOWN")) {
                    // UNKNOWN means AI couldn't classify (most often: AI call
                    // itself failed). Render gray so it doesn't read as "LOW".
                    sevRun.setColor("777777");
                } else {
                    sevRun.setColor("27AE60");
                }
            }

            // --- Error Summary ---
            addSectionHeader(doc, "Error Summary");
            addBodyText(doc, errorMessage != null ? errorMessage : "(no error message)");

            // --- AI Analysis (main content) ---
            addSectionHeader(doc, "Root Cause Analysis");
            if (aiAnalysis != null && !aiAnalysis.isEmpty()) {
                // Split AI analysis into paragraphs and render
                String[] sections = aiAnalysis.split("\n\n");
                for (String section : sections) {
                    section = section.trim();
                    if (section.isEmpty()) continue;

                    if (section.startsWith("##") || section.startsWith("**")) {
                        // Sub-heading
                        String heading = section.replaceAll("^#+\\s*", "").replaceAll("\\*\\*", "");
                        addSubSectionHeader(doc, heading);
                    } else if (section.startsWith("```")) {
                        // Code block
                        String code = section.replaceAll("```\\w*\\n?", "").replaceAll("```$", "");
                        addCodeBlock(doc, code);
                    } else if (section.startsWith("1.") || section.startsWith("- ")) {
                        // List items
                        String[] items = section.split("\n");
                        for (String item : items) {
                            item = item.replaceAll("^\\d+\\.\\s*", "").replaceAll("^-\\s*", "").trim();
                            if (!item.isEmpty()) {
                                addBulletPoint(doc, item);
                            }
                        }
                    } else {
                        addBodyText(doc, section.replaceAll("\\*\\*", ""));
                    }
                }
            } else {
                addBodyText(doc, "AI analysis was not available for this error.");
            }

            // --- Source Code ---
            if (sourceCode != null && !sourceCode.isEmpty()) {
                addSectionHeader(doc, "Relevant Source Code");
                if (isUnmapped) {
                    XWPFParagraph note = doc.createParagraph();
                    note.setSpacingAfter(100);
                    XWPFRun noteRun = note.createRun();
                    noteRun.setText("Note: Repository mapping was not found. The AI analysis above includes educated guesses based on class names, package structure, and error patterns.");
                    noteRun.setItalic(true);
                    noteRun.setFontSize(10);
                    noteRun.setColor("856404");
                    noteRun.setFontFamily("Calibri");
                }
                addCodeBlock(doc, sourceCode);
            } else if (isUnmapped) {
                addSectionHeader(doc, "Source Code");
                XWPFParagraph note = doc.createParagraph();
                XWPFRun noteRun = note.createRun();
                noteRun.setText("Source code unavailable — repository mapping not found for this job's Java class. "
                        + "The root cause analysis above is based on AI inference from the stack trace, "
                        + "class names, and common Agile PLM integration patterns.");
                noteRun.setItalic(true);
                noteRun.setFontSize(10);
                noteRun.setColor("856404");
                noteRun.setFontFamily("Calibri");
            }

            // --- Repository Info ---
            if (repoInfo != null && !repoInfo.isEmpty()) {
                addSectionHeader(doc, "Repository Context");
                addBodyText(doc, repoInfo);
            }

            // --- Stack Trace ---
            addSectionHeader(doc, "Full Stack Trace");
            addCodeBlock(doc, stackTrace != null ? stackTrace : "(no stack trace available)");

            // --- Footer ---
            XWPFParagraph footer = doc.createParagraph();
            footer.setSpacingBefore(400);
            footer.setBorderTop(Borders.SINGLE);
            XWPFRun footerRun = footer.createRun();
            footerRun.setText("Generated by PLM Toolkit / Scheduled Job Monitor AI Debug Assistant");
            footerRun.setFontSize(9);
            footerRun.setColor("999999");
            footerRun.setFontFamily("Calibri");

            // Write to bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());

        } catch (Exception e) {
            logger.warning("[MONITOR-DOC] Failed to generate .docx for " + taskName + ": " + e.getMessage());
            return null;
        }
    }

    private void addSectionHeader(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingBefore(300);
        para.setSpacingAfter(100);
        para.setBorderBottom(Borders.SINGLE);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(14);
        run.setFontFamily("Calibri");
        run.setColor("2C3E50");
    }

    private void addSubSectionHeader(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingBefore(200);
        para.setSpacingAfter(80);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(12);
        run.setFontFamily("Calibri");
        run.setColor("34495E");
    }

    private void addBodyText(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingAfter(100);
        XWPFRun run = para.createRun();
        // Handle line breaks within text
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            run.setText(lines[i]);
            if (i < lines.length - 1) run.addBreak();
        }
        run.setFontSize(11);
        run.setFontFamily("Calibri");
    }

    private void addBulletPoint(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingAfter(60);
        XWPFRun run = para.createRun();
        run.setText("\u2022  " + text);
        run.setFontSize(11);
        run.setFontFamily("Calibri");
    }

    private void addCodeBlock(XWPFDocument doc, String code) {
        XWPFParagraph para = doc.createParagraph();
        para.setSpacingAfter(100);

        // Set shading for the code block background
        CTPPr pPr = para.getCTP().addNewPPr();
        CTShd shd = pPr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setFill("F5F5F5");

        XWPFRun run = para.createRun();
        String[] lines = code.split("\n");
        for (int i = 0; i < lines.length; i++) {
            run.setText(lines[i]);
            if (i < lines.length - 1) run.addBreak();
        }
        run.setFontSize(9);
        run.setFontFamily("Consolas");
        run.setColor("333333");
    }
}
