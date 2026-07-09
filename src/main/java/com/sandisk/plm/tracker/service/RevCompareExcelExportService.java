package com.sandisk.plm.tracker.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

@Service
public class RevCompareExcelExportService {

    public void exportCompare(List<Map<String, Object>> diff, String partNumber,
                              String labelA, String labelB, OutputStream out) throws IOException {
        // Inline strings — no shared-strings table (OOM-safety).
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 1000, false, false);
        try {
            SXSSFSheet sheet = workbook.createSheet("Rev Compare");

            // Compare fields in order
            String[][] fields = {
                {"qty", "Qty"}, {"description", "Description"}, {"componentRev", "Comp Rev"},
                {"componentChange", "Comp Change"}, {"bomType", "Type"}, {"seqNum", "Seq#"},
                {"primaryPn", "Primary P/N"}, {"refDes", "Ref Designator"}, {"notes", "BOM Notes"}
            };
            int fCount = fields.length;
            // Column layout: [Component, f1..f9] | gap | [Component, f1..f9]
            int aStart = 0;
            int gapCol = 1 + fCount; // divider column
            int bStart = gapCol + 1;

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

            // Row 0: Rev A banner | gap | Rev B banner
            Row bannerRow = sheet.createRow(0);
            Cell aCell = bannerRow.createCell(aStart);
            aCell.setCellValue(partNumber + " — Rev " + labelA);
            aCell.setCellStyle(headerAStyle);
            for (int i = 1; i <= fCount; i++) bannerRow.createCell(aStart + i).setCellStyle(headerAStyle);
            bannerRow.createCell(gapCol).setCellStyle(gapStyle);
            Cell bCell = bannerRow.createCell(bStart);
            bCell.setCellValue(partNumber + " — Rev " + labelB);
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
            int[] fieldWidths = {3000, 12000, 3000, 5000, 3000, 2500, 5000, 5000, 5000};
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
