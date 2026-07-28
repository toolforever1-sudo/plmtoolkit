package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.EcnReportService;
import com.sandisk.plm.tracker.service.ReportService;
import com.sandisk.plm.tracker.service.WeeklyRejectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST endpoints for the Weekly Rejection Report view (a sibling view inside the ECN
 * Dashboard tab — see {@link WeeklyRejectionService}).
 *
 * <p>Mounted under {@code /api/ecn-report/rejection/}. Auth mirrors
 * {@link RejectionTrackerController}: read endpoints need a logged-in session (enforced
 * by AuthFilter); mutating endpoints (refresh, manual-fill, send) need canEdit.
 */
@RestController
@RequestMapping("/api/ecn-report/rejection")
public class WeeklyRejectionController {

    private static final Logger logger = Logger.getLogger(WeeklyRejectionController.class.getName());

    @Autowired private WeeklyRejectionService weeklyService;
    @Autowired private EcnReportService ecnService;
    @Autowired private ReportService reportService;
    @Autowired private ActivityLogger activityLogger;

    // ---- session helpers (mirror RejectionTrackerController) ----
    private String username(HttpSession s) {
        Object u = s.getAttribute("username");
        return u != null ? u.toString() : "unknown";
    }
    private String displayName(HttpSession s) { return (String) s.getAttribute("displayName"); }
    private boolean isAdmin(HttpSession s) { return Boolean.TRUE.equals(s.getAttribute("isPlmAdmin")); }
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
    // GET /data?lookbackDays=7
    // =========================================================================
    @GetMapping("/data")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getData(@RequestParam(required = false) Integer lookbackDays) {
        int lb = lookbackDays != null ? lookbackDays : weeklyService.getDefaultLookbackDays();
        Map<String, Object> r = new LinkedHashMap<>();
        if (!weeklyService.hasData()) {
            r.put("success", false);
            r.put("message", "No rejection report yet. Click Refresh to generate.");
            // Still surface refresh status so the UI poll can detect an in-flight/failed run.
            attachRefreshStatus(r);
            return r;
        }
        r.putAll(weeklyService.getData(lb));
        r.put("success", true);
        attachRefreshStatus(r);
        return r;
    }

