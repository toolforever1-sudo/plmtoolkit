// ============================================================================
// User Permissions tab — admin + permissions-admin allowlist (Vikas Singh) only.
// Lets you:
//   - See every user (logged in via app + DL members who never logged in)
//   - Edit per-user tab visibility (admin-only tabs are locked, never grantable)
//   - Search org-wide AD for someone NOT yet in the access DL, pick tabs,
//     submit a request to IT to add them to the DL
// Backend: /api/permissions/* (UserPermissionsController)
// ============================================================================

var permsState = {
    tabs: [],          // [{key, label, adminOnly, permissionsAdmin}, ...]
    users: [],         // sorted user rows (logged-in first)
    pending: [],       // pending DL requests
    counts: {},
    editingUser: null, // user currently in the edit modal
    addSelected: null, // AD user picked from typeahead
    typeaheadTimer: null
};

function permsBootstrap() {
    if (permsState.tabs.length) return;
    fetch('/api/permissions/tabs').then(function (r) {
        if (!r.ok) throw new Error('not authorized');
        return r.json();
    }).then(function (tabs) {
        permsState.tabs = tabs;
        permsLoadUsers();
    }).catch(function (e) {
        var el = document.getElementById('permsUserList');
        if (el) el.innerHTML = '<div style="padding:20px; color:#B8342B; text-align:center;">Failed to load tab catalog: ' + (e.message || e) + '</div>';
    });
}

function permsLoadUsers() {
    fetch('/api/permissions/users').then(function (r) { return r.json(); }).then(function (data) {
        permsState.users = data.users || [];
        permsState.pending = data.pendingRequests || [];
        permsState.counts = data.counts || {};
        permsState.me = data.me || {};
        permsState.adAddUrl = data.adAddUrl || '';
        permsState.accessGroupName = data.accessGroupName || '';
        permsRenderCounts();
        permsRenderUserList();
        permsRenderPending();
    });
}

function permsRenderCounts() {
    var c = permsState.counts || {};
    var fields = {
        permsCountLoggedIn: c.loggedIn,
        permsCountDlOnly: c.dlNeverLogged,
        permsCountExplicit: c.explicit,
        permsCountPending: c.pending
    };
    Object.keys(fields).forEach(function (id) {
        var el = document.getElementById(id);
        if (el) el.textContent = (fields[id] == null ? '0' : String(fields[id]));
    });
}

