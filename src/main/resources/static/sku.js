// === SKU Lookup ===
var skuResults = [];
var skuColumnFilters = {};
var skuCurrentItems = '';
var skuAllFields = [];
var skuColumnsLoaded = false;

// Multi-source external data state
var externalSources = [];                // full list from /external-sources
var externalHeadersCache = {};           // { sourceId: [headers...] } — lazy-loaded
var selectedSourceColumns = {};          // { sourceId: [col1, col2, ...] } — user selection
// Ordered list of sourceIds the user has checked (preserves column display order)
var selectedSourceOrder = [];

var SKU_DEFAULT_COLS = ['number', 'description', 'rev', 'category', 'productLine', 'lifecyclePhase'];

function loadSkuFields() {
    if (skuColumnsLoaded) return;
    fetch('/api/sku-data/fields')
        .then(function(res) { return res.json(); })
        .then(function(fields) {
            skuAllFields = fields || [];
            skuColumnsLoaded = true;
            buildSkuColumnPicker();
        })
        .catch(function() {});
    loadExternalSources();
}

function loadExternalSources() {
    fetch('/api/sku-data/external-sources')
        .then(function(res) { return res.json(); })
        .then(function(sources) {
            externalSources = sources || [];
            renderExternalSourcesList();
        })
        .catch(function() {});
}

/** Render a checkbox-per-source list. Each source expands to its own column picker when checked. */
function renderExternalSourcesList() {
    var container = document.getElementById('skuExternalSourcesList');
    if (!container) return;
    container.innerHTML = '';

    if (externalSources.length === 0) {
        container.innerHTML = '<div style="font-size:12px; color:#888;">No external sources configured. Add them in the Extensions tab.</div>';
        return;
    }

    externalSources.forEach(function(src) {
        var srcWrap = document.createElement('div');
        srcWrap.style.cssText = 'border:1px solid #d0d5dd; border-radius:6px; padding:8px 10px; background:#fff;';
        srcWrap.setAttribute('data-source-id', src.id);

        // Header row: checkbox + name. Rebuilds happen from the Extensions tab,
        // not here — the SKU Lookup tab is for querying, not for managing sources.
        var hdr = document.createElement('div');
        hdr.style.cssText = 'display:flex; align-items:center; gap:8px;';

        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.className = 'ext-source-cb';
        cb.setAttribute('data-source-id', src.id);
        cb.onchange = function() { onSourceToggle(src.id, cb.checked); };

        var lbl = document.createElement('label');
        lbl.style.cssText = 'font-size:13px; font-weight:600; color:#1a3a5c; cursor:pointer;';
        lbl.textContent = src.name;
        lbl.onclick = function() { cb.checked = !cb.checked; onSourceToggle(src.id, cb.checked); };

        hdr.appendChild(cb);
        hdr.appendChild(lbl);
        srcWrap.appendChild(hdr);

        // Column picker — hidden until source is checked
        var colsDiv = document.createElement('div');
        colsDiv.id = 'skuExtCols-' + src.id;
        colsDiv.style.cssText = 'margin-top:8px; display:none;';
        srcWrap.appendChild(colsDiv);

        container.appendChild(srcWrap);
    });
}

function onSourceToggle(sourceId, checked) {
    var colsDiv = document.getElementById('skuExtCols-' + sourceId);
    if (!checked) {
        if (colsDiv) { colsDiv.style.display = 'none'; colsDiv.innerHTML = ''; }
        delete selectedSourceColumns[sourceId];
        var idx = selectedSourceOrder.indexOf(sourceId);
        if (idx >= 0) selectedSourceOrder.splice(idx, 1);
        return;
    }

    if (selectedSourceOrder.indexOf(sourceId) < 0) selectedSourceOrder.push(sourceId);
    selectedSourceColumns[sourceId] = [];

    // Use cached headers if we have them; otherwise fetch
    if (externalHeadersCache[sourceId]) {
        renderColumnCheckboxesForSource(sourceId, externalHeadersCache[sourceId]);
    } else {
        colsDiv.style.display = 'block';
        colsDiv.innerHTML = '<div style="font-size:12px; color:#888;">Loading columns...</div>';
        fetch('/api/sku-data/external-headers?sourceId=' + encodeURIComponent(sourceId))
            .then(function(res) { return res.json(); })
            .then(function(data) {
                externalHeadersCache[sourceId] = data.headers || [];
                renderColumnCheckboxesForSource(sourceId, externalHeadersCache[sourceId]);
            })
            .catch(function() {
                colsDiv.innerHTML = '<div style="font-size:12px; color:#dc3545;">Failed to load columns.</div>';
            });
    }
}

