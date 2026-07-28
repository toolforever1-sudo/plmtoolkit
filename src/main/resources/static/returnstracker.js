// ============================================================================
// Returns Tracker — sub-view inside the ECN Report tab.
//
// Companion to ecnreport.js. Owns the pill toggle (Cycle Time / Returns Tracker),
// the date-range picker, and all rendering for the Returns dashboard:
//   - AI executive narrative
//   - AI anomaly callouts
//   - KPI tiles
//   - Category + product line + trend panels
//   - Top repeat requestors + top themes
//   - Rejection events table with row-click drill-down
//
// Backend: /api/ecn-report/returns/* (RejectionTrackerController)
// ============================================================================

var returnsState = {
    view: 'cycle',           // 'cycle' or 'returns'
    range: 'golive',         // default to "Since audit go-live"
    classification: 'audit', // 'audit' or 'ai'
    period: 'live',          // 'live' or a frozen snapshot id
    customFrom: null,
    customTo: null,
    data: null,
    // PT-61: per-column header filters replaced the single "Filter events..." box.
    // Map of columnKey → lowercase substring; empty = no filter on that column.
    columnFilters: {},
    // B (Vikas Singh 2026-07-06): events table default-sorts by ECN# and every
    // column is click-to-sort. { key, dir: 'asc'|'desc' }.
    sort: { key: 'ecnNumber', dir: 'asc' },
    loading: false,
};

// Production go-live of the return-comment audit. Overwritten from /data
// (auditGoLiveDate) once the first payload arrives.
var RETURNS_AUDIT_GOLIVE = '2026-06-24';

// Rollback lever (Jul 2026): when false, the Returns Tracker reverts to the
// classic look — single-colour Product Line bars + Weeks in the Period dropdown.
// Driven by the server flag app.returns.enhanced-view (surfaced on /data and
// /periods); default true = the new stacked-reason-code view. Flip the property
// and restart to roll back for everyone, no code change.
var RETURNS_ENHANCED_VIEW = true;

// PT-61: taxonomy refresh per Jimmy + Noraida — drop "Ambiguous Request",
// add "Return Requested", rename "Returned By Requestor" to "Returned by Owner".
// Legacy keys still mapped (for cached events that haven't been re-categorized
// by the next Python pass) so colors stay stable during the rollout.
var RETURNS_CATEGORY_COLORS = {
    'Returned by Owner':         '#1F8A4C',
    'Incomplete Documentation':  '#C7801B',
    'Insufficient Information':  '#B8342B',
    'Wrong Information':         '#4a6fa5',
    'Duplicate Request':         '#6B7280',
    'Return Requested':          '#7C3AED',
    'Unknown':                   '#999999',
    'No audit code':             '#B0B0B0',
    // Legacy aliases — server now remaps on read, kept here so any pre-enrichment
    // chart still picks the right color if a stale payload arrives mid-deploy.
    'Returned By Requestor':     '#1F8A4C',
    'Ambiguous Request':         '#999999'
};

// ---------------------------------------------------------------------------
// View toggle (pill)
// ---------------------------------------------------------------------------

function ecnSwitchView(view) {
    returnsState.view = view;
    try { localStorage.setItem('ecnReportView', view); } catch (e) {}
    if (typeof logPageView === 'function') {
        var label = view === 'returns'    ? 'Returns Tracker'
                  : view === 'volume'     ? 'Volume Reports'
                  : view === 'overdue'    ? 'Overdue Tracker'
                  : view === 'duedate'    ? 'Due Date Expiration'
                  : view === 'teamreport' ? 'Team Report'
                  : view === 'rejection'  ? 'Rejection Report'
                  : view === 'kpimaster'  ? 'KPI Master'
                  :                         'Cycle Time';
        logPageView('ecnreport', label);
    }

    var pillCycle = document.getElementById('ecnViewCycle');
    var pillReturns = document.getElementById('ecnViewReturns');
    var pillVolume = document.getElementById('ecnViewVolume');
    var pillOverdue = document.getElementById('ecnViewOverdue');
    var pillDueDate = document.getElementById('ecnViewDueDate');
    var pillTeamReport = document.getElementById('ecnViewTeamReport');
    var pillRejection = document.getElementById('ecnViewRejection');
    var pillKpi = document.getElementById('ecnViewKpiMaster');
    var cycleView = document.getElementById('cycleTimeView');
    var returnsView = document.getElementById('returnsTrackerView');
    var volumeView = document.getElementById('volumeReportView');
    var overdueView = document.getElementById('overdueTrackerView');
    var dueDateView = document.getElementById('dueDateView');
    var teamReportView = document.getElementById('teamReportInAppView');
    var rejectionView = document.getElementById('rejectionReportView');
    var kpiView = document.getElementById('kpiMasterView');
    var returnsCtrls = document.getElementById('returnsControls');

    function setActive(active) {
        [pillCycle, pillReturns, pillVolume, pillOverdue, pillDueDate, pillTeamReport, pillRejection, pillKpi].forEach(function (btn) {
            if (!btn) return;
            if (btn === active) {
                btn.style.background = '#fff';
                btn.style.color = '#0F1720';
                btn.style.boxShadow = '0 1px 2px rgba(0,0,0,0.06)';
            } else {
                btn.style.background = 'transparent';
                btn.style.color = '#6B7280';
                btn.style.boxShadow = 'none';
            }
        });
    }

    function hideAllButCycle() {
        if (returnsView) returnsView.style.display = 'none';
        if (volumeView) volumeView.style.display = 'none';
        if (overdueView) overdueView.style.display = 'none';
        if (dueDateView) dueDateView.style.display = 'none';
        if (teamReportView) teamReportView.style.display = 'none';
        if (rejectionView) rejectionView.style.display = 'none';
        if (kpiView) kpiView.style.display = 'none';
        if (returnsCtrls) returnsCtrls.style.display = 'none';
    }

    if (view === 'returns') {
        setActive(pillReturns);
        cycleView.style.display = 'none';
        hideAllButCycle();
        returnsView.style.display = '';
        if (returnsCtrls) returnsCtrls.style.display = 'flex';
        returnsToggleCycleToolbar(false);
        var gear = document.getElementById('returnsRecipientsBtn');
        if (gear) gear.style.display = '';
        returnsLoadPeriods();
        returnsLoad();
    } else if (view === 'volume') {
        setActive(pillVolume);
        cycleView.style.display = 'none';
        hideAllButCycle();
        if (volumeView) volumeView.style.display = '';
        returnsToggleCycleToolbar(false);
        if (typeof volumeInit === 'function') volumeInit();
    } else if (view === 'overdue') {
        setActive(pillOverdue);
        cycleView.style.display = 'none';
        hideAllButCycle();
        if (overdueView) overdueView.style.display = '';
        returnsToggleCycleToolbar(false);
        if (typeof overdueLoad === 'function') overdueLoad();
    } else if (view === 'duedate') {
        setActive(pillDueDate);
        cycleView.style.display = 'none';
        hideAllButCycle();
        if (dueDateView) dueDateView.style.display = '';
        returnsToggleCycleToolbar(false);
        if (typeof dueDateInit === 'function') dueDateInit();
    } else if (view === 'teamreport') {
        setActive(pillTeamReport);
        cycleView.style.display = 'none';
        hideAllButCycle();
        if (teamReportView) teamReportView.style.display = '';
        returnsToggleCycleToolbar(false);
        if (typeof teamReportInAppInit === 'function') teamReportInAppInit();
    } else if (view === 'rejection') {
        setActive(pillRejection);
        cycleView.style.display = 'none';
        hideAllButCycle();
        if (rejectionView) rejectionView.style.display = '';
        returnsToggleCycleToolbar(false);
        if (typeof rejectionInit === 'function') rejectionInit();
    } else if (view === 'kpimaster') {
        setActive(pillKpi);
        cycleView.style.display = 'none';
        hideAllButCycle();
        if (kpiView) kpiView.style.display = '';
        returnsToggleCycleToolbar(false);
        if (typeof kpiMasterInit === 'function') kpiMasterInit();
    } else {
        setActive(pillCycle);
        cycleView.style.display = '';
        hideAllButCycle();
        returnsToggleCycleToolbar(true);
    }
}

