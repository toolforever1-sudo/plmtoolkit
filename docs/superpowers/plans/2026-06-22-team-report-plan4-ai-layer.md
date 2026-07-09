# Team Report Plan 4 — Per-PCM Workload Analytics (#10) + AI Report Suggestions (#9)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add a **per-PCM workload analytics** layer (#10) — a deterministic ranked card (Top/Bottom 5, avg monthly workload, distribution %), plus an **AI executive summary + recommendations** over the PCM workload — and an **AI report-improvement suggestions** card (#9). The deterministic card is frontend-only from the existing `/data` payload; the two AI pieces are single Portkey calls that extend the existing `regenerate-ai` flow.

**Architecture:** The deterministic per-PCM stats are computed client-side (pure JS) from `/data`'s `ytd`/`changes`/`volume`. The AI pieces reuse the existing `PortkeyClient` + `analyzeTeam` pattern: `regenerate-ai` (which already runs the per-Program-Team analysis) gains two more single Portkey calls — `analyzePcmWorkload` and `analyzeReportSuggestions` — whose results are written into the analysis.json sidecar (new keys `pcmWorkload`, `reportSuggestions`) and surfaced by `/data`. The new per-PCM layer is **separate from** the existing per-Program-Team AI cards (a deliberate second grain). YoY is stubbed (needs the empty `byPcm` history from Plan 3).

**Tech Stack:** Java 11 + the existing `PortkeyClient` (Portkey gateway, locally enabled at `ai.vortex.sandisk.com`); vanilla ES5 JS; Node `node:test`.

---

## Background facts (verified 2026-06-22)

- **Portkey is locally enabled** (`portkey.enabled=true`, `portkey.base-url=https://ai.vortex.sandisk.com/...`); the existing May report's analysis.json has 7 real AI summaries → the AI pieces are verifiable locally.
- **`/data` already carries everything for the deterministic per-PCM stats**: `pcms`, `ytd` (per PCM `[items, xco, ecn]`), `changes`/`volume` (per PCM per month `[aml,eco,mco,ecn]`), `months`. No backend change for the deterministic card.
- **AI call pattern** (`TeamReportController.analyzeTeam`, ~line 1225-1283): guard `portkeyClient.isEnabled()`; `model = portkeyProvider + "/" + portkeyModel`; `String raw = portkeyClient.chat(model, system, user, 1500)`; `stripCodeFences(raw)`; `JSON.readValue(json, new TypeReference<Map<String,Object>>(){})`; 2-attempt retry with 2s pause; graceful fallback string on failure.
- **`regenerate-ai`** (line 475-559): builds `analysisGroups`, writes sidecar `{month, groups}` via `JSON.writerWithDefaultPrettyPrinter().writeValue(sidecar.toFile(), wrap)`, returns `{success, analysis, elapsedMs}`. It has `sr` (StoredReport) in scope — so it can call `extractTeamReportData(sr, month)` to get per-PCM numbers for the AI prompt.
- **`/data` analysis read** (`extractTeamReportData`, ~line 876-904): reads the sidecar's `groups` into `out.put("analysis", …)`. Add `pcmWorkload` / `reportSuggestions` reads here.
- **YoY** needs per-PCM prior-year data = Plan 3's `byPcm` history, which is empty → stub the YoY column.

---

## File structure

- **Modify:** `src/main/java/com/sandisk/plm/tracker/controller/TeamReportController.java` — two AI helper methods + extend `regenerate-ai` + extend the `/data` sidecar read.
- **Modify:** `src/main/resources/static/teamreport-inapp.js` — `triPcmWorkload` (pure), workload card render, AI exec-summary card, report-suggestions card; GroupCards.
- **Modify:** `test/js/teamreport-inapp.test.js` — `triPcmWorkload` test.

### New analysis.json / payload keys

```jsonc
// sidecar wrap + /data payload gain:
"pcmWorkload": { "summary": "exec prose…", "recommendations": "• balance…\n• plan…" },
"reportSuggestions": { "suggestions": "• add…\n• show…" }
```

