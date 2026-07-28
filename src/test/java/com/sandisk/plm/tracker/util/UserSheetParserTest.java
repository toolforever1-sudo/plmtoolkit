package com.sandisk.plm.tracker.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class UserSheetParserTest {

    private MockMultipartFile xlsxOf(String name, String[][] grid) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Sheet1");
            for (int r = 0; r < grid.length; r++) {
                Row row = sh.createRow(r);
                for (int c = 0; c < grid[r].length; c++) row.createCell(c).setCellValue(grid[r][c]);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return new MockMultipartFile("file", name, "application/vnd.ms-excel", bos.toByteArray());
        }
    }

    @Test
    void parsesXlsxHeadersAndRows() throws Exception {
        MockMultipartFile f = xlsxOf("test.xlsx", new String[][]{
            {"Name", "Email"},
            {"Philip Tam", "Philip.Tam@sandisk.com"},
            {"Eva Lu", "eva.lu@sandisk.com"}
        });
        UserSheetParser.ParsedSheet ps = new UserSheetParser().parse(f);
        assertEquals(2, ps.headers.size());
        assertEquals("Name", ps.headers.get(0));
        assertEquals("Email", ps.headers.get(1));
        assertEquals(2, ps.rows.size());
        assertEquals("Philip Tam", ps.rows.get(0).get(0));
        assertEquals("eva.lu@sandisk.com", ps.rows.get(1).get(1));
    }

    @Test
    void parsesCsv() throws Exception {
        String csv = "Name,Email\nPhilip Tam,Philip.Tam@sandisk.com\n";
        MockMultipartFile f = new MockMultipartFile("file", "test.csv", "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));
        UserSheetParser.ParsedSheet ps = new UserSheetParser().parse(f);
        assertEquals(2, ps.headers.size());
        assertEquals(1, ps.rows.size());
        assertEquals("Philip Tam", ps.rows.get(0).get(0));
    }

    @Test
    void rejectsEmptySheet() throws Exception {
        MockMultipartFile f = xlsxOf("empty.xlsx", new String[][]{});
        UserSheetParser p = new UserSheetParser();
        assertThrows(IllegalArgumentException.class, () -> p.parse(f));
    }

    @Test
    void splitCsvHandlesQuotedComma() throws Exception {
        // "Doe, Jane" is a single field containing a comma — must not be split
        String csv = "\"Doe, Jane\",jane@sandisk.com\n";
        MockMultipartFile f = new MockMultipartFile("file", "test.csv", "text/csv",
            ("Name,Email\n" + csv).getBytes(StandardCharsets.UTF_8));
        UserSheetParser.ParsedSheet ps = new UserSheetParser().parse(f);
        assertEquals(1, ps.rows.size());
        assertEquals("Doe, Jane", ps.rows.get(0).get(0));
        assertEquals("jane@sandisk.com", ps.rows.get(0).get(1));
    }

    @Test
    void rejectsUnsupportedFileType() {
        MockMultipartFile f = new MockMultipartFile("file", "roster.pdf", "application/pdf",
            new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF magic bytes
        UserSheetParser p = new UserSheetParser();
        assertThrows(IllegalArgumentException.class, () -> p.parse(f));
    }
}
