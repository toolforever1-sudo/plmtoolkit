package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.ObaResolution;
import com.sandisk.plm.tracker.service.AgileItemFilesClient;
import com.sandisk.plm.tracker.service.ObaApiKeyGuard;
import com.sandisk.plm.tracker.service.ObaResolverService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Machine-facing OBA document retrieval for the Auto OBA Buyoff System.
 *
 *   GET /api/oba/required-docs?sku=<SKU>[&checklist=<docNum>]   (X-API-Key)
 *   GET /api/oba/file?item=<item>&name=<fileName>               (X-API-Key)
 *
 * See docs/superpowers/specs/2026-06-26-oba-required-docs-api-design.md.
 */
@RestController
@RequestMapping("/api/oba")
public class ObaController {

    private static final Logger LOG = Logger.getLogger(ObaController.class.getName());

    private final ObaResolverService resolver;
    private final AgileItemFilesClient filesClient;
    private final ObaApiKeyGuard guard;

    @Value("${app.oba.checklist-default:25-07-SM-03-00006}")
    private String checklistDefault;

    public ObaController(ObaResolverService resolver, AgileItemFilesClient filesClient,
                         ObaApiKeyGuard guard) {
        this.resolver = resolver;
        this.filesClient = filesClient;
        this.guard = guard;
    }

