# Returns Tracker: Snapshots + AI-vs-Audit Classification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add prior-period (week/month/quarter) frozen snapshots reachable from a dropdown, and expose AI-inferred vs audit-enforced return classifications with an agreement metric — on the ECN Returns Tracker.

**Architecture:** Extend `RejectionTrackerService` to derive two per-event classifications (`aiCategory`, `auditCategory`) plus `categorySource`, compute aggregates for either source, and an AI↔audit agreement block. A new `RejectionSnapshotService` freezes computed periods to `data/ecn-report/rejection-snapshots/*.json` (written by the scheduler + a one-time backfill from the retained cache). The controller gains `/periods`, a `period=` loader on `/data`, and returns both aggregate sets + agreement. The frontend adds a period dropdown, an audit/AI toggle, an agreement tile, a mismatch panel, and a go-live-default range preset. Excel export is corrected to the agreed taxonomy and both classifications.

**Tech Stack:** Java 11 (Spring Boot, Jackson, Apache POI), JUnit 5 (jupiter), vanilla JS, Maven (`plm-field-tracker-1.0.1`).

**Audit go-live date:** `2026-06-24` (config `app.returns.audit-golive-date`).

---

## File structure

| File | Responsibility | Change |
|---|---|---|
| `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java` | Classification derivation, aggregates, agreement, date span | Modify |
| `src/main/java/com/sandisk/plm/tracker/service/RejectionSnapshotService.java` | Freeze/list/read period snapshots + backfill | Create |
| `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerScheduler.java` | Write snapshots after weekly/monthly send | Modify |
| `src/main/java/com/sandisk/plm/tracker/controller/RejectionTrackerController.java` | `/periods`, `period=` load, both aggregate sets, `/snapshot` | Modify |
| `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerEmailService.java` | Excel: aiCategory/auditCategory/source columns, taxonomy fix | Modify |
| `src/main/resources/application.properties` | `app.returns.audit-golive-date` | Modify |
| `src/main/resources/static/returnstracker.js` | Period dropdown, classification toggle, agreement UI, presets | Modify |
| `src/main/resources/static/index.html` | Period select, toggle buttons, agreement/mismatch containers | Modify |
| `src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java` | Unit tests for derivation/agreement | Create |
| `src/test/java/com/sandisk/plm/tracker/service/RejectionSnapshotServiceTest.java` | Unit tests for snapshot IO/backfill | Create |

**Constants introduced (use these exact names everywhere):**
- `RejectionTrackerService.NO_AUDIT_CODE = "No audit code"`
- `RejectionTrackerService.AUDIT_CATEGORY_ORDER` (list, ends with `NO_AUDIT_CODE`)
- Event fields: `aiCategory`, `auditCategory`, `categorySource` (values `audit`/`ai`/`owner`/`unknown`)
- Aggregate method: `getAggregatesFor(List<Map<String,Object>> evs, String field, List<String> order)`
- Agreement method: `computeAgreement(List<Map<String,Object>> evs)` → keys `coded`, `matched`, `agreementPct` (Integer or null), `mismatches`

---

## Phase 1 — Per-event classification derivation

