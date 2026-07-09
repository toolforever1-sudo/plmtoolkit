package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Per-(key,bucket) fixed-window rate limiter for the Agent API. DATA and FILES
 * are independent buckets so a burst of downloads can't starve queries and vice
 * versa. On block, reports the seconds until the current window resets.
 */
@Service
public class AgentRateLimiter {

    public enum Bucket { DATA, FILES }

    public static final class Decision {
        public final boolean allowed;
        public final long retryAfterSeconds; // 0 when allowed
        public Decision(boolean allowed, long retryAfterSeconds) {
            this.allowed = allowed; this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private static final class Window { long resetAtMs; int count; }

    private final int dataPerMin;
    private final int filesPerMin;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AgentRateLimiter(@Value("${app.agent.rate.data-per-min:60}") int dataPerMin,
                            @Value("${app.agent.rate.files-per-min:10}") int filesPerMin) {
        this.dataPerMin = dataPerMin;
        this.filesPerMin = filesPerMin;
    }

    /** Test seam — overridden in tests to control time. */
    protected long now() { return System.currentTimeMillis(); }

    public synchronized Decision tryAcquire(String label, Bucket bucket) {
        int limit = bucket == Bucket.FILES ? filesPerMin : dataPerMin;
        String k = label + ":" + bucket;
        long now = now();
        Window w = windows.get(k);
        if (w == null || now >= w.resetAtMs) {
            w = new Window();
            w.resetAtMs = now + 60_000L;
            w.count = 0;
            windows.put(k, w);
        }
        if (w.count < limit) {
            w.count++;
            return new Decision(true, 0);
        }
        long retry = Math.max(1, (w.resetAtMs - now + 999) / 1000);
        return new Decision(false, retry);
    }
}
