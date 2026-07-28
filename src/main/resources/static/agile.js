// === Agile Lookup ===
var agileResults = [];
var agileColumns = [];
var agileColumnFilters = {};
var agileFieldNames = [];
var agileSelectedFields = [];

// Load field names for typeahead
function loadAgileFieldNames() {
    fetch('/api/agile-lookup/field-names')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            agileFieldNames = data || [];
        })
        .catch(function() {});
}
loadAgileFieldNames();

function showFieldSuggestions(input) {
    var query = input.value.trim().toLowerCase();
    var container = document.getElementById('agileFieldSuggestions');

    if (!query || query.length < 1) {
        container.style.display = 'none';
        return;
    }

    var matches = agileFieldNames.filter(function(f) {
        return f.name.toLowerCase().indexOf(query) >= 0
            && agileSelectedFields.indexOf(f.name) < 0;
    }).slice(0, 10);

    if (matches.length === 0) {
        container.style.display = 'none';
        return;
    }

    container.innerHTML = '';
    matches.forEach(function(f) {
        var div = document.createElement('div');
        div.className = 'field-suggestion';
        div.innerHTML = '<span>' + esc(f.name) + '</span><span class="tab-hint">' + esc(f.tab) + '</span>';
        div.onclick = function() {
            addFieldTag(f.name);
            input.value = '';
            container.style.display = 'none';
        };
        container.appendChild(div);
    });
    container.style.display = 'block';
}

function addFieldTag(name) {
    if (agileSelectedFields.indexOf(name) >= 0) return;
    agileSelectedFields.push(name);
    renderFieldTags();
}

function removeFieldTag(name) {
    agileSelectedFields = agileSelectedFields.filter(function(f) { return f !== name; });
    renderFieldTags();
}

function renderFieldTags() {
    var container = document.getElementById('agileManualFieldTags');
    container.innerHTML = '';
    agileSelectedFields.forEach(function(name, idx) {
        var tag = document.createElement('span');
        tag.className = 'field-tag';
        tag.draggable = true;
        tag.setAttribute('data-idx', idx);
        tag.innerHTML = '<span class="drag-handle">&#9776;</span> ' + esc(name) + ' <span class="remove" onclick="event.stopPropagation(); removeFieldTag(\'' + name.replace(/'/g, "\\'") + '\')">&times;</span>';

        tag.addEventListener('dragstart', function(e) {
            e.dataTransfer.setData('text/plain', idx);
            tag.classList.add('dragging');
        });
        tag.addEventListener('dragend', function() {
            tag.classList.remove('dragging');
        });
        tag.addEventListener('dragover', function(e) {
            e.preventDefault();
            tag.classList.add('drag-over-tag');
        });
        tag.addEventListener('dragleave', function() {
            tag.classList.remove('drag-over-tag');
        });
        tag.addEventListener('drop', function(e) {
            e.preventDefault();
            tag.classList.remove('drag-over-tag');
            var fromIdx = parseInt(e.dataTransfer.getData('text/plain'));
            var toIdx = idx;
            if (fromIdx !== toIdx) {
                var moved = agileSelectedFields.splice(fromIdx, 1)[0];
                agileSelectedFields.splice(toIdx, 0, moved);
                renderFieldTags();
            }
        });

        container.appendChild(tag);
    });
}

function uploadAgileFieldNames(input) {
    if (!input.files || !input.files[0]) return;
    var status = document.getElementById('agileFieldUploadStatus');
    status.textContent = 'Uploading...';
    var formData = new FormData();
    formData.append('file', input.files[0]);
    fetch('/api/agile-lookup/upload-field-names', { method: 'POST', body: formData })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                status.innerHTML = '<span style="color:#28a745;">' + data.message + '</span>';
                loadAgileFieldNames();
            } else {
                status.innerHTML = '<span style="color:#dc3545;">' + (data.message || 'Failed') + '</span>';
            }
            input.value = '';
        })
        .catch(function(err) {
            status.innerHTML = '<span style="color:#dc3545;">Error: ' + err.message + '</span>';
            input.value = '';
        });
}

