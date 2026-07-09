package com.sandisk.plm.tracker.model;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class BomRaceRun {
    public final String runId;
    public final List<String> items;
    public final int maxDepth;
    public final long startedAtNanos;
    public final long createdAtMillis;
    public volatile SseEmitter emitter;
    public volatile CompletableFuture<?> toolkitFuture;
    public volatile CompletableFuture<?> sdkFuture;

    // Bookkeeping accumulated during the race, read by finishRace.
    public volatile List<BomResult> toolkitRows = new ArrayList<>();
    public volatile List<Map<String, Object>> sdkRows = new ArrayList<>();
    public volatile long toolkitTotalMs = 0L;
    public volatile long sdkTotalMs = 0L;
    public volatile int sdkErrorCount = 0;

    /** Set true by finishRace before completing the emitter so the cancel() callback
     *  doesn't evict the run — downloads need it to survive past race-done until TTL. */
    public volatile boolean finished = false;

    /** Captured at /start so finishRace can persist the run's owner to bomrace_run.username. */
    public volatile String username = "";

    public BomRaceRun(String runId, List<String> items, int maxDepth, long startedAtNanos) {
        this.runId = runId;
        this.items = items;
        this.maxDepth = maxDepth;
        this.startedAtNanos = startedAtNanos;
        this.createdAtMillis = System.currentTimeMillis();
    }
}
