// === BOM Explorer ===
var bomResults = [];

// Derive `rootSku` from each record's path so the on-screen Root SKU column +
// filter work without a backend round-trip. Path is built as `itemNumber + bom_path`
// where bom_path comes from SYS_CONNECT_BY_PATH; the actual separator turns out to
// be either ">" or " > " depending on level, so split on the first ">" and trim.
function decorateBomResults(arr) {
    if (!arr) return arr;
    arr.forEach(function (r) {
        if (r && r.path) {
            var sep = r.path.indexOf('>');
            r.rootSku = (sep < 0 ? r.path : r.path.substring(0, sep)).trim();
        } else {
            r.rootSku = '';
        }
    });
    return arr;
}
var bomSort = { field: 'level', dir: 'asc' };
var bomColumnFilters = {};
var bomCurrentMode = 'explode';
var bomCurrentItems = '';
// True total row count from the server (the JSON payload is capped at ~20K rows;
// the full set lives server-side for export). bomDisplayTruncated = server capped it.
var bomServerTotal = 0;
var bomDisplayTruncated = false;

// === Load BOM status on tab switch ===
function loadBomStatus() {
    fetch('/api/bom/status')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.dataLoaded) {
                document.getElementById('bomDataAsOf').style.display = 'block';
                document.getElementById('bomDataAsOfText').textContent =
                    'BOM data current as of: ' + data.dataAsOf;
            }
        })
        .catch(function() {});
    bomLoadFilterOptions();
}

// === Pre-filters (PT-27) ===
var bomFilterOptionsLoaded = false;
function bomLoadFilterOptions() {
    if (bomFilterOptionsLoaded) return;
    fetch('/api/refdata/bom-filters')
        .then(function(r) { return r.json(); })
        .then(function(d) {
            bomFillSelect('bomFilterLifecycles', d.lifecycles || []);
            bomFillSelect('bomFilterPartTypes', d.partTypes || []);
            bomFilterOptionsLoaded = true;
        })
        .catch(function() {});
}
function bomFillSelect(id, values) {
    var sel = document.getElementById(id);
    if (!sel) return;
    sel.innerHTML = '';
    values.forEach(function(v) {
        var o = document.createElement('option');
        o.value = v; o.textContent = v;
        sel.appendChild(o);
    });
}
function bomFilterToggle(ev) {
    if (ev) ev.preventDefault();
    var p = document.getElementById('bomFilterPanel');
    var t = document.getElementById('bomFilterToggle');
    if (!p || !t) return;
    var open = p.style.display !== 'none';
    p.style.display = open ? 'none' : '';
    t.innerHTML = (open ? '&#9656;' : '&#9662;') + ' Pre-filters <span id="bomFilterSummary" style="color:#6B7280;">'
        + (document.getElementById('bomFilterSummary') ? document.getElementById('bomFilterSummary').innerHTML : '')
        + '</span>';
}
function bomFilterClear() {
    ['bomFilterLifecycles','bomFilterPartTypes'].forEach(function(id) {
        var s = document.getElementById(id);
        if (s) Array.from(s.options).forEach(function(o) { o.selected = false; });
    });
    var pf = document.getElementById('bomFilterPrefixes'); if (pf) pf.value = '';
    var mt = document.getElementById('bomFilterMaxTopLevel'); if (mt) mt.value = '';
    bomUpdateFilterSummary();
}
function bomReadFilters() {
    function picks(id) {
        var s = document.getElementById(id);
        if (!s) return '';
        return Array.from(s.selectedOptions).map(function(o) { return o.value; }).join(',');
    }
    function val(id) {
        var el = document.getElementById(id); return el ? el.value : '';
    }
    return {
        lifecycles: picks('bomFilterLifecycles'),
        lifecyclesMode: val('bomFilterLifecyclesMode'),
        partTypes: picks('bomFilterPartTypes'),
        partTypesMode: val('bomFilterPartTypesMode'),
        prefixes: val('bomFilterPrefixes').trim(),
        prefixesMode: val('bomFilterPrefixesMode'),
        maxTopLevelParents: val('bomFilterMaxTopLevel')
    };
}
function bomFilterQueryString() {
    var f = bomReadFilters();
    var parts = [];
    if (f.lifecycles) { parts.push('lifecycles=' + encodeURIComponent(f.lifecycles));
                        parts.push('lifecyclesMode=' + encodeURIComponent(f.lifecyclesMode)); }
    if (f.partTypes)  { parts.push('partTypes=' + encodeURIComponent(f.partTypes));
                        parts.push('partTypesMode=' + encodeURIComponent(f.partTypesMode)); }
    if (f.prefixes)   { parts.push('prefixes=' + encodeURIComponent(f.prefixes));
                        parts.push('prefixesMode=' + encodeURIComponent(f.prefixesMode)); }
    if (f.maxTopLevelParents && parseInt(f.maxTopLevelParents, 10) > 0) {
        parts.push('maxTopLevelParents=' + encodeURIComponent(f.maxTopLevelParents));
    }
    return parts.join('&');
}
function bomAppendFiltersToForm(form) {
    var f = bomReadFilters();
    function add(k, v) {
        if (v === '' || v == null) return;
        var i = document.createElement('input'); i.type = 'hidden'; i.name = k; i.value = v;
        form.appendChild(i);
    }
    if (f.lifecycles) { add('lifecycles', f.lifecycles); add('lifecyclesMode', f.lifecyclesMode); }
    if (f.partTypes)  { add('partTypes', f.partTypes); add('partTypesMode', f.partTypesMode); }
    if (f.prefixes)   { add('prefixes', f.prefixes); add('prefixesMode', f.prefixesMode); }
    if (f.maxTopLevelParents && parseInt(f.maxTopLevelParents, 10) > 0) add('maxTopLevelParents', f.maxTopLevelParents);
}
function bomAppendFiltersToFormData(fd) {
    var f = bomReadFilters();
    if (f.lifecycles) { fd.append('lifecycles', f.lifecycles); fd.append('lifecyclesMode', f.lifecyclesMode); }
    if (f.partTypes)  { fd.append('partTypes', f.partTypes); fd.append('partTypesMode', f.partTypesMode); }
    if (f.prefixes)   { fd.append('prefixes', f.prefixes); fd.append('prefixesMode', f.prefixesMode); }
    if (f.maxTopLevelParents && parseInt(f.maxTopLevelParents, 10) > 0) fd.append('maxTopLevelParents', f.maxTopLevelParents);
}
function bomUpdateFilterSummary() {
    var f = bomReadFilters();
    var bits = [];
    if (f.lifecycles) bits.push('lifecycle ' + (f.lifecyclesMode === 'exclude' ? '≠ ' : '= ') + f.lifecycles.split(',').length);
    if (f.partTypes)  bits.push('part-type ' + (f.partTypesMode === 'exclude' ? '≠ ' : '= ') + f.partTypes.split(',').length);
    if (f.prefixes)   bits.push('prefix ' + (f.prefixesMode === 'exclude' ? '≠ ' : '= ') + f.prefixes.split(',').length);
    if (f.maxTopLevelParents && parseInt(f.maxTopLevelParents, 10) > 0) bits.push('max top-level ' + f.maxTopLevelParents);
    var summary = bits.length ? '· ' + bits.join(' · ') : '';
    var el = document.getElementById('bomFilterSummary');
    if (el) el.textContent = summary;
}
document.addEventListener('change', function(e) {
    if (e.target && /^bomFilter/.test(e.target.id || '')) bomUpdateFilterSummary();
});
document.addEventListener('input', function(e) {
    if (e.target && /^bomFilter/.test(e.target.id || '')) bomUpdateFilterSummary();
});

