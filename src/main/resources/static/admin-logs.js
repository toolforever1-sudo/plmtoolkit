// =====================================================================
//  admin-logs.js — Admin → Server Logs viewer.
//
//  Renders a modal with a dropdown of available log files in
//  D:\plm-toolkit\logs\ (toolkit + agile-service + watchdog + deploy),
//  shows a tail of the picked file, and lets the user grep with a
//  keyword. Backed by /api/admin/logs (controller is admin-only).
// =====================================================================
(function () {
  'use strict';

  var MODAL_ID = 'adminLogsModal';

  window.adminLogsOpen = function () {
    ensureModal();
    document.getElementById(MODAL_ID).style.display = 'flex';
    loadLogList();
  };

  function ensureModal() {
    if (document.getElementById(MODAL_ID)) return;
    var m = document.createElement('div');
    m.id = MODAL_ID;
    m.style.cssText = 'position:fixed; inset:0; background:rgba(15,23,32,0.5); z-index:1000; display:none; align-items:center; justify-content:center;';
    m.innerHTML =
      '<div style="background:#fff; width:90%; max-width:1100px; max-height:88vh; border-radius:8px; border:1px solid #E8E6DF; display:flex; flex-direction:column;">' +
        '<div style="padding:14px 18px; border-bottom:1px solid #E8E6DF; display:flex; align-items:center; justify-content:space-between;">' +
          '<div>' +
            '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:18px; font-weight:600;">Server Logs</div>' +
            '<div id="adminLogsDirLabel" style="font-size:11px; color:#6B7280; margin-top:2px;"></div>' +
          '</div>' +
          '<button onclick="adminLogsClose()" style="background:none; border:none; font-size:22px; color:#6B7280; cursor:pointer; line-height:1;">&times;</button>' +
        '</div>' +
        '<div style="padding:12px 18px; border-bottom:1px solid #E8E6DF; display:flex; gap:10px; flex-wrap:wrap; align-items:center;">' +
          '<label style="font-size:11px; color:#6B7280;">File</label>' +
          '<select id="adminLogsFile" onchange="adminLogsRefresh()" style="padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px; font-size:12px; min-width:240px;"></select>' +
          '<label style="font-size:11px; color:#6B7280;">Lines</label>' +
          '<input type="number" id="adminLogsLines" value="500" min="1" max="5000" style="width:80px; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px; font-size:12px;">' +
          '<label style="font-size:11px; color:#6B7280;">Search</label>' +
          '<input type="text" id="adminLogsQuery" placeholder="optional keyword…" style="flex:1; min-width:200px; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px; font-size:12px;" onkeydown="if(event.key===\'Enter\')adminLogsRefresh()">' +
          '<label style="font-size:11px; color:#6B7280;">±Ctx</label>' +
          '<input type="number" id="adminLogsContext" value="0" min="0" max="20" style="width:60px; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px; font-size:12px;">' +
          '<button onclick="adminLogsRefresh()" style="background:#0F1720; color:#fff; border:none; padding:6px 14px; border-radius:4px; font-size:12px; font-weight:600; cursor:pointer;">Refresh</button>' +
        '</div>' +
        '<div id="adminLogsMeta" style="padding:6px 18px; font-size:11px; color:#6B7280; background:#FAFAF7;"></div>' +
        '<pre id="adminLogsBody" style="flex:1; overflow:auto; margin:0; padding:14px 18px; background:#0F1720; color:#e6e6e6; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px; line-height:1.45; white-space:pre-wrap; word-break:break-all;">Loading…</pre>' +
      '</div>';
    document.body.appendChild(m);
  }

  window.adminLogsClose = function () {
    var m = document.getElementById(MODAL_ID);
    if (m) m.style.display = 'none';
  };

  function loadLogList() {
    var sel = document.getElementById('adminLogsFile');
    sel.innerHTML = '<option>Loading…</option>';
    fetch('/api/admin/logs', { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) {
          sel.innerHTML = '<option>'+ esc(data.message || data.error || 'Failed to list logs') +'</option>';
          document.getElementById('adminLogsBody').textContent = '';
          return;
        }
        var dir = document.getElementById('adminLogsDirLabel');
        if (dir) dir.textContent = data.directory || '';
        sel.innerHTML = '';
        (data.logs || []).forEach(function (f) {
          var o = document.createElement('option');
          o.value = f.name;
          o.textContent = f.name + ' (' + humanSize(f.sizeBytes) + ')';
          sel.appendChild(o);
        });
        // Pick the most recently modified by default. The list endpoint sorts
        // descending by name so plm-toolkit.log is usually first; honor whatever
        // the server returned.
        if (sel.options.length > 0) {
          sel.selectedIndex = 0;
          adminLogsRefresh();
        } else {
          document.getElementById('adminLogsBody').textContent = '(no logs found)';
        }
      })
      .catch(function (err) {
        sel.innerHTML = '<option>'+ esc('Failed: ' + err.message) +'</option>';
      });
  }

  window.adminLogsRefresh = function () {
    var sel = document.getElementById('adminLogsFile');
    var name = sel && sel.value;
    if (!name) return;
    var lines = parseInt(document.getElementById('adminLogsLines').value, 10) || 500;
    var q = document.getElementById('adminLogsQuery').value.trim();
    var ctx = parseInt(document.getElementById('adminLogsContext').value, 10) || 0;
    var qs = 'lines=' + lines + (q ? '&q=' + encodeURIComponent(q) : '') + (ctx ? '&context=' + ctx : '');
    document.getElementById('adminLogsBody').textContent = 'Loading…';
    document.getElementById('adminLogsMeta').textContent = '';
    fetch('/api/admin/logs/' + encodeURIComponent(name) + '?' + qs, { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) {
          document.getElementById('adminLogsBody').textContent = data.error || data.message || 'Failed to load';
          return;
        }
        var meta = data.name + '  ·  size ' + humanSize(data.sizeBytes) +
                   '  ·  modified ' + (data.modifiedUtc || '?') +
                   '  ·  ' + data.returnedLines + ' / ' + data.requestedLines + ' lines' +
                   (data.filter ? '  ·  filter "' + data.filter + '"' : '');
        document.getElementById('adminLogsMeta').textContent = meta;
        document.getElementById('adminLogsBody').textContent = data.text || '(empty)';
        // Scroll to the bottom so the most recent lines are visible without
        // needing to scroll — matches "tail -f" muscle memory.
        var pre = document.getElementById('adminLogsBody');
        pre.scrollTop = pre.scrollHeight;
      })
      .catch(function (err) {
        document.getElementById('adminLogsBody').textContent = 'Failed: ' + err.message;
      });
  };

  function humanSize(n) {
    if (n == null || n < 0) return '?';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
    return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
})();