function permsRenderUserList() {
    var el = document.getElementById('permsUserList');
    if (!el) return;
    var filter = (document.getElementById('permsFilter') || {}).value || '';
    filter = filter.trim().toLowerCase();
    var rows = permsState.users || [];
    if (filter) {
        rows = rows.filter(function (u) {
            return (u.username || '').toLowerCase().indexOf(filter) >= 0
                || (u.displayName || '').toLowerCase().indexOf(filter) >= 0
                || (u.email || '').toLowerCase().indexOf(filter) >= 0;
        });
    }
    if (!rows.length) {
        el.innerHTML = '<div style="padding:20px; text-align:center; color:#6B7280;">No users match.</div>';
        return;
    }

    var html = '<table style="width:100%; border-collapse:collapse; font-size:13px;">';
    html += '<thead><tr style="background:#FAFAF7; border-bottom:1px solid #E8E6DF;">'
        + '<th style="text-align:left; padding:10px; font-weight:600; color:#6B7280; font-size:11px; letter-spacing:.05em; text-transform:uppercase;">User</th>'
        + '<th style="text-align:left; padding:10px; font-weight:600; color:#6B7280; font-size:11px; letter-spacing:.05em; text-transform:uppercase;">Status</th>'
        + '<th style="text-align:left; padding:10px; font-weight:600; color:#6B7280; font-size:11px; letter-spacing:.05em; text-transform:uppercase;">Tab access</th>'
        + '<th style="text-align:left; padding:10px; font-weight:600; color:#6B7280; font-size:11px; letter-spacing:.05em; text-transform:uppercase; font-family:\'IBM Plex Mono\',Consolas,monospace;">Roles</th>'
        + '<th style="text-align:right; padding:10px; font-weight:600; color:#6B7280; font-size:11px; letter-spacing:.05em; text-transform:uppercase;"></th>'
        + '</tr></thead><tbody>';

    rows.forEach(function (u, idx) {
        var statusBits = [];
        if (u.isPlmAdmin) {
            statusBits.push('<span style="background:#1F8A4C; color:#fff; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;">PLM ADMIN</span>');
        }
        if (u.hasLoggedIn) {
            var when = (u.lastLoginTs || '').replace('T', ' ');
            statusBits.push('<span title="Last login: ' + permsEsc(when) + ' &bull; ' + (u.loginCount || 0) + ' total" style="background:#e8f5e9; color:#155724; padding:2px 8px; border-radius:10px; font-size:11px;">logged in</span>');
        } else if (u.dlMember) {
            statusBits.push('<span style="background:#fff3cd; color:#856404; padding:2px 8px; border-radius:10px; font-size:11px;">in DL, never logged in</span>');
        } else {
            statusBits.push('<span style="background:#e8f0fe; color:#1a3a5c; padding:2px 8px; border-radius:10px; font-size:11px;">pending DL add</span>');
        }
        var lastLoginLabel = u.hasLoggedIn ? '<span style="font-size:11px; color:#6B7280; margin-left:6px;">' + permsEsc((u.lastLoginTs || '').slice(0, 10)) + '</span>' : '';

        var tabAccess;
        if (u.isPlmAdmin) {
            tabAccess = '<span style="font-size:12px; color:#1F8A4C; font-weight:600;">All tabs (admin)</span>'
                + '<div style="font-size:11px; color:#6B7280; margin-top:2px;">Tab visibility cannot be restricted &mdash; remove from <code>pdl-plm-admin</code> AD group instead.</div>';
        } else if (u.managedExplicitly) {
            var allowed = u.allowedTabs || [];
            tabAccess = '<span style="font-size:12px;"><strong style="color:#7C3AED;">' + allowed.length + '</strong> tab' + (allowed.length === 1 ? '' : 's')
                + ' <span style="color:#6B7280;">(custom)</span></span>';
            if (allowed.length) {
                tabAccess += '<div style="font-size:11px; color:#6B7280; margin-top:2px;">' + allowed.map(permsEsc).join(', ') + '</div>';
            }
        } else {
            tabAccess = '<span style="font-size:12px; color:#6B7280;">All non-admin tabs <span style="font-size:11px;">(default)</span></span>';
        }

        // PLM admins get a clickable name (opens AD profile + activity popover).
        // Permissions-admins see name as plain text — they can manage tab visibility
        // but not drill into per-user activity. Computed up here so the Remove button
        // (admin-only) below can also gate on it.
        var viewerIsAdmin = !!(permsState.me && permsState.me.isPlmAdmin);

        // Admin rows show a disabled "Locked" pill in place of the Edit button.
        // The server also rejects edits to admin users (defense in depth) — this
        // is just to keep the click from being attempted in the first place.
        var actionCell;
        if (u.isPlmAdmin) {
            actionCell = '<span title="Admin tab visibility cannot be edited here. Remove from pdl-plm-admin AD group to revoke admin." style="display:inline-block; border:1px solid #E8E6DF; color:#6B7280; padding:5px 10px; border-radius:4px; font-size:12px; cursor:not-allowed;">&#128274; Locked</span>';
        } else {
            actionCell = '<button onclick="permsOpenEditModal(\'' + permsEsc(u.username) + '\')" style="background:none; border:1px solid #4a6fa5; color:#4a6fa5; padding:5px 10px; border-radius:4px; font-size:12px; cursor:pointer;">Edit</button>';
            // Admin-only Remove button: opens the IT-APP-Agile-admin DL page in a new
            // tab. Pure external link — this tool sends no notification to the user
            // being removed (AD/portal handles removal; user finds out at next login).
            if (viewerIsAdmin) {
                actionCell += ' <a href="https://anywhere.sandisk.com/ad-group-info/IT-APP-Agile-admin" target="_blank" rel="noopener" title="Open the IT-APP-Agile-admin AD group page to remove this user. The user is not notified by this tool." style="display:inline-block; margin-left:6px; background:none; border:1px solid #B8342B; color:#B8342B; padding:5px 10px; border-radius:4px; font-size:12px; cursor:pointer; text-decoration:none;">Remove</a>';
            }
        }

        var nameDisplay = viewerIsAdmin
            ? '<a href="#" onclick="permsOpenUserPopover(\'' + permsEsc(u.username) + '\', \'' + permsEsc((u.displayName || u.username || '').replace(/\u0027/g, "\\u0027")) + '\'); return false;" style="font-weight:600; color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #c8d3e3;" title="Click for AD profile + activity">' + permsEsc(u.displayName || u.username) + '</a>'
            : '<span style="font-weight:600; color:#0F1720;">' + permsEsc(u.displayName || u.username) + '</span>';

        // Roles cell — Engineering Insider. Toggle renders ONLY for PLM IT
        // (canGrantEngineeringInsider); everyone else sees a read-only chip on
        // holders. The server enforces the same rule with a 403 — this gate is
        // cosmetic.
        var canGrantEng = !!(permsState.me && permsState.me.canGrantEngineeringInsider);
        var isEngHolder = !!u.engineeringInsider;
        var engTitle = 'Engineering insider — can ask the help chatbot in-depth questions about how the toolkit is built';
        var rolesCell;
        if (canGrantEng) {
            var swBg = isEngHolder ? '#4a6fa5' : '#e9ecef';
            var knobLeft = isEngHolder ? '15px' : '1px';
            rolesCell = '<button onclick="permsToggleEngInsider(\'' + permsEsc(u.username) + '\', ' + (!isEngHolder) + ', this)" title="' + engTitle + '" '
                + 'style="display:inline-flex; align-items:center; gap:7px; background:none; border:0; cursor:pointer; font-size:12px; color:#555; padding:0;">'
                + '<span style="width:32px; height:18px; border-radius:999px; background:' + swBg + '; border:1px solid ' + (isEngHolder ? '#4a6fa5' : '#d0d5dd') + '; position:relative; display:inline-block; transition:background .12s;">'
                + '<span style="position:absolute; top:1px; left:' + knobLeft + '; width:14px; height:14px; border-radius:999px; background:#fff; box-shadow:0 1px 2px rgba(15,23,32,.25); transition:left .12s;"></span>'
                + '</span><span>Engineering insider</span></button>';
            if (isEngHolder && u.engineeringInsiderChange) {
                rolesCell += '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10px; color:#9aa1a9; margin-top:3px;">'
                    + permsEsc((u.engineeringInsiderChange.at || '').slice(0, 10)) + ' &middot; by ' + permsEsc(u.engineeringInsiderChange.byDisplay || u.engineeringInsiderChange.by || '') + '</div>';
            }
        } else if (isEngHolder) {
            rolesCell = '<span title="Granted by PLM IT only" style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; border:1px solid #E8E6DF; border-radius:10px; padding:2px 8px;">Engineering insider &middot; PLM IT only</span>';
        } else {
            rolesCell = '<span style="color:#c3c9cf;">&mdash;</span>';
        }

        var bg = idx % 2 === 0 ? '#fff' : '#FAFAF7';
        html += '<tr style="background:' + bg + '; border-bottom:1px solid #E8E6DF;">'
            + '<td style="padding:10px; vertical-align:top;">'
                + '<div>' + nameDisplay + '</div>'
                + '<div style="font-size:11px; color:#6B7280;">' + permsEsc(u.username) + (u.email ? ' &bull; ' + permsEsc(u.email) : '') + '</div>'
            + '</td>'
            + '<td style="padding:10px; vertical-align:top; white-space:nowrap;">' + statusBits.join(' ') + lastLoginLabel + '</td>'
            + '<td style="padding:10px; vertical-align:top;">' + tabAccess + '</td>'
            + '<td style="padding:10px; vertical-align:top; white-space:nowrap;">' + rolesCell + '</td>'
            + '<td style="padding:10px; vertical-align:top; text-align:right; white-space:nowrap;">' + actionCell + '</td>'
            + '</tr>';
    });
    html += '</tbody></table>';
    el.innerHTML = html;
}

/** Grant/revoke the Engineering Insider role. PLM IT only — the server 403s
 *  anyone else, so a failed call just reports and re-renders. */
function permsToggleEngInsider(username, grant, btn) {
    if (btn) btn.disabled = true;
    fetch('/api/permissions/roles/engineering-insider', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username, grant: grant })
    })
    .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
    .then(function (res) {
        if (!res.ok || !res.d.success) {
            appAlert(res.d.error || 'Could not update the Engineering Insider role.');
        }
        permsLoadUsers();
    })
    .catch(function () {
        appAlert('Could not update the Engineering Insider role — server unreachable.');
        permsLoadUsers();
    });
}

