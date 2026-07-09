// === Extensions Tab ===
var extSourcesLoaded = false;
// Set by app.js based on /api/auth/session — true means full admin (can remove/schedule)
var extIsAdmin = false;
var extContribCandidatesCache = null; // cached typeahead candidates

function loadExtensions() {
    if (extSourcesLoaded) return;
    extSourcesLoaded = true;
    refreshExtensionsList();
    refreshActiveJobs(); // resume tracking any in-flight rebuilds (e.g., from another tab)
    if (extIsAdmin) {
        showAdminOnlyExtensionsUI();
        refreshContributorsList();
    } else {
        // Hide schedule hour input for non-admins; show explanatory hint
        var hourInput = document.getElementById('extHour');
        if (hourInput) {
            hourInput.disabled = true;
            hourInput.placeholder = 'admin only';
            hourInput.value = '';
        }
        var hint = document.getElementById('extScheduleHint');
        if (hint) hint.style.display = 'block';
    }
}

/** Reveals the admin-only Permissions panel. Called from loadExtensions when extIsAdmin=true. */
function showAdminOnlyExtensionsUI() {
    var panel = document.getElementById('extPermissionsPanel');
    if (panel) panel.style.display = 'block';
}

function refreshExtensionsList() {
    fetch('/api/sku-data/external-sources/config')
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(sources) {
            renderExtensionsList(sources);
        })
        .catch(function() {});
}

