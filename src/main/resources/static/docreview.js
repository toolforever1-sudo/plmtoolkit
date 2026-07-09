// =====================================================================
//  docreview.js — Review Tracker tab.
//
//  Documents whose Next Review Date is within the selected window, excluding
//  OBS / OBS-SKU / Preliminary lifecycle phases. Source SQL:
//  documents_next_review_search.sql. Server endpoint: /api/doc-review/data.
//
//  Behaviour mirrors the new Overdue Tracker pattern:
//    - tab click does NOT auto-run the query
//    - first visit shows a "Click ↻ Refresh to load" empty state
//    - Refresh re-runs the query and re-paints
//    - changing the window dropdown doesn't auto-fire; user clicks Refresh
// =====================================================================

(function () {
    'use strict';

    var _state = { loaded: false, rows: [], lastQs: '', meta: {} };
    var _sort = { key: 'nextReviewDate', dir: 'asc' };
    var _columnFilters = {};
    var _debounceTimer = null;

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function readWindow() {
        var winEl = document.getElementById('docReviewWindow');
        var fromEl = document.getElementById('docReviewFrom');
        var toEl   = document.getElementById('docReviewTo');
        var window = winEl ? winEl.value : '30d';
        var from = (window === 'custom' && fromEl) ? fromEl.value : '';
        var to   = (window === 'custom' && toEl)   ? toEl.value   : '';
        return { window: window, from: from, to: to };
    }
    function windowQs(w) {
        var parts = ['window=' + encodeURIComponent(w.window)];
        if (w.from) parts.push('from=' + encodeURIComponent(w.from));
        if (w.to)   parts.push('to='   + encodeURIComponent(w.to));
        return '?' + parts.join('&');
    }

    window.docReviewOnWindowChange = function () {
        var winEl = document.getElementById('docReviewWindow');
        var fromEl = document.getElementById('docReviewFrom');
        var toEl   = document.getElementById('docReviewTo');
        var sepEl  = document.getElementById('docReviewSep');
        var custom = winEl && winEl.value === 'custom';
        var disp = custom ? '' : 'none';
        if (fromEl) fromEl.style.display = disp;
        if (toEl)   toEl.style.display   = disp;
        if (sepEl)  sepEl.style.display  = disp;
        // PT-67 pattern: don't auto-fire. If a previous run is loaded, flag
        // the staleness so the user knows they need to click Refresh.
        if (_state.loaded) {
            var s = document.getElementById('docReviewStatus');
            if (s) s.innerHTML = '<span style="color:#C7801B;">Window changed — click <strong>↻ Refresh</strong> to re-query Agile.</span>';
        }
    };

    // Entry point: called from switchTab when 'docreview' is activated.
    // Does NOT fetch — server hit only happens on explicit Refresh.
    var _inputsCleared = false;
    function clearColumnInputsOnce() {
        // Browsers (Chrome especially) autofill empty text inputs from history /
        // password managers / extensions if any placeholder looks heuristically
        // form-like — which can populate a column filter the user never typed.
        // We blast them once on first tab visit so the initial render is clean.
        if (_inputsCleared) return;
        document.querySelectorAll('.doc-col-filter').forEach(function (i) { i.value = ''; });
        _columnFilters = {};
        _inputsCleared = true;
    }
    window.docReviewLoad = function () {
        clearColumnInputsOnce();
        if (_state.loaded) {
            renderAll();
            return;
        }
        showEmptyState();
    };

    window.docReviewRefresh = function () {
        var w = readWindow();
        var qs = windowQs(w);
        showLoading();
        // POST /refresh forces a re-query (bypasses the server cache). The cold
        // query is ~3 minutes on this dataset (the Document Owner LISTAGG join
        // is the bottleneck) — subsequent /data hits are sub-second cache hits.
        fetch('/api/doc-review/refresh' + qs, { method: 'POST', credentials: 'same-origin' })
            .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, body: j }; }); })
            .then(function (resp) {
                if (!resp.ok) {
                    hideLoading();
                    showCustomAlertMaybe('Review Tracker', 'Query failed: ' + (resp.body && resp.body.error ? resp.body.error : 'unknown'));
                    return;
                }
                _state.loaded = true;
                _state.rows = resp.body.rows || [];
                _state.lastQs = qs;
                _state.meta = resp.body;
                hideLoading();
                renderAll();
            })
            .catch(function (e) {
                hideLoading();
                showCustomAlertMaybe('Review Tracker', 'Query failed: ' + e.message);
            });
    };

    window.docReviewExportExcel = function () {
        var w = readWindow();
        // GET via top-level navigation so the browser downloads the xlsx.
        window.location = '/api/doc-review/export' + windowQs(w);
    };

    window.docReviewSortBy = function (key) {
        if (_sort.key === key) {
            _sort.dir = _sort.dir === 'asc' ? 'desc' : 'asc';
        } else {
            _sort.key = key;
            _sort.dir = 'asc';
        }
        renderTable();
    };

    window.debouncedDocReviewFilter = function () {
        if (_debounceTimer) clearTimeout(_debounceTimer);
        _debounceTimer = setTimeout(applyColumnFilters, 150);
    };

    window.clearDocReviewFilters = function () {
        document.querySelectorAll('.doc-col-filter').forEach(function (i) { i.value = ''; });
        _columnFilters = {};
        renderTable();
    };

    function applyColumnFilters() {
        _columnFilters = {};
        document.querySelectorAll('.doc-col-filter').forEach(function (i) {
            var v = (i.value || '').trim();
            if (v) _columnFilters[i.getAttribute('data-col')] = v.toLowerCase();
        });
        renderTable();
    }

    function filteredRows() {
        var rows = _state.rows;
        var keys = Object.keys(_columnFilters);
        if (keys.length === 0) return rows.slice();
        return rows.filter(function (r) {
            for (var i = 0; i < keys.length; i++) {
                var k = keys[i];
                var v = (r[k] || '').toString().toLowerCase();
                if (v.indexOf(_columnFilters[k]) < 0) return false;
            }
            return true;
        });
    }

    function sortRows(rows) {
        var k = _sort.key, dir = _sort.dir === 'asc' ? 1 : -1;
        return rows.slice().sort(function (a, b) {
            var av = (a[k] || '').toString();
            var bv = (b[k] || '').toString();
            if (av === bv) return 0;
            return av > bv ? dir : -dir;
        });
    }

    function renderAll() {
        renderStatus();
        renderInsightStrip();
        renderTable();
        paintSortIcons();
    }

    function renderStatus() {
        var s = document.getElementById('docReviewStatus');
        if (!s) return;
        var m = _state.meta || {};
        var desc = m.windowDescription || {};
        var parts = [];
        if (desc.label) parts.push(desc.label);
        if (desc.from && desc.to) parts.push(esc(desc.from) + ' → ' + esc(desc.to));
        else if (desc.to)         parts.push('through ' + esc(desc.to));
        parts.push((m.count || 0) + ' rows');
        s.textContent = parts.join(' · ');
    }

    function renderInsightStrip() {
        var strip = document.getElementById('docReviewInsightStrip');
        if (!strip) return;
        strip.style.display = _state.rows.length === 0 ? 'none' : 'grid';
        if (_state.rows.length === 0) return;
        var docSet = new Set();
        var overdue = 0;
        var today = new Date(); today.setHours(0,0,0,0);
        _state.rows.forEach(function (r) {
            if (r.itemNumber) docSet.add(r.itemNumber);
            if (r.nextReviewDate) {
                var d = new Date(r.nextReviewDate + 'T00:00:00');
                if (!isNaN(d.getTime()) && d <= today) overdue++;
            }
        });
        var set = function (id, val) {
            var el = document.getElementById(id);
            if (el) el.textContent = val;
        };
        var pendCount = _state.rows.filter(function (r) { return r.changeNumber; }).length;
        set('docReviewInsightDocs', docSet.size);
        set('docReviewInsightDocsSub', (_state.rows.length - docSet.size) + ' multi-change');
        set('docReviewInsightRows', _state.rows.length);
        set('docReviewInsightOverdue', overdue);
        var qt = _state.meta.queryTimeMs;
        set('docReviewInsightTime', qt != null ? (qt + ' ms') : '—');
        set('docReviewInsightDocsSub', pendCount + ' with pending');
    }

    function renderTable() {
        var rows = sortRows(filteredRows());
        var tbody = document.getElementById('docReviewBody');
        var emptyEl = document.getElementById('docReviewEmpty');
        var noResEl = document.getElementById('docReviewNoResults');
        var wrapEl  = document.getElementById('docReviewTableWrapper');
        if (typeof console !== 'undefined' && console.log) {
            console.log('[docreview] renderTable: loaded=' + _state.loaded
                      + ', stateRows=' + _state.rows.length
                      + ', filteredRows=' + rows.length
                      + ', filters=' + JSON.stringify(_columnFilters));
        }
        if (!_state.loaded) {
            showEmptyState();
            return;
        }
        if (emptyEl) emptyEl.style.display = 'none';
        if (rows.length === 0) {
            if (noResEl) noResEl.style.display = '';
            if (wrapEl) wrapEl.style.display = 'none';
            return;
        }
        if (noResEl) noResEl.style.display = 'none';
        if (wrapEl)  wrapEl.style.display = '';
        if (!tbody) return;
        var today = new Date(); today.setHours(0,0,0,0);
        var html = '';
        rows.forEach(function (r, i) {
            var bg = i % 2 === 0 ? '#FAFAF7' : '#fff';
            var nrd = r.nextReviewDate || '';
            var dateCellStyle = '';
            if (nrd) {
                var d = new Date(nrd + 'T00:00:00');
                if (!isNaN(d.getTime()) && d <= today) {
                    dateCellStyle = ' style="color:#B8342B; font-weight:600;"';
                }
            }
            var agileBase = (window.AGILE_WEBCLIENT_URL || 'https://plm.sandisk.com/Agile');
            var docLink = r.itemNumber
                ? '<a href="' + agileBase + '/object/Items/' + encodeURIComponent(r.itemNumber) + '" target="_blank" style="color:#4a6fa5; text-decoration:none;">' + esc(r.itemNumber) + '</a>'
                : '';
            var changeLink = r.changeNumber
                ? '<a href="' + agileBase + '/object/ECN/' + encodeURIComponent(r.changeNumber) + '" target="_blank" style="color:#4a6fa5; text-decoration:none;">' + esc(r.changeNumber) + '</a>'
                : '';
            html += '<tr style="background:' + bg + ';">'
                  + '<td style="font-family:monospace;">' + docLink + '</td>'
                  + '<td>' + esc(r.description) + '</td>'
                  + '<td>' + esc(r.lifecyclePhase) + '</td>'
                  + '<td>' + esc(r.rev) + '</td>'
                  + '<td>' + esc(r.documentType) + '</td>'
                  + '<td style="color:#4a6fa5; font-size:12px;">' + esc(r.owners) + '</td>'
                  + '<td' + dateCellStyle + '>' + esc(nrd) + '</td>'
                  + '<td style="font-family:monospace;">' + changeLink + '</td>'
                  + '<td>' + esc(r.changeType) + '</td>'
                  + '</tr>';
        });
        tbody.innerHTML = html;
    }

    function paintSortIcons() {
        document.querySelectorAll('.doc-sort-icon').forEach(function (el) { el.textContent = ''; });
        var hdr = document.querySelector('th[data-doc-sort="' + _sort.key + '"] .doc-sort-icon');
        if (hdr) hdr.textContent = _sort.dir === 'asc' ? ' ▲' : ' ▼';
    }

    function showLoading() {
        var loadEl = document.getElementById('docReviewLoading');
        var emptyEl = document.getElementById('docReviewEmpty');
        var noResEl = document.getElementById('docReviewNoResults');
        var wrapEl  = document.getElementById('docReviewTableWrapper');
        if (loadEl) loadEl.style.display = '';
        if (emptyEl) emptyEl.style.display = 'none';
        if (noResEl) noResEl.style.display = 'none';
        if (wrapEl)  wrapEl.style.display = 'none';
        var s = document.getElementById('docReviewStatus');
        if (s) s.textContent = 'Running Agile query…';
    }
    function hideLoading() {
        var loadEl = document.getElementById('docReviewLoading');
        if (loadEl) loadEl.style.display = 'none';
    }
    function showEmptyState() {
        var loadEl = document.getElementById('docReviewLoading');
        var emptyEl = document.getElementById('docReviewEmpty');
        var noResEl = document.getElementById('docReviewNoResults');
        var wrapEl  = document.getElementById('docReviewTableWrapper');
        var stripEl = document.getElementById('docReviewInsightStrip');
        if (loadEl) loadEl.style.display = 'none';
        if (emptyEl) emptyEl.style.display = '';
        if (noResEl) noResEl.style.display = 'none';
        if (wrapEl)  wrapEl.style.display = 'none';
        if (stripEl) stripEl.style.display = 'none';
    }
    function showCustomAlertMaybe(title, msg) {
        if (typeof window.showCustomAlert === 'function') {
            window.showCustomAlert(title, msg);
        } else {
            // fallback only if ui-modal helpers aren't loaded yet
            alert(title + ': ' + msg);
        }
    }
})();
