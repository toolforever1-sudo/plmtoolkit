# ECN Due Date Expiration view — Design

**Date:** 2026-07-13
**Requested via:** ECN-137642-PROJ ("Add ECN 'Due Date Expiration' tab under ECN Dashboard that is a
replica of 'ECN Due Date Expiration Report' sent out as email notification every day. Refresh and cache
the data every day. Add a graph showing daily dues trend, and a tile with count of ECN pending with each
Analyst.")
**Source email logic:** `~/git/ecnreports/src/com/sandisk/agile/report/ECNDueDateExpirationNotification.java`

## Goal

An in-app, cached, always-available replica of the daily *ECN Due Date Expiration* email, added as a new
pill under the **ECN Dashboard** view toggle, plus two analytics the email lacks: a daily-dues **trend**
graph and a **per-analyst** at-risk-count tile row.

## Placement

New pill **"Due Date Expiration"** in the ECN Dashboard toggle, next to *Overdue Tracker* (its natural
sibling). Adds `#ecnViewDueDate` button and `#dueDateView` container; `ecnSwitchView('duedate')` branch in
`returnstracker.js`. Frontend module `duedateexpiration.js`; backend `DueDateExpirationController` +
`DueDateExpirationService`.

## Data source — Oracle (`agileDataSource`), no SDK, no Python

Reproduces the report's population as direct SQL, mirroring the joins `OverdueTrackerService` already uses.

**Pinned DB facts (verified against the live 06-30-26 email population, 2026-07-13):**
- **Target Due Date** = `agile.page_three.DATE31` (attribute node `251739706` "TargetDueDate", i.e. the
  report's `ecn.duedate.baseID`). Confirmed: matched the email's dates exactly where unchanged
  (ECN-136772→06-23, ECN-137328→07-01) and drifted *later* elsewhere (analysts bumping the date after the
  nudge — the intended behavior). `DATE32` is ~2 weeks earlier and is NOT the target due date.
- **Population** — mirrors the saved Agile search `/Global Searches/ECNReports/ECNDueDateExpireReport`
  (criteria confirmed from the Agile UI 2026-07-13), all AND'd:
  - `subclass = 251739700` (Object Type = ECN), `delete_flag` clear.
  - **Status In (RELEASED, REVIEW, SUBMIT)** — implemented as `sn.name NOT IN` the excluded set
    (`Pending,Cancel,Cancelled,Canceled,Completed,Void,Hold`). The operative in-flight status is
    **"Release"** (the CCB/approval release routing) — it carries a `release_date` yet is NOT done, so we
    **do NOT filter `release_date IS NULL`** (doing so was the original bug — it hid all the real ECNs and
    surfaced only the paused Hold ones). **Hold is paused → excluded.**
  - **Product Line(s) Not Equal** `9001 - EVB ENGG` and `N/A` (`app.duedate.exclude-product-lines`).
  - **Number Does Not Contain `CCB`** (`app.duedate.exclude-number-contains`).
  - **Request Classification Not In {Project Request|…}** → `rc_parent.entryvalue <> 'Project Request'`
    (`app.duedate.project-classification-parent`); the "Include IT (-PROJ)" toggle lifts this.
- **Due window** = `DATE31 < TRUNC(SYSDATE) + 2` (calendar, matching the email's `Target Due Date < tomorrow`
  param; kept as calendar for exact email parity — the severity *labels* use working days, as the report does).
- **Analyst** = `agileuser` via `c.owner`, strip `(loginid)` suffix (`stripIdSuffix`).
- **Product Line** = `c.product_lines` CSV → `resolveProductLines` (listentry under parent 291).
- **Priority** = `listentry.entryvalue` via `c.category` (Urgent vs Standard).
- **Status** = `nodetable.name` via `c.status`.
- **Proposal** = description-of-change, truncated ~200 chars (as the email does).
- **Notes/Comments** = latest `agile.change_history.comments` row with `event_type IN (14,15,17,65)` for the
  ECN — the same comment source `OverdueTrackerService` documents as the email's Comments/Notes column.

**Working days** ported from the report's `getWorkingDaysBetweenTwoDates` (Mon–Fri, sign = overdue vs
remaining). Severity buckets match the email: **Critical** < −10 wd overdue, **Overdue** −10…−1 wd,
**Due Soon** ≥ 0. Each bucket split Urgent / Standard. Rows sorted most-overdue-first.

## UI (`dueDateView`)

1. **Header + target-days legend** (Standard/Urgent working-day targets, from config).
2. **Three summary badges** — Critical / Overdue / Due Soon, each with Urgent vs Standard counts.
3. **Per-analyst tile row (NEW ask)** — one mini-tile per Change Analyst counting *this* expiration set;
   click a tile to filter the table to that analyst.
4. **Two charts (NEW ask, Chart.js — `chart.min.js` already bundled):**
   - *Backlog over time* — line of daily Critical/Overdue/Due-Soon counts from stored snapshots.
   - *Forward due histogram* — bars per upcoming calendar day, computed live from DATE31.
5. **Table** — ECN# (link to PLM), Product Line, Analyst, Proposal, Target Due Date, Priority dot,
   Status/days badge, Comments/Notes — matching the email, restyled to the in-app dashboard look.

## Refresh, cache & snapshots

- **Cache**: in-memory result + `./data/ecn-report/due-date-expiration-cache.json`, served instantly.
  A **Refresh** button re-runs on demand.
- **Daily job**: `DueDateExpirationScheduler` `@Scheduled(cron = ${app.duedate.refresh-cron:0 0 6 * * *})`,
  gated by `app.scheduling.disabled` (dormant locally) and maintenance mode. Refreshes cache and appends
  **one snapshot per day** — `{date, critical, overdue, dueSoon, total}` — to
  `./data/ecn-report/due-date-expiration-snapshots.json` (idempotent per date). Feeds the backlog line.

## Endpoints (`/api/ecn-report/due-date/...`)

- `GET  /data`     — badges + table rows + per-analyst counts + forward histogram (from cache).
- `POST /refresh`  — re-run query, update cache + today's snapshot; returns fresh `/data`.
- `GET  /trend`    — snapshot series for the backlog-over-time chart.
- `GET  /download` — Excel export (matches dashboard convention).

All endpoints require an authenticated session (same guard as sibling controllers).

## Out of scope

- **Email send** — the server-side `ecnreports` job already sends the daily email. This is the in-app,
  cached view of the same population plus trend/analyst analytics. A "Send to me" preview can be added
  later if wanted.

## Verify-during-implementation

- Re-confirm the row count of the SQL population against the live email logic (spot-check a handful of
  Review/Hold overdue ECNs).
- Confirm `change_history` Notes join returns sensible text for ECNs that have CCB comments.
