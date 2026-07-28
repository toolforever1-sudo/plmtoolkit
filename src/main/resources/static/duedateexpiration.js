/* ============================================================================
 * Due Date Expiration view — ECN Dashboard (ECN-137642-PROJ)
 *
 * In-app, cached replica of the daily "ECN Due Date Expiration" email, plus a
 * per-analyst tile row and two trend charts. v2 design language (tokens.css:
 * insight-strip stats, CSS dots, mono data, table-first layout).
 *
 * Backend: /api/ecn-report/due-date/{data,refresh,trend,download}
 * ==========================================================================*/

var dueDateState = {
    built: false,
    data: null,
    includeProj: false,
    analystFilter: null,   // click a tile to filter the table
    changeTypeFilter: null, // PT-98: click a classification button / bar to filter the table
    charts: {},            // canvasId -> Chart instance
    trendExpanded: false,
    forwardHist: [],       // per-day bars
    forwardRows: [],       // ECN detail behind the bars
    selectedFwd: null      // selected bar date iso (drilldown)
};

function dueDateInit() {
    dueDateBuildShell();
    dueDateLoad();
    dueDateLoadTrend();
}

function dueDateBuildShell() {
    if (dueDateState.built) return;
    var host = document.getElementById('dueDateView');
    if (!host) return;
    host.innerHTML =
        dueDateStyles() +
        '<div class="dd-view">' +
        '<div class="dd-head">' +
            '<div>' +
                '<div class="dd-title">ECN Due Date Expiration</div>' +
                '<div id="dueDateSubtitle" class="dd-sub">Loading&hellip;</div>' +
            '</div>' +
            '<div class="dd-tools">' +
                '<label class="dd-toggle" title="Include IT / project (-PROJ) ECNs. Off = operational view matching the email.">' +
                    '<input type="checkbox" id="dueDateProjToggle" onchange="dueDateToggleProj(this.checked)"> Include IT (-PROJ)' +
                '</label>' +
                '<button class="dd-btn-ghost" onclick="dueDateDownload()" title="Download the current view as Excel">&#11015; Download Excel</button>' +
                '<button class="dd-btn-primary" id="dueDateRefreshBtn" onclick="dueDateRefresh()" title="Re-query Agile now and update the cache + snapshot">&#8635; Refresh</button>' +
            '</div>' +
        '</div>' +
        // 1. Summary stat strip
        '<div id="dueDateStrip" class="dd-strip"></div>' +
        // 2. Trends — kept prominent under the stat strip (matches the v2 mock)
        '<div class="dd-charts">' +
            '<div class="dd-chart-card" id="dueDateTrendCard">' +
                '<div class="dd-chart-head"><span class="dd-chart-title">Backlog over time</span>' +
                    '<button class="dd-mini" id="dueDateTrendToggle" onclick="dueDateToggleTrend()" style="display:none;">expand &rarr;</button></div>' +
                '<div id="dueDateTrendBody"><div class="dd-canvas-wrap"><canvas id="dueDateTrendChart"></canvas></div></div>' +
                '<div id="dueDateTrendMsg" class="dd-chart-msg" style="display:none;"></div>' +
            '</div>' +
            '<div class="dd-chart-card">' +
                '<div class="dd-chart-head"><span class="dd-chart-title">Due dates ahead</span>' +
                    '<span class="dd-chart-note">next ' + '<span id="dueDateFwdDays">28</span>' + ' days &middot; gray = weekend</span></div>' +
                '<div class="dd-canvas-wrap"><canvas id="dueDateForwardChart"></canvas></div>' +
                '<div id="dueDateFwdAxis" class="dd-fwd-axis"></div>' +
            '</div>' +
        '</div>' +
        // 2b. Bar-click drilldown: ECNs due on the selected date (#29)
        '<div id="dueDateFwdList" class="dd-fwd-list" style="display:none;"></div>' +
        // 2c. ECN Change Type breakdown tile — horizontal bar graph of counts
        //     by classification, sorted high->low (PT-98). Click a bar to filter.
        '<div class="dd-chart-card dd-ct-card">' +
            '<div class="dd-chart-head"><span class="dd-chart-title">ECNs by change type</span>' +
                '<span class="dd-chart-note">click a bar to filter the table</span></div>' +
            '<div id="dueDateCtWrap" class="dd-ct-wrap"><div class="dd-canvas-wrap" id="dueDateCtCanvasWrap"><canvas id="dueDateCtChart"></canvas></div></div>' +
            '<div id="dueDateCtMsg" class="dd-chart-msg" style="display:none;"></div>' +
        '</div>' +
        // 3. Per-analyst tiles
        '<div class="dd-section-label">ECNs pending per Analyst <span class="dd-hint">— click to filter the table</span></div>' +
        '<div id="dueDateAnalysts" class="dd-analysts"></div>' +
        // 3b. ECN Change Classification filter buttons (like the Cycle Time tab, PT-98)
        '<div id="dueDateCtFilterLabel" class="dd-section-label" style="display:none;">Filter by ECN change type</div>' +
        '<div id="dueDateCtFilters" class="dd-ctfilters" style="display:none;"></div>' +
        // 4. The work queue
        '<div id="dueDateTableWrap" class="dd-tablewrap"></div>' +
        '</div>';
    dueDateState.built = true;
}

