# IMS Dashboard Refinements — Design

**Date:** 2026-06-29
**Source:** `~/Documents/ims_improvement.pptx` (3-slide feedback deck)
**Approach:** A — frontend-first re-grouping + one cheap, locally-testable SQL column; no SDK calls, no DCO discovery.

## Goal

Refine the IMS Dashboard tab per the feedback deck: remove redundant chrome (Slide 1),
restructure the summary tiles into three DRR-lifecycle groups (Slide 2), and rework the
data table columns (Slide 3). All work is local-testable except where explicitly noted as
a deferred follow-up.

## Affected files

| File | Role |
|---|---|
| `src/main/resources/static/imsreview.js` | Tile strip render, segment filtering, table render, filters |
| `src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java` | KPI/segment computation, `pullDocsDueWithin()` SQL, `DocRow` |
| `src/main/resources/static/index.html` | Panel markup if any static rows are removed |
| `src/main/resources/static/whats-new.js` | Changelog entry (pre-build requirement) |

---

## Slide 1 — Removals

1. **Pipeline funnel strip** (`NOT SENT / IN FLIGHT / CLOSED`) — remove from `renderKpiStrip()`
   (`imsreview.js:438-455`). Redundant with the grouped tiles below.
2. **"Only DRRs created on/after [date]" filter row** — remove `renderDrrCreatedBar()`
   (`imsreview.js:686-708`) and its predicate in `filterAdminRows()` (`imsreview.js:1391-1394`),
   plus the `drrCreatedFilterOn` / `drrCreatedAfter` state. The `2026-07-05` go-live anchor
   constant stays (still used for New-vs-Legacy classification).
3. **Keep** the red "*N documents need a new owner → Review & reassign owners*" banner
   (`imsreview.js:457-484`). It is an actionable CTA, not a duplicate. (Confirmed with user.)

---

## Slide 2 — Grouped summary tiles

Replace the flat 6-segment strip (`ready_to_send / needs_owner / in_flight / need_help /
closed / legacy`) with **three groups**, each containing **sub-state tiles**. Each tile shows
a count and acts as a table filter (same click-to-filter UX the segments have today).

### Group classification (one per doc row)

- **Need DRR** — `!hasDrr`
- **New DRR** — `hasDrr` AND `drrCreateDate >= 2026-07-05`
- **Legacy DRR** — `hasDrr` AND `drrCreateDate < 2026-07-05`

`drrCreateDate` is the DRR change's **real Agile create date** (new SQL column), replacing the
current earliest-queue-event-timestamp heuristic in `ImsReviewService.java:494`.

### New DRR sub-tiles (driven by our queue status; precedence top-down)

| Tile | Rule |
|---|---|
| NEED OWNER | `allOwnersLeft` (all owners inactive) — **overrides** the rows below |
| PENDING RESPONSE | `SENT_TO_DO`, or `NOT_SENT` with a valid owner (auto-trigger ⇒ effectively sent). Any one owner response advances it out |
| IN PROCESS | `SENT_TO_DM` or `DO_NEEDS_CHANGE` (DRR/DCO at Submit/CCB) |
| NEED HELP | `DO_NEED_HELP` |
| CLOSED | `DM_APPROVED` or `CANCELLED` |

### Legacy DRR sub-tiles (driven by the DRR change's live Agile workflow status)

Legacy DRRs are mostly untouched in our queue (status `NOT_SENT`), so their sub-state comes
from the new DRR-change-status SQL column, not queue status.

| Tile | Rule |
|---|---|
| NEED OWNER | `allOwnersLeft` |
| PENDING RESPONSE | DRR change still at **Pending** |
| IN PROCESS | DRR change at **Submit / CCB** |

### Need DRR sub-tile

- **DRR MISSING** — IMS doc is due but has no DRR linked. Tile + filter only; no new "trigger"
  action wired in this pass (deferred).

### Multi-owner rule

"Any one response advances it" (user decision). A doc leaves PENDING RESPONSE as soon as one
owner responds. NEED OWNER applies only when **all** owners are inactive.

### Implementation notes

- The Agile status-node → "Pending" vs "Submit/CCB" mapping is finalized during implementation
  against real `agile.change.status` node descriptions (pull a sample first).
- New-vs-Legacy cutoff moves onto the real DRR create date (see above); `2026-07-05` anchor stays.

---

## Slide 3 — Data table columns

### Column set (left → right)

| # | Column | Source / behavior |
|---|---|---|
| 1 | Number / Description | as-is; doc # hyperlinked + selectable |
| 2 | DRR | number only — **drop the "legacy" badge**; hyperlinked |
| 3 | DRR Owner *(new)* | DRR change originator — cheap add to the existing `pc` join |
| 4 | DRR Create Date *(new)* | DRR change's real create date, `MM-DD-YYYY`, no timestamp |
| 5 | DCO *(new)* | toolkit-created DCO # from queue events; blank if Agile-direct; hyperlinked when present |
| 6 | DCO Status *(new)* | muted `—` placeholder (no source under minimal sourcing) |
| 7 | DCO Owner *(new)* | muted `—` placeholder |
| 8 | DCO Create Date *(new)* | from the queue event when DCO was created (toolkit only), `MM-DD-YYYY`; else blank |
| 9 | Owners | as-is (badges + LDAP status) |
| 10 | Next Review | reformat to `MM-DD-YYYY` |
| 11 | Status | as-is (status pill) |
| 12 | File | as-is (Get File) |
| 13 | Action | Send / Re-Send / Re-Set |

### Other standards

- **Numbers hyperlinked + copyable:** Doc #, DRR #, DCO # link to their Agile objects (reuse the
  existing link helper) and stay selectable; add a small click-to-copy affordance on each number.
- **Filter on every column:** add inline filter inputs for the new columns (DRR Owner, DCO,
  DCO Status, DCO Owner, DCO Create Date) and Status, alongside existing ones.
- **Width:** 13 columns is wide; table keeps horizontal scroll, no columns hidden.

---

## Backend changes (minimal)

`pullDocsDueWithin()` `pc` CTE (`ImsReviewService.java:2668-2674`) already joins `agile.change`
for the DRR number. Add to the **same** query (no new lookups, locally testable):

- `change.status` (workflow status node, via `nodetable`) → powers Legacy sub-states + future DCO parity
- `change.<originator/owner>` → DRR Owner column
- `change.<create date>` → DRR Create Date column + New/Legacy cutoff

`DocRow` (`ImsReviewService.java:384-400`) gains: `drrStatus`, `drrOwner`, `drrCreateDate`.
DCO #, DCO create date come from existing queue events (`Event.agileDco`,
`ImsReviewService.java:515-516`); no new fetch.

---

## Out of scope (logged follow-ups)

1. **Live DCO Status / DCO Owner** — query the known toolkit DCO #s against the CHANGE table
   (Oracle, testable) to populate columns 6–7. Cheap but deferred per minimal-sourcing decision.
2. **"Trigger DRR" action** behind the DRR MISSING tile.
3. **DCO data for Agile-direct DCOs** (created outside the toolkit) — needs DRR→DCO linkage
   discovery; not attempted.

## Testing

- Local end-to-end via `plmadmin` login at `http://localhost:8090` (heap ≥4g).
- Local-testable: Slides 1 & 2 in full; table columns 1–4, 9–13.
- Pre-build: update `whats-new.js` per project rule.
