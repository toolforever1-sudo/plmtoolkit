package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AgentRateLimiterTest {

    /** Subclass exposing a settable clock. */
    static class TestLimiter extends AgentRateLimiter {
        long nowMs = 1_000_000L;
        TestLimiter(int data, int files) { super(data, files); }
        @Override protected long now() { return nowMs; }
    }

    @Test
    void allowsUpToLimitThenBlocks() {
        TestLimiter l = new TestLimiter(3, 10);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        AgentRateLimiter.Decision d = l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA);
        assertFalse(d.allowed);
        assertTrue(d.retryAfterSeconds >= 1 && d.retryAfterSeconds <= 60);
    }

    @Test
    void dataAndFileBucketsAreIndependent() {
        TestLimiter l = new TestLimiter(1, 1);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertFalse(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.FILES).allowed);
    }

    @Test
    void keysAreIndependent() {
        TestLimiter l = new TestLimiter(1, 1);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertTrue(l.tryAcquire("ci", AgentRateLimiter.Bucket.DATA).allowed);
    }

    @Test
    void windowRefillsAfter60s() {
        TestLimiter l = new TestLimiter(1, 1);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertFalse(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        l.nowMs += 60_001L;
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
    }
}
