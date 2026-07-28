package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.DueDateExpirationService;
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
 * Due Date Expiration view — under the ECN Dashboard tab. In-app, cached replica
 * of the daily <em>ECN Due Date Expiration</em> email plus a daily-dues trend and
 * per-analyst at-risk counts. Data comes from {@link DueDateExpirationService}
 * (direct Agile Oracle SQL, no SDK).
 */
@RestController
@RequestMapping("/api/ecn-report/due-date")
public class DueDateExpirationController {

    @Autowired private DueDateExpirationService dueDateService;
    @Autowired(required = false) private ActivityLogger activityLogger;

    @GetMapping("/data")
    public ResponseEntity<?> data(HttpSession session,
                                  @RequestParam(value = "includeProj", required = false) Boolean includeProj) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        return ResponseEntity.ok(dueDateService.getData(includeProj));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpSession session,
                                     @RequestParam(value = "includeProj", required = false) Boolean includeProj) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        Map<String, Object> out = dueDateService.refresh(includeProj);
        if (activityLogger != null) {
            try {
                String user = String.valueOf(session.getAttribute("username"));
                activityLogger.log(user, user, "duedate-refresh", "includeProj=" + includeProj);
            } catch (Exception ignore) { }
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/trend")
    public ResponseEntity<?> trend(HttpSession session) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        return ResponseEntity.ok(dueDateService.getTrend());
    }

    /** Streams the current population as .xlsx (columns match the email table). */
    @GetMapping("/download")
    public void download(HttpSession session,
                         @RequestParam(value = "includeProj", required = false) Boolean includeProj,
                         HttpServletResponse response) throws IOException {
        if (!isLoggedIn(session)) {
            response.setStatus(401);
            response.getWriter().write("{\"error\":\"Login required.\"}");
            return;
        }
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "ECN-Due-Date-Expiration-" + date + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        dueDateService.buildExcel(response.getOutputStream(), includeProj);
        if (activityLogger != null) {
            try {
                String user = String.valueOf(session.getAttribute("username"));
                activityLogger.log(user, user, "duedate-export", "includeProj=" + includeProj);
            } catch (Exception ignore) { }
        }
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
