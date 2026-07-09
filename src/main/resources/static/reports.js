// === Utilities Tab (v2 — grouped tables with drawer) ===
var reportsLoaded = false;
var reportPollers = {};
var reportsList = [];
var reportSentTimes = {}; // id -> timestamp of last sent
var _utilView = 'mine'; // 'mine' or 'all'
var _drawerReportId = null;
var _drawerReport = null;

// =========================================================================
// System report IDs (used for grouping)
// =========================================================================
var SYSTEM_IDS = ['send-stats', 'download-log', 'dry-run', 'cache-stats', 'clear-caches', 'item-cache-seed', 'item-cache-delta', 'ad-bulk-lookup', 'ecn-report'];

function isSystemReport(r) {
    return SYSTEM_IDS.indexOf(r.id) >= 0;
}

function isUploadedReport(r) {
    return r.type === 'utility';
}

function isBuiltinReport(r) {
    return !isSystemReport(r) && !isUploadedReport(r);
}

// =========================================================================
// Load + render
// =========================================================================

function loadReports() {
    if (reportsLoaded) return;
    fetch('/api/reports')
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(reports) {
            reportsLoaded = true;
            reportsList = reports || [];
            renderUtilitiesPage(reportsList);
        })
        .catch(function() {});
}

function renderUtilitiesPage(reports) {
    if (!reports) reports = [];

    // Split into groups
    var builtin = [];
    var uploaded = [];
    var system = [];
    reports.forEach(function(r) {
        if (isSystemReport(r)) system.push(r);
        else if (isUploadedReport(r)) uploaded.push(r);
        else builtin.push(r);
    });

    // Update count label
    var countLabel = document.getElementById('reportsCountLabel');
    var totalVisible = reports.length;
    var canRun = 0;
    var uploadedCount = uploaded.length;
    reports.forEach(function(r) { if (r.hasAccess !== false && r.hasAccess !== 'false') canRun++; });
    if (countLabel) {
        countLabel.innerHTML = '<b>' + totalVisible + '</b> total \u00b7 <b>' + canRun + '</b> you can run' +
            (uploadedCount > 0 ? ' \u00b7 <b>' + uploadedCount + '</b> uploaded by admins' : '');
    }

    // Show admin controls
    if (window.extIsAdmin) {
        var uploadBtn = document.getElementById('uploadScriptBtn');
        if (uploadBtn) uploadBtn.style.display = '';
        var viewToggle = document.getElementById('viewToggle');
        if (viewToggle) viewToggle.style.display = '';
    }

    // Update group hints
    var builtinHint = document.getElementById('builtinHint');
    if (builtinHint) builtinHint.innerHTML = '<b>' + builtin.length + '</b> report' + (builtin.length !== 1 ? 's' : '') + ' \u00b7 engineered by IT';

    var uploadedHint = document.getElementById('uploadedHint');
    if (uploadedHint) uploadedHint.innerHTML = '<b>' + uploaded.length + '</b> script' + (uploaded.length !== 1 ? 's' : '') + (uploaded.length > 0 ? ' \u00b7 runs need config files' : '');

    var systemHint = document.getElementById('systemHint');
    if (systemHint) systemHint.innerHTML = '<b>' + system.length + '</b> tool' + (system.length !== 1 ? 's' : '') + ' \u00b7 admin-only \u00b7 direct downloads';

    // Render each group table
    renderGroupTable('builtinTableContainer', builtin, 'builtin');
    renderGroupTable('uploadedTableContainer', uploaded, 'uploaded');
    renderGroupTable('systemTableContainer', system, 'system');

    // Show/hide groups based on content
    var groupBuiltin = document.getElementById('groupBuiltin');
    var groupUploaded = document.getElementById('groupUploaded');
    var groupSystem = document.getElementById('groupSystem');
    if (groupBuiltin) groupBuiltin.style.display = builtin.length > 0 ? '' : 'none';
    if (groupUploaded) groupUploaded.style.display = uploaded.length > 0 ? '' : 'none';
    if (groupSystem) groupSystem.style.display = system.length > 0 ? '' : 'none';

    // Check status of each report + scan for email fields in utilities
    reports.forEach(function(report) {
        if (report.hasAccess !== false && report.hasAccess !== 'false') {
            checkReportStatus(report.id);
            if (isUploadedReport(report)) checkEmailFields(report.id);
        }
    });
}

function renderGroupTable(containerId, reports, groupType) {
    var container = document.getElementById(containerId);
    if (!container) return;

    if (!reports || reports.length === 0) {
        container.innerHTML = '<div style="padding:30px 20px;text-align:center;color:var(--ink-3);font-family:var(--font-mono);font-size:12px;">No ' + groupType + ' utilities.</div>';
        return;
    }

    var html = '<table class="util-tbl"><thead><tr>';
    html += '<th style="padding-left:18px">Utility</th>';
    html += '<th>Type</th>';
    html += '<th>Owner</th>';
    html += '<th>Est. runtime</th>';
    html += '<th>Recipients</th>';
    html += '<th>Last run</th>';
    html += '<th style="padding-right:18px;text-align:right">Action</th>';
    html += '</tr></thead><tbody>';

    reports.forEach(function(report) {
        html += renderUtilityRow(report, groupType);
    });

    html += '</tbody></table>';
    container.innerHTML = html;
}

// =========================================================================
// Row rendering
// =========================================================================

function renderUtilityRow(report, groupType) {
    var hasAccess = report.hasAccess !== false && report.hasAccess !== 'false';
    var isBroadcast = report.recipientMode === 'broadcast' || report.id === 'whats-new-digest';
    var recipientCount = report.recipientCount || 0;
    var isDownload = report.id === 'download-log' || report.id === 'cache-stats';
    var hasSupportFiles = report.type === 'utility' && report.supportFiles;
    var rowStyle = hasAccess ? '' : ' style="opacity:0.5"';
    var rowClass = hasAccess ? ' class="expandable"' : '';

    // Check if currently in "all" view and user can't access
    if (_utilView === 'mine' && !hasAccess && !window.extIsAdmin) return '';

    var html = '<tr id="report-row-' + report.id + '"' + rowClass + rowStyle + '>';

    // Name + description
    html += '<td style="padding-left:18px">';
    html += '<div class="name" id="report-name-' + report.id + '">' + esc(report.name);
    if (!hasAccess) html += ' <span class="no-access-tag">no access</span>';
    if (hasSupportFiles && !report.lastRun) {
        html += ' <span style="background:color-mix(in oklab,var(--warn,#c7801b) 14%,white);color:oklch(40% 0.12 60);font-family:var(--font-mono);font-size:9px;letter-spacing:0.08em;padding:1px 5px;border-radius:2px;margin-left:4px;text-transform:uppercase;">Needs config</span>';
    }
    html += '</div>';
    html += '<div class="desc">' + esc(report.description || '') + '</div>';
    html += '<div class="progress-inline" id="report-progress-' + report.id + '" style="display:none;"></div>';
    html += '</td>';

    // Type chip
    html += '<td>';
    if (groupType === 'builtin') {
        html += '<span class="type-chip builtin">BUILT-IN</span>';
    } else if (groupType === 'uploaded') {
        var ext = getFileExt(report.filename);
        html += '<span class="type-chip uploaded">UPLOADED' + (ext ? ' <span class="ext">\u00b7 ' + esc(ext) + '</span>' : '') + '</span>';
    } else {
        html += '<span class="type-chip system">SYSTEM</span>';
    }
    html += '</td>';

    // Owner
    var owner = report.owner || '';
    var isSystemOwner = !owner || owner.toLowerCase() === 'system' || isSystemReport(report);
    html += '<td>';
    if (isSystemOwner) {
        html += '<div class="owner-cell"><div class="av sys">SYS</div>System</div>';
    } else {
        var initials = getReportInitials(owner);
        html += '<div class="owner-cell"><div class="av">' + initials + '</div>' + esc(shortenOwner(owner));
        if (groupType === 'uploaded' && report.uploadedDate) {
            html += '<small>' + esc(report.uploadedDate) + '</small>';
        }
        html += '</div>';
    }
    html += '</td>';

    // Est. runtime
    html += '<td class="rt">' + esc(report.estimatedTime || '~varies') + '</td>';

    // Recipients
    html += '<td>';
    if (isDownload) {
        html += '<span class="recip-pill download"><span class="arr">\u2193</span><span class="c">browser</span></span>';
    } else if (isBroadcast) {
        var countText = recipientCount > 0 ? recipientCount + ' users' : 'all users';
        html += '<span class="recip-pill broadcast"><span class="arr">\u2192</span><span class="c">' + countText + '</span></span>';
        if (report.recipientDesc) html += '<div class="recip-sub">' + esc(report.recipientDesc) + '</div>';
    } else {
        html += '<span class="recip-pill"><span class="arr">\u2192</span><span class="c">you</span></span>';
    }
    html += '</td>';

    // Last run
    html += '<td class="last" id="report-last-' + report.id + '">';
    if (report.lastRun) {
        html += '<b>' + esc(report.lastRun) + '</b>';
        if (report.lastRunBy) html += '<br><span class="who">by ' + esc(report.lastRunBy) + '</span>';
        if (report.lastRowCount) html += ' \u00b7 ' + report.lastRowCount + ' rows';
    } else {
        html += '<span class="who">Never run</span>';
    }
    html += '</td>';

    // Action cell
    html += '<td class="act" style="padding-right:18px" id="report-act-' + report.id + '">';
    if (!hasAccess) {
        html += '<button class="btn sm" disabled>Request access</button>';
    } else if (reportSentTimes[report.id]) {
        html += renderSentState(report.id);
    } else {
        html += renderActionButton(report, groupType, isBroadcast, isDownload, hasSupportFiles, recipientCount);
    }
    html += '</td>';

    html += '</tr>';

    // Run history expansion row (hidden by default)
    html += '<tr id="report-history-' + report.id + '" class="run-history" style="display:none">';
    html += '<td colspan="7" style="padding:0;border-bottom:1px solid var(--line,#e0e0e0)"><div id="report-history-content-' + report.id + '"></div></td>';
    html += '</tr>';

    return html;
}

