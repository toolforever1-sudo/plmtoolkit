// Volume Reports — sub-tab under ECN Report.
// Inputs: Manager (single-select from access DL) + Begin/End dates + flags.
// Calls /api/ecn-report/volume/run and renders KPI tiles + detail table.

var volumeState = {
    managers: [],
    managersLoadedAt: 0,
    rows: [],
    summary: null,
    initialized: false,
    activeFilters: { changeType: null, priority: null, createUser: null }, // HANDOFF #2 KPI/People click filters
    selectedManagerLabel: ''  // captured for the drawer's context recap pill
};

function volumeInit() {
    if (volumeState.initialized) return;
    volumeState.initialized = true;
    // Default date window: last 30 days, inclusive end = today
    var today = new Date();
    var from = new Date(); from.setDate(from.getDate() - 30);
    document.getElementById('volumeFromDate').value = from.toISOString().substring(0, 10);
    document.getElementById('volumeToDate').value = today.toISOString().substring(0, 10);
    // Reflect persisted row-mode preference in the segmented toggle even
    // before any data is loaded.
    _volumePaintRowModeButtons(volumeRowMode());
    volumeLoadManagers();
    // Surface "Last time you used …" recall the moment the panel renders
    // (HANDOFF #4) so returning users see it immediately.
    teamReportRefreshLastUsed();
}

// ---------- HANDOFF #2: Date preset pills ----------
// 5 preset pills (This month / Last month / Last quarter / YTD / Custom).
// Custom\u2026 is the live state when the user types in the date inputs.
function volumeApplyDatePreset(preset) {
    var today = new Date();
    var from, to;
    switch (preset) {
        case 'thisMonth': {
            from = new Date(today.getFullYear(), today.getMonth(), 1);
            to = today;
            break;
        }
        case 'lastMonth': {
            from = new Date(today.getFullYear(), today.getMonth() - 1, 1);
            to = new Date(today.getFullYear(), today.getMonth(), 0); // last day of prev month
            break;
        }
        case 'lastQuarter': {
            // Last *completed* calendar quarter, regardless of where today sits.
            var q = Math.floor(today.getMonth() / 3); // 0..3 of current
            // previous quarter index (wraps to last quarter of prior year)
            var py = q === 0 ? today.getFullYear() - 1 : today.getFullYear();
            var pq = q === 0 ? 3 : q - 1;
            from = new Date(py, pq * 3, 1);
            to = new Date(py, pq * 3 + 3, 0);
            break;
        }
        case 'ytd': {
            from = new Date(today.getFullYear(), 0, 1);
            to = today;
            break;
        }
        case 'custom':
        default:
            preset = 'custom';
            // leave date inputs as-is
            break;
    }
    if (preset !== 'custom') {
        document.getElementById('volumeFromDate').value = from.toISOString().substring(0, 10);
        document.getElementById('volumeToDate').value = to.toISOString().substring(0, 10);
    }
    _volumePaintDatePreset(preset);
}
function volumeMarkCustom() { _volumePaintDatePreset('custom'); }
function _volumePaintDatePreset(preset) {
    document.querySelectorAll('#volumeDatePresets button[data-preset]').forEach(function (btn) {
        var on = btn.getAttribute('data-preset') === preset;
        btn.style.background = on ? '#fff' : 'transparent';
        btn.style.color = on ? '#0F1720' : '#6B7280';
        btn.style.fontWeight = on ? '600' : '400';
        btn.style.boxShadow = on ? '0 1px 2px rgba(0,0,0,0.06)' : 'none';
        btn.setAttribute('aria-checked', String(on));
    });
}

// ---------- HANDOFF #2: Status pill + Run/Export header buttons ----------
function _volumeSetStatusPill(text, kind) {
    var pill = document.getElementById('volumeStatusPill');
    if (!pill) return;
    if (!text) { pill.style.display = 'none'; return; }
    var bg = '#e8f5e9', fg = '#155724';
    if (kind === 'busy') { bg = '#fff8e1'; fg = '#7a5b0c'; }
    else if (kind === 'err') { bg = '#fdeaea'; fg = '#B8342B'; }
    pill.style.display = 'inline-flex';
    pill.style.background = bg;
    pill.style.color = fg;
    pill.textContent = text;
}
function _volumeEnableHeaderButtons(enabled) {
    ['volumeOpenDrawerBtn', 'volumeHeaderExportBtn'].forEach(function (id) {
        var b = document.getElementById(id);
        if (!b) return;
        b.disabled = !enabled;
        b.style.opacity = enabled ? '1' : '0.5';
        b.style.cursor = enabled ? 'pointer' : 'not-allowed';
    });
}

function volumeLoadManagers() {
    var sel = document.getElementById('volumeManagerSelect');
    sel.innerHTML = '<option value="">Loading managers...</option>';
    fetch('/api/ecn-report/volume/managers')
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (!data.success) {
                sel.innerHTML = '<option value="">Failed to load: ' + volumeEsc(data.message || 'unknown') + '</option>';
                return;
            }
            volumeState.managers = data.managers || [];
            sel.innerHTML = '<option value="">Select a manager...</option>';
            volumeState.managers.forEach(function (m) {
                var opt = document.createElement('option');
                opt.value = m.username;
                opt.textContent = (m.displayName || m.username) + ' — ' + m.username;
                sel.appendChild(opt);
            });
        })
        .catch(function (err) {
            sel.innerHTML = '<option value="">Failed: ' + volumeEsc(err.message || 'error') + '</option>';
        });
}

function volumeRunReport() {
    var managerSel = document.getElementById('volumeManagerSelect');
    var manager = managerSel.value;
    volumeState.selectedManagerLabel = manager
        ? (managerSel.options[managerSel.selectedIndex].textContent || manager)
        : '';
    var fromDate = document.getElementById('volumeFromDate').value;
    var toDate = document.getElementById('volumeToDate').value;
    var transitive = document.getElementById('volumeTransitive').checked;
    var includeManager = document.getElementById('volumeIncludeManager').checked;
    var btn = document.getElementById('volumeRunBtn');

    if (!manager) { _volumeSetStatusPill('Pick a manager.', 'err'); return; }
    if (!fromDate || !toDate) { _volumeSetStatusPill('Pick begin and end dates.', 'err'); return; }

    btn.disabled = true;
    btn.innerHTML = '<span>\u23F3 Running\u2026</span>';
    _volumeSetStatusPill('Resolving reports and querying Agile\u2026', 'busy');
    var t0 = Date.now();
    document.getElementById('volumeKpiTiles').style.display = 'none';
    document.getElementById('volumeReportsBox').style.display = 'none';
    document.getElementById('volumeWarnings').style.display = 'none';
    document.getElementById('volumeRowsGroup').style.display = 'none';
    _volumeEnableHeaderButtons(false);
    // Reset any active KPI/People filters from a prior run.
    volumeState.activeFilters = { changeType: null, priority: null, createUser: null };

    fetch('/api/ecn-report/volume/run', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            managerUsername: manager,
            fromDate: fromDate,
            toDate: toDate,
            transitive: transitive,
            includeManager: includeManager
        })
    })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            btn.disabled = false;
            btn.innerHTML = '<span>&#9654; Run Report</span><kbd style="background:rgba(255,255,255,0.18); border:1px solid rgba(255,255,255,0.3); border-radius:3px; padding:1px 5px; font-size:10px; font-family:\'IBM Plex Mono\',Consolas,monospace;">R</kbd>';
            if (!data.success) {
                _volumeSetStatusPill(data.message || 'Run failed.', 'err');
                return;
            }
            volumeState.rows = data.rows || [];
            volumeState.summary = data.summary || {};
            var rowCount = (data.summary && data.summary.totalRows) || volumeState.rows.length || 0;
            var dur = ((Date.now() - t0) / 1000).toFixed(1);
            _volumeSetStatusPill('\u2713 Done \u00b7 ' + rowCount.toLocaleString() + ' rows \u00b7 ' + dur + 's', 'ok');
            volumeRenderReports(data.reports || []);
            volumeRenderKpis(data.summary || {});
            volumeRenderWarnings(data.warnings || []);
            _volumeApplyAndRender();
            _volumeEnableHeaderButtons(volumeState.rows.length > 0);
            if (typeof teamReportShowIfHasRows === 'function') teamReportShowIfHasRows();
        })
        .catch(function (err) {
            btn.disabled = false;
            btn.innerHTML = '<span>&#9654; Run Report</span><kbd style="background:rgba(255,255,255,0.18); border:1px solid rgba(255,255,255,0.3); border-radius:3px; padding:1px 5px; font-size:10px; font-family:\'IBM Plex Mono\',Consolas,monospace;">R</kbd>';
            _volumeSetStatusPill('Network error: ' + (err.message || err), 'err');
        });
}

