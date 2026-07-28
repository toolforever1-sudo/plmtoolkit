# IMS Dashboard Refinements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refine the IMS Dashboard per the 3-slide feedback deck — remove redundant chrome (Slide 1), restructure the summary tiles into three DRR-lifecycle groups (Slide 2), and rework the data-table columns (Slide 3).

**Architecture:** Frontend-first. The dashboard computes its tile segmentation client-side from `_state.adminRows`, so the new New-DRR / Legacy-DRR / Need-DRR tile model lives in a new pure JS module (`imsreview-classify.js`, unit-tested with `node --test`) consumed by `imsreview.js`. The backend gains three columns on the existing DRR change-join (`c.status`, `c.originator`, `c.create_date`) plus a DCO-create-date passthrough from queue events — no new queries, no SDK calls.

**Tech Stack:** Java 11 / Spring Boot 2.7 (Maven), vanilla ES5 browser JS, `node:test` for JS unit tests, Oracle SQL against the Agile schema.

**Reference spec:** `docs/superpowers/specs/2026-06-29-ims-dashboard-refinements-design.md`

---

## Constraints & verification reality (read first)

- **The Agile Oracle DB IS reachable from this Mac via JDBC** (only the Agile *SDK* / `plm-agile-service` :8081 is unavailable). The Task 1 SQL has been validated against live `agile_prod` via the `universal-db` MCP (connection `agile_prod`, username `agile`) — columns/joins resolve and real data is correct. Re-run the validation query there if you change the SQL. There is still no DB-backed JUnit harness, so the Java wiring is `mvn compile`-verified.
- **Resolved DRR status vocabulary** (live `agile_prod`, DRRs with `statustype IN (0,1,2)`): `Pending` (2663), `Submit` (1), `CCB` (5). Implemented/Release/Cancel are excluded by the existing `statustype` filter, so legacy in-scope DRRs are exactly Pending/Submit/CCB. `imsDrrStatusBucket()` is therefore exact, not a guess.
- **Use `c.create_date`, not `c.submit_date`**, for the DRR create date and the New/Legacy cutoff: `submit_date` is NULL for ~96% of Pending DRRs (never submitted), while `create_date` is never null.
- **No JS DOM test framework.** HTML-render functions are verified manually against the local app at `http://localhost:8090` (login `plmadmin`, heap ≥4g — see CLAUDE.md Local Setup). Only side-effect-free helpers are unit-tested (`Test/js/*.test.js`, run with `node --test Test/js/*.test.js`).
- **`imsreview.js` is a `window`-touching IIFE** and cannot be `require()`d in Node. All unit-tested logic therefore lives in the new `imsreview-classify.js` (no `window`/`document` references), loaded before `imsreview.js` in `index.html`.
- **Pre-build rule (CLAUDE.md):** update `src/main/resources/static/whats-new.js` before any `mvn package`. That is Task 6.

## File structure

| File | Responsibility | Task |
|---|---|---|
| `src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java` | Add DRR status/owner/create-date to SQL + `DocRow` + row map; DCO create-date passthrough | 1 |
| `src/main/resources/static/imsreview-classify.js` *(new)* | Pure tile-classification + counts (New/Legacy/Need-DRR); UMD-guarded | 2 |
| `Test/js/imsreview.test.js` *(new)* | `node:test` unit tests for the classifier | 2 |
| `src/main/resources/static/index.html` | Load `imsreview-classify.js` before `imsreview.js` | 2 |
| `src/main/resources/static/imsreview.js` | Remove funnel + DRR-created bar (Slide 1); grouped tile strip + tile filtering (Slide 2); table columns + copy + MM-DD-YYYY + filters (Slide 3) | 3,4,5 |
| `src/main/resources/static/whats-new.js` | Changelog entry | 6 |

---

## Task 1: Backend — DRR status / owner / create-date + DCO create-date

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java` (`DocRow` ~383-388; `pullDocsDueWithin()` SQL ~2650-2736; `dataForAdmin()` row map ~476-516; add `latestAgileDcoTs()` near `latestAgileDco()` ~621-628)

Confirmed column facts (validated against live `agile_prod`): the change's workflow status is `c.status` → `agile.nodetable.name` (values Pending/Submit/CCB); originator is `c.originator` → `agile.agileuser.id` (never null, formats "Last, First"); create date is `c.create_date` (never null — **not** `c.submit_date`, which is null for unsubmitted Pending DRRs).

- [ ] **Step 1: Add fields to `DocRow`**

Replace (lines 383-388):

```java
    /** Stripped-down doc row used by the admin view + send flow. */
    public static final class DocRow {
        public String docNumber, drrNumber, description, lifecyclePhase, rev, documentType;
        public String nextReviewDate;
        public List<OwnerRef> owners = new ArrayList<>();
    }
```

with:

```java
    /** Stripped-down doc row used by the admin view + send flow. */
    public static final class DocRow {
        public String docNumber, drrNumber, description, lifecyclePhase, rev, documentType;
        public String nextReviewDate;
        /** DRR change metadata (from the pc CTE in pullDocsDueWithin):
         *  drrStatus  = Agile workflow status node name (e.g. "Pending", "CCB").
         *  drrOwner   = DRR change originator, "Last, First".
         *  drrCreateDate = DRR change create_date as YYYY-MM-DD. */
        public String drrStatus, drrOwner, drrCreateDate;
        public List<OwnerRef> owners = new ArrayList<>();
    }
```

- [ ] **Step 2: Extend the `pc` CTE and SELECT in `pullDocsDueWithin()`**

Replace the `pc` CTE (lines 2668-2675):

```java
            "pc AS ( " +
            "  SELECT DISTINCT r.item AS item_id, c.change_number, sub.name AS change_type " +
            "  FROM   agile.rev    r " +
            "  JOIN   agile.change c   ON c.id = r.change AND c.statustype IN (0,1,2) AND NVL(c.delete_flag,0) != 1 " +
            "  JOIN   agile.nodetable sub ON sub.id = c.subclass " +
            "  WHERE  r.site = 0 AND r.item IN (SELECT id FROM qp) " +
            "    AND  c.change_number LIKE 'DRR-%' " +
            "), " +
