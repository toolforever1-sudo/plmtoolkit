# Team Report Plan 3 — Yearly History (#5) + stubs for Year toggle / ECN-by-PL-by-year (#8)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add the org-wide **Total Volume Processed Yearly** chart (#5, 2014–2026) to the in-app Team Report, sourced from a JSON seed of Noraida's own figures (mockup image009). Wire a `/api/team-report/history` endpoint that reads an external override file if present (so Noraida's fuller data drops in without a rebuild). Add lightweight **"awaiting historical data"** stubs for the per-PCM **Year** toggle and the **ECN-by-product-line-by-year** chart (#8), whose per-PCM / per-PL history has no source yet.

**Architecture:** A version-controlled default seed `src/main/resources/team-report/yearly-history.json` ships in the JAR; an optional external `data/team-report/yearly-history.json` (gitignored, hot-swappable) overrides it. `TeamReportController` exposes it at `GET /api/team-report/history`. The in-app viewer fetches it once and renders the yearly chart; the Year toggle and #8 read the (currently empty) `byPcm` / `byProductLine` seed sections and show a placeholder until they're populated.

**Tech Stack:** Java 11 + Jackson (already used: `JSON.readValue`) for the backend; vanilla ES5 JS for the chart; Node `node:test` for the pure year-series transform.

---

## Background / why a seed (verified 2026-06-22)

- The Team Report workbook only contains **2025–2026** rows (`Raw data-affected item` 9.8K rows 2025 + 28.7K 2026; `Raw data-No Dup` ~2.6K). There is **no multi-year history** in it.
- The PPTX deck's "2014–2026" slide 9 is **static mockup PNG images**, not data-backed. `build_team_report.py` maintains only the current calendar year.
- Noraida's mockup **image009** ("Total Volume Process Yearly") is the only concrete source of yearly figures. **Total ECO** and **Total Affected Items** are fully legible for all 13 years; **MCO/AML** labels overlap and are partly unreadable — seed them best-effort and mark provisional.
- Per-PCM-by-year and per-product-line-by-year data exist **nowhere** readable → stub, don't fabricate. User decision (2026-06-22): build #5 now, stub the rest for Noraida's file.

---

## File structure

- **Create:** `src/main/resources/team-report/yearly-history.json` — default seed (from image009).
- **Modify:** `src/main/java/com/sandisk/plm/tracker/controller/TeamReportController.java` — add `GET /history` endpoint + a loader (external override → classpath default).
- **Modify:** `src/main/resources/static/teamreport-inapp.js` — fetch `/history`, pure `triYearSeries`, render `triYearlyChartHtml`, GroupCard; Year-toggle + #8 placeholders.
- **Modify:** `test/js/teamreport-inapp.test.js` — Node test for `triYearSeries`.

### Seed JSON schema

```jsonc
{
  "source": "Seeded from Noraida's mockup image009 (ECN-135613-PROJ), 2026-06. ECO + affectedItems are exact reads; mco/aml (and 2018/2024/2025/2026 minor values) are provisional image reads — REPLACE with Noraida's actual data file. byPcm / byProductLine are intentionally empty until her file is provided.",
  "years": [ { "year": "2014", "label": "2014 (Apr-Dec)", "eco": 2049, "mco": 595, "aml": 388, "affectedItems": 33550 }, … ],
  "byPcm": {},          // year -> { pcm -> [aml,eco,mco,ecn] }  — TODO Noraida's file
  "byProductLine": {}   // year -> { productLine -> ecnCount }   — TODO Noraida's file
}
```

---

## Task 1: Seed file + `/api/team-report/history` endpoint

