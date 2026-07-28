package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.util.FileItemParser;
import com.sandisk.plm.tracker.util.UploadColumnDetector;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * Enrichment endpoint backend: takes a user-uploaded Excel file, detects the
 * item-number column, runs the filtered Change History query, and writes back
 * the same workbook with 4 new columns inserted right after the item-number
 * column:
 *
 * <pre>
 *   Lifecycle Phase Reached  |  Release Date  |  Change Number  |  Change Type
 * </pre>
 *
 * <p>For each input row, the matching history record is the <strong>earliest released
 * REV</strong> for that item that satisfies the active filter. Items with no match
 * leave the four new cells blank.</p>
 *
 * <p>Formatting / row count / sheet name from the input are preserved. Cells to
 * the right of the item-number column are shifted right by 4 to make room.</p>
 */
@Service
public class ChangeHistoryEnrichService {

    private static final Logger logger = Logger.getLogger(ChangeHistoryEnrichService.class.getName());

    /** Headers for the 4 inserted columns. The "(from Change History)" suffix makes it
     *  obvious to end users that these were added by the Toolkit, not part of their original file. */
    private static final String[] NEW_HEADERS = {
        "Lifecycle Phase Reached (from Change History)",
        "Release Date (from Change History)",
        "Change Number (from Change History)",
        "Change Type (from Change History)"
    };

    @Autowired private ChangeHistoryService changeHistoryService;
    @Autowired private UploadColumnDetector columnDetector;

    /** Result envelope returned to the controller. */
    public static class EnrichResult {
        public byte[] xlsxBytes;
        public int totalRows;          // input data rows
        public int matchedItems;       // rows that found a history match
        public int sourceColumn;       // 0-indexed item-number column
        public String sourceHeader;
        public String detectionMethod; // header-match / ai-fallback / default-col-a
        public long durationMs;
        public boolean streamed;       // true when the SAX→SXSSF path was used (big files)
    }

    /** Row count at/below which we use the full-fidelity XSSF DOM rewrite; above it we switch to
     *  the streaming (SAX→SXSSF) writer. The DOM rewrite balloons to ~2.4 GB of XMLBeans objects
     *  for a 48K-row file (root cause of the PT-124 OOM), so big files must stream. Single source
     *  of truth: the shared HeapGuard ENRICH policy. */
    public static final int DOM_MAX_ROWS = com.sandisk.plm.tracker.util.HeapGuard.Op.ENRICH.maxRows;

    /** Backwards-compatible 4-arg overload — kept so any older caller compiles unchanged. */
    public EnrichResult enrich(MultipartFile file,
                               List<String> lifecyclePhases,
                               List<String> changeTypes,
                               OutputStream out) throws IOException {
        return enrich(file, lifecyclePhases, changeTypes, -1, DOM_MAX_ROWS, out);
    }

    /** Backwards-compatible 5-arg overload — uses the default DOM/streaming threshold. */
    public EnrichResult enrich(MultipartFile file,
                               List<String> lifecyclePhases,
                               List<String> changeTypes,
                               int itemColumnOverride,
                               OutputStream out) throws IOException {
        return enrich(file, lifecyclePhases, changeTypes, itemColumnOverride, DOM_MAX_ROWS, out);
    }

    /** Controller-facing overload that honors the First/Last entry mode (earliest vs latest
     *  matching change per item). "Show all"/null → earliest, because enrich fills exactly one
     *  value per item and can't inline every historical row. */
    public EnrichResult enrich(MultipartFile file,
                               List<String> lifecyclePhases,
                               List<String> changeTypes,
                               int itemColumnOverride,
                               ChangeHistoryService.EntryMode entryMode,
                               OutputStream out) throws IOException {
        return enrich(file, lifecyclePhases, changeTypes, itemColumnOverride, DOM_MAX_ROWS, entryMode, out);
    }

    /** Test/back-compat overload without an explicit entry mode → earliest per item. */
    public EnrichResult enrich(MultipartFile file,
                               List<String> lifecyclePhases,
                               List<String> changeTypes,
                               int itemColumnOverride,
                               int domThresholdRows,
                               OutputStream out) throws IOException {
        return enrich(file, lifecyclePhases, changeTypes, itemColumnOverride, domThresholdRows,
                      ChangeHistoryService.EntryMode.ALL, out);
    }