// === Explode / Implode ===
function doBomExplode() { doBomSearch('explode'); }
function doBomImplode() { doBomSearch('implode'); }

function doBomSearch(mode) {
    var items = document.getElementById('bomItemInput').value.trim();
    var fileInput = document.getElementById('bomFileInput');
    var maxDepth = document.getElementById('bomMaxDepth').value;

    // File upload takes priority
    if (fileInput.files && fileInput.files.length > 0) {
        doBomFileUpload(fileInput.files[0], mode, maxDepth);
        return;
    }

    if (!items) {
        document.getElementById('bomEmptyState').style.display = 'block';
        document.getElementById('bomTableWrapper').style.display = 'none';
        document.getElementById('bomNoResults').style.display = 'none';
        return;
    }

    // Soft warning for large Where-Used batches. 100+ inputs typically means
    // the user uploaded a file via the paste path; confirm before running,
    // since implode can fan out 1000x. Server hard cap (500) catches anything
    // bigger, but this gives the user a chance to back out gracefully.
    if (mode === 'implode') {
        var distinct = bomCountInputs(items);
        if (distinct >= 100) {
            var msg = 'You\'re running Where-Used on ' + distinct + ' item' + (distinct === 1 ? '' : 's') + '.\n\n'
                + 'A Where-Used batch this large can fan out to hundreds of thousands of parent assemblies, '
                + 'which slows the query and can pressure JVM heap. Continue anyway?\n\n'
                + 'Tip: split into batches of 50-100 items for the snappiest experience.';
            if (window.appConfirm) {
                appConfirm(msg, { title: 'Large Where-Used batch' }).then(function (ok) {
                    if (ok) bomRunSearch(mode, items, maxDepth);
                });
                return;
            }
            if (!confirm(msg)) return;
        }
    }
    bomRunSearch(mode, items, maxDepth);
}

/** Count distinct non-empty inputs in a comma/whitespace-separated list.
 *  Mirrors the server's parseItems() behavior so the soft-warn threshold
 *  + the server hard-cap measure the same thing. */
function bomCountInputs(items) {
    if (!items) return 0;
    var seen = {};
    items.split(/[\s,;]+/).forEach(function (s) {
        var t = s.trim();
        if (t) seen[t] = true;
    });
    return Object.keys(seen).length;
}

function bomRunSearch(mode, items, maxDepth) {
    bomCurrentMode = mode;
    bomCurrentItems = items;
    showBomLoading();

    var fq = bomFilterQueryString();
    var url = '/api/bom/' + mode + '?items=' + encodeURIComponent(items) + '&maxDepth=' + maxDepth + (fq ? '&' + fq : '');
    fetch(url)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            hideBomLoading();
            // Server-side rejections (input-size cap, etc.) come back with
            // {error, errorCode, results:[]}. Surface them with the project's
            // appAlert modal so the user sees the cap message + batching
            // suggestion instead of an empty grid.
            if (data && data.error) {
                if (window.appAlert) appAlert(data.error, { title: 'Query rejected' });
                else showCustomAlert('PLM Toolkit', data.error);
                return;
            }
            bomResults = decorateBomResults(data.results || []);
            bomServerTotal = (typeof data.totalCount === 'number') ? data.totalCount : bomResults.length;
            bomDisplayTruncated = !!data.displayTruncated;
            bomColumnFilters = {};
            document.querySelectorAll('.bom-col-filter').forEach(function(i) { i.value = ''; });
            updateBomChips(data);
            updateBomDataAsOf(data);
            if (bomResults.length === 0) {
                showBomNoResults(data);
                hideBomInsightStrip();
            } else {
                renderBomResults();
                showBomTable();
                fillBomInsightStrip(bomResults, data);
            }
            // Notify user about top-level assemblies in export
            if (data.extraTopLevelCount > 0) {
                showBomExtraTopLevelNotice(data.extraTopLevelCount, data.totalTopLevelCount);
            } else if (data.totalTopLevelCount > 0) {
                showBomTopLevelInfo(data.totalTopLevelCount);
            } else {
                hideBomExtraTopLevelNotice();
            }
        })
        .catch(function(err) {
            hideBomLoading();
            // A result too large to serialize/transfer can still be exported via the
            // streaming CSV. Offer that escape hatch instead of a dead-end error.
            var msg = 'The on-screen view could not load this BOM — it may be too large to display. '
                    + 'Download the complete result as a zipped CSV instead?';
            if (window.appConfirm) {
                appConfirm(msg, { title: 'Result too large for on-screen view', okText: '↓ Download Full CSV (zip)' })
                    .then(function (ok) { if (ok) doBomExportCsv(); });
            } else if (confirm(msg)) {
                doBomExportCsv();
            }
        });
}

