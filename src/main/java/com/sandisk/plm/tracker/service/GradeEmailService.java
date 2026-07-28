package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Sends the two Backlog-grade notifications and returns the {@link GradeModels.OutboxEntry}
 * it built so the controller can persist it even when SMTP throws (the audit
 * trail must record what we attempted to send). Recipients are config-driven
 * ({@code plm.grade.notify.admin} / {@code plm.grade.notify.it}), never hard-coded.
 * SMTP follows the same unauthenticated relay pattern as {@link MeetingEmailService}.
 *
 * <p>Bodies contain only the actor's AD name, the ECN/rule, the (user-authored)
 * rationale, the verification method, the AI decision, and the grade delta — no
 * credentials or secrets (per the project's never-share-credentials rule).
 */
@Service
public class GradeEmailService {

    private static final Logger LOG = Logger.getLogger(GradeEmailService.class.getName());

    @Value("${mail.smtp.host:mailrelay.sandisk.com}") private String smtpHost;
    @Value("${mail.smtp.port:25}")                     private int smtpPort;
    @Value("${mail.from:PLM-Toolkit@sandisk.com}")     private String mailFrom;
    @Value("${plm.grade.notify.admin:pdl-plm-admin@sandisk.com}") private String notifyAdmin;
    @Value("${plm.grade.notify.it:pdl-plm-it@sandisk.com}")       private String notifyIt;

    public String adminRecipient() { return notifyAdmin; }
    public String itRecipient() { return notifyIt; }

    /** Accepted item action → admin. Returns the (sent or attempted) outbox entry. */
    public GradeModels.OutboxEntry sendAdminAccept(GradeModels.GradeRecord r) {
        String by = nz(r.actorName, r.actorAd, "A reviewer");
        boolean include = "include".equals(r.action);
        String subject = "Backlog re-graded " + r.gradeBefore + " → " + r.gradeAfter
                + (include ? " — item re-included (" : " — dispute accepted (") + ecnShort(r.targetEcn) + ")";
        List<String> body = new ArrayList<>();
        body.add(include
                ? by + " put " + ecnShort(r.targetEcn) + " back into the at-risk set."
                : by + " disputed " + ecnShort(r.targetEcn) + " and the AI accepted it.");
        body.add("Rationale: \"" + nz(r.userReason, "") + "\"");
        if (!include) { String how = howVerified(r); if (how != null) body.add(how); }
        if (r.aiReason != null && !r.aiReason.isEmpty()) body.add("AI decision: " + r.aiReason);
        body.add("Grade: " + r.gradeBefore + " (" + r.scoreBefore + ") → " + r.gradeAfter + " (" + r.scoreAfter + ").");
        return build(notifyAdmin, subject, body, false);
    }

    /** Runtime logic change → IT team. Returns the (sent or attempted) outbox entry. */
    public GradeModels.OutboxEntry sendItLogicChange(GradeModels.GradeRecord r) {
        String by = nz(r.actorName, r.actorAd, "A reviewer");
        String subject = "Grading logic changed at run time — " + nz(r.summary, "");
        List<String> body = new ArrayList<>();
        body.add(by + " changed a grading rule at run time.");
        body.add("Change: " + nz(r.summary, "") + " (" + nz(r.detail, "") + ").");
        body.add("Rationale: \"" + nz(r.userReason, "") + "\"");
        String how = howVerified(r);
        if (how != null) body.add(how);
        body.add("AI decision: " + nz(r.aiReason, ""));
        body.add("Grade: " + r.gradeBefore + " (" + r.scoreBefore + ") → " + r.gradeAfter + " (" + r.scoreAfter + ").");
        body.add("This rule now applies to every future grade until reverted.");
        return build(notifyIt, subject, body, true);
    }

    /** Accepted standing theme policy → IT team. */
    public GradeModels.OutboxEntry sendItPolicy(GradeModels.ThemePolicy pol, int matchedCount) {
        String by = nz(pol.actorName, pol.actorAd, "A reviewer");
        String subject = "Grading policy added — " + nz(pol.predicate, "theme exclusion") + " (" + matchedCount + " ECN" + (matchedCount == 1 ? "" : "s") + ")";
        List<String> body = new ArrayList<>();
        body.add(by + " added a standing grading policy.");
        body.add("Policy: " + nz(pol.predicate, ""));
        body.add("Rationale (theirs): \"" + nz(pol.userReason, "") + "\"");
        if (pol.aiRationale != null && !pol.aiRationale.isEmpty()) body.add("AI decision: " + pol.aiRationale);
        body.add(matchedCount + " ECN" + (matchedCount == 1 ? " is" : "s are") + " no longer being considered for grading.");
        body.add("This policy re-applies to every future grade until it is reverted.");
        return build(notifyIt, subject, body, true);
    }

    private String howVerified(GradeModels.GradeRecord r) {
        if (r.attachmentRef != null && !r.attachmentRef.isEmpty()) return "Attached proof on file (archive ref " + r.attachmentRef + ").";
        if ("agile_record".equals(r.verifiedBy)) {
            return "Verified against Agile ECN record — Description" + (r.fieldQuote != null && !r.fieldQuote.isEmpty() ? (": \"" + r.fieldQuote + "\"") : "") + ".";
        }
        return null;
    }

    private GradeModels.OutboxEntry build(String to, String subject, List<String> body, boolean it) {
        GradeModels.OutboxEntry e = new GradeModels.OutboxEntry();
        e.id = "ob_" + System.currentTimeMillis() + "_" + Integer.toHexString((subject + to).hashCode());
        e.to = to;
        e.subject = subject;
        e.body = body;
        e.sentAt = System.currentTimeMillis();
        e.it = it;
        sendHtml(to, subject, htmlOf(subject, body));
        return e;
    }

    /** Best-effort send; failures are logged but never thrown — the outbox entry
     *  is still persisted so History shows what was attempted. */
    private void sendHtml(String to, String subject, String html) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", "false");
            Session session = Session.getInstance(props);
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(mailFrom));
            msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            msg.setSubject(EmailEnvTag.tag(subject));
            msg.setContent(html, "text/html; charset=utf-8");
            Transport.send(msg);
        } catch (Exception e) {
            LOG.warning("[GRADE-MAIL] send to " + to + " failed: " + e.getMessage());
        }
    }

    private static String htmlOf(String subject, List<String> body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720;max-width:600px\">");
        sb.append("<div style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:18px;font-weight:600;margin-bottom:10px\">")
          .append(esc(subject)).append("</div>");
        for (String line : body) {
            sb.append("<p style=\"font-size:13px;line-height:1.6;margin:6px 0\">").append(esc(line)).append("</p>");
        }
        sb.append("<p style=\"font-size:11px;color:#6B7280;margin-top:16px;border-top:1px solid #E8E6DF;padding-top:8px\">")
          .append("This is an automated notification from the PLM Toolkit. Please do not reply to this email.</p></div>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String ecnShort(String ecn) { return ecn == null ? "" : ecn.replace("-PROJ", ""); }

    private static String nz(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }
}