```

with (adds status / originator / create_date; LEFT JOINs so a null status/originator never drops the DRR):

```java
            "pc AS ( " +
            "  SELECT DISTINCT r.item AS item_id, c.change_number, sub.name AS change_type, " +
            "         sn.name AS drr_status, " +
            "         orig.last_name || ', ' || orig.first_name AS drr_owner, " +
            "         TO_CHAR(c.create_date, 'YYYY-MM-DD') AS drr_create_date " +
            "  FROM   agile.rev    r " +
            "  JOIN   agile.change c   ON c.id = r.change AND c.statustype IN (0,1,2) AND NVL(c.delete_flag,0) != 1 " +
            "  JOIN   agile.nodetable sub ON sub.id = c.subclass " +
            "  LEFT JOIN agile.nodetable sn   ON sn.id = c.status " +
            "  LEFT JOIN agile.agileuser orig ON orig.id = c.originator " +
            "  WHERE  r.site = 0 AND r.item IN (SELECT id FROM qp) " +
            "    AND  c.change_number LIKE 'DRR-%' " +
            "), " +
```

Then replace the SELECT's DRR line (line 2690):

```java
            "       pc.change_number AS drr_number, " +
```

with:

```java
            "       pc.change_number AS drr_number, " +
            "       pc.drr_status AS drr_status, " +
            "       pc.drr_owner AS drr_owner, " +
            "       pc.drr_create_date AS drr_create_date, " +
```

- [ ] **Step 3: Populate the new fields in the `computeIfAbsent` row builder**

In `pullDocsDueWithin()`, inside the `computeIfAbsent` lambda (after line 2717 `x.nextReviewDate = nvl(rs.getString("next_review_date"));`), add:

```java
                            x.drrStatus = nvl(rs.getString("drr_status"));
                            x.drrOwner = nvl(rs.getString("drr_owner"));
                            x.drrCreateDate = nvl(rs.getString("drr_create_date"));
```

- [ ] **Step 4: Add `latestAgileDcoTs()` helper**

Immediately after `latestAgileDco()` (after line 628), add:

```java
    /** Timestamp of the event that created the latest DCO — surfaces as the
     *  "DCO Create Date" column. Mirrors latestAgileDco() but returns ev.ts. */
    private String latestAgileDcoTs(ImsReviewQueueStore.QueueItem q) {
        if (q == null || q.history == null) return null;
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            if (ev.agileDco != null && !ev.agileDco.trim().isEmpty()) return ev.ts;
        }
        return null;
    }
```

- [ ] **Step 5: Surface the new fields on the row map in `dataForAdmin()`**

Replace line 494:

```java
                row.put("drrCreated", q == null ? "" : earliestEventTs(q));
```

with (prefer the real DRR create_date; fall back to earliest queue event if create_date is somehow blank):

```java
                row.put("drrCreated", (d.drrCreateDate != null && !d.drrCreateDate.isEmpty())
                        ? d.drrCreateDate
                        : (q == null ? "" : earliestEventTs(q)));
                row.put("drrStatus", nvl(d.drrStatus));
                row.put("drrOwner", nvl(d.drrOwner));
```

Then replace the DCO block (lines 515-516):

```java
                String dco = latestAgileDco(q);
                if (dco != null && !dco.isEmpty()) row.put("agileDco", dco);
```

with:

```java
                String dco = latestAgileDco(q);
                if (dco != null && !dco.isEmpty()) row.put("agileDco", dco);
                String dcoTs = latestAgileDcoTs(q);
                if (dcoTs != null && !dcoTs.isEmpty()) row.put("dcoCreated", dcoTs.substring(0, Math.min(10, dcoTs.length())));
```

> Note: `lookupDoc()` (the DO/DM card path) is intentionally **not** changed — the card view does not show these columns, so leaving its `pc` CTE untouched keeps the change minimal. Logged as a known divergence.

- [ ] **Step 6: Compile**

Run: `JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-11* mvn -q -DskipTests compile 2>&1 | tail -20`
Expected: `BUILD SUCCESS` (or no compile errors).

Then validate the SQL itself against the live DB (the Mac can reach it). Run the `pc` CTE's new SELECT via the `universal-db` MCP on connection `agile_prod` and confirm `drr_status` ∈ {Pending, Submit, CCB}, `drr_owner` formats "Last, First", and `drr_create_date` is populated:

```sql
SELECT DISTINCT c.change_number, sn.name AS drr_status,
       orig.last_name || ', ' || orig.first_name AS drr_owner,
       TO_CHAR(c.create_date,'YYYY-MM-DD') AS drr_create_date
FROM agile.change c
JOIN agile.nodetable sub ON sub.id = c.subclass
LEFT JOIN agile.nodetable sn ON sn.id = c.status
LEFT JOIN agile.agileuser orig ON orig.id = c.originator
WHERE c.change_number LIKE 'DRR-%' AND NVL(c.delete_flag,0) != 1 AND c.statustype IN (0,1,2)
  AND sn.name IN ('Submit','CCB')
```
Expected: rows like `DRR-0019004 | CCB | Zhang, Alpha | 2026-06-22` (already verified once during planning).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java
git commit -m "feat(ims): surface DRR status/owner/create-date + DCO create-date on admin rows

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Pure tile-classification module + unit tests (TDD)

**Files:**
- Create: `src/main/resources/static/imsreview-classify.js`
- Create: `Test/js/imsreview.test.js`
- Modify: `src/main/resources/static/index.html` (add `<script>` include)

The classifier maps one row → `{group, tile}`:
- **need_drr / drr_missing** — `!hasDrr`
- **new / *** — `hasDrr` AND `drrCreated >= anchor`; sub-tile by queue `status` (with NEED OWNER overriding when `allOwnersLeft`)
- **legacy / *** — `hasDrr` AND `drrCreated < anchor`; sub-tile by the DRR's Agile `drrStatus` (NEED OWNER overriding when `allOwnersLeft`)

- [ ] **Step 1: Write the failing test**

Create `Test/js/imsreview.test.js`:

```javascript
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const C = require('../../src/main/resources/static/imsreview-classify.js');

const ANCHOR = '2026-07-05';
function row(o) { return Object.assign({ owners: [], status: 'NOT_SENT' }, o); }

