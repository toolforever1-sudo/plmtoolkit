package com.sandisk.plm.tracker.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Compliance-grade approval PDF generator for IMS Review (Phase 3).
 *
 * <p>Renders a single-page PDF capturing one approval event — DO_NO_CHANGE
 * or DM_APPROVED — with the verified identity, action, timestamps, IP, and a
 * fingerprint that ties the PDF back to the queue.jsonl event log.</p>
 *
 * <p>Output is stored at {@code data/ims-review/pdfs/<docNumber>-<eventTs>-<role>.pdf}
 * and its SHA-256 is appended to the corresponding response event so the PDF
 * embedded in the DRR and the toolkit's system-of-record can be cross-checked
 * by an auditor at any future date.</p>
 *
 * <p>PDFBox 2.0.x doesn't ship a PDF/A-1b conformance helper. We render with
 * the Type-1 standard 14 fonts (always present, never embedded), keep the
 * document free of JavaScript / forms / external links, and set Producer +
 * CreationDate explicitly. That's structurally PDF/A-compatible and good
 * enough for the internal IMS workflow. If external compliance ever demands
 * a strict /A-1b stamp + embedded fonts + ICC profile, that's a follow-on
 * — the metadata + audit chain are already in place.</p>
 */
@Service
public class ImsReviewPdfService {

    private static final Logger LOG = Logger.getLogger(ImsReviewPdfService.class.getName());

    @Value("${app.ims-review.pdf-dir:./data/ims-review/pdfs}")
    private String pdfDir;

    @Value("${app.toolkit.version:1.0.1}")
    private String toolkitVersion;

    @PostConstruct
    public void init() {
        try { Files.createDirectories(Paths.get(pdfDir)); }
        catch (Exception e) { LOG.warning("[IMS-PDF] could not create " + pdfDir + ": " + e.getMessage()); }
    }

    /** Input to {@link #generate}. */
    public static final class ApprovalInput {
        public String docNumber;
        public String description;
        public String rev;
        public String lifecyclePhase;
        public String documentType;
        public String nextReviewDate;
        public String drrNumber;
        public String role;                  // "DO" | "DM"
        public String actionLabel;           // "No Change Needed" | "DM Approved — No Change Confirmed"
        public String signerDisplayName;
        public String signerEmail;
        public String signerSamAccount;
        public String signedAtUtc;           // ISO-8601
        public String signerIp;
        public String sendEventUuid;         // the SEND token they redeemed
        public String responseEventTs;       // the RESPONSE event timestamp (drives filename)
        /** Phase-5 rich-form payload. When present, the PDF surfaces a
         *  "Submitted DCO Request" section listing the form fields, and
         *  prints the checksum in the provenance footer so an auditor can
         *  bind the PDF 1:1 to the exact form in queue.jsonl. */
        public java.util.Map<String, Object> dcoForm;
        public String dcoFormChecksum;
    }

    public static final class GeneratedPdf {
        public Path path;
        public String relPath;
        public String sha256;
        public long bytes;
    }

