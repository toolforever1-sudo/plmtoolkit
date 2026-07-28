/*
 * Team Report in-app view (Noraida's three asks: PCM processing, monthly
 * affected items, AI analysis) — sits as a 5th sub-tab under ECN Report.
 *
 * Port of the React mock at
 *   team-report-handoff/ui_kits/plm-toolkit/index.html
 * to vanilla JS, matching the toolkit's existing tab patterns
 * (volumereport.js / returnstracker.js).
 *
 * Data: GET /api/team-report/months    → ["May_2026", "Mar_2026", …]
 *       GET /api/team-report/data?month=… → the shape report-data.js pins
 *
 * Export menu:
 *   - Excel: reuses the existing /api/team-report/recent download path
 *   - PowerPoint: POST /api/team-report/{month}/pptx (Phase 3, soon)
 *   - Discrepancy: reuses existing pipeline
 *
 * "Generate Team Report →" reuses the existing drawer (teamReportOpenDrawer)
 * defined in volumereport.js.
 */

var TRI_STATE = {
    data: null,
    month: null,
    view: 'This month',  // 'This month' | 'YTD' | 'Q1'..'Q4'
    months: [],
    loading: false,
    aiAutoTriggered: {}, // month → true once we've kicked off an auto-regen,
                        // so navigating away and back doesn't re-fire
    aiGenerating: false,
    history: null
};

function triLoadHistory() {
    if (TRI_STATE.history) { return; }
    fetch('/api/team-report/history', { credentials: 'same-origin' })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (h) { if (h) { TRI_STATE.history = h; if (TRI_STATE.data) triRender(); } })
        .catch(function () { /* yearly chart stays hidden on failure */ });
}

function teamReportInAppInit() {
    var container = document.getElementById('teamReportInAppView');
    if (!container) return;
    container.style.display = '';
    triLoadHistory();
    // Probe which months have a report on disk. Default to the latest.
    fetch('/api/team-report/months', { credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) {
            // Chronological, newest first. A plain sort() is lexicographic and
            // ranks "May_2026" above "Jun_2026" — the tab would stay stuck on
            // May even after June is generated.
            TRI_STATE.months = (d && d.months) ? d.months.slice().sort(function (a, b) {
                return triMonthOrdinal(b) - triMonthOrdinal(a);
            }) : [];
            var latest = TRI_STATE.months.length ? TRI_STATE.months[0] : null;
            triRenderEmptyOrLoad(latest);
        })
        .catch(function (e) {
            triRenderError('Failed to list months: ' + e.message);
        });
}

function triRenderEmptyOrLoad(month) {
    if (!month) {
        triRenderEmpty();
        return;
    }
    triLoadMonth(month);
}

function triLoadMonth(month) {
    TRI_STATE.month = month;
    TRI_STATE.loading = true;
    triRenderSkeleton();
    fetch('/api/team-report/data?month=' + encodeURIComponent(month), { credentials: 'same-origin' })
        .then(function (r) {
            if (!r.ok) return r.json().then(function (j) { throw new Error(j.error || ('HTTP ' + r.status)); });
            return r.json();
        })
        .then(function (d) {
            TRI_STATE.data = d;
            TRI_STATE.loading = false;
            triRender();
            // Auto-fire AI regen when the workbook data is on disk but the
            // analysis JSON sidecar isn't. Vikas 2026-06-11: "have the
            // toolkit just automatically invoke AI when the user clicks the
            // tab instead of manually". Guarded against double-fire so a
            // tab-flip during the call doesn't burn extra Portkey tokens.
            // Also re-fire when a stale (pre-Plan-4) sidecar carries the
            // per-Program-Team groups but lacks the newer per-PCM workload AI /
            // report-suggestions — so older cached analyses self-upgrade on the
            // next tab open instead of silently hiding the new AI cards.
            if ((!d.analysis || d.analysis.length === 0 || !d.pcmWorkload)
                    && !TRI_STATE.aiAutoTriggered[month]
                    && !TRI_STATE.aiGenerating) {
                TRI_STATE.aiAutoTriggered[month] = true;
                triRegenerateAi(/*manual=*/false);
            }
        })
        .catch(function (e) {
            TRI_STATE.loading = false;
            triRenderError(e.message);
        });
}

function triFmt(n) {
    if (n == null || isNaN(n)) return '—';
    return Number(n).toLocaleString('en-US');
}

var TRI_FULL_MONTH = {
    Jan: 'January', Feb: 'February', Mar: 'March', Apr: 'April', May: 'May', Jun: 'June',
    Jul: 'July', Aug: 'August', Sep: 'September', Oct: 'October', Nov: 'November', Dec: 'December'
};

var TRI_MONTH_SEQ = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                     'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Jun_2026" → comparable ordinal (year*12 + monthIdx); -1 when unparseable.
 *  Plain sort() on MMM_YYYY strings is lexicographic ("Jun" < "May"!) — always
 *  order months through this. */
function triMonthOrdinal(m) {
    if (!m || String(m).indexOf('_') < 0) return -1;
    var p = String(m).split('_');
    var idx = TRI_MONTH_SEQ.indexOf(p[0]);
    if (idx < 0 || !/^\d{4}$/.test(p[1])) return -1;
    return parseInt(p[1], 10) * 12 + idx;
}

/** Date → MMM_YYYY of the last complete calendar month (Jan 15 → Dec of prior year). */
function triLastCompleteMonth(now) {
    var y = now.getFullYear();
    var mi = now.getMonth() - 1;
    if (mi < 0) { mi = 11; y -= 1; }
    return TRI_MONTH_SEQ[mi] + '_' + y;
}

function triShortMonthOf(month) {
    if (!month || month.indexOf('_') < 0) return month || '';
    return month.split('_')[0];
}

function triEsc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}

// === Aggregates ===========================================================

// NOTE: iterates TRI_STATE.data.pcms (not derived from `src`). Callers that pass a
// dataset `d` must ensure TRI_STATE.data === d, else pcms and rows can mismatch.
function triMonthTotals(src, m) {
    // returns [AML, ECO, MCO, ECN] summed across PCMs for month m
    var d = TRI_STATE.data;
    var t = [0, 0, 0, 0];
    if (!d || !d.pcms) return t;
    d.pcms.forEach(function (p) {
        var row = src[p] && src[p][m];
        if (!row) return;
        for (var i = 0; i < 4; i++) t[i] += row[i] || 0;
    });
    return t;
}

// Quarter helpers (Plan 1). Pure — unit-tested in test/js/teamreport-inapp.test.js.
var TRI_QUARTER_MONTHS = {
    Q1: ['Jan', 'Feb', 'Mar'],
    Q2: ['Apr', 'May', 'Jun'],
    Q3: ['Jul', 'Aug', 'Sep'],
    Q4: ['Oct', 'Nov', 'Dec']
};

