package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.DocReviewService;
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
import java.util.List;
import java.util.Map;

/**
 * Backs the Review Tracker tab — documents in Agile whose Next Review Date is
 * within the requested window and whose lifecycle phase is not OBS / OBS-SKU /
 * Preliminary.
 */
@RestController
@RequestMapping("/api/doc-review")
public class DocReviewController {

    @Autowired private DocReviewService docReviewService;
    @Autowired private ActivityLogger activityLogger;

    @GetMapping("/data")
    public ResponseEntity<?> data(HttpSession session,
                                  @RequestParam(value = "window", required = false) String windowParam,
                                  @RequestParam(value = "from",   required = false) String fromIso,
                                  @RequestParam(value = "to",     required = false) String toIso,
                                  @RequestParam(value = "force",  required = false, defaultValue = "false") boolean force) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        DocReviewService.Window window = DocReviewService.parseWindow(windowParam);
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        try {
            rows = docReviewService.search(window, fromIso, toIso, force);
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
        long ms = System.currentTimeMillis() - t0;
        boolean cacheHit = (ms < 1000); // cache hits are sub-second
        if (!cacheHit) {
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                    "DOC_REVIEW_SEARCH",
                    "window=" + window + " | rows=" + rows.size() + " | " + ms + "ms"
                    + (force ? " | forced" : "")
                    + summarizeCustom(window, fromIso, toIso));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("count", rows.size());
        out.put("queryTimeMs", ms);
        out.put("window", window.name());
        out.put("windowDescription", DocReviewService.describeWindow(window, fromIso, toIso));
        out.put("cacheBuiltAtMs", docReviewService.cacheBuiltAtMs());
        out.put("cacheHit", cacheHit);
        return ResponseEntity.ok(out);
    }

    /** POST /refresh: explicit re-query. Same shape as /data but always force=true. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpSession session,
                                     @RequestParam(value = "window", required = false) String windowParam,
                                     @RequestParam(value = "from",   required = false) String fromIso,
                                     @RequestParam(value = "to",     required = false) String toIso) {
        return data(session, windowParam, fromIso, toIso, true);
    }

    @GetMapping("/export")
    public void export(HttpSession session,
                       @RequestParam(value = "window", required = false) String windowParam,
                       @RequestParam(value = "from",   required = false) String fromIso,
                       @RequestParam(value = "to",     required = false) String toIso,
                       HttpServletResponse response) throws IOException {
        if (!isLoggedIn(session)) {
            response.setStatus(401);
            response.getWriter().write("{\"error\":\"Login required.\"}");
            return;
        }
        DocReviewService.Window window = DocReviewService.parseWindow(windowParam);
        List<Map<String, Object>> rows = docReviewService.search(window, fromIso, toIso);
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "Doc-Reviews-" + date + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        docReviewService.writeExcel(response.getOutputStream(), rows);
        activityLogger.log(s(session, "username"), s(session, "displayName"),
                "DOC_REVIEW_EXPORT",
                "window=" + window + " | rows=" + rows.size()
                + summarizeCustom(window, fromIso, toIso));
    }

    private static String summarizeCustom(DocReviewService.Window window, String fromIso, String toIso) {
        if (window != DocReviewService.Window.CUSTOM) return "";
        return " | from=" + (fromIso == null ? "" : fromIso)
             + " | to="   + (toIso   == null ? "" : toIso);
    }

    private static boolean isLoggedIn(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null && !u.toString().isEmpty();
    }

    private static String s(HttpSession session, String key) {
        if (session == null) return "unknown";
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "unknown";
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
