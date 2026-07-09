// Global export debounce — prevents double-click OOM on concurrent exports
// Client-side: prevents same user double-clicking
// Server-side: ExportRateLimitFilter returns 429 if another user is already exporting
var _exportLocked = false;
function guardExport(fn) {
    if (_exportLocked) return;
    _exportLocked = true;
    document.querySelectorAll('.btn-export').forEach(function(b) { b.disabled = true; b.style.opacity = '0.5'; });
    try { fn(); } catch(e) {}
    setTimeout(function() {
        _exportLocked = false;
        document.querySelectorAll('.btn-export').forEach(function(b) { b.disabled = false; b.style.opacity = ''; });
    }, 5000);
}
// Handle 429 from server for fetch-based exports (SKU)
function checkExport429(res) {
    if (res.status === 429) {
        showCustomAlert('PLM Toolkit', 'Another export is in progress. Please wait a moment and try again.');
        return false;
    }
    return true;
}

var currentResults = [];
var currentSort = { field: 'timestamp', dir: 'desc' };
var expandedRows = new Set();
var currentUserEmail = '';
var columnFilters = {};
var selectedDates = null; // null = all dates selected
var searchStartTime = 0;

// === Day/Night Mode ===
function initNightMode() {
    var saved = localStorage.getItem('plm-night-mode');
    var isNight;
    if (saved === 'day') { isNight = false; }
    else if (saved === 'night') { isNight = true; }
    else {
        // Auto-detect: night = 6 PM to 6 AM
        var hour = new Date().getHours();
        isNight = (hour >= 18 || hour < 6);
    }
    applyNightMode(isNight);
}

function toggleNightMode() {
    var isNight = !document.body.classList.contains('night-mode');
    applyNightMode(isNight);
    localStorage.setItem('plm-night-mode', isNight ? 'night' : 'day');
}

function applyNightMode(isNight) {
    if (isNight) {
        document.body.classList.add('night-mode');
    } else {
        document.body.classList.remove('night-mode');
    }
    var toggle = document.getElementById('nightModeToggle');
    if (toggle) toggle.textContent = isNight ? '☀️ Day' : '🌙 Night';
}

initNightMode();

// === Session Timer ===
// PT-56 fix: previously this was a 30-min HARD CAP from page load with no
// activity reset and no warning. Jimmy lost feedback he was typing because
// the timer hit 0 mid-form. Now:
//   1. SESSION_MAX is 60 min (server side keeps the JSESSIONID for 2h).
//   2. Real user activity (click/keydown/scroll) bumps sessionStartTime so
//      the timer is a sliding window — anyone actively using the tool never
//      hits the cap.
//   3. At 5 min remaining, a top-of-page banner offers "Stay logged in" with
//      a one-click extend (no full page reload, no lost form data).
var sessionStartTime = Date.now();
var SESSION_MAX = 60 * 60 * 1000;
var sessionWarningShown = false;

// Activity tracking — reset the timer when the user is clearly engaged.
// Debounced to once per 30 s so we don't thrash on mousemove during reading.
(function () {
    var lastReset = Date.now();
    function bump() {
        var now = Date.now();
        if (now - lastReset < 30 * 1000) return;
        lastReset = now;
        sessionStartTime = now;
        // If the warning banner was up, hide it — the user is plainly active.
        var banner = document.getElementById('sessionWarningBanner');
        if (banner) banner.remove();
        sessionWarningShown = false;
    }
    ['mousedown','keydown','touchstart'].forEach(function (evt) {
        document.addEventListener(evt, bump, { passive: true, capture: true });
    });
})();

window.dismissSessionWarningBanner = function () {
    var b = document.getElementById('sessionWarningBanner');
    if (b) b.remove();
    // Don't reset sessionWarningShown — banner re-renders if remaining drops further.
};

window.stayLoggedIn = function () {
    sessionStartTime = Date.now();
    sessionWarningShown = false;
    var b = document.getElementById('sessionWarningBanner');
    if (b) b.remove();
    // Explicit heartbeat so the server-side session is kept warm too.
    fetch('/api/auth/session', { credentials: 'same-origin', cache: 'no-store' })
        .catch(function () { /* ignore — local timer is already reset */ });
};

function showSessionWarningBanner(secsLeft) {
    if (sessionWarningShown) return;
    sessionWarningShown = true;
    var b = document.createElement('div');
    b.id = 'sessionWarningBanner';
    b.style.cssText = 'position:fixed; top:0; left:0; right:0; z-index:9999; background:#fff3cd; color:#856404;'
        + ' border-bottom:1px solid #ffe69a; padding:10px 16px; font-size:13px; display:flex; align-items:center; gap:12px;'
        + ' font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;';
    b.innerHTML =
        '<span>&#9888; Your session will expire in <strong id="sessionWarningCount">' + Math.ceil(secsLeft) + 's</strong>.'
        + ' Want to stay logged in?</span>'
        + '<button onclick="stayLoggedIn()" style="background:#856404; color:#fff; border:none; padding:5px 14px; border-radius:4px; font-size:12px; font-weight:600; cursor:pointer;">Stay logged in</button>'
        + '<span style="flex:1;"></span>'
        + '<a href="#" onclick="dismissSessionWarningBanner(); return false;" style="color:#856404; text-decoration:none; font-size:18px; line-height:1;">&times;</a>';
    document.body.appendChild(b);
}

setInterval(function() {
    var el = document.getElementById('sessionTimer');
    var elapsed = Date.now() - sessionStartTime;
    var remaining = Math.max(0, SESSION_MAX - elapsed);
    var mins = Math.floor(remaining / 60000);
    var secs = Math.floor((remaining % 60000) / 1000);

    if (el) {
        if (remaining <= 0) {
            el.innerHTML = '<span style="color:#dc3545; font-weight:600;">Expired</span>';
        } else if (mins < 5) {
            el.innerHTML = '<span style="color:#ffc107; font-weight:600;">' + mins + ':' + (secs < 10 ? '0' : '') + secs + '</span>';
        } else {
            el.innerHTML = mins + 'm';
        }
    }

    // Soft-expiry warning at 5 min remaining (PT-56). Update countdown if banner already showing.
    if (remaining > 0 && remaining <= 5 * 60 * 1000) {
        if (!sessionWarningShown) {
            showSessionWarningBanner(remaining / 1000);
        } else {
            var c = document.getElementById('sessionWarningCount');
            if (c) c.textContent = mins + 'm ' + (secs < 10 ? '0' : '') + secs + 's';
        }
    }
}, 1000);

// Check API response for expired session — redirect to login if 401
function checkAuth(res) {
    if (res.status === 401) {
        showSessionExpiredModal();
        throw new Error('Session expired');
    }
    return res;
}

// === Session keep-alive heartbeat (PT-33) ===
// Server session timeout is 2 h. Without a heartbeat, any tab the user reads
// for 2 h without clicking (or any tab they alt-tabbed away from in the
// middle of a workflow) gets kicked out, even though they were "actively
// using" the tool. We ping /api/auth/session every 5 min while the page is
// visible so an active session stays warm. Hidden background tabs don't
// ping, so a forgotten tab will eventually time out as intended.
//
// PIGGYBACK: deploy-detection (2026-06-15). Same heartbeat fetches
// /api/admin/build-info to grab the running JAR's SHA. When the SHA changes
// mid-session (= a deploy.bat landed a new JAR), we show a friendly
// auto-reload overlay instead of the scary "Session Expired" modal. With
// Tomcat PersistentManager wired in TomcatSessionPersistenceConfig, the
// JSESSIONID survives the restart — the reload just refreshes the page so
// the user sees the new build's static assets without a re-login.
(function () {
    var HEARTBEAT_MS = 5 * 60 * 1000;          // 5 min — well under the 2 h server timeout
    var lastPing = 0;
    var initialBuildSha = null;       // captured on first build-info fetch
    var reloadScheduled = false;      // guard against double-fire

    function maybePing() {
        if (document.visibilityState !== 'visible') return;
        var now = Date.now();
        if (now - lastPing < HEARTBEAT_MS) return;
        lastPing = now;
        fetch('/api/auth/session', { credentials: 'same-origin', cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d && d.authenticated === false) {
                    // Session was already gone (heavy reset, persistence file deleted,
                    // or another tab logged out). Show the same modal the normal 401
                    // path uses so the user picks the moment to re-auth instead of
                    // getting bounced mid-action.
                    if (typeof showSessionExpiredModal === 'function') showSessionExpiredModal();
                }
            })
            .catch(function () { /* network blip — try again on next tick */ });
        // Build-SHA probe (best-effort, never blocks the session ping).
        fetch('/api/admin/build-info', { credentials: 'same-origin', cache: 'no-store' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (info) {
                if (!info || !info.jarSha256) return;
                if (initialBuildSha === null) {
                    initialBuildSha = info.jarSha256;
                    return;
                }
                if (info.jarSha256 !== initialBuildSha && !reloadScheduled) {
                    reloadScheduled = true;
                    showDeployReloadOverlay(info);
                }
            })
            .catch(function () { /* ignore */ });
    }
    // Re-ping when the tab regains focus so a user who alt-tabbed back to a
    // 4-hour-old session refreshes immediately rather than waiting up to 5 min.
    document.addEventListener('visibilitychange', maybePing);
    window.addEventListener('focus', maybePing);
    setInterval(maybePing, 60 * 1000);  // check every minute; ping only if 5+ min elapsed and tab is visible
    // First ping fires a minute after page load — no need to ping the moment
    // the page loads because the initial /api/auth/session call from the page
    // load already touched the session.
})();

/**
 * Friendly overlay shown when the heartbeat detects the JAR SHA changed
 * mid-session (= a deploy landed). Auto-reloads after 2 seconds so the user
 * picks up the new build's static assets. With session persistence on,
 * the reload doesn't kick them back to login.
 */
function showDeployReloadOverlay(info) {
    if (document.getElementById('deployReloadOverlay')) return;
    var overlay = document.createElement('div');
    overlay.id = 'deployReloadOverlay';
    overlay.style.cssText = 'position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:10001; display:flex; align-items:center; justify-content:center; font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;';
    var version = (info && info.version) ? (' v' + info.version) : '';
    overlay.innerHTML =
        '<div style="background:#fff; max-width:420px; padding:22px 26px; border-radius:8px; box-shadow:0 12px 36px rgba(0,0,0,0.18);">' +
            '<div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280; margin-bottom:4px;">PLM Toolkit</div>' +
            '<h3 style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:20px; font-weight:500; margin:0 0 8px; color:#0F1720;">New build deployed</h3>' +
            '<p style="font-size:13px; color:#0F1720; line-height:1.5; margin:0 0 14px;">A fresh' + version + ' just landed on the server. Reloading the page in <span id="deployReloadCount">2</span>s so you pick up the latest static files. Your session stays valid — no need to log in again.</p>' +
            '<div style="text-align:right;">' +
                '<button onclick="reloadForDeployNow()" style="padding:7px 14px; font-size:13px; background:#0F1720; color:#fff; border:0; border-radius:4px; cursor:pointer; font-weight:600;">Reload now</button>' +
            '</div>' +
        '</div>';
    document.body.appendChild(overlay);
    var remaining = 2;
    var tick = setInterval(function () {
        remaining--;
        var c = document.getElementById('deployReloadCount');
        if (c) c.textContent = remaining;
        if (remaining <= 0) {
            clearInterval(tick);
            window.location.reload();
        }
    }, 1000);
}
function reloadForDeployNow() {
    window.location.reload();
}

/**
 * Soft-redirect on session expiry: shows a modal explaining what happened
 * (usually a JAR restart or 30+ min inactivity) instead of jerking the user
 * to the login page mid-action. They can copy any in-progress form data
 * before clicking "Log in again".
 */
function showSessionExpiredModal() {
    if (document.getElementById('sessionExpiredModal')) return; // already shown
    var modal = document.createElement('div');
    modal.id = 'sessionExpiredModal';
    modal.style.cssText = 'position:fixed; inset:0; background:rgba(0,0,0,0.5); z-index:10000; display:flex; align-items:center; justify-content:center;';
    modal.innerHTML =
        '<div style="background:white; border-radius:8px; padding:24px 32px; max-width:440px; box-shadow:0 6px 20px rgba(0,0,0,0.25); position:relative;">' +
            '<a href="#" onclick="dismissSessionExpiredModal(); return false;" style="position:absolute; top:8px; right:14px; font-size:18px; color:#888; text-decoration:none;" title="Dismiss (so you can copy form data first)">&times;</a>' +
            '<h3 style="margin:0 0 10px; color:#1a3a5c;">Session Expired</h3>' +
            '<p style="margin:0 0 12px; color:#555; line-height:1.5; font-size:14px;">Your session is no longer valid. This usually means the server was restarted, or you have been inactive for 30+ minutes.</p>' +
            '<p style="margin:0 0 18px; color:#888; font-size:12px;">Tip: dismiss this dialog (X) if you need to copy any form data before logging in again.</p>' +
            '<div style="text-align:right;">' +
                '<button onclick="dismissSessionExpiredModal()" style="margin-right:8px; padding:8px 14px; border:1px solid #d0d5dd; background:white; color:#555; border-radius:6px; cursor:pointer; font-size:13px;">Dismiss</button>' +
                '<button onclick="window.location.href=\'/login.html\'" style="padding:8px 16px; border:none; background:#1a3a5c; color:white; border-radius:6px; cursor:pointer; font-size:13px; font-weight:600;">Log in again</button>' +
            '</div>' +
        '</div>';
    document.body.appendChild(modal);
}

function dismissSessionExpiredModal() {
    var m = document.getElementById('sessionExpiredModal');
    if (m && m.parentNode) m.parentNode.removeChild(m);
}

// ----- Item Number input normalization -----
// User feedback (Vikas Singh): pasting from Excel arrives space- or newline-separated;
// pasting from a flat text file arrives one-per-line. Normalize both into the comma-
// separated form the backend expects, on paste and on blur, across every Item Number
// input in the app.
function normalizeItemList(text) {
    if (!text) return '';
    return text.split(/[\s,;]+/).map(function(s){return s.trim();})
               .filter(function(s){return s.length > 0;}).join(', ');
}

function attachItemInputNormalizer(el) {
    if (!el || el._itemNormAttached) return;
    el._itemNormAttached = true;
    el.addEventListener('paste', function() {
        // Let the default paste happen first, then normalize on the next tick.
        setTimeout(function() { el.value = normalizeItemList(el.value); }, 0);
    });
    el.addEventListener('blur', function() {
        var n = normalizeItemList(el.value);
        if (n !== el.value) el.value = n;
    });
}

document.addEventListener('DOMContentLoaded', function() {
    // Wire up every known item-list input so paste-from-Excel works.
    var ids = ['bomItemInput', 'partsItemInput', 'agileManualItems',
               'historyItemInput', 'skuItemInput'];
    ids.forEach(function(id) { attachItemInputNormalizer(document.getElementById(id)); });
});

document.addEventListener('DOMContentLoaded', function() {
    // Check session on page load
    fetch('/api/auth/session')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (!data.authenticated) {
                window.location.href = '/login.html';
                return;
            }
            currentUserEmail = data.email || '';
            // Agile webclient base for every tab's deep-links — env-driven so QA
            // points at plmqa.sandisk.com. Set before any tab renders.
            window.AGILE_WEBCLIENT_URL = data.agileWebclientUrl || 'https://plm.sandisk.com/Agile';
            var greeting = document.getElementById('userGreeting');
            if (greeting) greeting.textContent = data.displayName || data.username;
            // Surface admin flag and username for per-action gating
            window.extIsAdmin = !!data.isPlmAdmin;
            window.extIsContributor = !!data.isExtensionContributor;
            window.currentUsername = data.username || '';
            window.currentDisplayName = data.displayName || data.username || '';
            if (data.buildLabel) {
                var bl = document.getElementById('buildLabel');
                if (bl) bl.textContent = 'Build: ' + data.buildLabel;
                // Topbar build pill (HANDOFF-ecn-improvements #1) — version on the
                // pill, full data-source subhead in the title attr (already set in
                // index.html). Append the formatted build label to the existing
                // tooltip so hover reveals build+source together.
                var bp = document.getElementById('buildPill');
                if (bp) {
                    bp.textContent = data.buildLabel.split('\u00b7')[0].trim() || data.buildLabel;
                    var existingTitle = bp.getAttribute('title') || '';
                    bp.setAttribute('title', existingTitle + '\n\nBuild: ' + data.buildLabel);
                }
                // BETA banner version-key: re-evaluate now that we have a real version.
                // Strip the leading 'v' so the key matches the bare version string.
                var ver = (data.buildLabel.split('\u00b7')[0] || '').trim().replace(/^v/i, '');
                window.appBuildVersion = ver;
                if (typeof npApplyBetaState === 'function') npApplyBetaState(ver);
            }
            if (typeof renderInstanceIndicator === 'function') {
                renderInstanceIndicator({
                    label: data.instanceLabel,
                    banner: data.instanceBanner,
                    bannerNonprod: data.instanceBannerNonprod,
                    prodData: data.instanceProdData,
                    mode: 'app',
                    version: window.appBuildVersion || ''
                });
            }
            if (data.isPlmAdmin) {
                // Topbar Admin\u25BE menu (HANDOFF-ecn-improvements #1). Showing this
                // also un-hides the legacy techGuideLink span (kept for back-compat
                // until any external links to it land).
                var adminMenu = document.getElementById('adminMenu');
                if (adminMenu) adminMenu.style.display = '';
                document.querySelectorAll('.np-admin-only').forEach(function(el) { el.style.display = ''; });
                var tg = document.getElementById('techGuideLink');
                if (tg) tg.style.display = 'inline';
                var sl = document.getElementById('statsLink');
                if (sl) sl.style.display = 'inline';
                var ll = document.getElementById('logDownloadLink');
                if (ll) ll.style.display = 'inline';
                var dr = document.getElementById('dryRunLink');
                if (dr) dr.style.display = 'inline';
                var adminSec = document.getElementById('agileAdminSection');
                if (adminSec) adminSec.style.display = 'block';
                var reportsTab = document.getElementById('tabReports');
                if (reportsTab) reportsTab.style.display = '';
                var skuSeedBtn = document.getElementById('skuSeedBtn');
                if (skuSeedBtn) skuSeedBtn.style.display = '';
                // PT-26: Extensions, User Management are now sub-tabs inside the
                // "More\u2026" parent. The original top-level buttons are kept (display:none)
                // for permission-grant compatibility but no longer toggled visible —
                // visibility flows through the More parent + applyServerTabPermissions.
                var bomNotesRegen = document.getElementById('bomNotesRegenLink');
                if (bomNotesRegen) bomNotesRegen.style.display = 'inline';
                var uploadScriptBtn = document.getElementById('uploadScriptBtn');
                if (uploadScriptBtn) uploadScriptBtn.style.display = 'inline';
                // HANDOFF #1 follow-up: AD Health and AI Eval moved into the Admin
                // dropdown — top-level buttons stay hidden in the tab bar; users
                // reach them via Admin\u25BE -> AD Health / AI Eval which calls
                // switchTab('adhealth' | 'aieval').
                var ecnReportTab = document.getElementById('tabEcnReport');
                if (ecnReportTab) ecnReportTab.style.display = '';
                // Single/Sole Source: released to all PLM admins 2026-05-11.
                var ssTab = document.getElementById('tabSingleSole');
                if (ssTab) ssTab.style.display = '';
            }
            // Announcement banner (all users, not just admins): newest sent
            // banner-channel announcement this user hasn't dismissed. Dismissal
            // is stored server-side per user, so it survives browser switches.
            npCheckAnnouncementBanner();
            // PT-26: extension contributors and permissions admins reach Extensions /
            // User Management through the More\u2026 sub-tab nav. Sub-tab visibility
            // is gated server-side via allowedTabs (see applyServerTabPermissions).
            // Server-driven tab visibility: hide any tab NOT in data.allowedTabs.
            // This is how per-user permissions (set via the User Permissions tab)
            // get enforced for non-admins. For users with no explicit record,
            // the server returns the full default set so nothing changes here.
            applyServerTabPermissions(data.allowedTabs);
            // PT-26: re-surface specific More\u2026 sub-tabs for users whose access
            // comes from a flag, not from allowedTabs:
            //   - isExtensionContributor  -> Extensions sub-tab
            //   - isPermissionsAdmin      -> User Management sub-tab
            //   - isPlmAdmin              -> all five sub-tabs (More parent always visible)
            // Each path also un-hides the More\u2026 parent button in case the user
            // has zero entries in allowedTabs that map to More.
            function moreShow(subKey) {
                document.querySelectorAll('.more-sub[data-sub="' + subKey + '"]').forEach(function (b) {
                    b.style.display = '';
                });
                var moreBtn = document.getElementById('tabMore');
                if (moreBtn) moreBtn.style.display = '';
            }
            if (data.isPlmAdmin) {
                ['helpcenter','permissions','extensions','compare','changereviews'].forEach(moreShow);
            } else {
                if (data.isExtensionContributor) moreShow('extensions');
                if (data.isPermissionsAdmin)    moreShow('permissions');
            }
            // Apply user's tab visibility preferences (after admin tabs are shown)
            applyTabPrefs();
            // Show AD offline banner if logged in via cache or emergency account
            if (data.loginSource === 'cache' || data.loginSource === 'emergency') {
                showAdOfflineBanner(data.loginSource);
            }
            if (!data.isPlmAdmin) {
                // Non-admin: check if they have any reports assigned
                fetch('/api/reports').then(function(r) { return r.json(); }).then(function(reports) {
                    if (reports && reports.length > 0) {
                        var reportsTab = document.getElementById('tabReports');
                        if (reportsTab) reportsTab.style.display = '';
                    }
                }).catch(function() {});
                // Non-admin: check if they are an ECN Report editor
                fetch('/api/ecn-report/data').then(function(r) { return r.json(); }).then(function(d) {
                    if (d.canEdit) {
                        var ecnTab = document.getElementById('tabEcnReport');
                        if (ecnTab) ecnTab.style.display = '';
                    }
                }).catch(function() {});
            }
        })
        .catch(function() { window.location.href = '/login.html'; });

    document.getElementById('toggleSwitch').classList.add('active');
    document.getElementById('searchForm').addEventListener('keydown', function(e) {
        if (e.key === 'Enter') { e.preventDefault(); doSearch(); }
    });
    document.querySelectorAll('th.sortable').forEach(function(th) {
        th.addEventListener('click', function() {
            var sortField = th.getAttribute('data-sort');
            if (currentSort.field === sortField) {
                currentSort.dir = currentSort.dir === 'asc' ? 'desc' : 'asc';
            } else {
                currentSort.field = sortField;
                currentSort.dir = 'asc';
            }
            renderResults();
            updateSortIcons();
        });
    });
});

function doSearch() {
    var params = buildParams();
    showLoading();
    var qs = buildQueryString(params);
    fetch('/api/changes?' + qs)
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            currentResults = data.results || [];
            // Client-side filter for 1-hour lookback using server-computed ageMs
            var lookbackVal = document.getElementById('daysSelect').value;
            if (lookbackVal === '1h') {
                var oneHourMs = 60 * 60 * 1000;
                currentResults = currentResults.filter(function(r) {
                    return r.ageMs != null && r.ageMs >= 0 && r.ageMs <= oneHourMs;
                });
            }
            expandedRows.clear();
            columnFilters = {};
            selectedDates = null;
            document.querySelectorAll('.col-filter:not(.date-filter-btn)').forEach(function(i) { i.value = ''; });
            buildDateCheckboxes();
            hideLoading();
            // For 1h lookback, adjust the chip counts to reflect client-side filtering
            if (lookbackVal === '1h') {
                var uniqueItems1h = {};
                currentResults.forEach(function(r) { if (r.itemNumber) uniqueItems1h[r.itemNumber] = true; });
                data = Object.assign({}, data, {
                    totalCount: currentResults.length,
                    uniqueItems: Object.keys(uniqueItems1h).length
                });
            }
            updateChips(params, data);
            // Show DB offline banner if needed
            var banner = document.getElementById('dbOfflineBanner');
            if (data.dbOffline && data.dataAsOf) {
                document.getElementById('dataAsOfTime').textContent = data.dataAsOf;
                banner.style.display = 'block';
            } else {
                banner.style.display = 'none';
            }
            if (currentResults.length === 0) {
                showNoResults();
                fillInsightStrip([]);
            } else { renderResults(); showTable(); }
        })
        .catch(function(err) { hideLoading(); showCustomAlert('PLM Toolkit','Search failed: ' + err.message); });
}

function doClear() {
    document.getElementById('fieldInput').value = '';
    document.getElementById('itemInput').value = '';
    document.getElementById('oldInput').value = '';
    document.getElementById('newInput').value = '';
    document.getElementById('userInput').value = '';
    document.getElementById('daysSelect').value = '7';
    document.getElementById('netFilterToggle').checked = true;
    document.getElementById('toggleSwitch').classList.add('active');
    currentResults = [];
    expandedRows.clear();
    document.getElementById('statusBar').style.display = 'none';
    document.getElementById('tableWrapper').style.display = 'none';
    document.getElementById('noResults').style.display = 'none';
    document.getElementById('emptyState').style.display = 'block';
}

function buildColumnFilterQS() {
    var parts = [];
    document.querySelectorAll('.col-filter').forEach(function(input) {
        var val = input.value.trim();
        if (val) parts.push('col' + input.getAttribute('data-col').charAt(0).toUpperCase() + input.getAttribute('data-col').slice(1) + '=' + encodeURIComponent(val));
    });
    return parts.join('&');
}

function doExport() {
    guardExport(function() {
        var params = buildParams();
        var qs = buildQueryString(params);
        var colQs = buildColumnFilterQS();
        if (colQs) qs += '&' + colQs;
        window.location.href = '/api/changes/export?' + qs;
    });
}

function doLogout() {
    fetch('/api/auth/logout', { method: 'POST' })
        .then(function() { window.location.href = '/login.html'; })
        .catch(function() { window.location.href = '/login.html'; });
}

function doEmailMe() {
    var params = buildParams();
    var btn = document.getElementById('emailBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Sending...';
    var qs = buildQueryString(params);
    var colQs = buildColumnFilterQS();
    if (colQs) qs += '&' + colQs;
    fetch('/api/changes/email?' + qs, { method: 'POST' })
        .then(checkAuth)
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
                showCustomAlert('PLM Toolkit',data.message || 'Failed to send email.');
            }
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.innerHTML = origText;
            showCustomAlert('PLM Toolkit','Failed to send email: ' + err.message);
        });
}

function toggleNetFilter(e) {
    if (e) { e.preventDefault(); e.stopPropagation(); }
    var cb = document.getElementById('netFilterToggle');
    cb.checked = !cb.checked;
    var toggle = document.getElementById('toggleSwitch');
    if (cb.checked) { toggle.classList.add('active'); } else { toggle.classList.remove('active'); }
    if (currentResults.length > 0 || document.getElementById('tableWrapper').style.display !== 'none') { doSearch(); }
}

function buildParams() {
    return {
        field: document.getElementById('fieldInput').value.trim() || null,
        item: document.getElementById('itemInput').value.trim() || null,
        oldContains: document.getElementById('oldInput').value.trim() || null,
        newContains: document.getElementById('newInput').value.trim() || null,
        user: document.getElementById('userInput').value.trim() || null,
        days: document.getElementById('daysSelect').value,
        netFilter: document.getElementById('netFilterToggle').checked
    };
}

function buildQueryString(params) {
    var parts = [];
    if (params.field) parts.push('field=' + encodeURIComponent(params.field));
    if (params.item) parts.push('item=' + encodeURIComponent(params.item));
    if (params.user) parts.push('user=' + encodeURIComponent(params.user));
    // "1h" = 1 hour lookback; send days=1 to API and filter client-side
    var apiDays = (params.days === '1h') ? '1' : params.days;
    parts.push('days=' + apiDays);
    if (params.oldContains) parts.push('oldContains=' + encodeURIComponent(params.oldContains));
    if (params.newContains) parts.push('newContains=' + encodeURIComponent(params.newContains));
    parts.push('netFilter=' + params.netFilter);
    return parts.join('&');
}