function returnsToggleCycleToolbar(show) {
    // The Cycle Time tools (Run/Email/Excel/SLA/Editors) live in the .page-head
    // tools div. Hide them on Returns view; restore on Cycle view.
    var ids = ['ecnEmailBtn', 'ecnUploadSlaBtn', 'ecnManageEditorsBtn'];
    var alwaysShownInCycle = document.querySelectorAll('#panelEcnReport .page-head .tools button');
    alwaysShownInCycle.forEach(function (btn) {
        // Don't reveal display:none buttons that were already conditionally hidden
        if (show) {
            // Restore — only if this is a button that was meant to be visible
            // (we can't tell, so we leave conditional ones alone)
            if (!ids.includes(btn.id)) btn.style.display = '';
        } else {
            btn.style.display = 'none';
        }
    });
    var profileSel = document.getElementById('ecnProfileSelect');
    if (profileSel) profileSel.style.display = show ? '' : 'none';
}

// ---------------------------------------------------------------------------
// Date range
// ---------------------------------------------------------------------------

function returnsHandleRangeChange(val) {
    returnsState.range = val;
    var from = document.getElementById('returnsCustomFrom');
    var to = document.getElementById('returnsCustomTo');
    if (val === 'custom') {
        from.style.display = '';
        to.style.display = '';
        // Default custom to last 30 days if empty
        if (!from.value) {
            var t = new Date();
            var f = new Date(); f.setDate(f.getDate() - 30);
            from.value = f.toISOString().substring(0, 10);
            to.value = t.toISOString().substring(0, 10);
        }
    } else {
        from.style.display = 'none';
        to.style.display = 'none';
    }
    returnsLoad();
}

function returnsHandleCustomDate() {
    if (returnsState.range === 'custom') returnsLoad();
}

function returnsResolveRange() {
    var today = new Date();
    var to = today.toISOString().substring(0, 10);
    var from;
    var d = new Date(today);
    if (returnsState.range === '7d') {
        d.setDate(d.getDate() - 7); from = d.toISOString().substring(0, 10);
    } else if (returnsState.range === '30d') {
        d.setDate(d.getDate() - 30); from = d.toISOString().substring(0, 10);
    } else if (returnsState.range === '90d') {
        d.setDate(d.getDate() - 90); from = d.toISOString().substring(0, 10);
    } else if (returnsState.range === 'quarter') {
        var m = today.getMonth();
        var qStart = Math.floor(m / 3) * 3;
        var s = new Date(today.getFullYear(), qStart, 1);
        from = s.toISOString().substring(0, 10);
    } else if (returnsState.range === 'golive') {
        from = RETURNS_AUDIT_GOLIVE;
    } else if (returnsState.range === 'legacy') {
        var g = new Date(RETURNS_AUDIT_GOLIVE);
        g.setDate(g.getDate() - 1);
        to = g.toISOString().substring(0, 10);
        from = '2026-01-01';
    } else if (returnsState.range === 'custom') {
        from = document.getElementById('returnsCustomFrom').value;
        to = document.getElementById('returnsCustomTo').value;
        if (!from || !to) return null;
    }
    return { from: from, to: to };
}

// ---------------------------------------------------------------------------
// Data load
// ---------------------------------------------------------------------------

function returnsLoad() {
    if (returnsState.loading) return;
    var r = returnsResolveRange();
    if (!r) return;
    returnsState.loading = true;
    document.getElementById('returnsStatus').textContent = 'Loading return-to-pending events for ' + r.from + ' → ' + r.to + '...';
    fetch('/api/ecn-report/returns/data?from=' + r.from + '&to=' + r.to)
        .then(function (resp) { return resp.json(); })
        .then(function (data) {
            returnsState.loading = false;
            if (!data.success) {
                document.getElementById('returnsStatus').textContent = data.message || 'No data available.';
                document.getElementById('returnsStatus').style.color = '#B8342B';
                return;
            }
            returnsState.data = data;
            if (data.auditGoLiveDate) RETURNS_AUDIT_GOLIVE = data.auditGoLiveDate;
            returnsRender();
        })
        .catch(function (err) {
            returnsState.loading = false;
            document.getElementById('returnsStatus').textContent = 'Error: ' + err.message;
        });
}

// rejection_tracker.py --narrative-window only understands "<int>d" or "YYYY-MM".
// The UI range tokens (golive / legacy / quarter / custom) are NOT valid windows,
// so map them to a day-count the script accepts. Without this, Refresh on the
// default "Since audit go-live" range sent window=golive and the script died with
// "ValueError: Unrecognized window: golive" (Exit 1).
function returnsNarrativeWindow() {
    var range = returnsState.range;
    if (range === '7d' || range === '30d' || range === '90d') return range;
    var r = returnsResolveRange();
    if (!r || !r.from || !r.to) return '90d';
    var days = Math.round(
        (new Date(r.to + 'T00:00:00') - new Date(r.from + 'T00:00:00')) / 86400000);
    if (!(days >= 1)) days = 90;
    return days + 'd';
}

function returnsRefresh() {
    var btn = event && event.target ? event.target : null;
    if (btn) { btn.disabled = true; btn.textContent = '↻ Refreshing…'; }
    fetch('/api/ecn-report/returns/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ window: returnsNarrativeWindow() })
    })
    .then(function (r) { return r.json(); })
    .then(function (data) {
        if (!data.success) {
            appAlert(data.message || 'Refresh failed');
            if (btn) { btn.disabled = false; btn.textContent = '↻ Refresh'; }
            return;
        }
        var statusEl = document.getElementById('returnsStatus');
        statusEl.style.color = '#0F1720';
        // Poll for completion: stop early on FAILURE (refreshStatus=='failed') so the
        // spinner doesn't camp for 8 minutes when the script crashed on launch.
        // Poll faster (3s) so the live log tail feels like real progress.
        var attempts = 0;
        var lastLogShown = 'Starting refresh — connecting to Agile and AI...';
        var spinnerFrames = ['⠋','⠙','⠹','⠸','⠼','⠴','⠦','⠧','⠇','⠏'];
        var spinFrame = 0;
        // Render the initial frame immediately so the user sees animation, then tick.
        statusEl.textContent = spinnerFrames[0] + ' ' + lastLogShown;
        var spinInterval = setInterval(function () {
            spinFrame = (spinFrame + 1) % spinnerFrames.length;
            if (btn && btn.disabled) {
                statusEl.textContent = spinnerFrames[spinFrame] + ' ' + lastLogShown;
            }
        }, 200);
        var pollInterval = setInterval(function () {
            attempts++;
            if (attempts > 300) {  // 15 minutes (3s × 300) — covers worst-case Oracle slowness
                clearInterval(pollInterval);
                clearInterval(spinInterval);
                if (btn) { btn.disabled = false; btn.textContent = '↻ Refresh'; }
                statusEl.textContent = 'Refresh timed out after 15 min. The script may still be running on the server — reload the page in a few minutes to see the latest data.';
                return;
            }
            fetch('/api/ecn-report/returns/data?from=' + returnsResolveRange().from + '&to=' + returnsResolveRange().to)
                .then(function (r) { return r.json(); })
                .then(function (d) {
                    if (d.refreshStatus === 'failed') {
                        clearInterval(pollInterval);
                        clearInterval(spinInterval);
                        if (btn) { btn.disabled = false; btn.textContent = '↻ Refresh'; }
                        statusEl.textContent =
                            'Refresh failed: ' + (d.refreshError || 'unknown error') + ' — check the server log for details.';
                        statusEl.style.color = '#B8342B';
                        return;
                    }
                    if (d.refreshLogTail) lastLogShown = d.refreshLogTail;
                    if (d.lastRunTs && returnsState.data && d.lastRunTs !== returnsState.data.lastRunTs) {
                        clearInterval(pollInterval);
                        clearInterval(spinInterval);
                        returnsState.data = d;
                        returnsRender();
                        if (btn) { btn.disabled = false; btn.textContent = '↻ Refresh'; }
                    }
                });
        }, 3000);
    });
}

