package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import com.sandisk.plm.tracker.model.ObaResolution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ObaResolverServiceTest {

    private final ObaResolverService service = new ObaResolverService(null);

    /** Ancestry is taken from the BOM path (SYS_CONNECT_BY_PATH), not the parent column —
     *  so test rows are built with a realistic {@code path}. */
    private static BomResult rowP(String component, String desc, String path) {
        BomResult r = new BomResult(0, null, component, "1", desc, "", "ACT", "1", "", "", "D");
        r.setPath(path);
        return r;
    }

    /**
     * The real 0TS2670 shape as it comes back from bom_extract (verified against QA):
     * the flattened explode reports parent=SKU for L000, but the path carries the true
     * chain SKU &gt; C039 &gt; L000 &gt; D026.
     */
    private static List<BomResult> sample() {
        List<BomResult> rows = new ArrayList<>();
        rows.add(rowP("C0390TS2670", "BC,0TS2670 Carrera Syndicate", "0TS2670 > C0390TS2670"));
        rows.add(rowP("C0570TS2670", "BC,0TS2670 Carrera Syndicate", "0TS2670 > C0570TS2670")); // C057, not C039
        rows.add(rowP("L000-000466-L0", "Label Printing Inst, Product Carrera TCG FIPS",
                "0TS2670 > C0390TS2670 > L000-000466-L0")); // not "outer ... shipping label"
        rows.add(rowP("L000-000420-L1", "Label-Printing-Instruction,Outer Carton Shipping Label (Google)",
                "0TS2670 > C0390TS2670 > L000-000420-L1")); // the match
        rows.add(rowP("54-55-11034", "Label, Security 6.8mm", "0TS2670 > C0390TS2670 > 54-55-11034"));
        rows.add(rowP("D026-002282-L1", "Work Instructions, eSSD Shipping Label for Google",
                "0TS2670 > C0390TS2670 > L000-000420-L1 > D026-002282-L1")); // the match
        // A second D026 that hangs off the OTHER L000 — must NOT be picked.
        rows.add(rowP("D026-002331-L0", "Label Work Instr, Label Specification Final",
                "0TS2670 > C0390TS2670 > L000-000466-L0 > D026-002331-L0"));
        return rows;
    }

    @Test
    void resolvesLabelProofAndSpec() {
        ObaResolution r = service.resolveFromRows("0TS2670", sample());
        assertEquals("C0390TS2670", r.c039Item);
        assertEquals("L000-000420-L1", r.labelProofItem);
        assertTrue(r.labelProofDesc.toLowerCase().contains("outer carton shipping label"));
        assertEquals("D026-002282-L1", r.labelSpecItem); // the D026 under the chosen L000, not the other
        assertTrue(r.warnings.isEmpty(), "expected no warnings, got " + r.warnings);
    }

    /** The same item appearing on two BOM paths (under a C039 and a C057 branch) is ONE
     *  item, not an ambiguity. */
    @Test
    void sameItemOnTwoBranchesIsNotAmbiguous() {
        List<BomResult> rows = sample();
        // L000-000420-L1 and D026-002282-L1 also reachable via the C057 branch.
        rows.add(rowP("L000-000420-L1", "Label-Printing-Instruction,Outer Carton Shipping Label (Google)",
                "0TS2670 > C0570TS2670 > L000-000420-L1"));
        rows.add(rowP("D026-002282-L1", "Work Instructions, eSSD Shipping Label for Google",
                "0TS2670 > C0570TS2670 > L000-000420-L1 > D026-002282-L1"));
        ObaResolution r = service.resolveFromRows("0TS2670", rows);
        assertEquals("L000-000420-L1", r.labelProofItem);
        assertEquals("D026-002282-L1", r.labelSpecItem);
        assertTrue(r.warnings.isEmpty(), "duplicate paths of one item must not warn, got " + r.warnings);
    }

    @Test
    void flagsMultipleC039() {
        List<BomResult> rows = sample();
        rows.add(rowP("C0391TS2670", "BC second C039", "0TS2670 > C0391TS2670"));
        ObaResolution r = service.resolveFromRows("0TS2670", rows);
        assertEquals("C0390TS2670", r.c039Item); // first in row order still wins
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("Multiple C039")),
                "expected a Multiple C039 warning, got " + r.warnings);
    }

    @Test
    void flagsMultipleL000() {
        List<BomResult> rows = sample();
        // A second, genuinely different L000 under the same C039 that also matches.
        rows.add(rowP("L000-000777-L1", "Label-Printing-Instruction,Outer Carton Shipping Label (dup)",
                "0TS2670 > C0390TS2670 > L000-000777-L1"));
        ObaResolution r = service.resolveFromRows("0TS2670", rows);
        assertEquals("L000-000420-L1", r.labelProofItem); // first in row order still wins
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("Multiple L000")),
                "expected a Multiple L000 warning, got " + r.warnings);
    }

    @Test
    void warnsWhenNoC039() {
        List<BomResult> rows = new ArrayList<>();
        rows.add(rowP("F000-CAR-0046", "Config file", "0TS2670 > F000-CAR-0046"));
        ObaResolution r = service.resolveFromRows("0TS2670", rows);
        assertNull(r.labelProofItem);
        assertNull(r.labelSpecItem);
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("No C039")),
                "expected a No C039 warning, got " + r.warnings);
    }

    @Test
    void warnsWhenNoD026UnderLabel() {
        List<BomResult> rows = new ArrayList<>();
        rows.add(rowP("C0390TS2670", "assembly", "0TS2670 > C0390TS2670"));
        rows.add(rowP("L000-000420-L1", "Outer Carton Shipping Label",
                "0TS2670 > C0390TS2670 > L000-000420-L1"));
        ObaResolution r = service.resolveFromRows("0TS2670", rows);
        assertEquals("L000-000420-L1", r.labelProofItem);
        assertNull(r.labelSpecItem);
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("No D026")),
                "expected a No D026 warning, got " + r.warnings);
    }
}
