package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Persistent retry queue for IMS Review Agile write-backs that aborted at
 * {@code find-drr} due to a transport-class failure (plm-agile-service
 * unreachable / connection refused / read timeout).
 *
 * <p>Scope per Vikas's design call 2026-06-05: only auto-retry when the
 * write-back never reached the first committing step. That covers the
 * 22-hour deploy-window outage that stranded Bibi's DRR-0018775 and is
 * safe to blind-retry because no Agile state was mutated yet. Failures
 * at later steps (attach succeeded but history failed, etc.) need
 * per-step idempotency tracking before they can be auto-retried — for
 * now the operator uses the admin {@code /replay-writeback} endpoint.
 *
 * <p>Backoff schedule: 1 min → 5 min → 15 min → 60 min → give up.
 * Gives ~80 minutes for the agile-service watchdog (or a human) to
 * bring plm-agile-service back up.
 *
 * <p>Storage: append-only JSONL at
 * {@code ./data/ims-review/retry-queue.jsonl}. On every mutation, the
 * full in-memory queue is re-snapshotted to that file via temp-file +
 * atomic move so a half-written file can never be loaded on restart.
 */
@Service
public class ImsReviewRetryQueue {

    private static final Logger LOG = Logger.getLogger(ImsReviewRetryQueue.class.getName());

    /** Backoff schedule in milliseconds — when the i-th attempt fires next. */
    private static final long[] BACKOFF_MS = {
            60_000L,        // 1 min after the original failure
            5L  * 60_000L,  // 5 min after attempt 1
            15L * 60_000L,  // 15 min after attempt 2
            60L * 60_000L   // 60 min after attempt 3
    };

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Value("${app.ims-review.retry-queue-file:./data/ims-review/retry-queue.jsonl}")
    private String queueFile;

    /** Guarded by {@code this}. */
    private final List<Entry> entries = new ArrayList<>();

    /** One pending retry. Survives JVM restarts. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Entry {
        public String id;                 // UUID — stable across attempts
        public String docNumber;
        public String drrNumber;
        public String eventType;          // DM_RESPONSE_APPROVED, etc.
        public String eventTs;            // ISO ts of the response event
        public String enqueuedAt;         // ISO ts of first enqueue
        public int attempts;              // count of attempts so far (0 = enqueued but not tried)
        public String lastAttemptAt;      // ISO ts (null until first attempt)
        public String lastErrorReason;    // SDK error from most recent attempt
        public long nextAttemptAtMs;      // epoch ms — when the runner should pick this up
        public String enqueuedReason;     // what made us enqueue (the original error)
        public boolean gaveUp;            // terminal — runner skips, operator must use replay endpoint
        public String gaveUpAt;
        /** Filled when the retry runner succeeds — entry then drops from the queue. */
        public String resolvedAt;
        public String resolvedCorrId;