// Captured at upload time so the Enrich button reuses the user's chosen column.
var bomLastUploadFile = null;
var bomLastChosenColumn = null;

function doBomFileUpload(file, mode, maxDepth) {
    var err = validateUploadFile(file);
    if (err) { showCustomAlert('PLM Toolkit',err); return; }
    bomCurrentMode = mode;
    bomLastUploadFile = file;
    showBomLoading();

    smartUpload(file, {
        op: 'QUERY',
        proceed: function(itemCol) {
            bomLastChosenColumn = itemCol >= 0 ? itemCol : null;
            doBomUploadXhr(file, mode, maxDepth, itemCol);
        },
        onTooLarge: function(probe) {
            hideBomLoading();
            showCustomAlert('PLM Toolkit', probe.message);
        }
    });
}

function doBomUploadXhr(file, mode, maxDepth, itemCol) {
    var formData = new FormData();
    formData.append('file', file);
    formData.append('mode', mode);
    formData.append('maxDepth', maxDepth);
    if (itemCol >= 0) formData.append('itemColumn', String(itemCol));
    bomAppendFiltersToFormData(formData);

    fetch('/api/bom/upload', { method: 'POST', body: formData })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            hideBomLoading();
            // Server-side rejections (file too big, parse error, or the
            // input-size cap added 2026-06-15) come back with
            // {error, ...}. Surface them as a modal so the user sees the
            // batching suggestion instead of an empty grid.
            if (data && data.error) {
                if (window.appAlert) appAlert(data.error, { title: 'Upload rejected' });
                else showCustomAlert('PLM Toolkit', data.error);
                return;
            }
            bomResults = decorateBomResults(data.results || []);
            bomServerTotal = (typeof data.totalCount === 'number') ? data.totalCount : bomResults.length;
            bomDisplayTruncated = !!data.displayTruncated;
            bomCurrentItems = data.items || '';
            bomColumnFilters = {};
            document.querySelectorAll('.bom-col-filter').forEach(function(i) { i.value = ''; });
            updateBomChips(data);
            updateBomDataAsOf(data);
            if (bomResults.length === 0) { showBomNoResults(data); hideBomInsightStrip(); }
            else { renderBomResults(); showBomTable(); fillBomInsightStrip(bomResults, data); }
            // Show/hide Enrich button — Implode only.
            var eb = document.getElementById('bomEnrichBtn');
            if (eb) eb.style.display = (mode === 'implode' && bomLastUploadFile) ? '' : 'none';
        })
        .catch(function(err) {
            hideBomLoading();
            showCustomAlert('PLM Toolkit','File upload failed: ' + err.message);
        });
}

function doBomClear() {
    document.getElementById('bomItemInput').value = '';
    document.getElementById('bomFileInput').value = '';
    document.getElementById('bomMaxDepth').value = '20';
    bomResults = [];
    bomColumnFilters = {};
    document.querySelectorAll('.bom-col-filter').forEach(function(i) { i.value = ''; });
    document.getElementById('bomStatusBar').style.display = 'none';
    document.getElementById('bomTableWrapper').style.display = 'none';
    document.getElementById('bomNoResults').style.display = 'none';
    document.getElementById('bomEmptyState').style.display = 'block';
}

// === BOM Reload ===
function doBomReload() {
    var link = event.target;
    link.textContent = 'Reloading...';
    fetch('/api/bom/reload', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            link.textContent = 'Reload data';
            if (data.success) {
                document.getElementById('bomDataAsOfText').textContent =
                    'BOM data current as of: ' + data.dataAsOf + ' (just reloaded)';
            } else {
                showCustomAlert('PLM Toolkit','Reload failed.');
            }
        })
        .catch(function() { link.textContent = 'Reload data'; showCustomAlert('PLM Toolkit','Reload failed.'); });
}

