# IT Enhancements — Editable Grid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the IT Enhancements tab from a single editable column (`Target UAT`) to a spreadsheet-style editable grid covering 7 IT-owned fields (IT Owner, Hrs, Project, IT Actions, Target UAT, Target Go-Live, IT Log), with per-cell dirty/save state, keyboard navigation, fill-down, and per-user Agile write-back attribution.

**Architecture:** Vanilla JS port of the approved React prototype in `handoff/IT Enhancements/`. Reuses the existing batch save endpoints (`/save-batch`) and per-user transient SDK session in `plm-agile-service`. Generalizes the existing single-cell editor (`startEditUat` → `startEdit`), the dirty map (cellName-keyed), and the Agile write-back allowlist + value parser.

**Tech Stack:**
- Frontend: vanilla JS in `src/main/resources/static/it-enhancements.js`; CSS tokens via `handoff/tokens.css` (already linked from `index.html`)
- Backend (toolkit): Spring Boot, `ItEnhancementsController` (allowlist mirror)
- Backend (agile-service): Spring Boot + Agile SDK 9.3.6, `PerUserChangeUpdateController` (extend allowlist + per-cell-type value handlers)

**Reference files (read-only, do not modify):**
- `handoff/IT Enhancements/IT Enhancements Editable Grid.html` — CSS reference for the new grid
- `handoff/IT Enhancements/grid.jsx` — interaction model reference (React)
- `handoff/IT Enhancements/data.js` — COLUMNS shape reference + cell-name source
- `handoff/IT Enhancements/tokens.css` — design tokens (already linked in `index.html`)
- `IT-Enhancements-Editable-Grid-Spec.md` — the spec this plan implements

---

## File Structure

**Modify:**
- `src/main/resources/static/it-enhancements.js` — major rewrite (single-col → N-col editable grid, per-cell state machine, keyboard nav, fill-down)
- `src/main/resources/static/index.html` (`#panelItEnhancements`, lines 3512–3586) — minor: intro copy refresh, density toggle button, legend footer container, scoped CSS block for `.ite2-*` classes
- `src/main/java/com/sandisk/plm/tracker/controller/ItEnhancementsController.java` — small: add `EDITABLE_CELLS` mirror in `saveCell()` and `saveBatch()`
- `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/controller/PerUserChangeUpdateController.java` — major: extend `ALLOWED_CELLS`; dispatch on cellName to per-type handlers (date / number / list / multi-list / clob)
- `src/main/resources/static/whats-new.js` — top-of-array entry for the release