function dueDateStyles() {
    return '<style>' +
    '.dd-view{font-family:var(--font-sans,"IBM Plex Sans",sans-serif);color:var(--ink,#1a1d24);}' +
    '.dd-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:14px;}' +
    '.dd-title{font-family:var(--font-serif,Georgia,serif);font-size:var(--t-h2,18px);font-weight:600;color:var(--ink,#1a1d24);}' +
    '.dd-sub{font-size:var(--t-micro,11px);color:var(--ink-3,#6B7280);margin-top:3px;font-family:var(--font-mono,monospace);}' +
    '.dd-tools{display:flex;align-items:center;gap:10px;flex-wrap:wrap;}' +
    '.dd-toggle{font-size:var(--t-small,12px);color:var(--ink-3,#6B7280);display:inline-flex;align-items:center;gap:5px;cursor:pointer;}' +
    // buttons (match the v2 mock: ghost Excel + dark Refresh)
    '.dd-btn-ghost,.dd-btn-primary{font-family:var(--font-sans,sans-serif);font-size:var(--t-small,12px);font-weight:500;padding:6px 12px;border-radius:var(--radius-sm,4px);cursor:pointer;display:inline-flex;align-items:center;gap:6px;line-height:1;}' +
    '.dd-btn-ghost{background:var(--surface,#fff);color:var(--ink-2,#40454e);border:1px solid var(--line-2,#d4d2cb);}' +
    '.dd-btn-ghost:hover{background:var(--surface-2,#f3f3ee);}' +
    '.dd-btn-primary{background:var(--ink,#1a1d24);color:#fff;border:1px solid var(--ink,#1a1d24);}' +
    '.dd-btn-primary:hover{background:var(--ink-2,#40454e);}' +
    '.dd-btn-primary:disabled{opacity:0.6;cursor:default;}' +
    '.dd-hint{font-weight:400;text-transform:none;letter-spacing:0;color:var(--ink-4,#9aa0aa);}' +
    '.dd-chart-note{font-family:var(--font-mono,monospace);font-size:10px;color:var(--ink-4,#9aa0aa);}' +
    '.dd-fwd-axis{font-family:var(--font-mono,monospace);font-size:9.5px;color:var(--ink-3,#6B7280);display:flex;justify-content:space-between;margin-top:4px;padding:0 2px;}' +
    '.dd-fwd-axis .mid{color:var(--ink-4,#9aa0aa);}' +
    // bar-click drilldown list (#29)
    '.dd-fwd-list{border:1px solid var(--line,#e8e6df);border-radius:var(--radius,6px);background:var(--surface,#fff);margin:-8px 0 20px;padding:10px 14px;}' +
    '.dd-fwd-list .hd{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:8px;}' +
    '.dd-fwd-list .ttl{font-family:var(--font-mono,monospace);font-size:var(--t-small,12px);color:var(--ink,#1a1d24);}' +
    '.dd-fwd-list .ttl b{color:var(--accent,#2f6fb2);}' +
    '.dd-fwd-list .x{font-family:var(--font-mono,monospace);font-size:11px;color:var(--ink-3,#6B7280);cursor:pointer;border:1px solid var(--line,#e8e6df);border-radius:var(--radius-sm,4px);padding:2px 8px;background:var(--surface,#fff);}' +
    '.dd-fwd-list .x:hover{background:var(--surface-2,#f3f3ee);}' +
    '.dd-fwd-list .items{display:flex;flex-wrap:wrap;gap:6px;}' +
    '.dd-fwd-item{display:inline-flex;align-items:center;gap:8px;border:1px solid var(--line,#e8e6df);border-radius:var(--radius-sm,4px);padding:4px 9px;font-size:11px;}' +
    '.dd-fwd-item a{font-family:var(--font-mono,monospace);color:var(--accent,#2f6fb2);text-decoration:none;}' +
    '.dd-fwd-item a:hover{text-decoration:underline;}' +
    '.dd-fwd-item .an{color:var(--ink-2,#40454e);}' +
    '.dd-fwd-item .st{font-family:var(--font-mono,monospace);font-size:9px;color:var(--ink-4,#9aa0aa);text-transform:uppercase;}' +
    // stat strip
    '.dd-strip{display:flex;gap:1px;background:var(--line,#e8e6df);border:1px solid var(--line,#e8e6df);border-radius:var(--radius,6px);overflow:hidden;margin-bottom:18px;}' +
    '.dd-stat{flex:1;background:var(--surface,#fff);padding:12px 16px;min-width:150px;}' +
    '.dd-stat .lab{display:flex;align-items:center;gap:6px;font-family:var(--font-mono,monospace);font-size:var(--t-label,10.5px);letter-spacing:var(--ls-label,0.1em);text-transform:uppercase;color:var(--ink-3,#6B7280);}' +
    '.dd-dot{width:7px;height:7px;border-radius:50%;display:inline-block;flex:none;}' +
    '.dd-stat .num{font-family:var(--font-serif,Georgia,serif);font-size:26px;font-weight:600;line-height:1.1;margin-top:6px;}' +
    '.dd-stat .sub{font-family:var(--font-mono,monospace);font-size:var(--t-micro,11px);color:var(--ink-3,#6B7280);margin-top:3px;}' +
    // analyst tiles
    '.dd-section-label{font-family:var(--font-mono,monospace);font-size:var(--t-label,10.5px);letter-spacing:var(--ls-label,0.1em);text-transform:uppercase;color:var(--ink-3,#6B7280);margin-bottom:8px;}' +
    '.dd-analysts{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:20px;}' +
    '.dd-atile{border:1px solid var(--line,#e8e6df);background:var(--surface,#fff);border-radius:var(--radius,6px);padding:7px 12px;cursor:pointer;text-align:left;min-width:104px;transition:border-color .12s;}' +
    '.dd-atile:hover{border-color:var(--line-2,#d4d2cb);}' +
    '.dd-atile.on{border-color:var(--accent,#3b6fb5);background:var(--accent-2,#eef3fb);}' +
    '.dd-atile .nm{font-size:var(--t-small,12px);color:var(--ink-2,#40454e);font-weight:500;}' +
    '.dd-atile .ct{font-family:var(--font-serif,Georgia,serif);font-size:18px;color:var(--ink,#1a1d24);line-height:1.15;}' +
    '.dd-atile .bd{font-family:var(--font-mono,monospace);font-size:10px;color:var(--ink-3,#6B7280);}' +
    // table
    '.dd-tablewrap{overflow:auto;max-height:62vh;border:1px solid var(--line,#e8e6df);border-radius:var(--radius,6px);margin-bottom:22px;}' +
    '.dd-table{border-collapse:separate;border-spacing:0;width:100%;min-width:920px;}' +
    '.dd-table thead th{position:sticky;top:0;z-index:2;background:var(--surface-2,#f3f3ee);color:var(--ink-3,#6B7280);font-family:var(--font-mono,monospace);font-size:var(--t-label,10.5px);letter-spacing:0.06em;text-transform:uppercase;font-weight:500;text-align:left;padding:9px 12px;border-bottom:1px solid var(--line-2,#d4d2cb);}' +
    '.dd-table tbody td{padding:9px 12px;font-size:var(--t-small,12px);color:var(--ink,#1a1d24);border-bottom:1px solid var(--line,#e8e6df);vertical-align:top;}' +
    '.dd-table tbody tr:hover td{background:var(--surface-2,#f7f7f3);}' +
    '.dd-mono{font-family:var(--font-mono,monospace);}' +
    '.dd-ecn{font-family:var(--font-mono,monospace);color:var(--accent,#2f6fb2);font-weight:500;text-decoration:none;white-space:nowrap;}' +
    '.dd-ecn:hover{text-decoration:underline;}' +
    '.dd-status{font-family:var(--font-mono,monospace);font-size:10px;color:var(--ink-3,#6B7280);text-transform:uppercase;letter-spacing:0.04em;}' +
    '.dd-analyst{color:var(--ink-2,#40454e);font-weight:500;}' +
    '.dd-clamp{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;max-width:300px;line-height:1.4;cursor:pointer;}' +
    '.dd-clamp.open{-webkit-line-clamp:unset;}' +
    '.dd-chip{display:inline-block;font-family:var(--font-mono,monospace);font-size:10.5px;font-weight:600;padding:2px 8px;border-radius:var(--radius-pill,999px);white-space:nowrap;}' +
    '.dd-chip.crit{background:var(--bad-bg,#fbeaea);color:var(--bad-ink,#a13029);}' +
    '.dd-chip.od{background:var(--warn-bg,#fdf3e0);color:#8a5a12;}' +
    '.dd-chip.soon{background:var(--accent-2,#eef3fb);color:var(--accent-ink,#2b5488);}' +
    '.dd-prio-u{display:inline-block;font-family:var(--font-mono,monospace);font-size:10px;font-weight:600;padding:1px 7px;border-radius:var(--radius-pill,999px);background:var(--bad-bg,#fbeaea);color:var(--bad-ink,#a13029);text-transform:uppercase;letter-spacing:0.04em;}' +
    '.dd-prio-s{font-size:var(--t-small,12px);color:var(--ink-3,#6B7280);}' +
    // ECN Change Classification filter buttons (pill style, matches Cycle Time tab)
    '.dd-ctfilters{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px;}' +
    '.dd-ctbtn{display:inline-flex;align-items:center;gap:6px;font-family:var(--font-sans,sans-serif);font-size:var(--t-small,12px);font-weight:500;color:var(--ink-2,#40454e);background:var(--surface,#fff);border:1px solid var(--line-2,#d4d2cb);border-radius:var(--radius-pill,999px);padding:5px 12px;cursor:pointer;line-height:1;transition:border-color .12s,background .12s,box-shadow .12s;}' +
    '.dd-ctbtn:hover{border-color:var(--accent,#3b6fb5);}' +
    '.dd-ctbtn.on{border-color:var(--accent,#3b6fb5);background:var(--accent-2,#eef3fb);color:var(--accent-ink,#2b5488);box-shadow:inset 0 0 0 1px var(--accent,#3b6fb5);}' +
    '.dd-ctbtn .n{font-family:var(--font-mono,monospace);font-size:10.5px;opacity:0.65;}' +
    // ECN Change Type breakdown tile
    '.dd-ct-card{margin-bottom:20px;}' +
    '.dd-ct-wrap .dd-canvas-wrap{height:auto;min-height:120px;}' +
    // charts
    '.dd-charts{display:grid;grid-template-columns:1fr 1fr;gap:14px;}' +
    '.dd-chart-card{background:var(--surface,#fff);border:1px solid var(--line,#e8e6df);border-radius:var(--radius,6px);padding:12px 14px;}' +
    '.dd-chart-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;}' +
    '.dd-chart-title{font-family:var(--font-mono,monospace);font-size:var(--t-label,10.5px);letter-spacing:0.08em;text-transform:uppercase;color:var(--ink-3,#6B7280);}' +
    '.dd-canvas-wrap{position:relative;height:190px;}' +
    '.dd-chart-msg{font-size:var(--t-small,12px);color:var(--ink-3,#6B7280);padding:22px 6px;line-height:1.5;}' +
    '.dd-mini{border:1px solid var(--line,#e8e6df);background:var(--surface,#fff);border-radius:var(--radius-sm,4px);font-family:var(--font-mono,monospace);font-size:10px;color:var(--ink-2,#40454e);padding:2px 8px;cursor:pointer;}' +
    '@media(max-width:900px){.dd-charts{grid-template-columns:1fr;}}' +
    '</style>';
}