// === Rendering ===
function renderBomResults() {
    var tbody = document.getElementById('bomResultsBody');
    tbody.innerHTML = '';

    var filtered = getFilteredBomResults();
    updateBomFilterCount(filtered.length, bomResults.length);

    // Cap rendered rows to prevent browser OOM — full data available via Export Excel
    var MAX_RENDER = 10000;
    var renderCapped = false;

    var sorted = filtered.slice().sort(function(a, b) {
        var aVal = a[bomSort.field];
        var bVal = b[bomSort.field];
        if (aVal === undefined) aVal = '';
        if (bVal === undefined) bVal = '';
        if (typeof aVal === 'string') { aVal = aVal.toLowerCase(); bVal = (bVal + '').toLowerCase(); }
        if (aVal < bVal) return bomSort.dir === 'asc' ? -1 : 1;
        if (aVal > bVal) return bomSort.dir === 'asc' ? 1 : -1;
        return 0;
    });

    if (sorted.length > MAX_RENDER) {
        sorted = sorted.slice(0, MAX_RENDER);
        renderCapped = true;
    }

    // Show cap warning
    var capMsg = document.getElementById('bomRenderCapMsg');
    if (!capMsg) {
        capMsg = document.createElement('div');
        capMsg.id = 'bomRenderCapMsg';
        capMsg.style.cssText = 'padding:8px 16px; font-size:12px; color:#856404; background:#fff3cd; border-radius:4px; margin-bottom:8px; display:none;';
        var wrapper = document.getElementById('bomTableWrapper');
        wrapper.insertBefore(capMsg, wrapper.querySelector('table'));
    }
    // Show the cap notice if we truncated the on-screen render OR the server capped
    // the JSON payload (bomDisplayTruncated). Use the true server total, not the
    // capped client-side count, and steer huge results to Full CSV.
    if (renderCapped || bomDisplayTruncated) {
        var trueTotal = bomDisplayTruncated ? bomServerTotal : filtered.length;
        var shown = Math.min(MAX_RENDER, sorted.length);
        var csvLink = '<a href="#" onclick="doBomExportCsv();return false;" style="font-weight:700; color:#4a6fa5; text-decoration:underline;">&#8595; Full CSV (zip)</a>';
        var tail = bomDisplayTruncated
            ? 'Use <strong>Export Excel</strong> (up to ~1M rows) or ' + csvLink + ' for the complete dataset.'
            : 'Use <strong>Export Excel</strong> or <strong>Email Me</strong> for the complete dataset.';
        capMsg.innerHTML = 'Showing first <strong>' + shown.toLocaleString() + '</strong> of <strong>'
            + trueTotal.toLocaleString() + '</strong> rows on screen. ' + tail;
        capMsg.style.display = 'block';
    } else {
        capMsg.style.display = 'none';
    }

    // Direction-aware columns (PT-22): show only Parent BOM in Where Used (implode),
    // only Child BOM in Explode. Toggle a class on the table itself so the rule applies
    // to every matching cell automatically — including the data cells we're about to
    // append. (The earlier per-element querySelectorAll approach ran *before* the new
    // <td>s existed, leaving the data column visible while the header was hidden, which
    // shifted every column label one to the left.)
    var bomTable = document.querySelector('#bomTableWrapper table');
    if (bomTable) {
        bomTable.classList.toggle('bom-mode-explode', bomCurrentMode === 'explode');
        bomTable.classList.toggle('bom-mode-implode', bomCurrentMode === 'implode');
    }

    sorted.forEach(function(rec) {
        var tr = document.createElement('tr');
        var indent = '';
        for (var i = 0; i < rec.level; i++) indent += '&nbsp;&nbsp;&nbsp;';
        var lvlClass = rec.level === 0 ? 'v2-lvl root' : 'v2-lvl';
        var levelBadge = '<span class="' + lvlClass + '">' + rec.level + '</span>';

        var topBadge = (bomCurrentMode === 'implode' && rec.topLevel) ? ' <span class="v2-life act" style="font-size:9px;">TOP</span>' : '';

        tr.innerHTML =
            '<td>' + indent + levelBadge + '</td>' +
            '<td style="font-family:monospace;font-size:11px;color:#4a6fa5;">' + esc(rec.rootSku || '') + '</td>' +
            '<td class="bom-col-parent">' + esc(rec.parent) + topBadge + '</td>' +
            '<td class="bom-col-component"><strong>' + esc(rec.component) + '</strong></td>' +
            '<td>' + esc(rec.quantity) + '</td>' +
            '<td title="' + esc(rec.description) + '">' + truncate(rec.description, 50) + '</td>' +
            '<td>' + esc(rec.itemType) + '</td>' +
            '<td>' + bomStatusBadge(rec.status) + '</td>' +
            '<td>' + esc(rec.rev) + '</td>' +
            '<td>' + esc(rec.lifecyclePhase) + '</td>' +
            '<td>' + esc(rec.productLine) + '</td>' +
            '<td style="font-size:11px;">' + esc(rec.subcontractors) + '</td>' +
            '<td style="font-size:11px;">' + esc(rec.actualBuildPlant) + '</td>' +
            '<td style="font-size:11px;">' + esc(rec.refDesignator) + '</td>' +
            '<td>' + esc(rec.findNumber) + '</td>' +
            '<td style="font-size:11px;" title="' + esc(rec.path) + '">' + truncate(rec.path, 40) + '</td>';
        tbody.appendChild(tr);
    });
}

function bomStatusBadge(status) {
    if (!status) return '';
    var cls = 'v2-life';
    var s = status.toUpperCase();
    if (s === 'ACT' || s === 'C-ACT') cls += ' act';
    else if (s === 'OBS' || s.indexOf('OBS') >= 0 || s === 'EOL') cls += ' eol';
    else if (s === 'CPROD' || s === 'PEND' || s === 'DEV' || s === 'MKT') cls += ' pend';
    return '<span class="' + cls + '">' + esc(status) + '</span>';
}

// === BOM Column Filters ===
var _bomFilterTimer = null;
function debouncedBomFilter() {
    if (_bomFilterTimer) clearTimeout(_bomFilterTimer);
    _bomFilterTimer = setTimeout(function() { applyBomFilters(); }, 300);
}

function applyBomFilters() {
    bomColumnFilters = {};
    document.querySelectorAll('.bom-col-filter').forEach(function(input) {
        var val = input.value.trim().toLowerCase();
        if (val) bomColumnFilters[input.getAttribute('data-col')] = val;
    });
    renderBomResults();
}

function clearBomFilters() {
    document.querySelectorAll('.bom-col-filter').forEach(function(input) { input.value = ''; });
    bomColumnFilters = {};
    renderBomResults();
}

function getFilteredBomResults() {
    if (Object.keys(bomColumnFilters).length === 0) return bomResults;
    return bomResults.filter(function(rec) {
        for (var col in bomColumnFilters) {
            if (!matchesFilter(rec[col], bomColumnFilters[col])) return false;
        }
        return true;
    });
}

// === BOM Sort ===
document.addEventListener('DOMContentLoaded', function() {
    // Setup BOM sortable headers (delayed to ensure DOM is ready)
    setTimeout(function() {
        document.querySelectorAll('th[data-bom-sort]').forEach(function(th) {
            th.style.cursor = 'pointer';
            th.addEventListener('click', function() {
                var field = th.getAttribute('data-bom-sort');
                if (bomSort.field === field) {
                    bomSort.dir = bomSort.dir === 'asc' ? 'desc' : 'asc';
                } else {
                    bomSort.field = field;
                    bomSort.dir = 'asc';
                }
                renderBomResults();
                document.querySelectorAll('.bom-sort-icon').forEach(function(icon) { icon.textContent = ''; });
                var active = th.querySelector('.bom-sort-icon');
                if (active) active.textContent = bomSort.dir === 'asc' ? '\u25B2' : '\u25BC';
            });
        });
    }, 100);

    // Enter key in BOM input triggers explode
    setTimeout(function() {
        var bomInput = document.getElementById('bomItemInput');
        if (bomInput) {
            bomInput.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') { e.preventDefault(); doBomExplode(); }
            });
        }
    }, 100);
});