test('no DRR -> need_drr / drr_missing', () => {
  assert.deepStrictEqual(
    C.imsClassifyTile(row({ hasDrr: false }), ANCHOR),
    { group: 'need_drr', tile: 'drr_missing' });
});

test('new DRR, not sent, valid owner -> new / pending_response', () => {
  const r = row({ hasDrr: true, drrCreated: '2026-08-01', status: 'NOT_SENT', hasValidOwner: true, allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.pending_response');
});

test('new DRR, sent to DM -> new / in_process', () => {
  const r = row({ hasDrr: true, drrCreated: '2026-08-01', status: 'SENT_TO_DM', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.in_process');
});

test('new DRR, DO needs change -> new / in_process', () => {
  const r = row({ hasDrr: true, drrCreated: '2026-08-01', status: 'DO_NEEDS_CHANGE', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.in_process');
});

test('new DRR, need help -> new / need_help', () => {
  const r = row({ hasDrr: true, drrCreated: '2026-08-01', status: 'DO_NEED_HELP', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.need_help');
});

test('new DRR, approved/cancelled -> new / closed', () => {
  assert.strictEqual(C.imsTileKey(row({ hasDrr: true, drrCreated: '2026-08-01', status: 'DM_APPROVED', allOwnersLeft: false }), ANCHOR), 'new.closed');
  assert.strictEqual(C.imsTileKey(row({ hasDrr: true, drrCreated: '2026-08-01', status: 'CANCELLED', allOwnersLeft: false }), ANCHOR), 'new.closed');
});

test('new DRR, all owners left overrides status -> new / need_owner', () => {
  const r = row({ hasDrr: true, drrCreated: '2026-08-01', status: 'SENT_TO_DM', allOwnersLeft: true });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.need_owner');
});

test('legacy DRR, Agile status Pending -> legacy / pending_response', () => {
  const r = row({ hasDrr: true, drrCreated: '2021-01-01', status: 'NOT_SENT', drrStatus: 'Pending', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'legacy.pending_response');
});

test('legacy DRR, Agile status CCB -> legacy / in_process', () => {
  const r = row({ hasDrr: true, drrCreated: '2021-01-01', status: 'NOT_SENT', drrStatus: 'CCB', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'legacy.in_process');
});

test('legacy DRR, all owners left -> legacy / need_owner', () => {
  const r = row({ hasDrr: true, drrCreated: '2021-01-01', drrStatus: 'CCB', allOwnersLeft: true });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'legacy.need_owner');
});

test('multi-owner: any one valid owner means not need_owner (derives flags)', () => {
  // No explicit allOwnersLeft -> derived from owners array; one ACTIVE = valid.
  const r = row({ hasDrr: true, drrCreated: '2026-08-01', status: 'SENT_TO_DO',
                  owners: [{ ldapStatus: 'NOT_FOUND' }, { ldapStatus: 'ACTIVE' }] });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.pending_response');
});

test('imsTileCounts tallies per key', () => {
  const rows = [
    row({ hasDrr: false }),
    row({ hasDrr: true, drrCreated: '2026-08-01', status: 'NOT_SENT', allOwnersLeft: false }),
    row({ hasDrr: true, drrCreated: '2026-08-01', status: 'SENT_TO_DM', allOwnersLeft: false }),
    row({ hasDrr: true, drrCreated: '2021-01-01', drrStatus: 'Pending', allOwnersLeft: false })
  ];
  const counts = C.imsTileCounts(rows, ANCHOR);
  assert.strictEqual(counts['need_drr.drr_missing'], 1);
  assert.strictEqual(counts['new.pending_response'], 1);
  assert.strictEqual(counts['new.in_process'], 1);
  assert.strictEqual(counts['legacy.pending_response'], 1);
});

test('imsDrrStatusBucket: pending vs submit_ccb', () => {
  assert.strictEqual(C.imsDrrStatusBucket('Pending'), 'pending');
  assert.strictEqual(C.imsDrrStatusBucket(''), 'pending');
  assert.strictEqual(C.imsDrrStatusBucket('CCB'), 'submit_ccb');
  assert.strictEqual(C.imsDrrStatusBucket('Submit'), 'submit_ccb');
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `node --test Test/js/imsreview.test.js`
Expected: FAIL — `Cannot find module '.../imsreview-classify.js'`.

- [ ] **Step 3: Write the classifier module**

Create `src/main/resources/static/imsreview-classify.js`:

```javascript
/* Pure tile-classification for the IMS Dashboard. No window/document refs so
 * it can be unit-tested under `node --test`. Loaded in the browser before
 * imsreview.js (exposes window.ImsClassify); required directly in tests. */
(function (root, factory) {
    var api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (typeof window !== 'undefined') window.ImsClassify = api;
})(this, function () {
    'use strict';

    function hasDrrOf(r) {
        if (typeof r.hasDrr === 'boolean') return r.hasDrr;
        return !!(r.drrNumber && r.drrNumber.trim && r.drrNumber.trim().length > 0);
    }

    // Mirror the server-side owner-health semantics for payloads that lack
    // the precomputed flags (older JAR mid-deploy). "Valid" = ACTIVE/UNKNOWN/null.
    function allOwnersLeftOf(r) {
        if (typeof r.allOwnersLeft === 'boolean') return r.allOwnersLeft;
        var owners = r.owners || [];
        var hasMissing = false, hasDisabled = false, hasValid = false;
        for (var i = 0; i < owners.length; i++) {
            var s = owners[i].ldapStatus;
            if (s === 'NOT_FOUND') hasMissing = true;
            else if (s === 'DISABLED') hasDisabled = true;
            else hasValid = true;
        }
        return owners.length > 0 && !hasValid && hasMissing && !hasDisabled;
    }

    // Map a legacy DRR's Agile workflow status name into the two buckets the
    // deck distinguishes. The in-scope statuses (statustype 0,1,2) are exactly
    // Pending / Submit / CCB, confirmed against live agile_prod — so this is an
    // exact map, not a guess. Anything else (incl. empty) falls to "pending".
    function imsDrrStatusBucket(name) {
        var s = (name || '').trim().toLowerCase();
        if (s === 'submit' || s === 'ccb') return 'submit_ccb';
        return 'pending';
    }

    function imsClassifyTile(r, anchor) {
        if (!hasDrrOf(r)) return { group: 'need_drr', tile: 'drr_missing' };
        var created = (r.drrCreated || '').substring(0, 10);
        var legacy = !!created && created < anchor;
        var allLeft = allOwnersLeftOf(r);
        if (legacy) {
            if (allLeft) return { group: 'legacy', tile: 'need_owner' };
            return { group: 'legacy', tile: imsDrrStatusBucket(r.drrStatus) === 'submit_ccb' ? 'in_process' : 'pending_response' };
        }
        // New DRR — driven by our own queue status. NEED OWNER overrides.
        if (allLeft) return { group: 'new', tile: 'need_owner' };
        switch (r.status) {
            case 'DO_NEED_HELP': return { group: 'new', tile: 'need_help' };
            case 'DM_APPROVED':
            case 'CANCELLED':    return { group: 'new', tile: 'closed' };
            case 'SENT_TO_DM':
            case 'DO_NEEDS_CHANGE': return { group: 'new', tile: 'in_process' };
            case 'SENT_TO_DO':
            case 'NOT_SENT':
            default: return { group: 'new', tile: 'pending_response' };
        }
    }

    function imsTileKey(r, anchor) {
        var c = imsClassifyTile(r, anchor);
        return c.group + '.' + c.tile;
    }

    function imsTileCounts(rows, anchor) {
        var counts = {};
        (rows || []).forEach(function (r) {
            var k = imsTileKey(r, anchor);
            counts[k] = (counts[k] || 0) + 1;
        });
        return counts;
    }

    return {
        imsDrrStatusBucket: imsDrrStatusBucket,
        imsClassifyTile: imsClassifyTile,
        imsTileKey: imsTileKey,
        imsTileCounts: imsTileCounts
    };
});
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `node --test Test/js/imsreview.test.js`
Expected: PASS — all tests green (`# pass 13`, `# fail 0`).

- [ ] **Step 5: Load the module in `index.html` before `imsreview.js`**

Run: `grep -n 'imsreview.js' src/main/resources/static/index.html`
Then immediately **before** the `<script src="imsreview.js"></script>` line found, insert:

```html
    <script src="imsreview-classify.js"></script>
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/imsreview-classify.js Test/js/imsreview.test.js src/main/resources/static/index.html
git commit -m "feat(ims): pure tile-classification module (New/Legacy/Need-DRR) + unit tests

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Slide 1 — remove the funnel strip and the DRR-created filter row

**Files:**
- Modify: `src/main/resources/static/imsreview.js`

- [ ] **Step 1: Remove the pipeline funnel from `renderKpiStrip()`**

In `renderKpiStrip()`, delete the entire "pipeline funnel" block (the `var notSent.../var inFlight.../var closed...`, the `total` line, the `function fseg(...)`, and the `var funnel = ...` assignment — lines 437-455). Then change the final assembly line:

```javascript
        kpiBox.innerHTML = funnel + banner + segHtml;
```

to:

```javascript
        kpiBox.innerHTML = banner + segHtml;
```

(The full new `renderKpiStrip` body is given in Task 4 Step 2; this step just records the funnel removal. If executing Task 4 immediately after, apply Task 4's full replacement instead.)

- [ ] **Step 2: Remove the DRR-created filter bar and its state/predicate**

a) Delete `renderDrrCreatedBar()` entirely (lines 683-698).

b) In `filterAdminRows()`, delete the DRR-created predicate block (lines 1390-1394):

```javascript
            if (_state.drrCreatedFilterOn && _state.drrCreatedAfter) {
                var created = (r.drrCreated || '').substring(0, 10);
                if (!created || created < _state.drrCreatedAfter) return false;
            }
```

c) In `renderAdminTable()`, remove the two `+ renderDrrCreatedBar()` calls (one in the empty-rows path ~line 575, one in the populated path ~line 583). Replace the empty-rows path's `emptyMsg` block (lines 568-579) with the simpler:

```javascript
            var emptyMsg = 'No matching documents — try a wider window, a different tile, or clear the column filters below.';
            adminBox.innerHTML = personalHint
                + '<p style="color:#6B7280; text-align:center; padding:20px 30px 6px;">' + emptyMsg + '</p>'
                + '<div style="text-align:center; margin-bottom:14px;">' + clearBtn + '</div>'
                + renderEmptyTableShellWithFilters();
            return;
```

d) Delete the now-unused `window.imsToggleDrrCreatedFilter` and `window.imsSetDrrCreatedAfter` handlers (grep: `grep -n 'imsToggleDrrCreatedFilter\|imsSetDrrCreatedAfter' src/main/resources/static/imsreview.js` and remove their `window.…= function` definitions).

e) In `_state` (top of file), remove the `drrCreatedFilterOn: false,` line. **Keep** `drrCreatedAfter: '2026-07-05'` — it is the New/Legacy anchor used by the classifier and `isLegacyDrr()`.

- [ ] **Step 3: Verify no dangling references**

Run: `grep -n 'renderDrrCreatedBar\|drrCreatedFilterOn\|imsToggleDrrCreatedFilter\|imsSetDrrCreatedAfter\|var funnel\|function fseg' src/main/resources/static/imsreview.js`
Expected: no matches.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/imsreview.js
git commit -m "feat(ims): Slide 1 — remove redundant funnel strip and DRR-created filter row

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Slide 2 — grouped tile strip + tile-based filtering

**Files:**
- Modify: `src/main/resources/static/imsreview.js`

This replaces the flat 6-segment model with three grouped sections of sub-tiles, driven by `ImsClassify`. The `_state.segment` string becomes `_state.tile` (a `'group.tile'` key).

- [ ] **Step 1: Swap segment state for tile state and retire segment helpers**

a) In `_state`, replace:

```javascript
        segment: 'ready_to_send',// 'ready_to_send' | 'needs_owner' | 'in_flight' | 'need_help' | 'closed'
```

with:

```javascript
        tile: 'new.pending_response', // 'group.tile' key — see imsreview-classify.js
        legacySubFilter: null,        // retained field (unused after Slide-2 rework)
```

b) Delete `rowMatchesSegment()` (lines 97-128) and `segmentCounts()` (lines 130-138) — superseded by `ImsClassify`.

c) Delete `renderLegacySubFilter()` (lines 766-806) and its `window.imsSetLegacySubFilter` handler (lines 808-811). The Legacy-owner sub-filter is replaced by the Legacy-DRR group's own NEED OWNER tile.

d) In `filterAdminRows()` delete the legacy-sub block (lines 1396-1407) and replace the segment line (line 1387):

```javascript
            if (!rowMatchesSegment(r, seg)) return false;
```

with:

```javascript
            if (ImsClassify.imsTileKey(r, _state.drrCreatedAfter) !== _state.tile) return false;
```

Also at the top of `filterAdminRows()` replace `var seg = _state.segment || 'ready_to_send';` with `var tileKey = _state.tile || 'new.pending_response';` and remove the now-unused `var legacySub = _state.legacySubFilter;` line.

- [ ] **Step 2: Replace `renderKpiStrip()` with the grouped-tile version**

Replace the whole `renderKpiStrip(kpiBox)` function (lines 420-515) with:

```javascript
    // Presentation metadata for the grouped tile strip. Classification lives
    // in imsreview-classify.js; this is purely labels/colors/order.
    var TILE_GROUPS = [
        { group: 'new', label: 'New DRR', hint: 'after go-live / via dashboard', tiles: [
            { tile: 'pending_response', label: 'Pending Response', color: '#C7801B', sub: 'awaiting owner response' },
            { tile: 'in_process',       label: 'In Process',       color: '#1F8A4C', sub: 'DRR/DCO at Submit / CCB' },
            { tile: 'need_owner',       label: 'Need Owner',       color: '#B8342B', sub: 'all owners inactive' },
            { tile: 'need_help',        label: 'Need Help',        color: '#5B21B6', sub: 'owner asked for help' },
            { tile: 'closed',           label: 'Closed',           color: '#0F1720', sub: 'DRR closed' }
        ] },
        { group: 'legacy', label: 'Legacy DRR', hint: 'via old process', tiles: [
            { tile: 'pending_response', label: 'Pending Response', color: '#C7801B', sub: 'DRR still at Pending' },
            { tile: 'in_process',       label: 'In Process',       color: '#1F8A4C', sub: 'DRR/DCO at Submit / CCB' },
            { tile: 'need_owner',       label: 'Need Owner',       color: '#B8342B', sub: 'all owners inactive' }
        ] },
        { group: 'need_drr', label: 'Need DRR', hint: 'IMS doc due, no DRR', tiles: [
            { tile: 'drr_missing', label: 'DRR Missing', color: '#6B7280', sub: 'trigger a new DRR' }
        ] }
    ];

    /** Coordinator dashboard header: owner-missing banner + three grouped
     *  tile sections (New DRR / Legacy DRR / Need DRR). Counts come from
     *  ImsClassify.imsTileCounts over the locally-held adminRows. */
    function renderKpiStrip(kpiBox) {
        if (!kpiBox) return;
        var legacySelect = document.getElementById('imsReviewStatusFilter');
        if (legacySelect && legacySelect.parentElement) {
            legacySelect.parentElement.style.display = 'none';
        }
        var d = _state.meta || {};
        var counts = ImsClassify.imsTileCounts(_state.adminRows || [], _state.drrCreatedAfter);

        // ---- Owner-missing banner (kept per Slide-1 decision) ------------
        var allLeft = (typeof d.kpiAllOwnersLeft === 'number') ? d.kpiAllOwnersLeft : (d.kpiOwnerMissing || 0);
        var disabled = d.kpiOwnerDisabled || 0;
        var banner = '';
        if (allLeft > 0) {
            banner = '<div style="background:#fdeaea; border-left:4px solid #B8342B; border-radius:0 6px 6px 0; padding:12px 16px; margin-top:12px; display:flex; align-items:center; justify-content:space-between; gap:14px;">'
                   + '<div style="color:#721c24; font-size:13px;">'
                   +   '<strong>' + allLeft.toLocaleString() + ' document' + (allLeft === 1 ? '' : 's')
                   +   ' need' + (allLeft === 1 ? 's' : '') + ' a new owner</strong>'
                   +   ' &mdash; every assigned Document Owner has left SanDisk.'
                   + '</div>'
                   + '<button onclick="imsReviewReassignAll()" '
                   +   'style="padding:8px 14px; background:#B8342B; color:#fff; border:0; border-radius:5px; cursor:pointer; font-weight:600; white-space:nowrap;">'
                   +   'Review &amp; reassign owners &rarr;'
                   + '</button>'
                   + '</div>';
        }
        if (disabled > 0) {
            banner += '<div style="background:#fff8e1; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; padding:10px 16px; margin-top:8px; font-size:12.5px; color:#5a4a1f;">'
                    + '<strong>' + disabled.toLocaleString() + '</strong> document' + (disabled === 1 ? ' has an owner whose' : 's have owners whose')
                    + ' SanDisk sign-in is disabled. They can still be reassigned from any row\'s &#9998; edit-owners action.'
                    + '</div>';
        }

        // ---- Three grouped tile sections ---------------------------------
        var groupsHtml = '<div style="display:flex; gap:14px; margin-top:14px; flex-wrap:wrap;">';
        TILE_GROUPS.forEach(function (g) {
            groupsHtml += '<div style="flex:1; min-width:240px; border:1px solid #E8E6DF; border-radius:8px; background:#fff; overflow:hidden;">'
                        + '<div style="padding:8px 12px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
                        +   '<div style="font-size:12px; font-weight:700; color:#0F1720;">' + esc(g.label) + '</div>'
                        +   '<div style="font-size:10.5px; color:#6B7280;">' + esc(g.hint) + '</div>'
                        + '</div>'
                        + '<div style="display:flex; flex-wrap:wrap;">';
            g.tiles.forEach(function (t) {
                var key = g.group + '.' + t.tile;
                var active = _state.tile === key;
                var bg = active ? '#FAFAF7' : '#fff';
                var topBorder = active ? '3px solid ' + t.color : '3px solid transparent';
                groupsHtml += '<button role="tab" onclick="imsTileClick(\'' + key + '\')" '
                            + 'style="flex:1 1 50%; min-width:110px; background:' + bg + '; border:0; border-top:' + topBorder
                            + '; border-right:1px solid #E8E6DF; border-bottom:1px solid #E8E6DF; padding:9px 11px; cursor:pointer; text-align:left;">'
                            + '<div style="font-size:10.5px; text-transform:uppercase; letter-spacing:0.4px; color:#6B7280;">' + esc(t.label) + '</div>'
                            + '<div style="font-size:18px; font-weight:700; color:' + t.color + ';">' + (counts[key] || 0).toLocaleString() + '</div>'
                            + '<div style="font-size:10px; color:#9CA3AF;">' + esc(t.sub) + '</div>'
                            + '</button>';
            });
            groupsHtml += '</div></div>';
        });
        groupsHtml += '</div>';

        kpiBox.innerHTML = banner + groupsHtml;
        kpiBox.style.display = 'block';
        kpiBox.style.gridTemplateColumns = '';
        kpiBox.style.background = 'transparent';
        kpiBox.style.gap = '';
    }
```

- [ ] **Step 3: Replace `imsSegmentClick` with `imsTileClick`**

Run: `grep -n 'imsSegmentClick' src/main/resources/static/imsreview.js` and replace the handler definition with:

```javascript
    window.imsTileClick = function (key) {
        _state.tile = key;
        _state.selectedDocs = {};   // clear selection on tile switch
        imsReviewRender();
    };
```

- [ ] **Step 4: Re-key bulk-action gating and per-row actions off the tile/status**

a) Replace `canBulkActOnSegment(seg)` (lines 757-759) with:

```javascript
    /** Bulk actions only make sense where every row's action is identical:
     *  Send (new pending-response = not-yet-sent) or Reassign (need-owner). */
    function canBulkActOnTile(key) {
        return key === 'new.pending_response' || key === 'new.need_owner' || key === 'legacy.need_owner';
    }
```

b) In `renderBulkBar(rows, seg)` (lines 813-842): rename the param to `key`, change the guard `if (!canBulkActOnSegment(seg)) return '';` to `if (!canBulkActOnTile(key)) return '';`, and change the primary-action branch `if (seg === 'ready_to_send') {` to `if (key === 'new.pending_response') {`.

c) Replace `segmentActionHtml(r, seg)` (lines 844-879) with a status-driven version (independent of which tile is shown):

```javascript
    /** Per-row action, decided by the row's own state (not the visible tile)
     *  so it is always correct. Never offers a Send that would bounce. */
    function segmentActionHtml(r) {
        var doc = esc(r.docNumber).replace(/'/g, "\\'");
        var drr = esc(r.drrNumber || '').replace(/'/g, "\\'");
        var resetBtn = (_state.meta && _state.meta.canSeeAdminView)
            ? '<button onclick="imsReset(\'' + doc + '\', \'' + drr + '\')" '
              + 'title="Wipes the queue state for this doc; full history is preserved in queue.jsonl" '
              + 'style="padding:4px 8px; font-size:11px; background:#fff; color:#B8342B; border:1px solid #E8E6DF; border-radius:4px; margin-left:3px; cursor:pointer;">⟲ Reset</button>'
            : '';
        var unlockBtn = (_state.meta && _state.meta.isAdmin
                && (r.status === 'SENT_TO_DO' || r.status === 'SENT_TO_DM'))
            ? '<button onclick="imsUnlockToken(\'' + doc + '\')" '
              + 'title="Clear the password-attempt lockout on this doc\'s active link(s)" '
              + 'style="padding:4px 8px; font-size:11px; background:#fff; color:#5B21B6; border:1px solid #E8E6DF; border-radius:4px; margin-left:3px; cursor:pointer;">&#x1F511; Unlock</button>'
            : '';
        var hasDrr = (typeof r.hasDrr === 'boolean') ? r.hasDrr : !!(r.drrNumber && r.drrNumber.trim && r.drrNumber.trim().length > 0);
        if (!hasDrr) return '<span style="color:#6B7280; font-size:11px;">needs DRR</span>';
        var allLeft = (typeof r.allOwnersLeft === 'boolean') ? r.allOwnersLeft : false;
        if (allLeft) return '<button onclick="imsOpenEditOwners(\'' + doc + '\')" style="padding:4px 10px; font-size:11px; background:#B8342B; color:#fff; border:0; border-radius:4px; font-weight:600; cursor:pointer;">👤 Assign owner</button>';
        switch (r.status) {
            case 'NOT_SENT':
                return '<button onclick="imsSend(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 10px; font-size:11px; background:#2c3e50; color:#fff; border:0; border-radius:4px; font-weight:600; cursor:pointer;">📤 Send</button>';
            case 'SENT_TO_DO':
            case 'SENT_TO_DM':
            case 'DO_NEEDS_CHANGE':
                return '<button onclick="imsSend(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 8px; font-size:11px; background:#fff; color:#4a6fa5; border:1px solid #E8E6DF; border-radius:4px; margin-right:3px; cursor:pointer;">↻ Resend</button>'
                     + '<button onclick="imsCancel(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 8px; font-size:11px; background:#fff; color:#B8342B; border:1px solid #E8E6DF; border-radius:4px; cursor:pointer;">✕ Cancel</button>'
                     + unlockBtn + resetBtn;
            case 'DO_NEED_HELP':
                return '<span style="color:#6B7280; font-size:11px;">⌐ DCC handling</span>' + resetBtn;
            case 'DM_APPROVED':
            case 'CANCELLED':
                return '<span style="color:#6B7280; font-size:11px;">read-only</span>' + resetBtn;
            default:
                return '';
        }
    }
```

- [ ] **Step 5: Update `renderAdminTable()` call sites for the renamed helpers**

In `renderAdminTable()`:
- Replace `var seg = _state.segment || 'ready_to_send';` with `var tileKey = _state.tile || 'new.pending_response';`
- Replace `var canBulk = canBulkActOnSegment(seg);` with `var canBulk = canBulkActOnTile(tileKey);`
- Remove the `html += renderLegacySubFilter(seg);` line.
- Replace `html += renderBulkBar(rows, seg);` with `html += renderBulkBar(rows, tileKey);`
- In the body loop, replace `segmentActionHtml(r, seg)` with `segmentActionHtml(r)`.

(The header/filter/cell column changes are Task 5 — leave them for now; the table still renders the old columns.)

- [ ] **Step 6: Verify no dangling references**

Run: `grep -n '_state.segment\|rowMatchesSegment\|segmentCounts\|renderLegacySubFilter\|canBulkActOnSegment\|imsSegmentClick\|imsSetLegacySubFilter' src/main/resources/static/imsreview.js`
Expected: no matches.

- [ ] **Step 7: Manual smoke test (local app)**

Start the app per CLAUDE.md Local Setup, log in as `plmadmin`, open the IMS Dashboard tab. Confirm: funnel gone; three grouped tile sections render with counts; clicking a tile filters the table; owner-missing red banner still present. (See Task 6 for the run command.)

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/imsreview.js
git commit -m "feat(ims): Slide 2 — grouped New/Legacy/Need-DRR tiles replace flat segments

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Slide 3 — data-table columns

**Files:**
- Modify: `src/main/resources/static/imsreview.js`

Adds DRR Owner, DRR Create Date, DCO, DCO Status, DCO Owner, DCO Create Date; reformats DRR/DCO/Next-Review dates to `MM-DD-YYYY`; drops the DRR "legacy" badge; adds copy affordances and per-column filters.

- [ ] **Step 1: Add the `MM-DD-YYYY` formatter and copy helpers**

After `fmtDate()` (line 50), add:

```javascript
    /** Format an ISO yyyy-mm-dd as MM-DD-YYYY (no timestamp). '' for empty. */
    function fmtMMDDYYYY(iso) {
        if (!iso) return '';
        var s = String(iso).substring(0, 10);
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
        if (!m) return esc(iso);
        return m[2] + '-' + m[3] + '-' + m[1];
    }

    /** Append a tiny click-to-copy glyph after a rendered number/link. */
    function withCopy(html, raw) {
        if (!raw) return html;
        var safe = String(raw).replace(/'/g, '');
        return html + '<span onclick="imsCopyNum(\'' + esc(safe) + '\')" title="Copy ' + esc(raw) + '" '
             + 'style="cursor:pointer; margin-left:4px; color:#9CA3AF; font-size:10px; user-select:none;">&#9106;</span>';
    }
    window.imsCopyNum = function (t) {
        try { navigator.clipboard.writeText(t); showToast('Copied ' + t); }
        catch (e) { showToast('Copy failed'); }
    };
```

- [ ] **Step 2: Replace the table header row + filter row**

In `renderAdminTable()`, replace the `<thead>` assembly (lines 588-608, the two `<tr>` blocks for headers and column filters) with:

```javascript
        html += '<thead><tr style="background:#2c3e50; color:#fff;">'
              + (canBulk ? '<th style="text-align:center; padding:8px 6px; width:32px;"><input type="checkbox" onclick="imsToggleAllSelected(this.checked)" title="Select all visible" id="imsSelectAll"></th>' : '<th style="width:0; padding:0;"></th>')
              + sortableTh('docNumber',       'Number / Description')
              + sortableTh('drrNumber',       'DRR')
              + '<th style="text-align:left; padding:8px 10px;">DRR Owner</th>'
              + '<th style="text-align:left; padding:8px 10px;">DRR Created</th>'
              + '<th style="text-align:left; padding:8px 10px;">DCO</th>'
              + '<th style="text-align:left; padding:8px 10px;">DCO Status</th>'
              + '<th style="text-align:left; padding:8px 10px;">DCO Owner</th>'
              + '<th style="text-align:left; padding:8px 10px;">DCO Created</th>'
              + sortableTh('owners',          'Owner(s)')
              + sortableTh('nextReviewDate',  'Next Review')
              + sortableTh('status',          'Status')
              + '<th style="text-align:left; padding:8px 10px;">File</th>'
              + '<th style="text-align:left; padding:8px 10px;">Action</th>'
              + '</tr>'
              + '<tr style="background:#FAFAF7;">'
              +   (canBulk ? '<td></td>' : '<td style="width:0; padding:0;"></td>')
              +   colFilterCell('docNumber',       'Filter doc # or description…', true)
              +   colFilterCell('drrNumber',       'DRR-…')
              +   colFilterCell('drrOwner',        'name')
              +   colFilterCell('drrCreated',      'MM-DD-YYYY')
              +   colFilterCell('agileDco',        'DCO-…')
              +   colFilterCell('dcoStatus',       '—')
              +   colFilterCell('dcoOwner',        '—')
              +   colFilterCell('dcoCreated',      'MM-DD-YYYY')
              +   colFilterCell('ownerDisplay',    'name or loginid')
              +   colFilterCell('nextReviewDate',  'MM-DD-YYYY')
              +   colFilterCell('status',          'status')
              +   '<td></td>'
              +   '<td></td>'
              + '</tr>'
              + '</thead><tbody>';
```

- [ ] **Step 3: Update the band-divider colSpan**

Replace `var colSpan = (canBulk ? 1 : 0) + 8;` (line 619) with:

```javascript
        var colSpan = (canBulk ? 1 : 0) + 13;
```

- [ ] **Step 4: Replace the per-row cell assembly**

In the `rows.forEach` body of `renderAdminTable()`, replace the cell-building block from the Number/Description cell through the Action cell (lines 650-678) with:

```javascript
            // Number / Description
            html += '<td style="padding:7px 10px;">'
                 + '<div style="font-family:monospace; font-weight:600;">' + withCopy(agileItemLink(r.docNumber), r.docNumber) + '</div>'
                 + '<div style="color:#6B7280; font-size:11px; margin-top:1px;">' + esc(r.description) + '</div>'
                 + '</td>';
            // DRR (no legacy badge)
            html += '<td style="padding:7px 10px; font-family:monospace;">' + (r.drrNumber ? withCopy(agileChangeLink(r.drrNumber), r.drrNumber) : '') + '</td>';
            // DRR Owner / DRR Created
            html += '<td style="padding:7px 10px; color:#4a6fa5; font-size:11px;">' + esc(r.drrOwner || '') + '</td>';
            html += '<td style="padding:7px 10px; font-size:11px;">' + esc(fmtMMDDYYYY(r.drrCreated)) + '</td>';
            // DCO / DCO Status / DCO Owner / DCO Created
            var muted = '<span style="color:#9CA3AF;">—</span>';
            html += '<td style="padding:7px 10px; font-family:monospace;">' + (r.agileDco ? withCopy(agileChangeLink(r.agileDco), r.agileDco) : muted) + '</td>';
            html += '<td style="padding:7px 10px;">' + muted + '</td>';
            html += '<td style="padding:7px 10px;">' + muted + '</td>';
            html += '<td style="padding:7px 10px; font-size:11px;">' + (r.dcoCreated ? esc(fmtMMDDYYYY(r.dcoCreated)) : muted) + '</td>';
            // Owners
            html += '<td style="padding:7px 10px; color:#4a6fa5; font-size:11px;">' + renderOwnersCell(r) + '</td>';
            // Next Review (MM-DD-YYYY) with overdue sublabel
            html += '<td style="padding:7px 10px;">'
                 + '<div style="' + (od ? 'color:#B8342B; font-weight:600;' : '') + '">' + esc(fmtMMDDYYYY(nrd)) + '</div>'
                 + (od ? '<div style="font-size:10.5px; color:#B8342B; margin-top:1px;">' + esc(od) + '</div>' : '')
                 + '</td>'
                 // Status (DCO now has its own column, so renderDcoSubLink is dropped here)
                 + '<td style="padding:7px 10px;">' + statusPill(r.status) + renderHelpNote(r) + '</td>'
                 + '<td style="padding:7px 10px;"><button onclick="imsGetFile(\'' + doc + '\')" style="padding:4px 8px; font-size:11px; background:#fff; color:#4a6fa5; border:1px solid #E8E6DF; border-radius:4px; cursor:pointer;">⬇ Get File</button>'
                 + renderUploadedAttachmentLink(r) + '</td>'
                 + '<td style="padding:7px 10px;">' + segmentActionHtml(r) + '</td>'
                 + '</tr>';
```

> `renderDcoSubLink(r)` is intentionally dropped from the Status cell (DCO is now its own column). Leave the function defined (harmless) or delete it; if deleting, also remove its single former call — already removed here.

- [ ] **Step 5: Verify references resolve**

Run: `grep -n 'fmtMMDDYYYY\|withCopy\|imsCopyNum' src/main/resources/static/imsreview.js`
Expected: definitions + uses present. Then `grep -n 'isLegacyDrr' src/main/resources/static/imsreview.js` — `isLegacyDrr` may now be unused (badge removed); leave it (used nowhere is harmless) or delete its definition if you prefer a clean tree.

- [ ] **Step 6: Manual smoke test (local app)**

Reload the IMS Dashboard. Confirm: new columns present in order; DRR/DCO/Next-Review dates show `MM-DD-YYYY`; DRR legacy badge gone; DCO Status/Owner show `—`; copy glyph copies the number; per-column filter inputs filter (try DRR Owner and DRR Created). Note: DRR Owner / DRR Created / DCO Created populate only once Task 1 is deployed to the server (local snapshot data may be blank — that is expected on this Mac).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/imsreview.js
git commit -m "feat(ims): Slide 3 — table columns (DRR/DCO owner+status+dates), MM-DD-YYYY, copy, per-column filters

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Changelog + build + staging

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Add a What's New entry**

Run: `grep -n 'WHATS_NEW_RELEASES' src/main/resources/static/whats-new.js | head -1` to find the array start, then insert a new entry at the **top** of the `WHATS_NEW_RELEASES` array:

```javascript
    {
        date: '2026-06-29',
        title: 'IMS Dashboard refinements',
        items: {
            improve: [
                'Summary tiles regrouped into New DRR / Legacy DRR / Need DRR, each with its own sub-states.',
                'Removed the redundant Not sent / In flight / Closed funnel and the go-live date filter row.'
            ],
            new: [
                'Data table now shows DRR Owner, DRR Created, DCO, DCO Status, DCO Owner and DCO Created columns.',
                'Click-to-copy on every Doc / DRR / DCO number; per-column filters on all columns.'
            ],
            fix: [
                'DRR / DCO / Next Review dates now display as MM-DD-YYYY without a timestamp.'
            ]
        }
    },
```

(Match the exact object shape of the existing first entry — adjust key names if the existing entries differ.)

- [ ] **Step 2: Build the JAR**

Run: `JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-11* mvn -q -DskipTests package 2>&1 | tail -25`
Expected: `BUILD SUCCESS`, produces `target/plm-field-tracker-1.0.1.jar`.

- [ ] **Step 3: Run JS unit tests**

Run: `node --test Test/js/*.test.js`
Expected: all suites pass (includes the new `imsreview.test.js`).

- [ ] **Step 4: Local smoke test**

```bash
cp target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties
```

Log in at `http://localhost:8090` as `plmadmin`; exercise Slides 1–3 per the per-task smoke checks.

- [ ] **Step 5: Stage to the prod share (per CLAUDE.md Post-Build Deploy)**

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
```

If `/Volumes/uls-ep-aglipccb/` is not mounted, report it and stop — do not write to the live folder. Verify size parity with `stat -f "%z"` on source and staged copies.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "chore(ims): What's New entry for dashboard refinements

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Server-verification handoff (after deploy)

The SQL change (Task 1) cannot run on this Mac. After Vikas deploys the staged JAR to the server, confirm on a live IMS Dashboard:
1. **DRR Owner / DRR Created** populate for rows with a DRR.
2. **Legacy DRR sub-tiles** split correctly — spot-check a known legacy DRR at Pending vs at Submit/CCB. (`imsDrrStatusBucket()` is already exact-mapped to the live status vocabulary, so this should just confirm.)
3. **DCO / DCO Created** populate for toolkit-created DCOs; **DCO Status / DCO Owner** show `—` (expected — deferred follow-up).

## Out of scope (logged follow-ups — see spec §"Out of scope")

1. Live DCO Status / DCO Owner sourcing (query the known toolkit DCO #s against the CHANGE table — Oracle, testable).
2. "Trigger DRR" action behind the DRR MISSING tile.
3. DCO data for Agile-direct (non-toolkit) DCOs.
