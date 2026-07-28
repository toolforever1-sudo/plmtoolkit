package com.sandisk.plm.tracker.service;

/** Condenses an ECN description to a single short line. Returns null/blank on
 *  failure so callers can fall back to the raw text. */
@FunctionalInterface
public interface LineSummarizer {
    String summarize(String ecnNumber, String description);
}
