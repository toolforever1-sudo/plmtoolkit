// === Change History ===
var historyResults = [];
// When a search/upload returns more rows than the server sends to the preview, this holds
// {total, limit} so renderHistoryTable can show a "showing first N of M" banner. null = not capped.
var historyPreviewInfo = null;
var historyColumnFilters = {};
var historyCurrentItems = '';
var historySort = { field: 'releaseDate', dir: 'desc' };
var _historyFilterTimer = null;
var historyDateFilter = null; // null = all dates, {} = selected dates
// PT-X: client-side toggle to hide rows with a blank Prior Lifecycle. Useful
// when you only care about real phase transitions (e.g. PPROD → ACT) and want
// to drop intra-phase revs that just released a new ECO at the same phase.
var historyOnlyWithPrior = false;

// Pre-filter selections (PT-OBS-SKU). Empty array = no filter.
var historyLifecycleFilter = [];
var historyChangeTypeFilter = [];
// PT-63: First / Last entry mode. 'all' | 'first' | 'last'. 'all' = no constraint.
var historyEntryMode = 'all';
// PT-64: Release Date range + Part Type chips + relative-date macro.
var historyDateFrom = '';        // ISO yyyy-MM-dd
var historyDateTo   = '';
var historyDateRelative = '';    // '' | 'last7d' | 'last30d' | 'last90d' | 'lastyear'
var historyPartTypeFilter = [];  // ['SKU', 'ECO', ...]
// Quick-pick chip for Part Type. Match is substring-insensitive against
// item_extract.NEW_PART_CLASS so "SKU" finds "A199-Retail Finished Good (SKU)".
// The ECO/MCO/Drawing/Document/Assembly chips were dropped — turns out field users
// only ever click SKU. Custom values go in the free-text input next to the chip.
var HISTORY_PART_TYPE_CHIPS = ['SKU'];
// PT-X: free-text typealong for Part Type. Comma-separated, substring-matched
// against NEW_PART_CLASS like the chip. Merged with chip selections at send time.
var historyPartTypeText = '';
// Last uploaded file (kept in memory so the Enrich button can replay it without
// re-prompting the user for the file picker).
var historyLastUploadFile = null;
var historyLastItemColumn = null;
// Cap on input count above which we hide Lifecycle Journey (per Vikas Singh's
// 2026-05-08 feedback after the 13K-item OOM — the journey is only useful for
// small inputs and explodes the DOM beyond that).
var HISTORY_JOURNEY_MAX_ITEMS = 10;

function doHistorySearch() {
    var items = document.getElementById('historyItemInput').value.trim();
    var fileInput = document.getElementById('historyFileInput');

    if (fileInput.files && fileInput.files.length > 0) {
        doHistoryFileUpload(fileInput.files[0]);
        return;
    }

    // PT-66: items become optional when a Release Date range is set. The server
    // refuses an unbounded scan, so we mirror that gate here to give a clearer
    // error than an empty result set.
    var hasDate = !!(historyDateRelative || historyDateFrom || historyDateTo);
    if (!items && !hasDate) {
        document.getElementById('historyEmptyState').style.display = 'block';
        document.getElementById('historyTableWrapper').style.display = 'none';
        document.getElementById('historyNoResults').style.display = 'none';
        showCustomAlert('PLM Toolkit',
            'Enter item number(s), or set a Release Date range to search across all items.');
        return;
    }

    historyCurrentItems = items;
    showHistoryLoading();

    var qs = '/api/history/search?items=' + encodeURIComponent(items)
           + historyFilterQs();
    fetch(qs)
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            historyResults = data.results || [];
            historyPreviewInfo = data.previewLimited ? { total: data.totalCount, limit: data.previewLimit } : null;
            historyColumnFilters = {};
            document.querySelectorAll('.history-col-filter').forEach(function(i) { i.value = ''; });
            hideHistoryLoading();
            updateHistoryChips(data);
            if (historyResults.length === 0) { showHistoryNoResults(); hideHistoryInsightStrip(); }
            else { renderHistoryTable(); showHistoryTable(); fillHistoryInsightStrip(historyResults, data); }
        })
        .catch(function(err) { hideHistoryLoading(); showCustomAlert('PLM Toolkit', 'Search failed: ' + err.message); });
}

function doHistoryFileUpload(file) {
    var err = validateUploadFile(file);
    if (err) { showCustomAlert('PLM Toolkit', err); return; }
    showHistoryLoading();
    historyLastUploadFile = file;
    historyLastItemColumn = null;  // re-resolve via probe
    var enrichBtn = document.getElementById('historyEnrichBtn');
    if (enrichBtn) enrichBtn.style.display = '';

    smartUpload(file, {
        op: 'QUERY',
        proceed: function(itemCol) {
            var formData = new FormData();
            formData.append('file', file);
            if (itemCol >= 0) formData.append('itemColumn', String(itemCol));
            historyAppendFiltersToFormData(formData);
            // Stash the chosen column so the Enrich button uses the same one without re-prompting.
            historyLastChosenColumn = itemCol >= 0 ? itemCol : null;

            fetch('/api/history/upload', { method: 'POST', body: formData })
                .then(checkAuth)
                .then(function(res) { return res.json(); })
                .then(function(data) {
                    historyResults = data.results || [];
                    historyPreviewInfo = data.previewLimited ? { total: data.totalCount, limit: data.previewLimit } : null;
                    historyCurrentItems = data.items || '';
                    historyColumnFilters = {};
                    document.querySelectorAll('.history-col-filter').forEach(function(i) { i.value = ''; });
                    hideHistoryLoading();
                    updateHistoryChips(data);
                    if (historyResults.length === 0) { showHistoryNoResults(); hideHistoryInsightStrip(); }
                    else { renderHistoryTable(); showHistoryTable(); fillHistoryInsightStrip(historyResults, data); }
                })
                .catch(function(err) { hideHistoryLoading(); showCustomAlert('PLM Toolkit', 'Upload failed: ' + err.message); });
        },
        onTooLarge: function(probe) {
            hideHistoryLoading();
            if (enrichBtn) enrichBtn.style.display = 'none';
            showCustomAlert('PLM Toolkit', probe.message);
        }
    });
}

// Captured at upload time so Enrich can re-use the user's chosen column.
var historyLastChosenColumn = null;