function renderExtensionsList(sources) {
    var container = document.getElementById('extensionsList');
    if (!container) return;

    if (!sources || sources.length === 0) {
        container.innerHTML = '<p style="color:#888; text-align:center; padding:20px;">No external sources configured. Add one below.</p>';
        return;
    }

    var html = '<table class="data-table" style="font-size:13px;"><thead><tr>';
    html += '<th>Source</th><th>File Path</th><th>Key Col</th><th>Schedule</th><th>Cache Health</th><th style="text-align:right;">Records</th><th>Added By</th><th>Actions</th>';
    html += '</tr></thead><tbody>';

    sources.forEach(function(src) {
        var scheduleText = src.scheduleHour ? '<span style="font-family:var(--font-mono);font-size:11px;">Daily \u00b7 ' + src.scheduleHour + ':00</span>' : '<span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3);">Manual only</span>';
        var addedByText = src.addedBy ? esc(src.addedBy) : '<span style="color:var(--ink-4);">&mdash;</span>';
        var addedAtText = src.addedAt ? '<br><span style="font-family:var(--font-mono);font-size:10px; color:var(--ink-4);">' + esc(formatRelativeTime(src.addedAt)) + '</span>' : '';
        // Show last-refreshed line when someone has replaced the file since the
        // original add. Distinct from cache-rebuild time (Cache Health column);
        // this is "who last swapped the underlying source data".
        var refreshedLine = '';
        if (src.lastRefreshedBy && src.lastRefreshedAt) {
            refreshedLine = '<br><span style="font-size:10.5px;color:var(--ink-3);" title="Last refreshed by ' + esc(src.lastRefreshedBy) + ' on ' + esc(src.lastRefreshedAt) + '">'
                + '\u21bb refreshed by ' + esc(src.lastRefreshedBy)
                + ' \u00b7 <span style="font-family:var(--font-mono);">' + esc(formatRelativeTime(src.lastRefreshedAt)) + '</span></span>';
        }
        var cacheAgeCell = formatCacheAgeCell(src);
        var recordCell = formatRecordCountCell(src);
        html += '<tr>';
        html += '<td><div style="font-weight:500;color:var(--ink);">' + esc(src.name) + '</div><div style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4);letter-spacing:0.04em;">ID \u00b7 ' + esc(src.id) + '</div></td>';
        html += '<td style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3);word-break:break-all;max-width:260px;">' + esc(src.file) + '</td>';
        html += '<td style="font-family:var(--font-mono);font-size:12px;color:var(--ink);">' + esc(src.keyColumn) + '</td>';
        html += '<td>' + scheduleText + '</td>';
        html += '<td>' + cacheAgeCell + '</td>';
        html += '<td style="text-align:right;font-family:var(--font-mono);font-size:12px;font-weight:500;font-variant-numeric:tabular-nums;">' + recordCell + '</td>';
        html += '<td style="font-size:12px;color:var(--ink-2);">' + addedByText + addedAtText + refreshedLine + '</td>';
        // Owner-based gating for non-admin contributors:
        //  - Generate: anyone with manage rights (admin or contributor)
        //  - Replace: admin OR contributor whose own username matches src.addedByUsername (same as Remove)
        //  - Schedule: admin only
        //  - Remove: admin OR contributor whose own username matches src.addedByUsername
        var ownsRow = !!(window.currentUsername && src.addedByUsername
            && String(src.addedByUsername).toLowerCase() === String(window.currentUsername).toLowerCase());
        var canRemove = extIsAdmin || (window.extIsContributor && ownsRow);
        var canReplace = canRemove; // identical gate

        html += '<td style="white-space:nowrap;">';
        html += '<button class="btn-clear" onclick="generateExtSource(\'' + esc(src.id) + '\')" style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.06em;text-transform:uppercase;color:var(--ink-2);background:var(--surface,white);border:1px solid var(--line-2,#d0d5dd);border-radius:3px;padding:4px 9px;margin:2px;cursor:pointer;">Generate</button> ';
        if (canReplace) {
            html += '<button class="btn-clear" onclick="replaceExtSource(\'' + esc(src.id) + '\',\'' + esc(src.keyColumn) + '\')" title="Upload a fresh file to refresh this source\u2019s data" style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.06em;text-transform:uppercase;color:var(--ink-2);background:var(--surface,white);border:1px solid var(--line-2,#d0d5dd);border-radius:3px;padding:4px 9px;margin:2px;cursor:pointer;">\u21bb Replace</button> ';
        }
        if (extIsAdmin) {
            html += '<button class="btn-clear" onclick="scheduleExtSource(\'' + esc(src.id) + '\')" style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.06em;text-transform:uppercase;color:var(--ink-2);background:var(--surface,white);border:1px solid var(--line-2,#d0d5dd);border-radius:3px;padding:4px 9px;margin:2px;cursor:pointer;">Schedule</button> ';
        }
        if (canRemove) {
            html += '<button class="btn-clear" onclick="removeExtSource(\'' + esc(src.id) + '\')" style="font-family:var(--font-mono);font-size:10px;letter-spacing:0.06em;text-transform:uppercase;color:var(--bad,#b8342b);background:var(--surface,white);border:1px solid var(--line-2,#d0d5dd);border-radius:3px;padding:4px 9px;margin:2px;cursor:pointer;">Remove</button>';
        } else if (window.extIsContributor) {
            html += '<span title="Only the owner or a PLM admin can remove or replace this." style="font-size:11px;color:var(--ink-4);font-style:italic;">owner: ' + (src.addedBy ? esc(src.addedBy) : 'admin') + '</span>';
        } else if (!extIsAdmin) {
            html += '<span style="font-size:11px;color:var(--ink-4);font-style:italic;">admin-only</span>';
        }
        html += '</td>';
        html += '</tr>';
    });

    html += '</tbody></table>';
    container.innerHTML = html;
}

function onExtFileSelected() {
    var fileInput = document.getElementById('extFile');
    var select = document.getElementById('extKeyCol');
    select.innerHTML = '<option value="">-- reading headers... --</option>';
    select.disabled = true;

    if (!fileInput.files || !fileInput.files.length) {
        select.innerHTML = '<option value="">-- select file first --</option>';
        return;
    }

    var formData = new FormData();
    formData.append('file', fileInput.files[0]);

    fetch('/api/sku-data/external-sources/headers', { method: 'POST', body: formData })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success && data.headers && data.headers.length > 0) {
                select.innerHTML = '<option value="">-- select key column --</option>';
                data.headers.forEach(function(h) {
                    var opt = document.createElement('option');
                    opt.value = h;
                    opt.textContent = h;
                    select.appendChild(opt);
                });
                select.disabled = false;
            } else {
                select.innerHTML = '<option value="">-- no headers found --</option>';
            }
        })
        .catch(function() {
            select.innerHTML = '<option value="">-- failed to read --</option>';
        });
}

