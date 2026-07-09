// === Data Compare ===
// Ensure Agile field names are loaded for the mapping step
var compareAllAgileFields = [];
function loadCompareAgileFields() {
    if (compareAllAgileFields.length > 0) return;
    // Try from agile.js global first
    if (typeof agileFieldNames !== 'undefined' && agileFieldNames.length > 0) {
        compareAllAgileFields = agileFieldNames.map(function(f) { return f.name; });
        return;
    }
    fetch('/api/agile-lookup/field-names')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            compareAllAgileFields = (data || []).map(function(f) { return f.name; });
        })
        .catch(function() {});
}
loadCompareAgileFields();
var compareSessionId = null;
var compareStep = 1;
var compareHeaders = [];
var comparePreview = [];
var compareTotalRows = 0;
var compareFilteredCount = 0;
var compareMappings = [];
var compareResults = null;
var comparePoller = null;

function initCompare() {
    showCompareStep(1);
}

function showCompareStep(step) {
    compareStep = step;
    for (var i = 1; i <= 5; i++) {
        var el = document.getElementById('compareStep' + i);
        if (el) el.style.display = i === step ? 'block' : 'none';
    }
    // Update step indicators
    for (var i = 1; i <= 5; i++) {
        var ind = document.getElementById('compareInd' + i);
        if (ind) {
            if (i < step) ind.className = 'step-ind done';
            else if (i === step) ind.className = 'step-ind active';
            else ind.className = 'step-ind';
        }
    }
}

// === Step 1: Upload ===
function doCompareUpload() {
    var fileInput = document.getElementById('compareFileInput');
    if (!fileInput.files || !fileInput.files.length) {
        showCustomAlert('PLM Toolkit', 'Please select a file to upload.');
        return;
    }
    var btn = document.getElementById('compareUploadBtn');
    btn.disabled = true;
    btn.textContent = 'Uploading...';

    var formData = new FormData();
    formData.append('file', fileInput.files[0]);

    fetch('/api/compare/upload', { method: 'POST', body: formData })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.textContent = 'Upload & Analyze';
            if (data.error) { showCustomAlert('PLM Toolkit', data.error); return; }
            compareSessionId = data.sessionId;
            compareHeaders = data.headers || [];
            comparePreview = data.preview || [];
            compareTotalRows = data.totalRows || 0;
            renderUploadPreview(data);
            // Build part number column selector
            var sel = document.getElementById('comparePnCol');
            sel.innerHTML = '';
            compareHeaders.forEach(function(h) {
                var opt = document.createElement('option');
                opt.value = h;
                opt.textContent = h;
                sel.appendChild(opt);
            });
            document.getElementById('compareUploadResult').style.display = 'block';
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.textContent = 'Upload & Analyze';
            showCustomAlert('PLM Toolkit', 'Upload failed: ' + err.message);
        });
}

function renderUploadPreview(data) {
    var container = document.getElementById('comparePreview');
    var html = '<p style="font-size:13px; color:#555; margin-bottom:8px;"><strong>' + data.totalRows + '</strong> rows, <strong>' + data.headers.length + '</strong> columns</p>';
    html += '<div style="overflow-x:auto;"><table class="data-table" style="font-size:11px;"><thead><tr>';
    data.headers.forEach(function(h) { html += '<th>' + esc(h) + '</th>'; });
    html += '</tr></thead><tbody>';
    (data.preview || []).forEach(function(row) {
        html += '<tr>';
        data.headers.forEach(function(h) { html += '<td>' + esc(row[h] || '') + '</td>'; });
        html += '</tr>';
    });
    html += '</tbody></table></div>';
    container.innerHTML = html;
}

function goToFilterStep() {
    var pnCol = document.getElementById('comparePnCol').value;
    if (!pnCol) { showCustomAlert('PLM Toolkit', 'Please select the Part Number column.'); return; }
    // Build filter column dropdown
    var filterCol = document.getElementById('compareFilterCol');
    filterCol.innerHTML = '<option value="">(no filter — use all rows)</option>';
    compareHeaders.forEach(function(h) {
        var opt = document.createElement('option');
        opt.value = h;
        opt.textContent = h;
        filterCol.appendChild(opt);
    });
    document.getElementById('compareFilterCount').textContent = compareTotalRows + ' rows (no filter applied)';
    compareFilteredCount = compareTotalRows;
    showCompareStep(2);
}