/* ---- semantic colors resolved from tokens (fallbacks for older browsers) ---- */
function ddColor(name) {
    var map = { crit: '--bad', od: '--warn', soon: '--accent',
                critFb: '#c0392b', odFb: '#c7801b', soonFb: '#3b6fb5' };
    try {
        var v = getComputedStyle(document.documentElement).getPropertyValue(map[name]).trim();
        if (v) return v;
    } catch (e) {}
    return name === 'crit' ? map.critFb : name === 'od' ? map.odFb : map.soonFb;
}

/* ---- loading ---- */

function dueDateToggleProj(on) {
    dueDateState.includeProj = !!on;
    dueDateState.analystFilter = null;
    dueDateState.changeTypeFilter = null;
    dueDateLoad();
    // Snapshots track the operational view only — no need to reload the trend (#15).
}

function dueDateLoad() {
    var sub = document.getElementById('dueDateSubtitle');
    if (sub) sub.textContent = 'Loading…';
    fetch('/api/ecn-report/due-date/data?includeProj=' + dueDateState.includeProj, { credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) {
            if (d && d.error) { if (sub) sub.textContent = 'Error: ' + d.error; return; }
            dueDateState.data = d;
            dueDateRender();
        })
        .catch(function (e) { if (sub) sub.textContent = 'Load failed: ' + e; });
}

