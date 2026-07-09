# ECO Timeline Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a top-level "ECO Timeline" tab that, given an item number + start/end date, lists every released ECO that changed any component anywhere in the item's indented BOM (evolved-union, full recursion) and what each ECO changed, exportable to Excel.

**Architecture:** A new `EcoTimelineService` runs two live Oracle queries — (1) a recursive `CONNECT BY` over `AGILE.BOM` whose edges are pre-filtered to BOM lines active at any point in the window, yielding every assembly node in the structure with its indent level; (2) per-assembly redline rows whose `CHANGE_IN`/`CHANGE_OUT` released within the window. A pure, unit-tested `EcoTimelineClassifier` turns redline rows into Added / Removed / Modify(primary#, qty, find#, notes) events by pairing add+remove rows via `PRIOR_BOM`. A controller exposes `/query`, `/export`, `/email`; a new tab + `eco-timeline.js` render and export the result.

**Tech Stack:** Java 8, Spring Boot (raw JDBC via injected `DataSource`), Apache POI (XSSF), JUnit 5 (Jupiter), vanilla JS frontend.

**Build/test environment:** This toolkit targets **Java 11** (`pom.xml`: `<java.version>11</java.version>`) — NOT Java 8. The global CLAUDE.md "Java 1.8" note applies only to the Agile SDK PX projects, not plm-field-tracker. Prefix maven commands with the Corretto 11 home:
```
export JAVA11=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home
```
Run tests: `JAVA_HOME=$JAVA11 mvn -q -Dtest=EcoTimelineClassifierTest test`
Compile: `JAVA_HOME=$JAVA11 mvn -q -DskipTests compile`

**Testing note (matches repo convention):** This repo unit-tests pure logic only — no DB/H2 (see `ChangeQueryServiceTest`; `RevCompareService` has no test). So the classifier (Task 2) gets full TDD. The SQL service, controller, export, and frontend are verified by compile + a manual end-to-end checklist against the live QA Agile DB (Task 8). The Oracle-specific SQL (`CONNECT BY`, redline) cannot run on H2, but these are **direct JDBC queries (not Agile SDK calls), so the QA Agile DB IS reachable from the local setup** — Task 8 runs fully on this machine.

**Schema facts this relies on** (`AGILE.BOM`): `ITEM` (parent item id), `COMPONENT` (child item id), `ITEM_NUMBER` (component part-number string), `QUANTITY`, `FIND_NUMBER`, `NOTES`, `DESCRIPTION`, `CHANGE_IN` (FK to `CHANGE` that added the row, `0`=pre-existing), `CHANGE_OUT` (FK to `CHANGE` that removed it, `0`=active), `PRIOR_BOM` (FK to the retired row's `ID`; links a modify's remove+add pair), `ID`. `AGILE.CHANGE`: `ID`, `CHANGE_NUMBER`, `RELEASE_DATE` (NULL=pending), `DELETE_FLAG`. `AGILE.ITEM`: `ID`, `ITEM_NUMBER`, `DESCRIPTION`. Tables are referenced unqualified (the `dataSource` connects into the AGILE schema), exactly as `RevCompareService` does.

---

## File Structure

**Create:**
- `src/main/java/com/sandisk/plm/tracker/model/BomRedlineRow.java` — raw redline row (one `AGILE.BOM` row + its change-in/out metadata). Plain mutable fields; input to the classifier.
- `src/main/java/com/sandisk/plm/tracker/model/EcoTimelineRow.java` — one output event (the report row). Constructor + getters.
- `src/main/java/com/sandisk/plm/tracker/service/EcoTimelineClassifier.java` — pure logic: redline rows → events. Holds the change-type label constants. Unit-tested.
- `src/main/java/com/sandisk/plm/tracker/service/EcoTimelineService.java` — the two Oracle queries + orchestration.
- `src/main/java/com/sandisk/plm/tracker/service/EcoTimelineExcelExportService.java` — POI export.
- `src/main/java/com/sandisk/plm/tracker/controller/EcoTimelineController.java` — `/query`, `/export`, `/email`.
- `src/main/resources/static/eco-timeline.js` — frontend.
- `src/test/java/com/sandisk/plm/tracker/service/EcoTimelineClassifierTest.java` — classifier tests.

**Modify:**
- `src/main/resources/static/index.html` — nav button, panel, script tag.
- `src/main/resources/static/app.js` — `switchTab` show/hide + active class; `TAB_PREFS_CONFIG` entry.
- `src/main/resources/static/style.css` — change-type pill styles.
- `src/main/resources/static/whats-new.js` — release entry (Task 9).

---

## Task 1: Model classes

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/model/BomRedlineRow.java`
- Create: `src/main/java/com/sandisk/plm/tracker/model/EcoTimelineRow.java`

- [ ] **Step 1: Create `BomRedlineRow`** (plain fields — keeps classifier tests readable)

```java
package com.sandisk.plm.tracker.model;

import java.sql.Timestamp;

/** One raw AGILE.BOM redline row for a single parent assembly, with the
 *  CHANGE_IN / CHANGE_OUT metadata needed to classify what an ECO did. */
public class BomRedlineRow {
    public long bomRowId;          // BOM.ID
    public long priorBom;          // BOM.PRIOR_BOM (0 if none) — links a modify's add->removed row
    public String componentPn;     // BOM.ITEM_NUMBER (component part number)
    public String componentDesc;   // COALESCE(BOM.DESCRIPTION, ITEM.DESCRIPTION)
    public String quantity;        // BOM.QUANTITY (as string)
    public String findNumber;      // BOM.FIND_NUMBER (find # / seq)
    public String notes;           // BOM.NOTES

    public String changeInNum;     // CHANGE.CHANGE_NUMBER of CHANGE_IN (null if 0/none)
    public Timestamp changeInRd;   // CHANGE.RELEASE_DATE of CHANGE_IN
    public String changeOutNum;    // CHANGE.CHANGE_NUMBER of CHANGE_OUT (null if 0/none)
    public Timestamp changeOutRd;  // CHANGE.RELEASE_DATE of CHANGE_OUT

    public BomRedlineRow() { }
}
```

- [ ] **Step 2: Create `EcoTimelineRow`** (one report row; getters for Jackson + export)

```java
package com.sandisk.plm.tracker.model;

import java.sql.Timestamp;

/** One ECO-attributed change event in the timeline report. */
public class EcoTimelineRow {
    private final int level;
    private final String parentAssembly;
    private final String component;
    private final String componentDescription;
    private final String ecoNumber;
    private final String ecoReleaseDate;   // formatted yyyy-MM-dd (Pacific)
    private final String changeType;
    private final String detail;
    private final long releaseTsMillis;    // for sorting; harmless in JSON

    public EcoTimelineRow(int level, String parentAssembly, String component,
                          String componentDescription, String ecoNumber,
                          String ecoReleaseDate, String changeType, String detail,
                          Timestamp releaseTs) {
        this.level = level;
        this.parentAssembly = parentAssembly == null ? "" : parentAssembly;
        this.component = component == null ? "" : component;
        this.componentDescription = componentDescription == null ? "" : componentDescription;
        this.ecoNumber = ecoNumber == null ? "" : ecoNumber;
        this.ecoReleaseDate = ecoReleaseDate == null ? "" : ecoReleaseDate;
        this.changeType = changeType == null ? "" : changeType;
        this.detail = detail == null ? "" : detail;
        this.releaseTsMillis = releaseTs == null ? 0L : releaseTs.getTime();
    }

    public int getLevel() { return level; }
    public String getParentAssembly() { return parentAssembly; }
    public String getComponent() { return component; }
    public String getComponentDescription() { return componentDescription; }
    public String getEcoNumber() { return ecoNumber; }
    public String getEcoReleaseDate() { return ecoReleaseDate; }
    public String getChangeType() { return changeType; }
    public String getDetail() { return detail; }
    public long getReleaseTsMillis() { return releaseTsMillis; }
}
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=$JAVA11 mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (no errors for the two new files).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/model/BomRedlineRow.java \
        src/main/java/com/sandisk/plm/tracker/model/EcoTimelineRow.java
git commit -m "feat(eco-timeline): add BomRedlineRow + EcoTimelineRow models"
```

---

## Task 2: EcoTimelineClassifier (pure logic, TDD)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/EcoTimelineClassifier.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/EcoTimelineClassifierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomRedlineRow;
import com.sandisk.plm.tracker.model.EcoTimelineRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EcoTimelineClassifierTest {

    private EcoTimelineClassifier classifier;
    private Timestamp from;   // window start
    private Timestamp to;     // window end
    private Timestamp inWin;  // a release date inside the window

    @BeforeEach
    void setUp() {
        classifier = new EcoTimelineClassifier();
        from = new Timestamp(1_000_000L);
        to   = new Timestamp(9_000_000L);
        inWin = new Timestamp(5_000_000L);
    }

    private BomRedlineRow addedRow(long id, String comp, String eco) {
        BomRedlineRow r = new BomRedlineRow();
        r.bomRowId = id; r.priorBom = 0; r.componentPn = comp; r.componentDesc = comp + " desc";
        r.quantity = "1"; r.findNumber = "10"; r.notes = "";
        r.changeInNum = eco; r.changeInRd = inWin;
        return r;
    }

    @Test
    void pureAdd_isAdded() {
        List<BomRedlineRow> rows = new ArrayList<>();
        rows.add(addedRow(100, "ABC-001", "ECO-1"));
        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", 1, rows, from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.ADDED, out.get(0).getChangeType());
        assertEquals("ABC-001", out.get(0).getComponent());
        assertEquals("ECO-1", out.get(0).getEcoNumber());
        assertEquals("ASSY-1", out.get(0).getParentAssembly());
        assertEquals(1, out.get(0).getLevel());
    }

    @Test
    void pureRemove_isRemoved() {
        BomRedlineRow r = new BomRedlineRow();
        r.bomRowId = 200; r.priorBom = 0; r.componentPn = "ABC-002"; r.componentDesc = "d";
        r.quantity = "2"; r.findNumber = "20"; r.notes = "";
        r.changeOutNum = "ECO-2"; r.changeOutRd = inWin;
        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", 2, List.of(r), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.REMOVED, out.get(0).getChangeType());
        assertEquals("ABC-002", out.get(0).getComponent());
    }

    @Test
    void quantityModify_pairsViaPriorBom() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "2"; removed.findNumber = "10"; removed.notes = "";
        removed.changeOutNum = "ECO-3"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "4"; added.findNumber = "10"; added.notes = "";
        added.changeInNum = "ECO-3"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", 1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        EcoTimelineRow row = out.get(0);
        assertEquals(EcoTimelineClassifier.QUANTITY_CHANGED, row.getChangeType());
        assertTrue(row.getDetail().contains("2"));
        assertTrue(row.getDetail().contains("4"));
        assertEquals("ECO-3", row.getEcoNumber());
    }

    @Test
    void primaryNumberModify_componentReplaced() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "old";
        removed.quantity = "1"; removed.findNumber = "10"; removed.notes = "";
        removed.changeOutNum = "ECO-4"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "DEF-002"; added.componentDesc = "new";
        added.quantity = "1"; added.findNumber = "10"; added.notes = "";
        added.changeInNum = "ECO-4"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", 1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.PRIMARY_NUMBER_CHANGED, out.get(0).getChangeType());
        assertEquals("DEF-002", out.get(0).getComponent());        // shows the new component
        assertTrue(out.get(0).getDetail().contains("ABC-001"));
        assertTrue(out.get(0).getDetail().contains("DEF-002"));
    }

    @Test
    void multiFieldModify_isModified() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "1"; removed.findNumber = "10"; removed.notes = "";
        removed.changeOutNum = "ECO-5"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "3"; added.findNumber = "99"; added.notes = "";   // qty AND find# changed
        added.changeInNum = "ECO-5"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", 1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.MODIFIED, out.get(0).getChangeType());
        assertTrue(out.get(0).getDetail().contains("Qty"));
        assertTrue(out.get(0).getDetail().contains("Find#"));
    }

    @Test
    void outOfWindow_producesNothing() {
        BomRedlineRow r = addedRow(100, "ABC-001", "ECO-9");
        r.changeInRd = new Timestamp(100L);   // before window start
        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", 1, List.of(r), from, to);
        assertTrue(out.isEmpty());
    }

    @Test
    void refDesOnlyModify_isSkipped() {
        // Pair where NO tracked field differs (only an untracked field like ref-des
        // would have changed) must produce no row — ref-des is out of scope.
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "1"; removed.findNumber = "10"; removed.notes = "n";
        removed.changeOutNum = "ECO-6"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "1"; added.findNumber = "10"; added.notes = "n";   // identical tracked fields
        added.changeInNum = "ECO-6"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", 1, List.of(removed, added), from, to);
        assertTrue(out.isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$JAVA11 mvn -q -Dtest=EcoTimelineClassifierTest test`
Expected: COMPILATION FAILURE / test failure — `EcoTimelineClassifier` does not exist yet.

- [ ] **Step 3: Write the implementation**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomRedlineRow;
import com.sandisk.plm.tracker.model.EcoTimelineRow;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Pure logic: turn AGILE.BOM redline rows for one parent assembly into
 * ECO-attributed timeline events. No DB access — unit-tested in isolation.
 *
 * An event is produced for a change only when that change released inside the
 * [from,to] window. A row added (CHANGE_IN) whose PRIOR_BOM points at a row
 * removed (CHANGE_OUT) by the SAME change is a modify; otherwise the add and
 * remove stand alone. A modify whose only differing fields are untracked
 * (e.g. ref-designator) is dropped.
 */
public class EcoTimelineClassifier {

    public static final String ADDED = "Added";
    public static final String REMOVED = "Removed";
    public static final String PRIMARY_NUMBER_CHANGED = "Primary number changed";
    public static final String QUANTITY_CHANGED = "Quantity changed";
    public static final String FIND_NUMBER_CHANGED = "Find # changed";
    public static final String NOTES_CHANGED = "Notes changed";
    public static final String MODIFIED = "Modified";

    private static final ZoneId PT = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(PT);

    public List<EcoTimelineRow> classifyAssembly(String parentPn, int level,
                                                 List<BomRedlineRow> rows,
                                                 Timestamp from, Timestamp to) {
        Map<String, List<BomRedlineRow>> addsByChange = new LinkedHashMap<>();
        Map<String, List<BomRedlineRow>> remsByChange = new LinkedHashMap<>();
        Map<String, Timestamp> rdByChange = new HashMap<>();

        for (BomRedlineRow r : rows) {
            if (r.changeInNum != null && inWindow(r.changeInRd, from, to)) {
                addsByChange.computeIfAbsent(r.changeInNum, k -> new ArrayList<>()).add(r);
                rdByChange.put(r.changeInNum, r.changeInRd);
            }
            if (r.changeOutNum != null && inWindow(r.changeOutRd, from, to)) {
                remsByChange.computeIfAbsent(r.changeOutNum, k -> new ArrayList<>()).add(r);
                rdByChange.put(r.changeOutNum, r.changeOutRd);
            }
        }

        List<EcoTimelineRow> out = new ArrayList<>();
        Set<String> changes = new LinkedHashSet<>();
        changes.addAll(addsByChange.keySet());
        changes.addAll(remsByChange.keySet());

        for (String chg : changes) {
            List<BomRedlineRow> adds = new ArrayList<>(addsByChange.getOrDefault(chg, Collections.emptyList()));
            List<BomRedlineRow> rems = new ArrayList<>(remsByChange.getOrDefault(chg, Collections.emptyList()));
            Timestamp rdTs = rdByChange.get(chg);
            String rd = FMT.format(rdTs.toInstant());

            // Pair modifies via PRIOR_BOM.
            Iterator<BomRedlineRow> ai = adds.iterator();
            while (ai.hasNext()) {
                BomRedlineRow a = ai.next();
                if (a.priorBom == 0) continue;
                BomRedlineRow match = null;
                for (BomRedlineRow rmv : rems) {
                    if (rmv.bomRowId == a.priorBom) { match = rmv; break; }
                }
                if (match != null) {
                    rems.remove(match);
                    ai.remove();
                    EcoTimelineRow mod = modifyRow(parentPn, level, chg, rd, rdTs, match, a);
                    if (mod != null) out.add(mod);   // null = only untracked fields changed
                }
            }
            for (BomRedlineRow a : adds) {
                out.add(new EcoTimelineRow(level, parentPn, a.componentPn, a.componentDesc,
                        chg, rd, ADDED, "Added", rdTs));
            }
            for (BomRedlineRow rmv : rems) {
                out.add(new EcoTimelineRow(level, parentPn, rmv.componentPn, rmv.componentDesc,
                        chg, rd, REMOVED, "Removed", rdTs));
            }
        }
        return out;
    }

    private EcoTimelineRow modifyRow(String parentPn, int level, String chg, String rd,
                                     Timestamp rdTs, BomRedlineRow oldR, BomRedlineRow newR) {
        List<String> details = new ArrayList<>();
        String type = null;
        if (!eq(oldR.componentPn, newR.componentPn)) {
            details.add("Replaced " + nz(oldR.componentPn) + " → " + nz(newR.componentPn));
            type = PRIMARY_NUMBER_CHANGED;
        }
        if (!eq(oldR.quantity, newR.quantity)) {
            details.add("Qty " + nz(oldR.quantity) + " → " + nz(newR.quantity));
            type = QUANTITY_CHANGED;
        }
        if (!eq(oldR.findNumber, newR.findNumber)) {
            details.add("Find# " + nz(oldR.findNumber) + " → " + nz(newR.findNumber));
            type = FIND_NUMBER_CHANGED;
        }
        if (!eq(oldR.notes, newR.notes)) {
            details.add("Notes changed");
            type = NOTES_CHANGED;
        }
        if (details.isEmpty()) return null;   // only untracked fields (e.g. ref-des) changed
        String finalType = details.size() > 1 ? MODIFIED : type;
        String comp = newR.componentPn != null ? newR.componentPn : oldR.componentPn;
        String desc = newR.componentDesc != null ? newR.componentDesc : oldR.componentDesc;
        return new EcoTimelineRow(level, parentPn, comp, desc, chg, rd, finalType,
                String.join("; ", details), rdTs);
    }

    private static boolean inWindow(Timestamp t, Timestamp from, Timestamp to) {
        return t != null && !t.before(from) && !t.after(to);
    }

    private static boolean eq(String a, String b) { return nz(a).equals(nz(b)); }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=$JAVA11 mvn -q -Dtest=EcoTimelineClassifierTest test`
Expected: PASS — 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/EcoTimelineClassifier.java \
        src/test/java/com/sandisk/plm/tracker/service/EcoTimelineClassifierTest.java
git commit -m "feat(eco-timeline): classifier for redline -> ECO change events (TDD)"
```

---

## Task 3: EcoTimelineService (live Oracle queries + orchestration)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/EcoTimelineService.java`

No unit test (Oracle-specific SQL; repo convention — see plan header). Verified by compile here and manual run in Task 8.

- [ ] **Step 1: Write the service**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomRedlineRow;
import com.sandisk.plm.tracker.model.EcoTimelineRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Logger;

@Service
public class EcoTimelineService {

    private static final Logger logger = Logger.getLogger(EcoTimelineService.class.getName());
    private static final ZoneId PT = ZoneId.of("America/Los_Angeles");
    private static final int MAX_ASSEMBLIES = 5000;   // safety cap on the union walk

    private final DataSource dataSource;
    private final EcoTimelineClassifier classifier = new EcoTimelineClassifier();

    public EcoTimelineService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Main entry: returns { rows, ecoCount, componentCount, queryTimeMs,
     *  truncated, depthLimitReached, item, from, to } or { error }. */
    public Map<String, Object> query(String item, LocalDate from, LocalDate to, int maxDepth) {
        long start = System.currentTimeMillis();
        Map<String, Object> resp = new LinkedHashMap<>();

        Timestamp fromTs = Timestamp.from(from.atStartOfDay(PT).toInstant());
        // inclusive end-of-day: midnight of (to+1) minus 1 ms
        Timestamp toTs = Timestamp.from(to.plusDays(1).atStartOfDay(PT).toInstant().minusMillis(1));

        try {
            Long rootId = resolveItemId(item.trim());
            if (rootId == null) {
                resp.put("error", "Item not found: " + item);
                return resp;
            }

            // Step 1 — evolved-union tree: assembly id -> min indent level, + part number.
            LinkedHashMap<Long, int[]> levelById = new LinkedHashMap<>(); // value[0]=level
            Map<Long, String> pnById = new HashMap<>();
            int deepest = walkUnionTree(rootId, fromTs, toTs, maxDepth, levelById, pnById);
            boolean truncated = levelById.size() >= MAX_ASSEMBLIES;

            // Step 2+3 — per assembly redline -> classify.
            List<EcoTimelineRow> rows = new ArrayList<>();
            int scanned = 0;
            for (Map.Entry<Long, int[]> e : levelById.entrySet()) {
                if (scanned++ >= MAX_ASSEMBLIES) { truncated = true; break; }
                List<BomRedlineRow> redline = fetchRedline(e.getKey(), fromTs, toTs);
                rows.addAll(classifier.classifyAssembly(
                        pnById.get(e.getKey()), e.getValue()[0], redline, fromTs, toTs));
            }

            // Sort: ECO release date asc, then indent level, then parent assembly.
            rows.sort(Comparator
                    .comparingLong(EcoTimelineRow::getReleaseTsMillis)
                    .thenComparingInt(EcoTimelineRow::getLevel)
                    .thenComparing(EcoTimelineRow::getParentAssembly));

            Set<String> ecos = new HashSet<>();
            Set<String> comps = new HashSet<>();
            for (EcoTimelineRow r : rows) { ecos.add(r.getEcoNumber()); comps.add(r.getComponent()); }

            resp.put("rows", rows);
            resp.put("ecoCount", ecos.size());
            resp.put("componentCount", comps.size());
            resp.put("queryTimeMs", System.currentTimeMillis() - start);
            resp.put("truncated", truncated);
            resp.put("depthLimitReached", deepest >= maxDepth);
            resp.put("item", item.trim());
            resp.put("from", from.toString());
            resp.put("to", to.toString());
        } catch (Exception e) {
            logger.warning("ECO timeline query failed for " + item + ": " + e.getMessage());
            resp.put("error", "Query failed: " + e.getMessage());
        }
        return resp;
    }

    private Long resolveItemId(String item) throws SQLException {
        String sql = "SELECT ID FROM item WHERE UPPER(ITEM_NUMBER) = UPPER(?) AND ROWNUM = 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.setString(1, item);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("ID") : null;
            }
        }
    }

    /** Recursive walk over window-active BOM edges. Fills levelById (assembly id ->
     *  [minLevel]) and pnById; returns the deepest level reached. */
    private int walkUnionTree(long rootId, Timestamp fromTs, Timestamp toTs, int maxDepth,
                              LinkedHashMap<Long, int[]> levelById, Map<Long, String> pnById)
            throws SQLException {
        // edges CTE = BOM lines active at any point in [from,to]:
        //   added on/before window end  AND  not removed before window start.
        String sql =
            "SELECT t.parent_id, MIN(t.lvl) AS lvl, MAX(it.ITEM_NUMBER) AS pn FROM ( " +
            "  SELECT LEVEL AS lvl, e.parent_id, e.child_id FROM ( " +
            "    SELECT b.ITEM AS parent_id, b.COMPONENT AS child_id " +
            "    FROM bom b " +
            "    LEFT JOIN change ci ON ci.ID = b.CHANGE_IN " +
            "    LEFT JOIN change co ON co.ID = b.CHANGE_OUT " +
            "    WHERE b.COMPONENT IS NOT NULL " +
            "      AND (b.CHANGE_IN = 0 OR (ci.RELEASE_DATE IS NOT NULL AND ci.RELEASE_DATE <= ?)) " +
            "      AND (b.CHANGE_OUT = 0 OR co.RELEASE_DATE IS NULL OR co.RELEASE_DATE >= ?) " +
            "  ) e " +
            "  START WITH e.parent_id = ? " +
            "  CONNECT BY NOCYCLE PRIOR e.child_id = e.parent_id AND LEVEL <= ? " +
            ") t " +
            "JOIN item it ON it.ID = t.parent_id " +
            "GROUP BY t.parent_id";

        int deepest = 0;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(120);
            ps.setTimestamp(1, toTs);     // CHANGE_IN <= window end
            ps.setTimestamp(2, fromTs);   // CHANGE_OUT >= window start
            ps.setLong(3, rootId);
            ps.setInt(4, maxDepth);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("parent_id");
                    int lvl = rs.getInt("lvl");
                    levelById.put(id, new int[]{ lvl });
                    pnById.put(id, rs.getString("pn"));
                    if (lvl > deepest) deepest = lvl;
                    if (levelById.size() >= MAX_ASSEMBLIES) break;
                }
            }
        }
        return deepest;
    }

    /** Redline rows for one assembly whose CHANGE_IN or CHANGE_OUT released in window. */
    private List<BomRedlineRow> fetchRedline(long assemblyId, Timestamp fromTs, Timestamp toTs)
            throws SQLException {
        String sql =
            "SELECT b.ID, b.PRIOR_BOM, b.ITEM_NUMBER, " +
            "       COALESCE(b.DESCRIPTION, ci_item.DESCRIPTION) AS comp_desc, " +
            "       b.QUANTITY, b.FIND_NUMBER, b.NOTES, " +
            "       cin.CHANGE_NUMBER AS cin_num, cin.RELEASE_DATE AS cin_rd, " +
            "       cout.CHANGE_NUMBER AS cout_num, cout.RELEASE_DATE AS cout_rd " +
            "FROM bom b " +
            "LEFT JOIN item ci_item ON ci_item.ID = b.COMPONENT " +
            "LEFT JOIN change cin ON cin.ID = b.CHANGE_IN " +
            "LEFT JOIN change cout ON cout.ID = b.CHANGE_OUT " +
            "WHERE b.ITEM = ? " +
            "  AND ( (cin.RELEASE_DATE IS NOT NULL AND cin.RELEASE_DATE BETWEEN ? AND ? " +
            "         AND NVL(cin.DELETE_FLAG,0) <> 1) " +
            "     OR (cout.RELEASE_DATE IS NOT NULL AND cout.RELEASE_DATE BETWEEN ? AND ? " +
            "         AND NVL(cout.DELETE_FLAG,0) <> 1) )";

        List<BomRedlineRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(120);
            ps.setLong(1, assemblyId);
            ps.setTimestamp(2, fromTs);
            ps.setTimestamp(3, toTs);
            ps.setTimestamp(4, fromTs);
            ps.setTimestamp(5, toTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BomRedlineRow r = new BomRedlineRow();
                    r.bomRowId = rs.getLong("ID");
                    r.priorBom = rs.getLong("PRIOR_BOM");   // 0 if NULL
                    r.componentPn = rs.getString("ITEM_NUMBER");
                    r.componentDesc = rs.getString("comp_desc");
                    r.quantity = rs.getString("QUANTITY");
                    r.findNumber = rs.getString("FIND_NUMBER");
                    r.notes = rs.getString("NOTES");
                    r.changeInNum = rs.getString("cin_num");
                    r.changeInRd = rs.getTimestamp("cin_rd");
                    r.changeOutNum = rs.getString("cout_num");
                    r.changeOutRd = rs.getTimestamp("cout_rd");
                    out.add(r);
                }
            }
        }
        return out;
    }
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=$JAVA11 mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/EcoTimelineService.java
git commit -m "feat(eco-timeline): service with evolved-union walk + per-assembly redline"
```

---

## Task 4: EcoTimelineExcelExportService

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/EcoTimelineExcelExportService.java`