function renderActionButton(report, groupType, isBroadcast, isDownload, hasSupportFiles, recipientCount) {
    var html = '';
    var isUploaded = groupType === 'uploaded';

    if (report.id === 'clear-caches') {
        // Destructive action
        html += '<button class="btn sm danger" onclick="event.stopPropagation();triggerReport(\'' + report.id + '\')">';
        html += '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" style="width:12px;height:12px;"><path d="M2 4h12M6 4V2h4v2M4 4l1 10h6l1-10"/></svg>';
        html += 'Clear caches</button>';
    } else if (isDownload) {
        // Direct download
        html += '<button class="btn sm" onclick="event.stopPropagation();triggerReport(\'' + report.id + '\')">';
        html += '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" style="width:12px;height:12px;"><path d="M8 2v9M4 7l4 4 4-4M2 14h12"/></svg>';
        html += 'Download now</button>';
    } else if (hasSupportFiles) {
        // Run with files (opens drawer)
        html += '<div class="act-inline">';
        html += '<button class="btn sm primary" onclick="event.stopPropagation();triggerReport(\'' + report.id + '\')">';
        html += '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" style="width:12px;height:12px;"><path d="M3 3v10l4-2 4 2V3M3 3h8"/></svg>';
        html += 'Run with files\u2026</button>';
        if (isUploaded && window.extIsAdmin) {
            html += renderMenuButton(report);
        }
        html += '</div>';
    } else if (isBroadcast) {
        // Broadcast
        var btnCount = recipientCount > 0 ? recipientCount + ' users' : 'all users';
        html += '<button class="btn sm broadcast" onclick="event.stopPropagation();showBroadcastConfirm(\'' + report.id + '\', \'' + esc(report.name).replace(/'/g, "\\'") + '\', \'' + btnCount + '\')">';
        html += '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" style="width:12px;height:12px;"><path d="M2 6v4l3 1 2 4 2-1-1-3 7-3V2L2 6z"/></svg>';
        html += 'Run & send to ' + btnCount + '</button>';
    } else {
        // Personal report
        if (isUploaded) {
            html += '<div class="act-inline">';
        }
        html += '<button class="btn sm primary" id="report-btn-' + report.id + '" onclick="event.stopPropagation();executeRunImmediate(\'' + report.id + '\', true)">';
        html += '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" style="width:12px;height:12px;"><path d="M2 4l6 5 6-5M2 4v8h12V4"/></svg>';
        html += 'Run & email me</button>';
        if (isUploaded && window.extIsAdmin) {
            html += renderMenuButton(report);
            html += '</div>';
        } else if (isUploaded) {
            html += '</div>';
        }
        // "Also add me to script email" toggle — only for utilities
        if (isUploaded) {
            html += '<div id="inject-email-row-' + report.id + '" style="display:none;margin-top:6px;font-size:11.5px;color:#555;">';
            html += '<label style="display:flex;align-items:center;gap:6px;cursor:pointer;font-weight:normal;" onclick="event.stopPropagation()">';
            html += '<input type="checkbox" id="inject-email-' + report.id + '" onchange="onInjectToggle(\'' + report.id + '\')" style="margin:0">';
            html += '<span>Also add my email to script notifications</span>';
            html += '</label>';
            html += '<div id="inject-email-detail-' + report.id + '" style="display:none;margin:4px 0 0 22px;font-size:10.5px;color:#888;font-family:monospace;"></div>';
            html += '</div>';
        }
    }

    return html;
}

function renderMenuButton(report) {
    var html = '<button class="act-menu" onclick="event.stopPropagation();toggleMenu(this)" style="position:relative">\u22ef</button>';
    html += '<div class="menu-pop">';
    html += '<button onclick="event.stopPropagation();closeAllMenus();showEditUtilityModal(\'' + esc(report.id) + '\')">\u270e Edit script</button>';
    html += '<button onclick="event.stopPropagation();closeAllMenus();appAlert(\'Coming soon\')">\u2398 Duplicate</button>';
    if (report.filename) {
        html += '<button onclick="event.stopPropagation();closeAllMenus();window.location.href=\'/api/reports/utility/' + esc(report.id) + '/file/' + esc(report.filename) + '\'">&#x2193; Download source</button>';
    }
    // Only owner can delete
    var isOwner = !report.ownerUsername || report.ownerUsername === window.currentUsername;
    if (isOwner) {
        html += '<button class="danger" onclick="event.stopPropagation();closeAllMenus();confirmDeleteUtility(\'' + esc(report.id) + '\', \'' + esc(report.name) + '\')">\u2715 Delete utility</button>';
    }
    html += '</div>';
    return html;
}

// =========================================================================
// Helpers
// =========================================================================

function getFileExt(filename) {
    if (!filename) return '';
    var dot = filename.lastIndexOf('.');
    return dot >= 0 ? filename.substring(dot) : '';
}

function shortenOwner(name) {
    if (!name) return '';
    // "Last, First" -> "First L."
    var parts = name.split(',');
    if (parts.length >= 2) {
        var last = parts[0].trim();
        var first = parts[1].trim().split(' ')[0];
        return first + ' ' + last.charAt(0) + '.';
    }
    return name;
}

function getReportInitials(name) {
    if (!name) return '?';
    var parts = name.split(/[\s,]+/).filter(function(p) { return p.length > 0; });
    if (parts.length >= 2) return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
    return parts[0] ? parts[0][0].toUpperCase() : '?';
}

// =========================================================================
// Menu handling
// =========================================================================

function toggleMenu(btn) {
    var menu = btn.nextElementSibling;
    if (!menu) return;
    var wasOpen = menu.classList.contains('open');
    closeAllMenus();
    if (!wasOpen) menu.classList.add('open');
}

function closeAllMenus() {
    var menus = document.querySelectorAll('.menu-pop.open');
    for (var i = 0; i < menus.length; i++) {
        menus[i].classList.remove('open');
    }
}

// Close menus on outside click
document.addEventListener('click', function(e) {
    if (!e.target.closest('.act-menu') && !e.target.closest('.menu-pop')) {
        closeAllMenus();
    }
});

// =========================================================================
// Search / filter
// =========================================================================

function filterUtilities() {
    var input = document.getElementById('utilSearchInput');
    var query = input ? input.value.toLowerCase().trim() : '';
    var rows = document.querySelectorAll('.util-tbl tbody tr');
    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        if (row.classList.contains('run-history')) {
            // History rows follow data rows, handled separately
            continue;
        }
        var name = row.querySelector('.name');
        var desc = row.querySelector('.desc');
        var text = '';
        if (name) text += name.textContent.toLowerCase();
        if (desc) text += ' ' + desc.textContent.toLowerCase();
        var match = !query || text.indexOf(query) >= 0;
        row.style.display = match ? '' : 'none';
        // Also hide associated history row
        var nextRow = row.nextElementSibling;
        if (nextRow && nextRow.classList.contains('run-history') && !match) {
            nextRow.style.display = 'none';
        }
    }
}

// =========================================================================
// Mine/All toggle
// =========================================================================

function setUtilView(view) {
    _utilView = view;
    var toggle = document.getElementById('viewToggle');
    if (toggle) {
        var buttons = toggle.querySelectorAll('button');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].classList.remove('on');
        }
        if (view === 'mine') buttons[0].classList.add('on');
        else buttons[1].classList.add('on');
    }
    // Re-render
    reportsLoaded = false;
    reportsList.length = 0;
    loadReports();
}

// =========================================================================
// Row expand (run history)
// =========================================================================

function toggleRunHistory(id) {
    var historyRow = document.getElementById('report-history-' + id);
    if (!historyRow) return;

    var isVisible = historyRow.style.display !== 'none';
    if (isVisible) {
        historyRow.style.display = 'none';
        return;
    }

    historyRow.style.display = 'table-row';

    // Load current status info for the history display
    var contentDiv = document.getElementById('report-history-content-' + id);
    if (!contentDiv) return;

    fetch('/api/reports/' + id + '/status')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            var report = null;
            reportsList.forEach(function(r) { if (r.id === id) report = r; });
            var name = report ? report.name : id;

            var html = '<div style="background:var(--surface-2,#f5f3ed)">';
            html += '<div class="rh-head"><span><b>Last run</b> \u2014 ' + esc(name) + '</span>';
            if (data.status === 'completed' && data.elapsedSeconds) {
                html += '<span>runtime <b>' + data.elapsedSeconds + 's</b></span>';
            }
            html += '</div>';
            html += '<table class="rh-tbl">';

            if (data.status === 'completed') {
                var endTime = data.endTime ? new Date(data.endTime).toLocaleString([], {month:'short', day:'numeric', hour:'2-digit', minute:'2-digit'}) : 'Unknown';
                var sizeText = data.size ? (data.size / 1024).toFixed(0) + ' KB' : '\u2014';
                html += '<tr><td>' + endTime + '</td>';
                html += '<td><span class="rh-dot s"></span>Completed</td>';
                html += '<td>' + (data.elapsedSeconds ? data.elapsedSeconds + 's' : '\u2014') + '</td>';
                html += '<td>' + sizeText + '</td>';
                html += '<td><a onclick="downloadReport(\'' + id + '\')">\u2193 download</a></td></tr>';
            } else if (data.status === 'failed') {
                html += '<tr><td>\u2014</td>';
                html += '<td><span class="rh-dot f"></span>Failed</td>';
                html += '<td>' + esc(data.error || 'Unknown error').substring(0, 80) + '</td>';
                html += '<td>\u2014</td>';
                html += '<td><a onclick="downloadRunLog(\'' + id + '\')">\u2193 log</a></td></tr>';
            } else if (data.status === 'running') {
                html += '<tr><td>Now</td>';
                html += '<td><span class="rh-dot s" style="background:var(--accent,#2c6fbb);animation:pulse 1.4s infinite"></span>Running</td>';
                html += '<td>' + (data.elapsedSeconds || 0) + 's</td>';
                html += '<td>\u2014</td>';
                html += '<td>\u2014</td></tr>';
            } else {
                html += '<tr><td colspan="5" style="text-align:center;color:var(--ink-3)">No run data available</td></tr>';
            }

            html += '</table></div>';
            contentDiv.innerHTML = html;
        })
        .catch(function() {
            contentDiv.innerHTML = '<div style="padding:14px 18px;color:var(--ink-3);font-family:var(--font-mono);font-size:11px;">Could not load run history.</div>';
        });
}

// Attach expand handler via delegation
document.addEventListener('click', function(e) {
    var row = e.target.closest('tr.expandable');
    if (!row) return;
    // Don't expand if clicking a button, link, or menu
    if (e.target.closest('button') || e.target.closest('a') || e.target.closest('.act-menu') || e.target.closest('.menu-pop')) return;
    var id = row.id ? row.id.replace('report-row-', '') : null;
    if (id) toggleRunHistory(id);
});

// =========================================================================
// Sent state rendering
// =========================================================================

var _lastKnownFilenames = {}; // id -> filename from last status poll

function hasOutputFile(id) {
    return !!_lastKnownFilenames[id];
}

function renderSentState(id) {
    var time = reportSentTimes[id];
    var timeStr = time ? new Date(time).toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'}) : '';

    // Add ⋯ menu for uploaded utilities
    var report = null;
    reportsList.forEach(function(r) { if (r.id === id) report = r; });
    var isUtility = report && report.type === 'utility';
    var menuHtml = '';
    if (isUtility && window.extIsAdmin) {
        var ownerMatch = !report.ownerUsername || report.ownerUsername === window.currentUsername;
        menuHtml = ' <button class="act-menu" onclick="event.stopPropagation();this.nextElementSibling.classList.toggle(\'open\')" style="position:relative">\u22ef</button>' +
            '<div class="menu-pop">' +
            '<button onclick="event.stopPropagation();showEditUtilityModal(\'' + id + '\')">\u270e Edit script & env</button>' +
            '<button onclick="event.stopPropagation();window.location.href=\'/api/reports/utility/' + id + '/file/' + esc((report && report.filename) || '') + '\'">\u2193 Download source</button>' +
            (ownerMatch ? '<button class="danger" onclick="event.stopPropagation();confirmDeleteUtility(\'' + id + '\', \'' + esc((report && report.name) || '') + '\')">\u2715 Delete</button>' : '') +
            '</div>';
    }

    return '<div class="act-group">' +
        '<div class="act-inline">' +
        '<span class="state-sent">\u2713 sent ' + timeStr + '</span>' +
        menuHtml +
        '</div>' +
        '<span class="sub">' +
        (hasOutputFile(id) ? '<a onclick="event.stopPropagation();downloadReport(\'' + id + '\')">download</a> \u00b7 ' : '') +
        '<a onclick="event.stopPropagation();downloadRunLog(\'' + id + '\')">log</a> \u00b7 <a onclick="event.stopPropagation();triggerReport(\'' + id + '\')">rerun</a></span>' +
        '</div>';
}

// =========================================================================
// Broadcast confirm modal
// =========================================================================

function showBroadcastConfirm(id, name, countText) {
    var existing = document.getElementById('broadcastConfirmModal');
    if (existing) existing.remove();

    var modal = document.createElement('div');
    modal.id = 'broadcastConfirmModal';
    modal.className = 'modal-scrim';
    modal.innerHTML =
        '<div class="report-modal">' +
        '  <div class="mh">' +
        '    <div class="kicker">Broadcast \u00b7 goes to real users</div>' +
        '    <h3>Send <i>' + esc(name) + '</i> to ' + esc(countText) + '?</h3>' +
        '  </div>' +
        '  <div class="mb">' +
        '    <p>This will email the report to all recipients. Make sure the content is ready before sending.</p>' +
        '    <div class="recipblock">' +
        '      <div class="rk">Recipients</div>' +
        '      <div class="rv">' + esc(countText) + '</div>' +
        '    </div>' +
        '  </div>' +
        '  <div class="mf">' +
        '    <button class="btn" onclick="closeBroadcastConfirm()">Cancel</button>' +
        '    <button class="btn primary" onclick="confirmBroadcastSend(\'' + esc(id) + '\')">Send to ' + esc(countText) + '</button>' +
        '  </div>' +
        '</div>';
    document.body.appendChild(modal);

    modal.querySelector('.btn:not(.primary)').focus();

    modal.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') closeBroadcastConfirm();
    });
}

function closeBroadcastConfirm() {
    var modal = document.getElementById('broadcastConfirmModal');
    if (modal) modal.remove();
}

function confirmBroadcastSend(id) {
    closeBroadcastConfirm();
    executeRunImmediate(id);
}

// =========================================================================
// Trigger, poll, update
// =========================================================================

function triggerReport(id) {
    var report = null;
    reportsList.forEach(function(r) { if (r.id === id) report = r; });

    // If it's a utility with support files, show the drawer
    if (report && report.type === 'utility' && report.supportFiles) {
        showRunWithFilesDrawer(id, report);
        return;
    }

    // Otherwise, run immediately
    executeRunImmediate(id);
}

function executeRunImmediate(id, emailMe, injectMyEmail) {
    var actCell = document.getElementById('report-act-' + id);
    if (actCell) {
        actCell.innerHTML = '<div class="act-group"><span class="state-queued">Queued\u2026</span></div>';
    }

    var url = '/api/reports/' + id + '/run';
    var params = [];
    if (emailMe) params.push('emailMe=true');
    // Check drawer checkbox OR card checkbox
    if (injectMyEmail) {
        params.push('injectMyEmail=true');
    } else {
        var injectCb = document.getElementById('inject-email-' + id);
        if (injectCb && injectCb.checked) params.push('injectMyEmail=true');
    }
    if (params.length > 0) url += '?' + params.join('&');

    fetch(url, { method: 'POST' })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                if (data.message && data.message.indexOf('REDIRECT:') === 0) {
                    var redirectUrl = data.message.substring(9);
                    if (id === 'dry-run') {
                        doDryRun();
                    } else {
                        window.location.href = redirectUrl;
                    }
                    var actCell2 = document.getElementById('report-act-' + id);
                    if (actCell2) {
                        reportSentTimes[id] = Date.now();
                        actCell2.innerHTML = renderSentState(id);
                    }
                    return;
                }
                startReportPolling(id);
                if (id === 'send-stats') {
                    setTimeout(function() {
                        reportSentTimes[id] = Date.now();
                        var ac = document.getElementById('report-act-' + id);
                        if (ac) ac.innerHTML = renderSentState(id);
                    }, 3000);
                }
            } else {
                reportsLoaded = false;
                loadReports();
                showCustomAlert('PLM Toolkit', data.message || 'Failed to start report.');
            }
        })
        .catch(function(err) {
            reportsLoaded = false;
            loadReports();
            showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
        });
}

