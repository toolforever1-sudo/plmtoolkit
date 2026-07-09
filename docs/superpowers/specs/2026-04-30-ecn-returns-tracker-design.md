# ECN Returns Tracker — Design

**Date:** 2026-04-30
**Author:** Vikas Jindal (PLM IT) + Claude
**Driver:** ECN-135232-PROJ (Noraida Nazri); brainstorm with Vikas
**Audience:** Jimmy Sessumes, Noraida Nazri, Kathy Ashe, Andy Kuver, Aiyappa Cheppudira

## Problem

Per Noraida's CRD: ECNs are repeatedly returned to "Pending" by PCM analysts due to incomplete or unclear submissions. The same requestors and product lines recur, indicating people/process gaps rather than complexity. There is no consolidated visibility into rejection events, no categorization of *why*, and no actionable insight feed for leadership.

## Goal

Deliver a "Returns Tracker" view inside the existing **ECN Report** tab that:

- Captures every rejection event (any later status → Pending) with the rejecting analyst's comment
- AI-categorizes each event into 5 fixed buckets + a free-text theme phrase
- Generates an executive narrative, anomaly callouts, and per-requestor coaching insights
- Sends a weekly Monday and monthly 1st-of-month auto-email to the leadership distribution
- Lets Noraida pick any custom date span and ad-hoc email the current view

## Non-goals