---

## Task 1: Frontend — deterministic per-PCM workload card (#10 core)

**Files:** Modify `teamreport-inapp.js`, `test/js/teamreport-inapp.test.js`.

- [ ] **Step 1: Append the failing test**

```js
test('triPcmWorkload ranks PCMs by xCO with avg + distribution %', () => {
  const d = {
    months: ['Jan', 'Feb'],
    pcms: ['A', 'B'],
    ytd: { A: [1000, 80, 70], B: [400, 20, 15] }  // [items, xco, ecn]
  };
  const w = tri.triPcmWorkload(d);
  assert.strictEqual(w.grandXco, 100);
  assert.strictEqual(w.rows[0].pcm, 'A');        // ranked desc by xco
  assert.strictEqual(w.rows[0].xco, 80);
  assert.strictEqual(w.rows[0].avgMonthly, 40);  // 80 / 2 months
  assert.strictEqual(w.rows[0].pct, 80);         // 80 / 100
  assert.strictEqual(w.rows[1].pct, 20);
});

test('triPcmWorkload returns empty shape for missing data', () => {
  assert.deepStrictEqual(tri.triPcmWorkload(null), { rows: [], grandXco: 0, months: 1 });
  assert.deepStrictEqual(tri.triPcmWorkload({ pcms: ['A'] }), { rows: [], grandXco: 0, months: 1 });
});
```

- [ ] **Step 2: Run → FAIL** — `node --test test/js/teamreport-inapp.test.js` → `tri.triPcmWorkload is not a function`.

- [ ] **Step 3: Implement `triPcmWorkload` + export**

```js
function triPcmWorkload(d) {
    if (!d || !d.pcms || !d.ytd) return { rows: [], grandXco: 0, months: 1 };
    var months = (d.months && d.months.length) ? d.months.length : 1;
    var grandXco = 0;
    d.pcms.forEach(function (p) { var y = d.ytd[p]; if (y) grandXco += y[1] || 0; });
    var rows = d.pcms.map(function (p) {
        var y = d.ytd[p] || [0, 0, 0];
        var xco = y[1] || 0;
        return { pcm: p, items: y[0] || 0, xco: xco, ecn: y[2] || 0,
                 avgMonthly: Math.round(xco / months),
                 pct: grandXco ? Math.round((xco / grandXco) * 1000) / 10 : 0 };
    }).sort(function (a, b) { return b.xco - a.xco; });
    return { rows: rows, grandXco: grandXco, months: months };
}
```
Add `triPcmWorkload: triPcmWorkload` to `module.exports`.

- [ ] **Step 4: Run → PASS** — all pass.

- [ ] **Step 5: Implement `triPcmWorkloadHtml` (presentational)**

Renders a ranked table (highest→lowest xCO) with a Top-5 / Bottom-5 visual cue (bold first 5 rows; a faint divider before the bottom 5 if >10 PCMs). YoY column shows `—` (stub).

