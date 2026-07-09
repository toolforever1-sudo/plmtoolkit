package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.FeedbackItem;
import com.sandisk.plm.tracker.model.FeedbackQueueFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Logger;

@Service
public class FeedbackQueueService {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_TRIAGING = "triaging";
    public static final String STATUS_AWAITING_APPROVAL = "awaiting_approval";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_DISMISSED = "dismissed";

    private static final Logger logger = Logger.getLogger(FeedbackQueueService.class.getName());

    @Value("${app.feedback.queue.file:./data/feedback-queue.json}")
    private String filePath;

    @Value("${app.feedback.queue.import-on-init:true}")
    private boolean importOnInit;

    @Value("${app.feedback.attachments.dir:./data/feedback-attachments}")
    private String attachmentsDir;

    @Autowired private ActivityLogger activityLogger;
    @Autowired private UserPermissionsService userPermissionsService;
    @Autowired private LdapAuthService ldapAuthService;
    @Autowired(required = false) private BuildInfoService buildInfoService;
    // @Lazy breaks a circular bean dependency: FeedbackResolveEmailService
    // already pulls in things that transitively reach FeedbackQueueService.
    // We only call this on the auto-done path, so a lazy proxy is fine.
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private FeedbackResolveEmailService feedbackResolveEmailService;

    private final ObjectMapper mapper = new ObjectMapper();
    private FeedbackQueueFile state = new FeedbackQueueFile();

    @PostConstruct
    public synchronized void init() {
        File f = new File(filePath);
        if (f.exists()) {
            try {
                state = mapper.readValue(f, FeedbackQueueFile.class);
                if (state == null) state = new FeedbackQueueFile();
                if (state.items == null) state.items = new ArrayList<>();
                if (state.nextId < 1) state.nextId = 1;
                logger.info("[FEEDBACK-Q] Loaded " + state.items.size() + " items, nextId=" + state.nextId);
                backfillMissingEmails();
                backfillOrphanUserNames();
            } catch (Exception e) {
                logger.warning("[FEEDBACK-Q] Failed to load " + filePath + ": " + e.getMessage());
                state = new FeedbackQueueFile();
            }
            return;
        }
        if (importOnInit) {
            logger.info("[FEEDBACK-Q] No queue file at " + filePath + " — importing from activity log.");
            importFromActivityLog();
            saveToDisk();
        } else {
            logger.info("[FEEDBACK-Q] No queue file at " + filePath + " — starting empty (import-on-init=false).");
            saveToDisk();
        }
    }

