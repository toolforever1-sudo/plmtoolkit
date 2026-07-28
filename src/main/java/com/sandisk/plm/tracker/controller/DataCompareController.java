package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.DataCompareService;
import com.sandisk.plm.tracker.service.DataCompareService.FieldMapping;
import com.sandisk.plm.tracker.service.DataCompareService.MappingSuggestion;
import com.sandisk.plm.tracker.service.ActivityLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/compare")
public class DataCompareController {

    @Autowired
    private DataCompareService dataCompareService;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private com.sandisk.plm.tracker.service.UploadQuarantineService quarantineService;

    /**
     * Upload an Excel/CSV file for comparison.
     * Returns headers, preview rows, total row count, and a session ID.
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                       HttpSession httpSession) {
        Map<String, Object> response = new LinkedHashMap<>();
        String qTicket = quarantineService.quarantine(file, s(httpSession, "username"),
                "/api/data-compare/upload", new LinkedHashMap<>());
        try {
            String filename = file.getOriginalFilename();
            if (filename == null) filename = "upload.xlsx";

            if (file.isEmpty()) {
                response.put("error", "The uploaded file is empty.");
                return response;
            }

            Map<String, Object> result = dataCompareService.handleUpload(
                    file.getInputStream(), filename, file.getSize());

            activityLogger.log(
                    s(httpSession, "username"), s(httpSession, "displayName"),
                    "DATA_COMPARE_UPLOAD",
                    "File: " + filename + " | Rows: " + result.get("totalRows"));

            quarantineService.release(qTicket);
            return result;

        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return response;
        } catch (Exception e) {
            response.put("error", "Upload failed: " + e.getMessage());
            return response;
        }
    }

    /**
     * Apply a filter to the uploaded data.
     * Body: {sessionId, filterColumn, filterValue}
     */
    @PostMapping("/filter")
    public Map<String, Object> filter(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            String sessionId = body.get("sessionId");
            String filterColumn = body.get("filterColumn");
            String filterValue = body.get("filterValue");

            if (sessionId == null || sessionId.isEmpty()) {
                response.put("error", "sessionId is required.");
                return response;
            }

            return dataCompareService.applyFilter(sessionId, filterColumn, filterValue);

        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return response;
        }
    }

    /**
     * Suggest field mappings between Excel columns and Agile field names.
     * Body: {sessionId}
     */
    @PostMapping("/suggest-mappings")
    public Map<String, Object> suggestMappings(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            String sessionId = body.get("sessionId");
            if (sessionId == null || sessionId.isEmpty()) {
                response.put("error", "sessionId is required.");
                return response;
            }

            List<MappingSuggestion> suggestions = dataCompareService.suggestMappings(sessionId);
            response.put("mappings", suggestions);
            return response;

        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return response;
        }
    }

    /**
     * Trigger an async comparison run.
     * Body: {sessionId, partNumberColumn, mappings: [{excelColumn, agileField, action}]}
     */
    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(@RequestBody Map<String, Object> body,
                                    HttpSession httpSession) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            String sessionId = (String) body.get("sessionId");
            String partNumberColumn = (String) body.get("partNumberColumn");

            if (sessionId == null || sessionId.isEmpty()) {
                response.put("error", "sessionId is required.");
                return response;
            }
            if (partNumberColumn == null || partNumberColumn.isEmpty()) {
                response.put("error", "partNumberColumn is required.");
                return response;
            }

            // Parse mappings from request
            List<Map<String, String>> rawMappings = (List<Map<String, String>>) body.get("mappings");
            if (rawMappings == null || rawMappings.isEmpty()) {
                response.put("error", "mappings are required.");
                return response;
            }

            List<FieldMapping> mappings = new ArrayList<>();
            for (Map<String, String> rm : rawMappings) {
                FieldMapping fm = new FieldMapping();
                fm.excelColumn = rm.get("excelColumn");
                fm.agileField = rm.get("agileField");
                fm.action = rm.getOrDefault("action", "skip");
                mappings.add(fm);
            }

            dataCompareService.runComparison(sessionId, partNumberColumn, mappings);

            activityLogger.log(
                    s(httpSession, "username"), s(httpSession, "displayName"),
                    "DATA_COMPARE_RUN",
                    "Session: " + sessionId + " | Key: " + partNumberColumn
                            + " | Mappings: " + mappings.size());

            response.put("status", "running");
            response.put("message", "Comparison started.");
            return response;

        } catch (IllegalArgumentException | IllegalStateException e) {
            response.put("error", e.getMessage());
            return response;
        }
    }

    /**
     * Poll comparison progress.
     */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String sessionId) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            return dataCompareService.getStatus(sessionId);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return response;
        }
    }

    /**
     * Get comparison results.
     */
    @GetMapping("/results")
    public Map<String, Object> results(@RequestParam String sessionId) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            return dataCompareService.getResults(sessionId);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return response;
        }
    }

    /**
     * Export comparison results as an Excel file.
     */
    @GetMapping("/export")
    public void export(@RequestParam String sessionId,
                        HttpSession httpSession,
                        HttpServletResponse httpResponse) {
        try {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = "Data-Compare-" + date + ".xlsx";

            httpResponse.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            httpResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);

            dataCompareService.exportExcel(sessionId, httpResponse.getOutputStream());

            activityLogger.log(
                    s(httpSession, "username"), s(httpSession, "displayName"),
                    "DATA_COMPARE_EXPORT", "Session: " + sessionId);

        } catch (Exception e) {
            httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            try {
                httpResponse.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
            } catch (Exception ignored) {}
        }
    }

    private String s(HttpSession session, String key) {
        if (session == null) return "unknown";
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "unknown";
    }
}