```js
function triPcmWorkloadHtml() {
    var d = TRI_STATE.data;
    var w = triPcmWorkload(d);
    if (!w.rows.length) return '<div style="padding:18px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#6B7280;">No PCM workload data.</div>';
    var n = w.rows.length;
    var html = '<table style="width:100%; border-collapse:collapse; font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;">';
    html += '<thead><tr>';
    ['#', 'PCM', 'Total xCO', 'Affected items', 'Avg / month', '% of total', 'YoY'].forEach(function (h, i) {
        var align = (i <= 1) ? 'left' : 'right';
        html += '<th style="text-align:' + align + '; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280; font-weight:500; padding:8px 14px; border-bottom:1px solid #E8E6DF; background:#FAFAF7;">' + triEsc(h) + '</th>';
    });
    html += '</tr></thead><tbody>';
    w.rows.forEach(function (r, i) {
        var isTop = i < 5, isBottom = n > 10 && i >= n - 5;
        var bg = (i % 2 === 0) ? '#fff' : '#FAFAF7';
        var weight = isTop ? '600' : '400';
        var rankColor = isTop ? '#1F8A4C' : (isBottom ? '#B8342B' : '#6B7280');
        html += '<tr style="background:' + bg + ';">'
              + '<td style="padding:8px 14px; font-size:12px; font-family:\'IBM Plex Mono\',Consolas,monospace; color:' + rankColor + '; border-bottom:1px solid #E8E6DF;">' + (i + 1) + '</td>'
              + '<td style="padding:8px 14px; font-size:12.5px; font-weight:' + weight + '; color:#0F1720; border-bottom:1px solid #E8E6DF;">' + triEsc(r.pcm) + '</td>'
              + '<td style="padding:8px 14px; font-size:12.5px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; font-weight:' + weight + '; border-bottom:1px solid #E8E6DF;">' + triFmt(r.xco) + '</td>'
              + '<td style="padding:8px 14px; font-size:12.5px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; border-bottom:1px solid #E8E6DF;">' + triFmt(r.items) + '</td>'
              + '<td style="padding:8px 14px; font-size:12.5px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; border-bottom:1px solid #E8E6DF;">' + triFmt(r.avgMonthly) + '</td>'
              + '<td style="padding:8px 14px; font-size:12.5px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; border-bottom:1px solid #E8E6DF;">' + r.pct + '%</td>'
              + '<td style="padding:8px 14px; font-size:11.5px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; color:#9CA3AF; border-bottom:1px solid #E8E6DF;" title="Year-over-year needs Noraida\'s per-PCM history file">&mdash;</td>'
              + '</tr>';
    });
    html += '</tbody></table>';
    html += '<div style="padding:8px 14px 12px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; color:#9CA3AF;">Green = top 5 by xCO &middot; red = bottom 5 &middot; YoY pending Noraida&rsquo;s per-PCM history file</div>';
    return html;
}
```

- [ ] **Step 6: Wire a GroupCard into `triRender`**