function addExtSource() {
    var name = document.getElementById('extName').value.trim();
    var fileInput = document.getElementById('extFile');
    var keyCol = document.getElementById('extKeyCol').value.trim();
    var hourInput = document.getElementById('extHour');
    var hour = (hourInput && !hourInput.disabled) ? hourInput.value : '';

    if (!name || !keyCol) {
        showCustomAlert('PLM Toolkit', 'Name and key column are required.');
        return;
    }
    if (!fileInput.files || !fileInput.files.length) {
        showCustomAlert('PLM Toolkit', 'Please select a file to upload.');
        return;
    }

    var formData = new FormData();
    formData.append('file', fileInput.files[0]);
    formData.append('name', name);
    formData.append('keyColumn', keyCol);
    if (hour) formData.append('scheduleHour', hour);

    var btn = event.target;
    btn.disabled = true;
    btn.textContent = 'Uploading...';

    fetch('/api/sku-data/external-sources/upload', {
        method: 'POST',
        body: formData
    })
    .then(checkAuth)
    .then(function(res) { return res.json(); })
    .then(function(data) {
        btn.disabled = false;
        btn.textContent = 'Add Source';
        // Source already registered server-side — refresh list either way so the row appears.
        refreshExtensionsList();
        if (typeof loadExternalSources === 'function') loadExternalSources();

        if (data.jobId) {
            // Clear the form so the user can add another while this one builds
            document.getElementById('extName').value = '';
            document.getElementById('extFile').value = '';
            var keySelect = document.getElementById('extKeyCol');
            keySelect.innerHTML = '<option value="">-- select file first --</option>';
            keySelect.disabled = true;
            if (hourInput) hourInput.value = '';
            // Live status that polls and updates in place — no blocking dialog.
            showJobStatusBanner(data.jobId, name);
        } else {
            // No job id (rejected pre-queue, or unexpected error)
            showCustomAlert('PLM Toolkit', data.message || 'Upload failed.');
        }
    })
    .catch(function(err) {
        btn.disabled = false;
        btn.textContent = 'Add Source';
        showCustomAlert('PLM Toolkit', 'Upload failed: ' + (err.message || err));
    });
}

function generateExtSource(id) {
    var btn = event.target;
    btn.disabled = true;
    btn.textContent = 'Queuing...';
    fetch('/api/sku-data/external-sources/' + id + '/generate', { method: 'POST' })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.textContent = 'Generate';
            if (data.jobId) {
                showJobStatusBanner(data.jobId, id);
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Generate failed.');
            }
        })
        .catch(function() { btn.disabled = false; btn.textContent = 'Generate'; });
}

// =============================================================================
// Job status banner — polls /jobs/{id} every 2s and updates in place.
// =============================================================================

/** Tracks active poll timers keyed by jobId so we never double-poll. */
var jobPollTimers = {};

function showJobStatusBanner(jobId, label) {
    var container = ensureJobBannerContainer();
    var existing = document.getElementById('jobBanner-' + jobId);
    if (existing) existing.remove();

    var banner = document.createElement('div');
    banner.id = 'jobBanner-' + jobId;
    banner.style.cssText = 'background:#eef2f7; border:1px solid #d0d5dd; border-left:4px solid #4a6fa5; border-radius:6px; padding:10px 14px; margin-bottom:8px; font-size:13px; display:flex; align-items:center; gap:10px;';
    banner.innerHTML =
        '<span class="job-spinner" style="display:inline-block; width:14px; height:14px; border:2px solid #d0d5dd; border-top-color:#4a6fa5; border-radius:50%; animation: jobSpin 1s linear infinite;"></span>'
        + '<span style="flex:1;">'
        +   '<strong>' + esc(label) + '</strong>: <span class="job-status-text">submitting...</span>'
        + '</span>'
        + '<a href="#" onclick="dismissJobBanner(\'' + esc(jobId) + '\'); return false;" style="color:#888; text-decoration:none; padding:0 6px;" title="Dismiss">&times;</a>';
    container.appendChild(banner);
    ensureSpinKeyframes();
    pollJobStatus(jobId);
}

function pollJobStatus(jobId) {
    fetch('/api/sku-data/external-sources/jobs/' + encodeURIComponent(jobId))
        .then(checkAuth)
        .then(function(res) {
            if (res.status === 404) return null;
            return res.json();
        })
        .then(function(job) {
            if (!job) return;
            updateJobBanner(jobId, job);
            // Stop polling on a terminal state, otherwise schedule the next tick
            var terminal = (job.status === 'success' || job.status === 'failed' || job.status === 'rejected');
            if (!terminal) {
                jobPollTimers[jobId] = setTimeout(function() { pollJobStatus(jobId); }, 2000);
            } else {
                delete jobPollTimers[jobId];
                // Refresh the table so Cache Age + record count are current
                refreshExtensionsList();
                if (typeof loadExternalSources === 'function') loadExternalSources();
            }
        })
        .catch(function() {
            // Network blip — try again in 5s
            jobPollTimers[jobId] = setTimeout(function() { pollJobStatus(jobId); }, 5000);
        });
}

