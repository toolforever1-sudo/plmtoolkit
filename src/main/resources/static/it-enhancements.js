/* IT Enhancements dashboard — spreadsheet-style editable grid covering 7
 * IT-owned fields (Assigned IT Owner, Effort/Hrs, Project, IT Actions Taken,
 * Target UAT, Target Go-Live, IT Log). Per-cell dirty state, keyboard
 * navigation, fill-down (Cmd/Ctrl+D + corner handle drag), and per-user Agile
 * write-back attribution via the existing /save-batch endpoint.
 *
 * Reads from agprod via /api/it-enhancements/data; saves through
 * /api/it-enhancements/save-batch (the server forwards each edit to
 * plm-agile-service which opens a transient SDK session under the caller's
 * own Agile credentials so the History tab attributes the modification).
 *
 * Tab visibility is gated server-side (AD group PDL-PLM-admin OR per-user
 * grant). The tab button is present in the DOM for everyone; the server's
 * allowedTabs hides it for non-members via applyServerTabPermissions.
 *
 * Cell-name conventions verified 2026-06-12 against ECN-128313-PROJ:
 *  - Target UAT Date         (DATE)
 *  - Target Go Live Date     (DATE)          — NO hyphen
 *  - Effort  (person-hours)  (TEXT)          — literal DOUBLE SPACE
 *  - Assigned IT Owner       (LIST single)   — not a Users user-ref slot
 *  - Project                 (LIST multi)    — wire format: pipe-separated
 *  - IT Actions Taken        (LIST multi)    — wire format: pipe-separated
 *  - IT Log                  (TEXT / longform) — popover editor
 */