// === BOM Export & Email ===
// Both endpoints take items in a form body (not the URL) so 1K+-item bulk uploads
// don't blow past Tomcat's maxHttpHeaderSize and 400 with "Bad Request".
function doBomExport() {
    guardExport(function() {
        if (!bomCurrentItems) return;
        var maxDepth = document.getElementById('bomMaxDepth').value;
        // Build the form body as URLSearchParams so we can fetch() it and
        // intercept JSON error responses (the server returns a 400 with
        // {errorCode:'EXCEL_ROW_LIMIT',...} when a BOM exceeds Excel's
        // 1,048,576-row cap — see BomExcelExportService.EXCEL_MAX_ROW_COUNT).
        var body = new URLSearchParams();
        body.append('items', bomCurrentItems);
        body.append('mode', bomCurrentMode);
        body.append('maxDepth', maxDepth);
        bomAppendFiltersToParams(body);
        fetch('/api/bom/export', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: body.toString()
        }).then(function (resp) {
            // 400 with JSON body = pre-flight rejection (row-count cap, etc.).
            // Show an inline alert with the server's suggested next step
            // instead of streaming an error page into a new tab.
            var ct = (resp.headers.get('Content-Type') || '').toLowerCase();
            if (!resp.ok && ct.indexOf('application/json') >= 0) {
                return resp.json().then(function (err) {
                    var msg = (err && err.message) || 'BOM export rejected by the server.';
                    if (window.appAlert) appAlert(msg, { title: 'Excel row limit' });
                    else alert(msg);
                });
            }
            if (!resp.ok) {
                var fallback = 'BOM export failed (' + resp.status + ' ' + resp.statusText + ').';
                if (window.appAlert) appAlert(fallback, { title: 'Export failed' });
                else alert(fallback);
                return;
            }
            // Stream the XLSX bytes to a blob + trigger a download.
            var cd = resp.headers.get('Content-Disposition') || '';
            var m = cd.match(/filename="?([^";]+)"?/i);
            var filename = m ? m[1] : 'BOM-export.xlsx';
            return resp.blob().then(function (blob) {
                var url = URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.href = url; a.download = filename;
                document.body.appendChild(a); a.click(); a.remove();
                setTimeout(function () { URL.revokeObjectURL(url); }, 1500);
            });
        }).catch(function (e) {
            var msg = 'BOM export failed: ' + (e && e.message ? e.message : 'network error');
            if (window.appAlert) appAlert(msg, { title: 'Export failed' });
            else alert(msg);
        });
    });
}

/** Full path-expanded explode/implode as a zipped CSV — the escape hatch for
 *  results that overflow Excel's 1,048,576-row sheet. Downloads via a hidden-iframe
 *  form POST so the browser streams the (potentially multi-GB) zip straight to disk
 *  instead of buffering a blob in page memory. */
function doBomExportCsv() {
    if (!bomCurrentItems) return;
    var maxDepth = document.getElementById('bomMaxDepth').value;
    var iframe = document.getElementById('bomCsvDlFrame');
    if (!iframe) {
        iframe = document.createElement('iframe');
        iframe.id = 'bomCsvDlFrame';
        iframe.name = 'bomCsvDlFrame';
        iframe.style.display = 'none';
        document.body.appendChild(iframe);
    }
    var form = document.createElement('form');
    form.method = 'POST';
    form.action = '/api/bom/export-csv';
    form.target = 'bomCsvDlFrame';
    function add(n, v) {
        var i = document.createElement('input');
        i.type = 'hidden'; i.name = n; i.value = v; form.appendChild(i);
    }
    add('items', bomCurrentItems);
    add('mode', bomCurrentMode);
    add('maxDepth', maxDepth);
    document.body.appendChild(form);
    form.submit();
    setTimeout(function () { form.remove(); }, 3000);
    if (window.appAlert) {
        appAlert('Preparing your full CSV (zipped). The download starts automatically once the server finishes streaming — a very large explosion can take a minute or two.',
                 { title: 'Full CSV export' });
    }
}

/** Mirror of {@link bomAppendFiltersToForm} / {@link bomAppendFiltersToFormData}
 *  for a URLSearchParams instance. Uses bomReadFilters() so the wire shape
 *  matches the other two append helpers exactly. */
function bomAppendFiltersToParams(params) {
    var f = bomReadFilters();
    if (f.lifecycles) { params.append('lifecycles', f.lifecycles); params.append('lifecyclesMode', f.lifecyclesMode); }
    if (f.partTypes)  { params.append('partTypes', f.partTypes); params.append('partTypesMode', f.partTypesMode); }
    if (f.prefixes)   { params.append('prefixes', f.prefixes); params.append('prefixesMode', f.prefixesMode); }
    if (f.maxTopLevelParents && parseInt(f.maxTopLevelParents, 10) > 0) {
        params.append('maxTopLevelParents', f.maxTopLevelParents);
    }
}

function doBomEmail() {
    if (!bomCurrentItems) return;
    var btn = document.getElementById('bomEmailBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Sending...';
    var maxDepth = document.getElementById('bomMaxDepth').value;

    var fd = new FormData();
    fd.append('items', bomCurrentItems);
    fd.append('mode', bomCurrentMode);
    fd.append('maxDepth', maxDepth);
    bomAppendFiltersToFormData(fd);
    fetch('/api/bom/email', { method: 'POST', body: fd })
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
                showCustomAlert('PLM Toolkit',data.message || 'Failed to send email.');
            }
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.innerHTML = origText;
            showCustomAlert('PLM Toolkit','Failed: ' + err.message);
        });
}