        public Entry() {}
        Entry(String id, String doc, String drr, String et, String ts, String enqAt,
              long nextMs, String reason) {
            this.id = id; this.docNumber = doc; this.drrNumber = drr; this.eventType = et;
            this.eventTs = ts; this.enqueuedAt = enqAt;
            this.nextAttemptAtMs = nextMs; this.enqueuedReason = reason;
        }
    }

    @PostConstruct
    public void load() {
        File f = new File(queueFile);
        if (!f.exists()) {
            LOG.info("[IMS-RETRY] no queue file at " + queueFile + " — starting empty");
            return;
        }
        try {
            int loaded = 0;
            for (String line : Files.readAllLines(f.toPath())) {
                if (line == null || line.trim().isEmpty()) continue;
                try {
                    Entry e = mapper.readValue(line, Entry.class);
                    if (e.resolvedAt == null) {
                        synchronized (this) { entries.add(e); }
                        loaded++;
                    }
                } catch (Exception parseErr) {
                    LOG.warning("[IMS-RETRY] skipped malformed line: " + parseErr.getMessage());
                }
            }
            LOG.info("[IMS-RETRY] loaded " + loaded + " pending retries from " + queueFile);
        } catch (Exception e) {
            LOG.warning("[IMS-RETRY] startup load failed: " + e.getMessage());
        }
    }

    /** Add a new retry. Coalesces on (docNumber, drrNumber, eventType) so two
     *  rapid failures for the same event don't queue two retries. */
    public synchronized Entry enqueue(String docNumber, String drrNumber,
                                      String eventType, String eventTs,
                                      String enqueuedReason) {
        for (Entry existing : entries) {
            if (sameTarget(existing, docNumber, drrNumber, eventType)) {
                LOG.info("[IMS-RETRY] coalesce — existing entry " + existing.id
                        + " for doc=" + docNumber + " drr=" + drrNumber
                        + " eventType=" + eventType + " (already queued)");
                return existing;
            }
        }
        Entry e = new Entry(
                java.util.UUID.randomUUID().toString(),
                docNumber, drrNumber, eventType, eventTs,
                Instant.now().toString(),
                System.currentTimeMillis() + BACKOFF_MS[0],
                enqueuedReason);
        entries.add(e);
        persist();
        LOG.info("[IMS-RETRY] enqueued doc=" + docNumber + " drr=" + drrNumber
                + " eventType=" + eventType
                + " nextAttemptInMs=" + (e.nextAttemptAtMs - System.currentTimeMillis())
                + " reason=" + enqueuedReason);
        return e;
    }

    /** Returns a snapshot of entries due-or-overdue right now and not in
     *  the give-up state. Safe to iterate without holding the lock. */
    public synchronized List<Entry> dueNow() {
        long now = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.gaveUp) continue;
            if (e.resolvedAt != null) continue;
            if (e.nextAttemptAtMs <= now) out.add(e);
        }
        return out;
    }

    /** Snapshot of every still-pending entry (for the admin diagnostic
     *  endpoint). */
    public synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    /** Called by the retry runner after each attempt. Updates attempts +
     *  nextAttemptAtMs, or marks gave-up if we exhausted BACKOFF_MS. */
    public synchronized void markAttempted(Entry e, boolean success, String errReason, String corrId) {
        e.attempts++;
        e.lastAttemptAt = Instant.now().toString();
        if (success) {
            e.resolvedAt = e.lastAttemptAt;
            e.resolvedCorrId = corrId;
            // Remove from the pending list so dueNow() doesn't return it again
            entries.removeIf(x -> x.id.equals(e.id));
            persist();
            LOG.info("[IMS-RETRY] resolved id=" + e.id + " doc=" + e.docNumber
                    + " drr=" + e.drrNumber + " attempts=" + e.attempts
                    + " corrId=" + corrId);
            return;
        }
        e.lastErrorReason = errReason == null ? "(unknown)" : errReason;
        // Schedule next attempt or mark gave-up
        int nextIdx = e.attempts; // attempts already incremented; index into BACKOFF_MS
        if (nextIdx >= BACKOFF_MS.length) {
            e.gaveUp = true;
            e.gaveUpAt = e.lastAttemptAt;
            LOG.warning("[IMS-RETRY] gave up id=" + e.id + " doc=" + e.docNumber
                    + " drr=" + e.drrNumber + " after " + e.attempts
                    + " attempts. lastError=" + e.lastErrorReason);
        } else {
            e.nextAttemptAtMs = System.currentTimeMillis() + BACKOFF_MS[nextIdx];
            LOG.info("[IMS-RETRY] reschedule id=" + e.id + " doc=" + e.docNumber
                    + " attempts=" + e.attempts
                    + " nextInMs=" + BACKOFF_MS[nextIdx]
                    + " lastError=" + e.lastErrorReason);
        }
        persist();
    }

    /** Admin operation: drop an entry from the queue without retrying. Used
     *  when the operator resolved the underlying failure manually via the
     *  replay-writeback endpoint and wants to clear the retry record. */
    public synchronized boolean removeById(String id) {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.id.equals(id)) {
                it.remove();
                persist();
                LOG.info("[IMS-RETRY] removed by admin id=" + id);
                return true;
            }
        }
        return false;
    }

    private boolean sameTarget(Entry e, String doc, String drr, String et) {
        return safeEq(e.docNumber, doc) && safeEq(e.drrNumber, drr) && safeEq(e.eventType, et);
    }

    private static boolean safeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private synchronized void persist() {
        try {
            Path out = Paths.get(queueFile);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Path tmp = Paths.get(queueFile + ".tmp");
            try (java.io.BufferedWriter w = Files.newBufferedWriter(tmp)) {
                for (Entry e : entries) {
                    w.write(mapper.writeValueAsString(e));
                    w.newLine();
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warning("[IMS-RETRY] persist failed: " + e.getMessage());
        }
    }

    // Public summary for diagnostics
    public synchronized Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        int pending = 0, gaveUp = 0;
        long earliestDueMs = Long.MAX_VALUE;
        for (Entry e : entries) {
            if (e.gaveUp) gaveUp++;
            else {
                pending++;
                if (e.nextAttemptAtMs < earliestDueMs) earliestDueMs = e.nextAttemptAtMs;
            }
        }
        m.put("pendingCount", pending);
        m.put("gaveUpCount", gaveUp);
        if (pending > 0 && earliestDueMs != Long.MAX_VALUE) {
            long inMs = earliestDueMs - System.currentTimeMillis();
            m.put("nextDueInSeconds", Math.max(0L, inMs / 1000));
        }
        m.put("entries", entries);
        return m;
    }
}
