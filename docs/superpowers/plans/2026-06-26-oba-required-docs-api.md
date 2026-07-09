# OBA Required-Doc Retrieval API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a machine-facing toolkit API that, given a SKU, resolves the OBA shipping-label and checklist documents from the Agile BOM (custom-schema DB) and hands the Auto OBA system per-file download links, gated by a static API key.

**Architecture:** A new `ObaController` in `plm-field-tracker` resolves SKU → item numbers via `ObaResolverService` (BFS over `BomDataService.explode` output) and fetches per-item attachment metadata + bytes through `AgileItemFilesClient`, which calls two new endpoints on `plm-agile-service` (`/api/document/{item}/files` and `/api/document/{item}/file?name=`). The SDK side reuses the existing `DocumentAttachmentsService` traversal. SKU→item resolution is pure DB (testable locally); attachment fetch needs the live SDK (testable on QA only).

**Tech Stack:** Java 11 (toolkit) / Java 8 (agile-service), Spring Boot, JUnit 5 + Mockito + MockMvc (toolkit tests), Agile SDK 9.3.6, Jackson, Oracle custom schema.

**Design spec:** `docs/superpowers/specs/2026-06-26-oba-required-docs-api-design.md`

---

## File Structure

**plm-field-tracker** (`/Users/vikasjindal/git/plm-field-tracker`)
- Create: `src/main/java/com/sandisk/plm/tracker/model/ObaResolution.java` — resolved item numbers + warnings (internal, not serialized).
- Create: `src/main/java/com/sandisk/plm/tracker/service/ObaResolverService.java` — SKU → item numbers via BFS over BOM explode rows.
- Create: `src/main/java/com/sandisk/plm/tracker/service/ObaApiKeyGuard.java` — constant-time `X-API-Key` check.
- Create: `src/main/java/com/sandisk/plm/tracker/service/AgileItemFilesClient.java` — HTTP client for the two new agile-service endpoints.
- Create: `src/main/java/com/sandisk/plm/tracker/controller/ObaController.java` — the two public endpoints.
- Modify: `src/main/resources/application.properties` — add `app.oba.*` keys.
- Modify: `src/main/resources/static/whats-new.js` — release entry.
- Test: `src/test/java/com/sandisk/plm/tracker/service/ObaResolverServiceTest.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/ObaApiKeyGuardTest.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/AgileItemFilesClientTest.java`
- Test: `src/test/java/com/sandisk/plm/tracker/controller/ObaControllerTest.java`

**plm-agile-service** (`/Users/vikasjindal/git/plm-agile-service`)
- Modify: `src/main/java/com/sandisk/plm/agile/service/DocumentAttachmentsService.java` — add `listFiles()` + `fetchOne()`.
- Create: `src/main/java/com/sandisk/plm/agile/controller/DocumentFilesController.java` — `/files` and `/file` endpoints.

---

## Task 1: `ObaResolution` model

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/model/ObaResolution.java`

- [ ] **Step 1: Create the model**

```java
package com.sandisk.plm.tracker.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of resolving a SKU to its OBA document item numbers. Internal — the
 * controller maps this onto the JSON response. A null item field means that
 * level could not be resolved (a matching warning is added).
 */
public class ObaResolution {
    public String c039Item;          // the chosen C039 assembly (diagnostic)
    public String labelProofItem;    // L000 Outer Shipping Label item, or null
    public String labelProofDesc;    // its BOM description, or null
    public String labelSpecItem;     // D026 spec item, or null
    public String labelSpecDesc;     // its BOM description, or null
    public final List<String> warnings = new ArrayList<>();
}
```

- [ ] **Step 2: Compile**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o compile`
Expected: BUILD SUCCESS (or download deps if first run).