// === BOM Chips ===
function updateBomChips(data) {
    var chips = document.getElementById('bomChips');
    chips.innerHTML = '';
    addBomChip(chips, data.mode === 'explode' ? 'Explode' : 'Where Used');
    if (data.items) {
        var label = data.items.length > 40 ? data.items.substring(0, 40) + '...' : data.items;
        addBomChip(chips, 'Items: ' + label);
    }
    var countText = data.totalCount + ' rows';
    if (data.queryTimeMs) countText += ' \u2022 ' + data.queryTimeMs + 'ms';
    addBomChip(chips, countText, 'chip-count');
    if (data.truncated && data.skippedItems && data.skippedItems.length > 0) {
        addBomChip(chips, 'Too many results for ' + data.skippedItems.length + ' item(s) — skipped to prevent overload', 'chip-warn');
    }
    if (data.fromFileItems && data.fromFileItems.length > 0) {
        var asOf = data.fromFileAsOf ? ' (data as of ' + data.fromFileAsOf + ')' : '';
        addBomChip(chips, data.fromFileItems.length + ' item(s) too deep for live DB — served from offline extract' + asOf, 'chip-info');
    }
    // Smart-detector chip (only after a file upload).
    if (data.itemColumn) {
        var ic = data.itemColumn;
        var methodLabel = ic.method === 'header-match'  ? 'header'
                       : ic.method === 'ai-fallback'    ? 'AI'
                       : ic.method === 'user-override'  ? 'you picked'
                       : ic.method === 'default-col-a'  ? 'default A'
                       : ic.method;
        addBomChip(chips, 'Item column: ' + ic.letter + ' (' + (ic.header || '\u2014') + ') \u00b7 via ' + methodLabel, 'chip-info');
    }
    if (typeof data.uniqueItems === 'number' && typeof data.inputRows === 'number'
            && data.uniqueItems > 0 && data.inputRows > data.uniqueItems) {
        addBomChip(chips, data.uniqueItems + ' unique items from ' + data.inputRows + ' rows', 'chip-info');
    }
    if (data.filtersApplied) {
        addBomChip(chips, 'Filters: ' + data.filtersApplied, 'chip-info');
    }
    document.getElementById('bomStatusBar').style.display = 'flex';
}

// === BOM Implode Enrich ===
function doBomEnrichImplode() {
    if (!bomLastUploadFile) {
        showCustomAlert('PLM Toolkit', 'Upload a file first \u2014 enrichment writes the matching top-level parents back into the original spreadsheet.');
        return;
    }
    var btn = document.getElementById('bomEnrichBtn');
    var origText = btn ? btn.innerHTML : '';
    if (btn) { btn.disabled = true; btn.innerHTML = 'Building...'; }
    smartEnrich(bomLastUploadFile, {
        endpoint: '/api/bom/enrich-implode',
        extraFields: bomLastChosenColumn != null ? { itemColumn: bomLastChosenColumn } : null,
        onDone: function() { if (btn) { btn.disabled = false; btn.innerHTML = origText; } }
    });
}

function updateBomFilterCount(shown, total) {
    var el = document.getElementById('bomFilterCount');
    if (!el) {
        el = document.createElement('span');
        el.id = 'bomFilterCount';
        el.className = 'chip chip-warn';
        el.style.display = 'none';
        var chips = document.getElementById('bomChips');
        if (chips) chips.appendChild(el);
    }
    if (shown < total) {
        el.textContent = 'Showing ' + shown + ' of ' + total + ' rows';
        el.style.display = '';
    } else {
        el.style.display = 'none';
    }
}

function addBomChip(container, text, extraClass) {
    var span = document.createElement('span');
    span.className = 'chip' + (extraClass ? ' ' + extraClass : '');
    span.textContent = text;
    container.appendChild(span);
}

function updateBomDataAsOf(data) {
    if (data.dataAsOf) {
        document.getElementById('bomDataAsOf').style.display = 'block';
        document.getElementById('bomDataAsOfText').textContent =
            'BOM data current as of: ' + data.dataAsOf;
    }
}

// === BOM UI State ===
var bomSearchStart = 0;
var bomProgressPoller = null;

function startBomProgressPolling() {
    if (bomProgressPoller) clearInterval(bomProgressPoller);
    var pollStart = Date.now();
    var lastLiveProgress = null;  // remembered between polls so the elapsed-time tick
                                   // can re-render even when /api/bom/progress is between updates

    function paintNow() {
        var text = document.getElementById('bomLoadingText');
        if (!text) return;
        var secs = Math.round((Date.now() - pollStart) / 1000);
        var elapsed = secs >= 2 ? ' \u00b7 ' + secs + 's elapsed' : '';
        if (lastLiveProgress) {
            text.textContent = lastLiveProgress + elapsed;
        } else if (secs >= 2) {
            text.textContent = 'Reading the file you uploaded\u2026' + elapsed;
        }
    }
    // Render twice a second so the "Xs elapsed" tick keeps moving even when the
    // server hasn't issued a new progress update — keeps the UI feeling alive.
    if (bomProgressPoller) clearInterval(bomProgressPoller);
    var tickTimer = setInterval(paintNow, 500);
    bomProgressPoller = setInterval(function() {
        fetch('/api/bom/progress')
            .then(function(res) { return res.json(); })
            .then(function(data) {
                // Batched path: surface level + edge progress. The per-item counter
                // doesn't move until the final emit phase, so on bulk uploads the old
                // "Processing item 0 of 9097" message would stay stuck for the entire
                // run. We also ignore stale completedItems from a previous query —
                // the controller resets the counters before parsing the new file but
                // the poller was racing the reset and would briefly flash stale values.
                if ((data.stage === 'queries' || data.stage === 'top-walk') && data.level > 0) {
                    var label = data.stage === 'top-walk'
                        ? 'Finding top-level parents \u2014 level '
                        : 'Walking BOM tree \u2014 level ';
                    var msg = label + data.level;
                    if (data.maxDepth > 0) msg += ' of ' + data.maxDepth;
                    // Chunk progress (Oracle 1000-IN cap forces N chunks per level —
                    // 9097 inputs → 10 chunks at level 1). This is what actually moves
                    // during the slow part; the level number stays put for tens of seconds.
                    if (data.chunksTotal > 0) msg += ' \u00b7 chunk ' + data.chunksDone + '/' + data.chunksTotal;
                    if (data.edges > 0) msg += ' \u00b7 ' + data.edges.toLocaleString() + ' edges so far';
                    lastLiveProgress = msg;
                } else if (data.stage === 'emit' && data.total > 0) {
                    lastLiveProgress = 'Emitting rows \u2014 ' + data.completed + ' of ' + data.total + ' input items';
                } else if (data.stage === 'top-level') {
                    var msg = 'Finding top-level assemblies';
                    if (data.chunksTotal > 0) msg += ' \u2014 chunk ' + data.chunksDone + '/' + data.chunksTotal;
                    lastLiveProgress = msg;
                }
                // Note: we deliberately do NOT update lastLiveProgress when stage is
                // empty + total>0 — that's the stale-data window between requests.
                paintNow();
            })
            .catch(function() {});
    }, 1000);
    // Stash so stopBomProgressPolling can clear both.
    bomProgressPoller._tickTimer = tickTimer;
}

