# Agent API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only, API-key-gated `/api/agent/*` gateway so the Atwork AI agent can discover (via a catalog) and retrieve SanDisk PLM business data and document files, with rate limiting that makes throttled/incomplete responses unmistakable.

**Architecture:** A registry-driven gateway. `AgentApiController` exposes thin wrapper endpoints that delegate in-process to existing service beans (the same beans the session-authed controllers use). A static `AgentEndpointRegistry` is the single source of truth for the `/catalog` discovery response and cannot drift from the mapped endpoints (a test enforces parity). Cross-cutting concerns (key check, rate limit, audit, error envelope) live in one `gate(...)`/`data(...)` helper pair, so each wrapper is a few lines. Fail-closed: no configured key ⇒ every endpoint 503s. Mirrors the existing OBA pattern (`ObaController` + `ObaApiKeyGuard`).

**Tech Stack:** Java 11, Spring Boot 2.7.18 (`javax.servlet`, not jakarta), JUnit 5 (Jupiter) + Mockito + MockMvc slice tests (`@WebMvcTest`). Build with Maven (`mvn`), JDK = Amazon Corretto 11.

**Spec:** `docs/superpowers/specs/2026-07-08-atwork-agent-api-design.md`

---

## File Structure

**New files:**
- `src/main/java/com/sandisk/plm/tracker/service/AgentApiKeyGuard.java` — multi-key `X-API-Key` validator; resolves a per-key label for audit. Constant-time compare.
- `src/main/java/com/sandisk/plm/tracker/service/AgentRateLimiter.java` — per-(key,bucket) fixed-window limiter; independent DATA and FILES buckets.
- `src/main/java/com/sandisk/plm/tracker/service/AgentEndpoint.java` — immutable descriptor (method, path, domain, description, returns, params).
- `src/main/java/com/sandisk/plm/tracker/service/AgentEndpointRegistry.java` — static ordered list of `AgentEndpoint`; backs the catalog + parity test.
- `src/main/java/com/sandisk/plm/tracker/controller/AgentApiController.java` — `/api/agent/*` wrappers + `/catalog`; holds the `gate`/`data`/`err` helpers.
- Tests: `AgentApiKeyGuardTest`, `AgentRateLimiterTest`, `AgentEndpointRegistryTest` (under `src/test/.../service/`), `AgentApiControllerTest`, `AgentApiCatalogParityTest` (under `src/test/.../controller/`), plus new methods in `AuthFilterTest`.

**Modified files:**
- `src/main/java/com/sandisk/plm/tracker/config/AuthFilter.java:48` — add `/api/agent/` to the session-auth exemption list.
- `src/main/resources/application.properties` — blank keys + rate defaults (fail-closed).
- `src/main/resources/static/whats-new.js` — changelog entry (pre-build requirement).

**Delegation targets (existing beans — do not modify):** `ItemsSearchService`, `BomDataService`, `ChangeQueryService`, `ChangeHistoryService`, `RevCompareService`, `EcoTimelineService`, `ChangeReviewService`, `DocReviewService`, `SdsmDocumentsService`, `SdsmPartsService`, `SdsmDeviationsService`, `SdsmContextIndex`, `SdsmFileService`, `SkuDataService`, `EcnReportService`, `KpiClassificationService`, `ReportService`, `RejectionTrackerService`, `RejectionSnapshotService`, `RejectionTrackerEmailService`, `OverdueTrackerService`, `AgileItemFilesClient`, `ActivityLogger`.

---

## Task 1: Config defaults (fail-closed)

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the Agent API config block**

Add after the OBA block (near line 63, after `app.oba.checklist-default=...`):

```properties
# === Agent API (Atwork AI agent — read-only gateway) ===
# See docs/superpowers/specs/2026-07-08-atwork-agent-api-design.md.
# Comma-separated keys (multiple ⇒ overlap rotation). BLANK in git — set on the
# server via external config. Blank ⇒ every /api/agent/* endpoint 503s (fail-closed).
app.agent.api-keys=
# Optional per-key labels for audit, index-aligned to app.agent.api-keys.
# Missing/blank label for key N falls back to "keyN".
app.agent.api-key-labels=
# Per-key rate limits (fixed 60s window). DATA = queries, FILES = downloads.
app.agent.rate.data-per-min=60
app.agent.rate.files-per-min=10
```

- [ ] **Step 2: Verify it parses**

Run: `grep -n "app.agent" src/main/resources/application.properties`
Expected: the 4 `app.agent.*` keys print.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat(agent-api): add fail-closed config defaults for /api/agent/*"
```

---

## Task 2: AgentApiKeyGuard (multi-key + labels)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/AgentApiKeyGuard.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/AgentApiKeyGuardTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AgentApiKeyGuardTest {

    @Test
    void notConfiguredWhenBlank() {
        AgentApiKeyGuard g = new AgentApiKeyGuard("", "");
        assertEquals(AgentApiKeyGuard.Result.NOT_CONFIGURED, g.check("anything").result);
    }

    @Test
    void unauthorizedWhenNullOrWrong() {
        AgentApiKeyGuard g = new AgentApiKeyGuard("k1,k2", "atwork,ci");
        assertEquals(AgentApiKeyGuard.Result.UNAUTHORIZED, g.check(null).result);
        assertEquals(AgentApiKeyGuard.Result.UNAUTHORIZED, g.check("nope").result);
    }

    @Test
    void okResolvesLabelPerKey() {
        AgentApiKeyGuard g = new AgentApiKeyGuard("k1,k2", "atwork,ci");
        AgentApiKeyGuard.CheckResult r1 = g.check("  k1  ");
        assertEquals(AgentApiKeyGuard.Result.OK, r1.result);
        assertEquals("atwork", r1.label);
        assertEquals("ci", g.check("k2").label);
    }

    @Test
    void labelFallsBackWhenMissing() {
        AgentApiKeyGuard g = new AgentApiKeyGuard("k1,k2", "atwork"); // only one label
        assertEquals("atwork", g.check("k1").label);
        assertEquals("key2", g.check("k2").label);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentApiKeyGuardTest test`
Expected: compile failure — `AgentApiKeyGuard` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates the {@code X-API-Key} header for the Agent API against one or more
 * configured keys ({@code app.agent.api-keys}, comma-separated for overlap
 * rotation). Blank config → NOT_CONFIGURED (endpoints must 503, never open by
 * default). On match, resolves a per-key audit label from
 * {@code app.agent.api-key-labels} (index-aligned; falls back to "keyN").
 * Comparison is constant-time per key.
 */
@Service
public class AgentApiKeyGuard {

    public enum Result { OK, NOT_CONFIGURED, UNAUTHORIZED }

    public static final class CheckResult {
        public final Result result;
        public final String label; // non-null only when result == OK
        public CheckResult(Result result, String label) { this.result = result; this.label = label; }
    }

    private final List<String> keys = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    public AgentApiKeyGuard(@Value("${app.agent.api-keys:}") String keysCsv,
                            @Value("${app.agent.api-key-labels:}") String labelsCsv) {
        List<String> ks = splitCsv(keysCsv);
        List<String> ls = splitCsv(labelsCsv);
        for (int i = 0; i < ks.size(); i++) {
            keys.add(ks.get(i));
            labels.add(i < ls.size() && !ls.get(i).isEmpty() ? ls.get(i) : "key" + (i + 1));
        }
    }

    public CheckResult check(String provided) {
        if (keys.isEmpty()) return new CheckResult(Result.NOT_CONFIGURED, null);
        if (provided == null) return new CheckResult(Result.UNAUTHORIZED, null);
        byte[] b = provided.trim().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < keys.size(); i++) {
            byte[] a = keys.get(i).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(a, b)) return new CheckResult(Result.OK, labels.get(i));
        }
        return new CheckResult(Result.UNAUTHORIZED, null);
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=AgentApiKeyGuardTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AgentApiKeyGuard.java src/test/java/com/sandisk/plm/tracker/service/AgentApiKeyGuardTest.java
git commit -m "feat(agent-api): AgentApiKeyGuard — multi-key gate with audit labels"
```

---

## Task 3: AgentRateLimiter (DATA + FILES buckets)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/AgentRateLimiter.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/AgentRateLimiterTest.java`

- [ ] **Step 1: Write the failing test**

The limiter uses a protected `now()` seam so time can be controlled in tests.

```java
package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AgentRateLimiterTest {

    /** Subclass exposing a settable clock. */
    static class TestLimiter extends AgentRateLimiter {
        long nowMs = 1_000_000L;
        TestLimiter(int data, int files) { super(data, files); }
        @Override protected long now() { return nowMs; }
    }

    @Test
    void allowsUpToLimitThenBlocks() {
        TestLimiter l = new TestLimiter(3, 10);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        AgentRateLimiter.Decision d = l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA);
        assertFalse(d.allowed);
        assertTrue(d.retryAfterSeconds >= 1 && d.retryAfterSeconds <= 60);
    }

    @Test
    void dataAndFileBucketsAreIndependent() {
        TestLimiter l = new TestLimiter(1, 1);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertFalse(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        // files bucket still has its own budget
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.FILES).allowed);
    }

    @Test
    void keysAreIndependent() {
        TestLimiter l = new TestLimiter(1, 1);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertTrue(l.tryAcquire("ci", AgentRateLimiter.Bucket.DATA).allowed);
    }

    @Test
    void windowRefillsAfter60s() {
        TestLimiter l = new TestLimiter(1, 1);
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        assertFalse(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
        l.nowMs += 60_001L;
        assertTrue(l.tryAcquire("atwork", AgentRateLimiter.Bucket.DATA).allowed);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentRateLimiterTest test`
