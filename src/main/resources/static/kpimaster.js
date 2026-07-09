// === KPI Master ===
// The single, centralized editor + reference for ECN KPI classifications:
// Request Classification + Subclass -> SLA Target (Std/Urgent), ECN Classification,
// Change Type. Sourced from Agile D029-00006. SLA target editing was relocated here
// from the Cycle Time tab (Vikas Singh 2026-07-06).
//
// Editing (target days, Save/Promote profile) is restricted to PLM IT members plus
// Vikas Singh and Jimmy Sessumes — the same `ecnState.canEdit` gate the SLA editor
// used (canEdit = PLM admin OR configured editor). Everyone else sees it read-only.
// ECN Classification + Change Type come straight from the fixed D029 sheet (read-only).

var kpiMasterState = { entries: null, loaded: false, filter: '', source: '', canEdit: false, isAdmin: false };

function kpiEsc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}

// KPI Master edit access is a DEDICATED gate (PLM IT + Vikas Singh + Jimmy Sessumes),
// narrower than the general ECN-report editor role — supplied by the server on the
// /kpi-classifications response as canEdit.
function kpiCanEdit() {
    return !!kpiMasterState.canEdit;
}

function kpiMasterInit() {
    var el = document.getElementById('kpiMasterView');
    if (!el) return;
    if (kpiMasterState.entries) { kpiMasterRender(); return; }
    el.innerHTML = '<div style="padding:30px; color:#6B7280;">Loading KPI Master…</div>';
    fetch('/api/ecn-report/kpi-classifications', { credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) {
            kpiMasterState.entries = d.entries || [];
            kpiMasterState.loaded = !!d.loaded;
            kpiMasterState.source = d.source || '';
            kpiMasterState.canEdit = !!d.canEdit;
            kpiMasterState.isAdmin = !!d.isAdmin;
            kpiMasterRender();
        })
        .catch(function (e) {
            el.innerHTML = '<div style="padding:30px; color:#B8342B;">Failed to load KPI Master: ' + kpiEsc(e && e.message) + '</div>';
        });
}

var KPI_CLASS_COLORS = {
    'General ECNs': '#1F8A4C', "PDR ECN's": '#4a6fa5', "CCB ECN's": '#7C3AED',
    "Project ECN's": '#C7801B', "Dedicated Process ECN's": '#B8342B', 'Factory Changes': '#6B7280'
};

function kpiPill(text, color) {
    if (!text) return '<span style="color:#9ca3af;">—</span>';
    var c = color || '#6B7280';
    return '<span style="display:inline-block; padding:1px 8px; border-radius:10px; font-size:11px; font-weight:600; ' +
        'color:' + c + '; background:' + c + '1a; border:1px solid ' + c + '33;">' + kpiEsc(text) + '</span>';
}

// Live SLA target for a class|subclass key: dirty edit > active-profile/current >
// the D029 baseline carried in the entry. Mirrors the old SLA editor's precedence.
function kpiTargetValue(key, priority, entry) {
    var dirty = (typeof ecnState !== 'undefined' && ecnState.dirtyTargets) ? ecnState.dirtyTargets[key] : null;
    if (dirty && dirty[priority] !== undefined) return dirty[priority];
    var cur = (typeof ecnState !== 'undefined' && ecnState.currentTargets) ? ecnState.currentTargets[key] : null;
    if (cur && cur[priority] !== undefined && cur[priority] !== null) return cur[priority];
    return priority === 'standard' ? entry.standard : entry.urgent;
}