function clearColumnFilters() {
    document.querySelectorAll('.col-filter:not(.date-filter-btn)').forEach(function(input) { input.value = ''; });
    columnFilters = {};
    selectedDates = null;
    buildDateCheckboxes();
    expandedRows.clear();
    renderResults();
}

function applyColumnFilters() {
    columnFilters = {};
    document.querySelectorAll('.col-filter').forEach(function(input) {
        var val = input.value.trim().toLowerCase();
        if (val) columnFilters[input.getAttribute('data-col')] = val;
    });
    expandedRows.clear();
    renderResults();
}

function getFilteredResults() {
    var hasColFilters = Object.keys(columnFilters).length > 0;
    var hasDateFilter = selectedDates !== null;
    if (!hasColFilters && !hasDateFilter) return currentResults;
    return currentResults.filter(function(rec) {
        // Date checkbox filter
        if (hasDateFilter) {
            var dateKey = getDateKey(rec.timestamp);
            if (!selectedDates[dateKey]) return false;
        }
        // Column text filters (supports !negation and comma OR)
        for (var col in columnFilters) {
            if (!matchesFilter(rec[col], columnFilters[col])) return false;
        }
        return true;
    });
}

function getDateKey(ts) {
    if (!ts) return '';
    var d = new Date(ts);
    return pad(d.getMonth() + 1) + '/' + pad(d.getDate());
}

function renderResults() {
    var tbody = document.getElementById('resultsBody');
    tbody.innerHTML = '';
    var filtered = getFilteredResults();
    updateFilterCount(filtered, currentResults.length);
    showUserCardTip();
    fillInsightStrip(filtered);
    var sorted = filtered.slice().sort(function(a, b) {
        var aVal = a[currentSort.field] || '';
        var bVal = b[currentSort.field] || '';
        if (currentSort.field === 'timestamp') { aVal = new Date(aVal).getTime() || 0; bVal = new Date(bVal).getTime() || 0; }
        if (typeof aVal === 'string') { aVal = aVal.toLowerCase(); bVal = bVal.toLowerCase(); }
        if (aVal < bVal) return currentSort.dir === 'asc' ? -1 : 1;
        if (aVal > bVal) return currentSort.dir === 'asc' ? 1 : -1;
        return 0;
    });
    sorted.forEach(function(rec, idx) {
        var isExpanded = expandedRows.has(idx);
        var tr = document.createElement('tr');
        if (isExpanded) tr.classList.add('row-expanded');
        tr.onclick = function() { toggleRow(idx); };
        tr.innerHTML =
            '<td><span class="expand-icon">&#9654;</span></td>' +
            '<td><strong>' + esc(rec.itemNumber) + '</strong> <span class="copy-btn" title="Copy item number" onclick="copyItemNumber(event,\'' + esc(rec.itemNumber).replace(/'/g, "\\'") + '\')">&#128203;</span></td>' +
            '<td>' + esc(rec.fieldName) + '</td>' +
            '<td class="val-old truncate" title="' + esc(rec.oldValue) + '">' + esc(rec.oldValue) + '</td>' +
            '<td class="val-new truncate" title="' + esc(rec.newValue) + '">' + esc(rec.newValue) + '</td>' +
            '<td class="val-user">' + renderUserLink(rec.userName) + '</td>' +
            '<td class="rel-time" data-age="' + (rec.ageMs || 0) + '" data-rendered="' + Date.now() + '" title="' + formatDateFull(rec.timestamp) + '">' + formatAgeMs(rec.ageMs) + '</td>' +
            '<td>' + esc(rec.revNumber) + '</td>';
        tbody.appendChild(tr);
        if (isExpanded) {
            var expTr = document.createElement('tr');
            expTr.classList.add('expand-row');
            var td = document.createElement('td');
            td.colSpan = 8;
            td.innerHTML =
                '<div class="inline-expand">' +
                '  <div class="diff-meta">' +
                '    <span><strong>' + esc(rec.itemNumber) + '</strong> &mdash; ' + esc(rec.fieldName) + '</span>' +
                '    <span>' + formatDateFull(rec.timestamp) + ' &bull; Rev ' + esc(rec.revNumber) + ' &bull; ' + esc(rec.userName) + '</span>' +
                '  </div>' +
                '  <div class="inline-diff">' + computeInlineDiff(rec.oldValue, rec.newValue) + '</div>' +
                '</div>';
            expTr.appendChild(td);
            tbody.appendChild(expTr);
        }
    });
}

function toggleRow(idx) {
    if (expandedRows.has(idx)) { expandedRows.delete(idx); } else { expandedRows.add(idx); }
    renderResults();
}

// === Insight Strip ===
function fillInsightStrip(rows) {
    var strip = document.getElementById('insightStrip');
    if (!strip) return;
    if (!rows || rows.length === 0) { strip.style.display = 'none'; return; }
    strip.style.display = 'grid';

    // Total changes
    document.getElementById('insightChangesVal').textContent = rows.length.toLocaleString();

    // Unique items
    var items = {};
    rows.forEach(function(r) { if (r.itemNumber) items[r.itemNumber] = true; });
    document.getElementById('insightItemsVal').textContent = Object.keys(items).length.toLocaleString();

    // Top field
    var fields = {};
    rows.forEach(function(r) {
        var f = r.fieldName || '';
        fields[f] = (fields[f] || 0) + 1;
    });
    var topField = '', topCount = 0;
    for (var f in fields) { if (fields[f] > topCount) { topField = f; topCount = fields[f]; } }
    var el = document.getElementById('insightTopFieldVal');
    if (topField) {
        // Shorten long field names
        var short = topField.indexOf('.') > 0 ? topField.split('.').pop() : topField;
        el.innerHTML = esc(short) + ' <span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3,#888);">&middot; ' + topCount + '</span>';
    } else {
        el.textContent = '—';
    }

    // Blank → Value (fields that went from blank to a value)
    var blankToVal = 0;
    rows.forEach(function(r) {
        if (r.oldValue === '(blank)' && r.newValue && r.newValue !== '(blank)') blankToVal++;
    });
    document.getElementById('insightFlaggedVal').textContent = blankToVal.toLocaleString();
}

// === User Info Popover ===
var _userInfoCache = {};

function getInitials(name) {
    if (!name) return '?';
    var parts = name.split(/[\s,]+/).filter(function(p) { return p.length > 0; });
    if (parts.length >= 2) return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
    return parts[0][0].toUpperCase();
}

function renderUserLink(userName) {
    if (!userName) return '';
    // Extract employee ID from parentheses, e.g. "Kumar, Balaji (1000262932)" → "1000262932"
    var match = userName.match(/\((\d+)\)/);
    if (!match) {
        var initials = getInitials(userName);
        return '<span class="user-avatar">' + initials + '</span>' + esc(userName);
    }
    var empId = match[1];
    var displayName = userName.replace(/\s*\(\d+\)/, '').trim();
    var initials = getInitials(displayName);
    return '<span class="user-avatar">' + initials + '</span><span class="user-card-link" data-empid="' + esc(empId) + '" onclick="showUserCard(event, \'' + esc(empId) + '\')">' + esc(displayName) + '</span>';
}

function showUserCard(e, empId) {
    e.stopPropagation();
    // Remove any existing popover
    closeUserCard();

    var pop = document.createElement('div');
    pop.className = 'user-card-popover';
    pop.id = 'userCardPopover';
    pop.innerHTML = '<span class="ucp-close" onclick="closeUserCard()">&times;</span><div class="ucp-loading">Loading...</div>';

    // Position near click
    var x = Math.min(e.clientX, window.innerWidth - 300);
    var y = e.clientY + 10;
    if (y + 220 > window.innerHeight) y = e.clientY - 230;
    pop.style.left = x + 'px';
    pop.style.top = y + 'px';
    document.body.appendChild(pop);

    // Check client cache
    if (_userInfoCache[empId]) {
        renderUserCardContent(pop, _userInfoCache[empId]);
        return;
    }

    fetch('/api/auth/user-info/' + empId)
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data.success) {
                pop.innerHTML = '<span class="ucp-close" onclick="closeUserCard()">&times;</span>' +
                    '<div class="ucp-loading">User not found in directory</div>';
                return;
            }
            _userInfoCache[empId] = data;
            renderUserCardContent(pop, data);
        })
        .catch(function() {
            pop.innerHTML = '<span class="ucp-close" onclick="closeUserCard()">&times;</span>' +
                '<div class="ucp-loading">Lookup failed</div>';
        });
}

function renderUserCardContent(pop, data) {
    var rows = '';
    if (data.email) rows += '<div class="ucp-row"><span class="ucp-label">Email</span><span class="ucp-val">' + esc(data.email) + '</span></div>';
    if (data.department) rows += '<div class="ucp-row"><span class="ucp-label">Dept</span><span class="ucp-val">' + esc(data.department) + '</span></div>';
    if (data.manager) rows += '<div class="ucp-row"><span class="ucp-label">Manager</span><span class="ucp-val">' + esc(data.manager) + '</span></div>';
    if (data.country) rows += '<div class="ucp-row"><span class="ucp-label">Country</span><span class="ucp-val">' + esc(data.country) + (data.city ? ' (' + esc(data.city) + ')' : '') + '</span></div>';
    if (!rows) rows = '<div class="ucp-row"><span class="ucp-val" style="color:#888;">No details available</span></div>';

    pop.innerHTML =
        '<span class="ucp-close" onclick="closeUserCard()">&times;</span>' +
        '<div class="ucp-header">' +
        '  <div class="ucp-name">' + esc(data.displayName || '') + '</div>' +
        (data.title ? '  <div class="ucp-title">' + esc(data.title) + '</div>' : '') +
        '</div>' +
        '<div class="ucp-body">' + rows + '</div>';
}

function closeUserCard() {
    var pop = document.getElementById('userCardPopover');
    if (pop) pop.remove();
}

function showUserCardTip() {
    if (localStorage.getItem('userCardTipDismissed')) return;
    var tip = document.getElementById('userCardTip');
    if (tip) tip.style.display = 'flex';
}

function dismissUserCardTip() {
    localStorage.setItem('userCardTipDismissed', '1');
    var tip = document.getElementById('userCardTip');
    if (tip) tip.style.display = 'none';
}

// Close popover when clicking outside
document.addEventListener('click', function(e) {
    var pop = document.getElementById('userCardPopover');
    if (pop && !pop.contains(e.target) && !e.target.classList.contains('user-card-link')) {
        closeUserCard();
    }
});

function computeInlineDiff(oldVal, newVal) {
    if (oldVal === '(blank)' && newVal === '(blank)') return '<span class="diff-same">(no change)</span>';
    if (oldVal === '(blank)') return '<span class="diff-add">' + esc(newVal) + '</span>';
    if (newVal === '(blank)') return '<span class="diff-del">' + esc(oldVal) + '</span>';

    // Split by comma into entries (e.g., "C002 - SDM (Shanghai-SDSS)" is one entry)
    var oldEntries = oldVal.split(',').map(function(s) { return s.trim(); }).filter(function(s) { return s; });
    var newEntries = newVal.split(',').map(function(s) { return s.trim(); }).filter(function(s) { return s; });

    var oldSet = {};
    oldEntries.forEach(function(e) { oldSet[e] = true; });
    var newSet = {};
    newEntries.forEach(function(e) { newSet[e] = true; });

    var html = '';

    // Show removed entries (in old but not in new)
    var removed = oldEntries.filter(function(e) { return !newSet[e]; });
    if (removed.length > 0) {
        html += '<span class="diff-del">' + esc(removed.join(', ')) + '</span>';
        if (newEntries.length > 0) html += '<br>';
    }

    // Show new entries with additions highlighted
    var parts = [];
    newEntries.forEach(function(e) {
        if (!oldSet[e]) {
            parts.push('<span class="diff-add">' + esc(e) + '</span>');
        } else {
            parts.push('<span class="diff-same">' + esc(e) + '</span>');
        }
    });
    html += parts.join(', ');

    return html;
}

function updateChips(params, data) {
    var chips = document.getElementById('chips');
    chips.innerHTML = '';
    if (params.field) addChip(chips, 'Field: ' + params.field);
    if (params.item) addChip(chips, 'Item: ' + params.item);
    if (params.oldContains) addChip(chips, 'Old: ' + params.oldContains);
    if (params.newContains) addChip(chips, 'New: ' + params.newContains);
    if (params.user) addChip(chips, 'By: ' + params.user);
    addChip(chips, 'Last ' + params.days + ' days');
    var countText = data.totalCount + ' changes \u2022 ' + data.uniqueItems + ' items';
    if (data.queryTimeMs) countText += ' \u2022 ' + data.queryTimeMs + 'ms';
    addChip(chips, countText, 'chip-count');
    if (data.truncated) addChip(chips, 'Truncated to 500 rows', 'chip-warn');
    document.getElementById('statusBar').style.display = 'flex';
}

function updateFilterCount(filtered, total) {
    var el = document.getElementById('changesFilterCount');
    if (!el) {
        el = document.createElement('span');
        el.id = 'changesFilterCount';
        el.className = 'chip chip-warn';
        el.style.display = 'none';
        var chips = document.getElementById('chips');
        if (chips) chips.appendChild(el);
    }
    var shown = filtered.length;
    if (shown < total) {
        var uniqueItems = {};
        filtered.forEach(function(r) { if (r.itemNumber) uniqueItems[r.itemNumber] = true; });
        var uniqueCount = Object.keys(uniqueItems).length;
        el.textContent = 'Showing ' + shown + ' of ' + total + ' rows \u2022 ' + uniqueCount + ' items';
        el.style.display = '';
    } else {
        el.style.display = 'none';
    }
}

function addChip(container, text, extraClass) {
    var span = document.createElement('span');
    span.className = 'chip' + (extraClass ? ' ' + extraClass : '');
    span.textContent = text;
    container.appendChild(span);
}

function updateSortIcons() {
    document.querySelectorAll('th.sortable .sort-icon').forEach(function(icon) { icon.textContent = ''; });
    var active = document.querySelector('th[data-sort="' + currentSort.field + '"] .sort-icon');
    if (active) active.textContent = currentSort.dir === 'asc' ? '\u25B2' : '\u25BC';
}

function showLoading() {
    document.getElementById('loading').style.display = 'block';
    document.getElementById('emptyState').style.display = 'none';
    document.getElementById('noResults').style.display = 'none';
    document.getElementById('tableWrapper').style.display = 'none';
    document.getElementById('statusBar').style.display = 'none';
    searchStartTime = startAdaptiveProgress('changes', 'loadingText', 'progressBar', 'progressPct', 'progressWrap', 'Querying PLM database...');
}
function hideLoading() {
    stopAdaptiveProgress('changes', searchStartTime, 'progressBar', 'progressPct');
    document.getElementById('loading').style.display = 'none';
}
function showTable() {
    document.getElementById('tableWrapper').style.display = 'block';
    document.getElementById('noResults').style.display = 'none';
    document.getElementById('emptyState').style.display = 'none';
}
function showNoResults() {
    document.getElementById('noResults').style.display = 'block';
    document.getElementById('tableWrapper').style.display = 'none';
    document.getElementById('emptyState').style.display = 'none';
}