function updateJobBanner(jobId, job) {
    var banner = document.getElementById('jobBanner-' + jobId);
    if (!banner) return;
    var spinner = banner.querySelector('.job-spinner');
    var text = banner.querySelector('.job-status-text');
    if (!text) return;

    var statusColor = '#4a6fa5';
    var msg;
    switch (job.status) {
        case 'queued':
            msg = 'queued' + (job.queuePosition ? ' (position ' + job.queuePosition + ' in line)' : '') + '...';
            break;
        case 'running':
            var elapsedSec = job.startedAt ? Math.floor((Date.now() - job.startedAt) / 1000) : 0;
            var rowsTxt = (typeof job.rowsProcessed === 'number' && job.rowsProcessed > 0)
                    ? Number(job.rowsProcessed).toLocaleString() + ' rows processed, '
                    : '';
            msg = 'building cache... (' + rowsTxt + elapsedSec + 's elapsed)';
            break;
        case 'success':
            statusColor = '#28a745';
            msg = '\u2713 ' + (job.message || 'cache built');
            if (spinner) spinner.style.display = 'none';
            banner.style.borderLeftColor = statusColor;
            break;
        case 'failed':
            statusColor = '#dc3545';
            msg = '\u2717 ' + (job.message || 'failed');
            if (spinner) spinner.style.display = 'none';
            banner.style.borderLeftColor = statusColor;
            break;
        case 'rejected':
            statusColor = '#b8860b';
            msg = '\u26A0 ' + (job.message || 'rejected');
            if (spinner) spinner.style.display = 'none';
            banner.style.borderLeftColor = statusColor;
            break;
        default:
            msg = job.status;
    }
    text.innerHTML = msg;
    text.style.color = statusColor;
}

function dismissJobBanner(jobId) {
    if (jobPollTimers[jobId]) {
        clearTimeout(jobPollTimers[jobId]);
        delete jobPollTimers[jobId];
    }
    var b = document.getElementById('jobBanner-' + jobId);
    if (b) b.remove();
}

function ensureJobBannerContainer() {
    var c = document.getElementById('jobBannerContainer');
    if (c) return c;
    c = document.createElement('div');
    c.id = 'jobBannerContainer';
    c.style.cssText = 'max-width:1400px; margin:0 auto 16px; padding:0 24px;';
    var listDiv = document.getElementById('extensionsList');
    if (listDiv && listDiv.parentNode) {
        listDiv.parentNode.insertBefore(c, listDiv);
    } else {
        document.body.appendChild(c);
    }
    return c;
}

function ensureSpinKeyframes() {
    if (document.getElementById('jobSpinKeyframes')) return;
    var s = document.createElement('style');
    s.id = 'jobSpinKeyframes';
    s.textContent = '@keyframes jobSpin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }';
    document.head.appendChild(s);
}

/** When the tab is opened, surface any queued/running jobs already in flight
 *  (e.g., a rebuild someone else triggered, or one started in a previous session). */
function refreshActiveJobs() {
    fetch('/api/sku-data/external-sources/jobs')
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (!data || !data.success || !data.jobs) return;
            data.jobs.forEach(function(job) {
                if (job.status === 'queued' || job.status === 'running') {
                    if (!document.getElementById('jobBanner-' + job.id) && !jobPollTimers[job.id]) {
                        showJobStatusBanner(job.id, job.sourceName || job.sourceId);
                    }
                }
            });
        })
        .catch(function() {});
}

function scheduleExtSource(id) {
    appPrompt('Enter the hour (0-23) for daily generation:', '', { placeholder: 'e.g. 8' }).then(function (hour) {
        if (hour === null) return;
        hour = hour.trim();
        if (!/^\d{1,2}$/.test(hour) || parseInt(hour) < 0 || parseInt(hour) > 23) {
            showCustomAlert('PLM Toolkit', 'Please enter a valid hour (0-23).');
            return;
        }
        fetch('/api/sku-data/external-sources/' + id + '/schedule', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ hour: hour })
        })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                showCustomAlert('PLM Toolkit', data.message);
                refreshExtensionsList();
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Failed.');
            }
        });
    });
}