// Apply active filters and render. Filters AND together. ECO/MCO/AML are
// single-select via changeType so toggling one while another is active
// just swaps. People-Queried chip is single-select via createUser.
function _volumeApplyAndRender() {
    var f = volumeState.activeFilters;
    var hasFilter = !!(f.changeType || f.priority || f.createUser);
    var rows = !hasFilter ? volumeState.rows : volumeState.rows.filter(function (r) {
        if (f.changeType && r.changeType !== f.changeType) return false;
        if (f.priority && r.priority !== f.priority) return false;
        if (f.createUser && r.createUser !== f.createUser) return false;
        return true;
    });
    volumeRenderRows(rows);
}

// ---------- HANDOFF #2: KPI tile click \u2192 changeType filter ----------
function volumeToggleChangeTypeFilter(type) {
    if (volumeState.activeFilters.changeType === type) {
        volumeState.activeFilters.changeType = null;
    } else {
        volumeState.activeFilters.changeType = type;
    }
    _volumePaintKpiActive();
    _volumeApplyAndRender();
}
function _volumePaintKpiActive() {
    var active = volumeState.activeFilters.changeType;
    document.querySelectorAll('[data-kpi-type]').forEach(function (tile) {
        var t = tile.getAttribute('data-kpi-type');
        if (t === active) {
            tile.style.borderColor = '#4a6fa5';
            tile.style.boxShadow = '0 0 0 2px rgba(74,111,165,0.18)';
        } else {
            tile.style.borderColor = '#E8E6DF';
            tile.style.boxShadow = 'none';
        }
    });
}

// ---------- HANDOFF #2: People Queried chip toggle ----------
function volumeTogglePersonFilter(username) {
    if (!username) return;
    if (volumeState.activeFilters.createUser === username) {
        volumeState.activeFilters.createUser = null;
    } else {
        volumeState.activeFilters.createUser = username;
    }
    _volumePaintPersonActive();
    _volumeApplyAndRender();
}
function _volumePaintPersonActive() {
    var active = volumeState.activeFilters.createUser;
    document.querySelectorAll('[data-person-username]').forEach(function (chip) {
        var u = chip.getAttribute('data-person-username');
        if (u === active) {
            chip.style.background = '#e8f0fe';
            chip.style.borderColor = '#4a6fa5';
            chip.style.color = '#1a3a5c';
        } else {
            chip.style.background = '#FAFAF7';
            chip.style.borderColor = '#E8E6DF';
            chip.style.color = '';
        }
    });
}

function volumeRenderKpis(summary) {
    var box = document.getElementById('volumeKpiTiles');
    var byType = summary.byChangeType || {};
    var ecoRows = (byType.ECO && byType.ECO.rows) || 0;
    var mcoRows = (byType.MCO && byType.MCO.rows) || 0;
    var amlRows = (byType.AML && byType.AML.rows) || 0;
    var ecoDist = (byType.ECO && byType.ECO.distinct) || 0;
    var mcoDist = (byType.MCO && byType.MCO.distinct) || 0;
    var amlDist = (byType.AML && byType.AML.distinct) || 0;
    var html = '<div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(170px, 1fr)); gap:12px;">';
    html += volumeKpiTile('Total Rows',         summary.totalRows || 0,        'one row per affected item');
    html += volumeKpiTile('Distinct Changes',   summary.distinctChanges || 0,  'unique change numbers');
    html += volumeKpiTile('ECO',                ecoRows,                       ecoDist + ' distinct', 'ECO');
    html += volumeKpiTile('MCO',                mcoRows,                       mcoDist + ' distinct', 'MCO');
    html += volumeKpiTile('AML',                amlRows,                       amlDist + ' distinct', 'AML');
    html += '</div>';
    box.innerHTML = html;
    box.style.display = '';
    _volumePaintKpiActive();
}

// Filterable tiles get a data-kpi-type attribute + click handler. Informational
// tiles (Total Rows, Distinct Changes) stay non-interactive.
function volumeKpiTile(label, value, hint, filterType) {
    var clickable = !!filterType;
    var attrs = clickable
        ? ' data-kpi-type="' + volumeEsc(filterType) + '" onclick="volumeToggleChangeTypeFilter(\'' + filterType + '\')" role="button" tabindex="0" style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:14px 16px; cursor:pointer; transition:border-color 0.12s, box-shadow 0.12s;"'
        : ' style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:14px 16px;"';
    return '<div' + attrs + '>' +
        '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:0.06em; font-weight:600; display:flex; align-items:center; gap:6px;">' +
            volumeEsc(label) +
            (clickable ? ' <span style="font-size:9px; color:#9ca3af; font-weight:500; letter-spacing:0;">(click to filter)</span>' : '') +
        '</div>' +
        '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:26px; font-weight:600; color:#0F1720; margin-top:2px;">' + value + '</div>' +
        '<div style="font-size:11px; color:#6B7280; margin-top:2px;">' + volumeEsc(hint || '') + '</div>' +
        '</div>';
}

function volumeRenderReports(reports) {
    var box = document.getElementById('volumeReportsBox');
    var body = document.getElementById('volumeReportsBody');
    var count = document.getElementById('volumeReportsCount');
    if (!reports.length) { box.style.display = 'none'; return; }
    count.textContent = '(' + reports.length + ' \u00b7 click a chip to filter)';
    var html = '<div style="display:flex; flex-wrap:wrap; gap:6px;">';
    reports.forEach(function (r) {
        var name = r.displayName || r.samAccountName || '';
        // The ChangeSearchService builds createUser as "Last, First (loginid)".
        // Match that exact string so chip-click filters reliably.
        var asCreator = name + ' (' + (r.samAccountName || '') + ')';
        var tag = r.isManager ? '\U0001F451 ' : '';
        var empId = r.employeeId ? '<span style="color:#6B7280; font-family:monospace;">' + volumeEsc(r.employeeId) + '</span>' :
                                   '<span style="color:#B8342B;">no AD employeeID</span>';
        html += '<button type="button" data-person-username="' + volumeEsc(asCreator) + '" onclick="volumeTogglePersonFilter(\'' + volumeEsc(asCreator).replace(/\u0027/g, "&#39;") + '\')" style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:14px; padding:3px 10px; font-size:12px; cursor:pointer; font-family:inherit;">' +
            tag + volumeEsc(name) + ' \u00b7 ' + empId + '</button>';
    });
    html += '</div>';
    body.innerHTML = html;
    box.style.display = '';
    _volumePaintPersonActive();
}

function volumeRenderWarnings(warnings) {
    var box = document.getElementById('volumeWarnings');
    if (!warnings || !warnings.length) { box.style.display = 'none'; return; }
    box.innerHTML = '<strong>Warnings:</strong><ul style="margin:6px 0 0 18px; padding:0;">' +
        warnings.map(function (w) { return '<li>' + volumeEsc(w) + '</li>'; }).join('') + '</ul>';
    box.style.display = '';
}

// HANDOFF #3: persisted row-mode preference. Group view collapses
// the per-affected-item rows under one parent row per Change # so the
// table opens at ~423 rows instead of 5,614. Toggle persists per user.
var VOLUME_ROW_MODE_KEY = 'plm.ecnRowMode';
function volumeRowMode() {
    var v = localStorage.getItem(VOLUME_ROW_MODE_KEY);
    return v === 'flat' ? 'flat' : 'group';
}
function volumeSetRowMode(mode) {
    if (mode !== 'group' && mode !== 'flat') return;
    localStorage.setItem(VOLUME_ROW_MODE_KEY, mode);
    _volumePaintRowModeButtons(mode);
    if (volumeState.rows && volumeState.rows.length) volumeRenderRows(volumeState.rows);
}
function _volumePaintRowModeButtons(mode) {
    var g = document.getElementById('volumeRowModeGroup');
    var f = document.getElementById('volumeRowModeFlat');
    if (!g || !f) return;
    var on = { background:'#fff', color:'#0F1720', fontWeight:'600', boxShadow:'0 1px 2px rgba(0,0,0,0.06)' };
    var off = { background:'transparent', color:'#6B7280', fontWeight:'500', boxShadow:'none' };
    var apply = function(el, s) { Object.keys(s).forEach(function(k) { el.style[k] = s[k]; }); };
    apply(g, mode === 'group' ? on : off); g.setAttribute('aria-selected', mode === 'group');
    apply(f, mode === 'flat'  ? on : off); f.setAttribute('aria-selected', mode === 'flat');
}

