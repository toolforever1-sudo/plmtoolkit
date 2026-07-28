# BOM Race — Concept Showcase — Design

**Date:** 2026-05-10
**Status:** Approved, ready for implementation plan

## Goal

A new admin-only "concept showcase" tab that races two implementations of BOM **explode** head-to-head and reports who finished first and whether the results agree:

- **Toolkit lane** — the existing cached-snapshot path (`bom_extract` + `item_extract` via Oracle CONNECT BY).
- **Agile SDK lane** — a live walk through Agile via `plm-agile-service` (port 8081).

Admin picks an input source (random N items with BOMs from `item_extract`, or an uploaded `.xlsx`), hits **Start race**, watches both lanes run side-by-side in real time, then sees a final scoreboard with timings + match scores.

## Out of scope (v1)

- **Implode** — explode-only for v1; revisit if the showcase lands.
- **Persistent race history table** — every race is in-memory only. The activity log captures the headline numbers (`BOM_RACE_START` / `BOM_RACE_DONE`) for trend-spotting via grep.
- **Cross-run comparison view** — each race is self-contained.
- **CSV/Excel export** of race results — per-item diff is on screen only.
- **Implementing this in the toolkit JVM** — no `AgileAPI936.jar` dependency added to `plm-field-tracker`. SDK code lives in `plm-agile-service` only.

## Decisions (locked during brainstorming)

| # | Decision | Rationale |
|---|---|---|
| 1 | Add SDK endpoints to existing `plm-agile-service` | Reuses the Spring Boot project that already holds the live SDK session. Keeps `plm-field-tracker` free of `AgileAPI936.jar`. |
| 2 | Explode only | One mode for v1; sharper demo; less new code. |
| 3 | Random sample = "items with BOMs" only, capped at `app.bomrace.max-items=10` | Truly random from `item_extract` is mostly leaf parts → boring 0-row races. Cap is configurable so the demo can flip to 25 mid-session if timings look too similar. |
| 4 | Match score = set-match + structural-match (both shown) | Set-match (distinct components) is the forgiving headline; structural-match (parent→child + qty) is the stricter sub-metric. Avoids whitespace/refdes false negatives. |
| 5 | UI placement: **new "Labs" top-level tab**, "BOM Race" as its first sub-tab | Establishes a reusable home for future concept showcases. Admin-only via `np-admin-only` CSS class plus server-side role check. |
| 6 | Race-screen layout: **split lanes** (side-by-side toolkit vs SDK) | The user explicitly framed this as "race each other." Side-by-side lanes deliver that feel; best for screen-share demos. |
| 7 | Credentials: **none from the user** — the race rides on `plm-agile-service`'s existing SDK session | Same as how `AgileLookupController` currently uses it. Admin doesn't enter Agile creds. |
| 8 | Lane fairness: each side runs items its **natural** way — toolkit calls the existing batched `BomDataService.explodeMultiple()` (one widening IN-list query for all N items, the same path the production BOM tab uses since commit `55f6c07`); SDK runs items sequentially in one session | Asymmetry is the point of the showcase: real production cost of each path. A tooltip on the race screen surfaces this so it isn't hidden. **Avoids the bug** of fanning out per-item `explode()` calls in parallel — `BomDataService` keeps `bomTruncated` / `bomSkippedItems` as `volatile` instance fields on a Spring singleton, which would race under parallelism. |
| 9 | Local Mac cannot reach Agile — JAR ships to remote for end-to-end test | Captured in `feedback_agile_sdk_remote_testing.md` memory. Build locally, copy via existing `cp ~/git/plm-field-tracker/target/...` post-build step, deploy on remote, test there with same `plmadmin`/`newworld` creds. |

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Browser (admin only)                                            │
│   Labs ▸ BOM Race    ── split-lane race screen + scoreboard      │
└──────────────────────────────────────────────────────────────────┘
            │  POST /api/bomrace/start  → { runId, items[] }
            │  GET  /api/bomrace/{runId}/stream  (SSE)
            ▼
┌──────────────────────────────────────────────────────────────────┐
│  plm-field-tracker  (this Spring Boot app)                       │
│                                                                  │
│  BomRaceController  ─┐                                           │
│  BomRaceService      │  fires BOTH lanes at the same instant     │
│   ├── ToolkitLane    │  (CompletableFuture.allOf)                │
│   │   └─ BomDataService.explode()  [existing, SQL CONNECT BY]    │
│   │                                                              │
│   └── SdkLane                                                    │
│       └─ HTTP → plm-agile-service /api/lookup/bom/explode        │
│                                                                  │
│  Lanes stream progress events back to the browser through       │
│  one SSE channel keyed by runId (SDK lane ticks per-item live;   │
│  toolkit lane batched, emits a final burst — see Lane fairness). │
└──────────────────────────────────────────────────────────────────┘
            │  POST /api/lookup/bom/explode  { items[], maxDepth }
            ▼
