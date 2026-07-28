# Prod OOM Analysis — Team Report POI DOM + chronic heap pressure

**Date:** 2026-07-08 · **Status:** Analysis complete, fix proposed — NOT implemented, NOT deployed
**Scope:** 5+ `java.lang.OutOfMemoryError: Java heap space` crashes on uls-ep-aglipccb since April (9 heap dumps on disk, ~82 GB total).

## TL;DR

The Jul 7 18:17 crash is explained end-to-end: **every Team Report read path
(`extractTeamReportData` / `extractGroupedForMonth`) opens the 7.7 MB stashed
XLSX via `new XSSFWorkbook(...)`, and that single DOM open costs ~1,050 MB of
heap** (measured empirically with the project's own POI 5.2.5 against the real
Jul 7 workbook — the file holds 131 MB of uncompressed XML, including one
84 MB sheet with 44,850 rows). On a 6 GB heap whose baseline sits at 85–87%,
one more 1 GB transient is fatal. The frontend triggers this parse on **every
Team Report tab open and month switch** (`GET /api/team-report/data`), and the
generate flow parses the workbook again seconds after generation completes.

Recommendation: **fix, don't (just) resize the heap.** The spike is
per-request and unbounded (N users/tabs = N × 1 GB); no realistic -Xmx wins
that race. Live data after a Full GC is only ~2.1 GB, so 6 GB is plenty once
the transient is removed.

## Evidence

### Jul 7 crash timeline (pid 7640, from `logs/plm-toolkit.log` on the share)

| Time | Event |
|---|---|
| 16:39:09 | JVM start (deploy). Item cache loaded: **786,706 records, 501 MB on disk** |
| 18:15:17 | Noraida runs one-click Team Report generate (Jun_2026) |
| 18:15:28 | ChangeSearchService: 6,332 rows |
| 18:16:07 | AI post-process begins → `extractGroupedForMonth` = **POI DOM open #1 (~1 GB)** |
| 18:17:06.698 | `[TEAM-REPORT] OK month=Jun_2026 out=7928239B` — generation done |
| ~18:17:07 | UI receives envelope, tab refresh fires `GET /data?month=Jun_2026` → **POI DOM open #2** → `OutOfMemoryError` |
| 18:20:23 | 8.6 GB hprof written (39 s), `-XX:+ExitOnOutOfMemoryError` exits, watchdog respawns |

After respawn (18:22–18:35) the same user ran regenerate-AI and another
generate **concurrently** — two DOM opens in flight — and survived only
because the heap was fresh.

### Measured numbers (not estimates)

- `new XSSFWorkbook(FileInputStream)` on the Jul 7 workbook: **+1,050 MB** heap.
- `OPCPackage.open(File)` variant: **+913 MB** — the DOM itself is the cost; the File constructor is not a fix.
- Workbook internals: `Raw data-affected item` = 44,850 rows (84 MB XML), pivot caches = 40 MB XML, `Raw data-No Dup` = 3,003 rows. XSSF DOM parses **all** sheets eagerly, so even `/data` (which reads ~3K rows from small sheets) pays the full ~1 GB.
- Idle Full GC on Jul 8 02:11: **75% → 39% (4,625 → 2,120 MB), 2.5 GB collectable garbage reclaimed.** So true live set ≈ 2.1 GB; the routine 85–87% readings are mostly uncollected garbage from big-object churn (POI DOMs, `byte[]`s, base64 strings — humongous allocations under G1 with ~4 MB regions).
- Item cache is the dominant resident block: 786K records × per-record `LinkedHashMap<String,String>` (~27 entries each) + 12 column indexes + 2 date indexes ≈ **1.5–1.8 GB** of the 2.1 GB baseline. `seed()` builds a second full copy before swapping (`newRecords` + old `records` both live) — this is why local needs ≥4g just to seed.
- Apr 22 / May 6 / May 8 crashes predate the current log retention (archive only goes back to Jul 1); the Team Report feature existed by May 8 (a Mar_2026 stash exists dated 2026-05-08 02:21 — note: that timestamp is ~10 hours before the May 8 13:51 crash). Root cause for those is unconfirmed without dump analysis, but the same mechanics (85% baseline + any ~1 GB transient: Team Report, BOM extract, item-cache seed) fit.

### Contributing design issues found in code review

1. **`TeamReportController` never checks `MemoryGuard`** — the circuit breaker exists (used by SkuData/ExternalSource/RejectionTracker/JobQueue) but not on the heaviest endpoint family in the app.
2. No caching of the `/data` payload — the extracted result is a small map (<1 MB), yet it's re-derived via a 1 GB parse on every tab open/month switch by any user.
3. `regenerateAi` does **two** sequential DOM opens (`extractGroupedForMonth` + `extractTeamReportData`); `exportPptx` does one; nothing serializes them across requests/users.
4. The generate envelope carries `outputBase64` (10.6 MB string) + optional `discrepancyBase64` — ~30–40 MB of humongous allocations + a ~21 MB JSON response, even though the stash + `/recent/{storedAs}/download` URL already exist.
5. `HeapPressureMonitor.idleWindowGc` was gated off all evening ("Skipped — 1 user(s) active") and only fired at 02:11 — no daytime relief by design.