function doHistoryClear() {
    document.getElementById('historyItemInput').value = '';
    document.getElementById('historyFileInput').value = '';
    historyResults = [];
    historyPreviewInfo = null;
    historyCurrentItems = '';
    historyColumnFilters = {};
    if (historyOnlyWithPrior) historyToggleOnlyWithPrior();  // reset toggle + button visual
    historyLifecycleFilter = [];
    historyChangeTypeFilter = [];
    var ctInput = document.getElementById('historyChangeTypeInput');
    if (ctInput) ctInput.value = '';
    // PT-71: also reset Release Date range / relative, Part Type chips, and First/Last entry mode.
    historyDateFrom = '';
    historyDateTo = '';
    historyDateRelative = '';
    var dFrom = document.getElementById('historyDateFrom'); if (dFrom) dFrom.value = '';
    var dTo   = document.getElementById('historyDateTo');   if (dTo)   dTo.value   = '';
    var dRel  = document.getElementById('historyDateRelative'); if (dRel) dRel.value = '';
    historyPartTypeFilter = [];
    historyPartTypeText = '';
    var ptInp = document.getElementById('historyPartTypeInput'); if (ptInp) ptInp.value = '';
    if (typeof renderHistoryPartTypeChips === 'function') renderHistoryPartTypeChips();
    historyEntryMode = 'all';
    var modeAll = document.querySelector('input[name="historyEntryMode"][value="all"]');
    if (modeAll) modeAll.checked = true;
    if (typeof historyRepaintLifecyclePills === 'function') historyRepaintLifecyclePills();
    if (typeof historyUpdatePrefilterHint === 'function') historyUpdatePrefilterHint();
    document.getElementById('historyStatusBar').style.display = 'none';
    document.getElementById('historyTableWrapper').style.display = 'none';
    document.getElementById('historyNoResults').style.display = 'none';
    document.getElementById('historyEmptyState').style.display = 'block';
}

// Shows a "previewing first N of M" banner above the results grid when the server capped the
// preview payload (huge "Show all" searches). The full set is available via Export/Enrich, which
// stream — so we never try to render hundreds of thousands of rows into the DOM (that froze the tab).
function renderHistoryPreviewBanner() {
    var wrap = document.getElementById('historyTableWrapper');
    if (!wrap) return;
    var banner = document.getElementById('historyPreviewBanner');
    if (!historyPreviewInfo) { if (banner) banner.style.display = 'none'; return; }
    if (!banner) {
        banner = document.createElement('div');
        banner.id = 'historyPreviewBanner';
        banner.style.cssText = 'margin:0 0 10px;padding:8px 12px;border-left:4px solid #C7801B;'
            + 'background:#fff8e1;border-radius:0 6px 6px 0;font-size:12.5px;color:#856404;';
        wrap.insertBefore(banner, wrap.firstChild);
    }
    var fmt = function(n) { return (n || 0).toLocaleString(); };
    banner.innerHTML = 'Showing the first <strong>' + fmt(historyPreviewInfo.limit) + '</strong> of <strong>'
        + fmt(historyPreviewInfo.total) + '</strong> matching rows on screen. '
        + 'Use <strong>Export</strong> or <strong>Enrich</strong> to get the complete set — those stream the full data.';
    banner.style.display = '';
}

// === Rendering ===
function renderHistoryTable() {
    var tbody = document.getElementById('historyResultsBody');
    tbody.innerHTML = '';

    renderHistoryPreviewBanner();
    buildHistoryDateCheckboxes();
    var filtered = getFilteredHistoryResults();
    updateHistoryFilterCount(filtered.length, historyResults.length);

    var dateFields = ['releaseDate', 'incorpDate', 'effectiveDate', 'obsoleteDate'];
    var sorted = filtered.slice().sort(function(a, b) {
        // Primary sort: group by item number
        var aItem = (a.itemNumber || '').toLowerCase();
        var bItem = (b.itemNumber || '').toLowerCase();
        if (aItem < bItem) return -1;
        if (aItem > bItem) return 1;

        // Secondary sort: user-selected field
        var aVal = a[historySort.field] || '';
        var bVal = b[historySort.field] || '';
        if (dateFields.indexOf(historySort.field) >= 0) {
            var aDate = aVal ? new Date(aVal) : new Date(0);
            var bDate = bVal ? new Date(bVal) : new Date(0);
            if (isNaN(aDate)) aDate = new Date(0);
            if (isNaN(bDate)) bDate = new Date(0);
            return historySort.dir === 'asc' ? aDate - bDate : bDate - aDate;
        }
        if (typeof aVal === 'string') { aVal = aVal.toLowerCase(); bVal = bVal.toLowerCase(); }
        if (aVal < bVal) return historySort.dir === 'asc' ? -1 : 1;
        if (aVal > bVal) return historySort.dir === 'asc' ? 1 : -1;
        return 0;
    });

    sorted.forEach(function(rec) {
        var tr = document.createElement('tr');
        var lc = (rec.lifecyclePhase || '').replace(/^Items\./, '');
        var lcCls = historyLifecycleClass(lc);
        var prior = (rec.priorPhase || '').replace(/^Items\./, '');
        var priorCls = historyLifecycleClass(prior);
        tr.innerHTML =
            '<td style="font-family:var(--font-mono);font-size:12.5px;font-weight:500;">' + esc(rec.itemNumber) + '</td>' +
            '<td style="text-align:center;font-family:var(--font-mono);">' + esc(rec.rev) + '</td>' +
            '<td>' + (prior ? '<span class="v2-life ' + priorCls + '" style="opacity:0.7;">' + esc(prior) + '</span>'
                            : '<span style="color:#9CA3AF;font-size:11px;">—</span>') + '</td>' +
            '<td>' + (lc ? '<span class="v2-life ' + lcCls + '">' + esc(lc) + '</span>' : '') + '</td>' +
            '<td style="font-family:var(--font-mono);font-size:12px;">' + esc(rec.releaseDate) + '</td>' +
            '<td style="font-family:var(--font-mono);font-size:12.5px;font-weight:500;color:var(--accent);">' + esc(rec.changeNumber) + '</td>' +
            '<td>' + historyChangeTypeBadge(rec.changeType) + '</td>' +
            '<td title="' + esc(rec.changeDescription) + '">' + truncate(rec.changeDescription, 50) + '</td>' +
            '<td>' + historyStatusBadge(rec.changeStatus) + '</td>';
        tbody.appendChild(tr);
    });

    buildLifecycleJourney();
}

function historyStatusBadge(status) {
    if (!status) return '';
    var s = status.toUpperCase();
    var cls = 'v2-life';
    if (s.indexOf('RELEASED') >= 0 || s.indexOf('COMPLETED') >= 0) cls += ' act';
    else if (s.indexOf('CANCEL') >= 0) cls += ' eol';
    else if (s.indexOf('PENDING') >= 0 || s.indexOf('REVIEW') >= 0) cls += ' pend';
    return '<span class="' + cls + '">' + esc(status) + '</span>';
}