function startReportPolling(id) {
    if (reportPollers[id]) clearInterval(reportPollers[id]);
    updateReportUI(id, 'running', null);
    reportPollers[id] = setInterval(function() { checkReportStatus(id); }, 5000);
}

function checkReportStatus(id) {
    fetch('/api/reports/' + id + '/status')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            updateReportUI(id, data.status, data);
            if (data.status === 'completed' || data.status === 'failed') {
                if (reportPollers[id]) { clearInterval(reportPollers[id]); reportPollers[id] = null; }
            } else if (data.status === 'running' && !reportPollers[id]) {
                startReportPolling(id);
            }
        })
        .catch(function() {});
}

function updateReportUI(id, status, data) {
    var row = document.getElementById('report-row-' + id);
    var actCell = document.getElementById('report-act-' + id);
    var progress = document.getElementById('report-progress-' + id);
    var lastCell = document.getElementById('report-last-' + id);
    if (!row) return;

    row.classList.remove('running');

    if (status === 'running') {
        row.classList.add('running');
        var elapsed = data && data.elapsedSeconds ? data.elapsedSeconds : 0;
        var pct = data && data.progress ? data.progress : 0;
        var mins = Math.floor(elapsed / 60);
        var secs = elapsed % 60;
        var timeStr = mins + ':' + (secs < 10 ? '0' : '') + secs;

        if (progress) {
            progress.style.display = 'block';
            progress.innerHTML = 'RUNNING \u00b7 ' + (pct > 0 ? pct + '%' : timeStr) +
                '<div class="bar"><div class="fill" style="width:' + (pct || 50) + '%;"></div></div>';
        }

        if (actCell) {
            actCell.innerHTML =
                '<div class="act-group">' +
                '<span class="state-running">Running \u00b7 ' + timeStr + '</span>' +
                '<span class="sub"><a onclick="event.stopPropagation();showReportDetailPanel(\'' + id + '\', null)">view stream</a> \u00b7 <a onclick="event.stopPropagation();cancelReport(\'' + id + '\')">cancel</a></span>' +
                '</div>';
        }

        if (lastCell) {
            lastCell.innerHTML = '<b>Running now</b><br><span class="who">' + timeStr + ' elapsed</span>';
        }

        showReportDetailPanel(id, data);

    } else if (status === 'completed') {
        if (progress) progress.style.display = 'none';

        var endTime = data && data.endTime ? data.endTime : Date.now();
        reportSentTimes[id] = endTime;
        if (data.filename) _lastKnownFilenames[id] = data.filename;
        else delete _lastKnownFilenames[id];
        var elapsedC = data && data.elapsedSeconds ? data.elapsedSeconds : 0;
        var sizeText = data && data.size ? ' \u00b7 ' + (data.size / 1024).toFixed(0) + ' KB' : '';

        if (actCell) actCell.innerHTML = renderSentState(id);

        var ageMs = Date.now() - endTime;
        var timeLabel;
        if (ageMs < 300000) {
            timeLabel = '<b>Just now</b> (' + elapsedC + 's)';
        } else if (ageMs < 86400000) {
            timeLabel = '<b>' + new Date(endTime).toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'}) + '</b> (' + elapsedC + 's)';
        } else {
            timeLabel = '<b>' + new Date(endTime).toLocaleDateString([], {month:'short', day:'numeric'}) + ' ' + new Date(endTime).toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'}) + '</b>';
        }

        if (lastCell) {
            if (data.filename) {
                lastCell.innerHTML = timeLabel + sizeText +
                    '<br><a class="dl" onclick="event.stopPropagation();downloadReport(\'' + id + '\')">\u2193 Download ' + esc(data.filename) + '</a>';
            } else {
                lastCell.innerHTML = timeLabel + '<br><span class="who">completed \u2014 no output file</span>';
            }
        }

        // Keep stream panel visible after completion so user can see the full log
        if (data && data.logs && data.logs.length > 0) {
            showReportDetailPanel(id, data);
        } else {
            hideReportDetailPanel();
        }

    } else if (status === 'failed') {
        if (progress) progress.style.display = 'none';

        var errMsg = data && data.error ? data.error : 'Unknown error';
        if (errMsg.length > 120) errMsg = errMsg.substring(0, 120) + '\u2026';

        if (actCell) {
            var report = null;
            reportsList.forEach(function(r) { if (r.id === id) report = r; });
            var isUtility = report && report.type === 'utility';

            var menuHtml = '';
            if (isUtility && window.extIsAdmin) {
                var ownerMatch = !report.ownerUsername || report.ownerUsername === window.currentUsername;
                menuHtml = ' <button class="act-menu" onclick="event.stopPropagation();this.nextElementSibling.classList.toggle(\'open\')" style="position:relative">\u22ef</button>' +
                    '<div class="menu-pop">' +
                    '<button onclick="event.stopPropagation();showEditUtilityModal(\'' + id + '\')">\u270e Edit script & env</button>' +
                    '<button onclick="event.stopPropagation();window.location.href=\'/api/reports/utility/' + id + '/file/' + esc((report && report.filename) || '') + '\'">\u2193 Download source</button>' +
                    (ownerMatch ? '<button class="danger" onclick="event.stopPropagation();confirmDeleteUtility(\'' + id + '\', \'' + esc((report && report.name) || '') + '\')">\u2715 Delete</button>' : '') +
                    '</div>';
            }

            actCell.innerHTML = '<div class="act-group">' +
                '<div class="act-inline">' +
                '<span class="state-failed">\u2715 failed</span>' +
                menuHtml +
                '</div>' +
                '<span class="sub"><a onclick="event.stopPropagation();downloadRunLog(\'' + id + '\')">view log</a> \u00b7 ' +
                (isUtility ? '<a onclick="event.stopPropagation();showEditUtilityModal(\'' + id + '\')">fix config</a> \u00b7 ' : '') +
                '<a onclick="event.stopPropagation();executeRunImmediate(\'' + id + '\', true)">retry & email</a></span>' +
                '</div>';
        }

        if (lastCell) {
            lastCell.innerHTML = '<span style="color:var(--bad,#b8342b);font-weight:500;">Failed</span><br><span class="who">' + esc(errMsg) + '</span>';
        }

        // Keep stream panel visible on failure so user can see error logs
        if (data && data.logs && data.logs.length > 0) {
            showReportDetailPanel(id, data);
        } else {
            hideReportDetailPanel();
        }

    } else {
        if (progress) progress.style.display = 'none';
    }
}

function downloadReport(id) {
    window.location.href = '/api/reports/' + id + '/download';
}

// =========================================================================
// Detail panel + cancel
// =========================================================================

function showReportDetailPanel(id, data) {
    var panel = document.getElementById('reportDetailPanel');
    if (!panel) return;

    var report = null;
    reportsList.forEach(function(r) { if (r.id === id) report = r; });
    var name = report ? report.name : id;
    var isBroadcast = report && (report.recipientMode === 'broadcast' || report.id === 'whats-new-digest');
    var elapsed = data && data.elapsedSeconds ? data.elapsedSeconds : 0;
    var pct = data && data.progress ? data.progress : 0;
    var stage = data && data.stage ? data.stage : '';
    var logs = data && data.logs ? data.logs : [];
    var deliveryText = isBroadcast ? 'email \u2192 all recipients' : 'email \u2192 you';

    var logHtml = '';
    logs.forEach(function(line) {
        var cls = '';
        if (line.indexOf('\u2713') >= 0 || line.indexOf('OK') >= 0) cls = 'g';
        else if (line.indexOf('\u26A0') >= 0 || line.indexOf('warn') >= 0) cls = 'w';
        logHtml += '<div>' + (cls ? '<span class="' + cls + '">' : '') + esc(line) + (cls ? '</span>' : '') + '</div>';
    });
    if (!logHtml) logHtml = '<div style="color:#7a818b;">Waiting for output\u2026</div>';

    // Determine actual status for the panel header
    var runStatus = data && data.status ? data.status : 'running';
    var kickerText, barWidth, pctText, cancelBtn;

    if (runStatus === 'completed') {
        kickerText = 'Completed \u00b7 ' + elapsed + 's \u00b7 ' + deliveryText;
        barWidth = '100%';
        pctText = '\u2713 Done';
        cancelBtn = '';
    } else if (runStatus === 'failed') {
        kickerText = 'Failed \u00b7 ' + elapsed + 's';
        barWidth = '100%';
        pctText = '\u2717 ' + (data && data.error ? esc(data.error).substring(0, 80) : 'Error');
        cancelBtn = '';
    } else {
        kickerText = 'Running \u00b7 ' + (pct > 0 ? pct + '% complete' : elapsed + 's elapsed') + ' \u00b7 will ' + deliveryText + ' when done';
        barWidth = (pct || 50) + '%';
        pctText = stage ? esc(stage) : 'Processing\u2026';
        cancelBtn = '<button class="btn sm" style="color:var(--bad);border-color:rgba(184,52,43,0.3);" onclick="cancelReport(\'' + id + '\')">Cancel run</button>';
    }

    panel.style.display = 'grid';
    panel.className = 'report-detail';
    panel.innerHTML =
        '<div class="runcard">' +
        '  <div style="display:flex;justify-content:space-between;align-items:start;">' +
        '    <div>' +
        '      <h3>' + esc(name) + '</h3>' +
        '      <div class="kicker">' + kickerText + '</div>' +
        '    </div>' +
        cancelBtn +
        '  </div>' +
        '  <div class="run-bar" style="margin-top:12px;"><div class="fill" style="width:' + barWidth + ';' + (runStatus === 'failed' ? 'background:var(--bad,#b8342b);' : runStatus === 'completed' ? 'background:var(--good,#1f8a4c);' : '') + '"></div></div>' +
        '  <div class="run-pct">' + pctText + '</div>' +
        '  <div class="stream">' + logHtml + '</div>' +
        '  <div style="margin-top:8px;display:flex;gap:10px;align-items:center;">' +
        '    <a href="#" onclick="downloadRunLog(\'' + id + '\'); return false;" style="font-family:var(--font-mono);font-size:10px;color:var(--accent,#2c6fbb);text-decoration:none;letter-spacing:0.04em;">\u2193 Download log</a>' +
        '  </div>' +
        '</div>' +
        '<div class="side-card">' +
        '  <h4>Run details</h4>' +
        '  <dl class="kv">' +
        '    <dt>Run ID</dt><dd>RUN-' + Math.random().toString(16).substring(2, 6).toUpperCase() + '</dd>' +
        '    <dt>Started</dt><dd>' + elapsed + 's ago</dd>' +
        '    <dt>Delivery</dt><dd>' + deliveryText + '</dd>' +
        '    <dt>Output</dt><dd>xlsx attachment</dd>' +
        '    <dt>Triggered</dt><dd>manual</dd>' +
        '  </dl>' +
        '</div>';

    setTimeout(function() { panel.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, 100);
}

function hideReportDetailPanel() {
    var panel = document.getElementById('reportDetailPanel');
    if (panel) panel.style.display = 'none';
}

function cancelReport(id) {
    fetch('/api/reports/' + id + '/cancel', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function() { checkReportStatus(id); })
        .catch(function() {});
}

// =========================================================================
// Run with files — DRAWER (replaces old modal)
// =========================================================================

function classifySupportFile(filename) {
    var ext = filename.toLowerCase().replace(/^.*\./, '.');
    var configExts = ['.properties', '.xml', '.json', '.cfg', '.ini', '.yaml', '.yml'];
    for (var i = 0; i < configExts.length; i++) {
        if (ext === configExts[i]) return 'Config';
    }
    return 'Input';
}

function showRunWithFilesDrawer(id, report) {
    _drawerReportId = id;
    _drawerReport = report;

    var titleEl = document.getElementById('drawerTitle');
    if (titleEl) titleEl.textContent = report.name;

    var body = document.getElementById('drawerBody');
    if (!body) return;

    var files = report.supportFiles.split(',');
    var html = '';

    // Last config note
    if (report.lastRun) {
        html += '<div class="last-config-note">';
        html += '<b>Last run \u00b7 ' + esc(report.lastRun) + '</b>';
        if (report.lastRunBy) html += ' by ' + esc(report.lastRunBy);
        html += ' \u2014 your stored config files are pre-loaded below. Click Run to reuse them, or replace any file before running.';
        html += '</div>';
    } else {
        html += '<div class="last-config-note">';
        html += '<b>Last run \u00b7 never</b> \u2014 this is the first run. Upload the required files below. We\'ll remember your config for next time.';
        html += '</div>';
    }

    html += '<h4>Config & input files</h4>';

    for (var i = 0; i < files.length; i++) {
        var fname = files[i].trim();
        if (!fname) continue;
        var fileType = classifySupportFile(fname);
        var typeClass = fileType === 'Config' ? 'cfg' : 'inp';

        html += '<div class="file-row">';
        html += '<div class="fr-top">';
        html += '<span class="fr-type ' + typeClass + '">' + fileType + '</span>';
        html += '<span class="fr-name">' + esc(fname) + '</span>';
        html += '<span class="fr-stored">\u2713 stored</span>';
        html += '</div>';
        html += '<div class="fr-input">';
        html += '<input type="file" class="drawer-file-picker" data-filename="' + esc(fname) + '" onchange="onDrawerFileSelected(this, ' + i + ')">';
        html += '</div>';
        html += '<div id="drawer-warnings-' + i + '" style="display:none;"></div>';
        html += '<div class="fr-replace-hint">Replace with a new file or leave to use the stored version.</div>';
        html += '</div>';
    }

    // Environment variables (if any detected)
    if (report.envVars) {
        html += '<h4>Runtime arguments <span style="font-weight:400;color:var(--ink-4);text-transform:none;letter-spacing:0">optional</span></h4>';
        try {
            var envObj = typeof report.envVars === 'string' ? JSON.parse(report.envVars) : report.envVars;
            for (var key in envObj) {
                if (envObj.hasOwnProperty(key)) {
                    html += '<div class="env-row">';
                    html += '<label>' + esc(key) + '</label>';
                    html += '<input type="text" class="drawer-env-val" data-key="' + esc(key) + '" placeholder="' + esc(envObj[key] || '') + '" value="' + esc(envObj[key] || '') + '">';
                    html += '</div>';
                }
            }
        } catch (e) { /* skip env vars if parsing fails */ }
    }

    // Delivery options
    html += '<h4>Delivery</h4>';
    html += '<div class="delivery-radio">';
    html += '<label class="on" onclick="selectDeliveryRadio(this)">';
    html += '<input type="radio" name="drawerDelivery" value="email" checked>';
    html += '<div><span class="dt">Email me the output</span>Output sent to your email when the run finishes.</div>';
    html += '</label>';
    html += '<label onclick="selectDeliveryRadio(this)">';
    html += '<input type="radio" name="drawerDelivery" value="none">';
    html += '<div><span class="dt">No delivery \u2014 log only</span>Useful for dry-runs. Output stays in run history.</div>';
    html += '</label>';
    html += '</div>';

    // Email injection option
    html += '<div id="drawerInjectRow" style="display:none;margin-top:12px;">';
    html += '<label style="display:flex;align-items:flex-start;gap:8px;cursor:pointer;font-size:12.5px;color:#333;padding:10px 14px;background:#f0f7ff;border:1px solid #d0e3f5;border-radius:6px;">';
    html += '<input type="checkbox" id="drawerInjectEmail" style="margin-top:2px">';
    html += '<div><strong>Also add my email to script notifications</strong>';
    html += '<div id="drawerInjectDetail" style="font-size:11px;color:#888;margin-top:4px;font-family:monospace;"></div>';
    html += '</div></label></div>';

    body.innerHTML = html;

    // Check for email fields in config and show the toggle if found
    fetch('/api/reports/' + id + '/email-fields')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var fields = data.fields || [];
            if (fields.length === 0) return;
            var row = document.getElementById('drawerInjectRow');
            if (row) row.style.display = '';
            var detail = document.getElementById('drawerInjectDetail');
            if (detail) {
                var html = '';
                fields.forEach(function(f) {
                    var lbl = f.action === 'append'
                        ? 'will append your email to: ' + f.currentValue
                        : 'will set to your email';
                    html += f.key + ' \u2014 ' + lbl + '<br>';
                });
                detail.innerHTML = html;
            }
        }).catch(function(){});

    // Open drawer
    document.getElementById('drawerScrim').classList.add('open');
    document.getElementById('paramsDrawer').classList.add('open');
}

