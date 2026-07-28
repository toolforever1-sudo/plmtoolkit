package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.activation.DataHandler;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

/**
 * Renders + sends the IMS Review tab's emails. Templates live under
 * {@code src/main/resources/templates/email/ims-review-*.html}. Variable
 * substitution is plain {@code String.replace} — matches the existing
 * EmailService pattern, no Thymeleaf in this project.
 *
 * <p><b>Local-test redirect:</b> when {@code app.ims-review.email-redirect-to}
 * is configured, the recipients are overridden and a yellow banner is prepended
 * to the body listing the original To: / Cc: addresses. The subject gets a
 * {@code [LOCAL TEST]} suffix. This keeps dev sends from leaking to real DOs.
 */
@Service
public class ImsReviewEmailService {

    private static final Logger LOG = Logger.getLogger(ImsReviewEmailService.class.getName());

    // Env-driven sender so QA mail comes from PLM-Toolkit-qa@ (was a hardcoded constant).
    @Value("${mail.from:PLM-Toolkit@sandisk.com}")
    private String fromAddr;
    // Non-prod label (e.g. "TEST") — drives the test banner in the email body. Empty on prod.
    @Value("${app.instance.label:}")
    private String instanceLabel;
    public static final long   ATTACH_LIMIT_BYTES = 10L * 1024 * 1024;  // 10 MB per CRD

    /** DCC distribution list — Cc'd on every DO/DM notification per CRD
     *  ECN-129414. Defaults to {@code pdl-plm-admin@sandisk.com} until the
     *  real DCC DL is provisioned in LDAP. */
    @Value("${app.ims-review.dcc-dl:pdl-plm-admin@sandisk.com}")
    public String dccDl;

    /** IMS-Doc-Managers-Agile DL — Cc'd on every DO notification per CRD
     *  ECN-129414. Defaults to {@code pdl-plm-admin@sandisk.com} until the
     *  real managers DL is provisioned in LDAP. */
    @Value("${app.ims-review.managers-dl:pdl-plm-admin@sandisk.com}")
    public String managersDl;

    /** DL Cc'd on EVERY IMS review-flow email (DO/DM/DCC/stakeholder/reassign),
     *  injected centrally in {@link #send(Payload)} (Vikas 2026-06-30). Empty
     *  disables it. Comma-separated for multiple. */
    @Value("${app.ims-review.always-cc:PDL-IMS-DCC-Agile@sandisk.com}")
    public String alwaysCc;

    @Value("${app.smtp.host:mailrelay.sandisk.com}")
    private String smtpHost;

    @Value("${app.smtp.port:25}")
    private int smtpPort;

    /** When set, ALL outbound IMS Review emails are redirected to this address. */
    @Value("${app.ims-review.email-redirect-to:}")
    private String redirectTo;

    @Value("${app.ims-review.toolkit-base-url:http://uls-ep-aglipccb:8090}")
    private String toolkitBaseUrl;

    /** Response-link TTL in days — must match ImsReviewService's
     *  app.ims-review.token-ttl-days so the email's "Link expires in" copy
     *  agrees with the actual expiry the page enforces. Default 100. */
    @Value("${app.ims-review.token-ttl-days:100}")
    private int tokenTtlDays;

    /** Agile PLM webclient base — used to wrap change#/item# in email body
     *  as clickable links: {@code <base>/object/<type>/<number>}. Matches
     *  the toolkit's other UIs (docreview.js, ecnreport.js, etc.). */
    @Value("${app.ims-review.agile-webclient-url:https://plm.sandisk.com/Agile}")
    public String agileWebclientUrl;

    /** ServiceNow link for AD account creation / reactivation. Surfaced in
     *  the footer of every IMS Review email so recipients who can't log in
     *  to Agile know how to get unblocked without asking Vikas. */
    @Value("${app.ims-review.servicenow-account-url:https://sndk.service-now.com/sp?id=wd_sc_cat_item&sys_id=1efef64137917e00548b53b543990e05}")
    private String serviceNowAccountUrl;

    @org.springframework.beans.factory.annotation.Value("${app.ims-review.training-doc-url:}")
    private String trainingDocUrl;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("dataSource")
    private javax.sql.DataSource agileDataSource;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LdapManagerLookup managerLookupForReassign;

    /** Per-recipient Agile account status. Cached 5 min to keep repeat email
     *  renders cheap. */
    private enum AccountStatus { ACTIVE, DISABLED, NOT_FOUND, UNKNOWN }
    private static final class CachedStatus {
        final AccountStatus status; final long fetchedAtMs;
        CachedStatus(AccountStatus s, long t) { this.status = s; this.fetchedAtMs = t; }
    }
    private final java.util.concurrent.ConcurrentHashMap<String, CachedStatus> accountStatusCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ACCOUNT_STATUS_TTL_MS = 5L * 60 * 1000;

    /** Look up an email in {@code agileuser}. Returns:
     *    ACTIVE     — row exists, enabled = 1
     *    DISABLED   — row exists, enabled != 1
     *    NOT_FOUND  — no row
     *    UNKNOWN    — query failed (fail-open: caller suppresses the footer)
     *  Cached 5 min on lowercased email. */
    private AccountStatus checkAgileAccount(String email) {
        if (email == null || email.indexOf('@') < 0) return AccountStatus.UNKNOWN;
        String key = email.trim().toLowerCase();
        CachedStatus c = accountStatusCache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && (now - c.fetchedAtMs) < ACCOUNT_STATUS_TTL_MS) return c.status;

