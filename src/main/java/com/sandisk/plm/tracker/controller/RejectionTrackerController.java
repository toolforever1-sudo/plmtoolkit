package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.EcnReportService;
import com.sandisk.plm.tracker.service.RejectionTrackerEmailService;
import com.sandisk.plm.tracker.service.RejectionSnapshotService;
import com.sandisk.plm.tracker.service.RejectionTrackerService;
import com.sandisk.plm.tracker.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST endpoints for the Returns Tracker view.
 *
 * Mounted under /api/ecn-report/returns/.
 *
 * Auth model mirrors EcnReportController: read endpoints require a logged-in
 * session (handled by AuthFilter); mutating endpoints (refresh, email, recipients)
 * require canEdit (admin or named editor).
 */
@RestController
@RequestMapping("/api/ecn-report/returns")
public class RejectionTrackerController {

    private static final Logger logger = Logger.getLogger(RejectionTrackerController.class.getName());

    @Autowired private RejectionTrackerService rejectionService;
    @Autowired private RejectionTrackerEmailService emailService;
    @Autowired private EcnReportService ecnService;
    @Autowired private ReportService reportService;
    @Autowired private ActivityLogger activityLogger;
    @Autowired private RejectionSnapshotService snapshotService;

    @Value("${app.reports.python:python}") private String pythonExe;
    @Value("${app.returns.audit-golive-date:2026-06-24}") private String auditGoLiveDate;
    // Rollback lever: false restores the classic Returns Tracker look (single-colour
    // Product Line bars + Weeks in the Period dropdown). Surfaced to the frontend.
    @Value("${app.returns.enhanced-view:true}") private boolean returnsEnhancedView;

    // =========================================================================
    // Session helpers (mirror EcnReportController)
    // =========================================================================