    /**
     * @param domThresholdRows row count at/below which the DOM rewrite is used; above it the
     *                         streaming writer is used (bounded memory). Exposed for tests.
     * @param entryMode        LAST → latest matching change per item; FIRST / ALL / null → earliest.
     */
    public EnrichResult enrich(MultipartFile file,
                               List<String> lifecyclePhases,
                               List<String> changeTypes,
                               int itemColumnOverride,
                               int domThresholdRows,
                               ChangeHistoryService.EntryMode entryMode,
                               OutputStream out) throws IOException {
        long t0 = System.currentTimeMillis();
        EnrichResult er = new EnrichResult();

        // 1. Detect item-number column + extract item list (one streaming pass — cheap, ~50 MB
        //    regardless of file size). This gives us the row count before any heavy work.
        FileItemParser.ParseResult parse = FileItemParser.parseItemsWithDetection(file, columnDetector, itemColumnOverride);
        er.sourceColumn = parse.sourceColumn;
        er.sourceHeader = parse.sourceHeader;
        er.detectionMethod = parse.method;
        er.totalRows = parse.totalRows;

        // 2. Run filtered query. distinctItems() dedups first — a SKU repeated across 900 rows is
        //    queried once, not 900 times (the query also dedups internally, but this is explicit
        //    and avoids handing it a list full of repeats).
        List<Map<String, String>> history = changeHistoryService.getHistoryFiltered(
                parse.distinctItems(), lifecyclePhases, changeTypes);

        // 3. Group by item number — keep the earliest (or, for LAST mode, the latest) released REV per item.
        Map<String, Map<String, String>> earliestByItem = pickBestPerItem(history, entryMode);
        er.matchedItems = earliestByItem.size();

        // 4. Write the enriched workbook. Small files use the full-fidelity DOM rewrite; big files
        //    stream (SAX→SXSSF) so memory stays flat instead of building a multi-GB DOM (PT-124).
        if (er.totalRows <= domThresholdRows) {
            er.streamed = false;
            writeEnrichedDom(file, parse, earliestByItem, out);
        } else {
            er.streamed = true;
            com.sandisk.plm.tracker.util.StreamingEnrichWriter.write(
                    file, parse.sourceColumn, parse.headerRowIndex, NEW_HEADERS,
                    itemNumber -> {
                        Map<String, String> m = earliestByItem.get(itemNumber);
                        return m == null ? null : new String[]{
                                m.get("lifecyclePhase"), m.get("releaseDate"),
                                m.get("changeNumber"), m.get("changeType")};
                    },
                    out);
        }
        er.durationMs = System.currentTimeMillis() - t0;
        logger.info(String.format("[HISTORY-ENRICH] rows=%d items=%d matched=%d col=%d (%s) method=%s path=%s dur=%dms",
                er.totalRows, parse.distinctItems().size(), er.matchedItems, er.sourceColumn,
                er.sourceHeader, er.detectionMethod, er.streamed ? "stream" : "dom", er.durationMs));
        return er;
    }

    /** Full-fidelity DOM rewrite (used for files ≤ {@link #DOM_MAX_ROWS}). Re-reads the input as an
     *  XSSFWorkbook, shifts every cell past the item column right by 4, and fills the inserted
     *  columns. Preserves everything (styles, merges, conditional formatting) but holds the whole
     *  workbook in memory — which is why big files take the streaming path instead. */
    private void writeEnrichedDom(MultipartFile file, FileItemParser.ParseResult parse,
                                  Map<String, Map<String, String>> earliestByItem, OutputStream out) throws IOException {
        try (InputStream is = file.getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            int sourceCol = parse.sourceColumn;
            int headerRow = parse.headerRowIndex;
            int lastRow = sheet.getLastRowNum();
            DataFormatter fmt = new DataFormatter();

            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));

            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // Shift cells from sourceCol+1 onwards right by 4 positions.
                int lastCellNum = row.getLastCellNum();   // exclusive — POI quirk
                for (int c = lastCellNum - 1; c > sourceCol; c--) {
                    Cell src = row.getCell(c);
                    if (src == null) continue;
                    Cell dst = row.createCell(c + NEW_HEADERS.length);
                    copyCell(src, dst);
                    row.removeCell(src);
                }

