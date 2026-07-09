package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.AgileFieldNameService;
import com.sandisk.plm.tracker.service.AgileLookupService;
import com.sandisk.plm.tracker.service.EmailService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/agile-lookup")
public class AgileLookupController {

    @Autowired
    private AgileLookupService agileLookupService;

    @Autowired
    private com.sandisk.plm.tracker.service.UploadQuarantineService quarantineService;

    @Autowired
    private AgileFieldNameService agileFieldNameService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ActivityLogger activityLogger;

    // Cache last results for export/email
    private volatile List<Map<String, String>> lastResults = new ArrayList<>();
    private volatile List<String> lastColumns = new ArrayList<>();

    @PostMapping("/upload")
    @SuppressWarnings("unchecked")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "itemColumn", required = false) Integer itemColumn,
                                       HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, String> qParams = new LinkedHashMap<>();
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file, s(session, "username"),
                "/api/agile-lookup/upload", qParams);

        try {
            // Validate file
            String filename = file.getOriginalFilename();
            if (filename == null || (!filename.toLowerCase().endsWith(".xlsx") && !filename.toLowerCase().endsWith(".xls"))) {
                response.put("error", "Please upload an Excel file (.xlsx or .xls). Got: " +
                    (filename != null ? filename : "unknown"));
                return response;
            }
            if (file.isEmpty()) {
                response.put("error", "The uploaded file is empty.");
                return response;
            }
            if (file.getSize() > 10 * 1024 * 1024) {
                response.put("error", "File too large (max 10MB).");
                return response;
            }

            // Check if Agile microservice is running
            if (!agileLookupService.isServiceAvailable()) {
                response.put("error", "The Agile Lookup service is not running. Please contact PLM Admin to start it.");
                return response;
            }

            // Forward to microservice
            byte[] fileBytes = file.getBytes();
            Map<String, Object> result = agileLookupService.forwardLookup(fileBytes, filename, itemColumn);

            // Check for errors from microservice
            if (result.containsKey("error")) {
                response.put("error", result.get("error"));
                return response;
            }

            // Cache results for export/email
            List<Map<String, String>> results = (List<Map<String, String>>) result.get("results");
            List<String> columns = (List<String>) result.get("columns");
            if (results != null) lastResults = results;
            if (columns != null) lastColumns = columns;

            activityLogger.log(
                s(session, "username"), s(session, "displayName"),
                "AGILE_LOOKUP", "Items: " + result.getOrDefault("totalCount", 0) +
                " | Time: " + result.getOrDefault("queryTimeMs", 0) + "ms");

            quarantineService.release(qTicket);
            return result;

        } catch (Exception e) {
            response.put("error", "Agile lookup failed: " + e.getMessage());
            return response;
        }
    }

    @GetMapping("/export")
    public void export(HttpSession session, HttpServletResponse httpResponse) throws IOException {
        if (lastResults.isEmpty() || lastColumns.isEmpty()) return;

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "Agile-Lookup-" + date + ".xlsx";

        httpResponse.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        httpResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "AGILE_EXPORT", lastResults.size() + " rows exported");

        writeExcel(lastResults, lastColumns, httpResponse.getOutputStream());
    }

    @PostMapping("/email")
    public Map<String, Object> emailReport(HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");

        if (email == null || email.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "Not logged in.");
            return resp;
        }

        if (lastResults.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "No results to send. Run a lookup first.");
            return resp;
        }

        try {
            ByteArrayOutputStream excelOut = new ByteArrayOutputStream();
            writeExcel(lastResults, lastColumns, excelOut);

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = "Agile-Lookup-" + date + ".xlsx";

            emailService.sendBomReport(email, displayName, excelOut.toByteArray(),
                filename, "Agile Lookup (" + lastResults.size() + " items)", lastResults.size());

            activityLogger.log(
                (String) session.getAttribute("username"), displayName,
                "AGILE_EMAIL", lastResults.size() + " rows emailed");

            resp.put("success", true);
            resp.put("message", "Report sent to " + email);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "Failed: " + e.getMessage());
        }
        return resp;
    }

    @GetMapping("/max-items")
    public Map<String, Object> getMaxItems() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("maxItems", agileLookupService.getMaxItems());
        resp.put("serviceAvailable", agileLookupService.isServiceAvailable());
        return resp;
    }

    @GetMapping("/field-names")
    public List<Map<String, String>> getFieldNames() {
        return agileFieldNameService.getFieldNames();
    }

    @PostMapping("/upload-field-names")
    public Map<String, Object> uploadFieldNames(@RequestParam("file") MultipartFile file, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            response.put("success", false);
            response.put("message", "Admin access required.");
            return response;
        }
        try {
            int count = agileFieldNameService.importFromCsv(file.getInputStream());
            response.put("success", true);
            response.put("message", "Imported " + count + " field names.");
            response.put("count", count);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to import: " + e.getMessage());
        }
        return response;
    }

    @PostMapping("/manual-lookup")
    @SuppressWarnings("unchecked")
    public Map<String, Object> manualLookup(
            @RequestParam String items,
            @RequestParam String fields,
            HttpSession session) {

        Map<String, Object> response = new LinkedHashMap<>();

        // Split items and fields. Items accept comma / whitespace / semicolon —
        // matches the frontend normalizer (paste from Excel may arrive space- or newline-separated).
        String[] itemArray = items.split("[\\s,;]+");
        String[] fieldArray = fields.split(",");

        List<String> itemList = new ArrayList<>();
        for (String item : itemArray) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) itemList.add(trimmed);
        }

        List<String> fieldList = new ArrayList<>();
        for (String field : fieldArray) {
            String trimmed = field.trim();
            if (!trimmed.isEmpty()) fieldList.add(trimmed);
        }

        if (itemList.isEmpty() || fieldList.isEmpty()) {
            response.put("results", new ArrayList<>());
            response.put("columns", new ArrayList<>());
            response.put("message", "Items and fields are required.");
            return response;
        }

        // Check if Agile microservice is running
        if (!agileLookupService.isServiceAvailable()) {
            response.put("error", "The Agile Lookup service is not running. Please contact PLM Admin to start it.");
            return response;
        }

        try {
            // Build an in-memory Excel workbook to forward to the microservice
            byte[] excelBytes = buildLookupExcel(itemList, fieldList);
            Map<String, Object> result = agileLookupService.forwardLookup(excelBytes, "manual-lookup.xlsx");

            if (result.containsKey("error")) {
                return result;
            }

            // Cache results for export/email
            List<Map<String, String>> results = (List<Map<String, String>>) result.get("results");
            List<String> columns = (List<String>) result.get("columns");
            if (results != null) lastResults = results;
            if (columns != null) lastColumns = columns;

            activityLogger.log(
                s(session, "username"), s(session, "displayName"),
                "AGILE_MANUAL_LOOKUP", "Items: " + result.getOrDefault("totalCount", 0) +
                " | Fields: " + fieldList.size() +
                " | Time: " + result.getOrDefault("queryTimeMs", 0) + "ms");

            return result;

        } catch (Exception e) {
            response.put("error", "Agile lookup failed: " + e.getMessage());
            return response;
        }
    }

    /** Build a minimal Excel file with item numbers in column A and field names as headers in B onwards. */
    private byte[] buildLookupExcel(List<String> itemList, List<String> fieldList) throws IOException {
        // Streaming + inline strings (OOM-safety).
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 1000, false, false);
        try {
            SXSSFSheet sheet = workbook.createSheet("Lookup");
            // Header row: A1 = "Item Number", B1..N1 = field names
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("Item Number");
            for (int i = 0; i < fieldList.size(); i++) {
                headerRow.createCell(i + 1).setCellValue(fieldList.get(i));
            }
            // Data rows: item numbers in column A
            for (int r = 0; r < itemList.size(); r++) {
                Row row = sheet.createRow(r + 1);
                row.createCell(0).setCellValue(itemList.get(r));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private String s(HttpSession session, String key) {
        if (session == null) return "unknown";
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "unknown";
    }

    private void writeExcel(List<Map<String, String>> results, List<String> columns,
                             OutputStream out) throws IOException {
        // Streaming + inline strings (OOM-safety).
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 1000, false, false);
        try {
            SXSSFSheet sheet = workbook.createSheet("Agile Lookup");
            // Track all columns up-front so autoSizeColumn works on the
            // streaming sheet (it can only sample rows still in the window).
            for (int i = 0; i < columns.size(); i++) sheet.trackColumnForAutoSizing(i);

            XSSFCellStyle headerStyle = (XSSFCellStyle) workbook.createCellStyle();
            XSSFFont headerFont = (XSSFFont) workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(
                new byte[]{(byte) 0x2c, (byte) 0x3e, (byte) 0x50}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                String col = columns.get(i);
                cell.setCellValue(col.equals("_STATUS") ? "Status" : col);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (Map<String, String> record : results) {
                Row row = sheet.createRow(rowIdx++);
                for (int i = 0; i < columns.size(); i++) {
                    row.createCell(i).setCellValue(
                        record.getOrDefault(columns.get(i), ""));
                }
            }

            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) > 12800) sheet.setColumnWidth(i, 12800);
            }
            sheet.createFreezePane(0, 1);
            if (!results.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, columns.size() - 1));
            }

            workbook.write(out);
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }
}
