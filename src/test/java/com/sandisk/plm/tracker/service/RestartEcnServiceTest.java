package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.ItEnhancementsService.Row;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestartEcnServiceTest {

    private static final String ELIGIBLE = "UAT complete, CAB Prep";

    private Row row(String ecn, String status, String ownerLogin, String ownerName, String proposal) {
        Row r = new Row();
        r.ecnNumber = ecn; r.status = status;
        r.itOwnerLoginId = ownerLogin; r.itOwner = ownerName; r.proposal = proposal;
        return r;
    }

    private RestartEcnService svc() {
        RestartEcnService s = new RestartEcnService();
        s.eligibleItStatus = ELIGIBLE;
        return s;
    }

    @Test
    void candidatesKeepOnlyEligibleItStatus_caseInsensitive() {
        List<Row> rows = Arrays.asList(
                row("ECN-1", "UAT COMPLETE, CAB PREP", "a", "Alice", "d1"),
                row("ECN-2", "WIP", "b", "Bob", "d2"),
                row("ECN-3", ELIGIBLE, "c", "Carol", "d3"));
        List<RestartEcnService.Candidate> c = svc().candidates(rows);
        assertEquals(2, c.size());
        assertEquals("ECN-1", c.get(0).ecn);
        assertEquals("ECN-3", c.get(1).ecn);
    }

    @Test
    void revalidateDropsIneligibleEcnAndReportsIt() {
        List<Row> rows = Arrays.asList(
                row("ECN-1", ELIGIBLE, "a", "Alice", "d1"),
                row("ECN-2", "WIP", "b", "Bob", "d2"));
        RestartEcnService.Validated v =
                svc().revalidate(rows, Arrays.asList("ECN-1", "ECN-2"), "z");
        assertTrue(v.ok);
        assertEquals(Arrays.asList("ECN-1"), v.acceptedEcns);
        assertEquals(Arrays.asList("ECN-2"), v.droppedEcns);
    }

    @Test
    void revalidateFailsWhenNoEligibleEcnRemains() {
        List<Row> rows = Arrays.asList(row("ECN-2", "WIP", "b", "Bob", "d2"));
        RestartEcnService.Validated v = svc().revalidate(rows, Arrays.asList("ECN-2"), "z");
        assertFalse(v.ok);
        assertNotNull(v.error);
    }

    @Test
    void revalidateRejectsOwnerWhoOwnsARelatedEcn() {
        List<Row> rows = Arrays.asList(row("ECN-1", ELIGIBLE, "alice", "Alice", "d1"));
        RestartEcnService.Validated v = svc().revalidate(rows, Arrays.asList("ECN-1"), "alice");
        assertFalse(v.ok);
        assertTrue(v.error.toLowerCase().contains("owner"));
    }

    @Test
    void revalidateAcceptsOwnerWhoOwnsNoRelatedEcn() {
        List<Row> rows = Arrays.asList(row("ECN-1", ELIGIBLE, "alice", "Alice", "d1"));
        RestartEcnService.Validated v = svc().revalidate(rows, Arrays.asList("ECN-1"), "dave");
        assertTrue(v.ok);
        assertEquals("dave", v.itOwnerLogin);
    }

    @Test
    void buildFileDescription_ecnsFirstThenNoteElseFilename() {
        assertEquals("ECN-0245719, ECN-0245801 — jar for extensions",
                RestartEcnService.buildFileDescription(
                        Arrays.asList("ECN-0245719", "ECN-0245801"), "jar for extensions", "x.jar"));
        // no note -> fall back to the filename (2026-07-20 rule change)
        assertEquals("ECN-0245719 — build.properties",
                RestartEcnService.buildFileDescription(Arrays.asList("ECN-0245719"), "  ", "build.properties"));
        // no ecns -> just the tail, no dash
        assertEquals("just a note",
                RestartEcnService.buildFileDescription(java.util.Collections.emptyList(), "just a note", "f.txt"));
        // no ecns and no note -> filename alone, still no dash
        assertEquals("f.txt",
                RestartEcnService.buildFileDescription(java.util.Collections.emptyList(), null, "f.txt"));
        // no filename either -> ECNs alone, no trailing dash
        assertEquals("ECN-0245719",
                RestartEcnService.buildFileDescription(Arrays.asList("ECN-0245719"), null, null));
    }

    @Test
    void itOwnerRosterIsDistinctByLogin_skipsBlank_sortedByName() {
        List<Row> rows = Arrays.asList(
                row("ECN-1", "WIP",     "kmanna",    "Manna, Kuntal",        "d1"),
                row("ECN-2", ELIGIBLE,  "amuhammed", "Muhammed, Afrozuddin", "d2"),
                row("ECN-3", "WIP",     "KManna",    "Manna, Kuntal",        "d3"),  // dup login (case-insensitive)
                row("ECN-4", "WIP",     "",          "No Login",             "d4"),  // blank login -> skipped
                row("ECN-5", ELIGIBLE,  "vjindal",   "Jindal, Vikas",        "d5"));
        List<RestartEcnService.ItOwner> roster = svc().itOwnerRoster(rows);
        // Derived from ALL rows (not just eligible), distinct by login, blank skipped, sorted by name.
        assertEquals(3, roster.size());
        assertEquals("Jindal, Vikas", roster.get(0).name);
        assertEquals("Manna, Kuntal", roster.get(1).name);
        assertEquals("Muhammed, Afrozuddin", roster.get(2).name);
        assertEquals("vjindal", roster.get(0).login);
    }
}
