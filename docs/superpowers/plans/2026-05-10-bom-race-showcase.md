# BOM Race Concept Showcase — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an admin-only "Labs ▸ BOM Race" tab that races the toolkit's cached-SQL BOM explode against the live Agile SDK and shows side-by-side timings + result-match score.

**Architecture:** Two repos. `plm-agile-service` (port 8081) gets new SDK-backed BOM endpoints. `plm-field-tracker` gets a new orchestrator that fires both lanes from one `CompletableFuture.allOf`, streams progress via SSE, and renders a split-lane race screen. The toolkit lane reuses the existing batched `BomDataService.explodeMultiple()` (production path); the SDK lane is sequential per-item by design.

**Tech Stack:** Java 1.8, Spring Boot, Spring MVC `SseEmitter`, Oracle (CONNECT BY + DBMS_RANDOM), Agile SDK (`AgileAPI936.jar`, kept inside `plm-agile-service` only), vanilla JS + `EventSource` on the frontend, JUnit Jupiter for unit tests.

**Spec:** `docs/superpowers/specs/2026-05-10-bom-race-showcase-design.md`

**Testing reality:** The local Mac can't reach Agile. The SDK lane only works when deployed on the remote server. Plan that:
- All `plm-agile-service` work compiles + builds locally; smoke-test happens on remote (Task D1).
- Backend orchestration in `plm-field-tracker` can be partially exercised locally — the pre-flight health check will return 503 ("agile-service-unreachable"), which is the *expected* local error path. UI gating, sample-query, and "service down" callout all testable locally.
- End-to-end race only verifiable on remote with `plmadmin`/`newworld` creds.

**Commit policy:** Per `~/git/CLAUDE.md`, default to staging only `.java` files in commits. Frontend tasks (HTML/JS/properties/markdown) explicitly call out the non-`.java` files in their `git add` commands so the boundary is intentional.

---

## File Structure

### plm-agile-service (`~/git/plm-agile-service/`)

| Path | New/Modify | Responsibility |
|---|---|---|
| `src/main/java/com/sandisk/plm/agile/model/SdkBomRow.java` | NEW | DTO for one BOM row: `{level, parent, component, qty, refdes, seq}`. Mirrors toolkit's `BomResult` shape. |
| `src/main/java/com/sandisk/plm/agile/service/AgileBomService.java` | NEW | SDK-backed BOM walk. Two public methods: `explodeOne(item, maxDepth)` (recursive walk for one item) and `explodeAll(items, maxDepth)` (sequential per-item, with per-item timing). |
| `src/main/java/com/sandisk/plm/agile/controller/AgileBomController.java` | NEW | `GET /api/lookup/bom/health`, `POST /api/lookup/bom/explode`. Reuses existing Agile session injection from `AgileLookupController` pattern. |

### plm-field-tracker (`~/git/plm-field-tracker/`)

| Path | New/Modify | Responsibility |
|---|---|---|
| `src/main/java/com/sandisk/plm/tracker/model/BomRaceRun.java` | NEW | In-memory state for one race: `runId`, `items`, `toolkitFuture`, `sdkFuture`, `emitter`, `startedAtNanos`, `createdAtMillis` (for lazy TTL sweep). |
| `src/main/java/com/sandisk/plm/tracker/service/AgileBomRaceClient.java` | NEW | Spring `RestTemplate` HTTP client to `plm-agile-service /api/lookup/bom/explode` and `/health`. URL from existing `agile.service.url` property. |
| `src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java` | NEW | Race orchestrator. Owns `Map<runId, BomRaceRun>`, fires `CompletableFuture.allOf`, emits SSE events, computes set+structural match, lazy-sweeps expired runs on each `start`. |
| `src/main/java/com/sandisk/plm/tracker/controller/BomRaceController.java` | NEW | `POST /api/bomrace/start`, `GET /api/bomrace/{runId}/stream`. Admin-gated via `session.getAttribute("isPlmAdmin")` (same pattern as `AiEvalController:741`). |
| `src/test/java/com/sandisk/plm/tracker/service/BomRaceMatchTest.java` | NEW | TDD coverage for the comparison logic (set match, structural match, drift detection). Pure functions on lists — no Spring context. |
| `src/main/resources/static/index.html` | MODIFY | Add `tabLabs` top-level button (`np-admin-only`), Labs sub-nav with "BOM Race" sub-tab, BOM Race screen markup. |
| `src/main/resources/static/app.js` | MODIFY | Register `'labs'` and `'bomrace'` cases in `switchTab()`. |
| `src/main/resources/static/bomrace.js` | NEW | Frontend driver: input form → `/start` POST → `EventSource` on `/stream` → progress bars → scoreboard → diff expander. |
| `src/main/resources/static/whats-new.js` | MODIFY | Add today's release entry with the BOM Race showcase line. |
| `src/main/resources/application.properties` | MODIFY | Add four `app.bomrace.*` properties. |

---

## Phase A — plm-agile-service (SDK provider)

### Task A1: SdkBomRow model + AgileBomService.explodeOne

**Files:**
- Create: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/model/SdkBomRow.java`
- Create: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/service/AgileBomService.java`

- [ ] **Step 1: Create the SdkBomRow DTO**

```java
// ~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/model/SdkBomRow.java
package com.sandisk.plm.agile.model;

public class SdkBomRow {
    public int level;
    public String parent;
    public String component;
    public String qty;
    public String refdes;
    public String seq;

    public SdkBomRow() {}

    public SdkBomRow(int level, String parent, String component, String qty, String refdes, String seq) {
        this.level = level;
        this.parent = parent;
        this.component = component;
        this.qty = qty == null ? "" : qty;
        this.refdes = refdes == null ? "" : refdes;
        this.seq = seq == null ? "" : seq;
    }
}
```

- [ ] **Step 2: Create AgileBomService skeleton with `explodeOne`**

Reference: `~/plm_repos/bomextract/src/SandiskOQRMExtractorUsingDatabase.java` for the live SDK BOM-walk pattern. The Agile constants for the BOM table cell are in `com.agile.api.ItemConstants` (e.g. `TABLE_BOM`); column IDs are e.g. `ATT_BOM_ITEM_NUMBER`, `ATT_BOM_QTY`, `ATT_BOM_REF_DES`, `ATT_BOM_FIND_NUM`. Verify the exact constant names against `~/Desktop/WinsData/Documents/SanDisk/common/lib/AgileAPI936.jar` if needed.

```java
// ~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/service/AgileBomService.java
package com.sandisk.plm.agile.service;

import com.agile.api.*;
import com.sandisk.plm.agile.model.SdkBomRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.logging.Logger;

@Service
public class AgileBomService {

    private static final Logger logger = Logger.getLogger(AgileBomService.class.getName());

    @Value("${agile.server.url}")        private String agileUrl;
    @Value("${agile.username}")          private String agileUsername;
    @Value("${agile.password}")          private String agilePassword;

    private IAgileSession session;

    @PostConstruct
    void login() throws APIException {
        Map<Integer, String> params = new HashMap<>();
        params.put(AgileSessionFactory.USERNAME, agileUsername);
        params.put(AgileSessionFactory.PASSWORD, agilePassword);
        session = AgileSessionFactory.getInstance(agileUrl).createSession(params);
        logger.info("[AGILE_BOM] SDK session established for " + agileUsername);
    }

    /** Recursive top-down BOM walk for one item. Emits parent->child rows up to maxDepth.
     *  Level convention matches the toolkit: input item = 0, direct children = 1, etc. */
    public List<SdkBomRow> explodeOne(String itemNumber, int maxDepth) throws APIException {
        List<SdkBomRow> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        walk(itemNumber.trim(), 0, maxDepth, out, visited);
        return out;
    }

    private void walk(String partNumber, int level, int maxDepth,
                      List<SdkBomRow> out, Set<String> visited) throws APIException {
        if (level >= maxDepth) return;
        if (!visited.add(partNumber.toUpperCase())) return; // cycle guard

        IItem item = (IItem) session.getObject(IItem.OBJECT_TYPE, partNumber);
        if (item == null) return;

        ITable bomTable = item.getTable(ItemConstants.TABLE_BOM);
        Iterator<?> rows = bomTable.getReferentIterator();
        while (rows.hasNext()) {
            IRow row = (IRow) rows.next();
            String childNum = String.valueOf(row.getValue(ItemConstants.ATT_BOM_ITEM_NUMBER));
            String qty      = String.valueOf(row.getValue(ItemConstants.ATT_BOM_QTY));
            String refdes   = String.valueOf(row.getValue(ItemConstants.ATT_BOM_REF_DES));
            String seq      = String.valueOf(row.getValue(ItemConstants.ATT_BOM_FIND_NUM));

            out.add(new SdkBomRow(level + 1, partNumber, childNum, qty, refdes, seq));
            walk(childNum, level + 1, maxDepth, out, visited);
        }
    }
}
```

- [ ] **Step 3: Build to verify compilation**

