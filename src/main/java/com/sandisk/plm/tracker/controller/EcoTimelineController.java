package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.EcoTimelineRow;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.EcoTimelineExcelExportService;
import com.sandisk.plm.tracker.service.EcoTimelineService;
import com.sandisk.plm.tracker.service.EmailService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/eco-timeline")
public class EcoTimelineController {

    private final EcoTimelineService service;
    private final EcoTimelineExcelExportService excelExportService;
    private final EmailService emailService;
    private final ActivityLogger activityLogger;

    public EcoTimelineController(EcoTimelineService service,
                                EcoTimelineExcelExportService excelExportService,
                                EmailService emailService,
                                ActivityLogger activityLogger) {
        this.service = service;
        this.excelExportService = excelExportService;
        this.emailService = emailService;
        this.activityLogger = activityLogger;
    }

    private String s(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "";
    }

    /** Strip anything but [A-Za-z0-9._-] from a value used in a download filename,
     *  so a crafted item number can't inject CR/LF/quotes into the Content-Disposition header. */
    private String safeName(String item) {
        String s = (item == null ? "" : item.trim()).replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "item" : s;
    }

    @GetMapping("/query")
    public Map<String, Object> query(@RequestParam String item,
                                     @RequestParam String from,
                                     @RequestParam String to,
                                     @RequestParam(required = false, defaultValue = "25") int maxDepth,
                                     HttpSession session) {
        Map<String, Object> resp = parseAndRun(item, from, to, maxDepth);
        if (!resp.containsKey("error")) {
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "ECO_TIMELINE", item + " | " + from + ".." + to +
                " | ecos=" + resp.get("ecoCount") + " comps=" + resp.get("componentCount"));
        }
        return resp;
    }

    /** Shared validation + service call. */
    private Map<String, Object> parseAndRun(String item, String from, String to, int maxDepth) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (item == null || item.trim().isEmpty()) {
            resp.put("error", "Item number is required."); return resp;
        }
        LocalDate f, t;
        try { f = LocalDate.parse(from.trim()); t = LocalDate.parse(to.trim()); }
        catch (Exception e) { resp.put("error", "Dates must be yyyy-MM-dd."); return resp; }
        if (f.isAfter(t)) { resp.put("error", "Start date is after end date."); return resp; }
        if (maxDepth < 1) maxDepth = 1;
        if (maxDepth > 99) maxDepth = 99;
        return service.query(item.trim(), f, t, maxDepth);
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/export")
    public void export(@RequestParam String item,
                       @RequestParam String from,
                       @RequestParam String to,
                       @RequestParam(required = false, defaultValue = "25") int maxDepth,
                       HttpServletResponse response) throws IOException {
        Map<String, Object> result = parseAndRun(item, from, to, maxDepth);
        if (result.containsKey("error")) {
            response.sendError(400, (String) result.get("error"));
            return;
        }
        String filename = "ECO-Timeline-" + safeName(item) + "-" + from + "_" + to + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        List<EcoTimelineRow> rows = (List<EcoTimelineRow>) result.get("rows");
        try {
            excelExportService.exportTimeline(rows, item.trim(), from, to, response.getOutputStream());
        } catch (Exception e) {
            response.sendError(500, "Export failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/email")
    public Map<String, Object> email(@RequestParam String item,
                                     @RequestParam String from,
                                     @RequestParam String to,
                                     @RequestParam(required = false, defaultValue = "25") int maxDepth,
                                     HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");
        if (email == null || email.isEmpty()) {
            resp.put("success", false); resp.put("message", "Not logged in."); return resp;
        }
        Map<String, Object> result = parseAndRun(item, from, to, maxDepth);
        if (result.containsKey("error")) {
            resp.put("success", false); resp.put("message", (String) result.get("error")); return resp;
        }
        try {
            List<EcoTimelineRow> rows = (List<EcoTimelineRow>) result.get("rows");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            excelExportService.exportTimeline(rows, item.trim(), from, to, out);
            String filename = "ECO-Timeline-" + safeName(item) + "-" + from + "_" + to + ".xlsx";
            String title = "ECO Timeline: " + item.trim() + " (" + from + " to " + to + ")";
            emailService.sendBomReport(email, displayName, out.toByteArray(), filename, title, rows.size());
            activityLogger.log(s(session, "username"), displayName,
                "ECO_TIMELINE_EMAIL", item.trim() + " | " + rows.size() + " rows emailed");
            resp.put("success", true);
            resp.put("message", "Report sent to " + email + " (" + rows.size() + " rows)");
        } catch (Exception e) {
            resp.put("success", false); resp.put("message", "Failed: " + e.getMessage());
        }
        return resp;
    }
}
