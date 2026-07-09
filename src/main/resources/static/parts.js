// === Part Extract ===
var partsResults = [];
var partsColumns = [];
var partsAllHeaders = [];
var partsColumnsLoaded = false;
var partsColumnFilters = {};
var partsDateFilters = {}; // col -> { date1: true, date2: true } or null for all
var partsCurrentItems = '';
var partsDisplayColumns = []; // subset shown on screen
var MAX_DISPLAY_COLS = 8;
var DATE_COLUMNS = ['REV_RELEASE_DATE', 'CREATE_DATE', 'RELEASE_DATE', 'UPDATE_DATE'];

// Friendly display names for DB columns
var COLUMN_LABELS = {
    PART_NUMBER: 'Part Number', REV: 'Rev', PM: 'P/M', PRODUCTLINE: 'Product Line',
    HIST: 'History', CIS: 'CIS', CREATE_USER: 'Create User', EFF_DATE_CHANGE: 'Eff Date Change',
    FAMILYNAME: 'Family Name', CAPACITY: 'Capacity', GROUPV: 'Group', IMAGE: 'Image',
    MATL_COST: 'Material Cost', MC_DEPTH: 'MC Depth', MC_HEIGHT: 'MC Height', MC_QTY: 'MC Qty',
    MC_WEIGHT: 'MC Weight', MC_WIDTH: 'MC Width', NEW_EFF_DATE: 'New Eff Date',
    OLD_EFF_DATE: 'Old Eff Date', PRODUCTDIVISION: 'Product Division', PROMOGROUP: 'Promo Group',
    ROLLSTATUS: 'Roll Status', SIDENSITY: 'SI Density', SPDEPTH: 'SP Depth', SPHEIGHT: 'SP Height',
    SPWEIGHT: 'SP Weight', SPWIDTH: 'SP Width', STATUSCODE: 'Status Code',
    STATUSROLLDATE: 'Status Roll Date', SUBCONTRACTORS: 'Subcontractors',
    MATERIAL_GROUP: 'Material Group', UM: 'Unit of Measure', WEIGHTUM: 'Weight UM',
    CUSTOMER_PN: 'Customer P/N', GREEN_COMPLIANCE: 'Green Compliance',
    MATERIAL_TYPE: 'Material Type', ALT_BOM_NUMBER: 'Alt BOM Number',
    ALT_BOM_PARENT: 'Alt BOM Parent', CONTROLLER_TECHNOLOGY: 'Controller Technology',
    GROSS_DIE_WAFER: 'Gross Die/Wafer', PRODUCT_TYPE: 'Product Type',
    MARKETING_PROGRAM: 'Marketing Program', MEMORY_PIN_COUNT: 'Memory Pin Count',
    MEMORY_PACKAGE: 'Memory Package', CONTROLLER_PIN_COUNT: 'Controller Pin Count',
    CONTROLLER_PACKAGE: 'Controller Package', SIP_PIN_COUNT: 'SIP Pin Count',
    SIP_PACKAGE: 'SIP Package', DESCRIPTION: 'Description', RELEASE_DATE: 'Release Date',
    AS_SOLD: 'As Sold', MARKING_SPEC: 'Marking Spec', EAN: 'EAN',
    CARTON_AUM: 'Carton AUM', CARTON_QTY: 'Carton Qty', UPDATE_DATE: 'Update Date',
    PKG_TYPE: 'Package Type', PART_CLASS: 'Part Class', CATEGORY: 'Category', WIP: 'WIP',
    CUSTOM_REV: 'Custom Rev', DLE_MODEL_NAME: 'DLE Model Name',
    CUSTOMER_FIRMWARE_VERSION: 'Customer FW Version', MST_FIRMWARE_VERSION: 'MST FW Version',
    ATA_FW_IDENTIFY: 'ATA FW Identify', USER_CAPACITY_LBA: 'User Capacity LBA',
    OLD_MATERIAL_NUMBER: 'Old Material Number', MARKETING_PROGRAM_CATEGORY: 'Marketing Program Category',
    PRODUCT_CUSTOMER: 'Product Customer', PROGRAM_NAME: 'Program Name',
    IDB_PROGRAM: 'IDB Program', SOURCE_PART_NUMBER: 'Source Part Number',
    ACTUAL_BUILD_PLANT: 'Actual Build Plant', REGULATORY_COMPLIANCE: 'Regulatory Compliance',
    BOM_TYPE: 'BOM Type', GOLDEN_PART: 'Golden Part', KGD_TEST_FLOW: 'KGD Test Flow',
    ECCN: 'ECCN', STORAGE_CONDITION: 'Storage Condition', GLOBAL_OPN: 'Global OPN',
    MFG_ID: 'MFG ID', NEW_PART_CLASS: 'Item Type', CLASSIFICATION_PDM: 'Classification PDM',
    REV_RELEASE_DATE: 'Rev Release Date', CREATE_DATE: 'Create Date',
    LIFECYCLE_PHASE: 'Lifecycle Phase', SINGLE_SOLE_SOURCE: 'Single/Sole Source'
};

