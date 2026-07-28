// ============================================================================
// Weekly Rejection Report — sub-view inside the ECN Dashboard tab.
//
// Companion to ecnreport.js / returnstracker.js. Wraps the Python engine
// (weekly_rejection_report.py) exposed by /api/ecn-report/rejection/*.
//
// Distinct from the Returns Tracker: this captures the explicit Reject workflow
// action (CHANGE_HISTORY event 13) across ALL change classes, 6 AI buckets, plus
// the Days-in-CCB aging analysis + manual-fill workflow.
//
// The view's DOM is built here (vanilla, like the mockup) into #rejectionReportView;
// the drawer/scrim/toast are appended to <body> once.
// ============================================================================

var rejectionState = {
    lookback: 7,
    data: null,
    loading: false,
    domBuilt: false,
    inited: false,
    columnFilters: {}
};

// 6-bucket category palette (muted, toolkit-friendly — matches the mockup).
var RR_CAT_COLORS = {
    'Ambiguous Request':        '#4a6fa5',
    'Wrong Information':         '#B8342B',
    'Incomplete Documentation': '#C7801B',
    'Insufficient Information': '#d9803a',
    'Duplicate Request':        '#7d5ba6',
    'Wrong Proposal':           '#9C6500'
};
// Change-type palette (shared with the Excel/email engine's CT_COLORS).
var RR_CT_COLORS = {
    'ECO': '#2980b9', 'MCO': '#27ae60', 'DCO': '#8e44ad', 'ECN': '#d35400',
    'AML': '#c0392b', 'Deviation': '#C7801B', 'DRR': '#7f8c8d', 'QCO': '#2c3e50',
    'SpecialOrders': '#e74c3c', 'Special Order': '#e74c3c', 'PDRNew': '#16a085',
    'NMA': '#1abc9c', 'TRC': '#95a5a6'
};