- [ ] **Step 1: Write the export service** (mirrors `RevCompareExcelExportService` style)

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.EcoTimelineRow;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.List;

@Service
public class EcoTimelineExcelExportService {

    private static final String[] HEADERS = {
        "Level", "Parent Assembly", "Component #", "Description",
        "ECO #", "ECO Release Date", "Change Type", "Detail"
    };

    public void exportTimeline(List<EcoTimelineRow> rows, String item, String from, String to,
                               OutputStream out) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("ECO Timeline");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Title row (item + window)
            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue(
                "ECO Timeline — " + item + "  (" + from + " to " + to + ")");

            Row head = sheet.createRow(2);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }

            int r = 3;
            for (EcoTimelineRow row : rows) {
                Row dr = sheet.createRow(r++);
                dr.createCell(0).setCellValue(row.getLevel());
                dr.createCell(1).setCellValue(row.getParentAssembly());
                dr.createCell(2).setCellValue(row.getComponent());
                dr.createCell(3).setCellValue(row.getComponentDescription());
                dr.createCell(4).setCellValue(row.getEcoNumber());
                dr.createCell(5).setCellValue(row.getEcoReleaseDate());
                dr.createCell(6).setCellValue(row.getChangeType());
                dr.createCell(7).setCellValue(row.getDetail());
            }

            for (int i = 0; i < HEADERS.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=$JAVA11 mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

> If `mvn` reports `autoSizeColumn` issues in a headless env, it is safe to leave as-is; the project already uses POI elsewhere. No change needed.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/EcoTimelineExcelExportService.java
git commit -m "feat(eco-timeline): Excel export service"
```

---

## Task 5: EcoTimelineController

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/controller/EcoTimelineController.java`

- [ ] **Step 1: Write the controller** (mirrors `RevCompareController`)

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.EcoTimelineRow;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.EcoTimelineExcelExportService;
import com.sandisk.plm.tracker.service.EcoTimelineService;
import com.sandisk.plm.tracker.service.EmailService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/eco-timeline")
public class EcoTimelineController {

    private final EcoTimelineService service;
    private final EcoTimelineExcelExportService excelExportService;
    private final EmailService emailService;
    private final ActivityLogger activityLogger;

    public EcoTimelineController(EcoTimelineService service,
                                EcoTimelineExcelExportService excelExportService,
                                EmailService emailService,
                                ActivityLogger activityLogger) {
        this.service = service;
        this.excelExportService = excelExportService;
        this.emailService = emailService;
        this.activityLogger = activityLogger;
    }

    private String s(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "";
    }

    @GetMapping("/query")
    public Map<String, Object> query(@RequestParam String item,
                                     @RequestParam String from,
                                     @RequestParam String to,
                                     @RequestParam(required = false, defaultValue = "25") int maxDepth,
                                     HttpSession session) {
        Map<String, Object> resp = parseAndRun(item, from, to, maxDepth);
        if (!resp.containsKey("error")) {
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "ECO_TIMELINE", item + " | " + from + ".." + to +
                " | ecos=" + resp.get("ecoCount") + " comps=" + resp.get("componentCount"));
        }
        return resp;
    }

    /** Shared validation + service call. */
    private Map<String, Object> parseAndRun(String item, String from, String to, int maxDepth) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (item == null || item.trim().isEmpty()) {
            resp.put("error", "Item number is required."); return resp;
        }
        LocalDate f, t;
        try { f = LocalDate.parse(from.trim()); t = LocalDate.parse(to.trim()); }
        catch (Exception e) { resp.put("error", "Dates must be yyyy-MM-dd."); return resp; }
        if (f.isAfter(t)) { resp.put("error", "Start date is after end date."); return resp; }
        if (maxDepth < 1) maxDepth = 1;
        if (maxDepth > 99) maxDepth = 99;
        return service.query(item.trim(), f, t, maxDepth);
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/export")
    public void export(@RequestParam String item,
                       @RequestParam String from,
                       @RequestParam String to,
                       @RequestParam(required = false, defaultValue = "25") int maxDepth,
                       HttpServletResponse response) throws IOException {
        Map<String, Object> result = parseAndRun(item, from, to, maxDepth);
        if (result.containsKey("error")) {
            response.sendError(400, (String) result.get("error"));
            return;
        }
        String filename = "ECO-Timeline-" + item.trim() + "-" + from + "_" + to + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);
        List<EcoTimelineRow> rows = (List<EcoTimelineRow>) result.get("rows");
        try {
            excelExportService.exportTimeline(rows, item.trim(), from, to, response.getOutputStream());
        } catch (Exception e) {
            response.sendError(500, "Export failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/email")
    public Map<String, Object> email(@RequestParam String item,
                                     @RequestParam String from,
                                     @RequestParam String to,
                                     @RequestParam(required = false, defaultValue = "25") int maxDepth,
                                     HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");
        if (email == null || email.isEmpty()) {
            resp.put("success", false); resp.put("message", "Not logged in."); return resp;
        }
        Map<String, Object> result = parseAndRun(item, from, to, maxDepth);
        if (result.containsKey("error")) {
            resp.put("success", false); resp.put("message", (String) result.get("error")); return resp;
        }
        try {
            List<EcoTimelineRow> rows = (List<EcoTimelineRow>) result.get("rows");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            excelExportService.exportTimeline(rows, item.trim(), from, to, out);
            String filename = "ECO-Timeline-" + item.trim() + "-" + from + "_" + to + ".xlsx";
            String title = "ECO Timeline: " + item.trim() + " (" + from + " to " + to + ")";
            emailService.sendBomReport(email, displayName, out.toByteArray(), filename, title, rows.size());
            activityLogger.log(s(session, "username"), displayName,
                "ECO_TIMELINE_EMAIL", item.trim() + " | " + rows.size() + " rows emailed");
            resp.put("success", true);
            resp.put("message", "Report sent to " + email + " (" + rows.size() + " rows)");
        } catch (Exception e) {
            resp.put("success", false); resp.put("message", "Failed: " + e.getMessage());
        }
        return resp;
    }
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=$JAVA11 mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/EcoTimelineController.java
git commit -m "feat(eco-timeline): controller with query/export/email endpoints"
```

---

## Task 6: Frontend — panel, nav, and tab wiring

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `src/main/resources/static/app.js`

- [ ] **Step 1: Add the nav button** in `index.html`. After the BOM tab button (the line `<button class="tab" id="tabBom" onclick="switchTab('bom')">BOM</button>`, ~line 370), insert:

```html
    <button class="tab" id="tabEcoTimeline" onclick="switchTab('ecotimeline')">ECO Timeline</button>
```

- [ ] **Step 2: Add the panel** in `index.html`. Immediately before `<div id="panelParts" class="tab-panel" ...>` (~line 777), insert:

```html
<div id="panelEcoTimeline" class="tab-panel" style="display:none;">
  <div style="padding:8px 0 4px 0;">
    <span style="display:inline-flex; align-items:center; gap:6px; background:#eef4fb; border:1px solid #d6e4f5; color:#4a6fa5; font-size:11px; font-weight:600; padding:3px 10px; border-radius:12px;">
      Source: Agile PLM (live)
    </span>
  </div>
  <h2 style="margin:8px 0; font-family:'IBM Plex Serif',Georgia,serif;">ECO Timeline</h2>
  <p style="color:#6B7280; font-size:13px; margin:0 0 14px 0;">
    Enter an item and a date range to see every released ECO that changed any component in its full indented BOM, and what each ECO changed.
  </p>

  <div style="display:flex; flex-wrap:wrap; gap:14px; align-items:flex-end; margin-bottom:14px;">
    <div>
      <label style="display:block; font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.04em; margin-bottom:3px;">Item Number</label>
      <input id="etItemInput" type="text" placeholder="e.g. SDC256G32BA-0512N" style="width:280px; padding:7px 10px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;">
    </div>
    <div>
      <label style="display:block; font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.04em; margin-bottom:3px;">Start Date</label>
      <input id="etFromInput" type="date" style="padding:7px 10px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;">
    </div>
    <div>
      <label style="display:block; font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.04em; margin-bottom:3px;">End Date</label>
      <input id="etToInput" type="date" style="padding:7px 10px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;">
    </div>
    <div>
      <label style="display:block; font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.04em; margin-bottom:3px;">Max Depth</label>
      <input id="etMaxDepth" type="number" min="1" max="99" value="25" style="width:70px; padding:7px 10px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;">
    </div>
    <button onclick="etRun()" style="background:#2c3e50; color:#fff; border:none; padding:8px 18px; border-radius:6px; font-size:13px; cursor:pointer;">Run</button>
    <button onclick="etClear()" style="background:#fff; color:#0F1720; border:1px solid #E8E6DF; padding:8px 16px; border-radius:6px; font-size:13px; cursor:pointer;">Clear</button>
    <button id="etExportBtn" onclick="etExport()" style="background:#fff; color:#1F8A4C; border:1px solid #cde8d6; padding:8px 16px; border-radius:6px; font-size:13px; cursor:pointer; display:none;">Export Excel</button>
    <button id="etEmailBtn" onclick="etEmail()" style="background:#fff; color:#4a6fa5; border:1px solid #d6e4f5; padding:8px 16px; border-radius:6px; font-size:13px; cursor:pointer; display:none;">Email this view</button>
  </div>

  <div id="etKpis" style="display:none; gap:24px; margin:8px 0 16px 0;"></div>
  <div id="etStatus" style="color:#6B7280; font-size:13px;"></div>
  <div id="etResults"></div>
</div>
```

- [ ] **Step 3: Wire `switchTab`** in `app.js`. After the BOM panel display line (`document.getElementById('panelBom').style.display = tab === 'bom' ? '' : 'none';`, ~line 1226), insert:

```javascript
    var panelET = document.getElementById('panelEcoTimeline');
    if (panelET) panelET.style.display = tab === 'ecotimeline' ? '' : 'none';
```

- [ ] **Step 4: Wire the active-class** in `app.js`. After the BOM tab active-class line (`document.getElementById('tabBom').className = (tab === 'bom' || tab === 'bomcompare') ? 'tab active' : 'tab';`, ~line 1335), insert:

```javascript
    var tabETEl = document.getElementById('tabEcoTimeline');
    if (tabETEl) tabETEl.className = tab === 'ecotimeline' ? 'tab active' : 'tab';
```

- [ ] **Step 5: Register in `TAB_PREFS_CONFIG`** in `app.js`. After the `{ id: 'tabBom', label: 'BOM (Explorer + Compare)', key: 'bom' },` line (~line 2533), insert:

```javascript
    { id: 'tabEcoTimeline', label: 'ECO Timeline', key: 'ecotimeline' },
```

- [ ] **Step 6: Add the script tag** in `index.html`. Near the other script tags (e.g. after `<script src="audit-trail.js?v=20260513pt60"></script>`, ~line 4513), insert:

```html
<script src="eco-timeline.js?v=20260618a"></script>
```

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/index.html src/main/resources/static/app.js
git commit -m "feat(eco-timeline): add top-level tab, panel, and switchTab wiring"
```

---

## Task 7: Frontend — eco-timeline.js

**Files:**
- Create: `src/main/resources/static/eco-timeline.js`
- Modify: `src/main/resources/static/style.css`

- [ ] **Step 1: Write `eco-timeline.js`** (fetch → KPIs → table with per-column filters + sort → export/email)

```javascript
// ECO Timeline tab. Given item + date range, lists released ECOs that changed
// any component in the indented BOM. Talks to /api/eco-timeline/*.
(function () {
  var lastRows = [];          // unfiltered rows from the server
  var colFilters = {};        // column key -> lowercase filter text
  var sortKey = null, sortDir = 1;

  var COLS = [
    { key: 'level', label: 'Level' },
    { key: 'parentAssembly', label: 'Parent Assembly' },
    { key: 'component', label: 'Component #' },
    { key: 'componentDescription', label: 'Description' },
    { key: 'ecoNumber', label: 'ECO #' },
    { key: 'ecoReleaseDate', label: 'ECO Release Date' },
    { key: 'changeType', label: 'Change Type' },
    { key: 'detail', label: 'Detail' }
  ];

  function val(el) { return (document.getElementById(el).value || '').trim(); }

  window.etRun = function () {
    var item = val('etItemInput'), from = val('etFromInput'), to = val('etToInput');
    var maxDepth = val('etMaxDepth') || '25';
    if (!item) { appAlert('Enter an item number.'); return; }
    if (!from || !to) { appAlert('Pick both a start and an end date.'); return; }
    setStatus('Querying live Agile…');
    document.getElementById('etResults').innerHTML = '';
    var qs = 'item=' + encodeURIComponent(item) + '&from=' + from + '&to=' + to + '&maxDepth=' + maxDepth;
    fetch('/api/eco-timeline/query?' + qs)
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) { setStatus(''); appAlert(data.error); return; }
        lastRows = data.rows || [];
        colFilters = {}; sortKey = null;
        renderKpis(data);
        renderTable();
        document.getElementById('etExportBtn').style.display = lastRows.length ? '' : 'none';
        document.getElementById('etEmailBtn').style.display = lastRows.length ? '' : 'none';
        var notes = [];
        if (data.truncated) notes.push('result truncated (structure too large)');
        if (data.depthLimitReached) notes.push('max depth reached — increase Max Depth for deeper levels');
        setStatus(lastRows.length ? (notes.length ? '⚠ ' + notes.join('; ') : '')
                                  : 'No component changes found in this window.');
      })
      .catch(function (e) { setStatus(''); appAlert('Query failed: ' + e); });
  };

  window.etClear = function () {
    ['etItemInput', 'etFromInput', 'etToInput'].forEach(function (id) { document.getElementById(id).value = ''; });
    document.getElementById('etMaxDepth').value = '25';
    lastRows = []; colFilters = {}; sortKey = null;
    document.getElementById('etResults').innerHTML = '';
    document.getElementById('etKpis').style.display = 'none';
    document.getElementById('etExportBtn').style.display = 'none';
    document.getElementById('etEmailBtn').style.display = 'none';
    setStatus('');
  };

  window.etExport = function () {
    var qs = 'item=' + encodeURIComponent(val('etItemInput')) + '&from=' + val('etFromInput') +
             '&to=' + val('etToInput') + '&maxDepth=' + (val('etMaxDepth') || '25');
    window.location = '/api/eco-timeline/export?' + qs;
  };

  window.etEmail = function () {
    var qs = 'item=' + encodeURIComponent(val('etItemInput')) + '&from=' + val('etFromInput') +
             '&to=' + val('etToInput') + '&maxDepth=' + (val('etMaxDepth') || '25');
    fetch('/api/eco-timeline/email?' + qs, { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (d) { appAlert(d.message || (d.success ? 'Sent.' : 'Failed.')); })
      .catch(function (e) { appAlert('Email failed: ' + e); });
  };

  function setStatus(msg) { document.getElementById('etStatus').textContent = msg; }

  function renderKpis(data) {
    var el = document.getElementById('etKpis');
    el.style.display = 'flex';
    el.innerHTML =
      kpi('ECOs Found', data.ecoCount) +
      kpi('Components Affected', data.componentCount) +
      kpi('Date Span', data.from + ' → ' + data.to) +
      kpi('Query Time', (data.queryTimeMs || 0) + ' ms');
  }
  function kpi(label, value) {
    return '<div style="min-width:120px;"><div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.04em;">' +
      label + '</div><div style="font-size:20px; font-weight:700; color:#0F1720;">' + value + '</div></div>';
  }

  function pill(type) {
    var map = {
      'Added': 'et-pill-added', 'Removed': 'et-pill-removed',
      'Primary number changed': 'et-pill-primary', 'Quantity changed': 'et-pill-qty',
      'Find # changed': 'et-pill-find', 'Notes changed': 'et-pill-notes', 'Modified': 'et-pill-mod'
    };
    var cls = map[type] || 'et-pill-mod';
    return '<span class="et-pill ' + cls + '">' + esc(type) + '</span>';
  }

  function visibleRows() {
    var rows = lastRows.filter(function (r) {
      return COLS.every(function (c) {
        var f = colFilters[c.key];
        if (!f) return true;
        return String(r[c.key] == null ? '' : r[c.key]).toLowerCase().indexOf(f) !== -1;
      });
    });
    if (sortKey) {
      rows = rows.slice().sort(function (a, b) {
        var va = a[sortKey], vb = b[sortKey];
        if (sortKey === 'level') { va = +va; vb = +vb; }
        return (va < vb ? -1 : va > vb ? 1 : 0) * sortDir;
      });
    }
    return rows;
  }

  function renderTable() {
    var rows = visibleRows();
    var html = '<table class="et-table" style="width:100%; border-collapse:collapse; font-size:12.5px;">';
    html += '<thead><tr>';
    COLS.forEach(function (c) {
      var arrow = sortKey === c.key ? (sortDir === 1 ? ' ▲' : ' ▼') : '';
      html += '<th data-col="' + c.key + '" style="background:#2c3e50; color:#fff; text-align:left; padding:7px 9px; cursor:pointer; white-space:nowrap;">' +
        esc(c.label) + arrow + '</th>';
    });
    html += '</tr><tr>';
    COLS.forEach(function (c) {
      html += '<th style="background:#f4f5f7; padding:3px 6px;"><input data-filter="' + c.key +
        '" value="' + esc(colFilters[c.key] || '') + '" placeholder="filter" ' +
        'style="width:100%; box-sizing:border-box; font-size:11px; padding:3px 5px; border:1px solid #E8E6DF; border-radius:4px;"></th>';
    });
    html += '</tr></thead><tbody>';
    rows.forEach(function (r, i) {
      html += '<tr style="background:' + (i % 2 ? '#ffffff' : '#FAFAF7') + ';">';
      COLS.forEach(function (c) {
        var cell = c.key === 'changeType' ? pill(r[c.key]) : esc(String(r[c.key] == null ? '' : r[c.key]));
        html += '<td style="padding:6px 9px; border-bottom:1px solid #eee;">' + cell + '</td>';
      });
      html += '</tr>';
    });
    html += '</tbody></table>';
    if (!rows.length) html += '<p style="color:#6B7280; padding:10px 0;">No rows match the current filters.</p>';
    var container = document.getElementById('etResults');
    container.innerHTML = html;

    container.querySelectorAll('th[data-col]').forEach(function (th) {
      th.addEventListener('click', function () {
        var k = th.getAttribute('data-col');
        if (sortKey === k) sortDir = -sortDir; else { sortKey = k; sortDir = 1; }
        renderTable();
      });
    });
    container.querySelectorAll('input[data-filter]').forEach(function (inp) {
      inp.addEventListener('input', function () {
        colFilters[inp.getAttribute('data-filter')] = inp.value.trim().toLowerCase();
        renderTable();
      });
    });
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch];
    });
  }
})();
```

> Uses `appAlert` from `ui-modal.js` (already loaded), not native dialogs — required by project convention.

- [ ] **Step 2: Add change-type pill styles** to `style.css` (append at end):

```css
/* ECO Timeline change-type pills */
.et-pill { display:inline-block; padding:1px 8px; border-radius:10px; font-size:11px; font-weight:600; white-space:nowrap; }
.et-pill-added   { background:#e8f5e9; color:#1F8A4C; }
.et-pill-removed { background:#fdeaea; color:#B8342B; }
.et-pill-primary { background:#ede7f6; color:#7C3AED; }
.et-pill-qty     { background:#fff3cd; color:#856404; }
.et-pill-find    { background:#e8f0fe; color:#1a3a5c; }
.et-pill-notes   { background:#f0f0f0; color:#444; }
.et-pill-mod     { background:#fff3cd; color:#856404; }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/eco-timeline.js src/main/resources/static/style.css
git commit -m "feat(eco-timeline): frontend table, filters, export/email"
```

---

## Task 8: Manual end-to-end verification (live DB)

No automated DB test exists (repo convention). Verify against live Agile.

- [ ] **Step 1: Update What's New** (required by project pre-build rule). Add a new entry at the TOP of `WHATS_NEW_RELEASES` in `src/main/resources/static/whats-new.js`:

```javascript
  {
    date: '2026-06-18',
    title: 'ECO Timeline',
    items: [
      { type: 'new', text: 'New ECO Timeline tab: enter an item and a date range to see every released ECO that changed any component across the full indented BOM, with what each ECO changed (added, removed, primary number, quantity, find #, notes). Exportable to Excel.' }
    ]
  },
```

- [ ] **Step 2: Full build**

Run: `JAVA_HOME=$JAVA11 mvn -q -DskipTests package`
Expected: BUILD SUCCESS, `target/plm-field-tracker-1.0.1.jar` produced.

- [ ] **Step 3: Run locally** (these are direct JDBC queries to the QA Agile DB, which IS reachable from this machine)

```
cd ~/Documents/plm-toolkit\ 2
java -Xmx4g -jar ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar \
  --spring.config.additional-location=file:./config/application.properties
```
Open http://localhost:8090, log in (plmadmin), click **ECO Timeline**.

- [ ] **Step 4: Functional checks** — pick a known multi-level assembly (e.g. an SKU with sub-assemblies) and a date range with known ECO activity:
  - Verify rows appear with correct **Parent Assembly** attribution at multiple **Level** values (confirms recursion past level 1).
  - Verify an **Added**, a **Removed**, and at least one **Modify** (qty or primary number) classify correctly vs Agile's redline for one ECO.
  - Cross-check one ECO against the existing **BOM → Compare** tab (same part, the two revs around that ECO) — the add/remove/modify should agree.
  - Verify a component removed *during* the window still appears (evolved-union requirement): pick an ECO that removed a sub-assembly and confirm both the removal and any prior changes under it show.
  - Verify **Export Excel** downloads and matches the on-screen rows.
  - Verify **Email this view** sends to the logged-in user.
  - Verify date range is inclusive on both ends (an ECO released exactly on the start or end date appears).

- [ ] **Step 5: Commit the changelog + final state**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(eco-timeline): What's New entry for ECO Timeline tab"
```

- [ ] **Step 6: Deploy to staging** (per project rule — staging only, never live):

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
```
If `/Volumes/uls-ep-aglipccb/` is not mounted, report it and ask Vikas to mount — do not skip silently. Verify copied size matches `target/` with `stat -f "%z"`.

---

## Self-Review (completed during planning)

- **Spec coverage:** evolved-union (Task 3 `walkUnionTree` window-overlap edges) ✓; full recursion (`CONNECT BY` + per-assembly scan) ✓; change types add/remove/primary#/qty/find#/notes, ref-des excluded (Task 2 classifier + test `refDesOnlyModify_isSkipped`) ✓; released ECOs only + Pacific inclusive window (Task 3 `fromTs`/`toTs`, `RELEASE_DATE` not null) ✓; strictly component changes — attribute-only ECOs produce no row (classifier only emits on BOM-line redline) ✓; new top-level tab (Task 6) ✓; live Agile source (Task 3) ✓; output columns + KPIs (Tasks 4, 7) ✓; Excel export + email (Tasks 4, 5, 7) ✓; guardrails maxDepth + MAX_ASSEMBLIES + timeouts (Tasks 3, 5) ✓.
- **Placeholder scan:** none — every code step is complete.
- **Type consistency:** `BomRedlineRow` public fields used identically in tests, service, classifier; `EcoTimelineRow` getters used in classifier output, service sort/serialization, and export; `classifyAssembly(parentPn, level, rows, from, to)` signature consistent across Task 2 test, classifier, and Task 3 caller; change-type label constants (`EcoTimelineClassifier.ADDED` etc.) match the pill `map` keys in `eco-timeline.js` and the `style.css` classes.
- **Known validation risk:** the two Oracle queries (`walkUnionTree`, `fetchRedline`) are Oracle-specific and cannot be unit-tested on H2; Task 8 validates them against live data, with a hard handoff note if the DB is unreachable from this Mac.
