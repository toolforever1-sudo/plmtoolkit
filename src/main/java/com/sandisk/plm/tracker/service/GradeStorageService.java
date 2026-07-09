package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * File-based persistence for the Backlog Grade dispute/audit system (see
 * {@link GradeModels}). Mirrors {@link MeetingStorageService}: this app has no
 * DB, so each audit record lives in {@code <gradeDir>/disputes/<id>.json}
 * (kind=item) or {@code <gradeDir>/logic-changes/<id>.json} (kind=rule), the
 * sent emails are appended to {@code <gradeDir>/outbox/outbox.jsonl}, and the
 * most recent grade snapshot (for the trend pill) is {@code <gradeDir>/last-grade.json}.
 * Atomic temp-file + rename writes.
 */
@Service
public class GradeStorageService {

    private static final Logger LOG = Logger.getLogger(GradeStorageService.class.getName());

    private final String baseDir;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public GradeStorageService(@Value("${plm.grade.dir:./data/grade}") String baseDir) {
        this.baseDir = baseDir;
    }

    private File disputesDir() { return ensureDir(new File(baseDir, "disputes")); }
    private File logicDir()    { return ensureDir(new File(baseDir, "logic-changes")); }
    private File outboxDir()   { return ensureDir(new File(baseDir, "outbox")); }
    private File policiesDir() { return ensureDir(new File(baseDir, "policies")); }

    // ------------------------------------------------------------------
    // Standing theme policies
    // ------------------------------------------------------------------

    public synchronized void savePolicy(GradeModels.ThemePolicy pol) {
        if (pol == null || pol.id == null) return;
        writeJson(new File(policiesDir(), sanitize(pol.id) + ".json"), pol);
    }

    /** All policies, newest first. Caller filters by {@code active}. */
    public List<GradeModels.ThemePolicy> listPolicies() {
        List<GradeModels.ThemePolicy> out = new ArrayList<>();
        File[] files = policiesDir().listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) for (File f : files) {
            try { out.add(mapper.readValue(f, GradeModels.ThemePolicy.class)); }
            catch (Exception e) { LOG.warning("[GRADE] skipping unreadable policy " + f.getName() + ": " + e.getMessage()); }
        }
        out.sort(Comparator.comparingLong((GradeModels.ThemePolicy p) -> p.createdAt).reversed());
        return out;
    }

    public GradeModels.ThemePolicy getPolicy(String id) {
        if (id == null || id.isEmpty()) return null;
        File f = new File(policiesDir(), sanitize(id) + ".json");
        if (!f.exists()) return null;
        try { return mapper.readValue(f, GradeModels.ThemePolicy.class); }
        catch (Exception e) { return null; }
    }

    /** Revert = deactivate (kept on disk for audit). */
    public void deactivatePolicy(String id) {
        GradeModels.ThemePolicy p = getPolicy(id);
        if (p == null) return;
        p.active = false;
        savePolicy(p);
    }

    // ------------------------------------------------------------------
    // Audit records
    // ------------------------------------------------------------------

    public synchronized void saveRecord(GradeModels.GradeRecord r) {
        if (r == null || r.id == null) return;
        File dir = "rule".equals(r.kind) ? logicDir() : disputesDir();
        writeJson(new File(dir, sanitize(r.id) + ".json"), r);
    }

    /** All audit records (item + rule), most recent ({@code createdAt}) first. */
    public List<GradeModels.GradeRecord> listRecords() {
        List<GradeModels.GradeRecord> out = new ArrayList<>();
        readAll(disputesDir(), out);
        readAll(logicDir(), out);
        out.sort(Comparator.comparingLong((GradeModels.GradeRecord r) -> r.createdAt).reversed());
        return out;
    }

    public void deleteRecord(String id) {
        if (id == null || id.isEmpty()) return;
        String name = sanitize(id) + ".json";
        File a = new File(disputesDir(), name), b = new File(logicDir(), name);
        if (a.exists() && !a.delete()) LOG.warning("[GRADE] could not delete " + a.getPath());
        if (b.exists() && !b.delete()) LOG.warning("[GRADE] could not delete " + b.getPath());
    }

    private void readAll(File dir, List<GradeModels.GradeRecord> out) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try {
                out.add(mapper.readValue(f, GradeModels.GradeRecord.class));
            } catch (Exception e) {
                LOG.warning("[GRADE] skipping unreadable record " + f.getName() + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Outbox (append-only JSONL)
    // ------------------------------------------------------------------

    public synchronized void appendOutbox(GradeModels.OutboxEntry e) {
        if (e == null) return;
        File f = new File(outboxDir(), "outbox.jsonl");
        try {
            String line = mapper.writeValueAsString(e) + System.lineSeparator();
            Files.write(f.toPath(), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            LOG.warning("[GRADE] outbox append failed: " + ex.getMessage());
        }
    }

    /** Outbox entries, most recent ({@code sentAt}) first. */
    public List<GradeModels.OutboxEntry> listOutbox() {
        List<GradeModels.OutboxEntry> out = new ArrayList<>();
        File f = new File(outboxDir(), "outbox.jsonl");
        if (!f.exists()) return out;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) continue;
                try { out.add(mapper.readValue(line, GradeModels.OutboxEntry.class)); }
                catch (Exception inner) { LOG.warning("[GRADE] skipping bad outbox line: " + inner.getMessage()); }
            }
        } catch (Exception e) {
            LOG.warning("[GRADE] outbox read failed: " + e.getMessage());
        }
        out.sort(Comparator.comparingLong((GradeModels.OutboxEntry e) -> e.sentAt).reversed());
        return out;
    }

    // ------------------------------------------------------------------
    // Last grade (trend baseline) — reuses GradeRecord for {gradeAfter, scoreAfter}
    // ------------------------------------------------------------------

    public synchronized void saveLastGrade(String letter, int score) {
        GradeModels.GradeRecord r = new GradeModels.GradeRecord();
        r.gradeAfter = letter; r.scoreAfter = score; r.createdAt = System.currentTimeMillis();
        writeJson(new File(ensureDir(new File(baseDir)), "last-grade.json"), r);
    }

    public GradeModels.GradeRecord readLastGrade() {
        File f = new File(baseDir, "last-grade.json");
        if (!f.exists()) return null;
        try { return mapper.readValue(f, GradeModels.GradeRecord.class); }
        catch (Exception e) { LOG.warning("[GRADE] last-grade read failed: " + e.getMessage()); return null; }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private File ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) LOG.warning("[GRADE] could not create directory " + dir.getPath());
        return dir;
    }

    private void writeJson(File f, Object value) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, value);
            Files.move(tmp.toPath(), f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ame) {
            try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception inner) { LOG.warning("[GRADE] write fallback failed for " + f.getPath() + ": " + inner.getMessage()); }
        } catch (Exception e) {
            LOG.warning("[GRADE] write failed for " + f.getPath() + ": " + e.getMessage());
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** Unused placeholder kept for symmetry with MeetingStorageService imports. */
    @SuppressWarnings("unused")
    private static List<String> empty() { return Collections.emptyList(); }
}
