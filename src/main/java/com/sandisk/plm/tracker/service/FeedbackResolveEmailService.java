package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.FeedbackItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Sends the "ready to test" email to a feedback reporter when an admin marks
 * their item done. Honours {@code app.feedback.email.outbound} — when {@code false},
 * the rendered HTML is logged instead of sent (used during local testing so we
 * don't email real users from a dev box).
 */
@Service
public class FeedbackResolveEmailService {

    private static final Logger logger = Logger.getLogger(FeedbackResolveEmailService.class.getName());

    @Value("${mail.smtp.host:mailrelay.sandisk.com}") private String smtpHost;
    @Value("${mail.smtp.port:25}") private int smtpPort;
    @Value("${mail.from:PLM-Toolkit@sandisk.com}") private String fromAddress;
    @Value("${app.admin-email:pdl-plm-admin@sandisk.com}") private String adminEmail;
    @Value("${app.feedback.email.outbound:true}") private boolean outboundEnabled;
    /** Prod toolkit URL surfaced in the "how to test" block. Override per env. */
    @Value("${app.prod.url:http://uls-ep-aglipccb.sandisk.com:8090/}") private String prodUrl;

    /** Crash-recovery tickets are auto-filed by plmadmin and the body always contains
     *  "User: Name (employeeId)" naming the real upload user. Re-route to that user
     *  on the ready-to-test email so the file owner (not plmadmin) gets notified. */
    private static final java.util.regex.Pattern CRASH_USER_RE =
            java.util.regex.Pattern.compile("(?m)^User:\\s+([^()\\n]+?)\\s*\\((\\d+)\\)\\s*$");
    private static final String CRASH_PREFIX = "[Auto-filed crash recovery]";

    /** Total bytes cap for attaching the original upload to an OoM resolve email.
     *  25 MB is the SanDisk mail-relay limit; we use 24 MB to leave room for the
     *  HTML body + MIME overhead. If the attachment(s) exceed this, the email
     *  still sends without the file and notes that fact in the log. */
    private static final long ATTACHMENT_TOTAL_BYTE_CAP = 24L * 1024 * 1024;

    @Autowired private EmailTemplateService emailTemplate;
    @Autowired private LdapAuthService ldapAuthService;
    @Autowired private FeedbackQueueService feedbackQueueService;

    /**
     * @return true if an email was sent (or would have been, with outbound disabled).
     *         false if there was nowhere to send (no reporter email).
     */
    public boolean sendReadyToTest(FeedbackItem item) {
        if (item == null) return false;

        // ---- PT-53: figure out the actual recipient and whether this is a crash-recovery ticket ----
        // Crash-recovery tickets are auto-filed under plmadmin but the upload user we
        // actually want to notify is named in the body ("User: Name (id)"). Re-route
        // to that user; fall back to the reporter on parse miss or LDAP miss.
        boolean isCrashRecovery = item.text != null && item.text.startsWith(CRASH_PREFIX);
        String toEmail = item.reporterEmail;
        String toDisplayName = item.reporterDisplayName;
        String toUsername = item.reporterUsername;

        if (isCrashRecovery) {
            CrashUser cu = extractCrashUser(item.text);
            if (cu != null) {
                LdapAuthService.UserInfo info = safeLookupById(cu.employeeId);
                if (info != null && info.email != null && !info.email.isEmpty()) {
                    toEmail = info.email;
                    toDisplayName = info.displayName != null ? info.displayName : cu.displayName;
                    // Mark sAMAccount as unknown — UserInfo doesn't surface it. We don't
                    // need it: crash users get the non-admin "re-use your file" template
                    // regardless of group membership (no implementation details either way).
                    toUsername = null;
                    logger.info("[FEEDBACK-Q] " + item.ptId + " (crash) re-routed from plmadmin to " + toEmail);
                } else {
                    logger.info("[FEEDBACK-Q] " + item.ptId + " (crash) — LDAP miss on employeeId=" + cu.employeeId
                            + " (name=" + cu.displayName + "); falling back to original reporter.");
                }
            }
        }

        // Standard email-missing fallback (kept for non-crash tickets without a stored email).
        if (toEmail == null || toEmail.isEmpty()) {
            String fallback = lookupEmailViaLdap(toUsername);
            if (fallback != null && !fallback.isEmpty()) {
                logger.info("[FEEDBACK-Q] " + item.ptId + " filled recipient email via LDAP: " + fallback);
                toEmail = fallback;
                if (!isCrashRecovery) {
                    item.reporterEmail = fallback;
                    feedbackQueueService.updateReporterEmailIfMissing(item.ptId, fallback);
                }
            } else {
                logger.info("[FEEDBACK-Q] " + item.ptId + " has no recipient email (LDAP miss) — skipping resolve email.");
                return false;
            }
        }

        // ---- decide template flavor (admin = full detail; non-admin = how-to-test only) ----
        // Crash users always get the OoM-specific "re-use your file" body, regardless of admin status.
        boolean recipientIsAdmin = !isCrashRecovery && isUserInPlmAdmin(toUsername);

        try {
            String timestamp = new SimpleDateFormat("MMM d, yyyy h:mm a").format(new Date());
            String firstName = firstNameOf(toDisplayName);
            String shortSummary = summariseForSubject(item.text, 60);

            // PT-53: subject line now describes the ticket — readable in a crowded inbox.
            String subject = "Feedback \u00b7 " + item.ptId + " \u00b7 " + safe(item.category)
                + (shortSummary.isEmpty() ? "" : " \u00b7 " + shortSummary)
                + " \u2014 ready to test";

            String heroTitle = isCrashRecovery
                    ? "The issue you ran into is fixed"
                    : "Your feedback is ready to test";
            String heroSub = isCrashRecovery
                    ? "Hi " + firstName + ", the upload that failed earlier should work now. Try the same file."
                    : "Hi " + firstName + ", the request you sent has shipped. Please try it out.";

            StringBuilder bodyHtml = new StringBuilder();

            if (isCrashRecovery) {
                // PT-53 #3: minimal OoM body. They already know their flow — just tell
                // them it's fixed and they can re-use the file they had.
                bodyHtml.append(emailTemplate.callout(
                        "What changed",
                        "We pushed a fix for the issue you hit. <strong>Open the toolkit and try the same file you uploaded last time.</strong> "
                            + "If it still doesn't work, reply to this email or file a new feedback ticket from the toolkit.",
                        "ok"));
                bodyHtml.append(renderTestLink());
            } else if (recipientIsAdmin) {
                // Admin recipient: full detail template (the existing one + new prod URL + how-to-test block).
                bodyHtml.append(emailTemplate.callout("Your original request",
                        escHtml(item.text), null));
                if (item.adminNote != null && !item.adminNote.isEmpty()) {
                    bodyHtml.append(emailTemplate.callout("Notes from admin",
                            escHtml(item.adminNote), "ok"));
                }
                if (item.aiEffortHours != null && item.actualHours != null) {
                    bodyHtml.append(renderEffortBlock(item.aiEffortHours, item.actualHours));
                }
                bodyHtml.append(renderHowToTest(item));
                bodyHtml.append(emailTemplate.kvBlock(
                        emailTemplate.kvRow("Submitted", safe(item.submittedAt)),
                        emailTemplate.kvRow("Category", safe(item.category)),
                        emailTemplate.kvRow("Marked done by",
                                item.resolvedByDisplay == null ? safe(item.resolvedBy) : item.resolvedByDisplay)
                ));
            } else {
                // Non-admin recipient on a normal ticket: action-oriented, no implementation
                // detail. They get a short echo of their request + the test walkthrough.
                bodyHtml.append(emailTemplate.callout("Your original request",
                        escHtml(item.text), null));
                bodyHtml.append(renderHowToTest(item));
            }

            String metaStrip =
                    "<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\">" +
                    "<tr><td class=\"email-muted\" style=\"font-size:11px;color:#6B7280;font-family:'IBM Plex Mono',Consolas,monospace;\">" +
                    item.ptId + " &middot; " + timestamp +
                    "</td></tr></table>";

            String body = emailTemplate.wrap(
                    "Support",
                    "Resolved",
                    "READY TO TEST \u00b7 " + item.ptId,
                    heroTitle,
                    heroSub,
                    null,
                    bodyHtml.toString(),
                    null,
                    null,
                    null,
                    null,
                    metaStrip,
                    null);

            // For OoM crash-recovery tickets, attach the original upload(s) so the user
            // can re-try without having to dig the file back up. Skip if total > 25 MB
            // (mail-relay limit). Non-crash tickets never get attachments — they're
            // process emails, not file echoes.
            List<File> attachments = isCrashRecovery ? collectAttachmentsUnderCap(item) : java.util.Collections.emptyList();

            if (!outboundEnabled) {
                logger.info("[FEEDBACK-Q] outbound=false — would have sent to " + toEmail
                        + " (subject=" + subject + ", crash=" + isCrashRecovery + ", admin=" + recipientIsAdmin
                        + ", attachments=" + attachments.size() + "). Body length=" + body.length());
                return true;
            }

            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            Session session = Session.getInstance(props);

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));
            message.addRecipient(Message.RecipientType.CC, new InternetAddress(adminEmail));
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));

            if (attachments.isEmpty()) {
                message.setContent(body, "text/html; charset=utf-8");
            } else {
                MimeMultipart mp = new MimeMultipart("mixed");
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(body, "text/html; charset=utf-8");
                mp.addBodyPart(htmlPart);
                for (File f : attachments) {
                    MimeBodyPart filePart = new MimeBodyPart();
                    filePart.setDataHandler(new DataHandler(new FileDataSource(f)));
                    filePart.setFileName(f.getName());
                    mp.addBodyPart(filePart);
                }
                message.setContent(mp);
            }

            Transport.send(message);
            logger.info("[FEEDBACK-Q] Sent resolve email for " + item.ptId + " to " + toEmail
                    + " (crash=" + isCrashRecovery + ", admin=" + recipientIsAdmin
                    + ", attachments=" + attachments.size() + ")");
            return true;
        } catch (Exception e) {
            logger.warning("[FEEDBACK-Q] Failed to send resolve email for " + item.ptId + ": " + e.getMessage());
            return false;
        }
    }

    // ===================================================================
    //  PT-53 helpers
    // ===================================================================

    private static class CrashUser {
        final String displayName;
        final String employeeId;
        CrashUser(String n, String id) { displayName = n; employeeId = id; }
    }

    /**
     * For an OoM crash-recovery ticket, gather every readable attachment file
     * whose combined size is still under {@link #ATTACHMENT_TOTAL_BYTE_CAP}.
     * Returns an empty list if nothing fits — the email then sends without
     * attachments rather than failing. Honors both the new
     * {@code attachmentPaths} list (PT-38 multi-attach) and the legacy single
     * {@code attachmentPath} field.
     */
    private List<File> collectAttachmentsUnderCap(FeedbackItem item) {
        List<File> out = new ArrayList<>();
        if (item == null) return out;

        List<String> paths = new ArrayList<>();
        if (item.attachmentPaths != null) paths.addAll(item.attachmentPaths);
        if (item.attachmentPath != null && !item.attachmentPath.isEmpty()
                && !paths.contains(item.attachmentPath)) {
            paths.add(item.attachmentPath);
        }
        if (paths.isEmpty()) return out;

        long total = 0;
        for (String p : paths) {
            File f = new File(p);
            if (!f.exists() || !f.isFile() || !f.canRead()) {
                logger.fine("[FEEDBACK-Q] " + item.ptId + " attachment missing or unreadable: " + p);
                continue;
            }
            long sz = f.length();
            if (total + sz > ATTACHMENT_TOTAL_BYTE_CAP) {
                logger.info("[FEEDBACK-Q] " + item.ptId + " attachment " + f.getName()
                        + " (" + sz + " B) would exceed " + ATTACHMENT_TOTAL_BYTE_CAP
                        + " B cap — emailing without it.");
                continue;
            }
            total += sz;
            out.add(f);
        }
        return out;
    }

    /** Pull "User: Jimmy Sessumes (1359)" out of an auto-filed crash-recovery body. */
    private static CrashUser extractCrashUser(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = CRASH_USER_RE.matcher(text);
        if (m.find()) return new CrashUser(m.group(1).trim(), m.group(2).trim());
        return null;
    }

    /** Defensive LDAP lookup — never let an LDAP hiccup take down the resolve email. */
    private LdapAuthService.UserInfo safeLookupById(String empId) {
        try { return ldapAuthService.lookupUserById(empId); }
        catch (Exception e) {
            logger.fine("[FEEDBACK-Q] lookupUserById(" + empId + ") failed: " + e.getMessage());
            return null;
        }
    }

    /** Is the sAMAccount in the pdl-plm-admin DL? Null/blank usernames are non-admin. */
    private boolean isUserInPlmAdmin(String sAMAccount) {
        if (sAMAccount == null || sAMAccount.isEmpty()) return false;
        try {
            java.util.Set<String> admins = ldapAuthService.listAdminUsernames();
            return admins != null && admins.contains(sAMAccount.toLowerCase());
        } catch (Exception e) {
            // Fail closed (non-admin gets the lighter body). Better than rendering admin-only
            // detail to an arbitrary user if LDAP transiently chokes.
            logger.fine("[FEEDBACK-Q] listAdminUsernames() failed: " + e.getMessage());
            return false;
        }
    }

    /** First N chars of the ticket text, single-line, trimmed at a word boundary. */
    private static String summariseForSubject(String text, int max) {
        if (text == null) return "";
        String oneLine = text.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
        if (oneLine.isEmpty()) return "";
        if (oneLine.length() <= max) return "\"" + oneLine + "\"";
        // Cut at a space if possible to avoid mid-word truncation.
        String cut = oneLine.substring(0, max);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > max * 0.6) cut = cut.substring(0, lastSpace);
        return "\"" + cut + "\u2026\"";
    }

    /** Just the prod URL as a tappable link block. Used in the OoM body. */
    private String renderTestLink() {
        return "<div style=\"margin:14px 0; font-size:13px; color:#0F1720;\">"
            + "Open the toolkit: <a href=\"" + escHtml(prodUrl) + "\" style=\"color:#4a6fa5; font-weight:600;\">"
            + escHtml(prodUrl) + "</a>"
            + "</div>";
    }

    /** Generic how-to-test walkthrough. Used for both admin and non-admin non-crash tickets. */
    private String renderHowToTest(FeedbackItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:14px 0; border-left:4px solid #4a6fa5; background:#f4f7fb; padding:10px 14px; border-radius:0 6px 6px 0;\">");
        sb.append("<div style=\"font-size:11px; font-weight:600; letter-spacing:0.5px; text-transform:uppercase; color:#4a6fa5; margin-bottom:6px;\">How to test in prod</div>");
        sb.append("<ol style=\"margin:0; padding-left:20px; font-size:13px; color:#0F1720; line-height:1.6;\">");
        sb.append("<li>Open <a href=\"").append(escHtml(prodUrl)).append("\" style=\"color:#4a6fa5; font-weight:600;\">")
          .append(escHtml(prodUrl)).append("</a> and sign in with your AD credentials.</li>");
        sb.append("<li>Try the change described in <em>Your original request</em> above. ");
        sb.append("If your request named a specific tab or button, head there; otherwise the affected feature should behave the way you described.</li>");
        sb.append("<li>Reply to this email (or file a fresh feedback ticket from the toolkit) if anything's still off.</li>");
        sb.append("</ol>");
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Email the original requestor when AI tags their ticket {@code have_questions}.
     * CCs the PLM admin DL so the admin team sees the same loop and can step in
     * if the requestor goes silent. Same outbound-disabled pattern as the
     * "ready to test" email.
     */
    public boolean sendQuestionsToRequestor(FeedbackItem item) {
        if (item == null) return false;
        if (item.aiQuestions == null || item.aiQuestions.isEmpty()) return false;

        if (item.reporterEmail == null || item.reporterEmail.isEmpty()) {
            String fallback = lookupEmailViaLdap(item.reporterUsername);
            if (fallback != null && !fallback.isEmpty()) {
                item.reporterEmail = fallback;
                feedbackQueueService.updateReporterEmailIfMissing(item.ptId, fallback);
            } else {
                logger.info("[FEEDBACK-Q] " + item.ptId + " has no reporter email — skipping questions email.");
                return false;
            }
        }

        try {
            String firstName = firstNameOf(item.reporterDisplayName);
            int n = item.aiQuestions.size();
            String subject = item.ptId + " \u2014 Claude has " + n + " quick question" + (n == 1 ? "" : "s")
                + " before we can scope this";

            String heroTitle = "We need a bit more detail on " + item.ptId;
            String heroSub = "Hi " + firstName + ", the feedback you submitted is in triage. The AI flagged a few specifics it needs before we can estimate.";

            StringBuilder qHtml = new StringBuilder();
            for (int i = 0; i < item.aiQuestions.size(); i++) {
                qHtml.append("<p style=\"margin:6px 0;font-size:13px;\"><strong>")
                     .append(i + 1).append(".</strong> ")
                     .append(escHtml(item.aiQuestions.get(i)))
                     .append("</p>");
            }

            String bodyHtml = emailTemplate.callout("Your original request",
                    escHtml(item.text), null);
            if (item.aiAssessment != null && !item.aiAssessment.isEmpty()) {
                bodyHtml += emailTemplate.callout("Why these questions",
                        escHtml(item.aiAssessment), null);
            }
            bodyHtml += "<div style=\"margin:14px 0 6px;font-size:12px;color:#6B7280;letter-spacing:0.5px;text-transform:uppercase;border-bottom:1px solid #E8E6DF;padding-bottom:6px;\">Questions</div>";
            bodyHtml += qHtml.toString();
            bodyHtml += "<div style=\"margin-top:14px;font-size:13px;color:#0F1720;line-height:1.6;\">"
                + "Answer in the toolkit: open the <strong>My Feedback</strong> drawer (top-right account menu), "
                + "find " + item.ptId + ", click the blue <strong>Have Questions</strong> pill, type your "
                + "answers, and hit Send. Once you do, the AI re-triages on the next poll cycle and we can "
                + "scope the work."
                + "</div>";

            String metaStrip =
                    "<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\">" +
                    "<tr><td class=\"email-muted\" style=\"font-size:11px;color:#6B7280;font-family:'IBM Plex Mono',Consolas,monospace;\">" +
                    item.ptId + " &middot; tagged have_questions &middot; " +
                    new SimpleDateFormat("MMM d, yyyy h:mm a").format(new Date()) +
                    "</td></tr></table>";

            String body = emailTemplate.wrap(
                    "Support",
                    "In triage",
                    "QUESTIONS \u00b7 " + item.ptId,
                    heroTitle,
                    heroSub,
                    null,
                    bodyHtml,
                    null, null, null, null,
                    metaStrip,
                    null);

            if (!outboundEnabled) {
                logger.info("[FEEDBACK-Q] outbound=false — would have sent questions email to "
                    + item.reporterEmail + " (subject=" + subject + ").");
                return true;
            }

            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            Session session = Session.getInstance(props);

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(item.reporterEmail));
            message.addRecipient(Message.RecipientType.CC, new InternetAddress(adminEmail));
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));
            message.setContent(body, "text/html; charset=utf-8");

            Transport.send(message);
            logger.info("[FEEDBACK-Q] Sent questions email for " + item.ptId + " to " + item.reporterEmail
                + " (CC " + adminEmail + ")");
            return true;
        } catch (Exception e) {
            logger.warning("[FEEDBACK-Q] Failed to send questions email for " + item.ptId + ": " + e.getMessage());
            return false;
        }
    }

    private String lookupEmailViaLdap(String username) {
        if (username == null || username.isEmpty()) return null;
        try {
            LdapAuthService.UserInfo info = ldapAuthService.lookupUserByUsername(username);
            if (info != null && info.email != null && !info.email.isEmpty()) return info.email;
        } catch (Exception ex) {
            logger.fine("[FEEDBACK-Q] LDAP lookup failed for " + username + ": " + ex.getMessage());
        }
        return null;
    }

    private String firstNameOf(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "there";
        if (displayName.contains(",")) return displayName.split(",")[1].trim();
        return displayName.split("\\s+")[0];
    }

    private String safe(String s) { return s == null ? "" : s; }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Render a small "Estimated vs Actual" callout block. Used in the ready-to-test
     * email so the requestor (and the admin CC) can see how the AI's effort
     * estimate held up against real elapsed agent time. Colors:
     *   green  - actual ≤ estimate (we hit or beat it)
     *   amber  - actual ≤ 1.5× estimate (close, within tolerance)
     *   red    - actual >  1.5× estimate (we blew the estimate)
     */
    private String renderEffortBlock(Double estimatedHours, Double actualHours) {
        if (estimatedHours == null || actualHours == null) return "";
        double est = estimatedHours;
        double act = actualHours;
        String estStr = formatHours(est);
        String actStr = formatHours(act);
        double delta = act - est;
        String deltaStr = (delta >= 0 ? "+" : "") + formatHours(Math.abs(delta)) + (delta >= 0 ? " over" : " under");
        String pctStr;
        if (est > 0) {
            int pct = (int) Math.round((act / est) * 100.0);
            pctStr = pct + "% of estimate";
        } else {
            pctStr = "—";
        }
        String borderColor;
        String bgColor;
        String label;
        if (act <= est) {
            borderColor = "#1F8A4C"; bgColor = "#e8f5e9"; label = "Beat the estimate";
        } else if (act <= est * 1.5) {
            borderColor = "#C7801B"; bgColor = "#fff8e1"; label = "Within tolerance";
        } else {
            borderColor = "#B8342B"; bgColor = "#fdeaea"; label = "Over the estimate";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:14px 0; border-left:4px solid ").append(borderColor)
          .append("; background:").append(bgColor)
          .append("; padding:10px 14px; border-radius:0 6px 6px 0;\">");
        sb.append("<div style=\"font-size:11px; font-weight:600; letter-spacing:0.5px; text-transform:uppercase; color:")
          .append(borderColor).append("; margin-bottom:6px;\">Estimated vs actual &middot; ")
          .append(escHtml(label)).append("</div>");
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" role=\"presentation\" style=\"font-size:13px; color:#0F1720;\">");
        sb.append("<tr><td style=\"padding:2px 16px 2px 0; color:#6B7280;\">AI estimate at triage</td>")
          .append("<td style=\"padding:2px 0; font-weight:600;\">~").append(escHtml(estStr)).append("</td></tr>");
        sb.append("<tr><td style=\"padding:2px 16px 2px 0; color:#6B7280;\">Actual agent time</td>")
          .append("<td style=\"padding:2px 0; font-weight:600;\">~").append(escHtml(actStr)).append("</td></tr>");
        sb.append("<tr><td style=\"padding:2px 16px 2px 0; color:#6B7280;\">Variance</td>")
          .append("<td style=\"padding:2px 0;\">").append(escHtml(deltaStr)).append(" &middot; ")
          .append(escHtml(pctStr)).append("</td></tr>");
        sb.append("</table>");
        sb.append("<div style=\"font-size:11px; color:#6B7280; margin-top:6px;\">Actual is measured from when the agent's poll cycle picked the ticket up to when the JAR was built.</div>");
        sb.append("</div>");
        return sb.toString();
    }

    private String formatHours(double h) {
        if (h < 1.0) {
            int mins = (int) Math.round(h * 60.0);
            return mins + " min";
        }
        if (h < 10.0) {
            return String.format(java.util.Locale.US, "%.1f", h) + " hr";
        }
        return Math.round(h) + " hr";
    }
}