// ---------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------

function returnsRender() {
    var d = returnsState.data;
    if (!d) return;
    if (typeof d.enhancedView === 'boolean') RETURNS_ENHANCED_VIEW = d.enhancedView;
    var ev = d.events || [];
    var agg = (returnsState.classification === 'ai'
        ? d.aggregatesAi : d.aggregates) || d.aggregates || {};
    var nar = d.narrative || {};

    var repeatCount = (agg.repeatRequestors != null ? agg.repeatRequestors : agg.repeatOffenders) || 0;
    var excludedCount = agg.excludedFromAi || 0;
    var statusText = ev.length + ' return-to-pending events · ' + (agg.uniqueEcns || 0) + ' unique ECNs · ' +
        repeatCount + ' repeat requestors';
    if (excludedCount > 0) {
        statusText += ' · ' + excludedCount + ' excluded from AI';
    }
    if (d.lastRunTs) {
        statusText += ' · Last refresh ' + new Date(d.lastRunTs).toLocaleString();
    }
    document.getElementById('returnsStatus').textContent = statusText;
    document.getElementById('returnsStatus').style.color = '#0F1720';

    // A/G (Vikas Singh, 2026-07-06): the AI "Executive Narrative", the "N excluded
    // from AI" callout, and the "AI → Audit Mismatches" section were removed from the
    // dashboard. Hide their containers so no stale content lingers.
    returnsHideRemovedSections();

    returnsRenderAnomalies(nar.anomalies || []);
    returnsRenderKpiTiles(agg);
    returnsRenderAgreement(d.agreement);
    returnsRenderCategoryPanel(agg);
    returnsRenderProductLinePanel(agg);
    // F: Repeat Requestors + Product Teams now render ABOVE the Trend tile (the DOM
    // order is set in index.html); the call order here doesn't affect placement.
    returnsRenderRequestorPanel(agg, nar);
    returnsRenderThemesPanel(agg, nar);
    returnsRenderTrendPanel(agg);
    returnsRenderEventsTable(ev);
}

/** A/G: hide the removed AI sections (narrative card, excluded-from-AI callout,
 *  AI↔Audit mismatches) if their containers exist. */
function returnsHideRemovedSections() {
    ['returnsNarrativeCard', 'returnsExcludedBanner', 'returnsMismatchPanel'].forEach(function (id) {
        var el = document.getElementById(id);
        if (el) el.style.display = 'none';
    });
}

// ---------------------------------------------------------------------------
// AI vs audit classification toggle
// ---------------------------------------------------------------------------

function returnsSetClassification(mode) {
    returnsState.classification = mode;
    var audit = document.getElementById('returnsClsAudit');
    var ai = document.getElementById('returnsClsAi');
    [ [audit, 'audit'], [ai, 'ai'] ].forEach(function (pair) {
        if (!pair[0]) return;
        var on = pair[1] === mode;
        pair[0].style.background = on ? '#fff' : 'transparent';
        pair[0].style.color = on ? '#0F1720' : '#6B7280';
        pair[0].style.boxShadow = on ? '0 1px 2px rgba(0,0,0,0.06)' : 'none';
    });
    if (returnsState.data) returnsRender();
}

function returnsRenderAgreement(agree) {
    var el = document.getElementById('returnsAgreement');
    if (!el) return;
    if (!agree || !agree.coded) {
        el.innerHTML = '<div style="font-size:12px;color:#6B7280;">No audit-coded returns in this '
            + 'period yet — AI↔audit agreement will appear once analysts use reason-code prefixes '
            + '(post ' + RETURNS_AUDIT_GOLIVE + ').</div>';
        return;
    }
    var pct = agree.agreementPct;
    var color = pct >= 80 ? '#1F8A4C' : pct >= 60 ? '#C7801B' : '#B8342B';
    el.innerHTML =
        '<div style="display:flex;align-items:center;gap:14px;">' +
        '<div style="font-size:26px;font-weight:bold;color:' + color + ';">' + pct + '%</div>' +
        '<div style="font-size:12px;color:#6B7280;">AI↔Audit agreement &middot; ' +
        agree.matched + ' of ' + agree.coded + ' coded returns match the human reason code' +
        (agree.mismatches && agree.mismatches.length
            ? ' &middot; ' + agree.mismatches.length + ' mismatch' + (agree.mismatches.length === 1 ? '' : 'es') + ' below'
            : '') +
        '</div></div>';
}

function returnsRenderMismatches(agree) {
    var el = document.getElementById('returnsMismatchPanel');
    if (!el) return;
    var mm = agree && agree.mismatches ? agree.mismatches : [];
    if (!mm.length) { el.style.display = 'none'; return; }
    el.style.display = '';
    var html = '<div style="font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:.05em;'
        + 'font-weight:600;border-bottom:1px solid #E8E6DF;padding-bottom:4px;margin-bottom:8px;">'
        + 'AI &harr; Audit mismatches (' + mm.length + ')</div>';
    html += '<table style="width:100%;border-collapse:collapse;font-size:12px;">'
        + '<tr style="background:#2c3e50;color:#fff;">'
        + '<th style="text-align:left;padding:6px 10px;">ECN#</th>'
        + '<th style="text-align:left;padding:6px 10px;">AI inferred</th>'
        + '<th style="text-align:left;padding:6px 10px;">Audit code</th>'
        + '<th style="text-align:left;padding:6px 10px;">Comment</th></tr>';
    mm.forEach(function (m, i) {
        var bg = i % 2 ? '#FAFAF7' : '#fff';
        html += '<tr style="background:' + bg + ';">'
            + '<td style="padding:6px 10px;border:1px solid #cccccc;">' + returnsEsc(m.ecnNumber || '') + '</td>'
            + '<td style="padding:6px 10px;border:1px solid #cccccc;color:#4a6fa5;">' + returnsEsc(m.aiCategory || '') + '</td>'
            + '<td style="padding:6px 10px;border:1px solid #cccccc;color:#B8342B;">' + returnsEsc(m.auditCategory || '') + '</td>'
            + '<td style="padding:6px 10px;border:1px solid #cccccc;">' + returnsEsc(m.comment || '') + '</td></tr>';
    });
    html += '</table>';
    el.innerHTML = html;
}

function returnsRenderNarrative(nar) {
    var card = document.getElementById('returnsNarrativeCard');
    var body = document.getElementById('returnsNarrativeBody');
    card.style.display = '';
    if (!nar || !nar.narrative) {
        // Don't silently hide — show a placeholder so the user knows the AI section
        // exists and how to populate it. Hidden cards confused users when narrative
        // hadn't been generated yet for the chosen window.
        body.innerHTML = '<div style="color:#6B7280; font-size:13px;">No AI narrative for this date range yet. ' +
            'Click <strong>↻ Refresh</strong> to generate one (takes ~40 seconds).</div>';
        return;
    }
    // returnsRenderBullets handles both markdown bullets (preferred) and prose
    // fallback (graceful for older cached narratives).
    body.innerHTML = returnsRenderBullets(nar.narrative);
}

