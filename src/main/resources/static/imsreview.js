// =====================================================================
//  imsreview.js — IMS Review tab.
//
//  Two roles served by the same tab; server's GET /api/ims-review/role
//  decides which view renders:
//    - "admin"  → DCC operational dashboard (table + per-row send/cancel/get-file)
//    - "do_dm"  → DO/DM card view (their queue with 3-or-2 response buttons)
//
//  Side panel handles "Needs Change" + "Need Help" uploads from the DO/DM view.
//  Manual-Refresh pattern: tab click hits /role + the appropriate list endpoint
//  once; subsequent updates only happen on explicit Refresh / per-row action.
// =====================================================================

(function () {
    'use strict';

    var _state = {
        loaded: false,
        view: null,        // 'admin' | 'do_dm'
        adminRows: [],
        cardRows: [],
        meta: {},
        currentPanelDoc: null,   // { docNumber, drrNumber, mode: 'NEEDS_CHANGE' | 'NEED_HELP' }
        columnFilters: {},       // { docNumber: 'puneet', ... } — lowercased substring match
        ownerStatusFilter: null, // 'DISABLED' | 'NOT_FOUND' | null — set when user clicks an owner indicator
        // New-vs-Legacy DRR cutoff anchor (go-live). The tile classifier in
        // imsreview-classify.js treats a DRR created before this date as legacy.
        drrCreatedAfter: '2026-07-05',
        editOwnerCtx: null,      // { docNumber, owners: [...] } while the edit-owners modal is open
        // Dashboard-overhaul state (handoff 2026-05-29)
        tile: 'new.pending_response', // 'group.tile' key — see imsreview-classify.js
        sortKey: 'nextReviewDate',
        sortDir: 'asc',          // 'asc' | 'desc'
        selectedDocs: {}         // { docNumber: true } — keyed; per segment, cleared on segment switch
    };

    var _MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

    /** Format an ISO yyyy-mm-dd date as "12 Jun 2024". Falls back to the
     *  raw string for anything that isn't a clean ISO date. */
    function fmtDate(iso) {
        if (!iso || !/^\d{4}-\d{2}-\d{2}/.test(iso)) return esc(iso || '');
        var d = new Date(iso.substring(0, 10) + 'T00:00:00');
        if (isNaN(d.getTime())) return esc(iso);
        return d.getDate() + ' ' + _MONTHS[d.getMonth()] + ' ' + d.getFullYear();
    }

    /** Format an ISO yyyy-mm-dd as MM-DD-YYYY (no timestamp). '' for empty. */
    function fmtMMDDYYYY(iso) {
        if (!iso) return '';
        var s = String(iso).substring(0, 10);
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
        if (!m) return esc(iso);
        return m[2] + '-' + m[3] + '-' + m[1];
    }

    /** Prepend a tiny click-to-copy glyph in FRONT of a rendered number/link. */
    function withCopy(html, raw) {
        if (!raw) return html;
        var safe = esc(String(raw)).replace(/'/g, "\\'");
        return '<span onclick="imsCopyNum(\'' + safe + '\')" title="Copy ' + safe + '" '
             + 'style="cursor:pointer; margin-right:4px; color:#9CA3AF; font-size:10px; user-select:none;">&#9106;</span>' + html;
    }
    window.imsCopyNum = function (t) {
        // navigator.clipboard only exists on secure origins (https / localhost).
        // The toolkit is served over plain http on the QA/prod hosts, so fall
        // back to the legacy execCommand('copy') path there (works on http).
        function legacyCopy() {
            try {
                var ta = document.createElement('textarea');
                ta.value = t;
                ta.setAttribute('readonly', '');
                ta.style.position = 'fixed';
                ta.style.top = '-1000px';
                document.body.appendChild(ta);
                ta.select();
                ta.setSelectionRange(0, ta.value.length);
                var ok = document.execCommand('copy');
                document.body.removeChild(ta);
                showToast(ok ? 'Copied ' + t : 'Copy failed');
            } catch (e) { showToast('Copy failed'); }
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(t).then(function () { showToast('Copied ' + t); }, legacyCopy);
        } else {
            legacyCopy();
        }
    };

    /** "5d overdue" / "3mo overdue" / "2yr+ overdue" sublabel. Returns ''
     *  if the date is today or future. */
    function overdueLabel(iso) {
        if (!iso) return '';
        var t = new Date(); t.setHours(0,0,0,0);
        var d = new Date(iso.substring(0, 10) + 'T00:00:00');
        if (isNaN(d.getTime())) return '';
        var days = Math.floor((t.getTime() - d.getTime()) / (1000*60*60*24));
        if (days <= 0) return '';
        if (days < 30) return days + 'd overdue';
        var months = Math.floor(days / 30);
        if (months < 12) return months + 'mo overdue';
        return Math.floor(months/12) + 'yr+ overdue';
    }

    /** Bucket a row into one of three review-date bands for grouping. */
    function dateBand(iso) {
        if (!iso) return 'upcoming';
        var t = new Date(); t.setHours(0,0,0,0);
        var d = new Date(iso.substring(0, 10) + 'T00:00:00');
        if (isNaN(d.getTime())) return 'upcoming';
        var days = Math.floor((d.getTime() - t.getTime()) / (1000*60*60*24));
        if (days < 0) return 'overdue';
        if (days <= 30) return 'due_soon';
        return 'upcoming';
    }

    var _filterDebounceTimer = null;

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    // Prominent colored badge for an Agile change (DRR/DCO) workflow status —
    // replaces the faint grey "(status)" text that used to sit under the number.
    function changeStatusBadge(status) {
        var s = (status || '').trim();
        if (!s) return '';
        var lc = s.toLowerCase();
        var bg = '#eef0f3', fg = '#6B7280';
        if (lc === 'implemented' || lc === 'complete' || lc === 'completed') { bg = '#e8f5e9'; fg = '#1F8A4C'; } // done
        else if (lc === 'release' || lc === 'released')                      { bg = '#ede9fe'; fg = '#5B21B6'; } // released, awaiting impl
        else if (lc === 'ccb' || lc === 'ccbreviewedbyjv')                   { bg = '#fff3cd'; fg = '#856404'; } // in CCB review
        else if (lc === 'submit')                                            { bg = '#e8f0fe'; fg = '#1a3a5c'; } // submitted
        else if (lc === 'cancel' || lc === 'cancelled')                      { bg = '#fdeaea'; fg = '#B8342B'; } // cancelled
        return '<span style="display:inline-block; margin-top:3px; background:' + bg + '; color:' + fg
             + '; padding:1px 7px; border-radius:9px; font-size:10px; font-weight:700; letter-spacing:.02em;'
             + ' font-family:\'IBM Plex Sans\',Arial,sans-serif;">' + esc(s) + '</span>';
    }

    // Accepts a row object (preferred) or a raw status string (legacy callers).
    function statusPill(rOrStatus) {
        var r = (rOrStatus && typeof rOrStatus === 'object') ? rOrStatus : null;
        var status = r ? r.status : rOrStatus;
        var CC = (typeof ImsClassify !== 'undefined') ? ImsClassify : null;
        // Agile-closed wins: once the DRR/DCO reaches a terminal Agile status the
        // review is done, even though the toolkit queue status is still
        // DO_NEEDS_CHANGE (the toolkit never sees the post-submit Agile workflow).
        // Keep the Status column consistent with the Closed tile.
        if (r && CC && CC.imsAgileClosed && CC.imsAgileClosed(r)) {
            var astat = r.dcoStatus || r.drrStatus || 'Implemented';
            return '<span style="background:#e8f5e9; color:#1F8A4C; padding:2px 8px; border-radius:10px; font-weight:600;">'
                 + esc(astat + ' · Closed') + '</span>';
        }
        // DCO released but not yet implemented — still In Process. Surface it so
        // it's clear the change cleared CCB and just needs incorporating.
        if (r && CC && CC.imsDcoReleasedPending && CC.imsDcoReleasedPending(r)) {
            return '<span style="background:#ede9fe; color:#5B21B6; padding:2px 8px; border-radius:10px; font-weight:600;">'
                 + esc('DCO Released · awaiting implementation') + '</span>';
        }
        var bg = '#eef0f3', fg = '#444';
        var label = status;
        switch (status) {
            case 'NOT_SENT':         label = 'Not Sent';                bg = '#eef0f3'; fg = '#444';    break;
            case 'SENT_TO_DO':       label = 'Sent to DO';              bg = '#e8f0fe'; fg = '#1a3a5c'; break;
            case 'SENT_TO_DM':       label = 'Sent to DM';              bg = '#fff3cd'; fg = '#856404'; break;
            case 'DM_APPROVED':      label = 'DM Approved · Closed';    bg = '#e8f5e9'; fg = '#1F8A4C'; break;
            case 'DO_NEEDS_CHANGE':  label = 'DO: Needs Change';        bg = '#7C3AED22'; fg = '#5B21B6'; break;
            case 'DO_NEED_HELP':     label = 'Need Help';               bg = '#fdeaea'; fg = '#B8342B'; break;
            case 'CANCELLED':        label = 'Cancelled';               bg = '#eef0f3'; fg = '#888';    break;
        }
        return '<span style="background:' + bg + '; color:' + fg + '; padding:2px 8px; border-radius:10px; font-weight:600;">' + esc(label) + '</span>';
    }

    /** Status hint shown on cards a DO can SEE but doesn't need to ACT on
     *  (co-owner is handling, DM is reviewing, DCC hasn't kicked off yet). */
    function imsStatusPillForOwnerView(r) {
        var rcp = r.currentRecipient || '';
        switch (r.status) {
            case 'NOT_SENT':
                return '<div style="padding:8px 12px; background:#FAFAF7; border-left:3px solid #6B7280; border-radius:0 4px 4px 0; font-size:12px; color:#444;">'
                     + '<strong>Not started.</strong> Doc Control hasn\'t kicked off this review yet — you\'ll get an email when they do.'
                     + '</div>';
            case 'SENT_TO_DO':
                return '<div style="padding:8px 12px; background:#e8f0fe; border-left:3px solid #4a6fa5; border-radius:0 4px 4px 0; font-size:12px; color:#1a3a5c;">'
                     + '<strong>Sent to co-owner.</strong> ' + esc(rcp) + ' is reviewing this one — you don\'t need to do anything.'
                     + '</div>';
            case 'SENT_TO_DM':
                return '<div style="padding:8px 12px; background:#fff3cd; border-left:3px solid #C7801B; border-radius:0 4px 4px 0; font-size:12px; color:#856404;">'
                     + '<strong>Waiting on manager.</strong> The DO confirmed <em>No Change Needed</em>; the manager (' + esc(rcp) + ') needs to approve.'
                     + '</div>';
            default:
                return '<div style="padding:8px 12px; background:#FAFAF7; border-left:3px solid #6B7280; border-radius:0 4px 4px 0; font-size:12px; color:#444;">'
                     + statusPill(r)
                     + '</div>';
        }
    }

    // ----------------------------------------------------------------------
    // URL helpers — ?asDO=true flips admins/DCC to the DO card view
    // ----------------------------------------------------------------------
    function getAsDOFlag() {
        try {
            var p = new URLSearchParams(window.location.search);
            return p.get('asDO') === 'true';
        } catch (e) { return false; }
    }

    function setAsDOFlag(val) {
        try {
            var p = new URLSearchParams(window.location.search);
            if (val) p.set('asDO', 'true');
            else p.delete('asDO');
            var search = p.toString();
            var newUrl = window.location.pathname
                       + (search ? '?' + search : '')
                       + window.location.hash;
            history.replaceState({}, '', newUrl);
        } catch (e) {}
    }

    window.imsSwitchToAdminView = function () {
        setAsDOFlag(false);
        imsReviewLoad();
    };

    window.imsSwitchToDoView = function () {
        setAsDOFlag(true);
        imsReviewLoad();
    };

    // ----------------------------------------------------------------------
    // Honor ?tab=ims-review in the URL on page load — the email's deep link
    // depends on this. Runs after the page + auth setup finishes (auth's own
    // DOMContentLoaded handler also needs to win first). Single one-shot.
    // ----------------------------------------------------------------------
    document.addEventListener('DOMContentLoaded', function () {
        try {
            var p = new URLSearchParams(window.location.search);
            var requested = p.get('tab');
            if (requested === 'ims-review') {
                // Wait a tick so the auth/permissions init finishes wiring tabs
                setTimeout(function () {
                    if (typeof window.switchTab === 'function') window.switchTab('ims-review');
                }, 250);
            }
        } catch (e) {}
    });

    // ----------------------------------------------------------------------
    // Entry point — fired by switchTab('ims-review')
    // ----------------------------------------------------------------------
    window.imsReviewLoad = function () {
        // Generic spinner until /role tells us which path we're on. Then the
        // admin branch swaps the label to "Running Agile query…" and the
        // card-view branch keeps the plain "Loading your queue…" so DOs aren't
        // misled into thinking a heavy SQL is running for their one item.
        showLoading(true, 'Loading…');
        var asDO = getAsDOFlag() ? 'true' : 'false';
        fetch('/api/ims-review/role?asDO=' + asDO, { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (role) {
                _state.view = role.view;
                _state.meta = role;
                if (role.view === 'admin') {
                    imsReviewRefresh();
                } else {
                    loadMyQueue();
                }
            })
            .catch(function (e) {
                showLoading(false);
                document.getElementById('imsReviewStatus').innerHTML =
                    '<span style="color:#B8342B;">Failed to load role: ' + esc(e.message) + '</span>';
            });
    };

    window.imsReviewRefresh = function (force) {
        // 2026-06-04: a force-refresh re-queries Agile (~22s SQL pull) and the
        // legacy showLoading() target sits at the BOTTOM of the panel — out of
        // sight while the user is looking at the toolbar. They saw nothing
        // change after clicking and reported "Refresh is dead." Use the
        // top-right pending toast (same pattern Send uses) so feedback is
        // visible immediately and gets swapped on completion. Soft refresh
        // (window-change autorefresh) is fast enough to skip the toast — just
        // re-render.
        var pendingToast = force
            ? (typeof showToast === 'function' ? showToast('Refreshing IMS Dashboard…', 'pending') : null)
            : null;
        // Keep the legacy bottom-of-panel spinner too so non-admin paths that
        // don't have showToast in scope still see something.
        showLoading(true, force ? 'Running Agile query…' : 'Loading…');
        var winEl = document.getElementById('imsReviewWindow');
        var days = winEl ? winEl.value : '30';
        var maxOverdueEl = document.getElementById('imsReviewMaxOverdueYears');
        var maxOverdueYears = maxOverdueEl ? maxOverdueEl.value : '5';
        var url = '/api/ims-review/data?days=' + encodeURIComponent(days)
                + '&maxOverdueYears=' + encodeURIComponent(maxOverdueYears)
                + (force ? '&refresh=true' : '');
        fetch(url, { credentials: 'same-origin' })
            .then(function (r) {
                if (!r.ok) {
                    return r.json().then(function (j) { throw new Error(j.error || ('HTTP ' + r.status)); });
                }
                return r.json();
            })
            .then(function (d) {
                _state.adminRows = d.rows || [];
                _state.meta = Object.assign({}, _state.meta, d);
                // Self-study training-guide link (also surfaced under the DCO
                // form's Training Requirement field via DCO.trainingDocUrl).
                _state.trainingDocUrl = (d && d.trainingDocUrl) || '';
                if (window.DCO) window.DCO.trainingDocUrl = _state.trainingDocUrl;
                _state.loaded = true;
                showLoading(false);
                imsReviewRender();
                if (pendingToast) {
                    var n = (d.rows || []).length;
                    pendingToast.replaceWith('Refreshed — ' + n + ' document' + (n === 1 ? '' : 's') + '.', 'ok');
                }
            })
            .catch(function (e) {
                showLoading(false);
                document.getElementById('imsReviewStatus').innerHTML =
                    '<span style="color:#B8342B;">Failed to load: ' + esc(e.message) + '</span>';
                if (pendingToast) pendingToast.replaceWith('Refresh failed: ' + e.message, 'err');
            });
    };

    function loadMyQueue() {
        showLoading(true, 'Loading your queue…');
        fetch('/api/ims-review/my-queue', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                _state.cardRows = d.rows || [];
                _state.loaded = true;
                showLoading(false);
                imsReviewRender();
            })
            .catch(function (e) {
                showLoading(false);
                document.getElementById('imsReviewStatus').innerHTML =
                    '<span style="color:#B8342B;">Failed to load queue: ' + esc(e.message) + '</span>';
            });
    }

    window.imsReviewOnWindowChange = function () { imsReviewRefresh(); };

    /** Switch between the two IMS Dashboard sub-views: the doc grid
     *  (default) and the owner-change history table. Folded in from a
     *  short-lived standalone "Owner Audit" tab on 2026-06-05 per
     *  Vikas — anyone with IMS Dashboard access should see both. */
    window.imsReviewSwitchSubView = function (key) {
        var docs = document.getElementById('imsReviewSubDocs');
        var oh   = document.getElementById('imsReviewSubOwnerHistory');
        var bDocs = document.getElementById('imsSubTabDocs');
        var bOH   = document.getElementById('imsSubTabOwnerHistory');
        var tools = document.getElementById('imsReviewAdminTools');
        var status = document.getElementById('imsReviewStatus');
        var showDocs = (key !== 'owner-history');
        if (docs)  docs.style.display  = showDocs ? '' : 'none';
        if (oh)    oh.style.display    = showDocs ? 'none' : '';
        // The IMS-level toolbar (Window / Max overdue / Status / Refresh /
        // Export) is irrelevant on the owner-history view — hide it.
        if (tools) tools.style.display = showDocs ? '' : 'none';
        if (status) status.style.display = showDocs ? '' : 'none';
        function styleTab(btn, active, accent) {
            if (!btn) return;
            btn.style.color = active ? '#0F1720' : '#6B7280';
            btn.style.fontWeight = active ? '600' : '500';
            btn.style.borderBottomColor = active ? accent : 'transparent';
        }
        styleTab(bDocs, showDocs, '#4a6fa5');
        styleTab(bOH, !showDocs, '#4a6fa5');
        if (!showDocs && typeof window.ownerAuditInit === 'function') {
            window.ownerAuditInit();
        }
    };

    // ----------------------------------------------------------------------
    // Render
    // ----------------------------------------------------------------------
    window.imsReviewRender = function () {
        var adminBox = document.getElementById('imsReviewAdminContainer');
        var cardBox  = document.getElementById('imsReviewCardContainer');
        var kpiBox   = document.getElementById('imsReviewKpi');
        var emptyBox = document.getElementById('imsReviewEmpty');
        var toolsBox = document.getElementById('imsReviewAdminTools');
        if (emptyBox) emptyBox.style.display = 'none';

        if (_state.view === 'admin') {
            if (cardBox)  cardBox.style.display = 'none';
            if (adminBox) adminBox.style.display = '';
            if (toolsBox) toolsBox.style.display = 'flex';
            // Export button visible only when user could have hit /export (admin
            // or explicit `ims-review` grant). Auto-granted DO/DM don't get it.
            var exportBtn = document.getElementById('imsReviewExportBtn');
            if (exportBtn) exportBtn.style.display = (_state.meta && _state.meta.canSeeAdminView) ? '' : 'none';
            renderAdminTable(adminBox);
            renderKpiStrip(kpiBox);
            updateAdminStatus();
        } else {
            if (adminBox) adminBox.style.display = 'none';
            if (toolsBox) toolsBox.style.display = 'none';
            if (kpiBox)   kpiBox.style.display = 'none';
            if (cardBox)  cardBox.style.display = '';
            renderCardView(cardBox);
            updateCardStatus();
        }
    };

    function updateAdminStatus() {
        var s = document.getElementById('imsReviewStatus');
        if (!s) return;
        var d = _state.meta || {};
        var line = 'Next ' + (d.daysAhead || 30) + ' days · ' + (d.count || 0) + ' docs';
        if (d.droppedAsTooOverdue && d.droppedAsTooOverdue > 0) {
            line += ' (hiding ' + d.droppedAsTooOverdue + ' docs overdue by more than '
                  + (d.maxOverdueYears || 5) + 'y)';
        }
        s.textContent = line;
    }

    function updateCardStatus() {
        var s = document.getElementById('imsReviewStatus');
        if (!s) return;
        var n = _state.cardRows.length;
        s.textContent = n === 1 ? '1 document waiting for you' : (n + ' documents waiting for you');
    }

    // Presentation metadata for the grouped tile strip. Classification lives
    // in imsreview-classify.js; this is purely labels/colors/order.
    var TILE_GROUPS = [
        { group: 'new', label: 'New DRR', hint: 'after go-live / via dashboard', tiles: [
            { tile: 'in_process',       label: 'In Process',       color: '#1F8A4C', sub: 'DRR/DCO at Submit / CCB / Release' },
            { tile: 'pending_response', label: 'Pending Response', color: '#C7801B', sub: 'awaiting owner response' },
            { tile: 'need_owner',       label: 'Need Owner',       color: '#B8342B', sub: 'all owners inactive' },
            { tile: 'need_help',        label: 'Need Help',        color: '#5B21B6', sub: 'owner asked for help' },
            { tile: 'closed',           label: 'Closed',           color: '#0F1720', sub: 'DRR closed' }
        ] },
        { group: 'legacy', label: 'Legacy DRR', hint: 'via old process', tiles: [
            { tile: 'in_process',       label: 'In Process',       color: '#1F8A4C', sub: 'DRR/DCO at Submit / CCB / Release' },
            { tile: 'pending_response', label: 'Pending Response', color: '#C7801B', sub: 'DRR still at Pending' },
            { tile: 'need_owner',       label: 'Need Owner',       color: '#B8342B', sub: 'all owners inactive' }
        ] },
        { group: 'need_drr', label: 'Need DRR', hint: 'IMS doc due, no DRR', tiles: [
            { tile: 'drr_missing',      label: 'DRR Missing',      color: '#6B7280', sub: 'trigger a new DRR' },
            // PT-123 (Jimmy): the 'No DRR Required' tile is hidden only where the server
            // sets app.ims.hide-no-drr-tile=true (QA). It stays visible in prod (default
            // false) until the change is promoted. renderKpiStrip drops it when meta.hideNoDrrTile.
            { tile: 'no_drr_required',  label: 'No DRR Required',  color: '#6B7280', sub: 'exempt doc style' }
        ] }
    ];

    /** Coordinator dashboard header: owner-missing banner + three grouped
     *  tile sections (New DRR / Legacy DRR / Need DRR). Counts come from
     *  ImsClassify.imsTileCounts over the locally-held adminRows. */
    function renderKpiStrip(kpiBox) {
        if (!kpiBox) return;
        var legacySelect = document.getElementById('imsReviewStatusFilter');
        if (legacySelect && legacySelect.parentElement) {
            legacySelect.parentElement.style.display = 'none';
        }
        var d = _state.meta || {};
        var counts = ImsClassify.imsTileCounts(_state.adminRows || [], _state.drrCreatedAfter);

        // ---- Three grouped tile sections ---------------------------------
        // Each group is flex-weighted by its tile count so every tile across
        // the three groups ends up roughly the same width; tiles within a
        // group sit on a single row (no wrap).
        var groupsHtml = '<div style="display:flex; gap:12px; margin-top:14px; flex-wrap:wrap;">';
        // PT-123: hide the 'No DRR Required' tile only when the server flags it
        // (app.ims.hide-no-drr-tile=true — QA). Prod default keeps it visible.
        var hideNoDrr = !!d.hideNoDrrTile;
        TILE_GROUPS.forEach(function (g) {
            var tiles = hideNoDrr ? g.tiles.filter(function (t) { return t.tile !== 'no_drr_required'; }) : g.tiles;
            if (tiles.length === 0) return;
            groupsHtml += '<div style="flex:' + tiles.length + ' 1 0; min-width:' + (tiles.length * 96) + 'px; border:1px solid #E8E6DF; border-radius:8px; background:#fff; overflow:hidden;">'
                        + '<div style="padding:6px 10px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
                        +   '<div style="font-size:11.5px; font-weight:700; color:#0F1720;">' + esc(g.label) + '</div>'
                        +   '<div style="font-size:10px; color:#6B7280;">' + esc(g.hint) + '</div>'
                        + '</div>'
                        + '<div style="display:flex;">';
            tiles.forEach(function (t) {
                var key = g.group + '.' + t.tile;
                var active = _state.tile === key;
                var bg = active ? '#FAFAF7' : '#fff';
                var topBorder = active ? '3px solid ' + t.color : '3px solid transparent';
                groupsHtml += '<button role="tab" onclick="imsTileClick(\'' + key + '\')" title="' + esc(t.sub) + '" '
                            + 'style="flex:1 1 0; min-width:0; background:' + bg + '; border:0; border-top:' + topBorder
                            + '; border-right:1px solid #E8E6DF; padding:7px 8px; cursor:pointer; text-align:left;">'
                            + '<div style="font-size:9.5px; text-transform:uppercase; letter-spacing:0.3px; color:#6B7280; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">' + esc(t.label) + '</div>'
                            + '<div style="font-size:17px; font-weight:700; color:' + t.color + ';">' + (counts[key] || 0).toLocaleString() + '</div>'
                            + '</button>';
            });
            groupsHtml += '</div></div>';
        });
        groupsHtml += '</div>';

        kpiBox.innerHTML = groupsHtml;
        kpiBox.style.display = 'block';
        kpiBox.style.gridTemplateColumns = '';
        kpiBox.style.background = 'transparent';
        kpiBox.style.gap = '';
    }

    /** Set or clear the owner-status filter from a per-owner indicator click.
     *  Distinct from segments (which the segmented control owns) — the
     *  indicator filter restricts the currently visible segment further. */
    window.imsSetOwnerStatusFilter = function (status) {
        _state.ownerStatusFilter = status || null;
        imsReviewRender();
    };

    window.imsTileClick = function (key) {
        _state.tile = key;
        _state.selectedDocs = {};   // clear selection on tile switch
        imsReviewRender();
    };

    function renderAdminTable(adminBox) {
        if (!adminBox) return;
        var rows = filterAdminRows();
        // Hint: if this user is ALSO a DO/DM with pending items, offer the switch
        var personalHint = '';
        if (_state.meta && _state.meta.queueSize > 0) {
            var n = _state.meta.queueSize;
            personalHint = '<div style="background:#e8f0fe; border:1px solid #b4d0fe; border-radius:6px; padding:8px 14px; margin:0 0 12px; font-size:12.5px; color:#1a3a5c;">'
                         + 'You have <strong>' + n + ' document' + (n === 1 ? '' : 's') + '</strong> waiting for your review as a DO/DM. '
                         + '<a href="#" onclick="imsSwitchToDoView(); return false;" style="color:#4a6fa5; font-weight:600; text-decoration:none;">Open your queue &rarr;</a>'
                         + '</div>';
        }
        if (rows.length === 0) {
            // Don't hide the filter row — that's a UX trap (user can't see the
            // filter that's nuking their results, can't clear it). Show an
            // explicit "Clear column filters" button + the filter row, so they
            // can recover without reloading.
            var activeFilterCount = Object.keys(_state.columnFilters || {}).length;
            var clearBtn = activeFilterCount > 0
                ? '<button onclick="imsClearColumnFilters()" style="margin:6px 0 0; padding:4px 10px; font-size:11px; background:#fff; color:#4a6fa5; border:1px solid #E8E6DF; border-radius:4px; cursor:pointer;">&times; Clear column filters (' + activeFilterCount + ')</button>'
                : '';
            var emptyMsg = 'No matching documents — try a wider window, a different tile, or clear the column filters below.';
            adminBox.innerHTML = personalHint
                + '<p style="color:#6B7280; text-align:center; padding:20px 30px 6px;">' + emptyMsg + '</p>'
                + '<div style="text-align:center; margin-bottom:14px;">' + clearBtn + '</div>'
                + renderEmptyTableShellWithFilters();
            return;
        }
        // Sort by current sortKey / sortDir before render. Default: nextReviewDate asc
        // (most overdue first — matches handoff §4 triage order).
        rows = sortAdminRows(rows);
        var tileKey = _state.tile || 'new.pending_response';
        var canBulk = canBulkActOnTile(tileKey);

        var html = personalHint;
        html += renderBulkBar(rows, tileKey);
        html += '<table style="width:100%; border-collapse:collapse; font-size:12px; background:#fff;">';
        html += '<thead><tr style="background:#2c3e50; color:#fff;">'
              + (canBulk ? '<th style="text-align:center; padding:8px 6px; width:32px;"><input type="checkbox" onclick="imsToggleAllSelected(this.checked)" title="Select all visible" id="imsSelectAll"></th>' : '<th style="width:0; padding:0;"></th>')
              + sortableTh('docNumber',       'Number / Description')
              + sortableTh('drrNumber',       'DRR')
              + '<th style="text-align:left; padding:8px 10px;">DRR Created</th>'
              + '<th style="text-align:left; padding:8px 10px;">DCO</th>'
              + '<th style="text-align:left; padding:8px 10px;">DCO Created</th>'
              + sortableTh('owners',          'Owner(s)')
              + sortableTh('nextReviewDate',  'Next Review')
              + sortableTh('status',          'Status')
              + '<th style="text-align:left; padding:8px 10px;">File</th>'
              + '<th style="text-align:left; padding:8px 10px;">Action</th>'
              + '</tr>'
              + '<tr style="background:#FAFAF7;">'
              +   (canBulk ? '<td></td>' : '<td style="width:0; padding:0;"></td>')
              +   colFilterCell('docNumber',       'Filter doc # or description…', true)
              +   colFilterCell('drrNumber',       'DRR-…')
              +   colFilterCell('drrCreated',      'MM-DD-YYYY')
              +   colFilterCell('agileDco',        'DCO-…')
              +   colFilterCell('dcoCreated',      'MM-DD-YYYY')
              +   colFilterCell('ownerDisplay',    'name or loginid')
              +   colFilterCell('nextReviewDate',  'MM-DD-YYYY')
              +   colFilterCell('status',          'status')
              +   '<td></td>'
              +   '<td></td>'
              + '</tr>'
              + '</thead><tbody>';

        // Group-band dividers (Overdue / Due soon / Upcoming). Only render
        // group headers when sorted by nextReviewDate (any direction) — for
        // other sort keys the bands wouldn't be contiguous, so we drop them
        // to keep the table readable.
        var groupsActive = _state.sortKey === 'nextReviewDate';
        var prevBand = null;
        var colSpan = 11;
        var bandMeta = {
            overdue:  { label: 'Overdue · review date already passed · oldest first', bg: '#fdeaea', fg: '#721c24', accent: '#B8342B' },
            due_soon: { label: 'Due soon · within 30 days', bg: '#fff8e1', fg: '#5a4a1f', accent: '#C7801B' },
            upcoming: { label: 'Upcoming · more than 30 days out', bg: '#eef0f3', fg: '#444',    accent: '#6B7280' }
        };

        rows.forEach(function (r, i) {
            if (groupsActive) {
                var band = dateBand(r.nextReviewDate);
                if (band !== prevBand) {
                    var bm = bandMeta[band];
                    html += '<tr><td colspan="' + colSpan + '" '
                          + 'style="background:' + bm.bg + '; color:' + bm.fg
                          + '; border-left:4px solid ' + bm.accent
                          + '; padding:6px 12px; font-size:11px; font-weight:700; text-transform:uppercase; letter-spacing:0.5px;">'
                          + esc(bm.label) + '</td></tr>';
                    prevBand = band;
                }
            }
            var bg = i % 2 === 0 ? '#FAFAF7' : '#fff';
            var nrd = r.nextReviewDate || '';
            var od = overdueLabel(nrd);
            var doc = esc(r.docNumber).replace(/'/g, "\\'");

            html += '<tr style="background:' + bg + ';">';
            if (canBulk) {
                var checked = _state.selectedDocs[r.docNumber] ? 'checked' : '';
                html += '<td style="text-align:center; padding:7px 6px;">'
                      + '<input type="checkbox" data-doc="' + esc(r.docNumber) + '" onclick="imsToggleSelected(\'' + doc + '\', this.checked)" ' + checked + '></td>';
            } else {
                html += '<td style="width:0; padding:0;"></td>';
            }
            // Number / Description
            html += '<td style="padding:7px 10px;">'
                 + '<div style="font-family:monospace; font-weight:600;">' + withCopy(agileItemLink(r.docNumber), r.docNumber) + '</div>'
                 + '<div style="color:#6B7280; font-size:11px; margin-top:1px;">' + esc(r.description) + '</div>'
                 + '</td>';
            // DRR — number + copy, with the DRR's Agile status in (brackets) below
            html += '<td style="padding:7px 10px; font-family:monospace;">'
                 + (r.drrNumber ? withCopy(agileChangeLink(r.drrNumber), r.drrNumber) : '')
                 + (r.drrStatus ? '<div>' + changeStatusBadge(r.drrStatus) + '</div>' : '')
                 + '</td>';
            // DRR Created
            html += '<td style="padding:7px 10px; font-size:11px;">' + esc(fmtMMDDYYYY(r.drrCreated)) + '</td>';
            // DCO — number + copy, with the DCO's Agile status in (brackets) below
            var muted = '<span style="color:#9CA3AF;">—</span>';
            html += '<td style="padding:7px 10px; font-family:monospace;">'
                 + (r.agileDco ? withCopy(agileChangeLink(r.agileDco), r.agileDco) : muted)
                 + (r.dcoStatus ? '<div>' + changeStatusBadge(r.dcoStatus) + '</div>' : '')
                 + '</td>';
            // DCO Created
            html += '<td style="padding:7px 10px; font-size:11px;">' + (r.dcoCreated ? esc(fmtMMDDYYYY(r.dcoCreated)) : muted) + '</td>';
            // Owners
            html += '<td style="padding:7px 10px; color:#4a6fa5; font-size:11px;">' + renderOwnersCell(r) + '</td>';
            // Next Review (MM-DD-YYYY) with overdue sublabel
            html += '<td style="padding:7px 10px;">'
                 + '<div style="' + (od ? 'color:#B8342B; font-weight:600;' : '') + '">' + esc(fmtMMDDYYYY(nrd)) + '</div>'
                 + (od ? '<div style="font-size:10.5px; color:#B8342B; margin-top:1px;">' + esc(od) + '</div>' : '')
                 + '</td>'
                 // Status (DCO now has its own column, so renderDcoSubLink is dropped here)
                 + '<td style="padding:7px 10px;">' + statusPill(r) + renderHelpNote(r) + '</td>'
                 + '<td style="padding:7px 10px;"><button onclick="imsGetFile(\'' + doc + '\')" style="padding:4px 8px; font-size:11px; background:#fff; color:#4a6fa5; border:1px solid #E8E6DF; border-radius:4px; cursor:pointer;">⬇ Get File</button>'
                 + renderUploadedAttachmentLink(r) + '</td>'
                 + '<td style="padding:7px 10px;">' + segmentActionHtml(r) + '</td>'
                 + '</tr>';
        });
        html += '</tbody></table>';
        adminBox.innerHTML = html;
    }

    // ====================================================================
    // Dashboard overhaul helpers (handoff 2026-05-29)
    // ====================================================================

    /** Sort the filtered rows by current sortKey/sortDir. Stable-ish. */
    function sortAdminRows(rows) {
        var key = _state.sortKey || 'nextReviewDate';
        var dir = _state.sortDir === 'desc' ? -1 : 1;
        var copy = rows.slice();
        copy.sort(function (a, b) {
            var av, bv;
            if (key === 'owners') {
                av = (a.ownerDisplay || '').toLowerCase();
                bv = (b.ownerDisplay || '').toLowerCase();
            } else {
                av = a[key] == null ? '' : String(a[key]).toLowerCase();
                bv = b[key] == null ? '' : String(b[key]).toLowerCase();
            }
            if (av < bv) return -1 * dir;
            if (av > bv) return  1 * dir;
            return 0;
        });
        return copy;
    }

    /** Header cell that flips sort direction on click; renders the arrow. */
    function sortableTh(key, label) {
        var arrow = '';
        if (_state.sortKey === key) arrow = _state.sortDir === 'asc' ? ' &uarr;' : ' &darr;';
        return '<th onclick="imsSortBy(\'' + key + '\')" '
             + 'style="text-align:left; padding:8px 10px; cursor:pointer; user-select:none;" '
             + 'title="Click to sort by ' + esc(label) + '">'
             + esc(label) + '<span style="opacity:0.7;">' + arrow + '</span></th>';
    }
    window.imsSortBy = function (key) {
        if (_state.sortKey === key) {
            _state.sortDir = _state.sortDir === 'asc' ? 'desc' : 'asc';
        } else {
            _state.sortKey = key;
            _state.sortDir = 'asc';
        }
        imsReviewRender();
    };

    /** Bulk actions only make sense where every row's action is identical:
     *  Send (new pending-response = not-yet-sent) or Reassign (need-owner). */
    function canBulkActOnTile(key) {
        return key === 'new.pending_response' || key === 'new.need_owner' || key === 'legacy.need_owner';
    }

    function renderBulkBar(rows, key) {
        if (!canBulkActOnTile(key)) return '';
        var selectedCount = 0;
        rows.forEach(function (r) { if (_state.selectedDocs[r.docNumber]) selectedCount++; });
        var primary;
        if (key === 'new.pending_response') {
            primary = {
                label: '📤 Send ' + selectedCount + ' doc' + (selectedCount === 1 ? '' : 's') + ' to owners',
                color: '#2c3e50', onclick: 'imsBulkSend()', sub: 'all selected docs have a valid owner'
            };
        } else {
            primary = {
                label: '👤 Reassign ' + selectedCount + ' owner' + (selectedCount === 1 ? '' : 's') + '…',
                color: '#B8342B', onclick: 'imsBulkReassign()', sub: 'opens the edit-owners modal for each selected doc'
            };
        }
        var disabled = selectedCount === 0;
        return '<div style="position:sticky; top:0; z-index:10; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:10px 14px; margin:0 0 10px; display:flex; align-items:center; justify-content:space-between; gap:12px; flex-wrap:wrap;">'
             + '<div style="font-size:12.5px; color:#6B7280;">'
             +   (selectedCount === 0
                    ? 'Select rows below to enable bulk actions.'
                    : '<strong>' + selectedCount + '</strong> selected · ' + esc(primary.sub))
             + '</div>'
             + '<button onclick="' + primary.onclick + '" ' + (disabled ? 'disabled' : '')
             +   ' style="padding:8px 16px; background:' + (disabled ? '#9CA3AF' : primary.color)
             +   '; color:#fff; border:0; border-radius:5px; font-weight:600; cursor:' + (disabled ? 'not-allowed' : 'pointer') + ';">'
             +   esc(primary.label)
             + '</button>'
             + '</div>';
    }

    /** Per-row action, decided by the row's own state (not the visible tile)
     *  so it is always correct. Never offers a Send that would bounce. */
    function segmentActionHtml(r) {
        var doc = esc(r.docNumber).replace(/'/g, "\\'");
        var drr = esc(r.drrNumber || '').replace(/'/g, "\\'");
        var resetBtn = (_state.meta && _state.meta.canSeeAdminView)
            ? '<button onclick="imsReset(\'' + doc + '\', \'' + drr + '\')" '
              + 'title="Wipes the queue state for this doc; full history is preserved in queue.jsonl" '
              + 'style="padding:4px 8px; font-size:11px; background:#fff; color:#B8342B; border:1px solid #E8E6DF; border-radius:4px; margin-left:3px; cursor:pointer;">⟲ Reset</button>'
            : '';
        var unlockBtn = (_state.meta && _state.meta.isAdmin
                && (r.status === 'SENT_TO_DO' || r.status === 'SENT_TO_DM'))
            ? '<button onclick="imsUnlockToken(\'' + doc + '\')" '
              + 'title="Clear the password-attempt lockout on this doc\'s active link(s)" '
              + 'style="padding:4px 8px; font-size:11px; background:#fff; color:#5B21B6; border:1px solid #E8E6DF; border-radius:4px; margin-left:3px; cursor:pointer;">&#x1F511; Unlock</button>'
            : '';
        var hasDrr = (typeof r.hasDrr === 'boolean') ? r.hasDrr : !!(r.drrNumber && r.drrNumber.trim && r.drrNumber.trim().length > 0);
        if (!hasDrr) {
            if (r.drrExempt) {
                return '<span style="display:inline-block; padding:2px 9px; font-size:11px; background:#eef0f2; color:#6B7280; border:1px solid #E8E6DF; border-radius:10px;">No DRR required</span>';
            }
            // Prod guardrail: DRR creation is turned off on this site (use QA).
            // Only hide when the flag is explicitly false so older payloads
            // (flag absent) keep the existing behavior.
            if (_state.meta && _state.meta.drrCreationEnabled === false) {
                return '<span title="Create and test DRRs on the QA toolkit" style="color:#6B7280; font-size:11px;">DRR creation off (use QA)</span>';
            }
            var hv = (typeof r.hasValidOwner === 'boolean') ? r.hasValidOwner : false;
            return hv
                ? '<button onclick="imsSendNewDrr(\'' + doc + '\')" style="padding:4px 10px; font-size:11px; background:#2c3e50; color:#fff; border:0; border-radius:4px; font-weight:600; cursor:pointer;">&#10010; Send New DRR</button>'
                : '<span style="color:#6B7280; font-size:11px;">reassign owner first</span>';
        }
        var allLeft = (typeof r.allOwnersLeft === 'boolean') ? r.allOwnersLeft : false;
        if (allLeft) return '<button onclick="imsOpenEditOwners(\'' + doc + '\')" style="padding:4px 10px; font-size:11px; background:#B8342B; color:#fff; border:0; border-radius:4px; font-weight:600; cursor:pointer;">👤 Assign owner</button>';
        switch (r.status) {
            case 'NOT_SENT':
                return '<button onclick="imsSend(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 10px; font-size:11px; background:#2c3e50; color:#fff; border:0; border-radius:4px; font-weight:600; cursor:pointer;">📤 Send</button>';
            case 'SENT_TO_DO':
            case 'SENT_TO_DM':
            case 'DO_NEEDS_CHANGE':
                return '<button onclick="imsSend(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 8px; font-size:11px; background:#fff; color:#4a6fa5; border:1px solid #E8E6DF; border-radius:4px; margin-right:3px; cursor:pointer;">↻ Resend</button>'
                     + '<button onclick="imsCancel(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 8px; font-size:11px; background:#fff; color:#B8342B; border:1px solid #E8E6DF; border-radius:4px; cursor:pointer;">✕ Cancel</button>'
                     + unlockBtn + resetBtn;
            case 'DO_NEED_HELP':
                return '<span style="color:#6B7280; font-size:11px;">⌐ DCC handling</span>' + resetBtn;
            case 'DM_APPROVED':
            case 'CANCELLED':
                return '<span style="color:#6B7280; font-size:11px;">read-only</span>' + resetBtn;
            default:
                return '';
        }
    }

    // Agile webclient URL helpers — same patterns as ImsReviewEmailService
    // (agileLinkItem / agileLinkChange). Base URL comes from server meta so
    // env switches (test vs prod) need only one config change.
    function agileBase() {
        return (_state.meta && _state.meta.agileWebclientUrl) || 'https://plm.sandisk.com/Agile';
    }
    function agileItemLink(number) {
        if (!number) return '';
        var n = String(number).trim();
        if (!n) return '';
        var url = agileBase() + '/object/Part/' + encodeURIComponent(n) + '/tab/13';
        return '<a href="' + url + '" target="_blank" title="Open ' + esc(n) + ' in Agile (Files tab)"'
             + ' style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;">' + esc(n) + '</a>';
    }
    function agileChangeLink(number) {
        if (!number) return '';
        var n = String(number).trim();
        if (!n) return '';
        var dash = n.indexOf('-');
        var type = dash > 0 ? n.substring(0, dash) : 'Change';
        var url = agileBase() + '/object/' + encodeURIComponent(type) + '/' + encodeURIComponent(n);
        return '<a href="' + url + '" target="_blank" title="Open ' + esc(n) + ' in Agile"'
             + ' style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;">' + esc(n) + '</a>';
    }
    /** Renders one 📎 link per DO-uploaded attachment (Needs Change revised
     *  doc, or Need Help screenshots — now supports multiple files per
     *  upload event). Hits /api/ims-review/upload?docNumber=…&file=<stored>
     *  for each. Shown under the "Get File" button so DCC has both the
     *  IMS doc and the owner's submission(s) in one column. */
    function renderUploadedAttachmentLink(r) {
        var u = r.uploadedAttachment;
        if (!u) return '';
        var files = u.files || (u.filename ? [{ filename: u.filename, storedName: u.storedName || '' }] : []);
        if (!files.length) return '';
        var ctx = u.eventType === 'DO_RESPONSE_NEED_HELP' ? 'help'
                : u.eventType === 'DO_RESPONSE_NEEDS_CHANGE' ? 'revised'
                : 'attachment';
        return files.map(function (f) {
            var tooltip = 'Download the ' + ctx + ' attachment uploaded by the Document Owner (' + esc(f.filename) + ')';
            var url = '/api/ims-review/upload?docNumber=' + encodeURIComponent(r.docNumber)
                    + '&drrNumber=' + encodeURIComponent(r.drrNumber || '')
                    + (f.storedName ? '&file=' + encodeURIComponent(f.storedName) : '');
            var display = f.filename && f.filename.length > 28 ? f.filename.substring(0, 25) + '…' : f.filename;
            return '<a href="' + url + '" title="' + tooltip + '" '
                 + 'style="display:block; margin-top:4px; font-size:10.5px; color:#5B21B6; text-decoration:none;">'
                 + '&#128206; ' + esc(display)
                 + '</a>';
        }).join('');
    }

    /** Renders the Need-Help message the Document Owner typed, under the
     *  "Need Help" status pill. Previously this note was delivered only in the
     *  DCC "Need Help" email and was never visible in the toolkit (A72). Full
     *  text is in the title attribute; the inline copy is truncated. */
    function renderHelpNote(r) {
        var h = r.helpNote;
        if (!h || !h.note) return '';
        var full = h.note;
        var short = full.length > 90 ? full.substring(0, 87) + '…' : full;
        var by = h.by ? ' — ' + esc(h.by) : '';
        return '<div title="' + esc(full) + '" style="margin-top:4px; font-size:10.5px; color:#B8342B; '
             + 'background:#fdeaea; border-left:3px solid #B8342B; border-radius:0 4px 4px 0; padding:4px 6px; '
             + 'max-width:260px; white-space:normal; line-height:1.4;">'
             + '<span style="font-weight:600;">&#128172; Need-Help note' + by + ':</span> '
             + esc(short)
             + '</div>';
    }

    /** Plain comma-joined owner names — no badges. Used on the Needs-owner
     *  tab where the cell already conveys the single problem via one badge. */
    function renderOwnerNamesPlain(r) {
        var owners = r.owners || [];
        if (owners.length === 0) return '';
        return owners.map(function (o) { return esc(o.displayName || o.email || o.loginId || ''); }).join('; ');
    }

    // Selection -----------------------------------------------------------
    window.imsToggleSelected = function (docNumber, checked) {
        if (checked) _state.selectedDocs[docNumber] = true;
        else delete _state.selectedDocs[docNumber];
        // Re-render the bulk bar count without redrawing the full table —
        // we can just trigger a render since the table itself shows checked
        // state from _state.selectedDocs.
        imsReviewRender();
    };
    window.imsToggleAllSelected = function (checked) {
        var visible = filterAdminRows();
        if (checked) {
            visible.forEach(function (r) { _state.selectedDocs[r.docNumber] = true; });
        } else {
            visible.forEach(function (r) { delete _state.selectedDocs[r.docNumber]; });
        }
        imsReviewRender();
    };

    /** CTA from the owner-missing banner ("Review & reassign owners").
     *  Lands the user on the New-DRR need-owner tile, selects every visible
     *  row so the bulk "Reassign N owners" action bar is ready, and scrolls
     *  the table into view. */
    window.imsReviewReassignAll = function () {
        _state.tile = 'new.need_owner';
        _state.ownerStatusFilter = null;
        _state.selectedDocs = {};
        filterAdminRows().forEach(function (r) { _state.selectedDocs[r.docNumber] = true; });
        imsReviewRender();
        var box = document.getElementById('imsReviewAdminContainer');
        if (box && box.scrollIntoView) box.scrollIntoView({ behavior: 'smooth', block: 'start' });
    };

    // Bulk Send -----------------------------------------------------------
    window.imsBulkSend = function () {
        var docs = Object.keys(_state.selectedDocs).filter(function (k) { return _state.selectedDocs[k]; });
        if (!docs.length) return;
        if (!confirm('Send the IMS Dashboard email to the Document Owner for ' + docs.length + ' selected doc(s)?')) return;
        var rowsByDoc = {};
        _state.adminRows.forEach(function (r) { rowsByDoc[r.docNumber] = r; });
        var sent = 0, failed = 0;
        var promises = docs.map(function (docNumber) {
            var fd = new FormData();
            fd.append('docNumber', docNumber);
            fd.append('drrNumber', (rowsByDoc[docNumber] && rowsByDoc[docNumber].drrNumber) || '');
            return fetch('/api/ims-review/send', { method: 'POST', credentials: 'same-origin', body: fd })
                .then(function (r) {
                    if (r.ok) { sent++; return; }
                    failed++;
                })
                .catch(function () { failed++; });
        });
        showToast('Sending ' + docs.length + ' email(s)…', 'ok');
        Promise.all(promises).then(function () {
            _state.selectedDocs = {};
            showToast(sent + ' sent, ' + failed + ' failed. Refreshing…', failed ? 'err' : 'ok');
            imsReviewRefresh(true);
        });
    };

    // Bulk Reassign — steps through the selection one by one in the existing
    // single-doc modal. Each Save advances to the next; Cancel skips the rest.
    window.imsBulkReassign = function () {
        var docs = Object.keys(_state.selectedDocs).filter(function (k) { return _state.selectedDocs[k]; });
        if (!docs.length) return;
        if (!confirm('Step through the Edit-Owners modal for ' + docs.length + ' selected doc(s)?')) return;
        _state.bulkReassignQueue = docs.slice();
        imsBulkReassignNext();
    };
    window.imsBulkReassignNext = function () {
        var q = _state.bulkReassignQueue || [];
        if (!q.length) {
            showToast('Bulk reassign complete.', 'ok');
            _state.selectedDocs = {};
            imsReviewRefresh(true);
            return;
        }
        var next = q.shift();
        _state.bulkReassignQueue = q;
        imsOpenEditOwners(next);
    };

    function actionButtonsHtml(r) {
        var doc = esc(r.docNumber).replace(/'/g, "\\'");
        var drr = esc(r.drrNumber || '').replace(/'/g, "\\'");
        // Reset: "throw this doc back to NOT_SENT" escape hatch.
        // Always shown next to any non-NOT_SENT status so anyone with IMS
        // Review access can recover from a wrong recipient, cascade gone
        // sideways, or a test we want to re-run end-to-end. Server gates
        // identically (hasAdminOrDccAccess).
        var resetBtn = (_state.meta && _state.meta.canSeeAdminView)
            ? '<button onclick="imsReset(\'' + doc + '\', \'' + drr + '\')" '
              + 'title="Wipes the queue state for this doc; full history is preserved in queue.jsonl" '
              + 'style="padding:4px 8px; font-size:11px; background:#fff; color:#B8342B; border:1px solid #E8E6DF; border-radius:4px; margin-left:3px; cursor:pointer;">⟲ Reset</button>'
            : '';
        switch (r.status) {
            case 'NOT_SENT':
                return '<button onclick="imsSend(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 10px; font-size:11px; background:#2c3e50; color:#fff; border:0; border-radius:4px; font-weight:600; cursor:pointer;">📤 Send to DO</button>';
            case 'SENT_TO_DO':
            case 'SENT_TO_DM':
                return '<button onclick="imsSend(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 8px; font-size:11px; background:#fff; color:#4a6fa5; border:1px solid #E8E6DF; border-radius:4px; margin-right:3px; cursor:pointer;">↻ Resend</button>'
                     + '<button onclick="imsCancel(\'' + doc + '\', \'' + drr + '\')" style="padding:4px 8px; font-size:11px; background:#fff; color:#B8342B; border:1px solid #E8E6DF; border-radius:4px; cursor:pointer;">✕ Cancel</button>'
                     + resetBtn;
            case 'DM_APPROVED':
            case 'CANCELLED':
                return '<span style="color:#6B7280; font-size:11px;">read-only</span>' + resetBtn;
            case 'DO_NEEDS_CHANGE':
            case 'DO_NEED_HELP':
                return '<span style="color:#6B7280; font-size:11px;">⌐ DCC handling</span>' + resetBtn;
            default:
                return '';
        }
    }

    /** Render the Owner(s) cell with per-owner LDAP-status indicators and
     *  an inline ✎ button to open the edit-owners modal. Healthy owners
     *  show as plain names; only DISABLED / NOT_FOUND get a badge. Clicking
     *  a badge applies the corresponding KPI filter so the user sees every
     *  row with that problem. */
    function renderOwnersCell(r) {
        var owners = r.owners || [];
        if (owners.length === 0) {
            return '<span style="color:#9CA3AF;">&mdash;</span>'
                 + (canEditOwners() ? ownerEditButton(r) : '');
        }
        var parts = owners.map(function (o) {
            var name = esc(o.displayName || o.email || o.loginId || '');
            var badge = '';
            if (o.ldapStatus === 'DISABLED') {
                badge = ' <span title="SanDisk sign-in disabled — click to filter to all rows with disabled owners"'
                      + ' onclick="event.stopPropagation(); imsSetOwnerStatusFilter(\'DISABLED\');"'
                      + ' style="background:#fff3cd; color:#856404; padding:0 5px; border-radius:8px; font-size:10px; font-weight:600; cursor:pointer; border:1px solid #f0d068;">&#8856; disabled</span>';
            } else if (o.ldapStatus === 'NOT_FOUND') {
                badge = ' <span title="Has left SanDisk — click to filter to all rows with missing owners"'
                      + ' onclick="event.stopPropagation(); imsSetOwnerStatusFilter(\'NOT_FOUND\');"'
                      + ' style="background:#fdeaea; color:#B8342B; padding:0 5px; border-radius:8px; font-size:10px; font-weight:600; cursor:pointer; border:1px solid #f0a8a4;">&#8856; left SanDisk</span>';
            }
            return name + badge;
        });
        var html = parts.join('; ');
        if (canEditOwners()) html += ownerEditButton(r);
        return html;
    }

    function canEditOwners() {
        // 2026-06-02: opened from admin-only to "anyone who can see the admin
        // view" — i.e. admins + any user explicitly granted the ims-review tab
        // in User Management. Server-side gate on POST /admin/owner relaxed to
        // hasAdminOrDccAccess to match. Auto-granted DO/DM still don't get it
        // (canSeeAdminView is false for them).
        return !!(_state.meta && _state.meta.canSeeAdminView);
    }

    function ownerEditButton(r) {
        var doc = esc(r.docNumber).replace(/'/g, "\\'");
        return ' <a href="#" onclick="imsOpenEditOwners(\'' + doc + '\'); return false;"'
             + ' title="Edit Document Owners (writes back to Agile)"'
             + ' style="margin-left:6px; color:#6B7280; text-decoration:none; font-size:13px;">&#9998;</a>';
    }

    /** Open the edit-owners modal for a doc. Picks up the current owners
     *  from the row in _state.adminRows. */
    window.imsOpenEditOwners = function (docNumber) {
        var row = null;
        for (var i = 0; i < _state.adminRows.length; i++) {
            if (_state.adminRows[i].docNumber === docNumber) { row = _state.adminRows[i]; break; }
        }
        if (!row) { appAlert ? appAlert('Doc not found in cached rows. Click Refresh first.') : alert('Doc not found.'); return; }
        // Clone owner list so cancel doesn't mutate the table state
        _state.editOwnerCtx = {
            docNumber: docNumber,
            description: row.description || '',
            owners: (row.owners || []).map(function (o) {
                return { loginId: o.loginId, displayName: o.displayName, email: o.email, ldapStatus: o.ldapStatus };
            })
        };
        renderEditOwnersModal();
    };

    function renderEditOwnersModal() {
        var ctx = _state.editOwnerCtx;
        if (!ctx) return;
        var existing = document.getElementById('imsEditOwnersModal');
        if (existing) existing.remove();
        var chips = ctx.owners.map(function (o, idx) {
            var statusBadge = '';
            if (o.ldapStatus === 'DISABLED') statusBadge = ' <span style="background:#fff3cd; color:#856404; padding:0 5px; border-radius:8px; font-size:10px;">&#8856; disabled</span>';
            else if (o.ldapStatus === 'NOT_FOUND') statusBadge = ' <span style="background:#fdeaea; color:#B8342B; padding:0 5px; border-radius:8px; font-size:10px;">&#8856; left SanDisk</span>';
            var label = esc(o.displayName || o.email || o.loginId || '');
            var login = esc(o.loginId || '');
            return '<span style="display:inline-flex; align-items:center; gap:6px; background:#f3f4f6; border:1px solid #E8E6DF; border-radius:14px; padding:4px 10px; font-size:12px; margin:2px;">'
                 + label + statusBadge
                 + ' <span style="color:#9CA3AF; font-size:10.5px;">(' + login + ')</span>'
                 + ' <a href="#" onclick="imsRemoveOwnerChip(' + idx + '); return false;" title="Remove" style="color:#B8342B; text-decoration:none; font-weight:600;">&times;</a>'
                 + '</span>';
        }).join('');
        if (!chips) chips = '<em style="color:#6B7280;">No owners assigned. Add at least one below before saving.</em>';
        var html = '<div id="imsEditOwnersModal" style="position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:9000; display:flex; align-items:center; justify-content:center;">'
                 + '<div style="background:#fff; border-radius:8px; max-width:560px; width:92%; padding:20px 22px; box-shadow:0 8px 32px rgba(0,0,0,0.25);">'
                 +   '<div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">Edit Document Owners</div>'
                 +   '<h2 style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:18px; margin:4px 0 2px;">' + esc(ctx.docNumber) + '</h2>'
                 +   '<div style="font-size:12px; color:#6B7280; margin-bottom:12px;">' + esc(ctx.description) + '</div>'

                 +   '<div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280; margin-bottom:4px;">Current Owners</div>'
                 +   '<div id="imsOwnerChips" style="border:1px solid #E8E6DF; border-radius:6px; padding:8px; min-height:38px; margin-bottom:14px;">' + chips + '</div>'

                 +   '<div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280; margin-bottom:4px;">Add Owner (active Agile users only)</div>'
                 +   '<input id="imsOwnerSearch" type="text" placeholder="Type a name, email, or loginid…" autocomplete="off"'
                 +     ' oninput="imsOwnerSearchTypeahead()" '
                 +     ' style="width:100%; padding:8px 10px; font-size:13px; border:1px solid #E8E6DF; border-radius:5px;">'
                 +   '<div id="imsOwnerSearchResults" style="border:1px solid #E8E6DF; border-top:none; border-radius:0 0 5px 5px; max-height:180px; overflow-y:auto; display:none; background:#fff;"></div>'

                 +   '<div id="imsOwnerSaveStatus" style="font-size:11.5px; color:#6B7280; margin-top:14px; min-height:18px;"></div>'

                 +   '<div style="display:flex; justify-content:flex-end; gap:8px; margin-top:8px;">'
                 +     '<button onclick="imsCloseEditOwners()" style="padding:8px 16px; background:#fff; color:#444; border:1px solid #E8E6DF; border-radius:5px; cursor:pointer;">Cancel</button>'
                 +     '<button id="imsSaveOwnersBtn" onclick="imsSaveOwners()" style="padding:8px 18px; background:#2c3e50; color:#fff; border:0; border-radius:5px; cursor:pointer; font-weight:600;">Save to Agile</button>'
                 +   '</div>'
                 + '</div></div>';
        document.body.insertAdjacentHTML('beforeend', html);
        setTimeout(function () { var i = document.getElementById('imsOwnerSearch'); if (i) i.focus(); }, 50);
    }

    window.imsRemoveOwnerChip = function (idx) {
        if (!_state.editOwnerCtx) return;
        _state.editOwnerCtx.owners.splice(idx, 1);
        renderEditOwnersModal();
    };

    window.imsCloseEditOwners = function () {
        _state.editOwnerCtx = null;
        var m = document.getElementById('imsEditOwnersModal');
        if (m) m.remove();
        // If the user cancelled mid-bulk, ask whether to stop the rest of
        // the queue. Default = stop (it's a destructive user-cancel signal).
        if (_state.bulkReassignQueue && _state.bulkReassignQueue.length > 0) {
            var remaining = _state.bulkReassignQueue.length;
            if (confirm('Cancel the remaining ' + remaining + ' doc(s) in this bulk reassign?')) {
                _state.bulkReassignQueue = null;
                _state.selectedDocs = {};
                imsReviewRender();
            } else {
                imsBulkReassignNext();
            }
        }
    };

    var _ownerSearchDebounce = null;
    window.imsOwnerSearchTypeahead = function () {
        if (_ownerSearchDebounce) clearTimeout(_ownerSearchDebounce);
        _ownerSearchDebounce = setTimeout(function () {
            var inp = document.getElementById('imsOwnerSearch');
            var q = inp ? (inp.value || '').trim() : '';
            var box = document.getElementById('imsOwnerSearchResults');
            if (!box) return;
            if (q.length < 2) { box.style.display = 'none'; box.innerHTML = ''; return; }
            fetch('/api/ims-review/admin/users/search?q=' + encodeURIComponent(q) + '&limit=15')
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    var hits = (j && j.body && j.body.hits) || (j && j.hits) || [];
                    if (!hits.length) { box.style.display = 'block'; box.innerHTML = '<div style="padding:8px 12px; color:#9CA3AF; font-size:12px;">No matches.</div>'; return; }
                    var html = hits.map(function (h) {
                        var hLogin = esc(h.loginId || '');
                        var hName  = esc(h.displayName || '');
                        var hMail  = esc(h.email || '');
                        return '<div onclick="imsAddOwnerFromHit(\'' + hLogin + '\', \'' + hName.replace(/'/g, "&#39;") + '\', \'' + hMail.replace(/'/g, "&#39;") + '\')"'
                             + ' style="padding:7px 12px; cursor:pointer; border-bottom:1px solid #F3F4F6; font-size:12.5px;"'
                             + ' onmouseover="this.style.background=\'#f3f4f6\'" onmouseout="this.style.background=\'#fff\'">'
                             + '<strong>' + hName + '</strong>'
                             + ' <span style="color:#6B7280; font-size:11px;">(' + hLogin + ' &middot; ' + hMail + ')</span>'
                             + '</div>';
                    }).join('');
                    box.innerHTML = html;
                    box.style.display = 'block';
                })
                .catch(function () {
                    box.style.display = 'block';
                    box.innerHTML = '<div style="padding:8px 12px; color:#B8342B; font-size:12px;">Search failed. Try again.</div>';
                });
        }, 220);
    };

    window.imsAddOwnerFromHit = function (loginId, displayName, email) {
        if (!_state.editOwnerCtx) return;
        // De-dup by loginId (case-insensitive)
        var existing = _state.editOwnerCtx.owners;
        for (var i = 0; i < existing.length; i++) {
            if ((existing[i].loginId || '').toLowerCase() === (loginId || '').toLowerCase()) {
                var inp = document.getElementById('imsOwnerSearch'); if (inp) inp.value = '';
                var box = document.getElementById('imsOwnerSearchResults'); if (box) { box.style.display = 'none'; box.innerHTML = ''; }
                return;
            }
        }
        existing.push({ loginId: loginId, displayName: displayName, email: email, ldapStatus: 'ACTIVE' });
        // Auto-replace: once a real active owner is added, drop everyone who has
        // LEFT SanDisk (ldapStatus NOT_FOUND) so the user doesn't have to × them
        // off one by one. Active and disabled owners are kept.
        var beforeCount = _state.editOwnerCtx.owners.length;
        _state.editOwnerCtx.owners = _state.editOwnerCtx.owners.filter(function (o) {
            return o.ldapStatus !== 'NOT_FOUND';
        });
        var removedLeft = beforeCount - _state.editOwnerCtx.owners.length;
        renderEditOwnersModal();
        if (removedLeft > 0 && typeof showToast === 'function') {
            showToast('Removed ' + removedLeft + ' departed owner' + (removedLeft === 1 ? '' : 's') + ' (left SanDisk).', 'ok');
        }
    };

    window.imsSaveOwners = function () {
        var ctx = _state.editOwnerCtx;
        if (!ctx) return;
        var ids = ctx.owners.map(function (o) { return o.loginId; }).filter(Boolean);
        if (!ids.length) {
            var s = document.getElementById('imsOwnerSaveStatus');
            if (s) s.innerHTML = '<span style="color:#B8342B;">Add at least one owner before saving.</span>';
            return;
        }
        // Also send the displayName + email per owner so the backend can patch
        // the docs cache row in place (no 100+ second Agile rebuild). Server
        // falls back to bare loginIds if this list is missing.
        var ownersMeta = ctx.owners
            .filter(function (o) { return o.loginId; })
            .map(function (o) {
                return {
                    loginId: o.loginId,
                    displayName: o.displayName || '',
                    email: o.email || '',
                    ldapStatus: o.ldapStatus || 'ACTIVE'  // search-result rows are always ACTIVE
                };
            });
        var btn = document.getElementById('imsSaveOwnersBtn');
        if (btn) { btn.disabled = true; btn.textContent = 'Saving…'; }
        var statusEl = document.getElementById('imsOwnerSaveStatus');
        if (statusEl) statusEl.innerHTML = '<span style="color:#6B7280;">Writing back to Agile&hellip;</span>';
        fetch('/api/ims-review/admin/owner', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ docNumber: ctx.docNumber, loginIds: ids, owners: ownersMeta })
        }).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok, body: j }; });
        }).then(function (res) {
            if (res.ok && res.body && res.body.ok) {
                if (statusEl) statusEl.innerHTML = '<span style="color:#1F8A4C;">&#10003; Saved.</span>';
                setTimeout(function () {
                    imsCloseEditOwners();
                    // Backend patched the cache row in place, so a normal
                    // refresh (no ?refresh=true) returns the new owners
                    // instantly without re-pulling 1400+ docs from Agile.
                    if (_state.bulkReassignQueue && _state.bulkReassignQueue.length > 0) {
                        imsBulkReassignNext();
                    } else if (_state.bulkReassignQueue && _state.bulkReassignQueue.length === 0) {
                        showToast('Bulk reassign complete.', 'ok');
                        _state.bulkReassignQueue = null;
                        _state.selectedDocs = {};
                        imsReviewRefresh(false);
                    } else {
                        imsReviewRefresh(false);
                    }
                }, 500);
            } else {
                var msg = (res.body && (res.body.error || res.body.errorReason)) || 'Save failed.';
                if (statusEl) statusEl.innerHTML = '<span style="color:#B8342B;">' + esc(msg) + '</span>';
                if (btn) { btn.disabled = false; btn.textContent = 'Save to Agile'; }
            }
        }).catch(function (e) {
            if (statusEl) statusEl.innerHTML = '<span style="color:#B8342B;">Network error: ' + esc(e.message || e) + '</span>';
            if (btn) { btn.disabled = false; btn.textContent = 'Save to Agile'; }
        });
    };

    /** When admin filtering kills every row, render a minimal table head +
     *  filter row only — so the user can see (and edit / clear) the filters
     *  that nuked their results. */
    function renderEmptyTableShellWithFilters() {
        var canBulk = canBulkActOnTile(_state.tile);
        return '<table style="width:100%; border-collapse:collapse; font-size:12px; background:#fff;">'
             + '<thead><tr style="background:#2c3e50; color:#fff;">'
             +   (canBulk ? '<th style="width:32px; padding:0;"></th>' : '<th style="width:0; padding:0;"></th>')
             +   '<th style="text-align:left; padding:8px 10px;">Number / Description</th>'
             +   '<th style="text-align:left; padding:8px 10px;">DRR</th>'
             +   '<th style="text-align:left; padding:8px 10px;">DRR Created</th>'
             +   '<th style="text-align:left; padding:8px 10px;">DCO</th>'
             +   '<th style="text-align:left; padding:8px 10px;">DCO Created</th>'
             +   '<th style="text-align:left; padding:8px 10px;">Owner(s)</th>'
             +   '<th style="text-align:left; padding:8px 10px;">Next Review</th>'
             +   '<th style="text-align:left; padding:8px 10px;">Status</th>'
             +   '<th style="text-align:left; padding:8px 10px;">File</th>'
             +   '<th style="text-align:left; padding:8px 10px;">Action</th>'
             + '</tr>'
             + '<tr style="background:#FAFAF7;">'
             +   (canBulk ? '<td></td>' : '<td style="width:0; padding:0;"></td>')
             +   colFilterCell('docNumber',       'Filter doc # or description…', true)
             +   colFilterCell('drrNumber',       'DRR-…')
             +   colFilterCell('drrCreated',      'MM-DD-YYYY')
             +   colFilterCell('agileDco',        'DCO-…')
             +   colFilterCell('dcoCreated',      'MM-DD-YYYY')
             +   colFilterCell('ownerDisplay',    'name or loginid')
             +   colFilterCell('nextReviewDate',  'MM-DD-YYYY')
             +   colFilterCell('status',          'status')
             +   '<th></th><th></th>'
             + '</tr></thead></table>';
    }

    /** Clear all column filters and re-render. */
    window.imsClearColumnFilters = function () {
        _state.columnFilters = {};
        imsReviewRender();
    };

    function filterAdminRows() {
        var tileKey = _state.tile || 'new.pending_response';
        var colFilters = _state.columnFilters || {};
        var colKeys = Object.keys(colFilters);
        var ownerStatusFilter = _state.ownerStatusFilter;
        return _state.adminRows.filter(function (r) {
            // Tile filter — drives the headline split via the shared
            // classifier (New / Legacy / Need-DRR sub-tiles). Owns the
            // owner-NOT_FOUND vs status split structurally so the Send
            // button never appears on a row with a dead owner.
            if (ImsClassify.imsTileKey(r, _state.drrCreatedAfter) !== tileKey) return false;
            // Owner-status indicator filter (clicked on a per-owner badge)
            // — composes with the segment. Lets the user narrow inside any
            // tab (e.g. on "In flight", show only rows with a disabled owner).
            if (ownerStatusFilter) {
                var hit = false;
                var owners = r.owners || [];
                for (var oi = 0; oi < owners.length; oi++) {
                    if (owners[oi].ldapStatus === ownerStatusFilter) { hit = true; break; }
                }
                if (!hit) return false;
            }
            // Per-column substring filters (still composable on top of all the above).
            // Special-case: the combined Number/Description input lives on the
            // docNumber column key but matches either field.
            for (var i = 0; i < colKeys.length; i++) {
                var k = colKeys[i];
                var needle = colFilters[k];
                if (k === 'docNumber') {
                    var combined = ((r.docNumber || '') + ' ' + (r.description || '')).toLowerCase();
                    if (combined.indexOf(needle) < 0) return false;
                } else if (k === 'drrCreated' || k === 'dcoCreated' || k === 'nextReviewDate') {
                    // These columns DISPLAY MM-DD-YYYY but the row holds raw ISO;
                    // match either form so the placeholder "MM-DD-YYYY" works too.
                    var rawv = (r[k] || '').toString().toLowerCase();
                    var fmtv = fmtMMDDYYYY(r[k] || '').toLowerCase();
                    if (rawv.indexOf(needle) < 0 && fmtv.indexOf(needle) < 0) return false;
                } else {
                    var v = (r[k] || '').toString().toLowerCase();
                    if (v.indexOf(needle) < 0) return false;
                }
            }
            return true;
        });
    }

    window.imsApplyColumnFilters = function () {
        if (_filterDebounceTimer) clearTimeout(_filterDebounceTimer);
        _filterDebounceTimer = setTimeout(function () {
            // Capture which filter input has focus + cursor position BEFORE the
            // re-render destroys it, so we can restore after.
            var focused = null;
            var ae = document.activeElement;
            if (ae && ae.classList && ae.classList.contains('ims-col-filter')) {
                focused = {
                    col: ae.getAttribute('data-col'),
                    val: ae.value,
                    start: ae.selectionStart,
                    end:   ae.selectionEnd
                };
            }
            var next = {};
            document.querySelectorAll('.ims-col-filter').forEach(function (i) {
                var v = (i.value || '').trim();
                if (v) next[i.getAttribute('data-col')] = v.toLowerCase();
            });
            _state.columnFilters = next;
            imsReviewRender();
            // Restore focus + cursor on the same column's input
            if (focused) {
                var restored = document.querySelector('.ims-col-filter[data-col="' + focused.col + '"]');
                if (restored) {
                    if (restored.value !== focused.val) restored.value = focused.val;
                    restored.focus();
                    try { restored.setSelectionRange(focused.start, focused.end); } catch (e) {}
                }
            }
        }, 150);
    };

    window.imsClearColumnFilters = function () {
        document.querySelectorAll('.ims-col-filter').forEach(function (i) { i.value = ''; });
        _state.columnFilters = {};
        imsReviewRender();
    };

    function colFilterCell(col, placeholder, withClear) {
        var clearLink = withClear
            ? '<a href="#" onclick="imsClearColumnFilters(); return false;" title="Clear all column filters" style="position:absolute; right:4px; top:50%; transform:translateY(-50%); color:#9CA3AF; font-size:11px; text-decoration:none;">&#10005;</a>'
            : '';
        // Persist current filter value across re-renders (HTML-escape since
        // it goes into an attribute).
        var current = (_state.columnFilters || {})[col] || '';
        return '<td style="padding:4px 6px; position:relative;">'
             + '<input class="ims-col-filter" data-col="' + col + '" autocomplete="off" '
             + 'placeholder="' + placeholder + '" '
             + 'value="' + esc(current) + '" '
             + 'oninput="imsApplyColumnFilters()" '
             + 'style="width:calc(100% - ' + (withClear ? '18' : '4') + 'px); padding:3px 6px; font-size:11px; border:1px solid #E8E6DF; border-radius:3px;">'
             + clearLink
             + '</td>';
    }

    window.imsReviewExport = function () {
        var winEl = document.getElementById('imsReviewWindow');
        var days = winEl ? winEl.value : '30';
        window.location = '/api/ims-review/export?days=' + encodeURIComponent(days);
    };

    // ----------------------------------------------------------------------
    // DO/DM card view
    // ----------------------------------------------------------------------
    function renderCardView(cardBox) {
        if (!cardBox) return;
        // Switch-back link for users who normally see the admin view but landed
        // here via ?asDO=true (the email-link path)
        var switchBack = '';
        if (_state.meta && _state.meta.canSeeAdminView) {
            switchBack = '<div style="padding:10px 0 0;"><a href="#" onclick="imsSwitchToAdminView(); return false;" '
                       + 'style="font-size:12px; color:#4a6fa5; text-decoration:none;">'
                       + '&larr; Switch to admin view (see all docs in window)</a></div>';
        }
        if (_state.cardRows.length === 0) {
            cardBox.innerHTML = switchBack
                + '<p style="color:#6B7280; text-align:center; padding:40px;">Nothing waiting for you right now. <br><br>If you got an email expecting an item here, give the page a refresh — your manager may have already acted on it.</p>';
            return;
        }
        // Sort: action-required first, then informational ("you're an owner
        // but it's not your turn") underneath.
        var actionRows = _state.cardRows.filter(function (r) { return r.requiresMyAction !== false; });
        var infoRows = _state.cardRows.filter(function (r) { return r.requiresMyAction === false; });

        var html = switchBack;
        if (actionRows.length > 0) {
            html += '<div style="padding:14px 0;"><h2 style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:22px; font-weight:500; margin:0 0 4px;">'
                  + (actionRows.length === 1 ? '1 document waiting' : (actionRows.length + ' documents waiting'))
                  + ' for you</h2><p style="color:#6B7280; margin:0; font-size:13px;">Each card below is summarized here for reference. <strong>Respond from the IMS Document Review email Doc Control sent you</strong> — the email\'s action buttons open a secure response page where you sign with your AD password.</p></div>';
            actionRows.forEach(function (r) { html += renderCard(r); });
        } else {
            html += '<div style="padding:14px 0;"><h2 style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:22px; font-weight:500; margin:0 0 4px;">No action required from you right now</h2><p style="color:#6B7280; margin:0; font-size:13px;">The docs below are listed under you as an owner but the next step is on someone else.</p></div>';
        }
        if (infoRows.length > 0) {
            html += '<div style="margin:24px 0 8px; padding:8px 0; border-top:1px solid #E8E6DF;"><div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">Also pending under your name (' + infoRows.length + ')</div><p style="color:#6B7280; margin:4px 0 0; font-size:12px;">Listed here so you know what else is in motion. No action needed from you on these — yet.</p></div>';
            infoRows.forEach(function (r) { html += renderCard(r); });
        }
        cardBox.innerHTML = html;
    }

    function renderCard(r) {
        var doc = esc(r.docNumber).replace(/'/g, "\\'");
        var drr = esc(r.drrNumber || '').replace(/'/g, "\\'");
        var isDm = (r.role === 'DM');
        var needsAction = (r.requiresMyAction !== false);  // default true for back-compat
        var card = '';
        card += '<div style="margin:14px 0; background:#fff; border:1px solid #E8E6DF; border-radius:8px; overflow:hidden;' + (needsAction ? '' : ' opacity:0.92;') + '">';
        card += '  <div style="display:flex; gap:14px; padding:14px 18px; border-bottom:1px solid #E8E6DF; align-items:flex-start;">';
        card += '    <div style="flex:1;">';
        card += '      <div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:13px; color:#4a6fa5; font-weight:600;">' + esc(r.docNumber) + ' <span style="font-size:11px; color:#6B7280; font-weight:normal; margin-left:8px;">Rev ' + esc(r.rev || '') + ' · ' + esc(r.lifecyclePhase || '') + '</span></div>';
        card += '      <div style="font-size:15px; margin-top:2px;">' + esc(r.description || '') + '</div>';
        card += '      <div style="font-size:11px; color:#6B7280; margin-top:4px;">Document Type: ' + esc(r.documentType || '') + ' · DRR: <span style="color:#4a6fa5;">' + esc(r.drrNumber || '') + '</span></div>';
        if (isDm && r.doDisplayName) {
            card += '      <div style="margin-top:8px; padding:6px 10px; background:#e8f5e9; border-left:3px solid #1F8A4C; border-radius:0 4px 4px 0; font-size:12px; color:#1a4d2a;"><strong>' + esc(r.doDisplayName) + ' (DO)</strong> confirmed <em>No Change Needed</em> on ' + esc((r.doResponseDate || '').substring(0, 10)) + '. Your manager-level approval is requested.</div>';
        }
        card += '    </div>';
        card += '    <div style="text-align:right;">';
        card += '      <div style="font-size:11px; color:#6B7280;">Next Review</div>';
        card += '      <div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:13px; font-weight:600;">' + esc(r.nextReviewDate || '') + '</div>';
        card += '    </div>';
        card += '    <button onclick="imsGetFile(\'' + doc + '\')" style="padding:6px 12px; font-size:12px; background:#fff; color:#4a6fa5; border:1px solid #E8E6DF; border-radius:5px; cursor:pointer;">⬇ Get File</button>';
        card += '  </div>';
        card += '  <div style="padding:14px 18px; background:#FAFAF7;">';
        if (!needsAction) {
            // Owner-but-not-my-turn card: status pill only, no action buttons.
            // Tells the DO why this is on their list without making them feel
            // they need to do something. The matching action is happening
            // elsewhere (other co-owner, manager, or hasn't been kicked off
            // by DCC yet).
            card += '    <div>' + imsStatusPillForOwnerView(r) + '</div>';
            card += '  </div>';
            card += '</div>';
            return card;
        }
        // Approvals now happen via the email-link → password-gated PDF flow,
        // not in-tab. Card view becomes a read-only "what's pending for me"
        // dashboard with a pointer back to the email.
        card += '    <div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280; margin-bottom:8px;">'
              + (isDm ? 'Your decision as DM' : 'Your response') + '</div>';
        card += '    <div style="padding:10px 12px; background:#fff; border:1px solid #E8E6DF; border-radius:6px; font-size:12.5px; color:#0F1720;">'
              + '<strong>Respond from your email.</strong> Open the IMS Document Review email Doc Control sent you and click the action button '
              + '(' + (isDm ? '<em>Confirm No Change</em> or <em>Send Back to DO</em>' : '<em>No Change Needed</em>, <em>Needs Change · Upload</em>, or <em>Need Help</em>')
              + '). You\'ll confirm with your AD password and we generate a signed PDF for the DRR.'
              + '<div style="margin-top:6px; font-size:11px; color:#6B7280;">No email? Reply to <a href="mailto:pdl-plm-admin@sandisk.com" style="color:#4a6fa5;">pdl-plm-admin@sandisk.com</a> and we\'ll re-issue it.</div>'
              + '</div>';
        if (false) {  // legacy in-tab approval buttons — kept disabled for revert reference
            card += '    <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px;">';
            card += '      <button onclick="imsRespondSimple(\'' + doc + '\', \'' + drr + '\', \'DM_RESPONSE_APPROVED\')" style="text-align:left; padding:12px; background:#fff; border:1px solid #E8E6DF; border-radius:6px; cursor:pointer;">';
            card += '        <div style="font-weight:600; color:#1F8A4C; font-size:13px;">✓ Confirm No Change</div>';
            card += '        <div style="font-size:11px; color:#6B7280; margin-top:4px; line-height:1.4;">Closes the review. DCC will attach the confirmation and close the DRR.</div>';
            card += '      </button>';
            card += '      <button onclick="imsRespondSimple(\'' + doc + '\', \'' + drr + '\', \'DM_RESPONSE_SEND_BACK\')" style="text-align:left; padding:12px; background:#fff; border:1px solid #E8E6DF; border-radius:6px; cursor:pointer;">';
            card += '        <div style="font-weight:600; color:#5B21B6; font-size:13px;">↩ Send Back to DO</div>';
            card += '        <div style="font-size:11px; color:#6B7280; margin-top:4px; line-height:1.4;">DO needs to revise. Re-opens the DO step.</div>';
            card += '      </button>';
            card += '    </div>';
        } else if (false) {
            card += '    <div style="display:grid; grid-template-columns:repeat(3, 1fr); gap:10px;">';
            card += '      <button onclick="imsRespondSimple(\'' + doc + '\', \'' + drr + '\', \'DO_RESPONSE_NO_CHANGE\')" style="text-align:left; padding:12px; background:#fff; border:1px solid #E8E6DF; border-radius:6px; cursor:pointer;">';
            card += '        <div style="font-weight:600; color:#1F8A4C; font-size:13px;">✓ No Change Needed</div>';
            card += '        <div style="font-size:11px; color:#6B7280; margin-top:4px; line-height:1.4;">This IMS document is current and needs no change.</div>';
            card += '      </button>';
            card += '      <button onclick="imsOpenPanel(\'' + doc + '\', \'' + drr + '\', \'NEEDS_CHANGE\')" style="text-align:left; padding:12px; background:#fff; border:1px solid #E8E6DF; border-radius:6px; cursor:pointer;">';
            card += '        <div style="font-weight:600; color:#5B21B6; font-size:13px;">✎ Needs Change</div>';
            card += '        <div style="font-size:11px; color:#6B7280; margin-top:4px; line-height:1.4;">This document is outdated. I have an updated version to upload.</div>';
            card += '      </button>';
            card += '      <button onclick="imsOpenPanel(\'' + doc + '\', \'' + drr + '\', \'NEED_HELP\')" style="text-align:left; padding:12px; background:#fff; border:1px solid #E8E6DF; border-radius:6px; cursor:pointer;">';
            card += '        <div style="font-weight:600; color:#C7801B; font-size:13px;">? Need Help</div>';
            card += '        <div style="font-size:11px; color:#6B7280; margin-top:4px; line-height:1.4;">I need Doc Control\'s assistance to proceed.</div>';
            card += '      </button>';
            card += '    </div>';
        }
        card += '  </div>';
        card += '</div>';
        return card;
    }

    // ----------------------------------------------------------------------
    // Per-row admin actions
    // ----------------------------------------------------------------------
    window.imsSend = function (docNumber, drrNumber) {
        // 2026-06-03: dropped the native confirm() prompt. The ~2-4 second
        // server wait (Agile attachment fetch + LDAP probe + SMTP send) shows
        // a pending toast immediately so the user has feedback that something
        // is happening; gets replaced by the success/error toast when the
        // response arrives.
        var pending = showToast('Sending email for ' + docNumber + '…', 'pending');
        var fd = new FormData();
        fd.append('docNumber', docNumber);
        fd.append('drrNumber', drrNumber || '');
        fetch('/api/ims-review/send', { method: 'POST', credentials: 'same-origin', body: fd })
            .then(function (r) {
                if (!r.ok) return r.json().then(function (j) { throw new Error(j.error || ('HTTP ' + r.status)); });
                return r.json();
            })
            .then(function (q) {
                var recipient = (q && q.currentRecipient) ? q.currentRecipient : '(unknown)';
                pending.replaceWith('Email sent to ' + recipient + '.', 'ok');
                imsReviewRefresh();
            })
            .catch(function (e) { pending.replaceWith('Send failed: ' + e.message, 'err'); });
    };

    window.imsCancel = function (docNumber, drrNumber) {
        if (!confirm('Cancel the in-flight review for ' + docNumber + '? DO/DM will no longer see it.')) return;
        var fd = new FormData();
        fd.append('docNumber', docNumber);
        fd.append('drrNumber', drrNumber || '');
        fetch('/api/ims-review/cancel', { method: 'POST', credentials: 'same-origin', body: fd })
            .then(function (r) { return r.json(); })
            .then(function () { imsReviewRefresh(); })
            .catch(function (e) { showToast('Cancel failed: ' + e.message, 'err'); });
    };

    window.imsUnlockToken = function (docNumber) {
        if (!confirm('Clear the password-attempt lockout on the active link(s) for ' + docNumber + '?\n\nUse this when a recipient says they\'re getting "This link is temporarily locked" but they have the right password.')) return;
        var fd = new FormData();
        fd.append('docNumber', docNumber);
        fetch('/api/ims-review/admin/unlock-token', { method: 'POST', credentials: 'same-origin', body: fd })
            .then(function (r) { return r.json(); })
            .then(function (j) {
                if (j && j.ok) {
                    showToast('Unlocked. Recipient can retry the email link now (' + (j.cleared || 0) + ' counter(s) cleared).', 'ok');
                } else {
                    showToast('Unlock failed: ' + ((j && (j.error || j.errorReason)) || 'unknown'), 'err');
                }
            })
            .catch(function (e) { showToast('Unlock failed: ' + e.message, 'err'); });
    };

    window.imsReset = function (docNumber, drrNumber) {
        appConfirm(
            'Reset ' + docNumber + ' back to NOT_SENT?\n\n'
          + 'This wipes the queue state so Send re-runs from scratch. Past '
          + 'emails are not unsent, but the row goes back to the start and '
          + 'the prior history stays in queue.jsonl as an audit trail.',
            { danger: true, okText: 'Reset' }
        ).then(function (ok) {
            if (!ok) return;
            var fd = new FormData();
            fd.append('docNumber', docNumber);
            fd.append('drrNumber', drrNumber || '');
            fetch('/api/ims-review/reset', { method: 'POST', credentials: 'same-origin', body: fd })
                .then(function (r) {
                    if (!r.ok) return r.json().then(function (j) { throw new Error(j.error || ('HTTP ' + r.status)); });
                    return r.json();
                })
                .then(function () {
                    showToast(docNumber + ' reset to NOT_SENT', 'ok');
                    imsReviewRefresh();
                })
                .catch(function (e) { showToast('Reset failed: ' + e.message, 'err'); });
        });
    };

    window.imsSendNewDrr = function (doc) {
        appConfirm('Create a new DRR for ' + doc + ' and send it to its owner(s)?',
            { okText: 'Send New DRR' }
        ).then(function (ok) {
            if (!ok) return;
            fetch('/api/ims-review/create-drr?docNumber=' + encodeURIComponent(doc),
                  { method: 'POST', credentials: 'same-origin' })
                .then(function (resp) { return resp.json().then(function (j) { return { ok: resp.ok, j: j }; }); })
                .then(function (x) {
                    if (x.ok && x.j && x.j.ok) {
                        showToast('DRR ' + (x.j.drrNumber || '') + ' created & sent'
                            + (x.j.sendWarning ? ' (created; send had a warning)' : ''), 'ok');
                        imsReviewRefresh(true);
                    } else {
                        appAlert((x.j && (x.j.reason || x.j.error)) || 'Failed to create DRR.');
                    }
                })
                .catch(function () { appAlert('Failed to create DRR — service unreachable.'); });
        });
    };

    window.imsGetFile = function (docNumber) {
        window.location = '/api/ims-review/file?docNumber=' + encodeURIComponent(docNumber);
    };

    // ----------------------------------------------------------------------
    // DO/DM response actions
    // ----------------------------------------------------------------------
    window.imsRespondSimple = function (docNumber, drrNumber, type) {
        var label = ({
            'DO_RESPONSE_NO_CHANGE': 'Confirm "No Change Needed" for ' + docNumber + '? An email will go to your manager for approval.',
            'DM_RESPONSE_APPROVED': 'Confirm DM approval for ' + docNumber + '? This closes the review.',
            'DM_RESPONSE_SEND_BACK': 'Send back to DO for ' + docNumber + '? They will need to revise.'
        })[type] || ('Submit ' + type + '?');
        if (!confirm(label)) return;
        var fd = new FormData();
        fd.append('docNumber', docNumber);
        fd.append('drrNumber', drrNumber || '');
        fd.append('type', type);
        fetch('/api/ims-review/respond', { method: 'POST', credentials: 'same-origin', body: fd })
            .then(function (r) {
                if (!r.ok) return r.json().then(function (j) { throw new Error(j.error || ('HTTP ' + r.status)); });
                return r.json();
            })
            .then(function () {
                showToast('Response submitted.', 'ok');
                imsReviewLoad();   // reload role + appropriate view
            })
            .catch(function (e) { showToast('Submit failed: ' + e.message, 'err'); });
    };

    // ----------------------------------------------------------------------
    // Side panel — Needs Change / Need Help
    // ----------------------------------------------------------------------
    window.imsOpenPanel = function (docNumber, drrNumber, mode) {
        _state.currentPanelDoc = { docNumber: docNumber, drrNumber: drrNumber || '', mode: mode };
        var eyebrow = document.getElementById('imsPanelEyebrow');
        var title   = document.getElementById('imsPanelTitle');
        var docEl   = document.getElementById('imsPanelDocNumber');
        var fileLbl = document.getElementById('imsPanelFileLabel');
        var notesLbl = document.getElementById('imsPanelNotesLabel');
        var notes   = document.getElementById('imsPanelNotes');
        var file    = document.getElementById('imsPanelFile');
        var submit  = document.getElementById('imsPanelSubmit');
        var msg     = document.getElementById('imsPanelMessage');

        if (file)  file.value = '';
        if (notes) notes.value = '';
        if (msg)   { msg.style.display = 'none'; msg.textContent = ''; }

        if (mode === 'NEEDS_CHANGE') {
            if (eyebrow) eyebrow.textContent = 'Needs Change';
            if (title)   title.textContent = 'Submit updated version';
            if (fileLbl) fileLbl.textContent = 'Updated file';
            if (notesLbl) notesLbl.textContent = 'Notes for Doc Control (optional)';
            if (notes)   notes.placeholder = 'e.g. Updated the audit checklist to match the new ISO 9001:2025 wording.';
            if (submit)  submit.textContent = 'Submit to Doc Control →';
        } else {
            if (eyebrow) eyebrow.textContent = 'Need Help';
            if (title)   title.textContent = 'Request Doc Control assistance';
            if (fileLbl) fileLbl.textContent = 'Attach a file (optional)';
            if (notesLbl) notesLbl.textContent = 'What do you need help with?';
            if (notes)   notes.placeholder = 'Describe what you need.';
            if (submit)  submit.textContent = 'Send to Doc Control →';
        }
        if (docEl) docEl.textContent = docNumber;

        var aside = document.getElementById('imsNeedsChangePanel');
        var back  = document.getElementById('imsPanelBackdrop');
        if (back)  back.style.display = '';
        if (aside) aside.style.transform = 'translateX(0)';
    };

    window.imsClosePanel = function () {
        var aside = document.getElementById('imsNeedsChangePanel');
        var back  = document.getElementById('imsPanelBackdrop');
        if (aside) aside.style.transform = 'translateX(100%)';
        if (back)  back.style.display = 'none';
        _state.currentPanelDoc = null;
    };

    window.imsSubmitPanel = function () {
        var c = _state.currentPanelDoc;
        if (!c) return;
        var file  = document.getElementById('imsPanelFile');
        var notes = document.getElementById('imsPanelNotes');
        var msg   = document.getElementById('imsPanelMessage');
        var type  = (c.mode === 'NEEDS_CHANGE') ? 'DO_RESPONSE_NEEDS_CHANGE' : 'DO_RESPONSE_NEED_HELP';

        // Needs Change requires a file; Need Help doesn't
        if (c.mode === 'NEEDS_CHANGE' && (!file || !file.files || file.files.length === 0)) {
            if (msg) { msg.textContent = 'Please upload the updated file.'; msg.style.display = ''; }
            return;
        }

        var fd = new FormData();
        fd.append('docNumber', c.docNumber);
        fd.append('drrNumber', c.drrNumber || '');
        fd.append('type', type);
        if (notes && notes.value) fd.append('note', notes.value);
        if (file && file.files && file.files.length > 0) fd.append('file', file.files[0]);

        var submit = document.getElementById('imsPanelSubmit');
        if (submit) { submit.disabled = true; submit.textContent = 'Submitting…'; }

        fetch('/api/ims-review/respond', { method: 'POST', credentials: 'same-origin', body: fd })
            .then(function (r) {
                if (!r.ok) return r.json().then(function (j) { throw new Error(j.error || ('HTTP ' + r.status)); });
                return r.json();
            })
            .then(function () {
                showToast('Submitted to Doc Control.', 'ok');
                imsClosePanel();
                imsReviewLoad();
            })
            .catch(function (e) {
                if (msg) { msg.textContent = 'Submit failed: ' + e.message; msg.style.display = ''; }
            })
            .finally(function () {
                if (submit) { submit.disabled = false; submit.textContent = (c.mode === 'NEEDS_CHANGE') ? 'Submit to Doc Control →' : 'Send to Doc Control →'; }
            });
    };

    // ----------------------------------------------------------------------
    // Utilities
    // ----------------------------------------------------------------------
    function showLoading(on, label) {
        var loadEl = document.getElementById('imsReviewLoading');
        var txtEl = document.getElementById('imsReviewLoadingText');
        if (txtEl) txtEl.textContent = label || 'Loading…';
        if (loadEl) loadEl.style.display = on ? '' : 'none';
    }

    /**
     * Inline toast — small floating card at the top-right that auto-dismisses.
     * Replaces the prior modal-in-disguise that called showCustomAlert. Auto-
     * stacking: if multiple toasts fire in a row they stack vertically.
     *
     *   kind = 'ok'      → green left border, auto-dismiss in 4s
     *   kind = 'err'     → red left border, auto-dismiss in 8s
     *   kind = 'pending' → amber left border + spinner, NO auto-dismiss.
     *                      Returned handle has dismiss() / replaceWith(msg, kind).
     *
     * Returns a handle with { dismiss(), replaceWith(msg, kind) } so the
     * Send flow can show "Sending…" immediately and replace it with the
     * "Sent to <recipient>" toast when the response arrives.
     */
    function showToast(msg, kind) {
        kind = kind || 'ok';
        if (!document.getElementById('imsToastHostStyles')) {
            var st = document.createElement('style');
            st.id = 'imsToastHostStyles';
            st.textContent =
                '.ims-toast-host{position:fixed;top:18px;right:18px;z-index:9998;display:flex;flex-direction:column;gap:8px;max-width:360px;pointer-events:none;}'
              + '.ims-toast{background:#fff;border:1px solid #E8E6DF;border-left:4px solid #4a6fa5;border-radius:6px;box-shadow:0 6px 18px rgba(15,23,32,0.10);'
              + 'padding:10px 14px;font-family:"IBM Plex Sans","Segoe UI",Calibri,Arial,sans-serif;font-size:13px;color:#0F1720;'
              + 'pointer-events:auto;animation:imsToastIn 160ms ease-out;display:flex;align-items:center;gap:10px;}'
              + '.ims-toast.ok{border-left-color:#1F8A4C;}'
              + '.ims-toast.err{border-left-color:#B8342B;}'
              + '.ims-toast.pending{border-left-color:#C7801B;}'
              + '.ims-toast .ims-toast-icon{font-size:14px;line-height:1;flex-shrink:0;}'
              + '.ims-toast .ims-toast-body{flex:1;line-height:1.45;word-break:break-word;}'
              + '.ims-toast-spinner{width:12px;height:12px;border:2px solid #E8E6DF;border-top-color:#C7801B;border-radius:50%;animation:imsToastSpin 0.7s linear infinite;flex-shrink:0;}'
              + '@keyframes imsToastIn{from{opacity:0;transform:translateX(8px);}to{opacity:1;transform:translateX(0);}}'
              + '@keyframes imsToastOut{from{opacity:1;transform:translateX(0);}to{opacity:0;transform:translateX(8px);}}'
              + '@keyframes imsToastSpin{to{transform:rotate(360deg);}}';
            document.head.appendChild(st);
        }
        var host = document.getElementById('imsToastHost');
        if (!host) {
            host = document.createElement('div');
            host.id = 'imsToastHost';
            host.className = 'ims-toast-host';
            document.body.appendChild(host);
        }
        var el = document.createElement('div');
        el.className = 'ims-toast ' + kind;
        function setBody(message, k) {
            var iconHtml = (k === 'pending')
                ? '<span class="ims-toast-spinner"></span>'
                : '<span class="ims-toast-icon">' + (k === 'err' ? '&#10006;' : '&#10003;') + '</span>';
            el.innerHTML = iconHtml + '<span class="ims-toast-body"></span>';
            el.querySelector('.ims-toast-body').textContent = message;
        }
        setBody(msg, kind);
        host.appendChild(el);
        var dismissTimer = null;
        function scheduleAutoDismiss(k) {
            if (dismissTimer) { clearTimeout(dismissTimer); dismissTimer = null; }
            if (k === 'pending') return;          // keep until caller dismisses
            var ttl = (k === 'err') ? 8000 : 4000;
            dismissTimer = setTimeout(handle.dismiss, ttl);
        }
        var handle = {
            dismiss: function () {
                if (dismissTimer) { clearTimeout(dismissTimer); dismissTimer = null; }
                if (!el.parentNode) return;
                el.style.animation = 'imsToastOut 160ms ease-in forwards';
                setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 180);
            },
            replaceWith: function (newMsg, newKind) {
                newKind = newKind || 'ok';
                el.className = 'ims-toast ' + newKind;
                setBody(newMsg, newKind);
                scheduleAutoDismiss(newKind);
            }
        };
        scheduleAutoDismiss(kind);
        return handle;
    }
})();