function historyLifecycleClass(phase) {
    if (!phase) return '';
    var p = phase.toUpperCase();
    if (p === 'ACT' || p === 'PRODUCTION' || p === 'C-ACT') return 'act';
    if (p === 'RISK') return 'risk';
    if (p.indexOf('OBS') >= 0 || p === 'EOL') return 'eol';
    if (p === 'DEV' || p === 'DEVELOPMENT' || p === 'MKT' || p === 'CPROD' || p.indexOf('PROTO') >= 0) return 'pend';
    return '';
}

function historyChangeTypeBadge(type) {
    if (!type) return '';
    var t = type.toUpperCase();
    var cls = 'v2-life';
    if (t.indexOf('ECO') >= 0) cls += ' act';
    else if (t.indexOf('MCO') >= 0) cls += ' pend';
    else if (t.indexOf('PCR') >= 0) cls += ' pend';
    else if (t.indexOf('AML') >= 0) cls += ' act';
    return '<span class="' + cls + '" style="font-size:10.5px;">' + esc(type) + '</span>';
}

// === Filters ===
function debouncedHistoryFilter() {
    if (_historyFilterTimer) clearTimeout(_historyFilterTimer);
    _historyFilterTimer = setTimeout(function() { applyHistoryFilters(); }, 300);
}

function applyHistoryFilters() {
    historyColumnFilters = {};
    document.querySelectorAll('.history-col-filter').forEach(function(input) {
        var val = input.value.trim().toLowerCase();
        if (val) historyColumnFilters[input.getAttribute('data-col')] = val;
    });
    renderHistoryTable();
}

function clearHistoryFilters() {
    document.querySelectorAll('.history-col-filter').forEach(function(i) { i.value = ''; });
    historyColumnFilters = {};
    historyDateFilter = null;
    renderHistoryTable();
}

function getFilteredHistoryResults() {
    var hasColFilters = Object.keys(historyColumnFilters).length > 0;
    var hasDateFilter = historyDateFilter !== null;
    if (!hasColFilters && !hasDateFilter && !historyOnlyWithPrior) return historyResults;
    return historyResults.filter(function(rec) {
        for (var col in historyColumnFilters) {
            var val = rec[col];
            if (col === 'lifecyclePhase' && val) val = val.replace(/^Items\./, '');
            if (!matchesFilter(val, historyColumnFilters[col])) return false;
        }
        if (hasDateFilter) {
            var dateKey = getHistoryDateKey(rec.releaseDate);
            if (!historyDateFilter[dateKey]) return false;
        }
        if (historyOnlyWithPrior && !(rec.priorPhase && rec.priorPhase.trim())) return false;
        return true;
    });
}

function historyToggleOnlyWithPrior() {
    historyOnlyWithPrior = !historyOnlyWithPrior;
    var btn = document.getElementById('historyOnlyWithPriorBtn');
    if (btn) {
        if (historyOnlyWithPrior) {
            btn.innerHTML = '&#10003; Only with prior';
            btn.style.background = '#1a3a5c';
            btn.style.color = '#fff';
            btn.style.borderColor = '#1a3a5c';
        } else {
            btn.innerHTML = 'Only with prior';
            btn.style.background = '';
            btn.style.color = '';
            btn.style.borderColor = '';
        }
    }
    renderHistoryTable();
}

function getHistoryDateKey(val) {
    if (!val) return '';
    // Extract MM-DD-YYYY from "MM-DD-YYYY hh:mm:ss AM"
    return val.substring(0, 10);
}

function buildHistoryDateCheckboxes() {
    var container = document.getElementById('historyDateCB');
    if (!container) return;
    var dates = {};
    historyResults.forEach(function(rec) {
        var key = getHistoryDateKey(rec.releaseDate);
        if (key) dates[key] = true;
    });
    // Sort by YYYY-MM-DD descending
    var sorted = Object.keys(dates).sort(function(a, b) {
        var toSort = function(d) { var p = d.split('-'); return p.length === 3 ? p[2] + '-' + p[0] + '-' + p[1] : d; };
        return toSort(b).localeCompare(toSort(a));
    });
    container.innerHTML = '';
    sorted.forEach(function(dateStr) {
        var label = document.createElement('label');
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = historyDateFilter === null || (historyDateFilter && historyDateFilter[dateStr]);
        cb.value = dateStr;
        cb.onchange = function() { onHistoryDateChange(); };
        label.appendChild(cb);
        label.appendChild(document.createTextNode(' ' + dateStr));
        container.appendChild(label);
    });
    updateHistoryDateBtn();
}

function toggleHistoryDateFilter() {
    var dd = document.getElementById('historyDateDD');
    dd.style.display = dd.style.display === 'none' ? 'block' : 'none';
}

function onHistoryDateChange() {
    var checkboxes = document.getElementById('historyDateCB').querySelectorAll('input[type="checkbox"]');
    var allChecked = true;
    var selected = {};
    checkboxes.forEach(function(cb) {
        if (cb.checked) selected[cb.value] = true;
        else allChecked = false;
    });
    historyDateFilter = allChecked ? null : selected;
    updateHistoryDateBtn();
    renderHistoryTable();
}

function historyDateSelectAll() {
    historyDateFilter = null;
    document.getElementById('historyDateCB').querySelectorAll('input[type="checkbox"]').forEach(function(cb) { cb.checked = true; });
    updateHistoryDateBtn();
    renderHistoryTable();
}

function historyDateSelectNone() {
    historyDateFilter = {};
    document.getElementById('historyDateCB').querySelectorAll('input[type="checkbox"]').forEach(function(cb) { cb.checked = false; });
    updateHistoryDateBtn();
    renderHistoryTable();
}

function updateHistoryDateBtn() {
    var btn = document.getElementById('historyDateBtn');
    if (!btn) return;
    if (historyDateFilter === null) {
        btn.innerHTML = 'All dates &#9662;';
        btn.style.background = '#2c3e50';
    } else {
        var count = Object.keys(historyDateFilter).length;
        if (count === 0) {
            btn.innerHTML = '0 dates &#9662; (click to restore)';
            btn.style.background = '#dc3545';
        } else {
            btn.innerHTML = count + ' date' + (count !== 1 ? 's' : '') + ' &#9662;';
            btn.style.background = '#2c3e50';
        }
    }
}

// Close date dropdown when clicking outside
document.addEventListener('click', function(e) {
    var dd = document.getElementById('historyDateDD');
    var btn = document.getElementById('historyDateBtn');
    if (dd && dd.style.display !== 'none' && !dd.contains(e.target) && e.target !== btn) {
        dd.style.display = 'none';
    }
});

