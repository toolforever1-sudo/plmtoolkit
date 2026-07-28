package com.sandisk.plm.tracker.service;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.logging.Logger;

/**
 * File-based cache for AI error analyses. Each unique stack trace (by normalized hash)
 * gets its own JSON file in ./data/monitor-error-cache/. No automatic TTL — entries
 * persist until manually cleared.
 */
@Service
public class MonitorAnalysisCacheService {

    private static final Logger logger = Logger.getLogger(MonitorAnalysisCacheService.class.getName());
    private static final Path CACHE_DIR = Paths.get("./data/monitor-error-cache");

    public MonitorAnalysisCacheService() {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            logger.warning("[MONITOR-CACHE] Failed to create cache directory: " + e.getMessage());
        }
    }

    /**
     * Normalize a stack trace for hashing: lowercase, collapse whitespace, strip line numbers.
     * This ensures the same logical error maps to the same hash even if line numbers drift.
     */
    public String normalizeTrace(String stackTrace) {
        if (stackTrace == null) return "";
        // Strip line numbers from stack frames: (File.java:123) -> (File.java)
        String normalized = stackTrace.replaceAll(":\\d+\\)", ")");
        // Lowercase and collapse whitespace
        normalized = normalized.toLowerCase().replaceAll("\\s+", " ").trim();
        return normalized;
    }

    /**
     * Compute SHA-256 hash of normalized trace.
     */
    public String computeHash(String normalizedTrace) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalizedTrace.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            // Fallback to simple hash
            return Integer.toHexString(normalizedTrace.hashCode());
        }
    }

    /**
     * Look up a cached analysis by trace hash. Returns the raw JSON string, or null if not cached.
     */
    public String getCached(String traceHash) {
        Path file = CACHE_DIR.resolve(traceHash + ".json");
        if (!Files.exists(file)) return null;
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            logger.info("[MONITOR-CACHE] Cache HIT for " + traceHash.substring(0, 12) + "...");
            return json;
        } catch (IOException e) {
            logger.warning("[MONITOR-CACHE] Failed to read cache file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Store an analysis result in the cache.
     */
    public void putCache(String traceHash, String json) {
        Path file = CACHE_DIR.resolve(traceHash + ".json");
        try {
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            logger.info("[MONITOR-CACHE] Cached analysis for " + traceHash.substring(0, 12) + "...");
        } catch (IOException e) {
            logger.warning("[MONITOR-CACHE] Failed to write cache file: " + e.getMessage());
        }
    }

    /**
     * Retrieve a cached summary for a given trace hash (used for "resolved" notifications).
     * Returns just the summary string, or null if not cached.
     */
    public String getCachedSummary(String traceHash) {
        String json = getCached(traceHash);
        if (json == null) return null;
        // Extract "summary" field from JSON
        return extractJsonField(json, "summary");
    }

    /**
     * Simple JSON field extractor — avoids adding a JSON library dependency.
     * Finds "fieldName":"value" and returns the value.
     */
    public static String extractJsonField(String json, String fieldName) {
        String search = "\"" + fieldName + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(":", idx + search.length());
        if (colonIdx < 0) return null;
        // Skip whitespace after colon
        int startQuote = json.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = startQuote + 1;
        while (endQuote < json.length()) {
            char c = json.charAt(endQuote);
            if (c == '\\') { endQuote += 2; continue; }
            if (c == '"') break;
            endQuote++;
        }
        if (endQuote <= startQuote + 1) return null;
        return json.substring(startQuote + 1, endQuote)
                .replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