                // Now sourceCol+1 .. sourceCol+4 are empty. Fill them.
                if (r == headerRow) {
                    for (int i = 0; i < NEW_HEADERS.length; i++) {
                        Cell c = row.createCell(sourceCol + 1 + i, CellType.STRING);
                        c.setCellValue(NEW_HEADERS[i]);
                        // copy header style from the item-number header cell so the new headers
                        // visually blend with the rest
                        Cell ref = row.getCell(sourceCol);
                        if (ref != null && ref.getCellStyle() != null) c.setCellStyle(ref.getCellStyle());
                    }
                } else {
                    Cell itemCell = row.getCell(sourceCol);
                    String itemNum = itemCell == null ? "" : fmt.formatCellValue(itemCell).trim();
                    Map<String, String> match = earliestByItem.get(itemNum);
                    if (match != null) {
                        setStr (row, sourceCol + 1, match.get("lifecyclePhase"));
                        setDate(row, sourceCol + 2, match.get("releaseDate"), dateStyle);
                        setStr (row, sourceCol + 3, match.get("changeNumber"));
                        setStr (row, sourceCol + 4, match.get("changeType"));
                    }
                    // else: leave the 4 cells blank
                }
            }

            wb.write(out);
        }
    }

    /** Best-by-RELEASE_DATE per item: earliest for FIRST/ALL, latest for LAST.
     *  PT-tz: parsing in Pacific keeps the relative ordering identical to the
     *  on-screen view (formatTs emits Pacific). Any consistent TZ would sort the
     *  same, but matching Pacific avoids surprises if a future caller reads the
     *  epoch directly. */
    private static Map<String, Map<String, String>> pickBestPerItem(List<Map<String, String>> rows,
                                                                     ChangeHistoryService.EntryMode mode) {
        boolean latest = mode == ChangeHistoryService.EntryMode.LAST;
        Map<String, Map<String, String>> best = new HashMap<>();
        Map<String, Long> bestEpoch = new HashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyyy hh:mm:ss a");
        sdf.setTimeZone(ChangeHistoryService.PACIFIC_TZ);
        for (Map<String, String> r : rows) {
            String item = r.get("itemNumber");
            if (item == null || item.isEmpty()) continue;
            // No release date sorts last for "earliest" (Long.MAX) and last for "latest" (Long.MIN),
            // so a dated rev always beats an undated one in either direction.
            long epoch = latest ? Long.MIN_VALUE : Long.MAX_VALUE;
            String d = r.get("releaseDate");
            if (d != null && !d.isEmpty()) {
                try { epoch = sdf.parse(d).getTime(); }
                catch (ParseException ignore) {}
            }
            Long prev = bestEpoch.get(item);
            if (prev == null || (latest ? epoch > prev : epoch < prev)) {
                bestEpoch.put(item, epoch);
                best.put(item, r);
            }
        }
        return best;
    }

    private static void copyCell(Cell src, Cell dst) {
        if (src.getCellStyle() != null) dst.setCellStyle(src.getCellStyle());
        switch (src.getCellType()) {
            case STRING:  dst.setCellValue(src.getStringCellValue()); break;
            case NUMERIC: dst.setCellValue(src.getNumericCellValue()); break;
            case BOOLEAN: dst.setCellValue(src.getBooleanCellValue()); break;
            case FORMULA: dst.setCellFormula(src.getCellFormula()); break;
            case BLANK:   dst.setBlank(); break;
            case ERROR:   dst.setCellErrorValue(src.getErrorCellValue()); break;
            default:      dst.setBlank();
        }
    }

    private static void setStr(Row row, int col, String value) {
        Cell c = row.createCell(col, CellType.STRING);
        c.setCellValue(value == null ? "" : value);
    }

    /** Date column: parse "MM-dd-yyyy hh:mm:ss a" and write as a true Excel date with format.
     *  PT-tz note: the input string is already in Pacific wall-clock (set by
     *  ChangeHistoryService.formatTs). We deliberately parse it with the JVM
     *  default TZ here — and POI also uses the JVM default TZ when converting
     *  the resulting Date to an Excel date-serial — so the two cancel out and
     *  the cell ends up displaying the same Pacific wall-clock the UI shows.
     *  Setting an explicit TZ here would break that cancellation: on a UTC prod
     *  the cell would shift 7–8h ahead of the on-screen value. */
    private static void setDate(Row row, int col, String value, CellStyle dateStyle) {
        if (value == null || value.isEmpty()) {
            row.createCell(col, CellType.BLANK);
            return;
        }
        try {
            Date d = new SimpleDateFormat("MM-dd-yyyy hh:mm:ss a").parse(value);
            Cell c = row.createCell(col);
            c.setCellValue(d);
            c.setCellStyle(dateStyle);
        } catch (ParseException e) {
            // Fallback: write raw string.
            Cell c = row.createCell(col, CellType.STRING);
            c.setCellValue(value);
        }
    }
}
