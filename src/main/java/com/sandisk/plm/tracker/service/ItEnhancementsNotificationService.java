package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Fires per-ECN notification emails after a Meeting Mode save batch lands in
 * Agile. One email per ECN goes to the row's requestor + IT owner, with
 * {@code pdl-plm-admin@sandisk.com} on cc. A separate admin-only failure
 * email is sent when the save itself failed for one or more cells.
 *
 * <p>Recipient resolution is via {@link LdapAuthService#lookupUserById} on
 * the loginid carried by the row (4-hour cache, so repeated meetings don't
 * hammer LDAP). If a loginid resolves to no email, that recipient is
 * silently dropped from the To: list (the failure email to admin gets a
 * note about which lookups failed).
 *
 * <p>Mail design follows the project email guidelines (SanDisk palette,
 * IBM Plex fonts, dark-mode meta, sandisk pill in the footer). Plain HTML
 * because the bodies are short and table-driven.
 */
@Service
public class ItEnhancementsNotificationService {

    private static final Logger LOG = Logger.getLogger(ItEnhancementsNotificationService.class.getName());

    private static final String ADMIN_CC = "pdl-plm-admin@sandisk.com";
    // Agile webclient base — env-driven (https://plmqa.sandisk.com/Agile on QA).
    @Value("${app.ims-review.agile-webclient-url:https://plm.sandisk.com/Agile}")
    private String agileWebclientUrl;

    @Value("${mail.smtp.host:mailrelay.sandisk.com}") private String smtpHost;
    @Value("${mail.smtp.port:25}")                     private int smtpPort;
    @Value("${mail.from:PLM-Toolkit@sandisk.com}") private String mailFrom;

    @Autowired private LdapAuthService ldapAuthService;

    /** Per-recipient-set summary outcome. The grouping key is the *set* of
     *  recipients on an ECN (Requestor + IT Owner, deduped + sorted), not
     *  any single person. So Krati's feedback (2026-06-16) is honored:
     *  if Vikas Singh is requestor and Afroz is IT owner on ECN-X, they
     *  both go on the same email's To: line — one email, not two.
     *  Multiple ECNs that share the same (requestor, owner) pair group
     *  into one summary. */
    public static class NotifyResult {
        /** Comma-joined list of all To: addresses (lowest-cased for the key
         *  but original case preserved here). */
        public String recipientEmail;
        /** "Last, First; Last, First" for the display names on the To: line. */
        public String recipientName;
        public List<String> ecns = new ArrayList<>();
        public boolean sent;
        public String error;
    }

    /** A group of edits that share the same primary (To:) recipient set.
     *  Each email has a primary set (To) and a secondary set (Cc + admin).
     *  Per Krati's 2026-06-16 feedback: when the editor is on the IT team
     *  (PDL-PLM-admin), the IT Owner is demoted to Cc and the Requestor is
     *  the primary; otherwise the opposite. */
    private static class RecipientGroup {
        /** Lower-cased primary key → original-case email. Insertion order
         *  preserved for stable To: ordering. */
        final java.util.LinkedHashMap<String, String> toAddresses = new java.util.LinkedHashMap<>();
        /** Lower-cased secondary key → original-case email. Union across
         *  every ECN in the group; appears on Cc alongside the admin DL. */
        final java.util.LinkedHashMap<String, String> ccAddresses = new java.util.LinkedHashMap<>();
        /** First name of the first primary recipient — drives the greeting. */
        String greetingFirstName;
        final List<Map<String, Object>> editsInGroup = new ArrayList<>();
        /** Parallel to editsInGroup — pre-rendered role line per ECN. */
        final List<String> roleLineForEcn = new ArrayList<>();
    }

    /** Backwards-compatible overload — calls the new signature with
     *  {@code editorIsItTeam = false} (treats the editor as a non-IT
     *  Requestor, the legacy behavior). */
    public List<NotifyResult> sendChangeNotifications(List<Map<String, Object>> edits, String editorDisplayName) {
        return sendChangeNotifications(edits, editorDisplayName, false);
    }

    /** Group every {@code edit} by the primary (To:) recipient set and send
     *  one summary email per group.
     *
     *  <p>Primary vs. Cc split is driven by {@code editorIsItTeam}:
     *  <ul>
     *    <li><b>Editor is IT team</b> (toolkit isPlmAdmin = PDL-PLM-admin DL):
     *        Requestor goes in To, IT Owner moves to Cc. The IT side has
     *        effectively been notified by one of their own making the
     *        change; the Requestor is the party who needs to take note.</li>
     *    <li><b>Editor is not IT team</b> (e.g. a business Requestor with a
     *        per-user grant): IT Owner goes in To, Requestor moves to Cc.</li>
     *  </ul>
     *  Admin DL ({@code pdl-plm-admin@sandisk.com}) is always on Cc. */
    public List<NotifyResult> sendChangeNotifications(List<Map<String, Object>> edits,
                                                       String editorDisplayName,
                                                       boolean editorIsItTeam) {
        List<NotifyResult> outcomes = new ArrayList<>();
        if (edits == null || edits.isEmpty()) return outcomes;

        java.util.LinkedHashMap<String, RecipientGroup> bySetKey = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> unresolved = new java.util.LinkedHashMap<>();

        for (Map<String, Object> edit : edits) {
            String reqLoginId = (String) edit.get("requestorLoginId");
            String reqName = (String) edit.getOrDefault("requestorName", "");
            String ownerLoginId = (String) edit.get("itOwnerLoginId");
            String ownerName = (String) edit.getOrDefault("itOwnerName", "");
            ResolvedUser reqUser = resolveOrFlag(reqLoginId, reqName, unresolved);
            ResolvedUser ownerUser = resolveOrFlag(ownerLoginId, ownerName, unresolved);

            String reqDisplay = reqUser == null ? null : reqUser.displayName;
            String ownerDisplay = ownerUser == null ? null : ownerUser.displayName;

            // Pick primary vs. secondary based on editor team membership.
            // When the same person holds both roles, just put them in To
            // (skip the Cc dedup; admin still gets Cc'd).
            ResolvedUser primary;
            ResolvedUser secondary;
            if (editorIsItTeam) {
                primary = reqUser != null ? reqUser : ownerUser;
                secondary = (reqUser != null && ownerUser != null
                             && !reqUser.email.equalsIgnoreCase(ownerUser.email))
                             ? ownerUser : null;
            } else {
                primary = ownerUser != null ? ownerUser : reqUser;
                secondary = (reqUser != null && ownerUser != null
                             && !reqUser.email.equalsIgnoreCase(ownerUser.email))
                             ? reqUser : null;
            }
            if (primary == null) continue;  // nothing to notify on

            // Group key = primary email only (Cc'd people don't affect
            // grouping — they're a union). Multiple ECNs with the same
            // primary collapse into one summary email.
            String groupKey = primary.email.toLowerCase();

            RecipientGroup group = bySetKey.computeIfAbsent(groupKey, k -> {
                RecipientGroup g = new RecipientGroup();
                g.toAddresses.put(primary.email.toLowerCase(), primary.email);
                String firstDisplay = primary.displayName;
                if (firstDisplay != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[^,]+,\\s*([^\\s,]+)").matcher(firstDisplay.trim());
                    if (m.find()) g.greetingFirstName = m.group(1);
                }
                return g;
            });
            // Accumulate the per-ECN secondary onto the group's Cc union.
            if (secondary != null) {
                group.ccAddresses.putIfAbsent(secondary.email.toLowerCase(), secondary.email);
            }

            // Per-ECN role line — surfaces both roles when distinct people,
            // collapsed when same person. The line describes the ECN's
            // assigned roles, not To/Cc — the actual delivery split is
            // visible in the email header.
            String roleLine;
            if (reqUser != null && ownerUser != null
                    && reqUser.email.equalsIgnoreCase(ownerUser.email)) {
                roleLine = "Requestor & IT Owner: <strong>" + escHtml(reqDisplay) + "</strong>";
            } else if (reqUser != null && ownerUser != null) {
                roleLine = "Requestor: <strong>" + escHtml(reqDisplay)
                        + "</strong> &middot; IT Owner: <strong>" + escHtml(ownerDisplay) + "</strong>";
            } else if (reqUser != null) {
                roleLine = "Requestor: <strong>" + escHtml(reqDisplay)
                        + "</strong> &middot; IT Owner: <em>" + escHtml(ownerName == null || ownerName.isEmpty() ? "(unresolved)" : ownerName) + "</em>";
            } else {
                roleLine = "IT Owner: <strong>" + escHtml(ownerDisplay)
                        + "</strong> &middot; Requestor: <em>" + escHtml(reqName == null || reqName.isEmpty() ? "(unresolved)" : reqName) + "</em>";
            }
            group.editsInGroup.add(edit);
            group.roleLineForEcn.add(roleLine);
        }

        for (Map.Entry<String, String> u : unresolved.entrySet()) {
            NotifyResult miss = new NotifyResult();
            miss.recipientEmail = "";
            miss.recipientName = u.getValue();
            miss.sent = false;
            miss.error = "loginid " + u.getKey() + " could not be resolved to an AD email";
            outcomes.add(miss);
        }

        for (RecipientGroup group : bySetKey.values()) {
            NotifyResult res = new NotifyResult();
            res.recipientEmail = String.join(", ", group.toAddresses.values());
            res.recipientName = "";  // names are inside the email body
            for (Map<String, Object> e : group.editsInGroup) {
                Object ecn = e.get("ecn");
                if (ecn != null) res.ecns.add(String.valueOf(ecn));
            }
            try {
                int n = group.editsInGroup.size();
                String subject = "PLM IT Enhancements — " + n + " ECN" + (n == 1 ? "" : "s")
                        + " updated"
                        + (editorDisplayName == null || editorDisplayName.isEmpty() ? "" : " by " + editorDisplayName);
                String html = renderSummaryHtml(group, editorDisplayName);
                Properties props = new Properties();
                props.put("mail.smtp.host", smtpHost);
                props.put("mail.smtp.port", String.valueOf(smtpPort));
                props.put("mail.smtp.auth", "false");
                Session session = Session.getInstance(props);
                MimeMessage msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(mailFrom));
                for (String addr : group.toAddresses.values()) {
                    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(addr));
                }
                // Cc = the demoted counterpart(s) for every ECN in this
                // group, deduped + the admin DL.
                for (String addr : group.ccAddresses.values()) {
                    msg.addRecipient(Message.RecipientType.CC, new InternetAddress(addr));
                }
                msg.addRecipient(Message.RecipientType.CC, new InternetAddress(ADMIN_CC));
                msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));
                msg.setContent(html, "text/html; charset=utf-8");
                Transport.send(msg);
                res.sent = true;
            } catch (Exception e) {
                res.sent = false;
                res.error = e.getMessage();
                LOG.warning("[IT-ENH-NOTIFY] summary to " + res.recipientEmail + " failed: " + e.getMessage());
            }
            outcomes.add(res);
        }
        return outcomes;
    }

    /** Internal — resolved AD user. Null if loginid was blank or lookup
     *  failed; the failure is recorded in the {@code unresolved} map so the
     *  controller can include it in the admin failure summary. */
    private static class ResolvedUser {
        final String email;
        final String displayName;
        ResolvedUser(String email, String displayName) {
            this.email = email;
            this.displayName = displayName;
        }
    }

    private ResolvedUser resolveOrFlag(String loginId, String displayName,
                                       java.util.LinkedHashMap<String, String> unresolved) {
        if (loginId == null || loginId.trim().isEmpty()) return null;
        LdapAuthService.UserInfo info = ldapAuthService.lookupUserById(loginId.trim());
        if (info == null || info.email == null || info.email.isEmpty()) {
            unresolved.put(loginId.trim(),
                    (displayName == null || displayName.isEmpty()) ? loginId.trim() : displayName);
            return null;
        }
        String name = info.displayName != null && !info.displayName.isEmpty() ? info.displayName : displayName;
        return new ResolvedUser(info.email, name);
    }

    /**
     * Admin-only email summarizing one or more save failures. Sent when the
     * save-batch call returned non-OK rows. Body lists the failed cells
     * with the SDK error and instructions to retry from the toolkit UI
     * (where the dirty entries are still pending).
     */
    public void sendFailureNotification(List<Map<String, Object>> failures,
                                        List<String> notificationErrors,
                                        String editorDisplayName) {
        if ((failures == null || failures.isEmpty()) && (notificationErrors == null || notificationErrors.isEmpty())) {
            return;
        }
        try {
            String subject = "PLM IT Enhancements — save failures ("
                    + (failures == null ? 0 : failures.size()) + " edit(s), "
                    + (notificationErrors == null ? 0 : notificationErrors.size()) + " notify error(s))";
            String html = renderFailureHtml(failures, notificationErrors, editorDisplayName);
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", "false");
            Session session = Session.getInstance(props);
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(mailFrom));
            msg.addRecipient(Message.RecipientType.TO, new InternetAddress(ADMIN_CC));
            msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));
            msg.setContent(html, "text/html; charset=utf-8");
            Transport.send(msg);
        } catch (Exception e) {
            LOG.warning("[IT-ENH-NOTIFY] admin failure email failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Email rendering
    // ------------------------------------------------------------------

    /** Build the per-ECN block. Header is the linked ECN # + FULL proposal
     *  text (no truncation — Krati's feedback 2026-06-16: short snippets
     *  don't give recipients enough context to recognize the ECN). Below
     *  that: a pre-rendered role line ("Requestor: X · IT Owner: Y") + the
     *  field-diff table + the meeting note (if any). */
    @SuppressWarnings("unchecked")
    private String renderEcnBlock(String ecn, Map<String, Object> edit, String roleLineHtml) {
        String note = (String) edit.getOrDefault("note", "");
        if (note == null) note = "";
        List<Map<String, Object>> changes = (List<Map<String, Object>>) edit.get("changes");
        if (changes == null) changes = new ArrayList<>();

        StringBuilder changesRows = new StringBuilder();
        for (Map<String, Object> c : changes) {
            String field = String.valueOf(c.getOrDefault("field", ""));
            String oldV = String.valueOf(c.getOrDefault("old", ""));
            String newV = String.valueOf(c.getOrDefault("new", ""));
            if (oldV == null || oldV.isEmpty() || "null".equals(oldV)) oldV = "(blank)";
            if (newV == null || newV.isEmpty() || "null".equals(newV)) newV = "(blank)";
            changesRows.append("<tr>")
                .append("<td style='padding:5px 10px;border:1px solid #cccccc;background:#FAFAF7;color:#6B7280;font-weight:600;width:32%;'>").append(escHtml(field)).append("</td>")
                .append("<td style='padding:5px 10px;border:1px solid #cccccc;background:#ffffff;color:#444;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:12px;'>").append(escHtml(oldV)).append("</td>")
                .append("<td style='padding:5px 10px;border:1px solid #cccccc;background:#e8f5e9;color:#155724;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:12px;font-weight:600;'>").append(escHtml(newV)).append("</td>")
                .append("</tr>");
        }
        String changesTable = changesRows.length() == 0 ? ""
            : ("<table cellspacing='0' cellpadding='0' style='width:100%;border-collapse:collapse;font-size:12px;color:#0F1720;margin:8px 0 0;'>"
              + "<thead><tr style='background:#2c3e50;color:#fff;'>"
              + "<th style='padding:5px 10px;text-align:left;border:1px solid #444;font-size:10.5px;letter-spacing:0.04em;text-transform:uppercase;'>Field</th>"
              + "<th style='padding:5px 10px;text-align:left;border:1px solid #444;font-size:10.5px;letter-spacing:0.04em;text-transform:uppercase;'>Previous</th>"
              + "<th style='padding:5px 10px;text-align:left;border:1px solid #444;font-size:10.5px;letter-spacing:0.04em;text-transform:uppercase;'>New</th>"
              + "</tr></thead><tbody>" + changesRows.toString() + "</tbody></table>");

        String noteBlock = note.trim().isEmpty() ? ""
            : ("<div style='margin-top:8px;background:#FAFAF7;border-left:3px solid #4a6fa5;border-radius:0 4px 4px 0;padding:8px 12px;font-size:12.5px;line-height:1.45;color:#444;white-space:pre-wrap;'>"
              + "<div style='font-size:10px;text-transform:uppercase;letter-spacing:0.05em;color:#6B7280;font-weight:600;margin-bottom:2px;'>Meeting note &rarr; IT Log</div>"
              + escHtml(note) + "</div>");

        String agileUrl = agileWebclientUrl + "/object/ECN/" + ecn;

        // Full proposal text (no truncation per 2026-06-16 feedback).
        // Collapse whitespace runs so a CRLF-heavy CLOB doesn't sprawl, but
        // keep the whole thing for context.
        String proposal = String.valueOf(edit.getOrDefault("proposal", ""));
        if (proposal == null || "null".equals(proposal)) proposal = "";
        proposal = proposal.replaceAll("\\s+", " ").trim();
        String proposalHtml = proposal.isEmpty() ? ""
            : "<div style='font-size:12.5px;color:#444;font-weight:400;margin-top:4px;line-height:1.45;'>"
                + escHtml(proposal) + "</div>";

        return "<div style='margin:14px 0;padding:14px 16px;border:1px solid #E8E6DF;border-radius:6px;background:#fff;'>"
            + "<div style='margin-bottom:6px;'>"
            +   "<a href='" + escAttr(agileUrl) + "' style='font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:14px;color:#4a6fa5;font-weight:600;text-decoration:none;border-bottom:1px dashed #4a6fa5;'>" + escHtml(ecn) + "</a>"
            +   proposalHtml
            +   "<div style='font-size:11px;color:#6B7280;margin-top:6px;'>" + roleLineHtml + "</div>"
            + "</div>"
            + changesTable
            + noteBlock
            + "</div>";
    }

    /** Render the per-recipient-group summary email. Lists every ECN in
     *  the group along with the role line (Requestor + IT Owner). */
    private String renderSummaryHtml(RecipientGroup group, String editorDisplayName) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new Date());
        String editor = editorDisplayName == null || editorDisplayName.isEmpty() ? "(unknown editor)" : editorDisplayName;
        int n = group.editsInGroup.size();
        StringBuilder ecnBlocks = new StringBuilder();
        for (int i = 0; i < group.editsInGroup.size(); i++) {
            Map<String, Object> e = group.editsInGroup.get(i);
            String ecn = String.valueOf(e.get("ecn"));
            String roleLine = group.roleLineForEcn.get(i);
            ecnBlocks.append(renderEcnBlock(ecn, e, roleLine));
        }
        // Greeting addresses only the To: recipient (Cc'd people get a copy
        // but the email speaks primarily to the To: party). "Hi <First>,"
        // when there's one To: recipient; "Hi all," when multiple.
        String greeting;
        if (group.toAddresses.size() > 1) {
            greeting = "Hi all,";
        } else {
            greeting = group.greetingFirstName != null && !group.greetingFirstName.isEmpty()
                ? ("Hi " + group.greetingFirstName + ",")
                : "Hi,";
        }
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'>"
            + "<style>body{margin:0;padding:0;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720}</style></head><body>"
            + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' border='0' style='background:#FAFAF7;'>"
            + "<tr><td align='center' style='padding:24px 14px;'>"
            + "<table role='presentation' width='620' cellspacing='0' cellpadding='0' border='0' style='max-width:620px;background:#ffffff;border:1px solid #E8E6DF;border-radius:8px;'>"
            + "<tr><td style='padding:14px 22px;border-bottom:1px solid #E8E6DF;font-size:11px;color:#6B7280;letter-spacing:0.5px;'>"
            +   "Agile PLM &nbsp;/&nbsp; IT Enhancements"
            + "</td></tr>"
            + "<tr><td style='padding:18px 22px 4px;'>"
            +   "<div style='font-size:11px;color:#6B7280;letter-spacing:1px;text-transform:uppercase;font-weight:500;'>" + n + " ECN" + (n == 1 ? "" : "s") + " updated</div>"
            +   "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif;font-size:22px;font-weight:600;margin:4px 0 0;color:#0F1720;'>" + escHtml(greeting) + "</h1>"
            +   "<p style='font-size:13px;color:#0F1720;line-height:1.55;margin:8px 0 0;'>"
            +     "<strong style='color:#4a6fa5;'>" + escHtml(editor) + "</strong> updated " + n + " IT Enhancement" + (n == 1 ? "" : "s")
            +     " that you're involved with. Summary below — open each in Agile PLM to dive in."
            +   "</p>"
            + "</td></tr>"
            + "<tr><td style='padding:6px 22px 14px;'>"
            +   ecnBlocks.toString()
            + "</td></tr>"
            + "<tr><td style='padding:12px 22px;border-top:1px solid #E8E6DF;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:11px;color:#6B7280;'>"
            +   escHtml(stamp)
            + "</td></tr>"
            + "<tr><td style='background:#FAFAF7;border-top:1px solid #E8E6DF;border-radius:0 0 8px 8px;padding:14px 22px;'>"
            +   "<span style='display:inline-block;padding:3px 12px;font-size:11px;background:#ffffff;border:1px solid #ececec;border-radius:20px;color:#6B7280;'>sandisk</span>"
            +   "<div style='font-size:11px;color:#6B7280;margin-top:6px;'>PLM Field Tracker &middot; IT Enhancements notification</div>"
            +   "<div style='font-size:11px;color:#6B7280;margin-top:2px;'>This is an automated notification. Please do not reply to this email.</div>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private String renderFailureHtml(List<Map<String, Object>> failures,
                                     List<String> notifyErrors,
                                     String editorDisplayName) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new Date());
        String editor = editorDisplayName == null || editorDisplayName.isEmpty() ? "(unknown editor)" : editorDisplayName;

        StringBuilder rows = new StringBuilder();
        if (failures != null) {
            for (Map<String, Object> f : failures) {
                rows.append("<tr>")
                    .append("<td style='padding:6px 10px;border:1px solid #cccccc;background:#fff;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:12px;'>")
                    .append(escHtml(String.valueOf(f.getOrDefault("ecn", ""))))
                    .append("</td>")
                    .append("<td style='padding:6px 10px;border:1px solid #cccccc;background:#fff;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:12px;'>")
                    .append(escHtml(String.valueOf(f.getOrDefault("cell", ""))))
                    .append("</td>")
                    .append("<td style='padding:6px 10px;border:1px solid #cccccc;background:#fdeaea;color:#721c24;font-size:12px;'>")
                    .append(escHtml(String.valueOf(f.getOrDefault("error", ""))))
                    .append("</td>")
                    .append("</tr>");
            }
        }
        String failuresTable = rows.length() == 0 ? ""
            : ("<p style='margin:14px 0 6px;font-weight:600;font-size:13px;color:#0F1720;'>Save failures</p>"
              + "<table cellspacing='0' cellpadding='0' style='width:100%;border-collapse:collapse;font-size:12.5px;'>"
              + "<thead><tr style='background:#2c3e50;color:#fff;'>"
              + "<th style='padding:6px 10px;text-align:left;border:1px solid #444;'>ECN</th>"
              + "<th style='padding:6px 10px;text-align:left;border:1px solid #444;'>Cell</th>"
              + "<th style='padding:6px 10px;text-align:left;border:1px solid #444;'>Error</th>"
              + "</tr></thead><tbody>" + rows.toString() + "</tbody></table>");

        StringBuilder notifyBlock = new StringBuilder();
        if (notifyErrors != null && !notifyErrors.isEmpty()) {
            notifyBlock.append("<p style='margin:14px 0 6px;font-weight:600;font-size:13px;color:#0F1720;'>Notification send errors</p>");
            notifyBlock.append("<ul style='margin:0;padding-left:20px;font-size:12.5px;color:#444;'>");
            for (String n : notifyErrors) {
                notifyBlock.append("<li>").append(escHtml(n)).append("</li>");
            }
            notifyBlock.append("</ul>");
        }

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'>"
            + "<style>body{margin:0;padding:0;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720}</style></head><body>"
            + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' border='0' style='background:#FAFAF7;'>"
            + "<tr><td align='center' style='padding:24px 14px;'>"
            + "<table role='presentation' width='600' cellspacing='0' cellpadding='0' border='0' style='max-width:600px;background:#ffffff;border:1px solid #E8E6DF;border-radius:8px;'>"
            + "<tr><td style='padding:14px 22px;border-bottom:1px solid #E8E6DF;'>"
            +   "<span style='font-size:11px;color:#6B7280;letter-spacing:0.5px;'>Agile PLM &nbsp;/&nbsp; IT Enhancements</span> "
            +   "<span style='display:inline-block;padding:3px 10px;font-size:11px;background:#fdeaea;color:#721c24;border-radius:10px;font-weight:600;margin-left:6px;'>SAVE FAILURE</span>"
            + "</td></tr>"
            + "<tr><td style='padding:18px 22px 4px;'>"
            +   "<div style='font-size:11px;color:#6B7280;letter-spacing:1px;text-transform:uppercase;font-weight:500;'>Save failed</div>"
            +   "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif;font-size:22px;font-weight:600;margin:4px 0 0;color:#B8342B;'>"
            +     "One or more edits did not land in Agile"
            +   "</h1>"
            +   "<p style='font-size:13px;color:#6B7280;margin:6px 0 0;'>Triggered by <strong style='color:#4a6fa5;'>" + escHtml(editor) + "</strong></p>"
            + "</td></tr>"
            + "<tr><td style='padding:6px 22px 14px;'>"
            +   "<div style='background:#fdeaea;border-left:4px solid #B8342B;border-radius:0 6px 6px 0;padding:10px 14px;font-size:13px;line-height:1.5;color:#721c24;margin:14px 0;'>"
            +     "<strong>Retry:</strong> the failed edits remain in the user's dirty state in Meeting Mode. They can fix and click <em>Save changes to Agile</em> again. No data was lost."
            +   "</div>"
            +   failuresTable
            +   notifyBlock.toString()
            + "</td></tr>"
            + "<tr><td style='padding:12px 22px;border-top:1px solid #E8E6DF;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:11px;color:#6B7280;'>"
            +   escHtml(stamp)
            + "</td></tr>"
            + "<tr><td style='background:#FAFAF7;border-top:1px solid #E8E6DF;border-radius:0 0 8px 8px;padding:14px 22px;'>"
            +   "<span style='display:inline-block;padding:3px 12px;font-size:11px;background:#ffffff;border:1px solid #ececec;border-radius:20px;color:#6B7280;'>sandisk</span>"
            +   "<div style='font-size:11px;color:#6B7280;margin-top:6px;'>PLM Field Tracker &middot; IT Enhancements save-failure alert</div>"
            +   "<div style='font-size:11px;color:#6B7280;margin-top:2px;'>This is an automated notification. Please do not reply to this email.</div>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String escAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