┌──────────────────────────────────────────────────────────────────┐
│  plm-agile-service  (port 8081, separate Spring Boot project)    │
│                                                                  │
│  AgileBomController  +  AgileBomService                          │
│   └─ Live SDK walk: IItem.getTable(BOM) recursively, per item.   │
└──────────────────────────────────────────────────────────────────┘
```

**Why this shape**
- All race orchestration lives in `plm-field-tracker` so `plm-agile-service` stays a dumb data provider — easy to reuse for non-race features later.
- Both lanes are submitted from a single `CompletableFuture.allOf(...)` invocation so the start instant is genuinely shared. Clock starts from `System.nanoTime()` captured *before* both submits.
- SSE (`SseEmitter` — built into Spring) streams per-item events to the browser. Race traffic is one-way, short-lived (seconds, not hours), so WebSocket is overkill and polling would lose the live feel.

## Data flow

### Resolving the input list

**Random mode:**
```sql
SELECT i.PART_NUMBER
  FROM item_extract i
 WHERE i.PART_NUMBER IN (SELECT DISTINCT BOM_NUMBER FROM bom_extract)
 ORDER BY DBMS_RANDOM.VALUE
 FETCH FIRST ? ROWS ONLY
```
N capped at `app.bomrace.max-items` (default `10`).

**Upload mode:** reuses `UploadColumnDetector` (autodetects the item-number column, same plumbing the regular BOM tab uses). Capped to the same N — anything over is truncated with a UI warning.

### Race lifecycle

```
1. Browser POSTs /api/bomrace/start { mode:"random", n:10 }
                                or  { mode:"upload", items:[…] }
   → backend pre-flights GET /api/lookup/bom/health on plm-agile-service
   → if down → 503 {ok:false, reason:"agile-service-unreachable"} (no runId)
   → if up   → resolves item list, creates a runId, returns { runId, items }
2. Browser opens GET /api/bomrace/{runId}/stream (SSE, text/event-stream)
3. Backend BomRaceService:
      ToolkitLane = CompletableFuture.supplyAsync(...)   ← SQL CONNECT BY (4-thread pool)
      SdkLane     = CompletableFuture.supplyAsync(...)   ← HTTP → plm-agile-service
      Both submitted within the same line; clock starts from System.nanoTime() captured BEFORE both submits.
4. Each lane emits SSE events as items complete.
5. When both futures complete, backend computes set-match + structural-match,
   emits race-done, closes the stream.
