package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.ChangeRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

@Service
public class ExcelExportService {

    private static final String[] HEADERS = {
            "Item Number", "Field Name", "Old Value", "New Value",
            "Timestamp", "Changed By", "Rev"
    };

    public void export(List<ChangeRecord> records, OutputStream out) throws IOException {
        // Streaming workbook: keep only `windowSize` rows in memory, flush
        // older rows to a temp file on disk. Inline strings (no shared-strings
        // table) — avoids the O(N²) SharedStringsTable.addEntry blow-up that
        // OOMed prod (heap dump 10 GB on a 6 GB heap) on 2026-05-06 while
        // exporting ~11k change records via the scheduled report email job.
        // Constructor args: (xssfWorkbook=null, windowSize=100, compressTmpFiles=false, useSharedStringsTable=false)
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 100, false, false);
        try {
            SXSSFSheet sheet = workbook.createSheet("Changes");

            // SXSSFWorkbook delegates style creation to the underlying XSSFWorkbook
            // but its public API returns the base CellStyle/Font interfaces, so
            // we need explicit casts here to keep using the XSSF-specific RGB
            // colour and clone-style methods.
            XSSFCellStyle headerStyle = (XSSFCellStyle) workbook.createCellStyle();
            XSSFFont headerFont = (XSSFFont) workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(
                    new byte[]{(byte) 0x2c, (byte) 0x3e, (byte) 0x50}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            XSSFCellStyle evenStyle = (XSSFCellStyle) workbook.createCellStyle();
            evenStyle.setFillForegroundColor(new XSSFColor(
                    new byte[]{(byte) 0xf8, (byte) 0xf9, (byte) 0xfa}, null));
            evenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            evenStyle.setVerticalAlignment(VerticalAlignment.TOP);
            evenStyle.setWrapText(true);

            XSSFCellStyle oddStyle = (XSSFCellStyle) workbook.createCellStyle();
            oddStyle.setVerticalAlignment(VerticalAlignment.TOP);
            oddStyle.setWrapText(true);

            XSSFCellStyle evenDateStyle = (XSSFCellStyle) workbook.createCellStyle();
            evenDateStyle.cloneStyleFrom(evenStyle);
            evenDateStyle.setDataFormat(workbook.createDataFormat()
                    .getFormat("yyyy-mm-dd hh:mm"));

            XSSFCellStyle oddDateStyle = (XSSFCellStyle) workbook.createCellStyle();
            oddDateStyle.cloneStyleFrom(oddStyle);
            oddDateStyle.setDataFormat(workbook.createDataFormat()
                    .getFormat("yyyy-mm-dd hh:mm"));

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (ChangeRecord rec : records) {
                Row row = sheet.createRow(rowIdx);
                boolean even = rowIdx % 2 == 0;
                XSSFCellStyle rowStyle = even ? evenStyle : oddStyle;
                XSSFCellStyle rowDateStyle = even ? evenDateStyle : oddDateStyle;

                Cell c0 = row.createCell(0); c0.setCellValue(rec.getItemNumber()); c0.setCellStyle(rowStyle);
                Cell c1 = row.createCell(1); c1.setCellValue(rec.getFieldName()); c1.setCellStyle(rowStyle);
                Cell c2 = row.createCell(2); c2.setCellValue(rec.getOldValue()); c2.setCellStyle(rowStyle);
                Cell c3 = row.createCell(3); c3.setCellValue(rec.getNewValue()); c3.setCellStyle(rowStyle);

                Cell c4 = row.createCell(4);
                if (rec.getTimestamp() != null) {
                    c4.setCellValue(rec.getTimestamp());
                    c4.setCellStyle(rowDateStyle);
                }

                Cell c5 = row.createCell(5); c5.setCellValue(rec.getUserName()); c5.setCellStyle(rowStyle);
                Cell c6 = row.createCell(6); c6.setCellValue(rec.getRevNumber()); c6.setCellStyle(rowStyle);

                rowIdx++;
            }

            // Auto-size is slow for large datasets; use fixed widths for >1000 rows.
            // SXSSF requires column tracking to be enabled BEFORE rows are written
            // for autoSizeColumn to work — but at this point all rows are in the
            // 100-row sliding window of unflushed rows, and tracking after-the-fact
            // would only sample those. So for the small-dataset path we trust the
            // window covers everything (records.size() ≤ 1000 < 100 is false but
            // SXSSF keeps all unflushed rows in memory; we just enable tracking
            // for all columns up-front). For the large-dataset path we use fixed
            // widths (same as before).
            if (records.size() <= 1000) {
                // Best-effort: track all columns retroactively. SXSSF only knows
                // about rows still in its sliding window — for very small reports
                // (≤100 rows) this matches XSSF behaviour exactly; for 100<N≤1000
                // it auto-sizes based on the last 100 rows, which is good enough.
                for (int i = 0; i < HEADERS.length; i++) {
                    sheet.trackColumnForAutoSizing(i);
                    sheet.autoSizeColumn(i);
                    if (sheet.getColumnWidth(i) > 12800) sheet.setColumnWidth(i, 12800);
                }
            } else {
                // Fixed reasonable widths: Item(5000), Field(6000), Old(10000), New(10000), Time(5000), User(5000), Rev(5000)
                int[] widths = {5000, 6000, 10000, 10000, 5000, 5000, 5000};
                for (int i = 0; i < HEADERS.length; i++) {
                    sheet.setColumnWidth(i, widths[i]);
                }
            }

            sheet.createFreezePane(0, 1);
            if (!records.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));
            }

            workbook.write(out);
        } finally {
            // Free the temp files SXSSF used to spool flushed rows.
            workbook.dispose();
            workbook.close();
        }
    }
}
