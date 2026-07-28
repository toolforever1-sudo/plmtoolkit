package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Lightweight heap-pressure check used as a circuit breaker before starting
 * any heap-heavy operation (POI Excel parsing, cache rebuilds, large extracts).
 *
 * <p>The goal is not to be precise — Java's GC makes that impossible without
 * pausing the world — but to prevent the obvious failure mode where one big
 * upload pushes the JVM into OOM and takes down the entire web server.
 *
 * <p>Threshold defaults to 0.80 (used / max heap). Configurable via
 * {@code app.memory.pressure-threshold}. Prod overrides to 0.70 to leave more
 * room for the in-memory ExternalSource cache load (~700 MB transient spike on
 * a 6 GB heap). Local stays at the default 0.80 since its heap is smaller.
 * Above the threshold, refuse new heavy work with HTTP 503 + a clear
 * "try again in a minute" message instead of crashing.
 */
@Service
public class MemoryGuard {

    /** Default pressure threshold (used heap / max heap). Overridden by config. */
    public static final double PRESSURE_THRESHOLD = 0.80;

    @Value("${app.memory.pressure-threshold:0.80}")
    private double pressureThreshold;

    /** True if the JVM is too close to its heap limit to safely start more heavy work. */
    public boolean isUnderPressure() {
        return usedFraction() > pressureThreshold;
    }

    /** The currently-effective threshold (config-driven). Useful for diagnostic messages. */
    public double getThreshold() {
        return pressureThreshold;
    }

    /** Used heap as a percentage (0–100) of the configured maximum. */
    public int usedPercent() {
        return (int) Math.round(usedFraction() * 100.0);
    }

    /** Used heap in MB. */
    public long usedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    /** Configured maximum heap (-Xmx) in MB. */
    public long maxMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    /** Used heap as a fraction (0.0–1.0) of the configured maximum. Useful for
     *  callers that want a numeric threshold check (e.g. the heap-pressure
     *  alert sampler) instead of the integer-rounded {@link #usedPercent()}. */
    public double usedFraction() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        if (max <= 0) return 0.0;
        return (double) used / (double) max;
    }
}