function dueDateRefresh() {
    var btn = document.getElementById('dueDateRefreshBtn');
    var sub = document.getElementById('dueDateSubtitle');
    if (btn) { btn.disabled = true; btn.innerHTML = '&#8635; Refreshing…'; }
    if (sub) sub.textContent = 'Refreshing from Agile…';
    var t0 = Date.now();
    fetch('/api/ecn-report/due-date/refresh?includeProj=' + dueDateState.includeProj, { method: 'POST', credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) {
            if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; Refresh'; }
            if (d && d.error) { if (sub) sub.textContent = 'Error: ' + d.error; return; }
            dueDateState.data = d;
            var secs = ((Date.now() - t0) / 1000).toFixed(1);
            dueDateRender();
            dueDateLoadTrend();
            if (sub) sub.innerHTML = '&#10003; Refreshed &middot; ' + (d.summary ? d.summary.total : 0) + ' rows &middot; ' + secs + 's';
        })
        .catch(function (e) {
            if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; Refresh'; }
            if (sub) sub.textContent = 'Refresh failed: ' + e;
        });
}

function dueDateDownload() {
    window.location.href = '/api/ecn-report/due-date/download?includeProj=' + dueDateState.includeProj;
}

/* ---- render ---- */

function dueDateRender() {
    var d = dueDateState.data;
    if (!d) return;
    var sub = document.getElementById('dueDateSubtitle');
    if (sub && sub.textContent.indexOf('Refreshed') === -1) {
        var total = (d.summary && d.summary.total) || 0;
        sub.innerHTML = esc(d.reportDate || '') + ' · ' + total + ' ECN' + (total !== 1 ? 's' : '') +
            ' · targets ' + (d.targetStandardDays || 10) + '/' + (d.targetUrgentDays || 6) + ' wd' +
            (d.lastRunAt ? ' · ' + esc(d.lastRunAt) : '');
    }
    dueDateState.forwardRows = d.forwardRows || [];
    dueDateState.selectedFwd = null;
    var lp = document.getElementById('dueDateFwdList');
    if (lp) lp.style.display = 'none';
    dueDateState.changeTypeFilter = null;
    dueDateRenderStrip(d.summary);
    dueDateRenderAnalysts(d.perAnalyst);
    dueDateRenderChangeTypes(d.changeTypeCounts);
    dueDateRenderCtFilters(d.changeTypeCounts);
    dueDateRenderTable(d.rows);
    dueDateDrawForward(d.forwardHistogram);
}

