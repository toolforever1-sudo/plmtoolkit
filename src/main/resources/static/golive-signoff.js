/* Go-Live & Target-UAT sign-off page.
 *
 * Vanilla JS (mirrors the style in it-enhancements.js). The mode (GOLIVE vs
 * UAT) is driven by the `flow` field in the /api/golive-signoff/data response.
 *
 * Flow:
 *   1. Read token from URL.
 *   2. GET /api/golive-signoff/data?token=… — render cards.
 *   3. User edits dates inline; past-due rows MUST have a new today-or-future date.
 *   4. POST /api/golive-signoff/submit with samAccountName + password.
 *   5. Render success state with per-ECN chips, OR show an error.
 */
(function () {
    'use strict';

    var STATE = {
        token: '',
        flow: '',          // 'GOLIVE' | 'UAT'
        loginId: '',
        displayName: '',
        ccAddr: 'pdl-plm-admin@sandisk.com',
        today: '',         // 'YYYY-MM-DD'
        rows: [],          // server-supplied, augmented with .newDate
        submitting: false
    };

    function getToken() {
        var m = location.search.match(/[?&]token=([^&]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }

    function fmt(iso) {
        if (!iso) return '—';
        var d = new Date(iso + 'T00:00:00');
        if (isNaN(d.getTime())) return iso;
        return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    }

    function fmtLong(iso) {
        if (!iso) return '—';
        var d = new Date(iso + 'T00:00:00');
        if (isNaN(d.getTime())) return iso;
        return d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function dayDiff(isoA, isoB) {
        var a = new Date(isoA + 'T00:00:00').getTime();
        var b = new Date(isoB + 'T00:00:00').getTime();
        return Math.round((a - b) / (24 * 3600 * 1000));
    }

    function noun() { return STATE.flow === 'GOLIVE' ? 'Go-live' : 'Target UAT'; }

    function dueChipText(row) {
        if (!row.currentDate) return '';
        var d = dayDiff(row.currentDate, STATE.today);
        if (d < 0) {
            return Math.abs(d) + ' day' + (Math.abs(d) === 1 ? '' : 's') + ' past ' + (STATE.flow === 'GOLIVE' ? 'due' : 'UAT');
        }
        if (d === 1) return STATE.flow === 'GOLIVE' ? 'Goes live tomorrow' : 'UAT due tomorrow';
        return (STATE.flow === 'GOLIVE' ? 'In ' : 'UAT in ') + d + ' days';
    }

    function statusChipClass(status) {
        var s = (status || '').toLowerCase();
        if (s === 'uat') return 'uat';
        if (s === 'wip') return 'wip';
        if (s === 'analysis') return 'analysis';
        return 'analysis';
    }

    // ============================================================
    // Boot
    // ============================================================
    document.addEventListener('DOMContentLoaded', function () {
        STATE.token = getToken();
        if (!STATE.token) {
            return showFatal('Missing token', 'The sign-off link in your email is missing the token parameter.');
        }
        loadData();
    });

    function loadData() {
        fetch('/api/golive-signoff/data?token=' + encodeURIComponent(STATE.token), { credentials: 'omit' })
            .then(function (r) {
                if (r.ok) return r.json();
                return r.json().then(function (b) {
                    var msg = (b && b.error) ? b.error : ('HTTP ' + r.status);
                    var heading;
                    if (r.status === 410) heading = 'Link no longer valid';
                    else if (r.status === 404) heading = 'Link not found';
                    else heading = 'Could not load sign-off page';
                    showFatal(heading, msg);
                    throw new Error(msg);
                });
            })
            .then(function (resp) { absorb(resp); render(); })
            .catch(function (e) { console.error('[GLS] loadData failed:', e); });
    }

    function absorb(resp) {
        STATE.flow = resp.flow;
        STATE.loginId = resp.loginId || '';
        STATE.displayName = resp.displayName || '';
        STATE.ccAddr = resp.ccAddr || STATE.ccAddr;
        STATE.today = resp.today || new Date().toISOString().slice(0, 10);
        STATE.rows = (resp.rows || []).map(function (r) {
            r.newDate = r.currentDate || '';
            return r;
        });
    }

    function render() {
        document.getElementById('glsLoading').style.display = 'none';
        document.getElementById('glsBody').style.display = '';

        var isGoLive = STATE.flow === 'GOLIVE';
        document.getElementById('glsCrumbTail').textContent = '/ ' + (isGoLive ? 'Go-Live Sign-Off' : 'Target UAT Update');
        document.getElementById('glsTokenChip').innerHTML = 'Signed link &middot; <b>' + escapeHtml(STATE.loginId) + '</b>';
        document.getElementById('glsKicker').textContent = 'IT Enhancements · ' + (isGoLive ? 'Go-Live confirmation' : 'Target UAT check');
        document.getElementById('glsHeading').textContent = isGoLive ? 'Confirm your go-live dates' : 'Update your Target UAT dates';
        document.getElementById('glsFooterContact').textContent = STATE.ccAddr;
        document.getElementById('glsFooterToken').textContent = 'Token · single use';

        var firstName = (STATE.displayName.split(',')[1] || STATE.displayName.split(' ')[0] || 'there').trim();
        var upcoming = STATE.rows.filter(function (r) { return !r.overdue && !r.noDate; }).length;
        var overdue = STATE.rows.filter(function (r) { return r.overdue; }).length;
        var noDate = STATE.rows.filter(function (r) { return r.noDate; }).length;
        var subParts = [];
        if (isGoLive) {
            if (upcoming > 0) subParts.push(upcoming + ' enhancement' + (upcoming === 1 ? ' is' : 's are') + ' scheduled to go live within 3 days');
            if (overdue > 0) subParts.push(overdue + (overdue === 1 ? ' has' : ' have') + ' slipped past the date');
            if (noDate > 0) subParts.push(noDate + (noDate === 1 ? ' has' : ' have') + ' no go-live date at all');
            document.getElementById('glsSub').textContent = 'Hi ' + firstName + ' — ' + subParts.join(', ') + '. '
                + 'Keep an upcoming date as-is to confirm it; past-due dates or rows with no date need a new one before you can sign off.';
        } else {
            if (upcoming > 0) subParts.push(upcoming + ' ECN' + (upcoming === 1 ? '' : 's') + ' hit Target UAT within 3 days');
            if (overdue > 0) subParts.push(overdue + ' Target UAT date' + (overdue === 1 ? ' has' : 's have') + ' already passed');
            if (noDate > 0) subParts.push(noDate + (noDate === 1 ? ' has' : ' have') + ' no Target UAT date set');
            document.getElementById('glsSub').textContent = 'Hi ' + firstName + ' — these ECNs assigned to you are still in WIP / Analysis but '
                + subParts.join(', ') + '. Keep a date only if UAT will really start by then; past-due rows and rows with no date must be picked.';
        }

        // Pre-fill loginId from token (user can edit if it auto-filled wrong)
        var loginInput = document.getElementById('glsLoginInput');
        if (!loginInput.value) loginInput.value = STATE.loginId;

        renderCards();
        refreshSummary();
        wire();
    }

    function renderCards() {
        var c = document.getElementById('glsCards');
        var html = '';
        var pastDueDone = false, noDateDone = false;
        for (var i = 0; i < STATE.rows.length; i++) {
            var r = STATE.rows[i];
            if (r.overdue && !r.noDate && !pastDueDone) {
                html += '<div class="gls-divider">' + (STATE.flow === 'GOLIVE' ? 'Past due — new date required' : 'UAT date passed — new date required') + '</div>';
                pastDueDone = true;
            }
            if (r.noDate && !noDateDone) {
                html += '<div class="gls-divider">' + (STATE.flow === 'GOLIVE' ? 'No go-live date set — pick one to confirm' : 'No Target UAT date set — pick one to confirm') + '</div>';
                noDateDone = true;
            }
            html += cardHtml(r, i);
        }
        c.innerHTML = html;
    }

    function cardHtml(r, idx) {
        var isGoLive = STATE.flow === 'GOLIVE';
        var statusClass = statusChipClass(r.status);
        // noDate cards share the red "over" treatment in the chip color +
        // border tint; the chip label reads "no date set" instead of "N days
        // past due".
        var dueClass = (r.overdue || r.noDate) ? 'over' : 'soon';
        var dueText = r.noDate
            ? (isGoLive ? 'no go-live date set' : 'no UAT date set')
            : dueChipText(r);
        var min = STATE.today;
        var dateLabel = isGoLive ? 'Current go-live' : 'Current Target UAT';
        var newLabel = isGoLive ? 'New go-live date' : 'New Target UAT date';
        var slotA = isGoLive ? 'IT owner' : 'Requestor';
        var slotAv = isGoLive ? (r.itOwner || '—') : (r.requestor || '—');
        var slotB = isGoLive ? 'UAT since' : 'Target go-live';
        var slotBv = isGoLive ? (r.targetUAT ? fmt(r.targetUAT) : '—') : (r.targetGoLive ? fmt(r.targetGoLive) : '—');

        var help;
        if (r.noDate) {
            help = isGoLive ? 'No go-live date set yet — pick one to confirm.' : 'No Target UAT date set yet — pick one to confirm.';
        } else if (r.overdue) {
            help = isGoLive ? 'This date has passed — pick a new go-live date to sign off.' : 'This date has passed — pick a new Target UAT date to sign off.';
        } else {
            help = isGoLive ? 'Leave as-is to confirm the current date.' : 'Leave as-is only if UAT will start by this date.';
        }

        // Reuse the .is-overdue class for noDate rows too (same red treatment).
        var classes = 'gls-card' + ((r.overdue || r.noDate) ? ' is-overdue' : '');
        var currentDisplay = r.noDate ? 'Not set yet' : fmtLong(r.currentDate);
        return '<div class="' + classes + '" data-idx="' + idx + '" data-original="' + escapeHtml(r.currentDate || '') + '" data-nodate="' + (r.noDate ? '1' : '0') + '">'
            + '<div class="gls-card-main">'
            +   '<div class="gls-card-head">'
            +     '<span class="gls-ecn">' + escapeHtml(r.ecnNumber) + '</span>'
            +     '<span class="gls-chip ' + statusClass + '">' + escapeHtml(r.status || '') + '</span>'
            +     '<span class="gls-chip ' + dueClass + '">' + escapeHtml(dueText) + '</span>'
            +   '</div>'
            +   '<p class="gls-desc">' + escapeHtml(r.proposal || '') + '</p>'
            +   '<div class="gls-meta">'
            +     '<div class="gls-meta-item"><div class="k">' + slotA + '</div><div class="v">' + escapeHtml(slotAv) + '</div></div>'
            +     '<div class="gls-meta-item"><div class="k">' + slotB + '</div><div class="v">' + escapeHtml(slotBv) + '</div></div>'
            +     '<div class="gls-meta-item"><div class="k">Priority</div><div class="v">' + escapeHtml(r.priority || '—') + '</div></div>'
            +   '</div>'
            + '</div>'
            + '<div class="gls-card-date">'
            +   '<div class="k">' + dateLabel + '</div>'
            +   '<div class="gls-current">' + escapeHtml(currentDisplay) + '</div>'
            +   '<div class="k" style="margin-top:6px;">' + newLabel + '</div>'
            +   '<input class="gls-date-input" type="date" data-idx="' + idx + '" value="' + escapeHtml(r.currentDate || '') + '" min="' + escapeHtml(min) + '">'
            +   '<div class="gls-date-help">' + escapeHtml(help) + '</div>'
            +   '<span class="gls-flag">Date changed</span>'
            + '</div>'
            + '</div>';
    }

    function wire() {
        document.querySelectorAll('.gls-date-input').forEach(function (inp) {
            inp.addEventListener('input', function () {
                var idx = parseInt(inp.getAttribute('data-idx'), 10);
                STATE.rows[idx].newDate = inp.value;
                refreshOneCard(idx);
                refreshSummary();
            });
        });
        document.getElementById('glsSubmit').addEventListener('click', submit);
        document.getElementById('glsPass').addEventListener('keydown', function (e) {
            if (e.key === 'Enter') submit();
        });
    }

    function refreshOneCard(idx) {
        var card = document.querySelector('.gls-card[data-idx="' + idx + '"]');
        if (!card) return;
        var r = STATE.rows[idx];
        var orig = card.getAttribute('data-original') || '';
        var isChanged = (r.newDate || '') !== orig;
        card.classList.toggle('is-changed', isChanged);
        var flag = card.querySelector('.gls-flag');
        var input = card.querySelector('.gls-date-input');
        if (r.noDate) {
            // Rows that started with no date stay "New date required" until
            // the user picks something. There is no "rescheduled" semantics
            // for these — they only ever transition to "Set".
            if (r.newDate) {
                flag.textContent = 'Set';
                flag.className = 'gls-flag';
                input.classList.remove('is-required');
            } else {
                flag.textContent = 'New date required';
                flag.className = 'gls-flag req';
            }
        } else if (r.overdue) {
            if (isChanged) {
                flag.textContent = 'Rescheduled';
                flag.className = 'gls-flag';
                input.classList.remove('is-required');
            } else {
                flag.textContent = 'New date required';
                flag.className = 'gls-flag req';
            }
        } else {
            flag.textContent = 'Date changed';
            flag.className = 'gls-flag';
        }
    }

    function refreshSummary() {
        var changed = 0, needsPast = 0, needsNoDate = 0;
        STATE.rows.forEach(function (r) {
            if ((r.newDate || '') !== (r.currentDate || '')) changed++;
            // Rows that need a fresh pick before sign-off can complete:
            //   - past-due rows whose date hasn't been changed
            //   - no-date rows that still have no date selected
            if (r.overdue && (r.newDate || '') === (r.currentDate || '')) needsPast++;
            if (r.noDate && !r.newDate) needsNoDate++;
        });
        var s = STATE.rows.length + ' enhancement' + (STATE.rows.length === 1 ? '' : 's')
            + ' · ' + changed + ' date change' + (changed === 1 ? '' : 's');
        if (needsPast > 0)   s += ' · ' + needsPast + ' past due';
        if (needsNoDate > 0) s += ' · ' + needsNoDate + ' no date set';
        document.getElementById('glsSummary').textContent = s;
        document.querySelectorAll('.gls-card').forEach(function (c, i) { refreshOneCard(i); });
        return needsPast + needsNoDate;
    }

    function showError(msg) {
        var el = document.getElementById('glsErr');
        el.textContent = msg;
        el.classList.add('is-on');
    }
    function clearError() {
        var el = document.getElementById('glsErr');
        el.textContent = '';
        el.classList.remove('is-on');
    }
    function showBlock(msg) {
        var frame = document.getElementById('glsFrame');
        var el = document.getElementById('glsBlockNote');
        if (msg) el.textContent = msg;
        frame.classList.add('has-block');
    }
    function clearBlock() {
        document.getElementById('glsFrame').classList.remove('has-block');
    }

    function submit() {
        if (STATE.submitting) return;
        clearError();
        var needs = refreshSummary();
        if (needs > 0) {
            var noun = STATE.flow === 'GOLIVE' ? 'enhancement' : 'ECN';
            showBlock(needs + ' ' + noun + (needs === 1 ? '' : 's') + ' still need a new date before you can sign off (past-due or never filled in).');
            STATE.rows.forEach(function (r, i) {
                var blocked = (r.overdue && (r.newDate || '') === (r.currentDate || ''))
                           || (r.noDate && !r.newDate);
                if (blocked) {
                    var inp = document.querySelector('.gls-date-input[data-idx="' + i + '"]');
                    if (inp) inp.classList.add('is-required');
                }
            });
            return;
        }
        clearBlock();
        // Validate all newDates server-side anyway, but warn client-side too.
        for (var i = 0; i < STATE.rows.length; i++) {
            var r = STATE.rows[i];
            if (!r.newDate) { showError('ECN ' + r.ecnNumber + ' is missing a date.'); return; }
            if (r.newDate < STATE.today) { showError(r.ecnNumber + ': date must be today or later.'); return; }
        }
        var login = document.getElementById('glsLoginInput').value.trim();
        var pass = document.getElementById('glsPass').value;
        if (!login) { showError('Enter your Agile loginid.'); document.getElementById('glsLoginInput').focus(); return; }
        if (!pass) { showError('Enter your Agile password.'); document.getElementById('glsPass').focus(); return; }

        var changes = STATE.rows.map(function (r) {
            return { ecnNumber: r.ecnNumber, newDate: r.newDate };
        });
        STATE.submitting = true;
        var btn = document.getElementById('glsSubmit');
        btn.disabled = true;
        var origText = btn.textContent;
        btn.textContent = 'Writing to Agile…';

        fetch('/api/golive-signoff/submit', {
            method: 'POST',
            credentials: 'omit',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                token: STATE.token,
                samAccountName: login,
                password: pass,
                changes: changes
            })
        })
            .then(function (r) { return r.json().then(function (b) { return { status: r.status, body: b }; }); })
            .then(function (pkt) {
                STATE.submitting = false;
                btn.disabled = false;
                btn.textContent = origText;
                if (pkt.body && pkt.body.ok) {
                    document.getElementById('glsPass').value = '';
                    renderSuccess(pkt.body.results || []);
                } else {
                    var msg = (pkt.body && pkt.body.error) ? pkt.body.error : ('HTTP ' + pkt.status);
                    showError(msg);
                    if (pkt.body && pkt.body.tokenLocked) {
                        btn.disabled = true;
                    }
                }
            })
            .catch(function (e) {
                STATE.submitting = false;
                btn.disabled = false;
                btn.textContent = origText;
                showError('Network error: ' + e.message);
            });
    }

    function renderSuccess(results) {
        var frame = document.getElementById('glsFrame');
        frame.classList.add('is-done');
        var sub = document.getElementById('glsSuccessSub');
        var now = new Date();
        var timeStr = now.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
            + ', ' + now.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
        sub.textContent = 'Signed off by ' + STATE.displayName + ' (' + STATE.loginId + ') · ' + timeStr;
        var rowsEl = document.getElementById('glsResultRows');
        var html = '';
        var nounStr = noun();
        results.forEach(function (rr) {
            var label, cls;
            if (!rr.ok) {
                label = 'Error';
                cls = 'err';
            } else if (rr.status === 'rescheduled') {
                label = 'Rescheduled';
                cls = 'over';
            } else if (rr.status === 'changed') {
                label = 'Updated';
                cls = 'upd';
            } else {
                label = 'Confirmed';
                cls = '';
            }
            var what;
            if (!rr.ok) {
                what = (rr.error || 'save failed');
            } else if (rr.status === 'rescheduled') {
                what = nounStr + ' rescheduled · ' + fmt(rr.oldDate) + ' → ' + fmt(rr.newDate);
            } else if (rr.status === 'changed') {
                what = nounStr + ' changed · ' + fmt(rr.oldDate) + ' → ' + fmt(rr.newDate);
            } else {
                what = nounStr + ' confirmed · ' + fmt(rr.oldDate);
            }
            html += '<div class="gls-result-row">'
                + '<span class="ecn">' + escapeHtml(rr.ecnNumber || '') + '</span>'
                + '<span class="what">' + escapeHtml(what) + '</span>'
                + '<span class="ok ' + cls + '">' + label + '</span>'
                + '</div>';
        });
        rowsEl.innerHTML = html;
        document.getElementById('glsMailNote').innerHTML = 'Confirmation sent to your inbox &middot; cc ' + escapeHtml(STATE.ccAddr);
    }

    function showFatal(heading, message) {
        document.getElementById('glsLoading').style.display = 'none';
        document.getElementById('glsBody').style.display = 'none';
        document.getElementById('glsSuccess').style.display = 'none';
        document.getElementById('glsFatal').style.display = '';
        document.getElementById('glsFatalH').textContent = heading;
        document.getElementById('glsFatalP').innerHTML = escapeHtml(message)
            + ' Contact <b>' + escapeHtml(STATE.ccAddr) + '</b>.';
        document.getElementById('glsFatalContact').textContent = STATE.ccAddr;
    }
})();