function renderColumnCheckboxesForSource(sourceId, headers) {
    var container = document.getElementById('skuExtCols-' + sourceId);
    if (!container) return;
    container.innerHTML = '';
    container.style.display = 'block';

    if (headers.length === 0) {
        container.innerHTML = '<div style="font-size:12px; color:#888;">No columns available.</div>';
        return;
    }

    var lbl = document.createElement('div');
    lbl.style.cssText = 'font-size:11px; color:#666; margin-bottom:4px; font-weight:600;';
    lbl.textContent = 'Select external columns to include:';
    container.appendChild(lbl);

    var actions = document.createElement('div');
    actions.style.cssText = 'margin-bottom:6px;';
    var selAll = document.createElement('a');
    selAll.href = '#';
    selAll.textContent = 'Select All';
    selAll.style.cssText = 'font-size:11px; color:#4a6fa5; margin-right:12px;';
    selAll.onclick = function(e) { e.preventDefault(); toggleAllExtColsForSource(sourceId, true); };
    var desAll = document.createElement('a');
    desAll.href = '#';
    desAll.textContent = 'Deselect All';
    desAll.style.cssText = 'font-size:11px; color:#4a6fa5;';
    desAll.onclick = function(e) { e.preventDefault(); toggleAllExtColsForSource(sourceId, false); };
    actions.appendChild(selAll);
    actions.appendChild(desAll);
    container.appendChild(actions);

    var wrap = document.createElement('div');
    wrap.style.cssText = 'display:flex; flex-wrap:wrap; gap:4px 12px; max-height:100px; overflow-y:auto; border:1px solid #e0e0e0; border-radius:6px; padding:8px; background:#fafafa;';

    headers.forEach(function(hdr) {
        var label = document.createElement('label');
        label.style.cssText = 'font-size:12px; display:flex; align-items:center; gap:3px; cursor:pointer; white-space:nowrap;';
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.value = hdr;
        cb.className = 'ext-col-cb-' + sourceId;
        cb.setAttribute('data-source-id', sourceId);
        cb.onchange = function() { updateSelectedColsForSource(sourceId); };
        label.appendChild(cb);
        label.appendChild(document.createTextNode(hdr));
        wrap.appendChild(label);
    });
    container.appendChild(wrap);
}

function toggleAllExtColsForSource(sourceId, checked) {
    document.querySelectorAll('.ext-col-cb-' + sourceId).forEach(function(cb) { cb.checked = checked; });
    updateSelectedColsForSource(sourceId);
}

function updateSelectedColsForSource(sourceId) {
    var cols = [];
    document.querySelectorAll('.ext-col-cb-' + sourceId + ':checked').forEach(function(cb) {
        cols.push(cb.value);
    });
    selectedSourceColumns[sourceId] = cols;
}

/** Returns true if the user has selected at least one source with at least one column. */
function hasAnyExternalSelection() {
    for (var i = 0; i < selectedSourceOrder.length; i++) {
        var sid = selectedSourceOrder[i];
        if (selectedSourceColumns[sid] && selectedSourceColumns[sid].length > 0) return true;
    }
    return false;
}

/** Build the sourceColumnsMap JSON payload for the backend. Only includes sources
 *  that have at least one column selected. */
function buildSourceColumnsMap() {
    var map = {};
    selectedSourceOrder.forEach(function(sid) {
        var cols = selectedSourceColumns[sid] || [];
        if (cols.length > 0) map[sid] = cols;
    });
    return map;
}

