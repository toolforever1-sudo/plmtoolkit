package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RejectionClassificationTest {

    @Test
    void aiView_aliasesLegacyAndFallsBackToUnknown() {
        assertEquals("Wrong Information", RejectionTrackerService.aiView("Wrong Information"));
        assertEquals("Returned by Owner", RejectionTrackerService.aiView("Returned By Requestor"));
        assertEquals("Unknown", RejectionTrackerService.aiView("Ambiguous Request"));
        assertEquals("Unknown", RejectionTrackerService.aiView(null));
        assertEquals("Unknown", RejectionTrackerService.aiView("Something Else"));
    }

    @Test
    void auditView_prefersCodeThenOwnerThenNoCode() {
        assertEquals("Wrong Information", RejectionTrackerService.auditView("WI: pn missing", "Unknown"));
        assertEquals("Insufficient Information",
                RejectionTrackerService.auditView("insufficient information: unclear", "Wrong Information"));
        assertEquals("Returned by Owner", RejectionTrackerService.auditView("no prefix here", "Returned By Requestor"));
        assertEquals(RejectionTrackerService.NO_AUDIT_CODE,
                RejectionTrackerService.auditView("just a free comment", "Wrong Information"));
        assertEquals(RejectionTrackerService.NO_AUDIT_CODE,
                RejectionTrackerService.auditView(null, null));
    }

    @Test
    void categorySource_classifiesOrigin() {
        assertEquals("audit", RejectionTrackerService.categorySource("WI: x", "Unknown"));
        assertEquals("owner", RejectionTrackerService.categorySource("free text", "Returned By Requestor"));
        assertEquals("ai", RejectionTrackerService.categorySource("free text", "Wrong Information"));
        assertEquals("unknown", RejectionTrackerService.categorySource("free text", "Ambiguous Request"));
    }

    @Test
    void enrichEvent_setsAllThreeClassificationFields() throws Exception {
        RejectionTrackerService svc = new RejectionTrackerService();
        java.util.Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("ts", "2026-06-28T10:00:00");
        e.put("ecnNumber", "ECN-1");
        e.put("comment", "WI: input PN doesn't exist");
        e.put("category", "Insufficient Information"); // AI disagrees with the human code

        java.lang.reflect.Method m = RejectionTrackerService.class.getDeclaredMethod(
            "enrichEvent", java.util.Map.class, java.util.Map.class, java.util.Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> out = (java.util.Map<String, Object>) m.invoke(
            svc, e, java.util.Collections.emptyMap(), java.util.Collections.emptyMap());

        assertEquals("Insufficient Information", out.get("aiCategory"));
        assertEquals("Wrong Information", out.get("auditCategory"));
        assertEquals("audit", out.get("categorySource"));
        assertEquals("Wrong Information", out.get("category")); // existing default unchanged
    }

    private java.util.Map<String, Object> ev(String field, String value) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ts", "2026-06-28T10:00:00");
        m.put("ecnNumber", "ECN-" + value.hashCode());
        m.put("requestor", "Req A (1)");
        m.put(field, value);
        return m;
    }

    @Test
    void getAggregatesFor_bucketsByFieldWithFallback() {
        RejectionTrackerService svc = new RejectionTrackerService();
        java.util.List<java.util.Map<String, Object>> evs = java.util.Arrays.asList(
            ev("auditCategory", "Wrong Information"),
            ev("auditCategory", "Wrong Information"),
            ev("auditCategory", "No audit code"),
            ev("auditCategory", "totally-unmapped") // -> fallback (last in order)
        );
        java.util.Map<String, Object> agg = svc.getAggregatesFor(
            evs, "auditCategory", RejectionTrackerService.AUDIT_CATEGORY_ORDER);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> cats = (java.util.Map<String, Integer>) agg.get("categories");
        assertEquals(2, cats.get("Wrong Information").intValue());
        assertEquals(2, cats.get("No audit code").intValue()); // real + fallback
        assertEquals(4, agg.get("totalEvents"));
    }

    private java.util.Map<String, Object> pair(String ecn, String ai, String audit) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ecnNumber", ecn);
        m.put("ts", "2026-06-28T10:00:00");
        m.put("comment", audit + ": note");
        m.put("aiCategory", ai);
        m.put("auditCategory", audit);
        return m;
    }

    @Test
    void computeAgreement_countsOnlyDoublyCodedEvents() {
        RejectionTrackerService svc = new RejectionTrackerService();
        java.util.List<java.util.Map<String, Object>> evs = java.util.Arrays.asList(
            pair("E1", "Wrong Information", "Wrong Information"),       // match
            pair("E2", "Insufficient Information", "Wrong Information"),// mismatch
            pair("E3", "Unknown", "Wrong Information"),                 // ai not coded -> excluded
            pair("E4", "Wrong Information", "No audit code")            // audit not coded -> excluded
        );
        java.util.Map<String, Object> a = svc.computeAgreement(evs);
        assertEquals(2, a.get("coded"));
        assertEquals(1, a.get("matched"));
        assertEquals(50, a.get("agreementPct"));
        @SuppressWarnings("unchecked")
        java.util.List<Object> mm = (java.util.List<Object>) a.get("mismatches");
        assertEquals(1, mm.size());
    }

    @Test
    void computeAgreement_nullPctWhenNoCodedEvents() {
        RejectionTrackerService svc = new RejectionTrackerService();
        java.util.List<java.util.Map<String, Object>> evs = java.util.Arrays.asList(
            pair("E1", "Wrong Information", "No audit code"));
        java.util.Map<String, Object> a = svc.computeAgreement(evs);
        assertEquals(0, a.get("coded"));
        assertNull(a.get("agreementPct"));
    }

    @Test
    void eventDateSpan_nullWhenEmpty() {
        RejectionTrackerService svc = new RejectionTrackerService();
        assertNull(svc.getEventDateSpan()); // no cache loaded in a bare instance
    }
}
