# Team Report In-App Charts (Plan 1 of N) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Quarter view to the in-app PCM table and three new in-app charts (Volume-by-PCM, Volume-by-Month, Changes-by-Month split by ECO/AML/MCO) — all computed from data the `/api/team-report/data` payload already returns, so no backend or Python work and full local testability.

**Architecture:** The in-app viewer (`teamreport-inapp.js`) renders everything as HTML strings into `#teamReportInAppView` via `innerHTML`; charts are hand-rolled `<div>`-height bars (no chart library). We keep that idiom. New behavior is split into **pure functions** (aggregation + chart-series building — unit-tested with Node's built-in test runner) and thin **HTML-render wrappers** (verified manually in the local browser). A small `module.exports` guard at the bottom of the file exposes the pure functions to Node without affecting browser loading.

**Tech Stack:** Vanilla ES5-style JS (matching the existing file), Node v26 `node:test` + `node:assert` for unit tests. No new dependencies. Local app at `http://localhost:8090`.

---

## Plan roadmap (decomposition of the spec)

This is **Plan 1 of a multi-plan effort** decomposed from
`docs/superpowers/specs/2026-06-22-team-report-enhancements-design.md`. Each plan ships working,
testable software on its own. Later plans get their own detailed documents.

- **Plan 1 (this doc):** In-app payload-ready work — PCM table Quarter view (#1 partial), in-app
  Volume-by-PCM (#2), Volume-by-Month (#3), Changes-by-Month ECO/AML/MCO split (#4). Pure JS.
- **Plan 2:** In-app charts needing a backend payload extension — Change-Activities pie (#6) and
  ECN-by-Product-Line stacked (#7). Adds extraction to `TeamReportController` + `/api/team-report/data`.
- **Plan 3:** Historical subsystem — seed `Yearly History` from Noraida's data, `/api/team-report/history`
  endpoint, in-app Year toggle (#1 Year), Changes-by-Year (#5), ECN-by-PL-by-Year (#8).
- **Plan 4:** AI layer — per-PCM workload analytics (#10, new layer) + report-improvement suggestions
  (#9), across in-app + AI sidecar.
- **Plan 5:** Excel engine — A1 template-seeded native charts for #4, #5, #7, #8 + `Yearly History`
  sheet writer (`build_team_report.py`, server-verified).
- **Plan 6:** PPTX — wire slides 5–9 + per-PCM workload content (`team_report_pptx_generator.py`,
  server-verified).

---

## File structure

- **Modify:** `src/main/resources/static/teamreport-inapp.js` — add pure helpers, three chart render
  functions, the Quarter view branch, the generalized view toggle, and the `module.exports` guard.
- **Create:** `test/js/teamreport-inapp.test.js` — Node unit tests for the pure functions.
- **Create:** `test/js/README.md` — one line on how to run the JS tests.

No other files change in Plan 1. The data payload shape consumed here (already returned by the
backend) is:

```js
// d = TRI_STATE.data
{
  month: 'May_2026',
  months: ['Jan','Feb','Mar','Apr','May'],   // short month names, current year
  pcms: ['Daeren Hong','Syauqie', /* … */],
  changes: { 'Daeren Hong': { Jan:[aml,eco,mco,ecn], Feb:[…], … }, … },
  volume:  { 'Daeren Hong': { Jan:[aml,eco,mco,ecn], Feb:[…], … }, … },
  ytd:     { 'Daeren Hong': [items,xco,ecn], … }
}
```

---

## Task 1: Quarter aggregation pure helpers

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js` (add functions near the existing
  `triMonthTotals` at line 113; add export guard at end of file)
- Create: `test/js/teamreport-inapp.test.js`
- Create: `test/js/README.md`

- [ ] **Step 1: Write the failing test**

Create `test/js/teamreport-inapp.test.js`:

```js
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const tri = require('../../src/main/resources/static/teamreport-inapp.js');

const FIXTURE = {
  month: 'May_2026',
  months: ['Jan', 'Feb', 'Mar', 'Apr', 'May'],
  pcms: ['Daeren Hong', 'Syauqie'],
  changes: {
    'Daeren Hong': { Jan: [1, 10, 2, 8], Feb: [0, 5, 1, 4], Mar: [2, 8, 0, 6], Apr: [1, 6, 1, 5], May: [0, 7, 2, 5] },
    'Syauqie':     { Jan: [0, 3, 1, 2], Feb: [1, 2, 0, 2], Mar: [0, 4, 1, 3], Apr: [0, 3, 0, 2], May: [1, 5, 1, 4] }
  },
  volume: {
    'Daeren Hong': { Jan: [10, 100, 20, 8], Feb: [0, 50, 10, 4], Mar: [20, 80, 0, 6], Apr: [10, 60, 10, 5], May: [0, 70, 20, 5] },
    'Syauqie':     { Jan: [0, 30, 10, 2], Feb: [10, 20, 0, 2], Mar: [0, 40, 10, 3], Apr: [0, 30, 0, 2], May: [10, 50, 10, 4] }
  },
  ytd: { 'Daeren Hong': [1536, 92, 75], 'Syauqie': [800, 60, 40] }
};

test('triQuarterOf maps months to quarters', () => {
  assert.strictEqual(tri.triQuarterOf('Jan'), 'Q1');
  assert.strictEqual(tri.triQuarterOf('Mar'), 'Q1');
  assert.strictEqual(tri.triQuarterOf('Apr'), 'Q2');
  assert.strictEqual(tri.triQuarterOf('Dec'), 'Q4');
});

test('triMonthsInQuarter returns only available months for a quarter', () => {
  assert.deepStrictEqual(tri.triMonthsInQuarter('Q1', FIXTURE.months), ['Jan', 'Feb', 'Mar']);
  assert.deepStrictEqual(tri.triMonthsInQuarter('Q2', FIXTURE.months), ['Apr', 'May']); // Jun absent
  assert.deepStrictEqual(tri.triMonthsInQuarter('Q3', FIXTURE.months), []);
});

test('triQuartersInData lists quarters that have at least one month', () => {
  assert.deepStrictEqual(tri.triQuartersInData(FIXTURE.months), ['Q1', 'Q2']);
});

test('triPcmTotalsOverMonths sums a PCM across a month list', () => {
  // Daeren Hong Q1 changes: Jan[1,10,2,8]+Feb[0,5,1,4]+Mar[2,8,0,6] = [3,23,3,18]
  assert.deepStrictEqual(
    tri.triPcmTotalsOverMonths(FIXTURE.changes, 'Daeren Hong', ['Jan', 'Feb', 'Mar']),
    [3, 23, 3, 18]
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: FAIL — `Cannot find module` or `tri.triQuarterOf is not a function` (the file has no exports yet).

- [ ] **Step 3: Add the pure helpers and export guard**

In `src/main/resources/static/teamreport-inapp.js`, immediately after `triMonthTotals` (ends line 124)
and before `function triSum` (line 126), add:

```js
// Quarter helpers (Plan 1). Pure — unit-tested in test/js/teamreport-inapp.test.js.
var TRI_QUARTER_MONTHS = {
    Q1: ['Jan', 'Feb', 'Mar'],
    Q2: ['Apr', 'May', 'Jun'],
    Q3: ['Jul', 'Aug', 'Sep'],
    Q4: ['Oct', 'Nov', 'Dec']
};

function triQuarterOf(shortMonth) {
    for (var q in TRI_QUARTER_MONTHS) {
        if (TRI_QUARTER_MONTHS[q].indexOf(shortMonth) >= 0) return q;
    }
    return null;
}

function triMonthsInQuarter(quarter, months) {
    var inQ = TRI_QUARTER_MONTHS[quarter] || [];
    return months.filter(function (m) { return inQ.indexOf(m) >= 0; });
}

function triQuartersInData(months) {
    var seen = [];
    ['Q1', 'Q2', 'Q3', 'Q4'].forEach(function (q) {
        if (triMonthsInQuarter(q, months).length > 0) seen.push(q);
    });
    return seen;
}

function triPcmTotalsOverMonths(src, pcm, monthList) {
    var t = [0, 0, 0, 0];
    var byMonth = src[pcm];
    if (!byMonth) return t;
    monthList.forEach(function (m) {
        var row = byMonth[m];
        if (!row) return;
        for (var i = 0; i < 4; i++) t[i] += row[i] || 0;
    });
    return t;
}
```

At the very **end** of the file, add the export guard:

```js
// Node test harness only — no effect in the browser (module is undefined there).
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        triQuarterOf: triQuarterOf,
        triMonthsInQuarter: triMonthsInQuarter,
        triQuartersInData: triQuartersInData,
        triPcmTotalsOverMonths: triPcmTotalsOverMonths
    };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: PASS — 4 tests pass.

- [ ] **Step 5: Add the JS test README**

Create `test/js/README.md`:

```md
# JS unit tests

Pure-function tests for the static JS in `src/main/resources/static/`.

Run all:  `node --test test/js/`

These cover only side-effect-free helpers (aggregation, chart-series building).
HTML-render functions are verified manually against the local app at http://localhost:8090.
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js test/js/README.md
git commit -m "feat(team-report): quarter aggregation helpers for in-app PCM table"
```

---

## Task 2: Quarter view in the PCM table + generalized toggle

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js`
  - `triSelectView` (line 569-573)
  - `triPcmTableHtml` (line 326-362)
  - the toggle options in `triRender` (line 227-229)
- Modify: `test/js/teamreport-inapp.test.js` (add quarter-row test)

The current view state is binary ('This month' / 'Jan–May YTD'). We generalize it to also support
'Quarter'. (The 'Year' option is deferred to Plan 3, where multi-year data makes it meaningful.)

- [ ] **Step 1: Write the failing test for the quarter row builder**

Append to `test/js/teamreport-inapp.test.js`:

```js
test('triPcmQuarterRows builds one row per PCM plus a total, for a quarter', () => {
  const rows = tri.triPcmQuarterRows(FIXTURE, 'Q1');
  // Daeren Hong Q1 changes [3,23,3,18]; volume items = aml+eco+mco summed over Jan-Mar
  // volume Jan[10,100,20]+Feb[0,50,10]+Mar[20,80,0] = items 10+100+20+0+50+10+20+80+0 = 290
  assert.deepStrictEqual(rows[0], {
    pcm: 'Daeren Hong', aml: 3, eco: 23, mco: 3, xco: 29, ecn: 18, items: 290
  });
  const total = rows[rows.length - 1];
  assert.strictEqual(total.__total, true);
  assert.strictEqual(total.pcm, 'All PCMs');
  // totals = Daeren[3,23,3] + Syauqie Q1 changes Jan[0,3,1]+Feb[1,2,0]+Mar[0,4,1]=[1,9,2]
  assert.strictEqual(total.aml, 4);
  assert.strictEqual(total.eco, 32);
  assert.strictEqual(total.mco, 5);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: FAIL — `tri.triPcmQuarterRows is not a function`.

- [ ] **Step 3: Add `triPcmQuarterRows` and export it**

In `teamreport-inapp.js`, add after `triPcmTotalsOverMonths`:

```js
function triPcmQuarterRows(d, quarter) {
    var monthsInQ = triMonthsInQuarter(quarter, d.months);
    var rows = [];
    var tAml = 0, tEco = 0, tMco = 0, tEcn = 0, tItems = 0;
    d.pcms.forEach(function (p) {
        var c = triPcmTotalsOverMonths(d.changes, p, monthsInQ);
        var v = triPcmTotalsOverMonths(d.volume, p, monthsInQ);
        var items = v[0] + v[1] + v[2];
        rows.push({ pcm: p, aml: c[0], eco: c[1], mco: c[2], xco: c[0] + c[1] + c[2], ecn: c[3], items: items });
        tAml += c[0]; tEco += c[1]; tMco += c[2]; tEcn += c[3]; tItems += items;
    });
    rows.push({ pcm: 'All PCMs', aml: tAml, eco: tEco, mco: tMco, xco: tAml + tEco + tMco, ecn: tEcn, items: tItems, __total: true });
    return rows;
}
```

Add `triPcmQuarterRows: triPcmQuarterRows` to the `module.exports` object at the end of the file.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: PASS — all tests pass.

- [ ] **Step 5: Generalize the view toggle (browser behavior)**

Replace `triSelectView` (currently lines 569-573):

```js
function triSelectView(opt) {
    if (opt && opt.indexOf('YTD') >= 0) TRI_STATE.view = 'YTD';
    else if (opt === 'Q1' || opt === 'Q2' || opt === 'Q3' || opt === 'Q4') TRI_STATE.view = opt;
    else TRI_STATE.view = 'This month';
    triRender();
}
```

Update the default state comment and value at line 25 from:

```js
    view: 'This month',  // or 'Jan–May YTD'
```
to:
```js
    view: 'This month',  // 'This month' | 'YTD' | 'Q1'..'Q4'
```

Update every existing `TRI_STATE.view === 'This month'` check that was using `'Jan–May YTD'` as the
"other" branch. Specifically in `triRender` (lines 221-223) the YTD label test still works because it
checks `=== 'This month'`; leave those. In `triPcmTableHtml` (line 331), change the branch condition
from `if (TRI_STATE.view === 'This month')` handling and add a Quarter branch (next step).

- [ ] **Step 6: Add the Quarter branch to `triPcmTableHtml`**

Replace `triPcmTableHtml` (lines 326-362) with:

```js
function triPcmTableHtml(month) {
    var d = TRI_STATE.data;
    var headers = ['PCM', 'AML', 'ECO', 'MCO', 'Total xCO', 'ECN', 'Affected items'];
    var mapRow = function (r) { return [r.pcm, r.aml, r.eco, r.mco, r.xco, r.ecn, triFmt(r.items)]; };
    var totalOpt = { isTotal: function (i, row) { return row && /All PCMs/.test(row[0]); } };

    if (TRI_STATE.view === 'Q1' || TRI_STATE.view === 'Q2' || TRI_STATE.view === 'Q3' || TRI_STATE.view === 'Q4') {
        var qrows = triPcmQuarterRows(d, TRI_STATE.view);
        return triDataTableHtml(headers, qrows.map(mapRow), totalOpt);
    }

    if (TRI_STATE.view === 'This month') {
        var ct = triMonthTotals(d.changes, month);
        var vt = triMonthTotals(d.volume, month);
        var rows = [];
        d.pcms.forEach(function (p) {
            var c = d.changes[p][month] || [0, 0, 0, 0];
            var v = d.volume[p][month] || [0, 0, 0, 0];
            rows.push({ pcm: p, aml: c[0], eco: c[1], mco: c[2], xco: c[0] + c[1] + c[2], ecn: c[3], items: v[0] + v[1] + v[2] });
        });
        rows.push({ pcm: 'All PCMs', aml: ct[0], eco: ct[1], mco: ct[2], xco: ct[0] + ct[1] + ct[2], ecn: ct[3], items: vt[0] + vt[1] + vt[2], __total: true });
        return triDataTableHtml(headers, rows.map(mapRow), totalOpt);
    }

    // YTD view
    var arr = Object.keys(d.ytd || {}).map(function (p) {
        var v = d.ytd[p];
        return [p, triFmt(v[0]), v[1], v[2]];
    });
    return triDataTableHtml(['PCM', 'Affected items', 'Total xCO', 'Total ECN'], arr, {});
}
```

Note: `isTotal` now matches on the rendered first cell `'All PCMs'` so it works for both month and
quarter rows (the quarter mapper does not carry `__total` through `mapRow`).

- [ ] **Step 7: Add Quarter buttons to the toggle in `triRender`**

Replace the toggle block (lines 227-229) with a dynamic option list that appends the quarters present
in the data:

```js
        +        triSegmentedHtml('triView',
                    ['This month', 'Jan–' + month + ' YTD'].concat(triQuartersInData(d.months)),
                    TRI_STATE.view === 'This month' ? 'This month'
                        : (TRI_STATE.view === 'YTD' ? 'Jan–' + month + ' YTD' : TRI_STATE.view))
```

Because `triSegmentedHtml`'s buttons call `triSelectView(opt)` with the literal label, the YTD button
passes `'Jan–May YTD'` (contains 'YTD' → maps to 'YTD') and the quarter buttons pass `'Q1'`/`'Q2'`
(matched directly). No change to `triSegmentedHtml` needed.

- [ ] **Step 8: Manually verify in the browser**

Start the local app (heap ≥4g):
```bash
cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties
```
Log in at http://localhost:8090 (user `plmadmin`, password from private memory). Open ECN Report →
Team Report. Confirm:
- The toggle now shows `This month | Jan–May YTD | Q1 | Q2`.
- Clicking `Q1` shows per-PCM rows whose AML/ECO/MCO equal the sum of Jan+Feb+Mar, with an "All PCMs"
  total row.
- Clicking `Q2` shows Apr+May sums. `This month` and `YTD` behave as before.

Expected: all three behaviors correct; no console errors.

- [ ] **Step 9: Run the full JS test suite**

Run: `node --test test/js/`
Expected: PASS — all tests green.

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js
git commit -m "feat(team-report): Quarter view for in-app PCM table (#1)"
```

---

## Task 3: Volume-by-PCM combo chart (#2)

A per-PCM grouped bar chart: Total xCO and Total ECN bars per PCM, with affected-items shown as a
labeled overlay line value. We render it with the same `<div>`-height idiom as `triVolumeChartHtml`.

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js` (add `triPcmVolumeSeries` + `triPcmChartHtml`,
  wire a new GroupCard into `triRender`)
- Modify: `test/js/teamreport-inapp.test.js`

- [ ] **Step 1: Write the failing test**

Append to `test/js/teamreport-inapp.test.js`:

```js
test('triPcmVolumeSeries returns xco/ecn/items totals per PCM across all months', () => {
  const s = tri.triPcmVolumeSeries(FIXTURE);
  const daeren = s.find(x => x.pcm === 'Daeren Hong');
  // changes xco = sum over months of (aml+eco+mco):
  // Jan 13, Feb 6, Mar 10, Apr 8, May 9 = 46 ; ecn = 8+4+6+5+5 = 28
  assert.strictEqual(daeren.xco, 46);
  assert.strictEqual(daeren.ecn, 28);
  // volume items = sum over months of (aml+eco+mco):
  // Jan 130, Feb 60, Mar 100, Apr 80, May 90 = 460
  assert.strictEqual(daeren.items, 460);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: FAIL — `tri.triPcmVolumeSeries is not a function`.

- [ ] **Step 3: Implement `triPcmVolumeSeries` and export it**

Add after `triPcmQuarterRows`:

```js
function triPcmVolumeSeries(d) {
    return d.pcms.map(function (p) {
        var c = triPcmTotalsOverMonths(d.changes, p, d.months);
        var v = triPcmTotalsOverMonths(d.volume, p, d.months);
        return { pcm: p, xco: c[0] + c[1] + c[2], ecn: c[3], items: v[0] + v[1] + v[2] };
    });
}
```

Add `triPcmVolumeSeries: triPcmVolumeSeries` to `module.exports`.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: PASS.

- [ ] **Step 5: Implement the render wrapper `triPcmChartHtml`**

Add near `triVolumeChartHtml` (after line 420):

```js
function triPcmChartHtml() {
    var d = TRI_STATE.data;
    var s = triPcmVolumeSeries(d);
    var maxBar = Math.max.apply(null, s.map(function (x) { return Math.max(x.xco, x.ecn); }).concat([1]));
    var maxItems = Math.max.apply(null, s.map(function (x) { return x.items; }).concat([1]));
    var colors = { xco: '#B8342B', ecn: '#7FA84B' };
    var html = '<div style="display:grid; grid-template-columns:repeat(' + s.length + ', 1fr); gap:0; padding:18px 18px 10px; align-items:end;">';
    s.forEach(function (x) {
        html += '<div style="display:flex; flex-direction:column; align-items:center; padding:8px 6px 6px;">';
        html += '<div style="display:flex; align-items:flex-end; gap:5px; height:150px;">';
        [['xco', x.xco], ['ecn', x.ecn]].forEach(function (kv) {
            var h = Math.max(2, (kv[1] / maxBar) * 130);
            html += '<div style="width:24px; border-radius:2px 2px 0 0; height:' + h + 'px; background:' + colors[kv[0]] + '; position:relative;">'
                  + '<span style="position:absolute; top:-16px; left:50%; transform:translateX(-50%); font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6B7280; white-space:nowrap;">' + triFmt(kv[1]) + '</span>'
                  + '</div>';
        });
        html += '</div>';
        // affected-items value (the "line" series, shown as a labeled chip under the bars)
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6f42c1; margin-top:4px;">▲ ' + triFmt(x.items) + '</div>';
        html += '<div style="font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif; font-size:11px; color:#0F1720; font-weight:500; margin-top:4px; text-align:center; line-height:1.2;">' + triEsc(x.pcm) + '</div>';
        html += '</div>';
    });
    html += '</div>';
    html += '<div style="display:flex; gap:14px; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; align-items:center;">'
          + '<span><i style="width:9px; height:9px; background:#B8342B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>Total xCO</span>'
          + '<span><i style="width:9px; height:9px; background:#7FA84B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>Total ECN</span>'
          + '<span style="color:#6f42c1;">▲ Affected items</span>'
          + '</div>';
    return html;
}
```

- [ ] **Step 6: Wire a new GroupCard into `triRender`**

In `triRender`, immediately after the monthly bar-chart GroupCard (after line 245, before the AI
GroupCard at line 247), insert:

```js
        // GroupCard 2b: volume by PCM (#2)
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">Total volume processed by PCM</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">Jan&ndash;' + month + ' &middot; <strong>xCO / ECN / affected items</strong></span>'
        + '    </div>'
        + '  </div>'
        + '  ' + triPcmChartHtml()
        + '</div>'
```

- [ ] **Step 7: Manually verify in the browser**

Reload http://localhost:8090 → Team Report. Confirm a new "Total volume processed by PCM" card shows
one labeled bar-pair (xCO red, ECN green) per PCM with an affected-items value beneath, and the
relative bar heights look sane vs the table totals. No console errors.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js
git commit -m "feat(team-report): in-app Volume-by-PCM chart (#2)"
```

---

## Task 4: Volume-by-Month combo chart (#3)

Per-month Total xCO and Total ECN bars + affected-items value, across `d.months`.

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js`
- Modify: `test/js/teamreport-inapp.test.js`

- [ ] **Step 1: Write the failing test**

Append:

```js
test('triMonthVolumeSeries returns xco/ecn/items per month', () => {
  const s = tri.triMonthVolumeSeries(FIXTURE);
  const jan = s.find(x => x.month === 'Jan');
  // changes Jan across PCMs: Daeren[1,10,2,8] + Syauqie[0,3,1,2] = [1,13,3,10]
  // xco = 1+13+3 = 17 ; ecn = 10
  assert.strictEqual(jan.xco, 17);
  assert.strictEqual(jan.ecn, 10);
  // volume Jan: Daeren[10,100,20] + Syauqie[0,30,10] => items 130+40 = 170
  assert.strictEqual(jan.items, 170);
  assert.strictEqual(s.length, 5);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: FAIL — `tri.triMonthVolumeSeries is not a function`.

- [ ] **Step 3: Implement `triMonthVolumeSeries` and export it**

Add after `triPcmVolumeSeries`:

```js
function triMonthVolumeSeries(d) {
    return d.months.map(function (m) {
        var c = triMonthTotals(d.changes, m);
        var v = triMonthTotals(d.volume, m);
        return { month: m, xco: c[0] + c[1] + c[2], ecn: c[3], items: v[0] + v[1] + v[2] };
    });
}
```

Add `triMonthVolumeSeries: triMonthVolumeSeries` to `module.exports`.

> Note: `triMonthTotals` reads `TRI_STATE.data` internally, so in the Node test `TRI_STATE` must be
> populated. Add this once near the top of the test file, after `FIXTURE` is defined:
> ```js
> // triMonthTotals reads the module-global TRI_STATE; point it at the fixture.
> if (tri.__setState) tri.__setState(FIXTURE);
> ```
> and export a tiny setter by adding `__setState: function (d) { TRI_STATE.data = d; }` to
> `module.exports`.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: PASS.

- [ ] **Step 5: Implement the render wrapper `triMonthVolumeChartHtml`**

Add after `triPcmChartHtml`:

```js
function triMonthVolumeChartHtml() {
    var d = TRI_STATE.data;
    var s = triMonthVolumeSeries(d);
    var yy = d.month.split('_')[1].substring(2);
    var maxBar = Math.max.apply(null, s.map(function (x) { return Math.max(x.xco, x.ecn); }).concat([1]));
    var colors = { xco: '#B8342B', ecn: '#7FA84B' };
    var html = '<div style="display:grid; grid-template-columns:repeat(' + s.length + ', 1fr); gap:0; padding:18px 18px 10px; align-items:end;">';
    s.forEach(function (x) {
        html += '<div style="display:flex; flex-direction:column; align-items:center; padding:8px 6px 6px;">';
        html += '<div style="display:flex; align-items:flex-end; gap:5px; height:150px;">';
        [['xco', x.xco], ['ecn', x.ecn]].forEach(function (kv) {
            var h = Math.max(2, (kv[1] / maxBar) * 130);
            html += '<div style="width:24px; border-radius:2px 2px 0 0; height:' + h + 'px; background:' + colors[kv[0]] + '; position:relative;">'
                  + '<span style="position:absolute; top:-16px; left:50%; transform:translateX(-50%); font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6B7280; white-space:nowrap;">' + triFmt(kv[1]) + '</span>'
                  + '</div>';
        });
        html += '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6f42c1; margin-top:4px;">▲ ' + triFmt(x.items) + '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-top:4px;">' + triEsc(x.month) + "'" + yy + '</div>';
        html += '</div>';
    });
    html += '</div>';
    html += '<div style="display:flex; gap:14px; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; align-items:center;">'
          + '<span><i style="width:9px; height:9px; background:#B8342B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>Total xCO</span>'
          + '<span><i style="width:9px; height:9px; background:#7FA84B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>Total ECN</span>'
          + '<span style="color:#6f42c1;">▲ Affected items</span>'
          + '</div>';
    return html;
}
```

- [ ] **Step 6: Wire a GroupCard into `triRender`**

Immediately after the GroupCard 2b inserted in Task 3, insert:

```js
        // GroupCard 2c: volume by month (#3)
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">Total volume processed by month</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">FY' + d.month.split('_')[1].substring(2) + ' &middot; <strong>xCO / ECN / affected items</strong></span>'
        + '    </div>'
        + '  </div>'
        + '  ' + triMonthVolumeChartHtml()
        + '</div>'
```

- [ ] **Step 7: Manually verify in the browser**

Reload Team Report. Confirm a "Total volume processed by month" card with one bar-pair per month
(Jan'26…May'26) and affected-items values. No console errors.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js
git commit -m "feat(team-report): in-app Volume-by-Month chart (#3)"
```

---

## Task 5: Changes-by-Month split by ECO/AML/MCO (#4)

Per-month grouped bars for ECO, AML, MCO change counts, plus the affected-items value. Distinct from
the existing "Total affected items by month" card (which bars affected-item volume); this one bars
the **change counts** by type.

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js`
- Modify: `test/js/teamreport-inapp.test.js`

- [ ] **Step 1: Write the failing test**

Append:

```js
test('triChangeTypeMonthSeries returns eco/aml/mco counts + items per month', () => {
  const s = tri.triChangeTypeMonthSeries(FIXTURE);
  const jan = s.find(x => x.month === 'Jan');
  // changes Jan across PCMs = [aml1, eco13, mco3, ecn10]
  assert.strictEqual(jan.aml, 1);
  assert.strictEqual(jan.eco, 13);
  assert.strictEqual(jan.mco, 3);
  assert.strictEqual(jan.items, 170);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: FAIL — `tri.triChangeTypeMonthSeries is not a function`.

- [ ] **Step 3: Implement `triChangeTypeMonthSeries` and export it**

Add after `triMonthVolumeSeries`:

```js
function triChangeTypeMonthSeries(d) {
    return d.months.map(function (m) {
        var c = triMonthTotals(d.changes, m); // [AML, ECO, MCO, ECN]
        var v = triMonthTotals(d.volume, m);
        return { month: m, aml: c[0], eco: c[1], mco: c[2], items: v[0] + v[1] + v[2] };
    });
}
```

Add `triChangeTypeMonthSeries: triChangeTypeMonthSeries` to `module.exports`.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: PASS.

- [ ] **Step 5: Implement the render wrapper `triChangeTypeMonthChartHtml`**

Add after `triMonthVolumeChartHtml`. Reuses the ECO/MCO/AML color scheme already used by
`triVolumeChartHtml` (ECO `#4a6fa5`, MCO `#9CA3AF`, AML `#C7801B`):

```js
function triChangeTypeMonthChartHtml() {
    var d = TRI_STATE.data;
    var s = triChangeTypeMonthSeries(d);
    var yy = d.month.split('_')[1].substring(2);
    var maxBar = Math.max.apply(null, s.map(function (x) { return Math.max(x.eco, x.aml, x.mco); }).concat([1]));
    var colors = { ECO: '#4a6fa5', MCO: '#9CA3AF', AML: '#C7801B' };
    var html = '<div style="display:grid; grid-template-columns:repeat(' + s.length + ', 1fr); gap:0; padding:18px 18px 10px; align-items:end;">';
    s.forEach(function (x) {
        html += '<div style="display:flex; flex-direction:column; align-items:center; padding:8px 6px 6px;">';
        html += '<div style="display:flex; align-items:flex-end; gap:4px; height:150px;">';
        [['ECO', x.eco], ['MCO', x.mco], ['AML', x.aml]].forEach(function (kv) {
            var h = Math.max(2, (kv[1] / maxBar) * 130);
            html += '<div style="width:18px; border-radius:2px 2px 0 0; height:' + h + 'px; background:' + colors[kv[0]] + '; position:relative;">'
                  + '<span style="position:absolute; top:-15px; left:50%; transform:translateX(-50%); font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; color:#6B7280; white-space:nowrap;">' + triFmt(kv[1]) + '</span>'
                  + '</div>';
        });
        html += '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-top:8px;">' + triEsc(x.month) + "'" + yy + '</div>';
        html += '</div>';
    });
    html += '</div>';
    html += '<div style="display:flex; gap:14px; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; align-items:center;">'
          + '<span><i style="width:9px; height:9px; background:#4a6fa5; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>ECO</span>'
          + '<span><i style="width:9px; height:9px; background:#9CA3AF; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>MCO</span>'
          + '<span><i style="width:9px; height:9px; background:#C7801B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>AML</span>'
          + '</div>';
    return html;
}
```

- [ ] **Step 6: Wire a GroupCard into `triRender`**

Immediately after the GroupCard 2c inserted in Task 4, insert:

```js
        // GroupCard 2d: changes processed by month, by type (#4)
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">Total changes processed by month</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">change counts &middot; <strong>ECO / MCO / AML</strong></span>'
        + '    </div>'
        + '  </div>'
        + '  ' + triChangeTypeMonthChartHtml()
        + '</div>'
```

- [ ] **Step 7: Manually verify in the browser**

Reload Team Report. Confirm a "Total changes processed by month" card with three thin bars
(ECO/MCO/AML) per month and correct legend colors. Cross-check one month's ECO count against the PCM
table's ECO column summed for that month. No console errors.

- [ ] **Step 8: Run the full JS suite**

Run: `node --test test/js/`
Expected: PASS — all tests green.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js
git commit -m "feat(team-report): in-app Changes-by-Month (ECO/AML/MCO) chart (#4)"
```

---

## Done criteria for Plan 1

- `node --test test/js/` passes (all pure-function tests green).
- In the local app, the Team Report tab shows: a working `This month / YTD / Q1 / Q2` toggle on the
  PCM table, and three new chart cards (Volume-by-PCM, Volume-by-Month, Changes-by-Month) rendering
  correctly with no console errors.
- No backend, Python, or PPTX files were touched (those are Plans 2–6).
- Each task committed separately with the messages above.

When Plan 1 is merged and verified, proceed to **Plan 2** (Change-Activities pie #6 + ECN-by-Product-Line
#7), which begins by extending `TeamReportController.extractTeamReportData()` and the
`/api/team-report/data` payload to carry activity-type and product-line breakdowns.
