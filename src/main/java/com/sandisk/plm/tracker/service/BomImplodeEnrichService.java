package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import com.sandisk.plm.tracker.util.FileItemParser;
import com.sandisk.plm.tracker.util.UploadColumnDetector;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.logging.Logger;

/**
 * BOM Implode (Where-Used) enrichment: takes a user-uploaded Excel file,
 * detects the item-number column, runs the deep where-used traversal for each
 * unique item, and writes back the same workbook with one new column inserted
 * right after the item-number column listing every distinct top-level assembly
 * that rolls up to the input item.
 *
 * <p>Useful for the question: "for this list of components, which final
 * products use them?"</p>
 */
@Service
public class BomImplodeEnrichService {

    private static final Logger logger = Logger.getLogger(BomImplodeEnrichService.class.getName());

    private static final String NEW_HEADER = "Top-Level Parent(s) (from BOM Implode)";

    @Autowired private BomDataService bomDataService;
    @Autowired private UploadColumnDetector columnDetector;

    public static class EnrichResult {
        public int totalRows;
        public int matchedItems;
        public int sourceColumn;
        public String sourceHeader;
        public String detectionMethod;
        public long durationMs;
        /** True when the input was an OLE2 binary .xls (HSSF), false for OOXML .xlsx (XSSF).
         *  Set by the service after the workbook is opened so the controller can pick the
         *  matching response content-type and filename extension. */
        public boolean isHssf;
    }

    /** Thrown when a BOM enrich request exceeds the per-call row cap. Caller
     *  translates to 413 before any output is written. See PT-29/PT-30 follow-up
     *  pattern in PartsEnrichService. */
    public static class RowCapExceededException extends IOException {
        public RowCapExceededException(String msg) { super(msg); }
    }

    public EnrichResult enrich(MultipartFile file,
                                int itemColumnOverride,
                                OutputStream out) throws IOException {
        return enrich(file, itemColumnOverride, out, Integer.MAX_VALUE);
    }

    public EnrichResult enrich(MultipartFile file,
                                int itemColumnOverride,
                                OutputStream out,
                                int maxRows) throws IOException {
        long t0 = System.currentTimeMillis();
        EnrichResult er = new EnrichResult();

        FileItemParser.ParseResult parse =
                FileItemParser.parseItemsWithDetection(file, columnDetector, itemColumnOverride);
        er.sourceColumn = parse.sourceColumn;
        er.sourceHeader = parse.sourceHeader;
        er.detectionMethod = parse.method;
        er.totalRows = parse.totalRows;

        // Hard row-count cap. The 825K-row BOM-Implosion file that brought
        // prod down passed the size-based HeapGuard estimate but POI XSSF DOM
        // needs ~30-40× file size for that many cells. Reject loudly before
        // we let it OOM the JVM.
        if (er.totalRows > maxRows) {
            throw new RowCapExceededException("This file has " + er.totalRows + " rows, but BOM Where-Used enrichment is capped at "
                + maxRows + " rows to protect server memory. Please split the file or use the database query instead.");
        }

        // One traversal across all unique inputs; group results by the input item that produced them.
        List<String> uniques = parse.distinctItems();
        List<BomResult> tops = bomDataService.findTopLevelAssemblies(uniques);

        // For each input, accumulate a set of distinct top-level parent identifiers.
        Map<String, java.util.LinkedHashSet<String>> topsByInput = new HashMap<>();
        for (BomResult br : tops) {
            String input = br.getInputItem();
            if (input == null) continue;
            String parent = br.getParent();
            if (parent == null || parent.isEmpty()) continue;
            topsByInput.computeIfAbsent(input.toUpperCase(Locale.ROOT),
                    k -> new java.util.LinkedHashSet<>()).add(parent);
        }
        er.matchedItems = topsByInput.size();

        // PT-46: bug repro — hard-coded XSSFWorkbook threw OLE2NotOfficeXmlFileException
        // on a 49 KB .xls (HSSF) upload from Jimmy. FileItemParser already routed through
        // WorkbookFactory.create() so the parse step succeeded; the enrich step blew up
        // on the same file. Using the same factory here heals both formats — Sheet/Row/Cell
        // are interfaces so the rest of the code is format-agnostic.
        try (InputStream is = file.getInputStream();
             Workbook wb = WorkbookFactory.create(is)) {
            er.isHssf = wb instanceof org.apache.poi.hssf.usermodel.HSSFWorkbook;
            Sheet sheet = wb.getSheetAt(0);
            int sourceCol = parse.sourceColumn;
            int headerRow = parse.headerRowIndex;
            int lastRow = sheet.getLastRowNum();
            DataFormatter fmt = new DataFormatter();

            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // Shift everything right by 1 column.
                int lastCellNum = row.getLastCellNum();
                for (int c = lastCellNum - 1; c > sourceCol; c--) {
                    Cell src = row.getCell(c);
                    if (src == null) continue;
                    Cell dst = row.createCell(c + 1);
                    copyCell(src, dst);
                    row.removeCell(src);
                }

                if (r == headerRow) {
                    Cell ref = row.getCell(sourceCol);
                    Cell c = row.createCell(sourceCol + 1, CellType.STRING);
                    c.setCellValue(NEW_HEADER);
                    if (ref != null && ref.getCellStyle() != null) c.setCellStyle(ref.getCellStyle());
                } else {
                    Cell itemCell = row.getCell(sourceCol);
                    String itemNum = itemCell == null ? "" : fmt.formatCellValue(itemCell).trim();
                    java.util.LinkedHashSet<String> parents = itemNum.isEmpty() ? null
                            : topsByInput.get(itemNum.toUpperCase(Locale.ROOT));
                    Cell c = row.createCell(sourceCol + 1, CellType.STRING);
                    c.setCellValue(joinParents(parents));
                }
            }

            wb.write(out);
        }
        er.durationMs = System.currentTimeMillis() - t0;
        logger.info(String.format("[BOM-IMPLODE-ENRICH] rows=%d items=%d matched=%d col=%d (%s) method=%s dur=%dms",
                er.totalRows, uniques.size(), er.matchedItems,
                er.sourceColumn, er.sourceHeader, er.detectionMethod, er.durationMs));
        return er;
    }

    /**
     * Join a set of parent item numbers into the single comma-separated cell value
     * we write into the "Top-Level Parent(s)" column. Excel's per-cell text cap is
     * 32,767 characters — common ASIC dies roll up into hundreds of top-level
     * assemblies and the joined list blew past that on the PT-46 file. We stop
     * appending once a hard budget is reached and add a {@code (+N more)} suffix
     * so the truncation is visible to the user instead of silent.
     */
    static String joinParents(java.util.Collection<String> parents) {
        if (parents == null || parents.isEmpty()) return "";
        final int HARD_CAP = 32_700;   // leaves headroom for the "(+N more)" tail
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        int dropped = 0;
        for (String p : parents) {
            if (p == null || p.isEmpty()) continue;
            int sep = sb.length() == 0 ? 0 : 2;  // ", "
            if (sb.length() + sep + p.length() > HARD_CAP) {
                dropped = parents.size() - kept;
                break;
            }
            if (sep > 0) sb.append(", ");
            sb.append(p);
            kept++;
        }
        if (dropped > 0) {
            // Re-count the actual remainder — `parents` may include empty entries we skipped.
            int remaining = parents.size() - kept;
            sb.append(" (+").append(remaining).append(" more)");
        }
        return sb.toString();
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