// === Sort ===
function sortHistoryBy(field) {
    if (historySort.field === field) {
        historySort.dir = historySort.dir === 'asc' ? 'desc' : 'asc';
    } else {
        historySort.field = field;
        historySort.dir = 'asc';
    }
    renderHistoryTable();
    document.querySelectorAll('.history-sort-icon').forEach(function(icon) { icon.textContent = ''; });
    var active = document.querySelector('th[data-history-sort="' + field + '"] .history-sort-icon');
    if (active) active.textContent = historySort.dir === 'asc' ? '\u25B2' : '\u25BC';
}

// === Enrich the uploaded file with history columns ===
function doHistoryEnrich() {
    if (!historyLastUploadFile) {
        showCustomAlert('PLM Toolkit', 'Upload a file first \u2014 enrichment writes the matching history fields back into the original spreadsheet.');
        return;
    }
    var btn = document.getElementById('historyEnrichBtn');
    var origText = btn ? btn.innerHTML : '';
    if (btn) { btn.disabled = true; btn.innerHTML = 'Building...'; }

    var formData = new FormData();
    formData.append('file', historyLastUploadFile);
    if (historyLastChosenColumn != null) formData.append('itemColumn', String(historyLastChosenColumn));
    if (historyLifecycleFilter.length)  formData.append('lifecyclePhases', historyLifecycleFilter.join(','));
    if (historyChangeTypeFilter.length) formData.append('changeTypes', historyChangeTypeFilter.join(','));

    fetch('/api/history/enrich', { method: 'POST', body: formData })
        .then(function(res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            // Try to honor the server-supplied filename.
            var disp = res.headers.get('Content-Disposition') || '';
            var m = disp.match(/filename="?([^";]+)"?/i);
            var filename = m ? m[1] : (historyLastUploadFile.name.replace(/\.[^.]+$/, '') + ' (enriched).xlsx');
            return res.blob().then(function(blob) { return { blob: blob, filename: filename }; });
        })
        .then(function(out) {
            var url = URL.createObjectURL(out.blob);
            var a = document.createElement('a');
            a.href = url; a.download = out.filename;
            document.body.appendChild(a); a.click(); a.remove();
            URL.revokeObjectURL(url);
            if (btn) { btn.disabled = false; btn.innerHTML = origText; }
        })
        .catch(function(err) {
            if (btn) { btn.disabled = false; btn.innerHTML = origText; }
            showCustomAlert('PLM Toolkit', 'Enrichment failed: ' + err.message);
        });
}

// === Pre-filter helpers ===
function historyFilterQs() {
    var parts = [];
    if (historyLifecycleFilter.length)  parts.push('lifecyclePhases=' + encodeURIComponent(historyLifecycleFilter.join(',')));
    if (historyChangeTypeFilter.length) parts.push('changeTypes='     + encodeURIComponent(historyChangeTypeFilter.join(',')));
    if (historyDateRelative)            parts.push('releaseDateRelative=' + encodeURIComponent(historyDateRelative));
    else {
        if (historyDateFrom) parts.push('releaseDateFrom=' + encodeURIComponent(historyDateFrom));
        if (historyDateTo)   parts.push('releaseDateTo='   + encodeURIComponent(historyDateTo));
    }
    var mergedPt = historyMergedPartTypes();
    if (mergedPt.length)                parts.push('partTypes=' + encodeURIComponent(mergedPt.join(',')));
    if (historyEntryMode && historyEntryMode !== 'all') parts.push('entryMode=' + encodeURIComponent(historyEntryMode));
    return parts.length ? '&' + parts.join('&') : '';
}

// Append the same filters to a FormData (used by upload + export + email).
function historyAppendFiltersToFormData(fd) {
    if (historyLifecycleFilter.length)  fd.append('lifecyclePhases', historyLifecycleFilter.join(','));
    if (historyChangeTypeFilter.length) fd.append('changeTypes',     historyChangeTypeFilter.join(','));
    if (historyDateRelative)            fd.append('releaseDateRelative', historyDateRelative);
    else {
        if (historyDateFrom) fd.append('releaseDateFrom', historyDateFrom);
        if (historyDateTo)   fd.append('releaseDateTo',   historyDateTo);
    }
    var mergedPt = historyMergedPartTypes();
    if (mergedPt.length)                fd.append('partTypes', mergedPt.join(','));
    if (historyEntryMode && historyEntryMode !== 'all') fd.append('entryMode', historyEntryMode);
}

// === Export & Email ===
// Both endpoints take items in a form body (not the URL) so 10K+-item bulk uploads
// don't blow past Tomcat's maxHttpHeaderSize and 400 with "Bad Request".
function doHistoryExport() {
    // PT-69: prior gate `if (!historyCurrentItems) return;` silently killed Export
    // for date-range-only searches (PT-66 made items optional when a date range
    // is set, so historyCurrentItems is '' even when results are present).
    if (!historyResults.length) {
        showCustomAlert('PLM Toolkit', 'Run a search first — there are no results to export.');
        return;
    }
    var f = document.createElement('form');
    f.method = 'POST';
    f.action = '/api/history/export';
    f.style.display = 'none';
    var add = function(name, value) {
        var i = document.createElement('input');
        i.type = 'hidden';
        i.name = name;
        i.value = value;
        f.appendChild(i);
    };
    add('items', historyCurrentItems);
    if (historyLifecycleFilter.length)  add('lifecyclePhases', historyLifecycleFilter.join(','));
    if (historyChangeTypeFilter.length) add('changeTypes',     historyChangeTypeFilter.join(','));
    if (historyDateRelative)            add('releaseDateRelative', historyDateRelative);
    else {
        if (historyDateFrom) add('releaseDateFrom', historyDateFrom);
        if (historyDateTo)   add('releaseDateTo',   historyDateTo);
    }
    var mergedPtExport = historyMergedPartTypes();
    if (mergedPtExport.length)          add('partTypes', mergedPtExport.join(','));
    if (historyEntryMode && historyEntryMode !== 'all') add('entryMode', historyEntryMode);
    document.body.appendChild(f);
    f.submit();
    f.remove();
}