function permsRenderPending() {
    var pending = permsState.pending || [];
    var panel = document.getElementById('permsPendingPanel');
    var list = document.getElementById('permsPendingList');
    var count = document.getElementById('permsPendingCount');
    if (!panel || !list) return;
    if (!pending.length) { panel.style.display = 'none'; return; }
    panel.style.display = '';
    if (count) count.textContent = '(' + pending.length + ')';

    if (!permsState.pendingSel) permsState.pendingSel = {};
    if (!permsState.pendingChecking) permsState.pendingChecking = {};
    if (!permsState.pendingFailed) permsState.pendingFailed = {};

    var adAddUrl = permsState.adAddUrl || '';
    var groupName = permsState.accessGroupName || 'access DL';

    // Awaiting = not yet invited (no welcomeSentAt). Those are the selectable targets.
    var anyAwaiting = pending.some(function (p) { return !p.welcomeSentAt; });
    document.getElementById('permsPendingSelectAllBar').style.display = anyAwaiting ? '' : 'none';

    var html = '<table style="width:100%; border-collapse:collapse; font-size:12px;"><tbody>';
    pending.forEach(function (p) {
        var sam = p.sAMAccountName;
        var invited = !!p.welcomeSentAt;
        var checking = !!permsState.pendingChecking[sam];
        var failed = !!permsState.pendingFailed[sam];
        var selected = !!permsState.pendingSel[sam];

        var pill, actions = '', checkbox = '';
        if (invited) {
            pill = '<span style="background:var(--good-bg,#d4edda); color:var(--good-ink,#155724); padding:2px 8px; border-radius:10px; font-size:11px;">Invite sent</span>';
            actions = '<button onclick="permsSendInvites([\'' + permsEsc(sam) + '\'])" title="Last sent: ' + permsEsc(p.welcomeSentAt || '') + (p.welcomeSentByDisplay ? ' by ' + permsEsc(p.welcomeSentByDisplay) : '') + '"'
                + ' style="background:none; border:1px solid var(--line-2,#1F8A4C); color:#1F8A4C; padding:4px 10px; border-radius:6px; font-size:11px; font-weight:600; cursor:pointer; white-space:nowrap;">Resend invite</button>';
        } else if (checking) {
            pill = '<span style="background:var(--accent-2,#e8f0fe); color:var(--accent-ink,#1a3a5c); padding:2px 8px; border-radius:10px; font-size:11px;">Checking DL…</span>';
        } else if (failed) {
            pill = '<span style="background:#fdeaea; color:#B8342B; padding:2px 8px; border-radius:10px; font-size:11px;">Not in DL yet</span>';
            checkbox = '<input type="checkbox" ' + (selected ? 'checked ' : '') + 'onclick="permsTogglePendingSel(\'' + permsEsc(sam) + '\')" style="margin-right:6px;">';
            actions = permsAwaitingActions(sam, adAddUrl, groupName);
        } else {
            pill = '<span style="background:var(--warn-bg,#fff3cd); color:#856404; padding:2px 8px; border-radius:10px; font-size:11px;">Awaiting DL add</span>';
            checkbox = '<input type="checkbox" ' + (selected ? 'checked ' : '') + 'onclick="permsTogglePendingSel(\'' + permsEsc(sam) + '\')" style="margin-right:6px;">';
            actions = permsAwaitingActions(sam, adAddUrl, groupName);
        }

        var tabsLabel = (p.requestedTabs || []).map(permsEsc).join(', ') || '(none)';
        html += '<tr style="border-bottom:1px solid var(--line,#E8E6DF);">'
            + '<td style="padding:8px; white-space:nowrap;">' + checkbox + '<strong>' + permsEsc(p.displayName || sam) + '</strong> '
                + '<span style="color:#6B7280; font-size:11px;">' + permsEsc(sam) + '</span></td>'
            + '<td style="padding:8px;">' + pill + '</td>'
            + '<td style="padding:8px; color:#6B7280; font-size:11px;">' + permsEsc(p.requestedAt || '') + '<br>by ' + permsEsc(p.requestedByDisplay || '') + '</td>'
            + '<td style="padding:8px; color:#6B7280; font-size:11px;">tabs: ' + tabsLabel + '</td>'
            + '<td style="padding:8px; text-align:right; white-space:nowrap;">' + actions + '</td>'
            + '</tr>';
    });
    html += '</tbody></table>';
    list.innerHTML = html;
    permsUpdateSelCount();
}

function permsAwaitingActions(sam, adAddUrl, groupName) {
    var addLink = adAddUrl
        ? '<a href="' + permsEsc(adAddUrl) + '" target="_blank" rel="noopener" title="Open AD self-service to add ' + permsEsc(sam) + ' to ' + permsEsc(groupName) + '" style="font-size:11px; color:var(--accent); margin-right:10px;">Add to DL ↗</a>'
        : '';
    var sendBtn = '<button onclick="permsSendInvites([\'' + permsEsc(sam) + '\'])" style="background:var(--accent); color:#fff; border:none; padding:4px 10px; border-radius:6px; font-size:11px; font-weight:600; cursor:pointer; white-space:nowrap;">Send invite</button>';
    return addLink + sendBtn;
}

function permsTogglePendingSel(sam) {
    if (!permsState.pendingSel) permsState.pendingSel = {};
    if (permsState.pendingSel[sam]) delete permsState.pendingSel[sam];
    else permsState.pendingSel[sam] = true;
    permsUpdateSelCount();
}

function permsAwaitingUsernames() {
    return (permsState.pending || []).filter(function (p) { return !p.welcomeSentAt; })
        .map(function (p) { return p.sAMAccountName; });
}

function permsToggleSelectAllPending(cb) {
    if (!permsState.pendingSel) permsState.pendingSel = {};
    var targets = permsAwaitingUsernames();
    if (cb.checked) targets.forEach(function (u) { permsState.pendingSel[u] = true; });
    else permsState.pendingSel = {};
    permsRenderPending();
}

function permsUpdateSelCount() {
    var n = Object.keys(permsState.pendingSel || {}).length;
    var el = document.getElementById('permsSelCount');
    if (el) el.textContent = n;
    var btn = document.getElementById('permsSendSelectedBtn');
    if (btn) btn.disabled = n === 0;
    var allBtn = document.getElementById('permsSendAllBtn');
    if (allBtn) allBtn.disabled = permsAwaitingUsernames().length === 0;
}

function permsSendSelected() {
    var us = Object.keys(permsState.pendingSel || {});
    if (us.length) permsSendInvites(us);
}

function permsSendAll() {
    var us = permsAwaitingUsernames();
    if (us.length) permsSendInvites(us);
}

function permsShowPendingBanner(kind, html) {
    var b = document.getElementById('permsPendingBanner');
    if (!b) return;
    var bg = kind === 'success' ? 'var(--good-bg,#e8f5e9)' : kind === 'warn' ? 'var(--warn-bg,#fff8e1)' : '#fdeaea';
    var fg = kind === 'success' ? 'var(--good-ink,#1F8A4C)' : kind === 'warn' ? '#856404' : '#B8342B';
    b.style.display = '';
    b.style.background = bg;
    b.style.color = fg;
    b.innerHTML = html;
}

