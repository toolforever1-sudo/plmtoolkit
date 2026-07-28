package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Store + business logic for Admin &rarr; Communications &rarr; Announcements.
 *
 * <p>File-backed like the app's other feature stores: {@code index.json}
 * (id &rarr; record map, atomic tmp-rename writes per GradeStorageService) plus
 * one archived {@code <id>.html} snapshot per sent announcement — the exact
 * generic-rendered HTML that went out, for the history "Open as sent" view.
 *
 * <p>Send gate: {@code testedRevision == revision}. {@code revision} bumps only
 * when what actually goes out changes (subject, generated bodyHtml, audience);
 * rough-draft notes and channel toggles don't relock a tested announcement.
 *
 * <p>Scheduled sends: a 60s {@link Scheduled} poller (inherits the global
 * {@code app.scheduling.disabled} kill-switch) fires anything due that is
 * still test-validated. Scheduled records are shared drafts — any PLM IT
 * member may edit until send; every edit lands in {@code revisions}.
 */
@Service
public class AnnouncementService {

    private static final Logger LOG = Logger.getLogger(AnnouncementService.class.getName());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter SCHED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final int BANNER_TTL_DAYS = 14;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseDir;
    private final UserPermissionsService userPermissionsService;
    private final LdapAuthService ldapAuthService;
    private final AnnouncementEmailService emailService;
    private final ActivityLogger activityLogger;
    private final MaintenanceService maintenanceService;

    private final Map<String, Announcement> store = new LinkedHashMap<>();

    @Autowired
    public AnnouncementService(@Value("${app.announcements.dir:./data/announcements}") String baseDir,
                               UserPermissionsService userPermissionsService,
                               LdapAuthService ldapAuthService,
                               AnnouncementEmailService emailService,
                               ActivityLogger activityLogger,
                               MaintenanceService maintenanceService) {
        this.baseDir = baseDir;
        this.userPermissionsService = userPermissionsService;
        this.ldapAuthService = ldapAuthService;
        this.emailService = emailService;
        this.activityLogger = activityLogger;
        this.maintenanceService = maintenanceService;
    }

    // ---- model ----------------------------------------------------------

    /**
     * Doubles as the stored record AND the update patch. Patch semantics:
     * null field = "leave unchanged" — so the string/list fields deliberately
     * default to null (a "" initializer would make a partial patch silently
     * wipe fields it never mentioned; that bug ate the draft on generate).
     * Real defaults are stamped at creation in {@link #upsert}.
     */
    public static class Announcement {
        public String id;
        public String status = "draft";              // draft | scheduled | sent
        public String subject;
        public String draft;                         // rough / wordsmithed source text
        public String bodyHtml;                      // AI-generated inner body; may contain ${firstName}
        public String audienceType;                  // everLoggedIn | dl | specific
        public List<String> specificUsers;
        public Boolean channelEmail;   // null in a patch = leave unchanged
        public Boolean channelBanner;
        public Boolean channelWhatsNew;
        public int revision = 0;
        public int testedRevision = -1;
        public String testedBy, testedAt;
        public String scheduledFor;                  // yyyy-MM-dd'T'HH:mm (server-local)
        public String createdBy, createdByDisplay, createdAt;
        public String lastEditedBy, lastEditedByDisplay, lastEditedAt;
        public List<Map<String, String>> revisions = new ArrayList<>();
        public String sentAt, sentBy;
        public int recipientCount, sentCount, failedCount;
        public List<Map<String, String>> failures = new ArrayList<>();
        public List<String> bannerDismissedBy = new ArrayList<>();
    }

    // ---- lifecycle / persistence -----------------------------------------

    @PostConstruct
    public synchronized void init() {
        store.clear();
        File f = indexFile();
        if (!f.exists()) return;
        try {
            Map<String, Announcement> data = mapper.readValue(f, new TypeReference<Map<String, Announcement>>() {});
            store.putAll(data);
            LOG.info("[ANNOUNCE] loaded " + store.size() + " announcements from " + f.getPath());
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[ANNOUNCE] failed to load " + f.getPath() + ": " + ex.getMessage());
        }
    }

