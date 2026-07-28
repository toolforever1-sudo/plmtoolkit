/* Meeting Mode — live sessions, bubbles & meeting records.
 *
 * Vanilla-JS port of the approved `Meeting Live.dc.html` prototype (see
 * MEETING-MODE-IMPLEMENTATION.md). Owns the My Enhancements / Meeting Mode /
 * Meeting records views; "All Enhancements" stays the existing editable grid
 * in it-enhancements.js. Rendered into #iteMMRoot; the top sub-nav pills in
 * index.html drive view switching via window.MeetingMode.show(view).
 *
 * Capture happens in the centre detail pane: editing Target UAT / Go-Live /
 * IT-log and pressing "Save to Agile" writes one AGILE bubble per changed
 * field (live per-user write under the caller's AD identity). A Notes field
 * (+ optional assignee) writes a NOTE bubble. IT Status is read-only.
 *
 * Backend: /api/meeting/* (sessions, bubbles, wrap, send, records,
 * action-items) + /api/it-enhancements/{save-cell,agile-signin} for the
 * unrecorded My-Enhancements saves and the per-user Agile sign-in.
 *
 * Styling uses the toolkit design tokens (tokens.css) verbatim. Modals use the
 * ui-modal.js helpers (appAlert/appConfirm/appPrompt), never native dialogs.
 */