**Files:**
- Create: `src/main/resources/team-report/yearly-history.json`
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/TeamReportController.java`

- [ ] **Step 1: Create the seed file**

Create `src/main/resources/team-report/yearly-history.json` with exactly:

```json
{
  "source": "Seeded from Noraida's mockup image009 (ECN-135613-PROJ), 2026-06. eco + affectedItems are exact reads from the chart; mco/aml are provisional (labels overlap in the image) and the 2018/2024/2025/2026 minor values are estimates - REPLACE this file with Noraida's actual data when available. byPcm and byProductLine are intentionally empty until her file is provided.",
  "years": [
    { "year": "2014", "label": "2014 (Apr-Dec)", "eco": 2049, "mco": 595, "aml": 388, "affectedItems": 33550 },
    { "year": "2015", "label": "2015", "eco": 4543, "mco": 1312, "aml": 318, "affectedItems": 71431 },
    { "year": "2016", "label": "2016", "eco": 4607, "mco": 1691, "aml": 225, "affectedItems": 68204 },
    { "year": "2017", "label": "2017", "eco": 4759, "mco": 1626, "aml": 280, "affectedItems": 86128 },
    { "year": "2018", "label": "2018", "eco": 5301, "mco": 1577, "aml": 250, "affectedItems": 116853 },
    { "year": "2019", "label": "2019", "eco": 4911, "mco": 1981, "aml": 215, "affectedItems": 98013 },
    { "year": "2020", "label": "2020", "eco": 4009, "mco": 1251, "aml": 135, "affectedItems": 68436 },
    { "year": "2021", "label": "2021", "eco": 3230, "mco": 997, "aml": 173, "affectedItems": 71023 },
    { "year": "2022", "label": "2022", "eco": 2687, "mco": 1103, "aml": 107, "affectedItems": 52322 },
    { "year": "2023", "label": "2023", "eco": 2244, "mco": 911, "aml": 116, "affectedItems": 38399 },
    { "year": "2024", "label": "2024", "eco": 3043, "mco": 1100, "aml": 20, "affectedItems": 44756 },
    { "year": "2025", "label": "2025", "eco": 3153, "mco": 989, "aml": 390, "affectedItems": 49442 },
    { "year": "2026", "label": "2026 (YTD)", "eco": 1568, "mco": 565, "aml": 200, "affectedItems": 28726 }
  ],
  "byPcm": {},
  "byProductLine": {}
}
```

- [ ] **Step 2: Add the endpoint + loader to TeamReportController**

First find an existing endpoint to mirror style (e.g. the `/months` or `/data` mapping, and the auth-guard pattern `session.getAttribute("username") == null`). Then add this endpoint method (near the other `@GetMapping`s) and a loader. Use the existing `JSON` ObjectMapper field already in the class (used by the analysis loader at line ~879 as `JSON.readValue(...)`).

```java
@GetMapping("/history")
public ResponseEntity<Map<String, Object>> teamReportHistory(HttpSession session) {
    if (session.getAttribute("username") == null) {
        return ResponseEntity.status(401).body(err("Not authenticated"));
    }
    try {
        return ResponseEntity.ok(loadYearlyHistory());
    } catch (Exception e) {
        LOG.warning("[TEAM-REPORT-HISTORY] failed: " + e.getMessage());
        return ResponseEntity.status(500).body(err("Failed to read yearly history: " + e.getMessage()));
    }
}

/**
 * Yearly history seed. External override at ./data/team-report/yearly-history.json wins
 * (hot-swappable with Noraida's real file); otherwise the bundled classpath default.
 */
private Map<String, Object> loadYearlyHistory() throws IOException {
    java.nio.file.Path override = java.nio.file.Paths.get("data", "team-report", "yearly-history.json");
    if (java.nio.file.Files.exists(override)) {
        return JSON.readValue(override.toFile(), new TypeReference<Map<String, Object>>(){});
    }
    try (java.io.InputStream in = getClass().getResourceAsStream("/team-report/yearly-history.json")) {
        if (in == null) throw new IOException("bundled yearly-history.json not found on classpath");
        return JSON.readValue(in, new TypeReference<Map<String, Object>>(){});
    }
}
```

If `err(...)`, `LOG`, `JSON`, `TypeReference`, `HttpSession` aren't resolvable, mirror exactly how the existing `/data` endpoint (around line 677) references them — they are all already used in this file.

- [ ] **Step 3: Compile**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q compile 2>&1 | tail -20
```
Expected: BUILD SUCCESS. (No app run / tests — orchestrator does live curl validation.)

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/team-report/yearly-history.json src/main/java/com/sandisk/plm/tracker/controller/TeamReportController.java
git commit -m "feat(team-report): yearly-history seed + /api/team-report/history endpoint (#5)"
```

---

## Task 2: Frontend — Total Volume Processed Yearly chart (#5)

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js`
- Modify: `test/js/teamreport-inapp.test.js`