function rrEsc(s) {
    if (s === null || s === undefined) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function rrCtColor(t) { return RR_CT_COLORS[t] || '#6B7280'; }
function rrCatColor(c) { return RR_CAT_COLORS[c] || '#6B7280'; }
function rrHexBg(hex) {
    var h = (hex || '#888').replace('#', '');
    if (h.length < 6) return 'rgba(136,136,136,0.10)';
    var r = parseInt(h.substr(0, 2), 16), g = parseInt(h.substr(2, 2), 16), b = parseInt(h.substr(4, 2), 16);
    return 'rgba(' + r + ',' + g + ',' + b + ',0.10)';
}
function rrCcbClass(d) { return d == null ? '' : (d <= 7 ? 'g' : (d <= 14 ? 'y' : 'r')); }

// ---------------------------------------------------------------------------
// Entry point (called by ecnSwitchView('rejection'))
// ---------------------------------------------------------------------------
function rejectionInit() {
    rejectionEnsureDom();
    rejectionState.inited = true;
    rejectionLoad();
}

function rejectionEnsureDom() {
    if (rejectionState.domBuilt) return;
    var view = document.getElementById('rejectionReportView');
    if (!view) return;

    // Scoped styles (self-contained so the look matches the mockup regardless of tokens.css).
    if (!document.getElementById('rrStyle')) {
        var st = document.createElement('style');
        st.id = 'rrStyle';
        st.textContent = rrCss();
        document.head.appendChild(st);
    }

    view.innerHTML =
        '<div class="rr">' +
        // controls strip
        '<div class="rr-controls">' +
            '<span class="rr-lbl">Period:</span>' +
            '<select class="rr-btn" id="rrPeriod" onchange="rejectionOnPeriod(this.value)">' +
                '<option value="7">Last 7 days (weekly)</option>' +
                '<option value="30">Last 30 days</option>' +
                '<option value="90">Last 90 days</option>' +
            '</select>' +
            '<button class="rr-btn" onclick="rejectionRefresh()" title="Re-run the query + AI categorization">&#8635; Refresh</button>' +
            '<span class="rr-grow"></span>' +
            '<span class="rr-pill" id="rrGatewayPill">AI <b>—</b></span>' +
            '<span class="rr-pill" id="rrCatPill">categorized <b>—</b></span>' +
            '<button class="rr-btn" id="rrExportBtn" onclick="rejectionExport()">&#11015; Export Excel</button>' +
            '<button class="rr-btn primary" id="rrSendBtn" onclick="rejectionSendToMe()">&#9993; Send to me</button>' +
        '</div>' +
        '<div class="rr-status" id="rrStatus">Loading…</div>' +
        // manual-fill gate
        '<div id="rrManualFill"></div>' +
        // KPI strip
        '<div class="rr-kpis" id="rrKpis"></div>' +
        // narrative
        '<div class="rr-group rr-narr" id="rrNarrCard" style="display:none;">' +
            '<div class="rr-group-head"><h3>Executive Summary <span class="sub">data-derived · updates per period</span></h3></div>' +
            '<div class="rr-group-body" id="rrNarrBody"></div>' +
        '</div>' +
        // categories + aging
        '<div class="rr-grid2">' +
            '<div class="rr-group"><div class="rr-group-head"><h3>Rejection Categories <span class="sub">AI-classified · 6 buckets</span></h3></div><div class="rr-group-body" id="rrCatPanel"></div></div>' +
            '<div class="rr-group"><div class="rr-group-head"><h3>Days in CCB — Aging <span class="sub">from status transitions</span></h3></div><div class="rr-group-body" id="rrAgingPanel"></div></div>' +
        '</div>' +
        // trend
        '<div class="rr-group"><div class="rr-group-head"><h3>Rejection Trend <span class="sub">last 8 weeks · total rejections</span></h3></div><div class="rr-group-body" id="rrTrendPanel"></div></div>' +
        // requestors + change type
        '<div class="rr-grid2">' +
            '<div class="rr-group"><div class="rr-group-head"><h3>Top Repeat Requestors <span class="sub">&ge;2 rejected changes in window</span></h3></div><div class="rr-group-body" style="padding:4px 8px 8px" id="rrReqPanel"></div></div>' +
            '<div class="rr-group"><div class="rr-group-head"><h3>Rejections by Change Type <span class="sub">all change classes</span></h3></div><div class="rr-group-body" id="rrCtPanel"></div></div>' +
        '</div>' +
        // events table
        '<div class="rr-group">' +
            '<div class="rr-group-head"><h3>Rejection Events <span class="sub" id="rrEvtCount"></span></h3>' +
            '<div style="font-size:11px;color:#6B7280">CCB heat · AI category · full CCB chain on row open</div></div>' +
            '<div class="rr-events-wrap" id="rrEventsWrap"></div>' +
        '</div>' +
        '</div>';

    // drawer + scrim + toast (appended to body once)
    if (!document.getElementById('rrDrawer')) {
        var drawer = document.createElement('aside');
        drawer.className = 'rr-drawer';
        drawer.id = 'rrDrawer';
        drawer.innerHTML =
            '<div class="rr-drawer-head">' +
                '<div><div id="rrDChg" style="font-family:monospace;font-weight:600;color:#4a6fa5;font-size:15px;"></div>' +
                '<div class="rr-ct" id="rrDMeta"></div></div>' +
                '<button class="rr-x" onclick="rejectionCloseDrawer()">&times;</button>' +
            '</div>' +
            '<div class="rr-drawer-body">' +
                '<div class="rr-dsec"><div class="rr-h">AI Category</div><div class="rr-aibox"><b id="rrDCat"></b><br><span id="rrDCatWhy" style="color:#3a4150"></span></div></div>' +
                '<div class="rr-dsec"><div class="rr-h">Description of Change</div><div id="rrDDesc" style="font-size:13px;color:#3a4150;line-height:1.55"></div></div>' +
                '<div class="rr-dsec"><div class="rr-h">Reject Comment</div><div id="rrDComment" style="font-size:13px;color:#0F1720;line-height:1.55;background:#FAFAF7;border:1px solid #E8E6DF;border-radius:6px;padding:10px 12px;white-space:pre-wrap"></div></div>' +
                '<div class="rr-dsec"><div class="rr-h">CCB Comment Chain</div><div class="rr-chain" id="rrDChain"></div></div>' +
            '</div>';
        document.body.appendChild(drawer);
        var scrim = document.createElement('div');
        scrim.className = 'rr-scrim';
        scrim.id = 'rrScrim';
        scrim.onclick = rejectionCloseDrawer;
        document.body.appendChild(scrim);
    }

    rejectionState.domBuilt = true;
}

// ---------------------------------------------------------------------------
// Load / refresh
// ---------------------------------------------------------------------------
function rejectionOnPeriod(v) {
    rejectionState.lookback = parseInt(v, 10) || 7;
    rejectionLoad();
}

function rejectionLoad() {
    if (rejectionState.loading) return;
    rejectionState.loading = true;
    var statusEl = document.getElementById('rrStatus');
    if (statusEl) { statusEl.textContent = 'Loading…'; statusEl.style.color = '#6B7280'; }
    fetch('/api/ecn-report/rejection/data?lookbackDays=' + rejectionState.lookback)
        .then(function (r) { return r.json(); })
        .then(function (d) {
            rejectionState.loading = false;
            if (!d.success) {
                if (statusEl) {
                    statusEl.textContent = d.message || 'No data. Click Refresh to generate.';
                    statusEl.style.color = '#B8342B';
                }
                rejectionState.data = null;
                return;
            }
            rejectionState.data = d;
            rejectionRender(d);
        })
        .catch(function (err) {
            rejectionState.loading = false;
            if (statusEl) statusEl.textContent = 'Error: ' + err.message;
        });
}

function rejectionRefresh() {
    var btn = (typeof event !== 'undefined' && event && event.target) ? event.target : null;
    if (btn) { btn.disabled = true; btn.innerHTML = '&#8635; Refreshing…'; }
    fetch('/api/ecn-report/rejection/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lookbackDays: rejectionState.lookback })
    })
    .then(function (r) { return r.json(); })
    .then(function (d) {
        if (!d.success) {
            if (typeof appAlert === 'function') appAlert(d.message || 'Refresh failed'); else alert(d.message || 'Refresh failed');
            if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; Refresh'; }
            return;
        }
        var statusEl = document.getElementById('rrStatus');
        var attempts = 0;
        var lastLog = 'Starting refresh — connecting to Agile and AI…';
        var frames = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
        var fi = 0;
        var prevGen = rejectionState.data ? rejectionState.data.generatedAt : null;
        if (statusEl) { statusEl.style.color = '#0F1720'; statusEl.textContent = frames[0] + ' ' + lastLog; }
        var spin = setInterval(function () {
            fi = (fi + 1) % frames.length;
            if (btn && btn.disabled && statusEl) statusEl.textContent = frames[fi] + ' ' + lastLog;
        }, 200);
        var poll = setInterval(function () {
            attempts++;
            if (attempts > 300) {  // 15 min
                clearInterval(poll); clearInterval(spin);
                if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; Refresh'; }
                if (statusEl) statusEl.textContent = 'Refresh timed out after 15 min — reload the page in a few minutes.';
                return;
            }
            fetch('/api/ecn-report/rejection/data?lookbackDays=' + rejectionState.lookback)
                .then(function (r) { return r.json(); })
                .then(function (d2) {
                    if (d2.refreshStatus === 'failed') {
                        clearInterval(poll); clearInterval(spin);
                        if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; Refresh'; }
                        if (statusEl) {
                            statusEl.textContent = 'Refresh failed: ' + (d2.refreshError || 'unknown error') + ' — check the server log.';
                            statusEl.style.color = '#B8342B';
                        }
                        return;
                    }
                    if (d2.refreshLogTail) lastLog = d2.refreshLogTail;
                    if (d2.success && d2.generatedAt && d2.generatedAt !== prevGen) {
                        clearInterval(poll); clearInterval(spin);
                        rejectionState.data = d2;
                        rejectionRender(d2);
                        if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; Refresh'; }
                    }
                });
        }, 3000);
    });
}