function colLabel(col) {
    return COLUMN_LABELS[col] || col;
}

var MONTH_MAP = {JAN:'01',FEB:'02',MAR:'03',APR:'04',MAY:'05',JUN:'06',JUL:'07',AUG:'08',SEP:'09',OCT:'10',NOV:'11',DEC:'12'};

// Normalize any date string to MM-DD-YYYY HH:MM:SS AM format
function normalizeDate(val) {
    if (!val || val.trim() === '') return '';
    val = val.trim();
    // Pattern 1: DD-MON-YYYY HH:MM:SS AM (e.g. "13-APR-2026 08:29:06 AM")
    var m1 = val.match(/^(\d{1,2})-([A-Z]{3})-(\d{4})\s+(.*)/i);
    if (m1) {
        var mm = MONTH_MAP[m1[2].toUpperCase()] || m1[2];
        var dd = m1[1].length === 1 ? '0' + m1[1] : m1[1];
        return mm + '-' + dd + '-' + m1[3] + ' ' + m1[4];
    }
    // Pattern 2: already MM-DD-YYYY ... — return as-is
    return val;
}

// Extract just the date portion as MM-DD-YYYY for filter keys, sortable
function normalizeDateKey(val) {
    if (!val || val.trim() === '') return '';
    val = val.trim();
    // Pattern 1: DD-MON-YYYY
    var m1 = val.match(/^(\d{1,2})-([A-Z]{3})-(\d{4})/i);
    if (m1) {
        var mm = MONTH_MAP[m1[2].toUpperCase()] || m1[2];
        var dd = m1[1].length === 1 ? '0' + m1[1] : m1[1];
        return mm + '-' + dd + '-' + m1[3];
    }
    // Pattern 2: MM-DD-YYYY
    var m2 = val.match(/^(\d{2})-(\d{2})-(\d{4})/);
    if (m2) return m2[1] + '-' + m2[2] + '-' + m2[3];
    return val.substring(0, 10).trim();
}

var PARTS_DEFAULT_COLS = [
    'PART_NUMBER', 'REV', 'STATUSCODE', 'LIFECYCLE_PHASE',
    'NEW_PART_CLASS', 'PRODUCTLINE', 'REV_RELEASE_DATE', 'CREATE_DATE',
    'SUBCONTRACTORS', 'MATERIAL_TYPE'
];

// === Load available columns on tab switch ===
function loadPartsColumns() {
    if (partsColumnsLoaded) return;
    fetch('/api/parts/columns')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            partsAllHeaders = data.columns || [];
            buildColumnPicker();
            partsColumnsLoaded = true;
            if (data.dataAsOf) {
                document.getElementById('partsDataAsOf').style.display = 'block';
                document.getElementById('partsDataAsOfText').textContent =
                    'Part data current as of: ' + data.dataAsOf;
            }
        })
        .catch(function() {});
}

function buildColumnPicker() {
    var avail = document.getElementById('availableFields');
    var displayed = document.getElementById('displayedFields');
    avail.innerHTML = '';
    displayed.innerHTML = '';

    // Default displayed columns in order
    var displayedSet = new Set(PARTS_DEFAULT_COLS);

    // Add defaults to displayed list (in order)
    PARTS_DEFAULT_COLS.forEach(function(col) {
        if (col === 'PART_NUMBER') return;
        var opt = document.createElement('option');
        opt.value = col;
        opt.textContent = colLabel(col);
        displayed.appendChild(opt);
    });

    // Add remaining to available list
    partsAllHeaders.forEach(function(col) {
        if (col === 'PART_NUMBER') return;
        if (displayedSet.has(col)) return;
        var opt = document.createElement('option');
        opt.value = col;
        opt.textContent = colLabel(col);
        avail.appendChild(opt);
    });
}

function moveFieldsRight() {
    var avail = document.getElementById('availableFields');
    var displayed = document.getElementById('displayedFields');
    Array.from(avail.selectedOptions).forEach(function(opt) {
        displayed.appendChild(opt);
    });
}

function moveFieldsLeft() {
    var avail = document.getElementById('availableFields');
    var displayed = document.getElementById('displayedFields');
    Array.from(displayed.selectedOptions).forEach(function(opt) {
        avail.appendChild(opt);
    });
    // Re-sort available alphabetically
    var opts = Array.from(avail.options);
    opts.sort(function(a, b) { return a.value.localeCompare(b.value); });
    avail.innerHTML = '';
    opts.forEach(function(o) { avail.appendChild(o); });
}

