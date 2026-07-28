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
 * SKU Lookup enrichment: takes a user-uploaded Excel file, detects the
 * item-number column, looks up each SKU in the JSON cache, and writes back
 * the same workbook with one new column per requested field inserted right
 * after the item-number column.
 *
 * <p>Mirrors {@link PartsEnrichService} — same shift-and-insert approach.</p>
 */
@Service
public class SkuEnrichService {

    private static final Logger logger = Logger.getLogger(SkuEnrichService.class.getName());

    @Autowired private SkuDataService skuDataService;
    @Autowired private UploadColumnDetector columnDetector;

    public static class EnrichResult {
        public int totalRows;
        public int matchedItems;
        public int sourceColumn;
        public String sourceHeader;
        public String detectionMethod;
        public long durationMs;
    }

    public EnrichResult enrich(MultipartFile file,
                                List<String> selectedFields,
                                int itemColumnOverride,
                                OutputStream out) throws IOException {
        long t0 = System.currentTimeMillis();
        EnrichResult er = new EnrichResult();

        FileItemParser.ParseResult parse =
                FileItemParser.parseItemsWithDetection(file, columnDetector, itemColumnOverride);
        er.sourceColumn = parse.sourceColumn;
        er.sourceHeader = parse.sourceHeader;
        er.detectionMethod = parse.method;
        er.totalRows = parse.totalRows;

        // Look up each unique item once.
        Map<String, Map<String, Object>> recordByItem = new HashMap<>();
        for (String item : parse.distinctItems()) {
            Object rec = skuDataService.getRecord(item);
            if (rec instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) rec;
                recordByItem.put(item.toUpperCase(Locale.ROOT), m);
            }
        }
        er.matchedItems = recordByItem.size();

        String[] newHeaders = new String[selectedFields.size()];
        for (int i = 0; i < selectedFields.size(); i++) {
            newHeaders[i] = selectedFields.get(i) + " (from SKU Lookup)";
        }

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
                    Map<String, Object> rec = itemNum.isEmpty() ? null
                            : recordByItem.get(itemNum.toUpperCase(Locale.ROOT));
                    for (int i = 0; i < selectedFields.size(); i++) {
                        Cell c = row.createCell(sourceCol + 1 + i, CellType.STRING);
                        if (rec != null) {
                            Object v = rec.get(selectedFields.get(i));
                            c.setCellValue(v == null ? "" : v.toString());
                        } else {
                            c.setCellValue("");
                        }
                    }
                }
            }

            wb.write(out);
        }
        er.durationMs = System.currentTimeMillis() - t0;
        logger.info(String.format("[SKU-ENRICH] rows=%d matched=%d fields=%d col=%d (%s) method=%s dur=%dms",
                er.totalRows, er.matchedItems, selectedFields.size(),
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
