(function () {
    let brMode = 'random';
    let currentRunId = null;
    let currentEvtSrc = null;
    let raceClockInterval = null;
    let raceStartedAt = 0;

    // ---- Lane state ----
    let totalItems = 0;
    let toolkitDone = 0, sdkDone = 0;
    let toolkitRows = 0, sdkRows = 0;
    let perItem = { toolkit: {}, sdk: {} };
    // Replay recording — captures every SSE event with its arrival delta
    // (ms since raceStartedAt). On race-done we serialize this array to localStorage.
    let _replayLog = null;
    let _replayActive = false;

    window.brSetMode = function (mode) {
        brMode = mode;
        document.getElementById('brModeRandom').style.background = mode === 'random' ? '#2c3e50' : 'transparent';
        document.getElementById('brModeRandom').style.color      = mode === 'random' ? '#fff' : '#6B7280';
        document.getElementById('brModeUpload').style.background = mode === 'upload' ? '#2c3e50' : 'transparent';
        document.getElementById('brModeUpload').style.color      = mode === 'upload' ? '#fff' : '#6B7280';
        document.getElementById('brFile').style.display = mode === 'upload' ? 'inline-block' : 'none';
        document.getElementById('brN').style.display    = mode === 'random' ? 'inline-block' : 'none';
    };

    window.brOnTabOpen = function () {
        document.getElementById('brError').style.display = 'none';
        document.getElementById('brScoreboard').style.display = 'none';
        // Set the Standard preset highlighted by default + populate the leaderboard.
        brHighlightPreset(parseInt(document.getElementById('brN').value, 10) || 10);
        brLoadHistory();
        // Toggle Replay button state based on whether a recording exists in localStorage.
        try {
            var has = !!localStorage.getItem('bomraceReplayLog');
            var btn = document.getElementById('brReplay');
            if (btn) {
                btn.disabled = !has;
                btn.style.opacity = has ? '1' : '0.5';
                btn.style.cursor = has ? 'pointer' : 'not-allowed';
            }
        } catch (e) {}
    };

    window.brApplyPreset = function (n, label) {
        document.getElementById('brN').value = n;
        brSetMode('random');
        brHighlightPreset(n);
    };

    function brHighlightPreset(n) {
        document.querySelectorAll('.brPreset').forEach(function (b) {
            b.classList.toggle('active', parseInt(b.getAttribute('data-n'), 10) === n);
        });
    }

    /** Replay the most recent race from a localStorage recording at 2× speed.
     *  No backend hit — useful for demos when the SDK is slow or unreachable. */
    window.brReplayLast = function () {
        let log;
        try {
            const raw = localStorage.getItem('bomraceReplayLog');
            if (!raw) { brShowError('No replay available — run a race first.'); return; }
            log = JSON.parse(raw);
        } catch (e) { brShowError('Replay unavailable: ' + e.message); return; }
        if (!log.events || log.events.length === 0) {
            brShowError('Replay log is empty.'); return;
        }

        // Tear down anything in flight, then set up the lanes for the cached run.
        if (currentEvtSrc) { try { currentEvtSrc.close(); } catch (e) {} currentEvtSrc = null; }
        if (raceClockInterval) { clearInterval(raceClockInterval); raceClockInterval = null; }
        document.getElementById('brError').style.display = 'none';
        document.getElementById('brScoreboard').style.display = 'none';

        currentRunId = log.runId; // so download buttons in the finish card still resolve
        brResetLanes(log.totalItems);
        document.getElementById('brLanes').style.display = 'block';
        raceStartedAt = Date.now();
        _replayActive = true;

        const SPEED = 2; // 2× clock — half the real wall-clock delays
        const tickerLabel = ' (replay)';
        // Update the race-time clock during playback
        raceClockInterval = setInterval(() => {
            const t = Math.floor((Date.now() - raceStartedAt) / 1000);
            document.getElementById('brClock').textContent =
                'Race time: ' + Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0') + tickerLabel;
        }, 200);

        log.events.forEach((ev) => {
            setTimeout(() => {
                // Re-dispatch via the same handlers, but skip the recording append.
                const data = JSON.parse(ev.data);
                switch (ev.name) {
                    case 'race-start': brOnRaceStart(data); break;
                    case 'item-start': brOnItemStart(data); break;
                    case 'item-done':  brOnItemDone(data);  break;
                    case 'item-error': brOnItemError(data); break;
                    case 'lane-done':  brOnLaneDone(data);  break;
                    case 'race-done':
                        brOnRaceDone(data);
                        _replayActive = false;
                        if (raceClockInterval) { clearInterval(raceClockInterval); raceClockInterval = null; }
                        break;
                }
            }, Math.max(0, ev.t / SPEED));
        });
    };

    function brLoadHistory() {
        fetch('/api/bomrace/history?limit=5', { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(brRenderHistory)
            .catch(function () { /* silent — leaderboard is best-effort */ });
    }

    function brRenderHistory(rows) {
        var el = document.getElementById('brHistory');
        if (!el) return;
        if (!rows || rows.length === 0) {
            el.innerHTML = '<div style="color:#9CA3AF; font-style:italic;">No races yet. Start one to populate this list.</div>';
            return;
        }
        var html = '<table class="brHistTable"><thead><tr>' +
            '<th>When</th><th class="num">N</th><th class="num">Toolkit</th><th class="num">SDK</th>' +
            '<th class="num">Speedup</th><th>By</th></tr></thead><tbody>';
        rows.forEach(function (r) {
            var when = r.when ? brFormatRelative(r.when) : '—';
            var tk   = r.toolkitMs != null ? (r.toolkitMs / 1000).toFixed(1) + 's' : '—';
            var sdk  = r.sdkMs     != null ? (r.sdkMs / 1000).toFixed(1) + 's'     : '—';
            var sp   = r.speedup   != null ? r.speedup.toFixed(1) + '\u00d7'        : '—';
            var by   = r.by ? r.by : '—';
            var spClass = (r.speedup >= 1) ? 'ok' : 'warn';
            html += '<tr>' +
                '<td>' + when + '</td>' +
                '<td class="num">' + r.n + '</td>' +
                '<td class="num">' + tk + '</td>' +
                '<td class="num">' + sdk + '</td>' +
                '<td class="num ' + spClass + '" style="font-weight:600;">' + sp + '</td>' +
                '<td>' + by + '</td>' +
                '</tr>';
        });
        html += '</tbody></table>';
        el.innerHTML = html;
    }

    function brFormatRelative(epochMs) {
        var diff = Math.round((Date.now() - epochMs) / 1000);
        if (diff < 60)         return diff + 's ago';
        if (diff < 3600)       return Math.round(diff / 60) + 'm ago';
        if (diff < 86400)      return Math.round(diff / 3600) + 'h ago';
        if (diff < 86400 * 7)  return Math.round(diff / 86400) + 'd ago';
        var d = new Date(epochMs);
        return (d.getMonth() + 1) + '/' + d.getDate();
    }

    window.brStart = async function () {
        if (currentEvtSrc) { try { currentEvtSrc.close(); } catch (e) {} currentEvtSrc = null; }
        if (raceClockInterval) { clearInterval(raceClockInterval); raceClockInterval = null; }
        document.getElementById('brError').style.display = 'none';
        document.getElementById('brScoreboard').style.display = 'none';

        let payload = { mode: brMode, n: parseInt(document.getElementById('brN').value, 10) || 10 };

        if (brMode === 'upload') {
            const f = document.getElementById('brFile').files[0];
            if (!f) { brShowError('Pick an Excel file first.'); return; }
            brShowError('Upload mode not yet wired in v1 — use Random for the showcase. (Follow-up ticket.)');
            return;
        }

        const resp = await fetch('/api/bomrace/start', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (!resp.ok) {
            const j = await resp.json().catch(() => ({}));
            brShowError(j.message || j.error || ('HTTP ' + resp.status));
            return;
        }
        const { runId, items } = await resp.json();
        currentRunId = runId;
        brResetLanes(items.length);
        document.getElementById('brLanes').style.display = 'grid';

        raceStartedAt = Date.now();
        raceClockInterval = setInterval(() => {
            const t = Math.floor((Date.now() - raceStartedAt) / 1000);
            document.getElementById('brClock').textContent =
                'Race time: ' + Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0');
        }, 200);

        // Start a fresh recording for replay. Each SSE event gets stamped with arrival delta.
        _replayLog = { runId: runId, items: items, totalItems: items.length, events: [], savedAt: Date.now() };
        _replayActive = false;

        currentEvtSrc = new EventSource('/api/bomrace/' + runId + '/stream');
        currentEvtSrc.addEventListener('race-start', (e) => brHandleEvent('race-start', e.data));
        currentEvtSrc.addEventListener('item-start', (e) => brHandleEvent('item-start', e.data));
        currentEvtSrc.addEventListener('item-done',  (e) => brHandleEvent('item-done',  e.data));
        currentEvtSrc.addEventListener('item-error', (e) => brHandleEvent('item-error', e.data));
        currentEvtSrc.addEventListener('lane-done',  (e) => brHandleEvent('lane-done',  e.data));
        currentEvtSrc.addEventListener('race-done',  (e) => brHandleEvent('race-done',  e.data));
        currentEvtSrc.addEventListener('error',      ()  => brShowError('Stream error.'));
    };

    /** Centralized event router — records the event for replay, then dispatches to its handler. */
    function brHandleEvent(name, rawData) {
        if (!_replayActive && _replayLog) {
            _replayLog.events.push({ name: name, data: rawData, t: Date.now() - raceStartedAt });
        }
        const data = JSON.parse(rawData);
        switch (name) {
            case 'race-start': brOnRaceStart(data); break;
            case 'item-start': brOnItemStart(data); break;
            case 'item-done':  brOnItemDone(data);  break;
            case 'item-error': brOnItemError(data); break;
            case 'lane-done':  brOnLaneDone(data);  break;
            case 'race-done':
                brOnRaceDone(data);
                // Persist the recording so "Replay last" can replay this run.
                if (!_replayActive && _replayLog) {
                    try {
                        localStorage.setItem('bomraceReplayLog', JSON.stringify(_replayLog));
                        const btn = document.getElementById('brReplay');
                        if (btn) { btn.disabled = false; btn.style.opacity = '1'; btn.style.cursor = 'pointer'; }
                    } catch (e) { /* quota exceeded — ignore */ }
                }
                break;
        }
    };

    function brShowError(msg) {
        const el = document.getElementById('brError');
        el.textContent = msg;
        el.style.display = 'block';
    }

    function brResetLanes(n) {
        totalItems = n;
        toolkitDone = 0; sdkDone = 0;
        toolkitRows = 0; sdkRows = 0;
        perItem = { toolkit: {}, sdk: {} };
        _laneRows = { toolkit: null, sdk: null };
        ['brLaneToolkit', 'brLaneSdk'].forEach((id) => {
            const lane = document.getElementById(id);
            // Reset runner to start line
            const runner = lane.querySelector('.brRunner');
            if (runner) runner.style.left = '0%';
            // Clear ghost-trail visited markers
            lane.querySelectorAll('.brGhost').forEach(g => g.classList.remove('visited'));
            // Reset stats / ticker
            lane.querySelector('.brLaneCount').textContent = '0/' + n;
            const rowsEl = lane.querySelector('.brLaneRows');
            if (rowsEl) rowsEl.textContent = '0';
            lane.querySelector('.brLaneClock').textContent = '—';
            const active = lane.querySelector('.brLaneActive');
            if (active) active.textContent = 'starting…';
            const speed = lane.querySelector('.brSpeedPill');
            if (speed) speed.textContent = '—';
            // Clear any previous row-parity info icon
            const oldInfo = lane.querySelector('.brRowInfo');
            if (oldInfo) oldInfo.remove();
        });
        // Clear any previous finish card content
        const dl = document.getElementById('brDownloads');
        if (dl) dl.innerHTML = '';
    }

    /** Move the runner to its current progress %, light up ghost markers it has passed,
     *  and refresh the per-lane speed pill (items/s). */
    function brAdvanceRunner(laneEl, doneCount, totalMs) {
        const pct = totalItems > 0 ? (doneCount / totalItems) * 100 : 0;
        const runner = laneEl.querySelector('.brRunner');
        if (runner) runner.style.left = pct + '%';
        laneEl.querySelectorAll('.brGhost').forEach(g => {
            const at = parseFloat(g.style.left);
            if (pct >= at) g.classList.add('visited');
        });
        const elapsed = Math.max(0.001, (totalMs != null ? totalMs : (Date.now() - raceStartedAt)) / 1000);
        const rate = doneCount / elapsed; // items/s
        const speed = laneEl.querySelector('.brSpeedPill');
        if (speed) speed.textContent = rate >= 10 ? rate.toFixed(0) + ' items/s' : rate.toFixed(2) + ' items/s';
    }

    function brOnRaceStart(d) {
        // race-start arrives once both lanes are submitted; just confirm UI is in race state
    }

    function brOnItemStart(d) {
        const lane = d.lane === 'toolkit' ? document.getElementById('brLaneToolkit')
                                          : document.getElementById('brLaneSdk');
        const active = lane.querySelector('.brLaneActive');
        if (active) active.textContent = 'now: ' + d.item;
    }

    function brOnItemDone(d) {
        perItem[d.lane][d.item] = { rows: d.rows, durationMs: d.durationMs };
        if (d.lane === 'toolkit') { toolkitDone++; toolkitRows += (d.rows || 0); }
        else                      { sdkDone++;     sdkRows     += (d.rows || 0); }
        const lane = d.lane === 'toolkit' ? document.getElementById('brLaneToolkit')
                                          : document.getElementById('brLaneSdk');
        const done = d.lane === 'toolkit' ? toolkitDone : sdkDone;
        const rows = d.lane === 'toolkit' ? toolkitRows : sdkRows;
        brAdvanceRunner(lane, done, null);
        lane.querySelector('.brLaneCount').textContent = done + '/' + totalItems;
        const rowsEl = lane.querySelector('.brLaneRows');
        if (rowsEl) rowsEl.textContent = rows;
    }

    function brOnItemError(d) {
        perItem[d.lane][d.item] = { error: d.message, durationMs: d.errorMs };
        if (d.lane === 'toolkit') toolkitDone++; else sdkDone++;
        const lane = d.lane === 'toolkit' ? document.getElementById('brLaneToolkit')
                                          : document.getElementById('brLaneSdk');
        const done = d.lane === 'toolkit' ? toolkitDone : sdkDone;
        brAdvanceRunner(lane, done, null);
        // Tint the runner red to surface the error visually
        const runner = lane.querySelector('.brRunner');
        if (runner) runner.style.borderColor = '#B8342B';
        lane.querySelector('.brLaneCount').textContent = done + '/' + totalItems;
    }

    function brOnLaneDone(d) {
        const lane = d.lane === 'toolkit' ? document.getElementById('brLaneToolkit')
                                          : document.getElementById('brLaneSdk');
        lane.querySelector('.brLaneClock').textContent = (d.totalMs / 1000).toFixed(1) + 's';
        // Land the runner at the finish line + final speed pill from real lane time
        brAdvanceRunner(lane, totalItems, d.totalMs);
        lane.querySelector('.brLaneCount').textContent = totalItems + '/' + totalItems;
        const rowsEl = lane.querySelector('.brLaneRows');
        if (rowsEl) rowsEl.textContent = d.totalRows || 0;
        brMaybeAttachRowParityInfo(d.lane, d.totalRows || 0);
        const active = lane.querySelector('.brLaneActive');
        if (active) active.textContent = '\u2713 done';
    }

    // Attaches an info icon with a hover tooltip explaining the dedup difference
    // between the two lanes' row counts. Only meaningful once both lanes report.
    let _laneRows = { toolkit: null, sdk: null };
    function brMaybeAttachRowParityInfo(lane, totalRows) {
        _laneRows[lane] = totalRows;
        if (_laneRows.toolkit == null || _laneRows.sdk == null) return;
        const tip = "Row counts can differ even when both methods agree on the parts:\n" +
            "• Toolkit's batched walk repeats common sub-assemblies once per parent occurrence.\n" +
            "• SDK uses a per-input visited set that compresses repeats.\n" +
            "Use the Set match indicator (above) to see if the actual parts agree.";
        ['brLaneToolkit', 'brLaneSdk'].forEach(id => {
            const lane = document.getElementById(id);
            // Racetrack uses .brTrackTicker for the live status line; old markup used .brLaneStat.
            const stat = lane.querySelector('.brTrackTicker') || lane.querySelector('.brLaneStat');
            if (!stat || stat.querySelector('.brRowInfo')) return; // already attached
            const span = document.createElement('span');
            span.className = 'brRowInfo';
            span.setAttribute('data-tip', tip);
            span.textContent = ' \u24D8';
            stat.appendChild(span);
        });
    }

    function brOnRaceDone(d) {
        if (raceClockInterval) { clearInterval(raceClockInterval); raceClockInterval = null; }
        if (currentEvtSrc) { try { currentEvtSrc.close(); } catch (e) {} currentEvtSrc = null; }

        // Reveal the dark card FIRST so the user always sees the frame + downloads,
        // even if a downstream render step throws. (Race-done is the demo payoff —
        // a silent failure here was hiding the whole scoreboard including downloads.)
        const board = document.getElementById('brScoreboard');
        if (board) board.style.display = 'block';

        try { brRenderRaceDone(d); }
        catch (err) { console.error('[bomrace] race-done render failed:', err, 'payload=', d); }
    }

    function brRenderRaceDone(d) {
        d = d || {};
        const set = d.setMatch || { ok: false, onlyToolkit: [], onlySdk: [] };
        const str = d.structuralMatch || { ok: false, onlyToolkit: [], onlySdk: [] };
        const setOnlyTk  = (set.onlyToolkit || []);
        const setOnlySdk = (set.onlySdk || []);
        const strOnlyTk  = (str.onlyToolkit || []);
        const strOnlySdk = (str.onlySdk || []);

        // Hero numeral — speedup ratio (or "slower" if <1).
        const speedupTxt = d.speedup >= 1
            ? d.speedup.toFixed(1) + '\u00d7'
            : (1 / d.speedup).toFixed(1) + '\u00d7';
        const speedupLabel = d.speedup >= 1 ? 'faster' : 'slower';
        document.getElementById('brFinishHero').innerHTML = speedupTxt +
            ' <span style="font-size:18px; color:#9CB7DC; font-weight:400; font-family:\'IBM Plex Sans\',sans-serif;">' + speedupLabel + '</span>';
        document.getElementById('brFinishSub').textContent =
            (d.toolkitMs/1000).toFixed(1) + 's vs ' + (d.sdkMs/1000).toFixed(1) + 's';

        // Match chips (green when ok, amber when diff)
        const chips = document.getElementById('brFinishChips');
        chips.innerHTML =
            '<span class="brFinishChip ' + (set.ok ? '' : 'warn') + '">' +
                (set.ok ? '\u2713' : '\u26a0') + ' Set match ' +
                (set.ok ? 'ok' : (setOnlyTk.length + setOnlySdk.length) + ' diff') +
            '</span>' +
            '<span class="brFinishChip ' + (str.ok ? '' : 'warn') + '">' +
                (str.ok ? '\u2713' : '\u26a0') + ' Structural match ' +
                (str.ok ? 'ok' : (strOnlyTk.length + strOnlySdk.length) + ' diff') +
            '</span>' +
            '<span class="brFinishChip">' +
                'Toolkit ' + (_laneRows.toolkit || 0).toLocaleString() + ' rows \u00b7 SDK ' + (_laneRows.sdk || 0).toLocaleString() + ' rows' +
            '</span>';

        // "What this means" — scale the savings to a 500-item monthly report.
        const minutesSaved = (d.sdkMs - d.toolkitMs) / 1000 / 60 * (500 / Math.max(1, totalItems));
        const sessionsSaved = 500;
        const meaning = document.getElementById('brFinishMeaning');
        if (d.speedup >= 1) {
            meaning.innerHTML =
                '<strong>What this means:</strong> on a 500-item monthly report, this saves ' +
                '~<strong>' + Math.round(minutesSaved) + ' minutes</strong> of wall time and ' +
                '~<strong>' + sessionsSaved + ' SDK sessions</strong> compared to the live walk.';
        } else {
            meaning.innerHTML = '<strong>What this means:</strong> the SDK was faster on this run \u2014 a result worth investigating.';
        }

        // Download buttons — two xlsx files: input items, then race results (Toolkit + SDK in tabs).
        const dl = document.getElementById('brDownloads');
        if (dl && currentRunId) {
            const base = '/api/bomrace/' + currentRunId + '/download/';
            const tk  = _laneRows.toolkit != null ? _laneRows.toolkit : 0;
            const sdk = _laneRows.sdk     != null ? _laneRows.sdk     : 0;
            dl.innerHTML =
              `<a class="brDl" href="${base}input"   download>⬇ Input items (${totalItems})</a>` +
              `<a class="brDl" href="${base}results" download>⬇ Race results · xlsx (Toolkit ${tk} + SDK ${sdk} rows)</a>`;
        }

        // Build the details block — per-item table now shows toolkit rows alongside SDK timings/rows.
        // Toolkit time is per-batch (single SQL call), so it's reported once at the bottom, not per row.
        const det = document.getElementById('brDetails');
        const onlyTk = setOnlyTk.slice(0, 50);
        const onlySdk = setOnlySdk.slice(0, 50);
        const tkPerItem = d.toolkitPerItem || {};
        let html = '<div style="margin-top:6px;"><strong>Per-item comparison</strong>';
        html += '<table style="width:100%; font-size:11px; border-collapse:collapse; margin-top:4px;">';
        html += '<tr style="background:#2c3e50; color:#fff;">' +
                '<th style="padding:4px 8px; text-align:left;">Item</th>' +
                '<th style="padding:4px 8px; text-align:right;">Toolkit rows</th>' +
                '<th style="padding:4px 8px; text-align:right;">SDK rows</th>' +
                '<th style="padding:4px 8px; text-align:right;">SDK ms</th></tr>';
        // Iterate the SDK per-item map (insertion order matches the input list).
        Object.keys(perItem.sdk).forEach((item, i) => {
            const r = perItem.sdk[item];
            const bg = i % 2 ? '#FAFAF7' : '#fff';
            const ms = r.error ? 'ERR' : (r.durationMs != null ? r.durationMs.toLocaleString() : '—');
            const sdkRows = r.error ? '—' : (r.rows != null ? r.rows.toLocaleString() : '0');
            const tkRows = tkPerItem[item] != null ? tkPerItem[item].toLocaleString() : '—';
            html += `<tr style="background:${bg};">` +
                    `<td style="padding:4px 8px; border-bottom:1px solid #E8E6DF;">${item}</td>` +
                    `<td style="padding:4px 8px; text-align:right; font-family:'IBM Plex Mono',Consolas,monospace; border-bottom:1px solid #E8E6DF;">${tkRows}</td>` +
                    `<td style="padding:4px 8px; text-align:right; font-family:'IBM Plex Mono',Consolas,monospace; border-bottom:1px solid #E8E6DF;">${sdkRows}</td>` +
                    `<td style="padding:4px 8px; text-align:right; font-family:'IBM Plex Mono',Consolas,monospace; border-bottom:1px solid #E8E6DF;">${ms}</td>` +
                    `</tr>`;
        });
        html += '</table>';
        html += `<div style="font-size:11px; color:#6B7280; margin-top:6px;">Toolkit lane is batched (one SQL call for all items), so its total time of <strong>${(d.toolkitMs/1000).toFixed(1)}s</strong> isn\u2019t broken down per item.</div></div>`;
        if (onlyTk.length || onlySdk.length) {
            html += '<div style="margin-top:14px;"><strong>Set-match diff</strong></div>';
            if (onlyTk.length) html += '<div style="margin-top:6px; font-size:11px;"><span style="color:#4a6fa5; font-weight:600;">Found by toolkit only:</span> ' + onlyTk.join(', ') + (setOnlyTk.length > 50 ? ' …' : '') + '</div>';
            if (onlySdk.length) html += '<div style="margin-top:6px; font-size:11px;"><span style="color:#C7801B; font-weight:600;">Found by SDK only:</span> ' + onlySdk.join(', ') + (setOnlySdk.length > 50 ? ' …' : '') + '</div>';
        }
        det.innerHTML = html;

        // (display:block already set in brOnRaceDone wrapper before this render runs.)
        // Refresh the leaderboard so the just-finished run shows up at the top.
        brLoadHistory();
    }

    window.brToggleDetails = function () {
        const det = document.getElementById('brDetails');
        det.style.display = det.style.display === 'none' ? 'block' : 'none';
    };
})();
