package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.EmailTemplateService;
import com.sandisk.plm.tracker.service.FeedbackQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.mail.*;
import javax.mail.internet.*;
import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/support")
public class SupportController {

    private static final Logger logger = Logger.getLogger(SupportController.class.getName());

    @Value("${mail.smtp.host}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from}")
    private String fromAddress;

    @Value("${app.admin-email:pdl-plm-admin@sandisk.com}")
    private String adminEmail;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired(required = false)
    private com.sandisk.plm.tracker.service.FileArchiveService fileArchive;

    @Autowired
    private EmailTemplateService emailTemplate;

    @Autowired
    private FeedbackQueueService feedbackQueueService;

    @PostMapping("/raise-issue")
    public Map<String, Object> raiseIssue(@RequestBody Map<String, String> request, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();

        String description = request.get("description");
        String screenshot = request.get("screenshot"); // base64 data URI or null
        String currentUrl = request.get("currentUrl");
        String currentTab = request.get("currentTab");

        String username = session != null ? (String) session.getAttribute("username") : "unknown";
        String displayName = session != null ? (String) session.getAttribute("displayName") : "unknown";
        String email = session != null ? (String) session.getAttribute("email") : "";

        if (description == null || description.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "Description is required.");
            return response;
        }

        // Allocate the persistent PT-#### up front so the outbound email, the
        // success response, and the queue record all share the same id.
        // Raise Issue items land in the queue as "Bug Report" so admins see
        // them in the same triage view as regular feedback (PT-27 follow-up).
        String ptId = feedbackQueueService.allocateNextId();

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));

            Session mailSession = Session.getInstance(props);
            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress("pdl-plm-admin@sandisk.com"));
            if (email != null && !email.isEmpty()) {
                message.addRecipient(Message.RecipientType.CC, new InternetAddress(email));
            }
            String issueSubject = "Issue \u00b7 " + currentTab + " \u00b7 " + ptId + " \u00b7 from " + displayName;
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(issueSubject));
            if (email != null && !email.isEmpty()) {
                message.setReplyTo(new javax.mail.Address[]{ new InternetAddress(email) });
            }

            // Build HTML body via EmailTemplateService
            String timestamp = new java.text.SimpleDateFormat("MMM d, yyyy h:mm a").format(new java.util.Date());
            String heroTitle = description.length() > 60 ? description.substring(0, 60) + "..." : description;
            String heroSub = "Reported by " + displayName + " from the " + currentTab + " tab.";

            String replySubject = "Re: " + issueSubject;
            String mailtoHref;
            try { mailtoHref = "mailto:" + email + "?subject=" + java.net.URLEncoder.encode(replySubject, "UTF-8"); }
            catch (Exception enc) { mailtoHref = "mailto:" + email; }

            String bodyHtml = emailTemplate.callout(null, escHtml(description), "bad")
                    + emailTemplate.kvBlock(
                        emailTemplate.kvRow("Reporter", displayName + " (" + username + ")"),
                        emailTemplate.kvRow("Email", email),
                        emailTemplate.kvRow("Tab", currentTab),
                        emailTemplate.kvRow("URL", currentUrl)
                    );

            String body = emailTemplate.wrap(
                    "Support",          // section
                    "Issue",            // tag
                    "Issue report \u00b7 " + timestamp, // eyebrow
                    heroTitle,          // heroTitle
                    heroSub,            // heroSub
                    null,               // kpiHtml
                    bodyHtml,           // bodyHtml
                    "Reply to reporter",// ctaText
                    mailtoHref,         // ctaHref
                    null,               // ctaHint
                    null,               // attachHtml
                    null,               // metaStripHtml (auto-generate)
                    "issue queue \u2192 pdl-plm-admin" // footerLine
            );

            if (screenshot != null && !screenshot.isEmpty() && screenshot.startsWith("data:image")) {
                // Build multipart message with screenshot attachment
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setContent(body, "text/html; charset=utf-8");

                // Decode base64 image
                String base64Data = screenshot.substring(screenshot.indexOf(",") + 1);
                byte[] imageBytes = java.util.Base64.getDecoder().decode(base64Data);

                MimeBodyPart imagePart = new MimeBodyPart();
                javax.activation.DataSource ds = new javax.mail.util.ByteArrayDataSource(imageBytes, "image/png");
                imagePart.setDataHandler(new javax.activation.DataHandler(ds));
                imagePart.setFileName("screenshot.png");

                Multipart multipart = new MimeMultipart();
                multipart.addBodyPart(textPart);
                multipart.addBodyPart(imagePart);
                message.setContent(multipart);
            } else {
                message.setContent(body, "text/html; charset=utf-8");
            }

            Transport.send(message);
            logger.info("[SUPPORT] Issue raised by " + displayName + " (" + username + ") as " + ptId);

            activityLogger.log(username, displayName, "RAISE_ISSUE",
                    ptId + " | Issue raised: " + description.substring(0, Math.min(100, description.length())));

            // Persist to the feedback queue as a Bug Report so chatbot-raised
            // issues show up alongside regular feedback in the triage view.
            // Include the tab + URL inline in the queue text so admins can
            // route without opening the email; attach the screenshot if any.
            try {
                StringBuilder queueText = new StringBuilder();
                queueText.append(description);
                queueText.append("\n\n--- Context (auto-captured) ---\n");
                if (currentTab != null && !currentTab.isEmpty()) queueText.append("Tab: ").append(currentTab).append('\n');
                if (currentUrl != null && !currentUrl.isEmpty()) queueText.append("URL: ").append(currentUrl).append('\n');
                queueText.append("Submitted via: Raise Issue (chatbot)");

                MultipartFile screenshotFile = buildScreenshotMultipart(screenshot, ptId);
                feedbackQueueService.addItem(ptId, "Bug Report", queueText.toString(),
                        username, displayName, email, screenshotFile);
            } catch (Exception qx) {
                logger.warning("[SUPPORT] Queue persistence failed for " + ptId + ": " + qx.getMessage());
            }

            response.put("success", true);
            response.put("ptId", ptId);
            response.put("message", "Issue submitted successfully (" + ptId + ").");
        } catch (Exception e) {
            logger.warning("[SUPPORT] Failed to send issue: " + e.getMessage());
            response.put("success", false);
            response.put("message", "Failed to send: " + e.getMessage());
        }

        return response;
    }

    /** Wrap a base64 PNG data URI as a Spring MultipartFile so we can hand it
     *  to {@link FeedbackQueueService#addItem} unchanged. Returns null when
     *  no screenshot was supplied or the data URI was malformed. */
    private MultipartFile buildScreenshotMultipart(String dataUri, String ptId) {
        if (dataUri == null || dataUri.isEmpty() || !dataUri.startsWith("data:image")) return null;
        int comma = dataUri.indexOf(",");
        if (comma < 0) return null;
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(dataUri.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            logger.warning("[SUPPORT] Bad base64 in screenshot for " + ptId + ": " + e.getMessage());
            return null;
        }
        return new ByteArrayMultipartFile(bytes, "screenshot.png", "image/png");
    }

    /** Minimal {@link MultipartFile} backed by a byte array. Only the methods
     *  {@link FeedbackQueueService#addItem} actually calls are implemented in
     *  a meaningful way — the rest exist to satisfy the interface. */
    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] bytes;
        private final String filename;
        private final String contentType;
        ByteArrayMultipartFile(byte[] bytes, String filename, String contentType) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.filename = filename;
            this.contentType = contentType;
        }
        @Override public String getName() { return "attachment"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
        @Override public void transferTo(java.io.File dest) throws java.io.IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }

    @PostMapping(value = "/feedback", consumes = {"multipart/form-data", "application/json"})
    public Map<String, Object> submitFeedback(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String text,
            // PT-38: accept multiple files under "attachments". Keep "attachment"
            // (singular) for back-compat with old callers (AI Eval auto-file etc).
            @RequestParam(required = false) java.util.List<MultipartFile> attachments,
            @RequestParam(required = false) MultipartFile attachment,
            HttpSession session) {
        String username = session != null ? (String) session.getAttribute("username") : "unknown";
        String displayName = session != null ? (String) session.getAttribute("displayName") : "unknown";
        String email = session != null ? (String) session.getAttribute("email") : "";

        // PT-45: gate keyboard-mashing on the public Feedback form (e.g. "bcfkjdfbkdbkdbkd")
        // before it reaches the queue. Same deterministic heuristic that protects the
        // AI Eval bug-report path. Internal callers (AI Eval auto-file, crash-recovery
        // orphans) bypass this by calling submitFeedbackInternal directly — their bodies
        // are system-generated and shouldn't be re-gated.
        String tTrim = text == null ? "" : text.trim();
        if (com.sandisk.plm.tracker.util.GibberishHeuristic.looksLikeGibberish(tTrim)) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("success", false);
            bad.put("message", "That looks like random characters. Please describe the issue or suggestion in a sentence or two.");
            return bad;
        }

        java.util.List<MultipartFile> files = new java.util.ArrayList<>();
        if (attachments != null) {
            for (MultipartFile f : attachments) if (f != null && !f.isEmpty()) files.add(f);
        }
        if (attachment != null && !attachment.isEmpty()) files.add(attachment);

        // Archive each attachment to the file-archive (30-day retention). The feedback
        // flow doesn't go through UploadQuarantineService, so we record directly here.
        if (fileArchive != null) {
            for (MultipartFile f : files) {
                fileArchive.recordUpload(f, username, displayName,
                        "/api/support/feedback", "feedback-attachment", null);
            }
        }

        return submitFeedbackInternal(category, text, files, username, displayName, email);
    }

    /**
     * Programmatic entry point for filing feedback from other controllers
     * (e.g. the AI Eval User-Ask flow auto-files Bug Reports for C/D/F items
     * with explanations). Does the same email + queue persist the public
     * {@code /feedback} endpoint does.
     *
     * @return response map with {@code success} and {@code ptId} on success,
     *         or {@code success=false} + {@code message} on failure.
     */
    /** Back-compat overload for internal callers passing a single attachment. */
    public Map<String, Object> submitFeedbackInternal(String category, String text,
                                                        MultipartFile attachment,
                                                        String username, String displayName, String email) {
        java.util.List<MultipartFile> list = (attachment == null || attachment.isEmpty())
                ? java.util.Collections.<MultipartFile>emptyList()
                : java.util.Collections.singletonList(attachment);
        return submitFeedbackInternal(category, text, list, username, displayName, email);
    }

    public Map<String, Object> submitFeedbackInternal(String category, String text,
                                                        java.util.List<MultipartFile> attachments,
                                                        String username, String displayName, String email) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (category == null) category = "General";
        if (text == null) text = "";
        if (username == null) username = "unknown";
        if (displayName == null) displayName = "unknown";
        if (email == null) email = "";

        if (text.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "Feedback text is required.");
            return response;
        }

        // Allocate the persistent PT-#### up front so the outbound email and the
        // queue record share the same id.
        String ptId = feedbackQueueService.allocateNextId();

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            Session mailSession = Session.getInstance(props);
            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(adminEmail));
            if (email != null && !email.isEmpty()) {
                message.addRecipient(Message.RecipientType.CC, new InternetAddress(email));
            }
            String subject = "Feedback \u00b7 " + category + " \u00b7 " + ptId + " \u00b7 " + displayName;
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));
            if (email != null && !email.isEmpty()) {
                message.setReplyTo(new javax.mail.Address[]{ new InternetAddress(email) });
            }

            // Build HTML body via EmailTemplateService
            String timestamp = new java.text.SimpleDateFormat("MMM d, yyyy h:mm a").format(new java.util.Date());
            String heroTitle = text.length() > 60 ? text.substring(0, 60) + "..." : text;
            String firstName = displayName.contains(",") ? displayName.split(",")[1].trim()
                    : displayName.split("\\s+")[0];

            // Build mailto with subject pre-filled for context
            String replySubject = "Re: " + subject;
            String mailtoHref = "mailto:" + email + "?subject=" + java.net.URLEncoder.encode(replySubject, "UTF-8");

            String bodyHtml = emailTemplate.callout(null, escHtml(text), null)
                    + emailTemplate.kvBlock(
                        emailTemplate.kvRow("From", displayName + " (" + username + ")"),
                        emailTemplate.kvRow("Email", email),
                        emailTemplate.kvRow("Category", category)
                    );

            // Pre-render the meta strip so it carries the persistent ptId rather
            // than the random id wrap() generates by default.
            String metaStripHtml =
                    "<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\">" +
                    "<tr><td class=\"email-muted\" style=\"font-size:11px;color:#6B7280;font-family:'IBM Plex Mono',Consolas,monospace;\">" +
                    ptId + " &middot; " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) +
                    "</td></tr></table>";

            String body = emailTemplate.wrap(
                    "Support",          // section
                    "Feedback",         // tag
                    category + " \u00b7 " + timestamp, // eyebrow
                    heroTitle,          // heroTitle
                    "From " + displayName, // heroSub
                    null,               // kpiHtml
                    bodyHtml,           // bodyHtml
                    "Reply to " + firstName, // ctaText
                    mailtoHref,         // ctaHref
                    null,               // ctaHint
                    null,               // attachHtml
                    metaStripHtml,      // metaStripHtml — pinned to ptId
                    null                // footerLine
            );

            // PT-38: attach ALL N files to the email (loop). All file sizes are
            // already capped client-side at 25 MB total, which fits under the
            // SanDisk mail relay's inbound limit.
            if (attachments != null && !attachments.isEmpty()) {
                MimeMultipart multipart = new MimeMultipart();
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(body, "text/html; charset=utf-8");
                multipart.addBodyPart(htmlPart);

                for (MultipartFile a : attachments) {
                    if (a == null || a.isEmpty()) continue;
                    MimeBodyPart filePart = new MimeBodyPart();
                    filePart.setDataHandler(new javax.activation.DataHandler(
                            new javax.mail.util.ByteArrayDataSource(a.getBytes(), a.getContentType())));
                    filePart.setFileName(a.getOriginalFilename());
                    multipart.addBodyPart(filePart);
                }
                message.setContent(multipart);
            } else {
                message.setContent(body, "text/html; charset=utf-8");
            }
            Transport.send(message);

            activityLogger.log(username, displayName, "FEEDBACK", category + ": " + text.substring(0, Math.min(100, text.length()))
                + (attachments != null && !attachments.isEmpty() ? " | Attachments: " + attachments.size() : ""));
            logger.info("[FEEDBACK] " + ptId + " " + displayName + " (" + category + "): " + text.substring(0, Math.min(100, text.length())));

            // Persist to queue (after the email goes — admins still get notified
            // even if the queue write hiccups).
            try {
                feedbackQueueService.addItem(ptId, category, text, username, displayName, email, attachments);
            } catch (Exception qx) {
                logger.warning("[FEEDBACK] Queue persistence failed for " + ptId + ": " + qx.getMessage());
            }

            response.put("success", true);
            response.put("ptId", ptId);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed: " + e.getMessage());
        }
        return response;
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