// ---------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------
function rejectionRender(d) {
    var k = d.kpis || {};
    var period = d.period || {};
    var statusEl = document.getElementById('rrStatus');
    if (statusEl) {
        var line = 'Period ' + (period.from || '?') + ' → ' + (period.to || '?') +
            ' · ' + (k.total || 0) + ' rejections across ' + (k.uniqueChanges || 0) + ' changes' +
            ' · AI categorized ' + (k.categorized || 0) + '/' + (k.total || 0);
        if (d.generatedAt) line += ' · generated ' + d.generatedAt.replace('T', ' ');
        statusEl.innerHTML = rrEsc(line) + (d.stale ? ' <span style="color:#C7801B;font-weight:600;">· stale for this period — click Refresh</span>' : '');
        statusEl.style.color = '#0F1720';
    }
    // pills
    var gp = document.getElementById('rrGatewayPill');
    if (gp) gp.innerHTML = 'AI <b>' + rrEsc(d.model || '—') + '</b>';
    var cp = document.getElementById('rrCatPill');
    if (cp) cp.innerHTML = 'categorized <b>' + (k.categorized || 0) + '/' + (k.total || 0) + '</b>';

    rejectionRenderManualFill(d.manualFill || [], d.readyToExport !== false);
    rejectionRenderKpis(k, d.trendWeeks || [], d.ccbThresholdDays || 14);
    rejectionRenderNarrative(d);
    rejectionRenderCategories(d.categories || []);
    rejectionRenderAging(d.aging || {});
    rejectionRenderTrend(d.trendWeeks || []);
    rejectionRenderRequestors(d.topRequestors || []);
    rejectionRenderChangeTypes(d.byChangeType || []);
    rejectionRenderEvents(d.events || []);
    rejectionUpdateGateButtons(d.manualFill || []);
}

function rejectionRenderManualFill(gaps, ready) {
    var el = document.getElementById('rrManualFill');
    if (!el) return;
    if (!gaps.length) {
        el.innerHTML = '';
        return;
    }
    var rows = gaps.map(function (g) {
        return '<div class="rr-mf-row" data-chg="' + rrEsc(g.changeNumber) + '" data-field="' + rrEsc(g.field) + '">' +
            '<span class="rr-mf-chg">' + rrEsc(g.changeNumber) + '</span>' +
            '<span class="rr-mf-why">' + rrEsc(g.reason || (g.field + ' — manual lookup')) + '</span>' +
            '<input type="text" placeholder="Enter value…" onchange="rejectionSaveManualFill(this)">' +
            '</div>';
    }).join('');
    el.innerHTML =
        '<div class="rr-mf">' +
            '<div class="rr-mf-head">' +
                '<div class="rr-mf-ico">!</div>' +
                '<div><div class="rr-mf-title">' + gaps.length + ' cell' + (gaps.length > 1 ? 's' : '') + ' need manual review before export</div>' +
                '<div class="rr-mf-sub">The script can’t resolve <b>Program Name</b> for DRR / Special Order changes — fill from the Agile Cover Page. Saved values flow into the Excel + email.</div></div>' +
                '<div class="rr-mf-count">' + gaps.length + ' pending</div>' +
            '</div>' +
            '<div class="rr-mf-rows">' + rows + '</div>' +
        '</div>';
}

function rejectionSaveManualFill(input) {
    var row = input.closest('.rr-mf-row');
    if (!row) return;
    var changeNumber = row.getAttribute('data-chg');
    var field = row.getAttribute('data-field');
    var value = input.value.trim();
    if (!value) return;
    input.disabled = true;
    fetch('/api/ecn-report/rejection/manual-fill', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ changeNumber: changeNumber, field: field, value: value })
    })
    .then(function (r) { return r.json(); })
    .then(function (d) {
        input.disabled = false;
        if (!d.success) {
            if (typeof appAlert === 'function') appAlert(d.message || 'Save failed'); else alert(d.message || 'Save failed');
            return;
        }
        // Patch in-memory state + re-render the gate + buttons.
        if (rejectionState.data) {
            rejectionState.data.manualFill = d.manualFill || [];
            rejectionState.data.readyToExport = d.readyToExport;
        }
        rejectionRenderManualFill(d.manualFill || [], d.readyToExport !== false);
        rejectionUpdateGateButtons(d.manualFill || []);
    })
    .catch(function (err) {
        input.disabled = false;
        if (typeof appAlert === 'function') appAlert('Save failed: ' + err.message);
    });
}

function rejectionUpdateGateButtons(gaps) {
    var pending = (gaps || []).length;
    var ex = document.getElementById('rrExportBtn');
    var sb = document.getElementById('rrSendBtn');
    [ex, sb].forEach(function (b) {
        if (!b) return;
        if (pending > 0) { b.classList.add('rr-gated'); b.title = pending + ' manual-fill cell(s) pending — you can still send/export with confirm'; }
        else { b.classList.remove('rr-gated'); b.title = ''; }
    });
}

