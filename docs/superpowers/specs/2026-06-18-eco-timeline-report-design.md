# ECO Timeline Report — Design Spec

**Date:** 2026-06-18
**Scope:** New top-level "ECO Timeline" tab. Given one item number and a date range, report every released ECO that changed any component anywhere in the item's *indented* BOM, and what each ECO changed. Export-friendly to Excel.

## Origin / Requirement

Requested by the Planning group. A planner gives an item number (typically a SKU) plus a **start date** and **end date**, and needs to see, for that item **and its complete indented BOM** (sub-assemblies, sub-sub-assemblies, all the way down), which released ECOs landed in that window and what each one did to the BOM — a component added, removed, replaced (primary number changed), quantity changed, find-number changed, or notes changed.

The structure is treated as a **moving target**: components added or removed *during* the window must still appear (the "evolved union" of the structure across `[start, end]`).

## Key Decisions (locked with requester)

1. **Recursion: full.** Every node in the indented structure is scanned for changes — SKU → Subassembly A → Subassembly B → … . An ECO that changed a component three levels down is reported, attributed to its immediate parent assembly.
2. **Structure scope: evolved union.** Any assembly/component that existed in the structure at *any* point in `[start, end]` is included — not just today's BOM, not just the start-date snapshot.
3. **Change types reported:** Added, Removed, Primary number changed (component replaced X→Y), Quantity changed, Find-number/Seq changed, Notes changed. **Reference designator changes are excluded** (per requester).
4. **Strictly component (BOM-line) changes.** ECOs that released against an assembly but changed only that assembly's *own* item attributes (no BOM-line add/remove/modify) produce **no row**.
5. **Released ECOs only.** `CHANGE.RELEASE_DATE` not null and within the window. Pending ECOs are out of scope.
6. **Placement:** new top-level tab (own entry in the main nav), not a sub-tab.
7. **Data source:** live `AGILE.BOM` / `AGILE.CHANGE` / `AGILE.ITEM` (the redline columns are not in `bom_extract`). Source label: "Agile PLM (live)".

## Why this is tractable (schema facts)

Per-ECO BOM changes are recorded natively on `AGILE.BOM`:

- `CHANGE_IN` — FK to the `CHANGE` that **added** this BOM line (`0` = pre-existing).
- `CHANGE_OUT` — FK to the `CHANGE` that **removed** this BOM line (`0` = still active).
- `PRIOR_BOM` — FK to the predecessor BOM row; links the remove+add pair that represents a **modify**.
- `RELEASE_DATE` lives on `AGILE.CHANGE` (one per ECO) — the start/end filter axis.

`AGILE.BOM` uses `ITEM` (parent assembly item id) and `COMPONENT` (child item id), plus `ITEM_NUMBER` (component part number string), `QUANTITY`, `FIND_NUMBER`, `SEQ`, `NOTES`.

`RevCompareService` already implements the released/pending redline-scoping logic for a single pair of revisions; this feature generalizes that pattern to a recursive walk over a date window. The PT-37 handoff (`docs/handoffs/2026-05-11-pt37-agile-redline-schema.md`) documents the redline semantics.

## Algorithm (EcoTimelineService)

**Step 1 — Build the evolved-union tree.**
One recursive `CONNECT BY NOCYCLE PRIOR b.COMPONENT = b.ITEM` over `AGILE.BOM`, starting from the input item's `ITEM.ID`. A BOM line is a valid edge if it was **active at any point in the window**:

```
(CHANGE_IN = 0  OR  ci.RELEASE_DATE <= :to)        -- added on or before window end
AND (CHANGE_OUT = 0  OR  co.RELEASE_DATE IS NULL  OR  co.RELEASE_DATE >= :from)  -- not removed before window start
```

This yields every assembly + component present in the structure during the window, each with its indent level and path. Deduplicate the set of distinct assembly item ids (the nodes whose BOMs must be scanned).

**Step 2 — Collect ECO events per assembly.**
For each assembly in the tree, select `AGILE.BOM` rows where:

- `CHANGE_IN` is a released change with `RELEASE_DATE` in `[from, to]` → an **Added** event, or
- `CHANGE_OUT` is a released change with `RELEASE_DATE` in `[from, to]` → a **Removed** event.

**Step 3 — Classify modifies.**
Within the same parent assembly and same `CHANGE`, an Added row whose `PRIOR_BOM` points at a Removed row (same change) is a **Modify**. Compare prior vs new line to pick the change type:

- component `ITEM_NUMBER` differs → **Primary number changed** (X → Y)
- `QUANTITY` differs → **Quantity changed**
- `FIND_NUMBER`/`SEQ` differs → **Find # changed**
- `NOTES` differs → **Notes changed**