/** Returns the display name for a source ID (from the cached list). */
function getSourceName(sourceId) {
    for (var i = 0; i < externalSources.length; i++) {
        if (externalSources[i].id === sourceId) return externalSources[i].name;
    }
    return sourceId;
}

function buildSkuColumnPicker() {
    var avail = document.getElementById('skuAvailableFields');
    var displayed = document.getElementById('skuDisplayedFields');
    if (!avail || !displayed) return;
    avail.innerHTML = '';
    displayed.innerHTML = '';

    var displayedSet = new Set(SKU_DEFAULT_COLS);

    SKU_DEFAULT_COLS.forEach(function(col) {
        var opt = document.createElement('option');
        opt.value = col;
        opt.textContent = col;
        displayed.appendChild(opt);
    });

    skuAllFields.forEach(function(col) {
        if (displayedSet.has(col)) return;
        var opt = document.createElement('option');
        opt.value = col;
        opt.textContent = col;
        avail.appendChild(opt);
    });
}

function skuMoveRight() {
    var avail = document.getElementById('skuAvailableFields');
    var displayed = document.getElementById('skuDisplayedFields');
    Array.from(avail.selectedOptions).forEach(function(opt) { displayed.appendChild(opt); });
}
function skuMoveLeft() {
    var avail = document.getElementById('skuAvailableFields');
    var displayed = document.getElementById('skuDisplayedFields');
    Array.from(displayed.selectedOptions).forEach(function(opt) { avail.appendChild(opt); });
    var opts = Array.from(avail.options);
    opts.sort(function(a, b) { return a.value.localeCompare(b.value); });
    avail.innerHTML = '';
    opts.forEach(function(o) { avail.appendChild(o); });
}
function skuMoveUp() {
    var sel = document.getElementById('skuDisplayedFields');
    var selected = Array.from(sel.selectedOptions);
    for (var i = 0; i < selected.length; i++) {
        var idx = selected[i].index;
        if (idx > 0) sel.insertBefore(selected[i], sel.options[idx - 1]);
    }
}
function skuMoveDown() {
    var sel = document.getElementById('skuDisplayedFields');
    var selected = Array.from(sel.selectedOptions);
    for (var i = selected.length - 1; i >= 0; i--) {
        var idx = selected[i].index;
        if (idx < sel.options.length - 1) sel.insertBefore(sel.options[idx + 1], selected[i]);
    }
}

function getSkuSelectedColumns() {
    var cols = [];
    var displayed = document.getElementById('skuDisplayedFields');
    if (displayed) {
        Array.from(displayed.options).forEach(function(opt) { cols.push(opt.value); });
    }
    return cols.length > 0 ? cols : SKU_DEFAULT_COLS;
}

/** Restore column picker to a saved set of column names. */
function restoreSkuColumns(cols) {
    var avail = document.getElementById('skuAvailableFields');
    var displayed = document.getElementById('skuDisplayedFields');
    if (!avail || !displayed) return;

    // Move everything back to available first
    while (displayed.options.length > 0) {
        avail.appendChild(displayed.options[0]);
    }
    // Move the saved columns to displayed in order
    cols.forEach(function(col) {
        for (var i = 0; i < avail.options.length; i++) {
            if (avail.options[i].value === col) {
                displayed.appendChild(avail.options[i]);
                break;
            }
        }
    });
}

/** Restore external source selections from a saved sourceColumnsMap. */
function restoreSkuExtSources(sourceMap) {
    if (!sourceMap || typeof sourceMap !== 'object') return;

    // Wait a tick for external sources to render (they load async)
    setTimeout(function() {
        Object.keys(sourceMap).forEach(function(sourceId) {
            var cb = document.querySelector('.ext-source-cb[data-source-id="' + sourceId + '"]');
            if (cb && !cb.checked) {
                cb.checked = true;
                onSourceToggle(sourceId, true);
            }
            // After the column picker renders, check the saved columns
            setTimeout(function() {
                var savedCols = sourceMap[sourceId] || [];
                var colsDiv = document.getElementById('skuExtCols-' + sourceId);
                if (!colsDiv) return;
                var checkboxes = colsDiv.querySelectorAll('input[type="checkbox"]');
                checkboxes.forEach(function(chk) {
                    chk.checked = savedCols.indexOf(chk.value) >= 0;
                    // Trigger onchange to update selectedSourceColumns
                    if (chk.checked && chk.onchange) chk.onchange();
                });
                // Update selectedSourceColumns directly
                selectedSourceColumns[sourceId] = savedCols;
            }, 300);
        });
    }, 500);
}

