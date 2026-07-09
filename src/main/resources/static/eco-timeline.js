// ECO Timeline tab. Given item + date range, lists released ECOs that changed
// any component in the indented BOM. Talks to /api/eco-timeline/*.
(function () {
  var lastRows = [];          // unfiltered rows from the server
  var byEco = [];             // rows folded into one entry per ECO (release-date order)
  var colFilters = {};        // flat view: column key -> lowercase filter text
  var sortKey = null, sortDir = 1;
  var activeEco = '';         // timeline/drawer filter, lowercase ECO #, '' = all
  var view = 'group';         // 'group' | 'flat' — persisted in localStorage
  var lastQueryMs = 0;        // moved off the KPI tiles into the quiet status line

  // Flat view = the Excel/email export shape (kept in sync with the export columns).
  var COLS = [
    { key: 'path', label: 'Path' },
    { key: 'component', label: 'Component #' },
    { key: 'primaryNumber', label: 'Primary #' },
    { key: 'componentDescription', label: 'Description' },
    { key: 'ecoNumber', label: 'ECO #' },
    { key: 'ecoDescription', label: 'ECO Description' },
    { key: 'ecoReleaseDate', label: 'Release' },
    { key: 'changeType', label: 'Change Type' },
    { key: 'detail', label: 'Detail' }
  ];

  var winFromMs = 0, winToMs = 0;   // search window bounds, for timeline positioning

  // Color + severity for the timeline markers and pills. Most-impactful change wins.
  var TYPE_COLOR = {
    'Removed': '#B8342B', 'Primary number changed': '#7C3AED', 'Modified': '#C7801B',
    'Quantity changed': '#C7801B', 'Find # changed': '#1a3a5c', 'Notes changed': '#888',
    'Added': '#1F8A4C'
  };
  var SEVERITY = ['Removed', 'Primary number changed', 'Modified', 'Quantity changed',
                  'Find # changed', 'Added', 'Notes changed'];

  var RECENT_KEY = 'etRecentItems', VIEW_KEY = 'etView';

  function val(el) { return (document.getElementById(el).value || '').trim(); }
  function agileBase() { return window.AGILE_WEBCLIENT_URL || 'https://plm.sandisk.com/Agile'; }

  window.etRun = function () {
    var item = val('etItemInput'), from = val('etFromInput'), to = val('etToInput');
    var maxDepth = val('etMaxDepth') || '25';
    if (!item) { appAlert('Enter an item number.'); return; }
    if (!from || !to) { appAlert('Pick both a start and an end date.'); return; }
    winFromMs = Date.parse(from); winToMs = Date.parse(to);
    activeEco = ''; colFilters = {}; sortKey = null;
    setStatus('Querying live Agile…');
    document.getElementById('etResults').innerHTML = '';
    var qs = 'item=' + encodeURIComponent(item) + '&from=' + from + '&to=' + to + '&maxDepth=' + maxDepth;
    fetch('/api/eco-timeline/query?' + qs)
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.error) { setStatus(''); appAlert(data.error); return; }
        lastRows = data.rows || [];
        lastQueryMs = data.queryTimeMs || 0;
        buildByEco();
        pushRecent(item);
        renderKpis(data);
        render();
        document.getElementById('etExportBtn').style.display = lastRows.length ? '' : 'none';
        document.getElementById('etEmailBtn').style.display = lastRows.length ? '' : 'none';
        var notes = [];
        if (data.truncated) notes.push('result truncated (structure too large)');
        if (data.depthLimitReached) notes.push('max depth reached — increase Max Depth for deeper levels');
        var diag = lastRows.length ? (byEco.length + ' ECOs · ' + lastRows.length + ' change events · ' + lastQueryMs + ' ms') : '';
        setStatus(lastRows.length ? (notes.length ? '⚠ ' + notes.join('; ') + ' · ' + diag : diag)
                                  : 'No component changes found in this window.');
      })
      .catch(function (e) { setStatus(''); appAlert('Query failed: ' + e); });
  };

  window.etClear = function () {
    ['etItemInput', 'etFromInput', 'etToInput'].forEach(function (id) { document.getElementById(id).value = ''; });
    document.getElementById('etMaxDepth').value = '25';
    lastRows = []; byEco = []; colFilters = {}; sortKey = null; activeEco = '';
    document.getElementById('etResults').innerHTML = '';
    document.getElementById('etKpis').style.display = 'none';
    document.getElementById('etExportBtn').style.display = 'none';
    document.getElementById('etEmailBtn').style.display = 'none';
    setActivePreset(null);
    setStatus('');
  };

  function exportQs() {
    return 'item=' + encodeURIComponent(val('etItemInput')) + '&from=' + val('etFromInput') +
           '&to=' + val('etToInput') + '&maxDepth=' + (val('etMaxDepth') || '25');
  }
  window.etExport = function () { window.location = '/api/eco-timeline/export?' + exportQs(); };
  window.etEmail = function () {
    fetch('/api/eco-timeline/email?' + exportQs(), { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (d) { appAlert(d.message || (d.success ? 'Sent.' : 'Failed.')); })
      .catch(function (e) { appAlert('Email failed: ' + e); });
  };

  function setStatus(msg) { document.getElementById('etStatus').textContent = msg; }

  // ---- group rows into one entry per ECO ----
  function buildByEco() {
    var map = {};
    lastRows.forEach(function (r) {
      var k = r.ecoNumber || '?';
      if (!map[k]) {
        map[k] = {
          eco: k, kind: (k.indexOf('-') > 0 ? k.substring(0, k.indexOf('-')) : 'Change'),
          ms: r.releaseTsMillis || 0, date: r.ecoReleaseDate || '',
          desc: r.ecoDescription || '', status: r.ecoStatus || '', reason: r.ecoReason || '',
          rows: [], types: {}
        };
      }
      map[k].rows.push(r);
      map[k].types[r.changeType] = (map[k].types[r.changeType] || 0) + 1;
    });
    byEco = Object.keys(map).map(function (k) { return map[k]; });
    byEco.sort(function (a, b) { return a.ms - b.ms; });
  }

  // ---- KPIs (Change Events replaces the dev-facing Query Time tile) ----
  function renderKpis(data) {
    var el = document.getElementById('etKpis');
    el.style.display = 'flex';
    el.innerHTML =
      kpi('ECOs Found', data.ecoCount) +
      kpi('Change Events', lastRows.length, 'across the BOM') +
      kpi('Components Affected', data.componentCount) +
      kpi('Date Span', data.from + ' → ' + data.to);
  }
  function kpi(label, value, sub) {
    return '<div style="min-width:120px;"><div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.04em;">' +
      esc(label) + '</div><div style="font-size:20px; font-weight:700; color:#0F1720;">' + esc(value) + '</div>' +
      (sub ? '<div style="font-size:11px; color:#9ca3af;">' + esc(sub) + '</div>' : '') + '</div>';
  }

  function pill(type) {
    var map = {
      'Added': 'et-pill-added', 'Removed': 'et-pill-removed',
      'Primary number changed': 'et-pill-primary', 'Quantity changed': 'et-pill-qty',
      'Find # changed': 'et-pill-find', 'Notes changed': 'et-pill-notes', 'Modified': 'et-pill-mod'
    };
    return '<span class="et-pill ' + (map[type] || 'et-pill-mod') + '">' + esc(type) + '</span>';
  }
  function statusPill(status) {
    var s = (status || '').toLowerCase();
    var cls = s.indexOf('released') !== -1 || s.indexOf('implement') !== -1 ? 'et-pill-added' : 'et-pill-notes';
    return status ? '<span class="et-pill ' + cls + '">' + esc(status) + '</span>' : '';
  }
  function pathHtml(p) {
    return esc(String(p || '')).replace(/ \/ /g, ' <span style="color:#9ca3af;">▸</span> ');
  }
  function summaryPills(types) {
    return Object.keys(types).map(function (t) {
      return '<span class="et-pill ' + pillCls(t) + '">' + esc(t) + ' ×' + types[t] + '</span>';
    }).join(' ');
  }
  function pillCls(type) {
    var map = {
      'Added': 'et-pill-added', 'Removed': 'et-pill-removed',
      'Primary number changed': 'et-pill-primary', 'Quantity changed': 'et-pill-qty',
      'Find # changed': 'et-pill-find', 'Notes changed': 'et-pill-notes', 'Modified': 'et-pill-mod'
    };
    return map[type] || 'et-pill-mod';
  }

  // ---- timeline strip: legend, quarter ticks, hover card, click-to-filter ----
  function renderTimeline() {
    if (!byEco.length) return '';
    var span = (winToMs > winFromMs) ? (winToMs - winFromMs) : 1;
    var legend = ['Removed', 'Primary number changed', 'Quantity changed', 'Find # changed', 'Added']
      .map(function (t) {
        return '<span style="display:inline-flex; align-items:center; gap:5px; font-size:11px; color:#4b5563;">' +
          '<i style="width:9px; height:9px; border-radius:50%; background:' + TYPE_COLOR[t] + ';"></i>' + esc(t) + '</span>';
      }).join('');

    var dots = byEco.map(function (e) {
      var pct = Math.max(0, Math.min(100, ((e.ms - winFromMs) / span) * 100));
      var dom = null;
      for (var si = 0; si < SEVERITY.length; si++) { if (e.types[SEVERITY[si]]) { dom = SEVERITY[si]; break; } }
      if (!dom) dom = Object.keys(e.types)[0];
      var color = TYPE_COLOR[dom] || '#856404';
      var active = activeEco && e.eco.toLowerCase() === activeEco;
      var count = e.rows.length;
      var sz = (count >= 3 ? 16 : 12) + (active ? 3 : 0);
      return '<div class="et-dot" data-eco="' + esc(e.eco) + '" ' +
        'style="position:absolute; left:' + pct.toFixed(2) + '%; top:22px; transform:translate(-50%,-50%); ' +
        'width:' + sz + 'px; height:' + sz + 'px; border-radius:50%; background:' + color + '; ' +
        'border:2px solid ' + (active ? '#0F1720' : '#fff') + '; box-shadow:0 1px 3px rgba(0,0,0,.28); cursor:pointer; z-index:' + (active ? 6 : 2) + ';"></div>';
    }).join('');

    var ticks = quarterTicks(winFromMs, winToMs, span);
    var hint = activeEco ? ' <span style="color:#4a6fa5; text-transform:none; letter-spacing:0;">(filtered to ' + esc(activeEco.toUpperCase()) + ' — click its dot again to clear)</span>' : '';
    return '<div class="et-tl-card">' +
      '<div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px; margin-bottom:6px;">' +
      '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.04em;">ECO Timeline — hover a marker for details, click to filter' + hint + '</div>' +
      '<div style="display:flex; gap:14px; flex-wrap:wrap;">' + legend + '</div></div>' +
      '<div style="position:relative; height:50px; margin-top:6px;">' +
      '<div style="position:absolute; left:0; top:22px; width:100%; height:2px; background:#E8E6DF;"></div>' +
      dots + ticks +
      '</div></div>';
  }

  function quarterTicks(fromMs, toMs, span) {
    if (!fromMs || !toMs) return '';
    var out = '', d = new Date(fromMs);
    d.setDate(1);
    // advance to the next quarter boundary (Jan/Apr/Jul/Oct)
    var m = d.getMonth(); d.setMonth(m - (m % 3));
    var html = [];
    var guard = 0;
    while (d.getTime() <= toMs && guard++ < 40) {
      if (d.getTime() >= fromMs) {
        var pct = ((d.getTime() - fromMs) / span) * 100;
        var lbl = ['Jan', 'Apr', 'Jul', 'Oct'][d.getMonth() / 3] + " '" + String(d.getFullYear()).slice(2);
        html.push('<div style="position:absolute; top:30px; left:' + pct.toFixed(2) + '%; transform:translateX(-50%); font-size:10px; color:#9ca3af; font-family:\'IBM Plex Mono\',monospace;">' + lbl + '</div>');
      }
      d.setMonth(d.getMonth() + 3);
    }
    return html.join('');
  }

  // ---- view toggle bar ----
  function viewBar() {
    return '<div style="display:flex; align-items:center; gap:12px; margin:14px 0 10px 0;">' +
      '<div class="et-seg">' +
      '<button data-view="group" class="' + (view === 'group' ? 'on' : '') + '">Grouped by ECO</button>' +
      '<button data-view="flat" class="' + (view === 'flat' ? 'on' : '') + '">Flat table</button>' +
      '</div></div>';
  }

  // ---- grouped view ----
  function renderGroups() {
    var list = activeEco ? byEco.filter(function (e) { return e.eco.toLowerCase() === activeEco; }) : byEco;
    if (!list.length) return '<p style="color:#6B7280; padding:10px 0;">No ECOs match the current filter.</p>';
    return list.map(function (e, i) {
      var open = (activeEco || i === 0) ? ' open' : '';
      var rows = e.rows.map(function (r) {
        return '<tr>' +
          '<td>' + pathHtml(r.path) + '</td>' +
          '<td style="font-family:\'IBM Plex Mono\',monospace; font-weight:500;">' + esc(r.component) + '</td>' +
          '<td style="font-family:\'IBM Plex Mono\',monospace; color:#4b5563;">' + esc(r.primaryNumber || '') + '</td>' +
          '<td style="color:#4b5563;">' + esc(r.componentDescription) + '</td>' +
          '<td>' + pill(r.changeType) + '</td>' +
          '<td style="color:#4b5563;">' + esc(r.detail) + '</td>' +
          '</tr>';
      }).join('');
      return '<div class="et-group' + open + '" data-eco="' + esc(e.eco) + '">' +
        '<div class="et-group-head" data-toggle="1">' +
        '<span class="et-caret">▸</span>' +
        '<div style="flex:1; min-width:0;">' +
        '<div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">' +
        '<span style="font-family:\'IBM Plex Mono\',monospace; font-weight:600; font-size:14px;">' + esc(e.eco) + '</span>' +
        '<span class="et-kind">' + esc(e.kind) + '</span>' + statusPill(e.status) +
        '<span style="margin-left:auto; font-family:\'IBM Plex Mono\',monospace; font-size:12px; color:#6B7280;">' + esc(e.date) + '</span>' +
        '</div>' +
        (e.desc ? '<div style="font-size:13.5px; color:#0F1720; margin-top:5px;">' + esc(e.desc) + '</div>' : '') +
        (e.reason ? '<div style="font-size:12px; color:#6B7280; margin-top:4px;"><b style="color:#4b5563;">Reason for change:</b> ' + esc(e.reason) + '</div>' : '') +
        '<div style="display:flex; gap:6px; flex-wrap:wrap; margin-top:9px; align-items:center;">' + summaryPills(e.types) +
        '<span class="et-drawer-link" data-open="' + esc(e.eco) + '">View change order →</span>' +
        '</div></div></div>' +
        '<div class="et-group-body">' +
        '<table class="et-ct"><thead><tr><th>Path</th><th>Component #</th><th>Primary #</th><th>Description</th><th>Change</th><th>Detail</th></tr></thead>' +
        '<tbody>' + rows + '</tbody></table></div>' +
        '</div>';
    }).join('');
  }

  // ---- flat view (sortable + per-column filter; ECO # opens the drawer) ----
  function flatRows() {
    var rows = lastRows.filter(function (r) {
      if (activeEco && (r.ecoNumber || '').toLowerCase() !== activeEco) return false;
      return COLS.every(function (c) {
        var f = colFilters[c.key];
        if (!f) return true;
        return String(r[c.key] == null ? '' : r[c.key]).toLowerCase().indexOf(f) !== -1;
      });
    });
    if (sortKey) {
      rows = rows.slice().sort(function (a, b) {
        var va = a[sortKey], vb = b[sortKey];
        return (va < vb ? -1 : va > vb ? 1 : 0) * sortDir;
      });
    }
    return rows;
  }
  function renderFlat() {
    var rows = flatRows();
    var html = '<table class="et-table" style="width:100%; border-collapse:collapse; font-size:12.5px;"><thead><tr>';
    COLS.forEach(function (c) {
      var arrow = sortKey === c.key ? (sortDir === 1 ? ' ▲' : ' ▼') : '';
      html += '<th data-col="' + c.key + '" style="background:#2c3e50; color:#fff; text-align:left; padding:7px 9px; cursor:pointer; white-space:nowrap;">' + esc(c.label) + arrow + '</th>';
    });
    html += '</tr><tr>';
    COLS.forEach(function (c) {
      html += '<th style="background:#f4f5f7; padding:3px 6px;"><input data-filter="' + c.key + '" value="' + esc(colFilters[c.key] || '') +
        '" placeholder="filter" style="width:100%; box-sizing:border-box; font-size:11px; padding:3px 5px; border:1px solid #E8E6DF; border-radius:4px;"></th>';
    });
    html += '</tr></thead><tbody>';
    rows.forEach(function (r, i) {
      html += '<tr style="background:' + (i % 2 ? '#ffffff' : '#FAFAF7') + ';">';
      COLS.forEach(function (c) {
        var cell;
        if (c.key === 'changeType') cell = pill(r[c.key]);
        else if (c.key === 'path') cell = pathHtml(r[c.key]);
        else if (c.key === 'ecoNumber') cell = '<span class="et-drawer-link" data-open="' + esc(r.ecoNumber) + '">' + esc(r.ecoNumber) + '</span>';
        else cell = esc(String(r[c.key] == null ? '' : r[c.key]));
        html += '<td style="padding:6px 9px; border-bottom:1px solid #eee;">' + cell + '</td>';
      });
      html += '</tr>';
    });
    html += '</tbody></table>';
    if (!rows.length) html += '<p style="color:#6B7280; padding:10px 0;">No rows match the current filters.</p>';
    return html;
  }

  // ---- orchestrate ----
  function render() {
    var html = renderTimeline() + viewBar() + (view === 'group' ? renderGroups() : renderFlat());
    var container = document.getElementById('etResults');
    container.innerHTML = html;
    wire(container);
  }

  function wire(container) {
    // view toggle
    container.querySelectorAll('.et-seg button[data-view]').forEach(function (b) {
      b.addEventListener('click', function () { setView(b.getAttribute('data-view')); });
    });
    // timeline dots: hover card + click-to-filter
    container.querySelectorAll('.et-dot').forEach(function (m) {
      m.addEventListener('mouseenter', function (ev) { showPop(ev, m.getAttribute('data-eco')); });
      m.addEventListener('mouseleave', hidePop);
      m.addEventListener('click', function () {
        var eco = (m.getAttribute('data-eco') || '').toLowerCase();
        activeEco = (activeEco === eco) ? '' : eco;
        hidePop(); render();
      });
    });
    // grouped: header toggle + drawer link
    container.querySelectorAll('.et-group-head[data-toggle]').forEach(function (h) {
      h.addEventListener('click', function (ev) {
        if (ev.target.closest('.et-drawer-link')) return;
        h.parentElement.classList.toggle('open');
      });
    });
    container.querySelectorAll('.et-drawer-link[data-open]').forEach(function (a) {
      a.addEventListener('click', function (ev) { ev.stopPropagation(); openDrawer(a.getAttribute('data-open')); });
    });
    // flat: sort + filter
    container.querySelectorAll('th[data-col]').forEach(function (th) {
      th.addEventListener('click', function () {
        var k = th.getAttribute('data-col');
        if (sortKey === k) sortDir = -sortDir; else { sortKey = k; sortDir = 1; }
        render();
      });
    });
    container.querySelectorAll('input[data-filter]').forEach(function (inp) {
      inp.addEventListener('input', function () {
        colFilters[inp.getAttribute('data-filter')] = inp.value.trim().toLowerCase();
        render();
      });
    });
  }

  function setView(v) {
    view = v;
    try { localStorage.setItem(VIEW_KEY, v); } catch (e) {}
    render();
  }

  // ---- hover card ----
  function showPop(ev, ecoNum) {
    var e = byEco.filter(function (x) { return x.eco === ecoNum; })[0];
    if (!e) return;
    var pop = document.getElementById('etPop');
    var breakdown = Object.keys(e.types).map(function (t) {
      return '<em style="font-style:normal; font-size:10px; background:rgba(255,255,255,.15); padding:1px 6px; border-radius:6px;">' + esc(t) + ' ×' + e.types[t] + '</em>';
    }).join(' ');
    pop.innerHTML = '<b style="font-family:\'IBM Plex Mono\',monospace;">' + esc(e.eco) + '</b> ' +
      '<span style="color:#9ab; font-size:10px;">' + esc(e.kind) + '</span>' +
      '<div style="color:#bcd; font-size:10.5px; margin:2px 0 5px 0;">' + esc(e.date) + ' · ' + e.rows.length + ' change' + (e.rows.length > 1 ? 's' : '') + '</div>' +
      (e.desc ? '<div style="color:#e2e2ea; line-height:1.4;">' + esc(e.desc) + '</div>' : '') +
      '<div style="margin-top:5px; display:flex; flex-wrap:wrap; gap:4px;">' + breakdown + '</div>';
    pop.style.opacity = '1';
    var r = ev.target.getBoundingClientRect();
    pop.style.left = '0px'; pop.style.top = '0px';
    var pr = pop.getBoundingClientRect();
    var x = Math.min(Math.max(8, r.left + r.width / 2 - pr.width / 2), window.innerWidth - pr.width - 8);
    var y = r.top - pr.height - 10; if (y < 8) y = r.bottom + 10;
    pop.style.left = x + 'px'; pop.style.top = y + 'px';
  }
  function hidePop() { var p = document.getElementById('etPop'); if (p) p.style.opacity = '0'; }

  // ---- detail drawer ----
  window.etCloseDrawer = function () {
    document.getElementById('etDrawer').classList.remove('show');
    document.getElementById('etScrim').classList.remove('show');
  };
  function openDrawer(ecoNum) {
    var e = byEco.filter(function (x) { return x.eco === ecoNum; })[0];
    if (!e) return;
    var type = e.kind || 'Change';
    var link = agileBase() + '/object/' + encodeURIComponent(type) + '/' + encodeURIComponent(e.eco);
    var comps = e.rows.map(function (r) {
      return '<div style="display:flex; align-items:center; gap:8px; padding:7px 0; border-bottom:1px solid #eee; font-size:12.5px;">' +
        pill(r.changeType) + '<span style="font-family:\'IBM Plex Mono\',monospace; font-weight:500;">' + esc(r.component) + '</span>' +
        (r.primaryNumber ? '<span style="font-family:\'IBM Plex Mono\',monospace; color:#4a6fa5;" title="i2 primary">▸ ' + esc(r.primaryNumber) + '</span>' : '') +
        '<span style="color:#6B7280; flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">' + esc(r.componentDescription) + '</span></div>';
    }).join('');
    function drRow(label, value, mono) {
      return '<div style="margin-bottom:16px;"><div style="font-size:10.5px; letter-spacing:.08em; text-transform:uppercase; color:#6B7280; margin-bottom:4px;">' + esc(label) + '</div>' +
        '<div style="font-size:13.5px; color:#0F1720;' + (mono ? " font-family:'IBM Plex Mono',monospace;" : '') + '">' + value + '</div></div>';
    }
    document.getElementById('etDrNum').textContent = e.eco;
    document.getElementById('etDrBody').innerHTML =
      drRow('Type', esc(e.kind) + (e.status ? ' · ' + statusPill(e.status) : '')) +
      drRow('Released', esc(e.date), true) +
      (e.desc ? drRow('Description', esc(e.desc)) : '') +
      (e.reason ? drRow('Reason for change', esc(e.reason)) : '') +
      drRow('Affected components (' + e.rows.length + ')', comps) +
      '<a href="' + esc(link) + '" target="_blank" rel="noopener" class="et-agile-link">Open in Agile PLM ↗</a>';
    document.getElementById('etDrawer').classList.add('show');
    document.getElementById('etScrim').classList.add('show');
  }

  // ---- date-range presets ----
  window.etPreset = function (kind, btn) {
    var today = new Date();
    var to = iso(today), from;
    if (kind === '90') from = iso(daysAgo(90));
    else if (kind === '180') from = iso(daysAgo(180));
    else if (kind === '365') from = iso(daysAgo(365));
    else if (kind === 'ytd') from = '2025-01-01';
    else { from = '2000-01-01'; }
    document.getElementById('etFromInput').value = from;
    document.getElementById('etToInput').value = to;
    setActivePreset(btn);
  };
  function setActivePreset(btn) {
    document.querySelectorAll('#etPresets .et-preset').forEach(function (b) { b.classList.remove('active'); });
    if (btn) btn.classList.add('active');
  }
  function daysAgo(n) { var d = new Date(); d.setDate(d.getDate() - n); return d; }
  function iso(d) { return d.toISOString().slice(0, 10); }

  // ---- recent searches ----
  function getRecent() {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); } catch (e) { return []; }
  }
  function pushRecent(item) {
    var list = getRecent().filter(function (x) { return x.toLowerCase() !== item.toLowerCase(); });
    list.unshift(item);
    list = list.slice(0, 3);
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)); } catch (e) {}
    renderRecent();
  }
  function renderRecent() {
    var wrap = document.getElementById('etRecent');
    if (!wrap) return;
    var list = getRecent();
    if (!list.length) { wrap.style.display = 'none'; return; }
    wrap.style.display = 'flex';
    wrap.innerHTML = '<span style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.04em;">Recent</span>' +
      list.map(function (it) {
        return '<a data-recent="' + esc(it) + '" style="color:#4a6fa5; cursor:pointer; font-family:\'IBM Plex Mono\',monospace; font-size:11.5px;">' + esc(it) + '</a>';
      }).join('');
    wrap.querySelectorAll('a[data-recent]').forEach(function (a) {
      a.addEventListener('click', function () {
        document.getElementById('etItemInput').value = a.getAttribute('data-recent');
        etRun();
      });
    });
  }

  // ---- init ----
  function init() {
    try { var v = localStorage.getItem(VIEW_KEY); if (v === 'flat' || v === 'group') view = v; } catch (e) {}
    var presets = document.getElementById('etPresets');
    if (presets) {
      presets.querySelectorAll('.et-preset').forEach(function (b) {
        b.addEventListener('click', function () { etPreset(b.getAttribute('data-r'), b); });
      });
    }
    renderRecent();
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') etCloseDrawer(); });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch];
    });
  }
})();
