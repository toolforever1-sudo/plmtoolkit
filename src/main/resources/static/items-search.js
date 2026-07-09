// ============================================================
//  items-search.js  —  Items / Search sub-tab (PT-67)
//
//  Query builder over the Oracle item_extract table (~778K rows,
//  30 columns). Each row is connector + column + operator + value.
//  Operators are type-aware (string / categorical / date /
//  multi-value) and the column allow-list + per-type operator
//  list are fetched from /api/items-search/columns so the server
//  remains the source of truth.
//
//  Result table is sortable + per-column filterable (same pattern
//  as Review Tracker / Change History). Capped at 1000 rows on
//  screen; full match set goes to Excel.
// ============================================================
(function () {
    'use strict';

    var _state = {
        columns: [],                 // [{name,label,type,common,operators}]
        columnsByName: {},
        rows: [],                    // [{id, connector, column, operator, value, values}]
        nextRowId: 1,
        results: null,               // last run result
        resultColumns: null,         // user-picked or default
        sort: { col: null, dir: 'asc' },
        colFilters: {},              // {col: substr}
        // Checkbox popover state
        cbActiveRowId: null,
        cbAllValues: [],
        cbSelected: {},
        // Column picker draft state
        pickerDraft: {}
    };

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c];
        });
    }

    // ----------------------------------------------------------------------
    // Bootstrap — pull column metadata once per session
    // ----------------------------------------------------------------------
    var _initOnce = false;
    window.itemsSearchInit = function () {
        if (_initOnce) return;
        _initOnce = true;
        fetch('/api/items-search/columns', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                _state.columns = d.columns || [];
                _state.columns.forEach(function (c) { _state.columnsByName[c.name] = c; });
                // Seed with one empty row so the user has something to fill in
                if (_state.rows.length === 0) itemsSearchAddRow();
            })
            .catch(function (e) {
                document.getElementById('itemsSearchStatus').innerHTML =
                    '<span style="color:#B8342B;">Failed to load column metadata: ' + esc(e.message) + '</span>';
            });
    };

    // ----------------------------------------------------------------------
    // Builder — add / remove / render rows
    // ----------------------------------------------------------------------
    window.itemsSearchAddRow = function () {
        var first = _state.rows.length === 0;
        _state.rows.push({
            id: _state.nextRowId++,
            connector: first ? '' : 'AND',
            column: '',
            operator: '',
            value: '',
            values: null
        });
        renderRows();
    };

    window.itemsSearchClear = function () {
        _state.rows = [];
        _state.results = null;
        _state.resultColumns = null;
        _state.sort = { col: null, dir: 'asc' };
        _state.colFilters = {};
        document.getElementById('itemsSearchResultsWrap').style.display = 'none';
        document.getElementById('itemsSearchStatus').innerHTML = '';
        document.getElementById('itemsSearchExportBtn').disabled = true;
        itemsSearchAddRow();
    };

    window.itemsSearchRemoveRow = function (id) {
        _state.rows = _state.rows.filter(function (r) { return r.id !== id; });
        if (_state.rows.length === 0) itemsSearchAddRow();
        else {
            // Ensure first row has no connector
            _state.rows[0].connector = '';
            renderRows();
        }
    };

    function renderRows() {
        var box = document.getElementById('itemsSearchRows');
        if (!box) return;
        var html = '';
        _state.rows.forEach(function (r, idx) {
            html += renderRow(r, idx);
        });
        box.innerHTML = html;
    }

    function renderRow(r, idx) {
        var col = _state.columnsByName[r.column];
        var ops = col ? col.operators : [];
        var commonGrp = '<optgroup label="Common">';
        var moreGrp = '<optgroup label="More fields…">';
        _state.columns.forEach(function (c) {
            // PT-95: derived (Agile-sourced) columns are result-only — they can be
            // added to the results/export but not used as a filter condition.
            if (c.filterable === false) return;
            var sel = (c.name === r.column) ? ' selected' : '';
            var opt = '<option value="' + esc(c.name) + '"' + sel + '>' + esc(c.label) + '</option>';
            if (c.common) commonGrp += opt; else moreGrp += opt;
        });
        commonGrp += '</optgroup>';
        moreGrp += '</optgroup>';

        var opOptions = '<option value="">— operator —</option>';
        ops.forEach(function (op) {
            var sel = (op === r.operator) ? ' selected' : '';
            opOptions += '<option value="' + esc(op) + '"' + sel + '>' + esc(opLabel(op)) + '</option>';
        });

        var connector = '';
        if (idx === 0) {
            connector = '<span style="display:inline-block; width:72px; font-size:11px; color:#6B7280; text-transform:uppercase; padding:6px 10px;">Where</span>';
        } else {
            var aSel = r.connector === 'AND' ? ' selected' : '';
            var oSel = r.connector === 'OR'  ? ' selected' : '';
            connector =
                '<select data-rowid="' + r.id + '" onchange="itemsSearchOnConnector(' + r.id + ', this.value)" '
              + 'style="width:72px; padding:6px 8px; font-size:12px; font-weight:600; border:1px solid #E8E6DF; border-radius:4px; background:#fff;">'
              + '<option value="AND"' + aSel + '>AND</option>'
              + '<option value="OR"'  + oSel + '>OR</option>'
              + '</select>';
        }

        var html = '';
        html += '<div style="display:flex; gap:6px; align-items:center; margin-bottom:6px;">';
        html += connector;
        html += '<select onchange="itemsSearchOnColumn(' + r.id + ', this.value)" '
              + 'style="flex:0 0 200px; padding:6px 8px; font-size:12px; border:1px solid #E8E6DF; border-radius:4px; background:#fff;">'
              + '<option value="">— column —</option>'
              + commonGrp + moreGrp
              + '</select>';
        html += '<select onchange="itemsSearchOnOperator(' + r.id + ', this.value)" '
              + 'style="flex:0 0 150px; padding:6px 8px; font-size:12px; border:1px solid #E8E6DF; border-radius:4px; background:#fff;"'
              + (col ? '' : ' disabled') + '>'
              + opOptions + '</select>';
        html += renderValueInput(r, col);
        html += '<button onclick="itemsSearchRemoveRow(' + r.id + ')" '
              + 'title="Remove this condition" '
              + 'style="background:none; border:0; color:#B8342B; cursor:pointer; padding:6px 8px; font-size:14px;">&times;</button>';
        html += '</div>';
        return html;
    }

    function opLabel(op) {
        return ({
            'eq':'equals',
            'neq':'not equals',
            'contains':'contains',
            'starts_with':'starts with',
            'ends_with':'ends with',
            'is_empty':'is empty',
            'is_not_empty':'is not empty',
            'in_list':'in list…',
            'on_or_before':'on or before',
            'on_or_after':'on or after',
            'between':'between'
        })[op] || op;
    }

    function renderValueInput(r, col) {
        var common = 'flex:1; padding:6px 8px; font-size:12px; border:1px solid #E8E6DF; border-radius:4px;';
        if (!col || !r.operator) {
            return '<input type="text" disabled placeholder="…" style="' + common + ' background:#FAFAF7;">';
        }
        // Empty-check operators have no value
        if (r.operator === 'is_empty' || r.operator === 'is_not_empty') {
            return '<input type="text" disabled placeholder="(no value)" style="' + common + ' background:#FAFAF7;">';
        }
        // Date-range "between"
        if (r.operator === 'between') {
            var v = r.values || ['',''];
            return '<input type="date" value="' + esc(v[0]) + '" onchange="itemsSearchOnDateRange(' + r.id + ', 0, this.value)" style="' + common + '">'
                 + '<input type="date" value="' + esc(v[1]) + '" onchange="itemsSearchOnDateRange(' + r.id + ', 1, this.value)" style="' + common + '">';
        }
        // Date single
        if (col.type === 'DATE') {
            return '<input type="date" value="' + esc(r.value) + '" onchange="itemsSearchOnValue(' + r.id + ', this.value)" style="' + common + '">';
        }
        // In list (categorical)
        if (r.operator === 'in_list') {
            var n = r.values ? r.values.length : 0;
            var label = n === 0 ? 'Pick values…' : (n + ' value' + (n === 1 ? '' : 's') + ' selected');
            return '<button onclick="itemsSearchOpenCheckbox(' + r.id + ')" '
                 + 'style="' + common + ' background:#fff; cursor:pointer; text-align:left;">'
                 + '&#9776; ' + esc(label) + '</button>';
        }
        // Equals/neq on categorical → could be a quick dropdown, but keep
        // single text input for now (user can always switch to in_list).
        return '<input type="text" value="' + esc(r.value || '') + '" '
             + 'placeholder="' + esc(col.type === 'MULTI' ? 'e.g. C002' : 'value') + '" '
             + 'onchange="itemsSearchOnValue(' + r.id + ', this.value)" '
             + 'oninput="itemsSearchOnValue(' + r.id + ', this.value)" '
             + 'style="' + common + '">';
    }

    function findRow(id) {
        for (var i = 0; i < _state.rows.length; i++) if (_state.rows[i].id === id) return _state.rows[i];
        return null;
    }

    window.itemsSearchOnConnector = function (id, v) {
        var r = findRow(id); if (!r) return;
        r.connector = v;
    };

    window.itemsSearchOnColumn = function (id, v) {
        var r = findRow(id); if (!r) return;
        r.column = v;
        r.operator = '';
        r.value = '';
        r.values = null;
        renderRows();
    };

    window.itemsSearchOnOperator = function (id, v) {
        var r = findRow(id); if (!r) return;
        r.operator = v;
        // Reset value on operator change since the input type may differ
        r.value = '';
        r.values = null;
        renderRows();
    };

    window.itemsSearchOnValue = function (id, v) {
        var r = findRow(id); if (!r) return;
        r.value = v;
    };

    window.itemsSearchOnDateRange = function (id, idx, v) {
        var r = findRow(id); if (!r) return;
        if (!r.values) r.values = ['',''];
        r.values[idx] = v;
    };

    // ----------------------------------------------------------------------
    // Checkbox popover (for in_list operator)
    // ----------------------------------------------------------------------
    window.itemsSearchOpenCheckbox = function (id) {
        var r = findRow(id); if (!r) return;
        _state.cbActiveRowId = id;
        _state.cbSelected = {};
        (r.values || []).forEach(function (v) { _state.cbSelected[v] = true; });
        document.getElementById('itemsSearchCbTitle').textContent = 'Pick values — ' + (_state.columnsByName[r.column] || {}).label;
        document.getElementById('itemsSearchCbList').innerHTML = '<div style="color:#6B7280; padding:20px; text-align:center;">Loading…</div>';
        document.getElementById('itemsSearchCheckboxPopover').style.transform = 'translateX(0)';
        fetch('/api/items-search/distinct?column=' + encodeURIComponent(r.column), { credentials: 'same-origin' })
            .then(function (rr) { return rr.json(); })
            .then(function (d) {
                _state.cbAllValues = d.values || [];
                renderCheckboxList('');
            })
            .catch(function (e) {
                document.getElementById('itemsSearchCbList').innerHTML =
                    '<div style="color:#B8342B; padding:20px;">Failed: ' + esc(e.message) + '</div>';
            });
    };

    function renderCheckboxList(filter) {
        var box = document.getElementById('itemsSearchCbList');
        var lc = (filter || '').toLowerCase();
        var html = '';
        _state.cbAllValues.forEach(function (v) {
            if (lc && v.toLowerCase().indexOf(lc) < 0) return;
            var checked = _state.cbSelected[v] ? ' checked' : '';
            html += '<label style="display:flex; align-items:center; gap:8px; padding:4px 0; cursor:pointer;">'
                  + '<input type="checkbox" value="' + esc(v) + '"' + checked
                  + ' onchange="itemsSearchToggleCheckbox(this.value, this.checked)">'
                  + '<span>' + esc(v) + '</span></label>';
        });
        if (!html) html = '<div style="color:#6B7280; padding:20px; text-align:center;">No matches.</div>';
        box.innerHTML = html;
    }

    window.itemsSearchToggleCheckbox = function (v, on) {
        if (on) _state.cbSelected[v] = true;
        else delete _state.cbSelected[v];
    };

    window.itemsSearchFilterCheckbox = function (q) { renderCheckboxList(q); };

    window.itemsSearchApplyCheckbox = function () {
        var r = findRow(_state.cbActiveRowId); if (!r) { itemsSearchCloseCheckbox(); return; }
        r.values = Object.keys(_state.cbSelected);
        renderRows();
        itemsSearchCloseCheckbox();
    };

    window.itemsSearchCloseCheckbox = function () {
        document.getElementById('itemsSearchCheckboxPopover').style.transform = 'translateX(100%)';
    };

    // ----------------------------------------------------------------------
    // Run + render results
    // ----------------------------------------------------------------------
    window.itemsSearchRun = function () {
        var conditions = _state.rows.filter(function (r) {
            if (!r.column || !r.operator) return false;
            // Empty-check ops don't need a value
            if (r.operator === 'is_empty' || r.operator === 'is_not_empty') return true;
            if (r.operator === 'between')  return r.values && r.values[0] && r.values[1];
            if (r.operator === 'in_list')  return r.values && r.values.length > 0;
            return r.value && r.value.length > 0;
        });
        if (conditions.length === 0) {
            document.getElementById('itemsSearchStatus').innerHTML =
                '<span style="color:#C7801B;">Add at least one complete condition before running.</span>';
            return;
        }
        var btn = document.getElementById('itemsSearchRunBtn');
        btn.disabled = true; btn.textContent = '⌛ Running…';
        document.getElementById('itemsSearchStatus').innerHTML = '<span style="color:#6B7280;">Running query…</span>';

        var body = {
            conditions: conditions.map(function (c) {
                return {
                    connector: c.connector, column: c.column, operator: c.operator,
                    value: c.value || null, values: c.values || null
                };
            }),
            columns: _state.resultColumns || null
        };
        fetch('/api/items-search/run', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        })
        .then(function (r) { return r.json(); })
        .then(function (d) {
            btn.disabled = false; btn.innerHTML = '&#9654; Run Search';
            if (d.error) {
                document.getElementById('itemsSearchStatus').innerHTML =
                    '<span style="color:#B8342B;">' + esc(d.error) + '</span>';
                return;
            }
            _state.results = d;
            _state.resultColumns = d.columns;
            _state.sort = { col: null, dir: 'asc' };
            _state.colFilters = {};
            document.getElementById('itemsSearchExportBtn').disabled = false;
            renderResults();
            var trunc = d.truncated
                ? ' <span style="color:#C7801B; font-weight:600;">Showing first ' + d.returnedCount.toLocaleString() + ' — refine or export for the full set.</span>'
                : '';
            document.getElementById('itemsSearchStatus').innerHTML =
                '<span style="color:#6B7280;">Matched ' + d.matchedCount.toLocaleString()
              + ', returned ' + d.returnedCount.toLocaleString() + ' in ' + d.elapsedMs + ' ms.</span>' + trunc;
        })
        .catch(function (e) {
            btn.disabled = false; btn.innerHTML = '&#9654; Run Search';
            document.getElementById('itemsSearchStatus').innerHTML =
                '<span style="color:#B8342B;">Run failed: ' + esc(e.message) + '</span>';
        });
    };

    function renderResults() {
        var wrap = document.getElementById('itemsSearchResultsWrap');
        var tbl = document.getElementById('itemsSearchResultsTable');
        if (!_state.results) { wrap.style.display = 'none'; return; }
        wrap.style.display = '';
        document.getElementById('itemsSearchSummary').innerHTML =
            '<strong>' + _state.results.returnedCount.toLocaleString() + '</strong> of '
          + '<strong>' + _state.results.matchedCount.toLocaleString() + '</strong> matches';

        var cols = _state.resultColumns || [];
        var rows = _state.results.rows.slice();
        // Apply per-column filter
        var fk = Object.keys(_state.colFilters);
        if (fk.length > 0) {
            rows = rows.filter(function (r) {
                for (var i = 0; i < fk.length; i++) {
                    var k = fk[i];
                    if (String(r[k] || '').toLowerCase().indexOf(_state.colFilters[k]) < 0) return false;
                }
                return true;
            });
        }
        // Apply sort
        if (_state.sort.col) {
            var c = _state.sort.col, dir = _state.sort.dir === 'asc' ? 1 : -1;
            rows.sort(function (a, b) {
                var av = (a[c] || '').toString(), bv = (b[c] || '').toString();
                return av < bv ? -dir : av > bv ? dir : 0;
            });
        }

        var html = '<table style="width:100%; border-collapse:collapse; font-size:12px;">';
        html += '<thead><tr>';
        cols.forEach(function (c) {
            var arrow = _state.sort.col === c
                ? (_state.sort.dir === 'asc' ? ' ▲' : ' ▼') : '';
            html += '<th onclick="itemsSearchSort(\'' + c + '\')" '
                  + 'style="text-align:left; padding:8px 10px; background:#2c3e50; color:#fff; cursor:pointer; border-bottom:1px solid #444444; white-space:nowrap;">'
                  + esc(labelFor(c)) + arrow + '</th>';
        });
        html += '</tr><tr>';
        cols.forEach(function (c) {
            var v = _state.colFilters[c] || '';
            html += '<th style="padding:4px 6px; background:#FAFAF7; border-bottom:1px solid #cccccc;">'
                  + '<input type="text" value="' + esc(v) + '" placeholder="Filter…" '
                  + 'oninput="itemsSearchFilterCol(\'' + c + '\', this.value)" '
                  + 'style="width:100%; padding:3px 6px; border:1px solid #E8E6DF; border-radius:3px; font-size:11px;">'
                  + '</th>';
        });
        html += '</tr></thead><tbody>';
        rows.forEach(function (r, ri) {
            var bg = ri % 2 === 0 ? '#FAFAF7' : '#ffffff';
            html += '<tr style="background:' + bg + ';">';
            cols.forEach(function (c) {
                var v = r[c] == null ? '' : r[c];
                var cellStyle = 'padding:6px 10px; border-bottom:1px solid #cccccc;';
                if (c === 'PART_NUMBER') cellStyle += ' font-family:\'IBM Plex Mono\',Consolas,monospace; color:#4a6fa5; font-weight:600;';
                html += '<td style="' + cellStyle + '">' + esc(v) + '</td>';
            });
            html += '</tr>';
        });
        if (rows.length === 0) {
            html += '<tr><td colspan="' + cols.length + '" style="padding:20px; text-align:center; color:#6B7280;">No rows match the current column filters.</td></tr>';
        }
        html += '</tbody></table>';
        tbl.innerHTML = html;
    }

    function labelFor(name) {
        var c = _state.columnsByName[name];
        return c ? c.label : name;
    }

    window.itemsSearchSort = function (col) {
        if (_state.sort.col === col) {
            _state.sort.dir = _state.sort.dir === 'asc' ? 'desc' : 'asc';
        } else {
            _state.sort.col = col;
            _state.sort.dir = 'asc';
        }
        renderResults();
    };

    var _filterDebounce = null;
    window.itemsSearchFilterCol = function (col, val) {
        // Capture focus + cursor so re-render doesn't blow it away (same trick
        // as the admin-table filters in imsreview.js).
        var ae = document.activeElement;
        var saved = null;
        if (ae && ae.tagName === 'INPUT') {
            saved = { val: ae.value, start: ae.selectionStart, end: ae.selectionEnd, col: col };
        }
        if (_filterDebounce) clearTimeout(_filterDebounce);
        _filterDebounce = setTimeout(function () {
            var v = (val || '').trim().toLowerCase();
            if (v) _state.colFilters[col] = v;
            else delete _state.colFilters[col];
            renderResults();
            if (saved) {
                var inputs = document.querySelectorAll('#itemsSearchResultsTable input');
                for (var i = 0; i < inputs.length; i++) {
                    if (inputs[i].getAttribute('oninput') && inputs[i].getAttribute('oninput').indexOf("'" + saved.col + "'") >= 0) {
                        inputs[i].focus();
                        try { inputs[i].setSelectionRange(saved.start, saved.end); } catch (e) {}
                        break;
                    }
                }
            }
        }, 120);
    };

    // ----------------------------------------------------------------------
    // Column picker
    // ----------------------------------------------------------------------
    window.itemsSearchOpenColumnPicker = function () {
        var current = new Set(_state.resultColumns || []);
        _state.pickerDraft = {};
        var html = '';
        _state.columns.forEach(function (c) {
            var checked = current.has(c.name) ? ' checked' : '';
            _state.pickerDraft[c.name] = current.has(c.name);
            html += '<label style="display:flex; align-items:center; gap:8px; padding:3px 0; font-size:13px; cursor:pointer;">'
                  + '<input type="checkbox" data-col="' + esc(c.name) + '"' + checked
                  + ' onchange="itemsSearchTogglePicker(this.dataset.col, this.checked)">'
                  + '<span>' + esc(c.label) + '</span>'
                  + (c.common ? '' : '<span style="font-size:10px; color:#6B7280;">(more)</span>')
                  + '</label>';
        });
        document.getElementById('itemsSearchColumnPickerList').innerHTML = html;
        document.getElementById('itemsSearchColumnPicker').style.transform = 'translateX(0)';
    };

    window.itemsSearchTogglePicker = function (col, on) {
        _state.pickerDraft[col] = on;
    };

    window.itemsSearchApplyColumnPick = function () {
        var picked = [];
        _state.columns.forEach(function (c) {
            if (_state.pickerDraft[c.name]) picked.push(c.name);
        });
        if (picked.length === 0) picked.push('PART_NUMBER');
        // Auto-pin filtered columns so they're never hidden by accident
        _state.rows.forEach(function (r) {
            if (r.column && picked.indexOf(r.column) < 0) picked.push(r.column);
        });
        _state.resultColumns = picked;
        itemsSearchCloseColumnPicker();
        if (_state.results) {
            // Re-run so the new column set populates from the server (we don't
            // ship every column down on every run).
            itemsSearchRun();
        }
    };

    window.itemsSearchCloseColumnPicker = function () {
        document.getElementById('itemsSearchColumnPicker').style.transform = 'translateX(100%)';
    };

    // ----------------------------------------------------------------------
    // Export
    // ----------------------------------------------------------------------
    window.itemsSearchExport = function () {
        var conditions = _state.rows.filter(function (r) {
            if (!r.column || !r.operator) return false;
            if (r.operator === 'is_empty' || r.operator === 'is_not_empty') return true;
            if (r.operator === 'between')  return r.values && r.values[0] && r.values[1];
            if (r.operator === 'in_list')  return r.values && r.values.length > 0;
            return r.value && r.value.length > 0;
        });
        if (conditions.length === 0) return;
        var body = {
            conditions: conditions.map(function (c) {
                return {
                    connector: c.connector, column: c.column, operator: c.operator,
                    value: c.value || null, values: c.values || null
                };
            }),
            columns: _state.resultColumns || null
        };
        // Use fetch + blob download so we can POST a JSON body. Server sets
        // Content-Disposition so the filename is preserved.
        fetch('/api/items-search/export', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(function (r) {
            if (!r.ok) return r.json().then(function (j) { throw new Error(j.error || 'HTTP ' + r.status); });
            return r.blob().then(function (blob) {
                var cd = r.headers.get('Content-Disposition') || '';
                var m = cd.match(/filename="([^"]+)"/);
                var fname = m ? m[1] : 'Items-Search.xlsx';
                var url = URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.href = url; a.download = fname; document.body.appendChild(a); a.click();
                document.body.removeChild(a); URL.revokeObjectURL(url);
            });
        }).catch(function (e) {
            document.getElementById('itemsSearchStatus').innerHTML =
                '<span style="color:#B8342B;">Export failed: ' + esc(e.message) + '</span>';
        });
    };
})();