function selectDeliveryRadio(label) {
    var radios = label.closest('.delivery-radio');
    if (!radios) return;
    var labels = radios.querySelectorAll('label');
    for (var i = 0; i < labels.length; i++) labels[i].classList.remove('on');
    label.classList.add('on');
}

function onDrawerFileSelected(input, idx) {
    if (!input.files || !input.files.length) return;
    var file = input.files[0];
    var fname = file.name.toLowerCase();
    if (!fname.endsWith('.properties') && !fname.endsWith('.cfg') && !fname.endsWith('.ini')) return;

    var warningsDiv = document.getElementById('drawer-warnings-' + idx);
    if (!warningsDiv) return;
    warningsDiv.style.display = 'block';
    warningsDiv.innerHTML = '<div class="fr-validate">\u2699 Validating\u2026</div>';

    var formData = new FormData();
    formData.append('file', file);
    fetch('/api/reports/validate-properties', { method: 'POST', body: formData })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (!data.success || !data.warnings || data.warnings.length === 0) {
                warningsDiv.innerHTML = '<div class="fr-validate">\u2713 Paths look good</div>';
                setTimeout(function() { warningsDiv.style.display = 'none'; }, 3000);
            } else {
                renderPathWarnings(warningsDiv, data.warnings);
            }
        })
        .catch(function() { warningsDiv.style.display = 'none'; });
}

function closeParamsDrawer() {
    document.getElementById('drawerScrim').classList.remove('open');
    document.getElementById('paramsDrawer').classList.remove('open');
    _drawerReportId = null;
    _drawerReport = null;
}

function resetDrawerDefaults() {
    if (_drawerReport) {
        showRunWithFilesDrawer(_drawerReportId, _drawerReport);
    }
}

function submitDrawerRun() {
    if (!_drawerReportId) return;
    var id = _drawerReportId;

    var pickers = document.querySelectorAll('.drawer-file-picker');
    var hasNewFiles = false;
    var formData = new FormData();

    // Check delivery option
    var emailMe = false;
    var deliveryRadio = document.querySelector('input[name="drawerDelivery"]:checked');
    if (deliveryRadio && deliveryRadio.value === 'email') emailMe = true;

    // Check inject email option
    var injectCb = document.getElementById('drawerInjectEmail');
    var injectMyEmail = injectCb && injectCb.checked;

    for (var i = 0; i < pickers.length; i++) {
        if (pickers[i].files && pickers[i].files.length > 0) {
            formData.append('files', pickers[i].files[0]);
            hasNewFiles = true;
        }
    }

    closeParamsDrawer();

    if (!hasNewFiles) {
        executeRunImmediate(id, emailMe, injectMyEmail);
        return;
    }

    // Upload replacement files first, then run
    var actCell = document.getElementById('report-act-' + id);
    if (actCell) {
        actCell.innerHTML = '<div class="act-group"><span class="state-queued">Uploading files\u2026</span></div>';
    }

    fetch('/api/reports/' + id + '/replace-files', { method: 'POST', body: formData })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                executeRunImmediate(id, emailMe, injectMyEmail);
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Failed to replace files.');
                reportsLoaded = false;
                loadReports();
            }
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'File upload failed: ' + err.message);
            reportsLoaded = false;
            loadReports();
        });
}

// Keep old modal function as alias for backward compat
function showRunWithFilesModal(id, report) {
    showRunWithFilesDrawer(id, report);
}

function closeRunWithFilesModal() {
    closeParamsDrawer();
}

function submitRunWithFiles(id, checkForNewFiles) {
    _drawerReportId = id;
    submitDrawerRun();
}

// ESC closes drawer
document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') {
        var scrim = document.getElementById('drawerScrim');
        if (scrim && scrim.classList.contains('open')) {
            closeParamsDrawer();
        }
    }
});

// =========================================================================
// Utilities — upload / delete scripts
// =========================================================================

var _wizardData = {};

function _wizardStepIndicator(step) {
    var labels = ['File', 'Support files', 'Details'];
    var html = '<div style="display:flex;align-items:center;justify-content:center;gap:6px;margin-bottom:14px;">';
    for (var i = 0; i < labels.length; i++) {
        var n = i + 1;
        var active = n === step;
        var done = n < step;
        var dotBg = active ? 'var(--accent,#2c6fbb)' : done ? 'var(--good,#1f8a4c)' : 'var(--line,#d0d5dd)';
        var dotColor = active || done ? '#fff' : 'var(--ink-3,#888)';
        var labelColor = active ? 'var(--ink,#222)' : 'var(--ink-3,#888)';
        var labelWeight = active ? '600' : '400';
        html += '<div style="display:flex;align-items:center;gap:4px;">';
        html += '<span style="display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;border-radius:50%;background:' + dotBg + ';color:' + dotColor + ';font-size:10px;font-weight:600;">' + (done ? '\u2713' : n) + '</span>';
        html += '<span style="font-size:11px;color:' + labelColor + ';font-weight:' + labelWeight + ';">' + labels[i] + '</span>';
        if (i < labels.length - 1) html += '<span style="color:var(--line,#d0d5dd);margin:0 2px;">\u2014</span>';
        html += '</div>';
    }
    html += '</div>';
    return html;
}

function showUploadScriptModal() {
    _wizardData = {};
    var existing = document.getElementById('uploadScriptModal');
    if (existing) existing.remove();

    var modal = document.createElement('div');
    modal.id = 'uploadScriptModal';
    modal.className = 'modal-scrim';
    modal.innerHTML =
        '<div class="report-modal" style="max-width:520px;max-height:80vh;overflow-y:auto;display:flex;flex-direction:column;">' +
        '  <div class="mh">' +
        '    <div class="kicker">Utilities</div>' +
        '    <h3>Upload Script</h3>' +
        '  </div>' +
        '  <div class="mb" style="flex:1;overflow-y:auto;display:flex;flex-direction:column;gap:12px;">' +
        _wizardStepIndicator(1) +
        '    <div>' +
        '      <label style="font-size:11px;font-weight:600;color:#666;text-transform:uppercase;">Script File (.py, .java, or .jar)</label><br>' +
        '      <input type="file" id="wizardScriptFile" accept=".py,.java,.jar" style="font-size:12px;margin-top:4px;" onchange="onWizardFileSelected()">' +
        '    </div>' +
        '    <div id="wizardAnalysisStatus" style="display:none;margin-top:2px;font-family:var(--font-mono);font-size:11px;color:var(--accent,#2c6fbb);"></div>' +
        '    <div id="wizardEntryPointSection" style="display:none;"></div>' +
        '  </div>' +
        '  <div class="mf">' +
        '    <button class="btn" onclick="closeUploadScriptModal()">Cancel</button>' +
        '    <button class="btn primary" id="wizardNextBtn1" disabled onclick="wizardStep2()">Next \u2192</button>' +
        '  </div>' +
        '</div>';
    document.body.appendChild(modal);

    modal.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') closeUploadScriptModal();
    });
}

function onWizardFileSelected() {
    var fileInput = document.getElementById('wizardScriptFile');
    if (!fileInput || !fileInput.files || !fileInput.files.length) return;

    _wizardData.file = fileInput.files[0];
    var statusEl = document.getElementById('wizardAnalysisStatus');
    statusEl.style.display = 'block';
    statusEl.style.color = 'var(--accent,#2c6fbb)';
    statusEl.innerHTML = '\u2699 Analyzing script\u2026';

    var nextBtn = document.getElementById('wizardNextBtn1');
    if (nextBtn) nextBtn.disabled = true;

    var formData = new FormData();
    formData.append('file', fileInput.files[0]);

    fetch('/api/reports/analyze-script', { method: 'POST', body: formData })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (!data.success) {
                statusEl.innerHTML = '\u26A0 Analysis unavailable \u2014 continuing with defaults';
                statusEl.style.color = 'var(--ink-4,#999)';
                _wizardData.analysisResult = {};
                if (nextBtn) nextBtn.disabled = false;
                return;
            }
            _wizardData.analysisResult = data;
            _renderWizardAnalysis(data);
            if (nextBtn) nextBtn.disabled = false;
        })
        .catch(function() {
            statusEl.innerHTML = '\u26A0 Analysis failed \u2014 continuing with defaults';
            statusEl.style.color = 'var(--ink-4,#999)';
            _wizardData.analysisResult = {};
            if (nextBtn) nextBtn.disabled = false;
        });
}

