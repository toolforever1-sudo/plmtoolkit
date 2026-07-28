# Team Report Plan 2 — Change-Activities Pie (#6) + ECN-by-Product-Line (#7)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new in-app Team Report charts that need data the payload doesn't yet carry — a Change-Activities donut (#6) and an ECN-by-Product-Line stacked column (#7, top-12 lines + "Other") — by extending the backend `/api/team-report/data` payload and rendering both client-side.

**Architecture:** `TeamReportController.extractTeamReportData` (Apache POI) gains a second sheet pass that re-derives two breakdowns from `Raw data-No Dup` + the `Classification Change Type` lookup sheet, replicating the workbook's VLOOKUP so the numbers match Noraida's Excel/PPTX. Two new payload keys (`activityTypes`, `ecnByProductLine`) feed two new render functions in `teamreport-inapp.js`. Pure client-side transforms (donut stops, top-N bucketing) are unit-tested with Node; rendering and backend extraction are verified live against the running app and validated against the workbook's pivot grand totals.

**Tech Stack:** Java 11 + Apache POI (backend), vanilla ES5 JS with CSS `conic-gradient` donut + div stacked bars (frontend), Node `node:test` for pure transforms.

---

## Background facts (verified against the live May_2026 workbook)

- The pie's category column in the raw sheets (`col Q`) is an **unevaluated VLOOKUP formula → reads empty** via POI. Do NOT use it.
- Re-derive the category instead: `Raw data-No Dup` **col P** ("Classifictions" [sic], populated, e.g. `BOM/Component Update|Alternate Linkage`) looked up against the **`Classification Change Type`** sheet (col A = classification key, col B = category). This reproduces `col Q`'s VLOOKUP. Categories seen: `New Part/BOM Creation, Part/BOM Change, Firmware/Test, IDM/DM, Lifecycle/Status Change, Data Alignment, New SKU Creation, Supplier Change` (plus a `Data Aligment` typo to normalize).
- Product lines: `Raw data-No Dup` **col G** ("Product Line(s)"), multiple lines joined by **`|`** (e.g. `1032 - Client - SATA|1037 - Client - PCIe`). Split on `|`.
- Month filter: `Raw data-No Dup` **col O** = `Mmm_YYYY` (e.g. `May_2026`); current-year months use short names (`Jan`..`May`).
- Column indices (0-based): A=0 ECN#, B=1 xCO#, F=5 ChangeType, G=6 ProductLines, O=14 Month, P=15 Classifictions, Q=16 (formula, unused), R=17.
- Existing reusable helper: `cellStr(Cell)` (line ~1253) handles STRING/NUMERIC/**FORMULA** (cached) → use it. (`readStr` does NOT handle formulas — don't use it here.)
- Insertion point: inside the open-workbook try-with-resources, immediately after `out.put("ytd", ytd);` (line 872), before the closing `}` (line 873).
- Validation targets (this snapshot): `Monthly Change Activities` pivot grand total = **351**; May product-line top counts: `1037 - Client - PCIe`=70, `9701 - Wafer Purchase/Die Parts`=61, `1024 - MicroSD`=47.

---

## File structure

- **Modify:** `src/main/java/com/sandisk/plm/tracker/controller/TeamReportController.java` — add a private helper `addActivityAndProductLine(XSSFWorkbook, Map, String, List<String>)` and call it at the insertion point.
- **Modify:** `src/main/resources/static/teamreport-inapp.js` — add pure transforms (`triDonutStops`, `triTopNProductLines`) + render functions (`triActivityPieHtml`, `triEcnByPlHtml`) + two GroupCards in `triRender`.
- **Modify:** `test/js/teamreport-inapp.test.js` — Node tests for the two pure transforms.

No new files.

### New payload shape (added keys)

```jsonc
{
  // …existing keys…
  "activityTypes": [ {"name":"Part/BOM Change","count":108}, {"name":"Lifecycle/Status Change","count":67}, … ],  // desc by count, current month
  "ecnByProductLine": {            // current-year months → (product line → ECN count)
     "Jan": {"1037 - Client - PCIe": 61, "1024 - MicroSD": 40, …},
     "Feb": { … }, … "May": { … }
  }
}
```

---

## Task 1: Backend — add `activityTypes` + `ecnByProductLine` to the payload

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/TeamReportController.java`

This task has no JUnit fixture (no sample workbook in the repo); it is verified by curling the running app and comparing to the workbook's pivot totals (steps 4–6).

- [ ] **Step 1: Add the helper method**

Add this private method immediately after `extractTeamReportData(...)` ends (after line 906, before `monthLabel`):

```java
/**
 * Re-derive two breakdowns the rolled-forward sheets don't expose as plain values:
 *  - activityTypes: change-activity category counts for `month`, via col P (classification)
 *    looked up against the 'Classification Change Type' sheet (replicating col Q's VLOOKUP).
 *  - ecnByProductLine: per-(current-year month) product-line counts, splitting col G on '|'.
 * Source sheet is 'Raw data-No Dup' (deduped, matches the workbook's pivots).
 */
