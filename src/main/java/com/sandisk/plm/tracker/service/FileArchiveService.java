package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores a copy of every file that crosses the toolkit's user boundary.
 *
 * <p>Uploads → bytes saved to disk + index entry. Bytes purged after
 * {@code retention-bytes-days} days; index row kept for {@code retention-index-days}
 * days. Downloads → metadata-only (filename, size, SHA-256). Index row purged
 * after {@code retention-index-days} days.
 *
 * <p>Index is a JSON-lines file ({@code archive-index.jsonl}) so partial
 * writes are recoverable (each line is independent). Append writes go through
 * a single {@code synchronized} block to keep the file consistent across
 * concurrent uploads.
 *
 * <p>Design intent: failed uploads ARE recorded. Capture happens BEFORE the
 * downstream handler runs, so even uploads that 500 in the agile-service /
 * Python sidecar / wherever are preserved. That's the whole point.
 */
@Service
public class FileArchiveService {

    private static final Logger logger = Logger.getLogger(FileArchiveService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._-]");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss-SSS");

    @Value("${app.file-archive.dir:./file-archive}")
    private String archiveDirSetting;

    @Value("${app.file-archive.retention-bytes-days:30}")
    private int retentionBytesDays;

    @Value("${app.file-archive.retention-index-days:90}")
    private int retentionIndexDays;

    private Path archiveRoot;
    private Path uploadsRoot;
    private Path indexFile;
    private final Object indexLock = new Object();

    @PostConstruct
    public void init() {
        archiveRoot = Paths.get(archiveDirSetting).toAbsolutePath().normalize();
        uploadsRoot = archiveRoot.resolve("uploads");
        indexFile = archiveRoot.resolve("archive-index.jsonl");
        try {
            Files.createDirectories(uploadsRoot);
            if (!Files.exists(indexFile)) Files.createFile(indexFile);
            logger.info("[FILE-ARCHIVE] root=" + archiveRoot + " retentionBytes=" + retentionBytesDays
                + "d retentionIndex=" + retentionIndexDays + "d");
        } catch (IOException e) {
            logger.warning("[FILE-ARCHIVE] init failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    //  Recording
    // -------------------------------------------------------------------

    /**
     * Persist the upload bytes to disk and append an index entry. Returns the
     * assigned id. Best-effort — any I/O failure here is logged but does NOT
     * propagate to the caller; archiving a file must never break the user's
     * actual workflow.
     */
    public String recordUpload(MultipartFile file, String user, String displayName,
                               String route, String feature, String ptId) {
        if (file == null || file.isEmpty()) return null;
        String id = newId();
        try {
            String originalName = file.getOriginalFilename();
            if (originalName == null) originalName = "unnamed";
            String safeName = SAFE_NAME.matcher(originalName).replaceAll("_");
            if (safeName.length() > 80) safeName = safeName.substring(safeName.length() - 80);

            Instant now = Instant.now();
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            String userBucket = sanitiseUser(user);
            Path dayDir = uploadsRoot.resolve(today.format(DAY_FMT)).resolve(userBucket);
            Files.createDirectories(dayDir);
            String filenameOnDisk = now.atZone(ZoneOffset.UTC).format(TIME_FMT) + "__" + id + "__" + safeName;
            Path dest = dayDir.resolve(filenameOnDisk);

            // Stream-copy + hash in one pass.
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = file.getInputStream();
                 OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("ts", now.toString());
            entry.put("direction", "upload");
            entry.put("user", nz(user));
            entry.put("displayName", nz(displayName));
            entry.put("route", nz(route));
            entry.put("feature", nz(feature));
            entry.put("filename", originalName);
            entry.put("bytes", Files.size(dest));
            entry.put("sha256", toHex(sha.digest()));
            entry.put("ptId", ptId);
            entry.put("archivedPath", archiveRoot.relativize(dest).toString().replace('\\', '/'));
            entry.put("purged", false);
            entry.put("permanent", false);
            appendIndex(entry);
            return id;
        } catch (Exception e) {
            logger.warning("[FILE-ARCHIVE] recordUpload failed for " + nz(feature) + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Pre-computed variant — for the streaming download filter, which hashes
     * bytes as they flow through to the client and gives us the digest plus
     * a count without ever materialising the file in memory.
     */
    public String recordDownload(String filename, long byteCount, String sha256Hex,
                                 String user, String displayName,
                                 String route, String feature) {
        String id = newId();
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("ts", Instant.now().toString());
            entry.put("direction", "download");
            entry.put("user", nz(user));
            entry.put("displayName", nz(displayName));
            entry.put("route", nz(route));
            entry.put("feature", nz(feature));
            entry.put("filename", nz(filename));
            entry.put("bytes", byteCount);
            entry.put("sha256", nz(sha256Hex));
            entry.put("ptId", null);
            entry.put("archivedPath", null);
            entry.put("purged", false);
            entry.put("permanent", false);
            appendIndex(entry);
            return id;
        } catch (Exception e) {
            logger.warning("[FILE-ARCHIVE] recordDownload (streamed) failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Compute SHA-256 of the bytes and append a metadata-only index entry.
     * No bytes are persisted — downloads are large and easy to regenerate;
     * what we care about is "can I prove which exact bytes the user saw?"
     */
    public String recordDownload(String filename, byte[] bytes, String user, String displayName,
                                 String route, String feature) {
        if (bytes == null) return null;
        String id = newId();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(bytes);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("ts", Instant.now().toString());
            entry.put("direction", "download");
            entry.put("user", nz(user));
            entry.put("displayName", nz(displayName));
            entry.put("route", nz(route));
            entry.put("feature", nz(feature));
            entry.put("filename", nz(filename));
            entry.put("bytes", bytes.length);
            entry.put("sha256", toHex(sha.digest()));
            entry.put("ptId", null);
            entry.put("archivedPath", null);
            entry.put("purged", false);
            entry.put("permanent", false);
            appendIndex(entry);
            return id;
        } catch (NoSuchAlgorithmException e) {
            logger.warning("[FILE-ARCHIVE] recordDownload failed (no SHA-256): " + e.getMessage());
            return null;
        } catch (Exception e) {
            logger.warning("[FILE-ARCHIVE] recordDownload failed: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------
    //  Admin operations
    // -------------------------------------------------------------------

    public static class ListResult {
        public List<Map<String, Object>> entries = new ArrayList<>();
        public int totalScanned;
    }

    /** List index entries (most recent first), filtered. Best-effort cap of 500. */
    public ListResult list(String userFilter, String featureFilter, String directionFilter,
                           String filenameFilter, Integer maxResults) {
        ListResult r = new ListResult();
        int cap = (maxResults == null || maxResults <= 0) ? 500 : Math.min(maxResults, 2000);
        String uf = lower(userFilter);
        String ff = lower(featureFilter);
        String df = lower(directionFilter);
        String nf = lower(filenameFilter);

        List<Map<String, Object>> all = readAllEntries();
        r.totalScanned = all.size();
        // Iterate newest first.
        for (int i = all.size() - 1; i >= 0; i--) {
            Map<String, Object> e = all.get(i);
            if (uf != null && !lower(asStr(e.get("user"))).contains(uf)
                && !lower(asStr(e.get("displayName"))).contains(uf)) continue;
            if (ff != null && !lower(asStr(e.get("feature"))).contains(ff)) continue;
            if (df != null && !lower(asStr(e.get("direction"))).equals(df)) continue;
            if (nf != null && !lower(asStr(e.get("filename"))).contains(nf)) continue;
            r.entries.add(e);
            if (r.entries.size() >= cap) break;
        }
        return r;
    }

    /** Open the archived upload by id. Returns null if not found, throws if bytes are purged or download. */
    public Path resolveArchivedPath(String id) {
        Map<String, Object> e = findById(id);
        if (e == null) return null;
        if (!"upload".equals(e.get("direction"))) {
            throw new IllegalArgumentException("Entry " + id + " is a download — bytes are not archived.");
        }
        if (Boolean.TRUE.equals(e.get("purged"))) {
            throw new IllegalStateException("Entry " + id + " has been purged.");
        }
        String rel = asStr(e.get("archivedPath"));
        if (rel == null || rel.isEmpty()) return null;
        Path p = archiveRoot.resolve(rel).normalize();
        // Safety: resolved path must still live under archiveRoot.
        if (!p.startsWith(archiveRoot)) {
            throw new SecurityException("Archived path escapes archive root: " + rel);
        }
        return p;
    }

    public Map<String, Object> findById(String id) {
        if (id == null) return null;
        for (Map<String, Object> e : readAllEntries()) {
            if (id.equals(e.get("id"))) return e;
        }
        return null;
    }

    /** Mark an entry as permanent (skip purge) — admin action. */
    public boolean markPermanent(String id, boolean flag) {
        synchronized (indexLock) {
            List<Map<String, Object>> all = readAllEntries();
            boolean changed = false;
            for (Map<String, Object> e : all) {
                if (id.equals(e.get("id"))) {
                    e.put("permanent", flag);
                    changed = true;
                    break;
                }
            }
            if (changed) rewriteIndex(all);
            return changed;
        }
    }

    // -------------------------------------------------------------------
    //  Scheduled purge — runs daily at 03:00 server-local
    // -------------------------------------------------------------------

    public static class PurgeResult {
        public int bytesPurged;
        public int rowsDropped;
        public int totalAfter;
    }

    /** Daily purge tick. Cron expression: 0 0 3 * * * (server timezone). */
    @Scheduled(cron = "${app.file-archive.purge-cron:0 0 3 * * *}")
    public PurgeResult runPurge() {
        synchronized (indexLock) {
            try {
                Instant byteCutoff = Instant.now().minusSeconds(retentionBytesDays * 86400L);
                Instant indexCutoff = Instant.now().minusSeconds(retentionIndexDays * 86400L);
                List<Map<String, Object>> all = readAllEntries();
                List<Map<String, Object>> kept = new ArrayList<>(all.size());
                PurgeResult r = new PurgeResult();
                for (Map<String, Object> e : all) {
                    Instant ts;
                    try { ts = Instant.parse(asStr(e.get("ts"))); }
                    catch (Exception ignored) { ts = Instant.now(); }

                    if (ts.isBefore(indexCutoff) && !Boolean.TRUE.equals(e.get("permanent"))) {
                        // Drop the row entirely. Also delete any leftover bytes.
                        tryDeleteArchivedBytes(e);
                        r.rowsDropped++;
                        continue;
                    }
                    if ("upload".equals(e.get("direction"))
                        && !Boolean.TRUE.equals(e.get("purged"))
                        && !Boolean.TRUE.equals(e.get("permanent"))
                        && ts.isBefore(byteCutoff)) {
                        tryDeleteArchivedBytes(e);
                        e.put("purged", true);
                        e.put("archivedPath", null);
                        r.bytesPurged++;
                    }
                    kept.add(e);
                }
                rewriteIndex(kept);
                r.totalAfter = kept.size();
                logger.info("[FILE-ARCHIVE] purge: bytesPurged=" + r.bytesPurged
                    + " rowsDropped=" + r.rowsDropped + " kept=" + r.totalAfter);
                return r;
            } catch (Exception e) {
                logger.warning("[FILE-ARCHIVE] purge failed: " + e.getMessage());
                return new PurgeResult();
            }
        }
    }

    private void tryDeleteArchivedBytes(Map<String, Object> e) {
        String rel = asStr(e.get("archivedPath"));
        if (rel == null || rel.isEmpty()) return;
        try {
            Path p = archiveRoot.resolve(rel).normalize();
            if (p.startsWith(archiveRoot)) Files.deleteIfExists(p);
        } catch (Exception ex) {
            // Non-fatal; the row is still updated.
        }
    }

    // -------------------------------------------------------------------
    //  Index I/O
    // -------------------------------------------------------------------

    private void appendIndex(Map<String, Object> entry) {
        synchronized (indexLock) {
            try {
                String line = MAPPER.writeValueAsString(entry) + "\n";
                Files.write(indexFile, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ioe) {
                logger.warning("[FILE-ARCHIVE] index append failed: " + ioe.getMessage());
            }
        }
    }

    private void rewriteIndex(List<Map<String, Object>> entries) {
        Path tmp = archiveRoot.resolve("archive-index.jsonl.new");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Map<String, Object> e : entries) {
                w.write(MAPPER.writeValueAsString(e));
                w.write("\n");
            }
        } catch (IOException ioe) {
            logger.warning("[FILE-ARCHIVE] index rewrite (tmp write) failed: " + ioe.getMessage());
            return;
        }
        try {
            Files.move(tmp, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ioe) {
            logger.warning("[FILE-ARCHIVE] index rewrite (rename) failed: " + ioe.getMessage());
        }
    }

    private List<Map<String, Object>> readAllEntries() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(indexFile)) return out;
        try (Stream<String> s = Files.lines(indexFile, StandardCharsets.UTF_8)) {
            Iterator<String> it = s.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line == null || line.trim().isEmpty()) continue;
                try {
                    out.add(MAPPER.readValue(line, new TypeReference<Map<String, Object>>(){}));
                } catch (Exception parseErr) {
                    // Skip corrupt line; keep going.
                }
            }
        } catch (IOException e) {
            logger.warning("[FILE-ARCHIVE] index read failed: " + e.getMessage());
        }
        return out;
    }

    // -------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------

    private static String newId() {
        return Long.toHexString(System.nanoTime()).substring(0, 8) + Long.toHexString((long) (Math.random() * 0xFFFF));
    }

    private static String sanitiseUser(String user) {
        if (user == null || user.isEmpty()) return "unknown";
        return SAFE_NAME.matcher(user).replaceAll("_");
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String lower(String s) {
        return (s == null || s.isEmpty()) ? null : s.toLowerCase();
    }

    private static String asStr(Object o) { return o == null ? "" : String.valueOf(o); }
}
