// === smart-upload.js ===
// Shared helper used by every upload-and-query tab (BOM, Part Extract, SKU, Change History).
//
// Flow:
//   1. POST the file to /api/upload/probe.
//   2. If the probe says "tooLarge"   -> show the friendly message via showCustomAlert and stop.
//   3. If the probe says "confident"  -> call opts.proceed(itemColumnIndex).
//   4. If the probe says "uncertain"  -> open #columnPickerModal so the user picks the column,
//      then call opts.proceed(itemColumnIndex) with whatever they chose.
//
// The "real" upload still goes through the page's existing endpoint (/api/bom/upload, etc.) —
// this helper only owns the column-detection / heap-preflight / column-picker glue. Each page
// passes its own `proceed(col)` callback that does the actual fetch.

(function() {
    'use strict';
    if (window.smartUpload) return;  // idempotent — index.html could load this twice in dev

    function probeFile(file, op) {
        var fd = new FormData();
        fd.append('file', file);
        if (op) fd.append('op', op);
        return fetch('/api/upload/probe', { method: 'POST', body: fd })
            .then(typeof checkAuth === 'function' ? checkAuth : function(r) { return r; })
            .then(function(res) { return res.json(); });
    }

    // The modal element is created lazily on first use so we don't pollute index.html
    // for every page.
    function ensureModal() {
        var m = document.getElementById('columnPickerModal');
        if (m) return m;
        m = document.createElement('div');
        m.id = 'columnPickerModal';
        m.className = 'modal-overlay';
        m.style.display = 'none';
        m.innerHTML =
            '<div class="modal-content" style="max-width:760px;">' +
            '  <div class="modal-header">' +
            '    <h2>Which column has the item number?</h2>' +
            '    <button class="modal-close" onclick="smartUpload._closeModal()">&times;</button>' +
            '  </div>' +
            '  <div class="modal-body">' +
            '    <p id="cpFileInfo" style="font-size:13px; color:#444; margin:0 0 12px;"></p>' +
            '    <p style="font-size:12px; color:#6B7280; margin:0 0 8px;">' +
            '      We could not confidently identify the item-number column. Pick the column ' +
            '      that holds your part / SKU / item numbers and we will use that.</p>' +
            '    <div style="max-height:340px; overflow:auto; border:1px solid #E8E6DF; border-radius:6px;">' +
            '      <table id="cpTable" style="width:100%; border-collapse:collapse; font-size:12.5px;">' +
            '        <thead>' +
            '          <tr style="background:#2c3e50; color:white;">' +
            '            <th style="padding:6px 8px; text-align:left; width:32px;"></th>' +
            '            <th style="padding:6px 8px; text-align:left; width:48px;">Col</th>' +
            '            <th style="padding:6px 8px; text-align:left;">Header</th>' +
            '            <th style="padding:6px 8px; text-align:left;">Sample values</th>' +
            '          </tr>' +
            '        </thead>' +
            '        <tbody id="cpTableBody"></tbody>' +
            '      </table>' +
            '    </div>' +
            '    <div style="display:flex; gap:8px; justify-content:flex-end; margin-top:14px;">' +
            '      <button class="btn-clear" onclick="smartUpload._closeModal()">Cancel</button>' +
            '      <button class="btn-search" id="cpUseBtn">Use this column</button>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(m);
        m.addEventListener('click', function(e) {
            if (e.target === m) closeModal();
        });
        return m;
    }

    function closeModal() {
        var m = document.getElementById('columnPickerModal');
        if (m) m.style.display = 'none';
    }

    function showPicker(file, probe, onConfirm) {
        var m = ensureModal();
        var info = document.getElementById('cpFileInfo');
        var bytes = file.size;
        var size = bytes > 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(1) + ' MB' : Math.round(bytes / 1024) + ' KB';
        var rowText = probe.totalRows && probe.totalRows > 0 ? (probe.totalRows + ' rows') : 'rows in this sheet';
        info.innerHTML = '<strong>' + escapeHtml(file.name) + '</strong> &middot; ' +
            'Sheet: <strong>' + escapeHtml(probe.sourceSheet || '—') + '</strong> &middot; ' +
            rowText + ' &middot; ' + size;

        var body = document.getElementById('cpTableBody');
        body.innerHTML = '';
        var cols = probe.columns || [];
        var defaultIdx = typeof probe.suggestedColumn === 'number' ? probe.suggestedColumn : 0;
        cols.forEach(function(c) {
            var tr = document.createElement('tr');
            var checked = c.index === defaultIdx ? ' checked' : '';
            var samples = (c.sample || []).filter(function(s) { return s != null && String(s).length > 0; });
            var sampleStr = samples.slice(0, 3).join('  |  ');
            if (sampleStr.length > 200) sampleStr = sampleStr.substring(0, 200) + '…';
            tr.innerHTML =
                '<td style="padding:6px 8px;"><input type="radio" name="cpRadio" value="' + c.index + '"' + checked + '></td>' +
                '<td style="padding:6px 8px; font-family:var(--font-mono); font-weight:600;">' + escapeHtml(c.letter) + '</td>' +
                '<td style="padding:6px 8px;">' + escapeHtml(c.header || '') + '</td>' +
                '<td style="padding:6px 8px; font-family:var(--font-mono); font-size:11.5px; color:#444;">' +
                  escapeHtml(sampleStr) + '</td>';
            // Make the whole row clickable.
            tr.style.cursor = 'pointer';
            tr.addEventListener('click', function(e) {
                if (e.target.tagName !== 'INPUT') {
                    var input = tr.querySelector('input[type="radio"]');
                    if (input) input.checked = true;
                }
            });
            body.appendChild(tr);
        });

        var useBtn = document.getElementById('cpUseBtn');
        useBtn.onclick = function() {
            var sel = document.querySelector('input[name="cpRadio"]:checked');
            if (!sel) {
                if (typeof showCustomAlert === 'function') showCustomAlert('PLM Toolkit', 'Pick a column first.');
                return;
            }
            closeModal();
            onConfirm(parseInt(sel.value, 10));
        };

        m.style.display = 'flex';
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function(c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    /**
     * Public entry point.
     *
     * @param {File}     file     the File from <input type=file> or drag-drop
     * @param {Object}   opts
     * @param {string}   opts.op  'QUERY' (default) or 'ENRICH' — sent to the heap-guard
     * @param {Function} opts.proceed   called with the chosen 0-indexed item column;
     *                                   for confident probes this fires immediately
     * @param {Function} [opts.onProbe] called with the probe payload before any decision —
     *                                   useful for showing a "detected col X" chip
     * @param {Function} [opts.onTooLarge] called instead of showing the default alert
     */
    function smartUpload(file, opts) {
        opts = opts || {};
        if (!file) return;
        if (typeof opts.proceed !== 'function') {
            console.error('[smart-upload] opts.proceed is required');
            return;
        }

        // Plain-text inputs (.csv, .txt) skip the probe — there's nothing to detect, the
        // legacy parser already handles single-column line files.
        var name = (file.name || '').toLowerCase();
        if (!name.endsWith('.xlsx') && !name.endsWith('.xls')) {
            opts.proceed(-1);  // -1 means "no override — let the backend handle it"
            return;
        }

        probeFile(file, opts.op || 'QUERY')
            .then(function(probe) {
                if (typeof opts.onProbe === 'function') opts.onProbe(probe);
                if (probe.error) {
                    if (typeof showCustomAlert === 'function') showCustomAlert('PLM Toolkit', probe.error);
                    return;
                }
                if (probe.tooLarge) {
                    if (typeof opts.onTooLarge === 'function') opts.onTooLarge(probe);
                    else if (typeof showCustomAlert === 'function') showCustomAlert('PLM Toolkit', probe.message);
                    return;
                }
                if (probe.confident) {
                    var idx = probe.column && typeof probe.column.index === 'number' ? probe.column.index : -1;
                    opts.proceed(idx);
                    return;
                }
                // Uncertain — pop the picker.
                showPicker(file, probe, function(chosen) { opts.proceed(chosen); });
            })
            .catch(function(err) {
                console.error('[smart-upload] probe failed', err);
                // Fall back to no override — legacy detector still runs server-side.
                opts.proceed(-1);
            });
    }

    smartUpload._closeModal = closeModal;

    /**
     * Public enrich helper. Runs the same probe → modal flow, then POSTs to the supplied
     * endpoint and triggers a browser download of the resulting blob.
     *
     * @param {File}    file
     * @param {Object}  opts
     * @param {string}  opts.endpoint        e.g. '/api/parts/enrich'
     * @param {Object}  [opts.extraFields]   extra form fields to append (e.g. {columns: 'PART_NUMBER,DESCRIPTION'})
     * @param {string}  [opts.downloadName]  fallback download filename if Content-Disposition is missing
     * @param {Function}[opts.onStart]       called when the request fires (e.g. to disable the button)
     * @param {Function}[opts.onDone]        called when the download completes (success or failure)
     */
    function smartEnrich(file, opts) {
        opts = opts || {};
        if (!file) return;
        if (!opts.endpoint) {
            console.error('[smart-enrich] opts.endpoint is required');
            return;
        }
        smartUpload(file, {
            op: 'ENRICH',
            proceed: function(itemCol) {
                if (typeof opts.onStart === 'function') opts.onStart();
                var fd = new FormData();
                fd.append('file', file);
                if (itemCol >= 0) fd.append('itemColumn', String(itemCol));
                if (opts.extraFields) {
                    Object.keys(opts.extraFields).forEach(function(k) {
                        var v = opts.extraFields[k];
                        if (v != null) fd.append(k, String(v));
                    });
                }
                fetch(opts.endpoint, { method: 'POST', body: fd })
                    .then(function(res) {
                        if (!res.ok) {
                            return res.text().then(function(t) {
                                throw new Error(t || ('HTTP ' + res.status));
                            });
                        }
                        var disp = res.headers.get('Content-Disposition') || '';
                        var m = disp.match(/filename="?([^";]+)"?/i);
                        var filename = m ? m[1] : (opts.downloadName ||
                            (file.name.replace(/\.[^.]+$/, '') + ' (enriched).xlsx'));
                        return res.blob().then(function(blob) { return { blob: blob, filename: filename }; });
                    })
                    .then(function(out) {
                        var url = URL.createObjectURL(out.blob);
                        var a = document.createElement('a');
                        a.href = url; a.download = out.filename;
                        document.body.appendChild(a); a.click(); a.remove();
                        URL.revokeObjectURL(url);
                        if (typeof opts.onDone === 'function') opts.onDone(true);
                    })
                    .catch(function(err) {
                        if (typeof showCustomAlert === 'function')
                            showCustomAlert('PLM Toolkit', 'Enrichment failed: ' + err.message);
                        if (typeof opts.onDone === 'function') opts.onDone(false);
                    });
            }
        });
    }

    window.smartUpload = smartUpload;
    window.smartEnrich = smartEnrich;
})();
