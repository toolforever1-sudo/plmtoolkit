package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SingleSoleSourceRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFCreationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Writes the Single/Sole Source xlsx by cloning the template's row-3 cell styles
 * onto each data row. Mirrors SSReport.java:writeSheet from the legacy Windows
 * batch — uniform styles, no per-row formatting.
 */
@Service
public class SingleSoleSourceExcelService {

    @Value("${app.singlesole.template}")
    private String templateLocation;

    @Autowired
    private ResourceLoader resourceLoader;

    /** PT-65 follow-up (May 27, 2026): Shruthi asked for three separate tabs —
     *  Designation Needed / Single Source / Sole Source — instead of the
     *  PT-54-era two-tab layout (Needed + a combined Provided). The template
     *  natively ships with all three sheets; we now write all three and stop
     *  removing the Sole Source tab. */
    private static final String SHEET_NEEDED = "Single MPN-Designation Needed";
    private static final String SHEET_SINGLE = "Single Source";
    private static final String SHEET_SOLE   = "Sole Source";
    /** Old → new tab-rename map; applied to the workbook on every run so the
     *  current template (which still has legacy names) doesn't need rebuilding. */
    private static final String LEGACY_NEEDED = "Designation Needed";
    private static final String LEGACY_SINGLE = "Single Source";
    private static final String LEGACY_SOLE   = "Sole Source";
    private static final int DATA_START_ROW = 2;   // 0-indexed; row 3 in Excel
    private static final int COL_COUNT = 15;       // A..O

    /** Returns counts in the order [needed, provided, single, sole]. {@code provided}
     *  is now {@code single + sole} since the Provided tab no longer exists in the
     *  output — kept in the shape for backward compatibility with email/log callers
     *  that still reference the slot. */
    public int[] write(List<SingleSoleSourceRow> all, OutputStream out) throws Exception {
        Resource res = resourceLoader.getResource(templateLocation);
        try (InputStream in = res.getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(in)) {

            // PT-65 follow-up: rename the legacy "Designation Needed" tab to the
            // spec-mandated SHEET_NEEDED label. SINGLE/SOLE tab names already match
            // the spec, so no rename needed there. We *keep* the Sole Source sheet
            // this time (PT-54-era code removed it); writing all three sheets is the
            // whole point of this change.
            renameSheet(wb, LEGACY_NEEDED, SHEET_NEEDED);

            // Split the rows by the field's literal value. "Needed" = the user hasn't
            // designated yet; Single/Sole = the explicit user choice. Each list goes
            // to its own sheet.
            List<SingleSoleSourceRow> neededRows = new ArrayList<>();
            List<SingleSoleSourceRow> singleRows = new ArrayList<>();
            List<SingleSoleSourceRow> soleRows = new ArrayList<>();
            for (SingleSoleSourceRow r : all) {
                String s = r.singleSoleSource == null ? "" : r.singleSoleSource.trim();
                if (LEGACY_NEEDED.equalsIgnoreCase(s)) {
                    neededRows.add(r);
                } else if (LEGACY_SINGLE.equalsIgnoreCase(s)) {
                    singleRows.add(r);
                } else if (LEGACY_SOLE.equalsIgnoreCase(s)) {
                    soleRows.add(r);
                }
            }

            // Needed sheet: (Current) column shows the literal word "Blank" per spec.
            XSSFSheet neededSheet = wb.getSheet(SHEET_NEEDED);
            if (neededSheet == null) throw new IllegalStateException("Template missing tab: " + SHEET_NEEDED);
            writeSheet(wb, neededSheet, neededRows, "Blank");
            activateA1(neededSheet);

            // Single Source sheet: (Current) column shows the actual designated value.
            XSSFSheet singleSheet = wb.getSheet(SHEET_SINGLE);
            if (singleSheet == null) throw new IllegalStateException("Template missing tab: " + SHEET_SINGLE);
            writeSheet(wb, singleSheet, singleRows, null);
            activateA1(singleSheet);

            // Sole Source sheet: same layout as Single Source.
            XSSFSheet soleSheet = wb.getSheet(SHEET_SOLE);
            if (soleSheet == null) throw new IllegalStateException("Template missing tab: " + SHEET_SOLE);
            writeSheet(wb, soleSheet, soleRows, null);
            activateA1(soleSheet);

            // Make the Needed sheet the home tab — per spec, that's what users should
            // see when they open the file, regardless of what the template had selected.
            int neededIdx = wb.getSheetIndex(SHEET_NEEDED);
            if (neededIdx >= 0) {
                wb.setActiveSheet(neededIdx);
                wb.setFirstVisibleTab(neededIdx);
            }

            wb.write(out);
            int providedTotal = singleRows.size() + soleRows.size();
            return new int[]{neededRows.size(), providedTotal, singleRows.size(), soleRows.size()};
        }
    }

