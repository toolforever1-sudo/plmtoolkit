package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.ChangeRecord;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExcelExportServiceTest {

    private ExcelExportService service;

    @BeforeEach
    void setUp() {
        service = new ExcelExportService();
    }

    @Test
    void export_createsValidWorkbook() throws Exception {
        List<ChangeRecord> records = Arrays.asList(
                new ChangeRecord("01-100", "Description", "Old desc", "New desc",
                        new Timestamp(System.currentTimeMillis()), "Zhu, Peter", "B"),
                new ChangeRecord("01-200", "Subcontractors", "C002", "C002, C067",
                        new Timestamp(System.currentTimeMillis()), "Kim, Sarah", "A")
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(records, out);

        assertTrue(out.size() > 0);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertEquals("Changes", sheet.getSheetName());
            assertEquals(2, sheet.getLastRowNum());
            assertEquals(7, sheet.getRow(0).getLastCellNum());
            assertEquals("Item Number", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("01-100", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("01-200", sheet.getRow(2).getCell(0).getStringCellValue());
        }
    }

    @Test
    void export_emptyList_createsHeaderOnly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(Arrays.asList(), out);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertEquals(0, sheet.getLastRowNum());
        }
    }
}