function esc(str) {
    if (!str) return '';
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function formatDate(ts) {
    if (!ts) return '';
    var d = new Date(ts);
    return pad(d.getMonth() + 1) + '/' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function formatDateFull(ts) {
    if (!ts) return '';
    var d = new Date(ts);
    var months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    return months[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear() + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function pad(n) { return n < 10 ? '0' + n : '' + n; }

function copyItemNumber(e, itemNum) {
    e.stopPropagation();
    var ta = document.createElement('textarea');
    ta.value = itemNum;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    var el = e.target;
    el.textContent = '\u2713';
    setTimeout(function() { el.innerHTML = '&#128203;'; }, 1000);
}

// Format age in milliseconds as human-readable relative time
function formatAgeMs(ageMs) {
    if (!ageMs || ageMs < 0) return 'just now';
    var secs = Math.floor(ageMs / 1000);
    if (secs < 60) return 'just now';
    var mins = Math.floor(secs / 60);
    if (mins < 60) return mins + 'm ago';
    var hrs = Math.floor(mins / 60);
    if (hrs < 24) return hrs + 'h ago';
    var days = Math.floor(hrs / 24);
    if (days === 1) return '1d ago';
    return days + 'd ago';
}

// Auto-refresh relative timestamps every 60 seconds
setInterval(function() {
    var cells = document.querySelectorAll('.rel-time[data-age]');
    var now = Date.now();
    for (var i = 0; i < cells.length; i++) {
        var age = parseInt(cells[i].getAttribute('data-age'));
        var rendered = parseInt(cells[i].getAttribute('data-rendered'));
        var elapsed = now - rendered;
        cells[i].textContent = formatAgeMs(age + elapsed);
    }
}, 60000);

// === Help Modal ===
function showHelp() {
    document.getElementById('helpModal').style.display = 'flex';
}

function closeHelp() {
    document.getElementById('helpModal').style.display = 'none';
}

// === Example Searches ===
function runExample(field, item, oldC, newC, user, days) {
    closeHelp();
    document.getElementById('fieldInput').value = field;
    document.getElementById('itemInput').value = item;
    document.getElementById('oldInput').value = oldC;
    document.getElementById('newInput').value = newC;
    document.getElementById('userInput').value = user;
    document.getElementById('daysSelect').value = days;
    doSearch();
}

// === Keyboard Shortcuts ===
document.addEventListener('keydown', function(e) {
    // ? opens help (only when not typing in an input)
    if (e.key === '?' && !isInputFocused()) {
        e.preventDefault();
        showHelp();
    }
    // Escape closes help or collapses rows
    if (e.key === 'Escape') {
        if (document.getElementById('helpModal').style.display !== 'none') {
            closeHelp();
        } else if (expandedRows.size > 0) {
            expandedRows.clear();
            renderResults();
        }
    }
});

// === Date Filter Dropdown ===
function buildDateCheckboxes() {
    var container = document.getElementById('dateCheckboxes');
    if (!container) return;
    // Collect unique dates from current results, sorted descending
    var dates = {};
    currentResults.forEach(function(rec) {
        var key = getDateKey(rec.timestamp);
        if (key) dates[key] = true;
    });
    var sorted = Object.keys(dates).sort().reverse();
    container.innerHTML = '';
    sorted.forEach(function(dateStr) {
        var label = document.createElement('label');
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = selectedDates === null || selectedDates[dateStr];
        cb.value = dateStr;
        cb.onchange = function() { onDateCheckboxChange(); };
        label.appendChild(cb);
        label.appendChild(document.createTextNode(' ' + dateStr));
        container.appendChild(label);
    });
    updateDateFilterBtn();
}

function toggleDateFilter() {
    var dd = document.getElementById('dateFilterDropdown');
    dd.style.display = dd.style.display === 'none' ? 'block' : 'none';
}

function onDateCheckboxChange() {
    var checkboxes = document.querySelectorAll('#dateCheckboxes input[type="checkbox"]');
    var allChecked = true;
    var noneChecked = true;
    selectedDates = {};
    checkboxes.forEach(function(cb) {
        if (cb.checked) { selectedDates[cb.value] = true; noneChecked = false; }
        else { allChecked = false; }
    });
    if (allChecked) selectedDates = null; // all = no filter
    updateDateFilterBtn();
    expandedRows.clear();
    renderResults();
}

function dateSelectAll() {
    selectedDates = null;
    document.querySelectorAll('#dateCheckboxes input[type="checkbox"]').forEach(function(cb) { cb.checked = true; });
    updateDateFilterBtn();
    expandedRows.clear();
    renderResults();
}

function dateSelectNone() {
    selectedDates = {};
    document.querySelectorAll('#dateCheckboxes input[type="checkbox"]').forEach(function(cb) { cb.checked = false; });
    updateDateFilterBtn();
    expandedRows.clear();
    renderResults();
}

function updateDateFilterBtn() {
    var btn = document.getElementById('dateFilterBtn');
    if (!btn) return;
    if (selectedDates === null) {
        btn.innerHTML = 'All dates &#9662;';
        btn.style.background = '#2c3e50';
        btn.style.color = '#fff';
    } else {
        var count = Object.keys(selectedDates).length;
        if (count === 0) {
            btn.innerHTML = '0 dates &#9662; (click to restore)';
            btn.style.background = '#dc3545';
            btn.style.color = '#fff';
        } else {
            btn.innerHTML = count + ' date' + (count !== 1 ? 's' : '') + ' &#9662;';
            btn.style.background = '#2c3e50';
            btn.style.color = '#fff';
        }
    }
}

// Close date dropdown when clicking outside
document.addEventListener('click', function(e) {
    var dd = document.getElementById('dateFilterDropdown');
    var btn = document.getElementById('dateFilterBtn');
    if (dd && dd.style.display !== 'none' && !dd.contains(e.target) && e.target !== btn) {
        dd.style.display = 'none';
    }
});

// === Tab Switching ===
// Map of internal tab keys → human-readable names sent to the activity log.
// Anything not in the map is logged as the raw key — easy to spot if a new tab
// gets wired up without updating this list.
var TAB_LOG_NAMES = {
    changes: 'Field Changes', bom: 'BOM Explorer', parts: 'Part Extract',
    agile: 'Agile Lookup', history: 'Change History', reports: 'Utilities',
    compare: 'Data Compare', sku: 'SKU Lookup', extensions: 'Extensions',
    bomcompare: 'BOM Compare', changereviews: 'Change Reviews',
    adhealth: 'AD Health', ecnreport: 'ECN Dashboard', helpcenter: 'Work Instructions',
    aieval: 'AI Eval', docreview: 'Review Tracker', 'ims-review': 'IMS Dashboard'
};

function logPageView(tab, view) {
    try {
        fetch('/api/activity/page', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ tab: TAB_LOG_NAMES[tab] || tab, view: view || null })
        });
    } catch (e) { /* fire-and-forget */ }
}

function switchTab(tab) {
    // Block tab switching when session is expired
    var elapsed = Date.now() - sessionStartTime;
    if (elapsed >= SESSION_MAX) {
        showSessionExpiredModal();
        return;
    }
    // Log page view (fire-and-forget; doesn't block the tab swap)
    logPageView(tab);
    document.getElementById('panelChanges').style.display = tab === 'changes' ? '' : 'none';
    document.getElementById('panelBom').style.display = tab === 'bom' ? '' : 'none';
    var panelET = document.getElementById('panelEcoTimeline');
    if (panelET) panelET.style.display = tab === 'ecotimeline' ? '' : 'none';
    document.getElementById('panelParts').style.display = tab === 'parts' ? '' : 'none';
    document.getElementById('panelAgile').style.display = tab === 'agile' ? '' : 'none';
    document.getElementById('panelHistory').style.display = tab === 'history' ? '' : 'none';
    // PT-50: Audit Trail is the 3rd sub-tab under Change History.
    var panelAT = document.getElementById('panelAuditTrail');
    if (panelAT) panelAT.style.display = tab === 'audittrail' ? '' : 'none';
    document.getElementById('panelReports').style.display = tab === 'reports' ? '' : 'none';
    document.getElementById('panelCompare').style.display = tab === 'compare' ? '' : 'none';
    document.getElementById('panelSku').style.display = tab === 'sku' ? '' : 'none';
    document.getElementById('panelExtensions').style.display = tab === 'extensions' ? '' : 'none';
    document.getElementById('panelBomCompare').style.display = tab === 'bomcompare' ? '' : 'none';
    document.getElementById('panelChangeReviews').style.display = tab === 'changereviews' ? '' : 'none';
    // Labs is now a primary tab containing three sub-screens (BOM Race, AD Health, AI Eval).
    // adhealth/aieval visibility is owned by the Labs container, not the top-level screen list.
    var labsActive = (tab === 'labs' || tab === 'bomrace' || tab === 'adhealth' || tab === 'aieval');
    var screenLabs = document.getElementById('screenLabs');
    if (screenLabs) screenLabs.style.display = labsActive ? '' : 'none';
    var bomraceSub = document.getElementById('screenBomRace');
    if (bomraceSub) bomraceSub.style.display = (tab === 'labs' || tab === 'bomrace') ? '' : 'none';
    document.getElementById('panelAdHealth').style.display = tab === 'adhealth' ? '' : 'none';
    var panelEcn = document.getElementById('panelEcnReport');
    if (panelEcn) panelEcn.style.display = tab === 'ecnreport' ? '' : 'none';
    var panelDR = document.getElementById('panelDocReview');
    if (panelDR) {
        var drShow = (tab === 'docreview');
        panelDR.style.display = drShow ? '' : 'none';
        if (drShow && typeof window.docReviewLoad === 'function') window.docReviewLoad();
    }
    var tabDR = document.getElementById('tabDocReview');
    if (tabDR) tabDR.className = tab === 'docreview' ? 'tab active' : 'tab';
    var panelIR = document.getElementById('panelImsReview');
    if (panelIR) {
        var irShow = (tab === 'ims-review');
        panelIR.style.display = irShow ? '' : 'none';
        if (irShow && typeof window.imsReviewLoad === 'function') window.imsReviewLoad();
    }
    var tabIR = document.getElementById('tabImsReview');
    if (tabIR) tabIR.className = tab === 'ims-review' ? 'tab active' : 'tab';
    var panelSdsm = document.getElementById('panelSdsm');
    if (panelSdsm) {
        var sdsmShow = (tab === 'sdsm');
        panelSdsm.style.display = sdsmShow ? '' : 'none';
        if (sdsmShow && typeof window.sdsmInit === 'function') window.sdsmInit();
    }
    var panelSS = document.getElementById('panelSingleSole');
    if (panelSS) panelSS.style.display = tab === 'singlesole' ? '' : 'none';
    var panelIte = document.getElementById('panelItEnhancements');
    if (panelIte) {
        var iteShow = (tab === 'it-enhancements');
        panelIte.style.display = iteShow ? '' : 'none';
        if (iteShow && typeof window.iteInit === 'function') window.iteInit();
    }
    var tabIteEl = document.getElementById('tabItEnhancements');
    if (tabIteEl) tabIteEl.className = tab === 'it-enhancements' ? 'tab active' : 'tab';
    // Owner Audit was a separate tab briefly (June 5, 2026) — folded into the
    // IMS Dashboard as a sub-view per Vikas's recommendation. No separate
    // tab handling needed; imsreview.js owns the sub-view switch.
    var panelHC = document.getElementById('panelHelpCenter');
    if (panelHC) panelHC.style.display = tab === 'helpcenter' ? '' : 'none';
    var panelAie = document.getElementById('panelAiEval');
    if (panelAie) {
        var aieShow = (tab === 'aieval');
        panelAie.style.display = aieShow ? '' : 'none';
        // panelAie is reparented into screenLabs at runtime (see index.html DOMContentLoaded
        // script). When the page is on Items/etc., screenLabs is hidden, which also hides
        // panelAie via parent — even if its own display is ''. So no extra logic needed here.
        if (aieShow && typeof window.aieInit === 'function') window.aieInit();
    }
    var panelPerms = document.getElementById('panelPermissions');
    if (panelPerms) {
        var permsShow = (tab === 'permissions');
        panelPerms.style.display = permsShow ? '' : 'none';
        if (permsShow && typeof permsBootstrap === 'function') permsBootstrap();
    }
    // Announcements (Admin > Communications) — menu-only tab, admin-gated server-side
    var panelAnn = document.getElementById('panelAnnouncements');
    if (panelAnn) {
        var annShow = (tab === 'announcements');
        panelAnn.style.display = annShow ? '' : 'none';
        if (annShow && typeof window.announcementsInit === 'function') window.announcementsInit();
    }
    // Items is a parent tab with four sub-tabs (Part Extract, Agile Lookup,
    // SKU Lookup, Search) — Field Changes moved to Change History per PT-50.
    var panelIS = document.getElementById('panelItemsSearch');
    if (panelIS) {
        var isShow = (tab === 'items-search');
        panelIS.style.display = isShow ? '' : 'none';
        if (isShow && typeof window.itemsSearchInit === 'function') window.itemsSearchInit();
    }
    var itemsActive = (tab === 'parts' || tab === 'agile' || tab === 'sku' || tab === 'items-search');
    var tabItemsEl = document.getElementById('tabItems');
    if (tabItemsEl) tabItemsEl.className = itemsActive ? 'tab active' : 'tab';
    document.getElementById('tabChanges').className = tab === 'changes' ? 'tab active' : 'tab';
    // Sync the three mirrored Items sub-tab navs (one inside each Items child panel).
    document.querySelectorAll('.items-sub').forEach(function(btn) {
        var sub = btn.getAttribute('data-sub');
        var on = (sub === tab);
        btn.style.color = on ? '#0F1720' : '#6B7280';
        btn.style.fontWeight = on ? '600' : '500';
        btn.style.borderBottomColor = on ? '#4a6fa5' : 'transparent';
    });
    // PT-50: Change History is a parent tab with three sub-tabs (Revision
    // History, Field Changes, Audit Trail). Any of them lights up the parent.
    var historyActive = (tab === 'history' || tab === 'changes' || tab === 'audittrail');
    document.querySelectorAll('.history-sub').forEach(function(btn) {
        var sub = btn.getAttribute('data-sub');
        var on = (sub === tab);
        btn.style.color = on ? '#0F1720' : '#6B7280';
        btn.style.fontWeight = on ? '600' : '500';
        btn.style.borderBottomColor = on ? '#4a6fa5' : 'transparent';
    });
    // BOM is a parent tab with two sub-tabs (Explorer + Compare). Either sub
    // lights up the parent.
    document.getElementById('tabBom').className = (tab === 'bom' || tab === 'bomcompare') ? 'tab active' : 'tab';
    var tabETEl = document.getElementById('tabEcoTimeline');
    if (tabETEl) tabETEl.className = tab === 'ecotimeline' ? 'tab active' : 'tab';
    // Update sub-tab visual state on both panels (the navs are mirrored).
    var subExplorerActive = (tab === 'bom');
    var subCompareActive = (tab === 'bomcompare');
    ['bomSubTabExplorer','bomSubTabExplorer2'].forEach(function(id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.style.color = subExplorerActive ? '#0F1720' : '#6B7280';
        el.style.fontWeight = subExplorerActive ? '600' : '500';
        el.style.borderBottomColor = subExplorerActive ? '#4a6fa5' : 'transparent';
    });
    ['bomSubTabCompare','bomSubTabCompare2'].forEach(function(id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.style.color = subCompareActive ? '#0F1720' : '#6B7280';
        el.style.fontWeight = subCompareActive ? '600' : '500';
        el.style.borderBottomColor = subCompareActive ? '#4a6fa5' : 'transparent';
    });
    document.getElementById('tabParts').className = tab === 'parts' ? 'tab active' : 'tab';
    document.getElementById('tabAgile').className = tab === 'agile' ? 'tab active' : 'tab';
    document.getElementById('tabHistory').className = historyActive ? 'tab active' : 'tab';
    var tabReportsEl = document.getElementById('tabReports');
    if (tabReportsEl) tabReportsEl.className = tab === 'reports' ? 'tab active' : 'tab';
    document.getElementById('tabCompare').className = tab === 'compare' ? 'tab active' : 'tab';
    document.getElementById('tabSku').className = tab === 'sku' ? 'tab active' : 'tab';
    var tabExtEl = document.getElementById('tabExtensions');
    if (tabExtEl) tabExtEl.className = tab === 'extensions' ? 'tab active' : 'tab';
    // tabBomCompare is hidden in the new sub-tab world; leave the className
    // as-is to keep CSS rules predictable, but don't toggle active on it.
    document.getElementById('tabChangeReviews').className = tab === 'changereviews' ? 'tab active' : 'tab';
    // tabAdHealth was removed from the top nav — AD Health is now a Labs sub-tab.
    // (Highlighting handled by labsSubTabAdHealth below.)
    var tabEcnEl = document.getElementById('tabEcnReport');
    if (tabEcnEl) tabEcnEl.className = tab === 'ecnreport' ? 'tab active' : 'tab';
    var tabSdsmEl = document.getElementById('tabSdsm');
    if (tabSdsmEl) tabSdsmEl.className = tab === 'sdsm' ? 'tab active' : 'tab';
    var tabSSEl = document.getElementById('tabSingleSole');
    if (tabSSEl) tabSSEl.className = tab === 'singlesole' ? 'tab active' : 'tab';
    var tabLabsEl = document.getElementById('tabLabs');
    if (tabLabsEl) tabLabsEl.className = labsActive ? 'tab active' : 'tab';
    // Labs sub-tab highlighting (BOM Race / AD Health / AI Eval).
    var labsSub = { bomrace: 'labsSubTabBomRace', adhealth: 'labsSubTabAdHealth', aieval: 'labsSubTabAiEval' };
    Object.keys(labsSub).forEach(function (k) {
        var el = document.getElementById(labsSub[k]);
        if (!el) return;
        var on = (tab === k) || (k === 'bomrace' && tab === 'labs');
        el.style.color = on ? '#0F1720' : '#6B7280';
        el.style.fontWeight = on ? '600' : '500';
        el.style.borderBottomColor = on ? '#4a6fa5' : 'transparent';
    });
    var tabHCEl = document.getElementById('tabHelpCenter');
    if (tabHCEl) tabHCEl.className = tab === 'helpcenter' ? 'tab active' : 'tab';
    // tabAiEval was removed from the top nav — AI Eval is now a Labs sub-tab.
    var tabPermsEl = document.getElementById('tabPermissions');
    if (tabPermsEl) tabPermsEl.className = tab === 'permissions' ? 'tab active' : 'tab';
    // PT-26: "More\u2026" is a parent tab grouping Work Instructions, User Management,
    // Extensions, Data Compare, Change Reviews. Any of those lights up the parent.
    var moreActive = (tab === 'helpcenter' || tab === 'permissions' || tab === 'extensions'
                      || tab === 'compare' || tab === 'changereviews');
    var tabMoreEl = document.getElementById('tabMore');
    if (tabMoreEl) tabMoreEl.className = moreActive ? 'tab active' : 'tab';
    // Sync the five mirrored sub-tab navs (one inside each More child panel).
    document.querySelectorAll('.more-sub').forEach(function(btn) {
        var sub = btn.getAttribute('data-sub');
        var on = (sub === tab);
        btn.style.color = on ? '#0F1720' : '#6B7280';
        btn.style.fontWeight = on ? '600' : '500';
        btn.style.borderBottomColor = on ? '#4a6fa5' : 'transparent';
    });
    if (tab === 'bom' && typeof loadBomStatus === 'function') loadBomStatus();
    if (tab === 'parts' && typeof loadPartsColumns === 'function') loadPartsColumns();
    if (tab === 'reports' && typeof loadReports === 'function') loadReports();
    if (tab === 'compare' && typeof initCompare === 'function') initCompare();
    if (tab === 'sku' && typeof loadSkuFields === 'function') loadSkuFields();
    if (tab === 'extensions' && typeof loadExtensions === 'function') loadExtensions();
    if (tab === 'helpcenter' && typeof loadHelpCenter === 'function') loadHelpCenter();
    if (tab === 'ecnreport' && typeof ecnLoadTab === 'function') ecnLoadTab();
    if (tab === 'singlesole' && typeof ssRefreshStatus === 'function') ssRefreshStatus();
    if ((tab === 'labs' || tab === 'bomrace') && typeof brOnTabOpen === 'function') brOnTabOpen();
    if (tab === 'changereviews') { if (typeof crLoadCountries === 'function') crLoadCountries(); if (typeof crAutoLoadSelf === 'function') crAutoLoadSelf(); }
    if (tab === 'agile') {
        fetch('/api/agile-lookup/max-items').then(function(r){return r.json();}).then(function(d){
            var el = document.getElementById('agileMaxItems');
            if (el) el.textContent = d.maxItems;
        }).catch(function(){});
    }
}

// === Shared Adaptive Progress Bar ===
// Only shows progress bar if previous query took >10s
var _progressTimers = {};
var _defaultEstimates = { changes: 60000, bom: 15000, parts: 15000 };
var _progressEstimates = JSON.parse(localStorage.getItem('progressEstimates') || JSON.stringify(_defaultEstimates));

/**
 * Adaptive progress UI.
 *
 * @param textId  pass the actual text element id, OR null to suppress text updates
 *                from this function — the caller (e.g. BOM, which has its own poller)
 *                wants to own that element entirely.
 */
function startAdaptiveProgress(key, textId, barId, pctId, wrapId, defaultText) {
    var estimated = _progressEstimates[key] || 0;
    var showBar = estimated > 10000;
    var text = textId ? document.getElementById(textId) : null;
    var bar = document.getElementById(barId);
    var pct = document.getElementById(pctId);
    var wrap = document.getElementById(wrapId);
    if (text) text.textContent = defaultText;
    if (showBar && wrap && bar && pct) {
        wrap.style.display = '';
        pct.style.display = '';
        bar.style.width = '0%';
        pct.textContent = '0%';
    } else if (wrap) {
        wrap.style.display = 'none';
        if (pct) pct.style.display = 'none';
    }
    var startTime = Date.now();
    if (_progressTimers[key]) clearInterval(_progressTimers[key]);
    _progressTimers[key] = setInterval(function() {
        var elapsed = Date.now() - startTime;
        var secs = Math.round(elapsed / 1000);
        if (text && secs >= 2) text.textContent = defaultText + ' ' + secs + 's';
        if (showBar && bar && pct && estimated > 0) {
            var progress = Math.min(95, Math.round((elapsed / estimated) * 90));
            bar.style.width = progress + '%';
            pct.textContent = progress + '%';
        }
    }, 500);
    return startTime;
}

function stopAdaptiveProgress(key, startTime, barId, pctId) {
    if (_progressTimers[key]) { clearInterval(_progressTimers[key]); _progressTimers[key] = null; }
    var bar = document.getElementById(barId);
    var pct = document.getElementById(pctId);
    if (bar) bar.style.width = '100%';
    if (pct) pct.textContent = '100%';
    if (startTime > 0) {
        var duration = Date.now() - startTime;
        _progressEstimates[key] = duration;
        try { localStorage.setItem('progressEstimates', JSON.stringify(_progressEstimates)); } catch(e) {}
    }
}

// === Shared File Upload Validation ===
function validateUploadFile(file) {
    if (!file) return 'No file selected.';
    var name = file.name.toLowerCase();
    if (!name.endsWith('.txt') && !name.endsWith('.csv') && !name.endsWith('.xlsx') && !name.endsWith('.xls')) {
        return 'Please upload a .txt, .csv, .xlsx, or .xls file (got "' + file.name + '").';
    }
    // Excel files can be larger than text files
    var maxSize = (name.endsWith('.xlsx') || name.endsWith('.xls')) ? 100 * 1024 * 1024 : 5 * 1024 * 1024;
    if (file.size > maxSize) {
        return 'File too large (max ' + (maxSize / 1024 / 1024) + 'MB). Got ' + Math.round(file.size / 1024) + 'KB.';
    }
    if (file.size === 0) {
        return 'File is empty.';
    }
    return null; // valid
}

// === Shared Column Filter Matching ===
// Supports: "value" (contains), "!value" (not contains), "a,b" (OR), "!a,!b" (AND-NOT)
function matchesFilter(cellValue, filterStr) {
    var val = (cellValue || '').toLowerCase();
    var tokens = filterStr.split(',');
    var hasPositive = false;
    var positiveMatch = false;

    for (var i = 0; i < tokens.length; i++) {
        var t = tokens[i].trim();
        if (!t) continue;
        if (t.charAt(0) === '!') {
            // Negative: must NOT contain
            var neg = t.substring(1).toLowerCase();
            if (neg && val.indexOf(neg) >= 0) return false;
        } else {
            // Positive: must contain at least one (OR logic)
            hasPositive = true;
            if (val.indexOf(t) >= 0) positiveMatch = true;
        }
    }
    // If there were positive tokens, at least one must match
    if (hasPositive && !positiveMatch) return false;
    return true;
}

function doSendStats() {
    var btn = document.getElementById('statsBtn');
    var origText = btn.innerHTML;
    btn.innerHTML = 'Sending...';
    fetch('/api/admin/send-delta-report', { method: 'POST' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                btn.innerHTML = '&#10003; Sent!';
                setTimeout(function() { btn.innerHTML = origText; }, 2000);
            } else {
                btn.innerHTML = origText;
                showCustomAlert('PLM Toolkit',data.message || 'Failed.');
            }
        })
        .catch(function(err) { btn.innerHTML = origText; showCustomAlert('PLM Toolkit','Failed: ' + err.message); });
}

// === Admin Dry Run — pre-deployment sanity check ===
function doDryRun() {
    // Open the window immediately (must be synchronous with the click to avoid popup blockers)
    var win = window.open('', 'plm-dry-run', 'width=900,height=800,scrollbars=yes');
    if (!win) { showCustomAlert('PLM Toolkit', 'Popup blocked — allow popups for this site and retry.'); return; }
    win.document.write('<!doctype html><html><head><title>PLM Toolkit — Dry Run</title></head><body style="font-family:Segoe UI,Arial,sans-serif; padding:24px; color:#222;"><h2 style="margin:0 0 8px;">Running dry-run checks...</h2><p style="color:#777;">Probing DB, LDAP, Agile proxy, Python, filesystem paths...</p></body></html>');
    win.document.close();

    fetch('/api/admin/dry-run')
        .then(function(res) { return res.json(); })
        .then(function(data) { renderDryRun(win, data); })
        .catch(function(err) {
            win.document.body.innerHTML = '<h2 style="color:#c0392b;">Dry-run failed to run</h2><pre>' + esc(err.message) + '</pre>';
        });
}

function renderDryRun(win, data) {
    if (data.error) {
        win.document.body.innerHTML = '<h2 style="color:#c0392b;">Dry-run refused</h2><p>' + esc(data.error) + '</p>';
        return;
    }
    var overallColor = data.overall === 'ok' ? '#28a745' : (data.overall === 'warn' ? '#d58512' : '#c0392b');
    var overallLabel = (data.overall || '').toUpperCase();
    var host = data.host || {};
    var rt = data.runtime || {};
    var cfg = data.config || {};
    var checks = data.checks || [];

    var html = '';
    html += '<html><head><title>PLM Toolkit — Dry Run (' + esc(overallLabel) + ')</title>';
    html += '<style>';
    html += 'body{font-family:Segoe UI,Arial,sans-serif; padding:24px; color:#222; background:#f8f9fa; margin:0;}';
    html += 'h2{margin:0 0 12px;} h3{margin:24px 0 8px; font-size:14px; color:#1a3a5c; border-bottom:1px solid #d0d5dd; padding-bottom:4px;}';
    html += '.pill{display:inline-block; padding:4px 12px; border-radius:12px; color:white; font-weight:600; font-size:12px; letter-spacing:0.5px;}';
    html += 'table{border-collapse:collapse; width:100%; background:white; font-size:12px;}';
    html += 'th,td{text-align:left; padding:6px 10px; border-bottom:1px solid #e8ecf0; vertical-align:top;}';
    html += 'th{background:#2c3e50; color:white; font-weight:600;}';
    html += 'tr:nth-child(even) td{background:#fafbfc;}';
    html += '.s-ok{color:#28a745; font-weight:600;} .s-warn{color:#d58512; font-weight:600;} .s-fail{color:#c0392b; font-weight:600;}';
    html += 'code{background:#eef1f4; padding:1px 6px; border-radius:3px; font-size:11px;}';
    html += '.kv{font-size:12px;} .kv td{padding:3px 10px;} .kv td:first-child{color:#666; width:160px;}';
    html += '</style></head><body>';
    html += '<h2>PLM Toolkit — Deployment Dry Run</h2>';
    html += '<p><span class="pill" style="background:' + overallColor + ';">' + esc(overallLabel) + '</span>';
    html += '&nbsp;&nbsp;<span style="color:#777; font-size:12px;">' + esc(data.timestamp || '') + '</span></p>';

    html += '<h3>Host</h3><table class="kv">';
    html += '<tr><td>Hostname</td><td><strong>' + esc(host.hostname) + '</strong> (' + esc(host.address) + ')</td></tr>';
    html += '<tr><td>OS</td><td>' + esc(host.os) + '</td></tr>';
    html += '<tr><td>Working dir</td><td><code>' + esc(host.workingDir) + '</code></td></tr>';
    html += '<tr><td>Process user</td><td>' + esc(host.user) + '</td></tr>';
    html += '</table>';

    html += '<h3>Runtime</h3><table class="kv">';
    html += '<tr><td>Java</td><td>' + esc(rt.javaVersion) + ' (' + esc(rt.javaVendor) + ')</td></tr>';
    html += '<tr><td>Heap</td><td>used <strong>' + esc(rt.heapUsedMB) + '</strong> / committed ' + esc(rt.heapTotalMB) + ' / max <strong>' + esc(rt.heapMaxMB) + '</strong> MB</td></tr>';
    html += '<tr><td>Processors</td><td>' + esc(rt.availableProcessors) + '</td></tr>';
    html += '</table>';

    html += '<h3>Resolved config</h3><table class="kv">';
    Object.keys(cfg).forEach(function(k) {
        html += '<tr><td>' + esc(k) + '</td><td><code>' + esc(cfg[k]) + '</code></td></tr>';
    });
    html += '</table>';

    html += '<h3>Checks</h3><table>';
    html += '<tr><th style="width:24px;"></th><th>Check</th><th>Target</th><th>Detail</th></tr>';
    checks.forEach(function(c) {
        var cls = 's-' + (c.status || 'fail');
        var icon = c.status === 'ok' ? '&#10003;' : (c.status === 'warn' ? '&#9888;' : '&#10007;');
        html += '<tr>';
        html += '<td class="' + cls + '" style="font-size:16px;">' + icon + '</td>';
        html += '<td>' + esc(c.name) + '</td>';
        html += '<td><code>' + esc(c.target) + '</code></td>';
        html += '<td class="' + cls + '">' + esc(c.detail) + '</td>';
        html += '</tr>';
    });
    html += '</table>';
    html += '<p style="margin-top:24px; color:#777; font-size:11px;">Status legend: <span class="s-ok">&#10003; ok</span> — working; <span class="s-warn">&#9888; warn</span> — non-blocking (some features may be unavailable); <span class="s-fail">&#10007; fail</span> — must fix before serving traffic.</p>';
    html += '</body></html>';

    win.document.open();
    win.document.write(html);
    win.document.close();
}

// === Saved Searches ===
function toggleSavedSearches() {
    var dd = document.getElementById('savedSearchesDropdown');
    if (dd.style.display === 'none') {
        loadSavedSearches();
        dd.style.display = 'block';
    } else {
        dd.style.display = 'none';
    }
}

function buildSearchRow(s, isOwned) {
    var div = document.createElement('div');
    div.style.cssText = 'padding:6px 12px; display:flex; justify-content:space-between; align-items:center; cursor:pointer; font-size:12px;';
    div.onmouseover = function() { div.style.background = '#f0f4ff'; };
    div.onmouseout = function() { div.style.background = ''; };

    var tabLabel = s.tab === 'changes' ? 'FC' : s.tab === 'bom' ? 'BOM' : s.tab === 'history' ? 'HIST' : s.tab === 'parts' ? 'Parts' : s.tab === 'sku' ? 'SKU' : 'Agile';

    var nameSpan = document.createElement('span');
    var labelHtml = '<span style="background:#e8f0fe; color:#1a3a5c; padding:1px 5px; border-radius:3px; font-size:10px; font-weight:600; margin-right:6px;">' + tabLabel + '</span>';
    if (!isOwned && s.ownerDisplay) {
        labelHtml += '<span style="color:#999; font-size:10px; margin-right:4px;">' + esc(s.ownerDisplay) + ':</span>';
    }
    nameSpan.innerHTML = labelHtml + esc(s.name);
    nameSpan.style.cssText = 'flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
    nameSpan.onclick = function() { loadSavedSearch(s); };

    var actions = document.createElement('span');
    actions.style.cssText = 'display:flex; align-items:center; gap:2px; flex-shrink:0;';

    if (isOwned) {
        // Share toggle button
        var shareBtn = document.createElement('span');
        shareBtn.textContent = s.shared ? '\u{1F517}' : '\u{1F513}';
        shareBtn.title = s.shared ? 'Shared \u2014 click to unshare' : 'Private \u2014 click to share';
        shareBtn.style.cssText = 'cursor:pointer; padding:4px 6px; font-size:12px; line-height:1; border-radius:3px; opacity:' + (s.shared ? '1' : '0.4') + ';';
        shareBtn.onmouseover = function() { shareBtn.style.background = '#e8f0fe'; shareBtn.style.opacity = '1'; };
        shareBtn.onmouseout = function() { shareBtn.style.background = 'transparent'; shareBtn.style.opacity = s.shared ? '1' : '0.4'; };
        shareBtn.onclick = function(e) { e.stopPropagation(); toggleShareSearch(s.id); };
        actions.appendChild(shareBtn);

        // Delete button
        var delBtn = document.createElement('span');
        delBtn.textContent = '\u2715';
        delBtn.title = 'Delete this saved search';
        delBtn.style.cssText = 'color:#ccc; cursor:pointer; padding:4px 6px; font-size:13px; line-height:1; border-radius:3px;';
        delBtn.onmouseover = function() { delBtn.style.color = '#dc3545'; delBtn.style.background = '#fde8e8'; };
        delBtn.onmouseout = function() { delBtn.style.color = '#ccc'; delBtn.style.background = 'transparent'; };
        delBtn.onclick = function(e) { e.stopPropagation(); e.preventDefault(); deleteSavedSearch(s.id); };
        actions.appendChild(delBtn);
    }

    div.appendChild(nameSpan);
    div.appendChild(actions);
    return div;
}

function loadSavedSearches() {
    fetch('/api/searches')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            var mine = data.mine || [];
            var shared = data.shared || [];

            // Render user's own searches
            var list = document.getElementById('savedSearchesList');
            list.innerHTML = '';
            if (mine.length === 0) {
                list.innerHTML = '<div style="padding:12px; color:#999; font-size:12px; text-align:center;">No saved searches yet.<br>Click the star button to save one.</div>';
            } else {
                mine.forEach(function(s) {
                    list.appendChild(buildSearchRow(s, true));
                });
            }

            // Render shared searches
            var sharedSection = document.getElementById('sharedSearchesSection');
            var sharedList = document.getElementById('sharedSearchesList');
            sharedList.innerHTML = '';
            if (shared.length === 0) {
                sharedSection.style.display = 'none';
            } else {
                sharedSection.style.display = 'block';
                shared.forEach(function(s) {
                    sharedList.appendChild(buildSearchRow(s, false));
                });
            }
        });
}

function toggleShareSearch(id) {
    fetch('/api/searches/' + encodeURIComponent(id) + '/share', { method: 'PUT' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                loadSavedSearches();
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Could not toggle sharing.');
            }
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'Error: ' + (err.message || 'network error'));
        });
}

function saveCurrentSearch(tab) {
    showCustomPrompt('Save Search', 'Give this search a name:', 'e.g. New Subcon Assignments')
        .then(function(name) {
            if (!name || !name.trim()) return;

            var params = {};
            if (tab === 'changes') {
                params.field = document.getElementById('fieldInput').value;
                params.item = document.getElementById('itemInput').value;
                params.oldContains = document.getElementById('oldInput').value;
                params.newContains = document.getElementById('newInput').value;
                params.user = document.getElementById('userInput').value;
                params.days = document.getElementById('daysSelect').value;
            } else if (tab === 'bom') {
                params.items = document.getElementById('bomItemInput').value;
                params.maxDepth = document.getElementById('bomMaxDepth').value;
            } else if (tab === 'parts') {
                params.items = document.getElementById('partsItemInput').value;
                params.relFrom = document.getElementById('partsRelFrom').value;
                params.relTo = document.getElementById('partsRelTo').value;
                params.columns = getSelectedColumns().join(',');
            } else if (tab === 'history') {
                params.items = document.getElementById('historyItemInput').value;
                // PT-72: round-trip the Change-History pre-filters too. Saving only
                // `items` made saved searches useless when the user's value was a
                // date range or part-type / entry-mode combination.
                if (typeof historyLifecycleFilter !== 'undefined' && historyLifecycleFilter.length)
                    params.lifecyclePhases = historyLifecycleFilter.join(',');
                if (typeof historyChangeTypeFilter !== 'undefined' && historyChangeTypeFilter.length)
                    params.changeTypes = historyChangeTypeFilter.join(',');
                if (typeof historyDateRelative !== 'undefined' && historyDateRelative)
                    params.releaseDateRelative = historyDateRelative;
                if (typeof historyDateFrom !== 'undefined' && historyDateFrom)
                    params.releaseDateFrom = historyDateFrom;
                if (typeof historyDateTo !== 'undefined' && historyDateTo)
                    params.releaseDateTo = historyDateTo;
                if (typeof historyPartTypeFilter !== 'undefined' && historyPartTypeFilter.length)
                    params.partTypes = historyPartTypeFilter.join(',');
                if (typeof historyEntryMode !== 'undefined' && historyEntryMode && historyEntryMode !== 'all')
                    params.entryMode = historyEntryMode;
            } else if (tab === 'agile') {
                params.items = document.getElementById('agileManualItems').value;
                params.fields = (typeof agileSelectedFields !== 'undefined') ? agileSelectedFields.join(',') : '';
            } else if (tab === 'sku') {
                params.items = document.getElementById('skuItemInput').value;
                params.columns = getSkuSelectedColumns().join(',');
                // Save external source selections
                if (typeof selectedSourceColumns !== 'undefined' && typeof selectedSourceOrder !== 'undefined') {
                    params.extSources = JSON.stringify(buildSourceColumnsMap());
                }
            } else if (tab === 'changereviews') {
                params.analysts = JSON.stringify(crSelectedAnalysts);
            }

            fetch('/api/searches', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name.trim(), tab: tab, params: params })
            })
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) showCustomAlert('PLM Toolkit', 'Search saved successfully!');
            });
        });
}