(If multiple fields changed on one modify, emit the most significant or list them; default: one row, detail string lists each field's old→new. Decided in implementation; keep it one row per modify.)

Pure Added (no `PRIOR_BOM` predecessor in range) → **Added**. Pure Removed (no same-change replacement) → **Removed**.

**Step 4 — Timezone & ordering.**
Treat `[from, to]` as inclusive on both endpoints in Pacific time (consistent with the rest of the app — see `ChangeHistoryService.formatTs`). Render release dates in Pacific time. Default sort: ECO release date ascending, then indent path.

## Guardrails

- **Max depth:** configurable (default 25 levels), with a `Max Depth` control on the form (mirrors Explorer). Flag "limit reached" if hit.
- **Item/row cap:** total-component cap on the union walk (mirrors Explorer's 50K guard); set a `truncated` flag and a visible warning if exceeded.
- **Query timeout:** ~90–120s (match existing live-Agile services).
- **Empty input handling:** require item + both dates; reject if `from > to`.

## Output

One row per **component × ECO event**.

| Column | Source |
|---|---|
| Level | indent depth in the union tree |
| Parent Assembly | the assembly whose BOM changed (item number) |
| Component # | affected component part number |
| Component Description | `item_extract`/`ITEM` description |
| ECO # | `CHANGE.CHANGE_NUMBER` |
| ECO Release Date | `CHANGE.RELEASE_DATE` (Pacific) |
| Change Type | Added / Removed / Primary number changed / Quantity changed / Find # changed / Notes changed |
| Detail | human-readable old → new (e.g. "Qty 2 → 4", "Replaced ABC-001 → ABC-002", "Added", "Removed") |

KPIs (insight strip): **ECOs found**, **Components affected**, **Date span**, **Query time**.
Client-side per-column filters + sort, following the Explorer/Compare table pattern.

## Backend

### EcoTimelineController.java
- `GET /api/eco-timeline/query?item=&from=&to=&maxDepth=` — returns `{ rows[], ecoCount, componentCount, queryTimeMs, truncated, depthLimitReached, filterSummary }`.
- `GET|POST /api/eco-timeline/export?item=&from=&to=&maxDepth=` — Excel download (POST tolerated for parity with other tabs; here inputs are small so GET is primary).
- `POST /api/eco-timeline/email` — email the Excel to the requesting user (matches toolkit convention).

### EcoTimelineService.java
- `query(item, from, to, maxDepth)` → builds the union tree (Step 1), collects + classifies events (Steps 2–3), returns the row list + counts.
- Reuses the redline date-scoping patterns from `RevCompareService`; IN-list chunking (1000) when scanning many assemblies, as `ChangeHistoryService` does.

### Excel export
- Reuse the existing export helper pattern (`BomExcelExportService`-style POI/SXSSF). Columns match the on-screen table. Follow the project's email-design and Excel conventions.

## Frontend

### eco-timeline.js (new)
- Form: Item Number, Start Date, End Date, Max Depth, **Run** / **Clear**.
- Fetch `/api/eco-timeline/query`, render KPI strip + results table with column filters and sort.
- **Export to Excel** and **Email this view** buttons (use `ui-modal.js` helpers, not native dialogs).

### index.html (modify)
- New top-level nav tab "ECO Timeline" + its panel.
- `<script>` tag for `eco-timeline.js`.

### app.js (modify)
- Register the new tab in `switchTab` / nav wiring.

### style.css (modify)
- Any table/badge classes specific to change-type pills (reuse existing where possible). Night-mode support.

## Files to Create
1. `src/main/java/com/sandisk/plm/tracker/service/EcoTimelineService.java`
2. `src/main/java/com/sandisk/plm/tracker/controller/EcoTimelineController.java`
3. `src/main/resources/static/eco-timeline.js`

## Files to Modify
1. `src/main/resources/static/index.html` — nav tab + panel + script tag
2. `src/main/resources/static/app.js` — tab switching wiring
3. `src/main/resources/static/style.css` — change-type pill styling, night mode

## Data Flow
```
Enter item + start + end (+ max depth) → query API
→ Step 1: recursive CONNECT BY over AGILE.BOM → evolved-union tree (all assemblies in window)
→ Step 2: per assembly, BOM rows whose CHANGE_IN/CHANGE_OUT released in [from,to]
→ Step 3: classify Added / Removed / Modify(primary#, qty, find#, notes) via PRIOR_BOM pairing
→ rows + KPIs returned → table render → export / email
```

## Limits
- Max depth: default 25 (configurable, form control).
- Total-component cap on union walk (truncate + flag).
- Released ECOs only; ref-designator changes and attribute-only ECOs excluded.
- Query timeout ~90–120s.

## Out of Scope (this version)
- Pending/unreleased ECOs.
- Reference-designator change detection.
- Attribute-only ECO rows (no BOM-line change).
- Scheduled/automated delivery (manual export/email only for v1).