function ddStat(label, bucketKey, bucket) {
    var t = bucket ? bucket.total : 0, u = bucket ? bucket.urgent : 0, s = bucket ? bucket.standard : 0;
    var col = ddColor(bucketKey);
    return '<div class="dd-stat" style="border-top:2px solid ' + col + ';">' +
        '<div class="lab"><span class="dd-dot" style="background:' + col + ';"></span>' + label + '</div>' +
        '<div class="num" style="color:' + col + ';">' + t + '</div>' +
        '<div class="sub">Urgent ' + u + ' &middot; Standard ' + s + '</div>' +
    '</div>';
}

function dueDateRenderStrip(summary) {
    var el = document.getElementById('dueDateStrip');
    if (!el || !summary) return;
    el.innerHTML =
        ddStat('Critical &middot; &gt;10d overdue', 'crit', summary.critical) +
        ddStat('Overdue &middot; 1–10d', 'od', summary.overdue) +
        ddStat('Due soon &middot; ≤1d', 'soon', summary.dueSoon);
}

function dueDateRenderAnalysts(list) {
    var el = document.getElementById('dueDateAnalysts');
    if (!el) return;
    if (!list || !list.length) { el.innerHTML = '<span class="dd-sub">No ECNs in this view.</span>'; return; }
    var total = list.reduce(function (a, x) { return a + x.total; }, 0);
    var allOd = list.reduce(function (a, x) { return a + (x.overdue || 0) + (x.critical || 0); }, 0);
    var allSoon = list.reduce(function (a, x) { return a + (x.dueSoon || 0); }, 0);
    var allBd = [];
    if (allOd) allBd.push('<span style="color:' + ddColor('od') + '">' + allOd + ' od</span>');
    if (allSoon) allBd.push('<span style="color:' + ddColor('soon') + '">' + allSoon + ' soon</span>');
    var html = '<button class="dd-atile' + (dueDateState.analystFilter === null ? ' on' : '') + '" data-analyst="__all__">' +
        '<div class="nm">All analysts</div><div class="ct">' + total + '</div><div class="bd">' + allBd.join(' · ') + '</div></button>';
    for (var i = 0; i < list.length; i++) {
        var a = list[i];
        var on = dueDateState.analystFilter === a.analyst;
        var bd = [];
        if (a.critical) bd.push('<span style="color:' + ddColor('crit') + '">' + a.critical + ' crit</span>');
        if (a.overdue) bd.push('<span style="color:' + ddColor('od') + '">' + a.overdue + ' od</span>');
        if (a.dueSoon) bd.push('<span style="color:' + ddColor('soon') + '">' + a.dueSoon + ' soon</span>');
        html += '<button class="dd-atile' + (on ? ' on' : '') + '" data-analyst="' + esc(a.analyst) + '">' +
            '<div class="nm">' + esc(a.analyst) + '</div>' +
            '<div class="ct">' + a.total + '</div>' +
            '<div class="bd">' + bd.join(' · ') + '</div></button>';
    }
    el.innerHTML = html;
    // #1: bind via data-attribute + listener — never inline a name into a JS string.
    el.querySelectorAll('.dd-atile').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var name = btn.getAttribute('data-analyst');
            dueDateSetAnalyst(name === '__all__' ? null : name);
        });
    });
}

function dueDateSetAnalyst(name) {
    dueDateState.analystFilter = (dueDateState.analystFilter === name) ? null : name;
    dueDateRenderAnalysts(dueDateState.data.perAnalyst);
    dueDateRenderTable(dueDateState.data.rows);
}

/* ---- ECN Change Classification: filter buttons + breakdown bar tile (PT-98) ---- */

// Pill-style filter buttons, one per change type (sorted high->low by the
// backend), plus an "All" reset — same interaction as the Cycle Time tab.
function dueDateRenderCtFilters(list) {
    var el = document.getElementById('dueDateCtFilters');
    var lab = document.getElementById('dueDateCtFilterLabel');
    if (!el) return;
    if (!list || !list.length) {
        el.style.display = 'none';
        if (lab) lab.style.display = 'none';
        el.innerHTML = '';
        return;
    }
    var total = list.reduce(function (a, x) { return a + (x.count || 0); }, 0);
    var html = '<button type="button" class="dd-ctbtn' + (dueDateState.changeTypeFilter === null ? ' on' : '') + '" data-ct="__all__">' +
        'All types <span class="n">(' + total + ')</span></button>';
    for (var i = 0; i < list.length; i++) {
        var c = list[i];
        var on = dueDateState.changeTypeFilter === c.type;
        html += '<button type="button" class="dd-ctbtn' + (on ? ' on' : '') + '" data-ct="' + esc(c.type) + '">' +
            esc(c.type) + ' <span class="n">(' + c.count + ')</span></button>';
    }
    el.innerHTML = html;
    el.style.display = 'flex';
    if (lab) lab.style.display = '';
    // Bind via data-attribute so a change-type label with quotes/specials is never
    // inlined into a JS string (matches the analyst-tile pattern, #1).
    el.querySelectorAll('.dd-ctbtn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var t = btn.getAttribute('data-ct');
            dueDateSetChangeType(t === '__all__' ? null : t);
        });
    });
}

