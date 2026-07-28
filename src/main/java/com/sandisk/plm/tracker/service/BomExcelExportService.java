package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

@Service
public class BomExcelExportService {

    /** Maximum row index addressable in an XLSX sheet (POI hard-fails at
     *  {@code SXSSFSheet.createRow(1048576)}). Used by the pre-flight guard
     *  in {@link #export} so an oversized BOM rejects with a 400 + a helpful
     *  message instead of crashing mid-write and leaving ~2 GB of orphaned
     *  {@code BomResult} objects pinned in heap. The crash mode used to
     *  cascade into the heap-pressure alert (PT-ops, 2026-06-15). */
    public static final int EXCEL_MAX_ROW_COUNT = 1_048_576;

    /** Sheet preamble in every export: title (row 0), spacer (row 1),
     *  header (row 2). Data starts at row 3 — so the usable data-row budget
     *  per sheet is {@link #EXCEL_MAX_ROW_COUNT} minus the preamble. */
    private static final int SHEET_PREAMBLE_ROWS = 3;

    /** Thrown by {@link #export} when the result set would overflow Excel's
     *  per-sheet row cap. Carries the offending sheet name + needed row
     *  count so the controller can build a 400 response with a clear
     *  "reduce N to ≤ M" message. Runtime exception so it propagates through
     *  the existing throws-IOException signature without churning callers. */
    public static class ExcelRowLimitExceededException extends RuntimeException {
        public final String sheetName;
        public final int neededRows;
        public ExcelRowLimitExceededException(String sheetName, int neededRows) {
            super("Sheet '" + sheetName + "' would need " + neededRows
                + " rows; Excel allows at most " + EXCEL_MAX_ROW_COUNT + ".");
            this.sheetName = sheetName;
            this.neededRows = neededRows;
        }
    }

    // "Input PN" is the original input item the user typed. It's column B so users
    // can filter the Excel by their input even in multi-PN explosions. Path[0] in
    // explode mode = the root; Path[last] in implode mode = the input the user typed.
    // Direction-aware: Explode shows only Child BOM (the components below the input PN);
    // Where Used (implode) shows only Parent BOM (the assemblies the input PN feeds into).
    private static final String[] HEADERS_EXPLODE = {
            "Level", "Input PN", "Child BOM", "Qty", "Description", "BOM Notes",
            "Status", "Rev", "Item Type", "Lifecycle Phase", "Product Line",
            "Subcontractors", "Actual Build Plant", "Ref Designator", "Find #", "Path"
    };

    private static final String[] HEADERS_IMPLODE = {
            "Level", "Input PN", "Parent BOM", "Qty", "Description", "BOM Notes",
            "Status", "Rev", "Item Type", "Lifecycle Phase", "Product Line",
            "Subcontractors", "Actual Build Plant", "Ref Designator", "Find #", "Path"
    };

    /** First segment of the path string. The path is built as `itemNumber + bom_path`
     *  where bom_path comes from SYS_CONNECT_BY_PATH; the actual separator turns out
     *  to be either ">" or " > " depending on level, so split on the first ">" and
     *  trim — that always yields the root input SKU. */
    private static String rootSkuFromPath(String path) {
        if (path == null || path.isEmpty()) return "";
        int sep = path.indexOf('>');
        return (sep < 0 ? path : path.substring(0, sep)).trim();
    }

    public void export(List<BomResult> results, String title, OutputStream out) throws IOException {
        export(results, title, "explode", null, out);
    }

    public void export(List<BomResult> results, String title, String mode,
                        List<BomResult> deepTopLevels, OutputStream out) throws IOException {
        // Pre-flight row-count guard. Throw BEFORE allocating the workbook so
        // (1) the controller can map the failure to a clean 400 without
        // having written any response bytes, and (2) we don't pay the
        // ~2 GB heap cost of opening SXSSF + building styles only to crash
        // at row 1,048,576 deep inside POI. See EXCEL_MAX_ROW_COUNT comment.
        int needBomSheet = SHEET_PREAMBLE_ROWS + (results == null ? 0 : results.size());
        if (needBomSheet > EXCEL_MAX_ROW_COUNT) {
            throw new ExcelRowLimitExceededException("BOM", needBomSheet);
        }
        boolean isImplodeForCheck = "implode".equals(mode);
        if (isImplodeForCheck) {
            List<BomResult> topSourceCheck = (deepTopLevels != null && !deepTopLevels.isEmpty())
                    ? deepTopLevels : results;
            if (topSourceCheck != null) {
                java.util.HashSet<String> seenCheck = new java.util.HashSet<>();
                int topCount = 0;
                for (BomResult r : topSourceCheck) {
                    if (!r.isTopLevel()) continue;
                    String key = (r.getInputItem() != null ? r.getInputItem() : "") + "|" + r.getParent();
                    if (seenCheck.add(key)) topCount++;
                }
                int needTopSheet = SHEET_PREAMBLE_ROWS + topCount;
                if (needTopSheet > EXCEL_MAX_ROW_COUNT) {
                    throw new ExcelRowLimitExceededException("Top-Level Assemblies", needTopSheet);
                }
            }
        }

        // Inline strings — no shared-strings table. Same OOM-safety pattern
        // as ExcelExportService (see comment there for context).
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 1000, false, false);
        try {
            SXSSFSheet sheet = workbook.createSheet("BOM");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle borderStyle = workbook.createCellStyle();
            borderStyle.setBorderBottom(BorderStyle.THIN);
            borderStyle.setBorderTop(BorderStyle.THIN);
            borderStyle.setBorderLeft(BorderStyle.THIN);
            borderStyle.setBorderRight(BorderStyle.THIN);
            borderStyle.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            borderStyle.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            borderStyle.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            borderStyle.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());

