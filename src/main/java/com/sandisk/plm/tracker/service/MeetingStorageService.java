package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * File-based persistence for Meeting Mode (see {@link MeetingModels}). This app
 * has no DB, so each live {@link MeetingModels.Session} lives in
 * {@code ./data/meeting-sessions/<id>.json} and each frozen
 * {@link MeetingModels.Record} in {@code ./data/meeting-records/<id>.json}.
 * Atomic temp-file + rename write, mirroring {@link DraftStorageService}.
 *
 * <p>On wrap-up send the session is frozen into a record and the live session
 * file is deleted — the record is the durable artifact thereafter.
 */
@Service
public class MeetingStorageService {

    private static final Logger LOG = Logger.getLogger(MeetingStorageService.class.getName());

    @Value("${app.meeting.sessions.dir:./data/meeting-sessions}")
    private String sessionsDir;
    @Value("${app.meeting.records.dir:./data/meeting-records}")
    private String recordsDir;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ------------------------------------------------------------------
    // Sessions (live)
    // ------------------------------------------------------------------

    public synchronized void saveSession(MeetingModels.Session s) {
        if (s == null || s.id == null) return;
        writeJson(new File(ensureDir(sessionsDir), sanitize(s.id) + ".json"), s);
    }

    public MeetingModels.Session readSession(String id) {
        if (id == null || id.isEmpty()) return null;
        File f = new File(sessionsDir, sanitize(id) + ".json");
        if (!f.exists()) return null;
        try {
            return mapper.readValue(f, MeetingModels.Session.class);
        } catch (Exception e) {
            LOG.warning("[MEETING] failed to read session " + f.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    public void deleteSession(String id) {
        if (id == null || id.isEmpty()) return;
        File f = new File(sessionsDir, sanitize(id) + ".json");
        if (f.exists() && !f.delete()) LOG.warning("[MEETING] could not delete session " + f.getPath());
    }

    // ------------------------------------------------------------------
    // Records (frozen)
    // ------------------------------------------------------------------

    public synchronized void saveRecord(MeetingModels.Record r) {
        if (r == null || r.id == null) return;
        writeJson(new File(ensureDir(recordsDir), sanitize(r.id) + ".json"), r);
    }

    public MeetingModels.Record readRecord(String id) {
        if (id == null || id.isEmpty()) return null;
        File f = new File(recordsDir, sanitize(id) + ".json");
        if (!f.exists()) return null;
        try {
            return mapper.readValue(f, MeetingModels.Record.class);
        } catch (Exception e) {
            LOG.warning("[MEETING] failed to read record " + f.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    /** All records, most recent (highest {@code endedAt}) first. Skips any
     *  unparseable file rather than failing the whole list. */
    public List<MeetingModels.Record> listRecords() {
        List<MeetingModels.Record> out = new ArrayList<>();
        File dir = new File(recordsDir);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            try {
                MeetingModels.Record r = mapper.readValue(f, MeetingModels.Record.class);
                if (!r.deleted) out.add(r);   // soft-deleted records stay on disk for audit but are hidden
            } catch (Exception e) {
                LOG.warning("[MEETING] skipping unreadable record " + f.getName() + ": " + e.getMessage());
            }
        }
        out.sort(Comparator.comparingLong((MeetingModels.Record r) -> r.endedAt).reversed());
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private File ensureDir(String path) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            LOG.warning("[MEETING] could not create directory " + dir.getPath());
        }
        return dir;
    }

    private void writeJson(File f, Object value) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, value);
            Files.move(tmp.toPath(), f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ame) {
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception inner) {
                LOG.warning("[MEETING] write fallback failed for " + f.getPath() + ": " + inner.getMessage());
            }
        } catch (Exception e) {
            LOG.warning("[MEETING] write failed for " + f.getPath() + ": " + e.getMessage());
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
