// =====================================================================
//  perms-stats.js — 30-day login traffic chart + per-user activity popover
//  for the User Permissions tab. Talks to:
//    GET /api/permissions/stats/traffic?days=30
//    GET /api/permissions/stats/user/{username}/activity
//    GET /api/auth/user-info/{username}
// =====================================================================

(function () {
  'use strict';

  let _chart = null;
  let _chartSeries = [];    // last fetched traffic series — used by click handler to resolve index → date
  let _chartIsAdmin = false; // viewer is PLM admin (controls drilldown affordance)
  let _profileCache = {};   // username → AD profile JSON
  let _activityCache = {};  // username → activity JSON

  // -------------------------------------------------------------------
  // 30-day traffic chart
  // -------------------------------------------------------------------
  window.permsLoadTrafficChart = function () {
    fetch('/api/permissions/stats/traffic?days=30', { credentials: 'same-origin' })
      .then(r => {
        if (r.status === 403) throw new Error('forbidden');
        return r.json();
      })
      .then(data => {
        _chartIsAdmin = !!data.isPlmAdmin;
        renderTrafficChart(data.series || []);
      })
      .catch(err => {
        const el = document.getElementById('permsTrafficSummary');
        if (el) el.textContent = err.message === 'forbidden' ? '' : 'Failed to load: ' + err.message;
      });
  };

  function renderTrafficChart(series) {
    const canvas = document.getElementById('permsTrafficChart');
    if (!canvas || typeof Chart === 'undefined') return;

    _chartSeries = series;
    const labels = series.map(d => d.day.substring(5)); // MM-DD
    const logins = series.map(d => d.logins);
    const uniqueUsers = series.map(d => d.uniqueUsers);

    const totalLogins = logins.reduce((a, b) => a + b, 0);
    const peak = Math.max(...uniqueUsers);
    const summary = document.getElementById('permsTrafficSummary');
    const baseText = totalLogins + ' logins · peak ' + peak + ' unique users/day';
    if (summary) summary.textContent = _chartIsAdmin ? baseText + ' · click any bar for that day\u2019s logins' : baseText;

    if (_chart) _chart.destroy();
    _chart = new Chart(canvas.getContext('2d'), {
      type: 'bar',
      data: {
        labels: labels,
        datasets: [
          {
            label: 'Logins',
            data: logins,
            backgroundColor: 'rgba(74, 111, 165, 0.55)',
            borderColor: '#4a6fa5',
            borderWidth: 1,
            yAxisID: 'y',
            order: 2
          },
          {
            label: 'Unique users',
            data: uniqueUsers,
            type: 'line',
            borderColor: '#1F8A4C',
            backgroundColor: 'rgba(31, 138, 76, 0.15)',
            tension: 0.3,
            pointRadius: 2.5,
            pointBackgroundColor: '#1F8A4C',
            yAxisID: 'y1',
            order: 1
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        // Drilldown handlers are only attached for PLM admins. Permissions-
        // admins see chart numbers but cannot open the per-day modal.
        onClick: !_chartIsAdmin ? null : (evt, elements) => {
          let idx = -1;
          if (elements && elements.length > 0 && typeof elements[0].index === 'number') {
            idx = elements[0].index;
          } else if (_chart) {
            const points = _chart.getElementsAtEventForMode(evt, 'index', { intersect: false }, true);
            if (points.length > 0) idx = points[0].index;
          }
          if (idx < 0 || idx >= _chartSeries.length) return;
          openTrafficDayModal(_chartSeries[idx].day);
        },
        onHover: !_chartIsAdmin ? null : (evt, elements) => {
          const target = evt.native ? evt.native.target : evt.target;
          if (target && target.style) {
            target.style.cursor = (elements && elements.length > 0) ? 'pointer' : 'default';
          }
        },
        plugins: {
          legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } },
          tooltip: { backgroundColor: '#0F1720', titleFont: { size: 12 }, bodyFont: { size: 12 } }
        },
        scales: {
          x: { ticks: { font: { size: 10 }, maxRotation: 0, autoSkip: true, autoSkipPadding: 6 }, grid: { display: false } },
          y: {
            beginAtZero: true,
            position: 'left',
            title: { display: true, text: 'Logins', font: { size: 11 }, color: '#4a6fa5' },
            ticks: { font: { size: 10 } },
            grid: { color: '#f0f0f0' }
          },
          y1: {
            beginAtZero: true,
            position: 'right',
            title: { display: true, text: 'Unique users', font: { size: 11 }, color: '#1F8A4C' },
            ticks: { font: { size: 10 }, precision: 0 },
            grid: { drawOnChartArea: false }
          }
        }
      }
    });
  }

  // -------------------------------------------------------------------
  // Per-day login drilldown — opens when a chart bar/point is clicked
  // -------------------------------------------------------------------
  function openTrafficDayModal(date) {
    const m = document.getElementById('trafficDayModal');
    if (!m) return;
    document.getElementById('trafficDayTitle').textContent = 'Logins on ' + formatDayHeader(date);
    document.getElementById('trafficDaySubtitle').textContent = '';
    document.getElementById('trafficDayBody').innerHTML =
      '<div style="text-align:center; color:#6B7280; padding:30px;">Loading...</div>';
    m.style.display = 'flex';

    fetch('/api/permissions/stats/traffic/day?date=' + encodeURIComponent(date), { credentials: 'same-origin' })
      .then(r => r.json())
      .then(data => renderTrafficDayBody(data))
      .catch(err => {
        document.getElementById('trafficDayBody').innerHTML =
          '<div style="color:#B8342B; padding:14px;">Failed to load: ' + esc(err.message || String(err)) + '</div>';
      });
  }
  window.permsCloseTrafficDay = function () {
    const m = document.getElementById('trafficDayModal');
    if (m) m.style.display = 'none';
  };

  let _trafficDayDate = null; // cached so chip clicks can compute the session window

  function renderTrafficDayBody(data) {
    const body = document.getElementById('trafficDayBody');
    const sub = document.getElementById('trafficDaySubtitle');
    if (!body) return;
    const tzLabel = data.zoneLabel ? ' · times in ' + data.zoneLabel : '';
    if (sub) sub.textContent = (data.totalLogins || 0) + ' logins · ' + (data.uniqueUsers || 0) + ' unique users' + tzLabel + ' · click any chip for that session';
    _trafficDayDate = data.date;

    const users = data.users || [];
    if (users.length === 0) {
      body.innerHTML = '<div style="text-align:center; color:#6B7280; padding:30px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px;">No logins recorded on this day.</div>';
      return;
    }
    body.innerHTML = users.map((u, ui) => {
      const times = (u.times || []);
      const timesHtml = times.map((t, ti) => {
        // Each chip is clickable. We pass the user index + chip index so the
        // handler can compute the [thisLogin, nextLogin) window using the
        // utcIso anchors (server-side TZ conversion is display-only).
        const displayTxt = (typeof t === 'string') ? t : (t.display || '');
        return '<a href="#" onclick="permsOpenSession(' + ui + ',' + ti + '); return false;"' +
          ' style="display:inline-block; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:10px; padding:2px 8px; font-size:11px;' +
          ' font-family:\'IBM Plex Mono\',Consolas,monospace; color:#0F1720; margin:2px 4px 2px 0; text-decoration:none; cursor:pointer;"' +
          ' title="See what ' + esc(u.displayName || u.username) + ' did after this login">' + esc(displayTxt) + '</a>';
      }).join('');
      return (
        '<div data-uidx="' + ui + '" style="padding:10px 0; border-bottom:1px solid #E8E6DF;">' +
          '<div style="display:flex; justify-content:space-between; align-items:flex-start;">' +
            '<div style="min-width:160px;">' +
              '<a href="#" onclick="permsOpenUserPopover(\'' + esc(u.username) + '\', \'' + esc(u.displayName || u.username) + '\'); return false;" style="font-weight:600; color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #c8d3e3;">' + esc(u.displayName || u.username) + '</a>' +
              '<div style="font-size:11px; color:#6B7280; font-family:\'IBM Plex Mono\',Consolas,monospace;">' + esc(u.username) + '</div>' +
            '</div>' +
            '<div style="flex:1; text-align:right; padding-left:12px;">' + timesHtml +
              '<div style="font-size:10px; color:#6B7280; margin-top:2px;">' + times.length + ' login' + (times.length === 1 ? '' : 's') + '</div>' +
            '</div>' +
          '</div>' +
          '<div class="session-detail" style="display:none; margin-top:10px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:10px 12px;"></div>' +
        '</div>'
      );
    }).join('');

    // Stash the user list for chip-click handler.
    _trafficDayUsers = users;
  }

  let _trafficDayUsers = [];

  window.permsOpenSession = function (uidx, tidx) {
    const u = _trafficDayUsers[uidx];
    if (!u) return;
    const times = u.times || [];
    const t = times[tidx];
    if (!t) return;

    // Resolve session window using the UTC anchors the server provided. The
    // chip's display text is in the viewer's timezone but the boundary math
    // has to stay in UTC — that's the API contract.
    const fromIso = (typeof t === 'object' && t.utcIso) ? t.utcIso : null;
    if (!fromIso) return;
    let toIso;
    if (tidx + 1 < times.length && typeof times[tidx + 1] === 'object' && times[tidx + 1].utcIso) {
      toIso = times[tidx + 1].utcIso;
    } else {
      // Last login → look forward 60 minutes from this UTC instant.
      const d = new Date(fromIso);
      d.setUTCMinutes(d.getUTCMinutes() + 60);
      toIso = d.toISOString().replace(/\.\d{3}Z$/, 'Z');
    }
    const displayTxt = (typeof t === 'object') ? (t.display || '') : t;

    // Find the corresponding row's session-detail div and toggle it.
    const row = document.querySelector('#trafficDayBody div[data-uidx="' + uidx + '"]');
    if (!row) return;
    const detail = row.querySelector('.session-detail');
    if (!detail) return;

    // If already showing the SAME session, collapse.
    if (detail.dataset.activeFrom === fromIso && detail.style.display !== 'none') {
      detail.style.display = 'none';
      detail.dataset.activeFrom = '';
      return;
    }

    detail.dataset.activeFrom = fromIso;
    detail.style.display = '';
    detail.innerHTML = '<div style="text-align:center; color:#6B7280; padding:14px; font-size:12px;">Loading session starting ' + esc(displayTxt) + '\u2026</div>';

    fetch('/api/permissions/stats/user/' + encodeURIComponent(u.username) + '/session?fromIso=' + encodeURIComponent(fromIso) + '&toIso=' + encodeURIComponent(toIso),
          { credentials: 'same-origin' })
      .then(r => r.json())
      .then(data => renderSessionEvents(detail, data, displayTxt))
      .catch(err => {
        detail.innerHTML = '<div style="color:#B8342B; padding:8px; font-size:12px;">Failed to load: ' + esc(err.message || String(err)) + '</div>';
      });
  };

  function renderSessionEvents(target, data, startDisplay) {
    const events = data.events || [];
    const tzNote = data.zoneLabel ? ' \u00b7 ' + data.zoneLabel : '';
    const header = '<div style="font-size:11px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280; margin-bottom:6px; font-weight:600;">' +
      'Session from ' + esc(startDisplay) + tzNote + ' \u00b7 ' + events.length + ' event' + (events.length === 1 ? '' : 's') + '</div>';
    if (events.length === 0) {
      target.innerHTML = header + '<div style="font-size:12px; color:#6B7280; font-style:italic;">No activity recorded in this window.</div>';
      return;
    }
    const rows = events.map(e => {
      const action = prettyAction(e.action);
      const details = e.details ? esc(e.details).substring(0, 200) : '';
      return (
        '<div style="display:flex; align-items:flex-start; padding:3px 0; font-size:12px; border-bottom:1px solid #ededec;">' +
          '<div style="width:80px; flex-shrink:0; font-family:\'IBM Plex Mono\',Consolas,monospace; color:#6B7280;">' + esc(e.time) + '</div>' +
          '<div style="width:160px; flex-shrink:0; font-weight:600; color:#0F1720;">' + esc(action) + '</div>' +
          '<div style="flex:1; color:#0F1720;">' + details + '</div>' +
        '</div>'
      );
    }).join('');
    target.innerHTML = header + rows;
  }

  function formatDayHeader(iso) {
    if (!iso) return '';
    try {
      const parts = iso.split('-');
      const d = new Date(Date.UTC(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10)));
      const days = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
      const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
      return days[d.getUTCDay()] + ', ' + months[d.getUTCMonth()] + ' ' + d.getUTCDate() + ', ' + d.getUTCFullYear();
    } catch (e) { return iso; }
  }

  // -------------------------------------------------------------------
  // Per-user popover: AD profile + activity drilldown
  // -------------------------------------------------------------------
  window.permsOpenUserPopover = function (username, displayName) {
    const m = document.getElementById('userProfilePopover');
    if (!m) return;
    document.getElementById('userProfileName').textContent = displayName || username;
    document.getElementById('userProfileUsername').textContent = username;
    document.getElementById('userProfileBody').innerHTML =
      '<div style="text-align:center; color:#6B7280; padding:30px;">Loading...</div>';
    m.style.display = 'flex';

    Promise.all([
      _profileCache[username]
        ? Promise.resolve(_profileCache[username])
        : fetch('/api/auth/user-info/' + encodeURIComponent(username), { credentials: 'same-origin' })
            .then(r => r.json())
            .then(j => { _profileCache[username] = j; return j; }),
      _activityCache[username]
        ? Promise.resolve(_activityCache[username])
        : fetch('/api/permissions/stats/user/' + encodeURIComponent(username) + '/activity', { credentials: 'same-origin' })
            .then(r => r.json())
            .then(j => { _activityCache[username] = j; return j; })
    ])
      .then(([profile, activity]) => renderUserProfileBody(username, displayName, profile, activity))
      .catch(err => {
        document.getElementById('userProfileBody').innerHTML =
          '<div style="color:#B8342B; padding:14px;">Failed to load: ' + esc(err.message || String(err)) + '</div>';
      });
  };

  window.permsCloseUserPopover = function () {
    const m = document.getElementById('userProfilePopover');
    if (m) m.style.display = 'none';
  };

  function renderUserProfileBody(username, displayName, profile, activity) {
    const body = document.getElementById('userProfileBody');
    if (!body) return;

    // AD section
    let adHtml;
    if (!profile || profile.success === false) {
      adHtml = '<div style="background:#fff8e1; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; padding:10px 14px; font-size:12px;">No AD profile found for <code>' + esc(username) + '</code>. They may have left the company or the username is non-standard.</div>';
    } else {
      const dept = profile.department || '<span style="color:#6B7280;">—</span>';
      const title = profile.title || '<span style="color:#6B7280;">—</span>';
      const manager = profile.manager || '<span style="color:#6B7280;">—</span>';
      const office = [profile.city, profile.country].filter(x => x).join(', ') || '<span style="color:#6B7280;">—</span>';
      const emailRow = profile.email
        ? '<tr><td style="color:#6B7280; padding:5px 0; width:30%;">Email</td><td style="padding:5px 0;"><a href="mailto:' + esc(profile.email) + '" style="color:#4a6fa5;">' + esc(profile.email) + '</a></td></tr>'
        : '';
      adHtml =
        '<div style="font-size:11px; letter-spacing:0.08em; text-transform:uppercase; color:#6B7280; margin-bottom:8px; font-weight:600;">AD profile</div>' +
        '<table style="width:100%; font-size:13px; margin-bottom:18px;">' +
          '<tr><td style="color:#6B7280; padding:5px 0; width:30%;">Department</td><td style="padding:5px 0;">' + (typeof dept === 'string' && dept.indexOf('<') === 0 ? dept : esc(dept)) + '</td></tr>' +
          '<tr><td style="color:#6B7280; padding:5px 0;">Title</td><td style="padding:5px 0;">' + (typeof title === 'string' && title.indexOf('<') === 0 ? title : esc(title)) + '</td></tr>' +
          '<tr><td style="color:#6B7280; padding:5px 0;">Manager</td><td style="padding:5px 0;">' + (typeof manager === 'string' && manager.indexOf('<') === 0 ? manager : esc(manager)) + '</td></tr>' +
          '<tr><td style="color:#6B7280; padding:5px 0;">Office</td><td style="padding:5px 0;">' + (typeof office === 'string' && office.indexOf('<') === 0 ? office : esc(office)) + '</td></tr>' +
          emailRow +
        '</table>';
    }

    // Activity section
    let activityHtml = '';
    if (activity && activity.success !== false) {
      const lastLogin = activity.lastLoginAt ? formatDateShort(activity.lastLoginAt) : '<span style="color:#6B7280;">never</span>';
      activityHtml =
        '<div style="font-size:11px; letter-spacing:0.08em; text-transform:uppercase; color:#6B7280; margin-bottom:8px; font-weight:600;">Activity</div>' +
        '<div style="display:grid; grid-template-columns:repeat(3, 1fr); gap:8px; margin-bottom:14px;">' +
          kpiTile('Last 7 days', activity.logins7d, '#1F8A4C', 'logins') +
          kpiTile('Last 30 days', activity.logins30d, '#4a6fa5', 'logins') +
          kpiTile('Total actions (30d)', activity.totalActions30d, '#7C3AED', '') +
        '</div>' +
        '<div style="font-size:12px; color:#6B7280; margin-bottom:8px;">Last login: <strong style="color:#0F1720;">' + (typeof lastLogin === 'string' && lastLogin.indexOf('<') === 0 ? lastLogin : esc(lastLogin)) + '</strong></div>';

      if (activity.topActions && activity.topActions.length > 0) {
        activityHtml += '<div style="font-size:11px; letter-spacing:0.08em; text-transform:uppercase; color:#6B7280; margin:14px 0 6px 0; font-weight:600;">Most-used features (30d)</div>';
        const max = Math.max(...activity.topActions.map(a => a.count));
        activityHtml += activity.topActions.map(a => {
          const pct = max > 0 ? (a.count / max) * 100 : 0;
          return (
            '<div style="margin-bottom:6px;">' +
              '<div style="display:flex; justify-content:space-between; font-size:12px; margin-bottom:2px;">' +
                '<span>' + esc(prettyAction(a.action)) + '</span>' +
                '<span style="color:#6B7280; font-family:\'IBM Plex Mono\',monospace;">' + a.count + '</span>' +
              '</div>' +
              '<div style="background:#F0F0EC; border-radius:3px; height:6px; overflow:hidden;">' +
                '<div style="background:#4a6fa5; height:100%; width:' + pct.toFixed(1) + '%;"></div>' +
              '</div>' +
            '</div>'
          );
        }).join('');
      } else {
        activityHtml += '<div style="font-size:12px; color:#6B7280; padding:8px 0; font-style:italic;">No actions recorded in the last 30 days beyond logins.</div>';
      }
    }

    body.innerHTML = adHtml + activityHtml;
  }

  function kpiTile(label, value, color, suffix) {
    return (
      '<div style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:10px; text-align:center;">' +
        '<div style="font-size:10px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280;">' + esc(label) + '</div>' +
        '<div style="font-size:22px; font-weight:600; color:' + color + '; margin-top:2px;">' + (value == null ? '—' : value) + '</div>' +
        (suffix ? '<div style="font-size:10px; color:#6B7280;">' + esc(suffix) + '</div>' : '') +
      '</div>'
    );
  }

  function prettyAction(a) {
    if (!a) return '';
    return a.replace(/_/g, ' ').toLowerCase().replace(/\b[a-z]/g, c => c.toUpperCase());
  }

  function formatDateShort(iso) {
    if (!iso) return '';
    try {
      const d = new Date(iso);
      if (isNaN(d.getTime())) return iso;
      const now = new Date();
      const diffMs = now - d;
      const diffH = Math.round(diffMs / (60 * 60 * 1000));
      if (diffH < 24) return diffH + 'h ago';
      const diffD = Math.round(diffH / 24);
      if (diffD < 14) return diffD + 'd ago';
      const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
      return months[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear();
    } catch (e) { return iso; }
  }

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  // -------------------------------------------------------------------
  // Activity-log export — admin only
  // -------------------------------------------------------------------
  function showExportButtonIfAdmin() {
    fetch('/api/auth/session', { credentials: 'same-origin' })
      .then(r => r.json())
      .then(s => {
        const btn = document.getElementById('permsActivityExportBtn');
        if (btn && s && s.isPlmAdmin) btn.style.display = 'inline-block';
      })
      .catch(() => { /* silent — button stays hidden if we can't confirm admin */ });
  }

  window.permsOpenActivityExport = function () {
    const m = document.getElementById('activityExportModal');
    if (!m) return;
    // Default to last 30 days.
    const today = new Date();
    const thirtyAgo = new Date(today.getTime() - 29 * 24 * 60 * 60 * 1000);
    const iso = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    document.getElementById('permsExportFrom').value = iso(thirtyAgo);
    document.getElementById('permsExportTo').value = iso(today);
    m.style.display = 'flex';
  };

  window.permsCloseActivityExport = function () {
    const m = document.getElementById('activityExportModal');
    if (m) m.style.display = 'none';
  };

  window.permsRunActivityExport = function () {
    const from = document.getElementById('permsExportFrom').value;
    const to = document.getElementById('permsExportTo').value;
    if (!from || !to) { appAlert('Pick a from and to date.'); return; }
    if (from > to) { appAlert('From date must be on or before To date.'); return; }
    // Trigger browser download via plain navigation. Cookie is shared.
    const url = '/api/permissions/stats/export?from=' + encodeURIComponent(from) + '&to=' + encodeURIComponent(to);
    window.location.href = url;
    permsCloseActivityExport();
  };

  // Hook: when User Permissions tab is opened, also load the traffic chart.
  document.addEventListener('click', function (e) {
    if (!e || !e.target) return;
    const t = e.target;
    const onClickAttr = t.getAttribute && t.getAttribute('onclick');
    if (t.id === 'tabPermissions' || (onClickAttr && onClickAttr.indexOf("'permissions'") >= 0)) {
      setTimeout(() => { permsLoadTrafficChart(); showExportButtonIfAdmin(); }, 200);
    }
  }, true);
})();
