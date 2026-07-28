package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.UserPermissionsService.PendingRequest;
import com.sandisk.plm.tracker.service.UserPermissionsService.TabDef;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Sends a "welcome to PLM Toolkit, your access is ready" email to a user who
 * has just been added to the access DL but hasn't logged in yet. CC includes
 * the admin DL and the requester (the person who configured their tabs).
 *
 * <p>Sent on demand via the manual verified send-invites action
 * ({@code POST /api/permissions/dl-requests/send-invites}), after a live
 * DL-membership check confirms the user is already in the access group.
 */
@Service
public class DlWelcomeEmailService {

    private static final Logger logger = Logger.getLogger(DlWelcomeEmailService.class.getName());

    @Value("${mail.smtp.host:mailrelay.sandisk.com}") private String smtpHost;
    @Value("${mail.smtp.port:25}") private int smtpPort;
    @Value("${mail.from:PLM-Toolkit@sandisk.com}") private String mailFrom;
    @Value("${app.admin-email:pdl-plm-admin@sandisk.com}") private String adminEmail;
    @Value("${app.maintenance.app-url:http://uls-ep-aglipccb:8090}") private String appUrl;

    @Autowired private LdapAuthService ldapAuthService;

    /**
     * @return true on send, false if there's no usable reporter address.
     */
    public boolean sendWelcome(PendingRequest req) {
        if (req == null) return false;
        if (req.email == null || req.email.isEmpty()) {
            logger.warning("[DL-WELCOME] " + req.sAMAccountName + " has no email — skipping welcome.");
            return false;
        }

        // Look up the requester's email so we can CC them. Best-effort — if the
        // lookup fails we still send to the new user + admin DL.
        String requesterEmail = lookupRequesterEmail(req.requestedBy);

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            Session mailSession = Session.getInstance(props);

            MimeMessage msg = new MimeMessage(mailSession);
            msg.setFrom(new InternetAddress(mailFrom));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(req.email));
            msg.addRecipient(Message.RecipientType.CC, new InternetAddress(adminEmail));
            if (requesterEmail != null && !requesterEmail.isEmpty()
                    && !requesterEmail.equalsIgnoreCase(req.email)
                    && !requesterEmail.equalsIgnoreCase(adminEmail)) {
                msg.addRecipient(Message.RecipientType.CC, new InternetAddress(requesterEmail));
            }
            msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("Welcome to PLM Toolkit \u2014 your access is ready, " + firstNameOf(req.displayName)));
            msg.setContent(buildHtml(req), "text/html; charset=utf-8");

            Transport.send(msg);
            logger.info("[DL-WELCOME] Sent to " + req.email
                    + (requesterEmail != null ? " (cc " + requesterEmail + ", " + adminEmail + ")" : " (cc " + adminEmail + ")"));
            return true;
        } catch (Exception e) {
            logger.warning("[DL-WELCOME] Send failed for " + req.sAMAccountName + ": " + e.getMessage());
            return false;
        }
    }

    private String lookupRequesterEmail(String username) {
        if (username == null || username.isEmpty()) return null;
        try {
            LdapAuthService.UserInfo info = ldapAuthService.lookupUserByUsername(username);
            if (info != null && info.email != null && !info.email.isEmpty()) return info.email;
        } catch (Exception ex) {
            logger.fine("[DL-WELCOME] LDAP lookup failed for requester " + username + ": " + ex.getMessage());
        }
        return null;
    }

    private String buildHtml(PendingRequest req) {
        String firstName = firstNameOf(req.displayName);
        String timestamp = new SimpleDateFormat("MMM d, yyyy h:mm a").format(new Date());
        String requesterTxt = (req.requestedByDisplay == null || req.requestedByDisplay.isEmpty())
            ? "your team" : esc(req.requestedByDisplay);

        // Map tab keys back to friendly labels
        Map<String, String> tabLabels = new LinkedHashMap<>();
        for (TabDef t : UserPermissionsService.TAB_CATALOG) tabLabels.put(t.key, t.label);
        StringBuilder tabsRows = new StringBuilder();
        List<String> tabs = req.requestedTabs == null ? java.util.Collections.<String>emptyList() : req.requestedTabs;
        if (tabs.isEmpty()) {
            tabsRows.append("<li>All standard tabs (default access)</li>");
        } else {
            for (String key : tabs) {
                tabsRows.append("<li>").append(esc(tabLabels.getOrDefault(key, key))).append("</li>");
            }
        }

        return "<!doctype html><html lang=\"en\"><head>"
            + "<meta charset=\"utf-8\">"
            + "<meta name=\"color-scheme\" content=\"light dark\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">"
            + "<title>Welcome to PLM Toolkit</title>"
            + "<style>"
            + "@media (prefers-color-scheme: dark) {"
            + "  .email-body { background:#15171a !important; }"
            + "  .email-card { background:#1c1f23 !important; border-color:#2a2f36 !important; }"
            + "  .email-ink { color:#e7e9eb !important; }"
            + "  .email-muted { color:#9ba1aa !important; }"
            + "  .callout-success { background:#0f3320 !important; }"
            + "  .footer-pill { border-color:#2a2f36 !important; color:#9ba1aa !important; }"
            + "}"
            + "</style></head>"
            + "<body class=\"email-body\" style=\"margin:0;padding:24px;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720;\">"
            + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" class=\"email-card\" align=\"center\""
            + " style=\"background:#fff;border:1px solid #E8E6DF;border-radius:8px;border-collapse:separate;\">"

            // Nav header
            + "<tr><td style=\"padding:14px 24px;border-bottom:1px solid #E8E6DF;\">"
            + "<table cellspacing=\"0\" cellpadding=\"0\" role=\"presentation\"><tr>"
            + "<td style=\"background:#0F1720;color:#fff;font-weight:600;font-size:11px;border-radius:4px;padding:3px 7px;\">PT</td>"
            + "<td style=\"padding:0 8px;font-size:13px;color:#6B7280;\" class=\"email-muted\">PLM Toolkit</td>"
            + "<td style=\"font-size:13px;color:#0F1720;font-weight:600;\" class=\"email-ink\">Welcome</td>"
            + "<td style=\"padding:0 8px;\"><span style=\"display:inline-block;padding:3px 9px;border-radius:12px;background:#d4edda;color:#155724;font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:0.05em;\">Access ready</span></td>"
            + "</tr></table></td></tr>"

            // Hero
            + "<tr><td style=\"padding:24px 24px 12px 24px;\">"
            + "<div class=\"email-muted\" style=\"font-size:11px;letter-spacing:0.08em;text-transform:uppercase;color:#6B7280;\">INVITATION \u00b7 " + esc(timestamp) + "</div>"
            + "<h1 class=\"email-ink\" style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:600;color:#0F1720;margin:6px 0 4px 0;line-height:1.3;\">Welcome to PLM Toolkit, " + esc(firstName) + "</h1>"
            + "<p class=\"email-muted\" style=\"margin:0;font-size:13px;color:#6B7280;\">" + requesterTxt + " set up your access and added you to the toolkit DL. You can sign in now using your SanDisk AD credentials.</p>"
            + "</td></tr>"

            // CTA
            + "<tr><td style=\"padding:6px 24px 12px 24px;text-align:center;\">"
            + "<a href=\"" + esc(appUrl) + "\" target=\"_blank\""
            + " style=\"display:inline-block;background:#0F1720;color:#fff;padding:12px 28px;border-radius:6px;font-size:14px;font-weight:600;text-decoration:none;font-family:'IBM Plex Sans',sans-serif;\">"
            + "Open PLM Toolkit</a>"
            + "<div class=\"email-muted\" style=\"font-size:11px;color:#6B7280;margin-top:8px;font-family:'IBM Plex Mono',Consolas,monospace;\">" + esc(appUrl) + "</div>"
            + "</td></tr>"

            // Tabs you can see
            + "<tr><td style=\"padding:6px 24px 16px 24px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" class=\"callout-success\" style=\"background:#e8f5e9;border-left:4px solid #1F8A4C;border-radius:0 6px 6px 0;\">"
            + "<tr><td style=\"padding:14px 16px;font-size:13px;color:#0F1720;\" class=\"email-ink\">"
            + "<strong>Tabs you can see on first login:</strong>"
            + "<ul style=\"margin:8px 0 0 18px;padding:0;font-size:13px;\">" + tabsRows.toString() + "</ul>"
            + "</td></tr></table></td></tr>"

            // Quick start tips
            + "<tr><td style=\"padding:6px 24px 8px 24px;\">"
            + "<h3 class=\"email-muted\" style=\"font-size:12px;letter-spacing:0.08em;text-transform:uppercase;color:#6B7280;border-bottom:1px solid #E8E6DF;padding-bottom:6px;margin:0 0 10px 0;font-weight:600;\">Quick start</h3>"
            + "<ul style=\"margin:0 0 0 18px;padding:0;font-size:13px;color:#0F1720;line-height:1.55;\" class=\"email-ink\">"
            + "<li>Click the link above and sign in with your <strong>AD username + password</strong>.</li>"
            + "<li>Use the <strong>?</strong> button at the bottom-left if you get stuck \u2014 there\u2019s an in-app feedback form for bugs and feature requests.</li>"
            + "<li>Your name in the top-right opens a <strong>Visible Tabs</strong> menu so you can hide anything you don\u2019t use.</li>"
            + "<li>Reply to this email if any tab you expected isn\u2019t visible \u2014 we\u2019ll sort it out.</li>"
            + "</ul></td></tr>"

            // Meta strip
            + "<tr><td style=\"padding:16px 24px;border-top:1px solid #cccccc;\">"
            + "<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\"><tr>"
            + "<td class=\"email-muted\" style=\"font-size:11px;color:#6B7280;font-family:'IBM Plex Mono',Consolas,monospace;\">"
            + "PLM Toolkit \u00b7 access invitation \u00b7 " + esc(timestamp)
            + "</td></tr></table></td></tr>"

            // Footer
            + "<tr><td style=\"padding:14px 24px;border-top:1px solid #E8E6DF;background:#FAFAF7;text-align:center;\">"
            + "<span class=\"footer-pill\" style=\"display:inline-block;padding:3px 11px;border:1px solid #ececec;border-radius:20px;color:#6B7280;font-size:11px;\">sandisk</span>"
            + "<div class=\"email-muted\" style=\"font-size:11px;color:#6B7280;margin-top:6px;\">PLM Toolkit \u00b7 Field Tracker</div>"
            + "<div class=\"email-muted\" style=\"font-size:11px;color:#6B7280;\">If you weren\u2019t expecting this email, reply to let us know.</div>"
            + "</td></tr>"

            + "</table></body></html>";
    }

    private String firstNameOf(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "there";
        if (displayName.contains(",")) return displayName.split(",")[1].trim();
        return displayName.split("\\s+")[0];
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