private void addActivityAndProductLine(XSSFSheet noDup, XSSFSheet lookup,
                                       Map<String, Object> out, String month,
                                       java.util.List<String> monthLabels) {
    // classification -> category map
    Map<String, String> clsToCat = new java.util.HashMap<>();
    if (lookup != null) {
        for (int r = 1; r <= lookup.getLastRowNum(); r++) {
            Row row = lookup.getRow(r);
            if (row == null) continue;
            String cls = cellStr(row.getCell(0)).trim();
            String cat = cellStr(row.getCell(1)).trim();
            if (!cls.isEmpty() && !cat.isEmpty()) clsToCat.put(cls, normalizeCategory(cat));
        }
    }

    String targetYear = month.contains("_") ? month.substring(month.indexOf('_') + 1) : "";

    Map<String, Integer> activity = new java.util.HashMap<>();
    // current-year months → (PL → count), seeded in display order
    Map<String, Map<String, Integer>> plByMonth = new LinkedHashMap<>();
    for (String m : monthLabels) plByMonth.put(m, new java.util.HashMap<>());

    if (noDup != null) {
        for (int r = 1; r <= noDup.getLastRowNum(); r++) {
            Row row = noDup.getRow(r);
            if (row == null) continue;
            String rowMonth = cellStr(row.getCell(14)).trim();   // O = Month (e.g. May_2026)
            if (rowMonth.isEmpty() || !rowMonth.contains("_")) continue;
            String mmm = rowMonth.substring(0, rowMonth.indexOf('_'));
            String yyyy = rowMonth.substring(rowMonth.indexOf('_') + 1);

            // activity categories: current month only
            if (rowMonth.equalsIgnoreCase(month)) {
                String cls = cellStr(row.getCell(15)).trim();    // P = Classifictions
                String cat = clsToCat.get(cls);
                if (cat != null && !cat.isEmpty()) activity.merge(cat, 1, Integer::sum);
            }
            // product lines: any current-year month we display
            if (yyyy.equals(targetYear) && plByMonth.containsKey(mmm)) {
                String plField = cellStr(row.getCell(6));        // G = Product Line(s)
                for (String pl : plField.split("\\|")) {
                    String p = pl.trim();
                    if (!p.isEmpty()) plByMonth.get(mmm).merge(p, 1, Integer::sum);
                }
            }
        }
    }

    // activityTypes → list of {name,count} desc by count
    java.util.List<Map<String, Object>> activityList = new ArrayList<>();
    activity.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getKey());
                m.put("count", e.getValue());
                activityList.add(m);
            });

    out.put("activityTypes", activityList);
    out.put("ecnByProductLine", plByMonth);
}

private static String normalizeCategory(String c) {
    if (c == null) return "";
    String t = c.trim();
    if (t.equalsIgnoreCase("Data Aligment")) return "Data Alignment";  // fix known source typo
    return t;
}
```

- [ ] **Step 2: Call the helper at the insertion point**

In `extractTeamReportData`, inside the try-with-resources, change line 872 region from:

```java
            out.put("ytd", ytd);
        }
```
to:
```java
            out.put("ytd", ytd);

            addActivityAndProductLine(
                wb.getSheet("Raw data-No Dup"),
                wb.getSheet("Classification Change Type"),
                out, month, monthLabels);
        }
