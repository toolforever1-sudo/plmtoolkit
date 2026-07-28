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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Builds and sends the two Meeting Mode wrap-up outputs (see
 * {@code MEETING-MODE-IMPLEMENTATION.md} §6):
 *
 * <ol>
 *   <li><b>Consolidated recap</b> to everyone in the room — Action items,
 *       Saved-to-Agile (grouped by ECN), and a "Sent separately" notice.</li>
 *   <li><b>Separate owner notifications</b> — one email per person who got an
 *       update but was not in the room (out-of-room IT owners + out-of-room
 *       action assignees).</li>
 * </ol>
 *
 * <p>Email addresses are resolved from AD loginids via
 * {@link LdapAuthService#lookupUserById}. SMTP + design follow the same
 * conventions as {@link ItEnhancementsNotificationService} (SanDisk palette,
 * IBM Plex fonts, sandisk footer pill, {@link EmailEnvTag} env prefix).
 */
@Service
public class MeetingEmailService {

    private static final Logger LOG = Logger.getLogger(MeetingEmailService.class.getName());

    /** Always Cc'd on every Meeting Mode email (recap + separate notifications),
     *  matching {@link ItEnhancementsNotificationService}'s admin Cc. */
    private static final String ADMIN_CC = "pdl-plm-admin@sandisk.com";

    @Value("${mail.smtp.host:mailrelay.sandisk.com}") private String smtpHost;
    @Value("${mail.smtp.port:25}")                     private int smtpPort;
    @Value("${mail.from:PLM-Toolkit@sandisk.com}")     private String mailFrom;
    @Value("${app.ims-review.agile-webclient-url:https://plm.sandisk.com/Agile}")
    private String agileWebclientUrl;

    @Autowired private LdapAuthService ldapAuthService;

    /** Outcome of a wrap-up send — drives the frozen record's recipient list
     *  and {@code notified} count. */
    public static class SendResult {
        public final List<MeetingModels.Recipient> recipients = new ArrayList<>();
        public int notifiedCount;
        public final List<String> errors = new ArrayList<>();
    }

    /**
     * Compute + send both outputs for {@code session}. Idempotency is the
     * caller's concern (it deletes the live session after freezing). Returns
     * the set of people actually emailed so the record can list them.
     */
    public SendResult sendWrapUp(MeetingModels.Session session) {
        SendResult result = new SendResult();
        if (session == null) return result;

        String dateStr = new SimpleDateFormat("MMMM d, yyyy").format(
                new Date(session.endedAt != null ? session.endedAt : System.currentTimeMillis()));

        // Room membership for the not-in-room checks.
        Set<String> roomAds = new LinkedHashSet<>();
        for (MeetingModels.Attendee a : session.attendees) {
            if (a.ad != null) roomAds.add(a.ad.trim().toLowerCase());
        }

        // ---- the notify set: out-of-room owners (AGILE) + out-of-room assignees (NOTE) ----
        // Keyed by AD loginid → display name. Each maps to the ECN/text lines
        // that concern them (for the separate email body).
        Map<String, String> notifyNames = new LinkedHashMap<>();
        Map<String, List<String>> notifyLines = new LinkedHashMap<>();
        for (MeetingModels.Bubble b : session.bubbles) {
            if ("AGILE".equals(b.kind) && b.ownerNotInRoom && b.ownerAd != null && !b.ownerAd.isEmpty()) {
                addNotify(notifyNames, notifyLines, b.ownerAd, b.ownerName,
                        ecnLink(b.ecn) + " — " + escHtml(b.text));
            }
            if ("NOTE".equals(b.kind) && b.assigneeAd != null && !b.assigneeAd.isEmpty()
                    && !roomAds.contains(b.assigneeAd.trim().toLowerCase())) {
                addNotify(notifyNames, notifyLines, b.assigneeAd, b.assigneeName,
                        ecnLink(b.ecn) + " — " + escHtml(b.text) + " (action assigned to you)");
            }
        }

        // ---- B. separate owner notifications (send first so the recap can
        //         truthfully say who was notified) ----
        List<String> notifiedDisplay = new ArrayList<>();
        for (Map.Entry<String, String> e : notifyNames.entrySet()) {
            String ad = e.getKey();
            String name = e.getValue();
            LdapAuthService.UserInfo info = ldapAuthService.lookupUserById(ad);
            String email = info == null ? null : info.email;
            String disp = (info != null && info.displayName != null && !info.displayName.isEmpty())
                    ? info.displayName : name;
            if (email == null || email.isEmpty()) {
                result.errors.add("could not resolve email for " + (name == null ? ad : name));
                continue;
            }
            boolean sent = sendOne(email,
                    "IT Enhancements — updated in your absence (" + dateStr + ")",
                    renderNotifyHtml(disp, session.leaderName, dateStr, notifyLines.get(ad)));
            if (sent) {
                notifiedDisplay.add(firstName(disp != null ? disp : name));
                MeetingModels.Recipient r = new MeetingModels.Recipient();
                r.name = disp; r.email = email; r.type = "NOTIFIED";
                result.recipients.add(r);
                result.notifiedCount++;
            } else {
                result.errors.add("notify email to " + disp + " failed");
            }
        }

        // ---- A. consolidated recap to the room ----
        // Resolve attendee emails (skip the ones that don't resolve).
        List<String> toAddrs = new ArrayList<>();
        for (MeetingModels.Attendee a : session.attendees) {
            String email = a.email;
            if (email == null || email.isEmpty()) {
                LdapAuthService.UserInfo info = a.ad == null ? null : ldapAuthService.lookupUserById(a.ad);
                email = info == null ? null : info.email;
            }
            if (email != null && !email.isEmpty()) {
                toAddrs.add(email);
                MeetingModels.Recipient r = new MeetingModels.Recipient();
                r.name = a.name; r.email = email; r.type = "ROOM";
                result.recipients.add(r);
            }
        }
        String recapHtml = renderRecapHtml(session, dateStr, notifiedDisplay);
        if (!toAddrs.isEmpty()) {
            boolean sent = sendMany(toAddrs, "IT Enhancements meeting — " + dateStr, recapHtml);
            if (!sent) result.errors.add("recap email to the room failed");
        } else {
            result.errors.add("no room attendee resolved to an email — recap not sent");
        }
        return result;
    }

    private static void addNotify(Map<String, String> names, Map<String, List<String>> lines,
                                  String ad, String name, String line) {
        String key = ad.trim();
        names.putIfAbsent(key, name);
        lines.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
    }

    // ------------------------------------------------------------------
    // Recap (to the room)
    // ------------------------------------------------------------------

    /** One change's worth of captured updates, for the grouped recap. */
    private static class ChangeGroup {
        String title;
        final List<String> agile = new ArrayList<>();          // AGILE bubble texts (written)
        final List<String> failed = new ArrayList<>();          // AGILE texts that failed to write
        final List<String[]> actions = new ArrayList<>();       // {assigneeName, text}
        final List<String> notes = new ArrayList<>();           // unassigned NOTE texts
    }

    private String renderRecapHtml(MeetingModels.Session s, String dateStr, List<String> notifiedDisplay) {
        // Group every bubble by change (ECN), preserving first-seen order, so
        // each change reads as one block with its description for context.
        LinkedHashMap<String, ChangeGroup> byEcn = new LinkedHashMap<>();
        int actionCount = 0;
        for (MeetingModels.Bubble b : s.bubbles) {
            ChangeGroup g = byEcn.computeIfAbsent(b.ecn, k -> new ChangeGroup());
            if ((g.title == null || g.title.isEmpty()) && b.ecnTitle != null) g.title = b.ecnTitle;
            if ("AGILE".equals(b.kind)) {
                if ("FAILED".equals(b.agileWriteStatus)) g.failed.add(b.text);
                else g.agile.add(b.text);
            } else if ("NOTE".equals(b.kind)) {
                if (b.assigneeName != null && !b.assigneeName.isEmpty()) { g.actions.add(new String[]{ b.assigneeName, b.text }); actionCount++; }
                else g.notes.add(b.text);
            }
        }

        StringBuilder blocks = new StringBuilder();
        for (Map.Entry<String, ChangeGroup> e : byEcn.entrySet()) {
            blocks.append(renderChangeBlock(e.getKey(), e.getValue()));
        }
        String changesBlock = byEcn.isEmpty()
                ? "<div style='font-size:12.5px;color:#6B7280;margin-top:12px;'>Nothing was captured in this session.</div>"
                : section(byEcn.size() + " change" + (byEcn.size() == 1 ? "" : "s") + " reviewed"
                        + (actionCount > 0 ? " · " + actionCount + " action item" + (actionCount == 1 ? "" : "s") : ""),
                        blocks.toString());

        // Sent separately (names only).
        String notifyBlock = notifiedDisplay == null || notifiedDisplay.isEmpty() ? "" :
                section("Sent separately",
                        "<div style='font-size:12.5px;color:#0F1720;line-height:1.55;'>"
                                + "Owners not in the room were emailed: <strong>"
                                + escHtml(String.join(", ", dedup(notifiedDisplay))) + "</strong>.</div>");

        String attendeeLine = attendeeNames(s);
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new Date());

        return shell("IT Enhancements meeting",
                "<div style='font-size:11px;color:#6B7280;letter-spacing:1px;text-transform:uppercase;font-weight:500;'>Meeting recap · " + escHtml(dateStr) + "</div>"
                        + "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif;font-size:22px;font-weight:600;margin:4px 0 0;color:#0F1720;'>IT Enhancements review</h1>"
                        + "<p style='font-size:13px;color:#0F1720;line-height:1.55;margin:8px 0 0;'>Led by <strong style='color:#4a6fa5;'>"
                        + escHtml(s.leaderName) + "</strong>. In the room: " + escHtml(attendeeLine) + ".</p>",
                changesBlock + notifyBlock, stamp);
    }

    /** Render one change as a bordered card: ECN link + description, then its
     *  Agile updates, action items, and any plain notes. */
    private String renderChangeBlock(String fullEcn, ChangeGroup g) {
        StringBuilder inner = new StringBuilder();
        if (!g.agile.isEmpty()) {
            inner.append("<div style='font-size:12.5px;color:#0F1720;line-height:1.7;margin-top:8px;'>")
                    .append("<span style='display:inline-block;font-size:10px;text-transform:uppercase;letter-spacing:0.04em;color:#1F8A4C;font-weight:600;margin-right:6px;'>Saved to Agile</span>")
                    .append(escHtml(String.join(" · ", g.agile))).append("</div>");
        }
        if (!g.failed.isEmpty()) {
            inner.append("<div style='font-size:12.5px;color:#B8342B;line-height:1.7;margin-top:6px;'>")
                    .append("<span style='display:inline-block;font-size:10px;text-transform:uppercase;letter-spacing:0.04em;color:#B8342B;font-weight:600;margin-right:6px;'>Not saved · needs retry</span>")
                    .append(escHtml(String.join(" · ", g.failed))).append("</div>");
        }
        for (String[] a : g.actions) {
            inner.append("<div style='font-size:12.5px;color:#0F1720;line-height:1.6;margin-top:6px;'>")
                    .append("<span style='display:inline-block;font-size:10px;text-transform:uppercase;letter-spacing:0.04em;color:#C7801B;font-weight:600;margin-right:6px;'>Action</span>")
                    .append("<strong style='color:#4a6fa5;'>").append(escHtml(a[0])).append("</strong> — ").append(escHtml(a[1]))
                    .append("</div>");
        }
        for (String n : g.notes) {
            inner.append("<div style='font-size:12.5px;color:#444;line-height:1.6;margin-top:6px;'>")
                    .append("<span style='display:inline-block;font-size:10px;text-transform:uppercase;letter-spacing:0.04em;color:#6B7280;font-weight:600;margin-right:6px;'>Note</span>")
                    .append(escHtml(n)).append("</div>");
        }

        String title = g.title == null ? "" : g.title.replaceAll("\\s+", " ").trim();
        String titleHtml = title.isEmpty() ? "" :
                "<div style='font-size:12.5px;color:#444;font-weight:400;margin-top:4px;line-height:1.45;'>" + escHtml(title) + "</div>";

        return "<div style='margin:12px 0;padding:13px 15px;border:1px solid #E8E6DF;border-radius:6px;background:#fff;'>"
                + "<div>" + ecnLink(fullEcn) + "</div>"
                + titleHtml
                + inner
                + "</div>";
    }

    // ------------------------------------------------------------------
    // Separate owner notification
    // ------------------------------------------------------------------

    private String renderNotifyHtml(String name, String leaderName, String dateStr, List<String> lines) {
        StringBuilder items = new StringBuilder();
        if (lines != null) {
            for (String l : lines) {
                // l is pre-rendered HTML (ECN link + escaped text) — append raw.
                items.append("<div style='font-size:12.5px;color:#0F1720;line-height:1.6;margin:2px 0;'>• ")
                        .append(l).append("</div>");
            }
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new Date());
        return shell("Updated in your absence",
                "<div style='font-size:11px;color:#6B7280;letter-spacing:1px;text-transform:uppercase;font-weight:500;'>IT Enhancements · " + escHtml(dateStr) + "</div>"
                        + "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif;font-size:22px;font-weight:600;margin:4px 0 0;color:#0F1720;'>Hi "
                        + escHtml(firstName(name)) + ",</h1>"
                        + "<p style='font-size:13px;color:#0F1720;line-height:1.55;margin:8px 0 0;'>"
                        + "An enhancement you own or were assigned was updated in today's IT Enhancements review (led by <strong style='color:#4a6fa5;'>"
                        + escHtml(leaderName) + "</strong>) while you were not in the room. Please review and confirm.</p>",
                section("What changed",
                        "<div style='border-left:4px solid #4a6fa5;background:#FAFAF7;border-radius:0 6px 6px 0;padding:9px 13px;'>" + items + "</div>"),
                stamp);
    }

    // ------------------------------------------------------------------
    // shared HTML scaffold
    // ------------------------------------------------------------------

    private String section(String title, String inner) {
        return "<div style='margin-top:16px;'>"
                + "<div style='font-size:11px;text-transform:uppercase;letter-spacing:0.05em;color:#6B7280;font-weight:600;border-bottom:1px solid #E8E6DF;padding-bottom:4px;margin-bottom:8px;'>"
                + escHtml(title) + "</div>" + inner + "</div>";
    }

    private String shell(String navName, String hero, String body, String stamp) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'>"
                + "<style>body{margin:0;padding:0;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720}</style></head><body>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' border='0' style='background:#FAFAF7;'>"
                + "<tr><td align='center' style='padding:24px 14px;'>"
                + "<table role='presentation' width='620' cellspacing='0' cellpadding='0' border='0' style='max-width:620px;background:#ffffff;border:1px solid #E8E6DF;border-radius:8px;'>"
                + "<tr><td style='padding:14px 22px;border-bottom:1px solid #E8E6DF;font-size:11px;color:#6B7280;letter-spacing:0.5px;'>Agile PLM &nbsp;/&nbsp; " + escHtml(navName) + "</td></tr>"
                + "<tr><td style='padding:18px 22px 4px;'>" + hero + "</td></tr>"
                + "<tr><td style='padding:6px 22px 14px;'>" + body + "</td></tr>"
                + "<tr><td style='padding:12px 22px;border-top:1px solid #E8E6DF;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:11px;color:#6B7280;'>" + escHtml(stamp) + "</td></tr>"
                + "<tr><td style='background:#FAFAF7;border-top:1px solid #E8E6DF;border-radius:0 0 8px 8px;padding:14px 22px;'>"
                + "<span style='display:inline-block;padding:3px 12px;font-size:11px;background:#ffffff;border:1px solid #ececec;border-radius:20px;color:#6B7280;'>sandisk</span>"
                + "<div style='font-size:11px;color:#6B7280;margin-top:6px;'>PLM Field Tracker &middot; IT Enhancements Meeting Mode</div>"
                + "<div style='font-size:11px;color:#6B7280;margin-top:2px;'>This is an automated notification. Please do not reply to this email.</div>"
                + "</td></tr></table></td></tr></table></body></html>";
    }

    // ------------------------------------------------------------------
    // SMTP + helpers
    // ------------------------------------------------------------------

    private boolean sendOne(String to, String subject, String html) {
        return sendMany(java.util.Collections.singletonList(to), subject, html);
    }

    private boolean sendMany(List<String> to, String subject, String html) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", "false");
            Session session = Session.getInstance(props);
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(mailFrom));
            for (String addr : to) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(addr));
            // pdl-plm-admin is Cc'd on every Meeting Mode email.
            msg.addRecipient(Message.RecipientType.CC, new InternetAddress(ADMIN_CC));
            msg.setSubject(EmailEnvTag.tag(subject));
            msg.setContent(html, "text/html; charset=utf-8");
            Transport.send(msg);
            return true;
        } catch (Exception e) {
            LOG.warning("[MEETING-MAIL] send to " + to + " failed: " + e.getMessage());
            return false;
        }
    }

    private static String attendeeNames(MeetingModels.Session s) {
        List<String> names = new ArrayList<>();
        for (MeetingModels.Attendee a : s.attendees) if (a.name != null) names.add(a.name);
        return String.join(", ", names);
    }

    private static List<String> dedup(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    /** Strip the {@code -PROJ} suffix the toolkit carries internally so the
     *  email shows the bare ECN the recipient recognizes. */
    private static String ecnShort(String ecn) {
        return ecn == null ? "" : ecn.replace("-PROJ", "");
    }

    /** Agile webclient object URL for a full ECN — same convention as the
     *  grid's {@code agileChangeLinkHtml} (type = prefix before the first
     *  hyphen, full number as the object id). */
    private String agileObjectUrl(String fullEcn) {
        if (fullEcn == null || fullEcn.trim().isEmpty()) return agileWebclientUrl;
        String n = fullEcn.trim();
        int dash = n.indexOf('-');
        String type = dash > 0 ? n.substring(0, dash) : "Change";
        return agileWebclientUrl + "/object/" + type + "/" + n;
    }

    /** An <a> linking the full ECN to Agile, displaying the short (no -PROJ) form. */
    private String ecnLink(String fullEcn) {
        return "<a href='" + escAttr(agileObjectUrl(fullEcn))
                + "' style='font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:11px;color:#4a6fa5;font-weight:600;text-decoration:none;border-bottom:1px dashed #a0b8d0;'>"
                + escHtml(ecnShort(fullEcn)) + "</a>";
    }

    /** "Zhu, Peter" → "Peter"; "Vikas Jindal" → "Vikas". */
    private static String firstName(String name) {
        if (name == null || name.isEmpty()) return "there";
        String n = name.trim();
        int comma = n.indexOf(',');
        if (comma >= 0 && comma + 1 < n.length()) return n.substring(comma + 1).trim().split("\\s+")[0];
        return n.split("\\s+")[0];
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