function doSkuSearch() {
    var items = document.getElementById('skuItemInput').value.trim();
    var fileInput = document.getElementById('skuFileInput');

    if (fileInput && fileInput.files && fileInput.files.length > 0) {
        doSkuFileUpload(fileInput.files[0]);
        return;
    }

    if (!items) {
        document.getElementById('skuEmptyState').style.display = 'block';
        document.getElementById('skuTableWrapper').style.display = 'none';
        document.getElementById('skuNoResults').style.display = 'none';
        return;
    }

    skuCurrentItems = items;
    document.getElementById('skuEmptyState').style.display = 'none';
    document.getElementById('skuLoading').style.display = 'block';

    var isCombined = hasAnyExternalSelection();
    var url;
    if (isCombined) {
        url = '/api/sku-data/combined-search?items=' + encodeURIComponent(items) +
              '&sourceColumnsMap=' + encodeURIComponent(JSON.stringify(buildSourceColumnsMap()));
    } else {
        url = '/api/sku-data/search?items=' + encodeURIComponent(items);
    }

    fetch(url)
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            document.getElementById('skuLoading').style.display = 'none';
            if (isCombined) {
                skuResults = flattenCombinedResults(data.results || []);
            } else {
                skuResults = data.results || [];
            }
            skuColumnFilters = {};
            updateSkuChips(data);
            if (skuResults.length === 0) {
                document.getElementById('skuNoResults').style.display = 'block';
                if (data.notFound && data.notFound.length > 0) {
                    document.getElementById('skuNoResultsMsg').textContent = 'Not found: ' + data.notFound.join(', ');
                }
                hideSkuInsightStrip();
            } else {
                renderSkuTable();
                document.getElementById('skuTableWrapper').style.display = 'block';
                fillSkuInsightStrip(skuResults, data);
            }
        })
        .catch(function(err) {
            document.getElementById('skuLoading').style.display = 'none';
            showCustomAlert('PLM Toolkit', 'Search failed: ' + err.message);
        });
}

// Captured at upload time so the Enrich button reuses the user's chosen column.
var skuLastUploadFile = null;
var skuLastChosenColumn = null;

function doSkuFileUpload(file) {
    document.getElementById('skuEmptyState').style.display = 'none';
    document.getElementById('skuLoading').style.display = 'block';
    skuLastUploadFile = file;

    smartUpload(file, {
        op: 'QUERY',
        proceed: function(itemCol) {
            skuLastChosenColumn = itemCol >= 0 ? itemCol : null;
            doSkuFileUploadXhr(file, itemCol);
        },
        onTooLarge: function(probe) {
            document.getElementById('skuLoading').style.display = 'none';
            showCustomAlert('PLM Toolkit', probe.message);
        }
    });
}

