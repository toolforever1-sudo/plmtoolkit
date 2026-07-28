package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.ChangeHistoryService;
import com.sandisk.plm.tracker.service.EmailService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
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
@RequestMapping("/api/history")
public class ChangeHistoryController {

    @Autowired
    private ChangeHistoryService changeHistoryService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private com.sandisk.plm.tracker.util.UploadColumnDetector columnDetector;

    @Autowired
    private com.sandisk.plm.tracker.service.ChangeHistoryEnrichService enrichService;

    @Autowired
    private com.sandisk.plm.tracker.service.UploadQuarantineService quarantineService;

    private volatile List<Map<String, String>> lastResults = new ArrayList<>();
    private volatile String lastItems = "";

    /** Cap on rows we keep pinned in the {@link #lastResults} export/email cache. A single big
     *  search (Sophia's 797K-row upload) would otherwise stay resident in this singleton field
     *  for ALL users until the next search — that pinned baseline is why the heap sat at 85%
     *  between operations before the PT-124 OOM. Above the cap we don't cache; /export and
     *  /email re-run the query (their existing fallback path). */
    private static final int CACHE_MAX_ROWS = 50_000;

    /** Max rows serialized into the on-screen preview response. "Show all" on a 48K-item file
     *  returns ~797K rows; sending them all froze the browser (it renders one table row each).
     *  The preview is capped here — totalCount still reports the true size, and Export/Enrich
     *  (which stream) deliver the complete set. */
    private static final int PREVIEW_MAX_ROWS = 10_000;

    /** The slice of results to send to the browser preview (never more than PREVIEW_MAX_ROWS). */
    private static List<Map<String, String>> previewSlice(List<Map<String, String>> results) {
        return results.size() > PREVIEW_MAX_ROWS ? results.subList(0, PREVIEW_MAX_ROWS) : results;
    }

    private void cacheResults(List<Map<String, String>> results, String itemsKey) {
        if (results.size() <= CACHE_MAX_ROWS) {
            lastResults = results;
            lastItems = itemsKey;
        } else {
            // Too large to pin globally — drop the cache; export/email will re-query on demand.
            lastResults = new ArrayList<>();
            lastItems = "";
        }
    }

