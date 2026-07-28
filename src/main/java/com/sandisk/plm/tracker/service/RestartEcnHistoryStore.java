package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Append-only JSONL store of Restart ECN history (§4). One JSON line per save;
 * the newest line for an {@code ecn} is the current state (last-wins on load),
 * so the file survives JVM restarts. Mirrors the {@code ims-review/queue.jsonl}
 * pattern. In-memory map is the read path; every mutation appends + updates it.
 */
@Component
public class RestartEcnHistoryStore {

    private static final Logger LOG = Logger.getLogger(RestartEcnHistoryStore.class.getName());
    private static final ObjectMapper M = new ObjectMapper();

    @Value("${restartEcn.history-file:./data/restart-ecn/history.jsonl}")
    private String historyFile;

    /** ecn -> current record. Insertion order = creation order. */
    private final Map<String, Record> byEcn = new LinkedHashMap<>();

    public static final class BundledEcn {
        public String ecn, proposal, itOwner, itOwnerLoginId;
    }

    public static final class FileEntry {
        public String name;
        public long size;
        public List<String> ecns = new ArrayList<>();
        public String type, by, at;
    }

    public static final class Activity {
        public String ts, type, text, by;   // type: created | ecns-added | files-added | notify
    }

    public static final class Record {
        public String ecn, deployDate, itOwnerLogin, itOwnerName, createdBy, createdAt;
        public String status = "Pending";
        public List<BundledEcn> bundled = new ArrayList<>();
        public List<FileEntry> files = new ArrayList<>();
        public List<Activity> activity = new ArrayList<>();   // newest-first
    }

    public static String nowIso() { return Instant.now().toString(); }

    @PostConstruct
    public synchronized void load() {
        try {
            Path p = Paths.get(historyFile);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            if (!Files.exists(p)) { LOG.info("[RESTART-HIST] no history file yet at " + p); return; }
            int lines = 0;
            try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        Record r = M.readValue(line, Record.class);
                        if (r != null && r.ecn != null) { byEcn.put(r.ecn, r); lines++; }
                    } catch (Exception badLine) {
                        LOG.warning("[RESTART-HIST] skipping malformed line: " + badLine.getMessage());
                    }
                }
            }
            LOG.info("[RESTART-HIST] loaded " + byEcn.size() + " Restart ECN record(s) from " + lines + " line(s)");
        } catch (Exception e) {
            LOG.warning("[RESTART-HIST] load failed (starting empty): " + e.getMessage());
        }
    }

    public synchronized Record get(String ecn) { return ecn == null ? null : byEcn.get(ecn); }

    /** All records, newest-created first. */
    public synchronized List<Record> list() {
        List<Record> out = new ArrayList<>(byEcn.values());
        java.util.Collections.reverse(out);
        return out;
    }

    /** Persist a record (append a full snapshot line + update the in-memory map). */
    public synchronized void save(Record r) {
        if (r == null || r.ecn == null) return;
        byEcn.put(r.ecn, r);
        try {
            Path p = Paths.get(historyFile);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            try (BufferedWriter bw = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                bw.write(M.writeValueAsString(r));
                bw.newLine();
            }
        } catch (Exception e) {
            LOG.warning("[RESTART-HIST] save failed for " + r.ecn + ": " + e.getMessage());
        }
    }

    public synchronized void addActivity(Record r, String type, String text, String by) {
        Activity a = new Activity();
        a.ts = nowIso(); a.type = type; a.text = text; a.by = by;
        r.activity.add(0, a);   // newest-first
    }
}