// Group rows by Change # so the parent row can summarise N affected items.
function _volumeGroupByChange(rows) {
    var byNumber = {};
    var order = [];
    rows.forEach(function(r) {
        var k = r.number || '(no number)';
        if (!byNumber[k]) { byNumber[k] = []; order.push(k); }
        byNumber[k].push(r);
    });
    return order.map(function(k) { return { number: k, items: byNumber[k] }; });
}

function volumeRenderRows(rows) {
    var group = document.getElementById('volumeRowsGroup');
    var container = document.getElementById('volumeRowsContainer');
    var count = document.getElementById('volumeRowsCount');

    if (!rows.length) {
        count.textContent = '(0)';
        container.innerHTML = '<p style="color:#6B7280; text-align:center; padding:30px;">No matching changes.</p>';
        group.style.display = '';
        return;
    }

    var mode = volumeRowMode();
    _volumePaintRowModeButtons(mode);

    if (mode === 'flat') {
        count.textContent = '(' + rows.length.toLocaleString() + ' rows)';
        container.innerHTML = _volumeRenderFlat(rows);
    } else {
        var grouped = _volumeGroupByChange(rows);
        count.textContent = '(' + grouped.length.toLocaleString() + ' changes \u00b7 ' + rows.length.toLocaleString() + ' affected items)';
        container.innerHTML = _volumeRenderGrouped(grouped);
    }
    group.style.display = '';
}

function _volumeRenderFlat(rows) {
    var html = '<table style="width:100%; border-collapse:collapse; font-size:12px;">';
    html += '<thead style="position:sticky; top:0; background:#2c3e50; color:#fff; z-index:2;"><tr>';
    ['Number', 'Type', 'Status', 'Priority', 'Item', 'Description', 'Product Lines', 'Create User', 'Date Originated', 'Date Released', 'Requestor', 'Analyst', 'Classification'].forEach(function (h) {
        html += '<th style="padding:7px 10px; text-align:left; font-weight:600; border-bottom:1px solid #444;">' + volumeEsc(h) + '</th>';
    });
    html += '</tr></thead><tbody>';
    rows.forEach(function (r, idx) {
        var bg = idx % 2 === 0 ? '#fff' : '#FAFAF7';
        html += '<tr class="vol-row" style="background:' + bg + ';">';
        html += '<td style="padding:6px 10px; font-family:monospace; font-weight:600; color:#0F1720;">' + volumeEsc(r.number) + '</td>';
        html += '<td style="padding:6px 10px;"><span style="font-family:monospace; font-size:11px; padding:2px 6px; border-radius:3px; background:' + volumeTypeColor(r.changeType) + '; color:#fff;">' + volumeEsc(r.changeType) + '</span></td>';
        html += '<td style="padding:6px 10px;">' + volumeEsc(r.status || '') + '</td>';
        html += '<td style="padding:6px 10px;">' + volumeEsc(r.priority || '') + '</td>';
        html += '<td style="padding:6px 10px; font-family:monospace;">' + volumeEsc(r.itemNumberAffected || '') + '</td>';
        html += '<td style="padding:6px 10px; max-width:280px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="' + volumeEsc(r.description || '') + '">' + volumeEsc(r.description || '') + '</td>';
        html += '<td style="padding:6px 10px;" title="' + volumeEsc(r.productLines || '') + '">' + volumeEsc(r.productLines || '') + '</td>';
        html += '<td style="padding:6px 10px; color:#4a6fa5;">' + volumeEsc(r.createUser || '') + '</td>';
        html += '<td style="padding:6px 10px; font-family:monospace; font-size:11px;">' + volumeEsc(r.dateOriginated || '') + '</td>';
        html += '<td style="padding:6px 10px; font-family:monospace; font-size:11px;">' + volumeEsc(r.dateReleased || '') + '</td>';
        html += '<td style="padding:6px 10px;">' + volumeEsc(r.requestor || '') + '</td>';
        html += '<td style="padding:6px 10px;">' + volumeEsc(r.analyst || '') + '</td>';
        html += '<td style="padding:6px 10px; font-size:11px;" title="' + volumeEsc(r.classification || '') + '">' + volumeEsc(r.classification || '') + '</td>';
        html += '</tr>';
    });
    html += '</tbody></table>';
    return html;
}

function _volumeRenderGrouped(grouped) {
    var html = '<table style="width:100%; border-collapse:collapse; font-size:12px;">';
    html += '<thead style="position:sticky; top:0; background:#2c3e50; color:#fff; z-index:2;"><tr>';
    ['', 'Change #', 'Type', 'Priority', 'Items', 'Program', 'Summary', 'Owner', 'Released', 'Done', 'Category'].forEach(function (h) {
        html += '<th style="padding:7px 10px; text-align:left; font-weight:600; border-bottom:1px solid #444;">' + volumeEsc(h) + '</th>';
    });
    html += '</tr></thead><tbody>';

    grouped.forEach(function (g, idx) {
        var head = g.items[0];
        var bg = idx % 2 === 0 ? '#fff' : '#FAFAF7';
        var rowId = 'volgrp-' + idx;
        // Parent group row — clickable to expand.
        html += '<tr class="vol-grp-head" data-grp="' + rowId + '" style="background:' + bg + '; cursor:pointer;" onclick="volumeToggleGroup(\'' + rowId + '\')">';
        html += '<td style="padding:6px 4px 6px 10px; width:18px;"><span class="vol-grp-caret" id="' + rowId + '-caret" style="display:inline-block; transition:transform 0.12s; color:#6B7280;">&#9656;</span></td>';
        html += '<td style="padding:6px 10px; font-family:monospace; font-weight:600; color:#0F1720;">' + volumeEsc(g.number) + '</td>';
        html += '<td style="padding:6px 10px;"><span style="font-family:monospace; font-size:11px; padding:2px 6px; border-radius:3px; background:' + volumeTypeColor(head.changeType) + '; color:#fff;">' + volumeEsc(head.changeType) + '</span></td>';
        html += '<td style="padding:6px 10px;">' + volumeEsc(head.priority || '') + '</td>';
        html += '<td style="padding:6px 10px; color:#6B7280; font-variant-numeric:tabular-nums;">\u00d7' + g.items.length + '</td>';
        html += '<td style="padding:6px 10px;" title="' + volumeEsc(head.productLines || '') + '">' + _truncate(volumeEsc(head.productLines || ''), 60) + '</td>';
        html += '<td style="padding:6px 10px; max-width:320px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="' + volumeEsc(head.description || '') + '">' + volumeEsc(head.description || '') + '</td>';
        html += '<td style="padding:6px 10px; color:#4a6fa5;">' + volumeEsc(head.createUser || '') + '</td>';
        html += '<td style="padding:6px 10px; font-family:monospace; font-size:11px;" title="Released">' + volumeEsc(head.dateReleased || '\u2014') + '</td>';
        html += '<td style="padding:6px 10px; font-family:monospace; font-size:11px;" title="Originated">' + volumeEsc(head.dateOriginated || '\u2014') + '</td>';
        html += '<td style="padding:6px 10px; font-size:11px;" title="' + volumeEsc(head.classification || '') + '">' + _truncate(volumeEsc(head.classification || ''), 32) + '</td>';
        html += '</tr>';

        // Child rows — hidden by default; one per affected item with tree connector.
        g.items.forEach(function (r, ci) {
            var last = ci === g.items.length - 1;
            var connector = last ? '\u2514\u2500' : '\u251c\u2500';
            html += '<tr class="vol-grp-child" data-parent="' + rowId + '" style="display:none; background:#FAFAF7;">';
            html += '<td></td>';
            html += '<td style="padding:5px 10px; font-family:monospace; color:#6B7280; font-size:11px;">' + connector + ' item</td>';
            html += '<td colspan="2" style="padding:5px 10px; font-family:monospace; font-size:11px; color:#0F1720;">' + volumeEsc(r.itemNumberAffected || '') + '</td>';
            html += '<td colspan="2" style="padding:5px 10px; color:#6B7280; font-size:11px;">' + volumeEsc(r.status || '') + '</td>';
            html += '<td style="padding:5px 10px; font-size:11px; color:#0F1720;" title="' + volumeEsc(r.description || '') + '">' + _truncate(volumeEsc(r.description || ''), 60) + '</td>';
            html += '<td style="padding:5px 10px; font-size:11px; color:#6B7280;">' + volumeEsc(r.requestor || '') + '</td>';
            html += '<td colspan="2" style="padding:5px 10px; font-family:monospace; font-size:11px; color:#6B7280;">' + volumeEsc(r.dateReleased || r.dateOriginated || '') + '</td>';
            html += '<td style="padding:5px 10px; font-size:11px; color:#6B7280;" title="' + volumeEsc(r.classification || '') + '">' + _truncate(volumeEsc(r.classification || ''), 32) + '</td>';
            html += '</tr>';
        });
    });
    html += '</tbody></table>';
    return html;
}

