package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.HeapPressureMonitor;
import com.sandisk.plm.tracker.service.MaintenanceService;
import com.sandisk.plm.tracker.service.SessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Endpoints for the scheduled-maintenance feature. Status is readable by any
 * authenticated user (so all open browsers can poll and display the countdown
 * banner). Schedule and cancel are restricted to the {@code app.maintenance.allowed-users}
 * allowlist (intentionally tighter than the broad pdl-plm-admin group).
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private static final Logger logger = Logger.getLogger(MaintenanceController.class.getName());

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private HeapPressureMonitor heapPressureMonitor;

    @Autowired
    private SessionRegistry sessionRegistry;

    /** Active = touched the server within this window. Matches the AuthFilter / session
     *  timeout default; a user who hasn't moved in 5 min isn't really "in the toolkit". */
    private static final long ACTIVE_WINDOW_MS = 5L * 60L * 1000L;

    /**
     * Returns the current pending shutdown (or {@code null}) plus a flag indicating
     * whether the calling user is on the maintenance allowlist (so the UI can show
     * or hide the "Schedule maintenance" link).
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        MaintenanceService.PendingShutdown p = maintenanceService.getPending();
        if (p != null) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("scheduledAt", p.scheduledAt);
            pending.put("targetEpochMs", p.targetEpochMs);
            pending.put("scheduledByDisplayName", p.scheduledByDisplayName);
            pending.put("reason", p.reason);
            pending.put("recipientCount", p.recipients == null ? 0 : p.recipients.size());
            pending.put("maxTargetEpochMs", p.maxTargetEpochMs);
            pending.put("totalExtendedMs", p.totalExtendedMs);
            resp.put("pending", pending);
        } else {
            resp.put("pending", null);
        }
        String username = (String) session.getAttribute("username");
        resp.put("canSchedule", maintenanceService.isAllowed(username));
        resp.put("allowedUsers", maintenanceService.getAllowedUsers());
        resp.put("inMaintenanceMode", maintenanceService.isInMaintenanceMode());
        // Extension budget so any user's banner can show/hide the "+5 / +10 min" buttons.
        long budget = maintenanceService.extendBudgetMs();
        resp.put("canExtend", p != null && budget > 0);
        resp.put("extendBudgetMs", budget);
        resp.put("maxExtendMinutes", maintenanceService.getMaxExtendMinutes());
        return resp;
    }

    /**
     * End-user self-service extension. Any authenticated user can buy the room a
     * few more minutes to finish their work (capped server-side at
     * {@code app.maintenance.max-extend-minutes} total). Not allowlist-gated —
     * that's the whole point: the people about to be interrupted get the choice.
     */
    @PostMapping("/extend")
    public ResponseEntity<?> extend(@RequestBody Map<String, Object> body, HttpSession session) {
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        if (displayName == null || displayName.isEmpty()) displayName = username;
        int minutes;
        try {
            minutes = body.get("minutes") instanceof Number
                ? ((Number) body.get("minutes")).intValue()
                : Integer.parseInt(String.valueOf(body.getOrDefault("minutes", "10")));
        } catch (NumberFormatException nfe) {
            return ResponseEntity.badRequest().body(err("minutes must be 5 or 10."));
        }
        Map<String, Object> result = maintenanceService.extend(minutes, username, displayName);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.badRequest().body(result);
    }

    /**
     * Localhost-only readiness probe for {@code deploy.bat}. Instead of sleeping a
     * fixed amount, the deploy script polls this until {@code ready} so end-user
     * extensions actually hold the shutdown off. {@code ready} is true when nothing
     * is pending or the (possibly-extended) target time has passed.
     */
    @GetMapping("/deploy-ready")
    public ResponseEntity<?> deployReady(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            return ResponseEntity.status(403).body(err("This endpoint is localhost-only."));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        MaintenanceService.PendingShutdown p = maintenanceService.getPending();
        long now = System.currentTimeMillis();
        if (p == null) {
            resp.put("ready", true);
            resp.put("remainingSeconds", 0);
            resp.put("targetEpochMs", 0L);
        } else {
            long remMs = p.targetEpochMs - now;
            resp.put("ready", remMs <= 0);
            resp.put("remainingSeconds", Math.max(0L, remMs / 1000L));
            resp.put("targetEpochMs", p.targetEpochMs);
            resp.put("totalExtendedMinutes", p.totalExtendedMs / 60_000L);
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * Manually exit maintenance mode without redeploying. Allowlist admins only.
     * Use this when you scheduled a maintenance shutdown but decided not to deploy
     * a new JAR (so the JAR-mtime heuristic in {@code init()} won't auto-exit).
     */
    @PostMapping("/exit-mode")
    public ResponseEntity<?> exitMode(HttpSession session) {
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        if (!maintenanceService.isAllowed(username)) {
            return ResponseEntity.status(403).body(err("Only the maintenance admins can exit maintenance mode."));
        }
        boolean wasIn = maintenanceService.isInMaintenanceMode();
        maintenanceService.exitMaintenanceMode(username, displayName);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("wasInMaintenanceMode", wasIn);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/schedule")
    public ResponseEntity<?> schedule(@RequestBody Map<String, Object> body, HttpSession session) {
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        if (!maintenanceService.isAllowed(username)) {
            return ResponseEntity.status(403).body(err(
                "Only the maintenance admins can schedule shutdowns. Allowed: "
                + String.join(", ", maintenanceService.getAllowedUsers())));
        }
        try {
            int minutes = body.get("minutes") instanceof Number
                ? ((Number) body.get("minutes")).intValue()
                : Integer.parseInt(String.valueOf(body.getOrDefault("minutes", "10")));
            String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
            MaintenanceService.PendingShutdown p = maintenanceService.schedule(
                minutes, reason, username, displayName);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("targetEpochMs", p.targetEpochMs);
            resp.put("recipientCount", p.recipients.size());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException valErr) {
            return ResponseEntity.badRequest().body(err(valErr.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * Localhost-only schedule endpoint used by {@code deploy.bat}. Decides on the
     * server side whether to warn-and-wait or proceed immediately, based on who's
     * actually logged in right now:
     *
     * <ul>
     *   <li><strong>No real users active</strong> (only deploy admins, or nobody)
     *       → returns {@code shouldWait=false}. Deploy script proceeds immediately
     *       with no banner / email / wait.</li>
     *   <li><strong>One or more non-admin users active</strong> → schedules a
     *       5-minute maintenance window (fires the in-app banner + emails the
     *       active users), then returns {@code shouldWait=true, waitSeconds=300}
     *       so deploy.bat sleeps the right amount before swapping the JAR.</li>
     * </ul>
     *
     * <p>"Deploy admins to ignore" = members of {@code app.maintenance.allowed-users}
     * (the same allowlist that can schedule maintenance via the UI). Rationale:
     * those people are running the deploy, they don't need to warn themselves.</p>
     *
     * <p>Background: PT-56 — Jimmy lost mid-form feedback because a deploy went
     * through with no warning. We started by always warning for 2 min, but that's
     * 2 min of unnecessary delay when nobody's even using the toolkit. Smarter:
     * check who's online and skip the wait when only admins are present.</p>
     *
     * <p>Anything not arriving from a loopback address is rejected with 403 —
     * implicit security gate is "you're on the Windows toolkit box if you can
     * hit this".</p>
     */
    @PostMapping("/schedule-from-deploy")
    public ResponseEntity<?> scheduleFromDeploy(@RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isLoopback(remote)) {
            logger.warning("[MAINT] /schedule-from-deploy rejected non-loopback request from " + remote);
            return ResponseEntity.status(403).body(err("This endpoint is localhost-only."));
        }
        if (body == null) body = new LinkedHashMap<>();

        try {
            // Collect active users, drop the deploy-admin allowlist.
            java.util.Set<String> adminAllowlist = new java.util.HashSet<>();
            for (String a : maintenanceService.getAllowedUsers()) adminAllowlist.add(a.toLowerCase());

            List<SessionRegistry.ActiveUser> active = sessionRegistry.listActive(ACTIVE_WINDOW_MS);
            List<Map<String, Object>> nonAdminActive = new java.util.ArrayList<>();
            for (SessionRegistry.ActiveUser u : active) {
                if (u.username == null) continue;
                if (adminAllowlist.contains(u.username.toLowerCase())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("username", u.username);
                row.put("displayName", u.displayName);
                row.put("email", u.email);
                row.put("lastSeenMsAgo", System.currentTimeMillis() - u.lastSeen);
                nonAdminActive.add(row);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("activeUserCount", nonAdminActive.size());
            resp.put("activeUsers", nonAdminActive);
            resp.put("adminAllowlist", maintenanceService.getAllowedUsers());

            if (nonAdminActive.isEmpty()) {
                // Nobody to warn — deploy can proceed immediately.
                resp.put("shouldWait", false);
                resp.put("waitSeconds", 0);
                resp.put("message", "No non-admin users active — deploy may proceed without warning.");
                logger.info("[MAINT] /schedule-from-deploy: shouldWait=false (no non-admin active, " + active.size() + " admin)");
                return ResponseEntity.ok(resp);
            }

            // Real users present. Fire the warning window WITHOUT arming a
            // server-side System.exit timer — deploy.bat owns the shutdown
            // after its own sleep. PT-92: under the old code, the JVM would
            // call System.exit at exactly the 5-min mark, the watchdog would
            // respawn a JVM from the still-old JAR mid-boot, and deploy.bat
            // would wake up to find a parallel JVM holding plm-toolkit.log
            // open — file-lock errors on the new watchdog. By skipping the
            // timer arm here, the JVM stays up for the full sleep window and
            // deploy.bat is the only thing that ever shuts it down.
            int minutes = body.get("minutes") instanceof Number
                ? ((Number) body.get("minutes")).intValue()
                : Integer.parseInt(String.valueOf(body.getOrDefault("minutes", "5")));
            String reason = body.get("reason") == null
                ? "Toolkit is going down for an update in " + minutes + " min — please save your work."
                : String.valueOf(body.get("reason"));
            MaintenanceService.PendingShutdown p = maintenanceService.fireWarningWithoutTimer(
                minutes, reason, "deploy.bat", "deploy.bat (server console)");
            resp.put("shouldWait", true);
            resp.put("waitSeconds", minutes * 60);
            resp.put("targetEpochMs", p.targetEpochMs);
            resp.put("recipientCount", p.recipients.size());
            resp.put("message", nonAdminActive.size() + " non-admin user(s) active — " + minutes + "-min warning fired.");
            logger.info("[MAINT] /schedule-from-deploy: shouldWait=true, " + minutes + "-min warning fired"
                + " (active non-admins=" + nonAdminActive.size() + ", emailed=" + p.recipients.size()
                + ", server-side timer NOT armed — deploy.bat owns shutdown)");
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException valErr) {
            return ResponseEntity.badRequest().body(err(valErr.getMessage()));
        } catch (Exception e) {
            logger.warning("[MAINT] /schedule-from-deploy failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /** True for IPv4 + IPv6 loopback addresses (and the legacy "0:0:0:..." form). */
    private static boolean isLoopback(String addr) {
        if (addr == null) return false;
        return addr.equals("127.0.0.1")
            || addr.equals("::1")
            || addr.equals("0:0:0:0:0:0:0:1")
            || addr.startsWith("127.");  // catch the full 127.0.0.0/8 loopback block
    }

    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(HttpSession session) {
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        if (!maintenanceService.isAllowed(username)) {
            return ResponseEntity.status(403).body(err("Only the maintenance admins can cancel shutdowns."));
        }
        MaintenanceService.PendingShutdown p = maintenanceService.cancel(username, displayName);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("wasPending", p != null);
        return ResponseEntity.ok(resp);
    }

    /**
     * Manual trigger to verify the heap-pressure alert email path. Sends one
     * email NOW with the current heap snapshot, bypassing the threshold +
     * cooldown gates. Only the maintenance allowlist can call it (same gate as
     * schedule/cancel — already an admin-tight set).
     */
    @PostMapping("/heap-alert/test")
    public ResponseEntity<?> testHeapAlert(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (!maintenanceService.isAllowed(username)) {
            return ResponseEntity.status(403).body(err("Only the maintenance admins can trigger a test heap-alert email."));
        }
        try {
            heapPressureMonitor.sendTestAlert();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Test heap-alert email sent.");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err("Failed to send test alert: " + e.getMessage()));
        }
    }

    /**
     * Returns the live gate state for the overnight idle-window Full GC. Lets
     * an admin see "would it fire if the timer ticked right now" without
     * actually doing anything. Auth via the same maintenance allowlist.
     */
    @GetMapping("/heap-gc/status")
    public ResponseEntity<?> heapGcStatus(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (!maintenanceService.isAllowed(username)) {
            return ResponseEntity.status(403).body(err("Only the maintenance admins can read heap-gc status."));
        }
        return ResponseEntity.ok(heapPressureMonitor.getIdleGcStatus());
    }

    /**
     * Forces a Full GC immediately, bypassing the time-window / idle / threshold
     * gates that the scheduler uses. Returns before/after heap stats. Use for
     * verifying the mechanism end-to-end without waiting for the overnight window.
     */
    @PostMapping("/heap-gc/test")
    public ResponseEntity<?> testHeapGc(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (!maintenanceService.isAllowed(username)) {
            return ResponseEntity.status(403).body(err("Only the maintenance admins can trigger a forced GC."));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("result", heapPressureMonitor.runIdleWindowGcNow());
        return ResponseEntity.ok(resp);
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("error", msg);
        return e;
    }
}