// Returns 'Q1'..'Q4' for a short month name (e.g. 'Jan'), or null if unrecognised.
function triQuarterOf(shortMonth) {
    for (var q in TRI_QUARTER_MONTHS) {
        if (!TRI_QUARTER_MONTHS.hasOwnProperty(q)) continue;
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

function triPcmVolumeSeries(d) {
    return d.pcms.map(function (p) {
        var c = triPcmTotalsOverMonths(d.changes, p, d.months);
        var v = triPcmTotalsOverMonths(d.volume, p, d.months);
        return { pcm: p, xco: c[0] + c[1] + c[2], ecn: c[3], items: v[0] + v[1] + v[2] };
    });
}

function triMonthVolumeSeries(d) {
    return d.months.map(function (m) {
        var c = triMonthTotals(d.changes, m);
        var v = triMonthTotals(d.volume, m);
        return { month: m, xco: c[0] + c[1] + c[2], ecn: c[3], items: v[0] + v[1] + v[2] };
    });
}

function triChangeTypeMonthSeries(d) {
    return d.months.map(function (m) {
        var c = triMonthTotals(d.changes, m); // [AML, ECO, MCO, ECN]
        var v = triMonthTotals(d.volume, m);
        return { month: m, aml: c[0], eco: c[1], mco: c[2], items: v[0] + v[1] + v[2] };
    });
}

function triSum(a) { return a.reduce(function (x, y) { return x + (y || 0); }, 0); }

// === Render ==============================================================

function triContainer() { return document.getElementById('teamReportInAppView'); }

function triRenderEmpty() {
    var html = '<div style="padding:48px 20px; text-align:center; color:#6B7280;">'
        + '<h2 style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:22px; color:#0F1720; margin:0 0 10px;">Team Report</h2>'
        + '<p style="font-size:13.5px; max-width:520px; margin:0 auto 18px; line-height:1.5;">'
        + 'No Team Report has been generated yet. Use the existing <strong>Generate Team Report →</strong> flow '
        + 'under Volume Reports to roll the latest monthly report; it will appear here once it lands on disk.</p>'
        + '<button class="tool-btn" onclick="triGenerateClick()" style="font-weight:600;">Generate Team Report →</button>'
        + '</div>';
    triContainer().innerHTML = html;
}

function triRenderError(msg) {
    triContainer().innerHTML =
        '<div style="padding:40px 20px; text-align:center;">'
        + '<div style="background:#fdeaea; border-left:4px solid #B8342B; padding:12px 18px; border-radius:0 6px 6px 0; max-width:560px; margin:0 auto; text-align:left;">'
        + '<strong style="color:#B8342B;">Couldn\'t load the Team Report.</strong><br>'
        + '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px; color:#6B7280;">' + triEsc(msg) + '</span>'
        + '</div></div>';
}

function triRenderSkeleton() {
    triContainer().innerHTML =
        '<div style="padding:48px 20px; text-align:center; color:#6B7280; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px;">'
        + 'Loading Team Report…</div>';
}

function triRender() {
    var d = TRI_STATE.data;
    if (!d) { triRenderEmpty(); return; }
    var month = triShortMonthOf(TRI_STATE.month);
    var monthFull = TRI_FULL_MONTH[month] || month;

    // Page-level KPI computations
    var ct = triMonthTotals(d.changes, month); // [AML, ECO, MCO, ECN]
    var vt = triMonthTotals(d.volume, month);
    var xco = ct[0] + ct[1] + ct[2];
    var items = vt[0] + vt[1] + vt[2];
    var mi = d.months.indexOf(month);
    var prev = mi > 0 ? d.months[mi - 1] : null;
    var pct = null;
    if (prev) {
        var pv = triMonthTotals(d.volume, prev);
        var p = pv[0] + pv[1] + pv[2];
        pct = p > 0 ? ((items - p) / p) * 100 : null;
    }
    var topPcm = d.pcms.map(function (p) {
        var c = d.changes[p][month] || [0, 0, 0, 0];
        return [p, c[0] + c[1] + c[2]];
    }).sort(function (a, b) { return b[1] - a[1]; })[0];

    var html = ''
        + '<div style="padding:0 16px 24px;">'
        + triStaleBannerHtml()
        // Page head: title + month switcher + Export + Generate
        + '<div style="display:flex; justify-content:space-between; align-items:flex-end; gap:16px; flex-wrap:wrap; padding:14px 0 12px;">'
        + '  <div>'
        + '    <h2 style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:22px; font-weight:500; letter-spacing:-0.005em; margin:0;">'
        +        'Team Report &mdash; ' + triEsc(monthFull) + ' ' + triEsc(d.month.split('_')[1])
        +        (d.manager && d.manager.managerDisplayName
                    ? '<span style="font-family:\'IBM Plex Sans\',\'Segoe UI\',sans-serif; font-size:12px; font-weight:600; background:#e8f0fe; color:#1a3a5c; border-radius:14px; padding:3px 12px; margin-left:10px; vertical-align:3px;">'
                      + triEsc(d.manager.managerDisplayName) + '&rsquo;s team</span>'
                    : '')
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; letter-spacing:0.08em; background:#e8f0fe; color:#1a3a5c; border-radius:3px; padding:1px 5px; margin-left:6px;">NEW</span>'
        + '    </h2>'
        + '    <div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; color:#6B7280; margin-top:3px;">'
        +        triEsc(d.generated || '')
        +        (d.manager && d.manager.managerUsername
                    ? ' &middot; manager ' + triEsc(d.manager.managerUsername) : '')
        + '    </div>'
        + '  </div>'
        + '  <div style="display:flex; gap:8px; align-items:center; position:relative;">'
        + '    ' + triMonthSwitcherHtml(d.months, month)
        + '    <button class="tool-btn" id="triExportBtn" onclick="triToggleExportMenu()">Export &#9662;</button>'
        + '    <div id="triExportMenu" style="display:none; position:absolute; right:140px; top:100%; min-width:340px; background:#fff; border:1px solid #E8E6DF; border-radius:8px; box-shadow:0 4px 12px rgba(0,0,0,0.12); z-index:60; overflow:hidden; margin-top:4px;">'
        +        triExportMenuItemsHtml()
        + '    </div>'
        + '    <button class="tool-btn tool-btn-primary" onclick="triGenerateClick()" style="font-weight:600;">Generate Team Report &rarr;</button>'
        + '  </div>'
        + '</div>'

        // 4-stat strip
        + '<div style="display:grid; grid-template-columns:repeat(4, 1fr); gap:0; background:#E8E6DF; border:1px solid #E8E6DF; border-radius:8px; overflow:hidden;">'
        +    triStatTile('Total xCO processed', triFmt(xco), 'AML ' + ct[0] + ' &middot; ECO ' + ct[1] + ' &middot; MCO ' + ct[2])
        +    triStatTile('Affected items', triFmt(items),
                pct == null ? '&mdash;' : (Math.abs(pct).toFixed(1) + '% vs ' + prev),
                pct == null ? '' : (pct >= 0 ? 'up' : 'down'))
        +    triStatTile('Total ECN', triFmt(ct[3]), 'unique change notices')
        +    triStatTile('Top PCM', topPcm[0], topPcm[1] + ' xCOs this month', '', /*smallValue=*/true)
        + '</div>'

        // GroupCard 1: PCM processing table
        + '<div style="margin-top:18px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">Total processing by PCM</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">'
        +          triPcmTableSubLabel(month, monthFull)
        + '      </span>'
        + '    </div>'
        + '    <div>'
        +        triSegmentedHtml('triView',
                    ['This month', 'Jan–' + month + ' YTD'].concat(triQuartersInData(d.months)).concat(['Year']),
                    TRI_STATE.view === 'This month' ? 'This month'
                        : (TRI_STATE.view === 'YTD' ? 'Jan–' + month + ' YTD' : TRI_STATE.view))
        + '    </div>'
        + '  </div>'
        + '  <div id="triPcmTableHost">' + triPcmTableHtml(month) + '</div>'
        + '</div>'

        // GroupCard 2: monthly bar chart
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">Total affected items by month</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">by change type &middot; <strong>ECO / MCO / AML</strong></span>'
        + '    </div>'
        + '    <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; background:#F0EFE9; padding:3px 9px; border-radius:999px;">ECN ' + triFmt(ct[3]) + ' in ' + month + '</span>'
        + '  </div>'
        + '  ' + triVolumeChartHtml(month)
        + '</div>'

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

        + (TRI_STATE.history && triYearSeries(TRI_STATE.history).length ?
          (
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

        // GroupCard 2h: ECN by product line by year (#8) — placeholder until Noraida's file
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">ECN by product line, by year</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">2014&ndash;2026</span>'
        + '    </div>'
        + '  </div>'
        + '  ' + (triPlYearSeries(TRI_STATE.history, 12).years.length
                  ? triPlByYearHtml()
                  : '<div style="padding:18px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#6B7280;">Awaiting historical product-line data.</div>')
        + '</div>'

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

        // GroupCard 3: AI analysis
        + '<div style="margin-top:14px; border:1px solid #E8E6DF; border-radius:8px; background:#fff;">'
        + '  <div style="display:flex; align-items:center; justify-content:space-between; padding:10px 14px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '    <div>'
        + '      <span style="font-size:13px; font-weight:600; color:#0F1720;">AI analysis &mdash; ' + triEsc(monthFull) + ' ' + triEsc(d.month.split('_')[1]) + '</span>'
        + '      <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-left:10px;">per Program Team &middot; <strong>Claude</strong> via Portkey</span>'
        + '    </div>'
        + '    <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; background:#F0EFE9; padding:3px 9px; border-radius:999px;">'
        +        (d.analysis && d.analysis.length
                    ? '<span style="display:inline-block; width:6px; height:6px; background:#1F8A4C; border-radius:50%; margin-right:5px; vertical-align:1px;"></span>'
                      + triFmt(d.analysis.reduce(function (a, t) { return a + (t.count || 0); }, 0)) + ' rows analyzed'
                    : 'AI Analysis not yet generated for this month')
        + '    </span>'
        + '  </div>'
        + '  ' + triAiGridHtml()
        + '</div>'

        // Bottom CTA bar — mirrors the top-right Generate button so users
        // who are deep in the AI grid don't have to scroll back up. Vikas
        // 2026-06-11.
        + '<div style="display:flex; justify-content:center; align-items:center; gap:14px; padding:22px 0 14px; margin-top:18px; border-top:1px solid #E8E6DF;">'
        + '  <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; color:#6B7280; letter-spacing:0.04em;">NEXT MONTH&apos;S ROLLOVER</span>'
        + '  <button class="tool-btn tool-btn-primary" onclick="triGenerateClick()" style="font-weight:600; padding:9px 22px; font-size:13.5px;">Generate Team Report &rarr;</button>'
        + '  <button class="tool-btn" onclick="triToggleExportMenuBottom()" style="font-weight:500; padding:9px 16px; font-size:13.5px;">Export &#9662;</button>'
        + '  <div id="triExportMenuBottom" style="display:none; position:absolute; right:50%; transform:translateX(140px); min-width:340px; background:#fff; border:1px solid #E8E6DF; border-radius:8px; box-shadow:0 4px 12px rgba(0,0,0,0.12); z-index:60; margin-top:46px; overflow:hidden;">'
        +       triExportMenuItemsHtml()
        + '  </div>'
        + '</div>'

        + '<div style="text-align:center; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#9CA3AF; padding:0 0 4px;">'
        +    'All figures from ' + triEsc(d.generated.split(' · ').pop() || '') + ' &middot; Excel output unchanged &middot; PowerPoint export uses the SanDisk corporate template'
        + '</div>'

        + '</div>';

    triContainer().innerHTML = html;
}

function triMonthSwitcherHtml(months, sel) {
    var html = '<div style="display:inline-flex; background:#F0EFE9; border-radius:6px; padding:2px; border:1px solid #E8E6DF;">';
    months.forEach(function (m) {
        var active = m === sel;
        html += '<button onclick="triSelectMonth(\'' + m + '\')" '
              + 'style="border:0; padding:5px 11px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; '
              + 'background:' + (active ? '#fff' : 'transparent') + '; color:' + (active ? '#0F1720' : '#6B7280') + '; '
              + 'font-weight:' + (active ? '600' : '400') + '; border-radius:4px; cursor:pointer;'
              + (active ? ' box-shadow:0 1px 2px rgba(0,0,0,0.06);' : '') + '">' + triEsc(m) + '</button>';
    });
    html += '</div>';
    return html;
}

function triStatTile(label, value, trend, dir, smallValue) {
    var arrow = dir === 'up' ? '▲ ' : dir === 'down' ? '▼ ' : '';
    var arrowColor = dir === 'up' ? '#1F8A4C' : dir === 'down' ? '#B8342B' : '#6B7280';
    return ''
        + '<div style="background:#fff; padding:14px 16px;">'
        +   '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280; margin-bottom:4px;">' + triEsc(label) + '</div>'
        +   '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:' + (smallValue ? '20px' : '28px') + '; font-weight:500; color:#0F1720; line-height:1.1; font-variant-numeric:tabular-nums;">' + triEsc(value) + '</div>'
        +   '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-top:5px;">'
        +     '<span style="color:' + arrowColor + ';">' + arrow + '</span>' + trend
        +   '</div>'
        + '</div>';
}

function triSegmentedHtml(stateKey, options, sel) {
    var html = '<div style="display:inline-flex; background:#F0EFE9; border-radius:6px; padding:2px; border:1px solid #E8E6DF;">';
    options.forEach(function (opt) {
        var active = opt === sel;
        html += '<button onclick="triSelectView(\'' + opt.replace(/'/g, "\\'") + '\')" '
              + 'style="border:0; padding:5px 11px; font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif; font-size:11.5px; '
              + 'background:' + (active ? '#fff' : 'transparent') + '; color:' + (active ? '#0F1720' : '#6B7280') + '; '
              + 'font-weight:' + (active ? '600' : '400') + '; border-radius:4px; cursor:pointer;'
              + (active ? ' box-shadow:0 1px 2px rgba(0,0,0,0.06);' : '') + '">' + triEsc(opt) + '</button>';
    });
    html += '</div>';
    return html;
}

function triPcmTableSubLabel(month, monthFull) {
    var d = TRI_STATE.data;
    var yr = d.month.split('_')[1];
    if (TRI_STATE.view === 'This month') {
        return '<strong>' + triEsc(monthFull) + '</strong> ' + yr + ' &middot; ' + d.pcms.length + ' active PCMs';
    }
    if (TRI_STATE.view === 'Q1' || TRI_STATE.view === 'Q2' || TRI_STATE.view === 'Q3' || TRI_STATE.view === 'Q4') {
        var qm = triMonthsInQuarter(TRI_STATE.view, d.months);
        var span = qm.length ? (triEsc(qm[0]) + '&ndash;' + triEsc(qm[qm.length - 1])) : triEsc(TRI_STATE.view);
        return '<strong>' + triEsc(TRI_STATE.view) + '</strong> ' + span + ' ' + yr;
    }
    if (TRI_STATE.view === 'Year') {
        var yrows = triPcmYearRows(TRI_STATE.history);
        return yrows.years.length
            ? ('<strong>By year</strong> ' + triEsc(yrows.years[0]) + '&ndash;' + triEsc(yrows.years[yrows.years.length - 1]) + ' &middot; latest is YTD')
            : '<strong>By year</strong>';
    }
    return '<strong>Jan&ndash;' + month + '</strong> ' + yr + ' cumulative';
}

function triPcmTableHtml(month) {
    var d = TRI_STATE.data;
    var headers = ['PCM', 'AML', 'ECO', 'MCO', 'Total xCO', 'ECN', 'Affected items'];
    var mapRow = function (r) { return [r.pcm, r.aml, r.eco, r.mco, r.xco, r.ecn, triFmt(r.items)]; };
    var totalOpt = { isTotal: function (i, row) { return row && row[0] === 'All PCMs'; } };

    if (TRI_STATE.view === 'Year') {
        var yr = triPcmYearRows(TRI_STATE.history);
        if (!yr.rows.length) {
            return '<div style="padding:18px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#6B7280;">Per-PCM yearly history not loaded.</div>';
        }
        var yhdr = ['PCM'].concat(yr.years);
        var yrows = yr.rows.map(function (r) {
            return [r.pcm].concat(r.cells.map(function (v) { return v == null ? '—' : triFmt(v); }));
        });
        return triDataTableHtml(yhdr, yrows, {})
             + '<div style="padding:6px 14px 12px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; color:#9CA3AF;">Total xCO per PCM per year &middot; latest year is YTD &middot; blank = PCM not active that year</div>';
    }

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

function triDataTableHtml(headers, rows, opts) {
    var html = '<table style="width:100%; border-collapse:collapse; font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;">';
    html += '<thead><tr>';
    headers.forEach(function (h, i) {
        var align = i === 0 ? 'left' : 'right';
        html += '<th style="text-align:' + align + '; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280; font-weight:500; padding:8px 14px; border-bottom:1px solid #E8E6DF; background:#FAFAF7;">' + triEsc(h) + '</th>';
    });
    html += '</tr></thead><tbody>';
    rows.forEach(function (r, ri) {
        var isTotal = opts && opts.isTotal && opts.isTotal(ri, r);
        var rowBg = (ri % 2 === 0 && !isTotal) ? '#fff' : (isTotal ? '#F8F6F1' : '#FAFAF7');
        var fontWeight = isTotal ? '600' : '400';
        var topBorder = isTotal ? '2px solid #2c3e50' : '0';
        html += '<tr style="background:' + rowBg + ';">';
        r.forEach(function (cell, ci) {
            var align = ci === 0 ? 'left' : 'right';
            var font = ci === 0 ? "'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif" : "'IBM Plex Mono',Consolas,monospace";
            html += '<td style="padding:8px 14px; font-size:12.5px; text-align:' + align + '; border-bottom:1px solid #E8E6DF; border-top:' + topBorder + '; font-family:' + font + '; font-weight:' + fontWeight + '; font-variant-numeric:tabular-nums; color:#0F1720;">' + triEsc(cell) + '</td>';
        });
        html += '</tr>';
    });
    html += '</tbody></table>';
    return html;
}

function triVolumeChartHtml(selMonth) {
    var d = TRI_STATE.data;
    var totals = d.months.map(function (m) { return { m: m, t: triMonthTotals(d.volume, m) }; });
    var max = Math.max.apply(null, totals.map(function (x) { return Math.max(x.t[0], x.t[1], x.t[2]); }));
    var colors = { AML: '#C7801B', ECO: '#4a6fa5', MCO: '#9CA3AF' };
    var html = '<div style="display:grid; grid-template-columns:repeat(' + d.months.length + ', 1fr); gap:0; padding:18px 18px 10px; align-items:end;">';
    totals.forEach(function (tt) {
        var m = tt.m, t = tt.t;
        var active = m === selMonth;
        html += '<div onclick="triSelectMonth(\'' + m + '_' + d.month.split('_')[1] + '\')" '
              + 'style="display:flex; flex-direction:column; align-items:center; gap:0; cursor:pointer; border-radius:6px; padding:8px 6px 6px;'
              + (active ? ' background:#e8f0fe;' : '') + '">';
        html += '<div style="display:flex; align-items:flex-end; gap:5px; height:150px;">';
        [['ECO', t[1]], ['MCO', t[2]], ['AML', t[0]]].forEach(function (kv) {
            var h = Math.max(2, (kv[1] / max) * 140);
            html += '<div style="width:26px; border-radius:2px 2px 0 0; height:' + h + 'px; background:' + colors[kv[0]] + '; position:relative;">'
                  + '<span style="position:absolute; top:-16px; left:50%; transform:translateX(-50%); font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6B7280; white-space:nowrap;">' + triFmt(kv[1]) + '</span>'
                  + '</div>';
        });
        html += '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:' + (active ? '#1a3a5c' : '#6B7280') + '; font-weight:' + (active ? '600' : '400') + '; margin-top:8px;">' + triEsc(m) + "'" + d.month.split('_')[1].substring(2) + '</div>';
        html += '</div>';
    });
    html += '</div>';
    html += '<div style="display:flex; gap:14px; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; align-items:center;">'
          + '<span><i style="width:9px; height:9px; background:#4a6fa5; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>ECO</span>'
          + '<span><i style="width:9px; height:9px; background:#9CA3AF; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>MCO</span>'
          + '<span><i style="width:9px; height:9px; background:#C7801B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>AML</span>'
          + '<span style="margin-left:auto;">Affected items &middot; click a month to drive the page</span>'
          + '</div>';
    return html;
}

// Shared renderer for the xCO/ECN bars + affected-items chip charts.
// `series` items must have {xco, ecn, items}; `labelFn(item)` returns the
// per-column label HTML (PCM name, month, etc.).
function triXcoEcnItemsChartHtml(series, labelFn) {
    var s = series;
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
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6f42c1; margin-top:4px;">&#9650; ' + triFmt(x.items) + '</div>';
        html += labelFn(x);
        html += '</div>';
    });
    html += '</div>';
    html += '<div style="display:flex; gap:14px; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; align-items:center;">'
          + '<span><i style="width:9px; height:9px; background:#B8342B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>Total xCO</span>'
          + '<span><i style="width:9px; height:9px; background:#7FA84B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>Total ECN</span>'
          + '<span style="color:#6f42c1;">&#9650; Affected items</span>'
          + '</div>';
    return html;
}

function triPcmChartHtml() {
    return triXcoEcnItemsChartHtml(triPcmVolumeSeries(TRI_STATE.data), function (x) {
        return '<div style="font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif; font-size:11px; color:#0F1720; font-weight:500; margin-top:4px; text-align:center; line-height:1.2;">' + triEsc(x.pcm) + '</div>';
    });
}

function triMonthVolumeChartHtml() {
    var yy = TRI_STATE.data.month.split('_')[1].substring(2);
    return triXcoEcnItemsChartHtml(triMonthVolumeSeries(TRI_STATE.data), function (x) {
        return '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-top:4px;">' + triEsc(x.month) + "'" + triEsc(yy) + '</div>';
    });
}

var TRI_PIE_COLORS = ['#4a6fa5', '#1F8A4C', '#C7801B', '#7C3AED', '#B8342B', '#0F766E', '#9CA3AF', '#D97706', '#2c3e50'];

function triActivityPieHtml() {
    var d = TRI_STATE.data;
    var items = (d.activityTypes || []);
    if (!items.length) {
        return '<div style="padding:18px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#6B7280;">No change-activity data for this month.</div>';
    }
    var donut = triDonutStops(items, TRI_PIE_COLORS);
    var html = '<div style="display:flex; gap:24px; align-items:center; flex-wrap:wrap; padding:18px;">';
    html += '<div style="position:relative; width:170px; height:170px; flex:0 0 auto;">'
          + '<div style="width:170px; height:170px; border-radius:50%; background:conic-gradient(' + donut.gradient + ');"></div>'
          + '<div style="position:absolute; top:50%; left:50%; transform:translate(-50%,-50%); width:96px; height:96px; border-radius:50%; background:#fff; display:flex; flex-direction:column; align-items:center; justify-content:center;">'
          + '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:24px; font-weight:500; color:#0F1720;">' + triFmt(donut.total) + '</div>'
          + '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280;">changes</div>'
          + '</div></div>';
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

function triDonutStops(items, palette) {
    var total = 0;
    items.forEach(function (it) { total += it.count || 0; });
    if (total <= 0) {
        return { total: 0, segments: [], gradient: '#E8E6DF 0% 100%' };
    }
    var segments = [], stops = [], acc = 0;
    items.forEach(function (it, i) {
        var pct = Math.round((it.count / total) * 1000) / 10;
        var start = (acc / total) * 100;
        acc += it.count || 0;
        var end = (acc / total) * 100;
        var color = palette[i % palette.length];
        segments.push({ name: it.name, count: it.count, pct: pct, color: color });
        stops.push(color + ' ' + (Math.round(start * 100) / 100) + '% ' + (Math.round(end * 100) / 100) + '%');
    });
    return { total: total, segments: segments, gradient: stops.join(', ') };
}

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

var TRI_PL_COLORS = ['#4a6fa5', '#1F8A4C', '#C7801B', '#7C3AED', '#B8342B', '#0F766E',
                     '#D97706', '#2c3e50', '#6B7280', '#15803D', '#9333EA', '#0E7490', '#A16207'];

function triYearlyChartHtml() {
    var h = TRI_STATE.history;
    var s = triYearSeries(h);
    if (!s.length) return '';
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
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; color:#6B7280; margin-top:2px; white-space:nowrap;">' + triEsc(x.year) + '</div>';
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

// Year-series shaping for the "Total volume processed yearly" chart (#5).
// Pure — no DOM access, no TRI_STATE. Unit-tested in test/js/teamreport-inapp.test.js.
function triYearSeries(history) {
    if (!history || !history.years || !history.years.length) return [];
    return history.years.map(function (y) {
        var eco = y.eco || 0, mco = y.mco || 0, aml = y.aml || 0;
        return { year: y.year, label: y.label || y.year, eco: eco, mco: mco, aml: aml,
                 xco: eco + mco + aml, items: y.affectedItems || 0 };
    });
}

function triEcnByPlHtml() {
    var d = TRI_STATE.data;
    var ecn = d.ecnByProductLine || {};
    var months = d.months || [];
    var picked = triTopNProductLines(ecn, months, 12);
    if (!picked.lines.length) {
        return '<div style="padding:18px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#6B7280;">No product-line data for this period.</div>';
    }
    var lines = picked.lines;
    var yy = d.month.split('_')[1].substring(2);
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
    html += '<div style="display:flex; gap:10px 14px; flex-wrap:wrap; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; color:#6B7280;">';
    lines.forEach(function (pl, i) {
        html += '<span><i style="width:9px; height:9px; background:' + colorOf(pl, i) + '; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>' + triEsc(pl) + '</span>';
    });
    html += '</div>';
    return html;
}

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
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6f42c1; margin-top:4px;">&#9650; ' + triFmt(x.items) + '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; margin-top:4px;">' + triEsc(x.month) + "'" + triEsc(yy) + '</div>';
        html += '</div>';
    });
    html += '</div>';
    html += '<div style="display:flex; gap:14px; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; align-items:center;">'
          + '<span><i style="width:9px; height:9px; background:#4a6fa5; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>ECO</span>'
          + '<span><i style="width:9px; height:9px; background:#9CA3AF; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>MCO</span>'
          + '<span><i style="width:9px; height:9px; background:#C7801B; border-radius:2px; display:inline-block; margin-right:5px; vertical-align:-1px;"></i>AML</span>'
          + '<span style="color:#6f42c1;">&#9650; Affected items</span>'
          + '</div>';
    return html;
}

function triAiGridHtml() {
    var d = TRI_STATE.data;
    if (!d.analysis || d.analysis.length === 0) {
        var fullMonth = triEsc(TRI_FULL_MONTH[triShortMonthOf(TRI_STATE.month)] || TRI_STATE.month);
        if (TRI_STATE.aiGenerating) {
            // Auto-fire is running — show a live progress card.
            return '<div style="padding:40px 18px; text-align:center; color:#6B7280;">'
                + '<div style="display:inline-flex; align-items:center; gap:10px; font-size:13px;">'
                +   '<span style="display:inline-block; width:14px; height:14px; border:2px solid #E8E6DF; border-top-color:#4a6fa5; border-radius:50%; animation:trispin 0.9s linear infinite;"></span>'
                +   'Asking Claude about ' + fullMonth + '… typically <strong style="color:#0F1720;">60&ndash;90&nbsp;sec</strong> for the per-team analysis.'
                + '</div>'
                + '<style>@keyframes trispin { to { transform: rotate(360deg); } }</style>'
                + '</div>';
        }
        // Idle empty state — should be rare since auto-fire kicks in on
        // load. Surfaces a manual retry button for the error / retry case.
        return '<div style="padding:34px 18px; text-align:center; color:#6B7280;">'
            + '<p style="font-size:13px; margin:0 0 16px;">'
            +   'AI analysis hasn&rsquo;t been generated for ' + fullMonth + ' yet.'
            + '</p>'
            + '<button class="tool-btn tool-btn-primary" onclick="triRegenerateAi(true)" style="font-weight:600; padding:8px 18px; font-size:13px;">Generate AI Analysis</button>'
            + '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#9CA3AF; margin-top:10px;">Reads the existing workbook on disk &middot; runs the per-team Portkey call &middot; ~60&ndash;90 sec</div>'
            + '</div>';
    }
    var html = '<div style="display:grid; grid-template-columns:repeat(2, 1fr); gap:12px; padding:14px 16px 16px;">';
    d.analysis.forEach(function (t) {
        var isHigh = t.risk === 'high';
        var calloutBg = isHigh ? '#fdeaea' : '#F0EFE9';
        var calloutColor = isHigh ? '#B8342B' : '#6B7280';
        var calloutBoldColor = isHigh ? '#B8342B' : '#0F1720';
        var riskBg = isHigh ? '#fdeaea' : (t.risk === 'med' ? '#fff8e1' : '#F0EFE9');
        var riskColor = isHigh ? '#B8342B' : (t.risk === 'med' ? '#856404' : '#6B7280');
        html += '<div style="border:1px solid #E8E6DF; border-radius:6px; padding:13px 15px; background:#fff;">'
              + '<div style="display:flex; align-items:baseline; gap:9px; margin-bottom:6px;">'
              +   '<span style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:16px; font-weight:500;">' + triEsc(t.team) + '</span>'
              +   '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280;">' + triFmt(t.count) + ' changes &middot; ' + triEsc(t.urgent || '') + '</span>'
              +   '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; letter-spacing:0.06em; text-transform:uppercase; padding:2px 7px; border-radius:4px; margin-left:auto; background:' + riskBg + '; color:' + riskColor + ';">' + triEsc(t.risk || '') + ' risk</span>'
              + '</div>'
              + '<p style="font-size:12.5px; color:#6B7280; line-height:1.55; margin:0;">' + triEsc(t.summary || '') + '</p>';
        if (t.callout) {
            html += '<div style="margin-top:9px; padding:8px 11px; border-radius:4px; font-size:12px; line-height:1.5; background:' + calloutBg + '; color:' + calloutColor + ';">'
                  + '<strong style="color:' + calloutBoldColor + ';">Callout:</strong> ' + triEsc(t.callout)
                  + '</div>';
        }
        html += '</div>';
    });
    html += '</div>';
    return html;
}

/**
 * Re-fires the AI per-team analysis pass against the existing workbook on
 * disk and splices the result into the page state. Triggered automatically
 * on tab load when {data.analysis} is empty (so users no longer have to
 * walk through the Generate flow just to get AI for an existing month) and
 * also from the manual "Generate AI Analysis" button in the empty state.
 *
 * The `manual` flag is just for diagnostics — the call path is the same.
 */
function triRegenerateAi(manual) {
    if (TRI_STATE.aiGenerating) return;
    var month = TRI_STATE.month;
    if (!month) return;
    TRI_STATE.aiGenerating = true;
    triRender(); // flip the AI card into the spinning "Asking Claude…" state
    fetch('/api/team-report/' + encodeURIComponent(month) + '/regenerate-ai',
            { method: 'POST', credentials: 'same-origin' })
        .then(function (r) {
            if (!r.ok) return r.json().then(function (j) {
                throw new Error(j.error || ('HTTP ' + r.status));
            });
            return r.json();
        })
        .then(function (d) {
            TRI_STATE.aiGenerating = false;
            if (d && d.analysis && TRI_STATE.data) {
                TRI_STATE.data.analysis = d.analysis;
                if (d.pcmWorkload) TRI_STATE.data.pcmWorkload = d.pcmWorkload;
                if (d.reportSuggestions) TRI_STATE.data.reportSuggestions = d.reportSuggestions;
                triRender();
            } else {
                // Server reported success but no payload — re-fetch the full
                // data shape just to be safe.
                triLoadMonth(month);
            }
        })
        .catch(function (e) {
            TRI_STATE.aiGenerating = false;
            // Clear the auto-trigger flag so the user can click the manual
            // button to retry on the next render without changing months.
            TRI_STATE.aiAutoTriggered[month] = false;
            triRender();
            var msg = 'AI analysis failed: ' + e.message;
            if (typeof appAlert === 'function') appAlert(msg);
            else console.warn(msg);
        });
}

/**
 * Smart wrapper around teamReportOpenDrawer() that handles the cold-start
 * case: the drawer (in volumereport.js) early-returns when
 * volumeState.rows is empty. That's correct for Volume Reports — you need a
 * query first — but it makes the button look dead when called from the
 * Team Report tab without having visited Volume Reports first.
 *
 * If we're cold, we walk the user there with a friendly banner instead of
 * silently doing nothing. If we're warm, just open the drawer as the mock
 * intends.
 */
function triGenerateClick() {
    var warm = window.volumeState && volumeState.rows && volumeState.rows.length;
    if (warm) {
        teamReportOpenDrawer();
        return;
    }
    // Cold, but since the one-click endpoint exists the query-first walk-through
    // is only needed for a custom month / a hand-picked prior file — open the
    // one-click preflight dialog (who it covers + manager picker) instead.
    if (typeof teamReportOneClickConfirm === 'function') {
        teamReportOneClickConfirm();
        return;
    }
    if (typeof appAlert === 'function') {
        appAlert(
            'Generate Team Report needs a Volume Reports query first.\n\n' +
            'Open Volume Reports → pick a manager + date range → Run, then come ' +
            'back here. The drawer will open with your run pre-filled.'
        );
    } else {
        alert('First open Volume Reports → run a query, then come back here and click Generate.');
    }
    if (typeof ecnSwitchView === 'function') ecnSwitchView('volume');
}

/**
 * Amber nudge when the newest generated report lags behind the last complete
 * calendar month (e.g. it's July but the newest report on disk is May) —
 * with an in-place one-click Generate so the tab catches itself up.
 */
function triStaleBannerHtml() {
    var latest = TRI_STATE.months.length ? TRI_STATE.months[0] : null;
    if (!latest) return '';
    var target = triLastCompleteMonth(new Date());
    if (triMonthOrdinal(target) <= triMonthOrdinal(latest)) return '';
    var label = target.replace('_', ' ');
    return '<div id="triStaleBanner" style="margin:14px 0 0; padding:10px 14px; background:#fff8e1; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; font-size:12.5px; color:#7a5210; display:flex; align-items:center; gap:12px; flex-wrap:wrap;">'
        + '<span><strong>' + triEsc(label) + ' is complete</strong> but its report hasn’t been generated yet — the newest on disk is ' + triEsc(latest.replace('_', ' ')) + '.</span>'
        + '<button id="triStaleGenBtn" class="tool-btn" onclick="triOneClickFromTab()" style="border-color:#C7801B; color:#7a5210; font-weight:600;">&#9889; Generate ' + triEsc(label) + ' now</button>'
        + '<span id="triStaleStatus" style="flex-basis:100%; display:none; font-size:12px; color:#6B7280;"></span>'
        + '<span id="triStaleErr" style="flex-basis:100%; display:none; font-size:12px; color:#B8342B;"></span>'
        + '<span id="triStaleOk" style="flex-basis:100%; display:none; font-size:12px; color:#155724;"></span>'
        + '</div>';
}

/** Banner button → one-click preflight; progress shows inside the banner. */
function triOneClickFromTab() {
    if (typeof teamReportOneClickConfirm !== 'function') return;
    teamReportOneClickConfirm(null, {
        btn: document.getElementById('triStaleGenBtn'),
        statusEl: document.getElementById('triStaleStatus'),
        okEl: document.getElementById('triStaleOk'),
        errEl: document.getElementById('triStaleErr')
    });
}

/**
 * Called by volumereport.js after ANY successful generation (one-click or
 * drawer) — re-probe which months exist on disk and load the newest, so the
 * in-app tab reflects the new report without a page reload.
 */
function triOnReportGenerated() {
    teamReportInAppInit();
}

// === Interactions =========================================================

function triSelectMonth(month) {
    // Two forms accepted: "May" (just short label) or "May_2026" (full key)
    if (month && month.indexOf('_') < 0 && TRI_STATE.data) {
        month = month + '_' + TRI_STATE.data.month.split('_')[1];
    }
    if (TRI_STATE.months.indexOf(month) >= 0) {
        triLoadMonth(month);
    } else if (TRI_STATE.data) {
        // No backend file for that month — just re-render with stat strip
        // updated to the new "current month" within the existing data window.
        var short = month.split('_')[0];
        if (TRI_STATE.data.months.indexOf(short) >= 0) {
            // Pretend we picked the displayed window's column for stat calcs.
            // (No fetch needed — the months list is already loaded.)
            // We'll just rerender by faking month state.
            // For simplicity, no-op — clicking a missing month does nothing.
        }
    }
}

function triSelectView(opt) {
    if (opt && opt.indexOf('YTD') >= 0) TRI_STATE.view = 'YTD';
    else if (opt === 'Q1' || opt === 'Q2' || opt === 'Q3' || opt === 'Q4') TRI_STATE.view = opt;
    else if (opt === 'Year') TRI_STATE.view = 'Year';
    else TRI_STATE.view = 'This month';
    triRender();
}

function triToggleExportMenu() {
    var el = document.getElementById('triExportMenu');
    if (!el) return;
    el.style.display = el.style.display === 'none' ? 'block' : 'none';
}

function triToggleExportMenuBottom() {
    var el = document.getElementById('triExportMenuBottom');
    if (!el) return;
    el.style.display = el.style.display === 'none' ? 'block' : 'none';
}

function triExportMenuItemsHtml() {
    return ''
        + '<button onclick="triExportExcel()" style="display:block; width:100%; text-align:left; padding:12px 16px; background:transparent; border:0; border-bottom:1px solid #E8E6DF; cursor:pointer; font-family:inherit; font-size:13px;">'
        +   '<div style="font-weight:500; color:#0F1720;">Excel workbook (.xlsx) <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; letter-spacing:0.06em; text-transform:uppercase; color:#155724; background:#d4edda; border-radius:3px; padding:1px 5px; margin-left:6px;">Current</span></div>'
        +   '<div style="font-size:11.5px; color:#6B7280; line-height:1.45; margin-top:3px;">Team_Report_2026_' + triShortMonthOf(TRI_STATE.month) + '.xlsx &mdash; full rolled-forward workbook with raw-data sheets, pivots and discrepancies.docx.</div>'
        + '</button>'
        + '<button onclick="triExportPptx()" style="display:block; width:100%; text-align:left; padding:12px 16px; background:transparent; border:0; cursor:pointer; font-family:inherit; font-size:13px;">'
        +   '<div style="font-weight:500; color:#0F1720;">PowerPoint (.pptx) &mdash; SanDisk template <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; letter-spacing:0.06em; text-transform:uppercase; color:#1a3a5c; background:#e8f0fe; border-radius:3px; padding:1px 5px; margin-left:6px;">New</span></div>'
        +   '<div style="font-size:11.5px; color:#6B7280; line-height:1.45; margin-top:3px;">4 slides: title, total processing by PCM, affected items by month, AI analysis.</div>'
        + '</button>';
}

function triExportExcel() {
    document.getElementById('triExportMenu').style.display = 'none';
    // Cross-user month download — serves the SAME newest-on-disk report the
    // tab renders (the old /recent scan was per-user and name-substring
    // based, which could grab a different user's file or a quarantined one).
    window.location.href = '/api/team-report/' + encodeURIComponent(TRI_STATE.month) + '/xlsx';
}

function triExportPptx() {
    document.getElementById('triExportMenu').style.display = 'none';
    var url = '/api/team-report/' + encodeURIComponent(TRI_STATE.month) + '/pptx';
    fetch(url, { method: 'POST', credentials: 'same-origin' })
        .then(function (r) {
            if (!r.ok) return r.text().then(function (t) { throw new Error(t.substring(0, 400)); });
            return r.blob().then(function (blob) {
                var cd = r.headers.get('Content-Disposition') || '';
                var m = cd.match(/filename="?([^";]+)"?/);
                var fname = m ? m[1] : 'Team_Report_' + TRI_STATE.month + '.pptx';
                var dlUrl = URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.href = dlUrl; a.download = fname;
                document.body.appendChild(a); a.click();
                document.body.removeChild(a); URL.revokeObjectURL(dlUrl);
            });
        })
        .catch(function (e) {
            if (typeof appAlert === 'function') appAlert('PowerPoint export failed: ' + e.message);
            else alert('PowerPoint export failed: ' + e.message);
        });
}

// ─── triPcmWorkload ──────────────────────────────────────────────────────────
// Computes per-PCM workload analytics from /data, ranked by xCO (descending).
// Returns { rows, grandXco, months } where each row is
//   { pcm, items, xco, ecn, avgMonthly, pct }.
// pct is rounded to 1 decimal (e.g. 80 not 80.0 when clean).
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

// ─── triPcmWorkloadHtml ───────────────────────────────────────────────────────
// Renders the PCM workload ranked table (GroupCard 2i).
// Top 5 by xCO are highlighted green; bottom 5 are highlighted red.
// YoY column is a stub (—) pending Noraida's per-PCM history file.
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
              + (function () {
                  var py = triPcmPrevYearXco(TRI_STATE.history, r.pcm);
                  var cell = py ? ("vs '" + py.year.substring(2) + ": " + triFmt(py.xco)) : '&mdash;';
                  return '<td style="padding:8px 14px; font-size:11px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; color:#6B7280; border-bottom:1px solid #E8E6DF;">' + cell + '</td>';
                })()
              + '</tr>';
    });
    html += '</tbody></table>';
    html += '<div style="padding:8px 14px 12px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; color:#9CA3AF;">Green = top 5 by xCO' + (n > 10 ? ' &middot; red = bottom 5' : '') + ' &middot; YoY = prior full-year xCO</div>';
    return html;
}

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

