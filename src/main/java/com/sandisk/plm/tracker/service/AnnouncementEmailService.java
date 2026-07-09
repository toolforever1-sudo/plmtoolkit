package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders and sends Announcement emails (Admin &rarr; Communications).
 *
 * <p>Template: {@code /templates/email/announcement.html} — plain
 * {@code ${var}} replacement, same convention as {@link ImsReviewEmailService}
 * (no Thymeleaf). The stored announcement body keeps a literal
 * {@code ${firstName}} token; {@link #renderEmail} deliberately preserves it so
 * the send loop can personalize per recipient via {@link #personalize}.
 *
 * <p>Every recipient gets an individual message (personalized greeting). The
 * admin-DL Cc rides the FIRST message only — Cc'ing the DL on all N copies
 * would flood it (same choice ImsReviewEmailService made for personalized
 * sends). Subjects go through {@link EmailEnvTag#tag} for the non-prod prefix.
 *
 * <p>Local safety: {@code app.announcements.email-redirect-to} (empty on prod)
 * swaps every recipient for the redirect address and marks the subject, so a
 * dev-box "send to 47 users" can never leak.
 */
@Service
public class AnnouncementEmailService {

    private static final Logger LOG = Logger.getLogger(AnnouncementEmailService.class.getName());

    @Value("${mail.from:PLM-Toolkit@sandisk.com}")
    private String fromAddr;

    @Value("${app.smtp.host:mailrelay.sandisk.com}")
    private String smtpHost;

    @Value("${app.smtp.port:25}")
    private int smtpPort;

    /** Always-Cc DL for real announcement sends (spec: "Cc pdl-plm-admin — always on"). */
    @Value("${app.announcements.cc:PDL-PLM-admin@sandisk.com}")
    private String ccAdminDl;

    /** Non-empty on dev boxes: every announcement email goes here instead of the real audience. */
    @Value("${app.announcements.email-redirect-to:}")
    private String redirectTo;

    // ---- data holders -------------------------------------------------

    /** One resolved audience member. */
    public static class Recipient {
        public String username;
        public String displayName;
        public String email;

        public Recipient() {}

        public Recipient(String username, String displayName, String email) {
            this.username = username;
            this.displayName = displayName;
            this.email = email;
        }
    }

    /** Outcome of a batch send — one failure never aborts the loop. */
    public static class SendResult {
        public int sent;
        public List<Map<String, String>> failures = new ArrayList<>();
    }

    // ---- rendering -----------------------------------------------------

    /**
     * Wraps the stored inner body in the announcement template. The result
     * still contains {@code ${firstName}} — call {@link #personalize} before
     * handing it to a mail client or archive.
     */
    public String renderEmail(String subject, String bodyHtml, String dateLabel) {
        Map<String, String> vars = new HashMap<>();
        vars.put("subject", subject == null ? "" : subject);
        vars.put("bodyHtml", bodyHtml == null ? "" : bodyHtml);
        vars.put("dateLabel", dateLabel == null ? "" : dateLabel);
        try (InputStream in = getClass().getResourceAsStream("/templates/email/announcement.html")) {
            if (in == null) throw new IllegalStateException("Template not found: announcement.html");
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            String tpl = sb.toString();
            for (Map.Entry<String, String> e : vars.entrySet()) {
                tpl = tpl.replace("${" + e.getKey() + "}", e.getValue());
            }
            return tpl; // ${firstName} intentionally left for personalize()
        } catch (Exception ex) {
            throw new RuntimeException("Failed to render announcement template: " + ex.getMessage(), ex);
        }
    }

    /** "Jindal, Vikas" → "Vikas"; "Vikas Jindal" → "Vikas"; blank → "there". Strips "(12345)" ids. */
    public static String firstNameOf(String displayName) {
        if (displayName == null) return "there";
        String s = displayName.replaceAll("\\([^)]*\\)", "").trim();
        if (s.isEmpty()) return "there";
        if (s.contains(",")) {
            String after = s.substring(s.indexOf(',') + 1).trim();
            if (!after.isEmpty()) return after.split("\\s+")[0];
        }
        return s.split("\\s+")[0];
    }

    /** Substitutes {@code ${firstName}} then strips any remaining unresolved {@code ${...}}. */
    public static String personalize(String html, String firstName) {
        if (html == null) return null;
        String name = (firstName == null || firstName.trim().isEmpty()) ? "there" : firstName.trim();
        return html.replace("${firstName}", name)
                   .replaceAll("\\$\\{[a-zA-Z0-9_]+\\}", "");
    }

    // ---- sending -------------------------------------------------------

    /**
     * Real send: one personalized message per recipient; admin-DL Cc on the
     * first message only. Returns per-recipient success/failure.
     */
    public SendResult sendToRecipients(String subject, String renderedHtml, List<Recipient> recipients) {
        SendResult result = new SendResult();
        boolean ccDone = false;
        for (Recipient r : recipients) {
            if (r == null || r.email == null || r.email.trim().isEmpty()) {
                Map<String, String> f = new HashMap<>();
                f.put("username", r == null ? "?" : r.username);
                f.put("email", "");
                f.put("reason", "NO_EMAIL");
                result.failures.add(f);
                continue;
            }
            try {
                String cc = ccDone ? null : ccAdminDl;
                sendOne(subject, personalize(renderedHtml, firstNameOf(r.displayName)), r.email.trim(), cc, false);
                ccDone = true;
                result.sent++;
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "[ANNOUNCE] send failed for " + r.username + ": " + ex.getMessage());
                Map<String, String> f = new HashMap<>();
                f.put("username", r.username);
                f.put("email", r.email);
                f.put("reason", "EMAIL_FAILED: " + ex.getMessage());
                result.failures.add(f);
            }
        }
        return result;
    }

    /** Test send: caller only, "[TEST] " marker, no DL Cc, personalized to the caller. */
    public void sendTest(String subject, String renderedHtml, String email, String displayName) throws Exception {
        sendOne("[TEST] " + subject, personalize(renderedHtml, firstNameOf(displayName)), email, null, true);
    }

    private void sendOne(String subject, String html, String to, String cc, boolean isTest) throws Exception {
        String actualTo = to;
        String actualSubject = subject;
        String actualHtml = html;
        // The redirect applies to test sends too: on a dev box the caller's
        // session email can be a real DL (emergency admin logs in as
        // pdl-plm-admin), and a "test to me" must never fan out to it.
        boolean redirected = redirectTo != null && !redirectTo.trim().isEmpty();
        if (redirected) {
            actualHtml = "<div style=\"background:#fff3cd; border:1px solid #ffeeba; color:#856404; "
                    + "padding:10px 14px; margin:0 0 12px; font-size:13px;\">&#x26A0; <strong>Local test mode</strong> "
                    + "&mdash; would have gone to: <code>" + to + "</code></div>" + html;
            actualTo = redirectTo.trim();
            cc = null;
            if (!actualSubject.contains("[LOCAL TEST]")) actualSubject = actualSubject + " [LOCAL TEST]";
        }
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromAddr));
        msg.setSubject(EmailEnvTag.tag(actualSubject), "UTF-8");
        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(actualTo));
        if (cc != null && !cc.trim().isEmpty() && !cc.equalsIgnoreCase(actualTo)) {
            for (String c : cc.split(",")) {
                if (!c.trim().isEmpty()) msg.addRecipient(Message.RecipientType.CC, new InternetAddress(c.trim()));
            }
        }
        msg.setContent(actualHtml, "text/html; charset=UTF-8");
        Transport.send(msg);
    }
}