function stopBomProgressPolling() {
    if (bomProgressPoller) {
        if (bomProgressPoller._tickTimer) clearInterval(bomProgressPoller._tickTimer);
        clearInterval(bomProgressPoller);
        bomProgressPoller = null;
    }
}

function showBomLoading() {
    document.getElementById('bomLoading').style.display = 'block';
    document.getElementById('bomEmptyState').style.display = 'none';
    document.getElementById('bomNoResults').style.display = 'none';
    document.getElementById('bomTableWrapper').style.display = 'none';
    document.getElementById('bomStatusBar').style.display = 'none';
    // textId=null — the BOM poller owns the text element itself (sets the message
    // based on real server-side stage + a self-managed elapsed-time tick). Adaptive
    // is just here to drive the % bar from the saved estimate.
    bomSearchStart = startAdaptiveProgress('bom', null, 'bomProgressBar', 'bomProgressPct', 'bomProgressWrap', 'Processing BOM data...');
    startBomProgressPolling();
}
function hideBomLoading() {
    stopBomProgressPolling();
    stopAdaptiveProgress('bom', bomSearchStart, 'bomProgressBar', 'bomProgressPct');
    document.getElementById('bomLoading').style.display = 'none';
}
function showBomTable() {
    document.getElementById('bomTableWrapper').style.display = 'block';
    document.getElementById('bomNoResults').style.display = 'none';
    document.getElementById('bomEmptyState').style.display = 'none';
}
function showBomNoResults(data) {
    var noResultsDiv = document.getElementById('bomNoResults');
    // Update message based on whether items were skipped due to size
    if (data && data.truncated && data.skippedItems && data.skippedItems.length > 0) {
        noResultsDiv.innerHTML =
            '<h2>BOM Results Too Large</h2>' +
            '<p>The following item(s) returned too many results (50,000+ rows each) and were skipped to prevent server overload:</p>' +
            '<p style="margin-top:8px; font-weight:600; color:#dc3545;">' + data.skippedItems.join(', ') + '</p>' +
            '<p style="margin-top:12px; font-size:13px; color:#666;">Try reducing the <strong>Max Depth</strong> (e.g., 5 or 10 instead of 20) to get fewer results, or search for these items individually.</p>';
    } else {
        noResultsDiv.innerHTML =
            '<h2>No BOM Data Found</h2>' +
            '<p>The item number was not found in the BOM data. Check the number and try again.</p>';
    }
    noResultsDiv.style.display = 'block';
    document.getElementById('bomTableWrapper').style.display = 'none';
    document.getElementById('bomEmptyState').style.display = 'none';
}

function truncate(str, max) {
    if (!str) return '';
    var s = esc(str);
    if (s.length > max) return s.substring(0, max) + '..';
    return s;
}
// Reuse esc() from app.js

// === Full BOM Extract Download ===
function doBomFullExtractDownload() {
    var btn = document.getElementById('bomFullExtractBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Checking...';

    fetch('/api/bom/full-extract-status')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.innerHTML = origText;
            if (data.available) {
                var sizeMB = (data.size / 1024 / 1024).toFixed(1);
                showCustomAlert('PLM Toolkit',
                    'Full BOM Extract available:\n' +
                    data.filename + ' (' + sizeMB + ' MB)\n' +
                    'Generated: ' + data.date + '\n\n' +
                    'Download will start automatically.');
                window.location.href = '/api/bom/full-extract-download';
            } else if (data.generating) {
                showCustomAlert('PLM Toolkit', 'BOM extract generation is in progress. Please check back in a few minutes.');
            } else {
                // No file and not generating — offer to trigger
                triggerBomExtract();
            }
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.innerHTML = origText;
            showCustomAlert('PLM Toolkit', 'Failed to check extract status: ' + err.message);
        });
}

function triggerBomExtract() {
    fetch('/api/bom/full-extract-trigger', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            showCustomAlert('PLM Toolkit', data.message);
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
        });
}

// === BOM Notes Extract ===
function doBomNotesExtract() {
    var btn = document.getElementById('bomNotesExtractBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Checking...';

    fetch('/api/bom/bom-notes-status')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.innerHTML = origText;
            // Order matters: a regen-in-progress message takes precedence over any stale file on disk.
            if (data.generating) {
                var msg = 'BOM Notes extract is currently being regenerated.';
                if (data.bomCount > 0) {
                    if (data.bomProcessed > 0) {
                        var pct = Math.floor((data.bomProcessed / data.bomCount) * 100);
                        msg += '\n\nProgress: ' + data.bomProcessed.toLocaleString() + ' of ' + data.bomCount.toLocaleString() + ' BOMs (' + pct + '%)';
                        if (data.rowCount > 0) msg += '\nRows written: ' + data.rowCount.toLocaleString();
                    } else {
                        msg += '\n\nFound ' + data.bomCount.toLocaleString() + ' BOMs with notes — starting extract...';
                    }
                } else {
                    msg += '\n\nQuery is still scanning for BOMs with notes.';
                }
                msg += '\n\nPlease check back in a few minutes.';
                showCustomAlert('PLM Toolkit', msg);
            } else if (data.tooSmall) {
                // Existing file is empty/corrupt — flag it, don't auto-download.
                showCustomAlert('PLM Toolkit',
                    'The cached BOM Notes extract looks empty or corrupt:\n' +
                    data.filename + ' (' + data.size.toLocaleString() + ' bytes).\n\n' +
                    'Use the "regenerate" link (admin-only) to produce a fresh extract.');
            } else if (data.available) {
                var sizeMB = (data.size / 1024 / 1024).toFixed(1);
                var ageText = '';
                if (data.minutesAgo < 1) ageText = 'just now';
                else if (data.minutesAgo < 60) ageText = data.minutesAgo + ' minutes ago';
                else if (data.minutesAgo < 1440) ageText = Math.floor(data.minutesAgo / 60) + ' hours ago';
                else ageText = Math.floor(data.minutesAgo / 1440) + ' days ago';
                showCustomAlert('PLM Toolkit',
                    'BOM Notes Extract available:\n' +
                    data.filename + ' (' + sizeMB + ' MB)\n' +
                    'Generated: ' + ageText + '\n\n' +
                    'Download will start automatically.');
                window.location.href = '/api/bom/bom-notes-download';
            } else {
                triggerBomNotesExtract();
            }
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.innerHTML = origText;
            showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
        });
}

