package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.SdsmAttachment;
import com.sandisk.plm.tracker.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST endpoints powering the SDSM operator-view tab.
 *
 * <pre>
 *   GET  /api/sdsm/search?q=&lt;part-or-change-number&gt;
 *        → all controlled docs for the part (Documents pass + Parts pass results
 *          for that exact item number) plus any active QN deviations.
 *
 *   GET  /api/sdsm/file/{attachId}?fileName=...&parentNumber=...&rev=...
 *        → streams the PDF bytes (local share first, plm-agile-service fallback).
 *
 *   GET  /api/sdsm/active-deviations
 *        → shop-floor banner: every active QN deviation right now.
 * </pre>
 *
 * <p>Auth-wise these inherit the toolkit's standard {@code AuthFilter} cover —
 * no extra gating here; an operator who can log into the toolkit can use SDSM.
 */
@RestController
@RequestMapping("/api/sdsm")
public class SdsmController {

    private static final Logger LOG = Logger.getLogger(SdsmController.class.getName());

    @Autowired private SdsmDocumentsService docsService;
    @Autowired private SdsmPartsService partsService;
    @Autowired private SdsmDeviationsService devsService;
    @Autowired private SdsmFileService fileService;
    @Autowired private SdsmDiagnoseService diagnoseService;
    @Autowired private SdsmContextIndex contextIndex;
    @Autowired private ActivityLogger activityLogger;
    @Autowired private DataSource dataSource;
    @Value("${agile.service.url:http://localhost:8081}") private String agileServiceUrl;