- [ ] **Step 3: Commit**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
git add src/main/java/com/sandisk/plm/tracker/model/ObaResolution.java
git commit -m "feat(oba): add ObaResolution model"
```

---

## Task 2: `ObaResolverService` — BFS resolution

The pure method `resolveFromRows(sku, rows)` does all the work and is fully unit-testable with synthetic `BomResult` rows (no DB). `resolve(sku)` just calls `BomDataService.explode` and delegates.

`BomResult` constructor (confirmed):
`new BomResult(int level, String parent, String component, String quantity, String description, String notes, String status, String rev, String refDesignator, String findNumber, String itemType)` — getters `getParent()`, `getComponent()`, `getDescription()`.

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/ObaResolverService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/ObaResolverServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import com.sandisk.plm.tracker.model.ObaResolution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ObaResolverServiceTest {

    private final ObaResolverService service = new ObaResolverService(null);

    private static BomResult row(int level, String parent, String component, String desc) {
        return new BomResult(level, parent, component, "1", desc, "", "ACT", "1", "", "", "D");
    }

    /** The 0TS2670 shape from the source doc screenshots. */
    private static List<BomResult> sample() {
        List<BomResult> rows = new ArrayList<>();
        rows.add(row(1, "0TS2670", "C0390TS2670", "BC,0TS2670 Carrera Syndicate"));
        rows.add(row(1, "0TS2670", "C0570TS2670", "BC,0TS2670 Carrera Syndicate"));   // C057, not C039
        rows.add(row(2, "C0390TS2670", "L000-000466-L0", "Label Printing Inst, Product Carrera TCG FIPS")); // not outer
        rows.add(row(2, "C0390TS2670", "L000-000420-L1", "Label-Printing-Instruction,Outer Carton Shipping Label (Google)")); // match
        rows.add(row(2, "C0390TS2670", "54-55-11034", "Label, Security 6.8mm"));
        rows.add(row(3, "L000-000420-L1", "D026-002282-L1", "Work Instructions, eSSD Shipping Label for Google")); // match
        rows.add(row(3, "L000-000420-L1", "54-55-00414", "LABEL,SHIPPING BOX,SANDISK"));
        return rows;
    }

    @Test
    void resolvesLabelProofAndSpec() {
        ObaResolution r = service.resolveFromRows("0TS2670", sample());
        assertEquals("C0390TS2670", r.c039Item);
        assertEquals("L000-000420-L1", r.labelProofItem);
        assertTrue(r.labelProofDesc.toLowerCase().contains("outer carton shipping label"));
        assertEquals("D026-002282-L1", r.labelSpecItem);
        assertTrue(r.warnings.isEmpty(), "expected no warnings, got " + r.warnings);
    }

    @Test
    void flagsMultipleC039() {
        List<BomResult> rows = sample();
        rows.add(row(1, "0TS2670", "C0391TS2670", "BC second C039"));
        rows.add(row(2, "C0391TS2670", "L000-000999-L1", "Label-Printing-Instruction,Outer Carton Shipping Label dup"));
        ObaResolution r = service.resolveFromRows("0TS2670", rows);
        assertEquals("C0390TS2670", r.c039Item); // first in BFS order still wins
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("Multiple C039")),
                "expected a Multiple C039 warning, got " + r.warnings);
    }

    @Test
    void warnsWhenNoC039() {
        List<BomResult> rows = new ArrayList<>();
        rows.add(row(1, "0TS2670", "F000-CAR-0046", "Config file"));
        ObaResolution r = service.resolveFromRows("0TS2670", rows);
        assertNull(r.labelProofItem);
        assertNull(r.labelSpecItem);
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("No C039")),
                "expected a No C039 warning, got " + r.warnings);
    }

    @Test
    void warnsWhenNoD026UnderLabel() {
        List<BomResult> rows = new ArrayList<>();
        rows.add(row(1, "0TS2670", "C0390TS2670", "assembly"));
        rows.add(row(2, "C0390TS2670", "L000-000420-L1", "Outer Carton Shipping Label"));
        ObaResolution r = service.resolveFromRows("0TS2670", rows);
        assertEquals("L000-000420-L1", r.labelProofItem);
        assertNull(r.labelSpecItem);
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("No D026")),
                "expected a No D026 warning, got " + r.warnings);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test -Dtest=ObaResolverServiceTest`
Expected: COMPILATION FAILURE — `ObaResolverService` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import com.sandisk.plm.tracker.model.ObaResolution;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Resolves a SKU to the OBA document item numbers by walking its BOM:
 *   SKU → C039 assembly → L000 "Outer ... Shipping Label" item → D026 spec item.
 * "Search the whole sub-tree at each level"; first match wins, extras are flagged.
 */
@Service
public class ObaResolverService {

    /** Deep enough for SKU→C039→L000→D026 (~4 levels) while staying on the pure-SQL
     *  path — BomDataService routes depth>10 to the offline file fallback. */
    static final int MAX_DEPTH = 10;

    private static final Pattern OUTER_SHIPPING =
            Pattern.compile("outer.*shipping label", Pattern.CASE_INSENSITIVE);

    private final BomDataService bomDataService;

    public ObaResolverService(BomDataService bomDataService) {
        this.bomDataService = bomDataService;
    }

    public ObaResolution resolve(String sku) {
        List<BomResult> rows = bomDataService.explode(sku.trim(), MAX_DEPTH);
        return resolveFromRows(sku.trim(), rows);
    }