function doHistoryEmail() {
    // PT-69: same fix as doHistoryExport — gate on having results, not on items text.
    if (!historyResults.length) {
        showCustomAlert('PLM Toolkit', 'Run a search first — there are no results to email.');
        return;
    }
    var btn = document.getElementById('historyEmailBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Sending...';

    var fd = new FormData();
    fd.append('items', historyCurrentItems);
    historyAppendFiltersToFormData(fd);
    fetch('/api/history/email', { method: 'POST', body: fd })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            if (data.success) {
                btn.innerHTML = '&#10003; Sent!';
                btn.style.background = '#28a745';
                btn.style.borderColor = '#28a745';
                setTimeout(function() { btn.innerHTML = origText; btn.style.background = '#1a3a5c'; btn.style.borderColor = '#1a3a5c'; }, 2000);
            } else {
                btn.innerHTML = origText;
                showCustomAlert('PLM Toolkit', data.message || 'Failed.');
            }
        })
        .catch(function(err) { btn.disabled = false; btn.innerHTML = origText; showCustomAlert('PLM Toolkit', 'Failed: ' + err.message); });
}

// === Chips ===
function updateHistoryChips(data) {
    var chips = document.getElementById('historyChips');
    chips.innerHTML = '';
    if (data.items) {
        var label = data.items.length > 40 ? data.items.substring(0, 40) + '...' : data.items;
        addHistoryChip(chips, 'Items: ' + label);
    }
    var countText = data.totalCount + ' records';
    if (data.queryTimeMs) countText += ' \u2022 ' + data.queryTimeMs + 'ms';
    addHistoryChip(chips, countText, 'chip-count');

    // PT-63/PT-64: surface active pre-filters next to the count so the user can
    // tell at a glance which constraints shaped the result.
    if (data.filterSummary) {
        addHistoryChip(chips, data.filterSummary, 'chip-info');
    }

    // Item-column detection chip (only present after a file upload).
    if (data.itemColumn) {
        var ic = data.itemColumn;
        var methodLabel = ic.method === 'header-match' ? 'header'
                       : ic.method === 'ai-fallback'   ? 'AI'
                       : ic.method === 'user-override' ? 'you picked'
                       : ic.method === 'default-col-a' ? 'default A'
                       : ic.method;
        var chipText = 'Item column: ' + ic.letter + ' (' + (ic.header || '\u2014') + ') \u00b7 via ' + methodLabel;
        addHistoryChip(chips, chipText, 'chip-info');
        // Stash for the enrich button so it knows there was an upload.
        historyLastUploadFile = historyLastUploadFile || null;  // set by doHistoryFileUpload
        historyLastItemColumn = ic;
    } else {
        historyLastItemColumn = null;
    }
    // Dedup chip — only when input rows > unique items.
    if (typeof data.uniqueItems === 'number' && typeof data.inputRows === 'number'
            && data.uniqueItems > 0 && data.inputRows > data.uniqueItems) {
        addHistoryChip(chips, data.uniqueItems + ' unique items from ' + data.inputRows + ' rows', 'chip-info');
    }
    document.getElementById('historyStatusBar').style.display = 'flex';
}

function updateHistoryFilterCount(shown, total) {
    var el = document.getElementById('historyFilterCount');
    if (!el) {
        el = document.createElement('span');
        el.id = 'historyFilterCount';
        el.className = 'chip chip-warn';
        el.style.display = 'none';
        document.getElementById('historyChips').appendChild(el);
    }
    if (shown < total) {
        el.textContent = 'Showing ' + shown + ' of ' + total + ' rows';
        el.style.display = '';
    } else {
        el.style.display = 'none';
    }
}

function addHistoryChip(container, text, extraClass) {
    var span = document.createElement('span');
    span.className = 'chip' + (extraClass ? ' ' + extraClass : '');
    span.textContent = text;
    container.appendChild(span);
}

// === UI State ===
function showHistoryLoading() {
    document.getElementById('historyLoading').style.display = 'block';
    document.getElementById('historyEmptyState').style.display = 'none';
    document.getElementById('historyNoResults').style.display = 'none';
    document.getElementById('historyTableWrapper').style.display = 'none';
    document.getElementById('historyStatusBar').style.display = 'none';
}
function hideHistoryLoading() {
    document.getElementById('historyLoading').style.display = 'none';
}
function showHistoryTable() {
    document.getElementById('historyTableWrapper').style.display = 'block';
    document.getElementById('historyNoResults').style.display = 'none';
    document.getElementById('historyEmptyState').style.display = 'none';
}
function showHistoryNoResults() {
    document.getElementById('historyNoResults').style.display = 'block';
    document.getElementById('historyTableWrapper').style.display = 'none';
    document.getElementById('historyEmptyState').style.display = 'none';
}

// Enter key triggers search
document.addEventListener('DOMContentLoaded', function() {
    setTimeout(function() {
        var input = document.getElementById('historyItemInput');
        if (input) {
            input.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') { e.preventDefault(); doHistorySearch(); }
            });
        }
        renderHistoryLifecyclePills();
    }, 100);
});

// === Lifecycle pre-filter pills ===
// Canonical phase order matches LIFECYCLE_ORDER below; kept inline so this runs
// before the journey block is loaded.
var HISTORY_LIFECYCLE_PILLS = ['DEV','PROTO','PPROD','C-ACT','ACT','MKT','RISK','EOL','OBS','OBS-SKU','DEAD'];

function renderHistoryLifecyclePills() {
    var host = document.getElementById('historyLifecyclePills');
    if (!host) return;
    host.innerHTML = '';
    HISTORY_LIFECYCLE_PILLS.forEach(function(phase) {
        var b = document.createElement('button');
        b.type = 'button';
        b.dataset.phase = phase;
        b.textContent = phase;
        b.style.cssText = 'padding:4px 10px; font-size:12px; font-weight:600; ' +
            'background:#fff; color:#0F1720; border:1px solid #E8E6DF; border-radius:14px; ' +
            'cursor:pointer; transition:background 0.1s, color 0.1s, border-color 0.1s;';
        b.addEventListener('click', function() { historyToggleLifecyclePill(phase); });
        host.appendChild(b);
    });
    historyUpdatePrefilterHint();
}

function historyToggleLifecyclePill(phase) {
    var idx = historyLifecycleFilter.indexOf(phase);
    if (idx >= 0) historyLifecycleFilter.splice(idx, 1);
    else          historyLifecycleFilter.push(phase);
    historyRepaintLifecyclePills();
    historyUpdatePrefilterHint();
}

function historyRepaintLifecyclePills() {
    document.querySelectorAll('#historyLifecyclePills [data-phase]').forEach(function(b) {
        var phase = b.dataset.phase;
        var on = historyLifecycleFilter.indexOf(phase) >= 0;
        b.style.background   = on ? '#1a3a5c' : '#fff';
        b.style.color        = on ? '#fff'    : '#0F1720';
        b.style.borderColor  = on ? '#1a3a5c' : '#E8E6DF';
    });
}