(function () {
    'use strict';

    // Module state — module-scoped, not on window. Externally-called entrypoints
    // are exported at the bottom (iteInit / iteRefresh / iteForceRefresh /
    // iteSaveAll / iteSetDensity / iteAgileSigninSubmit / iteAgileSigninCancel).
    var STATE = {
        rows: [],          // server-supplied
        filtered: [],      // after band + search + filter
        // dirty: ckey → { ecn, cell, value, status, msg }
        //   ckey = ecn + '||' + cellName (one entry per edited cell)
        //   status: 'pending' | 'saving' | 'saved' | 'error'
        //   msg: validation reason (only meaningful on 'error')
        dirty: {},
        bandSelected: 'all',
        blankFilters: {},
        bands: { overdueUat: 0, overdueGoLive: 0, approachingGoLive: 0 },
        blanks: { targetUAT: 0, targetGoLive: 0, submitDate: 0, releaseDate: 0 },
        // Selection: active cell + range end (same column).
        sel: { ri: 0, ci: -1, r2: 0 },
        editing: false,
        fillDrag: null,
        density: (typeof localStorage !== 'undefined' && localStorage.getItem('ite-density')) || 'comfortable',
        // Default-hide rows whose IT Status is a date literal or otherwise
        // not in the active-workflow set. Persisted to localStorage so a user
        // who flipped it stays in their preferred view across reloads.
        includeArchived: (typeof localStorage !== 'undefined' && localStorage.getItem('ite-include-archived') === '1') || false,
        // Agile-defined dropdown options per cellName, populated from the
        // /data response. Read by editorOptions() in buildEditor.
        cellOptions: {},
        cachedAt: 0,
        loadedAt: 0,
        booted: false,
        // Sub-tab gate. 'all' | 'my' | 'meeting'.
        //   - all: every row visible in the editable grid.
        //   - my: filtered to rows where itOwnerLoginId == current user
        //         (case-insensitive); enables bulk action bar.
        //   - meeting: Meeting Mode review surface (multi-requestor select,
        //         focus pills, row-by-row review, note → IT Log pending edit,
        //         end-of-meeting scorecard + consolidated emails). Hides the
        //         editable grid + toolbar; lives in #iteMeetingPanel.
        // currentUser is fetched lazily from /api/auth/session on first init.
        // isItTeam reflects PDL-PLM-admin DL membership — drives the
        // notification To/Cc split (Krati's 2026-06-16 feedback): when the
        // editor is on the IT team, the assigned IT Owner moves from To to
        // Cc so the email primarily addresses the Requestor.
        subTab: 'all',
        currentUser: { loginId: '', displayName: '', isItTeam: false },
        // Row-selection set (used by bulk actions). Keyed by ecnNumber → true.
        // Cleared on subTab switch.
        selected: {},
        // ----- IT Log structured history cache -----
        //   ecnNumber → [{ author, loginId, timestamp, date, body }, ...]
        //   Populated lazily by fetchItLogHistory(ecn). Cleared on
        //   iteRefresh / iteForceRefresh so stale entries don't linger.
        itLogHistory: {},
        // ----- Draft persistence -----
        //   draftLoaded: true once the initial recovery prompt has finished
        //                (either recovered or discarded). Prevents the
        //                autosave path from clobbering the draft before the
        //                user has had a chance to recover it.
        //   draftMeta: { fileName, savedAt, savedAtMs } from the loaded
        //              draft — shown in the recovery toast / prompt.
        draftLoaded: false,
        draftMeta: null,
        // ----- Meeting Mode state -----
        //   selReqs: requestors selected to "be in the room" (set of names);
        //            empty set means all.
        //   focusPill: null | 'overdue' | 'going_live_soon' | 'high_no_owner'.
        //   focusedEcn: ECN currently displayed in the right pane.
        //   pinnedEcns: ECNs kept visible in the rail after they stopped
        //               matching the focus pill (spec §5 pin-rule). Cleared
        //               when the pill toggles off or the user navigates away.
        //   notes: ecnNumber → raw note text (session-only; mirrored as a
        //          pending IT Log edit through writeCellEdit).
        //   wrappedUp: true once the user clicked "Wrap up & consolidate".
        //   consolBy: 'requestor' | 'owner' — toggle on wrap-up.
        //   scorecard: deterministic metrics + computed letter grade.
        //   showRequestorPopover: bool for the multi-select dropdown.
        meeting: {
            selReqs: {},
            focusPill: null,
            focusedEcn: '',
            pinnedEcns: {},
            notes: {},
            wrappedUp: false,
            consolBy: 'requestor',
            scorecard: null,
            showRequestorPopover: false
        }
    };

    // The 7 IT Status values Vikas wants to see by default. Anything else
    // (a date literal, "Draft", "BGA", "NA", "QN#: …", blank, …) is treated
    // as archived and hidden unless "Include archived" is toggled on.
    // Comparison is case-insensitive and whitespace-normalized so prod typos
    // ("Completed " / "completed" / "Live, need to verify in production")
    // all map to the right bucket.
    var ACTIVE_WORKFLOW_STATUSES_NORM = (function () {
        var arr = [
            'Analysis',
            'WIP',
            'UAT',
            'Rework',
            'UAT complete, CAB Prep',
            'Live, need to verify in Production',
            'Completed'
        ];
        var s = {};
        arr.forEach(function (v) { s[normStatus(v)] = 1; });
        return s;
    })();

    function normStatus(s) {
        return String(s || '').replace(/\s+/g, ' ').trim().toLowerCase();
    }

    function isActiveWorkflow(row) {
        return ACTIVE_WORKFLOW_STATUSES_NORM[normStatus(row.status)] === 1;
    }

    function isUrgentUnassigned(row) {
        var p = String(row.priority || '').toLowerCase();
        var isUrgent = p.indexOf('high') >= 0 || /^\s*1[-\s]/.test(p);
        var noOwner = !row.itOwner || !String(row.itOwner).trim();
        return isUrgent && noOwner && isActiveWorkflow(row);
    }

    // Per-column model. Drives both render and edit semantics.
    //   type: 'ecn'|'priority'|'status'|'person'|'text'|'wrap'|'number'|'date'|'longtext'
    //   edit: true => developer-editable (pencil + cursor:cell + hover affordance)
    //   cell: Agile cell name sent on save (editable cols only — used as the
    //         dirty map key AND looked up in the CELL_TO_FIELD reverse map)
    //   opts: name of a COMPUTED_OPTS entry for select/datalist editors
    //   w:    column width in CSS px
    //   frozen: true => sticky-left column (ECN only)
    //
    // Cell names are VERIFIED against agprod (see file header). The plan's
    // initial COLUMNS shape used three names that turned out to be wrong;
    // the spec was corrected after a Phase 0 introspection probe.
    // PT-IT-ENH (Vikas, 2026-06-12): the Submitted / Released / Problem
    // Statement columns were dropped from the grid — not actionable, just
    // visual noise. The fields still come back on the row (Java side wasn't
    // touched) so the blank-filter pills still work in case we ever bring
    // them back. The 'opts' values map to data.cellOptions keys (cell name)
    // so the dropdowns get their values from Agile's available-values list
    // instead of just what's been seen in the cached rows.
    var COLUMNS = [
        { key: 'ecnNumber',        label: 'ECN',              w: 116, type: 'ecn',      edit: false, frozen: true },
        { key: 'priority',         label: 'Priority',         w: 78,  type: 'priority', edit: false },
        { key: 'status',           label: 'IT Status',        w: 118, type: 'status',   edit: false },
        { key: 'workflowStatus',   label: 'Workflow',         w: 92,  type: 'text',     edit: false },
        { key: 'itOwner',          label: 'IT Owner',         w: 170, type: 'person',   edit: true,  cell: 'Page Three.Assigned IT Owner' },
        { key: 'requestor',        label: 'Requestor',        w: 140, type: 'person',   edit: false },
        { key: 'category',         label: 'Category',         w: 150, type: 'text',     edit: false },
        { key: 'proposal',         label: 'Proposal',         w: 250, type: 'wrap',     edit: false },
        { key: 'hours',            label: 'Hrs',              w: 64,  type: 'number',   edit: true,  cell: 'Page Three.Effort  (person-hours)' },
        { key: 'project',          label: 'Project',          w: 180, type: 'multilist', edit: true,  cell: 'Page Three.Project' },
        { key: 'itActions',        label: 'IT Actions Taken', w: 200, type: 'multilist', edit: true,  cell: 'Page Three.IT Actions Taken' },
        { key: 'targetUAT',        label: 'Target UAT',       w: 118, type: 'date',     edit: true,  cell: 'Page Three.Target UAT Date' },
        { key: 'targetGoLive',     label: 'Target Go-Live',   w: 122, type: 'date',     edit: true,  cell: 'Page Three.Target Go Live Date' },
        { key: 'reworkReason',     label: 'Rework Reason',    w: 140, type: 'text',     edit: false },
        { key: 'itLog',            label: 'IT Log',           w: 240, type: 'longtext', edit: true,  cell: 'Page Three.IT Log' }
    ];

    // Reverse map: cellName → row field key. Used to patch the local row
    // after a successful save (so the grid reflects the new value without
    // another /data round-trip).
    var CELL_TO_FIELD = (function () {
        var m = {};
        COLUMNS.forEach(function (c) { if (c.cell) m[c.cell] = c.key; });
        return m;
    })();

    // Multi-list columns send their value on the wire as a pipe-separated string
    // (so display values can contain commas — e.g. "Code Change (please fill in
    // Jar Name/Class Name)"). The agile-service dispatcher splits on "|".
    var MULTI_LIST_KEYS = { project: true, itActions: true };
    var MULTI_LIST_CELLS = { 'Page Three.Project': true, 'Page Three.IT Actions Taken': true };

    /** Translate a user-typed value (display form) into the on-the-wire form the
     *  agile-service backend expects. Today the only translation is:
     *  multi-list cells take a comma-separated user input and become a
     *  pipe-separated wire string. Single-value cells pass through unchanged. */
    function wireValue(cellName, displayVal) {
        if (!MULTI_LIST_CELLS[cellName]) return displayVal;
        if (displayVal == null || displayVal === '') return '';
        // User input is comma-separated display values. Trim each piece and
        // drop empties, then join with "|".
        return String(displayVal)
            .split(/\s*,\s*/)
            .map(function (s) { return s.trim(); })
            .filter(function (s) { return s.length > 0; })
            .join('|');
    }

    // Computed editor options. Recomputed from STATE.rows after each /data load.
    var COMPUTED_OPTS = { OWNERS: [], PROJECT_SUGGESTIONS: [], ACTION_SUGGESTIONS: [] };

    function recomputeOpts() {
        COMPUTED_OPTS.OWNERS = uniqueSorted(STATE.rows.map(function (r) { return r.itOwner; }));
        var projSet = {}, actSet = {};
        STATE.rows.forEach(function (r) {
            // Multi-list display values arrive comma-joined from the read path
            // (ItEnhancementsService joins listentries with ", "). Split with
            // some tolerance — commas inside parens are common ("(please fill
            // in Jar Name/Class Name)"), so we split on ", " specifically not
            // bare commas.
            (r.project || '').split(/,\s+/).forEach(function (p) { if (p) projSet[p.trim()] = 1; });
            (r.itActions || '').split(/,\s+/).forEach(function (a) { if (a) actSet[a.trim()] = 1; });
        });
        COMPUTED_OPTS.PROJECT_SUGGESTIONS = Object.keys(projSet).sort();
        COMPUTED_OPTS.ACTION_SUGGESTIONS = Object.keys(actSet).sort();
    }

    function ckey(ecn, cellName) { return ecn + '||' + cellName; }
    function firstEditableColIndex() { for (var i = 0; i < COLUMNS.length; i++) if (COLUMNS[i].edit) return i; return 0; }
    function within(x, a, b) { return x >= Math.min(a, b) && x <= Math.max(a, b); }

    // ============================================================
    // Init + load
    // ============================================================

    function iteInit() {
        if (!STATE.booted) {
            wireUi();
            STATE.booted = true;
        }
        // Lazy session fetch — non-blocking. If the My-tab is the user's
        // current sub-tab on init, the band counts will reflect 0 until the
        // session resolves; then we re-render. Most users land on All anyway.
        if (!STATE.currentUser.loginId) {
            fetch('/api/auth/session', { credentials: 'same-origin' })
                .then(function (r) { return r.json(); })
                .then(function (s) {
                    STATE.currentUser = {
                        loginId: (s && s.username) || '',
                        displayName: (s && s.displayName) || '',
                        isItTeam: !!(s && s.isPlmAdmin)
                    };
                    // Re-render sub-nav so the My-count + Owned-by label show up
                    // once we know who we are.
                    renderSubNav();
                    if (STATE.subTab === 'my') { applyFilter(); render(); }
                })
                .catch(function () {/* leave currentUser blank — toast on bulk action */});
        }
        iteRefresh();
    }

    /** Lower-case loginid for case-insensitive matching. The session returns
     *  whatever case AD has on file; the row's itOwnerLoginId comes from
     *  AGILE.AGILEUSER.LOGINID which is typically 4-digit numeric (e.g.
     *  "8252") so case rarely matters in practice — but defensive lower-casing
     *  is cheap and protects against mixed-case shop accounts. */
    function lc(s) { return String(s || '').trim().toLowerCase(); }

    /** True if this row belongs to the currently-signed-in user. */
    function isMine(row) {
        var me = lc(STATE.currentUser.loginId);
        if (!me) return false;
        return lc(row.itOwnerLoginId) === me;
    }

    function wireUi() {
        document.querySelectorAll('#iteBands .ite-band').forEach(function (btn) {
            btn.addEventListener('click', function () {
                STATE.bandSelected = btn.getAttribute('data-band');
                highlightBand();
                applyFilter();
                render();
            });
        });
        document.querySelectorAll('.ite-blank-pill').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var key = btn.getAttribute('data-blank');
                if (STATE.blankFilters[key]) delete STATE.blankFilters[key];
                else STATE.blankFilters[key] = true;
                highlightBlankPills();
                applyFilter();
                render();
            });
        });
        var searchEl = document.getElementById('iteSearch');
        if (searchEl) searchEl.addEventListener('input', debounce(function () {
            applyFilter(); render();
        }, 180));
        ['iteFilterOwner', 'iteFilterStatus'].forEach(function (id) {
            var el = document.getElementById(id);
            if (el) el.addEventListener('change', function () { applyFilter(); render(); });
        });
        var inclArc = document.getElementById('iteIncludeArchived');
        if (inclArc) {
            inclArc.checked = STATE.includeArchived;
            inclArc.addEventListener('change', function () {
                STATE.includeArchived = !!inclArc.checked;
                try { localStorage.setItem('ite-include-archived', STATE.includeArchived ? '1' : '0'); } catch (_) {}
                // Recompute counts (the "All" tile label changes) and re-filter.
                renderBands(STATE.bands || {});
                applyFilter();
                render();
            });
        }

        // Density toggle (persisted to localStorage).
        wireDensityButtons();

        // Grid-level keyboard handler — receives keys when the <table>
        // (tabIndex=0) has focus.
        var grid = document.getElementById('iteGrid');
        if (grid) {
            grid.addEventListener('keydown', onGridKey);
        }
    }

    function wireDensityButtons() {
        var c = document.getElementById('iteDensComfortable');
        var k = document.getElementById('iteDensCompact');
        if (c) c.addEventListener('click', function () { iteSetDensity('comfortable'); });
        if (k) k.addEventListener('click', function () { iteSetDensity('compact'); });
        applyDensityClass();
    }

    function iteSetDensity(d) {
        STATE.density = d;
        try { localStorage.setItem('ite-density', d); } catch (e) {/* ignore */}
        applyDensityClass();
    }

    function applyDensityClass() {
        var grid = document.getElementById('iteGrid');
        if (grid) {
            if (STATE.density === 'compact') grid.classList.add('is-compact');
            else grid.classList.remove('is-compact');
        }
        var c = document.getElementById('iteDensComfortable');
        var k = document.getElementById('iteDensCompact');
        if (c) c.classList.toggle('is-on', STATE.density === 'comfortable');
        if (k) k.classList.toggle('is-on', STATE.density === 'compact');
    }

    function iteRefresh() {
        showLoading(true);
        showToast(null);
        fetch('/api/it-enhancements/data', { credentials: 'same-origin' })
            .then(handleDataResponse)
            .then(function (resp) { absorbResponse(resp); showLoading(false); })
            .catch(function (e) {
                showLoading(false);
                showToast({ kind: 'error', text: e.message });
            });
    }

    /** Force-refresh — re-pulls from agprod (the regular Refresh just reads the
     *  cache snapshot, which the server tops up hourly). Shown as a pending
     *  toast since the SQL can take ~1s and we want clear feedback. */
    function iteForceRefresh() {
        var btn = document.getElementById('iteForceRefreshBtn');
        if (btn) btn.disabled = true;
        showToast({ kind: 'pending', text: 'Refreshing from agprod…' });
        fetch('/api/it-enhancements/refresh', {
            method: 'POST',
            credentials: 'same-origin'
        })
            .then(handleDataResponse)
            .then(function (resp) {
                absorbResponse(resp);
                showToast({ kind: 'success', text: 'Refreshed — ' + resp.rowCount + ' rows.' });
            })
            .catch(function (e) { showToast({ kind: 'error', text: e.message }); })
            .finally(function () { if (btn) btn.disabled = false; });
    }

    function handleDataResponse(r) {
        if (r.status === 403) throw new Error('Access denied — IT Enhancements tab not granted to your account.');
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
    }

    function absorbResponse(resp) {
        STATE.rows = resp.rows || [];
        STATE.loadedAt = resp.loadedAt || Date.now();
        STATE.cachedAt = resp.cachedAt || 0;
        STATE.bands = resp.bands || {};
        STATE.blanks = resp.blanks || {};
        // Agile-defined available-values for the editable LIST cells. May be
        // {} on first /data when agile-service hasn't been called yet; the
        // editor falls back to computed-from-rows lists in that case.
        STATE.cellOptions = resp.cellOptions || {};
        recomputeOpts();
        if (STATE.sel.ci < 0) STATE.sel.ci = firstEditableColIndex();
        renderSubNav();
        renderBands(STATE.bands);
        renderBlankPillCounts(STATE.blanks);
        renderFreshness();
        buildFilterDropdowns(STATE.rows);
        applyFilter();
        render();
        // Feed fresh rows to the Meeting Mode v2 module (My / Meeting /
        // Records views). It repaints itself if one of its views is active.
        if (window.MeetingMode) window.MeetingMode.setData(STATE.rows, STATE.currentUser);
        // Probe for a recoverable draft once per page load. Runs only on the
        // first /data hit so refreshes during the same session don't re-prompt.
        if (!STATE.draftLoaded && STATE.draftMeta === null) {
            // Set a sentinel so a second absorbResponse during init doesn't
            // double-fire the probe. loadDraftAndPrompt flips draftLoaded
            // when it resolves (either way).
            STATE.draftMeta = 'probing';
            loadDraftAndPrompt();
        }
        // After every fresh /data, clear the per-ECN IT log history cache —
        // a force-refresh probably picked up new entries.
        STATE.itLogHistory = {};
    }

    // ============================================================
    // Rendering
    // ============================================================

    function renderBands(bands) {
        // The "All" tile is now "Active workflow" — only count rows that
        // pass the active-workflow predicate (or every row when the user
        // has flipped the Include-archived toggle on).
        //
        // In My view (subTab='my'), every count is recomputed against the
        // current-user-owned slice; the server-supplied `bands` is ignored
        // because it counts every row globally.
        var myView = STATE.subTab === 'my';
        var scopeRows = myView ? STATE.rows.filter(isMine) : STATE.rows;
        var activeCount = 0, archivedCount = 0, urgentUnassignedCount = 0;
        var overdueUat = 0, overdueGoLive = 0, approaching = 0;
        for (var i = 0; i < scopeRows.length; i++) {
            var r = scopeRows[i];
            if (isActiveWorkflow(r)) {
                activeCount++;
                if (isUrgentUnassigned(r)) urgentUnassignedCount++;
            } else {
                archivedCount++;
            }
            if (myView) {
                if (r._kind === 'overdue_uat') overdueUat++;
                else if (r._kind === 'overdue_golive') overdueGoLive++;
                else if (r._kind === 'approaching_golive') approaching++;
            }
        }
        var allTile = document.getElementById('iteBandAllN');
        if (allTile) allTile.textContent = STATE.includeArchived
            ? (scopeRows.length + ' / ' + activeCount + ' active')
            : activeCount;
        var uatTile = document.getElementById('iteBandUatN');
        if (uatTile) uatTile.textContent = myView ? overdueUat : (bands.overdueUat || 0);
        var goTile = document.getElementById('iteBandGoN');
        if (goTile) goTile.textContent = myView ? overdueGoLive : (bands.overdueGoLive || 0);
        var apTile = document.getElementById('iteBandApproachN');
        if (apTile) apTile.textContent = myView ? approaching : (bands.approachingGoLive || 0);
        var urgTile = document.getElementById('iteBandUrgUnassN');
        // "Urgent & Unassigned" inside the My view is naturally 0 (you ARE
        // the owner), but render the count anyway so the tile doesn't blink
        // empty on tab-switch.
        if (urgTile) urgTile.textContent = urgentUnassignedCount;
        var arcMeta = document.getElementById('iteArchivedCount');
        if (arcMeta) arcMeta.textContent = archivedCount > 0 ? '(' + archivedCount + ' hidden)' : '';
        var cb = document.getElementById('iteIncludeArchived');
        if (cb && cb.checked !== STATE.includeArchived) cb.checked = STATE.includeArchived;
        highlightBand();
    }

    /** Sub-nav: All / My / Meeting Mode. Renders the counts in the pill
     *  buttons, the "Owned by …" muted label, and the Meeting Mode notes
     *  badge. Called from absorbResponse, iteSetSubTab, the session-fetch
     *  resolve handler, and meeting-mode note edits. */
    function renderSubNav() {
        var allBtn = document.querySelector('#iteSubNav .ite-subtab[data-subtab="all"]');
        var myBtn  = document.querySelector('#iteSubNav .ite-subtab[data-subtab="my"]');
        var meetBtn = document.querySelector('#iteSubNav .ite-subtab[data-subtab="meeting"]');
        var recordsBtn = document.querySelector('#iteSubNav .ite-subtab[data-subtab="records"]');
        var allN = document.getElementById('iteSubAllN');
        var myN  = document.getElementById('iteSubMyN');
        var meetN = document.getElementById('iteSubMeetN');
        var ownerLbl = document.getElementById('iteSubMyOwner');
        var allCount = STATE.includeArchived
            ? STATE.rows.length
            : STATE.rows.filter(isActiveWorkflow).length;
        var myRows = STATE.rows.filter(isMine);
        var myCount = STATE.includeArchived ? myRows.length : myRows.filter(isActiveWorkflow).length;
        if (allN) allN.textContent = allCount;
        if (myN) myN.textContent = myCount;
        // Style the active sub-tab pill (dark fill) and the inactive one (white).
        function paint(btn, on) {
            if (!btn) return;
            btn.classList.toggle('is-on', on);
            btn.style.background = on ? '#0F1720' : '#fff';
            btn.style.color = on ? '#fff' : '#0F1720';
        }
        paint(allBtn, STATE.subTab === 'all');
        paint(myBtn,  STATE.subTab === 'my');
        paint(meetBtn, STATE.subTab === 'meeting');
        paint(recordsBtn, STATE.subTab === 'records');
        // Notes-captured badge on the Meeting Mode pill.
        var nNotes = Object.keys(STATE.meeting.notes || {}).filter(function (k) {
            return (STATE.meeting.notes[k] || '').trim().length > 0;
        }).length;
        if (meetN) {
            if (nNotes > 0) {
                meetN.style.display = '';
                meetN.textContent = nNotes;
            } else {
                meetN.style.display = 'none';
            }
        }
        if (ownerLbl) {
            if (STATE.subTab === 'my' && STATE.currentUser.displayName) {
                ownerLbl.style.display = '';
                ownerLbl.textContent = 'Owned by ' + STATE.currentUser.displayName
                    + (STATE.currentUser.loginId ? ' (' + STATE.currentUser.loginId + ')' : '');
            } else if (STATE.subTab === 'my' && STATE.currentUser.loginId) {
                ownerLbl.style.display = '';
                ownerLbl.textContent = 'Owned by ' + STATE.currentUser.loginId;
            } else {
                ownerLbl.style.display = 'none';
            }
        }
    }

    /** Switch sub-tabs. Clears selection, recomputes band scope, hides the
     *  owner-filter dropdown on My view, toggles bulk bar, and shows /
     *  hides the Meeting Mode panel vs. the All / My grid chrome. */
    function iteSetSubTab(tab) {
        if (tab !== 'all' && tab !== 'my' && tab !== 'meeting' && tab !== 'records') tab = 'all';
        if (STATE.subTab === tab) return;
        STATE.subTab = tab;
        STATE.selected = {};
        STATE.bandSelected = 'all';

        // My / Meeting Mode / Meeting records are owned by the Meeting Mode v2
        // module (meeting-mode.js → #iteMMRoot). Only "All Enhancements" uses
        // the legacy editable-grid chrome. The old #iteMeetingPanel is retired.
        var moduleView = (tab === 'my' || tab === 'meeting' || tab === 'records');

        var ownerSel = document.getElementById('iteFilterOwner');
        if (ownerSel) ownerSel.style.display = '';
        var bulk = document.getElementById('iteBulkBar');
        if (bulk) bulk.style.display = 'none';

        var chrome = document.getElementById('iteAllMyChrome');
        var meetPanel = document.getElementById('iteMeetingPanel');
        var mmRoot = document.getElementById('iteMMRoot');
        if (chrome) chrome.style.display = moduleView ? 'none' : '';
        if (meetPanel) meetPanel.style.display = 'none';
        if (mmRoot) mmRoot.style.display = moduleView ? 'block' : 'none';

        renderSubNav();
        if (moduleView) {
            if (window.MeetingMode) {
                window.MeetingMode.setData(STATE.rows, STATE.currentUser);
                window.MeetingMode.show(tab);
            }
        } else {
            if (window.MeetingMode) window.MeetingMode.hide();
            renderBands(STATE.bands || {});
            renderBlankPillCounts(STATE.blanks || {});
            applyFilter();
            render();
        }
    }

    function highlightBand() {
        document.querySelectorAll('#iteBands .ite-band').forEach(function (btn) {
            var on = btn.getAttribute('data-band') === STATE.bandSelected;
            btn.style.boxShadow = on ? 'inset 0 0 0 1px #4a6fa5' : 'none';
            btn.style.background = on ? '#eef3f9' : '#FAFAF7';
        });
    }

    function highlightBlankPills() {
        document.querySelectorAll('.ite-blank-pill').forEach(function (btn) {
            var key = btn.getAttribute('data-blank');
            var on = !!STATE.blankFilters[key];
            btn.style.background = on ? '#fff8e1' : '#fff';
            btn.style.borderColor = on ? '#C7801B' : '#E8E6DF';
            btn.style.color = on ? '#856404' : '#0F1720';
        });
    }

    function renderBlankPillCounts(blanks) {
        // In My view, recompute blank counts against the my-rows slice — the
        // server-supplied `blanks` is global. Spec §7a: "missing-date pills
        // all re-scope to my rows."
        var myView = STATE.subTab === 'my';
        var localBlanks = blanks || {};
        if (myView) {
            localBlanks = { targetUAT: 0, targetGoLive: 0, submitDate: 0, releaseDate: 0 };
            var mine = STATE.rows.filter(isMine);
            mine.forEach(function (r) {
                if (!r.targetUAT) localBlanks.targetUAT++;
                if (!r.targetGoLive) localBlanks.targetGoLive++;
                if (!r.submitDate) localBlanks.submitDate++;
                if (!r.releaseDate) localBlanks.releaseDate++;
            });
        }
        document.querySelectorAll('.ite-blank-pill').forEach(function (btn) {
            var key = btn.getAttribute('data-blank');
            var n = (localBlanks && localBlanks[key]) || 0;
            var span = btn.querySelector('.ite-blank-n');
            if (span) {
                span.textContent = '(' + n + ')';
                btn.style.opacity = (n === 0 && !STATE.blankFilters[key]) ? '0.45' : '1';
            }
        });
        highlightBlankPills();
    }

    function renderFreshness() {
        var el = document.getElementById('iteFreshness');
        if (!el) return;
        if (!STATE.cachedAt) { el.textContent = 'Live · cache cold'; return; }
        var ageSec = Math.max(0, Math.floor((Date.now() - STATE.cachedAt) / 1000));
        var ago;
        if (ageSec < 60) ago = ageSec + 's ago';
        else if (ageSec < 3600) ago = Math.floor(ageSec / 60) + 'm ago';
        else ago = Math.floor(ageSec / 3600) + 'h ' + Math.floor((ageSec % 3600) / 60) + 'm ago';
        var stamp = new Date(STATE.cachedAt).toLocaleString();
        el.textContent = 'Snapshot · ' + ago;
        el.title = 'Cached at ' + stamp + '. Hourly background rebuild keeps this fresh. Click "Refresh now" to force re-pull.';
    }

    function buildFilterDropdowns(rows) {
        var ownerSel = document.getElementById('iteFilterOwner');
        var statusSel = document.getElementById('iteFilterStatus');
        if (!ownerSel || !statusSel) return;
        var keepOwner = ownerSel.value;
        var keepStatus = statusSel.value;
        var owners = uniqueSorted(rows.map(function (r) { return r.itOwner; }));
        var statuses = uniqueSorted(rows.map(function (r) { return r.status; }));
        ownerSel.innerHTML = '<option value="">All IT owners</option>' +
            owners.map(function (o) { return '<option value="' + escapeAttr(o) + '">' + escapeHtml(o) + '</option>'; }).join('');
        statusSel.innerHTML = '<option value="">All IT statuses</option>' +
            statuses.map(function (s) { return '<option value="' + escapeAttr(s) + '">' + escapeHtml(s) + '</option>'; }).join('');
        ownerSel.value = keepOwner || '';
        statusSel.value = keepStatus || '';
    }

    // Clear every filter the user might have set (free-text search, owner
    // dropdown, status dropdown, "show only rows missing X" blank filters,
    // and the 4-band kpi selector). Optionally restore selection on the row
    // matching `restore.ecn` + `restore.cellName` after the next render.
    // Used by the post-save "Show it" toast action.
    function clearAllFilters(restore) {
        var s = document.getElementById('iteSearch'); if (s) s.value = '';
        var o = document.getElementById('iteFilterOwner'); if (o) o.value = '';
        var st = document.getElementById('iteFilterStatus'); if (st) st.value = '';
        STATE.blankFilters = {};
        STATE.bandSelected = 'all';
        applyFilter();
        if (restore && restore.ecn) {
            var ri = (STATE.filtered || []).findIndex(function (r) { return r.ecnNumber === restore.ecn; });
            var ci = -1;
            if (restore.cellName) {
                for (var i = 0; i < COLUMNS.length; i++) {
                    if (COLUMNS[i].cell === restore.cellName) { ci = i; break; }
                }
            }
            if (ri >= 0) {
                STATE.sel.ri = ri;
                STATE.sel.r2 = ri;
                if (ci >= 0) STATE.sel.ci = ci;
            }
        }
        // Refresh the UI affordances that reflect filter state (blank-filter
        // pill toggles, band kpi tile highlight, search box clear).
        refreshBlankFilterPills();
        refreshBandSelection();
        render();
        ensureActiveVisible();
    }

    // Best-effort refreshers for affordances mutated above. The helpers are
    // defined elsewhere in this file; guard with typeof so this works even
    // if a future refactor removes them.
    function refreshBlankFilterPills() {
        try { if (typeof rebuildBlankFilterPills === 'function') rebuildBlankFilterPills(); } catch (_) {}
    }
    function refreshBandSelection() {
        try { if (typeof rebuildBandTiles === 'function') rebuildBandTiles(); } catch (_) {}
    }

    function applyFilter() {
        var q = (document.getElementById('iteSearch').value || '').trim().toLowerCase();
        var ownerSel = document.getElementById('iteFilterOwner');
        var statusSel = document.getElementById('iteFilterStatus');
        var ownerFilter = ownerSel ? ownerSel.value : '';
        var statusFilter = statusSel ? statusSel.value : '';
        var blankKeys = Object.keys(STATE.blankFilters);
        var myView = STATE.subTab === 'my';
        STATE.filtered = STATE.rows.filter(function (r) {
            // My-Enhancements gate: only my rows. The owner-filter dropdown
            // is hidden in the My view (redundant), but we still enforce
            // here in case the dropdown was set before switching tabs.
            if (myView && !isMine(r)) return false;
            // Active-workflow gate. The 'urgent_unassigned' band always
            // implies active-workflow membership; the per-band classifications
            // ('overdue_uat', 'overdue_golive', 'approaching_golive') already
            // imply WIP / UAT / UAT complete status, so they implicitly fall
            // inside active workflow. We gate explicitly anyway for safety in
            // case the Java classifier ever loosens.
            if (!STATE.includeArchived && !isActiveWorkflow(r)) return false;
            if (STATE.bandSelected === 'urgent_unassigned') {
                if (!isUrgentUnassigned(r)) return false;
            } else if (STATE.bandSelected !== 'all' && r._kind !== STATE.bandSelected) {
                return false;
            }
            if (ownerFilter && r.itOwner !== ownerFilter) return false;
            if (statusFilter && r.status !== statusFilter) return false;
            for (var i = 0; i < blankKeys.length; i++) {
                var k = blankKeys[i];
                var v = r[k];
                if (v != null && v !== '') return false;
            }
            if (q) {
                var hay = [r.ecnNumber, r.proposal, r.problemStatement, r.project,
                           r.requestor, r.itOwner, r.itActions, r.itLog]
                    .filter(Boolean).join(' ').toLowerCase();
                if (hay.indexOf(q) < 0) return false;
            }
            return true;
        });
        // Clamp selection.
        if (STATE.sel.ri >= STATE.filtered.length) {
            STATE.sel.ri = Math.max(0, STATE.filtered.length - 1);
            STATE.sel.r2 = STATE.sel.ri;
        }
        var rc = document.getElementById('iteRowCount');
        if (rc) rc.textContent = STATE.filtered.length + ' / ' + STATE.rows.length + ' rows';
    }

    function render() {
        var head = document.getElementById('iteGridHead');
        var body = document.getElementById('iteGridBody');
        var empty = document.getElementById('iteEmpty');
        if (!head || !body) return;

        applyDensityClass();

        var myView = STATE.subTab === 'my';

        // Headers (rendered once per cycle; cheap). In My view we prepend a
        // sticky checkbox column for row selection (select-all in the header).
        var headHtml = '';
        if (myView) {
            var allChecked = STATE.filtered.length > 0 &&
                STATE.filtered.every(function (r) { return STATE.selected[r.ecnNumber]; });
            headHtml += '<th class="ite2-th is-select is-frozen" style="width:34px; min-width:34px; left:0; z-index:5;">' +
                '<input type="checkbox" id="iteSelectAll" ' + (allChecked ? 'checked' : '') +
                ' style="margin:0; cursor:pointer;" title="Select all visible rows"></th>';
        }
        headHtml += COLUMNS.map(function (c) {
            var cls = 'ite2-th' + (c.edit ? ' is-edit' : '') + (c.frozen ? ' is-frozen' : '');
            var style = 'width:' + c.w + 'px; min-width:' + c.w + 'px;';
            // In My view the frozen ECN column sits to the right of the checkbox,
            // so push its sticky `left` past 34px.
            if (myView && c.frozen) style += ' left:34px;';
            var pencil = c.edit ? '<span class="ite2-th-pencil" title="Editable">✎</span>' : '';
            return '<th class="' + cls + '" style="' + style + '"><span>' + escapeHtml(c.label) + '</span>' + pencil + '</th>';
        }).join('');
        head.innerHTML = headHtml;

        if (!STATE.filtered.length) {
            body.innerHTML = '';
            empty.style.display = '';
            return;
        }
        empty.style.display = 'none';

        var sel = STATE.sel;
        var fillEnd = (STATE.fillDrag != null ? STATE.fillDrag : sel.r2);

        var html = STATE.filtered.map(function (r, ri) {
            var kind = r._kind || 'ok';
            var rowCells = '';
            if (myView) {
                var checked = STATE.selected[r.ecnNumber] ? 'checked' : '';
                rowCells += '<td class="ite2-td is-select is-frozen" data-ri="' + ri + '" data-ci="-1" data-noselect="1" style="width:34px; min-width:34px; left:0; z-index:1; padding:6px 8px;">' +
                    '<input type="checkbox" class="ite2-rowsel" data-ecn="' + escapeAttr(r.ecnNumber) + '" ' + checked +
                    ' style="margin:0; cursor:pointer;"></td>';
            }
            rowCells += COLUMNS.map(function (c, ci) {
                return renderCell(r, c, ri, ci, sel, fillEnd, myView);
            }).join('');
            return '<tr class="ite2-tr kind-' + escapeAttr(kind) + '" data-ecn="' + escapeAttr(r.ecnNumber) + '">' +
                rowCells + '</tr>';
        }).join('');
        body.innerHTML = html;

        // Delegated mouse handling on the body — picks the cell under cursor
        // for selection / edit start. We attach as a property (not addEventListener
        // every render) so we don't stack listeners.
        body.onmousedown = onBodyMouseDown;
        body.ondblclick = onBodyDblClick;

        // Wire the checkbox column (My view only).
        if (myView) {
            var selectAll = document.getElementById('iteSelectAll');
            if (selectAll) selectAll.onchange = function () {
                if (selectAll.checked) {
                    STATE.filtered.forEach(function (r) { STATE.selected[r.ecnNumber] = true; });
                } else {
                    STATE.filtered.forEach(function (r) { delete STATE.selected[r.ecnNumber]; });
                }
                renderBulkBar();
                render();
            };
            var boxes = body.querySelectorAll('input.ite2-rowsel');
            Array.prototype.forEach.call(boxes, function (cb) {
                cb.onchange = function () {
                    var ecn = cb.getAttribute('data-ecn');
                    if (cb.checked) STATE.selected[ecn] = true;
                    else delete STATE.selected[ecn];
                    renderBulkBar();
                    // Selecting one row may toggle the select-all checkbox state;
                    // re-render just the head to update it.
                    var hdr = document.getElementById('iteSelectAll');
                    if (hdr) {
                        var allChecked = STATE.filtered.length > 0 &&
                            STATE.filtered.every(function (r) { return STATE.selected[r.ecnNumber]; });
                        hdr.checked = allChecked;
                    }
                };
            });
        }
        renderBulkBar();

        refreshDirtyBadge();
        ensureActiveVisible();
    }

    /** Reflect the current selection count + enable/disable selection-scoped
     *  buttons. Called whenever STATE.selected changes. */
    function renderBulkBar() {
        var bar = document.getElementById('iteBulkBar');
        if (!bar) return;
        var n = Object.keys(STATE.selected).filter(function (k) { return STATE.selected[k]; }).length;
        var label = document.getElementById('iteBulkCount');
        if (label) label.textContent = (n === 0
            ? 'Nothing selected · Extend buttons act on all your rows'
            : (n + ' selected'));
        var logBtn = document.getElementById('iteBulkLogBtn');
        if (logBtn) {
            logBtn.disabled = (n === 0);
            logBtn.style.opacity = (n === 0) ? '0.5' : '1';
        }
        var clearBtn = document.getElementById('iteBulkClearBtn');
        if (clearBtn) {
            clearBtn.disabled = (n === 0);
            clearBtn.style.opacity = (n === 0) ? '0.5' : '1';
        }
    }

    function renderCell(r, c, ri, ci, sel, fillEnd, myView) {
        var d = STATE.dirty[ckey(r.ecnNumber, c.cell || '')];
        var effVal = d ? d.value : (r[c.key] || '');
        var active = sel.ri === ri && sel.ci === ci;
        var inRange = (ci === sel.ci) && within(ri, sel.ri, fillEnd) && ri !== sel.ri;
        var cls = 'ite2-td type-' + c.type + (c.edit ? ' is-edit' : ' is-ro') + (c.frozen ? ' is-frozen' : '');
        if (active) cls += ' is-active';
        if (inRange) cls += ' is-inrange';
        if (d) cls += ' st-' + d.status;
        var style = 'width:' + c.w + 'px; max-width:' + c.w + 'px;';
        // In My view the checkbox column sits at left:0, so push the frozen
        // ECN column past it.
        if (myView && c.frozen) style += ' left:34px;';
        var titleAttr = '';
        if (d && d.status === 'error' && d.msg) titleAttr = ' title="' + escapeAttr(d.msg) + '"';
        var inner = renderDisplay(c, effVal);
        var dot = d ? '<span class="ite2-dot"></span>' : '';
        var handle = (active && c.edit) ? '<span class="ite2-handle" title="Drag to fill down"></span>' : '';
        return '<td class="' + cls + '" data-ri="' + ri + '" data-ci="' + ci + '" style="' + style + '"' + titleAttr + '>' +
            inner + dot + handle + '</td>';
    }

    function renderDisplay(c, value) {
        var blank = (value == null || value === '');
        if (c.type === 'ecn') return agileChangeLinkHtml(value);
        if (c.type === 'priority') {
            var p = (value || '').charAt(0);
            return '<span class="cell-prio prio-' + escapeAttr(p) + '">' + (blank ? '—' : escapeHtml(value)) + '</span>';
        }
        if (c.type === 'status') return '<span class="cell-status">' + (blank ? '—' : escapeHtml(value)) + '</span>';
        if (c.type === 'person') return blank ? dashFor(c) :
            '<span class="cell-person' + (c.edit ? '' : ' is-ro') + '">' + escapeHtml(value) + '</span>';
        if (c.type === 'date') return blank ? dashFor(c) :
            '<span class="u-mono cell-date">' + escapeHtml(value) + '</span>';
        if (c.type === 'number') return blank ? dashFor(c) :
            '<span class="u-mono cell-num">' + escapeHtml(value) + '</span>';
        if (c.type === 'wrap') return blank ? dashFor(c) :
            '<div class="cell-wrap">' + escapeHtml(value) + '</div>';
        if (c.type === 'longtext') {
            if (blank) return dashFor(c);
            // IT Log values may carry inline HTML — render as plain text for safety.
            return '<div class="cell-log"><span class="cell-log-txt">' + escapeHtml(value) + '</span></div>';
        }
        return blank ? dashFor(c) : '<div class="cell-text">' + escapeHtml(value) + '</div>';
    }

    function dashFor(c) { return '<span class="cell-dash' + (c.edit ? ' editable' : '') + '">—</span>'; }

    // Same convention as imsreview.js#agileChangeLink — splits the number on
    // the first hyphen, uses the prefix as the Agile object type ("ECN" /
    // "MCO" / etc). Falls back to "Change" if there's no hyphen. The base URL
    // matches the IMS Review fallback so a property change in
    // app.ims-review.agile-webclient-url drifts both surfaces in one bump
    // when we eventually wire IT-Enh to the same backend meta.
    function agileBaseUrl() {
        // Read window.AGILE_WEBCLIENT_URL LIVE — app.js sets it from the session
        // fetch (plmqa.sandisk.com on QA) shortly after load, so a value
        // snapshotted at this module's load time would be the stale prod
        // default. Don't cache it.
        return (STATE.meta && STATE.meta.agileWebclientUrl)
            || window.AGILE_WEBCLIENT_URL
            || 'https://plm.sandisk.com/Agile';
    }
    function agileChangeLinkHtml(number) {
        if (!number) return '<span class="cell-ecn u-mono">' + escapeHtml(number || '') + '</span>';
        var n = String(number).trim();
        if (!n) return '<span class="cell-ecn u-mono"></span>';
        var dash = n.indexOf('-');
        var type = dash > 0 ? n.substring(0, dash) : 'Change';
        var url = agileBaseUrl() + '/object/' + encodeURIComponent(type) + '/' + encodeURIComponent(n);
        // target="_blank" + rel="noopener" so the new tab can't reach back
        // into our window context. data-noselect is read by the body
        // mousedown handler so clicking the link doesn't also flip the cell
        // selection (the cell is read-only anyway; double-action would be
        // noisy when all the user wanted was to open Agile).
        return '<a class="cell-ecn u-mono" href="' + url + '" target="_blank" rel="noopener"' +
            ' data-noselect="1"' +
            ' title="Open ' + escapeAttr(n) + ' in Agile (new tab)"' +
            ' style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;">' +
            escapeHtml(n) + '</a>';
    }

    function ensureActiveVisible() {
        var grid = document.getElementById('iteGrid');
        if (!grid) return;
        var scroll = grid.parentNode;
        if (!scroll) return;
        var td = grid.querySelector('td[data-ri="' + STATE.sel.ri + '"][data-ci="' + STATE.sel.ci + '"]');
        if (!td) return;
        var ctR = scroll.getBoundingClientRect();
        var tdR = td.getBoundingClientRect();
        var padTop = 40;
        if (tdR.top < ctR.top + padTop) scroll.scrollTop -= (ctR.top + padTop - tdR.top);
        else if (tdR.bottom > ctR.bottom) scroll.scrollTop += (tdR.bottom - ctR.bottom + 8);
        if (tdR.left < ctR.left + 124) scroll.scrollLeft -= (ctR.left + 124 - tdR.left);
        else if (tdR.right > ctR.right) scroll.scrollLeft += (tdR.right - ctR.right + 8);
    }

    // ============================================================
    // Mouse interaction
    // ============================================================

    function onBodyMouseDown(ev) {
        // Click landed inside an active editor (date input, select, textarea)
        // — leave it alone. Previously, clicking the calendar icon inside an
        // <input type="date"> bubbled up here, triggered render(), and the
        // editor input was destroyed mid-pick.
        if (ev.target.closest && ev.target.closest('input, select, textarea')) return;
        // Fill-handle drag — start handle listeners before treating as selection.
        var handle = ev.target.closest && ev.target.closest('.ite2-handle');
        if (handle) {
            ev.preventDefault(); ev.stopPropagation();
            startFillDrag(STATE.sel.ri, STATE.sel.ci);
            return;
        }
        // Anchor / button / anything that opts out of cell-selection via
        // data-noselect="1" (e.g. the ECN Agile-link in the frozen left
        // column) should NOT also flip the active cell. Let the browser
        // handle the click natively and bail.
        var noSel = ev.target.closest && ev.target.closest('[data-noselect="1"]');
        if (noSel) return;
        var td = ev.target.closest && ev.target.closest('td[data-ri]');
        if (!td) return;
        var ri = parseInt(td.getAttribute('data-ri'), 10);
        var ci = parseInt(td.getAttribute('data-ci'), 10);
        if (isNaN(ri) || isNaN(ci)) return;
        if (ev.shiftKey && ci === STATE.sel.ci) {
            STATE.sel.r2 = ri;
        } else {
            STATE.sel.ri = ri; STATE.sel.ci = ci; STATE.sel.r2 = ri;
        }
        var grid = document.getElementById('iteGrid');
        if (grid) grid.focus({ preventScroll: true });
        // Just update the active-cell highlight; don't re-render the whole
        // body. The full innerHTML rewrite between two consecutive clicks
        // was confusing the browser's dblclick tracking, so a single click
        // could "swallow" the dblclick that should have opened the editor.
        updateActiveHighlight();
    }

    /** Selection-only repaint. Pulls the .is-active class to the newly-
     *  selected cell, drops it from wherever it was, syncs fill-handle and
     *  in-range styling. Used by mousedown so we don't trigger a full body
     *  innerHTML rewrite (and break dblclick / kill an open editor). */
    function updateActiveHighlight() {
        var grid = document.getElementById('iteGrid');
        if (!grid) return;
        // Drop active + in-range + any orphaned fill handle from the previous
        // selection.
        grid.querySelectorAll('td.is-active').forEach(function (td) {
            td.classList.remove('is-active');
            var h = td.querySelector('.ite2-handle');
            if (h) h.remove();
        });
        grid.querySelectorAll('td.is-inrange').forEach(function (td) {
            td.classList.remove('is-inrange');
        });
        // Apply to current selection.
        var ri = STATE.sel.ri, ci = STATE.sel.ci;
        var sel = grid.querySelector('td[data-ri="' + ri + '"][data-ci="' + ci + '"]');
        if (sel) {
            sel.classList.add('is-active');
            var col = COLUMNS[ci];
            if (col && col.edit && !sel.querySelector('.ite2-handle')) {
                var handle = document.createElement('span');
                handle.className = 'ite2-handle';
                handle.title = 'Drag to fill down';
                sel.appendChild(handle);
            }
        }
        // Mirror the in-range band if there's an active range (sel.ri != sel.r2).
        var fillEnd = STATE.fillDrag != null ? STATE.fillDrag : STATE.sel.r2;
        if (fillEnd !== ri) {
            var tds = grid.querySelectorAll('td[data-ci="' + ci + '"]');
            tds.forEach(function (td) {
                var tdRi = parseInt(td.getAttribute('data-ri'), 10);
                if (within(tdRi, ri, fillEnd) && tdRi !== ri) td.classList.add('is-inrange');
            });
        }
        ensureActiveVisible();
    }

    function onBodyDblClick(ev) {
        var td = ev.target.closest && ev.target.closest('td[data-ri]');
        if (!td) return;
        var ri = parseInt(td.getAttribute('data-ri'), 10);
        var ci = parseInt(td.getAttribute('data-ci'), 10);
        if (isNaN(ri) || isNaN(ci)) return;
        var col = COLUMNS[ci];
        if (!col.edit) {
            // Give the user feedback when they try to edit a read-only column,
            // so the double-click doesn't appear to silently no-op. Common
            // confusion when a user lands on the frozen ECN column first.
            showToast({ kind: 'warn', text: 'The ' + (col.label || 'this') + ' column is read-only. Editable columns are marked ✎.' });
            return;
        }
        startEdit(ri, ci);
    }

    function startFillDrag(startRi, startCi) {
        function onMove(ev) {
            var el = document.elementFromPoint(ev.clientX, ev.clientY);
            var td = el && el.closest ? el.closest('td[data-ri]') : null;
            if (td && parseInt(td.getAttribute('data-ci'), 10) === startCi) {
                var ri = parseInt(td.getAttribute('data-ri'), 10);
                if (ri !== STATE.fillDrag) {
                    STATE.fillDrag = ri;
                    repaintRange(startCi);
                }
            }
        }
        function onUp() {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            var end = STATE.fillDrag;
            STATE.fillDrag = null;
            if (end != null && end !== startRi) {
                fillRange(startCi, startRi, end);
                STATE.sel.ri = startRi;
                STATE.sel.ci = startCi;
                STATE.sel.r2 = end;
            }
            render();
        }
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    }

    /** Light-weight in-range repaint — flips the .is-inrange class on cells
     *  in the active column without re-rendering the body. Keeps drag-to-fill
     *  feeling snappy on big grids. */
    function repaintRange(ci) {
        var sel = STATE.sel;
        var end = (STATE.fillDrag != null ? STATE.fillDrag : sel.r2);
        var grid = document.getElementById('iteGrid');
        if (!grid) return;
        var tds = grid.querySelectorAll('td[data-ci="' + ci + '"]');
        tds.forEach(function (td) {
            var ri = parseInt(td.getAttribute('data-ri'), 10);
            var on = within(ri, sel.ri, end) && ri !== sel.ri;
            td.classList.toggle('is-inrange', on);
        });
    }

    // ============================================================
    // Keyboard
    // ============================================================

    function onGridKey(e) {
        if (STATE.editing) return;
        var col = COLUMNS[STATE.sel.ci];
        var k = e.key;
        if (k === 'ArrowDown') { e.preventDefault(); moveSel(1, 0, e.shiftKey); }
        else if (k === 'ArrowUp') { e.preventDefault(); moveSel(-1, 0, e.shiftKey); }
        else if (k === 'ArrowRight') { e.preventDefault(); moveSel(0, 1, false); }
        else if (k === 'ArrowLeft') { e.preventDefault(); moveSel(0, -1, false); }
        else if (k === 'Tab') { e.preventDefault(); moveToNextEditable(e.shiftKey ? -1 : 1); }
        else if (k === 'Enter') { e.preventDefault(); if (col && col.edit) startEdit(STATE.sel.ri, STATE.sel.ci); }
        else if (k === 'Escape') { e.preventDefault(); STATE.sel.r2 = STATE.sel.ri; render(); }
        else if ((k === 'd' || k === 'D') && (e.metaKey || e.ctrlKey)) { e.preventDefault(); fillDown(); }
        else if (k === 'Backspace' || k === 'Delete') {
            if (col && col.edit && col.type !== 'longtext') {
                e.preventDefault();
                var row = STATE.filtered[STATE.sel.ri];
                if (row) { writeCellEdit(row, col, ''); render(); }
            }
        } else if (k.length === 1 && !e.metaKey && !e.ctrlKey && !e.altKey) {
            if (col && col.edit && (col.type === 'number' || col.type === 'text')) {
                e.preventDefault();
                startEdit(STATE.sel.ri, STATE.sel.ci, k);
            }
        }
    }

    function moveSel(dr, dc, extend) {
        var s = STATE.sel;
        var nr = Math.min(Math.max(0, s.ri + dr), Math.max(0, STATE.filtered.length - 1));
        var nc = Math.min(Math.max(0, s.ci + dc), COLUMNS.length - 1);
        if (extend) {
            s.r2 = nr;
        } else {
            s.ri = nr; s.ci = nc; s.r2 = nr;
        }
        render();
    }

    function moveToNextEditable(dir) {
        var s = STATE.sel;
        var ri = s.ri, ci = s.ci;
        for (var guard = 0; guard < COLUMNS.length * Math.max(1, STATE.filtered.length) + COLUMNS.length; guard++) {
            ci += dir;
            if (ci >= COLUMNS.length) { ci = 0; ri = Math.min(ri + 1, STATE.filtered.length - 1); }
            if (ci < 0) { ci = COLUMNS.length - 1; ri = Math.max(ri - 1, 0); }
            if (COLUMNS[ci].edit) break;
        }
        s.ri = ri; s.ci = ci; s.r2 = ri;
        render();
    }

    // ============================================================
    // Edit start / commit / cancel
    // ============================================================

    function startEdit(ri, ci, seedChar) {
        var col = COLUMNS[ci]; if (!col.edit) return;
        var row = STATE.filtered[ri]; if (!row) return;
        if (col.type === 'longtext') {
            openLogPopover(row, col);
            return;
        }
        if (col.type === 'multilist') {
            openMultiListPopover(row, col);
            return;
        }

        var td = document.querySelector('#iteGridBody td[data-ri="' + ri + '"][data-ci="' + ci + '"]');
        if (!td || td.querySelector('input, select, textarea')) return;

        STATE.editing = true;

        var current = effValue(row, col);
        var startVal = seedChar != null ? seedChar : current;
        var input = buildEditor(col, startVal);
        td.innerHTML = '';
        td.appendChild(input);
        setTimeout(function () {
            try { input.focus(); if (input.select && input.type !== 'date') input.select(); } catch (e) {}
        }, 0);

        var committed = false;
        function done(advance) {
            if (committed) return;
            committed = true;
            STATE.editing = false;
            var v = input.value || '';
            writeCellEdit(row, col, v);
            if (advance === 'down') {
                STATE.sel.ri = Math.min(STATE.sel.ri + 1, STATE.filtered.length - 1);
                STATE.sel.r2 = STATE.sel.ri;
            } else if (advance === 'right') {
                moveToNextEditable(1);
                refocusGrid();
                return; // moveToNextEditable already calls render
            }
            render();
            refocusGrid();
        }
        function cancel() {
            if (committed) return;
            committed = true;
            STATE.editing = false;
            render();
            refocusGrid();
        }

        input.addEventListener('blur', function () {
            // Defer the commit so native browser overlays (date picker on
            // <input type="date">, OS-level select dropdown) have a moment
            // to keep focus on the editor while their UI is open. If focus
            // returned to the input within ~200ms, the user is still in the
            // picker — skip the commit and let the editor stay open.
            setTimeout(function () {
                if (committed) return;
                if (document.activeElement === input) return;
                done(null);
            }, 200);
        });
        // <input type="date">'s native picker fires a 'change' event when
        // the user actually selects a date — commit right then so we don't
        // have to wait for the deferred blur.
        if (col.type === 'date') {
            input.addEventListener('change', function () { done('down'); });
        }
        // <select> dropdowns (person + the new list-text editors for Project /
        // IT Actions Taken) should also commit on change. Picking a value is
        // a definite action; making the user also Tab/Enter to confirm is
        // extra friction.
        if (input.tagName === 'SELECT') {
            input.addEventListener('change', function () { done('down'); });
        }
        input.addEventListener('keydown', function (ev) {
            if (ev.key === 'Enter') { ev.preventDefault(); done('down'); }
            else if (ev.key === 'Tab') { ev.preventDefault(); done('right'); }
            else if (ev.key === 'Escape') { ev.preventDefault(); cancel(); }
        });
    }

    function refocusGrid() {
        var grid = document.getElementById('iteGrid');
        if (grid) setTimeout(function () { try { grid.focus({ preventScroll: true }); } catch (e) {} }, 0);
    }

    /** Choose the source list for a column's editor.
     *  Order of preference:
     *    1. The Agile-defined available-values for col.cell (data.cellOptions
     *       from the /data endpoint — populated by ItEnhancementsService via
     *       plm-agile-service /api/cell-options). This is the source Vikas
     *       asked for so dropdowns reflect Agile's list, not what's happened
     *       to appear in the cached rows.
     *    2. The COMPUTED_OPTS fallback (computed from row values). Kept so
     *       the legacy dropdowns still render if cell-options haven't loaded
     *       yet or the agile-service is unreachable.
     */
    function editorOptions(col) {
        if (col.cell && STATE.cellOptions && Array.isArray(STATE.cellOptions[col.cell])) {
            return STATE.cellOptions[col.cell];
        }
        // Legacy fallback keys for the three columns that previously had
        // computed-from-rows option lists.
        var key = (col.key === 'itOwner') ? 'OWNERS'
                : (col.key === 'project') ? 'PROJECT_SUGGESTIONS'
                : (col.key === 'itActions') ? 'ACTION_SUGGESTIONS'
                : null;
        if (key && COMPUTED_OPTS[key]) return COMPUTED_OPTS[key];
        return [];
    }

    /** Defeat browser password / form autofill on the inline cell editor.
     *  Chrome ignores autocomplete="off" on plain text inputs; it picks up on
     *  field name patterns and sometimes offers saved logins (which is what
     *  Vikas saw — "8252 / plmadmin / vikas.jindal@sandisk.com" suggestions
     *  popping up over the Project editor). Setting autocomplete to a value
     *  the password manager doesn't recognize (plus a random name) is the
     *  established workaround. */
    function disableAutofill(input) {
        input.setAttribute('autocomplete', 'one-time-code');
        input.setAttribute('autocorrect', 'off');
        input.setAttribute('autocapitalize', 'off');
        input.setAttribute('spellcheck', 'false');
        input.setAttribute('name', 'iteCellEditor_' + Math.random().toString(36).slice(2, 10));
    }

    function buildEditor(col, startVal) {
        if (col.type === 'date') {
            var i = document.createElement('input');
            i.type = 'date'; i.value = startVal || ''; i.className = 'ite2-input';
            disableAutofill(i);
            return i;
        }
        if (col.type === 'number') {
            var n = document.createElement('input');
            n.type = 'text'; n.inputMode = 'decimal'; n.value = startVal || '';
            n.className = 'ite2-input';
            disableAutofill(n);
            return n;
        }
        if (col.type === 'person') {
            var opts = editorOptions(col);
            var s = document.createElement('select');
            s.className = 'ite2-input ite2-select';
            var blank = document.createElement('option');
            blank.value = ''; blank.textContent = '— clear —';
            s.appendChild(blank);
            opts.forEach(function (o) {
                var opt = document.createElement('option');
                opt.value = o; opt.textContent = o;
                if (o === startVal) opt.selected = true;
                s.appendChild(opt);
            });
            // Stale-owner sentinel: if the row's current value isn't in the
            // Agile list (someone set in Agile Web who isn't on the active
            // user list anymore, e.g. inactive), append it so the editor
            // doesn't silently re-select to the first option.
            if (startVal && opts.indexOf(startVal) < 0) {
                var stale = document.createElement('option');
                stale.value = startVal; stale.textContent = startVal + ' (current)';
                stale.selected = true;
                s.appendChild(stale);
            }
            return s;
        }
        // PT-IT-ENH (Vikas, 2026-06-12): Project and IT Actions Taken are
        // multi-LIST cells. Earlier they used <input list="…"> with a
        // <datalist> sibling — Chrome's datalist popup positions itself
        // below the input but isn't reliably anchored to the active cell:
        // the user double-clicked row 1, then mouse-hovered row 2, and the
        // popup appeared near row 2 with no way to confidently click an
        // option. Switching to a real <select> when Agile-defined options
        // are present anchors the dropdown to the cell properly and gives
        // a click-to-pick interaction the user actually trusts.
        //
        // Trade-off: <select> is single-value. The cached row's display
        // value may be multi (semicolon-joined); we pre-select the first
        // segment and let the user pick a single new Agile-list value
        // (which replaces the multi-value on save). Multi-value editing
        // is therefore lossy from this UI in v1 — users who genuinely need
        // to set multiple Project / IT Actions Taken values should fall
        // back to Agile Web. If we re-introduce multi-value editing, a
        // popover with checkboxes (one row per cellOptions entry) is the
        // pattern to use.
        var agileOpts = (col.cell && STATE.cellOptions && Array.isArray(STATE.cellOptions[col.cell]))
                ? STATE.cellOptions[col.cell] : null;
        if (agileOpts && agileOpts.length) {
            var listSel = document.createElement('select');
            listSel.className = 'ite2-input ite2-select';
            var blankList = document.createElement('option');
            blankList.value = ''; blankList.textContent = '— clear —';
            listSel.appendChild(blankList);
            // If startVal is a multi-value display string ("A, B"), seed
            // the select with the first segment so the user sees something
            // selected.
            var firstSeg = String(startVal || '').split(/\s*,\s*/)[0].trim();
            agileOpts.forEach(function (o) {
                var opt = document.createElement('option');
                opt.value = o; opt.textContent = o;
                if (o === firstSeg) opt.selected = true;
                listSel.appendChild(opt);
            });
            // Preserve unmatched current value (e.g. an older option that
            // Agile no longer offers, or a multi-segment value where no
            // single segment matches a current Agile option) so picking
            // "— clear —" is the only way to lose it.
            if (startVal && agileOpts.indexOf(firstSeg) < 0) {
                var staleList = document.createElement('option');
                staleList.value = startVal; staleList.textContent = startVal + ' (current)';
                staleList.selected = true;
                listSel.appendChild(staleList);
            }
            return listSel;
        }

        // Plain text input — no Agile-defined list, no autofill, no datalist.
        var t = document.createElement('input');
        t.type = 'text'; t.className = 'ite2-input';
        t.value = startVal || '';
        disableAutofill(t);
        // Computed-from-rows fallback datalist (legacy behaviour). Kept so
        // text columns without Agile options still get typeahead from prior
        // row values.
        var datalistVals = editorOptions(col);
        if (datalistVals.length) {
            var listId = 'iteDL_' + col.key;
            t.setAttribute('list', listId);
            var dl = document.getElementById(listId);
            if (!dl) {
                dl = document.createElement('datalist');
                dl.id = listId;
                document.body.appendChild(dl);
            }
            dl.innerHTML = datalistVals.map(function (o) {
                return '<option value="' + escapeAttr(o) + '"></option>';
            }).join('');
        }
        return t;
    }

    function writeCellEdit(row, col, newVal) {
        if (!col.cell) return;
        var k = ckey(row.ecnNumber, col.cell);
        var committed = row[col.key] || '';
        if ((newVal || '') === committed) {
            // Same as original — clear any pending edit.
            delete STATE.dirty[k];
        } else {
            var v = validateCell(col, newVal, row);
            STATE.dirty[k] = {
                ecn: row.ecnNumber,
                cell: col.cell,
                value: newVal,
                status: v.ok ? 'pending' : 'error',
                msg: v.ok ? '' : v.msg
            };
        }
        refreshDirtyBadge();
        scheduleDraftSave();
    }

    function effValue(row, col) {
        if (!col.cell) return row[col.key] || '';
        var d = STATE.dirty[ckey(row.ecnNumber, col.cell)];
        return d ? (d.value || '') : (row[col.key] || '');
    }

    function validateCell(col, value, row) {
        if (col.type === 'number') {
            if (value && !/^\d+(\.\d+)?$/.test(String(value).trim())) {
                return { ok: false, msg: 'Hours must be a non-negative number.' };
            }
        }
        if (col.key === 'targetGoLive') {
            var uatCol = COLUMNS.filter(function (c) { return c.key === 'targetUAT'; })[0];
            var uatStr = effValue(row, uatCol);
            var uat = parseIsoDate(uatStr);
            var gl = parseIsoDate(value);
            if (uat && gl && gl < uat) {
                return { ok: false, msg: 'Go-Live is before Target UAT.' };
            }
        }
        return { ok: true };
    }
    function parseIsoDate(s) { return s ? new Date(s + 'T00:00:00') : null; }

    // ============================================================
    // Fill-down
    // ============================================================

    function fillRange(ci, r0, r1) {
        var col = COLUMNS[ci]; if (!col.edit) return;
        var src = STATE.filtered[r0]; if (!src) return;
        var val = effValue(src, col);
        var lo = Math.min(r0, r1), hi = Math.max(r0, r1);
        for (var ri = lo; ri <= hi; ri++) {
            if (ri === r0) continue;
            var row = STATE.filtered[ri]; if (!row) continue;
            writeCellEdit(row, col, val);
        }
    }

    function fillDown() {
        var sel = STATE.sel;
        var col = COLUMNS[sel.ci];
        if (!col || !col.edit) return;
        var lo = Math.min(sel.ri, sel.r2), hi = Math.max(sel.ri, sel.r2);
        if (hi === lo) {
            showToast({ kind: 'pending', text: 'Shift+↓ to select rows first, then Cmd/Ctrl+D — or drag the corner handle.' });
            return;
        }
        fillRange(sel.ci, sel.ri, sel.r2);
        var n = hi - lo;
        var row = STATE.filtered[sel.ri];
        var v = row ? effValue(row, col) : '';
        render();
        showToast({ kind: 'success', text: 'Filled "' + (v || '(blank)') + '" into ' + n + ' row' + (n === 1 ? '' : 's') + ' below.' });
    }

    // ============================================================
    // IT Log popover
    // ============================================================

    var LOG_POP_STATE = null;

    function openLogPopover(row, col) {
        // Build the popover lazily and append to body. Esc closes; Cmd/Ctrl+Enter
        // commits. Click outside the inner card closes (matches the prototype).
        closeLogPopover();
        var current = effValue(row, col) || '';
        var back = document.createElement('div');
        back.className = 'ite2-modal-back';
        back.id = 'iteLogPopBack';
        var pop = document.createElement('div');
        pop.className = 'ite2-logpop';
        pop.innerHTML =
            '<div class="u-label">IT Log · ' + escapeHtml(row.ecnNumber) + '</div>' +
            '<div class="ite2-logpop-h">Edit log entry</div>' +
            '<textarea class="ite2-textarea" rows="10" id="iteLogPopTxt"></textarea>' +
            '<div class="ite2-logpop-foot">' +
                '<span class="ite2-hint">Cmd/Ctrl+Enter to save · Esc to cancel</span>' +
                '<div class="ite2-btn-row">' +
                    '<button class="ite2-btn" id="iteLogPopCancel">Cancel</button>' +
                    '<button class="ite2-btn primary" id="iteLogPopSave">Apply</button>' +
                '</div>' +
            '</div>';
        back.appendChild(pop);
        document.body.appendChild(back);

        var txt = pop.querySelector('#iteLogPopTxt');
        txt.value = current;
        setTimeout(function () { try { txt.focus(); txt.setSelectionRange(current.length, current.length); } catch (e) {} }, 0);

        function commit() {
            var val = txt.value || '';
            writeCellEdit(row, col, val);
            closeLogPopover();
            render();
            refocusGrid();
        }
        function cancel() {
            closeLogPopover();
            refocusGrid();
        }
        pop.querySelector('#iteLogPopSave').addEventListener('click', commit);
        pop.querySelector('#iteLogPopCancel').addEventListener('click', cancel);
        back.addEventListener('mousedown', function (ev) {
            if (ev.target === back) cancel();
        });
        txt.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape') { ev.preventDefault(); cancel(); }
            else if (ev.key === 'Enter' && (ev.metaKey || ev.ctrlKey)) { ev.preventDefault(); commit(); }
        });
        LOG_POP_STATE = { back: back, commit: commit, cancel: cancel };
    }

    function closeLogPopover() {
        var back = document.getElementById('iteLogPopBack');
        if (back && back.parentNode) back.parentNode.removeChild(back);
        LOG_POP_STATE = null;
    }

    // ============================================================
    // Multi-list popover (Project / IT Actions Taken)
    // ============================================================
    //
    // PT-IT-ENH (Vikas, 2026-06-12): native <select> dropdowns don't anchor
    // reliably to grid cells — Chrome positions the popup wherever it wants,
    // which lands "adjacent but not in the cell" after a Tab. Native <select>
    // also can't do multi-select with a usable UX. The popover is a custom
    // modal sized to the available values list, with a top-anchored search
    // box and a checkbox per value. Pre-checks current selections; supports
    // sticky stale values that aren't in the Agile list anymore so the user
    // doesn't silently lose them by opening the editor.
    //
    // Wire format on save mirrors the legacy text-editor path: comma-joined
    // display string in dirty; wireValue() rewrites it to pipe-separated
    // before posting to /save-batch. As long as no individual Agile list
    // value contains a comma the split is loss-less; current Project + IT
    // Actions Taken values are all comma-free (eyeballed against the live
    // dropdown 2026-06-12). If that changes we'd switch dirty to store the
    // selection set directly and skip the comma round-trip.

    var MLIST_POP_STATE = null;

    function openMultiListPopover(row, col) {
        closeMultiListPopover();
        var optsAll = (STATE.cellOptions && Array.isArray(STATE.cellOptions[col.cell]))
                ? STATE.cellOptions[col.cell].slice() : [];
        var current = effValue(row, col) || '';
        // Pre-checked values, split from the row's current comma-joined
        // display string. Trim segments, drop empties.
        var selected = {};
        current.split(/\s*,\s*/).forEach(function (v) {
            var t = v.trim(); if (t) selected[t] = true;
        });
        // Any pre-checked value that isn't in the Agile list (e.g. a list
        // entry that got deactivated after this ECN was last touched) gets
        // pinned at the top so the user can keep it.
        var staleSet = {};
        Object.keys(selected).forEach(function (v) {
            if (optsAll.indexOf(v) < 0) staleSet[v] = true;
        });
        var allRows = Object.keys(staleSet).concat(optsAll);

        var back = document.createElement('div');
        back.className = 'ite2-modal-back';
        back.id = 'iteMlistPopBack';
        var pop = document.createElement('div');
        pop.className = 'ite2-mlistpop';
        // Inline styles so the popover renders even on a stripped
        // toolkit deployment that may have missed a CSS reload.
        pop.style.cssText = 'background:#fff; border:1px solid #E8E6DF; border-radius:8px; padding:18px 22px; min-width:420px; max-width:560px; max-height:80vh; display:flex; flex-direction:column; box-shadow:0 12px 40px rgba(0,0,0,0.25); font-family:inherit;';
        pop.innerHTML =
            '<div class="u-label" style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">' +
                escapeHtml(col.label) + ' &middot; ' + escapeHtml(row.ecnNumber) + '</div>' +
            '<div style="font-family:Georgia,serif; font-size:18px; margin-top:4px; margin-bottom:10px;">Select values</div>' +
            '<input type="text" id="iteMlistSearch" placeholder="Type to filter…" style="margin-bottom:8px; padding:6px 10px; border:1px solid #E8E6DF; border-radius:4px; font-size:13px; width:100%; box-sizing:border-box;">' +
            '<div id="iteMlistList" style="flex:1; overflow-y:auto; border:1px solid #E8E6DF; border-radius:4px; padding:4px 4px; min-height:260px; max-height:50vh; background:#FAFAF7;"></div>' +
            '<div style="display:flex; justify-content:space-between; align-items:center; margin-top:14px;">' +
                '<span style="color:#6B7280; font-size:11px;">Cmd/Ctrl+Enter to save &middot; Esc to cancel &middot; <span id="iteMlistCount">0 selected</span></span>' +
                '<div>' +
                    '<button class="ite2-btn" id="iteMlistClear" style="background:transparent; border:1px solid #E8E6DF; padding:6px 12px; border-radius:4px; font-size:12px; cursor:pointer; margin-right:6px;">Clear all</button>' +
                    '<button class="ite2-btn" id="iteMlistCancel" style="background:transparent; border:1px solid #E8E6DF; padding:6px 12px; border-radius:4px; font-size:12px; cursor:pointer; margin-right:6px;">Cancel</button>' +
                    '<button class="ite2-btn primary" id="iteMlistSave" style="background:#4a6fa5; color:#fff; border:1px solid #4a6fa5; padding:6px 14px; border-radius:4px; font-size:12px; cursor:pointer; font-weight:600;">Apply</button>' +
                '</div>' +
            '</div>';
        back.appendChild(pop);
        document.body.appendChild(back);

        var listEl = pop.querySelector('#iteMlistList');
        var searchEl = pop.querySelector('#iteMlistSearch');
        var countEl = pop.querySelector('#iteMlistCount');

        function updateCount() {
            var n = Object.keys(selected).filter(function (k) { return selected[k]; }).length;
            countEl.textContent = n + ' selected';
        }

        function renderList(filter) {
            var f = (filter || '').trim().toLowerCase();
            var rowsHtml = allRows.map(function (o) {
                if (f && o.toLowerCase().indexOf(f) < 0) return '';
                var checked = selected[o] ? 'checked' : '';
                var stale = staleSet[o]
                    ? ' <span style="color:#C7801B; font-size:10px;">(not in Agile list)</span>' : '';
                // Highlight stale-value rows with a subtle amber tint.
                var bg = staleSet[o] ? 'background:#fff8e1;' : '';
                return '<label style="display:block; padding:5px 10px; cursor:pointer; border-radius:3px; ' + bg + '">' +
                       '<input type="checkbox" data-val="' + escapeAttr(o) + '" ' + checked +
                       ' style="margin-right:8px; vertical-align:middle;">' + escapeHtml(o) + stale + '</label>';
            }).join('');
            listEl.innerHTML = rowsHtml || '<div style="padding:14px; text-align:center; color:#9CA3AF; font-size:12px;">No values match &ldquo;' + escapeHtml(filter || '') + '&rdquo;.</div>';
            Array.prototype.forEach.call(listEl.querySelectorAll('input[type="checkbox"]'), function (cb) {
                cb.addEventListener('change', function () {
                    var v = cb.getAttribute('data-val');
                    if (cb.checked) selected[v] = true;
                    else delete selected[v];
                    updateCount();
                });
            });
            updateCount();
        }

        renderList('');

        searchEl.addEventListener('input', function () { renderList(searchEl.value); });

        function commit() {
            var vals = Object.keys(selected).filter(function (v) { return selected[v]; });
            vals.sort(function (a, b) { return a.localeCompare(b); });
            writeCellEdit(row, col, vals.join(', '));
            closeMultiListPopover();
            render();
            refocusGrid();
        }
        function cancel() {
            closeMultiListPopover();
            refocusGrid();
        }
        function clearAll() {
            Object.keys(selected).forEach(function (k) { delete selected[k]; });
            renderList(searchEl.value);
        }

        pop.querySelector('#iteMlistSave').addEventListener('click', commit);
        pop.querySelector('#iteMlistCancel').addEventListener('click', cancel);
        pop.querySelector('#iteMlistClear').addEventListener('click', clearAll);
        back.addEventListener('mousedown', function (ev) {
            if (ev.target === back) cancel();
        });
        // Capture keys at the popover so the grid's keydown handler doesn't
        // steal Esc / Enter (which it uses for cell edit / nav).
        pop.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape') { ev.preventDefault(); ev.stopPropagation(); cancel(); }
            else if (ev.key === 'Enter' && (ev.metaKey || ev.ctrlKey)) {
                ev.preventDefault(); ev.stopPropagation(); commit();
            }
        });

        setTimeout(function () { try { searchEl.focus(); } catch (e) {} }, 0);
        MLIST_POP_STATE = { back: back, commit: commit, cancel: cancel };
    }

    function closeMultiListPopover() {
        var back = document.getElementById('iteMlistPopBack');
        if (back && back.parentNode) back.parentNode.removeChild(back);
        MLIST_POP_STATE = null;
    }

    // ============================================================
    // Restart ECN creation dialog
    // ============================================================

    function fmtMDY(iso) {                       // "2026-07-04" -> "07/04/26"
        var p = (iso || '').split('-');
        return p.length === 3 ? (p[1] + '/' + p[2] + '/' + p[0].slice(2)) : '';
    }
    function todayIso() {
        var d = new Date(), z = function (n) { return (n < 10 ? '0' : '') + n; };
        return d.getFullYear() + '-' + z(d.getMonth() + 1) + '-' + z(d.getDate());
    }

    function openRestartEcnDialog() {
        fetch('/api/it-enhancements/restart-ecn-candidates', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (data) { renderRestartEcnDialog(data); })
            .catch(function () { showToast({ kind: 'error', text: 'Could not load eligible ECNs.' }); });
    }

    function renderRestartEcnDialog(data) {
        closeRestartEcnDialog();
        var candidates = (data && data.candidates) || [];
        var roster = (data && data.itOwnerRoster) || [];
        if (!candidates.length) {
            showToast({ kind: 'warn', text: 'No ECNs at "UAT complete, CAB Prep" to bundle.' });
            return;
        }
        var selected = {};                       // ecn -> true

        var back = document.createElement('div');
        back.className = 'ite2-modal-back';
        back.id = 'iteRestartBack';
        var pop = document.createElement('div');
        pop.style.cssText = 'background:#fff; border:1px solid #E8E6DF; border-radius:10px; padding:20px 24px; min-width:520px; max-width:640px; max-height:86vh; display:flex; flex-direction:column; box-shadow:0 24px 64px rgba(15,23,32,0.22); font-family:inherit;';
        pop.innerHTML =
            '<style>.reLbl{font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; text-transform:uppercase; letter-spacing:1px; color:#6B7280; display:block;}' +
            '.rePill{font-family:inherit; background:#fff; border:1px solid #E8E6DF; padding:5px 13px; border-radius:999px; font-size:11px; cursor:pointer; color:#42526e;}' +
            '.rePill.on{background:#e8f0fe; border-color:#4a6fa5; color:#1a3a5c;}</style>' +
            '<div style="display:flex; justify-content:space-between; align-items:flex-start;">' +
                '<div><div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; text-transform:uppercase; letter-spacing:1.5px; color:#6B7280;">AGILE PLM &middot; IT ENHANCEMENTS</div>' +
                '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:22px; font-weight:600; color:#0F1720; margin-top:2px;">Create Restart ECN</div></div>' +
                '<span id="reTopClose" style="cursor:pointer; font-size:22px; color:#9CA3AF; line-height:1;">&times;</span>' +
            '</div>' +
            '<div style="border-bottom:1px solid #E8E6DF; margin:14px 0 16px;"></div>' +
            '<div style="display:flex; gap:22px;">' +
                '<div style="flex:1;"><label class="reLbl">Deployment date</label>' +
                    '<input type="date" id="reDate" value="' + todayIso() + '" style="display:block; width:100%; box-sizing:border-box; margin:5px 0 5px; padding:7px 10px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;">' +
                    '<div id="rePreview" style="font-size:12px; color:#6B7280;"></div></div>' +
                '<div style="flex:1;"><label class="reLbl">IT Owner</label>' +
                    '<select id="reOwner" style="display:block; width:100%; box-sizing:border-box; margin:5px 0 5px; padding:7px 10px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;"></select>' +
                    '<div id="reOwnerCap" style="font-size:12px; color:#6B7280;">PLM IT team &mdash; must not own a bundled ECN.</div>' +
                    '<div id="reOwnerMsg" style="color:#C7801B; font-size:11px;"></div></div>' +
            '</div>' +
            '<div style="border-bottom:1px solid #E8E6DF; margin:16px 0;"></div>' +
            '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; gap:10px;">' +
                '<label class="reLbl" style="flex:1;">Bundle enhancement ECNs &middot; UAT complete, CAB Prep</label>' +
                '<button type="button" id="reMyOnly" class="rePill">My ECNs only</button>' +
                '<button type="button" id="reSelAll" class="rePill">Select all</button>' +
                '<span id="reCount" style="color:#6B7280; font-size:11px; white-space:nowrap;">0 of 0 selected</span>' +
            '</div>' +
            '<div id="reList" style="overflow-y:auto; border:1px solid #E8E6DF; border-radius:6px; padding:4px; max-height:30vh; background:#FAFAF7;"></div>' +
            '<div style="display:flex; justify-content:space-between; align-items:center; margin-top:16px;">' +
                '<label class="reLbl">Attachments</label><span id="reAttNote" style="color:#9CA3AF; font-size:11px;">none yet</span>' +
            '</div>' +
            '<div style="font-size:11.5px; color:#6B7280; margin:4px 0 6px; line-height:1.4;">New files start tagged with every selected ECN &mdash; untoggle any that don\'t apply. References are written into the file description as ECN #, ECN # on the Attachments tab in Agile. .jar and .properties files also need an IT attachment type.</div>' +
            '<div id="reDrop" style="border:1.5px dashed #C9D2DE; border-radius:8px; padding:16px; text-align:center; color:#6B7280; font-size:12px; cursor:pointer; background:#FAFAF7; margin:2px 0;">&#8681; Drop files here or <span style="color:#4a6fa5; text-decoration:underline;">browse</span></div>' +
            '<div id="reDropErr" role="alert" style="display:none; margin:6px 0 2px; padding:8px 10px; border-left:3px solid #B8342B; background:#fdeaea; color:#B8342B; font-size:12px; border-radius:0 6px 6px 0;"></div>' +
            '<input type="file" id="reFileInput" multiple style="display:none;">' +
            '<div id="reFiles" style="max-height:26vh; overflow-y:auto;"></div>' +
            '<div id="reFileHint" style="color:#B8342B; font-size:11px; min-height:0;"></div>' +
            '<div id="reSteps" style="margin-top:10px;"></div>' +
            '<div id="reResult" style="font-size:12.5px; line-height:1.4; margin-top:8px; min-height:0; word-break:break-word;"></div>' +
            '<div id="reDetails" style="margin-top:2px;"></div>' +
            '<div style="border-top:1px solid #E8E6DF; margin-top:16px; padding-top:14px; display:flex; justify-content:space-between; align-items:center;">' +
                '<span id="reFootHint" style="color:#6B7280; font-size:12px;"></span>' +
                '<div style="white-space:nowrap;">' +
                    '<button type="button" id="reCancel" style="background:transparent; border:1px solid #E8E6DF; padding:8px 16px; border-radius:6px; font-size:13px; cursor:pointer; margin-right:8px;">Cancel</button>' +
                    '<button type="button" id="reCreate" disabled style="background:#4a6fa5; color:#fff; border:1px solid #4a6fa5; padding:8px 18px; border-radius:6px; font-size:13px; cursor:pointer; font-weight:600; opacity:0.5;">Create Restart ECN &rarr;</button>' +
                '</div>' +
            '</div>';
        back.appendChild(pop);
        document.body.appendChild(back);

        var dateEl = pop.querySelector('#reDate');
        var previewEl = pop.querySelector('#rePreview');
        var listEl = pop.querySelector('#reList');
        var countEl = pop.querySelector('#reCount');
        var ownerEl = pop.querySelector('#reOwner');
        var ownerMsg = pop.querySelector('#reOwnerMsg');
        var createBtn = pop.querySelector('#reCreate');

        // ---- Attachments state (§2) ----
        var attachTypes = (data && data.attachmentTypes) || {};   // ext -> [type values]
        var maxMb = (data && data.attachmentMaxMb) || 25;
        var reFiles = [];                                          // {id, file, ecns:{ecn:true}, type, note}
        var fileSeq = 0;
        // Which file row is expanded. null = auto (first incomplete file, and it
        // auto-advances as files are completed); '' = user collapsed everything;
        // otherwise the explicit file id the user clicked. Reset to null on add.
        var reOpenFileId = null;
        var filesEl = pop.querySelector('#reFiles');
        var dropEl = pop.querySelector('#reDrop');
        var fileInput = pop.querySelector('#reFileInput');
        var fileHint = pop.querySelector('#reFileHint');

        function checkedEcnList() { return Object.keys(selected).filter(function (k) { return selected[k]; }); }
        function extOf(name) { var m = /\.([^.\/\\]+)$/.exec(name || ''); return m ? m[1].toLowerCase() : ''; }
        function typesFor(name) { return attachTypes[extOf(name)] || []; }
        function humanSize(b) { return b < 1024 ? b + ' B' : (b < 1048576 ? (b / 1024).toFixed(0) + ' KB' : (b / 1048576).toFixed(1) + ' MB'); }
        // Mirrors RestartEcnService.buildFileDescription exactly — this is a preview
        // of what actually lands in Agile, so the two must stay in step: ECN refs,
        // then " — " and the note, or the filename when no note was typed.
        function descPreview(f) {
            var refs = Object.keys(f.ecns).filter(function (e) { return f.ecns[e] && selected[e]; });
            var base = refs.join(', ');
            var tail = (f.note && f.note.trim()) ? f.note.trim() : (f.file.name || '');
            if (!tail) return base;
            return (base ? base + ' — ' : '') + tail;
        }
        function addFiles(list) {
            var skipped = [];
            // Pre-tag every new file with the ECNs currently checked in the bundle —
            // the common case is "these jars belong to everything I just selected".
            // A file added before any ECN is checked starts untagged (red "needs ECN #").
            var preTag = {};
            checkedEcnList().forEach(function (e) { preTag[e] = true; });
            for (var i = 0; i < list.length; i++) {
                var file = list[i];
                if (file.size > maxMb * 1048576) { skipped.push(file.name + ' (' + humanSize(file.size) + ')'); continue; }
                var seed = {};
                Object.keys(preTag).forEach(function (e) { seed[e] = true; });
                reFiles.push({ id: ++fileSeq, file: file, ecns: seed, type: '', note: '' });
            }
            reOpenFileId = null;   // back to auto — land on the first incomplete file
            // Show the over-limit warning inside the modal (next to the dropzone),
            // not just as a page toast behind it — the toast is easy to miss.
            var errEl = pop.querySelector('#reDropErr');
            if (errEl) {
                if (skipped.length) {
                    errEl.innerHTML = '<b>' + skipped.length + ' file' + (skipped.length > 1 ? 's' : '') + ' skipped &mdash; over the ' + maxMb + '&nbsp;MB attachment limit:</b><br>' + skipped.map(function (s) { return escapeHtml(s); }).join('<br>');
                    errEl.style.display = 'block';
                } else {
                    errEl.style.display = 'none'; errEl.innerHTML = '';
                }
            }
            renderFiles();
        }
        // ---- Collapsible file rows — exactly one expanded at a time (2026-07-20).
        // Multiple .jar files made the dialog overflow; each file is now a compact
        // ~32px row (chevron · name · size · right-aligned summary · remove) and only
        // the file being edited is expanded.
        function fileRefs(f) { return Object.keys(f.ecns).filter(function (e) { return f.ecns[e] && selected[e]; }); }
        function fileStale(f) { return Object.keys(f.ecns).filter(function (e) { return f.ecns[e] && !selected[e]; }); }
        function fileIncomplete(f) {
            var opts = typesFor(f.file.name);
            return fileRefs(f).length === 0 || (opts.length > 0 && !f.type);
        }
        function autoOpenFileId() {
            for (var i = 0; i < reFiles.length; i++) { if (fileIncomplete(reFiles[i])) return reFiles[i].id; }
            return null;
        }
        function renderFiles() {
            var an = document.getElementById('reAttNote');
            if (an) an.textContent = reFiles.length ? (reFiles.length + ' file' + (reFiles.length > 1 ? 's' : '')) : 'none yet';
            if (!reFiles.length) { filesEl.innerHTML = ''; refreshCreate(); return; }
            var checked = checkedEcnList();
            // null = auto (first incomplete, auto-advances); '' = all collapsed.
            var openId = (reOpenFileId === null) ? autoOpenFileId() : reOpenFileId;
            filesEl.innerHTML = reFiles.map(function (f) {
                var opts = typesFor(f.file.name);
                var refs = fileRefs(f);
                var stale = fileStale(f);
                var tagged = refs.length > 0;
                var needsType = opts.length > 0 && !f.type;
                var complete = tagged && !needsType;
                var open = (openId !== null && openId !== '' && String(f.id) === String(openId));
                var summary = tagged
                    ? escapeHtml(refs.join(', ')) + (needsType ? ' &middot; needs type' : (f.type ? ' &middot; ' + escapeHtml(f.type) : ''))
                    : (stale.length ? 're-tag &mdash; ECNs left the bundle' : 'needs ECN #');
                var sumColor = complete ? '#6B7280' : '#B8342B';
                var hdr = '<div class="re-fhdr" data-fid="' + f.id + '" style="display:flex; align-items:center; gap:8px; cursor:pointer; min-width:0;">' +
                        '<span style="color:#9CA3AF; font-size:10px; width:10px; flex:0 0 auto;">' + (open ? '&#9662;' : '&#9656;') + '</span>' +
                        '<span style="color:#9CA3AF; flex:0 0 auto;">&#128206;</span>' +
                        '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; min-width:0; flex:1 1 auto;" title="' + escapeAttr(f.file.name) + '">' + escapeHtml(f.file.name) + '</span>' +
                        '<span style="color:#9CA3AF; font-size:11px; flex:0 0 auto;">' + humanSize(f.file.size) + '</span>' +
                        '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:' + sumColor + '; flex:0 1 auto; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:right;">' + summary + '</span>' +
                        '<span data-fid="' + f.id + '" class="re-frm" title="remove" style="cursor:pointer; color:#9CA3AF; font-size:16px; font-weight:700; padding-left:4px; flex:0 0 auto;">&times;</span>' +
                    '</div>';
                if (!open) {
                    return '<div style="border:1px solid ' + (complete ? '#E8E6DF' : '#E4A0A0') + '; border-radius:8px; padding:7px 10px; margin-bottom:6px; background:#fff;">' + hdr + '</div>';
                }
                var chips = checked.map(function (e) {
                    var on = !!f.ecns[e];
                    return '<span data-fid="' + f.id + '" data-ecn="' + escapeAttr(e) + '" class="re-chip" style="display:inline-block; cursor:pointer; font-size:11px; padding:2px 8px; margin:2px 3px 0 0; border-radius:999px; border:1px solid ' + (on ? '#4a6fa5' : '#E8E6DF') + '; background:' + (on ? '#e8f0fe' : '#fff') + '; color:' + (on ? '#1a3a5c' : '#6B7280') + ';">' + (on ? '✓ ' : '') + escapeHtml(e) + '</span>';
                }).join('');
                var applyAll = (tagged && reFiles.length > 1)
                    ? '<button type="button" class="re-fapply" data-fid="' + f.id + '" title="Copy this file\'s ECN references onto every pending file" style="border:0; background:transparent; color:#4a6fa5; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; cursor:pointer; padding:0 2px; margin-left:auto; white-space:nowrap;">apply to all files</button>'
                    : '';
                var typeSel = opts.length ? ('<select data-fid="' + f.id + '" class="re-ftype" style="font-size:12px; padding:6px 8px; border:1px solid ' + (f.type ? '#E8E6DF' : '#4a6fa5') + '; border-radius:6px;"><option value="">— IT attachment type —</option>' + opts.map(function (o) { return '<option value="' + escapeAttr(o) + '"' + (f.type === o ? ' selected' : '') + '>' + escapeHtml(o) + '</option>'; }).join('') + '</select>') : '';
                return '<div style="border:1px solid ' + (complete ? '#E8E6DF' : '#E4A0A0') + '; border-radius:8px; padding:10px 12px; margin-bottom:8px; background:#fff;">' + hdr +
                    '<div style="display:flex; align-items:center; flex-wrap:wrap; margin-top:8px;"><span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; text-transform:uppercase; letter-spacing:1px; color:#9CA3AF; margin-right:6px;">for ecn #</span>' + (chips || '<span style="color:#C7801B; font-size:11px;">check ECNs above to tag this file</span>') + applyAll + '</div>' +
                    (stale.length ? '<div style="color:#B8342B; font-size:11px; margin-top:4px;">re-tag this file — references unbundled ECN' + (stale.length > 1 ? 's' : '') + ': ' + escapeHtml(stale.join(', ')) + '</div>' : '') +
                    '<div style="display:flex; gap:8px; margin-top:8px;">' + typeSel +
                        '<input data-fid="' + f.id + '" class="re-fnote" placeholder="Description note (optional)" value="' + escapeAttr(f.note || '') + '" style="flex:1; box-sizing:border-box; font-size:12px; padding:6px 8px; border:1px solid #E8E6DF; border-radius:6px;">' +
                    '</div>' +
                    '<div class="re-fdesc" data-fid="' + f.id + '" style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; color:#6B7280; margin-top:8px; word-break:break-word;">description: ' + escapeHtml(descPreview(f)) + '</div>' +
                '</div>';
            }).join('');
            // Row header toggles expansion. Controls inside stopPropagation so they
            // don't collapse the card out from under the click.
            Array.prototype.forEach.call(filesEl.querySelectorAll('.re-fhdr'), function (h) {
                h.addEventListener('click', function () {
                    var id = h.getAttribute('data-fid');
                    reOpenFileId = (openId !== null && openId !== '' && String(openId) === String(id)) ? '' : id;
                    renderFiles();
                });
            });
            Array.prototype.forEach.call(filesEl.querySelectorAll('.re-chip'), function (ch) {
                ch.addEventListener('click', function (ev) {
                    ev.stopPropagation();
                    var f = reFiles.filter(function (x) { return x.id == ch.getAttribute('data-fid'); })[0]; if (!f) return;
                    var e = ch.getAttribute('data-ecn'); if (f.ecns[e]) delete f.ecns[e]; else f.ecns[e] = true; renderFiles();
                });
            });
            Array.prototype.forEach.call(filesEl.querySelectorAll('.re-fapply'), function (b) {
                b.addEventListener('click', function (ev) {
                    ev.stopPropagation();
                    var f = reFiles.filter(function (x) { return x.id == b.getAttribute('data-fid'); })[0]; if (!f) return;
                    var src = Object.keys(f.ecns).filter(function (e) { return f.ecns[e]; });
                    reFiles.forEach(function (x) {
                        var next = {}; src.forEach(function (e) { next[e] = true; }); x.ecns = next;
                    });
                    renderFiles();
                });
            });
            Array.prototype.forEach.call(filesEl.querySelectorAll('.re-frm'), function (x) {
                x.addEventListener('click', function (ev) {
                    ev.stopPropagation();   // removing must not expand/collapse the row
                    reFiles = reFiles.filter(function (y) { return y.id != x.getAttribute('data-fid'); });
                    renderFiles();
                });
            });
            Array.prototype.forEach.call(filesEl.querySelectorAll('.re-ftype'), function (s) {
                s.addEventListener('click', function (ev) { ev.stopPropagation(); });
                s.addEventListener('change', function () { var f = reFiles.filter(function (x) { return x.id == s.getAttribute('data-fid'); })[0]; if (f) { f.type = s.value; renderFiles(); } });
            });
            Array.prototype.forEach.call(filesEl.querySelectorAll('.re-fnote'), function (n) {
                n.addEventListener('click', function (ev) { ev.stopPropagation(); });
                n.addEventListener('input', function () {
                    var f = reFiles.filter(function (x) { return x.id == n.getAttribute('data-fid'); })[0]; if (!f) return;
                    f.note = n.value;
                    var d = filesEl.querySelector('.re-fdesc[data-fid="' + f.id + '"]');
                    if (d) d.textContent = 'description: ' + descPreview(f);
                });
            });
            refreshCreate();
        }

        function excludedOwnerLogins() {         // loginIds of the CHECKED ECNs' owners
            var set = {};
            candidates.forEach(function (c) {
                if (selected[c.ecn] && c.itOwnerLoginId) set[String(c.itOwnerLoginId).toLowerCase()] = true;
            });
            return set;
        }
        function rebuildOwners() {
            // Roster is [{login, name}]. Exclude by loginId the owner of any CHECKED
            // ECN (robust — same loginId source as the candidates), leaving the rest.
            var excl = excludedOwnerLogins();
            var prev = ownerEl.value;
            var opts = ['<option value="">— choose IT owner —</option>'];
            roster.forEach(function (o) {
                var login = (o && o.login) ? String(o.login) : '';
                if (!login || excl[login.toLowerCase()]) return;
                opts.push('<option value="' + escapeAttr(login) + '">' + escapeHtml((o && o.name) || login) + '</option>');
            });
            ownerEl.innerHTML = opts.join('');
            var match = null;
            for (var i = 0; i < ownerEl.options.length; i++) {
                if (ownerEl.options[i].value === prev) { match = ownerEl.options[i]; break; }
            }
            if (prev && match) ownerEl.value = prev; else ownerEl.value = '';
            var remaining = ownerEl.options.length - 1;
            var anyTicked = Object.keys(selected).some(function (k) { return selected[k]; });
            if (remaining !== 0) ownerMsg.textContent = '';
            else if (roster.length === 0) ownerMsg.textContent = 'No IT owners found in the enhancement data.';
            else if (anyTicked) ownerMsg.textContent = 'No eligible IT owner — every IT owner owns one of the selected ECNs.';
            else ownerMsg.textContent = '';
            var cap = document.getElementById('reOwnerCap');
            if (cap) cap.style.display = ownerMsg.textContent ? 'none' : '';
            refreshCreate();
        }
        function refreshCreate() {
            var n = checkedEcnList().length;
            countEl.textContent = n + ' of ' + candidates.length + ' selected';
            var needRef = 0, needType = 0;
            reFiles.forEach(function (f) {
                var refs = Object.keys(f.ecns).filter(function (e) { return f.ecns[e] && selected[e]; });
                if (refs.length === 0) needRef++;
                else if (typesFor(f.file.name).length > 0 && !f.type) needType++;
            });
            if (fileHint) {
                var h = '';
                if (needRef) h += needRef + ' file' + (needRef > 1 ? 's' : '') + ' still need an ECN reference. ';
                if (needType) h += needType + ' file' + (needType > 1 ? 's' : '') + ' still need an IT attachment type.';
                fileHint.textContent = h;
            }
            var ok = dateEl.value && n > 0 && ownerEl.value && needRef === 0 && needType === 0;
            createBtn.disabled = !ok;
            createBtn.style.opacity = ok ? '1' : '0.5';
            var fh = document.getElementById('reFootHint');
            if (fh) {
                var hint = '';
                if (!dateEl.value) hint = 'Pick a deployment date.';
                else if (n === 0) hint = 'Select at least one enhancement ECN.';
                else if (!ownerEl.value) hint = 'Choose an IT owner.';
                else if (needRef || needType) hint = 'Finish tagging the attachments.';
                fh.textContent = hint;
            }
        }
        function updatePreview() { previewEl.textContent = dateEl.value ? ('Deployment ' + fmtMDY(dateEl.value) + ' · Restart Agile Service') : ''; }

        // Render the checklist (filtered by the "My ECNs only" toggle).
        var myOnly = false;
        function visibleCandidates() {
            if (!myOnly) return candidates;
            var me = lc((STATE.currentUser && STATE.currentUser.loginId) || '');
            return candidates.filter(function (c) { return lc(c.itOwnerLoginId) === me; });
        }
        function renderChecklist() {
            var vis = visibleCandidates();
            if (!vis.length) {
                listEl.innerHTML = '<div style="padding:10px; color:#9CA3AF; font-size:12px;">' + (myOnly ? 'None of the eligible ECNs are owned by you.' : 'No eligible ECNs.') + '</div>';
                return;
            }
            listEl.innerHTML = vis.map(function (c) {
                var sub = (c.proposal || '').slice(0, 80);
                var ck = selected[c.ecn] ? ' checked' : '';
                return '<label style="display:block; padding:7px 10px; cursor:pointer; border-radius:4px;' + (selected[c.ecn] ? ' background:#e8f0fe;' : '') + '">' +
                    '<span style="float:right; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; letter-spacing:1px; text-transform:uppercase; color:#9CA3AF; margin-top:2px;">' + escapeHtml(c.workflowStatus || '') + '</span>' +
                    '<input type="checkbox" data-ecn="' + escapeAttr(c.ecn) + '"' + ck + ' style="margin-right:8px; vertical-align:middle;">' +
                    '<b>' + escapeHtml(c.ecn) + '</b> <span style="color:#6B7280; font-size:11px;">' +
                    escapeHtml(c.itOwner || '') + '</span><br>' +
                    '<span style="color:#6B7280; font-size:11px; margin-left:24px;">' + escapeHtml(sub) + '</span></label>';
            }).join('');
            Array.prototype.forEach.call(listEl.querySelectorAll('input[type="checkbox"]'), function (cb) {
                cb.addEventListener('change', function () {
                    var e = cb.getAttribute('data-ecn');
                    if (cb.checked) selected[e] = true; else delete selected[e];
                    if (cb.parentNode) cb.parentNode.style.background = cb.checked ? '#e8f0fe' : '';
                    rebuildOwners();
                    renderFiles();   // chips reflect the new bundle; flag now-unbundled refs
                });
            });
        }
        renderChecklist();
        pop.querySelector('#reMyOnly').addEventListener('click', function () { myOnly = !myOnly; this.classList.toggle('on', myOnly); renderChecklist(); });
        pop.querySelector('#reTopClose').addEventListener('click', function () { closeRestartEcnDialog(); });

        pop.querySelector('#reSelAll').addEventListener('click', function () {
            Array.prototype.forEach.call(listEl.querySelectorAll('input[type="checkbox"]'), function (cb) {
                cb.checked = true; selected[cb.getAttribute('data-ecn')] = true;
            });
            rebuildOwners();
            renderFiles();
        });
        dateEl.addEventListener('input', function () { updatePreview(); refreshCreate(); });
        ownerEl.addEventListener('change', function () { refreshCreate(); });
        pop.querySelector('#reCancel').addEventListener('click', function () { closeRestartEcnDialog(); });
        back.addEventListener('mousedown', function (ev) { if (ev.target === back) closeRestartEcnDialog(); });
        // Attachments: drag-drop + browse.
        if (dropEl) {
            dropEl.addEventListener('click', function () { fileInput.click(); });
            dropEl.addEventListener('dragover', function (ev) { ev.preventDefault(); dropEl.style.background = '#e8f0fe'; });
            dropEl.addEventListener('dragleave', function () { dropEl.style.background = '#FAFAF7'; });
            dropEl.addEventListener('drop', function (ev) {
                ev.preventDefault(); dropEl.style.background = '#FAFAF7';
                if (ev.dataTransfer && ev.dataTransfer.files && ev.dataTransfer.files.length) addFiles(ev.dataTransfer.files);
            });
        }
        if (fileInput) fileInput.addEventListener('change', function () { if (fileInput.files.length) addFiles(fileInput.files); fileInput.value = ''; });
        createBtn.addEventListener('click', function () {
            submitRestartEcn({
                deployDate: dateEl.value,
                itOwnerLogin: ownerEl.value,       // Agile loginid (option value); server re-validates by login
                relatedEcns: checkedEcnList(),
                _files: reFiles                    // {file, ecns, type, note}[] — uploaded after create
            }, createBtn);
        });

        updatePreview(); rebuildOwners(); refreshCreate();
        RESTART_BACK = back;
    }

    var RESTART_BACK = null;
    function closeRestartEcnDialog() { if (RESTART_BACK) { RESTART_BACK.remove(); RESTART_BACK = null; } }

    // ---- Restart ECN history drawer (§4) ----
    var RESTART_DRAWER = null;
    function closeRestartDrawer() { if (RESTART_DRAWER) { RESTART_DRAWER.remove(); RESTART_DRAWER = null; } }
    function restartStatusChip(s) {
        var st = s || 'Pending';
        var bg = st === 'Pending' ? '#fff3cd' : /deploy/i.test(st) ? '#e8f5e9' : '#eef1f5';
        var fg = st === 'Pending' ? '#856404' : /deploy/i.test(st) ? '#155724' : '#42526e';
        return '<span style="background:' + bg + '; color:' + fg + '; font-size:11px; padding:1px 8px; border-radius:999px;">' + escapeHtml(st) + '</span>';
    }
    function reFmtWhen(iso) {
        if (!iso) return '';
        try { var d = new Date(iso); if (isNaN(d.getTime())) return iso; return d.toLocaleString([], { year: '2-digit', month: 'numeric', day: 'numeric', hour: 'numeric', minute: '2-digit' }); }
        catch (e) { return iso; }
    }
    function updateRestartHistCount(n) {
        var el = document.getElementById('iteRestartHistCount'); if (!el) return;
        if (n > 0) { el.textContent = n; el.style.display = ''; } else { el.style.display = 'none'; }
    }
    function openRestartHistory(detailEcn, fromEmail) {
        closeRestartDrawer();
        var back = document.createElement('div');
        back.id = 'iteRestartDrawerBack';
        back.style.cssText = 'position:fixed; inset:0; background:rgba(20,24,40,0.32); z-index:10000; display:flex; justify-content:flex-end;';
        var panel = document.createElement('div');
        panel.style.cssText = 'background:#fff; width:480px; max-width:96vw; height:100%; box-shadow:-8px 0 32px rgba(0,0,0,0.18); display:flex; flex-direction:column; font-family:inherit;';
        panel.innerHTML =
            '<div style="padding:14px 18px; border-bottom:1px solid #E8E6DF; display:flex; justify-content:space-between; align-items:center;">' +
                '<div><div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">Agile PLM &middot; IT Enhancements</div>' +
                '<div id="reDrawerTitle" style="font-family:Georgia,serif; font-size:18px;">Restart history</div></div>' +
                '<span id="reDrawerClose" style="cursor:pointer; font-size:22px; color:#6B7280; line-height:1;">&times;</span></div>' +
            '<div id="reDrawerNotice" style="display:none; background:#e8f0fe; color:#1a3a5c; font-size:11.5px; padding:8px 18px; border-bottom:1px solid #d6e0f5;"></div>' +
            '<div id="reDrawerBody" style="flex:1; overflow-y:auto; padding:14px 18px;">Loading…</div>';
        back.appendChild(panel);
        document.body.appendChild(back);
        panel.querySelector('#reDrawerClose').addEventListener('click', closeRestartDrawer);
        back.addEventListener('mousedown', function (ev) { if (ev.target === back) closeRestartDrawer(); });
        RESTART_DRAWER = back;
        if (detailEcn) {
            if (fromEmail) {
                var nt = document.getElementById('reDrawerNotice');
                if (nt) { nt.style.display = ''; nt.textContent = 'Opened from the notification email — additions here are emailed to pdl-plm-admin@sandisk.com.'; }
            }
            showRestartDetail(detailEcn);
        } else {
            loadRestartList();
        }
    }
    // Deep link: #it-enhancements/restart/{ecn} opens the tab + drawer detail + arrival notice.
    function iteHandleRestartDeepLink() {
        var h = (window.location.hash || '').replace(/^#/, '');
        var m = /it-enhancements\/restart\/([^\/?&]+)/.exec(h);
        if (!m) return;
        var ecn = decodeURIComponent(m[1]);
        if (typeof switchTab === 'function') { try { switchTab('it-enhancements'); } catch (e) {} }
        openRestartHistory(ecn, true);
    }
    function loadRestartList() {
        var body = document.getElementById('reDrawerBody'); if (body) body.innerHTML = 'Loading…';
        var t = document.getElementById('reDrawerTitle'); if (t) t.textContent = 'Restart history';
        fetch('/api/it-enhancements/restart-ecn/history', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); }).then(renderRestartList)
            .catch(function () { var b = document.getElementById('reDrawerBody'); if (b) b.innerHTML = '<div style="color:#B8342B;">Could not load history.</div>'; });
    }
    function renderRestartList(data) {
        var body = document.getElementById('reDrawerBody'); if (!body) return;
        var rows = (data && data.history) || [];
        updateRestartHistCount(rows.length);
        if (!rows.length) { body.innerHTML = '<div style="color:#6B7280; font-size:13px;">No Restart ECNs created via the toolkit yet.</div>'; return; }
        body.innerHTML = rows.map(function (r) {
            return '<div class="re-hrow" data-ecn="' + escapeAttr(r.ecn) + '" style="border:1px solid #E8E6DF; border-radius:6px; padding:10px 12px; margin-bottom:8px; cursor:pointer;">' +
                '<div style="display:flex; justify-content:space-between; align-items:center;"><b style="font-size:13px;">' + escapeHtml(r.ecn) + '</b>' + restartStatusChip(r.status) + '</div>' +
                '<div style="color:#6B7280; font-size:11.5px; margin-top:3px;">deploy ' + escapeHtml(fmtMDY(r.deployDate) || r.deployDate || '') + ' &middot; ' + (r.ecnCount || 0) + ' ECN' + (r.ecnCount === 1 ? '' : 's') + ' &middot; ' + (r.fileCount || 0) + ' file' + (r.fileCount === 1 ? '' : 's') + '</div>' +
                '<div style="color:#9CA3AF; font-size:11px; margin-top:2px;">by ' + escapeHtml(r.createdBy || '') + ' &middot; ' + escapeHtml(reFmtWhen(r.createdAt)) + '</div>' +
            '</div>';
        }).join('');
        Array.prototype.forEach.call(body.querySelectorAll('.re-hrow'), function (el) {
            el.addEventListener('click', function () { showRestartDetail(el.getAttribute('data-ecn')); });
        });
    }
    function showRestartDetail(ecn) {
        var body = document.getElementById('reDrawerBody'); if (body) body.innerHTML = 'Loading…';
        fetch('/api/it-enhancements/restart-ecn/' + encodeURIComponent(ecn), { credentials: 'same-origin' })
            .then(function (r) { return r.json(); }).then(function (d) { renderRestartDetail(d && d.record); })
            .catch(function () { var b = document.getElementById('reDrawerBody'); if (b) b.innerHTML = '<div style="color:#B8342B;">Could not load detail.</div>'; });
    }
    var RE_MONO = '\'IBM Plex Mono\',Consolas,monospace';
    function reSectionHead(t) {
        return '<div style="font-family:' + RE_MONO + '; font-size:10.5px; text-transform:uppercase; letter-spacing:1px; color:#6B7280; border-top:1px solid #E8E6DF; padding-top:14px; margin-top:14px;">' + t + '</div>';
    }
    function reSectionHeadAction(t, btnId, label) {
        return '<div style="display:flex; justify-content:space-between; align-items:center; border-top:1px solid #E8E6DF; padding-top:14px; margin-top:14px; margin-bottom:8px; gap:10px;">' +
            '<span style="font-family:' + RE_MONO + '; font-size:10.5px; text-transform:uppercase; letter-spacing:1px; color:#6B7280;">' + t + '</span>' +
            '<span id="' + btnId + '" style="cursor:pointer; border:1px solid #E8E6DF; border-radius:6px; padding:5px 12px; font-size:12px; color:#42526e; background:#fff; font-weight:600; white-space:nowrap;">' + label + '</span></div>';
    }
    function reMetaCol(label, val) {
        return '<div style="flex:1; min-width:0;"><div style="font-family:' + RE_MONO + '; font-size:9.5px; text-transform:uppercase; letter-spacing:1px; color:#9CA3AF;">' + label + '</div>' +
            '<div style="font-size:13px; color:#0F1720; margin-top:3px; word-break:break-word;">' + escapeHtml(val || '—') + '</div></div>';
    }
    function reStatusPill(s) {
        var st = s || 'Pending';
        var dep = /deploy/i.test(st);
        var bg = dep ? '#e8f5e9' : '#eef1f5', fg = dep ? '#155724' : '#42526e';
        return '<span style="background:' + bg + '; color:' + fg + '; font-family:' + RE_MONO + '; font-size:10px; text-transform:uppercase; letter-spacing:1px; padding:2px 10px; border-radius:999px;">&#9679; ' + escapeHtml(st) + '</span>';
    }
    function renderRestartDetail(rec) {
        var body = document.getElementById('reDrawerBody'); if (!body) return;
        if (!rec) { body.innerHTML = '<div style="color:#B8342B;">Not found.</div>'; return; }
        var t = document.getElementById('reDrawerTitle'); if (t) t.textContent = rec.ecn;
        var type = rec.ecn.indexOf('-') > 0 ? rec.ecn.substring(0, rec.ecn.indexOf('-')) : 'Change';
        var agileUrl = agileBaseUrl() + '/object/' + encodeURIComponent(type) + '/' + encodeURIComponent(rec.ecn);
        var relHtml = (rec.bundled || []).map(function (b) {
            return '<div style="border:1px solid #E8E6DF; border-radius:6px; padding:8px 10px; margin-bottom:6px; display:flex; align-items:center; gap:8px;">' +
                '<b style="font-size:12.5px; white-space:nowrap;">' + escapeHtml(b.ecn) + '</b>' +
                '<span style="color:#6B7280; font-size:11.5px; flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">' + escapeHtml(b.proposal || '') + '</span>' +
                '<span style="color:#9CA3AF; font-size:11px; white-space:nowrap;">' + escapeHtml(b.itOwner || '') + '</span></div>';
        }).join('') || '<div style="color:#9CA3AF; font-size:12px;">none</div>';
        var fileHtml = (rec.files || []).map(function (f) {
            return '<div style="border-bottom:1px solid #F1F0EC; padding:8px 2px;"><b style="font-size:12.5px; word-break:break-all;">' + escapeHtml(f.name) + '</b>' +
                (f.type ? ' <span style="font-size:10px; background:#eef1f5; color:#42526e; padding:1px 8px; border-radius:999px;">' + escapeHtml(f.type) + '</span>' : '') +
                '<div style="color:#6B7280; font-size:11px; margin-top:2px;">' + escapeHtml((f.ecns || []).join(', ')) + ' &middot; ' + escapeHtml(f.by || '') + '</div></div>';
        }).join('') || '<div style="color:#9CA3AF; font-size:12px;">none</div>';
        var actHtml = (rec.activity || []).map(function (a) {
            var icon = (a.type === 'notify')
                ? '<span style="color:#4a6fa5; margin-right:8px;">&#9993;</span>'
                : '<span style="color:#C9D2DE; margin-right:8px;">&#9679;</span>';
            return '<div style="display:flex; justify-content:space-between; align-items:baseline; padding:5px 0; font-size:11.5px;">' +
                '<span style="color:#0F1720;">' + icon + escapeHtml(a.text || a.type || '') + (a.by ? ' <span style="color:#9CA3AF;">&mdash; ' + escapeHtml(a.by) + '</span>' : '') + '</span>' +
                '<span style="color:#9CA3AF; white-space:nowrap; margin-left:10px;">' + escapeHtml(reFmtWhen(a.ts)) + '</span></div>';
        }).join('') || '<div style="color:#9CA3AF; font-size:12px;">none</div>';
        var relN = (rec.bundled || []).length, fileN = (rec.files || []).length;
        var ro = !!rec.deployed;   // Released/Completed in Agile → read-only
        var relHead = 'Relationships &middot; ' + relN + ' enhancement ECN' + (relN === 1 ? '' : 's');
        var fileHead = 'Attachments &middot; ' + fileN + ' file' + (fileN === 1 ? '' : 's');
        body.innerHTML =
            '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">' +
                '<span id="reBackLink" style="cursor:pointer; color:#4a6fa5; font-size:12px;">&larr; All Restart ECNs</span>' +
                '<a href="' + agileUrl + '" target="_blank" rel="noopener" style="color:#4a6fa5; font-size:12px; text-decoration:none;">Open in Agile &#8599;</a>' +
            '</div>' +
            '<div style="display:flex; align-items:center; gap:10px;"><span style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:20px; color:#0F1720;">' + escapeHtml(rec.ecn) + '</span>' + reStatusPill(rec.status) + '</div>' +
            (ro ? '<div style="font-size:11.5px; color:#155724; margin-top:6px;">Deployed in Agile — read-only.</div>' : '') +
            '<div style="display:flex; gap:18px; margin:14px 0 2px;">' +
                reMetaCol('Deployment', fmtMDY(rec.deployDate) || rec.deployDate) +
                reMetaCol('IT Owner', rec.itOwnerName || rec.itOwnerLogin) +
                reMetaCol('Created by', rec.createdBy) +
                reMetaCol('Created', reFmtWhen(rec.createdAt)) +
            '</div>' +
            (ro ? reSectionHead(relHead) : reSectionHeadAction(relHead, 'reAddEcnsBtn', '+ Add ECNs')) + '<div id="reAddEcnsForm"></div>' + relHtml +
            (ro ? reSectionHead(fileHead) : reSectionHeadAction(fileHead, 'reAddFilesBtn', '+ Add files')) + '<div id="reAddFilesForm"></div>' + fileHtml +
            reSectionHead('Activity &middot; every action emails pdl-plm-admin@sandisk.com') + actHtml;
        var bl = document.getElementById('reBackLink'); if (bl) bl.addEventListener('click', loadRestartList);
        if (!ro) {
            var ae = document.getElementById('reAddEcnsBtn'); if (ae) ae.addEventListener('click', function () { openAddEcnsForm(rec); });
            var af = document.getElementById('reAddFilesBtn'); if (af) af.addEventListener('click', function () { openAddFilesForm(rec); });
        }
    }

    // ---- §4d: inline "Add ECNs" in the drawer detail ----
    function openAddEcnsForm(rec) {
        var host = document.getElementById('reAddEcnsForm'); if (!host) return;
        if (host.getAttribute('data-open') === '1') { host.innerHTML = ''; host.removeAttribute('data-open'); return; }
        host.setAttribute('data-open', '1');
        host.innerHTML = '<div style="color:#6B7280; font-size:12px; padding:6px 0;">Loading eligible ECNs…</div>';
        fetch('/api/it-enhancements/restart-ecn-candidates', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                var cands = (d && d.candidates) || [];
                var bundled = {}; (rec.bundled || []).forEach(function (b) { bundled[b.ecn] = true; });
                var avail = cands.filter(function (c) { return !bundled[c.ecn]; });
                var sel = {}, myOnly = false;
                function vis() { if (!myOnly) return avail; var me = lc((STATE.currentUser && STATE.currentUser.loginId) || ''); return avail.filter(function (c) { return lc(c.itOwnerLoginId) === me; }); }
                function render() {
                    var v = vis();
                    var list = v.length ? v.map(function (c) {
                        return '<label style="display:block; font-size:12px; padding:3px 4px;"><input type="checkbox" data-ecn="' + escapeAttr(c.ecn) + '"' + (sel[c.ecn] ? ' checked' : '') + ' style="margin-right:6px;"><b>' + escapeHtml(c.ecn) + '</b> <span style="color:#6B7280;">' + escapeHtml(c.itOwner || '') + '</span></label>';
                    }).join('') : '<div style="color:#9CA3AF; font-size:12px; padding:4px;">' + (myOnly ? 'none owned by you' : 'no more eligible ECNs') + '</div>';
                    host.innerHTML = '<div style="border:1px solid #E8E6DF; border-radius:6px; padding:8px; margin:6px 0;">' +
                        '<label style="font-size:11px; color:#6B7280;"><input type="checkbox" id="reAeMy"' + (myOnly ? ' checked' : '') + ' style="vertical-align:middle;"> My ECNs only</label>' +
                        '<div style="max-height:22vh; overflow-y:auto; margin:4px 0;">' + list + '</div>' +
                        '<div id="reAeMsg" style="color:#B8342B; font-size:11px; min-height:12px;"></div>' +
                        '<div style="text-align:right;"><button id="reAeCancel" style="background:transparent; border:1px solid #E8E6DF; padding:4px 10px; border-radius:4px; font-size:12px; cursor:pointer; margin-right:6px;">Cancel</button>' +
                        '<button id="reAeAdd" style="background:#4a6fa5; color:#fff; border:1px solid #4a6fa5; padding:4px 12px; border-radius:4px; font-size:12px; cursor:pointer;">Add</button></div></div>';
                    Array.prototype.forEach.call(host.querySelectorAll('input[data-ecn]'), function (cb) {
                        cb.addEventListener('change', function () { var e = cb.getAttribute('data-ecn'); if (cb.checked) sel[e] = true; else delete sel[e]; });
                    });
                    host.querySelector('#reAeMy').addEventListener('change', function () { myOnly = this.checked; render(); });
                    host.querySelector('#reAeCancel').addEventListener('click', function () { host.innerHTML = ''; host.removeAttribute('data-open'); });
                    host.querySelector('#reAeAdd').addEventListener('click', function () {
                        var ecns = Object.keys(sel).filter(function (k) { return sel[k]; });
                        if (!ecns.length) { host.querySelector('#reAeMsg').textContent = 'Select at least one ECN.'; return; }
                        submitAddEcns(rec.ecn, ecns);
                    });
                }
                render();
            })
            .catch(function () { host.innerHTML = '<div style="color:#B8342B; font-size:12px;">Could not load candidates.</div>'; });
    }
    function submitAddEcns(ecn, ecns) {
        showToast({ kind: 'pending', text: 'Adding ' + ecns.length + ' ECN(s)…' });
        fetch('/api/it-enhancements/restart-ecn/' + encodeURIComponent(ecn) + '/add-ecns', {
            method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ecns: ecns })
        })
        .then(function (r) { return r.json(); })
        .then(function (res) {
            if (res && res.needsAgileSignin) { window.iteOpenAgileSignin(function (ok) { if (ok) submitAddEcns(ecn, ecns); }); return; }
            if (!res || !res.ok) { showToast({ kind: 'error', text: (res && res.error) || 'Add failed.' }); return; }
            var n = (res.relatedOk || []).length, bad = (res.relatedFailed || []).length;
            showToast({ kind: bad ? 'warn' : 'success', text: 'Added ' + n + ' ECN(s)' + (bad ? ', ' + bad + ' failed' : '') + ' to ' + ecn });
            showRestartDetail(ecn);
        })
        .catch(function () { showToast({ kind: 'error', text: 'Add failed (network).' }); });
    }

    // ---- §4d: inline "Add files" in the drawer detail (options = this ECN's bundled ECNs) ----
    function openAddFilesForm(rec) {
        var host = document.getElementById('reAddFilesForm'); if (!host) return;
        if (host.getAttribute('data-open') === '1') { host.innerHTML = ''; host.removeAttribute('data-open'); return; }
        host.setAttribute('data-open', '1');
        host.innerHTML = '<div style="color:#6B7280; font-size:12px; padding:6px 0;">Loading…</div>';
        fetch('/api/it-enhancements/restart-ecn-candidates', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                var attachTypes = (d && d.attachmentTypes) || {};
                var maxMb = (d && d.attachmentMaxMb) || 25;
                var bundledEcns = (rec.bundled || []).map(function (b) { return b.ecn; });
                var files = [], seq = 0, skipMsg = '';
                function extOf(n) { var m = /\.([^.\/\\]+)$/.exec(n || ''); return m ? m[1].toLowerCase() : ''; }
                function typesFor(n) { return attachTypes[extOf(n)] || []; }
                function valid(f) {
                    var refs = Object.keys(f.ecns).filter(function (e) { return f.ecns[e]; });
                    if (!refs.length) return false;
                    if (typesFor(f.file.name).length && !f.type) return false;
                    return true;
                }
                // Collapsible rows here too — one expanded at a time. null = auto
                // (first incomplete); '' = all collapsed. No note input in the drawer.
                var openFileId = null;
                function afIncomplete(f) { return !valid(f); }
                function afAutoOpenId() {
                    for (var i = 0; i < files.length; i++) { if (afIncomplete(files[i])) return files[i].id; }
                    return null;
                }
                function render() {
                    var openId = (openFileId === null) ? afAutoOpenId() : openFileId;
                    var rows = files.map(function (f) {
                        var opts = typesFor(f.file.name);
                        var refs = Object.keys(f.ecns).filter(function (e) { return f.ecns[e]; });
                        var tagged = refs.length > 0;
                        var needsType = opts.length > 0 && !f.type;
                        var complete = tagged && !needsType;
                        var open = (openId !== null && openId !== '' && String(f.id) === String(openId));
                        var summary = tagged
                            ? escapeHtml(refs.join(', ')) + (needsType ? ' &middot; needs type' : (f.type ? ' &middot; ' + escapeHtml(f.type) : ''))
                            : 'needs ECN #';
                        var sumColor = complete ? '#6B7280' : '#B8342B';
                        var hdr = '<div class="reAfHdr" data-fid="' + f.id + '" style="display:flex; align-items:center; gap:7px; cursor:pointer; min-width:0;">' +
                                '<span style="color:#9CA3AF; font-size:10px; width:10px; flex:0 0 auto;">' + (open ? '&#9662;' : '&#9656;') + '</span>' +
                                '<span style="color:#9CA3AF; flex:0 0 auto;">&#128206;</span>' +
                                '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; min-width:0; flex:1 1 auto;" title="' + escapeAttr(f.file.name) + '">' + escapeHtml(f.file.name) + '</span>' +
                                '<span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; color:' + sumColor + '; flex:0 1 auto; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:right;">' + summary + '</span>' +
                                '<span data-fid="' + f.id + '" class="reAfRm" title="remove" style="cursor:pointer; color:#9CA3AF; font-size:16px; font-weight:700; padding-left:4px; flex:0 0 auto;">&times;</span>' +
                            '</div>';
                        if (!open) {
                            return '<div style="border:1px solid ' + (complete ? '#E8E6DF' : '#E4A0A0') + '; border-radius:8px; padding:7px 10px; margin-bottom:6px;">' + hdr + '</div>';
                        }
                        var chips = bundledEcns.map(function (e) {
                            var on = !!f.ecns[e];
                            return '<span data-fid="' + f.id + '" data-ecn="' + escapeAttr(e) + '" class="reAfChip" style="display:inline-block; cursor:pointer; font-size:11px; padding:1px 7px; margin:2px 3px 0 0; border-radius:999px; border:1px solid ' + (on ? '#4a6fa5' : '#E8E6DF') + '; background:' + (on ? '#e8f0fe' : '#fff') + '; color:' + (on ? '#1a3a5c' : '#6B7280') + ';">' + (on ? '✓ ' : '') + escapeHtml(e) + '</span>';
                        }).join('');
                        var applyAll = (tagged && files.length > 1)
                            ? '<button type="button" class="reAfApply" data-fid="' + f.id + '" title="Copy this file\'s ECN references onto every pending file" style="border:0; background:transparent; color:#4a6fa5; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; cursor:pointer; padding:0 2px; margin-left:auto; white-space:nowrap;">apply to all files</button>'
                            : '';
                        var typeSel = opts.length ? ('<select data-fid="' + f.id + '" class="reAfType" style="width:100%; box-sizing:border-box; font-size:12px; padding:6px 8px; border:1px solid ' + (f.type ? '#E8E6DF' : '#4a6fa5') + '; border-radius:6px;"><option value="">— IT attachment type —</option>' + opts.map(function (o) { return '<option value="' + escapeAttr(o) + '"' + (f.type === o ? ' selected' : '') + '>' + escapeHtml(o) + '</option>'; }).join('') + '</select>') : '';
                        return '<div style="border:1px solid ' + (complete ? '#E8E6DF' : '#E4A0A0') + '; border-radius:8px; padding:10px 12px; margin-bottom:8px;">' + hdr +
                            '<div style="display:flex; align-items:center; flex-wrap:wrap; margin-top:8px;"><span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; text-transform:uppercase; letter-spacing:1px; color:#9CA3AF; margin-right:6px;">for ecn #</span>' + (chips || '<span style="color:#C7801B; font-size:11px;">no bundled ECNs</span>') + applyAll + '</div>' +
                            (typeSel ? '<div style="margin-top:8px;">' + typeSel + '</div>' : '') +
                        '</div>';
                    }).join('');
                    var needFix = files.some(function (f) { return !valid(f); });
                    host.innerHTML = '<div style="border:1px solid #E8E6DF; border-radius:6px; padding:8px; margin:6px 0;">' +
                        '<input type="file" id="reAfInput" multiple style="font-size:12px;">' +
                        (skipMsg ? '<div role="alert" style="margin:6px 0; padding:8px 10px; border-left:3px solid #B8342B; background:#fdeaea; color:#B8342B; font-size:12px; border-radius:0 6px 6px 0;">' + skipMsg + '</div>' : '') +
                        '<div style="margin-top:6px;">' + rows + '</div>' +
                        (files.length ? '<div style="font-size:11px; color:#6B7280; margin:2px 0 6px;">New files start tagged with every bundled ECN &mdash; untoggle any that don\'t apply.</div>' : '') +
                        '<div id="reAfMsg" style="color:#B8342B; font-size:11px; min-height:12px;">' + (files.length && needFix ? 'Each file needs ≥1 ECN and a type when required.' : '') + '</div>' +
                        '<div style="text-align:right;"><button id="reAfCancel" style="background:transparent; border:1px solid #E8E6DF; padding:4px 10px; border-radius:4px; font-size:12px; cursor:pointer; margin-right:6px;">Cancel</button>' +
                        '<button id="reAfAdd" style="background:#4a6fa5; color:#fff; border:1px solid #4a6fa5; padding:4px 12px; border-radius:4px; font-size:12px; cursor:pointer;"' + (files.length && !needFix ? '' : ' disabled') + '>Upload</button></div></div>';
                    host.querySelector('#reAfInput').addEventListener('change', function () {
                        var skipped = [];
                        for (var i = 0; i < this.files.length; i++) {
                            var fl = this.files[i];
                            if (fl.size > maxMb * 1048576) { skipped.push(fl.name + ' (' + (fl.size / 1048576).toFixed(1) + ' MB)'); continue; }
                            // Pre-tag with every bundled ECN — untoggle what doesn't apply.
                            var seed = {}; bundledEcns.forEach(function (e) { seed[e] = true; });
                            files.push({ id: ++seq, file: fl, ecns: seed, type: '', note: '' });
                        }
                        skipMsg = skipped.length ? ('<b>' + skipped.length + ' file' + (skipped.length > 1 ? 's' : '') + ' skipped &mdash; over the ' + maxMb + '&nbsp;MB limit:</b><br>' + skipped.map(function (s) { return escapeHtml(s); }).join('<br>')) : '';
                        openFileId = null;   // back to auto
                        render();
                    });
                    Array.prototype.forEach.call(host.querySelectorAll('.reAfHdr'), function (h) {
                        h.addEventListener('click', function () {
                            var id = h.getAttribute('data-fid');
                            openFileId = (openId !== null && openId !== '' && String(openId) === String(id)) ? '' : id;
                            render();
                        });
                    });
                    Array.prototype.forEach.call(host.querySelectorAll('.reAfChip'), function (ch) {
                        ch.addEventListener('click', function (ev) { ev.stopPropagation(); var f = files.filter(function (x) { return x.id == ch.getAttribute('data-fid'); })[0]; if (!f) return; var e = ch.getAttribute('data-ecn'); if (f.ecns[e]) delete f.ecns[e]; else f.ecns[e] = true; render(); });
                    });
                    Array.prototype.forEach.call(host.querySelectorAll('.reAfApply'), function (b) {
                        b.addEventListener('click', function (ev) {
                            ev.stopPropagation();
                            var f = files.filter(function (x) { return x.id == b.getAttribute('data-fid'); })[0]; if (!f) return;
                            var src = Object.keys(f.ecns).filter(function (e) { return f.ecns[e]; });
                            files.forEach(function (x) { var next = {}; src.forEach(function (e) { next[e] = true; }); x.ecns = next; });
                            render();
                        });
                    });
                    Array.prototype.forEach.call(host.querySelectorAll('.reAfType'), function (s) {
                        s.addEventListener('click', function (ev) { ev.stopPropagation(); });
                        s.addEventListener('change', function () { var f = files.filter(function (x) { return x.id == s.getAttribute('data-fid'); })[0]; if (f) { f.type = s.value; render(); } });
                    });
                    Array.prototype.forEach.call(host.querySelectorAll('.reAfRm'), function (x) {
                        x.addEventListener('click', function (ev) { ev.stopPropagation(); files = files.filter(function (y) { return y.id != x.getAttribute('data-fid'); }); render(); });
                    });
                    host.querySelector('#reAfCancel').addEventListener('click', function () { host.innerHTML = ''; host.removeAttribute('data-open'); });
                    host.querySelector('#reAfAdd').addEventListener('click', function () {
                        var payloadFiles = files.map(function (f) { return { file: f.file, ecns: f.ecns, type: f.type, note: f.note || '' }; });
                        showToast({ kind: 'pending', text: 'Uploading ' + payloadFiles.length + ' file(s)…' });
                        uploadRestartFiles(rec.ecn, payloadFiles, function (fres) {
                            if (fres && fres.needsAgileSignin) { window.iteOpenAgileSignin(function (ok) { if (ok) host.querySelector('#reAfAdd').click(); }); return; }
                            var okN = fres ? (fres.filesOk || []).length : 0, badN = fres ? (fres.filesFailed || []).length : 0;
                            showToast({ kind: badN ? 'warn' : 'success', text: okN + ' file(s) added' + (badN ? ', ' + badN + ' failed' : '') + ' to ' + rec.ecn });
                            showRestartDetail(rec.ecn);
                        });
                    });
                }
                render();
            })
            .catch(function () { host.innerHTML = '<div style="color:#B8342B; font-size:12px;">Could not load.</div>'; });
    }

    // Write the create outcome into the dialog itself (id="reResult"), so it's
    // seen right where the user clicked Create — not only as a background toast.
    function reSetResult(text, tone) {                    // tone: 'pending'|'error'|'warn'|'success'
        var el = document.getElementById('reResult');
        if (!el) return;
        var color = tone === 'error' ? '#B8342B'
                  : tone === 'warn'  ? '#C7801B'
                  : tone === 'success' ? '#1F8A4C' : '#6B7280';
        var mark = tone === 'error' ? '✕ ' : tone === 'warn' ? '⚠ ' : tone === 'success' ? '✓ ' : '';
        el.style.color = color;
        el.style.fontWeight = (tone === 'success' || tone === 'error') ? '600' : '400';
        el.textContent = text ? (mark + text) : '';
    }
    function reEnableCreate(btn) {
        if (btn) { btn.disabled = false; btn.style.opacity = '1'; }
    }

    // Live create-progress step dots. steps: [{key,label,status,note}]
    // status: pending | running | ok | warn | fail
    function reRenderSteps(steps) {
        var el = document.getElementById('reSteps'); if (!el) return;
        if (!steps || !steps.length) { el.innerHTML = ''; return; }
        el.innerHTML = steps.map(function (s) {
            var color = s.status === 'ok' ? '#1F8A4C' : s.status === 'warn' ? '#C7801B'
                      : s.status === 'fail' ? '#B8342B' : s.status === 'running' ? '#4a6fa5' : '#C9D2DE';
            var mark = s.status === 'ok' ? '✓' : s.status === 'warn' ? '⚠' : s.status === 'fail' ? '✕'
                     : s.status === 'running' ? '<span class="spinner" style="display:inline-block; vertical-align:middle;"></span>' : '○';
            return '<div style="display:flex; align-items:center; font-size:12px; color:#0F1720; padding:2px 0;">' +
                '<span style="display:inline-block; width:16px; text-align:center; color:' + color + '; font-weight:700; margin-right:8px;">' + mark + '</span>' +
                '<span>' + escapeHtml(s.label) + (s.note ? ' <span style="color:#6B7280;">— ' + escapeHtml(s.note) + '</span>' : '') + '</span></div>';
        }).join('');
    }

    // Expandable "Details ▾" of per-item failures with next-step guidance.
    function reBuildDetails(res, fres) {
        var el = document.getElementById('reDetails'); if (!el) return;
        var items = [];
        (res.relatedFailed || []).forEach(function (r) {
            items.push('Relationship ' + (r.ecn || '') + ' failed: ' + (r.reason || '') + ' — add it later from Restart history → Add ECNs, or link it manually in Agile.');
        });
        (res.droppedEcns || []).forEach(function (e) {
            items.push(e + ' was dropped (no longer at "UAT complete, CAB Prep").');
        });
        if (fres) (fres.filesFailed || []).forEach(function (f) {
            items.push('File ' + (f.name || '') + ' failed: ' + (f.reason || '') + ' — re-add it from Restart history → Add files.');
        });
        if (!items.length) { el.innerHTML = ''; return; }
        el.innerHTML = '<span id="reDetailsToggle" style="cursor:pointer; color:#4a6fa5; font-size:12px;">Details ▾</span>' +
            '<div id="reDetailsBody" style="display:none; margin-top:4px; border-left:3px solid #E8E6DF; padding-left:8px;">' +
            items.map(function (t) { return '<div style="font-size:11.5px; color:#0F1720; padding:2px 0;">• ' + escapeHtml(t) + '</div>'; }).join('') + '</div>';
        var tg = document.getElementById('reDetailsToggle'), bd = document.getElementById('reDetailsBody');
        tg.addEventListener('click', function () {
            var open = bd.style.display !== 'none';
            bd.style.display = open ? 'none' : 'block';
            tg.textContent = open ? 'Details ▾' : 'Details ▴';
        });
    }

    function submitRestartEcn(payload, btn) {
        if (btn) { btn.disabled = true; btn.style.opacity = '0.5'; }
        var files = (payload && payload._files) || [];
        var nEcns = (payload.relatedEcns || []).length;
        var steps = [
            { key: 'create', label: 'Create ECN', status: 'running' },
            { key: 'rel', label: 'Relationships (' + nEcns + ' ECN' + (nEcns === 1 ? '' : 's') + ')', status: 'pending' }
        ];
        if (files.length) steps.push({ key: 'att', label: 'Attachments (' + files.length + ' file' + (files.length === 1 ? '' : 's') + ')', status: 'pending' });
        reSetResult('', 'pending');
        reRenderSteps(steps);
        showToast({ kind: 'pending', text: 'Creating Restart ECN…' });
        fetch('/api/it-enhancements/create-restart-ecn', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(function (r) { return r.json(); })
        .then(function (res) {
            if (res && res.needsAgileSignin) {
                // Prompt for Agile creds via the shared sign-in modal, then retry once.
                reRenderSteps([]); reSetResult('', 'pending');
                window.iteOpenAgileSignin(function (ok) {
                    if (ok) submitRestartEcn(payload, btn);
                    else { showToast(null); reSetResult('', 'pending'); reEnableCreate(btn); }
                });
                return;
            }
            if (!res || !res.ok) {
                var em = (res && res.error) ? res.error : 'Create failed.';
                steps[0].status = 'fail'; steps[0].note = em; reRenderSteps(steps);
                reSetResult(em, 'error');
                showToast({ kind: 'error', text: em });
                reEnableCreate(btn);
                return;
            }
            // ECN created (cover page + relationships happen server-side in this call).
            steps[0].status = 'ok'; steps[0].note = res.ecnNumber;
            var relOk = (res.relatedOk || []).length, relBad = (res.relatedFailed || []).length;
            steps[1].status = relBad ? 'warn' : 'ok';
            steps[1].note = relOk + ' linked' + (relBad ? ', ' + relBad + ' failed' : '');
            reRenderSteps(steps);
            if (files.length) {
                var att = steps[2]; att.status = 'running'; reRenderSteps(steps);
                uploadRestartFiles(res.ecnNumber, files, function (fres) { finishRestartResult(res, fres, steps); });
            } else {
                finishRestartResult(res, null, steps);
            }
        })
        .catch(function () {
            steps[0].status = 'fail'; steps[0].note = 'network'; reRenderSteps(steps);
            reSetResult('Create failed (network).', 'error');
            showToast({ kind: 'error', text: 'Create failed (network).' });
            reEnableCreate(btn);
        });
    }

    // Upload the dialog's files to the created Restart ECN (multipart). Each file's
    // metadata (ECN refs, IT type, note) is sent index-aligned in a JSON "meta" part.
    function uploadRestartFiles(ecn, files, cb) {
        var fd = new FormData();
        var meta = [];
        files.forEach(function (f) {
            fd.append('files', f.file, f.file.name);
            meta.push({ ecns: Object.keys(f.ecns).filter(function (e) { return f.ecns[e]; }), type: f.type || '', note: f.note || '' });
        });
        fd.append('meta', JSON.stringify(meta));
        fetch('/api/it-enhancements/restart-ecn/' + encodeURIComponent(ecn) + '/add-files', {
            method: 'POST', credentials: 'same-origin', body: fd
        })
        .then(function (r) { return r.json(); })
        .then(cb)
        .catch(function () { cb({ ok: false, filesFailed: files.map(function (f) { return { name: f.file.name, reason: 'network' }; }) }); });
    }

    // Render the final create result (with optional file-upload result) in the dialog.
    function finishRestartResult(res, fres, steps) {
        var relOk = (res.relatedOk || []).length;
        var relBad = (res.relatedFailed || []).length;
        var dropped = (res.droppedEcns || []).length;
        var filesOk = fres ? (fres.filesOk || []).length : 0;
        var filesBad = fres ? (fres.filesFailed || []).length : 0;
        // Finalize the attachments step dot.
        if (steps) {
            var att = steps.filter(function (s) { return s.key === 'att'; })[0];
            if (att) { att.status = filesBad ? 'warn' : 'ok'; att.note = filesOk + ' uploaded' + (filesBad ? ', ' + filesBad + ' failed' : ''); }
            reRenderSteps(steps);
        }
        reBuildDetails(res, fres);
        // Left in Pending on purpose — the team keeps adding before submitting in Agile.
        var msg = 'Created ' + res.ecnNumber + ' — related ' + relOk;
        if (fres) msg += ' · ' + filesOk + ' file(s) attached';
        msg += ' · in Pending — submit in Agile when the bundle is final';
        var warn = relBad || dropped || filesBad;
        if (relBad) msg += '; ' + relBad + ' relationship(s) failed';
        if (filesBad) msg += '; ' + filesBad + ' file(s) failed';
        if (dropped) msg += '; ' + dropped + ' dropped (no longer eligible)';
        reSetResult(msg, warn ? 'warn' : 'success');
        showToast({ kind: warn ? 'warn' : 'success', text: msg });
        // Turn the primary button into "View Restart ECN →" (opens the history drawer
        // detail for this ECN inside the toolkit). Clone to drop the old submit listener.
        var createBtn = document.getElementById('reCreate');
        if (createBtn) {
            var view = createBtn.cloneNode(true);
            view.textContent = 'View Restart ECN →';
            view.style.display = ''; view.disabled = false; view.style.opacity = '1';
            createBtn.parentNode.replaceChild(view, createBtn);
            var viewEcn = res.ecnNumber;
            view.addEventListener('click', function () { closeRestartEcnDialog(); openRestartHistory(viewEcn); });
        }
        var cancelBtn = document.getElementById('reCancel');
        if (cancelBtn) { cancelBtn.textContent = 'Close'; cancelBtn.style.borderColor = '#4a6fa5'; cancelBtn.style.color = '#4a6fa5'; }
    }

    // ============================================================
    // Dirty badge / Save
    // ============================================================

    function refreshDirtyBadge() {
        var n = 0;
        Object.keys(STATE.dirty).forEach(function (k) {
            var s = STATE.dirty[k].status;
            if (s === 'pending' || s === 'error') n++;
        });
        var el = document.getElementById('iteDirtyN');
        if (el) el.textContent = n;
        var btn = document.getElementById('iteSaveAllBtn');
        if (btn) {
            btn.disabled = (n === 0);
            btn.style.opacity = (n === 0) ? '0.5' : '1';
        }
    }

    function iteSaveAll() {
        var edits = [];
        Object.keys(STATE.dirty).forEach(function (k) {
            var d = STATE.dirty[k];
            if (d.status !== 'pending') return;
            edits.push({ ecn: d.ecn, cellName: d.cell, value: wireValue(d.cell, d.value) });
        });
        if (!edits.length) {
            var errN = Object.keys(STATE.dirty).filter(function (k) { return STATE.dirty[k].status === 'error'; }).length;
            if (errN) showToast({ kind: 'warn', text: errN + ' cell(s) need attention before saving — see the red dots.' });
            return;
        }
        // Optimistically mark every pending edit as saving so the dots pulse
        // blue immediately. If save-batch comes back with a 401-like
        // needsAgileSignin, we revert to pending in the retry path.
        edits.forEach(function (e) {
            var d = STATE.dirty[ckey(e.ecn, e.cellName)];
            if (d) d.status = 'saving';
        });
        render();
        runSaveBatch(edits, /* afterSignin */ false);
    }

    function runSaveBatch(edits, afterSignin) {
        showToast({ kind: 'pending', text: 'Saving ' + edits.length + ' change(s) to Agile…' });
        var btn = document.getElementById('iteSaveAllBtn');
        if (btn) btn.disabled = true;

        fetch('/api/it-enhancements/save-batch', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ edits: edits })
        })
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                if (resp.needsAgileSignin) {
                    if (afterSignin) {
                        showToast({ kind: 'error', text: 'Save still failing after sign-in. Try again or check the agile-service log.' });
                        // Revert saving → pending so user can retry.
                        edits.forEach(function (e) {
                            var d = STATE.dirty[ckey(e.ecn, e.cellName)];
                            if (d && d.status === 'saving') d.status = 'pending';
                        });
                        render();
                        return;
                    }
                    // Revert saving → pending so the modal feedback is honest
                    // about the state of the data.
                    edits.forEach(function (e) {
                        var d = STATE.dirty[ckey(e.ecn, e.cellName)];
                        if (d && d.status === 'saving') d.status = 'pending';
                    });
                    render();
                    openAgileSigninModal(edits);
                    return;
                }
                var results = resp.results || [];
                var failures = [];
                // Track the FIRST successful save's (ecn, cellName) so we can
                // restore active selection there post-render. Improves the
                // "saved 1 — now where did the row go?" UX after an edit.
                var firstSaved = null;
                // Per-ECN diff capture for the notification path. Old value
                // MUST be read before row[field] gets overwritten below.
                var notifyByEcn = {};
                results.forEach(function (res) {
                    var k = ckey(res.ecn, res.cellName);
                    if (res.ok) {
                        var d = STATE.dirty[k];
                        if (d) d.status = 'saved';
                        var row = pickRow(res.ecn);
                        var field = CELL_TO_FIELD[res.cellName];
                        var oldVal = (row && field) ? (row[field] || '') : '';
                        var newVal = res.newValue != null ? res.newValue : (d ? d.value : '');
                        if (row && field) row[field] = newVal || '';
                        if (firstSaved == null) firstSaved = { ecn: res.ecn, cellName: res.cellName };
                        // Stash the diff for the per-ECN notification email.
                        if (!notifyByEcn[res.ecn]) notifyByEcn[res.ecn] = { row: row, changes: [] };
                        var col = COLUMNS.filter(function (c) { return c.cell === res.cellName; })[0];
                        // Skip IT Log — its content goes in the email's note
                        // block, not the field-diff table.
                        if (col && col.key !== 'itLog') {
                            notifyByEcn[res.ecn].changes.push({
                                field: col.label,
                                old: oldVal,
                                new: newVal || ''
                            });
                        }
                    } else {
                        var de = STATE.dirty[k];
                        if (de) { de.status = 'error'; de.msg = res.error || 'unknown'; }
                        failures.push({ ecn: res.ecn, cell: res.cellName, err: res.error || 'unknown' });
                    }
                });
                render();
                // Recompute filter dropdowns + suggestions in case an owner/project
                // changed.
                recomputeOpts();
                buildFilterDropdowns(STATE.rows);

                // Restore selection / scroll to the saved row+cell so the
                // user sees what they just saved instead of staring at a
                // filter-empty grid.
                var hiddenSaved = false;
                if (firstSaved) {
                    var savedRi = (STATE.filtered || []).findIndex(function (r) { return r.ecnNumber === firstSaved.ecn; });
                    var savedCi = -1;
                    for (var i = 0; i < COLUMNS.length; i++) {
                        if (COLUMNS[i].cell === firstSaved.cellName) { savedCi = i; break; }
                    }
                    if (savedRi >= 0 && savedCi >= 0) {
                        STATE.sel.ri = savedRi;
                        STATE.sel.ci = savedCi;
                        STATE.sel.r2 = savedRi;
                        render();
                        ensureActiveVisible();
                    } else if (savedCi >= 0) {
                        // Row exists in STATE.rows but the current filter
                        // hides it. Note this so the toast message tells the
                        // user (and includes a one-click "show it" action).
                        hiddenSaved = true;
                    }
                }

                if (failures.length === 0) {
                    if (hiddenSaved) {
                        showToast({
                            kind: 'success',
                            text: 'Saved ' + resp.savedCount + ' change(s) — the row is hidden by your current filter.',
                            action: { label: 'Show it', onClick: function () { clearAllFilters(firstSaved); } }
                        });
                    } else {
                        showToast({ kind: 'success', text: 'Saved ' + resp.savedCount + ' change(s) under your Agile identity.' });
                    }
                } else if (resp.savedCount > 0) {
                    showToast({ kind: 'warn', text: 'Saved ' + resp.savedCount + ' · ' + failures.length +
                        ' failed (' + failures[0].ecn + ': ' + truncate(failures[0].err, 120) + ')' });
                } else {
                    showToast({ kind: 'error', text: 'No changes saved — ' + truncate(failures[0].err, 200) });
                }
                // Fire per-ECN notification emails — only from Meeting Mode
                // (the explicit "I'm doing a review and want emails sent"
                // context). All / My grid edits stay silent so a casual
                // single-cell tweak doesn't blast emails to the requestor
                // and owner unintentionally.
                if (STATE.subTab === 'meeting') {
                    triggerMeetingNotifications(notifyByEcn, failures);
                    // Refresh the wrap-up screen so the stale "X pending
                    // edits" + per-recipient cards reflect the post-save
                    // state immediately. Without this, the user sees the
                    // same screen as before the click and (rightly) thinks
                    // nothing happened.
                    STATE.meeting.scorecard = meetingComputeScorecard();
                    renderMeetingMode();
                }
                // Fade out 'saved' entries after 2.2s (matches prototype).
                setTimeout(function () {
                    var any = false;
                    Object.keys(STATE.dirty).forEach(function (k) {
                        if (STATE.dirty[k].status === 'saved') { delete STATE.dirty[k]; any = true; }
                    });
                    if (any) {
                        refreshDirtyBadge();
                        render();
                        // Repaint the wrap-up so the consolidation cards
                        // for now-saved ECNs disappear and the counters
                        // settle on the final post-save state.
                        if (STATE.subTab === 'meeting') renderMeetingMode();
                    }
                }, 2200);
            })
            .catch(function (e) {
                // Network / parse failure — flip everything back to pending so the
                // user can retry without losing their edits.
                edits.forEach(function (ed) {
                    var d = STATE.dirty[ckey(ed.ecn, ed.cellName)];
                    if (d && d.status === 'saving') d.status = 'pending';
                });
                render();
                showToast({ kind: 'error', text: 'Save failed: ' + e.message });
            })
            .finally(function () {
                refreshDirtyBadge();
            });
    }

    /** Fire per-ECN notification emails after a Meeting Mode save batch.
     *  Each ECN with at least one successful save (or a captured note) gets
     *  one email to requestor + IT owner with admin on cc. Failed edits get
     *  rolled into a single admin-only failure summary. Best-effort — never
     *  blocks the UI on a notification failure. */
    function triggerMeetingNotifications(notifyByEcn, failures) {
        // Pull in captured notes for every ECN that was touched, even if
        // the only thing that "changed" was the IT Log (note).
        Object.keys(STATE.meeting.notes || {}).forEach(function (ecn) {
            var note = (STATE.meeting.notes[ecn] || '').trim();
            if (!note) return;
            if (!notifyByEcn[ecn]) {
                var row = pickRow(ecn);
                if (!row) return;
                notifyByEcn[ecn] = { row: row, changes: [] };
            }
            notifyByEcn[ecn].note = note;
        });
        var ecns = Object.keys(notifyByEcn);
        if (ecns.length === 0 && (!failures || failures.length === 0)) return;

        var edits = ecns.map(function (ecn) {
            var d = notifyByEcn[ecn];
            var row = d.row || {};
            return {
                ecn: ecn,
                requestorLoginId: row.requestorLoginId || '',
                requestorName: row.requestor || '',
                itOwnerLoginId: row.itOwnerLoginId || '',
                itOwnerName: row.itOwner || '',
                // Include the proposal so the email shows enough context for
                // the recipient to recognize the ECN without clicking.
                proposal: row.proposal || '',
                changes: d.changes || [],
                note: d.note || ''
            };
        });
        var failedList = (failures || []).map(function (f) {
            return { ecn: f.ecn, cell: f.cell, error: f.err };
        });
        showToast({ kind: 'pending', text: 'Sending ' + ecns.length + ' notification email' + (ecns.length === 1 ? '' : 's') + '…' });
        fetch('/api/it-enhancements/notify-changes', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                edits: edits,
                failed: failedList,
                userDisplayName: STATE.currentUser.displayName || ''
            })
        })
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                // Response is now per-RECIPIENT: each results[i] is one
                // person who got a summary email listing N ECNs they're
                // involved with.
                if (resp.ok && resp.sent > 0) {
                    showToast({ kind: 'success', text: 'Sent ' + resp.sent + ' summary email' + (resp.sent === 1 ? '' : 's') + ' (one per recipient, cc admin).' });
                } else if (resp.sent > 0) {
                    showToast({ kind: 'warn', text: 'Sent ' + resp.sent + ' · ' + (resp.notifyErrors || []).length + ' failed — admin emailed.' });
                } else if ((resp.notifyErrors || []).length > 0) {
                    showToast({ kind: 'error', text: 'No notifications sent — ' + truncate(resp.notifyErrors[0], 120) + ' (admin emailed)' });
                }
                // Clear captured meeting notes for every ECN that landed in
                // at least one successful summary email (since the IT Log
                // already saved via /save-batch). Aggregate the ECN set
                // across all sent recipients.
                if (resp.ok && STATE.meeting && STATE.meeting.notes) {
                    var sentEcns = {};
                    (resp.results || []).forEach(function (rcp) {
                        if (rcp && rcp.sent && Array.isArray(rcp.ecns)) {
                            rcp.ecns.forEach(function (e) { sentEcns[e] = true; });
                        }
                    });
                    Object.keys(sentEcns).forEach(function (ecn) { delete STATE.meeting.notes[ecn]; });
                    renderSubNav();
                }
            })
            .catch(function (e) {
                showToast({ kind: 'error', text: 'Notification call failed: ' + (e && e.message ? e.message : 'network error') });
            });
    }

    function pickRow(ecn) {
        for (var i = 0; i < STATE.rows.length; i++) {
            if (STATE.rows[i].ecnNumber === ecn) return STATE.rows[i];
        }
        return null;
    }

    // ============================================================
    // Agile sign-in modal (per-user write-back gate)
    // ============================================================

    var PENDING_RETRY_EDITS = null;
    // Optional one-shot success/cancel callback used when the modal is opened
    // by another module (Meeting Mode v2) rather than the grid's save flow.
    var AGILE_SIGNIN_CB = null;

    function openAgileSigninModal(pendingEdits) {
        PENDING_RETRY_EDITS = pendingEdits || null;
        fetch('/api/auth/session', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (s) {
                var u = (s && s.username) || '';
                var el = document.getElementById('iteAgileSigninUsername');
                if (el) el.value = u;
            })
            .catch(function () {/* username will be blank — user can still type the password */});
        var modal = document.getElementById('iteAgileSigninModal');
        modal.style.display = 'flex';
        var pw = document.getElementById('iteAgileSigninPassword');
        if (pw) {
            pw.value = '';
            setTimeout(function () { pw.focus(); }, 50);
            pw.onkeydown = function (ev) {
                if (ev.key === 'Enter') { ev.preventDefault(); iteAgileSigninSubmit(); }
                else if (ev.key === 'Escape') { ev.preventDefault(); iteAgileSigninCancel(); }
            };
        }
        var err = document.getElementById('iteAgileSigninError');
        if (err) { err.style.display = 'none'; err.textContent = ''; }
        showToast(null);
    }

    function iteAgileSigninCancel() {
        var modal = document.getElementById('iteAgileSigninModal');
        if (modal) modal.style.display = 'none';
        PENDING_RETRY_EDITS = null;
        if (AGILE_SIGNIN_CB) { var cb = AGILE_SIGNIN_CB; AGILE_SIGNIN_CB = null; cb(false); return; }
        var btn = document.getElementById('iteSaveAllBtn');
        if (btn) btn.disabled = false;
        showToast({ kind: 'warn', text: 'Save cancelled — pending edits kept; click "Save all changes" when ready.' });
    }

    function iteAgileSigninSubmit() {
        var pw = document.getElementById('iteAgileSigninPassword');
        var errEl = document.getElementById('iteAgileSigninError');
        var submitBtn = document.getElementById('iteAgileSigninSubmitBtn');
        var pwd = pw ? pw.value : '';
        if (!pwd) {
            if (errEl) { errEl.textContent = 'Enter your Agile password to continue.'; errEl.style.display = ''; }
            return;
        }
        if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = 'Verifying…'; }
        if (errEl) { errEl.style.display = 'none'; }

        fetch('/api/it-enhancements/agile-signin', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password: pwd })
        })
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                if (resp.ok) {
                    var modal = document.getElementById('iteAgileSigninModal');
                    if (modal) modal.style.display = 'none';
                    if (pw) pw.value = '';
                    if (AGILE_SIGNIN_CB) { var cb = AGILE_SIGNIN_CB; AGILE_SIGNIN_CB = null; cb(true); return; }
                    if (PENDING_RETRY_EDITS && PENDING_RETRY_EDITS.length) {
                        var edits = PENDING_RETRY_EDITS;
                        PENDING_RETRY_EDITS = null;
                        // Re-mark as saving for the retry so the visual is in sync.
                        edits.forEach(function (e) {
                            var d = STATE.dirty[ckey(e.ecn, e.cellName)];
                            if (d) d.status = 'saving';
                        });
                        render();
                        runSaveBatch(edits, /* afterSignin */ true);
                    } else {
                        showToast({ kind: 'success', text: 'Signed in to Agile as ' + (resp.username || 'you') + '.' });
                    }
                } else {
                    if (errEl) {
                        errEl.textContent = resp.error || 'Verify failed.';
                        errEl.style.display = '';
                    }
                }
            })
            .catch(function (e) {
                if (errEl) {
                    errEl.textContent = 'Verify failed: ' + e.message;
                    errEl.style.display = '';
                }
            })
            .finally(function () {
                if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = 'Sign in & save'; }
            });
    }

    // ============================================================
    // Toast / loading helpers
    // ============================================================

    function showToast(t) {
        var el = document.getElementById('iteToast');
        if (!el) return;
        if (!t) { el.style.display = 'none'; el.innerHTML = ''; return; }
        var bg, color, border;
        if (t.kind === 'success') { bg = '#e8f5e9'; color = '#155724'; border = '#1F8A4C'; }
        else if (t.kind === 'error') { bg = '#fdeaea'; color = '#721c24'; border = '#B8342B'; }
        else if (t.kind === 'warn') { bg = '#fff8e1'; color = '#856404'; border = '#C7801B'; }
        else { bg = '#e8f0fe'; color = '#1a3a5c'; border = '#4a6fa5'; }
        el.style.display = '';
        el.style.background = bg;
        el.style.color = color;
        el.style.borderLeft = '4px solid ' + border;
        var spinner = (t.kind === 'pending' ? '<span class="spinner" style="display:inline-block; vertical-align:middle; margin-right:8px;"></span>' : '');
        var actionHtml = '';
        if (t.action && t.action.label && typeof t.action.onClick === 'function') {
            actionHtml = ' <button type="button" id="iteToastAction" style="margin-left:12px; padding:3px 10px; font-size:12px; background:transparent; border:1px solid ' + border + '; border-radius:14px; color:' + color + '; cursor:pointer; font-weight:600;">' +
                escapeHtml(t.action.label) + '</button>';
        }
        el.innerHTML = spinner + escapeHtml(t.text) + actionHtml;
        if (actionHtml) {
            var btn = el.querySelector('#iteToastAction');
            if (btn) btn.addEventListener('click', function () {
                try { t.action.onClick(); } finally { showToast(null); }
            });
        }
    }

    function showLoading(on) {
        var l = document.getElementById('iteLoading');
        var grid = document.getElementById('iteGrid');
        if (l) l.style.display = on ? '' : 'none';
        if (grid) grid.style.display = on ? 'none' : '';
    }

    // ============================================================
    // Utilities
    // ============================================================

    function uniqueSorted(arr) {
        var seen = {};
        var out = [];
        arr.forEach(function (v) {
            if (v == null || v === '') return;
            if (seen[v]) return;
            seen[v] = true;
            out.push(v);
        });
        out.sort();
        return out;
    }

    function debounce(fn, wait) {
        var t;
        return function () {
            var ctx = this, args = arguments;
            clearTimeout(t);
            t = setTimeout(function () { fn.apply(ctx, args); }, wait);
        };
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function escapeAttr(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function truncate(s, n) {
        if (s == null) return '';
        var t = String(s);
        return t.length > n ? t.substring(0, n) + '…' : t;
    }

    // ============================================================
    // Send sign-off (manual digest trigger; admin-only)
    // ============================================================
    /** Open a small modal that lets an admin fire either Flow A (Go-Live, to
     *  requestor) or Flow B (Target UAT, to IT owner) for one loginid.
     *
     *  Defaults: flow=Flow A; loginId pre-filled from the currently-selected
     *  row's requestorLoginId (Flow A) or itOwnerLoginId (Flow B). Switching
     *  the radio re-picks the default from the same row.
     *
     *  Server-side, /api/golive-signoff/trigger is admin-gated (403s for
     *  non-admins) — the toolbar button is visible to everyone but pressing
     *  it without admin role just surfaces the 403 in the toast. */
    function iteSendSignoff() {
        var selRow = STATE.filtered[STATE.sel.ri] || STATE.rows[0] || {};
        var defaultFlow = 'GOLIVE';

        function pickDefault(flow, row) {
            if (flow === 'GOLIVE') return row.requestorLoginId || '';
            return row.itOwnerLoginId || '';
        }

        // Build a deduped list of everyone in the grid who can receive a
        // sign-off email — every requestor and every IT owner across all
        // STATE.rows. Sorted by display name. Role bitmap (R / I) is shown
        // as a muted suffix so the admin can tell at a glance whether the
        // person they're picking matches the active flow.
        function buildSignoffRecipients() {
            var seen = {};
            STATE.rows.forEach(function (r) {
                var rid = String(r.requestorLoginId || '').trim();
                if (rid) {
                    if (!seen[rid]) seen[rid] = { loginId: rid, name: (r.requestor || '').trim(), roles: {} };
                    seen[rid].roles.requestor = true;
                }
                var oid = String(r.itOwnerLoginId || '').trim();
                if (oid) {
                    if (!seen[oid]) seen[oid] = { loginId: oid, name: (r.itOwner || '').trim(), roles: {} };
                    seen[oid].roles.owner = true;
                }
            });
            var arr = Object.keys(seen).map(function (k) { return seen[k]; });
            arr.sort(function (a, b) {
                var an = a.name || a.loginId;
                var bn = b.name || b.loginId;
                return an.localeCompare(bn);
            });
            return arr;
        }
        var recipients = buildSignoffRecipients();

        var overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;inset:0;background:rgba(15,23,32,0.55);z-index:20000;'
            + "display:flex;align-items:center;justify-content:center;padding:20px;"
            + "font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;";
        overlay.innerHTML = ''
            + '<div style="background:#fff;max-width:520px;width:100%;border-radius:8px;border:1px solid #E8E6DF;box-shadow:0 10px 40px rgba(0,0,0,0.18);overflow:hidden;">'
            + '<div style="display:flex;align-items:center;gap:10px;padding:14px 18px;border-bottom:1px solid #E8E6DF;">'
            +   '<div style="width:24px;height:24px;background:#0F1720;color:#fff;font-size:10px;font-weight:700;text-align:center;line-height:24px;border-radius:5px;flex:0 0 24px;">PT</div>'
            +   '<div style="font-size:13px;font-weight:600;color:#0F1720;">Send sign-off email</div>'
            + '</div>'
            + '<div style="padding:18px 20px;font-size:13.5px;line-height:1.5;color:#0F1720;">'
            +   '<p style="margin:0 0 14px;color:#4a4f58;">Fire a digest immediately for one loginid &mdash; useful for re-sending after a missed deadline or testing the email layout. Bypasses the daily dedupe.</p>'
            +   '<div style="margin-bottom:12px;">'
            +     '<label style="display:flex;align-items:flex-start;gap:8px;padding:8px;border:1px solid #E8E6DF;border-radius:6px;cursor:pointer;margin-bottom:6px;">'
            +       '<input type="radio" name="iteSsFlow" value="GOLIVE" checked style="margin-top:3px;">'
            +       '<div><div style="font-weight:600;font-size:13px;">Flow A &mdash; Go-Live sign-off</div>'
            +       '<div style="font-size:11.5px;color:#6B7280;">To the requestor. Writes <code>Page Three.Target Go Live Date</code>.</div></div>'
            +     '</label>'
            +     '<label style="display:flex;align-items:flex-start;gap:8px;padding:8px;border:1px solid #E8E6DF;border-radius:6px;cursor:pointer;">'
            +       '<input type="radio" name="iteSsFlow" value="UAT" style="margin-top:3px;">'
            +       '<div><div style="font-weight:600;font-size:13px;">Flow B &mdash; Target UAT check</div>'
            +       '<div style="font-size:11.5px;color:#6B7280;">To the IT owner. Writes <code>Page Three.Target UAT Date</code>.</div></div>'
            +     '</label>'
            +   '</div>'
            +   '<div style="margin-bottom:8px;">'
            +     '<label style="display:block;font-family:\'IBM Plex Mono\',Consolas,monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#6B7280;margin-bottom:4px;">Recipient</label>'
            +     '<select id="iteSsLogin" style="width:100%;padding:7px 10px;font-size:13.5px;border:1px solid #E8E6DF;border-radius:6px;box-sizing:border-box;background:#fff;">'
            +       '<option value="">— pick a person —</option>'
            +       recipients.map(function (p) {
                        var label = (p.name || p.loginId);
                        // Suffix the role(s) so the admin can confirm they
                        // picked someone whose role matches the active flow.
                        var roleBits = [];
                        if (p.roles.requestor) roleBits.push('Requestor');
                        if (p.roles.owner) roleBits.push('IT Owner');
                        var suffix = ' — ' + p.loginId + (roleBits.length ? ' · ' + roleBits.join(' / ') : '');
                        return '<option value="' + escapeAttr(p.loginId) + '">'
                            + escapeHtml(label) + escapeHtml(suffix) + '</option>';
                     }).join('')
            +     '</select>'
            +   '</div>'
            +   '<div id="iteSsHelp" style="font-size:11.5px;color:#6B7280;"></div>'
            +   '<div id="iteSsErr" style="display:none;margin-top:10px;font-size:12px;color:#B8342B;background:#fdeaea;border:1px solid #eac8c5;border-radius:6px;padding:8px 12px;"></div>'
            + '</div>'
            + '<div style="display:flex;justify-content:flex-end;gap:8px;padding:12px 18px;border-top:1px solid #E8E6DF;background:#FAFAF7;">'
            +   '<button id="iteSsCancel" style="background:#fff;color:#6B7280;border:1px solid #E8E6DF;padding:8px 18px;border-radius:6px;font-size:13px;font-weight:500;cursor:pointer;">Cancel</button>'
            +   '<button id="iteSsSend" style="background:#0F1720;color:#fff;border:none;padding:8px 18px;border-radius:6px;font-size:13px;font-weight:600;cursor:pointer;">Send</button>'
            + '</div>'
            + '</div>';
        document.body.appendChild(overlay);

        var loginInput = overlay.querySelector('#iteSsLogin');
        var helpEl = overlay.querySelector('#iteSsHelp');
        var errEl = overlay.querySelector('#iteSsErr');
        var sendBtn = overlay.querySelector('#iteSsSend');
        var cancelBtn = overlay.querySelector('#iteSsCancel');

        function updateDefaults() {
            var flow = (overlay.querySelector('input[name="iteSsFlow"]:checked') || {}).value || 'GOLIVE';
            var pick = pickDefault(flow, selRow);
            if (pick) {
                // Append a one-off option if the pre-filled loginid isn't in
                // the recipients list (e.g. the selected row's requestor or
                // owner has been deleted/renamed since the snapshot).
                var exists = Array.prototype.some.call(loginInput.options, function (o) { return o.value === pick; });
                if (!exists) {
                    var opt = document.createElement('option');
                    opt.value = pick;
                    opt.textContent = pick + ' — (not in current grid)';
                    loginInput.appendChild(opt);
                }
                loginInput.value = pick;
                helpEl.textContent = 'Pre-filled from selected row ' + (selRow.ecnNumber || '') + '. Pick someone else if needed.';
            } else {
                loginInput.value = '';
                helpEl.textContent = 'Selected row has no ' + (flow === 'GOLIVE' ? 'requestor' : 'IT owner') + ' — pick a recipient below.';
            }
        }
        updateDefaults();
        overlay.querySelectorAll('input[name="iteSsFlow"]').forEach(function (r) {
            r.addEventListener('change', updateDefaults);
        });

        function close() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }

        cancelBtn.addEventListener('click', close);
        overlay.addEventListener('click', function (e) { if (e.target === overlay) close(); });
        document.addEventListener('keydown', function escHandler(e) {
            if (e.key === 'Escape') { close(); document.removeEventListener('keydown', escHandler); }
        });

        sendBtn.addEventListener('click', function () {
            var flow = (overlay.querySelector('input[name="iteSsFlow"]:checked') || {}).value || 'GOLIVE';
            var loginId = (loginInput.value || '').trim();
            if (!loginId) {
                errEl.textContent = 'Pick a recipient from the dropdown.';
                errEl.style.display = 'block';
                loginInput.focus();
                return;
            }
            errEl.style.display = 'none';
            sendBtn.disabled = true;
            sendBtn.textContent = 'Sending…';
            fetch('/api/golive-signoff/trigger', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ flow: flow, loginId: loginId })
            })
                .then(function (r) { return r.json().then(function (b) { return { status: r.status, body: b }; }); })
                .then(function (pkt) {
                    sendBtn.disabled = false;
                    sendBtn.textContent = 'Send';
                    if (pkt.body && pkt.body.ok) {
                        close();
                        if (pkt.body.sent > 0) {
                            showToast({ kind: 'success', text: 'Sign-off email sent to ' + loginId + ' (' + flow + ').' });
                        } else {
                            showToast({ kind: 'pending', text: 'No qualifying ECNs for ' + loginId + ' (' + flow + ') in the window.' });
                        }
                    } else {
                        var msg = (pkt.body && pkt.body.error) ? pkt.body.error : ('HTTP ' + pkt.status);
                        errEl.textContent = msg;
                        errEl.style.display = 'block';
                    }
                })
                .catch(function (e) {
                    sendBtn.disabled = false;
                    sendBtn.textContent = 'Send';
                    errEl.textContent = 'Network error: ' + e.message;
                    errEl.style.display = 'block';
                });
        });
    }

    // ============================================================
    // Bulk actions (My view) — selection + clear
    // ============================================================

    function iteBulkClearSelection() {
        STATE.selected = {};
        renderBulkBar();
        render();
    }

    // ============================================================
    // Bulk: Extend dates (preview-then-save)
    // ============================================================
    //
    // §7c semantics:
    //   - scope = selected rows if any are checked, else ALL My rows
    //   - per row, include UAT or Go-Live ONLY if it's blank or < today
    //     (future-dated rows are skipped)
    //   - new value = base + 7d, where base = existingDate || today
    //   - 'both' evaluates UAT and Go-Live independently — a row may
    //     contribute one or both date cells
    //   - conflict flag: if resulting Go-Live < (possibly-also-extended)
    //     UAT, mark with red `!`; user may still save
    //
    // The preview *is* the review gate. On Save N to Agile we go through
    // the same /save-batch path as a normal multi-cell save, so the
    // existing Agile-signin modal + retry path Just Works.

    var BUSINESS_TODAY = (function () {
        var d = new Date();
        d.setHours(0, 0, 0, 0);
        return d;
    })();

    function fmtIsoDate(d) {
        var y = d.getFullYear();
        var m = String(d.getMonth() + 1).padStart(2, '0');
        var dd = String(d.getDate()).padStart(2, '0');
        return y + '-' + m + '-' + dd;
    }

    function addDays(d, n) {
        var nd = new Date(d.getTime());
        nd.setDate(nd.getDate() + n);
        return nd;
    }

    /** Pick the rows the Extend buttons should operate on. Selection if any,
     *  else all My rows (per §7c). The All view is gated upstream — the
     *  bulk bar is hidden, so this is only ever called in My view. */
    function pickBulkScopeRows() {
        var selectedEcns = Object.keys(STATE.selected).filter(function (k) { return STATE.selected[k]; });
        if (selectedEcns.length) {
            var set = {};
            selectedEcns.forEach(function (e) { set[e] = true; });
            return STATE.rows.filter(function (r) { return set[r.ecnNumber] && isMine(r); });
        }
        return STATE.rows.filter(isMine);
    }

    /** Compute the preview items for a given Extend mode. */
    function computeExtendItems(mode) {
        var scope = pickBulkScopeRows();
        var todayIso = fmtIsoDate(BUSINESS_TODAY);
        var wantUat = (mode === 'uat' || mode === 'both');
        var wantGoLive = (mode === 'golive' || mode === 'both');
        var items = [];
        scope.forEach(function (r) {
            // For each row, evaluate UAT and Go-Live independently. A row
            // can contribute 0, 1, or 2 items depending on which dates are
            // past/blank.
            var rowItems = [];
            function consider(field, label, cellName) {
                var current = r[field] || '';
                // Past or blank? Blank = always include. Future = skip.
                var include = false;
                if (!current) include = true;
                else include = (current < todayIso);
                if (!include) return;
                var base = current ? new Date(current + 'T00:00:00') : BUSINESS_TODAY;
                var next = addDays(base, 7);
                rowItems.push({
                    ecn: r.ecnNumber,
                    field: field,
                    label: label,
                    cellName: cellName,
                    current: current,
                    next: fmtIsoDate(next),
                    nextDate: next  // kept for conflict eval below
                });
            }
            if (wantUat) consider('targetUAT', 'Target UAT', 'Page Three.Target UAT Date');
            if (wantGoLive) consider('targetGoLive', 'Target Go-Live', 'Page Three.Target Go Live Date');
            // Conflict flag for Go-Live: if the new Go-Live is before the
            // effective new UAT for the SAME row, mark it. Effective UAT =
            // the row's possibly-just-extended UAT (from rowItems) OR the
            // existing UAT value if not being extended this round.
            var newUatIso = null;
            for (var i = 0; i < rowItems.length; i++) {
                if (rowItems[i].field === 'targetUAT') newUatIso = rowItems[i].next;
            }
            if (newUatIso == null) newUatIso = r.targetUAT || '';
            rowItems.forEach(function (it) {
                if (it.field === 'targetGoLive' && newUatIso && it.next < newUatIso) {
                    it.conflict = true;
                    it.conflictReason = 'Go-Live (' + it.next + ') is before Target UAT (' + newUatIso + ').';
                }
            });
            rowItems.forEach(function (it) { delete it.nextDate; });  // not needed downstream
            items.push.apply(items, rowItems);
        });
        return items;
    }

    function iteBulkExtend(mode) {
        if (STATE.subTab !== 'my') return;  // safety — bulk bar is hidden anyway
        if (!STATE.currentUser.loginId) {
            showToast({ kind: 'error', text: 'Could not detect your loginid — refresh the page and try again.' });
            return;
        }
        var items = computeExtendItems(mode);
        if (!items.length) {
            var label = (mode === 'uat') ? 'Target UAT' : (mode === 'golive') ? 'Target Go-Live' : 'Target UAT or Go-Live';
            var scopeLabel = Object.keys(STATE.selected).filter(function (k) { return STATE.selected[k]; }).length
                ? 'the selected rows'
                : 'My Enhancements';
            showToast({ kind: 'pending', text: 'No rows with a past or missing ' + label + ' in ' + scopeLabel + '.' });
            return;
        }
        openExtendPreviewModal(mode, items);
    }

    function openExtendPreviewModal(mode, items) {
        // Count distinct ECNs the items touch — useful summary.
        var ecnSet = {};
        items.forEach(function (it) { ecnSet[it.ecn] = true; });
        var ecnCount = Object.keys(ecnSet).length;
        var modeLabel = (mode === 'uat') ? 'Target UAT'
                      : (mode === 'golive') ? 'Target Go-Live'
                      : 'Target UAT &amp; Go-Live';

        var overlay = document.createElement('div');
        overlay.className = 'ite2-modal-back';
        overlay.style.cssText = 'position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:20000;' +
            ' display:flex; align-items:center; justify-content:center; padding:20px;' +
            " font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;";

        var rowsHtml = items.map(function (it) {
            var currentTxt = it.current
                ? '<span class="u-mono" style="color:#0F1720;">' + escapeHtml(it.current) + '</span>'
                : '<span style="color:#9CA3AF; font-style:italic;">(blank)</span>';
            var nextTxt = '<span class="u-mono" style="color:#0F1720; font-weight:600;">' + escapeHtml(it.next) + '</span>';
            var flag = it.conflict
                ? '<span title="' + escapeAttr(it.conflictReason) + '" style="color:#B8342B; font-weight:700; margin-right:6px;">!</span>'
                : '';
            return '<tr style="border-bottom:1px solid #E8E6DF;' + (it.conflict ? ' background:#fdeaea;' : '') + '">' +
                '<td style="padding:6px 10px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px; color:#4a6fa5;">' + flag + escapeHtml(it.ecn) + '</td>' +
                '<td style="padding:6px 10px; font-size:12px;">' + escapeHtml(it.label) + '</td>' +
                '<td style="padding:6px 10px; font-size:12px; text-align:right;">' + currentTxt + '</td>' +
                '<td style="padding:6px 10px; font-size:12px; text-align:center; color:#6B7280;">→</td>' +
                '<td style="padding:6px 10px; font-size:12px;">' + nextTxt + '</td>' +
                '</tr>';
        }).join('');

        var conflictCount = items.filter(function (it) { return it.conflict; }).length;
        var warnBanner = '';
        if (conflictCount > 0) {
            warnBanner = '<div style="margin:0 0 10px; padding:8px 12px; background:#fdeaea; color:#721c24; border-left:4px solid #B8342B; border-radius:0 4px 4px 0; font-size:12px;">' +
                conflictCount + ' row(s) would land Target Go-Live BEFORE Target UAT. Marked with <strong style="color:#B8342B;">!</strong>. ' +
                'You can still save (they will be flagged as errors), or cancel and use "Extend both +1 wk" so UAT moves alongside.' +
                '</div>';
        }

        overlay.innerHTML = ''
            + '<div style="background:#fff; max-width:760px; width:100%; max-height:88vh; border-radius:8px; border:1px solid #E8E6DF; box-shadow:0 12px 40px rgba(0,0,0,0.25); overflow:hidden; display:flex; flex-direction:column;">'
            + '<div style="display:flex; align-items:center; gap:10px; padding:14px 18px; border-bottom:1px solid #E8E6DF;">'
            +   '<div style="width:26px; height:26px; background:#0F1720; color:#fff; font-size:10px; font-weight:700; text-align:center; line-height:26px; border-radius:5px; flex:0 0 26px;">PT</div>'
            +   '<div style="font-size:14px; font-weight:600; color:#0F1720;">Preview: extend ' + modeLabel + ' by 7 days</div>'
            + '</div>'
            + '<div style="padding:14px 20px; font-size:13px; color:#0F1720; line-height:1.5; overflow-y:auto;">'
            +   '<div style="margin:0 0 12px; color:#4a4f58;">'
            +     '<strong>' + items.length + ' date' + (items.length === 1 ? '' : 's') + '</strong> across <strong>' + ecnCount + ' enhancement' + (ecnCount === 1 ? '' : 's') + '</strong>. Future-dated rows are left untouched. '
            +     'Each save writes under your Agile identity (' + escapeHtml(STATE.currentUser.displayName || STATE.currentUser.loginId) + ').'
            +   '</div>'
            +   warnBanner
            +   '<div style="border:1px solid #E8E6DF; border-radius:4px; overflow:hidden;">'
            +     '<table style="width:100%; border-collapse:collapse;">'
            +       '<thead style="background:#FAFAF7;">'
            +         '<tr>'
            +           '<th style="padding:7px 10px; text-align:left; font-size:11px; text-transform:uppercase; color:#6B7280; font-weight:600;">ECN</th>'
            +           '<th style="padding:7px 10px; text-align:left; font-size:11px; text-transform:uppercase; color:#6B7280; font-weight:600;">Field</th>'
            +           '<th style="padding:7px 10px; text-align:right; font-size:11px; text-transform:uppercase; color:#6B7280; font-weight:600;">Current</th>'
            +           '<th style="padding:7px 10px;"></th>'
            +           '<th style="padding:7px 10px; text-align:left; font-size:11px; text-transform:uppercase; color:#6B7280; font-weight:600;">New</th>'
            +         '</tr>'
            +       '</thead>'
            +       '<tbody>' + rowsHtml + '</tbody>'
            +     '</table>'
            +   '</div>'
            + '</div>'
            + '<div style="display:flex; justify-content:flex-end; gap:8px; padding:12px 18px; border-top:1px solid #E8E6DF; background:#FAFAF7;">'
            +   '<button id="iteExtCancel" style="background:#fff; color:#6B7280; border:1px solid #E8E6DF; padding:8px 18px; border-radius:6px; font-size:13px; font-weight:500; cursor:pointer;">Cancel</button>'
            +   '<button id="iteExtSave" style="background:#0F1720; color:#fff; border:none; padding:8px 18px; border-radius:6px; font-size:13px; font-weight:600; cursor:pointer;">Save ' + items.length + ' to Agile</button>'
            + '</div>'
            + '</div>';
        document.body.appendChild(overlay);

        function close() { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); }

        overlay.querySelector('#iteExtCancel').addEventListener('mousedown', function (ev) { ev.preventDefault(); close(); });
        overlay.addEventListener('mousedown', function (ev) { if (ev.target === overlay) close(); });

        overlay.querySelector('#iteExtSave').addEventListener('mousedown', function (ev) {
            ev.preventDefault();
            // Translate preview items → /save-batch edits AND patch the
            // local dirty map so the cells visibly flip pending → saving
            // → saved/error in the grid behind the modal. We then close
            // the modal and reuse runSaveBatch (which already handles the
            // needsAgileSignin retry path).
            var edits = items.map(function (it) {
                STATE.dirty[ckey(it.ecn, it.cellName)] = {
                    ecn: it.ecn,
                    cell: it.cellName,
                    value: it.next,
                    status: it.conflict ? 'error' : 'saving',
                    msg: it.conflict ? it.conflictReason : ''
                };
                return { ecn: it.ecn, cellName: it.cellName, value: it.next };
            });
            // Drop conflict items from the batch — they're flagged error and
            // stay dirty so the user can fix individually.
            var validEdits = edits.filter(function (e) {
                var d = STATE.dirty[ckey(e.ecn, e.cellName)];
                return d && d.status === 'saving';
            });
            close();
            refreshDirtyBadge();
            render();
            if (validEdits.length === 0) {
                showToast({ kind: 'warn', text: 'All extended dates produced conflicts — see the red dots. Use "Extend both" to keep UAT and Go-Live aligned.' });
                return;
            }
            runSaveBatch(validEdits, /* afterSignin */ false);
        });

        document.addEventListener('keydown', function escHandler(ev) {
            if (ev.key === 'Escape') { close(); document.removeEventListener('keydown', escHandler); }
        });
    }

    // ============================================================
    // Bulk: IT-Log note (prepend dated, attributed stamp)
    // ============================================================

    function iteBulkAddLogNote() {
        if (STATE.subTab !== 'my') return;
        var selectedEcns = Object.keys(STATE.selected).filter(function (k) { return STATE.selected[k]; });
        if (!selectedEcns.length) {
            showToast({ kind: 'warn', text: 'Select one or more rows first, then click "Add log note…".' });
            return;
        }
        var stamp = fmtIsoDate(BUSINESS_TODAY) + ' '
            + (STATE.currentUser.displayName || STATE.currentUser.loginId || 'me');
        var overlay = document.createElement('div');
        overlay.className = 'ite2-modal-back';
        overlay.style.cssText = 'position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:20000;' +
            ' display:flex; align-items:center; justify-content:center; padding:20px;' +
            " font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;";
        overlay.innerHTML = ''
            + '<div style="background:#fff; max-width:560px; width:100%; border-radius:8px; border:1px solid #E8E6DF; box-shadow:0 12px 40px rgba(0,0,0,0.25); overflow:hidden;">'
            + '<div style="display:flex; align-items:center; gap:10px; padding:14px 18px; border-bottom:1px solid #E8E6DF;">'
            +   '<div style="width:26px; height:26px; background:#0F1720; color:#fff; font-size:10px; font-weight:700; text-align:center; line-height:26px; border-radius:5px; flex:0 0 26px;">PT</div>'
            +   '<div style="font-size:14px; font-weight:600; color:#0F1720;">Add IT-Log note to ' + selectedEcns.length + ' enhancement' + (selectedEcns.length === 1 ? '' : 's') + '</div>'
            + '</div>'
            + '<div style="padding:18px 20px; font-size:13px; line-height:1.5; color:#0F1720;">'
            +   '<p style="margin:0 0 10px; color:#4a4f58;">'
            +     'Your note will be prepended (with today\'s date and your name) to each selected row\'s IT Log as a <em>pending edit</em>. '
            +     'Click <strong>Save all changes</strong> in the toolbar afterwards to write them to Agile.'
            +   '</p>'
            +   '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; color:#6B7280; margin-bottom:6px;">Stamp prefix preview</div>'
            +   '<div style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:4px; padding:6px 10px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#0F1720; margin-bottom:14px;">[' + escapeHtml(stamp) + '] &lt;your note here&gt;</div>'
            +   '<label style="display:block; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; letter-spacing:0.1em; text-transform:uppercase; color:#6B7280; margin-bottom:4px;">Note</label>'
            +   '<textarea id="iteLogNoteTxt" rows="5" placeholder="What did you do? Any blockers? Status update?" style="width:100%; box-sizing:border-box; padding:8px 10px; font-size:13px; border:1px solid #E8E6DF; border-radius:6px; font-family:inherit; resize:vertical;"></textarea>'
            +   '<div style="font-size:11px; color:#6B7280; margin-top:6px;">Cmd/Ctrl+Enter to apply &middot; Esc to cancel</div>'
            + '</div>'
            + '<div style="display:flex; justify-content:flex-end; gap:8px; padding:12px 18px; border-top:1px solid #E8E6DF; background:#FAFAF7;">'
            +   '<button id="iteLogNoteCancel" style="background:#fff; color:#6B7280; border:1px solid #E8E6DF; padding:8px 18px; border-radius:6px; font-size:13px; font-weight:500; cursor:pointer;">Cancel</button>'
            +   '<button id="iteLogNoteApply" style="background:#0F1720; color:#fff; border:none; padding:8px 18px; border-radius:6px; font-size:13px; font-weight:600; cursor:pointer;">Queue as pending edits</button>'
            + '</div>'
            + '</div>';
        document.body.appendChild(overlay);
        var txt = overlay.querySelector('#iteLogNoteTxt');
        setTimeout(function () { try { txt.focus(); } catch (e) {} }, 0);

        function close() { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); }
        function apply() {
            var note = (txt.value || '').trim();
            if (!note) {
                showToast({ kind: 'warn', text: 'Type a note before applying.' });
                txt.focus();
                return;
            }
            // Prepend to each selected row's IT Log as a pending edit. The
            // existing dirty machinery will surface them as pending (amber
            // dots) so the user can review or roll back before pressing
            // Save all changes.
            var queued = 0;
            selectedEcns.forEach(function (ecn) {
                var row = pickRow(ecn);
                if (!row) return;
                var existing = effLogValue(row);
                var prefix = '[' + stamp + '] ' + note;
                var combined = existing ? (prefix + '\n' + existing) : prefix;
                // Reuse writeCellEdit so dirty bookkeeping is consistent.
                var logCol = COLUMNS.filter(function (c) { return c.cell === 'Page Three.IT Log'; })[0];
                if (!logCol) return;
                writeCellEdit(row, logCol, combined);
                queued++;
            });
            close();
            refreshDirtyBadge();
            render();
            if (queued) {
                showToast({
                    kind: 'success',
                    text: 'Queued ' + queued + ' IT-Log update' + (queued === 1 ? '' : 's') + ' as pending. Click "Save all changes" to write to Agile.'
                });
            } else {
                showToast({ kind: 'warn', text: 'No selected rows could be updated — try again or check selection.' });
            }
        }

        overlay.querySelector('#iteLogNoteCancel').addEventListener('click', close);
        overlay.querySelector('#iteLogNoteApply').addEventListener('click', apply);
        overlay.addEventListener('mousedown', function (ev) { if (ev.target === overlay) close(); });
        overlay.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape') { ev.preventDefault(); ev.stopPropagation(); close(); }
            else if (ev.key === 'Enter' && (ev.metaKey || ev.ctrlKey)) {
                ev.preventDefault(); ev.stopPropagation(); apply();
            }
        });
    }

    /** Effective IT Log value for a row — respects an in-flight pending edit. */
    function effLogValue(row) {
        var k = ckey(row.ecnNumber, 'Page Three.IT Log');
        var d = STATE.dirty[k];
        return d ? (d.value || '') : (row.itLog || '');
    }

    // ============================================================
    // MEETING MODE
    // ============================================================
    //
    // Meeting Mode sub-tab. Walk a backlog with the room: filter to the
    // requestors present, pick a focus driver (overdue / going live ≤7d /
    // high-priority unowned), then step through items one-by-one. Notes
    // become IT-Log pending edits (so the page's Save-all writes them to
    // Agile under the user's identity); Target UAT / Target Go-Live are
    // editable live; on wrap-up we render a deterministic scorecard +
    // consolidated per-requestor / per-IT-owner email cards.
    //
    // Everything lives in #iteMeetingPanel. The All/My grid chrome
    // (#iteAllMyChrome) is hidden by iteSetSubTab when meeting mode is on.
    //
    // Spec: Meeting-Mode-Spec (1).md (Downloads).

    /** Get all distinct requestors in the active-workflow set. Excludes the
     *  archived rows (date-as-status, Draft, etc.) unless includeArchived is
     *  on — matches the rest of the surface. */
    function meetingRequestors() {
        var rows = STATE.includeArchived ? STATE.rows : STATE.rows.filter(isActiveWorkflow);
        var counts = {};
        rows.forEach(function (r) {
            var name = (r.requestor || '').trim();
            if (!name) return;
            counts[name] = (counts[name] || 0) + 1;
        });
        // Sort by backlog count desc (busiest requestor first), then by name
        // asc as a tiebreaker for stable ordering.
        var keys = Object.keys(counts);
        keys.sort(function (a, b) {
            if (counts[b] !== counts[a]) return counts[b] - counts[a];
            return a.localeCompare(b);
        });
        return keys.map(function (k) { return { name: k, n: counts[k] }; });
    }

    /** Recompute the kind for a row using its EFFECTIVE Target UAT /
     *  Target Go-Live (dirty values if pending), so when the user re-baselines
     *  a date the focus pill counts and the risk badge update live. Mirrors
     *  ItEnhancementsService.classifyRow. */
    function meetingComputeKind(row) {
        var uatStr = effValue(row, COLUMNS.filter(function (c) { return c.key === 'targetUAT'; })[0]);
        var glStr  = effValue(row, COLUMNS.filter(function (c) { return c.key === 'targetGoLive'; })[0]);
        var uat = uatStr ? new Date(uatStr + 'T00:00:00') : null;
        var gl  = glStr  ? new Date(glStr + 'T00:00:00')  : null;
        var today = new Date(); today.setHours(0, 0, 0, 0);
        var s = String(row.status || '').trim().toUpperCase();
        var oneDay = 86400000, sevenDays = 7 * oneDay;
        if (s === 'WIP' && uat && uat.getTime() < today.getTime()) return 'overdue_uat';
        if ((s === 'UAT' || s.indexOf('UAT COMPLETE') === 0) && gl && gl.getTime() < today.getTime()) return 'overdue_golive';
        if (s === 'UAT' && gl && gl.getTime() >= today.getTime() && gl.getTime() <= today.getTime() + sevenDays) return 'approaching_golive';
        return 'ok';
    }

    /** Does a row match the focus pill given a live kind? */
    function meetingPillMatch(pill, row, kind) {
        if (!pill) return true;
        if (pill === 'overdue') return kind === 'overdue_uat' || kind === 'overdue_golive';
        if (pill === 'going_live_soon') return kind === 'approaching_golive';
        if (pill === 'high_no_owner') {
            var p = String(row.priority || '').toLowerCase();
            var isHi = p.indexOf('1') === 0 || p.indexOf('high') >= 0;
            var noOwner = !(row.itOwner && row.itOwner.trim());
            return isHi && noOwner;
        }
        return true;
    }

    /** Rows in scope for the rail given current requestor + focus-pill
     *  filters. Pin rule: a row that was visible under the pill but stopped
     *  matching mid-edit (e.g. user fixed the date) stays visible until the
     *  user navigates away. */
    function meetingScopedRows() {
        var m = STATE.meeting;
        var baseRows = STATE.includeArchived ? STATE.rows.slice() : STATE.rows.filter(isActiveWorkflow);
        var selReqs = m.selReqs || {};
        var anyReq = Object.keys(selReqs).some(function (k) { return selReqs[k]; });
        var rowsByReq = anyReq
            ? baseRows.filter(function (r) { return selReqs[(r.requestor || '').trim()]; })
            : baseRows;
        // Decorate each row with its LIVE kind (used for badge + border) AND
        // its SORT kind (used for ordering). For pinned rows the sort kind is
        // the snapshot stored at pin-time — so a row that the user just fixed
        // mid-edit holds its original position in the rail and the user can
        // keep walking the list naturally with Next.
        var pill = m.focusPill;
        var pinned = m.pinnedEcns || {};
        var withKind = rowsByReq.map(function (r) {
            var live = meetingComputeKind(r);
            var snap = pinned[r.ecnNumber];
            // pinnedEcns used to be a boolean (true). For back-compat, fall
            // back to the live kind if a legacy entry is just `true`.
            var sortKind = (typeof snap === 'string') ? snap : live;
            return { row: r, kind: live, sortKind: sortKind };
        });
        var matched = withKind.filter(function (rk) {
            return meetingPillMatch(pill, rk.row, rk.kind) || pinned[rk.row.ecnNumber];
        });
        // Stable ordering: overdue > approaching > ok, then by ECN number.
        var order = { overdue_uat: 0, overdue_golive: 1, approaching_golive: 2, ok: 3 };
        matched.sort(function (a, b) {
            var oa = order[a.sortKind] != null ? order[a.sortKind] : 3;
            var ob = order[b.sortKind] != null ? order[b.sortKind] : 3;
            if (oa !== ob) return oa - ob;
            return String(a.row.ecnNumber).localeCompare(String(b.row.ecnNumber));
        });
        return matched;
    }

    /** Per-row dirty status — used by the rail edited-state indicator. */
    function meetingEcnEditState(ecn) {
        var keys = Object.keys(STATE.dirty);
        var hasError = false, hasPending = false;
        for (var i = 0; i < keys.length; i++) {
            if (keys[i].indexOf(ecn + '||') !== 0) continue;
            var d = STATE.dirty[keys[i]];
            if (!d) continue;
            if (d.status === 'error') hasError = true;
            else if (d.status === 'pending' || d.status === 'saving') hasPending = true;
        }
        if (hasError) return 'error';
        if (hasPending) return 'pending';
        return null;
    }

    /** True if a non-empty captured note exists for the ECN. */
    function meetingHasNote(ecn) {
        return !!(STATE.meeting.notes[ecn] && STATE.meeting.notes[ecn].trim().length);
    }

    /** Live counts for each focus pill — respects requestor selection. */
    function meetingPillCounts() {
        var m = STATE.meeting;
        var base = STATE.includeArchived ? STATE.rows.slice() : STATE.rows.filter(isActiveWorkflow);
        var selReqs = m.selReqs || {};
        var anyReq = Object.keys(selReqs).some(function (k) { return selReqs[k]; });
        var rows = anyReq
            ? base.filter(function (r) { return selReqs[(r.requestor || '').trim()]; })
            : base;
        var overdue = 0, approaching = 0, hiNoOwner = 0;
        rows.forEach(function (r) {
            var k = meetingComputeKind(r);
            if (k === 'overdue_uat' || k === 'overdue_golive') overdue++;
            if (k === 'approaching_golive') approaching++;
            var p = String(r.priority || '').toLowerCase();
            var isHi = p.indexOf('1') === 0 || p.indexOf('high') >= 0;
            if (isHi && !(r.itOwner && r.itOwner.trim())) hiNoOwner++;
        });
        return { all: rows.length, overdue: overdue, approaching: approaching, hiNoOwner: hiNoOwner };
    }

    /** Main render entry point. Builds the whole Meeting Mode tree into
     *  #iteMeetingPanel — either the review screen or the wrap-up screen. */
    function renderMeetingMode() {
        var panel = document.getElementById('iteMeetingPanel');
        if (!panel) return;
        var m = STATE.meeting;
        if (m.wrappedUp) {
            panel.innerHTML = renderMeetingWrapUp();
            wireWrapUpHandlers();
        } else {
            panel.innerHTML = renderMeetingReview();
            wireReviewHandlers();
            // Keep the focused rail item in view as the user walks the list
            // with Prev / Next or arrow keys. Spec gotcha: nudge scrollTop,
            // do NOT use scrollIntoView (which jerks the whole page).
            ensureRailFocusedVisible();
        }
    }

    /** Scroll the rail container so the .is-on item is visible. Idempotent —
     *  does nothing when the active item is already on screen. Called from
     *  renderMeetingMode after each repaint. */
    function ensureRailFocusedVisible() {
        var rail = document.getElementById('iteMeetRail');
        if (!rail) return;
        var active = rail.querySelector('.ite2-rail-item.is-on');
        if (!active) return;
        var pad = 24;  // soft visible margin above / below the item
        var railTop = rail.scrollTop;
        var railBottom = railTop + rail.clientHeight;
        var itemTop = active.offsetTop;
        var itemBottom = itemTop + active.offsetHeight;
        if (itemTop < railTop + pad) {
            rail.scrollTop = Math.max(0, itemTop - pad);
        } else if (itemBottom > railBottom - pad) {
            rail.scrollTop = itemBottom - rail.clientHeight + pad;
        }
    }

    /** Review-screen HTML (control bar + pills + panes). */
    function renderMeetingReview() {
        var scoped = meetingScopedRows();
        var m = STATE.meeting;
        // Ensure focusedEcn is valid against the scoped set.
        if (m.focusedEcn) {
            var stillThere = scoped.some(function (rk) { return rk.row.ecnNumber === m.focusedEcn; });
            if (!stillThere && scoped.length) m.focusedEcn = scoped[0].row.ecnNumber;
        } else if (scoped.length) {
            m.focusedEcn = scoped[0].row.ecnNumber;
        }
        var idx = scoped.findIndex(function (rk) { return rk.row.ecnNumber === m.focusedEcn; });
        var nNotes = Object.keys(m.notes).filter(function (k) { return (m.notes[k] || '').trim().length; }).length;

        return ''
            + '<div class="ite2-meet">'
            +   renderMeetingControlBar(scoped, idx, nNotes)
            +   renderMeetingPills()
            +   renderMeetingLegend()
            +   '<div class="ite2-meet-panes">'
            +     '<div class="ite2-rail" id="iteMeetRail">' + renderMeetingRail(scoped, idx) + '</div>'
            +     '<div class="ite2-focus" id="iteMeetFocus">' + renderMeetingFocusCard(scoped, idx) + '</div>'
            +   '</div>'
            + '</div>';
    }

    /** Small horizontal legend under the focus pills. Explains the rail dots
     *  (note captured vs. pending edit vs. error) and the left-border kinds
     *  (overdue UAT / overdue Go-Live / approaching Go-Live). Tracks the
     *  exact tokens used in renderMeetingRail() + CSS. */
    function renderMeetingLegend() {
        return ''
            + '<div class="ite2-meet-legend">'
            +   '<span class="ite2-meet-legend-grp">'
            +     '<span class="ite2-meet-legend-lbl">Rail dots</span>'
            +     '<span class="ite2-meet-legend-item"><span class="ite2-rail-dot has-note"></span>Note captured</span>'
            +     '<span class="ite2-meet-legend-item"><span class="ite2-rail-dot is-pending"></span>Edited · pending save</span>'
            +     '<span class="ite2-meet-legend-item"><span class="ite2-rail-dot is-error"></span>Needs fix (validation)</span>'
            +   '</span>'
            +   '<span class="ite2-meet-legend-sep"></span>'
            +   '<span class="ite2-meet-legend-grp">'
            +     '<span class="ite2-meet-legend-lbl">Left border</span>'
            +     '<span class="ite2-meet-legend-item"><span class="ite2-meet-legend-bar" style="background:#B8342B;"></span>Overdue UAT</span>'
            +     '<span class="ite2-meet-legend-item"><span class="ite2-meet-legend-bar" style="background:#C7801B;"></span>Overdue Go-Live</span>'
            +     '<span class="ite2-meet-legend-item"><span class="ite2-meet-legend-bar" style="background:#4a6fa5;"></span>Approaching Go-Live</span>'
            +   '</span>'
            + '</div>';
    }

    function renderMeetingControlBar(scoped, idx, nNotes) {
        var m = STATE.meeting;
        var reqs = meetingRequestors();
        var selCount = Object.keys(m.selReqs).filter(function (k) { return m.selReqs[k]; }).length;
        var selLabel;
        if (selCount === 0) selLabel = 'All requestors (' + reqs.length + ')';
        else if (selCount === 1) {
            var only = Object.keys(m.selReqs).filter(function (k) { return m.selReqs[k]; })[0];
            selLabel = only;
        } else selLabel = selCount + ' requestors selected';
        var pop = '';
        if (m.showRequestorPopover) {
            pop = ''
                + '<div class="ite2-ms-pop" id="iteMeetReqPop">'
                +   '<div class="ite2-ms-actions">'
                +     '<button id="iteMeetReqSelectAll">Select all</button>'
                +     '<button id="iteMeetReqClear">Clear</button>'
                +   '</div>'
                +   reqs.map(function (r) {
                        var checked = m.selReqs[r.name] ? 'checked' : '';
                        return '<label class="ite2-ms-row">'
                            + '<input type="checkbox" data-req="' + escapeAttr(r.name) + '" ' + checked + '>'
                            + '<span>' + escapeHtml(r.name) + '</span>'
                            + '<span class="ite2-ms-n">' + r.n + '</span>'
                            + '</label>';
                     }).join('')
                + '</div>';
        }

        // Pending dirty entries (date edits, IT Log edits, anything in the
        // dirty map). The wrap-up button enables when there's ANY pending
        // change to commit, not just captured notes — a Target-date-only
        // edit should still let the user wrap up and save.
        var nDirty = Object.keys(STATE.dirty || {}).length;
        var progressBits = [];
        if (scoped.length) progressBits.push('<strong>' + (idx + 1) + ' / ' + scoped.length + '</strong>');
        progressBits.push(nNotes + ' note' + (nNotes === 1 ? '' : 's') + ' captured');
        if (nDirty > 0) progressBits.push(nDirty + ' pending edit' + (nDirty === 1 ? '' : 's'));
        var progress = progressBits.join(' · ');

        var wrapDisabled = (nNotes === 0 && nDirty === 0);

        return ''
            + '<div class="ite2-meet-bar">'
            +   '<span class="u-label">Reviewing</span>'
            +   '<div class="ite2-ms" id="iteMeetReqWrap">'
            +     '<button class="ite2-ms-btn" id="iteMeetReqBtn" type="button">' + escapeHtml(selLabel) + '</button>'
            +     pop
            +   '</div>'
            +   '<span class="ite2-meet-bar-progress">' + progress + '</span>'
            +   '<span style="flex:1 1 auto;"></span>'
            +   '<button class="ite2-meet-wrapbtn" id="iteMeetWrapBtn"' + (wrapDisabled ? ' disabled style="opacity:0.5; cursor:not-allowed;"' : '') + '>Wrap up &amp; consolidate →</button>'
            + '</div>';
    }

    function renderMeetingPills() {
        var m = STATE.meeting;
        var c = meetingPillCounts();
        function pill(key, label, tone, n) {
            var on = m.focusPill === key;
            return '<button class="ite2-pill ' + tone + (on ? ' is-on' : '') + '" data-pill="' + key + '">'
                + escapeHtml(label) + ' <span class="ite2-pill-n">(' + n + ')</span>'
                + '</button>';
        }
        var showAll = '<button class="ite2-pill-clear" id="iteMeetShowAll">Show all (' + c.all + ')</button>';
        return ''
            + '<div class="ite2-meet-pills">'
            +   '<span class="ite2-meet-pills-lbl">Start with</span>'
            +   pill('overdue', 'Overdue UAT / Go-Live', 'pill-red', c.overdue)
            +   pill('going_live_soon', 'Going live ≤ 7 days', 'pill-accent', c.approaching)
            +   pill('high_no_owner', 'High priority · no IT owner', 'pill-warn', c.hiNoOwner)
            +   (m.focusPill ? showAll : '')
            + '</div>';
    }

    function renderMeetingRail(scoped, idx) {
        if (!scoped.length) {
            return '<div class="ite2-rail-empty">No items match the current filter. Adjust the requestor selection or clear the focus pill.</div>';
        }
        return scoped.map(function (rk, i) {
            var r = rk.row;
            var on = (i === idx);
            var edit = meetingEcnEditState(r.ecnNumber);
            var noteOn = meetingHasNote(r.ecnNumber);
            var cls = 'ite2-rail-item kind-' + rk.kind + (on ? ' is-on' : '');
            if (edit === 'pending') cls += ' edited-pending';
            else if (edit === 'error') cls += ' edited-error';
            var dots = '';
            if (noteOn) dots += '<span class="ite2-rail-dot has-note" title="Note captured"></span>';
            if (edit === 'pending') dots += '<span class="ite2-rail-dot is-pending" title="Edited · pending save"></span>';
            else if (edit === 'error') dots += '<span class="ite2-rail-dot is-error" title="Needs fix"></span>';
            var proposalShort = (r.proposal || r.problemStatement || '').trim();
            return '<div class="' + cls + '" data-ecn="' + escapeAttr(r.ecnNumber) + '">'
                + '<div class="ite2-rail-num">' + (i + 1) + '.</div>'
                + '<div class="ite2-rail-ecn">' + agileChangeLinkHtml(r.ecnNumber) + '</div>'
                + '<div class="ite2-rail-prop">' + (proposalShort ? escapeHtml(proposalShort) : '<em style="color:#9CA3AF;">(no proposal text)</em>') + '</div>'
                + '<div class="ite2-rail-status">' + escapeHtml(r.status || '—') + '</div>'
                + '<div class="ite2-rail-dots">' + dots + '</div>'
                + '</div>';
        }).join('');
    }

    function renderMeetingFocusCard(scoped, idx) {
        if (!scoped.length || idx < 0) {
            return '<div class="ite2-focus-empty">Pick a requestor (or clear the focus pill) to populate the list, then click an item to review it here.</div>';
        }
        var rk = scoped[idx];
        var r = rk.row;
        var kind = rk.kind;
        var editState = meetingEcnEditState(r.ecnNumber);

        var uatCol = COLUMNS.filter(function (c) { return c.key === 'targetUAT'; })[0];
        var glCol  = COLUMNS.filter(function (c) { return c.key === 'targetGoLive'; })[0];
        var uatVal = effValue(r, uatCol);
        var glVal  = effValue(r, glCol);
        var uatDirty = STATE.dirty[ckey(r.ecnNumber, uatCol.cell)];
        var glDirty  = STATE.dirty[ckey(r.ecnNumber, glCol.cell)];

        function chipForEdit() {
            // Both chips get a row-level Undo link that discards every
            // dirty entry for this ECN + the captured note. Single
            // affordance for the common "ah, never mind" case.
            if (editState === 'error') return '<span class="ite2-chip is-error">Needs fix</span> <button class="ite2-undo-link" data-undo="row" title="Discard all pending edits on this ECN (only affects values not yet saved to Agile)">↶ Undo</button>';
            if (editState === 'pending') return '<span class="ite2-chip is-pending">Edited · pending save</span> <button class="ite2-undo-link" data-undo="row" title="Discard all pending edits on this ECN (only affects values not yet saved to Agile)">↶ Undo</button>';
            return '';
        }

        function metaCell(label, value, isMono) {
            var blank = !value;
            return '<div class="ite2-fmeta-cell">'
                + '<div class="ite2-fmeta-lbl">' + escapeHtml(label) + '</div>'
                + '<div class="ite2-fmeta-val' + (blank ? ' is-blank' : '') + (isMono ? ' is-mono' : '') + '">'
                +   (blank ? '—' : escapeHtml(value))
                + '</div></div>';
        }

        function dateMetaCell(label, col, value, dirty) {
            var inputCls = 'ite2-fmeta-dateinput';
            if (dirty && dirty.status === 'error') inputCls += ' is-error';
            else if (dirty) inputCls += ' is-pending';
            var msg = (dirty && dirty.status === 'error' && dirty.msg)
                ? '<div class="ite2-fmeta-err">' + escapeHtml(dirty.msg) + '</div>' : '';
            var undo = dirty
                ? '<button class="ite2-undo-link" data-undo="cell" data-cell="' + escapeAttr(col.cell) + '" title="Discard this pending edit and restore the value from Agile">↶</button>'
                : '';
            return '<div class="ite2-fmeta-cell">'
                + '<div class="ite2-fmeta-lbl">' + escapeHtml(label) + ' <span class="ite2-pencil-tiny" title="Editable">✎</span></div>'
                + '<div class="ite2-fmeta-date">'
                +   '<input type="date" class="' + inputCls + '" data-datecol="' + escapeAttr(col.key) + '" value="' + escapeAttr(value || '') + '">'
                +   undo
                + '</div>'
                + msg
                + '</div>';
        }

        var note = STATE.meeting.notes[r.ecnNumber] || '';
        // Use COMMITTED IT Log (r.itLog) for the Latest Update block — we
        // want it to reflect what's in Agile, separate from the pending
        // meeting note. effLogValue() would include the in-flight stamp.
        var committedLog = r.itLog || '';
        var noteHintTxt = note.trim()
            ? 'Saved as a pending IT Log edit, stamped <span style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px;">[YYYY-MM-DD ' + escapeHtml(STATE.currentUser.displayName || 'you') + ']</span> + prepended to the existing log.'
            : 'Saved as a pending IT Log edit, stamped with today\'s date + your name. Apply with Save all changes (in any tab) or the Wrap up button.';
        var noteUndo = note.trim()
            ? ' <button class="ite2-undo-link" data-undo="note" title="Discard this captured note">↶ Discard note</button>'
            : '';

        return ''
            + '<div class="ite2-focus-head">'
            +   '<span class="ite2-focus-ecn">' + agileChangeLinkHtml(r.ecnNumber) + '</span>'
            +   '<span class="ite2-badge kind-' + kind + '">' + meetingKindLabel(kind) + '</span>'
            +   chipForEdit()
            + '</div>'
            + '<div class="ite2-fmeta">'
            +   metaCell('Requestor', r.requestor)
            +   metaCell('IT Owner', r.itOwner)
            +   metaCell('IT Status', r.status)
            +   metaCell('Priority', r.priority)
            +   dateMetaCell('Target UAT', uatCol, uatVal, uatDirty)
            +   dateMetaCell('Target Go-Live', glCol, glVal, glDirty)
            +   metaCell('Hrs', r.hours, true)
            +   metaCell('Project', r.project)
            + '</div>'
            + '<div class="ite2-focus-body">'
            +   (r.problemStatement ? ('<h4>Problem</h4><p>' + escapeHtml(r.problemStatement) + '</p>') : '')
            +   '<h4>Proposal</h4><p>' + escapeHtml(r.proposal || '(none captured)') + '</p>'
            +   buildLatestUpdateBlock(r.ecnNumber, committedLog)
            +   '<h4>Meeting note → IT Log</h4>'
            +   '<div class="ite2-note-wrap">'
            +     '<textarea id="iteMeetNoteTxt" class="ite2-note-ta" placeholder="Decision, action, blocker, agreed next step…">' + escapeHtml(note) + '</textarea>'
            +     '<div class="ite2-note-hint">' + noteHintTxt + noteUndo + '</div>'
            +   '</div>'
            + '</div>'
            + '<div class="ite2-focus-nav">'
            +   '<button id="iteMeetPrev"' + (idx <= 0 ? ' disabled' : '') + '>← Prev</button>'
            +   '<button id="iteMeetNext"' + (idx >= scoped.length - 1 ? ' disabled' : '') + '>Next →</button>'
            + '</div>';
    }

    function meetingKindLabel(kind) {
        if (kind === 'overdue_uat') return 'Overdue UAT';
        if (kind === 'overdue_golive') return 'Overdue Go-Live';
        if (kind === 'approaching_golive') return 'Approaching Go-Live';
        return 'On track';
    }

    /** Render a "Latest update" block above the meeting note section.
     *  Prefers the structured history fetched from /it-log-history (gives
     *  full author + timestamp attribution for entries written directly in
     *  Agile). Falls back to parsing the toolkit's own `[YYYY-MM-DD Name]`
     *  stamp from the committed plain-text IT Log if structured history
     *  hasn't loaded yet or returned empty.
     *
     *  Source = COMMITTED log on the row, not effValue — the in-flight
     *  meeting note already shows in the textarea above. */
    function buildLatestUpdateBlock(ecn, committedLog) {
        var cached = STATE.itLogHistory ? STATE.itLogHistory[ecn] : undefined;
        // Three states for the structured-history cache:
        //   - undefined  : not yet probed
        //   - 'fetching' : request in flight; show the "loading…" suffix
        //   - Array      : probe completed (empty → use fallback parser
        //                  without "loading…"; non-empty → use as-is)
        if (Array.isArray(cached) && cached.length) {
            return buildLatestBlockFromEntries(cached);
        }
        var inFlight = (cached === 'fetching');
        var raw = (committedLog || '').replace(/\s+$/, '');
        if (!raw) return '';
        var name = null, date = null, entry = raw;
        // Pattern 1: toolkit's own prepend stamp `[YYYY-MM-DD Display Name]`.
        var stamp = raw.match(/^\[(\d{4}-\d{2}-\d{2})\s+([^\]]+)\]\s*([\s\S]*?)(?=\n\[\d{4}-\d{2}-\d{2}\s+[^\]]+\]|$)/);
        if (stamp) {
            date = stamp[1];
            name = stamp[2].trim();
            entry = stamp[3].trim();
        } else {
            // Pattern 2: Agile's own author-band header. After Oracle strips
            // HTML with REGEXP_REPLACE(text,'<[^>]+>',' '), the original
            // <b>Last, First (loginid) MM/DD/YYYY HH:MM:SS AM/PM TZ</b> header
            // becomes inline plain text at the start of the entry. Match it
            // so we can show author + ISO date even without the structured
            // /it-log-history endpoint (which needs agprod access).
            var agile = raw.match(/^([^()<>\n]+?)\s*\((\d{3,})\)\s+(\d{1,2}\/\d{1,2}\/\d{4})\s+\d{1,2}:\d{2}:\d{2}\s+(?:AM|PM)\s+\S+\s+([\s\S]*?)(?=\n\s*[^()<>\n]+?\s*\(\d{3,}\)\s+\d{1,2}\/\d{1,2}\/\d{4}\s+\d{1,2}:\d{2}:\d{2}\s+(?:AM|PM)|$)/);
            if (agile) {
                name = agile[1].trim();
                var dm = agile[3].split('/');
                var mm = dm[0].length === 1 ? '0' + dm[0] : dm[0];
                var dd = dm[1].length === 1 ? '0' + dm[1] : dm[1];
                date = dm[2] + '-' + mm + '-' + dd;
                entry = agile[4].replace(/\s+/g, ' ').trim();
            } else {
                var firstPara = raw.split(/\n{2,}/)[0].trim();
                entry = firstPara.length <= 400 ? firstPara : (firstPara.substring(0, 400) + '…');
            }
        }
        var hasMore = raw.length > entry.length + 50;
        var header;
        if (name) {
            header = 'Latest update by <span class="ite2-latest-by">' + escapeHtml(name) + '</span> · <span class="u-mono">' + escapeHtml(date) + '</span>';
        } else if (inFlight) {
            header = 'Latest IT Log entry · <span class="u-mono" style="font-size:10.5px;">attribution loading…</span>';
        } else {
            header = 'Latest IT Log entry';
        }
        return ''
            + '<div class="ite2-latest" data-ecn="' + escapeAttr(ecn) + '">'
            +   '<div class="ite2-latest-head">' + header + '</div>'
            +   '<div class="ite2-latest-body">' + escapeHtml(entry) + '</div>'
            +   (hasMore
                    ? '<details class="ite2-latest-more"><summary>Show full IT Log history (' + raw.length + ' chars)</summary><div class="ite2-latest-full">' + escapeHtml(raw) + '</div></details>'
                    : '')
            + '</div>';
    }

    /** Latest-update block built from a structured /it-log-history response.
     *  Entries are most-recent-first; the head shows the top entry's author
     *  + ISO date, the body shows the top entry's text, and the rest are
     *  available behind a Show-full-history details toggle. */
    function buildLatestBlockFromEntries(entries) {
        var top = entries[0];
        var dateOrTs = top.date || top.timestamp || '';
        var header = top.author
            ? ('Latest update by <span class="ite2-latest-by">' + escapeHtml(top.author) + '</span>'
                + (top.loginId ? ' <span class="u-mono" style="font-size:10.5px;color:#9CA3AF;">(' + escapeHtml(top.loginId) + ')</span>' : '')
                + (dateOrTs ? ' · <span class="u-mono">' + escapeHtml(dateOrTs) + '</span>' : ''))
            : 'Latest IT Log entry';
        var bodyTxt = (top.body || '').trim();
        var more = '';
        if (entries.length > 1) {
            var rest = entries.slice(1).map(function (e) {
                var head = (e.author ? e.author : 'Unknown')
                    + (e.loginId ? ' (' + e.loginId + ')' : '')
                    + (e.timestamp ? ' — ' + e.timestamp : '');
                return '<div class="ite2-latest-prev"><div class="ite2-latest-prev-head">' + escapeHtml(head) + '</div>'
                    + '<div class="ite2-latest-prev-body">' + escapeHtml(e.body || '') + '</div></div>';
            }).join('');
            more = '<details class="ite2-latest-more"><summary>Show ' + (entries.length - 1) + ' earlier entr' + (entries.length === 2 ? 'y' : 'ies') + '</summary><div class="ite2-latest-full">' + rest + '</div></details>';
        }
        return ''
            + '<div class="ite2-latest">'
            +   '<div class="ite2-latest-head">' + header + '</div>'
            +   '<div class="ite2-latest-body">' + escapeHtml(bodyTxt) + '</div>'
            +   more
            + '</div>';
    }

    /** Lazy-fetch structured IT Log history for the focused ECN. Caches in
     *  STATE.itLogHistory so navigation doesn't re-hit the backend. Triggers
     *  a re-render of the focus card on arrival so the attribution appears
     *  without the user having to click anything. */
    function fetchItLogHistory(ecn) {
        if (!ecn) return;
        // Skip if already cached: 'fetching' = in flight, Array = done.
        var existing = STATE.itLogHistory[ecn];
        if (existing === 'fetching' || Array.isArray(existing)) return;
        STATE.itLogHistory[ecn] = 'fetching';
        fetch('/api/it-enhancements/it-log-history?ecn=' + encodeURIComponent(ecn), { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : { entries: [] }; })
            .then(function (resp) {
                STATE.itLogHistory[ecn] = (resp && resp.entries) || [];
                // Only re-render if we're still on this ECN; navigation may
                // have moved on before the response landed.
                if (STATE.subTab === 'meeting' && STATE.meeting.focusedEcn === ecn) {
                    renderMeetingMode();
                }
            })
            .catch(function () {
                STATE.itLogHistory[ecn] = [];
                if (STATE.subTab === 'meeting' && STATE.meeting.focusedEcn === ecn) {
                    renderMeetingMode();
                }
            });
    }

    /** Discard pending edits — either every dirty entry on the focused row
     *  (kind='row'), one specific cell (kind='cell' + data-cell attr), or
     *  just the captured note (kind='note'). Triggered by the ↶ Undo buttons
     *  rendered on the focus card. */
    function discardPending(kind, cellName) {
        var ecn = STATE.meeting.focusedEcn;
        if (!ecn) return;
        if (kind === 'row') {
            Object.keys(STATE.dirty).forEach(function (k) {
                if (k.indexOf(ecn + '||') === 0) delete STATE.dirty[k];
            });
            delete STATE.meeting.notes[ecn];
            delete STATE.meeting.pinnedEcns[ecn];
        } else if (kind === 'cell' && cellName) {
            delete STATE.dirty[ckey(ecn, cellName)];
            // The IT Log cell is tied to the captured note — clear both.
            if (cellName === 'Page Three.IT Log') delete STATE.meeting.notes[ecn];
        } else if (kind === 'note') {
            delete STATE.meeting.notes[ecn];
            delete STATE.dirty[ckey(ecn, 'Page Three.IT Log')];
        }
        refreshDirtyBadge();
        renderSubNav();
        renderMeetingMode();
        scheduleDraftSave();
    }

    /** Wire all event handlers for the review screen. Called after each
     *  re-render — selectors anchor onto fresh DOM nodes. */
    function wireReviewHandlers() {
        var panel = document.getElementById('iteMeetingPanel');
        if (!panel) return;

        // Trigger structured IT Log history fetch for the focused ECN. The
        // result lands asynchronously and re-renders with full attribution.
        if (STATE.meeting.focusedEcn) {
            fetchItLogHistory(STATE.meeting.focusedEcn);
        }

        // Requestor multi-select dropdown.
        var reqBtn = document.getElementById('iteMeetReqBtn');
        if (reqBtn) reqBtn.addEventListener('click', function (ev) {
            ev.stopPropagation();
            STATE.meeting.showRequestorPopover = !STATE.meeting.showRequestorPopover;
            renderMeetingMode();
        });
        if (STATE.meeting.showRequestorPopover) {
            // Outside-click closes popover. Bind once.
            setTimeout(function () {
                var closer = function (ev) {
                    var pop = document.getElementById('iteMeetReqPop');
                    var wrap = document.getElementById('iteMeetReqWrap');
                    if (wrap && wrap.contains(ev.target)) return;
                    if (pop && pop.contains(ev.target)) return;
                    document.removeEventListener('mousedown', closer);
                    STATE.meeting.showRequestorPopover = false;
                    renderMeetingMode();
                };
                document.addEventListener('mousedown', closer);
            }, 0);
            var pop = document.getElementById('iteMeetReqPop');
            if (pop) {
                pop.querySelectorAll('input[type=checkbox]').forEach(function (cb) {
                    cb.addEventListener('change', function () {
                        var name = cb.getAttribute('data-req');
                        if (cb.checked) STATE.meeting.selReqs[name] = true;
                        else delete STATE.meeting.selReqs[name];
                        // Reset focused row and pin-set when scope shifts.
                        STATE.meeting.focusedEcn = '';
                        STATE.meeting.pinnedEcns = {};
                        renderMeetingMode();
                    });
                });
                var selAllBtn = document.getElementById('iteMeetReqSelectAll');
                if (selAllBtn) selAllBtn.addEventListener('click', function () {
                    meetingRequestors().forEach(function (r) { STATE.meeting.selReqs[r.name] = true; });
                    STATE.meeting.focusedEcn = '';
                    STATE.meeting.pinnedEcns = {};
                    renderMeetingMode();
                });
                var clearBtn = document.getElementById('iteMeetReqClear');
                if (clearBtn) clearBtn.addEventListener('click', function () {
                    STATE.meeting.selReqs = {};
                    STATE.meeting.focusedEcn = '';
                    STATE.meeting.pinnedEcns = {};
                    renderMeetingMode();
                });
            }
        }

        // Focus pills (one active at a time; click again to clear).
        panel.querySelectorAll('.ite2-pill[data-pill]').forEach(function (p) {
            p.addEventListener('click', function () {
                var k = p.getAttribute('data-pill');
                if (STATE.meeting.focusPill === k) STATE.meeting.focusPill = null;
                else STATE.meeting.focusPill = k;
                // Clearing the pill drops the pin set; switching pills also resets.
                STATE.meeting.pinnedEcns = {};
                STATE.meeting.focusedEcn = '';
                renderMeetingMode();
            });
        });
        var showAll = document.getElementById('iteMeetShowAll');
        if (showAll) showAll.addEventListener('click', function () {
            STATE.meeting.focusPill = null;
            STATE.meeting.pinnedEcns = {};
            renderMeetingMode();
        });

        // Rail item click → focus.
        panel.querySelectorAll('.ite2-rail-item[data-ecn]').forEach(function (it) {
            it.addEventListener('click', function () {
                STATE.meeting.focusedEcn = it.getAttribute('data-ecn');
                renderMeetingMode();
            });
        });

        // Prev / Next.
        var prev = document.getElementById('iteMeetPrev');
        var next = document.getElementById('iteMeetNext');
        if (prev) prev.addEventListener('click', function () { meetingNavigate(-1); });
        if (next) next.addEventListener('click', function () { meetingNavigate(1); });

        // Note textarea → mirror to meeting.notes AND write IT Log pending edit.
        var ta = document.getElementById('iteMeetNoteTxt');
        if (ta) {
            // Use debounced input so we don't spam writeCellEdit on every keystroke.
            var debounced = debounce(function () { commitMeetingNote(STATE.meeting.focusedEcn, ta.value); }, 250);
            ta.addEventListener('input', debounced);
            ta.addEventListener('blur', function () { commitMeetingNote(STATE.meeting.focusedEcn, ta.value); });
        }

        // Target UAT / Target Go-Live editable inputs → writeCellEdit.
        panel.querySelectorAll('input[type=date][data-datecol]').forEach(function (inp) {
            inp.addEventListener('change', function () {
                var ecn = STATE.meeting.focusedEcn;
                var row = STATE.rows.filter(function (r) { return r.ecnNumber === ecn; })[0];
                if (!row) return;
                var key = inp.getAttribute('data-datecol');
                var col = COLUMNS.filter(function (c) { return c.key === key; })[0];
                if (!col) return;
                // Pin the row so it doesn't vanish from the rail mid-edit if
                // the new date no longer matches the active pill (spec §5).
                // Snapshot the pre-edit kind so the row keeps its rail
                // position even after the kind flips to 'ok'.
                if (STATE.meeting.focusPill && !STATE.meeting.pinnedEcns[ecn]) {
                    STATE.meeting.pinnedEcns[ecn] = meetingComputeKind(row);
                }
                writeCellEdit(row, col, inp.value || '');
                renderSubNav();
                renderMeetingMode();
            });
        });

        // Undo / discard buttons (row-level, per-cell, per-note).
        panel.querySelectorAll('button.ite2-undo-link[data-undo]').forEach(function (b) {
            b.addEventListener('click', function (ev) {
                ev.preventDefault(); ev.stopPropagation();
                discardPending(b.getAttribute('data-undo'), b.getAttribute('data-cell'));
            });
        });

        // Wrap-up button.
        var wrap = document.getElementById('iteMeetWrapBtn');
        if (wrap) wrap.addEventListener('click', function () {
            STATE.meeting.scorecard = meetingComputeScorecard();
            STATE.meeting.wrappedUp = true;
            renderMeetingMode();
        });

        // Keyboard nav — only when focus isn't in an editable element.
        // Bind one document-level listener per render; cheap and self-removing.
        var key = function (ev) {
            // Bail if focus is in an input / textarea / select / contentEditable.
            var t = ev.target;
            if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
            // Only respond when meeting tab is active and not wrapped up.
            if (STATE.subTab !== 'meeting' || STATE.meeting.wrappedUp) {
                document.removeEventListener('keydown', key);
                return;
            }
            if (ev.key === 'ArrowDown' || ev.key === 'ArrowRight') { ev.preventDefault(); meetingNavigate(1); }
            else if (ev.key === 'ArrowUp' || ev.key === 'ArrowLeft') { ev.preventDefault(); meetingNavigate(-1); }
        };
        // Replace any prior listener to avoid stacking.
        if (panel.__meetKeyHandler) document.removeEventListener('keydown', panel.__meetKeyHandler);
        panel.__meetKeyHandler = key;
        document.addEventListener('keydown', key);
    }

    function meetingNavigate(delta) {
        var scoped = meetingScopedRows();
        if (!scoped.length) return;
        var idx = scoped.findIndex(function (rk) { return rk.row.ecnNumber === STATE.meeting.focusedEcn; });
        if (idx < 0) idx = 0;
        var next = Math.max(0, Math.min(scoped.length - 1, idx + delta));
        STATE.meeting.focusedEcn = scoped[next].row.ecnNumber;
        renderMeetingMode();
    }

    /** Mirror the textarea content into STATE.meeting.notes AND into the dirty
     *  map as a pending IT Log edit, stamped per spec §6c. Clearing the note
     *  removes the pending edit. */
    function commitMeetingNote(ecn, raw) {
        if (!ecn) return;
        var note = (raw || '').replace(/\r\n/g, '\n');
        var trimmed = note.trim();
        var row = STATE.rows.filter(function (r) { return r.ecnNumber === ecn; })[0];
        if (!row) return;
        var logCol = COLUMNS.filter(function (c) { return c.cell === 'Page Three.IT Log'; })[0];
        if (!logCol) return;
        var existing = row.itLog || '';
        // Read the prior captured note (if any) so we know what to strip from
        // the existing IT Log when the textarea content changes.
        var prevNote = STATE.meeting.notes[ecn] || '';
        var prevStamped = prevNote.trim() ? buildLogStamp(prevNote) : '';
        // The IT Log we're "appending to" is whatever existed BEFORE the
        // current meeting's stamp — peel that off if present.
        var baseLog = existing;
        var dirty = STATE.dirty[ckey(ecn, logCol.cell)];
        if (dirty && prevStamped && (dirty.value || '').indexOf(prevStamped) === 0) {
            // Strip the previous stamp + the separating newline.
            var rest = (dirty.value || '').substring(prevStamped.length);
            if (rest.charAt(0) === '\n') rest = rest.substring(1);
            baseLog = rest;
        }
        STATE.meeting.notes[ecn] = note;
        if (!trimmed) {
            // Remove from dirty (writeCellEdit clears when equal to committed
            // value). If we had stripped a prev stamp above, write the base
            // back as the new value.
            writeCellEdit(row, logCol, baseLog);
        } else {
            var stamped = buildLogStamp(note);
            var combined = baseLog ? (stamped + '\n' + baseLog) : stamped;
            writeCellEdit(row, logCol, combined);
        }
        renderSubNav();
        // writeCellEdit already scheduled a draft save; explicit call here
        // covers the path where the note was cleared and dirty equals
        // committed (writeCellEdit took the early-out branch without
        // touching dirty, but meeting.notes still changed).
        scheduleDraftSave();
    }

    /** Build the stamped IT Log prefix per spec §6c. */
    function buildLogStamp(note) {
        var d = new Date();
        var ymd = d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
        var name = STATE.currentUser.displayName || STATE.currentUser.loginId || 'me';
        return '[' + ymd + ' ' + name + '] ' + note;
    }
    function pad2(n) { return n < 10 ? '0' + n : '' + n; }

    // ============================================================
    // Wrap-up screen: scorecard + consolidation
    // ============================================================

    /** Compute deterministic metrics + a letter grade for the WHOLE backlog
     *  (every row, not just the meeting subset). Spec §7.
     *
     *  IT Log population is intentionally NOT in the scorecard — many
     *  straightforward ECNs ship without any log entry, so penalizing the
     *  backlog for that would generate false bad-hygiene signals. Schedule
     *  health, ownership, target dates, and effort estimates are the
     *  actionable signals. */
    function meetingComputeScorecard() {
        var rows = STATE.rows.filter(isActiveWorkflow);
        var n = rows.length || 1;  // guard / 0
        var withOwner = 0, withUat = 0, withGoLive = 0, withHours = 0;
        var overdueUat = 0, overdueGoLive = 0, staleAnalysis = 0;
        rows.forEach(function (r) {
            if (r.itOwner && r.itOwner.trim()) withOwner++;
            if (r.targetUAT) withUat++;
            if (r.targetGoLive) withGoLive++;
            if (r.hours && String(r.hours).trim()) withHours++;
            var k = meetingComputeKind(r);
            if (k === 'overdue_uat') overdueUat++;
            else if (k === 'overdue_golive') overdueGoLive++;
            var s = String(r.status || '').trim().toUpperCase();
            if (s === 'ANALYSIS' && !r.targetUAT && !r.targetGoLive) staleAnalysis++;
        });
        function pct(num) { return Math.round((num / n) * 100); }
        var ownerPct = pct(withOwner);
        var uatPct = pct(withUat);
        var goLivePct = pct(withGoLive);
        var hoursPct = pct(withHours);
        var schedulePct = Math.max(0, 100 - pct(overdueUat + overdueGoLive + staleAnalysis));

        // Weighted composite. Schedule health is the highest-signal metric
        // (overdue rows are the actual user-facing failure), followed by
        // ownership coverage and target-date discipline. Effort estimates
        // round out the picture. Weights sum to 1.00.
        var composite = Math.round(
            0.40 * schedulePct +
            0.20 * ownerPct +
            0.15 * uatPct +
            0.15 * goLivePct +
            0.10 * hoursPct
        );
        var grade;
        if (composite >= 90) grade = 'A';
        else if (composite >= 80) grade = 'B';
        else if (composite >= 70) grade = 'C';
        else if (composite >= 60) grade = 'D';
        else grade = 'F';

        // Rule-based rationale + "to reach an A" list.
        var rationale;
        if (grade === 'A') rationale = 'Backlog hygiene is strong — most active rows have an owner, target dates, and effort estimates, and overdue counts are low.';
        else if (grade === 'B') rationale = 'Backlog is in good shape but a few completeness or schedule-health metrics are dragging the average down.';
        else if (grade === 'C') rationale = 'Mixed — coverage is uneven and overdue items are accumulating. Tighten ownership and date discipline.';
        else rationale = 'Significant gaps in ownership, target dates, or schedule discipline. Concentrate next week on closing the largest gaps.';

        var toReachA = [];
        if (schedulePct < 90) toReachA.push('Bring overdue UAT/Go-Live and stale Analysis items under 10% — currently ' + (overdueUat + overdueGoLive + staleAnalysis) + ' / ' + n + ' rows.');
        if (ownerPct < 95) toReachA.push('Assign an IT Owner on the ' + (n - withOwner) + ' active row' + ((n - withOwner) === 1 ? '' : 's') + ' that are still unassigned.');
        if (uatPct < 90) toReachA.push('Set a Target UAT date on the ' + (n - withUat) + ' row' + ((n - withUat) === 1 ? '' : 's') + ' missing one.');
        if (goLivePct < 90) toReachA.push('Set a Target Go-Live date on the ' + (n - withGoLive) + ' row' + ((n - withGoLive) === 1 ? '' : 's') + ' missing one.');
        if (hoursPct < 80) toReachA.push('Estimate effort (Hrs) for the ' + (n - withHours) + ' row' + ((n - withHours) === 1 ? '' : 's') + ' without an Hrs value.');
        if (!toReachA.length) toReachA.push('Hold the line — no obvious gap to close. Keep notes flowing and ship the in-flight UAT items.');

        return {
            grade: grade,
            composite: composite,
            rationale: rationale,
            toReachA: toReachA,
            metrics: [
                { label: 'IT Owner coverage', pct: ownerPct, n: withOwner, total: n },
                { label: 'Target UAT set', pct: uatPct, n: withUat, total: n },
                { label: 'Target Go-Live set', pct: goLivePct, n: withGoLive, total: n },
                { label: 'Effort (Hrs) set', pct: hoursPct, n: withHours, total: n },
                { label: 'Schedule health', pct: schedulePct, n: n - (overdueUat + overdueGoLive + staleAnalysis), total: n }
            ],
            risks: {
                overdueUat: overdueUat,
                overdueGoLive: overdueGoLive,
                staleAnalysis: staleAnalysis,
                noOwner: n - withOwner
            },
            total: n
        };
    }

    function renderMeetingWrapUp() {
        var s = STATE.meeting.scorecard;
        if (!s) s = STATE.meeting.scorecard = meetingComputeScorecard();
        var nNotes = Object.keys(STATE.meeting.notes).filter(function (k) { return (STATE.meeting.notes[k] || '').trim().length; }).length;
        var nDirty = Object.keys(STATE.dirty).length;
        // Save button is whichever wording best fits what's pending. When
        // notes are queued the user expects "save notes"; when only date /
        // field edits are queued, the older label is misleading.
        var saveLabel;
        if (nNotes > 0 && nDirty > nNotes) saveLabel = 'Save ' + nDirty + ' changes to Agile →';
        else if (nNotes > 0) saveLabel = 'Save ' + nNotes + ' note' + (nNotes === 1 ? '' : 's') + ' to Agile →';
        else if (nDirty > 0) saveLabel = 'Save ' + nDirty + ' change' + (nDirty === 1 ? '' : 's') + ' to Agile →';
        else saveLabel = 'Nothing to save';
        var saveDisabled = (nNotes === 0 && nDirty === 0);
        return ''
            + '<div class="ite2-meet">'
            +   '<div class="ite2-meet-bar">'
            +     '<button class="ite2-meet-wrapbtn is-back" id="iteMeetBackBtn">← Back to review</button>'
            +     '<span class="u-label">Wrap-up</span>'
            +     '<span class="ite2-meet-bar-progress">' + nNotes + ' note' + (nNotes === 1 ? '' : 's') + ' ready · '
            +       nDirty + ' pending edit' + (nDirty === 1 ? '' : 's') + '</span>'
            +     '<span style="flex:1 1 auto;"></span>'
            +     '<button class="ite2-meet-wrapbtn" id="iteMeetSaveNotesBtn"' + (saveDisabled ? ' disabled style="opacity:0.5; cursor:not-allowed;"' : '') + '>' + saveLabel + '</button>'
            +   '</div>'
            +   renderScorecardCard(s)
            +   renderConsolidation()
            + '</div>';
    }

    function renderScorecardCard(s) {
        function bar(m) {
            var fillCls = m.pct >= 80 ? 'hi' : m.pct >= 60 ? '' : m.pct >= 40 ? 'mid' : 'low';
            return '<div class="ite2-sc-bar">'
                + '<span class="ite2-sc-bar-lbl">' + escapeHtml(m.label) + '</span>'
                + '<span class="ite2-sc-bar-val">' + m.pct + '% · ' + m.n + '/' + m.total + '</span>'
                + '<div class="ite2-sc-bar-track"><div class="ite2-sc-bar-fill ' + fillCls + '" style="width:' + m.pct + '%;"></div></div>'
                + '</div>';
        }
        var risks = '';
        if (s.risks.overdueUat) risks += '<span class="ite2-sc-risk red">' + s.risks.overdueUat + ' overdue UAT</span>';
        if (s.risks.overdueGoLive) risks += '<span class="ite2-sc-risk warn">' + s.risks.overdueGoLive + ' overdue Go-Live</span>';
        if (s.risks.staleAnalysis) risks += '<span class="ite2-sc-risk warn">' + s.risks.staleAnalysis + ' stale Analysis</span>';
        if (s.risks.noOwner) risks += '<span class="ite2-sc-risk warn">' + s.risks.noOwner + ' without IT Owner</span>';
        if (!risks) risks = '<span class="ite2-sc-risk">No outstanding red flags 🎯</span>';
        return ''
            + '<div class="ite2-scorecard">'
            +   '<div class="ite2-sc-head">'
            +     '<div>'
            +       '<div class="ite2-sc-grade is-' + s.grade + '">' + s.grade + '</div>'
            +       '<div class="ite2-sc-composite">' + s.composite + ' / 100</div>'
            +     '</div>'
            +     '<div class="ite2-sc-rationale">'
            +       '<h4>Backlog scorecard · ' + s.total + ' active rows</h4>'
            +       '<p>' + escapeHtml(s.rationale) + '</p>'
            +     '</div>'
            +     '<button class="ite2-sc-regrade" id="iteMeetRegrade">Re-grade</button>'
            +   '</div>'
            +   '<div class="ite2-sc-bars">' + s.metrics.map(bar).join('') + '</div>'
            +   '<div class="ite2-sc-risks">' + risks + '</div>'
            +   '<div class="ite2-sc-toA">'
            +     '<h4>To reach an A</h4>'
            +     '<ol>' + s.toReachA.map(function (t) { return '<li>' + escapeHtml(t) + '</li>'; }).join('') + '</ol>'
            +   '</div>'
            +   '<div class="ite2-sc-foot">Deterministic scoring. AI narrative + tailored recommendations require the server-side scorecard proxy (not yet wired); see Meeting-Mode-Spec §7.</div>'
            + '</div>';
    }

    /** Per-recipient consolidation. Mirrors the backend's actual behavior:
     *  one summary email per distinct person (deduped by display name), each
     *  listing every ECN that touches them in this save batch. Requestor &
     *  IT-owner roles on the same person collapse into one summary. Cc to
     *  pdl-plm-admin@sandisk.com is implicit on every email and shown in
     *  the header. */
    function renderConsolidation() {
        var ecns = meetingTouchedEcns();
        if (!ecns.length) {
            return ''
                + '<div class="ite2-consol">'
                +   '<div class="ite2-consol-head"><span class="u-label">Consolidated emails</span></div>'
                +   '<div class="ite2-consol-empty">No pending changes. Go back to the review screen and edit a Target date or capture a note — each person involved will receive ONE summary email listing every ECN they\'re on.</div>'
                + '</div>';
        }
        var groups = meetingGroupByRecipient(ecns);
        var cards = groups.map(renderRecipientSummaryCard).join('');
        return ''
            + '<div class="ite2-consol">'
            +   '<div class="ite2-consol-head">'
            +     '<span class="u-label">Consolidated emails (' + groups.length + ' recipient' + (groups.length === 1 ? '' : 's') + ' · ' + ecns.length + ' ECN' + (ecns.length === 1 ? '' : 's') + ' touched)</span>'
            +     '<span class="ite2-consol-cc">'
            +       (STATE.currentUser && STATE.currentUser.isItTeam
                        ? 'You\'re on the IT team — so the <strong>Requestor</strong> goes in <strong>To</strong> and the <strong>IT Owner</strong> + <strong>pdl-plm-admin@sandisk.com</strong> are on <strong>Cc</strong>.'
                        : 'You\'re editing as a non-IT user — so the <strong>IT Owner</strong> goes in <strong>To</strong> and the <strong>Requestor</strong> + <strong>pdl-plm-admin@sandisk.com</strong> are on <strong>Cc</strong>.')
            +       ' One summary per recipient — Krati owning 3 affected ECNs gets ONE email listing all 3. Sends automatically when Save succeeds.'
            +     '</span>'
            +   '</div>'
            +   cards
            + '</div>';
    }

    /** Group touched ECNs by the primary (To:) recipient and union the
     *  secondary (Cc:) recipients. Mirrors the backend's send algorithm so
     *  the preview matches the actual email behavior. Primary vs. secondary
     *  is determined by the editor's team:
     *  - Editor on IT team (PDL-PLM-admin DL): To = Requestor, Cc = IT Owner.
     *  - Editor NOT on IT team:                 To = IT Owner, Cc = Requestor.
     *  Multiple ECNs that share the same primary collapse into one entry;
     *  their Cc lines union together. Returns an array of group objects
     *  ready for renderRecipientSummaryCard. */
    function meetingGroupByRecipient(ecns) {
        var editorIsItTeam = !!(STATE.currentUser && STATE.currentUser.isItTeam);
        var byKey = {};
        ecns.forEach(function (ecn) {
            var data = meetingChangesForEcn(ecn);
            if (!data) return;
            var row = data.row;
            // Build the candidate recipients for this ECN. Dedup by loginid,
            // fallback to display name.
            function recipientFor(loginId, name) {
                if (!loginId && !name) return null;
                return {
                    loginId: loginId || '',
                    name: name || loginId,
                    key: (loginId || name || '').toLowerCase()
                };
            }
            var reqR = recipientFor(row.requestorLoginId, row.requestor);
            var ownerR = recipientFor(row.itOwnerLoginId, row.itOwner);
            var sameOnEcn = reqR && ownerR && reqR.key === ownerR.key;

            // Pick primary (To) vs. secondary (Cc) per the editor's team.
            var primary, secondary;
            if (editorIsItTeam) {
                primary = reqR || ownerR;
                secondary = (reqR && ownerR && !sameOnEcn) ? ownerR : null;
            } else {
                primary = ownerR || reqR;
                secondary = (reqR && ownerR && !sameOnEcn) ? reqR : null;
            }
            if (!primary) return;

            // Group key = primary loginid alone; Cc'd people don't affect
            // grouping (they're unioned across the group's ECNs).
            var groupKey = primary.key;
            if (!byKey[groupKey]) {
                byKey[groupKey] = {
                    primary: primary,
                    ccMap: {},           // keyed by loginid → recipient
                    items: []
                };
            }
            if (secondary) {
                byKey[groupKey].ccMap[secondary.key] = secondary;
            }
            // Role line on each ECN. Describes the assigned roles (To/Cc is
            // visible in the email header itself).
            var roleLine;
            if (sameOnEcn) {
                roleLine = 'Requestor & IT Owner: <strong>' + escapeHtml(row.requestor) + '</strong>';
            } else {
                roleLine = 'Requestor: <strong>' + escapeHtml(row.requestor || '—')
                    + '</strong> &middot; IT Owner: <strong>' + escapeHtml(row.itOwner || '—') + '</strong>';
            }
            byKey[groupKey].items.push({
                ecn: ecn, row: row, changes: data.changes, note: data.note,
                hasError: data.hasError, roleLine: roleLine
            });
        });
        // Final shape: derive label, sort by To: name for stable preview order.
        var groups = Object.keys(byKey).map(function (k) {
            var g = byKey[k];
            var p = g.primary;
            var toLabel = (p.name || p.loginId) + (p.loginId ? ' (' + p.loginId + ')' : '');
            var ccList = Object.keys(g.ccMap).map(function (kk) {
                var r = g.ccMap[kk];
                return (r.name || r.loginId) + (r.loginId ? ' (' + r.loginId + ')' : '');
            });
            return {
                primary: p,
                toLabel: toLabel,
                ccList: ccList,           // array of "Name (loginid)"
                label: toLabel,           // back-compat for any other reader
                items: g.items
            };
        });
        groups.sort(function (a, b) {
            return String(a.toLabel || '').localeCompare(String(b.toLabel || ''));
        });
        return groups;
    }

    function renderRecipientSummaryCard(group) {
        // Group shape: { primary:{loginId,name}, toLabel, ccList:["Name (id)", …], items:[{ecn,row,changes,note,hasError,roleLine}] }
        var toLabel = group.toLabel || group.label || '—';
        var ccList = group.ccList || [];
        var ccLine = 'pdl-plm-admin@sandisk.com';
        if (ccList.length) ccLine = ccList.join(' &middot; ') + ' &middot; pdl-plm-admin@sandisk.com';
        var n = group.items.length;
        var subject = 'PLM IT Enhancements — ' + n + ' ECN' + (n === 1 ? '' : 's') + ' updated by ' + (STATE.currentUser.displayName || 'PLM IT');
        var itemsHtml = group.items.map(function (it) {
            var errBadge = it.hasError ? '<span class="ite2-consol-diff-err" title="Has a validation error">⚠ needs fix</span>' : '';
            var changesHtml = it.changes.length
                ? '<table>'
                    + '<thead><tr><th>Field</th><th>Previous</th><th>New</th></tr></thead>'
                    + '<tbody>'
                    + it.changes.map(function (c) {
                        return '<tr>'
                            + '<td class="ite2-consol-diff-field">' + escapeHtml(c.field) + '</td>'
                            + '<td class="ite2-consol-diff-old">' + escapeHtml(c.oldVal || '(blank)') + '</td>'
                            + '<td class="ite2-consol-diff-new">' + escapeHtml(c.newVal || '(blank)') + '</td>'
                            + '</tr>';
                      }).join('')
                    + '</tbody></table>'
                : '';
            var noteHtml = it.note
                ? '<div class="ite2-consol-note-block">'
                    + '<div class="ite2-consol-note-label">Meeting note → IT Log</div>'
                    + '<div class="ite2-consol-note-text">' + escapeHtml(it.note) + '</div>'
                  + '</div>'
                : '';
            // Full proposal next to the ECN so the recipient can identify it at
            // a glance — no truncation (the rail card shows the complete text).
            var proposal = (it.row.proposal || '').replace(/\s+/g, ' ').trim();
            var proposalHtml = proposal
                ? ' <span class="ite2-consol-ecn-proposal">— ' + escapeHtml(proposal) + '</span>'
                : '';
            return '<div class="ite2-consol-ecn-block">'
                + '<div class="ite2-consol-ecn-head">'
                +   '<span>' + agileChangeLinkHtml(it.ecn) + proposalHtml + '</span>'
                +   '<span class="ite2-consol-ecn-meta">' + it.roleLine + ' ' + errBadge + '</span>'
                + '</div>'
                + '<div class="ite2-consol-diffs">' + changesHtml + '</div>'
                + noteHtml
                + '</div>';
        }).join('');
        return ''
            + '<div class="ite2-consol-card">'
            +   '<div class="ite2-consol-card-head">'
            +     '<div class="ite2-consol-name">' + escapeHtml(toLabel) + '</div>'
            +     '<span style="font-size:11px;color:#6B7280;">' + n + ' ECN' + (n === 1 ? '' : 's') + '</span>'
            +   '</div>'
            +   '<div class="ite2-consol-emaildraft">'
            +     '<div class="ite2-consol-em-line"><strong>To:</strong> ' + escapeHtml(toLabel) + '</div>'
            +     '<div class="ite2-consol-em-line"><strong>Cc:</strong> ' + ccLine + '</div>'
            +     '<div class="ite2-consol-em-line"><strong>Subject:</strong> ' + escapeHtml(subject) + '</div>'
            +   '</div>'
            +   itemsHtml
            + '</div>';
    }

    /** Distinct list of ECNs touched in this session — any dirty field edit
     *  OR any non-empty captured note. Ordered by the order rows appear in
     *  STATE.rows so the preview cards line up with what the user saw in
     *  the rail. */
    function meetingTouchedEcns() {
        var seen = {};
        Object.keys(STATE.dirty || {}).forEach(function (k) {
            var ecn = k.split('||')[0];
            if (ecn) seen[ecn] = true;
        });
        Object.keys(STATE.meeting.notes || {}).forEach(function (ecn) {
            if ((STATE.meeting.notes[ecn] || '').trim().length) seen[ecn] = true;
        });
        // Preserve grid order — sort by STATE.rows index.
        var rowIdx = {};
        STATE.rows.forEach(function (r, i) { rowIdx[r.ecnNumber] = i; });
        return Object.keys(seen).sort(function (a, b) {
            return (rowIdx[a] == null ? 9999 : rowIdx[a]) - (rowIdx[b] == null ? 9999 : rowIdx[b]);
        });
    }

    /** Collect field-level diffs for the given ECN from STATE.dirty. The
     *  IT Log cell is treated separately (rendered as the meeting note),
     *  unless there's a dirty IT Log entry without a captured note (rare —
     *  someone hand-edited the log in the grid). */
    function meetingChangesForEcn(ecn) {
        var row = STATE.rows.filter(function (r) { return r.ecnNumber === ecn; })[0];
        if (!row) return null;
        var changes = [];
        var hasError = false;
        Object.keys(STATE.dirty).forEach(function (k) {
            if (k.indexOf(ecn + '||') !== 0) return;
            var d = STATE.dirty[k];
            var col = COLUMNS.filter(function (c) { return c.cell === d.cell; })[0];
            if (!col) return;
            // Skip IT Log here — it shows up as the meeting note section.
            if (col.key === 'itLog') {
                if (d.status === 'error') hasError = true;
                return;
            }
            if (d.status === 'error') hasError = true;
            changes.push({
                field: col.label,
                cellName: d.cell,
                oldVal: row[col.key] || '',
                newVal: d.value || '',
                status: d.status,
                msg: d.msg || ''
            });
        });
        return {
            row: row,
            changes: changes,
            note: (STATE.meeting.notes[ecn] || '').trim(),
            hasError: hasError
        };
    }

    function renderEcnNotificationCard(ecn) {
        var data = meetingChangesForEcn(ecn);
        if (!data) return '';
        var row = data.row;
        var changes = data.changes;
        var note = data.note;

        var reqLabel = (row.requestor || '—') + (row.requestorLoginId ? ' (' + row.requestorLoginId + ')' : '');
        var ownerLabel = (row.itOwner || '—') + (row.itOwnerLoginId ? ' (' + row.itOwnerLoginId + ')' : '');
        var changesHtml = changes.length
            ? '<div class="ite2-consol-diffs"><table>'
                + '<thead><tr><th>Field</th><th>Previous</th><th>New</th></tr></thead>'
                + '<tbody>'
                + changes.map(function (c) {
                    var errBadge = c.status === 'error'
                        ? ' <span class="ite2-consol-diff-err" title="' + escapeAttr(c.msg) + '">⚠ validation error</span>'
                        : '';
                    return '<tr>'
                        + '<td class="ite2-consol-diff-field">' + escapeHtml(c.field) + errBadge + '</td>'
                        + '<td class="ite2-consol-diff-old">' + escapeHtml(c.oldVal || '(blank)') + '</td>'
                        + '<td class="ite2-consol-diff-new">' + escapeHtml(c.newVal || '(blank)') + '</td>'
                        + '</tr>';
                  }).join('')
                + '</tbody></table></div>'
            : '';
        var noteHtml = note
            ? '<div class="ite2-consol-note-block">'
                + '<div class="ite2-consol-note-label">Meeting note → IT Log</div>'
                + '<div class="ite2-consol-note-text">' + escapeHtml(note) + '</div>'
              + '</div>'
            : '';
        var errorBanner = data.hasError
            ? '<div class="ite2-consol-error-banner">Has a validation error — fix it on the review screen before saving, or this ECN will be excluded from the batch.</div>'
            : '';

        return ''
            + '<div class="ite2-consol-card" data-ecn="' + escapeAttr(ecn) + '">'
            +   '<div class="ite2-consol-card-head">'
            +     '<div class="ite2-consol-name">' + agileChangeLinkHtml(ecn) + '</div>'
            +     '<span style="font-size:11px;color:#6B7280;">' + (changes.length + (note ? 1 : 0)) + ' change' + ((changes.length + (note ? 1 : 0)) === 1 ? '' : 's') + '</span>'
            +   '</div>'
            +   '<div class="ite2-consol-emaildraft">'
            +     '<div class="ite2-consol-em-line"><strong>To:</strong> ' + escapeHtml(reqLabel) + ' &middot; ' + escapeHtml(ownerLabel) + '</div>'
            +     '<div class="ite2-consol-em-line"><strong>Cc:</strong> pdl-plm-admin@sandisk.com</div>'
            +     '<div class="ite2-consol-em-line"><strong>Subject:</strong> PLM IT Enhancements — ' + escapeHtml(ecn) + ' updated by ' + escapeHtml(STATE.currentUser.displayName || 'PLM IT') + '</div>'
            +   '</div>'
            +   errorBanner
            +   changesHtml
            +   noteHtml
            + '</div>';
    }

    function renderConsolidationCard(name, items, by) {
        var displayName = name;
        var email = synthEmailFromDisplayName(name);
        var counterpartLabel = (by === 'requestor') ? 'IT Owner' : 'Requestor';
        var subject = 'PLM IT Enhancements — meeting follow-up (' + items.length + ' item' + (items.length === 1 ? '' : 's') + ')';
        var bodyText = buildConsolEmailBody(name, items, by);
        // mailto: encodes to;cc;subject;body.
        var mailto = 'mailto:' + encodeURIComponent(email)
            + '?cc=' + encodeURIComponent('pdl-plm-admin@sandisk.com')
            + '&subject=' + encodeURIComponent(subject)
            + '&body=' + encodeURIComponent(bodyText);
        var itemsHtml = items.map(function (it) {
            var counterpart = (by === 'requestor') ? (it.row.itOwner || '—') : (it.row.requestor || '—');
            return '<div class="ite2-consol-item">'
                + '<div class="ite2-consol-item-head">'
                +   '<span class="ite2-consol-item-ecn">' + agileChangeLinkHtml(it.ecn) + '</span>'
                +   '<span class="ite2-consol-item-status">' + escapeHtml(it.row.status || '—') + ' · ' + escapeHtml(counterpartLabel + ': ' + counterpart) + '</span>'
                + '</div>'
                + '<div class="ite2-consol-item-note">' + escapeHtml(it.note) + '</div>'
                + '</div>';
        }).join('');
        // Encode the mailto so a copy-to-clipboard handler can read it back.
        return ''
            + '<div class="ite2-consol-card" data-name="' + escapeAttr(name) + '">'
            +   '<div class="ite2-consol-card-head">'
            +     '<div><div class="ite2-consol-name">' + escapeHtml(displayName) + '</div>'
            +       '<div class="ite2-consol-email">' + escapeHtml(email) + ' · cc pdl-plm-admin@sandisk.com</div>'
            +     '</div>'
            +     '<span style="font-size:11px; color:#6B7280;">' + items.length + ' item' + (items.length === 1 ? '' : 's') + '</span>'
            +   '</div>'
            +   itemsHtml
            +   '<div class="ite2-consol-actions">'
            +     '<a href="' + escapeAttr(mailto) + '">Draft email</a>'
            +     '<button data-copy="1" data-to="' + escapeAttr(email) + '" data-subj="' + escapeAttr(subject) + '" data-body="' + escapeAttr(bodyText) + '">Copy email</button>'
            +   '</div>'
            + '</div>';
    }

    /** Synthesize first.last@sandisk.com from a "Last, First" display name.
     *  Spec §8: this is a placeholder until the AD/Exchange directory lookup
     *  is wired. Falls back to a simple slug of the whole name. */
    function synthEmailFromDisplayName(name) {
        if (!name) return 'unknown@sandisk.com';
        var n = name.trim();
        // "Last, First" → first.last
        var m = n.match(/^([^,]+),\s*(.+)$/);
        if (m) {
            var last = m[1].trim().split(/\s+/).pop();
            var first = m[2].trim().split(/\s+/)[0];
            return slugify(first) + '.' + slugify(last) + '@sandisk.com';
        }
        // "First Last" → first.last
        var parts = n.split(/\s+/);
        if (parts.length >= 2) return slugify(parts[0]) + '.' + slugify(parts[parts.length - 1]) + '@sandisk.com';
        return slugify(n) + '@sandisk.com';
    }
    function slugify(s) { return String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, ''); }

    /** Build the consolidated email body for one group. Plain text — fine
     *  for mailto + clipboard. */
    function buildConsolEmailBody(name, items, by) {
        var greeting = (function () {
            // "Last, First" → "Hi First,"
            var m = String(name || '').match(/^[^,]+,\s*([^\s,]+)/);
            return m ? ('Hi ' + m[1] + ',') : ('Hi ' + (name || 'team') + ',');
        })();
        var lines = [];
        lines.push(greeting);
        lines.push('');
        lines.push('Quick recap of the IT Enhancements we discussed this week:');
        lines.push('');
        items.forEach(function (it) {
            var counterpart = (by === 'requestor') ? ('IT Owner: ' + (it.row.itOwner || '—')) : ('Requestor: ' + (it.row.requestor || '—'));
            lines.push('• ' + it.ecn + ' (' + (it.row.status || '—') + ') — ' + counterpart);
            if (it.row.proposal) lines.push('    Proposal: ' + truncate(it.row.proposal, 180));
            lines.push('    Note: ' + it.note.replace(/\n+/g, ' '));
            lines.push('');
        });
        lines.push('Reply to this thread with any corrections or follow-ups.');
        lines.push('');
        lines.push('Thanks,');
        lines.push(STATE.currentUser.displayName || 'PLM IT');
        lines.push('(cc: pdl-plm-admin@sandisk.com)');
        return lines.join('\n');
    }
    function truncate(s, n) {
        s = String(s || '').replace(/\s+/g, ' ').trim();
        return s.length > n ? (s.substring(0, n - 1) + '…') : s;
    }

    function wireWrapUpHandlers() {
        var back = document.getElementById('iteMeetBackBtn');
        if (back) back.addEventListener('click', function () {
            STATE.meeting.wrappedUp = false;
            renderMeetingMode();
        });
        var save = document.getElementById('iteMeetSaveNotesBtn');
        if (save) save.addEventListener('click', function () {
            // Just call the page's batch save — notes are already pending.
            iteSaveAll();
        });
        var re = document.getElementById('iteMeetRegrade');
        if (re) re.addEventListener('click', function () {
            STATE.meeting.scorecard = meetingComputeScorecard();
            renderMeetingMode();
        });
        document.querySelectorAll('.ite2-consol-toggle button[data-by]').forEach(function (b) {
            b.addEventListener('click', function () {
                STATE.meeting.consolBy = b.getAttribute('data-by');
                renderMeetingMode();
            });
        });
        // Copy-email buttons.
        document.querySelectorAll('.ite2-consol-actions button[data-copy]').forEach(function (b) {
            b.addEventListener('click', function () {
                var to = b.getAttribute('data-to');
                var subj = b.getAttribute('data-subj');
                var body = b.getAttribute('data-body');
                var payload = 'To: ' + to + '\nCc: pdl-plm-admin@sandisk.com\nSubject: ' + subj + '\n\n' + body;
                copyToClipboard(payload).then(function () {
                    showToast({ kind: 'success', text: 'Copied — paste into your mail client.' });
                }).catch(function () {
                    showToast({ kind: 'error', text: 'Could not access clipboard. Use Draft email instead.' });
                });
            });
        });
    }

    // ============================================================
    // Draft persistence (autosave + recovery)
    // ============================================================
    //
    // Every change to STATE.dirty or STATE.meeting.notes gets debounced
    // and POSTed to /api/it-enhancements/draft. The server stores one
    // file per user under ./drafts/<loginid>-active.json. On next iteInit
    // we GET the draft (if any) and prompt the user to recover.
    //
    // Auto-discard: after a fully-successful save-batch the server-side
    // controller deletes the draft itself (in saveBatch), so on next
    // login we start from a clean slate against the latest Agile state.

    var DRAFT_SAVE_DEBOUNCE_MS = 1000;
    var draftSaveTimer = null;

    /** Build the snapshot we'll POST as the draft body. Includes every
     *  pending edit + Meeting Mode captured-notes + focus state. We do NOT
     *  include ephemeral UI state (wrappedUp, scorecard, popovers). */
    function buildDraftSnapshot() {
        return {
            dirty: STATE.dirty,
            meeting: {
                notes: STATE.meeting.notes,
                selReqs: STATE.meeting.selReqs,
                focusPill: STATE.meeting.focusPill,
                focusedEcn: STATE.meeting.focusedEcn,
                pinnedEcns: STATE.meeting.pinnedEcns
            }
        };
    }

    /** Mark the draft as dirty — schedules a debounced POST. Called from
     *  writeCellEdit (via the saveDraft hook below), commitMeetingNote,
     *  and discardPending. */
    function scheduleDraftSave() {
        // Skip the very first auto-save until the recovery prompt has been
        // resolved — otherwise we'd overwrite the recoverable draft with
        // empty state before the user has had a chance to recover.
        if (!STATE.draftLoaded) return;
        if (draftSaveTimer) clearTimeout(draftSaveTimer);
        draftSaveTimer = setTimeout(saveDraftNow, DRAFT_SAVE_DEBOUNCE_MS);
    }

    function saveDraftNow() {
        draftSaveTimer = null;
        var snap = buildDraftSnapshot();
        var nDirty = Object.keys(snap.dirty || {}).length;
        var nNotes = Object.keys((snap.meeting && snap.meeting.notes) || {})
                .filter(function (k) { return (snap.meeting.notes[k] || '').trim().length; }).length;
        // Nothing to save → ask the server to delete any existing draft so
        // we don't keep stale state around. (Server tolerates DELETE on
        // missing files.)
        if (nDirty === 0 && nNotes === 0) {
            fetch('/api/it-enhancements/draft', {
                method: 'DELETE', credentials: 'same-origin'
            }).catch(function () {/* best-effort */});
            return;
        }
        fetch('/api/it-enhancements/draft', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(snap)
        }).catch(function () {/* best-effort; we'll retry on next edit */});
    }

    /** Load the user's draft on iteInit (after rows have landed). If there
     *  is one, show a recovery prompt; if not, mark draftLoaded so
     *  subsequent edits start autosaving immediately. */
    function loadDraftAndPrompt() {
        fetch('/api/it-enhancements/draft', { credentials: 'same-origin' })
            .then(function (r) {
                if (r.status === 404) return null;
                if (!r.ok) return null;
                return r.json();
            })
            .then(function (draft) {
                if (!draft) {
                    STATE.draftLoaded = true;
                    return;
                }
                STATE.draftMeta = {
                    fileName: draft.fileName,
                    savedAt: draft.savedAt,
                    savedAtMs: draft.savedAtMs
                };
                openRecoveryPrompt(draft);
            })
            .catch(function () { STATE.draftLoaded = true; });
    }

    /** Recovery modal. Shows file name, when it was saved, how many edits
     *  + notes are in it, and offers Recover / Discard. Recover loads the
     *  draft into STATE; Discard fires a DELETE so the user starts fresh. */
    function openRecoveryPrompt(draft) {
        var nDirty = Object.keys((draft && draft.dirty) || {}).length;
        var nNotes = Object.keys(((draft && draft.meeting) || {}).notes || {})
                .filter(function (k) { return (draft.meeting.notes[k] || '').trim().length; }).length;
        var ago = humanAgo(draft.savedAtMs || Date.now());
        var overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed; inset:0; background:rgba(0,0,0,0.35); z-index:10001; display:flex; align-items:center; justify-content:center;';
        overlay.innerHTML = ''
            + '<div style="background:#fff; width:480px; max-width:92vw; border-radius:8px; padding:22px 26px; box-shadow:0 12px 36px rgba(0,0,0,0.18); font-family:\'IBM Plex Sans\',sans-serif;">'
            +   '<div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">Meeting Mode</div>'
            +   '<h3 style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:20px; font-weight:500; margin:2px 0 8px;">Recover unsaved edits?</h3>'
            +   '<div style="font-size:13px; color:#0F1720; line-height:1.5; margin-bottom:14px;">'
            +     'Found an unsaved meeting draft on the server:<br>'
            +     '<div style="margin-top:8px; padding:8px 12px; background:#FAFAF7; border-left:3px solid #4a6fa5; border-radius:0 4px 4px 0;">'
            +       '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11.5px; color:#4a6fa5; font-weight:600;">' + escapeHtml(draft.fileName || '(draft)') + '</div>'
            +       '<div style="font-size:11.5px; color:#6B7280; margin-top:4px;">Saved ' + escapeHtml(ago) + ' · <strong>' + nDirty + '</strong> pending edit' + (nDirty === 1 ? '' : 's') + ' · <strong>' + nNotes + '</strong> note' + (nNotes === 1 ? '' : 's') + '</div>'
            +     '</div>'
            +     '<div style="margin-top:10px; font-size:12px; color:#6B7280;">Recover to continue where you left off, or Discard to start fresh from the latest Agile state.</div>'
            +   '</div>'
            +   '<div style="display:flex; justify-content:flex-end; gap:8px;">'
            +     '<button id="iteRecoverDiscard" style="padding:7px 14px; font-size:13px; background:#fff; color:#0F1720; border:1px solid #E8E6DF; border-radius:4px; cursor:pointer;">Discard</button>'
            +     '<button id="iteRecoverRestore" style="padding:7px 16px; font-size:13px; background:#0F1720; color:#fff; border:1px solid #0F1720; border-radius:4px; cursor:pointer; font-weight:600;">Recover</button>'
            +   '</div>'
            + '</div>';
        document.body.appendChild(overlay);

        function close() { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); }
        overlay.querySelector('#iteRecoverRestore').addEventListener('click', function () {
            applyDraftToState(draft);
            STATE.draftLoaded = true;
            close();
            // Land the user inside Meeting Mode if the draft has meeting
            // notes — that's where they were when they last saved.
            var nm = Object.keys(((draft && draft.meeting) || {}).notes || {}).length;
            if (nm > 0) {
                iteSetSubTab('meeting');
            } else {
                refreshDirtyBadge();
                render();
            }
            showToast({ kind: 'success', text: 'Recovered ' + nDirty + ' pending edit' + (nDirty === 1 ? '' : 's') + ' and ' + nNotes + ' note' + (nNotes === 1 ? '' : 's') + '.' });
        });
        overlay.querySelector('#iteRecoverDiscard').addEventListener('click', function () {
            fetch('/api/it-enhancements/draft', { method: 'DELETE', credentials: 'same-origin' })
                .catch(function () {/* best-effort */});
            STATE.draftLoaded = true;
            STATE.draftMeta = null;
            close();
            showToast({ kind: 'pending', text: 'Draft discarded.' });
        });
        // Block esc/outside-click — force an explicit choice so we don't
        // leave the user in a weird state.
    }

    /** Merge a recovered draft into STATE. Replaces dirty + meeting subset;
     *  every other state stays put. */
    function applyDraftToState(draft) {
        if (!draft) return;
        if (draft.dirty && typeof draft.dirty === 'object') {
            STATE.dirty = draft.dirty;
        }
        if (draft.meeting && typeof draft.meeting === 'object') {
            if (draft.meeting.notes) STATE.meeting.notes = draft.meeting.notes;
            if (draft.meeting.selReqs) STATE.meeting.selReqs = draft.meeting.selReqs;
            if (draft.meeting.focusPill) STATE.meeting.focusPill = draft.meeting.focusPill;
            if (draft.meeting.focusedEcn) STATE.meeting.focusedEcn = draft.meeting.focusedEcn;
            if (draft.meeting.pinnedEcns) STATE.meeting.pinnedEcns = draft.meeting.pinnedEcns;
        }
        renderSubNav();
    }

    /** Human "5 minutes ago" / "2 hours ago" / "yesterday" string. Best-effort
     *  — falls back to the ISO timestamp if math goes sideways. */
    function humanAgo(savedAtMs) {
        var delta = Date.now() - savedAtMs;
        if (isNaN(delta) || delta < 0) return new Date(savedAtMs).toLocaleString();
        var sec = Math.floor(delta / 1000);
        if (sec < 60) return 'just now';
        var min = Math.floor(sec / 60);
        if (min < 60) return min + ' minute' + (min === 1 ? '' : 's') + ' ago';
        var hr = Math.floor(min / 60);
        if (hr < 24) return hr + ' hour' + (hr === 1 ? '' : 's') + ' ago';
        var day = Math.floor(hr / 24);
        if (day === 1) return 'yesterday';
        if (day < 30) return day + ' days ago';
        return new Date(savedAtMs).toLocaleString();
    }

    function copyToClipboard(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            return navigator.clipboard.writeText(text);
        }
        // Fallback for older browsers / file:// contexts.
        return new Promise(function (resolve, reject) {
            try {
                var ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'fixed'; ta.style.opacity = '0';
                document.body.appendChild(ta);
                ta.select();
                document.execCommand('copy');
                document.body.removeChild(ta);
                resolve();
            } catch (e) { reject(e); }
        });
    }

    // Export entrypoints
    window.iteInit = iteInit;
    window.iteRefresh = iteRefresh;
    window.iteForceRefresh = iteForceRefresh;
    window.iteSaveAll = iteSaveAll;
    window.iteSetDensity = iteSetDensity;
    window.iteAgileSigninSubmit = iteAgileSigninSubmit;
    window.iteAgileSigninCancel = iteAgileSigninCancel;
    // Reusable per-user Agile sign-in for other modules (Meeting Mode v2).
    // Opens the proper password modal; cb(true) on verified sign-in, cb(false)
    // on cancel. One-shot.
    window.iteOpenAgileSignin = function (cb) { AGILE_SIGNIN_CB = cb || null; openAgileSigninModal(null); };
    // Expose the Agile webclient base so Meeting Mode v2 builds ECN links the
    // same way the grid does (STATE.meta first, then the env fallback).
    window.iteAgileBaseUrl = agileBaseUrl;
    window.iteSendSignoff = iteSendSignoff;
    window.iteSetSubTab = iteSetSubTab;
    window.iteBulkExtend = iteBulkExtend;
    window.iteBulkAddLogNote = iteBulkAddLogNote;
    window.iteBulkClearSelection = iteBulkClearSelection;
    window.openRestartEcnDialog = openRestartEcnDialog;
    window.openRestartHistory = openRestartHistory;
    window.addEventListener('hashchange', iteHandleRestartDeepLink);
    setTimeout(iteHandleRestartDeepLink, 500);   // handle a deep link present at load

    // Meeting Mode v2 callbacks. _syncPill flips the top sub-nav after a
    // wrap-up send (live session → records); _onRefresh folds the grade
    // drawer's force-refresh back into the grid's STATE so All Enhancements
    // stays in sync with what Meeting Mode just re-read.
    if (window.MeetingMode) {
        window.MeetingMode._syncPill = function (tab) { iteSetSubTab(tab); };
        window.MeetingMode._onRefresh = function (resp) {
            if (resp && resp.rows) { try { absorbResponse(resp); } catch (e) {} }
        };
    }
})();
