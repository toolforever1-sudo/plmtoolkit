package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.EcoTimelineRow;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.List;

@Service
public class EcoTimelineExcelExportService {

    private static final String[] HEADERS = {
        "Level", "Path", "Component #", "Primary #", "Description",
        "ECO #", "ECO Description", "ECO Status", "ECO Release Date", "Change Type", "Detail"
    };

    public void exportTimeline(List<EcoTimelineRow> rows, String item, String from, String to,
                               OutputStream out) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("ECO Timeline");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Title row (item + window)
            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue(
                "ECO Timeline — " + item + "  (" + from + " to " + to + ")");

            Row head = sheet.createRow(2);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }

            int r = 3;
            for (EcoTimelineRow row : rows) {
                Row dr = sheet.createRow(r++);
                dr.createCell(0).setCellValue(row.getLevel());
                dr.createCell(1).setCellValue(row.getPath());
                dr.createCell(2).setCellValue(row.getComponent());
                dr.createCell(3).setCellValue(row.getPrimaryNumber());
                dr.createCell(4).setCellValue(row.getComponentDescription());
                dr.createCell(5).setCellValue(row.getEcoNumber());
                dr.createCell(6).setCellValue(row.getEcoDescription());
                dr.createCell(7).setCellValue(row.getEcoStatus());
                dr.createCell(8).setCellValue(row.getEcoReleaseDate());
                dr.createCell(9).setCellValue(row.getChangeType());
                dr.createCell(10).setCellValue(row.getDetail());
            }

            for (int i = 0; i < HEADERS.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
        }
    }
}
