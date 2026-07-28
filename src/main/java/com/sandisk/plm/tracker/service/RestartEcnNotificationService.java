package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn;
import com.sandisk.plm.tracker.service.RestartEcnHistoryStore.FileEntry;
import com.sandisk.plm.tracker.service.RestartEcnHistoryStore.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Restart ECN notification emails (§5) — Created + Updated, sent to the config
 * notify list (pdl-plm-admin). Raw javax.mail like ItEnhancementsNotificationService.
 * Every send is fail-soft: a dead SMTP relay must never fail the Agile write.
 */
@Service
public class RestartEcnNotificationService {

    private static final Logger LOG = Logger.getLogger(RestartEcnNotificationService.class.getName());

    @Value("${mail.smtp.host:mailrelay.sandisk.com}") private String smtpHost;
    @Value("${mail.smtp.port:25}")                     private int smtpPort;
    @Value("${mail.from:PLM-Toolkit@sandisk.com}")     private String mailFrom;
    @Value("${restartEcn.notifyList:pdl-plm-admin@sandisk.com}") private String notifyList;
    @Value("${app.ims-review.toolkit-base-url:http://uls-ep-aglipccb:8090}") private String toolkitBaseUrl;

    /** true if the email went out. Never throws. */
    public boolean sendCreated(Record rec) {
        if (rec == null) return false;
        try {
            String subject = "[PLM Toolkit] Restart ECN " + rec.ecn + " created — deployment " + mdy(rec.deployDate);
            return send(subject, buildCreatedHtml(rec));
        } catch (Exception e) {
            LOG.warning("[RESTART-NOTIFY] created " + (rec == null ? "?" : rec.ecn) + " failed: " + e.getMessage());
            return false;
        }
    }