// === Step 2: Filter ===
function applyCompareFilter() {
    var col = document.getElementById('compareFilterCol').value;
    var val = document.getElementById('compareFilterVal').value.trim();
    if (!col || !val) {
        document.getElementById('compareFilterCount').textContent = compareTotalRows + ' rows (no filter applied)';
        compareFilteredCount = compareTotalRows;
        return;
    }
    fetch('/api/compare/filter', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: compareSessionId, filterColumn: col, filterValue: val })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        compareFilteredCount = data.filteredCount || 0;
        var msg = compareFilteredCount + ' of ' + compareTotalRows + ' rows match filter';
        if (data.capped) msg += ' (capped at 500)';
        document.getElementById('compareFilterCount').textContent = msg;
    });
}

function goToMapStep() {
    if (compareFilteredCount > 500) {
        showCustomAlert('PLM Toolkit', 'Please filter to 500 rows or fewer before proceeding.');
        return;
    }
    // Get mapping suggestions
    document.getElementById('compareMappingLoading').style.display = 'block';
    showCompareStep(3);
    fetch('/api/compare/suggest-mappings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: compareSessionId })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        document.getElementById('compareMappingLoading').style.display = 'none';
        var pnCol = document.getElementById('comparePnCol').value;
        // Exclude the part number column — it's already the key, not a field to compare
        compareMappings = (data.mappings || []).filter(function(m) {
            return m.excelColumn !== pnCol;
        });
        renderMappingTable(compareMappings);
    });
}

// === Step 3: Map Fields ===
function renderMappingTable(mappings) {
    var container = document.getElementById('compareMappingTable');
    var pnCol = document.getElementById('comparePnCol').value;
    var html = '<div style="background:#e8f0fe; padding:8px 14px; border-radius:6px; margin-bottom:12px; font-size:13px;">';
    html += '<strong style="color:#1a3a5c;">Part Number Key:</strong> <code style="background:#d4e8f7; padding:1px 6px; border-radius:3px;">' + esc(pnCol) + '</code> — this column will be used to match items between your file and Agile.';
    html += '</div>';
    html += '<table class="data-table" style="font-size:12px;"><thead><tr>';
    html += '<th>Excel Column</th><th>Action</th><th>Agile Field</th><th>Confidence</th>';
    html += '</tr></thead><tbody>';

    mappings.forEach(function(m, idx) {
        var confColor = m.confidence === 'high' ? '#28a745' : m.confidence === 'medium' ? '#ffc107' : '#6c757d';
        var confLabel = m.confidence || 'none';
        var suggestedField = m.suggestedAgileField || m.suggestedField || '';
        var alternatives = m.alternatives || m.suggestions || [];
        // Default action: compare if there's a suggestion, otherwise include
        var defaultAction = suggestedField ? 'compare' : 'include';

        html += '<tr>';
        html += '<td><strong>' + esc(m.excelColumn) + '</strong></td>';
        html += '<td><select id="compareAction_' + idx + '" onchange="onCompareActionChange(' + idx + ')" style="height:30px; border:1px solid #d0d5dd; border-radius:4px; font-size:12px;">';
        html += '<option value="compare"' + (defaultAction === 'compare' ? ' selected' : '') + '>Compare</option>';
        html += '<option value="include"' + (defaultAction === 'include' ? ' selected' : '') + '>Include (no compare)</option>';
        html += '<option value="skip">Skip</option>';
        html += '</select></td>';
        html += '<td><input id="compareAgile_' + idx + '" list="compareAgileList" value="' + esc(suggestedField) + '" placeholder="Type to search fields..." style="height:30px; border:1px solid #d0d5dd; border-radius:4px; font-size:12px; width:250px; padding:0 8px;"' + (defaultAction !== 'compare' ? ' disabled' : '') + '></td>';
        html += '<td><span style="background:' + confColor + '; color:white; padding:2px 8px; border-radius:3px; font-size:10px;">' + confLabel + '</span></td>';
        html += '</tr>';
    });

    html += '</tbody></table>';

    // Build shared datalist with all Agile field names
    html += '<datalist id="compareAgileList">';
    compareAllAgileFields.forEach(function(name) {
        html += '<option value="' + esc(name) + '">';
    });
    html += '</datalist>';

    container.innerHTML = html;
}