function permsSendInvites(usernames) {
    if (!usernames || !usernames.length) return;
    if (!permsState.pendingChecking) permsState.pendingChecking = {};
    if (!permsState.pendingFailed) permsState.pendingFailed = {};
    usernames.forEach(function (u) { permsState.pendingChecking[u] = true; delete permsState.pendingFailed[u]; });
    permsRenderPending();
    fetch('/api/permissions/dl-requests/send-invites', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ usernames: usernames })
    }).then(function (r) { return r.json(); })
      .then(function (resp) {
          usernames.forEach(function (u) { delete permsState.pendingChecking[u]; });
          if (!resp.success) { permsShowPendingBanner('error', 'Send failed: ' + permsEsc(resp.error || 'unknown')); permsRenderPending(); return; }
          var sent = resp.sent || [], failed = resp.failed || [];
          var notInDl = failed.filter(function (f) { return f.reason === 'NOT_IN_DL'; });
          var otherFail = failed.filter(function (f) { return f.reason !== 'NOT_IN_DL'; });
          // Persist NOT_IN_DL as a red retryable state; clear selection for handled rows.
          failed.forEach(function (f) { if (f.reason === 'NOT_IN_DL') permsState.pendingFailed[f.username] = true; });
          sent.forEach(function (s) { delete permsState.pendingFailed[s.username]; delete permsState.pendingSel[s.username]; });
          notInDl.forEach(function (f) { delete permsState.pendingSel[f.username]; });

          var names = function (arr) { return arr.map(function (x) { return permsEsc(x.displayName || x.username); }).join(', '); };
          if (failed.length === 0) {
              permsShowPendingBanner('success', '✓ ' + sent.length + ' invite' + (sent.length === 1 ? '' : 's') + ' sent.');
          } else if (sent.length === 0 && otherFail.length === 0) {
              permsShowPendingBanner('warn', '⚠ ' + names(notInDl) + ' not in the ' + permsEsc(permsState.accessGroupName || 'access') + ' DL yet. Add them, then try Send again. No email was sent.');
          } else {
              var parts = [];
              if (sent.length) parts.push('✓ ' + sent.length + ' sent');
              if (notInDl.length) parts.push('⚠ not in DL yet: ' + names(notInDl) + ' — add and retry, no email sent');
              if (otherFail.length) parts.push('✕ failed: ' + names(otherFail));
              permsShowPendingBanner('warn', parts.join(' · '));
          }
          permsLoadUsers();   // refresh server truth (sent rows now show "Invite sent")
      })
      .catch(function (err) {
          usernames.forEach(function (u) { delete permsState.pendingChecking[u]; });
          permsShowPendingBanner('error', 'Send failed: ' + permsEsc(String(err)));
          permsRenderPending();
      });
}

function permsClearCompleted() {
    appConfirm('Remove all completed DL requests from the list?', { okText: 'Remove' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/permissions/clear-completed', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function () { permsLoadUsers(); });
    });
}

// ---------------------------------------------------------------------------
// Edit modal
// ---------------------------------------------------------------------------

function permsOpenEditModal(username) {
    var u = (permsState.users || []).find(function (x) { return x.username === username; });
    if (!u) { appAlert('User not in current list — refresh and try again.'); return; }
    if (u.isPlmAdmin) {
        // The list view shows a "Locked" pill in place of the Edit button for
        // admins, so this branch is reachable only via stale state or someone
        // calling the function from the console. Either way, refuse cleanly.
        appAlert('Cannot edit a PLM admin\u0027s tabs from here.\nAdmins always see every tab. Remove them from the pdl-plm-admin AD group to revoke admin access.');
        return;
    }
    permsState.editingUser = u;
    document.getElementById('permsEditTitle').textContent = u.displayName || u.username;
    document.getElementById('permsEditSubtitle').textContent = u.username + (u.email ? ' \u00b7 ' + u.email : '');

    var adminBanner = document.getElementById('permsEditAdminBanner');
    adminBanner.style.display = 'none';

    var saveBtn = document.getElementById('permsSaveBtn');
    if (saveBtn) { saveBtn.disabled = false; saveBtn.textContent = 'Save'; }

    permsRenderEditTabs(u.allowedTabs || null, u.managedExplicitly);
    document.getElementById('permsEditModal').style.display = 'flex';
}

function permsRenderEditTabs(currentAllowed, managedExplicitly) {
    var listEl = document.getElementById('permsEditTabList');
    if (!listEl) return;
    var allowedSet = {};
    if (managedExplicitly && currentAllowed) {
        currentAllowed.forEach(function (k) { allowedSet[k] = true; });
    }
    var html = '';
    permsState.tabs.forEach(function (t) {
        var checked = managedExplicitly ? !!allowedSet[t.key] : !t.adminOnly && !t.permissionsAdmin;
        var locked = t.adminOnly || t.permissionsAdmin;
        var lockNote = '';
        if (t.adminOnly) lockNote = '<span style="color:#B8342B; font-size:11px; margin-left:6px;">&#128274; admin only</span>';
        else if (t.permissionsAdmin) lockNote = '<span style="color:#B8342B; font-size:11px; margin-left:6px;">&#128274; permissions admin only</span>';
        html += '<label style="display:flex; align-items:center; padding:6px 4px; border-bottom:1px solid #FAFAF7;' + (locked ? ' opacity:0.55;' : ' cursor:pointer;') + '">'
            + '<input type="checkbox" data-tab-key="' + permsEsc(t.key) + '" ' + (checked ? 'checked ' : '') + (locked ? 'disabled ' : '') + 'style="margin-right:10px;">'
            + '<span style="font-size:13px;">' + permsEsc(t.label) + '</span>'
            + lockNote
            + '</label>';
    });
    if (!managedExplicitly) {
        html = '<div style="background:#FAFAF7; border-left:3px solid #6B7280; padding:8px 12px; margin-bottom:10px; font-size:12px; color:#6B7280;">'
            + 'This user has the default tab set. Click any non-admin tab below to start managing them explicitly.'
            + '</div>' + html;
    }
    listEl.innerHTML = html;
}

function permsCloseEdit() {
    document.getElementById('permsEditModal').style.display = 'none';
    permsState.editingUser = null;
}

