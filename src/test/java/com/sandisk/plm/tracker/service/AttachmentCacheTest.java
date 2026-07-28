package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AttachmentCacheTest {

    /** Cache with a controllable clock for TTL tests. */
    static class TestCache extends AttachmentCache {
        long nowMs = 1_000_000L;
        TestCache(long maxBytes, long ttlMinutes) { super(maxBytes, ttlMinutes); }
        @Override protected long now() { return nowMs; }
    }

    @Test
    void putGetRoundTrip() {
        TestCache c = new TestCache(1_000_000, 60);
        c.put("D1", "a.pdf", new byte[]{1,2,3}, "application/pdf", "a.pdf");
        AttachmentCache.Entry e = c.get("D1", "a.pdf");
        assertNotNull(e);
        assertArrayEquals(new byte[]{1,2,3}, e.bytes);
        assertEquals("application/pdf", e.contentType);
        assertEquals("a.pdf", e.filename);
    }

    @Test
    void keyIsItemCaseInsensitivePlusFilename() {
        TestCache c = new TestCache(1_000_000, 60);
        c.put("d1", "a.pdf", new byte[]{1}, "x", "a.pdf");
        assertNotNull(c.get("D1", "a.pdf"));           // item case-insensitive
        assertNull(c.get("D1", "other.pdf"));          // filename part matters
    }

    @Test
    void ttlExpiry() {
        TestCache c = new TestCache(1_000_000, 10);    // 10 min TTL
        c.put("D1", "a.pdf", new byte[]{1}, "x", "a.pdf");
        assertNotNull(c.get("D1", "a.pdf"));
        c.nowMs += 11 * 60_000L;                        // advance past TTL
        assertNull(c.get("D1", "a.pdf"));
        assertEquals(0, c.size());                      // expired entry dropped
    }

    @Test
    void lruEvictionBySize() {
        TestCache c = new TestCache(10, 60);            // 10-byte cap
        c.put("D1", "a", new byte[]{1,2,3,4,5,6}, "x", "a");  // 6 bytes
        c.get("D1", "a");                               // touch → most-recently-used
        c.put("D2", "b", new byte[]{1,2,3,4,5,6}, "x", "b");  // 6 bytes → 12 > 10 → evict LRU (D1)
        assertNull(c.get("D1", "a"));
        assertNotNull(c.get("D2", "b"));
        assertTrue(c.bytes() <= 10);
    }

    @Test
    void oversizeFileNotCached() {
        TestCache c = new TestCache(10, 60);
        c.put("D1", "big", new byte[20], "x", "big");   // single file > cap
        assertNull(c.get("D1", "big"));
        assertEquals(0, c.size());
    }

    @Test
    void disabledWhenZero() {
        TestCache c = new TestCache(0, 60);
        assertFalse(c.enabled());
        c.put("D1", "a.pdf", new byte[]{1}, "x", "a.pdf");
        assertNull(c.get("D1", "a.pdf"));
    }
}