function _truncate(s, n) {
    if (!s) return '';
    if (s.length <= n) return s;
    return s.substring(0, n - 1) + '\u2026';
}

function volumeToggleGroup(rowId) {
    var caret = document.getElementById(rowId + '-caret');
    var children = document.querySelectorAll('tr.vol-grp-child[data-parent="' + rowId + '"]');
    if (!children.length) return;
    var open = children[0].style.display !== 'none';
    children.forEach(function(tr) { tr.style.display = open ? 'none' : ''; });
    if (caret) caret.style.transform = open ? '' : 'rotate(90deg)';
}

// Page-level hotkeys:
//   "/" focuses the filter input  (HANDOFF #3)
//   "R" runs the volume report    (HANDOFF #2)
// Both skip when focus is in an editable field, and only fire when the
// Volume Reports panel is the active view.
document.addEventListener('keydown', function (e) {
    if (e.key !== '/' && (e.key !== 'r' && e.key !== 'R')) return;
    var t = e.target;
    if (!t) return;
    var tag = (t.tagName || '').toUpperCase();
    if (tag === 'INPUT' || tag === 'TEXTAREA' || t.isContentEditable) return;
    // Only when Volume Reports view is visible.
    var view = document.getElementById('volumeReportView');
    if (!view || view.style.display === 'none') return;

    if (e.key === '/') {
        var f = document.getElementById('volumeRowsSearch');
        var grp = document.getElementById('volumeRowsGroup');
        if (!f || !grp || grp.style.display === 'none') return;
        e.preventDefault();
        f.focus();
        f.select();
    } else {
        // R: trigger Run Report. Don't fire while drawer is open (could be over an input).
        var drawer = document.getElementById('teamReportDrawer');
        if (drawer && drawer.style.display !== 'none') return;
        var btn = document.getElementById('volumeRunBtn');
        if (!btn || btn.disabled) return;
        e.preventDefault();
        volumeRunReport();
    }
});

function volumeTypeColor(type) {
    if (type === 'ECO') return '#4a6fa5';
    if (type === 'MCO') return '#1F8A4C';
    if (type === 'AML') return '#7C3AED';
    return '#6B7280';
}

function volumeFilterRows() {
    var q = document.getElementById('volumeRowsSearch').value.toLowerCase().trim();
    var inGroupMode = volumeRowMode() === 'group';
    var heads = document.querySelectorAll('#volumeRowsContainer tbody tr.vol-grp-head');
    var flat = document.querySelectorAll('#volumeRowsContainer tbody tr.vol-row');

    if (!inGroupMode) {
        // Flat mode: simple text match per row.
        flat.forEach(function (tr) {
            tr.style.display = (!q || tr.textContent.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
        });
        return;
    }

    // Group mode: parent visible if parent's row text matches OR any child matches.
    // When a filter is active, expand ALL matching parents so users see their hits.
    // When filter clears, collapse everything back to the default (children hidden).
    heads.forEach(function (head) {
        var grpId = head.getAttribute('data-grp');
        var children = document.querySelectorAll('tr.vol-grp-child[data-parent="' + grpId + '"]');
        var caret = document.getElementById(grpId + '-caret');

        if (!q) {
            head.style.display = '';
            children.forEach(function (c) { c.style.display = 'none'; });
            if (caret) caret.style.transform = '';
            return;
        }

        var headMatch = head.textContent.toLowerCase().indexOf(q) >= 0;
        var matchedChildren = [];
        children.forEach(function (c) {
            if (c.textContent.toLowerCase().indexOf(q) >= 0) matchedChildren.push(c);
        });

        if (headMatch || matchedChildren.length) {
            head.style.display = '';
            // Reveal only the children that match (or all if the parent itself is the hit).
            children.forEach(function (c) {
                if (headMatch) c.style.display = '';
                else c.style.display = matchedChildren.indexOf(c) >= 0 ? '' : 'none';
            });
            if (caret && (headMatch || matchedChildren.length)) caret.style.transform = 'rotate(90deg)';
        } else {
            head.style.display = 'none';
            children.forEach(function (c) { c.style.display = 'none'; });
        }
    });
}

function volumeExportXlsx() {
    var manager = document.getElementById('volumeManagerSelect').value;
    var fromDate = document.getElementById('volumeFromDate').value;
    var toDate = document.getElementById('volumeToDate').value;
    var transitive = document.getElementById('volumeTransitive').checked;
    var includeManager = document.getElementById('volumeIncludeManager').checked;
    if (!manager || !fromDate || !toDate) {
        appAlert('Pick a manager and dates first, then run the report.');
        return;
    }
    if (!(volumeState.rows && volumeState.rows.length)) {
        appAlert('No rows to export. Run a report first.');
        return;
    }
    var status = document.getElementById('volumeStatus');
    if (status) status.textContent = 'Building Excel…';
    fetch('/api/ecn-report/volume/export', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            managerUsername: manager,
            fromDate: fromDate,
            toDate: toDate,
            transitive: transitive,
            includeManager: includeManager
        })
    })
        .then(function (r) {
            if (!r.ok) return r.text().then(function (t) { throw new Error(t || ('HTTP ' + r.status)); });
            return r.blob();
        })
        .then(function (blob) {
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = 'volume-report_' + manager + '_' + fromDate + '_to_' + toDate + '.xlsx';
            a.click();
            setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
            if (status) status.textContent = 'Excel downloaded.';
        })
        .catch(function (err) {
            if (status) status.innerHTML = '<span style="color:#B8342B;">Export failed: ' + volumeEsc(err.message || err) + '</span>';
        });
}

function volumeEsc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// === Build next-month Team Report ===
// Calls /api/team-report/generate with the criteria from the most recent
// volume run + the user's uploaded prior-month XLSX. The server runs
// build_team_report.py and returns the Team Report XLSX + a discrepancies
// DOCX as base64 in one JSON envelope.

var TEAM_REPORT_MONTH_RE = /^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)_\d{4}$/;

// HANDOFF #4: drawer replaces the old inline panel.
// teamReportShowIfHasRows just enables the header trigger button now;
// the user opens the drawer themselves with "Generate Team Report \u2192".
function teamReportShowIfHasRows() {
    // Header button enabling is handled by _volumeEnableHeaderButtons in
    // volumeRunReport's success branch. This stub stays so existing call
    // sites don't break.
}

function _teamReportMonthFromDate(yyyymmdd) {
    if (!yyyymmdd) return '';
    var m = /^(\d{4})-(\d{2})-\d{2}$/.exec(yyyymmdd);
    if (!m) return '';
    var months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    var idx = parseInt(m[2], 10) - 1;
    if (idx < 0 || idx > 11) return '';
    return months[idx] + '_' + m[1];
}

// Parse a YYYY-MM-DD or "Mar_2026" string into [year, month-1].
function _trMonthParse(s) {
    if (!s) return null;
    var m1 = /^([A-Za-z]{3})_(\d{4})$/.exec(s);
    if (m1) {
        var months = {Jan:0,Feb:1,Mar:2,Apr:3,May:4,Jun:5,Jul:6,Aug:7,Sep:8,Oct:9,Nov:10,Dec:11};
        var idx = months[m1[1]];
        if (idx == null) return null;
        return { y: parseInt(m1[2],10), m: idx };
    }
    return null;
}
function _trAddMonth(p, delta) {
    var d = new Date(p.y, p.m + delta, 1);
    return { y: d.getFullYear(), m: d.getMonth() };
}
function _trMonthLabel(p) {
    var months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    return months[p.m] + '_' + p.y;
}

