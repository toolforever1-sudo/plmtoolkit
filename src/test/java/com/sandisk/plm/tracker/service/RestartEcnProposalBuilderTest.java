package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartEcnProposalBuilderTest {

    private RestartEcnProposalBuilder builder(LineSummarizer s) {
        RestartEcnProposalBuilder b = new RestartEcnProposalBuilder();
        b.summarizer = s;
        b.proposalHeader = "Deployment {date}:";
        b.problemHeader = "Deploying ECNs:";
        return b;
    }

    private Map<String,String> descs() {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("ECN-134394-PROJ", "Apple Audit & Automation: gap in automation implemented via ECN-122260.");
        m.put("ECN-136664-PROJ", "Single/Sole Source: update rules to retain manual Multi Source assignment.");
        return m;
    }

    @Test
    void proposalUsesAiSummaryPerEcnWithDateHeader() {
        LineSummarizer ai = (ecn, d) -> "AI[" + ecn + "]";
        RestartEcnProposalBuilder.Built out =
                builder(ai).build(LocalDate.of(2026, 7, 4), descs());
        assertEquals(
            "Deployment 07/04/26:\n"
          + "• ECN-134394-PROJ: AI[ECN-134394-PROJ]\n"
          + "• ECN-136664-PROJ: AI[ECN-136664-PROJ]",
            out.proposal);
    }

    @Test
    void problemStatementListsEcnNumbers() {
        RestartEcnProposalBuilder.Built out =
                builder((e, d) -> "x").build(LocalDate.of(2026, 7, 4), descs());
        assertEquals("Deploying ECNs:\nECN-134394-PROJ, ECN-136664-PROJ", out.problemStatement);
    }

    @Test
    void fallsBackToTruncatedDescriptionWhenAiReturnsNull() {
        LineSummarizer ai = (ecn, d) -> null;   // AI unavailable
        Map<String,String> one = new LinkedHashMap<>();
        String longDesc = "This is a long description that should be truncated to a bounded length "
                + "so the proposal never becomes enormous even without any AI summary at all here.";
        one.put("ECN-1", longDesc);
        RestartEcnProposalBuilder.Built out =
                builder(ai).build(LocalDate.of(2026, 1, 2), one);
        assertTrue(out.proposal.startsWith("Deployment 01/02/26:\n• ECN-1: This is a long description"));
        // Bounded (header + bullet), and never the full untruncated text.
        assertTrue(out.proposal.length() < longDesc.length() + 40);
    }
}