function moveFieldUp() {
    var sel = document.getElementById('displayedFields');
    var selected = Array.from(sel.selectedOptions);
    for (var i = 0; i < selected.length; i++) {
        var idx = selected[i].index;
        if (idx > 0) {
            sel.insertBefore(selected[i], sel.options[idx - 1]);
        }
    }
}

function moveFieldDown() {
    var sel = document.getElementById('displayedFields');
    var selected = Array.from(sel.selectedOptions);
    for (var i = selected.length - 1; i >= 0; i--) {
        var idx = selected[i].index;
        if (idx < sel.options.length - 1) {
            sel.insertBefore(sel.options[idx + 1], selected[i]);
        }
    }
}

function getSelectedColumns() {
    var cols = ['PART_NUMBER'];
    var displayed = document.getElementById('displayedFields');
    if (displayed) {
        Array.from(displayed.options).forEach(function(opt) {
            cols.push(opt.value);
        });
    }
    return cols;
}

// === Search ===
function doPartsSearch() {
    var items = document.getElementById('partsItemInput').value.trim();
    var fileInput = document.getElementById('partsFileInput');
    var relFrom = document.getElementById('partsRelFrom').value;
    var relTo = document.getElementById('partsRelTo').value;
    var columns = getSelectedColumns();

    if (fileInput.files && fileInput.files.length > 0) {
        doPartsFileUpload(fileInput.files[0], columns, relFrom, relTo);
        return;
    }

    if (!items && !relFrom && !relTo) {
        document.getElementById('partsEmptyState').style.display = 'block';
        document.getElementById('partsTableWrapper').style.display = 'none';
        document.getElementById('partsNoResults').style.display = 'none';
        return;
    }

    partsCurrentItems = items || '*';
    partsColumns = columns;
    showPartsLoading();

    // PT-85: POST the payload as JSON instead of stuffing it into a query
    // string. Long part lists (Jimmy: 404 parts) used to blow Tomcat's 8KB
    // header buffer and bounce with a 400 before the controller ran.
    var body = {
        items: partsCurrentItems,
        columns: columns.join(','),
        releaseDateFrom: relFrom || null,
        releaseDateTo: relTo || null
    };

    fetch('/api/parts/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            partsResults = data.results || [];
            partsColumns = data.columns || columns;
            // Add DESCRIPTION to display columns if not present
            if (partsColumns.indexOf('DESCRIPTION') < 0) partsColumns.push('DESCRIPTION');
            partsColumnFilters = {};
            hidePartsLoading();
            updatePartsChips(data);
            if (partsResults.length === 0) { showPartsNoResults(); hidePartsInsightStrip(); }
            else { renderPartsTable(); showPartsTable(); fillPartsInsightStrip(partsResults, data); }
        })
        .catch(function(err) { hidePartsLoading(); showCustomAlert('PLM Toolkit','Search failed: ' + err.message); });
}

// Captured at upload time so the Enrich button reuses the user's chosen column.
var partsLastUploadFile = null;
var partsLastChosenColumn = null;
var partsLastEnrichArgs = null;

function doPartsFileUpload(file, columns, relFrom, relTo) {
    var err = validateUploadFile(file);
    if (err) { showCustomAlert('PLM Toolkit',err); return; }
    showPartsLoading();
    partsLastUploadFile = file;
    partsLastEnrichArgs = { columns: columns, relFrom: relFrom, relTo: relTo };

    smartUpload(file, {
        op: 'QUERY',
        proceed: function(itemCol) {
            partsLastChosenColumn = itemCol >= 0 ? itemCol : null;
            var formData = new FormData();
            formData.append('file', file);
            formData.append('columns', columns.join(','));
            if (relFrom) formData.append('releaseDateFrom', relFrom);
            if (relTo) formData.append('releaseDateTo', relTo);
            if (itemCol >= 0) formData.append('itemColumn', String(itemCol));

            fetch('/api/parts/upload', { method: 'POST', body: formData })
                .then(function(res) { return res.json(); })
                .then(function(data) {
                    partsResults = data.results || [];
                    partsColumns = data.columns || columns;
                    if (partsColumns.indexOf('DESCRIPTION') < 0) partsColumns.push('DESCRIPTION');
                    partsCurrentItems = data.items || '';
                    partsColumnFilters = {};
                    hidePartsLoading();
                    updatePartsChips(data);
                    if (partsResults.length === 0) { showPartsNoResults(); hidePartsInsightStrip(); }
                    else { renderPartsTable(); showPartsTable(); fillPartsInsightStrip(partsResults, data); }
                    var eb = document.getElementById('partsEnrichBtn');
                    if (eb) eb.style.display = partsLastUploadFile ? '' : 'none';
                })
                .catch(function(err) { hidePartsLoading(); showCustomAlert('PLM Toolkit','Upload failed: ' + err.message); });
        },
        onTooLarge: function(probe) { hidePartsLoading(); showCustomAlert('PLM Toolkit', probe.message); }
    });
}