```

(Confirm the local variable holding the months list is named `monthLabels`; if the code uses a different name for the list put into `out.put("months", …)`, pass that variable instead.)

- [ ] **Step 3: Compile**

Run (Corretto 11):
```bash
cd /Users/vikasjindal/git/plm-field-tracker
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -o compile 2>&1 | tail -20
```
Expected: BUILD SUCCESS (no compile errors). If offline `-o` fails to resolve, drop `-o`.

- [ ] **Step 4: Run the app serving this build, against real data**

The repo's running-app verification pattern (proven in Plan 1): build is not needed to serve Java changes via the existing JAR — Java changes DO require a rebuilt classpath. Use `mvn spring-boot:run` so the compiled controller is live, pointing at the local data + config, on a spare port:

```bash
cd /Users/vikasjindal/git/plm-field-tracker
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home \
  mvn -q -o spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Xmx4g" \
  -Dspring-boot.run.arguments="--server.port=8096 --spring.config.additional-location=file:/Users/vikasjindal/Documents/plm-toolkit 2/config/application.properties --app.cache.file=/Users/vikasjindal/Documents/plm-toolkit 2/cache/field-changes-cache.ser" \
  > /tmp/plan2-app.log 2>&1 &
```
Wait for `Started Application` in `/tmp/plan2-app.log` (cache seed ~30–60s). If the data/cache relative paths don't resolve from the repo CWD, instead copy `target/*.jar` into `~/Documents/plm-toolkit 2/` and run it there (see Plan 1's deploy note) — but for verification `spring-boot:run` with absolute config/cache paths is preferred.

- [ ] **Step 5: Authenticate + fetch the new keys**

```bash
MEM="/Users/vikasjindal/.claude/projects/-Users-vikasjindal-git-plm-field-tracker/memory/secret_plmadmin_password.md"
PW=$(grep -m1 'Password (cleartext)' "$MEM" | sed -E 's/.*`([^`]+)`.*/\1/')
curl -sS -c /tmp/p2c.txt -H "Content-Type: application/json" \
  --data "{\"username\":\"plmadmin\",\"password\":$(printf '%s' "$PW" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')}" \
  http://localhost:8096/api/auth/login >/dev/null
curl -sS -b /tmp/p2c.txt "http://localhost:8096/api/team-report/data?month=May_2026" \
 | python3 -c 'import json,sys;d=json.load(sys.stdin);a=d.get("activityTypes");print("activityTypes:",a);print("activity total:",sum(x["count"] for x in a));pl=d.get("ecnByProductLine",{});print("May PL top5:",sorted(pl.get("May",{}).items(),key=lambda x:-x[1])[:5])'
```

- [ ] **Step 6: Validate against the workbook pivots**

Expected:
- `activity total` should be within a few of the `Monthly Change Activities` pivot grand total for that snapshot (rows whose classification isn't in the lookup are dropped — log the delta; a small gap is acceptable, a large one means a column/lookup mismatch).
- `May PL top5` should lead with `1037 - Client - PCIe` (~70) and `9701 - Wafer Purchase/Die Parts` (~61).

If totals are wildly off (e.g. 0 or 10×), re-check the column indices (P=15, G=6, O=14) and that `Raw data-No Dup`/`Classification Change Type` sheet names match. Stop the app (`kill` the spring-boot:run JVM on :8096) when done.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/TeamReportController.java
git commit -m "feat(team-report): add activityTypes + ecnByProductLine to /data payload (#6,#7)"
```

---

## Task 2: Frontend — Change-Activities donut (#6)

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js`
- Modify: `test/js/teamreport-inapp.test.js`

Renders a CSS `conic-gradient` donut + a count/percent table from `d.activityTypes`. Pure stop-computation is unit-tested.

- [ ] **Step 1: Write the failing test**

Append to `test/js/teamreport-inapp.test.js`:

```js
test('triDonutStops builds cumulative conic-gradient stops with percentages', () => {
  const items = [{ name: 'A', count: 75 }, { name: 'B', count: 25 }];
  const r = tri.triDonutStops(items, ['#111', '#222']);
  assert.strictEqual(r.total, 100);
  assert.strictEqual(r.segments[0].pct, 75);
  assert.strictEqual(r.segments[1].pct, 25);
  // gradient string covers 0→75% then 75→100%
  assert.ok(r.gradient.includes('#111 0% 75%'));
  assert.ok(r.gradient.includes('#222 75% 100%'));
});