        AccountStatus s = AccountStatus.UNKNOWN;
        int rowCount = 0;
        Integer maxEnabled = null;
        try (java.sql.Connection conn = agileDataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 // MAX(enabled) so "any enabled row wins" — agileuser commonly
                 // has multiple rows per email (old disabled + new active);
                 // the user is active if ANY of them are enabled=1.
                 "SELECT COUNT(*), MAX(enabled) FROM agileuser WHERE UPPER(email) = UPPER(?)")) {
            ps.setString(1, key);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    rowCount = rs.getInt(1);
                    int mv = rs.getInt(2);
                    boolean wasNull = rs.wasNull();
                    maxEnabled = wasNull ? null : mv;
                }
            }
            if (rowCount == 0) {
                s = AccountStatus.NOT_FOUND;
            } else if (maxEnabled != null && maxEnabled == 1) {
                s = AccountStatus.ACTIVE;
            } else {
                s = AccountStatus.DISABLED;
            }
        } catch (Exception ex) {
            LOG.warning("[IMS-MAIL] checkAgileAccount FAIL for " + key
                    + " err=" + ex.getClass().getSimpleName() + ":" + ex.getMessage());
        }
        LOG.info("[IMS-MAIL] checkAgileAccount email=" + key
                + " rowCount=" + rowCount + " maxEnabled=" + maxEnabled
                + " → " + s);
        accountStatusCache.put(key, new CachedStatus(s, now));
        return s;
    }

    /** Render the per-recipient ServiceNow + VPN footer based on Agile
     *  account status. Always includes the SanDisk network/VPN note since
     *  every link in the email (toolkit response page + Agile webclient)
     *  is on the internal network. Account-status callout is added only
     *  for DISABLED / NOT_FOUND recipients. */
    private String accountStatusFooterFor(String email) {
        AccountStatus s = checkAgileAccount(email);
        StringBuilder sb = new StringBuilder();

        // Always-shown: SanDisk network / VPN reminder. Every link in this
        // email (toolkit response page + Agile webclient deep-links) sits
        // on the corporate network — recipients who try to click from a
        // personal device without VPN otherwise see a generic connection
        // error and don't know to switch networks.
        sb.append("<div style=\"font-size:11px; color:#6B7280; margin-top:6px;\">")
          .append("&#x1F310; All links above need the <strong>SanDisk network or VPN</strong> (Cisco AnyConnect). ")
          .append("If a link doesn't load, connect to VPN and try again.")
          .append("</div>");

        // Conditional: account-status note for users who can't get into Agile.
        if (s == AccountStatus.DISABLED || s == AccountStatus.NOT_FOUND) {
            String headline = (s == AccountStatus.DISABLED)
                ? "Your Agile PLM account is currently <strong>disabled</strong>"
                : "We don't see an Agile PLM account for <strong>" + esc(email) + "</strong>";
            sb.append("<div style=\"font-size:11.5px; color:#6B7280; margin-top:8px; padding:8px 10px; background:#fff8e1; border-left:3px solid #C7801B; border-radius:0 4px 4px 0;\">")
              .append("&#x26A0; <strong>Need to view this in Agile?</strong> ").append(headline).append(". ")
              .append("Use the <a href=\"").append(esc(serviceNowAccountUrl)).append("\" target=\"_blank\" style=\"color:#4a6fa5;\">ServiceNow request form</a> ")
              .append("to get your account ")
              .append(s == AccountStatus.DISABLED ? "reactivated." : "created.")
              .append("</div>");
        }
        return sb.toString();
    }

    /** Payload passed to {@link #send}. */
    public static final class Payload {
        public String templateName;          // e.g. "ims-review-do"
        public String subject;
        public List<String> to = new ArrayList<>();
        public List<String> cc = new ArrayList<>();
        public Map<String, String> vars = new LinkedHashMap<>();
        public byte[] attachmentBytes;       // null = no attachment
        public String attachmentFilename;    // ignored if bytes null
        public boolean overdue;              // sets X-Priority + Importance header
        /** SEND-event token — drives the per-action HTTPS approval URLs.
         *  Set by the caller BEFORE invoking payloadForDoEmail /
         *  payloadForDmEmail so the action buttons in the email carry it. */
        public String token;
        /** Optional extra attachments (the single-attachment fields above
         *  cover the legacy case). Used by the DCC closure email to ship
         *  both the DO and DM compliance PDFs together. */
        public List<NamedBlob> extraAttachments = new ArrayList<>();
    }

    public static final class NamedBlob {
        public final String filename;
        public final byte[] bytes;
        public final String mimeType;
        public NamedBlob(String filename, byte[] bytes, String mimeType) {
            this.filename = filename; this.bytes = bytes; this.mimeType = mimeType;
        }
    }

    /** Wrap an Agile change number (DCO-N / DRR-N / ECN-N) in a webclient
     *  link. The type segment is the prefix before the first hyphen — same
     *  convention every other toolkit UI uses. Empty/null number → empty
     *  string (caller decides whether to render a placeholder). */
    String agileLinkChange(String number) {
        if (number == null || number.trim().isEmpty()) return "";
        String n = number.trim();
        int dash = n.indexOf('-');
        String type = dash > 0 ? n.substring(0, dash) : "Change";
        String url = agileWebclientUrl + "/object/"
                   + java.net.URLEncoder.encode(type, java.nio.charset.StandardCharsets.UTF_8)
                   + "/"
                   + java.net.URLEncoder.encode(n, java.nio.charset.StandardCharsets.UTF_8);
        return "<a href=\"" + url + "\" target=\"_blank\" style=\"color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;\">"
             + esc(n) + "</a>";
    }

    /** Wrap an Agile item number (e.g. IMS doc 02-02-WW-01-00001) in a
     *  webclient link. Items use the {@code Part} type segment and the
     *  {@code /tab/13} suffix to deep-link straight to the Files tab —
     *  format confirmed against a working Agile URL on 2026-05-28. */
    String agileLinkItem(String number) {
        if (number == null || number.trim().isEmpty()) return "";
        String n = number.trim();
        String url = agileWebclientUrl + "/object/Part/"
                   + java.net.URLEncoder.encode(n, java.nio.charset.StandardCharsets.UTF_8)
                   + "/tab/13";
        return "<a href=\"" + url + "\" target=\"_blank\" style=\"color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;\">"
             + esc(n) + "</a>";
    }

    /** Legacy generic footer — kept only as a fallback when we can't compute
     *  per-recipient status (e.g. multi-recipient bulk-send code paths that
     *  don't yet iterate). Per-recipient sends should use
     *  {@link #accountStatusFooterFor(String)} instead. */
    String serviceNowFooterHtml() { return ""; }

    /** Compose an HTTPS link into ims-respond.html for a single action.
     *  Token-only auth — no session required at click time. */
    String respondUrl(String token, String action) {
        if (token == null || token.isEmpty()) return toolkitBaseUrl + "/ims-respond.html";
        String url = toolkitBaseUrl + "/ims-respond.html?token="
             + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        // No action → the recipient picks their response on the page itself
        // (the single "Submit your response" button in the DO email). With an
        // action, the page pre-selects that option (legacy per-option buttons).
        if (action != null && !action.isEmpty()) url += "&action=" + action;
        return url;
    }

    /** Raw Agile webclient URL for a change/DRR number (no anchor markup) —
     *  used for the "manage the DRR directly within Agile" link in the DO email. */
    String agileUrlChange(String number) {
        if (number == null || number.trim().isEmpty()) return agileWebclientUrl;
        String n = number.trim();
        int dash = n.indexOf('-');
        String type = dash > 0 ? n.substring(0, dash) : "Change";
        return agileWebclientUrl + "/object/"
             + java.net.URLEncoder.encode(type, java.nio.charset.StandardCharsets.UTF_8)
             + "/" + java.net.URLEncoder.encode(n, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Back-compat (no lifecycle / no inactive-owner-labels / no greeting name). */
    public Payload payloadForDoEmail(String docNumber, String docDescription, String docRev,
                                     String docType, String nextReviewDate, String drrNumber,
                                     List<String> doEmails, byte[] attachmentBytes,
                                     String attachmentFilename, int fileCount, long totalBytes,
                                     boolean doInactive) {
        return payloadForDoEmail(docNumber, docDescription, docRev, docType, "", nextReviewDate,
                drrNumber, doEmails, attachmentBytes, attachmentFilename,
                fileCount, totalBytes, doInactive, java.util.Collections.emptyList(), null);
    }

    /** Back-compat (no lifecycle / no greeting name). */
    public Payload payloadForDoEmail(String docNumber, String docDescription, String docRev,
                                     String docType, String nextReviewDate, String drrNumber,
                                     List<String> doEmails, byte[] attachmentBytes,
                                     String attachmentFilename, int fileCount, long totalBytes,
                                     boolean doInactive, List<String> inactiveOwnerLabels) {
        return payloadForDoEmail(docNumber, docDescription, docRev, docType, "", nextReviewDate,
                drrNumber, doEmails, attachmentBytes, attachmentFilename,
                fileCount, totalBytes, doInactive, inactiveOwnerLabels, null);
    }

    /** Back-compat (no greeting name) — keeps the prior 14-arg signature
     *  that callers without LDAP display-name access can still use. */
    public Payload payloadForDoEmail(String docNumber, String docDescription, String docRev,
                                     String docType, String docLifecycle,
                                     String nextReviewDate, String drrNumber,
                                     List<String> doEmails, byte[] attachmentBytes,
                                     String attachmentFilename, int fileCount, long totalBytes,
                                     boolean doInactive, List<String> inactiveOwnerLabels) {
        return payloadForDoEmail(docNumber, docDescription, docRev, docType, docLifecycle,
                nextReviewDate, drrNumber, doEmails, attachmentBytes, attachmentFilename,
                fileCount, totalBytes, doInactive, inactiveOwnerLabels, null);
    }

    /** Canonical builder. {@code inactiveOwnerLabels} is non-empty when one
     *  or more DOs were detected inactive in LDAP — their display names are
     *  surfaced in the red banner so DCC knows exactly who to reassign.
     *  {@code docLifecycle} is the lifecycle phase (e.g. "ACT") — separate
     *  from {@code docType} which is the doc subclass (e.g. "Document").
     *  {@code firstOwnerDisplayName} (nullable) is the LDAP/Agile-resolved
     *  full name of the primary recipient — used for the greeting. When null,
     *  falls back to title-cased email handle (e.g. "vikas.jindal" → "Vikas"). */
    public Payload payloadForDoEmail(String docNumber, String docDescription, String docRev,
                                     String docType, String docLifecycle,
                                     String nextReviewDate, String drrNumber,
                                     List<String> doEmails, byte[] attachmentBytes,
                                     String attachmentFilename, int fileCount, long totalBytes,
                                     boolean doInactive, List<String> inactiveOwnerLabels,
                                     String firstOwnerDisplayName) {
        Payload p = new Payload();
        p.templateName = "ims-review-do";

        boolean cap = (totalBytes > ATTACH_LIMIT_BYTES);
        boolean overdue = isOverdue(nextReviewDate);
        long days = daysFromTodayTo(nextReviewDate);

        p.subject = (overdue ? "[Overdue] " : "") + "IMS Document Review — " + docNumber
                + " — " + nextReviewDate;
        p.overdue = overdue;

        if (doInactive) {
            // Inactive DO: To: managers DL, Cc: DCC DL (de-dup if same value)
            p.to.add(managersDl);
            addCcDedup(p, dccDl);
        } else {
            p.to.addAll(doEmails);
            addCcDedup(p, managersDl);
            addCcDedup(p, dccDl);
        }

        Map<String, String> v = p.vars;
        // Render docNumber + drrNumber as clickable Agile webclient links so
        // recipients can jump straight to the doc / change request without
        // hunting through Agile. Plain text fallback if blank.
        v.put("docNumber", docNumber == null || docNumber.isEmpty() ? "" : agileLinkItem(docNumber));
        v.put("docDescription", nvl(docDescription));
        v.put("docRev", nvl(docRev));
        v.put("docType", nvl(docType));
        v.put("docLifecycle", nvl(docLifecycle));
        v.put("nextReviewDate", nvl(nextReviewDate));
        v.put("drrNumber", drrNumber == null || drrNumber.isEmpty() ? "" : agileLinkChange(drrNumber));
        // Raw Agile deep-link for the "manage the DRR directly within Agile" link
        // under the Submit-your-response button. Falls back to the doc if no DRR.
        v.put("drrAgileUrl", drrNumber == null || drrNumber.isEmpty()
                ? agileUrlChange(docNumber) : agileUrlChange(drrNumber));
        v.put("serviceNowFooter", serviceNowFooterHtml());
        // "Due In" vs "Status" + the badge contents change between states.
        // Overdue: badge says "past due" (calm pill, not screaming day count);
        //          row label flips to "Status" so we're not asking "how
        //          overdue?" again on the same row.
        // Due-soon: keep the "N days" countdown — that's useful planning info.
        v.put("dueInLabel", overdue ? "Status" : "Due in");
        v.put("dueInDaysLabel", overdue
                ? "Past due &middot; Overdue since " + nvl(nextReviewDate)
                : (days + " day" + (days == 1 ? "" : "s")));
        v.put("dueInBadgeClass", overdue ? "red" : "amber");
        v.put("dueInBadgeStyle", overdue
                ? "background:#fdeaea; color:#B8342B;"
                : "background:#fff3cd; color:#856404;");
        // Recipient + link-expiry rows (PT-82 / 2026-06-05). The response page
        // already surfaces these; Jimmy asked for the email body to match the
        // response page exactly so the two layouts stay synced. recipientEmail
        // is the human-readable "To:" address — for multi-owner sends, render
        // the full list joined by "; " so each DO knows the link landed in
        // every owner's inbox.
        StringBuilder recipientStr = new StringBuilder();
        if (doEmails != null) {
            for (int i = 0; i < doEmails.size(); i++) {
                if (i > 0) recipientStr.append("; ");
                recipientStr.append(esc(doEmails.get(i)));
            }
        }
        v.put("recipientEmail", recipientStr.length() == 0 ? "(unknown)" : recipientStr.toString());
        // Document Owner — shown in the trimmed summary table of the single-CTA
        // email. Strip any "(systemId)" suffix per the email design rules
        // (show "Zhu, Peter", not "Zhu, Peter (14759)").
        String ownerDisplay = firstOwnerDisplayName != null && !firstOwnerDisplayName.trim().isEmpty()
                ? firstOwnerDisplayName.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim()
                : (recipientStr.length() == 0 ? "(unknown)" : recipientStr.toString());
        v.put("documentOwner", ownerDisplay);
        // Token TTL is tokenTtlDays from send; the email is sent fresh so the
        // full window is always correct at the moment the recipient opens it.
        // We don't try to be clever about the actual remaining time because
        // we'd have to recompute on every render and a fresh-send window is
        // the honest answer anyway.
        v.put("linkExpiresIn", tokenTtlDays + " days");
        v.put("importantPill", overdue
                ? "<span style=\"background:#f8d7da; color:#721c24; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;\">&#x1F6A9; Past due</span>"
                : "<span style=\"background:#e8f0fe; color:#1a3a5c; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;\">Action requested</span>");
        // Headline asks the question — same wording in both states so the
        // owner's mental model is "review the doc, pick an answer" no matter
        // when the email arrives.
        v.put("statusHeadline", "Confirm this document is still accurate");
        v.put("footerCopy", overdue
                ? "<strong>Past the review deadline.</strong> Please respond as soon as you can to clear the compliance flag."
                : "<strong>Please respond within 15 days</strong> to keep this document's compliance status current.");
        v.put("footerStyle", overdue
                ? "background:#fdeaea; border-left:4px solid #B8342B; color:#721c24;"
                : "background:#e8f5e9; border-left:4px solid #1F8A4C; color:#1a4d2a;");
        v.put("buttonStyle", overdue
                ? "background:#B8342B; color:#fff;"
                : "background:#2c3e50; color:#fff;");
        // File section — show the actual attached filename when we have one
        // (the file IS attached to this email; calling it out by name makes
        // it scannable and clickable in most mail clients).
        String fileSection;
        if (attachmentBytes != null && !cap) {
            if (fileCount == 1 && attachmentFilename != null && !attachmentFilename.isEmpty()) {
                fileSection = "<a href=\"#\" style=\"color:#4a6fa5; font-weight:600; text-decoration:none;\">&#128206; "
                        + esc(attachmentFilename) + "</a>"
                        + " <span style=\"color:#6B7280;\">(" + humanReadable(totalBytes) + ", attached)</span>";
            } else {
                fileSection = "<span style=\"color:#4a6fa5; font-weight:600;\">&#128206; " + fileCount
                        + " files attached</span> <span style=\"color:#6B7280;\">(" + humanReadable(totalBytes) + ")</span>";
            }
        } else if (cap) {
            fileSection = "<span style=\"color:#C7801B;\">Too large to attach (&gt;10&nbsp;MB). Open the document in Agile to review.</span>";
        } else {
            fileSection = "<span style=\"color:#C7801B;\">No attachment available &mdash; open the document in Agile to review.</span>";
        }
        v.put("fileSection", fileSection);
        String inactiveNamesHtml = "";
        if (inactiveOwnerLabels != null && !inactiveOwnerLabels.isEmpty()) {
            StringBuilder sb = new StringBuilder(" The following Document Owner");
            sb.append(inactiveOwnerLabels.size() == 1 ? " is " : "s are ");
            sb.append("no longer active in LDAP: <strong>");
            for (int i = 0; i < inactiveOwnerLabels.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(esc(inactiveOwnerLabels.get(i)));
            }
            sb.append("</strong>.");
            inactiveNamesHtml = sb.toString();
        }
        v.put("doInactiveBanner", doInactive
                ? "<div style=\"background:#fdeaea; border-left:4px solid #B8342B; border-radius:0 6px 6px 0; padding:10px 14px; margin:0 20px 12px; font-size:12.5px; color:#721c24;\">"
                + "<strong>Document Owner for this IMS Document is no longer with SanDisk.</strong>"
                + inactiveNamesHtml
                + " Please reassign the Owner in Agile and trigger the notification from the DRR Dashboard.</div>"
                : "");
        // Legacy in-tab URL — kept for fallback templates / back-compat.
        // The per-action HTTPS-tokenized URLs are stamped in send() AFTER
        // the caller sets p.token. Doing it here would lose the token because
        // p is just being constructed (caller sets the token on the returned
        // payload, then calls send()).
        v.put("responseUrl", toolkitBaseUrl + "/index.html?tab=ims-review&asDO=true");

        // Greeting — prefer the LDAP-resolved display name if the caller
        // supplied one (parse "Last, First" → "First" for friendliness).
        // Fall back to title-cased email handle ("vikas.jindal" → "Vikas")
        // so we never render the raw handle.
        String greeting = "team";
        if (firstOwnerDisplayName != null && !firstOwnerDisplayName.trim().isEmpty()) {
            String trimmed = firstOwnerDisplayName.trim();
            int comma = trimmed.indexOf(',');
            if (comma >= 0 && comma + 1 < trimmed.length()) {
                // "Jindal, Vikas" → "Vikas"
                String first = trimmed.substring(comma + 1).trim();
                if (!first.isEmpty()) greeting = first.split("\\s+")[0];
            } else {
                // Single token or "First Last"
                greeting = trimmed.split("\\s+")[0];
            }
        } else if (!doEmails.isEmpty()) {
            String handle = doEmails.get(0).split("@")[0];
            // "vikas.jindal" → "Vikas"
            String firstSeg = handle.split("\\.")[0];
            if (!firstSeg.isEmpty()) {
                greeting = Character.toUpperCase(firstSeg.charAt(0))
                        + (firstSeg.length() > 1 ? firstSeg.substring(1) : "");
            }
        }
        v.put("greetingName", greeting);

        if (!cap && attachmentBytes != null) {
            p.attachmentBytes = attachmentBytes;
            p.attachmentFilename = attachmentFilename;
        }
        return p;
    }

    /** Build a payload for the DM second-stage email (after DO No Change).
     *  The DO is auto-added to Cc so they can see their no-change response is now
     *  with the manager (per CRD: DO/DM both stay in the loop until DRR closes). */
    public Payload payloadForDmEmail(String docNumber, String docDescription, String docRev,
                                     String docType, String docLifecycle,
                                     String nextReviewDate, String drrNumber,
                                     String dmEmail, String doEmail,
                                     String doDisplayName, String doResponseDate,
                                     byte[] attachmentBytes, String attachmentFilename,
                                     int fileCount, long totalBytes, boolean dmFallback) {
        Payload p = payloadForDoEmail(docNumber, docDescription, docRev, docType, docLifecycle,
                nextReviewDate, drrNumber, Collections.singletonList(dmEmail),
                attachmentBytes, attachmentFilename, fileCount, totalBytes, false,
                java.util.Collections.emptyList());
        p.templateName = "ims-review-dm";
        p.subject = "[Manager Approval] " + p.subject;
        // Add DO to Cc so they see the next-step email
        if (doEmail != null && !doEmail.trim().isEmpty()) {
            String norm = doEmail.trim().toLowerCase();
            boolean already = false;
            for (String t : p.to) if (t.equalsIgnoreCase(norm)) { already = true; break; }
            for (String c : p.cc) if (c.equalsIgnoreCase(norm)) { already = true; break; }
            if (!already) p.cc.add(norm);
        }
        // Headline asks the question — DM sees "one approval to close this
        // review" so the ask is unambiguous (matches the redesigned email).
        p.vars.put("statusHeadline", "One approval to close this review");
        p.vars.put("doDisplayName", nvl(doDisplayName));
        p.vars.put("doResponseDate", nvl(doResponseDate));
        if (dmFallback) {
            p.vars.put("dmFallbackBanner",
                    "<div style=\"background:#fff8e1; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; padding:10px 14px; margin:0 20px 12px; font-size:12.5px; color:#5a4a1f;\">"
                    + "<strong>Document Owner's manager could not be resolved from LDAP.</strong> "
                    + "Please reassign the Owner in Agile, or notify the correct manager manually.</div>");
        } else {
            p.vars.put("dmFallbackBanner", "");
        }
        return p;
    }

    /** Render + dispatch. Handles the local-redirect wrap.
     *
     *  <p>Account-status footer (the ServiceNow nudge) is personalized PER
     *  recipient. If at least one To recipient is missing or disabled in
     *  agileuser, we split into per-recipient sends so each owner sees only
     *  their own status note. Active recipients see no footer at all.
     *  Cc list rides only on the first send to avoid Cc duplication. */
    public void send(Payload p) {
        try {
            // Always Cc the DCC Agile DL (and any other always-cc addresses) on
            // every IMS review-flow email. Added here (before the personalized
            // split) so it rides the first send only — no per-recipient dupes.
            if (alwaysCc != null) {
                for (String a : alwaysCc.split(",")) addCcDedup(p, a.trim());
            }
            // Probe statuses up front; decide bulk-vs-split based on whether
            // anyone has a non-empty per-recipient footer.
            boolean anyNeedsFooter = false;
            for (String to : p.to) {
                if (!accountStatusFooterFor(to).isEmpty()) { anyNeedsFooter = true; break; }
            }
            if (anyNeedsFooter && p.to.size() > 0) {
                List<String> originalTo = new ArrayList<>(p.to);
                List<String> originalCc = new ArrayList<>(p.cc);
                for (int i = 0; i < originalTo.size(); i++) {
                    String recipient = originalTo.get(i);
                    p.to = java.util.Collections.singletonList(recipient);
                    p.cc = (i == 0) ? originalCc : java.util.Collections.<String>emptyList();
                    if (p.vars != null) {
                        p.vars.put("accountStatusFooter", accountStatusFooterFor(recipient));
                    }
                    sendOne(p);
                }
                // Restore the payload state so any reuse downstream sees the
                // original recipients (the toolkit usually doesn't reuse, but
                // be safe).
                p.to = originalTo; p.cc = originalCc;
                if (p.vars != null) p.vars.remove("accountStatusFooter");
                return;
            }
            // Single bulk send — all recipients are ACTIVE (or status unknown).
            if (p.vars != null) p.vars.put("accountStatusFooter", "");
            sendOne(p);
        } catch (Exception e) {
            LOG.warning("[IMS-MAIL] send failed: " + e.getMessage());
            throw new RuntimeException("IMS Review email send failed: " + e.getMessage(), e);
        }
    }

    /** Original send-one logic — does the template render + SMTP transport
     *  for one (to, cc, vars) tuple. Called by {@link #send(Payload)} once
     *  for the bulk case and once-per-recipient for the personalized case. */
    private void sendOne(Payload p) {
        try {
            // Stamp per-action HTTPS URLs LAST (after the caller has set
            // p.token). The payload builders can't do this themselves
            // because token is set on the returned Payload by the caller.
            if (p.vars != null) {
                // Consolidated DO link — one "Submit your response" button; the
                // recipient picks No Change / Needs Change / Need Help on the page
                // (PT, Vikas Singh 2026-06-16: showing the 3 options in BOTH the
                // email and the page was redundant). Legacy per-option URLs kept
                // for any template still referencing them.
                p.vars.put("responseUrl",         respondUrl(p.token, null));
                p.vars.put("responseUrlNoChange", respondUrl(p.token, "NO_CHANGE"));
                p.vars.put("responseUrlUpload",   respondUrl(p.token, "UPLOAD"));
                p.vars.put("responseUrlHelp",     respondUrl(p.token, "HELP"));
                p.vars.put("responseUrlApprove",  respondUrl(p.token, "DM_APPROVE"));
                p.vars.put("responseUrlSendBack", respondUrl(p.token, "DM_SEND_BACK"));
                // 2026-06-03: thread the site's red SanDisk logo through to
                // the footer so every IMS Review email uses the same brand
                // mark the in-app header does (replaces the lowercase
                // "sandisk" text pill). Asset lives at /sandisk-logo-red.png
                // on the toolkit; emails reference it by absolute URL.
                p.vars.putIfAbsent("logoUrl", toolkitBaseUrl + "/sandisk-logo-red.png");
                p.vars.putIfAbsent("trainingDocUrl", trainingDocUrl == null ? "" : trainingDocUrl);
            }

            // Resolve template
            String html = renderTemplate(p.templateName, p.vars);

            // Non-prod instance badge — splice a TEST banner at the top so the
            // recipient sees this came from a test box. Driven by app.instance.label.
            if (instanceLabel != null && !instanceLabel.trim().isEmpty()) {
                String tb = "<div style=\"background:#C7801B; color:#fff; padding:8px 14px; "
                        + "margin:0 0 12px; font-size:13px; font-weight:600; border-radius:4px;\">"
                        + "&#x26A0; " + esc(instanceLabel.trim()) + " &mdash; this is a non-production test message."
                        + "</div>";
                html = injectBanner(html, tb);
            }

            // Local-test redirect — splice a banner in and swap recipients
            boolean redirected = redirectTo != null && !redirectTo.trim().isEmpty();
            if (redirected) {
                String origTo = String.join(", ", p.to);
                String origCc = String.join(", ", p.cc);
                String banner = "<div style=\"background:#fff3cd; border:1px solid #ffeeba; "
                        + "color:#856404; padding:10px 14px; margin:0 0 12px; font-size:13px;\">"
                        + "&#x26A0; <strong>Local test mode</strong> &mdash; would have gone to: "
                        + "<code>" + esc(origTo) + "</code>; Cc: <code>" + esc(origCc) + "</code>"
                        + "</div>";
                html = injectBanner(html, banner);
                p.to = Collections.singletonList(redirectTo.trim());
                p.cc = Collections.emptyList();
                if (!p.subject.contains("[LOCAL TEST]")) p.subject = p.subject + " [LOCAL TEST]";
            }

            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            Session session = Session.getInstance(props);
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(fromAddr));
            msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(p.subject), "UTF-8");

            for (String t : p.to) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(t));
            for (String c : p.cc) if (!p.to.contains(c)) msg.addRecipient(Message.RecipientType.CC, new InternetAddress(c));

            if (p.overdue && !redirected) {
                msg.setHeader("X-Priority", "1");
                msg.setHeader("Importance", "High");
            }

            boolean hasPrimary = p.attachmentBytes != null && p.attachmentFilename != null;
            boolean hasExtras  = p.extraAttachments != null && !p.extraAttachments.isEmpty();
            if (hasPrimary || hasExtras) {
                MimeMultipart mp = new MimeMultipart();
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(html, "text/html; charset=UTF-8");
                mp.addBodyPart(htmlPart);
                if (hasPrimary) {
                    MimeBodyPart filePart = new MimeBodyPart();
                    ByteArrayDataSource bds = new ByteArrayDataSource(p.attachmentBytes, "application/octet-stream");
                    filePart.setDataHandler(new DataHandler(bds));
                    filePart.setFileName(p.attachmentFilename);
                    mp.addBodyPart(filePart);
                }
                if (hasExtras) {
                    for (NamedBlob nb : p.extraAttachments) {
                        if (nb == null || nb.bytes == null || nb.filename == null) continue;
                        MimeBodyPart part = new MimeBodyPart();
                        ByteArrayDataSource bds = new ByteArrayDataSource(nb.bytes,
                                nb.mimeType == null ? "application/octet-stream" : nb.mimeType);
                        part.setDataHandler(new DataHandler(bds));
                        part.setFileName(nb.filename);
                        mp.addBodyPart(part);
                    }
                }
                msg.setContent(mp);
            } else {
                msg.setContent(html, "text/html; charset=UTF-8");
            }

            Transport.send(msg);
            LOG.info("[IMS-MAIL] sent " + p.templateName + " to=" + p.to + " cc=" + p.cc
                    + (redirected ? " (redirected)" : ""));
        } catch (Exception e) {
            LOG.warning("[IMS-MAIL] sendOne failed: " + e.getMessage());
            throw new RuntimeException("IMS Review email sendOne failed: " + e.getMessage(), e);
        }
    }

    /** Convenience overload for the DCC-bound "Needs Change" + "Need Help" emails. */
    public Payload payloadForDccEmail(String templateName, String subject, String docNumber,
                                      String docDescription, String drrNumber,
                                      String doDisplayName, String doEmail, String note,
                                      byte[] uploadedFile, String uploadedFilename) {
        Payload p = new Payload();
        p.templateName = templateName;
        p.subject = subject;
        p.to.add(dccDl);
        addCcDedup(p, managersDl);
        if (doEmail != null && !doEmail.isEmpty()) addCcDedup(p, doEmail);

        Map<String, String> v = p.vars;
        v.put("docNumber", agileLinkItem(docNumber));
        v.put("docDescription", nvl(docDescription));
        v.put("drrNumber", agileLinkChange(drrNumber));
        v.put("doDisplayName", nvl(doDisplayName));
        v.put("doEmail", nvl(doEmail));
        v.put("noteHtml", note == null || note.isEmpty()
                ? "<em style=\"color:#6B7280;\">No additional notes.</em>"
                : "<div style=\"white-space:pre-wrap;\">" + esc(note) + "</div>");
        v.put("uploadedFileLabel", uploadedFilename == null ? "(none)" : uploadedFilename);
        // asDO=true flips the IMS Review tab into the DO/DM card view even for
        // admins / DCC users when they're also the recipient on this row.
        v.put("responseUrl", toolkitBaseUrl + "/index.html?tab=ims-review&asDO=true");

        if (uploadedFile != null && uploadedFile.length > 0) {
            p.attachmentBytes = uploadedFile;
            p.attachmentFilename = uploadedFilename;
        }
        return p;
    }

    /** Read template from classpath and substitute variables. */
    private String renderTemplate(String name, Map<String, String> vars) throws IOException {
        String path = "/templates/email/" + name + ".html";
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Template not found: " + path);
            byte[] bytes = in.readAllBytes();
            String tpl = new String(bytes, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> e : vars.entrySet()) {
                tpl = tpl.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
            // Drop any unresolved placeholders so we don't leak ${var} into rendered HTML
            tpl = tpl.replaceAll("\\$\\{[a-zA-Z0-9_]+\\}", "");
            return tpl;
        }
    }

    private static String injectBanner(String html, String banner) {
        // Insert just inside the first <div> wrapper if we can find one; otherwise prepend.
        int idx = html.indexOf("<body");
        if (idx >= 0) {
            int gt = html.indexOf(">", idx);
            if (gt > 0) return html.substring(0, gt + 1) + banner + html.substring(gt + 1);
        }
        return banner + html;
    }

    private static boolean isOverdue(String iso) {
        try { return LocalDate.parse(iso).isBefore(LocalDate.now()); }
        catch (Exception e) { return false; }
    }

    private static long daysFromTodayTo(String iso) {
        try { return ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(iso)); }
        catch (Exception e) { return 0L; }
    }

    private static String humanReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    /** Add an address to p.cc only if not already present (case-insensitive).
     *  Lets both managersDl and dccDl point at the same DL (the default until
     *  the real DLs ship) without producing a duplicate Cc header. */
    private static void addCcDedup(Payload p, String addr) {
        if (addr == null || addr.trim().isEmpty()) return;
        String norm = addr.trim().toLowerCase();
        for (String c : p.cc) if (c != null && c.trim().equalsIgnoreCase(norm)) return;
        p.cc.add(norm);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ==================================================================
    // Phase 5 — DCO form stakeholder notifications
    // ==================================================================

    /** Send the stakeholder notification email after a successful DCO submit.
     *  To = NotifyStakeholders \ {submitter} (the stakeholders being notified);
     *  if that's empty, the roster is promoted to To so the message isn't Cc-only.
     *  Cc = DocumentOwners ∪ Approvers ∪ Observers (the form roster) + managers/DCC DLs
     *  + submitter + canonical doc-owners (ctx.allowedActors), all minus anyone in To.
     *  Signed attestation PDF attached. Returns the count of unique To recipients. */
    public int sendStakeholderNotify(ImsReviewService.TokenContext ctx,
                                     LdapAuthService.VerifyResult v,
                                     java.util.Map<String, Object> form,
                                     String dcoNumber, String newRev,
                                     int attachmentsCount,
                                     byte[] attestationPdfBytes) {
        Payload p = new Payload();
        p.templateName = "ims-review-dco-stakeholder-notify";

        // Recipient routing (Vikas 2026-06-23): the email goes TO the stakeholders
        // being notified; the internal roster picked on the form — Document Owners,
        // Approvers, Observers — goes in Cc so they stay in the loop without being
        // the primary addressees.
        String submitter = (v == null || v.email == null) ? "" : v.email.trim().toLowerCase();

        java.util.Set<String> to = new java.util.LinkedHashSet<>();
        addAllLower(to, form.get("notifyStakeholders"));
        if (!submitter.isEmpty()) to.remove(submitter);

        // Internal roster → Cc.
        java.util.Set<String> roster = new java.util.LinkedHashSet<>();
        addAllLower(roster, form.get("documentOwners"));
        addAllLower(roster, form.get("approvers"));
        addAllLower(roster, form.get("observers"));
        if (!submitter.isEmpty()) roster.remove(submitter);

        // If no stakeholder email addresses were given (e.g. the responder only
        // uploaded an email-copy proof file), promote the roster to To so the
        // message still has a primary recipient instead of going Cc-only.
        if (to.isEmpty()) {
            to.addAll(roster);
            roster.clear();
        }
        p.to = new java.util.ArrayList<>(to);

        // Document Owners / Approvers / Observers in Cc (skip anyone already in To).
        for (String e : roster) {
            boolean inTo = false;
            for (String t : p.to) if (e.equalsIgnoreCase(t)) { inTo = true; break; }
            if (!inTo) addCcDedup(p, e);
        }
        addCcDedup(p, managersDl);
        addCcDedup(p, dccDl);
        // 2026-06-03: the submitter (= the DO who signed the DCO form) used
        // to be silently dropped from the email so they wouldn't see their
        // own send. Vikas asked for the Document Owner to always be copied
        // on IMS Review emails, so we re-add them to Cc instead. Idempotent
        // via addCcDedup; no-op when the submitter is already in To.
        if (!submitter.isEmpty()) {
            boolean inTo = false;
            for (String t : p.to) if (submitter.equalsIgnoreCase(t)) { inTo = true; break; }
            if (!inTo) addCcDedup(p, submitter);
        }
        // 2026-06-08: also Cc every Document Owner who originally received
        // the IMS Review SEND (= the canonical doc-owner roster from Agile
        // at the time the review was opened). The form's "documentOwners"
        // field captures who the FORM-FILLER picked, which doesn't always
        // include the full current roster (e.g. when only one DO of a
        // multi-DO doc redeems and doesn't add the others). Ensures the
        // owners of record stay in the loop on every [New DCO] regardless
        // of what the form-filler typed. Idempotent via addCcDedup.
        if (ctx != null && ctx.allowedActors != null) {
            for (String docOwnerEmail : ctx.allowedActors) {
                if (docOwnerEmail == null) continue;
                String e = docOwnerEmail.trim().toLowerCase();
                if (e.isEmpty()) continue;
                boolean inTo = false;
                for (String t : p.to) if (e.equalsIgnoreCase(t)) { inTo = true; break; }
                if (!inTo) addCcDedup(p, e);
            }
        }

        String prio = String.valueOf(form.getOrDefault("priority", "Standard"));
        p.subject = "[New DCO] " + nvl(dcoNumber) + " · " + nvl(ctx == null ? "" : ctx.docNumber) + " · " + prio;

        java.util.Map<String, String> vars = p.vars;
        vars.put("dcoNumber", agileLinkChange(dcoNumber));
        vars.put("docNumber", agileLinkItem(ctx == null ? null : ctx.docNumber));
        vars.put("drrNumber", agileLinkChange(ctx == null ? null : ctx.drrNumber));
        vars.put("serviceNowFooter", serviceNowFooterHtml());
        vars.put("newRev", nvl(newRev));
        vars.put("priority", prio);
        vars.put("descriptionOfChange", String.valueOf(form.getOrDefault("descriptionOfChange", "")));
        vars.put("reasonForChange", String.valueOf(form.getOrDefault("reasonForChange", "")));
        vars.put("productLines", joinList(form.get("productLines")));
        vars.put("subcontractors", joinList(form.get("subcontractors")));
        vars.put("trainingRequirement", String.valueOf(form.getOrDefault("trainingRequirement", "")));
        vars.put("businessUnit", String.valueOf(form.getOrDefault("businessUnit", "")));
        vars.put("changeImpactDisposition", String.valueOf(form.getOrDefault("changeImpactDisposition", "")));
        vars.put("changeImpactDetails", String.valueOf(form.getOrDefault("changeImpactDetails", "")));
        vars.put("documentOwners", joinList(form.get("documentOwners")));
        vars.put("approvers", joinList(form.get("approvers")));
        vars.put("observers", joinList(form.get("observers")));
        vars.put("notifyStakeholders", joinList(form.get("notifyStakeholders")));
        vars.put("attachmentsCount", String.valueOf(attachmentsCount));
        vars.put("signedBy", nvl(v == null ? null : v.displayName));
        vars.put("signedEmail", nvl(v == null ? null : v.email));
        vars.put("submittedAt", java.time.Instant.now().toString());

        if (attestationPdfBytes != null && attestationPdfBytes.length > 0) {
            p.extraAttachments.add(new NamedBlob(
                    "ims-attestation-" + nvl(dcoNumber) + ".pdf",
                    attestationPdfBytes, "application/pdf"));
        }
        send(p);
        return to.size();
    }

    /** DCC alert for create-dco-rich failure. Inline HTML — no template,
     *  this only ever goes to internal Doc Control. Best-effort; failure
     *  swallowed since the toolkit already logged the original DCO failure. */
    public void sendDcoFailedAlert(ImsReviewService.TokenContext ctx,
                                   LdapAuthService.VerifyResult v,
                                   AgileWriteBackClient.Result cr) {
        try {
            String stepFailed = "(unknown)";
            String orphan = "";
            if (cr != null && cr.body != null) {
                Object sf = cr.body.get("stepFailedAt");
                if (sf != null) stepFailed = sf.toString();
                Object od = cr.body.get("orphanDco");
                if (od != null) orphan = od.toString();
            }
            String body = "<html><body style=\"font-family:'IBM Plex Sans',sans-serif; color:#0F1720;\">"
                + "<h2 style=\"font-family:'IBM Plex Serif',serif;color:#B8342B;\">IMS Dashboard — DCO creation failed</h2>"
                + "<p>The toolkit could not create the DCO for the Document Owner Needs-Change submission. The Document Owner's signed attestation PDF is still on file in queue.jsonl.</p>"
                + "<table cellpadding=\"6\" style=\"border-collapse:collapse;\">"
                + tr("Doc", ctx == null ? "" : ctx.docNumber)
                + tr("DRR", ctx == null ? "" : ctx.drrNumber)
                + tr("Document Owner", (v == null ? "" : nvl(v.displayName)) + " &lt;" + (v == null ? "" : nvl(v.email)) + "&gt;")
                + tr("Correlation ID", cr == null ? "" : nvl(cr.corrId))
                + tr("Failed at step", stepFailed)
                + tr("Error", cr == null || cr.errorReason == null ? "(none)" : cr.errorReason)
                + (orphan.isEmpty() ? "" : tr("Orphan DCO", orphan + " — may exist in Agile, manual cleanup required"))
                + "</table>"
                + "<p style=\"color:#6B7280;font-size:11px;\">Search the plm-agile-service log for <code>corrId="
                + (cr == null ? "" : nvl(cr.corrId)) + "</code> to see every step that ran.</p>"
                + "</body></html>";
            sendAlert("[IMS Dashboard] DCO creation FAILED — " + (ctx == null ? "" : nvl(ctx.docNumber)), body);
        } catch (Exception ignored) {
            // Alert send is best-effort — caller already logged the real failure.
        }
    }

    /** DCC alert for stakeholder-notification SMTP failure. DCO is real in
     *  Agile by the time we get here; this just tells DCC the notify pass
     *  needs to be resent manually. */
    public void sendStakeholderNotifyFailedAlert(ImsReviewService.TokenContext ctx,
                                                 String dcoNumber,
                                                 java.util.Map<String, Object> form,
                                                 Throwable err) {
        try {
            String body = "<html><body style=\"font-family:'IBM Plex Sans',sans-serif;\">"
                + "<h2 style=\"color:#C7801B;\">Stakeholder notification failed</h2>"
                + "<p>DCO <strong style=\"color:#4a6fa5;\">" + esc(nvl(dcoNumber)) + "</strong> was created OK in Agile, but the toolkit "
                + "couldn't deliver the stakeholder notification email.</p>"
                + "<p><strong>Recipients that should have been notified:</strong></p>"
                + "<pre style=\"background:#FAFAF7;padding:8px;border-radius:4px;font-size:11px;\">"
                + esc(joinList(form == null ? null : form.get("notifyStakeholders"))) + "\n"
                + esc(joinList(form == null ? null : form.get("documentOwners"))) + "\n"
                + esc(joinList(form == null ? null : form.get("approvers"))) + "\n"
                + esc(joinList(form == null ? null : form.get("observers"))) + "</pre>"
                + "<p>Error: <code>" + esc(err == null ? "(unknown)" : err.getMessage()) + "</code></p>"
                + "</body></html>";
            sendAlert("[IMS Dashboard] Stakeholder notify failed — " + nvl(dcoNumber), body);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Phase 5 helpers
    /** Public wrapper around {@link #sendAlert} for non-IMS-Review callers
     *  (e.g. the retry-runner give-up alert) — exposes the same dccDl /
     *  redirect-to-local rules without forcing the caller to know SMTP.
     *  Best-effort: any error is logged + thrown so the caller can decide. */
    public void sendAdminAlertHtml(String subject, String htmlBody) throws Exception {
        sendAlert(subject, htmlBody);
    }

    // ------------------------------------------------------------------
    private void sendAlert(String subject, String htmlBody) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromAddr));
        boolean redirected = redirectTo != null && !redirectTo.trim().isEmpty();
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject + (redirected ? " [LOCAL TEST]" : "")), "UTF-8");
        String to = redirected ? redirectTo : dccDl;
        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setContent(htmlBody, "text/html; charset=UTF-8");
        Transport.send(msg);
        LOG.info("[IMS-MAIL] sent alert to=" + to + " subject=\"" + subject + "\""
                + (redirected ? " (redirected)" : ""));
    }

    /** Extract email addresses from a list field. Handles both shapes:
     *  - List of strings (e.g. notifyStakeholders) — each string treated as email
     *  - List of maps {loginId, displayName, email} (the user-picker fields)
     *  Lower-cases for de-dup; empty/null entries dropped. */
    private static final java.util.regex.Pattern EMAIL_RE =
            java.util.regex.Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** Extract a clean email address from a free-form string (e.g. an Outlook
     *  "Last, First &lt;addr&gt;" paste). Falls back to the trimmed input when no
     *  address pattern is present. */
    private static String extractEmail(String raw) {
        if (raw == null) return "";
        java.util.regex.Matcher m = EMAIL_RE.matcher(raw);
        return m.find() ? m.group() : raw.trim();
    }

    private static void addAllLower(java.util.Set<String> out, Object listObj) {
        if (!(listObj instanceof java.util.List)) return;
        for (Object o : (java.util.List<?>) listObj) {
            if (o == null) continue;
            String email = "";
            if (o instanceof java.util.Map) {
                Object e = ((java.util.Map<?, ?>) o).get("email");
                if (e != null) email = e.toString();
            } else {
                // Free-form string (notifyStakeholders) — may be an Outlook
                // "Name <addr>" paste. Extract the address so InternetAddress
                // doesn't choke on the display name / stray spaces.
                email = extractEmail(o.toString());
            }
            email = email.trim().toLowerCase();
            if (!email.isEmpty()) out.add(email);
        }
    }

    /** Human-readable rendering of a list field for inline display in the
     *  stakeholder notification email. User-picker shapes render as
     *  "Display Name &lt;email&gt;"; plain string lists render as-is. */
    private static String joinList(Object listObj) {
        if (!(listObj instanceof java.util.List)) {
            return listObj == null ? "" : listObj.toString();
        }
        java.util.List<?> list = (java.util.List<?>) listObj;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            Object o = list.get(i);
            if (o == null) continue;
            if (o instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                Object dn = m.get("displayName");
                Object em = m.get("email");
                if (dn != null && em != null) sb.append(dn).append(" <").append(em).append(">");
                else if (dn != null) sb.append(dn);
                else if (em != null) sb.append(em);
                else sb.append(m.get("loginId"));
            } else {
                sb.append(o.toString());
            }
        }
        return sb.toString();
    }

    private static String tr(String k, String v) {
        return "<tr><td style=\"color:#6B7280;font-weight:600;\">" + esc(k) + "</td><td>" + esc(v) + "</td></tr>";
    }

    // ------------------------------------------------------------------
    // Owner reassignment notification (2026-06-03)
    // ------------------------------------------------------------------

    /** Configurable Doc Control DL Cc'd on every owner-reassignment email. Separate from
     *  {@code app.ims-review.dcc-dl} so it can be retargeted without affecting the DO/DM
     *  cascade. Defaults to {@code pdl-plm-admin@sandisk.com}. */
    @Value("${app.ims-review.owner-reassign.cc-dl:pdl-plm-admin@sandisk.com}")
    public String ownerReassignCcDl;

    /** Optional kill-switch so the reassign notification can be muted without a redeploy
     *  (e.g. during bulk admin cleanup that would generate hundreds of emails). */
    @Value("${app.ims-review.owner-reassign.enabled:true}")
    public boolean ownerReassignEnabled;

    /** Lightweight DTO so the controller doesn't have to know about Jakarta MimeMessage.
     *  {@code ldapStatus} is mutable so {@link #sendOwnerReassignEmail} can freshen
     *  any missing values from a live LDAP probe right before composing the email
     *  — the docs cache may have UNKNOWN if the row was patched before background
     *  enrichment finished, and we want the "left SanDisk" copy to be accurate. */
    public static final class OwnerEntry {
        public final String displayName;
        public final String email;
        public final String loginId;
        /** ACTIVE / DISABLED / NOT_FOUND / UNKNOWN — drives the Cc-vs-skip
         *  decision in {@link #sendOwnerReassignEmail}. No longer surfaces in
         *  the email body — the bare name is shown regardless. */
        public String ldapStatus;
        public OwnerEntry(String displayName, String email, String loginId, String ldapStatus) {
            this.displayName = displayName; this.email = email; this.loginId = loginId; this.ldapStatus = ldapStatus;
        }
        boolean isActive() { return "ACTIVE".equalsIgnoreCase(ldapStatus); }
        boolean leftCompany() {
            return "NOT_FOUND".equalsIgnoreCase(ldapStatus) || "DISABLED".equalsIgnoreCase(ldapStatus);
        }
        boolean needsStatusProbe() {
            return ldapStatus == null || ldapStatus.isEmpty() || "UNKNOWN".equalsIgnoreCase(ldapStatus);
        }
        String label() {
            if (displayName != null && !displayName.isEmpty()) return displayName;
            if (email != null && !email.isEmpty()) return email;
            return loginId == null ? "(unknown)" : loginId;
        }
    }

    /**
     * Notify the new owners that they've been assigned to a doc, Cc the removed owners
     * that are still at SanDisk, Cc the Doc Control DL, and attach the doc's current
     * Agile attachment.
     *
     * <p>Newly-added owners (those present in {@code newOwners} but not in {@code oldOwners},
     * compared by loginId) go in the To line. Removed owners that are {@code ACTIVE} go in
     * the Cc line; removed owners that are {@code NOT_FOUND} / {@code DISABLED} are silently
     * skipped (no Cc, no bounce). The body lists removed owners by bare name only — HR-
     * sensitive "left SanDisk" badges/callouts were stripped 2026-06-05 per Vikas.
     *
     * <p>Caller responsibility: do the Agile write first; we email AFTER success. This
     * method is safe to call fire-and-forget — any failure is logged but never thrown.
     */
    public void sendOwnerReassignEmail(String docNumber, String docDescription, String drrNumber,
                                       String nextReviewDate, String reassignedByDisplay,
                                       List<OwnerEntry> oldOwners, List<OwnerEntry> newOwners,
                                       byte[] attachmentBytes, String attachmentFilename, long attachmentSize) {
        if (!ownerReassignEnabled) {
            LOG.info("[IMS-MAIL] owner-reassign disabled — skipping for doc=" + docNumber);
            return;
        }
        if (docNumber == null || docNumber.isEmpty()) return;
        if (oldOwners == null) oldOwners = Collections.emptyList();
        if (newOwners == null) newOwners = Collections.emptyList();

        // Freshen any old-owner status that the cache didn't have a definite
        // answer for. The docs-cache row might still have ldapStatus=UNKNOWN
        // if the row was patched between cold pull and background enrichment,
        // and we want the "left SanDisk" message in the email to reflect the
        // actual AD state at send time. Small set (typically 1-3 owners), so
        // a synchronous probe is fine on the executor thread.
        if (managerLookupForReassign != null) {
            for (OwnerEntry o : oldOwners) {
                if (!o.needsStatusProbe()) continue;
                if (o.email == null || o.email.isEmpty()) {
                    o.ldapStatus = LdapManagerLookup.LdapStatus.UNKNOWN.name();
                    continue;
                }
                try {
                    o.ldapStatus = managerLookupForReassign.checkUserStatus(o.email).name();
                } catch (Exception probeErr) {
                    LOG.warning("[IMS-MAIL] LDAP probe for reassign email failed for "
                            + o.email + ": " + probeErr.getMessage());
                }
            }
        }

        // Diff by loginId — the canonical owner identity.
        Set<String> newIds = new LinkedHashSet<>();
        for (OwnerEntry o : newOwners) if (o.loginId != null) newIds.add(o.loginId.trim());
        Set<String> oldIds = new LinkedHashSet<>();
        for (OwnerEntry o : oldOwners) if (o.loginId != null) oldIds.add(o.loginId.trim());
        List<OwnerEntry> addedOwners = new ArrayList<>();
        for (OwnerEntry o : newOwners) {
            if (o.loginId == null || !oldIds.contains(o.loginId.trim())) addedOwners.add(o);
        }
        List<OwnerEntry> removedOwners = new ArrayList<>();
        for (OwnerEntry o : oldOwners) {
            if (o.loginId == null || !newIds.contains(o.loginId.trim())) removedOwners.add(o);
        }

        // To = added owners with valid email. If nothing was added (e.g. owners reordered
        // or only removals happened) fall back to all current owners so somebody on the
        // active roster gets notified.
        List<String> toList = new ArrayList<>();
        for (OwnerEntry o : addedOwners) if (o.email != null && o.email.contains("@")) toList.add(o.email);
        if (toList.isEmpty()) {
            for (OwnerEntry o : newOwners) if (o.email != null && o.email.contains("@")) toList.add(o.email);
        }
        if (toList.isEmpty()) {
            // No recipient candidate at all — skip rather than send a dead-letter.
            LOG.info("[IMS-MAIL] owner-reassign skipped for doc=" + docNumber
                    + " — no resolvable email on the new owner roster");
            return;
        }

        // Cc = removed owners still ACTIVE + the configurable Doc Control DL.
        List<String> ccList = new ArrayList<>();
        for (OwnerEntry o : removedOwners) {
            if (o.isActive() && o.email != null && o.email.contains("@") && !toList.contains(o.email)) {
                ccList.add(o.email);
            }
        }
        if (ownerReassignCcDl != null && !ownerReassignCcDl.trim().isEmpty()
                && !toList.contains(ownerReassignCcDl) && !ccList.contains(ownerReassignCcDl)) {
            ccList.add(ownerReassignCcDl);
        }

        // Build vars.
        Payload p = new Payload();
        p.templateName = "ims-review-owner-reassigned";
        p.subject = "IMS Dashboard: you have been assigned as Document Owner for " + docNumber;
        p.to = toList;
        p.cc = ccList;

        String docAgileUrl = agileWebclientUrl + "/object/Part/"
                + java.net.URLEncoder.encode(docNumber.trim(), java.nio.charset.StandardCharsets.UTF_8);
        String drrAgileUrl = "";
        if (drrNumber != null && !drrNumber.trim().isEmpty()) {
            String dn = drrNumber.trim();
            int dash = dn.indexOf('-');
            String type = dash > 0 ? dn.substring(0, dash) : "Change";
            drrAgileUrl = agileWebclientUrl + "/object/"
                    + java.net.URLEncoder.encode(type, java.nio.charset.StandardCharsets.UTF_8)
                    + "/" + java.net.URLEncoder.encode(dn, java.nio.charset.StandardCharsets.UTF_8);
        }

        p.vars.put("docNumber", esc(docNumber));
        p.vars.put("docDescription", esc(docDescription == null ? "" : docDescription));
        p.vars.put("drrNumber", esc(drrNumber == null || drrNumber.isEmpty() ? "(no related DRR)" : drrNumber));
        p.vars.put("docAgileUrl", docAgileUrl);
        p.vars.put("drrAgileUrl", drrAgileUrl.isEmpty() ? docAgileUrl : drrAgileUrl);
        p.vars.put("nextReviewDate", esc(nextReviewDate == null || nextReviewDate.isEmpty() ? "(not set)" : nextReviewDate));
        p.vars.put("reassignedByDisplay", esc(reassignedByDisplay == null || reassignedByDisplay.isEmpty()
                ? "Doc Control" : reassignedByDisplay));
        p.vars.put("newOwnersList", buildOwnersListHtml(newOwners));
        p.vars.put("removedOwnersList", buildRemovedOwnersListHtml(removedOwners));
        p.vars.put("leftCompanyCallout", buildLeftCompanyCalloutHtml(removedOwners));
        p.vars.put("attachmentBlock", buildAttachmentBlockHtml(attachmentFilename, attachmentSize, attachmentBytes != null));
        p.vars.put("sentAt", esc(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new java.util.Date())));

        if (attachmentBytes != null && attachmentBytes.length > 0
                && attachmentFilename != null && !attachmentFilename.isEmpty()) {
            p.attachmentBytes = attachmentBytes;
            p.attachmentFilename = attachmentFilename;
        }

        try {
            send(p);
            LOG.info("[IMS-MAIL] owner-reassign sent doc=" + docNumber
                    + " to=" + toList + " cc=" + ccList
                    + " added=" + addedOwners.size() + " removed=" + removedOwners.size()
                    + " attachKB=" + (attachmentBytes == null ? 0 : attachmentBytes.length / 1024));
        } catch (Exception e) {
            LOG.warning("[IMS-MAIL] owner-reassign send failed for doc=" + docNumber + ": " + e.getMessage());
        }
    }

    private static String buildOwnersListHtml(List<OwnerEntry> owners) {
        if (owners == null || owners.isEmpty()) return "<em style=\"color:#6B7280;\">(none)</em>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < owners.size(); i++) {
            if (i > 0) sb.append("<br>");
            OwnerEntry o = owners.get(i);
            sb.append("<strong>").append(esc(o.label())).append("</strong>");
            if (o.email != null && !o.email.isEmpty()) {
                sb.append(" <span style=\"color:#6B7280;\">&lt;").append(esc(o.email)).append("&gt;</span>");
            }
        }
        return sb.toString();
    }

    /**
     * Render the replaced-owner list as bare names — no "has left SanDisk"
     * badge, no email-address tag, no "(Cc'd)" annotation. Vikas 2026-06-05:
     * the previous version showed a red "has left SanDisk" pill next to
     * names that LDAP probed as NOT_FOUND, which leaks HR-sensitive info
     * (who departed) to anyone the reassignment email lands in front of.
     * The bare-name form keeps the new owner informed about who they're
     * replacing without broadcasting attrition.
     */
    private static String buildRemovedOwnersListHtml(List<OwnerEntry> removed) {
        if (removed == null || removed.isEmpty()) return "<em style=\"color:#6B7280;\">No prior owner was removed by this change.</em>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < removed.size(); i++) {
            if (i > 0) sb.append("<br>");
            sb.append("<strong>").append(esc(removed.get(i).label())).append("</strong>");
        }
        return sb.toString();
    }

    /**
     * No-op since 2026-06-05 — the "Heads up: previous Document Owner has
     * left SanDisk" callout used to sit above the document details, but the
     * person-left info is HR-sensitive and shouldn't be broadcast even to
     * the incoming owner. Kept as a no-op shim so the template variable
     * {@code leftCompanyCallout} still resolves to the empty string and the
     * email layout doesn't shift. Remove the template placeholder + this
     * method in a future cleanup.
     */
    private static String buildLeftCompanyCalloutHtml(List<OwnerEntry> removed) {
        return "";
    }

    private static String buildAttachmentBlockHtml(String filename, long sizeBytes, boolean haveBytes) {
        if (!haveBytes || filename == null || filename.isEmpty()) {
            return "<tr><td style=\"padding:14px 20px 0;\">"
                + "<div style=\"font-size:12px;color:#6B7280;text-transform:uppercase;letter-spacing:0.5px;border-bottom:1px solid #E8E6DF;padding-bottom:4px;\">Attachment</div>"
                + "<p style=\"margin:8px 0;font-size:12.5px;color:#6B7280;\">"
                + "No file is attached to this notification &mdash; the Agile document had no fetchable attachment at the time of the reassignment.</p>"
                + "</td></tr>";
        }
        String sizeLabel = humanBytes(sizeBytes);
        return "<tr><td style=\"padding:14px 20px 0;\">"
            + "<div style=\"font-size:12px;color:#6B7280;text-transform:uppercase;letter-spacing:0.5px;border-bottom:1px solid #E8E6DF;padding-bottom:4px;\">Attachment</div>"
            + "<p style=\"margin:8px 0;font-size:12.5px;color:#0F1720;\">"
            + "&#128206; <strong>" + esc(filename) + "</strong> "
            + "<span style=\"color:#6B7280;\">(" + esc(sizeLabel) + ")</span> &mdash; current Agile attachment, included with this email.</p>"
            + "</td></tr>";
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------
    // DCO auto-submit deferred (DRR submit failed) — 2026-06-03
    // ------------------------------------------------------------------

    /** Configurable address for the IT-side diagnostic email when DCO auto-submit
     *  is aborted because the DRR submit failed. Defaults to {@code pdl-plm-admin@sandisk.com}. */
    @Value("${app.ims-review.it-diagnostic-dl:pdl-plm-admin@sandisk.com}")
    public String itDiagnosticDl;

    /**
     * When the toolkit creates a DCO but plm-agile-service aborts the auto-submit
     * because the DRR couldn't be moved Pending → Submit, send TWO emails:
     *
     * <ol>
     *   <li>To the DO who signed (business-friendly, no stack trace) — letting them
     *       know their submission landed but PLM IT is tracking the deferred submit
     *       so they don't worry.</li>
     *   <li>To {@link #itDiagnosticDl} (default {@code pdl-plm-admin@sandisk.com})
     *       — full diagnostic with the cause-chained SDK error, corrId, doc/DRR/DCO
     *       numbers, and signer identity. This is the email PLM IT triages.</li>
     * </ol>
     *
     * Both follow the project email design guidelines (SanDisk palette, IBM Plex
     * fonts, dark-mode meta, sandisk pill footer). Sends are best-effort; any
     * failure is logged but never thrown.
     */
    public void sendDcoSubmitDeferredEmails(ImsReviewService.TokenContext ctx,
                                            LdapAuthService.VerifyResult v,
                                            String dcoNumber,
                                            String drrSubmitErrorDetail,
                                            String corrId) {
        if (ctx == null) return;
        String doc = nvl(ctx.docNumber);
        String drr = nvl(ctx.drrNumber);
        String dco = nvl(dcoNumber);
        String desc = nvl(ctx.description);
        String signerName = v == null ? "" : nvl(v.displayName);
        String signerEmail = v == null ? "" : nvl(v.email);
        String detail = nvl(drrSubmitErrorDetail);
        String corr = nvl(corrId);
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new java.util.Date());

        // ----- DO email (friendly) -----
        try {
            if (signerEmail != null && signerEmail.contains("@")) {
                String userHtml = buildDcoDeferredUserHtml(doc, desc, drr, dco, signerName);
                Payload up = new Payload();
                up.subject = "IMS Dashboard: your DCO " + dco + " was created — PLM IT is tracking the submission";
                up.to = java.util.Collections.singletonList(signerEmail);
                up.cc = java.util.Collections.emptyList();
                sendRawHtml(up, userHtml, "dco-submit-deferred-user");
            } else {
                LOG.info("[IMS-MAIL] DCO-deferred user email skipped — no signer email on context (doc=" + doc + ")");
            }
        } catch (Exception userErr) {
            LOG.warning("[IMS-MAIL] DCO-deferred user email failed: " + userErr.getMessage());
        }

        // ----- IT diagnostic email -----
        try {
            String itHtml = buildDcoDeferredItHtml(doc, desc, drr, dco, signerName, signerEmail, detail, corr, now);
            Payload ip = new Payload();
            ip.subject = "[PLM IT] DCO " + dco + " auto-submit deferred — DRR " + drr + " push failed";
            ip.to = java.util.Collections.singletonList(nvl(itDiagnosticDl));
            // Cc the signer (= the DO who filled the form) so they have the
            // diagnostic context too, per Vikas's 2026-06-03 "always copy the
            // doc owner" ask. They already got the friendly user email above,
            // but the IT diagnostic carries the SDK error chain that DCC may
            // ask them about.
            java.util.List<String> itCc = new java.util.ArrayList<>();
            if (signerEmail != null && signerEmail.contains("@")
                    && !signerEmail.equalsIgnoreCase(nvl(itDiagnosticDl))) {
                itCc.add(signerEmail);
            }
            ip.cc = itCc;
            sendRawHtml(ip, itHtml, "dco-submit-deferred-it");
        } catch (Exception itErr) {
            LOG.warning("[IMS-MAIL] DCO-deferred IT email failed: " + itErr.getMessage());
        }
    }

    /**
     * Sibling to {@link #sendDcoSubmitDeferredEmails} for the OTHER failure
     * mode: the DCO was fully populated but {@code dco.changeStatus(Submit)}
     * itself threw (typically a DCOAudit check the toolkit didn't pre-fill,
     * e.g. blank Training Requirement on an Automotive doc).
     *
     * Same recipients + structure as the DRR-deferred email — friendly user
     * note to the signer, diagnostic to {@link #itDiagnosticDl} — but the
     * copy honestly says "DCO submit failed" instead of "DRR submit failed".
     * Both are necessary because the user-facing reason is different and
     * the IT triage path is different (DRR-side fix vs. DCO-cell pre-fill
     * in DcoRichCreationService).
     *
     * Added 2026-06-08 after DCO-530023 fired the misleading [New DCO]
     * stakeholder notification while the DCO was actually stuck in Pending
     * on a Training-Requirement audit. Sends are best-effort.
     */
    public void sendDcoSubmitFailedEmails(ImsReviewService.TokenContext ctx,
                                          LdapAuthService.VerifyResult v,
                                          String dcoNumber,
                                          String submitErrorDetail,
                                          String corrId) {
        if (ctx == null) return;
        String doc = nvl(ctx.docNumber);
        String drr = nvl(ctx.drrNumber);
        String dco = nvl(dcoNumber);
        String desc = nvl(ctx.description);
        String signerName = v == null ? "" : nvl(v.displayName);
        String signerEmail = v == null ? "" : nvl(v.email);
        String detail = nvl(submitErrorDetail);
        String corr = nvl(corrId);
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new java.util.Date());

        try {
            if (signerEmail != null && signerEmail.contains("@")) {
                String userHtml = buildDcoSubmitFailedUserHtml(doc, desc, drr, dco, signerName, detail);
                Payload up = new Payload();
                up.subject = "IMS Dashboard: action needed — manually submit DCO " + dco + " in Agile";
                up.to = java.util.Collections.singletonList(signerEmail);
                up.cc = java.util.Collections.emptyList();
                sendRawHtml(up, userHtml, "dco-submit-failed-user");
            } else {
                LOG.info("[IMS-MAIL] DCO-submit-failed user email skipped — no signer email on context (doc=" + doc + ")");
            }
        } catch (Exception userErr) {
            LOG.warning("[IMS-MAIL] DCO-submit-failed user email failed: " + userErr.getMessage());
        }

        try {
            String itHtml = buildDcoSubmitFailedItHtml(doc, desc, drr, dco, signerName, signerEmail, detail, corr, now);
            Payload ip = new Payload();
            ip.subject = "[PLM IT] DCO " + dco + " auto-submit failed — Pre-Submit audit blocked changeStatus";
            ip.to = java.util.Collections.singletonList(nvl(itDiagnosticDl));
            java.util.List<String> itCc = new java.util.ArrayList<>();
            if (signerEmail != null && signerEmail.contains("@")
                    && !signerEmail.equalsIgnoreCase(nvl(itDiagnosticDl))) {
                itCc.add(signerEmail);
            }
            ip.cc = itCc;
            sendRawHtml(ip, itHtml, "dco-submit-failed-it");
        } catch (Exception itErr) {
            LOG.warning("[IMS-MAIL] DCO-submit-failed IT email failed: " + itErr.getMessage());
        }
    }

    /** Minimal IT alert when the DM-Approve close-out could not advance the DRR
     *  to Review (CCB). Goes to {@link #itDiagnosticDl} only. Added 2026-07-15:
     *  a DRR was sitting stuck in Pending with no notification (the "Approved"
     *  email is sent optimistically and doesn't reflect the write-back result). */
    public void sendCloseNoChangeFailedAlert(ImsReviewService.TokenContext ctx, String drr,
                                             String errorReason, String failedStep, String corrId) {
        try {
            String doc  = nvl(ctx == null ? "" : ctx.docNumber);
            String desc = nvl(ctx == null ? "" : ctx.description);
            String now  = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new java.util.Date());
            String html = buildCloseNoChangeFailedItHtml(doc, desc, nvl(drr), nvl(errorReason),
                    nvl(failedStep), nvl(corrId), now);
            Payload p = new Payload();
            p.subject = "[PLM IT] DRR " + nvl(drr) + " did not reach Review — DM-Approve close-out failed";
            p.to = java.util.Collections.singletonList(nvl(itDiagnosticDl));
            p.cc = java.util.Collections.emptyList();
            sendRawHtml(p, html, "close-no-change-failed-it");
        } catch (Exception e) {
            LOG.warning("[IMS-MAIL] close-no-change-failed alert failed: " + e.getMessage());
        }
    }

    private String buildCloseNoChangeFailedItHtml(String doc, String desc, String drr,
                                                  String errorReason, String failedStep,
                                                  String corr, String now) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'></head>"
            + "<body style='margin:0; padding:0; background:#FAFAF7; font-family:\"IBM Plex Sans\",\"Segoe UI\",Calibri,Arial,sans-serif; color:#0F1720;'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center' width='600' style='max-width:600px; margin:24px auto; background:#fff; border:1px solid #E8E6DF; border-radius:8px;'>"
            + "<tr><td style='padding:14px 20px; font-size:11px; color:#6B7280; border-bottom:1px solid #E8E6DF;'>PLM IT &middot; IMS Dashboard &middot; DRR Close-out"
            + "<span style='float:right; background:#fdeaea; color:#B8342B; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;'>Did not reach Review</span></td></tr>"
            + "<tr><td style='padding:18px 20px 6px;'>"
            + "<div style='text-transform:uppercase; letter-spacing:0.6px; color:#6B7280; font-size:11px; font-weight:600;'>IMS DASHBOARD &middot; DRR STAYED PENDING</div>"
            + "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif; font-size:20px; font-weight:500; margin:6px 0 4px;'>DRR did not advance to Review after DM approval</h1>"
            + "<p style='color:#6B7280; margin:0 0 14px; font-size:12.5px;'>Both the Document Owner and their manager confirmed No Change, but the toolkit could not advance <strong style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(drr) + "</strong> to Review (CCB). It is still in Pending and needs a look.</p>"
            + "</td></tr>"
            + "<tr><td style='padding:0 20px 12px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>Context</div>"
            + "<table role='presentation' cellpadding='6' cellspacing='0' width='100%' style='margin-top:6px; font-size:12.5px;'>"
            + "<tr><td style='color:#6B7280; font-weight:600; width:32%; white-space:nowrap; vertical-align:top;'>Document</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(doc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Description</td><td style='word-break:break-word;'>" + esc(desc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>DRR</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(drr) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Failed step</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace;'>" + esc(failedStep) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>corrId</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px;'>" + esc(corr) + "</td></tr>"
            + "</table></td></tr>"
            + "<tr><td style='padding:0 20px 14px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>Error</div>"
            + "<pre style='background:#FAFAF7; border:1px solid #E8E6DF; border-radius:4px; padding:10px 12px; font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px; white-space:pre-wrap; word-break:break-word; color:#0F1720; margin:8px 0 0;'>" + esc(errorReason) + "</pre></td></tr>"
            + "<tr><td style='padding:0 20px 14px;'>"
            + "<div style='background:#fff8e1; border-left:4px solid #C7801B; padding:10px 14px; border-radius:0 6px 6px 0; font-size:12.5px;'>"
            + "<strong>Suggested triage:</strong> open " + esc(drr) + " in Agile and try a manual Submit &rarr; CCB. If it rejects on a required field, fill it and advance; if it rejects on privilege (errorCode 407), the agile-service account needs the DRR status-change / comment privilege. Grep agile-service.log for the corrId above for the full step trace.</div></td></tr>"
            + "<tr><td style='padding:14px 20px; border-top:1px solid #E8E6DF; background:#FAFAF7; font-size:11px; color:#6B7280;'>"
            + "plm-field-tracker &middot; IMS Dashboard &middot; DRR close-out failed<br>Generated at " + esc(now) + ". This email is for IT triage only.</td></tr>"
            + "</table></body></html>";
    }

    private String buildDcoSubmitFailedUserHtml(String doc, String desc, String drr, String dco, String signerName, String submitErrorDetail) {
        String greeting = (signerName == null || signerName.isEmpty()) ? "Hi," : "Hi " + esc(signerName.split(",")[0].trim()) + ",";
        String dcoUrl = nvl(agileWebclientUrl);
        if (dcoUrl.endsWith("/")) dcoUrl = dcoUrl.substring(0, dcoUrl.length() - 1);
        String dcoLink = dcoUrl + "/object/ECN/" + java.net.URLEncoder.encode(dco, java.nio.charset.StandardCharsets.UTF_8);
        // "Who to contact" addresses reuse the DLs already in config so they never drift:
        // DCC = the always-Cc Document Control DL; PLM IT = the IT diagnostic DL.
        String dccContact = (alwaysCc == null || alwaysCc.trim().isEmpty()) ? "" : alwaysCc.split(",")[0].trim();
        String itContact = nvl(itDiagnosticDl);
        String errBlock = submitErrorDetail == null || submitErrorDetail.isEmpty()
                ? ""
                : "<tr><td style='padding:0 20px 14px;'>"
                  + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>Why auto-submit failed</div>"
                  + "<pre style='background:#FAFAF7; border:1px solid #E8E6DF; border-radius:4px; padding:10px 12px; font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px; white-space:pre-wrap; word-break:break-word; color:#0F1720; margin:8px 0 0;'>"
                  + esc(submitErrorDetail)
                  + "</pre>"
                  + "<p style='color:#6B7280; margin:6px 0 0; font-size:12px;'>The exact field Agile is asking for is named in the message above &mdash; open the affected document, fill that field, save, then submit the DCO.</p>"
                  + "</td></tr>";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'></head>"
            + "<body style='margin:0; padding:0; background:#FAFAF7; font-family:\"IBM Plex Sans\",\"Segoe UI\",Calibri,Arial,sans-serif; color:#0F1720;'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center' width='600' style='max-width:600px; margin:24px auto; background:#fff; border:1px solid #E8E6DF; border-radius:8px;'>"
            + "<tr><td style='padding:14px 20px; font-size:11px; color:#6B7280; border-bottom:1px solid #E8E6DF;'>Agile PLM &middot; IMS Dashboard &middot; DCO Created &mdash; Action Needed"
            + "<span style='float:right; background:#fff3cd; color:#856404; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;'>Please submit manually</span></td></tr>"
            + "<tr><td style='padding:18px 20px 6px;'>"
            + "<div style='text-transform:uppercase; letter-spacing:0.6px; color:#6B7280; font-size:11px; font-weight:600;'>IMS DASHBOARD &middot; PLEASE SUBMIT THE DCO IN AGILE</div>"
            + "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif; font-size:22px; font-weight:500; margin:6px 0 4px;'>" + greeting + " your DCO <strong style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(dco) + "</strong> was created, but needs you to submit it</h1>"
            + "<p style='color:#6B7280; margin:0 0 16px; font-size:13px;'>The revised version of <strong style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(doc) + "</strong> is in Agile with your files, owners, approvers, observers, and the attestation PDF all attached. The auto-submit step itself was rejected by an Agile pre-submit audit, so the DCO is sitting in Pending and waiting on you to push it through manually.</p>"
            + "</td></tr>"
            + "<tr><td style='padding:0 20px 12px;'>"
            + "<div style='background:#fff8e1; border-left:4px solid #C7801B; padding:10px 14px; border-radius:0 6px 6px 0; font-size:13px;'>"
            + "<strong>What to do:</strong> open the DCO in Agile, review the affected document for the missing field named in the error below, fill it in, then click <strong>Next Status &rarr; Submit</strong>. PLM IT is also looking at whether the toolkit can pre-fill this field on the next IMS Review cycle so you don't have to."
            + "</td></tr>"
            + "<tr><td align='center' style='padding:6px 20px 18px;'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0'><tr><td>"
            + "<a href='" + esc(dcoLink) + "' style='display:inline-block; background:#4a6fa5; color:#ffffff; text-decoration:none; padding:11px 24px; border-radius:6px; font-weight:600; font-size:13.5px;'>Open " + esc(dco) + " in Agile</a>"
            + "</td></tr></table>"
            + "</td></tr>"
            + errBlock
            + "<tr><td style='padding:0 20px 14px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>Details</div>"
            + "<table role='presentation' cellpadding='6' cellspacing='0' width='100%' style='margin-top:6px; font-size:13px;'>"
            + "<tr><td style='color:#6B7280; font-weight:600; width:35%; white-space:nowrap; vertical-align:top;'>Document</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(doc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Description</td><td style='word-break:break-word;'>" + esc(desc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>DCO</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(dco) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Linked DRR</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(drr) + "</td></tr>"
            + "</table></td></tr>"
            + "<tr><td style='padding:0 20px 16px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>Who to contact</div>"
            + "<table role='presentation' cellpadding='6' cellspacing='0' width='100%' style='margin-top:6px; font-size:13px;'>"
            + "<tr><td style='color:#6B7280; font-weight:600; width:35%; white-space:nowrap; vertical-align:top;'>Document Control (DCC)</td>"
            + "<td>Help submitting the DCO or questions about the document &mdash; "
            + (dccContact.isEmpty() ? "your Document Control team" : "<a href='mailto:" + esc(dccContact) + "' style='color:#4a6fa5; font-weight:600;'>" + esc(dccContact) + "</a>")
            + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; white-space:nowrap; vertical-align:top;'>PLM IT</td>"
            + "<td>The technical error above or toolkit behavior &mdash; "
            + (itContact.isEmpty() ? "PLM IT" : "<a href='mailto:" + esc(itContact) + "' style='color:#4a6fa5; font-weight:600;'>" + esc(itContact) + "</a>")
            + "</td></tr>"
            + "</table></td></tr>"
            + "<tr><td style='padding:14px 20px; border-top:1px solid #E8E6DF; background:#FAFAF7; font-size:11px; color:#6B7280;'>"
            + "plm-field-tracker &middot; IMS Dashboard<br>This is an automated notification. Please do not reply to this email."
            + "</td></tr></table></body></html>";
    }

    private String buildDcoSubmitFailedItHtml(String doc, String desc, String drr, String dco,
                                              String signerName, String signerEmail,
                                              String submitErrorDetail, String corrId, String sentAt) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'></head>"
            + "<body style='margin:0; padding:0; background:#FAFAF7; font-family:\"IBM Plex Sans\",\"Segoe UI\",Calibri,Arial,sans-serif; color:#0F1720;'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center' width='620' style='max-width:620px; margin:24px auto; background:#fff; border:1px solid #E8E6DF; border-radius:8px;'>"
            + "<tr><td style='padding:14px 20px; font-size:11px; color:#6B7280; border-bottom:1px solid #E8E6DF;'>PLM IT &middot; IMS Dashboard &middot; DCO Auto-Submit Failed"
            + "<span style='float:right; background:#fdeaea; color:#B8342B; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;'>Investigation needed</span></td></tr>"
            + "<tr><td style='padding:18px 20px 6px;'>"
            + "<div style='text-transform:uppercase; letter-spacing:0.6px; color:#6B7280; font-size:11px; font-weight:600;'>IMS DASHBOARD &middot; DCO STAYED PENDING</div>"
            + "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif; font-size:20px; font-weight:500; margin:6px 0 4px;'>DCO submit failed &mdash; Pre-Submit audit blocked changeStatus</h1>"
            + "<p style='color:#6B7280; margin:0 0 14px; font-size:12.5px;'>Toolkit created <strong style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(dco) + "</strong> with all cells, owners, approvers, observers and attachments. <strong>The DRR was advanced cleanly</strong> (so it's not the DRR-deferred path) &mdash; the failure came from <code>IChange.changeStatus(Submit)</code> itself, almost certainly the DCOAudit Pre-Submit PX flagging a different missing field on the affected Document. See the error block below for the exact audit message.</p>"
            + "</td></tr>"
            + "<tr><td style='padding:0 20px 12px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>Context</div>"
            + "<table role='presentation' cellpadding='6' cellspacing='0' width='100%' style='margin-top:6px; font-size:12.5px;'>"
            + "<tr><td style='color:#6B7280; font-weight:600; width:32%; white-space:nowrap; vertical-align:top;'>Document</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(doc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Description</td><td style='word-break:break-word;'>" + esc(desc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>DRR</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(drr) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>DCO</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(dco) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Signer</td><td>" + esc(signerName) + " &lt;" + esc(signerEmail) + "&gt;</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>corrId</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px;'>" + esc(corrId) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Timestamp</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px;'>" + esc(sentAt) + "</td></tr>"
            + "</table></td></tr>"
            + "<tr><td style='padding:6px 20px 14px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>changeStatus error (DCOAudit root cause)</div>"
            + "<pre style='background:#FAFAF7; border:1px solid #E8E6DF; border-radius:4px; padding:10px 12px; font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px; white-space:pre-wrap; word-break:break-word; color:#0F1720; margin:8px 0 0;'>"
            + esc(submitErrorDetail.isEmpty() ? "(no detail captured)" : submitErrorDetail)
            + "</pre></td></tr>"
            + "<tr><td style='padding:0 20px 14px;'>"
            + "<div style='background:#fff8e1; border-left:4px solid #C7801B; padding:10px 14px; border-radius:0 6px 6px 0; font-size:12.5px;'>"
            + "<strong>What the DO was asked to do:</strong> the signer (Cc'd on this email) got a separate \"action needed\" note pointing at the DCO with the audit message above &mdash; they are being asked to fix the field on the affected Document and click <em>Next Status &rarr; Submit</em> manually. <strong>Your job on this one:</strong> decide whether the missing field is something the toolkit can pre-fill on the next IMS Review cycle. If yes, add a Step 5.x pre-fill in <code>DcoRichCreationService</code> mirroring the existing N/A-on-Referenced-Documents pattern. If no, leave it &mdash; the manual-submit ask is the steady state. Grep <code>agile-service.log</code> for the corrId above to get the full step trace."
            + "</td></tr>"
            + "<tr><td style='padding:14px 20px; border-top:1px solid #E8E6DF; background:#FAFAF7; font-size:11px; color:#6B7280;'>"
            + "plm-field-tracker &middot; IMS Dashboard &middot; DCO auto-submit failed<br>The DO has been notified separately and asked to submit the DCO manually. This email is for IT triage / toolkit-improvement decisions."
            + "</td></tr></table></body></html>";
    }

    /** Tiny wrapper that runs the existing redirect + send pipeline on a
     *  pre-rendered HTML body (no template substitution needed). Used by
     *  the DCO-deferred notifications. */
    private void sendRawHtml(Payload p, String html, String label) throws Exception {
        boolean redirected = redirectTo != null && !redirectTo.trim().isEmpty();
        if (redirected) {
            String origTo = String.join(", ", p.to);
            String banner = "<div style=\"background:#fff3cd; border:1px solid #ffeeba; "
                    + "color:#856404; padding:10px 14px; margin:0 0 12px; font-size:13px;\">"
                    + "&#x26A0; <strong>Local test mode</strong> &mdash; would have gone to: "
                    + "<code>" + esc(origTo) + "</code>"
                    + "</div>";
            html = injectBanner(html, banner);
            p.to = java.util.Collections.singletonList(redirectTo.trim());
            p.cc = java.util.Collections.emptyList();
            if (!p.subject.contains("[LOCAL TEST]")) p.subject = p.subject + " [LOCAL TEST]";
        }
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        javax.mail.Session session = javax.mail.Session.getInstance(props);
        javax.mail.internet.MimeMessage msg = new javax.mail.internet.MimeMessage(session);
        msg.setFrom(new javax.mail.internet.InternetAddress(fromAddr));
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(p.subject), "UTF-8");
        for (String t : p.to) msg.addRecipient(javax.mail.Message.RecipientType.TO, new javax.mail.internet.InternetAddress(t));
        for (String c : p.cc) msg.addRecipient(javax.mail.Message.RecipientType.CC, new javax.mail.internet.InternetAddress(c));
        msg.setContent(html, "text/html; charset=UTF-8");
        javax.mail.Transport.send(msg);
        LOG.info("[IMS-MAIL] sent " + label + " to=" + p.to + (redirected ? " (redirected)" : ""));
    }

    private String buildDcoDeferredUserHtml(String doc, String desc, String drr, String dco, String signerName) {
        String greeting = (signerName == null || signerName.isEmpty()) ? "Hi," : "Hi " + esc(signerName.split(",")[0].trim()) + ",";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'></head>"
            + "<body style='margin:0; padding:0; background:#FAFAF7; font-family:\"IBM Plex Sans\",\"Segoe UI\",Calibri,Arial,sans-serif; color:#0F1720;'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center' width='600' style='max-width:600px; margin:24px auto; background:#fff; border:1px solid #E8E6DF; border-radius:8px;'>"
            + "<tr><td style='padding:14px 20px; font-size:11px; color:#6B7280; border-bottom:1px solid #E8E6DF;'>Agile PLM &middot; IMS Dashboard &middot; DCO Created"
            + "<span style='float:right; background:#fff3cd; color:#856404; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;'>Manual submission queued</span></td></tr>"
            + "<tr><td style='padding:18px 20px 6px;'>"
            + "<div style='text-transform:uppercase; letter-spacing:0.6px; color:#6B7280; font-size:11px; font-weight:600;'>IMS DASHBOARD &middot; NO ACTION REQUIRED</div>"
            + "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif; font-size:22px; font-weight:500; margin:6px 0 4px;'>" + greeting + " your DCO <strong style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(dco) + "</strong> was created</h1>"
            + "<p style='color:#6B7280; margin:0 0 16px; font-size:13px;'>Thanks for submitting the revised version of <strong style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(doc) + "</strong>. Your files and attestation PDF are attached to the DCO in Agile.</p>"
            + "</td></tr>"
            + "<tr><td style='padding:0 20px 12px;'>"
            + "<div style='background:#fff8e1; border-left:4px solid #C7801B; padding:10px 14px; border-radius:0 6px 6px 0; font-size:13px;'>"
            + "<strong>One thing to know:</strong> the toolkit was not able to auto-submit the DCO because the linked DRR (<strong style='font-family:\"IBM Plex Mono\",Consolas,monospace;'>" + esc(drr) + "</strong>) could not be advanced automatically. "
            + "<strong>PLM IT is aware and is tracking this</strong> &mdash; they'll either resolve the DRR-side blocker and submit the DCO, or have Doc Control submit it manually."
            + "</td></tr>"
            + "<tr><td style='padding:0 20px 14px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>Details</div>"
            + "<table role='presentation' cellpadding='6' cellspacing='0' width='100%' style='margin-top:6px; font-size:13px;'>"
            + "<tr><td style='color:#6B7280; font-weight:600; width:35%; white-space:nowrap; vertical-align:top;'>Document</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(doc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Description</td><td style='word-break:break-word;'>" + esc(desc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>DCO</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(dco) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Linked DRR</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(drr) + "</td></tr>"
            + "</table></td></tr>"
            + "<tr><td style='padding:8px 20px 16px; font-size:12.5px; color:#6B7280;'>"
            + "You don't need to take any further action. We'll follow up once it's resolved."
            + "</td></tr>"
            + "<tr><td style='padding:14px 20px; border-top:1px solid #E8E6DF; background:#FAFAF7; font-size:11px; color:#6B7280;'>"
            + "plm-field-tracker &middot; IMS Dashboard<br>This is an automated notification. Please do not reply to this email."
            + "</td></tr></table></body></html>";
    }

    private String buildDcoDeferredItHtml(String doc, String desc, String drr, String dco,
                                          String signerName, String signerEmail,
                                          String drrSubmitErrorDetail, String corrId, String sentAt) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'></head>"
            + "<body style='margin:0; padding:0; background:#FAFAF7; font-family:\"IBM Plex Sans\",\"Segoe UI\",Calibri,Arial,sans-serif; color:#0F1720;'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center' width='620' style='max-width:620px; margin:24px auto; background:#fff; border:1px solid #E8E6DF; border-radius:8px;'>"
            + "<tr><td style='padding:14px 20px; font-size:11px; color:#6B7280; border-bottom:1px solid #E8E6DF;'>PLM IT &middot; IMS Dashboard &middot; DCO Auto-Submit Deferred"
            + "<span style='float:right; background:#fdeaea; color:#B8342B; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;'>Investigation needed</span></td></tr>"
            + "<tr><td style='padding:18px 20px 6px;'>"
            + "<div style='text-transform:uppercase; letter-spacing:0.6px; color:#6B7280; font-size:11px; font-weight:600;'>IMS DASHBOARD &middot; DCO STAYED PENDING</div>"
            + "<h1 style='font-family:\"IBM Plex Serif\",Georgia,serif; font-size:20px; font-weight:500; margin:6px 0 4px;'>DRR submit failed &mdash; DCO not auto-submitted</h1>"
            + "<p style='color:#6B7280; margin:0 0 14px; font-size:12.5px;'>Toolkit created <strong style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(dco) + "</strong> with all cells, owners, approvers, observers and attachments, then aborted the auto-submit because the linked DRR could not be advanced to Submit. DCO is fully populated &mdash; manual submission will work as soon as the DRR is unblocked.</p>"
            + "</td></tr>"
            + "<tr><td style='padding:0 20px 12px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>Context</div>"
            + "<table role='presentation' cellpadding='6' cellspacing='0' width='100%' style='margin-top:6px; font-size:12.5px;'>"
            + "<tr><td style='color:#6B7280; font-weight:600; width:32%; white-space:nowrap; vertical-align:top;'>Document</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(doc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Description</td><td style='word-break:break-word;'>" + esc(desc) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>DRR</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(drr) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>DCO</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; color:#4a6fa5;'>" + esc(dco) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Signer</td><td>" + esc(signerName) + " &lt;" + esc(signerEmail) + "&gt;</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>corrId</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px;'>" + esc(corrId) + "</td></tr>"
            + "<tr><td style='color:#6B7280; font-weight:600; vertical-align:top;'>Timestamp</td><td style='font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px;'>" + esc(sentAt) + "</td></tr>"
            + "</table></td></tr>"
            + "<tr><td style='padding:6px 20px 14px;'>"
            + "<div style='font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;'>DRR submit error (root cause)</div>"
            + "<pre style='background:#FAFAF7; border:1px solid #E8E6DF; border-radius:4px; padding:10px 12px; font-family:\"IBM Plex Mono\",Consolas,monospace; font-size:11.5px; white-space:pre-wrap; word-break:break-word; color:#0F1720; margin:8px 0 0;'>"
            + esc(drrSubmitErrorDetail.isEmpty() ? "(no detail captured)" : drrSubmitErrorDetail)
            + "</pre></td></tr>"
            + "<tr><td style='padding:0 20px 14px;'>"
            + "<div style='background:#fff8e1; border-left:4px solid #C7801B; padding:10px 14px; border-radius:0 6px 6px 0; font-size:12.5px;'>"
            + "<strong>Suggested triage:</strong> open the DRR in Agile and attempt a manual Submit. Workflow rules will surface the missing field / failed PX. Fix root cause, then have DCC submit the DCO. Grep agile-service.log for the corrId above to get the full step trace."
            + "</td></tr>"
            + "<tr><td style='padding:14px 20px; border-top:1px solid #E8E6DF; background:#FAFAF7; font-size:11px; color:#6B7280;'>"
            + "plm-field-tracker &middot; IMS Dashboard &middot; DCO auto-submit deferred<br>The DO has been notified separately (no stack trace) that PLM IT is tracking. This email is for IT triage only."
            + "</td></tr></table></body></html>";
    }
}
