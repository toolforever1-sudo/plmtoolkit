package com.sandisk.plm.tracker.util;

import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a small user-roster spreadsheet (.xlsx/.xls/.csv) into a header row +
 * data rows. Rosters are tiny (tens to a few hundred rows), so a full POI DOM
 * load is fine here — unlike the 778K-row item caches that need streaming.
 */
@org.springframework.stereotype.Component
public class UserSheetParser {

    /** Hard cap so a pasted-in giant file can't OOM the import path. */
    public static final int MAX_ROWS = 5000;

    /** Column cap — prevents a crafted 16K-column header from allocating MAX_ROWS × 16K strings. */
    public static final int MAX_COLS = 500;

    public static class ParsedSheet {
        public List<String> headers = new ArrayList<>();
        public List<List<String>> rows = new ArrayList<>();
        /** First up-to-3 data rows, for the AI column-mapping prompt. */
        public List<List<String>> sampleRows() {
            return rows.subList(0, Math.min(3, rows.size()));
        }
    }

    public ParsedSheet parse(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        ParsedSheet out;
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            out = parseCsv(file);
        } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            out = parseExcel(file);
        } else {
            throw new IllegalArgumentException("Unsupported file type. Use .xlsx, .xls, or .csv.");
        }
        if (out.headers.isEmpty()) {
            throw new IllegalArgumentException("The file appears to be empty — no header row found.");
        }
        return out;
    }

    private ParsedSheet parseExcel(MultipartFile file) throws Exception {
        ParsedSheet ps = new ParsedSheet();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            if (wb.getNumberOfSheets() == 0) return ps;
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            int firstRow = sheet.getFirstRowNum();
            int lastRow = Math.min(sheet.getLastRowNum(), firstRow + MAX_ROWS);
            int width = 0;
            Row header = sheet.getRow(firstRow);
            if (header != null) {
                // getLastCellNum() returns -1 for an empty row; clamp negative to 0, then cap at MAX_COLS
                width = Math.min(Math.max(header.getLastCellNum(), 0), MAX_COLS);
                for (int c = 0; c < width; c++) ps.headers.add(fmt.formatCellValue(header.getCell(c)).trim());
            }
            for (int r = firstRow + 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                boolean anyValue = false;
                for (int c = 0; c < width; c++) {
                    String v = fmt.formatCellValue(row.getCell(c)).trim();
                    if (!v.isEmpty()) anyValue = true;
                    cells.add(v);
                }
                if (anyValue) ps.rows.add(cells);
            }
        }
        return ps;
    }

    private ParsedSheet parseCsv(MultipartFile file) throws Exception {
        ParsedSheet ps = new ParsedSheet();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            int count = 0;
            while ((line = br.readLine()) != null) {
                if (first) {
                    for (String h : splitCsv(line)) ps.headers.add(h.trim());
                    first = false;
                    continue;
                }
                if (line.trim().isEmpty()) continue;
                if (count++ >= MAX_ROWS) break;
                List<String> cells = new ArrayList<>();
                for (String c : splitCsv(line)) cells.add(c.trim());
                ps.rows.add(cells);
            }
        }
        return ps;
    }

    /** Minimal CSV split handling double-quoted fields with embedded commas. */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
