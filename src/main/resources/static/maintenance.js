// ========================================================================
// Maintenance — header link (allowlist), schedule modal, sticky countdown banner.
// Polls /api/maintenance/status every 15s. Shows full-screen modal in the last
// 30 seconds before shutdown. Survives page reloads (state lives on the server).
// ========================================================================

(function () {
    'use strict';

    var POLL_MS = 15000;
    var LAST_STATUS = null;
    var MODAL_SHOWN = false;

    function $(id) { return document.getElementById(id); }

    function fmtRemaining(ms) {
        if (ms <= 0) return '00:00';
        var total = Math.floor(ms / 1000);
        var m = Math.floor(total / 60);
        var s = total % 60;
        return (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);
    }

    function ensureBanner() {
        var b = $('maintBanner');
        if (b) return b;
        b = document.createElement('div');
        b.id = 'maintBanner';
        b.style.cssText = 'display:none; position:sticky; top:0; z-index:9998; '
            + 'background:#B8342B; color:#fff; padding:10px 16px; text-align:center; '
            + 'font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif; '
            + 'font-size:13px; font-weight:600; box-shadow:0 2px 6px rgba(0,0,0,0.18);';
        document.body.insertBefore(b, document.body.firstChild);
        return b;
    }

    function ensureModal() {
        var m = $('maintModal');
        if (m) return m;
        m = document.createElement('div');
        m.id = 'maintModal';
        m.style.cssText = 'display:none; position:fixed; inset:0; z-index:10001; '
            + 'background:rgba(15,23,32,0.85); align-items:center; justify-content:center; '
            + 'font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;';
        m.innerHTML = '<div style="background:#fff; max-width:480px; padding:28px; border-radius:8px; text-align:center;">'
            + '<div style="font-size:36px; margin-bottom:8px;">&#9888;</div>'
            + '<h2 style="margin:0 0 12px; font-family:\'IBM Plex Serif\',Georgia,serif; font-size:22px;">Maintenance starting now</h2>'
            + '<div style="color:#0F1720; font-size:14px; line-height:1.55;">PLM Toolkit is going offline for scheduled maintenance. '
            + 'Save anything unsaved \u2014 the app will be unavailable for a few minutes.</div>'
            + '<div id="maintModalCountdown" style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:32px; '
            + 'color:#B8342B; margin:18px 0 6px; font-weight:700;">00:30</div>'
            + '<button id="maintModalExtend" onclick="extendMaint(10)" style="display:none; margin:4px 0 10px; background:#1F8A4C; color:#fff; border:0; border-radius:6px; padding:10px 16px; font-size:13px; font-weight:600; cursor:pointer;">+10 more minutes</button>'
            + '<div style="color:#6B7280; font-size:12px;">You\u0027ll get an email when the app is back online.</div>'
            + '</div>';
        document.body.appendChild(m);
        return m;
    }

    function updateBanner(status) {
        var banner = ensureBanner();
        var modal = ensureModal();
        if (!status || !status.pending) {
            banner.style.display = 'none';
            modal.style.display = 'none';
            MODAL_SHOWN = false;
            return;
        }
        var p = status.pending;
        var now = Date.now();
        var remaining = p.targetEpochMs - now;
        if (remaining <= 0) {
            banner.style.display = 'block';
            banner.textContent = 'PLM Toolkit is going offline now \u2014 hang tight, you\u0027ll get an email when it\u0027s back.';
            modal.style.display = 'flex';
            $('maintModalCountdown').textContent = '00:00';
            return;
        }
        banner.style.display = 'block';
        var remStr = fmtRemaining(remaining);
        // Hide "by X" when the scheduler is a system actor (deploy.bat). Real users
        // saw "by deploy.bat (server console)" before — implementation detail leaking
        // into a user-facing banner. The reason text already carries enough context.
        var by = p.scheduledByDisplayName || '';
        var byIsSystem = !by || /^deploy\.bat/i.test(by);
        var bySegment = byIsSystem ? '' : (' \u00b7 by ' + escapeHtml(by));
        var reason = p.reason ? ' \u00b7 ' + p.reason : '';
        banner.innerHTML = '&#9888; <strong>Scheduled maintenance in ' + remStr + '</strong>'
            + bySegment + escapeHtml(reason)
            + ' \u00b7 you\u0027ll get an email when it\u0027s back online.'
            + (status.canExtend
                ? ' · Need more time? <a href="#" onclick="extendMaint(5); return false;" style="color:#fff; text-decoration:underline; margin-left:6px;">+5 min</a>'
                    + ' <a href="#" onclick="extendMaint(10); return false;" style="color:#fff; text-decoration:underline; margin-left:6px;">+10 min</a>'
                : (status.pending ? ' · Maximum extension reached — please save your work.' : ''))
            + (status.canSchedule
                ? ' <a href="#" onclick="cancelMaint(); return false;" style="color:#fff; text-decoration:underline; margin-left:12px;">Cancel</a>'
                : '');

        // Last 30 seconds: surface a full-screen modal.
        if (remaining <= 30000) {
            modal.style.display = 'flex';
            $('maintModalCountdown').textContent = fmtRemaining(remaining);
            var mx = $('maintModalExtend');
            if (mx) mx.style.display = status.canExtend ? 'inline-block' : 'none';
            MODAL_SHOWN = true;
        } else if (MODAL_SHOWN) {
            modal.style.display = 'none';
            MODAL_SHOWN = false;
        }
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function fetchStatus() {
        return fetch('/api/maintenance/status', { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (s) {
                LAST_STATUS = s;
                updateBanner(s);
                // Toggle the header link based on canSchedule.
                var link = $('maintLink');
                if (link) link.style.display = (s && s.canSchedule) ? '' : 'none';
                return s;
            })
            .catch(function () { /* ignore transient errors — try again next tick */ });
    }

    // Animate countdown between polls so the seconds tick smoothly.
    setInterval(function () { updateBanner(LAST_STATUS); }, 1000);
    setInterval(fetchStatus, POLL_MS);
    document.addEventListener('DOMContentLoaded', fetchStatus);
    // In case DOMContentLoaded already fired:
    if (document.readyState === 'interactive' || document.readyState === 'complete') fetchStatus();

    // ----- Admin actions (only used when canSchedule=true) ---------------

    window.openMaintModal = function () {
        if (!LAST_STATUS || !LAST_STATUS.canSchedule) {
            appAlert('Only the maintenance admins can schedule a shutdown.');
            return;
        }
        var existing = LAST_STATUS.pending;
        appPrompt(
            existing
                ? 'A shutdown is already scheduled. Cancel it first via the banner.'
                : 'Take PLM Toolkit down for maintenance in how many minutes?\n(1\u201360, default 10)',
            existing ? '' : '10',
            { okText: 'Next' }
        ).then(function (minutes) {
            if (existing || !minutes) return;
            var m = parseInt(minutes, 10);
            if (isNaN(m) || m < 1 || m > 60) { appAlert('Enter a number between 1 and 60.'); return; }
            appPrompt('Optional reason (shown to users in the banner + email):', '', { placeholder: 'e.g. hotfix for PT-46', okText: 'Schedule' }).then(function (reason) {
                reason = reason || '';
                fetch('/api/maintenance/schedule', {
                    method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ minutes: m, reason: reason })
                }).then(function (r) {
                    return r.json().then(function (j) { return { ok: r.ok, body: j }; });
                }).then(function (resp) {
                    if (!resp.ok) {
                        appAlert('Failed: ' + (resp.body.error || 'unknown'));
                        return;
                    }
                    appAlert('Shutdown scheduled in ' + m + ' min. ' + resp.body.recipientCount
                          + ' active user(s) emailed.');
                    fetchStatus();
                });
            });
        });
    };

    // Any user can request more time before the shutdown. Server caps the total
    // extension, so this fails gracefully once the budget is exhausted.
    window.extendMaint = function (minutes) {
        fetch('/api/maintenance/extend', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ minutes: minutes })
        }).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok, body: j }; });
        }).then(function (resp) {
            if (!resp.ok || !resp.body.success) {
                appAlert(resp.body.error || 'Could not extend the maintenance window.');
            } else {
                var added = resp.body.appliedMinutes;
                var leftMin = Math.round((resp.body.extendBudgetMs || 0) / 60000);
                appAlert('Added ' + added + ' more minute' + (added === 1 ? '' : 's') + '.'
                    + (leftMin > 0 ? ' You can extend up to ' + leftMin + ' more min if needed.' : ' Maximum extension reached.'));
            }
            fetchStatus();   // refresh banner countdown + budget immediately
        }).catch(function (e) {
            appAlert('Could not extend the maintenance window: ' + e.message);
        });
    };

    window.cancelMaint = function () {
        if (!LAST_STATUS || !LAST_STATUS.canSchedule) return;
        appConfirm('Cancel the scheduled maintenance?', { okText: 'Cancel maintenance', cancelText: 'Keep scheduled' }).then(function (ok) {
            if (!ok) return;
            fetch('/api/maintenance/cancel', {
                method: 'POST', credentials: 'same-origin'
            }).then(function (r) { return r.json(); })
              .then(function () { fetchStatus(); });
        });
    };

})();