The viewer fetches `/history` once and caches it on `TRI_STATE.history`; the chart reuses Plan 1's shared `triXcoEcnItemsChartHtml`? No — yearly needs ECO/MCO/AML bars + an affected-items line, like Plan 1's Changes-by-Month chart. We render with the same div-bar idiom.

- [ ] **Step 1: Add the `/history` fetch**

In `teamreport-inapp.js`, add a cache field to `TRI_STATE` (near the top object): `history: null,`. Then in `teamReportInAppInit` (after it calls the months probe) OR at the top of `triLoadMonth`, add a one-time fetch:

```js
function triLoadHistory() {
    if (TRI_STATE.history) { return; }
    fetch('/api/team-report/history', { credentials: 'same-origin' })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (h) { if (h) { TRI_STATE.history = h; if (TRI_STATE.data) triRender(); } })
        .catch(function () { /* yearly chart just stays hidden on failure */ });
}
```
Call `triLoadHistory();` once inside `teamReportInAppInit()` (right after the `fetch('/api/team-report/months'...)` line is fine — it runs in parallel).

- [ ] **Step 2: Write the failing test**

Append to `test/js/teamreport-inapp.test.js`:

```js
test('triYearSeries shapes the history payload into per-year rows', () => {
  const hist = { years: [
    { year: '2014', label: '2014 (Apr-Dec)', eco: 2049, mco: 595, aml: 388, affectedItems: 33550 },
    { year: '2015', label: '2015', eco: 4543, mco: 1312, aml: 318, affectedItems: 71431 }
  ]};
  const s = tri.triYearSeries(hist);
  assert.strictEqual(s.length, 2);
  assert.strictEqual(s[0].label, '2014 (Apr-Dec)');
  assert.strictEqual(s[0].eco, 2049);
  assert.strictEqual(s[0].items, 33550);
  assert.strictEqual(s[1].xco, 4543 + 1312 + 318); // total xCO derived
});

test('triYearSeries returns [] for missing/empty history', () => {
  assert.deepStrictEqual(tri.triYearSeries(null), []);
  assert.deepStrictEqual(tri.triYearSeries({}), []);
});
```

- [ ] **Step 3: Run → FAIL**

`node --test test/js/teamreport-inapp.test.js` → `tri.triYearSeries is not a function`.

- [ ] **Step 4: Implement `triYearSeries` + export**

```js
function triYearSeries(history) {
    if (!history || !history.years || !history.years.length) return [];
    return history.years.map(function (y) {
        var eco = y.eco || 0, mco = y.mco || 0, aml = y.aml || 0;
        return { year: y.year, label: y.label || y.year, eco: eco, mco: mco, aml: aml,
                 xco: eco + mco + aml, items: y.affectedItems || 0 };
    });
}
```
Add `triYearSeries: triYearSeries` to `module.exports`.

- [ ] **Step 5: Run → PASS**

`node --test test/js/teamreport-inapp.test.js` → all pass.

- [ ] **Step 6: Implement `triYearlyChartHtml` (presentational)**

ECO/MCO/AML grouped bars + an affected-items value chip per year, scaled independently (bars on change-count scale, items shown as a labeled chip — the existing idiom, since we have no dual-axis line primitive). Reuses the ECO/MCO/AML palette.