// ─── triPcmYearRows ───────────────────────────────────────────────────────────
// Shapes history.byPcm into { years, rows } where each row is { pcm, cells, latest }.
// cells[i] = xco for years[i], null if PCM was not active that year.
// Rows are sorted descending by the latest-year xco.
// Pure — no DOM access, no TRI_STATE. Unit-tested.
function triPcmYearRows(history) {
    var bp = history && history.byPcm;
    if (!bp) return { years: [], rows: [] };
    var years = Object.keys(bp).sort();
    if (!years.length) return { years: [], rows: [] };
    var pcmSet = {};
    years.forEach(function (y) { Object.keys(bp[y] || {}).forEach(function (p) { pcmSet[p] = 1; }); });
    var latest = years[years.length - 1];
    var rows = Object.keys(pcmSet).map(function (p) {
        var cells = years.map(function (y) { var e = (bp[y] || {})[p]; return e ? (e.xco || 0) : null; });
        var latestXco = ((bp[latest] || {})[p] || {}).xco || 0;
        return { pcm: p, cells: cells, latest: latestXco };
    }).sort(function (a, b) { return b.latest - a.latest; });
    return { years: years, rows: rows };
}

// ─── triPlYearSeries ──────────────────────────────────────────────────────────
// Shapes history.byProductLine into { years, lines, byLine } for the yearly
// stacked-column chart. Top N PLs by grand total; rest bucketed into "Other".
// Pure — no DOM access, no TRI_STATE. Unit-tested.
function triPlYearSeries(history, n) {
    var bpl = history && history.byProductLine;
    if (!bpl) return { years: [], lines: [], byLine: {} };
    var years = Object.keys(bpl).sort();
    if (!years.length) return { years: [], lines: [], byLine: {} };
    var totals = {};
    years.forEach(function (y) {
        Object.keys(bpl[y] || {}).forEach(function (pl) { totals[pl] = (totals[pl] || 0) + (bpl[y][pl] || 0); });
    });
    var ranked = Object.keys(totals).sort(function (a, b) { return totals[b] - totals[a]; });
    var top = ranked.slice(0, n);
    var hasOther = ranked.length > n;
    var lines = hasOther ? top.concat(['Other']) : top.slice();
    var byLine = {};
    lines.forEach(function (pl) { byLine[pl] = []; });
    years.forEach(function (y) {
        var row = bpl[y] || {};
        top.forEach(function (pl) { byLine[pl].push(row[pl] || 0); });
        if (hasOther) {
            var o = 0;
            Object.keys(row).forEach(function (k) { if (top.indexOf(k) < 0) o += row[k] || 0; });
            byLine['Other'].push(o);
        }
    });
    return { years: years, lines: lines, byLine: byLine };
}