function _renderWizardAnalysis(data) {
    var statusEl = document.getElementById('wizardAnalysisStatus');
    var badge = data.aiAnalyzed
        ? '<span style="background:#eef6f1;color:#1f8a4c;padding:2px 6px;border-radius:3px;font-size:9px;font-weight:600;letter-spacing:0.08em;margin-right:6px;">AI</span>'
        : (data.jarInfo
            ? '<span style="background:var(--accent-2,#eef3fa);color:var(--accent,#2c6fbb);padding:2px 6px;border-radius:3px;font-size:9px;font-weight:600;letter-spacing:0.08em;margin-right:6px;">JAR</span>'
            : '<span style="background:var(--surface-2,#f0f0f0);color:var(--ink-3);padding:2px 6px;border-radius:3px;font-size:9px;font-weight:600;letter-spacing:0.08em;margin-right:6px;">REGEX</span>');

    var envCount = data.envVars ? data.envVars.length : 0;
    var depCount = data.dependencies ? data.dependencies.length : 0;
    var statusText = '\u2713 ' + envCount + ' env var' + (envCount !== 1 ? 's' : '') + ', ' + depCount + ' dep' + (depCount !== 1 ? 's' : '');

    if (data.jarInfo) {
        statusText += ' \u00b7 Main: ' + esc(data.mainClass || 'none');
    }

    statusEl.innerHTML = badge + '<span style="font-size:11px;color:var(--good,#1f8a4c);">' + statusText + '</span>';
    statusEl.style.color = 'var(--good,#1f8a4c)';

    var epSection = document.getElementById('wizardEntryPointSection');
    if (epSection && data.entryPoints && data.entryPoints.length > 1) {
        epSection.style.display = 'block';
        var epHtml = '<div style="padding:10px 12px;background:var(--accent-2,#eef3fa);border:1px solid var(--line,#e0e0e0);border-radius:6px;">';
        epHtml += '<div style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:var(--ink-3);margin-bottom:8px;">Entry point \u2014 which class to run?</div>';
        data.entryPoints.forEach(function(ep, idx) {
            var checked = idx === 0 ? ' checked' : '';
            var isClassOnly = ep.source === 'class-only';
            var srcBadge = isClassOnly
                ? '<span style="font-family:var(--font-mono);font-size:9px;background:#faf3e5;color:#c7801b;padding:1px 5px;border-radius:2px;margin-left:6px;">BYTECODE</span>'
                : '<span style="font-family:var(--font-mono);font-size:9px;background:#eef6f1;color:#1f8a4c;padding:1px 5px;border-radius:2px;margin-left:6px;">SOURCE</span>';
            epHtml += '<label style="display:flex;align-items:flex-start;gap:8px;padding:6px 0;border-bottom:1px solid var(--line,#e0e0e0);cursor:pointer;">' +
                '<input type="radio" name="wizardEntryPoint" value="' + esc(ep.className) + '"' + checked + ' style="margin-top:3px;accent-color:var(--ink,#222);">' +
                '<div>' +
                '<div style="font-family:var(--font-mono);font-size:12px;color:var(--ink);font-weight:500;">' + esc(ep.className) + srcBadge + '</div>' +
                '<div style="font-size:11px;color:var(--ink-3);margin-top:1px;">' + esc(ep.file) +
                (ep.needsConfig === 'true' ? ' \u00b7 <span style="color:var(--warn,#c7801b);">needs config</span>' : '') +
                (ep.description ? ' \u00b7 ' + esc(ep.description) : '') +
                '</div></div></label>';
        });
        epHtml += '</div>';
        epSection.innerHTML = epHtml;
    } else if (epSection && data.entryPoints && data.entryPoints.length === 1) {
        _wizardData.entryPoint = data.entryPoints[0].className;
    }
}

function wizardStep2() {
    var epRadio = document.querySelector('input[name="wizardEntryPoint"]:checked');
    if (epRadio) {
        _wizardData.entryPoint = epRadio.value;
    }

    var data = _wizardData.analysisResult || {};
    var modalDiv = document.querySelector('#uploadScriptModal .report-modal');
    if (!modalDiv) return;

    var supportPrompts = [];
    var selectedEp = null;
    if (data.entryPoints && _wizardData.entryPoint) {
        for (var i = 0; i < data.entryPoints.length; i++) {
            if (data.entryPoints[i].className === _wizardData.entryPoint) { selectedEp = data.entryPoints[i]; break; }
        }
    }

    var needsProps = false;
    var propsLabel = 'Upload properties file';
    if (selectedEp && selectedEp.needsConfig === 'true') { needsProps = true; }
    if (data.configProperties && data.configProperties.length > 0) { needsProps = true; }
    if (data.fileArgs) {
        for (var j = 0; j < data.fileArgs.length; j++) {
            var fa = data.fileArgs[j];
            if (fa.name && fa.name.indexOf('.properties') >= 0) {
                propsLabel = 'Upload ' + fa.name;
                needsProps = true;
            }
        }
    }

    var needsInput = false;
    if (data.fileArgs && data.fileArgs.length > 0) {
        for (var k = 0; k < data.fileArgs.length; k++) {
            var farg = data.fileArgs[k];
            if (farg.name && farg.name.indexOf('.properties') < 0) {
                needsInput = true;
            }
        }
    }

    if (needsProps) {
        supportPrompts.push({ label: propsLabel, argIndex: 0, isProps: true });
    }
    if (needsInput) {
        for (var m = 0; m < data.fileArgs.length; m++) {
            var fi = data.fileArgs[m];
            if (fi.name && fi.name.indexOf('.properties') < 0) {
                supportPrompts.push({ label: fi.label || fi.name || 'Upload input file', argIndex: m, isProps: false });
            }
        }
    }

    var hasPrompts = supportPrompts.length > 0 || (data.jarInfo);

    var bodyHtml = _wizardStepIndicator(2);
    if (!hasPrompts) {
        bodyHtml += '<div style="padding:20px 0;text-align:center;color:var(--ink-3);font-size:13px;">No supporting files detected. You can add some or skip to the next step.</div>';
    } else {
        bodyHtml += '<div style="font-size:12px;color:var(--ink-3);margin-bottom:8px;">Upload any config or input files this script needs.</div>';
    }
    bodyHtml += '<div id="utilSupportFilesContainer"></div>';
    bodyHtml += '<button type="button" onclick="addSupportFileRow()" style="margin-top:6px;font-size:11px;color:#4a6fa5;background:none;border:none;cursor:pointer;padding:0;">+ Add another file</button>';

    modalDiv.innerHTML =
        '<div class="mh">' +
        '  <div class="kicker">Utilities \u00b7 Step 2 of 3</div>' +
        '  <h3>Supporting Files</h3>' +
        '</div>' +
        '<div class="mb" style="flex:1;overflow-y:auto;display:flex;flex-direction:column;gap:10px;">' +
        bodyHtml +
        '</div>' +
        '<div class="mf">' +
        '  <button class="btn" onclick="_wizardBackTo1()">\u2190 Back</button>' +
        '  <button class="btn primary" onclick="wizardStep3()">Next \u2192</button>' +
        '</div>';

    for (var p = 0; p < supportPrompts.length; p++) {
        addSupportFileRow(supportPrompts[p].label, supportPrompts[p].argIndex);
    }
}

function _wizardBackTo1() {
    var existing = document.getElementById('uploadScriptModal');
    if (existing) existing.remove();

    var modal = document.createElement('div');
    modal.id = 'uploadScriptModal';
    modal.className = 'modal-scrim';
    modal.innerHTML =
        '<div class="report-modal" style="max-width:520px;max-height:80vh;overflow-y:auto;display:flex;flex-direction:column;">' +
        '  <div class="mh">' +
        '    <div class="kicker">Utilities</div>' +
        '    <h3>Upload Script</h3>' +
        '  </div>' +
        '  <div class="mb" style="flex:1;overflow-y:auto;display:flex;flex-direction:column;gap:12px;">' +
        _wizardStepIndicator(1) +
        '    <div>' +
        '      <label style="font-size:11px;font-weight:600;color:#666;text-transform:uppercase;">Script File (.py, .java, or .jar)</label><br>' +
        '      <input type="file" id="wizardScriptFile" accept=".py,.java,.jar" style="font-size:12px;margin-top:4px;" onchange="onWizardFileSelected()">' +
        '      <div style="font-size:11px;color:var(--good,#1f8a4c);margin-top:4px;">\u2713 ' + esc(_wizardData.file ? _wizardData.file.name : '') + ' selected</div>' +
        '    </div>' +
        '    <div id="wizardAnalysisStatus" style="display:none;margin-top:2px;font-family:var(--font-mono);font-size:11px;color:var(--accent,#2c6fbb);"></div>' +
        '    <div id="wizardEntryPointSection" style="display:none;"></div>' +
        '  </div>' +
        '  <div class="mf">' +
        '    <button class="btn" onclick="closeUploadScriptModal()">Cancel</button>' +
        '    <button class="btn primary" id="wizardNextBtn1" onclick="wizardStep2()">Next \u2192</button>' +
        '  </div>' +
        '</div>';
    document.body.appendChild(modal);

    modal.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') closeUploadScriptModal();
    });

    if (_wizardData.analysisResult && Object.keys(_wizardData.analysisResult).length > 0) {
        var statusEl = document.getElementById('wizardAnalysisStatus');
        if (statusEl) statusEl.style.display = 'block';
        _renderWizardAnalysis(_wizardData.analysisResult);
    }
}

function wizardStep3() {
    _wizardData._supportFileInputs = [];
    var sfInputs = document.querySelectorAll('.util-support-file');
    for (var i = 0; i < sfInputs.length; i++) {
        if (sfInputs[i].files && sfInputs[i].files.length > 0) {
            _wizardData._supportFileInputs.push(sfInputs[i].files[0]);
        }
    }

    var data = _wizardData.analysisResult || {};
    var modalDiv = document.querySelector('#uploadScriptModal .report-modal');
    if (!modalDiv) return;

    var bodyHtml = _wizardStepIndicator(3);

    bodyHtml += '<div>' +
        '<label style="font-size:11px;font-weight:600;color:#666;text-transform:uppercase;">Name</label><br>' +
        '<input type="text" id="wizardName" placeholder="e.g. Nightly Sync Check" value="' + esc(data.suggestedName || '') + '" style="width:100%;height:36px;border:1px solid #d0d5dd;border-radius:6px;padding:0 10px;font-size:13px;margin-top:4px;">' +
        '</div>';

    bodyHtml += '<div>' +
        '<label style="font-size:11px;font-weight:600;color:#666;text-transform:uppercase;">Description</label><br>' +
        '<textarea id="wizardDesc" rows="2" placeholder="What does this script do?" style="width:100%;border:1px solid #d0d5dd;border-radius:6px;padding:8px 10px;font-size:13px;font-family:inherit;margin-top:4px;resize:vertical;">' + esc(data.suggestedDescription || '') + '</textarea>' +
        '</div>';

    var prefillArgs = data.cliArgs || '';
    bodyHtml += '<div>' +
        '<label style="font-size:11px;font-weight:600;color:#666;text-transform:uppercase;">CLI Arguments</label><br>' +
        '<input type="text" id="wizardCliArgs" placeholder="e.g. --verbose --output report.csv" value="' + esc(prefillArgs) + '" style="width:100%;height:36px;border:1px solid #d0d5dd;border-radius:6px;padding:0 10px;font-size:13px;font-family:var(--font-mono);margin-top:4px;">';

    if (data.detectedFlags && data.detectedFlags.length > 0) {
        bodyHtml += '<div style="margin-top:4px;display:flex;flex-wrap:wrap;gap:4px;">';
        data.detectedFlags.forEach(function(f) {
            bodyHtml += '<span onclick="var ci=document.getElementById(\'wizardCliArgs\');ci.value+=(ci.value?\' \':\'\')+\'' + esc(f) + '\'" style="display:inline-block;padding:2px 8px;background:var(--surface-2,#f0f0f0);border-radius:3px;font-family:var(--font-mono);font-size:10px;color:var(--ink-2);cursor:pointer;border:1px solid var(--line,#e0e0e0);">' + esc(f) + '</span>';
        });
        bodyHtml += '</div>';
    }
    bodyHtml += '</div>';

    bodyHtml += '<div>' +
        '<label style="font-size:11px;font-weight:600;color:#666;text-transform:uppercase;">Environment Variables</label>' +
        '<div id="utilEnvVarsContainer" style="margin-top:6px;"></div>' +
        '<button type="button" onclick="addEnvVarRow(\'\',\'\',false)" style="margin-top:6px;font-size:11px;color:#4a6fa5;background:none;border:none;cursor:pointer;padding:0;">+ Add variable</button>' +
        '</div>';

    bodyHtml += '<div>' +
        '<label style="font-size:11px;font-weight:600;color:#666;text-transform:uppercase;">Dependency JARs</label>' +
        '<div style="font-size:11px;color:var(--ink-3);margin:2px 0 6px;">Library JARs needed on the classpath.</div>' +
        '<div id="utilDepJarsContainer"></div>' +
        '<button type="button" onclick="addDepJarRow()" style="margin-top:6px;font-size:11px;color:#4a6fa5;background:none;border:none;cursor:pointer;padding:0;">+ Add JAR</button>' +
        '</div>';

    modalDiv.innerHTML =
        '<div class="mh">' +
        '  <div class="kicker">Utilities \u00b7 Step 3 of 3</div>' +
        '  <h3>Details & Upload</h3>' +
        '</div>' +
        '<div class="mb" style="flex:1;overflow-y:auto;display:flex;flex-direction:column;gap:12px;">' +
        bodyHtml +
        '</div>' +
        '<div class="mf">' +
        '  <button class="btn" onclick="wizardStep2()">\u2190 Back</button>' +
        '  <button class="btn primary" onclick="submitWizard()">Upload</button>' +
        '</div>';

    if (data.envVars && data.envVars.length > 0) {
        data.envVars.forEach(function(ev) {
            addEnvVarRow(ev.key, ev.defaultValue || '', ev.sensitive);
        });
    }
}

