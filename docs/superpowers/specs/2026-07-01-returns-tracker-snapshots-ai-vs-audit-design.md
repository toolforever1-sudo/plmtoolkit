# Returns Tracker: Past-Trends Snapshots + AI-vs-Audit Classification

**Date:** 2026-07-01
**Author:** Vikas Jindal (with Claude)
**Status:** Design approved, pending spec review
**Requested by:** Jimmy Sessumes, Noraida Nazri, Vikas Singh (FW: Return Tracker, 2026-07-01)

## Problem

Two related requests against the ECN **Returns Tracker** (Return-to-Pending) dashboard:

1. **Past trends / prior-period snapshots (Vikas Singh).** The tracker view holds only ~90 days;
   older AI-assessed ECNs age out of the source. The team wants prior **month/quarter** reports
   saved as immutable snapshots, reachable from a **dropdown**, on demand. There is no snapshot
   mechanism today — `rejection-narrative.json` is a single rolling file and the event cache is a
   single append-only file with no per-period archive.

2. **AI-inferred vs audit-enforced reason codes (Jimmy / Noraida).** An audit Process Extension
   (`prototype-ecr-pcn` → `CancelECN` / `CancelECR`) went live in **production on 2026-06-24**. It
   forces a non-requestor Return-to-Pending comment to **start with a classification prefix** — a
   short code (`ID:`, `II:`, `WI:`, `DR:`, `RR:`) or the full name, case-insensitive, colon-delimited.
   The dashboard already parses that prefix (`classifyByCommentPrefix()`) **and** has an AI-inferred
   category from `rejection_tracker.py`, but `enrichEvent()` lets the audit prefix silently win, so
   the two sources are never compared. The team wants to (a) split the data at the go-live date,
   (b) toggle/compare AI-inferred vs audit-enforced codes, and (c) fix the Excel export, which still
   shows the old classification and hardcodes the retired "Ambiguous Request" category.

## Key facts established during exploration

- Audit code is embedded in the **free-text return comment** — there is no separate structured
  Agile field. The dashboard parses the prefix; no new Agile attribute is needed.
- Canonical audit codes (from `CancelECN.auditClassificationPrefix`): `Incomplete Documentation:`/`ID:`,
  `Insufficient Information:`/`II:`, `Wrong Information:`/`WI:`, `Duplicate Request:`/`DR:`,
  `Return Requested:`/`RR:`. Audit enforcement applies only to **non-requestor Return-to-Pending**.
  "Returned by Owner" is auto-interpreted (creator returns while ECN is @Submit); "Unknown" is the
  empty/generic fallback. "Ambiguous Request" is **retired**.
- The event cache (`data/ecn-report/rejection-cache.json`) is **append-only and retains raw events
  back to at least Jan 2026** (verified: 2320 events spanning 2026-01-01 → 2026-05-04 in the local
  snapshot). Each event already stores both `category` (AI) and the raw `comment`. So both
  classifications can be computed per event and any prior month can be recomputed — but a cache
  rebuild/reseed would lose whatever Agile itself has aged out, which is why **frozen** snapshots matter.
- **Audit go-live: 2026-06-24** (production). Stored as config, not a literal.

## Affected files (current implementation)

| Component | File |
|---|---|
| Frontend | `src/main/resources/static/returnstracker.js`, `src/main/resources/static/index.html` (Returns Tracker view ~2938–3090) |
| Controller | `src/main/java/com/sandisk/plm/tracker/controller/RejectionTrackerController.java` |
| Service | `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerService.java` |
| Email/Excel | `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerEmailService.java` |
| Scheduler | `src/main/java/com/sandisk/plm/tracker/service/RejectionTrackerScheduler.java` |
| Python generator | `data/ecn-report/rejection_tracker.py` |
| Runtime data | `data/ecn-report/rejection-cache.json`, `rejection-narrative.json`, `rejection-recipients.json` |

## Design

### 1. Classification data model (core change)

Split the single collapsed `category` into explicit, **persisted** fields on each enriched event:

- `aiCategory` — from the Python-assigned `category` (AI inference over free text).
- `auditCategory` — from `classifyByCommentPrefix()` on the raw comment; `null` when the comment
  carries no valid prefix.
- `categorySource` — one of `audit` | `ai` | `owner` | `unknown`, describing which classification is
  authoritative for display when the source toggle is set to the default (audit-first) mode.

`enrichEvent()` is refactored to populate all three instead of overwriting `category`. Values are
**written back to `rejection-cache.json`** so email, Excel, and API all read the same numbers
(this fixes the "Excel shows stale classification" bug — the Excel currently reads a cached
`category` that is not re-persisted after prefix override).

**Agreement metric** (computed in `RejectionTrackerService.getAggregates`):
- Denominator = events with a non-null `auditCategory` (i.e., post-go-live, coded returns).
- `agreementPct` = share of that set where `aiCategory == auditCategory`.
- `mismatches[]` = `{ ecnNumber, aiCategory, auditCategory, comment, ts }` for the disagreeing subset.
- When the denominator is 0 (e.g., a legacy pre-audit period), agreement is reported as
  `null`/"n/a" rather than 0%.

**Taxonomy reference** updated to the agreed table from Noraida's image: five coded categories
+ "Returned by Owner" (auto) + "Unknown" (fallback). "Ambiguous Request" removed everywhere it is
still hardcoded (service taxonomy list and the Excel Categories sheet).

### 2. Snapshot archive (frozen)