// ---------- HANDOFF #4: Drawer open/close + body lock ----------
function teamReportOpenDrawer() {
    if (!(volumeState.rows && volumeState.rows.length)) return;
    var scrim = document.getElementById('teamReportScrim');
    var drawer = document.getElementById('teamReportDrawer');
    if (!drawer) return;
    scrim.style.display = '';
    drawer.style.display = '';
    // Pre-fill month from current run's fromDate. Don't clobber if user typed.
    var monthInput = document.getElementById('teamReportMonth');
    if (monthInput && !monthInput.value.trim()) {
        var derived = _teamReportMonthFromDate(document.getElementById('volumeFromDate').value);
        if (derived) monthInput.value = derived;
    }
    // Context recap pill
    var ctx = document.getElementById('trDrawerContext');
    if (ctx) {
        var s = volumeState.summary || {};
        var rows = s.totalRows || (volumeState.rows ? volumeState.rows.length : 0);
        var who = volumeState.selectedManagerLabel || document.getElementById('volumeManagerSelect').value;
        var monthLabel = (monthInput.value || '(target)').replace('_', ' ');
        ctx.textContent = 'Based on ' + monthLabel + ' \u00b7 ' + (who || '?') + ' \u00b7 ' + rows.toLocaleString() + ' rows \u00b7 ECO+MCO+AML';
    }
    // First-time visitors see "What this does" expanded; collapsed thereafter.
    var det = document.getElementById('trHowDetails');
    if (det) det.open = (localStorage.getItem('plm.teamReportHowSeen') !== '1');
    teamReportRefreshLastUsed();
    teamReportRefreshRecent();
    teamReportValidate();
    teamReportSetupDropZone();
    document.addEventListener('keydown', _trDrawerEsc);
}
function teamReportCloseDrawer() {
    var scrim = document.getElementById('teamReportScrim');
    var drawer = document.getElementById('teamReportDrawer');
    if (drawer) drawer.style.display = 'none';
    if (scrim) scrim.style.display = 'none';
    // Remember the user opened+saw the accordion at least once.
    var det = document.getElementById('trHowDetails');
    if (det && !det.open) localStorage.setItem('plm.teamReportHowSeen', '1');
    document.removeEventListener('keydown', _trDrawerEsc);
}
function _trDrawerEsc(e) { if (e.key === 'Escape') teamReportCloseDrawer(); }

// ---------- Drop zone ----------
var _trDropZoneBound = false;
function teamReportSetupDropZone() {
    if (_trDropZoneBound) return;
    var zone = document.getElementById('trDropZone');
    if (!zone) return;
    _trDropZoneBound = true;
    zone.addEventListener('dragover', function (e) {
        e.preventDefault();
        zone.style.borderColor = '#4a6fa5';
        zone.style.background = '#f0f4ff';
    });
    zone.addEventListener('dragleave', function () {
        zone.style.borderColor = '#d0d5dd';
        zone.style.background = '';
    });
    zone.addEventListener('drop', function (e) {
        e.preventDefault();
        zone.style.borderColor = '#d0d5dd';
        zone.style.background = '';
        if (e.dataTransfer.files && e.dataTransfer.files.length) {
            teamReportFilePicked(e.dataTransfer.files[0]);
        }
    });
    zone.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            document.getElementById('teamReportPrev').click();
        }
    });
}
function teamReportFilePicked(file) {
    if (!file) { teamReportClearFile(); return; }
    var picker = document.getElementById('teamReportPrev');
    if (picker.files && picker.files[0] !== file) {
        // Sync the hidden input when a drop event delivered the file.
        try {
            var dt = new DataTransfer();
            dt.items.add(file);
            picker.files = dt.files;
        } catch (e) { /* old browsers — ignore */ }
    }
    var empty = document.getElementById('trDropEmpty');
    var filled = document.getElementById('trDropFilled');
    document.getElementById('trDropFilename').textContent = file.name;
    document.getElementById('trDropSize').textContent = '\u00b7 ' + _trFormatBytes(file.size);
    empty.style.display = 'none';
    filled.style.display = '';
    // Persist the filename for "last used" recall (no file bytes stored).
    try {
        localStorage.setItem('plm.lastTeamReport', JSON.stringify({ filename: file.name, sizeBytes: file.size, picked: Date.now() }));
    } catch (e) {}
    teamReportRefreshLastUsed();
    teamReportValidate();
}
function teamReportClearFile() {
    var picker = document.getElementById('teamReportPrev');
    picker.value = '';
    document.getElementById('trDropEmpty').style.display = '';
    document.getElementById('trDropFilled').style.display = 'none';
    teamReportValidate();
}
function _trFormatBytes(n) {
    if (!n) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n/1024).toFixed(1) + ' KB';
    return (n/(1024*1024)).toFixed(1) + ' MB';
}

function teamReportRefreshLastUsed() {
    var box = document.getElementById('trLastUsed');
    var name = document.getElementById('trLastUsedFilename');
    if (!box || !name) return;
    try {
        var raw = localStorage.getItem('plm.lastTeamReport');
        if (!raw) { box.style.display = 'none'; return; }
        var parsed = JSON.parse(raw);
        if (!parsed || !parsed.filename) { box.style.display = 'none'; return; }
        name.textContent = parsed.filename;
        box.style.display = 'flex';
    } catch (e) { box.style.display = 'none'; }
}

// ---------- Smart warning when prior month + 1 != target month ----------
function _trUpdateMonthWarning() {
    var warn = document.getElementById('trMonthWarning');
    if (!warn) return;
    warn.style.display = 'none';
    var picker = document.getElementById('teamReportPrev');
    var monthInput = document.getElementById('teamReportMonth');
    var file = picker && picker.files && picker.files[0];
    if (!file || !monthInput || !monthInput.value) return;
    // Try to parse "Team_Report_YYYY_MMM.xlsx" or "Team Report_YYYY_MMM.xlsx".
    var fm = /(\d{4})[_ ]([A-Za-z]{3})/.exec(file.name);
    if (!fm) return;
    var prior = _trMonthParse(fm[2] + '_' + fm[1]);
    var target = _trMonthParse(monthInput.value.trim());
    if (!prior || !target) return;
    var expected = _trAddMonth(prior, 1);
    if (expected.y === target.y && expected.m === target.m) return;
    var priorLabel  = _trMonthLabel(prior).replace('_', ' ');
    var targetLabel = _trMonthLabel(target).replace('_', ' ');
    warn.textContent = 'Heads up \u2014 prior file is ' + priorLabel + ' but target is ' + targetLabel + '. Continue if intended.';
    warn.style.display = '';
}

// ---------- Recent reports list ----------
function teamReportRefreshRecent() {
    var section = document.getElementById('trRecentSection');
    var list = document.getElementById('trRecentList');
    if (!section || !list) return;
    fetch('/api/team-report/recent?limit=3')
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
            if (!data || !data.items || !data.items.length) { section.style.display = 'none'; return; }
            section.style.display = '';
            list.innerHTML = data.items.map(function (it) {
                var when = (it.generatedAt || '').replace('T', ' ').substring(0, 16);
                var size = _trFormatBytes(it.sizeBytes || 0);
                var dl = it.downloadUrl ? '<a href="' + volumeEsc(it.downloadUrl) + '" style="color:#4a6fa5; text-decoration:none;">Download</a>' : '<span style="color:#9ca3af;">expired</span>';
                return '<div style="display:flex; align-items:center; gap:10px; padding:6px 0; border-bottom:1px solid #f0efea;">' +
                    '<code style="flex:1; color:#0F1720; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="' + volumeEsc(it.filename) + '">' + volumeEsc(it.filename) + '</code>' +
                    '<span style="color:#6B7280; font-size:11px;">' + size + '</span>' +
                    '<span style="color:#9ca3af; font-size:11px;">' + volumeEsc(when) + '</span>' +
                    dl +
                    '</div>';
            }).join('');
        })
        .catch(function () { section.style.display = 'none'; });
}