(function () {
  'use strict';

  // ---- state -----------------------------------------------------------
  var S = {
    view: 'meeting',          // myenh | meeting | records | record
    phase: 'pre',             // pre | live | wrap | ended
    rows: [], user: { loginId: '', displayName: '', isItTeam: false },
    items: [],                // mapped enhancement view-models
    room: [],                 // [{ad,name,leader}]
    addPeopleOpen: false,
    addPeopleSel: {},         // ad → true while picking (multi-select)
    _roster: [],              // roster snapshot backing the add-people dropdown
    sessionId: null, bubbles: [], bubbleSeq: 0,
    selectedId: null,
    elapsed: 0, _timer: null,
    drawer: null,             // grade | agenda
    grade: 'idle', progress: 0, refreshed: 0, gradeData: null, _grade: null,
    gradeOpen: {},            // grade driver key → expanded (drill-down ECN list)
    // ---- Backlog Grade revamp (centered modal: explainer/trend/disputes/history) ----
    gradeUi: {
      rule: { graceDays: 0, includeNoOwner: true, missingPenalty: 6 },  // runtime grading config
      tab: 'breakdown',        // breakdown | atrisk | chat | history
      explainer: false,
      model: '@vertexai-global/anthropic.claude-opus-4-8', models: [],  // default grading model (Opus 4.8); models = fetched catalog
      excluded: {},            // ecn → true (accepted item disputes this session)
      policyExcluded: {},      // ecn → true (dropped by a standing theme policy; not counted at all)
      policies: [],            // active standing policies [{id,predicate,count}]
      ruleApplied: {},         // ruleKey → true (accepted rule disputes this session)
      disputeFor: null,        // ecn (item) | 'rule:<key>' | null
      disputeText: '', disputeFile: null, aiReply: null, busy: false,
      flash: null,             // { text, to }
      history: { logic: [], accepts: [], outbox: [] }, historyLoaded: false,
      chat: [], chatSeeded: false,
      lastScore: null, lastLetter: null,
    },
    agendaAxis: 'requestor', agendaStatus: {},
    records: [], recordsLoaded: false, selectedRecord: null, recordData: null,
    myEdits: {}, mySaved: {},
    busy: false,
    // ---- v2 workspace ----
    railFilter: 'all',        // urgency filter pill
    railSearch: '',           // live substring filter (ANDed with pill)
    reqFilter: {},            // selected requestor names (empty = all) — multi-select
    prioFilter: {},           // selected priorities (empty = all) — multi-select
    statusFilter: {},         // selected IT statuses (empty = all) — multi-select
    reqOpen: false, prioOpen: false, statusOpen: false,
    recSelect: {},            // selected meeting-record ids (admin consolidate)
    noteHistory: {}, _noteHistEcn: null,  // per-ECN prior-notes timeline cache
    railW: railWidthInit(),   // resizable rail width (persisted)
    maximized: false,         // full-screen workspace (session-only)
  };
  function anySel(map) { for (var k in map) if (map[k]) return true; return false; }

  // Urgency filter pills (mirror the All Enhancements grid).
  var FILTERS = [
    { k: 'all', label: 'All', tone: 'ink' },
    { k: 'overdue', label: 'Overdue UAT / Go-Live', tone: 'bad' },
    { k: 'approaching', label: 'Approaching', tone: 'warn' },
    { k: 'no_owner', label: 'No IT owner', tone: 'bad' },
    { k: 'live_verify', label: 'Live · verify in prod', tone: 'accent' },
    { k: 'on_track', label: 'On track', tone: 'good' },
  ];
  var TONE = { bad: 'var(--bad)', warn: 'var(--warn)', good: 'var(--good)', ink: 'var(--ink)', accent: 'var(--accent)' };

  function railWidthInit() {
    var w = 300;
    try { var v = parseInt(localStorage.getItem('mm-rail-width'), 10); if (v >= 220 && v <= 600) w = v; } catch (e) {}
    return w;
  }

  // ---- palette / small UI atoms ---------------------------------------
  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function ecnShortJs(id) { return String(id == null ? '' : id).replace('-PROJ', ''); }
  // Agile webclient object URL — same convention as the grid's
  // agileChangeLinkHtml (reuses window.iteAgileBaseUrl so the base tracks
  // STATE.meta / the env config; type = prefix before the first hyphen).
  function ecnUrl(id) {
    if (!id) return '';
    var base = (window.iteAgileBaseUrl && window.iteAgileBaseUrl())
      || window.AGILE_WEBCLIENT_URL || 'https://plm.sandisk.com/Agile';
    var n = String(id).trim();
    var dash = n.indexOf('-');
    var type = dash > 0 ? n.substring(0, dash) : 'Change';
    return base + '/object/' + encodeURIComponent(type) + '/' + encodeURIComponent(n);
  }
  // An <a> opening the full ECN in Agile (new tab), showing `display`.
  function ecnA(fullId, display, style) {
    return '<a href="' + ecnUrl(fullId) + '" target="_blank" rel="noopener" title="Open '
      + esc(fullId) + ' in Agile (new tab)" style="' + (style || '') + '">' + esc(display) + '</a>';
  }
  function badge(state, label) {
    var c = state === 'failed' ? ['var(--bad-bg)', 'var(--bad-ink)']
      : state === 'warn' ? ['var(--warn-bg)', '#856404']
      : state === 'sent' ? ['var(--accent-2)', 'var(--accent-ink)']
      : ['var(--surface-3)', 'var(--ink-3)'];
    return '<span style="display:inline-flex;align-items:center;font-family:var(--font-mono);font-size:9px;'
      + 'letter-spacing:.04em;padding:2px 7px;border-radius:4px;background:' + c[0] + ';color:' + c[1] + '">'
      + esc(label) + '</span>';
  }
  function btn(label, primary, onclick, h) {
    var s = 'font-family:var(--font-sans);font-size:12.5px;font-weight:500;border-radius:6px;padding:0 13px;'
      + 'height:' + (h || 36) + 'px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;'
      + (primary ? 'border:1px solid var(--accent);background:var(--accent);color:#fff'
                 : 'border:1px solid var(--line-2);background:#fff;color:var(--ink)');
    return '<button onclick="' + onclick + '" style="' + s + '">' + label + '</button>';
  }
  // Priority pill — red Urgent, amber High, neutral otherwise.
  function prioBadge(p) {
    if (!p) return '';
    var pl = p.toLowerCase();
    var c = pl.indexOf('urgent') >= 0 ? ['var(--bad-bg)', 'var(--bad-ink)']
      : pl.indexOf('high') >= 0 ? ['var(--warn-bg)', '#856404']
      : ['var(--surface-3)', 'var(--ink-3)'];
    return '<span title="Priority" style="display:inline-flex;align-items:center;font-family:var(--font-mono);font-size:8.5px;letter-spacing:.04em;padding:2px 7px;border-radius:4px;background:' + c[0] + ';color:' + c[1] + '">' + esc(p) + '</span>';
  }
  function distinct(getter) {
    var seen = {}, out = [];
    (S.items || []).forEach(function (it) { var v = (getter(it) || '').trim(); if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); } });
    return out.sort(function (a, b) { return a.localeCompare(b); });
  }
  // A multi-select filter dropdown (Requestor / Priority). Options are toggled
  // by index (itemFn(i)) so names with quotes/apostrophes never break the
  // inline handler; itemFn(-1) clears.
  function filterDropdown(label, open, options, selMap, toggleFn, itemFn) {
    var n = 0; for (var k in selMap) if (selMap[k]) n++;
    var btnStyle = 'display:inline-flex;align-items:center;gap:5px;font-size:11.5px;padding:6px 10px;border-radius:6px;cursor:pointer;white-space:nowrap;'
      + (n ? 'background:var(--accent-2);color:var(--accent-ink);border:1px solid var(--accent)' : 'background:#fff;color:var(--ink-2);border:1px solid var(--line-2)');
    var dd = '';
    if (open) {
      var rows = options.length ? options.map(function (o, i) {
        var on = !!selMap[o];
        return '<div onclick="MeetingMode.' + itemFn + '(' + i + ')" style="display:flex;align-items:center;gap:8px;font-size:12px;color:var(--ink-2);padding:6px 8px;border-radius:5px;cursor:pointer" onmouseover="this.style.background=\'var(--surface-2)\'" onmouseout="this.style.background=\'\'">'
          + '<span style="width:14px;height:14px;border-radius:4px;border:1.5px solid ' + (on ? 'var(--accent)' : 'var(--line-2)') + ';background:' + (on ? 'var(--accent)' : '#fff') + ';color:#fff;display:flex;align-items:center;justify-content:center;font-size:9px;flex:none">' + (on ? '✓' : '') + '</span>'
          + '<span>' + esc(o) + '</span></div>';
      }).join('') : '<div style="font-size:12px;color:var(--ink-4);padding:6px 8px">None.</div>';
      dd = '<div style="position:absolute;top:32px;right:0;z-index:30;background:#fff;border:1px solid var(--line-2);border-radius:8px;box-shadow:var(--shadow-pop);min-width:200px;max-height:300px;overflow:auto;padding:5px">'
        + '<div style="display:flex;align-items:center;justify-content:space-between;padding:4px 8px 6px"><span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.1em;text-transform:uppercase;color:var(--ink-4)">' + esc(label) + '</span>'
        + (n ? '<span onclick="MeetingMode.' + itemFn + '(-1)" style="font-size:10px;color:var(--accent);cursor:pointer">clear</span>' : '') + '</div>'
        + rows + '</div>';
    }
    return '<span style="position:relative;display:inline-flex;flex:none"><span onclick="MeetingMode.' + toggleFn + '()" style="' + btnStyle + '">' + esc(label) + (n ? ' (' + n + ')' : '') + ' ▾</span>' + dd + '</span>';
  }

  // ---- date helpers ----------------------------------------------------
  function midnight(d) { var x = new Date(d); x.setHours(0, 0, 0, 0); return x; }
  function parseD(s) {
    if (!s) return null;
    s = String(s).trim(); if (!s) return null;
    var m = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (m) return midnight(new Date(+m[1], +m[2] - 1, +m[3]));
    m = s.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})/);
    if (m) return midnight(new Date(+m[3], +m[1] - 1, +m[2]));
    var t = Date.parse(s); return isNaN(t) ? null : midnight(new Date(t));
  }
  function isoOf(s) {
    var d = parseD(s); if (!d) return '';
    var mm = ('0' + (d.getMonth() + 1)).slice(-2), dd = ('0' + d.getDate()).slice(-2);
    return d.getFullYear() + '-' + mm + '-' + dd;
  }
  function fmtMD(s) {
    var d = parseD(s); if (!d) return '';
    return ('0' + (d.getMonth() + 1)).slice(-2) + '/' + ('0' + d.getDate()).slice(-2) + '/' + d.getFullYear();
  }
  function daysUntil(s) { var d = parseD(s); if (!d) return null; return Math.round((d - midnight(new Date())) / 86400000); }
  function clock(d) { d = d || new Date(); return d.getHours() + ':' + ('0' + d.getMinutes()).slice(-2); }
  function initials(name) {
    var p = String(name || '').replace(/[.,]/g, '').split(/\s+/).filter(Boolean);
    return (p.length > 1 ? p[0][0] + p[1][0] : (p[0] || '?').slice(0, 1)).toUpperCase();
  }
  function lc(s) { return String(s || '').toLowerCase(); }
  function isUnassigned(o) { return !o || /unassigned/i.test(o); }

  // ---- stage-aware overdue --------------------------------------------
  // A past Target UAT date only means "overdue" if the item HASN'T reached
  // UAT yet. Once the IT Status says it's in UAT or beyond (UAT, UAT complete,
  // CAB prep, sign-off, scheduled, live, done…), a past UAT date is expected,
  // not overdue. Same idea for go-live vs. items already live/done.
  function uatReached(status) {
    return /\buat\b|cab|sign.?off|schedul|deploy|go.?live|\blive\b|\bprod|\bdone\b|\bcomplet|clos(e|ed)?\b|releas/i.test(status || '');
  }
  function liveStage(status) {
    return /\blive\b|in prod|production|deployed|\bdone\b|\bcomplet|clos(e|ed)?\b|releas/i.test(status || '');
  }
  // "Completed", "Complete", "Closed", "Released", "Cancelled", "Done" — terminal
  // IT statuses. These items are finished, so they drop out of the grade entirely
  // (not overdue, not counted). \bcomplet so "Incomplete" doesn't match.
  function doneStage(status) { return /\bdone\b|\bcomplet|clos(e|ed)?\b|releas|cancel/i.test(status || ''); }
  function overdueUat(it) { var du = daysUntil(it.uat); return du !== null && du < 0 && !uatReached(it.status); }
  function overdueGoLive(it) { var dg = daysUntil(it.goLive); return dg !== null && dg < 0 && !liveStage(it.status); }
  function anyOverdue(it) { return overdueUat(it) || overdueGoLive(it); }

  // ---- map rows → enhancement view-models ------------------------------
  function buildItems() {
    S.items = (S.rows || []).map(function (r) {
      var owner = r.itOwner || 'Unassigned';
      var prio = (r.priority || '').toLowerCase();
      var hi = prio.indexOf('urgent') >= 0 || prio.indexOf('high') >= 0;
      var it = {
        id: r.ecnNumber, title: r.proposal || r.problemStatement || '',
        requestor: r.requestor || '', requestorLoginId: r.requestorLoginId || '',
        owner: owner, ownerLoginId: r.itOwnerLoginId || '',
        status: r.status || '', uat: r.targetUAT || '', goLive: r.targetGoLive || '',
        priority: (r.priority || '').trim(), hiPri: hi,
      };
      it.badges = computeBadges(it);
      it.flags = computeFlags(it);
      it.score = computeScore(it);
      it.stage = computeStage(it);
      var t = agendaText(it); it.discuss = t.discuss; it.closeWhen = t.closeWhen; it.blocker = t.blocker; it.next = t.next;
      return it;
    });
    if (!S.selectedId && S.items.length) S.selectedId = S.items[0].id;
  }
  function computeBadges(it) {
    var b = [], dg = daysUntil(it.goLive), done = doneStage(it.status);
    if (overdueUat(it)) b.push({ state: 'failed', label: 'Overdue UAT' });
    if (overdueGoLive(it)) b.push({ state: 'failed', label: 'Overdue go-live' });
    else if (dg !== null && dg >= 0 && dg <= 7 && !done) b.push({ state: 'warn', label: 'Go-live · ' + dg + 'd' });
    if (isUnassigned(it.owner) && it.hiPri) b.push({ state: 'failed', label: 'No IT owner' });
    if (!b.length) {
      if (/sign.?off/i.test(it.status)) b.push({ state: 'warn', label: 'Sign-off' });
      else b.push({ state: 'sent', label: done ? 'Done' : 'On track' });
    }
    return b.slice(0, 2);
  }
  function computeScore(it) {
    var s = 0, du = daysUntil(it.uat), dg = daysUntil(it.goLive), done = doneStage(it.status);
    if (done) return 0;
    if (overdueUat(it)) s += 140 + Math.min(-du, 30);
    if (overdueGoLive(it)) s += 120 + Math.min(-dg, 30);
    else if (dg !== null && dg >= 0 && dg <= 7) s += 80;
    if (isUnassigned(it.owner) && it.hiPri) s += 60;
    if (isUnassigned(it.owner)) s += 20;
    if (!it.uat || !it.goLive) s += 15;
    if (/wip|uat|sign/i.test(it.status)) s += 10;
    return s;
  }
  function computeStage(it) {
    if (/done/i.test(it.status)) return 'Done';
    if (/sign.?off|uat/i.test(it.status)) return 'UAT sign-off';
    if (/schedul/i.test(it.status)) return 'Scheduling';
    if (isUnassigned(it.owner)) return 'Scheduling';
    return 'Build / WIP';
  }
  // Urgency flags (drive the filter pills). One item can carry several.
  function computeFlags(it) {
    var flags = [], dg = daysUntil(it.goLive), done = doneStage(it.status);
    var overdue = anyOverdue(it);
    var approaching = (!done) && ((dg !== null && dg >= 0 && dg <= 7) || /sign.?off/i.test(it.status));
    var liveVerify = /live[\s,]*need to verify in production/i.test(it.status);
    if (overdue) flags.push('overdue');
    if (approaching) flags.push('approaching');
    if (isUnassigned(it.owner)) flags.push('no_owner');
    if (liveVerify) flags.push('live_verify');
    if (!flags.length) flags.push('on_track');
    return flags;
  }
  function itemMatchesFilter(it, f) { return f === 'all' || (it.flags || []).indexOf(f) !== -1; }
  function agendaText(it) {
    var du = daysUntil(it.uat), dg = daysUntil(it.goLive), parts = [], blocker = 'None', next = 'Continue; status next week';
    if (isUnassigned(it.owner)) { parts.push('assign an IT owner'); blocker = 'No owner'; next = 'Assign an owner'; }
    if (overdueUat(it)) { parts.push('reschedule UAT (slipped ' + (-du) + 'd)'); blocker = 'UAT slipped ' + (-du) + 'd'; next = 'Set a new UAT date'; }
    if (dg !== null && dg >= 0 && dg <= 7) { parts.push('confirm go-live in ' + dg + 'd'); if (blocker === 'None') { blocker = 'Go-live in ' + dg + 'd'; next = 'Confirm the go-live'; } }
    if (overdueGoLive(it)) { parts.push('go-live is overdue'); if (blocker === 'None') { blocker = 'Go-live overdue ' + (-dg) + 'd'; next = 'Reschedule go-live'; } }
    if (!it.uat || !it.goLive) parts.push('set the missing target date(s)');
    if (!parts.length) parts.push('quick status only; low risk');
    return { discuss: parts.join('; ') + '.', closeWhen: 'owner named & dates confirmed.', blocker: blocker, next: next };
  }
  function itemById(id) { for (var i = 0; i < S.items.length; i++) if (S.items[i].id === id) return S.items[i]; return S.items[0]; }
  function inRoom(ad) { ad = lc(ad); for (var i = 0; i < S.room.length; i++) if (lc(S.room[i].ad) === ad) return true; return false; }
  function roster() {
    var seen = {}, out = [];
    S.room.forEach(function (p) { seen[lc(p.ad)] = true; });
    S.items.forEach(function (it) {
      [[it.ownerLoginId, it.owner], [it.requestorLoginId, it.requestor]].forEach(function (pr) {
        var ad = pr[0], nm = pr[1];
        if (!ad || isUnassigned(nm) || seen[lc(ad)]) return;
        seen[lc(ad)] = true; out.push({ ad: ad, name: nm });
      });
    });
    return out.sort(function (a, b) { return a.name.localeCompare(b.name); });
  }

  // ---- bubbles & notify ------------------------------------------------
  function bubblesOf(id) { return S.bubbles.filter(function (b) { return b.ecn === id; }); }
  function notifySet() {
    var set = {};
    S.bubbles.forEach(function (b) {
      if (b.kind === 'AGILE' && b.ownerNotInRoom && b.ownerName) set[b.ownerName] = true;
      if (b.kind === 'NOTE' && b.assigneeName && !inRoom(b.assigneeAd)) set[b.assigneeName] = true;
    });
    return Object.keys(set);
  }
  function agileCount() { return S.bubbles.filter(function (b) { return b.kind === 'AGILE'; }).length; }
  function noteCount() { return S.bubbles.filter(function (b) { return b.kind === 'NOTE'; }).length; }

  // ---- API -------------------------------------------------------------
  function api(method, url, body) {
    return fetch(url, {
      method: method, credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    }).then(function (r) { return r.json().catch(function () { return {}; }); });
  }
  // Run an Agile-writing action; on needsAgileSignin prompt for the password,
  // verify, then retry once.
  function withAgile(doIt) {
    return doIt().then(function (resp) {
      if (resp && resp.needsAgileSignin) {
        return promptAgileSignin().then(function (ok) { return ok ? doIt() : resp; });
      }
      return resp;
    });
  }
  function promptAgileSignin() {
    return new Promise(function (resolve) {
      // Reuse the grid's proper masked-password Agile sign-in modal when
      // available (never a cleartext prompt — the leader's screen may be
      // shared). Falls back to a text prompt only if the modal is absent.
      if (window.iteOpenAgileSignin) { window.iteOpenAgileSignin(function (ok) { resolve(!!ok); }); return; }
      window.appPrompt('Enter your Agile (AD) password to write under your own identity. '
        + 'It is held only for this session and never stored.', '', { title: 'Sign in to Agile', okText: 'Sign in' })
        .then(function (pw) {
          if (!pw) return resolve(false);
          api('POST', '/api/it-enhancements/agile-signin', { password: pw }).then(function (r) {
            if (r && r.ok) resolve(true);
            else { window.appAlert((r && r.error) || 'Agile sign-in failed.', { title: 'Sign-in failed' }); resolve(false); }
          });
        }).catch(function () { resolve(false); });
    });
  }

  // ---- mount / public API ---------------------------------------------
  function root() { return document.getElementById('iteMMRoot'); }
  function setData(rows, user) {
    S.rows = rows || [];
    if (user) S.user = user;
    buildItems();
    if (isActive()) render();
  }
  function show(view) {
    S.view = (view === 'my' || view === 'myenh') ? 'myenh'
      : (view === 'records' || view === 'record') ? (S.view === 'record' ? 'record' : 'records')
      : 'meeting';
    if (S.view === 'records') { loadRecords(); }
    var el = root(); if (el) el.style.display = 'block';
    render();
  }
  function hide() { var el = root(); if (el) el.style.display = 'none'; document.body.classList.remove('mm-fullscreen'); }
  function isActive() { var el = root(); return el && el.style.display !== 'none'; }
  // Full-screen only applies on the live meeting workspace.
  function applyChrome() { document.body.classList.toggle('mm-fullscreen', !!S.maximized && S.view === 'meeting' && isActive()); }
  // Filter the rail by the search box without re-rendering (keeps input focus);
  // also drives the "No items match" empty state.
  function applyRailSearch() {
    var rail = document.getElementById('mm-rail'); if (!rail) return;
    var q = (S.railSearch || '').trim().toLowerCase();
    var reqOn = anySel(S.reqFilter), prioOn = anySel(S.prioFilter), statusOn = anySel(S.statusFilter);
    var reqSel = {}, prioSel = {}, statusSel = {};
    for (var k in S.reqFilter) if (S.reqFilter[k]) reqSel[k.toLowerCase()] = 1;
    for (var p in S.prioFilter) if (S.prioFilter[p]) prioSel[p.toLowerCase()] = 1;
    for (var st in S.statusFilter) if (S.statusFilter[st]) statusSel[st.toLowerCase()] = 1;
    var rows = rail.querySelectorAll('[data-search]'), visible = 0;
    for (var i = 0; i < rows.length; i++) {
      var m = (!q || rows[i].getAttribute('data-search').indexOf(q) >= 0)
        && (!reqOn || reqSel[rows[i].getAttribute('data-requestor')])
        && (!prioOn || prioSel[rows[i].getAttribute('data-priority')])
        && (!statusOn || statusSel[rows[i].getAttribute('data-status')]);
      rows[i].style.display = m ? '' : 'none';
      if (m) visible++;
    }
    var empty = document.getElementById('mm-rail-empty');
    if (empty) empty.style.display = visible ? 'none' : '';
  }
  function railResize(e) {
    e.preventDefault();
    var startX = e.clientX, startW = S.railW, rail = document.getElementById('mm-rail');
    function move(ev) { var w = Math.max(220, Math.min(600, startW + (ev.clientX - startX))); S.railW = w; if (rail) rail.style.width = w + 'px'; }
    function up() { document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up); document.body.style.userSelect = ''; try { localStorage.setItem('mm-rail-width', S.railW); } catch (e2) {} }
    document.body.style.userSelect = 'none';
    document.addEventListener('mousemove', move); document.addEventListener('mouseup', up);
  }

  // ---- room init -------------------------------------------------------
  function ensureLeader() {
    if (S.room.length) return;
    var me = { ad: S.user.loginId || 'me', name: S.user.displayName || S.user.loginId || 'Me', leader: true };
    S.room = [me];
  }

  // =====================================================================
  // RENDER
  // =====================================================================
  function render() {
    var el = root(); if (!el) return;
    ensureLeader();
    // Preserve the rail's scroll position across re-renders (e.g. selecting a
    // row re-renders the rail for the highlight) so the list doesn't jump to
    // the top. Reset only when the list itself changes (filter/search), via
    // S._resetRailScroll set by those handlers.
    var prevRail = document.getElementById('mm-rail');
    var keepScroll = (prevRail && !S._resetRailScroll) ? prevRail.scrollTop : 0;
    S._resetRailScroll = false;
    // Preserve the grade panel's scroll across re-renders so it doesn't jump to
    // the top (PT-103). The chat tab pins to the newest message instead.
    var prevGradeScroll = document.getElementById('mm-grade-scroll');
    var keepGradeScroll = prevGradeScroll ? prevGradeScroll.scrollTop : 0;
    var html = '';
    if (S.view === 'myenh') html = renderMyEnh();
    else if (S.view === 'records') html = renderRecordsList();
    else if (S.view === 'record') html = renderRecordDetail();
    else html = renderMeeting();
    html += renderDrawers();
    el.innerHTML = html;
    applyChrome();
    applyRailSearch();   // re-apply the active search after any re-render
    var newRail = document.getElementById('mm-rail');
    if (newRail) newRail.scrollTop = keepScroll;   // preserve (or 0 when a filter/search reset it)
    var gs = document.getElementById('mm-grade-scroll');
    if (gs) gs.scrollTop = (S.drawer === 'grade' && S.gradeUi.tab === 'chat') ? gs.scrollHeight : keepGradeScroll;
  }

  // ---- MY ENHANCEMENTS -------------------------------------------------
  function myItems() {
    var me = lc(S.user.loginId);
    return S.items.filter(function (it) { return me && lc(it.ownerLoginId) === me; });
  }
  function prioRank(p) { var s = (p || '').toLowerCase(); return s.indexOf('urgent') >= 0 ? 4 : s.indexOf('high') >= 0 ? 3 : s.indexOf('med') >= 0 ? 2 : s.indexOf('low') >= 0 ? 1 : 0; }
  function myReasons(it) {
    var r = [];
    if (overdueUat(it)) r.push({ label: 'UAT overdue', tone: 'bad' });
    if (overdueGoLive(it)) r.push({ label: 'Go-live overdue', tone: 'bad' });
    if (prioRank(it.priority) >= 3) r.push({ label: 'High priority', tone: 'warn' });
    return r;
  }
  function reasonChip(rc) {
    var c = rc.tone === 'bad' ? ['var(--bad-bg)', 'var(--bad-ink)'] : ['var(--warn-bg)', '#856404'];
    return '<span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.03em;padding:2px 7px;border-radius:4px;background:' + c[0] + ';color:' + c[1] + '">' + esc(rc.label) + '</span>';
  }
  function renderMyRow(it, idx, attn) {
    var ed = S.myEdits[it.id] || {}, saved = S.mySaved[it.id];
    var badges = it.badges.map(function (b) { return badge(b.state, b.label); }).join(' ');
    var reasons = attn ? myReasons(it).map(reasonChip).join(' ') : '';
    var prioTag = (attn && it.priority) ? '<span style="font-family:var(--font-mono);font-size:9.5px;padding:2px 7px;border-radius:4px;background:var(--surface-2);color:var(--ink-2);border:1px solid var(--line)">' + esc(it.priority) + '</span>' : '';
    return '<div style="padding:14px 16px;border-bottom:1px solid var(--line);' + (attn ? 'border-left:3px solid var(--bad)' : '') + '">'
      + '<div style="display:flex;align-items:center;gap:9px;flex-wrap:wrap">'
      + ecnA(it.id, it.id, 'font-family:var(--font-mono);font-size:12.5px;color:var(--accent);text-decoration:none;font-weight:500')
      + badges + prioTag + reasons
      + (attn ? '' : '<span style="display:inline-flex;align-items:center;gap:5px;font-family:var(--font-mono);font-size:10px;padding:2px 8px;border-radius:5px;background:var(--surface-2);color:var(--ink-2);border:1px solid var(--line)">IT status · <b style="color:var(--ink)">' + esc(it.status || '—') + '</b><span style="font-size:8.5px;letter-spacing:.04em;color:var(--ink-4)">AGILE · READ-ONLY</span></span>')
      + '<span id="mm-my-saved-' + idx + '" style="font-family:var(--font-mono);font-size:10px;color:var(--good-ink);margin-left:auto;' + (saved ? '' : 'display:none') + '">✓ Saved to Agile · just now</span>'
      + '</div>'
      + '<div style="font-size:12.5px;color:var(--ink-3);margin-top:4px">' + esc(it.title) + '</div>'
      + (attn ? '<div style="display:flex;align-items:center;gap:6px;margin-top:8px"><span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.05em;text-transform:uppercase;color:var(--ink-4)">IT status</span><span style="font-size:12px;color:var(--ink)">' + esc(it.status || '—') + '</span><span style="font-family:var(--font-mono);font-size:8px;letter-spacing:.04em;color:var(--ink-4);border:1px solid var(--line);border-radius:4px;padding:1px 5px">AGILE · READ-ONLY</span></div>' : '')
      + '<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:10px 14px;margin-top:' + (attn ? '10px' : '12px') + ';max-width:600px">'
      + myField('Target UAT', 'mm-my-uat-' + idx, 'date', isoOf(ed.uat !== undefined ? ed.uat : it.uat), idx)
      + myField('Target Go-Live', 'mm-my-golive-' + idx, 'date', isoOf(ed.goLive !== undefined ? ed.goLive : it.goLive), idx)
      + myField('IT log note', 'mm-my-log-' + idx, 'text', '', idx)
      + '</div>'
      + '<div style="display:flex;align-items:center;gap:10px;margin-top:11px;max-width:600px">'
      + '<span style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4)">Edits are saved to Agile only when you click Save.</span>'
      + '<span style="margin-left:auto"></span>'
      + '<button id="mm-my-savebtn-' + idx + '" onclick="MeetingMode._mysave(' + idx + ')" style="font-family:var(--font-sans);font-size:12.5px;font-weight:500;border-radius:6px;padding:0 13px;height:32px;cursor:pointer;border:1px solid var(--accent);background:var(--accent);color:#fff;display:inline-flex;align-items:center;gap:5px">Save to Agile →</button>'
      + '</div></div>';
  }
  function renderMyEnh() {
    var mine = myItems();
    // keep original index (mySave reads myItems()[idx]); split + sort attention-first
    var withIdx = mine.map(function (it, i) { return { it: it, idx: i, attn: overdueUat(it) || overdueGoLive(it) || prioRank(it.priority) >= 3 }; });
    var attn = withIdx.filter(function (x) { return x.attn; }).sort(function (a, b) {
      var pr = prioRank(b.it.priority) - prioRank(a.it.priority); if (pr) return pr;
      var da = daysUntil(a.it.uat), db = daysUntil(b.it.uat);
      return (da === null ? 1e9 : da) - (db === null ? 1e9 : db);
    });
    var ok = withIdx.filter(function (x) { return !x.attn; });
    var sectionHdr = function (label, n, danger) {
      return '<div style="display:flex;align-items:center;gap:8px;padding:9px 16px;border-bottom:1px solid var(--line);background:' + (danger ? 'var(--bad-bg)' : 'var(--surface-2)') + '">'
        + (danger ? '<span style="font-size:12px;color:var(--bad-ink)">▲</span>' : '')
        + '<span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:' + (danger ? 'var(--bad-ink)' : 'var(--ink-3)') + ';font-weight:600">' + label + '</span>'
        + '<span style="font-family:var(--font-mono);font-size:10px;color:' + (danger ? 'var(--bad-ink)' : 'var(--ink-4)') + '">' + n + '</span>'
        + (danger ? '<span style="font-size:11px;color:var(--bad-ink);margin-left:auto;line-height:1.3">Behind schedule or high priority</span>' : '') + '</div>';
    };
    var body = '';
    if (!mine.length) body = '<div style="padding:30px 16px;text-align:center;color:var(--ink-4);font-size:13px">You don\'t own any enhancements right now.</div>';
    else {
      if (attn.length) body += sectionHdr('Needs your attention', attn.length, true) + attn.map(function (x) { return renderMyRow(x.it, x.idx, true); }).join('');
      body += sectionHdr('On track', ok.length, false) + ok.map(function (x) { return renderMyRow(x.it, x.idx, false); }).join('');
    }
    return '<div style="border:1px solid var(--line);border-radius:8px;background:#fff;overflow:hidden">'
      + '<div style="display:flex;align-items:center;gap:14px;padding:13px 16px;border-bottom:1px solid var(--line);flex-wrap:wrap">'
      + '<div><div style="font-family:var(--font-serif);font-size:15px;font-weight:500">My Enhancements</div>'
      + '<div style="font-family:var(--font-mono);font-size:10.5px;color:var(--ink-3);margin-top:4px">You own ' + mine.length + ' · sorted so what needs you is first · Save per row</div></div>'
      + '<span style="display:inline-flex;align-items:center;gap:6px;font-family:var(--font-mono);font-size:9.5px;letter-spacing:.06em;padding:3px 9px;border-radius:999px;background:var(--surface-2);color:var(--ink-3);border:1px solid var(--line)">○ NOT RECORDED</span>'
      + '</div>' + body + '</div>';
  }
  // Plain input — edits are held in the DOM until the row's Save button fires.
  // No per-field auto-save: rapid blur-saves to the same change object raced
  // and tripped Agile's optimistic lock (PCAPIException 531). data-1p-ignore /
  // data-lpignore + autocomplete=off keep password managers off these fields.
  function myField(label, id, type, value) {
    return '<label style="display:flex;flex-direction:column;gap:4px">'
      + '<span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-4)">' + esc(label) + '</span>'
      + '<input id="' + id + '" type="' + type + '" value="' + esc(value) + '" data-orig="' + esc(value) + '" autocomplete="off" data-1p-ignore data-lpignore="true" '
      + (type === 'text' ? 'placeholder="optional…" ' : '')
      + 'style="font-family:' + (type === 'date' ? 'var(--font-mono)' : 'var(--font-sans)') + ';font-size:12.5px;border:1px solid var(--line-2);border-radius:6px;padding:6px 8px;color:var(--ink)"></label>';
  }
  // Save one My-Enhancements row to Agile. Reads the row's inputs from the DOM,
  // diffs against the item, and writes the changed cells SEQUENTIALLY (awaiting
  // each) so two writes never hit the same change object at once.
  function mySave(idx) {
    var mine = myItems(); var it = mine[idx]; if (!it) return;
    var uatEl = document.getElementById('mm-my-uat-' + idx);
    var goEl = document.getElementById('mm-my-golive-' + idx);
    var logEl = document.getElementById('mm-my-log-' + idx);
    var btnEl = document.getElementById('mm-my-savebtn-' + idx);
    // Compare each input against its OWN original rendered value (data-orig) —
    // apples-to-apples, no date re-normalization that can spuriously match.
    var jobs = [];
    if (uatEl && uatEl.value && uatEl.value !== uatEl.dataset.orig) jobs.push({ el: uatEl, cell: 'Page Three.Target UAT Date', value: uatEl.value, apply: function () { it.uat = uatEl.value; } });
    if (goEl && goEl.value && goEl.value !== goEl.dataset.orig) jobs.push({ el: goEl, cell: 'Page Three.Target Go Live Date', value: goEl.value, apply: function () { it.goLive = goEl.value; } });
    var logVal = logEl ? (logEl.value || '').trim() : '';
    if (logVal) jobs.push({ el: logEl, cell: 'Page Three.IT Log', value: logVal, apply: function () {} });
    if (!jobs.length) { window.appAlert('No changes to save on ' + ecnShortJs(it.id) + '. Edit Target UAT, Go-Live, or the IT-log note first.', { title: 'Nothing changed' }); return; }

    function setBtn(label, disabled) { if (btnEl) { btnEl.textContent = label; btnEl.disabled = disabled; btnEl.style.opacity = disabled ? '0.7' : '1'; btnEl.style.cursor = disabled ? 'default' : 'pointer'; } }
    setBtn('Saving…', true);   // instant feedback before any network call

    var errs = [], saved = 0, cancelled = false;
    var chain = Promise.resolve();
    jobs.forEach(function (j) {
      chain = chain.then(function () {
        if (cancelled) return;
        return withAgile(function () { return api('POST', '/api/it-enhancements/save-cell', { ecn: it.id, cellName: j.cell, value: j.value }); })
          .then(function (resp) {
            if (resp && resp.ok) { j.apply(); if (j.el) j.el.dataset.orig = j.value; saved++; }   // sync data-orig so a re-save is a no-op
            else if (resp && resp.needsAgileSignin) { cancelled = true; }   // user dismissed sign-in
            else { errs.push(j.cell.replace('Page Three.', '') + ': ' + ((resp && resp.error) || 'save failed')); }
          });
      });
    });
    chain.then(function () {
      if (errs.length) {
        var modified = errs.join(' ').toLowerCase().indexOf('has been modified') >= 0 || errs.join('').indexOf('531') >= 0;
        // Don't re-render on failure — that would wipe the user's edits.
        setBtn('Save to Agile →', false);
        window.appAlert((saved ? saved + ' field(s) saved. ' : '') + 'These did not save:\n' + errs.join('\n')
          + (modified ? '\n\nThis change was modified in Agile since the page loaded. Open All Enhancements, click Refresh, then try again.' : ''),
          { title: 'Save failed' });
      } else if (cancelled) {
        setBtn('Save to Agile →', false);
      } else {
        // Re-render so inputs/badges reflect the saved values + the ✓ chip.
        S.mySaved[it.id] = true; render();
        setTimeout(function () { S.mySaved[it.id] = false; if (S.view === 'myenh') render(); }, 4000);
      }
    });
  }

  // ---- MEETING (pre / live) -------------------------------------------
  function renderMeeting() {
    var live = S.phase === 'live' || S.phase === 'wrap';
    return (live ? sessionBar() : preBar()) + meetingMain(live) + (S.phase === 'wrap' ? wrapDrawer() : '');
  }

  // ---- v4: "Discussed this meeting" surfacing --------------------------
  // All three surfaces (banner, header badge, rail pill) derive from S.bubbles
  // at render time, so Save/Add-note, Undo, and Cancel update them live for free.

  // Green "✓ DISCUSSED · N" pill next to the ECN id in the detail header.
  function discussedBadge(sel) {
    var n = bubblesOf(sel.id).length;
    if (!n) return '';
    return '<span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.04em;padding:2px 8px;border-radius:999px;background:var(--good-bg);color:var(--good-ink)">✓ DISCUSSED · ' + n + '</span>';
  }

  // Banner at the top of the detail pane listing every capture made on this item
  // THIS session (tag + text + time), with "last at {time}". Empty when no captures.
  function discussedBanner(sel) {
    var caps = bubblesOf(sel.id);
    if (!caps.length) return '';
    var lastAt = caps[caps.length - 1].time || '';
    var rows = caps.map(function (b, i) {
      var note = b.kind === 'NOTE';
      return '<div style="display:flex;gap:9px;align-items:baseline;padding:8px 0;' + (i ? 'border-top:1px dashed var(--line-2)' : '') + '">'
        + '<span style="font-family:var(--font-mono);font-size:8.5px;padding:1px 5px;border-radius:3px;flex:none;' + (note ? 'background:var(--warn-bg);color:#6a4a12' : 'background:var(--accent-2);color:var(--accent-ink)') + '">' + (note ? 'NOTE' : '→ AGILE') + '</span>'
        + '<span style="flex:1;min-width:0;font-size:12px;color:var(--ink);line-height:1.45">' + esc(b.text) + '</span>'
        + '<span style="font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4);flex:none">' + esc(b.time || '') + '</span>'
        + '</div>';
    }).join('');
    return '<div style="margin-top:12px;border:1px solid color-mix(in oklab,var(--good) 30%,white);border-radius:8px;overflow:hidden">'
      + '<div style="display:flex;align-items:center;gap:8px;padding:8px 13px;background:var(--good-bg);border-bottom:1px solid color-mix(in oklab,var(--good) 24%,white)">'
      + '<span style="width:17px;height:17px;border-radius:50%;background:var(--good);color:#fff;font-size:10px;display:flex;align-items:center;justify-content:center;flex:none">✓</span>'
      + '<span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--good-ink)">Discussed this meeting</span>'
      + '<span style="margin-left:auto;font-family:var(--font-mono);font-size:9.5px;color:var(--good-ink)">last at ' + esc(lastAt) + '</span></div>'
      + '<div style="background:#fff;padding:2px 13px">' + rows + '</div></div>';
  }
  function avatarSm(p) {
    return 'width:24px;height:24px;border-radius:50%;background:' + (p.leader ? 'var(--accent)' : 'var(--ink-3)')
      + ';color:#fff;font-family:var(--font-mono);font-size:9px;display:flex;align-items:center;justify-content:center;border:2px solid #fff;'
      + (p.leader ? '' : 'margin-left:-7px');
  }
  function preBar() {
    var chips = S.room.map(function (p, i) {
      return '<span style="display:inline-flex;align-items:center;gap:6px;font-size:12px;padding:4px 9px 4px 4px;background:var(--surface-2);border:1px solid var(--line);border-radius:999px">'
        + '<span style="width:20px;height:20px;border-radius:50%;background:' + (p.leader ? 'var(--accent)' : 'var(--ink-3)') + ';color:#fff;font-family:var(--font-mono);font-size:8.5px;display:flex;align-items:center;justify-content:center;flex:none">' + esc(initials(p.name)) + '</span>'
        + esc(p.name)
        + (p.leader ? '<span style="font-family:var(--font-mono);font-size:8px;letter-spacing:.04em;color:var(--ink-4)">LEADER</span>'
                    : '<span onclick="MeetingMode._removePerson(' + i + ')" style="color:var(--ink-4);cursor:pointer;font-size:14px;line-height:1">×</span>')
        + '</span>';
    }).join('');
    var ros = roster(); S._roster = ros;
    var selN = ros.filter(function (o) { return S.addPeopleSel[o.ad]; }).length;
    var opts = ros.length ? ros.map(function (o, i) {
      var on = !!S.addPeopleSel[o.ad];
      return '<div id="mm-rosteropt-' + i + '" data-name="' + esc(o.name).toLowerCase() + '" onclick="MeetingMode._togglePerson(' + i + ')" style="display:flex;align-items:center;gap:8px;font-size:12.5px;color:var(--ink-2);padding:7px 8px;border-radius:5px;cursor:pointer" onmouseover="this.style.background=\'var(--surface-2)\'" onmouseout="this.style.background=\'\'">'
        + '<span id="mm-rostercheck-' + i + '" style="width:15px;height:15px;border-radius:4px;border:1.5px solid ' + (on ? 'var(--accent)' : 'var(--line-2)') + ';background:' + (on ? 'var(--accent)' : '#fff') + ';color:#fff;display:flex;align-items:center;justify-content:center;font-size:10px;flex:none">' + (on ? '✓' : '') + '</span>'
        + '<span>' + esc(o.name) + '</span></div>';
    }).join('') : '<div style="font-size:12px;color:var(--ink-4);padding:7px 8px">Everyone is already in the room.</div>';
    var dd = S.addPeopleOpen
      ? '<div style="position:absolute;top:34px;left:0;z-index:30;background:#fff;border:1px solid var(--line-2);border-radius:8px;box-shadow:var(--shadow-pop);min-width:248px;padding:6px;max-height:320px;display:flex;flex-direction:column">'
        + '<div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.1em;text-transform:uppercase;color:var(--ink-4);padding:4px 6px 2px">Add to the room</div>'
        + (ros.length ? '<input id="mm-people-search" oninput="MeetingMode._filterPeople(this.value)" placeholder="Filter people…" autocomplete="off" data-1p-ignore data-lpignore="true" style="margin:2px 4px 6px;padding:6px 8px;border:1px solid var(--line-2);border-radius:6px;font-size:12px;font-family:var(--font-sans);color:var(--ink)">' : '')
        + '<div id="mm-people-list" style="overflow:auto;flex:1;min-height:0">' + opts + '</div>'
        + (ros.length ? '<div style="display:flex;align-items:center;gap:8px;border-top:1px solid var(--line);margin-top:6px;padding-top:7px"><span id="mm-people-count" style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4)">' + selN + ' selected</span>'
          + '<button id="mm-people-add" onclick="MeetingMode._commitPeople()" style="margin-left:auto;font-family:var(--font-sans);font-size:12px;font-weight:500;border-radius:6px;padding:0 12px;height:30px;cursor:pointer;border:1px solid var(--accent);background:var(--accent);color:#fff">Add' + (selN ? ' (' + selN + ')' : '') + ' →</button></div>' : '')
        + '</div>'
      : '';
    return '<div style="border:1px solid var(--line);border-radius:8px;background:#fff;padding:16px 18px;display:flex;align-items:center;gap:16px;flex-wrap:wrap">'
      + '<div><div style="font-family:var(--font-mono);font-size:10px;letter-spacing:.1em;text-transform:uppercase;color:var(--ink-3)">In the room</div>'
      + '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:8px">' + chips
      + '<span style="position:relative;display:inline-flex"><span onclick="MeetingMode._toggleAddPeople()" style="display:inline-flex;align-items:center;gap:5px;font-size:12px;color:var(--accent-ink);padding:5px 11px;border:1px dashed var(--line-2);border-radius:999px;cursor:pointer">＋ Add people</span>' + dd + '</span>'
      + '</div></div>'
      + '<div style="margin-left:auto;display:flex;align-items:center;gap:8px">'
      + maxBtn()
      + btn('Grade now ↻', false, 'MeetingMode._gradeNow()', 38)
      + btn('Build agenda →', false, 'MeetingMode._buildAgenda()', 38)
      + '<span style="width:1px;height:24px;background:var(--line-2);margin:0 2px"></span>'
      + btn('● Start now', true, 'MeetingMode._startNow()', 38)
      + '</div></div>';
  }
  function maxBtn() {
    return '<span onclick="MeetingMode._toggleMax()" title="Maximize the workspace" style="font-size:11.5px;color:var(--ink-2);border:1px solid var(--line-2);border-radius:6px;padding:8px 11px;cursor:pointer;white-space:nowrap">'
      + (S.maximized ? '⤡ Exit full screen' : '⤢ Full screen') + '</span>';
  }
  function sessionBar() {
    var avs = S.room.map(function (p) { return '<span style="' + avatarSm(p) + '">' + esc(initials(p.name)) + '</span>'; }).join('');
    var leader = (S.room.find(function (p) { return p.leader; }) || {}).name || 'Leader';
    return '<div style="border:1px solid var(--line);border-radius:8px;background:#fff;display:flex;align-items:center;gap:13px;padding:11px 16px">'
      + '<span style="display:inline-flex;align-items:center;gap:7px;font-family:var(--font-mono);font-size:11px;color:var(--bad)"><span style="width:8px;height:8px;border-radius:50%;background:var(--bad);animation:mmPulse 1.2s infinite"></span>LIVE · <span data-mm-timer>' + Math.floor(S.elapsed / 60) + ':' + ('0' + (S.elapsed % 60)).slice(-2) + '</span></span>'
      + '<span style="font-size:12.5px;color:var(--ink-2)"><b style="color:var(--ink)">' + esc(leader) + '</b> leading</span>'
      + '<div style="display:flex;align-items:center;padding-left:2px">' + avs + '</div>'
      + '<div style="margin-left:auto;display:flex;align-items:center;gap:8px">'
      + '<span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3);margin-right:2px"><b style="color:var(--ink)">' + agileCount() + '</b> → Agile · <b style="color:var(--ink)">' + noteCount() + '</b> notes</span>'
      + maxBtn()
      + btn('Grade ↻', false, 'MeetingMode._gradeNow()', 36)
      + btn('Agenda', false, 'MeetingMode._buildAgenda()', 36)
      + '<span onclick="MeetingMode._cancelMeeting()" title="Cancel — discard this meeting" style="font-size:11.5px;color:var(--bad-ink);border:1px solid color-mix(in oklab,var(--bad) 32%,white);border-radius:6px;padding:8px 11px;cursor:pointer">Cancel</span>'
      + btn('Wrap up &amp; send →', true, 'MeetingMode._wrapUp()', 36)
      + '</div></div>';
  }
  // urgency tone for a row's left border (selected wins via the caller)
  function railTone(it) {
    var f = it.flags || [];
    if (f.indexOf('overdue') >= 0) return 'var(--bad)';
    if (f.indexOf('approaching') >= 0) return 'var(--warn)';
    if (f.indexOf('no_owner') >= 0) return 'var(--bad)';
    if (f.indexOf('live_verify') >= 0) return 'var(--accent)';
    return 'transparent';
  }
  function filterToolbar() {
    var counts = {};
    FILTERS.forEach(function (f) { counts[f.k] = S.items.filter(function (it) { return itemMatchesFilter(it, f.k); }).length; });
    var pills = FILTERS.map(function (f) {
      var on = S.railFilter === f.k, c = TONE[f.tone];
      var style = 'display:inline-flex;align-items:center;gap:6px;font-size:11.5px;padding:5px 10px;border-radius:999px;cursor:pointer;white-space:nowrap;'
        + (on ? 'background:' + (f.tone === 'ink' ? 'var(--ink)' : c) + ';color:#fff;border:1px solid transparent'
              : 'background:#fff;color:var(--ink-2);border:1px solid var(--line-2)');
      var dot = 'width:6px;height:6px;border-radius:50%;background:' + (on ? 'rgba(255,255,255,.85)' : c);
      var cnt = 'font-family:var(--font-mono);font-size:10px;' + (on ? 'color:rgba(255,255,255,.85)' : 'color:var(--ink-4)');
      return '<span onclick="MeetingMode._setFilter(\'' + f.k + '\')" style="' + style + '"><span style="' + dot + '"></span>' + esc(f.label) + ' <span style="' + cnt + '">' + counts[f.k] + '</span></span>';
    }).join('');
    S._statusOpts = distinct(function (it) { return it.status; });
    S._reqOpts = distinct(function (it) { return it.requestor; });
    S._prioOpts = distinct(function (it) { return it.priority; });
    var statusDD = filterDropdown('IT status', S.statusOpen, S._statusOpts, S.statusFilter, '_toggleStatusDD', '_toggleStatus');
    var reqDD = filterDropdown('Requestor', S.reqOpen, S._reqOpts, S.reqFilter, '_toggleReqDD', '_toggleReq');
    var prioDD = filterDropdown('Priority', S.prioOpen, S._prioOpts, S.prioFilter, '_togglePrioDD', '_togglePrio');
    return '<div style="display:flex;align-items:center;gap:8px;padding:9px 13px;border-bottom:1px solid var(--line);background:var(--surface-2)">'
      + '<div style="display:flex;align-items:center;gap:6px;overflow-x:auto;flex:1;min-width:0">' + pills + '</div>'
      + statusDD + reqDD + prioDD
      + '<span style="display:inline-flex;align-items:center;gap:6px;border:1px solid var(--line-2);border-radius:6px;padding:5px 9px;background:#fff;flex:none">'
      + '<span style="color:var(--ink-4);font-size:12px">⌕</span>'
      + '<input id="mm-rail-search" value="' + esc(S.railSearch) + '" oninput="MeetingMode._railSearch(this.value)" placeholder="Filter list…" autocomplete="off" data-1p-ignore data-lpignore="true" style="border:none;outline:none;font-family:var(--font-sans);font-size:12px;color:var(--ink);width:120px;background:transparent"></span>'
      + '</div>';
  }
  function meetingMain(live) {
    var sel = itemById(S.selectedId);
    if (live && sel) loadNoteHistory(sel.id);   // lazy-fetch prior meeting notes for the timeline
    // rail — filtered by the active pill; search is applied via direct DOM
    // (applyRailSearch) so the search box keeps focus while typing.
    var railRows = S.items.filter(function (it) { return itemMatchesFilter(it, S.railFilter); }).map(function (it) {
      var bs = bubblesOf(it.id), notIn = !isUnassigned(it.owner) && !inRoom(it.ownerLoginId), seld = it.id === S.selectedId;
      var tone = seld ? 'var(--accent)' : railTone(it);
      var ds = lc(it.id + ' ' + (it.title || '') + ' ' + (it.requestor || '') + ' ' + (it.owner || ''));
      return '<div data-search="' + esc(ds) + '" data-requestor="' + esc(lc(it.requestor)) + '" data-priority="' + esc(lc(it.priority)) + '" data-status="' + esc(lc(it.status)) + '" onclick="MeetingMode._select(\'' + esc(it.id) + '\')" style="padding:11px 13px;border-bottom:1px solid var(--line);cursor:pointer;border-left:3px solid ' + tone + ';background:' + (seld ? 'var(--accent-2)' : '#fff') + '">'
        + '<div style="display:flex;align-items:center;gap:7px;flex-wrap:wrap">' + ecnA(it.id, it.id, 'font-family:var(--font-mono);font-size:11.5px;color:var(--accent);text-decoration:none;font-weight:500')
        + (notIn ? '<span style="font-family:var(--font-mono);font-size:8px;padding:1px 4px;border-radius:3px;background:var(--bad-bg);color:var(--bad-ink)">⚠</span>' : '')
        + prioBadge(it.priority) + '</div>'
        + '<div style="font-size:12px;color:var(--ink-2);margin-top:4px;line-height:1.4;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden">' + esc(it.title) + '</div>'
        + '<div style="font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4);margin-top:5px">' + esc(it.status || '—') + ' · UAT ' + esc(fmtMD(it.uat) || '—') + '</div>'
        + (bs.length ? '<div style="margin-top:5px"><span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.03em;padding:2px 7px;border-radius:999px;background:var(--good-bg);color:var(--good-ink)">✓ Discussed · ' + bs.length + '</span></div>' : '')
        + '</div>';
    }).join('');
    var rail = '<div id="mm-rail-empty" style="display:none;padding:22px 14px;text-align:center;color:var(--ink-4);font-size:12px;line-height:1.6">No items match this filter.</div>' + railRows;
    // detail
    var ownerColor = isUnassigned(sel.owner) ? 'var(--bad)' : (!inRoom(sel.ownerLoginId) ? '#7a4b12' : 'var(--ink)');
    var badges = sel.badges.map(function (b) { return badge(b.state, b.label); }).join(' ');
    var detail = '<div style="display:flex;align-items:center;gap:9px;flex-wrap:wrap">'
      + ecnA(sel.id, sel.id, 'font-family:var(--font-mono);font-size:14px;color:var(--accent);text-decoration:none;font-weight:500') + badges + prioBadge(sel.priority)
      + (live ? discussedBadge(sel) : '') + '</div>'
      + '<div style="font-size:13px;color:var(--ink-2);margin-top:6px">' + esc(sel.title) + '</div>'
      + (live ? discussedBanner(sel) : '')
      + '<div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:14px 20px;margin-top:18px">'
      + metaCell('Requestor', esc(sel.requestor || '—'))
      + '<div><div style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)">IT Owner</div><div style="font-size:13px;margin-top:4px;color:' + ownerColor + '">' + esc(sel.owner) + '</div></div>'
      + '<div><div style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)">IT Status</div><div style="display:flex;align-items:center;gap:6px;margin-top:4px"><span style="font-size:13px">' + esc(sel.status || '—') + '</span><span style="font-family:var(--font-mono);font-size:8.5px;letter-spacing:.04em;padding:1px 6px;border-radius:4px;background:var(--surface-3);color:var(--ink-3)">AGILE · READ-ONLY</span></div></div>'
      + '</div>';
    if (live) detail += updateCard(sel);
    // (v4) Per-item captures now surface as the "Discussed this meeting" banner at the TOP of the
    // detail pane (discussedBanner, inserted above). The old chip strip that sat here — below the
    // edit card — was too easy to miss while scrolling, which is what let items get re-discussed.
    var logRail = live ? logRailHtml(sel) : '';
    var wsHeight = S.maximized ? 'calc(100vh - 132px)' : 'calc(100vh - 340px)';
    return '<div style="margin-top:14px;border:1px solid var(--line);border-radius:8px;overflow:hidden;display:flex;flex-direction:column;background:#fff;height:' + wsHeight + ';min-height:440px">'
      + filterToolbar()
      + '<div style="flex:1;display:flex;min-height:0">'
      +   '<div id="mm-rail" style="width:' + S.railW + 'px;border-right:1px solid var(--line);overflow:auto;flex:none">' + rail + '</div>'
      +   '<div onmousedown="MeetingMode._railResize(event)" style="width:6px;flex:none;cursor:col-resize;background:var(--line)" title="Drag to resize"></div>'
      +   '<div style="flex:1;overflow:auto;padding:18px 20px;min-width:0">' + detail + '</div>'
      +   logRail
      + '</div></div>';
  }
  function metaCell(k, v) {
    return '<div><div style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)">' + k + '</div><div style="font-size:13px;margin-top:4px">' + v + '</div></div>';
  }
  function updateCard(sel) {
    var assignees = S.room.concat(roster());
    var aopts = '<option value="">Assign… (optional)</option>' + assignees.map(function (p) {
      return '<option value="' + esc(p.ad) + '|' + esc(p.name) + '">' + esc(p.name) + '</option>';
    }).join('');
    return '<div style="margin-top:18px;border:1px solid var(--line);border-radius:8px;overflow:hidden">'
      + '<div style="display:flex;align-items:center;gap:8px;padding:9px 13px;background:var(--surface-2);border-bottom:1px solid var(--line)">'
      + '<span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)">Update this enhancement</span>'
      + '<span style="font-family:var(--font-mono);font-size:8.5px;letter-spacing:.04em;padding:1px 6px;border-radius:4px;background:var(--accent-2);color:var(--accent-ink)">→ captured now · saved to Agile on wrap-up</span></div>'
      + '<div style="padding:13px">'
      + '<div style="display:grid;grid-template-columns:1fr 1fr;gap:11px 14px">'
      + capInput('Target UAT', 'mm-detail-uat', 'date', isoOf(sel.uat))
      + capInput('Target Go-Live', 'mm-detail-golive', 'date', isoOf(sel.goLive))
      + '</div>'
      + '<label style="display:flex;flex-direction:column;gap:4px;margin-top:11px"><span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-4)">IT log <span style="color:var(--accent-ink)">· saved to Agile on wrap-up</span></span>'
      + '<input id="mm-detail-log" autocomplete="off" data-1p-ignore data-lpignore="true" placeholder="Decision or status note for the Agile IT log…" style="font-family:var(--font-sans);font-size:12.5px;border:1px solid var(--line-2);border-radius:6px;padding:7px 9px;color:var(--ink)"></label>'
      + '<div style="display:flex;justify-content:flex-end;margin-top:10px">' + btn('Capture →', true, 'MeetingMode._saveDetail()', 34) + '</div>'
      + '<div style="border-top:1px solid var(--line);margin:12px -13px 0;padding:13px 13px 0">'
      + '<label style="display:flex;flex-direction:column;gap:4px"><span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-4)">Notes <span style="color:#6a4a12">· not sent to Agile</span></span>'
      + '<input id="mm-detail-note" autocomplete="off" data-1p-ignore data-lpignore="true" placeholder="e.g. follow up with the requestor…" style="font-family:var(--font-sans);font-size:12.5px;border:1px solid var(--line-2);border-radius:6px;padding:7px 9px;color:var(--ink)"></label>'
      + '<div style="display:flex;align-items:center;gap:8px;margin-top:9px">'
      + '<select id="mm-detail-assignee" style="font-family:var(--font-sans);font-size:11.5px;border:1px solid var(--line-2);border-radius:5px;padding:5px 8px;color:var(--ink-2);background:#fff">' + aopts + '</select>'
      + '<span onclick="MeetingMode._addNote()" style="margin-left:auto;font-family:var(--font-sans);font-size:11.5px;font-weight:500;color:var(--ink);background:#fff;border:1px solid var(--line-2);border-radius:5px;padding:6px 12px;cursor:pointer">＋ Add note</span>'
      + '</div>' + noteHistoryHtml(sel) + '</div></div></div>';
  }
  // "Notes from earlier meetings" — meeting-only notes captured on this ECN in
  // prior sessions (read-only timeline). Fetched lazily per ECN via loadNoteHistory.
  function noteHistoryHtml(sel) {
    var hist = S.noteHistory[sel.id];
    if (!hist || !hist.length) return '';
    var thread = hist.map(function (h) {
      var when = h.createdAt ? new Date(h.createdAt) : null;
      var date = when ? when.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : '';
      var meta = (h.author ? h.author.split(',')[0] : 'Someone') + ' · ' + date + (h.assignee ? ' · → ' + h.assignee : '');
      return '<div style="display:flex;gap:9px;padding:0 0 11px 4px">'
        + '<span style="width:19px;height:19px;border-radius:50%;background:var(--ink-3);color:#fff;font-family:var(--font-mono);font-size:8px;display:flex;align-items:center;justify-content:center;flex:none;margin-left:-14px;border:2px solid #fff">' + esc(initials(h.author || '?')) + '</span>'
        + '<div style="flex:1;min-width:0"><div style="font-size:12px;color:var(--ink-2);line-height:1.5">' + esc(h.text) + '</div>'
        + '<div style="font-family:var(--font-mono);font-size:9px;color:var(--ink-4);margin-top:3px">' + esc(meta) + '</div></div></div>';
    }).join('');
    return '<div style="margin-top:13px;border-top:1px dashed var(--line-2);padding-top:11px">'
      + '<div style="display:flex;align-items:center;gap:7px;margin-bottom:9px">'
      + '<span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-4)">Notes from earlier meetings</span>'
      + '<span style="font-family:var(--font-mono);font-size:9px;color:var(--ink-4);background:var(--surface-2);border:1px solid var(--line);border-radius:999px;padding:1px 6px">' + hist.length + '</span>'
      + '<span style="font-family:var(--font-mono);font-size:8.5px;color:var(--ink-4);margin-left:auto">never sent to Agile</span></div>'
      + '<div style="position:relative;padding-left:9px;border-left:1px solid var(--line)">' + thread + '</div></div>';
  }
  function capInput(label, id, type, value) {
    return '<label style="display:flex;flex-direction:column;gap:4px"><span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-4)">' + esc(label) + '</span>'
      + '<input id="' + id + '" type="' + type + '" value="' + esc(value) + '" autocomplete="off" style="font-family:var(--font-mono);font-size:12.5px;border:1px solid var(--line-2);border-radius:6px;padding:7px 9px;color:var(--ink)"></label>';
  }
  function logRailHtml(sel) {
    var rows = S.bubbles.map(function (b) {
      var note = b.kind === 'NOTE';
      var agileMeta = b.agileWriteStatus === 'WRITTEN' ? ' ✓ saved'
        : b.agileWriteStatus === 'FAILED' ? ' · ⚠ not saved'
        : ' · saves on wrap-up';
      var meta = (b.authorName ? b.authorName.split(',')[0].split(' ')[0] : 'Leader') + ' · ' + (b.time || '')
        + (note ? ' · not in Agile' : agileMeta);
      return '<div style="display:flex;gap:9px;padding:10px 13px;border-bottom:1px solid var(--line);animation:mmIn .18s ease;' + (note ? 'background:color-mix(in oklab,var(--warn) 6%,white)' : '') + '">'
        + '<span style="width:22px;height:22px;border-radius:50%;background:' + (note ? 'var(--ink-3)' : 'var(--accent)') + ';color:#fff;font-family:var(--font-mono);font-size:9px;display:flex;align-items:center;justify-content:center;flex:none">' + esc(initials(b.authorName || 'L')) + '</span>'
        + '<div style="flex:1;min-width:0"><div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap">'
        + ecnA(b.ecn, ecnShortJs(b.ecn), 'font-family:var(--font-mono);font-size:11px;color:var(--accent);text-decoration:none')
        + '<span style="font-family:var(--font-mono);font-size:8.5px;padding:1px 5px;border-radius:3px;' + (note ? 'background:var(--warn-bg);color:#6a4a12' : 'background:var(--accent-2);color:var(--accent-ink)') + '">' + (note ? 'NOTE' : '→ AGILE') + '</span>'
        + (b.ownerNotInRoom ? '<span style="font-family:var(--font-mono);font-size:8.5px;padding:1px 5px;border-radius:3px;background:var(--bad-bg);color:var(--bad-ink)">⚠ ' + esc((b.ownerName || '').split(',')[0]) + '</span>' : '')
        + '</div><div style="font-size:12px;color:var(--ink);margin-top:3px;line-height:1.4">' + esc(b.text) + '</div>'
        + (note && b.assigneeName ? '<div style="margin-top:5px"><span style="font-size:10px;padding:2px 7px;border-radius:4px;background:var(--accent-2);color:var(--accent-ink)">Action → ' + esc(b.assigneeName) + '</span></div>' : '')
        + '<div style="display:flex;align-items:center;gap:8px;margin-top:5px"><span style="font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4)">' + esc(meta) + '</span>'
        + '<span onclick="MeetingMode._undo(\'' + esc(b.id) + '\')" title="' + (note ? 'Remove this note' : 'Undo — removes the pending Agile write') + '" style="margin-left:auto;font-family:var(--font-sans);font-size:10px;font-weight:600;color:var(--ink-2);background:#fff;border:1px solid var(--line-2);border-radius:5px;padding:3px 9px;cursor:pointer;display:inline-flex;align-items:center;gap:4px;flex:none" onmouseover="this.style.background=\'var(--bad-bg)\';this.style.color=\'var(--bad-ink)\';this.style.borderColor=\'color-mix(in oklab,var(--bad) 32%,white)\'" onmouseout="this.style.background=\'#fff\';this.style.color=\'var(--ink-2)\';this.style.borderColor=\'var(--line-2)\'">↺ Undo</span></div></div></div>';
    }).join('');
    var notify = notifySet();
    var tray = notify.length
      ? '<div style="display:flex;align-items:center;gap:8px;padding:9px 13px;background:var(--bad-bg);border-top:1px solid color-mix(in oklab,var(--bad) 22%,white)"><span style="font-size:12px;color:var(--bad-ink)">⚠</span><span style="font-size:11px;color:var(--bad-ink);line-height:1.4">Will email ' + esc(notify.join(', ')) + ' at wrap-up.</span></div>'
      : '';
    return '<div style="width:320px;border-left:1px solid var(--line);display:flex;flex-direction:column;background:var(--surface-2);flex:none">'
      + '<div style="display:flex;align-items:baseline;justify-content:space-between;padding:11px 14px;border-bottom:1px solid var(--line)"><span style="font-family:var(--font-serif);font-size:14px;font-weight:500">Meeting log</span><span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.06em;text-transform:uppercase;color:var(--good-ink)">● recording</span></div>'
      + '<div style="flex:1;overflow:auto;background:#fff">'
      + (S.bubbles.length ? rows : '<div style="padding:24px 16px;text-align:center;color:var(--ink-4);font-size:12px;line-height:1.6">No bubbles yet.<br>Capture an update on the left — it docks here and saves to Agile on wrap-up.</div>')
      + '</div>' + tray
      + '<div style="border-top:1px solid var(--line);padding:9px 13px;background:#fff;font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4);line-height:1.5">Updates are captured on the left, on <b style="color:var(--accent)">' + esc(sel.id) + '</b>.</div>'
      + '</div>';
  }

  // ---- WRAP DRAWER -----------------------------------------------------
  function wrapDrawer() {
    var actions = S.bubbles.filter(function (b) { return b.kind === 'NOTE' && b.assigneeName; });
    var agileMap = {};
    S.bubbles.filter(function (b) { return b.kind === 'AGILE'; }).forEach(function (b) {
      var k = b.ecn || ''; agileMap[k] = agileMap[k] ? agileMap[k] + ' · ' + b.text : b.text;   // full ECN key
    });
    var notify = notifySet();
    var toChips = S.room.map(function (p) { return '<span style="font-size:11.5px;padding:3px 8px;border-radius:4px;background:var(--surface-2);color:var(--ink-2);border:1px solid var(--line)">' + esc(p.name) + '</span>'; }).join('');
    var actionsBlock = actions.length ? '<div style="margin-top:13px"><div style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:#6a4a12">Action items</div>'
      + '<div style="margin-top:6px;border:1px dashed color-mix(in oklab,var(--warn) 34%,white);border-radius:6px;background:color-mix(in oklab,var(--warn) 5%,white);padding:9px 11px">'
      + actions.map(function (b) { return '<div style="font-size:12px;color:var(--ink);line-height:1.6"><b>' + esc(b.assigneeName) + '</b> — ' + esc(b.text) + ' (' + ecnA(b.ecn, ecnShortJs(b.ecn), 'font-family:var(--font-mono);font-size:10px;color:var(--accent);text-decoration:none') + ')</div>'; }).join('')
      + '</div></div>' : '';
    var agileKeys = Object.keys(agileMap);
    var agileBlock = '<div style="margin-top:14px"><div style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--accent-ink)">Will save to Agile on send</div>'
      + (agileKeys.length ? '<div style="margin-top:6px;font-size:12px;color:var(--ink-2);line-height:1.75">'
          + agileKeys.map(function (k) { return '<div>' + ecnA(k, ecnShortJs(k), 'font-family:var(--font-mono);font-size:11px;color:var(--accent);text-decoration:none') + ' — ' + esc(agileMap[k]) + '</div>'; }).join('') + '</div>'
          : '<div style="margin-top:6px;font-size:12px;color:var(--ink-4)">No Agile updates captured.</div>') + '</div>';
    var notifyBlock = notify.length ? '<div style="margin-top:16px;border:1px solid color-mix(in oklab,var(--bad) 26%,white);border-radius:8px;overflow:hidden">'
      + '<div style="display:flex;align-items:center;gap:7px;padding:9px 11px;background:var(--bad-bg);border-bottom:1px solid color-mix(in oklab,var(--bad) 20%,white)"><span style="color:var(--bad-ink);font-size:13px">⚠</span><span style="font-size:11.5px;color:var(--bad-ink);font-weight:500">Sent separately — owners not in the room</span></div>'
      + '<div style="padding:8px 11px">' + notify.map(function (n) { return '<div style="padding:5px 0"><div style="font-size:12px;color:var(--ink)"><b>' + esc(n) + '</b></div></div>'; }).join('') + '</div></div>' : '';
    var sendLabel = notify.length ? 'Send recap + notify (' + notify.length + ') →' : 'Send recap →';
    return '<div onclick="MeetingMode._cancelWrap()" style="position:fixed;inset:0;background:rgba(15,23,32,.42);z-index:40"></div>'
      + '<div style="position:fixed;top:0;right:0;bottom:0;width:460px;background:#fff;box-shadow:var(--shadow-pop);z-index:45;display:flex;flex-direction:column;animation:mmIn .2s ease">'
      + '<div style="display:flex;align-items:flex-start;justify-content:space-between;padding:15px 18px 12px;border-bottom:1px solid var(--line)">'
      + '<div><div style="font-family:var(--font-serif);font-size:16px;font-weight:500">Send meeting recap</div>'
      + '<div style="font-family:var(--font-mono);font-size:10.5px;color:var(--ink-3);margin-top:5px">' + agileCount() + ' to save to Agile · ' + noteCount() + ' notes · ' + notify.length + ' to notify</div></div>'
      + '<span onclick="MeetingMode._cancelWrap()" style="cursor:pointer;color:var(--ink-4);font-size:18px">×</span></div>'
      + '<div style="flex:1;overflow:auto;padding:14px 18px">'
      + '<div style="display:flex;align-items:center;gap:7px;flex-wrap:wrap;padding-bottom:10px;border-bottom:1px solid var(--line)"><span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-4)">To</span>' + toChips + '</div>'
      + actionsBlock + agileBlock + notifyBlock + '</div>'
      + '<div style="border-top:1px solid var(--line);padding:11px 18px;display:flex;align-items:center;justify-content:space-between">'
      + '<span style="font-family:var(--font-mono);font-size:10.5px;color:var(--ink-3)">Ends meeting · files to records</span>'
      + '<button id="mm-send-btn" onclick="MeetingMode._send()" style="font-family:var(--font-sans);font-size:12.5px;font-weight:500;border-radius:6px;padding:0 13px;height:36px;cursor:pointer;border:1px solid var(--accent);background:var(--accent);color:#fff;display:inline-flex;align-items:center;gap:5px">' + esc(sendLabel) + '</button>'
      + '</div></div>';
  }

  // ---- RECORDS ---------------------------------------------------------
  function loadRecords() {
    api('GET', '/api/meeting/records').then(function (r) {
      S.records = (r && r.records) || []; S.recordsLoaded = true;
      if (S.view === 'records') render();
    });
  }
  function renderRecordsList() {
    var admin = isAdmin();
    var rows = (S.records || []).map(function (r, i) {
      var d = new Date(r.date || Date.now());
      var chips = [r.counts.agile + ' → Agile', r.counts.actions + ' action' + (r.counts.actions === 1 ? '' : 's')
        + (r.counts.actionsOpen ? ' · ' + r.counts.actionsOpen + ' open' : ''), r.counts.notified + ' notified'];
      var checked = !!S.recSelect[r.id];
      var checkbox = admin ? '<span onclick="MeetingMode._toggleRecSel(\'' + esc(r.id) + '\')" title="Select" style="width:16px;height:16px;flex:none;border-radius:4px;border:1.5px solid ' + (checked ? 'var(--accent)' : 'var(--line-2)') + ';background:' + (checked ? 'var(--accent)' : '#fff') + ';color:#fff;display:flex;align-items:center;justify-content:center;font-size:10px;cursor:pointer">' + (checked ? '✓' : '') + '</span>' : '';
      return '<div style="display:flex;align-items:center;gap:14px;padding:14px 16px;border-bottom:1px solid var(--line);' + (i === 0 ? 'background:var(--accent-2)' : '') + '">'
        + checkbox
        + '<div onclick="MeetingMode._openRecord(\'' + esc(r.id) + '\')" style="width:46px;flex:none;text-align:center;cursor:pointer"><div style="font-family:var(--font-serif);font-size:22px;font-weight:500;line-height:1">' + d.getDate() + '</div><div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.05em;text-transform:uppercase;color:var(--ink-4);margin-top:2px">' + d.toLocaleString('en-US', { month: 'short' }) + '</div></div>'
        + '<div onclick="MeetingMode._openRecord(\'' + esc(r.id) + '\')" style="flex:1;min-width:0;cursor:pointer"><div style="display:flex;align-items:center;gap:8px"><span style="font-size:13px;color:var(--ink);font-weight:500">' + esc(r.title || 'IT Enhancements review') + '</span>' + (i === 0 ? '<span style="font-family:var(--font-mono);font-size:9px;padding:1px 6px;border-radius:4px;background:var(--accent);color:#fff">LATEST</span>' : '') + '</div>'
        + '<div style="font-family:var(--font-mono);font-size:10.5px;color:var(--ink-3);margin-top:4px">led by ' + esc(r.leaderName || '—') + ' · ' + r.attendeeCount + ' in room</div>'
        + '<div style="display:flex;gap:6px;margin-top:8px;flex-wrap:wrap">' + chips.map(function (c) { return '<span style="font-family:var(--font-mono);font-size:10px;padding:2px 7px;border-radius:4px;border:1px solid var(--line);background:var(--surface-2);color:var(--ink-2)">' + esc(c) + '</span>'; }).join('') + '</div></div>'
        + (admin ? '<span onclick="MeetingMode._deleteRecord(\'' + esc(r.id) + '\')" title="Delete record" style="flex:none;color:var(--ink-4);font-size:11px;font-family:var(--font-sans);cursor:pointer;padding:6px 9px;border-radius:5px;border:1px solid var(--line-2)" onmouseover="this.style.background=\'var(--bad-bg)\';this.style.color=\'var(--bad-ink)\'" onmouseout="this.style.background=\'\';this.style.color=\'var(--ink-4)\'">Delete</span>' : '')
        + '<span onclick="MeetingMode._openRecord(\'' + esc(r.id) + '\')" style="font-family:var(--font-sans);font-size:11.5px;flex:none;border-radius:5px;padding:6px 12px;color:var(--ink-2);border:1px solid var(--line-2);cursor:pointer">Open →</span></div>';
    }).join('');
    if (!S.records.length) rows = '<div style="padding:34px 16px;text-align:center;color:var(--ink-4);font-size:13px">' + (S.recordsLoaded ? 'No meeting records yet. Run a Meeting Mode session and wrap it up to file the first record.' : 'Loading…') + '</div>';
    // selection action bar (admin)
    var selIds = Object.keys(S.recSelect).filter(function (k) { return S.recSelect[k]; });
    var selBar = '';
    if (admin && selIds.length) {
      var days = {}; selIds.forEach(function (id) { var r = S.records.find(function (x) { return x.id === id; }); if (r) days[dayKeyMs(r.date)] = (days[dayKeyMs(r.date)] || 0) + 1; });
      var nDays = Object.keys(days).length, sameDay = selIds.length >= 2 && nDays === 1;
      var hint = selIds.length < 2 ? 'Select 2+ records' : (!sameDay ? 'Pick records from the same day to merge' : 'Merge ' + selIds.length + ' records');
      var mergeStyle = 'font-family:var(--font-sans);font-size:11.5px;font-weight:500;border-radius:5px;padding:5px 12px;' + (sameDay ? 'color:#fff;background:var(--ink);cursor:pointer' : 'color:var(--ink-4);background:var(--surface-3);cursor:not-allowed');
      selBar = '<div style="display:flex;align-items:center;gap:12px;padding:10px 16px;background:var(--accent-2);border-bottom:1px solid var(--line)">'
        + '<span style="font-family:var(--font-mono);font-size:11px;color:var(--accent-ink)"><b>' + selIds.length + '</b> selected</span>'
        + '<span style="font-size:11.5px;color:var(--ink-3)">' + esc(hint) + '</span>'
        + '<div style="margin-left:auto;display:flex;align-items:center;gap:8px"><span onclick="MeetingMode._clearRecSel()" style="font-size:11.5px;color:var(--ink-3);cursor:pointer">Clear</span>'
        + '<span ' + (sameDay ? 'onclick="MeetingMode._consolidate()"' : '') + ' style="' + mergeStyle + '">Consolidate →</span></div></div>';
    }
    return '<div style="border:1px solid var(--line);border-radius:8px;background:#fff;overflow:hidden">'
      + '<div style="display:flex;align-items:center;padding:11px 16px;border-bottom:1px solid var(--line)"><span style="font-family:var(--font-serif);font-size:15px;font-weight:500">Meeting records</span>'
      + (admin ? '<span style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4);margin-left:10px">select to delete or consolidate</span>' : '') + '</div>'
      + selBar + rows + '</div>';
  }
  function renderRecordDetail() {
    var r = S.recordData; if (!r) return '<div style="padding:24px;color:var(--ink-4)">Loading…</div>';
    var d = new Date(r.date || r.endedAt || Date.now());
    var ended = new Date(r.endedAt || Date.now());
    var meta = 'Ended ' + clock(ended) + ' · led by ' + (r.leaderName || '—') + ' · ' + (r.attendees || []).map(function (a) { return a.name; }).join(', ');
    var stats = [['To Agile', r.counts.agile], ['Notes', r.counts.notes], ['Actions', r.counts.actions], ['Notified', r.counts.notified]]
      .map(function (s) { return '<div style="background:#fff;padding:11px 16px"><div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)">' + s[0] + '</div><div style="font-family:var(--font-serif);font-size:22px;font-weight:500;margin-top:2px">' + s[1] + '</div></div>'; }).join('');
    var actions = (r.actionItems || []).map(function (a) {
      var done = a.status === 'DONE';
      return '<div style="display:flex;align-items:center;gap:10px;padding:10px 13px;border-bottom:1px solid var(--line)">'
        + '<span style="width:18px;height:18px;border-radius:50%;flex:none;' + (done ? 'background:var(--good);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center' : 'border:2px solid var(--warn)') + '">' + (done ? '✓' : '') + '</span>'
        + '<div style="flex:1"><span style="font-size:12.5px;color:var(--ink)"><b>' + esc(a.assigneeName || '—') + '</b> — ' + esc(a.text) + '</span> ' + ecnA(a.ecn, ecnShortJs(a.ecn), 'font-family:var(--font-mono);font-size:10px;color:var(--accent);text-decoration:none') + '</div>'
        + '<span onclick="MeetingMode._toggleAction(\'' + esc(a.id) + '\',\'' + (done ? 'OPEN' : 'DONE') + '\')" title="Toggle status" style="cursor:pointer;font-family:var(--font-mono);font-size:10px;flex:none;padding:3px 8px;border-radius:4px;' + (done ? 'color:var(--good-ink);background:var(--good-bg)' : 'color:#6a4a12;background:var(--warn-bg)') + '">' + (done ? 'Done' + (a.closedAt ? ' · ' + new Date(a.closedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : '') : 'Open') + '</span></div>';
    }).join('');
    var log = (r.log || []).map(function (b) {
      var note = b.kind === 'NOTE';
      return '<div style="display:flex;gap:9px;padding:9px 13px;border-bottom:1px solid var(--line);' + (note ? 'background:color-mix(in oklab,var(--warn) 5%,white)' : '') + '">'
        + '<span style="width:20px;height:20px;border-radius:50%;background:' + (note ? 'var(--ink-3)' : 'var(--accent)') + ';color:#fff;font-family:var(--font-mono);font-size:8.5px;display:flex;align-items:center;justify-content:center;flex:none">' + esc(initials(b.authorName || 'L')) + '</span>'
        + '<div style="flex:1;min-width:0"><div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap">' + ecnA(b.ecn, ecnShortJs(b.ecn), 'font-family:var(--font-mono);font-size:11px;color:var(--accent);text-decoration:none')
        + '<span style="font-family:var(--font-mono);font-size:8.5px;padding:1px 5px;border-radius:3px;' + (note ? 'background:var(--warn-bg);color:#6a4a12' : 'background:var(--accent-2);color:var(--accent-ink)') + '">' + (note ? 'NOTE' : (b.agileWriteStatus === 'FAILED' ? '⚠ NOT IN AGILE' : 'IN AGILE ✓')) + '</span></div>'
        + '<div style="font-size:12px;color:var(--ink);margin-top:2px">' + esc(b.text) + '</div></div>'
        + '<span style="font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4);flex:none">' + esc(b.time || '') + '</span></div>';
    }).join('');
    return '<div style="border:1px solid var(--line);border-radius:8px;background:#fff;overflow:hidden">'
      + '<div style="display:flex;align-items:flex-start;justify-content:space-between;padding:14px 18px 12px;border-bottom:1px solid var(--line)">'
      + '<div><div style="display:flex;align-items:center;gap:9px"><span onclick="MeetingMode._backToRecords()" style="font-family:var(--font-mono);font-size:11px;color:var(--accent);cursor:pointer">← Records</span>'
      + '<span style="font-family:var(--font-serif);font-size:16px;font-weight:500">' + d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) + ' · ' + esc(r.title || 'IT Enhancements review') + '</span>'
      + '<span style="font-family:var(--font-mono);font-size:9px;letter-spacing:.04em;padding:2px 7px;border-radius:4px;background:var(--surface-2);color:var(--ink-3);border:1px solid var(--line)">READ-ONLY</span></div>'
      + '<div style="font-family:var(--font-mono);font-size:10.5px;color:var(--ink-3);margin-top:6px">' + esc(meta) + '</div></div></div>'
      + '<div style="display:grid;grid-template-columns:repeat(4,1fr);background:var(--line);gap:1px;border-bottom:1px solid var(--line)">' + stats + '</div>'
      + '<div style="padding:16px 18px">'
      + (actions ? '<div style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)">Action items · follow-through</div><div style="margin-top:7px;border:1px solid var(--line);border-radius:7px;overflow:hidden">' + actions + '</div>' : '')
      + '<div style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3);margin-top:16px">Meeting log <span style="color:var(--ink-4)">· frozen at wrap-up</span></div>'
      + '<div style="margin-top:7px;border:1px solid var(--line);border-radius:7px;overflow:hidden">' + (log || '<div style="padding:16px;color:var(--ink-4);font-size:12px">No bubbles.</div>') + '</div>'
      + '</div></div>';
  }

  // ---- DRAWERS (grade modal / agenda drawer) --------------------------
  function renderDrawers() {
    if (!S.drawer) return '';
    var scrim = '<div onclick="MeetingMode._closeDrawer()" style="position:fixed;inset:0;background:rgba(15,23,32,.38);z-index:40"></div>';
    return scrim + (S.drawer === 'grade' ? gradeModal() : agendaDrawer());
  }

  // ---- Backlog Grade — centered modal (prototype 2a) ------------------
  function gradeModal() {
    var open = 'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);width:768px;max-width:calc(100vw - 32px);'
      + 'height:632px;max-height:calc(100vh - 32px);background:#fff;border:1px solid var(--line);border-radius:14px;'
      + 'box-shadow:var(--shadow-pop);z-index:45;display:flex;flex-direction:column;overflow:hidden;animation:mmModalIn .2s ease';
    if (S.grade === 'refreshing') return '<div style="' + open + '">' + gradeHead() + gradeRefreshing() + '</div>';
    var g = S.gradeData || computeGrade();
    return '<div style="' + open + '">' + gradeHead() + gradeFlash()
      + '<div style="display:flex;flex:1;min-height:0">' + gradeLeft(g) + gradeRight(g) + '</div></div>';
  }
  function gradeHead() {
    return '<div style="display:flex;align-items:flex-start;justify-content:space-between;padding:16px 20px 14px;border-bottom:1px solid var(--line);flex:none">'
      + '<div><div style="display:flex;align-items:center;gap:9px"><span style="font-family:var(--font-serif);font-size:17px;font-weight:500">Backlog grade</span>'
      + '<span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.06em;background:var(--accent-2);color:var(--accent-ink);padding:2px 6px;border-radius:4px">AI</span></div>'
      + '<div style="font-family:var(--font-mono);font-size:10.5px;margin-top:5px;color:' + (S.grade === 'done' ? 'var(--good-ink)' : 'var(--ink-3)') + '">'
      + (S.grade === 'done' ? '↻ Refreshed all ' + S.rows.length + ' · graded just now ✓' : '↻ Auto-refreshing before grading…') + '</div></div>'
      + '<span onclick="MeetingMode._closeDrawer()" style="cursor:pointer;color:var(--ink-4);font-size:20px;line-height:1">×</span></div>';
  }
  function gradeRefreshing() {
    return '<div style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;padding:24px">'
      + '<span style="width:11px;height:11px;border-radius:50%;background:var(--accent);animation:mmPulse 1.1s infinite"></span>'
      + '<div style="font-family:var(--font-mono);font-size:12px;color:var(--ink-2)">Refreshing all ' + S.rows.length + ' enhancements…</div>'
      + '<div style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3)"><b id="mm-grade-read" style="color:var(--ink)">' + S.refreshed + '</b> / ' + S.rows.length + ' read from agprod</div>'
      + '<div style="width:240px;height:5px;background:var(--surface-3);border-radius:3px;overflow:hidden"><div id="mm-grade-bar" style="width:' + S.progress + '%;height:100%;background:var(--accent);transition:width .1s linear"></div></div></div>';
  }
  function gradeFlash() {
    var f = S.gradeUi.flash; if (!f) return '';
    return '<div class="mm-flash" style="display:flex;align-items:center;gap:10px;padding:9px 20px;border-bottom:1px solid var(--line);background:var(--good-bg);flex:none">'
      + '<span style="color:var(--good-ink);font-size:13px">✓</span>'
      + '<span style="flex:1;font-size:11.5px;color:var(--good-ink);line-height:1.4">' + esc(f.text) + ' <span style="font-family:var(--font-mono)">✉ ' + esc(f.to) + '</span> notified.</span>'
      + '<span onclick="MeetingMode._gradeTab(\'history\')" style="cursor:pointer;font-family:var(--font-mono);font-size:10.5px;color:var(--accent);white-space:nowrap">View history →</span></div>';
  }
  function gradePolicyNote() {
    var pols = (S.gradeUi.policies || []).filter(function (p) { return (p.count || 0) > 0; });
    if (!pols.length) return '';
    var n = 0; pols.forEach(function (p) { n += (p.count || 0); });
    return '<div style="font-size:11.5px;color:var(--ink-3);line-height:1.5;margin-top:8px;padding-top:8px;border-top:1px dashed var(--line)">'
      + '<b style="color:var(--ink-2)">' + n + ' ECN' + (n === 1 ? '' : 's') + ' not considered for grading</b> — by ' + pols.length + ' standing policy' + (pols.length === 1 ? '' : ' policies') + ' (see History).</div>';
  }
  function gradeLeft(g) {
    var ui = S.gradeUi, rule = gradeRule(), total = g.total || 65;
    var tr = window.GradeLogic.trend(g.score, ui.lastScore, ui.lastLetter);
    var models = ui.models && ui.models.length ? ui.models : [{ slug: '', label: 'Production default' }];
    var chips = models.map(function (m, i) {
      var active = (m.slug || '') === (ui.model || '');
      return '<span onclick="MeetingMode._gradeModel(' + i + ')" style="cursor:pointer;font-family:var(--font-mono);font-size:10px;padding:4px 9px;border-radius:999px;'
        + 'border:1px solid ' + (active ? 'var(--accent)' : 'var(--line-2)') + ';background:' + (active ? 'var(--accent)' : '#fff') + ';color:' + (active ? '#fff' : 'var(--ink-3)') + '">' + esc(m.label) + '</span>';
    }).join('');
    var explainer = '';
    if (ui.explainer) {
      explainer = '<div style="font-size:11.5px;color:var(--ink-2);line-height:1.6;margin-top:10px">'
        + 'Start at <b>100</b>, subtract the <b style="color:var(--bad-ink)">at-risk</b> share of the backlog, then <b>−' + rule.missingPenalty + '</b> for the soft missing-dates signal.'
        + '<div style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3);background:var(--surface-2);border:1px solid var(--line);border-radius:6px;padding:9px 11px;margin-top:9px;line-height:1.5">'
        + '100 − round(' + g.atRiskN + '/' + total + ' × 100) − ' + rule.missingPenalty + '<br>= <b style="color:' + g.scoreColor + '">' + g.score + '</b> → ' + esc(g.letter) + '</div>'
        + '<div style="margin-top:8px;color:var(--ink-3)">Disagree with how a signal is weighted? Dispute the rule in Breakdown — with proof, I\'ll change the logic.</div></div>';
    }
    return '<div style="width:270px;flex:none;border-right:1px solid var(--line);padding:22px 22px 18px;display:flex;flex-direction:column">'
      + '<div style="display:flex;align-items:flex-end;gap:14px"><div style="font-family:var(--font-serif);font-size:80px;font-weight:500;line-height:.82;color:' + g.scoreColor + '">' + esc(g.letter) + '</div>'
      + '<div style="padding-bottom:8px"><div style="font-family:var(--font-mono);font-size:14px;color:var(--ink-2)"><b style="color:var(--ink)">' + g.score + '</b> / 100</div>'
      + '<div style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3);margin-top:4px">Meeting readiness</div></div></div>'
      + '<div style="display:inline-flex;align-self:flex-start;align-items:center;gap:6px;margin-top:14px;padding:4px 10px;border-radius:999px;font-family:var(--font-mono);font-size:11px;background:' + tr.bg + ';color:' + tr.color + '"><span>' + tr.arrow + '</span><span>' + esc(tr.text) + '</span></div>'
      + '<div style="font-size:12.5px;color:var(--ink-2);line-height:1.55;margin-top:16px">' + esc(g.summary) + '</div>'
      + gradePolicyNote()
      + '<div onclick="MeetingMode._gradeExplainer()" style="cursor:pointer;display:flex;align-items:center;justify-content:space-between;margin-top:18px;padding-top:14px;border-top:1px solid var(--line)">'
      + '<span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)">How is this calculated?</span>'
      + '<span style="color:var(--ink-4);font-size:10px">' + (ui.explainer ? '▾' : '▸') + '</span></div>' + explainer
      + '<div style="margin-top:auto;padding-top:16px"><div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-4);margin-bottom:7px">Grading model</div>'
      + '<div style="display:flex;flex-wrap:wrap;gap:5px">' + chips + '</div></div></div>';
  }
  function gradeRight(g) {
    var ui = S.gradeUi;
    var histN = (ui.history.logic.length + ui.history.accepts.length);
    var tabs = [['breakdown', 'Breakdown'], ['atrisk', 'At-risk · ' + g.atRiskN], ['chat', 'Ask AI'], ['history', 'History · ' + histN]];
    var strip = tabs.map(function (t) {
      var on = ui.tab === t[0];
      return '<span onclick="MeetingMode._gradeTab(\'' + t[0] + '\')" style="cursor:pointer;font-family:var(--font-sans);font-size:12px;font-weight:500;padding:8px 12px;border-bottom:2px solid '
        + (on ? 'var(--accent)' : 'transparent') + ';color:' + (on ? 'var(--ink)' : 'var(--ink-3)') + '">' + esc(t[1]) + '</span>';
    }).join('');
    var body = ui.tab === 'breakdown' ? gradeBreakdown(g)
      : ui.tab === 'atrisk' ? gradeAtRiskTab(g)
      : ui.tab === 'chat' ? gradeChatTab(g)
      : gradeHistoryTab();
    var chatInput = ui.tab === 'chat'
      ? '<div style="border-top:1px solid var(--line);padding:10px 14px;display:flex;gap:8px;align-items:center;flex:none">'
        + '<input id="mm-grade-chat" onkeydown="if(event.key===\'Enter\'&&!event.shiftKey){event.preventDefault();MeetingMode._gradeChatSend()}" placeholder="Argue an item isn\'t at-risk — bring proof…" style="flex:1;height:34px;border:1px solid var(--line-2);border-radius:7px;padding:0 11px;font-family:var(--font-sans);font-size:12.5px;color:var(--ink);outline:none">'
        + '<span onclick="MeetingMode._gradeChatSend()" style="cursor:pointer;font-family:var(--font-sans);font-size:12px;font-weight:500;color:#fff;background:var(--accent);border-radius:7px;padding:9px 14px">Send</span></div>'
      : '';
    return '<div style="flex:1;min-width:0;display:flex;flex-direction:column">'
      + '<div style="display:flex;gap:2px;padding:10px 14px 0;border-bottom:1px solid var(--line);flex:none">' + strip + '</div>'
      + '<div id="mm-grade-scroll" class="sb" style="flex:1;overflow:auto;padding:14px 18px">' + body + '</div>' + chatInput + '</div>';
  }
  // Breakdown bars + per-rule dispute affordance for the three penalty signals.
  function gradeBreakdown(g) {
    var ui = S.gradeUi;
    var ruleMap = {
      overdue: { applied: gradeRule().graceDays > 0, summary: 'Items overdue < 7 days no longer count' },
      noowner: { applied: !gradeRule().includeNoOwner, summary: 'Unowned high-priority no longer penalized' },
      missing: { applied: gradeRule().missingPenalty === 0, summary: 'Missing target dates set to 0 penalty' }
    };
    return g.drivers.map(function (d) {
      var open = !!S.gradeOpen[d.key], hasItems = d.items && d.items.length;
      var header = '<div ' + (hasItems ? 'onclick="MeetingMode._gradeDriver(\'' + d.key + '\')" ' : '') + 'style="display:flex;align-items:center;gap:10px;padding:8px 0;' + (hasItems ? 'cursor:pointer' : '') + '">'
        + '<span style="width:7px;height:7px;border-radius:50%;flex:none;background:' + d.color + '"></span>'
        + '<span style="font-size:12.5px;color:var(--ink-2);width:158px">' + esc(d.label) + '</span>'
        + '<div style="flex:1;height:5px;background:var(--surface-3);border-radius:3px;overflow:hidden"><div style="width:' + d.pct + '%;height:100%;background:' + d.color + '"></div></div>'
        + '<span style="font-family:var(--font-mono);font-size:12px;color:var(--ink);font-weight:500;width:24px;text-align:right">' + d.count + '</span>'
        + '<span style="color:var(--ink-4);font-size:9px;width:10px;text-align:center">' + (hasItems ? (open ? '▾' : '▸') : '') + '</span></div>';
      var list = '';
      if (open && hasItems) {
        list = '<div style="margin:0 0 6px 17px;border-left:1px solid var(--line);padding-left:12px">'
          + d.items.slice(0, 8).map(function (x) {
              return '<div style="display:flex;align-items:center;gap:8px;padding:3px 0">'
                + ecnA(x.id, ecnShortJs(x.id), 'font-family:var(--font-mono);font-size:11px;color:var(--accent);text-decoration:none')
                + '<span style="font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4)">' + esc(x.meta || x.status || '') + '</span></div>';
            }).join('')
          + (d.items.length > 8 ? '<div style="font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4);padding-top:3px">+ ' + (d.items.length - 8) + ' more</div>' : '')
          + '</div>';
      }
      var ruleRow = '';
      var rm = ruleMap[d.key];
      if (rm) {
        if (rm.applied) ruleRow = '<div style="margin:0 0 8px 17px;font-family:var(--font-mono);font-size:10px;color:var(--good-ink)">✓ logic adjusted for this signal</div>';
        else if (ui.disputeFor === 'rule:' + d.key) ruleRow = gradeDisputeForm('rule:' + d.key, rm.summary, '');
        else ruleRow = '<div style="margin:0 0 8px 17px"><span onclick="MeetingMode._gradeOpenDispute(\'rule:' + d.key + '\')" style="cursor:pointer;font-family:var(--font-mono);font-size:10px;color:var(--accent)">⚖ Dispute this rule →</span></div>';
      }
      return header + list + ruleRow;
    }).join('');
  }
  // Shared dispute form (item + rule). forKey = ecn | 'rule:<key>'. The textarea
  // stores into S.gradeUi.disputeText via oninput WITHOUT re-rendering (so focus +
  // content survive); it is seeded from disputeText on every render.
  function gradeDisputeForm(forKey, ruleSummary, itemDesc) {
    var ui = S.gradeUi, isRule = forKey.indexOf('rule:') === 0;
    var intro = isRule
      ? '<div style="font-size:11.5px;color:var(--accent-ink);font-weight:500;margin-bottom:2px">Dispute rule — “' + esc(ruleSummary) + '”?</div>'
        + '<div style="font-size:11.5px;color:var(--ink-2);line-height:1.5;margin-bottom:8px">Argue why this signal shouldn\'t lower the grade. I\'ll only change the logic on <b>verifiable proof</b> — a policy/SOP, a CCB decision, a date or a linked record.</div>'
      : '<div style="font-size:11.5px;color:var(--ink-2);line-height:1.5;margin-bottom:8px">Why isn\'t <b>' + esc(ecnShortJs(forKey)) + '</b> at-risk? I\'ll <b>cross-check your reason against this ECN\'s Agile record first</b> — if the record already backs you up, no upload is needed. Otherwise bring proof (a date, owner, CCB/policy note) or a screenshot.</div>'
        + '<div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-4);margin-bottom:3px">On record in Agile · Description</div>'
        + '<div style="font-size:11.5px;color:var(--ink-2);line-height:1.45;background:var(--surface-2);border:1px solid var(--line);border-radius:6px;padding:8px 10px;margin-bottom:9px">' + esc(itemDesc || '—') + '</div>';
    var ph = isRule
      ? 'e.g. Per CCB policy 6/2026, items under 7 days overdue are within normal review cycle — see SOP-PLM-114.'
      : 'e.g. The ECN description says Agile IT help not required.';
    var fileChip = ui.disputeFile
      ? '<span style="display:inline-flex;align-items:center;gap:6px;font-family:var(--font-mono);font-size:10.5px;color:var(--good-ink);background:var(--good-bg);border-radius:6px;padding:4px 8px">✓ ' + esc(ui.disputeFile.name) + ' <span onclick="MeetingMode._gradeClearFile()" style="cursor:pointer;color:var(--ink-4)">×</span></span>'
      : '';
    var reply = '';
    if (ui.aiReply) {
      if (ui.aiReply.status === 'need_file') reply = '<div style="display:flex;gap:7px;font-size:11.5px;line-height:1.45;margin-top:8px;padding:8px 10px;border-radius:6px;background:var(--accent-2);color:var(--accent-ink)"><span>📎</span><span>' + esc(ui.aiReply.text) + '</span></div>';
      else if (ui.aiReply.status === 'reject') reply = '<div style="display:flex;gap:7px;font-size:11.5px;line-height:1.45;margin-top:8px;padding:8px 10px;border-radius:6px;background:var(--bad-bg);color:var(--bad-ink)"><span>✕</span><span>' + esc(ui.aiReply.text) + '</span></div>';
    }
    var clip = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"></path></svg>';
    var submitLabel = ui.busy ? 'Reviewing…' : 'Submit for review';
    return '<div style="margin:2px 0 12px ' + (isRule ? '17px' : '0') + ';border:1px solid var(--accent);border-radius:9px;padding:12px;background:var(--accent-2)">'
      + intro
      + '<textarea id="mm-dispute-ta" oninput="MeetingMode._gradeDraft(this.value)" placeholder="' + esc(ph) + '" style="width:100%;box-sizing:border-box;height:62px;resize:none;border:1px solid var(--line-2);border-radius:7px;padding:8px 10px;font-family:var(--font-sans);font-size:12px;color:var(--ink);outline:none">' + esc(ui.disputeText || '') + '</textarea>'
      + '<div style="display:flex;align-items:center;gap:8px;margin-top:8px;flex-wrap:wrap">'
      + '<label style="cursor:pointer;font-family:var(--font-sans);font-size:11px;font-weight:500;color:var(--accent-ink);background:#fff;border:1px solid color-mix(in oklab,var(--accent) 30%,white);border-radius:6px;padding:5px 10px;display:inline-flex;align-items:center;gap:5px">' + clip + 'Attach screenshot<input type="file" accept="image/*" onchange="MeetingMode._gradeFile(this)" style="display:none"></label>'
      + fileChip + '</div>' + reply
      + '<div style="display:flex;gap:7px;margin-top:9px">'
      + '<span onclick="MeetingMode._gradeSubmitDispute()" style="cursor:pointer;font-family:var(--font-sans);font-size:11.5px;font-weight:500;color:#fff;background:var(--accent);border-radius:6px;padding:6px 12px;' + (ui.busy ? 'opacity:.7;pointer-events:none' : '') + '">' + submitLabel + '</span>'
      + '<span onclick="MeetingMode._gradeCancelDispute()" style="cursor:pointer;font-family:var(--font-sans);font-size:11.5px;color:var(--ink-3);border:1px solid var(--line-2);border-radius:6px;padding:6px 11px">Cancel</span></div></div>';
  }
  function gradeAtRiskTab(g) {
    var ui = S.gradeUi;
    var atRisk = (g.drivers.find(function (d) { return d.key === 'overdue'; }).items || [])
      .concat(g.drivers.find(function (d) { return d.key === 'noowner'; }).items || []);
    var intro = '<div style="font-size:11.5px;color:var(--ink-3);line-height:1.5;margin-bottom:10px">Items dragging the grade down. <b>Dispute</b> opens a review — the AI re-grades only if your evidence holds up, and logs the decision.</div>';
    var cards = atRisk.length ? atRisk.map(function (it) {
      var form = ui.disputeFor === it.id ? gradeDisputeForm(it.id, '', it.desc) : '';
      return '<div style="border:1px solid var(--line);border-radius:8px;padding:11px 12px;margin-bottom:8px">'
        + '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">'
        + ecnA(it.id, ecnShortJs(it.id), 'font-family:var(--font-mono);font-size:11.5px;color:var(--accent);font-weight:500;text-decoration:none')
        + '<span style="font-family:var(--font-mono);font-size:8.5px;letter-spacing:.04em;padding:1px 5px;border-radius:3px;background:var(--bad-bg);color:var(--bad-ink)">' + (it.kind === 'overdue' ? 'OVERDUE' : 'NO OWNER') + '</span></div>'
        + '<div style="font-size:12.5px;color:var(--ink);margin-top:5px;line-height:1.4">' + esc(it.title || '') + '</div>'
        + '<div style="display:flex;align-items:center;justify-content:space-between;margin-top:8px">'
        + '<span style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4)">' + esc(it.meta || '') + '</span>'
        + (ui.disputeFor === it.id ? '' : '<span onclick="MeetingMode._gradeOpenDispute(\'' + it.id + '\')" style="cursor:pointer;font-family:var(--font-sans);font-size:11px;font-weight:500;color:var(--bad-ink);background:var(--bad-bg);border:1px solid color-mix(in oklab,var(--bad) 24%,white);border-radius:5px;padding:4px 10px">Dispute</span>')
        + '</div>' + form + '</div>';
    }).join('') : '<div style="font-size:12px;color:var(--ink-4);padding:20px 0;text-align:center">Nothing at-risk — every active item is on track.</div>';
    var exclKeys = Object.keys(ui.excluded).filter(function (k) { return ui.excluded[k]; });
    var excluded = exclKeys.length
      ? '<div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-4);margin:14px 0 7px">Excluded by you</div>'
        + exclKeys.map(function (id) {
            return '<div style="display:flex;align-items:center;justify-content:space-between;gap:8px;padding:7px 11px;border:1px dashed var(--line-2);border-radius:7px;margin-bottom:6px;opacity:.85">'
              + '<span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3);text-decoration:line-through">' + esc(ecnShortJs(id)) + '</span>'
              + '<span onclick="MeetingMode._gradeRestore(\'' + id + '\')" style="cursor:pointer;font-family:var(--font-mono);font-size:10px;color:var(--accent);text-decoration:underline">restore</span></div>';
          }).join('')
      : '';
    return intro + cards + excluded;
  }
  function gradeChatTab(g) {
    var ui = S.gradeUi;
    var bubbles = (ui.chat || []).map(function (m) {
      var user = m.role === 'user';
      return '<div style="display:flex;justify-content:' + (user ? 'flex-end' : 'flex-start') + '">'
        + '<div style="max-width:84%;background:' + (user ? 'var(--accent)' : 'var(--surface-2)') + ';color:' + (user ? '#fff' : 'var(--ink)') + ';font-size:12.5px;line-height:1.5;padding:9px 12px;border-radius:11px">' + esc(m.text) + '</div></div>';
    }).join('');
    return '<div style="display:flex;flex-direction:column;gap:10px">' + bubbles + '</div>';
  }
  function gradeHistoryTab() {
    var ui = S.gradeUi, h = ui.history;
    if (!ui.historyLoaded && !(h.logic.length + h.accepts.length + h.outbox.length)) {
      return '<div style="text-align:center;padding:34px 20px;color:var(--ink-4)"><div style="font-size:22px;margin-bottom:8px">⚖</div>'
        + '<div style="font-size:12px;line-height:1.5;max-width:260px;margin:0 auto">No changes yet. Disputes that clear the evidence bar — and any runtime logic changes — get logged here, each with the AI\'s reason and the email that went out.</div></div>';
    }
    var out = '';
    if (h.logic.length) {
      out += '<div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3);margin:0 0 8px">⚙ Logic changes · ' + h.logic.length + '</div>';
      out += h.logic.map(function (r) {
        return '<div style="border:1px solid color-mix(in oklab,var(--warn) 32%,white);border-radius:8px;padding:11px 12px;margin-bottom:9px;background:color-mix(in oklab,var(--warn) 5%,white)">'
          + '<div style="display:flex;align-items:center;justify-content:space-between;gap:8px"><span style="font-size:12.5px;color:var(--ink);font-weight:500;line-height:1.35">' + esc(r.summary) + '</span>'
          + '<span style="font-family:var(--font-mono);font-size:10px;font-weight:600;background:var(--good-bg);color:var(--good-ink);padding:2px 7px;border-radius:999px;white-space:nowrap">' + esc(r.gradeBefore) + ' → ' + esc(r.gradeAfter) + '</span></div>'
          + '<div style="font-family:var(--font-mono);font-size:10px;color:var(--ink-3);margin-top:5px">' + esc(r.detail || '') + ' · score ' + r.scoreBefore + ' → ' + r.scoreAfter + '</div>'
          + '<div style="font-size:11.5px;color:var(--ink-2);line-height:1.45;margin-top:7px"><b style="color:var(--ink-3)">Rationale:</b> “' + esc(r.userReason) + '”</div>'
          + '<div style="font-size:11.5px;color:var(--ink-2);line-height:1.45;margin-top:3px"><b style="color:var(--accent-ink)">AI decision:</b> ' + esc(r.aiReason) + '</div>'
          + '<div style="display:flex;align-items:center;justify-content:space-between;margin-top:8px;padding-top:8px;border-top:1px solid var(--line)">'
          + '<span style="font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4)">✉ ' + esc(r.emailedTo || '') + ' · ' + esc(r.actorName || r.actorAd || '') + ' · ' + esc(fmtTs(r.createdAt)) + '</span>'
          + (r.id ? '<span onclick="MeetingMode._gradeRevertRule(\'' + r.id + '\',\'' + (r.ruleKey || '') + '\')" style="cursor:pointer;font-family:var(--font-mono);font-size:10px;color:var(--accent);text-decoration:underline">revert</span>' : '') + '</div></div>';
      }).join('');
    }
    if (h.accepts.length) {
      out += '<div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3);margin:14px 0 8px">✓ Accepted decisions · ' + h.accepts.length + '</div>';
      out += h.accepts.map(function (r) {
        var inc = r.action === 'include';
        var tag = '<span style="font-family:var(--font-mono);font-size:8px;letter-spacing:.04em;padding:1px 5px;border-radius:3px;margin-left:6px;background:' + (inc ? 'var(--accent-2)' : 'var(--surface-3)') + ';color:' + (inc ? 'var(--accent-ink)' : 'var(--ink-3)') + '">' + (inc ? 'INCLUDED' : 'EXCLUDED') + '</span>';
        return '<div style="border:1px solid var(--line);border-radius:8px;padding:11px 12px;margin-bottom:9px">'
          + '<div style="display:flex;align-items:center;justify-content:space-between;gap:8px"><span><span style="font-family:var(--font-mono);font-size:11.5px;color:var(--accent);font-weight:500">' + esc(ecnShortJs(r.targetEcn)) + '</span>' + tag + '</span>'
          + '<span style="font-family:var(--font-mono);font-size:10px;font-weight:600;background:var(--good-bg);color:var(--good-ink);padding:2px 7px;border-radius:999px;white-space:nowrap">' + esc(r.gradeBefore) + ' → ' + esc(r.gradeAfter) + '</span></div>'
          + '<div style="font-size:12px;color:var(--ink-2);margin-top:4px;line-height:1.35">' + esc(r.title || '') + '</div>'
          + '<div style="font-size:11.5px;color:var(--ink-2);line-height:1.45;margin-top:7px"><b style="color:var(--ink-3)">Rationale:</b> “' + esc(r.userReason) + '”</div>'
          + '<div style="font-size:11.5px;color:var(--ink-2);line-height:1.45;margin-top:3px"><b style="color:var(--accent-ink)">AI decision:</b> ' + esc(r.aiReason) + '</div>'
          + '<div style="font-family:var(--font-mono);font-size:9.5px;color:var(--ink-4);margin-top:8px;padding-top:8px;border-top:1px solid var(--line)">✉ ' + esc(r.emailedTo || '') + ' · ' + esc(r.actorName || r.actorAd || '') + ' · ' + esc(fmtTs(r.createdAt)) + '</div></div>';
      }).join('');
    }
    if (h.outbox.length) {
      out += '<div style="font-family:var(--font-mono);font-size:9px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3);margin:14px 0 8px">✉ Outbox · ' + h.outbox.length + '</div>';
      out += h.outbox.map(function (e) {
        var lines = (e.body || []).map(function (t) { return '<div style="font-size:11px;color:var(--ink-3);line-height:1.5">' + esc(t) + '</div>'; }).join('');
        return '<div style="border:1px solid var(--line);border-radius:8px;padding:11px 12px;margin-bottom:9px;background:var(--surface-2)">'
          + '<div style="display:flex;align-items:center;gap:7px;flex-wrap:wrap"><span style="font-family:var(--font-mono);font-size:8.5px;letter-spacing:.04em;padding:1px 6px;border-radius:3px;background:' + (e.it ? 'var(--warn-bg)' : 'var(--accent-2)') + ';color:' + (e.it ? '#6a4a12' : 'var(--accent-ink)') + '">' + (e.it ? 'IT TEAM' : 'ADMIN') + '</span>'
          + '<span style="font-family:var(--font-mono);font-size:10.5px;color:var(--ink-2)">To: ' + esc(e.to) + '</span></div>'
          + '<div style="font-size:12px;color:var(--ink);font-weight:500;margin-top:6px;line-height:1.4">' + esc(e.subject) + '</div>'
          + '<div style="margin-top:6px;border-top:1px solid var(--line);padding-top:6px">' + lines + '</div>'
          + '<div style="font-family:var(--font-mono);font-size:9px;color:var(--ink-4);margin-top:7px">Sent ' + esc(fmtTs(e.sentAt)) + '</div></div>';
      }).join('');
    }
    return out || '<div style="font-size:12px;color:var(--ink-4);padding:20px 0;text-align:center">No changes yet.</div>';
  }
  function fmtTs(ms) {
    if (!ms) return '';
    try { return new Date(ms).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }); }
    catch (e) { return ''; }
  }
  // Map the live item set into the GradeLogic `counts` shape. Closed items are
  // excluded; accepted item-disputes (S.gradeUi.excluded) are moved from the
  // at-risk buckets into On track so the grade rises live. Each at-risk row
  // carries the fields the modal + evaluator need (desc = the Agile text we
  // cross-check a dispute against).
  function buildCounts() {
    var rows = S.items || [], total = 0;
    var excl = (S.gradeUi && S.gradeUi.excluded) || {};
    var polExcl = (S.gradeUi && S.gradeUi.policyExcluded) || {};
    var overdue = [], unownedHi = [], onTrackBase = [], soon = [], missing = [];
    rows.forEach(function (it) {
      if (doneStage(it.status)) return;                 // closed don't count
      if (polExcl[it.id]) return;                       // dropped by a standing policy — not considered for grading at all
      total++;
      var dg = daysUntil(it.goLive), du = daysUntil(it.uat);
      var od = anyOverdue(it);
      var noOwnerHi = isUnassigned(it.owner) && it.hiPri;
      var noUat = !it.uat, noGo = !it.goLive;
      if (dg !== null && dg >= 0 && dg <= 7) soon.push({ id: it.id, meta: 'go-live ' + (fmtMD(it.goLive) || '') });
      if (noUat || noGo) missing.push({ id: it.id, meta: noUat && noGo ? 'no UAT or Go-Live date' : noUat ? 'no UAT date' : 'no Go-Live date' });
      var excluded = !!excl[it.id];
      var daysOverdue = 0;
      if (overdueUat(it) && du !== null) daysOverdue = Math.max(daysOverdue, -du);
      if (overdueGoLive(it) && dg !== null) daysOverdue = Math.max(daysOverdue, -dg);
      var meta = overdueUat(it) ? ('UAT · ' + (-du) + 'd overdue')
        : overdueGoLive(it) ? ('Go-Live · ' + (-dg) + 'd overdue') : (it.status || '');
      var desc = it.title || '';   // problemStatement/proposal folded into it.title by buildItems
      if (!excluded && od) overdue.push({ id: it.id, title: it.title, meta: meta, status: it.status, daysOverdue: daysOverdue, desc: desc, kind: 'overdue' });
      else if (!excluded && noOwnerHi) unownedHi.push({ id: it.id, title: it.title, meta: 'High priority · no IT owner', status: it.status, desc: desc, kind: 'noowner' });
      else onTrackBase.push({ id: it.id, meta: (it.uat ? 'UAT ' + (fmtMD(it.uat) || '') : (it.goLive ? 'Go-Live ' + (fmtMD(it.goLive) || '') : '')) });
    });
    return { total: total || 1, overdue: overdue, unownedHi: unownedHi, soon: soon, missing: missing, missingCount: missing.length, onTrackBase: onTrackBase };
  }
  function gradeRule() {
    return (S.gradeUi && S.gradeUi.rule) || { graceDays: 0, includeNoOwner: true, missingPenalty: 6 };
  }
  function computeGrade() {
    var g = window.GradeLogic.computeGrade(buildCounts(), gradeRule());
    g.line = g.summary;                 // back-compat with markup that reads g.line
    return g;
  }
  // Tag a missing-dates item with which date(s) it lacks, for the drill-down.
  function decorateMissing(it, noUat, noGo) {
    var which = noUat && noGo ? 'no UAT or Go-Live date' : noUat ? 'no UAT date' : 'no Go-Live date';
    return { id: it.id, status: it.status, miss: which };
  }
  function agendaDrawer() {
    var axis = S.agendaAxis;
    var ranked = S.items.slice().sort(function (a, b) { return b.score - a.score; });
    var stageCounts = {};
    ranked.forEach(function (e) { stageCounts[e.stage] = (stageCounts[e.stage] || 0) + 1; });
    var bnStage = Object.keys(stageCounts).sort(function (a, b) { return stageCounts[b] - stageCounts[a]; })[0] || '';
    var bnCount = stageCounts[bnStage] || 0;
    var enriched = ranked.map(function (e, i) {
      var st = S.agendaStatus[e.id], n = bubblesOf(e.id).length, done = n > 0 || st === 'closed', deferred = st === 'deferred' && !done;
      var ownerOut = !isUnassigned(e.owner) && !inRoom(e.ownerLoginId);
      return { e: e, rank: i + 1, done: done, deferred: deferred, n: n, ownerOut: ownerOut };
    });
    var active = enriched.filter(function (x) { return !x.done && !x.deferred; });
    var doneL = enriched.filter(function (x) { return x.done; });
    var defL = enriched.filter(function (x) { return x.deferred; });
    var rankColor = function (sc) { return sc >= 120 ? 'var(--ink)' : sc >= 60 ? 'var(--ink-3)' : 'var(--ink-4)'; };
    var segOn = 'padding:4px 11px;font-size:11.5px;border-radius:3px;cursor:pointer;background:var(--surface-2);color:var(--ink);font-weight:500';
    var segOff = 'padding:4px 11px;font-size:11.5px;border-radius:3px;cursor:pointer;color:var(--ink-3)';
    var activeHtml = active.map(function (x) {
      var e = x.e, badges = e.badges.map(function (b) { return badge(b.state, b.label); }).join(' ');
      var frame = axis === 'owner'
        ? '<div style="font-size:12.5px;color:var(--ink-2);margin-top:6px;line-height:1.5"><b style="color:var(--ink)">Blocker</b> — ' + esc(e.blocker) + '</div><div style="font-size:11.5px;color:var(--ink-3);margin-top:3px"><span style="color:var(--accent-ink);font-weight:500">Next step</span> — ' + esc(e.next) + '</div>'
        : '<div style="font-size:12.5px;color:var(--ink-2);margin-top:6px;line-height:1.5"><b style="color:var(--ink)">Discuss</b> — ' + esc(e.discuss) + '</div><div style="font-size:11.5px;color:var(--ink-3);margin-top:3px"><span style="color:var(--good-ink);font-weight:500">Close when</span> — ' + esc(e.closeWhen) + '</div>';
      return '<div style="display:flex;gap:12px;padding:13px 16px;border-bottom:1px solid var(--line)">'
        + '<div style="width:22px;height:22px;border-radius:50%;background:' + rankColor(e.score) + ';color:#fff;font-family:var(--font-mono);font-size:11px;display:flex;align-items:center;justify-content:center;flex:none">' + x.rank + '</div>'
        + '<div style="flex:1;min-width:0"><div style="display:flex;align-items:center;gap:7px;flex-wrap:wrap">'
        + ecnA(e.id, e.id + ' ↗', 'font-family:var(--font-mono);font-size:12px;color:var(--accent);text-decoration:none;font-weight:500') + badges
        + (x.ownerOut ? '<span style="font-family:var(--font-mono);font-size:8.5px;padding:1px 5px;border-radius:3px;background:var(--bad-bg);color:var(--bad-ink)">⚠ ' + esc(e.owner.split(',')[0]) + ' not in room</span>' : '') + '</div>'
        + '<div style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4);margin-top:5px">' + (axis === 'owner' ? 'Asked by ' + esc(e.requestor || '—') : 'Owner · ' + esc(e.owner)) + '</div>'
        + frame
        + '<div style="margin-top:9px;display:flex;align-items:center;gap:7px">'
        + '<span onclick="MeetingMode._pick(\'' + esc(e.id) + '\')" style="font-family:var(--font-sans);font-size:11.5px;font-weight:500;color:#fff;background:var(--ink);border-radius:5px;padding:4px 11px;cursor:pointer">Open &amp; update →</span>'
        + '<span onclick="MeetingMode._agendaSet(\'' + esc(e.id) + '\',\'closed\')" style="font-family:var(--font-sans);font-size:11.5px;color:var(--good-ink);background:var(--good-bg);border:1px solid color-mix(in oklab,var(--good) 24%,white);border-radius:5px;padding:4px 10px;cursor:pointer">✓ Mark done</span>'
        + '<span onclick="MeetingMode._agendaSet(\'' + esc(e.id) + '\',\'deferred\')" style="font-family:var(--font-sans);font-size:11.5px;color:var(--ink-3);border:1px solid var(--line-2);border-radius:5px;padding:4px 10px;cursor:pointer">Defer</span>'
        + '</div></div></div>';
    }).join('');
    var doneHtml = doneL.length ? '<div style="display:flex;align-items:center;gap:7px;padding:9px 16px;background:var(--good-bg);border-top:1px solid var(--line);border-bottom:1px solid var(--line)"><span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--good-ink);font-weight:600">Done this meeting</span><span style="font-family:var(--font-mono);font-size:10px;color:var(--good-ink)">' + doneL.length + '</span></div>'
      + doneL.map(function (x) { return '<div style="display:flex;align-items:center;gap:11px;padding:10px 16px;border-bottom:1px solid var(--line);opacity:.7"><span style="width:18px;height:18px;border-radius:50%;background:var(--good);color:#fff;font-size:10px;display:flex;align-items:center;justify-content:center;flex:none">✓</span><div style="flex:1;min-width:0">' + ecnA(x.e.id, x.e.id + ' ↗', 'font-family:var(--font-mono);font-size:11.5px;color:var(--accent);text-decoration:none;font-weight:500') + '<div style="font-family:var(--font-mono);font-size:10px;color:var(--good-ink);margin-top:2px">' + (x.n > 0 ? '✓ ' + x.n + (x.n === 1 ? ' update' : ' updates') + ' captured' : '✓ Marked done') + '</div></div>' + (x.n === 0 ? '<span onclick="MeetingMode._agendaSet(\'' + esc(x.e.id) + '\',\'closed\')" style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4);cursor:pointer;text-decoration:underline">undo</span>' : '') + '</div>'; }).join('') : '';
    var defHtml = defL.length ? '<div style="display:flex;align-items:center;gap:7px;padding:9px 16px;background:var(--surface-2);border-top:1px solid var(--line);border-bottom:1px solid var(--line)"><span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3);font-weight:600">Deferred</span><span style="font-family:var(--font-mono);font-size:10px;color:var(--ink-3)">' + defL.length + '</span></div>'
      + defL.map(function (x) { return '<div style="display:flex;align-items:center;gap:11px;padding:10px 16px;border-bottom:1px solid var(--line);opacity:.7"><span style="width:18px;height:18px;border-radius:50%;border:2px solid var(--line-2);flex:none"></span><div style="flex:1;min-width:0">' + ecnA(x.e.id, x.e.id + ' ↗', 'font-family:var(--font-mono);font-size:11.5px;color:var(--accent);text-decoration:none;font-weight:500') + '<div style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4);margin-top:2px">↪ Deferred</div></div><span onclick="MeetingMode._agendaSet(\'' + esc(x.e.id) + '\',\'deferred\')" style="font-family:var(--font-mono);font-size:10px;color:var(--ink-4);cursor:pointer;text-decoration:underline">undo</span></div>'; }).join('') : '';
    var topId = active.length ? active[0].e.id : (ranked.length ? ranked[0].id : '');
    var bn = bnCount > 1 ? 'Bottleneck — ' + bnStage + '. ' + bnCount + ' of ' + ranked.length + ' items wait at this one stage. Clear it first to unblock the rest.' : 'No single bottleneck — items are spread across stages.';
    return '<div style="position:fixed;top:0;right:0;bottom:0;width:432px;background:#fff;box-shadow:var(--shadow-pop);z-index:45;display:flex;flex-direction:column;animation:mmIn .2s ease">'
      + '<div style="display:flex;align-items:flex-start;justify-content:space-between;padding:16px 18px 12px;border-bottom:1px solid var(--line)"><div><div style="display:flex;align-items:center;gap:8px"><span style="font-family:var(--font-serif);font-size:16px;font-weight:500">Meeting agenda</span><span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.06em;background:var(--accent-2);color:var(--accent-ink);padding:2px 6px;border-radius:4px">AI</span></div><div style="font-family:var(--font-mono);font-size:10.5px;color:var(--ink-3);margin-top:6px">' + ranked.length + ' items · most urgent first</div></div><span onclick="MeetingMode._closeDrawer()" style="cursor:pointer;color:var(--ink-4);font-size:18px;line-height:1">×</span></div>'
      + '<div style="display:flex;align-items:center;gap:8px;padding:10px 16px;border-bottom:1px solid var(--line);background:var(--surface-2)"><span style="font-family:var(--font-mono);font-size:9.5px;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-4)">Frame by</span><span style="display:inline-flex;background:#fff;border:1px solid var(--line-2);border-radius:5px;padding:2px;gap:2px"><span onclick="MeetingMode._setAxis(\'requestor\')" style="' + (axis === 'requestor' ? segOn : segOff) + '">Requestors</span><span onclick="MeetingMode._setAxis(\'owner\')" style="' + (axis === 'owner' ? segOn : segOff) + '">IT owners</span></span></div>'
      + '<div style="margin:12px 16px 4px;display:flex;gap:10px;padding:11px 13px;background:var(--warn-bg);border:1px solid color-mix(in oklab,var(--warn) 30%,white);border-radius:6px"><span style="color:#6a4a12;font-size:13px;line-height:1.3">▲</span><div style="font-size:12.5px;color:#5a3e10;line-height:1.5">' + esc(bn) + '</div></div>'
      + '<div style="flex:1;overflow:auto">' + (activeHtml || '<div style="padding:24px;text-align:center;color:var(--ink-4);font-size:12px">All items handled or deferred.</div>') + doneHtml + defHtml + '</div>'
      + '<div style="border-top:1px solid var(--line);padding:11px 16px;display:flex;align-items:center;justify-content:space-between;background:#fff"><span style="font-family:var(--font-mono);font-size:11px;color:var(--ink-3)">Ranked for who\'s in the room</span>' + btn('Start at #1 →', true, 'MeetingMode._startTop(\'' + esc(topId) + '\')', 34) + '</div></div>';
  }

  // ---- add-people multi-select (no full re-render while picking, so the
  //      filter box keeps focus and the list keeps its scroll position) -----
  function togglePerson(i) {
    var o = (S._roster || [])[i]; if (!o) return;
    S.addPeopleSel[o.ad] = !S.addPeopleSel[o.ad];
    var on = !!S.addPeopleSel[o.ad];
    var chk = document.getElementById('mm-rostercheck-' + i);
    if (chk) { chk.style.borderColor = on ? 'var(--accent)' : 'var(--line-2)'; chk.style.background = on ? 'var(--accent)' : '#fff'; chk.textContent = on ? '✓' : ''; }
    updatePeopleCount();
  }
  function filterPeople(q) {
    q = (q || '').toLowerCase();
    (S._roster || []).forEach(function (o, i) {
      var row = document.getElementById('mm-rosteropt-' + i);
      if (row) row.style.display = (!q || o.name.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
    });
  }
  function updatePeopleCount() {
    var n = (S._roster || []).filter(function (o) { return S.addPeopleSel[o.ad]; }).length;
    var c = document.getElementById('mm-people-count'); if (c) c.textContent = n + ' selected';
    var b = document.getElementById('mm-people-add'); if (b) b.textContent = 'Add' + (n ? ' (' + n + ')' : '') + ' →';
  }
  function commitPeople() {
    (S._roster || []).forEach(function (o) {
      if (S.addPeopleSel[o.ad] && !inRoom(o.ad)) S.room.push({ ad: o.ad, name: o.name, leader: false });
    });
    S.addPeopleSel = {}; S.addPeopleOpen = false; render();
  }

  // =====================================================================
  // ACTIONS (called from inline handlers)
  // =====================================================================
  function reseedSelected() { /* no-op: detail reads DOM values live */ }

  // ---- Backlog Grade — handlers + accept pipeline ----------------------
  var GRADE_DEFAULTS = { admin: 'PDL-PLM-admin@sandisk.com', it: 'PDL-PLM-admin@sandisk.com' };
  function gradeNotifyAdmin() { return (S.gradeUi.notify && S.gradeUi.notify.admin) || GRADE_DEFAULTS.admin; }
  function gradeNotifyIt() { return (S.gradeUi.notify && S.gradeUi.notify.it) || GRADE_DEFAULTS.it; }
  function detectItNotRequired(rec) {
    var d = ((rec && rec.desc) || '').toLowerCase();
    return /(agile )?it\b[^.]*\b(not|no|isn'?t|never)\b[^.]*(need|requir|necess|involv)|no it (help|involv|support)|it (help|support) not (need|requir)|not an it (item|change|ecn)|business[- ]?(side|led|only)/.test(d);
  }
  function prettyModel(slug) { var s = String(slug || ''); var t = s.indexOf('/') >= 0 ? s.slice(s.indexOf('/') + 1) : s; return t || s; }
  function gradeOpenAux() { fetchGradeModels(); loadGradeHistory(); }
  // Re-apply active standing policies to the current backlog (AI-matched server-side),
  // marking matched ECNs as "not considered for grading." Runs on every grade.
  function applyPolicies(done) {
    var items = (S.items || []).filter(function (it) { return !doneStage(it.status); })
      .map(function (it) { return { id: it.id, title: it.title, desc: it.title, status: it.status }; });
    api('POST', '/api/meeting/grade/apply-policies', { items: items, model: S.gradeUi.model }).then(function (r) {
      var pe = {}; ((r && r.excluded) || []).forEach(function (id) { pe[id] = true; });
      S.gradeUi.policyExcluded = pe;
      S.gradeUi.policies = (r && r.policies) || [];
      S.gradeData = null;
      if (done) done(); else if (S.drawer === 'grade') render();
    }).catch(function () { if (done) done(); });
  }
  function fetchGradeModels() {
    if (S.gradeUi.models.length) return;
    var saved = ''; try { saved = localStorage.getItem('mm-grade-model') || ''; } catch (e) {}
    if (saved) S.gradeUi.model = saved;
    api('GET', '/api/ai-eval/models').then(function (r) {
      var out = [{ slug: '', label: 'Production default' }];
      // catalog shape: { groups:[{label, options:[{slug,label}]}] }; tolerate a flat list too.
      if (r && Array.isArray(r.groups)) {
        r.groups.forEach(function (gp) { (gp.options || []).forEach(function (o) { if (o && o.slug) out.push({ slug: o.slug, label: o.label || prettyModel(o.slug) }); }); });
      } else {
        var list = (r && (r.models || r)) || [];
        if (Array.isArray(list)) list.forEach(function (m) {
          if (typeof m === 'string') out.push({ slug: m, label: prettyModel(m) });
          else if (m && (m.slug || m.id)) out.push({ slug: m.slug || m.id, label: m.label || m.name || prettyModel(m.slug || m.id) });
        });
      }
      S.gradeUi.models = out;
      if (S.drawer === 'grade') render();
    }).catch(function () {});
  }
  function persistGradeModel(slug) { try { localStorage.setItem('mm-grade-model', slug || ''); } catch (e) {} }
  function loadGradeHistory() {
    api('GET', '/api/meeting/grade/history').then(function (r) {
      if (r && (r.logic || r.accepts || r.outbox)) {
        S.gradeUi.history = { logic: r.logic || [], accepts: r.accepts || [], outbox: r.outbox || [] };
      }
      if (r && r.lastGrade && typeof r.lastGrade.score === 'number') { S.gradeUi.lastScore = r.lastGrade.score; S.gradeUi.lastLetter = r.lastGrade.letter; }
      if (r && r.notify) S.gradeUi.notify = r.notify;
      S.gradeUi.historyLoaded = true;
      if (S.drawer === 'grade') render();
    }).catch(function () { S.gradeUi.historyLoaded = true; });
  }
  function seedGradeChat() {
    var ui = S.gradeUi; if (ui.chatSeeded) return; ui.chatSeeded = true;
    var g = computeGrade();
    ui.chat = [{ role: 'ai', text: 'This backlog scores ' + g.letter + ' (' + g.score + ' / 100). The main risks are overdue UAT/Go-Live items and unowned high-priority items; missing target dates is a soft signal. Think an item shouldn’t count? Tell me which ECN and why — I’ll need proof before I re-grade.' }];
  }
  function gradeRecord(ecn) {
    var c = buildCounts(), all = c.overdue.concat(c.unownedHi);
    var r = all.filter(function (x) { return x.id === ecn; })[0] || { desc: '', title: '', status: '' };
    return { desc: r.desc, title: r.title, status: r.status, itNotRequired: detectItNotRequired(r) };
  }
  // Evaluate via the server LLM path; fall back to the deterministic module on
  // any error / missing endpoint (the on-prem path the handoff requires).
  function evaluateDisputeRemote(payload) {
    return api('POST', '/api/meeting/grade/evaluate', payload).then(function (r) {
      if (r && r.status) return r;
      return window.GradeLogic.evaluateDispute({ text: payload.reason, hasFile: payload.hasFile, record: payload.record });
    }).catch(function () {
      return window.GradeLogic.evaluateDispute({ text: payload.reason, hasFile: payload.hasFile, record: payload.record });
    });
  }
  function uploadDisputeFile(file) {
    if (!file) return Promise.resolve('');
    var fd = new FormData(); fd.append('file', file);
    return fetch('/api/meeting/grade/disputes/attachment', { method: 'POST', credentials: 'same-origin', body: fd })
      .then(function (r) { return r.json().catch(function () { return {}; }); })
      .then(function (r) { return (r && r.attachmentRef) || ''; }).catch(function () { return ''; });
  }
  function acceptReasonText(ev, kind, label, fileName) {
    var basis;
    if (ev.verifiedBy === 'agile_record') basis = 'I checked the ECN record in Agile and the Description field confirms it' + (ev.fieldQuote ? (' ("' + ev.fieldQuote + '")') : '') + ', so no upload was needed';
    else if (ev.verifiedBy === 'attachment' || fileName) basis = 'the screenshot you attached' + (fileName ? (' (' + fileName + ')') : '') + ' confirms it';
    else basis = 'your rationale cites ' + (((ev.evidence || []).join(', ')) || 'verifiable evidence') + ', which I can verify against Agile';
    return 'Accepted — ' + basis + '. ' + (kind === 'rule' ? ('Applied as a runtime grading-logic change: ' + label + '.') : ('Excluded ' + label + ' from the at-risk set.'));
  }
  function ruleSummaryFor(key) { return { overdue: 'Items overdue < 7 days no longer count', noowner: 'Unowned high-priority no longer penalized', missing: 'Missing target dates set to 0 penalty' }[key]; }
  function ruleDetailFor(key) { return { overdue: 'overdue grace 0d → 7d', noowner: 'unowned high-pri: counted → ignored', missing: 'missing-date penalty 6 → 0' }[key]; }
  function applyRuleChange(key) { var r = S.gradeUi.rule; if (key === 'overdue') r.graceDays = 7; else if (key === 'noowner') r.includeNoOwner = false; else if (key === 'missing') r.missingPenalty = 0; }
  function revertRuleChange(key) { var r = S.gradeUi.rule; if (key === 'overdue') r.graceDays = 0; else if (key === 'noowner') r.includeNoOwner = true; else if (key === 'missing') r.missingPenalty = 6; }
  function newLocalId(p) { return p + '_local_' + Date.now() + '_' + Math.floor(Math.random() * 1000); }
  function localStamp(rec, emailedTo) {
    return Object.assign({ id: newLocalId(rec.kind === 'rule' ? 'gl' : 'gd'), createdAt: Date.now(), actorName: S.user.displayName || S.user.loginId || '', actorAd: S.user.loginId || '', emailedTo: emailedTo }, rec);
  }
  function buildLocalOutbox(rec, isIt) {
    var to = isIt ? gradeNotifyIt() : gradeNotifyAdmin();
    var by = S.user.displayName || S.user.loginId || 'You';
    var attach = rec.attachmentRef ? ('Attached proof: ' + ((S.gradeUi.disputeFile && S.gradeUi.disputeFile.name) || 'screenshot') + '.')
      : (rec.verifiedBy === 'agile_record' ? ('Verified against Agile ECN record — Description' + (rec.fieldQuote ? (': "' + rec.fieldQuote + '"') : '') + '.') : null);
    var isInclude = rec.action === 'include';
    var body = (isIt ? [
      by + ' changed a grading rule at run time.',
      'Change: ' + rec.summary + ' (' + rec.detail + ').',
      'Rationale: "' + rec.userReason + '"', attach, 'AI decision: ' + rec.aiReason,
      'Grade: ' + rec.gradeBefore + ' (' + rec.scoreBefore + ') → ' + rec.gradeAfter + ' (' + rec.scoreAfter + ').',
      'This rule now applies to every future grade until reverted.'
    ] : isInclude ? [
      by + ' put ' + ecnShortJs(rec.targetEcn) + ' back into the at-risk set.',
      'Rationale: "' + rec.userReason + '"',
      'Grade: ' + rec.gradeBefore + ' (' + rec.scoreBefore + ') → ' + rec.gradeAfter + ' (' + rec.scoreAfter + ').'
    ] : [
      by + ' disputed ' + ecnShortJs(rec.targetEcn) + ' and the AI accepted it.',
      'Rationale: "' + rec.userReason + '"', attach, 'AI decision: ' + rec.aiReason,
      'Grade: ' + rec.gradeBefore + ' (' + rec.scoreBefore + ') → ' + rec.gradeAfter + ' (' + rec.scoreAfter + ').'
    ]).filter(function (x) { return !!x; });
    var subject = isIt ? ('Grading logic changed at run time — ' + rec.summary)
      : isInclude ? ('Backlog re-graded ' + rec.gradeBefore + ' → ' + rec.gradeAfter + ' — item re-included (' + ecnShortJs(rec.targetEcn) + ')')
      : ('Backlog re-graded ' + rec.gradeBefore + ' → ' + rec.gradeAfter + ' — dispute accepted (' + ecnShortJs(rec.targetEcn) + ')');
    S.gradeUi.history.outbox.unshift({ to: to, subject: subject, body: body, sentAt: Date.now(), it: isIt });
  }
  function clearDisputeForm() { var ui = S.gradeUi; ui.disputeFor = null; ui.disputeText = ''; ui.disputeFile = null; ui.aiReply = null; ui.busy = false; }
  function gradeBacklogItems() {
    return (S.items || []).filter(function (it) { return !doneStage(it.status); })
      .map(function (it) { return { id: it.id, title: it.title, desc: it.title, status: it.status }; });
  }
  function gradeSubmitDispute() {
    var ui = S.gradeUi, forKey = ui.disputeFor; if (!forKey || ui.busy) return;
    var ta = document.getElementById('mm-dispute-ta'); if (ta) ui.disputeText = (ta.value || '').trim();
    var reason = ui.disputeText;
    var isRule = forKey.indexOf('rule:') === 0;
    if (isRule) { submitRuleOrPolicy(forKey.replace('rule:', ''), reason); return; }
    var ecn = forKey, record = gradeRecord(ecn);
    ui.busy = true; render();
    evaluateDisputeRemote({ kind: 'item', ecn: ecn, reason: reason, hasFile: !!ui.disputeFile, model: ui.model, sessionId: S.sessionId || '', record: record }).then(function (ev) {
      ui.busy = false;
      if (!ev || ev.status !== 'accept') { ui.aiReply = { status: (ev && ev.status) || 'reject', text: (ev && ev.reason) || 'Could not verify that.' }; render(); return; }
      acceptItem(ecn, record, reason, ev);
    });
  }
  // Rule-box submit: the server decides whether the argument is a numeric TOGGLE
  // for this signal or a content POLICY over a theme of ECNs (which needs no
  // per-ECN screenshot). Policy wins when it describes a class of ECNs.
  function submitRuleOrPolicy(ruleKey, reason) {
    var ui = S.gradeUi; ui.busy = true; render();
    api('POST', '/api/meeting/grade/rule-or-policy', { ruleKey: ruleKey, reason: reason, items: gradeBacklogItems(), model: ui.model }).then(function (r) {
      ui.busy = false;
      if (!r || !r.outcome) { ui.aiReply = { status: 'reject', text: 'Could not reach the grader — try again.' }; render(); return; }
      if (r.outcome === 'policy') { onPolicyAccepted(r); clearDisputeForm(); render(); return; }
      if (r.outcome === 'toggle') { acceptRule(ruleKey, reason, { verifiedBy: r.verifiedBy, evidence: r.evidence || [] }); clearDisputeForm(); render(); return; }
      ui.aiReply = { status: r.outcome === 'need_file' ? 'need_file' : 'reject', text: r.reason || 'Not enough to change the grading logic.' }; render();
    }).catch(function () { ui.busy = false; ui.aiReply = { status: 'reject', text: 'Could not reach the grader — try again.' }; render(); });
  }
  // A standing theme policy was accepted (from the rule box or Ask AI): mark the
  // matched ECNs as no-longer-graded, re-grade, log it, and report the count.
  function onPolicyAccepted(r) {
    var ui = S.gradeUi, before = computeGrade();
    var matched = r.matched || [];
    matched.forEach(function (id) { ui.policyExcluded[id] = true; });
    var pol = r.policy || {};
    if (pol.id) ui.policies.unshift({ id: pol.id, predicate: pol.predicate, count: matched.length });
    S.gradeData = null;
    var after = computeGrade(), n = matched.length;
    var rec = { kind: 'rule', ruleKey: 'policy', id: pol.id, summary: pol.predicate || 'Theme policy',
      detail: n + ' ECN' + (n === 1 ? '' : 's') + ' excluded', userReason: pol.userReason || r.reason || '',
      aiReason: r.rationale || '', gradeBefore: before.letter, gradeAfter: after.letter, scoreBefore: before.score, scoreAfter: after.score };
    ui.history.logic.unshift(localStamp(rec, r.emailedTo || gradeNotifyIt()));
    buildLocalOutbox({ kind: 'rule', summary: pol.predicate || 'Theme policy', detail: n + ' ECNs excluded', userReason: rec.userReason, aiReason: rec.aiReason, gradeBefore: before.letter, gradeAfter: after.letter, scoreBefore: before.score, scoreAfter: after.score }, true);
    ui.flash = { text: n + ' ECN' + (n === 1 ? ' is' : 's are') + ' no longer considered for grading — ' + (pol.predicate || 'policy added') + '. Re-graded ' + before.letter + ' → ' + after.letter + '.', to: r.emailedTo || gradeNotifyIt() };
  }
  function acceptItem(ecn, record, reason, ev) {
    var ui = S.gradeUi, file = ui.disputeFile && ui.disputeFile.file;
    (file ? uploadDisputeFile(file) : Promise.resolve('')).then(function (attachmentRef) {
      var before = computeGrade();
      ui.excluded[ecn] = true; S.gradeData = null;
      var after = computeGrade();
      var rec = { kind: 'item', targetEcn: ecn, title: (record && record.title) || '', userReason: reason,
        verifiedBy: ev.verifiedBy || 'cited_evidence', fieldQuote: ev.fieldQuote || '', attachmentRef: attachmentRef || '',
        aiReason: acceptReasonText(ev, 'item', ecnShortJs(ecn), (ui.disputeFile && ui.disputeFile.name) || ''),
        gradeBefore: before.letter, gradeAfter: after.letter, scoreBefore: before.score, scoreAfter: after.score,
        sessionId: S.sessionId || '', model: ui.model || '' };
      ui.history.accepts.unshift(localStamp(rec, gradeNotifyAdmin()));
      buildLocalOutbox(rec, false);
      api('POST', '/api/meeting/grade/disputes', rec).then(function (r) { if (r && r.record && r.record.id) reconcile(ui.history.accepts, r.record); }).catch(function () {});
      var how = ev.verifiedBy === 'agile_record' ? ' (verified against the Agile record — no upload needed)' : (attachmentRef ? ' and attached proof' : '');
      ui.flash = { text: 'Re-graded ' + before.letter + ' → ' + after.letter + '. ' + ecnShortJs(ecn) + ' excluded with a documented reason' + how + '.', to: gradeNotifyAdmin() };
      clearDisputeForm(); render();
    });
  }
  function acceptRule(key, reason, ev) {
    var ui = S.gradeUi, before = computeGrade();
    applyRuleChange(key); ui.ruleApplied[key] = true; S.gradeData = null;
    var after = computeGrade(), summary = ruleSummaryFor(key), detail = ruleDetailFor(key);
    var rec = { kind: 'rule', ruleKey: key, summary: summary, detail: detail, userReason: reason,
      verifiedBy: ev.verifiedBy || 'cited_evidence', fieldQuote: ev.fieldQuote || '', attachmentRef: '',
      aiReason: acceptReasonText(ev, 'rule', summary, (ui.disputeFile && ui.disputeFile.name) || ''),
      gradeBefore: before.letter, gradeAfter: after.letter, scoreBefore: before.score, scoreAfter: after.score,
      ruleConfigJson: JSON.stringify(ui.rule), sessionId: S.sessionId || '', model: ui.model || '' };
    ui.history.logic.unshift(localStamp(rec, gradeNotifyIt()));
    buildLocalOutbox(rec, true);
    api('POST', '/api/meeting/grade/logic-changes', rec).then(function (r) { if (r && r.record && r.record.id) reconcile(ui.history.logic, r.record); }).catch(function () {});
    ui.flash = { text: 'Grading logic changed at run time — ' + summary + '. Re-graded ' + before.letter + ' → ' + after.letter + '.', to: gradeNotifyIt() };
    clearDisputeForm(); render();
  }
  // Replace the optimistic local record (matched by content) with the durable
  // server one once the POST returns (gives it a real id for revert).
  function reconcile(list, serverRec) {
    for (var i = 0; i < list.length; i++) {
      if (String(list[i].id).indexOf('_local_') >= 0 && list[i].kind === serverRec.kind
        && list[i].userReason === serverRec.userReason
        && (list[i].targetEcn || '') === (serverRec.targetEcn || '')
        && (list[i].ruleKey || '') === (serverRec.ruleKey || '')) { list[i] = serverRec; break; }
    }
    if (S.drawer === 'grade') render();
  }
  // Find every at-risk item named in the message (order-preserving, deduped) —
  // not just the last one. Matches the full id or the bare number.
  function gradeMatchEcns(text) {
    var low = (text || '').toLowerCase(), c = buildCounts(), all = c.overdue.concat(c.unownedHi);
    var seen = {}, out = [];
    all.forEach(function (x) {
      var tail = String(x.id).replace('ECN-', '').replace('-PROJ', '');
      if ((low.indexOf(String(x.id).toLowerCase()) >= 0 || low.indexOf(tail.toLowerCase()) >= 0) && !seen[x.id]) { seen[x.id] = 1; out.push(x); }
    });
    return out;
  }
  function gradeCommitExclude(item, reason, ev) {
    var ui = S.gradeUi, before = computeGrade();
    ui.excluded[item.id] = true; S.gradeData = null;
    var after = computeGrade();
    var rec = { kind: 'item', action: 'exclude', targetEcn: item.id, title: item.title || '', userReason: reason, verifiedBy: ev.verifiedBy || 'cited_evidence', fieldQuote: ev.fieldQuote || '', attachmentRef: '', aiReason: acceptReasonText(ev, 'item', ecnShortJs(item.id), ''), gradeBefore: before.letter, gradeAfter: after.letter, scoreBefore: before.score, scoreAfter: after.score, sessionId: S.sessionId || '', model: ui.model || '' };
    ui.history.accepts.unshift(localStamp(rec, gradeNotifyAdmin())); buildLocalOutbox(rec, false);
    api('POST', '/api/meeting/grade/disputes', rec).then(function (r) { if (r && r.record && r.record.id) reconcile(ui.history.accepts, r.record); }).catch(function () {});
    return { before: before, after: after };
  }
  // Keep an item in scope, or put a previously-excluded one back. Always logged
  // (with reason); emailed only when it actually moves the grade (a restore).
  function gradeCommitInclude(ecn, title, reason) {
    var ui = S.gradeUi, before = computeGrade();
    var wasExcluded = !!ui.excluded[ecn];
    if (wasExcluded) delete ui.excluded[ecn];
    S.gradeData = null;
    var after = computeGrade();
    var changed = before.score !== after.score;
    var rec = { kind: 'item', action: 'include', targetEcn: ecn, title: title || '', userReason: reason, verifiedBy: '', fieldQuote: '', attachmentRef: '', aiReason: wasExcluded ? 'Put back into the at-risk set.' : 'Kept in scope.', gradeBefore: before.letter, gradeAfter: after.letter, scoreBefore: before.score, scoreAfter: after.score, sessionId: S.sessionId || '', model: ui.model || '' };
    ui.history.accepts.unshift(localStamp(rec, changed ? gradeNotifyAdmin() : ''));
    if (changed) buildLocalOutbox(rec, false);
    api('POST', '/api/meeting/grade/disputes', rec).then(function (r) { if (r && r.record && r.record.id) reconcile(ui.history.accepts, r.record); }).catch(function () {});
    return { before: before, after: after, changed: changed };
  }
  // Free-form, two-way chat. The server (Opus, deterministic fallback) resolves
  // the message into include/exclude actions; the client applies them, re-grades,
  // and persists each. No looping — an ambiguous ask comes back as a question.
  function gradeChatSend() {
    var inp = document.getElementById('mm-grade-chat'); var val = inp ? (inp.value || '').trim() : ''; if (!val) return;
    var ui = S.gradeUi; ui.chat.push({ role: 'user', text: val }); if (inp) inp.value = '';
    ui.chat.push({ role: 'ai', text: '…' }); var thinking = ui.chat.length - 1; render();
    var c = buildCounts();
    var atRisk = c.overdue.concat(c.unownedHi).map(function (x) { return { id: x.id, title: x.title, desc: x.desc, status: x.status, kind: x.kind }; });
    var excluded = Object.keys(ui.excluded).filter(function (k) { return ui.excluded[k]; });
    function drop() { if (ui.chat[thinking] && ui.chat[thinking].text === '…') ui.chat.splice(thinking, 1); }
    api('POST', '/api/meeting/grade/chat', { message: val, atRisk: atRisk, excluded: excluded, model: ui.model }).then(function (r) {
      drop();
      // Standing theme policy stated in chat.
      if (r && r.policy && r.policy.id) {
        onPolicyAccepted(r);
        var g0 = computeGrade(), n0 = (r.matched || []).length;
        ui.chat.push({ role: 'ai', text: (r.reply ? r.reply + ' ' : '') + n0 + ' ECN' + (n0 === 1 ? ' is' : 's are') + ' no longer considered for grading (standing policy). Grade is now ' + g0.letter + ' (' + g0.score + ' / 100). Revert it anytime in History.' });
        render(); return;
      }
      var actions = (r && r.actions) || [];
      if (!actions.length) { ui.chat.push({ role: 'ai', text: (r && r.reply) || 'Tell me which ECN to include or exclude, and why.' }); render(); return; }
      var byId = {}; atRisk.forEach(function (x) { byId[x.id] = x; });
      var excl = [], incl = [], needFile = [], rejected = [], lastDelta = null;
      actions.forEach(function (a) {
        var item = byId[a.ecn] || { id: a.ecn, title: '' };
        if (a.action === 'exclude') {
          if (a.decision === 'accept') { if (!ui.excluded[a.ecn]) { lastDelta = gradeCommitExclude(item, a.reason || val, { verifiedBy: a.verifiedBy, fieldQuote: a.fieldQuote, evidence: [] }); excl.push(ecnShortJs(a.ecn)); } }
          else if (a.decision === 'need_file') needFile.push(ecnShortJs(a.ecn));
          else rejected.push(ecnShortJs(a.ecn));
        } else if (a.action === 'include') {
          var d = gradeCommitInclude(a.ecn, item.title, a.reason || val); lastDelta = { before: d.before, after: d.after }; incl.push(ecnShortJs(a.ecn));
        }
      });
      var g = computeGrade();
      var lines = [];
      if (r.reply) lines.push(r.reply);
      if (excl.length) lines.push('Excluded ' + excl.join(', ') + '.');
      if (incl.length) lines.push('Kept/included ' + incl.join(', ') + '.');
      if (needFile.length) lines.push('For ' + needFile.join(', ') + ' I need a screenshot — open Dispute in the At-risk tab.');
      if (rejected.length) lines.push('Couldn’t verify ' + rejected.join(', ') + ' — give a date, a new owner, or a CCB/policy note.');
      lines.push('Grade is now ' + g.letter + ' (' + g.score + ' / 100).');
      ui.chat.push({ role: 'ai', text: lines.join(' ') });
      if (lastDelta) ui.flash = { text: 'Re-graded ' + lastDelta.before.letter + ' → ' + g.letter + '.', to: gradeNotifyAdmin() };
      render();
    }).catch(function () { drop(); ui.chat.push({ role: 'ai', text: 'Sorry — I couldn’t reach the grader just now. Try again.' }); render(); });
  }
  function gradeRevertRule(id, key) {
    var ui = S.gradeUi;
    if (key === 'policy') {
      // Deactivate the standing policy, then re-match remaining policies so the
      // excluded set (and grade) rebuilds correctly.
      ui.history.logic = ui.history.logic.filter(function (r) { return r.id !== id; });
      ui.policies = ui.policies.filter(function (p) { return p.id !== id; });
      api('DELETE', '/api/meeting/grade/policies/' + encodeURIComponent(id)).then(function () { applyPolicies(function () { S.gradeData = null; render(); }); }).catch(function () { render(); });
      return;
    }
    revertRuleChange(key); delete ui.ruleApplied[key]; S.gradeData = null;
    ui.history.logic = ui.history.logic.filter(function (r) { return r.id !== id; });
    if (id && String(id).indexOf('_local_') < 0) api('DELETE', '/api/meeting/grade/logic-changes/' + encodeURIComponent(id)).catch(function () {});
    render();
  }

  var H = {
    _toggleAddPeople: function () { S.addPeopleOpen = !S.addPeopleOpen; if (!S.addPeopleOpen) S.addPeopleSel = {}; render(); },
    _togglePerson: function (i) { togglePerson(i); },
    _filterPeople: function (q) { filterPeople(q); },
    _commitPeople: function () { commitPeople(); },
    _removePerson: function (i) { if (S.room[i] && !S.room[i].leader) S.room.splice(i, 1); render(); },
    _select: function (id) { S.selectedId = id; render(); },
    _pick: function (id) { S.selectedId = id; S.drawer = null; if (S.phase !== 'live' && S.phase !== 'wrap') startSession(); else render(); },
    _startNow: function () { startSession(); },
    _startTop: function (id) { if (id) { S.selectedId = id; } S.drawer = null; if (S.phase !== 'live' && S.phase !== 'wrap') startSession(); else render(); },
    _gradeNow: function () { gradeNow(); },
    _buildAgenda: function () { S.drawer = 'agenda'; render(); },
    _closeDrawer: function () { S.drawer = null; render(); },
    _setAxis: function (a) { S.agendaAxis = a; render(); },
    _agendaSet: function (id, st) { var c = S.agendaStatus[id]; if (c === st) delete S.agendaStatus[id]; else S.agendaStatus[id] = st; render(); },
    _saveDetail: function () { saveDetail(); },
    _addNote: function () { addNote(); },
    _wrapUp: function () { if (!S.bubbles.length) { window.appConfirm('Nothing captured yet — wrap up anyway?', { title: 'Empty session' }).then(function (ok) { if (ok) { S.phase = 'wrap'; render(); } }); return; } S.phase = 'wrap'; render(); },
    _cancelWrap: function () { S.phase = 'live'; render(); },
    _send: function () { sendRecap(); },
    _mysave: function (i) { mySave(i); },
    _openRecord: function (id) { openRecord(id); },
    _backToRecords: function () { S.view = 'records'; S.recordData = null; render(); },
    _toggleAction: function (id, status) { toggleAction(id, status); },
    // ---- v2 workspace ----
    _setFilter: function (k) { S.railFilter = k; S._resetRailScroll = true; render(); },
    _gradeDriver: function (key) { S.gradeOpen[key] = !S.gradeOpen[key]; render(); },
    // ---- Backlog Grade modal ----
    _gradeTab: function (t) { S.gradeUi.tab = t; if (t === 'history') loadGradeHistory(); render(); },
    _gradeExplainer: function () { S.gradeUi.explainer = !S.gradeUi.explainer; render(); },
    _gradeModel: function (i) { var m = S.gradeUi.models[i]; if (m) { S.gradeUi.model = m.slug; persistGradeModel(m.slug); render(); } },
    _gradeOpenDispute: function (forKey) { var ui = S.gradeUi; ui.disputeFor = forKey; ui.disputeText = ''; ui.disputeFile = null; ui.aiReply = null; if (forKey.indexOf('rule:') !== 0) ui.tab = 'atrisk'; render(); var ta = document.getElementById('mm-dispute-ta'); if (ta) ta.focus(); },
    _gradeCancelDispute: function () { clearDisputeForm(); render(); },
    _gradeDraft: function (v) { S.gradeUi.disputeText = v; },   // no render — preserve focus/content
    _gradeFile: function (input) { var f = input && input.files && input.files[0]; if (f) { S.gradeUi.disputeFile = { name: f.name, file: f }; S.gradeUi.aiReply = null; render(); } },
    _gradeClearFile: function () { S.gradeUi.disputeFile = null; render(); },
    _gradeSubmitDispute: function () { gradeSubmitDispute(); },
    _gradeRestore: function (id) { delete S.gradeUi.excluded[id]; S.gradeData = null; render(); },
    _gradeChatSend: function () { gradeChatSend(); },
    _gradeRevertRule: function (id, key) { gradeRevertRule(id, key); },
    _railSearch: function (q) { S.railSearch = q; applyRailSearch(); var r = document.getElementById('mm-rail'); if (r) { r.scrollTop = 0; S._railScroll = 0; } },
    _toggleStatusDD: function () { S.statusOpen = !S.statusOpen; S.reqOpen = false; S.prioOpen = false; render(); },
    _toggleReqDD: function () { S.reqOpen = !S.reqOpen; S.prioOpen = false; S.statusOpen = false; render(); },
    _togglePrioDD: function () { S.prioOpen = !S.prioOpen; S.reqOpen = false; S.statusOpen = false; render(); },
    _toggleStatus: function (i) { if (i < 0) { S.statusFilter = {}; } else { var o = (S._statusOpts || [])[i]; if (o) S.statusFilter[o] = !S.statusFilter[o]; } S._resetRailScroll = true; render(); },
    _toggleReq: function (i) { if (i < 0) { S.reqFilter = {}; } else { var o = (S._reqOpts || [])[i]; if (o) S.reqFilter[o] = !S.reqFilter[o]; } S._resetRailScroll = true; render(); },
    _togglePrio: function (i) { if (i < 0) { S.prioFilter = {}; } else { var o = (S._prioOpts || [])[i]; if (o) S.prioFilter[o] = !S.prioFilter[o]; } S._resetRailScroll = true; render(); },
    _cancelMeeting: function () { cancelMeeting(); },
    _undo: function (bid) { undoBubble(bid); },
    _deleteRecord: function (id) { deleteRecord(id); },
    _toggleRecSel: function (id) { if (S.recSelect[id]) delete S.recSelect[id]; else S.recSelect[id] = true; render(); },
    _clearRecSel: function () { S.recSelect = {}; render(); },
    _consolidate: function () { consolidateRecords(); },
    _railResize: function (e) { railResize(e); },
    _toggleMax: function () { S.maximized = !S.maximized; applyChrome(); render(); },
  };

  function snapshotItems() {
    // Deep-copy the editable item state at Start so Cancel can fully revert
    // in-meeting field edits (uat/goLive/status). Writes commit only at send,
    // so this is a pure local rollback.
    S._snapshot = {};
    S.items.forEach(function (it) { S._snapshot[it.id] = { uat: it.uat, goLive: it.goLive, status: it.status }; });
  }
  function startSession() {
    if (S.sessionId) { S.phase = 'live'; startTimer(); render(); return; }
    snapshotItems();
    var attendees = S.room.map(function (p) { return { ad: p.ad, name: p.name, isLeader: !!p.leader }; });
    api('POST', '/api/meeting/sessions', { title: 'IT Enhancements review', attendees: attendees }).then(function (r) {
      if (r && r.sessionId) { S.sessionId = r.sessionId; S.phase = 'live'; S.elapsed = 0; startTimer(); render(); }
      else window.appAlert('Could not start the session.', { title: 'Start failed' });
    });
  }
  function cancelMeeting() {
    var n = S.bubbles.length;
    window.appConfirm('Discards all ' + n + ' captured update(s). Nothing is written to Agile and no email is sent. Field changes made in the meeting are reverted.',
      { title: 'Cancel this meeting?', danger: true, okText: 'Discard meeting', cancelText: 'Keep' }).then(function (ok) {
      if (!ok) return;
      if (S.sessionId) api('DELETE', '/api/meeting/sessions/' + S.sessionId);   // discard server session
      // revert in-meeting field edits from the snapshot
      if (S._snapshot) S.items.forEach(function (it) { var snap = S._snapshot[it.id]; if (snap) { it.uat = snap.uat; it.goLive = snap.goLive; it.status = snap.status; it.badges = computeBadges(it); it.flags = computeFlags(it); } });
      clearInterval(S._timer);
      S.sessionId = null; S.bubbles = []; S.phase = 'pre'; S.elapsed = 0; S.agendaStatus = {}; S._snapshot = null;
      render();
    });
  }
  function undoBubble(bid) {
    var idx = -1, b = null;
    for (var i = 0; i < S.bubbles.length; i++) if (S.bubbles[i].id === bid) { idx = i; b = S.bubbles[i]; break; }
    if (idx < 0) return;
    // revert the underlying field edit (structured Agile bubbles carry oldValue)
    var it = itemById(b.ecn);
    if (it && b.field === 'TARGET_UAT' && b.oldValue !== undefined) it.uat = b.oldValue;
    if (it && b.field === 'TARGET_GOLIVE' && b.oldValue !== undefined) it.goLive = b.oldValue;
    if (it) { it.badges = computeBadges(it); it.flags = computeFlags(it); }
    S.bubbles.splice(idx, 1);
    if (S.sessionId) api('DELETE', '/api/meeting/sessions/' + S.sessionId + '/bubbles/' + encodeURIComponent(bid));
    render();
  }
  // ---- v3 record management (PLM admin only; server also enforces) -------
  function isAdmin() { return !!(S.user && S.user.isItTeam); }
  function dayKeyMs(ms) { var d = new Date(ms || Date.now()); return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate(); }
  function deleteRecord(id) {
    window.appConfirm('The frozen log and its action items are removed. Updates already written to Agile are not affected. This cannot be undone.',
      { title: 'Delete this meeting record?', danger: true, okText: 'Delete record', cancelText: 'Keep' }).then(function (ok) {
      if (!ok) return;
      api('DELETE', '/api/meeting/records/' + encodeURIComponent(id)).then(function (r) {
        if (r && r.ok) {
          S.records = S.records.filter(function (x) { return x.id !== id; });
          if (S.selectedRecord === id) { S.view = 'records'; S.recordData = null; }
          delete S.recSelect[id]; render();
        } else window.appAlert((r && r.error) || 'Delete failed.', { title: 'Delete failed' });
      });
    });
  }
  function consolidateRecords() {
    var ids = Object.keys(S.recSelect).filter(function (k) { return S.recSelect[k]; });
    if (ids.length < 2) return;
    var days = {};
    ids.forEach(function (id) { var r = S.records.find(function (x) { return x.id === id; }); if (r) days[dayKeyMs(r.date)] = 1; });
    if (Object.keys(days).length !== 1) { window.appAlert('Pick records from the same day to merge.', { title: 'Different days' }); return; }
    window.appConfirm('Combines the selected same-day meetings into one record — totals are summed and both logs are concatenated. The originals are replaced.',
      { title: 'Consolidate ' + ids.length + ' records?', okText: 'Consolidate', cancelText: 'Keep' }).then(function (ok) {
      if (!ok) return;
      api('POST', '/api/meeting/records/consolidate', { ids: ids }).then(function (r) {
        if (r && r.record) { S.recSelect = {}; S.recordsLoaded = false; loadRecords(); }
        else window.appAlert((r && r.error) || 'Consolidate failed.', { title: 'Consolidate failed' });
      });
    });
  }
  // ---- notes-from-earlier-meetings (per selected ECN) -------------------
  function loadNoteHistory(ecn) {
    if (!ecn || S.noteHistory[ecn] !== undefined || S._noteHistEcn === ecn) return;   // cached or in-flight
    S._noteHistEcn = ecn;
    api('GET', '/api/meeting/notes-history?ecn=' + encodeURIComponent(ecn)).then(function (r) {
      S.noteHistory[ecn] = (r && r.notes) || [];
      S._noteHistEcn = null;
      if (isActive() && S.view === 'meeting') render();
    });
  }
  function startTimer() {
    clearInterval(S._timer);
    S._timer = setInterval(function () {
      S.elapsed++;
      // Update only the timer span — never full-render on the tick, or the
      // detail-pane capture inputs would lose focus mid-keystroke.
      var el = root() && root().querySelector('[data-mm-timer]');
      if (el) el.textContent = Math.floor(S.elapsed / 60) + ':' + ('0' + (S.elapsed % 60)).slice(-2);
    }, 1000);
  }

  function saveDetail() {
    if (!S.sessionId) { startSession(); return; }
    var sel = itemById(S.selectedId);
    var uat = (document.getElementById('mm-detail-uat') || {}).value || '';
    var golive = (document.getElementById('mm-detail-golive') || {}).value || '';
    var log = ((document.getElementById('mm-detail-log') || {}).value || '').trim();
    var ownerOut = !isUnassigned(sel.owner) && !inRoom(sel.ownerLoginId);
    var jobs = [];
    if (uat && isoOf(uat) !== isoOf(sel.uat)) jobs.push({ field: 'TARGET_UAT', text: 'Target UAT → ' + fmtMD(uat), oldValue: isoOf(sel.uat), newValue: isoOf(uat) });
    if (golive && isoOf(golive) !== isoOf(sel.goLive)) jobs.push({ field: 'TARGET_GOLIVE', text: 'Target Go-Live → ' + fmtMD(golive), oldValue: isoOf(sel.goLive), newValue: isoOf(golive) });
    if (log) jobs.push({ field: 'IT_LOG_NOTE', text: log, oldValue: '', newValue: log });
    if (!jobs.length) { window.appAlert('No changes to capture.', { title: 'Nothing changed' }); return; }
    // Capture only — these are written to Agile at Wrap up & send, not now.
    var chain = Promise.resolve();
    jobs.forEach(function (j) {
      chain = chain.then(function () {
        return api('POST', '/api/meeting/sessions/' + S.sessionId + '/bubbles', {
          ecn: sel.id, ecnTitle: sel.title, kind: 'AGILE', text: j.text, field: j.field, oldValue: j.oldValue, newValue: j.newValue,
          ownerAd: sel.ownerLoginId, ownerName: sel.owner, time: clock(),
        }).then(function (b) {
          if (b && b.id) {
            S.bubbles.push(b);
            if (j.field === 'TARGET_UAT') sel.uat = j.newValue;
            if (j.field === 'TARGET_GOLIVE') sel.goLive = j.newValue;
          }
        });
      });
    });
    chain.then(function () { sel.badges = computeBadges(sel); render(); });
  }
  function addNote() {
    if (!S.sessionId) { startSession(); return; }
    var sel = itemById(S.selectedId);
    var note = ((document.getElementById('mm-detail-note') || {}).value || '').trim();
    if (!note) { window.appAlert('Type a note first.', { title: 'Empty note' }); return; }
    var asg = (document.getElementById('mm-detail-assignee') || {}).value || '';
    var aAd = '', aName = '';
    if (asg) { var parts = asg.split('|'); aAd = parts[0]; aName = parts[1] || ''; }
    api('POST', '/api/meeting/sessions/' + S.sessionId + '/bubbles', {
      ecn: sel.id, ecnTitle: sel.title, kind: 'NOTE', text: note, assigneeAd: aAd, assigneeName: aName,
      ownerAd: sel.ownerLoginId, ownerName: sel.owner, time: clock(),
    }).then(function (b) { if (b && b.id) { S.bubbles.push(b); render(); } });
  }
  function gradeNow() {
    S.drawer = 'grade'; S.grade = 'refreshing'; S.progress = 0; S.refreshed = 0;
    gradeOpenAux();   // fetch model catalog + durable history + last-grade while refreshing
    render();
    clearInterval(S._grade);
    S._grade = setInterval(function () {
      S.progress = Math.min(95, S.progress + 9);
      S.refreshed = Math.round(S.rows.length * S.progress / 100);
      // Update only the progress bar + count — full re-render every tick made
      // the whole pane flash. If the drawer markup isn't present, stop.
      var bar = root() && root().querySelector('#mm-grade-bar');
      var num = root() && root().querySelector('#mm-grade-read');
      if (bar) bar.style.width = S.progress + '%';
      if (num) num.textContent = S.refreshed;
    }, 90);
    api('POST', '/api/it-enhancements/refresh').then(function (resp) {
      clearInterval(S._grade);
      if (resp && resp.rows) { S.rows = resp.rows; buildItems(); }
      S.progress = 100; S.refreshed = S.rows.length;
      applyPolicies(function () {
        S.gradeData = computeGrade(); S.grade = 'done'; seedGradeChat();
        if (window.MeetingMode._onRefresh) window.MeetingMode._onRefresh(resp);
        render();
      });
    }).catch(function () { clearInterval(S._grade); S.gradeData = computeGrade(); S.grade = 'done'; seedGradeChat(); render(); });
  }
  function sendRecap() { if (S.sessionId) doSend(); }
  function doSend() {
    var btn = document.getElementById('mm-send-btn');
    if (btn) { btn.disabled = true; btn.style.opacity = '0.7'; btn.textContent = 'Saving to Agile & sending…'; }
    function restoreBtn() { if (btn) { btn.disabled = false; btn.style.opacity = '1'; btn.textContent = 'Send recap →'; } }
    api('POST', '/api/meeting/sessions/' + S.sessionId + '/send').then(function (resp) {
      // The captured Agile writes happen on the server during /send. If no
      // per-user Agile password is cached, sign in and retry — the session
      // stays live, nothing is lost.
      if (resp && resp.needsAgileSignin) {
        promptAgileSignin().then(function (ok) { if (ok) doSend(); else restoreBtn(); });
        return;
      }
      if (resp && resp.record) {
        clearInterval(S._timer);
        // Persist the grade at review-end as the baseline for the next review's trend pill.
        try { var gg = computeGrade(); api('POST', '/api/meeting/grade/last-grade', { letter: gg.letter, score: gg.score }); } catch (e) {}
        S.recordData = resp.record; S.view = 'record'; S.selectedRecord = resp.record.id;
        S.sessionId = null; S.bubbles = []; S.phase = 'pre'; S.agendaStatus = {}; S.recordsLoaded = false;
        var msgs = [];
        if (resp.writeFailures) msgs.push(resp.writeFailures + ' Agile update(s) failed to save: ' + ((resp.writeErrors || []).join('; ')) + '. They are flagged "needs retry" in the record and email.');
        if (resp.mailErrors && resp.mailErrors.length) msgs.push('Some emails did not send: ' + resp.mailErrors.join('; '));
        render();
        if (window.MeetingMode._syncPill) window.MeetingMode._syncPill('records');
        if (msgs.length) window.appAlert(msgs.join('\n\n'), { title: 'Filed with warnings' });
      } else {
        window.appAlert((resp && resp.error) || 'Send failed — the session is still live.', { title: 'Send failed' });
        restoreBtn();
      }
    });
  }
  function openRecord(id) {
    api('GET', '/api/meeting/records/' + encodeURIComponent(id)).then(function (r) {
      if (r && r.id) { S.recordData = r; S.view = 'record'; S.selectedRecord = id; render(); }
    });
  }
  function toggleAction(id, status) {
    api('PATCH', '/api/meeting/action-items/' + encodeURIComponent(id), { status: status }).then(function (a) {
      if (a && a.id && S.recordData) {
        S.recordData.actionItems = S.recordData.actionItems.map(function (x) { return x.id === a.id ? a : x; });
        S.recordData.counts.actionsOpen = S.recordData.actionItems.filter(function (x) { return x.status !== 'DONE'; }).length;
        render();
      }
    });
  }

  // ---- export ----------------------------------------------------------
  window.MeetingMode = {
    setData: setData, show: show, hide: hide, isActive: isActive,
    // inline handlers
    _toggleAddPeople: H._toggleAddPeople, _togglePerson: H._togglePerson, _filterPeople: H._filterPeople,
    _commitPeople: H._commitPeople, _removePerson: H._removePerson,
    _select: H._select, _pick: H._pick, _startNow: H._startNow, _startTop: H._startTop,
    _gradeNow: H._gradeNow, _buildAgenda: H._buildAgenda, _closeDrawer: H._closeDrawer, _setAxis: H._setAxis,
    _agendaSet: H._agendaSet, _saveDetail: H._saveDetail, _addNote: H._addNote, _wrapUp: H._wrapUp,
    _cancelWrap: H._cancelWrap, _send: H._send, _mysave: H._mysave, _openRecord: H._openRecord,
    _backToRecords: H._backToRecords, _toggleAction: H._toggleAction,
    _setFilter: H._setFilter, _gradeDriver: H._gradeDriver, _railSearch: H._railSearch, _railResize: H._railResize, _toggleMax: H._toggleMax,
    _gradeTab: H._gradeTab, _gradeExplainer: H._gradeExplainer, _gradeModel: H._gradeModel,
    _gradeOpenDispute: H._gradeOpenDispute, _gradeCancelDispute: H._gradeCancelDispute, _gradeDraft: H._gradeDraft,
    _gradeFile: H._gradeFile, _gradeClearFile: H._gradeClearFile, _gradeSubmitDispute: H._gradeSubmitDispute,
    _gradeRestore: H._gradeRestore, _gradeChatSend: H._gradeChatSend, _gradeRevertRule: H._gradeRevertRule,
    _toggleReqDD: H._toggleReqDD, _togglePrioDD: H._togglePrioDD, _toggleReq: H._toggleReq, _togglePrio: H._togglePrio,
    _toggleStatusDD: H._toggleStatusDD, _toggleStatus: H._toggleStatus, _cancelMeeting: H._cancelMeeting, _undo: H._undo,
    _deleteRecord: H._deleteRecord, _toggleRecSel: H._toggleRecSel, _clearRecSel: H._clearRecSel, _consolidate: H._consolidate,
    // optional hooks set by it-enhancements.js
    _onRefresh: null, _syncPill: null,
  };
})();