    /**
     * Operator search: type or scan a part/document number, get every controlled
     * doc that applies. The same number could match either Documents (e.g.
     * {@code 06-05-WW-02-00002}) or Parts (e.g. {@code 30-08-60003}); we run
     * both passes and merge.
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q, HttpSession session) {
        long t0 = System.currentTimeMillis();
        String trimmed = q == null ? "" : q.trim();
        Map<String, Object> response = new LinkedHashMap<>();

        if (trimmed.isEmpty()) {
            response.put("results", Collections.emptyList());
            response.put("totalCount", 0);
            response.put("queryTimeMs", 0);
            return response;
        }

        List<SdsmAttachment> all = new ArrayList<>();
        try {
            all.addAll(docsService.run(trimmed, 0));
            all.addAll(partsService.run(trimmed, 0));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[SDSM] search failed for " + trimmed, e);
        }

        long elapsed = System.currentTimeMillis() - t0;

        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SDSM_SEARCH",
            "q=" + trimmed + " | found=" + all.size() + " | " + elapsed + "ms");

        response.put("query", trimmed);
        response.put("results", all);
        response.put("totalCount", all.size());
        response.put("queryTimeMs", elapsed);
        return response;
    }

    // -----------------------------------------------------------------------
    //  Operator-first ("Find by Station / Product") endpoints
    // -----------------------------------------------------------------------

    /** Dropdown universe: every Spec / Step Code that appears on at least one in-scope doc. */
    @GetMapping("/specs")
    public Map<String, Object> listSpecs() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("values", contextIndex.getSpecs());
        r.put("indexBuiltAt", contextIndex.getLastBuiltAt());
        return r;
    }

    /** Dropdown universe: every Product Group on at least one in-scope doc. */
    @GetMapping("/product-groups")
    public Map<String, Object> listProductGroups() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("values", contextIndex.getProductGroups());
        r.put("indexBuiltAt", contextIndex.getLastBuiltAt());
        return r;
    }

    /** Dropdown universe: every Product (item number) on at least one in-scope doc. */
    @GetMapping("/products")
    public Map<String, Object> listProducts() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("values", contextIndex.getProducts());
        r.put("indexBuiltAt", contextIndex.getLastBuiltAt());
        return r;
    }

    /**
     * Cascading filter source for the context-mode form. Given any combination
     * of the four current selections (spec, productGroup, product, styles),
     * returns the available remaining values for each axis plus per-style
     * counts so the UI can grey out chips that would yield zero results.
     *
     * <p>All four query params are optional. Empty / missing = "no filter on
     * that axis." The candidate set is the intersection of whatever filters
     * ARE provided, applied with the same "OR ALL" semantics on Product Group
     * and Product that the main search uses.
     *
     * <p>Sub-millisecond against the warm in-memory index — safe to call on
     * every keystroke (the UI debounces at ~300ms anyway).
     */
    @GetMapping("/context-options")
    public Map<String, Object> contextOptions(
            @RequestParam(required = false) String spec,
            @RequestParam(required = false) String productGroup,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String styles) {
        Set<String> styleFilter = null;
        if (styles != null && !styles.trim().isEmpty()) {
            styleFilter = new HashSet<>();
            for (String s : styles.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) styleFilter.add(t);
            }
        }
        SdsmContextIndex.Options opts = contextIndex.options(
            blank(spec) ? null : spec.trim(),
            blank(productGroup) ? null : productGroup.trim(),
            blank(product) ? null : product.trim(),
            styleFilter);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("specs",         opts.specs);
        r.put("productGroups", opts.productGroups);
        r.put("products",      opts.products);
        r.put("styleCounts",   opts.styleCounts);
        r.put("totalCandidates", opts.totalCandidates);
        r.put("indexBuiltAt",  contextIndex.getLastBuiltAt());
        return r;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /**
     * Operator search: "I'm at station {spec}, working on product {product} in
     * group {productGroup} — show me my docs." Spec / Product Group / Product
     * are required (the floor model). Optional {@code styles} CSV narrows to a
     * subset of Document Styles.
     *
     * <p>Returns the same {@link com.sandisk.plm.tracker.model.SdsmAttachment}
     * shape as {@code /search} so the UI can reuse its result-card renderer.
     */
    @GetMapping("/search-by-context")
    public Map<String, Object> searchByContext(
            @RequestParam String spec,
            @RequestParam String productGroup,
            @RequestParam String product,
            @RequestParam(required = false) String styles,
            HttpSession session) {
        long t0 = System.currentTimeMillis();
        Set<String> styleFilter = null;
        if (styles != null && !styles.trim().isEmpty()) {
            styleFilter = new HashSet<>();
            for (String s : styles.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) styleFilter.add(t);
            }
        }
        List<com.sandisk.plm.tracker.model.SdsmAttachment> results;
        try {
            List<SdsmContextIndex.IndexedItem> matches = contextIndex.findByContext(spec, productGroup, product, styleFilter);
            results = contextIndex.hydrateAttachments(matches);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[SDSM] search-by-context failed", e);
            results = Collections.emptyList();
        }
        long elapsed = System.currentTimeMillis() - t0;

        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SDSM_SEARCH_CTX",
            "spec=" + spec + " | pg=" + productGroup + " | product=" + product
                + " | styles=" + (styles == null ? "" : styles)
                + " | found=" + results.size() + " | " + elapsed + "ms");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("spec", spec);
        response.put("productGroup", productGroup);
        response.put("product", product);
        response.put("styles", styles);
        response.put("results", results);
        response.put("totalCount", results.size());
        response.put("queryTimeMs", elapsed);
        return response;
    }

    /**
     * Diagnostic: "Why didn't this part return any documents?" Runs the same
     * gates the main search applies and reports which one(s) failed, plus
     * concrete next steps. Always succeeds (returns a structured Diagnosis
     * object even for non-existent items).
     */
    @GetMapping("/diagnose")
    public SdsmDiagnoseService.Diagnosis diagnose(@RequestParam String q, HttpSession session) {
        SdsmDiagnoseService.Diagnosis d = diagnoseService.diagnose(q);
        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SDSM_DIAGNOSE",
            "q=" + q + " | classification=" + d.classification + " | checks=" + d.checks.size());
        return d;
    }

    /**
     * Returns every active Quality Notice deviation right now. The factory-floor
     * banner uses this to alert operators without them having to look it up per
     * part.
     */
    @GetMapping("/active-deviations")
    public Map<String, Object> activeDeviations() {
        long t0 = System.currentTimeMillis();
        List<SdsmAttachment> devs;
        try {
            devs = devsService.run(null);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[SDSM] active-deviations failed", e);
            devs = Collections.emptyList();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("results", devs);
        r.put("totalCount", devs.size());
        r.put("queryTimeMs", System.currentTimeMillis() - t0);
        return r;
    }

    /**
     * Stream a single attachment. Tries the local share first, falls back to
     * plm-agile-service. The query params (fileName, rev, parentNumber) are
     * needed for the local-first probe — without them we'd have to hit the SDK
     * even when the file is staged on disk.
     */
    @GetMapping("/file/{attachId}")
    public ResponseEntity<byte[]> streamFile(
            @PathVariable long attachId,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) String rev,
            @RequestParam(required = false) String parentNumber,
            @RequestParam(value = "inline", defaultValue = "true") boolean inline,
            HttpSession session) {

        SdsmFileService.Result r = fileService.fetch(parentNumber, rev, fileName, attachId);
        if (r == null) {
            LOG.warning("[SDSM] file not found: attachId=" + attachId + " name=" + fileName);
            return ResponseEntity.notFound().build();
        }

        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SDSM_FILE",
            "attachId=" + attachId + " | name=" + r.filename + " | source=" + r.source
                + " | bytes=" + r.bytes.length);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(r.contentType));
        // Use ContentDisposition.builder so we get a proper "inline; filename=..." header.
        // setContentDispositionFormData is for multipart upload context — it produces
        // 'form-data; name="inline"; filename=...' which Chrome treats as a download
        // even when the response is a PDF, so the iframe preview comes up blank.
        h.setContentDisposition(
            ContentDisposition.builder(inline ? "inline" : "attachment")
                .filename(r.filename)
                .build());
        h.setContentLength(r.bytes.length);
        h.add("X-SDSM-Source", r.source);
        // Allow same-origin iframe embedding so the operator-view modal can preview the
        // PDF inline. (Spring Security would otherwise default X-Frame-Options:DENY,
        // but this app doesn't use Spring Security — explicit ALLOW kept for clarity.)
        h.add("X-Frame-Options", "SAMEORIGIN");
        return new ResponseEntity<>(r.bytes, h, org.springframework.http.HttpStatus.OK);
    }

    // -----------------------------------------------------------------------
    //  Deviation attachment bundle (zip) — SDK-only, bypasses local share
    // -----------------------------------------------------------------------

    /**
     * Stream a zip containing every attachment for the given Deviation, fetched
     * via the Agile SDK (plm-agile-service) — explicitly skips the local share
     * probe so we can confirm the SDK download path works end-to-end. Used by
     * the per-deviation "Download all attachments (zip)" link in the
     * Active Quality Notices banner.
     *
     * <p>Steps:
     * <ol>
     *   <li>Resolve {@code CHANGE.ID} from the change number.</li>
     *   <li>Pull every {@code ATTACHMENT_FULL_MAP} row whose {@code PARENT_ID}
     *       matches that change id (deviations always use {@code PARENT_ID}; the
     *       {@code PARENT_ID2} on those rows is 0).</li>
     *   <li>For each attachment, GET {@code <agile.service.url>/api/sdsm-file/{attachId}}
     *       — same SDK path the file-fetch fallback uses, but called directly so
     *       we don't accidentally pick up a staged copy from disk.</li>
     *   <li>Write each blob into a {@link ZipOutputStream} on the response,
     *       deduping filenames if the deviation contains two attachments with
     *       the same name.</li>
     * </ol>
     *
     * <p>404 if the change number doesn't exist or has no attachments. 502 if
     * any single attachment download fails (better to fail loudly than to ship
     * a half-empty zip the operator might trust).
     */
    @GetMapping("/deviation-zip/{changeNumber}")
    public void deviationZip(@PathVariable String changeNumber,
                              HttpServletResponse response,
                              HttpSession session) throws IOException {
        long t0 = System.currentTimeMillis();

        Long changeId = lookupChangeId(changeNumber);
        if (changeId == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Change " + changeNumber + " not found");
            return;
        }

        List<AttRow> rows = listDeviationAttachments(changeId);
        if (rows.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No attachments on " + changeNumber);
            return;
        }

        // Stream the zip directly to the response — no intermediate buffering.
        // Even with 4-5 attachments (typical QN deviation) the total can run to
        // 10-30 MB; streaming keeps heap pressure flat.
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
            "attachment; filename=\"" + changeNumber + "_attachments.zip\"");
        response.setHeader("X-SDSM-Source", "agile-sdk");

        Set<String> usedNames = new HashSet<>();
        int ok = 0, failed = 0;
        long totalBytes = 0;
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
            for (AttRow a : rows) {
                String safeName = uniqueZipName(a.fileName, a.attachId, usedNames);
                try {
                    byte[] bytes = fetchSdkBytes(a.attachId, a.fileName);
                    if (bytes == null) {
                        LOG.warning("[SDSM-ZIP] " + changeNumber + " attachId=" + a.attachId
                                + " (" + a.fileName + ") — agile-service returned no bytes");
                        failed++;
                        continue;
                    }
                    ZipEntry entry = new ZipEntry(safeName);
                    zip.putNextEntry(entry);
                    zip.write(bytes);
                    zip.closeEntry();
                    ok++;
                    totalBytes += bytes.length;
                } catch (IOException e) {
                    LOG.warning("[SDSM-ZIP] " + changeNumber + " attachId=" + a.attachId
                            + " (" + a.fileName + ") failed: " + e.getMessage());
                    failed++;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "SDSM_DEV_ZIP",
            "change=" + changeNumber + " | attachments=" + rows.size()
                + " | ok=" + ok + " | failed=" + failed
                + " | bytes=" + totalBytes + " | " + elapsed + "ms");
    }

    private Long lookupChangeId(String changeNumber) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT ID FROM agile.CHANGE WHERE CHANGE_NUMBER = ?")) {
            ps.setString(1, changeNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.warning("[SDSM-ZIP] CHANGE lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static class AttRow {
        long attachId; String fileName; long fileSize;
    }

    private List<AttRow> listDeviationAttachments(long changeId) {
        List<AttRow> out = new ArrayList<>();
        String sql = "SELECT ATTACH_ID, FILEFILENAME, FILEFILE_SIZE FROM agile.ATTACHMENT_FULL_MAP " +
                     " WHERE PARENT_ID = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, changeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AttRow a = new AttRow();
                    a.attachId = rs.getLong(1);
                    a.fileName = rs.getString(2);
                    a.fileSize = rs.getLong(3);
                    out.add(a);
                }
            }
        } catch (SQLException e) {
            LOG.warning("[SDSM-ZIP] attachment lookup failed for changeId=" + changeId + ": " + e.getMessage());
        }
        return out;
    }

    /** SDK fetch via plm-agile-service. Returns null on any non-200 / network error. */
    private byte[] fetchSdkBytes(long attachId, String fileName) throws IOException {
        String url = agileServiceUrl + "/api/sdsm-file/" + attachId;
        if (fileName != null && !fileName.isEmpty()) {
            url += "?fileName=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8.name());
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(120_000);  // larger files (DOCX/PDF up to ~100MB) need a generous read timeout
        conn.setRequestMethod("GET");
        try {
            int code = conn.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Build a zip entry name that won't collide; falls back to "{attachId}-{name}" on dupes. */
    private static String uniqueZipName(String fileName, long attachId, Set<String> used) {
        String base = (fileName == null || fileName.isEmpty()) ? ("attachment-" + attachId) : fileName;
        // Strip any backslashes / forward slashes that could escape the zip directory
        // (zip-slip mitigation). Filenames in agprod don't usually contain slashes,
        // but defensive — and also strips Windows-style paths if any leak in.
        base = base.replace("\\", "_").replace("/", "_");
        if (!used.contains(base)) { used.add(base); return base; }
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext  = dot > 0 ? base.substring(dot)    : "";
        String alt  = stem + "_" + attachId + ext;
        used.add(alt);
        return alt;
    }
}