function kpiTargetCell(key, priority, entry, canEdit) {
    var val = kpiTargetValue(key, priority, entry);
    var isNA = (val === 'NA' || val === 'TBD' || val === null || val === undefined || val === '');
    var dirty = (typeof ecnState !== 'undefined' && ecnState.dirtyTargets) ? ecnState.dirtyTargets[key] : null;
    var hi = (dirty && dirty[priority] !== undefined) ? 'background:#fff3cd;' : '';
    var td = '<td style="padding:6px 12px; text-align:center; border:1px solid #cccccc;' + hi + '">';
    if (canEdit) {
        td += '<input type="number" value="' + (isNA ? '' : val) + '" placeholder="' + (isNA ? 'NA' : '') + '" ' +
            'style="width:58px; text-align:center; border:1px solid #ddd; border-radius:3px; padding:2px;' + (isNA ? ' color:#999;' : '') + '" ' +
            'onchange="ecnEditTarget(\'' + key.replace(/'/g, "\\'") + '\', \'' + priority + '\', this.value); kpiMasterRender();">';
    } else {
        td += isNA ? '<span style="color:#9ca3af; font-family:\'IBM Plex Mono\',Consolas,monospace;">NA</span>'
                   : '<strong style="font-family:\'IBM Plex Mono\',Consolas,monospace;">' + kpiEsc(val) + '</strong>';
    }
    return td + '</td>';
}

function kpiMasterRender() {
    var el = document.getElementById('kpiMasterView');
    if (!el) return;
    var entries = kpiMasterState.entries || [];
    var canEdit = kpiCanEdit();
    var flt = (kpiMasterState.filter || '').toLowerCase();
    var shown = !flt ? entries : entries.filter(function (e) {
        return [e.requestClassification, e.requestSubclass, e.ecnClassification, e.changeType]
            .some(function (v) { return String(v || '').toLowerCase().indexOf(flt) >= 0; });
    });

    var html = '';
    // Header / intro
    html += '<div class="group" style="margin-bottom:16px;"><div style="padding:14px 18px;">' +
        '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:20px; font-weight:bold; color:#0F1720;">KPI Master</div>' +
        '<div style="font-size:13px; color:#6B7280; margin-top:4px;">The single lookup for <strong>SLA targets</strong>, <strong>ECN Classification</strong>, and <strong>Change Type</strong>, keyed by Request Classification + Subclass. All ECN dashboards derive ECN Classification and Change Type from here. Source: <strong>' + kpiEsc(kpiMasterState.source || 'D029-00006') + '</strong>.' +
        (kpiMasterState.loaded ? '' : ' <span style="color:#B8342B;">(mapping file not loaded on this server)</span>') +
        '</div>';
    if (canEdit) {
        html += '<div style="font-size:12px; color:#1F8A4C; margin-top:6px;">✎ You have edit access — adjust SLA target days below, then Save as Profile or Promote to Active SLA. ECN Classification &amp; Change Type are fixed by D029.</div>';
    } else {
        html += '<div style="font-size:12px; color:#6B7280; margin-top:6px;">Read-only. SLA target editing is limited to PLM IT and the KPI owners.</div>';
    }
    // Profile label + apply bar (ids reused by ecnUpdateDirtyBadge; relocated here
    // from the Cycle Time SLA editor).
    html += '<div style="margin-top:10px; display:flex; align-items:center; gap:14px; flex-wrap:wrap;">' +
        '<span id="ecnSlaProfileLabel" style="font-size:12px; color:#6B7280;">Profile: <strong>Baseline</strong></span>' +
        '<input type="text" id="kpiMasterFilter" value="' + kpiEsc(kpiMasterState.filter) + '" placeholder="Filter classification / subclass / change type…" ' +
        'oninput="kpiMasterState.filter=this.value; kpiMasterRender(); var f=document.getElementById(\'kpiMasterFilter\'); if(f){f.focus();f.setSelectionRange(f.value.length,f.value.length);}" ' +
        'style="flex:1; max-width:380px; padding:6px 10px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;">' +
        '<span style="font-size:12px; color:#6B7280;">' + shown.length + ' of ' + entries.length + ' subclasses</span>' +
        '</div>';
    html += '<div id="ecnApplyBar" style="display:none; margin-top:10px; padding:8px 12px; background:#fff8e1; border:1px solid #ffe0a3; border-radius:6px;"></div>';
    html += '</div></div>';

    // Grouped table
    html += '<div class="group"><div style="padding:0; overflow-x:auto;">';
    html += '<table style="width:100%; border-collapse:collapse; font-size:13px;">';
    html += '<tr style="background:#2c3e50; color:#fff;">' +
        '<th style="padding:8px 12px; text-align:left; border:1px solid #444;">Request Classification</th>' +
        '<th style="padding:8px 12px; text-align:left; border:1px solid #444;">Request Subclass</th>' +
        '<th style="padding:8px 12px; text-align:center; border:1px solid #444;">Target&nbsp;Std</th>' +
        '<th style="padding:8px 12px; text-align:center; border:1px solid #444;">Target&nbsp;Urgent</th>' +
        '<th style="padding:8px 12px; text-align:left; border:1px solid #444;">ECN Classification</th>' +
        '<th style="padding:8px 12px; text-align:left; border:1px solid #444;">Change Type</th>' +
        '<th style="padding:8px 12px; text-align:left; border:1px solid #444;">Comments</th>' +
        '</tr>';

    var lastClass = null, i = 0;
    shown.forEach(function (e) {
        var cls = e.requestClassification || '';
        if (cls !== lastClass) {
            html += '<tr style="background:#e9ecef;"><td colspan="7" style="padding:6px 12px; font-weight:700; color:#0F1720; border:1px solid #cccccc;">' + kpiEsc(cls) + '</td></tr>';
            lastClass = cls; i = 0;
        }
        var bg = (i % 2 === 0) ? '#ffffff' : '#FAFAF7';
        i++;
        var key = e.requestClassification + '|' + e.requestSubclass;
        html += '<tr style="background:' + bg + ';">' +
            '<td style="padding:6px 12px; border:1px solid #cccccc;"></td>' +
            '<td style="padding:6px 12px; border:1px solid #cccccc;">' + kpiEsc(e.requestSubclass) + '</td>' +
            kpiTargetCell(key, 'standard', e, canEdit) +
            kpiTargetCell(key, 'urgent', e, canEdit) +
            '<td style="padding:6px 12px; border:1px solid #cccccc;">' + kpiPill(e.ecnClassification, KPI_CLASS_COLORS[e.ecnClassification]) + '</td>' +
            '<td style="padding:6px 12px; border:1px solid #cccccc;">' + kpiPill(e.changeType, '#4a6fa5') + '</td>' +
            '<td style="padding:6px 12px; border:1px solid #cccccc; color:#6B7280; font-size:12px;">' + kpiEsc(e.comments || '') + '</td>' +
            '</tr>';
    });
    if (!shown.length) {
        html += '<tr><td colspan="7" style="padding:24px; text-align:center; color:#6B7280;">No subclasses match &ldquo;' + kpiEsc(kpiMasterState.filter) + '&rdquo;.</td></tr>';
    }
    html += '</table></div>';

    if (canEdit) {
        html += '<div style="padding:12px 16px; display:flex; gap:8px; border-top:1px solid #E8E6DF;">' +
            '<button class="tool-btn" onclick="ecnSaveProfileModal()">Save as Profile…</button>' +
            '<button class="tool-btn" onclick="ecnResetTargets()">Reset to Baseline</button>' +
            (kpiMasterState.canEdit ? '<button class="tool-btn" onclick="ecnPromoteProfile()" style="color:#856404;">★ Promote to Active SLA</button>' : '') +
            '</div>';
    }
    html += '</div>';

    el.innerHTML = html;
    // Populate the (relocated) profile label + apply bar.
    if (typeof ecnUpdateDirtyBadge === 'function') ecnUpdateDirtyBadge();
}
