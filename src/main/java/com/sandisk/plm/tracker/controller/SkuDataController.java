package com.sandisk.plm.tracker.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.ExtensionPermissionService;
import com.sandisk.plm.tracker.service.ExternalSourceService;
import com.sandisk.plm.tracker.service.JobQueueService;
import com.sandisk.plm.tracker.service.LdapAuthService;
import com.sandisk.plm.tracker.service.SkuDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * REST endpoints for the All-SKUs JSON data cache.
 *
 * <pre>
 *   POST /api/sku-data/seed   – full extract (~15-30 s)
 *   POST /api/sku-data/delta  – incremental update (~1-2 s)
 *   GET  /api/sku-data/status – cache info (file size, record count, timestamps)
 * </pre>
 */
@RestController
@RequestMapping("/api/sku-data")
public class SkuDataController {

    private static final Logger LOG = Logger.getLogger(SkuDataController.class.getName());

    @Autowired
    private SkuDataService skuDataService;

    @Autowired
    private com.sandisk.plm.tracker.service.UploadQuarantineService quarantineService;

    @Autowired
    private ExternalSourceService externalSourceService;

    @Autowired
    private com.sandisk.plm.tracker.util.UploadColumnDetector columnDetector;

    @Autowired
    private com.sandisk.plm.tracker.service.SkuEnrichService skuEnrichService;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private ExtensionPermissionService extensionPermissionService;

    @Autowired
    private LdapAuthService ldapAuthService;

    @Autowired
    private JobQueueService jobQueueService;