function removeExtSource(id) {
    appConfirm('Remove this external source?', { danger: true, okText: 'Remove' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/sku-data/external-sources/' + id, { method: 'DELETE' })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                refreshExtensionsList();
                if (typeof loadExternalSources === 'function') loadExternalSources();
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Failed.');
            }
        });
    });
}

// =============================================================================
// Contributor Permissions (admin only)
// =============================================================================

function refreshContributorsList() {
    fetch('/api/sku-data/external-sources/permissions')
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) renderContributorsList(data.contributors || []);
        });
}

function renderContributorsList(contributors) {
    var container = document.getElementById('extContributorsList');
    if (!container) return;
    if (contributors.length === 0) {
        container.innerHTML = '<span style="color:var(--ink-3,#888);">No contributors yet. Add one below.</span>';
        return;
    }

    // Render placeholder chips first, then resolve names via API
    container.innerHTML = '';
    contributors.forEach(function(u) {
        var chip = document.createElement('span');
        chip.className = 'ext-contrib-chip';
        chip.setAttribute('data-uid', u);
        chip.style.cssText = 'display:inline-flex;align-items:center;gap:6px;background:var(--surface-2,#f0f0f0);border:1px solid var(--line,#e0e0e0);border-radius:14px;padding:4px 6px 4px 4px;margin:2px 4px 2px 0;font-size:12px;';

        // Avatar + loading name
        chip.innerHTML =
            '<span class="user-avatar" style="width:20px;height:20px;font-size:9px;">' + esc(u.substring(0, 2).toUpperCase()) + '</span>' +
            '<span class="ext-contrib-name" style="cursor:pointer;color:var(--accent,#2c6fbb);font-weight:500;" onclick="showUserCard(event,\'' + esc(u) + '\')">' + esc(u) + '</span>' +
            '<a href="#" onclick="revokeContributor(\'' + esc(u) + '\'); return false;" title="Revoke" style="margin-left:2px;color:var(--ink-4,#aaa);text-decoration:none;font-size:14px;padding:0 4px;line-height:1;">&times;</a>';
        container.appendChild(chip);

        // Resolve display name
        resolveContributorName(u, chip);
    });

    extContribCandidatesCache = null;
}

function resolveContributorName(empId, chip) {
    // Check client cache first
    if (typeof _userInfoCache !== 'undefined' && _userInfoCache[empId]) {
        applyContributorName(chip, _userInfoCache[empId]);
        return;
    }
    fetch('/api/auth/user-info/' + empId)
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) {
                if (typeof _userInfoCache !== 'undefined') _userInfoCache[empId] = data;
                applyContributorName(chip, data);
            }
        })
        .catch(function() { /* keep the ID as fallback */ });
}

function applyContributorName(chip, data) {
    var nameEl = chip.querySelector('.ext-contrib-name');
    var avEl = chip.querySelector('.user-avatar');
    if (nameEl && data.displayName) {
        nameEl.textContent = data.displayName;
    }
    if (avEl && data.displayName) {
        avEl.textContent = getInitials(data.displayName);
    }
}

function revokeContributor(username) {
    appConfirm('Revoke contributor access from "' + username + '"?', { danger: true, okText: 'Revoke' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/sku-data/external-sources/permissions/contributors/' + encodeURIComponent(username), { method: 'DELETE' })
            .then(checkAuth)
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) {
                    renderContributorsList(data.contributors || []);
                } else {
                    showCustomAlert('PLM Toolkit', data.message || 'Failed.');
                }
            });
    });
}

function grantContributor(username) {
    fetch('/api/sku-data/external-sources/permissions/contributors/' + encodeURIComponent(username), { method: 'PUT' })
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                document.getElementById('extContribInput').value = '';
                hideContributorTypeahead();
                renderContributorsList(data.contributors || []);
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Failed.');
            }
        });
}

function onContributorTypeahead() {
    var input = document.getElementById('extContribInput');
    if (!input) return;
    var q = input.value.trim();
    var url = '/api/sku-data/external-sources/permissions/candidates' + (q ? '?q=' + encodeURIComponent(q) : '');
    fetch(url)
        .then(checkAuth)
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (!data.success) return;
            renderContributorSuggestions(data.candidates || []);
        });
}