    @GetMapping("/required-docs")
    public ResponseEntity<?> requiredDocs(@RequestParam("sku") String sku,
                                          @RequestParam(value = "checklist", required = false) String checklist,
                                          @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ResponseEntity<?> deny = gate(apiKey);
        if (deny != null) return deny;

        String checklistNum = (checklist != null && !checklist.trim().isEmpty())
                ? checklist.trim() : checklistDefault;

        ObaResolution res = resolver.resolve(sku);
        List<String> warnings = new ArrayList<>(res.warnings);
        List<String> missingRoles = new ArrayList<>();

        List<Map<String, Object>> documents = new ArrayList<>();
        documents.add(roleDoc("label-proof", res.labelProofItem, res.labelProofDesc, warnings, missingRoles));
        documents.add(roleDoc("label-spec", res.labelSpecItem, res.labelSpecDesc, warnings, missingRoles));
        documents.add(roleDoc("oba-checklist", checklistNum, null, warnings, missingRoles));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku.trim());
        body.put("generatedAt", Instant.now().toString());
        body.put("checklist", checklistNum);
        // Top-level "is anything missing" summary so the Auto OBA system (and a human
        // checking QA, where prod attachments may not exist) can tell at a glance which
        // parts still need a file added in Agile.
        body.put("attachmentMissing", !missingRoles.isEmpty());
        body.put("missingRoles", missingRoles);
        body.put("documents", documents);
        body.put("warnings", warnings);
        return ResponseEntity.ok(body);
    }

    /**
     * Per-role status values:
     *   "ok"                   — item resolved and at least one attachment file present
     *   "attachment-missing"   — item resolved in Agile but it has zero attachment files
     *                            (add the file to the part in Agile)
     *   "agile-item-not-found" — the resolved item number does not exist in Agile
     *                            (e.g. QA doesn't have this part — create it + add the file)
     *   "item-not-resolved"    — the BOM did not yield an item number for this role
     *   "lookup-error"         — the attachment lookup failed (agile-service down/error)
     */
    private Map<String, Object> roleDoc(String role, String itemNumber, String itemDesc,
                                        List<String> warnings, List<String> missingRoles) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("role", role);
        doc.put("itemNumber", itemNumber);
        if (itemDesc != null) doc.put("itemDescription", itemDesc);

        if (itemNumber == null) {
            doc.put("found", false);
            doc.put("status", "item-not-resolved");
            doc.put("message", "Could not resolve the " + role + " item from the BOM");
            missingRoles.add(role);
            return doc;   // resolver already added a detail warning for the missing level
        }

        AgileItemFilesClient.FilesResult fr = filesClient.listFiles(itemNumber);
        if (!fr.found) {
            boolean notFound = fr.error != null && fr.error.toLowerCase().contains("not found");
            String status = notFound ? "agile-item-not-found" : "lookup-error";
            String msg = notFound
                    ? "Item " + itemNumber + " not found in Agile — create the part and add the attachment"
                    : "Could not read attachments for " + itemNumber
                      + (fr.error != null ? " (" + fr.error + ")" : "");
            doc.put("found", false);
            doc.put("status", status);
            doc.put("message", msg);
            warnings.add(msg);
            missingRoles.add(role);
            return doc;
        }

        if (fr.files.isEmpty()) {
            String msg = "Attachment missing — add the file to item " + itemNumber + " in Agile";
            doc.put("found", false);
            doc.put("status", "attachment-missing");
            doc.put("message", msg);
            warnings.add(msg);
            missingRoles.add(role);
            return doc;
        }

        List<Map<String, Object>> files = new ArrayList<>();
        int retrievable = 0;
        for (AgileItemFilesClient.FileMeta m : fr.files) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("fileName", m.fileName);
            if (m.fileDescription != null) f.put("fileDescription", m.fileDescription);
            f.put("fileType", m.fileType);
            f.put("byteSize", m.byteSize);
            f.put("isPdf", m.fileName != null && m.fileName.toLowerCase().endsWith(".pdf"));
            // contentAvailable: TRUE/FALSE when the agile-service reported it, null if the
            // service is older and didn't. A file counts as retrievable unless explicitly false.
            f.put("contentAvailable", m.contentAvailable);
            boolean fileRetrievable = !Boolean.FALSE.equals(m.contentAvailable);
            if (fileRetrievable) retrievable++;
            // A null fileName can't round-trip through ?name=, so omit the link rather
            // than emit a non-functional "...&name=" URL; keep the other metadata.
            if (m.fileName != null) {
                f.put("downloadUrl", "/api/oba/file?item=" + enc(itemNumber) + "&name=" + enc(m.fileName));
            }
            files.add(f);
        }

        doc.put("files", files);
        if (retrievable == 0) {
            // Rows exist but no file content is retrievable from this Agile environment
            // (typical in QA, where the attachment blob isn't in the vault).
            String msg = "Attachment content not available in this Agile environment — add the "
                    + "actual file(s) to item " + itemNumber;
            doc.put("found", false);
            doc.put("status", "content-unavailable");
            doc.put("message", msg);
            warnings.add(msg);
            missingRoles.add(role);
        } else {
            doc.put("found", true);
            doc.put("status", "ok");
        }
        return doc;
    }

    @GetMapping("/file")
    public ResponseEntity<byte[]> file(@RequestParam("item") String item,
                                       @RequestParam(value = "name", required = false) String name,
                                       @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ResponseEntity<?> deny = gate(apiKey);
        if (deny != null) return ResponseEntity.status(deny.getStatusCode()).build();

        AgileItemFilesClient.FileStream fs = filesClient.fetchFile(item, name);
        if (fs.httpStatus == 200 && fs.bytes != null) {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(parseContentType(fs.contentType));
            h.setContentDisposition(ContentDisposition.builder("attachment").filename(fs.filename).build());
            h.setContentLength(fs.bytes.length);
            return new ResponseEntity<>(fs.bytes, h, org.springframework.http.HttpStatus.OK);
        }
        if (fs.httpStatus == 422) {
            // Attachment row exists but its content isn't retrievable here (QA vault gap).
            LOG.info("[OBA] file proxy content-unavailable (422) for " + item + "/" + name
                    + (fs.error != null ? " (" + fs.error + ")" : ""));
            HttpHeaders h = new HttpHeaders();
            if (fs.error != null) h.add("X-OBA-Reason", fs.error.replaceAll("[\\r\\n]", " "));
            return new ResponseEntity<>(h, org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (fs.httpStatus == 404) {
            LOG.info("[OBA] file proxy upstream 404 for " + item + "/" + name
                    + (fs.error != null ? " (" + fs.error + ")" : ""));
            return ResponseEntity.status(404).build();
        }
        if (fs.httpStatus == 400) {
            LOG.info("[OBA] file proxy upstream 400 for " + item + "/" + name
                    + (fs.error != null ? " (" + fs.error + ")" : ""));
            return ResponseEntity.status(400).build();
        }
        LOG.info("[OBA] file proxy upstream status " + fs.httpStatus + " for " + item + "/" + name
                + (fs.error != null ? " (" + fs.error + ")" : ""));
        return ResponseEntity.status(502).build();
    }

    /** Returns a deny ResponseEntity (503/401) or null when access is allowed. */
    private ResponseEntity<?> gate(String apiKey) {
        switch (guard.check(apiKey)) {
            case NOT_CONFIGURED:
                return ResponseEntity.status(503).body(Collections.singletonMap("error", "OBA API not configured"));
            case UNAUTHORIZED:
                return ResponseEntity.status(401).body(Collections.singletonMap("error", "invalid or missing X-API-Key"));
            case OK:
            default:
                return null;
        }
    }

    private static String enc(String s) {
        // %20 (not '+') for spaces — these values land in a URL query the consumer GETs
        // back; '+' is ambiguous across proxy hops, %20 decodes to a space everywhere.
        try { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (Exception e) { return s; }
    }

    /** Parse an upstream Content-Type, falling back to octet-stream when it's null or
     *  malformed — a bad header must not turn a valid download into a 500. */
    private static MediaType parseContentType(String contentType) {
        if (contentType == null) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            LOG.info("[OBA] unparseable upstream Content-Type '" + contentType + "': " + e.getMessage());
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
