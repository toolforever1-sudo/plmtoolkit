package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.util.UploadColumnDetector;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Covers the PT-124 fix: Change-History enrich must not build a multi-GB XSSF DOM for a huge
 * upload. Small files keep the full-fidelity DOM rewrite; big files switch to the streaming
 * (SAX→SXSSF) writer that inserts the 4 columns with flat memory. Also verifies the history
 * query runs once per <em>unique</em> item, not once per repeated row.
 */
public class ChangeHistoryEnrichServiceTest {

    private static void set(Object o, String field, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, v);
    }

    /** Header row: Description | Number | Qty. {@code dataRows} data rows; item col = B (index 1). */
    private static MultipartFile xlsx(int dataRows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Sheet1");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Description");
            h.createCell(1).setCellValue("Number");
            h.createCell(2).setCellValue("Qty");
            for (int i = 1; i <= dataRows; i++) {
                Row r = s.createRow(i);
                r.createCell(0).setCellValue("desc " + i);
                r.createCell(1).setCellValue("ITEM-" + i);
                r.createCell(2).setCellValue(i);         // numeric, to check type preservation on shift
            }
            wb.write(bos);
            return new MockMultipartFile("file", "sku.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());
        }
    }

    private ChangeHistoryEnrichService newService(ChangeHistoryService chs) throws Exception {
        ChangeHistoryEnrichService svc = new ChangeHistoryEnrichService();
        set(svc, "changeHistoryService", chs);
        set(svc, "columnDetector", new UploadColumnDetector());
        return svc;
    }

    private static Map<String, String> historyRow(String item, String phase, String date, String chg, String type) {
        Map<String, String> m = new HashMap<>();
        m.put("itemNumber", item); m.put("lifecyclePhase", phase); m.put("releaseDate", date);
        m.put("changeNumber", chg); m.put("changeType", type);
        return m;
    }

    @Test
    void smallFileUsesDomPath() throws Exception {
        ChangeHistoryService chs = mock(ChangeHistoryService.class);
        when(chs.getHistoryFiltered(anyList(), anyList(), anyList())).thenReturn(Collections.emptyList());
        ChangeHistoryEnrichService svc = newService(chs);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // domThreshold high → DOM path
        ChangeHistoryEnrichService.EnrichResult er =
            svc.enrich(xlsx(20), Collections.emptyList(), Collections.emptyList(), 1, 1000, out);

        assertFalse(er.streamed, "small file should use the DOM path");
        assertTrue(out.size() > 0);
        verify(chs, times(1)).getHistoryFiltered(anyList(), anyList(), anyList());
    }

    @Test
    void largeFileUsesStreamingPathAndInsertsColumnsCorrectly() throws Exception {
        ChangeHistoryService chs = mock(ChangeHistoryService.class);
        when(chs.getHistoryFiltered(anyList(), anyList(), anyList()))
            .thenReturn(Collections.singletonList(
                historyRow("ITEM-1", "ACT", "01-15-2026 12:00:00 AM", "ECO-0001", "Standard")));
        ChangeHistoryEnrichService svc = newService(chs);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // domThreshold low (5) with 20 rows → streaming path
        ChangeHistoryEnrichService.EnrichResult er =
            svc.enrich(xlsx(20), Collections.emptyList(), Collections.emptyList(), 1, 5, out);

        assertTrue(er.streamed, "over-threshold file should use the streaming path");
        assertTrue(out.size() > 0);

        // Read the streamed output back and verify structure.
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            Row header = s.getRow(0);
            assertEquals("Number", header.getCell(1).getStringCellValue());
            assertEquals("Lifecycle Phase Reached (from Change History)", header.getCell(2).getStringCellValue());
            assertEquals("Change Type (from Change History)", header.getCell(5).getStringCellValue());
            assertEquals("Qty", header.getCell(6).getStringCellValue(), "original col C must shift to G (+4)");

            Row item1 = s.getRow(1);   // ITEM-1
            assertEquals("ITEM-1", item1.getCell(1).getStringCellValue());
            assertEquals("ACT", item1.getCell(2).getStringCellValue());
            assertEquals("ECO-0001", item1.getCell(4).getStringCellValue());
            assertEquals("Standard", item1.getCell(5).getStringCellValue());
            assertEquals(1.0, item1.getCell(6).getNumericCellValue(), 0.001, "Qty stays numeric after shift");

            Row item2 = s.getRow(2);   // ITEM-2 — no history match, 4 cells blank
            assertTrue(item2.getCell(2) == null || item2.getCell(2).toString().isEmpty());
        }
    }

    @Test
    void entryModeFirstVsLastPicksEarliestVsLatestPerItem() throws Exception {
        // ITEM-1 has two released changes; FIRST should fill the 2020 one, LAST the 2026 one.
        java.util.List<Map<String,String>> hist = Arrays.asList(
            historyRow("ITEM-1", "DEV", "01-01-2020 12:00:00 AM", "ECO-EARLY", "ECO"),
            historyRow("ITEM-1", "ACT", "01-01-2026 12:00:00 AM", "ECO-LATE",  "ECO"));

        for (Object[] tc : new Object[][]{
                {ChangeHistoryService.EntryMode.FIRST, "ECO-EARLY", "DEV"},
                {ChangeHistoryService.EntryMode.LAST,  "ECO-LATE",  "ACT"}}) {
            ChangeHistoryService chs = mock(ChangeHistoryService.class);
            when(chs.getHistoryFiltered(anyList(), anyList(), anyList())).thenReturn(hist);
            ChangeHistoryEnrichService svc = newService(chs);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            svc.enrich(xlsx(3), Collections.emptyList(), Collections.emptyList(), 1,
                       (ChangeHistoryService.EntryMode) tc[0], out);
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
                Row item1 = wb.getSheetAt(0).getRow(1);       // ITEM-1
                assertEquals(tc[2], item1.getCell(2).getStringCellValue(), "lifecycle for " + tc[0]);
                assertEquals(tc[1], item1.getCell(4).getStringCellValue(), "change number for " + tc[0]);
            }
        }
    }

    @Test
    void repeatedItemsAreQueriedOnce() throws Exception {
        ChangeHistoryService chs = mock(ChangeHistoryService.class);
        when(chs.getHistoryFiltered(anyList(), anyList(), anyList())).thenReturn(Collections.emptyList());
        ChangeHistoryEnrichService svc = newService(chs);

        // 30 rows but only 3 distinct items, each repeated 10×.
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Sheet1");
            Row h = s.createRow(0); h.createCell(0).setCellValue("Number");
            for (int i = 1; i <= 30; i++) s.createRow(i).createCell(0).setCellValue("ITEM-" + (i % 3));
            wb.write(bos);
            MultipartFile f = new MockMultipartFile("file", "d.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());
            svc.enrich(f, Collections.emptyList(), Collections.emptyList(), 0, 1000, new ByteArrayOutputStream());
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
        verify(chs).getHistoryFiltered(cap.capture(), anyList(), anyList());
        List<String> queried = cap.getValue();
        assertEquals(new HashSet<>(queried).size(), queried.size(), "no duplicate items sent to the query");
        assertEquals(3, queried.size(), "3 distinct items despite 30 rows");
    }
}