function permsSaveUser() {
    var u = permsState.editingUser;
    if (!u) return;
    var checks = document.querySelectorAll('#permsEditTabList input[type="checkbox"]');
    var allowed = [];
    checks.forEach(function (cb) {
        if (cb.checked && !cb.disabled) allowed.push(cb.getAttribute('data-tab-key'));
    });
    var btn = document.getElementById('permsSaveBtn');
    btn.disabled = true; btn.textContent = 'Saving...';
    fetch('/api/permissions/user', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            sAMAccountName: u.username,
            displayName: u.displayName,
            email: u.email,
            allowedTabs: allowed
        })
    }).then(function (r) { return r.json(); })
      .then(function (resp) {
          btn.disabled = false; btn.textContent = 'Save';
          if (!resp.success) { appAlert('Save failed: ' + (resp.error || 'unknown')); return; }
          permsCloseEdit();
          permsLoadUsers();
      })
      .catch(function (e) {
          btn.disabled = false; btn.textContent = 'Save';
          appAlert('Save failed: ' + e);
      });
}

function permsResetUser() {
    var u = permsState.editingUser;
    if (!u) return;
    appConfirm('Reset ' + (u.displayName || u.username) + ' to default (sees all non-admin tabs)?', { okText: 'Reset' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/permissions/user/' + encodeURIComponent(u.username), { method: 'DELETE' })
            .then(function (r) { return r.json(); })
            .then(function () { permsCloseEdit(); permsLoadUsers(); });
    });
}

// ---------------------------------------------------------------------------
// Add users from AD modal (multi-select + preset tab grants)
// ---------------------------------------------------------------------------

function permsOpenAddModal() {
    permsState.add = { selectedUsers: [], granted: {}, activePreset: 'viewer', catalog: null, results: [] };
    document.getElementById('permsAddSearch').value = '';
    document.getElementById('permsAddResults').style.display = 'none';
    document.getElementById('permsAddResults').innerHTML = '';
    document.getElementById('permsAddModal').style.display = 'flex';
    permsRenderSelectedUsers();
    // Load the rich catalog (tabs+groups+presets), then apply the default preset.
    fetch('/api/permissions/tab-catalog').then(function (r) { return r.json(); }).then(function (cat) {
        permsState.add.catalog = cat;
        document.getElementById('permsAddTabTotal').textContent = (cat.tabs || []).filter(function (t) { return t.grantable; }).length;
        permsApplyPreset('viewer');
        permsRenderPresets();
    }).catch(function (e) {
        document.getElementById('permsAddTabGroups').innerHTML = '<div style="padding:12px; color:#B8342B; font-size:12px;">Failed to load tab catalog: ' + permsEsc(String(e)) + '</div>';
    });
    setTimeout(function () { document.getElementById('permsAddSearch').focus(); }, 50);
}

function permsCloseAdd() {
    document.getElementById('permsAddModal').style.display = 'none';
    permsState.add = null;
}

function permsTypeahead(query) {
    if (permsState.typeaheadTimer) clearTimeout(permsState.typeaheadTimer);
    var resultsEl = document.getElementById('permsAddResults');
    if (!query || query.trim().length < 3) { resultsEl.style.display = 'none'; resultsEl.innerHTML = ''; return; }
    resultsEl.style.display = 'block';
    resultsEl.innerHTML = '<div style="padding:12px; text-align:center; color:#6B7280; font-size:12px;">Searching AD&hellip;</div>';
    permsState.typeaheadTimer = setTimeout(function () {
        fetch('/api/permissions/ad-search?q=' + encodeURIComponent(query.trim()))
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                if (!permsState.add) return;
                permsState.add.results = resp.results || [];
                permsRenderResults();
            })
            .catch(function (e) {
                resultsEl.innerHTML = '<div style="padding:12px; color:#B8342B; font-size:12px;">Search failed: ' + permsEsc(String(e)) + '</div>';
            });
    }, 300);
}

function permsRenderResults() {
    if (!permsState.add) return;
    var resultsEl = document.getElementById('permsAddResults');
    var rows = permsState.add.results || [];
    var selected = {};
    permsState.add.selectedUsers.forEach(function (u) { selected[u.sAMAccountName] = true; });
    if (!rows.length) { resultsEl.innerHTML = '<div style="padding:12px; text-align:center; color:#6B7280; font-size:12px;">No matches.</div>'; return; }
    var html = '';
    rows.forEach(function (r, i) {
        var added = selected[r.sAMAccountName];
        var dl = r.alreadyInDL
            ? '<span style="background:var(--good-bg); color:var(--good-ink); padding:1px 7px; border-radius:8px; font-size:10px; margin-left:6px;">In access DL</span>'
            : '';
        html += '<div ' + (added ? '' : 'onclick="permsAddPickUser(' + i + ')" ') + 'style="padding:8px 12px; border-bottom:1px solid var(--surface-2); ' + (added ? 'opacity:0.5;' : 'cursor:pointer;') + '" '
            + (added ? '' : 'onmouseover="this.style.background=\'var(--surface-2)\'" onmouseout="this.style.background=\'transparent\'"') + '>'
            + '<div style="font-size:13px; color:#0F1720; font-weight:600;">' + permsEsc(r.displayName || r.sAMAccountName) + dl + (added ? '<span style="font-size:10px; color:#6B7280; margin-left:6px;">Added</span>' : '') + '</div>'
            + '<div style="font-size:11px; color:#6B7280; font-family:var(--font-mono);">' + permsEsc(r.sAMAccountName) + (r.email ? ' &bull; ' + permsEsc(r.email) : '') + '</div>'
            + '</div>';
    });
    resultsEl.innerHTML = html;
}

function permsAddPickUser(idx) {
    if (!permsState.add) return;
    var r = (permsState.add.results || [])[idx];
    if (!r) return;
    if (permsState.add.selectedUsers.some(function (u) { return u.sAMAccountName === r.sAMAccountName; })) return;
    permsState.add.selectedUsers.push({ sAMAccountName: r.sAMAccountName, displayName: r.displayName, email: r.email, alreadyInDL: !!r.alreadyInDL });
    var input = document.getElementById('permsAddSearch');
    input.value = '';
    document.getElementById('permsAddResults').style.display = 'none';
    document.getElementById('permsAddResults').innerHTML = '';
    permsState.add.results = [];
    permsRenderSelectedUsers();
    input.focus();
}

function permsAddRemoveUser(sam) {
    if (!permsState.add) return;
    permsState.add.selectedUsers = permsState.add.selectedUsers.filter(function (u) { return u.sAMAccountName !== sam; });
    permsRenderSelectedUsers();
}

function permsAddClearUsers() {
    permsState.add.selectedUsers = [];
    permsRenderSelectedUsers();
}

function permsInitials(name) {
    var parts = (name || '?').trim().split(/\s+/);
    var a = parts[0] ? parts[0].charAt(0) : '?';
    var b = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (a + b).toUpperCase();
}