```bash
cd ~/git/plm-agile-service && mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS, no compile errors. If a constant name (e.g. `ATT_BOM_FIND_NUM`) doesn't resolve, decompile `~/Desktop/WinsData/Documents/SanDisk/common/lib/AgileAPI936.jar` to find the actual identifier and update the call.

- [ ] **Step 4: Commit**

```bash
cd ~/git/plm-agile-service && \
git add src/main/java/com/sandisk/plm/agile/model/SdkBomRow.java \
        src/main/java/com/sandisk/plm/agile/service/AgileBomService.java && \
git commit -m "$(cat <<'EOF'
feat(bom): add AgileBomService with single-item recursive SDK explode

First slice of the BOM Race showcase backend on the SDK side. Walks
IItem.getTable(BOM) recursively up to maxDepth, with per-walk visited-set
cycle guard. Multi-item entry point + controller land in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A2: explodeAll + AgileBomController endpoints

**Files:**
- Modify: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/service/AgileBomService.java` (add `explodeAll`)
- Create: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/controller/AgileBomController.java`

- [ ] **Step 1: Add `explodeAll` to AgileBomService**

Append this method (and the supporting `PerItemResult` static class) to `AgileBomService.java` from Task A1:

```java
    /** Sequential per-item explode with per-item wall-clock timing.
     *  Sequential by design — Agile SDK session isn't thread-safe and parallelism
     *  would distort the comparison with login-overhead. */
    public Result explodeAll(List<String> items, int maxDepth) {
        long t0 = System.currentTimeMillis();
        List<PerItemResult> perItem = new ArrayList<>(items.size());
        int errorCount = 0;
        for (String item : items) {
            long itemStart = System.currentTimeMillis();
            try {
                List<SdkBomRow> rows = explodeOne(item, maxDepth);
                perItem.add(new PerItemResult(item, rows, System.currentTimeMillis() - itemStart, null));
            } catch (Exception e) {
                errorCount++;
                logger.warning("[AGILE_BOM] " + item + " failed: " + e.getMessage());
                perItem.add(new PerItemResult(item, Collections.emptyList(),
                        System.currentTimeMillis() - itemStart, e.getMessage()));
            }
        }
        return new Result(perItem, System.currentTimeMillis() - t0, errorCount);
    }

    public static class PerItemResult {
        public String item;
        public List<SdkBomRow> rows;
        public long durationMs;
        public String error; // null on success

        public PerItemResult() {}
        public PerItemResult(String item, List<SdkBomRow> rows, long durationMs, String error) {
            this.item = item; this.rows = rows; this.durationMs = durationMs; this.error = error;
        }
    }

    public static class Result {
        public List<PerItemResult> perItem;
        public long totalMs;
        public int errorCount;

        public Result() {}
        public Result(List<PerItemResult> perItem, long totalMs, int errorCount) {
            this.perItem = perItem; this.totalMs = totalMs; this.errorCount = errorCount;
        }
    }
```

- [ ] **Step 2: Create AgileBomController**

```java
// ~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/controller/AgileBomController.java
package com.sandisk.plm.agile.controller;

import com.sandisk.plm.agile.service.AgileBomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/lookup/bom")
public class AgileBomController {

    @Autowired
    private AgileBomService bomService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("service", "agile-bom");
        return resp;
    }

    /** POST body: {"items":["FG-1","FG-2"], "maxDepth":20}
     *  Returns: {perItem:[{item,rows[],durationMs,error}], totalMs, errorCount} */
    @PostMapping("/explode")
    public AgileBomService.Result explode(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) body.getOrDefault("items", Collections.emptyList());
        int maxDepth = ((Number) body.getOrDefault("maxDepth", 20)).intValue();
        return bomService.explodeAll(items, maxDepth);
    }
}
```

- [ ] **Step 3: Build to verify**

```bash
cd ~/git/plm-agile-service && mvn -q -DskipTests package
```

Expected: BUILD SUCCESS, JAR produced under `target/`. End-to-end smoke test happens on remote in Task D1 — local can't reach Agile.

- [ ] **Step 4: Commit**

```bash
cd ~/git/plm-agile-service && \
git add src/main/java/com/sandisk/plm/agile/service/AgileBomService.java \
        src/main/java/com/sandisk/plm/agile/controller/AgileBomController.java && \
git commit -m "$(cat <<'EOF'
feat(bom): add /api/lookup/bom/{health,explode} endpoints

Sequential per-item BOM explode with per-item timing + error capture.
Sequential is intentional — Agile SDK session is not thread-safe, and
per-thread sessions would distort the BOM Race comparison with login
overhead. End-to-end smoke happens on remote.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase B — plm-field-tracker backend

### Task B1: AgileBomRaceClient

**Files:**
- Create: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/AgileBomRaceClient.java`

- [ ] **Step 1: Create the HTTP client**

```java
// ~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/AgileBomRaceClient.java
package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.logging.Logger;

@Service
public class AgileBomRaceClient {

    private static final Logger logger = Logger.getLogger(AgileBomRaceClient.class.getName());

    @Value("${agile.service.url:http://localhost:8081}")
    private String agileServiceUrl;

    private final RestTemplate http = new RestTemplate();

    /** Pre-flight. Returns true only if /api/lookup/bom/health responds 200 with ok=true. */
    public boolean healthCheck() {
        try {
            ResponseEntity<Map> resp = http.getForEntity(
                agileServiceUrl + "/api/lookup/bom/health", Map.class);
            return resp.getStatusCode() == HttpStatus.OK
                && Boolean.TRUE.equals(resp.getBody() != null ? resp.getBody().get("ok") : null);
        } catch (ResourceAccessException e) {
            logger.warning("[BOM_RACE] agile-service unreachable: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warning("[BOM_RACE] agile-service health check failed: " + e.getMessage());
            return false;
        }
    }

    /** POST /api/lookup/bom/explode. Returns the raw response map; caller unpacks perItem[]. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> explode(List<String> items, int maxDepth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("maxDepth", maxDepth);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = http.exchange(
            agileServiceUrl + "/api/lookup/bom/explode",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        return resp.getBody();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd ~/git/plm-field-tracker && mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/java/com/sandisk/plm/tracker/service/AgileBomRaceClient.java && \
git commit -m "$(cat <<'EOF'
feat(bomrace): add HTTP client for plm-agile-service BOM endpoints

Pre-flight healthCheck() returns false on unreachable/unhealthy service
(captures the local-Mac case explicitly). explode() POSTs items+maxDepth
and returns the raw map for the orchestrator to unpack.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B2: BomRaceRun model + BomRaceService scaffolding

**Files:**
- Create: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/model/BomRaceRun.java`
- Create: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java`

- [ ] **Step 1: Create BomRaceRun model**

```java
// ~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/model/BomRaceRun.java
package com.sandisk.plm.tracker.model;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BomRaceRun {
    public final String runId;
    public final List<String> items;
    public final int maxDepth;
    public final long startedAtNanos;
    public final long createdAtMillis;
    public volatile SseEmitter emitter;
    public volatile CompletableFuture<?> toolkitFuture;
    public volatile CompletableFuture<?> sdkFuture;

    public BomRaceRun(String runId, List<String> items, int maxDepth, long startedAtNanos) {
        this.runId = runId;
        this.items = items;
        this.maxDepth = maxDepth;
        this.startedAtNanos = startedAtNanos;
        this.createdAtMillis = System.currentTimeMillis();
    }
}
```

- [ ] **Step 2: Create BomRaceService scaffolding (sample query + lazy sweep)**

```java
// ~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomRaceRun;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class BomRaceService {

    private static final Logger logger = Logger.getLogger(BomRaceService.class.getName());

    private final DataSource customDataSource;
    private final BomDataService bomDataService;
    private final AgileBomRaceClient agileClient;

    @Value("${app.bomrace.max-items:10}")          private int maxItems;
    @Value("${app.bomrace.sdk.item-timeout-ms:60000}")  private long sdkItemTimeoutMs;
    @Value("${app.bomrace.race-timeout-ms:300000}")     private long raceTimeoutMs;
    @Value("${app.bomrace.run-ttl-ms:600000}")          private long runTtlMs;

    private final Map<String, BomRaceRun> runs = new ConcurrentHashMap<>();

    public BomRaceService(@Qualifier("customDataSource") DataSource customDataSource,
                          BomDataService bomDataService,
                          AgileBomRaceClient agileClient) {
        this.customDataSource = customDataSource;
        this.bomDataService = bomDataService;
        this.agileClient = agileClient;
    }

    /** Lazy sweep of expired runs. Called at the top of every startRace. */
    void sweepExpired() {
        long cutoff = System.currentTimeMillis() - runTtlMs;
        runs.entrySet().removeIf(e -> e.getValue().createdAtMillis < cutoff);
    }

    /** Random sample from items that actually have BOMs (otherwise most picks would be 0-row leaves). */
    public List<String> sampleRandomItemsWithBoms(int n) {
        int capped = Math.min(n, maxItems);
        String sql =
            "SELECT i.PART_NUMBER FROM item_extract i " +
            "WHERE i.PART_NUMBER IN (SELECT DISTINCT BOM_NUMBER FROM bom_extract) " +
            "ORDER BY DBMS_RANDOM.VALUE FETCH FIRST ? ROWS ONLY";
        List<String> out = new ArrayList<>(capped);
        try (Connection c = customDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, capped);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            logger.warning("[BOM_RACE] sample query failed: " + e.getMessage());
        }
        return out;
    }

    public boolean isAgileServiceUp() { return agileClient.healthCheck(); }

    public int getMaxItems() { return maxItems; }
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd ~/git/plm-field-tracker && mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/java/com/sandisk/plm/tracker/model/BomRaceRun.java \
        src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java && \