- New directory `data/ecn-report/rejection-snapshots/`. Files named by granularity:
  `week-YYYY-MM-DD.json` (week-ending date, matching the weekly email's window `to`),
  `month-YYYY-MM.json`, and `quarter-YYYY-QN.json`.
- Each snapshot file contains: period bounds (`from`/`to`), KPIs, and aggregate blocks
  (category / product-line / product-team / requestor / daily-trend) computed for **both**
  classification sources, the agreement block, the narrative (best-effort), and `generatedAt`.
  Snapshots are **immutable** once written.
- **Writer**: a new method invoked at the end of the scheduler's jobs — the **weekly** job freezes
  the just-completed week; the **monthly** job freezes the just-completed month, and on quarter
  boundaries (Jan/Apr/Jul/Oct runs) also freezes the just-completed quarter. Idempotent: skips if the
  file already exists (a re-run does not overwrite history).
- **One-time backfill**: a routine (guarded so it runs once) that iterates the complete past weeks,
  months, and quarters present in the append-only cache and writes their snapshots, so the dropdown
  is populated on first deploy. Narrative is generated per period where missing via the existing
  Python narrative path; if generation fails, the snapshot is still written with aggregates only
  (narrative omitted).

### 3. API changes (`/api/ecn-report/returns`)

- `GET /periods` → `[{ id, label, type: "week"|"month"|"quarter", from, to, generatedAt }]`, newest
  first, built by listing the snapshots directory.
- `GET /data` gains two optional query params:
  - `period=<id>` — load the named frozen snapshot instead of recomputing the live window.
  - `classification=ai|audit` — default `audit`. The response **always** carries both aggregate
    sets and the agreement block regardless of this param, so the frontend source toggle re-renders
    with no extra server round-trip. (`classification` is retained for email/export defaults and
    deep-link consistency.)
- `POST /snapshot` (admin-only, mirrors existing admin-gated endpoints) → freeze the current or
  last-completed period on demand; returns the created snapshot id.

### 4. Frontend UX (`returnstracker.js` / `index.html`)

- **Period dropdown** beside the existing date-range control, grouped by granularity via `optgroup`:
  `Live` · **Quarters** (`Q2 2026`…) · **Months** (`June 2026`, `May 2026`…) · **Weeks**
  (`Week ending Jun 28`…). Populated from `GET /periods`. Weekly entries in the dropdown are capped
  to the most recent 26 (all snapshot files are still stored and reachable by id); months and
  quarters are unbounded. Selecting a frozen period fetches `GET /data?period=<id>`, disables the
  custom date inputs, and shows a "frozen snapshot • generated <ts>" caption. Selecting `Live`
  restores the date-range control.
- **Classification toggle**: a segmented control `Audit-enforced | AI-inferred` (default
  Audit-enforced). Flipping it re-renders all panels from the already-loaded payload — no server call.
  All category-driven panels (KPI top-category, category bar panel, requestor patterns, trend
  coloring) honor the active source.
- **Agreement tile + mismatch panel**: a new KPI tile "AI↔Audit agreement" and a compact
  "AI↔Audit mismatches" panel listing disagreeing events (ECN, AI said X, audit said Y, comment
  excerpt), shown only when the period has coded (post-go-live) returns.
- **Go-live default & legacy**: the default range becomes **"Since audit go-live (Jun 24, 2026)"**
  (a new preset that clamps the lower bound to the configured go-live date). A **"Legacy (pre-audit)"**
  preset exposes older data for reference. Legacy periods naturally render mostly "no audit code" —
  which is itself the signal.

### 5. Excel / email fix

- Report sheet: replace the single stale `category` column with the persisted `aiCategory` +
  `auditCategory`, and add **Audit Code** (raw prefix, e.g. `WI`) and **Classification Source**
  columns. Manual-override column behavior preserved.
- Categories reference sheet regenerated from the agreed taxonomy (drop "Ambiguous Request").
- Email KPI block gains the AI↔Audit agreement stat for post-go-live windows (omitted/"n/a" for
  legacy windows).

### 6. Configuration

- `app.returns.audit-golive-date=2026-06-24` (single source of truth; used by the go-live preset,
  the agreement denominator, and the before/after split). Adjustable without code change.

## Testing

- **Unit** — prefix parsing across all five codes + full names, case-insensitive, colon-required
  (reject `WI ` without colon, accept `wi:`); `categorySource` resolution for owner/ai/audit/unknown;
  agreement computation including the zero-denominator ("n/a") case; snapshot serialize→deserialize
  round-trip; backfill week/month/quarter bucketing (boundary weeks spanning month/quarter ends,
  partial current period excluded).
- **Integration/behavioral** — a frozen snapshot renders identically to its live window computed at
  freeze time; the classification toggle swaps sources without a refetch; `GET /periods` reflects
  files in the snapshots directory; go-live preset clamps the lower bound correctly.
- **Data fix regression** — Excel export reflects the re-persisted classification (no stale
  category), and the Categories sheet no longer emits "Ambiguous Request".

## Scope guardrails (YAGNI)

- Week + month + quarter snapshots (weekly email itself is otherwise unchanged).
- No re-classification of history beyond what is already in the append-only cache.
- Audit codes stay parsed from the comment — no new Agile field, no PX changes in this work.
- No change to the Cycle Time / Volume / Team / Overdue / Rejection tabs.

## Out of scope / future

- Re-running AI classification over pre-go-live history.
- Surfacing agreement trends across snapshots (period-over-period agreement chart).