function submitWizard() {
    var nameInput = document.getElementById('wizardName');
    var descInput = document.getElementById('wizardDesc');
    var cliInput = document.getElementById('wizardCliArgs');

    if (!_wizardData.file) {
        showCustomAlert('PLM Toolkit', 'No script file selected. Go back to Step 1.');
        return;
    }
    if (!nameInput || !nameInput.value.trim()) {
        showCustomAlert('PLM Toolkit', 'Please enter a name for the utility.');
        return;
    }

    var envVars = {};
    var keys = document.querySelectorAll('.util-env-key');
    var vals = document.querySelectorAll('.util-env-val');
    for (var i = 0; i < keys.length; i++) {
        var k = keys[i].value.trim();
        var v = vals[i].value.trim();
        if (k) envVars[k] = v;
    }

    var formData = new FormData();
    formData.append('file', _wizardData.file);
    formData.append('name', nameInput.value.trim());
    formData.append('description', (descInput && descInput.value) ? descInput.value.trim() : '');
    formData.append('envVars', JSON.stringify(envVars));
    formData.append('cliArgs', (cliInput && cliInput.value) ? cliInput.value.trim() : '');

    if (_wizardData.entryPoint) {
        formData.append('entryPoint', _wizardData.entryPoint);
    }

    if (_wizardData._supportFileInputs) {
        for (var j = 0; j < _wizardData._supportFileInputs.length; j++) {
            formData.append('supportFiles', _wizardData._supportFileInputs[j]);
        }
    }

    var depJars = document.querySelectorAll('.util-dep-jar');
    for (var d = 0; d < depJars.length; d++) {
        if (depJars[d].files && depJars[d].files.length > 0) {
            formData.append('depJars', depJars[d].files[0]);
        }
    }

    fetch('/api/reports/upload-script', { method: 'POST', body: formData })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                closeUploadScriptModal();
                reportsLoaded = false;
                loadReports();
                showCustomAlert('PLM Toolkit', data.message || 'Utility uploaded.');
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Upload failed.');
            }
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'Upload failed: ' + err.message);
        });
}

// =========================================================================
// Wizard helpers
// =========================================================================

function addEnvVarRow(key, value, sensitive) {
    var container = document.getElementById('utilEnvVarsContainer');
    if (!container) return;
    var row = document.createElement('div');
    row.style.cssText = 'display:flex;gap:6px;align-items:center;margin-bottom:4px;';
    var inputType = sensitive ? 'password' : 'text';
    row.innerHTML =
        '<input type="text" class="util-env-key" placeholder="KEY" value="' + esc(key || '') + '" style="width:160px;height:32px;border:1px solid #d0d5dd;border-radius:4px;padding:0 8px;font-size:12px;font-family:var(--font-mono);">' +
        '<span style="color:#999;">=</span>' +
        '<input type="' + inputType + '" class="util-env-val" placeholder="' + (sensitive ? '(sensitive)' : 'value') + '" value="' + esc(value || '') + '" style="flex:1;height:32px;border:1px solid #d0d5dd;border-radius:4px;padding:0 8px;font-size:12px;font-family:var(--font-mono);">' +
        (sensitive ? '<span style="font-family:var(--font-mono);font-size:9px;color:#c7801b;padding:0 2px;" title="Sensitive value">&#x1f512;</span>' : '') +
        '<button type="button" onclick="this.parentElement.remove()" style="background:none;border:none;color:#dc3545;cursor:pointer;font-size:14px;padding:0 4px;" title="Remove">&times;</button>';
    container.appendChild(row);
}

function addSupportFileRow(label, argIndex) {
    var container = document.getElementById('utilSupportFilesContainer');
    if (!container) return;
    var rowId = 'sf-row-' + Date.now() + '-' + Math.random().toString(36).substr(2, 4);
    var row = document.createElement('div');
    row.id = rowId;
    row.style.cssText = 'margin-bottom:8px;';
    var labelText = label || 'Supporting file';
    var argHint = argIndex !== undefined ? ' <span style="font-family:var(--font-mono);font-size:9px;color:var(--accent);background:var(--accent-2,#eef3fa);padding:1px 4px;border-radius:2px;">arg[' + argIndex + ']</span>' : '';
    row.innerHTML =
        '<div style="display:flex;gap:6px;align-items:center;padding:8px 10px;background:var(--surface-2,#f5f3ed);border:1px solid var(--line,#e0e0e0);border-radius:5px;">' +
        '  <div style="flex:1;min-width:0;">' +
        '    <div style="font-size:11px;color:var(--ink-2);margin-bottom:3px;">' + esc(labelText) + argHint + '</div>' +
        '    <input type="file" class="util-support-file" accept=".properties,.txt,.csv,.xlsx,.xls,.xml,.json,.cfg,.ini,.yaml,.yml" style="font-size:11px;max-width:100%;" onchange="onSupportFileSelected(this, \'' + rowId + '\')">' +
        '  </div>' +
        '  <button type="button" onclick="document.getElementById(\'' + rowId + '\').remove()" style="background:none;border:none;color:#dc3545;cursor:pointer;font-size:14px;padding:0 4px;flex-shrink:0;" title="Remove">&times;</button>' +
        '</div>' +
        '<div id="' + rowId + '-warnings" style="display:none;"></div>';
    container.appendChild(row);
}

function addDepJarRow() {
    var container = document.getElementById('utilDepJarsContainer');
    if (!container) return;
    var row = document.createElement('div');
    row.style.cssText = 'display:flex;gap:6px;align-items:center;margin-bottom:6px;padding:8px 10px;background:var(--accent-2,#eef3fa);border:1px solid var(--line,#e0e0e0);border-radius:5px;';
    row.innerHTML =
        '<div style="flex:1;min-width:0;">' +
        '  <div style="font-size:11px;color:var(--ink-2);margin-bottom:3px;">Library JAR</div>' +
        '  <input type="file" class="util-dep-jar" accept=".jar" style="font-size:11px;max-width:100%;">' +
        '</div>' +
        '<button type="button" onclick="this.parentElement.remove()" style="background:none;border:none;color:#dc3545;cursor:pointer;font-size:14px;padding:0 4px;flex-shrink:0;" title="Remove">&times;</button>';
    container.appendChild(row);
}

function onSupportFileSelected(input, rowId) {
    if (!input.files || !input.files.length) return;
    var file = input.files[0];
    var fname = file.name.toLowerCase();

    if (!fname.endsWith('.properties') && !fname.endsWith('.cfg') && !fname.endsWith('.ini')) return;

    var warningsDiv = document.getElementById(rowId + '-warnings');
    if (!warningsDiv) return;
    warningsDiv.style.display = 'block';
    warningsDiv.innerHTML = '<div style="font-family:var(--font-mono);font-size:10px;color:var(--accent);padding:4px 10px;">\u2699 Validating paths in ' + esc(file.name) + '\u2026</div>';

    var formData = new FormData();
    formData.append('file', file);

    fetch('/api/reports/validate-properties', { method: 'POST', body: formData })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (!data.success || !data.warnings || data.warnings.length === 0) {
                warningsDiv.innerHTML = '<div style="font-family:var(--font-mono);font-size:10px;color:var(--good,#1f8a4c);padding:4px 10px;">\u2713 All paths look good</div>';
                setTimeout(function() { warningsDiv.style.display = 'none'; }, 3000);
                return;
            }
            renderPathWarnings(warningsDiv, data.warnings);
        })
        .catch(function() {
            warningsDiv.style.display = 'none';
        });
}

function renderPathWarnings(container, warnings) {
    container._pathWarnings = warnings;

    var html = '<div style="margin-top:4px;padding:10px 12px;background:#faf3e5;border-left:2px solid #c7801b;border-radius:0 4px 4px 0;">';
    html += '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">';
    html += '<div style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#856404;">\u26A0 ' + warnings.length + ' path warning' + (warnings.length > 1 ? 's' : '') + '</div>';
    html += '<button type="button" onclick="autoFixPaths(this.closest(\'[id$=-warnings]\')||this.closest(\'div[id]\'))" style="font-family:var(--font-mono);font-size:10px;background:var(--ink,#222);color:#fff;border:none;border-radius:4px;padding:4px 10px;cursor:pointer;letter-spacing:0.04em;">Fix all &amp; re-upload \u2192</button>';
    html += '</div>';

    warnings.forEach(function(w, idx) {
        html += '<div style="padding:6px 0;border-bottom:1px solid rgba(199,128,27,0.2);font-size:12px;">';
        html += '<div style="display:flex;align-items:flex-start;gap:6px;">';
        html += '<input type="checkbox" class="path-fix-cb" data-idx="' + idx + '" checked style="margin-top:3px;accent-color:var(--ink,#222);">';
        html += '<div style="flex:1;">';
        html += '<div style="font-family:var(--font-mono);font-size:11px;color:var(--ink);font-weight:500;">' + esc(w.key) + '</div>';
        html += '<div style="font-family:var(--font-mono);font-size:10px;color:#856404;margin-top:2px;">' + esc(w.issue) + '</div>';
        html += '<div style="font-family:var(--font-mono);font-size:10px;color:var(--ink-3);margin-top:1px;"><span style="text-decoration:line-through;">' + esc(w.value) + '</span></div>';
        if (w.suggestion) {
            html += '<div style="font-family:var(--font-mono);font-size:10px;color:var(--good,#1f8a4c);margin-top:1px;">\u2192 ' + esc(w.suggestion) + '</div>';
        }
        html += '</div></div></div>';
    });

    html += '</div>';
    container.innerHTML = html;
}

function autoFixPaths(warningsDiv) {
    if (!warningsDiv || !warningsDiv._pathWarnings) return;
    var warnings = warningsDiv._pathWarnings;

    var checkboxes = warningsDiv.querySelectorAll('.path-fix-cb');
    var fixes = {};
    for (var i = 0; i < checkboxes.length; i++) {
        if (checkboxes[i].checked) {
            var idx = parseInt(checkboxes[i].getAttribute('data-idx'));
            var w = warnings[idx];
            if (w && w.suggestion) {
                fixes[w.key] = w.suggestion;
            }
        }
    }

    if (Object.keys(fixes).length === 0) {
        showCustomAlert('PLM Toolkit', 'No fixes selected.');
        return;
    }

    var fileInput = warningsDiv.previousElementSibling
        ? warningsDiv.previousElementSibling.querySelector('input[type="file"]')
        : null;
    if (!fileInput || !fileInput.files || !fileInput.files.length) {
        showCustomAlert('PLM Toolkit', 'Cannot find the properties file to fix.');
        return;
    }

    var reader = new FileReader();
    reader.onload = function(e) {
        var content = e.target.result;
        var lines = content.split('\n');
        var fixedLines = [];
        var fixCount = 0;

        for (var li = 0; li < lines.length; li++) {
            var line = lines[li];
            var fixed = false;
            for (var fixKey in fixes) {
                var regex = new RegExp('^(\\s*' + fixKey.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*=\\s*)(.*)$');
                var match = line.match(regex);
                if (match) {
                    fixedLines.push(match[1] + fixes[fixKey]);
                    fixCount++;
                    fixed = true;
                    break;
                }
            }
            if (!fixed) fixedLines.push(line);
        }

        var fixedContent = fixedLines.join('\n');
        var fixedBlob = new Blob([fixedContent], { type: 'text/plain' });
        var fixedFile = new File([fixedBlob], fileInput.files[0].name, { type: 'text/plain' });

        var dt = new DataTransfer();
        dt.items.add(fixedFile);
        fileInput.files = dt.files;

        warningsDiv.innerHTML =
            '<div style="margin-top:4px;padding:10px 12px;background:#eef6f1;border-left:2px solid var(--good,#1f8a4c);border-radius:0 4px 4px 0;">' +
            '<div style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:var(--good,#1f8a4c);margin-bottom:4px;">\u2713 Fixed ' + fixCount + ' path' + (fixCount > 1 ? 's' : '') + '</div>' +
            '<div style="font-size:11px;color:var(--ink-2);">The properties file has been updated in memory. It will be saved with the corrected paths when you upload.</div>' +
            '</div>';
        warningsDiv._pathWarnings = null;
    };
    reader.readAsText(fileInput.files[0]);
}

function closeUploadScriptModal() {
    var modal = document.getElementById('uploadScriptModal');
    if (modal) modal.remove();
    _wizardData = {};
}

function downloadRunLog(id) {
    fetch('/api/reports/' + id + '/status')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            var logs = data.logs || [];
            if (logs.length === 0) { showCustomAlert('PLM Toolkit', 'No log output available.'); return; }
            var content = logs.join('\n');
            var blob = new Blob([content], { type: 'text/plain' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = id + '-run.log';
            a.click();
            URL.revokeObjectURL(url);
        })
        .catch(function() { showCustomAlert('PLM Toolkit', 'Could not fetch log.'); });
}