    public boolean sendUpdated(Record rec, String whatChanged, String byDisplay,
                               Set<String> addedEcns, Set<String> addedFiles) {
        if (rec == null) return false;
        try {
            String subject = "[PLM Toolkit] " + rec.ecn + " updated — " + whatChanged
                    + (byDisplay == null || byDisplay.isEmpty() ? "" : " by " + byDisplay);
            return send(subject, buildUpdatedHtml(rec, whatChanged, addedEcns, addedFiles));
        } catch (Exception e) {
            LOG.warning("[RESTART-NOTIFY] updated " + rec.ecn + " failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    private boolean send(String subject, String html) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", "false");
            Session session = Session.getInstance(props);
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(mailFrom));
            for (String addr : notifyList.split("[,;]")) {
                String a = addr.trim();
                if (!a.isEmpty()) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(a));
            }
            msg.setSubject(EmailEnvTag.tag(subject));
            msg.setContent(html, "text/html; charset=utf-8");
            Transport.send(msg);
            LOG.info("[RESTART-NOTIFY] sent: " + subject);
            return true;
        } catch (Exception e) {
            LOG.warning("[RESTART-NOTIFY] SMTP send failed: " + e.getMessage());
            return false;
        }
    }

    private String deepLink(String ecn) {
        String base = toolkitBaseUrl == null ? "" : toolkitBaseUrl.replaceAll("/+$", "");
        return base + "/#it-enhancements/restart/" + ecn;
    }

    static String mdy(String iso) {
        if (iso == null) return "";
        try { return LocalDate.parse(iso.trim()).format(DateTimeFormatter.ofPattern("MM/dd/yy")); }
        catch (Exception e) { return iso; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- shared chrome (Email Design Guidelines: 600px card, IBM Plex, SanDisk palette) ----
    private String head(String eyebrow, String title, String badgeHtml) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"light dark\"></head>"
            + "<body style=\"margin:0;padding:24px 0;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#fff;border:1px solid #E8E6DF;border-radius:8px;overflow:hidden;\">"
            + "<tr><td style=\"padding:16px 28px 0;\"><span style=\"font-size:12px;color:#6B7280;\">Agile PLM / Restart ECN</span>" + badgeHtml + "</td></tr>"
            + "<tr><td style=\"padding:12px 28px 4px;\"><div style=\"font-size:11px;text-transform:uppercase;letter-spacing:1px;color:#6B7280;\">" + eyebrow + "</div>"
            + "<div style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:700;color:#0F1720;\">" + esc(title) + "</div></td></tr>";
    }

    private String kvRow(String k, String v) {
        return "<tr><td style=\"padding:3px 0;font-weight:600;color:#6B7280;width:35%;white-space:nowrap;\">" + esc(k)
            + "</td><td style=\"padding:3px 0;color:#0F1720;word-break:break-word;\">" + v + "</td></tr>";
    }

    private String bundledTable(List<BundledEcn> bundled, Set<String> added) {
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:12px;margin-top:6px;border-collapse:collapse;\">");
        sb.append("<tr><th align=\"left\" style=\"background:#2c3e50;color:#fff;padding:5px 8px;\">ECN</th>")
          .append("<th align=\"left\" style=\"background:#2c3e50;color:#fff;padding:5px 8px;\">Proposal</th>")
          .append("<th align=\"left\" style=\"background:#2c3e50;color:#fff;padding:5px 8px;\">IT Owner</th></tr>");
        int i = 0;
        for (BundledEcn b : bundled) {
            String bg = (i++ % 2 == 0) ? "#FAFAF7" : "#ffffff";
            String chip = (added != null && added.contains(b.ecn)) ? " <span style=\"background:#e8f5e9;color:#1F8A4C;font-size:10px;padding:1px 6px;border-radius:999px;\">ADDED</span>" : "";
            sb.append("<tr><td style=\"padding:5px 8px;border-bottom:1px solid #cccccc;background:").append(bg).append(";color:#4a6fa5;font-weight:600;\">").append(esc(b.ecn)).append(chip).append("</td>")
              .append("<td style=\"padding:5px 8px;border-bottom:1px solid #cccccc;background:").append(bg).append(";\">").append(esc(b.proposal == null ? "" : (b.proposal.length() > 70 ? b.proposal.substring(0, 70) + "…" : b.proposal))).append("</td>")
              .append("<td style=\"padding:5px 8px;border-bottom:1px solid #cccccc;background:").append(bg).append(";\">").append(esc(b.itOwner)).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String filesTable(List<FileEntry> files, Set<String> added) {
        if (files == null || files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<div style=\"font-size:12px;font-weight:600;color:#6B7280;margin-top:12px;border-bottom:1px solid #E8E6DF;padding-bottom:4px;\">Attachments</div>");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:12px;margin-top:6px;border-collapse:collapse;\">");
        int i = 0;
        for (FileEntry f : files) {
            String bg = (i++ % 2 == 0) ? "#FAFAF7" : "#ffffff";
            String chip = (added != null && added.contains(f.name)) ? " <span style=\"background:#e8f5e9;color:#1F8A4C;font-size:10px;padding:1px 6px;border-radius:999px;\">ADDED</span>" : "";
            sb.append("<tr><td style=\"padding:5px 8px;border-bottom:1px solid #cccccc;background:").append(bg).append(";\">").append(esc(f.name)).append(chip).append("</td>")
              .append("<td style=\"padding:5px 8px;border-bottom:1px solid #cccccc;background:").append(bg).append(";color:#6B7280;\">").append(esc(String.join(", ", f.ecns == null ? java.util.Collections.<String>emptyList() : f.ecns))).append("</td>")
              .append("<td style=\"padding:5px 8px;border-bottom:1px solid #cccccc;background:").append(bg).append(";color:#6B7280;\">").append(esc(f.type)).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String cta(String ecn) {
        return "<tr><td style=\"padding:16px 28px 4px;\"><a href=\"" + deepLink(ecn) + "\" style=\"display:inline-block;background:#2c3e50;color:#fff;text-decoration:none;font-size:13px;padding:9px 18px;border-radius:6px;\">Open Restart ECN &rarr;</a></td></tr>";
    }

    private String foot() {
        return "<tr><td style=\"padding:16px 28px;background:#FAFAF7;border-top:1px solid #E8E6DF;\">"
            + "<span style=\"border:1px solid #ececec;border-radius:20px;padding:2px 12px;font-size:11px;color:#6B7280;\">sandisk</span>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:6px;\">PLM Toolkit — Restart ECN. This is an automated notification. Please do not reply.</div></td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private String buildCreatedHtml(Record rec) {
        String badge = "<span style=\"float:right;background:#fff3cd;color:#856404;font-size:11px;padding:2px 10px;border-radius:12px;\">Created &middot; Pending</span>";
        StringBuilder sb = new StringBuilder();
        sb.append(head("Restart ECN created", rec.ecn, badge));
        sb.append("<tr><td style=\"padding:14px 28px 0;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:13px;\">");
        sb.append(kvRow("Deployment", esc(mdy(rec.deployDate))));
        sb.append(kvRow("IT Owner", esc(rec.itOwnerName != null && !rec.itOwnerName.isEmpty() ? rec.itOwnerName : rec.itOwnerLogin)));
        sb.append(kvRow("Created by", esc(rec.createdBy)));
        sb.append(kvRow("Classification", "Restart Agile Service"));
        sb.append("</table></td></tr>");
        sb.append("<tr><td style=\"padding:14px 28px 0;\"><div style=\"font-size:12px;font-weight:600;color:#6B7280;border-bottom:1px solid #E8E6DF;padding-bottom:4px;\">Bundled ECNs</div>")
          .append(bundledTable(rec.bundled, null)).append(filesTable(rec.files, null)).append("</td></tr>");
        sb.append(cta(rec.ecn));
        sb.append("<tr><td style=\"padding:12px 28px 0;\"><div style=\"background:#e8f0fe;border-left:4px solid #4a6fa5;border-radius:0 6px 6px 0;padding:10px 14px;font-size:12px;color:#0F1720;\">"
            + "Anyone on this list can add ECNs or files from the Restart history drawer — every addition emails this list. "
            + "The ECN stays in <b>Pending</b> until someone submits it in Agile when the bundle is final.</div></td></tr>");
        sb.append(foot());
        return sb.toString();
    }

    private String buildUpdatedHtml(Record rec, String whatChanged, Set<String> addedEcns, Set<String> addedFiles) {
        String badge = "<span style=\"float:right;background:#e8f5e9;color:#155724;font-size:11px;padding:2px 10px;border-radius:12px;\">Updated</span>";
        StringBuilder sb = new StringBuilder();
        sb.append(head("Restart ECN updated", rec.ecn, badge));
        sb.append("<tr><td style=\"padding:12px 28px 0;\"><div style=\"background:#e8f5e9;border-left:4px solid #1F8A4C;border-radius:0 6px 6px 0;padding:10px 14px;font-size:13px;color:#0F1720;\">"
            + "<b>What changed:</b> " + esc(whatChanged) + "</div></td></tr>");
        sb.append("<tr><td style=\"padding:14px 28px 0;\"><div style=\"font-size:12px;font-weight:600;color:#6B7280;border-bottom:1px solid #E8E6DF;padding-bottom:4px;\">Bundle now &middot; "
            + rec.bundled.size() + " ECN" + (rec.bundled.size() == 1 ? "" : "s") + " &middot; " + rec.files.size() + " file" + (rec.files.size() == 1 ? "" : "s") + "</div>")
          .append(bundledTable(rec.bundled, addedEcns)).append(filesTable(rec.files, addedFiles)).append("</td></tr>");
        sb.append(cta(rec.ecn));
        sb.append(foot());
        return sb.toString();
    }
}
