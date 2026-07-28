package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.AgentDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DocumentIndexServiceTest {

    private static final String FIXTURE =
        "{\"generatedAt\":\"2026-07-10\",\"count\":3,\"documents\":[" +
        "{\"number\":\"00-00-WW-02-00007\",\"description\":\"Quality Control Plan for Calypso\"," +
        " \"lifecyclePhase\":\"ACT\",\"style\":\"Guideline\",\"function\":\"Quality Management Systems|General\"," +
        " \"classification\":\"Non-Automotive\",\"owners\":\"Shen, Mike\"," +
        " \"attachments\":[{\"fileName\":\"qc.docx\",\"fileDescription\":\"Final\"}]}," +
        "{\"number\":\"00-11-XX-00-00001\",\"description\":\"Compliance and Conduct Policy\"," +
        " \"lifecyclePhase\":\"ACT\",\"style\":\"Policy\",\"function\":\"Legal|General\"," +
        " \"classification\":\"Both Automotive and Non-Automotive\",\"owners\":\"Doe, Jane\"," +
        " \"attachments\":[{\"fileName\":\"policy.pdf\",\"fileDescription\":\"Final\"}]}," +
        "{\"number\":\"00-22-YY-00-00002\",\"description\":\"Obsolete WI\"," +
        " \"lifecyclePhase\":\"OBS\",\"style\":\"WI\",\"function\":\"Legal|Sub\"," +
        " \"classification\":\"Automotive\",\"owners\":\"Doe, Jane\",\"attachments\":[]}" +
        "]}";

    private DocumentIndexService svcWith(Path dir) throws Exception {
        Path f = dir.resolve("documents-index.json");
        Files.write(f, FIXTURE.getBytes("UTF-8"));
        DocumentIndexService s = new DocumentIndexService(f.toString());
        s.reload();
        return s;
    }

    @Test
    void loadsIndex(@TempDir Path dir) throws Exception {
        DocumentIndexService s = svcWith(dir);
        assertTrue(s.isLoaded());
        assertEquals(3, s.size());
        assertEquals("2026-07-10", s.generatedAt());
    }

    @Test
    void missingFileIsFailSoft() {
        DocumentIndexService s = new DocumentIndexService("/no/such/file.json");
        s.reload();
        assertFalse(s.isLoaded());
        assertEquals(0, s.size());
        assertEquals(0, s.search(new HashMap<>(), null, 0, 50).total);
    }

    @Test
    void freeTextMatchesDescription(@TempDir Path dir) throws Exception {
        DocumentIndexService s = svcWith(dir);
        DocumentIndexService.SearchResult r = s.search(new HashMap<>(), "compliance", 0, 50);
        assertEquals(1, r.total);
        assertEquals("00-11-XX-00-00001", r.documents.get(0).number);
    }

    @Test
    void filterByStyleAndLifecycle(@TempDir Path dir) throws Exception {
        DocumentIndexService s = svcWith(dir);
        Map<String, String> f = new HashMap<>();
        f.put("lifecycle", "act");           // case-insensitive
        DocumentIndexService.SearchResult r = s.search(f, null, 0, 50);
        assertEquals(2, r.total);
        f.put("style", "Policy");
        assertEquals(1, s.search(f, null, 0, 50).total);
    }

    @Test
    void paging(@TempDir Path dir) throws Exception {
        DocumentIndexService s = svcWith(dir);
        DocumentIndexService.SearchResult p0 = s.search(new HashMap<>(), null, 0, 2);
        assertEquals(3, p0.total);
        assertEquals(2, p0.documents.size());
        DocumentIndexService.SearchResult p1 = s.search(new HashMap<>(), null, 1, 2);
        assertEquals(1, p1.documents.size());
    }

    @Test
    void aggregateByStyle(@TempDir Path dir) throws Exception {
        DocumentIndexService s = svcWith(dir);
        Map<String, Object> agg = s.aggregate("style", new HashMap<>(), null);
        assertEquals("style", agg.get("groupBy"));
        assertEquals(3, agg.get("matchedDocuments"));
        // 3 distinct styles (Guideline, Policy, WI), one each
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> buckets = (java.util.List<Map<String, Object>>) agg.get("buckets");
        assertEquals(3, buckets.size());
    }

    @Test
    void aggregateWithFilter(@TempDir Path dir) throws Exception {
        DocumentIndexService s = svcWith(dir);
        Map<String, String> f = new HashMap<>();
        f.put("owner", "Doe, Jane");
        Map<String, Object> agg = s.aggregate("lifecycle", f, null);
        assertEquals(2, agg.get("matchedDocuments")); // Jane owns the Policy (ACT) + Obsolete WI (OBS)
    }

    @Test
    void aggregateBadGroupByThrows(@TempDir Path dir) throws Exception {
        DocumentIndexService s = svcWith(dir);
        assertThrows(IllegalArgumentException.class, () -> s.aggregate("bogus", new HashMap<>(), null));
    }

    @Test
    void getByNumberCaseInsensitive(@TempDir Path dir) throws Exception {
        DocumentIndexService s = svcWith(dir);
        assertNotNull(s.get("00-11-xx-00-00001"));
        assertNull(s.get("does-not-exist"));
    }

    // --- DB-refresh dedup: page_three can yield a populated + a blank row per item;
    //     mergeDocRow must keep the populated one regardless of arrival order. ---
    private static AgentDocument doc(String num, String style) {
        AgentDocument d = new AgentDocument(); d.number = num; d.style = style; return d;
    }

    @Test
    void mergeDocRowKeepsPopulatedRow_blankFirst() {
        Map<String, AgentDocument> m = new HashMap<>();
        DocumentIndexService.mergeDocRow(m, doc("D1", null));      // blank row first
        DocumentIndexService.mergeDocRow(m, doc("D1", "Policy"));  // populated row second
        assertEquals("Policy", m.get("D1").style);
    }

    @Test
    void dateRangeFilters(@TempDir Path dir) throws Exception {
        // Build a fixture with createDate/revReleaseDate to exercise range filters.
        String fx = "{\"generatedAt\":\"2026-07-10\",\"count\":3,\"documents\":[" +
            "{\"number\":\"A\",\"description\":\"a\",\"createDate\":\"2026-03-01\",\"revReleaseDate\":\"2026-04-01\",\"attachments\":[]}," +
            "{\"number\":\"B\",\"description\":\"b\",\"createDate\":\"2025-06-01\",\"revReleaseDate\":\"2025-07-01\",\"attachments\":[]}," +
            "{\"number\":\"C\",\"description\":\"c\",\"createDate\":\"2024-01-01\",\"revReleaseDate\":\"2024-02-01\",\"attachments\":[]}]}";
        Path f = dir.resolve("documents-index.json");
        Files.write(f, fx.getBytes("UTF-8"));
        DocumentIndexService s = new DocumentIndexService(f.toString());
        s.reload();

        Map<String, String> created2026 = new HashMap<>();
        created2026.put("createdFrom", "2026-01-01"); created2026.put("createdTo", "2026-12-31");
        DocumentIndexService.SearchResult r1 = s.search(created2026, null, 0, 50);
        assertEquals(1, r1.total);
        assertEquals("A", r1.documents.get(0).number);

        Map<String, String> releasedFrom2025 = new HashMap<>();
        releasedFrom2025.put("releasedFrom", "2025-01-01");
        DocumentIndexService.SearchResult r2 = s.search(releasedFrom2025, null, 0, 50);
        assertEquals(2, r2.total); // A (2026) + B (2025), not C (2024)
    }

    @Test
    void mergeDocRowKeepsPopulatedRow_populatedFirst() {
        Map<String, AgentDocument> m = new HashMap<>();
        DocumentIndexService.mergeDocRow(m, doc("D1", "Policy"));  // populated first
        DocumentIndexService.mergeDocRow(m, doc("D1", null));      // blank second must not clobber
        assertEquals("Policy", m.get("D1").style);
    }
}