function dueDateSetChangeType(type) {
    dueDateState.changeTypeFilter = (dueDateState.changeTypeFilter === type) ? null : type;
    var d = dueDateState.data;
    if (!d) return;
    dueDateRenderCtFilters(d.changeTypeCounts);
    dueDateDrawChangeTypes(d.changeTypeCounts); // recolor the selected bar
    dueDateRenderTable(d.rows);
}

// Horizontal bar graph of ECN counts per change type, sorted high->low.
function dueDateRenderChangeTypes(list) {
    var wrap = document.getElementById('dueDateCtWrap');
    var msg = document.getElementById('dueDateCtMsg');
    if (!wrap || !msg) return;
    if (!list || !list.length) {
        dueDateDestroy('dueDateCtChart');
        wrap.style.display = 'none';
        msg.style.display = 'block';
        msg.textContent = 'No ECNs to classify in this view.';
        return;
    }
    msg.style.display = 'none';
    wrap.style.display = 'block';
    // Size the canvas to the row count so bars stay readable (~26px/row + padding).
    var cw = document.getElementById('dueDateCtCanvasWrap');
    if (cw) cw.style.height = Math.max(120, list.length * 26 + 24) + 'px';
    dueDateDrawChangeTypes(list);
}

function ddCtColor(type) {
    // Selected type -> accent; everything else -> a muted neutral bar.
    if (dueDateState.changeTypeFilter && type === dueDateState.changeTypeFilter) return ddColor('soon');
    if (dueDateState.changeTypeFilter) return 'rgba(150,150,150,0.35)';
    return ddColor('soon');
}

function dueDateDrawChangeTypes(list) {
    var canvas = document.getElementById('dueDateCtChart');
    if (!canvas || typeof Chart === 'undefined' || !list || !list.length) return;
    dueDateState.ctList = list;
    dueDateDestroy('dueDateCtChart');
    var labels = [], data = [], colors = [];
    for (var i = 0; i < list.length; i++) {
        labels.push(list[i].type);
        data.push(list[i].count);
        colors.push(ddCtColor(list[i].type));
    }
    dueDateState.charts['dueDateCtChart'] = new Chart(canvas, {
        type: 'bar',
        data: { labels: labels, datasets: [{ label: 'ECNs', data: data, backgroundColor: colors, borderRadius: 2, barThickness: 16, maxBarThickness: 18 }] },
        options: {
            indexAxis: 'y',
            responsive: true, maintainAspectRatio: false,
            onClick: function (evt, els) {
                if (!els || !els.length) return;
                var t = dueDateState.ctList[els[0].index];
                if (t) dueDateSetChangeType(t.type === dueDateState.changeTypeFilter ? null : t.type);
            },
            onHover: function (evt, els) { evt.native.target.style.cursor = els && els.length ? 'pointer' : 'default'; },
            plugins: {
                legend: { display: false },
                tooltip: { callbacks: { label: function (c) { return c.parsed.x + ' ECN' + (c.parsed.x !== 1 ? 's' : ''); } } }
            },
            scales: {
                x: { beginAtZero: true, ticks: { precision: 0, font: { size: 10 } }, grid: { color: 'rgba(0,0,0,0.05)' } },
                y: { grid: { display: false }, ticks: { font: { size: 10 }, autoSkip: false } }
            }
        }
    });
}

function ddDaysChip(bucket, label) {
    var cls = bucket === 'critical' ? 'crit' : bucket === 'overdue' ? 'od' : 'soon';
    return '<span class="dd-chip ' + cls + '">' + esc(label) + '</span>';
}

function dueDateRenderTable(rows) {
    var el = document.getElementById('dueDateTableWrap');
    if (!el) return;
    var filtered = rows || [];
    if (dueDateState.analystFilter) filtered = filtered.filter(function (r) { return r.analyst === dueDateState.analystFilter; });
    if (dueDateState.changeTypeFilter) filtered = filtered.filter(function (r) { return (r.changeType || 'Unclassified') === dueDateState.changeTypeFilter; });
    if (!filtered.length) {
        el.innerHTML = '<div style="padding:22px;text-align:center;color:var(--ink-3,#6B7280);font-size:13px;">' +
            (dueDateState.analystFilter || dueDateState.changeTypeFilter
                ? 'No ECNs match the current filter' +
                    (dueDateState.analystFilter ? ' — analyst \u201c' + esc(dueDateState.analystFilter) + '\u201d' : '') +
                    (dueDateState.changeTypeFilter ? ' — change type \u201c' + esc(dueDateState.changeTypeFilter) + '\u201d' : '') + '.'
                : 'No overdue or due-soon ECNs right now.') +
            '</div>';
        return;
    }
    var html = '<table class="dd-table"><thead><tr>' +
        '<th>ECN</th><th>Product Line</th><th>Analyst</th><th>Proposal</th>' +
        '<th>Due</th><th>Priority</th><th>Days</th><th>Comments / Notes</th>' +
        '</tr></thead><tbody>';
    for (var i = 0; i < filtered.length; i++) {
        var r = filtered[i];
        var prio = r.isUrgent ? '<span class="dd-prio-u">Urgent</span>' : '<span class="dd-prio-s">Standard</span>';
        html += '<tr>' +
            '<td><a class="dd-ecn" href="' + esc(r.link) + '" target="_blank">' + esc(r.ecnNumber) + '</a>' +
                '<div class="dd-status">' + esc(r.status) + '</div></td>' +
            '<td>' + esc(r.productLine) + '</td>' +
            '<td class="dd-analyst">' + esc(r.analyst) + '</td>' +
            '<td><div class="dd-clamp" onclick="this.classList.toggle(\'open\')">' + esc(r.proposal) + '</div></td>' +
            '<td class="dd-mono" style="white-space:nowrap;">' + esc(r.targetDueDate) + '</td>' +
            '<td>' + prio + '</td>' +
            '<td>' + ddDaysChip(r.bucket, r.daysLabel) + '</td>' +
            '<td>' + esc(r.notes) + '</td>' +
        '</tr>';
    }
    html += '</tbody></table>';
    el.innerHTML = html;
}