function renderContributorSuggestions(candidates) {
    var box = document.getElementById('extContribSuggestions');
    if (!box) return;
    if (candidates.length === 0) {
        box.innerHTML = '<div style="padding:10px; color:#888; font-size:12px;">No matching users</div>';
        box.style.display = 'block';
        return;
    }
    var html = '';
    candidates.forEach(function(c) {
        html += '<div onclick="grantContributor(\'' + esc(c.username) + '\')" style="padding:8px 12px; cursor:pointer; border-bottom:1px solid #f0f0f0;" onmouseover="this.style.background=\'#f5f7fa\'" onmouseout="this.style.background=\'white\'">';
        html += '<div style="font-weight:600; color:#1a3a5c;">' + esc(c.displayName) + '</div>';
        html += '<div style="font-size:11px; color:#888;">' + esc(c.username) + (c.email ? ' &bull; ' + esc(c.email) : '') + '</div>';
        html += '</div>';
    });
    box.innerHTML = html;
    box.style.display = 'block';
}

function hideContributorTypeahead() {
    var box = document.getElementById('extContribSuggestions');
    if (box) box.style.display = 'none';
}

/** Renders an ISO timestamp as a friendly relative phrase: "just now",
 *  "5 minutes ago", "1 hour ago", "3 days ago", or a date for >30 days. */