function rejectionRenderKpis(k, trend, threshold) {
    var el = document.getElementById('rrKpis');
    if (!el) return;
    var total = k.total || 0, unique = k.uniqueChanges || 0, rejectors = k.rejectors || 0;
    var avg = (k.avgDaysInCcb != null) ? k.avgDaysInCcb : 0;
    var topCat = k.topCategory || '—', topPct = k.topCategoryPct || 0;
    var avgColor = avg > threshold ? '#B8342B' : (avg > 7 ? '#C7801B' : '#1F8A4C');
    // WoW from trend
    var wow = '';
    if (trend.length >= 2) {
        var cur = trend[trend.length - 1].total, prev = trend[trend.length - 2].total;
        var delta = cur - prev;
        wow = delta === 0 ? '· flat vs prior week'
            : (delta > 0 ? '<span class="up">▲ ' + delta + '</span> vs prior week'
                         : '<span class="down">▼ ' + Math.abs(delta) + '</span> vs prior week');
    }
    var perChange = unique > 0 ? (total / unique).toFixed(2) + ' rejections / change' : '';
    function kpi(label, value, extra, color) {
        return '<div class="rr-kpi"><div class="k">' + rrEsc(label) + '</div>' +
            '<div class="v"' + (color ? ' style="color:' + color + '"' : '') + '>' + value + '</div>' +
            '<div class="d">' + (extra || '') + '</div></div>';
    }
    el.innerHTML =
        kpi('Total Rejections', total, wow, '') +
        kpi('Unique Changes', unique, perChange, '') +
        kpi('Rejectors', rejectors, 'distinct reviewers', '#4a6fa5') +
        kpi('Avg Days in CCB', avg, 'threshold ' + threshold, avgColor) +
        '<div class="rr-kpi"><div class="k">Top Category</div>' +
            '<div class="v" style="font-size:17px;padding-top:4px;color:' + rrCatColor(topCat) + '">' + rrEsc(topCat) + '</div>' +
            '<div class="d">' + topPct + '% of all</div></div>';
}

function rejectionRenderNarrative(d) {
    var card = document.getElementById('rrNarrCard');
    var body = document.getElementById('rrNarrBody');
    if (!card || !body) return;
    var k = d.kpis || {};
    if (!(k.total > 0)) { card.style.display = 'none'; return; }
    card.style.display = '';
    var trend = d.trendWeeks || [];
    var wowTxt = '';
    if (trend.length >= 2) {
        var cur = trend[trend.length - 1].total, prev = trend[trend.length - 2].total;
        if (prev > 0) {
            var pct = Math.round((cur - prev) / prev * 100);
            wowTxt = (pct >= 0 ? 'up ' : 'down ') + Math.abs(pct) + '% from the prior week';
        }
    }
    var topCT = (d.byChangeType || []).slice(0, 2).map(function (c) { return c.type; }).filter(Boolean);
    var avg = k.avgDaysInCcb || 0;
    var thresh = d.ccbThresholdDays || 14;
    var parts = [];
    parts.push('Rejection volume was <b>' + (k.total || 0) + ' across ' + (k.uniqueChanges || 0) + ' changes</b> this period' + (wowTxt ? ', ' + wowTxt : '') + '.');
    if (topCT.length) parts.push(' Activity concentrated in <b>' + topCT.map(rrEsc).join('</b> and <b>') + '</b> change types.');
    if (k.topCategory && k.topCategory !== '—') parts.push(' <b>' + rrEsc(k.topCategory) + '</b> is the dominant driver (' + (k.topCategoryPct || 0) + '%).');
    parts.push(' Average Days in CCB is <b>' + avg + '</b> against a ' + thresh + '-day threshold' + (avg > thresh ? ' — above target.' : '.'));
    body.innerHTML = parts.join('');
}

function rejectionRenderCategories(cats) {
    var el = document.getElementById('rrCatPanel');
    if (!el) return;
    var max = Math.max.apply(null, cats.map(function (c) { return c.count; }).concat([1]));
    el.innerHTML = cats.map(function (c) {
        var color = rrCatColor(c.name);
        var w = Math.round(c.count / max * 100);
        return '<div class="rr-catbar"><div class="nm"><span class="sw" style="background:' + color + '"></span>' + rrEsc(c.name) + '</div>' +
            '<div class="track"><div class="fill" style="width:' + w + '%;background:' + color + '"></div></div>' +
            '<div class="ct" style="color:' + color + '">' + c.count + '</div></div>';
    }).join('');
}

function rejectionRenderAging(aging) {
    var el = document.getElementById('rrAgingPanel');
    if (!el) return;
    var bands = [
        { key: '0-7', label: '0–7 days', color: '#1F8A4C' },
        { key: '8-14', label: '8–14 days', color: '#C7801B' },
        { key: '14-30', label: '14–30 days', color: '#d9803a' },
        { key: '30+', label: '30+ days', color: '#B8342B' }
    ];
    var cells = bands.map(function (b) {
        return '<div class="rr-aband"><div class="n" style="color:' + b.color + '">' + (aging[b.key] || 0) + '</div><div class="l">' + b.label + '</div></div>';
    }).join('');
    el.innerHTML = '<div class="rr-aging">' + cells + '</div>' +
        '<div class="rr-aging-note"><span><i style="background:#1F8A4C"></i>healthy &le;7</span>' +
        '<span><i style="background:#C7801B"></i>watch 8–14</span>' +
        '<span><i style="background:#B8342B"></i>escalate &gt;14</span>' +
        '<span style="margin-left:auto;color:#9CA3AF">heat-map matches the Excel CCB column</span></div>';
}

