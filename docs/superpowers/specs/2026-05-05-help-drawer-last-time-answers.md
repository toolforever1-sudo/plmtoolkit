# Help Drawer — "Who/when last generated …" answers

**Date:** 2026-05-05
**Files in scope:** `src/main/java/com/sandisk/plm/tracker/controller/AiHelpController.java`
**Trigger:** Vikas asked the Help drawer "who generated the 'ECN Report' cycle time last time and when?" and got an aggregate Returns-Tracker ranking table with no "last" answer.

## Two bugs

1. **Filter mis-routes ECN Report → Returns Tracker.** `parseActionFilter` line 3089 maps "ecn report" to action substring `RETURNS`. ECN Report runs are logged as `ECN_REPORT_RUN` / `_DONE` / `_FAIL` / `_DOWNLOAD` in `EcnReportController`. They never match `RETURNS`, so the user sees Returns Tracker activity instead. The 18 events in the screenshot are all `RETURNS_REFRESH*`.
2. **No "last time" answer shape.** Question patterns like "last time", "most recent", "latest", "when was the last", "when did … last" all fall through to `formatWhoReport`, which only renders an aggregate ranking table.

## Fix (this spec)

### A. Disambiguate ECN Report vs Returns Tracker

Rewrite the relevant clauses in `parseActionFilter`. Most-specific first:

| Trigger phrases in question | Filter label | Action substring(s) |
| --- | --- | --- |
| `returns tracker`, `rejection tracker` | Returns Tracker | `RETURNS_` |
| `ecn report`, `cycle time`, `ecn cycle` | ECN Report | `ECN_REPORT_` |
| `returns ` (trailing space) | Returns Tracker | `RETURNS_` *(legacy fallback, kept after the specific terms)* |

### B. Generalize to other end-user reports

`ReportController` multiplexes several reports onto a single action code `REPORT_RUN`, distinguished by the detail string ("Triggered What's New digest", "Triggered Activity Stats to …", "Triggered Dry Run", "Triggered Item Cache Seed/Delta", "Downloaded server log"). The current `ActionFilter` only matches on action, so those can't be addressed individually.

Extend `ActionFilter` with an optional `detailSubstrings[]`. When set, the filter requires both the action prefix AND the detail to contain one of the listed substrings (case-insensitive). Action-only filters keep working unchanged (the field is null/empty).

Add filters for:

| Trigger | Label | Action | Detail substring(s) |
| --- | --- | --- | --- |
| `what's new`, `whats new` | What's New digest | `REPORT_RUN` | `what's new`, `whats new` |
| `activity stats` | Activity Stats email | `REPORT_RUN` | `activity stats` |
| `dry run` | Dry Run | `REPORT_RUN` | `dry run` |
| `item cache` | Item Cache (seed/delta) | `REPORT_RUN` | `item cache` |
| `server log` | Server Log download | `REPORT_RUN` | `server log` |

Order: these go before the existing tab-based filters so a question like "when was the last activity stats email sent" picks up the detail-aware filter rather than the legacy "stats" / "report" generic match.

The `matches(String action)` signature is replaced with `matches(ActivityLogger.ActivityEntry e)`. Two call sites (lines 2975, 2986) updated.

### C. "Last time" answer shape

New helper `looksLikeLastTimeQuestion(q)` returns true for any of:
`last time`, `most recent`, `most-recent`, `most recently`, `latest`, `when was the last`, `when was last`, or `when` + (`last` | `recent`).

In `formatWhoReport`, when this helper returns true AND the filtered `entries` list is non-empty, prepend a single-sentence headline before the existing ranking table:

> **Vikas Jindal** last generated *ECN Report* on **May 5, 6:03 PM**.
> *Action: ECN_REPORT_RUN — "Started ECN report generation"*

Pulled from the filtered entry with `max(timestamp)`. Display name falls back to username when displayName is empty. Date format `MMM d, h:mm a` (matches the existing table). Action+detail line is muted/small (12px, `#666`).

Per Vikas:
- **(a)** Keep the full ranking table below the headline. The headline is additive.
- **(b)** Questions without "last/most recent" qualifiers ("who generated ECN Report") keep the current ranking-only behavior.

### D. What's NOT in this change

- No change to the LLM-context path (`buildActivityContextForLlm`) — this is a deterministic-router fix.
- No new endpoints, no UI changes outside the rendered HTML.
- No refactor of the 3469-line `AiHelpController.java`. (It's overgrown; flag for follow-up.)
- No detail-substring filtering for non-`REPORT_RUN` actions (the existing tab filters work fine on action prefix alone).

## Test plan

Manual against the running local toolkit (`http://localhost:8090`, `plmadmin`/`newworld`):

1. **Original failing question.** "who generated the 'ECN Report' cycle time last time and when?" → Headline "*X* last generated *ECN Report* on *date*", followed by the ECN_REPORT_* ranking table. Verify Returns Tracker activity does NOT appear.
2. **Returns Tracker, last time.** "when was the last Returns Tracker refresh?" → Headline + RETURNS_* ranking.
3. **What's New digest.** "who triggered the last What's New digest?" → Headline + REPORT_RUN events filtered to "what's new" detail.
4. **Without time qualifier.** "who has been using the ECN Report?" → No headline; existing ranking table only.
5. **Heaviest user.** "who is the heaviest user today?" → Existing heaviest behavior, no headline.
6. **Empty result.** "when was the last item cache delta?" with no matching events → existing "No <em>X</em> recorded in …" message, no headline.

Edge cases handled:
- Display name empty → falls back to username.
- Detail string empty/null → omit the *Action:* sub-line; keep the headline.
- Multiple events at the same timestamp → pick the first encountered (deterministic by iteration order).

## Risk

- Filter reordering could shift behavior for ambiguous questions. Mitigation: most-specific terms first, legacy "returns " (with trailing space) kept as fallback so old phrasings still work.
- New detail-aware filter is opt-in (only set when a registered phrase matches); existing filters unchanged.
- Single-file change, ~80 LOC. Easy to roll back if it surprises in QA.

## Build / deploy

Standard plm-field-tracker pipeline (per project CLAUDE.md):
- Update `whats-new.js` with this entry before `mvn package`.
- `mvn package` produces `target/plm-field-tracker-1.0.1.jar`.
- Copy to `/Volumes/uls-ep-aglipccb/plm-toolkit/` (server) and `~/Documents/plm-toolkit\ 2/` (local).
- User restarts the local instance to verify; server pickup is a separate operator step.

I'll stop after the local build + verification and ask before deploying further.
