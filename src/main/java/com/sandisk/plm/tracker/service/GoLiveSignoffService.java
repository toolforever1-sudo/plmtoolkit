package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Go-Live &amp; Target-UAT sign-off — daily digest + tokenized sign-off page.
 *
 * <p>Two flows, both rooted in {@link ItEnhancementsService#readAll()}:
 *
 * <ul>
 *   <li><b>Flow A "GOLIVE"</b> — to the requestor. Filter: IT Status = "UAT"
 *       AND Target Go-Live in {@code [today+1, today+3]} OR past.</li>
 *   <li><b>Flow B "UAT"</b> — to the IT owner. Filter: IT Status in
 *       {WIP, Analysis} AND Target UAT in {@code [today+1, today+3]} OR past.</li>
 * </ul>
 *
 * <p>Per-user write-back happens via {@link AgileWriteBackClient}'s existing
 * {@code updateChangeCellAsUser}; the toolkit never writes as Administrator.
 *
 * <p>State + token shape lives at {@code ./data/golive-signoff-state.json}
 * (atomic temp-file-and-move write). See spec §1 for the exact JSON shape.
 */
@Service
public class GoLiveSignoffService {

    private static final Logger LOG = Logger.getLogger(GoLiveSignoffService.class.getName());

    // Env-driven sender so QA mail comes from PLM-Toolkit-qa@ (was hardcoded).
    @Value("${mail.from:PLM-Toolkit@sandisk.com}")
    private String fromAddr;

    public static final String FLOW_GOLIVE = "GOLIVE";
    public static final String FLOW_UAT = "UAT";

    private static final String CELL_GOLIVE = "Page Three.Target Go Live Date";
    private static final String CELL_UAT = "Page Three.Target UAT Date";

    private static final String STATE_FILE = "./data/golive-signoff-state.json";
    private static final int STATE_VERSION = 2;

    private static final long TOKEN_TTL_DAYS = 7L;
    private static final long MS_PER_DAY = 24L * 60 * 60 * 1000;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();

    @Value("${mail.smtp.host:mailrelay.sandisk.com}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${app.golive-signoff.cc:pdl-plm-admin@sandisk.com}")
    private String ccAddr;

    @Value("${app.golive-signoff.base-url:http://uls-ep-aglipccb.sandisk.com:8090}")
    private String baseUrl;

    @Value("${app.golive-signoff.window-days:3}")
    private int windowDays;

    @Value("${app.golive-signoff.overdue-reminder-days:7}")
    private int overdueReminderDays;

    @Autowired private ItEnhancementsService itEnhancementsService;
    @Autowired private AgileWriteBackClient agileWriteBackClient;
    @Autowired(required = false) private LdapAuthService ldapAuthService;
    @Autowired(required = false) private ActivityLogger activityLogger;

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // State — guarded by this
    private final Object stateLock = new Object();
    private State state = new State();

    // ------------------------------------------------------------------
    // State file shape (mirrors spec §1)
    // ------------------------------------------------------------------
    public static final class State {
        public int version = STATE_VERSION;
        public Map<String, TokenRec> tokens = new LinkedHashMap<>();
        /** "GOLIVE|ECN-…|YYYY-MM-DD" → ISO timestamp of send */
        public Map<String, String> sent = new LinkedHashMap<>();
        /** "UAT|loginId" → ISO timestamp of last overdue-only reminder */
        public Map<String, String> lastOverdueReminder = new LinkedHashMap<>();
    }

    public static final class TokenRec {
        public String flow;
        public String loginId;
        public String displayName;
        public List<EcnRef> ecns = new ArrayList<>();
        public String createdAt;
        public String expiresAt;
        public String usedAt;       // null until consumed
        public int failedAttempts;
    }

    public static final class EcnRef {
        public String ecnNumber;
        public String dateAtSend;
        public boolean overdue;
        /** True when the row had no flow-cell date set when the digest was
         *  composed. Renders alongside {@code overdue} on the sign-off page
         *  with the same red "New date required" treatment, and the
         *  confirmation email reads "set · Jun 22, 2026" instead of
         *  "changed · A → B" for these rows. */
        public boolean noDate;

        public EcnRef() {}
        public EcnRef(String e, String d, boolean o) { this.ecnNumber = e; this.dateAtSend = d; this.overdue = o; }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------
    @PostConstruct
    public void loadState() {
        File f = new File(STATE_FILE);
        if (!f.exists()) {
            LOG.info("[GLS] no state file at " + STATE_FILE + " — starting fresh");
            return;
        }
        try {
            State loaded = mapper.readValue(f, State.class);
            if (loaded != null) {
                if (loaded.tokens == null) loaded.tokens = new LinkedHashMap<>();
                if (loaded.sent == null) loaded.sent = new LinkedHashMap<>();
                if (loaded.lastOverdueReminder == null) loaded.lastOverdueReminder = new LinkedHashMap<>();
                synchronized (stateLock) { this.state = loaded; }
                LOG.info("[GLS] loaded state — tokens=" + loaded.tokens.size()
                        + " sentEntries=" + loaded.sent.size()
                        + " overdueReminders=" + loaded.lastOverdueReminder.size());
            }
        } catch (Exception e) {
            LOG.warning("[GLS] failed to load state, starting fresh: " + e.getMessage());
        }
    }

    private void persistState() {
        File out = new File(STATE_FILE);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(STATE_FILE + ".tmp");
        try {
            synchronized (stateLock) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, state);
            }
            Files.move(tmp.toPath(), out.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warning("[GLS] persistState failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Daily scheduler — gated by app.scheduling.disabled via SchedulingConfig
    // ------------------------------------------------------------------
    @Scheduled(cron = "0 0 8 * * *")
    public void dailyDigest() {
        try {
            LOG.info("[GLS] dailyDigest start");
            itEnhancementsService.refreshNow();
            int aSent = runFlow(FLOW_GOLIVE, /*manualLoginId*/ null);
            int bSent = runFlow(FLOW_UAT, /*manualLoginId*/ null);
            LOG.info("[GLS] dailyDigest done: GOLIVE=" + aSent + " UAT=" + bSent);
        } catch (Exception e) {
            LOG.warning("[GLS] dailyDigest failed: " + e.getMessage());
        }
    }

    /** Run one flow's filter + grouping + send pass. When {@code manualLoginId}
     *  is non-null we only send to that one person (used by the admin
     *  /trigger button). Returns number of digests sent. */
    public int runFlow(String flow, String manualLoginId) {
        if (!FLOW_GOLIVE.equals(flow) && !FLOW_UAT.equals(flow)) {
            throw new IllegalArgumentException("unknown flow: " + flow);
        }
        List<ItEnhancementsService.Row> rows = itEnhancementsService.readAll();
        LocalDate today = LocalDate.now();

        // Group qualifying rows by recipient loginId.
        Map<String, List<ItEnhancementsService.Row>> groups = new LinkedHashMap<>();
        Map<String, String> displayNameById = new LinkedHashMap<>();
        for (ItEnhancementsService.Row r : rows) {
            if (!qualifies(flow, r, today)) continue;
            String loginId = recipientLoginId(flow, r);
            String displayName = recipientDisplayName(flow, r);
            if (loginId == null || loginId.trim().isEmpty()) {
                LOG.warning("[GLS] " + flow + " row missing recipient loginId: ecn=" + r.ecnNumber);
                continue;
            }
            loginId = loginId.trim();
            if (manualLoginId != null && !manualLoginId.equalsIgnoreCase(loginId)) continue;
            groups.computeIfAbsent(loginId, k -> new ArrayList<>()).add(r);
            displayNameById.putIfAbsent(loginId, displayName);
        }

        int sent = 0;
        for (Map.Entry<String, List<ItEnhancementsService.Row>> e : groups.entrySet()) {
            String loginId = e.getKey();
            List<ItEnhancementsService.Row> bucket = e.getValue();
            try {
                if (sendDigest(flow, loginId, displayNameById.get(loginId), bucket, today, manualLoginId != null)) {
                    sent++;
                }
            } catch (Exception sendErr) {
                LOG.warning("[GLS] " + flow + " send failed for " + loginId + ": " + sendErr.getMessage());
            }
        }
        return sent;
    }

    private static String recipientLoginId(String flow, ItEnhancementsService.Row r) {
        return FLOW_GOLIVE.equals(flow) ? r.requestorLoginId : r.itOwnerLoginId;
    }
    private static String recipientDisplayName(String flow, ItEnhancementsService.Row r) {
        return FLOW_GOLIVE.equals(flow) ? r.requestor : r.itOwner;
    }
    private static String flowDate(String flow, ItEnhancementsService.Row r) {
        return FLOW_GOLIVE.equals(flow) ? r.targetGoLive : r.targetUAT;
    }
    private static String flowCellName(String flow) {
        return FLOW_GOLIVE.equals(flow) ? CELL_GOLIVE : CELL_UAT;
    }

    /** Filter per spec §1.2, extended 2026-06-12 (Vikas) to cover a third
     *  bucket: ECNs with NO date set at all. Those rows also need attention
     *  — they can't be signed off and they're not implicitly visible to the
     *  end user via a "X days past due" chip. So we surface them in the same
     *  digest, in their own "no date set" section, and require a new date
     *  before submit (same as past-due). Excludes "today" (= neither
     *  upcoming nor past). */
    private boolean qualifies(String flow, ItEnhancementsService.Row r, LocalDate today) {
        String status = r.status == null ? "" : r.status.trim();
        if (FLOW_GOLIVE.equals(flow)) {
            if (!"UAT".equalsIgnoreCase(status)) return false;
            LocalDate d = parseIso(r.targetGoLive);
            if (d == null) return true;            // no go-live set
            return inWindowOrPast(d, today);
        } else {
            if (!"WIP".equalsIgnoreCase(status) && !"Analysis".equalsIgnoreCase(status)) return false;
            LocalDate d = parseIso(r.targetUAT);
            if (d == null) return true;            // no UAT date set
            return inWindowOrPast(d, today);
        }
    }

    private boolean inWindowOrPast(LocalDate d, LocalDate today) {
        if (d.isBefore(today)) return true;                // past due
        if (d.isAfter(today) && !d.isAfter(today.plusDays(windowDays))) return true; // today+1..today+N
        return false;
    }

    private static LocalDate parseIso(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------
    // Compose + send one digest. Returns true if a digest was actually sent.
    // ------------------------------------------------------------------
    private boolean sendDigest(String flow, String loginId, String displayName,
                                List<ItEnhancementsService.Row> bucket, LocalDate today,
                                boolean manualOverride) throws Exception {
        // Apply dedupe gates. Build the subset of ECNs we'd include this time.
        // Three buckets: upcoming (in window), overdue (past), noDate (blank).
        // Cadence: noDate ride along with overdue — same weekly cap when no
        // upcoming is present, since both states represent "user has to pick
        // a date before they can sign off".
        List<ItEnhancementsService.Row> upcoming = new ArrayList<>();
        List<ItEnhancementsService.Row> overdue = new ArrayList<>();
        List<ItEnhancementsService.Row> noDate = new ArrayList<>();
        for (ItEnhancementsService.Row r : bucket) {
            LocalDate d = parseIso(flowDate(flow, r));
            if (d == null) noDate.add(r);
            else if (d.isBefore(today)) overdue.add(r);
            else upcoming.add(r);
        }
        // Cadence: skip upcoming items already sent for the same (flow, ecn, date).
        Map<String, String> sentMap;
        Map<String, String> overdueMap;
        synchronized (stateLock) {
            sentMap = state.sent;
            overdueMap = state.lastOverdueReminder;
        }
        if (!manualOverride) {
            List<ItEnhancementsService.Row> upcomingKept = new ArrayList<>();
            for (ItEnhancementsService.Row r : upcoming) {
                String key = flow + "|" + r.ecnNumber + "|" + flowDate(flow, r);
                if (!sentMap.containsKey(key)) upcomingKept.add(r);
            }
            upcoming = upcomingKept;
            // Weekly-cap cadence kicks in when there's nothing upcoming. Both
            // overdue and noDate rows ride along the SAME weekly cap — each
            // is a "user has to pick a date before they can sign off" state,
            // and we don't want to spam someone with two reminders in one
            // week just because one cell is past and another is blank.
            if (upcoming.isEmpty() && (!overdue.isEmpty() || !noDate.isEmpty())) {
                String key = flow + "|" + loginId;
                String last = overdueMap.get(key);
                if (last != null) {
                    try {
                        long when = Date.from(java.time.Instant.parse(last)).getTime();
                        if (System.currentTimeMillis() - when < overdueReminderDays * MS_PER_DAY) {
                            return false; // too soon
                        }
                    } catch (Exception parseErr) { /* fall through */ }
                }
            }
        }
        if (upcoming.isEmpty() && overdue.isEmpty() && noDate.isEmpty()) return false;

        // Resolve recipient email (LDAP, fall back).
        String toEmail = resolveEmail(loginId);

        // Sort: upcoming asc by date, then overdue asc by date, then noDate
        // alphabetical by ECN so the user sees a stable order across digests.
        upcoming.sort((a, b) -> nullsafe(flowDate(flow, a)).compareTo(nullsafe(flowDate(flow, b))));
        overdue.sort((a, b) -> nullsafe(flowDate(flow, a)).compareTo(nullsafe(flowDate(flow, b))));
        noDate.sort((a, b) -> nullsafe(a.ecnNumber).compareTo(nullsafe(b.ecnNumber)));
        List<ItEnhancementsService.Row> ordered = new ArrayList<>();
        ordered.addAll(upcoming);
        ordered.addAll(overdue);
        ordered.addAll(noDate);

        // Mint token covering these ECNs.
        TokenRec tok = new TokenRec();
        tok.flow = flow;
        tok.loginId = loginId;
        tok.displayName = displayName == null ? loginId : displayName;
        tok.createdAt = java.time.Instant.now().toString();
        tok.expiresAt = java.time.Instant.now().plusSeconds(TOKEN_TTL_DAYS * 24 * 3600).toString();
        tok.usedAt = null;
        tok.failedAttempts = 0;
        for (ItEnhancementsService.Row r : ordered) {
            String d = flowDate(flow, r);
            LocalDate ld = parseIso(d);
            boolean isOver = ld != null && ld.isBefore(today);
            EcnRef ref = new EcnRef(r.ecnNumber, d, isOver);
            ref.noDate = (ld == null);
            tok.ecns.add(ref);
        }
        String token = mintToken();
        synchronized (stateLock) {
            state.tokens.put(token, tok);
            // Mark upcoming as sent for cadence.
            String nowIso = java.time.Instant.now().toString();
            for (ItEnhancementsService.Row r : upcoming) {
                state.sent.put(flow + "|" + r.ecnNumber + "|" + flowDate(flow, r), nowIso);
            }
            // Roll the 7-day clock whenever the digest carried any rows that
            // require a date pick (overdue or noDate). Same behaviour we
            // already had for overdue rows.
            if (!overdue.isEmpty() || !noDate.isEmpty()) {
                state.lastOverdueReminder.put(flow + "|" + loginId, nowIso);
            }
        }
        persistState();

        // Compose + send.
        String subject = buildSubject(flow, upcoming.size() + overdue.size() + noDate.size(),
                overdue.size(), noDate.size());
        String html = buildDigestHtml(flow, tok, token, ordered, today, toEmail);
        boolean highImportance = !overdue.isEmpty() || !noDate.isEmpty();
        sendHtml(toEmail, ccAddr, subject, html, highImportance);
        LOG.info("[GLS] " + flow + " sent to " + toEmail + " (" + ordered.size() + " ECNs, "
                + overdue.size() + " overdue, " + noDate.size() + " no-date)");
        if (activityLogger != null) {
            activityLogger.log("system", "GoLiveSignoff",
                    "GLS_DIGEST_SENT",
                    flow + " " + loginId + " (" + ordered.size() + " ECNs, "
                            + overdue.size() + " overdue, " + noDate.size() + " no-date)");
        }
        return true;
    }

    private static String nullsafe(String s) { return s == null ? "" : s; }

    private String mintToken() {
        byte[] buf = new byte[24];   // 192 bits
        RNG.nextBytes(buf);
        return URL64.encodeToString(buf);
    }

    private String resolveEmail(String loginId) {
        try {
            if (ldapAuthService != null) {
                LdapAuthService.UserInfo info = ldapAuthService.lookupUserByUsername(loginId);
                if (info != null && info.email != null && !info.email.isEmpty()) {
                    return info.email;
                }
            }
        } catch (Exception ignore) { /* fall through */ }
        String fallback = loginId + "@sandisk.com";
        LOG.warning("[GLS] LDAP miss for loginId=" + loginId + " — falling back to " + fallback);
        return fallback;
    }

    private String buildSubject(String flow, int total, int overdue, int noDate) {
        String s;
        if (FLOW_GOLIVE.equals(flow)) {
            s = "[Action Required] Go-Live sign-off — " + total + " enhancement"
                    + (total == 1 ? "" : "s");
        } else {
            s = "[Action Required] Target UAT check — " + total + " ECN"
                    + (total == 1 ? "" : "s") + " still in WIP/Analysis";
        }
        if (overdue > 0) s += " · " + overdue + " past due";
        if (noDate > 0)  s += " · " + noDate + " no date set";
        return s;
    }

    // ------------------------------------------------------------------
    // Email HTML rendering — both flows. Inline-styled tables, mockup-matching.
    // ------------------------------------------------------------------
    private String buildDigestHtml(String flow, TokenRec tok, String token,
                                    List<ItEnhancementsService.Row> ordered, LocalDate today,
                                    String toEmail) {
        boolean isGoLive = FLOW_GOLIVE.equals(flow);
        String section = isGoLive ? "Go-Live Sign-Off" : "Target UAT Check";
        String firstName = firstNameFromDisplay(tok.displayName);
        int total = ordered.size();
        int overdueCount = 0;
        int noDateCount = 0;
        for (ItEnhancementsService.Row r : ordered) {
            LocalDate d = parseIso(flowDate(flow, r));
            if (d == null) noDateCount++;
            else if (d.isBefore(today)) overdueCount++;
        }
        int upcomingCount = total - overdueCount - noDateCount;

        String hero;
        String heroSub;
        if (isGoLive) {
            StringBuilder h = new StringBuilder();
            if (upcomingCount > 0) {
                h.append(upcomingCount).append(" enhancement").append(upcomingCount == 1 ? "" : "s")
                        .append(" go").append(upcomingCount == 1 ? "es" : "")
                        .append(" live within ").append(windowDays).append("&nbsp;days");
            }
            if (overdueCount > 0) {
                if (h.length() > 0) h.append(" &mdash; and ");
                h.append(overdueCount).append(" ").append(overdueCount == 1 ? "is" : "are").append(" past due");
            }
            if (noDateCount > 0) {
                if (h.length() > 0) h.append(" &mdash; and ");
                h.append(noDateCount).append(" ").append(noDateCount == 1 ? "has" : "have").append(" no date set");
            }
            hero = h.toString();
            heroSub = "Hi " + esc(firstName) + " — these IT enhancements you requested are in <b>UAT</b>. "
                    + "Please confirm each upcoming go-live date"
                    + (overdueCount + noDateCount > 0
                            ? ", and pick a new date for the one"
                            + ((overdueCount + noDateCount) == 1 ? "" : "s")
                            + " that " + ((overdueCount + noDateCount) == 1 ? "needs" : "need")
                            + " one (past due or never filled in)."
                            : ".");
        } else {
            StringBuilder h = new StringBuilder();
            if (upcomingCount > 0) {
                h.append(upcomingCount).append(" of your ECN").append(upcomingCount == 1 ? "" : "s")
                        .append(" hit Target UAT within ").append(windowDays).append("&nbsp;days");
            }
            if (overdueCount > 0) {
                if (h.length() > 0) h.append(" &mdash; and ");
                h.append(overdueCount).append(" ").append(overdueCount == 1 ? "is" : "are").append(" past due");
            }
            if (noDateCount > 0) {
                if (h.length() > 0) h.append(" &mdash; and ");
                h.append(noDateCount).append(" ").append(noDateCount == 1 ? "has" : "have").append(" no Target UAT date yet");
            }
            if (h.length() == 0) h.append("Target UAT check");
            hero = h.toString() + " &mdash; but aren't in UAT yet";
            heroSub = "Hi " + esc(firstName) + " — these ECNs are assigned to you and still in "
                    + "<b>WIP / Analysis</b>. Please set or update each Target UAT date so the plan stays honest"
                    + (overdueCount > 0 || noDateCount > 0
                            ? " &mdash; rows past due or missing a date need a fresh pick." : ".");
        }

        StringBuilder cards = new StringBuilder();
        // Order is already: upcoming → overdue → noDate (from sendDigest).
        // Emit the matching section divider as we cross each boundary.
        boolean pastDueDividerEmitted = false;
        boolean noDateDividerEmitted = false;
        for (ItEnhancementsService.Row r : ordered) {
            LocalDate d = parseIso(flowDate(flow, r));
            boolean isOver = d != null && d.isBefore(today);
            boolean isNoDate = (d == null);
            if (isOver && !pastDueDividerEmitted) {
                cards.append(pastDueDividerHtml(isGoLive));
                pastDueDividerEmitted = true;
            }
            if (isNoDate && !noDateDividerEmitted) {
                cards.append(noDateDividerHtml(isGoLive));
                noDateDividerEmitted = true;
            }
            cards.append(cardHtml(flow, r, today, isOver, isNoDate));
        }

        String ctaLabel = isGoLive ? "Review &amp; sign off &rarr;" : "Update UAT dates &rarr;";
        String linkUrl = baseUrl + "/golive-signoff.html?token=" + token;
        String expiresStr = fmtShort(LocalDate.now().plusDays(TOKEN_TTL_DAYS));
        String reqId = (isGoLive ? "GLS-" : "UAT-") + token.substring(0, Math.min(4, token.length())).toUpperCase(Locale.ROOT);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"color-scheme\" content=\"light dark\">")
                .append("</head><body style=\"margin:0;padding:0;background:#f0f0f0;\">")
                .append("<div style=\"background:#fafaf7; padding:24px 20px;\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"max-width:600px; margin:0 auto; background:#ffffff; border:1px solid #e8e6df; border-radius:8px; overflow:hidden; font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;\">")
                // HEADER
                .append("<tr><td style=\"padding:18px 24px 16px; border-bottom:1px solid #ececec;\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>")
                .append("<td style=\"width:24px; vertical-align:middle;\"><div style=\"width:24px;height:24px;border-radius:5px;background:#0F1720;color:#FAFAF7;font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;font-weight:700;text-align:center;line-height:24px;letter-spacing:0.03em;\">PT</div></td>")
                .append("<td style=\"padding-left:10px; vertical-align:middle;\">")
                .append("<span style=\"font-size:13px;font-weight:600;color:#0F1720;\">PLM Toolkit</span>")
                .append("<span style=\"font-size:13px;color:#6B7280;font-weight:400;\"> / ").append(esc(section)).append("</span>")
                .append("</td><td style=\"text-align:right; vertical-align:middle;\">")
                .append("<span style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#C7801B;border:1px solid #ecd9b4;padding:3px 8px;border-radius:3px;background:#faf3e5;\">Action required</span>")
                .append("</td></tr></table></td></tr>")
                // HERO
                .append("<tr><td style=\"padding:22px 24px 18px; background:#ffffff;\">")
                .append("<div style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;letter-spacing:0.12em;text-transform:uppercase;color:#6B7280;margin-bottom:8px;\">")
                .append(esc(LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))))
                .append(" &middot; 8:00 AM</div>")
                .append("<h1 style=\"font-family:'IBM Plex Serif',Georgia,serif;font-weight:500;font-size:26px;line-height:1.2;letter-spacing:-0.01em;color:#0F1720;margin:0 0 6px;\">")
                .append(hero).append("</h1>")
                .append("<p style=\"font-size:14px;color:#4a4f58;line-height:1.55;margin:0;\">")
                .append(heroSub).append("</p>")
                .append("</td></tr>")
                // CARDS
                .append(cards)
                // CTA
                .append("<tr><td style=\"padding:20px 24px 8px;background:#ffffff;text-align:center;\">")
                .append("<a href=\"").append(esc(linkUrl)).append("\" style=\"display:inline-block;background:#0F1720;color:#FAFAF7;text-decoration:none;padding:12px 28px;border-radius:5px;font-size:14px;font-weight:500;letter-spacing:0.01em;\">")
                .append(ctaLabel).append("</a>")
                .append("<div style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:11px;color:#6B7280;margin-top:10px;\">This link is unique to you &middot; expires ")
                .append(esc(expiresStr));
        if (!isGoLive) html.append(" &middot; or edit directly in the IT Enhancements tab");
        html.append("</div></td></tr>")
                // HOW IT WORKS NOTE
                .append("<tr><td style=\"padding:16px 24px 22px;\"><table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>")
                .append("<td style=\"width:2px;background:#0F1720;\"></td>")
                .append("<td style=\"background:#f5f2e9;padding:12px 16px;border-radius:0 4px 4px 0;\">")
                .append("<p style=\"font-size:12.5px;color:#2a2f37;line-height:1.65;margin:0;\">");
        if (isGoLive) {
            html.append("On the sign-off page you can <b>keep or change each date</b> &mdash; past-due dates must be rescheduled &mdash; then confirm with your password. Changes are written to Agile <b>under your own identity (")
                    .append(esc(tok.loginId)).append(")</b>. A confirmation copy goes to <b>").append(esc(ccAddr)).append("</b>.");
        } else {
            html.append("Past-due UAT dates must be rescheduled. Updates are written to Agile <b>under your own identity (")
                    .append(esc(tok.loginId)).append(")</b> &mdash; same as saving from the grid. A confirmation copy goes to <b>")
                    .append(esc(ccAddr)).append("</b>.");
        }
        html.append("</p></td></tr></table></td></tr>")
                // META STRIP
                .append("<tr><td style=\"padding:14px 24px;background:#fafaf7;border-top:1px solid #ececec;\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>")
                .append("<td style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;color:#6B7280;letter-spacing:0.06em;text-transform:uppercase;\">Req ID &middot; <b style=\"color:#0F1720;font-weight:500;\">").append(esc(reqId)).append("</b></td>")
                .append("<td style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;color:#6B7280;letter-spacing:0.06em;text-transform:uppercase;text-align:center;\">To &middot; <b style=\"color:#0F1720;font-weight:500;\">").append(esc(toEmail)).append("</b></td>")
                .append("<td style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;color:#6B7280;letter-spacing:0.06em;text-transform:uppercase;text-align:right;\">Cc &middot; <b style=\"color:#0F1720;font-weight:500;\">").append(esc(ccShortName(ccAddr))).append("</b></td>")
                .append("</tr></table></td></tr>")
                // FOOTER
                .append("<tr><td style=\"padding:14px 24px 18px;background:#fafaf7;text-align:center;\">")
                .append("<div style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;color:#9CA3AF;line-height:1.7;\">Generated by PLM Toolkit &middot; IT Enhancements &middot; daily 8:00 AM check<br>");
        if (isGoLive) {
            html.append("Sent because an enhancement you requested is in UAT with go-live in the next 1&ndash;").append(windowDays).append(" days or past due.");
        } else {
            html.append("Sent because an ECN assigned to you is in WIP/Analysis with Target UAT in the next 1&ndash;").append(windowDays).append(" days or past due.");
        }
        html.append("</div></td></tr></table></div></body></html>");
        return html.toString();
    }

    private String cardHtml(String flow, ItEnhancementsService.Row r, LocalDate today,
                            boolean isOver, boolean isNoDate) {
        boolean isGoLive = FLOW_GOLIVE.equals(flow);
        String date = flowDate(flow, r);
        LocalDate d = parseIso(date);
        String status = r.status == null ? "" : r.status.trim();
        String statusChipBg, statusChipFg, statusText;
        if ("UAT".equalsIgnoreCase(status)) { statusChipBg = "#eef3fa"; statusChipFg = "#2C6FBB"; statusText = "UAT"; }
        else if ("WIP".equalsIgnoreCase(status)) { statusChipBg = "#f0ecfa"; statusChipFg = "#6B4FBB"; statusText = "WIP"; }
        else if ("Analysis".equalsIgnoreCase(status)) { statusChipBg = "#ececec"; statusChipFg = "#444444"; statusText = "Analysis"; }
        else { statusChipBg = "#ececec"; statusChipFg = "#444444"; statusText = status; }

        // noDate and isOver share the same red-tint treatment; the card just
        // reads "no date set" instead of "N days past due".
        boolean redTint = isOver || isNoDate;
        String dueChipBg = redTint ? "#fdeaea" : "#faf3e5";
        String dueChipFg = redTint ? "#B8342B" : "#C7801B";
        String dueChipText = isNoDate
                ? (isGoLive ? "no go-live date set" : "no UAT date set")
                : dueChipText(isGoLive, d, today, isOver);

        String borderColor = redTint ? "#eac8c5" : "#e8e6df";
        String dateColor = redTint ? "#B8342B" : "#0F1720";
        String hairlineColor = redTint ? "#f3dedd" : "#ececec";

        String dateLabel = isGoLive ? "Current go-live" : "Target UAT";
        String slotA = isGoLive ? "IT owner" : "Requestor";
        String slotAVal = isGoLive ? r.itOwner : r.requestor;
        String slotB = isGoLive ? "UAT since" : "Target go-live";
        String slotBVal = isGoLive ? fmtShort(r.targetUAT) : fmtShort(r.targetGoLive);

        StringBuilder b = new StringBuilder();
        b.append("<tr><td style=\"padding:0 24px ").append(isOver ? "6px" : "14px").append(";\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border:1px solid ").append(borderColor).append(";border-radius:8px;\">")
                .append("<tr><td style=\"padding:14px 18px 12px;\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>")
                .append("<td style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:13px;font-weight:600;color:#4a6fa5;\">").append(esc(r.ecnNumber)).append("</td>")
                .append("<td style=\"text-align:right;\">")
                .append("<span style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:9px;font-weight:600;letter-spacing:0.1em;text-transform:uppercase;padding:2px 7px;border-radius:3px;background:").append(statusChipBg).append(";color:").append(statusChipFg).append(";\">").append(esc(statusText)).append("</span>")
                .append("<span style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:9px;font-weight:600;letter-spacing:0.1em;text-transform:uppercase;padding:2px 7px;border-radius:3px;background:").append(dueChipBg).append(";color:").append(dueChipFg).append(";margin-left:4px;\">").append(esc(dueChipText)).append("</span>")
                .append("</td></tr></table>")
                .append("<p style=\"font-size:13px;color:#2a2f37;line-height:1.55;margin:8px 0 0;\">").append(esc(truncate(r.proposal, 250))).append("</p>")
                .append("</td></tr>")
                .append("<tr><td style=\"padding:0 18px 14px;\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border-top:1px solid ").append(hairlineColor).append(";\"><tr>")
                .append("<td style=\"padding-top:11px;width:40%;\">")
                .append("<div style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#6B7280;\">").append(esc(dateLabel)).append("</div>")
                .append("<div style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:17px;font-weight:500;color:").append(dateColor).append(";margin-top:2px;\">")
                .append(isNoDate ? "Not set yet" : esc(fmtLong(d))).append("</div>")
                .append("</td>")
                .append("<td style=\"padding-top:11px;width:32%;\">")
                .append("<div style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#6B7280;\">").append(esc(slotA)).append("</div>")
                .append("<div style=\"font-size:12.5px;color:#0F1720;margin-top:3px;\">").append(esc(nvl(slotAVal))).append("</div>")
                .append("</td>")
                .append("<td style=\"padding-top:11px;\">")
                .append("<div style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#6B7280;\">").append(esc(slotB)).append("</div>")
                .append("<div style=\"font-size:12.5px;color:#0F1720;margin-top:3px;\">").append(esc(nvl(slotBVal))).append("</div>")
                .append("</td></tr></table></td></tr></table></td></tr>");
        return b.toString();
    }

    private String pastDueDividerHtml(boolean isGoLive) {
        String txt = isGoLive ? "Past due &mdash; pick a new date" : "UAT date passed &mdash; still in WIP";
        return "<tr><td style=\"padding:14px 24px 10px;\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>"
                + "<td style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;font-weight:600;letter-spacing:0.12em;text-transform:uppercase;color:#B8342B;white-space:nowrap;padding-right:10px;\">"
                + txt + "</td>"
                + "<td style=\"border-top:1px solid #eac8c5;font-size:0;line-height:0;\">&nbsp;</td>"
                + "</tr></table></td></tr>";
    }

    private String noDateDividerHtml(boolean isGoLive) {
        String txt = isGoLive
                ? "No go-live date set &mdash; pick one to confirm"
                : "No Target UAT date set &mdash; pick one to confirm";
        return "<tr><td style=\"padding:14px 24px 10px;\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>"
                + "<td style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;font-weight:600;letter-spacing:0.12em;text-transform:uppercase;color:#B8342B;white-space:nowrap;padding-right:10px;\">"
                + txt + "</td>"
                + "<td style=\"border-top:1px solid #eac8c5;font-size:0;line-height:0;\">&nbsp;</td>"
                + "</tr></table></td></tr>";
    }

    private String dueChipText(boolean isGoLive, LocalDate d, LocalDate today, boolean isOver) {
        if (d == null) return "";
        if (isOver) {
            long days = today.toEpochDay() - d.toEpochDay();
            return days + " day" + (days == 1 ? "" : "s") + " past " + (isGoLive ? "due" : "UAT");
        }
        long days = d.toEpochDay() - today.toEpochDay();
        if (days == 1) return isGoLive ? "goes live tomorrow" : "UAT due tomorrow";
        return (isGoLive ? "in " : "UAT in ") + days + " days";
    }

    // ------------------------------------------------------------------
    // SMTP send
    // ------------------------------------------------------------------
    private void sendHtml(String toEmail, String ccAddrList, String subject, String html, boolean highImportance)
            throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromAddr));
        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));
        if (ccAddrList != null) {
            for (String c : ccAddrList.split(",")) {
                c = c.trim();
                if (c.isEmpty() || c.equalsIgnoreCase(toEmail)) continue;
                msg.addRecipient(Message.RecipientType.CC, new InternetAddress(c));
            }
        }
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject), "UTF-8");
        if (highImportance) {
            msg.setHeader("X-Priority", "1");
            msg.setHeader("Importance", "High");
        }
        msg.setContent(html, "text/html; charset=UTF-8");
        Transport.send(msg);
    }

    // ------------------------------------------------------------------
    // Sign-off page data (GET) + submit
    // ------------------------------------------------------------------
    public Map<String, Object> dataForToken(String token) {
        if (token == null || token.isEmpty()) return errorPayload(400, "Missing token.");
        TokenRec tok;
        synchronized (stateLock) { tok = state.tokens.get(token); }
        if (tok == null) return errorPayload(404, "Token not found or revoked.");
        if (tok.usedAt != null) return errorPayload(410, "Token already used.");
        if (isExpired(tok)) return errorPayload(410, "Token expired.");

        // Re-read live rows so dates/status reflect current Agile state.
        List<ItEnhancementsService.Row> liveRows = itEnhancementsService.readAll();
        Map<String, ItEnhancementsService.Row> byEcn = new LinkedHashMap<>();
        for (ItEnhancementsService.Row r : liveRows) {
            if (r.ecnNumber != null) byEcn.put(r.ecnNumber, r);
        }
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> outRows = new ArrayList<>();
        for (EcnRef ref : tok.ecns) {
            ItEnhancementsService.Row r = byEcn.get(ref.ecnNumber);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ecnNumber", ref.ecnNumber);
            if (r == null) {
                row.put("missing", true);
                row.put("currentDate", ref.dateAtSend);
                row.put("overdue", ref.overdue);
                row.put("noDate", ref.noDate);
                outRows.add(row);
                continue;
            }
            row.put("proposal", r.proposal);
            row.put("status", r.status);
            row.put("itOwner", r.itOwner);
            row.put("requestor", r.requestor);
            row.put("priority", r.priority);
            row.put("targetUAT", r.targetUAT);
            row.put("targetGoLive", r.targetGoLive);
            String currentDate = flowDate(tok.flow, r);
            row.put("currentDate", currentDate);
            LocalDate d = parseIso(currentDate);
            boolean isOver = d != null && d.isBefore(today);
            boolean isNoDate = (currentDate == null || currentDate.isEmpty());
            row.put("overdue", isOver);
            row.put("noDate", isNoDate);
            // Whether the qualifying condition still applies
            row.put("inScope", qualifies(tok.flow, r, today));
            outRows.add(row);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("flow", tok.flow);
        resp.put("loginId", tok.loginId);
        resp.put("displayName", tok.displayName);
        resp.put("expiresAt", tok.expiresAt);
        resp.put("today", today.toString());
        resp.put("ccAddr", ccAddr);
        resp.put("rows", outRows);
        return resp;
    }

    private Map<String, Object> errorPayload(int status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        m.put("status", status);
        return m;
    }

    private boolean isExpired(TokenRec tok) {
        try {
            return java.time.Instant.parse(tok.expiresAt).isBefore(java.time.Instant.now());
        } catch (Exception e) { return false; }
    }

    /** Result envelope returned by submit. */
    public static final class SubmitResult {
        public int httpStatus;
        public boolean ok;
        public String error;
        public boolean tokenLocked;
        public boolean needsAgileSignin;
        public List<Map<String, Object>> results = new ArrayList<>();
    }

    public SubmitResult submit(String token, String samAccountName, String password,
                                List<Map<String, Object>> changes) {
        SubmitResult sr = new SubmitResult();
        if (token == null || token.isEmpty()) { sr.httpStatus = 400; sr.error = "Missing token."; return sr; }
        if (samAccountName == null || samAccountName.isEmpty()) { sr.httpStatus = 400; sr.error = "Missing Agile loginid."; return sr; }
        if (password == null || password.isEmpty()) { sr.httpStatus = 400; sr.error = "Password required."; return sr; }

        TokenRec tok;
        synchronized (stateLock) { tok = state.tokens.get(token); }
        if (tok == null) { sr.httpStatus = 404; sr.error = "Token not found or revoked."; return sr; }
        if (tok.usedAt != null) { sr.httpStatus = 410; sr.error = "Token already used."; return sr; }
        if (isExpired(tok)) { sr.httpStatus = 410; sr.error = "Token expired."; return sr; }
        if (tok.failedAttempts >= 5) { sr.httpStatus = 423; sr.error = "Too many failed attempts — token locked."; sr.tokenLocked = true; return sr; }
        if (!tok.loginId.equalsIgnoreCase(samAccountName.trim())) {
            sr.httpStatus = 403;
            sr.error = "Agile loginid does not match the recipient of this sign-off link.";
            return sr;
        }

        // Validate ECNs vs token + dates + overdue coverage.
        java.util.Set<String> tokenEcns = new java.util.HashSet<>();
        java.util.Set<String> overdueEcns = new java.util.HashSet<>();
        for (EcnRef ref : tok.ecns) {
            tokenEcns.add(ref.ecnNumber);
            if (ref.overdue) overdueEcns.add(ref.ecnNumber);
        }
        LocalDate today = LocalDate.now();
        // Re-evaluate overdue against LIVE data (a date may have already moved).
        List<ItEnhancementsService.Row> liveRows = itEnhancementsService.readAll();
        Map<String, ItEnhancementsService.Row> byEcn = new LinkedHashMap<>();
        for (ItEnhancementsService.Row r : liveRows) {
            if (r.ecnNumber != null) byEcn.put(r.ecnNumber, r);
        }
        java.util.Set<String> liveOverdue = new java.util.HashSet<>();
        java.util.Set<String> liveNoDate = new java.util.HashSet<>();
        for (String ecn : tokenEcns) {
            ItEnhancementsService.Row r = byEcn.get(ecn);
            if (r == null) continue;
            String dRaw = flowDate(tok.flow, r);
            LocalDate d = parseIso(dRaw);
            if (d == null) liveNoDate.add(ecn);
            else if (d.isBefore(today)) liveOverdue.add(ecn);
        }

        if (changes == null) changes = Collections.emptyList();
        Map<String, String> changeByEcn = new LinkedHashMap<>();
        for (Map<String, Object> ch : changes) {
            String ecn = strOrNull(ch.get("ecnNumber"));
            String newDate = strOrNull(ch.get("newDate"));
            if (ecn == null) continue;
            changeByEcn.put(ecn, newDate);
        }
        for (String ecn : changeByEcn.keySet()) {
            if (!tokenEcns.contains(ecn)) { sr.httpStatus = 400; sr.error = "Change refers to an ECN outside this token: " + ecn; return sr; }
        }
        for (String ecn : liveOverdue) {
            String nd = changeByEcn.get(ecn);
            if (nd == null || nd.isEmpty()) { sr.httpStatus = 400; sr.error = "Past-due ECN " + ecn + " requires a new date."; return sr; }
        }
        // No-date ECNs also need a new date — they have nothing to "confirm
        // as-is", and Vikas asked for these to be blocked at submit just like
        // past-due ones (spec extension 2026-06-12).
        for (String ecn : liveNoDate) {
            String nd = changeByEcn.get(ecn);
            if (nd == null || nd.isEmpty()) { sr.httpStatus = 400; sr.error = "ECN " + ecn + " has no date set — pick one to confirm."; return sr; }
        }
        // Each newDate must be today-or-future and parseable
        for (Map.Entry<String, String> e : changeByEcn.entrySet()) {
            String nd = e.getValue();
            if (nd == null || nd.isEmpty()) continue; // un-changed
            LocalDate d = parseIso(nd);
            if (d == null) { sr.httpStatus = 400; sr.error = "Invalid date for " + e.getKey() + ": " + nd; return sr; }
            if (d.isBefore(today)) { sr.httpStatus = 400; sr.error = "Date for " + e.getKey() + " must be today-or-future."; return sr; }
        }

        // Verify password BEFORE any write.
        String corrId = UUID.randomUUID().toString();
        AgileWriteBackClient.Result verify = agileWriteBackClient.verifyUserCredentials(
                samAccountName.trim(), password, corrId);
        if (verify == null || !verify.ok) {
            String err = verify == null ? "verify failed" : (verify.errorReason == null ? "verify failed" : verify.errorReason);
            boolean invalidCreds = err != null && (err.toLowerCase().contains("invalid username or password") || err.contains("60062"));
            synchronized (stateLock) {
                tok.failedAttempts += 1;
            }
            persistState();
            sr.httpStatus = invalidCreds ? 401 : 502;
            sr.error = invalidCreds ? ("Agile rejected those credentials (loginid " + samAccountName + ").")
                    : ("Could not verify Agile credentials: " + err + ". Contact " + ccAddr + ".");
            sr.tokenLocked = tok.failedAttempts >= 5;
            sr.needsAgileSignin = invalidCreds;
            return sr;
        }

        // Now write — only the flow's cell, only for this token's ECNs.
        String cell = flowCellName(tok.flow);
        for (EcnRef ref : tok.ecns) {
            String ecn = ref.ecnNumber;
            String currentLive = null;
            ItEnhancementsService.Row r = byEcn.get(ecn);
            if (r != null) currentLive = flowDate(tok.flow, r);
            String requested = changeByEcn.get(ecn);
            boolean isChanged = requested != null && !requested.isEmpty() && !requested.equals(currentLive);
            boolean wasOver = liveOverdue.contains(ecn);
            boolean wasNoDate = liveNoDate.contains(ecn);

            Map<String, Object> rowResult = new LinkedHashMap<>();
            rowResult.put("ecnNumber", ecn);
            rowResult.put("oldDate", currentLive);
            rowResult.put("newDate", requested);
            rowResult.put("changed", isChanged);
            rowResult.put("overdue", wasOver);
            rowResult.put("wasNoDate", wasNoDate);

            if (!isChanged) {
                rowResult.put("ok", true);
                // "set" reads better than "confirmed" or "rescheduled" for a
                // row that had no date before — but realistically wasNoDate
                // can never reach this branch because we gated above. Cover
                // the conceptual case anyway.
                String stat = wasNoDate ? "set" : (wasOver ? "rescheduled" : "confirmed");
                rowResult.put("status", stat);
                sr.results.add(rowResult);
                continue;
            }
            String rowCorrId = UUID.randomUUID().toString();
            AgileWriteBackClient.Result wr = agileWriteBackClient.updateChangeCellAsUser(
                    ecn, samAccountName.trim(), password, cell, requested, rowCorrId);
            boolean ok = wr != null && wr.ok;
            rowResult.put("ok", ok);
            // Status precedence: noDate → set, overdue → rescheduled, otherwise changed.
            String writeStatus = wasNoDate ? "set" : (wasOver ? "rescheduled" : "changed");
            rowResult.put("status", ok ? writeStatus : "error");
            if (!ok) {
                rowResult.put("error", wr == null ? "no response" : (wr.errorReason == null ? "save failed" : wr.errorReason));
            } else if (r != null) {
                // Patch the in-memory cache so the IT-ENH tab reflects new date immediately.
                if (FLOW_GOLIVE.equals(tok.flow)) r.targetGoLive = requested;
                else r.targetUAT = requested;
            }
            sr.results.add(rowResult);
        }

        // Mark token used.
        synchronized (stateLock) {
            tok.usedAt = java.time.Instant.now().toString();
        }
        persistState();
        sr.httpStatus = 200;
        sr.ok = true;

        // Send confirmation email (best-effort).
        try {
            String email = resolveEmail(tok.loginId);
            String subject = (FLOW_GOLIVE.equals(tok.flow) ? "[Confirmed] Go-Live sign-off" : "[Confirmed] Target UAT update")
                    + " — " + sr.results.size() + " ECN" + (sr.results.size() == 1 ? "" : "s");
            String html = buildConfirmationHtml(tok, sr.results, email);
            sendHtml(email, ccAddr, subject, html, /*highImportance*/ false);
        } catch (Exception emailErr) {
            LOG.warning("[GLS] confirmation email failed for token: " + emailErr.getMessage());
        }
        if (activityLogger != null) {
            activityLogger.log(samAccountName, tok.displayName,
                    "GLS_SIGNOFF",
                    tok.flow + " token consumed (" + sr.results.size() + " ECNs)");
        }
        return sr;
    }

    private String buildConfirmationHtml(TokenRec tok, List<Map<String, Object>> results, String toEmail) {
        boolean isGoLive = FLOW_GOLIVE.equals(tok.flow);
        String section = isGoLive ? "Go-Live Sign-Off" : "Target UAT Update";
        StringBuilder rows = new StringBuilder();
        for (Map<String, Object> rr : results) {
            String ecn = strOrNull(rr.get("ecnNumber"));
            String status = strOrNull(rr.get("status"));
            String oldD = strOrNull(rr.get("oldDate"));
            String newD = strOrNull(rr.get("newDate"));
            boolean ok = Boolean.TRUE.equals(rr.get("ok"));
            String what;
            String chipBg, chipFg, chipLabel;
            if (!ok) {
                what = "save failed — " + esc(strOrNull(rr.get("error")));
                chipBg = "#fdeaea"; chipFg = "#B8342B"; chipLabel = "Error";
            } else if ("set".equals(status)) {
                what = (isGoLive ? "Go-live" : "Target UAT") + " set &middot; " + esc(fmtShort(newD));
                chipBg = "#eef6f1"; chipFg = "#1F8A4C"; chipLabel = "Set";
            } else if ("rescheduled".equals(status)) {
                what = (isGoLive ? "Go-live" : "Target UAT") + " rescheduled &middot; " + esc(fmtShort(oldD)) + " → " + esc(fmtShort(newD));
                chipBg = "#fff8e1"; chipFg = "#C7801B"; chipLabel = "Rescheduled";
            } else if ("changed".equals(status)) {
                what = (isGoLive ? "Go-live" : "Target UAT") + " changed &middot; " + esc(fmtShort(oldD)) + " → " + esc(fmtShort(newD));
                chipBg = "#eef3fa"; chipFg = "#2C6FBB"; chipLabel = "Updated";
            } else {
                what = (isGoLive ? "Go-live" : "Target UAT") + " confirmed &middot; " + esc(fmtShort(oldD));
                chipBg = "#eef6f1"; chipFg = "#1F8A4C"; chipLabel = "Confirmed";
            }
            rows.append("<tr><td style=\"padding:12px 16px;border-bottom:1px solid #ececec;background:#ffffff;\">")
                    .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>")
                    .append("<td style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:12.5px;font-weight:600;color:#4a6fa5;width:170px;\">").append(esc(ecn)).append("</td>")
                    .append("<td style=\"font-size:12.5px;color:#444;\">").append(what).append("</td>")
                    .append("<td style=\"text-align:right;\"><span style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:9px;font-weight:600;letter-spacing:0.1em;text-transform:uppercase;padding:2px 7px;border-radius:3px;background:").append(chipBg).append(";color:").append(chipFg).append(";\">")
                    .append(chipLabel).append("</span></td>")
                    .append("</tr></table></td></tr>");
        }
        String when = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a"));
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body style=\"margin:0;padding:0;background:#f0f0f0;\">")
                .append("<div style=\"background:#fafaf7; padding:24px 20px;\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"max-width:600px; margin:0 auto; background:#ffffff; border:1px solid #e8e6df; border-radius:8px; overflow:hidden; font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;\">")
                .append("<tr><td style=\"padding:18px 24px 16px; border-bottom:1px solid #ececec;\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>")
                .append("<td style=\"width:24px; vertical-align:middle;\"><div style=\"width:24px;height:24px;border-radius:5px;background:#0F1720;color:#FAFAF7;font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;font-weight:700;text-align:center;line-height:24px;letter-spacing:0.03em;\">PT</div></td>")
                .append("<td style=\"padding-left:10px; vertical-align:middle;\">")
                .append("<span style=\"font-size:13px;font-weight:600;color:#0F1720;\">PLM Toolkit</span>")
                .append("<span style=\"font-size:13px;color:#6B7280;font-weight:400;\"> / ").append(esc(section)).append("</span>")
                .append("</td><td style=\"text-align:right; vertical-align:middle;\">")
                .append("<span style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#1F8A4C;border:1px solid #c7e8d3;padding:3px 8px;border-radius:3px;background:#eef6f1;\">Confirmed</span>")
                .append("</td></tr></table></td></tr>")
                .append("<tr><td style=\"padding:22px 24px 8px;\">")
                .append("<div style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;letter-spacing:0.12em;text-transform:uppercase;color:#6B7280;margin-bottom:8px;\">").append(esc(when)).append("</div>")
                .append("<h1 style=\"font-family:'IBM Plex Serif',Georgia,serif;font-weight:500;font-size:24px;line-height:1.2;letter-spacing:-0.01em;color:#0F1720;margin:0 0 6px;\">Written to Agile</h1>")
                .append("<p style=\"font-size:13.5px;color:#4a4f58;line-height:1.55;margin:0;\">Signed off by ").append(esc(tok.displayName))
                .append(" (").append(esc(tok.loginId)).append(").</p></td></tr>")
                .append("<tr><td style=\"padding:14px 24px 22px;\">")
                .append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border:1px solid #E8E6DF;border-radius:8px;overflow:hidden;\">")
                .append(rows).append("</table></td></tr>")
                .append("<tr><td style=\"padding:14px 24px 18px;background:#fafaf7;text-align:center;border-top:1px solid #ececec;\">")
                .append("<div style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:10px;color:#9CA3AF;line-height:1.7;\">Confirmation sent to ").append(esc(toEmail))
                .append(" &middot; cc ").append(esc(ccAddr)).append("<br>PLM Toolkit &middot; IT Enhancements</div></td></tr>")
                .append("</table></div></body></html>");
        return html.toString();
    }

    // ------------------------------------------------------------------
    // utilities
    // ------------------------------------------------------------------
    private static String firstNameFromDisplay(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "there";
        // "Jain, Krati" → "Krati"; "Vikas Jindal" → "Vikas"
        if (displayName.contains(",")) {
            String[] parts = displayName.split(",", 2);
            return parts.length > 1 ? parts[1].trim() : displayName;
        }
        String[] parts = displayName.split("\\s+");
        return parts.length > 0 ? parts[0] : displayName;
    }

    private static String fmtShort(String iso) {
        if (iso == null || iso.isEmpty()) return "—";
        LocalDate d = parseIso(iso);
        if (d == null) return iso;
        return d.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
    }
    private static String fmtShort(LocalDate d) {
        if (d == null) return "—";
        return d.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
    }
    private static String fmtLong(LocalDate d) {
        if (d == null) return "—";
        return d.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"));
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isEmpty() ? null : s;
    }

    private static String nvl(String s) { return s == null ? "—" : s; }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s;
        return s.substring(0, n - 1) + "…";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String ccShortName(String full) {
        if (full == null) return "";
        int at = full.indexOf('@');
        return at > 0 ? full.substring(0, at) : full;
    }
}
