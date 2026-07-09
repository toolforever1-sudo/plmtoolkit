package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.BomFilters;
import com.sandisk.plm.tracker.model.BomResult;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.BomDataService;
import com.sandisk.plm.tracker.service.BomExcelExportService;
import com.sandisk.plm.tracker.service.BomExtractService;
import com.sandisk.plm.tracker.service.EmailService;
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
@RequestMapping("/api/bom")
public class BomController {

    @Autowired
    private BomDataService bomDataService;

    @Autowired
    private com.sandisk.plm.tracker.service.UploadQuarantineService quarantineService;

    @Autowired
    private com.sandisk.plm.tracker.util.UploadColumnDetector columnDetector;

    @Autowired
    private BomExcelExportService bomExcelExportService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private BomExtractService bomExtractService;

    @Autowired
    private com.sandisk.plm.tracker.service.BomCsvStreamService bomCsvStreamService;

    @Autowired
    private com.sandisk.plm.tracker.service.BomImplodeEnrichService bomImplodeEnrichService;

    @Autowired
    private com.sandisk.plm.tracker.util.HeapGuard bomHeapGuard;

    private com.sandisk.plm.tracker.util.HeapGuard heapGuardForBom() { return bomHeapGuard; }

    // Cache last query result so export/email don't re-query
    private volatile List<BomResult> lastBomResults = new ArrayList<>();
    private volatile List<BomResult> lastTopLevelResults = new ArrayList<>();
    private volatile String lastBomMode = "";
    private volatile String lastBomItems = "";
    private volatile BomFilters lastBomFilters = BomFilters.none();

    @GetMapping("/explode")
    public Map<String, Object> explode(
            @RequestParam String items,
            @RequestParam(defaultValue = "20") int maxDepth,
            @RequestParam(required = false) String lifecycles,
            @RequestParam(required = false) String lifecyclesMode,
            @RequestParam(required = false) String partTypes,
            @RequestParam(required = false) String partTypesMode,
            @RequestParam(required = false) String prefixes,
            @RequestParam(required = false) String prefixesMode,
            @RequestParam(required = false) Integer maxTopLevelParents,
            HttpSession session) {
        BomFilters filters = BomFilters.parse(lifecycles, lifecyclesMode,
                partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
        Map<String, Object> result = runQuery(items, maxDepth, true, filters);
        String filterDesc = filters.describe();
        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "BOM_EXPLODE", "Items: " + items + " | Rows: " + result.get("totalCount")
                + (filterDesc.isEmpty() ? "" : " | Filters: " + filterDesc));
        return result;
    }

    @GetMapping("/implode")
    public Map<String, Object> implode(
            @RequestParam String items,
            @RequestParam(defaultValue = "20") int maxDepth,
            @RequestParam(required = false) String lifecycles,
            @RequestParam(required = false) String lifecyclesMode,
            @RequestParam(required = false) String partTypes,
            @RequestParam(required = false) String partTypesMode,
            @RequestParam(required = false) String prefixes,
            @RequestParam(required = false) String prefixesMode,
            @RequestParam(required = false) Integer maxTopLevelParents,
            HttpSession session) {
        BomFilters filters = BomFilters.parse(lifecycles, lifecyclesMode,
                partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
        Map<String, Object> result = runQuery(items, maxDepth, false, filters);
        String filterDesc = filters.describe();
        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "BOM_IMPLODE", "Items: " + items + " | Rows: " + result.get("totalCount")
                + (filterDesc.isEmpty() ? "" : " | Filters: " + filterDesc));
        return result;
    }