function permsRenderSelectedUsers() {
    if (!permsState.add) return;
    var listEl = document.getElementById('permsAddSelectedList');
    var users = permsState.add.selectedUsers;
    document.getElementById('permsAddCount').textContent = users.length;
    document.getElementById('permsAddClearAll').style.display = users.length ? '' : 'none';
    if (!users.length) {
        listEl.innerHTML = '<div style="padding:16px; text-align:center; color:#6B7280; font-size:12px;">No users yet &mdash; search above and click people to add them.</div>';
    } else {
        var html = '';
        users.forEach(function (u) {
            var pill = u.alreadyInDL
                ? '<span style="background:var(--good-bg); color:var(--good-ink); padding:1px 8px; border-radius:8px; font-size:10px; white-space:nowrap;">In access DL</span>'
                : '<span style="background:var(--warn-bg); color:#7a5200; padding:1px 8px; border-radius:8px; font-size:10px; white-space:nowrap;">Email IT</span>';
            html += '<div style="display:flex; align-items:center; gap:10px; padding:8px 12px; border-bottom:1px solid var(--surface-2);">'
                + '<div style="width:28px; height:28px; border-radius:50%; background:var(--accent-2); color:var(--accent-ink); font-size:11px; font-weight:600; display:flex; align-items:center; justify-content:center; flex-shrink:0;">' + permsEsc(permsInitials(u.displayName)) + '</div>'
                + '<div style="flex:1; min-width:0;"><div style="font-size:13px; font-weight:600; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">' + permsEsc(u.displayName || u.sAMAccountName) + '</div>'
                + '<div style="font-size:11px; color:#6B7280; font-family:var(--font-mono); white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">' + permsEsc(u.sAMAccountName) + (u.email ? ' &middot; ' + permsEsc(u.email) : '') + '</div></div>'
                + pill
                + '<button onclick="permsAddRemoveUser(\'' + permsEsc(u.sAMAccountName) + '\')" title="Remove" style="background:none; border:none; color:#6B7280; font-size:16px; cursor:pointer; line-height:1; flex-shrink:0;">&times;</button>'
                + '</div>';
        });
        listEl.innerHTML = html;
    }
    permsRenderAddFooter();
}

function permsGrantedKeys() {
    var g = permsState.add.granted || {};
    return Object.keys(g).filter(function (k) { return g[k]; });
}

function permsApplyPreset(id) {
    if (!permsState.add) return;
    var cat = permsState.add.catalog;
    if (!cat) return;
    var preset = (cat.presets || []).filter(function (p) { return p.id === id; })[0];
    if (!preset) return;
    var g = {};
    (preset.tabKeys || []).forEach(function (k) { g[k] = true; });
    permsState.add.granted = g;
    permsState.add.activePreset = id;
    permsRenderTabGroups();
    permsRenderPresets();
    permsRenderAddFooter();
}

function permsToggleTab(key) {
    if (!permsState.add) return;
    var g = permsState.add.granted;
    g[key] = !g[key];
    permsState.add.activePreset = null;   // hand-edit => custom
    permsRenderTabGroups();
    permsRenderPresets();
    permsRenderAddFooter();
}

function permsToggleGroup(group, selectAll) {
    if (!permsState.add) return;
    var cat = permsState.add.catalog;
    (cat.tabs || []).forEach(function (t) {
        if (t.grantable && t.group === group) permsState.add.granted[t.key] = selectAll;
    });
    permsState.add.activePreset = null;
    permsRenderTabGroups();
    permsRenderPresets();
    permsRenderAddFooter();
}

function permsRenderPresets() {
    if (!permsState.add) return;
    var cat = permsState.add.catalog;
    if (!cat) return;
    var el = document.getElementById('permsAddPresets');
    var active = permsState.add.activePreset;
    var html = '';
    (cat.presets || []).forEach(function (p) {
        var on = active === p.id;
        html += '<button onclick="permsApplyPreset(\'' + permsEsc(p.id) + '\')" '
            + 'style="padding:5px 12px; border-radius:14px; font-size:12px; cursor:pointer; '
            + (on ? 'background:var(--accent); color:#fff; border:1px solid var(--accent);' : 'background:var(--surface); color:#0F1720; border:1px solid var(--line-2);') + '">'
            + permsEsc(p.label) + '</button>';
    });
    if (!active) {
        html += '<span style="padding:5px 10px; font-size:12px; color:#6B7280;">Custom</span>';
    }
    el.innerHTML = html;
}

function permsRenderTabGroups() {
    if (!permsState.add) return;
    var cat = permsState.add.catalog;
    if (!cat) return;
    var g = permsState.add.granted;
    var html = '';
    (cat.groups || []).forEach(function (group) {
        var tabs = (cat.tabs || []).filter(function (t) { return t.grantable && t.group === group; });
        if (!tabs.length) return;
        var sel = tabs.filter(function (t) { return g[t.key]; }).length;
        var allOn = sel === tabs.length;
        html += '<div style="padding:8px 2px; border-bottom:1px solid var(--surface-2);">'
            + '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">'
            + '<span style="font-size:12px; font-weight:600; color:#0F1720;">' + permsEsc(group) + ' <span style="color:#6B7280; font-weight:400;">' + sel + '/' + tabs.length + '</span></span>'
            + '<a href="javascript:void(0)" onclick="permsToggleGroup(\'' + permsEsc(group) + '\', ' + (allOn ? 'false' : 'true') + ')" style="font-size:11px; color:var(--accent);">' + (allOn ? 'Clear group' : 'Select all') + '</a>'
            + '</div>';
        tabs.forEach(function (t) {
            var on = !!g[t.key];
            html += '<label style="display:flex; align-items:center; gap:9px; padding:4px 2px; cursor:pointer;">'
                + '<span onclick="permsToggleTab(\'' + permsEsc(t.key) + '\')" style="width:16px; height:16px; border-radius:4px; flex-shrink:0; display:inline-flex; align-items:center; justify-content:center; '
                + (on ? 'background:var(--accent); border:1px solid var(--accent); color:#fff;' : 'background:var(--surface); border:1px solid var(--line-2); color:transparent;') + ' font-size:11px;">&#10003;</span>'
                + '<span onclick="permsToggleTab(\'' + permsEsc(t.key) + '\')" style="font-size:13px;">' + permsEsc(t.label) + '</span>'
                + '</label>';
        });
        html += '</div>';
    });
    document.getElementById('permsAddTabGroups').innerHTML = html;
    document.getElementById('permsAddTabCount').textContent = permsGrantedKeys().length;
}

function permsRenderAddFooter() {
    if (!permsState.add) return;
    var n = permsState.add.selectedUsers.length;
    var tabsN = permsGrantedKeys().length;
    var emailN = permsState.add.selectedUsers.filter(function (u) { return !u.alreadyInDL; }).length;
    var summary = n === 0
        ? ''
        : n + ' user' + (n === 1 ? '' : 's') + ' &middot; ' + tabsN + ' tab' + (tabsN === 1 ? '' : 's') + ' each &middot; '
          + emailN + ' will be emailed to IT to add to the access DL';
    document.getElementById('permsAddSummary').innerHTML = summary;
    document.getElementById('permsAddSubmitCount').textContent = n;
    document.getElementById('permsAddSubmitBtn').disabled = n === 0;
}

