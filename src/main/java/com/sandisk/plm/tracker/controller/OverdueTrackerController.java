package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.OverdueTrackerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Overdue Tracker — companion to the Returns Tracker under the ECN Report tab.
 * Surfaces open Standard ECNs that are over their cycle-time target with an
 * AI-categorized view of why each one is sitting (CCB / Approval Delay, Pending
 * Stakeholder Action, etc.). Source data comes from {@code change_history.comments}.
 */
@RestController
@RequestMapping("/api/ecn-report/overdue")
public class OverdueTrackerController {

    @Autowired private OverdueTrackerService overdueService;
    @Autowired private ActivityLogger activityLogger;

    @GetMapping("/data")
    public ResponseEntity<?> data(HttpSession session,
                                  @RequestParam(value = "minOver",         required = false) Integer minOver,
                                  @RequestParam(value = "maxOver",         required = false) Integer maxOver,
                                  @RequestParam(value = "createdRange",    required = false) String createdRange,
                                  @RequestParam(value = "createdFrom",     required = false) String createdFrom,
                                  @RequestParam(value = "createdTo",       required = false) String createdTo,
                                  @RequestParam(value = "releasedRange",   required = false) String releasedRange,
                                  @RequestParam(value = "releasedFrom",    required = false) String releasedFrom,
                                  @RequestParam(value = "releasedTo",      required = false) String releasedTo,
                                  @RequestParam(value = "classifications", required = false) String classifications) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        Map<String, Object> out = overdueService.getData(minOver, maxOver,
                createdRange, createdFrom, createdTo,
                releasedRange, releasedFrom, releasedTo,
                classifications);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpSession session,
                                     @RequestParam(value = "minOver",         required = false) Integer minOver,
                                     @RequestParam(value = "maxOver",         required = false) Integer maxOver,
                                     @RequestParam(value = "createdRange",    required = false) String createdRange,
                                     @RequestParam(value = "createdFrom",     required = false) String createdFrom,
                                     @RequestParam(value = "createdTo",       required = false) String createdTo,
                                     @RequestParam(value = "releasedRange",   required = false) String releasedRange,
                                     @RequestParam(value = "releasedFrom",    required = false) String releasedFrom,
                                     @RequestParam(value = "releasedTo",      required = false) String releasedTo,
                                     @RequestParam(value = "classifications", required = false) String classifications) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        Map<String, Object> out = overdueService.refresh(minOver, maxOver,
                createdRange, createdFrom, createdTo,
                releasedRange, releasedFrom, releasedTo,
                classifications);
        activityLogger.log(s(session, "username"), s(session, "displayName"),
                "OVERDUE_REFRESH", filterSummary(minOver, maxOver,
                        createdRange, createdFrom, createdTo,
                        releasedRange, releasedFrom, releasedTo,
                        classifications,
                        out.get("count"), out.get("mode")));
        return ResponseEntity.ok(out);
    }

    /** Streams the current Overdue Tracker population as .xlsx. Filters mirror /data
     *  so a saved URL or scheduled report can re-pull the same slice. */
    @GetMapping("/export")
    public void export(HttpSession session,
                       @RequestParam(value = "minOver",         required = false) Integer minOver,
                       @RequestParam(value = "maxOver",         required = false) Integer maxOver,
                       @RequestParam(value = "createdRange",    required = false) String createdRange,
                       @RequestParam(value = "createdFrom",     required = false) String createdFrom,
                       @RequestParam(value = "createdTo",       required = false) String createdTo,
                       @RequestParam(value = "releasedRange",   required = false) String releasedRange,
                       @RequestParam(value = "releasedFrom",    required = false) String releasedFrom,
                       @RequestParam(value = "releasedTo",      required = false) String releasedTo,
                       @RequestParam(value = "classifications", required = false) String classifications,
                       HttpServletResponse response) throws IOException {
        if (!isLoggedIn(session)) {
            response.setStatus(401);
            response.getWriter().write("{\"error\":\"Login required.\"}");
            return;
        }
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        boolean retrospective = (releasedFrom != null && !releasedFrom.trim().isEmpty())
                || (releasedTo != null && !releasedTo.trim().isEmpty())
                || (releasedRange != null && !releasedRange.trim().isEmpty()
                        && !"none".equalsIgnoreCase(releasedRange.trim()));
        String filename = (retrospective ? "Overdue-Tracker-Retrospective-" : "Overdue-Tracker-") + date + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        overdueService.buildExcel(response.getOutputStream(), minOver, maxOver,
                createdRange, createdFrom, createdTo,
                releasedRange, releasedFrom, releasedTo,
                classifications);
        activityLogger.log(s(session, "username"), s(session, "displayName"),
                "OVERDUE_EXPORT", filterSummary(minOver, maxOver,
                        createdRange, createdFrom, createdTo,
                        releasedRange, releasedFrom, releasedTo,
                        classifications,
                        null, retrospective ? "retrospective" : "open"));
    }

    private static String s(HttpSession session, String key) {
        if (session == null) return "unknown";
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "unknown";
    }

    private static String filterSummary(Integer minOver, Integer maxOver,
                                        String createdRange, String createdFrom, String createdTo,
                                        String releasedRange, String releasedFrom, String releasedTo,
                                        String classifications,
                                        Object count, Object mode) {
        StringBuilder sb = new StringBuilder();
        if (mode != null)  sb.append("mode=").append(mode);
        if (count != null) sb.append(" | rows=").append(count);
        sb.append(" | over=").append(minOver == null ? 0 : minOver)
          .append("-").append(maxOver == null ? 170 : maxOver);
        if (createdRange != null && !createdRange.trim().isEmpty())
            sb.append(" | created=").append(createdRange.trim())
              .append(createdFrom == null || createdFrom.isEmpty() ? "" : "(" + createdFrom + "→" + (createdTo == null ? "" : createdTo) + ")");
        if (releasedRange != null && !releasedRange.trim().isEmpty())
            sb.append(" | released=").append(releasedRange.trim())
              .append(releasedFrom == null || releasedFrom.isEmpty() ? "" : "(" + releasedFrom + "→" + (releasedTo == null ? "" : releasedTo) + ")");
        if (classifications != null && !classifications.trim().isEmpty())
            sb.append(" | cls=[").append(classifications.trim()).append("]");
        return sb.toString();
    }

    /** Live progress snapshot for the long-running data/refresh call. The frontend
     *  polls this every ~1.5s so the user sees "Querying Agile" / "Pulling comment
     *  chains" / "AI-categorizing batch 4 / 10" instead of a generic "Loading…". */
    @GetMapping("/progress")
    public ResponseEntity<?> progress(HttpSession session) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        return ResponseEntity.ok(overdueService.getProgress());
    }

    private static boolean isLoggedIn(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null && !u.toString().isEmpty();
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