/* ---- charts ---- */

function dueDateDestroy(id) {
    if (dueDateState.charts[id]) { try { dueDateState.charts[id].destroy(); } catch (e) {} delete dueDateState.charts[id]; }
}

function ddFwdColor(hist, i) {
    if (dueDateState.selectedFwd && hist[i].date === dueDateState.selectedFwd) return ddColor('crit'); // selected = darker/red
    return hist[i].weekend ? 'rgba(150,150,150,0.35)' : ddColor('soon');
}

function dueDateDrawForward(hist) {
    var canvas = document.getElementById('dueDateForwardChart');
    if (!canvas || typeof Chart === 'undefined' || !hist) return;
    dueDateState.forwardHist = hist;
    dueDateDestroy('dueDateForwardChart');
    var labels = [], data = [], colors = [];
    var peak = 0, peakLabel = '';
    for (var i = 0; i < hist.length; i++) {
        labels.push(hist[i].label);
        data.push(hist[i].count);
        colors.push(ddFwdColor(hist, i));
        if (hist[i].count > peak) { peak = hist[i].count; peakLabel = hist[i].label; }
    }
    // Axis annotation: first · peak (+ how-to) · last — matches the v2 mock.
    var axis = document.getElementById('dueDateFwdAxis');
    var fwdDays = document.getElementById('dueDateFwdDays');
    if (fwdDays && dueDateState.data) fwdDays.textContent = dueDateState.data.forwardDays || hist.length;
    if (axis && hist.length) {
        axis.innerHTML = '<span>' + esc(hist[0].label) + '</span>' +
            (peak > 0 ? '<span class="mid">' + esc(peakLabel) + ' · peak ' + peak + ' · click a bar to list its ECNs</span>' : '<span class="mid">no ECNs due</span>') +
            '<span>' + esc(hist[hist.length - 1].label) + '</span>';
    }
    dueDateState.charts['dueDateForwardChart'] = new Chart(canvas, {
        type: 'bar',
        data: { labels: labels, datasets: [{ label: 'ECNs due', data: data, backgroundColor: colors, borderRadius: 2 }] },
        options: {
            responsive: true, maintainAspectRatio: false,
            onClick: function (evt, els) {
                if (!els || !els.length) return;
                var idx = els[0].index;
                var h = dueDateState.forwardHist[idx];
                if (!h || h.count === 0) return;
                dueDateSelectFwd(h.date === dueDateState.selectedFwd ? null : h.date);
            },
            onHover: function (evt, els) { evt.native.target.style.cursor = els && els.length ? 'pointer' : 'default'; },
            plugins: {
                legend: { display: false },
                tooltip: { callbacks: { label: function (c) { return c.parsed.y + ' ECN' + (c.parsed.y !== 1 ? 's' : '') + ' due'; } } }
            },
            scales: {
                x: { grid: { display: false }, ticks: { font: { size: 9 }, maxRotation: 60, minRotation: 60, autoSkip: true, maxTicksLimit: 14 } },
                y: { beginAtZero: true, ticks: { precision: 0, font: { size: 10 } }, grid: { color: 'rgba(0,0,0,0.05)' } }
            }
        }
    });
}

// #29: clicking a bar lists the ECNs due that date (they're future dues, not in
// the overdue table — so we show them in their own panel below the chart).
function dueDateSelectFwd(dateIso) {
    dueDateState.selectedFwd = dateIso;
    // recolor bars without a full rebuild
    var ch = dueDateState.charts['dueDateForwardChart'];
    if (ch) {
        ch.data.datasets[0].backgroundColor = dueDateState.forwardHist.map(function (h, i) { return ddFwdColor(dueDateState.forwardHist, i); });
        ch.update();
    }
    dueDateRenderFwdList();
}