function historyOnChangeTypeInput() {
    var raw = document.getElementById('historyChangeTypeInput').value || '';
    historyChangeTypeFilter = raw.split(',').map(function(s) { return s.trim(); }).filter(Boolean);
    historyUpdatePrefilterHint();
}

// PT-X: typealong handler for the Part Type free-text input. Stores the raw
// string so historyMergedPartTypes() can union it with the chip selection at
// query/export/email time.
function historyOnPartTypeInput() {
    historyPartTypeText = (document.getElementById('historyPartTypeInput') || {}).value || '';
    historyUpdatePrefilterHint();
}

// Union of chip picks + typed values, case-insensitive de-duplicated. Substring
// match against NEW_PART_CLASS happens server-side, so leaving the chip text +
// typed text as-is is correct (no normalization needed).
function historyMergedPartTypes() {
    var typed = (historyPartTypeText || '').split(',')
        .map(function(s) { return s.trim(); })
        .filter(Boolean);
    var seen = {};
    var out = [];
    historyPartTypeFilter.concat(typed).forEach(function(v) {
        var k = v.toLowerCase();
        if (!seen[k]) { seen[k] = true; out.push(v); }
    });
    return out;
}

function historyUpdatePrefilterHint() {
    var active = historyLifecycleFilter.length > 0 || historyChangeTypeFilter.length > 0;
    var on  = document.getElementById('historyPrefilterHint');
    var off = document.getElementById('historyPrefilterHintEmpty');
    if (on)  on.style.display  = active ? '' : 'none';
    if (off) off.style.display = active ? 'none' : '';
}

// === Lifecycle Journey Analysis ===
function formatDuration(totalDays, ongoing) {
    if (totalDays <= 0) return ongoing ? 'current' : '<1 day';
    var years = Math.floor(totalDays / 365);
    var months = Math.floor((totalDays % 365) / 30);
    var days = totalDays % 30;
    var parts = [];
    if (years > 0) parts.push(years + (years === 1 ? ' year' : ' years'));
    if (months > 0) parts.push(months + (months === 1 ? ' month' : ' months'));
    if (days > 0 || parts.length === 0) parts.push(days + (days === 1 ? ' day' : ' days'));
    var text = parts.join(', ') + ' (' + totalDays + 'd)';
    if (ongoing) text += ' — ongoing';
    return text;
}

var LIFECYCLE_ORDER = ['DEV', 'PROTO', 'PPROD', 'C-ACT', 'ACT', 'MKT', 'RISK', 'EOL', 'OBS', 'OBS-SKU', 'DEAD'];
var LIFECYCLE_COLORS = {
    'DEV': '#6c757d', 'PROTO': '#17a2b8', 'PPROD': '#ffc107', 'C-ACT': '#20c997',
    'ACT': '#28a745', 'MKT': '#4a6fa5', 'RISK': '#e67e22', 'EOL': '#fd7e14', 'OBS': '#dc3545',
    'OBS-SKU': '#c82333', 'DEAD': '#343a40'
};