function onCompareActionChange(idx) {
    var action = document.getElementById('compareAction_' + idx).value;
    var agileInput = document.getElementById('compareAgile_' + idx);
    agileInput.disabled = (action !== 'compare');
    if (action !== 'compare') agileInput.value = '';
}

function goToCompareStep() {
    // Collect mappings from UI
    var finalMappings = [];
    compareMappings.forEach(function(m, idx) {
        var action = document.getElementById('compareAction_' + idx).value;
        var agileField = document.getElementById('compareAgile_' + idx).value;
        finalMappings.push({
            excelColumn: m.excelColumn,
            action: action,
            agileField: action === 'compare' ? agileField : null
        });
    });

    // Validate: at least one compare field
    var hasCompare = finalMappings.some(function(m) { return m.action === 'compare' && m.agileField; });
    if (!hasCompare) {
        showCustomAlert('PLM Toolkit', 'Please map at least one field to compare.');
        return;
    }

    showCompareStep(4);
    document.getElementById('compareProgress').textContent = 'Starting comparison...';

    var pnCol = document.getElementById('comparePnCol').value;
    var filterCol = document.getElementById('compareFilterCol').value;
    var filterVal = document.getElementById('compareFilterVal').value.trim();

    fetch('/api/compare/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            sessionId: compareSessionId,
            partNumberColumn: pnCol,
            mappings: finalMappings,
            filterColumn: filterCol || null,
            filterValue: filterVal || null
        })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.status === 'running' || data.success) {
            // No alert — Step 4 spinner is already showing
            startComparePolling();
        } else {
            showCustomAlert('PLM Toolkit', data.error || data.message || 'Failed to start comparison.');
            showCompareStep(3);
        }
    });
}

// === Step 4: Progress ===
function startComparePolling() {
    if (comparePoller) clearInterval(comparePoller);
    comparePoller = setInterval(function() {
        fetch('/api/compare/status?sessionId=' + compareSessionId)
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.status === 'running') {
                    document.getElementById('compareProgress').textContent =
                        'Looking up ' + data.progress + ' of ' + data.total + ' items...';
                    var pct = data.total > 0 ? Math.round(100 * data.progress / data.total) : 0;
                    document.getElementById('compareProgressBar').style.width = pct + '%';
                    document.getElementById('compareProgressPct').textContent = pct + '%';
                } else if (data.status === 'completed') {
                    clearInterval(comparePoller);
                    comparePoller = null;
                    loadCompareResults();
                } else if (data.status === 'failed') {
                    clearInterval(comparePoller);
                    comparePoller = null;
                    document.getElementById('compareProgress').textContent = 'Comparison failed: ' + (data.error || 'Unknown error');
                }
            });
    }, 3000);
}

function loadCompareResults() {
    fetch('/api/compare/results?sessionId=' + compareSessionId)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            compareResults = data;
            showCompareStep(5);
            renderCompareResults(data);
        });
}

// === Step 5: Results ===
function renderCompareResults(data) {
    // Summary
    document.getElementById('compareSummary').innerHTML =
        '<strong>' + (data.totalItems || 0) + '</strong> items compared. ' +
        '<span class="v2-life act" style="font-size:11px;">' + (data.matchCount || 0) + ' match</span> ' +
        '<span class="v2-life eol" style="font-size:11px;">' + (data.differCount || 0) + ' differ</span> ' +
        '<span class="v2-life" style="font-size:11px;">' + (data.notFoundCount || 0) + ' not found</span>';

    renderCompareTable(data.results || [], data.mappings || [], 'all');
}

var compareFilter = 'all';