function teamReportValidate() {
    var btn = document.getElementById('teamReportRunBtn');
    var err = document.getElementById('teamReportError');
    var status = document.getElementById('teamReportStatus');
    var monthEl = document.getElementById('teamReportMonth');
    var month = monthEl ? monthEl.value.trim() : '';
    var fileInput = document.getElementById('teamReportPrev');
    var file = fileInput && fileInput.files && fileInput.files[0];

    // Hide stale red error from prior runs whenever the user edits inputs.
    if (err) err.style.display = 'none';

    var ready = true;
    var statusText = 'Ready to generate';
    var statusColor = '#155724';

    if (!(volumeState.rows && volumeState.rows.length)) {
        ready = false;
        statusText = 'Run the volume report first';
        statusColor = '#6B7280';
    } else if (!file) {
        ready = false;
        statusText = 'Upload a file to continue';
        statusColor = '#6B7280';
    } else if (!file.name.toLowerCase().endsWith('.xlsx')) {
        ready = false;
        statusText = 'Prior Team Report must be a .xlsx file';
        statusColor = '#B8342B';
    } else if (!month) {
        ready = false;
        statusText = 'Pick a target month';
        statusColor = '#6B7280';
    } else if (!TEAM_REPORT_MONTH_RE.test(month)) {
        ready = false;
        statusText = 'Month must look like Mar_2026 (MMM_YYYY)';
        statusColor = '#B8342B';
    }

    if (status) {
        status.textContent = (ready ? '\u2713 ' : '') + statusText;
        status.style.color = statusColor;
    }
    if (btn) {
        btn.disabled = !ready;
        btn.style.opacity = ready ? '1' : '0.5';
        btn.style.cursor = ready ? 'pointer' : 'not-allowed';
    }

    _trUpdateMonthWarning();
}

// Staged progress so the user sees something is happening through the
// 6-30s wall-time window (cache hit ~6s; cache miss ~30s — see comments
// on /api/team-report/generate). Approximate timings, not server-driven.
var _trStageTimer = null;
// targetEl: optional element to write into — defaults to the drawer's
// #teamReportStatus; the one-click header flow passes its own status line.
function _trStartStages(targetEl) {
    _trStopStages();
    var stages = [
        { atMs: 0,      text: 'Pulling the rows from your last Run Report (or re-querying Agile if the cache rolled)\u2026' },
        { atMs: 4000,   text: 'Building the volume-report XLSX from your change rows\u2026' },
        { atMs: 15000,  text: 'Rolling last month\u2019s Team Report forward (raw-data sheets + formulas)\u2026' },
        { atMs: 20000,  text: 'Aggregating ECO / MCO / AML monthly volumes for the target month\u2026' },
        { atMs: 25000,  text: 'Restoring pivot tables and chart wiring (the load-bearing step)\u2026' },
        { atMs: 32000,  text: 'Writing the discrepancy report vs prior month\u2019s hand entries\u2026' },
        { atMs: 38000,  text: 'AI is reading your change descriptions per Program Team \u2014 surfacing themes, anomalies, and risk callouts\u2026' },
        { atMs: 60000,  text: 'AI still chewing \u2014 takes ~30\u201360s for ~6 teams in parallel against Claude Sonnet via Vortex\u2026' },
        { atMs: 90000,  text: 'Patching the column-R \u201c#N/A\u201d \u2192 \u201cMix\u201d fix and writing the new \u201cAI Analysis\u201d tab\u2026' },
        { atMs: 110000, text: 'Almost done \u2014 packaging the XLSX + DOCX for download\u2026' },
        { atMs: 150000, text: 'Still working. Typical end-to-end is ~2 min on a cache-cold run; cache-warm is ~90s.' }
    ];
    // Braille spinner: smoother in monospace than text dots
    var spinFrames = ['\u280B','\u2819','\u2839','\u2838','\u283C','\u2834','\u2826','\u2827','\u2807','\u280F'];
    var spinIdx = 0;
    var t0 = Date.now();
    var status = targetEl || document.getElementById('teamReportStatus');
    function tick() {
        var elapsed = Date.now() - t0;
        var current = stages[0].text;
        for (var i = stages.length - 1; i >= 0; i--) {
            if (elapsed >= stages[i].atMs) { current = stages[i].text; break; }
        }
        spinIdx = (spinIdx + 1) % spinFrames.length;
        var secs = (elapsed / 1000).toFixed(1);
        if (status) {
            status.innerHTML =
                '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; color:#4a6fa5; font-weight:600;">' + spinFrames[spinIdx] + '</span> ' +
                volumeEsc(current) +
                ' <span style="color:#9ca3af; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px;">[' + secs + 's]</span>';
        }
    }
    tick();
    _trStageTimer = setInterval(tick, 120);
}
function _trStopStages() {
    if (_trStageTimer) { clearInterval(_trStageTimer); _trStageTimer = null; }
}

function teamReportRun() {
    var btn = document.getElementById('teamReportRunBtn');
    var status = document.getElementById('teamReportStatus');
    var err = document.getElementById('teamReportError');
    var ok = document.getElementById('teamReportOk');
    var month = document.getElementById('teamReportMonth').value.trim();
    var file = document.getElementById('teamReportPrev').files[0];
    var manager = document.getElementById('volumeManagerSelect').value;
    var fromDate = document.getElementById('volumeFromDate').value;
    var toDate = document.getElementById('volumeToDate').value;
    var transitive = document.getElementById('volumeTransitive').checked;
    var includeManager = document.getElementById('volumeIncludeManager').checked;

    err.style.display = 'none';
    ok.style.display = 'none';
    btn.disabled = true;
    btn.style.opacity = '0.5';
    btn.style.cursor = 'not-allowed';
    var origLabel = btn.innerHTML;
    btn.innerHTML = '\u23F3 Generating\u2026';
    _trStartStages();

    var fd = new FormData();
    fd.append('month', month);
    fd.append('previousMonth', file);
    fd.append('managerUsername', manager);
    fd.append('fromDate', fromDate);
    fd.append('toDate', toDate);
    fd.append('transitive', transitive ? 'true' : 'false');
    fd.append('includeManager', includeManager ? 'true' : 'false');

    fetch('/api/team-report/generate', { method: 'POST', body: fd })
        .then(function (r) {
            return r.json().then(function (j) { return { http: r.status, body: j }; });
        })
        .then(function (resp) {
            _trStopStages();
            btn.innerHTML = origLabel;
            if (resp.http !== 200 || !resp.body || resp.body.success === false) {
                var msg = (resp.body && (resp.body.error || resp.body.message)) || ('HTTP ' + resp.http);
                err.textContent = 'Generation failed: ' + msg;
                if (resp.body && resp.body.stderr) {
                    err.textContent += ' \u00b7 (server log tail above; see browser console for full details)';
                    console.warn('[team-report] script stderr:\n' + resp.body.stderr);
                }
                err.style.display = '';
                status.textContent = '';
                teamReportValidate();
                return;
            }
            // Success — trigger downloads for both attachments
            _teamReportDownload(resp.body.outputFilename, resp.body.outputBase64,
                'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                resp.body.outputUrl);
            if (resp.body.discrepancyBase64 || resp.body.discrepancyUrl) {
                _teamReportDownload(resp.body.discrepancyFilename, resp.body.discrepancyBase64,
                    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                    resp.body.discrepancyUrl);
            }
            var disc = resp.body.discrepancyCount || 0;
            var dur = resp.body.durationMs ? Math.round(resp.body.durationMs / 100) / 10 : null;
            var aiDur = resp.body.aiDurationMs ? Math.round(resp.body.aiDurationMs / 100) / 10 : null;
            var aiNote = resp.body.aiNote;
            var msg = 'Team Report generated' + (dur ? ' (script ' + dur + 's' : '');
            if (aiDur) msg += ', AI analysis ' + aiDur + 's';
            msg += dur ? ').' : '.';
            msg += ' ' + (disc ? '<strong>' + disc + '</strong> discrepanc' + (disc === 1 ? 'y' : 'ies') + ' vs prior month' : 'No discrepancies vs prior month');
            if (aiNote) msg += ' \u2014 <em>' + volumeEsc(aiNote) + '</em>';
            else msg += ' \u2014 the new <strong>AI Analysis</strong> tab has per-team themes + risk callouts for ' + volumeEsc(month) + '.';
            ok.innerHTML = msg;
            ok.style.display = '';
            status.textContent = '';
            teamReportValidate();
            teamReportRefreshRecent();
            // Keep the in-app Team Report tab in sync with the new report.
            if (typeof triOnReportGenerated === 'function') triOnReportGenerated();
        })
        .catch(function (e) {
            _trStopStages();
            btn.innerHTML = origLabel;
            err.textContent = 'Network or server error: ' + (e && e.message ? e.message : e);
            err.style.display = '';
            status.textContent = '';
            teamReportValidate();
        });
}

function _teamReportDownload(filename, b64, mime, url) {
    // Preferred path (2026-07-08): the server stashes the file and hands back
    // a download URL — no ~10 MB base64 blob in the JSON envelope. base64 is
    // kept as the fallback for the rare stash-write-failure case.
    if (url) {
        var ua = document.createElement('a');
        ua.href = url;
        ua.download = filename || '';
        document.body.appendChild(ua);
        ua.click();
        setTimeout(function () { ua.remove(); }, 1500);
        return;
    }
    if (!b64) return;
    // base64 -> blob
    var bin = atob(b64);
    var len = bin.length;
    var bytes = new Uint8Array(len);
    for (var i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
    var blob = new Blob([bytes], { type: mime });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url; a.download = filename || 'download';
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(url); a.remove(); }, 1500);
}