Expected: compile failure — `AgentRateLimiter` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Per-(key,bucket) fixed-window rate limiter for the Agent API. DATA and FILES
 * are independent buckets so a burst of downloads can't starve queries and vice
 * versa. On block, reports the seconds until the current window resets.
 */
@Service
public class AgentRateLimiter {

    public enum Bucket { DATA, FILES }

    public static final class Decision {
        public final boolean allowed;
        public final long retryAfterSeconds; // 0 when allowed
        public Decision(boolean allowed, long retryAfterSeconds) {
            this.allowed = allowed; this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private static final class Window { long resetAtMs; int count; }

    private final int dataPerMin;
    private final int filesPerMin;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AgentRateLimiter(@Value("${app.agent.rate.data-per-min:60}") int dataPerMin,
                            @Value("${app.agent.rate.files-per-min:10}") int filesPerMin) {
        this.dataPerMin = dataPerMin;
        this.filesPerMin = filesPerMin;
    }

    /** Test seam — overridden in tests to control time. */
    protected long now() { return System.currentTimeMillis(); }

    public synchronized Decision tryAcquire(String label, Bucket bucket) {
        int limit = bucket == Bucket.FILES ? filesPerMin : dataPerMin;
        String k = label + ":" + bucket;
        long now = now();
        Window w = windows.get(k);
        if (w == null || now >= w.resetAtMs) {
            w = new Window();
            w.resetAtMs = now + 60_000L;
            w.count = 0;
            windows.put(k, w);
        }
        if (w.count < limit) {
            w.count++;
            return new Decision(true, 0);
        }
        long retry = Math.max(1, (w.resetAtMs - now + 999) / 1000);
        return new Decision(false, retry);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=AgentRateLimiterTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AgentRateLimiter.java src/test/java/com/sandisk/plm/tracker/service/AgentRateLimiterTest.java
git commit -m "feat(agent-api): AgentRateLimiter — independent DATA/FILES windows per key"
```

---

## Task 4: AgentEndpoint + AgentEndpointRegistry

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/AgentEndpoint.java`
- Create: `src/main/java/com/sandisk/plm/tracker/service/AgentEndpointRegistry.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/AgentEndpointRegistryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

public class AgentEndpointRegistryTest {

    @Test
    void everyEndpointIsFullyDescribed() {
        AgentEndpointRegistry reg = new AgentEndpointRegistry();
        assertFalse(reg.all().isEmpty(), "registry must not be empty");
        for (AgentEndpoint e : reg.all()) {
            assertNotNull(e.method, "method null on " + e.path);
            assertTrue(e.path != null && e.path.startsWith("/api/agent/"), "bad path: " + e.path);
            assertTrue(e.domain != null && !e.domain.isEmpty(), "no domain on " + e.path);
            assertTrue(e.description != null && !e.description.isEmpty(), "no description on " + e.path);
            assertTrue(e.returns != null && !e.returns.isEmpty(), "no returns on " + e.path);
            assertNotNull(e.params, "params null on " + e.path);
            for (AgentEndpoint.Param p : e.params) {
                assertTrue(p.name != null && !p.name.isEmpty(), "param no name on " + e.path);
                assertTrue(p.type != null && !p.type.isEmpty(), "param no type on " + e.path);
                assertTrue(p.description != null && !p.description.isEmpty(), "param no desc on " + e.path);
            }
        }
    }

    @Test
    void pathsAreUnique() {
        AgentEndpointRegistry reg = new AgentEndpointRegistry();
        Set<String> paths = reg.paths();
        assertEquals(reg.all().size(), paths.size(), "duplicate paths in registry");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentEndpointRegistryTest test`
Expected: compile failure — classes do not exist.

- [ ] **Step 3: Write AgentEndpoint**

```java
package com.sandisk.plm.tracker.service;

import java.util.Collections;
import java.util.List;

/** Immutable descriptor for one Agent API endpoint. Backs the /catalog response. */
public final class AgentEndpoint {

    public static final class Param {
        public final String name;
        public final String type;      // "string" | "integer" | "boolean" | "csv" | "date(YYYY-MM-DD)"
        public final boolean required;
        public final String description;
        public Param(String name, String type, boolean required, String description) {
            this.name = name; this.type = type; this.required = required; this.description = description;
        }
    }

    public final String method;   // "GET" | "POST"
    public final String path;     // full path, e.g. "/api/agent/changes"
    public final String domain;   // grouping label, e.g. "Changes"
    public final String description;
    public final String returns;  // human description of the response shape
    public final List<Param> params;

    public AgentEndpoint(String method, String path, String domain,
                         String description, String returns, List<Param> params) {
        this.method = method; this.path = path; this.domain = domain;
        this.description = description; this.returns = returns;
        this.params = params == null ? Collections.emptyList() : Collections.unmodifiableList(params);
    }
}
```

- [ ] **Step 4: Write AgentEndpointRegistry (skeleton with 3 seed entries)**

Start with three entries; Tasks 7-10 append the rest (each wrapper task adds its registry lines in the same style). A small builder keeps entries readable.

```java
package com.sandisk.plm.tracker.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the Agent API surface. The /catalog endpoint renders
 * this list, and AgentApiCatalogParityTest asserts these paths exactly match the
 * paths mapped on AgentApiController — so the catalog can never drift from reality.
 */
@Component
public class AgentEndpointRegistry {

    private final List<AgentEndpoint> endpoints = new ArrayList<>();

    public AgentEndpointRegistry() {
        // --- Items / Parts (Task 7 adds parts/search) ---
        add("GET", "/api/agent/items/columns", "Items",
            "List searchable item columns and the operators allowed on each.",
            "{ columns: [ {name, label, type, operators[]} ] }");
        add("GET", "/api/agent/items/distinct", "Items",
            "Distinct values for a categorical item column.",
            "{ column, values: [string] }",
            p("column", "string", true, "Column key from /items/columns (categorical only)"));
        add("POST", "/api/agent/items/search", "Items",
            "Attribute search over items. Body: { conditions:[{connector,column,operator,value,values[]}], columns:[string] }.",
            "{ rows:[{col:value}], columns:[string], matchedCount, truncated, elapsedMs }");
        // Tasks 8-10 append their entries here.
    }

    /** No-param add. */
    private void add(String method, String path, String domain, String desc, String returns) {
        endpoints.add(new AgentEndpoint(method, path, domain, desc, returns, new ArrayList<>()));
    }

    /** Add with params. */
    private void add(String method, String path, String domain, String desc, String returns,
                     AgentEndpoint.Param... params) {
        endpoints.add(new AgentEndpoint(method, path, domain, desc, returns,
                new ArrayList<>(Arrays.asList(params))));
    }

    private static AgentEndpoint.Param p(String name, String type, boolean required, String desc) {
        return new AgentEndpoint.Param(name, type, required, desc);
    }

    public List<AgentEndpoint> all() { return endpoints; }

    public Set<String> paths() {
        Set<String> s = new LinkedHashSet<>();
        for (AgentEndpoint e : endpoints) s.add(e.path);
        return s;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -Dtest=AgentEndpointRegistryTest test`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AgentEndpoint.java src/main/java/com/sandisk/plm/tracker/service/AgentEndpointRegistry.java src/test/java/com/sandisk/plm/tracker/service/AgentEndpointRegistryTest.java
git commit -m "feat(agent-api): AgentEndpoint descriptor + registry (catalog source of truth)"
```

---

## Task 5: AuthFilter exemption for /api/agent/

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/config/AuthFilter.java:48`
- Test: `src/test/java/com/sandisk/plm/tracker/config/AuthFilterTest.java` (add a method)

- [ ] **Step 1: Write the failing test (append to AuthFilterTest)**

Add this method inside the existing `AuthFilterTest` class:

```java
    /** Agent API is server-to-server with its own X-API-Key gate — the session
     *  filter must let it through, exactly like OBA. */
    @Test
    void agentApiBypassesSessionCheck() throws Exception {
        AuthFilter filter = new AuthFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/agent/catalog");

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(req, never()).getSession(anyBoolean());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AuthFilterTest test`
Expected: FAIL — `/api/agent/catalog` currently falls through to the session check (redirect/401), so `verify(chain).doFilter` fails.

- [ ] **Step 3: Add the exemption**

In `AuthFilter.java`, after the OBA line (`AuthFilter.java:48`, `path.startsWith("/api/oba/") ||`), add:

```java
            // Agent API (Atwork AI agent, server-to-server). No toolkit session —
            // the endpoints enforce their own X-API-Key gate + rate limiting in
            // AgentApiController.
            path.startsWith("/api/agent/") ||
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=AuthFilterTest test`
Expected: PASS (3 tests — 2 existing + new one).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/config/AuthFilter.java src/test/java/com/sandisk/plm/tracker/config/AuthFilterTest.java
git commit -m "feat(agent-api): exempt /api/agent/ from session auth (key-gated in controller)"
```

---

## Task 6: AgentApiController skeleton — gate/data/err helpers + /catalog

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/controller/AgentApiController.java`
- Test: `src/test/java/com/sandisk/plm/tracker/controller/AgentApiControllerTest.java`

This task builds the controller with **only** the `/catalog` endpoint and the shared helpers. Data/file wrappers come in Tasks 7-10. The controller injects the delegated service beans it will use across all tasks now (so later tasks only add methods, not constructor churn).

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AgentApiController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.sandisk\\.plm\\.tracker\\.config\\..*"))
public class AgentApiControllerTest {

    @Autowired MockMvc mvc;

    @MockBean AgentApiKeyGuard guard;
    @MockBean AgentRateLimiter rateLimiter;
    @MockBean AgentEndpointRegistry registry; // real one used in parity test; mocked here
    @MockBean ActivityLogger activityLogger;
    // Delegated service beans (unused in this task's tests but required by the context):
    @MockBean ItemsSearchService itemsSearchService;
    @MockBean BomDataService bomDataService;
    @MockBean ChangeQueryService changeQueryService;
    @MockBean ChangeHistoryService changeHistoryService;
    @MockBean RevCompareService revCompareService;
    @MockBean EcoTimelineService ecoTimelineService;
    @MockBean ChangeReviewService changeReviewService;
    @MockBean DocReviewService docReviewService;
    @MockBean SdsmDocumentsService sdsmDocumentsService;
    @MockBean SdsmPartsService sdsmPartsService;
    @MockBean SdsmDeviationsService sdsmDeviationsService;
    @MockBean SdsmContextIndex sdsmContextIndex;
    @MockBean SdsmFileService sdsmFileService;
    @MockBean SkuDataService skuDataService;
    @MockBean EcnReportService ecnReportService;
    @MockBean KpiClassificationService kpiClassificationService;
    @MockBean ReportService reportService;
    @MockBean RejectionTrackerService rejectionTrackerService;
    @MockBean RejectionSnapshotService rejectionSnapshotService;
    @MockBean RejectionTrackerEmailService rejectionTrackerEmailService;
    @MockBean OverdueTrackerService overdueTrackerService;
    @MockBean AgileItemFilesClient agileItemFilesClient;

    private void keyOk() {
        when(guard.check(anyString())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.OK, "atwork"));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(new AgentRateLimiter.Decision(true, 0));
    }

    @Test
    void catalog503WhenNotConfigured() throws Exception {
        when(guard.check(any())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.NOT_CONFIGURED, null));
        mvc.perform(get("/api/agent/catalog").header("X-API-Key", "x"))
           .andExpect(status().is(503));
    }

    @Test
    void catalog401WhenBadKey() throws Exception {
        when(guard.check(any())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.UNAUTHORIZED, null));
        mvc.perform(get("/api/agent/catalog").header("X-API-Key", "bad"))
           .andExpect(status().is(401));
    }