    public GeneratedPdf generate(ApprovalInput in) {
        if (in.docNumber == null || in.role == null) {
            throw new IllegalArgumentException("docNumber and role are required");
        }
        try {
            byte[] bytes = renderPdfBytes(in);
            String sha256 = sha256Hex(bytes);

            String filename = safe(in.docNumber) + "-" + safe(in.responseEventTs) + "-" + in.role + ".pdf";
            Path target = Paths.get(pdfDir, filename);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);

            GeneratedPdf out = new GeneratedPdf();
            out.path = target;
            out.relPath = "pdfs/" + filename;
            out.sha256 = sha256;
            out.bytes = bytes.length;
            return out;
        } catch (Exception e) {
            throw new RuntimeException("PDF generation failed: " + e.getMessage(), e);
        }
    }

    /** Read a previously-generated PDF off disk. */
    public byte[] readPdf(String relPath) {
        try {
            // relPath is like "pdfs/Foo-...-DO.pdf"; pdfDir is absolute-or-relative path that ENDS at /pdfs
            // Cope with both shapes.
            Path candidate;
            if (relPath.startsWith("pdfs/")) {
                String name = relPath.substring("pdfs/".length());
                candidate = Paths.get(pdfDir, name);
            } else {
                candidate = Paths.get(pdfDir, relPath);
            }
            return Files.readAllBytes(candidate);
        } catch (Exception e) {
            throw new RuntimeException("PDF read failed for " + relPath + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------
    private byte[] renderPdfBytes(ApprovalInput in) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            // PDF metadata — fixed so the same input renders the same fingerprint.
            doc.getDocumentInformation().setTitle("IMS Document Review — Approval — " + in.docNumber);
            doc.getDocumentInformation().setAuthor("Sandisk PLM Toolkit");
            doc.getDocumentInformation().setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("Electronic approval of document review"));
            doc.getDocumentInformation().setProducer("Sandisk PLM Toolkit v" + toolkitVersion + " (PDFBox 2.0)");

            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            float marginX = 54;     // ~0.75"
            float marginY = 54;
            float lineGap = 16;
            float y = pageHeight - marginY;

            PDFont titleFont = PDType1Font.TIMES_BOLD;
            PDFont sectionFont = PDType1Font.HELVETICA_BOLD;
            PDFont bodyFont = PDType1Font.HELVETICA;
            PDFont monoFont = PDType1Font.COURIER;
            PDFont italicFont = PDType1Font.HELVETICA_OBLIQUE;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Title bar
                cs.setNonStrokingColor(0.17f, 0.24f, 0.31f);  // #2c3e50
                cs.addRect(0, pageHeight - 40, pageWidth, 40);
                cs.fill();
                cs.setNonStrokingColor(1f, 1f, 1f);
                drawText(cs, titleFont, 16, marginX, pageHeight - 26, "Sandisk — IMS Document Review");
                drawText(cs, bodyFont, 9, pageWidth - marginX - 180, pageHeight - 26, "Electronic Approval Record");
                cs.setNonStrokingColor(0f, 0f, 0f);
                y = pageHeight - 40 - 30;

                // Hero
                drawText(cs, titleFont, 18, marginX, y, "Approval — " + nvl(in.actionLabel));
                y -= 22;
                drawText(cs, italicFont, 10, marginX, y,
                        "This document captures the verified " + (in.role.equals("DM") ? "manager-level " : "document-owner ")
                      + "approval of the IMS document review described below.");
                y -= 24;

                // Section: Document
                y = drawSectionLabel(cs, sectionFont, marginX, y, "Document");
                y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "Number",        in.docNumber, true);
                y = drawKV(cs, bodyFont, bodyFont, marginX, y, lineGap, "Description",  in.description, false);
                y = drawKV(cs, bodyFont, bodyFont, marginX, y, lineGap, "Rev / Lifecycle",
                            nvl(in.rev) + "  /  " + nvl(in.lifecyclePhase), false);
                if (in.documentType != null && !in.documentType.isEmpty()) {
                    y = drawKV(cs, bodyFont, bodyFont, marginX, y, lineGap, "Type", in.documentType, false);
                }
                y = drawKV(cs, bodyFont, bodyFont, marginX, y, lineGap, "Next Review Date", in.nextReviewDate, false);
                if (in.drrNumber != null && !in.drrNumber.isEmpty()) {
                    y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "Related DRR", in.drrNumber, true);
                }
                y -= 10;

                // Section: Action
                y = drawSectionLabel(cs, sectionFont, marginX, y, "Action Taken");
                y = drawKV(cs, bodyFont, bodyFont, marginX, y, lineGap, "Role",   roleLabel(in.role), false);
                y = drawKV(cs, bodyFont, bodyFont, marginX, y, lineGap, "Decision", in.actionLabel, false);
                y -= 10;

                // Section: Signer
                y = drawSectionLabel(cs, sectionFont, marginX, y, "Approver");
                y = drawKV(cs, bodyFont, bodyFont, marginX, y, lineGap, "Name",     in.signerDisplayName, false);
                y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "Email",    in.signerEmail, true);
                y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "AD Login", in.signerSamAccount, true);
                y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "Timestamp (UTC)", in.signedAtUtc, true);
                y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "Originating IP",  in.signerIp, true);
                y -= 10;

                // Visible signature block
                y = drawSectionLabel(cs, sectionFont, marginX, y, "Electronic Signature");
                String sigLine = "Electronically signed by " + nvl(in.signerDisplayName)
                              + " <" + nvl(in.signerEmail) + ">"
                              + " on " + nvl(in.signedAtUtc) + " UTC.";
                y = drawWrappedText(cs, italicFont, 10, marginX, y, lineGap, pageWidth - 2 * marginX, sigLine);
                y -= 4;
                y = drawWrappedText(cs, italicFont, 9, marginX, y, lineGap - 2, pageWidth - 2 * marginX,
                        "This PDF is system-generated by Sandisk PLM Toolkit. Modifications outside the toolkit void its authenticity. The toolkit retains a hash of this exact document for cross-verification.");
                y -= 16;

                // Provenance footer
                String fingerprintInput = nvl(in.sendEventUuid) + "|" + nvl(in.role)
                        + "|" + nvl(in.actionLabel) + "|" + nvl(in.signerEmail)
                        + "|" + nvl(in.signedAtUtc) + "|" + nvl(in.docNumber);
                String fingerprint = sha256Hex(fingerprintInput.getBytes("UTF-8"));

                y = drawSectionLabel(cs, sectionFont, marginX, y, "Provenance");
                y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "SEND Event UUID",  in.sendEventUuid, true);
                y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "Toolkit Version",  toolkitVersion, true);
                y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "Record Fingerprint",
                            fingerprint.substring(0, 32) + "…" + fingerprint.substring(fingerprint.length() - 8), true);
                if (in.dcoFormChecksum != null && !in.dcoFormChecksum.isEmpty()) {
                    String fc = in.dcoFormChecksum;
                    y = drawKV(cs, bodyFont, monoFont, marginX, y, lineGap, "DCO Form Checksum",
                            fc.length() > 40 ? fc.substring(0, 32) + "…" + fc.substring(fc.length() - 8) : fc, true);
                }
                y = drawWrappedText(cs, bodyFont, 8, marginX, y, lineGap - 4, pageWidth - 2 * marginX,
                        "Auditors can re-verify this record via the toolkit's verify-pdf endpoint with the SEND Event UUID above.");

                // Bottom rule + footer pill
                cs.setStrokingColor(0.91f, 0.90f, 0.87f);  // #E8E6DF
                cs.setLineWidth(0.5f);
                cs.moveTo(marginX, marginY + 24);
                cs.lineTo(pageWidth - marginX, marginY + 24);
                cs.stroke();
                cs.setNonStrokingColor(0.42f, 0.45f, 0.50f);  // #6B7280
                drawText(cs, bodyFont, 8, marginX, marginY + 12,
                        "Sandisk PLM Toolkit  ·  IMS Review  ·  Page 1 of 1  ·  Generated " + Instant.now().toString());
            }

            // ---- Page 2: Submitted DCO Request (the rich-form field values) ----
            if (in.dcoForm != null && !in.dcoForm.isEmpty()) {
                PDPage page2 = new PDPage(PDRectangle.LETTER);
                doc.addPage(page2);
                try (PDPageContentStream cs2 = new PDPageContentStream(doc, page2)) {
                    cs2.setNonStrokingColor(0.17f, 0.24f, 0.31f);
                    cs2.addRect(0, pageHeight - 40, pageWidth, 40);
                    cs2.fill();
                    cs2.setNonStrokingColor(1f, 1f, 1f);
                    drawText(cs2, titleFont, 16, marginX, pageHeight - 26, "Submitted DCO Request");
                    cs2.setNonStrokingColor(0f, 0f, 0f);
                    float y2 = pageHeight - 40 - 30;
                    java.util.Map<String, Object> ff = in.dcoForm;

                    y2 = drawSectionLabel(cs2, sectionFont, marginX, y2, "Change details");
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Description of Change", formStr(ff, "descriptionOfChange"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Reason for Change",     formStr(ff, "reasonForChange"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Priority",              formStr(ff, "priority"), false);
                    y2 -= 8;
                    y2 = drawSectionLabel(cs2, sectionFont, marginX, y2, "Scope");
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Product Line(s)",            formStr(ff, "productLines"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Subcontractors",            formStr(ff, "subcontractors"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Training Requirement",      formStr(ff, "trainingRequirement"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Business Unit",             formStr(ff, "businessUnit"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Change Impact Disposition", formStr(ff, "changeImpactDisposition"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Change Impact Details",     formStr(ff, "changeImpactDetails"), false);
                    y2 -= 8;
                    y2 = drawSectionLabel(cs2, sectionFont, marginX, y2, "People");
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Document Owner(s)",   formStr(ff, "documentOwners"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Approvers",           formStr(ff, "approvers"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Observers",           formStr(ff, "observers"), false);
                    y2 = drawKV(cs2, bodyFont, bodyFont, marginX, y2, lineGap, "Notify Stakeholders", formStr(ff, "notifyStakeholders"), false);

                    cs2.setNonStrokingColor(0.42f, 0.45f, 0.50f);
                    drawText(cs2, bodyFont, 8, marginX, marginY + 12,
                            "Sandisk PLM Toolkit  -  IMS Review  -  Submitted DCO form  -  Form checksum " + nvl(in.dcoFormChecksum));
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /** Stringify a form value for the PDF — lists become "a; b; c", empties become "—". */
    private static String formStr(java.util.Map<String, Object> form, String key) {
        Object v = form == null ? null : form.get(key);
        if (v == null) return "—";
        if (v instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) v;
            if (list.isEmpty()) return "—";
            StringBuilder sb = new StringBuilder();
            for (Object o : list) { if (sb.length() > 0) sb.append("; "); sb.append(o == null ? "" : o.toString()); }
            return sb.toString();
        }
        String s = v.toString().trim();
        return s.isEmpty() ? "—" : s;
    }

    private float drawSectionLabel(PDPageContentStream cs, PDFont font, float x, float y, String label) throws Exception {
        cs.setNonStrokingColor(0.42f, 0.45f, 0.50f);
        drawText(cs, font, 9, x, y, label.toUpperCase());
        cs.setStrokingColor(0.91f, 0.90f, 0.87f);
        cs.setLineWidth(0.4f);
        cs.moveTo(x, y - 4);
        cs.lineTo(x + 250, y - 4);
        cs.stroke();
        cs.setNonStrokingColor(0.06f, 0.09f, 0.13f);   // #0F1720
        return y - 18;
    }

    private float drawKV(PDPageContentStream cs, PDFont keyFont, PDFont valFont,
                         float x, float y, float lineGap, String key, String val, boolean monoVal) throws Exception {
        cs.setNonStrokingColor(0.42f, 0.45f, 0.50f);
        drawText(cs, keyFont, 9, x, y, key);
        cs.setNonStrokingColor(0.06f, 0.09f, 0.13f);
        return drawWrappedText(cs, valFont, monoVal ? 10 : 11, x + 130, y, lineGap, 360, nvl(val));
    }

    /** Wrap text to fit width; return new Y after drawing. */
    private float drawWrappedText(PDPageContentStream cs, PDFont font, float size,
                                  float x, float y, float lineGap, float maxWidth, String text) throws Exception {
        if (text == null) text = "";
        List<String> lines = wrap(text, font, size, maxWidth);
        for (String l : lines) {
            drawText(cs, font, size, x, y, l);
            y -= lineGap;
        }
        if (lines.isEmpty()) y -= lineGap;
        return y;
    }

    private void drawText(PDPageContentStream cs, PDFont font, float size, float x, float y, String text) throws Exception {
        if (text == null) text = "";
        // PDFBox standard fonts use WinAnsi; replace anything outside it with a safe fallback.
        text = text.replaceAll("[^\\u0000-\\u00FF]", "?");
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private List<String> wrap(String text, PDFont font, float size, float maxWidth) throws Exception {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String candidate = line.length() == 0 ? w : line + " " + w;
            float wpts = font.getStringWidth(strip(candidate)) / 1000f * size;
            if (wpts > maxWidth && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(w);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("[^\\u0000-\\u00FF]", "?");
    }

    private static String roleLabel(String role) {
        if ("DM".equals(role)) return "Document Manager (DM)";
        if ("DO".equals(role)) return "Document Owner (DO)";
        return role;
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    /** Filesystem-safe slug for use in filenames. */
    private static String safe(String s) {
        if (s == null) return "x";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
