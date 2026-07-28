package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class DebugHistoryService {

    private static final Logger logger = Logger.getLogger(DebugHistoryService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_PER_USER = 50;

    @Value("${app.debug-history.file:./data/debug-history.json}")
    private String filePath;

    // username -> list of entries (newest first)
    private final ConcurrentHashMap<String, List<DebugEntry>> history = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    public List<DebugEntry> getHistory(String username) {
        return history.getOrDefault(username, new ArrayList<>());
    }

    public void addEntry(String username, DebugEntry entry) {
        List<DebugEntry> list = history.computeIfAbsent(username, k -> new ArrayList<>());
        list.add(0, entry); // newest first
        // Cap at MAX_PER_USER
        while (list.size() > MAX_PER_USER) list.remove(list.size() - 1);
        saveToDisk();
    }

    public void clearHistory(String username) {
        history.remove(username);
        saveToDisk();
    }

    private void loadFromDisk() {
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            Map<String, List<DebugEntry>> data = mapper.readValue(f,
                new TypeReference<Map<String, List<DebugEntry>>>() {});
            history.putAll(data);
            int total = data.values().stream().mapToInt(List::size).sum();
            logger.info("[DEBUG-HISTORY] Loaded " + total + " entries for " + data.size() + " users");
        } catch (Exception e) {
            logger.warning("[DEBUG-HISTORY] Failed to load: " + e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        try {
            File f = new File(filePath);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, history);
        } catch (Exception e) {
            logger.warning("[DEBUG-HISTORY] Failed to save: " + e.getMessage());
        }
    }

    public static class DebugEntry {
        public String id;
        public String timestamp;      // ISO instant
        public String inputFile;       // filename or "Pasted text"
        public String rootCause;       // extracted exception summary
        public int tracesFound;
        public int sourceRefs;
        public int sourcesResolved;
        public long queryTimeMs;
        public String analysis;        // full AI markdown
        public String stackTrace;      // raw stack trace
        public List<Map<String, String>> sources; // source resolution details
    }
}