function returnsRenderExcludedCallout(excludedCount) {
    // Create / reuse a banner that explains the "Returned By Requestor" exclusion.
    // Lives just above the AI narrative card so users see it before consuming the
    // AI analysis.
    var card = document.getElementById('returnsNarrativeCard');
    if (!card || !card.parentNode) return;
    var banner = document.getElementById('returnsExcludedBanner');
    if (excludedCount <= 0) {
        if (banner) banner.style.display = 'none';
        return;
    }
    if (!banner) {
        banner = document.createElement('div');
        banner.id = 'returnsExcludedBanner';
        banner.style.cssText = 'margin-bottom:12px;padding:10px 14px;background:#e8f0fe;'
            + 'border-left:4px solid #4a6fa5;border-radius:0 6px 6px 0;font-size:13px;'
            + 'line-height:1.5;color:#0F1720;';
        card.parentNode.insertBefore(banner, card);
    }
    banner.style.display = '';
    banner.innerHTML = '<strong>' + excludedCount + ' ECN' + (excludedCount === 1 ? '' : 's')
        + ' excluded from AI analysis</strong> &mdash; the requestor pushed their own ECN back to '
        + 'Pending (correcting their own work, not an analyst return-to-pending). These show up in the '
        + '<em>Returned By Requestor</em> category in the chart below but contribute no theme, '
        + 'narrative input, or anomaly signal.';
}

function returnsRenderAnomalies(anomalies) {
    var el = document.getElementById('returnsAnomalies');
    // Anomaly strip is purely additive — hide entirely when empty (vs narrative,
    // which always shows so the user knows the section exists).
    if (!anomalies || !anomalies.length) { el.style.display = 'none'; return; }
    el.style.display = '';
    var html = '<div style="font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:.05em;font-weight:600;margin-bottom:6px;">⚠️ Anomalies</div>';
    html += '<div style="display:flex;flex-wrap:wrap;gap:8px;">';
    anomalies.forEach(function (a) {
        var dot = a.kind === 'spike' ? '🔺' : a.kind === 'theme' ? '🆕' : '👤';
        html += '<div style="background:#fff8e1;border-left:4px solid #C7801B;border-radius:0 6px 6px 0;padding:8px 12px;font-size:13px;color:#0F1720;flex:1 1 280px;">' +
            dot + '&nbsp;&nbsp;' + returnsEsc(a.text || '') + '</div>';
    });
    html += '</div>';
    el.innerHTML = html;
}

function returnsRenderKpiTiles(agg) {
    var el = document.getElementById('returnsKpiTiles');
    var total = agg.totalEvents || 0;
    var ecns = agg.uniqueEcns || 0;
    var repeats = (agg.repeatRequestors != null ? agg.repeatRequestors : agg.repeatOffenders) || 0;
    var avgPerEcn = ecns > 0 ? (total / ecns).toFixed(1) : '—';
    // Top category %
    var topCat = '—', topCatPct = 0;
    if (agg.categories) {
        Object.keys(agg.categories).forEach(function (k) {
            if (agg.categories[k] > topCatPct) { topCatPct = agg.categories[k]; topCat = k; }
        });
        topCatPct = total > 0 ? Math.round(100 * topCatPct / total) : 0;
    }
    function tile(label, value, color, sub, tooltip) {
        var titleAttr = tooltip ? ' title="' + returnsEsc(tooltip) + '"' : '';
        return '<div style="background:#FAFAF7;border:1px solid #E8E6DF;border-radius:6px;padding:14px 10px;text-align:center;' +
            (tooltip ? 'cursor:help;' : '') + '"' + titleAttr + '>' +
            '<div style="font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:.05em;font-weight:600;">' + returnsEsc(label) + '</div>' +
            '<div style="font-size:26px;font-weight:bold;color:' + color + ';margin-top:6px;line-height:1;">' + returnsEsc(String(value)) + '</div>' +
            (sub ? '<div style="font-size:10px;color:#6B7280;margin-top:6px;">' + returnsEsc(sub) + '</div>' : '') +
            '</div>';
    }
    el.innerHTML =
        '<div style="display:grid;grid-template-columns:repeat(5,1fr);gap:12px;">' +
        tile('Total Returns to Pending', total, '#0F1720', null,
             'Total return-to-Pending events in this window (one ECN can contribute multiple).') +
        tile('Affected ECNs', ecns, '#0F1720', null,
             'Distinct ECNs that were returned to Pending at least once in this window. Includes ECNs that are now Completed, In Progress, or currently Pending.') +
        tile('Top Category', topCatPct + '%', '#B8342B', topCat,
             'Largest single return-to-pending category as a share of all ' + total + ' events in this window.') +
        tile('Repeat Requestors', repeats, '#C7801B', '≥3 in window',
             'Distinct requestors with 3 or more ECNs returned to Pending in this window.') +
        tile('Avg returns per affected ECN', avgPerEcn, '#0F1720', null,
             'Total Returns to Pending ÷ Affected ECNs = ' + total + ' ÷ ' + ecns + '. Of the ECNs that got returned to Pending, this is the average number of times each was returned. Does NOT include ECNs that were never returned.') +
        '</div>';
}

function returnsRenderCategoryPanel(agg) {
    var el = document.getElementById('returnsCategoryPanel');
    var counts = agg.categories || {};
    var total = agg.totalEvents || 0;
    var max = Math.max.apply(null, Object.values(counts).concat([1]));
    var html = '<table style="width:100%;border-collapse:collapse;font-size:13px;">';
    Object.keys(counts).forEach(function (cat) {
        var c = counts[cat];
        var pct = total > 0 ? (100 * c / total).toFixed(1) : 0;
        var color = RETURNS_CATEGORY_COLORS[cat] || '#4a6fa5';
        var w = (100 * c / max).toFixed(1);
        html += '<tr>' +
            '<td style="padding:6px 8px;font-weight:600;width:35%;">' + returnsEsc(cat) + '</td>' +
            '<td style="padding:6px 8px;width:50%;">' +
                '<div style="background:#f0f0f0;border-radius:4px;height:18px;position:relative;">' +
                '<div style="background:' + color + ';width:' + w + '%;height:100%;border-radius:4px;"></div>' +
                '</div>' +
            '</td>' +
            '<td style="padding:6px 8px;text-align:right;font-family:monospace;font-size:12px;">' + c + ' (' + pct + '%)</td>' +
            '</tr>';
    });
    html += '</table>';
    el.innerHTML = html;
}

// Build a { key -> { category -> count } } matrix from the events, honoring the
// active Audit/AI toggle. `splitField` is the event field (productLine/productTeam
// /requestor); values are split on ';'. `keyFn` optionally normalizes each key
// (e.g. strip the "(id)" suffix on requestor names).
function returnsBuildCatMatrix(splitField, keyFn) {
    var field = returnsState.classification === 'ai' ? 'aiCategory' : 'auditCategory';
    var events = (returnsState.data && returnsState.data.events) || [];
    var m = {};
    events.forEach(function (e) {
        var raw = e[splitField];
        if (raw == null || raw === '') return;
        var cat = e[field] || 'Unknown';
        String(raw).split(';').forEach(function (seg) {
            seg = keyFn ? keyFn(seg) : seg.trim();
            if (!seg) return;
            (m[seg] = m[seg] || {})[cat] = (m[seg][cat] || 0) + 1;
        });
    });
    return m;
}