git commit -m "$(cat <<'EOF'
feat(bomrace): add BomRaceRun model + BomRaceService scaffolding

Holds the per-runId race state and exposes the random-items-with-BOMs
sample query (capped at app.bomrace.max-items) and Agile-service health
pre-flight. Lazy sweep of expired runs (no @Scheduled — that subsystem
is gated off locally). Comparison + orchestration land in the next two tasks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B3: Match logic (TDD)

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java` (add comparison helpers + result types)
- Create: `~/git/plm-field-tracker/src/test/java/com/sandisk/plm/tracker/service/BomRaceMatchTest.java`

- [ ] **Step 1: Write the failing test**

```java
// ~/git/plm-field-tracker/src/test/java/com/sandisk/plm/tracker/service/BomRaceMatchTest.java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class BomRaceMatchTest {

    /** Toolkit BomResult shape — see model/BomResult.java in this project. */
    private BomResult tk(int level, String parent, String component, String qty) {
        BomResult b = new BomResult(level, parent, component, qty, "", "", "", "", "", "", "", "", "", "", "", "");
        return b;
    }

    private Map<String, Object> sdk(int level, String parent, String component, String qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level); m.put("parent", parent);
        m.put("component", component); m.put("qty", qty);
        return m;
    }

    @Test
    void setMatch_identicalSets_isOk() {
        List<BomResult> tk = Arrays.asList(tk(1, "P1", "C1", "2"), tk(1, "P1", "C2", "1"));
        List<Map<String,Object>> sdk = Arrays.asList(sdk(1, "P1", "C1", "2"), sdk(1, "P1", "C2", "1"));
        BomRaceService.MatchScore s = BomRaceService.computeSetMatch(tk, sdk);
        assertTrue(s.ok);
        assertTrue(s.onlyToolkit.isEmpty());
        assertTrue(s.onlySdk.isEmpty());
    }

    @Test
    void setMatch_sdkMissingOnePart_surfacesDiff() {
        List<BomResult> tk = Arrays.asList(tk(1, "P1", "C1", "2"), tk(1, "P1", "C2", "1"));
        List<Map<String,Object>> sdk = Collections.singletonList(sdk(1, "P1", "C1", "2"));
        BomRaceService.MatchScore s = BomRaceService.computeSetMatch(tk, sdk);
        assertFalse(s.ok);
        assertEquals(Collections.singleton("C2"), s.onlyToolkit);
        assertTrue(s.onlySdk.isEmpty());
    }

    @Test
    void structuralMatch_qtyDiff_failsStructural_butSetStillOk() {
        List<BomResult> tk = Collections.singletonList(tk(1, "P1", "C1", "2"));
        List<Map<String,Object>> sdk = Collections.singletonList(sdk(1, "P1", "C1", "5"));
        assertTrue(BomRaceService.computeSetMatch(tk, sdk).ok);
        assertFalse(BomRaceService.computeStructuralMatch(tk, sdk).ok);
    }

    @Test
    void bothEmpty_matchesOk() {
        BomRaceService.MatchScore s = BomRaceService.computeSetMatch(
            Collections.emptyList(), Collections.emptyList());
        assertTrue(s.ok);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd ~/git/plm-field-tracker && \
mvn -q -Dtest=BomRaceMatchTest test
```

Expected: FAIL — `computeSetMatch` / `computeStructuralMatch` / `MatchScore` don't exist on `BomRaceService` yet.

- [ ] **Step 3: Implement the comparison helpers + types**

Append to `BomRaceService.java`:

```java
    public static class MatchScore {
        public boolean ok;
        public Set<String> onlyToolkit = new LinkedHashSet<>();
        public Set<String> onlySdk = new LinkedHashSet<>();
    }

    /** Set-match: forgiving headline. "Did both find the same set of distinct component part numbers?" */
    public static MatchScore computeSetMatch(List<com.sandisk.plm.tracker.model.BomResult> toolkit,
                                             List<Map<String, Object>> sdk) {
        Set<String> tkSet = new LinkedHashSet<>();
        for (com.sandisk.plm.tracker.model.BomResult b : toolkit) {
            if (b.getComponent() != null && !b.getComponent().isEmpty())
                tkSet.add(b.getComponent().trim().toUpperCase());
        }
        Set<String> sdkSet = new LinkedHashSet<>();
        for (Map<String, Object> r : sdk) {
            Object c = r.get("component");
            if (c != null && !c.toString().isEmpty())
                sdkSet.add(c.toString().trim().toUpperCase());
        }
        MatchScore s = new MatchScore();
        s.onlyToolkit = new LinkedHashSet<>(tkSet); s.onlyToolkit.removeAll(sdkSet);
        s.onlySdk     = new LinkedHashSet<>(sdkSet); s.onlySdk.removeAll(tkSet);
        s.ok = s.onlyToolkit.isEmpty() && s.onlySdk.isEmpty();
        return s;
    }

    /** Structural-match: stricter. "Did both agree on every parent->child edge AND quantity?" */
    public static MatchScore computeStructuralMatch(List<com.sandisk.plm.tracker.model.BomResult> toolkit,
                                                    List<Map<String, Object>> sdk) {
        Set<String> tkEdges = new LinkedHashSet<>();
        for (com.sandisk.plm.tracker.model.BomResult b : toolkit) {
            tkEdges.add(edgeKey(b.getParent(), b.getComponent(), b.getQty()));
        }
        Set<String> sdkEdges = new LinkedHashSet<>();
        for (Map<String, Object> r : sdk) {
            sdkEdges.add(edgeKey(s(r.get("parent")), s(r.get("component")), s(r.get("qty"))));
        }
        MatchScore s = new MatchScore();
        s.onlyToolkit = new LinkedHashSet<>(tkEdges); s.onlyToolkit.removeAll(sdkEdges);
        s.onlySdk     = new LinkedHashSet<>(sdkEdges); s.onlySdk.removeAll(tkEdges);
        s.ok = s.onlyToolkit.isEmpty() && s.onlySdk.isEmpty();
        return s;
    }

    private static String edgeKey(String parent, String child, String qty) {
        return up(parent) + "→" + up(child) + "@" + (qty == null ? "" : qty.trim());
    }
    private static String up(String v) { return v == null ? "" : v.trim().toUpperCase(); }
    private static String s(Object o)  { return o == null ? "" : o.toString(); }
```

Note: this references `BomResult` getters. Verify they exist by reading `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/model/BomResult.java`. If the getters are named differently (e.g. `component` is a public field, not `getComponent()`), adjust accordingly — same for `getParent()`, `getQty()`.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd ~/git/plm-field-tracker && \
mvn -q -Dtest=BomRaceMatchTest test
```

Expected: 4 tests passed.

- [ ] **Step 5: Commit**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java \
        src/test/java/com/sandisk/plm/tracker/service/BomRaceMatchTest.java && \
git commit -m "$(cat <<'EOF'
feat(bomrace): set-match + structural-match comparison logic

Set-match is the headline (distinct component part numbers, forgiving on
qty/refdes/order). Structural-match is the sub-metric (parent->child + qty,
strict). Both surface drift as onlyToolkit/onlySdk sets so the UI can show
which side has what.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B4: Race orchestration (CompletableFuture + SSE + timeouts)

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java` (add `startRace`, SSE emission, lane runners)

- [ ] **Step 1: Add the orchestration to BomRaceService**

Append to `BomRaceService.java`:

```java
    private final java.util.concurrent.ExecutorService laneExec =
        java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "bom-race-lane");
            t.setDaemon(true);
            return t;
        });

    /** Create a run, register an emitter, fire both lanes from the same instant.
     *  Returns the SseEmitter for the controller to hand back to the browser. */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter startRace(
            String runId, List<String> items, int maxDepth) {

        sweepExpired();
        long t0 = System.nanoTime();
        BomRaceRun run = new BomRaceRun(runId, items, maxDepth, t0);
        run.emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(raceTimeoutMs + 30_000L);
        runs.put(runId, run);

        run.emitter.onCompletion(() -> cancel(run));
        run.emitter.onTimeout(()    -> cancel(run));
        run.emitter.onError(err     -> cancel(run));

        emit(run, "race-start", mapOf("runId", runId, "items", items, "startedAt", System.currentTimeMillis()));

        run.toolkitFuture = java.util.concurrent.CompletableFuture.runAsync(() -> runToolkitLane(run), laneExec);
        run.sdkFuture     = java.util.concurrent.CompletableFuture.runAsync(() -> runSdkLane(run),     laneExec);

        java.util.concurrent.CompletableFuture
            .allOf(run.toolkitFuture, run.sdkFuture)
            .orTimeout(raceTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .whenComplete((v, err) -> finishRace(run, err));

        return run.emitter;
    }

    private void runToolkitLane(BomRaceRun run) {
        long t = System.nanoTime();
        try {
            // The batched path is all-or-nothing — no per-item progress to honestly emit.
            // The UI just shows a spinner on the toolkit lane until lane-done flips it to 100%.
            List<com.sandisk.plm.tracker.model.BomResult> rows =
                bomDataService.explodeMultiple(run.items, run.maxDepth);
            long ms = (System.nanoTime() - t) / 1_000_000L;
            run.toolkitRows = rows;
            emit(run, "lane-done", mapOf("lane", "toolkit", "totalMs", ms,
                "totalRows", rows.size(), "errorCount", 0));
        } catch (Exception ex) {
            long ms = (System.nanoTime() - t) / 1_000_000L;
            logger.warning("[BOM_RACE] toolkit lane failed: " + ex.getMessage());
            emit(run, "lane-done", mapOf("lane", "toolkit", "totalMs", ms,
                "totalRows", 0, "errorCount", 1, "error", ex.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void runSdkLane(BomRaceRun run) {
        long t = System.nanoTime();
        try {
            // Stream item-start events so the UI shows live progress on the SDK side.
            for (String item : run.items) {
                long elapsed = (System.nanoTime() - run.startedAtNanos) / 1_000_000L;
                emit(run, "item-start", mapOf("lane", "sdk", "item", item, "t", elapsed));
                // The actual call is batched at the service, but we surface progress per item
                // by calling explode() one-at-a-time via the client.
                Map<String, Object> resp = agileClient.explode(java.util.Collections.singletonList(item), run.maxDepth);
                List<Map<String, Object>> perItem = (List<Map<String, Object>>) resp.get("perItem");
                if (perItem != null && !perItem.isEmpty()) {
                    Map<String, Object> rec = perItem.get(0);
                    if (rec.get("error") != null) {
                        emit(run, "item-error", mapOf("lane", "sdk", "item", item,
                            "errorMs", rec.get("durationMs"), "message", rec.get("error")));
                    } else {
                        List<Map<String, Object>> rows = (List<Map<String, Object>>) rec.get("rows");
                        emit(run, "item-done", mapOf("lane", "sdk", "item", item,
                            "rows", rows == null ? 0 : rows.size(), "durationMs", rec.get("durationMs")));
                        if (rows != null) run.sdkRows.addAll(rows);
                    }
                }
            }
            long ms = (System.nanoTime() - t) / 1_000_000L;
            emit(run, "lane-done", mapOf("lane", "sdk", "totalMs", ms,
                "totalRows", run.sdkRows.size(), "errorCount", run.sdkErrorCount));
        } catch (Exception ex) {
            long ms = (System.nanoTime() - t) / 1_000_000L;
            logger.warning("[BOM_RACE] sdk lane failed: " + ex.getMessage());
            emit(run, "lane-done", mapOf("lane", "sdk", "totalMs", ms,
                "totalRows", 0, "errorCount", 1, "error", ex.getMessage()));
        }
    }

    private void finishRace(BomRaceRun run, Throwable err) {
        long totalMs = (System.nanoTime() - run.startedAtNanos) / 1_000_000L;
        if (err != null) {
            logger.warning("[BOM_RACE] runId=" + run.runId + " ended with error: " + err.getMessage());
        }
        // Find the per-lane totals from the events we already emitted — recompute from collected rows.
        long toolkitMs = run.toolkitTotalMs > 0 ? run.toolkitTotalMs : totalMs;
        long sdkMs     = run.sdkTotalMs     > 0 ? run.sdkTotalMs     : totalMs;
        double speedup = toolkitMs == 0 ? 0.0 : (double) sdkMs / (double) toolkitMs;

        MatchScore setM = computeSetMatch(run.toolkitRows, run.sdkRows);
        MatchScore strM = computeStructuralMatch(run.toolkitRows, run.sdkRows);

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("toolkitMs", toolkitMs);
        done.put("sdkMs", sdkMs);
        done.put("speedup", speedup);
        done.put("setMatch", mapOf("ok", setM.ok, "onlyToolkit", setM.onlyToolkit, "onlySdk", setM.onlySdk));
        done.put("structuralMatch", mapOf("ok", strM.ok,
            "onlyToolkit", strM.onlyToolkit, "onlySdk", strM.onlySdk));
        emit(run, "race-done", done);
        try { run.emitter.complete(); } catch (Exception ignored) {}
        logger.info("[BOM_RACE] runId=" + run.runId + " finished · toolkit=" + toolkitMs +
            "ms · sdk=" + sdkMs + "ms · setMatch=" + (setM.ok ? "ok" : "diff") +
            " · structMatch=" + (strM.ok ? "ok" : "diff"));
    }

    private void cancel(BomRaceRun run) {
        if (run.toolkitFuture != null) run.toolkitFuture.cancel(true);
        if (run.sdkFuture != null) run.sdkFuture.cancel(true);
        runs.remove(run.runId);
    }

    private void emit(BomRaceRun run, String event, Object data) {
        try {
            run.emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                .name(event).data(data));
            // Track lane totals as they fly by, for finishRace.
            if ("lane-done".equals(event) && data instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) data;
                if ("toolkit".equals(m.get("lane"))) run.toolkitTotalMs = ((Number) m.get("totalMs")).longValue();
                else if ("sdk".equals(m.get("lane"))) run.sdkTotalMs = ((Number) m.get("totalMs")).longValue();
            }
        } catch (java.io.IOException ignored) {
            // Browser disconnected. cancel() will fire via emitter callbacks.
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
```

Add these fields to `BomRaceRun.java` (Task B2 file) — the orchestrator needs them for finishRace bookkeeping:

```java
    public volatile java.util.List<com.sandisk.plm.tracker.model.BomResult> toolkitRows = new java.util.ArrayList<>();
    public volatile java.util.List<java.util.Map<String, Object>> sdkRows = new java.util.ArrayList<>();
    public volatile long toolkitTotalMs = 0L;
    public volatile long sdkTotalMs = 0L;
    public volatile int sdkErrorCount = 0;
```

- [ ] **Step 2: Run all unit tests to confirm no regression**

```bash
cd ~/git/plm-field-tracker && mvn -q test
```

Expected: existing tests + the 4 from Task B3 all pass. Compile warnings about `orTimeout` (Java 9+) — fall back to a `ScheduledExecutorService.schedule(() -> future.cancel(true), …)` wrapper if compile fails on Java 8 (this project's target).

> **Java 8 caveat:** `CompletableFuture.orTimeout()` is Java 9+. If `mvn compile` fails on this line, replace with:
> ```java
> static java.util.concurrent.ScheduledExecutorService TIMER =
>     java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
>         Thread t = new Thread(r, "bom-race-timeout"); t.setDaemon(true); return t; });
> // ... and instead of .orTimeout(...).whenComplete(...):
> java.util.concurrent.CompletableFuture<Void> all = java.util.concurrent.CompletableFuture
>     .allOf(run.toolkitFuture, run.sdkFuture);
> TIMER.schedule(() -> all.completeExceptionally(
>     new java.util.concurrent.TimeoutException("race-timeout")), raceTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
> all.whenComplete((v, err) -> finishRace(run, err));
> ```

- [ ] **Step 3: Commit**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java \
        src/main/java/com/sandisk/plm/tracker/model/BomRaceRun.java && \
git commit -m "$(cat <<'EOF'
feat(bomrace): orchestrator fires both lanes from a shared start instant

Toolkit lane calls explodeMultiple() once (production batched path);
SDK lane iterates items sequentially via the HTTP client so per-item
SSE progress lands live. Race-wide wall-clock cap closes the emitter
even if a lane hangs. Browser disconnect cancels both futures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B5: BomRaceController

**Files:**
- Create: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/controller/BomRaceController.java`

- [ ] **Step 1: Create the controller**

```java
// ~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/controller/BomRaceController.java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.BomRaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/bomrace")
public class BomRaceController {

    private static final Logger logger = Logger.getLogger(BomRaceController.class.getName());

    @Autowired private BomRaceService raceService;
    @Autowired private ActivityLogger activityLogger;

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));

        if (!raceService.isAgileServiceUp()) {
            return ResponseEntity.status(503).body(mapOf(
                "ok", false, "reason", "agile-service-unreachable",
                "message", "Agile lookup service is not reachable. The race can't start without it."));
        }

        String mode = String.valueOf(body.getOrDefault("mode", "random"));
        int n = ((Number) body.getOrDefault("n", raceService.getMaxItems())).intValue();
        int maxDepth = ((Number) body.getOrDefault("maxDepth", 20)).intValue();

        List<String> items;
        if ("upload".equals(mode)) {
            @SuppressWarnings("unchecked")
            List<String> up = (List<String>) body.getOrDefault("items", Collections.emptyList());
            items = up.subList(0, Math.min(up.size(), raceService.getMaxItems()));
        } else {
            items = raceService.sampleRandomItemsWithBoms(n);
        }
        if (items.isEmpty()) {
            return ResponseEntity.status(400).body(err("No items to race."));
        }

        String runId = UUID.randomUUID().toString();
        // Pre-create the emitter on the start call so the browser can open the stream immediately.
        // The actual SSE emission happens on the /stream call below.
        // We store the resolved item list and runId; /stream creates the emitter and kicks off lanes.
        raceService.preStage(runId, items, maxDepth);

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "BOM_RACE_START", "runId=" + runId + " mode=" + mode + " n=" + items.size());

        return ResponseEntity.ok(mapOf("runId", runId, "items", items));
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) {
            SseEmitter em = new SseEmitter(5_000L);
            try { em.send(SseEmitter.event().name("error").data(err("Admin access required."))); }
            catch (Exception ignored) {}
            em.complete();
            return em;
        }
        return raceService.startStagedRace(runId);
    }

    private static boolean isAdmin(HttpSession s) {
        Boolean v = (Boolean) s.getAttribute("isPlmAdmin");
        return v != null && v;
    }
    private static String s(HttpSession s, String k) {
        Object v = s.getAttribute(k); return v == null ? "" : v.toString();
    }
    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("error", msg); return m;
    }
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
```

- [ ] **Step 2: Add the `preStage` / `startStagedRace` pair to BomRaceService**

The controller separates "resolve items" (POST /start) from "open stream and fire lanes" (GET /stream) so the browser can `EventSource(...)` cleanly. Add to `BomRaceService.java`:

```java
    private final Map<String, List<String>> staged = new ConcurrentHashMap<>();
    private final Map<String, Integer> stagedDepth = new ConcurrentHashMap<>();

    public void preStage(String runId, List<String> items, int maxDepth) {
        staged.put(runId, items);
        stagedDepth.put(runId, maxDepth);
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter startStagedRace(String runId) {
        List<String> items = staged.remove(runId);
        Integer depth = stagedDepth.remove(runId);
        if (items == null) {
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter em =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(5_000L);
            try { em.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                .name("error").data(java.util.Collections.singletonMap("error", "unknown runId"))); }
            catch (Exception ignored) {}
            em.complete();
            return em;
        }
        return startRace(runId, items, depth == null ? 20 : depth);
    }
```

- [ ] **Step 3: Verify build + boot locally**

```bash
cd ~/git/plm-field-tracker && mvn -q -DskipTests package
```

Expected: BUILD SUCCESS. Then:

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
```

Restart the local toolkit (`pkill -f plm-field-tracker; cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties &`), then probe:

```bash
curl -sS -c /tmp/c.txt -H 'Content-Type: application/json' \
  -d '{"username":"plmadmin","password":"newworld"}' \
  http://localhost:8090/api/auth/login

curl -sS -b /tmp/c.txt -H 'Content-Type: application/json' \
  -d '{"mode":"random","n":5}' \
  http://localhost:8090/api/bomrace/start
```

Expected: `503 {ok:false, reason:"agile-service-unreachable", message:"Agile lookup service is not reachable..."}` (since local can't reach Agile). **This is the success case for local** — proves the pre-flight gate works.

- [ ] **Step 4: Commit**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/java/com/sandisk/plm/tracker/controller/BomRaceController.java \
        src/main/java/com/sandisk/plm/tracker/service/BomRaceService.java && \
git commit -m "$(cat <<'EOF'
feat(bomrace): admin-gated controller — POST /start + GET /stream

Pre-flight returns 503 when plm-agile-service is down (the local Mac
case), so the UI can show a clean "Agile service unavailable" callout
instead of half-starting a race. /start resolves items + stages the run;
/stream opens the SSE channel and kicks off both lanes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C — plm-field-tracker frontend

### Task C1: Labs tab + BOM Race markup in index.html

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/resources/static/index.html` (add Labs tab button + BOM Race screen)

- [ ] **Step 1: Add the Labs tab button to the top tab bar**

Open `~/git/plm-field-tracker/src/main/resources/static/index.html` and locate the top tab bar (search for `<button class="tab" id="tabEcnReport"`). Insert a new admin-only tab button immediately AFTER the ECN Report tab line:

```html
<button class="tab np-admin-only" id="tabLabs" onclick="switchTab('labs')" style="display:none;">&#129514; Labs</button>
```

- [ ] **Step 2: Add the Labs screen + BOM Race sub-tab markup**

Append this block at the end of `<body>` (immediately before the existing `<script>` tags), wrapped so it follows the project's existing tab-screen pattern:

```html
<!-- TAB: Labs (admin only) -->
<div id="screenLabs" class="screen" style="display:none;">
  <div style="border-bottom:1px solid #E8E6DF; margin-bottom:14px;">
    <button id="labsSubTabBomRace"
      onclick="switchTab('bomrace')"
      style="background:none; border:none; padding:10px 18px; font-size:13.5px; font-weight:600; color:#0F1720; border-bottom:2px solid #4a6fa5; cursor:pointer;">
      &#9889; BOM Race
    </button>
  </div>

  <div id="screenBomRace">
    <div style="padding:14px 16px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px;">
      <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
        <div role="tablist" style="display:inline-flex; border:1px solid #E8E6DF; border-radius:14px; overflow:hidden;">
          <button id="brModeRandom" type="button" onclick="brSetMode('random')"
            style="padding:5px 12px; font-size:12px; background:#2c3e50; color:#fff; font-weight:600; border:none; cursor:pointer;">Random</button>
          <button id="brModeUpload" type="button" onclick="brSetMode('upload')"
            style="padding:5px 12px; font-size:12px; background:transparent; color:#6B7280; border:none; cursor:pointer;">Upload</button>
        </div>
        <label style="font-size:12px; color:#374151;">N = <input id="brN" type="number" min="1" max="25" value="10" style="width:50px; padding:3px 6px; border:1px solid #E8E6DF; border-radius:4px;"></label>
        <input id="brFile" type="file" accept=".xlsx,.xls" style="display:none; font-size:12px;">
        <button id="brStart" onclick="brStart()" style="background:#4a6fa5; color:#fff; border:none; border-radius:4px; padding:6px 14px; font-size:12px; font-weight:600; cursor:pointer;">&#9889; Start race</button>
        <span id="brClock" style="margin-left:auto; font-family:'IBM Plex Mono',Consolas,monospace; font-size:13px; color:#6B7280;">Race time: 0:00</span>
      </div>
      <div id="brHelp" style="font-size:11px; color:#6B7280; margin-top:6px;">
        Each side runs the way it would in production. Toolkit batches via SQL. Agile SDK walks items live, sequentially (legacy SDK isn't thread-safe).
      </div>
    </div>

    <div id="brLanes" style="display:none; margin-top:14px; display:grid; grid-template-columns:1fr 1fr; gap:14px;">
      <div id="brLaneToolkit" style="background:#FAFAF7; border:1px solid #E8E6DF; border-left:3px solid #4a6fa5; border-radius:6px; padding:14px;">
        <div style="display:flex; justify-content:space-between; align-items:center; font-weight:600; color:#0F1720;">
          <span>&#x1F4BB; Toolkit</span>
          <span class="brLaneClock" style="font-family:'IBM Plex Mono',Consolas,monospace;">&mdash;</span>
        </div>
        <div style="font-size:11px; color:#6B7280;">Cached SQL · CONNECT BY (batched)</div>
        <div class="brBar" style="height:14px; background:#E8E6DF; border-radius:7px; overflow:hidden; margin-top:10px;">
          <div class="brBarFill" style="height:100%; width:0; background:#4a6fa5; transition:width .25s;"></div>
        </div>
        <div class="brLaneStat" style="display:flex; gap:14px; margin-top:6px; font-size:11px; color:#6B7280;">
          <span><b class="brLaneCount">0/0</b></span>
          <span><b class="brLaneRows">0</b> rows</span>
        </div>
      </div>
      <div id="brLaneSdk" style="background:#FAFAF7; border:1px solid #E8E6DF; border-left:3px solid #C7801B; border-radius:6px; padding:14px;">
        <div style="display:flex; justify-content:space-between; align-items:center; font-weight:600; color:#0F1720;">
          <span>&#x1F310; Agile SDK</span>
          <span class="brLaneClock" style="font-family:'IBM Plex Mono',Consolas,monospace;">&mdash;</span>
        </div>
        <div style="font-size:11px; color:#6B7280;">Live walk via plm-agile-service</div>
        <div class="brBar" style="height:14px; background:#E8E6DF; border-radius:7px; overflow:hidden; margin-top:10px;">
          <div class="brBarFill" style="height:100%; width:0; background:#C7801B; transition:width .25s;"></div>
        </div>
        <div class="brLaneStat" style="display:flex; gap:14px; margin-top:6px; font-size:11px; color:#6B7280;">
          <span><b class="brLaneCount">0/0</b></span>
          <span class="brLaneActive">&mdash;</span>
        </div>
      </div>
    </div>

    <div id="brScoreboard" style="display:none; margin-top:14px; padding:14px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px;">
      <div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:1px; margin-bottom:10px;">Final scoreboard</div>
      <div id="brScoreBars"></div>
      <div id="brScoreCallout" style="margin-top:10px; padding:8px 12px; border-left:3px solid #1F8A4C; background:#e8f5e9; border-radius:0 4px 4px 0; font-size:12px; color:#155724;"></div>
      <button onclick="brToggleDetails()" style="margin-top:10px; background:none; border:none; color:#4a6fa5; font-size:12px; font-weight:600; cursor:pointer;">▸ Show details</button>
      <div id="brDetails" style="display:none; margin-top:10px; font-size:12px; color:#374151;"></div>
    </div>

    <div id="brError" style="display:none; margin-top:14px; padding:12px; border-left:3px solid #B8342B; background:#fdeaea; border-radius:0 4px 4px 0; font-size:12px; color:#721c24;"></div>
  </div>
</div>
<script src="bomrace.js"></script>
```

- [ ] **Step 3: Confirm tab bar markup is consistent**

```bash
grep -n 'tabLabs\|tabEcnReport\|switchTab(.labs.)' ~/git/plm-field-tracker/src/main/resources/static/index.html | head -8
```

Expected: see `tabEcnReport` line, then `tabLabs` line right after, both `np-admin-only` (only `tabLabs` is hidden by default).

- [ ] **Step 4: Commit**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/resources/static/index.html && \
git commit -m "$(cat <<'EOF'
feat(bomrace): add admin-only Labs tab with BOM Race screen markup

Top-nav tab gated by np-admin-only (server-side enforced too). Sub-nav
houses the BOM Race screen — input row, twin lane cards, and the final
scoreboard area. JS wiring lands in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task C2: Tab routing + BOM Race JS skeleton

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/resources/static/app.js` (add `'labs'` and `'bomrace'` cases to `switchTab`)
- Create: `~/git/plm-field-tracker/src/main/resources/static/bomrace.js`

- [ ] **Step 1: Find the switchTab function**

```bash
grep -n 'function switchTab\|case .ecnreport' ~/git/plm-field-tracker/src/main/resources/static/app.js | head -6
```

Note the line ranges where the existing `case 'ecnreport':` (or similar) lives so the new cases land in the same `switch`.

- [ ] **Step 2: Add Labs + BOM Race routing**

Inside `switchTab(tab)` in `app.js`, alongside the other tab cases (e.g. near the `case 'ecnreport':` block), add:

```javascript
        case 'labs':
        case 'bomrace':
            document.getElementById('screenLabs').style.display = 'block';
            document.getElementById('tabLabs').classList.add('active');
            // Initialize the Race screen on first visit
            if (typeof brOnTabOpen === 'function') brOnTabOpen();
            break;
```

Also add `'screenLabs'` to whatever screen-hide loop already runs (search for `screenItems`/`screenBom` to find the pattern — every other tab adds itself to the same hide list).

- [ ] **Step 3: Create bomrace.js skeleton**

```javascript
// ~/git/plm-field-tracker/src/main/resources/static/bomrace.js
(function () {
    let brMode = 'random';
    let currentRunId = null;
    let currentEvtSrc = null;
    let raceClockInterval = null;
    let raceStartedAt = 0;

    window.brSetMode = function (mode) {
        brMode = mode;
        document.getElementById('brModeRandom').style.background = mode === 'random' ? '#2c3e50' : 'transparent';
        document.getElementById('brModeRandom').style.color      = mode === 'random' ? '#fff' : '#6B7280';
        document.getElementById('brModeUpload').style.background = mode === 'upload' ? '#2c3e50' : 'transparent';
        document.getElementById('brModeUpload').style.color      = mode === 'upload' ? '#fff' : '#6B7280';
        document.getElementById('brFile').style.display = mode === 'upload' ? 'inline-block' : 'none';
        document.getElementById('brN').style.display    = mode === 'random' ? 'inline-block' : 'none';
    };

    window.brOnTabOpen = function () {
        // Reset transient UI state when the tab is opened
        document.getElementById('brError').style.display = 'none';
        document.getElementById('brScoreboard').style.display = 'none';
    };

    window.brStart = async function () {
        // Shut down any prior race cleanly
        if (currentEvtSrc) { try { currentEvtSrc.close(); } catch (e) {} currentEvtSrc = null; }
        if (raceClockInterval) { clearInterval(raceClockInterval); raceClockInterval = null; }
        document.getElementById('brError').style.display = 'none';
        document.getElementById('brScoreboard').style.display = 'none';

        let payload = { mode: brMode, n: parseInt(document.getElementById('brN').value, 10) || 10 };

        if (brMode === 'upload') {
            const f = document.getElementById('brFile').files[0];
            if (!f) { brShowError('Pick an Excel file first.'); return; }
            // Simplest path: re-use the existing /api/upload/probe endpoint to extract the item column.
            // For v1 we keep it minimal — call probe, then send just the parsed item list.
            const form = new FormData(); form.append('file', f);
            const probe = await fetch('/api/upload/probe', { method: 'POST', body: form }).then(r => r.json());
            if (probe.tooLarge) { brShowError(probe.message); return; }
            // Server returns the column it picked; client doesn't yet have the row values without a second
            // request. For simplicity in v1, re-upload through a tiny helper — or for now, ask the user
            // to paste item numbers. (NOTE: full upload-mode plumbing is a follow-up; random covers the demo.)
            brShowError('Upload mode not yet wired in v1 — use Random for the showcase. (Follow-up ticket.)');
            return;
        }

        const resp = await fetch('/api/bomrace/start', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (!resp.ok) {
            const j = await resp.json().catch(() => ({}));
            brShowError(j.message || j.error || ('HTTP ' + resp.status));
            return;
        }
        const { runId, items } = await resp.json();
        currentRunId = runId;
        brResetLanes(items.length);
        document.getElementById('brLanes').style.display = 'grid';

        raceStartedAt = Date.now();
        raceClockInterval = setInterval(() => {
            const t = Math.floor((Date.now() - raceStartedAt) / 1000);
            document.getElementById('brClock').textContent =
                'Race time: ' + Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0');
        }, 200);

        currentEvtSrc = new EventSource('/api/bomrace/' + runId + '/stream');
        currentEvtSrc.addEventListener('race-start',  (e) => brOnRaceStart(JSON.parse(e.data)));
        currentEvtSrc.addEventListener('item-start',  (e) => brOnItemStart(JSON.parse(e.data)));
        currentEvtSrc.addEventListener('item-done',   (e) => brOnItemDone(JSON.parse(e.data)));
        currentEvtSrc.addEventListener('item-error',  (e) => brOnItemError(JSON.parse(e.data)));
        currentEvtSrc.addEventListener('lane-done',   (e) => brOnLaneDone(JSON.parse(e.data)));
        currentEvtSrc.addEventListener('race-done',   (e) => brOnRaceDone(JSON.parse(e.data)));
        currentEvtSrc.addEventListener('error',       (e) => brShowError('Stream error.'));
    };

    function brShowError(msg) {
        const el = document.getElementById('brError');
        el.textContent = msg;
        el.style.display = 'block';
    }

    // Stubs — implemented in Task C3
    function brResetLanes(n) {}
    function brOnRaceStart(d) {}
    function brOnItemStart(d) {}
    function brOnItemDone(d) {}
    function brOnItemError(d) {}
    function brOnLaneDone(d) {}
    function brOnRaceDone(d) {}
    window.brToggleDetails = function () {};
})();
```

- [ ] **Step 4: Restart local + verify the tab is clickable for an admin (and hidden for a non-admin)**

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
pkill -f plm-field-tracker || true
( cd ~/Documents/plm-toolkit\ 2 && nohup java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties > /tmp/toolkit.log 2>&1 & )
sleep 6
curl -sS -c /tmp/c.txt -H 'Content-Type: application/json' \
  -d '{"username":"plmadmin","password":"newworld"}' http://localhost:8090/api/auth/login >/dev/null
echo "Toolkit restarted. Open http://localhost:8090 in browser, log in as plmadmin / newworld."
```

> Note: this assumes `mvn package` ran in B5 — if not, run it first. The Maven invocation deliberately stays out of this step so the tester can sanity-check the JAR before flipping it.

In the browser: confirm the **🧪 Labs** tab appears (admin-only), clicking it shows the BOM Race input row, clicking **Start race** with default N=10 surfaces the "Agile lookup service is not reachable" error inside the screen (since local can't reach Agile). That's success criteria.

- [ ] **Step 5: Commit**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/resources/static/app.js src/main/resources/static/bomrace.js && \
git commit -m "$(cat <<'EOF'
feat(bomrace): wire Labs tab routing + bomrace.js skeleton

switchTab routes 'labs' and 'bomrace' to the same Labs screen. bomrace.js
handles input form, mode toggle, and EventSource lifecycle (open + close).
Lane render + scoreboard land in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task C3: Lane render + scoreboard + diff details

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/resources/static/bomrace.js` (replace the stubs from C2 with full implementations)

- [ ] **Step 1: Replace the stub functions with the full implementations**

In `bomrace.js`, replace the entire `// Stubs — implemented in Task C3` block down through `window.brToggleDetails = function () {};` with:

```javascript
    // ---- Lane state ----
    let totalItems = 0;
    let toolkitDone = 0, sdkDone = 0;
    let toolkitRows = 0, sdkRows = 0;
    let perItem = { toolkit: {}, sdk: {} };

    function brResetLanes(n) {
        totalItems = n;
        toolkitDone = 0; sdkDone = 0;
        toolkitRows = 0; sdkRows = 0;
        perItem = { toolkit: {}, sdk: {} };
        ['brLaneToolkit', 'brLaneSdk'].forEach((id) => {
            const lane = document.getElementById(id);
            lane.querySelector('.brBarFill').style.width = '0%';
            lane.querySelector('.brBarFill').style.background = id === 'brLaneToolkit' ? '#4a6fa5' : '#C7801B';
            lane.querySelector('.brLaneCount').textContent = '0/' + n;
            lane.querySelector('.brLaneRows').textContent = '0';
            lane.querySelector('.brLaneClock').textContent = '0.0s';
            const active = lane.querySelector('.brLaneActive');
            if (active) active.textContent = '—';
        });
    }

    function brOnRaceStart(d) {
        // race-start arrives once both lanes are submitted; just confirm UI is in race state
    }

    function brOnItemStart(d) {
        const lane = d.lane === 'toolkit' ? document.getElementById('brLaneToolkit')
                                          : document.getElementById('brLaneSdk');
        const active = lane.querySelector('.brLaneActive');
        if (active) active.textContent = d.item;
    }

    function brOnItemDone(d) {
        perItem[d.lane][d.item] = { rows: d.rows, durationMs: d.durationMs };
        if (d.lane === 'toolkit') { toolkitDone++; toolkitRows += (d.rows || 0); }
        else                      { sdkDone++;     sdkRows     += (d.rows || 0); }
        const lane = d.lane === 'toolkit' ? document.getElementById('brLaneToolkit')
                                          : document.getElementById('brLaneSdk');
        const done = d.lane === 'toolkit' ? toolkitDone : sdkDone;
        const rows = d.lane === 'toolkit' ? toolkitRows : sdkRows;
        lane.querySelector('.brBarFill').style.width = ((done / totalItems) * 100) + '%';
        lane.querySelector('.brLaneCount').textContent = done + '/' + totalItems;
        lane.querySelector('.brLaneRows').textContent = rows;
    }

    function brOnItemError(d) {
        perItem[d.lane][d.item] = { error: d.message, durationMs: d.errorMs };
        if (d.lane === 'toolkit') toolkitDone++; else sdkDone++;
        const lane = d.lane === 'toolkit' ? document.getElementById('brLaneToolkit')
                                          : document.getElementById('brLaneSdk');
        const done = d.lane === 'toolkit' ? toolkitDone : sdkDone;
        lane.querySelector('.brBarFill').style.width = ((done / totalItems) * 100) + '%';
        lane.querySelector('.brBarFill').style.background = '#B8342B';
        lane.querySelector('.brLaneCount').textContent = done + '/' + totalItems;
    }

    function brOnLaneDone(d) {
        const lane = d.lane === 'toolkit' ? document.getElementById('brLaneToolkit')
                                          : document.getElementById('brLaneSdk');
        lane.querySelector('.brLaneClock').textContent = (d.totalMs / 1000).toFixed(1) + 's';
        // Force the bar to 100% on completion (the toolkit lane jumps because the batched
        // call returns all items at once).
        lane.querySelector('.brBarFill').style.width = '100%';
        lane.querySelector('.brLaneCount').textContent = totalItems + '/' + totalItems;
        if (d.lane === 'toolkit') lane.querySelector('.brLaneRows').textContent = d.totalRows || 0;
        else                      lane.querySelector('.brLaneRows').textContent = d.totalRows || 0;
        const active = lane.querySelector('.brLaneActive');
        if (active) active.textContent = '✓ done';
    }

    function brOnRaceDone(d) {
        if (raceClockInterval) { clearInterval(raceClockInterval); raceClockInterval = null; }
        if (currentEvtSrc) { try { currentEvtSrc.close(); } catch (e) {} currentEvtSrc = null; }

        const max = Math.max(d.toolkitMs, d.sdkMs) || 1;
        const tkPct = Math.max(2, (d.toolkitMs / max) * 100);
        const sdkPct = Math.max(2, (d.sdkMs / max) * 100);
        document.getElementById('brScoreBars').innerHTML = `
          <div style="display:flex; align-items:center; gap:10px; margin:6px 0; font-size:12px;">
            <span style="width:90px; font-weight:600;">Toolkit</span>
            <div style="flex:1; height:18px; background:#fff; border:1px solid #E8E6DF; border-radius:3px; overflow:hidden;">
              <div style="height:100%; width:${tkPct}%; background:#4a6fa5; color:#fff; padding-left:8px; font-family:'IBM Plex Mono',Consolas,monospace; font-size:11px; line-height:18px;">${(d.toolkitMs/1000).toFixed(1)}s</div>
            </div>
          </div>
          <div style="display:flex; align-items:center; gap:10px; margin:6px 0; font-size:12px;">
            <span style="width:90px; font-weight:600;">Agile SDK</span>
            <div style="flex:1; height:18px; background:#fff; border:1px solid #E8E6DF; border-radius:3px; overflow:hidden;">
              <div style="height:100%; width:${sdkPct}%; background:#C7801B; color:#fff; padding-left:8px; font-family:'IBM Plex Mono',Consolas,monospace; font-size:11px; line-height:18px;">${(d.sdkMs/1000).toFixed(1)}s</div>
            </div>
          </div>`;

        const speedupTxt = d.speedup >= 1
            ? (d.speedup.toFixed(1) + '× faster')
            : ((1 / d.speedup).toFixed(1) + '× slower');
        const set = d.setMatch, str = d.structuralMatch;
        const setOk  = set.ok  ? '✓' : '⚠';
        const strOk  = str.ok  ? '✓' : '⚠';
        const callout = document.getElementById('brScoreCallout');
        const allOk = set.ok && str.ok;
        callout.style.borderLeftColor = allOk ? '#1F8A4C' : '#C7801B';
        callout.style.background      = allOk ? '#e8f5e9' : '#fff8e1';
        callout.style.color           = allOk ? '#155724' : '#856404';
        callout.innerHTML = `${setOk} Set match ${set.ok ? 'ok' : (set.onlyToolkit.length + set.onlySdk.length) + ' diff'} · ${strOk} Structural match ${str.ok ? 'ok' : (str.onlyToolkit.length + str.onlySdk.length) + ' diff'} · <strong>${speedupTxt}</strong>`;

        // Build the details block
        const det = document.getElementById('brDetails');
        const onlyTk = (set.onlyToolkit || []).slice(0, 50);
        const onlySdk = (set.onlySdk || []).slice(0, 50);
        let html = '<div style="margin-top:6px;"><strong>Per-item timings (SDK lane)</strong><table style="width:100%; font-size:11px; border-collapse:collapse; margin-top:4px;">';
        html += '<tr style="background:#2c3e50; color:#fff;"><th style="padding:4px 8px; text-align:left;">Item</th><th style="padding:4px 8px; text-align:right;">SDK ms</th><th style="padding:4px 8px; text-align:right;">SDK rows</th></tr>';
        Object.keys(perItem.sdk).forEach((item, i) => {
            const r = perItem.sdk[item];
            const bg = i % 2 ? '#FAFAF7' : '#fff';
            html += `<tr style="background:${bg};"><td style="padding:4px 8px; border-bottom:1px solid #E8E6DF;">${item}</td><td style="padding:4px 8px; text-align:right; font-family:'IBM Plex Mono',Consolas,monospace; border-bottom:1px solid #E8E6DF;">${r.error ? 'ERR' : (r.durationMs ?? '—')}</td><td style="padding:4px 8px; text-align:right; font-family:'IBM Plex Mono',Consolas,monospace; border-bottom:1px solid #E8E6DF;">${r.error ? '—' : (r.rows ?? 0)}</td></tr>`;
        });
        html += '</table></div>';
        if (onlyTk.length || onlySdk.length) {
            html += '<div style="margin-top:14px;"><strong>Set-match diff</strong></div>';
            if (onlyTk.length) html += '<div style="margin-top:6px; font-size:11px;"><span style="color:#4a6fa5; font-weight:600;">Found by toolkit only:</span> ' + onlyTk.join(', ') + (set.onlyToolkit.length > 50 ? ' …' : '') + '</div>';
            if (onlySdk.length) html += '<div style="margin-top:6px; font-size:11px;"><span style="color:#C7801B; font-weight:600;">Found by SDK only:</span> ' + onlySdk.join(', ') + (set.onlySdk.length > 50 ? ' …' : '') + '</div>';
        }
        det.innerHTML = html;

        document.getElementById('brScoreboard').style.display = 'block';
    }

    window.brToggleDetails = function () {
        const det = document.getElementById('brDetails');
        det.style.display = det.style.display === 'none' ? 'block' : 'none';
    };
```

- [ ] **Step 2: Local sanity check (UI only — race won't reach scoreboard locally)**

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar 2>/dev/null
pkill -f plm-field-tracker || true
( cd ~/Documents/plm-toolkit\ 2 && nohup java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties > /tmp/toolkit.log 2>&1 & )
sleep 6
```

In the browser: log in as `plmadmin/newworld`, click **🧪 Labs**, click **⚡ Start race**. Expected: red error pill saying "Agile lookup service is not reachable…" — that's the local-no-Agile success case. JS console should be clean (no syntax errors).

- [ ] **Step 3: Commit**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/resources/static/bomrace.js && \
git commit -m "$(cat <<'EOF'
feat(bomrace): full lane render + scoreboard + diff details

EventSource handlers fill the toolkit/SDK lane bars in real time. On
race-done the scoreboard reveals proportional bars, a single callout
with set/structural match + Nx faster, and an expandable details block
with per-item SDK timings and the set-diff (parts found by one side only).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase D — Wrap and ship

### Task D1: Properties + What's New + dual-build + remote handoff

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/resources/application.properties`
- Modify: `~/git/plm-field-tracker/src/main/resources/static/whats-new.js`

- [ ] **Step 1: Add the four `app.bomrace.*` properties**

Open `~/git/plm-field-tracker/src/main/resources/application.properties` and append (under the existing `app.*` properties, near `agile.service.url`):

```properties
# BOM Race showcase (admin-only Labs tab)
app.bomrace.max-items=10
app.bomrace.sdk.item-timeout-ms=60000
app.bomrace.race-timeout-ms=300000
app.bomrace.run-ttl-ms=600000
```

- [ ] **Step 2: Add today's What's New entry**

Open `~/git/plm-field-tracker/src/main/resources/static/whats-new.js`, locate the `WHATS_NEW_RELEASES` array, and insert this entry as the **first element** (newest):

```javascript
    {
        date: '2026-05-10',
        title: 'BOM Race — concept showcase (admin only)',
        items: [
            { badge: 'new', admin: true, text: '<strong>New &ldquo;Labs&rdquo; tab</strong> with the first showcase: <strong>BOM Race</strong>. Picks 10 random items that have BOMs, races the toolkit\'s cached SQL explode against a live Agile SDK explode (via the existing <code>plm-agile-service</code>), and shows a side-by-side scoreboard with timings + set/structural match scores.' },
            { badge: 'new', admin: true, text: 'Race UI is split-lane (toolkit on the left, Agile SDK on the right) with a live race clock, per-item progress on the SDK side, and a final bar chart with a one-line callout (e.g. <em>&ldquo;Set match 10/10 · Structural match 9/10 · 12.4× faster&rdquo;</em>). Diff details expander reveals per-item SDK timings and any parts found by only one side.' },
            { badge: 'improve', admin: true, text: 'When <code>plm-agile-service</code> is unreachable (the local-Mac case), the race screen shows a clean &ldquo;Agile service unavailable — race can&rsquo;t start&rdquo; callout instead of half-starting and timing out.' }
        ]
    },
```

- [ ] **Step 3: Build both JARs**

```bash
cd ~/git/plm-agile-service && mvn -q -DskipTests package && \
ls -lh target/*.jar
```

Expected: BUILD SUCCESS. Note the JAR filename for the handoff message.

```bash
cd ~/git/plm-field-tracker && mvn -q test && mvn -q -DskipTests package && \
ls -lh target/plm-field-tracker-1.0.1.jar
```

Expected: tests pass (including the 4 BomRaceMatchTest cases), BUILD SUCCESS, JAR ~size unchanged from current.

- [ ] **Step 4: Copy the toolkit JAR to its standard locations**

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/ \
  && echo "✓ network share" || echo "✗ network share not mounted — let user know"
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar \
  && echo "✓ local copy"
```

- [ ] **Step 5: Commit the wrap**

```bash
cd ~/git/plm-field-tracker && \
git add src/main/resources/application.properties src/main/resources/static/whats-new.js && \
git commit -m "$(cat <<'EOF'
feat(bomrace): properties defaults + What's New entry

app.bomrace.max-items=10 (configurable, bump to 25 mid-demo if timings
are too similar). Per-item SDK timeout 60s, whole-race cap 5min,
runId TTL 10min. What's New entry summarises the showcase for admins.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: STOP — hand off to user for remote deploy + end-to-end test**

Tell the user (verbatim):

> **Both JARs built. Toolkit JAR is at `target/plm-field-tracker-1.0.1.jar` and copied to the network share + local. The plm-agile-service JAR is at `~/git/plm-agile-service/target/<jar>`. Local box can't reach Agile, so end-to-end happens on remote.**
>
> **Please:**
> 1. Deploy the new `plm-agile-service` JAR on the remote (replace the running one — it owns the new `/api/lookup/bom/{health,explode}` endpoints).
> 2. Deploy the new `plm-field-tracker` JAR on the remote.
> 3. Tell me the remote URL and I'll smoke-test there with `plmadmin`/`newworld`.
>
> **Smoke checklist for the remote test (I'll run these once you confirm):**
> - Login as `plmadmin`. Confirm the new **🧪 Labs** tab shows up; non-admin should not see it.
> - Click **Start race** with default N=10 random. Both lanes should run, scoreboard should appear.
> - Force an SDK error: tweak `N` to 1, edit the random pool to include a known bad part — should show "1 SDK error" without killing the race.
> - Force a timeout: temporarily set `app.bomrace.sdk.item-timeout-ms=1` server-side; restart; verify the race finishes with all-timeout-errors instead of hanging.

Wait for the user's go-ahead with a remote URL before running any smoke commands.

---

## Self-review — coverage map

Cross-checking each spec section against the plan:

| Spec section | Tasks |
|---|---|
| Goal / out of scope | Plan goal + decisions table |
| Decisions 1–9 | All baked into Tasks A2 (decision 1, 2, 7, 8) · B2/B5 (3, 5) · B3 (4) · C1 (5, 6) · D1 (9) |
| Architecture | Tasks A2 (SDK side), B4–B5 (orchestration + controller), C1–C3 (UI) |
| Random sample SQL | Task B2, step 2 |
| Excel upload | Task C2 step 3 (note: explicit deferral to follow-up; spec scope said "either upload or random" — random is the showcase path. Upload-mode wiring left as a stub with a clear "follow-up" message in the UI) |
| SSE event types | Tasks B4 (server-side emission) + C3 (client handlers) |
| Lane fairness (toolkit batched, SDK sequential) | Task B4 (`runToolkitLane` calls `explodeMultiple`; `runSdkLane` iterates one-at-a-time via the client) |
| File changes (both repos) | Mapped 1:1 in the File Structure section above |
| UI mock | Task C1 markup; Task C3 dynamic rendering |
| Error handling table | Pre-flight 503 in B5; per-item try/catch in A2 + B4; race timeout in B4; emitter callbacks in B4; multi-admin Map state in B2; cache drift surfacing in B3 (covered by `onlyToolkit`/`onlySdk`); lazy sweep in B2 |
| Observability log lines | B4 (`finishRace` logs the final summary); activity log entries in B5 |
| Configuration table | Task D1 step 1 |
| Testing strategy | Local checks woven into B5 step 3, C2 step 4, C3 step 2; remote handoff at D1 step 6 |

**Known scope decision:** Excel upload UI is stubbed in C2 step 3 with a "use Random for v1" message — full upload plumbing depends on a second `/api/upload/probe` call to also return parsed item values, which is a slightly larger change than the showcase needs. Random sample is the showcase path; upload is a follow-up. The spec listed both as input modes, so this is a soft scope cut, not a blind miss. Leaving here as an explicit follow-up rather than silently dropping it.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-10-bom-race-showcase.md`.**