```js
function triYearlyChartHtml() {
    var h = TRI_STATE.history;
    var s = triYearSeries(h);
    if (!s.length) return '';   // chart card hidden until /history loads
    var maxBar = Math.max.apply(null, s.map(function (x) { return Math.max(x.eco, x.mco, x.aml); }).concat([1]));
    var colors = { ECO: '#4a6fa5', MCO: '#9CA3AF', AML: '#C7801B' };
    var html = '<div style="display:grid; grid-template-columns:repeat(' + s.length + ', 1fr); gap:0; padding:18px 12px 10px; align-items:end; overflow-x:auto;">';
    s.forEach(function (x) {
        html += '<div style="display:flex; flex-direction:column; align-items:center; padding:8px 3px 6px;">';
        html += '<div style="display:flex; align-items:flex-end; gap:2px; height:150px;">';
        [['ECO', x.eco], ['MCO', x.mco], ['AML', x.aml]].forEach(function (kv) {
            var ht = Math.max(2, (kv[1] / maxBar) * 130);
            html += '<div title="' + kv[0] + ': ' + kv[1] + '" style="width:9px; border-radius:2px 2px 0 0; height:' + ht + 'px; background:' + colors[kv[0]] + ';"></div>';
        });
        html += '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:8.5px; color:#6f42c1; margin-top:4px; white-space:nowrap;">&#9650; ' + triFmt(x.items) + '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; color:#6B7280; margin-top:2px; white-space:nowrap; transform:rotate(-35deg); transform-origin:center;">' + triEsc(x.year) + '</div>';
        html += '</div>';
    });
    html += '</div>';
    html += '<div style="display:flex; gap:14px; padding:6px 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; align-items:center;">'
          + '<span><i style="width:9px; height:9px; background:#4a6fa5; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>ECO</span>'
          + '<span><i style="width:9px; height:9px; background:#9CA3AF; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>MCO</span>'
          + '<span><i style="width:9px; height:9px; background:#C7801B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>AML</span>'
          + '<span style="color:#6f42c1;">&#9650; Affected items</span>'
          + '<span style="margin-left:auto; color:#9CA3AF;">Org-wide &middot; seeded from history file</span>'
          + '</div>';
    return html;
}
```

- [ ] **Step 7: Wire a GroupCard into `triRender` (only when history is present)**

Insert immediately after the GroupCard 2f (ECN by product line, from Plan 2) and before `// GroupCard 3: AI analysis`. Guard so the card only appears once `/history` has loaded:

```js
        + (TRI_STATE.history && triYearSeries(TRI_STATE.history).length ?
          (
            // GroupCard 2g: total volume processed yearly (#5)
            '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
          + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
          + '    <div>'
          + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">Total volume processed yearly</span>'
          + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">2014&ndash;2026 &middot; <strong>org-wide</strong></span>'
          + '    </div>'
          + '  </div>'
          + '  ' + triYearlyChartHtml()
          + '</div>'
          ) : '')
```

- [ ] **Step 8: Parse check + full suite**

`node -e "require('./src/main/resources/static/teamreport-inapp.js'); console.log('parse OK')"` → `parse OK`
`node --test test/js/*.test.js` → all pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js
git commit -m "feat(team-report): in-app Total Volume Processed Yearly chart (#5)"
```

---

## Task 3: Stubs — Year toggle (#1-Year) + ECN-by-PL-by-year (#8) placeholders

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js`

Both stubs read the seed's (currently empty) `byPcm` / `byProductLine` and show a clear "awaiting Noraida's historical file" note, so the UI advertises the feature without fabricating data. No new pure logic → no new tests.

- [ ] **Step 1: Add a "Year" toggle button that shows a placeholder**