function loadSavedSearch(s) {
    document.getElementById('savedSearchesDropdown').style.display = 'none';
    switchTab(s.tab);

    if (s.tab === 'changes') {
        document.getElementById('fieldInput').value = s.params.field || '';
        document.getElementById('itemInput').value = s.params.item || '';
        document.getElementById('oldInput').value = s.params.oldContains || '';
        document.getElementById('newInput').value = s.params.newContains || '';
        document.getElementById('userInput').value = s.params.user || '';
        document.getElementById('daysSelect').value = s.params.days || '7';
        doSearch();
    } else if (s.tab === 'bom') {
        document.getElementById('bomItemInput').value = s.params.items || '';
        document.getElementById('bomMaxDepth').value = s.params.maxDepth || '20';
    } else if (s.tab === 'parts') {
        document.getElementById('partsItemInput').value = s.params.items || '';
        document.getElementById('partsRelFrom').value = s.params.relFrom || '';
        document.getElementById('partsRelTo').value = s.params.relTo || '';
    } else if (s.tab === 'agile') {
        document.getElementById('agileManualItems').value = s.params.items || '';
        if (s.params.fields && typeof agileSelectedFields !== 'undefined') {
            agileSelectedFields = s.params.fields.split(',').filter(function(f) { return f; });
            if (typeof renderFieldTags === 'function') renderFieldTags();
        }
    } else if (s.tab === 'history') {
        document.getElementById('historyItemInput').value = s.params.items || '';
        // PT-72: restore the Change-History pre-filters into both the JS state vars
        // and their UI controls before searching, so the loaded search reproduces
        // the user's original constraints (date range, part type, entry mode, …).
        var p = s.params || {};
        if (typeof historyLifecycleFilter !== 'undefined') {
            historyLifecycleFilter = p.lifecyclePhases ? p.lifecyclePhases.split(',').filter(Boolean) : [];
            if (typeof historyRepaintLifecyclePills === 'function') historyRepaintLifecyclePills();
        }
        if (typeof historyChangeTypeFilter !== 'undefined') {
            historyChangeTypeFilter = p.changeTypes ? p.changeTypes.split(',').filter(Boolean) : [];
            var ctInp = document.getElementById('historyChangeTypeInput');
            if (ctInp) ctInp.value = historyChangeTypeFilter.join(', ');
        }
        if (typeof historyDateRelative !== 'undefined') {
            historyDateRelative = p.releaseDateRelative || '';
            var relSel = document.getElementById('historyDateRelative');
            if (relSel) relSel.value = historyDateRelative;
        }
        if (typeof historyDateFrom !== 'undefined') {
            historyDateFrom = p.releaseDateFrom || '';
            var dFrom = document.getElementById('historyDateFrom');
            if (dFrom) dFrom.value = historyDateFrom;
        }
        if (typeof historyDateTo !== 'undefined') {
            historyDateTo = p.releaseDateTo || '';
            var dTo = document.getElementById('historyDateTo');
            if (dTo) dTo.value = historyDateTo;
        }
        if (typeof historyPartTypeFilter !== 'undefined') {
            historyPartTypeFilter = p.partTypes ? p.partTypes.split(',').filter(Boolean) : [];
            if (typeof renderHistoryPartTypeChips === 'function') renderHistoryPartTypeChips();
        }
        if (typeof historyEntryMode !== 'undefined') {
            historyEntryMode = p.entryMode || 'all';
            var modeRadio = document.querySelector('input[name="historyEntryMode"][value="' + historyEntryMode + '"]');
            if (modeRadio) modeRadio.checked = true;
        }
        if (typeof historyUpdatePrefilterHint === 'function') historyUpdatePrefilterHint();
        doHistorySearch();
    } else if (s.tab === 'sku') {
        document.getElementById('skuItemInput').value = s.params.items || '';
        // Restore column selection
        if (s.params.columns && typeof restoreSkuColumns === 'function') {
            restoreSkuColumns(s.params.columns.split(','));
        }
        // Restore external source selections
        if (s.params.extSources && typeof restoreSkuExtSources === 'function') {
            try {
                restoreSkuExtSources(JSON.parse(s.params.extSources));
            } catch(e) {}
        }
    } else if (s.tab === 'changereviews') {
        if (s.params.analysts) {
            try {
                crSelectedAnalysts = JSON.parse(s.params.analysts);
                crRenderSelectedChips();
                crAutoLoaded = true; // prevent auto-load from overriding
                doChangeReviewSearch();
            } catch(e) {}
        }
    }
}

function deleteSavedSearch(id) {
    if (!id) {
        showCustomAlert('PLM Toolkit', 'Cannot delete: search has no ID.');
        return;
    }
    fetch('/api/searches/' + encodeURIComponent(id), { method: 'DELETE' })
        .then(function(res) {
            if (res.status === 401) {
                showCustomAlert('PLM Toolkit', 'Session expired. Please log in again.');
                return;
            }
            return res.json();
        })
        .then(function(data) {
            if (data && data.schedulesRemoved) {
                showCustomAlert('PLM Toolkit', data.message);
            }
            loadSavedSearches();
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'Could not delete: ' + (err.message || 'network error'));
            loadSavedSearches();
        });
}

// Close saved searches dropdown when clicking outside
document.addEventListener('click', function(e) {
    var dd = document.getElementById('savedSearchesDropdown');
    var btn = document.getElementById('savedSearchesBtn');
    if (dd && dd.style.display !== 'none' && !dd.contains(e.target) && e.target !== btn) {
        dd.style.display = 'none';
    }
});

// === Custom Modal (replaces browser prompt/alert) ===
var _modalResolve = null;

function showCustomPrompt(title, message, placeholder) {
    return new Promise(function(resolve) {
        _modalResolve = resolve;
        document.getElementById('customModalTitle').textContent = title;
        document.getElementById('customModalMessage').textContent = message;
        var input = document.getElementById('customModalInput');
        input.style.display = 'block';
        input.value = '';
        input.placeholder = placeholder || '';
        document.getElementById('customModalCancelBtn').style.display = '';
        document.getElementById('customModal').style.display = 'flex';
        setTimeout(function() { input.focus(); }, 100);
    });
}

function showCustomAlert(title, message) {
    return new Promise(function(resolve) {
        _modalResolve = resolve;
        document.getElementById('customModalTitle').textContent = title;
        document.getElementById('customModalMessage').textContent = message;
        document.getElementById('customModalInput').style.display = 'none';
        document.getElementById('customModalCancelBtn').style.display = 'none';
        document.getElementById('customModal').style.display = 'flex';
    });
}

function showCustomConfirm(title, message) {
    return new Promise(function(resolve) {
        _modalResolve = resolve;
        document.getElementById('customModalTitle').textContent = title;
        document.getElementById('customModalMessage').textContent = message;
        document.getElementById('customModalInput').style.display = 'none';
        document.getElementById('customModalCancelBtn').style.display = '';
        document.getElementById('customModal').style.display = 'flex';
    });
}

function customModalOk() {
    document.getElementById('customModal').style.display = 'none';
    var input = document.getElementById('customModalInput');
    if (_modalResolve) {
        _modalResolve(input.style.display !== 'none' ? input.value : true);
        _modalResolve = null;
    }
}

function customModalCancel() {
    document.getElementById('customModal').style.display = 'none';
    if (_modalResolve) { _modalResolve(null); _modalResolve = null; }
}

function isInputFocused() {
    var el = document.activeElement;
    return el && (el.tagName === 'INPUT' || el.tagName === 'SELECT' || el.tagName === 'TEXTAREA');
}

// === Drag & Drop for file inputs ===
function setupDropZone(zoneId, fileInputId) {
    var zone = document.getElementById(zoneId);
    var input = document.getElementById(fileInputId);
    if (!zone || !input) return;

    zone.addEventListener('dragover', function(e) {
        e.preventDefault();
        zone.classList.add('drag-over');
    });
    zone.addEventListener('dragleave', function(e) {
        e.preventDefault();
        zone.classList.remove('drag-over');
    });
    zone.addEventListener('drop', function(e) {
        e.preventDefault();
        zone.classList.remove('drag-over');
        if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
            input.files = e.dataTransfer.files;
            // Trigger change event so any listeners fire
            input.dispatchEvent(new Event('change', { bubbles: true }));
        }
    });
}

document.addEventListener('DOMContentLoaded', function() {
    setupDropZone('bomDropZone', 'bomFileInput');
    setupDropZone('partsDropZone', 'partsFileInput');
    setupDropZone('agileDropZone', 'agileFileInput');
    setupDropZone('historyDropZone', 'historyFileInput');
    setupDropZone('compareDropZone', 'compareFileInput');
    setupDropZone('skuDropZone', 'skuFileInput');
    initTabDragDrop();
});

// === Draggable Tab Reordering ===
function initTabDragDrop() {
    var tabBar = document.querySelector('.tab-bar');
    if (!tabBar) return;
    var tabs = tabBar.querySelectorAll('.tab');
    var dragSrc = null;

    tabs.forEach(function(tab) {
        tab.draggable = true;

        tab.addEventListener('dragstart', function(e) {
            dragSrc = tab;
            tab.classList.add('tab-dragging');
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setData('text/plain', tab.id);
        });

        tab.addEventListener('dragend', function() {
            tab.classList.remove('tab-dragging');
            tabBar.querySelectorAll('.tab').forEach(function(t) { t.classList.remove('tab-drag-over'); });
            dragSrc = null;
        });

        tab.addEventListener('dragover', function(e) {
            e.preventDefault();
            e.dataTransfer.dropEffect = 'move';
            if (tab !== dragSrc) tab.classList.add('tab-drag-over');
        });

        tab.addEventListener('dragleave', function() {
            tab.classList.remove('tab-drag-over');
        });

        tab.addEventListener('drop', function(e) {
            e.preventDefault();
            tab.classList.remove('tab-drag-over');
            if (!dragSrc || dragSrc === tab) return;

            // Reorder: insert dragged tab before or after the drop target
            var allTabs = Array.from(tabBar.querySelectorAll('.tab'));
            var dragIdx = allTabs.indexOf(dragSrc);
            var dropIdx = allTabs.indexOf(tab);
            if (dragIdx < dropIdx) {
                tabBar.insertBefore(dragSrc, tab.nextSibling);
            } else {
                tabBar.insertBefore(dragSrc, tab);
            }
            saveTabOrder();
        });
    });

    // Restore saved order on load
    restoreTabOrder();
}

function saveTabOrder() {
    var tabBar = document.querySelector('.tab-bar');
    var order = Array.from(tabBar.querySelectorAll('.tab')).map(function(t) { return t.id; });
    localStorage.setItem('tabOrder', JSON.stringify(order));
}

function restoreTabOrder() {
    var saved = localStorage.getItem('tabOrder');
    if (!saved) return;
    try {
        var order = JSON.parse(saved);
        var tabBar = document.querySelector('.tab-bar');
        if (!tabBar) return;
        // Only reorder if the saved IDs match current tabs (handles new/removed tabs gracefully)
        var currentIds = Array.from(tabBar.querySelectorAll('.tab')).map(function(t) { return t.id; });
        // Add any new tabs not in saved order to the end
        var merged = order.filter(function(id) { return currentIds.indexOf(id) >= 0; });
        currentIds.forEach(function(id) { if (merged.indexOf(id) < 0) merged.push(id); });

        merged.forEach(function(id) {
            var tab = document.getElementById(id);
            if (tab) tabBar.appendChild(tab);
        });
    } catch (e) { /* ignore corrupt localStorage */ }
}

// === Feedback ===
function openFeedback() { document.getElementById('feedbackModal').style.display = 'flex'; }
function closeFeedback() { document.getElementById('feedbackModal').style.display = 'none'; }

// PT-38: render a chip per selected file so the user sees what's being uploaded.
function _renderFeedbackChips() {
    var input = document.getElementById('feedbackAttachment');
    var chips = document.getElementById('feedbackAttachmentChips');
    if (!chips) return;
    chips.innerHTML = '';
    if (!input || !input.files) return;
    Array.from(input.files).forEach(function (f) {
        var kb = f.size / 1024;
        var sizeStr = kb < 1024 ? kb.toFixed(0) + ' KB' : (kb / 1024).toFixed(1) + ' MB';
        var chip = document.createElement('span');
        chip.style.cssText = 'display:inline-flex; align-items:center; gap:4px; padding:3px 8px; background:#f1f3f6; border:1px solid #d0d5dd; border-radius:12px; font-size:11px; color:#0F1720;';
        chip.textContent = f.name + ' · ' + sizeStr;
        chips.appendChild(chip);
    });
}
document.addEventListener('change', function (e) {
    if (e.target && e.target.id === 'feedbackAttachment') _renderFeedbackChips();
});

// PT-70: paste screenshots straight into the feedback modal (like AI chat tools)
// so reporters don't have to save → upload. Only active while the modal is open;
// gated to image/* clipboard items to avoid hijacking ordinary text paste in the
// textarea. Files appended to the existing FileList via DataTransfer so a paste
// plus a manual file pick coexist.
document.addEventListener('paste', function (e) {
    var modal = document.getElementById('feedbackModal');
    if (!modal || modal.style.display === 'none') return;
    var cd = e.clipboardData;
    if (!cd || !cd.items) return;
    var images = [];
    for (var i = 0; i < cd.items.length; i++) {
        var item = cd.items[i];
        if (item.kind === 'file' && item.type && item.type.indexOf('image/') === 0) {
            var blob = item.getAsFile();
            if (blob) images.push(blob);
        }
    }
    if (!images.length) return;
    e.preventDefault();
    var input = document.getElementById('feedbackAttachment');
    if (!input) return;
    var dt = new DataTransfer();
    if (input.files) {
        for (var j = 0; j < input.files.length; j++) dt.items.add(input.files[j]);
    }
    var stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    images.forEach(function (blob, k) {
        var ext = (blob.type.split('/')[1] || 'png').toLowerCase();
        // Browsers hand us a generic 'image.png' name; give it a unique, timestamped one
        // so multiple pastes don't all collide in the chips and on the server.
        var name = 'pasted-' + stamp + (k > 0 ? '-' + (k + 1) : '') + '.' + ext;
        var named = new File([blob], name, { type: blob.type, lastModified: Date.now() });
        dt.items.add(named);
    });
    input.files = dt.files;
    _renderFeedbackChips();
});

function submitFeedback() {
    var category = document.getElementById('feedbackCategory').value;
    var text = document.getElementById('feedbackText').value.trim();
    if (!category) {
        showCustomAlert('PLM Toolkit', 'Please pick a category so we can route your feedback correctly.');
        document.getElementById('feedbackCategory').focus();
        return;
    }
    if (!text) { showCustomAlert('PLM Toolkit', 'Please enter your feedback.'); return; }

    var fileInput = document.getElementById('feedbackAttachment');
    var files = (fileInput && fileInput.files) ? Array.from(fileInput.files) : [];

    // PT-38: 25 MB cap across ALL selected files (mail relay tops out around there).
    var TOTAL_CAP = 25 * 1024 * 1024;
    var totalSize = files.reduce(function (acc, f) { return acc + f.size; }, 0);
    if (totalSize > TOTAL_CAP) {
        showCustomAlert('PLM Toolkit',
            'Attachments too large: ' + (totalSize / 1024 / 1024).toFixed(1) +
            ' MB total. Maximum 25 MB across all files combined.');
        return;
    }

    var formData = new FormData();
    formData.append('category', category);
    formData.append('text', text);
    // Send under the same field name N times — Spring binds these to a List<MultipartFile>.
    files.forEach(function (f) { formData.append('attachments', f); });

    fetch('/api/support/feedback', {
        method: 'POST',
        body: formData
    })
    .then(checkAuth)
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (!data.success) {
            // PT-45: backend rejected the submission (e.g. gibberish gate).
            // Keep the dialog open with the text intact so the user can edit
            // and retry — closing the form and asking them to re-open is a
            // worse UX than surfacing the error inline.
            showCustomAlert('PLM Toolkit', data.message || 'Failed to submit.');
            return;
        }
        closeFeedback();
        document.getElementById('feedbackText').value = '';
        // Reset category to the blank "Select a category…" prompt so the next
        // submission also has to be explicit (PT-34).
        document.getElementById('feedbackCategory').selectedIndex = 0;
        if (fileInput) fileInput.value = '';
        _renderFeedbackChips();
        showCustomAlert('PLM Toolkit', 'Thank you for your feedback!');
    })
    .catch(function() { showCustomAlert('PLM Toolkit', 'Failed to submit feedback.'); });
}

// === AD Offline Banner ===
function showAdOfflineBanner(source) {
    var existing = document.getElementById('adOfflineBanner');
    if (existing) return; // already showing
    var msg = source === 'emergency'
        ? 'You are logged in with the <strong>emergency local account</strong>. SanDisk sign-in is currently unreachable.'
        : 'SanDisk sign-in is currently <strong>unreachable</strong>. You were logged in using cached credentials. Some features may be limited.';
    var banner = document.createElement('div');
    banner.id = 'adOfflineBanner';
    banner.style.cssText = 'background:#f8d7da; color:#721c24; text-align:center; padding:8px 16px; font-size:13px; border-bottom:1px solid #f5c6cb; position:relative;';
    banner.innerHTML = '<span style="font-size:15px; margin-right:6px;">&#9888;</span>' + msg +
        '<button onclick="this.parentNode.remove()" style="position:absolute; right:12px; top:50%; transform:translateY(-50%); background:none; border:none; color:#721c24; font-size:18px; cursor:pointer; opacity:0.6;">&times;</button>';
    var tabs = document.querySelector('.tab-bar');
    if (tabs && tabs.parentNode) {
        tabs.parentNode.insertBefore(banner, tabs);
    }
}

// === BOM Compare ===
var bomCompareData = [];
// Labels harmonised with rcFields where the underlying concept is the same:
//  - 'Type'      ↔ rcFields bomType     (was 'Item Type')
//  - 'Comp Rev'  ↔ rcFields componentRev (was 'Rev')
// 'Status' / 'Find #' are Two-Parts-only and stay distinct; 'Seq #' on the
// Revs side is a different concept (sequence number) so it stays separate too.
var bcFields = [
    { key: 'qty', label: 'Qty', checked: true },
    { key: 'description', label: 'Description', checked: true },
    { key: 'itemType', label: 'Type', checked: false },
    { key: 'status', label: 'Status', checked: false },
    { key: 'rev', label: 'Comp Rev', checked: false },
    { key: 'findNum', label: 'Find #', checked: false },
    { key: 'refDes', label: 'Ref Designator', checked: false },
    { key: 'notes', label: 'BOM Notes', checked: false }
];

function doBomCompare() {
    var a = document.getElementById('bomCompareA').value.trim();
    var b = document.getElementById('bomCompareB').value.trim();
    if (!a || !b) { showCustomAlert('PLM Toolkit', 'Enter both BOM A and BOM B.'); return; }

    var btn = document.getElementById('bomCompareBtn');
    btn.disabled = true;
    btn.textContent = 'Comparing...';
    document.getElementById('bomCompareEmpty').style.display = 'none';
    document.getElementById('bomCompareNoResults').style.display = 'none';
    document.getElementById('bomCompareTableWrapper').style.display = 'none';
    document.getElementById('bomCompareStats').style.display = 'none';
    document.getElementById('bomCompareFilter').style.display = 'none';

    fetch('/api/bom/compare?bomA=' + encodeURIComponent(a) + '&bomB=' + encodeURIComponent(b))
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.textContent = 'Compare';
            if (data.error) {
                showCustomAlert('PLM Toolkit', data.error);
                document.getElementById('bomCompareEmpty').style.display = 'block';
                return;
            }
            bomCompareData = data.diff || [];
            renderBomCompareStats(data);
            renderBcFieldPicker();
            renderBomCompareSideBySide();
            document.getElementById('bomCompareFilter').style.display = 'block';
            document.getElementById('bomCompareActions').style.display = 'block';
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.textContent = 'Compare';
            showCustomAlert('PLM Toolkit', 'Compare failed: ' + err.message);
        });
}

function renderBomCompareStats(data) {
    // Store parent info for header rendering
    window._bcData = data;
    // Hide redundant source pill
    var pill = document.querySelector('#panelBomCompare .data-source-pill');
    if (pill) pill.style.display = 'none';
    var html = '<div class="insight-strip" style="display:flex; gap:4px; flex-wrap:wrap;">';
    html += '<div class="insight-tile" style="min-width:100px;"><div class="insight-k">BOM A</div><div class="insight-v">' + data.countA + ' <span style="font-size:12px; font-weight:400; color:#888;">rows</span></div><div class="insight-sub">' + esc(data.bomA) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:100px;"><div class="insight-k">BOM B</div><div class="insight-v">' + data.countB + ' <span style="font-size:12px; font-weight:400; color:#888;">rows</span></div><div class="insight-sub">' + esc(data.bomB) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k" style="color:#dc3545;">Only in A</div><div class="insight-v" style="color:#dc3545;">' + (data.onlyInA || 0) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k" style="color:#28a745;">Only in B</div><div class="insight-v" style="color:#28a745;">' + (data.onlyInB || 0) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k" style="color:#e67e22;">Different</div><div class="insight-v" style="color:#e67e22;">' + (data.different || 0) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k">Same</div><div class="insight-v">' + (data.same || 0) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:70px;"><div class="insight-k">Time</div><div class="insight-v insight-v-sm">' + data.queryTimeMs + 'ms</div></div>';
    html += '<div style="margin-left:auto; display:flex; align-items:center; gap:6px; flex-shrink:0;">';
    html += '<button class="btn-export" onclick="doBomCompareExport()" style="font-size:12px; white-space:nowrap;">&#8595; Export Excel</button>';
    html += '<button class="btn-export" onclick="doBomCompareEmail()" id="bomCompareEmailBtn" style="font-size:12px; white-space:nowrap;">&#9993; Email to Me</button>';
    html += '</div>';
    html += '</div>';
    var el = document.getElementById('bomCompareStats');
    el.innerHTML = html;
    el.style.display = 'block';
}

function getCheckedBcFields() {
    return bcFields.filter(function(f) { return f.checked; });
}

function renderBomCompareSideBySide() {
    var showAdded = document.getElementById('bcShowAdded').checked;
    var showRemoved = document.getElementById('bcShowRemoved').checked;
    var showChanged = document.getElementById('bcShowChanged').checked;
    var showMatch = document.getElementById('bcShowMatch').checked;

    var filtered = bomCompareData.filter(function(r) {
        if (r.diffStatus === 'ADDED') return showAdded;
        if (r.diffStatus === 'REMOVED') return showRemoved;
        if (r.diffStatus === 'CHANGED') return showChanged;
        if (r.diffStatus === 'MATCH') return showMatch;
        return true;
    });

    var bomA = document.getElementById('bomCompareA').value.trim();
    var bomB = document.getElementById('bomCompareB').value.trim();
    var d = window._bcData || {};
    var fields = getCheckedBcFields();
    var fCount = 1 + fields.length; // Component + selected fields
    var divCol = '<td style="width:6px; min-width:6px; max-width:6px; background:#e67e22; padding:0; border:none;"></td>';

    // Build parent header text with rev + description
    var headerA = esc(bomA);
    if (d.bomArev || d.bomAdesc) {
        headerA += '<div style="font-size:11px; font-weight:400; opacity:0.85; margin-top:2px;">';
        if (d.bomArev) headerA += 'Rev ' + esc(d.bomArev);
        if (d.bomArev && d.bomAdesc) headerA += ' &mdash; ';
        if (d.bomAdesc) headerA += esc(d.bomAdesc);
        headerA += '</div>';
    }
    var headerB = esc(bomB);
    if (d.bomBrev || d.bomBdesc) {
        headerB += '<div style="font-size:11px; font-weight:400; opacity:0.85; margin-top:2px;">';
        if (d.bomBrev) headerB += 'Rev ' + esc(d.bomBrev);
        if (d.bomBrev && d.bomBdesc) headerB += ' &mdash; ';
        if (d.bomBdesc) headerB += esc(d.bomBdesc);
        headerB += '</div>';
    }

    // Single table with a divider column — guarantees row alignment
    var html = '<table class="data-table" style="font-size:12px; width:100%; border-collapse:collapse;">';

    // Banner row: BOM A name | divider | BOM B name
    html += '<thead><tr>';
    html += '<th colspan="' + fCount + '" style="text-align:center; background:#1a3a5c; color:white; font-size:13px; padding:10px 8px;">' + headerA + '</th>';
    html += '<th style="width:6px; background:#e67e22; padding:0; border:none;"></th>';
    html += '<th colspan="' + fCount + '" style="text-align:center; background:#2c5282; color:white; font-size:13px; padding:10px 8px;">' + headerB + '</th>';
    html += '</tr>';

    // Field headers
    html += '<tr>';
    // A side headers
    html += '<th style="background:#e8f0fe; color:#1a3a5c;">Component</th>';
    fields.forEach(function(f) { html += '<th style="background:#e8f0fe; color:#1a3a5c; font-size:11px;">' + f.label + '</th>'; });
    html += '<th style="width:6px; background:#e67e22; padding:0; border:none;"></th>';
    // B side headers
    html += '<th style="background:#dbeafe; color:#2c5282;">Component</th>';
    fields.forEach(function(f) { html += '<th style="background:#dbeafe; color:#2c5282; font-size:11px;">' + f.label + '</th>'; });
    html += '</tr></thead><tbody>';

    var rowNum = 0;
    filtered.forEach(function(r) {
        var isOnlyInA = r.diffStatus === 'REMOVED';
        var isOnlyInB = r.diffStatus === 'ADDED';
        var isChanged = r.diffStatus === 'CHANGED';
        var stripe = (rowNum % 2 === 1) ? '#f0f1f3' : '#ffffff';
        var rowBg = isChanged ? '#fff8e6' : stripe;
        rowNum++;
        html += '<tr style="background:' + rowBg + ';">';

        // === A side ===
        if (isOnlyInB) {
            html += '<td style="background:#f8f8f8; color:#ccc; font-style:italic;">&mdash;</td>';
            fields.forEach(function() { html += '<td style="background:#f8f8f8;"></td>'; });
        } else {
            html += '<td style="font-weight:600; white-space:nowrap;">' + esc(r.component) + '</td>';
            fields.forEach(function(f) {
                var valA = r[f.key + 'A'] || '';
                var valB = r[f.key + 'B'] || '';
                var style = '';
                if (isChanged && valA !== valB) style = 'background:#fce4e4; color:#c0392b; font-weight:600;';
                html += '<td style="' + style + '">' + esc(valA) + '</td>';
            });
        }

        // Divider
        html += divCol;

        // === B side ===
        if (isOnlyInA) {
            html += '<td style="background:#f8f8f8; color:#ccc; font-style:italic;">&mdash;</td>';
            fields.forEach(function() { html += '<td style="background:#f8f8f8;"></td>'; });
        } else {
            html += '<td style="font-weight:600; white-space:nowrap;">' + esc(r.component) + '</td>';
            fields.forEach(function(f) {
                var valA = r[f.key + 'A'] || '';
                var valB = r[f.key + 'B'] || '';
                var style = '';
                if (isChanged && valA !== valB) style = 'background:#fce4e4; color:#c0392b; font-weight:600;';
                html += '<td style="' + style + '">' + esc(valB) + '</td>';
            });
        }

        html += '</tr>';
    });

    html += '</tbody></table>';

    document.getElementById('bomCompareBody').innerHTML = html;
    document.getElementById('bomCompareTableWrapper').style.display = '';
}

// Alias for filter checkbox onchange
function filterBomCompare() { renderBomCompareSideBySide(); }

function toggleBcField(key) {
    bcFields.forEach(function(f) { if (f.key === key) f.checked = !f.checked; });
    renderBcFieldPicker();
    if (bomCompareData.length > 0) renderBomCompareSideBySide();
}

function bcToggleAllFields() {
    var allChecked = bcFields.every(function(f) { return f.checked; });
    bcFields.forEach(function(f) { f.checked = !allChecked; });
    renderBcFieldPicker();
    if (bomCompareData.length > 0) renderBomCompareSideBySide();
}

function renderBcFieldPicker() {
    var el = document.getElementById('bcFieldPicker');
    if (!el) return;
    var allChecked = bcFields.every(function(f) { return f.checked; });
    var anyChecked = bcFields.some(function(f) { return f.checked; });
    var masterLabel = allChecked ? 'Clear all' : 'Select all';
    var masterHtml = '<label style="margin-right:14px; cursor:pointer; white-space:nowrap; padding-right:10px; border-right:1px solid #d0d5dd;">' +
        '<input type="checkbox" ' + (allChecked ? 'checked' : '') +
        ' onchange="bcToggleAllFields()" style="margin-right:3px;">' +
        '<span style="font-weight:600; color:#4a6fa5;">' + masterLabel + '</span></label>';
    var fieldsHtml = bcFields.map(function(f) {
        return '<label style="margin-right:10px; cursor:pointer; white-space:nowrap;">' +
            '<input type="checkbox" ' + (f.checked ? 'checked' : '') +
            ' onchange="toggleBcField(\'' + f.key + '\')" style="margin-right:3px;">' +
            '<span style="' + (f.checked ? 'font-weight:600; color:#333;' : 'color:#888;') + '">' + f.label + '</span></label>';
    }).join('');
    el.innerHTML = masterHtml + fieldsHtml;
    // Reflect indeterminate state on the master checkbox when some-but-not-all are checked.
    var masterCb = el.querySelector('input[onchange="bcToggleAllFields()"]');
    if (masterCb) masterCb.indeterminate = !allChecked && anyChecked;
}

