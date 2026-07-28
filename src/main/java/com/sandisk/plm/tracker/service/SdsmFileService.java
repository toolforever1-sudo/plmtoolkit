package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Resolve PDF bytes for an SDSM attachment using a two-tier strategy.
 *
 * <ol>
 *   <li><b>Local share</b> — try the staged copy under the configured share path
 *       first. The original SDSM batch wrote files there as
 *       {@code <sandiskpn>_<rev>_<originalFilename>.pdf} (Documents/Parts) or
 *       {@code <sandiskpn>_<originalFilename>.pdf} (Deviations), so we probe a
 *       small set of conventional names. Bytes-from-disk is faster and avoids
 *       loading the Agile SDK at all.</li>
 *   <li><b>plm-agile-service fallback</b> — if no local copy, ask the companion
 *       microservice to stream the bytes from the live Agile File Vault by
 *       attachment id ({@code GET /api/sdsm-file/{attachId}}). That service
 *       owns the SDK dependency; this toolkit doesn't need to.</li>
 * </ol>
 *
 * <p>Both tiers are best-effort: a missing file returns null, never throws.
 * The caller (controller) renders a 404 in that case.
 *
 * <p>Configuration:
 * <pre>
 *   sdsm.share.dir   = /Volumes/uls-ep-aglipccb/PLM_Engineering/sdsm_attachments/Files
 *   agile.service.url= http://localhost:8081
 * </pre>
 */
@Service
public class SdsmFileService {

    private static final Logger LOG = Logger.getLogger(SdsmFileService.class.getName());

    @Value("${sdsm.share.dir:./data/sdsm-files}")
    private String shareDir;

    @Value("${agile.service.url:http://localhost:8081}")
    private String agileServiceUrl;

    @Value("${sdsm.share.fallback.dirs:}")
    private String fallbackDirsCsv;

    public static class Result {
        public final String filename;
        public final String source;        // "local" or "agile-sdk"
        public final byte[] bytes;
        public final String contentType;
        public Result(String filename, String source, byte[] bytes, String contentType) {
            this.filename = filename;
            this.source = source;
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }

    /**
     * Best-effort fetch by parent number / rev / filename / attachId. Tries the
     * local share first, falls back to plm-agile-service. Returns null if both
     * paths fail — let the caller surface a 404.
     */
    public Result fetch(String parentNumber, String rev, String originalFilename, long attachId) {
        Result local = tryLocal(parentNumber, rev, originalFilename);
        if (local != null) return local;
        return tryAgileService(attachId, originalFilename);
    }

    // -----------------------------------------------------------------------
    //  Tier 1: local share (the staged copy from the existing nightly batch)
    // -----------------------------------------------------------------------

    private Result tryLocal(String parentNumber, String rev, String originalFilename) {
        if (originalFilename == null || originalFilename.isEmpty()) return null;

        List<Path> candidates = new ArrayList<>();
        // Naming patterns the original GetAndSummarizeAttachments uses:
        //   Documents/Parts: <pn>_<rev>_<originalFilename>
        //   Deviations:     <pn>_<originalFilename>
        if (rev != null && !rev.isEmpty()) {
            candidates.add(buildPath(shareDir, parentNumber + "_" + rev + "_" + originalFilename));
        }
        candidates.add(buildPath(shareDir, parentNumber + "_" + originalFilename));
        // Also accept the plain filename (some operators may have manually staged copies).
        candidates.add(buildPath(shareDir, originalFilename));

        // Optional secondary roots — useful when both the SMB share and a
        // local snapshot exist (e.g., the dev mount at ~/documents/sdsm/...).
        if (fallbackDirsCsv != null && !fallbackDirsCsv.trim().isEmpty()) {
            for (String d : fallbackDirsCsv.split(",")) {
                String dir = d.trim();
                if (dir.isEmpty()) continue;
                if (rev != null && !rev.isEmpty()) {
                    candidates.add(buildPath(dir, parentNumber + "_" + rev + "_" + originalFilename));
                }
                candidates.add(buildPath(dir, parentNumber + "_" + originalFilename));
                candidates.add(buildPath(dir, originalFilename));
            }
        }

        for (Path p : candidates) {
            if (p == null) continue;
            try {
                if (Files.isReadable(p)) {
                    byte[] bytes = Files.readAllBytes(p);
                    LOG.fine("[SDSM-FILE] Served from local: " + p);
                    return new Result(p.getFileName().toString(), "local", bytes, contentTypeOf(originalFilename));
                }
            } catch (IOException e) {
                LOG.warning("[SDSM-FILE] Local probe failed for " + p + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static Path buildPath(String dir, String filename) {
        if (dir == null || dir.isEmpty()) return null;
        try { return Paths.get(dir, filename); } catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    //  Tier 2: plm-agile-service (SDK fallback)
    // -----------------------------------------------------------------------

    /**
     * Calls {@code GET <agile.service.url>/api/sdsm-file/{attachId}} and returns
     * the raw bytes. This endpoint is expected to live in the {@code plm-agile-service}
     * microservice — see TODO at the bottom of this class for the matching server-side
     * shape.
     */
    private Result tryAgileService(long attachId, String originalFilename) {
        if (attachId <= 0) return null;
        String url = agileServiceUrl + "/api/sdsm-file/" + attachId;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(60_000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                LOG.warning("[SDSM-FILE] plm-agile-service returned " + code + " for " + url);
                return null;
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                String name = originalFilename != null ? originalFilename : ("attach-" + attachId + ".pdf");
                LOG.info("[SDSM-FILE] Served via plm-agile-service: attachId=" + attachId
                        + " (" + out.size() + " bytes)");
                return new Result(name, "agile-sdk", out.toByteArray(), contentTypeOf(name));
            }
        } catch (IOException e) {
            LOG.warning("[SDSM-FILE] plm-agile-service call failed (" + url + "): " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String contentTypeOf(String filename) {
        if (filename == null) return "application/octet-stream";
        String n = filename.toLowerCase();
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    /* -----------------------------------------------------------------------
     *  TODO (plm-agile-service):
     *      Add to ~/git/plm-agile-service a controller method:
     *
     *      @GetMapping("/api/sdsm-file/{attachId}")
     *      public ResponseEntity<byte[]> getFileBytes(@PathVariable long attachId) {
     *          IAttachmentFile f = lookupByAttachId(attachId);
     *          try (InputStream in = f.getFile()) { ... return bytes ... }
     *      }
     *
     *      The lookupByAttachId step needs the same chain
     *      ATTACHMENT_FULL_MAP → ATTACHMENT.LATEST_VSN → IFileFolder → IAttachmentFile
     *      that GetAndSummarizeAttachments.getZipFile(...) walks today.
     * ----------------------------------------------------------------------- */
}
