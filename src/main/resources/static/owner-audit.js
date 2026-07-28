/* Owner Change Audit — Jimmy Sessumes's 2026-06-05 chat ask.
 *
 * Reads the toolkit's /api/ims-review/owner-audit endpoint which queries
 * Agile's ITEM_HISTORY for the IMS doc subclass, pairs each owner-cell
 * change with the toolkit's "Owners updated via PLM Toolkit …" marker
 * row (if present), and tags each row Toolkit / Direct in Agile.
 *
 * Auth: same gate as IMS Dashboard (admin or DCC grant). The tab is
 * exposed to anyone who can see IMS Dashboard so the audit follows the
 * same access boundary.
 */
(function () {
    'use strict';

    var STATE = {
        rows: [],
        filtered: [],
        meta: null,
        booted: false
    };

    function ownerAuditInit() {
        if (!STATE.booted) {
            wireUi();
            STATE.booted = true;
        }
        ownerAuditLoad();
    }

    function wireUi() {
        var searchEl = document.getElementById('ownerAuditSearch');
        if (searchEl) searchEl.addEventListener('input', debounce(function () { applyFilter(); render(); }, 180));
        var srcEl = document.getElementById('ownerAuditSourceFilter');
        if (srcEl) srcEl.addEventListener('change', function () { applyFilter(); render(); });
    }

    function ownerAuditLoad(force) {
        var daysEl = document.getElementById('ownerAuditDays');
        var days = daysEl ? daysEl.value : '90';
        showLoading(true);
        var url = '/api/ims-review/owner-audit?days=' + encodeURIComponent(days)
                + (force ? '&refresh=true' : '');
        fetch(url, { credentials: 'same-origin' })
            .then(function (r) {
                if (r.status === 403) throw new Error('Admin/DCC access required.');
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (d) {
                STATE.rows = d.rows || [];
                STATE.meta = d;
                showLoading(false);
                renderSummary();
                applyFilter();
                render();
            })
            .catch(function (e) {
                showLoading(false);
                STATE.rows = [];
                STATE.filtered = [];
                document.getElementById('ownerAuditCount').textContent = 'Failed: ' + e.message;
                render();
            });
    }

    function applyFilter() {
        var q = (document.getElementById('ownerAuditSearch').value || '').trim().toLowerCase();
        var srcEl = document.getElementById('ownerAuditSourceFilter');
        var srcFilter = srcEl ? srcEl.value : '';
        STATE.filtered = STATE.rows.filter(function (r) {
            if (srcFilter && r.source !== srcFilter) return false;
            if (q) {
                var hay = [r.docNumber, r.description, r.oldOwners, r.newOwners,
                           r.instigatedBy, r.agileUser]
                    .filter(Boolean).join(' ').toLowerCase();
                if (hay.indexOf(q) < 0) return false;
            }
            return true;
        });
        var cnt = document.getElementById('ownerAuditCount');
        if (cnt) {
            var line = STATE.filtered.length + ' / ' + STATE.rows.length + ' rows';
            var ca = STATE.meta && STATE.meta.cachedAt;
            if (ca) {
                var ageMin = Math.floor((Date.now() - ca) / 60000);
                line += ' · snapshot ' + (ageMin < 1 ? 'just now'
                                       : ageMin < 60 ? ageMin + 'm ago'
                                       : Math.floor(ageMin / 60) + 'h ' + (ageMin % 60) + 'm ago');
            }
            cnt.textContent = line;
        }
    }

    function renderSummary() {
        var box = document.getElementById('ownerAuditSummary');
        if (!box) return;
        var m = STATE.meta || {};
        function tile(label, val, color) {
            return '<div style="flex:1; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:10px 14px; text-align:left;">'
                 + '<div style="font-size:10px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">' + esc(label) + '</div>'
                 + '<div style="font-size:22px; font-weight:600; color:' + color + '; line-height:1.2;">' + esc(val.toLocaleString()) + '</div>'
                 + '</div>';
        }
        // Three buckets: toolkit with named user (post-marker), toolkit legacy
        // (Agile actor = administrator but no marker — we know it was the
        // toolkit, just not who clicked Save), and direct Agile UI changes.
        box.innerHTML =
            tile('Total changes', m.rowCount || 0, '#0F1720') +
            tile('Toolkit · with user', m.toolkitWithUser || 0, '#1F8A4C') +
            tile('Toolkit · legacy', m.toolkitLegacy || 0, '#4a6fa5') +
            tile('Direct in Agile', m.directInAgile || 0, '#C7801B');
    }

    function render() {
        var body = document.getElementById('ownerAuditBody');
        var empty = document.getElementById('ownerAuditEmpty');
        if (!body) return;
        if (!STATE.filtered.length) {
            body.innerHTML = '';
            empty.style.display = '';
            return;
        }
        empty.style.display = 'none';
        var html = STATE.filtered.map(function (r, i) {
            var bg = (i % 2 === 0) ? '#fff' : '#FAFAF7';
            var srcPill;
            var instigator;
            if (r.source === 'Toolkit') {
                srcPill = '<span style="background:#e8f5e9; color:#155724; padding:2px 8px; border-radius:10px; font-weight:600; font-size:10px;">Toolkit</span>';
                instigator = '<span style="color:#4a6fa5; font-weight:600;">' + esc(r.instigatedBy || '') + '</span>';
            } else if (r.source === 'Toolkit (legacy)') {
                srcPill = '<span title="Toolkit-driven (Agile actor is the SDK service account) but predates the marker that names the AD user. We know the toolkit did it; we don\'t know who clicked Save." style="background:#e8f0fe; color:#1a3a5c; padding:2px 8px; border-radius:10px; font-weight:600; font-size:10px;">Toolkit (legacy)</span>';
                instigator = '<span style="color:#6B7280; font-style:italic;">' + esc(r.instigatedBy || '') + '</span>';
            } else {
                srcPill = '<span style="background:#fff8e1; color:#856404; padding:2px 8px; border-radius:10px; font-weight:600; font-size:10px;">Direct in Agile</span>';
                instigator = '<span style="color:#0F1720; font-weight:500;">' + esc(r.instigatedBy || '') + '</span>';
            }
            return '<tr style="background:' + bg + '; border-bottom:1px solid #E8E6DF;">'
                 + '<td style="padding:7px 10px; vertical-align:top;">'
                 +   '<div style="font-family:monospace; font-weight:600; color:#4a6fa5;">' + esc(r.docNumber || '') + '</div>'
                 +   '<div style="color:#6B7280; font-size:11px; margin-top:1px; max-width:240px;">' + esc(r.description || '') + '</div>'
                 + '</td>'
                 + '<td style="padding:7px 10px; vertical-align:top; max-width:200px;">' + esc(r.oldOwners || '—') + '</td>'
                 + '<td style="padding:7px 10px; vertical-align:top; max-width:200px;">' + esc(r.newOwners || '—') + '</td>'
                 + '<td style="padding:7px 10px; vertical-align:top; font-family:\'IBM Plex Mono\',Consolas,monospace; color:#0F1720;">' + esc(r.changedAt || '') + '</td>'
                 + '<td style="padding:7px 10px; vertical-align:top;">' + srcPill + '</td>'
                 + '<td style="padding:7px 10px; vertical-align:top;">' + instigator + '</td>'
                 + '</tr>';
        }).join('');
        body.innerHTML = html;
    }

    function showLoading(on) {
        var l = document.getElementById('ownerAuditLoading');
        var g = document.getElementById('ownerAuditGrid');
        if (l) l.style.display = on ? '' : 'none';
        if (g) g.style.display = on ? 'none' : '';
    }

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function debounce(fn, wait) {
        var t;
        return function () {
            var ctx = this, args = arguments;
            clearTimeout(t);
            t = setTimeout(function () { fn.apply(ctx, args); }, wait);
        };
    }

    window.ownerAuditInit = ownerAuditInit;
    window.ownerAuditLoad = ownerAuditLoad;
})();