function showEditUtilityModal(id) {
    var report = null;
    reportsList.forEach(function(r) { if (r.id === id) report = r; });
    if (!report) return;

    var existing = document.getElementById('editFilesModal');
    if (existing) existing.remove();

    // Name field (editable)
    var nameHtml =
        '<div style="margin-bottom:10px;">' +
        '<div style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:var(--ink-3);">Name</div>' +
        '<input type="text" id="ef-util-name" value="' + esc(report.name || '') + '" ' +
        'style="width:100%;height:32px;border:1px solid var(--line-2,#d0d5dd);border-radius:4px;padding:0 8px;font-size:13px;font-weight:500;color:var(--ink);margin-top:4px;">' +
        '</div>';

    var scriptHtml =
        '<div style="padding:10px 12px;background:var(--accent-2,#eef3fa);border:1px solid var(--line,#e0e0e0);border-radius:5px;margin-bottom:10px;">' +
        '  <div style="font-size:12px;font-weight:500;color:var(--ink);margin-bottom:2px;">' +
        '    <span style="font-family:var(--font-mono);font-size:10px;background:var(--ink,#222);color:#fff;padding:1px 5px;border-radius:2px;margin-right:6px;">SCRIPT</span>' +
        '    ' + esc(report.filename || 'unknown') + ' <span style="color:var(--good,#1f8a4c);font-size:11px;">\u2713 current</span>' +
        '    <a href="/api/reports/utility/' + esc(id) + '/file/' + esc(report.filename || '') + '" style="font-family:var(--font-mono);font-size:10px;color:var(--accent,#2c6fbb);text-decoration:none;margin-left:6px;">\u2193 download</a>' +
        '  </div>' +
        '  <div style="margin-top:4px;font-size:11px;color:var(--ink-3);">Replace with updated version:</div>' +
        '  <input type="file" id="ef-script-picker" accept=".py,.java,.jar" style="font-size:11px;margin-top:2px;max-width:100%;">' +
        '</div>';

    var filesHtml = '';
    if (report.supportFiles) {
        var files = report.supportFiles.split(',');
        for (var i = 0; i < files.length; i++) {
            var fname = files[i].trim();
            if (!fname) continue;
            var fileType = classifySupportFile(fname);
            var rowId = 'ef-row-' + i;
            filesHtml +=
                '<div id="' + rowId + '" style="margin-bottom:6px;">' +
                '<div style="display:flex;align-items:center;gap:10px;padding:8px 10px;background:var(--surface-2,#f5f3ed);border:1px solid var(--line,#e0e0e0);border-radius:5px;">' +
                '  <div style="flex:1;min-width:0;">' +
                '    <div style="font-size:12px;font-weight:500;color:var(--ink);">' +
                '      <span style="font-family:var(--font-mono);font-size:10px;background:' + (fileType === 'Config' ? 'var(--accent-2,#eef3fa)' : '#fff3cd') + ';color:' + (fileType === 'Config' ? 'var(--accent,#2c6fbb)' : '#856404') + ';padding:1px 5px;border-radius:2px;margin-right:6px;">' + fileType + '</span>' +
                '      ' + esc(fname) + ' <span style="color:var(--good,#1f8a4c);font-size:11px;">\u2713 stored</span>' +
                '      <a href="/api/reports/utility/' + esc(id) + '/file/' + esc(fname) + '" style="font-family:var(--font-mono);font-size:10px;color:var(--accent,#2c6fbb);text-decoration:none;margin-left:6px;">\u2193 download</a>' +
                '    </div>' +
                '    <input type="file" class="ef-file-picker" data-filename="' + esc(fname) + '" style="font-size:11px;margin-top:3px;max-width:100%;" onchange="onSupportFileSelected(this, \'' + rowId + '\')">' +
                '  </div>' +
                '  <button type="button" onclick="removeSupportFile(this,\'' + esc(id) + '\',\'' + esc(fname) + '\')" style="background:none;border:none;color:var(--bad,#b8342b);cursor:pointer;font-size:14px;padding:0 4px;flex-shrink:0;" title="Remove file">&times;</button>' +
                '</div>' +
                '<div id="' + rowId + '-warnings" style="display:none;"></div>' +
                '</div>';
        }
    }

    // Load current env vars from server
    // CLI args section
    var cliArgsHtml =
        '<div style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:var(--ink-3);margin-top:8px;">CLI Arguments</div>' +
        '<div style="font-size:11px;color:var(--ink-4);margin:2px 0 4px;">Command-line flags passed to the script. Clear to remove.</div>' +
        '<input type="text" id="ef-cli-args" value="' + esc(report.cliArgs || '') + '" placeholder="e.g. --verbose --output report.xlsx" ' +
        'style="width:100%;height:32px;border:1px solid var(--line-2,#d0d5dd);border-radius:4px;padding:0 8px;font-size:12px;font-family:var(--font-mono);background:#fff;color:var(--ink);">';

    // JDK 1.8 checkbox (for .jar and .java utilities)
    var javaPathHtml = '';
    var fname = report.filename || '';
    if (fname.endsWith('.jar') || fname.endsWith('.java')) {
        var checked = report.useJdk8 === 'true' || report.useJdk8 === true ? ' checked' : '';
        javaPathHtml =
            '<div style="margin-top:8px;padding:10px 12px;background:var(--surface-2,#f5f3ed);border:1px solid var(--line,#e0e0e0);border-radius:5px;display:flex;align-items:center;gap:8px;">' +
            '<input type="checkbox" id="ef-use-jdk8"' + checked + ' style="accent-color:var(--ink,#222);margin:0;width:16px;height:16px;">' +
            '<label for="ef-use-jdk8" style="cursor:pointer;">' +
            '<div style="font-size:12px;font-weight:500;color:var(--ink);">Use JDK 1.8</div>' +
            '<div style="font-size:10px;color:var(--ink-3);font-family:var(--font-mono);margin-top:1px;">Required for Agile SDK utilities. Uses the server\u2019s configured JDK 1.8 path.</div>' +
            '</label></div>';
    }

    // Dependency JARs section
    var depJarsHtml = '';
    if (report.depJars) {
        var djs = report.depJars.split(',');
        depJarsHtml =
            '<div style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:var(--ink-3);margin-top:8px;">Dependency JARs</div>' +
            '<div style="font-size:11px;color:var(--ink-4);margin:2px 0 6px;">Replace library JARs on the classpath.</div>';
        for (var d = 0; d < djs.length; d++) {
            var djName = djs[d].trim();
            if (!djName) continue;
            depJarsHtml +=
                '<div class="ef-dep-row" data-dep-name="' + esc(djName) + '" style="display:flex;align-items:center;gap:8px;padding:8px 10px;background:var(--accent-2,#eef3fa);border:1px solid var(--line,#e0e0e0);border-radius:5px;margin-bottom:4px;">' +
                '<div style="flex:1;min-width:0;">' +
                '<div style="font-family:var(--font-mono);font-size:11px;color:var(--ink);font-weight:500;">' + esc(djName) + ' <span style="color:var(--good,#1f8a4c);font-size:10px;">\u2713 on classpath</span></div>' +
                '<div style="font-size:10px;color:var(--ink-4);margin-top:2px;">Replace:</div>' +
                '<input type="file" class="ef-dep-jar" data-filename="' + esc(djName) + '" accept=".jar" style="font-size:11px;margin-top:2px;">' +
                '</div>' +
                '<button type="button" onclick="removeDepJar(this,\'' + esc(id) + '\',\'' + esc(djName) + '\')" style="background:none;border:none;color:var(--bad,#b8342b);cursor:pointer;font-size:14px;padding:0 4px;flex-shrink:0;" title="Remove from classpath">&times;</button>' +
                '</div>';
        }
    }
    // Also show "Add dependency JAR" for utilities without any
    depJarsHtml +=
        '<div style="margin-top:4px;">' +
        '<div id="ef-new-dep-jar-row" style="display:none;padding:8px 10px;background:var(--surface-2,#f5f3ed);border:1px solid var(--line,#e0e0e0);border-radius:5px;margin-bottom:4px;">' +
        '<div style="font-family:var(--font-mono);font-size:11px;color:var(--ink);font-weight:500;margin-bottom:3px;">New dependency JAR</div>' +
        '<input type="file" id="ef-new-dep-jar" accept=".jar" style="font-size:11px;">' +
        '</div>' +
        '<a href="#" onclick="document.getElementById(\'ef-new-dep-jar-row\').style.display=\'block\';this.style.display=\'none\';return false;" id="ef-add-dep-link" style="font-family:var(--font-mono);font-size:10px;color:var(--accent,#2c6fbb);text-decoration:none;">+ Add dependency JAR</a>' +
        '</div>';

    var envHtml =
        '<div style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:var(--ink-3);margin-top:8px;">Environment Variables</div>' +
        '<div style="font-size:11px;color:var(--ink-4);margin:2px 0 6px;">Edit values to fix credentials, paths, or settings.</div>' +
        '<div id="ef-env-container"><div style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4);">Loading...</div></div>';

    var modal = document.createElement('div');
    modal.id = 'editFilesModal';
    modal.className = 'modal-scrim';
    // Close on scrim click
    modal.onclick = function(e) { if (e.target === modal) closeEditFilesModal(); };
    var btnRow = '<button class="btn" onclick="closeEditFilesModal()">Cancel</button>' +
        '<button class="btn primary" onclick="submitEditUtility(\'' + esc(id) + '\')">Save changes</button>';
    modal.innerHTML =
        '<div class="report-modal" style="max-width:560px;max-height:80vh;overflow-y:auto;">' +
        '  <div class="mh" style="display:flex;justify-content:space-between;align-items:center;">' +
        '    <div><div class="kicker">Edit utility</div>' +
        '    <h3>' + esc(report.name) + '</h3></div>' +
        '    <div style="display:flex;gap:6px;">' + btnRow + '</div>' +
        '  </div>' +
        '  <div class="mb" style="display:flex;flex-direction:column;gap:8px;">' +
        '    <p style="font-size:12px;color:var(--ink-3);margin:0;">Rename, replace files, or update settings.</p>' +
        nameHtml +
        scriptHtml +
        (filesHtml ? '<div style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:var(--ink-3);margin-top:4px;">Supporting files</div>' + filesHtml : '') +
        cliArgsHtml +
        javaPathHtml +
        depJarsHtml +
        envHtml +
        '  </div>' +
        '  <div class="mf">' + btnRow + '  </div>' +
        '</div>';
    document.body.appendChild(modal);

    modal.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') closeEditFilesModal();
    });

    // Load env vars from server
    fetch('/api/reports/utility/' + id + '/env')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            var container = document.getElementById('ef-env-container');
            if (!container) return;
            if (!data.success || !data.envVars || Object.keys(data.envVars).length === 0) {
                container.innerHTML = '<div style="font-size:11px;color:var(--ink-4);padding:4px 0;">No environment variables configured.</div>';
                return;
            }
            var html = '';
            for (var key in data.envVars) {
                var val = data.envVars[key];
                var isSensitive = key.toUpperCase().indexOf('PASSWORD') >= 0 || key.toUpperCase().indexOf('SECRET') >= 0 ||
                    key.toUpperCase().indexOf('API_KEY') >= 0 || key.toUpperCase().indexOf('TOKEN') >= 0;
                var inputType = isSensitive ? 'password' : 'text';
                html +=
                    '<div style="display:flex;gap:6px;align-items:center;margin-bottom:4px;">' +
                    '<div style="min-width:140px;font-family:var(--font-mono);font-size:11px;color:var(--ink);font-weight:500;">' + esc(key) +
                    (isSensitive ? ' <span style="font-size:10px;">\uD83D\uDD12</span>' : '') + '</div>' +
                    '<input type="' + inputType + '" class="ef-env-input" data-key="' + esc(key) + '" value="' + esc(val) + '" ' +
                    'style="flex:1;height:28px;border:1px solid var(--line-2,#d0d5dd);border-radius:4px;padding:0 8px;font-size:12px;font-family:var(--font-mono);background:#fff;color:var(--ink);">' +
                    '</div>';
            }
            container.innerHTML = html;
            container.setAttribute('data-util-id', id);
        })
        .catch(function() {
            var container = document.getElementById('ef-env-container');
            if (container) container.innerHTML = '<div style="font-size:11px;color:var(--bad);">Failed to load env vars.</div>';
        });
}

function removeSupportFile(btn, utilId, fileName) {
    appConfirm('Remove ' + fileName + '?', { danger: true, okText: 'Remove' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/reports/utility/' + utilId + '/cli-args', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ removeSupportFile: fileName })
        })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                // Remove the row from the UI
                var row = btn.closest('[id^="ef-row-"]');
                if (row) row.remove();
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Failed to remove.');
            }
        })
        .catch(function(err) { showCustomAlert('PLM Toolkit', 'Failed: ' + err.message); });
    });
}

function removeDepJar(btn, utilId, jarName) {
    appConfirm('Remove ' + jarName + ' from the classpath?', { danger: true, okText: 'Remove' }).then(function (innerOk) {
        if (!innerOk) return;
        fetch('/api/reports/utility/' + utilId + '/cli-args', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ removeDepJar: jarName })
        })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                var row = btn.closest('.ef-dep-row');
                if (row) row.remove();
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Failed to remove.');
            }
        })
        .catch(function(err) { showCustomAlert('PLM Toolkit', 'Failed: ' + err.message); });
    });
}