    private File indexFile() { return new File(baseDir, "index.json"); }

    private synchronized void save() {
        try {
            File f = indexFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, store);
            try {
                Files.move(tmp.toPath(), f.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "[ANNOUNCE] save failed: " + ex.getMessage(), ex);
        }
    }

    // ---- CRUD -------------------------------------------------------------

    public synchronized List<Announcement> list() {
        List<Announcement> all = new ArrayList<>(store.values());
        all.sort(Comparator
                .comparing((Announcement a) -> !"scheduled".equals(a.status))
                .thenComparing(a -> {
                    String k = "scheduled".equals(a.status) ? a.scheduledFor
                            : (a.sentAt != null ? a.sentAt : a.createdAt);
                    return k == null ? "" : k;
                }, Comparator.reverseOrder()));
        return all;
    }

    public synchronized Announcement get(String id) { return store.get(id); }

    /**
     * Create (patch.id == null) or update a record. Only send-affecting fields
     * (subject, bodyHtml, audienceType, specificUsers) bump {@code revision}
     * and thereby relock sending; draft notes and channel toggles don't.
     */
    public synchronized Announcement upsert(Announcement patch, String username, String displayName) {
        String now = LocalDateTime.now().format(TS);
        Announcement a;
        boolean created = false;
        if (patch.id == null || !store.containsKey(patch.id)) {
            a = new Announcement();
            a.id = patch.id != null ? patch.id
                    : "ann-" + System.currentTimeMillis() + "-"
                      + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
            a.createdBy = username;
            a.createdByDisplay = displayName;
            a.createdAt = now;
            a.subject = "";
            a.draft = "";
            a.bodyHtml = "";
            a.audienceType = "everLoggedIn";
            a.specificUsers = new ArrayList<>();
            a.channelEmail = Boolean.TRUE;
            a.channelBanner = Boolean.FALSE;
            a.channelWhatsNew = Boolean.FALSE;
            store.put(a.id, a);
            created = true;
        } else {
            a = store.get(patch.id);
            if ("sent".equals(a.status)) {
                throw new IllegalStateException("Sent announcements are read-only — use Duplicate.");
            }
        }

        boolean contentChanged = created
                || (patch.subject != null && !Objects.equals(nz(a.subject), patch.subject))
                || (patch.bodyHtml != null && !Objects.equals(nz(a.bodyHtml), patch.bodyHtml))
                || (patch.audienceType != null && !Objects.equals(nz(a.audienceType), patch.audienceType))
                || (patch.specificUsers != null && !Objects.equals(a.specificUsers, patch.specificUsers));

        a.subject = patch.subject == null ? a.subject : patch.subject;
        a.draft = patch.draft == null ? a.draft : patch.draft;
        if (patch.bodyHtml != null) a.bodyHtml = patch.bodyHtml;
        if (patch.audienceType != null) a.audienceType = patch.audienceType;
        if (patch.specificUsers != null) a.specificUsers = new ArrayList<>(patch.specificUsers);
        if (patch.channelEmail != null) a.channelEmail = patch.channelEmail;
        if (patch.channelBanner != null) a.channelBanner = patch.channelBanner;
        if (patch.channelWhatsNew != null) a.channelWhatsNew = patch.channelWhatsNew;

        if (contentChanged && !created) a.revision++;
        a.lastEditedBy = username;
        a.lastEditedByDisplay = displayName;
        a.lastEditedAt = now;
        addRevision(a, now, username, displayName, created ? "created" : (contentChanged ? "edited content" : "edited"));
        save();
        return a;
    }