function formatRelativeTime(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    var diffMs = Date.now() - d.getTime();
    if (diffMs < 0) return 'just now';
    var sec = Math.floor(diffMs / 1000);
    if (sec < 60) return 'just now';
    var min = Math.floor(sec / 60);
    if (min < 60) return min + (min === 1 ? ' minute ago' : ' minutes ago');
    var hr = Math.floor(min / 60);
    if (hr < 24) return hr + (hr === 1 ? ' hour ago' : ' hours ago');
    var day = Math.floor(hr / 24);
    if (day < 30) return day + (day === 1 ? ' day ago' : ' days ago');
    var months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    return months[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear();
}

/** Records cell — formatted unique count from the last successful rebuild.
 *  Shown right-aligned with thousands separators. Empty/dashes if never built. */
function formatRecordCountCell(src) {
    var n = parseInt(src.recordCount || '', 10);
    if (!n || isNaN(n)) return '<span style="color:#bbb;">&mdash;</span>';
    return '<strong>' + n.toLocaleString() + '</strong>';
}

/** Cache-age cell with color coding based on the source's schedule expectation:
 *  - Never generated: red
 *  - Scheduled daily but cache > 36h old: red (schedule may be broken)
 *  - Cache <= 24h old: green; <= 3 days: amber; > 3 days: red */
function formatCacheAgeCell(src) {
    if (!src.cacheGeneratedAt) {
        return '<span class="v2-life eol" style="font-size:10px;">never generated</span>';
    }
    var ageMs = Date.now() - parseInt(src.cacheGeneratedAtMs || '0', 10);
    var ageHr = ageMs / (60 * 60 * 1000);

    var healthCls = 'act';
    var healthLabel = 'fresh';
    var hint = '';
    if (src.scheduleHour && ageHr > 36) {
        healthCls = 'eol';
        healthLabel = 'stale';
        hint = '<br><span style="font-family:var(--font-mono);font-size:10px;color:var(--bad,#b8342b);">schedule overdue</span>';
    } else if (ageHr > 72) {
        healthCls = 'eol';
        healthLabel = 'stale';
    } else if (ageHr > 24) {
        healthCls = 'pend';
        healthLabel = 'aging';
    }
    var ageText = esc(formatRelativeTime(src.cacheGeneratedAt));
    return '<span class="v2-life ' + healthCls + '" style="font-size:10px;">' + healthLabel + ' \u00b7 ' + ageText + '</span>' + hint;
}

// ===========================================================================
// Replace File — refreshes an existing source with a new upload, optionally
// switching the key column. Auth-checked server-side; the UI gates it the
// same way Remove is gated (admin or owner).
// ===========================================================================

/** Open the Replace modal for a given source. */
function replaceExtSource(sourceId, currentKeyCol) {
    // Ensure the modal exists in the DOM (lazy-create on first use).
    var modal = document.getElementById('extReplaceModal');
    if (!modal) {
        modal = document.createElement('div');
        modal.id = 'extReplaceModal';
        modal.style.cssText = 'display:none;position:fixed;inset:0;background:rgba(0,0,0,0.4);z-index:9999;align-items:flex-start;justify-content:center;padding-top:10vh;';
        modal.innerHTML =
            '<div style="background:white;border-radius:8px;box-shadow:0 8px 32px rgba(0,0,0,0.2);width:480px;max-width:92vw;padding:0;font-family:var(--font-sans, system-ui);">'
              + '<div style="padding:16px 20px;border-bottom:1px solid var(--line-2,#e8e6df);display:flex;align-items:center;justify-content:space-between;">'
                + '<div style="font-size:14px;font-weight:600;color:var(--ink,#0F1720);">\u21bb Replace file for <span id="extReplaceTarget" style="font-family:var(--font-mono);color:var(--ink-2,#444);"></span></div>'
                + '<a href="#" onclick="closeExtReplaceModal();return false;" style="text-decoration:none;color:var(--ink-4,#aaa);font-size:18px;line-height:1;">&times;</a>'
              + '</div>'
              + '<div style="padding:16px 20px;font-size:12.5px;color:var(--ink-2,#444);line-height:1.5;">'
                + '<p style="margin:0 0 12px;color:var(--ink-3,#666);">Original creator stays the same. The current file remains on disk in case the rebuild fails.</p>'
                + '<label style="display:block;font-size:11px;color:var(--ink-3);text-transform:uppercase;letter-spacing:0.06em;margin-bottom:4px;">New file</label>'
                + '<input type="file" id="extReplaceFile" accept=".xlsx,.xls,.csv" onchange="onExtReplaceFileSelected()" style="margin-bottom:14px;font-size:12px;">'
                + '<label style="display:block;font-size:11px;color:var(--ink-3);text-transform:uppercase;letter-spacing:0.06em;margin-bottom:4px;">New key column <span style="text-transform:none;color:var(--ink-4);">(leave on \u201cKeep current\u201d to use the existing one)</span></label>'
                + '<select id="extReplaceKeyCol" disabled style="width:100%;padding:6px 8px;border:1px solid var(--line-2,#d0d5dd);border-radius:4px;font-size:13px;background:white;">'
                  + '<option value="">-- pick a file first --</option>'
                + '</select>'
                + '<div id="extReplaceMsg" style="margin-top:12px;font-size:12px;min-height:16px;"></div>'
              + '</div>'
              + '<div style="padding:12px 20px;border-top:1px solid var(--line-2,#e8e6df);display:flex;justify-content:flex-end;gap:8px;">'
                + '<button onclick="closeExtReplaceModal()" style="padding:6px 14px;border:1px solid var(--line-2,#d0d5dd);background:white;border-radius:4px;cursor:pointer;font-size:13px;">Cancel</button>'
                + '<button id="extReplaceSubmitBtn" onclick="submitExtReplace()" disabled style="padding:6px 14px;border:1px solid #0F1720;background:#0F1720;color:white;border-radius:4px;cursor:pointer;font-size:13px;">Replace</button>'
              + '</div>'
            + '</div>';
        document.body.appendChild(modal);
    }

    // Reset state
    modal.dataset.sourceId = sourceId;
    modal.dataset.currentKeyCol = currentKeyCol || '';
    document.getElementById('extReplaceTarget').textContent = sourceId;
    var fileInput = document.getElementById('extReplaceFile');
    if (fileInput) fileInput.value = '';
    var sel = document.getElementById('extReplaceKeyCol');
    if (sel) {
        sel.innerHTML = '<option value="">-- pick a file first --</option>';
        sel.disabled = true;
    }
    document.getElementById('extReplaceMsg').textContent = '';
    document.getElementById('extReplaceMsg').style.color = '';
    document.getElementById('extReplaceSubmitBtn').disabled = true;
    modal.style.display = 'flex';
}

function closeExtReplaceModal() {
    var modal = document.getElementById('extReplaceModal');
    if (modal) modal.style.display = 'none';
}

/** When the user picks a file, read its headers and populate the key-col dropdown. */
function onExtReplaceFileSelected() {
    var fileInput = document.getElementById('extReplaceFile');
    var sel = document.getElementById('extReplaceKeyCol');
    var msg = document.getElementById('extReplaceMsg');
    var submitBtn = document.getElementById('extReplaceSubmitBtn');
    var modal = document.getElementById('extReplaceModal');
    var currentKeyCol = (modal && modal.dataset.currentKeyCol) || '';

    if (!fileInput.files || !fileInput.files.length) {
        sel.innerHTML = '<option value="">-- pick a file first --</option>';
        sel.disabled = true;
        submitBtn.disabled = true;
        return;
    }
    sel.innerHTML = '<option value="">-- reading headers... --</option>';
    sel.disabled = true;
    msg.textContent = '';
    submitBtn.disabled = true;

    var formData = new FormData();
    formData.append('file', fileInput.files[0]);

    fetch('/api/sku-data/external-sources/headers', { method: 'POST', body: formData })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success && data.headers && data.headers.length > 0) {
                sel.innerHTML = '';
                // First option: keep current key column (default selection).
                var keep = document.createElement('option');
                keep.value = '';
                keep.textContent = 'Keep current (' + currentKeyCol + ')';
                sel.appendChild(keep);
                // Then every header in the new file.
                data.headers.forEach(function(h) {
                    var opt = document.createElement('option');
                    opt.value = h;
                    opt.textContent = h;
                    sel.appendChild(opt);
                });
                sel.disabled = false;

                // Sanity check: if the current key column isn't in the new file's headers,
                // surface a hint so the user knows they MUST pick a different one.
                var headersLc = data.headers.map(function(h) { return String(h).toLowerCase(); });
                if (currentKeyCol && headersLc.indexOf(String(currentKeyCol).toLowerCase()) < 0) {
                    msg.textContent = '\u26a0 Heads-up: the current key column \u201c' + currentKeyCol + '\u201d isn\u2019t in this file. Pick a new one above.';
                    msg.style.color = 'var(--warn,#C7801B)';
                }
                submitBtn.disabled = false;
            } else {
                sel.innerHTML = '<option value="">-- no headers found --</option>';
                msg.textContent = 'Could not read headers from this file.';
                msg.style.color = 'var(--bad,#B8342B)';
            }
        })
        .catch(function(err) {
            sel.innerHTML = '<option value="">-- error --</option>';
            msg.textContent = 'Failed to read headers: ' + (err && err.message ? err.message : err);
            msg.style.color = 'var(--bad,#B8342B)';
        });
}