            // Title row
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 12);
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);

            boolean isImplode = "implode".equals(mode);
            String[] headers = isImplode ? HEADERS_IMPLODE : HEADERS_EXPLODE;

            // Header row
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Data rows
            int rowIdx = 3;
            for (BomResult r : results) {
                Row row = sheet.createRow(rowIdx);

                row.createCell(0).setCellValue(r.getLevel());
                row.createCell(1).setCellValue(rootSkuFromPath(r.getPath()));
                // Direction-aware: Explode shows the child component, Where Used shows the parent assembly.
                // Only one of the two is emitted (PT-22).
                row.createCell(2).setCellValue(isImplode ? r.getParent() : r.getComponent());
                row.createCell(3).setCellValue(r.getQuantity());
                row.createCell(4).setCellValue(r.getDescription());
                row.createCell(5).setCellValue(r.getNotes());
                row.createCell(6).setCellValue(r.getStatus());
                row.createCell(7).setCellValue(r.getRev());
                row.createCell(8).setCellValue(r.getItemType());
                row.createCell(9).setCellValue(r.getLifecyclePhase() != null ? r.getLifecyclePhase() : "");
                row.createCell(10).setCellValue(r.getProductLine() != null ? r.getProductLine() : "");
                row.createCell(11).setCellValue(r.getSubcontractors() != null ? r.getSubcontractors() : "");
                row.createCell(12).setCellValue(r.getActualBuildPlant() != null ? r.getActualBuildPlant() : "");
                row.createCell(13).setCellValue(r.getRefDesignator());
                row.createCell(14).setCellValue(r.getFindNumber());
                row.createCell(15).setCellValue(r.getPath() != null ? r.getPath() : "");

                for (int c = 0; c < headers.length; c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null) cell.setCellStyle(borderStyle);
                }
                rowIdx++;
            }

            // Top-Level Assemblies tab for implode mode
            if (isImplode) {
                // Use deep top-level results if available, otherwise fall back to UI results
                List<BomResult> topSource = (deepTopLevels != null && !deepTopLevels.isEmpty())
                        ? deepTopLevels : results;

                if (!topSource.isEmpty()) {
                    SXSSFSheet topSheet = workbook.createSheet("Top-Level Assemblies");
                    Row topTitle = topSheet.createRow(0);
                    Cell topTitleCell = topTitle.createCell(0);
                    topTitleCell.setCellValue("Top-Level Assemblies for " + title.replace("BOM Where Used - ", "").replace("BOM Where Used ", ""));
                    topTitleCell.setCellStyle(titleStyle);

                    String[] topHeaders = {"Input PN", "Parent BOM", "Description", "Status",
                            "Item Type", "Lifecycle Phase", "Product Line", "Path"};
                    Row topHeaderRow = topSheet.createRow(2);
                    for (int i = 0; i < topHeaders.length; i++) {
                        Cell cell = topHeaderRow.createCell(i);
                        cell.setCellValue(topHeaders[i]);
                        cell.setCellStyle(headerStyle);
                    }

                    java.util.LinkedHashSet<String> seenKeys = new java.util.LinkedHashSet<>();
                    int topIdx = 3;
                    for (BomResult r : topSource) {
                        if (!r.isTopLevel()) continue;
                        String key = (r.getInputItem() != null ? r.getInputItem() : "") + "|" + r.getParent();
                        if (seenKeys.contains(key)) continue;
                        seenKeys.add(key);
                        Row tr = topSheet.createRow(topIdx++);
                        tr.createCell(0).setCellValue(r.getInputItem() != null ? r.getInputItem() : "");
                        tr.createCell(1).setCellValue(r.getParent());
                        tr.createCell(2).setCellValue(r.getDescription());
                        tr.createCell(3).setCellValue(r.getStatus());
                        tr.createCell(4).setCellValue(r.getItemType());
                        tr.createCell(5).setCellValue(r.getLifecyclePhase() != null ? r.getLifecyclePhase() : "");
                        tr.createCell(6).setCellValue(r.getProductLine() != null ? r.getProductLine() : "");
                        tr.createCell(7).setCellValue(r.getPath() != null ? r.getPath() : "");
                        for (int c = 0; c < topHeaders.length; c++) {
                            Cell cell = tr.getCell(c);
                            if (cell != null) cell.setCellStyle(borderStyle);
                        }
                    }
                    topSheet.setColumnWidth(0, 5000);
                    topSheet.setColumnWidth(1, 5000);
                    topSheet.setColumnWidth(2, 10000);
                    topSheet.setColumnWidth(3, 3000);
                    topSheet.setColumnWidth(4, 5000);
                    topSheet.setColumnWidth(5, 3500);
                    topSheet.setColumnWidth(6, 4000);
                    topSheet.setColumnWidth(7, 12000);
                    topSheet.createFreezePane(0, 3);
                    if (topIdx > 3) {
                        topSheet.setAutoFilter(new CellRangeAddress(2, 2, 0, topHeaders.length - 1));
                    }
                }
            }

            // Fixed column widths (16 columns: Level, Input PN, Parent BOM/Child BOM, Qty, Description,
            // BOM Notes, Status, Rev, Item Type, Lifecycle, Product Line, Subcontractors, Build Plant,
            // Ref Des, Find #, Path)
            int[] widths = {
                1800, 5000, 5000, 1800, 10000, 4000, 3000, 1800,
                5000, 3500, 4000, 5000, 4000, 5000, 1800, 12000
            };
            for (int i = 0; i < Math.min(widths.length, headers.length); i++) {
                sheet.setColumnWidth(i, widths[i]);
            }
            sheet.createFreezePane(0, 3);
            if (!results.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(2, 2, 0, headers.length - 1));
            }

            workbook.write(out);
        } finally {
            workbook.close();
            workbook.dispose();
        }
    }

    public void exportCompare(List<Map<String, Object>> diff, String bomA, String bomB,
                               OutputStream out) throws IOException {
        // Inline strings — no shared-strings table. Same OOM-safety pattern
        // as ExcelExportService (see comment there for context).
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 1000, false, false);
        try {
            SXSSFSheet sheet = workbook.createSheet("BOM Compare");

            // All compare fields in order
            String[][] fields = {
                {"qty", "Qty"}, {"description", "Description"}, {"itemType", "Item Type"},
                {"status", "Status"}, {"rev", "Rev"}, {"findNum", "Find #"},
                {"refDes", "Ref Designator"}, {"notes", "BOM Notes"}
            };
            int fCount = fields.length;
            // Column layout: [Component, f1..f8] | gap | [Component, f1..f8]
            int aStart = 0;
            int gapCol = 1 + fCount; // divider column
            int bStart = gapCol + 1;
            int totalCols = bStart + 1 + fCount;

            // Styles
            CellStyle headerAStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerAStyle.setFont(headerFont);
            headerAStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerAStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle headerBStyle = workbook.createCellStyle();
            Font headerBFont = workbook.createFont();
            headerBFont.setBold(true);
            headerBFont.setColor(IndexedColors.WHITE.getIndex());
            headerBStyle.setFont(headerBFont);
            headerBStyle.setFillForegroundColor(IndexedColors.INDIGO.getIndex());
            headerBStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle subHeaderAStyle = workbook.createCellStyle();
            Font subFont = workbook.createFont();
            subFont.setBold(true);
            subHeaderAStyle.setFont(subFont);
            subHeaderAStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            subHeaderAStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle subHeaderBStyle = workbook.createCellStyle();
            subHeaderBStyle.setFont(subFont);
            subHeaderBStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            subHeaderBStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle gapStyle = workbook.createCellStyle();
            gapStyle.setFillForegroundColor(IndexedColors.ORANGE.getIndex());
            gapStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle changedCellStyle = workbook.createCellStyle();
            changedCellStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            changedCellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font redFont = workbook.createFont();
            redFont.setColor(IndexedColors.RED.getIndex());
            redFont.setBold(true);
            changedCellStyle.setFont(redFont);

            CellStyle emptyRowStyle = workbook.createCellStyle();
            emptyRowStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            emptyRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle stripeStyle = workbook.createCellStyle();
            stripeStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            stripeStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Row 0: BOM A banner | gap | BOM B banner
            Row bannerRow = sheet.createRow(0);
            Cell aCell = bannerRow.createCell(aStart);
            aCell.setCellValue(bomA);
            aCell.setCellStyle(headerAStyle);
            for (int i = 1; i <= fCount; i++) bannerRow.createCell(aStart + i).setCellStyle(headerAStyle);
            bannerRow.createCell(gapCol).setCellStyle(gapStyle);
            Cell bCell = bannerRow.createCell(bStart);
            bCell.setCellValue(bomB);
            bCell.setCellStyle(headerBStyle);
            for (int i = 1; i <= fCount; i++) bannerRow.createCell(bStart + i).setCellStyle(headerBStyle);

            // Row 1: Field headers
            Row fieldRow = sheet.createRow(1);
            Cell c;
            c = fieldRow.createCell(aStart); c.setCellValue("Component"); c.setCellStyle(subHeaderAStyle);
            for (int i = 0; i < fCount; i++) {
                c = fieldRow.createCell(aStart + 1 + i); c.setCellValue(fields[i][1]); c.setCellStyle(subHeaderAStyle);
            }
            fieldRow.createCell(gapCol).setCellStyle(gapStyle);
            c = fieldRow.createCell(bStart); c.setCellValue("Component"); c.setCellStyle(subHeaderBStyle);
            for (int i = 0; i < fCount; i++) {
                c = fieldRow.createCell(bStart + 1 + i); c.setCellValue(fields[i][1]); c.setCellStyle(subHeaderBStyle);
            }

            // Data rows
            int rowIdx = 2;
            int rowNum = 0;
            for (Map<String, Object> r : diff) {
                Row row = sheet.createRow(rowIdx++);
                String ds = (String) r.getOrDefault("diffStatus", "");
                boolean isOnlyInA = "REMOVED".equals(ds);
                boolean isOnlyInB = "ADDED".equals(ds);
                boolean isChanged = "CHANGED".equals(ds);
                boolean stripe = (rowNum % 2 == 1);
                rowNum++;

                // A side
                if (isOnlyInB) {
                    for (int i = 0; i <= fCount; i++) {
                        c = row.createCell(aStart + i); c.setCellValue(""); c.setCellStyle(emptyRowStyle);
                    }
                } else {
                    row.createCell(aStart).setCellValue(str(r, "component"));
                    for (int i = 0; i < fCount; i++) {
                        String valA = str(r, fields[i][0] + "A");
                        String valB = str(r, fields[i][0] + "B");
                        c = row.createCell(aStart + 1 + i);
                        c.setCellValue(valA);
                        if (isChanged && !valA.equals(valB)) c.setCellStyle(changedCellStyle);
                        else if (stripe) c.setCellStyle(stripeStyle);
                    }
                    if (stripe && !isChanged) row.getCell(aStart).setCellStyle(stripeStyle);
                }

                // Gap
                row.createCell(gapCol).setCellStyle(gapStyle);

                // B side
                if (isOnlyInA) {
                    for (int i = 0; i <= fCount; i++) {
                        c = row.createCell(bStart + i); c.setCellValue(""); c.setCellStyle(emptyRowStyle);
                    }
                } else {
                    row.createCell(bStart).setCellValue(str(r, "component"));
                    for (int i = 0; i < fCount; i++) {
                        String valA = str(r, fields[i][0] + "A");
                        String valB = str(r, fields[i][0] + "B");
                        c = row.createCell(bStart + 1 + i);
                        c.setCellValue(valB);
                        if (isChanged && !valA.equals(valB)) c.setCellStyle(changedCellStyle);
                        else if (stripe) c.setCellStyle(stripeStyle);
                    }
                    if (stripe && !isChanged) row.getCell(bStart).setCellStyle(stripeStyle);
                }
            }

            // Column widths
            int[] fieldWidths = {3000, 12000, 5000, 3000, 2500, 3000, 5000, 5000}; // per field
            sheet.setColumnWidth(aStart, 5000); // Component A
            for (int i = 0; i < fCount; i++) sheet.setColumnWidth(aStart + 1 + i, fieldWidths[i]);
            sheet.setColumnWidth(gapCol, 400); // thin orange gap
            sheet.setColumnWidth(bStart, 5000); // Component B
            for (int i = 0; i < fCount; i++) sheet.setColumnWidth(bStart + 1 + i, fieldWidths[i]);
            sheet.createFreezePane(0, 2);

            workbook.write(out);
        } finally {
            workbook.close();
            workbook.dispose();
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }
}