    /** Pure resolution over already-fetched BOM rows. Unit-tested without a DB. */
    public ObaResolution resolveFromRows(String sku, List<BomResult> rows) {
        ObaResolution res = new ObaResolution();

        // parent(upper) -> child rows
        Map<String, List<BomResult>> children = new HashMap<>();
        for (BomResult r : rows) {
            if (r.getParent() == null) continue;
            children.computeIfAbsent(r.getParent().toUpperCase(), k -> new ArrayList<>()).add(r);
        }

        List<BomResult> c039s = bfsCollect(sku, children,
                r -> upper(r.getComponent()).startsWith("C039"));
        if (c039s.isEmpty()) {
            res.warnings.add("No C039 assembly found under SKU " + sku);
            return res;
        }
        BomResult c039 = c039s.get(0);
        res.c039Item = c039.getComponent();
        if (c039s.size() > 1) {
            res.warnings.add("Multiple C039 assemblies under " + sku + "; using first "
                    + c039.getComponent() + " (" + c039s.size() + " found)");
        }

        List<BomResult> labels = bfsCollect(c039.getComponent(), children,
                r -> upper(r.getComponent()).startsWith("L000")
                        && OUTER_SHIPPING.matcher(nvl(r.getDescription())).find());
        if (labels.isEmpty()) {
            res.warnings.add("No L000 Outer Shipping Label under C039 " + c039.getComponent());
            return res;
        }
        BomResult label = labels.get(0);
        res.labelProofItem = label.getComponent();
        res.labelProofDesc = label.getDescription();
        if (labels.size() > 1) {
            res.warnings.add("Multiple L000 Outer Shipping Label items under " + c039.getComponent()
                    + "; using first " + label.getComponent() + " (" + labels.size() + " found)");
        }

        List<BomResult> specs = bfsCollect(label.getComponent(), children,
                r -> upper(r.getComponent()).startsWith("D026"));
        if (specs.isEmpty()) {
            res.warnings.add("No D026 spec under L000 " + label.getComponent());
            return res;
        }
        BomResult spec = specs.get(0);
        res.labelSpecItem = spec.getComponent();
        res.labelSpecDesc = spec.getDescription();
        if (specs.size() > 1) {
            res.warnings.add("Multiple D026 spec items under " + label.getComponent()
                    + "; using first " + spec.getComponent() + " (" + specs.size() + " found)");
        }
        return res;
    }