test('triDonutStops handles empty input without dividing by zero', () => {
  const r = tri.triDonutStops([], ['#111']);
  assert.strictEqual(r.total, 0);
  assert.deepStrictEqual(r.segments, []);
  assert.strictEqual(r.gradient, '#E8E6DF 0% 100%'); // neutral ring
});
```

- [ ] **Step 2: Run → FAIL**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: FAIL — `tri.triDonutStops is not a function`.

- [ ] **Step 3: Implement `triDonutStops` + export**

Add near the other pure helpers in `teamreport-inapp.js`:

```js
function triDonutStops(items, palette) {
    var total = 0;
    items.forEach(function (it) { total += it.count || 0; });
    if (total <= 0) {
        return { total: 0, segments: [], gradient: '#E8E6DF 0% 100%' };
    }
    var segments = [], stops = [], acc = 0;
    items.forEach(function (it, i) {
        var pct = Math.round((it.count / total) * 1000) / 10; // 1-dp
        var start = (acc / total) * 100;
        acc += it.count || 0;
        var end = (acc / total) * 100;
        var color = palette[i % palette.length];
        segments.push({ name: it.name, count: it.count, pct: pct, color: color });
        stops.push(color + ' ' + (Math.round(start * 100) / 100) + '% ' + (Math.round(end * 100) / 100) + '%');
    });
    return { total: total, segments: segments, gradient: stops.join(', ') };
}
```

Add `triDonutStops: triDonutStops` to `module.exports`.

> Note: the test asserts integer-looking bounds (`0% 75%`). With two items 75/25 of total 100, starts/ends are exactly 0/75/100, and `Math.round(x*100)/100` yields `0`, `75`, `100` (no trailing `.0`), so the substring checks pass.

- [ ] **Step 4: Run → PASS**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: PASS.

- [ ] **Step 5: Implement the render wrapper `triActivityPieHtml`**

Add near the other render functions:

```js
var TRI_PIE_COLORS = ['#4a6fa5', '#1F8A4C', '#C7801B', '#7C3AED', '#B8342B', '#0F766E', '#9CA3AF', '#D97706', '#2c3e50'];

function triActivityPieHtml() {
    var d = TRI_STATE.data;
    var items = (d.activityTypes || []);
    if (!items.length) {
        return '<div style="padding:18px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#6B7280;">No change-activity data for this month.</div>';
    }
    var donut = triDonutStops(items, TRI_PIE_COLORS);
    var html = '<div style="display:flex; gap:24px; align-items:center; flex-wrap:wrap; padding:18px;">';
    // donut: conic-gradient ring with a white hole
    html += '<div style="position:relative; width:170px; height:170px; flex:0 0 auto;">'
          + '<div style="width:170px; height:170px; border-radius:50%; background:conic-gradient(' + donut.gradient + ');"></div>'
          + '<div style="position:absolute; top:50%; left:50%; transform:translate(-50%,-50%); width:96px; height:96px; border-radius:50%; background:#fff; display:flex; flex-direction:column; align-items:center; justify-content:center;">'
          + '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:24px; font-weight:500; color:#0F1720;">' + triFmt(donut.total) + '</div>'
          + '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280;">changes</div>'
          + '</div></div>';
    // legend / count table
    html += '<div style="flex:1 1 320px; min-width:280px;"><table style="width:100%; border-collapse:collapse; font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;">';
    donut.segments.forEach(function (s) {
        html += '<tr>'
              + '<td style="padding:5px 8px; width:14px;"><span style="display:inline-block; width:10px; height:10px; border-radius:2px; background:' + s.color + ';"></span></td>'
              + '<td style="padding:5px 8px; font-size:12.5px; color:#0F1720;">' + triEsc(s.name) + '</td>'
              + '<td style="padding:5px 8px; font-size:12.5px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; color:#0F1720;">' + triFmt(s.count) + '</td>'
              + '<td style="padding:5px 8px; font-size:11.5px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; color:#6B7280;">' + s.pct + '%</td>'
              + '</tr>';
    });
    html += '</table></div></div>';
    return html;
}
```

- [ ] **Step 6: Wire a GroupCard into `triRender`**

Insert immediately after the GroupCard 2d (Changes-by-Month, from Plan 1) and before `// GroupCard 3: AI analysis`:

```js
        // GroupCard 2e: change-activities pie (#6)
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">Change activities by type</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">' + triEsc(monthFull) + ' ' + d.month.split('_')[1] + ' &middot; <strong>% of changes</strong></span>'
        + '    </div>'
        + '  </div>'
        + '  ' + triActivityPieHtml()
        + '</div>'
```

- [ ] **Step 7: Parse check + full suite**

Run: `node -e "require('./src/main/resources/static/teamreport-inapp.js'); console.log('parse OK')"` → `parse OK`
Run: `node --test test/js/*.test.js` → all pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js
git commit -m "feat(team-report): in-app Change-Activities donut (#6)"
```

---

## Task 3: Frontend — ECN-by-Product-Line stacked column, top-12 + Other (#7)

**Files:**
- Modify: `src/main/resources/static/teamreport-inapp.js`
- Modify: `test/js/teamreport-inapp.test.js`

- [ ] **Step 1: Write the failing test**

Append to `test/js/teamreport-inapp.test.js`:

```js
test('triTopNProductLines keeps top N by grand total and buckets the rest into Other', () => {
  const ecnByMonth = {
    Jan: { PLa: 10, PLb: 5, PLc: 2, PLd: 1 },
    Feb: { PLa: 8, PLb: 4, PLc: 3, PLe: 1 }
  };
  const r = tri.triTopNProductLines(ecnByMonth, ['Jan', 'Feb'], 2);
  // grand totals: PLa=18, PLb=9, PLc=5, PLd=1, PLe=1 → top2 = PLa, PLb; rest → Other
  assert.deepStrictEqual(r.lines, ['PLa', 'PLb', 'Other']);
  // Jan Other = PLc+PLd = 3 ; Feb Other = PLc+PLe = 4
  assert.strictEqual(r.byMonth.Jan.Other, 3);
  assert.strictEqual(r.byMonth.Feb.Other, 4);
  assert.strictEqual(r.byMonth.Jan.PLa, 10);
  // months with no "Other" remainder still get a 0 so stacks line up
  const r2 = tri.triTopNProductLines({ Jan: { PLa: 1 } }, ['Jan'], 5);
  assert.deepStrictEqual(r2.lines, ['PLa']); // fewer than N, no Other bucket
});
```

- [ ] **Step 2: Run → FAIL**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: FAIL — `tri.triTopNProductLines is not a function`.

- [ ] **Step 3: Implement `triTopNProductLines` + export**

```js
function triTopNProductLines(ecnByMonth, months, n) {
    var totals = {};
    months.forEach(function (m) {
        var row = ecnByMonth[m] || {};
        Object.keys(row).forEach(function (pl) { totals[pl] = (totals[pl] || 0) + (row[pl] || 0); });
    });
    var ranked = Object.keys(totals).sort(function (a, b) { return totals[b] - totals[a]; });
    var top = ranked.slice(0, n);
    var hasOther = ranked.length > n;
    var lines = hasOther ? top.concat(['Other']) : top.slice();
    var byMonth = {};
    months.forEach(function (m) {
        var row = ecnByMonth[m] || {};
        var o = {};
        top.forEach(function (pl) { o[pl] = row[pl] || 0; });
        if (hasOther) {
            var other = 0;
            Object.keys(row).forEach(function (pl) { if (top.indexOf(pl) < 0) other += row[pl] || 0; });
            o.Other = other;
        }
        byMonth[m] = o;
    });
    return { lines: lines, byMonth: byMonth };
}
```

Add `triTopNProductLines: triTopNProductLines` to `module.exports`.

- [ ] **Step 4: Run → PASS**

Run: `node --test test/js/teamreport-inapp.test.js`
Expected: PASS.

- [ ] **Step 5: Implement the render wrapper `triEcnByPlHtml`**

```js
var TRI_PL_COLORS = ['#4a6fa5', '#1F8A4C', '#C7801B', '#7C3AED', '#B8342B', '#0F766E',
                     '#D97706', '#2c3e50', '#6B7280', '#15803D', '#9333EA', '#0E7490', '#A16207'];