```

### SSE event types

| event | when | body |
|---|---|---|
| `race-start` | both lanes submitted | `{runId, items:["FG-78451",...], startedAt:1730000000123}` |
| `item-start` | lane begins an item | `{lane:"toolkit"\|"sdk", item:"FG-78451", t:42}` (`t` = ms since start) |
| `item-done` | lane finishes an item | `{lane, item, rows:387, durationMs:1240}` |
| `item-error` | lane throws/times out for one item | `{lane, item, errorMs, message:"…"}` (race continues) |
| `lane-done` | lane finishes all items | `{lane, totalMs:4200, totalRows:3847, errorCount:0}` |
| `race-done` | both lanes done, comparison computed | `{toolkitMs, sdkMs, speedup, setMatch:{ok:10,total:10,details:[…]}, structuralMatch:{ok:9,total:10,details:[…]}}` — `speedup = sdkMs / toolkitMs` (positive ratio, e.g. `12.4` means toolkit is 12.4× faster). If `toolkitMs > sdkMs`, the value is still `sdkMs / toolkitMs` (a fraction < 1) — the UI flips the label to "X× slower" in that case. |

### Lane fairness

- **Toolkit lane:** one call to `BomDataService.explodeMultiple(items, maxDepth)` — the existing batched widening-IN-list path (commit `55f6c07`, `5min → 30s on 2.8K-item bulk`). This is what the production BOM tab does today, so the toolkit shows its honest speed. No per-item ticking on the toolkit progress bar — it's a single spinner that flips to "done in X.Xs" when the batched call returns. The SSE stream synthesizes per-item entries from the batched result so the diff details still populate; per-item `durationMs` on the toolkit side is reported as `null` (the batched call doesn't yield per-item times).
- **SDK lane:** N items processed *sequentially* in `plm-agile-service`. The Agile SDK session object isn't safely shareable across threads (legacy SDK, not thread-safe); per-thread sessions would distort the comparison with login overhead. Sequential is the honest "how PX code actually runs." The SDK lane emits live `item-start` / `item-done` events.
- This asymmetry is **the point of the showcase** — natural-mode-of-each-tool is what real users see in production. A tooltip on the race screen explains it so the comparison is transparent.

> **Why not fan out `BomDataService.explode()` per item across a thread pool?** `BomDataService` is a Spring singleton with `volatile` instance fields (`bomTruncated`, `bomSkippedItems`) mutated inside `explode()`. Parallel calls would race on those fields and corrupt the existing BOM tab's truncation reporting. The batched path side-steps the issue entirely *and* is what the production code already uses.

## File changes

### plm-field-tracker (this app)

| File | New/Edit | Purpose |
|---|---|---|
| `controller/BomRaceController.java` | NEW | `POST /api/bomrace/start` (resolves item list, returns `runId`), `GET /api/bomrace/{runId}/stream` (SSE). Admin-only — same `session.getAttribute("role")` check used in `AiEvalController`. |
| `service/BomRaceService.java` | NEW | Orchestrates both lanes via `CompletableFuture.allOf`, owns the per-`runId` `SseEmitter`, computes set + structural match at the end. |
| `service/AgileBomRaceClient.java` | NEW | Spring `RestTemplate` HTTP client to `plm-agile-service /api/lookup/bom/explode`. URL from existing `agile.service.url` property. |
| `model/BomRaceRun.java` | NEW | Holds `runId`, items, lane futures, emitter, `nanoTime` start. |
| `static/bomrace.js` | NEW | Drives the Labs ▸ BOM Race screen: input form, `EventSource` wiring, progress bars, clock, scoreboard render, "Show details" diff expander. |
| `static/index.html` | EDIT | Add **Labs** top-level tab (`np-admin-only`), with "BOM Race" as the first sub-tab. Markup follows the BOM tab's existing sub-nav pattern (`bomSubTabExplorer` / `Compare`). |
| `static/app.js` | EDIT | Register `'bomrace'` and `'labs'` tab IDs in `switchTab()` routing. |
| `static/whats-new.js` | EDIT | Per CLAUDE.md, add today's entry: "🧪 New: Labs tab with BOM Race showcase (admin only)". |
| `application.properties` | EDIT | Add `app.bomrace.max-items=10`, `app.bomrace.sdk.item-timeout-ms=60000`, `app.bomrace.race-timeout-ms=300000`, `app.bomrace.run-ttl-ms=600000`. |

No `AuthFilter` changes — `/api/**` is already gated, and the controller does its own admin role check (server-side gate, not just CSS).

### plm-agile-service (port 8081)

| File | New/Edit | Purpose |
|---|---|---|
| `controller/AgileBomController.java` | NEW | `POST /api/lookup/bom/explode` taking `{items:[…], maxDepth:20}`, returns `{perItem:[{item, rows:[…], durationMs}], totalMs, errorCount}`. `GET /api/lookup/bom/health` for the toolkit's pre-flight check. |
| `service/AgileBomService.java` | NEW | For each item: reuse the existing SDK session, walk `IItem.getTable(BOM)` recursively (depth-cap 20), accumulate rows. Sequential per-item by design. |
| `model/SdkBomRow.java` | NEW | `{level, parent, component, qty, refdes, seq}` — same shape as toolkit's `BomResult` so set/structural diffs work without translation. |

## UI

```
┌─ Input row ─────────────────────────────────────────────────────────┐
│ [Random | Upload]   N=[10]   [⚡ Start race]      Race time: 0:38   │
├─ Toolkit lane ──────────────┬─ Agile SDK lane ────────────────────┤
│ 💻 Toolkit         4.2s ✓   │ 🌐 Agile SDK            0:38        │
│ Cached SQL · CONNECT BY     │ Live walk via plm-agile-service     │
│ ████████████████████████ 100│ ███████░░░░░░░░░░░░░░░░░  32%       │
│ 10/10 · 3,847 rows          │ 3/10 · FG-78451 active              │
│ ✓ Finished, waiting on SDK  │                                     │
├─ Final scoreboard (revealed when race-done) ────────────────────────┤
│  Toolkit  ████░░░░░░░░░░░░░░░░░ 4.2s                                │
│  Agile SDK ████████████████████ 52.1s                               │
│  ✓ Set match 10/10 · Structural match 9/10 · 12.4× faster           │
│  ▸ Show details (per-item timings + diff: parts present in one      │
│     side only, qty mismatches)                                      │
└──────────────────────────────────────────────────────────────────────┘
```

Visual styling follows the **Email Design Guidelines** from the project root `CLAUDE.md`:
- Toolkit lane accent: primary blue `#4a6fa5`.
- SDK lane accent: warning amber `#C7801B`.
- Success callout: `#1F8A4C` (set/structural match green).
- IBM Plex Sans for body, IBM Plex Mono for the race clock and timings, IBM Plex Serif for the scoreboard hero "12.4× faster" line.

## Error handling

| Scenario | Behavior |
|---|---|
| `plm-agile-service` not reachable | Pre-flight `GET /api/lookup/bom/health` from `BomRaceController` *before* creating a runId. If down → `503 {ok:false, reason:"agile-service-unreachable"}`. UI shows a clear "Agile service unavailable — race can't start" callout, rest of toolkit stays usable. **No half-started races.** |
| SDK times out on a single item | Per-item timeout = `app.bomrace.sdk.item-timeout-ms=60000`. On expiry → emit `item-error` event, record the failed item in `lane-done.errorCount`, **continue with remaining items.** SDK lane completes; scoreboard shows e.g. "9/10 items raced · 1 SDK error". |
| Whole race exceeds wall-clock cap | `app.bomrace.race-timeout-ms=300000` (5 min). On expiry → cancel both futures, emit `race-done` with whatever is finished, mark unfinished items as errored. Demo never hangs forever. |
| Toolkit SQL errors (Oracle hiccup) | Same pattern: per-item try/catch, emit `item-error`, race continues. Symmetric with SDK lane. |
| Browser closes / EventSource drops mid-race | `BomRaceService` registers `emitter.onCompletion` and `onError` callbacks → cancels both futures, removes the run from the in-memory map. No orphan threads. |
| Two admins race at the same time | Each has its own `runId` and emitter. In-memory `Map<String, BomRaceRun>` keyed by `runId`. Single admin restarting the race → old runId's futures get cancelled and emitter closed; new runId starts fresh. |
| Item in cache but missing from Agile (cache drift) | SDK returns 0 rows for that item → `item-done` with `rows:0`. Set-match diff details surface it as "found in toolkit only" — exactly the kind of drift the showcase exists to expose. Not an error. |
| Item that was sampled has had its BOM deleted between sample and explode | Both lanes return 0 rows → `item-done rows:0` on both → set-match still ✓. Surfaces in the per-item details with a "0 rows" badge. |
| `runId` state cleanup | Lazy sweep: every `POST /api/bomrace/start` first removes any map entries older than `app.bomrace.run-ttl-ms=600000` (10 min). No `@Scheduled` job — the toolkit's whole scheduling subsystem is gated by `app.scheduling.disabled=true` on local (per `Application.java`'s `@ConditionalOnProperty`), so a scheduled cleaner wouldn't fire there. Lazy sweep works in every environment. |

## Observability

- Log file (standard `java.util.logging` at INFO, same pattern as `BomDataService`):
  - `[BOM_RACE] runId=abc started · n=10 · mode=random`
  - `[BOM_RACE] runId=abc lane=sdk item=FG-78451 done in 2347ms`
  - `[BOM_RACE] runId=abc finished · toolkit=4200ms · sdk=52100ms · setMatch=10/10 · structMatch=9/10`
- Activity log entries (`BOM_RACE_START`, `BOM_RACE_DONE`) include both timings — over time you can grep them to spot trends without rebuilding the showcase.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `app.bomrace.max-items` | `10` | Hard cap on N per race. Bump to `25` mid-demo if timings are too similar. |
| `app.bomrace.sdk.item-timeout-ms` | `60000` | Per-item timeout on the SDK lane. |
| `app.bomrace.race-timeout-ms` | `300000` | Whole-race wall-clock cap. |
| `app.bomrace.run-ttl-ms` | `600000` | How long completed runs linger in the in-memory map. |
| `agile.service.url` | `http://localhost:8081` (existing) | Reused for the SDK lane HTTP calls. |

## Testing strategy

- **Local (this Mac):** verify the toolkit-lane path, UI rendering, error-state callouts (since Agile service unreachable locally), Labs tab gating for non-admins, cleanup on browser disconnect. Pre-flight health check should reliably return 503 with a clear message.
- **Remote (deployed JAR):** end-to-end race, both lanes streaming, scoreboard, set/structural match details, per-item error injection (force a bogus item number to verify `item-error` doesn't kill the race). Same `plmadmin`/`newworld` creds.
- **Build + handoff:** standard `mvn package` → `target/plm-field-tracker-1.0.1.jar` → existing `cp ... /Volumes/uls-ep-aglipccb/plm-toolkit` post-build step → tell user where to look.
