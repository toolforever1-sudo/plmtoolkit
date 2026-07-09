package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.BomDataService;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/parts")
public class PartExtractController {

    @Autowired
    private BomDataService bomDataService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private com.sandisk.plm.tracker.service.UploadQuarantineService quarantineService;

    @Autowired
    private com.sandisk.plm.tracker.util.UploadColumnDetector columnDetector;

    @Autowired
    private com.sandisk.plm.tracker.service.PartsEnrichService partsEnrichService;

    @Autowired
    private com.sandisk.plm.tracker.util.HeapGuard heapGuard;

    /** Hard ceiling on enrich row count. Even if HeapGuard's size-based estimate
     *  passes, a wide-column 10K-row file pushes XSSF DOM + cell-shifting past
     *  any reasonable bound. Cap chosen to fit ≥ 99% of legitimate use cases. */
    private static final int ENRICH_MAX_ROWS = 10000;

    private static final java.util.logging.Logger pelog =
            java.util.logging.Logger.getLogger(PartExtractController.class.getName());

    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("running", bomDataService.isPartsSearchRunning());
        response.put("rowCount", bomDataService.getPartsRowCount());
        return response;
    }

    @GetMapping("/columns")
    public Map<String, Object> getColumns() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("columns", bomDataService.getPartExtractHeaders());
        response.put("dataAsOf", bomDataService.getDataAsOf());
        return response;
    }

    /** PT-85 (Jimmy, 2026-06-11): single shape for items/columns/date-range
     *  so we can route both query-string GETs (small lists) and JSON-body POSTs
     *  (lists too large for the URL — Jimmy's 404-part case blew Tomcat's 8KB
     *  header buffer and returned 400 before any controller ran). All three
     *  endpoints below have a POST variant that takes this body. */
    public static class PartsReq {
        public String items;
        public String columns;
        public String releaseDateFrom;
        public String releaseDateTo;
    }

    @GetMapping("/search")
    public Map<String, Object> searchGet(
            @RequestParam(required = false) String items,
            @RequestParam(required = false) String columns,
            @RequestParam(required = false) String releaseDateFrom,
            @RequestParam(required = false) String releaseDateTo,
            HttpSession session) {
        return doSearch(items, columns, releaseDateFrom, releaseDateTo, session);
    }

    @PostMapping("/search")
    public Map<String, Object> searchPost(@RequestBody PartsReq req, HttpSession session) {
        if (req == null) req = new PartsReq();
        return doSearch(req.items, req.columns, req.releaseDateFrom, req.releaseDateTo, session);
    }

    private Map<String, Object> doSearch(String items, String columns,
                                         String releaseDateFrom, String releaseDateTo,
                                         HttpSession session) {

        long start = System.currentTimeMillis();
        java.util.logging.Logger.getLogger("PartExtract").info(
            "[PART SEARCH REQUEST] items=" + items + " releaseDateFrom=" + releaseDateFrom +
            " releaseDateTo=" + releaseDateTo + " columns=" + columns);

        List<String> selectedCols = parseColumns(columns);

        List<Map<String, String>> results;
        if (items != null && !items.trim().isEmpty()) {
            results = bomDataService.searchParts(items, selectedCols, releaseDateFrom, releaseDateTo);
        } else {
            results = new ArrayList<>();
        }

        long elapsed = System.currentTimeMillis() - start;

        activityLogger.log(
            s(session, "username"), s(session, "displayName"),
            "PARTS_SEARCH", "Items: " + items + " | Results: " + results.size());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("totalCount", results.size());
        response.put("queryTimeMs", elapsed);
        response.put("columns", selectedCols);
        response.put("dataAsOf", bomDataService.getDataAsOf());
        response.put("truncated", bomDataService.isPartsTruncated());
        return response;
    }

    @PostMapping("/upload")
    public Map<String, Object> uploadAndSearch(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String columns,
            @RequestParam(required = false) String releaseDateFrom,
            @RequestParam(required = false) String releaseDateTo,
            @RequestParam(value = "itemColumn", required = false) Integer itemColumn,
            HttpSession session) throws IOException {

        Map<String, String> qParams = new LinkedHashMap<>();
        qParams.put("columns", String.valueOf(columns));
        qParams.put("releaseDateFrom", String.valueOf(releaseDateFrom));
        qParams.put("releaseDateTo", String.valueOf(releaseDateTo));
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file, s(session, "username"),
                "/api/parts/upload", qParams);

        String typeErr = com.sandisk.plm.tracker.util.FileItemParser.validateFileType(file.getOriginalFilename());
        if (typeErr != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", typeErr);
            err.put("results", new java.util.ArrayList<>());
            err.put("totalCount", 0);
            return err;
        }
        // Heap-guard ceiling rather than a hard 5MB byte cap — a 6MB file with the same
        // shape as the 5MB one is no more dangerous when the JVM has 4GB+ free.
        if (file.getSize() > 100 * 1024 * 1024) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "File too large (max 100MB). Please split into smaller files.");
            err.put("results", new java.util.ArrayList<>());
            err.put("totalCount", 0);
            return err;
        }

        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.util.FileItemParser.ParseResult parsed =
                com.sandisk.plm.tracker.util.FileItemParser.parseItemsWithDetection(file, columnDetector, override);
        // Dedup before joining — IN-list queries on duplicates just waste CPU.
        List<String> itemNumbers = parsed.distinctItems();

        if (itemNumbers.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "File is empty or contains no valid item numbers.");
            err.put("results", new java.util.ArrayList<>());
            err.put("totalCount", 0);
            return err;
        }

        String items = String.join(",", itemNumbers);

        try {
            long start = System.currentTimeMillis();
            List<String> selectedCols = parseColumns(columns);
            List<Map<String, String>> results = bomDataService.searchParts(items, selectedCols, releaseDateFrom, releaseDateTo);
            long elapsed = System.currentTimeMillis() - start;

            activityLogger.log(
                s(session, "username"), s(session, "displayName"),
                "PARTS_UPLOAD", "Items: " + itemNumbers.size()
                    + " (rows: " + parsed.totalRows + ", unique: " + parsed.uniqueItems + ")"
                    + " | Results: " + results.size()
                    + " | Sheet: " + parsed.sourceSheet
                    + " | Col: " + columnLetter(parsed.sourceColumn) + " (" + parsed.sourceHeader + ")"
                    + " via " + parsed.method);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", results);
            response.put("totalCount", results.size());
            response.put("queryTimeMs", elapsed);
            response.put("columns", selectedCols);
            response.put("items", items);
            response.put("dataAsOf", bomDataService.getDataAsOf());
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("index", parsed.sourceColumn);
            col.put("letter", columnLetter(parsed.sourceColumn));
            col.put("header", parsed.sourceHeader);
            col.put("method", parsed.method);
            col.put("sheet", parsed.sourceSheet);
            response.put("itemColumn", col);
            response.put("inputRows", parsed.totalRows);
            response.put("uniqueItems", parsed.uniqueItems);
            quarantineService.release(qTicket);
            return response;
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Query failed: " + e.getMessage());
            err.put("results", new java.util.ArrayList<>());
            err.put("totalCount", 0);
            err.put("items", items);
            return err;
        }
    }

    /** Enrich endpoint: streams back the input xlsx with the user's selected columns
     *  appended right after the detected item-number column. Mirrors the Change-History
     *  enrich pattern. */
    @PostMapping("/enrich")
    public void enrich(@RequestParam("file") MultipartFile file,
                       @RequestParam(required = false) String columns,
                       @RequestParam(required = false) String releaseDateFrom,
                       @RequestParam(required = false) String releaseDateTo,
                       @RequestParam(value = "itemColumn", required = false) Integer itemColumn,
                       HttpSession session,
                       HttpServletResponse response) throws IOException {
        List<String> selectedCols = parseColumns(columns);
        if (selectedCols.isEmpty()) {
            response.sendError(400, "Select at least one display field before enriching.");
            return;
        }

        // Server-side HeapGuard re-check. The smartEnrich JS already calls
        // /api/upload/probe which runs the same check, but a script or curl
        // caller can bypass it — so we re-verify here. Use response.reset()
        // + plain-text body because sendError(503, msg) strips the message
        // (Spring's default error handler returns JSON without it).
        com.sandisk.plm.tracker.util.HeapGuard.Verdict v =
                heapGuard.check(file.getSize(), com.sandisk.plm.tracker.util.HeapGuard.Op.ENRICH);
        if (!v.ok) {
            pelog.warning("[PARTS-ENRICH] HeapGuard rejected — " + v.message);
            response.reset();
            response.setStatus(503);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().write(v.message);
            return;
        }

        Map<String, String> qParams = new LinkedHashMap<>();
        qParams.put("columns", String.valueOf(columns));
        qParams.put("releaseDateFrom", String.valueOf(releaseDateFrom));
        qParams.put("releaseDateTo", String.valueOf(releaseDateTo));
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file, s(session, "username"),
                "/api/parts/enrich", qParams);

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String origName = file.getOriginalFilename() == null ? "input.xlsx" : file.getOriginalFilename();
        String base = origName.replaceFirst("\\.[^.]+$", "");
        String filename = base + " (enriched " + date + ").xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        // Heap-before snapshot — paired with the heap-after snapshot below so a
        // future incident is easier to diagnose than the previous PT-29/PT-30
        // pair where we only had "JVM disappeared" to go on.
        long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.service.PartsEnrichService.EnrichResult er;
        try {
            er = partsEnrichService.enrich(file, selectedCols, releaseDateFrom, releaseDateTo, override,
                                            response.getOutputStream(), ENRICH_MAX_ROWS);
        } catch (com.sandisk.plm.tracker.service.PartsEnrichService.RowCapExceededException tooBig) {
            // Headers were set above but the workbook never wrote — response
            // isn't committed yet, so a clean 413 + plain-text body works.
            pelog.warning("[PARTS-ENRICH] row cap exceeded: " + tooBig.getMessage());
            response.reset();
            response.setStatus(413);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().write(tooBig.getMessage());
            quarantineService.release(qTicket);
            return;
        }
        long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "PARTS_ENRICH", "Rows: " + er.totalRows + " | Matched: " + er.matchedItems
                + " | Cols: " + selectedCols.size()
                + " | Col: " + columnLetter(er.sourceColumn) + " (" + er.sourceHeader + ")"
                + " via " + er.detectionMethod
                + " | Duration: " + er.durationMs + "ms"
                + " | Heap: " + com.sandisk.plm.tracker.util.HeapGuard.humanBytes(heapBefore)
                + " → " + com.sandisk.plm.tracker.util.HeapGuard.humanBytes(heapAfter));

        quarantineService.release(qTicket);
    }

    /** 0-indexed column → "A", "B", ..., "Z", "AA", ... */
    private static String columnLetter(int idx) {
        StringBuilder sb = new StringBuilder();
        int n = idx;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = (n / 26) - 1;
        }
        return sb.toString();
    }

    @GetMapping("/export")
    public void exportGet(
            @RequestParam(required = false) String items,
            @RequestParam(required = false) String columns,
            @RequestParam(required = false) String releaseDateFrom,
            @RequestParam(required = false) String releaseDateTo,
            HttpSession session,
            HttpServletResponse response) throws IOException {
        doExport(items, columns, releaseDateFrom, releaseDateTo, session, response);
    }

    @PostMapping("/export")
    public void exportPost(@RequestBody PartsReq req, HttpSession session,
                           HttpServletResponse response) throws IOException {
        if (req == null) req = new PartsReq();
        doExport(req.items, req.columns, req.releaseDateFrom, req.releaseDateTo, session, response);
    }

    private void doExport(String items, String columns, String releaseDateFrom, String releaseDateTo,
                          HttpSession session, HttpServletResponse response) throws IOException {

        List<String> selectedCols = parseColumns(columns);
        List<Map<String, String>> results = bomDataService.searchParts(items, selectedCols, releaseDateFrom, releaseDateTo);

        // Add DESCRIPTION to column list for export
        List<String> exportCols = new ArrayList<>(selectedCols);
        if (!exportCols.contains("DESCRIPTION")) exportCols.add("DESCRIPTION");

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "Part-Extract-" + date + ".xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "PARTS_EXPORT", "Items: " + items + " | Rows: " + results.size());

        writeExcel(results, exportCols, response.getOutputStream());
    }

    @PostMapping("/email")
    public Map<String, Object> emailReport(@RequestBody PartsReq req, HttpSession session) {
        if (req == null) req = new PartsReq();
        String items = req.items;
        String columns = req.columns;
        String releaseDateFrom = req.releaseDateFrom;
        String releaseDateTo = req.releaseDateTo;

        Map<String, Object> resp = new LinkedHashMap<>();
        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");

        if (email == null || email.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "Not logged in.");
            return resp;
        }

        try {
            List<String> selectedCols = parseColumns(columns);
            List<Map<String, String>> results = bomDataService.searchParts(items, selectedCols, releaseDateFrom, releaseDateTo);

            List<String> exportCols = new ArrayList<>(selectedCols);
            if (!exportCols.contains("DESCRIPTION")) exportCols.add("DESCRIPTION");

            ByteArrayOutputStream excelOut = new ByteArrayOutputStream();
            writeExcel(results, exportCols, excelOut);

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = "Part-Extract-" + date + ".xlsx";

            emailService.sendBomReport(email, displayName, excelOut.toByteArray(),
                    filename, "Part Extract: " + (items != null ? items : "file upload"), results.size());

            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "PARTS_EMAIL", results.size() + " rows emailed");

            resp.put("success", true);
            resp.put("message", "Report sent to " + email + " (" + results.size() + " rows)");
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "Failed: " + e.getMessage());
        }
        return resp;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String s(HttpSession session, String key) {
        if (session == null) return "unknown";
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "unknown";
    }

    private List<String> parseColumns(String columns) {
        if (columns == null || columns.trim().isEmpty()) {
            return new ArrayList<>(Arrays.asList(
                    "PART_NUMBER", "REV", "STATUSCODE", "LIFECYCLE_PHASE",
                    "NEW_PART_CLASS", "PRODUCTLINE", "REV_RELEASE_DATE", "CREATE_DATE"));
        }
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static final Map<String, String> FRIENDLY_NAMES = new HashMap<>();
    static {
        FRIENDLY_NAMES.put("PART_NUMBER", "Part Number"); FRIENDLY_NAMES.put("REV", "Rev");
        FRIENDLY_NAMES.put("PM", "P/M"); FRIENDLY_NAMES.put("PRODUCTLINE", "Product Line");
        FRIENDLY_NAMES.put("HIST", "History"); FRIENDLY_NAMES.put("CIS", "CIS");
        FRIENDLY_NAMES.put("CREATE_USER", "Create User"); FRIENDLY_NAMES.put("EFF_DATE_CHANGE", "Eff Date Change");
        FRIENDLY_NAMES.put("FAMILYNAME", "Family Name"); FRIENDLY_NAMES.put("CAPACITY", "Capacity");
        FRIENDLY_NAMES.put("GROUPV", "Group"); FRIENDLY_NAMES.put("IMAGE", "Image");
        FRIENDLY_NAMES.put("MATL_COST", "Material Cost"); FRIENDLY_NAMES.put("MC_DEPTH", "MC Depth");
        FRIENDLY_NAMES.put("MC_HEIGHT", "MC Height"); FRIENDLY_NAMES.put("MC_QTY", "MC Qty");
        FRIENDLY_NAMES.put("MC_WEIGHT", "MC Weight"); FRIENDLY_NAMES.put("MC_WIDTH", "MC Width");
        FRIENDLY_NAMES.put("NEW_EFF_DATE", "New Eff Date"); FRIENDLY_NAMES.put("OLD_EFF_DATE", "Old Eff Date");
        FRIENDLY_NAMES.put("PRODUCTDIVISION", "Product Division"); FRIENDLY_NAMES.put("PROMOGROUP", "Promo Group");
        FRIENDLY_NAMES.put("ROLLSTATUS", "Roll Status"); FRIENDLY_NAMES.put("SIDENSITY", "SI Density");
        FRIENDLY_NAMES.put("SPDEPTH", "SP Depth"); FRIENDLY_NAMES.put("SPHEIGHT", "SP Height");
        FRIENDLY_NAMES.put("SPWEIGHT", "SP Weight"); FRIENDLY_NAMES.put("SPWIDTH", "SP Width");
        FRIENDLY_NAMES.put("STATUSCODE", "Status Code"); FRIENDLY_NAMES.put("STATUSROLLDATE", "Status Roll Date");
        FRIENDLY_NAMES.put("SUBCONTRACTORS", "Subcontractors"); FRIENDLY_NAMES.put("MATERIAL_GROUP", "Material Group");
        FRIENDLY_NAMES.put("UM", "Unit of Measure"); FRIENDLY_NAMES.put("WEIGHTUM", "Weight UM");
        FRIENDLY_NAMES.put("CUSTOMER_PN", "Customer P/N"); FRIENDLY_NAMES.put("GREEN_COMPLIANCE", "Green Compliance");
        FRIENDLY_NAMES.put("MATERIAL_TYPE", "Material Type"); FRIENDLY_NAMES.put("ALT_BOM_NUMBER", "Alt BOM Number");
        FRIENDLY_NAMES.put("ALT_BOM_PARENT", "Alt BOM Parent"); FRIENDLY_NAMES.put("CONTROLLER_TECHNOLOGY", "Controller Technology");
        FRIENDLY_NAMES.put("GROSS_DIE_WAFER", "Gross Die/Wafer"); FRIENDLY_NAMES.put("PRODUCT_TYPE", "Product Type");
        FRIENDLY_NAMES.put("MARKETING_PROGRAM", "Marketing Program"); FRIENDLY_NAMES.put("MEMORY_PIN_COUNT", "Memory Pin Count");
        FRIENDLY_NAMES.put("MEMORY_PACKAGE", "Memory Package"); FRIENDLY_NAMES.put("CONTROLLER_PIN_COUNT", "Controller Pin Count");
        FRIENDLY_NAMES.put("CONTROLLER_PACKAGE", "Controller Package"); FRIENDLY_NAMES.put("SIP_PIN_COUNT", "SIP Pin Count");
        FRIENDLY_NAMES.put("SIP_PACKAGE", "SIP Package"); FRIENDLY_NAMES.put("DESCRIPTION", "Description");
        FRIENDLY_NAMES.put("RELEASE_DATE", "Release Date"); FRIENDLY_NAMES.put("AS_SOLD", "As Sold");
        FRIENDLY_NAMES.put("MARKING_SPEC", "Marking Spec"); FRIENDLY_NAMES.put("EAN", "EAN");
        FRIENDLY_NAMES.put("CARTON_AUM", "Carton AUM"); FRIENDLY_NAMES.put("CARTON_QTY", "Carton Qty");
        FRIENDLY_NAMES.put("UPDATE_DATE", "Update Date"); FRIENDLY_NAMES.put("PKG_TYPE", "Package Type");
        FRIENDLY_NAMES.put("PART_CLASS", "Part Class"); FRIENDLY_NAMES.put("CATEGORY", "Category");
        FRIENDLY_NAMES.put("WIP", "WIP"); FRIENDLY_NAMES.put("CUSTOM_REV", "Custom Rev");
        FRIENDLY_NAMES.put("DLE_MODEL_NAME", "DLE Model Name"); FRIENDLY_NAMES.put("CUSTOMER_FIRMWARE_VERSION", "Customer FW Version");
        FRIENDLY_NAMES.put("MST_FIRMWARE_VERSION", "MST FW Version"); FRIENDLY_NAMES.put("ATA_FW_IDENTIFY", "ATA FW Identify");
        FRIENDLY_NAMES.put("USER_CAPACITY_LBA", "User Capacity LBA"); FRIENDLY_NAMES.put("OLD_MATERIAL_NUMBER", "Old Material Number");
        FRIENDLY_NAMES.put("MARKETING_PROGRAM_CATEGORY", "Marketing Program Category");
        FRIENDLY_NAMES.put("PRODUCT_CUSTOMER", "Product Customer"); FRIENDLY_NAMES.put("PROGRAM_NAME", "Program Name");
        FRIENDLY_NAMES.put("IDB_PROGRAM", "IDB Program"); FRIENDLY_NAMES.put("SOURCE_PART_NUMBER", "Source Part Number");
        FRIENDLY_NAMES.put("ACTUAL_BUILD_PLANT", "Actual Build Plant"); FRIENDLY_NAMES.put("REGULATORY_COMPLIANCE", "Regulatory Compliance");
        FRIENDLY_NAMES.put("BOM_TYPE", "BOM Type"); FRIENDLY_NAMES.put("GOLDEN_PART", "Golden Part");
        FRIENDLY_NAMES.put("KGD_TEST_FLOW", "KGD Test Flow"); FRIENDLY_NAMES.put("ECCN", "ECCN");
        FRIENDLY_NAMES.put("STORAGE_CONDITION", "Storage Condition"); FRIENDLY_NAMES.put("GLOBAL_OPN", "Global OPN");
        FRIENDLY_NAMES.put("MFG_ID", "MFG ID"); FRIENDLY_NAMES.put("NEW_PART_CLASS", "Item Type");
        FRIENDLY_NAMES.put("CLASSIFICATION_PDM", "Classification PDM"); FRIENDLY_NAMES.put("REV_RELEASE_DATE", "Rev Release Date");
        FRIENDLY_NAMES.put("CREATE_DATE", "Create Date"); FRIENDLY_NAMES.put("LIFECYCLE_PHASE", "Lifecycle Phase");
        FRIENDLY_NAMES.put("SINGLE_SOLE_SOURCE", "Single/Sole Source");
    }

    private String friendlyName(String col) {
        return FRIENDLY_NAMES.getOrDefault(col, col);
    }

    private static final Set<String> DATE_COLS = new HashSet<>(Arrays.asList(
            "REV_RELEASE_DATE", "CREATE_DATE", "RELEASE_DATE", "UPDATE_DATE"));

    private static final Map<String, String> MONTH_MAP = new HashMap<>();
    static {
        MONTH_MAP.put("JAN", "01"); MONTH_MAP.put("FEB", "02"); MONTH_MAP.put("MAR", "03");
        MONTH_MAP.put("APR", "04"); MONTH_MAP.put("MAY", "05"); MONTH_MAP.put("JUN", "06");
        MONTH_MAP.put("JUL", "07"); MONTH_MAP.put("AUG", "08"); MONTH_MAP.put("SEP", "09");
        MONTH_MAP.put("OCT", "10"); MONTH_MAP.put("NOV", "11"); MONTH_MAP.put("DEC", "12");
    }

    /** Normalize "13-APR-2026 08:29:06 AM" to "DD-MM-YYYY" (date only, no timestamp) for Excel export */
    private String normalizeDate(String val) {
        if (val == null || val.trim().isEmpty()) return "";
        val = val.trim();
        // Pattern: DD-MON-YYYY (with optional time portion)
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,2})-([A-Z]{3})-(\\d{4})(.*)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(val);
        if (m.matches()) {
            String dd = m.group(1).length() == 1 ? "0" + m.group(1) : m.group(1);
            String mm = MONTH_MAP.getOrDefault(m.group(2).toUpperCase(), m.group(2));
            return dd + "-" + mm + "-" + m.group(3);
        }
        // Pattern: MM-DD-YYYY (with optional time portion)
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("^(\\d{2})-(\\d{2})-(\\d{4})(.*)")
                .matcher(val);
        if (m2.matches()) {
            return m2.group(2) + "-" + m2.group(1) + "-" + m2.group(3);
        }
        return val;
    }

    private void writeExcel(List<Map<String, String>> results, List<String> columns,
                             OutputStream out) throws IOException {
        // Streaming + inline strings — bounded heap regardless of row count.
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 1000, false, false);
        try {
            SXSSFSheet sheet = workbook.createSheet("Part Extract");

            XSSFCellStyle headerStyle = (XSSFCellStyle) workbook.createCellStyle();
            XSSFFont headerFont = (XSSFFont) workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(
                    new byte[]{(byte) 0x2c, (byte) 0x3e, (byte) 0x50}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            XSSFCellStyle evenStyle = (XSSFCellStyle) workbook.createCellStyle();
            evenStyle.setFillForegroundColor(new XSSFColor(
                    new byte[]{(byte) 0xf8, (byte) 0xf9, (byte) 0xfa}, null));
            evenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Header row — use friendly names
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(friendlyName(columns.get(i)));
                cell.setCellStyle(headerStyle);
            }

            // Data rows
            int rowIdx = 1;
            for (Map<String, String> record : results) {
                Row row = sheet.createRow(rowIdx);
                boolean even = rowIdx % 2 == 0;
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = row.createCell(i);
                    String colName = columns.get(i);
                    String value = record.getOrDefault(colName, "");
                    if (DATE_COLS.contains(colName)) value = normalizeDate(value);
                    cell.setCellValue(value);
                    if (even) cell.setCellStyle(evenStyle);
                }
                rowIdx++;
            }

            // Fixed widths — autoSizeColumn is too slow for large datasets
            for (int i = 0; i < columns.size(); i++) {
                sheet.setColumnWidth(i, 5000);
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