- Changing the existing Cycle Time view (stays YTD, untouched)
- Replacing or modifying the existing ECN Report email
- Building a new top-level tab (toggle inside the existing tab instead)
- Rejection prevention / requestor coaching workflow (data-driven; coaching is leadership's job)

## Source data

| Source | Detail |
|---|---|
| `AGILE.CHANGE_HISTORY` | Workflow audit table; rejection event = `EVENT_TYPE=65 AND NEXT_STATUS='Pending' AND PREV_STATUS IS NOT NULL`. The `COMMENTS` field holds the analyst's free-text rejection narrative. 99% of events have non-empty comments (validated against 1,645 events in last 90d). |
| `AGILE.CHANGE` | Joined for `CHANGE_NUMBER`, requestor, product line, current status, description |
| `AGILE.NODETABLE` | Resolves status IDs to names |

## AI involvement

Per Vikas, "lots of AI". Concretely:

| Role | Trigger | Cost ballpark |
|---|---|---|
| Categorization (5 buckets + theme phrase) | Per new rejection event, batched 20 at a time | ~$0.001 each |
| Executive narrative (1–2 paragraphs) | Per view-window change (cached) | ~$0.01 |
| Anomaly callouts (2–4) | Same call as narrative | included |
| Per-requestor pattern + coaching action | Same call as narrative | included |
| Drill-down explanation per event | On-demand from row click | ~$0.005 |

**Model:** `claude-sonnet-4-6` via the project's existing Portkey gateway (`api.portkey.ai`, `x-portkey-api-key` header). Switches to Vortex (`ai.vortex.sandisk.com`) once Basu confirms the URL — no other code changes.

**Categories (fixed):** Insufficient Information · Incomplete Documentation · Ambiguous Request · Wrong Information · Duplicate Request

## Architecture

### New script: `rejection_tracker.py`

Lives in `./data/ecn-report/`. Invoked by:
- The Spring Boot scheduled jobs (weekly + monthly)
- The "Refresh" button in the Returns Tracker view
- Admin "Re-categorize all" button (forces full reprocess)

CLI:
```
rejection_tracker.py --config ecn_report.properties [--full] [--narrative-window 7d|30d|90d|YYYY-MM]
```

Flow:
1. Connect to Oracle, load existing `rejection-cache.json`
2. Query `CHANGE_HISTORY` for events since `last_run_ts` (or all if `--full`)
3. For each new event: enrich with ECN context (CHANGE join)
4. Batch 20 at a time → Claude categorize → append to cache with `{eventId, ecnNumber, fromStatus, requestor, productLine, ts, comment, category, theme, model, categorizedAt}`
5. Update `last_run_ts` in cache header
6. If `--narrative-window` given: aggregate stats for that window, call Claude for narrative + anomalies + per-requestor patterns, write to `rejection-narrative.json[<window>]`

### New persistent files (under `./data/ecn-report/`)

| File | Schema |
|---|---|
| `rejection-cache.json` | `{lastRunTs: ISO, model: str, events: {<eventId>: {...}}}` — append-only |
| `rejection-narrative.json` | `{<windowKey>: {generatedAt, narrative, anomalies[], topRequestors[], topThemes[]}}` |
| `rejection-recipients.json` | `{recipients: [{email, name}]}` — separate from existing ECN report distribution |

### Spring Boot additions

**New service:** `RejectionTrackerService`
- `getRejectionData(startDate, endDate)` — reads `rejection-cache.json`, filters to range, computes aggregates
- `getNarrative(windowKey)` — reads `rejection-narrative.json`; triggers regeneration if stale or missing
- `triggerRefresh()` — spawns `rejection_tracker.py` subprocess (same pattern as existing ECN report)
- `triggerReCategorize()` — spawns with `--full`
- `sendEmail(windowKey, recipients, subjectSuffix)` — composes HTML body + Excel attachment, sends via existing JavaMailSender bean
- `explainEvent(eventId)` — on-demand AI call for drill-down

**New controller endpoints** (mounted under `/api/ecn-report/returns/`):
- `GET /data?from=YYYY-MM-DD&to=YYYY-MM-DD` — events + computed aggregates
- `GET /narrative?window=...` — AI narrative for window
- `POST /refresh` — kicks off `rejection_tracker.py`
- `POST /email` — manual "email this view" (body: `{from, to, recipients?}`)
- `GET /recipients`, `PUT /recipients` — admin manage distribution
- `GET /explain/{eventId}` — drill-down AI explanation
- `GET /download` — Excel export (same shape as email attachment)

**New scheduled jobs** (in existing `ScheduledReportService`):
- `@Scheduled(cron="0 0 7 * * MON")` — weekly Monday 7 AM SGT (gated by `app.scheduling.disabled`)
- `@Scheduled(cron="0 0 7 1 * *")` — 1st of month 7 AM SGT

### UI additions

**Toggle in existing ECN Report tab:**

```
[ ◉ Cycle Time | ○ Returns Tracker ]      [ Date: Last 90 days ▾ ] [ Refresh ] [ Email this view ]
```

Toggle state persisted in `localStorage` (key: `ecnReportView`).

**Returns Tracker view sections** (top → bottom):

1. **Executive narrative card** — AI paragraph, refreshes when window changes
2. **Anomalies strip** — pill callouts (rendered only if non-empty)
3. **KPI tiles (5)** — Total Rejections · Affected ECNs · Top Category % · Repeat Offenders · Avg Rejections/ECN
4. **Charts row** — Category breakdown bar (5 fixed buckets) · Weekly trend line · Top-10 product lines bar
5. **Top Repeat Offenders** — Table: Requestor · Count · AI pattern · AI suggested action
6. **Top Themes** — AI-clustered themes with example comments
7. **Rejection Events table** — Date · ECN# · From Status · Requestor · Product Line · Category · Theme · Comment (truncated). Sortable, filterable, paginated. Row click → right-side slide-in panel with full comment + AI explanation.

**Date picker:** Last 7 days · Last 30 days · Last 90 days (default) · This Quarter · Custom (from–to). Only affects Returns Tracker.

**Email button:** Sends current view (date range + recipients) as ad-hoc email. Subject suffixed with `(ad-hoc)`.

### Email content

Curated subset of the tab — leadership grade:

- AI executive narrative (top)
- Anomaly callouts (if any)
- KPI tiles
- Category bar chart (rendered as inline image or HTML table)
- Top-5 requestors table
- Top-5 themes
- "View full interactive report →" link to tab
- Excel attachment with raw events + categorization

Email styled per `CLAUDE.md` design tokens (IBM Plex fonts, the SanDisk palette, dark-mode safe, sandisk pill in footer, no greetings/signoffs).

## Implementation choices (decided defaults, tunable)

| Choice | Default | Rationale |
|---|---|---|
| Repeat-offender threshold | ≥3 rejections in window | Pattern, not coincidence |
| Anomaly types | (a) category WoW Δ≥2× (b) new theme appearing (c) new top-5 requestor | High-signal subset |
| Drill-down UX | Right-side slide-in panel | Keeps table visible |
| AI model | `claude-sonnet-4-6` via Portkey | Project standard |
| Categorization batch size | 20 events / Claude call | Prompt-size + retry sweet spot |
| Cache eviction | Append-only; admin re-categorize button | Preserve history |
| Excel attachment | Auto-attached on every email | Matches existing ECN report |
| Recipients | Separate from existing ECN report distro list | Different audience per CRD |

## Potential follow-ups (decide after demo feedback)

| Idea | Source | Trigger to do it |
|---|---|---|
| **"% of all ECNs returned" KPI tile** — add a metric showing `affectedECNs ÷ totalECNsSubmittedInWindow`. Requires a second SQL pulling submission counts. Tile would replace or accompany "Avg returns per rejected ECN". | Vikas review (2026-04-30) | If demo audience finds the current "Avg returns per rejected ECN" answers a different question than they want |
| Bigger optimization of initial backfill SQL (pre-resolve "Pending" NODETABLE.ID, force LOCAL_DATE index) | Self | If production cron times complain |
| Excel attachment with raw events on emails | Original CRD | If recipients ask for it after seeing the in-email summary |
| Auto-coaching emails to individual repeat-offender requestors | Stretch goal | If Noraida says the data justifies it after a few weeks of leadership-only emails |

## Out of scope (this iteration)

- Auto-coaching emails to individual requestors (could be a follow-up after Noraida tests the data)
- ML-based duplicate detection (CRD's "Duplicate Request" bucket relies on AI inference from comment text, not cross-ECN similarity matching)
- Real-time push notifications when a new rejection happens
- Mobile-optimized layout (the existing dashboard is desktop-first; this matches)

## Risk & mitigation

| Risk | Mitigation |
|---|---|
| Portkey/Vortex outage breaks the weekly job | Job catches exceptions, sends email anyway with raw uncategorized data + a warning banner; admin notified |
| Claude misclassifies a comment | Low-stakes — leadership gets gist; admin can manually recategorize one event via API; "re-categorize all" available |
| Cache file grows large | At ~130 events/week × 50 weeks = 6,500/year, ~5 MB JSON. Acceptable for years; archive when crossing 50 MB |
| Categorization prompt drift between runs | Cache stores `model` + `categorizedAt` per event; admin can selectively re-run if prompt is changed |
| Multiple users hitting refresh simultaneously | `triggerRefresh` uses existing report-status lock from `ReportService` |

## Success criteria

- Noraida can open the tab, switch to Returns Tracker, and within 2 seconds see the last 90 days categorized
- The weekly auto-email goes out Monday 7am SGT with the prior week's data, AI narrative, and Excel attachment
- Click any row in the events table → AI explanation appears in <3 seconds
- Adding a new recipient takes <30 seconds via the gear icon
- All 5 fixed categories visible in the bar chart even if some have zero events (consistent week-over-week presentation)
