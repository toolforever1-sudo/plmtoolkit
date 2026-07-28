package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory LRU cache of Agile attachment bytes for the Agent API file endpoints.
 *
 * <p>Agile attachment filenames carry the revision (e.g. "… - Rev 8.docx"), and a
 * released doc's file for a given rev is immutable — so a cache keyed on
 * {@code item|fileName} self-invalidates: a new rev is a new filename → a miss.
 * A TTL guards the rare in-place replacement. Bounded by total bytes (LRU eviction).
 * Set {@code app.agent.filecache.max-bytes=0} to disable.
 *
 * <p>Purpose: repeated reads of the same policy documents (many agent users, one
 * shared key) are served locally instead of re-invoking the Agile SDK — which both
 * removes load on plm-agile-service and lets cache hits skip the file rate bucket.
 */
@Service
public class AttachmentCache {

    public static final class Entry {
        public final byte[] bytes;
        public final String contentType;
        public final String filename;
        final long storedAt;
        public Entry(byte[] bytes, String contentType, String filename, long storedAt) {
            this.bytes = bytes; this.contentType = contentType; this.filename = filename; this.storedAt = storedAt;
        }
    }

    private final long maxBytes;
    private final long ttlMs;
    private final LinkedHashMap<String, Entry> map =
        new LinkedHashMap<>(64, 0.75f, true /* access-order → eldest = least-recently-used */);
    private long currentBytes = 0L;

    public AttachmentCache(@Value("${app.agent.filecache.max-bytes:536870912}") long maxBytes,
                           @Value("${app.agent.filecache.ttl-minutes:720}") long ttlMinutes) {
        this.maxBytes = maxBytes;
        this.ttlMs = ttlMinutes * 60_000L;
    }

    /** Test seam. */
    protected long now() { return System.currentTimeMillis(); }

    private static String key(String item, String fileName) {
        return (item == null ? "" : item.trim().toUpperCase()) + "|" + (fileName == null ? "" : fileName.trim());
    }

    /** Cached entry, or null on miss/expired/disabled. */
    public synchronized Entry get(String item, String fileName) {
        if (maxBytes <= 0) return null;
        Entry e = map.get(key(item, fileName));
        if (e == null) return null;
        if (now() - e.storedAt > ttlMs) {
            map.remove(key(item, fileName));
            currentBytes -= e.bytes.length;
            return null;
        }
        return e;
    }

    /** Store clean bytes. No-op when disabled, bytes empty, or a single file exceeds the cap. */
    public synchronized void put(String item, String fileName, byte[] bytes, String contentType, String filename) {
        if (maxBytes <= 0 || bytes == null || bytes.length == 0 || bytes.length > maxBytes) return;
        String k = key(item, fileName);
        Entry prev = map.remove(k);
        if (prev != null) currentBytes -= prev.bytes.length;
        // Evict least-recently-used until the new entry fits.
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (currentBytes + bytes.length > maxBytes && it.hasNext()) {
            Map.Entry<String, Entry> eldest = it.next();
            currentBytes -= eldest.getValue().bytes.length;
            it.remove();
        }
        map.put(k, new Entry(bytes, contentType, filename, now()));
        currentBytes += bytes.length;
    }

    public synchronized int size() { return map.size(); }
    public synchronized long bytes() { return currentBytes; }
    public boolean enabled() { return maxBytes > 0; }
}
