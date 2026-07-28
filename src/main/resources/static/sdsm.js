/* SDSM operator-view tab — Shop-Floor Document Lookup
 *
 * Behaviour:
 *   - On tab activation: focus the input, refresh the active-deviations banner.
 *   - Operator types or scans a part/document number; Enter submits.
 *   - Backend returns one record per (file × spec list); we render one card per file.
 *   - Document Style drives the colored badge (WI = green, VIC = teal, Drawing = blue,
 *     Quality Notice = red, etc.).
 *   - Spec chips at the top let the operator filter to docs covering THEIR step code.
 *   - "View PDF" pops the PDF in an iframe modal — local-share bytes if available,
 *     SDK fallback otherwise. Source is announced via X-SDSM-Source response header.
 */

(function () {
  'use strict';

  // Document-style colour palette (sandisk palette, matches ccbinspector mockup).
  // PT-61 follow-up: added Temp VAR, Offline VAR, Offline WI per the eVAR/TTMS
  // training guide (27-04-SM-02-00054 Rev 5). Offline-* styles get a slightly
  // different fg/bg pair so operators can distinguish online (eVAR) from offline
  // (TTMS) docs at a glance.
  var STYLE_COLORS = {
    'WI':              { bg: '#e8f5e9', fg: '#1F8A4C', border: '#1F8A4C' },
    'VIC':             { bg: '#e0f2f1', fg: '#00695c', border: '#00695c' },
    'VAR':             { bg: '#f3e5f5', fg: '#6a1b9a', border: '#6a1b9a' },
    'Temp VAR':        { bg: '#f3e5f5', fg: '#6a1b9a', border: '#6a1b9a' },
    'FPS':             { bg: '#e3f2fd', fg: '#1565c0', border: '#1565c0' },
    'Drawing':         { bg: '#e8f0fe', fg: '#1a3a5c', border: '#4a6fa5' },
    'Guideline':       { bg: '#fff3e0', fg: '#C7801B', border: '#C7801B' },
    'Checklist':       { bg: '#fff8e1', fg: '#856404', border: '#C7801B' },
    'Offline WI':      { bg: '#ede7f6', fg: '#4527a0', border: '#4527a0' },
    'Offline VAR':     { bg: '#ede7f6', fg: '#4527a0', border: '#4527a0' },
    'Quality Notice':  { bg: '#fdeaea', fg: '#B8342B', border: '#B8342B' },
    'QN':              { bg: '#fdeaea', fg: '#B8342B', border: '#B8342B' }
  };

  var lastResults = [];        // last full search response — used by spec filter
  var lastQuery = '';
  var activeSpec = null;       // null = "show all"
  var contextSpec = null;      // the spec the user typed in station-mode (highlighted, others collapsed by default)
  var specRowExpanded = false; // top-of-results "Filter by Spec" row expanded?
  var cardExpanded = {};       // per-card spec list expanded? (key = attachId)
  var bannerLoaded = false;    // load active deviations once per session
  var dropdownsLoaded = false; // populate context-mode dropdowns once per session
  var ctxStyleSelected = {};   // { docStyle: true } for chips currently toggled on

  // ---- Recent-searches breadcrumb (PT-61 follow-up) ----
  // Operator searches that returned at least one doc are pinned to a chip row
  // above the input. Stored in localStorage, capped at 10. New entries push
  // older ones out (LRU). Each chip is clickable (re-runs the search) and has
  // an × button (removes just that entry).
  var SDSM_RECENT_KEY = 'sdsm.recent.v1';
  var SDSM_RECENT_MAX = 10;

  // Doc Style chip set (for the context-mode optional filter). Same whitelist
  // the backend uses so the chip universe matches what the search will accept.
  var CTX_STYLES = ['WI','VAR','VIC','FPS','Guideline','Checklist','Drawing','Temp VAR','Offline VAR','Offline WI'];

  // --- public init (called from app.js switchTab when this tab is shown) ---
  window.sdsmInit = function () {
    // Default mode is Station (operator-first); part-mode is opt-in.
    var mode = (function () { try { return localStorage.getItem('sdsm.mode') || 'station'; } catch (e) { return 'station'; } })();
    sdsmSetMode(mode, true);  // silent on init — don't blow away in-progress searches

    var input = document.getElementById('sdsmSearchInput');
    if (input && !input.dataset.boundEnter) {
      input.dataset.boundEnter = '1';
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); window.sdsmSearch(); }
      });
    }
    // Same Enter-handling on the three context-mode inputs, plus the cascading
    // dropdown narrowing on `oninput` (debounced) and `onchange` (immediate
    // when user picks a datalist option, which fires change but not input).
    ['sdsmCtxSpec','sdsmCtxProductGroup','sdsmCtxProduct'].forEach(function (id) {
      var el = document.getElementById(id);
      if (!el || el.dataset.boundEnter) return;
      el.dataset.boundEnter = '1';
      el.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); window.sdsmCtxSearch(); }
      });
      el.addEventListener('input',  cascadeSchedule);
      el.addEventListener('change', cascadeNow);
    });
    if (!bannerLoaded) {
      bannerLoaded = true;
      loadActiveDeviationsBanner();
    }
    if (!dropdownsLoaded) {
      dropdownsLoaded = true;
      loadContextDropdowns();
      renderCtxStyleChips();
    }
    renderRecentChips();
  };

  // --- Mode toggle: 'station' (default) vs 'part' ---
  window.sdsmSetMode = function (mode, silent) {
    if (mode !== 'station' && mode !== 'part') mode = 'station';
    try { localStorage.setItem('sdsm.mode', mode); } catch (e) {}
    var stationForm = document.getElementById('sdsmStationForm');
    var partForm    = document.getElementById('sdsmPartForm');
    var stationPill = document.getElementById('sdsmModeStation');
    var partPill    = document.getElementById('sdsmModePart');
    if (stationForm) stationForm.style.display = (mode === 'station') ? '' : 'none';
    if (partForm)    partForm.style.display    = (mode === 'part')    ? '' : 'none';
    if (stationPill) {
      var on = mode === 'station';
      stationPill.style.background = on ? '#0F1720' : '#ffffff';
      stationPill.style.color      = on ? '#ffffff' : '#0F1720';
      stationPill.style.fontWeight = on ? '600' : '500';
      var sub = stationPill.querySelector('span');
      if (sub) sub.style.color = on ? 'rgba(255,255,255,0.7)' : '#6B7280';
    }
    if (partPill) {
      var on2 = mode === 'part';
      partPill.style.background = on2 ? '#0F1720' : '#ffffff';
      partPill.style.color      = on2 ? '#ffffff' : '#0F1720';
      partPill.style.fontWeight = on2 ? '600' : '500';
      var sub2 = partPill.querySelector('span');
      if (sub2) sub2.style.color = on2 ? 'rgba(255,255,255,0.7)' : '#6B7280';
    }
    // Focus the most-relevant input on the active form (skip on the silent init
    // call so we don't yank focus from wherever the user already is).
    if (!silent) {
      setTimeout(function () {
        var target = (mode === 'station')
          ? document.getElementById('sdsmCtxSpec')
          : document.getElementById('sdsmSearchInput');
        if (target) { target.focus(); target.select && target.select(); }
      }, 50);
    }
  };

  // Cascading dropdown population — uses /api/sdsm/context-options. Each
  // selection narrows the OTHER inputs' suggestions and the style chip counts.
  // Debounced 300ms on text input; immediate on style-chip clicks.
  // Latest counts cached so chip greying survives re-renders.
  var lastCascadeOptions = null;
  var cascadeDebounce = null;
  function loadContextDropdowns() {
    cascadeNow();  // initial load = no filters = full universe
  }
  function cascadeSchedule() {
    if (cascadeDebounce) clearTimeout(cascadeDebounce);
    cascadeDebounce = setTimeout(cascadeNow, 300);
  }
  function cascadeNow() {
    var spec = (document.getElementById('sdsmCtxSpec') || {}).value || '';
    var pg   = (document.getElementById('sdsmCtxProductGroup') || {}).value || '';
    var prod = (document.getElementById('sdsmCtxProduct') || {}).value || '';
    var styles = Object.keys(ctxStyleSelected);
    var url = '/api/sdsm/context-options'
      + '?spec=' + encodeURIComponent(spec.trim())
      + '&productGroup=' + encodeURIComponent(pg.trim())
      + '&product=' + encodeURIComponent(prod.trim())
      + (styles.length ? ('&styles=' + encodeURIComponent(styles.join(','))) : '');
    fetch(url, { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d) return;
        lastCascadeOptions = d;
        fillDatalist('sdsmSpecOptions',    d.specs);
        fillDatalist('sdsmPgOptions',      d.productGroups);
        fillDatalist('sdsmProductOptions', d.products);
        renderCtxStyleChips();  // re-render with fresh counts
        renderCtxCandidatePreview(d.totalCandidates);
      })
      .catch(function () {});
  }
  function fillDatalist(id, values) {
    var dl = document.getElementById(id);
    if (!dl) return;
    dl.innerHTML = (values || []).map(function (v) {
      return '<option value="' + escapeAttr(v) + '"></option>';
    }).join('');
  }
  function renderCtxCandidatePreview(n) {
    var btn = document.getElementById('sdsmCtxSearchBtn');
    if (!btn) return;
    // Tiny live-count badge inside the button, only visible when all three
    // required fields are filled in. Lets the operator see "no results"
    // before clicking, so a wasted click is avoided.
    var spec = (document.getElementById('sdsmCtxSpec')||{}).value;
    var pg   = (document.getElementById('sdsmCtxProductGroup')||{}).value;
    var prod = (document.getElementById('sdsmCtxProduct')||{}).value;
    var ready = !!(spec && pg && prod);
    var existing = btn.querySelector('.ctx-candidate-badge');
    if (!ready || n == null) { if (existing) existing.remove(); return; }
    var text = n + ' match' + (n === 1 ? '' : 'es');
    if (existing) { existing.textContent = text; return; }
    var s = document.createElement('span');
    s.className = 'ctx-candidate-badge';
    s.textContent = text;
    s.style.cssText = 'margin-left:8px; padding:2px 8px; border-radius:10px; background:rgba(255,255,255,0.18); color:#ffffff; font-size:11px; font-weight:600;';
    btn.appendChild(s);
  }

  function renderCtxStyleChips() {
    var host = document.getElementById('sdsmCtxStyleChips');
    if (!host) return;
    var counts = (lastCascadeOptions && lastCascadeOptions.styleCounts) || {};
    host.innerHTML = CTX_STYLES.map(function (s) {
      var on  = !!ctxStyleSelected[s];
      var n   = counts[s] || 0;
      var dim = n === 0;
      var palette = STYLE_COLORS[s] || { bg: '#FAFAF7', fg: '#0F1720', border: '#E8E6DF' };
      var bg, fg, border, opacity, cursor;
      if (dim && !on) {
        bg = '#f4f4f4'; fg = '#999999'; border = '#dddddd'; opacity = '0.55'; cursor = 'not-allowed';
      } else {
        bg = on ? palette.fg : palette.bg;
        fg = on ? '#ffffff' : palette.fg;
        border = palette.border;
        opacity = '1'; cursor = 'pointer';
      }
      var label = escapeHtml(s) + (n != null ? ' <span style="opacity:0.7; font-weight:400;">(' + n + ')</span>' : '');
      return '<button type="button" data-style="' + escapeAttr(s) + '" onclick="sdsmCtxToggleStyle(this)" ' +
        (dim && !on ? 'disabled ' : '') +
        'style="padding:3px 9px; border-radius:11px; font-size:11px; font-weight:600; cursor:' + cursor + '; opacity:' + opacity + '; ' +
        'background:' + bg + '; color:' + fg + '; border:1px solid ' + border + ';">' + label + '</button>';
    }).join('');
  }
  window.sdsmCtxToggleStyle = function (btn) {
    if (btn.disabled) return;
    var s = btn.getAttribute('data-style');
    if (!s) return;
    if (ctxStyleSelected[s]) delete ctxStyleSelected[s]; else ctxStyleSelected[s] = true;
    cascadeNow();  // chip toggles are immediate (no debounce)
  };

  // Context-mode search: requires Spec + Product Group + Product, optional styles.
  window.sdsmCtxSearch = function () {
    var spec = (document.getElementById('sdsmCtxSpec').value || '').trim();
    var pg   = (document.getElementById('sdsmCtxProductGroup').value || '').trim();
    var prod = (document.getElementById('sdsmCtxProduct').value || '').trim();
    var missing = [];
    if (!spec) missing.push('Spec / Step Code');
    if (!pg)   missing.push('Product Group');
    if (!prod) missing.push('Product');
    if (missing.length) {
      if (typeof appAlert === 'function') appAlert('Please fill in: ' + missing.join(', '));
      else alert('Please fill in: ' + missing.join(', '));
      return;
    }
    var styles = Object.keys(ctxStyleSelected);
    var url = '/api/sdsm/search-by-context'
      + '?spec=' + encodeURIComponent(spec)
      + '&productGroup=' + encodeURIComponent(pg)
      + '&product=' + encodeURIComponent(prod)
      + (styles.length ? ('&styles=' + encodeURIComponent(styles.join(','))) : '');
    setBusy(true, 'sdsmCtxSearchBtn');
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (data) {
        lastQuery = spec + ' / ' + pg + ' / ' + prod;
        lastResults = (data && data.results) || [];
        activeSpec = null;
        contextSpec = spec;       // station-mode search → collapse cards/filter row around this spec
        specRowExpanded = false;  // start collapsed; user expands explicitly
        cardExpanded = {};
        render(data);
        // Hide diagnostic panel — it's only meaningful for part-number searches.
        var ex = document.getElementById('sdsmExplainPanel');
        if (ex) { ex.innerHTML = ''; ex.style.display = 'none'; }
      })
      .catch(renderError)
      .finally(function () { setBusy(false, 'sdsmCtxSearchBtn'); });
  };

  window.sdsmCtxClear = function () {
    document.getElementById('sdsmCtxSpec').value = '';
    document.getElementById('sdsmCtxProductGroup').value = '';
    document.getElementById('sdsmCtxProduct').value = '';
    ctxStyleSelected = {};
    renderCtxStyleChips();
    sdsmClear();
    document.getElementById('sdsmCtxSpec').focus();
  };

  // Recent searches — LRU list of part numbers that returned at least one doc.
  function loadRecent() {
    try {
      var raw = localStorage.getItem(SDSM_RECENT_KEY);
      if (!raw) return [];
      var arr = JSON.parse(raw);
      return Array.isArray(arr) ? arr.filter(function (x) { return typeof x === 'string'; }) : [];
    } catch (e) { return []; }
  }
  function saveRecent(arr) {
    try { localStorage.setItem(SDSM_RECENT_KEY, JSON.stringify(arr.slice(0, SDSM_RECENT_MAX))); }
    catch (e) {}
  }
  function pushRecent(q) {
    if (!q) return;
    var arr = loadRecent();
    // Remove any prior occurrence (case-insensitive match) so the new entry
    // sits at the front; keeps the chip row in true LRU order.
    arr = arr.filter(function (x) { return x.toLowerCase() !== q.toLowerCase(); });
    arr.unshift(q);
    if (arr.length > SDSM_RECENT_MAX) arr = arr.slice(0, SDSM_RECENT_MAX);
    saveRecent(arr);
    renderRecentChips();
  }
  function removeRecent(q) {
    var arr = loadRecent().filter(function (x) { return x.toLowerCase() !== q.toLowerCase(); });
    saveRecent(arr);
    renderRecentChips();
  }
  function renderRecentChips() {
    var strip = document.getElementById('sdsmRecentStrip');
    var host  = document.getElementById('sdsmRecentChips');
    if (!strip || !host) return;
    var arr = loadRecent();
    if (!arr.length) { strip.style.display = 'none'; host.innerHTML = ''; return; }
    strip.style.display = '';
    host.innerHTML = arr.map(function (q) {
      var qe = escapeAttr(q);
      return '<span style="display:inline-flex; align-items:center; gap:2px; background:#ffffff; border:1px solid #E8E6DF; border-radius:14px; padding:2px 4px 2px 10px; font-size:12px;">' +
        '<button onclick="sdsmRecentClick(\'' + qe + '\')" title="Re-run this search" ' +
        'style="background:none; border:0; color:#0F1720; cursor:pointer; padding:0; font-size:12px;">' + escapeHtml(q) + '</button>' +
        '<button onclick="sdsmRecentRemove(\'' + qe + '\')" title="Remove from recent" ' +
        'style="background:none; border:0; color:#6B7280; cursor:pointer; padding:0 6px; font-size:13px; line-height:1;">&times;</button>' +
        '</span>';
    }).join('');
  }
  window.sdsmRecentClick = function (q) {
    var input = document.getElementById('sdsmSearchInput');
    if (input) { input.value = q; input.focus(); }
    window.sdsmSearch();
  };
  window.sdsmRecentRemove = function (q) { removeRecent(q); };
  window.sdsmRecentClear = function () { saveRecent([]); renderRecentChips(); };

  // --- main search ---
  window.sdsmSearch = function () {
    var input = document.getElementById('sdsmSearchInput');
    var q = (input.value || '').trim();
    if (!q) { input.focus(); return; }

    setBusy(true);
    fetch('/api/sdsm/search?q=' + encodeURIComponent(q), {
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json' }
    })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (data) {
        lastQuery = q;
        lastResults = (data && data.results) || [];
        activeSpec = null;
        contextSpec = null;        // part-mode has no input spec to collapse around
        specRowExpanded = false;
        cardExpanded = {};
        render(data);
        // Auto-diagnose on empty results — the operator pressed Enter and got
        // nothing; surface the reason without making them click anything else.
        if (!lastResults.length) {
          fetchDiagnostic(q);
        } else {
          var ex = document.getElementById('sdsmExplainPanel');
          if (ex) { ex.innerHTML = ''; ex.style.display = 'none'; }
          // Successful searches get pinned to the recent-searches breadcrumb.
          // We only push when results came back so empty / typo searches don't
          // pollute the chip row.
          pushRecent(q);
        }
      })
      .catch(function (err) {
        renderError(err);
      })
      .finally(function () {
        setBusy(false);
      });
  };

  window.sdsmClear = function () {
    var input = document.getElementById('sdsmSearchInput');
    if (input) { input.value = ''; input.focus(); }
    lastResults = [];
    lastQuery = '';
    activeSpec = null;
    document.getElementById('sdsmResults').innerHTML = '';
    document.getElementById('sdsmSpecFilter').style.display = 'none';
    document.getElementById('sdsmStatusStrip').style.display = 'none';
    document.getElementById('sdsmNoMatch').style.display = 'none';
    document.getElementById('sdsmEmpty').style.display = '';
    var ex = document.getElementById('sdsmExplainPanel');
    if (ex) { ex.innerHTML = ''; ex.style.display = 'none'; }
  };

  // Auto-fired by sdsmSearch() when results are empty. Renders into the same
  // sdsmExplainPanel; replaces the static "No documents found" yellow box that
  // used to live in the HTML by hiding it before showing the dynamic panel.
  function fetchDiagnostic(q) {
    var noMatch = document.getElementById('sdsmNoMatch');
    if (noMatch) noMatch.style.display = 'none';
    var panel = document.getElementById('sdsmExplainPanel');
    if (!panel) return;
    panel.style.display = '';
    panel.innerHTML = '<div style="padding:14px 18px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; font-size:13px; color:#6B7280;">' +
      'Diagnosing &ldquo;' + escapeHtml(q) + '&rdquo;&hellip;</div>';

    fetch('/api/sdsm/diagnose?q=' + encodeURIComponent(q), { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); })
      .then(renderExplain)
      .catch(function (err) {
        panel.innerHTML = '<div style="padding:14px 18px; background:#fdeaea; border-left:4px solid #B8342B; border-radius:0 6px 6px 0; color:#B8342B; font-size:13px;">' +
          'Diagnostic failed: ' + escapeHtml(String(err && err.message || err)) + '</div>';
      });
  }

  function renderExplain(d) {
    var panel = document.getElementById('sdsmExplainPanel');
    var statusBadge = function (s) {
      if (s === 'pass') return '<span style="display:inline-block; min-width:48px; text-align:center; padding:2px 8px; border-radius:10px; background:#e8f5e9; color:#1F8A4C; font-weight:600; font-size:11px;">PASS</span>';
      if (s === 'fail') return '<span style="display:inline-block; min-width:48px; text-align:center; padding:2px 8px; border-radius:10px; background:#fdeaea; color:#B8342B; font-weight:600; font-size:11px;">FAIL</span>';
      return '<span style="display:inline-block; min-width:48px; text-align:center; padding:2px 8px; border-radius:10px; background:#e8f0fe; color:#1a3a5c; font-weight:600; font-size:11px;">INFO</span>';
    };
    var headerBg = '#0F1720';
    var verdictColor = '#0F1720';
    if (d.checks && d.checks.some(function (c) { return c.status === 'fail'; })) {
      headerBg = '#B8342B';
      verdictColor = '#B8342B';
    }

    var html = '<div style="background:#ffffff; border:1px solid #E8E6DF; border-radius:8px; overflow:hidden;">';
    // Header
    html += '<div style="background:' + headerBg + '; color:#ffffff; padding:14px 18px;">';
    html += '<div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; opacity:0.85;">Diagnostic for &ldquo;' + escapeHtml(d.query || '') + '&rdquo;</div>';
    if (d.itemFound && d.itemNumber) {
      html += '<div style="font-size:15px; font-weight:600; margin-top:4px;">' +
        escapeHtml(d.itemNumber) +
        (d.subclassName ? ' &middot; <span style="opacity:0.85; font-weight:400;">' + escapeHtml(d.subclassName) + '</span>' : '') +
        '</div>';
      if (d.description) {
        html += '<div style="font-size:13px; opacity:0.85; margin-top:2px;">' + escapeHtml(d.description) + '</div>';
      }
    }
    html += '</div>';

    // Verdict
    if (d.summary) {
      html += '<div style="padding:14px 18px; background:#FAFAF7; border-bottom:1px solid #E8E6DF;">' +
        '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; font-weight:600;">Verdict</div>' +
        '<div style="font-size:14px; color:' + verdictColor + '; margin-top:4px; line-height:1.5;">' + escapeHtml(d.summary) + '</div>' +
        '</div>';
    }

    // Checks
    if (d.checks && d.checks.length) {
      html += '<div style="padding:8px 18px;">';
      html += '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; font-weight:600; margin:8px 0 6px;">Checks</div>';
      html += '<table style="width:100%; border-collapse:collapse; font-size:13px;">';
      d.checks.forEach(function (c) {
        html += '<tr>' +
          '<td style="padding:6px 6px; vertical-align:top; width:60px;">' + statusBadge(c.status) + '</td>' +
          '<td style="padding:6px 6px; vertical-align:top; width:200px; font-weight:600; color:#0F1720;">' + escapeHtml(c.name) + '</td>' +
          '<td style="padding:6px 6px; vertical-align:top; color:#0F1720; line-height:1.5;">' + escapeHtml(c.detail) + '</td>' +
          '</tr>';
      });
      html += '</table>';
      html += '</div>';
    }

    // Next steps
    if (d.nextSteps && d.nextSteps.length) {
      html += '<div style="padding:14px 18px; background:#fff8e1; border-top:1px solid #E8E6DF;">';
      html += '<div style="font-size:11px; color:#856404; text-transform:uppercase; letter-spacing:0.5px; font-weight:600; margin-bottom:6px;">What would fix this</div>';
      html += '<ul style="margin:0; padding-left:20px; font-size:13px; color:#0F1720; line-height:1.6;">';
      d.nextSteps.forEach(function (s) {
        html += '<li>' + escapeHtml(s) + '</li>';
      });
      html += '</ul>';
      html += '</div>';
    }

    // Contact note (always shown)
    if (d.contactNote) {
      html += '<div style="padding:12px 18px; background:#e8f0fe; border-top:1px solid #E8E6DF; font-size:12px; color:#1a3a5c; line-height:1.5;">' +
        '&#9432; ' + escapeHtml(d.contactNote) +
        '</div>';
    }

    html += '</div>';
    panel.innerHTML = html;
    // Scroll the diagnostic into view so the operator sees it without hunting.
    try { panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); } catch (e) {}
  }

  function setBusy(busy, btnId) {
    var btn = document.getElementById(btnId || 'sdsmSearchBtn');
    if (!btn) return;
    btn.disabled = busy;
    if (busy) {
      // Stash the original label so we can restore it (context-mode button has
      // a Bahasa subtitle in a child <span>; reading textContent would lose that).
      if (!btn.dataset.origHtml) btn.dataset.origHtml = btn.innerHTML;
      btn.textContent = 'Loading…';
    } else if (btn.dataset.origHtml) {
      btn.innerHTML = btn.dataset.origHtml;
      delete btn.dataset.origHtml;
    }
  }

  function render(data) {
    var resultsDiv = document.getElementById('sdsmResults');
    var emptyDiv = document.getElementById('sdsmEmpty');
    var noMatchDiv = document.getElementById('sdsmNoMatch');
    var statusStrip = document.getElementById('sdsmStatusStrip');
    var specFilter = document.getElementById('sdsmSpecFilter');
    var specChips = document.getElementById('sdsmSpecChips');

    emptyDiv.style.display = 'none';

    if (!lastResults.length) {
      // The diagnostic panel (auto-fired by sdsmSearch) takes the empty-state
      // role — the static yellow "No documents found" banner is suppressed
      // because it duplicates the diagnostic verdict.
      resultsDiv.innerHTML = '';
      noMatchDiv.style.display = 'none';
      specFilter.style.display = 'none';
      statusStrip.style.display = 'none';
      return;
    }
    noMatchDiv.style.display = 'none';

    // Build spec chip set across all results
    var specSet = {};
    lastResults.forEach(function (r) {
      (r.specs || []).forEach(function (s) { if (s) specSet[s] = true; });
    });
    var specs = Object.keys(specSet).sort();
    if (specs.length > 1) {
      specFilter.style.display = '';
      specChips.innerHTML = '';
      specChips.appendChild(makeChip('All (' + lastResults.length + ')', null, true));

      // Collapse-around-input behavior (PT-61 follow-up). When a doc applies to
      // 250+ specs, rendering all of them obliterates screen real estate. So:
      //   - station mode (contextSpec set) → show ONLY that spec by default,
      //     plus a "show N other spec codes" toggle
      //   - part mode (no contextSpec)     → show first 8, plus toggle for the rest
      var visibleSet;
      if (contextSpec && specs.indexOf(contextSpec) >= 0) {
        visibleSet = specRowExpanded ? specs : [contextSpec];
      } else {
        visibleSet = specRowExpanded ? specs : specs.slice(0, 8);
      }
      visibleSet.forEach(function (s) {
        var count = lastResults.filter(function (r) { return (r.specs || []).indexOf(s) >= 0; }).length;
        specChips.appendChild(makeChip(s + ' (' + count + ')', s, false));
      });
      var hidden = specs.length - visibleSet.length;
      if (hidden > 0 || specRowExpanded) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.style.cssText = 'background:none; border:0; color:#4a6fa5; padding:4px 10px; font-size:12px; cursor:pointer; text-decoration:underline;';
        btn.textContent = specRowExpanded
          ? 'show fewer'
          : ('show ' + hidden + ' other spec code' + (hidden === 1 ? '' : 's'));
        btn.onclick = function () {
          specRowExpanded = !specRowExpanded;
          render({ queryTimeMs: null });  // re-render with new collapsed state
        };
        specChips.appendChild(btn);
      }
    } else {
      specFilter.style.display = 'none';
    }

    statusStrip.style.display = '';
    document.getElementById('sdsmCountText').textContent =
      lastResults.length + ' document' + (lastResults.length === 1 ? '' : 's') + ' for "' + lastQuery + '"';
    var t = (data && typeof data.queryTimeMs === 'number') ? (' · ' + data.queryTimeMs + ' ms') : '';
    document.getElementById('sdsmTimeText').textContent = t;

    paintResults();
  }

  function makeChip(label, specValue, allChip) {
    var el = document.createElement('button');
    el.type = 'button';
    el.textContent = label;
    el.style.cssText =
      'border:1px solid #E8E6DF; background:' + (allChip ? '#0F1720' : '#ffffff') + '; ' +
      'color:' + (allChip ? '#ffffff' : '#0F1720') + '; padding:5px 12px; border-radius:14px; ' +
      'font-size:12px; cursor:pointer;';
    el.dataset.spec = specValue == null ? '' : specValue;
    el.onclick = function () {
      activeSpec = specValue;
      // Repaint chip selection state
      Array.prototype.forEach.call(document.querySelectorAll('#sdsmSpecChips button'), function (b) {
        var on = (b.dataset.spec === (activeSpec == null ? '' : activeSpec));
        b.style.background = on ? '#0F1720' : '#ffffff';
        b.style.color = on ? '#ffffff' : '#0F1720';
      });
      paintResults();
    };
    return el;
  }

  // Per-card spec list expand/collapse — partials only, no full re-fetch.
  window.sdsmToggleCardSpecs = function (cardKey) {
    if (cardExpanded[cardKey]) delete cardExpanded[cardKey]; else cardExpanded[cardKey] = true;
    paintResults();
  };

  function paintResults() {
    var resultsDiv = document.getElementById('sdsmResults');
    var filtered = activeSpec == null
      ? lastResults
      : lastResults.filter(function (r) { return (r.specs || []).indexOf(activeSpec) >= 0; });

    if (!filtered.length) {
      resultsDiv.innerHTML =
        '<div style="padding:18px; color:#6B7280; font-size:13px; background:#FAFAF7; border:1px dashed #E8E6DF; border-radius:6px;">' +
        'No documents apply to spec ' + escapeHtml(activeSpec || '') + '.</div>';
      return;
    }

    var html = filtered.map(renderCard).join('');
    resultsDiv.innerHTML = html;
  }

  function renderCard(r) {
    var style = (r.documentStyle || '').trim();
    var palette = STYLE_COLORS[style] || { bg: '#e8e6df', fg: '#0F1720', border: '#cccccc' };

    // Per-card spec list — collapsed around the input spec by default to keep
    // cards compact (a Drawing on a top-level assembly can apply to 250+ specs).
    var allSpecs = r.specs || [];
    var cardKey  = String(r.attachId || r.fileId || r.fileName || Math.random());
    var expanded = !!cardExpanded[cardKey];
    var visible;
    if (contextSpec && allSpecs.indexOf(contextSpec) >= 0) {
      visible = expanded ? allSpecs : [contextSpec];
    } else {
      visible = expanded ? allSpecs : allSpecs.slice(0, 8);
    }
    var hidden = allSpecs.length - visible.length;
    var chipFor = function (s) {
      var on = (s === activeSpec) || (s === contextSpec && !activeSpec);
      return '<span style="display:inline-block; padding:2px 8px; border-radius:10px; font-size:11px; ' +
        'background:' + (on ? palette.fg : '#FAFAF7') + '; color:' + (on ? '#ffffff' : '#6B7280') +
        '; border:1px solid ' + (on ? palette.fg : '#E8E6DF') + '; margin:2px 3px;">' + escapeHtml(s) + '</span>';
    };
    var specsHtml = visible.map(chipFor).join('');
    if (hidden > 0 || expanded) {
      var label = expanded ? 'show fewer'
                           : ('show ' + hidden + ' other spec code' + (hidden === 1 ? '' : 's'));
      specsHtml += '<button type="button" onclick="sdsmToggleCardSpecs(\'' + escapeAttr(cardKey) + '\')" ' +
        'style="background:none; border:0; color:#4a6fa5; padding:2px 8px; font-size:11px; cursor:pointer; ' +
        'text-decoration:underline; margin:2px 3px;">' + label + '</button>';
    }

    var fileBytes = formatBytes(r.fileSize);
    var fileLabel = escapeHtml(r.fileName || '(unnamed)');
    var lifecycle = r.lifecyclePhase ? r.lifecyclePhase : '';
    var rev = r.rev ? ('Rev ' + escapeHtml(r.rev)) : '';
    var changeNum = r.changeNumber ? escapeHtml(r.changeNumber) : '';
    var product = r.product ? escapeHtml(r.product) : '';
    var pg = r.productGroup ? escapeHtml(r.productGroup) : '';
    var devEffective = '';
    if (r.source === 'deviation' && r.effectiveTo) {
      devEffective = '<div style="font-size:11px; color:#B8342B; margin-top:4px;">Quality Notice in effect through ' + escapeHtml(r.effectiveTo) + '</div>';
    }

    var viewUrl = '/api/sdsm/file/' + encodeURIComponent(r.attachId)
      + '?fileName=' + encodeURIComponent(r.fileName || '')
      + '&parentNumber=' + encodeURIComponent(r.parentNumber || '')
      + (r.rev ? '&rev=' + encodeURIComponent(r.rev) : '');

    return (
      '<div style="display:flex; gap:14px; padding:14px 16px; margin:10px 0; background:#ffffff; border:1px solid #E8E6DF; border-radius:8px;">' +
        '<div style="min-width:90px; text-align:center; align-self:center;">' +
          '<div style="display:inline-block; padding:6px 12px; border-radius:6px; background:' + palette.bg +
            '; color:' + palette.fg + '; border:1px solid ' + palette.border +
            '; font-weight:600; font-size:13px;">' + escapeHtml(style || '?') + '</div>' +
          (lifecycle ? '<div style="margin-top:6px; font-size:10px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px;">' + escapeHtml(lifecycle) + '</div>' : '') +
        '</div>' +
        '<div style="flex:1; min-width:0;">' +
          '<div style="font-weight:600; font-size:14px; color:#0F1720; word-break:break-word;">' + fileLabel + '</div>' +
          '<div style="margin-top:4px; font-size:12px; color:#6B7280;">' +
            escapeHtml(r.parentNumber || '') +
            (rev ? '  ·  ' + rev : '') +
            (changeNum ? '  ·  ' + changeNum : '') +
            (fileBytes ? '  ·  ' + fileBytes : '') +
          '</div>' +
          (pg || product
            ? '<div style="margin-top:4px; font-size:11px; color:#0F1720;">' +
                (pg ? '<strong>Product Group:</strong> ' + pg : '') +
                (pg && product ? '  ·  ' : '') +
                (product ? '<strong>Product:</strong> ' + product : '') +
              '</div>'
            : '') +
          devEffective +
          (specsHtml ? '<div style="margin-top:8px;">' + specsHtml + '</div>' : '') +
        '</div>' +
        '<div style="align-self:center;">' +
          '<button class="btn-primary" style="padding:10px 18px; font-size:13px; font-weight:600;" ' +
            'onclick="sdsmOpenPdf(' + r.attachId + ', \'' + escapeAttr(r.fileName || '') +
            '\', \'' + escapeAttr(r.parentNumber || '') + '\', \'' + escapeAttr(r.rev || '') +
            '\', \'' + escapeAttr(r.changeNumber || '') + '\')">View PDF</button>' +
          '<a class="btn" href="' + viewUrl + '&inline=false" download style="display:inline-block; margin-left:6px; padding:10px 14px; font-size:13px;">↓</a>' +
        '</div>' +
      '</div>'
    );
  }

  // --- PDF preview modal ---
  window.sdsmOpenPdf = function (attachId, fileName, parentNumber, rev, changeNumber) {
    var url = '/api/sdsm/file/' + encodeURIComponent(attachId)
      + '?fileName=' + encodeURIComponent(fileName)
      + '&parentNumber=' + encodeURIComponent(parentNumber)
      + (rev ? '&rev=' + encodeURIComponent(rev) : '');
    document.getElementById('sdsmPdfTitle').textContent = fileName;
    document.getElementById('sdsmPdfSubtitle').textContent =
      parentNumber + (rev ? '  ·  Rev ' + rev : '') + (changeNumber ? '  ·  ' + changeNumber : '');
    var dl = document.getElementById('sdsmPdfDownload');
    dl.href = url + '&inline=false';
    dl.setAttribute('download', fileName);
    document.getElementById('sdsmPdfFrame').src = url;
    document.getElementById('sdsmPdfModal').style.display = '';
  };
  window.sdsmClosePdf = function () {
    document.getElementById('sdsmPdfFrame').src = 'about:blank';
    document.getElementById('sdsmPdfModal').style.display = 'none';
  };

  // --- Active QN deviations banner ---
  function loadActiveDeviationsBanner() {
    fetch('/api/sdsm/active-deviations', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : { results: [] }; })
      .then(function (data) {
        var devs = (data && data.results) || [];
        var banner = document.getElementById('sdsmDevBanner');
        var body = document.getElementById('sdsmDevBannerBody');
        if (!devs.length) { banner.style.display = 'none'; return; }
        // Dedup by changeNumber — one deviation can yield N attachment rows here.
        var seen = {};
        var lines = [];
        devs.forEach(function (d) {
          if (seen[d.changeNumber]) return;
          seen[d.changeNumber] = true;
          var eff = d.effectiveTo ? (' (through ' + escapeHtml(d.effectiveTo) + ')') : '';
          // Per-deviation "Download all" link — calls /api/sdsm/deviation-zip/{change}
          // which bundles every attachment for the deviation as a zip, fetched via
          // the Agile SDK (no local-cache shortcut). Native <a download> handles the
          // file save without needing JS state.
          var zipUrl = '/api/sdsm/deviation-zip/' + encodeURIComponent(d.changeNumber);
          var dlLink = '<a href="' + zipUrl + '" download="' + escapeAttr(d.changeNumber) + '_attachments.zip" ' +
            'title="Download every attachment on this deviation as a zip (via Agile SDK)" ' +
            'style="margin-left:8px; padding:2px 8px; border:1px solid #B8342B; border-radius:10px; ' +
            'color:#B8342B; text-decoration:none; font-size:11px; font-weight:600;">&darr; zip</a>';
          lines.push(
            '<div style="margin:3px 0;">' +
              '<strong>' + escapeHtml(d.changeNumber) + '</strong>: ' +
              escapeHtml(d.parentDescription || '') + eff +
              dlLink +
            '</div>'
          );
        });
        body.innerHTML = lines.join('');
        banner.style.display = '';
      })
      .catch(function () { /* silent — not critical */ });
  }

  function renderError(err) {
    var resultsDiv = document.getElementById('sdsmResults');
    document.getElementById('sdsmEmpty').style.display = 'none';
    document.getElementById('sdsmNoMatch').style.display = 'none';
    document.getElementById('sdsmStatusStrip').style.display = 'none';
    document.getElementById('sdsmSpecFilter').style.display = 'none';
    resultsDiv.innerHTML =
      '<div style="margin:14px 0; padding:14px 18px; background:#fdeaea; border-left:4px solid #B8342B; border-radius:0 6px 6px 0; color:#B8342B; font-size:13px;">' +
      'Search failed: ' + escapeHtml(String(err && err.message || err)) + '</div>';
  }

  function formatBytes(n) {
    if (!n || n <= 0) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function escapeAttr(s) {
    return String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  }
})();
