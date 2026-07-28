package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the deterministic three-tier fallback (the on-prem / offline path). The
 * LLM path is covered by manual server-side verification; this guards the
 * fallback parity with the JS module's grade-logic.test.js.
 */
class GradeEvaluatorServiceTest {

    private final GradeEvaluatorService svc = new GradeEvaluatorService();

    private static final String DESC_NR = "Process-documentation update only. Business-led; Agile IT help not required.";
    private static final String DESC_IT = "IT-led; go-live slipped.";

    @Test
    void dbVerified_itNotRequiredMatch_acceptsNoFile() {
        GradeModels.EvaluateResult ev = svc.evaluateDeterministic(
                "The ECN description says Agile IT help not required.", false, DESC_NR);
        assertEquals("accept", ev.status);
        assertEquals("agile_record", ev.verifiedBy);
    }

    @Test
    void docClaim_noRecordSupport_needsFile() {
        GradeModels.EvaluateResult ev = svc.evaluateDeterministic(
                "The description says this is not an IT change.", false, DESC_IT);
        assertEquals("need_file", ev.status);
    }

    @Test
    void docClaim_withAttachment_accepts() {
        GradeModels.EvaluateResult ev = svc.evaluateDeterministic(
                "The description says this is not an IT change.", true, DESC_IT);
        assertEquals("accept", ev.status);
        assertEquals("attachment", ev.verifiedBy);
    }

    @Test
    void independentEvidence_accepts() {
        GradeModels.EvaluateResult ev = svc.evaluateDeterministic(
                "Reassigned to A. Rivera on 6/15 per CCB-2026-114; UAT signed off.", false, DESC_IT);
        assertEquals("accept", ev.status);
        assertEquals("cited_evidence", ev.verifiedBy);
    }

    @Test
    void weakOpinion_rejects() {
        GradeModels.EvaluateResult ev = svc.evaluateDeterministic("this is unfair", false, DESC_IT);
        assertEquals("reject", ev.status);
    }
}