function rejectionRenderTrend(weeks) {
    var el = document.getElementById('rrTrendPanel');
    if (!el) return;
    if (!weeks.length) { el.innerHTML = '<div style="color:#6B7280;padding:20px;text-align:center;">No trend data</div>'; return; }
    var vals = weeks.map(function (w) { return w.total; });
    var W = 760, H = 150, pad = 24;
    var mx = Math.max.apply(null, vals), mn = Math.min.apply(null, vals);
    if (mx === mn) mx = mn + 1;
    var step = weeks.length > 1 ? (W - pad * 2) / (weeks.length - 1) : 0;
    var pts = vals.map(function (v, i) {
        var x = pad + i * step;
        var y = H - pad - ((v - mn) / (mx - mn)) * (H - pad * 2);
        return [x, y];
    });
    var line = pts.map(function (p, i) { return (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1); }).join(' ');
    var area = line + ' L' + pts[pts.length - 1][0].toFixed(1) + ' ' + (H - pad) + ' L' + pad + ' ' + (H - pad) + ' Z';
    var dots = pts.map(function (p, i) {
        var last = i === pts.length - 1;
        return '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="' + (last ? 5 : 3.2) + '" fill="' + (last ? '#4a6fa5' : '#fff') + '" stroke="#4a6fa5" stroke-width="2"><title>' + rrEsc(weeks[i].weekOf) + ': ' + vals[i] + '</title></circle>' +
            (last ? '<text x="' + p[0].toFixed(1) + '" y="' + (p[1] - 12).toFixed(1) + '" text-anchor="middle" font-family="monospace" font-size="12" font-weight="600" fill="#0F1720">' + vals[i] + '</text>' : '');
    }).join('');
    var avg8 = Math.round(vals.reduce(function (a, b) { return a + b; }, 0) / vals.length);
    var peak = Math.max.apply(null, vals);
    var peakWeek = weeks[vals.indexOf(peak)].weekOf;
    el.innerHTML =
        '<div class="rr-trend-meta"><span class="big">' + vals[vals.length - 1] + '</span>' +
        '<span class="rr-ct">this week · 8-week avg ' + avg8 + ' · peak ' + peak + ' (wk of ' + rrEsc(peakWeek) + ')</span></div>' +
        '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%" height="150" preserveAspectRatio="none" style="overflow:visible">' +
        '<defs><linearGradient id="rrTrendG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4a6fa5" stop-opacity="0.16"/><stop offset="1" stop-color="#4a6fa5" stop-opacity="0"/></linearGradient></defs>' +
        '<path d="' + area + '" fill="url(#rrTrendG)"></path><path d="' + line + '" fill="none" stroke="#4a6fa5" stroke-width="2.4"></path>' + dots + '</svg>';
}

function rejectionRenderRequestors(reqs) {
    var el = document.getElementById('rrReqPanel');
    if (!el) return;
    if (!reqs.length) { el.innerHTML = '<div style="color:#6B7280;padding:20px;text-align:center;">No repeat requestors (&ge;2) in this window</div>'; return; }
    var max = Math.max.apply(null, reqs.map(function (r) { return r.count; }).concat([1]));
    var html = '<table class="rr-tbl rr-req"><tr><th style="width:22px"></th><th>Requestor</th><th>Product Line</th><th style="text-align:center">Rej</th><th style="width:80px"></th></tr>';
    reqs.slice(0, 10).forEach(function (r, i) {
        var w = Math.round(r.count / max * 100);
        var name = (r.name || '').replace(/\s*\(\d+\)$/, '');
        html += '<tr><td class="rank">' + (i + 1) + '</td><td class="nm">' + rrEsc(name) + '</td>' +
            '<td style="color:#6B7280">' + rrEsc(r.productLine || '') + '</td>' +
            '<td class="cnt">' + r.count + '</td>' +
            '<td><div class="rr-minibar"><i style="width:' + w + '%"></i></div></td></tr>';
    });
    html += '</table>';
    el.innerHTML = html;
}

function rejectionRenderChangeTypes(cts) {
    var el = document.getElementById('rrCtPanel');
    if (!el) return;
    if (!cts.length) { el.innerHTML = '<div style="color:#6B7280;padding:20px;text-align:center;">No data</div>'; return; }
    var max = Math.max.apply(null, cts.map(function (c) { return c.count; }).concat([1]));
    el.innerHTML = cts.map(function (c) {
        var color = rrCtColor(c.type);
        var w = Math.round(c.count / max * 100);
        return '<div class="rr-catbar"><div class="nm"><span class="sw" style="background:' + color + '"></span>' + rrEsc(c.type) + '</div>' +
            '<div class="track"><div class="fill" style="width:' + w + '%;background:' + color + '"></div></div>' +
            '<div class="ct">' + c.count + '</div></div>';
    }).join('');
}

function rejectionRenderEvents(events) {
    var el = document.getElementById('rrEventsWrap');
    var cnt = document.getElementById('rrEvtCount');
    if (!el) return;
    if (cnt) cnt.textContent = events.length + ' rows · click a change to drill in';
    if (!events.length) { el.innerHTML = '<div style="color:#6B7280;padding:30px;text-align:center;">No rejection events in this window.</div>'; return; }
    var html = '<table class="rr-tbl"><tr>' +
        '<th>Change</th><th>Type</th><th>Rejected By</th><th>Date</th><th style="text-align:center">CCB d</th><th>Status</th><th>AI Category</th><th>Reject Comment</th></tr>';
    events.forEach(function (e, i) {
        var ccb = e.daysInCcb;
        var cc = rrCcbClass(ccb);
        var ctc = rrCtColor(e.changeType);
        var cat = rrCatColor(e.returnCategory);
        var date = (e.rejectDate || '').substring(0, 10);
        var cmt = (e.rejectComment || '').replace(/\s+/g, ' ').substring(0, 160);
        html += '<tr onclick="rejectionOpenDrawer(' + i + ')" style="cursor:pointer">' +
            '<td><span class="rr-chg-link">' + rrEsc(e.changeNumber) + '</span></td>' +
            '<td><span class="rr-cttag" style="background:' + rrHexBg(ctc) + ';color:' + ctc + '"><span class="d" style="background:' + ctc + '"></span>' + rrEsc(e.changeType) + '</span></td>' +
            '<td>' + rrEsc((e.rejectedBy || '').replace(/\s*\(\w+\)$/, '')) + '</td>' +
            '<td style="color:#6B7280">' + rrEsc(date) + '</td>' +
            '<td style="text-align:center">' + (ccb != null ? '<span class="rr-ccb ' + cc + '">' + ccb + '</span>' : '<span style="color:#9CA3AF">—</span>') + '</td>' +
            '<td>' + rrEsc(e.currentStatus || '') + '</td>' +
            '<td>' + (e.returnCategory ? '<span class="rr-cat-chip" style="background:' + rrHexBg(cat) + ';color:' + cat + '"><span class="sw" style="background:' + cat + '"></span>' + rrEsc(e.returnCategory) + '</span>' : '<span style="color:#9CA3AF">—</span>') + '</td>' +
            '<td class="rr-cmt">' + rrEsc(cmt) + (cmt.length === 160 ? '…' : '') + '</td></tr>';
    });
    html += '</table>';
    el.innerHTML = html;
}