// ─── triPcmPrevYearXco ───────────────────────────────────────────────────────
// Returns { year, xco } for the second-latest year in history.byPcm for a
// given PCM, or null if unavailable (< 2 years or PCM not present).
// Pure — no DOM access, no TRI_STATE. Unit-tested.
function triPcmPrevYearXco(history, pcm) {
    var bp = history && history.byPcm;
    if (!bp) return null;
    var years = Object.keys(bp).sort();
    if (years.length < 2) return null;
    var prev = years[years.length - 2];
    var e = (bp[prev] || {})[pcm];
    return e ? { year: prev, xco: e.xco || 0 } : null;
}

// ─── triPlByYearHtml ──────────────────────────────────────────────────────────
// Stacked-column chart for ECN-by-product-line by year (#8).
// Categories = years, series = top-12 PLs + Other. Mirrors triEcnByPlHtml idiom.
function triPlByYearHtml() {
    var s = triPlYearSeries(TRI_STATE.history, 12);
    if (!s.years.length) return '';
    var maxTotal = 1;
    s.years.forEach(function (y, yi) {
        var t = 0; s.lines.forEach(function (pl) { t += s.byLine[pl][yi] || 0; });
        if (t > maxTotal) maxTotal = t;
    });
    var colorOf = function (pl, i) { return pl === 'Other' ? '#CBD5E1' : TRI_PL_COLORS[i % TRI_PL_COLORS.length]; };
    var html = '<div style="display:grid; grid-template-columns:repeat(' + s.years.length + ', 1fr); gap:0; padding:18px 10px 10px; align-items:end; overflow-x:auto;">';
    s.years.forEach(function (y, yi) {
        var total = 0; s.lines.forEach(function (pl) { total += s.byLine[pl][yi] || 0; });
        html += '<div style="display:flex; flex-direction:column; align-items:center; padding:8px 3px 6px;">';
        html += '<div style="position:relative; width:30px; height:170px; display:flex; flex-direction:column-reverse;">';
        s.lines.forEach(function (pl, i) {
            var v = s.byLine[pl][yi] || 0;
            if (v <= 0) return;
            var h = (v / maxTotal) * 160;
            html += '<div title="' + triEsc(pl) + ' ' + triEsc(y) + ': ' + v + '" style="width:30px; height:' + h + 'px; background:' + colorOf(pl, i) + ';"></div>';
        });
        html += '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9px; color:#6B7280; margin-top:4px;">' + triFmt(total) + '</div>';
        html += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6B7280; margin-top:2px;">' + triEsc(y) + '</div>';
        html += '</div>';
    });
    html += '</div>';
    html += '<div style="display:flex; gap:8px 12px; flex-wrap:wrap; padding:0 18px 14px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:9.5px; color:#6B7280;">';
    s.lines.forEach(function (pl, i) {
        html += '<span><i style="width:9px; height:9px; background:' + colorOf(pl, i) + '; border-radius:2px; display:inline-block; margin-right:4px; vertical-align:-1px;"></i>' + triEsc(pl) + '</span>';
    });
    html += '</div>';
    return html;
}

// Node test harness only — no effect in the browser (module is undefined there).
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        triQuarterOf: triQuarterOf,
        triMonthsInQuarter: triMonthsInQuarter,
        triQuartersInData: triQuartersInData,
        triPcmTotalsOverMonths: triPcmTotalsOverMonths,
        triPcmQuarterRows: triPcmQuarterRows,
        triPcmVolumeSeries: triPcmVolumeSeries,
        triMonthVolumeSeries: triMonthVolumeSeries,
        triChangeTypeMonthSeries: triChangeTypeMonthSeries,
        triDonutStops: triDonutStops,
        triTopNProductLines: triTopNProductLines,
        triYearSeries: triYearSeries,
        triPcmWorkload: triPcmWorkload,
        triPcmYearRows: triPcmYearRows,
        triPlYearSeries: triPlYearSeries,
        triPcmPrevYearXco: triPcmPrevYearXco,
        triMonthOrdinal: triMonthOrdinal,
        triLastCompleteMonth: triLastCompleteMonth,
        __setState: function (d) { TRI_STATE.data = d; }
    };
}