    private static void renameSheet(XSSFWorkbook wb, String oldName, String newName) {
        int idx = wb.getSheetIndex(oldName);
        if (idx >= 0) wb.setSheetName(idx, newName);
    }

    /** Spec: "Activate/Select cell A1 after populating the data." Sets the active
     *  cell and scrolls the top-left into view so the user lands on A1. */
    private static void activateA1(XSSFSheet sheet) {
        sheet.setActiveCell(new CellAddress(0, 0));
        sheet.showInPane(0, 0);
    }

    /**
     * Write data rows starting at DATA_START_ROW. {@code currentOverride} controls
     * the value written to column G ("Single/Sole Source (Current)"): pass "Blank"
     * for the Needed sheet (spec), or null to use {@code row.singleSoleSource} verbatim
     * (Provided sheet).
     */
    private static void writeSheet(XSSFWorkbook wb, XSSFSheet sheet, List<SingleSoleSourceRow> rows, String currentOverride) {
        clearOldData(sheet, DATA_START_ROW);
        CellStyle[] styles = buildColStyles(wb, sheet, DATA_START_ROW, COL_COUNT);
        CellStyle dateStyleK = cloneAsDateOnly(wb, styles[10]);  // Create Date
        CellStyle dateStyleL = cloneAsDateOnly(wb, styles[11]);  // Last Release Date

        int r = DATA_START_ROW;
        for (SingleSoleSourceRow row : rows) {
            Row excelRow = sheet.getRow(r);
            if (excelRow == null) excelRow = sheet.createRow(r);
            setStr(excelRow, 0,  row.productLine,      styles);
            setStr(excelRow, 1,  row.number,           styles);
            setStr(excelRow, 2,  row.description,      styles);
            setStr(excelRow, 3,  row.lifecyclePhase,   styles);
            setStr(excelRow, 4,  row.rev,              styles);
            if (row.mpnCount != null) {
                Cell c = excelRow.createCell(5);
                c.setCellValue(row.mpnCount.intValue());
                if (styles[5] != null) c.setCellStyle(styles[5]);
            } else {
                setStr(excelRow, 5, "", styles);
            }
            String currentVal = (currentOverride != null) ? currentOverride : row.singleSoleSource;
            setStr(excelRow, 6,  currentVal,           styles);
            setStr(excelRow, 7,  "",                   styles);  // (To Be) — left blank, stakeholders fill
            setStr(excelRow, 8,  row.partType,         styles);
            setStr(excelRow, 9,  row.materialGroup,    styles);
            setDate(excelRow, 10, row.createDate,      dateStyleK);
            setDate(excelRow, 11, row.revReleaseDate,  dateStyleL);
            setStr(excelRow, 12, row.mfrName,          styles);
            setStr(excelRow, 13, row.mfrPartNumber,    styles);
            setStr(excelRow, 14, row.preferredStatus,  styles);
            r++;
        }
    }

    private static void clearOldData(XSSFSheet sheet, int startRow) {
        int last = sheet.getLastRowNum();
        for (int i = startRow; i <= last; i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            for (int j = 0; j < COL_COUNT; j++) {
                Cell c = row.getCell(j);
                if (c != null && c.getCellType() != CellType.FORMULA) {
                    c.setBlank();
                }
            }
        }
    }

    private static CellStyle[] buildColStyles(XSSFWorkbook wb, XSSFSheet s, int templateRow, int cols) {
        CellStyle[] out = new CellStyle[cols];
        Row tr = s.getRow(templateRow);
        if (tr == null) return out;
        for (int i = 0; i < cols; i++) {
            Cell tc = tr.getCell(i);
            if (tc != null) {
                XSSFCellStyle cs = wb.createCellStyle();
                cs.cloneStyleFrom(tc.getCellStyle());
                out[i] = cs;
            }
        }
        return out;
    }

    private static CellStyle cloneAsDateOnly(XSSFWorkbook wb, CellStyle base) {
        XSSFCellStyle ds = wb.createCellStyle();
        if (base != null) ds.cloneStyleFrom(base);
        XSSFCreationHelper helper = wb.getCreationHelper();
        ds.setDataFormat(helper.createDataFormat().getFormat("dd-MMM-yyyy"));
        return ds;
    }

    private static void setStr(Row r, int c, String v, CellStyle[] styles) {
        Cell cell = r.createCell(c);
        cell.setCellValue(v == null ? "" : v);
        if (styles != null && c < styles.length && styles[c] != null) cell.setCellStyle(styles[c]);
    }

    private static void setDate(Row r, int c, Date d, CellStyle style) {
        Cell cell = r.getCell(c);
        if (cell == null) cell = r.createCell(c);
        if (d == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(d);
            if (style != null) cell.setCellStyle(style);
        }
    }
}