    @SuppressWarnings("unchecked")
    private void attachRefreshStatus(Map<String, Object> r) {
        Map<String, Object> st = reportService.getStatus(WeeklyRejectionService.JOB_ID);
        r.put("refreshStatus", st.get("status"));
        r.put("refreshError", st.get("error"));
        List<String> logs = (List<String>) st.get("logs");
        if (logs != null && !logs.isEmpty()) {
            for (int i = logs.size() - 1; i >= 0; i--) {
                String s = logs.get(i) == null ? "" : logs.get(i).trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("╔") || s.startsWith("╚") || s.startsWith("║")) continue;
                if (s.startsWith("─") || s.startsWith("——")) continue;
                r.put("refreshLogTail", s);
                break;
            }
        }
    }

    // =========================================================================
    // POST /refresh  body {lookbackDays}
    // =========================================================================
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        if (!canEdit(session)) return forbidden();
        final int lookbackDays = body != null && body.get("lookbackDays") != null
                ? Integer.parseInt(body.get("lookbackDays").toString())
                : weeklyService.getDefaultLookbackDays();
        final String user = username(session);
        final String display = displayName(session);

        reportService.markBuiltinRunning(WeeklyRejectionService.JOB_ID, "Weekly Rejection Report");
        activityLogger.log(user, display, "REJECTION_REPORT_REFRESH",
                "Started Weekly Rejection Report refresh (lookbackDays=" + lookbackDays + ")");

        Thread worker = new Thread(() -> {
            try {
                int exit = weeklyService.runReport(lookbackDays, null,
                        line -> { reportService.appendLog(WeeklyRejectionService.JOB_ID, line); logger.info("[REJECT-REPORT] " + line); });
                if (exit == 0) {
                    reportService.markBuiltinCompleted(WeeklyRejectionService.JOB_ID);
                    activityLogger.log(user, display, "REJECTION_REPORT_REFRESH_OK", "Weekly Rejection Report refresh complete");
                } else {
                    String friendly = friendlyError(exit);
                    reportService.markBuiltinFailed(WeeklyRejectionService.JOB_ID, friendly);
                    activityLogger.log(user, display, "REJECTION_REPORT_REFRESH_FAIL", "exit " + exit + " — " + friendly);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[REJECT-REPORT] Refresh error", e);
                reportService.markBuiltinFailed(WeeklyRejectionService.JOB_ID, e.getMessage());
                activityLogger.log(user, display, "REJECTION_REPORT_REFRESH_FAIL", e.getMessage());
            }
        }, "rejection-report-runner");
        worker.setDaemon(true);
        worker.start();
        return ok("Weekly Rejection Report refresh started");
    }

    @SuppressWarnings("unchecked")
    private String friendlyError(int exit) {
        String friendly = "Exit " + exit;
        Map<String, Object> st = reportService.getStatus(WeeklyRejectionService.JOB_ID);
        List<String> logs = (List<String>) st.get("logs");
        if (logs != null) {
            for (int i = logs.size() - 1; i >= 0; i--) {
                String s = logs.get(i) == null ? "" : logs.get(i).trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("[REJECT-REPORT]")) s = s.substring(15).trim();
                if (s.startsWith("RuntimeError:") || s.startsWith("ORA-") || s.startsWith("DPY-") || s.startsWith("oracledb.")) {
                    return s.replaceFirst("^RuntimeError:\\s*", "");
                }
            }
        }
        return friendly;
    }

    // =========================================================================
    // PUT /manual-fill  body {changeNumber, field, value}
    // =========================================================================
    @PutMapping("/manual-fill")
    public Map<String, Object> manualFill(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!canEdit(session)) return forbidden();
        String changeNumber = strOf(body.get("changeNumber"));
        String field = strOf(body.get("field"));
        String value = strOf(body.get("value"));
        if (changeNumber.isEmpty() || field.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "changeNumber and field are required");
            return r;
        }
        Map<String, Object> updated = weeklyService.applyManualFill(changeNumber, field, value);
        activityLogger.log(username(session), displayName(session), "REJECTION_REPORT_MANUAL_FILL",
                "Set " + field + " for " + changeNumber);
        Map<String, Object> r = new LinkedHashMap<>(updated);
        r.put("success", true);
        return r;
    }

    // =========================================================================
    // GET /download?lookbackDays=7&force=false
    // =========================================================================
    @GetMapping("/download")
    public void download(@RequestParam(required = false) Boolean force,
                         HttpServletResponse response, HttpSession session) throws IOException {
        boolean f = Boolean.TRUE.equals(force);
        if (!f && !weeklyService.isReadyToExport()) {
            response.sendError(HttpServletResponse.SC_CONFLICT,
                    "Resolve the " + weeklyService.getManualFillGaps().size()
                            + " manual-fill cell(s) first, or export with force=true.");
            return;
        }
        try {
            byte[] xlsx = weeklyService.readLatestExcel();
            if (xlsx == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No report generated yet — click Refresh first.");
                return;
            }
            String filename = weeklyService.latestExcelName();
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            response.setContentLength(xlsx.length);
            activityLogger.log(username(session), displayName(session), "REJECTION_REPORT_EXPORT",
                    "Exported " + filename + " (" + xlsx.length + " bytes)");
            response.getOutputStream().write(xlsx);
            response.getOutputStream().flush();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[REJECT-REPORT] Download failed", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Export failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // POST /send-to-me  body {force?:boolean}
    // =========================================================================
    @PostMapping("/send-to-me")
    public Map<String, Object> sendToMe(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        String myEmail = (String) session.getAttribute("email");
        if (myEmail == null || myEmail.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "No email address on your session — log out and back in.");
            return r;
        }
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        if (!force && !weeklyService.isReadyToExport()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Resolve the " + weeklyService.getManualFillGaps().size()
                    + " manual-fill cell(s) first, or send anyway.");
            r.put("needsForce", true);
            return r;
        }
        try {
            weeklyService.sendToMe(myEmail);
            activityLogger.log(username(session), displayName(session), "REJECTION_REPORT_SEND_ME", "Sent report to self");
            Map<String, Object> r = ok("Sent to " + myEmail);
            r.put("sentTo", myEmail);
            return r;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[REJECT-REPORT] Send-to-me failed", e);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "Email failed: " + e.getMessage());
            return r;
        }
    }

    private static String strOf(Object o) { return o == null ? "" : o.toString().trim(); }
}