**No changes:**
- `AgileWriteBackClient.java` (the toolkit's HTTP client) — `updateChangeCellAsUser` already forwards `cellName` + value generically
- `ItEnhancementsService.java` (read path / SQL / cache) — the `Row` already contains every field we'll edit

---

## Constraints and Gotchas (lift from the spec)

1. **Cell names are guesses except `Target UAT Date`.** Verify in Phase 0 before any write code lands. A wrong cell name is a silent no-op or a write to the wrong attribute.
2. **`IT Owner` is a Users/list cell** (`page_three.LIST53`, user-ref slot). Display value is a person name (e.g. "Jindal, Vikas"); the SDK write almost certainly needs the loginId — confirm.
3. **`Project` and `IT Actions Taken` are multi-value** (`agile_flex` with comma-separated `entryid` IDs in the DB text column). The read joins display values with `, `; the write must set a multi-list selection, not a comma string.
4. **`Estimated Hours`** lives in `page_three.TEXT37` — confirm whether the SDK cell is typed as text or number.
5. **`IT Log`** is a CLOB. Never log its contents.
6. **No Agile SDK on this Mac.** All backend Agile-write code is written locally but tested by the user on the server (per `feedback_no_agile_writeback_on_this_machine.md`). The plan's "run it" steps reflect that handoff.
7. **No `scrollIntoView`** — the prototype nudges `scrollTop`/`scrollLeft` manually; production does the same.
8. **Keep the batch-save model.** Do not auto-save per cell.
9. **Snapshot cache:** after a successful save, patch the row locally via a `cellName → fieldKey` reverse map (generalizes the existing `if (cellName === 'Page Three.Target UAT Date') row.targetUAT = …`).
10. **Use `appAlert/appConfirm/appPrompt`, not native** — see `feedback_use_app_modals.md`. The IT Log popover should follow the same modal pattern.
11. **Pre-build `whats-new.js` update** — see `CLAUDE.md` "Pre-Build" rule. Phase 7 does this.

---

## Phase 0 — Verify cell names before writing any save code

The spec's #1 gotcha. Cheapest possible probe: a one-off diagnostic endpoint that fetches a sample IT-Enhancement Change, walks its Page Three table, and prints every cell name + value + SDK type. Run once on the server, record the verified names in this plan, delete the endpoint.

### Task 0.1: Add a temporary cell-introspection endpoint (agile-service)

**Files:**
- Modify: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/controller/PerUserChangeUpdateController.java`

- [ ] **Step 1: Add a temporary diagnostic GET endpoint at the bottom of the controller class**

```java
/** TEMPORARY — Phase 0 cell-name verification for the editable-grid project.
 *  Removes after the cell-name table in IT-Enhancements-Editable-Grid-Spec.md
 *  is updated with verified names. DO NOT call from the UI.
 *  Usage:  curl http://localhost:8081/api/_introspect/change/ECN-127538-PROJ/cells */
@org.springframework.web.bind.annotation.GetMapping("/_introspect/change/{ecn}/cells")
public ResponseEntity<Map<String, Object>> introspectChangeCells(@PathVariable("ecn") String ecn) {
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ecn", ecn);
    IAgileSession session = null;
    try {
        HashMap<Object, Object> params = new HashMap<>();
        params.put(AgileSessionFactory.USERNAME, "agileservice");
        params.put(AgileSessionFactory.PASSWORD, System.getenv("AGILE_SVC_PWD"));
        // NB: this runs as the service account — read-only introspection only.
        session = AgileSessionFactory.getInstance(agileUrl).createSession(params);
        IChange ch = (IChange) session.getObject(IChange.OBJECT_TYPE, ecn);
        if (ch == null) { resp.put("error", "not found"); return ResponseEntity.ok(resp); }
        java.util.List<Map<String, Object>> cells = new java.util.ArrayList<>();
        com.agile.api.ITable t = ch.getTable(com.agile.api.ChangeConstants.TABLE_PAGETHREE);
        com.agile.api.IRow row = (com.agile.api.IRow) t.iterator().next();
        for (Object o : row.getCells()) {
            ICell c = (ICell) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("dataType", c.getDataType());                       // int code — look up against AgileEnum
            m.put("attributeId", c.getAttribute().getId());
            m.put("value", c.getValue() == null ? null : c.getValue().toString());
            cells.add(m);
        }
        resp.put("cells", cells);
        return ResponseEntity.ok(resp);
    } catch (Exception e) {
        resp.put("error", describeError(e));
        return ResponseEntity.ok(resp);
    } finally {
        if (session != null) try { session.close(); } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 2: Build the JAR locally and stage it for the user to deploy**

Run (local Mac):
```bash
cd ~/git/plm-agile-service && mvn -q -DskipTests package
ls -la target/plm-agile-service-*.jar
```
Expected: `target/plm-agile-service-*.jar` exists, no compile errors.

- [ ] **Step 3: Tell the user to deploy + run the probe and paste the JSON back**

The probe runs against the service account. **Pick a Change whose IT fields are all populated** so every cell shows a non-null value (e.g. `ECN-127538-PROJ` from the prototype dataset, or any ECN with a Target UAT, Hours, Project, IT Actions, IT Log set in agprod — confirm with the user). Single curl on the server:
```bash
curl -s http://localhost:8081/api/_introspect/change/<ECN>/cells | python3 -m json.tool
```

- [ ] **Step 4: Fill the verified cell-name table into the plan**

After the user pastes the probe output, update the table below with verified names + data-type codes. Discard any guess that doesn't match.

| Field (`Row`) | Editable | Verified cell name | SDK data type code | Attribute ID | Notes |
|---|---|---|---|---|---|
| `targetUAT` | ✓ | `Page Three.Target UAT Date` | 3 (DATE) | 9423 | already in production |
| `targetGoLive` | ✓ | `Page Three.Target Go Live Date` | 3 (DATE) | 9424 | **spec was wrong**: no hyphen in "Go Live" |
| `hours` | ✓ | `Page Three.Effort  (person-hours)` | 2 (TEXT) | 1581 | **spec was wrong**: name is "Effort  (person-hours)" with a literal **double space** between "Effort" and "(". Stored as TEXT, not number — write expects a plain numeric string |
| `itOwner` | ✓ | `Page Three.Assigned IT Owner` | 4 (LIST single) | 1561 | **spec was wrong**: actual name is "Assigned IT Owner", and it is a regular single-select LIST (dataType 4), **not a Users user-ref slot**. Display value includes loginId in parens: `"Nagesh, Shruthi (7349728)"` — write almost certainly needs the matching list entry, not the bare name |
| `project` | ✓ | `Page Three.Project` | 5 (LIST multi) | 251747921 | confirmed multi-list, e.g. `"Single Sole Source Report"` |
| `itActions` | ✓ | `Page Three.IT Actions Taken` | 5 (LIST multi) | 251748003 | confirmed multi-list, e.g. `"Code Change (please fill in Jar Name/Class Name)"` |
| `itLog` | ✓ | `Page Three.IT Log` | 2 (TEXT) | 2000025513 | **spec was wrong about type**: it's TEXT (dataType 2), not CLOB. Content carries inline HTML (`<p>…</p>`). Display still treats it as long-form / never logged |

**Bonus discovery (not in the 7-field list, but worth noting):** `Page Three.IT Status` (TEXT, attributeId 1580) — also IT-owned, currently holds values like `"Rework"`. Decide whether the editable grid covers it too or leaves it read-only for now.

**Probe sample used:** `ECN-128313-PROJ` (Single/Sole Source Report) — chosen because all 7 IT fields are populated. Probe ran 2026-06-12 against the live agile-service on port 8081.

- [ ] **Step 5: Commit the introspection endpoint as a separate commit so it's easy to revert**

```bash
git -C ~/git/plm-agile-service add src/main/java/com/sandisk/plm/agile/controller/PerUserChangeUpdateController.java
git -C ~/git/plm-agile-service commit -m "phase0: temp cell introspection probe for IT-Enh editable grid"
```

### Task 0.2: Remove the introspection endpoint once verified

- [ ] **Step 1: Delete `introspectChangeCells` from `PerUserChangeUpdateController.java`** (revert the Task 0.1 commit's changes to that method, keeping the rest of the file).
- [ ] **Step 2: Commit:**
```bash
git -C ~/git/plm-agile-service commit -am "phase0: remove temp introspection endpoint (verified cell names recorded in plan)"
```

> **Checkpoint:** the cell-name table above is fully filled in and committed to this plan file before starting Phase 1. If a name is different from the spec's guess, update Phase 1 too (the `ALLOWED_CELLS` set must match).

---

## Phase 1 — Backend: extend allowlist + per-type write handlers

All work is in `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/controller/PerUserChangeUpdateController.java`. The toolkit-side allowlist mirror in `ItEnhancementsController.java` is a tiny shim added at the end of the phase.

### Task 1.1: Extend the `ALLOWED_CELLS` set

**Files:**
- Modify: `~/git/plm-agile-service/.../PerUserChangeUpdateController.java:54-57`

- [ ] **Step 1: Replace the `ALLOWED_CELLS` initializer** with the 7-cell set (use verified names from Phase 0):

```java
private static final java.util.Set<String> ALLOWED_CELLS = new java.util.HashSet<>(java.util.Arrays.asList(
        "Page Three.Target UAT Date",
        "Page Three.Target Go-Live Date",   // verify
        "Page Three.Estimated Hours",       // verify
        "Page Three.IT Owner",              // verify
        "Page Three.Project",               // verify
        "Page Three.IT Actions Taken",      // verify
        "Page Three.IT Log"                 // verify
));
```

- [ ] **Step 2: Verify the controller still compiles**
```bash
cd ~/git/plm-agile-service && mvn -q -DskipTests compile
```
Expected: BUILD SUCCESS.

### Task 1.2: Refactor `updateCellAsUser` to dispatch on cellName

**Files:**
- Modify: `~/git/plm-agile-service/.../PerUserChangeUpdateController.java:107-156` (the value-parse + `cell.setValue` block)

The current code assumes ISO date for every cell. Replace the parse + `setValue` block with a per-type dispatcher.

- [ ] **Step 1: Delete the date-only parse block** (the existing `java.util.Date dateValue = null; if (rawValue != null && …)` block).

- [ ] **Step 2: Replace the SDK session block** so that after opening the session, fetching `IChange ch` and `ICell cell`, the value is resolved by a helper that switches on cellName.

```java
session = AgileSessionFactory.getInstance(agileUrl).createSession(params);
IChange ch = (IChange) session.getObject(IChange.OBJECT_TYPE, ecn);
if (ch == null) {
    resp.put("ok", false);
    resp.put("error", "change not found: " + ecn);
    return ResponseEntity.ok(resp);
}
ICell cell = ch.getCell(cellName);
Object oldRaw = cell.getValue();
String oldStr = renderCellValueAsString(oldRaw);

String rawString = rawValue == null ? "" : rawValue.toString().trim();
SetResult sr = setCellValue(session, cell, cellName, rawString);
if (!sr.ok) {
    resp.put("ok", false);
    resp.put("error", sr.error);
    return ResponseEntity.ok(resp);
}

resp.put("ok", true);
resp.put("oldValue", oldStr);
resp.put("newValue", sr.newDisplayValue);
resp.put("elapsedMs", System.currentTimeMillis() - t0);
LOG.info("[PER-USER-WRITE] corrId=" + corrId + " ecn=" + ecn + " cell=" + cellName
        + " asUser=" + asUsername + " old=" + oldStr + " new=" + sr.newDisplayValue
        + " elapsedMs=" + (System.currentTimeMillis() - t0));
// NB: cellName="Page Three.IT Log" — DO NOT include value in the log line, only the display marker
return ResponseEntity.ok(resp);
```

- [ ] **Step 3: Add the `SetResult` value object and `setCellValue` helper at the bottom of the class**

```java
private static final class SetResult {
    final boolean ok;
    final String error;            // non-null on failure
    final String newDisplayValue;  // human-readable echo back to UI
    private SetResult(boolean ok, String error, String newDisplayValue) {
        this.ok = ok; this.error = error; this.newDisplayValue = newDisplayValue;
    }
    static SetResult ok(String display) { return new SetResult(true, null, display); }
    static SetResult err(String msg) { return new SetResult(false, msg, null); }
}

/** Per-cell-type value parser + setter. Dispatches on cellName because the
 *  SDK data-type code is not stable across attribute kinds (e.g. multi-list
 *  vs. single-list both report list-ish codes). */
private SetResult setCellValue(IAgileSession session, ICell cell,
                                String cellName, String raw) throws APIException {
    // Empty / null clears any cell.
    boolean clearing = (raw == null || raw.isEmpty());

    if ("Page Three.Target UAT Date".equals(cellName)
            || "Page Three.Target Go-Live Date".equals(cellName)) {
        if (clearing) { cell.setValue(null); return SetResult.ok(""); }
        try {
            java.util.Date d = new SimpleDateFormat("yyyy-MM-dd").parse(raw);
            cell.setValue(d);
            return SetResult.ok(new SimpleDateFormat("yyyy-MM-dd").format(d));
        } catch (java.text.ParseException pe) {
            return SetResult.err("date must be yyyy-MM-dd (got " + raw + ")");
        }
    }

    if ("Page Three.Estimated Hours".equals(cellName)) {
        if (clearing) { cell.setValue(""); return SetResult.ok(""); }
        // ESTIMATED HOURS is text37 — accept any string but enforce a basic
        // number shape so we don't write "abc" to a numeric attribute.
        if (!raw.matches("^\\d+(\\.\\d+)?$")) {
            return SetResult.err("hours must be a non-negative number (got " + raw + ")");
        }
        cell.setValue(raw);
        return SetResult.ok(raw);
    }

    if ("Page Three.IT Owner".equals(cellName)) {
        if (clearing) { cell.setValue(null); return SetResult.ok(""); }
        // raw is the display name ("Jindal, Vikas"). Resolve to a loginId
        // using the SDK's user lookup, then set.  IT Owner is a single-select
        // user-ref slot.
        com.agile.api.IUser user = findUserByDisplayName(session, raw);
        if (user == null) return SetResult.err("no Agile user matches '" + raw + "'");
        cell.setValue(user);  // single-user cell accepts an IUser
        return SetResult.ok(displayNameFor(user));
    }

    if ("Page Three.Project".equals(cellName)
            || "Page Three.IT Actions Taken".equals(cellName)) {
        if (clearing) { cell.setValue(null); return SetResult.ok(""); }
        // Multi-list: split on commas, trim, resolve each name to a listentry,
        // set the cell to the array of listentries.
        String[] parts = raw.split("\\s*,\\s*");
        com.agile.api.IAgileList listEntries = cell.getAvailableValues();
        java.util.List<Object> selected = new java.util.ArrayList<>();
        java.util.List<String> unresolved = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            com.agile.api.IAgileList entry = findListEntryByValue(listEntries, p);
            if (entry == null) { unresolved.add(p); continue; }
            selected.add(entry);
        }
        if (!unresolved.isEmpty()) {
            return SetResult.err("unknown list values: " + String.join(", ", unresolved));
        }
        cell.setValue(selected.toArray());
        return SetResult.ok(String.join(", ", java.util.Arrays.asList(parts)));
    }

    if ("Page Three.IT Log".equals(cellName)) {
        // CLOB — pass through. Never log raw.
        cell.setValue(clearing ? "" : raw);
        return SetResult.ok(clearing ? "" : ("(" + raw.length() + " chars)"));
        // NB: returning the size, NOT the contents, so newValue echo to UI is safe
    }

    return SetResult.err("no handler for cell: " + cellName);
}

private static String renderCellValueAsString(Object v) {
    if (v == null) return "";
    if (v instanceof java.util.Date) return new SimpleDateFormat("yyyy-MM-dd").format((java.util.Date) v);
    if (v instanceof com.agile.api.IUser) return displayNameFor((com.agile.api.IUser) v);
    return v.toString();
}

private static String displayNameFor(com.agile.api.IUser u) {
    try {
        String last = (String) u.getValue(com.agile.api.UserConstants.ATT_GENERAL_INFO_LAST_NAME);
        String first = (String) u.getValue(com.agile.api.UserConstants.ATT_GENERAL_INFO_FIRST_NAME);
        if (last == null) last = "";
        if (first == null) first = "";
        if (last.isEmpty() && first.isEmpty()) return (String) u.getValue(com.agile.api.UserConstants.ATT_GENERAL_INFO_USER_ID);
        return (last + ", " + first).trim();
    } catch (Exception e) { return "(user)"; }
}

private static com.agile.api.IUser findUserByDisplayName(IAgileSession session, String displayName) throws APIException {
    // displayName is "Last, First". Pull last + first and use the SDK query.
    String[] parts = displayName.split("\\s*,\\s*", 2);
    if (parts.length != 2) return null;
    String last = parts[0].trim();
    String first = parts[1].trim();
    com.agile.api.IUser u = (com.agile.api.IUser) session.getObject(
            com.agile.api.UserConstants.OBJECT_TYPE,
            new java.util.HashMap<Object, Object>() {{
                put(com.agile.api.UserConstants.ATT_GENERAL_INFO_LAST_NAME, last);
                put(com.agile.api.UserConstants.ATT_GENERAL_INFO_FIRST_NAME, first);
            }});
    return u;
}

private static com.agile.api.IAgileList findListEntryByValue(com.agile.api.IAgileList list, String value) throws APIException {
    if (list == null) return null;
    com.agile.api.IAgileList[] kids = list.getChildren();
    if (kids == null) return null;
    for (com.agile.api.IAgileList k : kids) {
        if (value.equalsIgnoreCase(k.getValue() == null ? "" : k.getValue().toString())) return k;
    }
    return null;
}
```

> **NB:** the user-lookup and listentry APIs above are the ones used in `~/git/plm-agile-service`'s existing controllers (e.g. `AgileWriteBackController.createDcoRich` for listentries, `agile-service`'s users-search for users). Read those before writing — exact method names on `IAgileList`/`IUser` differ slightly between SDK 9.3.x minor versions. If your `IUser` lookup returns null when the display-name search uses `Map<Object,Object>`, fall back to `session.getObject(UserConstants.OBJECT_TYPE, loginId)` with a loginId resolved from a separate users-search call.

- [ ] **Step 4: Compile**
```bash
cd ~/git/plm-agile-service && mvn -q -DskipTests compile
```
Expected: BUILD SUCCESS. If `findUserByDisplayName` / `findListEntryByValue` don't compile against your SDK jar, swap to the patterns used in the existing controllers under `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/`.

### Task 1.3: Mirror the allowlist on the toolkit side

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/ItEnhancementsController.java`

- [ ] **Step 1: Add the constant near the class top (under the `@Autowired` block, before `data()`):**

```java
/** Defense-in-depth mirror of plm-agile-service's PerUserChangeUpdateController.ALLOWED_CELLS.
 *  Reject unknown cellName here before forwarding. Must be kept in sync. */
private static final java.util.Set<String> EDITABLE_CELLS = new java.util.HashSet<>(java.util.Arrays.asList(
        "Page Three.Target UAT Date",
        "Page Three.Target Go-Live Date",
        "Page Three.Estimated Hours",
        "Page Three.IT Owner",
        "Page Three.Project",
        "Page Three.IT Actions Taken",
        "Page Three.IT Log"
));
```

- [ ] **Step 2: In `saveCell()` add a guard right after the `cellName` null/empty check (line ~141):**
```java
if (!EDITABLE_CELLS.contains(cellName.trim())) {
    resp.put("ok", false);
    resp.put("error", "cell not editable: " + cellName);
    return ResponseEntity.ok(resp);
}
```

- [ ] **Step 3: In `saveBatch()` add the same guard inside the per-edit loop (line ~222), after the ecn/cellName null check, before the `corrId = UUID.randomUUID()...` line:**
```java
if (!EDITABLE_CELLS.contains(cellName.trim())) {
    rowResp.put("ok", false);
    rowResp.put("error", "cell not editable: " + cellName);
    fails++;
    results.add(rowResp);
    continue;
}
```

- [ ] **Step 4: Compile**
```bash
cd ~/git/plm-field-tracker && mvn -q -DskipTests compile
```
Expected: BUILD SUCCESS.

### Task 1.4: Build the agile-service JAR for the user

- [ ] **Step 1:**
```bash
cd ~/git/plm-agile-service && mvn -q -DskipTests package
ls -la target/plm-agile-service-*.jar
```

- [ ] **Step 2: Commit:**
```bash
git -C ~/git/plm-agile-service add src/main/java/com/sandisk/plm/agile/controller/PerUserChangeUpdateController.java
git -C ~/git/plm-agile-service commit -m "feat(per-user-write): support number/list/multi-list/clob cells

Extends ALLOWED_CELLS to the 7-cell editable set for the IT Enhancements
grid. Adds a SetResult-based dispatcher in setCellValue() that handles
date (existing), number (Estimated Hours), single-list user (IT Owner),
multi-list (Project, IT Actions Taken), and CLOB (IT Log). IT Log raw
value is never logged."
```

- [ ] **Step 3: Hand off the JAR for the user to deploy + smoke-test each cell type per Phase 7.**

> **Checkpoint:** the user has deployed the new agile-service JAR and run a single curl against `/api/change/<ecn>/update-cell-as-user` for each of the 6 newly-supported cells (date already works). Report results back; if any cell type fails, fix the handler before moving on.

---

## Phase 2 — Frontend: COLUMNS model, cell render, per-cell dirty state

All frontend work lands in `src/main/resources/static/it-enhancements.js`. Keep the file IIFE-scoped (existing pattern); export only `iteInit`, `iteRefresh`, `iteForceRefresh`, `iteSaveAll`, the modal handlers, and any new entrypoints needed by `index.html`.

### Task 2.1: Replace the `COLUMNS` array with the typed shape

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js:36-55`

- [ ] **Step 1: Replace the existing `COLUMNS` array with the new shape (mirrors `handoff/IT Enhancements/data.js:41-60`):**

```js
// Per-column model. Drives both render and edit semantics.
//   type: 'ecn'|'priority'|'status'|'person'|'text'|'wrap'
//         |'number'|'date'|'longtext'
//   edit: true => developer-editable (renders fill-handle, hover affordance)
//   cell: Agile cell name sent on save (editable cols only — used as the
//         dirty map key AND looked up in the saved-row reverse map)
//   opts: name of a window.ITE_DATA-style list for select/datalist editors
//         ('OWNERS' | 'PROJECT_SUGGESTIONS' | 'ACTION_SUGGESTIONS')
//   w:    column width in CSS px
//   frozen: true => sticky-left column (ECN only)
var COLUMNS = [
    { key: 'ecnNumber',        label: 'ECN',              w: 116, type: 'ecn',      edit: false, frozen: true },
    { key: 'priority',         label: 'Priority',         w: 78,  type: 'priority', edit: false },
    { key: 'status',           label: 'IT Status',        w: 118, type: 'status',   edit: false },
    { key: 'workflowStatus',   label: 'Workflow',         w: 92,  type: 'text',     edit: false },
    { key: 'itOwner',          label: 'IT Owner',         w: 150, type: 'person',   edit: true,  cell: 'Page Three.IT Owner',           opts: 'OWNERS' },
    { key: 'requestor',        label: 'Requestor',        w: 140, type: 'person',   edit: false },
    { key: 'category',         label: 'Category',         w: 150, type: 'text',     edit: false },
    { key: 'problemStatement', label: 'Problem Statement',w: 230, type: 'wrap',     edit: false },
    { key: 'proposal',         label: 'Proposal',         w: 250, type: 'wrap',     edit: false },
    { key: 'hours',            label: 'Hrs',              w: 64,  type: 'number',   edit: true,  cell: 'Page Three.Estimated Hours' },
    { key: 'project',          label: 'Project',          w: 170, type: 'text',     edit: true,  cell: 'Page Three.Project',            opts: 'PROJECT_SUGGESTIONS' },
    { key: 'itActions',        label: 'IT Actions Taken', w: 180, type: 'text',     edit: true,  cell: 'Page Three.IT Actions Taken',   opts: 'ACTION_SUGGESTIONS' },
    { key: 'targetUAT',        label: 'Target UAT',       w: 118, type: 'date',     edit: true,  cell: 'Page Three.Target UAT Date' },
    { key: 'targetGoLive',     label: 'Target Go-Live',   w: 122, type: 'date',     edit: true,  cell: 'Page Three.Target Go-Live Date' },
    { key: 'reworkReason',     label: 'Rework Reason',    w: 140, type: 'text',     edit: false },
    { key: 'itLog',            label: 'IT Log',           w: 240, type: 'longtext', edit: true,  cell: 'Page Three.IT Log' },
    { key: 'submitDate',       label: 'Submitted',        w: 100, type: 'date',     edit: false },
    { key: 'releaseDate',      label: 'Released',         w: 100, type: 'date',     edit: false }
];
```

- [ ] **Step 2: Add a reverse map for saved-cell row patching (replaces the existing `if (res.cellName === 'Page Three.Target UAT Date') row.targetUAT = …` in `runSaveBatch`):**

```js
var CELL_TO_FIELD = (function () {
    var m = {};
    COLUMNS.forEach(function (c) { if (c.cell) m[c.cell] = c.key; });
    return m;
})();
```

- [ ] **Step 3: Add OWNERS / PROJECT_SUGGESTIONS / ACTION_SUGGESTIONS — but compute from rows, not hardcode**

Hardcoding the prototype lists in production would drift. Compute on every render of the filter dropdowns + the editors:
```js
// Computed lazily in render(); recomputed when STATE.rows changes.
var COMPUTED_OPTS = { OWNERS: [], PROJECT_SUGGESTIONS: [], ACTION_SUGGESTIONS: [] };
function recomputeOpts() {
    COMPUTED_OPTS.OWNERS = uniqueSorted(STATE.rows.map(function (r) { return r.itOwner; }));
    // Project + IT Actions Taken are stored comma-joined in the row — split for suggestions
    var projSet = {}, actSet = {};
    STATE.rows.forEach(function (r) {
        (r.project || '').split(/\s*,\s*/).forEach(function (p) { if (p) projSet[p] = 1; });
        (r.itActions || '').split(/\s*,\s*/).forEach(function (a) { if (a) actSet[a] = 1; });
    });
    COMPUTED_OPTS.PROJECT_SUGGESTIONS = Object.keys(projSet).sort();
    COMPUTED_OPTS.ACTION_SUGGESTIONS = Object.keys(actSet).sort();
}
```
Wire `recomputeOpts()` into `absorbResponse()` right after `STATE.rows = resp.rows || [];`.

### Task 2.2: Replace the dirty map shape

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js:17-32`

- [ ] **Step 1: Change `STATE.dirty` from `{ ecn → { cellName → value } }` to a single ckey map.**

```js
// dirty: ckey → { value, status, msg }
//   ckey = ecn + '||' + cellName  (one entry per edited cell)
//   status: 'pending' | 'saving' | 'saved' | 'error'
//   msg: validation reason (only meaningful on 'error')
dirty: {},
```

Add the ckey helper:
```js
function ckey(ecn, cellName) { return ecn + '||' + cellName; }
```

- [ ] **Step 2: Update every existing `STATE.dirty[ecn][cellName]` access site:**

There are 6 sites in the current file:
- `renderCell()` `dateEdit` branch (line ~318): use `STATE.dirty[ckey(r.ecnNumber, c.cell)]`
- `startEditUat()` `pending` read + write (line ~362, ~387–388): now generalized in Task 3.x; replace with `ckey(...)`
- `startEditUat()` cleanup-when-same-as-original (line ~384–385): delete `STATE.dirty[ckey(...)]`
- `refreshDirtyBadge()` (line ~418): iterate `Object.keys(STATE.dirty)`, count entries whose `.status` ∈ {`pending`, `error`}
- `iteSaveAll()` (line ~434–441): iterate `Object.keys(STATE.dirty)`, push `{ ecn, cellName, value }` for entries whose status is `pending`
- `runSaveBatch()` results loop (line ~472–485): on `res.ok`, locate by `ckey(res.ecn, res.cellName)`; patch the row using `CELL_TO_FIELD[res.cellName]`

### Task 2.3: Generalize `renderCell` to switch on the new `type`

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js:297-351`

- [ ] **Step 1: Replace `renderCell` with a type-driven version matching `handoff/IT Enhancements/grid.jsx:397-415` semantics.** Render the display, plus the corner state-dot when there's a dirty entry, plus the fill handle when the cell is active + editable + not currently being edited.

Cells should add `data-ri` / `data-ci` attributes so keyboard nav + fill-handle drag can locate them; the dirty-state class should be `st-pending` / `st-saving` / `st-saved` / `st-error` per the prototype CSS.

The active-cell highlight is `is-active` (2px accent ring); in-range is `is-inrange`; fill-end is `is-fillend`. Match the prototype class names exactly so we can reuse its CSS verbatim.

### Task 2.4: Reuse the prototype CSS by inlining a scoped `<style>` block in `index.html`

**Files:**
- Modify: `src/main/resources/static/index.html` — add a `<style>` block scoped to `#panelItEnhancements .ite2-*` selectors, immediately above the panel `<div>`.

- [ ] **Step 1: Copy the relevant CSS from `handoff/IT Enhancements/IT Enhancements Editable Grid.html:14-189`** (the `.ite2-root`, `.ite2-bands`, `.ite2-toolbar`, `.ite2-pills`, `.ite2-toast`, `.ite2-grid`, `.ite2-td`, dirty-state, fill-handle, popover, and legend rules) — but namespace every selector under `#panelItEnhancements ` (e.g. `.ite2-grid` → `#panelItEnhancements .ite2-grid`). Drop the `body.v2` selector chain since the toolkit doesn't add that class.

- [ ] **Step 2: Confirm tokens still resolve.** `tokens.css` defines its tokens on `:root`, so they're available without `body.v2`. The `body.v2 .u-mono` / `body.v2 .u-label` / `body.v2 .u-display` utility classes from `tokens.css` won't apply; the prototype CSS uses them so duplicate just those three rules into the new `<style>` block, unconditionally:
```css
#panelItEnhancements .u-mono  { font-family: var(--font-mono); }
#panelItEnhancements .u-label { font-family: var(--font-mono); font-size: var(--t-label); letter-spacing: var(--ls-label); text-transform: uppercase; color: var(--ink-3); font-weight: 500; }
#panelItEnhancements .u-display { font-family: var(--font-serif); font-size: var(--t-display); font-weight: 500; }
```

> **Checkpoint:** load `http://localhost:8090` after restarting locally; the grid renders with the new look (dark sticky header, ECN frozen left, hover highlight on editable cells, monospace dates). No console errors. Editing still works for `Target UAT` (we'll generalize editors next).

---

## Phase 3 — Frontend: spreadsheet interaction model

### Task 3.1: Track the active cell + selection range

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js` — add to `STATE`:

- [ ] **Step 1: Add selection state.**
```js
// Active cell + vertical range. Indices are over STATE.filtered (re-clamped
// every render). ci is over COLUMNS.
sel: { ri: 0, ci: -1, r2: 0 },  // ci=-1 means "first editable col" — initialized in absorbResponse
editing: false,
draft: ''
```

- [ ] **Step 2: Initialize `sel.ci` to the first editable column in `absorbResponse()` after `STATE.rows = …`:**
```js
if (STATE.sel.ci < 0) STATE.sel.ci = firstEditableColIndex();
function firstEditableColIndex() { for (var i = 0; i < COLUMNS.length; i++) if (COLUMNS[i].edit) return i; return 0; }
```

- [ ] **Step 3: After `render()` paints the body, mark the active cell with `is-active` and any in-range cells with `is-inrange`.** Don't re-render the whole body on selection change — toggle classes on the existing `<td>` nodes using `body.querySelector('td[data-ri="..."][data-ci="..."]')`.

### Task 3.2: Keyboard navigation when not editing

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js` — add a single delegated `keydown` listener on the grid container.

- [ ] **Step 1: Mirror the keymap from `handoff/IT Enhancements/grid.jsx:199-217`:**

| Key | Behavior |
|---|---|
| `ArrowDown` / `ArrowUp` | move `sel.ri` (Shift extends `r2`) |
| `ArrowRight` / `ArrowLeft` | move `sel.ci` (no extend) |
| `Tab` / `Shift+Tab` | jump to next/prev editable column (wraps to next row at the end) |
| `Enter` | start editing if `sel.ci` is editable |
| `Escape` | collapse range (`r2 = ri`) |
| `⌘/Ctrl+D` | fill-down (Task 3.6) |
| `Backspace` / `Delete` | clear the cell if editable + not longtext |
| printable char | start editing seeded with the char (text/number cols only) |

- [ ] **Step 2: The grid container needs `tabIndex="0"` to receive keys.** Add it in `index.html` to the `<table id="iteGrid">` element.

### Task 3.3: Edit start, commit, cancel

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js` — replace `startEditUat()` with a generic `startEdit(ri, ci, seedChar)`.

- [ ] **Step 1: Replace `startEditUat()` with `startEdit()` that switches on `col.type`:**

```js
function startEdit(ri, ci, seedChar) {
    var col = COLUMNS[ci]; if (!col.edit) return;
    var row = STATE.filtered[ri]; if (!row) return;
    if (col.type === 'longtext') { openLogPopover(row, col); return; }

    var td = document.querySelector('#iteGridBody td[data-ri="' + ri + '"][data-ci="' + ci + '"]');
    if (!td || td.querySelector('input, select, textarea')) return;

    var current = effValue(row, col);
    var startVal = seedChar != null ? seedChar : current;
    var input = buildEditor(col, startVal);
    td.innerHTML = '';
    td.appendChild(input);
    setTimeout(function () { input.focus(); if (input.select && input.type !== 'date') { try { input.select(); } catch (e) {} } }, 0);

    var committed = false;
    function commit(advance) {
        if (committed) return;
        committed = true;
        writeCellEdit(row, col, input.value || '');
        if (advance === 'down') moveSel(1, 0, false);
        else if (advance === 'right') moveToNextEditable(1);
        render();
    }
    function cancel() { committed = true; render(); }

    input.addEventListener('blur', function () { commit(null); });
    input.addEventListener('keydown', function (ev) {
        if (ev.key === 'Enter') { ev.preventDefault(); commit('down'); }
        else if (ev.key === 'Tab') { ev.preventDefault(); commit('right'); }
        else if (ev.key === 'Escape') { ev.preventDefault(); cancel(); }
    });
}

function buildEditor(col, startVal) {
    if (col.type === 'date') {
        var i = document.createElement('input'); i.type = 'date'; i.value = startVal || '';
        i.className = 'ite2-input'; return i;
    }
    if (col.type === 'number') {
        var n = document.createElement('input'); n.type = 'text'; n.inputMode = 'decimal';
        n.value = startVal || ''; n.className = 'ite2-input'; return n;
    }
    if (col.type === 'person') {
        var s = document.createElement('select'); s.className = 'ite2-input ite2-select';
        var blank = document.createElement('option'); blank.value = ''; blank.textContent = '—'; s.appendChild(blank);
        (COMPUTED_OPTS[col.opts] || []).forEach(function (o) {
            var opt = document.createElement('option'); opt.value = o; opt.textContent = o;
            if (o === startVal) opt.selected = true;
            s.appendChild(opt);
        });
        return s;
    }
    // text with datalist
    var t = document.createElement('input'); t.type = 'text'; t.className = 'ite2-input';
    t.value = startVal || ''; t.autocomplete = 'off';
    if (col.opts) {
        var listId = 'iteDL_' + col.key;
        t.setAttribute('list', listId);
        var dl = document.getElementById(listId);
        if (!dl) {
            dl = document.createElement('datalist'); dl.id = listId; document.body.appendChild(dl);
        }
        dl.innerHTML = (COMPUTED_OPTS[col.opts] || []).map(function (o) {
            return '<option value="' + escapeAttr(o) + '"></option>';
        }).join('');
    }
    return t;
}

function writeCellEdit(row, col, newVal) {
    var k = ckey(row.ecnNumber, col.cell);
    var committed = row[col.key] || '';
    if ((newVal || '') === committed) { delete STATE.dirty[k]; }
    else {
        var v = validateCell(col, newVal, row);
        STATE.dirty[k] = { value: newVal, status: v.ok ? 'pending' : 'error', msg: v.ok ? '' : v.msg, ecn: row.ecnNumber, cell: col.cell };
    }
    refreshDirtyBadge();
}

function effValue(row, col) {
    var d = STATE.dirty[ckey(row.ecnNumber, col.cell)];
    return d ? (d.value || '') : (row[col.key] || '');
}
```

### Task 3.4: Delete / Backspace clears editable cell

- [ ] **Step 1:** In the keyboard handler, on `Backspace` / `Delete` over an editable non-longtext cell, call `writeCellEdit(row, col, '')` + re-render.

### Task 3.5: Shift+arrow / Shift+click range selection

- [ ] **Step 1: In the keydown handler:** when `ArrowDown`/`ArrowUp` is pressed with `shiftKey`, update only `sel.r2` (clamped to `[0, filtered.length-1]`); leave `sel.ri`/`sel.ci` alone. Re-paint the in-range class on cells in column `sel.ci` between `min(ri,r2)` and `max(ri,r2)`.

- [ ] **Step 2: In the delegated `mousedown` handler on the grid body:** when a `<td>` is clicked with `shiftKey` AND its `data-ci` matches `sel.ci`, set `sel.r2 = data-ri`. Otherwise set `{ri,ci,r2}` to the clicked cell.

### Task 3.6: Ctrl/Cmd+D fill-down

- [ ] **Step 1: Add `fillDown()`:** copy the anchor cell's effective value into rows `min(ri,r2)` … `max(ri,r2)` of `sel.ci`. If `r2 === ri`, show a toast hinting at the range-select step (matches `handoff/IT Enhancements/grid.jsx:239-245`).

- [ ] **Step 2: Wire to keydown** for the editable column (no-op on read-only columns). Show success toast: `Filled "<val>" into N rows below.`

### Task 3.7: Fill handle drag-to-fill

- [ ] **Step 1: When rendering the active editable cell**, append a fill-handle `<span class="ite2-handle">` in the bottom-right.

- [ ] **Step 2: Bind `mousedown` to the handle**:
- prevent default on the handle to avoid triggering the cell's mousedown
- track `mousemove` on `document` — find the `<td>` under cursor via `document.elementFromPoint(...).closest('td[data-ri]')`; if its `data-ci` matches the source `ci`, set `STATE.fillDrag = parseInt(td.dataset.ri)`
- on `mouseup`, if `fillDrag != null && fillDrag !== startRi`, run `fillRange(ci, startRi, fillDrag)`, update `sel.r2 = fillDrag`, clear `fillDrag`, render.

### Task 3.8: Replace `scrollIntoView` with manual nudge

- [ ] **Step 1: After every `sel` change, ensure the active TD is visible.** Use the manual scroll algorithm from `handoff/IT Enhancements/grid.jsx:129-139` (compares `getBoundingClientRect()` of the cell and the scroll container, bumps `scrollTop`/`scrollLeft` by the delta + padding). DO NOT call `scrollIntoView`.

> **Checkpoint:** open the tab locally; verify with the keyboard: arrows move the active cell, Tab/Shift+Tab jumps editable columns only, Enter starts editing on editable cells, Esc collapses range. Shift+Down extends range; Ctrl+D fills. Fill handle drags. No scroll jumps. Dates still save under your identity.

---

## Phase 4 — Per-cell state machine + visuals + IT Log popover

### Task 4.1: Visual state per dirty cell

Already wired by the prototype CSS in Task 2.4. Confirm:
- [ ] `st-pending` → amber tint + amber corner dot
- [ ] `st-saving` → blue tint + pulsing dot
- [ ] `st-saved` → green tint + green dot
- [ ] `st-error` → red tint + red dot + tooltip with `msg`

### Task 4.2: Save flow status transitions

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js` — extend `iteSaveAll()` + `runSaveBatch()`.

- [ ] **Step 1: Before posting,** mark every `pending` dirty entry as `saving` and re-render. Skip `error` entries (don't include in batch).

```js
function iteSaveAll() {
    var edits = [];
    Object.keys(STATE.dirty).forEach(function (k) {
        var d = STATE.dirty[k];
        if (d.status !== 'pending') return;
        edits.push({ ecn: d.ecn, cellName: d.cell, value: d.value });
        d.status = 'saving';
    });
    if (!edits.length) {
        var errN = Object.keys(STATE.dirty).filter(function (k) { return STATE.dirty[k].status === 'error'; }).length;
        if (errN) showToast({ kind: 'warn', text: errN + ' cell(s) need attention before saving — see the red dots.' });
        return;
    }
    render();
    runSaveBatch(edits, false);
}
```

- [ ] **Step 2: In `runSaveBatch()` results loop:** on `ok`, set the dirty entry to `saved`; patch the row using `CELL_TO_FIELD[res.cellName]`; schedule a 2-second timer to remove the entry from `STATE.dirty` (mirror `handoff/IT Enhancements/grid.jsx:298-300`). On failure, set status back to `error` with `msg = res.error`; leave it in the dirty map so the user can fix.

- [ ] **Step 3:** Generalize the row-patch logic by replacing:
```js
if (row && res.cellName === 'Page Three.Target UAT Date') {
    row.targetUAT = res.newValue || '';
}
```
with:
```js
var field = CELL_TO_FIELD[res.cellName];
if (row && field) row[field] = res.newValue || '';
```

### Task 4.3: Client-side validation

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js` — add `validateCell()`.

- [ ] **Step 1:** Mirror `handoff/IT Enhancements/grid.jsx:35-44`:

```js
function validateCell(col, value, row) {
    if (col.type === 'number') {
        if (value && !/^\d+(\.\d+)?$/.test(value.trim())) return { ok: false, msg: 'Hours must be a non-negative number.' };
    }
    if (col.key === 'targetGoLive') {
        var uat = parseIsoDate(effValue(row, COLUMNS.find(function (c) { return c.key === 'targetUAT'; })));
        var gl = parseIsoDate(value);
        if (uat && gl && gl < uat) return { ok: false, msg: 'Go-Live is before Target UAT.' };
    }
    return { ok: true };
}
function parseIsoDate(s) { return s ? new Date(s + 'T00:00:00') : null; }
```

### Task 4.4: IT Log popover

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js` — add `openLogPopover()`.
- Modify: `src/main/resources/static/index.html` — reuse the existing `appAlert`/`appPrompt` modal helpers if they fit; otherwise add a dedicated `<div id="iteLogPopover">` modal block alongside `iteAgileSigninModal`.

- [ ] **Step 1:** Check `ui-modal.js` (per `feedback_use_app_modals.md`) for an existing textarea-modal helper. If `appPrompt` supports multiline / large textareas, use it. Otherwise add a dedicated popover element in `index.html` modeled on `iteAgileSigninModal`.

- [ ] **Step 2:** Wire `Cmd/Ctrl+Enter` = save, `Esc` = cancel. On save, call `writeCellEdit(row, COLUMNS.find(c => c.key === 'itLog'), val)`.

### Task 4.5: Density toggle (comfortable / compact)

**Files:**
- Modify: `src/main/resources/static/index.html` — add two `<button>`s in the toolbar (line ~3553, alongside Refresh now / Save all):
```html
<div class="ite2-density">
    <button id="iteDensComfortable" class="ite2-seg is-on" onclick="iteSetDensity('comfortable')">Comfortable</button>
    <button id="iteDensCompact" class="ite2-seg" onclick="iteSetDensity('compact')">Compact</button>
</div>
```
- Modify: `src/main/resources/static/it-enhancements.js` — `iteSetDensity()` toggles `is-compact` class on `#iteGrid`, persists choice to `localStorage` (key `ite-density`).

### Task 4.6: Legend footer

**Files:**
- Modify: `src/main/resources/static/index.html` — add after `</div><!-- end panelItEnhancements -->` is too far; add inside the panel just before the closing tag. Mirror the prototype's `.ite2-legend`:

```html
<div class="ite2-legend">
    <span class="u-label">Cell states</span>
    <span class="ite2-leg st-pending"><span class="ite2-leg-dot"></span>Pending — edited, not yet saved</span>
    <span class="ite2-leg st-saving"><span class="ite2-leg-dot"></span>Saving to Agile…</span>
    <span class="ite2-leg st-saved"><span class="ite2-leg-dot"></span>Saved under your identity</span>
    <span class="ite2-leg st-error"><span class="ite2-leg-dot"></span>Needs attention (validation)</span>
    <span class="ite2-leg-sep"></span>
    <span class="ite2-leg-kbd"><kbd>Tab</kbd> next field · <kbd>Enter</kbd> edit / down · <kbd>⌘/Ctrl</kbd>+<kbd>D</kbd> fill down · drag the corner handle</span>
</div>
```

> **Checkpoint:** local sanity. Edit a Hrs cell, watch the dot go pending. Click Save → dot goes saving, then saved (green), then fades. Type "abc" in Hrs → dot goes error (red) with tooltip. Edit Go-Live before UAT → error. Use Cmd+D on a range — fills down.

---

## Phase 5 — Update intro copy + panel scaffolding

### Task 5.1: Refresh the panel intro

**Files:**
- Modify: `src/main/resources/static/index.html:3514-3521`

- [ ] **Step 1: Replace the existing "Click a Target UAT cell…" copy** with the prototype's wording (mirrors `handoff/IT Enhancements/grid.jsx:471-479`):

```html
<div style="font-size:13px; color:#6B7280; margin-top:4px;">
    Live read of the IT Enhancement workflow against agprod. Editable columns are marked
    <span style="color:#4a6fa5;">✎</span>; click a cell and type. Every save is written
    to Agile under your own AD identity (not Administrator), so the change History attributes
    it correctly.
</div>
```

### Task 5.2: Cache-bust the `it-enhancements.js` reference

**Files:**
- Modify: `src/main/resources/static/index.html:4077`

- [ ] **Step 1: Bump the cache-bust query param** from `?v=20260604c` to `?v=YYYYMMDDa` (today's date + suffix). Use `?v=20260605a`.

---

## Phase 6 — Verify locally + manual UAT plan

### Task 6.1: Build the toolkit, run locally

- [ ] **Step 1: Build:**
```bash
cd ~/git/plm-field-tracker && mvn -q -DskipTests package
ls -la target/plm-field-tracker-1.0.1.jar
```

- [ ] **Step 2: Stage locally + restart:**
```bash
cp target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/
# user restarts with: cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties
```

- [ ] **Step 3: Drive the local app**

Open `http://localhost:8090`, log in as `plmadmin` (password from memory). Confirm:
- Grid renders with the new look (sticky ECN col on the left, dark header, edit pencils on the 7 editable column headers, monospace dates).
- Click + type: opens editor; Enter commits + drops down; Tab → next editable cell.
- Shift+Down → range; Cmd+D → fill-down.
- Hrs `abc` → red dot.
- Edit a Target UAT (the one we know works) → click Save all → no Agile error path because local has no agile-service running (this is expected — the modal will pop and stay; just observe pending → saving stalls cleanly).

Note: per `local_setup.md`, the Agile lookup microservice on 8081 is NOT running locally, so the actual save will fail with a connection error — that's fine. We're verifying UI behavior here. Real save testing happens in Phase 7.

### Task 6.2: Commit the toolkit changes

- [ ] **Step 1:**
```bash
git -C ~/git/plm-field-tracker add \
  src/main/resources/static/it-enhancements.js \
  src/main/resources/static/index.html \
  src/main/java/com/sandisk/plm/tracker/controller/ItEnhancementsController.java \
  src/main/resources/static/whats-new.js
git -C ~/git/plm-field-tracker commit -m "feat(it-enhancements): spreadsheet-style editable grid

Generalizes the IT Enhancements tab from one editable column (Target UAT)
to seven: IT Owner, Hrs, Project, IT Actions Taken, Target UAT,
Target Go-Live, IT Log. Adds keyboard navigation, range selection,
fill-down (Cmd/Ctrl+D + corner handle drag), per-cell state machine
(pending/saving/saved/error), client-side validation. Save still
batches through /save-batch under the user's own Agile identity.

Mirrors the approved prototype in handoff/IT Enhancements/ — vanilla JS
port, no React. Backend allowlist mirror added in the toolkit controller;
the agile-service end of the allowlist + per-type value handlers
ship in a separate commit in ~/git/plm-agile-service."
```

---

## Phase 7 — Server UAT (user-driven, since Mac can't reach the SDK)

This phase is run by Vikas on the server. The plan below documents what to test; mark each row done as it's confirmed.

### Task 7.1: Update `whats-new.js`

**Files:**
- Modify: `src/main/resources/static/whats-new.js` (add new entry at the top of `WHATS_NEW_RELEASES`)

- [ ] **Step 1:** Add the entry above the current top:
```js
{
    date: '2026-06-05',
    title: 'IT Enhancements — editable grid',
    items: [
        { badge: 'new', text: 'Spreadsheet-style editing on the <strong>IT Enhancements</strong> tab. Now editable: IT Owner, Hrs, Project, IT Actions Taken, Target UAT, Target Go-Live, IT Log. Click a cell, type, then Save all. Every write is attributed to your AD identity in the Agile History tab.' },
        { badge: 'new', text: 'Keyboard navigation across editable cells (Tab / Shift+Tab), range select (Shift+arrow / Shift+click), and fill-down (Cmd/Ctrl+D or drag the corner handle).' },
        { badge: 'improve', text: 'Per-cell state shows up as a corner dot: <em>pending</em> (amber), <em>saving</em> (blue pulse), <em>saved</em> (green), <em>error</em> (red, tooltip shows the reason).' }
    ]
}
```

### Task 7.2: Stage the JAR for the prod share + restage agile-service

- [ ] **Step 1:**
```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
stat -f "%z" ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/plm-field-tracker-1.0.1.jar
```
Both stat outputs must match. If `/Volumes/uls-ep-aglipccb/` isn't mounted, stop and ask Vikas to mount.

- [ ] **Step 2:** Hand `~/git/plm-agile-service/target/plm-agile-service-*.jar` to Vikas to deploy on the server alongside the toolkit JAR. The agile-service code must be live for the new cells to save.

### Task 7.3: Manual UAT script (Vikas drives)

For each editable column, edit one cell + Save and confirm:
- The grid's local row reflects the new value (no flicker; no need to Refresh).
- In Agile Web Client, open the ECN's History tab → the most recent row attributes the edit to the actual user (`Last, First (loginid)`), not "Administrator".
- For multi-list (Project, IT Actions Taken), the cell now shows the selected listentries — not a literal comma string.
- For IT Owner, the new owner reflects on both the row and Agile Web (verify both display name + that the underlying user-ref slot updated).
- For IT Log, the long text is intact; the toolkit log line records `(N chars)` not the contents.

- [ ] Target UAT — date
- [ ] Target Go-Live — date
- [ ] Estimated Hours — number
- [ ] IT Owner — single-list user-ref
- [ ] Project — multi-list
- [ ] IT Actions Taken — multi-list
- [ ] IT Log — CLOB

### Task 7.4: Burn the temporary introspection endpoint (if not done after Phase 0)

- [ ] Confirm `introspectChangeCells` is no longer in the deployed agile-service JAR.

---

## Self-Review

Run through the spec section by section against the plan:

- §1 Files in play — all five files are covered (Tasks 2/3/4 for it-enhancements.js; 2.4 + 4.5 + 4.6 for index.html; 1.3 for ItEnhancementsController; 1.1+1.2 for PerUserChangeUpdateController; AgileWriteBackClient unchanged ✓; ItEnhancementsService unchanged ✓).
- §2 Editable field set — all 7 columns wired in Tasks 2.1 + 1.1 + 1.2. Cell-name verification gated in Phase 0.
- §3 Column model — covered in 2.1.
- §4 Interaction model — covered in 3.1–3.8 (select, edit start, commit, navigate, delete, fill-down range + handle, scroll nudge).
- §5 Dirty / save state machine — covered in 2.2 (per-ckey shape) + 4.1 (visuals) + 4.2 (transitions + 2s fade + row patch via CELL_TO_FIELD).
- §6 Validation — covered in 4.3 (hours non-negative, golive >= uat).
- §7a Toolkit allowlist mirror — covered in 1.3.
- §7b agile-service allowlist + non-date handlers — covered in 1.1 + 1.2.
- §8 Acceptance criteria — covered in Phase 6 + 7.
- §9 Gotchas — addressed: verify cell names first (Phase 0), multi-value handler (1.2), IT Owner loginId (1.2 via `IUser` setValue), snapshot patch (4.2), keep batch save (no auto-save in 4.2), no scrollIntoView (3.8).

**Placeholder scan:** all code blocks are concrete. No "TBD" / "handle edge cases" / "similar to Task N". The only deliberate placeholder is the Phase 0 cell-name table which gets filled in mid-run; the steps that depend on it (1.1 mirror, 1.3 mirror) explicitly call out "use verified names from Phase 0".

**Type consistency:** `ckey(ecn, cellName)` used uniformly; `STATE.dirty[k] = { value, status, msg, ecn, cell }` shape consistent across `writeCellEdit`, `iteSaveAll`, `runSaveBatch`. `COMPUTED_OPTS` keyed by string names ('OWNERS', 'PROJECT_SUGGESTIONS', 'ACTION_SUGGESTIONS') matching the `col.opts` field.