// Close suggestions when clicking outside
document.addEventListener('click', function(e) {
    var container = document.getElementById('agileFieldSuggestions');
    var input = document.getElementById('agileManualFields');
    if (container && container.style.display !== 'none' && e.target !== input && !container.contains(e.target)) {
        container.style.display = 'none';
    }
});

var agileSearchStart = 0;
function showAgileLoading() {
    document.getElementById('agileLoading').style.display = 'block';
    document.getElementById('agileEmptyState').style.display = 'none';
    document.getElementById('agileNoResults').style.display = 'none';
    document.getElementById('agileTableWrapper').style.display = 'none';
    document.getElementById('agileStatusBar').style.display = 'none';
    agileSearchStart = startAdaptiveProgress('agile', 'agileLoadingText', 'agileProgressBar', 'agileProgressPct', 'agileProgressWrap', 'Connecting to Agile PLM...');
}
function hideAgileLoading() {
    stopAdaptiveProgress('agile', agileSearchStart, 'agileProgressBar', 'agileProgressPct');
    document.getElementById('agileLoading').style.display = 'none';
}

function doAgileManualLookup(items, fields) {
    showAgileLoading();
    var formData = new URLSearchParams();
    formData.append('items', items);
    formData.append('fields', fields.join(','));
    fetch('/api/agile-lookup/manual-lookup', { method: 'POST', body: formData })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            hideAgileLoading();
            if (data.error) {
                document.getElementById('agileNoResultsMsg').textContent = data.error;
                document.getElementById('agileNoResults').style.display = 'block';
                document.getElementById('agileEmptyState').style.display = 'none';
                return;
            }
            agileResults = data.results || [];
            agileColumns = data.columns || [];
            agileColumnFilters = {};
            updateAgileChips(data);
            if (agileResults.length === 0) {
                document.getElementById('agileNoResultsMsg').textContent = data.message || 'No results returned.';
                document.getElementById('agileNoResults').style.display = 'block';
                document.getElementById('agileEmptyState').style.display = 'none';
                hideAgileInsightStrip();
            } else {
                renderAgileTable();
                document.getElementById('agileTableWrapper').style.display = 'block';
                document.getElementById('agileEmptyState').style.display = 'none';
                document.getElementById('agileNoResults').style.display = 'none';
                fillAgileInsightStrip(agileResults, agileColumns, data);

                if (data.fieldWarning) {
                    showCustomAlert('Field Name Warning', data.fieldWarning +
                        '\n\nThese columns show "Field Not Present" for all items. ' +
                        'Please check the field names for typos.');
                }
            }
        })
        .catch(function(err) {
            hideAgileLoading();
            showCustomAlert('PLM Toolkit', 'Lookup failed: ' + err.message);
        });
}

function doAgileLookup() {
    var fileInput = document.getElementById('agileFileInput');
    var manualItems = document.getElementById('agileManualItems').value.trim();
    var hasFile = fileInput && fileInput.files && fileInput.files.length > 0;

    if (hasFile) {
        var file = fileInput.files[0];
        var name = file.name.toLowerCase();

        // Text/CSV file: read items from file, combine with typed items, use typeahead fields
        if (name.endsWith('.txt') || name.endsWith('.csv')) {
            if (agileSelectedFields.length === 0) {
                showCustomAlert('PLM Toolkit', 'Please select at least one field name when uploading a text/CSV file.');
                return;
            }
            var reader = new FileReader();
            reader.onload = function(e) {
                var fileItems = e.target.result.split(/[\r\n,]+/).map(function(s) { return s.trim(); }).filter(function(s) { return s.length > 0; });
                var allItems = fileItems.join(',');
                if (manualItems) allItems = manualItems + ',' + allItems;
                doAgileManualLookup(allItems, agileSelectedFields);
            };
            reader.readAsText(file);
            return;
        }

        // Excel file: items in col A, field names in row 1 headers
        if (!name.endsWith('.xlsx') && !name.endsWith('.xls')) {
            showCustomAlert('PLM Toolkit', 'Please upload an Excel (.xlsx), text (.txt), or CSV (.csv) file.');
            return;
        }
    } else {
        // No file — manual input mode
        if (manualItems && agileSelectedFields.length > 0) {
            doAgileManualLookup(manualItems, agileSelectedFields);
            return;
        }
        if (manualItems && agileSelectedFields.length === 0) {
            showCustomAlert('PLM Toolkit', 'Please select at least one field name.');
            return;
        }
        showCustomAlert('PLM Toolkit', 'Please enter item numbers and select fields, or upload a file.');
        return;
    }

    // Excel upload path (file already captured above)
    doAgileExcelUpload(file, null);
}