    /** BFS over the whole sub-tree below {@code startNode}, collecting matches in
     *  BFS order. The start node itself is not tested. Cycle-guarded. */
    private static List<BomResult> bfsCollect(String startNode,
                                              Map<String, List<BomResult>> children,
                                              Predicate<BomResult> match) {
        List<BomResult> hits = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(startNode);
        visited.add(upper(startNode));
        while (!queue.isEmpty()) {
            String node = queue.poll();
            List<BomResult> kids = children.get(upper(node));
            if (kids == null) continue;
            for (BomResult kid : kids) {
                if (match.test(kid)) hits.add(kid);
                String comp = kid.getComponent();
                if (comp != null && visited.add(upper(comp))) queue.add(comp);
            }
        }
        return hits;
    }

    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static String nvl(String s) { return s == null ? "" : s; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test -Dtest=ObaResolverServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/ObaResolverService.java \
        src/test/java/com/sandisk/plm/tracker/service/ObaResolverServiceTest.java
git commit -m "feat(oba): resolve SKU to OBA doc item numbers via BOM BFS"
```

---

## Task 3: `ObaApiKeyGuard` — constant-time key check

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/ObaApiKeyGuard.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/ObaApiKeyGuardTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ObaApiKeyGuardTest {

    @Test
    void notConfiguredWhenKeyBlank() {
        ObaApiKeyGuard g = new ObaApiKeyGuard("");
        assertEquals(ObaApiKeyGuard.Result.NOT_CONFIGURED, g.check("anything"));
    }

    @Test
    void unauthorizedWhenMissingOrWrong() {
        ObaApiKeyGuard g = new ObaApiKeyGuard("s3cret-key");
        assertEquals(ObaApiKeyGuard.Result.UNAUTHORIZED, g.check(null));
        assertEquals(ObaApiKeyGuard.Result.UNAUTHORIZED, g.check("wrong"));
    }

    @Test
    void okWhenMatches() {
        ObaApiKeyGuard g = new ObaApiKeyGuard("s3cret-key");
        assertEquals(ObaApiKeyGuard.Result.OK, g.check("  s3cret-key  "));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test -Dtest=ObaApiKeyGuardTest`
Expected: COMPILATION FAILURE — `ObaApiKeyGuard` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates the {@code X-API-Key} header for the OBA endpoints against
 * {@code app.oba.api-key}. Blank config → NOT_CONFIGURED (endpoint must 503, never
 * open by default). Comparison is constant-time.
 */
@Service
public class ObaApiKeyGuard {

    public enum Result { OK, NOT_CONFIGURED, UNAUTHORIZED }

    private final String configuredKey;

    public ObaApiKeyGuard(@Value("${app.oba.api-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
    }

    public Result check(String provided) {
        if (configuredKey.isEmpty()) return Result.NOT_CONFIGURED;
        if (provided == null) return Result.UNAUTHORIZED;
        byte[] a = configuredKey.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b) ? Result.OK : Result.UNAUTHORIZED;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test -Dtest=ObaApiKeyGuardTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/ObaApiKeyGuard.java \
        src/test/java/com/sandisk/plm/tracker/service/ObaApiKeyGuardTest.java
git commit -m "feat(oba): constant-time X-API-Key guard"
```

---

## Task 4: `AgileItemFilesClient` — HTTP client + JSON parse

Mirrors the existing `AgileDocumentAttachmentsClient` HTTP pattern. The JSON parse of the `/files` response is a pure static method, unit-tested directly.

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/AgileItemFilesClient.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/AgileItemFilesClientTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AgileItemFilesClientTest {

    @Test
    void parsesFilesJson() {
        String json = "{\"itemNumber\":\"D026-002282-L1\",\"found\":true,\"files\":["
                + "{\"fileName\":\"spec _Final.pdf\",\"fileDescription\":\"Label Spec _Final\","
                + "\"fileType\":\"pdf\",\"byteSize\":297313},"
                + "{\"fileName\":\"spec _Final.docx\",\"fileDescription\":\"Label Spec _Final\","
                + "\"fileType\":\"docx\",\"byteSize\":111104}]}";
        AgileItemFilesClient.FilesResult r = AgileItemFilesClient.parseFilesJson(json);
        assertTrue(r.found);
        assertNull(r.error);
        assertEquals(2, r.files.size());
        assertEquals("spec _Final.pdf", r.files.get(0).fileName);
        assertEquals("Label Spec _Final", r.files.get(0).fileDescription);
        assertEquals("pdf", r.files.get(0).fileType);
        assertEquals(297313L, r.files.get(0).byteSize);
    }

    @Test
    void parsesNotFound() {
        AgileItemFilesClient.FilesResult r =
                AgileItemFilesClient.parseFilesJson("{\"itemNumber\":\"X\",\"found\":false,\"files\":[]}");
        assertFalse(r.found);
        assertTrue(r.files.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test -Dtest=AgileItemFilesClientTest`
Expected: COMPILATION FAILURE — `AgileItemFilesClient` does not exist.

- [ ] **Step 3: Write the implementation**

```java
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
            String url = agileServiceUrl + "/api/document/"
                    + URLEncoder.encode(itemNumber, StandardCharsets.UTF_8.name()) + "/files";
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
                    .append(URLEncoder.encode(itemNumber, StandardCharsets.UTF_8.name()))
                    .append("/file");
            if (fileName != null && !fileName.isEmpty()) {
                url.append("?name=").append(URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()));
            }
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            fs.httpStatus = conn.getResponseCode();
            if (fs.httpStatus != 200) {
                fs.error = "agile-service returned " + fs.httpStatus;
                return fs;
            }
            fs.contentType = conn.getContentType();
            fs.filename = AgileDocumentAttachmentsClient_parseFilename(
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

    private static String AgileDocumentAttachmentsClient_parseFilename(
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test -Dtest=AgileItemFilesClientTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AgileItemFilesClient.java \
        src/test/java/com/sandisk/plm/tracker/service/AgileItemFilesClientTest.java
git commit -m "feat(oba): agile-service per-item files client + JSON parse"
```

---

## Task 5: config keys

**Files:**
- Modify: `src/main/resources/application.properties` (after line 55, `agile.service.url=...`)

- [ ] **Step 1: Add the keys**

Add these lines after the `agile.service.url=http://localhost:8081` line:

```properties

# === OBA Required-Doc API ===
# Static API key for the Auto OBA Buyoff System. BLANK in git — set on the server
# via external config (--spring.config.additional-location). Blank ⇒ endpoint 503s
# (never open by default).
app.oba.api-key=
# Default OBA checklist document number (overridable via ?checklist= on the request).
app.oba.checklist-default=25-07-SM-03-00006
```

- [ ] **Step 2: Verify it compiles/packages**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat(oba): add app.oba.api-key and checklist-default config"
```

---

## Task 6: `ObaController` — public endpoints

The controller builds the `Map<String,Object>` response (matching the codebase JSON convention), applies the API-key gate, and proxies the single-file stream.

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/controller/ObaController.java`
- Test: `src/test/java/com/sandisk/plm/tracker/controller/ObaControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.ObaResolution;
import com.sandisk.plm.tracker.service.AgileItemFilesClient;
import com.sandisk.plm.tracker.service.ObaApiKeyGuard;
import com.sandisk.plm.tracker.service.ObaResolverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ObaController.class)
public class ObaControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ObaResolverService resolver;
    @MockBean private AgileItemFilesClient filesClient;
    @MockBean private ObaApiKeyGuard guard;

    private static AgileItemFilesClient.FilesResult oneFile(String name, String type) {
        AgileItemFilesClient.FilesResult r = new AgileItemFilesClient.FilesResult();
        r.found = true;
        AgileItemFilesClient.FileMeta m = new AgileItemFilesClient.FileMeta();
        m.fileName = name; m.fileType = type; m.byteSize = 100;
        r.files.add(m);
        return r;
    }

    @Test
    void notConfiguredReturns503() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.NOT_CONFIGURED);
        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void badKeyReturns401() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.UNAUTHORIZED);
        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670")
                        .header("X-API-Key", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void happyPathReturnsDocuments() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        ObaResolution res = new ObaResolution();
        res.c039Item = "C0390TS2670";
        res.labelProofItem = "L000-000420-L1";
        res.labelProofDesc = "Outer Carton Shipping Label";
        res.labelSpecItem = "D026-002282-L1";
        when(resolver.resolve(eq("0TS2670"))).thenReturn(res);
        when(filesClient.listFiles("L000-000420-L1")).thenReturn(oneFile("proof.docx", "docx"));
        when(filesClient.listFiles("D026-002282-L1")).thenReturn(oneFile("spec.pdf", "pdf"));
        when(filesClient.listFiles("25-07-SM-03-00006")).thenReturn(oneFile("checklist.pdf", "pdf"));

        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670")
                        .header("X-API-Key", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("0TS2670"))
                .andExpect(jsonPath("$.checklist").value("25-07-SM-03-00006"))
                .andExpect(jsonPath("$.documents[0].role").value("label-proof"))
                .andExpect(jsonPath("$.documents[0].itemNumber").value("L000-000420-L1"))
                .andExpect(jsonPath("$.documents[0].files[0].downloadUrl",
                        containsString("/api/oba/file?item=L000-000420-L1")))
                .andExpect(jsonPath("$.documents[1].role").value("label-spec"))
                .andExpect(jsonPath("$.documents[1].files[0].isPdf").value(true))
                .andExpect(jsonPath("$.documents[2].role").value("oba-checklist"));
    }
}
```

(Add the static import `static org.hamcrest.Matchers.containsString;` at the top.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test -Dtest=ObaControllerTest`
Expected: COMPILATION FAILURE — `ObaController` does not exist.

- [ ] **Step 3: Write the implementation**

```java
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

        List<Map<String, Object>> documents = new ArrayList<>();
        documents.add(roleDoc("label-proof", res.labelProofItem, res.labelProofDesc, warnings));
        documents.add(roleDoc("label-spec", res.labelSpecItem, res.labelSpecDesc, warnings));
        documents.add(roleDoc("oba-checklist", checklistNum, null, warnings));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku.trim());
        body.put("generatedAt", Instant.now().toString());
        body.put("checklist", checklistNum);
        body.put("documents", documents);
        body.put("warnings", warnings);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> roleDoc(String role, String itemNumber, String itemDesc,
                                        List<String> warnings) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("role", role);
        doc.put("itemNumber", itemNumber);
        if (itemDesc != null) doc.put("itemDescription", itemDesc);
        if (itemNumber == null) {
            doc.put("found", false);
            return doc;   // resolver already added a warning for the missing level
        }
        AgileItemFilesClient.FilesResult fr = filesClient.listFiles(itemNumber);
        if (!fr.found) {
            doc.put("found", false);
            warnings.add("No attachments resolved for " + role + " item " + itemNumber
                    + (fr.error != null ? " (" + fr.error + ")" : ""));
            return doc;
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (AgileItemFilesClient.FileMeta m : fr.files) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("fileName", m.fileName);
            if (m.fileDescription != null) f.put("fileDescription", m.fileDescription);
            f.put("fileType", m.fileType);
            f.put("byteSize", m.byteSize);
            f.put("isPdf", m.fileName != null && m.fileName.toLowerCase().endsWith(".pdf"));
            f.put("downloadUrl", "/api/oba/file?item=" + enc(itemNumber) + "&name=" + enc(m.fileName));
            files.add(f);
        }
        doc.put("found", true);
        doc.put("files", files);
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
            h.setContentType(fs.contentType != null
                    ? MediaType.parseMediaType(fs.contentType) : MediaType.APPLICATION_OCTET_STREAM);
            h.setContentDisposition(ContentDisposition.builder("attachment").filename(fs.filename).build());
            h.setContentLength(fs.bytes.length);
            return new ResponseEntity<>(fs.bytes, h, org.springframework.http.HttpStatus.OK);
        }
        if (fs.httpStatus == 404) return ResponseEntity.status(404).build();
        if (fs.httpStatus == 400) return ResponseEntity.status(400).build();
        LOG.info("[OBA] file proxy upstream status " + fs.httpStatus + " for " + item + "/" + name);
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
        try { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name()); }
        catch (Exception e) { return s; }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test -Dtest=ObaControllerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full toolkit test suite**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o test`
Expected: PASS (existing 15 test files + 4 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/ObaController.java \
        src/test/java/com/sandisk/plm/tracker/controller/ObaControllerTest.java
git commit -m "feat(oba): required-docs + file endpoints with API-key gate"
```

---

## Task 7: agile-service — `listFiles()` + `fetchOne()` on `DocumentAttachmentsService`

SDK code; **not** locally testable (no live Agile). Reuses the existing
`IItem → TABLE_ATTACHMENTS → IFileFolder → TABLE_FILES → IAttachmentFile` traversal.
Confirmed SDK constants: `FileFolderConstants.ATT_FILES_FILE_NAME`,
`ATT_FILES_FILE_DESCRIPTION`, `ATT_FILES_FILE_TYPE`, `ATT_FILES_FILE_SIZE`.

**Files:**
- Modify: `src/main/java/com/sandisk/plm/agile/service/DocumentAttachmentsService.java`

- [ ] **Step 1: Add `FileMeta` class + `listFiles()` + `fetchOne()`**

Insert these members into `DocumentAttachmentsService` (e.g. just after the existing
`Bundle` class and `AsZipMode` enum, before `fetchAll`):

```java
    /** Per-file metadata (no bytes) for the OBA /files endpoint. */
    public static final class FileMeta {
        public String fileName;
        public String fileDescription;
        public String fileType;
        public long byteSize;
    }

    /**
     * List the attachment files on {@code docNumber} without downloading bytes.
     * Returns {@code null} when no item with that number exists (caller → 404);
     * an empty list when the item exists but has no attachments.
     */
    public List<FileMeta> listFiles(String docNumber) throws APIException, IOException {
        IItem item;
        try {
            item = (IItem) session.getObject(IItem.OBJECT_TYPE, docNumber);
        } catch (ClassCastException cce) {
            throw new IOException("Object " + docNumber + " is not an IItem: " + cce.getMessage(), cce);
        }
        if (item == null) return null;

        List<FileMeta> out = new ArrayList<>();
        ITable attTable = item.getTable(ItemConstants.TABLE_ATTACHMENTS);
        Iterator<?> rowIt = attTable.iterator();
        while (rowIt.hasNext()) {
            IRow attRow = (IRow) rowIt.next();
            IFileFolder ff;
            try {
                ff = (IFileFolder) attRow.getReferent();
            } catch (Exception e) {
                LOG.warning("[DOC-FILES] row referent not IFileFolder on " + docNumber + ": " + e.getMessage());
                continue;
            }
            if (ff == null) continue;
            ITable filesTable = ff.getTable(FileFolderConstants.TABLE_FILES);
            Iterator<?> fit = filesTable.iterator();
            while (fit.hasNext()) {
                IRow fileRow = (IRow) fit.next();
                FileMeta m = new FileMeta();
                m.fileName = strVal(fileRow, FileFolderConstants.ATT_FILES_FILE_NAME);
                m.fileDescription = strVal(fileRow, FileFolderConstants.ATT_FILES_FILE_DESCRIPTION);
                m.fileType = strVal(fileRow, FileFolderConstants.ATT_FILES_FILE_TYPE);
                if (m.fileType == null || m.fileType.isEmpty()) m.fileType = extensionOf(m.fileName);
                m.byteSize = digitsToLong(strVal(fileRow, FileFolderConstants.ATT_FILES_FILE_SIZE));
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Fetch a single named file from {@code docNumber}. If {@code fileName} is null
     * and the item has exactly one file, returns that file. Returns {@code null}
     * when the item does not exist; throws {@link java.io.FileNotFoundException}
     * when the named file is absent and {@link IllegalStateException} when no name
     * is given but the item has multiple files.
     */
    public Bundle fetchOne(String docNumber, String fileName) throws APIException, IOException {
        IItem item;
        try {
            item = (IItem) session.getObject(IItem.OBJECT_TYPE, docNumber);
        } catch (ClassCastException cce) {
            throw new IOException("Object " + docNumber + " is not an IItem: " + cce.getMessage(), cce);
        }
        if (item == null) return null;

        List<IRow> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ITable attTable = item.getTable(ItemConstants.TABLE_ATTACHMENTS);
        Iterator<?> rowIt = attTable.iterator();
        while (rowIt.hasNext()) {
            IRow attRow = (IRow) rowIt.next();
            IFileFolder ff;
            try { ff = (IFileFolder) attRow.getReferent(); } catch (Exception e) { continue; }
            if (ff == null) continue;
            ITable filesTable = ff.getTable(FileFolderConstants.TABLE_FILES);
            Iterator<?> fit = filesTable.iterator();
            while (fit.hasNext()) {
                IRow fileRow = (IRow) fit.next();
                String n = strVal(fileRow, FileFolderConstants.ATT_FILES_FILE_NAME);
                candidates.add(fileRow);
                names.add(n == null ? "attachment" : n);
            }
        }

        if (candidates.isEmpty()) throw new java.io.FileNotFoundException("no files on " + docNumber);

        int chosen;
        if (fileName != null && !fileName.trim().isEmpty()) {
            chosen = -1;
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i) != null && names.get(i).trim().equalsIgnoreCase(fileName.trim())) { chosen = i; break; }
            }
            if (chosen < 0) throw new java.io.FileNotFoundException("file '" + fileName + "' not on " + docNumber);
        } else {
            if (candidates.size() > 1) {
                throw new IllegalStateException(docNumber + " has " + candidates.size()
                        + " files; specify ?name=");
            }
            chosen = 0;
        }

        IRow fileRow = candidates.get(chosen);
        String fname = names.get(chosen);
        byte[] bytes;
        try (InputStream in = ((IAttachmentFile) fileRow).getFile();
             ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            if (in == null) throw new IOException("getFile() returned null for " + fname + " on " + docNumber);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            bytes = out.toByteArray();
        }
        Bundle b = new Bundle();
        b.bytes = bytes;
        b.filename = fname;
        b.contentType = contentTypeOf(fname);
        b.fileCount = 1;
        b.totalBytes = bytes.length;
        return b;
    }

    private static String strVal(IRow row, Object attrId) {
        try {
            Object v = row.getValue(attrId);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static long digitsToLong(String s) {
        if (s == null) return 0L;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') d.append(c); else if (d.length() > 0) break;
        }
        if (d.length() == 0) return 0L;
        try { return Long.parseLong(d.toString()); } catch (Exception e) { return 0L; }
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase() : "";
    }
```

Note: `getValue(Object)` accepts the `Integer` attribute-id constants directly (the
existing `fetchAll` already calls `fileRow.getValue(FileFolderConstants.ATT_FILES_FILE_NAME)`).

- [ ] **Step 2: Compile the agile-service**

Run: `cd /Users/vikasjindal/git/plm-agile-service && mvn -q -o compile`
Expected: BUILD SUCCESS (deprecation/unchecked warnings on legacy SDK APIs are OK).

- [ ] **Step 3: Commit**

```bash
cd /Users/vikasjindal/git/plm-agile-service
git add src/main/java/com/sandisk/plm/agile/service/DocumentAttachmentsService.java
git commit -m "feat(oba): listFiles + fetchOne on DocumentAttachmentsService"
```

---

## Task 8: agile-service — `DocumentFilesController`

**Files:**
- Create: `src/main/java/com/sandisk/plm/agile/controller/DocumentFilesController.java`

- [ ] **Step 1: Create the controller**

```java
package com.sandisk.plm.agile.controller;

import com.sandisk.plm.agile.service.DocumentAttachmentsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-item attachment metadata + single-file download for the toolkit's OBA API.
 *
 *   GET /api/document/{docNumber}/files          → { itemNumber, found, files:[...] }
 *   GET /api/document/{docNumber}/file?name=<f>   → one file's bytes
 */
@RestController
public class DocumentFilesController {

    private static final Logger LOG = Logger.getLogger(DocumentFilesController.class.getName());

    @Autowired private DocumentAttachmentsService service;

    @GetMapping("/api/document/{docNumber}/files")
    public ResponseEntity<?> files(@PathVariable String docNumber) {
        try {
            List<DocumentAttachmentsService.FileMeta> metas = service.listFiles(docNumber);
            if (metas == null) return ResponseEntity.status(404).build();
            List<Map<String, Object>> files = new ArrayList<>();
            for (DocumentAttachmentsService.FileMeta m : metas) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("fileName", m.fileName);
                f.put("fileDescription", m.fileDescription);
                f.put("fileType", m.fileType);
                f.put("byteSize", m.byteSize);
                files.add(f);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("itemNumber", docNumber);
            body.put("found", true);
            body.put("files", files);
            return ResponseEntity.ok(body);
        } catch (com.agile.api.APIException ae) {
            LOG.log(Level.WARNING, "[DOC-FILES] APIException for " + docNumber + ": " + ae.getMessage());
            return ResponseEntity.status(404).build();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[DOC-FILES] failed for " + docNumber, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/api/document/{docNumber}/file")
    public ResponseEntity<byte[]> file(@PathVariable String docNumber,
                                       @RequestParam(value = "name", required = false) String name) {
        try {
            DocumentAttachmentsService.Bundle b = service.fetchOne(docNumber, name);
            if (b == null) return ResponseEntity.status(404).build();
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType(b.contentType));
            h.setContentDisposition(ContentDisposition.builder("inline").filename(b.filename).build());
            h.setContentLength(b.bytes.length);
            return new ResponseEntity<>(b.bytes, h, org.springframework.http.HttpStatus.OK);
        } catch (IllegalStateException ise) {
            // No name given but multiple files.
            return ResponseEntity.status(400).build();
        } catch (java.io.FileNotFoundException fnf) {
            return ResponseEntity.status(404).build();
        } catch (com.agile.api.APIException ae) {
            LOG.log(Level.WARNING, "[DOC-FILES] APIException (file) for " + docNumber + ": " + ae.getMessage());
            return ResponseEntity.status(404).build();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[DOC-FILES] file fetch failed for " + docNumber, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd /Users/vikasjindal/git/plm-agile-service && mvn -q -o compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
cd /Users/vikasjindal/git/plm-agile-service
git add src/main/java/com/sandisk/plm/agile/controller/DocumentFilesController.java
git commit -m "feat(oba): /files and /file per-item endpoints"
```

---

## Task 9: What's New entry + build both JARs

**Files:**
- Modify: `src/main/resources/static/whats-new.js` (toolkit)

- [ ] **Step 1: Add a release entry at the TOP of `WHATS_NEW_RELEASES`**

Insert as the first array element (before the current top `June 25, 2026` entry):

```javascript
    {
        date: 'June 26, 2026',
        title: 'OBA Required-Doc API &middot; machine retrieval of shipping label + checklist',
        items: [
            { badge: 'new', text: '<strong>Auto OBA document API.</strong> A keyed endpoint (<code>GET /api/oba/required-docs?sku=&hellip;</code>) resolves a SKU&rsquo;s Outer Shipping Label proof (L000), its D026 label specification, and the General SSD OBA Checklist, returning per-file download links the Auto OBA Buyoff System can pull.' },
            { badge: 'new', text: '<strong>Per-file download.</strong> <code>GET /api/oba/file?item=&hellip;&amp;name=&hellip;</code> streams a single attachment (PDF/docx) straight from Agile.' }
        ]
    },
```

- [ ] **Step 2: Build the toolkit JAR**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o clean package -DskipTests=false`
Expected: BUILD SUCCESS; `target/plm-field-tracker-1.0.1.jar` produced.

- [ ] **Step 3: Build the agile-service JAR**

Run: `cd /Users/vikasjindal/git/plm-agile-service && mvn -q -o clean package`
Expected: BUILD SUCCESS; `target/plm-agile-service-1.0.0.jar` produced.

- [ ] **Step 4: Commit the changelog**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
git add src/main/resources/static/whats-new.js
git commit -m "docs(oba): What's New entry for OBA Required-Doc API"
```

---

## Task 10: Stage artifacts + QA validation steps

Local end-to-end is not possible (the :8081 SDK call chain runs only on the server).
Resolution logic was unit-tested locally in Tasks 2–6.

- [ ] **Step 1: Stage the toolkit JAR (per project rules)**

If `/Volumes/uls-ep-aglipccb/` is not mounted, STOP and ask the user to mount it.

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
stat -f "%z" ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar \
            /Volumes/uls-ep-aglipccb/plm-toolkit/staging/plm-field-tracker-1.0.1.jar
```
Expected: the two sizes match.

- [ ] **Step 2: Hand off the agile-service JAR to QA**

`plm-agile-service-1.0.0.jar` deploys to QA (`uls-eq-aglapp01.wdc.com`) per that project's
own deploy flow. Confirm with Vikas before deploying — this carries the new SDK endpoints.

- [ ] **Step 3: QA validation checklist (run on the server, with the real API key)**

```bash
# 1. agile-service file metadata (live SDK)
curl -s http://localhost:8081/api/document/D026-002282-L1/files | head
curl -s http://localhost:8081/api/document/L000-000420-L1/files | head
curl -s "http://localhost:8081/api/document/25-07-SM-03-00006/files" | head

# 2. toolkit resolution + links (replace <KEY> with the configured app.oba.api-key)
curl -s -H "X-API-Key: <KEY>" "http://localhost:8090/api/oba/required-docs?sku=0TS2670" | python3 -m json.tool

# 3. follow a download link from the JSON (PDF should open)
curl -s -H "X-API-Key: <KEY>" \
  "http://localhost:8090/api/oba/file?item=D026-002282-L1&name=<urlencoded-pdf-name>" -o /tmp/spec.pdf
file /tmp/spec.pdf   # expect: PDF document

# 4. negative checks
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8090/api/oba/required-docs?sku=0TS2670"          # 503 if key unset, else 401
curl -s -o /dev/null -w "%{http_code}\n" -H "X-API-Key: wrong" "http://localhost:8090/api/oba/required-docs?sku=0TS2670"  # 401
```
Expected: `required-docs` returns the three roles with `found:true` and valid
`downloadUrl`s; the followed PDF link returns a real PDF; the negative checks return
503/401 as noted. Do NOT paste the API key into chat/email/git per the repo credential policy.

---

## Self-Review

**Spec coverage** (against `2026-06-26-oba-required-docs-api-design.md`):
- §Components — agile-service `listFiles`/`fetchOne` (Task 7) + `DocumentFilesController` (Task 8); toolkit `ObaResolverService` (Task 2), `AgileItemFilesClient` (Task 4), `ObaController` (Task 6). ✓
- §Public API contract — `/required-docs` JSON shape + key-less relative `downloadUrl` (Task 6 test asserts shape); `/file` proxy (Task 6). ✓
- §Resolution logic — BFS sub-tree, first-match + ambiguity warnings, per-level not-found warnings (Task 2). ✓
- §Auth & config — `X-API-Key` constant-time, 503-when-unconfigured / 401 (Task 3 + Task 6 gate), config keys blank in git (Task 5). ✓
- §Error handling — unknown SKU → 200 + found:false (Task 6 roleDoc returns found:false when item null/no files); per-role isolation (each role fetched independently); `/file` 404/400/502 mapping (Task 6 + Task 8). ✓
- §Testing — local resolver/guard/parse/controller tests (Tasks 2–6); QA SDK + e2e (Task 10). ✓
- §Build/deploy — whats-new + staging + local copy + agile-service to QA (Tasks 9–10). ✓

**Placeholder scan:** No TBD/TODO; every code step has complete code; commands have expected output. ✓

**Type consistency:** `ObaResolution` fields (`labelProofItem`, `labelProofDesc`, `labelSpecItem`, `labelSpecDesc`, `c039Item`, `warnings`) used identically in Tasks 1/2/6. `AgileItemFilesClient.FilesResult`/`FileMeta`/`FileStream` field names match across Tasks 4/6. `DocumentAttachmentsService.FileMeta` + `Bundle` (existing) used consistently in Tasks 7/8. `ObaApiKeyGuard.Result` enum values (`OK`/`NOT_CONFIGURED`/`UNAUTHORIZED`) match across Tasks 3/6. ✓