function doBomCompareClear() {
    document.getElementById('bomCompareA').value = '';
    document.getElementById('bomCompareB').value = '';
    bomCompareData = [];
    document.getElementById('bomCompareTableWrapper').style.display = 'none';
    document.getElementById('bomCompareStats').style.display = 'none';
    document.getElementById('bomCompareFilter').style.display = 'none';
    document.getElementById('bomCompareActions').style.display = 'none';
    document.getElementById('bomCompareNoResults').style.display = 'none';
    document.getElementById('bomCompareEmpty').style.display = 'block';
    var pill = document.querySelector('#panelBomCompare .data-source-pill');
    if (pill) pill.style.display = '';
}

function doBomCompareExport() {
    guardExport(function() {
        var a = document.getElementById('bomCompareA').value.trim();
        var b = document.getElementById('bomCompareB').value.trim();
        if (!a || !b) return;
        window.location.href = '/api/bom/compare/export?bomA=' + encodeURIComponent(a) + '&bomB=' + encodeURIComponent(b);
    });
}

function doBomCompareEmail() {
    var a = document.getElementById('bomCompareA').value.trim();
    var b = document.getElementById('bomCompareB').value.trim();
    if (!a || !b) return;
    var btn = document.getElementById('bomCompareEmailBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Sending...';

    fetch('/api/bom/compare/email?bomA=' + encodeURIComponent(a) + '&bomB=' + encodeURIComponent(b), { method: 'POST' })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            if (data.success) {
                btn.innerHTML = '&#10003; Sent!';
                setTimeout(function() { btn.innerHTML = origText; }, 2000);
            } else {
                btn.innerHTML = origText;
                showCustomAlert('PLM Toolkit', data.message || 'Failed.');
            }
        })
        .catch(function() { btn.disabled = false; btn.innerHTML = origText; showCustomAlert('PLM Toolkit', 'Email failed.'); });
}

// === Tab Preferences ===
// Notes on consolidation:
//   - "Items" is a parent tab grouping Field Changes / Part Extract / Agile
//     Lookup / SKU Lookup. Only the parent is listed here so the user sees ONE
//     toggle for the whole group; the sub-tabs are always available together.
//     applyServerTabPermissions hides the parent only if NONE of the four child
//     keys appear in the server's allowedTabs (handled below).
//   - "BOM" is similarly a parent grouping Explorer + Compare.
//   - "Field Changes" key on the server is "fields" (legacy); the items parent
//     uses a synthetic "items" key that's not in the server catalog.
var TAB_PREFS_CONFIG = [
    { id: 'tabItems', label: 'Items (Field Changes / Part Extract / Agile Lookup / SKU Lookup)', key: 'items', alwaysOn: true },
    { id: 'tabBom', label: 'BOM (Explorer + Compare)', key: 'bom' },
    { id: 'tabEcoTimeline', label: 'ECO Timeline', key: 'ecotimeline' },
    { id: 'tabHistory', label: 'Change History', key: 'history' },
    { id: 'tabReports', label: 'Utilities', key: 'reports', adminOnly: true },
    { id: 'tabAdHealth', label: 'AD Health', key: 'adhealth', adminOnly: true },
    { id: 'tabEcnReport', label: 'ECN Dashboard', key: 'ecnreport' },
    { id: 'tabDocReview', label: 'Review Tracker', key: 'docreview' },
    { id: 'tabImsReview', label: 'IMS Dashboard', key: 'ims-review' },
    { id: 'tabItEnhancements', label: 'IT Enhancements', key: 'it-enhancements' },
    { id: 'tabSdsm', label: 'Shop-Floor Docs', key: 'sdsm' },
    { id: 'tabSingleSole', label: 'Single/Sole Source Report', key: 'singlesole' },
    // PT-26: Work Instructions / User Management / Extensions / Data Compare /
    // Change Reviews are now sub-tabs of the synthetic "more" parent. The
    // individual buttons (display:none) stay in the DOM so server permission
    // grants on those keys keep working.
    { id: 'tabMore', label: 'More\u2026 (Work Instructions / User Management / Extensions / Data Compare / Change Reviews)', key: 'more' },
    { id: 'tabAiEval', label: 'AI Eval', key: 'aieval', adminOnly: false }
];

function getTabPrefs() {
    try {
        var saved = localStorage.getItem('plm-tab-prefs');
        return saved ? JSON.parse(saved) : null;
    } catch (e) { return null; }
}

function saveTabPrefs(prefs) {
    localStorage.setItem('plm-tab-prefs', JSON.stringify(prefs));
}

/**
 * Hide any tab whose key is NOT in the server-supplied allowedTabs array.
 * Doesn't re-show anything (so the existing admin/extension/canEdit show-paths
 * still work). Server is the source of truth for per-user denials; the JS
 * "show-on-some-other-condition" paths can re-show a tab on top of this.
 */
function applyServerTabPermissions(allowedTabs) {
    if (!allowedTabs) return;
    var allowedSet = {};
    allowedTabs.forEach(function (k) { allowedSet[k] = true; });
    // Synthetic parent keys: visible if ANY of their child keys is allowed.
    // - "items"  groups fields / parts / agile / sku
    // - "more"   groups helpcenter / permissions / extensions / compare / changereviews (PT-26)
    // (BOM Explorer key is "bom" which the server returns directly, no synthetic.)
    if (allowedSet['fields'] || allowedSet['parts'] || allowedSet['agile'] || allowedSet['sku']) {
        allowedSet['items'] = true;
    }
    if (allowedSet['helpcenter'] || allowedSet['permissions'] || allowedSet['extensions']
        || allowedSet['compare'] || allowedSet['changereviews']) {
        allowedSet['more'] = true;
    }
    TAB_PREFS_CONFIG.forEach(function (t) {
        if (allowedSet[t.key]) return;
        var el = document.getElementById(t.id);
        if (el) el.style.display = 'none';
    });

    // Sub-tab gating — hide the sub-tab buttons inside parent panels when their
    // own key isn't in allowedTabs. Without this, admins could toggle off e.g.
    // "BOM → Compare" in User Management but the user could still click into
    // panelBomCompare via the sub-tab nav (the data fetches would 403 but the
    // panel chrome would still appear). With this, the sub-tab button itself
    // is hidden, matching the user's intent for the per-tab grant.
    var subToKey = {
        // BOM panel sub-tab buttons
        'bomSubTabExplorer':  'bom',
        'bomSubTabExplorer2': 'bom',
        'bomSubTabCompare':   'bomcompare',
        'bomSubTabCompare2':  'bomcompare'
    };
    Object.keys(subToKey).forEach(function (id) {
        var el = document.getElementById(id);
        if (!el) return;
        if (!allowedSet[subToKey[id]]) el.style.display = 'none';
    });
    // Items sub-tabs (rendered in 4 mirrored navs across panelChanges, panelParts,
    // panelAgile, panelSku). Each button has data-sub = its key. Note the Items
    // child keys differ from data-sub names: data-sub="changes" → key "fields".
    var dataSubToKey = {
        'changes': 'fields',
        'parts':   'parts',
        'agile':   'agile',
        'sku':     'sku'
    };
    document.querySelectorAll('.items-sub').forEach(function (btn) {
        var sub = btn.getAttribute('data-sub');
        var key = dataSubToKey[sub];
        if (key && !allowedSet[key]) btn.style.display = 'none';
    });
    // More sub-tabs (rendered in 5 mirrored navs across panelHelpCenter,
    // panelPermissions, panelExtensions, panelCompare, panelChangeReviews). Each
    // button has data-sub = its own key. PT-26.
    document.querySelectorAll('.more-sub').forEach(function (btn) {
        var sub = btn.getAttribute('data-sub');
        if (sub && !allowedSet[sub]) btn.style.display = 'none';
    });
}

function applyTabPrefs() {
    var prefs = getTabPrefs();
    if (!prefs) return; // no prefs saved = show all
    TAB_PREFS_CONFIG.forEach(function(t) {
        if (t.alwaysOn) return;
        var el = document.getElementById(t.id);
        if (!el) return;
        // Don't override admin-only visibility — only hide if user unchecked AND tab is currently visible
        if (prefs[t.key] === false && el.style.display !== 'none') {
            el.style.display = 'none';
        }
    });
}

function renderTabPrefs() {
    var prefs = getTabPrefs() || {};
    var list = document.getElementById('tabPrefsList');
    if (!list) return;
    var html = '';
    TAB_PREFS_CONFIG.forEach(function(t) {
        var el = document.getElementById(t.id);
        // Skip tabs user can't see (admin-only for non-admins)
        if (el && el.style.display === 'none' && !prefs.hasOwnProperty(t.key)) return;
        if (t.adminOnly && !window.extIsAdmin) return;
        var checked = t.alwaysOn || prefs[t.key] !== false;
        html += '<label style="display:block; padding:3px 0; cursor:' + (t.alwaysOn ? 'default' : 'pointer') + '; font-size:13px;">' +
            '<input type="checkbox" ' + (checked ? 'checked' : '') + ' ' +
            (t.alwaysOn ? 'disabled' : 'onchange="toggleTabPref(\'' + t.key + '\', this.checked)"') +
            ' style="margin-right:8px;">' +
            '<span style="' + (checked ? 'color:#333;' : 'color:#aaa;') + '">' + t.label + '</span>' +
            (t.alwaysOn ? ' <span style="font-size:10px; color:#aaa;">(default)</span>' : '') +
            '</label>';
    });
    list.innerHTML = html;
}

function toggleTabPref(key, visible) {
    var prefs = getTabPrefs() || {};
    prefs[key] = visible;
    saveTabPrefs(prefs);
    // Apply immediately
    var cfg = TAB_PREFS_CONFIG.find(function(t) { return t.key === key; });
    if (cfg) {
        var el = document.getElementById(cfg.id);
        if (el) el.style.display = visible ? '' : 'none';
        // If hiding the active tab, switch to Field Changes
        if (!visible && el && el.classList.contains('active')) {
            switchTab('changes');
        }
    }
    renderTabPrefs();
}

function toggleTabPrefs() {
    var dd = document.getElementById('tabPrefsDropdown');
    if (dd.style.display === 'none') {
        renderTabPrefs();
        dd.style.display = 'block';
    } else {
        dd.style.display = 'none';
    }
}

// Close tab prefs dropdown when clicking outside
document.addEventListener('click', function(e) {
    var dd = document.getElementById('tabPrefsDropdown');
    var trigger = document.getElementById('userGreeting');
    if (dd && dd.style.display !== 'none' && !dd.contains(e.target) && e.target !== trigger) {
        dd.style.display = 'none';
    }
});

// === BOM Compare mode toggle ===
var bomCompareMode = 'same'; // 'same' (one part, two revs) or 'diff' (two parts, two revs)

function switchBomCompareMode(mode) {
    // mode = 'same' (one part, two revs) or 'diff' (two parts, two revs).
    // Both modes use the unified /api/rev-compare/compare endpoint; the only
    // difference is whether `partB` is supplied.
    bomCompareMode = mode;
    var same = (mode === 'same');
    var sameSec = document.getElementById('bcSameSection');
    var diffSec = document.getElementById('bcDiffSection');
    if (sameSec) sameSec.style.display = same ? '' : 'none';
    if (diffSec) diffSec.style.display = same ? 'none' : '';
    var sameBtn = document.getElementById('bcModeSameBtn');
    var diffBtn = document.getElementById('bcModeDiffBtn');
    if (sameBtn) sameBtn.className = 'bc-mode-pill' + (same ? ' active' : '');
    if (diffBtn) diffBtn.className = 'bc-mode-pill' + (same ? '' : ' active');

    // Switching modes resets the result area so stale results from the prior
    // mode don't sit around looking valid against the wrong inputs.
    if (typeof doRevCompareClear === 'function') doRevCompareClear();
}

// =========================================================================
// Different-parts mode: each side has its own part input + rev dropdown.
// Both sides feed into the same /api/rev-compare/compare endpoint, with
// `partB` set to the second part. Cached rev metadata per side so doBcDiffCompare
// can serialize "rev|change" the same way the same-part flow does.
// =========================================================================
var bcDiffRevsCache = { A: [], B: [] };

// Single-side loader kept for back-compat with any callers (e.g. Enter-on-input
// flows in older builds). New UI uses loadBcDiffRevsBoth which calls this for both.
function loadBcDiffRevs(side) {
    var inputId = side === 'A' ? 'bcDiffPartAInput' : 'bcDiffPartBInput';
    var dropId  = side === 'A' ? 'bcDiffRevA'      : 'bcDiffRevB';
    var part = document.getElementById(inputId).value.trim();
    if (!part) return Promise.resolve({ side: side, ok: false, reason: 'empty' });

    return fetch('/api/rev-compare/revs?part=' + encodeURIComponent(part))
        .then(checkAuth).then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data || data.length === 0) {
                return { side: side, ok: false, reason: 'no-revs', part: part };
            }
            bcDiffRevsCache[side] = data;
            populateOneRevDropdown(dropId, data);
            return { side: side, ok: true, part: part };
        });
}

// PT-39 follow-up: one button loads revs for BOTH sides in parallel. If either
// side is empty, just load the side that's filled; if both filled, fan out.
function loadBcDiffRevsBoth() {
    var partA = document.getElementById('bcDiffPartAInput').value.trim();
    var partB = document.getElementById('bcDiffPartBInput').value.trim();
    if (!partA && !partB) { showCustomAlert('PLM Toolkit', 'Enter at least one part number.'); return; }

    var btn = document.getElementById('bcDiffLoadBothBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Loading…'; }
    document.getElementById('revCompareEmpty').style.display = 'none';
    document.getElementById('revCompareNoResults').style.display = 'none';

    var calls = [];
    if (partA) calls.push(loadBcDiffRevs('A'));
    if (partB) calls.push(loadBcDiffRevs('B'));

    Promise.all(calls)
        .then(function(results) {
            if (btn) { btn.disabled = false; btn.textContent = 'Load Revs'; }
            var anyOk = results.some(function(r) { return r && r.ok; });
            if (anyOk) {
                document.getElementById('bcDiffRevRow').style.display = '';
            }
            var noRev = results.filter(function(r) { return r && r.reason === 'no-revs'; });
            if (noRev.length > 0) {
                showCustomAlert('PLM Toolkit',
                    'No revisions found for: ' + noRev.map(function(r) { return r.part; }).join(', '));
            }
        })
        .catch(function(err) {
            if (btn) { btn.disabled = false; btn.textContent = 'Load Revs'; }
            showCustomAlert('PLM Toolkit', 'Failed to load revisions: ' + err.message);
        });
}

// Same-part mode: swap the two rev-dropdown selections + re-fire the compare
// if a result is already on screen so the user sees the swap take effect.
function swapRevCompareSelections() {
    var a = document.getElementById('revCompareDropA');
    var b = document.getElementById('revCompareDropB');
    if (!a || !b) return;
    var t = a.value; a.value = b.value; b.value = t;
    _autoRecompareIfShowing('doRevCompare');
}

// Different-parts mode: swap parts + their rev dropdowns + caches. Bug fix —
// repopulating the dropdown from the cache was clobbering the user's selected
// rev. Swap the dropdowns' innerHTML + value directly so the selection is
// preserved.
function swapBcDiffParts() {
    var ia = document.getElementById('bcDiffPartAInput');
    var ib = document.getElementById('bcDiffPartBInput');
    var ra = document.getElementById('bcDiffRevA');
    var rb = document.getElementById('bcDiffRevB');
    if (!ia || !ib) return;
    var t = ia.value; ia.value = ib.value; ib.value = t;
    var cacheA = bcDiffRevsCache.A; bcDiffRevsCache.A = bcDiffRevsCache.B; bcDiffRevsCache.B = cacheA;
    if (ra && rb) {
        var aHtml = ra.innerHTML, aVal = ra.value;
        var bHtml = rb.innerHTML, bVal = rb.value;
        ra.innerHTML = bHtml; ra.value = bVal;
        rb.innerHTML = aHtml; rb.value = aVal;
    }
    _autoRecompareIfShowing('doBcDiffCompare');
}

// If the compare result table is currently visible, fire the compare again so
// the swap is reflected immediately. If nothing is showing, leave it alone
// (user is still setting up).
function _autoRecompareIfShowing(fnName) {
    var statsEl = document.getElementById('revCompareStats');
    if (statsEl && statsEl.style.display !== 'none' && typeof window[fnName] === 'function') {
        window[fnName]();
    }
}

function populateOneRevDropdown(dropId, revs) {
    var drop = document.getElementById(dropId);
    if (!drop) return;
    var html = '';
    revs.forEach(function(r) {
        var label = r.revLabel;
        if (r.changeNumber && r.revLabel !== 'Introductory') label += ' \u2014 ' + r.changeNumber;
        var value = r.revLabel + '|' + (r.changeNumber || '');
        html += '<option value="' + esc(value) + '">' + esc(label) + '</option>';
    });
    drop.innerHTML = html;
    drop.selectedIndex = 0; // most-recent rev (per the new sort order)
}

function doBcDiffCompare() {
    var partA = document.getElementById('bcDiffPartAInput').value.trim();
    var partB = document.getElementById('bcDiffPartBInput').value.trim();
    var selA  = document.getElementById('bcDiffRevA').value;
    var selB  = document.getElementById('bcDiffRevB').value;
    if (!partA || !partB) { showCustomAlert('PLM Toolkit', 'Enter both part numbers.'); return; }
    if (!selA || !selB)   { showCustomAlert('PLM Toolkit', 'Load revisions for both parts and pick one on each side.'); return; }
    var pA = selA.split('|'), pB = selB.split('|');
    var revA = pA[0], changeA = pA[1] || '';
    var revB = pB[0], changeB = pB[1] || '';

    var btn = document.getElementById('bcDiffCompareBtn');
    btn.disabled = true; btn.textContent = 'Comparing...';
    document.getElementById('revCompareEmpty').style.display = 'none';
    document.getElementById('revCompareNoResults').style.display = 'none';
    document.getElementById('revCompareTableWrapper').style.display = 'none';
    document.getElementById('revCompareStats').style.display = 'none';
    document.getElementById('revCompareFilter').style.display = 'none';

    var url = '/api/rev-compare/compare?part=' + encodeURIComponent(partA)
        + '&revA=' + encodeURIComponent(revA) + '&changeA=' + encodeURIComponent(changeA)
        + '&revB=' + encodeURIComponent(revB) + '&changeB=' + encodeURIComponent(changeB)
        + '&partB=' + encodeURIComponent(partB);

    fetch(url).then(checkAuth).then(function(r) { return r.json(); })
        .then(function(data) {
            btn.disabled = false; btn.textContent = 'Compare';
            if (data.error) {
                showCustomAlert('PLM Toolkit', data.error);
                document.getElementById('revCompareEmpty').style.display = 'block';
                return;
            }
            revCompareData = data.diff || [];
            renderRevCompareStats(data);
            renderRcFieldPicker();
            renderRevCompareSideBySide();
            document.getElementById('revCompareFilter').style.display = 'block';
            document.getElementById('revCompareActions').style.display = 'block';
        })
        .catch(function(err) {
            btn.disabled = false; btn.textContent = 'Compare';
            showCustomAlert('PLM Toolkit', 'Compare failed: ' + err.message);
        });
}

// === Rev Compare ===

// Word-level diff: highlights only the words that differ between two strings.
// side='a' renders strA with changed words highlighted; side='b' renders strB.
function wordDiffHtml(strA, strB, side) {
    var wordsA = strA.split(/(\s+)/);
    var wordsB = strB.split(/(\s+)/);
    var len = Math.max(wordsA.length, wordsB.length);
    var parts = [];
    for (var i = 0; i < len; i++) {
        var wa = i < wordsA.length ? wordsA[i] : '';
        var wb = i < wordsB.length ? wordsB[i] : '';
        var word = side === 'a' ? wa : wb;
        if (wa === wb) {
            parts.push(esc(word));
        } else if (word) {
            parts.push('<span style="background:#fce4e4; color:#c0392b; font-weight:600; padding:1px 3px; border-radius:3px;">' + esc(word) + '</span>');
        }
    }
    return parts.join('');
}

var revCompareData = [];
// Labels harmonised with bcFields. 'Comp Rev' and 'Type' are the matched
// pairs; 'Comp Change' / 'Seq #' / 'Primary P/N' are Two-Revs-only.
var rcFields = [
    { key: 'qty', label: 'Qty', checked: true },
    { key: 'description', label: 'Description', checked: true },
    { key: 'componentRev', label: 'Comp Rev', checked: false },
    { key: 'componentChange', label: 'Comp Change', checked: false },
    { key: 'bomType', label: 'Type', checked: false },
    { key: 'seqNum', label: 'Seq #', checked: false },
    { key: 'primaryPn', label: 'Primary P/N', checked: false },
    { key: 'refDes', label: 'Ref Designator', checked: false },
    { key: 'notes', label: 'BOM Notes', checked: false }
];

function loadRevCompareRevs() {
    var part = document.getElementById('revComparePartInput').value.trim();
    if (!part) { showCustomAlert('PLM Toolkit', 'Enter a part number.'); return; }

    var btn = document.getElementById('revCompareLoadBtn');
    btn.disabled = true;
    btn.textContent = 'Loading...';
    document.getElementById('revCompareEmpty').style.display = 'none';
    document.getElementById('revCompareNoResults').style.display = 'none';
    document.getElementById('revCompareRevSelectors').style.display = 'none';
    document.getElementById('revCompareStats').style.display = 'none';
    document.getElementById('revCompareFilter').style.display = 'none';
    document.getElementById('revCompareTableWrapper').style.display = 'none';

    fetch('/api/rev-compare/revs?part=' + encodeURIComponent(part))
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.textContent = 'Load Revs';
            if (!data || data.length === 0) {
                document.getElementById('revCompareNoResults').style.display = 'block';
                return;
            }
            populateRevDropdowns(data);
            document.getElementById('revCompareRevSelectors').style.display = 'block';
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.textContent = 'Load Revs';
            showCustomAlert('PLM Toolkit', 'Failed to load revisions: ' + err.message);
        });
}

function populateRevDropdowns(revs) {
    var dropA = document.getElementById('revCompareDropA');
    var dropB = document.getElementById('revCompareDropB');
    var html = '';
    revs.forEach(function(r) {
        var label = r.revLabel;
        if (r.changeNumber && r.revLabel !== 'Introductory') {
            label += ' \u2014 ' + r.changeNumber;
        }
        var value = r.revLabel + '|' + (r.changeNumber || '');
        html += '<option value="' + esc(value) + '">' + esc(label) + '</option>';
    });
    dropA.innerHTML = html;
    dropB.innerHTML = html;
    // Default: first = most recent, second = next one (or same if only one)
    if (revs.length > 1) {
        dropA.selectedIndex = 0;
        dropB.selectedIndex = 1;
    }
}

function doRevCompare() {
    var part = document.getElementById('revComparePartInput').value.trim();
    var selA = document.getElementById('revCompareDropA').value;
    var selB = document.getElementById('revCompareDropB').value;
    if (!part || !selA || !selB) { showCustomAlert('PLM Toolkit', 'Select both revisions.'); return; }

    var partsA = selA.split('|');
    var partsB = selB.split('|');
    var revA = partsA[0], changeA = partsA[1] || '';
    var revB = partsB[0], changeB = partsB[1] || '';

    var btn = document.getElementById('revCompareBtn');
    btn.disabled = true;
    btn.textContent = 'Comparing...';
    document.getElementById('revCompareEmpty').style.display = 'none';
    document.getElementById('revCompareNoResults').style.display = 'none';
    document.getElementById('revCompareTableWrapper').style.display = 'none';
    document.getElementById('revCompareStats').style.display = 'none';
    document.getElementById('revCompareFilter').style.display = 'none';

    var url = '/api/rev-compare/compare?part=' + encodeURIComponent(part) +
        '&revA=' + encodeURIComponent(revA) + '&changeA=' + encodeURIComponent(changeA) +
        '&revB=' + encodeURIComponent(revB) + '&changeB=' + encodeURIComponent(changeB);

    fetch(url)
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.textContent = 'Compare';
            if (data.error) {
                showCustomAlert('PLM Toolkit', data.error);
                document.getElementById('revCompareEmpty').style.display = 'block';
                return;
            }
            revCompareData = data.diff || [];
            renderRevCompareStats(data);
            renderRcFieldPicker();
            renderRevCompareSideBySide();
            document.getElementById('revCompareFilter').style.display = 'block';
            document.getElementById('revCompareActions').style.display = 'block';
        })
        .catch(function(err) {
            btn.disabled = false;
            btn.textContent = 'Compare';
            showCustomAlert('PLM Toolkit', 'Compare failed: ' + err.message);
        });
}

function renderRevCompareStats(data) {
    window._rcData = data;
    var pill = document.querySelector('#panelRevCompare .data-source-pill');
    if (pill) pill.style.display = 'none';

    var hA = data.headerA || {};
    var hB = data.headerB || {};

    var html = '<div class="insight-strip" style="display:flex; gap:4px; flex-wrap:wrap;">';
    var revShortA = data.revLabelA.split(' \u2014 ')[0];
    var revShortB = data.revLabelB.split(' \u2014 ')[0];
    html += '<div class="insight-tile" style="min-width:100px;"><div class="insight-k">Rev ' + esc(revShortA) + '</div><div class="insight-v">' + data.countA + ' <span style="font-size:12px; font-weight:400; color:#888;">rows</span></div></div>';
    html += '<div class="insight-tile" style="min-width:100px;"><div class="insight-k">Rev ' + esc(revShortB) + '</div><div class="insight-v">' + data.countB + ' <span style="font-size:12px; font-weight:400; color:#888;">rows</span></div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k" style="color:#dc3545;">Only in A</div><div class="insight-v" style="color:#dc3545;">' + (data.onlyInA || 0) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k" style="color:#28a745;">Only in B</div><div class="insight-v" style="color:#28a745;">' + (data.onlyInB || 0) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k" style="color:#e67e22;">Different</div><div class="insight-v" style="color:#e67e22;">' + (data.different || 0) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k">Same</div><div class="insight-v">' + (data.same || 0) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:70px;"><div class="insight-k">Time</div><div class="insight-v insight-v-sm">' + data.queryTimeMs + 'ms</div></div>';
    html += '<div style="margin-left:auto; display:flex; align-items:center; gap:6px; flex-shrink:0;">';
    html += '<button class="btn-export" onclick="doRevCompareExport()" style="font-size:12px; white-space:nowrap;">&#8595; Export Excel</button>';
    html += '<button class="btn-export" onclick="doRevCompareEmail()" id="revCompareEmailBtn" style="font-size:12px; white-space:nowrap;">&#9993; Email to Me</button>';
    html += '</div>';
    html += '</div>';

    // Parent header info row — lifecycle + description diffs
    var lcA = hA.parent_lifecycle || '';
    var lcB = hB.parent_lifecycle || '';
    var lcSame = lcA === lcB;
    var lcColorA = lcSame ? '#555' : (lcA === 'DEAD' ? '#dc3545' : lcA === 'MKT' ? '#28a745' : '#e67e22');
    var lcColorB = lcSame ? '#555' : (lcB === 'DEAD' ? '#dc3545' : lcB === 'MKT' ? '#28a745' : '#e67e22');

    // PT-39 follow-up: dedup identical labels. Show "Part:" once (same-part
    // mode); "Lifecycle:" once when the two sides match; A/B split only when
    // they differ.
    html += '<div style="display:flex; gap:16px; margin-top:8px; font-size:12px; color:#555; flex-wrap:wrap; align-items:center;">';
    if (data.samePart !== false) {
        html += '<div><strong>Part:</strong> ' + esc(data.part) + '</div>';
    } else {
        html += '<div><strong>Part A:</strong> ' + esc(data.part) + '</div>';
        if (data.partB) html += '<div><strong>Part B:</strong> ' + esc(data.partB) + '</div>';
    }
    if (lcSame && lcA) {
        html += '<div><strong>Lifecycle:</strong> <span style="color:#555; font-weight:700;">' + esc(lcA) + '</span></div>';
    } else {
        if (lcA) {
            html += '<div><strong>A Lifecycle:</strong> <span style="color:' + lcColorA + '; font-weight:700; background:#fce4e4; padding:1px 6px; border-radius:3px;">' + esc(lcA) + '</span></div>';
        }
        if (lcB) {
            html += '<div><strong>B Lifecycle:</strong> <span style="color:' + lcColorB + '; font-weight:700; background:#fce4e4; padding:1px 6px; border-radius:3px;">' + esc(lcB) + '</span></div>';
        }
    }
    html += '</div>';

    var descA = hA.parent_description || '';
    var descB = hB.parent_description || '';
    if (descA || descB) {
        var descSame = descA === descB;
        html += '<div style="font-size:12px; color:#666; margin-top:4px;">';
        if (descSame) {
            html += '<strong>Description:</strong> ' + esc(descA);
        } else {
            html += '<strong>A Desc:</strong> ' + wordDiffHtml(descA, descB, 'a');
            html += ' &nbsp;|&nbsp; <strong>B Desc:</strong> ' + wordDiffHtml(descA, descB, 'b');
        }
        html += '</div>';
    }

    var el = document.getElementById('revCompareStats');
    el.innerHTML = html;
    el.style.display = 'block';
}

