package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Bounded, single-thread job queue for heap-heavy work (currently: external-source
 * cache rebuilds). All cache rebuilds — manual upload, manual Generate, scheduled,
 * and startup auto-rebuild — flow through here, so at most one POI parse can be
 * running at any moment in the JVM.
 *
 * <p>Combined with {@link MemoryGuard}, this gives us two layers of protection:
 * <ol>
 *   <li>Memory pressure check: refuse with HTTP 503 if heap is already tight.</li>
 *   <li>Queue capacity: refuse with HTTP 503 if too many jobs are already pending.</li>
 * </ol>
 *
 * <p>Without this, multiple concurrent heavy uploads compound their heap usage and
 * OOM the JVM, taking the whole web server down for everyone — exactly the incident
 * we just had with Krati's BOM Notes upload.
 */
@Service
public class JobQueueService {

    private static final Logger LOG = Logger.getLogger(JobQueueService.class.getName());

    /** Max simultaneously queued (not yet running) jobs. Excess submissions are rejected. */
    public static final int MAX_QUEUED = 5;

    /** How many recent jobs to keep in memory for the status UI. */
    private static final int MAX_HISTORY = 50;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ext-rebuild-worker");
        t.setDaemon(true);
        return t;
    });

    /** Newest-first list of jobs (running + recent finished). */
    private final ConcurrentLinkedDeque<JobInfo> jobs = new ConcurrentLinkedDeque<>();

    /** Tracks how many jobs are still queued (not yet started by the worker). */
    private final AtomicInteger queuedCount = new AtomicInteger(0);

    private final AtomicLong jobIdSeq = new AtomicLong(System.currentTimeMillis());

    @Autowired
    private MemoryGuard memoryGuard;

    @Autowired
    private ExternalSourceService externalSourceService;

    /**
     * Submit an external-source rebuild. Returns immediately. The returned
     * {@link JobInfo} carries a status of {@code queued}, {@code running}, or
     * {@code rejected}. Callers should poll {@link #getJob(String)} for updates.
     */
    public JobInfo submitRebuild(String sourceId, String sourceName, String submittedBy) {
        // 1) Memory circuit breaker — refuse if the JVM is already under pressure.
        if (memoryGuard.isUnderPressure()) {
            JobInfo rejected = newJob("rebuild", sourceId, sourceName, submittedBy);
            rejected.status = "rejected";
            rejected.completedAt = rejected.submittedAt;
            rejected.message = "Server is busy: heap is " + memoryGuard.usedPercent()
                    + "% full (" + memoryGuard.usedMb() + " / " + memoryGuard.maxMb()
                    + " MB used). Please try again in a minute.";
            recordJob(rejected);
            LOG.warning("[JOB-QUEUE] Rejected (memory pressure) rebuild '" + sourceId + "' for " + submittedBy);
            return rejected;
        }

        // 2) Queue capacity — refuse if too many uploads are already pending.
        if (queuedCount.get() >= MAX_QUEUED) {
            JobInfo rejected = newJob("rebuild", sourceId, sourceName, submittedBy);
            rejected.status = "rejected";
            rejected.completedAt = rejected.submittedAt;
            rejected.message = "Rebuild queue is full (" + MAX_QUEUED + " pending). "
                    + "Please wait for the current rebuilds to finish, then try again.";
            recordJob(rejected);
            LOG.warning("[JOB-QUEUE] Rejected (queue full) rebuild '" + sourceId + "' for " + submittedBy);
            return rejected;
        }

        // 3) Accept and enqueue. Worker thread picks it up serially.
        JobInfo job = newJob("rebuild", sourceId, sourceName, submittedBy);
        job.status = "queued";
        job.queuePosition = queuedCount.incrementAndGet();
        recordJob(job);
        LOG.info("[JOB-QUEUE] Queued rebuild '" + sourceId + "' (job " + job.id + ", position " + job.queuePosition + ") for " + submittedBy);

        worker.submit(() -> runRebuildJob(job));
        return job;
    }

    private void runRebuildJob(JobInfo job) {
        queuedCount.decrementAndGet();
        job.queuePosition = null;
        job.startedAt = System.currentTimeMillis();
        job.status = "running";
        LOG.info("[JOB-QUEUE] Running rebuild '" + job.sourceId + "' (job " + job.id + ")");

        try {
            // Live progress: ExternalSourceService calls back with the rows-processed
            // count every ~1000 rows. The frontend polls /jobs/{id} every 2s and
            // reflects this in the banner ("12,450 rows processed").
            java.util.function.Consumer<Integer> progressCb = (rows) -> job.rowsProcessed = rows;
            Map<String, Object> result = externalSourceService.rebuildSource(job.sourceId, progressCb);
            job.completedAt = System.currentTimeMillis();
            long elapsedSec = Math.max(1, (job.completedAt - job.startedAt) / 1000);

            if ("success".equals(result.get("status"))) {
                job.status = "success";
                Object recObj = result.get("uniqueRecords");
                if (recObj instanceof Number) job.recordsCount = ((Number) recObj).intValue();
                job.message = (job.recordsCount != null ? job.recordsCount : 0)
                        + " records cached in " + elapsedSec + "s";
                LOG.info("[JOB-QUEUE] Done '" + job.sourceId + "': " + job.message);
            } else {
                job.status = "failed";
                job.message = String.valueOf(result.getOrDefault("message", "unknown error"));
                LOG.warning("[JOB-QUEUE] Failed '" + job.sourceId + "': " + job.message);
            }
        } catch (Throwable t) {
            // Belt-and-suspenders: catching Throwable means a runaway error never
            // kills the worker thread. Without this, an OOM here would orphan the
            // executor and queue future jobs forever.
            job.completedAt = System.currentTimeMillis();
            job.status = "failed";
            job.message = "Crashed during rebuild: "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            LOG.severe("[JOB-QUEUE] Crashed '" + job.sourceId + "': " + job.message);
        }
    }

    /** Fetch a single job by id (for polling). Returns null if unknown. */
    public JobInfo getJob(String jobId) {
        if (jobId == null) return null;
        for (JobInfo j : jobs) {
            if (jobId.equals(j.id)) return j;
        }
        return null;
    }

    /** Snapshot of recent jobs (newest first) — used by the Extensions UI. */
    public List<JobInfo> recentJobs() {
        return new ArrayList<>(jobs);
    }

    /** Number of jobs currently queued (not yet started). */
    public int currentQueueDepth() {
        return queuedCount.get();
    }

    /**
     * After Spring is fully started, enqueue a rebuild for any source whose JSON
     * cache file is missing. Runs once. Tomcat is already serving by this point,
     * so even if a rebuild is slow or fails the UI stays responsive.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void enqueueStartupRebuilds() {
        try {
            for (String sourceId : externalSourceService.listSourcesMissingCache()) {
                LOG.info("[JOB-QUEUE] Cache missing for '" + sourceId + "' — enqueuing startup rebuild");
                submitRebuild(sourceId, sourceId, "system-startup");
            }
        } catch (Exception e) {
            LOG.warning("[JOB-QUEUE] enqueueStartupRebuilds failed: " + e.getMessage());
        }
    }

    /**
     * Hourly check: any source whose configured schedule hour matches the current
     * hour gets enqueued through the same bounded queue as user-triggered rebuilds.
     * Replaces the old per-service scheduled rebuild so we can never have a
     * scheduled job and a user-triggered job parsing in parallel.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void enqueueScheduledRebuilds() {
        try {
            for (String[] pair : externalSourceService.listScheduledForCurrentHour()) {
                LOG.info("[JOB-QUEUE] Scheduled hour matched — enqueuing rebuild for '" + pair[0] + "'");
                submitRebuild(pair[0], pair[1], "scheduler");
            }
        } catch (Exception e) {
            LOG.warning("[JOB-QUEUE] enqueueScheduledRebuilds failed: " + e.getMessage());
        }
    }

    private JobInfo newJob(String type, String sourceId, String sourceName, String submittedBy) {
        JobInfo j = new JobInfo();
        j.id = "job-" + jobIdSeq.incrementAndGet();
        j.type = type;
        j.sourceId = sourceId;
        j.sourceName = sourceName;
        j.submittedBy = submittedBy;
        j.submittedAt = System.currentTimeMillis();
        return j;
    }

    private void recordJob(JobInfo job) {
        jobs.addFirst(job);
        while (jobs.size() > MAX_HISTORY) jobs.pollLast();
    }

    /** Public DTO returned to API callers — fields are public for Jackson. */
    public static class JobInfo {
        public String id;
        public String type;             // "rebuild"
        public String sourceId;
        public String sourceName;
        public String submittedBy;
        public Long submittedAt;        // epoch ms
        public Long startedAt;          // epoch ms (null while queued)
        public Long completedAt;        // epoch ms (null while queued/running)
        public String status;           // "queued" | "running" | "success" | "failed" | "rejected"
        public String message;          // human-readable result/error
        public Integer recordsCount;    // populated on success
        public Integer queuePosition;   // 1-based; null once running/done
        public volatile Integer rowsProcessed; // live progress while running (volatile — written by worker, read by HTTP threads)
    }
}