function doPartsEnrich() {
    if (!partsLastUploadFile) {
        showCustomAlert('PLM Toolkit', 'Upload a file first \u2014 enrichment writes the matching part fields back into the original spreadsheet.');
        return;
    }
    var args = partsLastEnrichArgs || {};
    var cols = args.columns || [];
    if (!cols || cols.length === 0) {
        showCustomAlert('PLM Toolkit', 'Select at least one display field on the right before enriching.');
        return;
    }
    var btn = document.getElementById('partsEnrichBtn');
    var origText = btn ? btn.innerHTML : '';
    if (btn) { btn.disabled = true; btn.innerHTML = 'Building...'; }
    var extra = { columns: cols.join(',') };
    if (args.relFrom) extra.releaseDateFrom = args.relFrom;
    if (args.relTo)   extra.releaseDateTo   = args.relTo;
    if (partsLastChosenColumn != null) extra.itemColumn = partsLastChosenColumn;
    smartEnrich(partsLastUploadFile, {
        endpoint: '/api/parts/enrich',
        extraFields: extra,
        onDone: function() { if (btn) { btn.disabled = false; btn.innerHTML = origText; } }
    });
}

function doPartsClear() {
    document.getElementById('partsItemInput').value = '';
    document.getElementById('partsFileInput').value = '';
    document.getElementById('partsRelFrom').value = '';
    document.getElementById('partsRelTo').value = '';
    partsResults = [];
    partsColumnFilters = {};
    document.getElementById('partsStatusBar').style.display = 'none';
    document.getElementById('partsTableWrapper').style.display = 'none';
    document.getElementById('partsNoResults').style.display = 'none';
    document.getElementById('partsEmptyState').style.display = 'block';
}