function permsAddSubmit() {
    if (!permsState.add) return;
    var users = permsState.add.selectedUsers;
    if (!users.length) { appAlert('Add at least one user first.'); return; }
    var allowed = permsGrantedKeys();
    var emailN = users.filter(function (u) { return !u.alreadyInDL; }).length;
    appConfirm('Submit ' + users.length + ' user' + (users.length === 1 ? '' : 's') + ' with ' + allowed.length + ' tab' + (allowed.length === 1 ? '' : 's') + ' each? ' + emailN + ' will be emailed to IT.', { okText: 'Submit' }).then(function (ok) {
        if (!ok) return;
        var btn = document.getElementById('permsAddSubmitBtn');
        btn.disabled = true;
        var payloadUsers = users.map(function (u) { return { sAMAccountName: u.sAMAccountName, displayName: u.displayName, email: u.email }; });
        fetch('/api/permissions/request-add-bulk', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ users: payloadUsers, allowedTabs: allowed })
        }).then(function (r) { return r.json(); })
          .then(function (resp) {
              if (!resp.success) { btn.disabled = false; appAlert('Submit failed: ' + (resp.error || 'unknown')); return; }
              permsCloseAdd();
              permsLoadUsers();
              appAlert('Added ' + resp.ok + ' user' + (resp.ok === 1 ? '' : 's') + '. ' + resp.emailed + ' emailed to IT to add to the access DL'
                  + (resp.failed ? '. ' + resp.failed + ' failed.' : '.'));
          })
          .catch(function (e) { btn.disabled = false; appAlert('Submit failed: ' + e); });
    });
}

// ---------------------------------------------------------------------------
// Import users from Excel
// ---------------------------------------------------------------------------

function permsOpenImport() {
    permsState.import = { columns: [], allRows: [], preview: [] };
    document.getElementById('permsImportFile').value = '';
    document.getElementById('permsImportUploadMsg').innerHTML = '';
    document.getElementById('permsImportStepUpload').style.display = '';
    document.getElementById('permsImportStepMapping').style.display = 'none';
    document.getElementById('permsImportStepPreview').style.display = 'none';
    document.getElementById('permsImportModal').style.display = 'flex';
}

function permsCloseImport() {
    document.getElementById('permsImportModal').style.display = 'none';
    permsState.import = null;
}

function permsImportUpload(inputEl) {
    if (!inputEl.files || !inputEl.files.length) return;
    var msg = document.getElementById('permsImportUploadMsg');
    msg.innerHTML = '<span style="color:#6B7280;">Reading & mapping columns…</span>';
    var fd = new FormData();
    fd.append('file', inputEl.files[0]);
    fetch('/api/permissions/import/analyze', { method: 'POST', body: fd })
        .then(function (r) { return r.json(); })
        .then(function (resp) {
            if (!resp.success) { msg.innerHTML = '<span style="color:#B8342B;">' + permsEsc(resp.error || 'Failed to read file.') + '</span>'; return; }
            permsState.import.columns = resp.columns || [];
            permsState.import.allRows = resp.allRows || [];
            permsState.import.mapping = resp.mapping || { nameColumn: -1, emailColumn: -1 };
            permsState.import.rows = resp.rows || [];
            if (resp.confident) {
                permsImportResolve(permsState.import.rows);
            } else {
                permsImportShowMapping(resp.mappingQuestion);
            }
        })
        .catch(function (e) { msg.innerHTML = '<span style="color:#B8342B;">Upload failed: ' + permsEsc(String(e)) + '</span>'; });
}

function permsImportShowMapping(question) {
    document.getElementById('permsImportStepUpload').style.display = 'none';
    document.getElementById('permsImportStepMapping').style.display = '';
    document.getElementById('permsImportMappingQ').textContent =
        question || 'Please confirm which columns hold the name and email.';
    var cols = permsState.import.columns;
    var nameSel = document.getElementById('permsImportNameCol');
    var emailSel = document.getElementById('permsImportEmailCol');
    var opts = '';
    cols.forEach(function (c, i) { opts += '<option value="' + i + '">' + permsEsc(c || ('Column ' + (i + 1))) + '</option>'; });
    nameSel.innerHTML = opts;
    emailSel.innerHTML = '<option value="-1">(none)</option>' + opts;
    if (permsState.import.mapping.nameColumn >= 0) nameSel.value = String(permsState.import.mapping.nameColumn);
    emailSel.value = String(permsState.import.mapping.emailColumn);
}

function permsImportApplyMapping() {
    var nameCol = parseInt(document.getElementById('permsImportNameCol').value, 10);
    var emailCol = parseInt(document.getElementById('permsImportEmailCol').value, 10);
    var rows = permsState.import.allRows.map(function (r) {
        return {
            name: (nameCol >= 0 && r[nameCol] != null) ? String(r[nameCol]).trim() : '',
            email: (emailCol >= 0 && r[emailCol] != null) ? String(r[emailCol]).trim() : ''
        };
    });
    permsImportResolve(rows);
}

function permsImportResolve(rows) {
    document.getElementById('permsImportStepUpload').style.display = 'none';
    document.getElementById('permsImportStepMapping').style.display = 'none';
    document.getElementById('permsImportStepPreview').style.display = '';
    document.getElementById('permsImportSummary').innerHTML = '<span style="color:#6B7280;">Matching ' + rows.length + ' rows against AD…</span>';
    document.getElementById('permsImportRows').innerHTML = '';
    fetch('/api/permissions/import/resolve', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rows: rows })
    }).then(function (r) { return r.json(); })
      .then(function (resp) {
          if (!resp.success) { document.getElementById('permsImportSummary').innerHTML = '<span style="color:#B8342B;">' + permsEsc(resp.error || 'Resolve failed.') + '</span>'; return; }
          permsState.import.preview = resp.rows || [];
          permsImportRenderPreview(resp.summary || {});
          permsImportRenderTabList();
      })
      .catch(function (e) { document.getElementById('permsImportSummary').innerHTML = '<span style="color:#B8342B;">Resolve failed: ' + permsEsc(String(e)) + '</span>'; });
}

