// =====================================================================
//  admin-file-archive.js — Admin → File Archive viewer.
//
//  Shows every captured upload (bytes kept 30 days) and download
//  (metadata-only, hash kept 90 days). Filter by user, feature,
//  direction, filename. Download archived upload bytes; mark entries
//  permanent so the purge job leaves them alone.
// =====================================================================
(function () {
  'use strict';

  var MODAL_ID = 'adminFileArchiveModal';

  window.adminFileArchiveOpen = function () {
    ensureModal();
    document.getElementById(MODAL_ID).style.display = 'flex';
    refresh();
  };

  function ensureModal() {
    if (document.getElementById(MODAL_ID)) return;
    var m = document.createElement('div');
    m.id = MODAL_ID;
    m.style.cssText = 'position:fixed; inset:0; background:rgba(15,23,32,0.5); z-index:1000; display:none; align-items:center; justify-content:center;';
    m.innerHTML =
      '<div style="background:#fff; width:96%; max-width:1300px; max-height:92vh; border-radius:8px; border:1px solid #E8E6DF; display:flex; flex-direction:column;">' +
        '<div style="padding:14px 18px; border-bottom:1px solid #E8E6DF; display:flex; align-items:center; justify-content:space-between;">' +
          '<div>' +
            '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:18px; font-weight:600;">File Archive</div>' +
            '<div style="font-size:11px; color:#6B7280; margin-top:2px;">Every user upload (bytes kept 30 days) and download (metadata-only, 90 days).</div>' +
          '</div>' +
          '<button onclick="adminFileArchiveClose()" style="background:none; border:none; font-size:22px; color:#6B7280; cursor:pointer; line-height:1;">&times;</button>' +
        '</div>' +
        '<div style="padding:10px 18px; border-bottom:1px solid #E8E6DF; display:flex; gap:10px; flex-wrap:wrap; align-items:center; background:#FAFAF7;">' +
          '<label style="font-size:11px; color:#6B7280;">User</label>' +
          '<input id="afaUser" type="text" placeholder="username or name" style="padding:5px 8px; border:1px solid #E8E6DF; border-radius:4px; font-size:12px; width:140px;" onkeydown="if(event.key===\'Enter\')adminFileArchiveRefresh()">' +
          '<label style="font-size:11px; color:#6B7280;">Feature</label>' +
          '<input id="afaFeature" type="text" placeholder="agile-lookup, bom, …" style="padding:5px 8px; border:1px solid #E8E6DF; border-radius:4px; font-size:12px; width:140px;" onkeydown="if(event.key===\'Enter\')adminFileArchiveRefresh()">' +
          '<label style="font-size:11px; color:#6B7280;">Direction</label>' +
          '<select id="afaDirection" style="padding:5px 8px; border:1px solid #E8E6DF; border-radius:4px; font-size:12px;">' +
            '<option value="">All</option><option value="upload">Upload</option><option value="download">Download</option>' +
          '</select>' +
          '<label style="font-size:11px; color:#6B7280;">Filename</label>' +
          '<input id="afaFilename" type="text" placeholder="optional substring" style="padding:5px 8px; border:1px solid #E8E6DF; border-radius:4px; font-size:12px; flex:1; min-width:160px;" onkeydown="if(event.key===\'Enter\')adminFileArchiveRefresh()">' +
          '<button onclick="adminFileArchiveRefresh()" style="background:#0F1720; color:#fff; border:none; padding:6px 14px; border-radius:4px; font-size:12px; font-weight:600; cursor:pointer;">Refresh</button>' +
          '<button onclick="adminFileArchivePurgeNow()" title="Run the 30/90-day purge now" style="background:#fff; color:#B8342B; border:1px solid #B8342B; padding:6px 12px; border-radius:4px; font-size:12px; cursor:pointer;">Purge now</button>' +
        '</div>' +
        '<div id="afaMeta" style="padding:6px 18px; font-size:11px; color:#6B7280;"></div>' +
        '<div style="flex:1; overflow:auto;">' +
          '<table id="afaTable" style="width:100%; border-collapse:collapse; font-size:12px;">' +
            '<thead style="position:sticky; top:0;"><tr style="background:#2c3e50; color:#fff;">' +
              '<th style="text-align:left; padding:6px 8px; border-bottom:1px solid #444;">Timestamp (UTC)</th>' +
              '<th style="text-align:left; padding:6px 8px; border-bottom:1px solid #444;">User</th>' +
              '<th style="text-align:left; padding:6px 8px; border-bottom:1px solid #444;">Feature</th>' +
              '<th style="text-align:left; padding:6px 8px; border-bottom:1px solid #444;">Filename</th>' +
              '<th style="text-align:right; padding:6px 8px; border-bottom:1px solid #444;">Size</th>' +
              '<th style="text-align:left; padding:6px 8px; border-bottom:1px solid #444;">Dir</th>' +
              '<th style="text-align:left; padding:6px 8px; border-bottom:1px solid #444;">SHA-256</th>' +
              '<th style="text-align:left; padding:6px 8px; border-bottom:1px solid #444;">Actions</th>' +
            '</tr></thead>' +
            '<tbody id="afaBody"><tr><td colspan="8" style="padding:14px; color:#6B7280;">Loading…</td></tr></tbody>' +
          '</table>' +
        '</div>' +
      '</div>';
    document.body.appendChild(m);
  }

  window.adminFileArchiveClose = function () {
    var m = document.getElementById(MODAL_ID);
    if (m) m.style.display = 'none';
  };

  window.adminFileArchiveRefresh = function () { refresh(); };

  function refresh() {
    var q = [
      ['user', val('afaUser')],
      ['feature', val('afaFeature')],
      ['direction', val('afaDirection')],
      ['filename', val('afaFilename')],
      ['limit', '500']
    ].filter(function (kv) { return kv[1]; })
      .map(function (kv) { return kv[0] + '=' + encodeURIComponent(kv[1]); }).join('&');
    document.getElementById('afaBody').innerHTML =
      '<tr><td colspan="8" style="padding:14px; color:#6B7280;">Loading…</td></tr>';
    fetch('/api/admin/file-archive' + (q ? '?' + q : ''), { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) {
          document.getElementById('afaBody').innerHTML =
            '<tr><td colspan="8" style="padding:14px; color:#B8342B;">' +
            esc(data.error || data.message || 'Failed') + '</td></tr>';
          return;
        }
        document.getElementById('afaMeta').textContent =
          'Showing ' + data.entries.length + ' of ' + data.totalScanned + ' total events';
        renderRows(data.entries);
      })
      .catch(function (err) {
        document.getElementById('afaBody').innerHTML =
          '<tr><td colspan="8" style="padding:14px; color:#B8342B;">' +
          esc('Failed: ' + err.message) + '</td></tr>';
      });
  }

  function renderRows(entries) {
    if (!entries.length) {
      document.getElementById('afaBody').innerHTML =
        '<tr><td colspan="8" style="padding:14px; color:#6B7280;">No matches.</td></tr>';
      return;
    }
    var html = entries.map(function (e) {
      var isUpload = e.direction === 'upload';
      var canDownload = isUpload && !e.purged;
      var pinIcon = e.permanent ? '\uD83D\uDCCC' : '\u2606';
      var rowBg = e.purged ? 'background:#FAFAF7;' : '';
      return '<tr style="border-bottom:1px solid #E8E6DF; ' + rowBg + '">' +
        '<td style="padding:5px 8px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; white-space:nowrap;">' +
          esc((e.ts || '').replace('T', ' ').replace('Z', '')) + '</td>' +
        '<td style="padding:5px 8px;">' + esc(e.displayName || e.user || '') +
          (e.displayName && e.user && e.displayName !== e.user
            ? ' <span style="color:#6B7280; font-size:10px;">(' + esc(e.user) + ')</span>' : '') + '</td>' +
        '<td style="padding:5px 8px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px;">' + esc(e.feature || '') + '</td>' +
        '<td style="padding:5px 8px;">' + esc(e.filename || '') +
          (e.purged ? ' <span style="color:#C7801B; font-size:10px;">(purged)</span>' : '') + '</td>' +
        '<td style="padding:5px 8px; text-align:right; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px;">' +
          humanSize(e.bytes) + '</td>' +
        '<td style="padding:5px 8px;">' +
          (isUpload ? '\u2B07\uFE0F <span style="font-size:10px; color:#6B7280;">up</span>' :
                       '\u2B06\uFE0F <span style="font-size:10px; color:#6B7280;">down</span>') + '</td>' +
        '<td style="padding:5px 8px;" title="' + esc(e.sha256 || '') + '">' +
          '<code style="font-size:10px; color:#6B7280; cursor:pointer;" onclick="navigator.clipboard.writeText(\'' +
            esc(e.sha256 || '') + '\'); this.textContent=\'(copied)\'; setTimeout(function(){location.reload()},1500);">' +
            esc((e.sha256 || '').substring(0, 12)) + '\u2026</code>' + '</td>' +
        '<td style="padding:5px 8px; white-space:nowrap;">' +
          (canDownload ? '<a href="/api/admin/file-archive/' + encodeURIComponent(e.id) +
            '" style="color:#4a6fa5; text-decoration:none; font-size:11px;">Download</a> &middot; ' : '') +
          '<a href="#" onclick="adminFileArchivePin(\'' + esc(e.id) + '\',' + (!e.permanent) +
            ',this);return false;" title="Toggle permanent retention" style="text-decoration:none; font-size:14px;">' +
            pinIcon + '</a>' +
        '</td>' +
      '</tr>';
    }).join('');
    document.getElementById('afaBody').innerHTML = html;
  }

  window.adminFileArchivePin = function (id, permanent, linkEl) {
    fetch('/api/admin/file-archive/' + encodeURIComponent(id) + '/pin', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ permanent: permanent })
    })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (!d.success) {
          appAlert(d.error || 'Pin failed.');
          return;
        }
        if (linkEl) linkEl.textContent = d.permanent ? '\uD83D\uDCCC' : '\u2606';
      })
      .catch(function (err) { appAlert('Pin failed: ' + err.message); });
  };

  window.adminFileArchivePurgeNow = function () {
    appConfirm('Run the 30/90-day purge now? Files older than 30 days will be deleted; index rows older than 90 days will be dropped.', { danger: true, okText: 'Purge now' }).then(function (ok) {
      if (!ok) return;
      fetch('/api/admin/file-archive/purge', { method: 'POST', credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) {
          if (!d.success) { appAlert(d.error || 'Purge failed.'); return; }
          appAlert('Purge done — ' + d.bytesPurged + ' byte-only purges, ' + d.rowsDropped + ' rows dropped, ' + d.totalAfter + ' kept.');
          refresh();
        })
        .catch(function (err) { appAlert('Purge failed: ' + err.message); });
    });
  };

  function val(id) { var el = document.getElementById(id); return el ? el.value.trim() : ''; }

  function humanSize(n) {
    if (n == null || n < 0) return '?';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
    return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
})();