    @Test
    void catalog429WithBodyAndHeaderWhenThrottled() throws Exception {
        when(guard.check(anyString())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.OK, "atwork"));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(new AgentRateLimiter.Decision(false, 12));
        mvc.perform(get("/api/agent/catalog").header("X-API-Key", "k"))
           .andExpect(status().is(429))
           .andExpect(header().string("Retry-After", "12"))
           .andExpect(jsonPath("$.reason").value("rate_limit"))
           .andExpect(jsonPath("$.retryable").value(true))
           .andExpect(jsonPath("$.retryAfterSeconds").value(12))
           .andExpect(jsonPath("$.endUserMessage").isNotEmpty());
    }

    @Test
    void catalogReturnsContractAndEndpoints() throws Exception {
        keyOk();
        when(registry.all()).thenReturn(java.util.Collections.singletonList(
            new AgentEndpoint("GET", "/api/agent/items/columns", "Items", "desc", "returns", null)));
        mvc.perform(get("/api/agent/catalog").header("X-API-Key", "k"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.version").value("1"))
           .andExpect(jsonPath("$.rateLimitContract.clientObligation").isNotEmpty())
           .andExpect(jsonPath("$.endpoints[0].path").value("/api/agent/items/columns"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentApiControllerTest test`
Expected: compile failure — `AgentApiController` does not exist.

- [ ] **Step 3: Write the controller skeleton**

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Read-only, X-API-Key-gated gateway for the Atwork AI agent.
 * Session auth is bypassed for /api/agent/* in AuthFilter; this controller
 * enforces the key + per-key rate limits and logs every call via ActivityLogger.
 * See docs/superpowers/specs/2026-07-08-atwork-agent-api-design.md.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentApiController {

    private static final Logger LOG = Logger.getLogger(AgentApiController.class.getName());

    private final AgentApiKeyGuard guard;
    private final AgentRateLimiter rateLimiter;
    private final AgentEndpointRegistry registry;
    private final ActivityLogger activityLogger;

    // Delegated read-only service beans.
    private final ItemsSearchService itemsSearchService;
    private final BomDataService bomDataService;
    private final ChangeQueryService changeQueryService;
    private final ChangeHistoryService changeHistoryService;
    private final RevCompareService revCompareService;
    private final EcoTimelineService ecoTimelineService;
    private final ChangeReviewService changeReviewService;
    private final DocReviewService docReviewService;
    private final SdsmDocumentsService sdsmDocumentsService;
    private final SdsmPartsService sdsmPartsService;
    private final SdsmDeviationsService sdsmDeviationsService;
    private final SdsmContextIndex sdsmContextIndex;
    private final SdsmFileService sdsmFileService;
    private final SkuDataService skuDataService;
    private final EcnReportService ecnReportService;
    private final KpiClassificationService kpiClassificationService;
    private final ReportService reportService;
    private final RejectionTrackerService rejectionTrackerService;
    private final RejectionSnapshotService rejectionSnapshotService;
    private final RejectionTrackerEmailService rejectionTrackerEmailService;
    private final OverdueTrackerService overdueTrackerService;
    private final AgileItemFilesClient agileItemFilesClient;

    @Value("${app.agent.rate.data-per-min:60}") private int dataPerMin;
    @Value("${app.agent.rate.files-per-min:10}") private int filesPerMin;

    public AgentApiController(AgentApiKeyGuard guard, AgentRateLimiter rateLimiter,
                              AgentEndpointRegistry registry, ActivityLogger activityLogger,
                              ItemsSearchService itemsSearchService, BomDataService bomDataService,
                              ChangeQueryService changeQueryService, ChangeHistoryService changeHistoryService,
                              RevCompareService revCompareService, EcoTimelineService ecoTimelineService,
                              ChangeReviewService changeReviewService, DocReviewService docReviewService,
                              SdsmDocumentsService sdsmDocumentsService, SdsmPartsService sdsmPartsService,
                              SdsmDeviationsService sdsmDeviationsService, SdsmContextIndex sdsmContextIndex,
                              SdsmFileService sdsmFileService, SkuDataService skuDataService,
                              EcnReportService ecnReportService, KpiClassificationService kpiClassificationService,
                              ReportService reportService, RejectionTrackerService rejectionTrackerService,
                              RejectionSnapshotService rejectionSnapshotService,
                              RejectionTrackerEmailService rejectionTrackerEmailService,
                              OverdueTrackerService overdueTrackerService,
                              AgileItemFilesClient agileItemFilesClient) {
        this.guard = guard; this.rateLimiter = rateLimiter; this.registry = registry;
        this.activityLogger = activityLogger;
        this.itemsSearchService = itemsSearchService; this.bomDataService = bomDataService;
        this.changeQueryService = changeQueryService; this.changeHistoryService = changeHistoryService;
        this.revCompareService = revCompareService; this.ecoTimelineService = ecoTimelineService;
        this.changeReviewService = changeReviewService; this.docReviewService = docReviewService;
        this.sdsmDocumentsService = sdsmDocumentsService; this.sdsmPartsService = sdsmPartsService;
        this.sdsmDeviationsService = sdsmDeviationsService; this.sdsmContextIndex = sdsmContextIndex;
        this.sdsmFileService = sdsmFileService; this.skuDataService = skuDataService;
        this.ecnReportService = ecnReportService; this.kpiClassificationService = kpiClassificationService;
        this.reportService = reportService; this.rejectionTrackerService = rejectionTrackerService;
        this.rejectionSnapshotService = rejectionSnapshotService;
        this.rejectionTrackerEmailService = rejectionTrackerEmailService;
        this.overdueTrackerService = overdueTrackerService; this.agileItemFilesClient = agileItemFilesClient;
    }

    // ---- Discovery ----

    @GetMapping("/catalog")
    public ResponseEntity<?> catalog(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ResponseEntity<Map<String, Object>> deny = gate(apiKey, AgentRateLimiter.Bucket.DATA, "/api/agent/catalog", null);
        if (deny != null) return deny;

        List<Map<String, Object>> eps = new ArrayList<>();
        for (AgentEndpoint e : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", e.method);
            m.put("path", e.path);
            m.put("domain", e.domain);
            m.put("description", e.description);
            m.put("returns", e.returns);
            List<Map<String, Object>> ps = new ArrayList<>();
            for (AgentEndpoint.Param p : e.params) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.name); pm.put("type", p.type);
                pm.put("required", p.required); pm.put("description", p.description);
                ps.add(pm);
            }
            m.put("params", ps);
            eps.add(m);
        }

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("dataPerMin", dataPerMin);
        contract.put("filesPerMin", filesPerMin);
        contract.put("onExceed", "HTTP 429 with Retry-After header and a machine-readable body.");
        contract.put("clientObligation", "A 429 means the requested data was NOT returned. If the agent "
                + "was gathering data to answer an end-user question, it MUST tell the end user the answer "
                + "is incomplete (throttled) rather than answering from partial data. Back off per "
                + "Retry-After and retry.");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "1");
        body.put("generatedAt", Instant.now().toString());
        body.put("rateLimitContract", contract);
        body.put("endpoints", eps);
        return ResponseEntity.ok(body);
    }

    // ---- Shared helpers (used by every wrapper) ----

    /** Key check → rate limit → audit. Returns a deny ResponseEntity, or null when allowed. */
    private ResponseEntity<Map<String, Object>> gate(String apiKey, AgentRateLimiter.Bucket bucket,
                                                     String path, String detail) {
        AgentApiKeyGuard.CheckResult cr = guard.check(apiKey);
        switch (cr.result) {
            case NOT_CONFIGURED: return err(503, "Agent API not configured");
            case UNAUTHORIZED:   return err(401, "invalid or missing X-API-Key");
            default: break;
        }
        AgentRateLimiter.Decision d = rateLimiter.tryAcquire(cr.label, bucket);
        if (!d.allowed) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("error", "Rate limit exceeded — this data was not returned. Any answer built without it is incomplete.");
            b.put("status", 429);
            b.put("reason", "rate_limit");
            b.put("retryable", true);
            b.put("retryAfterSeconds", d.retryAfterSeconds);
            b.put("endUserMessage", "I couldn't retrieve all the information needed to answer this fully "
                    + "because the PLM system is rate-limiting requests. Please try again in a few seconds.");
            return ResponseEntity.status(429).header("Retry-After", String.valueOf(d.retryAfterSeconds)).body(b);
        }
        activityLogger.log("agent:" + cr.label, cr.label, "AGENT_API",
                path + (detail == null || detail.isEmpty() ? "" : " " + detail));
        return null;
    }

    private static ResponseEntity<Map<String, Object>> err(int status, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", message);
        b.put("status", status);
        return ResponseEntity.status(status).body(b);
    }

    /** Wrap a read-only data supplier: gate (DATA bucket) then run, mapping failures to the envelope. */
    private ResponseEntity<?> data(String apiKey, String path, String detail, Supplier<Object> body) {
        ResponseEntity<Map<String, Object>> deny = gate(apiKey, AgentRateLimiter.Bucket.DATA, path, detail);
        if (deny != null) return deny;
        try {
            return ResponseEntity.ok(body.get());
        } catch (IllegalArgumentException e) {
            return err(400, e.getMessage() == null ? "bad request" : e.getMessage());
        } catch (Exception e) {
            LOG.warning("[AGENT] " + path + " failed: " + e);
            return err(503, "data source temporarily unavailable");
        }
    }

    /** Wrap a List result as { data:[...], count:N }. */
    private static Map<String, Object> listBody(List<?> list) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", list == null ? 0 : list.size());
        m.put("data", list == null ? Collections.emptyList() : list);
        return m;
    }

    /** Split a CSV param into a trimmed, non-empty list (empty list when null/blank). */
    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=AgentApiControllerTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/AgentApiController.java src/test/java/com/sandisk/plm/tracker/controller/AgentApiControllerTest.java
git commit -m "feat(agent-api): controller skeleton — gate/data/err helpers + /catalog"
```

---

## Task 7: Wrappers — Items, Parts, Changes, History

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/AgentApiController.java`
- Modify: `src/main/java/com/sandisk/plm/tracker/service/AgentEndpointRegistry.java`
- Modify: `src/test/java/com/sandisk/plm/tracker/controller/AgentApiControllerTest.java`

- [ ] **Step 1: Write the failing test (append to AgentApiControllerTest)**

```java
    @Test
    void changesSearchDelegatesAndReturnsTruncationFlag() throws Exception {
        keyOk();
        ChangeQueryService.SearchResult sr = new ChangeQueryService.SearchResult(
            java.util.Collections.emptyList(), 0, 0, 5L, true, false, "2026-07-08");
        when(changeQueryService.search(any(), any(), any(), anyInt(), any(), any(), anyBoolean()))
            .thenReturn(sr);
        mvc.perform(get("/api/agent/changes").header("X-API-Key", "k").param("item", "ABC"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.truncated").value(true))
           .andExpect(jsonPath("$.dataAsOf").value("2026-07-08"));
    }

    @Test
    void itemsDistinctDelegates() throws Exception {
        keyOk();
        when(itemsSearchService.distinctValues("lifecycle"))
            .thenReturn(java.util.Arrays.asList("Prototype", "Production"));
        mvc.perform(get("/api/agent/items/distinct").header("X-API-Key", "k").param("column", "lifecycle"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.column").value("lifecycle"))
           .andExpect(jsonPath("$.values[1]").value("Production"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentApiControllerTest test`
Expected: FAIL 404 (no such mapping yet).

- [ ] **Step 3: Add the wrapper methods**

Insert into `AgentApiController` (after the `catalog` method, before the helpers):

```java
    // ---- Items / Parts ----

    @GetMapping("/items/columns")
    public ResponseEntity<?> itemsColumns(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/items/columns", null, () -> {
            List<Map<String, Object>> cols = new ArrayList<>();
            for (ItemsSearchService.ColumnDef c : ItemsSearchService.COLUMNS) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.key);
                m.put("label", c.label);
                m.put("type", c.type.name());
                m.put("operators", ItemsSearchService.opsFor(c.type));
                cols.add(m);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("columns", cols);
            return body;
        });
    }

    @GetMapping("/items/distinct")
    public ResponseEntity<?> itemsDistinct(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam("column") String column) {
        return data(apiKey, "/api/agent/items/distinct", "column=" + column, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("column", column);
            body.put("values", itemsSearchService.distinctValues(column));
            return body;
        });
    }

    @PostMapping("/items/search")
    public ResponseEntity<?> itemsSearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestBody Map<String, Object> req) {
        return data(apiKey, "/api/agent/items/search", null, () -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawConds = (List<Map<String, Object>>) req.getOrDefault("conditions", Collections.emptyList());
            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) req.getOrDefault("columns", Collections.emptyList());
            List<ItemsSearchService.Condition> conds = new ArrayList<>();
            for (Map<String, Object> rc : rawConds) {
                ItemsSearchService.Condition c = new ItemsSearchService.Condition();
                c.connector = str(rc.get("connector"));
                c.column = str(rc.get("column"));
                c.operator = str(rc.get("operator"));
                c.value = str(rc.get("value"));
                Object vs = rc.get("values");
                if (vs instanceof List) {
                    List<String> lv = new ArrayList<>();
                    for (Object o : (List<?>) vs) lv.add(String.valueOf(o));
                    c.values = lv;
                }
                conds.add(c);
            }
            ItemsSearchService.RunResult r = itemsSearchService.run(conds, cols);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rows", r.rows);
            body.put("columns", r.columns);
            body.put("matchedCount", r.matchedCount);
            body.put("truncated", r.truncated);
            body.put("elapsedMs", r.elapsedMs);
            if (r.errorMessage != null) body.put("errorMessage", r.errorMessage);
            return body;
        });
    }

    @GetMapping("/parts/search")
    public ResponseEntity<?> partsSearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestParam("items") String items,
                                         @RequestParam(value = "columns", required = false) String columns,
                                         @RequestParam(value = "releaseDateFrom", required = false) String releaseDateFrom,
                                         @RequestParam(value = "releaseDateTo", required = false) String releaseDateTo) {
        return data(apiKey, "/api/agent/parts/search", "items=" + items, () ->
            listBody(bomDataService.searchParts(items, csv(columns), releaseDateFrom, releaseDateTo)));
    }

    // ---- Changes / History ----

    @GetMapping("/changes")
    public ResponseEntity<?> changes(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                     @RequestParam(value = "field", required = false) String field,
                                     @RequestParam(value = "item", required = false) String item,
                                     @RequestParam(value = "user", required = false) String user,
                                     @RequestParam(value = "days", defaultValue = "7") int days,
                                     @RequestParam(value = "oldContains", required = false) String oldContains,
                                     @RequestParam(value = "newContains", required = false) String newContains,
                                     @RequestParam(value = "netFilter", defaultValue = "false") boolean netFilter) {
        return data(apiKey, "/api/agent/changes", "item=" + item + " days=" + days, () -> {
            ChangeQueryService.SearchResult r =
                changeQueryService.search(field, item, user, days, oldContains, newContains, netFilter);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("results", r.getResults());
            body.put("totalCount", r.totalCount);
            body.put("uniqueItems", r.uniqueItems);
            body.put("queryTimeMs", r.queryTimeMs);
            body.put("truncated", r.truncated);
            body.put("dbOffline", r.dbOffline);
            body.put("dataAsOf", r.dataAsOf);
            return body;
        });
    }

    @GetMapping("/history/search")
    public ResponseEntity<?> historySearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam("items") String items,
                                           @RequestParam(value = "lifecyclePhases", required = false) String lifecyclePhases,
                                           @RequestParam(value = "changeTypes", required = false) String changeTypes,
                                           @RequestParam(value = "partTypes", required = false) String partTypes,
                                           @RequestParam(value = "releaseDateFrom", required = false) String releaseDateFrom,
                                           @RequestParam(value = "releaseDateTo", required = false) String releaseDateTo,
                                           @RequestParam(value = "entryMode", defaultValue = "ALL") String entryMode) {
        return data(apiKey, "/api/agent/history/search", "items=" + items, () -> {
            ChangeHistoryService.HistoryFilters f = new ChangeHistoryService.HistoryFilters();
            f.lifecyclePhases = csv(lifecyclePhases);
            f.changeTypes = csv(changeTypes);
            f.partTypes = csv(partTypes);
            f.releaseDateFrom = releaseDateFrom;
            f.releaseDateTo = releaseDateTo;
            try {
                f.entryMode = ChangeHistoryService.EntryMode.valueOf(entryMode.trim().toUpperCase());
            } catch (Exception ignore) {
                f.entryMode = ChangeHistoryService.EntryMode.ALL;
            }
            return listBody(changeHistoryService.getHistoryFiltered(csv(items), f));
        });
    }
```

Also add this small helper near `csv(...)`:

```java
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
```

- [ ] **Step 4: Add the registry entries**

In `AgentEndpointRegistry` constructor, replace the `// Tasks 8-10 append...` comment location by first adding the Task-7 entries right after the existing `items/search` entry:

```java
        add("GET", "/api/agent/parts/search", "Items",
            "Part-extract search over item_extract for one or more item numbers.",
            "{ count, data:[{col:value}] }",
            p("items", "csv", true, "Comma-separated item numbers"),
            p("columns", "csv", false, "Columns to return (default: service default set)"),
            p("releaseDateFrom", "date(YYYY-MM-DD)", false, "Filter: released on/after"),
            p("releaseDateTo", "date(YYYY-MM-DD)", false, "Filter: released on/before"));

        // --- Changes / History ---
        add("GET", "/api/agent/changes", "Changes",
            "Field-level change history search across items/users over a day window.",
            "{ results:[{item,field,oldValue,newValue,user,revNumber,...}], totalCount, uniqueItems, truncated, dbOffline, dataAsOf }",
            p("field", "string", false, "Field name filter"),
            p("item", "string", false, "Item number filter"),
            p("user", "string", false, "User filter"),
            p("days", "integer", false, "Lookback window in days (default 7)"),
            p("oldContains", "string", false, "Old-value substring filter"),
            p("newContains", "string", false, "New-value substring filter"),
            p("netFilter", "boolean", false, "Collapse to net change per item+field"));
        add("GET", "/api/agent/history/search", "Changes",
            "Item change/release history with lifecycle/change-type/part-type filters.",
            "{ count, data:[{col:value}] }",
            p("items", "csv", true, "Comma-separated item numbers"),
            p("lifecyclePhases", "csv", false, "Lifecycle phase filter"),
            p("changeTypes", "csv", false, "Change type filter"),
            p("partTypes", "csv", false, "Part type filter"),
            p("releaseDateFrom", "date(YYYY-MM-DD)", false, "Released on/after"),
            p("releaseDateTo", "date(YYYY-MM-DD)", false, "Released on/before"),
            p("entryMode", "string", false, "ALL | FIRST | LAST (default ALL)"));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -Dtest=AgentApiControllerTest,AgentEndpointRegistryTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/AgentApiController.java src/main/java/com/sandisk/plm/tracker/service/AgentEndpointRegistry.java src/test/java/com/sandisk/plm/tracker/controller/AgentApiControllerTest.java
git commit -m "feat(agent-api): Items/Parts/Changes/History read wrappers + catalog entries"
```

---

## Task 8: Wrappers — BOM, RevCompare, ECO Timeline, Change Review, Doc Review

**Files:**
- Modify: `AgentApiController.java`, `AgentEndpointRegistry.java`, `AgentApiControllerTest.java`

- [ ] **Step 1: Write the failing test (append)**

```java
    @Test
    void bomExplodeDelegates() throws Exception {
        keyOk();
        when(bomDataService.explodeMultiple(any(), anyInt(), any()))
            .thenReturn(java.util.Collections.emptyList());
        mvc.perform(get("/api/agent/bom/explode").header("X-API-Key", "k")
                .param("items", "ABC").param("maxDepth", "5"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void ecoTimelineParsesDates() throws Exception {
        keyOk();
        when(ecoTimelineService.query(eq("ABC"), any(), any(), anyInt()))
            .thenReturn(java.util.Collections.singletonMap("ok", true));
        mvc.perform(get("/api/agent/eco-timeline").header("X-API-Key", "k")
                .param("item", "ABC").param("from", "2026-01-01").param("to", "2026-06-30"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.ok").value(true));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentApiControllerTest test`
Expected: FAIL 404.

- [ ] **Step 3: Add wrapper methods**

```java
    // ---- BOM ----

    @GetMapping("/bom/explode")
    public ResponseEntity<?> bomExplode(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                        @RequestParam("items") String items,
                                        @RequestParam(value = "maxDepth", defaultValue = "20") int maxDepth,
                                        @RequestParam(value = "lifecycles", required = false) String lifecycles,
                                        @RequestParam(value = "lifecyclesMode", required = false) String lifecyclesMode,
                                        @RequestParam(value = "partTypes", required = false) String partTypes,
                                        @RequestParam(value = "partTypesMode", required = false) String partTypesMode,
                                        @RequestParam(value = "prefixes", required = false) String prefixes,
                                        @RequestParam(value = "prefixesMode", required = false) String prefixesMode,
                                        @RequestParam(value = "maxTopLevelParents", required = false) Integer maxTopLevelParents) {
        return data(apiKey, "/api/agent/bom/explode", "items=" + items, () -> {
            com.sandisk.plm.tracker.model.BomFilters filters = com.sandisk.plm.tracker.model.BomFilters.parse(
                lifecycles, lifecyclesMode, partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
            return listBody(bomDataService.explodeMultiple(csv(items), maxDepth, filters));
        });
    }

    @GetMapping("/bom/implode")
    public ResponseEntity<?> bomImplode(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                        @RequestParam("items") String items,
                                        @RequestParam(value = "maxDepth", defaultValue = "20") int maxDepth,
                                        @RequestParam(value = "lifecycles", required = false) String lifecycles,
                                        @RequestParam(value = "lifecyclesMode", required = false) String lifecyclesMode,
                                        @RequestParam(value = "partTypes", required = false) String partTypes,
                                        @RequestParam(value = "partTypesMode", required = false) String partTypesMode,
                                        @RequestParam(value = "prefixes", required = false) String prefixes,
                                        @RequestParam(value = "prefixesMode", required = false) String prefixesMode,
                                        @RequestParam(value = "maxTopLevelParents", required = false) Integer maxTopLevelParents) {
        return data(apiKey, "/api/agent/bom/implode", "items=" + items, () -> {
            com.sandisk.plm.tracker.model.BomFilters filters = com.sandisk.plm.tracker.model.BomFilters.parse(
                lifecycles, lifecyclesMode, partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
            return listBody(bomDataService.implodeMultiple(csv(items), maxDepth, filters));
        });
    }

    @GetMapping("/bom/components")
    public ResponseEntity<?> bomComponents(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam("parent") String parent) {
        return data(apiKey, "/api/agent/bom/components", "parent=" + parent, () ->
            listBody(bomDataService.getBomComponents(parent)));
    }

    // ---- Revisions ----

    @GetMapping("/rev-compare/revs")
    public ResponseEntity<?> revs(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                  @RequestParam("part") String part) {
        return data(apiKey, "/api/agent/rev-compare/revs", "part=" + part, () ->
            listBody(revCompareService.getRevisions(part)));
    }

    @GetMapping("/rev-compare/detail")
    public ResponseEntity<?> revDetail(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                       @RequestParam("part") String part,
                                       @RequestParam("rev") String rev,
                                       @RequestParam(value = "change", required = false) String change) {
        return data(apiKey, "/api/agent/rev-compare/detail", "part=" + part + " rev=" + rev, () ->
            revCompareService.getRevDetail(part, rev, change));
    }

    // ---- ECO Timeline ----

    @GetMapping("/eco-timeline")
    public ResponseEntity<?> ecoTimeline(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestParam("item") String item,
                                         @RequestParam(value = "from", required = false) String from,
                                         @RequestParam(value = "to", required = false) String to,
                                         @RequestParam(value = "maxDepth", defaultValue = "25") int maxDepth) {
        return data(apiKey, "/api/agent/eco-timeline", "item=" + item, () -> {
            java.time.LocalDate f = parseDate(from);
            java.time.LocalDate t = parseDate(to);
            return ecoTimelineService.query(item, f, t, maxDepth);
        });
    }

    // ---- Change Review ----

    @GetMapping("/change-reviews/analysts")
    public ResponseEntity<?> reviewAnalysts(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/change-reviews/analysts", null, () ->
            listBody(changeReviewService.getAnalysts()));
    }

    @GetMapping("/change-reviews/detail")
    public ResponseEntity<?> reviewDetail(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                          @RequestParam("change") String change) {
        return data(apiKey, "/api/agent/change-reviews/detail", "change=" + change, () ->
            changeReviewService.getSignoffDetail(change));
    }

    @GetMapping("/change-reviews/dashboard")
    public ResponseEntity<?> reviewDashboard(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                             @RequestParam(value = "days", defaultValue = "30") int days) {
        return data(apiKey, "/api/agent/change-reviews/dashboard", "days=" + days, () ->
            listBody(changeReviewService.getAllChangesInReview(days)));
    }

    // ---- Doc Review ----

    @GetMapping("/doc-review/data")
    public ResponseEntity<?> docReviewData(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam(value = "window", required = false) String window,
                                           @RequestParam(value = "from", required = false) String from,
                                           @RequestParam(value = "to", required = false) String to) {
        return data(apiKey, "/api/agent/doc-review/data", "window=" + window, () ->
            listBody(docReviewService.search(DocReviewService.parseWindow(window), from, to, false)));
    }
```

Add this date-parse helper near `csv(...)` (throws `IllegalArgumentException` → mapped to 400 by `data(...)`):

```java
    private static java.time.LocalDate parseDate(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return java.time.LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("bad date (expected YYYY-MM-DD): " + s);
        }
    }
```

- [ ] **Step 4: Add registry entries** (append in constructor)

```java
        // --- BOM ---
        add("GET", "/api/agent/bom/explode", "BOM",
            "Multi-level BOM explosion for one or more assemblies.",
            "{ count, data:[BomResult] }",
            p("items", "csv", true, "Comma-separated parent item numbers"),
            p("maxDepth", "integer", false, "Max explode depth (default 20)"),
            p("lifecycles", "csv", false, "Lifecycle filter values"),
            p("lifecyclesMode", "string", false, "include | exclude (default include)"),
            p("partTypes", "csv", false, "Part-type filter values"),
            p("partTypesMode", "string", false, "include | exclude"),
            p("prefixes", "csv", false, "Item-number prefix filter"),
            p("prefixesMode", "string", false, "include | exclude"),
            p("maxTopLevelParents", "integer", false, "Cap (where-used only)"));
        add("GET", "/api/agent/bom/implode", "BOM",
            "Where-used (reverse BOM) for one or more components.",
            "{ count, data:[BomResult] }",
            p("items", "csv", true, "Comma-separated component item numbers"),
            p("maxDepth", "integer", false, "Max implode depth (default 20)"),
            p("lifecycles", "csv", false, "Lifecycle filter values"),
            p("lifecyclesMode", "string", false, "include | exclude"),
            p("partTypes", "csv", false, "Part-type filter values"),
            p("partTypesMode", "string", false, "include | exclude"),
            p("prefixes", "csv", false, "Item-number prefix filter"),
            p("prefixesMode", "string", false, "include | exclude"),
            p("maxTopLevelParents", "integer", false, "Cap on top-level parents emitted"));
        add("GET", "/api/agent/bom/components", "BOM",
            "Direct (single-level) component rows for one parent item.",
            "{ count, data:[{col:value}] }",
            p("parent", "string", true, "Parent item number"));

        // --- Revisions ---
        add("GET", "/api/agent/rev-compare/revs", "Revisions",
            "List revisions for a part.",
            "{ count, data:[{rev,change,...}] }",
            p("part", "string", true, "Part number"));
        add("GET", "/api/agent/rev-compare/detail", "Revisions",
            "Attribute/BOM detail for one part at one revision/change.",
            "{ ...rev detail map... }",
            p("part", "string", true, "Part number"),
            p("rev", "string", true, "Revision label"),
            p("change", "string", false, "Change number pinning the rev"));

        // --- ECO Timeline ---
        add("GET", "/api/agent/eco-timeline", "ECO Timeline",
            "ECO/change timeline for an item over a date range.",
            "{ ...timeline map... }",
            p("item", "string", true, "Item number"),
            p("from", "date(YYYY-MM-DD)", false, "Range start"),
            p("to", "date(YYYY-MM-DD)", false, "Range end"),
            p("maxDepth", "integer", false, "Max depth (default 25)"));

        // --- Change Review ---
        add("GET", "/api/agent/change-reviews/analysts", "Change Review",
            "List change-review analysts.",
            "{ count, data:[{loginid,name,...}] }");
        add("GET", "/api/agent/change-reviews/detail", "Change Review",
            "Sign-off detail for one change.",
            "{ ...signoff detail map... }",
            p("change", "string", true, "Change number"));
        add("GET", "/api/agent/change-reviews/dashboard", "Change Review",
            "Changes currently in review over a lookback window.",
            "{ count, data:[{change,...}] }",
            p("days", "integer", false, "Lookback days (default 30)"));

        // --- Documents (Doc Review) ---
        add("GET", "/api/agent/doc-review/data", "Documents",
            "Document-review dataset for a time window.",
            "{ count, data:[{col:value}] }",
            p("window", "string", false, "Named window (service-defined)"),
            p("from", "date(YYYY-MM-DD)", false, "Custom range start"),
            p("to", "date(YYYY-MM-DD)", false, "Custom range end"));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -Dtest=AgentApiControllerTest,AgentEndpointRegistryTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(agent-api): BOM/Rev/ECO-timeline/change-review/doc-review wrappers + catalog"
```

---

## Task 9: Wrappers — SDSM search/facets, SKU, ECN report, Returns, Overdue

**Files:**
- Modify: `AgentApiController.java`, `AgentEndpointRegistry.java`, `AgentApiControllerTest.java`

- [ ] **Step 1: Write the failing test (append)**

```java
    @Test
    void skuFieldsDelegates() throws Exception {
        keyOk();
        when(skuDataService.getAvailableFields()).thenReturn(java.util.Arrays.asList("SKU", "Status"));
        mvc.perform(get("/api/agent/sku/fields").header("X-API-Key", "k"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0]").value("SKU"));
    }

    @Test
    void ecnReportDataDelegates() throws Exception {
        keyOk();
        when(ecnReportService.getEcnData()).thenReturn(java.util.Collections.singletonMap("rows", 5));
        mvc.perform(get("/api/agent/ecn-report/data").header("X-API-Key", "k"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.rows").value(5));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentApiControllerTest test`
Expected: FAIL 404.

- [ ] **Step 3: Add wrapper methods**

```java
    // ---- SDSM (document search + facets) ----

    @GetMapping("/sdsm/search")
    public ResponseEntity<?> sdsmSearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                        @RequestParam(value = "q", required = false) String q) {
        return data(apiKey, "/api/agent/sdsm/search", "q=" + q, () -> {
            String filter = (q == null) ? "" : q.trim();
            List<com.sandisk.plm.tracker.model.SdsmAttachment> out = new ArrayList<>();
            out.addAll(sdsmDocumentsService.run(filter, 0));
            out.addAll(sdsmPartsService.run(filter, 0));
            return listBody(out);
        });
    }

    @GetMapping("/sdsm/specs")
    public ResponseEntity<?> sdsmSpecs(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sdsm/specs", null, () -> listBody(sdsmContextIndex.getSpecs()));
    }

    @GetMapping("/sdsm/product-groups")
    public ResponseEntity<?> sdsmProductGroups(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sdsm/product-groups", null, () -> listBody(sdsmContextIndex.getProductGroups()));
    }

    @GetMapping("/sdsm/products")
    public ResponseEntity<?> sdsmProducts(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sdsm/products", null, () -> listBody(sdsmContextIndex.getProducts()));
    }

    @GetMapping("/sdsm/active-deviations")
    public ResponseEntity<?> sdsmActiveDeviations(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sdsm/active-deviations", null, () -> listBody(sdsmDeviationsService.run(null)));
    }

    // ---- SKU ----

    @GetMapping("/sku/fields")
    public ResponseEntity<?> skuFields(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sku/fields", null, () -> listBody(skuDataService.getAvailableFields()));
    }

    @GetMapping("/sku/search")
    public ResponseEntity<?> skuSearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                       @RequestParam("items") String items) {
        return data(apiKey, "/api/agent/sku/search", "items=" + items, () -> {
            List<Object> records = new ArrayList<>();
            for (String it : csv(items)) {
                Object rec = skuDataService.getRecord(it);
                if (rec != null) records.add(rec);
            }
            return listBody(records);
        });
    }

    // ---- ECN report data ----

    @GetMapping("/ecn-report/data")
    public ResponseEntity<?> ecnReportData(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/ecn-report/data", null, () -> ecnReportService.getEcnData());
    }

    @GetMapping("/ecn-report/kpi-classifications")
    public ResponseEntity<?> ecnKpi(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/ecn-report/kpi-classifications", null, () ->
            listBody(kpiClassificationService.getEntries()));
    }

    // ---- Returns / Rejection tracker ----

    @GetMapping("/returns/data")
    public ResponseEntity<?> returnsData(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestParam(value = "from", required = false) String from,
                                         @RequestParam(value = "to", required = false) String to) {
        return data(apiKey, "/api/agent/returns/data", "from=" + from + " to=" + to, () ->
            listBody(rejectionTrackerService.getEventsInRange(parseDate(from), parseDate(to))));
    }

    @GetMapping("/returns/periods")
    public ResponseEntity<?> returnsPeriods(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/returns/periods", null, () -> listBody(rejectionSnapshotService.listPeriods()));
    }

    @GetMapping("/returns/explain/{eventId}")
    public ResponseEntity<?> returnsExplain(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                            @PathVariable String eventId) {
        return data(apiKey, "/api/agent/returns/explain/" + eventId, null, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventId", eventId);
            try {
                body.put("explanation", rejectionTrackerEmailService.explainEvent(eventId));
            } catch (Exception e) {
                throw new RuntimeException(e); // → 503 via data(); explain is best-effort
            }
            return body;
        });
    }

    // ---- Overdue tracker ----

    @GetMapping("/overdue/data")
    public ResponseEntity<?> overdueData(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestParam(value = "minOver", required = false) Integer minOver,
                                         @RequestParam(value = "maxOver", required = false) Integer maxOver,
                                         @RequestParam(value = "classifications", required = false) String classifications) {
        return data(apiKey, "/api/agent/overdue/data", null, () ->
            overdueTrackerService.getData(minOver, maxOver, null, null, null, null, null, null, classifications));
    }
```

- [ ] **Step 4: Add registry entries** (append)

```java
        // --- Documents (SDSM) ---
        add("GET", "/api/agent/sdsm/search", "Documents",
            "Shop-floor (SDSM) document search by item-number substring.",
            "{ count, data:[SdsmAttachment] }",
            p("q", "string", false, "Item-number filter (blank = all, capped by service)"));
        add("GET", "/api/agent/sdsm/specs", "Documents", "List SDSM spec facet values.", "{ count, data:[string] }");
        add("GET", "/api/agent/sdsm/product-groups", "Documents", "List SDSM product-group facet values.", "{ count, data:[string] }");
        add("GET", "/api/agent/sdsm/products", "Documents", "List SDSM product facet values.", "{ count, data:[string] }");
        add("GET", "/api/agent/sdsm/active-deviations", "Documents", "List active SDSM deviations.", "{ count, data:[SdsmAttachment] }");

        // --- SKU ---
        add("GET", "/api/agent/sku/fields", "SKU", "List available SKU fields.", "{ count, data:[string] }");
        add("GET", "/api/agent/sku/search", "SKU",
            "Look up SKU records by item number(s) from the SKU cache.",
            "{ count, data:[{field:value}] }",
            p("items", "csv", true, "Comma-separated SKU/item numbers"));

        // --- Reports data ---
        add("GET", "/api/agent/ecn-report/data", "Reports",
            "ECN report dataset (KPIs/SLA rows) from the cached report data.",
            "{ ...ecn data map... }");
        add("GET", "/api/agent/ecn-report/kpi-classifications", "Reports",
            "ECN KPI classification reference entries.",
            "{ count, data:[{...}] }");
        add("GET", "/api/agent/returns/data", "Reports",
            "Returns/rejection events in a date range.",
            "{ count, data:[{eventId,...}] }",
            p("from", "date(YYYY-MM-DD)", false, "Range start"),
            p("to", "date(YYYY-MM-DD)", false, "Range end"));
        add("GET", "/api/agent/returns/periods", "Reports",
            "Available frozen returns snapshot periods.",
            "{ count, data:[{period,...}] }");
        add("GET", "/api/agent/returns/explain/{eventId}", "Reports",
            "Human-readable explanation of one returns event's classification.",
            "{ eventId, explanation }",
            p("eventId", "string", true, "Returns event id (path segment)"));
        add("GET", "/api/agent/overdue/data", "Reports",
            "Overdue-change tracker dataset with optional filters.",
            "{ ...overdue data map... }",
            p("minOver", "integer", false, "Min days overdue"),
            p("maxOver", "integer", false, "Max days overdue"),
            p("classifications", "csv", false, "Classification filter (CSV)"));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -Dtest=AgentApiControllerTest,AgentEndpointRegistryTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(agent-api): SDSM/SKU/ECN-report/returns/overdue wrappers + catalog"
```

---

## Task 10: File download wrappers (FILES bucket, 503 on upstream down)

**Files:**
- Modify: `AgentApiController.java`, `AgentEndpointRegistry.java`, `AgentApiControllerTest.java`

- [ ] **Step 1: Write the failing test (append)**

```java
    @Test
    void filesListDelegates() throws Exception {
        keyOk();
        AgileItemFilesClient.FilesResult fr = new AgileItemFilesClient.FilesResult();
        fr.found = true;
        when(agileItemFilesClient.listFiles("ABC")).thenReturn(fr);
        mvc.perform(get("/api/agent/files/list").header("X-API-Key", "k").param("item", "ABC"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.found").value(true));
    }

    @Test
    void filesDownloadReturnsBytes() throws Exception {
        keyOk();
        AgileItemFilesClient.FileStream fs = new AgileItemFilesClient.FileStream();
        fs.httpStatus = 200; fs.bytes = new byte[]{1,2,3};
        fs.filename = "spec.pdf"; fs.contentType = "application/pdf";
        when(agileItemFilesClient.fetchFile("ABC", "spec.pdf")).thenReturn(fs);
        mvc.perform(get("/api/agent/files/download").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "spec.pdf"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("spec.pdf")));
    }

    @Test
    void filesDownload503WhenUpstreamDown() throws Exception {
        keyOk();
        AgileItemFilesClient.FileStream fs = new AgileItemFilesClient.FileStream();
        fs.httpStatus = 0; fs.error = "connection refused";
        when(agileItemFilesClient.fetchFile(anyString(), any())).thenReturn(fs);
        mvc.perform(get("/api/agent/files/download").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "x.pdf"))
           .andExpect(status().is(503));
    }

    @Test
    void filesDownloadUsesFilesBucket() throws Exception {
        when(guard.check(anyString())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.OK, "atwork"));
        when(rateLimiter.tryAcquire("atwork", AgentRateLimiter.Bucket.FILES))
            .thenReturn(new AgentRateLimiter.Decision(false, 30));
        mvc.perform(get("/api/agent/files/download").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "x.pdf"))
           .andExpect(status().is(429));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentApiControllerTest test`
Expected: FAIL 404.

- [ ] **Step 3: Add wrapper methods**

```java
    // ---- Files (attachment metadata + bytes; FILES rate bucket) ----

    @GetMapping("/files/list")
    public ResponseEntity<?> filesList(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                       @RequestParam("item") String item) {
        ResponseEntity<Map<String, Object>> deny =
            gate(apiKey, AgentRateLimiter.Bucket.FILES, "/api/agent/files/list", "item=" + item);
        if (deny != null) return deny;
        try {
            AgileItemFilesClient.FilesResult fr = agileItemFilesClient.listFiles(item);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("item", item);
            body.put("found", fr.found);
            List<Map<String, Object>> files = new ArrayList<>();
            if (fr.files != null) {
                for (AgileItemFilesClient.FileMeta m : fr.files) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("fileName", m.fileName);
                    f.put("fileDescription", m.fileDescription);
                    f.put("fileType", m.fileType);
                    f.put("byteSize", m.byteSize);
                    f.put("contentAvailable", m.contentAvailable);
                    if (m.fileName != null) {
                        f.put("downloadUrl", "/api/agent/files/download?item="
                            + enc(item) + "&name=" + enc(m.fileName));
                    }
                    files.add(f);
                }
            }
            body.put("files", files);
            if (fr.error != null) body.put("error", fr.error);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            LOG.warning("[AGENT] files/list failed: " + e);
            return err(503, "document service temporarily unavailable");
        }
    }

    @GetMapping("/files/download")
    public ResponseEntity<?> filesDownload(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam("item") String item,
                                           @RequestParam(value = "name", required = false) String name) {
        ResponseEntity<Map<String, Object>> deny =
            gate(apiKey, AgentRateLimiter.Bucket.FILES, "/api/agent/files/download", "item=" + item + " name=" + name);
        if (deny != null) return deny;

        AgileItemFilesClient.FileStream fs;
        try {
            fs = agileItemFilesClient.fetchFile(item, name);
        } catch (Exception e) {
            LOG.warning("[AGENT] files/download failed: " + e);
            return err(503, "document service temporarily unavailable");
        }
        if (fs.httpStatus == 200 && fs.bytes != null) {
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setContentType(parseContentType(fs.contentType));
            h.setContentDisposition(org.springframework.http.ContentDisposition
                    .builder("attachment").filename(fs.filename == null ? "download" : fs.filename).build());
            h.setContentLength(fs.bytes.length);
            return new ResponseEntity<>(fs.bytes, h, org.springframework.http.HttpStatus.OK);
        }
        if (fs.httpStatus == 404) return err(404, "attachment not found");
        if (fs.httpStatus == 422) return err(422, "attachment content not available in this environment");
        if (fs.httpStatus == 400) return err(400, "bad attachment request");
        // 0 / 5xx = upstream (plm-agile-service) unreachable or errored.
        return err(503, "document service temporarily unavailable"
            + (fs.error != null ? " (" + fs.error + ")" : ""));
    }

    @GetMapping("/sdsm/file/{attachId}")
    public ResponseEntity<?> sdsmFile(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                      @PathVariable long attachId,
                                      @RequestParam(value = "fileName", required = false) String fileName,
                                      @RequestParam(value = "rev", required = false) String rev,
                                      @RequestParam(value = "parentNumber", required = false) String parentNumber) {
        ResponseEntity<Map<String, Object>> deny =
            gate(apiKey, AgentRateLimiter.Bucket.FILES, "/api/agent/sdsm/file/" + attachId, null);
        if (deny != null) return deny;
        try {
            SdsmFileService.Result r = sdsmFileService.fetch(parentNumber, rev, fileName, attachId);
            if (r == null || r.bytes == null) return err(404, "SDSM file not found");
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setContentType(parseContentType(r.contentType));
            h.setContentDisposition(org.springframework.http.ContentDisposition
                    .builder("attachment").filename(r.filename == null ? "download" : r.filename).build());
            h.setContentLength(r.bytes.length);
            return new ResponseEntity<>(r.bytes, h, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            LOG.warning("[AGENT] sdsm/file failed: " + e);
            return err(503, "SDSM file service temporarily unavailable");
        }
    }
```

Add these helpers near `str(...)` (ported from `ObaController`):

```java
    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception e) { return s; }
    }

    private static org.springframework.http.MediaType parseContentType(String contentType) {
        if (contentType == null) return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        try {
            return org.springframework.http.MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        }
    }
```

- [ ] **Step 4: Add registry entries** (append)

```java
        // --- Files ---
        add("GET", "/api/agent/files/list", "Files",
            "List attachment files on an item/document (with per-file downloadUrl).",
            "{ item, found, files:[{fileName,fileDescription,fileType,byteSize,contentAvailable,downloadUrl}] }",
            p("item", "string", true, "Item/document number"));
        add("GET", "/api/agent/files/download", "Files",
            "Download one attachment file's bytes (proxied from the Agile document service).",
            "binary file bytes (Content-Disposition: attachment)",
            p("item", "string", true, "Item/document number"),
            p("name", "string", false, "File name from /files/list (omit for the item's primary file)"));
        add("GET", "/api/agent/sdsm/file/{attachId}", "Files",
            "Download an SDSM shop-floor document by attachment id.",
            "binary file bytes (Content-Disposition: attachment)",
            p("attachId", "integer", true, "Attachment id (path segment)"),
            p("fileName", "string", false, "Original file name (helps the file lookup)"),
            p("rev", "string", false, "Document revision"),
            p("parentNumber", "string", false, "Parent document number"));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -Dtest=AgentApiControllerTest,AgentEndpointRegistryTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(agent-api): file download wrappers (FILES bucket, 503 on upstream down)"
```

---

## Task 11: Catalog↔controller parity test

Guarantees the catalog can never advertise a path the controller doesn't serve, or omit one it does.

**Files:**
- Create: `src/test/java/com/sandisk/plm/tracker/controller/AgentApiCatalogParityTest.java`

- [ ] **Step 1: Write the test**

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.AgentEndpointRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry (which backs /catalog) must list exactly the paths the controller
 * maps — no more, no less — excluding /catalog itself. Pure reflection, no Spring
 * context needed.
 */
public class AgentApiCatalogParityTest {

    @Test
    void registryPathsMatchControllerMappings() {
        Set<String> mapped = new LinkedHashSet<>();
        String base = "/api/agent";
        for (Method m : AgentApiController.class.getDeclaredMethods()) {
            String sub = subPath(m);
            if (sub == null) continue;
            String full = base + sub;
            if (full.equals("/api/agent/catalog")) continue;
            mapped.add(full);
        }
        Set<String> registered = new LinkedHashSet<>(new AgentEndpointRegistry().paths());
        assertEquals(new java.util.TreeSet<>(registered), new java.util.TreeSet<>(mapped),
            "Registry paths must equal controller-mapped paths (excluding /catalog)");
    }

    private static String subPath(Method m) {
        GetMapping g = AnnotatedElementUtils.findMergedAnnotation(m, GetMapping.class);
        if (g != null && g.value().length > 0) return g.value()[0];
        PostMapping p = AnnotatedElementUtils.findMergedAnnotation(m, PostMapping.class);
        if (p != null && p.value().length > 0) return p.value()[0];
        return null;
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn -q -Dtest=AgentApiCatalogParityTest test`
Expected: PASS. If it fails, the assertion message lists the mismatched paths — fix the registry or the mapping so they match exactly, then re-run.

- [ ] **Step 3: Run the whole agent suite together**

Run: `mvn -q -Dtest='Agent*,AuthFilterTest' test`
Expected: PASS (all guard, limiter, registry, controller, parity, and auth-filter tests).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/sandisk/plm/tracker/controller/AgentApiCatalogParityTest.java
git commit -m "test(agent-api): assert catalog registry matches controller mappings"
```

---

## Task 12: What's New entry, full build, local smoke test, staging

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Add the changelog entry (top of `WHATS_NEW_RELEASES`)**

Open `src/main/resources/static/whats-new.js`, find the `WHATS_NEW_RELEASES` array, and insert a new object as the FIRST element (match the existing entry shape — check a neighbor for exact keys):

```javascript
  {
    date: "2026-07-08",
    title: "Agent API — read-only gateway for AI agents",
    items: [
      { type: "new", text: "New /api/agent/* surface lets an approved AI agent (Atwork) discover endpoints via /api/agent/catalog and retrieve PLM data + document files with a single API key." },
      { type: "new", text: "Deny-by-default: no key configured means the whole surface is closed; per-key rate limits and full activity-log auditing on every call." },
    ],
  },
```

- [ ] **Step 2: Full build (Corretto 11)**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q clean package
```
Expected: `BUILD SUCCESS`; `target/plm-field-tracker-1.0.1.jar` produced; all tests (including the full existing suite) green.

- [ ] **Step 3: Local smoke test — start the app with a scratch key**

The local run uses an external config. Add a scratch key just for this smoke test (do NOT commit it):
```bash
cd ~/Documents/plm-toolkit\ 2
printf '\napp.agent.api-keys=smoke-test-key-123\napp.agent.api-key-labels=smoke\n' >> ./config/application.properties
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ./plm-field-tracker-1.0.1.jar
java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties >/tmp/agent-smoke.log 2>&1 &
sleep 25
```

- [ ] **Step 4: Exercise catalog, a data query, a file list, and the 401/503 paths**

Run:
```bash
echo "== 401 (no key) =="; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8090/api/agent/catalog
echo "== catalog (with key) =="; curl -s -H "X-API-Key: smoke-test-key-123" http://localhost:8090/api/agent/catalog | head -c 400; echo
echo "== a data query =="; curl -s -H "X-API-Key: smoke-test-key-123" "http://localhost:8090/api/agent/items/columns" | head -c 200; echo
echo "== files/list =="; curl -s -H "X-API-Key: smoke-test-key-123" "http://localhost:8090/api/agent/files/list?item=SOME-KNOWN-ITEM" | head -c 300; echo
echo "== audit lines =="; grep AGENT_API ~/Documents/plm-toolkit\ 2/data/activity-log.jsonl | tail -3
```
Expected: first call prints `401`; catalog returns JSON with `version`, `rateLimitContract`, and an `endpoints` array; `items/columns` returns a columns array; `files/list` returns JSON (found=true/false depending on whether plm-agile-service is running locally — a 503-style "document service temporarily unavailable" is acceptable locally since 8081 isn't up); the audit grep shows `agent:smoke` `AGENT_API` lines.

- [ ] **Step 5: Stop the app and remove the scratch key**

Run:
```bash
pkill -f plm-field-tracker-1.0.1.jar
cd ~/git/plm-field-tracker
# remove the two scratch lines we appended to the LOCAL external config (not in git)
sed -i '' '/app.agent.api-keys=smoke-test-key-123/d;/app.agent.api-key-labels=smoke/d' ~/Documents/plm-toolkit\ 2/config/application.properties
```
Expected: process stops; the scratch key lines are gone from the local external config. (The key was never in git — `application.properties` in the repo stays blank.)

- [ ] **Step 6: Commit the changelog and copy the JAR to staging**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): Agent API read-only gateway"
```

Then stage the artifact (per project CLAUDE.md — staging only, never the live folder). If `/Volumes/uls-ep-aglipccb/` is not mounted, STOP and tell Vikas to mount it; do not skip silently:
```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
stat -f "%z" ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/plm-field-tracker-1.0.1.jar
```
Expected: both `stat` sizes match (confirms the SMB write completed).

- [ ] **Step 7: Hand-off note (not a code step)**

The real key(s) are generated in the QA server's **external** config only (`app.agent.api-keys`), never committed. Give Harsh the catalog URL + key **outside Teams chat**, and call out the rate-limit contract (a 429 means the data was not returned; the agent must surface incompleteness to its end user). Vikas does the staging→live cutover.

---

## Self-Review

**Spec coverage:**
- Auth (X-API-Key, fail-closed, multi-key rotation) → Tasks 1, 2, 5. ✓
- Discovery catalog + rateLimitContract → Tasks 4, 6. ✓
- v1 allowlist (Items, Changes, BOM, Rev, ECO Timeline, Change Review, Documents, Files, SKU, Reports) → Tasks 7-10 (every row of the spec table has a wrapper). ✓
- Rate limiting (per-key, DATA vs FILES buckets, 429 + Retry-After) → Tasks 3, 6, 10. ✓
- Incomplete-response signalling (self-describing 429 body + endUserMessage; no partial 200s; result-cap `truncated` flag surfaced) → Task 6 (429 body), Task 7 (`truncated` from SearchResult/RunResult passed through). ✓
- Audit via ActivityLogger → Task 6 (`gate` logs every allowed call). ✓
- Uniform error envelope + no internal hostnames → Task 6 (`err`), Task 10 (generic upstream messages). ✓
- AuthFilter exemption → Task 5. ✓
- Config blank-in-git → Task 1; key handed off outside chat → Task 12 step 7. ✓
- Testing (guard, registry/catalog parity, limiter, controller, 429 contract) → Tasks 2,3,4,6,11. ✓
- Rollout (What's New, Corretto 11 build, staging-only) → Task 12. ✓
- HTTPS prerequisite → noted in spec as non-blocking; not a task here. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code; every test shows real assertions.

**Type consistency:** `AgentApiKeyGuard.check()` returns `CheckResult{result,label}` — used consistently in guard test and controller `gate`. `AgentRateLimiter.tryAcquire(String,Bucket)` returns `Decision{allowed,retryAfterSeconds}` — consistent across limiter test and controller. `AgentEndpoint`/`Param` field names (`method,path,domain,description,returns,params` / `name,type,required,description`) consistent across registry, catalog rendering, and parity test. Delegation signatures match the extracted reference (e.g. `changeQueryService.search(...)→SearchResult`, `bomDataService.explodeMultiple(List,int,BomFilters)`, `sdsmFileService.fetch(parentNumber,rev,originalFilename,attachId)→Result`, `AgileItemFilesClient.FileStream{bytes,filename,contentType,httpStatus,error}`).

**Note for the implementer:** the `ChangeQueryService.SearchResult` public fields (`totalCount,uniqueItems,queryTimeMs,truncated,dbOffline,dataAsOf`) and `getResults()` are used in Task 7's response map and its test constructor — confirm the field access modifiers when you reach that task; if any are private with getters, switch to the getters (the constructor arg order in the test is `(results,totalCount,uniqueItems,queryTimeMs,truncated,dbOffline,dataAsOf)` per the extracted reference).
