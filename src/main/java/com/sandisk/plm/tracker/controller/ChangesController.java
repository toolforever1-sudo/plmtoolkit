package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.ChangeQueryService;
import com.sandisk.plm.tracker.service.EmailService;
import com.sandisk.plm.tracker.service.ExcelExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import com.sandisk.plm.tracker.model.ChangeRecord;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/changes")
public class ChangesController {

    @Autowired
    private ChangeQueryService changeQueryService;

    @Autowired
    private ExcelExportService excelExportService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ActivityLogger activityLogger;

    // Cache last search results for fast export/email
    private volatile List<ChangeRecord> lastSearchResults = new ArrayList<>();

    @GetMapping
    public Map<String, Object> getChanges(
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String item,
            @RequestParam(required = false) String user,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String oldContains,
            @RequestParam(required = false) String newContains,
            @RequestParam(defaultValue = "true") boolean netFilter,
            HttpSession session) {

        ChangeQueryService.SearchResult result =
                changeQueryService.search(field, item, user, days,
                        oldContains, newContains, netFilter);

        lastSearchResults = result.getResults();

        activityLogger.log(
            session != null ? (String) session.getAttribute("username") : "unknown",
            session != null ? (String) session.getAttribute("displayName") : "unknown",
            "FIELD_SEARCH", "Results: " + result.getTotalCount() + " | Filters: field=" + field + " item=" + item);

        // Compute age (ms since change) server-side to avoid browser timezone issues
        long serverNow = System.currentTimeMillis();
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (ChangeRecord r : result.getResults()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemNumber", r.getItemNumber());
            row.put("fieldName", r.getFieldName());
            row.put("oldValue", r.getOldValue());
            row.put("newValue", r.getNewValue());
            row.put("timestamp", r.getTimestamp());
            row.put("userName", r.getUserName());
            row.put("revNumber", r.getRevNumber());
            row.put("ageMs", r.getTimestamp() != null ? serverNow - r.getTimestamp().getTime() : 0);
            enriched.add(row);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", enriched);
        response.put("totalCount", result.getTotalCount());
        response.put("uniqueItems", result.getUniqueItems());
        response.put("queryTimeMs", result.getQueryTimeMs());
        response.put("truncated", result.isTruncated());
        response.put("dbOffline", result.isDbOffline());
        response.put("dataAsOf", result.getDataAsOf());
        response.put("serverTime", serverNow);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("field", field);
        filters.put("item", item);
        filters.put("user", user);
        filters.put("days", days);
        filters.put("oldContains", oldContains);
        filters.put("newContains", newContains);
        filters.put("netFilter", netFilter);
        response.put("filters", filters);

        return response;
    }

    @GetMapping("/export")
    public void exportChanges(
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String item,
            @RequestParam(required = false) String user,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String oldContains,
            @RequestParam(required = false) String newContains,
            @RequestParam(defaultValue = "true") boolean netFilter,
            @RequestParam(required = false) String colItemNumber,
            @RequestParam(required = false) String colFieldName,
            @RequestParam(required = false) String colOldValue,
            @RequestParam(required = false) String colNewValue,
            @RequestParam(required = false) String colUserName,
            @RequestParam(required = false) String colRevNumber,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        // Use cached results from last search if available
        List<ChangeRecord> baseRecords;
        if (!lastSearchResults.isEmpty()) {
            baseRecords = lastSearchResults;
        } else {
            ChangeQueryService.SearchResult result =
                    changeQueryService.search(field, item, user, days,
                            oldContains, newContains, netFilter);
            baseRecords = result.getResults();
        }

        List<ChangeRecord> records = applyColumnFilters(baseRecords,
                colItemNumber, colFieldName, colOldValue, colNewValue, colUserName, colRevNumber);

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "PLM-Changes-" + date + ".xlsx";

        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=" + filename);

        activityLogger.log(
            session != null ? (String) session.getAttribute("username") : "unknown",
            session != null ? (String) session.getAttribute("displayName") : "unknown",
            "FIELD_EXPORT", records.size() + " rows exported to Excel");

        excelExportService.export(records, response.getOutputStream());
    }

    @PostMapping("/email")
    public Map<String, Object> emailReport(
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String item,
            @RequestParam(required = false) String user,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String oldContains,
            @RequestParam(required = false) String newContains,
            @RequestParam(defaultValue = "true") boolean netFilter,
            @RequestParam(required = false) String colItemNumber,
            @RequestParam(required = false) String colFieldName,
            @RequestParam(required = false) String colOldValue,
            @RequestParam(required = false) String colNewValue,
            @RequestParam(required = false) String colUserName,
            @RequestParam(required = false) String colRevNumber,
            HttpSession session) {

        Map<String, Object> response = new LinkedHashMap<>();

        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");

        if (email == null || email.isEmpty()) {
            response.put("success", false);
            response.put("message", "Not logged in or email not available.");
            return response;
        }

        try {
            List<ChangeRecord> baseRecords;
            if (!lastSearchResults.isEmpty()) {
                baseRecords = lastSearchResults;
            } else {
                ChangeQueryService.SearchResult result =
                        changeQueryService.search(field, item, user, days,
                                oldContains, newContains, netFilter);
                baseRecords = result.getResults();
            }

            List<ChangeRecord> records = applyColumnFilters(baseRecords,
                    colItemNumber, colFieldName, colOldValue, colNewValue, colUserName, colRevNumber);

            StringBuilder filters = new StringBuilder();
            if (field != null) filters.append("Field: ").append(field).append("; ");
            if (item != null) filters.append("Item: ").append(item).append("; ");
            if (oldContains != null) filters.append("Old: ").append(oldContains).append("; ");
            if (newContains != null) filters.append("New: ").append(newContains).append("; ");
            if (user != null) filters.append("By: ").append(user).append("; ");
            filters.append("Days: ").append(days);

            emailService.sendExcelReport(email, displayName, records, filters.toString());

            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "FIELD_EMAIL", records.size() + " rows emailed");

            response.put("success", true);
            response.put("message", "Report sent to " + email + " (" + records.size() + " records)");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to send email: " + e.getMessage());
        }

        return response;
    }

    private List<ChangeRecord> applyColumnFilters(List<ChangeRecord> records,
            String colItemNumber, String colFieldName, String colOldValue,
            String colNewValue, String colUserName, String colRevNumber) {

        return records.stream().filter(r -> {
            if (colItemNumber != null && !colItemNumber.isEmpty()
                    && !r.getItemNumber().toLowerCase().contains(colItemNumber.toLowerCase())) return false;
            if (colFieldName != null && !colFieldName.isEmpty()
                    && !r.getFieldName().toLowerCase().contains(colFieldName.toLowerCase())) return false;
            if (colOldValue != null && !colOldValue.isEmpty()
                    && !r.getOldValue().toLowerCase().contains(colOldValue.toLowerCase())) return false;
            if (colNewValue != null && !colNewValue.isEmpty()
                    && !r.getNewValue().toLowerCase().contains(colNewValue.toLowerCase())) return false;
            if (colUserName != null && !colUserName.isEmpty()
                    && !r.getUserName().toLowerCase().contains(colUserName.toLowerCase())) return false;
            if (colRevNumber != null && !colRevNumber.isEmpty()
                    && !r.getRevNumber().toLowerCase().contains(colRevNumber.toLowerCase())) return false;
            return true;
        }).collect(Collectors.toList());
    }
}