function filterCompareResults(filter) {
    compareFilter = filter;
    // Update button styles
    document.querySelectorAll('.compare-filter-btn').forEach(function(b) {
        var isActive = b.getAttribute('data-filter') === filter;
        b.style.background = isActive ? 'var(--ink, #222)' : 'var(--surface, white)';
        b.style.color = isActive ? 'white' : 'var(--ink-2, #555)';
        b.style.borderColor = isActive ? 'var(--ink, #222)' : 'var(--line-2, #d0d5dd)';
    });
    renderCompareTable(compareResults.results || [], compareResults.mappings || [], filter);
}

function renderCompareTable(results, mappings, filter) {
    var tbody = document.getElementById('compareResultsBody');
    var thead = document.getElementById('compareResultsHead');
    if (!tbody || !thead) return;

    // Build headers
    var headerHtml = '<tr><th>Part Number</th><th>Status</th>';
    var compareMaps = [];
    var includeMaps = [];
    mappings.forEach(function(m) {
        if (m.action === 'compare' && m.agileField) {
            compareMaps.push(m);
            headerHtml += '<th>' + esc(m.excelColumn) + '<br><span style="font-weight:400; font-size:10px; color:var(--ink-4,#999);">Your file</span></th>';
            headerHtml += '<th>' + esc(m.agileField) + '<br><span style="font-weight:400; font-size:10px; color:var(--ink-4,#999);">Agile</span></th>';
        } else if (m.action === 'include') {
            includeMaps.push(m);
            headerHtml += '<th>' + esc(m.excelColumn) + '</th>';
        }
    });
    headerHtml += '</tr>';
    thead.innerHTML = headerHtml;

    // Build rows
    tbody.innerHTML = '';
    var shown = 0;
    results.forEach(function(row) {
        var status = row.rowStatus || row.status || 'unknown';
        if (filter === 'differences' && status !== 'differs') return;
        if (filter === 'matches' && status !== 'match') return;
        if (filter === 'notfound' && status !== 'notfound') return;

        shown++;
        var tr = document.createElement('tr');
        var statusCls = status === 'match' ? 'v2-life act' : status === 'differs' ? 'v2-life eol' : 'v2-life';
        var statusLabel = status === 'match' ? 'Match' : status === 'differs' ? 'Differs' : status === 'notfound' ? 'Not Found' : status;

        var html = '<td style="font-family:var(--font-mono);font-size:12.5px;font-weight:500;">' + esc(row.partNumber || '') + '</td>';
        html += '<td><span class="' + statusCls + '" style="font-size:10.5px;">' + statusLabel + '</span></td>';

        // Compared fields
        var excelValues = row.excelValues || {};
        var agileValues = row.agileValues || {};
        var statuses = row.statuses || {};
        compareMaps.forEach(function(m) {
            var excelVal = excelValues[m.excelColumn] || '';
            var agileVal = agileValues[m.agileField] || '';
            var cellStatus = statuses[m.agileField] || 'unknown';
            var bg = cellStatus === 'match' ? 'var(--good-bg, #d4edda)' : cellStatus === 'differ' ? 'var(--bad-bg, #f8d7da)' : cellStatus === 'partial' ? 'var(--warn-bg, #fff3cd)' : '#fff';
            html += '<td style="background:' + bg + ';">' + esc(excelVal) + '</td>';
            html += '<td style="background:' + bg + ';">' + esc(agileVal) + '</td>';
        });

        // Included fields
        includeMaps.forEach(function(m) {
            html += '<td>' + esc(excelValues[m.excelColumn] || '') + '</td>';
        });

        tr.innerHTML = html;
        tbody.appendChild(tr);
    });

    document.getElementById('compareShownCount').textContent = shown + ' rows shown';
}

function doCompareExport() {
    guardExport(function() {
        if (!compareSessionId) return;
        window.location.href = '/api/compare/export?sessionId=' + compareSessionId;
    });
}

function resetCompare() {
    compareSessionId = null;
    compareStep = 1;
    compareHeaders = [];
    comparePreview = [];
    compareTotalRows = 0;
    compareFilteredCount = 0;
    compareMappings = [];
    compareResults = null;
    if (comparePoller) { clearInterval(comparePoller); comparePoller = null; }
    document.getElementById('compareFileInput').value = '';
    document.getElementById('compareUploadResult').style.display = 'none';
    showCompareStep(1);
}
