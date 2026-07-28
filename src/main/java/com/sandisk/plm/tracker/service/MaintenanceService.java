package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Scheduled-maintenance flow.
 *
 * <p>Allowed admins (configured via {@code app.maintenance.allowed-users}) can schedule
 * a graceful shutdown N minutes in the future. While the shutdown is pending:
 * <ul>
 *   <li>Every active page polls {@code /api/maintenance/status} and shows a top-of-screen
 *       countdown banner so users know the app is going down.</li>
 *   <li>An "going down" email is sent immediately to each currently-active user so they're
 *       notified even if they close the browser before the timer fires.</li>
 * </ul>
 *
 * <p>When the timer fires, this service writes a sentinel file
 * {@code ./data/maintenance-coming-back.json} that captures the recipient list and
 * shutdown reason, then calls {@code System.exit(0)}. The {@code run-loop.bat} wrapper
 * on the prod host restarts the JVM. On boot, {@link #init()} checks for the sentinel
 * file; if present, sends a "we're back" email to each recipient and deletes the file.
 *
 * <p>State persistence: the in-flight pending shutdown is also persisted to
 * {@code ./data/pending-shutdown.json} so an unexpected JVM crash doesn't lose the
 * scheduled time (we re-arm the timer on boot if the target time is still in the future).
 */
@Service
public class MaintenanceService {

    private static final Logger logger = Logger.getLogger(MaintenanceService.class.getName());

    private static final String PENDING_FILE = "./data/pending-shutdown.json";
    private static final String COMING_BACK_FILE = "./data/maintenance-coming-back.json";
    private static final long ACTIVE_USER_WINDOW_MS = 30L * 60 * 1000;
    private static final long MIN_MINUTES = 1;
    private static final long MAX_MINUTES = 60;

    private final ObjectMapper om = new ObjectMapper();
    private final AtomicReference<PendingShutdown> pending = new AtomicReference<>();
    private final Timer timer = new Timer("maint-timer", true);
    private TimerTask currentTask;

    // Maintenance-mode state. True when the watchdog respawned the JVM with
    // the same JAR after a scheduled shutdown fired — in this state, every
    // request is intercepted by MaintenanceModeFilter and served the static
    // maintenance page until either (a) the admin deploys a new JAR (auto-
    // detected via JAR mtime > flag mtime on the next boot) or (b) hits
    // /api/maintenance/exit-mode manually.
    private final AtomicBoolean inMaintenanceMode = new AtomicBoolean(false);
    private final AtomicReference<Map<String, Object>> maintenanceMetadata = new AtomicReference<>();

    @Value("${app.maintenance.allowed-users:8252,25868}")
    private String allowedUsersCsv;

    /** Max total minutes end users can push a pending shutdown back, across all
     *  "+5 / +10 min" clicks combined. Capped so the deploy can't be delayed
     *  indefinitely. Per-pending budget = original target + this many minutes. */
    @Value("${app.maintenance.max-extend-minutes:20}")
    private int maxExtendMinutes;

    @Value("${mail.smtp.host:mailrelay.sandisk.com}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from:PLM-Toolkit@sandisk.com}")
    private String fromAddress;

    @Value("${app.admin-email:pdl-plm-admin@sandisk.com}")
    private String adminEmail;

    @Value("${app.maintenance.app-url:http://uls-ep-aglipccb:8090}")
    private String appUrl;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private ActivityLogger activityLogger;

    @PostConstruct
    public void init() {
        // 1. Process the maintenance-mode flag (written by fire() before System.exit).
        //
        //    The flag is the ONE signal that distinguishes three boot scenarios:
        //
        //    a) No flag → normal boot, nothing to do.
        //    b) Flag exists, JAR mtime > flag mtime → admin actually deployed a
        //       new JAR during the maintenance window. Send back-online emails,
        //       delete flag, boot normally.
        //    c) Flag exists, JAR didn't change → the watchdog auto-respawned
        //       the JVM but no deploy happened yet. Enter maintenance mode so
        //       MaintenanceModeFilter serves the static maintenance page until
        //       the admin actually deploys (which falls into case (b) on next boot).
        //
        // Deploy-detection signal: BOTH file size AND mtime. We can't rely on mtime
        // alone because Windows' `copy /Y` preserves the source file's mtime on the
        // destination. After a deploy: live-jar mtime = staging-jar mtime, which can
        // easily be older than the maintenance flag's mtime → false "no deploy" verdict.
        // Comparing the JAR's size + mtime against snapshot captured at fire() time
        // catches the case: if EITHER changed, a deploy happened. (Different builds
        // of this codebase always differ in size — Spring Boot reshuffles the BOOT-INF
        // layout, and our whats-new.js etc. content varies.)
        File flagFile = new File(COMING_BACK_FILE);
        if (flagFile.exists()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = om.readValue(flagFile, Map.class);
                File jarFile = locateRunningJar();
                long jarMtime = jarFile == null ? 0L : jarFile.lastModified();
                long jarSize  = jarFile == null ? 0L : jarFile.length();
                long flagMtime = flagFile.lastModified();
                Long preMtime = asLong(data.get("preShutdownJarMtime"));
                Long preSize  = asLong(data.get("preShutdownJarSize"));

                // Primary check: did the JAR change between fire() and init()?
                boolean jarChanged = (jarFile != null)
                        && ((preSize  != null && preSize  != jarSize)
                         || (preMtime != null && preMtime != jarMtime));
                // Fallback for flags written before this fix (no preShutdownJarSize):
                // fall back to the legacy mtime-vs-flag-mtime check.
                boolean legacySignal = (jarFile != null) && (preSize == null)
                        && (jarMtime > flagMtime);
                boolean deployHappened = jarChanged || legacySignal;

                if (deployHappened) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> recipients = (List<Map<String, String>>) data.get("recipients");
                    String reason = (String) data.get("reason");
                    String scheduledByDisplay = (String) data.getOrDefault("scheduledByDisplayName", "PLM admin");
                    logger.info("[MAINT] JAR change detected (jar=" + (jarFile == null ? "?" : jarFile.getName())
                        + " size=" + jarSize + " mtime=" + jarMtime
                        + " preSize=" + preSize + " preMtime=" + preMtime
                        + ") — exiting maintenance mode.");
                    if (recipients != null && !recipients.isEmpty()) {
                        sendBackOnlineEmails(recipients, reason, scheduledByDisplay);
                        activityLogger.log("system", "PLM Toolkit",
                            "MAINTENANCE_BACK_ONLINE",
                            "notified=" + recipients.size() + " scheduledBy=" + scheduledByDisplay);
                    }
                    if (!flagFile.delete()) {
                        logger.warning("[MAINT] Could not delete " + flagFile.getAbsolutePath()
                            + " — may resend on next boot.");
                    }
                } else {
                    inMaintenanceMode.set(true);
                    maintenanceMetadata.set(data);
                    logger.warning("[MAINT] Maintenance flag present and JAR unchanged (jar="
                        + (jarFile == null ? "<unknown>" : jarFile.getName())
                        + " size=" + jarSize + " mtime=" + jarMtime
                        + " preSize=" + preSize + " preMtime=" + preMtime
                        + ") — entering maintenance mode. "
                        + "Deploy a new JAR (or POST /api/maintenance/exit-mode) to exit.");
                }
            } catch (Exception e) {
                logger.warning("[MAINT] Failed to process maintenance flag: " + e.getMessage());
            }
        }

        // 2. Re-arm any pending shutdown that survived a crash.
        File pendingFile = new File(PENDING_FILE);
        if (pendingFile.exists()) {
            try {
                PendingShutdown p = om.readValue(pendingFile, PendingShutdown.class);
                long now = System.currentTimeMillis();
                if (p != null && p.targetEpochMs > now) {
                    pending.set(p);
                    armTimer(p.targetEpochMs);
                    logger.info("[MAINT] Re-armed pending shutdown (target=" + p.targetEpochMs
                        + ", in " + ((p.targetEpochMs - now) / 1000) + "s).");
                } else {
                    if (!pendingFile.delete()) logger.warning("[MAINT] Stale pending file not deleted.");
                }
            } catch (Exception e) {
                logger.warning("[MAINT] Failed to re-arm pending shutdown: " + e.getMessage());
            }
        }
    }

    /**
     * Schedule a graceful shutdown {@code minutes} from now. Captures the current
     * active-user list, sends them an "going down" email, persists state, and starts
     * the timer. Throws {@link IllegalArgumentException} if a shutdown is already pending
     * (call {@link #cancel(String, String)} first to reschedule).
     */
    public synchronized PendingShutdown schedule(int minutes, String reason,
                                                  String scheduledByUsername,
                                                  String scheduledByDisplayName) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException("minutes must be between "
                + MIN_MINUTES + " and " + MAX_MINUTES + " (got " + minutes + ")");
        }
        if (pending.get() != null) {
            throw new IllegalArgumentException(
                "A shutdown is already pending. Cancel it first if you want to reschedule.");
        }

        long now = System.currentTimeMillis();
        long target = now + (minutes * 60_000L);

        PendingShutdown p = new PendingShutdown();
        p.scheduledAt = now;
        p.targetEpochMs = target;
        p.maxTargetEpochMs = target + (maxExtendMinutes * 60_000L);
        p.totalExtendedMs = 0;
        p.reason = reason == null ? "" : reason.trim();
        p.scheduledByUsername = scheduledByUsername;
        p.scheduledByDisplayName = scheduledByDisplayName;
        p.recipients = snapshotActiveRecipients(scheduledByUsername);

        pending.set(p);
        persist(p);
        armTimer(target);

        logger.info("[MAINT] Shutdown scheduled by " + scheduledByDisplayName
            + " in " + minutes + "min (target=" + Instant.ofEpochMilli(target)
            + ", recipients=" + p.recipients.size() + ")");

        // Email everyone immediately so they're warned even if they close the browser.
        try {
            sendGoingDownEmails(p);
        } catch (Exception e) {
            logger.warning("[MAINT] sendGoingDownEmails failed: " + e.getMessage());
        }

        activityLogger.log(scheduledByUsername, scheduledByDisplayName,
            "MAINTENANCE_SCHEDULE",
            "minutes=" + minutes + " recipients=" + p.recipients.size()
                + " reason=" + p.reason);
        return p;
    }

    /**
     * Fire a warning window without arming the System.exit timer. Sets the
     * in-app banner, snapshots recipients, sends the "going down" email,
     * but does NOT schedule the JVM auto-exit and does NOT persist to the
     * pending file. Used by the deploy.bat path where deploy.bat owns the
     * actual JVM shutdown after its own sleep.
     *
     * <p>Why this exists (PT-92, 2026-06-12): when deploy.bat called the
     * full {@link #schedule(int, String, String, String)} method, the JVM
     * would call {@code System.exit(0)} at the 5-minute mark — which is
     * roughly when deploy.bat is still mid-sleep. The watchdog would then
     * respawn a JVM from the OLD JAR, mid-boot, just as deploy.bat woke up
     * to swap the JAR. Net result: a parallel JVM with the log file held
     * open, file-lock errors on the new watchdog. By NOT arming the timer
     * here, the JVM stays up for the full sleep window and deploy.bat is
     * the only thing that ever kills it.</p>
     *
     * <p>Banner + email behaviour is identical to {@link #schedule(int, String, String, String)}.
     * The "back online" email on the next JVM boot does NOT fire (because
     * {@link #fire()} never runs to write the maintenance flag), which is
     * acceptable for deploy.bat's path — users already saw the going-down
     * banner; the toolkit just comes back up clean.</p>
     */
    public synchronized PendingShutdown fireWarningWithoutTimer(int minutes, String reason,
                                                                 String scheduledByUsername,
                                                                 String scheduledByDisplayName) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException("minutes must be between "
                + MIN_MINUTES + " and " + MAX_MINUTES + " (got " + minutes + ")");
        }
        if (pending.get() != null) {
            throw new IllegalArgumentException(
                "A shutdown is already pending. Cancel it first if you want to reschedule.");
        }

        long now = System.currentTimeMillis();
        long target = now + (minutes * 60_000L);

        PendingShutdown p = new PendingShutdown();
        p.scheduledAt = now;
        p.targetEpochMs = target;
        p.maxTargetEpochMs = target + (maxExtendMinutes * 60_000L);
        p.totalExtendedMs = 0;
        p.reason = reason == null ? "" : reason.trim();
        p.scheduledByUsername = scheduledByUsername;
        p.scheduledByDisplayName = scheduledByDisplayName;
        p.recipients = snapshotActiveRecipients(scheduledByUsername);

        pending.set(p);
        // Intentionally NOT calling persist(p) — see method javadoc.
        // Intentionally NOT calling armTimer(target) — deploy.bat owns the shutdown.

        logger.info("[MAINT] Warning-only window opened by " + scheduledByDisplayName
            + " for " + minutes + "min (target=" + Instant.ofEpochMilli(target)
            + ", recipients=" + p.recipients.size() + ") — no timer armed");

        try {
            sendGoingDownEmails(p);
        } catch (Exception e) {
            logger.warning("[MAINT] sendGoingDownEmails failed: " + e.getMessage());
        }

        activityLogger.log(scheduledByUsername, scheduledByDisplayName,
            "MAINTENANCE_WARN_ONLY",
            "minutes=" + minutes + " recipients=" + p.recipients.size()
                + " reason=" + p.reason);
        return p;
    }

    /** Cancel any pending shutdown. Notifies recipients via email. No-op if nothing pending. */
    public synchronized PendingShutdown cancel(String cancelledByUsername, String cancelledByDisplayName) {
        PendingShutdown p = pending.getAndSet(null);
        if (p == null) return null;
        if (currentTask != null) { currentTask.cancel(); currentTask = null; }
        timer.purge();
        new File(PENDING_FILE).delete();
        try {
            sendCancelledEmails(p, cancelledByDisplayName);
        } catch (Exception e) {
            logger.warning("[MAINT] sendCancelledEmails failed: " + e.getMessage());
        }
        activityLogger.log(cancelledByUsername, cancelledByDisplayName,
            "MAINTENANCE_CANCEL", "originalTarget=" + p.targetEpochMs);
        logger.info("[MAINT] Shutdown cancelled by " + cancelledByDisplayName);
        return p;
    }

    public PendingShutdown getPending() { return pending.get(); }

    public int getMaxExtendMinutes() { return maxExtendMinutes; }

    /** Remaining extension budget (ms) for the current pending shutdown, or 0 if none. */
    public long extendBudgetMs() {
        PendingShutdown p = pending.get();
        if (p == null) return 0L;
        return Math.max(0L, p.maxTargetEpochMs - p.targetEpochMs);
    }

    /**
     * End-user self-service: push the pending shutdown back by {@code extraMinutes}
     * (5 or 10). Capped at {@code maxTargetEpochMs} (original target +
     * {@code app.maintenance.max-extend-minutes}) so the deploy can't be delayed
     * forever. Works for BOTH the admin-scheduled path (re-arms the System.exit
     * timer + re-persists) and the deploy.bat warning path (in-memory only —
     * deploy.bat polls {@code /deploy-ready} and honors the new target).
     *
     * @return a result map: {@code success}, plus {@code targetEpochMs},
     *         {@code extendBudgetMs}, {@code appliedMinutes} on success, or
     *         {@code error} on failure.
     */
    public synchronized Map<String, Object> extend(int extraMinutes, String username, String displayName) {
        Map<String, Object> r = new LinkedHashMap<>();
        PendingShutdown p = pending.get();
        if (p == null) {
            r.put("success", false);
            r.put("error", "No maintenance is currently scheduled.");
            return r;
        }
        if (extraMinutes != 5 && extraMinutes != 10) {
            r.put("success", false);
            r.put("error", "You can add 5 or 10 minutes at a time.");
            return r;
        }
        long budget = p.maxTargetEpochMs - p.targetEpochMs;
        if (budget <= 0) {
            r.put("success", false);
            r.put("error", "Maximum extension reached — please save your work now.");
            r.put("targetEpochMs", p.targetEpochMs);
            r.put("extendBudgetMs", 0L);
            return r;
        }
        long requested = extraMinutes * 60_000L;
        long applied = Math.min(requested, budget);   // clamp to the remaining budget
        p.targetEpochMs += applied;
        p.totalExtendedMs += applied;

        // Admin-scheduled path armed a server-side System.exit timer — re-arm it to
        // the new target and re-persist so a crash re-arms correctly. The deploy.bat
        // path armed no timer (currentTask == null); it polls /deploy-ready instead.
        if (currentTask != null) {
            armTimer(p.targetEpochMs);
            persist(p);
        }

        activityLogger.log(username, displayName, "MAINTENANCE_EXTEND",
            "by=" + displayName + " +" + (applied / 60_000L) + "min newTarget=" + p.targetEpochMs
                + " totalExtended=" + (p.totalExtendedMs / 60_000L) + "min");
        logger.info("[MAINT] Window extended by " + displayName + " +" + (applied / 60_000L)
            + "min (newTarget=" + Instant.ofEpochMilli(p.targetEpochMs)
            + ", budgetLeftMs=" + (p.maxTargetEpochMs - p.targetEpochMs) + ")");

        r.put("success", true);
        r.put("targetEpochMs", p.targetEpochMs);
        r.put("extendBudgetMs", p.maxTargetEpochMs - p.targetEpochMs);
        r.put("appliedMinutes", applied / 60_000L);
        return r;
    }

    /**
     * True when the JVM booted with a maintenance-mode flag from a prior
     * scheduled shutdown and no fresh JAR was detected. Defense-in-depth:
     * also returns false if the admin manually deleted the flag file from
     * disk, so the app can exit maintenance mode without a JVM restart.
     */
    public boolean isInMaintenanceMode() {
        if (!inMaintenanceMode.get()) return false;
        File flagFile = new File(COMING_BACK_FILE);
        if (!flagFile.exists()) {
            inMaintenanceMode.set(false);
            maintenanceMetadata.set(null);
            logger.info("[MAINT] Flag file removed from disk — exiting maintenance mode.");
            return false;
        }
        return true;
    }

    /** Metadata captured when the shutdown fired (recipients, reason, scheduler). */
    public Map<String, Object> getMaintenanceMetadata() {
        return maintenanceMetadata.get();
    }

    /**
     * Manually exit maintenance mode without redeploying. Sends back-online
     * emails to the original recipient list, deletes the flag, and clears
     * the in-memory state. No-op when not in maintenance mode.
     */
    public synchronized void exitMaintenanceMode(String adminUsername, String adminDisplayName) {
        if (!inMaintenanceMode.get()) return;
        Map<String, Object> meta = maintenanceMetadata.get();
        File flagFile = new File(COMING_BACK_FILE);
        if (flagFile.exists() && !flagFile.delete()) {
            logger.warning("[MAINT] Could not delete " + flagFile.getAbsolutePath());
        }
        inMaintenanceMode.set(false);
        maintenanceMetadata.set(null);
        if (meta != null) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> recipients = (List<Map<String, String>>) meta.get("recipients");
                String reason = (String) meta.get("reason");
                String scheduledByDisplay = (String) meta.getOrDefault("scheduledByDisplayName", "PLM admin");
                if (recipients != null && !recipients.isEmpty()) {
                    sendBackOnlineEmails(recipients, reason, scheduledByDisplay);
                }
            } catch (Exception e) {
                logger.warning("[MAINT] back-online emails on manual exit failed: " + e.getMessage());
            }
        }
        activityLogger.log(adminUsername, adminDisplayName, "MAINTENANCE_EXIT_MANUAL",
            "by=" + adminDisplayName);
        logger.info("[MAINT] Maintenance mode manually exited by " + adminDisplayName);
    }

    /** Allowlist check — exact username match against the configured CSV. */
    public boolean isAllowed(String username) {
        if (username == null || username.isEmpty()) return false;
        String[] allowed = allowedUsersCsv.split(",");
        for (String a : allowed) {
            if (username.equalsIgnoreCase(a.trim())) return true;
        }
        return false;
    }

    public List<String> getAllowedUsers() {
        List<String> out = new ArrayList<>();
        for (String a : allowedUsersCsv.split(",")) {
            String t = a.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private List<Map<String, String>> snapshotActiveRecipients(String scheduledByUsername) {
        // Always include the scheduling admin so they get the back-online email too.
        // De-dupe by email (lowercased).
        Set<String> seenEmails = new LinkedHashSet<>();
        List<Map<String, String>> out = new ArrayList<>();
        for (SessionRegistry.ActiveUser u : sessionRegistry.listActive(ACTIVE_USER_WINDOW_MS)) {
            if (u.email == null || u.email.isEmpty()) continue;
            String key = u.email.toLowerCase();
            if (seenEmails.add(key)) {
                Map<String, String> r = new LinkedHashMap<>();
                r.put("username", u.username);
                r.put("displayName", u.displayName == null ? u.username : u.displayName);
                r.put("email", u.email);
                out.add(r);
            }
        }
        return out;
    }

    private void armTimer(long targetEpochMs) {
        if (currentTask != null) currentTask.cancel();
        currentTask = new TimerTask() {
            @Override public void run() { fire(); }
        };
        long delay = Math.max(1000L, targetEpochMs - System.currentTimeMillis());
        timer.schedule(currentTask, delay);
    }

    private void fire() {
        PendingShutdown p = pending.get();
        if (p == null) return;
        logger.warning("[MAINT] Shutdown timer fired — writing maintenance flag and exiting JVM. "
            + "When the watchdog respawns the JVM, init() will see the flag and either "
            + "(a) detect a fresh JAR and exit maintenance mode normally, or "
            + "(b) enter maintenance mode and serve the static page until the deploy lands.");

        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("recipients", p.recipients);
            data.put("reason", p.reason);
            data.put("scheduledByDisplayName", p.scheduledByDisplayName);
            data.put("triggeredAtEpochMs", System.currentTimeMillis());
            // Snapshot the JAR's size + mtime so init() on the next boot can detect
            // whether a deploy happened (Windows copy preserves source mtime, so the
            // legacy "JAR mtime > flag mtime" check isn't reliable across back-to-back
            // deploys — captured here to make the comparison size-aware too).
            File preShutdownJar = locateRunningJar();
            if (preShutdownJar != null) {
                data.put("preShutdownJarPath", preShutdownJar.getAbsolutePath());
                data.put("preShutdownJarMtime", preShutdownJar.lastModified());
                data.put("preShutdownJarSize", preShutdownJar.length());
            }
            File f = new File(COMING_BACK_FILE);
            f.getParentFile().mkdirs();
            om.writerWithDefaultPrettyPrinter().writeValue(f, data);
        } catch (Exception e) {
            logger.warning("[MAINT] Failed to write maintenance flag: " + e.getMessage());
        }
        // Best-effort cleanup of the pending file (we're going down anyway)
        new File(PENDING_FILE).delete();
        // Exit. run-loop.bat (or equivalent) will restart the JVM.
        System.exit(0);
    }

    /**
     * Locate the JAR this JVM was launched from, so we can compare its mtime
     * to the maintenance flag's mtime to detect "did the admin actually deploy
     * a new build during the downtime". Returns null when running from an
     * exploded classpath (IDE / dev mode) — caller treats that as "no deploy
     * detected" and stays in maintenance mode (developer can manually delete
     * the flag file to exit).
     *
     * <p>Implementation: in Spring Boot's uber-JAR layout, the
     * {@code ProtectionDomain} location is mangled (returns null or a nested
     * {@code jar:} URL), so we use {@code java.class.path} instead — for a
     * {@code java -jar foo.jar} launch, that property is exactly the JAR path.
     * Falls back to {@code sun.java.command} parsing if needed.
     */
    /** Best-effort coerce a JSON value (Number or String) to a Long, returning null
     *  if the field isn't present or can't be parsed. Used to read the optional
     *  pre-shutdown JAR mtime/size from older flag files. */
    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private File locateRunningJar() {
        // Primary: java.class.path is "foo.jar" for java -jar launches
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isEmpty() && !classPath.contains(File.pathSeparator)) {
            File f = new File(classPath);
            if (!f.isAbsolute()) f = f.getAbsoluteFile();
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) return f;
        }
        // Fallback: sun.java.command is "foo.jar arg1 arg2" for java -jar launches
        String javaCmd = System.getProperty("sun.java.command", "");
        if (!javaCmd.isEmpty()) {
            String first = javaCmd.split("\\s+", 2)[0];
            if (first.toLowerCase().endsWith(".jar")) {
                File f = new File(first);
                if (!f.isAbsolute()) f = f.getAbsoluteFile();
                if (f.isFile()) return f;
            }
        }
        // Last resort: ProtectionDomain (works outside Spring Boot uber-JARs)
        try {
            URL location = MaintenanceService.class.getProtectionDomain()
                .getCodeSource().getLocation();
            if (location != null) {
                File f = Paths.get(location.toURI()).toFile();
                if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) return f;
            }
        } catch (Exception ignored) { /* fall through */ }
        return null;
    }

    private synchronized void persist(PendingShutdown p) {
        try {
            File f = new File(PENDING_FILE);
            f.getParentFile().mkdirs();
            om.writerWithDefaultPrettyPrinter().writeValue(f, p);
        } catch (Exception e) {
            logger.warning("[MAINT] persist failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Emails — bare-bones HTML matching the SanDisk email design palette.
    // Kept inline (not via EmailTemplateService) because we only need 3 simple
    // notifications and want the service to stay self-contained.
    // ------------------------------------------------------------------

    private void sendGoingDownEmails(PendingShutdown p) throws Exception {
        String when = formatLocal(p.targetEpochMs);
        long minsFromNow = Math.max(1, (p.targetEpochMs - System.currentTimeMillis()) / 60_000L);
        String subject = "PLM Toolkit \u00b7 \u26A0 Scheduled maintenance in " + minsFromNow + " min";
        String hero = "PLM Toolkit will go offline at " + when;
        String body = "Scheduled by <strong>" + esc(p.scheduledByDisplayName) + "</strong> for maintenance."
            + (p.reason.isEmpty() ? "" : " Reason: " + esc(p.reason) + ".")
            + " You'll receive another email when the app is back online."
            + " You don't need to do anything \u2014 unsaved work in the browser will be lost on restart, so finish or export anything important now.";
        for (Map<String, String> r : p.recipients) {
            send(r.get("email"), subject, wrapEmail(subject, hero, body, "Heads up", "warn"));
        }
    }

    private void sendCancelledEmails(PendingShutdown p, String cancelledByDisplayName) throws Exception {
        String subject = "PLM Toolkit \u00b7 \u2713 Maintenance cancelled";
        String hero = "The scheduled maintenance has been cancelled.";
        String body = "Cancelled by <strong>" + esc(cancelledByDisplayName) + "</strong>."
            + " The app will stay online \u2014 carry on as normal.";
        for (Map<String, String> r : p.recipients) {
            send(r.get("email"), subject, wrapEmail(subject, hero, body, "Cancelled", "ok"));
        }
    }

    private void sendBackOnlineEmails(List<Map<String, String>> recipients, String reason,
                                      String scheduledByDisplayName) {
        String subject = "PLM Toolkit \u00b7 \u2713 Back online";
        String hero = "PLM Toolkit is back online.";
        String body = "The maintenance scheduled by <strong>" + esc(scheduledByDisplayName) + "</strong> is complete."
            + (reason == null || reason.isEmpty() ? "" : " (" + esc(reason) + ")")
            + " You can resume using the app at <a href=\"" + appUrl + "\">" + esc(appUrl) + "</a>.";
        for (Map<String, String> r : recipients) {
            try {
                send(r.get("email"), subject, wrapEmail(subject, hero, body, "Back online", "ok"));
            } catch (Exception e) {
                logger.warning("[MAINT] back-online email failed for " + r.get("email") + ": " + e.getMessage());
            }
        }
    }

    private void send(String to, String subject, String htmlBody) throws Exception {
        if (to == null || to.isEmpty()) return;
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session mailSession = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(mailSession);
        msg.setFrom(new InternetAddress(fromAddress));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));
        msg.setContent(htmlBody, "text/html; charset=utf-8");
        javax.mail.Transport.send(msg);
    }

    private String wrapEmail(String subject, String heroTitle, String bodyHtml,
                              String badgeLabel, String badgeKind) {
        String badgeBg, badgeColor;
        switch (badgeKind) {
            case "ok":   badgeBg = "#d4edda"; badgeColor = "#155724"; break;
            case "warn": badgeBg = "#fff3cd"; badgeColor = "#856404"; break;
            default:     badgeBg = "#e8f0fe"; badgeColor = "#1a3a5c"; break;
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"color-scheme\" content=\"light dark\">"
            + "<title>" + esc(subject) + "</title></head>"
            + "<body class=\"email-body\" style=\"margin:0;padding:24px;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" class=\"email-card\" "
            + "style=\"max-width:600px;background:#fff;border:1px solid #E8E6DF;border-radius:8px;\">"
            + "<tr><td style=\"padding:18px 24px;border-bottom:1px solid #E8E6DF;font-size:12px;color:#6B7280;\">"
            + "<strong>PLM Toolkit</strong> &nbsp;/&nbsp; Maintenance "
            + "<span style=\"float:right;background:" + badgeBg + ";color:" + badgeColor
            + ";padding:2px 10px;border-radius:12px;font-size:11px;font-weight:600;\">" + esc(badgeLabel) + "</span>"
            + "</td></tr>"
            + "<tr><td style=\"padding:24px;\">"
            + "<div style=\"font-size:11px;letter-spacing:0.08em;text-transform:uppercase;color:#6B7280;margin-bottom:6px;\">Maintenance notice</div>"
            + "<div style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:600;margin-bottom:14px;\">" + esc(heroTitle) + "</div>"
            + "<div style=\"font-size:14px;line-height:1.55;color:#0F1720;\">" + bodyHtml + "</div>"
            + "</td></tr>"
            + "<tr><td style=\"padding:14px 24px;border-top:1px solid #E8E6DF;font-family:'IBM Plex Mono',Consolas,monospace;font-size:11px;color:#6B7280;\">"
            + nowStamp()
            + "</td></tr>"
            + "<tr><td style=\"padding:14px 24px;background:#FAFAF7;border-top:1px solid #E8E6DF;\">"
            + "<span style=\"display:inline-block;border:1px solid #ececec;border-radius:20px;padding:3px 10px;font-size:11px;color:#6B7280;\">sandisk</span>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:6px;\">PLM Toolkit \u00b7 Maintenance notification</div>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:2px;\">This is an automated notification. Please do not reply to this email.</div>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private String formatLocal(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"));
    }

    private String nowStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** State of an in-flight scheduled shutdown. Persisted to disk via Jackson. */
    public static class PendingShutdown {
        public long scheduledAt;
        public long targetEpochMs;
        public String reason;
        public String scheduledByUsername;
        public String scheduledByDisplayName;
        public List<Map<String, String>> recipients;
        /** Absolute ceiling the targetEpochMs can be pushed to via end-user
         *  extensions (original target + max-extend-minutes). */
        public long maxTargetEpochMs;
        /** Running total of how much (ms) end users have already extended. */
        public long totalExtendedMs;
    }
}