function buildLifecycleJourney() {
    var container = document.getElementById('lifecycleJourney');
    var tableWrapper = document.getElementById('historyTableWrapper');
    if (!container) {
        container = document.createElement('div');
        container.id = 'lifecycleJourney';
        container.style.cssText = 'max-width:1400px; margin:16px auto; padding:0 16px;';
        tableWrapper.parentNode.insertBefore(container, tableWrapper);
    }
    container.innerHTML = '';

    // Group results by item number
    var items = {};
    historyResults.forEach(function(rec) {
        if (!items[rec.itemNumber]) items[rec.itemNumber] = [];
        items[rec.itemNumber].push(rec);
    });

    // Bulk-input gate. The journey panel is helpful for narrow lookups but
    // explodes the DOM (and memory) for large bulk inputs. Hide entirely above
    // the threshold and show a one-line notice instead.
    var itemCount = Object.keys(items).length;
    if (itemCount > HISTORY_JOURNEY_MAX_ITEMS) {
        var notice = document.createElement('div');
        notice.style.cssText = 'background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; ' +
            'padding:10px 14px; font-size:13px; color:#6B7280; margin-bottom:12px;';
        notice.innerHTML = '<strong>Lifecycle Journey hidden</strong> &mdash; ' + itemCount +
            ' items uploaded. Reduce to ' + HISTORY_JOURNEY_MAX_ITEMS + ' or fewer to enable per-item journey analysis.';
        container.appendChild(notice);
        return;
    }

    // PT-36: one outer collapsible parent wrapping all per-SKU sections (was
    // N collapsible boxes). Per-SKU sections inside are always expanded so
    // users don't have to click N times — they get everything visible after a
    // single click on the parent. Default collapsed to preserve PT-23's
    // intent that the journey doesn't push the change-history table down.
    var parentSection = document.createElement('div');
    parentSection.style.cssText = 'background:white; border-radius:8px; padding:16px 24px; margin-bottom:16px; box-shadow:0 1px 3px rgba(0,0,0,0.1);';

    var parentBody = document.createElement('div');
    parentBody.style.display = 'none';

    var parentHeader = document.createElement('h3');
    parentHeader.style.cssText = 'font-size:15px; color:#1a3a5c; margin:0; cursor:pointer; user-select:none; display:flex; align-items:center; gap:8px;';
    var parentChevron = document.createElement('span');
    parentChevron.style.cssText = 'display:inline-block; width:14px; font-size:12px; color:#6B7280;';
    parentChevron.textContent = '\u25B6'; // ▶ collapsed by default
    parentHeader.appendChild(parentChevron);
    var parentLabel = document.createElement('span');
    parentLabel.textContent = 'Lifecycle Journey (' + itemCount + (itemCount === 1 ? ' item' : ' items') + ')';
    parentHeader.appendChild(parentLabel);
    parentHeader.addEventListener('click', (function(b, c) {
        return function() {
            var open = b.style.display !== 'none';
            b.style.display = open ? 'none' : '';
            c.textContent = open ? '\u25B6' : '\u25BC';
        };
    })(parentBody, parentChevron));
    parentSection.appendChild(parentHeader);
    parentSection.appendChild(parentBody);
    container.appendChild(parentSection);

    for (var itemNum in items) {
        var recs = items[itemNum];
        var journey = computeJourney(recs);
        if (journey.length === 0) continue;

        var section = document.createElement('div');
        section.style.cssText = 'background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:14px 18px; margin-top:14px;';

        // PT-36: per-SKU body is always visible. The parent header above is
        // the only collapse point for the entire Lifecycle Journey block.
        var body = document.createElement('div');

        // Per-SKU label — non-clickable subheader (no chevron).
        var header = document.createElement('h4');
        header.style.cssText = 'font-size:13.5px; color:#1a3a5c; margin:0 0 10px 0; font-weight:600;';
        header.textContent = 'Lifecycle Journey: ' + itemNum;
        section.appendChild(header);

        // Visual timeline bar
        var totalDays = 0;
        journey.forEach(function(j) { totalDays += j.days; });
        if (totalDays > 0) {
            var barContainer = document.createElement('div');
            barContainer.style.cssText = 'display:flex; height:28px; border-radius:4px; overflow:hidden; margin:12px 0; border:1px solid #dee2e6;';
            journey.forEach(function(j) {
                var pct = Math.max((j.days / totalDays) * 100, 2);
                var seg = document.createElement('div');
                var bgColor = LIFECYCLE_COLORS[j.phase] || '#adb5bd';
                seg.style.cssText = 'flex:0 0 ' + pct + '%; background:' + bgColor + '; display:flex; align-items:center; justify-content:center; color:white; font-size:10px; font-weight:600; overflow:hidden; white-space:nowrap; padding:0 4px;';
                seg.title = j.phase + ': ' + j.days + ' days (' + j.entered + ' to ' + (j.exited || 'current') + ')';
                if (pct > 8) seg.textContent = j.phase;
                barContainer.appendChild(seg);
            });
            body.appendChild(barContainer);
        }

        // Summary table
        var table = document.createElement('table');
        table.style.cssText = 'width:100%; border-collapse:collapse; font-size:13px;';
        table.innerHTML =
            '<tr style="background:#2c3e50; color:white;">' +
            '<th style="padding:6px 10px; text-align:left;">Phase</th>' +
            '<th style="padding:6px 10px; text-align:left;">Entered</th>' +
            '<th style="padding:6px 10px; text-align:left;">Exited</th>' +
            '<th style="padding:6px 10px; text-align:right;">Duration</th>' +
            '<th style="padding:6px 10px; text-align:left;">Change</th>' +
            '<th style="padding:6px 10px; text-align:center;">Regression?</th>' +
            '</tr>';

        journey.forEach(function(j, idx) {
            var bgColor = idx % 2 === 0 ? '#ffffff' : '#f8f9fa';
            var regStyle = j.regression ? 'background:#fef2f2; color:#dc3545; font-weight:600;' : '';
            var daysText = formatDuration(j.days, !j.exited);
            table.innerHTML +=
                '<tr style="' + (regStyle || 'background:' + bgColor) + '">' +
                '<td style="padding:6px 10px;"><span style="display:inline-block; width:10px; height:10px; border-radius:50%; background:' + (LIFECYCLE_COLORS[j.phase] || '#adb5bd') + '; margin-right:6px;"></span>' + esc(j.phase) + '</td>' +
                '<td style="padding:6px 10px;">' + esc(j.entered) + '</td>' +
                '<td style="padding:6px 10px;">' + (j.exited ? esc(j.exited) : '<em>current</em>') + '</td>' +
                '<td style="padding:6px 10px; text-align:right; font-weight:600;">' + daysText + '</td>' +
                '<td style="padding:6px 10px; font-size:11px;">' + esc(j.changeNumber || '') + '</td>' +
                '<td style="padding:6px 10px; text-align:center;">' + (j.regression ? '<strong style="color:#dc3545;">YES</strong>' : '') + '</td>' +
                '</tr>';
        });
        body.appendChild(table);

        // Summary stats
        var totalTime = 0;
        var regressions = 0;
        var phaseTime = {};
        journey.forEach(function(j) {
            totalTime += j.days;
            if (j.regression) regressions++;
            phaseTime[j.phase] = (phaseTime[j.phase] || 0) + j.days;
        });
        var currentPhase = journey[journey.length - 1].phase;

        var stats = document.createElement('div');
        stats.style.cssText = 'margin-top:10px; padding:10px 14px; background:#f0f4f8; border-radius:6px; font-size:12px; color:#555;';
        var statsHtml = '<strong>Summary:</strong> ' + journey.length + ' phase transitions over ' + formatDuration(totalTime, false).replace(' — ongoing', '') + '. ';
        statsHtml += 'Current phase: <strong style="color:' + (LIFECYCLE_COLORS[currentPhase] || '#333') + ';">' + currentPhase + '</strong>. ';
        if (regressions > 0) {
            statsHtml += '<span style="color:#dc3545; font-weight:600;">' + regressions + ' regression(s) detected.</span> ';
        } else {
            statsHtml += '<span style="color:#28a745;">No regressions — clean progression.</span>';
        }
        // Longest phase
        var longestPhase = '';
        var longestDays = 0;
        for (var p in phaseTime) {
            if (phaseTime[p] > longestDays) { longestDays = phaseTime[p]; longestPhase = p; }
        }
        if (longestPhase) statsHtml += ' Longest phase: <strong>' + longestPhase + '</strong> (' + longestDays + ' days).';
        stats.innerHTML = statsHtml;
        body.appendChild(stats);

        section.appendChild(body);
        parentBody.appendChild(section);
    }
}

function computeJourney(records) {
    // Sort by release date ascending
    var sorted = records.slice().sort(function(a, b) {
        var ad = a.releaseDate ? new Date(a.releaseDate) : new Date(0);
        var bd = b.releaseDate ? new Date(b.releaseDate) : new Date(0);
        return ad - bd;
    });

    var journey = [];
    var lastPhase = '';
    var lastDate = null;

    sorted.forEach(function(rec) {
        var phase = rec.lifecyclePhase || '';
        // Extract the actual phase (after the dot, e.g., "Items.ACT" -> "ACT")
        if (phase.indexOf('.') >= 0) phase = phase.split('.').pop();
        phase = phase.trim();
        if (!phase) return;

        var dateStr = rec.releaseDate ? rec.releaseDate.substring(0, 10) : '';
        var date = rec.releaseDate ? new Date(rec.releaseDate) : null;

        // Skip records with no release date — can't place them in the timeline
        if (!date || isNaN(date.getTime())) return;

        if (phase !== lastPhase) {
            // Close previous phase
            if (journey.length > 0 && date) {
                var prev = journey[journey.length - 1];
                prev.exited = dateStr;
                prev.exitDate = date;
                prev.days = Math.round((date - prev.entryDate) / 86400000);
            }

            journey.push({
                phase: phase,
                entered: dateStr,
                entryDate: date,
                exited: null,
                exitDate: null,
                days: 0,
                changeNumber: rec.changeNumber || '',
                regression: false
            });

            lastPhase = phase;
            lastDate = date;
        }
    });

    // Close final phase with "now"
    if (journey.length > 0) {
        var last = journey[journey.length - 1];
        if (!last.exited && last.entryDate) {
            last.days = Math.round((new Date() - last.entryDate) / 86400000);
        }
    }

    // Second pass: mark regressions — flag the phase that caused the backward move
    // If journey[i] exits to journey[i+1] which is earlier in lifecycle, mark journey[i] as regression
    for (var ri = 0; ri < journey.length - 1; ri++) {
        var curIdx = LIFECYCLE_ORDER.indexOf(journey[ri].phase);
        var nextIdx = LIFECYCLE_ORDER.indexOf(journey[ri + 1].phase);
        if (curIdx >= 0 && nextIdx >= 0 && nextIdx < curIdx) {
            journey[ri].regression = true;
        }
    }

    return journey;
}