// Shared renderer: a list of {name, count, _key?} as horizontal bars, each
// stacked by reason-code category (colors + order from the Categories panel, which
// serves as the legend). Tooltip on each segment shows count AND % within the row
// (Vikas Singh, item E — applied to all three tiles for consistency).
function returnsStackedBarTable(items, matrix, limit) {
    var agg = (returnsState.classification === 'ai'
        ? (returnsState.data && returnsState.data.aggregatesAi)
        : (returnsState.data && returnsState.data.aggregates)) || {};
    var catOrder = Object.keys(agg.categories || {});
    var top = items.slice(0, limit || 8);
    if (!top.length) return '<div style="color:#6B7280;padding:20px;text-align:center;">No data</div>';
    var maxTotal = top[0].count || 1;
    var html = '<table style="width:100%;border-collapse:collapse;font-size:13px;">';
    top.forEach(function (p) {
        var breakdown = matrix[p._key != null ? p._key : p.name] || {};
        var rowTotal = 0;
        catOrder.forEach(function (c) { rowTotal += (breakdown[c] || 0); });
        if (!rowTotal) rowTotal = p.count || 1;
        var outerW = (100 * p.count / maxTotal).toFixed(1);
        var segs = '';
        catOrder.forEach(function (c) {
            var cnt = breakdown[c] || 0;
            if (!cnt) return;
            var segW = (100 * cnt / rowTotal).toFixed(2);
            var pct = (100 * cnt / rowTotal).toFixed(1);
            var color = RETURNS_CATEGORY_COLORS[c] || '#4a6fa5';
            segs += '<div title="' + returnsEsc(c + ': ' + cnt + ' (' + pct + '% of ' + returnsEsc(p.name) + ')') +
                '" style="width:' + segW + '%;background:' + color + ';height:100%;"></div>';
        });
        if (!segs) segs = '<div style="width:100%;background:#4a6fa5;height:100%;"></div>';
        html += '<tr>' +
            '<td style="padding:5px 8px;font-size:12px;width:55%;">' + returnsEsc(p.name) + '</td>' +
            '<td style="padding:5px 8px;width:35%;">' +
                '<div style="background:#f0f0f0;border-radius:4px;height:14px;width:' + outerW + '%;display:flex;overflow:hidden;">' + segs + '</div>' +
            '</td>' +
            '<td style="padding:5px 8px;text-align:right;font-family:monospace;font-size:12px;">' + p.count + '</td>' +
            '</tr>';
    });
    return html + '</table>';
}

function returnsRenderProductLinePanel(agg) {
    // PT-61 (May 13, 2026): the panel header was renamed from "Top Product Teams"
    // back to "Top Product Lines" and the data source was switched accordingly.
    // (Companion change: the second panel — formerly "Top Themes" — now renders
    // Top Product Teams via returnsRenderThemesPanel.)
    var el = document.getElementById('returnsProductLinePanel');
    var lines = agg.topProductLines || [];
    if (!lines.length) { el.innerHTML = '<div style="color:#6B7280;padding:20px;text-align:center;">No data</div>'; return; }

    if (!RETURNS_ENHANCED_VIEW) {
        // Classic single-colour bars (pre-Jul-2026 look — kept for rollback).
        var cmax = lines[0].count || 1;
        var chtml = '<table style="width:100%;border-collapse:collapse;font-size:13px;">';
        lines.slice(0, 8).forEach(function (p) {
            var cw = (100 * p.count / cmax).toFixed(1);
            chtml += '<tr>' +
                '<td style="padding:5px 8px;font-size:12px;width:55%;">' + returnsEsc(p.name) + '</td>' +
                '<td style="padding:5px 8px;width:35%;">' +
                    '<div style="background:#f0f0f0;border-radius:4px;height:14px;">' +
                    '<div style="background:#4a6fa5;width:' + cw + '%;height:100%;border-radius:4px;"></div>' +
                    '</div>' +
                '</td>' +
                '<td style="padding:5px 8px;text-align:right;font-family:monospace;font-size:12px;">' + p.count + '</td>' +
                '</tr>';
        });
        chtml += '</table>';
        el.innerHTML = chtml;
        return;
    }

    // Vikas Singh (Jul 2026): break each product-line bar down by reason code,
    // reusing the left "Return to Pending Categories" panel's colors + the shared
    // stacked-bar renderer (tooltip now shows % within each line — item E).
    var matrix = returnsBuildCatMatrix('productLine');
    el.innerHTML = returnsStackedBarTable(lines, matrix, 8);
}

function returnsRenderTrendPanel(agg) {
    var el = document.getElementById('returnsTrendPanel');
    var daily = agg.dailyCounts || {};
    var dates = Object.keys(daily).sort();
    if (!dates.length) { el.innerHTML = '<div style="color:#6B7280;padding:20px;text-align:center;">No data</div>'; return; }
    var max = Math.max.apply(null, Object.values(daily));
    // Inline SVG bar chart
    var w = 1100, h = 160, padL = 30, padR = 10, padT = 10, padB = 24;
    var plotW = w - padL - padR, plotH = h - padT - padB;
    var barW = plotW / dates.length;
    var svg = '<svg viewBox="0 0 ' + w + ' ' + h + '" style="width:100%;height:160px;">';
    // y-axis ticks
    [0, max].forEach(function (val, i) {
        var y = padT + plotH - (plotH * val / max);
        svg += '<line x1="' + padL + '" y1="' + y + '" x2="' + (w - padR) + '" y2="' + y + '" stroke="#eee" stroke-width="1"/>';
        svg += '<text x="' + (padL - 4) + '" y="' + (y + 3) + '" font-size="10" fill="#6B7280" text-anchor="end">' + val + '</text>';
    });
    dates.forEach(function (d, i) {
        var c = daily[d];
        var bh = max > 0 ? plotH * c / max : 0;
        var x = padL + i * barW;
        var y = padT + plotH - bh;
        svg += '<rect x="' + x + '" y="' + y + '" width="' + Math.max(1, barW - 1) + '" height="' + bh + '" fill="#4a6fa5">' +
            '<title>' + d + ': ' + c + ' return' + (c === 1 ? '' : 's') + ' to Pending</title></rect>';
    });
    // Date labels (first, mid, last)
    [0, Math.floor(dates.length / 2), dates.length - 1].forEach(function (i) {
        if (i < 0 || i >= dates.length) return;
        var x = padL + i * barW + barW / 2;
        svg += '<text x="' + x + '" y="' + (h - 6) + '" font-size="10" fill="#6B7280" text-anchor="middle">' + dates[i] + '</text>';
    });
    svg += '</svg>';
    el.innerHTML = svg;
}

function returnsRenderRequestorPanel(agg, nar) {
    // F (Vikas Singh 2026-07-06): moved above the Trend tile and switched to stacked
    // reason-code bars like Product Lines (the AI Pattern / Suggested-action columns,
    // part of the retired AI narrative, are dropped).
    var el = document.getElementById('returnsRequestorPanel');
    var reqs = agg.topRequestors || [];
    if (!reqs.length) { el.innerHTML = '<div style="color:#6B7280;padding:20px;text-align:center;">No data</div>'; return; }
    var norm = function (s) { return (s || '').replace(/\s*\([^)]*\)\s*$/, '').trim(); };
    var items = reqs.map(function (r) { var n = norm(r.name); return { name: n, _key: n, count: r.count || 0 }; });
    el.innerHTML = returnsStackedBarTable(items, returnsBuildCatMatrix('requestor', norm), 8);
}

function returnsRenderThemesPanel(agg, nar) {
    // PT-61 (May 13, 2026): repurposed — the panel container kept its id
    // (returnsThemesPanel) for HTML stability, but the content now renders
    // Top Product Teams (matching the KPI report's existing Team breakdown).
    // The AI-clustered themes panel has been retired — `nar.topThemes` is no
    // longer surfaced anywhere on this view. Kept the id so we don't have to
    // touch the index.html grid layout, plus the function name change ripples
    // into the call site below.
    var el = document.getElementById('returnsThemesPanel');
    var teams = agg.topProductTeams || [];
    if (!teams.length) { el.innerHTML = '<div style="color:#6B7280;padding:20px;text-align:center;">No data</div>'; return; }
    // F (Vikas Singh 2026-07-06): stacked by reason-code category, like Product Lines.
    el.innerHTML = returnsStackedBarTable(teams, returnsBuildCatMatrix('productTeam'), 8);
}