function getCheckedRcFields() {
    return rcFields.filter(function(f) { return f.checked; });
}

function renderRevCompareSideBySide() {
    var showAdded = document.getElementById('rcShowAdded').checked;
    var showRemoved = document.getElementById('rcShowRemoved').checked;
    var showChanged = document.getElementById('rcShowChanged').checked;
    var showMatch = document.getElementById('rcShowMatch').checked;

    var filtered = revCompareData.filter(function(r) {
        if (r.diffStatus === 'ADDED') return showAdded;
        if (r.diffStatus === 'REMOVED') return showRemoved;
        if (r.diffStatus === 'CHANGED') return showChanged;
        if (r.diffStatus === 'MATCH') return showMatch;
        return true;
    });

    var d = window._rcData || {};
    var hA = d.headerA || {};
    var hB = d.headerB || {};
    var fields = getCheckedRcFields();
    var fCount = 1 + fields.length; // Component + selected fields
    var divCol = '<td style="width:6px; min-width:6px; max-width:6px; background:#e67e22; padding:0; border:none;"></td>';

    // PT-39 / Vikas Singh follow-up: dark per-side banner is concise — just the
    // rev label, plus part number ONLY when comparing different parts. The
    // description and lifecycle already live in the summary strip above (and
    // are deduped there when both sides match), so repeating them per-side is
    // pure noise when the part is the same.
    var samePart = d.samePart !== false;
    var descSame = (hA.parent_description || '') === (hB.parent_description || '');
    var lcSameHdr = (hA.parent_lifecycle || '') === (hB.parent_lifecycle || '');
    function _buildRevHeader(part, revLabel, h) {
        var hdr = (samePart ? '' : esc(part) + ' &mdash; ') + 'Rev ' + esc(revLabel);
        // Only repeat description per-side when comparing different parts AND
        // the descriptions differ (otherwise the summary covers it).
        if (!samePart && !descSame && h.parent_description) {
            hdr += '<div style="font-size:11px; font-weight:400; opacity:0.85; margin-top:2px;">' + esc(h.parent_description) + '</div>';
        }
        // Same logic for lifecycle.
        if (!lcSameHdr && h.parent_lifecycle) {
            hdr += '<div style="font-size:11px; font-weight:400; opacity:0.75; margin-top:1px;">' + esc(h.parent_lifecycle) + '</div>';
        }
        return hdr;
    }
    var headerA = _buildRevHeader(d.part, d.revLabelA, hA);
    var headerB = _buildRevHeader(d.partB || d.part, d.revLabelB, hB);

    // PT-37 fix shipped (see RevCompareService.scoped_bom branch (3)) — pending
    // redline-removes are now correctly excluded via the BOM.PRIOR_BOM link.
    // No banner needed.
    var pendingBanner = '';

    // Single table with a divider column — guarantees row alignment
    var html = pendingBanner +
        '<table class="data-table" style="font-size:12px; width:100%; border-collapse:collapse;">';

    // Banner row
    html += '<thead><tr>';
    html += '<th colspan="' + fCount + '" style="text-align:center; background:#1a3a5c; color:white; font-size:13px; padding:10px 8px;">' + headerA + '</th>';
    html += '<th style="width:6px; background:#e67e22; padding:0; border:none;"></th>';
    html += '<th colspan="' + fCount + '" style="text-align:center; background:#2c5282; color:white; font-size:13px; padding:10px 8px;">' + headerB + '</th>';
    html += '</tr>';

    // Field headers
    html += '<tr>';
    html += '<th style="background:#e8f0fe; color:#1a3a5c;">Component</th>';
    fields.forEach(function(f) { html += '<th style="background:#e8f0fe; color:#1a3a5c; font-size:11px;">' + f.label + '</th>'; });
    html += '<th style="width:6px; background:#e67e22; padding:0; border:none;"></th>';
    html += '<th style="background:#dbeafe; color:#2c5282;">Component</th>';
    fields.forEach(function(f) { html += '<th style="background:#dbeafe; color:#2c5282; font-size:11px;">' + f.label + '</th>'; });
    html += '</tr></thead><tbody>';

    var rowNum = 0;
    filtered.forEach(function(r) {
        var isOnlyInA = r.diffStatus === 'REMOVED';
        var isOnlyInB = r.diffStatus === 'ADDED';
        var isChanged = r.diffStatus === 'CHANGED';
        var stripe = (rowNum % 2 === 1) ? '#f0f1f3' : '#ffffff';
        var rowBg = isChanged ? '#fff8e6' : stripe;
        rowNum++;
        html += '<tr style="background:' + rowBg + ';">';

        // === A side ===
        if (isOnlyInB) {
            html += '<td style="background:#f8f8f8; color:#ccc; font-style:italic;">&mdash;</td>';
            fields.forEach(function() { html += '<td style="background:#f8f8f8;"></td>'; });
        } else {
            html += '<td style="font-weight:600; white-space:nowrap;">' + esc(r.component) + '</td>';
            fields.forEach(function(f) {
                var valA = r[f.key + 'A'] || '';
                var valB = r[f.key + 'B'] || '';
                var style = '';
                if (isChanged && valA !== valB) style = 'background:#fce4e4; color:#c0392b; font-weight:600;';
                html += '<td style="' + style + '">' + esc(valA) + '</td>';
            });
        }

        // Divider
        html += divCol;

        // === B side ===
        if (isOnlyInA) {
            html += '<td style="background:#f8f8f8; color:#ccc; font-style:italic;">&mdash;</td>';
            fields.forEach(function() { html += '<td style="background:#f8f8f8;"></td>'; });
        } else {
            html += '<td style="font-weight:600; white-space:nowrap;">' + esc(r.component) + '</td>';
            fields.forEach(function(f) {
                var valA = r[f.key + 'A'] || '';
                var valB = r[f.key + 'B'] || '';
                var style = '';
                if (isChanged && valA !== valB) style = 'background:#fce4e4; color:#c0392b; font-weight:600;';
                html += '<td style="' + style + '">' + esc(valB) + '</td>';
            });
        }

        html += '</tr>';
    });

    html += '</tbody></table>';

    document.getElementById('revCompareBody').innerHTML = html;
    document.getElementById('revCompareTableWrapper').style.display = '';
}

function filterRevCompare() { renderRevCompareSideBySide(); }

function toggleRcField(key) {
    rcFields.forEach(function(f) { if (f.key === key) f.checked = !f.checked; });
    renderRcFieldPicker();
    if (revCompareData.length > 0) renderRevCompareSideBySide();
}

function rcToggleAllFields() {
    var allChecked = rcFields.every(function(f) { return f.checked; });
    rcFields.forEach(function(f) { f.checked = !allChecked; });
    renderRcFieldPicker();
    if (revCompareData.length > 0) renderRevCompareSideBySide();
}

function renderRcFieldPicker() {
    var el = document.getElementById('rcFieldPicker');
    if (!el) return;
    var allChecked = rcFields.every(function(f) { return f.checked; });
    var anyChecked = rcFields.some(function(f) { return f.checked; });
    var masterLabel = allChecked ? 'Clear all' : 'Select all';
    var masterHtml = '<label style="margin-right:14px; cursor:pointer; white-space:nowrap; padding-right:10px; border-right:1px solid #d0d5dd;">' +
        '<input type="checkbox" ' + (allChecked ? 'checked' : '') +
        ' onchange="rcToggleAllFields()" style="margin-right:3px;">' +
        '<span style="font-weight:600; color:#4a6fa5;">' + masterLabel + '</span></label>';
    var fieldsHtml = rcFields.map(function(f) {
        return '<label style="margin-right:10px; cursor:pointer; white-space:nowrap;">' +
            '<input type="checkbox" ' + (f.checked ? 'checked' : '') +
            ' onchange="toggleRcField(\'' + f.key + '\')" style="margin-right:3px;">' +
            '<span style="' + (f.checked ? 'font-weight:600; color:#333;' : 'color:#888;') + '">' + f.label + '</span></label>';
    }).join('');
    el.innerHTML = masterHtml + fieldsHtml;
    var masterCb = el.querySelector('input[onchange="rcToggleAllFields()"]');
    if (masterCb) masterCb.indeterminate = !allChecked && anyChecked;
}

function doRevCompareClear() {
    // Same-part inputs
    var sameInput = document.getElementById('revComparePartInput');
    if (sameInput) sameInput.value = '';
    var sameSelectors = document.getElementById('revCompareRevSelectors');
    if (sameSelectors) sameSelectors.style.display = 'none';

    // Different-parts inputs
    var diffA = document.getElementById('bcDiffPartAInput');
    var diffB = document.getElementById('bcDiffPartBInput');
    if (diffA) diffA.value = '';
    if (diffB) diffB.value = '';
    var diffRow = document.getElementById('bcDiffRevRow');
    if (diffRow) diffRow.style.display = 'none';
    var dropA = document.getElementById('bcDiffRevA');
    var dropB = document.getElementById('bcDiffRevB');
    if (dropA) dropA.innerHTML = '';
    if (dropB) dropB.innerHTML = '';
    bcDiffRevsCache = { A: [], B: [] };

    // Shared results area
    revCompareData = [];
    document.getElementById('revCompareTableWrapper').style.display = 'none';
    document.getElementById('revCompareStats').style.display = 'none';
    document.getElementById('revCompareFilter').style.display = 'none';
    document.getElementById('revCompareActions').style.display = 'none';
    document.getElementById('revCompareNoResults').style.display = 'none';
    document.getElementById('revCompareEmpty').style.display = 'block';
    var pill = document.querySelector('#panelRevCompare .data-source-pill');
    if (pill) pill.style.display = '';
}

function doRevCompareExport() {
    guardExport(function() {
    var part = document.getElementById('revComparePartInput').value.trim();
    var selA = document.getElementById('revCompareDropA').value;
    var selB = document.getElementById('revCompareDropB').value;
    if (!part || !selA || !selB) return;
    var partsA = selA.split('|');
    var partsB = selB.split('|');
    window.location.href = '/api/rev-compare/export?part=' + encodeURIComponent(part) +
        '&revA=' + encodeURIComponent(partsA[0]) + '&changeA=' + encodeURIComponent(partsA[1] || '') +
        '&revB=' + encodeURIComponent(partsB[0]) + '&changeB=' + encodeURIComponent(partsB[1] || '');
    });
}

function doRevCompareEmail() {
    var part = document.getElementById('revComparePartInput').value.trim();
    var selA = document.getElementById('revCompareDropA').value;
    var selB = document.getElementById('revCompareDropB').value;
    if (!part || !selA || !selB) return;
    var partsA = selA.split('|');
    var partsB = selB.split('|');
    var btn = document.getElementById('revCompareEmailBtn');
    var origText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Sending...';

    var url = '/api/rev-compare/email?part=' + encodeURIComponent(part) +
        '&revA=' + encodeURIComponent(partsA[0]) + '&changeA=' + encodeURIComponent(partsA[1] || '') +
        '&revB=' + encodeURIComponent(partsB[0]) + '&changeB=' + encodeURIComponent(partsB[1] || '');

    fetch(url, { method: 'POST' })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            if (data.success) {
                btn.innerHTML = '&#10003; Sent!';
                setTimeout(function() { btn.innerHTML = origText; }, 2000);
            } else {
                btn.innerHTML = origText;
                showCustomAlert('PLM Toolkit', data.message || 'Failed.');
            }
        })
        .catch(function() { btn.disabled = false; btn.innerHTML = origText; showCustomAlert('PLM Toolkit', 'Email failed.'); });
}

// === Change Reviews ===
var crTypeaheadTimer = null;
var crAllRows = [];           // full unfiltered dataset
var crActiveFilter = null;    // {kind:'type'|'state', value:'ECN'|'ready'|'rej'|'stale'}
var crGroupBy = 'none';       // 'none' | 'workflow' | 'changeType' | 'status'
var crSelectedAnalysts = [];  // [{loginId, displayName}, ...]

var crSelectedCountries = []; // selected country names
var crCountriesLoaded = false;

function crLoadCountries() {
    if (crCountriesLoaded) return;
    fetch('/api/change-reviews/countries')
        .then(checkAuth).then(function(r) { return r.json(); })
        .then(function(data) {
            crCountriesLoaded = true;
            crRenderCountries(data);
        }).catch(function() {
            document.getElementById('crCountryFilter').innerHTML = '';
        });
}