// === History Insight Strip ===
function fillHistoryInsightStrip(rows, data) {
    var strip = document.getElementById('historyInsightStrip');
    if (!strip) return;
    strip.style.display = 'grid';

    // Change records
    document.getElementById('historyInsightRows').textContent = rows.length.toLocaleString();

    // Unique items
    var items = {};
    rows.forEach(function(r) { if (r.itemNumber) items[r.itemNumber] = true; });
    var itemCount = Object.keys(items).length;
    document.getElementById('historyInsightItems').textContent = itemCount.toLocaleString();
    document.getElementById('historyInsightItemsSub').textContent = (rows.length / itemCount).toFixed(1) + ' changes per item avg';

    // Change types
    var types = {};
    rows.forEach(function(r) { var t = r.changeType || ''; if (t) types[t] = (types[t] || 0) + 1; });
    var typeCount = Object.keys(types).length;
    var topType = '', topCount = 0;
    for (var t in types) { if (types[t] > topCount) { topType = t; topCount = types[t]; } }
    document.getElementById('historyInsightTypes').textContent = typeCount;
    document.getElementById('historyInsightTypesSub').textContent = topType ? 'most common: ' + topType + ' (' + topCount + ')' : '';

    // Query time
    var ms = data.queryTimeMs || data.elapsed || 0;
    var el = document.getElementById('historyInsightTime');
    if (ms > 1000) {
        el.innerHTML = (ms / 1000).toFixed(1) + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">sec</span>';
    } else {
        el.innerHTML = ms + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">ms</span>';
    }

    document.getElementById('historyInsightRowsSub').textContent = itemCount + ' item' + (itemCount > 1 ? 's' : '') + ' queried';
}

function hideHistoryInsightStrip() {
    var strip = document.getElementById('historyInsightStrip');
    if (strip) strip.style.display = 'none';
}

// === PT-63: First / Last entry radio handler ===
function historyOnEntryModeChange() {
    var picked = document.querySelector('input[name="historyEntryMode"]:checked');
    historyEntryMode = picked ? picked.value : 'all';
    historyUpdatePrefilterHint();
}

// === PT-64: Release Date + Relative + Part Type handlers ===
function historyOnDateChange() {
    historyDateFrom = (document.getElementById('historyDateFrom') || {}).value || '';
    historyDateTo   = (document.getElementById('historyDateTo')   || {}).value || '';
    // Manual date input wins — clear the relative dropdown so the two stay in sync.
    if (historyDateFrom || historyDateTo) {
        historyDateRelative = '';
        var sel = document.getElementById('historyDateRelative');
        if (sel) sel.value = '';
    }
    historyUpdatePrefilterHint();
}

function historyOnDateRelativeChange() {
    var sel = document.getElementById('historyDateRelative');
    historyDateRelative = sel ? sel.value : '';
    if (historyDateRelative) {
        // Resolve to absolute dates for display; the server re-resolves at query
        // time so a scheduled search keeps tracking the rolling window.
        var today = new Date();
        var from = new Date(today);
        if      (historyDateRelative === 'last7d')   from.setDate(today.getDate() - 7);
        else if (historyDateRelative === 'last30d')  from.setDate(today.getDate() - 30);
        else if (historyDateRelative === 'last90d')  from.setDate(today.getDate() - 90);
        else if (historyDateRelative === 'lastyear') from.setFullYear(today.getFullYear() - 1);
        var fromInp = document.getElementById('historyDateFrom');
        var toInp   = document.getElementById('historyDateTo');
        if (fromInp) fromInp.value = from.toISOString().slice(0, 10);
        if (toInp)   toInp.value   = today.toISOString().slice(0, 10);
        historyDateFrom = (fromInp || {}).value || '';
        historyDateTo   = (toInp   || {}).value || '';
    }
    historyUpdatePrefilterHint();
}

function renderHistoryPartTypeChips() {
    var host = document.getElementById('historyPartTypeChips');
    if (!host) return;
    host.innerHTML = '';
    HISTORY_PART_TYPE_CHIPS.forEach(function(t) {
        var b = document.createElement('button');
        var active = historyPartTypeFilter.indexOf(t) >= 0;
        b.textContent = t;
        b.style.cssText = 'padding:4px 12px; font-size:12px; border-radius:14px; cursor:pointer; '
            + 'border:1px solid ' + (active ? '#4a6fa5' : '#E8E6DF') + '; '
            + 'background:' + (active ? '#4a6fa5' : '#fff') + '; '
            + 'color:' + (active ? '#fff' : '#0F1720') + '; '
            + 'font-weight:' + (active ? '600' : '500') + ';';
        b.onclick = function() {
            var idx = historyPartTypeFilter.indexOf(t);
            if (idx >= 0) historyPartTypeFilter.splice(idx, 1);
            else          historyPartTypeFilter.push(t);
            renderHistoryPartTypeChips();
            historyUpdatePrefilterHint();
        };
        host.appendChild(b);
    });
}

function historyUpdatePrefilterHint() {
    var any = historyLifecycleFilter.length || historyChangeTypeFilter.length
            || historyDateFrom || historyDateTo || historyDateRelative
            || historyPartTypeFilter.length || (historyPartTypeText && historyPartTypeText.trim())
            || (historyEntryMode && historyEntryMode !== 'all');
    var on  = document.getElementById('historyPrefilterHint');
    var off = document.getElementById('historyPrefilterHintEmpty');
    if (on)  on.style.display  = any ? '' : 'none';
    if (off) off.style.display = any ? 'none' : '';
}

document.addEventListener('DOMContentLoaded', function() {
    setTimeout(renderHistoryPartTypeChips, 120);
});