// ---------------------------------------------------------------------------
// Drawer
// ---------------------------------------------------------------------------
function rejectionOpenDrawer(i) {
    var d = rejectionState.data;
    if (!d || !d.events || !d.events[i]) return;
    var e = d.events[i];
    document.getElementById('rrDChg').textContent = e.changeNumber || '';
    var meta = (e.changeType || '') + ' · rejected ' + (e.rejectDate || '').substring(0, 10) +
        (e.daysInCcb != null ? ' · CCB ' + e.daysInCcb + ' days' : '');
    document.getElementById('rrDMeta').textContent = meta;
    document.getElementById('rrDCat').textContent = e.returnCategory || 'Uncategorized';
    document.getElementById('rrDCatWhy').textContent = rejectionCatWhy(e.returnCategory);
    document.getElementById('rrDDesc').textContent = e.description || '(no description)';
    document.getElementById('rrDComment').textContent = e.rejectComment || '(no reject comment)';
    var chain = e.ccbChain || [];
    var chainEl = document.getElementById('rrDChain');
    if (!chain.length) {
        chainEl.innerHTML = '<div style="color:#9CA3AF;font-size:12px;">No CCB comment chain captured.</div>';
    } else {
        chainEl.innerHTML = chain.map(function (c) {
            var isReject = (c.event || '').toLowerCase() === 'reject';
            return '<div class="rr-ev' + (isReject ? ' reject' : '') + '">' +
                '<div class="meta">' + (isReject ? '✕ REJECT · ' : '') + rrEsc(c.event || 'Comment') + ' · ' + rrEsc(c.ts || '') + ' · ' + rrEsc(c.user || '') + '</div>' +
                '<div class="txt">' + rrEsc(c.comment || '') + '</div></div>';
        }).join('');
    }
    document.getElementById('rrScrim').classList.add('open');
    document.getElementById('rrDrawer').classList.add('open');
}
function rejectionCloseDrawer() {
    var s = document.getElementById('rrScrim'), dr = document.getElementById('rrDrawer');
    if (s) s.classList.remove('open');
    if (dr) dr.classList.remove('open');
}
function rejectionCatWhy(cat) {
    return {
        'Ambiguous Request': 'The rejection and CCB chain ask the requestor to clarify scope; affected items are stated vaguely with no part list.',
        'Wrong Information': 'A field in the submission contradicts released Agile data (revision, target, or supplier).',
        'Incomplete Documentation': 'A required attachment or reference (work instruction, disposition, data) is missing from the change.',
        'Insufficient Information': 'Justification or supporting data for the proposed change is absent; reviewer asked for evidence.',
        'Duplicate Request': 'The change overlaps an existing/recent submission; reviewer asked to consolidate.',
        'Wrong Proposal': 'The proposed action is not feasible or appropriate for the situation described.'
    }[cat] || 'AI categorization of the rejection based on the reject comment + CCB chain.';
}

// ---------------------------------------------------------------------------
// Export / Send
// ---------------------------------------------------------------------------
function rejectionExport() {
    var pending = (rejectionState.data && rejectionState.data.manualFill) ? rejectionState.data.manualFill.length : 0;
    if (pending > 0) {
        var msg = 'Fill the ' + pending + ' flagged cell(s) first — they ship inside the Excel as yellow placeholders. Export anyway?';
        rejectionConfirm(msg).then(function (okGo) {
            if (okGo) window.location.href = '/api/ecn-report/rejection/download?force=true';
        });
        return;
    }
    window.location.href = '/api/ecn-report/rejection/download';
}

function rejectionSendToMe() {
    var pending = (rejectionState.data && rejectionState.data.manualFill) ? rejectionState.data.manualFill.length : 0;
    var doSend = function (force) {
        var btn = document.getElementById('rrSendBtn');
        if (btn) { btn.disabled = true; btn.innerHTML = '&#9993; Sending…'; }
        fetch('/api/ecn-report/rejection/send-to-me', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ force: !!force })
        })
        .then(function (r) { return r.json(); })
        .then(function (d) {
            if (btn) { btn.disabled = false; btn.innerHTML = '&#9993; Send to me'; }
            if (d.needsForce) {
                rejectionConfirm(d.message + ' Send anyway?').then(function (okGo) { if (okGo) doSend(true); });
                return;
            }
            if (typeof appAlert === 'function') appAlert(d.message || (d.success ? 'Sent' : 'Failed')); else alert(d.message || 'Done');
        })
        .catch(function (err) {
            if (btn) { btn.disabled = false; btn.innerHTML = '&#9993; Send to me'; }
            if (typeof appAlert === 'function') appAlert('Email failed: ' + err.message);
        });
    };
    if (pending > 0) {
        rejectionConfirm('Fill the ' + pending + ' flagged cell(s) first. Send anyway (placeholders ship as-is)?')
            .then(function (okGo) { if (okGo) doSend(true); });
        return;
    }
    doSend(false);
}

