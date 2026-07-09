package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ImsReviewExemptTest {
    private final java.util.List<String> styles = java.util.Arrays.asList(
        "Specs", "Drawing", "Record - for dead document - buy off report");

    @Test void matchesSpecsCaseAndSpaceInsensitive() {
        assertTrue(ImsReviewService.isDrrExempt("D001 Specs", styles));
        assertTrue(ImsReviewService.isDrrExempt("d025productoutlinedrawing", styles));
        assertTrue(ImsReviewService.isDrrExempt("Record  -  for dead document - buy off report", styles));
    }
    @Test void nonExemptForOrdinaryStyles() {
        assertFalse(ImsReviewService.isDrrExempt("Test Plan", styles));
        assertFalse(ImsReviewService.isDrrExempt("", styles));
        assertFalse(ImsReviewService.isDrrExempt(null, styles));
    }
    @Test void emptyConfigNeverExempt() {
        assertFalse(ImsReviewService.isDrrExempt("Specs", java.util.Collections.emptyList()));
    }
}
