// =====================================================================
// Audit Trail (PT-50 / ECN-135744-PROJ + PT-54)
// Sub-tab under Change History. Backend: POST /api/audit-trail/query (JSON),
// POST /api/audit-trail/export (Excel .xlsx).
//
// PT-54: Source toggle (Item History vs Change History) drives both the
// SQL backend AND the column set rendered in the result table.
// =====================================================================

(function () {
  // Last successful response + query payload, kept in module scope so column
  // filters + Excel export can reuse them without re-querying.
  var lastRows = [];
  var lastQuery = null;
  var lastSource = 'item';

  // Column layouts per source. Each entry has a `key` (matches Result.row key
  // from the backend), `label` (table header), and an optional `mono` flag
  // for monospace rendering on the change-number / item-number column.
  var COLUMNS = {
    item: [
      { key: 'item_number', label: 'Item #',  mono: true,  maxWidth: '' },
      { key: 'user_name',   label: 'User',    mono: false, maxWidth: '' },
      { key: 'rev',         label: 'Rev',     mono: true,  maxWidth: '' },
      { key: 'local_date',  label: 'Date',    mono: false, maxWidth: '', noWrap: true },
      { key: 'details',     label: 'Details', mono: false, maxWidth: '420px' }
    ],
    change: [
      { key: 'change_number',   label: 'Change #',         mono: true,  maxWidth: '' },
      { key: 'action',          label: 'Action',           mono: false, maxWidth: '' },
      { key: 'prev_status',     label: 'Prev Status',      mono: false, maxWidth: '' },
      { key: 'next_status',     label: 'Next Status',      mono: false, maxWidth: '' },
      { key: 'user_name',       label: 'User',             mono: false, maxWidth: '' },
      { key: 'affected_object', label: 'Affected Object',  mono: true,  maxWidth: '' },
      { key: 'local_date',      label: 'Date',             mono: false, maxWidth: '', noWrap: true },
      { key: 'comments',        label: 'Comments',         mono: false, maxWidth: '260px' },
      { key: 'details',         label: 'Details',          mono: false, maxWidth: '320px' },
      { key: 'users_notified',  label: 'User(s) Notified', mono: false, maxWidth: '260px' }
    ]
  };

  function splitList(raw) {
    if (!raw) return [];
    return raw.split(/[,\s\n]+/).map(function (s) { return s.trim(); }).filter(Boolean);
  }

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function val(id) {
    var el = document.getElementById(id);
    return el ? el.value : '';
  }

  function currentSource() {
    var el = document.querySelector('input[name="atSource"]:checked');
    return el ? el.value : 'item';
  }

  /**
   * Source toggle handler — show/hide change-specific fields, update the
   * data-source-pill text and tooltip. Wired from the radio inputs' onchange.
   */
  window.auditTrailSourceChanged = function () {
    var src = currentSource();
    var isItem = (src === 'item');
    // Change-only fields: Change Number, Action/Status, Comments
    var changeGroup   = document.getElementById('atChangeGroup');
    var actionGroup   = document.getElementById('atActionGroup');
    var commentsGroup = document.getElementById('atCommentsGroup');
    if (changeGroup)   changeGroup.style.display   = isItem ? 'none' : '';
    if (actionGroup)   actionGroup.style.display   = isItem ? 'none' : '';
    if (commentsGroup) commentsGroup.style.display = isItem ? 'none' : '';

    var label   = document.getElementById('atSourceLabel');
    var tooltip = document.getElementById('atSourceTooltip');
    if (label)   label.textContent = isItem ? 'AGILE.item_history' : 'AGILE.change_history';
    if (tooltip) tooltip.innerHTML = isItem
      ? 'Field-level changes from the Oracle <strong>AGILE.item_history</strong> table, joined with <strong>AGILE.item</strong> for the item number.'
      : 'Workflow events from the Oracle <strong>AGILE.change_history</strong> table, joined with <strong>AGILE.change</strong> for the change number, and <strong>AGILE.nodetable</strong> for status names.';

    // Clear last results — they're tied to the previous source and the
    // column set won't match anyway.
    renderAuditTrailEmpty();
    lastRows = [];
    lastQuery = null;
  };

  function buildPayload() {
    var src = currentSource();
    var payload = {
      source:        src,
      itemNumbers:   splitList(val('atItemInput')),
      dateFrom:      val('atDateFrom') || null,
      dateTo:        val('atDateTo') || null,
      usernames:     splitList(val('atUserInput')),
      details:       val('atDetailsInput')
    };
    if (src === 'change') {
      payload.changeNumbers = splitList(val('atChangeInput'));
      payload.action   = val('atActionInput');
      payload.comments = val('atCommentsInput');
    } else {
      payload.changeNumbers = [];
    }
    return payload;
  }

  window.doAuditTrailSearch = function () {
    var payload = buildPayload();
    var btn = document.querySelector('#panelAuditTrail .btn-search');
    if (btn) { btn.disabled = true; btn.textContent = 'Searching\u2026'; }

    fetch('/api/audit-trail/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify(payload)
    }).then(function (r) {
      return r.json().then(function (d) { return { ok: r.ok, d: d }; });
    }).then(function (res) {
      if (btn) { btn.disabled = false; btn.textContent = 'Search'; }
      if (!res.ok || res.d.error) {
        renderAuditTrailEmpty();
        var msg = (res.d && res.d.error) ? res.d.error : 'Query failed.';
        if (typeof appAlert === 'function') appAlert(msg); else alert(msg);
        return;
      }
      lastQuery = payload;
      lastSource = res.d.source || payload.source || 'item';
      lastRows = Array.isArray(res.d.rows) ? res.d.rows : [];
      renderAuditTrailRows(res.d);
    }).catch(function (e) {
      if (btn) { btn.disabled = false; btn.textContent = 'Search'; }
      if (typeof appAlert === 'function') appAlert('Audit Trail query failed: ' + e.message);
      else alert('Audit Trail query failed: ' + e.message);
    });
  };

  window.doAuditTrailClear = function () {
    ['atItemInput','atChangeInput','atDateFrom','atDateTo','atUserInput',
     'atActionInput','atDetailsInput','atCommentsInput'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.value = '';
    });
    document.querySelectorAll('.at-col-filter').forEach(function (el) { el.value = ''; });
    lastRows = [];
    lastQuery = null;
    renderAuditTrailEmpty();
  };

  window.doAuditTrailExport = function () {
    if (!lastQuery) {
      if (typeof appAlert === 'function') appAlert('Run a search first, then export the results.');
      else alert('Run a search first, then export the results.');
      return;
    }
    var btn = document.getElementById('atExportBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Exporting\u2026'; }

    fetch('/api/audit-trail/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify(lastQuery)
    }).then(function (r) {
      if (!r.ok) {
        return r.json().then(function (d) { throw new Error(d.error || 'Export failed (' + r.status + ').'); });
      }
      return r.blob().then(function (blob) {
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        var ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
        a.download = 'audit-trail-' + lastSource + '-' + ts + '.xlsx';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
      });
    }).catch(function (e) {
      if (typeof appAlert === 'function') appAlert('Export failed: ' + e.message);
      else alert('Export failed: ' + e.message);
    }).then(function () {
      if (btn) { btn.disabled = false; btn.innerHTML = '\uD83D\uDCE5 Export to Excel'; }
    });
  };

  function renderAuditTrailEmpty() {
    var wrap = document.getElementById('atResultsWrap');
    if (wrap) wrap.style.display = 'none';
    var intro = document.getElementById('atIntro');
    if (intro) intro.style.display = '';
  }

  /**
   * Rebuild the THEAD (column headers + per-column filter row) for the current
   * source, then call applyColumnFilters() to populate the rows. PT-54: this is
   * where the schema-aware column set is materialised.
   *
   * PT-54 fix: the per-column filter inputs previously had placeholder="filter\\u2026"
   * which rendered literally in the input (HTML doesn't interpret JS escapes inside
   * attribute values). We now build placeholders via DOM properties so the real
   * \u2026 character lands cleanly.
   */
  function rebuildThead(source) {
    var thead = document.getElementById('atResultsThead');
    if (!thead) return;
    var cols = COLUMNS[source] || COLUMNS.change;

    // Row 1: column header labels
    var headerRow = document.createElement('tr');
    headerRow.style.background = '#2c3e50';
    headerRow.style.color = '#fff';
    cols.forEach(function (col) {
      var th = document.createElement('th');
      th.style.cssText = 'text-align:left; padding:8px 10px; border-bottom:1px solid #444;';
      th.textContent = col.label;
      headerRow.appendChild(th);
    });

    // Row 2: per-column filter inputs (Agile-style, client-side substring match)
    var filterRow = document.createElement('tr');
    filterRow.style.background = '#FAFAF7';
    cols.forEach(function (col) {
      var th = document.createElement('th');
      th.style.cssText = 'padding:4px 6px; border-bottom:1px solid #E8E6DF;';
      var input = document.createElement('input');
      input.className = 'at-col-filter';
      input.setAttribute('data-col', col.key);
      input.placeholder = 'filter\u2026';  // real ellipsis char, not raw \u escape
      input.style.cssText = 'width:100%; box-sizing:border-box; padding:3px 6px; font-size:11.5px; border:1px solid #E8E6DF; border-radius:3px;';
      input.addEventListener('input', applyColumnFilters);
      th.appendChild(input);
      filterRow.appendChild(th);
    });

    thead.innerHTML = '';
    thead.appendChild(headerRow);
    thead.appendChild(filterRow);
  }

  function renderAuditTrailRows(data) {
    var wrap = document.getElementById('atResultsWrap');
    var meta = document.getElementById('atResultsMeta');
    var empty = document.getElementById('atResultsEmpty');
    var intro = document.getElementById('atIntro');
    if (!wrap || !meta || !empty) return;

    intro.style.display = 'none';
    wrap.style.display = '';

    var src = data.source || 'change';
    rebuildThead(src);

    var truncatedNote = data.truncated ? ' &middot; <strong style="color:#C7801B;">truncated at limit \u2014 narrow filters for full results</strong>' : '';
    meta.innerHTML = '<span id="atResultsMetaCount">' + lastRows.length + '</span> row(s) &middot; ' + data.elapsedMs + ' ms &middot; <em>' + (src === 'item' ? 'item_history' : 'change_history') + '</em>' + truncatedNote;

    // PT-66: server can surface a hint when the empty result is most likely due
    // to the item+change AND-intersection (the two don't relate). Show it in the
    // empty-state block \u2014 replaced on next non-empty render.
    if (empty) {
      if (data.hint && lastRows.length === 0) {
        empty.innerHTML =
          '<div style="display:inline-block; max-width:640px; padding:14px 18px; ' +
          'background:#fff8e1; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; ' +
          'text-align:left; color:#0F1720; line-height:1.55;">' +
          '<strong>No matching rows.</strong><br>' + esc(data.hint) +
          '</div>';
      } else {
        empty.innerHTML = 'No rows match these filters.';
      }
    }

    applyColumnFilters();
  }

  function applyColumnFilters() {
    var tbody = document.getElementById('atResultsTbody');
    var empty = document.getElementById('atResultsEmpty');
    var countEl = document.getElementById('atResultsMetaCount');
    if (!tbody || !empty) return;

    var filters = {};
    document.querySelectorAll('.at-col-filter').forEach(function (el) {
      var v = (el.value || '').trim().toLowerCase();
      if (v) filters[el.getAttribute('data-col')] = v;
    });

    var visible = [];
    for (var i = 0; i < lastRows.length; i++) {
      var r = lastRows[i];
      var match = true;
      for (var col in filters) {
        var cellVal = r[col];
        if (cellVal == null) { match = false; break; }
        if (String(cellVal).toLowerCase().indexOf(filters[col]) === -1) { match = false; break; }
      }
      if (match) visible.push(r);
    }

    if (countEl) {
      var total = lastRows.length;
      var hidden = total - visible.length;
      countEl.innerHTML = visible.length + ' of ' + total + (hidden > 0 ? ' (' + hidden + ' hidden by column filters)' : '');
    }

    if (visible.length === 0) {
      tbody.innerHTML = '';
      empty.style.display = '';
      return;
    }
    empty.style.display = 'none';

    var cols = COLUMNS[lastSource] || COLUMNS.change;
    var html = '';
    for (var j = 0; j < visible.length; j++) {
      var row = visible[j];
      var bg = (j % 2 === 0) ? '#FAFAF7' : '#ffffff';
      var tds = cols.map(function (col) {
        var styles = ['padding:6px 10px', 'border-bottom:1px solid #E8E6DF'];
        if (col.mono)     styles.push('font-family:\'IBM Plex Mono\',Consolas,monospace', 'font-size:12px');
        if (col.noWrap)   styles.push('white-space:nowrap');
        if (col.maxWidth) styles.push('max-width:' + col.maxWidth, 'word-break:break-word');
        return '<td style="' + styles.join('; ') + ';">' + esc(row[col.key]) + '</td>';
      }).join('');
      html += '<tr style="background:' + bg + ';">' + tds + '</tr>';
    }
    tbody.innerHTML = html;
  }
})();