function dueDateRenderFwdList() {
    var el = document.getElementById('dueDateFwdList');
    if (!el) return;
    if (!dueDateState.selectedFwd) { el.style.display = 'none'; el.innerHTML = ''; return; }
    var iso = dueDateState.selectedFwd;
    var items = (dueDateState.forwardRows || []).filter(function (r) { return r.dueIso === iso; });
    var label = iso;
    var barsMatch = (dueDateState.forwardHist || []).filter(function (h) { return h.date === iso; });
    if (barsMatch.length) label = barsMatch[0].label;
    var html = '<div class="hd"><div class="ttl"><b>' + esc(label) + '</b> · ' + items.length + ' ECN' + (items.length !== 1 ? 's' : '') + ' due</div>' +
        '<button class="x" onclick="dueDateSelectFwd(null)">&times; clear</button></div>';
    html += '<div class="items">';
    for (var i = 0; i < items.length; i++) {
        var it = items[i];
        var prio = it.isUrgent ? '<span class="dd-prio-u">Urgent</span>' : '';
        html += '<span class="dd-fwd-item"><a href="' + esc(it.link) + '" target="_blank">' + esc(it.ecnNumber) + '</a>' +
            '<span class="an">' + esc(it.analyst) + '</span>' + prio +
            '<span class="st">' + esc(it.status) + '</span></span>';
    }
    html += '</div>';
    el.innerHTML = html;
    el.style.display = 'block';
}

function dueDateLoadTrend() {
    fetch('/api/ecn-report/due-date/trend', { credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) { dueDateState.trendSeries = (d && d.series) ? d.series : []; dueDateRenderTrend(); })
        .catch(function () { dueDateState.trendSeries = []; dueDateRenderTrend(); });
}

function dueDateToggleTrend() {
    dueDateState.trendExpanded = !dueDateState.trendExpanded;
    dueDateRenderTrend();
}

// #22/#28: don't render a lonely dot / a flat line. Minimize with a reason until
// there's enough signal, with an expand toggle.
function dueDateRenderTrend() {
    var series = dueDateState.trendSeries || [];
    var body = document.getElementById('dueDateTrendBody');
    var msg = document.getElementById('dueDateTrendMsg');
    var toggle = document.getElementById('dueDateTrendToggle');
    if (!body || !msg || !toggle) return;

    var totals = series.map(function (p) { return p.total || 0; });
    var max = totals.length ? Math.max.apply(null, totals) : 0;
    var min = totals.length ? Math.min.apply(null, totals) : 0;
    var lowSignal = series.length < 3 || (max - min) <= 3;

    if (lowSignal && !dueDateState.trendExpanded) {
        dueDateDestroy('dueDateTrendChart');
        body.style.display = 'none';
        msg.style.display = 'block';
        var latest = series.length ? series[series.length - 1] : null;
        if (series.length === 0) {
            msg.textContent = 'No snapshots yet — the trend builds one point per day as the scheduled refresh runs.';
            toggle.style.display = 'none';
        } else if (series.length < 3) {
            msg.innerHTML = series.length + ' snapshot' + (series.length !== 1 ? 's' : '') + ' so far — ' +
                (latest ? latest.total + ' total on ' + esc(latest.date) : '') + '. The trend builds one point per day.';
            toggle.style.display = '';
            toggle.textContent = 'show anyway →';
        } else {
            msg.innerHTML = 'Minimized — backlog near-flat over ' + series.length + ' days (' + min + '–' + max + ' total, ±' + (max - min) + '). Nothing trending.';
            toggle.style.display = '';
            toggle.textContent = 'expand →';
        }
        return;
    }

    msg.style.display = 'none';
    body.style.display = 'block';
    toggle.style.display = series.length >= 3 ? '' : 'none';
    toggle.textContent = 'minimize';
    dueDateDrawTrend(series);
}

function dueDateDrawTrend(series) {
    var canvas = document.getElementById('dueDateTrendChart');
    if (!canvas || typeof Chart === 'undefined') return;
    dueDateDestroy('dueDateTrendChart');
    var labels = [], crit = [], od = [], soon = [];
    for (var i = 0; i < series.length; i++) {
        labels.push(series[i].date);
        crit.push(series[i].critical || 0);
        od.push(series[i].overdue || 0);
        soon.push(series[i].dueSoon || 0);
    }
    var pr = series.length > 30 ? 0 : 2;
    dueDateState.charts['dueDateTrendChart'] = new Chart(canvas, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [
                { label: 'Critical', data: crit, borderColor: ddColor('crit'), backgroundColor: 'transparent', tension: 0.25, pointRadius: pr, borderWidth: 1.5 },
                { label: 'Overdue', data: od, borderColor: ddColor('od'), backgroundColor: 'transparent', tension: 0.25, pointRadius: pr, borderWidth: 1.5 },
                { label: 'Due Soon', data: soon, borderColor: ddColor('soon'), backgroundColor: 'transparent', tension: 0.25, pointRadius: pr, borderWidth: 1.5 }
            ]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 10 } } } },
            scales: {
                x: { grid: { display: false }, ticks: { font: { size: 9 }, autoSkip: true, maxTicksLimit: 10 } },
                y: { beginAtZero: true, ticks: { precision: 0, font: { size: 10 } }, grid: { color: 'rgba(0,0,0,0.05)' } }
            }
        }
    });
}