    /**
     * Walk every item with a null reporter email and try to fill it from
     * UserPermissionsService first (cheap, in-memory) and LDAP second
     * (4-hour cache). Persist the file once at the end if anything changed.
     */
    /**
     * One-shot startup patch — rewrite the {@code User: <id>} line on every
     * auto-filed crash-recovery ticket to include the LDAP display name.
     * For new orphans the {@link UploadQuarantineService} writes the resolved
     * name directly; this method retro-fits existing tickets.
     *
     * Idempotent: only rewrites lines where the value after "User: " has no
     * space (i.e. raw numeric id). Once the line already reads "User: Jimmy
     * Sessumes (1359)", the regex won't match a second time.
     */
    private void backfillOrphanUserNames() {
        if (ldapAuthService == null) return;
        int patched = 0;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(\\[Auto-filed crash recovery\\][\\s\\S]*?\\nUser: )([^\\s\\n()]+)(\\n)");
        for (FeedbackItem it : state.items) {
            if (it.text == null || !it.text.startsWith("[Auto-filed crash recovery]")) continue;
            java.util.regex.Matcher m = p.matcher(it.text);
            if (!m.find()) continue;
            String rawId = m.group(2);
            try {
                LdapAuthService.UserInfo info = ldapAuthService.lookupUserById(rawId);
                if (info == null || info.displayName == null || info.displayName.isEmpty()) {
                    info = ldapAuthService.lookupUserByUsername(rawId);
                }
                if (info != null && info.displayName != null && !info.displayName.isEmpty()) {
                    String resolved = info.displayName + " (" + rawId + ")";
                    it.text = m.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                        m.group(1) + resolved + m.group(3)));
                    patched++;
                }
            } catch (Exception ignored) {}
        }
        if (patched > 0) {
            logger.info("[FEEDBACK-Q] backfilled " + patched + " orphan ticket(s) with LDAP display names");
            saveToDisk();
        }
    }

    private void backfillMissingEmails() {
        int filled = 0;
        for (FeedbackItem it : state.items) {
            if (it.reporterEmail != null && !it.reporterEmail.isEmpty()) continue;
            if (it.reporterUsername == null || it.reporterUsername.isEmpty()) continue;
            String email = userPermissionsService.getEmailForUsername(it.reporterUsername);
            if (email == null) email = lookupEmailViaLdap(it.reporterUsername);
            if (email != null && !email.isEmpty()) {
                it.reporterEmail = email;
                filled++;
            }
        }
        if (filled > 0) {
            logger.info("[FEEDBACK-Q] Backfilled " + filled + " reporter emails from LDAP/UserPermissions.");
            saveToDisk();
        }
    }

    /** Best-effort LDAP lookup for an AD username; returns null on miss. */
    private String lookupEmailViaLdap(String username) {
        try {
            LdapAuthService.UserInfo info = ldapAuthService.lookupUserByUsername(username);
            if (info != null && info.email != null && !info.email.isEmpty()) return info.email;
        } catch (Exception ex) {
            logger.fine("[FEEDBACK-Q] LDAP lookup failed for " + username + ": " + ex.getMessage());
        }
        return null;
    }

    /**
     * Public hook so other services (resolve email) can fill an email just-in-time
     * and persist it back. Idempotent and synchronized.
     */
    public synchronized void updateReporterEmailIfMissing(String ptId, String email) {
        if (ptId == null || email == null || email.isEmpty()) return;
        FeedbackItem it = findInternal(ptId);
        if (it == null) return;
        if (it.reporterEmail != null && !it.reporterEmail.isEmpty()) return;
        it.reporterEmail = email;
        saveToDisk();
    }

    /** Public for tests / admin re-import endpoints later. */
    public synchronized int importFromActivityLog() {
        List<ActivityLogger.ActivityEntry> all = activityLogger.getActivitiesSince(0L);
        all.sort(Comparator.comparingLong(e -> e.timestamp));
        int seeded = 0;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (ActivityLogger.ActivityEntry e : all) {
            if (!"FEEDBACK".equals(e.action)) continue;
            FeedbackItem it = new FeedbackItem();
            it.ptId = "PT-" + (state.nextId++);
            String details = e.details == null ? "" : e.details;
            int sep = details.indexOf(": ");
            if (sep > 0) {
                it.category = details.substring(0, sep);
                it.text = details.substring(sep + 2);
            } else {
                it.category = "General";
                it.text = details;
            }
            it.reporterUsername = e.username;
            it.reporterDisplayName = e.displayName;
            String email = userPermissionsService.getEmailForUsername(e.username);
            if (email == null) email = lookupEmailViaLdap(e.username);
            it.reporterEmail = email;
            it.submittedAt = sdf.format(new Date(e.timestamp));
            it.status = STATUS_OPEN;
            state.items.add(it);
            seeded++;
        }
        logger.info("[FEEDBACK-Q] Imported " + seeded + " items from activity log.");
        return seeded;
    }

    /** Allocates the next sequential PT-#### id without persisting an item. Caller persists via {@link #addItem}. */
    public synchronized String allocateNextId() {
        return "PT-" + (state.nextId++);
    }

    public synchronized FeedbackItem addItem(String ptId,
                                              String category,
                                              String text,
                                              String reporterUsername,
                                              String reporterDisplayName,
                                              String reporterEmail,
                                              MultipartFile attachment) {
        java.util.List<MultipartFile> list = (attachment == null || attachment.isEmpty())
                ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(attachment);
        return addItem(ptId, category, text, reporterUsername, reporterDisplayName, reporterEmail, list);
    }

    public synchronized FeedbackItem addItem(String ptId,
                                              String category,
                                              String text,
                                              String reporterUsername,
                                              String reporterDisplayName,
                                              String reporterEmail,
                                              java.util.List<MultipartFile> attachments) {
        FeedbackItem it = new FeedbackItem();
        it.ptId = ptId;
        it.category = (category == null || category.isEmpty()) ? "General" : category;
        it.text = text == null ? "" : text;
        it.reporterUsername = reporterUsername;
        it.reporterDisplayName = reporterDisplayName;
        it.reporterEmail = (reporterEmail == null || reporterEmail.isEmpty()) ? null : reporterEmail;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        it.submittedAt = sdf.format(new Date());
        it.status = STATUS_OPEN;

        // PT-38: persist all attachments under data/feedback-attachments/{ptId}/.
        // Filename collisions get a "-N" suffix. First file mirrored to the legacy
        // attachmentPath/attachmentFilename fields so old UI code keeps working.
        if (attachments != null && !attachments.isEmpty()) {
            try {
                Path dir = Paths.get(attachmentsDir, ptId);
                Files.createDirectories(dir);
                it.attachmentPaths = new java.util.ArrayList<>();
                it.attachmentFilenames = new java.util.ArrayList<>();
                java.util.Set<String> usedNames = new java.util.HashSet<>();
                for (MultipartFile mf : attachments) {
                    if (mf == null || mf.isEmpty()) continue;
                    String safeName = mf.getOriginalFilename();
                    if (safeName == null || safeName.isEmpty()) safeName = "attachment";
                    safeName = safeName.replaceAll("[^A-Za-z0-9._-]", "_");
                    String unique = safeName;
                    int n = 1;
                    while (!usedNames.add(unique)) {
                        int dot = safeName.lastIndexOf('.');
                        unique = (dot > 0)
                                ? safeName.substring(0, dot) + "-" + n + safeName.substring(dot)
                                : safeName + "-" + n;
                        n++;
                    }
                    Path target = dir.resolve(unique);
                    Files.write(target, mf.getBytes());
                    it.attachmentPaths.add(target.toString());
                    it.attachmentFilenames.add(unique);
                    if (it.attachmentPath == null) {
                        it.attachmentPath = target.toString();
                        it.attachmentFilename = unique;
                    }
                }
            } catch (Exception ex) {
                logger.warning("[FEEDBACK-Q] Failed to save attachments for " + ptId + ": " + ex.getMessage());
            }
        }

        state.items.add(it);
        saveToDisk();
        return it;
    }

    public synchronized FeedbackItem markDone(String ptId, String resolverUsername, String resolverDisplay, String adminNote) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        if (STATUS_DONE.equals(it.status)) return it;
        it.status = STATUS_DONE;
        it.adminNote = (adminNote == null || adminNote.trim().isEmpty()) ? null : adminNote.trim();
        it.resolvedBy = resolverUsername;
        it.resolvedByDisplay = resolverDisplay;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        it.resolvedAt = sdf.format(new Date());
        // Freeze actualHours at the done transition (pickup → build, falling back
        // to pickup → resolved when no build SHA was stamped).
        if (it.actualHours == null) it.actualHours = computeActualHours(it);
        saveToDisk();
        return it;
    }

    /**
     * Compute elapsed hours from when the agent first polled the ticket to the
     * moment we consider the work "done". Preferred end-point is agentBuildAt
     * (when the agent stamped the JAR SHA); if that's missing we fall back to
     * resolvedAt (i.e. the manual Mark Done click). Returns null if no pickup
     * time was ever recorded — we don't fake a number we can't defend.
     */
    private Double computeActualHours(FeedbackItem it) {
        if (it == null || it.agentPolledAt == null) return null;
        String endStr = (it.agentBuildAt != null) ? it.agentBuildAt : it.resolvedAt;
        if (endStr == null) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            long startMs = sdf.parse(it.agentPolledAt).getTime();
            long endMs = sdf.parse(endStr).getTime();
            double hours = Math.max(0, (endMs - startMs) / 3_600_000.0);
            return Math.round(hours * 100.0) / 100.0;
        } catch (Exception e) {
            logger.warning("[FEEDBACK-Q] could not compute actualHours for " + it.ptId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The agent (Claude via /poll-feedback) records the moment it actually
     * started working on this ticket. Idempotent: first call wins, subsequent
     * calls are no-ops. This is what anchors actualHours — we deliberately do
     * NOT use admin-approval time because approval can sit unworked for hours.
     */
    public synchronized FeedbackItem markAgentPickup(String ptId, String actorUsername, String actorDisplay) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        if (it.agentPolledAt != null) return it;  // first call wins
        SimpleDateFormat sdf = utcFmt();
        it.agentPolledAt = sdf.format(new Date());
        activityLogger.log(actorUsername, actorDisplay, "FEEDBACK_AGENT_PICKUP",
            "ptId=" + ptId + " est=" + (it.aiEffortHours == null ? "?" : it.aiEffortHours.toString()) + "h");
        saveToDisk();
        return it;
    }

    public synchronized FeedbackItem markInProgress(String ptId, String resolverUsername, String resolverDisplay) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        if (STATUS_IN_PROGRESS.equals(it.status)) return it;
        it.status = STATUS_IN_PROGRESS;
        it.dismissReason = null;
        it.resolvedBy = resolverUsername;
        it.resolvedByDisplay = resolverDisplay;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        it.resolvedAt = sdf.format(new Date());
        saveToDisk();
        return it;
    }

    public synchronized FeedbackItem markDismissed(String ptId, String resolverUsername, String resolverDisplay, String reason) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        if (STATUS_DISMISSED.equals(it.status)) return it;
        it.status = STATUS_DISMISSED;
        it.dismissReason = (reason == null || reason.trim().isEmpty()) ? null : reason.trim();
        it.resolvedBy = resolverUsername;
        it.resolvedByDisplay = resolverDisplay;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        it.resolvedAt = sdf.format(new Date());
        saveToDisk();
        return it;
    }

    public synchronized FeedbackItem markReopen(String ptId, String resolverUsername, String resolverDisplay) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        it.status = STATUS_OPEN;
        it.adminNote = null;
        it.dismissReason = null;
        it.resolvedAt = null;
        it.resolvedBy = resolverUsername;
        it.resolvedByDisplay = resolverDisplay;
        saveToDisk();
        return it;
    }

    // ===================================================================
    //  AI triage + sign-off + auto-mark-done
    //  Spec: docs/superpowers/specs/2026-05-13-feedback-queue-ai-triage-design.md
    // ===================================================================

    /** Move to triaging — typically called when a new attachment arrives or
     *  the requestor answers AI questions. */
    public synchronized FeedbackItem markTriaging(String ptId) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        it.status = STATUS_TRIAGING;
        saveToDisk();
        return it;
    }

    /** Persist an AI triage verdict. Status flips to awaiting_approval (admin's turn)
     *  unless the verdict is gibberish, in which case the item is auto-dismissed. */
    public synchronized FeedbackItem applyAiTriage(String ptId, String tag, String assessment,
                                                    Double effortHours, List<String> questions) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        it.aiTag = tag;
        it.aiAssessment = assessment;
        it.aiEffortHours = effortHours;
        it.aiQuestions = questions == null ? new ArrayList<>() : new ArrayList<>(questions);
        it.aiAnswers = it.aiAnswers == null ? new ArrayList<>() : it.aiAnswers;
        SimpleDateFormat sdf = utcFmt();
        it.aiTriagedAt = sdf.format(new Date());
        if ("gibberish".equals(tag)) {
            it.status = STATUS_DISMISSED;
            it.dismissReason = "Auto-dismissed: " + (assessment == null ? "AI couldn't extract any actionable content." : assessment);
            it.resolvedBy = "agent";
            it.resolvedByDisplay = "PLM Toolkit (auto)";
            it.resolvedAt = sdf.format(new Date());
            activityLogger.log("agent", "PLM Toolkit (auto)", "FEEDBACK_AUTO_DISMISS_GIBBERISH",
                "ptId=" + ptId + " reason=" + truncate(it.dismissReason, 80));
        } else {
            it.status = STATUS_AWAITING_APPROVAL;
        }
        saveToDisk();

        // Email the requestor (CC pdl-plm-admin) when the AI has questions.
        // Best-effort — never fail the triage write on email errors.
        if ("have_questions".equals(tag) && feedbackResolveEmailService != null) {
            try { feedbackResolveEmailService.sendQuestionsToRequestor(it); }
            catch (Exception e) {
                logger.warning("[FEEDBACK-Q] questions email failed for " + it.ptId + ": " + e.getMessage());
            }
        }
        return it;
    }

    /** Admin signs off → ticket moves to in_progress; agentStarted reset to false
     *  so the next poll cycle picks it up for the Maintenance Fix Flow. */
    public synchronized FeedbackItem markApproved(String ptId, String approverUsername, String approverDisplay) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        it.status = STATUS_IN_PROGRESS;
        it.agentStarted = false;
        it.resolvedBy = approverUsername;
        it.resolvedByDisplay = approverDisplay;
        SimpleDateFormat sdf = utcFmt();
        it.resolvedAt = sdf.format(new Date());
        activityLogger.log(approverUsername, approverDisplay, "FEEDBACK_APPROVE",
            "ptId=" + ptId + " tag=" + nz(it.aiTag));
        saveToDisk();
        return it;
    }

    /** Requestor (or admin) answers the AI's questions. Status flips to triaging
     *  so the next poll cycle re-evaluates with the answers in scope. */
    public synchronized FeedbackItem applyAnswers(String ptId, List<String> answers,
                                                   String answererUsername, String answererDisplay) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        it.aiAnswers = answers == null ? new ArrayList<>() : new ArrayList<>(answers);
        it.status = STATUS_TRIAGING;
        activityLogger.log(answererUsername, answererDisplay, "FEEDBACK_ANSWER",
            "ptId=" + ptId + " answers=" + it.aiAnswers.size());
        saveToDisk();
        return it;
    }

    /** Stamp the JAR SHA the agent just built for this ticket. The auto-mark-done
     *  cron uses this to determine when the fix is live in prod. */
    public synchronized FeedbackItem applyBuildSha(String ptId, String sha,
                                                    String stamperUsername, String stamperDisplay) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        it.agentBuildJarSha = sha;
        it.agentStarted = true;
        SimpleDateFormat sdf = utcFmt();
        it.agentBuildAt = sdf.format(new Date());
        activityLogger.log(stamperUsername, stamperDisplay, "FEEDBACK_BUILD_SHA",
            "ptId=" + ptId + " sha=" + (sha == null ? "null" : sha.substring(0, Math.min(12, sha.length()))));
        saveToDisk();
        return it;
    }

    /**
     * One-shot auto-mark-done sweep right after Spring finishes wiring beans.
     * The cron-based sweep runs every 5 min, but a deploy that lands at e.g.
     * 17:38 has to wait until 17:40 (and that tick fires while the JVM might
     * still be initializing). This listener closes the gap: as soon as
     * ApplicationReadyEvent fires (BuildInfoService is wired by then), we run
     * the sweep once so any ticket whose stamped SHA matches the freshly
     * deployed JAR auto-resolves within seconds of startup instead of up to 5
     * minutes later. PT-54 deploy was the canary that motivated this.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoMarkDoneOnStartup() {
        try {
            logger.info("[FEEDBACK-Q] one-shot startup sweep beginning...");
            autoMarkDoneSweep();
        } catch (Exception e) {
            // Never let a startup-sweep blow up break the app. The cron will
            // retry within 5 minutes anyway.
            logger.warning("[FEEDBACK-Q] startup sweep failed (cron will retry): " + e.getMessage());
        }
    }

    /**
     * Recurring auto-mark-done sweep. Runs every 5 minutes by default. For every
     * in_progress ticket with a stamped agentBuildJarSha, if the running JAR's
     * SHA-256 matches, the ticket is auto-resolved. This is the magic that
     * lets a deploy.bat run on prod resolve every ticket built into that JAR.
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${app.feedback.auto-done-cron:0 */5 * * * *}")
    public synchronized void autoMarkDoneSweep() {
        if (buildInfoService == null) return;
        String runningSha = buildInfoService.getJarSha256();
        if (runningSha == null || "unknown".equals(runningSha)) return;
        int matched = 0;
        List<FeedbackItem> emailQueue = new ArrayList<>();
        SimpleDateFormat sdf = utcFmt();
        for (FeedbackItem it : state.items) {
            if (!STATUS_IN_PROGRESS.equals(it.status)) continue;
            if (it.agentBuildJarSha == null) continue;
            if (!runningSha.equals(it.agentBuildJarSha)) continue;
            it.status = STATUS_DONE;
            it.resolvedBy = "agent";
            it.resolvedByDisplay = "PLM Toolkit (auto)";
            it.resolvedAt = sdf.format(new Date());
            if (it.adminNote == null) it.adminNote = "Auto-resolved by deploy: running JAR matches the build the agent stamped for this ticket.";
            if (it.actualHours == null) it.actualHours = computeActualHours(it);
            activityLogger.log("agent", "PLM Toolkit (auto)", "FEEDBACK_AUTO_DONE",
                "ptId=" + it.ptId + " sha=" + runningSha.substring(0, 12)
                + " est=" + (it.aiEffortHours == null ? "?" : it.aiEffortHours.toString())
                + "h actual=" + (it.actualHours == null ? "?" : it.actualHours.toString()) + "h");
            emailQueue.add(it);
            matched++;
        }
        if (matched > 0) {
            logger.info("[FEEDBACK-Q] auto-mark-done: resolved " + matched + " ticket(s) on SHA match " + runningSha.substring(0, 12));
            saveToDisk();
            // Send the same "ready to test" email the manual Mark Done button sends.
            // Best-effort — never fail the sweep on email errors.
            if (feedbackResolveEmailService != null) {
                for (FeedbackItem it : emailQueue) {
                    try { feedbackResolveEmailService.sendReadyToTest(it); }
                    catch (Exception e) {
                        logger.warning("[FEEDBACK-Q] ready-to-test email failed for " + it.ptId + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private static SimpleDateFormat utcFmt() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf;
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Find ticket without modifying state — exposed package-private for the controller. */
    synchronized FeedbackItem findInternalForController(String ptId) { return findInternal(ptId); }

    /** Add a file path to attachmentPaths (used by the new attach endpoint). */
    public synchronized FeedbackItem addAttachment(String ptId, String relativePath, String filename) {
        FeedbackItem it = findInternal(ptId);
        if (it == null) return null;
        if (it.attachmentPaths == null) it.attachmentPaths = new ArrayList<>();
        if (it.attachmentFilenames == null) it.attachmentFilenames = new ArrayList<>();
        it.attachmentPaths.add(relativePath);
        it.attachmentFilenames.add(filename);
        if (it.attachmentPath == null) {
            it.attachmentPath = relativePath;
            it.attachmentFilename = filename;
        }
        saveToDisk();
        return it;
    }

    public String getAttachmentsDir() { return attachmentsDir; }

    public synchronized List<FeedbackItem> listAll() {
        List<FeedbackItem> copy = new ArrayList<>(state.items);
        copy.sort((a, b) -> safe(b.submittedAt).compareTo(safe(a.submittedAt)));
        return copy;
    }

    public synchronized List<FeedbackItem> listForUser(String username) {
        if (username == null) return new ArrayList<>();
        List<FeedbackItem> out = new ArrayList<>();
        for (FeedbackItem it : state.items) {
            if (username.equalsIgnoreCase(it.reporterUsername)) out.add(it);
        }
        out.sort((a, b) -> safe(b.submittedAt).compareTo(safe(a.submittedAt)));
        return out;
    }

    public synchronized FeedbackItem findById(String ptId) {
        FeedbackItem it = findInternal(ptId);
        return it; // callers must not mutate
    }

    private FeedbackItem findInternal(String ptId) {
        if (ptId == null) return null;
        for (FeedbackItem it : state.items) {
            if (ptId.equals(it.ptId)) return it;
        }
        return null;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private synchronized void saveToDisk() {
        try {
            File f = new File(filePath);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, state);
        } catch (Exception e) {
            logger.warning("[FEEDBACK-Q] Failed to save " + filePath + ": " + e.getMessage());
        }
    }
}