// ---------------------------------------------------------------------------
// Events table + drill-down
// ---------------------------------------------------------------------------

// The category shown for an event must match the panels/bars: in Audit-enforced
// view use auditCategory (so uncoded returns read "No audit code"), in AI view
// use aiCategory. Falls back to the resolved `category` for older cached events
// / snapshots that predate these fields. Fixes Vikas Singh's report that the
// Categories panel showed "No audit code" with no matching rows in the table.
function returnsActiveCategory(e) {
    var f = (returnsState.classification === 'ai') ? e.aiCategory : e.auditCategory;
    return f || e.category || '';
}

// PT-61: column definitions for the events table — added Product Team after
// Product Line, replaced Theme with Change Type. Each column carries its own
// data accessor + filter input below the header. Order matches the requested
// extract column layout (#5 + #7).
var RETURNS_EVENT_COLUMNS = [
    { key: 'ts',          label: 'Date',         filter: false, get: function(e){ return (e.ts || '').substring(0,16).replace('T',' '); } },
    { key: 'ecnNumber',   label: 'ECN#',         filter: true,  get: function(e){ return e.ecnNumber || ''; } },
    { key: '_rcount',     label: '# Returns',    filter: false },  // computed at render time
    { key: 'fromStatus',  label: 'From',         filter: true,  get: function(e){ return e.fromStatus || ''; } },
    { key: 'requestor',   label: 'Requestor',    filter: true,  get: function(e){ return (e.requestor || '').replace(/\s*\(\d+\)$/, ''); } },
    { key: 'rejectedBy',  label: 'Returned by',  filter: true,  get: function(e){ return (e.rejectedBy || '').replace(/\s*\(\d+\)$/, ''); } },
    { key: 'productLine', label: 'Product Line', filter: true,  get: function(e){ return e.productLine || ''; } },
    { key: 'productTeam', label: 'Product Team', filter: true,  get: function(e){ return e.productTeam || ''; } },
    { key: 'category',    label: 'Category',     filter: true,  get: function(e){ return returnsActiveCategory(e); } },
    { key: 'changeType',  label: 'Change Type',  filter: true,  get: function(e){ return e.changeType || ''; } },
    { key: 'comment',     label: 'Comment',      filter: true,  get: function(e){ return e.comment || ''; } }
];

function returnsRenderEventsTable(events) {
    var container = document.getElementById('returnsEventsContainer');

    // Build ECN# → return-count lookup (in current window). Surfaced as the
    // "# Returns" column so users can spot ECNs that bounced back multiple times.
    var ecnReturnCount = {};
    events.forEach(function (e) {
        var k = e.ecnNumber || '';
        if (k) ecnReturnCount[k] = (ecnReturnCount[k] || 0) + 1;
    });

    // Apply per-column filters (case-insensitive substring match per active column).
    var activeFilters = [];
    Object.keys(returnsState.columnFilters || {}).forEach(function (k) {
        var v = (returnsState.columnFilters[k] || '').toLowerCase();
        if (v) activeFilters.push({ key: k, val: v });
    });
    var filtered = activeFilters.length === 0 ? events : events.filter(function (e) {
        for (var i = 0; i < activeFilters.length; i++) {
            var f = activeFilters[i];
            var col = RETURNS_EVENT_COLUMNS.find(function (c) { return c.key === f.key; });
            if (!col || !col.get) continue;
            var hay = String(col.get(e) || '').toLowerCase();
            if (hay.indexOf(f.val) < 0) return false;
        }
        return true;
    });

    // B: sortable by any column; default is ECN# ascending (Vikas Singh 2026-07-06).
    var sortKey = (returnsState.sort && returnsState.sort.key) || 'ecnNumber';
    var sortDir = (returnsState.sort && returnsState.sort.dir === 'desc') ? -1 : 1;
    function returnsSortVal(e) {
        if (sortKey === '_rcount') return ecnReturnCount[e.ecnNumber || ''] || 0;
        if (sortKey === 'ts') return e.ts || '';
        var col = RETURNS_EVENT_COLUMNS.find(function (c) { return c.key === sortKey; });
        return (col && col.get) ? String(col.get(e) || '') : '';
    }
    filtered.sort(function (a, b) {
        var av = returnsSortVal(a), bv = returnsSortVal(b);
        var cmp;
        if (typeof av === 'number' && typeof bv === 'number') cmp = av - bv;
        else cmp = String(av).localeCompare(String(bv), undefined, { numeric: true, sensitivity: 'base' });
        if (cmp === 0 && sortKey !== 'ecnNumber') {
            cmp = String(a.ecnNumber || '').localeCompare(String(b.ecnNumber || ''), undefined, { numeric: true });
        }
        return cmp * sortDir;
    });

    document.getElementById('returnsEventsCount').textContent = '(' + filtered.length + ' of ' + events.length + ')';

    var html = '<table style="width:100%;border-collapse:collapse;font-size:12px;">';
    // Header row
    html += '<tr style="background:#2c3e50;color:#fff;position:sticky;top:0;z-index:2;">';
    RETURNS_EVENT_COLUMNS.forEach(function (col) {
        var align = col.key === '_rcount' ? 'center' : 'left';
        var titleBase = '';
        if (col.key === '_rcount') titleBase = 'How many times this ECN was returned to Pending in the current window. ';
        if (col.key === 'rejectedBy') titleBase = 'The PCM analyst who returned the ECN to Pending and wrote the comment. ';
        var isSorted = sortKey === col.key;
        var arrow = isSorted ? (sortDir === 1 ? ' ▲' : ' ▼') : '';
        var titleAttr = ' title="' + returnsEsc(titleBase + 'Click to sort') + '"';
        html += '<th onclick="returnsSortBy(\'' + col.key + '\')" style="padding:6px 10px;text-align:' + align +
            ';border:1px solid #444;cursor:pointer;user-select:none;white-space:nowrap;"' + titleAttr + '>' +
            returnsEsc(col.label) + '<span style="font-size:10px;opacity:0.9;">' + arrow + '</span></th>';
    });
    html += '</tr>';
    // Per-column filter row (PT-61). Inputs render only on filterable columns;
    // the rest get a blank cell so the layout stays aligned.
    html += '<tr style="background:#FAFAF7;position:sticky;top:30px;z-index:2;">';
    RETURNS_EVENT_COLUMNS.forEach(function (col) {
        if (!col.filter) {
            html += '<td style="padding:4px 6px;border:1px solid #ccc;"></td>';
        } else {
            var v = (returnsState.columnFilters && returnsState.columnFilters[col.key]) || '';
            html += '<td style="padding:3px 4px;border:1px solid #ccc;">' +
                '<input type="text" data-col-filter="' + col.key + '" value="' + returnsEsc(v) +
                '" placeholder="filter…" style="width:100%;box-sizing:border-box;padding:2px 4px;border:1px solid #d8d8d8;border-radius:3px;font-size:11px;" oninput="returnsOnColumnFilterInput(this)">' +
                '</td>';
        }
    });
    html += '</tr>';

    if (!filtered.length) {
        var colSpan = RETURNS_EVENT_COLUMNS.length;
        html += '<tr><td colspan="' + colSpan + '" style="color:#6B7280;text-align:center;padding:30px;">No matching events.</td></tr>';
        html += '</table>';
        container.innerHTML = html;
        return;
    }

    filtered.forEach(function (e, i) {
        var bg = i % 2 === 0 ? '#fff' : '#FAFAF7';
        var color = RETURNS_CATEGORY_COLORS[returnsActiveCategory(e)] || '#6B7280';
        var ecnLink = e.ecnNumber
            ? '<a href="' + (window.AGILE_WEBCLIENT_URL || 'https://plm.sandisk.com/Agile') + '/object/ECN/' + encodeURIComponent(e.ecnNumber) + '" target="_blank" rel="noopener" style="color:#4a6fa5;text-decoration:none;border-bottom:1px dashed #a0b8d0;">' + returnsEsc(e.ecnNumber) + '</a>'
            : '';
        var comment = (e.comment || '').replace(/\s+/g, ' ').substring(0, 140);
        var rcount = ecnReturnCount[e.ecnNumber || ''] || 1;
        var rcountStyle = rcount >= 3
            ? 'background:#fdeaea;color:#B8342B;font-weight:bold;'
            : (rcount === 2 ? 'background:#fff8e1;color:#C7801B;font-weight:bold;' : 'color:#6B7280;');
        html += '<tr style="background:' + bg + ';cursor:pointer;" onclick="returnsOpenDrilldown(\'' + returnsEsc(e.eventId) + '\')">' +
            '<td style="padding:5px 8px;border:1px solid #ccc;white-space:nowrap;font-family:monospace;font-size:11px;">' + returnsEsc((e.ts || '').substring(0, 16).replace('T', ' ')) + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;white-space:nowrap;">' + ecnLink + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;text-align:center;' + rcountStyle + '">' + rcount + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;">' + returnsEsc(e.fromStatus || '') + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;color:#4a6fa5;font-weight:600;">' + returnsEsc((e.requestor || '').replace(/\s*\(\d+\)$/, '')) + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;color:#0F1720;">' + returnsEsc((e.rejectedBy || '').replace(/\s*\(\d+\)$/, '')) + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;font-size:11px;">' + returnsEsc(e.productLine || '') + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;font-size:11px;color:#0F1720;">' + returnsEsc(e.productTeam || '') + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;"><span style="display:inline-block;padding:1px 8px;border-radius:10px;background:' + color + '22;color:' + color + ';font-weight:600;font-size:11px;">' + returnsEsc(e.category || '') + '</span></td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;font-size:11px;color:#0F1720;">' + returnsEsc(e.changeType || '') + '</td>' +
            '<td style="padding:5px 8px;border:1px solid #ccc;font-size:11px;color:#0F1720;">' + returnsEsc(comment) + (comment.length === 140 ? '…' : '') + '</td>' +
            '</tr>';
    });
    html += '</table>';
    container.innerHTML = html;
}

