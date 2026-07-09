package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Stores per-user Meeting Mode drafts (pending IT Enhancements edits + captured
 * notes) on disk so the user can recover them on next login. One draft per
 * user, keyed by Agile loginid. After a successful per-user write-back the
 * controller deletes the draft so the next session starts fresh from the
 * latest Agile state.
 *
 * <p>File layout: {@code ./drafts/<loginid>-active.json}. Atomic write via
 * temp-file + rename so a crash mid-write doesn't leave half-written JSON.
 * Inside, {@code fileName} carries the user-friendly auto-name
 * (e.g. {@code VikasJindalEditedOn2026-06-15.json}) used in the recovery
 * prompt — the canonical disk path stays loginid-keyed for stability.
 *
 * <p>The shape of {@code dirty} and {@code meeting} is opaque to the
 * server — the JSON is round-tripped verbatim between client save and client
 * recover. We do <em>not</em> validate or interpret it server-side; that
 * keeps this layer cheap and future-proof against client-side schema bumps.
 */
@Service
public class DraftStorageService {

    private static final Logger LOG = Logger.getLogger(DraftStorageService.class.getName());
    private static final String DRAFT_DIR = "./drafts";
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private File draftFile(String loginId) {
        String safe = sanitize(loginId);
        return new File(DRAFT_DIR, safe + "-active.json");
    }

    /** Read the draft for a user. Returns {@code null} if no draft exists or
     *  the file can't be parsed (corrupted draft = treat as no draft; the
     *  user can always start a new one). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(String loginId) {
        if (loginId == null || loginId.isEmpty()) return null;
        File f = draftFile(loginId);
        if (!f.exists()) return null;
        try {
            return mapper.readValue(f, LinkedHashMap.class);
        } catch (Exception e) {
            LOG.warning("[DRAFT] failed to read " + f.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    /** Persist the draft for a user. {@code body} is taken as-is from the
     *  client and re-serialized; we just stamp the canonical loginId /
     *  fileName / savedAt fields before writing so the recovery prompt can
     *  show stable metadata even if the client forgot to send them. */
    public void write(String loginId, String displayName, Map<String, Object> body) {
        if (loginId == null || loginId.isEmpty()) return;
        File dir = new File(DRAFT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            LOG.warning("[DRAFT] could not create draft directory " + dir.getPath());
            return;
        }
        Map<String, Object> out = body == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(body);
        out.put("loginId", loginId);
        if (displayName != null) out.put("displayName", displayName);
        long now = System.currentTimeMillis();
        out.put("savedAtMs", now);
        out.put("savedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new java.util.Date(now)));
        // Suggest a friendly filename ("VikasJindalEditedOn2026-06-15.json")
        // unless the client supplied one. Strip non-alphanumerics from the
        // display name so the suggestion is filesystem-safe; if no display
        // name, fall back to the loginid.
        if (!out.containsKey("fileName") || out.get("fileName") == null
                || String.valueOf(out.get("fileName")).isEmpty()) {
            String tag = displayName != null && !displayName.isEmpty()
                    ? displayName.replaceAll("[^A-Za-z0-9]", "") : loginId;
            String ymd = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(now));
            out.put("fileName", tag + "EditedOn" + ymd + ".json");
        }
        File f = draftFile(loginId);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, out);
            Files.move(tmp.toPath(), f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ame) {
            // Some filesystems (overlay mounts on Windows shares) don't support
            // atomic move; fall back to non-atomic. Crash window is tiny.
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception inner) {
                LOG.warning("[DRAFT] write fallback failed for " + f.getPath() + ": " + inner.getMessage());
            }
        } catch (Exception e) {
            LOG.warning("[DRAFT] write failed for " + f.getPath() + ": " + e.getMessage());
        }
    }

    /** Remove the user's draft (called after a successful Agile save batch).
     *  Idempotent — returns silently if no draft exists. */
    public void delete(String loginId) {
        if (loginId == null || loginId.isEmpty()) return;
        File f = draftFile(loginId);
        if (f.exists() && !f.delete()) {
            LOG.warning("[DRAFT] failed to delete " + f.getPath());
        }
    }

    /** Defensive sanitization of the loginId for filesystem use — strips
     *  path separators and shell metacharacters so a malicious loginid can't
     *  escape the drafts directory. The legitimate Agile loginids are
     *  4-7 digit numbers (e.g. "8252"), so any non-alphanumeric drops out. */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
