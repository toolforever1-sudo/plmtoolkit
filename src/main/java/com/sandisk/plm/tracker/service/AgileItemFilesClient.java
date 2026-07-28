package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Client over the plm-agile-service per-item file endpoints:
 *   GET /api/document/{item}/files          → metadata list
 *   GET /api/document/{item}/file?name=...   → one file's bytes
 * Same HttpURLConnection style as {@link AgileDocumentAttachmentsClient}.
 */
@Service
public class AgileItemFilesClient {

    private static final Logger LOG = Logger.getLogger(AgileItemFilesClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${agile.service.url:http://localhost:8081}")
    private String agileServiceUrl;

    @Value("${agile.service.timeout-ms:30000}")
    private int timeoutMs;

    public static final class FileMeta {
        public String fileName;
        public String fileDescription;
        public String fileType;
        public long byteSize;
        /** Tri-state: TRUE/FALSE if the agile-service reported content retrievability,
         *  null if the field was absent (older service). */
        public Boolean contentAvailable;
    }

    /** Result of a /files call. {@code found} false ⇒ item not found OR call failed
     *  ({@code error} set in the failure case). */
    public static final class FilesResult {
        public boolean found;
        public List<FileMeta> files = new ArrayList<>();
        public String error;
    }

    /** Bytes of a single file. {@code httpStatus} carries the upstream code. */
    public static final class FileStream {
        public byte[] bytes;
        public String filename;
        public String contentType;
        public int httpStatus;
        public String error;
    }

    public FilesResult listFiles(String itemNumber) {
        FilesResult r = new FilesResult();
        if (itemNumber == null || itemNumber.isEmpty()) { r.error = "blank item"; return r; }
        HttpURLConnection conn = null;
        try {
            String url = agileServiceUrl + "/api/document/" + enc(itemNumber) + "/files";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            if (code == 404) { r.error = "item not found"; return r; }
            if (code != 200) { r.error = "agile-service returned " + code; return r; }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return parseFilesJson(out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.info("[OBA-FILES] listFiles failed for " + itemNumber + ": " + r.error);
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** URL-encode a path/query value with %20 (not '+') for spaces, so it round-trips
     *  cleanly through the agile-service's query/path decoding. */
    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (Exception e) { return s == null ? "" : s; }
    }

    /** Pure parse of the /files JSON body. */
    public static FilesResult parseFilesJson(String json) {
        FilesResult r = new FilesResult();
        try {
            JsonNode root = MAPPER.readTree(json);
            r.found = root.path("found").asBoolean(false);
            JsonNode files = root.path("files");
            if (files.isArray()) {
                for (JsonNode f : files) {
                    FileMeta m = new FileMeta();
                    m.fileName = f.path("fileName").asText(null);
                    m.fileDescription = f.path("fileDescription").asText(null);
                    m.fileType = f.path("fileType").asText(null);
                    m.byteSize = f.path("byteSize").asLong(0L);
                    JsonNode ca = f.get("contentAvailable");
                    m.contentAvailable = (ca == null || ca.isNull()) ? null : ca.asBoolean();
                    r.files.add(m);
                }
            }
        } catch (Exception e) {
            r.error = "parse error: " + e.getMessage();
        }
        return r;
    }

    public FileStream fetchFile(String itemNumber, String fileName) {
        FileStream fs = new FileStream();
        HttpURLConnection conn = null;
        try {
            StringBuilder url = new StringBuilder(agileServiceUrl)
                    .append("/api/document/")
                    .append(enc(itemNumber))
                    .append("/file");
            if (fileName != null && !fileName.isEmpty()) {
                url.append("?name=").append(enc(fileName));
            }
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            fs.httpStatus = conn.getResponseCode();
            if (fs.httpStatus != 200) {
                String reason = conn.getHeaderField("X-OBA-Reason");
                fs.error = reason != null ? reason : ("agile-service returned " + fs.httpStatus);
                return fs;
            }
            fs.contentType = conn.getContentType();
            fs.filename = parseFilename(
                    conn.getHeaderField("Content-Disposition"), fileName, itemNumber);
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                fs.bytes = out.toByteArray();
            }
            return fs;
        } catch (Exception e) {
            fs.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.info("[OBA-FILES] fetchFile failed for " + itemNumber + "/" + fileName + ": " + fs.error);
            return fs;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String parseFilename(
            String contentDisposition, String fallbackName, String itemNumber) {
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
        if (fallbackName != null && !fallbackName.isEmpty()) return fallbackName;
        return itemNumber + "-file.bin";
    }
}
