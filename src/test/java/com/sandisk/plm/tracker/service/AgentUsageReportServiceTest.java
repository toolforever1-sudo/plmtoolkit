package com.sandisk.plm.tracker.service;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class AgentUsageReportServiceTest {

    private static Map<String, Object> call(String path, String query, int status, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", "2026-07-11T10:00:00Z");
        m.put("method", "GET");
        m.put("path", path);
        m.put("query", query);
        m.put("status", status);
        m.put("ms", ms);
        return m;
    }

    private List<Map<String, Object>> sample() {
        return Arrays.asList(
            call("/api/agent/documents", "q=gift&size=50", 200, 40),
            call("/api/agent/documents", "q=compliance", 200, 55),
            call("/api/agent/files/text", "item=14-01-WW-00-00027&name=x.docx", 200, 1800),
            call("/api/agent/files/download", "item=ABC&name=y.pdf", 429, 3),
            call("/api/agent/documents/14-01-WW-00-00027", "", 200, 12));
    }

    @Test
    void buildExcelHasDetailAndSummarySheets() throws Exception {
        AgentUsageReportService svc = new AgentUsageReportService();
        byte[] bytes = svc.buildExcel(sample(), "Jul 11 test");
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet calls = wb.getSheet("Calls");
            assertNotNull(calls);
            assertEquals(5, calls.getLastRowNum());           // 5 data rows (header at row 0)
            Sheet summary = wb.getSheet("Summary by endpoint");
            assertNotNull(summary);
            // endpoints: documents, files/text, files/download, documents/{number} = 4
            assertEquals(4, summary.getLastRowNum());
        }
    }

    @Test
    void compactDigestSurfacesSearchTermsAndTallies() {
        AgentUsageReportService svc = new AgentUsageReportService();
        String d = svc.compactDigest(sample());
        assertTrue(d.contains("gift"), d);
        assertTrue(d.contains("compliance"), d);
        assertTrue(d.contains("Total calls: 5"), d);
        assertTrue(d.contains("429"), d);                     // the rate-limited call shows in status tally
        assertTrue(d.contains("documents/{number}"), d);      // path-variable collapsed
    }

    @Test
    void emptyWindowIsSafe() throws Exception {
        AgentUsageReportService svc = new AgentUsageReportService();
        byte[] bytes = svc.buildExcel(Collections.emptyList(), "empty");
        assertTrue(bytes.length > 0);                         // still a valid workbook
    }

    private static void set(Object o, String field, Object v) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, v);
    }

    @Test
    void recipientsAreHotEditableFileWinsOverConfig() throws Exception {
        AgentUsageReportService svc = new AgentUsageReportService();
        java.io.File tmp = java.io.File.createTempFile("recips", ".txt");
        tmp.deleteOnExit();
        set(svc, "recipientsFile", tmp.getAbsolutePath());
        set(svc, "recipientsCsv", "config-default@sandisk.com");

        // No override file content yet (empty) → falls back to configured default.
        java.nio.file.Files.write(tmp.toPath(), new byte[0]);
        assertEquals(Collections.singletonList("config-default@sandisk.com"), svc.recipients());

        // Write the override → next read reflects it immediately, no restart. Comments/blanks/dupes handled.
        svc.setRecipients(Arrays.asList("a@sandisk.com", "b@sandisk.com", "A@sandisk.com"));
        List<String> r = svc.recipients();
        assertEquals(Arrays.asList("a@sandisk.com", "b@sandisk.com"), r);   // deduped case-insensitively
        assertEquals("file", svc.recipientsStatus().get("source"));

        // Clearing the override removes the file so the config default applies again.
        svc.setRecipients(Collections.emptyList());
        assertEquals(Collections.singletonList("config-default@sandisk.com"), svc.recipients());
        assertEquals("config", svc.recipientsStatus().get("source"));
    }
}