function triggerBomNotesExtract() {
    fetch('/api/bom/bom-notes-trigger', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            showCustomAlert('PLM Toolkit', data.message + '\n\nClick the button again to see progress.');
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
        });
}

// Admin-only: force a fresh extract even when a cached file exists
function doBomNotesRegenerate(e) {
    if (e) e.preventDefault();

    // Check first — if a regen is already running, show progress instead of re-prompting
    fetch('/api/bom/bom-notes-status')
        .then(function(res) { return res.json(); })
        .then(function(status) {
            if (status.generating) {
                var msg = 'BOM Notes extract is already being generated.';
                if (status.bomCount > 0) {
                    if (status.bomProcessed > 0) {
                        var pct = Math.floor((status.bomProcessed / status.bomCount) * 100);
                        msg += '\n\nProgress: ' + status.bomProcessed.toLocaleString() + ' of ' + status.bomCount.toLocaleString() + ' BOMs (' + pct + '%)';
                        if (status.rowCount > 0) msg += '\nRows written: ' + status.rowCount.toLocaleString();
                    } else {
                        msg += '\n\nFound ' + status.bomCount.toLocaleString() + ' BOMs with notes — starting extract...';
                    }
                } else {
                    msg += '\n\nQuery is still scanning for BOMs with notes.';
                }
                msg += '\n\nClick "regenerate" again in a minute to see updated progress.';
                showCustomAlert('PLM Toolkit', msg);
                return;
            }
            showCustomConfirm('PLM Toolkit', 'Force regeneration of the BOM Notes extract? This runs a heavy query and takes several minutes.')
                .then(function(ok) {
                    if (!ok) return;
                    fetch('/api/bom/bom-notes-trigger', { method: 'POST' })
                        .then(function(res) { return res.json(); })
                        .then(function(data) {
                            showCustomAlert('PLM Toolkit', data.message + '\n\nClick "regenerate" to check progress.');
                        })
                        .catch(function(err) {
                            showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
                        });
                });
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'Failed to check status: ' + err.message);
        });
}

// === BOM Insight Strip ===
function fillBomInsightStrip(rows, data) {
    var strip = document.getElementById('bomInsightStrip');
    if (!strip) return;
    strip.style.display = 'grid';

    // Rows returned
    document.getElementById('bomInsightRows').textContent = rows.length.toLocaleString();
    var itemCount = data.items ? data.items.split(',').length : 1;
    document.getElementById('bomInsightRowsSub').textContent = 'from ' + itemCount + ' root item' + (itemCount > 1 ? 's' : '');

    // Unique components
    var comps = {};
    rows.forEach(function(r) { if (r.component) comps[r.component] = true; });
    var uniqueCount = Object.keys(comps).length;
    document.getElementById('bomInsightUnique').textContent = uniqueCount.toLocaleString();
    var reused = rows.length - uniqueCount;
    document.getElementById('bomInsightUniqueSub').textContent = reused > 0 ? reused + ' reused across branches' : 'all unique';

    // Max depth reached
    var maxDepth = 0;
    rows.forEach(function(r) { if (r.level > maxDepth) maxDepth = r.level; });
    var depthCap = document.getElementById('bomMaxDepth').value || '?';
    var el = document.getElementById('bomInsightDepth');
    el.innerHTML = maxDepth + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">of ' + depthCap + ' cap</span>';
    document.getElementById('bomInsightDepthSub').textContent = maxDepth >= parseInt(depthCap) ? 'limit reached' : 'limit not hit';

    // Query time
    var ms = data.queryTimeMs || data.elapsed || 0;
    var timeEl = document.getElementById('bomInsightTime');
    timeEl.innerHTML = ms.toLocaleString() + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">ms</span>';
    document.getElementById('bomInsightTimeSub').textContent = 'live query';
}

function hideBomInsightStrip() {
    var strip = document.getElementById('bomInsightStrip');
    if (strip) strip.style.display = 'none';
}

// === Top-Level Assembly Notices ===
function showBomExtraTopLevelNotice(extraCount, totalCount) {
    hideBomExtraTopLevelNotice();
    var notice = document.createElement('div');
    notice.id = 'bomExtraTopLevelNotice';
    notice.style.cssText = 'margin:8px auto; max-width:1400px; padding:10px 16px; background:#fff3cd; border:1px solid #ffc107; border-radius:6px; font-size:13px; color:#856404; display:flex; align-items:center; gap:8px;';
    notice.innerHTML = '<span style="font-size:16px;">&#9888;</span> ' +
        '<span><strong>' + extraCount + ' additional top-level assembl' + (extraCount === 1 ? 'y' : 'ies') +
        '</strong> found beyond your current Max Depth (' + totalCount + ' total). ' +
        '<strong>Export to Excel</strong> to see all top-level assemblies in the "Top-Level Assemblies" tab.</span>';
    var table = document.getElementById('bomTableWrapper');
    if (table && table.parentNode) {
        table.parentNode.insertBefore(notice, table);
    }
}

function showBomTopLevelInfo(totalCount) {
    hideBomExtraTopLevelNotice();
    var notice = document.createElement('div');
    notice.id = 'bomExtraTopLevelNotice';
    notice.style.cssText = 'margin:8px auto; max-width:1400px; padding:10px 16px; background:#e8f4fd; border:1px solid #b8daff; border-radius:6px; font-size:13px; color:#0c5460; display:flex; align-items:center; gap:8px;';
    notice.innerHTML = '<span style="font-size:16px;">&#9432;</span> ' +
        '<span><strong>' + totalCount + ' top-level assembl' + (totalCount === 1 ? 'y' : 'ies') +
        '</strong> found. Export to Excel to see the "Top-Level Assemblies" tab with full paths.</span>';
    var table = document.getElementById('bomTableWrapper');
    if (table && table.parentNode) {
        table.parentNode.insertBefore(notice, table);
    }
}

function hideBomExtraTopLevelNotice() {
    var existing = document.getElementById('bomExtraTopLevelNotice');
    if (existing) existing.remove();
}