    private String username(HttpSession s) {
        Object u = s.getAttribute("username");
        return u != null ? u.toString() : "unknown";
    }
    private String displayName(HttpSession s) { return (String) s.getAttribute("displayName"); }
    private boolean isAdmin(HttpSession s) {
        return Boolean.TRUE.equals(s.getAttribute("isPlmAdmin"));
    }
    private boolean canEdit(HttpSession s) {
        if (isAdmin(s)) return true;
        Object e = s.getAttribute("email");
        return ecnService.isEditor(username(s), e != null ? e.toString() : null);
    }
    private Map<String, Object> forbidden() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("message", "You do not have permission to perform this action.");
        return r;
    }
    private Map<String, Object> ok(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("message", msg);
        return r;
    }

    // =========================================================================
    // GET /data?from=YYYY-MM-DD&to=YYYY-MM-DD
    // =========================================================================

    @GetMapping("/data")
    public Map<String, Object> getData(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String period,
                                        @RequestParam(required = false, defaultValue = "audit") String classification) {
        Map<String, Object> r = new LinkedHashMap<>();

        // Frozen snapshot path.
        if (period != null && !period.isEmpty() && !"live".equals(period)) {
            Map<String, Object> snap = snapshotService.readSnapshot(period);
            if (snap == null) {
                r.put("success", false);
                r.put("message", "Snapshot not found: " + period);
                return r;
            }
            snap.put("success", true);
            snap.put("frozen", true);
            snap.put("auditGoLiveDate", auditGoLiveDate);
            snap.put("enhancedView", returnsEnhancedView);
            return snap;
        }

        if (!rejectionService.hasData()) {
            r.put("success", false);
            r.put("message", "No rejection data yet. Click Refresh to generate.");
            return r;
        }
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(90);
        LocalDate toDate = today;
        try {
            if (from != null && !from.isEmpty()) fromDate = LocalDate.parse(from);
            if (to != null && !to.isEmpty()) toDate = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            r.put("success", false);
            r.put("message", "Invalid date format (use YYYY-MM-DD)");
            return r;
        }
        List<Map<String, Object>> events = rejectionService.getEventsInRange(fromDate, toDate);
        Map<String, Object> aggregates = rejectionService.getAggregatesFor(
            events, "auditCategory", RejectionTrackerService.AUDIT_CATEGORY_ORDER);
        Map<String, Object> aggregatesAi = rejectionService.getAggregatesFor(
            events, "aiCategory", RejectionTrackerService.CATEGORIES);
        Map<String, Object> agreement = rejectionService.computeAgreement(events);
        String windowKey = RejectionTrackerService.windowKeyForRange(fromDate, toDate);
        Map<String, Object> narrative = rejectionService.getNarrative(windowKey);
        r.put("success", true);
        r.put("frozen", false);
        r.put("from", fromDate.toString());
        r.put("to", toDate.toString());
        r.put("windowKey", windowKey);
        r.put("classification", classification);
        r.put("auditGoLiveDate", auditGoLiveDate);
        r.put("enhancedView", returnsEnhancedView);
        r.put("events", events);
        r.put("aggregates", aggregates);
        r.put("aggregatesAi", aggregatesAi);
        r.put("agreement", agreement);
        r.put("narrative", narrative);
        r.put("lastRunTs", rejectionService.getLastRunTs());
        // Surface the most recent refresh job status so the UI poll can detect
        // a failure quickly (instead of camping on a "Refreshing..." spinner for
        // 8 minutes waiting for lastRunTs to change). Also expose the most recent
        // Python stdout line so the spinner can show live progress instead of a
        // static "Refreshing..." label.
        Map<String, Object> refreshStatus = reportService.getStatus("returns-tracker");
        r.put("refreshStatus", refreshStatus.get("status"));
        r.put("refreshError", refreshStatus.get("error"));
        @SuppressWarnings("unchecked")
        List<String> logs = (List<String>) refreshStatus.get("logs");
        if (logs != null && !logs.isEmpty()) {
            // Walk back from the end to find a non-trivial log line (skip blank,
            // the ASCII-banner box-drawing rows, and the lone status arrows).
            for (int i = logs.size() - 1; i >= 0; i--) {
                String s = logs.get(i) == null ? "" : logs.get(i).trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("\u2554") || s.startsWith("\u255a") || s.startsWith("\u2551")) continue;
                if (s.startsWith("\u2500") || s.startsWith("\u2014\u2014")) continue;
                r.put("refreshLogTail", s);
                break;
            }
        }
        return r;
    }

    // =========================================================================
    // POST /refresh — spawn rejection_tracker.py incremental run
    // =========================================================================

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody(required = false) Map<String, Object> body,
                                         HttpSession session) {
        if (!canEdit(session)) return forbidden();

        final String user = username(session);
        final String display = displayName(session);
        final String windowKey = body != null && body.get("window") != null
            ? body.get("window").toString() : "90d";

        reportService.markBuiltinRunning("returns-tracker", "Returns Tracker");
        activityLogger.log(user, display, "RETURNS_REFRESH",
                "Started Returns Tracker refresh (window=" + windowKey + ")");

        Thread worker = new Thread(() -> {
            try {
                // -u = unbuffered stdout/stderr. Without this, Python block-buffers
                // print() output when its stdout is piped to Java, so the live
                // log tail in the UI shows nothing until the buffer fills (~4-8 KB).
                ProcessBuilder pb = new ProcessBuilder(
                        pythonExe, "-u", "rejection_tracker.py",
                        "--config", "ecn_report.properties",
                        "--narrative-window", windowKey
                );
                pb.directory(new File("./data/ecn-report"));
                pb.redirectErrorStream(true);
                pb.environment().put("PYTHONIOENCODING", "utf-8");
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        reportService.appendLog("returns-tracker", line);
                        logger.info("[RETURNS] " + line);
                    }
                }
                int exit = p.waitFor();
                if (exit == 0) {
                    reportService.markBuiltinCompleted("returns-tracker");
                    activityLogger.log(user, display, "RETURNS_REFRESH_OK", "Returns Tracker refresh complete");
                } else {
                    // Pull the most informative log line for the error banner —
                    // a Python RuntimeError shows up in stderr (merged with stdout) as
                    // "RuntimeError: <message>". Prefer that over the generic "Exit N".
                    String friendly = "Exit " + exit;
                    Map<String, Object> st = reportService.getStatus("returns-tracker");
                    @SuppressWarnings("unchecked")
                    List<String> logs = (List<String>) st.get("logs");
                    if (logs != null) {
                        for (int i = logs.size() - 1; i >= 0; i--) {
                            String s = logs.get(i) == null ? "" : logs.get(i).trim();
                            if (s.isEmpty()) continue;
                            // Strip the leading "[RETURNS] " prefix if present.
                            if (s.startsWith("[RETURNS]")) s = s.substring(9).trim();
                            // The Python traceback has 'RuntimeError: <msg>' on its last
                            // non-blank line — use that. Also recognize ORA-* / DPY-* codes.
                            if (s.startsWith("RuntimeError:") || s.startsWith("ORA-")
                                || s.startsWith("DPY-") || s.startsWith("oracledb.")) {
                                friendly = s.replaceFirst("^RuntimeError:\\s*", "");
                                break;
                            }
                        }
                    }
                    reportService.markBuiltinFailed("returns-tracker", friendly);
                    activityLogger.log(user, display, "RETURNS_REFRESH_FAIL",
                        "Returns refresh exit " + exit + " — " + friendly);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[RETURNS] Refresh error", e);
                reportService.markBuiltinFailed("returns-tracker", e.getMessage());
                activityLogger.log(user, display, "RETURNS_REFRESH_FAIL", e.getMessage());
            }
        }, "returns-tracker-runner");
        worker.setDaemon(true);
        worker.start();
        return ok("Returns Tracker refresh started");
    }

    // =========================================================================
    // POST /email — Send the current view to recipients
    // =========================================================================

    @PostMapping("/email")
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendEmail(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!canEdit(session)) return forbidden();
        try {
            String from = (String) body.get("from");
            String to = (String) body.get("to");
            List<String> overrideRecipients = (List<String>) body.get("recipients");
            String subjectSuffix = body.get("subjectSuffix") != null
                ? body.get("subjectSuffix").toString() : " (ad-hoc)";
            int sent = emailService.sendForWindow(LocalDate.parse(from), LocalDate.parse(to),
                                                   overrideRecipients, subjectSuffix);
            activityLogger.log(username(session), displayName(session),
                    "RETURNS_EMAIL", "Sent Returns Tracker email to " + sent + " recipients");
            return ok("Email sent to " + sent + " recipient(s)");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[RETURNS] Email failed", e);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Email failed: " + e.getMessage());
            return r;
        }
    }

    // =========================================================================
    // POST /email-me — Send the current view ONLY to the logged-in user
    // (preview/test channel; doesn't notify the configured distribution)
    // =========================================================================

    @PostMapping("/email-me")
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendEmailToMe(@RequestBody Map<String, Object> body, HttpSession session) {
        String myEmail = (String) session.getAttribute("email");
        if (myEmail == null || myEmail.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "No email address on your session — log out and back in.");
            return r;
        }
        try {
            String from = (String) body.get("from");
            String to = (String) body.get("to");
            int sent = emailService.sendForWindow(LocalDate.parse(from), LocalDate.parse(to),
                    Collections.singletonList(myEmail), " (preview)");
            activityLogger.log(username(session), displayName(session),
                    "RETURNS_EMAIL_ME", "Sent Returns Tracker preview to self");
            return ok("Sent to " + myEmail + " (" + sent + " message)");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[RETURNS] Email-me failed", e);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Email failed: " + e.getMessage());
            return r;
        }
    }

    // =========================================================================
    // GET /export — Download Excel matching Noraida's "ECN Pullback" template
    // =========================================================================

    @GetMapping("/export")
    public void exportExcel(@RequestParam String from, @RequestParam String to,
                              HttpServletResponse response, HttpSession session) throws IOException {
        try {
            LocalDate fromDate = LocalDate.parse(from);
            LocalDate toDate = LocalDate.parse(to);
            List<Map<String, Object>> events = rejectionService.getEventsInRange(fromDate, toDate);
            byte[] xlsx = emailService.buildExcel(events, ecnService.getEcnLookup());
            String filename = "ECN_Return_to_Pending_" + from + "_to_" + to + ".xlsx";
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            response.setContentLength(xlsx.length);
            activityLogger.log(username(session), displayName(session),
                    "RETURNS_EXPORT", "Exported Returns Excel " + from + " → " + to
                            + " (" + events.size() + " events, " + xlsx.length + " bytes)");
            response.getOutputStream().write(xlsx);
            response.getOutputStream().flush();
        } catch (DateTimeParseException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid date format");
        }
    }

    // =========================================================================
    // GET /recipients
    // =========================================================================

    @GetMapping("/recipients")
    public Map<String, Object> getRecipients() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("recipients", rejectionService.getRecipients());
        return r;
    }

    // =========================================================================
    // PUT /recipients
    // =========================================================================

    @PutMapping("/recipients")
    @SuppressWarnings("unchecked")
    public Map<String, Object> setRecipients(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!canEdit(session)) return forbidden();
        List<String> recipients = (List<String>) body.get("recipients");
        if (recipients == null) recipients = new ArrayList<>();
        rejectionService.setRecipients(recipients);
        activityLogger.log(username(session), displayName(session),
                "RETURNS_RECIPIENTS_UPDATE", "Updated to " + recipients.size() + " recipients");
        return ok("Recipients updated");
    }

    // =========================================================================
    // GET /explain/{eventId} — On-demand AI explanation for a single event
    // =========================================================================

    @GetMapping("/explain/{eventId}")
    public Map<String, Object> explain(@PathVariable String eventId, HttpSession session) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            String text = emailService.explainEvent(eventId);
            r.put("success", true);
            r.put("explanation", text);
            activityLogger.log(username(session), displayName(session),
                    "RETURNS_EXPLAIN", "AI drill-down for event " + eventId);
        } catch (Exception e) {
            logger.warning("[RETURNS] Explain failed for " + eventId + ": " + e.getMessage());
            r.put("success", false);
            r.put("message", e.getMessage());
        }
        return r;
    }

    // =========================================================================
    // GET /periods — frozen snapshot dropdown source
    // =========================================================================

    @GetMapping("/periods")
    public Map<String, Object> periods() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("enhancedView", returnsEnhancedView);
        r.put("periods", snapshotService.listPeriods());
        return r;
    }

    // =========================================================================
    // POST /snapshot — freeze the last completed period on demand (admin)
    // body: { "type": "week"|"month"|"quarter" } (default month)
    // =========================================================================

    @PostMapping("/snapshot")
    public Map<String, Object> snapshot(@RequestBody(required = false) Map<String, Object> body,
                                        HttpSession session) {
        if (!canEdit(session)) return forbidden();
        String type = body != null && body.get("type") != null ? body.get("type").toString() : "month";
        LocalDate today = LocalDate.now();
        String id; LocalDate from, to;
        if ("week".equals(type)) {
            to = today.minusDays((today.getDayOfWeek().getValue() % 7) + 1); // last Sunday
            from = to.minusDays(6);
            id = RejectionSnapshotService.weekId(to);
        } else if ("quarter".equals(type)) {
            LocalDate prevQuarterDay = today.withDayOfMonth(1).minusMonths(
                ((today.getMonthValue() - 1) % 3) + 1);
            LocalDate[] qb = RejectionSnapshotService.quarterBounds(
                prevQuarterDay.getYear(), (prevQuarterDay.getMonthValue() - 1) / 3 + 1);
            from = qb[0]; to = qb[1]; id = RejectionSnapshotService.quarterId(qb[0]);
        } else {
            LocalDate firstThis = today.withDayOfMonth(1);
            to = firstThis.minusDays(1);
            from = to.withDayOfMonth(1);
            id = RejectionSnapshotService.monthId(from);
        }
        boolean created = snapshotService.writeSnapshot(id, from, to);
        activityLogger.log(username(session), displayName(session),
                "RETURNS_SNAPSHOT", (created ? "Created" : "Already exists") + " snapshot " + id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("id", id);
        r.put("created", created);
        r.put("message", created ? "Snapshot " + id + " created" : "Snapshot " + id + " already exists");
        return r;
    }
}