## Proposed fix (phased, for review)

### Phase 1 — kill the repeat 1 GB parse (small, high value)

**1a. Cache the workbook-derived `/data` payload keyed by `(xlsxPath, xlsx mtime)`.**
Small LRU (say 6 entries, `LinkedHashMap` accessOrder + `removeEldestEntry`) in
`TeamReportController`. Split `extractTeamReportData` so only the
POI-derived portion (pcms/changes/volume/ytd/activityTypes/ecnByProductLine)
is cached; keep reading `analysis.json` + `meta.json` sidecars fresh per call
(cheap JSON, and **required** because regenerate-AI rewrites the analysis
sidecar without touching the xlsx — caching it would serve stale AI cards).
Return a defensive copy (or build the response map around the cached
immutable part) so per-call `data.put("manager", …)` can't pollute the cache.
Result: tab opens/month switches become ~free; only a brand-new generation
pays one parse.

**1b. Gate + single-flight every POI open in `TeamReportController`.**
- `if (memoryGuard.isUnderPressure()) return 503 "heap busy — try again in a minute"` (same pattern as JobQueueService).
- A `Semaphore(1)` (or `ReentrantLock`) around all `XSSFWorkbook` opens in this controller (`/data` cache-miss, `extractGroupedForMonth`, pptx export) so two users/tabs can never stack 2 × 1 GB. Acquire with a timeout (e.g. 30 s → 503) to avoid pile-ups.

### Phase 2 — stream the reads (removes the spike entirely)

Replace DOM reads with POI's streaming reader (`XSSFReader` +
`XSSFSheetXMLHandler`/SAX) in both `extractTeamReportData` and
`extractGroupedForMonth`. All reads are simple forward row scans over known
sheets/columns — ideal SAX candidates. Expected cost drops from ~1 GB to
<50 MB per read. (Phase 1's cache remains useful to keep the tab snappy.)

### Phase 3 — trim the chronic baseline + churn

- **Envelope diet:** drop `outputBase64`/`discrepancyBase64` from the generate
  response; return the stash `downloadUrl`s instead (both endpoints already
  exist). Frontend change in the Team Report drawer JS.
- **Item cache:** eliminate the double-copy in `seed()` (build straight into
  the replacement `ConcurrentHashMap`); longer term, move per-record storage
  from `LinkedHashMap<String,String>` to a columnar `String[]` keyed by a
  column-index table (~600–800 MB entry-overhead savings). Optional.
- **JVM flags** (one-line `run-loop.bat` change, mirrored to the share):
  - add `-XX:+UseStringDeduplication` — 786K records share massively repeated values (PRODUCTLINE, LIFECYCLE_PHASE, …); free win on JDK 17 G1.
  - add `-XX:InitiatingHeapOccupancyPercent=30` — start G1 concurrent cycles earlier so 2.5 GB of garbage never accumulates while waiting for 02:11.
  - `-Xmx8g` **only if** the box has headroom (`systeminfo` — remember plm-agile-service runs there too with -Xmx2g). This is belt-and-braces, not the fix.

### Heap dump housekeeping (frees ~73 GB now)

9 dumps × 8–10 GB in `D:\plm-toolkit\heapdumps\` (Apr 22, May 6 ×2, May 8/11,
Jun 29 ×3, Jul 7):

- **Keep `java_pid7640.hprof` (Jul 7)** until the fix is verified — it can confirm the allocation site in Eclipse MAT if wanted.
- **Delete the other eight** (~73 GB). They're superseded diagnostics; nobody will MAT a May dump now.
- Add auto-pruning to `run-loop.bat` (top of loop, next to the mkdir):
  `forfiles /P D:\plm-toolkit\heapdumps /M *.hprof /D -14 /C "cmd /c del @path"`
  so future dumps self-expire after 14 days.

## Verification plan (when implemented)

1. Local: generate a Team Report, then hammer `/api/team-report/data` for the month 10× while watching `jcmd GC.heap_info` — expect one parse then cache hits (log line on miss).
2. Local: two concurrent `/data` cache-miss calls → second one waits (semaphore) instead of doubling heap.
3. Regenerate-AI → `/data` must show the fresh analysis (sidecar not cached).
4. Prod (Vikas cutover): watch `HeapPressureMonitor` — sustained-85% alerts should stop being routine.