/**
 * Upload an xlsx for Agile Lookup. Pulled out of doAgileLookup() so we can
 * retry the same file with a chosen itemColumn after the user picks one from
 * the disambiguation modal — see agileShowColumnPickModal().
 */
function doAgileExcelUpload(file, itemColumn) {
    showAgileLoading();
    var formData = new FormData();
    formData.append('file', file);
    if (itemColumn !== null && itemColumn !== undefined) {
        formData.append('itemColumn', String(itemColumn));
    }
    fetch('/api/agile-lookup/upload', { method: 'POST', body: formData })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            hideAgileLoading();
            // The companion service couldn't decide which column has the part
            // numbers — show the user the candidates and let them pick.
            if (data.needsColumnPick) {
                agileShowColumnPickModal(file, data);
                return;
            }
            if (data.error) {
                document.getElementById('agileNoResultsMsg').textContent = data.error;
                document.getElementById('agileNoResults').style.display = 'block';
                document.getElementById('agileEmptyState').style.display = 'none';
                return;
            }
            agileResults = data.results || [];
            agileColumns = data.columns || [];
            agileColumnFilters = {};
            updateAgileChips(data);
            if (agileResults.length === 0) {
                document.getElementById('agileNoResults').style.display = 'block';
                document.getElementById('agileEmptyState').style.display = 'none';
                hideAgileInsightStrip();
            } else {
                renderAgileTable();
                document.getElementById('agileTableWrapper').style.display = 'block';
                document.getElementById('agileEmptyState').style.display = 'none';
                document.getElementById('agileNoResults').style.display = 'none';
                fillAgileInsightStrip(agileResults, agileColumns, data);

                // Show warning if any fields weren't found
                if (data.fieldWarning) {
                    showCustomAlert('Field Name Warning', data.fieldWarning +
                        '\n\nThese columns show "Field Not Present" for all items. ' +
                        'Please check the field names in your Excel header row for typos.');
                }
            }
        })
        .catch(function(err) {
            hideAgileLoading();
            showCustomAlert('PLM Toolkit', 'Lookup failed: ' + err.message);
        });
}

/**
 * Show a modal asking the user which column has the part numbers to look up.
 * Fired when the backend returned {needsColumnPick: true, candidates: [...]}.
 * On pick, re-uploads the same File object with the chosen column index.
 */
function agileShowColumnPickModal(file, payload) {
    var existing = document.getElementById('agileColPickModal');
    if (existing) existing.remove();
    var candidates = payload.candidates || [];
    var headerRow = payload.headerRowIndex;
    var html =
        '<div id="agileColPickModal" style="position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:1100; display:flex; align-items:center; justify-content:center;">' +
          '<div style="background:#fff; width:96%; max-width:720px; border-radius:8px; border:1px solid #E8E6DF; box-shadow:0 10px 40px rgba(0,0,0,0.2);">' +
            '<div style="padding:16px 20px; border-bottom:1px solid #E8E6DF;">' +
              '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:18px; font-weight:600; color:#0F1720;">Which column has the part numbers?</div>' +
              '<div style="font-size:12px; color:#6B7280; margin-top:4px;">More than one column in your file looks like it could hold the item numbers to look up. Pick the one we should use.</div>' +
              '<div style="font-size:11px; color:#6B7280; margin-top:2px;">Header row detected at row ' + (headerRow + 1) + ' (1-based).</div>' +
            '</div>' +
            '<div style="padding:12px 20px;">';
    candidates.forEach(function(c, idx) {
        var samples = (c.samples || []).join('  ·  ') || '(no sample values)';
        html +=
          '<button onclick="agilePickColumn(' + c.colIndex + ')" style="display:block; width:100%; text-align:left; margin-bottom:10px; padding:12px 14px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; cursor:pointer; transition:background 0.1s;" onmouseover="this.style.background=\'#f0f4f9\'" onmouseout="this.style.background=\'#FAFAF7\'">' +
            '<div style="font-weight:600; color:#0F1720; font-size:13px;">' + esc(c.header || '(unnamed column ' + (c.colIndex + 1) + ')') + '</div>' +
            '<div style="font-size:11px; color:#6B7280; margin-top:2px;">Column ' + columnLetter(c.colIndex) + '  ·  score ' + c.score + '</div>' +
            '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; color:#4a6fa5; margin-top:6px;">' + esc(samples) + '</div>' +
          '</button>';
    });
    html +=
              '<div style="text-align:right; margin-top:8px;">' +
                '<button onclick="agileColPickCancel()" style="background:none; border:1px solid #E8E6DF; color:#6B7280; padding:8px 14px; border-radius:6px; font-size:13px; cursor:pointer;">Cancel</button>' +
              '</div>' +
            '</div>' +
          '</div>' +
        '</div>';
    var wrap = document.createElement('div');
    wrap.innerHTML = html;
    document.body.appendChild(wrap.firstChild);
    // Stash the file so the click handler can re-upload it.
    window._agilePendingFile = file;
}