### Task 1: Add audit taxonomy constants + view helpers

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java`:

```java
package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RejectionClassificationTest {

    @Test
    void aiView_aliasesLegacyAndFallsBackToUnknown() {
        assertEquals("Wrong Information", RejectionTrackerService.aiView("Wrong Information"));
        assertEquals("Returned by Owner", RejectionTrackerService.aiView("Returned By Requestor"));
        assertEquals("Unknown", RejectionTrackerService.aiView("Ambiguous Request"));
        assertEquals("Unknown", RejectionTrackerService.aiView(null));
        assertEquals("Unknown", RejectionTrackerService.aiView("Something Else"));
    }

    @Test
    void auditView_prefersCodeThenOwnerThenNoCode() {
        assertEquals("Wrong Information", RejectionTrackerService.auditView("WI: pn missing", "Unknown"));
        assertEquals("Insufficient Information",
                RejectionTrackerService.auditView("insufficient information: unclear", "Wrong Information"));
        assertEquals("Returned by Owner", RejectionTrackerService.auditView("no prefix here", "Returned By Requestor"));
        assertEquals(RejectionTrackerService.NO_AUDIT_CODE,
                RejectionTrackerService.auditView("just a free comment", "Wrong Information"));
        assertEquals(RejectionTrackerService.NO_AUDIT_CODE,
                RejectionTrackerService.auditView(null, null));
    }

    @Test
    void categorySource_classifiesOrigin() {
        assertEquals("audit", RejectionTrackerService.categorySource("WI: x", "Unknown"));
        assertEquals("owner", RejectionTrackerService.categorySource("free text", "Returned By Requestor"));
        assertEquals("ai", RejectionTrackerService.categorySource("free text", "Wrong Information"));
        assertEquals("unknown", RejectionTrackerService.categorySource("free text", "Ambiguous Request"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RejectionClassificationTest test`
Expected: FAIL — `aiView` / `auditView` / `categorySource` / `NO_AUDIT_CODE` do not exist (compile error).

- [ ] **Step 3: Add the constants and helpers**

In `RejectionTrackerService.java`, after the `CATEGORIES` list (ends line ~83), add:

```java
    /** Audit view has no AI fallback — uncoded returns bucket here. */
    public static final String NO_AUDIT_CODE = "No audit code";

    /** Category seed order for the audit-enforced view (adds the No-audit-code bucket). */
    public static final List<String> AUDIT_CATEGORY_ORDER = Arrays.asList(
        "Returned by Owner",
        "Incomplete Documentation",
        "Insufficient Information",
        "Wrong Information",
        "Duplicate Request",
        "Return Requested",
        NO_AUDIT_CODE
    );
```

Then add three static helpers near `classifyByCommentPrefix` (after line ~316):

```java
    /** AI-inferred view: alias legacy names, keep active categories, else Unknown. */
    public static String aiView(String rawAiCategory) {
        if (rawAiCategory == null) return "Unknown";
        String aliased = LEGACY_CATEGORY_ALIAS.getOrDefault(rawAiCategory, rawAiCategory);
        return CATEGORIES.contains(aliased) ? aliased : "Unknown";
    }

    /** Audit-enforced view: comment prefix wins; else structural owner-return; else no code. */
    public static String auditView(String comment, String rawAiCategory) {
        String code = classifyByCommentPrefix(comment);
        if (code != null) return code;
        if ("Returned by Owner".equals(aiView(rawAiCategory))) return "Returned by Owner";
        return NO_AUDIT_CODE;
    }

    /** Which source is authoritative for an event: audit | owner | ai | unknown. */
    public static String categorySource(String comment, String rawAiCategory) {
        if (classifyByCommentPrefix(comment) != null) return "audit";
        String av = aiView(rawAiCategory);
        if ("Returned by Owner".equals(av)) return "owner";
        return "Unknown".equals(av) ? "unknown" : "ai";
    }
```

Note: `LEGACY_CATEGORY_ALIAS` is currently `private static`. Leave its visibility; the helpers are in the same class.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RejectionClassificationTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java
git commit -m "feat(returns): add aiView/auditView/categorySource classification helpers"
```

---

### Task 2: Populate the three fields in enrichEvent

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java:273-305`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java`

- [ ] **Step 1: Add the failing test**

Append to `RejectionClassificationTest.java` (inside the class):

```java
    @Test
    void enrichEvent_setsAllThreeClassificationFields() throws Exception {
        RejectionTrackerService svc = new RejectionTrackerService();
        java.util.Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("ts", "2026-06-28T10:00:00");
        e.put("ecnNumber", "ECN-1");
        e.put("comment", "WI: input PN doesn't exist");
        e.put("category", "Insufficient Information"); // AI disagrees with the human code

        java.lang.reflect.Method m = RejectionTrackerService.class.getDeclaredMethod(
            "enrichEvent", java.util.Map.class, java.util.Map.class, java.util.Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> out = (java.util.Map<String, Object>) m.invoke(
            svc, e, java.util.Collections.emptyMap(), java.util.Collections.emptyMap());

        assertEquals("Insufficient Information", out.get("aiCategory"));
        assertEquals("Wrong Information", out.get("auditCategory"));
        assertEquals("audit", out.get("categorySource"));
        assertEquals("Wrong Information", out.get("category")); // existing default unchanged
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RejectionClassificationTest#enrichEvent_setsAllThreeClassificationFields test`
Expected: FAIL — `aiCategory` is null.

- [ ] **Step 3: Implement**

In `enrichEvent(...)`, immediately after `out.put("category", resolved);` (line ~297) insert:

```java
        // AI-vs-audit split (Jul 2026 — Jimmy/Noraida). Persist both classifications
        // and the authoritative source alongside the existing default `category`.
        out.put("aiCategory", aiView(aiCategory));
        out.put("auditCategory", auditView(comment, aiCategory));
        out.put("categorySource", categorySource(comment, aiCategory));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RejectionClassificationTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java
git commit -m "feat(returns): populate aiCategory/auditCategory/categorySource on each event"
```

---

## Phase 2 — Aggregates by source + agreement

### Task 3: getAggregatesFor(field, order)

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java`

- [ ] **Step 1: Add the failing test**

Append to `RejectionClassificationTest.java`:

```java
    private java.util.Map<String, Object> ev(String field, String value) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ts", "2026-06-28T10:00:00");
        m.put("ecnNumber", "ECN-" + value.hashCode());
        m.put("requestor", "Req A (1)");
        m.put(field, value);
        return m;
    }

    @Test
    void getAggregatesFor_bucketsByFieldWithFallback() {
        RejectionTrackerService svc = new RejectionTrackerService();
        java.util.List<java.util.Map<String, Object>> evs = java.util.Arrays.asList(
            ev("auditCategory", "Wrong Information"),
            ev("auditCategory", "Wrong Information"),
            ev("auditCategory", "No audit code"),
            ev("auditCategory", "totally-unmapped") // -> fallback (last in order)
        );
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> agg = svc.getAggregatesFor(
            evs, "auditCategory", RejectionTrackerService.AUDIT_CATEGORY_ORDER);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> cats = (java.util.Map<String, Integer>) agg.get("categories");
        assertEquals(2, cats.get("Wrong Information").intValue());
        assertEquals(2, cats.get("No audit code").intValue()); // real + fallback
        assertEquals(4, agg.get("totalEvents"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RejectionClassificationTest#getAggregatesFor_bucketsByFieldWithFallback test`
Expected: FAIL — `getAggregatesFor` not defined.

- [ ] **Step 3: Implement**

In `RejectionTrackerService.java`, refactor the existing `getAggregates(List)` so its body moves into a new source-parameterized method. Replace the current method signature line `public Map<String, Object> getAggregates(List<Map<String, Object>> evs) {` (line ~392) with:

```java
    /** Back-compat: aggregate by the default `category` field + active taxonomy. */
    public Map<String, Object> getAggregates(List<Map<String, Object>> evs) {
        return getAggregatesFor(evs, "category", CATEGORIES);
    }

    /**
     * Aggregate counts keyed on {@code field} (e.g. "auditCategory" / "aiCategory" /
     * "category"). Category counts are seeded from {@code order}; any event whose
     * value is null or not in {@code order} is bucketed into the LAST element of
     * {@code order}. All other rollups (product line/team/requestor/theme/trend)
     * are source-independent.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAggregatesFor(List<Map<String, Object>> evs,
                                                String field, List<String> order) {
```

Then inside the (now-renamed) body:
- Change the seed loop `for (String c : CATEGORIES) categoryCounts.put(c, 0);` to `for (String c : order) categoryCounts.put(c, 0);`
- Change `String cat = (String) e.get("category");` to:
  ```java
            String cat = (String) e.get(field);
            String fallback = order.get(order.size() - 1);
            if (cat == null || !categoryCounts.containsKey(cat)) cat = fallback;
  ```
- Everything else in the method stays the same (the `selfReturn` check on `"Returned by Owner".equals(cat)` still works because "Returned by Owner" is present in both `CATEGORIES` and `AUDIT_CATEGORY_ORDER`).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RejectionClassificationTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java
git commit -m "feat(returns): getAggregatesFor(field, order) for AI/audit aggregate sets"
```

---

### Task 4: computeAgreement

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java`

- [ ] **Step 1: Add the failing test**

Append to `RejectionClassificationTest.java`:

```java
    private java.util.Map<String, Object> pair(String ecn, String ai, String audit) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ecnNumber", ecn);
        m.put("ts", "2026-06-28T10:00:00");
        m.put("comment", audit + ": note");
        m.put("aiCategory", ai);
        m.put("auditCategory", audit);
        return m;
    }

    @Test
    void computeAgreement_countsOnlyDoublyCodedEvents() {
        RejectionTrackerService svc = new RejectionTrackerService();
        java.util.List<java.util.Map<String, Object>> evs = java.util.Arrays.asList(
            pair("E1", "Wrong Information", "Wrong Information"),       // match
            pair("E2", "Insufficient Information", "Wrong Information"),// mismatch
            pair("E3", "Unknown", "Wrong Information"),                 // ai not coded -> excluded
            pair("E4", "Wrong Information", "No audit code")            // audit not coded -> excluded
        );
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> a = svc.computeAgreement(evs);
        assertEquals(2, a.get("coded"));
        assertEquals(1, a.get("matched"));
        assertEquals(50, a.get("agreementPct"));
        @SuppressWarnings("unchecked")
        java.util.List<Object> mm = (java.util.List<Object>) a.get("mismatches");
        assertEquals(1, mm.size());
    }

    @Test
    void computeAgreement_nullPctWhenNoCodedEvents() {
        RejectionTrackerService svc = new RejectionTrackerService();
        java.util.List<java.util.Map<String, Object>> evs = java.util.Arrays.asList(
            pair("E1", "Wrong Information", "No audit code"));
        java.util.Map<String, Object> a = svc.computeAgreement(evs);
        assertEquals(0, a.get("coded"));
        assertNull(a.get("agreementPct"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RejectionClassificationTest#computeAgreement_countsOnlyDoublyCodedEvents test`
Expected: FAIL — `computeAgreement` not defined.

- [ ] **Step 3: Implement**

Add to `RejectionTrackerService.java` (after `getAggregatesFor`):

```java
    /** The five audit reason-code categories (exclude structural owner/unknown). */
    private static final Set<String> CODED_CATEGORIES = new HashSet<>(Arrays.asList(
        "Incomplete Documentation", "Insufficient Information", "Wrong Information",
        "Duplicate Request", "Return Requested"));

    /**
     * AI↔audit agreement over events that carry a real reason code in BOTH sources.
     * Returns coded (denominator), matched, agreementPct (Integer 0-100 or null when
     * coded==0), and mismatches (capped at 100) for the drill-down panel.
     */
    public Map<String, Object> computeAgreement(List<Map<String, Object>> evs) {
        int coded = 0, matched = 0;
        List<Map<String, Object>> mismatches = new ArrayList<>();
        for (Map<String, Object> e : evs) {
            String ai = (String) e.get("aiCategory");
            String audit = (String) e.get("auditCategory");
            if (!CODED_CATEGORIES.contains(ai) || !CODED_CATEGORIES.contains(audit)) continue;
            coded++;
            if (ai.equals(audit)) {
                matched++;
            } else if (mismatches.size() < 100) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ecnNumber", e.get("ecnNumber"));
                m.put("ts", e.get("ts"));
                m.put("aiCategory", ai);
                m.put("auditCategory", audit);
                m.put("comment", e.get("comment"));
                mismatches.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coded", coded);
        out.put("matched", matched);
        out.put("agreementPct", coded > 0 ? (Integer) Math.round(100f * matched / coded) : null);
        out.put("mismatches", mismatches);
        return out;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RejectionClassificationTest test`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java
git commit -m "feat(returns): computeAgreement (AI vs audit reason-code match)"
```

---

### Task 5: Expose the cache event date span (for backfill)

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java`

- [ ] **Step 1: Add the failing test**

Append to `RejectionClassificationTest.java`:

```java
    @Test
    void eventDateSpan_nullWhenEmpty() {
        RejectionTrackerService svc = new RejectionTrackerService();
        assertNull(svc.getEventDateSpan()); // no cache loaded in a bare instance
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RejectionClassificationTest#eventDateSpan_nullWhenEmpty test`
Expected: FAIL — `getEventDateSpan` not defined.

- [ ] **Step 3: Implement**

Add to `RejectionTrackerService.java` (after `getLastRunTs`):

```java
    /** Min/max event day present in the cache, as {min,max}; null when empty. */
    public LocalDate[] getEventDateSpan() {
        loadCacheIfStale();
        LocalDate min = null, max = null;
        for (Map<String, Object> e : events.values()) {
            String ts = (String) e.get("ts");
            if (ts == null || ts.length() < 10) continue;
            LocalDate d = LocalDate.parse(ts.substring(0, 10));
            if (min == null || d.isBefore(min)) min = d;
            if (max == null || d.isAfter(max)) max = d;
        }
        return min == null ? null : new LocalDate[]{min, max};
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RejectionClassificationTest test`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java src/test/java/com/sandisk/plm/tracker/service/RejectionClassificationTest.java
git commit -m "feat(returns): expose cache event date span for snapshot backfill"
```

---

## Phase 3 — Snapshot service

### Task 6: RejectionSnapshotService — period id/label helpers

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/RejectionSnapshotService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RejectionSnapshotServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/sandisk/plm/tracker/service/RejectionSnapshotServiceTest.java`:

```java
package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

public class RejectionSnapshotServiceTest {

    @Test
    void periodIdsAndLabels() {
        assertEquals("month-2026-06", RejectionSnapshotService.monthId(LocalDate.of(2026, 6, 15)));
        assertEquals("quarter-2026-Q2", RejectionSnapshotService.quarterId(LocalDate.of(2026, 6, 15)));
        assertEquals("week-2026-06-28", RejectionSnapshotService.weekId(LocalDate.of(2026, 6, 28)));

        assertEquals("June 2026", RejectionSnapshotService.labelFor("month-2026-06"));
        assertEquals("Q2 2026", RejectionSnapshotService.labelFor("quarter-2026-Q2"));
        assertEquals("Week ending Jun 28, 2026", RejectionSnapshotService.labelFor("week-2026-06-28"));
    }

    @Test
    void quarterBoundsAreInclusive() {
        LocalDate[] b = RejectionSnapshotService.quarterBounds(2026, 2);
        assertEquals(LocalDate.of(2026, 4, 1), b[0]);
        assertEquals(LocalDate.of(2026, 6, 30), b[1]);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RejectionSnapshotServiceTest test`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Create the class with static helpers**

Create `src/main/java/com/sandisk/plm/tracker/service/RejectionSnapshotService.java`:

```java
package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Freezes computed Returns Tracker periods (week/month/quarter) to immutable JSON
 * files so leadership can revisit prior reports from a dropdown even after the
 * rolling 90-day source window has aged the underlying ECNs out of Agile.
 *
 * Files live in {@code data/ecn-report/rejection-snapshots/}:
 *   week-YYYY-MM-DD.json | month-YYYY-MM.json | quarter-YYYY-QN.json
 * Each holds period bounds, both aggregate sets (audit + AI), the agreement block,
 * the enriched events, and the narrative (best-effort).
 */
@Service
public class RejectionSnapshotService {

    private static final Logger logger = Logger.getLogger(RejectionSnapshotService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DATA_DIR = "./data/ecn-report";
    private static final String SNAP_DIR = DATA_DIR + "/rejection-snapshots";
    private static final String BACKFILL_MARKER = SNAP_DIR + "/.backfilled";

    private static final DateTimeFormatter WEEK_LABEL = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy");

    @Autowired private RejectionTrackerService rejectionService;

    // ---- id / label / bounds helpers (static, pure) ----

    public static String monthId(LocalDate d) {
        return "month-" + d.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
    public static String quarterId(LocalDate d) {
        int q = (d.getMonthValue() - 1) / 3 + 1;
        return "quarter-" + d.getYear() + "-Q" + q;
    }
    public static String weekId(LocalDate weekEnding) {
        return "week-" + weekEnding.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String labelFor(String id) {
        if (id.startsWith("month-")) {
            LocalDate d = LocalDate.parse(id.substring(6) + "-01");
            return d.format(MONTH_LABEL);
        }
        if (id.startsWith("quarter-")) {
            String[] p = id.substring(8).split("-Q");
            return "Q" + p[1] + " " + p[0];
        }
        if (id.startsWith("week-")) {
            LocalDate d = LocalDate.parse(id.substring(5));
            return "Week ending " + d.format(WEEK_LABEL);
        }
        return id;
    }

    public static String typeOf(String id) {
        if (id.startsWith("month-")) return "month";
        if (id.startsWith("quarter-")) return "quarter";
        if (id.startsWith("week-")) return "week";
        return "unknown";
    }

    /** Inclusive [start,end] for quarter n (1-4) of a year. */
    public static LocalDate[] quarterBounds(int year, int q) {
        int startMonth = (q - 1) * 3 + 1;
        LocalDate start = LocalDate.of(year, startMonth, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        return new LocalDate[]{start, end};
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RejectionSnapshotServiceTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionSnapshotService.java src/test/java/com/sandisk/plm/tracker/service/RejectionSnapshotServiceTest.java
git commit -m "feat(returns): RejectionSnapshotService period id/label/bounds helpers"
```

---

### Task 7: Build + write + read a snapshot

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionSnapshotService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RejectionSnapshotServiceTest.java`

- [ ] **Step 1: Add the failing test**

Append to `RejectionSnapshotServiceTest.java` (add imports `java.nio.file.*` at top):

```java
    @Test
    void writeThenRead_roundTrips_andIsImmutable() throws Exception {
        // Point the service's data dir at a temp dir via reflection-free subclass hook:
        RejectionSnapshotService svc = new RejectionSnapshotService();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", "month-2099-01");
        payload.put("hello", "world");

        java.io.File dir = java.nio.file.Files.createTempDirectory("snaptest").toFile();
        java.io.File f = new java.io.File(dir, "month-2099-01.json");
        // writeSnapshotFile is a package-visible helper that takes an explicit File.
        boolean wrote = svc.writeSnapshotFile(f, payload);
        assertTrue(wrote);
        boolean again = svc.writeSnapshotFile(f, payload); // immutable: no overwrite
        assertFalse(again);

        java.util.Map<String, Object> back = svc.readSnapshotFile(f);
        assertEquals("world", back.get("hello"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RejectionSnapshotServiceTest#writeThenRead_roundTrips_andIsImmutable test`
Expected: FAIL — `writeSnapshotFile` / `readSnapshotFile` not defined.

- [ ] **Step 3: Implement file IO + payload builder + public API**

Add to `RejectionSnapshotService.java`:

```java
    // ---- file IO (package-visible for tests) ----

    boolean writeSnapshotFile(File f, Map<String, Object> payload) {
        if (f.exists()) return false; // immutable — never overwrite history
        try {
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, payload);
            return true;
        } catch (Exception e) {
            logger.warning("[RT-SNAP] write failed " + f + ": " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> readSnapshotFile(File f) {
        try {
            return mapper.readValue(f, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            logger.warning("[RT-SNAP] read failed " + f + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private File fileFor(String id) { return new File(SNAP_DIR, id + ".json"); }

    // ---- payload builder ----

    Map<String, Object> buildPayload(String id, LocalDate from, LocalDate to) {
        List<Map<String, Object>> events = rejectionService.getEventsInRange(from, to);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("type", typeOf(id));
        payload.put("label", labelFor(id));
        payload.put("from", from.toString());
        payload.put("to", to.toString());
        payload.put("generatedAt", Instant.now().toString());
        payload.put("events", events);
        payload.put("aggregates", rejectionService.getAggregatesFor(
            events, "auditCategory", RejectionTrackerService.AUDIT_CATEGORY_ORDER));
        payload.put("aggregatesAi", rejectionService.getAggregatesFor(
            events, "aiCategory", RejectionTrackerService.CATEGORIES));
        payload.put("agreement", rejectionService.computeAgreement(events));
        String windowKey = RejectionTrackerService.windowKeyForRange(from, to);
        payload.put("narrative", rejectionService.getNarrative(windowKey));
        return payload;
    }

    /** Freeze [from,to] as snapshot {@code id}; skips if it already exists. */
    public boolean writeSnapshot(String id, LocalDate from, LocalDate to) {
        File f = fileFor(id);
        if (f.exists()) return false;
        return writeSnapshotFile(f, buildPayload(id, from, to));
    }

    /** Load a frozen snapshot by id; null if absent. */
    public Map<String, Object> readSnapshot(String id) {
        File f = fileFor(id);
        if (!f.exists()) return null;
        return readSnapshotFile(f);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RejectionSnapshotServiceTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionSnapshotService.java src/test/java/com/sandisk/plm/tracker/service/RejectionSnapshotServiceTest.java
git commit -m "feat(returns): snapshot payload builder + immutable write/read"
```

---

### Task 8: listPeriods + backfill

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionSnapshotService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RejectionSnapshotServiceTest.java`

- [ ] **Step 1: Add the failing test**

Append to `RejectionSnapshotServiceTest.java`:

```java
    @Test
    void completeMonthsBetween_excludesPartialCurrentMonth() {
        java.util.List<java.time.YearMonth> ms = RejectionSnapshotService.completeMonthsBetween(
            java.time.LocalDate.of(2026, 1, 10),  // span min
            java.time.LocalDate.of(2026, 4, 5),   // span max
            java.time.LocalDate.of(2026, 4, 15));  // "today"
        // Jan (partial start still counts as a full calendar month present), Feb, Mar
        // are complete and before the current month (Apr); Apr excluded (current month).
        assertEquals(java.util.Arrays.asList(
            java.time.YearMonth.of(2026, 1),
            java.time.YearMonth.of(2026, 2),
            java.time.YearMonth.of(2026, 3)), ms);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RejectionSnapshotServiceTest#completeMonthsBetween_excludesPartialCurrentMonth test`
Expected: FAIL — `completeMonthsBetween` not defined.

- [ ] **Step 3: Implement listPeriods + enumeration + backfill**

Add to `RejectionSnapshotService.java` (add import `java.time.YearMonth`):

```java
    /** Calendar months from span-min..span-max, excluding the month containing `today`. */
    public static List<java.time.YearMonth> completeMonthsBetween(LocalDate min, LocalDate max, LocalDate today) {
        List<java.time.YearMonth> out = new ArrayList<>();
        java.time.YearMonth cur = java.time.YearMonth.from(min);
        java.time.YearMonth last = java.time.YearMonth.from(max);
        java.time.YearMonth currentMonth = java.time.YearMonth.from(today);
        while (!cur.isAfter(last)) {
            if (cur.isBefore(currentMonth)) out.add(cur);
            cur = cur.plusMonths(1);
        }
        return out;
    }

    /** Directory listing → period metadata, newest first. */
    public List<Map<String, Object>> listPeriods() {
        File dir = new File(SNAP_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 5);
            Map<String, Object> full = readSnapshotFile(f);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", id);
            meta.put("type", typeOf(id));
            meta.put("label", labelFor(id));
            meta.put("from", full.get("from"));
            meta.put("to", full.get("to"));
            meta.put("generatedAt", full.get("generatedAt"));
            out.add(meta);
        }
        // Sort by `to` date descending (fallback to id).
        out.sort((a, b) -> {
            String ta = String.valueOf(a.get("to")), tb = String.valueOf(b.get("to"));
            int c = tb.compareTo(ta);
            return c != 0 ? c : String.valueOf(b.get("id")).compareTo(String.valueOf(a.get("id")));
        });
        return out;
    }

    /** One-time: freeze all complete weeks (last 26), months, and quarters in the cache. */
    @PostConstruct
    public void backfillOnce() {
        try {
            if (new File(BACKFILL_MARKER).exists()) return;
            LocalDate[] span = rejectionService.getEventDateSpan();
            if (span == null) return;
            LocalDate today = LocalDate.now();

            // Months
            for (java.time.YearMonth ym : completeMonthsBetween(span[0], span[1], today)) {
                LocalDate from = ym.atDay(1);
                LocalDate to = ym.atEndOfMonth();
                writeSnapshot(monthId(from), from, to);
            }
            // Quarters (only fully-complete quarters before the current quarter)
            int curQ = (today.getMonthValue() - 1) / 3 + 1;
            for (int year = span[0].getYear(); year <= span[1].getYear(); year++) {
                for (int q = 1; q <= 4; q++) {
                    LocalDate[] qb = quarterBounds(year, q);
                    boolean complete = qb[1].isBefore(today)
                        && !(year == today.getYear() && q == curQ);
                    boolean inSpan = !qb[1].isBefore(span[0]) && !qb[0].isAfter(span[1]);
                    if (complete && inSpan) writeSnapshot(quarterId(qb[0]), qb[0], qb[1]);
                }
            }
            // Weeks — last 26 complete weeks ending on a Sunday, within span.
            LocalDate weekEnd = today.minusDays((today.getDayOfWeek().getValue() % 7) + 1); // last Sunday
            for (int i = 0; i < 26; i++) {
                LocalDate to = weekEnd.minusWeeks(i);
                LocalDate from = to.minusDays(6);
                if (to.isBefore(span[0])) break;
                if (from.isAfter(span[1])) continue;
                writeSnapshot(weekId(to), from, to);
            }
            new File(BACKFILL_MARKER).getParentFile().mkdirs();
            new File(BACKFILL_MARKER).createNewFile();
            logger.info("[RT-SNAP] Backfill complete: " + listPeriods().size() + " periods");
        } catch (Exception e) {
            logger.warning("[RT-SNAP] Backfill failed: " + e.getMessage());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RejectionSnapshotServiceTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionSnapshotService.java src/test/java/com/sandisk/plm/tracker/service/RejectionSnapshotServiceTest.java
git commit -m "feat(returns): listPeriods + one-time snapshot backfill from cache"
```

---

## Phase 4 — Scheduler wiring

### Task 9: Freeze snapshots after weekly/monthly send

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerScheduler.java`

- [ ] **Step 1: Add the autowire + calls (no unit test — scheduler is glue; covered by manual verification in Task 18)**

In `RejectionTrackerScheduler.java` add the field after line 35:

```java
    @Autowired private RejectionSnapshotService snapshotService;
```

In `weeklyEmail()`, replace `runAndSend("7d", from, to, "");` with:

```java
        runAndSend("7d", from, to, "");
        snapshotService.writeSnapshot(RejectionSnapshotService.weekId(to), from, to);
```

In `monthlyEmail()`, after the `runAndSend(key, from, to, "");` line add:

```java
        snapshotService.writeSnapshot(RejectionSnapshotService.monthId(from), from, to);
        // On quarter boundaries (Jan/Apr/Jul/Oct run → prior month is Mar/Jun/Sep/Dec),
        // also freeze the just-completed quarter.
        if (to.getMonthValue() % 3 == 0) {
            LocalDate[] qb = RejectionSnapshotService.quarterBounds(
                to.getYear(), (to.getMonthValue() - 1) / 3 + 1);
            snapshotService.writeSnapshot(RejectionSnapshotService.quarterId(qb[0]), qb[0], qb[1]);
        }
```

- [ ] **Step 2: Compile check**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerScheduler.java
git commit -m "feat(returns): scheduler freezes week/month/quarter snapshots after send"
```

---

## Phase 5 — Controller / API

### Task 10: Config property for go-live date

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the property**

Append to `src/main/resources/application.properties`:

```properties
# Returns Tracker — production go-live date of the audit that enforces
# classification-prefixed return comments (ID:/II:/WI:/DR:/RR:). Drives the
# default "Since audit go-live" range preset and the before/after split.
app.returns.audit-golive-date=2026-06-24
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat(returns): add app.returns.audit-golive-date config"
```

---

### Task 11: /data returns both aggregate sets + agreement + go-live; supports period=

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/RejectionTrackerController.java`

- [ ] **Step 1: Add the autowire + config value**

After line 40 (`@Autowired private ActivityLogger activityLogger;`) add:

```java
    @Autowired private RejectionSnapshotService snapshotService;
    @org.springframework.beans.factory.annotation.Value("${app.returns.audit-golive-date:2026-06-24}")
    private String auditGoLiveDate;
```

- [ ] **Step 2: Extend getData**

Replace the `getData` method signature + top of body (lines 78-97) so it accepts `period` and `classification`, and short-circuits to a frozen snapshot when `period` is set:

```java
    @GetMapping("/data")
    public Map<String, Object> getData(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String period,
                                        @RequestParam(required = false, defaultValue = "audit") String classification) {
        Map<String, Object> r = new LinkedHashMap<>();

        // Frozen snapshot path.
        if (period != null && !period.isEmpty() && !"live".equals(period)) {
            Map<String, Object> snap = snapshotService.readSnapshot(period);
            if (snap == null) {
                r.put("success", false);
                r.put("message", "Snapshot not found: " + period);
                return r;
            }
            snap.put("success", true);
            snap.put("frozen", true);
            snap.put("auditGoLiveDate", auditGoLiveDate);
            return snap;
        }

        if (!rejectionService.hasData()) {
            r.put("success", false);
            r.put("message", "No rejection data yet. Click Refresh to generate.");
            return r;
        }
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(90);
        LocalDate toDate = today;
        try {
            if (from != null && !from.isEmpty()) fromDate = LocalDate.parse(from);
            if (to != null && !to.isEmpty()) toDate = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            r.put("success", false);
            r.put("message", "Invalid date format (use YYYY-MM-DD)");
            return r;
        }
        List<Map<String, Object>> events = rejectionService.getEventsInRange(fromDate, toDate);
        Map<String, Object> aggregates = rejectionService.getAggregatesFor(
            events, "auditCategory", RejectionTrackerService.AUDIT_CATEGORY_ORDER);
        Map<String, Object> aggregatesAi = rejectionService.getAggregatesFor(
            events, "aiCategory", RejectionTrackerService.CATEGORIES);
        Map<String, Object> agreement = rejectionService.computeAgreement(events);
        String windowKey = RejectionTrackerService.windowKeyForRange(fromDate, toDate);
        Map<String, Object> narrative = rejectionService.getNarrative(windowKey);
        r.put("success", true);
        r.put("frozen", false);
        r.put("from", fromDate.toString());
        r.put("to", toDate.toString());
        r.put("windowKey", windowKey);
        r.put("classification", classification);
        r.put("auditGoLiveDate", auditGoLiveDate);
        r.put("events", events);
        r.put("aggregates", aggregates);
        r.put("aggregatesAi", aggregatesAi);
        r.put("agreement", agreement);
        r.put("narrative", narrative);
        r.put("lastRunTs", rejectionService.getLastRunTs());
```

Leave the existing `refreshStatus`/`refreshLogTail` block (lines 110-131) and the final `return r;` unchanged — they now follow the code above.

- [ ] **Step 3: Compile check**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/RejectionTrackerController.java
git commit -m "feat(returns): /data returns audit+ai aggregates, agreement, go-live; period= loads snapshot"
```

---

### Task 12: GET /periods and POST /snapshot

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/RejectionTrackerController.java`

- [ ] **Step 1: Add the endpoints**

Add before the closing brace of the class (after the `/explain/{eventId}` method):

```java
    // =========================================================================
    // GET /periods — frozen snapshot dropdown source
    // =========================================================================

    @GetMapping("/periods")
    public Map<String, Object> periods() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("periods", snapshotService.listPeriods());
        return r;
    }

    // =========================================================================
    // POST /snapshot — freeze the last completed period on demand (admin)
    // body: { "type": "week"|"month"|"quarter" } (default month)
    // =========================================================================

    @PostMapping("/snapshot")
    public Map<String, Object> snapshot(@RequestBody(required = false) Map<String, Object> body,
                                        HttpSession session) {
        if (!canEdit(session)) return forbidden();
        String type = body != null && body.get("type") != null ? body.get("type").toString() : "month";
        LocalDate today = LocalDate.now();
        String id; LocalDate from, to;
        if ("week".equals(type)) {
            to = today.minusDays((today.getDayOfWeek().getValue() % 7) + 1); // last Sunday
            from = to.minusDays(6);
            id = RejectionSnapshotService.weekId(to);
        } else if ("quarter".equals(type)) {
            LocalDate prevQuarterDay = today.withDayOfMonth(1).minusMonths(
                ((today.getMonthValue() - 1) % 3) + 1);
            LocalDate[] qb = RejectionSnapshotService.quarterBounds(
                prevQuarterDay.getYear(), (prevQuarterDay.getMonthValue() - 1) / 3 + 1);
            from = qb[0]; to = qb[1]; id = RejectionSnapshotService.quarterId(qb[0]);
        } else {
            LocalDate firstThis = today.withDayOfMonth(1);
            to = firstThis.minusDays(1);
            from = to.withDayOfMonth(1);
            id = RejectionSnapshotService.monthId(from);
        }
        boolean created = snapshotService.writeSnapshot(id, from, to);
        activityLogger.log(username(session), displayName(session),
                "RETURNS_SNAPSHOT", (created ? "Created" : "Already exists") + " snapshot " + id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("id", id);
        r.put("created", created);
        r.put("message", created ? "Snapshot " + id + " created" : "Snapshot " + id + " already exists");
        return r;
    }
```

- [ ] **Step 2: Compile check**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/RejectionTrackerController.java
git commit -m "feat(returns): GET /periods and POST /snapshot endpoints"
```

---

## Phase 6 — Frontend

### Task 13: State, colors, go-live/legacy presets

**Files:**
- Modify: `src/main/resources/static/returnstracker.js`

- [ ] **Step 1: Extend state + colors**

In `returnstracker.js`, change the `returnsState` object (lines 16-26) to add two fields and default the range to the go-live preset:

```javascript
var returnsState = {
    view: 'cycle',           // 'cycle' or 'returns'
    range: 'golive',         // default to "Since audit go-live"
    classification: 'audit', // 'audit' or 'ai'
    period: 'live',          // 'live' or a frozen snapshot id
    customFrom: null,
    customTo: null,
    data: null,
    columnFilters: {},
    loading: false,
};

// Production go-live of the return-comment audit. Overwritten from /data
// (auditGoLiveDate) once the first payload arrives.
var RETURNS_AUDIT_GOLIVE = '2026-06-24';
```

Add the `No audit code` color to `RETURNS_CATEGORY_COLORS` (inside the object literal, before the legacy aliases):

```javascript
    'No audit code':             '#B0B0B0',
```

- [ ] **Step 2: Add presets to returnsResolveRange**

In `returnsResolveRange()` (lines 195-217), add two branches before the `else if (returnsState.range === 'custom')` branch:

```javascript
    } else if (returnsState.range === 'golive') {
        from = RETURNS_AUDIT_GOLIVE;
    } else if (returnsState.range === 'legacy') {
        var g = new Date(RETURNS_AUDIT_GOLIVE);
        g.setDate(g.getDate() - 1);
        to = g.toISOString().substring(0, 10);
        from = '2026-01-01';
```

- [ ] **Step 3: Capture go-live from payload**

In `returnsLoad()`'s success handler, right after `returnsState.data = data;` (line ~238) add:

```javascript
            if (data.auditGoLiveDate) RETURNS_AUDIT_GOLIVE = data.auditGoLiveDate;
```

- [ ] **Step 4: Manual verify (deferred to Task 18)** — no JS unit harness in this repo.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/returnstracker.js
git commit -m "feat(returns): FE state, No-audit-code color, go-live/legacy range presets"
```

---

### Task 14: Classification toggle + agreement + mismatch rendering

**Files:**
- Modify: `src/main/resources/static/returnstracker.js`

- [ ] **Step 1: Make returnsRender source-aware + call new panels**

In `returnsRender()` (lines 317-349), replace the `var agg = d.aggregates || {};` line with:

```javascript
    var agg = (returnsState.classification === 'ai'
        ? d.aggregatesAi : d.aggregates) || d.aggregates || {};
```

Then, right after the `returnsRenderKpiTiles(agg);` line, add:

```javascript
    returnsRenderAgreement(d.agreement);
```

And after `returnsRenderEventsTable(ev);` add:

```javascript
    returnsRenderMismatches(d.agreement);
```

- [ ] **Step 2: Add the toggle + agreement + mismatch functions**

Append to `returnstracker.js` (before the final helper functions):

```javascript
// ---------------------------------------------------------------------------
// AI vs audit classification toggle
// ---------------------------------------------------------------------------

function returnsSetClassification(mode) {
    returnsState.classification = mode;
    var audit = document.getElementById('returnsClsAudit');
    var ai = document.getElementById('returnsClsAi');
    [ [audit, 'audit'], [ai, 'ai'] ].forEach(function (pair) {
        if (!pair[0]) return;
        var on = pair[1] === mode;
        pair[0].style.background = on ? '#fff' : 'transparent';
        pair[0].style.color = on ? '#0F1720' : '#6B7280';
        pair[0].style.boxShadow = on ? '0 1px 2px rgba(0,0,0,0.06)' : 'none';
    });
    if (returnsState.data) returnsRender();
}

function returnsRenderAgreement(agree) {
    var el = document.getElementById('returnsAgreement');
    if (!el) return;
    if (!agree || !agree.coded) {
        el.innerHTML = '<div style="font-size:12px;color:#6B7280;">No audit-coded returns in this '
            + 'period yet — AI↔audit agreement will appear once analysts use reason-code prefixes '
            + '(post ' + RETURNS_AUDIT_GOLIVE + ').</div>';
        return;
    }
    var pct = agree.agreementPct;
    var color = pct >= 80 ? '#1F8A4C' : pct >= 60 ? '#C7801B' : '#B8342B';
    el.innerHTML =
        '<div style="display:flex;align-items:center;gap:14px;">' +
        '<div style="font-size:26px;font-weight:bold;color:' + color + ';">' + pct + '%</div>' +
        '<div style="font-size:12px;color:#6B7280;">AI↔Audit agreement &middot; ' +
        agree.matched + ' of ' + agree.coded + ' coded returns match the human reason code' +
        (agree.mismatches && agree.mismatches.length
            ? ' &middot; ' + agree.mismatches.length + ' mismatch' + (agree.mismatches.length === 1 ? '' : 'es') + ' below'
            : '') +
        '</div></div>';
}

function returnsRenderMismatches(agree) {
    var el = document.getElementById('returnsMismatchPanel');
    if (!el) return;
    var mm = agree && agree.mismatches ? agree.mismatches : [];
    if (!mm.length) { el.style.display = 'none'; return; }
    el.style.display = '';
    var html = '<div style="font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:.05em;'
        + 'font-weight:600;border-bottom:1px solid #E8E6DF;padding-bottom:4px;margin-bottom:8px;">'
        + 'AI &harr; Audit mismatches (' + mm.length + ')</div>';
    html += '<table style="width:100%;border-collapse:collapse;font-size:12px;">'
        + '<tr style="background:#2c3e50;color:#fff;">'
        + '<th style="text-align:left;padding:6px 10px;">ECN#</th>'
        + '<th style="text-align:left;padding:6px 10px;">AI inferred</th>'
        + '<th style="text-align:left;padding:6px 10px;">Audit code</th>'
        + '<th style="text-align:left;padding:6px 10px;">Comment</th></tr>';
    mm.forEach(function (m, i) {
        var bg = i % 2 ? '#FAFAF7' : '#fff';
        html += '<tr style="background:' + bg + ';">'
            + '<td style="padding:6px 10px;border:1px solid #cccccc;">' + returnsEsc(m.ecnNumber || '') + '</td>'
            + '<td style="padding:6px 10px;border:1px solid #cccccc;color:#4a6fa5;">' + returnsEsc(m.aiCategory || '') + '</td>'
            + '<td style="padding:6px 10px;border:1px solid #cccccc;color:#B8342B;">' + returnsEsc(m.auditCategory || '') + '</td>'
            + '<td style="padding:6px 10px;border:1px solid #cccccc;">' + returnsEsc(m.comment || '') + '</td></tr>';
    });
    html += '</table>';
    el.innerHTML = html;
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/returnstracker.js
git commit -m "feat(returns): classification toggle + agreement tile + mismatch panel"
```

---

### Task 15: Period dropdown load + change handler

**Files:**
- Modify: `src/main/resources/static/returnstracker.js`

- [ ] **Step 1: Add period functions**

Append to `returnstracker.js`:

```javascript
// ---------------------------------------------------------------------------
// Frozen-period snapshots dropdown
// ---------------------------------------------------------------------------

function returnsLoadPeriods() {
    fetch('/api/ecn-report/returns/periods')
        .then(function (r) { return r.json(); })
        .then(function (d) {
            if (!d.success) return;
            var sel = document.getElementById('returnsPeriodSelect');
            if (!sel) return;
            var groups = { quarter: [], month: [], week: [] };
            (d.periods || []).forEach(function (p) {
                if (groups[p.type]) groups[p.type].push(p);
            });
            groups.week = groups.week.slice(0, 26); // cap weekly entries
            var html = '<option value="live">Live (date range)</option>';
            function grp(label, arr) {
                if (!arr.length) return '';
                var s = '<optgroup label="' + label + '">';
                arr.forEach(function (p) {
                    s += '<option value="' + returnsEsc(p.id) + '">' + returnsEsc(p.label) + '</option>';
                });
                return s + '</optgroup>';
            }
            html += grp('Quarters', groups.quarter) + grp('Months', groups.month) + grp('Weeks', groups.week);
            sel.innerHTML = html;
            sel.value = returnsState.period;
        })
        .catch(function () {});
}

function returnsHandlePeriodChange(val) {
    returnsState.period = val;
    var rangeWrap = document.getElementById('returnsRangeControls');
    var caption = document.getElementById('returnsFrozenCaption');
    if (val === 'live') {
        if (rangeWrap) rangeWrap.style.display = '';
        if (caption) caption.style.display = 'none';
        returnsLoad();
        return;
    }
    if (rangeWrap) rangeWrap.style.display = 'none';
    document.getElementById('returnsStatus').textContent = 'Loading frozen snapshot ' + val + '…';
    fetch('/api/ecn-report/returns/data?period=' + encodeURIComponent(val))
        .then(function (r) { return r.json(); })
        .then(function (d) {
            if (!d.success) {
                document.getElementById('returnsStatus').textContent = d.message || 'Snapshot load failed.';
                return;
            }
            returnsState.data = d;
            if (d.auditGoLiveDate) RETURNS_AUDIT_GOLIVE = d.auditGoLiveDate;
            returnsRender();
            if (caption) {
                caption.style.display = '';
                caption.textContent = 'Frozen snapshot · ' + (d.label || val)
                    + (d.generatedAt ? ' · generated ' + new Date(d.generatedAt).toLocaleString() : '');
            }
        })
        .catch(function (err) {
            document.getElementById('returnsStatus').textContent = 'Error: ' + err.message;
        });
}
```

- [ ] **Step 2: Load periods when the view opens**

In `ecnSwitchView()`, inside the `if (view === 'returns') {` block, right before `returnsLoad();` (line ~110) add:

```javascript
        returnsLoadPeriods();
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/returnstracker.js
git commit -m "feat(returns): period dropdown load + snapshot change handler"
```

---

### Task 16: Wire the controls into index.html

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Locate the Returns controls row**

Run: `grep -n 'returnsControls\|returnsRangeSelect\|returnsCustomFrom\|id="returnsStatus"' src/main/resources/static/index.html`
Expected: shows the `returnsControls` container and the existing range `<select>` + custom date inputs.

- [ ] **Step 2: Add the period select + classification toggle + frozen caption**

Inside the `returnsControls` container (the flex row that holds Refresh/Email/Recipients), add — as the FIRST children of that row — a period dropdown and the audit/AI segmented toggle. Wrap the EXISTING range `<select>` + custom date inputs in a `<span id="returnsRangeControls">` so the period handler can hide them. Concretely:

1. Wrap the existing range control. Change the existing range `<select ... onchange="returnsHandleRangeChange(this.value)">` and its two `returnsCustomFrom`/`returnsCustomTo` inputs by surrounding them with:

```html
<span id="returnsRangeControls" style="display:inline-flex;align-items:center;gap:6px;">
  <!-- existing range <select> + returnsCustomFrom + returnsCustomTo stay here -->
</span>
```

2. Add the two new "Since audit go-live" / "Legacy (pre-audit)" options to the existing range `<select>` (as the first two `<option>`s, before "Last 7 days"):

```html
<option value="golive">Since audit go-live (Jun 24, 2026)</option>
<option value="legacy">Legacy (pre-audit)</option>
```

3. Immediately before `#returnsRangeControls`, add the period dropdown:

```html
<select id="returnsPeriodSelect" onchange="returnsHandlePeriodChange(this.value)"
        style="padding:6px 8px;border:1px solid #E8E6DF;border-radius:6px;font-size:13px;">
  <option value="live">Live (date range)</option>
</select>
```

4. After `#returnsRangeControls`, add the classification toggle:

```html
<span style="display:inline-flex;background:#f0f0f0;border-radius:8px;padding:2px;">
  <button id="returnsClsAudit" type="button" onclick="returnsSetClassification('audit')"
    style="border:none;border-radius:6px;padding:5px 10px;font-size:12px;cursor:pointer;background:#fff;color:#0F1720;box-shadow:0 1px 2px rgba(0,0,0,0.06);">Audit-enforced</button>
  <button id="returnsClsAi" type="button" onclick="returnsSetClassification('ai')"
    style="border:none;border-radius:6px;padding:5px 10px;font-size:12px;cursor:pointer;background:transparent;color:#6B7280;">AI-inferred</button>
</span>
<span id="returnsFrozenCaption" style="display:none;font-size:11px;color:#6B7280;margin-left:8px;"></span>
```

- [ ] **Step 3: Add agreement + mismatch containers**

Find the KPI tiles container: `grep -n 'id="returnsKpiTiles"' src/main/resources/static/index.html`. Immediately AFTER that element's closing tag, add:

```html
<div id="returnsAgreement" style="margin:10px 0 4px;"></div>
```

Find the events table container (search `id="returnsEventsTable"` or the section that follows `returnsRenderThemesPanel`), and BEFORE the events table section add:

```html
<div id="returnsMismatchPanel" style="display:none;margin:16px 0;"></div>
```

- [ ] **Step 4: Set the default range select value**

Ensure the range `<select>` reflects the new default. Since `returnsState.range` defaults to `'golive'`, set the `golive` option `selected`:

```html
<option value="golive" selected>Since audit go-live (Jun 24, 2026)</option>
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat(returns): period dropdown, audit/AI toggle, agreement+mismatch containers"
```

---

## Phase 7 — Excel export correction (Noraida's ask)

### Task 17: Report sheet — aiCategory + audit columns; Categories sheet — agreed taxonomy

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerEmailService.java:197-252` (Report), `:448-489` (Categories)

- [ ] **Step 1: Report sheet — feed AI category correctly + add two columns**

In `buildExcel`, change the `headers` array (lines 197-203) to append two columns:

```java
        String[] headers = {
            "ECN#", "ECN# (Grouped)", "User", "Requestor vs Analyst Return",
            "User Comment", "Manual Comment Summary", "AI Comment Category",
            "Manual Category", "Action", "Comment Date", "Proposal", "Status",
            "Product Line(s)", "Product Line/Program Name", "Analyst", "Requestor",
            "Category", "Request Classification", "Date Originated", "Submit Date", "Date Released",
            "Audit Category", "Classification Source"
        };
```

Extend the `widths` array (line 211) by two entries:

```java
        int[] widths = {16, 16, 28, 18, 50, 24, 22, 18, 18, 18, 50, 12, 28, 32, 26, 26, 14, 32, 14, 14, 14, 22, 18};
```

In the row loop, change the "AI Comment Category" cell (line 236) from the stale `category` to the persisted `aiCategory`, and add the two new cells after the existing `setCell(r, 20, ...)` (line 251):

```java
            setCell(r, 6,  strOf(e.get("aiCategory")));
```

```java
            setCell(r, 21, strOf(e.get("auditCategory")));
            setCell(r, 22, strOf(e.get("categorySource")));
```

(The autofilter on line 254 uses `headers.length - 1`, so it adjusts automatically.)

- [ ] **Step 2: Categories sheet — agreed taxonomy, count by auditCategory**

Replace the `taxonomy` array (lines 448-456) with the agreed table (drop "Ambiguous Request", add "Return Requested", "Returned by Owner", "No audit code"):

```java
        String[][] taxonomy = {
            {"Returned by Owner", "ECN returned to Pending by its creator while @Submit (tracked as FYI; auto-interpreted)."},
            {"Incomplete Documentation", "Could not advance due to missing required documentation (MDDS, specs, etc.). Code ID:"},
            {"Insufficient Information", "Could not advance due to unclear/missing/incomplete required information. Code II:"},
            {"Wrong Information", "Provided information does not support the change or conflicts with process requirements. Code WI:"},
            {"Duplicate Request", "Duplicates an existing request already addressed under a different ECN. Code DR:"},
            {"Return Requested", "Analyst returned the ECN at the requestor's request. Code RR:"},
            {"No audit code", "Return comment carried no audit reason-code prefix (pre-audit or non-compliant)."}
        };
```

Change the count loop (lines 457-460) to count by `auditCategory`:

```java
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> e : events) {
            counts.merge(strOf(e.get("auditCategory")), 1, Integer::sum);
        }
```

The chart range uses `taxonomy.length` (lines 484-486), so it adjusts automatically.

- [ ] **Step 3: Compile check**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerEmailService.java
git commit -m "fix(returns): Excel shows AI+audit categories and agreed taxonomy (Noraida)"
```

---

## Phase 8 — Build, deploy locally, verify

### Task 18: Full build + local restart + end-to-end verification

**Files:** none (verification)

- [ ] **Step 1: Update the What's New changelog** (required before every JAR build — see CLAUDE.md)

Add a new entry at the TOP of `WHATS_NEW_RELEASES` in `src/main/resources/static/whats-new.js` dated `2026-07-01`, titled "Returns Tracker: past-period snapshots + AI-vs-audit classification", with items:
- new: "Period dropdown — revisit frozen weekly/monthly/quarterly Returns Tracker snapshots"
- new: "Audit-enforced vs AI-inferred toggle + AI↔Audit agreement metric and mismatch list"
- new: "Default view starts at the audit go-live (Jun 24, 2026); Legacy (pre-audit) preset for older data"
- fix: "Excel export now shows AI + audit categories and the agreed classification taxonomy"

Commit:
```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): Returns Tracker snapshots + AI-vs-audit entry"
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS; `RejectionClassificationTest` (8) and `RejectionSnapshotServiceTest` (4) green; no regressions.

- [ ] **Step 3: Package the JAR**

Run: `mvn -q -DskipTests package`
Expected: `target/plm-field-tracker-1.0.1.jar` built.

- [ ] **Step 4: Copy the artifact to the local smoke-test setup**

Run:
```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar "~/Documents/plm-toolkit 2/plm-field-tracker-1.0.1.jar"
```

- [ ] **Step 5: Restart the local instance**

Stop any running local JVM, then start (heap ≥4g, per CLAUDE.md):
```bash
cd ~/Documents/plm-toolkit\ 2
java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties
```
Wait for `Started` on http://localhost:8090. On first boot, `RejectionSnapshotService.backfillOnce()` writes month/quarter/week snapshots from the retained cache — confirm the log line `[RT-SNAP] Backfill complete: N periods`.

- [ ] **Step 6: Verify the API end-to-end**

Log in and exercise the endpoints (substitute the plmadmin password from private memory):
```bash
curl -sS -c /tmp/ck.txt -H "Content-Type: application/json" \
  -d '{"username":"plmadmin","password":"<PWD>"}' http://localhost:8090/api/auth/login
curl -sS -b /tmp/ck.txt "http://localhost:8090/api/ecn-report/returns/periods" | head -c 600
curl -sS -b /tmp/ck.txt "http://localhost:8090/api/ecn-report/returns/data?from=2026-06-24&to=2026-07-01" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('audit cats',list(d['aggregates']['categories'].items())[:3]);print('ai cats',list(d['aggregatesAi']['categories'].items())[:3]);print('agreement',d['agreement']['agreementPct'],d['agreement']['coded'])"
```
Expected: `/periods` returns a non-empty list (weeks/months/quarters); `/data` returns both `aggregates` and `aggregatesAi` and an `agreement` block. Pick one id from `/periods` and confirm `?period=<id>` returns `frozen:true` with `events`.

- [ ] **Step 7: Verify the UI**

Open http://localhost:8090, go to ECN Dashboard → Returns Tracker. Confirm:
- Period dropdown lists Quarters/Months/Weeks; selecting June 2026 loads a frozen snapshot with the "Frozen snapshot · …" caption and hides the date range.
- The `Audit-enforced | AI-inferred` toggle re-renders the category panel + KPI top-category without a network call (watch the Network tab — no `/data` request on toggle).
- The AI↔Audit agreement line shows a % (or the "no audit-coded returns yet" note for legacy periods).
- The mismatch panel appears when mismatches exist.
- Default range is "Since audit go-live (Jun 24, 2026)".

Use the preview/inspect tools or a screenshot to confirm rendering.

- [ ] **Step 8: Export the Excel and eyeball it**

Run:
```bash
curl -sS -b /tmp/ck.txt "http://localhost:8090/api/ecn-report/returns/export?from=2026-06-24&to=2026-07-01" -o /tmp/returns.xlsx
python3 - <<'PY'
import openpyxl
wb = openpyxl.load_workbook('/tmp/returns.xlsx')
rep = wb['Report']; print('Report headers:', [c.value for c in rep[1]][-4:])
cat = wb['Categories']; print('Categories col A:', [cat.cell(r,1).value for r in range(1,9)])
PY
```
Expected: Report headers end with `AI Comment Category … Audit Category, Classification Source`; Categories sheet has no "Ambiguous Request" and includes "No audit code".

- [ ] **Step 9: Commit any fixups discovered during verification, then stop the local instance if the user wants it left down (default: leave it running).**

- [ ] **Step 10: Email Vikas the completion summary**

Send an HTML summary to `vikas.jindal@sandisk.com` via `mailrelay.sandisk.com:25` (per CLAUDE.md "Long-running Work Notifications"), covering: what shipped (snapshots dropdown, AI-vs-audit toggle + agreement, Excel fix), the audit go-live date used (2026-06-24), how to try it locally (URL + steps), and the two open follow-ups (prod scheduler will start freezing snapshots on the next weekly/monthly run; backfill covers Jan–Jun 2026 from the retained cache). Do NOT include any credentials.

---

## Self-review

**Spec coverage:**
- §1 Classification data model → Tasks 1-2 (fields), 4 (agreement), 17 (Excel persistence via aiCategory/auditCategory/source). ✓
- §2 Snapshot archive → Tasks 6-8 (service), 9 (scheduler), 8 (backfill). ✓
- §3 API (/periods, period=, classification=, /snapshot) → Tasks 11-12. ✓
- §4 Frontend (period dropdown, toggle, agreement tile, mismatch, go-live default/legacy) → Tasks 13-16. ✓
- §5 Excel/email fix → Task 17. ✓
- §6 Config → Task 10. ✓
- Testing → Tasks 1-8 unit tests; Task 18 integration/manual. ✓
- Weekly+month+quarter granularity → Tasks 8, 9. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. ✓

**Type consistency:** `getAggregatesFor(evs, field, order)`, `computeAgreement`, `aiView`/`auditView`/`categorySource`, `NO_AUDIT_CODE`, `AUDIT_CATEGORY_ORDER`, snapshot `monthId/quarterId/weekId/labelFor/quarterBounds/quarterId`, event fields `aiCategory/auditCategory/categorySource` — used consistently across backend, snapshot service, controller, Excel, and frontend. ✓

**Note for implementer:** the email HTML KPI block (agreement stat in the email body) is intentionally deferred — the spec calls for it but it is low-risk polish; add it after Task 17 only if time permits, keying off the same `computeAgreement` output. It is NOT required for the feature to function.