function crRenderCountries(countries) {
    var el = document.getElementById('crCountryFilter');
    if (!el || !countries || countries.length === 0) { if (el) el.innerHTML = ''; return; }
    el.innerHTML = countries.map(function(c) {
        var isActive = crSelectedCountries.indexOf(c.country) >= 0;
        return '<span class="cr-chip' + (isActive ? ' active' : '') + '" ' +
               'onclick="crToggleCountry(\'' + esc(c.country).replace(/'/g, "\\'") + '\')">' +
               esc(c.country) + ' (' + c.count + ')</span>';
    }).join('');
}

function crToggleCountry(country) {
    var idx = crSelectedCountries.indexOf(country);
    if (idx >= 0) crSelectedCountries.splice(idx, 1);
    else crSelectedCountries.push(country);
    // Re-render country chips
    fetch('/api/change-reviews/countries')
        .then(checkAuth).then(function(r) { return r.json(); })
        .then(function(data) { crRenderCountries(data); }).catch(function() {});
    // If countries selected, load all analysts from those countries
    if (crSelectedCountries.length > 0) {
        fetch('/api/change-reviews/users?country=' + encodeURIComponent(crSelectedCountries.join(',')))
            .then(checkAuth).then(function(r) { return r.json(); })
            .then(function(users) {
                crSelectedAnalysts = users.map(function(u) { return { loginId: u.loginId, displayName: u.displayName }; });
                crRenderSelectedChips();
                doChangeReviewSearch();
            }).catch(function() {});
    }
}

// --- Multi-select typeahead ---
function crUserTypeahead() {
    clearTimeout(crTypeaheadTimer);
    var q = document.getElementById('crUserInput').value.trim();
    if (q.length < 2) { document.getElementById('crUserDropdown').style.display = 'none'; return; }
    crTypeaheadTimer = setTimeout(function() {
        fetch('/api/change-reviews/users?q=' + encodeURIComponent(q))
            .then(checkAuth).then(function(r) { return r.json(); })
            .then(function(users) { crRenderDropdown(users); }).catch(function() {});
    }, 250);
}

function crRenderDropdown(users) {
    var dd = document.getElementById('crUserDropdown');
    if (!users || users.length === 0) { dd.style.display = 'none'; return; }
    var selectedIds = crSelectedAnalysts.map(function(a) { return a.loginId; });
    var filtered = users.filter(function(u) { return selectedIds.indexOf(u.loginId) === -1; });
    if (filtered.length === 0) { dd.style.display = 'none'; return; }
    dd.innerHTML = filtered.map(function(u) {
        var countryTag = u.country && u.country !== 'Unknown' ? ' <span style="color:#2980b9; font-size:10px;">' + esc(u.country) + '</span>' : '';
        return '<div class="cr-user-option" onclick="crAddAnalyst(\'' + esc(u.loginId).replace(/'/g, "\\'") + '\',\'' + esc(u.displayName).replace(/'/g, "\\'") + '\')" ' +
               'style="padding:8px 12px; cursor:pointer; font-size:13px; border-bottom:1px solid #f0f0f0;">' +
               '<strong>' + esc(u.displayName) + '</strong> <span style="color:#888; font-size:11px;">(' + esc(u.loginId) + ')</span>' + countryTag + '</div>';
    }).join('');
    dd.style.display = 'block';
}

function crAddAnalyst(loginId, displayName) {
    for (var i = 0; i < crSelectedAnalysts.length; i++) {
        if (crSelectedAnalysts[i].loginId === loginId) return;
    }
    crSelectedAnalysts.push({ loginId: loginId, displayName: displayName });
    document.getElementById('crUserInput').value = '';
    document.getElementById('crUserDropdown').style.display = 'none';
    crRenderSelectedChips();
    doChangeReviewSearch();
}

function crRemoveAnalyst(loginId) {
    crSelectedAnalysts = crSelectedAnalysts.filter(function(a) { return a.loginId !== loginId; });
    crRenderSelectedChips();
    if (crSelectedAnalysts.length > 0) doChangeReviewSearch();
    else doChangeReviewClear();
}

function crRenderSelectedChips() {
    var el = document.getElementById('crSelectedChips');
    if (!el) return;
    el.innerHTML = crSelectedAnalysts.map(function(a) {
        return '<span class="cr-sel-chip">' + esc(a.displayName) +
               ' <span class="cr-sel-x" onclick="event.stopPropagation();crRemoveAnalyst(\'' + esc(a.loginId).replace(/'/g, "\\'") + '\')">&times;</span></span>';
    }).join('');
}

function crInputKeydown(event) {
    if (event.key === 'Enter') {
        event.preventDefault();
        var first = document.querySelector('#crUserDropdown .cr-user-option');
        if (first) first.click();
        else if (crSelectedAnalysts.length > 0) doChangeReviewSearch();
    } else if (event.key === 'Backspace' && document.getElementById('crUserInput').value === '' && crSelectedAnalysts.length > 0) {
        crRemoveAnalyst(crSelectedAnalysts[crSelectedAnalysts.length - 1].loginId);
    }
}

function crLoadMe() {
    crSelectedAnalysts = [{ loginId: window.currentUsername || '', displayName: window.currentDisplayName || '' }];
    document.getElementById('crUserInput').value = '';
    crRenderSelectedChips();
    doChangeReviewSearch();
}

// Keep backward compat for recent chips
function crPickUser(loginId, displayName) {
    crSelectedAnalysts = [{ loginId: loginId, displayName: displayName }];
    document.getElementById('crUserInput').value = '';
    crRenderSelectedChips();
    doChangeReviewSearch();
}

// --- P2.3 Recent searches ---
function crPushRecent(loginId, displayName) {
    try {
        var h = JSON.parse(localStorage.getItem('crRecentAnalysts') || '[]');
        h = h.filter(function(x) { return x.loginId !== loginId; });
        h.unshift({ loginId: loginId, displayName: displayName });
        localStorage.setItem('crRecentAnalysts', JSON.stringify(h.slice(0, 5)));
    } catch(e) {}
}

function crRenderRecent() {
    try {
        var h = JSON.parse(localStorage.getItem('crRecentAnalysts') || '[]');
        var el = document.getElementById('crRecentChips');
        if (!el || h.length === 0) return;
        el.innerHTML = '<span style="font-size:11px; color:#888; margin-right:4px;">Recent:</span>' +
            h.map(function(u) {
                return '<span class="cr-recent-chip" onclick="crPickUser(\'' + esc(u.loginId).replace(/'/g, "\\'") + '\',\'' + esc(u.displayName).replace(/'/g, "\\'") + '\')">' + esc(u.displayName) + '</span>';
            }).join('');
    } catch(e) {}
}

function crShowRecent() {
    var q = document.getElementById('crUserInput').value.trim();
    if (q.length === 0) crRenderRecent();
}

// --- Main search ---
function doChangeReviewSearch() {
    if (crSelectedAnalysts.length === 0) {
        showCustomAlert('PLM Toolkit', 'Select at least one analyst from the dropdown.');
        return;
    }
    var loginIds = crSelectedAnalysts.map(function(a) { return a.loginId; }).join(',');
    var label = crSelectedAnalysts.map(function(a) { return a.displayName; }).join(', ');

    var btn = document.getElementById('crSearchBtn');
    btn.disabled = true; btn.textContent = 'Searching...';
    document.getElementById('crEmpty').style.display = 'none';
    document.getElementById('crNoResults').style.display = 'none';
    document.getElementById('crTableWrapper').style.display = 'none';
    document.getElementById('crStats').style.display = 'none';
    document.getElementById('crFilterChips').style.display = 'none';
    document.getElementById('crUserDropdown').style.display = 'none';
    crActiveFilter = null;

    fetch('/api/change-reviews/list?loginid=' + encodeURIComponent(loginIds))
        .then(checkAuth).then(function(r) { return r.json(); })
        .then(function(data) {
            btn.disabled = false; btn.textContent = 'Search';
            if (data.error) { showCustomAlert('PLM Toolkit', data.error); document.getElementById('crEmpty').style.display = 'block'; return; }
            crAllRows = data.rows || [];
            if (crAllRows.length === 0) { document.getElementById('crNoResults').style.display = 'block'; return; }
            crSelectedAnalysts.forEach(function(a) { crPushRecent(a.loginId, a.displayName); });
            crRenderRecent();
            crRenderStats(data, label);
            crRenderFilterChips();
            crRenderTable(crApplyFilter());
        })
        .catch(function(err) { btn.disabled = false; btn.textContent = 'Search'; showCustomAlert('PLM Toolkit', 'Search failed: ' + err.message); });
}

// --- P0.1 + P0.2: Filters ---
function crToggleFilter(kind, value) {
    if (crActiveFilter && crActiveFilter.kind === kind && crActiveFilter.value === value) {
        crActiveFilter = null; // toggle off
    } else {
        crActiveFilter = { kind: kind, value: value };
    }
    crRenderStats(window._crLastData, window._crLastLabel);
    crRenderFilterChips();
    crRenderTable(crApplyFilter());
}

function crApplyFilter() {
    if (!crActiveFilter) return crAllRows;
    var f = crActiveFilter;
    return crAllRows.filter(function(r) {
        if (f.kind === 'type') return (r.changeType || 'Other') === f.value;
        if (f.kind === 'state') {
            var hasRej = (r.rejected || 0) > 0;
            var allOk = r.total > 0 && r.awaiting === 0 && !hasRej;
            var stale = (r.maxAwaitingDays || 0) > 10;
            if (f.value === 'ready') return allOk;
            if (f.value === 'rej') return hasRej;
            if (f.value === 'stale') return stale && !hasRej;
        }
        return true;
    });
}

function crIsReady(r) { return (r.requiredTotal || 0) > 0 && (r.requiredDone || 0) >= (r.requiredTotal || 0) && (r.rejected || 0) === 0; }
function crHasRej(r) { return (r.rejected || 0) > 0; }
function crIsStale(r) { return (r.maxAwaitingDays || 0) > 10 && !crHasRej(r); }

function crRenderFilterChips() {
    var ready = crAllRows.filter(crIsReady).length;
    var rej = crAllRows.filter(crHasRej).length;
    var stale = crAllRows.filter(crIsStale).length;
    var af = crActiveFilter;

    var html = '<div style="display:flex; gap:4px; flex-wrap:wrap; margin:6px auto; max-width:1400px;">';
    html += '<span class="cr-chip' + (!af ? ' active' : '') + '" onclick="crToggleFilter(null,null)">All (' + crAllRows.length + ')</span>';
    html += '<span class="cr-chip ready' + (af && af.value === 'ready' ? ' active' : '') + '" onclick="crToggleFilter(\'state\',\'ready\')">&#10003; Ready (' + ready + ')</span>';
    if (rej) html += '<span class="cr-chip rej' + (af && af.value === 'rej' ? ' active' : '') + '" onclick="crToggleFilter(\'state\',\'rej\')">&#10007; Rejected (' + rej + ')</span>';
    if (stale) html += '<span class="cr-chip stale' + (af && af.value === 'stale' ? ' active' : '') + '" onclick="crToggleFilter(\'state\',\'stale\')">&#9888; Stale &gt;10d (' + stale + ')</span>';
    html += '<span style="margin-left:auto; font-size:12px; color:#888;">Group: <select id="crGroupBySelect" onchange="crGroupBy=this.value;crRenderTable(crApplyFilter())" style="font-size:12px; border:1px solid #d0d5dd; border-radius:4px; padding:2px 6px;">';
    html += '<option value="none"' + (crGroupBy === 'none' ? ' selected' : '') + '>None</option>';
    html += '<option value="workflow"' + (crGroupBy === 'workflow' ? ' selected' : '') + '>Workflow</option>';
    html += '<option value="changeType"' + (crGroupBy === 'changeType' ? ' selected' : '') + '>Change Type</option>';
    html += '<option value="status"' + (crGroupBy === 'status' ? ' selected' : '') + '>Status</option>';
    html += '</select></span>';
    html += '</div>';

    var el = document.getElementById('crFilterChips');
    el.innerHTML = html;
    el.style.display = 'block';
}

// --- Stats (P0.1 clickable tiles) ---
function crRenderStats(data, analystLabel) {
    window._crLastData = data; window._crLastLabel = analystLabel;
    var rows = crAllRows;
    var byType = {};
    rows.forEach(function(r) { var t = r.changeType || 'Other'; byType[t] = (byType[t] || 0) + 1; });
    var af = crActiveFilter;

    var html = '<div class="insight-strip" style="display:flex; gap:4px; flex-wrap:wrap;">';
    var shortLabel = analystLabel.length > 30 ? analystLabel.substring(0, 28) + '...' : analystLabel;
    html += '<div class="insight-tile" style="min-width:120px;" title="' + esc(analystLabel) + '"><div class="insight-k">Analyst' + (crSelectedAnalysts.length > 1 ? 's (' + crSelectedAnalysts.length + ')' : '') + '</div><div class="insight-v insight-v-sm">' + esc(shortLabel) + '</div></div>';
    html += '<div class="insight-tile" style="min-width:80px;"><div class="insight-k">In Review</div><div class="insight-v" style="color:#e67e22;">' + rows.length + '</div></div>';
    Object.keys(byType).forEach(function(type) {
        var isActive = af && af.kind === 'type' && af.value === type;
        html += '<div class="insight-tile" style="min-width:70px; cursor:pointer;' + (isActive ? ' outline:2px solid #1a3a5c; outline-offset:-2px;' : '') + '" onclick="crToggleFilter(\'type\',\'' + esc(type).replace(/'/g, "\\'") + '\')"><div class="insight-k">' + esc(type) + '</div><div class="insight-v">' + byType[type] + '</div></div>';
    });
    html += '</div>';

    // Legend
    html += '<div style="display:flex; gap:12px; flex-wrap:wrap; font-size:11px; color:#666; margin-top:6px; align-items:center;">';
    html += '<span style="font-weight:600; color:#888;">Legend:</span>';
    html += '<span style="background:#e8f5e9; padding:1px 6px; border-radius:3px;">Ready</span>';
    html += '<span style="background:#fdeaea; padding:1px 6px; border-radius:3px;">Rejected</span>';
    html += '<span style="background:#fff3e0; padding:1px 6px; border-radius:3px;">Stale &gt;10d</span>';
    html += '<span style="margin-left:6px;">Bar: <span style="color:#28a745;">&#9632;</span>Approved <span style="color:#2980b9;">&#9632;</span>Ack <span style="color:#dc3545;">&#9632;</span>Rejected <span style="color:#c9c4b8;">&#9632;</span>Awaiting <span style="color:#ff6b4a;">&#9632;</span>Stale</span>';
    html += '</div>';

    var el = document.getElementById('crStats');
    el.innerHTML = html;
    el.style.display = 'block';
    var pill = document.querySelector('#panelChangeReviews .data-source-pill');
    if (pill) pill.style.display = 'none';
}

// --- Sort helper ---
function crSortRows(rows) {
    rows.sort(function(a, b) {
        var aReady = crIsReady(a) ? 1 : 0, bReady = crIsReady(b) ? 1 : 0;
        if (aReady !== bReady) return bReady - aReady;
        var aRej = crHasRej(a) ? 1 : 0, bRej = crHasRej(b) ? 1 : 0;
        if (aRej !== bRej) return bRej - aRej;
        var aStale = crIsStale(a) ? 1 : 0, bStale = crIsStale(b) ? 1 : 0;
        if (aStale !== bStale) return bStale - aStale;
        return 0;
    });
    return rows;
}

// --- P0.4 Stacked progress bar ---
function crBuildProgressBar(r) {
    var revs = (r.reviewers || []).filter(function(v) { return v.reqd; });
    if (revs.length === 0) return '';
    var total = revs.length;
    var b = { ok: 0, ack: 0, rj: 0, aw: 0, st: 0 };
    revs.forEach(function(v) {
        if (v.action === 'Approved') b.ok++;
        else if (v.action === 'Acknowledged') b.ack++;
        else if (v.action === 'Rejected') b.rj++;
        else if (v.days != null && v.days > 10) b.st++;
        else b.aw++;
    });
    var seg = function(cls, n) { return n ? '<div class="cr-seg ' + cls + '" style="width:' + (n / total * 100).toFixed(1) + '%"></div>' : ''; };
    var tip = revs.map(function(v) {
        var t = v.name + ' \u2014 ' + v.action;
        if (v.action === 'Awaiting' && v.days != null) t += ' (' + v.days + 'd)';
        return t;
    }).join('&#10;');
    return '<div class="cr-bar" title="' + tip + '">' + seg('ok', b.ok) + seg('ack', b.ack) + seg('rj', b.rj) + seg('aw', b.aw) + seg('st', b.st) + '</div>';
}

// --- P2.1 Inline rejection note ---
function crRejectNote(r) {
    if ((r.rejected || 0) === 0) return '';
    var revs = r.reviewers || [];
    for (var i = 0; i < revs.length; i++) {
        if (revs[i].action === 'Rejected') {
            var note = '<span class="cr-reject-note">&#10007; Rejected by ' + esc(revs[i].name);
            if (revs[i].comment) note += ' \u2014 "' + esc(revs[i].comment.length > 80 ? revs[i].comment.substring(0, 80) + '...' : revs[i].comment) + '"';
            note += '</span>';
            return note;
        }
    }
    return '';
}

// --- P2.2 Activity ping color ---
function crActivityColor(r) {
    if (!r.lastActivityMs) return '#e8e6e0';
    var hrs = (Date.now() - r.lastActivityMs) / 3600000;
    if (hrs < 24) return '#28a745';
    if (hrs < 72) return '#e67e22';
    if (hrs < 240) return '#ccc';
    return '#e8e6e0';
}

// --- Table rendering ---
function crRenderTable(rows) {
    rows = crSortRows(rows.slice());
    var tbody = document.getElementById('crTableBody');
    tbody.innerHTML = '';

    // P1.3 Grouping
    if (crGroupBy !== 'none') {
        var groups = {};
        rows.forEach(function(r) {
            var key = r[crGroupBy] || 'Other';
            if (!groups[key]) groups[key] = [];
            groups[key].push(r);
        });
        Object.keys(groups).forEach(function(key) {
            // Group header
            var gh = document.createElement('tr');
            gh.innerHTML = '<td colspan="8" style="background:#e9ecef; font-weight:700; font-size:13px; padding:8px 12px; cursor:pointer;" onclick="this.classList.toggle(\'cr-collapsed\');var s=this.nextElementSibling;while(s&&!s.classList.contains(\'cr-group-hdr\')){s.style.display=s.style.display===\'none\'?\'\':\'none\';s=s.nextElementSibling;}">' +
                '&#9662; ' + esc(key) + ' (' + groups[key].length + ')</td>';
            gh.classList.add('cr-group-hdr');
            tbody.appendChild(gh);
            groups[key].forEach(function(r, idx) { crAppendRow(tbody, r, idx); });
        });
    } else {
        rows.forEach(function(r, idx) { crAppendRow(tbody, r, idx); });
    }
    document.getElementById('crTableWrapper').style.display = '';
}

function crAppendRow(tbody, r, idx) {
    var tr = document.createElement('tr');
    tr.style.cursor = 'pointer';
    tr.onclick = function() { crShowDetail(r.changeNumber); };

    var hasRejection = crHasRej(r);
    var allApproved = crIsReady(r);
    var staleAwaiting = crIsStale(r);
    var stripe = idx % 2 === 1 ? '#f8f9fa' : '#fff';
    if (hasRejection) stripe = '#fdeaea';
    else if (allApproved) stripe = '#e8f5e9';
    else if (staleAwaiting) stripe = '#fff3e0';
    tr.style.background = stripe;

    // P2.2 Activity ping
    var pingColor = crActivityColor(r);
    tr.style.boxShadow = 'inset 3px 0 0 ' + pingColor;

    // P0.4 Progress bar + percentage
    var bar = crBuildProgressBar(r);
    var reqTotal = r.requiredTotal || 0, reqDone = r.requiredDone || 0;
    var pct = reqTotal > 0 ? Math.round((reqDone / reqTotal) * 100) : 0;
    var pctColor = allApproved ? '#28a745' : hasRejection ? '#dc3545' : staleAwaiting ? '#dc3545' : '#666';
    var signoffCell = bar + ' <span style="font-size:11px; color:' + pctColor + '; font-weight:600;">' + pct + '%</span>';

    // P0.3 Age column
    var ageDays = r.maxAwaitingDays;
    var ageStr = ageDays != null ? ageDays + 'd' : '\u2014';
    var ageStyle = "text-align:right; font-family:'IBM Plex Mono',monospace; font-size:12px;";
    if (ageDays != null && ageDays > 10) ageStyle += ' color:#dc3545; font-weight:600;';
    else if (ageDays != null && ageDays > 5) ageStyle += ' color:#e67e22; font-weight:600;';

    // P2.1 Rejection note + description truncation
    var descShort = r.description.length > 100 ? r.description.substring(0, 97) + '...' : r.description;
    var descHtml = esc(descShort);
    var rejNote = crRejectNote(r);
    if (rejNote) descHtml += '<div>' + rejNote + '</div>';

    tr.innerHTML =
        '<td class="cr-sticky-col" style="font-weight:600; white-space:nowrap; position:sticky; left:0; background:' + stripe + '; z-index:1;"><a href="' + crAgileUrl(r.changeType, r.changeNumber) + '" target="_blank" onclick="event.stopPropagation()" style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;" title="Open in Agile PLM">' + esc(r.changeNumber) + '</a></td>' +
        '<td style="max-width:400px; cursor:default;" title="' + esc(r.description) + '">' + descHtml + '</td>' +
        '<td>' + crStatusBadge(r.status) + '</td>' +
        '<td style="font-size:11px; color:#666;">' + esc(r.workflow) + '</td>' +
        '<td><span style="background:#e8f0fe; color:#1a3a5c; padding:2px 8px; border-radius:4px; font-size:11px; font-weight:600;">' + esc(r.changeType) + '</span></td>' +
        '<td>' + crPriorityDot(r.priority) + '</td>' +
        '<td style="' + ageStyle + '">' + ageStr + '</td>' +
        '<td style="white-space:nowrap;">' + signoffCell + '</td>';
    tbody.appendChild(tr);
}

function crStatusBadge(status) {
    var colors = { 'Review': '#e67e22', 'CCB': '#8e44ad', 'Execution': '#2980b9', 'Released': '#28a745' };
    var bg = colors[status] || '#6c757d';
    return '<span style="background:' + bg + '; color:#fff; padding:2px 8px; border-radius:4px; font-size:11px; font-weight:600;">' + esc(status) + '</span>';
}

function crPriorityDot(priority) {
    if (!priority) return '';
    var color = priority === 'Urgent' ? '#dc3545' : '#28a745';
    return '<span style="color:' + color + '; margin-right:4px;">&#9679;</span>' + esc(priority);
}

// --- Detail modal ---
function crShowDetail(changeNumber) {
    document.getElementById('crDetailTitle').innerHTML = 'Signoff Detail \u2014 <a href="' + (window.AGILE_WEBCLIENT_URL || 'https://plm.sandisk.com/Agile') + '/object/' + encodeURIComponent(changeNumber.split('-')[0]) + '/' + encodeURIComponent(changeNumber) + '" target="_blank" style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;" title="Open in Agile PLM">' + esc(changeNumber) + ' \u2197</a>';
    document.getElementById('crDetailBody').innerHTML = '<div style="text-align:center; padding:24px; color:#888;">Loading...</div>';
    document.getElementById('crDetailModal').style.display = 'flex';
    fetch('/api/change-reviews/detail?change=' + encodeURIComponent(changeNumber))
        .then(checkAuth).then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.error) { document.getElementById('crDetailBody').innerHTML = '<p style="color:#dc3545;">' + esc(data.error) + '</p>'; return; }
            crRenderDetail(data);
        })
        .catch(function(err) { document.getElementById('crDetailBody').innerHTML = '<p style="color:#dc3545;">Failed: ' + esc(err.message) + '</p>'; });
}

function crRenderDetail(data) {
    var rows = data.rows || [];
    if (rows.length === 0) { document.getElementById('crDetailBody').innerHTML = '<p style="color:#888; text-align:center;">No signoff rows found.</p>'; return; }
    var approved = 0, awaiting = 0, observers = 0, rejected = 0;
    rows.forEach(function(r) {
        if (r.action === 'Approved') approved++;
        else if (r.action === 'Awaiting Approval') awaiting++;
        else if (r.action === 'Acknowledged' || r.action === 'Observer') observers++;
        else if (r.action === 'Rejected') rejected++;
    });
    var approverCount = approved + awaiting + rejected;
    var html = '<div style="display:flex; gap:12px; margin-bottom:12px; font-size:13px;">';
    html += '<span style="color:#28a745; font-weight:600;">&#10003; ' + approved + ' Approved</span>';
    if (rejected) html += '<span style="color:#dc3545; font-weight:600;">&#10007; ' + rejected + ' Rejected</span>';
    if (awaiting) html += '<span style="color:#e67e22; font-weight:600;">&#9203; ' + awaiting + ' Awaiting</span>';
    html += '<span style="color:#888;">' + approverCount + ' approvers</span>';
    if (observers) html += '<span style="color:#aaa;">+ ' + observers + ' observers</span>';
    html += '</div>';
    html += '<table class="data-table" style="font-size:12px; width:100%;"><thead><tr>';
    html += '<th>Action</th><th>Req\'d</th><th>Reviewer</th><th>Signoff User</th><th>Time</th><th>Comments</th><th>Duration</th>';
    html += '</tr></thead><tbody>';
    rows.forEach(function(r, idx) {
        var isObserver = r.action === 'Acknowledged' || r.action === 'Observer';
        var actionLabel = isObserver ? 'Observer' : r.action;
        var actionColor = r.action === 'Approved' ? '#28a745' : r.action === 'Rejected' ? '#dc3545' : r.action === 'Awaiting Approval' ? '#e67e22' : isObserver ? '#aaa' : '#666';
        var stripe = idx % 2 === 1 ? '#f8f9fa' : '#fff';
        var timeStr = r.localClientTime ? formatDateFull(r.localClientTime) : '';
        var durationStr = r.signoffDurationDays != null ? r.signoffDurationDays + 'd' : '';
        var ds = '';
        if (r.signoffDurationDays != null && r.signoffDurationDays > 5) ds = 'color:#dc3545; font-weight:600;';
        else if (r.signoffDurationDays != null && r.signoffDurationDays > 2) ds = 'color:#e67e22; font-weight:600;';
        html += '<tr style="background:' + stripe + ';">';
        html += '<td style="color:' + actionColor + '; font-weight:' + (isObserver ? '400' : '600') + '; white-space:nowrap;' + (isObserver ? ' font-style:italic;' : '') + '">' + esc(actionLabel) + '</td>';
        html += '<td>' + (r.reqd === 'No' ? '<span style="color:#888;">No</span>' : 'Yes') + '</td>';
        html += '<td style="white-space:nowrap;">' + renderUserLink(r.reviewer) + '</td>';
        html += '<td style="white-space:nowrap;">' + (isObserver ? '' : renderUserLink(r.signoffUser || '')) + '</td>';
        html += '<td style="font-size:11px; white-space:nowrap;">' + timeStr + '</td>';
        html += '<td style="max-width:250px; overflow:hidden; text-overflow:ellipsis;" title="' + esc(r.signoffComments || '') + '">' + esc(r.signoffComments || '') + '</td>';
        html += '<td style="text-align:center; ' + ds + '">' + durationStr + '</td></tr>';
    });
    html += '</tbody></table>';
    document.getElementById('crDetailBody').innerHTML = html;
}

function closeCrDetail() { document.getElementById('crDetailModal').style.display = 'none'; }

function doChangeReviewClear() {
    document.getElementById('crUserInput').value = '';
    crSelectedAnalysts = [];
    crRenderSelectedChips();
    crAllRows = []; crActiveFilter = null;
    document.getElementById('crTableWrapper').style.display = 'none';
    document.getElementById('crStats').style.display = 'none';
    document.getElementById('crFilterChips').style.display = 'none';
    document.getElementById('crNoResults').style.display = 'none';
    document.getElementById('crEmpty').style.display = 'block';
    var pill = document.querySelector('#panelChangeReviews .data-source-pill');
    if (pill) pill.style.display = '';
}

// === Dashboard mode ===
var crDashMode = 'queue';
var crDashData = null;
var crDashDaysUsed = 30;
var crDashFilterAnalyst = [];
var crDashSelectedCountries = [];
var crDashChartFilter = null; // {field:'changeType'|'ageBucket'|'status', value:'ECN'|'11-30d'|...}
var CRD_PREFS_KEY = 'plm.crDash.prefs.v1';

function crDashApplyChartFilter(field, value) {
    if (crDashChartFilter && crDashChartFilter.field === field && crDashChartFilter.value === value) {
        crDashChartFilter = null; // toggle off
    } else {
        crDashChartFilter = { field: field, value: value };
    }
    crRenderDashboard();
    // Scroll to the table
    var tbl = document.getElementById('crDashTable');
    if (tbl) tbl.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function crMatchesAgeBucket(r, bucket) {
    var age = r.maxAwaitingDays || 0;
    if (bucket === '0-5d') return age <= 5;
    if (bucket === '6-10d') return age >= 6 && age <= 10;
    if (bucket === '11-30d') return age >= 11 && age <= 30;
    if (bucket === '31-60d') return age >= 31 && age <= 60;
    if (bucket === '60d+') return age > 60;
    return false;
}

function crDashSavePrefs() {
    try { localStorage.setItem(CRD_PREFS_KEY, JSON.stringify({ analyst: crDashFilterAnalyst, countries: crDashSelectedCountries, days: crDashDaysUsed })); } catch(e) {}
}
function crDashLoadPrefs() {
    try {
        var p = JSON.parse(localStorage.getItem(CRD_PREFS_KEY) || '{}');
        if (p.analyst) crDashFilterAnalyst = p.analyst;
        if (p.countries) crDashSelectedCountries = p.countries;
        if (p.days) crDashDaysUsed = p.days;
    } catch(e) {}
}

function crSwitchMode(mode) {
    crDashMode = mode;
    document.getElementById('crModeQueue').style.display = mode === 'queue' ? '' : 'none';
    document.getElementById('crModeDashboard').style.display = mode === 'dashboard' ? '' : 'none';
    var pill = document.querySelector('#panelChangeReviews > .data-source-pill');
    if (pill) pill.style.display = 'none';
    document.getElementById('crModeQueueBtn').className = 'bc-mode-pill' + (mode === 'queue' ? ' active' : '');
    document.getElementById('crModeDashBtn').className = 'bc-mode-pill' + (mode === 'dashboard' ? ' active' : '');
    // #6 Auto-load 30d on dashboard entry
    if (mode === 'dashboard' && !crDashData) { crDashLoadPrefs(); crLoadDashboard(); }
}

function crLoadDashboard() {
    var daysEl = document.getElementById('crDashDays');
    crDashDaysUsed = daysEl ? parseInt(daysEl.value) || 30 : 30;
    document.getElementById('crDashEmpty').style.display = 'none';
    document.getElementById('crDashContent').style.display = 'none';
    document.getElementById('crDashLoading').style.display = 'block';
    crDashSavePrefs();

    fetch('/api/change-reviews/dashboard?days=' + crDashDaysUsed)
        .then(checkAuth).then(function(r) { return r.json(); })
        .then(function(data) {
            document.getElementById('crDashLoading').style.display = 'none';
            if (data.error) { showCustomAlert('PLM Toolkit', data.error); return; }
            crDashData = data.rows || [];
            crRenderDashboard();
        })
        .catch(function(err) {
            document.getElementById('crDashLoading').style.display = 'none';
            showCustomAlert('PLM Toolkit', 'Dashboard failed: ' + err.message);
        });
} // selected analyst loginIds for dashboard filter

function crRenderDashboard() {
    var allRows = crDashData;
    if (!allRows || allRows.length === 0) return;

    // Apply country/analyst filters (affects charts + table)
    var baseRows = allRows;
    if (crDashFilterAnalyst.length > 0) {
        baseRows = allRows.filter(function(r) { return crDashFilterAnalyst.indexOf(r.ownerLoginId) >= 0; });
    } else if (crDashSelectedCountries.length > 0) {
        baseRows = allRows.filter(function(r) { return crDashSelectedCountries.indexOf(r.country || 'Unknown') >= 0; });
    }

    // Chart drill-down only affects the TABLE, not the charts (so charts keep the big picture)
    var rows = baseRows;
    if (crDashChartFilter) {
        var cf = crDashChartFilter;
        rows = baseRows.filter(function(r) {
            if (cf.field === 'changeType') return (r.changeType || 'Other') === cf.value;
            if (cf.field === 'country') return (r.country || 'Unknown') === cf.value;
            if (cf.field === 'status') return (r.status || 'Unknown') === cf.value;
            if (cf.field === 'ageBucket') return crMatchesAgeBucket(r, cf.value);
            return true;
        });
    }

    // Compute chart aggregates from baseRows (unfiltered by chart click)
    var byType = {}, byCountry = {}, byStatus = {}, byOwner = {};
    var ageBuckets = { '0-5d': 0, '6-10d': 0, '11-30d': 0, '31-60d': 0, '60d+': 0 };
    var totalReady = 0, totalRej = 0, totalStale = 0;

    baseRows.forEach(function(r) {
        var t = r.changeType || 'Other'; byType[t] = (byType[t] || 0) + 1;
        var c = r.country || 'Unknown'; byCountry[c] = (byCountry[c] || 0) + 1;
        var s = r.status || 'Unknown'; byStatus[s] = (byStatus[s] || 0) + 1;
        var o = r.ownerName || 'Unknown'; byOwner[o] = (byOwner[o] || 0) + 1;
        var age = r.maxAwaitingDays || 0;
        if (age <= 5) ageBuckets['0-5d']++;
        else if (age <= 10) ageBuckets['6-10d']++;
        else if (age <= 30) ageBuckets['11-30d']++;
        else if (age <= 60) ageBuckets['31-60d']++;
        else ageBuckets['60d+']++;
        var hasRej = (r.rejected || 0) > 0;
        var allOk = r.total > 0 && r.awaiting === 0 && !hasRej;
        if (allOk) totalReady++;
        if (hasRej) totalRej++;
        if (age > 10 && !hasRej && !allOk) totalStale++;
    });

    // #3 Compute blocked vs drifting (from baseRows, not chart-filtered)
    var totalBlocked = 0, totalDrifting = 0;
    baseRows.forEach(function(r) {
        var b = crComputeBlockedBy(r);
        if (b && b.type === 'blocked') totalBlocked++;
        else if (b && b.type === 'drifting') totalDrifting++;
    });

    // #1 Personal KPIs
    var myLoginId = window.currentUsername || '';
    var myRows = allRows.filter(function(r) { return r.ownerLoginId === myLoginId; });
    var myStale = myRows.filter(function(r) { return (r.maxAwaitingDays || 0) > 10; }).length;
    var myOldest = null;
    myRows.forEach(function(r) {
        var revs = (r.reviewers || []).filter(function(v) { return v.reqd && v.action === 'Awaiting'; });
        revs.forEach(function(v) { if (!myOldest || (v.days || 0) > myOldest.days) myOldest = { name: v.name, days: v.days || 0 }; });
    });

    // KPI tiles — #9 color discipline: neutral for volume, warn for stale, red for bad, green for good
    var periodLabel = crDashDaysUsed === 0 ? 'All time' : 'Last ' + crDashDaysUsed + 'd';
    var kpi = '<div class="insight-strip" style="display:flex; gap:4px; flex-wrap:wrap; align-items:center;">';
    // Personal tiles first
    if (myRows.length > 0) {
        kpi += '<div class="insight-tile" style="border-left:3px solid #4a6fa5; cursor:pointer;" onclick="crDashFilterAnalyst=[\'' + esc(myLoginId) + '\'];crDashSavePrefs();crRenderDashboard()" title="Click to filter to your changes"><div class="insight-k">My Stale</div><div class="insight-v" style="color:' + (myStale > 0 ? '#e67e22' : '#28a745') + ';">' + myStale + '</div></div>';
        kpi += '<div class="insight-tile" style="border-left:3px solid #4a6fa5;" title="' + (myOldest ? esc(myOldest.name) + ' — ' + myOldest.days + 'd' : 'None') + '"><div class="insight-k">My Oldest Wait</div><div class="insight-v insight-v-sm" style="color:' + (myOldest && myOldest.days > 10 ? '#dc3545' : '#333') + ';">' + (myOldest ? myOldest.days + 'd' : '\u2014') + '</div></div>';
    }
    kpi += '<div class="insight-tile"><div class="insight-k">In Review</div><div class="insight-v">' + rows.length + '</div><div style="font-size:10px; color:#888;">' + periodLabel + '</div></div>';
    kpi += '<div class="insight-tile"><div class="insight-k" style="color:#28a745;">Ready</div><div class="insight-v" style="color:#28a745;">' + totalReady + '</div></div>';
    kpi += '<div class="insight-tile"><div class="insight-k" style="color:#dc3545;">Rejected</div><div class="insight-v" style="color:#dc3545;">' + totalRej + '</div></div>';
    kpi += '<div class="insight-tile"><div class="insight-k" style="color:#dc3545;">Blocked</div><div class="insight-v" style="color:#dc3545;">' + totalBlocked + '</div></div>';
    kpi += '<div class="insight-tile"><div class="insight-k" style="color:#e67e22;">Drifting</div><div class="insight-v" style="color:#e67e22;">' + totalDrifting + '</div></div>';
    kpi += '<div class="insight-tile"><div class="insight-k">Analysts</div><div class="insight-v">' + Object.keys(byOwner).length + '</div></div>';
    kpi += '<div style="margin-left:auto; display:flex; align-items:center; gap:8px; flex-shrink:0;">';
    kpi += '<select id="crDashDays" onchange="crLoadDashboard()" style="font-size:12px; border:1px solid #d0d5dd; border-radius:4px; padding:3px 8px;">';
    ['7','30','90','365','0'].forEach(function(v) {
        var label = v === '0' ? 'All time' : v + ' days';
        kpi += '<option value="' + v + '"' + (parseInt(v) === crDashDaysUsed ? ' selected' : '') + '>' + label + '</option>';
    });
    kpi += '</select>';
    kpi += '<button class="btn-export" onclick="crExportDashboard()" style="font-size:12px;">&#8595; Export</button>';
    kpi += '<button class="btn-export" onclick="crLoadDashboard()" style="font-size:12px;">&#8635;</button>';
    kpi += '</div></div>';
    document.getElementById('crDashKpi').innerHTML = kpi;

    // Find current user's country from the data
    var myCountry = '';
    allRows.forEach(function(r) { if (r.ownerLoginId === myLoginId && r.country) myCountry = r.country; });

    // Filter bar: quick-filter buttons + analyst multi-select
    var allAnalysts = {};
    allRows.forEach(function(r) { allAnalysts[r.ownerLoginId] = r.ownerName; });
    var sortedAnalysts = Object.entries(allAnalysts).sort(function(a, b) { return a[1].localeCompare(b[1]); });
    var isMyFilter = crDashFilterAnalyst.length === 1 && crDashFilterAnalyst[0] === myLoginId;

    // Build country list with counts and active state
    var countryList = {};
    allRows.forEach(function(r) {
        var c = r.country || 'Unknown';
        if (!countryList[c]) countryList[c] = { count: 0, loginIds: {} };
        countryList[c].count++;
        countryList[c].loginIds[r.ownerLoginId] = true;
    });
    // Sort by count desc, my country first
    var sortedCountries = Object.keys(countryList).sort(function(a, b) {
        if (a === myCountry) return -1;
        if (b === myCountry) return 1;
        return countryList[b].count - countryList[a].count;
    });

    var fb = '';
    fb += '<div style="display:flex; align-items:center; gap:6px; flex-wrap:wrap;">';
    fb += '<button class="cr-chip' + (isMyFilter ? ' active' : '') + '" onclick="crDashFilterMe()" style="font-size:12px;" title="Show only my changes">&#128100; Me</button>';
    fb += '<span style="color:#ccc;">|</span>';
    sortedCountries.forEach(function(c) {
        var cIds = Object.keys(countryList[c].loginIds);
        var isActive = crDashSelectedCountries.indexOf(c) >= 0;
        var icon = c === myCountry ? '&#127968; ' : '';
        fb += '<button class="cr-chip' + (isActive ? ' active' : '') + '" onclick="crDashToggleCountry(\'' + esc(c).replace(/'/g, "\\'") + '\')" style="font-size:11px;">' + icon + esc(c) + ' (' + countryList[c].count + ')</button>';
    });
    if (crDashFilterAnalyst.length > 0 || crDashSelectedCountries.length > 0) {
        fb += '<span style="color:#ccc;">|</span>';
        fb += '<button class="cr-chip" onclick="crDashFilterAnalyst=[];crDashSelectedCountries=[];crDashSavePrefs();crRenderDashboard()" style="font-size:11px;">&#10005; Clear all</button>';
    }
    fb += '</div>';
    fb += '<div style="display:flex; align-items:center; gap:6px; margin-top:6px;">';
    fb += '<span style="font-size:12px; font-weight:600; color:#555;">Filter by analyst:</span> ';
    fb += '<select id="crDashAnalystSelect" multiple style="font-size:12px; border:1px solid #d0d5dd; border-radius:4px; padding:2px 6px; min-width:200px; max-width:400px; height:auto; max-height:80px;" onchange="crDashApplyAnalystFilter()">';
    sortedAnalysts.forEach(function(a) {
        var sel = crDashFilterAnalyst.indexOf(a[0]) >= 0 ? ' selected' : '';
        fb += '<option value="' + esc(a[0]) + '"' + sel + '>' + esc(a[1]) + '</option>';
    });
    fb += '</select>';
    if (crDashFilterAnalyst.length > 0) {
        fb += ' <span style="font-size:11px; color:#888;">(' + crDashFilterAnalyst.length + ' selected)</span>';
    }
    fb += '</div>';
    document.getElementById('crDashFilterBar').innerHTML = fb;

    // Charts
    var charts = '';
    charts += '<div style="flex:1; min-width:260px;">' + crPieChart('By Change Type', byType, 'changeType') + '</div>';
    charts += '<div style="flex:1; min-width:260px;">' + crPieChart('By Country', byCountry, 'country') + '</div>';
    charts += '<div style="flex:1; min-width:300px;">' + crBarChart('Aging Distribution', ageBuckets, 'ageBucket') + '</div>';
    var countryAging = {};
    baseRows.forEach(function(r) {
        var c = r.country || 'Unknown';
        if (!countryAging[c]) countryAging[c] = { ok: 0, stale: 0 };
        if ((r.maxAwaitingDays || 0) > 10) countryAging[c].stale++;
        else countryAging[c].ok++;
    });
    charts += '<div style="flex:1; min-width:300px;">' + crStackedBar('Country Health', countryAging) + '</div>';
    document.getElementById('crDashCharts').innerHTML = charts;

    // Changes table grouped by analyst
    var grouped = {};
    rows.forEach(function(r) {
        var key = r.ownerLoginId || 'unknown';
        if (!grouped[key]) grouped[key] = { name: r.ownerName, country: r.country || '', rows: [] };
        grouped[key].rows.push(r);
    });
    // Sort groups by total changes desc
    var groups = Object.values(grouped).sort(function(a, b) { return b.rows.length - a.rows.length; });

    var table = '';
    if (crDashChartFilter) {
        table += '<div style="margin:12px 0 4px; display:flex; align-items:center; gap:8px;">';
        table += '<span style="font-size:13px; color:#1a3a5c; font-weight:600;">Filtered: ' + esc(crDashChartFilter.field === 'ageBucket' ? 'Age ' : '') + esc(crDashChartFilter.value) + '</span>';
        table += '<button class="cr-chip" onclick="crDashChartFilter=null;crRenderDashboard()" style="font-size:11px;">&#10005; Clear</button>';
        table += '</div>';
    }
    table += '<div style="display:flex; align-items:center; gap:10px; margin:8px 0;">';
    table += '<h3 style="font-size:14px; color:#333; margin:0;">Changes by Analyst (' + rows.length + ' total)</h3>';
    table += '<button class="cr-chip" onclick="crDashToggleAllGroups(false)" style="font-size:11px;" title="Collapse all analyst groups">&#9660; Collapse all</button>';
    table += '<button class="cr-chip" onclick="crDashToggleAllGroups(true)" style="font-size:11px;" title="Expand all analyst groups">&#9650; Expand all</button>';
    table += '</div>';
    table += '<table class="data-table" style="font-size:12px; width:100%;">';
    table += '<thead><tr><th>Change</th><th>Description</th><th>Status</th><th>Type</th><th>Age</th><th>Next Approver</th><th>Signoff</th><th style="width:60px;"></th></tr></thead><tbody>';

    groups.forEach(function(g) {
        // Group header
        var gStale = g.rows.filter(function(r) { return (r.maxAwaitingDays || 0) > 10; }).length;
        var gReady = g.rows.filter(function(r) { return r.total > 0 && r.awaiting === 0 && (r.rejected || 0) === 0; }).length;
        var gRej = g.rows.filter(function(r) { return (r.rejected || 0) > 0; }).length;
        var badges = '';
        if (gReady) badges += '<span style="color:#28a745; font-size:11px; margin-left:8px;">&#10003;' + gReady + ' ready</span>';
        if (gRej) badges += '<span style="color:#dc3545; font-size:11px; margin-left:8px;">&#10007;' + gRej + ' rejected</span>';
        if (gStale) badges += '<span style="color:#e67e22; font-size:11px; margin-left:8px;">&#9888;' + gStale + ' stale</span>';
        var countryBadge = g.country ? ' <span style="color:#2980b9; font-size:10px;">(' + esc(g.country) + ')</span>' : '';

        var gLoginId = g.rows[0] ? g.rows[0].ownerLoginId : '';
        table += '<tr class="cr-group-hdr" style="background:#e9ecef; cursor:pointer;" onclick="this.classList.toggle(\'cr-collapsed\');var s=this.nextElementSibling;while(s&&!s.classList.contains(\'cr-group-hdr\')){s.style.display=s.style.display===\'none\'?\'\':\'none\';s=s.nextElementSibling;}">';
        table += '<td colspan="7" style="font-weight:700; font-size:13px; padding:8px 12px;"><span class="cr-group-caret">&#9662;</span> ' + esc(g.name) + countryBadge + ' <span style="color:#888; font-weight:400;">(' + g.rows.length + ')</span>' + badges + '</td>';
        table += '<td style="text-align:right; padding:8px 12px;"><button class="cr-action-btn" style="opacity:0.6; font-size:11px;" onclick="event.stopPropagation();crSwitchMode(\'queue\');crPickUser(\'' + esc(gLoginId).replace(/'/g, "\\'") + '\',\'' + esc(g.name).replace(/'/g, "\\'") + '\')" title="Open in Queue view">&#8599; Queue</button></td>';
        table += '</tr>';

        g.rows.sort(function(a, b) { return (b.maxAwaitingDays || 0) - (a.maxAwaitingDays || 0); });
        g.rows.forEach(function(r, i) {
            var age = r.maxAwaitingDays || 0;
            var ageColor = age > 30 ? '#dc3545' : age > 10 ? '#e67e22' : '#666';
            var hasRej = (r.rejected || 0) > 0;
            var allOk = r.total > 0 && r.awaiting === 0 && !hasRej;
            var bg = hasRej ? '#fdeaea' : allOk ? '#e8f5e9' : (i % 2 === 1 ? '#f8f9fa' : '#fff');
            var desc = r.description.length > 80 ? r.description.substring(0, 77) + '...' : r.description;
            var bar = crBuildProgressBar(r);
            var reqT = r.requiredTotal || 0, reqD = r.requiredDone || 0;
            var pct = reqT > 0 ? Math.round(reqD / reqT * 100) : 0;
            var pctColor = allOk ? '#28a745' : hasRej ? '#dc3545' : '#666';

            // #3 Blocked badge
            var blocked = crComputeBlockedBy(r);
            var blockedHtml = '';
            if (blocked && blocked.type === 'blocked') {
                blockedHtml = '<div class="cr-blocked-badge">\ud83d\udeab Blocked by ' + esc(blocked.name.split('(')[0].trim()) + ' \u00b7 ' + blocked.days + 'd</div>';
            } else if (blocked && blocked.type === 'drifting') {
                blockedHtml = '<div class="cr-blocked-badge" style="color:#e67e22; background:#fff3e0;">\u26a0 ' + blocked.count + ' reviewers drifting \u00b7 ' + blocked.days + 'd</div>';
            }

            // #4 Next approver
            var nextApp = crNextApprover(r);

            // #2 Hover actions
            var actions = '<span class="cr-row-actions">';
            actions += '<button class="cr-action-btn" title="Copy change number" onclick="crCopyChange(event,\'' + esc(r.changeNumber).replace(/'/g, "\\'") + '\')">\ud83d\udccb</button>';
            if (nextApp) actions += '<button class="cr-action-btn" title="Email ' + esc(nextApp.split('(')[0].trim()) + '" onclick="crEmailReviewer(event,\'' + esc(r.changeNumber).replace(/'/g, "\\'") + '\',\'' + esc(nextApp).replace(/'/g, "\\'") + '\')">\u2709</button>';
            actions += '<button class="cr-action-btn" title="Open in Agile PLM" onclick="crOpenInAgile(event,\'' + esc(r.changeType).replace(/'/g, "\\'") + '\',\'' + esc(r.changeNumber).replace(/'/g, "\\'") + '\')">\ud83d\udd17</button>';
            actions += '</span>';

            table += '<tr style="background:' + bg + '; cursor:pointer;" onclick="crShowDetail(\'' + esc(r.changeNumber).replace(/'/g, "\\'") + '\')">';
            table += '<td style="font-weight:600; white-space:nowrap; font-family:\'IBM Plex Mono\',monospace;"><a href="' + crAgileUrl(r.changeType, r.changeNumber) + '" target="_blank" onclick="event.stopPropagation()" style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;" title="Open in Agile PLM">' + esc(r.changeNumber) + '</a></td>';
            table += '<td title="' + esc(r.description) + '">' + esc(desc) + blockedHtml + '</td>';
            table += '<td>' + crStatusBadge(r.status) + '</td>';
            table += '<td><span style="background:#e8f0fe; color:#1a3a5c; padding:2px 6px; border-radius:4px; font-size:11px; font-weight:600;">' + esc(r.changeType) + '</span></td>';
            table += '<td style="font-weight:600; color:' + ageColor + '; font-family:\'IBM Plex Mono\',monospace; text-align:right;">' + age + 'd</td>';
            table += '<td style="font-size:11px; white-space:nowrap; color:#555;">' + esc(nextApp ? nextApp.split('(')[0].trim() : '') + '</td>';
            table += '<td style="white-space:nowrap;">' + bar + ' <span style="font-size:11px; color:' + pctColor + '; font-weight:600; font-family:\'IBM Plex Mono\',monospace;">' + pct + '%</span></td>';
            table += '<td>' + actions + '</td>';
            table += '</tr>';
        });
    });
    table += '</tbody></table>';
    document.getElementById('crDashTable').innerHTML = table;
    document.getElementById('crDashContent').style.display = 'block';
}

function crDashFilterMe() {
    var myId = window.currentUsername || '';
    if (!myId) return;
    crDashFilterAnalyst = [myId];
    crDashSavePrefs();
    crRenderDashboard();
}

function crDashToggleCountry(country) {
    if (!country || !crDashData) return;
    var idx = crDashSelectedCountries.indexOf(country);
    if (idx >= 0) crDashSelectedCountries.splice(idx, 1);
    else crDashSelectedCountries.push(country);

    // Rebuild analyst filter from selected countries
    if (crDashSelectedCountries.length > 0) {
        var ids = [], seen = {};
        crDashData.forEach(function(r) {
            if (crDashSelectedCountries.indexOf(r.country || 'Unknown') >= 0 && !seen[r.ownerLoginId]) {
                ids.push(r.ownerLoginId);
                seen[r.ownerLoginId] = true;
            }
        });
        crDashFilterAnalyst = ids;
    } else {
        crDashFilterAnalyst = [];
    }
    crDashSavePrefs();
    crRenderDashboard();
}

function crDashToggleAllGroups(expand) {
    var headers = document.querySelectorAll('#crDashTable .cr-group-hdr');
    headers.forEach(function(hdr) {
        if (expand) hdr.classList.remove('cr-collapsed');
        else hdr.classList.add('cr-collapsed');
        var s = hdr.nextElementSibling;
        while (s && !s.classList.contains('cr-group-hdr')) {
            s.style.display = expand ? '' : 'none';
            s = s.nextElementSibling;
        }
    });
}

function crDashApplyAnalystFilter() {
    var sel = document.getElementById('crDashAnalystSelect');
    crDashFilterAnalyst = [];
    for (var i = 0; i < sel.options.length; i++) {
        if (sel.options[i].selected) crDashFilterAnalyst.push(sel.options[i].value);
    }
    crDashSavePrefs();
    crRenderDashboard();
}

// #3 Compute blocked-by info per row (client-side)
function crComputeBlockedBy(r) {
    var revs = (r.reviewers || []).filter(function(v) { return v.reqd && v.action === 'Awaiting'; });
    if (revs.length === 0) return null;
    // Sort by days descending
    revs.sort(function(a, b) { return (b.days || 0) - (a.days || 0); });
    if (revs.length === 1 && (revs[0].days || 0) > 10) {
        return { name: revs[0].name, days: revs[0].days, type: 'blocked' };
    }
    if (revs.length > 1 && (revs[0].days || 0) > 10) {
        return { name: revs[0].name, days: revs[0].days, count: revs.length, type: 'drifting' };
    }
    return null;
}

// #4 Next required approver
function crNextApprover(r) {
    var revs = (r.reviewers || []).filter(function(v) { return v.reqd && v.action === 'Awaiting'; });
    if (revs.length === 0) return '';
    revs.sort(function(a, b) { return (b.days || 0) - (a.days || 0); });
    return revs[0].name || '';
}

// #2 Row hover action: copy change number
function crCopyChange(e, cn) {
    e.stopPropagation();
    var ta = document.createElement('textarea'); ta.value = cn; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta);
    var el = e.target; el.textContent = '\u2713'; setTimeout(function() { el.textContent = '\ud83d\udccb'; }, 1000);
}

// #2 Row hover action: email reviewer
function crEmailReviewer(e, cn, reviewerName) {
    e.stopPropagation();
    var subject = encodeURIComponent('[' + cn + '] Signoff pending — please review');
    window.open('mailto:?subject=' + subject + '&body=' + encodeURIComponent('Hi ' + reviewerName.split('(')[0].trim() + ',\n\nCould you please review and approve ' + cn + '? It is awaiting your signoff.\n\nThank you.'), '_blank');
}

// Open in Agile PLM deep link
function crAgileUrl(changeType, changeNumber) {
    return (window.AGILE_WEBCLIENT_URL || 'https://plm.sandisk.com/Agile') + '/object/' + encodeURIComponent(changeType) + '/' + encodeURIComponent(changeNumber);
}
function crOpenInAgile(e, changeType, changeNumber) {
    e.stopPropagation();
    window.open(crAgileUrl(changeType, changeNumber), '_blank');
}

// --- SVG Charts (clickable) ---
function crPieChart(title, data, filterField) {
    var entries = Object.entries(data).sort(function(a, b) { return b[1] - a[1]; });
    var total = entries.reduce(function(s, e) { return s + e[1]; }, 0);
    if (total === 0) return '';
    var colors = ['#1a3a5c', '#e67e22', '#28a745', '#dc3545', '#2980b9', '#8e44ad', '#f39c12', '#1abc9c', '#e74c3c', '#95a5a6'];
    var cx = 90, cy = 90, r = 80;
    var svg = '<svg width="180" height="180" viewBox="0 0 180 180" style="cursor:pointer;">';
    var angle = -Math.PI / 2;
    entries.forEach(function(e, i) {
        var slice = (e[1] / total) * 2 * Math.PI;
        var x1 = cx + r * Math.cos(angle);
        var y1 = cy + r * Math.sin(angle);
        var x2 = cx + r * Math.cos(angle + slice);
        var y2 = cy + r * Math.sin(angle + slice);
        var large = slice > Math.PI ? 1 : 0;
        var d = 'M' + cx + ',' + cy + ' L' + x1.toFixed(2) + ',' + y1.toFixed(2) + ' A' + r + ',' + r + ' 0 ' + large + ',1 ' + x2.toFixed(2) + ',' + y2.toFixed(2) + ' Z';
        var isActive = crDashChartFilter && crDashChartFilter.field === filterField && crDashChartFilter.value === e[0];
        svg += '<path d="' + d + '" fill="' + colors[i % colors.length] + '" onclick="crDashApplyChartFilter(\'' + esc(filterField) + '\',\'' + esc(e[0]).replace(/'/g, "\\'") + '\')" style="cursor:pointer;' + (isActive ? ' stroke:#000; stroke-width:3;' : '') + '"><title>' + e[0] + ': ' + e[1] + ' (' + Math.round(e[1] / total * 100) + '%) — click to filter</title></path>';
        angle += slice;
    });
    svg += '</svg>';

    var legend = entries.map(function(e, i) {
        var isActive = crDashChartFilter && crDashChartFilter.field === filterField && crDashChartFilter.value === e[0];
        return '<div style="display:flex; align-items:center; gap:6px; font-size:11px; cursor:pointer; padding:1px 4px; border-radius:3px;' + (isActive ? ' background:#e8f0fe; font-weight:700;' : '') + '" onclick="crDashApplyChartFilter(\'' + esc(filterField) + '\',\'' + esc(e[0]).replace(/'/g, "\\'") + '\')"><span style="width:10px; height:10px; border-radius:2px; background:' + colors[i % colors.length] + '; flex-shrink:0;"></span>' + esc(e[0]) + ' <strong>' + e[1] + '</strong> <span style="color:#888;">(' + Math.round(e[1] / total * 100) + '%)</span></div>';
    }).join('');

    return '<div style="background:#fff; border:1px solid #e8e6df; border-radius:8px; padding:16px;">' +
           '<div style="font-weight:700; font-size:13px; color:#333; margin-bottom:10px;">' + esc(title) + '</div>' +
           '<div style="display:flex; gap:16px; align-items:center;">' + svg +
           '<div style="display:flex; flex-direction:column; gap:4px;">' + legend + '</div></div></div>';
}

function crBarChart(title, data, filterField) {
    var entries = Object.entries(data);
    var max = Math.max.apply(null, entries.map(function(e) { return e[1]; }));
    if (max === 0) max = 1;
    var barColors = { '0-5d': '#28a745', '6-10d': '#f39c12', '11-30d': '#e67e22', '31-60d': '#dc3545', '60d+': '#8b0000' };
    var bars = entries.map(function(e) {
        var pct = (e[1] / max * 100).toFixed(0);
        var color = barColors[e[0]] || '#1a3a5c';
        var isActive = crDashChartFilter && crDashChartFilter.field === filterField && crDashChartFilter.value === e[0];
        return '<div style="display:flex; align-items:center; gap:8px; margin-bottom:6px; cursor:pointer; padding:2px 4px; border-radius:4px;' + (isActive ? ' background:#e8f0fe;' : '') + '" onclick="crDashApplyChartFilter(\'' + esc(filterField) + '\',\'' + esc(e[0]).replace(/'/g, "\\'") + '\')">' +
               '<span style="width:50px; font-size:11px; font-weight:600; text-align:right; color:#555;">' + e[0] + '</span>' +
               '<div style="flex:1; background:#f0f0f0; border-radius:3px; height:20px; overflow:hidden;">' +
               '<div style="width:' + pct + '%; background:' + color + '; height:100%; border-radius:3px; min-width:' + (e[1] > 0 ? '2px' : '0') + ';"></div></div>' +
               '<span style="width:30px; font-size:12px; font-weight:700; color:#333;">' + e[1] + '</span></div>';
    }).join('');

    return '<div style="background:#fff; border:1px solid #e8e6df; border-radius:8px; padding:16px;">' +
           '<div style="font-weight:700; font-size:13px; color:#333; margin-bottom:10px;">' + esc(title) + '</div>' + bars + '</div>';
}

function crStackedBar(title, data) {
    var entries = Object.entries(data).sort(function(a, b) { return (b[1].ok + b[1].stale) - (a[1].ok + a[1].stale); });
    var max = Math.max.apply(null, entries.map(function(e) { return e[1].ok + e[1].stale; }));
    if (max === 0) max = 1;

    var bars = entries.map(function(e) {
        var total = e[1].ok + e[1].stale;
        var okPct = (e[1].ok / max * 100).toFixed(0);
        var stalePct = (e[1].stale / max * 100).toFixed(0);
        return '<div style="display:flex; align-items:center; gap:8px; margin-bottom:6px;">' +
               '<span style="width:80px; font-size:11px; font-weight:600; text-align:right; color:#555; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="' + esc(e[0]) + '">' + esc(e[0]) + '</span>' +
               '<div style="flex:1; background:#f0f0f0; border-radius:3px; height:20px; overflow:hidden; display:flex;">' +
               '<div style="width:' + okPct + '%; background:#28a745; height:100%;" title="On track: ' + e[1].ok + '"></div>' +
               '<div style="width:' + stalePct + '%; background:#dc3545; height:100%;" title="Stale >10d: ' + e[1].stale + '"></div></div>' +
               '<span style="width:30px; font-size:12px; font-weight:700; color:#333;">' + total + '</span></div>';
    }).join('');

    return '<div style="background:#fff; border:1px solid #e8e6df; border-radius:8px; padding:16px;">' +
           '<div style="font-weight:700; font-size:13px; color:#333; margin-bottom:10px;">' + esc(title) + '</div>' +
           '<div style="display:flex; gap:12px; margin-bottom:8px; font-size:11px;"><span><span style="display:inline-block; width:10px; height:10px; background:#28a745; border-radius:2px;"></span> On track</span><span><span style="display:inline-block; width:10px; height:10px; background:#dc3545; border-radius:2px;"></span> Stale &gt;10d</span></div>' +
           bars + '</div>';
}

function crExportDashboard() {
    guardExport(function() {
        window.location.href = '/api/change-reviews/dashboard/export?days=' + crDashDaysUsed;
    });
}

// Auto-load self on first tab visit
var crAutoLoaded = false;
function crAutoLoadSelf() {
    if (crAutoLoaded) return;
    var loginId = window.currentUsername;
    var displayName = window.currentDisplayName;
    if (!loginId) return;
    crAutoLoaded = true;
    crSelectedAnalysts = [{ loginId: loginId, displayName: displayName }];
    crRenderSelectedChips();
    doChangeReviewSearch();
}

// Close dropdown when clicking outside
document.addEventListener('click', function(e) {
    var dd = document.getElementById('crUserDropdown');
    var input = document.getElementById('crUserInput');
    if (dd && dd.style.display !== 'none' && !dd.contains(e.target) && e.target !== input) dd.style.display = 'none';
});

// === AD Health Check ===
function runAdHealthCheck() {
    var user = document.getElementById('adHealthUser').value.trim();
    var pass = document.getElementById('adHealthPass').value;
    if (!user || !pass) { showCustomAlert('PLM Toolkit', 'Enter username and password.'); return; }

    var btn = document.getElementById('adHealthBtn');
    btn.disabled = true;
    btn.textContent = 'Testing...';
    var results = document.getElementById('adHealthResults');
    results.style.display = 'none';

    fetch('/api/admin/ad-health', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: user, password: pass })
    })
    .then(checkAuth)
    .then(function(res) { return res.json(); })
    .then(function(data) {
        btn.disabled = false;
        btn.textContent = 'Test AD Connection';
        if (data.error) {
            results.innerHTML = '<div style="padding:16px; background:#f8d7da; border:1px solid #f5c6cb; border-radius:8px; color:#721c24;">' + esc(data.error) + '</div>';
            results.style.display = 'block';
            return;
        }
        renderAdHealthResults(data);
    })
    .catch(function(err) {
        btn.disabled = false;
        btn.textContent = 'Test AD Connection';
        results.innerHTML = '<div style="padding:16px; background:#f8d7da; border:1px solid #f5c6cb; border-radius:8px; color:#721c24;">Request failed: ' + esc(err.message) + '</div>';
        results.style.display = 'block';
    });
}

function renderAdHealthResults(d) {
    var verdictColors = { FAST: '#28a745', NORMAL: '#17a2b8', SLOW: '#ffc107', VERY_SLOW: '#dc3545', UNREACHABLE: '#dc3545', AUTH_FAILED: '#e67e22' };
    var verdictLabels = { FAST: 'Fast', NORMAL: 'Normal', SLOW: 'Slow', VERY_SLOW: 'Very Slow', UNREACHABLE: 'Unreachable', AUTH_FAILED: 'Wrong Password' };
    var vc = verdictColors[d.verdict] || '#888';
    var vl = verdictLabels[d.verdict] || d.verdict;

    var html = '<div style="border:1px solid #e0e0e0; border-radius:8px; overflow:hidden;">';

    // Verdict banner
    html += '<div style="padding:16px 20px; background:' + vc + '; color:white; display:flex; justify-content:space-between; align-items:center;">';
    html += '<div><div style="font-size:11px; text-transform:uppercase; letter-spacing:1px; opacity:0.8;">AD Status</div>';
    html += '<div style="font-size:22px; font-weight:700;">' + vl + '</div></div>';
    html += '<div style="font-size:28px; font-weight:700;">' + d.totalMs + 'ms</div>';
    html += '</div>';

    // Detail rows
    html += '<div style="padding:0;">';

    function row(label, value, status) {
        var sc = status === 'OK' ? '#28a745' : status === 'AUTH_FAILED' ? '#dc3545' : status === 'UNREACHABLE' ? '#dc3545' : '#888';
        var badge = '<span style="display:inline-block; padding:2px 8px; border-radius:4px; font-size:11px; font-weight:600; background:' + sc + '15; color:' + sc + ';">' + status + '</span>';
        return '<div style="padding:12px 20px; border-bottom:1px solid #f0f0f0; display:flex; justify-content:space-between; align-items:center;">' +
            '<div><span style="font-weight:600; color:#333;">' + label + '</span></div>' +
            '<div style="display:flex; align-items:center; gap:12px;">' + badge + '<span style="font-family:monospace; font-size:14px; font-weight:600;">' + value + '</span></div></div>';
    }

    html += row('User Bind', d.userBindMs + 'ms', d.userBindStatus || '—');
    if (d.svcBindMs !== undefined) html += row('Service Account Bind', d.svcBindMs + 'ms', d.svcBindStatus || '—');
    if (d.groupLookupMs !== undefined) html += row('Group Lookup (' + (d.groupCount || 0) + ' groups)', d.groupLookupMs + 'ms', d.groupLookupStatus || '—');

    // Info section
    html += '<div style="padding:12px 20px; background:#f8f9fa; font-size:12px; color:#666;">';
    if (d.displayName) html += '<div>User: <strong>' + esc(d.displayName) + '</strong></div>';
    html += '<div>Server: ' + esc(d.ldapUrl || '') + '</div>';
    html += '<div>Tested: ' + esc(d.timestamp || '') + '</div>';
    html += '</div>';

    if (d.userBindError) {
        html += '<div style="padding:12px 20px; background:#f8d7da; color:#721c24; font-size:13px;">Error: ' + esc(d.userBindError) + '</div>';
    }
    if (d.svcBindError) {
        html += '<div style="padding:12px 20px; background:#f8d7da; color:#721c24; font-size:13px;">Service bind error: ' + esc(d.svcBindError) + '</div>';
    }

    html += '</div></div>';

    var el = document.getElementById('adHealthResults');
    el.innerHTML = html;
    el.style.display = 'block';
}

// ---------------------------------------------------------------------------
// Announcement banner (Admin > Communications). Shown to EVERY user on login
// when a sent announcement had the "in-app banner" channel enabled and this
// user hasn't dismissed it yet. Styling mirrors #betaBanner; dismissal posts
// to the server so it's per-user per-announcement (spec: shows once).
// ---------------------------------------------------------------------------
function npCheckAnnouncementBanner() {
    fetch('/api/announcements/banner', { credentials: 'same-origin' })
        .then(function(r) { return r.ok ? r.json() : null; })
        .then(function(d) {
            if (!d || !d.id) return;
            if (document.getElementById('annBanner')) return;
            var bar = document.createElement('div');
            bar.id = 'annBanner';
            bar.setAttribute('data-ann-id', d.id);
            bar.style.cssText = 'background:#fff3cd; color:#856404; text-align:center; ' +
                'padding:6px 38px 6px 16px; font-size:12px; border-bottom:1px solid #ffc107; position:relative;';
            var strong = document.createElement('strong');
            strong.textContent = 'Announcement';
            bar.appendChild(strong);
            bar.appendChild(document.createTextNode(' — ' + (d.subject || '')));
            var x = document.createElement('button');
            x.className = 'np-beta-x';
            x.title = 'Dismiss';
            x.setAttribute('aria-label', 'Dismiss announcement');
            x.innerHTML = '&times;';
            x.style.cssText = 'position:absolute; right:8px; top:50%; transform:translateY(-50%); ' +
                'background:transparent; border:none; color:#856404; cursor:pointer; font-size:14px; padding:2px 8px; line-height:1;';
            x.onclick = function() {
                bar.style.display = 'none';
                fetch('/api/announcements/banner/' + encodeURIComponent(d.id) + '/dismiss',
                      { method: 'POST', credentials: 'same-origin' }).catch(function() {});
            };
            bar.appendChild(x);
            var beta = document.getElementById('betaBanner');
            if (beta && beta.parentNode) {
                beta.parentNode.insertBefore(bar, beta.nextSibling);
            } else {
                document.body.insertBefore(bar, document.body.firstChild);
            }
        })
        .catch(function() {});
}