    @PostMapping("/upload")
    public Map<String, Object> uploadAndQuery(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "explode") String mode,
            @RequestParam(defaultValue = "20") int maxDepth,
            @RequestParam(value = "itemColumn", required = false) Integer itemColumn,
            @RequestParam(required = false) String lifecycles,
            @RequestParam(required = false) String lifecyclesMode,
            @RequestParam(required = false) String partTypes,
            @RequestParam(required = false) String partTypesMode,
            @RequestParam(required = false) String prefixes,
            @RequestParam(required = false) String prefixesMode,
            @RequestParam(required = false) Integer maxTopLevelParents,
            HttpSession session) throws IOException {

        Map<String, String> qParams = new LinkedHashMap<>();
        qParams.put("mode", mode);
        qParams.put("maxDepth", String.valueOf(maxDepth));
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file, s(session, "username"),
                "/api/bom/upload", qParams);

        // Reset progress counters before the slow file-parse step. Otherwise the
        // front-end poller briefly flashes stale values from the previous query
        // (e.g. "9097 of 9097" from a run that just finished) before the new query
        // reaches runBatched and zeroes them.
        bomDataService.resetBomProgress();

        // Validate file
        String typeErr = com.sandisk.plm.tracker.util.FileItemParser.validateFileType(file.getOriginalFilename());
        if (typeErr != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", typeErr);
            err.put("results", new ArrayList<>());
            err.put("totalCount", 0);
            return err;
        }
        // HeapGuard does the real check; this is a hard ceiling so we never even
        // try to open something absurd (e.g. someone uploads a 4 GB blob by mistake).
        if (file.getSize() > 200 * 1024 * 1024) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "File too large (max 200 MB). Please split into smaller files.");
            err.put("results", new ArrayList<>());
            err.put("totalCount", 0);
            return err;
        }

        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.util.FileItemParser.ParseResult parsed =
                com.sandisk.plm.tracker.util.FileItemParser.parseItemsWithDetection(file, columnDetector, override);
        // Dedup before joining — IN-list queries on duplicates just waste CPU, and a
        // re-uploaded BOM-Implosion export typically has the same parent across many rows.
        List<String> itemNumbers = parsed.distinctItems();

        if (itemNumbers.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "File is empty or contains no valid item numbers.");
            err.put("results", new ArrayList<>());
            err.put("totalCount", 0);
            return err;
        }

        String items = String.join(",", itemNumbers);
        BomFilters filters = BomFilters.parse(lifecycles, lifecyclesMode,
                partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
        Map<String, Object> result = runQuery(items, maxDepth, "explode".equals(mode), filters);
        String filterDesc = filters.describe();
        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "BOM_UPLOAD", "Mode: " + mode + " | Items: " + itemNumbers.size()
                + " (rows: " + parsed.totalRows + ", unique: " + parsed.uniqueItems + ")"
                + " | Rows: " + result.get("totalCount")
                + " | Sheet: " + parsed.sourceSheet
                + " | Col: " + columnLetter(parsed.sourceColumn) + " (" + parsed.sourceHeader + ")"
                + " via " + parsed.method
                + (filterDesc.isEmpty() ? "" : " | Filters: " + filterDesc));

        // Surface detection metadata so the UI can show "Sheet: Assembly list · Item column: A (ITEM_ID)"
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("index", parsed.sourceColumn);
        col.put("letter", columnLetter(parsed.sourceColumn));
        col.put("header", parsed.sourceHeader);
        col.put("method", parsed.method);
        col.put("sheet", parsed.sourceSheet);
        result.put("itemColumn", col);
        result.put("inputRows", parsed.totalRows);
        result.put("uniqueItems", parsed.uniqueItems);

        quarantineService.release(qTicket);
        return result;
    }

    /** Implode-only enrichment endpoint: streams back the input xlsx with one new column
     *  ("Top-Level Parent(s)") inserted right after the detected item-number column.
     *  The Explode mode doesn't get this — it's one-to-many and doesn't fit one row per input. */
    @PostMapping("/enrich-implode")
    public void enrichImplode(@RequestParam("file") MultipartFile file,
                              @RequestParam(value = "itemColumn", required = false) Integer itemColumn,
                              HttpSession session,
                              HttpServletResponse response) throws IOException {
        // Server-side HeapGuard re-check (defense-in-depth; the JS smartEnrich
        // already probes, but a curl/script caller can bypass). Spring's
        // sendError(503, msg) strips the message — write a plain-text body
        // directly so the caller sees the actual reason.
        com.sandisk.plm.tracker.util.HeapGuard.Verdict v =
                heapGuardForBom().check(file.getSize(), com.sandisk.plm.tracker.util.HeapGuard.Op.ENRICH);
        if (!v.ok) {
            java.util.logging.Logger.getLogger("BomController").warning("[BOM-ENRICH] HeapGuard rejected — " + v.message);
            response.reset();
            response.setStatus(503);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().write(v.message);
            return;
        }

        Map<String, String> qParams = new LinkedHashMap<>();
        if (itemColumn != null) qParams.put("itemColumn", itemColumn.toString());
        String qTicket = quarantineService.quarantine(file, s(session, "username"),
                "/api/bom/enrich-implode", qParams);

        // PT-46: respond with the same format the user uploaded. The service used to
        // hard-code XSSF and crash on .xls — now WorkbookFactory.create() handles both,
        // and we mirror the input format on the way out so the user's file stays usable
        // in their native tool. Sniff the magic bytes (don't trust filename extension).
        boolean inputIsHssf = isOle2(file);
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String origName = file.getOriginalFilename() == null
                ? (inputIsHssf ? "input.xls" : "input.xlsx") : file.getOriginalFilename();
        String base = origName.replaceFirst("\\.[^.]+$", "");
        String ext = inputIsHssf ? ".xls" : ".xlsx";
        String mime = inputIsHssf
                ? "application/vnd.ms-excel"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        String filename = base + " (where-used " + date + ")" + ext;

        response.setContentType(mime);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        int override = itemColumn == null ? -1 : itemColumn.intValue();
        com.sandisk.plm.tracker.service.BomImplodeEnrichService.EnrichResult er;
        try {
            er = bomImplodeEnrichService.enrich(file, override, response.getOutputStream(),
                    com.sandisk.plm.tracker.util.HeapGuard.Op.ENRICH.maxRows);
        } catch (com.sandisk.plm.tracker.service.BomImplodeEnrichService.RowCapExceededException tooBig) {
            java.util.logging.Logger.getLogger("BomController").warning("[BOM-ENRICH] row cap exceeded: " + tooBig.getMessage());
            response.reset();
            response.setStatus(413);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().write(tooBig.getMessage());
            quarantineService.release(qTicket);
            return;
        }

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "BOM_IMPLODE_ENRICH", "Rows: " + er.totalRows + " | Matched: " + er.matchedItems
                + " | Col: " + columnLetter(er.sourceColumn) + " (" + er.sourceHeader + ")"
                + " via " + er.detectionMethod
                + " | Duration: " + er.durationMs + "ms");

        quarantineService.release(qTicket);
    }

    /**
     * Sniff the first 8 bytes of an uploaded file to tell HSSF (.xls binary, OLE2)
     * from XSSF (.xlsx, OOXML/ZIP). Filename extension is unreliable — users rename.
     *
     * <p>OLE2 magic: {@code D0 CF 11 E0 A1 B1 1A E1}. OOXML is a ZIP so it starts
     * with {@code 50 4B 03 04}. Anything else falls through to "assume xlsx" which
     * matches the historical default and lets WorkbookFactory raise a clear error
     * downstream if the file is actually something else (CSV, garbage, etc.).
     */
    private static boolean isOle2(MultipartFile file) {
        try (java.io.InputStream is = file.getInputStream()) {
            byte[] head = new byte[8];
            int n = 0;
            while (n < 8) {
                int r = is.read(head, n, 8 - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 8) return false;
            return (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0
                && (head[4] & 0xFF) == 0xA1 && (head[5] & 0xFF) == 0xB1
                && (head[6] & 0xFF) == 0x1A && (head[7] & 0xFF) == 0xE1;
        } catch (IOException e) {
            return false;
        }
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

    /** Both GET and POST are supported. POST reads items from the form body, which avoids
     *  Tomcat's maxHttpHeaderSize cap (8 KB default) when the user uploaded thousands of
     *  items — at ~16 chars/item the URL otherwise blows past the limit and 400s. */
    @org.springframework.web.bind.annotation.RequestMapping(value = "/export",
            method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                      org.springframework.web.bind.annotation.RequestMethod.POST})
    public void export(
            @RequestParam String items,
            @RequestParam(defaultValue = "20") int maxDepth,
            @RequestParam(defaultValue = "explode") String mode,
            @RequestParam(required = false) String lifecycles,
            @RequestParam(required = false) String lifecyclesMode,
            @RequestParam(required = false) String partTypes,
            @RequestParam(required = false) String partTypesMode,
            @RequestParam(required = false) String prefixes,
            @RequestParam(required = false) String prefixesMode,
            @RequestParam(required = false) Integer maxTopLevelParents,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        BomFilters filters = BomFilters.parse(lifecycles, lifecyclesMode,
                partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
        // Use cached results if they match the current query
        List<BomResult> results;
        if (!lastBomResults.isEmpty() && items.equals(lastBomItems) && mode.equals(lastBomMode)) {
            results = lastBomResults;
        } else {
            List<String> itemList = parseItems(items);
            results = "explode".equals(mode)
                    ? bomDataService.explodeMultiple(itemList, maxDepth, filters)
                    : bomDataService.implodeMultiple(itemList, maxDepth, filters);
        }

        String modeLabel = "explode".equals(mode) ? "Explosion" : "Where Used";
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "BOM-" + modeLabel.replace(' ', '-') + "-" + date + ".xlsx";

        List<String> titleItems = parseItems(items);
        String title = "BOM " + modeLabel + (titleItems.size() == 1 ? " - " + titleItems.get(0) : " (" + titleItems.size() + " items)");
        List<BomResult> topLevels = "implode".equals(mode) ? lastTopLevelResults : null;

        // Set the download headers AFTER the export call begins streaming —
        // if the pre-flight row-count guard throws, we still need to write a
        // JSON error to the body without these headers in the way.
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);

        try {
            bomExcelExportService.export(results, title, mode, topLevels, response.getOutputStream());
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "BOM_EXPORT", mode + " | Items: " + items + " | Rows: " + results.size());
        } catch (BomExcelExportService.ExcelRowLimitExceededException tooBig) {
            // Pre-flight rejected the export — nothing has been written to
            // the response yet, so we can replace the download headers with
            // a JSON 400 the frontend can show as a toast.
            if (!response.isCommitted()) response.reset();
            response.setStatus(400);
            response.setContentType("application/json");
            String msg = "BOM " + modeLabel + " would produce "
                    + String.format("%,d", tooBig.neededRows)
                    + " rows on the '" + tooBig.sheetName + "' sheet, exceeding Excel's "
                    + String.format("%,d", BomExcelExportService.EXCEL_MAX_ROW_COUNT)
                    + "-row limit. Narrow the input list or tighten filters (status, lifecycle, prefixes) and try again.";
            response.getWriter().write(
                "{\"success\":false,\"errorCode\":\"EXCEL_ROW_LIMIT\",\"sheet\":\""
                + tooBig.sheetName + "\",\"neededRows\":" + tooBig.neededRows + ","
                + "\"limit\":" + BomExcelExportService.EXCEL_MAX_ROW_COUNT + ","
                + "\"message\":\"" + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "BOM_EXPORT_REJECTED", mode + " | Items: " + items + " | Rows: " + results.size()
                    + " | sheet=" + tooBig.sheetName + " neededRows=" + tooBig.neededRows);
        }
    }

    /** Full path-expanded explode/implode streamed as a zipped CSV — the escape
     *  hatch for results beyond Excel's 1,048,576-row limit (e.g. a bundle SKU that
     *  explodes to ~21M rows). Streams to the response at flat memory; no cap. */
    @org.springframework.web.bind.annotation.RequestMapping(value = "/export-csv",
            method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                      org.springframework.web.bind.annotation.RequestMethod.POST})
    public void exportCsv(
            @RequestParam String items,
            @RequestParam(defaultValue = "99") int maxDepth,
            @RequestParam(defaultValue = "explode") String mode,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        List<String> itemList = parseItems(items);
        boolean explode = !"implode".equals(mode);
        String modeLabel = explode ? "Explosion" : "Where-Used";
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String base = "BOM-" + modeLabel.replace(' ', '-')
                + (itemList.size() == 1 ? "-" + itemList.get(0).replaceAll("[^A-Za-z0-9._-]", "_") : "")
                + "-" + date;
        String zipName = base + ".csv.zip";

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + zipName);
        try {
            long rows = bomCsvStreamService.streamZip(itemList, maxDepth, explode, base,
                    response.getOutputStream());
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                    "BOM_EXPORT_CSV", mode + " | Items: " + items + " | Rows: " + rows);
        } catch (IOException e) {
            // Headers/body may already be partially streamed; can't cleanly switch to
            // a JSON error, so just log — the client sees a truncated/failed download.
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                    "BOM_EXPORT_CSV_FAILED", mode + " | Items: " + items + " | " + e.getMessage());
            throw e;
        }
    }

    @PostMapping("/email")
    public Map<String, Object> emailReport(
            @RequestParam String items,
            @RequestParam(defaultValue = "20") int maxDepth,
            @RequestParam(defaultValue = "explode") String mode,
            @RequestParam(required = false) String lifecycles,
            @RequestParam(required = false) String lifecyclesMode,
            @RequestParam(required = false) String partTypes,
            @RequestParam(required = false) String partTypesMode,
            @RequestParam(required = false) String prefixes,
            @RequestParam(required = false) String prefixesMode,
            @RequestParam(required = false) Integer maxTopLevelParents,
            HttpSession session) {

        Map<String, Object> response = new LinkedHashMap<>();
        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");

        if (email == null || email.isEmpty()) {
            response.put("success", false);
            response.put("message", "Not logged in.");
            return response;
        }

        try {
            BomFilters filters = BomFilters.parse(lifecycles, lifecyclesMode,
                    partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
            List<BomResult> results;
            if (!lastBomResults.isEmpty() && items.equals(lastBomItems) && mode.equals(lastBomMode)) {
                results = lastBomResults;
            } else {
                List<String> itemList = parseItems(items);
                results = "explode".equals(mode)
                        ? bomDataService.explodeMultiple(itemList, maxDepth, filters)
                        : bomDataService.implodeMultiple(itemList, maxDepth, filters);
            }

            String modeLabel = "explode".equals(mode) ? "Explosion" : "Where Used";
            List<String> titleItems = parseItems(items);
        String title = "BOM " + modeLabel + (titleItems.size() == 1 ? " - " + titleItems.get(0) : " (" + titleItems.size() + " items)");

            // Generate Excel to byte array
            ByteArrayOutputStream excelOut = new ByteArrayOutputStream();
            List<BomResult> topLevels = "implode".equals(mode) ? lastTopLevelResults : null;
            bomExcelExportService.export(results, title, mode, topLevels, excelOut);

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = "BOM-" + modeLabel.replace(' ', '-') + "-" + date + ".xlsx";

            // Send via email service
            emailService.sendBomReport(email, displayName, excelOut.toByteArray(),
                    filename, title, results.size());

            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "BOM_EMAIL", mode + " | " + results.size() + " rows emailed");

            response.put("success", true);
            response.put("message", "Report sent to " + email + " (" + results.size() + " rows)");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed: " + e.getMessage());
        }
        return response;
    }

    @GetMapping("/compare")
    public Map<String, Object> compareBoms(
            @RequestParam String bomA,
            @RequestParam String bomB,
            HttpSession session) {
        long start = System.currentTimeMillis();
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            List<Map<String, String>> listA = bomDataService.getBomComponents(bomA.trim());
            List<Map<String, String>> listB = bomDataService.getBomComponents(bomB.trim());

            // Build maps keyed by component
            Map<String, Map<String, String>> mapA = new LinkedHashMap<>();
            for (Map<String, String> r : listA) mapA.put(r.get("component"), r);
            Map<String, Map<String, String>> mapB = new LinkedHashMap<>();
            for (Map<String, String> r : listB) mapB.put(r.get("component"), r);

            // Compute diff
            List<Map<String, Object>> diff = new ArrayList<>();
            Set<String> allComponents = new LinkedHashSet<>();
            allComponents.addAll(mapA.keySet());
            allComponents.addAll(mapB.keySet());

            String[] compareFields = {"qty", "description", "itemType", "status", "rev", "findNum", "refDes", "notes"};

            int added = 0, removed = 0, changed = 0, unchanged = 0;
            for (String comp : allComponents) {
                Map<String, String> a = mapA.get(comp);
                Map<String, String> b = mapB.get(comp);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("component", comp);

                if (a != null && b == null) {
                    row.put("diffStatus", "REMOVED");
                    for (String f : compareFields) {
                        row.put(f + "A", a.getOrDefault(f, ""));
                        row.put(f + "B", "");
                    }
                    removed++;
                } else if (a == null && b != null) {
                    row.put("diffStatus", "ADDED");
                    for (String f : compareFields) {
                        row.put(f + "A", "");
                        row.put(f + "B", b.getOrDefault(f, ""));
                    }
                    added++;
                } else {
                    boolean anyChanged = false;
                    for (String f : compareFields) {
                        String va = a.getOrDefault(f, "");
                        String vb = b.getOrDefault(f, "");
                        row.put(f + "A", va);
                        row.put(f + "B", vb);
                        if (!va.equals(vb)) anyChanged = true;
                    }
                    row.put("diffStatus", anyChanged ? "CHANGED" : "MATCH");
                    if (anyChanged) changed++; else unchanged++;
                }
                diff.add(row);
            }

            long elapsed = System.currentTimeMillis() - start;
            // Look up parent item info (rev + description)
            Map<String, String> infoA = bomDataService.getItemInfo(bomA);
            Map<String, String> infoB = bomDataService.getItemInfo(bomB);

            response.put("diff", diff);
            response.put("bomA", bomA.trim());
            response.put("bomB", bomB.trim());
            response.put("bomArev", infoA.getOrDefault("rev", ""));
            response.put("bomAdesc", infoA.getOrDefault("description", ""));
            response.put("bomBrev", infoB.getOrDefault("rev", ""));
            response.put("bomBdesc", infoB.getOrDefault("description", ""));
            response.put("countA", listA.size());
            response.put("countB", listB.size());
            response.put("onlyInA", removed);
            response.put("onlyInB", added);
            response.put("different", changed);
            response.put("same", unchanged);
            response.put("queryTimeMs", elapsed);

            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "BOM_COMPARE", bomA.trim() + " vs " + bomB.trim() +
                " | A:" + listA.size() + " B:" + listB.size() +
                " | +=" + added + " -=" + removed + " ~=" + changed + " ==" + unchanged);
        } catch (Exception e) {
            response.put("error", "Compare failed: " + e.getMessage());
        }
        return response;
    }

    @GetMapping("/compare/export")
    public void exportCompare(
            @RequestParam String bomA,
            @RequestParam String bomB,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        Map<String, Object> compareResult = compareBoms(bomA, bomB, session);
        if (compareResult.containsKey("error")) {
            response.sendError(500, (String) compareResult.get("error"));
            return;
        }

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "BOM-Compare-" + date + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) compareResult.get("diff");
        bomExcelExportService.exportCompare(diff, bomA.trim(), bomB.trim(), response.getOutputStream());
    }

    @PostMapping("/compare/email")
    public Map<String, Object> emailCompare(
            @RequestParam String bomA,
            @RequestParam String bomB,
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
            Map<String, Object> compareResult = compareBoms(bomA, bomB, session);
            if (compareResult.containsKey("error")) {
                resp.put("success", false);
                resp.put("message", (String) compareResult.get("error"));
                return resp;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> diff = (List<Map<String, Object>>) compareResult.get("diff");

            ByteArrayOutputStream excelOut = new ByteArrayOutputStream();
            bomExcelExportService.exportCompare(diff, bomA.trim(), bomB.trim(), excelOut);

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = "BOM-Compare-" + date + ".xlsx";
            String title = "BOM Compare: " + bomA.trim() + " vs " + bomB.trim();

            emailService.sendBomReport(email, displayName, excelOut.toByteArray(),
                    filename, title, diff.size());

            activityLogger.log(
                (String) session.getAttribute("username"), displayName,
                "BOM_COMPARE_EMAIL", bomA.trim() + " vs " + bomB.trim() + " | " + diff.size() + " rows emailed");

            resp.put("success", true);
            resp.put("message", "Report sent to " + email + " (" + diff.size() + " rows)");
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "Failed: " + e.getMessage());
        }
        return resp;
    }

    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", bomDataService.getBomTotalItems());
        response.put("completed", bomDataService.getBomCompletedItems());
        // Batched-path counters — when a level walk is in flight the per-item counter
        // doesn't move (it only ticks during the final emit phase), so the front-end
        // uses these to render real progress instead of "0 of N".
        response.put("level", bomDataService.getBomCurrentLevel());
        response.put("maxDepth", bomDataService.getBomMaxDepthCap());
        response.put("edges", bomDataService.getBomEdgeCount());
        response.put("chunksDone", bomDataService.getBomChunksDone());
        response.put("chunksTotal", bomDataService.getBomChunksTotal());
        response.put("stage", bomDataService.getBomStage());
        return response;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dataLoaded", bomDataService.isDataLoaded());
        response.put("dataAsOf", "Live database");
        return response;
    }

    @GetMapping("/full-extract-status")
    public Map<String, Object> getFullExtractStatus() {
        return bomExtractService.getLatestExtract();
    }

    @PostMapping("/full-extract-trigger")
    public Map<String, Object> triggerFullExtract() {
        Map<String, Object> response = new LinkedHashMap<>();
        if (bomExtractService.isRunning()) {
            response.put("success", false);
            response.put("message", "Extract is already being generated. Please check back shortly.");
        } else {
            boolean started = bomExtractService.triggerExtractIfNeeded();
            if (started) {
                response.put("success", true);
                response.put("message", "BOM extract generation started. This may take several minutes.");
            } else {
                response.put("success", false);
                response.put("message", "An extract file already exists. Use the download button.");
            }
        }
        return response;
    }

    @GetMapping("/full-extract-download")
    public void downloadFullExtract(HttpServletResponse response) throws IOException {
        java.io.File file = bomExtractService.getExtractFile();
        if (file == null || !file.exists()) {
            response.sendError(404, "No extract available");
            return;
        }
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=" + file.getName());
        response.setContentLengthLong(file.length());
        try (InputStream in = new FileInputStream(file)) {
            org.springframework.util.StreamUtils.copy(in, response.getOutputStream());
        }
    }

    @GetMapping("/bom-notes-status")
    public Map<String, Object> getBomNotesStatus() {
        return bomExtractService.getLatestNotesExtract();
    }

    @PostMapping("/bom-notes-trigger")
    public Map<String, Object> triggerBomNotes(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) {
            response.put("success", false);
            response.put("message", "Admin access required to regenerate the BOM Notes extract.");
            return response;
        }
        if (bomExtractService.isNotesRunning()) {
            response.put("success", false);
            response.put("message", "BOM Notes extract is already being generated. Please check back shortly.");
        } else {
            boolean started = bomExtractService.triggerBomNotesExtract();
            if (started) {
                response.put("success", true);
                response.put("message", "BOM Notes extract started. This may take a few minutes.");
            } else {
                response.put("success", false);
                response.put("message", "Could not start extract.");
            }
        }
        return response;
    }

    @GetMapping("/bom-notes-download")
    public void downloadBomNotes(HttpServletResponse response) throws IOException {
        File file = bomExtractService.getNotesExtractFile();
        if (file == null || !file.exists()) {
            response.sendError(404, "No BOM Notes extract available");
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + file.getName());
        response.setContentLengthLong(file.length());
        try (InputStream in = new FileInputStream(file)) {
            org.springframework.util.StreamUtils.copy(in, response.getOutputStream());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Hard cap on distinct input items for Where-Used (implode) queries.
     *  500 inputs is comfortably above any legitimate batch (typical day-job
     *  use is 1-50 items). A 999-input upload reliably produces 1.1-1.9 M
     *  edges + 500K+ rows, which has triggered three heap-pressure alerts
     *  in one day (2026-06-15 ~14:46, ~17:05, ~18:02 PT — same operator,
     *  same file shape, same fan-out). Hitting the cap returns a clear 400
     *  with batching instructions rather than letting the query run.
     *  Explode is far less explosive (the fan-out is bounded by the tree
     *  depth, not the parent population) so it gets a higher ceiling. */
    private static final int IMPLODE_HARD_CAP_INPUTS = 500;
    private static final int EXPLODE_HARD_CAP_INPUTS = 5000;

    /** Max rows serialized into the on-screen JSON response. The full result set
     *  stays cached in {@link #lastBomResults} for Export/Email, and the table only
     *  renders ~10K rows, so shipping the whole set (up to ~1M) is pointless — and a
     *  1M-row payload is a ~700 MB JSON that OOMs / truncates the response (QA saw
     *  "Unexpected end of JSON input" exploding SDSSBBYWDSDHLDY19). Cap the payload;
     *  the UI shows the true total and points at Export Excel / Full CSV. */
    private static final int JSON_DISPLAY_CAP = 20000;

    private Map<String, Object> runQuery(String items, int maxDepth, boolean explode) {
        return runQuery(items, maxDepth, explode, BomFilters.none());
    }

    private Map<String, Object> runQuery(String items, int maxDepth, boolean explode, BomFilters filters) {
        if (filters == null) filters = BomFilters.none();
        long start = System.currentTimeMillis();
        List<String> itemList = parseItems(items);

        // Pre-flight input-size guard. Where-Used in particular fans out
        // hard — a 999-input batch produced 1.1-1.9M edges in prod today,
        // pinning ~2 GB of BomResult objects per run. Reject up front with
        // a clear "split into batches" message instead of running the
        // query and pushing heap to 96%.
        int inputs = itemList == null ? 0 : itemList.size();
        int cap = explode ? EXPLODE_HARD_CAP_INPUTS : IMPLODE_HARD_CAP_INPUTS;
        if (inputs > cap) {
            String modeLabel = explode ? "Explode" : "Where-Used (Implode)";
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error",
                modeLabel + " accepts at most " + String.format("%,d", cap)
                    + " input items per batch; you submitted "
                    + String.format("%,d", inputs) + ". "
                    + (explode ? "Split the list into smaller batches and try again."
                                : "A batch this big can fan out to 1-2 million parent assemblies "
                                  + "and tip the JVM into heap pressure. Split the list into "
                                  + "batches of " + cap + " or fewer items and re-run.")
            );
            err.put("errorCode", "INPUT_SIZE_CAP");
            err.put("cap", cap);
            err.put("inputs", inputs);
            err.put("results", new ArrayList<>());
            err.put("totalCount", 0);
            err.put("mode", explode ? "explode" : "implode");
            err.put("items", items);
            java.util.logging.Logger.getLogger("BomController").warning(
                "[BOM " + (explode ? "EXPLODE" : "IMPLODE") + "] rejected — inputs="
                + inputs + " exceeds cap=" + cap);
            return err;
        }

        List<BomResult> results = explode
                ? bomDataService.explodeMultiple(itemList, maxDepth, filters)
                : bomDataService.implodeMultiple(itemList, maxDepth, filters);

        // Cache for export/email
        lastBomResults = results;
        lastBomMode = explode ? "explode" : "implode";
        lastBomItems = items;
        lastBomFilters = filters;

        // For implode, find ALL top-level assemblies via deep traversal
        int extraTopLevelCount = 0;
        if (!explode) {
            List<BomResult> deepTopLevels = bomDataService.findTopLevelAssemblies(itemList, filters);
            lastTopLevelResults = deepTopLevels;

            // Count top-levels in deep results that are NOT in the UI results
            Set<String> uiTopLevels = new HashSet<>();
            for (BomResult r : results) {
                if (r.isTopLevel()) {
                    uiTopLevels.add((r.getInputItem() != null ? r.getInputItem() : "") + "|" + r.getParent());
                }
            }
            Set<String> deepKeys = new HashSet<>();
            for (BomResult r : deepTopLevels) {
                deepKeys.add((r.getInputItem() != null ? r.getInputItem() : "") + "|" + r.getParent());
            }
            for (String key : deepKeys) {
                if (!uiTopLevels.contains(key)) extraTopLevelCount++;
            }
            java.util.logging.Logger.getLogger("BomController").info(
                "[TOP-LEVEL] UI top-levels: " + uiTopLevels.size() +
                " | Deep top-levels: " + deepKeys.size() +
                " | Extra (not in UI): " + extraTopLevelCount);
        } else {
            lastTopLevelResults = new ArrayList<>();
        }

        long elapsed = System.currentTimeMillis() - start;

        // Serialize only a capped slice to JSON — the full set is cached above for
        // Export/Email; the table renders ~10K rows. Prevents the 700 MB-JSON OOM.
        int totalRows = results.size();
        List<BomResult> displayRows = (totalRows > JSON_DISPLAY_CAP)
                ? new ArrayList<>(results.subList(0, JSON_DISPLAY_CAP)) : results;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", displayRows);
        response.put("totalCount", totalRows);
        response.put("displayCount", displayRows.size());
        response.put("displayTruncated", totalRows > displayRows.size());
        response.put("queryTimeMs", elapsed);
        response.put("dataAsOf", bomDataService.getDataAsOf());
        response.put("mode", explode ? "explode" : "implode");
        response.put("items", items);
        response.put("truncated", bomDataService.isBomTruncated());
        List<String> skipped = bomDataService.getBomSkippedItems();
        if (skipped != null && !skipped.isEmpty()) {
            response.put("skippedItems", skipped);
        }
        List<String> fromFile = bomDataService.getBomFromFileItems();
        if (fromFile != null && !fromFile.isEmpty()) {
            response.put("fromFileItems", fromFile);
            response.put("fromFileAsOf", bomDataService.getBomFromFileAsOf());
        }
        if (!explode) {
            response.put("extraTopLevelCount", extraTopLevelCount);
            response.put("totalTopLevelCount", lastTopLevelResults.size());
        }
        String filterDesc = filters.describe();
        if (!filterDesc.isEmpty()) response.put("filtersApplied", filterDesc);
        return response;
    }

    private String s(HttpSession session, String key) {
        if (session == null) return "unknown";
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "unknown";
    }

    private List<String> parseItems(String items) {
        return Arrays.stream(items.split("[\\s,;]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
