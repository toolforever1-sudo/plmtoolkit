package com.sandisk.plm.tracker.controller;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity tests for the SAX streaming workbook reads that replaced the
 * XSSFWorkbook DOM opens (2026-07-08 OOM fix). The fixture mirrors the real
 * Team Report layout: summary rows on 'Total Changes Process' /
 * 'Total Volume Process2026', raw rows on 'Raw data-No Dup' /
 * 'Raw data-affected item', and the two lookup sheets.
 */
public class TeamReportScanTest {

    @TempDir
    static Path tmp;

    static File fixture;

    @BeforeAll
    static void buildFixture() throws IOException {
        fixture = tmp.resolve("Team_Report_2026_Feb.xlsx").toFile();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {

            // PCMs in D5..D6 (0-based row 4..5, col 3); Jan block E..H, Feb block I..L.
            Sheet cps = wb.createSheet("Total Changes Process");
            Row c5 = cps.createRow(4);
            c5.createCell(3).setCellValue("Alice "); // trailing space — must trim
            c5.createCell(4).setCellValue(1);  // Jan AML
            c5.createCell(5).setCellValue(2);  // Jan ECO
            c5.createCell(6).setCellValue(3);  // Jan MCO
            c5.createCell(7).setCellValue(4);  // Jan ECN
            c5.createCell(8).setCellValue(5);  // Feb AML
            c5.createCell(9).setCellValue(6);  // Feb ECO
            c5.createCell(10).setCellValue("7"); // Feb MCO as string — must still parse
            c5.createCell(11).setCellValue(8); // Feb ECN
            Row c6 = cps.createRow(5);
            c6.createCell(3).setCellValue("Bob");
            c6.createCell(4).setCellValue(10);
            c6.createCell(5).setCellValue(20);
            c6.createCell(6).setCellValue(30);
            c6.createCell(7).setCellValue(40);

            // Volume: PCM rows 3..4 (0-based 2..3), Jan block B..E, Feb block F..I.
            Sheet vps = wb.createSheet("Total Volume Process2026");
            Row v3 = vps.createRow(2);
            v3.createCell(0).setCellValue("Alice");
            v3.createCell(1).setCellValue(100); // Jan AML items
            v3.createCell(2).setCellValue(200); // Jan ECO items
            v3.createCell(3).setCellValue(300); // Jan MCO items
            v3.createCell(4).setCellValue(9);   // Jan ECN
            v3.createCell(5).setCellValue(1);   // Feb AML
            v3.createCell(6).setCellValue(1);   // Feb ECO
            v3.createCell(7).setCellValue(1);   // Feb MCO
            v3.createCell(8).setCellValue(1);   // Feb ECN
            vps.createRow(3).createCell(0).setCellValue("Bob");

            Sheet cls = wb.createSheet("Classification Change Type");
            cls.createRow(0).createCell(0).setCellValue("Classification"); // header
            Row cl1 = cls.createRow(1);
            cl1.createCell(0).setCellValue("BOM Update");
            cl1.createCell(1).setCellValue("Data Aligment"); // sic — must normalize
            Row cl2 = cls.createRow(2);
            cl2.createCell(0).setCellValue("Doc Change");
            cl2.createCell(1).setCellValue("Documentation");

            Sheet noDup = wb.createSheet("Raw data-No Dup");
            noDup.createRow(0).createCell(14).setCellValue("Month"); // header
            Row n1 = noDup.createRow(1);
            n1.createCell(14).setCellValue("Feb_2026");
            n1.createCell(15).setCellValue("BOM Update");
            n1.createCell(6).setCellValue("PL1|PL2");
            Row n2 = noDup.createRow(2);
            n2.createCell(14).setCellValue("Jan_2026");
            n2.createCell(15).setCellValue("Doc Change"); // wrong month — no activity count
            n2.createCell(6).setCellValue("PL1");
            Row n3 = noDup.createRow(3);
            n3.createCell(14).setCellValue("Feb_2025"); // wrong year — ignored entirely
            n3.createCell(6).setCellValue("PL1");
            Row n4 = noDup.createRow(4);
            n4.createCell(14).setCellValue("Feb_2026");
            n4.createCell(15).setCellValue("Unknown Cls"); // unmapped — not counted

            Sheet pt = wb.createSheet("Program Team");
            pt.createRow(0).createCell(0).setCellValue("Product Line"); // header
            Row p1 = pt.createRow(1);
            p1.createCell(0).setCellValue("PL1");
            p1.createCell(1).setCellValue("Team X");
            Row p2 = pt.createRow(2);
            p2.createCell(0).setCellValue("PL2");
            p2.createCell(1).setCellValue("Team Y");

            Sheet raw = wb.createSheet("Raw data-affected item");
            raw.createRow(0).createCell(14).setCellValue("Month"); // header
            Row r1 = raw.createRow(1);
            r1.createCell(2).setCellValue("desc1");
            r1.createCell(3).setCellValue("Released");
            r1.createCell(5).setCellValue("ECO");
            r1.createCell(6).setCellValue("PL1");
            r1.createCell(7).setCellValue("Urgent");
            r1.createCell(14).setCellValue("Feb_2026");
            r1.createCell(15).setCellValue("BOM Update");
            Row r2 = raw.createRow(2);
            r2.createCell(6).setCellValue("PL1;PL2"); // multi-line → Mix
            r2.createCell(14).setCellValue("Feb_2026");
            Row r3 = raw.createRow(3);
            r3.createCell(6).setCellValue("PL1");
            r3.createCell(14).setCellValue("Jan_2026"); // wrong month — filtered
            Row r4 = raw.createRow(4);
            r4.createCell(6).setCellValue("PLZ"); // unmapped → Mix
            r4.createCell(14).setCellValue("Feb_2026");

            try (FileOutputStream fos = new FileOutputStream(fixture)) {
                wb.write(fos);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scanWorkbookDataMatchesDomSemantics() throws IOException {
        Map<String, Object> core = TeamReportController.scanWorkbookData(fixture, "Feb_2026");

        assertEquals(List.of("Jan", "Feb"), core.get("months"));
        assertEquals(List.of("Alice", "Bob"), core.get("pcms"));

        Map<String, Map<String, int[]>> changes = (Map<String, Map<String, int[]>>) core.get("changes");
        assertArrayEquals(new int[]{1, 2, 3, 4}, changes.get("Alice").get("Jan"));
        assertArrayEquals(new int[]{5, 6, 7, 8}, changes.get("Alice").get("Feb")); // "7" via string cell
        assertArrayEquals(new int[]{10, 20, 30, 40}, changes.get("Bob").get("Jan"));
        assertArrayEquals(new int[]{0, 0, 0, 0}, changes.get("Bob").get("Feb")); // blank cells → 0

        Map<String, Map<String, int[]>> volume = (Map<String, Map<String, int[]>>) core.get("volume");
        assertArrayEquals(new int[]{100, 200, 300, 9}, volume.get("Alice").get("Jan"));
        assertArrayEquals(new int[]{1, 1, 1, 1}, volume.get("Alice").get("Feb"));

        // ytd Alice: items = (100+200+300)+(1+1+1)=603; ecn = 9+1=10;
        // xco = (1+2+3)+(5+6+7)=24
        Map<String, int[]> ytd = (Map<String, int[]>) core.get("ytd");
        assertArrayEquals(new int[]{603, 24, 10}, ytd.get("Alice"));

        // Only the Feb_2026 'BOM Update' row counts, mapped through the lookup
        // with the Aligment→Alignment normalization.
        List<Map<String, Object>> activity = (List<Map<String, Object>>) core.get("activityTypes");
        assertEquals(1, activity.size());
        assertEquals("Data Alignment", activity.get(0).get("name"));
        assertEquals(1, activity.get(0).get("count"));

        Map<String, Map<String, Integer>> pl = (Map<String, Map<String, Integer>>) core.get("ecnByProductLine");
        assertEquals(Integer.valueOf(1), pl.get("Jan").get("PL1"));
        assertEquals(Integer.valueOf(1), pl.get("Feb").get("PL1"));
        assertEquals(Integer.valueOf(1), pl.get("Feb").get("PL2"));
        assertNull(pl.get("Feb").get("PLZ")); // PLZ only exists on the affected-item sheet, not No-Dup
    }

    @Test
    void scanGroupedForMonthGroupsAndCollapsesToMix() throws IOException {
        Map<String, List<TeamReportController.RowData>> grouped =
                TeamReportController.scanGroupedForMonth(fixture, "Feb_2026");

        assertEquals(2, grouped.size());
        assertEquals(1, grouped.get("Team X").size());
        assertEquals(2, grouped.get("Mix").size()); // "PL1;PL2" + unmapped "PLZ"

        TeamReportController.RowData rd = grouped.get("Team X").get(0);
        assertEquals("desc1", rd.description);
        assertEquals("Released", rd.status);
        assertEquals("ECO", rd.changeType);
        assertEquals("PL1", rd.productLines);
        assertEquals("Urgent", rd.priority);
        assertEquals("BOM Update", rd.classifications);
    }

    @Test
    void missingSummarySheetThrows() throws IOException {
        File bare = tmp.resolve("bare.xlsx").toFile();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Something Else");
            try (FileOutputStream fos = new FileOutputStream(bare)) {
                wb.write(fos);
            }
        }
        IOException ex = assertThrows(IOException.class,
                () -> TeamReportController.scanWorkbookData(bare, "Feb_2026"));
        assertTrue(ex.getMessage().contains("Total Changes Process"));
    }

    @Test
    void monthFromStoredNameStillParsesStashNames() {
        assertEquals("Jun_2026", TeamReportController.monthFromStoredName(
                "20260707-181706__Team_Report_2026_Jun.xlsx"));
        assertNull(TeamReportController.monthFromStoredName(
                "20260707-181706__Team_Report_2026_Jun_discrepancies.docx"));
    }
}