Insert immediately after the GroupCard 2h (#8 placeholder from Plan 3) and before `// GroupCard 3: AI analysis`:

```js
        // GroupCard 2i: per-PCM workload analytics (#10 deterministic)
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">PCM workload analytics</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">ranked by xCO &middot; <strong>avg / distribution</strong></span>'
        + '    </div>'
        + '  </div>'
        + '  ' + triPcmWorkloadHtml()
        + '</div>'
```

- [ ] **Step 7: Parse check + full suite + commit**

```bash
node -e "require('./src/main/resources/static/teamreport-inapp.js'); console.log('parse OK')"
node --test test/js/*.test.js
git add src/main/resources/static/teamreport-inapp.js test/js/teamreport-inapp.test.js
git commit -m "feat(team-report): in-app per-PCM workload analytics card (#10 deterministic)"
```

---

## Task 2: Backend — AI per-PCM workload summary + report suggestions

**Files:** Modify `TeamReportController.java`.

Two new Portkey helpers (mirroring `analyzeTeam`'s isEnabled-guard + 2-attempt pattern), called from `regenerate-ai`, persisted to the sidecar and surfaced by `/data`. This task is verified by the orchestrator via live curl (Portkey on locally).

- [ ] **Step 1: Add `analyzePcmWorkload(...)`**

Add after `analyzeTeam(...)` (after line ~1284). It takes the per-PCM data from `extractTeamReportData` and returns `{summary, recommendations}`.

```java
/** AI exec summary + recommendations over per-PCM workload (one Portkey call).
 *  data is the map from extractTeamReportData (has "pcms" + "ytd"[items,xco,ecn]). */
@SuppressWarnings("unchecked")
private Map<String, Object> analyzePcmWorkload(Map<String, Object> data, String month) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("summary", ""); out.put("recommendations", "");
    if (!portkeyClient.isEnabled()) { out.put("summary", "(AI unavailable: portkey.enabled=false)"); return out; }

    List<String> pcms = (List<String>) data.getOrDefault("pcms", new ArrayList<>());
    Map<String, Object> ytd = (Map<String, Object>) data.getOrDefault("ytd", new LinkedHashMap<>());
    if (pcms.isEmpty()) return out;

    // build a compact stat block: pcm, xco, items, ecn, % of total
    long grand = 0;
    for (String p : pcms) { List<Number> y = (List<Number>) ytd.get(p); if (y != null && y.size() > 1) grand += y.get(1).longValue(); }
    StringBuilder stats = new StringBuilder();
    for (String p : pcms) {
        List<Number> y = (List<Number>) ytd.get(p);
        long items = (y != null && y.size() > 0) ? y.get(0).longValue() : 0;
        long xco = (y != null && y.size() > 1) ? y.get(1).longValue() : 0;
        long ecn = (y != null && y.size() > 2) ? y.get(2).longValue() : 0;
        int pct = grand > 0 ? (int) Math.round(100.0 * xco / grand) : 0;
        stats.append("  ").append(p).append(": xCO ").append(xco).append(", affected items ")
             .append(items).append(", ECN ").append(ecn).append(", ").append(pct).append("% of total\n");
    }
    String system =
        "You are an operations analyst summarising PCM (change-manager) workload for SanDisk PLM. "
        + "Given per-PCM volume stats for the month, return ONLY a JSON object with two string keys: "
        + "\"summary\" (2-4 sentences of executive prose: who carries the load, imbalance, notable concentration) and "
        + "\"recommendations\" (3-5 newline-separated bullets covering workload balancing, resource planning, "
        + "efficiency, and risk areas). Be concrete and cite the PCM names/percentages.";
    String user = "Month: " + month + "\nPer-PCM workload (ranked input):\n" + stats;
    String model = portkeyProvider + "/" + portkeyModel;
    for (int attempt = 1; attempt <= 2; attempt++) {
        try {
            Map<String, Object> parsed = JSON.readValue(stripCodeFences(portkeyClient.chat(model, system, user, 1200)),
                    new TypeReference<Map<String, Object>>(){});
            out.put("summary", String.valueOf(parsed.getOrDefault("summary", "")));
            out.put("recommendations", String.valueOf(parsed.getOrDefault("recommendations", "")));
            return out;
        } catch (Exception e) {
            if (attempt < 2) { try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
            else LOG.warning("[TEAM-REPORT] PCM workload AI failed: " + e.getMessage());
        }
    }
    out.put("summary", "(AI temporarily unavailable — re-run the report)");
    return out;
}
```

- [ ] **Step 2: Add `analyzeReportSuggestions(...)`**

```java
/** AI suggestions to improve the report itself (one Portkey call). */
@SuppressWarnings("unchecked")
private Map<String, Object> analyzeReportSuggestions(Map<String, Object> data, List<Map<String, Object>> groups, String month) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("suggestions", "");
    if (!portkeyClient.isEnabled()) { out.put("suggestions", "(AI unavailable: portkey.enabled=false)"); return out; }
    List<String> pcms = (List<String>) data.getOrDefault("pcms", new ArrayList<>());
    String system =
        "You are a reporting/analytics advisor. The SanDisk PLM monthly Team Report already shows: per-PCM "
        + "processing tables, volume-by-PCM and by-month charts, a change-activities pie, ECN-by-product-line, "
        + "a yearly trend, and per-Program-Team AI analysis. Suggest concrete improvements to the report's "
        + "insights and usability. Return ONLY a JSON object with one string key \"suggestions\" = 3-5 "
        + "newline-separated bullets. Be specific and actionable; avoid generic advice.";
    String user = "Month: " + month + "\nPCM count: " + pcms.size() + "\nProgram-team analyses: " + (groups == null ? 0 : groups.size());
    String model = portkeyProvider + "/" + portkeyModel;
    for (int attempt = 1; attempt <= 2; attempt++) {
        try {
            Map<String, Object> parsed = JSON.readValue(stripCodeFences(portkeyClient.chat(model, system, user, 900)),
                    new TypeReference<Map<String, Object>>(){});
            out.put("suggestions", String.valueOf(parsed.getOrDefault("suggestions", "")));
            return out;
        } catch (Exception e) {
            if (attempt < 2) { try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
            else LOG.warning("[TEAM-REPORT] report-suggestions AI failed: " + e.getMessage());
        }
    }
    out.put("suggestions", "(AI temporarily unavailable — re-run the report)");
    return out;
}
```

- [ ] **Step 3: Call both in `regenerate-ai` + persist + return**

In `regenerateAi`, after `analysisGroups` is built (after line 504) and BEFORE building the sidecar `wrap`, add:
```java
            Map<String, Object> pcmData = extractTeamReportData(sr, month);
            Map<String, Object> pcmWorkload = analyzePcmWorkload(pcmData, month);
            Map<String, Object> reportSuggestions = analyzeReportSuggestions(pcmData, analysisGroups, month);
```
Then in the `wrap` map (after `wrap.put("groups", analysisGroups);`, line 520) add:
```java
            wrap.put("pcmWorkload", pcmWorkload);
            wrap.put("reportSuggestions", reportSuggestions);
```
And in the response map (after `resp.put("analysis", analysisForUi);`, line 551) add:
```java
            resp.put("pcmWorkload", pcmWorkload);
            resp.put("reportSuggestions", reportSuggestions);
```

- [ ] **Step 4: Surface in `/data` (extractTeamReportData sidecar read)**

In `extractTeamReportData`, in the analysis-sidecar block (~line 877-903) where it reads the sidecar JSON `aj` and builds `analysis`, after `out.put("analysis", analysis);` (line 904) — but still using the already-parsed `aj` map — add reads for the two new sections. The current block parses `aj` only inside the `if (Files.exists(sidecar))`. Extend it: after extracting `groups`, also pull `pcmWorkload`/`reportSuggestions` from `aj` and put them on `out`. Concretely, inside the existing `try` that does `Map<String,Object> aj = JSON.readValue(...)`, add before its closing brace:
```java
                    Object pw = aj.get("pcmWorkload");
                    if (pw != null) out.put("pcmWorkload", pw);
                    Object rs = aj.get("reportSuggestions");
                    if (rs != null) out.put("reportSuggestions", rs);
```
(If `aj` is parsed in a scope that ends before `out.put("analysis", …)`, place these two puts right after `analysis.add(entry)` loop, still within the `if (groups != null)` / sidecar block where `aj` is in scope.)

- [ ] **Step 5: Compile**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q compile 2>&1 | tail -25
```
Expected: BUILD SUCCESS. Fix any error. Do NOT run the app (orchestrator does the live Portkey curl).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/TeamReportController.java
git commit -m "feat(team-report): AI per-PCM workload summary + report suggestions on regenerate-ai (#9,#10)"
```

---

## Task 3: Frontend — render AI exec-summary + recommendations (#10) + report suggestions (#9)

**Files:** Modify `teamreport-inapp.js`.

The viewer already auto-fires `regenerate-ai` when the sidecar lacks analysis, and stores the response. `/data` now also returns `pcmWorkload` / `reportSuggestions`. After `triRegenerateAi` succeeds, also splice these in (the existing handler sets `TRI_STATE.data.analysis = d.analysis` — extend it).

- [ ] **Step 1: Splice the new AI sections from the regenerate-ai response**

Find the `triRegenerateAi` success handler (where it does `TRI_STATE.data.analysis = d.analysis;`). Add right after:
```js
                if (d.pcmWorkload) TRI_STATE.data.pcmWorkload = d.pcmWorkload;
                if (d.reportSuggestions) TRI_STATE.data.reportSuggestions = d.reportSuggestions;
```

- [ ] **Step 2: Implement the two render helpers**

```js
function triBulletsHtml(text) {
    if (!text) return '';
    var lines = String(text).split('\n').filter(function (l) { return l.trim(); });
    var html = '<ul style="margin:6px 0 0; padding-left:18px;">';
    lines.forEach(function (l) {
        html += '<li style="font-size:12.5px; color:#0F1720; line-height:1.5; margin-bottom:3px;">' + triEsc(l.replace(/^\s*[•\*\-]\s*/, '')) + '</li>';
    });
    return html + '</ul>';
}

function triPcmWorkloadAiHtml() {
    var pw = TRI_STATE.data && TRI_STATE.data.pcmWorkload;
    if (!pw || (!pw.summary && !pw.recommendations)) return '';
    var html = '<div style="padding:14px 16px;">';
    if (pw.summary) html += '<div style="font-size:13px; color:#0F1720; line-height:1.55;">' + triEsc(pw.summary) + '</div>';
    if (pw.recommendations) {
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280; margin-top:12px;">Recommendations</div>';
        html += triBulletsHtml(pw.recommendations);
    }
    return html + '</div>';
}

function triReportSuggestionsHtml() {
    var rs = TRI_STATE.data && TRI_STATE.data.reportSuggestions;
    if (!rs || !rs.suggestions) return '';
    return '<div style="padding:14px 16px;">' + triBulletsHtml(rs.suggestions) + '</div>';
}
```

- [ ] **Step 3: Wire two GroupCards into `triRender`**

Insert immediately after GroupCard 2i (workload table, Task 1) and before `// GroupCard 3: AI analysis` — each guarded so it only shows when its data exists:

```js
        + (TRI_STATE.data.pcmWorkload && (TRI_STATE.data.pcmWorkload.summary || TRI_STATE.data.pcmWorkload.recommendations) ?
          ('<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
          + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
          + '    <div><span style="font-size:13px; font-weight:600; color:#0F1720;">PCM workload &mdash; AI summary</span>'
          + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">exec summary + recommendations &middot; <strong>Claude</strong></span></div>'
          + '  </div>'
          + '  ' + triPcmWorkloadAiHtml()
          + '</div>') : '')
        + (TRI_STATE.data.reportSuggestions && TRI_STATE.data.reportSuggestions.suggestions ?
          ('<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
          + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
          + '    <div><span style="font-size:13px; font-weight:600; color:#0F1720;">AI suggestions to improve this report</span>'
          + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">#9 &middot; <strong>Claude</strong></span></div>'
          + '  </div>'
          + '  ' + triReportSuggestionsHtml()
          + '</div>') : '')
```

- [ ] **Step 4: Parse check + full suite + commit**

```bash
node -e "require('./src/main/resources/static/teamreport-inapp.js'); console.log('parse OK')"
node --test test/js/*.test.js
git add src/main/resources/static/teamreport-inapp.js
git commit -m "feat(team-report): render AI PCM-workload summary + report suggestions cards (#9,#10)"
```

---

## Done criteria

- `node --test test/js/*.test.js` passes (incl. `triPcmWorkload`).
- The Team Report tab shows a **PCM workload analytics** ranked table (top-5 green / bottom-5 red, avg/month, % of total, YoY `—`).
- After AI regen, a **PCM workload — AI summary** card (exec summary + recommendations) and an **AI suggestions to improve this report** card appear.
- `/data` carries `pcmWorkload` / `reportSuggestions` when the sidecar has them; `regenerate-ai` returns them.

## End-to-end verification (orchestrator)

Build a JAR from the branch, run from the local data dir (Portkey enabled), log in. (1) Confirm the deterministic workload table renders from `/data` (no AI needed). (2) `POST /api/team-report/May_2026/regenerate-ai` and confirm the JSON response now includes non-empty `pcmWorkload.summary`/`recommendations` and `reportSuggestions.suggestions` (real Claude output via the vortex gateway). (3) Reload the tab and screenshot the workload table + the two AI cards. If Portkey is unreachable, the AI cards degrade to the "(AI temporarily unavailable…)" / hidden state — note that rather than failing the deterministic card.