function doSkuFileUploadXhr(file, itemCol) {
    var formData = new FormData();
    formData.append('file', file);
    if (itemCol >= 0) formData.append('itemColumn', String(itemCol));
    var isCombined = hasAnyExternalSelection();
    var url;

    if (isCombined) {
        formData.append('sourceColumnsMap', JSON.stringify(buildSourceColumnsMap()));
        url = '/api/sku-data/combined-search-file';
    } else {
        url = '/api/sku-data/search-file';
    }

    fetch(url, { method: 'POST', body: formData })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            document.getElementById('skuLoading').style.display = 'none';
            if (isCombined) {
                skuResults = flattenCombinedResults(data.results || []);
            } else {
                skuResults = data.results || [];
            }
            skuCurrentItems = skuResults.map(function(r) { return r.number; }).join(',');
            skuColumnFilters = {};
            updateSkuChips(data);
            if (skuResults.length === 0) {
                document.getElementById('skuNoResults').style.display = 'block';
                hideSkuInsightStrip();
            } else {
                renderSkuTable();
                document.getElementById('skuTableWrapper').style.display = 'block';
                fillSkuInsightStrip(skuResults, data);
            }
            var eb = document.getElementById('skuEnrichBtn');
            if (eb) eb.style.display = skuLastUploadFile ? '' : 'none';
        })
        .catch(function(err) {
            document.getElementById('skuLoading').style.display = 'none';
            showCustomAlert('PLM Toolkit', 'Upload failed: ' + err.message);
        });
}

function doSkuEnrich() {
    if (!skuLastUploadFile) {
        showCustomAlert('PLM Toolkit', 'Upload a file first \u2014 enrichment writes the matching SKU fields back into the original spreadsheet.');
        return;
    }
    var fields = (typeof getSkuSelectedColumns === 'function') ? getSkuSelectedColumns() : [];
    if (!fields || fields.length === 0) {
        showCustomAlert('PLM Toolkit', 'Select at least one display field on the right before enriching.');
        return;
    }
    var btn = document.getElementById('skuEnrichBtn');
    var origText = btn ? btn.innerHTML : '';
    if (btn) { btn.disabled = true; btn.innerHTML = 'Building...'; }
    var extra = { fields: fields.join(',') };
    if (skuLastChosenColumn != null) extra.itemColumn = skuLastChosenColumn;
    smartEnrich(skuLastUploadFile, {
        endpoint: '/api/sku-data/enrich',
        extraFields: extra,
        onDone: function() { if (btn) { btn.disabled = false; btn.innerHTML = origText; } }
    });
}

function doSkuClear() {
    document.getElementById('skuItemInput').value = '';
    document.getElementById('skuFileInput').value = '';
    skuResults = [];
    skuColumnFilters = {};
    document.getElementById('skuStatusBar').style.display = 'none';
    document.getElementById('skuTableWrapper').style.display = 'none';
    document.getElementById('skuNoResults').style.display = 'none';
    document.getElementById('skuEmptyState').style.display = 'block';
}

/** Build the full ordered column list for the results table.
 *  Agile columns come first, then for each selected external source: its selected columns.
 *  External column keys are flat strings: 'ext:{sourceId}:{colName}' so they don't collide
 *  when multiple sources share a column name. */
function getSkuAllTableCols() {
    var agileCols = getSkuSelectedColumns();
    var extCols = [];
    selectedSourceOrder.forEach(function(sid) {
        var cols = selectedSourceColumns[sid] || [];
        cols.forEach(function(c) { extCols.push('ext:' + sid + ':' + c); });
    });
    return agileCols.concat(extCols);
}

/** Parse a 'ext:{sourceId}:{colName}' key — returns { sourceId, col } or null. */
function parseExtCol(col) {
    if (col.indexOf('ext:') !== 0) return null;
    var rest = col.substring(4);
    var sep = rest.indexOf(':');
    if (sep < 0) return null;
    return { sourceId: rest.substring(0, sep), col: rest.substring(sep + 1) };
}

