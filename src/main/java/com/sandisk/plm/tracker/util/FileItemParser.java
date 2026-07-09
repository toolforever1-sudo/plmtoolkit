package com.sandisk.plm.tracker.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.*;

/**
 * Extracts item numbers from uploaded files.
 *
 * <p>For Excel: scans row 1 headers (and a few sample rows) and asks
 * {@link UploadColumnDetector} which column holds the item number. The detector
 * returns either {@code header-match}, {@code ai-fallback}, or
 * {@code default-col-a} (the legacy behavior).</p>
 *
 * <p>For text/CSV: still reads the first column on each line.</p>
 */
public class FileItemParser {

    static {
        // POI 5.x default 100 MB record cap is too low for real BOM exports — the
        // shared-strings table alone can exceed it. Bump to 1 GB so big xlsx uploads
        // open cleanly. (Heap is independently policed by HeapGuard.)
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(1024 * 1024 * 1024);
    }

    /** Result of a smart parse — items plus the column-detection metadata. */
    public static class ParseResult {
        public List<String> items = new ArrayList<>();          // ALL extracted (may include duplicates)
        public int sourceColumn;      // 0-indexed
        public String sourceHeader;
        public String method;         // "header-match" | "ai-fallback" | "default-col-a" | "text" | "user-override"
        public int headerRowIndex;    // 0-indexed; -1 if no header detected (e.g. plain text)
        public int totalRows;         // total non-empty input rows considered
        public String sourceSheet;    // sheet name (Excel only); null for text/CSV
        public int uniqueItems;       // count of distinct values in {@link #items}; ≤ items.size()

        /** Convenience: items deduped, preserving first-seen order. The query path should
         *  use this — duplicates in IN-clauses just waste CPU. */
        public List<String> distinctItems() {
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(items);
            return new ArrayList<>(seen);
        }
    }

    /**
     * Backwards-compatible item-only parse. Internal callers that only need the
     * extracted list (no detection metadata) keep working unchanged.
     */
    public static List<String> parseItems(MultipartFile file) throws IOException {
        return parseItems(file, null);
    }

    /**
     * Smart parse — uses {@code detector} to pick the column for Excel files.
     * If {@code detector} is null (or the file is plain text), behaves like the
     * legacy column-A parser.
     */
    public static List<String> parseItems(MultipartFile file, UploadColumnDetector detector) throws IOException {
        ParseResult r = parseItemsWithDetection(file, detector);
        return r.items;
    }

    public static ParseResult parseItemsWithDetection(MultipartFile file, UploadColumnDetector detector) throws IOException {
        return parseItemsWithDetection(file, detector, -1);
    }

