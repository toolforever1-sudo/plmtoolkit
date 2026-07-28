// =====================================================================
//  overduetracker.js — Overdue Tracker sub-view inside the ECN Report.
//
//  Mirrors the Returns Tracker UX for a different population: open
//  Standard ECNs that are over their cycle-time target, with AI-categorized
//  reasons sourced from change_history.comments. Backed by
//  /api/ecn-report/overdue.
// =====================================================================

(function () {
    'use strict';

    var _state = { loaded: false, data: null, rangeKey: null };

    function readRange() {
        var minEl = document.getElementById('overdueMinOver');
        var maxEl = document.getElementById('overdueMaxOver');
        var min = minEl ? parseInt(minEl.value, 10) : 0;
        var max = maxEl ? parseInt(maxEl.value, 10) : 170;
        if (!isFinite(min) || min < 0) min = 0;
        if (!isFinite(max) || max < 1) max = 170;
        if (max <= min) max = min + 1;
        // Date Created picker (preset dropdown + optional custom from/to).
        var crEl   = document.getElementById('overdueCreatedRange');
        var cfEl   = document.getElementById('overdueCreatedFrom');
        var ctEl   = document.getElementById('overdueCreatedTo');
        var createdRange = crEl ? crEl.value : '';
        var createdFrom  = (createdRange === 'custom' && cfEl) ? cfEl.value : '';
        var createdTo    = (createdRange === 'custom' && ctEl) ? ctEl.value : '';
        // Date Released picker (mirror shape). Any non-empty value flips the tab
        // into retrospective mode server-side.
        var rrEl   = document.getElementById('overdueReleasedRange');
        var rfEl   = document.getElementById('overdueReleasedFrom');
        var rtEl   = document.getElementById('overdueReleasedTo');
        var releasedRange = rrEl ? rrEl.value : '';
        var releasedFrom  = (releasedRange === 'custom' && rfEl) ? rfEl.value : '';
        var releasedTo    = (releasedRange === 'custom' && rtEl) ? rtEl.value : '';
        // Classifications — collect checked chip values. Empty array means "server default".
        // Before chips have been rendered (very first load), fall back to localStorage
        // so the user's prior selection persists across page reloads.
        var classChecked = [];
        var chipBoxes = document.querySelectorAll('#overdueClassChips input[type="checkbox"]');
        if (chipBoxes.length > 0) {
            chipBoxes.forEach(function (cb) { if (cb.checked) classChecked.push(cb.value); });
        } else {
            try {
                var stored = JSON.parse(localStorage.getItem('overdueClassifications') || 'null');
                if (Array.isArray(stored)) classChecked = stored;
            } catch (e) {}
        }
        return { min: min, max: max,
                 createdRange: createdRange, createdFrom: createdFrom, createdTo: createdTo,
                 releasedRange: releasedRange, releasedFrom: releasedFrom, releasedTo: releasedTo,
                 classifications: classChecked };
    }
    function rangeQs(r) {
        var parts = ['minOver=' + r.min, 'maxOver=' + r.max];
        if (r.createdRange)  parts.push('createdRange='  + encodeURIComponent(r.createdRange));
        if (r.createdFrom)   parts.push('createdFrom='   + encodeURIComponent(r.createdFrom));
        if (r.createdTo)     parts.push('createdTo='     + encodeURIComponent(r.createdTo));
        if (r.releasedRange) parts.push('releasedRange=' + encodeURIComponent(r.releasedRange));
        if (r.releasedFrom)  parts.push('releasedFrom='  + encodeURIComponent(r.releasedFrom));
        if (r.releasedTo)    parts.push('releasedTo='    + encodeURIComponent(r.releasedTo));
        if (r.classifications && r.classifications.length)
            parts.push('classifications=' + encodeURIComponent(r.classifications.join(',')));
        return '?' + parts.join('&');
    }
    function rangeKey(r) {
        var classKey = (r.classifications || []).slice().sort().join(',');
        return r.min + '|' + r.max
             + '|' + (r.createdRange  || '') + '|' + (r.createdFrom  || '') + '|' + (r.createdTo  || '')
             + '|' + (r.releasedRange || '') + '|' + (r.releasedFrom || '') + '|' + (r.releasedTo || '')
             + '|' + classKey;
    }

    function _togglePickers(prefix) {
        var rangeEl = document.getElementById('overdue' + prefix + 'Range');
        var fromEl  = document.getElementById('overdue' + prefix + 'From');
        var toEl    = document.getElementById('overdue' + prefix + 'To');
        var sepEl   = document.getElementById('overdue' + prefix + 'Sep');
        var custom  = rangeEl && rangeEl.value === 'custom';
        var disp    = custom ? '' : 'none';
        if (fromEl) fromEl.style.display = disp;
        if (toEl)   toEl.style.display   = disp;
        if (sepEl)  sepEl.style.display  = disp;
    }
    window.overdueOnCreatedRangeChange  = function () { _togglePickers('Created'); };
    window.overdueOnReleasedRangeChange = function () { _togglePickers('Released'); };

    // === Classification chips =================================================
    // Renders one chip per classification in availableClassifications. Chips
    // present in defaultClassifications (Standard + PDR) are checked by default;
    // others are unchecked (reference-only). Persists user toggles in localStorage
    // so a refresh / tab revisit keeps the selection.
    function renderClassificationChips(data) {
        var host = document.getElementById('overdueClassChips');
        if (!host) return;
        var available = data.availableClassifications || [];
        var defaults  = data.defaultClassifications  || [];
        if (available.length === 0) {
            host.innerHTML = '<span style="color:#9CA3AF; font-size:11px;">No classifications in baselineTargets — run the ECN Dashboard once first.</span>';
            return;
        }
        // Restore previously-checked set from localStorage, or fall back to defaults.
        var stored = null;
        try {
            var raw = localStorage.getItem('overdueClassifications');
            if (raw) stored = JSON.parse(raw);
        } catch (e) {}
        var checked = (stored && Array.isArray(stored)) ? new Set(stored) : new Set(defaults);
        var counts = data.classificationCounts || {};
        var html = '';
        available.forEach(function (cls) {
            var isChecked = checked.has(cls);
            var isDefault = defaults.indexOf(cls) >= 0;
            var count = counts[cls] || 0;
            var bg = isChecked ? '#e8f0fe' : '#fff';
            var border = isChecked ? '#4a6fa5' : '#E8E6DF';
            var color = isChecked ? '#1a3a5c' : '#6B7280';
            var label = esc(cls) + (count ? ' <span style="opacity:0.6;">(' + count + ')</span>' : '')
                      + (isDefault ? '' : ' <span style="opacity:0.5; font-size:10px;">ref</span>');
            html += '<label style="display:inline-flex; align-items:center; gap:4px; padding:3px 8px; '
                  + 'background:' + bg + '; border:1px solid ' + border + '; border-radius:14px; '
                  + 'font-size:11.5px; color:' + color + '; cursor:pointer;">'
                  + '<input type="checkbox" value="' + esc(cls) + '" '
                  + (isChecked ? 'checked' : '') + ' onchange="overdueOnClassChange()" '
                  + 'style="margin:0; cursor:pointer;">'
                  + label + '</label>';
        });
        host.innerHTML = html;
    }
    window.overdueOnClassChange = function () {
        var picked = [];
        document.querySelectorAll('#overdueClassChips input[type="checkbox"]:checked').forEach(function (cb) {
            picked.push(cb.value);
        });
        try { localStorage.setItem('overdueClassifications', JSON.stringify(picked)); } catch (e) {}
        // Re-paint chip styling immediately so toggles feel responsive.
        document.querySelectorAll('#overdueClassChips label').forEach(function (lab) {
            var cb = lab.querySelector('input[type="checkbox"]');
            if (!cb) return;
            lab.style.background = cb.checked ? '#e8f0fe' : '#fff';
            lab.style.borderColor = cb.checked ? '#4a6fa5' : '#E8E6DF';
            lab.style.color = cb.checked ? '#1a3a5c' : '#6B7280';
        });
    };
    window.overdueResetClassifications = function () {
        try { localStorage.removeItem('overdueClassifications'); } catch (e) {}
        // Re-render chips from cached data, then trigger a refresh.
        if (_state.data) renderClassificationChips(_state.data);
        if (typeof window.overdueRefresh === 'function') window.overdueRefresh();
    };

    // Wire the Export button (defined globally so the HTML onclick can find it).
    window.overdueExportExcel = function () {
        var r = readRange();
        // Browsers download via a navigation, so let the GET fire as a top-level link.
        window.location = '/api/ecn-report/overdue/export' + rangeQs(r);
    };

    function fmtDate(iso) {
        if (!iso) return '—';
        try {
            var d = new Date(iso);
            return d.toLocaleString();
        } catch (e) { return iso; }
    }

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function renderKpiTiles(data) {
        var retro = data.mode === 'retrospective';
        var countLabel = retro ? 'Released & overdue' : 'Open & overdue';
        var tiles = [
            { label: countLabel, value: data.count, tone: data.count > 0 ? 'warn' : 'ok' },
            { label: 'Avg days over target', value: data.avgDaysOverTarget != null ? data.avgDaysOverTarget : '—', tone: 'muted' },
            { label: 'Total day-debt', value: data.totalDaysOverTarget != null ? data.totalDaysOverTarget : '—', tone: 'muted' },
            { label: 'AI categorized', value: (data.ecnsCategorized || 0) + ' / ' + (data.ecnsFetched || 0), tone: 'muted' }
        ];
        var html = '<div style="display:grid; grid-template-columns:repeat(4,1fr); gap:12px;">';
        tiles.forEach(function (t) {
            var bg = '#FAFAF7';
            var fg = '#0F1720';
            if (t.tone === 'warn' && t.value > 0) { fg = '#C7801B'; }
            if (t.tone === 'ok')   { fg = '#1F8A4C'; }
            html += '<div style="background:' + bg + '; border:1px solid #E8E6DF; border-radius:6px; padding:14px;">'
                 +   '<div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">' + esc(t.label) + '</div>'
                 +   '<div style="font-size:26px; font-weight:600; color:' + fg + '; margin-top:4px;">' + esc(t.value) + '</div>'
                 + '</div>';
        });
        html += '</div>';
        document.getElementById('overdueKpiTiles').innerHTML = html;
    }

    function renderCategoryPanel(data) {
        var counts = data.categoryCounts || {};
        var total = 0;
        Object.keys(counts).forEach(function (k) { total += counts[k]; });
        if (total === 0) {
            document.getElementById('overdueCategoryPanel').innerHTML =
                '<p style="color:#6B7280; text-align:center; padding:20px;">No open out-of-target General ECNs right now. Looking good.</p>';
            return;
        }
        var colors = ['#B8342B', '#C7801B', '#4a6fa5', '#7C3AED', '#1F8A4C', '#9333EA', '#0EA5E9', '#6B7280'];
        var html = '<div style="display:flex; flex-direction:column; gap:6px;">';
        var cats = data.categories || Object.keys(counts);
        cats.forEach(function (cat, idx) {
            var c = counts[cat] || 0;
            if (c === 0) return;
            var pct = Math.round(100 * c / total);
            var bar = '<div style="background:' + colors[idx % colors.length] + '; width:' + pct + '%; height:14px; border-radius:3px;"></div>';
            html += '<div style="display:flex; align-items:center; gap:8px;">'
                 +   '<div style="flex:0 0 220px; font-size:12px; color:#0F1720;">' + esc(cat) + '</div>'
                 +   '<div style="flex:1; background:#EFEDE6; border-radius:3px; height:14px; overflow:hidden;">' + bar + '</div>'
                 +   '<div style="flex:0 0 70px; font-size:12px; color:#0F1720; text-align:right;">' + c + ' (' + pct + '%)</div>'
                 + '</div>';
        });
        html += '</div>';
        document.getElementById('overdueCategoryPanel').innerHTML = html;
    }

    function renderTeamPanel(data) {
        var teams = data.topTeams || [];
        if (teams.length === 0) {
            document.getElementById('overdueTeamPanel').innerHTML =
                '<p style="color:#6B7280; text-align:center; padding:20px;">—</p>';
            return;
        }
        var max = Math.max.apply(null, teams.map(function (t) { return t.count; }));
        var html = '<div style="display:flex; flex-direction:column; gap:6px;">';
        teams.forEach(function (t) {
            var pct = Math.round(100 * t.count / max);
            html += '<div style="display:flex; align-items:center; gap:8px;">'
                 +   '<div style="flex:0 0 200px; font-size:12px; color:#0F1720;">' + esc(t.name || '—') + '</div>'
                 +   '<div style="flex:1; background:#EFEDE6; border-radius:3px; height:14px; overflow:hidden;">'
                 +     '<div style="background:#2c3e50; width:' + pct + '%; height:14px;"></div>'
                 +   '</div>'
                 +   '<div style="flex:0 0 40px; font-size:12px; color:#0F1720; text-align:right;">' + t.count + '</div>'
                 + '</div>';
        });
        html += '</div>';
        document.getElementById('overdueTeamPanel').innerHTML = html;
    }

    function renderTable(data) {
        var rows = data.rows || [];
        var retro = data.mode === 'retrospective';
        // Re-label the section header so the user knows which population they're looking at.
        var hdr = document.querySelector('#overdueTrackerView .group .group-head h3');
        if (hdr) {
            var headerText = retro
                ? 'Released General ECNs — overdue at release '
                : 'Open Out-of-Target General ECNs ';
            hdr.innerHTML = headerText
                + '<span id="overdueRowCount" style="font-size:12px;color:#6B7280;font-weight:normal;">'
                + (rows.length ? '(' + rows.length + ')' : '') + '</span>';
        } else {
            document.getElementById('overdueRowCount').textContent =
                rows.length ? '(' + rows.length + ')' : '';
        }
        if (rows.length === 0) {
            var rangeNote = (data.minOver != null && data.maxOver != null)
                ? ' in the ' + data.minOver + '–' + data.maxOver + '-day-over-target range'
                : '';
            var emptyText = retro
                ? 'No General ECNs were released' + rangeNote + ' that exceeded the ' + (data.targetDays || 6) + '-day target.'
                : 'No open out-of-target General ECNs' + rangeNote + '.';
            document.getElementById('overdueEventsContainer').innerHTML =
                '<p style="color:#6B7280; text-align:center; padding:30px;">' + esc(emptyText) + '</p>';
            return;
        }
        var html = '<table style="width:100%; border-collapse:collapse; font-size:12px;">';
        html += '<thead><tr style="background:#2c3e50; color:#fff; position:sticky; top:0;">'
             + '<th style="text-align:left; padding:8px 10px;">ECN</th>'
             + '<th style="text-align:left; padding:8px 10px;">Classification</th>'
             + '<th style="text-align:left; padding:8px 10px;">Product Line</th>'
             + '<th style="text-align:left; padding:8px 10px;">Team</th>'
             + '<th style="text-align:left; padding:8px 10px;">Analyst</th>'
             + '<th style="text-align:left; padding:8px 10px;">Priority</th>'
             + '<th style="text-align:right; padding:8px 10px;">Days</th>'
             + '<th style="text-align:right; padding:8px 10px;" title="Target days for this row\'s classification + priority (10d Standard, 15d PDR, 30d fallback for NA classifications).">Target</th>'
             + '<th style="text-align:right; padding:8px 10px;">Over Target</th>'
             + '<th style="text-align:left; padding:8px 10px;">Status</th>'
             + '<th style="text-align:left; padding:8px 10px;">AI Category</th>'
             + '<th style="text-align:left; padding:8px 10px;">AI Summary</th>'
             + '<th style="text-align:left; padding:8px 10px;">Comment chain</th>'
             + '</tr></thead><tbody>';
        rows.forEach(function (r, i) {
            var bg = i % 2 === 0 ? '#FAFAF7' : '#fff';
            var catBg = '#e8f0fe', catFg = '#1a3a5c';
            if (/CCB|Approval/.test(r.category || ''))               { catBg = '#fdeaea'; catFg = '#B8342B'; }
            else if (/Cost/.test(r.category || ''))                  { catBg = '#fff3cd'; catFg = '#856404'; }
            else if (/Stakeholder|Customer|External/.test(r.category || '')) { catBg = '#fff8e1'; catFg = '#C7801B'; }
            else if (/Build|Tooling/.test(r.category || ''))         { catBg = '#e8f5e9'; catFg = '#1F8A4C'; }
            html += '<tr style="background:' + bg + ';">'
                 +   '<td style="padding:6px 10px; font-family:monospace;"><a href="' + (window.AGILE_WEBCLIENT_URL || 'https://plm.sandisk.com/Agile') + '/object/ECN/' + esc(r.ecnNumber) + '" target="_blank" style="color:#4a6fa5;">' + esc(r.ecnNumber) + '</a></td>'
                 +   '<td style="padding:6px 10px; font-size:11px; color:#6B7280;">' + esc(r.ecnClassification || '') + '</td>'
                 +   '<td style="padding:6px 10px;">' + esc(r.productLine) + '</td>'
                 +   '<td style="padding:6px 10px;">' + esc(r.productTeam) + '</td>'
                 +   '<td style="padding:6px 10px;">' + esc(r.analyst) + '</td>'
                 +   '<td style="padding:6px 10px;">' + esc(r.priority) + '</td>'
                 +   '<td style="padding:6px 10px; text-align:right;">' + esc(r.actualDays) + '</td>'
                 +   '<td style="padding:6px 10px; text-align:right; color:#6B7280;">' + esc(r.targetDays) + 'd</td>'
                 +   '<td style="padding:6px 10px; text-align:right; color:#B8342B; font-weight:600;">+' + esc(r.daysOverTarget) + 'd</td>'
                 +   '<td style="padding:6px 10px;">' + esc(r.currentStatus) + '</td>'
                 +   '<td style="padding:6px 10px;"><span style="background:' + catBg + '; color:' + catFg + '; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;">' + esc(r.category || 'Other') + '</span></td>'
                 +   '<td style="padding:6px 10px; max-width:240px;">' + esc(r.summary || '') + '</td>'
                 +   '<td style="padding:6px 10px; max-width:380px;"><div style="max-height:60px; overflow:auto; font-family:monospace; font-size:11px; color:#6B7280; white-space:pre-wrap;">' + esc(r.commentChain || '(no CCB comments)') + '</div></td>'
                 + '</tr>';
        });
        html += '</tbody></table>';
        document.getElementById('overdueEventsContainer').innerHTML = html;
    }

    function updateStatus(data) {
        var el = document.getElementById('overdueStatus');
        if (!el) return;
        var parts = [];
        if (data.lastRunAt) parts.push('Last run: ' + fmtDate(data.lastRunAt));
        if (data.ecnsCategorized != null) parts.push(data.ecnsCategorized + ' / ' + data.ecnsFetched + ' AI-categorized');
        if (data.minOver != null && data.maxOver != null) {
            parts.push('Range: ' + data.minOver + '–' + data.maxOver + ' days over target');
        }
        if (data.createdFrom && data.createdTo) {
            parts.push('Created: ' + esc(data.createdFrom) + ' → ' + esc(data.createdTo));
        }
        if (data.releasedFrom && data.releasedTo) {
            parts.push('<strong style="color:#C7801B;">Released: ' + esc(data.releasedFrom) + ' → ' + esc(data.releasedTo) + ' (retrospective)</strong>');
        }
        if (data.error) parts.push('<span style="color:#B8342B;">' + esc(data.error) + '</span>');
        el.innerHTML = parts.join(' &middot; ');
    }

    // === Live progress poller ====================================================
    // The /data and /refresh endpoints take 1–3 minutes (CCB-chain SQL + per-batch
    // Portkey calls). A bare "Loading…" makes the page feel hung, so while the
    // long fetch is in flight we hit GET /progress every 1.5s and re-paint the
    // four "Loading…" slots with a stage-specific line.
    var _spinIdx = 0;
    var _spinFrames = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
    var _progressTimer = null;

    function formatProgress(p) {
        var elapsed = Math.max(0, Math.round((p.elapsedMs || 0) / 1000));
        var spin = _spinFrames[_spinIdx++ % _spinFrames.length];
        var prefix = spin + ' ';
        var suffix = ' (' + elapsed + 's elapsed)';
        switch (p.stage) {
            case 'querying':
                return prefix + (p.message || 'Querying Agile for open overdue General ECNs') + '…' + suffix;
            case 'comments':
                return prefix + 'Pulling CCB comment chains for ' + (p.ecnCount || '…') + ' ECNs…' + suffix;
            case 'categorizing':
                var done = p.batchDone || 0;
                var total = p.batchTotal || 0;
                var per = total > 0 ? Math.round(100 * done / total) : 0;
                return prefix + 'AI-categorizing ' + (p.ecnCount || 0) + ' ECNs · batch '
                        + done + ' / ' + total + ' (' + per + '%)…' + suffix;
            case 'done':
                return prefix + (p.message || 'Wrapping up') + '…' + suffix;
            case 'idle':
            default:
                return prefix + 'Starting…' + suffix;
        }
    }

    function paintProgress(text) {
        var statusEl = document.getElementById('overdueStatus');
        if (statusEl) statusEl.textContent = text;
        var boxHtml = '<p style="color:#6B7280; text-align:center; padding:20px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px;">'
                + esc(text) + '</p>';
        var catEl  = document.getElementById('overdueCategoryPanel');
        var teamEl = document.getElementById('overdueTeamPanel');
        var tblEl  = document.getElementById('overdueEventsContainer');
        if (catEl)  catEl.innerHTML  = boxHtml;
        if (teamEl) teamEl.innerHTML = boxHtml;
        if (tblEl)  tblEl.innerHTML  = boxHtml;
    }

    function startProgressPoll() {
        stopProgressPoll();
        paintProgress('⠋ Starting…');
        var tick = function () {
            fetch('/api/ecn-report/overdue/progress', { credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (p) {
                    if (!p || !_progressTimer) return; // poll was stopped between fetch + then
                    paintProgress(formatProgress(p));
                })
                .catch(function () { /* transient — keep polling */ });
        };
        tick();
        _progressTimer = setInterval(tick, 1500);
    }

    function stopProgressPoll() {
        if (_progressTimer) { clearInterval(_progressTimer); _progressTimer = null; }
    }

    window.overdueLoad = function () {
        var r = readRange();
        var key = rangeKey(r);
        if (_state.loaded && _state.rangeKey === key) {
            // Re-render from cached data on subsequent tab visits — no extra fetch
            // unless the user clicks Refresh or the range changed.
            return renderAll(_state.data);
        }
        // PT-67: /data is now a pure cache reader, so this never triggers AI
        // categorization. No progress poll needed — the call returns immediately.
        fetch('/api/ecn-report/overdue/data' + rangeQs(r), { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                _state.loaded = true;
                _state.rangeKey = key;
                _state.data = d;
                renderAll(d);
            })
            .catch(function (e) {
                document.getElementById('overdueStatus').innerHTML =
                    '<span style="color:#B8342B;">Failed to load: ' + esc(e.message) + '</span>';
            });
    };

    window.overdueRefresh = function () {
        var r = readRange();
        var key = rangeKey(r);
        startProgressPoll();
        fetch('/api/ecn-report/overdue/refresh' + rangeQs(r), { method: 'POST', credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                stopProgressPoll();
                _state.loaded = true;
                _state.rangeKey = key;
                _state.data = d;
                renderAll(d);
            })
            .catch(function (e) {
                stopProgressPoll();
                document.getElementById('overdueStatus').innerHTML =
                    '<span style="color:#B8342B;">Refresh failed: ' + esc(e.message) + '</span>';
            });
    };

    function renderAll(d) {
        if (!d) return;
        renderClassificationChips(d);
        renderStaleBanner(d);
        // Retrospective-mode banner — only visible when a Date Released window is set.
        var banner = document.getElementById('overdueRetroBanner');
        var detail = document.getElementById('overdueRetroBannerDetail');
        if (banner) {
            var retro = d.mode === 'retrospective';
            banner.style.display = retro ? '' : 'none';
            if (retro && detail) {
                detail.textContent = 'Window: ' + (d.releasedFrom || '—') + ' → ' + (d.releasedTo || '—');
            }
        }

        // PT-67: /data is a cache reader. When the cache is empty, paint a
        // "click Refresh" CTA in every data slot — no auto-fire on tab click.
        if (d.cacheEmpty) {
            paintEmptyState();
            return;
        }
        updateStatus(d);
        renderKpiTiles(d);
        renderCategoryPanel(d);
        renderTeamPanel(d);
        renderTable(d);
    }

    function paintEmptyState() {
        var statusEl = document.getElementById('overdueStatus');
        if (statusEl) statusEl.innerHTML = '<span style="color:#6B7280;">No data yet — click <strong>↻ Refresh</strong> above to load.</span>';
        var cta = '<div style="text-align:center; padding:30px 20px; color:#6B7280; font-size:13px;">'
                + '<div style="font-size:14px; margin-bottom:8px; color:#0F1720;">Overdue Tracker hasn\'t been run in this session.</div>'
                + 'Click <strong style="color:#4a6fa5;">↻ Refresh</strong> in the toolbar to fetch open overdue ECNs and AI-categorize their CCB comment chains.'
                + '<div style="margin-top:6px; font-size:11.5px;">Takes ~1–3 minutes — runs once, then results are cached until you change filters and click Refresh again.</div>'
                + '</div>';
        var kpiEl = document.getElementById('overdueKpiTiles');
        if (kpiEl) kpiEl.innerHTML = '';
        var catEl  = document.getElementById('overdueCategoryPanel');
        var teamEl = document.getElementById('overdueTeamPanel');
        var tblEl  = document.getElementById('overdueEventsContainer');
        if (catEl)  catEl.innerHTML  = cta;
        if (teamEl) teamEl.innerHTML = '<p style="color:#6B7280; text-align:center; padding:20px;">—</p>';
        if (tblEl)  tblEl.innerHTML  = cta;
    }

    function renderStaleBanner(d) {
        // Inject (or remove) an amber callout above the table when the active
        // filters differ from the cached set. Cached data still renders below.
        var existing = document.getElementById('overdueStaleBanner');
        if (!d.filtersChanged) {
            if (existing) existing.remove();
            return;
        }
        if (existing) return; // already shown
        var host = document.getElementById('overdueRetroBanner');
        if (!host || !host.parentNode) return;
        var div = document.createElement('div');
        div.id = 'overdueStaleBanner';
        div.style.cssText = 'margin:0 0 12px; padding:10px 14px; background:#fff8e1; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; font-size:12.5px; color:#5a4a1f;';
        div.innerHTML = '<strong>Filters changed since the last refresh.</strong> '
                      + 'Showing the previous results below — click <strong>↻ Refresh</strong> to re-categorize with the current filters.';
        host.parentNode.insertBefore(div, host.nextSibling);
    }
})();
