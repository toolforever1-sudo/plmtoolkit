package com.sandisk.plm.tracker.util;

import org.springframework.stereotype.Component;

import java.util.logging.Logger;

/**
 * Refuses to process an uploaded file when the JVM doesn't have enough free heap
 * to do the work safely. Better to bounce a file with a friendly business-language
 * message than to OOM the server mid-request.
 *
 * <p>Heap need is estimated as {@code fileSize × multiplier}, where the multiplier
 * depends on the operation:</p>
 * <ul>
 *   <li>{@link Op#PROBE}  — 1× (streaming reader, only reads headers + 3 sample rows)</li>
 *   <li>{@link Op#QUERY}  — 3× (streaming column read + IN-list query result)</li>
 *   <li>{@link Op#ENRICH} — 8× (full XSSFWorkbook DOM + cell-shifting writeback)</li>
 * </ul>
 *
 * <p>Available headroom = {@code maxMemory − usedMemory − safetyBuffer (200 MB)}. The
 * buffer covers concurrent requests and the steady-state app overhead.</p>
 */
@Component
public class HeapGuard {

    private static final Logger logger = Logger.getLogger(HeapGuard.class.getName());

    /** 200 MB safety margin held back from "available" so concurrent requests can still run. */
    private static final long SAFETY_BUFFER_BYTES = 200L * 1024 * 1024;

    public enum Op {
        PROBE  (1,  "preview",      Integer.MAX_VALUE),
        QUERY  (3,  "query",        200_000),
        // ENRICH bumped 8 → 12 after PT-29/PT-30: a 2.2 MB / 3K-item parts
        // enrich passed the 8× check (~18 MB estimate) but blew up at runtime.
        // The real cost is XSSF DOM + cell-shift + query-result list, which
        // empirically lands closer to 12× file size for parts enrichment.
        //
        // For an 825K-row / 72 MB file (Vikas's BOM-Implosion-2026-04-14.xlsx
        // that brought prod down), the 12× multiplier estimates 864 MB — well
        // under 6 GB heap — but POI XSSF DOM mode actually needs 1.5–3 GB for
        // that many cells (~30–40× file size). So size-based estimation isn't
        // enough alone. The row-count cap below catches what the multiplier
        // misses: any workbook with > maxRows is rejected at probe time.
        ENRICH (12, "enrichment",   25_000);

        final int multiplier;
        public final String label;
        /** Hard ceiling on workbook rows for this operation. The probe layer
         *  uses this to reject oversize files before any upload happens. */
        public final int maxRows;
        Op(int m, String label, int maxRows) { this.multiplier = m; this.label = label; this.maxRows = maxRows; }
    }

    public static class Verdict {
        public final boolean ok;
        /** Friendly business-language message; null when ok=true. */
        public final String message;
        public final long fileSize;
        public final long estimatedNeed;
        public final long availableHeadroom;

        Verdict(boolean ok, String message, long fileSize, long estimatedNeed, long availableHeadroom) {
            this.ok = ok;
            this.message = message;
            this.fileSize = fileSize;
            this.estimatedNeed = estimatedNeed;
            this.availableHeadroom = availableHeadroom;
        }
    }

    public Verdict check(long fileSize, Op op) {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long headroom = maxHeap - usedHeap - SAFETY_BUFFER_BYTES;
        long need = fileSize * op.multiplier;

        if (need <= headroom) {
            return new Verdict(true, null, fileSize, need, headroom);
        }

        String msg = String.format(
            "This file is about %s and would need roughly %s of server memory for %s, " +
            "but only about %s is currently available on this server. " +
            "Please contact IT to increase the toolkit's memory, " +
            "or split the file into smaller chunks and try again.",
            humanBytes(fileSize), humanBytes(need), op.label, humanBytes(Math.max(0, headroom)));

        logger.warning(String.format("[HEAP-GUARD] REJECT op=%s fileSize=%s need=%s headroom=%s max=%s used=%s",
            op, humanBytes(fileSize), humanBytes(need), humanBytes(headroom),
            humanBytes(maxHeap), humanBytes(usedHeap)));

        return new Verdict(false, msg, fileSize, need, headroom);
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.0f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.1f GB", gb);
    }
}
