package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class BomRaceMatchTest {

    /** BomResult shape per src/main/java/com/sandisk/plm/tracker/model/BomResult.java —
     *  11-arg ctor: level, parent, component, quantity, description, notes, status, rev,
     *  refDesignator, findNumber, itemType. */
    private BomResult tk(int level, String parent, String component, String quantity) {
        return new BomResult(level, parent, component, quantity,
                /*description*/"", /*notes*/"", /*status*/"", /*rev*/"",
                /*refDesignator*/"", /*findNumber*/"", /*itemType*/"");
    }

    private Map<String, Object> sdk(int level, String parent, String component, String qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level); m.put("parent", parent);
        m.put("component", component); m.put("qty", qty);
        return m;
    }

    @Test
    void setMatch_identicalSets_isOk() {
        List<BomResult> tk = Arrays.asList(tk(1, "P1", "C1", "2"), tk(1, "P1", "C2", "1"));
        List<Map<String,Object>> sdk = Arrays.asList(sdk(1, "P1", "C1", "2"), sdk(1, "P1", "C2", "1"));
        BomRaceService.MatchScore s = BomRaceService.computeSetMatch(tk, sdk);
        assertTrue(s.ok);
        assertTrue(s.onlyToolkit.isEmpty());
        assertTrue(s.onlySdk.isEmpty());
    }

    @Test
    void setMatch_sdkMissingOnePart_surfacesDiff() {
        List<BomResult> tk = Arrays.asList(tk(1, "P1", "C1", "2"), tk(1, "P1", "C2", "1"));
        List<Map<String,Object>> sdk = Collections.singletonList(sdk(1, "P1", "C1", "2"));
        BomRaceService.MatchScore s = BomRaceService.computeSetMatch(tk, sdk);
        assertFalse(s.ok);
        assertEquals(Collections.singleton("C2"), s.onlyToolkit);
        assertTrue(s.onlySdk.isEmpty());
    }

    @Test
    void structuralMatch_qtyDiff_failsStructural_butSetStillOk() {
        List<BomResult> tk = Collections.singletonList(tk(1, "P1", "C1", "2"));
        List<Map<String,Object>> sdk = Collections.singletonList(sdk(1, "P1", "C1", "5"));
        assertTrue(BomRaceService.computeSetMatch(tk, sdk).ok);
        assertFalse(BomRaceService.computeStructuralMatch(tk, sdk).ok);
    }

    @Test
    void bothEmpty_matchesOk() {
        BomRaceService.MatchScore s = BomRaceService.computeSetMatch(
            Collections.emptyList(), Collections.emptyList());
        assertTrue(s.ok);
    }
}
