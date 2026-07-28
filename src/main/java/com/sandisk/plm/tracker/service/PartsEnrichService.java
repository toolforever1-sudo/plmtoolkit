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
import java.util.*;
import java.util.logging.Logger;

/**
 * Part-Extract enrichment: takes a user-uploaded Excel file, detects the
 * item-number column, runs the same query the UI runs (with the user's
 * selected display columns), and writes back the same workbook with one
 * new column per selected field inserted right after the item-number column.
 *
 * <p>For each input row, the matching record is the {@code item_extract}
 * row for that exact {@code PART_NUMBER}. Items with no match leave the
 * new cells blank.</p>
 *
 * <p>Mirrors {@link ChangeHistoryEnrichService} — same shift-and-insert
 * approach, same heap shape (XSSFWorkbook DOM, protected by the upstream
 * heap-guard call).</p>
 */
@Service
public class PartsEnrichService {

    private static final Logger logger = Logger.getLogger(PartsEnrichService.class.getName());

    @Autowired private BomDataService bomDataService;
    @Autowired private UploadColumnDetector columnDetector;

    public static class EnrichResult {
        public int totalRows;
        public int matchedItems;
        public int sourceColumn;
        public String sourceHeader;
        public String detectionMethod;
        public long durationMs;
    }

    /** Thrown when an enrich request exceeds the per-call row cap. Caller
     *  should translate to a 413 (Payload Too Large) before any output is
     *  written. Subclass of {@link IOException} so {@code throws IOException}
     *  callers don't need to change their signatures. */
    public static class RowCapExceededException extends IOException {
        public RowCapExceededException(String msg) { super(msg); }
    }

    public EnrichResult enrich(MultipartFile file,
                                List<String> selectedColumns,
                                String releaseDateFrom,
                                String releaseDateTo,
                                int itemColumnOverride,
                                OutputStream out) throws IOException {
        // Back-compat overload (no row cap). Prefer the variant that takes
        // maxRows from the controller — uncapped enrichment is the path that
        // caused PT-29/PT-30 (3K-item file OOM).
        return enrich(file, selectedColumns, releaseDateFrom, releaseDateTo,
                      itemColumnOverride, out, Integer.MAX_VALUE);
    }

    public EnrichResult enrich(MultipartFile file,
                                List<String> selectedColumns,
                                String releaseDateFrom,
                                String releaseDateTo,
                                int itemColumnOverride,
                                OutputStream out,
                                int maxRows) throws IOException {
        long t0 = System.currentTimeMillis();
        EnrichResult er = new EnrichResult();

        // 1. Detect item-number column + extract item list.
        FileItemParser.ParseResult parse =
                FileItemParser.parseItemsWithDetection(file, columnDetector, itemColumnOverride);
        er.sourceColumn = parse.sourceColumn;
        er.sourceHeader = parse.sourceHeader;
        er.detectionMethod = parse.method;
        er.totalRows = parse.totalRows;

        // Hard row-count cap (PT-29/PT-30 follow-up). Pushes back with a clear
        // business message instead of letting XSSF DOM + cell-shift run the
        // JVM out of heap halfway through a multi-thousand-row file. Throws
        // RowCapExceededException so the controller can return a clean 413
        // before the response stream is touched.
        if (er.totalRows > maxRows) {
            throw new RowCapExceededException("This file has " + er.totalRows + " rows, but enrichment is capped at "
                + maxRows + " rows to protect server memory. Please split the file or run the query in batches.");
        }

        // 2. Run query with deduped items, using the user's selected display columns.
        List<String> uniques = parse.distinctItems();
        String items = String.join(",", uniques);
        List<Map<String, String>> results = bomDataService.searchParts(
                items, selectedColumns, releaseDateFrom, releaseDateTo);

        // 3. Index by PART_NUMBER (case-insensitive — input column may have mixed case).
        Map<String, Map<String, String>> byPartNum = new HashMap<>();
        for (Map<String, String> row : results) {
            String pn = row.get("PART_NUMBER");
            if (pn != null && !pn.isEmpty()) {
                byPartNum.put(pn.toUpperCase(Locale.ROOT), row);
            }
        }
        er.matchedItems = byPartNum.size();

        // 4. Header labels for the inserted columns. Suffix with "(from Part Extract)"
        //    so users can tell what the enrichment added vs their original headers.
        String[] newHeaders = new String[selectedColumns.size()];
        for (int i = 0; i < selectedColumns.size(); i++) {
            newHeaders[i] = selectedColumns.get(i) + " (from Part Extract)";
        }

        // 5. Write enriched workbook.
        try (InputStream is = file.getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            int sourceCol = parse.sourceColumn;
            int headerRow = parse.headerRowIndex;
            int lastRow = sheet.getLastRowNum();
            DataFormatter fmt = new DataFormatter();

            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // Shift cells from sourceCol+1 onwards right by N positions.
                int lastCellNum = row.getLastCellNum();
                for (int c = lastCellNum - 1; c > sourceCol; c--) {
                    Cell src = row.getCell(c);
                    if (src == null) continue;
                    Cell dst = row.createCell(c + newHeaders.length);
                    copyCell(src, dst);
                    row.removeCell(src);
                }

                if (r == headerRow) {
                    Cell ref = row.getCell(sourceCol);
                    CellStyle hStyle = ref != null ? ref.getCellStyle() : null;
                    for (int i = 0; i < newHeaders.length; i++) {
                        Cell c = row.createCell(sourceCol + 1 + i, CellType.STRING);
                        c.setCellValue(newHeaders[i]);
                        if (hStyle != null) c.setCellStyle(hStyle);
                    }
                } else {
                    Cell itemCell = row.getCell(sourceCol);
                    String itemNum = itemCell == null ? "" : fmt.formatCellValue(itemCell).trim();
                    Map<String, String> match = itemNum.isEmpty() ? null
                            : byPartNum.get(itemNum.toUpperCase(Locale.ROOT));
                    for (int i = 0; i < selectedColumns.size(); i++) {
                        Cell c = row.createCell(sourceCol + 1 + i, CellType.STRING);
                        if (match != null) {
                            String v = match.get(selectedColumns.get(i));
                            c.setCellValue(v == null ? "" : v);
                        } else {
                            c.setCellValue("");
                        }
                    }
                }
            }

            wb.write(out);
        }
        er.durationMs = System.currentTimeMillis() - t0;
        logger.info(String.format("[PARTS-ENRICH] rows=%d items=%d matched=%d cols=%d col=%d (%s) method=%s dur=%dms",
                er.totalRows, uniques.size(), er.matchedItems, selectedColumns.size(),
                er.sourceColumn, er.sourceHeader, er.detectionMethod, er.durationMs));
        return er;
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
}