// === Render Table ===
function renderPartsTable() {
    var thead = document.getElementById('partsTableHead');
    var tbody = document.getElementById('partsResultsBody');
    thead.innerHTML = '';
    tbody.innerHTML = '';

    // Limit on-screen columns; DESCRIPTION always included if selected
    partsDisplayColumns = partsColumns.slice(0, MAX_DISPLAY_COLS);
    // If DESCRIPTION is in full list but not in display, add it as last visible col
    if (partsColumns.indexOf('DESCRIPTION') >= 0 && partsDisplayColumns.indexOf('DESCRIPTION') < 0) {
        partsDisplayColumns[partsDisplayColumns.length - 1] = 'DESCRIPTION';
    }

    // Show truncation message
    var colMsg = document.getElementById('partsColTruncMsg');
    if (!colMsg) {
        colMsg = document.createElement('div');
        colMsg.id = 'partsColTruncMsg';
        colMsg.style.cssText = 'padding:8px 16px; font-size:12px; color:#856404; background:#fff3cd; border-radius:4px; margin-bottom:8px; display:none;';
        var wrapper = document.getElementById('partsTableWrapper');
        wrapper.insertBefore(colMsg, wrapper.firstChild);
    }
    if (partsColumns.length > MAX_DISPLAY_COLS) {
        colMsg.innerHTML = 'Showing <strong>' + partsDisplayColumns.length + ' of ' + partsColumns.length + '</strong> columns on screen. Use <strong>Export Excel</strong> or <strong>Email Me</strong> for all ' + partsColumns.length + ' columns.';
        colMsg.style.display = 'block';
    } else {
        colMsg.style.display = 'none';
    }

    // Header row
    var headerTr = document.createElement('tr');
    partsDisplayColumns.forEach(function(col) {
        var th = document.createElement('th');
        th.textContent = colLabel(col);
        th.style.cursor = 'pointer';
        th.onclick = function() { sortPartsBy(col); };
        headerTr.appendChild(th);
    });
    thead.appendChild(headerTr);

    // Filter row
    var filterTr = document.createElement('tr');
    filterTr.className = 'filter-row';
    partsDisplayColumns.forEach(function(col, idx) {
        var td = document.createElement('td');
        if (idx === 0) {
            td.innerHTML = '<a href="#" onclick="clearPartsFilters(); return false;" title="Clear filters" style="color:#8bb8e8; font-size:11px; text-decoration:none;">&#10005;</a>';
        } else if (DATE_COLUMNS.indexOf(col) >= 0) {
            // Date checkbox dropdown
            td.style.position = 'relative';
            var btnId = 'partsDateBtn_' + col;
            var ddId = 'partsDateDD_' + col;
            var cbId = 'partsDateCB_' + col;
            td.innerHTML =
                '<button class="col-filter date-filter-btn" id="' + btnId + '" onclick="togglePartsDateFilter(\'' + col + '\')" style="text-align:left;cursor:pointer;width:100%;">All dates &#9662;</button>' +
                '<div id="' + ddId + '" class="date-dropdown" style="display:none;">' +
                '<div class="date-dropdown-actions"><a href="#" onclick="partsDateSelectAll(\'' + col + '\');return false;">All</a> <a href="#" onclick="partsDateSelectNone(\'' + col + '\');return false;">None</a></div>' +
                '<div id="' + cbId + '" class="date-checkboxes"></div></div>';
        } else {
            var input = document.createElement('input');
            input.className = 'col-filter';
            input.setAttribute('data-col', col);
            input.placeholder = 'Filter (!exclude)';
            input.oninput = function() { applyPartsFilters(); };
            td.appendChild(input);
        }
        filterTr.appendChild(td);
    });
    thead.appendChild(filterTr);

    // Build date checkboxes for date columns
    partsDisplayColumns.forEach(function(col) {
        if (DATE_COLUMNS.indexOf(col) < 0) return;
        buildPartsDateCheckboxes(col);
    });

    // Data rows
    var filtered = getFilteredPartsResults();
    updatePartsFilterCount(filtered.length, partsResults.length);
    filtered.forEach(function(rec) {
        var tr = document.createElement('tr');
        partsDisplayColumns.forEach(function(col) {
            var td = document.createElement('td');
            var val = rec[col] || '';

            // Item number in mono font
            if (col === 'PART_NUMBER' || col === 'ITEM_NUMBER' || col === 'NUMBER') {
                td.style.fontFamily = 'var(--font-mono)';
                td.style.fontSize = '12.5px';
                td.style.fontWeight = '500';
            }

            // Lifecycle/status as v2 chips
            if (col === 'STATUSCODE' || col === 'LIFECYCLE_PHASE') {
                var s = val.toUpperCase();
                var cls = 'v2-life';
                if (s === 'ACT' || s === 'C-ACT') cls += ' act';
                else if (s.indexOf('OBS') >= 0 || s === 'EOL') cls += ' eol';
                else if (s === 'DEV' || s === 'MKT' || s === 'PEND' || s === 'CPROD') cls += ' pend';
                td.innerHTML = val ? '<span class="' + cls + '">' + esc(val) + '</span>' : '';
            } else {
                td.textContent = (DATE_COLUMNS.indexOf(col) >= 0) ? normalizeDate(val) : val;
            }

            // Date columns in mono
            if (DATE_COLUMNS.indexOf(col) >= 0) {
                td.style.fontFamily = 'var(--font-mono)';
                td.style.fontSize = '12px';
            }

            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
}

// === Column Filters ===
function applyPartsFilters() {
    partsColumnFilters = {};
    document.querySelectorAll('#partsTableHead .col-filter').forEach(function(input) {
        var val = input.value.trim().toLowerCase();
        if (val) partsColumnFilters[input.getAttribute('data-col')] = val;
    });
    // Re-render just the body
    var tbody = document.getElementById('partsResultsBody');
    tbody.innerHTML = '';
    var filtered = getFilteredPartsResults();
    updatePartsFilterCount(filtered.length, partsResults.length);
    filtered.forEach(function(rec) {
        var tr = document.createElement('tr');
        partsDisplayColumns.forEach(function(col) {
            var td = document.createElement('td');
            var val = rec[col] || '';
            td.textContent = (DATE_COLUMNS.indexOf(col) >= 0) ? normalizeDate(val) : val;
            if (col === 'STATUSCODE' || col === 'LIFECYCLE_PHASE') {
                if (val.indexOf('OBS') >= 0) td.style.color = '#dc3545';
                else if (val === 'ACT') td.style.color = '#28a745';
                else if (val === 'MKT') td.style.color = '#4a6fa5';
                td.style.fontWeight = '600';
            }
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
}

function clearPartsFilters() {
    partsColumnFilters = {};
    partsDateFilters = {};
    document.querySelectorAll('#partsTableHead .col-filter:not(.date-filter-btn)').forEach(function(i) { i.value = ''; });
    renderPartsTable();
}

function getFilteredPartsResults() {
    var hasColFilters = Object.keys(partsColumnFilters).length > 0;
    var hasDateFilters = false;
    for (var dc in partsDateFilters) {
        if (partsDateFilters[dc] !== null && partsDateFilters[dc] !== undefined) { hasDateFilters = true; break; }
    }
    if (!hasColFilters && !hasDateFilters) return partsResults;
    return partsResults.filter(function(rec) {
        for (var col in partsColumnFilters) {
            if (!matchesFilter(rec[col], partsColumnFilters[col])) return false;
        }
        for (var dc in partsDateFilters) {
            var sel = partsDateFilters[dc];
            if (sel !== null && sel !== undefined) {
                var dateKey = getPartsDateKey(rec[dc]);
                if (!sel[dateKey]) return false;
            }
        }
        return true;
    });
}

// === Sort ===
var partsSortCol = '';
var partsSortDir = 'asc';

function sortPartsBy(col) {
    if (partsSortCol === col) {
        partsSortDir = partsSortDir === 'asc' ? 'desc' : 'asc';
    } else {
        partsSortCol = col;
        partsSortDir = 'asc';
    }
    partsResults.sort(function(a, b) {
        var aVal = (a[col] || '').toLowerCase();
        var bVal = (b[col] || '').toLowerCase();
        if (aVal < bVal) return partsSortDir === 'asc' ? -1 : 1;
        if (aVal > bVal) return partsSortDir === 'asc' ? 1 : -1;
        return 0;
    });
    renderPartsTable();
}

// === Export & Email ===
// PT-85: both Export and Email now POST a JSON body instead of building a
// query string. The old query-string form bounced at Tomcat's 8KB header
// buffer for any list > ~300 part numbers (Jimmy hit it at 404 parts).
function partsRequestBody() {
    var columns = getSelectedColumns();
    var relFrom = document.getElementById('partsRelFrom').value;
    var relTo = document.getElementById('partsRelTo').value;
    return {
        items: partsCurrentItems,
        columns: columns.join(','),
        releaseDateFrom: relFrom || null,
        releaseDateTo: relTo || null
    };
}

function doPartsExport() {
    guardExport(function() {
        if (!partsCurrentItems) return;
        fetch('/api/parts/export', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(partsRequestBody())
            })
            .then(function(r) {
                if (!r.ok) {
                    return r.text().then(function(t) {
                        var msg;
                        try { msg = JSON.parse(t).message || JSON.parse(t).error || ('HTTP ' + r.status); }
                        catch (e) { msg = 'HTTP ' + r.status; }
                        throw new Error(msg);
                    });
                }
                return r.blob().then(function(blob) {
                    var cd = r.headers.get('Content-Disposition') || '';
                    var m = cd.match(/filename="?([^";]+)"?/);
                    var fname = m ? m[1] : 'Part-Extract.xlsx';
                    var url = URL.createObjectURL(blob);
                    var a = document.createElement('a');
                    a.href = url; a.download = fname;
                    document.body.appendChild(a); a.click();
                    document.body.removeChild(a); URL.revokeObjectURL(url);
                });
            })
            .catch(function(err) { showCustomAlert('PLM Toolkit', 'Export failed: ' + err.message); });
    });
}

function doPartsEmail() {
    if (!partsCurrentItems) return;
    var btn = document.getElementById('partsEmailBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Sending...';

    fetch('/api/parts/email', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(partsRequestBody())
        })
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
                showCustomAlert('PLM Toolkit',data.message || 'Failed.');
            }
        })
        .catch(function(err) { btn.disabled = false; btn.innerHTML = origText; showCustomAlert('PLM Toolkit','Failed: ' + err.message); });
}

// === Chips ===
function updatePartsChips(data) {
    var chips = document.getElementById('partsChips');
    chips.innerHTML = '';
    if (partsCurrentItems) {
        var label = partsCurrentItems.length > 40 ? partsCurrentItems.substring(0, 40) + '...' : partsCurrentItems;
        addPartsChip(chips, 'Items: ' + label);
    }
    addPartsChip(chips, partsColumns.length + ' columns');
    var countText = data.totalCount + ' parts';
    if (data.queryTimeMs) countText += ' \u2022 ' + data.queryTimeMs + 'ms';
    addPartsChip(chips, countText, 'chip-count');
    if (data.truncated) {
        addPartsChip(chips, 'Results truncated at 50,000 rows. Use Export Excel for available data.', 'chip-warn');
    }
    if (data.itemColumn) {
        var ic = data.itemColumn;
        var methodLabel = ic.method === 'header-match'  ? 'header'
                       : ic.method === 'ai-fallback'    ? 'AI'
                       : ic.method === 'user-override'  ? 'you picked'
                       : ic.method === 'default-col-a'  ? 'default A'
                       : ic.method;
        addPartsChip(chips, 'Item column: ' + ic.letter + ' (' + (ic.header || '\u2014') + ') \u00b7 via ' + methodLabel, 'chip-info');
    }
    if (typeof data.uniqueItems === 'number' && typeof data.inputRows === 'number'
            && data.uniqueItems > 0 && data.inputRows > data.uniqueItems) {
        addPartsChip(chips, data.uniqueItems + ' unique items from ' + data.inputRows + ' rows', 'chip-info');
    }
    document.getElementById('partsStatusBar').style.display = 'flex';
}

function updatePartsFilterCount(shown, total) {
    var el = document.getElementById('partsFilterCount');
    if (!el) {
        el = document.createElement('span');
        el.id = 'partsFilterCount';
        el.className = 'chip chip-warn';
        el.style.display = 'none';
        var chips = document.getElementById('partsChips');
        if (chips) chips.appendChild(el);
    }
    if (shown < total) {
        el.textContent = 'Showing ' + shown + ' of ' + total + ' rows';
        el.style.display = '';
    } else {
        el.style.display = 'none';
    }
}

function addPartsChip(container, text, extraClass) {
    var span = document.createElement('span');
    span.className = 'chip' + (extraClass ? ' ' + extraClass : '');
    span.textContent = text;
    container.appendChild(span);
}

// === UI State ===
var partsSearchStart = 0;
var partsProgressPoller = null;

function startPartsProgressPolling() {
    if (partsProgressPoller) clearInterval(partsProgressPoller);
    partsProgressPoller = setInterval(function() {
        fetch('/api/parts/progress')
            .then(function(res) { return res.json(); })
            .then(function(data) {
                var text = document.getElementById('partsLoadingText');
                if (text && data.running && data.rowCount > 0) {
                    text.textContent = 'Found ' + data.rowCount.toLocaleString() + ' parts so far...';
                }
            })
            .catch(function() {});
    }, 1000);
}

function stopPartsProgressPolling() {
    if (partsProgressPoller) { clearInterval(partsProgressPoller); partsProgressPoller = null; }
}

function showPartsLoading() {
    document.getElementById('partsLoading').style.display = 'block';
    document.getElementById('partsEmptyState').style.display = 'none';
    document.getElementById('partsNoResults').style.display = 'none';
    document.getElementById('partsTableWrapper').style.display = 'none';
    document.getElementById('partsStatusBar').style.display = 'none';
    partsSearchStart = startAdaptiveProgress('parts', 'partsLoadingText', 'partsProgressBar', 'partsProgressPct', 'partsProgressWrap', 'Searching parts...');
    startPartsProgressPolling();
}
function hidePartsLoading() {
    stopPartsProgressPolling();
    stopAdaptiveProgress('parts', partsSearchStart, 'partsProgressBar', 'partsProgressPct');
    document.getElementById('partsLoading').style.display = 'none';
}
function showPartsTable() {
    document.getElementById('partsTableWrapper').style.display = 'block';
    document.getElementById('partsNoResults').style.display = 'none';
    document.getElementById('partsEmptyState').style.display = 'none';
}
function showPartsNoResults() {
    document.getElementById('partsNoResults').style.display = 'block';
    document.getElementById('partsTableWrapper').style.display = 'none';
    document.getElementById('partsEmptyState').style.display = 'none';
}

// === Parts Date Filter ===
function getPartsDateKey(val) {
    return normalizeDateKey(val);
}

function buildPartsDateCheckboxes(col) {
    var container = document.getElementById('partsDateCB_' + col);
    if (!container) return;
    var dates = {};
    partsResults.forEach(function(rec) {
        var key = getPartsDateKey(rec[col]);
        if (key) dates[key] = true;
    });
    // Sort by YYYY-MM-DD descending (newest first)
    var sorted = Object.keys(dates).sort(function(a, b) {
        // Convert MM-DD-YYYY to YYYY-MM-DD for proper sort
        var toSortable = function(d) {
            var p = d.split('-');
            return p.length === 3 ? p[2] + '-' + p[0] + '-' + p[1] : d;
        };
        return toSortable(b).localeCompare(toSortable(a));
    });
    container.innerHTML = '';
    var selectedDates = partsDateFilters[col];
    sorted.forEach(function(dateStr) {
        var label = document.createElement('label');
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = selectedDates === null || selectedDates === undefined || (selectedDates && selectedDates[dateStr]);
        cb.value = dateStr;
        cb.setAttribute('data-col', col);
        cb.onchange = function() { onPartsDateChange(col); };
        label.appendChild(cb);
        label.appendChild(document.createTextNode(' ' + dateStr));
        container.appendChild(label);
    });
    updatePartsDateBtn(col);
}

function togglePartsDateFilter(col) {
    var dd = document.getElementById('partsDateDD_' + col);
    dd.style.display = dd.style.display === 'none' ? 'block' : 'none';
}

function onPartsDateChange(col) {
    var container = document.getElementById('partsDateCB_' + col);
    var checkboxes = container.querySelectorAll('input[type="checkbox"]');
    var allChecked = true;
    var selected = {};
    checkboxes.forEach(function(cb) {
        if (cb.checked) { selected[cb.value] = true; }
        else { allChecked = false; }
    });
    partsDateFilters[col] = allChecked ? null : selected;
    updatePartsDateBtn(col);
    applyPartsFilters();
}

function partsDateSelectAll(col) {
    partsDateFilters[col] = null;
    var container = document.getElementById('partsDateCB_' + col);
    container.querySelectorAll('input[type="checkbox"]').forEach(function(cb) { cb.checked = true; });
    updatePartsDateBtn(col);
    applyPartsFilters();
}

function partsDateSelectNone(col) {
    partsDateFilters[col] = {};
    var container = document.getElementById('partsDateCB_' + col);
    container.querySelectorAll('input[type="checkbox"]').forEach(function(cb) { cb.checked = false; });
    updatePartsDateBtn(col);
    applyPartsFilters();
}

function updatePartsDateBtn(col) {
    var btn = document.getElementById('partsDateBtn_' + col);
    if (!btn) return;
    var selected = partsDateFilters[col];
    if (selected === null || selected === undefined) {
        btn.innerHTML = 'All dates &#9662;';
        btn.style.background = '#2c3e50';
    } else {
        var count = Object.keys(selected).length;
        if (count === 0) {
            btn.innerHTML = '0 dates &#9662; (click to restore)';
            btn.style.background = '#dc3545';
        } else {
            btn.innerHTML = count + ' date' + (count !== 1 ? 's' : '') + ' &#9662;';
            btn.style.background = '#2c3e50';
        }
    }
}

// Close date dropdowns when clicking outside
document.addEventListener('click', function(e) {
    DATE_COLUMNS.forEach(function(col) {
        var dd = document.getElementById('partsDateDD_' + col);
        var btn = document.getElementById('partsDateBtn_' + col);
        if (dd && dd.style.display !== 'none' && !dd.contains(e.target) && e.target !== btn) {
            dd.style.display = 'none';
        }
    });
});

// Enter key in parts input triggers search
document.addEventListener('DOMContentLoaded', function() {
    setTimeout(function() {
        var input = document.getElementById('partsItemInput');
        if (input) {
            input.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') { e.preventDefault(); doPartsSearch(); }
            });
        }
    }, 100);
});

// === Parts Insight Strip ===
function fillPartsInsightStrip(rows, data) {
    var strip = document.getElementById('partsInsightStrip');
    if (!strip) return;
    strip.style.display = 'grid';

    // Rows returned
    document.getElementById('partsInsightRows').textContent = rows.length.toLocaleString();
    document.getElementById('partsInsightRowsSub').textContent = partsCurrentItems ? 'matching query' : '';

    // Unique item types
    var types = {};
    var typeCol = partsColumns.indexOf('ITEM_TYPE') >= 0 ? 'ITEM_TYPE' : (partsColumns.indexOf('CATEGORY') >= 0 ? 'CATEGORY' : null);
    if (typeCol) {
        rows.forEach(function(r) { var t = r[typeCol]; if (t) types[t] = (types[t] || 0) + 1; });
    }
    var typeCount = Object.keys(types).length;
    document.getElementById('partsInsightTypes').textContent = typeCount > 0 ? typeCount : '—';
    // Find largest type
    var topType = '', topTypeCount = 0;
    for (var t in types) { if (types[t] > topTypeCount) { topType = t; topTypeCount = types[t]; } }
    document.getElementById('partsInsightTypesSub').textContent = topType ? 'largest: ' + topType + ' (' + topTypeCount + ')' : '';

    // Columns selected
    var totalCols = partsColumns.length;
    var displayCols = partsDisplayColumns.length;
    var el = document.getElementById('partsInsightCols');
    el.innerHTML = totalCols + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">selected</span>';
    document.getElementById('partsInsightColsSub').textContent = displayCols + ' on screen, ' + (totalCols - displayCols) + ' Excel only';

    // Query time
    var ms = data.queryTimeMs || data.elapsed || 0;
    var timeEl = document.getElementById('partsInsightTime');
    if (ms > 1000) {
        timeEl.innerHTML = (ms / 1000).toFixed(1) + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">sec</span>';
    } else {
        timeEl.innerHTML = ms + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">ms</span>';
    }
}

function hidePartsInsightStrip() {
    var strip = document.getElementById('partsInsightStrip');
    if (strip) strip.style.display = 'none';
}