function triEcnByPlHtml() {
    var d = TRI_STATE.data;
    var ecn = d.ecnByProductLine || {};
    var months = d.months || [];
    var picked = triTopNProductLines(ecn, months, 12);
    var lines = picked.lines;
    var yy = d.month.split('_')[1].substring(2);
    // max stacked-month total for scaling
    var maxTotal = 1;
    months.forEach(function (m) {
        var t = 0, row = picked.byMonth[m] || {};
        lines.forEach(function (pl) { t += row[pl] || 0; });
        if (t > maxTotal) maxTotal = t;
    });
    var colorOf = function (pl, i) { return pl === 'Other' ? '#CBD5E1' : TRI_PL_COLORS[i % TRI_PL_COLORS.length]; };
    var html = '<div style="display:grid; grid-template-columns:repeat(' + months.length + ', 1fr); gap:0; padding:18px 18px 10px; align-items:end;">';
    months.forEach(function (m) {
        var row = picked.byMonth[m] || {};
        var total = 0; lines.forEach(function (pl) { total += row[pl] || 0; });
        html += '<div style="display:flex; flex-direction:column; align-items:center; padding:8px 6px 6px;">';
        html += '<div style="position:relative; width:34px; height:170px; display:flex; flex-direction:column-reverse;">';
        lines.forEach(function (pl, i) {
            var v = row[pl] || 0;
            if (v <= 0) return;
            var h = (v / maxTotal) * 160;
            html += '<div title="' + triEsc(pl) + ': ' + v + '" style="width:34px; height:' + h + 'px; background:' + colorOf(pl, i) + ';"></div>';
        });
        html += '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6B7280; margin-top:4px;">' + triFmt(total) + '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-top:2px;">' + triEsc(m) + "'" + triEsc(yy) + '</div>';
        html += '</div>';
    });
    html += '</div>';
    // legend
    html += '<div style="display:flex; gap:10px 14px; flex-wrap:wrap; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; color:#6B7280;">';
    lines.forEach(function (pl, i) {
        html += '<span><i style="width:9px; height:9px; background:' + colorOf(pl, i) + '; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>' + triEsc(pl) + '</span>';
    });
    html += '</div>';
    return html;
}
```

- [ ] **Step 6: Wire a GroupCard into `triRender`**

Insert immediately after the GroupCard 2e (pie) and before `// GroupCard 3: AI analysis`:

```js
        // GroupCard 2f: ECN by product line (#7)
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">ECN processed by product line</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">by month &middot; <strong>top 12 + Other</strong></span>'
        + '    </div>'
        + '  </div>'
        + '  ' + triEcnByPlHtml()
        + '</div>'
```

- [ ] **Step 7: Parse check + full suite**

Run: `node -e "require('./src/main/resources/static/teamreport-inapp.js'); console.log('parse OK')"` → `parse OK`
Run: `node --test test/js/*.test.js` → all pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js
git commit -m "feat(team-report): in-app ECN-by-Product-Line stacked column, top-12+Other (#7)"
```

---

## Done criteria for Plan 2

- `node --test test/js/*.test.js` passes (Plan 1 tests + the two new transforms).
- Backend: `/api/team-report/data?month=May_2026` returns `activityTypes` (sane category counts close to the `Monthly Change Activities` pivot total) and `ecnByProductLine` (May led by `1037 - Client - PCIe`).
- In the running app, the Team Report tab shows two new cards — a change-activities donut + count table, and an ECN-by-product-line stacked column (top-12 + Other) — rendering with no console errors.
- Each task committed separately.

## End-to-end verification (after all three tasks)

Repeat the Plan 1 live-verification pattern against a running instance serving BOTH the rebuilt Java and the edited JS (a real `target/*.jar` run, or `mvn spring-boot:run` + static override): log in, open the Team Report tab, confirm the donut percentages sum to 100% and the PL stacks render with a readable ≤13-item legend; cross-check the donut total against the workbook's `Monthly Change Activities` pivot grand total. Screenshot both cards.