    private static void addRevision(Announcement a, String at, String by, String byDisplay, String note) {
        Map<String, String> r = new HashMap<>();
        r.put("at", at);
        r.put("by", by);
        r.put("byDisplay", byDisplay);
        r.put("note", note);
        a.revisions.add(r);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ---- test gate ----------------------------------------------------------

    public synchronized void markTested(String id, String username, String displayName) {
        Announcement a = require(id);
        a.testedRevision = a.revision;
        a.testedBy = username;
        a.testedAt = LocalDateTime.now().format(TS);
        save();
    }

    /** Send unlocks only when a test email went out for the CURRENT revision. */
    public boolean canSend(Announcement a) {
        return a != null && a.testedRevision == a.revision;
    }

    // ---- schedule -------------------------------------------------------------

    public synchronized Announcement schedule(String id, String whenLocal, String username, String displayName) {
        Announcement a = require(id);
        if (!canSend(a)) throw new IllegalStateException("A test email is required for this revision before scheduling.");
        LocalDateTime when;
        try {
            when = LocalDateTime.parse(whenLocal, SCHED);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Bad schedule time (expected yyyy-MM-ddTHH:mm): " + whenLocal);
        }
        if (!when.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("Schedule time must be in the future.");
        a.scheduledFor = when.format(SCHED);
        a.status = "scheduled";
        addRevision(a, LocalDateTime.now().format(TS), username, displayName, "scheduled for " + a.scheduledFor);
        save();
        return a;
    }

    public synchronized Announcement cancelSchedule(String id, String username, String displayName) {
        Announcement a = require(id);
        a.status = "draft";
        a.scheduledFor = null;
        addRevision(a, LocalDateTime.now().format(TS), username, displayName, "schedule cancelled");
        save();
        return a;
    }

    static boolean isDue(Announcement a, LocalDateTime now) {
        if (a == null || !"scheduled".equals(a.status) || a.scheduledFor == null) return false;
        try {
            return !LocalDateTime.parse(a.scheduledFor, SCHED).isAfter(now);
        } catch (Exception ex) {
            return false;
        }
    }

    /** 60s poller; no-ops entirely when app.scheduling.disabled=true (global switch). */
    @Scheduled(fixedDelay = 60000)
    public void pollScheduled() {
        if (maintenanceService != null && maintenanceService.isInMaintenanceMode()) return;
        List<Announcement> due = new ArrayList<>();
        synchronized (this) {
            LocalDateTime now = LocalDateTime.now();
            for (Announcement a : store.values()) {
                if (isDue(a, now) && canSend(a)) due.add(a);
            }
        }
        for (Announcement a : due) {
            try {
                LOG.info("[ANNOUNCE] scheduled send firing: " + a.id + " (" + a.subject + ")");
                sendNow(a.id, "scheduler", "Scheduler (created by " + nz(a.createdByDisplay) + ")");
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "[ANNOUNCE] scheduled send failed for " + a.id + ": " + ex.getMessage(), ex);
            }
        }
    }

    // ---- send -------------------------------------------------------------------

    /** Renders the full email (with ${firstName} token) for preview / test / send. */
    public String renderFull(Announcement a) {
        String dateLabel = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US));
        return emailService.renderEmail(a.subject, a.bodyHtml, dateLabel);
    }

    public synchronized Map<String, Object> sendNow(String id, String username, String displayName) {
        Announcement a = require(id);
        if ("sent".equals(a.status)) throw new IllegalStateException("Already sent.");
        if (!canSend(a)) throw new IllegalStateException("A test email is required for this revision.");

        List<AnnouncementEmailService.Recipient> recipients = resolveAudience(a);
        a.recipientCount = recipients.size();

        String html = renderFull(a);
        if (!Boolean.FALSE.equals(a.channelEmail)) {
            AnnouncementEmailService.SendResult res = emailService.sendToRecipients(a.subject, html, recipients);
            a.sentCount = res.sent;
            a.failedCount = res.failures.size();
            a.failures = res.failures;
        } else {
            a.sentCount = 0;
            a.failedCount = 0;
        }

        // Archive the exact (generic-rendered) HTML for "Open as sent"
        try {
            File archive = new File(baseDir, a.id + ".html");
            if (archive.getParentFile() != null) archive.getParentFile().mkdirs();
            Files.write(archive.toPath(),
                    AnnouncementEmailService.personalize(html, "there").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[ANNOUNCE] archive write failed for " + a.id + ": " + ex.getMessage());
        }

        a.status = "sent";
        a.sentAt = LocalDateTime.now().format(TS);
        a.sentBy = username;
        addRevision(a, a.sentAt, username, displayName, "sent to " + a.sentCount + "/" + a.recipientCount + " recipients");
        save();

        if (activityLogger != null) {
            activityLogger.log(username, displayName, "ANNOUNCEMENT_SENT",
                    "id=" + a.id + " subject=\"" + a.subject + "\" audience=" + a.audienceType
                    + " sent=" + a.sentCount + " failed=" + a.failedCount);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("announcement", a);
        out.put("sent", a.sentCount);
        out.put("failed", a.failedCount);
        return out;
    }

    /** Test email to the caller only; stamps the test gate for the current revision. */
    public void sendTest(String id, String callerEmail, String callerDisplay, String username) throws Exception {
        Announcement a = require(id);
        emailService.sendTest(a.subject, renderFull(a), callerEmail, callerDisplay);
        markTested(id, username, callerDisplay);
        if (activityLogger != null) {
            activityLogger.log(username, callerDisplay, "ANNOUNCEMENT_TEST",
                    "id=" + id + " rev=" + a.revision);
        }
    }

    public String getArchivedHtml(String id) {
        try {
            File f = new File(baseDir, id + ".html");
            if (!f.exists()) return null;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }

    private Announcement require(String id) {
        Announcement a = store.get(id);
        if (a == null) throw new IllegalArgumentException("No such announcement: " + id);
        return a;
    }

    // ---- audience ------------------------------------------------------------------

    /**
     * Resolved at SEND time so counts stay fresh (spec §8). Email resolution:
     * DL roster first (cached LDAP), then explicit UserRecord.email.
     */
    public List<AnnouncementEmailService.Recipient> resolveAudience(Announcement a) {
        Map<String, LdapAuthService.DirectoryUser> roster = rosterByUsername();
        Map<String, UserPermissionsService.UserRecord> records =
                userPermissionsService != null ? userPermissionsService.allRecords() : new HashMap<>();
        List<AnnouncementEmailService.Recipient> out = new ArrayList<>();

        if ("dl".equals(a.audienceType)) {
            for (LdapAuthService.DirectoryUser u : roster.values()) {
                out.add(new AnnouncementEmailService.Recipient(u.username, u.displayName, u.email));
            }
            return out;
        }

        List<String> usernames;
        if ("specific".equals(a.audienceType)) {
            usernames = a.specificUsers == null ? new ArrayList<>() : a.specificUsers;
        } else { // everLoggedIn
            usernames = new ArrayList<>();
            if (userPermissionsService != null) {
                usernames.addAll(userPermissionsService.loginHistory().keySet());
            }
        }
        Map<String, UserPermissionsService.LoginInfo> logins =
                userPermissionsService != null ? userPermissionsService.loginHistory() : new HashMap<>();
        for (String u : usernames) {
            String key = u == null ? "" : u.toLowerCase(Locale.ROOT);
            LdapAuthService.DirectoryUser d = roster.get(key);
            UserPermissionsService.UserRecord r = records.get(key);
            UserPermissionsService.LoginInfo li = logins.get(key);
            String email = d != null && d.email != null && !d.email.isEmpty() ? d.email
                    : (r != null && r.email != null && !r.email.isEmpty() ? r.email : null);
            String display = d != null ? d.displayName
                    : (li != null ? li.displayName : (r != null ? r.displayName : u));
            if (email == null && ldapAuthService != null) {
                // Logged-in-but-not-in-DL users (and hand-picked usernames) may
                // have no roster/record email — fall back to a cached AD lookup.
                try {
                    LdapAuthService.UserInfo info = ldapAuthService.lookupUserByUsername(u);
                    if (info != null) {
                        if (info.email != null && !info.email.isEmpty()) email = info.email;
                        if (info.displayName != null && !info.displayName.isEmpty()) display = info.displayName;
                    }
                } catch (Exception ex) {
                    LOG.log(Level.FINE, "[ANNOUNCE] AD lookup failed for " + u + ": " + ex.getMessage());
                }
            }
            out.add(new AnnouncementEmailService.Recipient(u, display, email));
        }
        return out;
    }

    private Map<String, LdapAuthService.DirectoryUser> rosterByUsername() {
        Map<String, LdapAuthService.DirectoryUser> byName = new LinkedHashMap<>();
        if (ldapAuthService == null) return byName;
        try {
            for (LdapAuthService.DirectoryUser u : ldapAuthService.listAccessGroupCandidates()) {
                if (u.username != null) byName.put(u.username.toLowerCase(Locale.ROOT), u);
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[ANNOUNCE] DL roster unavailable: " + ex.getMessage());
        }
        return byName;
    }

    /** Live counts for the audience radios. */
    public Map<String, Object> audienceCounts() {
        Map<String, LdapAuthService.DirectoryUser> roster = rosterByUsername();
        Map<String, UserPermissionsService.LoginInfo> logins =
                userPermissionsService != null ? userPermissionsService.loginHistory() : new HashMap<>();
        int everLoggedIn = logins.size();
        int dl = roster.size();
        int dlNeverLogged = 0;
        for (String u : roster.keySet()) {
            if (!logins.containsKey(u)) dlNeverLogged++;
        }
        List<Map<String, Object>> users = new ArrayList<>();
        // union of roster + logins for the specific-users picker
        Map<String, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (Map.Entry<String, LdapAuthService.DirectoryUser> e : roster.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("username", e.getValue().username);
            m.put("displayName", e.getValue().displayName);
            m.put("email", e.getValue().email);
            m.put("hasLoggedIn", logins.containsKey(e.getKey()));
            byUser.put(e.getKey(), m);
        }
        for (Map.Entry<String, UserPermissionsService.LoginInfo> e : logins.entrySet()) {
            if (byUser.containsKey(e.getKey())) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("username", e.getValue().username);
            m.put("displayName", e.getValue().displayName);
            m.put("email", null);
            m.put("hasLoggedIn", true);
            byUser.put(e.getKey(), m);
        }
        users.addAll(byUser.values());
        Map<String, Object> out = new HashMap<>();
        out.put("everLoggedIn", everLoggedIn);
        out.put("dl", dl);
        out.put("dlNeverLogged", dlNeverLogged);
        out.put("users", users);
        return out;
    }

    // ---- in-app banner ------------------------------------------------------------------

    /** Newest sent banner-channel announcement (≤14 days) not dismissed by this user. */
    public synchronized Announcement activeBannerFor(String username) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(BANNER_TTL_DAYS);
        Announcement best = null;
        for (Announcement a : store.values()) {
            if (!"sent".equals(a.status) || !Boolean.TRUE.equals(a.channelBanner) || a.sentAt == null) continue;
            if (a.bannerDismissedBy != null && a.bannerDismissedBy.contains(username)) continue;
            try {
                if (LocalDateTime.parse(a.sentAt, TS).isBefore(cutoff)) continue;
            } catch (Exception ex) {
                continue;
            }
            if (best == null || a.sentAt.compareTo(best.sentAt) > 0) best = a;
        }
        return best;
    }

    public synchronized void dismissBanner(String id, String username) {
        Announcement a = require(id);
        if (a.bannerDismissedBy == null) a.bannerDismissedBy = new ArrayList<>();
        if (!a.bannerDismissedBy.contains(username)) {
            a.bannerDismissedBy.add(username);
            save();
        }
    }

    /** Test hook: force a record into sent state with a given banner flag + sentAt. */
    public synchronized void forceSentForTest(String id, boolean channelBanner, LocalDateTime sentAt) {
        Announcement a = require(id);
        a.status = "sent";
        a.channelBanner = channelBanner;
        a.sentAt = sentAt.format(TS);
        save();
    }

    // ---- credential scrub ------------------------------------------------------------------

    /**
     * Defense-in-depth (CLAUDE.md rule): strips anything that looks like a
     * credential VALUE from AI output before it can reach an email body. The
     * prompt-side guardrail asks the model not to emit them; this catches the
     * cases where it does anyway. Errs toward over-redaction.
     */
    public static String scrubCredentials(String s) {
        if (s == null) return null;
        return s.replaceAll(
                "(?i)\\b(passwords?|passwd|pwd|passcodes?|tokens?|api[-_ ]?keys?|secrets?|credentials?)\\b(\\s*(?:is|was|[:=\\u2192-])\\s*)\\S+",
                "$1$2[redacted]");
    }
}
