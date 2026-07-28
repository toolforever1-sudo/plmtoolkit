package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

public class RejectionSnapshotServiceTest {

    @Test
    void periodIdsAndLabels() {
        assertEquals("month-2026-06", RejectionSnapshotService.monthId(LocalDate.of(2026, 6, 15)));
        assertEquals("quarter-2026-Q2", RejectionSnapshotService.quarterId(LocalDate.of(2026, 6, 15)));
        assertEquals("week-2026-06-28", RejectionSnapshotService.weekId(LocalDate.of(2026, 6, 28)));

        assertEquals("June 2026", RejectionSnapshotService.labelFor("month-2026-06"));
        assertEquals("Q2 2026", RejectionSnapshotService.labelFor("quarter-2026-Q2"));
        assertEquals("Week ending Jun 28, 2026", RejectionSnapshotService.labelFor("week-2026-06-28"));
    }

    @Test
    void quarterBoundsAreInclusive() {
        LocalDate[] b = RejectionSnapshotService.quarterBounds(2026, 2);
        assertEquals(LocalDate.of(2026, 4, 1), b[0]);
        assertEquals(LocalDate.of(2026, 6, 30), b[1]);
    }

    @Test
    void writeThenRead_roundTrips_andIsImmutable() throws Exception {
        RejectionSnapshotService svc = new RejectionSnapshotService();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", "month-2099-01");
        payload.put("hello", "world");

        java.io.File dir = java.nio.file.Files.createTempDirectory("snaptest").toFile();
        java.io.File f = new java.io.File(dir, "month-2099-01.json");
        boolean wrote = svc.writeSnapshotFile(f, payload);
        assertTrue(wrote);
        boolean again = svc.writeSnapshotFile(f, payload); // immutable: no overwrite
        assertFalse(again);

        java.util.Map<String, Object> back = svc.readSnapshotFile(f);
        assertEquals("world", back.get("hello"));
    }

    @Test
    void completeMonthsBetween_excludesPartialCurrentMonth() {
        java.util.List<java.time.YearMonth> ms = RejectionSnapshotService.completeMonthsBetween(
            LocalDate.of(2026, 1, 10),  // span min
            LocalDate.of(2026, 4, 5),   // span max
            LocalDate.of(2026, 4, 15)); // "today"
        assertEquals(java.util.Arrays.asList(
            java.time.YearMonth.of(2026, 1),
            java.time.YearMonth.of(2026, 2),
            java.time.YearMonth.of(2026, 3)), ms);
    }
}