function submitExtReplace() {
    var modal = document.getElementById('extReplaceModal');
    var sourceId = modal && modal.dataset.sourceId;
    if (!sourceId) return;

    var fileInput = document.getElementById('extReplaceFile');
    var sel = document.getElementById('extReplaceKeyCol');
    var msg = document.getElementById('extReplaceMsg');
    var submitBtn = document.getElementById('extReplaceSubmitBtn');

    if (!fileInput.files || !fileInput.files.length) {
        msg.textContent = 'Pick a file first.';
        msg.style.color = 'var(--bad,#B8342B)';
        return;
    }

    var formData = new FormData();
    formData.append('file', fileInput.files[0]);
    var newKey = sel ? sel.value : '';
    if (newKey) formData.append('keyColumn', newKey);

    submitBtn.disabled = true;
    submitBtn.textContent = 'Replacing\u2026';
    msg.textContent = 'Uploading and starting rebuild\u2026';
    msg.style.color = 'var(--ink-3,#666)';

    fetch('/api/sku-data/external-sources/' + encodeURIComponent(sourceId) + '/replace-file', {
        method: 'POST', body: formData
    })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                msg.textContent = '\u2713 ' + (data.message || 'File replaced. Cache rebuilding\u2026');
                msg.style.color = 'var(--ok,#1F8A4C)';
                // Track the rebuild job — same plumbing the upload form uses.
                if (data.jobId && typeof trackJob === 'function') {
                    trackJob(data.jobId, sourceId, true);
                }
                refreshExtensionsList();
                setTimeout(closeExtReplaceModal, 1200);
            } else {
                msg.textContent = '\u2717 ' + (data.message || 'Replace failed.');
                msg.style.color = 'var(--bad,#B8342B)';
                submitBtn.disabled = false;
                submitBtn.textContent = 'Replace';
            }
        })
        .catch(function(err) {
            msg.textContent = '\u2717 ' + (err && err.message ? err.message : err);
            msg.style.color = 'var(--bad,#B8342B)';
            submitBtn.disabled = false;
            submitBtn.textContent = 'Replace';
        });
}