window.agilePickColumn = function(colIndex) {
    var file = window._agilePendingFile;
    var modal = document.getElementById('agileColPickModal');
    if (modal) modal.remove();
    window._agilePendingFile = null;
    if (file) doAgileExcelUpload(file, colIndex);
};

window.agileColPickCancel = function() {
    var modal = document.getElementById('agileColPickModal');
    if (modal) modal.remove();
    window._agilePendingFile = null;
};

function columnLetter(idx) {
    var s = '';
    var n = idx;
    while (n >= 0) {
        s = String.fromCharCode(65 + (n % 26)) + s;
        n = Math.floor(n / 26) - 1;
    }
    return s;
}

function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function doAgileClear() {
    document.getElementById('agileFileInput').value = '';
    document.getElementById('agileManualItems').value = '';
    document.getElementById('agileManualFields').value = '';
    agileSelectedFields = [];
    renderFieldTags();
    agileResults = [];
    agileColumns = [];
    agileColumnFilters = {};
    document.getElementById('agileStatusBar').style.display = 'none';
    document.getElementById('agileTableWrapper').style.display = 'none';
    document.getElementById('agileNoResults').style.display = 'none';
    document.getElementById('agileEmptyState').style.display = 'block';
}

function renderAgileTable() {
    var thead = document.getElementById('agileTableHead');
    var tbody = document.getElementById('agileResultsBody');
    thead.innerHTML = '';
    tbody.innerHTML = '';

    // Header row
    var headerTr = document.createElement('tr');
    agileColumns.forEach(function(col) {
        var th = document.createElement('th');
        th.textContent = col === '_STATUS' ? 'Status' : col;
        th.style.cursor = 'pointer';
        th.onclick = function() { sortAgileBy(col); };
        headerTr.appendChild(th);
    });
    thead.appendChild(headerTr);

    // Filter row
    var filterTr = document.createElement('tr');
    filterTr.className = 'filter-row';
    agileColumns.forEach(function(col, idx) {
        var td = document.createElement('td');
        if (idx === 0) {
            td.innerHTML = '<a href="#" onclick="clearAgileFilters(); return false;" title="Clear filters" style="color:#8bb8e8; font-size:11px; text-decoration:none;">&#10005;</a>';
        } else {
            var input = document.createElement('input');
            input.className = 'col-filter';
            input.setAttribute('data-col', col);
            input.placeholder = 'Filter (!exclude)';
            input.oninput = function() { applyAgileFilters(); };
            td.appendChild(input);
        }
        filterTr.appendChild(td);
    });
    thead.appendChild(filterTr);

    // Data rows
    var filtered = getFilteredAgileResults();
    filtered.forEach(function(rec) {
        var tr = document.createElement('tr');
        agileColumns.forEach(function(col) {
            var td = document.createElement('td');
            var val = rec[col] || '';
            td.textContent = val;
            if (col === '_STATUS') {
                td.style.fontWeight = '600';
                td.style.color = val.indexOf('OK') === 0 ? '#28a745' : '#dc3545';
            } else if (val === 'Field Not Present') {
                td.style.color = '#dc3545';
                td.style.fontStyle = 'italic';
                td.style.fontSize = '11px';
            }
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
}

function applyAgileFilters() {
    agileColumnFilters = {};
    document.querySelectorAll('#agileTableHead .col-filter').forEach(function(input) {
        var val = input.value.trim().toLowerCase();
        if (val) agileColumnFilters[input.getAttribute('data-col')] = val;
    });
    var tbody = document.getElementById('agileResultsBody');
    tbody.innerHTML = '';
    var filtered = getFilteredAgileResults();
    filtered.forEach(function(rec) {
        var tr = document.createElement('tr');
        agileColumns.forEach(function(col) {
            var td = document.createElement('td');
            var val = rec[col] || '';
            td.textContent = val;
            if (col === '_STATUS') {
                td.style.fontWeight = '600';
                td.style.color = val.indexOf('OK') === 0 ? '#28a745' : '#dc3545';
            } else if (val === 'Field Not Present') {
                td.style.color = '#dc3545';
                td.style.fontStyle = 'italic';
                td.style.fontSize = '11px';
            }
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
}

function clearAgileFilters() {
    agileColumnFilters = {};
    document.querySelectorAll('#agileTableHead .col-filter').forEach(function(i) { i.value = ''; });
    renderAgileTable();
}

function getFilteredAgileResults() {
    if (Object.keys(agileColumnFilters).length === 0) return agileResults;
    return agileResults.filter(function(rec) {
        for (var col in agileColumnFilters) {
            if (!matchesFilter(rec[col], agileColumnFilters[col])) return false;
        }
        return true;
    });
}

var agileSortCol = '';
var agileSortDir = 'asc';
function sortAgileBy(col) {
    if (agileSortCol === col) { agileSortDir = agileSortDir === 'asc' ? 'desc' : 'asc'; }
    else { agileSortCol = col; agileSortDir = 'asc'; }
    agileResults.sort(function(a, b) {
        var av = (a[col] || '').toLowerCase(), bv = (b[col] || '').toLowerCase();
        if (av < bv) return agileSortDir === 'asc' ? -1 : 1;
        if (av > bv) return agileSortDir === 'asc' ? 1 : -1;
        return 0;
    });
    renderAgileTable();
}

function updateAgileChips(data) {
    var chips = document.getElementById('agileChips');
    chips.innerHTML = '';
    var span = document.createElement('span');
    span.className = 'chip chip-count';
    span.textContent = data.totalCount + ' items \u2022 ' + data.queryTimeMs + 'ms';
    chips.appendChild(span);
    document.getElementById('agileStatusBar').style.display = 'flex';
}

function doAgileExport() {
    if (agileResults.length === 0) return;
    window.location.href = '/api/agile-lookup/export';
}

function doAgileEmail() {
    if (agileResults.length === 0) return;
    var btn = document.getElementById('agileEmailBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Sending...';
    fetch('/api/agile-lookup/email', { method: 'POST' })
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


// === Agile Insight Strip ===
function fillAgileInsightStrip(rows, columns, data) {
    var strip = document.getElementById('agileInsightStrip');
    if (!strip) return;
    strip.style.display = 'grid';

    // Items looked up
    document.getElementById('agileInsightItems').textContent = rows.length.toLocaleString();
    document.getElementById('agileInsightItemsSub').textContent = 'via ' + (data.mode === 'upload' ? 'Excel upload' : 'manual input');

    // Fields queried
    var fieldCount = columns.length;
    document.getElementById('agileInsightFields').textContent = fieldCount;
    document.getElementById('agileInsightFieldsSub').textContent = 'across Title Block, Page 2, Page 3';

    // Field Not Present count
    var notPresent = 0;
    rows.forEach(function(rec) {
        columns.forEach(function(col) {
            if (rec[col] === 'Field Not Present') notPresent++;
        });
    });
    var el = document.getElementById('agileInsightMissing');
    el.textContent = notPresent;
    document.getElementById('agileInsightMissingSub').textContent = notPresent > 0 ? 'cells marked red' : 'all fields found';

    // Source
    document.getElementById('agileInsightSource').textContent = 'Live';
}

function hideAgileInsightStrip() {
    var strip = document.getElementById('agileInsightStrip');
    if (strip) strip.style.display = 'none';
}
