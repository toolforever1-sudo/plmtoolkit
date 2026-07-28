package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the row→map collapse used to build the Primary # column. */
public class I2PrimaryLookupServiceTest {

    private String[] row(String item, String comp) { return new String[]{ item, comp }; }

    @Test
    void singleItemSinglePrimary() {
        Map<String, String> m = I2PrimaryLookupService.collapsePrimaries(
                Collections.singletonList(row("N001-0627VG-20KKM", "N001-06270-2C")));
        assertEquals("N001-06270-2C", m.get("N001-0627VG-20KKM"));
    }

    @Test
    void multiplePrimaries_distinctAndSortedAndJoined() {
        Map<String, String> m = I2PrimaryLookupService.collapsePrimaries(List.of(
                row("A162-029264-1T00", "N001-0623VG-20KKM"),
                row("A162-029264-1T00", "E006-000643-DCL"),
                row("A162-029264-1T00", "N001-0623VG-20KKM")));   // duplicate
        assertEquals("E006-000643-DCL, N001-0623VG-20KKM", m.get("A162-029264-1T00"));
    }

    @Test
    void excludesNullBlankAndInactiveComponent() {
        Map<String, String> m = I2PrimaryLookupService.collapsePrimaries(List.of(
                row("X", "Inactive Component"),
                row("X", null),
                row("X", "   "),
                row("X", "REAL-PART")));
        assertEquals("REAL-PART", m.get("X"));
    }

    @Test
    void itemWithOnlyExcludedValues_isAbsent() {
        Map<String, String> m = I2PrimaryLookupService.collapsePrimaries(List.of(
                row("X", "Inactive Component"),
                row("X", null)));
        assertFalse(m.containsKey("X"));
    }

    @Test
    void multipleItems_keptSeparate() {
        Map<String, String> m = I2PrimaryLookupService.collapsePrimaries(List.of(
                row("A", "a1"),
                row("B", "b1")));
        assertEquals("a1", m.get("A"));
        assertEquals("b1", m.get("B"));
        assertEquals(2, m.size());
    }

    @Test
    void emptyInput_emptyMap() {
        assertTrue(I2PrimaryLookupService.collapsePrimaries(Collections.emptyList()).isEmpty());
    }

    // ---- Rule B: slot-primary mapping (component matches as primary OR substitute) ----

    private String[] slot(String componentItemId, String substituteItemId) {
        return new String[]{ componentItemId, substituteItemId };
    }

    @Test
    void substituteComponent_mapsToSlotPrimary() {
        // The component is a substitute in i2; show the slot's PRIMARY (another part at the same level).
        Map<String, String> m = I2PrimaryLookupService.slotPrimaries(
                new HashSet<>(List.of("A182-032457-128G")),
                Collections.singletonList(slot("A182-030530-128GB2", "A182-032457-128G")));
        assertEquals("A182-030530-128GB2", m.get("A182-032457-128G"));
    }

    @Test
    void primaryComponent_mapsToItself() {
        // The component IS the slot primary -> Primary # shows the component itself.
        Map<String, String> m = I2PrimaryLookupService.slotPrimaries(
                new HashSet<>(List.of("A182-032434-128G")),
                Collections.singletonList(slot("A182-032434-128G", null)));
        assertEquals("A182-032434-128G", m.get("A182-032434-128G"));
    }

    @Test
    void multipleSlots_distinctSortedJoined() {
        Map<String, String> m = I2PrimaryLookupService.slotPrimaries(
                new HashSet<>(List.of("SUB-1")),
                List.of(slot("PRIM-B", "SUB-1"),
                        slot("PRIM-A", "SUB-1"),
                        slot("PRIM-B", "SUB-1")));   // duplicate primary
        assertEquals("PRIM-A, PRIM-B", m.get("SUB-1"));
    }

    @Test
    void inactiveOrNullPrimary_excluded() {
        Map<String, String> m = I2PrimaryLookupService.slotPrimaries(
                new HashSet<>(List.of("SUB-1")),
                Arrays.asList(slot("Inactive Component", "SUB-1"),
                        slot(null, "SUB-1"),
                        slot("REAL-PRIM", "SUB-1")));
        assertEquals("REAL-PRIM", m.get("SUB-1"));
    }

    @Test
    void componentNotInAnySlot_absent() {
        Map<String, String> m = I2PrimaryLookupService.slotPrimaries(
                new HashSet<>(List.of("MISSING")),
                Collections.singletonList(slot("PRIM", "OTHER-SUB")));
        assertFalse(m.containsKey("MISSING"));
    }

    @Test
    void rowMatchingBothColumns_eachInputGetsThePrimary() {
        // One slot whose primary is one input and substitute is another input.
        Map<String, String> m = I2PrimaryLookupService.slotPrimaries(
                new HashSet<>(List.of("PRIM-X", "SUB-Y")),
                Collections.singletonList(slot("PRIM-X", "SUB-Y")));
        assertEquals("PRIM-X", m.get("PRIM-X"));   // primary maps to itself
        assertEquals("PRIM-X", m.get("SUB-Y"));    // substitute maps to the primary
    }
}