function rejectionConfirm(msg) {
    if (typeof appConfirm === 'function') return appConfirm(msg, { okText: 'Yes' });
    return Promise.resolve(window.confirm(msg));
}

// ---------------------------------------------------------------------------
// Scoped CSS (mirrors Rejection Report Dashboard.html mockup)
// ---------------------------------------------------------------------------
function rrCss() {
    return [
        '.rr{font-size:13px;color:#0F1720;}',
        '.rr-controls{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:12px;}',
        '.rr-lbl{font-size:12px;color:#6B7280;}.rr-grow{flex:1;}',
        '.rr-btn{height:30px;padding:0 11px;background:var(--surface,#fff);border:1px solid var(--line-2,#d4d2cb);border-radius:var(--radius-sm,6px);font-size:12.5px;color:var(--ink-2,#40454e);cursor:pointer;display:inline-flex;align-items:center;gap:6px;font-weight:500;}',
        '.rr-btn:hover{background:var(--surface-2,#f3f3ee);border-color:var(--line-2,#d4d2cb);}',
        '.rr-btn.primary{background:var(--ink,#1a1d24);color:#fff;border-color:var(--ink,#1a1d24);}.rr-btn.primary:hover{background:var(--ink-2,#40454e);}',
        '.rr-btn.rr-gated{opacity:0.7;}',
        'select.rr-btn{padding-right:6px;}',
        '.rr-pill{font-family:monospace;font-size:10.5px;letter-spacing:0.04em;color:#6B7280;background:#fff;border:1px solid #E8E6DF;border-radius:4px;padding:4px 8px;}.rr-pill b{color:#0F1720;font-weight:600;}',
        '.rr-status{font-size:12px;color:#6B7280;margin-bottom:12px;}',
        '.rr-group{background:#fff;border:1px solid #E8E6DF;border-radius:10px;box-shadow:0 1px 2px rgba(20,24,40,0.04);margin-bottom:16px;}',
        '.rr-group-head{padding:13px 16px 11px;border-bottom:1px solid #E8E6DF;display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;}',
        '.rr-group-head h3{font-size:14px;font-weight:600;margin:0;}.rr-group-head .sub{font-size:11px;color:#6B7280;font-weight:400;}',
        '.rr-group-body{padding:14px 16px;}',
        '.rr-grid2{display:grid;grid-template-columns:1fr 1fr;gap:16px;}',
        '@media(max-width:900px){.rr-grid2{grid-template-columns:1fr;}.rr-kpis{grid-template-columns:repeat(2,1fr)!important;}}',
        '.rr-kpis{background:linear-gradient(180deg,#fff,#fbfaf6);border:1px solid #E8E6DF;border-radius:10px;display:grid;grid-template-columns:repeat(5,1fr);overflow:hidden;margin-bottom:16px;}',
        '.rr-kpi{padding:15px 18px;border-right:1px solid #E8E6DF;}.rr-kpi:last-child{border-right:0;}',
        '.rr-kpi .k{font-family:monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#6B7280;margin-bottom:6px;}',
        '.rr-kpi .v{font-family:monospace;font-size:26px;font-weight:600;letter-spacing:-0.02em;line-height:1;}',
        '.rr-kpi .d{font-size:11px;margin-top:7px;color:#6B7280;}.rr-kpi .d .up{color:#B8342B;}.rr-kpi .d .down{color:#1F8A4C;}',
        '.rr-narr{background:linear-gradient(135deg,#fafbfc,#f3f6fa);border-left:4px solid #4a6fa5;}.rr-narr .rr-group-body{font-size:13.5px;line-height:1.62;}',
        '.rr-catbar{display:grid;grid-template-columns:180px 1fr 46px;align-items:center;gap:10px;padding:7px 0;}',
        '.rr-catbar .nm{font-size:12.5px;display:flex;align-items:center;gap:7px;}.rr-catbar .sw{width:10px;height:10px;border-radius:2px;flex:none;}',
        '.rr-catbar .track{height:18px;background:#FAFAF7;border-radius:4px;overflow:hidden;}.rr-catbar .fill{height:100%;border-radius:4px;}',
        '.rr-catbar .ct{font-family:monospace;font-size:12.5px;font-weight:600;text-align:right;}',
        '.rr-aging{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;}',
        '.rr-aband{border:1px solid #E8E6DF;border-radius:8px;padding:12px 8px;text-align:center;}',
        '.rr-aband .n{font-family:monospace;font-size:24px;font-weight:600;line-height:1;}.rr-aband .l{font-size:11px;color:#6B7280;margin-top:5px;}',
        '.rr-aging-note{font-size:11.5px;color:#6B7280;margin-top:12px;display:flex;align-items:center;gap:14px;flex-wrap:wrap;}',
        '.rr-aging-note span{display:inline-flex;align-items:center;gap:5px;}.rr-aging-note i{width:9px;height:9px;border-radius:2px;display:inline-block;}',
        '.rr-trend-meta{display:flex;gap:18px;align-items:baseline;margin-bottom:6px;}.rr-trend-meta .big{font-family:monospace;font-size:22px;font-weight:600;}.rr-ct{font-size:12px;color:#6B7280;}',
        '.rr-tbl{width:100%;border-collapse:collapse;font-size:12.5px;}',
        '.rr-tbl th{text-align:left;font-family:monospace;font-size:10px;letter-spacing:0.08em;text-transform:uppercase;color:#6B7280;font-weight:500;padding:8px 10px;border-bottom:1px solid #E8E6DF;background:#FAFAF7;position:sticky;top:0;}',
        '.rr-tbl td{padding:9px 10px;border-bottom:1px solid #E8E6DF;vertical-align:top;}.rr-tbl tr:last-child td{border-bottom:0;}',
        '.rr-req .rank{font-family:monospace;color:#9CA3AF;}.rr-req .nm{font-weight:500;}.rr-req .cnt{font-family:monospace;font-weight:600;text-align:center;}',
        '.rr-minibar{height:8px;background:#FAFAF7;border-radius:3px;overflow:hidden;min-width:60px;}.rr-minibar>i{display:block;height:100%;background:#4a6fa5;border-radius:3px;}',
        '.rr-events-wrap{max-height:520px;overflow:auto;}',
        '.rr-chg-link{font-family:monospace;font-weight:600;color:#4a6fa5;}.rr-tbl tr:hover .rr-chg-link{text-decoration:underline;}',
        '.rr-cttag{font-family:monospace;font-size:10px;font-weight:600;padding:2px 6px;border-radius:3px;display:inline-flex;align-items:center;gap:5px;}.rr-cttag .d{width:7px;height:7px;border-radius:50%;}',
        '.rr-ccb{font-family:monospace;font-size:12px;font-weight:600;border-radius:4px;padding:2px 8px;display:inline-block;min-width:30px;text-align:center;}',
        '.rr-ccb.g{background:#eaf6ee;color:#136a39;}.rr-ccb.y{background:#fbf2e2;color:#8a5a12;}.rr-ccb.r{background:#fbeae8;color:#8f261f;}',
        '.rr-cat-chip{font-size:11px;padding:2px 8px;border-radius:999px;display:inline-flex;align-items:center;gap:6px;white-space:nowrap;}.rr-cat-chip .sw{width:8px;height:8px;border-radius:50%;flex:none;}',
        '.rr-cmt{font-size:12px;color:#3a4150;max-width:300px;}',
        // manual-fill
        '.rr-mf{border:1px solid #C7801B;background:#fbf2e2;border-radius:10px;margin-bottom:16px;overflow:hidden;}',
        '.rr-mf-head{display:flex;align-items:center;gap:10px;padding:12px 16px;}',
        '.rr-mf-ico{width:22px;height:22px;border-radius:5px;background:#C7801B;color:#fff;font-size:13px;font-weight:700;display:grid;place-items:center;flex:none;}',
        '.rr-mf-title{font-weight:600;font-size:13.5px;}.rr-mf-sub{font-size:12px;color:#3a4150;}',
        '.rr-mf-count{margin-left:auto;font-family:monospace;font-size:11px;color:#8a5a12;background:#fff;border:1px solid #C7801B;border-radius:4px;padding:3px 8px;}',
        '.rr-mf-rows{padding:0 16px 14px;display:grid;gap:8px;}',
        '.rr-mf-row{display:grid;grid-template-columns:150px 1fr 280px;gap:12px;align-items:center;background:#fff;border:1px solid #E8E6DF;border-radius:7px;padding:9px 12px;}',
        '@media(max-width:900px){.rr-mf-row{grid-template-columns:1fr;}}',
        '.rr-mf-chg{font-family:monospace;font-size:12.5px;font-weight:600;color:#4a6fa5;}.rr-mf-why{font-size:12px;color:#6B7280;}',
        '.rr-mf-row input{height:32px;border:1px solid #E8E6DF;border-radius:6px;padding:0 10px;font-size:12.5px;width:100%;outline:none;}',
        '.rr-mf-row input:focus{border-color:#4a6fa5;box-shadow:0 0 0 2px rgba(74,111,165,0.15);}',
        // drawer
        '.rr-scrim{position:fixed;inset:0;background:rgba(15,23,32,0.34);opacity:0;pointer-events:none;transition:opacity .2s;z-index:9998;}.rr-scrim.open{opacity:1;pointer-events:auto;}',
        '.rr-drawer{position:fixed;top:0;right:0;height:100%;width:480px;max-width:92vw;background:#fff;border-left:1px solid #E8E6DF;box-shadow:-14px 0 40px rgba(20,24,40,0.14);transform:translateX(100%);transition:transform .24s ease;z-index:9999;display:flex;flex-direction:column;}.rr-drawer.open{transform:translateX(0);}',
        '.rr-drawer-head{padding:16px 20px;border-bottom:1px solid #E8E6DF;display:flex;align-items:flex-start;gap:12px;}.rr-drawer-head .rr-ct{font-size:12px;color:#6B7280;}',
        '.rr-x{margin-left:auto;background:none;border:0;font-size:20px;color:#6B7280;cursor:pointer;line-height:1;}',
        '.rr-drawer-body{padding:18px 20px;overflow:auto;}.rr-dsec{margin-bottom:18px;}',
        '.rr-h{font-family:monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#6B7280;margin-bottom:7px;}',
        '.rr-aibox{background:#eef3fa;border:1px solid #d4e2f3;border-radius:8px;padding:12px 14px;font-size:13px;line-height:1.55;}',
        '.rr-chain{border-left:2px solid #E8E6DF;padding-left:12px;display:grid;gap:11px;}',
        '.rr-chain .rr-ev .meta{font-family:monospace;font-size:10.5px;color:#9CA3AF;margin-bottom:2px;}',
        '.rr-chain .rr-ev .txt{font-size:12.5px;color:#3a4150;line-height:1.5;}',
        '.rr-chain .rr-ev.reject .meta{color:#B8342B;}'
    ].join('\n');
}

// ---------------------------------------------------------------------------
// Bootstrap — restore the view if it was the last one selected.
// ---------------------------------------------------------------------------
(function () {
    function tryInit() {
        var panel = document.getElementById('panelEcnReport');
        if (panel && panel.style.display !== 'none' && !rejectionState._restoreChecked) {
            rejectionState._restoreChecked = true;
            var stored = null;
            try { stored = localStorage.getItem('ecnReportView'); } catch (e) {}
            if (stored === 'rejection' && typeof ecnSwitchView === 'function') {
                ecnSwitchView('rejection');
            }
        }
    }
    setInterval(tryInit, 1000);
    document.addEventListener('DOMContentLoaded', tryInit);
})();