    private static final String[] HEADERS = {
        "Item Number", "Rev", "Prior Lifecycle", "Lifecycle Phase", "Release Date", "Incorp Date",
        "Effective Date", "Obsolete Date", "Change Number", "Change Type",
        "Change Description", "Change Status"
    };
    private static final String[] KEYS = {
        "itemNumber", "rev", "priorPhase", "lifecyclePhase", "releaseDate", "incorpDate",
        "effectiveDate", "obsoleteDate", "changeNumber", "changeType",
        "changeDescription", "changeStatus"
    };

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(value = "items", required = false) String items,
                                      @RequestParam(value = "lifecyclePhases",  required = false) String lifecyclePhases,
                                      @RequestParam(value = "changeTypes",      required = false) String changeTypes,
                                      @RequestParam(value = "releaseDateFrom",  required = false) String releaseDateFrom,
                                      @RequestParam(value = "releaseDateTo",    required = false) String releaseDateTo,
                                      @RequestParam(value = "partTypes",        required = false) String partTypes,
                                      @RequestParam(value = "entryMode",        required = false) String entryMode,
                                      @RequestParam(value = "releaseDateRelative", required = false) String releaseDateRelative,
                                      HttpSession session) {
        long start = System.currentTimeMillis();
        List<String> itemList = parseItems(items);
        ChangeHistoryService.HistoryFilters f = buildFilters(
                lifecyclePhases, changeTypes, releaseDateFrom, releaseDateTo,
                partTypes, entryMode, releaseDateRelative);

        List<Map<String, String>> results = changeHistoryService.getHistoryFiltered(itemList, f);
        cacheResults(results, items == null ? "" : items);

        long elapsed = System.currentTimeMillis() - start;

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "HISTORY_SEARCH", "Items: " + itemList.size() + " | Results: " + results.size()
                + summariseFilters(f));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", previewSlice(results));
        response.put("totalCount", results.size());
        response.put("previewLimited", results.size() > PREVIEW_MAX_ROWS);
        response.put("previewLimit", PREVIEW_MAX_ROWS);
        response.put("queryTimeMs", elapsed);
        response.put("items", items == null ? "" : items);
        response.put("filterSummary", describeFilters(f));
        // PT-66: caller sent no items — surface why the result is empty so the UI
        // can show a "set a release-date range" hint instead of a generic empty state.
        if (itemList.isEmpty() && (f.releaseDateFrom == null && f.releaseDateTo == null)) {
            response.put("noItemsHint", "Provide item numbers, or set a Release Date range to search across all items.");
        }
        return response;
    }

    @PostMapping("/upload")
    public Map<String, Object> uploadAndSearch(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "lifecyclePhases",     required = false) String lifecyclePhases,
            @RequestParam(value = "changeTypes",         required = false) String changeTypes,
            @RequestParam(value = "releaseDateFrom",     required = false) String releaseDateFrom,
            @RequestParam(value = "releaseDateTo",       required = false) String releaseDateTo,
            @RequestParam(value = "partTypes",           required = false) String partTypes,
            @RequestParam(value = "entryMode",           required = false) String entryMode,
            @RequestParam(value = "releaseDateRelative", required = false) String releaseDateRelative,
            @RequestParam(value = "itemColumn",          required = false) Integer itemColumn,
            HttpSession session) throws IOException {

        // Save a copy of the upload before doing any heavy work. If the JVM dies
        // mid-processing (OOM, kill, crash), the file stays on disk and gets emailed
        // to pdl-plm-admin on the next startup so we can investigate without bothering
        // the user. Released on successful completion.
        Map<String, String> qParams = new LinkedHashMap<>();
        qParams.put("lifecyclePhases", parseCsv(lifecyclePhases).toString());
        qParams.put("changeTypes",     parseCsv(changeTypes).toString());
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file, s(session, "username"),
                "/api/history/upload", qParams);

        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.util.FileItemParser.ParseResult parsed =
                com.sandisk.plm.tracker.util.FileItemParser.parseItemsWithDetection(file, columnDetector, override);
        // Dedup before querying — the IN-list query doesn't care about repeats and they
        // just waste CPU. Keep raw count for the chip ("47 unique items from 2,800 rows").
        List<String> itemList = parsed.distinctItems();
        ChangeHistoryService.HistoryFilters f = buildFilters(
                lifecyclePhases, changeTypes, releaseDateFrom, releaseDateTo,
                partTypes, entryMode, releaseDateRelative);

        long start = System.currentTimeMillis();
        String itemsKey = String.join(",", itemList);
        List<Map<String, String>> results = changeHistoryService.getHistoryFiltered(itemList, f);
        cacheResults(results, itemsKey);
        long elapsed = System.currentTimeMillis() - start;

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "HISTORY_UPLOAD", "Items: " + itemList.size() + " | Results: " + results.size()
                + " | Col: " + columnLetter(parsed.sourceColumn) + " (" + parsed.sourceHeader + ")"
                + " via " + parsed.method
                + summariseFilters(f));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", previewSlice(results));
        response.put("totalCount", results.size());
        response.put("previewLimited", results.size() > PREVIEW_MAX_ROWS);
        response.put("previewLimit", PREVIEW_MAX_ROWS);
        response.put("queryTimeMs", elapsed);
        response.put("items", itemsKey);
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("index", parsed.sourceColumn);
        col.put("letter", columnLetter(parsed.sourceColumn));
        col.put("header", parsed.sourceHeader);
        col.put("method", parsed.method);
        response.put("itemColumn", col);
        response.put("inputRows", parsed.totalRows);
        response.put("uniqueItems", parsed.uniqueItems);
        response.put("filterSummary", describeFilters(f));

        // Processing finished cleanly — drop the quarantined copy.
        quarantineService.release(qTicket);
        return response;
    }

    /** Enrichment endpoint: returns the input xlsx with 4 history columns inserted right after
     *  the detected item-number column. Filters apply same as /search and /upload. */
    @PostMapping(value = "/enrich")
    public void enrich(@RequestParam("file") MultipartFile file,
                       @RequestParam(value = "lifecyclePhases", required = false) String lifecyclePhases,
                       @RequestParam(value = "changeTypes",     required = false) String changeTypes,
                       @RequestParam(value = "itemColumn",      required = false) Integer itemColumn,
                       @RequestParam(value = "entryMode",       required = false) String entryMode,
                       HttpSession session,
                       HttpServletResponse response) throws IOException {
        List<String> phases = parseCsv(lifecyclePhases);
        List<String> types  = parseCsv(changeTypes);

        Map<String, String> qParams = new LinkedHashMap<>();
        qParams.put("lifecyclePhases", phases.toString());
        qParams.put("changeTypes",     types.toString());
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file, s(session, "username"),
                "/api/history/enrich", qParams);

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String origName = file.getOriginalFilename() == null ? "input.xlsx" : file.getOriginalFilename();
        String base = origName.replaceFirst("\\.[^.]+$", "");
        String filename = base + " (enriched " + date + ").xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        // PT-124: the service auto-selects the writeback path — full-fidelity XSSF DOM for files up
        // to ChangeHistoryEnrichService.DOM_MAX_ROWS, and a streaming SAX→SXSSF writer above that so
        // a very large upload (Sophia's 48,471-row SKU report) can't build a multi-GB DOM and OOM
        // the shared server. Memory stays flat for big files instead of scaling with rows×cols.
        // First/Last entry mode now actually affects enrich: FIRST → earliest matching change per
        // item (default), LAST → latest. "Show all"/absent falls back to earliest, since enrich
        // fills one value per item and can't inline every historical row.
        ChangeHistoryService.EntryMode mode = ChangeHistoryService.EntryMode.ALL;
        if (entryMode != null) {
            switch (entryMode.trim().toLowerCase()) {
                case "first": mode = ChangeHistoryService.EntryMode.FIRST; break;
                case "last":  mode = ChangeHistoryService.EntryMode.LAST;  break;
                default:      mode = ChangeHistoryService.EntryMode.ALL;
            }
        }
        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.service.ChangeHistoryEnrichService.EnrichResult er =
                enrichService.enrich(file, phases, types, override, mode, response.getOutputStream());

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "HISTORY_ENRICH", "Rows: " + er.totalRows + " | Matched: " + er.matchedItems
                + " | Col: " + columnLetter(er.sourceColumn) + " (" + er.sourceHeader + ")"
                + " via " + er.detectionMethod
                + " | Path: " + (er.streamed ? "streaming" : "dom")
                + " | Duration: " + er.durationMs + "ms"
                + (phases.isEmpty() ? "" : " | Phases: " + phases)
                + (types.isEmpty()  ? "" : " | Types: "  + types));

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

    private static List<String> parseCsv(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Build a HistoryFilters from request params. Both PT-63 (entryMode) and PT-64
     *  (date range / part type / relative date) flow through here. */
    private static ChangeHistoryService.HistoryFilters buildFilters(
            String lifecyclePhases, String changeTypes,
            String releaseDateFrom, String releaseDateTo,
            String partTypes, String entryMode, String releaseDateRelative) {
        ChangeHistoryService.HistoryFilters f = new ChangeHistoryService.HistoryFilters();
        f.lifecyclePhases = parseCsv(lifecyclePhases);
        f.changeTypes     = parseCsv(changeTypes);
        f.partTypes       = parseCsv(partTypes);
        // Relative-date macro (last7d / last30d / last90d / lastYear) takes
        // precedence over explicit from/to so a scheduled report keeps tracking
        // the rolling window as time advances.
        if (releaseDateRelative != null && !releaseDateRelative.trim().isEmpty()) {
            LocalDate today = LocalDate.now();
            LocalDate from;
            switch (releaseDateRelative.trim().toLowerCase()) {
                case "last7d":   from = today.minusDays(7);   break;
                case "last30d":  from = today.minusDays(30);  break;
                case "last90d":  from = today.minusDays(90);  break;
                case "lastyear": from = today.minusYears(1);  break;
                default:         from = null;
            }
            if (from != null) {
                f.releaseDateFrom = from.format(DateTimeFormatter.ISO_LOCAL_DATE);
                f.releaseDateTo   = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
        } else {
            f.releaseDateFrom = releaseDateFrom == null || releaseDateFrom.trim().isEmpty() ? null : releaseDateFrom.trim();
            f.releaseDateTo   = releaseDateTo   == null || releaseDateTo.trim().isEmpty()   ? null : releaseDateTo.trim();
        }
        if (entryMode != null) {
            switch (entryMode.trim().toLowerCase()) {
                case "first": f.entryMode = ChangeHistoryService.EntryMode.FIRST; break;
                case "last":  f.entryMode = ChangeHistoryService.EntryMode.LAST;  break;
                default:      f.entryMode = ChangeHistoryService.EntryMode.ALL;
            }
        }
        return f;
    }

    private static String summariseFilters(ChangeHistoryService.HistoryFilters f) {
        StringBuilder sb = new StringBuilder();
        if (!f.lifecyclePhases.isEmpty()) sb.append(" | Phases: ").append(f.lifecyclePhases);
        if (!f.changeTypes.isEmpty())     sb.append(" | Types: ").append(f.changeTypes);
        if (f.releaseDateFrom != null || f.releaseDateTo != null)
            sb.append(" | Dates: ").append(f.releaseDateFrom == null ? "" : f.releaseDateFrom)
              .append("..").append(f.releaseDateTo == null ? "" : f.releaseDateTo);
        if (!f.partTypes.isEmpty())       sb.append(" | PartTypes: ").append(f.partTypes);
        if (f.entryMode != ChangeHistoryService.EntryMode.ALL) sb.append(" | EntryMode: ").append(f.entryMode);
        return sb.toString();
    }

    /** Human-readable filter chip text, shown next to the result-count chip. */
    private static String describeFilters(ChangeHistoryService.HistoryFilters f) {
        List<String> parts = new ArrayList<>();
        if (!f.lifecyclePhases.isEmpty()) parts.add("Lifecycle: " + String.join(",", f.lifecyclePhases));
        if (!f.changeTypes.isEmpty())     parts.add("Change Type: " + String.join(",", f.changeTypes));
        if (f.releaseDateFrom != null || f.releaseDateTo != null) {
            parts.add("Release Date: " + (f.releaseDateFrom == null ? "—" : f.releaseDateFrom)
                    + " → " + (f.releaseDateTo == null ? "—" : f.releaseDateTo));
        }
        if (!f.partTypes.isEmpty())       parts.add("Part Type: " + String.join(",", f.partTypes));
        if (f.entryMode != ChangeHistoryService.EntryMode.ALL)
            parts.add("Per (item × state): " + (f.entryMode == ChangeHistoryService.EntryMode.FIRST ? "First entry" : "Last entry"));
        return String.join(" · ", parts);
    }

    /** Both GET (small inputs) and POST (bulk inputs — items in form body) are supported.
     *  Pasted-item lists from the search box stay in the URL; uploaded files (10K+ items)
     *  blow past Tomcat's maxHttpHeaderSize when sent as a query string and need POST. */
    @org.springframework.web.bind.annotation.RequestMapping(value = "/export",
            method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                      org.springframework.web.bind.annotation.RequestMethod.POST})
    public void export(@RequestParam(value = "items", required = false) String items,
                       @RequestParam(value = "lifecyclePhases",     required = false) String lifecyclePhases,
                       @RequestParam(value = "changeTypes",         required = false) String changeTypes,
                       @RequestParam(value = "releaseDateFrom",     required = false) String releaseDateFrom,
                       @RequestParam(value = "releaseDateTo",       required = false) String releaseDateTo,
                       @RequestParam(value = "partTypes",           required = false) String partTypes,
                       @RequestParam(value = "entryMode",           required = false) String entryMode,
                       @RequestParam(value = "releaseDateRelative", required = false) String releaseDateRelative,
                       HttpSession session,
                       HttpServletResponse response) throws IOException {

        List<Map<String, String>> results;
        String itemsKey = items == null ? "" : items;
        if (!lastResults.isEmpty() && itemsKey.equals(lastItems)) {
            results = lastResults;
        } else {
            ChangeHistoryService.HistoryFilters f = buildFilters(
                    lifecyclePhases, changeTypes, releaseDateFrom, releaseDateTo,
                    partTypes, entryMode, releaseDateRelative);
            results = changeHistoryService.getHistoryFiltered(parseItems(items), f);
        }

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "Change-History-" + date + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "HISTORY_EXPORT", results.size() + " rows exported");

        writeExcel(results, response.getOutputStream());
    }

    @PostMapping("/email")
    public Map<String, Object> emailReport(@RequestParam(value = "items", required = false) String items,
                                           @RequestParam(value = "lifecyclePhases",     required = false) String lifecyclePhases,
                                           @RequestParam(value = "changeTypes",         required = false) String changeTypes,
                                           @RequestParam(value = "releaseDateFrom",     required = false) String releaseDateFrom,
                                           @RequestParam(value = "releaseDateTo",       required = false) String releaseDateTo,
                                           @RequestParam(value = "partTypes",           required = false) String partTypes,
                                           @RequestParam(value = "entryMode",           required = false) String entryMode,
                                           @RequestParam(value = "releaseDateRelative", required = false) String releaseDateRelative,
                                           HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");

        if (email == null || email.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "Not logged in.");
            return resp;
        }

        try {
            List<Map<String, String>> results;
            String itemsKey = items == null ? "" : items;
            if (!lastResults.isEmpty() && itemsKey.equals(lastItems)) {
                results = lastResults;
            } else {
                ChangeHistoryService.HistoryFilters f = buildFilters(
                        lifecyclePhases, changeTypes, releaseDateFrom, releaseDateTo,
                        partTypes, entryMode, releaseDateRelative);
                results = changeHistoryService.getHistoryFiltered(parseItems(items), f);
            }

            ByteArrayOutputStream excelOut = new ByteArrayOutputStream();
            writeExcel(results, excelOut);

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = "Change-History-" + date + ".xlsx";
            emailService.sendBomReport(email, displayName, excelOut.toByteArray(),
                    filename, "Change History Report", results.size());

            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "HISTORY_EMAIL", results.size() + " rows emailed");

            resp.put("success", true);
            resp.put("message", "Report sent to " + email + " (" + results.size() + " records)");
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "Failed: " + e.getMessage());
        }
        return resp;
    }

    private void writeExcel(List<Map<String, String>> results, OutputStream out) throws IOException {
        // Inline strings — no shared-strings table (OOM-safety).
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 1000, false, false);
        try {
            Sheet sheet = workbook.createSheet("Change History");

            // PT-tz cleanup (May 27, 2026): match the cleaner Agile-Lookup-style
            // export — single dark-slate header (#2c3e50, matches the SanDisk
            // email/UI palette), no zebra row striping. The prior loud
            // DARK_BLUE-header + GREY_25_PERCENT-zebra combination read as
            // "too colorful" in Excel; this is the cleaner version Vikas Singh
            // referenced.
            XSSFCellStyle headerStyle = (XSSFCellStyle) workbook.createCellStyle();
            XSSFFont headerFont = (XSSFFont) workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(
                new byte[]{(byte) 0x2c, (byte) 0x3e, (byte) 0x50}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (Map<String, String> rec : results) {
                Row row = sheet.createRow(rowIdx);
                for (int i = 0; i < KEYS.length; i++) {
                    row.createCell(i).setCellValue(rec.getOrDefault(KEYS[i], ""));
                }
                rowIdx++;
            }

            int[] widths = {5000, 2000, 4000, 4000, 5000, 5000, 5000, 5000, 5000, 4000, 10000, 3500};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i]);
            sheet.createFreezePane(0, 1);
            if (!results.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));
            }

            addAnalysisSheet(workbook, results);

            workbook.write(out);
        } finally {
            workbook.close();
            workbook.dispose();
        }
    }

    private List<String> parseItems(String items) {
        if (items == null || items.trim().isEmpty()) return new ArrayList<>();
        return Arrays.stream(items.split("[\\s,;]+"))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String s(HttpSession session, String key) {
        if (session == null) return "unknown";
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "unknown";
    }

    private void addAnalysisSheet(SXSSFWorkbook workbook, List<Map<String, String>> results) {
        String[] PHASE_ORDER = {"DEV", "PROTO", "PPROD", "C-ACT", "ACT", "MKT", "EOL", "OBS", "OBS-SKU", "DEAD"};

        Sheet sheet = workbook.createSheet("Lifecycle Analysis");

        // Styles
        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 13);
        titleFont.setColor(IndexedColors.DARK_BLUE.getIndex());
        titleStyle.setFont(titleFont);

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle greenStyle = workbook.createCellStyle();
        greenStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle amberStyle = workbook.createCellStyle();
        amberStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        amberStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle regressionStyle = workbook.createCellStyle();
        Font redFont = workbook.createFont();
        redFont.setBold(true);
        redFont.setColor(IndexedColors.RED.getIndex());
        regressionStyle.setFont(redFont);
        regressionStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        regressionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle summaryStyle = workbook.createCellStyle();
        Font summaryFont = workbook.createFont();
        summaryFont.setBold(true);
        summaryFont.setItalic(true);
        summaryStyle.setFont(summaryFont);
        summaryStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        summaryStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle grayStyle = workbook.createCellStyle();
        grayStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        grayStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Group by item
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        for (Map<String, String> rec : results) {
            grouped.computeIfAbsent(rec.get("itemNumber"), k -> new ArrayList<>()).add(rec);
        }

        int rowIdx = 0;

        for (Map.Entry<String, List<Map<String, String>>> entry : grouped.entrySet()) {
            String itemNum = entry.getKey();
            List<Map<String, String>> recs = entry.getValue();

            // Sort by release date ascending (parse dates for correct order)
            java.text.SimpleDateFormat sortFmt = new java.text.SimpleDateFormat("MM-dd-yyyy hh:mm:ss a");
            recs.sort((a, b) -> {
                try {
                    java.util.Date ad = sortFmt.parse(a.getOrDefault("releaseDate", "01-01-1970 12:00:00 AM"));
                    java.util.Date bd = sortFmt.parse(b.getOrDefault("releaseDate", "01-01-1970 12:00:00 AM"));
                    return ad.compareTo(bd);
                } catch (Exception e) {
                    return a.getOrDefault("releaseDate", "").compareTo(b.getOrDefault("releaseDate", ""));
                }
            });

            // Item header
            Row titleRow = sheet.createRow(rowIdx++);
            Cell tc = titleRow.createCell(0);
            tc.setCellValue("Lifecycle Journey: " + itemNum);
            tc.setCellStyle(titleStyle);

            // Column headers
            Row hdrRow = sheet.createRow(rowIdx++);
            String[] analysisHeaders = {"Phase", "Entered", "Exited", "Duration (days)", "Change Number", "Regression"};
            for (int i = 0; i < analysisHeaders.length; i++) {
                Cell c = hdrRow.createCell(i);
                c.setCellValue(analysisHeaders[i]);
                c.setCellStyle(headerStyle);
            }

            // Compute journey
            String lastPhase = "";
            List<String[]> journey = new ArrayList<>();

            for (Map<String, String> rec : recs) {
                String phase = rec.getOrDefault("lifecyclePhase", "");
                if (phase.contains(".")) phase = phase.substring(phase.lastIndexOf('.') + 1).trim();
                if (phase.isEmpty()) continue;

                String dateStr = rec.getOrDefault("releaseDate", "");
                if (dateStr.isEmpty()) continue; // Skip records with no release date
                if (dateStr.length() > 10) dateStr = dateStr.substring(0, 10);

                if (!phase.equals(lastPhase)) {
                    // Close previous
                    if (!journey.isEmpty()) {
                        String[] prev = journey.get(journey.size() - 1);
                        prev[2] = dateStr;
                        prev[3] = String.valueOf(daysBetween(prev[1], dateStr));
                    }

                    journey.add(new String[]{phase, dateStr, "", "0", rec.getOrDefault("changeNumber", ""), ""});
                    lastPhase = phase;
                }
            }

            // Second pass: mark regressions — flag the phase that caused the backward move
            for (int ri = 0; ri < journey.size() - 1; ri++) {
                int curIdx = indexOf(PHASE_ORDER, journey.get(ri)[0]);
                int nextIdx = indexOf(PHASE_ORDER, journey.get(ri + 1)[0]);
                if (curIdx >= 0 && nextIdx >= 0 && nextIdx < curIdx) {
                    journey.get(ri)[5] = "YES";
                }
            }

            // Close final phase
            if (!journey.isEmpty()) {
                String[] last = journey.get(journey.size() - 1);
                if (last[2].isEmpty()) {
                    String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd-yyyy"));
                    last[2] = "current";
                    last[3] = String.valueOf(daysBetween(last[1], today));
                }
            }

            // Write journey rows
            int totalDays = 0;
            int regressionCount = 0;
            for (String[] j : journey) {
                Row row = sheet.createRow(rowIdx++);
                for (int i = 0; i < j.length; i++) {
                    Cell c = row.createCell(i);
                    if (i == 3) {
                        try { c.setCellValue(Integer.parseInt(j[i])); } catch (Exception e) { c.setCellValue(j[i]); }
                    } else {
                        c.setCellValue(j[i]);
                    }
                }

                // Color coding
                CellStyle style = null;
                if ("YES".equals(j[5])) {
                    style = regressionStyle;
                    regressionCount++;
                } else if ("ACT".equals(j[0])) {
                    style = greenStyle;
                } else if ("PROTO".equals(j[0]) || "PPROD".equals(j[0])) {
                    style = amberStyle;
                } else if ("DEV".equals(j[0])) {
                    style = grayStyle;
                }
                if (style != null) {
                    for (int c = 0; c < j.length; c++) row.getCell(c).setCellStyle(style);
                }

                try { totalDays += Integer.parseInt(j[3]); } catch (Exception ignored) {}
            }

            // Summary row
            Row summRow = sheet.createRow(rowIdx++);
            Cell sc = summRow.createCell(0);
            String summaryText = journey.size() + " transitions, " + formatDurationFriendly(totalDays);
            if (regressionCount > 0) {
                summaryText += ", " + regressionCount + " REGRESSION(S)";
            } else {
                summaryText += ", clean progression";
            }
            if (!journey.isEmpty()) {
                summaryText += ". Current: " + journey.get(journey.size() - 1)[0];
            }
            sc.setCellValue(summaryText);
            sc.setCellStyle(summaryStyle);

            rowIdx++; // blank row between items
        }

        // Column widths
        sheet.setColumnWidth(0, 4000);
        sheet.setColumnWidth(1, 4000);
        sheet.setColumnWidth(2, 4000);
        sheet.setColumnWidth(3, 3500);
        sheet.setColumnWidth(4, 5000);
        sheet.setColumnWidth(5, 3000);
    }

    private String formatDurationFriendly(int totalDays) {
        if (totalDays <= 0) return "<1 day";
        int years = totalDays / 365;
        int months = (totalDays % 365) / 30;
        int days = totalDays % 30;
        List<String> parts = new ArrayList<>();
        if (years > 0) parts.add(years + (years == 1 ? " year" : " years"));
        if (months > 0) parts.add(months + (months == 1 ? " month" : " months"));
        if (days > 0 || parts.isEmpty()) parts.add(days + (days == 1 ? " day" : " days"));
        return String.join(", ", parts) + " (" + totalDays + "d)";
    }

    private int indexOf(String[] arr, String val) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(val)) return i;
        }
        return -1;
    }

    private int daysBetween(String from, String to) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd-yyyy");
            java.util.Date d1 = sdf.parse(from);
            java.util.Date d2 = sdf.parse(to);
            return (int) ((d2.getTime() - d1.getTime()) / 86400000L);
        } catch (Exception e) {
            return 0;
        }
    }
}