    @Autowired
    private com.sandisk.plm.tracker.service.MemoryGuard memoryGuard;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Full seed: queries all SKU items and writes JSON from scratch.
     * Expect ~15-30 seconds for ~48K rows.
     */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            long t0 = System.currentTimeMillis();
            int count = skuDataService.seed();
            long elapsed = System.currentTimeMillis() - t0;

            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "SKU_SEED",
                count + " records loaded in " + (elapsed / 1000) + "s");

            result.put("status", "success");
            result.put("action", "seed");
            result.put("recordsWritten", count);
            result.put("elapsedMs", elapsed);
            result.put("elapsedSec", elapsed / 1000.0);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Seed failed", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Delta update: queries items released in the last N days, merges into JSON.
     * Expect ~1-2 seconds.
     */
    @PostMapping("/delta")
    public ResponseEntity<Map<String, Object>> delta(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            long t0 = System.currentTimeMillis();
            int count = skuDataService.delta();
            long elapsed = System.currentTimeMillis() - t0;

            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "SKU_DELTA",
                count + " records upserted in " + elapsed + "ms");

            result.put("status", "success");
            result.put("action", "delta");
            result.put("recordsUpserted", count);
            result.put("elapsedMs", elapsed);
            result.put("elapsedSec", elapsed / 1000.0);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Delta failed", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Status: returns info about the current JSON cache file.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(skuDataService.status());
    }

    /**
     * Search: look up SKU records by comma-separated item numbers.
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String items, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        // Dedup input items so that "ABC,ABC,DEF" doesn't produce duplicate
        // rows in the result table (PT-32). LinkedHashSet preserves the order
        // the user typed them in, which matches how /search-file already works
        // via parsed.distinctItems().
        LinkedHashSet<String> uniqueItems = new LinkedHashSet<>();
        for (String item : items.split("[\\s,;]+")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) uniqueItems.add(trimmed);
        }
        int inputCount = uniqueItems.size();
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String trimmed : uniqueItems) {
            Object record = skuDataService.getRecord(trimmed);
            if (record != null) {
                if (record instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rec = new LinkedHashMap<>((Map<String, Object>) record);
                    results.add(rec);
                }
            } else {
                notFound.add(trimmed);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SKU_LOOKUP",
            "Mode: manual | Items: " + inputCount + " | Found: " + results.size()
                + " | NotFound: " + notFound.size() + " | Time: " + elapsed + "ms");

        response.put("results", results);
        response.put("totalCount", results.size());
        response.put("notFound", notFound);
        response.put("queryTimeMs", elapsed);
        return response;
    }

    /**
     * Search by file upload: one item number per line.
     */
    @PostMapping("/search-file")
    public Map<String, Object> searchFile(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "itemColumn", required = false) Integer itemColumn,
                                          HttpSession session) throws IOException {
        Map<String, String> qParams = new LinkedHashMap<>();
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file,
                (String) session.getAttribute("username"),
                "/api/sku-data/search-file", qParams);

        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.util.FileItemParser.ParseResult parsed =
                com.sandisk.plm.tracker.util.FileItemParser.parseItemsWithDetection(file, columnDetector, override);
        List<String> items = parsed.distinctItems();

        Map<String, Object> response = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String item : items) {
            Object record = skuDataService.getRecord(item);
            if (record != null && record instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rec = new LinkedHashMap<>((Map<String, Object>) record);
                results.add(rec);
            } else {
                notFound.add(item);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SKU_LOOKUP",
            "Mode: file | File: " + (file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload")
                + " | Items: " + items.size()
                + " (rows: " + parsed.totalRows + ", unique: " + parsed.uniqueItems + ")"
                + " | Found: " + results.size()
                + " | NotFound: " + notFound.size()
                + " | Sheet: " + parsed.sourceSheet
                + " | Col: " + skuColumnLetter(parsed.sourceColumn) + " (" + parsed.sourceHeader + ")"
                + " via " + parsed.method
                + " | Time: " + elapsed + "ms");

        response.put("results", results);
        response.put("totalCount", results.size());
        response.put("notFound", notFound);
        response.put("queryTimeMs", elapsed);
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("index", parsed.sourceColumn);
        col.put("letter", skuColumnLetter(parsed.sourceColumn));
        col.put("header", parsed.sourceHeader);
        col.put("method", parsed.method);
        col.put("sheet", parsed.sourceSheet);
        response.put("itemColumn", col);
        response.put("inputRows", parsed.totalRows);
        response.put("uniqueItems", parsed.uniqueItems);
        quarantineService.release(qTicket);
        return response;
    }

    /** 0-indexed column → "A", "B", ..., "Z", "AA", ... */
    private static String skuColumnLetter(int idx) {
        StringBuilder sb = new StringBuilder();
        int n = idx;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = (n / 26) - 1;
        }
        return sb.toString();
    }

    /** Enrichment endpoint: streams back the input xlsx with the user's selected
     *  display fields appended right after the detected item-number column. */
    @PostMapping("/enrich")
    public void enrich(@RequestParam("file") MultipartFile file,
                       @RequestParam(value = "fields",     required = false) String fields,
                       @RequestParam(value = "itemColumn", required = false) Integer itemColumn,
                       HttpSession session,
                       javax.servlet.http.HttpServletResponse response) throws IOException {
        List<String> selectedFields = new ArrayList<>();
        if (fields != null && !fields.trim().isEmpty()) {
            for (String s : fields.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) selectedFields.add(t);
            }
        }
        if (selectedFields.isEmpty()) {
            response.sendError(400, "Select at least one display field before enriching.");
            return;
        }

        Map<String, String> qParams = new LinkedHashMap<>();
        qParams.put("fields", String.valueOf(fields));
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file,
                (String) session.getAttribute("username"),
                "/api/sku-data/enrich", qParams);

        java.time.LocalDate today = java.time.LocalDate.now();
        String date = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String origName = file.getOriginalFilename() == null ? "input.xlsx" : file.getOriginalFilename();
        String base = origName.replaceFirst("\\.[^.]+$", "");
        String filename = base + " (enriched " + date + ").xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.service.SkuEnrichService.EnrichResult er =
                skuEnrichService.enrich(file, selectedFields, override, response.getOutputStream());

        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SKU_ENRICH", "Rows: " + er.totalRows + " | Matched: " + er.matchedItems
                + " | Fields: " + selectedFields.size()
                + " | Col: " + skuColumnLetter(er.sourceColumn) + " (" + er.sourceHeader + ")"
                + " via " + er.detectionMethod
                + " | Duration: " + er.durationMs + "ms");

        quarantineService.release(qTicket);
    }

    /**
     * Returns the list of all available field names from the SKU data.
     */
    @GetMapping("/fields")
    public List<String> getAvailableFields() {
        return skuDataService.getAvailableFields();
    }

    // -----------------------------------------------------------------------
    //  External Sources
    // -----------------------------------------------------------------------

    /**
     * Returns the list of configured external data sources.
     */
    @GetMapping("/external-sources")
    public List<Map<String, String>> getExternalSources() {
        return externalSourceService.getSourceList();
    }

    /**
     * Returns column headers for a given external source.
     */
    @GetMapping("/external-headers")
    public Map<String, Object> getExternalHeaders(@RequestParam String sourceId) {
        Map<String, Object> response = new LinkedHashMap<>();
        List<String> headers = externalSourceService.getSourceHeaders(sourceId);
        response.put("sourceId", sourceId);
        response.put("headers", headers);
        return response;
    }

    /**
     * Combined search: looks up items in Agile SKU data and one or more external
     * sources, merging results into a single response.
     *
     * <p>Accepts either:
     * <ul>
     *   <li>{@code sourceId} + {@code sourceColumns} (legacy single-source)</li>
     *   <li>{@code sourceColumnsMap} — JSON object mapping sourceId -> [columns]
     *       (new multi-source format)</li>
     * </ul>
     */
    @GetMapping("/combined-search")
    public Map<String, Object> combinedSearch(
            @RequestParam String items,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String sourceColumns,
            @RequestParam(required = false) String sourceColumnsMap,
            HttpSession session) {

        long start = System.currentTimeMillis();
        Map<String, Set<String>> sourceMap = parseSourceColumnsMap(sourceId, sourceColumns, sourceColumnsMap);

        String[] itemArray = items.split("[\\s,;]+");
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String item : itemArray) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            Map<String, Object> row = buildCombinedRowMulti(trimmed, sourceMap);
            if (!(boolean) row.get("foundInAgile") && !(boolean) row.get("foundInAnyExternal")) {
                notFound.add(trimmed);
            }
            results.add(row);
        }

        long elapsed = System.currentTimeMillis() - start;
        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SKU_LOOKUP",
            "Mode: manual+sources | Items: " + itemArray.length + " | Found: " + results.size()
                + " | NotFound: " + notFound.size() + " | Sources: " + sourceMap.keySet()
                + " | Time: " + elapsed + "ms");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("totalCount", results.size());
        response.put("notFound", notFound);
        response.put("sourceIds", new ArrayList<>(sourceMap.keySet()));
        response.put("queryTimeMs", elapsed);
        return response;
    }

    /**
     * Combined search from file upload: one item number per line. Supports
     * multiple external sources via {@code sourceColumnsMap} JSON.
     */
    @PostMapping("/combined-search-file")
    public Map<String, Object> combinedSearchFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String sourceColumns,
            @RequestParam(required = false) String sourceColumnsMap,
            @RequestParam(value = "itemColumn", required = false) Integer itemColumn,
            HttpSession session) throws IOException {

        long start = System.currentTimeMillis();
        Map<String, Set<String>> sourceMap = parseSourceColumnsMap(sourceId, sourceColumns, sourceColumnsMap);

        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.util.FileItemParser.ParseResult parsed =
                com.sandisk.plm.tracker.util.FileItemParser.parseItemsWithDetection(file, columnDetector, override);
        List<String> items = parsed.distinctItems();

        List<Map<String, Object>> results = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String item : items) {
            Map<String, Object> row = buildCombinedRowMulti(item, sourceMap);
            if (!(boolean) row.get("foundInAgile") && !(boolean) row.get("foundInAnyExternal")) {
                notFound.add(item);
            }
            results.add(row);
        }

        long elapsed = System.currentTimeMillis() - start;
        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SKU_LOOKUP",
            "Mode: file+sources | File: " + (file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload")
                + " | Items: " + items.size()
                + " (rows: " + parsed.totalRows + ", unique: " + parsed.uniqueItems + ")"
                + " | Found: " + results.size()
                + " | NotFound: " + notFound.size()
                + " | Sources: " + sourceMap.keySet()
                + " | Sheet: " + parsed.sourceSheet
                + " | Col: " + skuColumnLetter(parsed.sourceColumn) + " (" + parsed.sourceHeader + ")"
                + " via " + parsed.method
                + " | Time: " + elapsed + "ms");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("totalCount", results.size());
        response.put("notFound", notFound);
        response.put("sourceIds", new ArrayList<>(sourceMap.keySet()));
        response.put("queryTimeMs", System.currentTimeMillis() - start);
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("index", parsed.sourceColumn);
        col.put("letter", skuColumnLetter(parsed.sourceColumn));
        col.put("header", parsed.sourceHeader);
        col.put("method", parsed.method);
        col.put("sheet", parsed.sourceSheet);
        response.put("itemColumn", col);
        response.put("inputRows", parsed.totalRows);
        response.put("uniqueItems", parsed.uniqueItems);
        return response;
    }

    /**
     * Admin or extension contributor: manually trigger rebuild of an external source cache.
     * Used by the per-source "Rebuild" button on the SKU Lookup tab. Async — returns a job id.
     */
    @PostMapping("/external-rebuild")
    public Map<String, Object> rebuildExternalSource(@RequestParam String sourceId, HttpSession session) {
        if (!canManageExtensions(session)) return forbiddenManage();

        String submittedBy = (String) session.getAttribute("displayName");
        JobQueueService.JobInfo job = jobQueueService.submitRebuild(sourceId, sourceId, submittedBy);

        activityLogger.log(
            (String) session.getAttribute("username"),
            submittedBy,
            "EXT_SOURCE_GENERATE",
            "Triggered rebuild of '" + sourceId + "' from SKU Lookup — job " + job.id + " (" + job.status + ")");

        Map<String, Object> result = new LinkedHashMap<>();
        boolean queued = "queued".equals(job.status) || "running".equals(job.status);
        // Match the existing response shape callers expect (status field).
        result.put("status", queued ? "queued" : "error");
        result.put("jobId", job.id);
        result.put("message", job.message != null ? job.message
                : "Rebuild " + job.status + (job.queuePosition != null ? " (position " + job.queuePosition + ")" : ""));
        return result;
    }

    // -----------------------------------------------------------------------
    //  Excel Export for SKU results
    // -----------------------------------------------------------------------

    /**
     * Receives the SKU results as JSON and returns an Excel file with:
     * Sheet 1 "SKU Data": all rows, highlighting merged values and blank cells
     * Sheet 2 "Column Sources": maps each column to its data source
     */
    @PostMapping("/export-excel")
    public void exportExcel(@RequestBody Map<String, Object> payload,
                            HttpSession session,
                            javax.servlet.http.HttpServletResponse response) throws IOException {

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("rows");
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) payload.get("columns");
        @SuppressWarnings("unchecked")
        List<String> displayNames = (List<String>) payload.get("displayNames");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> columnSources = (List<Map<String, String>>) payload.get("columnSources");

        if (rows == null) rows = new ArrayList<>();
        if (columns == null || columns.isEmpty()) return;
        if (displayNames == null) displayNames = columns;

        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=SKU-Lookup-" + date + ".xlsx");

        // Inline strings (no shared-strings table) — avoids the O(N²) SST
        // blow-up that OOMed prod on the Apr 22 SKU export and the May 6
        // scheduled-changes export.
        org.apache.poi.xssf.streaming.SXSSFWorkbook wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(null, 1000, false, false);
        try {
            // --- Styles ---
            org.apache.poi.ss.usermodel.CellStyle headerStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_TEAL.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            // Highlight: merged values (comma-separated → light yellow)
            org.apache.poi.ss.usermodel.CellStyle mergedStyle = wb.createCellStyle();
            mergedStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            mergedStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            // Highlight: blank cells → light gray
            org.apache.poi.ss.usermodel.CellStyle blankStyle = wb.createCellStyle();
            blankStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            blankStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            // --- Sheet 1: SKU Data ---
            org.apache.poi.xssf.streaming.SXSSFSheet sheet = wb.createSheet("SKU Data");
            org.apache.poi.ss.usermodel.Row hdrRow = sheet.createRow(0);
            for (int i = 0; i < displayNames.size(); i++) {
                org.apache.poi.ss.usermodel.Cell cell = hdrRow.createCell(i);
                cell.setCellValue(displayNames.get(i));
                cell.setCellStyle(headerStyle);
            }

            for (int r = 0; r < rows.size(); r++) {
                Map<String, Object> rec = rows.get(r);
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                for (int c = 0; c < columns.size(); c++) {
                    Object valObj = rec.get(columns.get(c));
                    String val = valObj != null ? valObj.toString() : "";
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                    cell.setCellValue(val);

                    String colKey = columns.get(c);
                    boolean isExternal = colKey.startsWith("ext:");
                    if (val.isEmpty()) {
                        cell.setCellStyle(blankStyle);
                    } else if (isExternal && val.contains(", ")) {
                        cell.setCellStyle(mergedStyle);
                    }
                }
            }

            // Auto-filter & freeze
            sheet.createFreezePane(0, 1);
            if (!rows.isEmpty()) {
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, columns.size() - 1));
            }
            for (int i = 0; i < columns.size(); i++) {
                sheet.setColumnWidth(i, Math.min(8000, 3000 + displayNames.get(i).length() * 300));
            }

            // --- Sheet 2: Column Sources ---
            if (columnSources != null && !columnSources.isEmpty()) {
                org.apache.poi.xssf.streaming.SXSSFSheet srcSheet = wb.createSheet("Column Sources");
                String[] srcHeaders = {"Column Name", "Source"};
                org.apache.poi.ss.usermodel.Row srcHdrRow = srcSheet.createRow(0);
                for (int i = 0; i < srcHeaders.length; i++) {
                    org.apache.poi.ss.usermodel.Cell cell = srcHdrRow.createCell(i);
                    cell.setCellValue(srcHeaders[i]);
                    cell.setCellStyle(headerStyle);
                }
                for (int i = 0; i < columnSources.size(); i++) {
                    Map<String, String> cs = columnSources.get(i);
                    org.apache.poi.ss.usermodel.Row srcRow = srcSheet.createRow(i + 1);
                    srcRow.createCell(0).setCellValue(cs.getOrDefault("column", ""));
                    srcRow.createCell(1).setCellValue(cs.getOrDefault("source", ""));
                }
                srcSheet.setColumnWidth(0, 8000);
                srcSheet.setColumnWidth(1, 8000);
            }

            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "SKU_EXPORT", "Excel export | Rows: " + rows.size() + " | Cols: " + columns.size());

            wb.write(response.getOutputStream());
        } finally {
            wb.close();
            wb.dispose();
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers for combined search
    // -----------------------------------------------------------------------

    /**
     * Build a result row that merges Agile data plus N external sources.
     * The row shape is:
     * <pre>
     * {
     *   number: "...",
     *   agile: {...},
     *   foundInAgile: true,
     *   externals: { sourceId1: {col:val}, sourceId2: {col:val} },
     *   foundInExternal: { sourceId1: true, sourceId2: false },
     *   foundInAnyExternal: true
     * }
     * </pre>
     */
    private Map<String, Object> buildCombinedRowMulti(String itemNumber, Map<String, Set<String>> sourceColumnsMap) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("number", itemNumber);

        // Agile lookup
        Object agileRec = skuDataService.getRecord(itemNumber);
        boolean foundInAgile = (agileRec != null && agileRec instanceof Map);
        row.put("agile", foundInAgile ? agileRec : Collections.emptyMap());
        row.put("foundInAgile", foundInAgile);

        // External sources lookup — one entry per source
        Map<String, Map<String, String>> externals = new LinkedHashMap<>();
        Map<String, Boolean> foundMap = new LinkedHashMap<>();
        boolean anyFound = false;

        for (Map.Entry<String, Set<String>> e : sourceColumnsMap.entrySet()) {
            String sourceId = e.getKey();
            Set<String> extCols = e.getValue();

            Map<String, String> extRec = externalSourceService.getRecord(sourceId, itemNumber);
            boolean found = (extRec != null);
            foundMap.put(sourceId, found);
            if (found) anyFound = true;

            if (found && extCols != null && !extCols.isEmpty()) {
                Map<String, String> filtered = new LinkedHashMap<>();
                for (String col : extCols) {
                    if (extRec.containsKey(col)) {
                        filtered.put(col, extRec.get(col));
                    }
                }
                externals.put(sourceId, filtered);
            } else {
                externals.put(sourceId, found ? extRec : Collections.emptyMap());
            }
        }

        row.put("externals", externals);
        row.put("foundInExternal", foundMap);
        row.put("foundInAnyExternal", anyFound);
        return row;
    }

    /**
     * Resolve either legacy (sourceId + sourceColumns) or new (sourceColumnsMap JSON)
     * into a canonical ordered map: sourceId -> requested columns (empty set = all columns).
     */
    private Map<String, Set<String>> parseSourceColumnsMap(String sourceId, String sourceColumns, String sourceColumnsMap) {
        Map<String, Set<String>> result = new LinkedHashMap<>();

        // Prefer new multi-source format if present
        if (sourceColumnsMap != null && !sourceColumnsMap.trim().isEmpty()) {
            try {
                Map<String, List<String>> raw = mapper.readValue(
                        sourceColumnsMap, new TypeReference<Map<String, List<String>>>() {});
                for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                    Set<String> cols = new LinkedHashSet<>();
                    if (e.getValue() != null) {
                        for (String c : e.getValue()) {
                            if (c != null && !c.trim().isEmpty()) cols.add(c.trim());
                        }
                    }
                    result.put(e.getKey(), cols);
                }
                return result;
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Failed to parse sourceColumnsMap JSON, falling back", ex);
            }
        }

        // Legacy single-source fallback
        if (sourceId != null && !sourceId.trim().isEmpty()) {
            Set<String> cols = new LinkedHashSet<>();
            if (sourceColumns != null && !sourceColumns.trim().isEmpty()) {
                for (String c : sourceColumns.split(",")) {
                    String t = c.trim();
                    if (!t.isEmpty()) cols.add(t);
                }
            }
            result.put(sourceId.trim(), cols);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    //  Extensions — Admin CRUD for external sources
    // -----------------------------------------------------------------------

    @GetMapping("/external-sources/config")
    public Object getExternalConfig(HttpSession session) {
        if (!canManageExtensions(session)) return forbiddenManage();
        return externalSourceService.getSourceList();
    }

    @PostMapping("/external-sources/add")
    public Map<String, Object> addExternalSource(@RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!canManageExtensions(session)) return forbiddenManage();

        String name = body.get("name");
        String file = body.get("file");
        String keyColumn = body.get("keyColumn");
        String scheduleHour = body.get("scheduleHour");

        if (name == null || name.isEmpty() || file == null || file.isEmpty() || keyColumn == null || keyColumn.isEmpty()) {
            response.put("success", false);
            response.put("message", "Name, file path, and key column are required.");
            return response;
        }

        try {
            String addedBy = (String) session.getAttribute("displayName");
            String addedByUsername = (String) session.getAttribute("username");
            externalSourceService.addSource(name, file, keyColumn, scheduleHour, addedBy, addedByUsername);
            activityLogger.log(
                addedByUsername,
                addedBy,
                "EXT_SOURCE_ADD",
                "Added external source: " + name + " (file: " + file + ", key: " + keyColumn + ")");
            response.put("success", true);
            response.put("message", "Source added: " + name);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed: " + e.getMessage());
        }
        return response;
    }

    /**
     * Upload an Excel file as a new external source. Returns IMMEDIATELY with a
     * job id; the actual POI parse runs on the JobQueueService worker thread so
     * other requests aren't blocked. Frontend polls {@code /jobs/{id}} for status.
     *
     * <p>Two backstops protect the JVM from one bad upload taking down the site:
     * <ul>
     *   <li>{@link com.sandisk.plm.tracker.service.MemoryGuard} refuses the upload if heap is already &gt;80% full.</li>
     *   <li>The job queue rejects new submissions when more than {@link JobQueueService#MAX_QUEUED} are pending.</li>
     * </ul>
     */
    /**
     * Reads just the header row from an uploaded Excel/CSV file and returns the column names.
     * Used by the frontend to populate the Key Column dropdown.
     */
    @PostMapping("/external-sources/headers")
    public Map<String, Object> readHeaders(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Map<String, Object> hp = heapPressureRefusalOrNull();
        if (hp != null) return hp;
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            String originalName = file.getOriginalFilename();
            if (originalName == null) originalName = "upload.xlsx";
            // Save to a temp file
            java.io.File tmp = java.io.File.createTempFile("hdr-", "-" + originalName);
            try {
                try (java.io.InputStream in = file.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                List<String> headers = new ArrayList<>();
                String lowerName = originalName.toLowerCase();
                if (lowerName.endsWith(".xlsx")) {
                    // Streaming read of just the first row. The previous
                    // implementation (`new XSSFWorkbook(pkg)`) inflated the
                    // ENTIRE workbook into memory — for a 60 MB .xlsx that
                    // can hang or OOM the JVM. We use POI's XSSFReader + SAX
                    // SheetContentsHandler and bail out after row 0.
                    headers.addAll(streamReadXlsxHeaders(tmp));
                } else {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(tmp);
                         org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis)) {
                        org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
                        org.apache.poi.ss.usermodel.Row row = sheet.getRow(0);
                        if (row != null) {
                            org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter();
                            for (int c = 0; c < row.getLastCellNum(); c++) {
                                org.apache.poi.ss.usermodel.Cell cell = row.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                                String h = fmt.formatCellValue(cell).trim();
                                if (!h.isEmpty()) headers.add(h);
                            }
                        }
                    }
                }
                response.put("success", true);
                response.put("headers", headers);
            } finally {
                tmp.delete();
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to read headers: " + e.getMessage());
        }
        return response;
    }

    /**
     * Stream-read the first row of an .xlsx file using POI's SAX-based reader.
     * Bails out immediately after row 0 so workbook size doesn't matter — for a
     * 60 MB .xlsx this returns in &lt;1 s with bounded memory, vs. hanging or
     * OOMing on the legacy {@code new XSSFWorkbook(pkg)} full-inflation path.
     */
    private static java.util.List<String> streamReadXlsxHeaders(java.io.File xlsxFile) throws Exception {
        final java.util.List<String> headers = new java.util.ArrayList<>();
        try (org.apache.poi.openxml4j.opc.OPCPackage pkg =
                 org.apache.poi.openxml4j.opc.OPCPackage.open(xlsxFile, org.apache.poi.openxml4j.opc.PackageAccess.READ)) {
            org.apache.poi.xssf.eventusermodel.XSSFReader reader =
                new org.apache.poi.xssf.eventusermodel.XSSFReader(pkg);
            org.apache.poi.xssf.model.StylesTable styles = reader.getStylesTable();
            org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable strings =
                new org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable(pkg);
            java.util.Iterator<java.io.InputStream> sheets = reader.getSheetsData();
            if (!sheets.hasNext()) return headers;
            try (java.io.InputStream sheet = sheets.next()) {
                org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler h =
                    new org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler() {
                        public void startRow(int rowNum) {}
                        public void endRow(int rowNum) {
                            if (rowNum == 0) throw new HeadersReadDoneSignal();
                        }
                        public void cell(String cellRef, String value, org.apache.poi.xssf.usermodel.XSSFComment comment) {
                            if (value != null) {
                                String trimmed = value.trim();
                                if (!trimmed.isEmpty()) headers.add(trimmed);
                            }
                        }
                        public void headerFooter(String text, boolean isHeader, String tagName) {}
                    };
                org.xml.sax.ContentHandler ch = new org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler(
                    styles, strings, h, new org.apache.poi.ss.usermodel.DataFormatter(), false);
                org.xml.sax.XMLReader sax = org.apache.poi.util.XMLHelper.newXMLReader();
                sax.setContentHandler(ch);
                try {
                    sax.parse(new org.xml.sax.InputSource(sheet));
                } catch (HeadersReadDoneSignal stop) {
                    // Expected: bailed out after row 0.
                }
            }
        }
        return headers;
    }

    /** Sentinel used to short-circuit the SAX parser after the first row. */
    private static final class HeadersReadDoneSignal extends RuntimeException {
        HeadersReadDoneSignal() { super(null, null, false, false); }
    }

    @PostMapping("/external-sources/upload")
    public Map<String, Object> uploadExternalSource(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam String name,
            @RequestParam String keyColumn,
            @RequestParam(required = false) String scheduleHour,
            HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!canManageExtensions(session)) return forbiddenManage();
        Map<String, Object> hp = heapPressureRefusalOrNull();
        if (hp != null) return hp;

        if (name.isEmpty() || keyColumn.isEmpty()) {
            response.put("success", false);
            response.put("message", "Name and key column are required.");
            return response;
        }

        try {
            // Save uploaded file to ./data/ (cheap — bounded by multipart max-file-size)
            String originalName = file.getOriginalFilename();
            if (originalName == null) originalName = "upload.xlsx";
            java.io.File destFile = new java.io.File("./data/" + originalName);
            destFile.getParentFile().mkdirs();
            try (java.io.InputStream in = file.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }

            String filePath = "./data/" + originalName;

            // Validate key column exists in the file headers (case-insensitive)
            String[] validation = externalSourceService.validateKeyColumn(filePath, keyColumn);
            if (validation[0] == null) {
                response.put("success", false);
                response.put("message", validation[1]);
                return response;
            }
            // Use the actual header name from the file (preserves original casing)
            String actualKeyColumn = validation[0];

            String addedBy = (String) session.getAttribute("displayName");
            String addedByUsername = (String) session.getAttribute("username");
            externalSourceService.addSource(name, filePath, actualKeyColumn, scheduleHour, addedBy, addedByUsername);

            // Submit the parse to the bounded queue — does not block the request thread.
            String sourceId = name.toLowerCase().replaceAll("[^a-z0-9]", "-").replaceAll("-+", "-");
            JobQueueService.JobInfo job = jobQueueService.submitRebuild(sourceId, name, addedBy);

            activityLogger.log(
                (String) session.getAttribute("username"),
                addedBy,
                "EXT_SOURCE_UPLOAD",
                "Uploaded external source: " + name + " (" + originalName + ", key: " + actualKeyColumn
                    + ", job: " + job.id + ", status: " + job.status + ")");

            // Even when the rebuild is rejected (busy / queue full), the source itself is
            // registered and the file is on disk — the user can click Generate later.
            boolean queued = "queued".equals(job.status) || "running".equals(job.status);
            response.put("success", queued);
            response.put("jobId", job.id);
            response.put("status", job.status);
            response.put("message", queued
                    ? "Source added: " + name + ". Cache build is " + job.status
                        + (job.queuePosition != null ? " (position " + job.queuePosition + ")" : "")
                        + " — this dialog will update when it finishes."
                    : (job.message != null ? job.message : "Cache build was not started."));
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("SkuDataController").log(java.util.logging.Level.SEVERE, "External source upload failed", e);
            response.put("success", false);
            response.put("message", "Failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName() + " - check server log"));
        }
        return response;
    }

    @DeleteMapping("/external-sources/{id}")
    public Map<String, Object> removeExternalSource(@PathVariable String id, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        boolean admin = isAdmin(session);
        boolean contributor = !admin && extensionPermissionService.isContributor(username);

        if (!admin && !contributor) {
            response.put("success", false);
            response.put("message", "Admin or extension contributor access required.");
            return response;
        }

        // Contributor delete: only allowed for sources they own. Legacy sources
        // (addedByUsername == null) fall through to admin-only — protects pre-existing
        // entries that have no recorded owner.
        if (contributor) {
            ExternalSourceService.SourceDef def = externalSourceService.findSource(id);
            if (def == null) {
                response.put("success", false);
                response.put("message", "Source not found: " + id);
                return response;
            }
            String owner = def.addedByUsername;
            if (owner == null || !owner.equalsIgnoreCase(username)) {
                response.put("success", false);
                response.put("message", "You can only delete extensions you added. This one is owned by "
                    + (def.addedBy != null ? def.addedBy : "an admin")
                    + " — ask them or a PLM admin to remove it.");
                return response;
            }
        }

        try {
            externalSourceService.removeSource(id);
            activityLogger.log(
                username,
                (String) session.getAttribute("displayName"),
                "EXT_SOURCE_REMOVE",
                "Removed external source: " + id + (contributor ? " (contributor self-delete)" : ""));
            response.put("success", true);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return response;
    }

    /**
     * Replace the underlying file for an existing source. Original addedBy is
     * preserved; lastRefreshedBy is recorded. Optionally accepts a new key
     * column. After saving the file, queues a rebuild via the same JobQueue
     * path the upload endpoint uses.
     *
     * Auth: admin OR (contributor AND owns the row). Same gate as DELETE.
     */
    @PostMapping("/external-sources/{id}/replace-file")
    public Map<String, Object> replaceExternalSourceFile(
            @PathVariable String id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String keyColumn,
            HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        boolean admin = isAdmin(session);
        boolean contributor = !admin && extensionPermissionService.isContributor(username);
        if (!admin && !contributor) return forbiddenManage();
        Map<String, Object> hp = heapPressureRefusalOrNull();
        if (hp != null) return hp;

        ExternalSourceService.SourceDef def = externalSourceService.findSource(id);
        if (def == null) {
            response.put("success", false);
            response.put("message", "Source not found: " + id);
            return response;
        }

        if (contributor) {
            String owner = def.addedByUsername;
            if (owner == null || !owner.equalsIgnoreCase(username)) {
                response.put("success", false);
                response.put("message", "You can only replace files for extensions you added. This one is owned by "
                    + (def.addedBy != null ? def.addedBy : "an admin")
                    + " — ask them or a PLM admin to refresh it.");
                return response;
            }
        }

        try {
            // Save the uploaded file with a timestamp suffix so the previous file
            // stays on disk in case the rebuild fails. We don't garbage-collect
            // older files automatically — admin can clean up ./data/ manually.
            String originalName = file.getOriginalFilename();
            if (originalName == null) originalName = "upload.xlsx";
            String stem = originalName.replaceFirst("\\.[^.]+$", "");
            String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : "";
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            String safeStem = stem.replaceAll("[^A-Za-z0-9._-]", "_");
            String filePath = "./data/" + safeStem + "-" + stamp + ext;
            java.io.File destFile = new java.io.File(filePath);
            destFile.getParentFile().mkdirs();
            try (java.io.InputStream in = file.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }

            // Validate the chosen key column exists in the new file (case-insensitive).
            // If keyColumn isn't supplied, fall back to the existing one.
            String effectiveKeyColumn = (keyColumn != null && !keyColumn.trim().isEmpty())
                    ? keyColumn.trim() : def.keyColumn;
            String[] validation = externalSourceService.validateKeyColumn(filePath, effectiveKeyColumn);
            if (validation[0] == null) {
                // Don't update the source if the new file isn't compatible — the
                // previous file is still in def.file and continues to serve.
                response.put("success", false);
                response.put("message", validation[1]);
                return response;
            }
            String actualKeyColumn = validation[0];

            String refreshedBy = (String) session.getAttribute("displayName");
            externalSourceService.replaceFile(id, filePath, actualKeyColumn, refreshedBy, username);

            JobQueueService.JobInfo job = jobQueueService.submitRebuild(id, def.name, refreshedBy);

            activityLogger.log(
                username,
                refreshedBy,
                "EXT_SOURCE_REPLACE",
                "Replaced file for '" + id + "' → " + filePath
                    + (keyColumn != null && !keyColumn.equalsIgnoreCase(def.keyColumn) ? " (new keyColumn: " + actualKeyColumn + ")" : "")
                    + " (job " + job.id + ", " + job.status + ")");

            boolean queued = "queued".equals(job.status) || "running".equals(job.status);
            response.put("success", queued);
            response.put("jobId", job.id);
            response.put("status", job.status);
            response.put("message", queued
                ? "File replaced. Cache rebuild is " + job.status + "."
                : (job.message != null ? job.message : "File replaced but rebuild was not started."));
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("SkuDataController").log(java.util.logging.Level.SEVERE, "External source replace-file failed", e);
            response.put("success", false);
            response.put("message", "Failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
        return response;
    }

    /**
     * Manual "Generate" button on the Extensions tab. Async — submits to the queue
     * and returns immediately with a job id.
     */
    @PostMapping("/external-sources/{id}/generate")
    public Map<String, Object> generateSource(@PathVariable String id, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!canManageExtensions(session)) return forbiddenManage();

        String submittedBy = (String) session.getAttribute("displayName");
        JobQueueService.JobInfo job = jobQueueService.submitRebuild(id, id, submittedBy);

        activityLogger.log(
            (String) session.getAttribute("username"),
            submittedBy,
            "EXT_SOURCE_GENERATE",
            "Triggered rebuild of '" + id + "' — job " + job.id + " (" + job.status + ")");

        boolean queued = "queued".equals(job.status) || "running".equals(job.status);
        response.put("success", queued);
        response.put("jobId", job.id);
        response.put("status", job.status);
        response.put("message", queued
                ? "Rebuild " + job.status
                    + (job.queuePosition != null ? " (position " + job.queuePosition + ")" : "")
                    + " — poll /jobs/" + job.id + " for status."
                : (job.message != null ? job.message : "Rebuild was not started."));
        return response;
    }

    @PostMapping("/external-sources/{id}/schedule")
    public Map<String, Object> scheduleSource(@PathVariable String id, @RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) { response.put("success", false); response.put("message", "Admin access required."); return response; }

        String hour = body.get("hour");
        try {
            externalSourceService.setScheduleHour(id, hour);
            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "EXT_SOURCE_SCHEDULE",
                "Set schedule for " + id + ": daily at " + hour + ":00");
            response.put("success", true);
            response.put("message", "Scheduled daily at " + hour + ":00");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return response;
    }

    private boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
    }

    /** Admin OR explicit extension contributor — allowed for add/upload/generate, NOT remove/schedule. */
    private boolean canManageExtensions(HttpSession session) {
        if (isAdmin(session)) return true;
        String username = (String) session.getAttribute("username");
        return extensionPermissionService.isContributor(username);
    }

    private Map<String, Object> forbiddenManage() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("message", "You do not have permission to manage extensions. Ask a PLM admin to grant you contributor access.");
        return r;
    }

    /**
     * Return a 503 JSON response if heap is currently under pressure, else null.
     * Used as an early gate at the top of upload/replace/headers endpoints — by
     * the time multipart parsing finishes, an already-pressured JVM might be
     * over the edge. JobQueueService has its own MemoryGuard check at submit
     * time; this is the upstream version that catches pressure before we even
     * accept the file.
     */
    private Map<String, Object> heapPressureRefusalOrNull() {
        if (!memoryGuard.isUnderPressure()) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("retryAfter", 60);
        r.put("message", "Server is busy: heap is " + memoryGuard.usedPercent()
            + "% full (" + memoryGuard.usedMb() + " / " + memoryGuard.maxMb()
            + " MB used, threshold " + Math.round(memoryGuard.getThreshold() * 100)
            + "%). Please try again in a minute.");
        return r;
    }

    // -----------------------------------------------------------------------
    //  Extension Permissions — Admin-only management of contributor list
    // -----------------------------------------------------------------------

    @GetMapping("/external-sources/permissions")
    public Map<String, Object> listPermissions(HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!isAdmin(session)) { resp.put("success", false); resp.put("message", "Admin access required."); return resp; }
        resp.put("success", true);
        resp.put("contributors", extensionPermissionService.getContributors());
        return resp;
    }

    @PutMapping("/external-sources/permissions/contributors/{username}")
    public Map<String, Object> grantContributor(@PathVariable String username, HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!isAdmin(session)) { resp.put("success", false); resp.put("message", "Admin access required."); return resp; }

        boolean added = extensionPermissionService.addContributor(username);
        if (added) {
            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "EXT_PERM_GRANT",
                "Granted Extensions contributor role to: " + username);
            resp.put("success", true);
            resp.put("message", "Granted contributor role to " + username);
        } else {
            resp.put("success", true);
            resp.put("message", username + " is already a contributor.");
        }
        resp.put("contributors", extensionPermissionService.getContributors());
        return resp;
    }

    @DeleteMapping("/external-sources/permissions/contributors/{username}")
    public Map<String, Object> revokeContributor(@PathVariable String username, HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!isAdmin(session)) { resp.put("success", false); resp.put("message", "Admin access required."); return resp; }

        boolean removed = extensionPermissionService.removeContributor(username);
        if (removed) {
            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "EXT_PERM_REVOKE",
                "Revoked Extensions contributor role from: " + username);
            resp.put("success", true);
            resp.put("message", "Revoked contributor role from " + username);
        } else {
            resp.put("success", true);
            resp.put("message", username + " was not a contributor.");
        }
        resp.put("contributors", extensionPermissionService.getContributors());
        return resp;
    }

    /**
     * Typeahead candidates for the "Add contributor" input. Returns AD users in the
     * access group ({@code IT-APP-Agile-admin}) who are NOT already admins, optionally
     * filtered by a query string. Admin-only.
     */
    @GetMapping("/external-sources/permissions/candidates")
    public Map<String, Object> listCandidates(@RequestParam(required = false, defaultValue = "") String q,
                                              HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!isAdmin(session)) { resp.put("success", false); resp.put("message", "Admin access required."); return resp; }

        String query = q.trim().toLowerCase();
        List<LdapAuthService.DirectoryUser> all = ldapAuthService.listAccessGroupCandidates();
        Set<String> alreadyContributors = new HashSet<>(extensionPermissionService.getContributors());

        List<Map<String, String>> matches = new ArrayList<>();
        for (LdapAuthService.DirectoryUser u : all) {
            if (alreadyContributors.contains(u.username.toLowerCase())) continue;
            if (!query.isEmpty()) {
                String hay = (u.username + " " + (u.displayName == null ? "" : u.displayName)).toLowerCase();
                if (!hay.contains(query)) continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("username", u.username);
            m.put("displayName", u.displayName == null ? u.username : u.displayName);
            m.put("email", u.email == null ? "" : u.email);
            matches.add(m);
            if (matches.size() >= 25) break; // cap typeahead suggestions
        }

        resp.put("success", true);
        resp.put("candidates", matches);
        return resp;
    }

    // -----------------------------------------------------------------------
    //  Job Queue — frontend polls these for async rebuild status
    // -----------------------------------------------------------------------

    /** Poll a single job by id. Returns 404 if unknown. */
    @GetMapping("/external-sources/jobs/{jobId}")
    public ResponseEntity<JobQueueService.JobInfo> getJob(@PathVariable String jobId, HttpSession session) {
        if (!canManageExtensions(session)) return ResponseEntity.status(403).build();
        JobQueueService.JobInfo job = jobQueueService.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job);
    }

    /** Snapshot of all recent jobs (newest first) — for the Extensions UI status banner. */
    @GetMapping("/external-sources/jobs")
    public Map<String, Object> listJobs(HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!canManageExtensions(session)) { resp.put("success", false); return resp; }
        resp.put("success", true);
        resp.put("queueDepth", jobQueueService.currentQueueDepth());
        resp.put("jobs", jobQueueService.recentJobs());
        return resp;
    }
}