// PT-61: per-column filter input handler. Updates returnsState and re-renders.
// Re-renders rebuild the input DOM (so we re-focus + restore the cursor on the
// changed column to keep typing fluid).
function returnsOnColumnFilterInput(input) {
    var key = input.getAttribute('data-col-filter');
    if (!key) return;
    var pos = input.selectionStart;
    if (!returnsState.columnFilters) returnsState.columnFilters = {};
    returnsState.columnFilters[key] = input.value;
    if (returnsState.data) returnsRenderEventsTable(returnsState.data.events || []);
    var fresh = document.querySelector('input[data-col-filter="' + key + '"]');
    if (fresh) {
        fresh.focus();
        try { fresh.setSelectionRange(pos, pos); } catch (e) {}
    }
}

// B: click a column header to sort; re-click toggles asc/desc.
function returnsSortBy(key) {
    var s = returnsState.sort || (returnsState.sort = { key: 'ecnNumber', dir: 'asc' });
    if (s.key === key) { s.dir = (s.dir === 'asc') ? 'desc' : 'asc'; }
    else { s.key = key; s.dir = 'asc'; }
    if (returnsState.data) returnsRenderEventsTable(returnsState.data.events || []);
}

function returnsOpenDrilldown(eventId) {
    var panel = document.getElementById('returnsDrilldown');
    var body = document.getElementById('returnsDrilldownBody');
    panel.style.display = '';
    var event = (returnsState.data.events || []).find(function (e) { return e.eventId === eventId; });
    if (!event) { body.innerHTML = '<p>Event not found.</p>'; return; }
    var color = RETURNS_CATEGORY_COLORS[returnsActiveCategory(event)] || '#6B7280';
    body.innerHTML =
        '<div style="font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:.05em;">' + returnsEsc(event.ecnNumber) + '</div>' +
        '<div style="font-size:18px;font-weight:600;color:#0F1720;margin-top:4px;">' + returnsEsc(event.description || '').substring(0, 200) + '</div>' +
        '<div style="margin-top:14px;display:grid;grid-template-columns:1fr 1fr;gap:10px;font-size:12px;">' +
            '<div><span style="color:#6B7280;">Returned from:</span> <strong>' + returnsEsc(event.fromStatus) + '</strong></div>' +
            '<div><span style="color:#6B7280;">By:</span> ' + returnsEsc((event.rejectedBy || '').replace(/\s*\(\d+\)$/, '')) + '</div>' +
            '<div><span style="color:#6B7280;">Requestor:</span> <strong style="color:#4a6fa5;">' + returnsEsc((event.requestor || '').replace(/\s*\(\d+\)$/, '')) + '</strong></div>' +
            '<div><span style="color:#6B7280;">When:</span> <span style="font-family:monospace;">' + returnsEsc((event.ts || '').replace('T', ' ').substring(0, 16)) + '</span></div>' +
            '<div><span style="color:#6B7280;">Product line:</span> ' + returnsEsc(event.productLine || '') + '</div>' +
            '<div><span style="color:#6B7280;">Product team:</span> ' + returnsEsc(event.productTeam || '') + '</div>' +
            '<div style="grid-column:1/-1;">' +
                '<span style="color:#6B7280;">Category:</span> <span style="display:inline-block;padding:2px 10px;border-radius:10px;background:' + color + '22;color:' + color + ';font-weight:600;">' + returnsEsc(event.category || '') + '</span>' +
                ' &nbsp; <span style="color:#6B7280;">Change type:</span> <em>' + returnsEsc(event.changeType || '') + '</em>' +
            '</div>' +
        '</div>' +
        '<div style="margin-top:16px;padding:12px 14px;background:#FAFAF7;border:1px solid #E8E6DF;border-radius:6px;font-size:13px;line-height:1.5;color:#0F1720;white-space:pre-wrap;">' + returnsEsc(event.comment || '(no comment)') + '</div>' +
        '<div style="margin-top:18px;padding:12px 14px;background:linear-gradient(135deg,#fafbfc,#f3f6fa);border-left:4px solid #4a6fa5;border-radius:0 6px 6px 0;">' +
            '<div style="font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:.05em;font-weight:600;margin-bottom:4px;">📝 AI Explanation</div>' +
            '<div id="returnsDrilldownAi" style="font-size:13px;color:#0F1720;line-height:1.5;">Generating…</div>' +
        '</div>';
    fetch('/api/ecn-report/returns/explain/' + encodeURIComponent(eventId))
        .then(function (r) { return r.json(); })
        .then(function (d) {
            var aiEl = document.getElementById('returnsDrilldownAi');
            if (!aiEl) return;
            if (d.success) {
                aiEl.innerHTML = returnsRenderBullets(d.explanation);
            } else {
                aiEl.innerHTML = '<span style="color:#B8342B;">AI unavailable: ' + returnsEsc(d.message || '') + '</span>';
            }
        });
}

function returnsCloseDrilldown() {
    document.getElementById('returnsDrilldown').style.display = 'none';
}

// ---------------------------------------------------------------------------
// Email this view
// ---------------------------------------------------------------------------

function returnsExportExcel() {
    var r = returnsResolveRange();
    if (!r) { appAlert('Pick a date range first'); return; }
    window.location.href = '/api/ecn-report/returns/export?from=' + r.from + '&to=' + r.to;
}

function returnsEmailMe() {
    var r = returnsResolveRange();
    if (!r) { appAlert('Pick a date range first'); return; }
    var btn = event && event.target ? event.target : null;
    if (btn) { btn.disabled = true; btn.textContent = '✉ Sending…'; }
    fetch('/api/ecn-report/returns/email-me', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ from: r.from, to: r.to })
    })
    .then(function (resp) { return resp.json(); })
    .then(function (d) {
        if (btn) { btn.disabled = false; btn.textContent = '✉ Send to me'; }
        appAlert(d.message || (d.success ? 'Sent' : 'Failed'));
    })
    .catch(function (err) {
        if (btn) { btn.disabled = false; btn.textContent = '✉ Send to me'; }
        appAlert('Email failed: ' + err.message);
    });
}