function submitEditUtility(id) {
    var scriptPicker = document.getElementById('ef-script-picker');
    var filePickers = document.querySelectorAll('.ef-file-picker');
    var envInputs = document.querySelectorAll('.ef-env-input');
    var formData = new FormData();
    var hasFiles = false;
    var hasEnvChanges = envInputs.length > 0;

    for (var i = 0; i < filePickers.length; i++) {
        if (filePickers[i].files && filePickers[i].files.length > 0) {
            formData.append('files', filePickers[i].files[0]);
            hasFiles = true;
        }
    }

    // Collect dep JAR replacements — these go to the lib/ subdirectory
    var depJarPickers = document.querySelectorAll('.ef-dep-jar');
    for (var d = 0; d < depJarPickers.length; d++) {
        if (depJarPickers[d].files && depJarPickers[d].files.length > 0) {
            formData.append('depJars', depJarPickers[d].files[0]);
            hasFiles = true;
        }
    }
    // New dep JAR
    var newDepJar = document.getElementById('ef-new-dep-jar');
    if (newDepJar && newDepJar.files && newDepJar.files.length > 0) {
        formData.append('depJars', newDepJar.files[0]);
        hasFiles = true;
    }

    var hasScript = scriptPicker && scriptPicker.files && scriptPicker.files.length > 0;
    if (hasScript) {
        formData.append('scriptFile', scriptPicker.files[0]);
    }

    // Collect all settings changes into sequential saves
    var cliInput = document.getElementById('ef-cli-args');
    var newCliArgs = cliInput ? cliInput.value.trim() : null;
    var nameInput = document.getElementById('ef-util-name');
    var newName = nameInput ? nameInput.value.trim() : '';
    var jdk8Checkbox = document.getElementById('ef-use-jdk8');
    var report = null;
    reportsList.forEach(function(r) { if (r.id === id) report = r; });

    // Build a chain of promises so saves happen sequentially
    var saveChain = Promise.resolve();

    // 1. Save env vars
    if (hasEnvChanges) {
        var envVars = {};
        for (var j = 0; j < envInputs.length; j++) {
            var key = envInputs[j].getAttribute('data-key');
            envVars[key] = envInputs[j].value;
        }
        saveChain = saveChain.then(function() {
            return fetch('/api/reports/utility/' + id + '/env', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(envVars)
            }).then(function(res) { return res.json(); });
        });
    }

    // 2. Save CLI args + name + useJdk8 in one call
    var fieldsToUpdate = {};
    if (newCliArgs !== null) fieldsToUpdate.cliArgs = newCliArgs;
    if (newName && report && newName !== report.name) fieldsToUpdate.name = newName;
    if (jdk8Checkbox) fieldsToUpdate.useJdk8 = jdk8Checkbox.checked ? 'true' : '';

    var hasFieldChanges = Object.keys(fieldsToUpdate).length > 0;
    if (hasFieldChanges) {
        saveChain = saveChain.then(function() {
            return fetch('/api/reports/utility/' + id + '/cli-args', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(fieldsToUpdate)
            }).then(function(res) { return res.json(); });
        });
    }

    if (!hasFiles && !hasScript) {
        if (hasEnvChanges || hasFieldChanges) {
            saveChain.then(function() {
                closeEditFilesModal();
                showCustomAlert('PLM Toolkit', 'Settings saved.');
                reportsLoaded = false;
                loadReports();
            });
            return;
        }
        showCustomAlert('PLM Toolkit', 'No changes to save.');
        return;
    }

    var url = '/api/reports/' + id + '/replace-files';
    if (hasScript) {
        formData.append('files', scriptPicker.files[0]);
    }

    fetch(url, { method: 'POST', body: formData })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            closeEditFilesModal();
            if (data.success) {
                showCustomAlert('PLM Toolkit', data.message || 'Utility updated.');
                reportsLoaded = false;
                loadReports();
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Failed.');
            }
        })
        .catch(function(err) {
            closeEditFilesModal();
            showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
        });
}

// Legacy alias
function showEditFilesModal(id) {
    var report = null;
    reportsList.forEach(function(r) { if (r.id === id) report = r; });
    if (!report || !report.supportFiles) return;
    showEditUtilityModal(id);
}

function closeEditFilesModal() {
    var modal = document.getElementById('editFilesModal');
    if (modal) modal.remove();
}

function submitEditFiles(id) {
    submitEditUtility(id);
}

function confirmDeleteUtility(id, name) {
    appConfirm('Delete utility "' + name + '"? This cannot be undone.', { danger: true, okText: 'Delete' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/reports/utility/' + id, { method: 'DELETE' })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                reportsLoaded = false;
                loadReports();
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Delete failed.');
            }
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'Delete failed: ' + err.message);
        });
    });
}

// ── Email injection toggle ──
var _emailFieldsCache = {};

function checkEmailFields(reportId) {
    if (_emailFieldsCache[reportId] !== undefined) {
        showInjectRow(reportId, _emailFieldsCache[reportId]);
        return;
    }
    fetch('/api/reports/' + reportId + '/email-fields')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var fields = data.fields || [];
            _emailFieldsCache[reportId] = fields;
            showInjectRow(reportId, fields);
        })
        .catch(function() {});
}

function showInjectRow(reportId, fields) {
    var row = document.getElementById('inject-email-row-' + reportId);
    if (!row) return;
    if (fields.length === 0) { row.style.display = 'none'; return; }
    row.style.display = '';
    var detail = document.getElementById('inject-email-detail-' + reportId);
    if (detail) {
        var html = '';
        fields.forEach(function(f) {
            var actionLabel = f.action === 'append'
                ? 'will append your email to: <b>' + esc(f.currentValue) + '</b>'
                : 'will set to your email';
            html += f.key + ' \u2014 ' + actionLabel + '<br>';
        });
        detail.innerHTML = html;
    }
}

function onInjectToggle(reportId) {
    var cb = document.getElementById('inject-email-' + reportId);
    var detail = document.getElementById('inject-email-detail-' + reportId);
    if (cb && cb.checked) {
        if (detail) detail.style.display = '';
        // Check if any fields would replace (not append)
        var fields = _emailFieldsCache[reportId] || [];
        var replaceFields = fields.filter(function(f) { return f.action === 'set'; });
        if (replaceFields.length > 0) {
            // No warning needed for blank fields — they're just being set
        }
    } else {
        if (detail) detail.style.display = 'none';
    }
}

// ── AD Bulk Lookup ──
function showAdBulkLookup() {
    var modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.style.display = 'flex';
    modal.onclick = function(e) { if (e.target === modal) modal.remove(); };
    modal.innerHTML =
        '<div class="modal-content" style="max-width:900px;max-height:90vh;overflow:auto;">' +
        '  <div class="modal-header"><h2>AD Bulk Lookup</h2><button onclick="this.closest(\'.modal-overlay\').remove()" class="modal-close">&times;</button></div>' +
        '  <div class="modal-body">' +
        '    <p style="margin:0 0 12px;color:#555;">Upload a CSV, TXT, or Excel file. The toolkit will resolve each person against Active Directory. <strong>Smart Fill</strong> reads your header row (e.g. <em>User ID, First Name, Department, Job Title</em>), looks up each row in AD, and fills any empty AD-mapped cells \u2014 multi-sheet aware, preserves all your other columns. Use it for access-certification spreadsheets.</p>' +
        '    <div style="display:flex;gap:12px;align-items:center;margin-bottom:16px;flex-wrap:wrap;">' +
        '      <input type="file" id="adLookupFile" accept=".csv,.txt,.xls,.xlsx" style="font-size:13px;">' +
        '      <button onclick="runAdBulkLookup()" class="btn-search" style="height:36px;padding:0 20px;" title="Old mode: assumes login IDs are in column A, returns a fixed table">Lookup (legacy)</button>' +
        '      <button onclick="runAdSmartFill()" style="height:36px;padding:0 20px;background:#1F8A4C;color:#fff;border:none;border-radius:6px;cursor:pointer;font-size:13px;font-weight:600;" title="New mode: reads your header row, fills AD-mapped columns, preserves the rest">\u2728 Smart Fill</button>' +
        '      <button id="adExportBtn" onclick="exportAdLookup()" style="display:none;height:36px;padding:0 16px;background:#1a3a5c;color:#fff;border:none;border-radius:6px;cursor:pointer;font-size:13px;">\u2913 Export Excel</button>' +
        '    </div>' +
        '    <div id="adLookupStatus" style="font-size:13px;color:#888;margin-bottom:8px;"></div>' +
        '    <div id="adLookupResults"></div>' +
        '  </div>' +
        '</div>';
    document.body.appendChild(modal);
}

function runAdBulkLookup() {
    var fileInput = document.getElementById('adLookupFile');
    if (!fileInput.files || !fileInput.files.length) { appAlert('Select a file first'); return; }

    var status = document.getElementById('adLookupStatus');
    status.textContent = 'Looking up users in AD\u2026';
    status.style.color = '#856404';

    var formData = new FormData();
    formData.append('file', fileInput.files[0]);

    fetch('/api/admin/ad-lookup-bulk', { method: 'POST', body: formData })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            window._adLookupData = data;
            var found = data.filter(function(r) { return r.STATUS === 'Found'; }).length;
            var notFound = data.length - found;
            status.innerHTML = '<strong>' + data.length + '</strong> users processed \u2022 <span style="color:#28a745;">' + found + ' found</span>' +
                (notFound > 0 ? ' \u2022 <span style="color:#dc3545;">' + notFound + ' not found</span>' : '');
            status.style.color = '#333';
            document.getElementById('adExportBtn').style.display = '';

            // Render table
            var cols = ['LOGIN_ID', 'DISPLAY_NAME', 'EMAIL', 'DEPARTMENT', 'TITLE', 'MANAGER', 'COUNTRY', 'STATUS'];
            var html = '<div style="overflow-x:auto;max-height:400px;overflow-y:auto;"><table style="border-collapse:collapse;font-size:12px;width:100%;">';
            html += '<tr>';
            cols.forEach(function(c) { html += '<th style="text-align:left;padding:5px 8px;border-bottom:2px solid #ccc;background:#f8f9fa;position:sticky;top:0;font-size:11px;color:#666;">' + c + '</th>'; });
            html += '</tr>';
            data.forEach(function(row) {
                var isNotFound = row.STATUS === 'NOT FOUND';
                html += '<tr style="' + (isNotFound ? 'background:#fdeaea;' : '') + '">';
                cols.forEach(function(c) {
                    var val = row[c] || '';
                    html += '<td style="padding:4px 8px;border-bottom:1px solid #eee;white-space:nowrap;' + (isNotFound && c === 'STATUS' ? 'color:#dc3545;font-weight:600;' : '') + '">' + val + '</td>';
                });
                html += '</tr>';
            });
            html += '</table></div>';
            document.getElementById('adLookupResults').innerHTML = html;
        })
        .catch(function(err) {
            status.textContent = 'Failed: ' + err.message;
            status.style.color = '#dc3545';
        });
}

function runAdSmartFill() {
    var fileInput = document.getElementById('adLookupFile');
    if (!fileInput.files || !fileInput.files.length) { appAlert('Select an Excel file first'); return; }
    var file = fileInput.files[0];
    if (!/\.xlsx?$/i.test(file.name)) {
        appAlert('Smart Fill needs an Excel file (.xlsx or .xls). For text/CSV use the legacy Lookup button.');
        return;
    }
    var status = document.getElementById('adLookupStatus');
    status.textContent = 'Smart-filling AD fields\u2026 multi-sheet processing can take a minute for large files.';
    status.style.color = '#856404';

    var formData = new FormData();
    formData.append('file', file);

    fetch('/api/admin/ad-smart-fill', { method: 'POST', body: formData })
        .then(function(r) {
            if (!r.ok) {
                return r.text().then(function(t) { throw new Error('HTTP ' + r.status + ': ' + (t || 'failed')); });
            }
            return r.blob();
        })
        .then(function(blob) {
            var a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            var base = file.name.replace(/\.xlsx?$/i, '');
            a.download = base + '__filled.xlsx';
            a.click();
            URL.revokeObjectURL(a.href);
            status.innerHTML = '\u2713 Smart-fill complete \u2014 downloaded <strong>' + base + '__filled.xlsx</strong>. Open it to see filled cells; "Not found" appears in red italic for people we couldn\u2019t resolve in AD.';
            status.style.color = '#1F8A4C';
        })
        .catch(function(err) {
            status.textContent = 'Smart Fill failed: ' + err.message;
            status.style.color = '#dc3545';
        });
}

function exportAdLookup() {
    var fileInput = document.getElementById('adLookupFile');
    if (!fileInput.files || !fileInput.files.length) return;

    var btn = document.getElementById('adExportBtn');
    btn.disabled = true; btn.textContent = 'Exporting\u2026';

    var formData = new FormData();
    formData.append('file', fileInput.files[0]);

    fetch('/api/admin/ad-lookup-export', { method: 'POST', body: formData })
        .then(function(r) {
            if (!r.ok) throw new Error('Export failed');
            return r.blob();
        })
        .then(function(blob) {
            var a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = 'AD-Lookup-' + new Date().toISOString().slice(0, 10) + '.xlsx';
            a.click(); URL.revokeObjectURL(a.href);
            btn.textContent = '\u2713 Downloaded';
            setTimeout(function() { btn.textContent = '\u2913 Export Excel'; btn.disabled = false; }, 3000);
        })
        .catch(function() {
            btn.textContent = 'Failed';
            setTimeout(function() { btn.textContent = '\u2913 Export Excel'; btn.disabled = false; }, 3000);
        });
}

// Hook into the report run flow for ad-bulk-lookup
var _origExecuteRunImmediate = executeRunImmediate;
executeRunImmediate = function(id, emailMe, injectMyEmail) {
    if (id === 'ad-bulk-lookup') {
        showAdBulkLookup();
        return;
    }
    _origExecuteRunImmediate(id, emailMe, injectMyEmail);
};
