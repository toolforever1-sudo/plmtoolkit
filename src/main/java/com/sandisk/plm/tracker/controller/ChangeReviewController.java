package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.ChangeReviewService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/change-reviews")
public class ChangeReviewController {

    private final ChangeReviewService changeReviewService;
    private final ActivityLogger activityLogger;

    public ChangeReviewController(ChangeReviewService changeReviewService,
                                  ActivityLogger activityLogger) {
        this.changeReviewService = changeReviewService;
        this.activityLogger = activityLogger;
    }

    // =========================================================================
    // GET /api/change-reviews/users — cached analyst list from Agile
    // =========================================================================

    @GetMapping("/users")
    public List<Map<String, String>> getUsers(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "") String country) {
        List<Map<String, String>> all = changeReviewService.getAnalysts();
        String query = q.trim().toLowerCase();
        // Filter by country if specified (comma-separated)
        if (!country.isEmpty()) {
            Set<String> countries = new LinkedHashSet<>();
            for (String c : country.split(",")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty()) countries.add(trimmed.toLowerCase());
            }
            all = all.stream()
                .filter(u -> countries.contains(u.getOrDefault("country", "Unknown").toLowerCase()))
                .collect(Collectors.toList());
        }
        if (query.isEmpty()) return all;
        return all.stream()
            .filter(u -> u.get("displayName").toLowerCase().contains(query)
                      || u.get("loginId").toLowerCase().contains(query))
            .limit(50)
            .collect(Collectors.toList());
    }

    @GetMapping("/countries")
    public List<Map<String, Object>> getCountries() {
        List<Map<String, String>> all = changeReviewService.getAnalysts();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> u : all) {
            String c = u.getOrDefault("country", "Unknown");
            counts.put(c, counts.getOrDefault(c, 0) + 1);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        counts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("country", e.getKey());
                m.put("count", e.getValue());
                result.add(m);
            });
        return result;
    }

    // =========================================================================
    // GET /api/change-reviews/list?loginid=7166137,22264
    // =========================================================================

    @GetMapping("/list")
    public Map<String, Object> listChanges(@RequestParam String loginid, HttpSession session) {
        long start = System.currentTimeMillis();
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            // Split comma-separated login IDs
            List<String> loginIds = new ArrayList<>();
            for (String id : loginid.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) loginIds.add(trimmed);
            }

            List<Map<String, String>> rows = changeReviewService.getChangesByAnalyst(loginIds);

            // Fetch signoff summaries in one batch query
            List<String> changeNums = new ArrayList<>();
            for (Map<String, String> r : rows) changeNums.add(r.get("changeNumber"));
            Map<String, Map<String, Object>> summaries = changeReviewService.getSignoffSummaries(changeNums);

            // Enrich each row with its signoff summary
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, String> r : rows) {
                Map<String, Object> row = new LinkedHashMap<>(r);
                Map<String, Object> summary = summaries.get(r.get("changeNumber"));
                if (summary != null) {
                    row.put("approved", summary.get("approved"));
                    row.put("awaiting", summary.get("awaiting"));
                    row.put("rejected", summary.get("rejected"));
                    row.put("total", summary.get("total"));
                    row.put("requiredTotal", summary.get("requiredTotal"));
                    row.put("requiredDone", summary.get("requiredDone"));
                    row.put("maxAwaitingDays", summary.get("maxAwaitingDays"));
                    row.put("lastActivityMs", summary.get("lastActivityMs"));
                    row.put("reviewers", summary.get("reviewers"));
                } else {
                    row.put("approved", 0);
                    row.put("awaiting", 0);
                    row.put("total", 0);
                    row.put("maxAwaitingDays", null);
                    row.put("reviewers", new java.util.ArrayList<>());
                }
                enriched.add(row);
            }

            response.put("rows", enriched);
            response.put("count", rows.size());
            response.put("queryTimeMs", System.currentTimeMillis() - start);

            activityLogger.log(
                s(session, "username"), s(session, "displayName"),
                "CHANGE_REVIEW_SEARCH", "loginid=" + loginid + " | " + rows.size() + " changes");
        } catch (Exception e) {
            response.put("error", "Query failed: " + e.getMessage());
        }
        return response;
    }

    // =========================================================================
    // GET /api/change-reviews/detail?change=<change_number>
    // =========================================================================

    @GetMapping("/detail")
    public Map<String, Object> getDetail(@RequestParam String change, HttpSession session) {
        long start = System.currentTimeMillis();
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            Map<String, Object> detail = changeReviewService.getSignoffDetail(change);
            response.putAll(detail);
            response.put("queryTimeMs", System.currentTimeMillis() - start);

            @SuppressWarnings("unchecked")
            List<?> detailRows = (List<?>) detail.get("rows");
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "CHANGE_REVIEW_DETAIL", change.trim() + " | " + (detailRows != null ? detailRows.size() : 0) + " reviewers");
        } catch (Exception e) {
            response.put("error", "Query failed: " + e.getMessage());
        }
        return response;
    }

    // =========================================================================
    // GET /api/change-reviews/dashboard — all changes across all analysts
    // =========================================================================

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(
            @RequestParam(defaultValue = "30") int days,
            HttpSession session) {
        long start = System.currentTimeMillis();
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            List<Map<String, String>> rows = changeReviewService.getAllChangesInReview(days);

            // Enrich with signoff summaries
            List<String> changeNums = new ArrayList<>();
            for (Map<String, String> r : rows) changeNums.add(r.get("changeNumber"));
            Map<String, Map<String, Object>> summaries = changeReviewService.getSignoffSummaries(changeNums);

            // Enrich + build country map from analyst cache
            Map<String, String> analystCountry = new LinkedHashMap<>();
            for (Map<String, String> a : changeReviewService.getAnalysts()) {
                analystCountry.put(a.get("loginId"), a.getOrDefault("country", "Unknown"));
            }

            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, String> r : rows) {
                Map<String, Object> row = new LinkedHashMap<>(r);
                Map<String, Object> summary = summaries.get(r.get("changeNumber"));
                if (summary != null) {
                    row.put("approved", summary.get("approved"));
                    row.put("awaiting", summary.get("awaiting"));
                    row.put("rejected", summary.get("rejected"));
                    row.put("total", summary.get("total"));
                    row.put("requiredTotal", summary.get("requiredTotal"));
                    row.put("requiredDone", summary.get("requiredDone"));
                    row.put("maxAwaitingDays", summary.get("maxAwaitingDays"));
                    row.put("lastActivityMs", summary.get("lastActivityMs"));
                    row.put("reviewers", summary.get("reviewers"));
                } else {
                    row.put("approved", 0);
                    row.put("awaiting", 0);
                    row.put("rejected", 0);
                    row.put("total", 0);
                    row.put("maxAwaitingDays", null);
                    row.put("reviewers", new ArrayList<>());
                }
                row.put("country", analystCountry.getOrDefault(r.get("ownerLoginId"), "Unknown"));
                enriched.add(row);
            }

            response.put("rows", enriched);
            response.put("count", rows.size());
            response.put("queryTimeMs", System.currentTimeMillis() - start);

            response.put("days", days);
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "CHANGE_REVIEW_DASHBOARD", "days=" + days + " | " + rows.size() + " changes across all analysts");
        } catch (Exception e) {
            response.put("error", "Dashboard query failed: " + e.getMessage());
        }
        return response;
    }

    // =========================================================================
    // GET /api/change-reviews/dashboard/export — Excel export
    // =========================================================================

    @GetMapping("/dashboard/export")
    public void exportDashboard(@RequestParam(defaultValue = "30") int days,
                                HttpSession session, HttpServletResponse response) throws IOException {
        Map<String, Object> data = dashboard(days, session);
        if (data.containsKey("error")) {
            response.sendError(500, (String) data.get("error"));
            return;
        }

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "Change-Review-Dashboard-" + date + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");

        SXSSFWorkbook wb = new SXSSFWorkbook(500);
        try {
            Sheet sheet = wb.createSheet("Review Dashboard");
            String[] headers = {"Change Number", "Description", "Owner", "Country", "Status",
                                "Workflow", "Change Type", "Priority", "Age (days)",
                                "Approved", "Awaiting", "Rejected", "Total Reviewers", "% Complete"};

            // Header style
            CellStyle hStyle = wb.createCellStyle();
            Font hFont = wb.createFont();
            hFont.setBold(true);
            hFont.setColor(IndexedColors.WHITE.getIndex());
            hStyle.setFont(hFont);
            hStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            hStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(hStyle);
            }

            int rowIdx = 1;
            for (Map<String, Object> r : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(str(r, "changeNumber"));
                row.createCell(1).setCellValue(str(r, "description"));
                row.createCell(2).setCellValue(str(r, "ownerName"));
                row.createCell(3).setCellValue(str(r, "country"));
                row.createCell(4).setCellValue(str(r, "status"));
                row.createCell(5).setCellValue(str(r, "workflow"));
                row.createCell(6).setCellValue(str(r, "changeType"));
                row.createCell(7).setCellValue(str(r, "priority"));
                Object age = r.get("maxAwaitingDays");
                if (age != null) row.createCell(8).setCellValue(((Number) age).intValue());
                Object app = r.get("approved");
                if (app != null) row.createCell(9).setCellValue(((Number) app).intValue());
                Object aw = r.get("awaiting");
                if (aw != null) row.createCell(10).setCellValue(((Number) aw).intValue());
                Object rej = r.get("rejected");
                if (rej != null) row.createCell(11).setCellValue(((Number) rej).intValue());
                Object tot = r.get("total");
                if (tot != null) row.createCell(12).setCellValue(((Number) tot).intValue());
                Object reqT = r.get("requiredTotal");
                Object reqD = r.get("requiredDone");
                if (reqT != null && ((Number) reqT).intValue() > 0) {
                    int pct = Math.round(((Number) reqD).floatValue() / ((Number) reqT).floatValue() * 100);
                    row.createCell(13).setCellValue(pct + "%");
                }
            }

            sheet.setColumnWidth(0, 5000);
            sheet.setColumnWidth(1, 15000);
            sheet.setColumnWidth(2, 5000);
            sheet.setColumnWidth(3, 4000);
            sheet.setColumnWidth(5, 6000);
            sheet.createFreezePane(0, 1);

            wb.write(response.getOutputStream());
        } finally {
            wb.close();
            wb.dispose();
        }
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }

    private String s(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "";
    }
}