function returnsEmailThisView() {
    var r = returnsResolveRange();
    if (!r) { appAlert('Pick a date range first'); return; }
    var btn = event && event.target ? event.target : null;
    appConfirm('Send this view to the configured recipients?\n\nWindow: ' + r.from + ' → ' + r.to, { okText: 'Send' }).then(function (ok) {
        if (!ok) return;
        if (btn) { btn.disabled = true; btn.textContent = '✉ Sending…'; }
        fetch('/api/ecn-report/returns/email', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ from: r.from, to: r.to })
        })
        .then(function (resp) { return resp.json(); })
        .then(function (d) {
            if (btn) { btn.disabled = false; btn.textContent = '✉ Email this view'; }
            appAlert(d.message || (d.success ? 'Sent' : 'Failed'));
        })
        .catch(function (err) {
            if (btn) { btn.disabled = false; btn.textContent = '✉ Email this view'; }
            appAlert('Email failed: ' + err.message);
        });
    });
}

// ---------------------------------------------------------------------------
// Recipients modal
// ---------------------------------------------------------------------------

function returnsShowRecipientsModal() {
    document.getElementById('returnsRecipientsModal').style.display = 'flex';
    fetch('/api/ecn-report/returns/recipients')
        .then(function (r) { return r.json(); })
        .then(function (d) {
            document.getElementById('returnsRecipientsTextarea').value = (d.recipients || []).join('\n');
        });
}

function returnsCloseRecipientsModal() {
    document.getElementById('returnsRecipientsModal').style.display = 'none';
}

function returnsSaveRecipients() {
    var text = document.getElementById('returnsRecipientsTextarea').value;
    var list = text.split(/[\n,;]+/).map(function (s) { return s.trim(); }).filter(function (s) { return s; });
    fetch('/api/ecn-report/returns/recipients', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ recipients: list })
    })
    .then(function (r) { return r.json(); })
    .then(function (d) {
        if (d.success) returnsCloseRecipientsModal();
        else appAlert(d.message || 'Save failed');
    });
}

// ---------------------------------------------------------------------------
// Bootstrap
// ---------------------------------------------------------------------------

function returnsEsc(s) {
    if (s === null || s === undefined) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// Render a markdown-bullet response (lines starting with -, *, or •) as an HTML
// list. Lines that don't start with a bullet marker are kept as paragraphs above
// the list, which gracefully handles older AI responses that returned prose.
function returnsRenderBullets(text) {
    if (!text) return '';
    var lines = String(text).split(/\r?\n/);
    var html = '';
    var inList = false;
    var paraBuf = '';
    function flushPara() {
        if (paraBuf.trim()) html += '<p style="margin:0 0 8px 0;">' + returnsEsc(paraBuf.trim()) + '</p>';
        paraBuf = '';
    }
    function applyInlineBold(s) {
        // Render **bold** runs to <strong>; escape everything else.
        var parts = s.split(/\*\*/);
        var out = '';
        for (var i = 0; i < parts.length; i++) {
            out += i % 2 === 0 ? returnsEsc(parts[i]) : '<strong>' + returnsEsc(parts[i]) + '</strong>';
        }
        return out;
    }
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        var m = line.match(/^\s*[-*\u2022]\s+(.+)$/);
        if (m) {
            if (!inList) { flushPara(); html += '<ul style="margin:0; padding-left:18px;">'; inList = true; }
            html += '<li style="margin:4px 0; line-height:1.5;">' + applyInlineBold(m[1]) + '</li>';
        } else if (line.trim() === '') {
            if (inList) { html += '</ul>'; inList = false; }
            flushPara();
        } else {
            if (inList) { html += '</ul>'; inList = false; }
            paraBuf += (paraBuf ? ' ' : '') + line;
        }
    }
    if (inList) html += '</ul>';
    flushPara();
    return html;
}

// On page load (and after the ECN Report tab opens), restore the previously
// selected view. We hook into the existing showTab() flow by polling for the
// ECN Report panel becoming visible — works without modifying app.js.
(function () {
    function tryInit() {
        var panel = document.getElementById('panelEcnReport');
        if (panel && panel.style.display !== 'none' && !returnsState._inited) {
            returnsState._inited = true;
            var stored = null;
            try { stored = localStorage.getItem('ecnReportView'); } catch (e) {}
            if (stored === 'returns') {
                ecnSwitchView('returns');
            }
        }
    }
    setInterval(tryInit, 1000);
    document.addEventListener('DOMContentLoaded', tryInit);
})();

// ---------------------------------------------------------------------------
// Frozen-period snapshots dropdown
// ---------------------------------------------------------------------------

function returnsLoadPeriods() {
    fetch('/api/ecn-report/returns/periods')
        .then(function (r) { return r.json(); })
        .then(function (d) {
            if (!d.success) return;
            if (typeof d.enhancedView === 'boolean') RETURNS_ENHANCED_VIEW = d.enhancedView;
            var sel = document.getElementById('returnsPeriodSelect');
            if (!sel) return;
            // Vikas Singh (Jul 2026): drop the Weeks group — it cluttered the list.
            // Quarters + Months are sufficient. (Weekly snapshots are still written
            // and reachable by id; they're just not listed in the dropdown.) The
            // classic view (RETURNS_ENHANCED_VIEW=false) restores the Weeks group.
            var groups = { quarter: [], month: [], week: [] };
            (d.periods || []).forEach(function (p) {
                if (groups[p.type]) groups[p.type].push(p);
            });
            var html = '<option value="live">Live (date range)</option>';
            function grp(label, arr) {
                if (!arr.length) return '';
                var s = '<optgroup label="' + label + '">';
                arr.forEach(function (p) {
                    s += '<option value="' + returnsEsc(p.id) + '">' + returnsEsc(p.label) + '</option>';
                });
                return s + '</optgroup>';
            }
            html += grp('Quarters', groups.quarter) + grp('Months', groups.month);
            if (!RETURNS_ENHANCED_VIEW) html += grp('Weeks', groups.week.slice(0, 26));
            sel.innerHTML = html;
            sel.value = returnsState.period;
        })
        .catch(function () {});
}

function returnsHandlePeriodChange(val) {
    returnsState.period = val;
    var rangeWrap = document.getElementById('returnsRangeControls');
    var caption = document.getElementById('returnsFrozenCaption');
    if (val === 'live') {
        if (rangeWrap) rangeWrap.style.display = '';
        if (caption) caption.style.display = 'none';
        returnsLoad();
        return;
    }
    if (rangeWrap) rangeWrap.style.display = 'none';
    document.getElementById('returnsStatus').textContent = 'Loading frozen snapshot ' + val + '…';
    fetch('/api/ecn-report/returns/data?period=' + encodeURIComponent(val))
        .then(function (r) { return r.json(); })
        .then(function (d) {
            if (!d.success) {
                document.getElementById('returnsStatus').textContent = d.message || 'Snapshot load failed.';
                return;
            }
            returnsState.data = d;
            if (d.auditGoLiveDate) RETURNS_AUDIT_GOLIVE = d.auditGoLiveDate;
            returnsRender();
            if (caption) {
                caption.style.display = '';
                caption.textContent = 'Frozen snapshot · ' + (d.label || val)
                    + (d.generatedAt ? ' · generated ' + new Date(d.generatedAt).toLocaleString() : '');
            }
        })
        .catch(function (err) {
            document.getElementById('returnsStatus').textContent = 'Error: ' + err.message;
        });
}