In `triSelectView`, add a `'Year'` branch:
```js
function triSelectView(opt) {
    if (opt && opt.indexOf('YTD') >= 0) TRI_STATE.view = 'YTD';
    else if (opt === 'Q1' || opt === 'Q2' || opt === 'Q3' || opt === 'Q4') TRI_STATE.view = opt;
    else if (opt === 'Year') TRI_STATE.view = 'Year';
    else TRI_STATE.view = 'This month';
    triRender();
}
```
Append `'Year'` to the toggle options in `triRender` (the `triSegmentedHtml('triView', [...].concat(triQuartersInData(d.months)), …)` call): change the options array to also concat `['Year']`, i.e. `['This month', 'Jan–' + month + ' YTD'].concat(triQuartersInData(d.months)).concat(['Year'])`. Update the selected-value expression so `TRI_STATE.view === 'Year'` selects `'Year'` (the existing `else` already returns `TRI_STATE.view`, so `'Year'` is returned as-is — verify).

In `triPcmTableHtml`, add a `Year` branch BEFORE the quarter branch:
```js
    if (TRI_STATE.view === 'Year') {
        var hasPcmYear = TRI_STATE.history && TRI_STATE.history.byPcm && Object.keys(TRI_STATE.history.byPcm).length;
        if (!hasPcmYear) {
            return '<div style="padding:18px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#6B7280; line-height:1.5;">'
                 + 'Per-PCM yearly history isn\'t loaded yet. Drop Noraida\'s historical data file at '
                 + '<code>data/team-report/yearly-history.json</code> (populate the <code>byPcm</code> section) and it will appear here.'
                 + '</div>';
        }
        // (future) render per-PCM yearly rows from TRI_STATE.history.byPcm
    }
```

- [ ] **Step 2: Add an #8 placeholder GroupCard**

Insert immediately after GroupCard 2g (yearly chart) and before `// GroupCard 3: AI analysis`:
```js
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">ECN by product line, by year</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">2014&ndash;2026</span>'
        + '    </div>'
        + '  </div>'
        + '  <div style="padding:18px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#6B7280; line-height:1.5;">'
        +    'Awaiting historical product-line data. Populate the <code>byProductLine</code> section of '
        +    '<code>data/team-report/yearly-history.json</code> (per year &rarr; product line &rarr; ECN count) with Noraida&rsquo;s file and this chart will render (top&nbsp;12 + Other, like the monthly version).'
        + '  </div>'
        + '</div>'
```

- [ ] **Step 3: Parse check + full suite + commit**

`node -e "require('./src/main/resources/static/teamreport-inapp.js'); console.log('parse OK')"` → `parse OK`
`node --test test/js/*.test.js` → all pass.
```bash
git add src/main/resources/static/teamreport-inapp.js
git commit -m "feat(team-report): Year-toggle + ECN-by-PL-by-year placeholders awaiting Noraida's history file (#1,#8)"
```

---

## Done criteria

- `node --test test/js/*.test.js` passes (Plan 1+2 + `triYearSeries`).
- `GET /api/team-report/history` returns the seed (13 years, 2014→2026).
- In the running app, the Team Report tab shows a **Total volume processed yearly** chart (2014–2026, ECO/MCO/AML bars + affected-items chips); the PCM-table toggle has a **Year** option that shows the "awaiting per-PCM history" note; an **ECN by product line, by year** card shows its placeholder. No console errors.

## End-to-end verification (orchestrator, after all tasks)

Run the new Java (`target/*.jar` from the local data dir, or `mvn spring-boot:run`) so `/history` is live, override the static dir to the repo frontend, log in as plmadmin, open the Team Report tab. Confirm: `curl /api/team-report/history` returns 13 years; the yearly chart renders with the affected-items peak at 2018 (116,853); the Year toggle + #8 show placeholders. Screenshot the yearly chart.

## Note for when Noraida's file arrives

Replacing `data/team-report/yearly-history.json` (external override) with her real figures — including the `byPcm` (year → pcm → [aml,eco,mco,ecn]) and `byProductLine` (year → PL → ecnCount) sections — lights up the Year toggle and #8 with **no code change**. Also correct the provisional mco/aml values in `years[]` from her file at that time.