// ---------- One-click "Generate Last Month's Report" ----------
// Zero-input path: POST /api/team-report/generate-last-month and let the
// server default everything (month = last complete calendar month, prior
// workbook = newest stashed report, criteria = the manager's own team for
// that month). The drawer flow above stays untouched as the manual override.

var _TR_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Jun_2026" → { y: 2026, mi: 5 } (0-based month index), or null. */
function _trMonthParse(m) {
    if (!m) return null;
    var p = String(m).split('_');
    if (p.length !== 2) return null;
    var mi = _TR_MONTHS.indexOf(p[0]);
    if (mi < 0 || !/^\d{4}$/.test(p[1])) return null;
    return { y: parseInt(p[1], 10), mi: mi };
}

/** "Jun_2026" → "Jun 2026" (display form); falls back to the input. */
function _trMonthLabel(m) {
    return _trMonthParse(m) ? String(m).replace('_', ' ') : String(m || '');
}

/** Date → MMM_YYYY of the last complete calendar month (Jan 15 → Dec of prior year). */
function _trLastCompleteMonth(now) {
    var y = now.getFullYear();
    var mi = now.getMonth() - 1;
    if (mi < 0) { mi = 11; y -= 1; }
    return _TR_MONTHS[mi] + '_' + y;
}

function _trOneClickSetBusy(btn, busy) {
    if (!btn) return;
    if (busy) {
        btn.dataset.origLabel = btn.innerHTML;
        btn.innerHTML = '⏳ Generating…';
    } else if (btn.dataset.origLabel) {
        btn.innerHTML = btn.dataset.origLabel;
    }
    btn.disabled = busy;
    btn.style.opacity = busy ? '0.5' : '1';
    btn.style.cursor = busy ? 'not-allowed' : 'pointer';
}

// managerUsername: optional — omit for "my own team" (the header button);
// the drawer shortcut passes the selected manager. force retries past the
// gap-month warning after the user confirms. ui: optional element overrides
// ({btn, statusEl, okEl, errEl}) so other surfaces (the Team Report tab's
// stale-month banner) can show progress in their own chrome instead of the
// Volume Reports header.
function teamReportOneClick(managerUsername, force, ui) {
    ui = ui || {};
    var btn = ui.btn || document.getElementById('trOneClickBtn');
    if (!btn || btn.disabled) return;
    var status = ui.statusEl || document.getElementById('trOneClickStatus');
    var errEl = ui.errEl || document.getElementById('trOneClickError');
    var okEl = ui.okEl || document.getElementById('trOneClickOk');
    if (errEl) errEl.style.display = 'none';
    if (okEl) okEl.style.display = 'none';
    _trOneClickSetBusy(btn, true);
    if (status) status.style.display = '';
    _trStartStages(status);

    function done() {
        _trStopStages();
        _trOneClickSetBusy(btn, false);
        if (status) { status.style.display = 'none'; status.textContent = ''; }
    }

    var params = [];
    if (managerUsername) params.push('managerUsername=' + encodeURIComponent(managerUsername));
    if (force) params.push('force=true');
    var url = '/api/team-report/generate-last-month' + (params.length ? '?' + params.join('&') : '');

    fetch(url, { method: 'POST' })
        .then(function (r) {
            return r.json().then(function (j) { return { http: r.status, body: j }; });
        })
        .then(function (resp) {
            done();
            var body = resp.body || {};
            if (resp.http === 200 && body.success !== false) {
                _teamReportDownload(body.outputFilename, body.outputBase64,
                    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                    body.outputUrl);
                if (body.discrepancyBase64 || body.discrepancyUrl) {
                    _teamReportDownload(body.discrepancyFilename, body.discrepancyBase64,
                        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                        body.discrepancyUrl);
                }
                var d = body.defaults || {};
                var disc = body.discrepancyCount || 0;
                var msg = '<strong>' + volumeEsc(_trMonthLabel(d.month)) + ' Team Report generated</strong>'
                    + ' from the saved ' + volumeEsc(_trMonthLabel(d.previousMonth)) + ' report'
                    + (d.managerUsername ? ' · team of ' + volumeEsc(d.managerUsername) : '') + '.';
                if (body.durationMs) {
                    msg += ' Script ' + Math.round(body.durationMs / 100) / 10 + 's'
                        + (body.aiDurationMs ? ', AI ' + Math.round(body.aiDurationMs / 100) / 10 + 's' : '') + '.';
                }
                msg += ' ' + (disc
                    ? '<strong>' + disc + '</strong> discrepanc' + (disc === 1 ? 'y' : 'ies') + ' vs prior month.'
                    : 'No discrepancies vs prior month.');
                if (body.aiNote) msg += ' — <em>' + volumeEsc(body.aiNote) + '</em>';
                if (okEl) { okEl.innerHTML = msg; okEl.style.display = ''; }
                teamReportRefreshRecent();
                // Keep the in-app Team Report tab in sync — it re-probes the
                // months on disk and jumps to the freshly generated one.
                if (typeof triOnReportGenerated === 'function') triOnReportGenerated();
                return;
            }
            if (body.code === 'STALE_PRIOR_REPORT') {
                appConfirm(body.error, { okText: 'Continue anyway' }).then(function (yes) {
                    if (yes) teamReportOneClick(managerUsername, true, ui);
                });
                return;
            }
            if (body.code === 'NO_PRIOR_REPORT' || body.code === 'NOT_A_MANAGER') {
                appAlert(body.error);
                return;
            }
            var msg2 = body.error || body.message || ('HTTP ' + resp.http);
            if (errEl) {
                errEl.textContent = 'Generation failed: ' + msg2;
                if (body.stderr) {
                    errEl.textContent += ' · see browser console for the script log';
                    console.warn('[team-report] script stderr:\n' + body.stderr);
                }
                errEl.style.display = '';
            }
        })
        .catch(function (e) {
            done();
            if (errEl) {
                errEl.textContent = 'Network or server error: ' + (e && e.message ? e.message : e);
                errEl.style.display = '';
            }
        });
}

// Drawer shortcut: one-click preflight seeded with whichever manager is
// selected in the Volume Reports dropdown (empty falls back to "me").
function teamReportOneClickFromDrawer() {
    var manager = document.getElementById('volumeManagerSelect').value;
    teamReportCloseDrawer();
    teamReportOneClickConfirm(manager || null);
}

// ---------- One-click preflight dialog ----------
// Shows WHO the report will cover (the manager's direct reports from AD) and
// WHICH saved workbook rolls forward, with a manager picker, before starting
// the 1-4 minute generation. Prevents the "I one-clicked as myself and got an
// all-zeros report" trap for admins whose AD reports aren't the PCM team.

function _trLoadManagerList() {
    if (volumeState.managers && volumeState.managers.length) {
        return Promise.resolve(volumeState.managers);
    }
    return fetch('/api/ecn-report/volume/managers', { credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) {
            volumeState.managers = (d && d.managers) || [];
            return volumeState.managers;
        })
        .catch(function () { return []; });
}

function _trFetchPreflight(managerUsername) {
    var url = '/api/team-report/generate-last-month/preflight'
        + (managerUsername ? '?managerUsername=' + encodeURIComponent(managerUsername) : '');
    return fetch(url, { credentials: 'same-origin' }).then(function (r) { return r.json(); });
}