function renderSkuTable() {
    var allCols = getSkuAllTableCols();

    var thead = document.getElementById('skuTableHead');
    var tbody = document.getElementById('skuResultsBody');
    thead.innerHTML = '';
    tbody.innerHTML = '';

    // Two-tier color palette so different external sources are visually distinguishable
    var extSourceColors = ['#1a7a6d', '#7a5a1a', '#7a1a5a', '#1a4a7a', '#5a1a1a'];

    // Header
    var narrowCols = ['rev', 'lifecyclePhase', 'pm', 'category'];
    var headerTr = document.createElement('tr');
    allCols.forEach(function(col) {
        var th = document.createElement('th');
        var ext = parseExtCol(col);
        if (ext) {
            var srcIdx = selectedSourceOrder.indexOf(ext.sourceId);
            var bg = extSourceColors[srcIdx % extSourceColors.length] || '#1a7a6d';
            th.innerHTML = '<div style="font-size:10px; opacity:0.8; font-weight:400;">' + esc(getSourceName(ext.sourceId)) + '</div>' + esc(ext.col);
            th.style.background = bg;
            th.style.color = '#fff';
        } else {
            th.textContent = col;
        }
        th.style.cursor = 'pointer';
        if (!ext && narrowCols.indexOf(col) >= 0) th.style.width = '60px';
        headerTr.appendChild(th);
    });
    thead.appendChild(headerTr);

    // Filter row
    var filterTr = document.createElement('tr');
    filterTr.className = 'filter-row';
    allCols.forEach(function(col, idx) {
        var td = document.createElement('td');
        if (idx === 0) {
            td.innerHTML = '<a href="#" onclick="clearSkuFilters(); return false;" title="Clear filters" style="color:#8bb8e8; font-size:11px; text-decoration:none;">&#10005;</a>';
        } else {
            var input = document.createElement('input');
            input.className = 'col-filter';
            input.setAttribute('data-col', col);
            input.placeholder = 'Filter';
            input.oninput = function() { applySkuFilters(); };
            td.appendChild(input);
        }
        filterTr.appendChild(td);
    });
    thead.appendChild(filterTr);

    // Data
    var filtered = getFilteredSkuResults();
    filtered.forEach(function(rec) {
        var tr = document.createElement('tr');
        allCols.forEach(function(col) {
            var td = document.createElement('td');
            var ext = parseExtCol(col);
            var val = rec[col] || '';
            if (col === 'number') {
                td.style.cssText = 'white-space:nowrap; font-family:var(--font-mono); font-size:12.5px; font-weight:500;';
                td.textContent = val;
            } else if (col === 'lifecyclePhase') {
                var lcCls = 'v2-life';
                var s = val.toUpperCase();
                if (s === 'ACT' || s === 'C-ACT') lcCls += ' act';
                else if (s.indexOf('OBS') >= 0 || s === 'EOL') lcCls += ' eol';
                else if (s === 'DEV' || s === 'MKT' || s === 'CPROD') lcCls += ' pend';
                td.innerHTML = val ? '<span class="' + lcCls + '">' + esc(val) + '</span>' : '';
            } else {
                td.textContent = val;
            }
            if (!ext && narrowCols.indexOf(col) >= 0) { td.style.width = '60px'; td.style.textAlign = 'center'; }
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
}

function applySkuFilters() {
    skuColumnFilters = {};
    document.querySelectorAll('#skuTableHead .col-filter').forEach(function(input) {
        var val = input.value.trim().toLowerCase();
        if (val) skuColumnFilters[input.getAttribute('data-col')] = val;
    });
    renderSkuBody();
}

function clearSkuFilters() {
    document.querySelectorAll('#skuTableHead .col-filter').forEach(function(i) { i.value = ''; });
    skuColumnFilters = {};
    renderSkuBody();
}

function renderSkuBody() {
    var allCols = getSkuAllTableCols();
    var narrowCols = ['rev', 'lifecyclePhase', 'pm', 'category'];
    var tbody = document.getElementById('skuResultsBody');
    tbody.innerHTML = '';
    var filtered = getFilteredSkuResults();
    filtered.forEach(function(rec) {
        var tr = document.createElement('tr');
        allCols.forEach(function(col) {
            var td = document.createElement('td');
            var ext = parseExtCol(col);
            var val = rec[col] || '';
            if (col === 'number') {
                td.style.cssText = 'white-space:nowrap; font-family:var(--font-mono); font-size:12.5px; font-weight:500;';
                td.textContent = val;
            } else if (col === 'lifecyclePhase') {
                var lcCls = 'v2-life';
                var s = val.toUpperCase();
                if (s === 'ACT' || s === 'C-ACT') lcCls += ' act';
                else if (s.indexOf('OBS') >= 0 || s === 'EOL') lcCls += ' eol';
                else if (s === 'DEV' || s === 'MKT' || s === 'CPROD') lcCls += ' pend';
                td.innerHTML = val ? '<span class="' + lcCls + '">' + esc(val) + '</span>' : '';
            } else {
                td.textContent = val;
            }
            if (!ext && narrowCols.indexOf(col) >= 0) { td.style.width = '60px'; td.style.textAlign = 'center'; }
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
}

function getFilteredSkuResults() {
    if (Object.keys(skuColumnFilters).length === 0) return skuResults;
    return skuResults.filter(function(rec) {
        for (var col in skuColumnFilters) {
            if (!matchesFilter(rec[col], skuColumnFilters[col])) return false;
        }
        return true;
    });
}

function updateSkuChips(data) {
    var chips = document.getElementById('skuChips');
    chips.innerHTML = '';
    var countText = data.totalCount + ' SKUs';
    if (data.queryTimeMs !== undefined) countText += ' \u2022 ' + data.queryTimeMs + 'ms';
    var span = document.createElement('span');
    span.className = 'chip chip-count';
    span.textContent = countText;
    chips.appendChild(span);
    if (data.notFound && data.notFound.length > 0) {
        var warn = document.createElement('span');
        warn.className = 'chip chip-warn';
        warn.textContent = data.notFound.length + ' not found';
        warn.title = data.notFound.join(', ');
        chips.appendChild(warn);
    }
    if (data.itemColumn) {
        var ic = data.itemColumn;
        var methodLabel = ic.method === 'header-match'  ? 'header'
                       : ic.method === 'ai-fallback'    ? 'AI'
                       : ic.method === 'user-override'  ? 'you picked'
                       : ic.method === 'default-col-a'  ? 'default A'
                       : ic.method;
        var info = document.createElement('span');
        info.className = 'chip chip-info';
        info.textContent = 'Item column: ' + ic.letter + ' (' + (ic.header || '\u2014') + ') \u00b7 via ' + methodLabel;
        chips.appendChild(info);
    }
    if (typeof data.uniqueItems === 'number' && typeof data.inputRows === 'number'
            && data.uniqueItems > 0 && data.inputRows > data.uniqueItems) {
        var dd = document.createElement('span');
        dd.className = 'chip chip-info';
        dd.textContent = data.uniqueItems + ' unique items from ' + data.inputRows + ' rows';
        chips.appendChild(dd);
    }
    document.getElementById('skuStatusBar').style.display = 'flex';
}

function doSkuDelta() {
    var btn = document.getElementById('skuDeltaBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Refreshing...';
    fetch('/api/sku-data/delta', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.innerHTML = origText;
            if (data.status !== 'error') {
                showCustomAlert('PLM Toolkit', 'SKU data refreshed. ' + (data.recordsUpserted || 0) + ' records updated.');
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Delta refresh failed.');
            }
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.innerHTML = origText;
            showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
        });
}

function doSkuSeed() {
    var btn = document.getElementById('skuSeedBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Seeding (~30 sec)...';
    fetch('/api/sku-data/seed', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.innerHTML = origText;
            if (data.status === 'success') {
                showCustomAlert('PLM Toolkit', 'SKU data seeded! ' + (data.recordsWritten || 0) + ' records loaded in ' + Math.round(data.elapsedSec || 0) + 's.');
                loadSkuFields(); // reload field list
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Seed failed.');
            }
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.innerHTML = origText;
            showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
        });
}

function doSkuExport() {
    if (typeof guardExport === 'function') { guardExport(doSkuExportInner); return; }
    doSkuExportInner();
}
function doSkuExportInner() {
    if (skuResults.length === 0) return;
    var allCols = getSkuAllTableCols();

    var displayNames = allCols.map(function(c) {
        var ext = parseExtCol(c);
        if (!ext) return c;
        return getSourceName(ext.sourceId) + ' - ' + ext.col;
    });

    // Build column sources mapping for the second sheet
    var columnSources = allCols.map(function(c, i) {
        var ext = parseExtCol(c);
        return {
            column: displayNames[i],
            source: ext ? getSourceName(ext.sourceId) + ' (External)' : 'Agile PLM (SKU Cache)'
        };
    });

    var payload = {
        rows: skuResults,
        columns: allCols,
        displayNames: displayNames,
        columnSources: columnSources
    };

    fetch('/api/sku-data/export-excel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    })
    .then(function(res) {
        if (!res.ok) throw new Error('Export failed');
        return res.blob();
    })
    .then(function(blob) {
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = 'SKU-Lookup-' + new Date().toISOString().slice(0,10) + '.xlsx';
        a.click();
        URL.revokeObjectURL(url);
    })
    .catch(function(err) {
        showCustomAlert('PLM Toolkit', 'Export failed: ' + (err.message || err));
    });
}

/**
 * Flatten combined search results: merges agile + external fields into a single flat record.
 * External columns become flat keys 'ext:{sourceId}:{colName}' so renderSkuTable can
 * distinguish them per source.
 */
function flattenCombinedResults(results) {
    return results.map(function(row) {
        var flat = {};
        // Agile fields
        if (row.agile) {
            for (var k in row.agile) {
                flat[k] = row.agile[k] != null ? String(row.agile[k]) : '';
            }
        }
        // Ensure number is always set
        if (!flat.number && row.number) flat.number = row.number;
        // External fields: row.externals = { sourceId: { col: val } }
        if (row.externals) {
            for (var sid in row.externals) {
                var rec = row.externals[sid] || {};
                for (var ek in rec) {
                    flat['ext:' + sid + ':' + ek] = rec[ek] != null ? String(rec[ek]) : '';
                }
            }
        }
        flat._foundInAgile = row.foundInAgile;
        flat._foundInExternal = row.foundInExternal;
        return flat;
    });
}

// Enter key
document.addEventListener('DOMContentLoaded', function() {
    setTimeout(function() {
        var input = document.getElementById('skuItemInput');
        if (input) {
            input.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') { e.preventDefault(); doSkuSearch(); }
            });
        }
    }, 100);
});

// === SKU Insight Strip ===
function fillSkuInsightStrip(rows, data) {
    var strip = document.getElementById('skuInsightStrip');
    if (!strip) return;
    strip.style.display = 'grid';

    // SKUs found
    document.getElementById('skuInsightRows').textContent = rows.length.toLocaleString();
    var notFound = data.notFound ? data.notFound.length : 0;
    document.getElementById('skuInsightRowsSub').textContent = notFound > 0 ? notFound + ' not found' : 'all matched';

    // External sources
    var srcCount = selectedSourceOrder.length;
    var activeSrc = 0;
    selectedSourceOrder.forEach(function(sid) {
        if (selectedSourceColumns[sid] && selectedSourceColumns[sid].length > 0) activeSrc++;
    });
    var srcEl = document.getElementById('skuInsightSources');
    srcEl.textContent = activeSrc > 0 ? activeSrc : 'none';
    document.getElementById('skuInsightSourcesSub').textContent = activeSrc > 0 ? activeSrc + ' joined' : 'Agile only';

    // Columns
    var allCols = getSkuAllTableCols();
    var agileCols = allCols.filter(function(c) { return !c.startsWith('ext:'); });
    var extCols = allCols.filter(function(c) { return c.startsWith('ext:'); });
    var colEl = document.getElementById('skuInsightCols');
    colEl.innerHTML = allCols.length + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">total</span>';
    document.getElementById('skuInsightColsSub').textContent = agileCols.length + ' Agile + ' + extCols.length + ' external';

    // Query time
    var ms = data.queryTimeMs || data.elapsed || 0;
    var timeEl = document.getElementById('skuInsightTime');
    if (ms > 1000) {
        timeEl.innerHTML = (ms / 1000).toFixed(1) + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">sec</span>';
    } else {
        timeEl.innerHTML = ms + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">ms</span>';
    }
}

function hideSkuInsightStrip() {
    var strip = document.getElementById('skuInsightStrip');
    if (strip) strip.style.display = 'none';
}
