package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Thin client over the {@code plm-agile-service} attachment-fetch endpoint
 * (mirrors the existing {@link SdsmFileService} pattern). Calls
 * {@code GET /api/document/{docNumber}/attachments?asZip=auto} and returns
 * the bytes plus filename / count headers. Designed to gracefully degrade
 * — if the endpoint is missing or the SDK call fails, returns an empty
 * bundle and the toolkit's email send path will swap in the
 * "Unable to attach" placeholder.
 */
@Service
public class AgileDocumentAttachmentsClient {

    private static final Logger LOG = Logger.getLogger(AgileDocumentAttachmentsClient.class.getName());

    @Value("${agile.service.url:http://localhost:8081}")
    private String agileServiceUrl;

    @Value("${agile.service.timeout-ms:30000}")
    private int timeoutMs;

    /** Local-test fallback file. When set AND the plm-agile-service call
     *  doesn't return bytes, the client returns this file instead so the
     *  outbound email has a real attachment for dev/demo. Leave empty in prod. */
    @Value("${app.ims-review.local-test-attachment-file:}")
    private String localTestAttachmentFile;

    public static final class Bundle {
        public byte[] bytes;       // null when nothing fetched
        public String filename;    // e.g. "33-05-SM-03-00011-attachments.zip"
        public int fileCount;
        public long totalBytes;
        public boolean ok;         // true → we have bytes to attach
        public String errorReason; // populated when ok=false (for diagnostics + audit)
    }

    public Bundle fetch(String docNumber) {
        Bundle b = doFetch(docNumber);
        // Local-test fallback — when the SDK call didn't produce bytes, fall
        // back to a configured local file so dev/demo emails carry a real
        // attachment. Disabled (empty config) in prod.
        if (!b.ok && localTestAttachmentFile != null && !localTestAttachmentFile.isEmpty()) {
            try {
                java.nio.file.Path p = java.nio.file.Paths.get(localTestAttachmentFile);
                if (java.nio.file.Files.exists(p)) {
                    b.bytes = java.nio.file.Files.readAllBytes(p);
                    String origName = p.getFileName().toString();
                    b.filename = docNumber + "-" + origName;
                    b.fileCount = 1;
                    b.totalBytes = b.bytes.length;
                    b.ok = true;
                    b.errorReason = "local test attachment (plm-agile-service unavailable: " + nvl(b.errorReason) + ")";
                    LOG.info("[AGILE-ATTACH] using local-test fallback for " + docNumber + " (" + b.totalBytes + " bytes)");
                }
            } catch (Exception e) {
                LOG.warning("[AGILE-ATTACH] local-test fallback failed: " + e.getMessage());
            }
        }
        return b;
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private Bundle doFetch(String docNumber) {
        Bundle b = new Bundle();
        if (docNumber == null || docNumber.isEmpty()) {
            b.errorReason = "blank docNumber";
            return b;
        }
        HttpURLConnection conn = null;
        try {
            String url = agileServiceUrl + "/api/document/"
                    + URLEncoder.encode(docNumber, StandardCharsets.UTF_8) + "/attachments?asZip=auto";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            if (code == 204) {
                // No attachments on this doc
                b.fileCount = 0;
                b.ok = false;
                b.errorReason = "no attachments";
                return b;
            }
            if (code != 200) {
                b.errorReason = "agile-service returned " + code;
                LOG.info("[AGILE-ATTACH] " + url + " → " + code);
                return b;
            }
            b.fileCount  = intHeader(conn, "X-Attachment-Count", 1);
            b.totalBytes = longHeader(conn, "X-Attachment-Total-Bytes", 0);
            b.filename   = parseFilename(conn.getHeaderField("Content-Disposition"), docNumber);

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                b.bytes = out.toByteArray();
            }
            if (b.totalBytes == 0) b.totalBytes = b.bytes.length;
            b.ok = b.bytes != null && b.bytes.length > 0;
            return b;
        } catch (Exception e) {
            b.errorReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.info("[AGILE-ATTACH] fetch failed for " + docNumber + ": " + b.errorReason);
            return b;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int intHeader(HttpURLConnection c, String h, int def) {
        try { String v = c.getHeaderField(h); return v == null ? def : Integer.parseInt(v); }
        catch (Exception e) { return def; }
    }

    private static long longHeader(HttpURLConnection c, String h, long def) {
        try { String v = c.getHeaderField(h); return v == null ? def : Long.parseLong(v); }
        catch (Exception e) { return def; }
    }

    private static String parseFilename(String contentDisposition, String docNumber) {
        if (contentDisposition != null) {
            int i = contentDisposition.indexOf("filename=");
            if (i >= 0) {
                String f = contentDisposition.substring(i + 9).trim();
                if (f.startsWith("\"")) f = f.substring(1);
                if (f.endsWith("\"")) f = f.substring(0, f.length() - 1);
                int s = f.indexOf(';');
                if (s > 0) f = f.substring(0, s).trim();
                if (!f.isEmpty()) return f;
            }
        }
        return docNumber + "-attachment.bin";
    }
}