// Entry point used by the header button, drawer shortcut, stale-month banner
// and the Team Report tab's cold-start path. ui = optional element overrides
// forwarded to teamReportOneClick.
function teamReportOneClickConfirm(managerUsername, ui) {
    Promise.all([_trFetchPreflight(managerUsername), _trLoadManagerList()])
        .then(function (res) { _trShowPreflightModal(res[0] || {}, res[1] || [], ui); })
        .catch(function (e) {
            appAlert('Could not check the report defaults: ' + (e && e.message ? e.message : e));
        });
}

function _trPreflightDetailsHtml(pf) {
    var who = volumeEsc(pf.managerUsername || '?');
    var monthLabel = volumeEsc(_trMonthLabel(pf.month));
    var html = '';
    if (!pf.isManager) {
        html += '<div style="padding:9px 12px; background:#fff8e1; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; font-size:12.5px; color:#7a5210;">'
            + '<strong>' + who + '</strong> has no direct reports in AD — a report for them would be empty. '
            + 'Pick the manager whose team the report should cover.</div>';
    } else {
        var names = pf.directReports.map(function (u) { return u.displayName || u.username; });
        var shown = names.slice(0, 10).join(', ') + (names.length > 10 ? ' +' + (names.length - 10) + ' more' : '');
        html += '<div style="font-size:13px;">Covers the <strong>' + monthLabel + '</strong> activity of '
            + '<strong>' + who + '</strong>’s ' + names.length + ' direct report' + (names.length === 1 ? '' : 's') + ':<br>'
            + '<span style="color:#6B7280;">' + volumeEsc(shown) + '</span></div>';
    }
    if (pf.priorSource) {
        html += '<div style="margin-top:8px; font-size:12px; color:#6B7280;">Rolls forward from the saved '
            + '<strong>' + volumeEsc(_trMonthLabel(pf.priorMonth)) + '</strong> report ('
            + volumeEsc(pf.priorSource) + ').'
            + (pf.staleWarning ? ' <span style="color:#C7801B;">Note: that skips at least one month — those months would show empty.</span>' : '')
            + '</div>';
    } else {
        html += '<div style="margin-top:8px; padding:9px 12px; background:#fdeaea; border-left:4px solid #B8342B; border-radius:0 6px 6px 0; font-size:12px; color:#B8342B;">'
            + 'No saved report to roll forward — generate once via the Generate Team Report drawer (with an uploaded prior file) first.</div>';
    }
    return html;
}

function _trShowPreflightModal(pf, managers, ui) {
    var old = document.getElementById('trPreflightOverlay');
    if (old) old.remove();

    var overlay = document.createElement('div');
    overlay.id = 'trPreflightOverlay';
    overlay.style.cssText = 'position:fixed;inset:0;background:rgba(15,23,32,0.55);z-index:20000;'
        + 'display:flex;align-items:center;justify-content:center;padding:20px;'
        + "font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;";

    var opts = '';
    var haveCurrent = false;
    managers.forEach(function (m) {
        if (m.username === pf.managerUsername) haveCurrent = true;
        opts += '<option value="' + volumeEsc(m.username) + '"'
            + (m.username === pf.managerUsername ? ' selected' : '') + '>'
            + volumeEsc((m.displayName || m.username) + ' — ' + m.username) + '</option>';
    });
    if (!haveCurrent && pf.managerUsername) {
        opts = '<option value="' + volumeEsc(pf.managerUsername) + '" selected>'
            + volumeEsc(pf.managerUsername + (pf.managerIsSelf ? ' (me)' : '')) + '</option>' + opts;
    }

    var monthLabel = volumeEsc(_trMonthLabel(pf.month));
    overlay.innerHTML =
        '<div style="background:#fff;max-width:560px;width:100%;border-radius:8px;border:1px solid #E8E6DF;box-shadow:0 10px 40px rgba(0,0,0,0.18);overflow:hidden;">'
        + '<div style="display:flex;align-items:center;gap:10px;padding:14px 18px;border-bottom:1px solid #E8E6DF;">'
        + '  <div style="background:#2c3e50;color:#fff;font-size:11px;font-weight:700;width:24px;height:24px;text-align:center;line-height:24px;border-radius:5px;flex:0 0 24px;">PT</div>'
        + '  <div style="font-size:13px;font-weight:600;color:#0F1720;">One-click Team Report — ' + monthLabel + '</div>'
        + '</div>'
        + '<div style="padding:18px 20px;font-size:14px;line-height:1.5;color:#0F1720;">'
        + '  <label style="display:block;font-size:11px;color:#6B7280;font-weight:600;letter-spacing:0.06em;text-transform:uppercase;margin-bottom:5px;">Report for the team of</label>'
        + '  <select id="trPfManager" style="width:100%;padding:7px 10px;border:1px solid #ccc;border-radius:6px;font-size:13px;">' + opts + '</select>'
        + '  <div id="trPfDetails" style="margin-top:12px;">' + _trPreflightDetailsHtml(pf) + '</div>'
        + '  <div style="margin-top:12px;font-size:11.5px;color:#6B7280;">Need a custom month or a hand-picked prior file? Use Volume Reports → Generate Team Report instead.</div>'
        + '</div>'
        + '<div style="display:flex;justify-content:flex-end;gap:8px;padding:12px 18px;border-top:1px solid #E8E6DF;background:#FAFAF7;">'
        + '  <button id="trPfCancel" style="background:#fff;color:#0F1720;border:1px solid #E8E6DF;padding:8px 18px;border-radius:6px;font-size:13px;font-weight:600;cursor:pointer;">Cancel</button>'
        + '  <button id="trPfGo" style="background:#0F1720;color:#fff;border:none;padding:8px 18px;border-radius:6px;font-size:13px;font-weight:600;cursor:pointer;">⚡ Generate ' + monthLabel + '</button>'
        + '</div>'
        + '</div>';
    document.body.appendChild(overlay);

    var current = pf;
    var goBtn = document.getElementById('trPfGo');
    function syncGo() {
        var ok = current && current.isManager && current.priorSource;
        goBtn.disabled = !ok;
        goBtn.style.opacity = ok ? '1' : '0.5';
        goBtn.style.cursor = ok ? 'pointer' : 'not-allowed';
    }
    syncGo();

    function close() {
        overlay.remove();
        document.removeEventListener('keydown', onKey);
    }
    function onKey(e) { if (e.key === 'Escape') close(); }
    document.addEventListener('keydown', onKey);
    overlay.addEventListener('mousedown', function (e) { if (e.target === overlay) close(); });

    document.getElementById('trPfCancel').onclick = close;
    document.getElementById('trPfManager').onchange = function () {
        var m = this.value;
        var details = document.getElementById('trPfDetails');
        details.innerHTML = '<span style="font-size:12px;color:#6B7280;">Checking ' + volumeEsc(m) + '’s team…</span>';
        _trFetchPreflight(m).then(function (p) {
            current = p || {};
            details.innerHTML = _trPreflightDetailsHtml(current);
            syncGo();
        }).catch(function (e) {
            details.innerHTML = '<span style="font-size:12px;color:#B8342B;">Lookup failed: ' + volumeEsc(e && e.message ? e.message : String(e)) + '</span>';
            current = null;
            syncGo();
        });
    };
    goBtn.onclick = function () {
        if (goBtn.disabled) return;
        var manager = document.getElementById('trPfManager').value || null;
        close();
        teamReportOneClick(manager, false, ui);
    };
}

// Stamp the concrete month into the header button so the user knows what
// they'll get before clicking ("Generate Jun 2026 Report").
function _trOneClickInitLabel() {
    var btn = document.getElementById('trOneClickBtn');
    if (!btn) return;
    var label = _trMonthLabel(_trLastCompleteMonth(new Date()));
    btn.innerHTML = '⚡ Generate ' + volumeEsc(label) + ' Report';
    btn.title = 'One click, no inputs: builds the ' + label + ' Team Report for your own team '
        + 'from your last saved report. Use the drawer for another manager or a custom month.';
}
if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', _trOneClickInitLabel);
    } else {
        _trOneClickInitLabel();
    }
}

// Node test shim — pure helpers only (mirrors teamreport-inapp.js).
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        _trMonthParse: _trMonthParse,
        _trMonthLabel: _trMonthLabel,
        _trLastCompleteMonth: _trLastCompleteMonth
    };
}