function permsImportRenderPreview(summary) {
    permsState.import.summary = summary;
    var s = summary || {};
    var summaryHtml =
        '<strong>' + (s.matched || 0) + '</strong> ready &middot; '
        + '<span style="color:#C7801B;">' + (s.ambiguous || 0) + ' need a pick</span> &middot; '
        + '<span style="color:#B8342B;">' + (s.nomatch || 0) + ' no match</span> &middot; '
        + '<span style="color:#155724;">' + (s.alreadyAccess || 0) + ' already have access</span>';
    if (s.blank) {
        summaryHtml += ' &middot; <span style="color:#6B7280;">' + s.blank + ' blank</span>';
    }
    document.getElementById('permsImportSummary').innerHTML = summaryHtml;
    var html = '';
    permsState.import.preview.forEach(function (row, idx) {
        var badge, tip = '';
        if (row.status === 'matched') badge = '<span style="background:#e8f5e9; color:#1F8A4C; padding:1px 8px; border-radius:8px; font-size:11px;">match</span>';
        else if (row.status === 'already-access') badge = '<span style="background:#e8f0fe; color:#1a3a5c; padding:1px 8px; border-radius:8px; font-size:11px;">already has access</span>';
        else if (row.status === 'ambiguous') badge = '<span style="background:#fff3cd; color:#856404; padding:1px 8px; border-radius:8px; font-size:11px;">pick one</span>';
        else if (row.status === 'blank') badge = '<span style="background:#e9ecef; color:#6B7280; padding:1px 8px; border-radius:8px; font-size:11px;">empty row</span>';
        else badge = '<span style="background:#f8d7da; color:#721c24; padding:1px 8px; border-radius:8px; font-size:11px;">no match</span>';
        if (row.message) tip = '<div style="font-size:11px; color:#6B7280; margin-top:2px;">' + permsEsc(row.message) + '</div>';

        var matchLine = '';
        if (row.match) matchLine = '<div style="font-size:11px; color:#4a6fa5;">&rarr; ' + permsEsc(row.match.displayName || '') + ' (' + permsEsc(row.match.sAMAccountName || '') + ')</div>';
        else if (row.status === 'ambiguous') {
            var opts = row.candidates.map(function (c, ci) { return '<option value="' + ci + '">' + permsEsc((c.displayName || '') + ' — ' + (c.sAMAccountName || '')) + '</option>'; }).join('');
            matchLine = '<select onchange="permsImportPickCandidate(' + idx + ', this.value)" style="margin-top:4px; padding:4px; border:1px solid #E8E6DF; border-radius:6px; font-size:12px;">'
                + '<option value="-1">— choose —</option>' + opts + '</select>';
        }

        html += '<div style="padding:8px 12px; border-bottom:1px solid #FAFAF7; display:flex; justify-content:space-between; gap:10px;">'
            + '<div><div style="font-size:13px; font-weight:600;">' + permsEsc(row.name || '(no name)') + '</div>'
            + '<div style="font-size:11px; color:#6B7280;">' + permsEsc(row.email || '') + '</div>' + matchLine + tip + '</div>'
            + '<div style="white-space:nowrap;">' + badge + '</div></div>';
    });
    document.getElementById('permsImportRows').innerHTML = html;
    permsImportUpdateCount();
}

function permsImportPickCandidate(idx, ci) {
    var row = permsState.import.preview[idx];
    ci = parseInt(ci, 10);
    if (!row || ci < 0 || !row.candidates[ci]) { row.match = null; row.status = 'ambiguous'; }
    else { row.match = row.candidates[ci]; row.status = 'matched'; }
    permsImportRenderPreview(permsState.import.summary || {});
}

function permsImportRenderTabList() {
    var listEl = document.getElementById('permsImportTabList');
    if (!listEl) return;
    var html = '';
    permsState.tabs.forEach(function (t) {
        var locked = t.adminOnly || t.permissionsAdmin;
        var defaultCheck = !locked && t.key !== 'aieval' && t.key !== 'extensions';
        var lockNote = '';
        if (t.adminOnly) lockNote = '<span style="color:#B8342B; font-size:11px; margin-left:6px;">&#128274; admin only</span>';
        else if (t.permissionsAdmin) lockNote = '<span style="color:#B8342B; font-size:11px; margin-left:6px;">&#128274; permissions admin only</span>';
        html += '<label style="display:flex; align-items:center; padding:6px 4px; border-bottom:1px solid #FAFAF7;' + (locked ? ' opacity:0.55;' : ' cursor:pointer;') + '">'
            + '<input type="checkbox" data-tab-key="' + permsEsc(t.key) + '" ' + (defaultCheck ? 'checked ' : '') + (locked ? 'disabled ' : '') + 'style="margin-right:10px;">'
            + '<span style="font-size:13px;">' + permsEsc(t.label) + '</span>' + lockNote + '</label>';
    });
    listEl.innerHTML = html;
}

function permsImportIncludedRows() {
    return (permsState.import.preview || []).filter(function (row) {
        return row.status === 'matched' && row.match && row.match.sAMAccountName && row.match.email;
    });
}

function permsImportUpdateCount() {
    var n = permsImportIncludedRows().length;
    var el = document.getElementById('permsImportSubmitCount');
    if (el) el.textContent = n + (n === 1 ? ' user' : ' users');
    var btn = document.getElementById('permsImportSubmitBtn');
    if (btn) btn.disabled = n === 0;
}

function permsImportSubmit() {
    var included = permsImportIncludedRows();
    if (!included.length) { appAlert('No matched users with an email to submit.'); return; }
    var checks = document.querySelectorAll('#permsImportTabList input[type="checkbox"]');
    var allowed = [];
    checks.forEach(function (cb) { if (cb.checked && !cb.disabled) allowed.push(cb.getAttribute('data-tab-key')); });

    appConfirm('Submit ' + included.length + ' user' + (included.length === 1 ? '' : 's') + ' to IT for access? One consolidated email will be sent.', { okText: 'Submit' }).then(function (ok) {
        if (!ok) return;
        var users = included.map(function (row) {
            return { sAMAccountName: row.match.sAMAccountName, displayName: row.match.displayName || row.name, email: row.match.email };
        });
        var btn = document.getElementById('permsImportSubmitBtn');
        btn.disabled = true;
        fetch('/api/permissions/import/submit', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ rows: users, allowedTabs: allowed })
        }).then(function (r) { return r.json(); })
          .then(function (resp) {
              if (!resp.success) { btn.disabled = false; appAlert('Submit failed: ' + (resp.error || 'unknown')); return; }
              permsCloseImport();
              permsLoadUsers();
              appAlert('Imported ' + resp.ok + ' user' + (resp.ok === 1 ? '' : 's') + '. IT emailed to add them to the access DL'
                  + (resp.failed ? '. ' + resp.failed + ' row(s) failed.' : '.'));
          })
          .catch(function (e) { btn.disabled = false; appAlert('Submit failed: ' + e); });
    });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function permsEsc(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