    /**
     * @param itemColumnOverride 0-indexed column to force-use; -1 means "let the detector decide".
     *                            When ≥ 0, the column-A header heuristic and AI fallback are both
     *                            skipped — the user (via the column-picker modal) has already chosen
     *                            which column to read.
     */
    public static ParseResult parseItemsWithDetection(MultipartFile file, UploadColumnDetector detector,
                                                       int itemColumnOverride) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null) filename = "";
        String lower = filename.toLowerCase();
        ParseResult r;
        if (lower.endsWith(".xlsx")) {
            // Streaming path — heap stays flat regardless of file size.
            r = parseXlsxStreaming(file, detector, itemColumnOverride);
        } else if (lower.endsWith(".xls")) {
            // Legacy .xls — the OLE2 format doesn't have a SAX equivalent in POI,
            // so we keep the DOM path. .xls files are usually small.
            r = parseExcelItemsSmart(file, detector, itemColumnOverride);
        } else {
            r = parseTextItemsSmart(file);
        }
        // Compute unique-items count (cheap, single pass).
        java.util.HashSet<String> seen = new java.util.HashSet<>(r.items);
        r.uniqueItems = seen.size();
        return r;
    }

    /**
     * Streaming xlsx parser — uses {@link StreamingExcelProbe} for column detection
     * and column extraction. Heap usage stays flat; a 72 MB BOM-Implosion file with
     * 825 K rows extracts in roughly 80 MB of heap instead of multi-GB.
     */
    private static ParseResult parseXlsxStreaming(MultipartFile file, UploadColumnDetector detector,
                                                   int itemColumnOverride) throws IOException {
        ParseResult res = new ParseResult();
        StreamingExcelProbe probe = new StreamingExcelProbe();
        StreamingExcelProbe.WorkbookProbe wp = probe.probe(file);
        if (wp.sheets.isEmpty()) {
            res.method = "default-col-a";
            return res;
        }
        int sheetIdx = StreamingExcelProbe.pickBestSheet(wp);
        StreamingExcelProbe.SheetProbe sp = wp.sheets.get(sheetIdx);
        res.sourceSheet = sp.sheetName;

        UploadColumnDetector.DetectionResult det;
        if (itemColumnOverride >= 0 && itemColumnOverride < Math.max(1, sp.headerRow.size())) {
            String h = itemColumnOverride < sp.headerRow.size() ? sp.headerRow.get(itemColumnOverride) : "";
            det = new UploadColumnDetector.DetectionResult(itemColumnOverride, h == null ? "" : h.trim(),
                    "user-override", sp.headerRowIndex);
        } else if (detector != null) {
            UploadColumnDetector.DetectionResult d0 = detector.detect(sp.headerRow, sp.sampleRows);
            // detector returns headerRow as 0/-1 relative to the row we passed; remap to file coords.
            int hri = d0.headerRow >= 0 ? sp.headerRowIndex : -1;
            det = new UploadColumnDetector.DetectionResult(d0.column, d0.header, d0.method, hri);
        } else {
            det = new UploadColumnDetector.DetectionResult(0,
                    sp.headerRow.isEmpty() ? "" : sp.headerRow.get(0), "default-col-a", sp.headerRowIndex);
        }

        res.sourceColumn = det.column;
        res.sourceHeader = det.header;
        res.method = det.method;
        res.headerRowIndex = det.headerRow;

        // Stream the chosen column.
        int skipThrough = det.headerRow >= 0 ? det.headerRow : -1;
        res.items = probe.streamColumn(file, sp.sheetName, det.column, skipThrough);
        // Best-effort total row count from the probe's <dimension>; otherwise use what we extracted.
        res.totalRows = sp.totalRows > 0 ? sp.totalRows - (det.headerRow + 1) : res.items.size();
        return res;
    }

    public static String validateFileType(String filename) {
        if (filename == null) return null;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return null;
        }
        return "Invalid file type. Please upload a .txt, .csv, .xlsx, or .xls file.";
    }

    // ── Text / CSV ──────────────────────────────────────────────────────────────

    private static ParseResult parseTextItemsSmart(MultipartFile file) throws IOException {
        ParseResult res = new ParseResult();
        res.sourceColumn = 0;
        res.sourceHeader = "";
        res.method = "text";
        res.headerRowIndex = -1;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            boolean firstLine = true;
            String line;
            int rowCount = 0;
            while ((line = reader.readLine()) != null) {
                rowCount++;
                if (firstLine) line = line.replace("\uFEFF", "");
                for (String token : line.split("[,\\t]")) {
                    String trimmed = token.trim();
                    if (trimmed.isEmpty() || trimmed.length() > 200) continue;
                    if (firstLine && UploadColumnDetector.isLikelyHeader(trimmed)) {
                        res.headerRowIndex = 0;
                        res.sourceHeader = trimmed;
                        continue;
                    }
                    res.items.add(trimmed);
                }
                firstLine = false;
            }
            res.totalRows = rowCount;
        }
        return res;
    }

    // ── Excel ───────────────────────────────────────────────────────────────────

    private static ParseResult parseExcelItemsSmart(MultipartFile file, UploadColumnDetector detector,
                                                     int itemColumnOverride) throws IOException {
        ParseResult res = new ParseResult();
        try (InputStream is = file.getInputStream();
             org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(is)) {

            org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter();

            // Multi-sheet workbooks: pick the sheet most likely to be a list of items
            // (highest priority header match, then most non-empty rows in that column).
            org.apache.poi.ss.usermodel.Sheet sheet = pickBestSheet(workbook, fmt);
            res.method = "default-col-a";  // will be overwritten by the detector below
            String pickedSheetName = sheet.getSheetName();

            int lastRow = sheet.getLastRowNum();
            int lastCol = computeLastCol(sheet);

            // Walk row 0..N looking for the first row that has ≥2 non-empty cells —
            // BOM-Implosion exports start with a banner ("BOM Implosion (43 items)")
            // and the real headers live on row 2. Cap the walk at 4 rows.
            int headerOffset = 0;
            List<String> headerRow = readRow(sheet, 0, lastCol, fmt);
            for (int attempt = 0; attempt < 4 && countNonEmpty(headerRow) < 2; attempt++) {
                if (headerOffset >= lastRow) break;
                headerOffset++;
                headerRow = readRow(sheet, headerOffset, lastCol, fmt);
            }
            // Sample rows immediately following the header row for the AI prompt.
            List<List<String>> samples = new ArrayList<>();
            for (int r = headerOffset + 1; r <= Math.min(headerOffset + 3, lastRow); r++) {
                samples.add(readRow(sheet, r, lastCol, fmt));
            }

            UploadColumnDetector.DetectionResult det;
            if (itemColumnOverride >= 0 && itemColumnOverride < Math.max(1, headerRow.size())) {
                String h = itemColumnOverride < headerRow.size() ? headerRow.get(itemColumnOverride) : "";
                det = new UploadColumnDetector.DetectionResult(itemColumnOverride, h == null ? "" : h.trim(),
                        "user-override", headerOffset);
            } else if (detector != null) {
                det = detector.detect(headerRow, samples);
                // detector.headerRow is 0 / -1 relative to the row we passed; remap to file coords.
                if (det.headerRow >= 0) {
                    det = new UploadColumnDetector.DetectionResult(det.column, det.header, det.method, headerOffset);
                }
            } else {
                det = new UploadColumnDetector.DetectionResult(0,
                        headerRow.isEmpty() ? "" : headerRow.get(0), "default-col-a", headerOffset);
            }

            res.sourceColumn = det.column;
            res.sourceHeader = det.header;
            res.method = det.method;
            res.headerRowIndex = det.headerRow;
            res.sourceSheet = pickedSheetName;

            int dataStart = Math.max(0, det.headerRow + 1);
            for (int r = dataStart; r <= lastRow; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) continue;
                org.apache.poi.ss.usermodel.Cell cell = row.getCell(det.column,
                        org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                String value = fmt.formatCellValue(cell).trim();
                if (value.isEmpty() || value.length() > 200) continue;
                res.items.add(value);
            }
            res.totalRows = lastRow - dataStart + 1;
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw new IOException("Cannot read encrypted Excel file.");
        }
        return res;
    }

    /**
     * Picks the sheet most likely to be a list of items in a multi-sheet workbook.
     *
     * <p>Strategy: score every sheet's row-1 headers against the same priority
     * keywords used by {@link UploadColumnDetector}. The sheet with the
     * highest-priority header match wins (lower index in the priority list = better).
     * Ties broken by the count of non-empty rows in the matched column. If no
     * sheet has any priority-keyword header, fall back to the sheet with the most
     * data rows (favoring the widest input).</p>
     *
     * <p>Edge cases:</p>
     * <ul>
     *   <li>Single-sheet workbook → return sheet 0 immediately.</li>
     *   <li>All sheets empty → return sheet 0 (legacy behavior).</li>
     * </ul>
     */
    private static org.apache.poi.ss.usermodel.Sheet pickBestSheet(
            org.apache.poi.ss.usermodel.Workbook wb,
            org.apache.poi.ss.usermodel.DataFormatter fmt) {
        int n = wb.getNumberOfSheets();
        if (n <= 1) return wb.getSheetAt(0);

        java.util.List<String> keywords = UploadColumnDetector.priorityKeywords();
        int bestPriority = Integer.MAX_VALUE;
        int bestRowCount = -1;
        int bestIdx = 0;
        boolean anyHeaderMatch = false;

        for (int s = 0; s < n; s++) {
            org.apache.poi.ss.usermodel.Sheet sh = wb.getSheetAt(s);
            if (sh == null) continue;
            int lastRow = sh.getLastRowNum();
            int lastCol = computeLastCol(sh);
            org.apache.poi.ss.usermodel.Row hdrRow = sh.getRow(0);
            if (hdrRow == null) continue;

            int sheetPriority = Integer.MAX_VALUE;
            int matchedCol = -1;
            for (int c = 0; c < lastCol; c++) {
                org.apache.poi.ss.usermodel.Cell cell = hdrRow.getCell(c);
                if (cell == null) continue;
                String norm = UploadColumnDetector.normalizeKey(fmt.formatCellValue(cell).trim());
                if (norm.isEmpty()) continue;
                int p = keywords.indexOf(norm);
                if (p >= 0 && p < sheetPriority) { sheetPriority = p; matchedCol = c; }
            }
            if (matchedCol >= 0) anyHeaderMatch = true;

            int rowsInCol = countNonEmptyInColumn(sh, matchedCol >= 0 ? matchedCol : 0, lastRow, fmt);

            // Lower priority wins; ties broken by more rows.
            if (sheetPriority < bestPriority
                    || (sheetPriority == bestPriority && rowsInCol > bestRowCount)) {
                bestPriority = sheetPriority;
                bestRowCount = rowsInCol;
                bestIdx = s;
            }
        }
        // If no sheet had a header match, sheet 0 is still the safe default.
        if (!anyHeaderMatch) return wb.getSheetAt(0);
        return wb.getSheetAt(bestIdx);
    }

    private static int countNonEmptyInColumn(org.apache.poi.ss.usermodel.Sheet sh, int col, int lastRow,
                                              org.apache.poi.ss.usermodel.DataFormatter fmt) {
        int count = 0;
        int cap = Math.min(lastRow, 5000);  // sampling cap — large sheets don't need a full scan
        for (int r = 1; r <= cap; r++) {
            org.apache.poi.ss.usermodel.Row row = sh.getRow(r);
            if (row == null) continue;
            org.apache.poi.ss.usermodel.Cell cell = row.getCell(col);
            if (cell == null) continue;
            String v = fmt.formatCellValue(cell).trim();
            if (!v.isEmpty()) count++;
        }
        return count;
    }

    private static int computeLastCol(org.apache.poi.ss.usermodel.Sheet sheet) {
        int maxRow = sheet.getLastRowNum();
        int last = 0;
        // Look at rows 0..2 to get a stable upper bound on column count.
        for (int r = 0; r <= Math.min(2, maxRow); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            if (row != null) last = Math.max(last, row.getLastCellNum());
        }
        return Math.max(last, 1);
    }

    private static int countNonEmpty(List<String> row) {
        if (row == null) return 0;
        int n = 0;
        for (String s : row) if (s != null && !s.trim().isEmpty()) n++;
        return n;
    }

    private static List<String> readRow(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, int lastCol,
                                        org.apache.poi.ss.usermodel.DataFormatter fmt) {
        List<String> out = new ArrayList<>();
        org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIndex);
        if (row == null) return out;
        for (int c = 0; c < lastCol; c++) {
            org.apache.poi.ss.usermodel.Cell cell = row.getCell(c,
                    org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            out.add(fmt.formatCellValue(cell).trim());
        }
        return out;
    }
}
