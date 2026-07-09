// =====================================================================
//  feedback-queue.js — admin sub-tab inside User Permissions, plus the
//  "My feedback" dropdown for non-admins. Talks to /api/feedback/queue.
// =====================================================================

(function () {
  'use strict';

  let _all = [];
  let _admin = false;
  let _filter = 'open';
  let _activePtId = null;

  // -------------------------------------------------------------------
  // Sub-tab toggle inside the User Permissions panel
  // -------------------------------------------------------------------
  window.permsSwitchSub = function (which) {
    const usersDiv = document.getElementById('permsSubsectionUsers');
    const fbDiv = document.getElementById('permsSubsectionFeedback');
    const tabUsers = document.getElementById('permsSubTabUsers');
    const tabFb = document.getElementById('permsSubTabFeedback');
    if (!usersDiv || !fbDiv) return;

    if (which === 'feedback') {
      usersDiv.style.display = 'none';
      fbDiv.style.display = '';
      tabUsers.style.borderBottomColor = 'transparent';
      tabUsers.style.color = '#6B7280';
      tabUsers.style.fontWeight = '500';
      tabFb.style.borderBottomColor = '#4a6fa5';
      tabFb.style.color = '#0F1720';
      tabFb.style.fontWeight = '600';
      feedbackQueueLoad();
    } else {
      usersDiv.style.display = '';
      fbDiv.style.display = 'none';
      tabUsers.style.borderBottomColor = '#4a6fa5';
      tabUsers.style.color = '#0F1720';
      tabUsers.style.fontWeight = '600';
      tabFb.style.borderBottomColor = 'transparent';
      tabFb.style.color = '#6B7280';
      tabFb.style.fontWeight = '500';
    }
  };

  // -------------------------------------------------------------------
  // Admin queue UI
  // -------------------------------------------------------------------
  window.feedbackQueueLoad = function () {
    const list = document.getElementById('feedbackQueueList');
    if (list) list.innerHTML = '<div style="text-align:center; color:#6B7280; padding:30px;">Loading...</div>';
    fetch('/api/feedback/queue', { credentials: 'same-origin' })
      .then(r => r.json())
      .then(data => {
        _all = data.items || [];
        window._feedbackQueueItems = _all;
        _admin = !!data.admin;
        feedbackQueueRender();
        updateFeedbackTabBadge();
      })
      .catch(err => {
        if (list) list.innerHTML = '<div style="color:#B8342B; padding:14px;">Failed to load: ' + err + '</div>';
      });
  };

  window.feedbackQueueSetFilter = function (which) {
    _filter = which;
    const buttons = [
      { id: 'fbFilterOpen', val: 'open' },
      { id: 'fbFilterInProgress', val: 'in_progress' },
      { id: 'fbFilterDismissed', val: 'dismissed' },
      { id: 'fbFilterDone', val: 'done' }
    ];
    buttons.forEach(b => {
      const btn = document.getElementById(b.id);
      if (!btn) return;
      const isActive = b.val === which;
      btn.style.background = isActive ? '#4a6fa5' : '#FAFAF7';
      btn.style.color = isActive ? '#fff' : '#6B7280';
      btn.style.border = isActive ? 'none' : '1px solid #E8E6DF';
      btn.style.fontWeight = isActive ? '600' : '500';
    });
    feedbackQueueRender();
  };

  function feedbackQueueRender() {
    const list = document.getElementById('feedbackQueueList');
    if (!list) return;

    const counts = { open: 0, in_progress: 0, done: 0, dismissed: 0 };
    // New triage/approval statuses count toward Open so the chip total
    // matches "awaiting human / agent action".
    _all.forEach(it => {
      const bucket = (it.status === 'triaging' || it.status === 'awaiting_approval')
        ? 'open' : it.status;
      counts[bucket] = (counts[bucket] || 0) + 1;
    });
    setText('fbFilterOpenCount', counts.open ? '· ' + counts.open : '');
    setText('fbFilterInProgressCount', counts.in_progress ? '· ' + counts.in_progress : '');
    setText('fbFilterDismissedCount', counts.dismissed ? '· ' + counts.dismissed : '');
    setText('fbFilterDoneCount', counts.done ? '· ' + counts.done : '');

    const filtered = _all.filter(it => {
      if (_filter === 'open') {
        return it.status === 'open' || it.status === 'triaging' || it.status === 'awaiting_approval';
      }
      return it.status === _filter;
    });
    if (filtered.length === 0) {
      const labelMap = { open: 'open', in_progress: 'in-progress', done: 'done', dismissed: 'dismissed' };
      list.innerHTML = '<div style="text-align:center; color:#6B7280; padding:40px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:8px;">' +
        'No <strong>' + (labelMap[_filter] || _filter) + '</strong> items.' +
        '</div>';
      return;
    }

    list.innerHTML = filtered.map(it => renderCard(it)).join('');
  }

  function renderCard(it) {
    const tag = categoryTag(it.category);
    const submittedTxt = formatDate(it.submittedAt);
    const titleTxt = (it.text || '').length > 100 ? (it.text || '').substring(0, 100) + '…' : (it.text || '');
    const fromLabel = (it.reporterDisplayName || it.reporterUsername || 'unknown') +
                      (it.reporterUsername ? ' (' + it.reporterUsername + ')' : '');
    const emailHtml = it.reporterEmail
      ? '<a href="mailto:' + esc(it.reporterEmail) + '" style="color:#4a6fa5;">' + esc(it.reporterEmail) + '</a>'
      : '<span style="color:#C7801B;">no email on file</span>';

    // AI triage pill (spec 2026-05-13) — shows the AI's verdict + clickable for have_questions.
    let aiTagHtml = '';
    if (it.aiTag === 'easy') {
      aiTagHtml = '<div style="margin:-4px 0 10px 0;"><span style="display:inline-block; padding:4px 10px; background:#e8f5e9; color:#1F8A4C; border:1px solid #c8e6c9; border-radius:12px; font-size:11px; font-weight:600; margin-right:8px;">\u{1F4A1} EASY' +
        (it.aiEffortHours ? ' · ~' + formatEffort(it.aiEffortHours) : '') + '</span>' +
        (it.aiAssessment ? '<span style="font-size:12px; color:#0F1720;">' + esc(it.aiAssessment) + '</span>' : '') + '</div>';
    } else if (it.aiTag === 'hard') {
      aiTagHtml = '<div style="margin:-4px 0 10px 0;"><span style="display:inline-block; padding:4px 10px; background:#fff3cd; color:#856404; border:1px solid #ffe0a3; border-radius:12px; font-size:11px; font-weight:600; margin-right:8px;">\u26A0\uFE0F HARD' +
        (it.aiEffortHours ? ' · ~' + formatEffort(it.aiEffortHours) : '') + '</span>' +
        (it.aiAssessment ? '<span style="font-size:12px; color:#0F1720;">' + esc(it.aiAssessment) + '</span>' : '') + '</div>';
    } else if (it.aiTag === 'have_questions') {
      const qCount = Array.isArray(it.aiQuestions) ? it.aiQuestions.length : 0;
      const aCount = Array.isArray(it.aiAnswers) ? it.aiAnswers.filter(a => a && a.trim()).length : 0;
      const unanswered = qCount - aCount;
      aiTagHtml = '<div style="margin:-4px 0 10px 0;"><button onclick="feedbackQueueShowQuestions(\'' + it.ptId + '\')" style="display:inline-block; padding:4px 10px; background:#e8f0fe; color:#1a3a5c; border:1px solid #cfe0ff; border-radius:12px; font-size:11px; font-weight:600; margin-right:8px; cursor:pointer;">\u2753 HAVE QUESTIONS · ' + unanswered + ' unanswered</button>' +
        (it.aiAssessment ? '<span style="font-size:12px; color:#0F1720;">' + esc(it.aiAssessment) + '</span>' : '') + '</div>';
    }

    // Build the "📎 Attach" + "Approve" buttons used by several statuses below.
    const attachBtn = '<button onclick="feedbackQueueAttach(\'' + it.ptId + '\')" style="background:none; color:#4a6fa5; border:1px solid #cfe0ff; padding:6px 12px; border-radius:6px; font-size:12px; font-weight:500; cursor:pointer; margin-right:6px;">\uD83D\uDCCE Attach</button>';
    const approveBtn = '<button onclick="feedbackQueueApprove(\'' + it.ptId + '\')" style="background:#0F1720; color:#fff; border:none; padding:6px 14px; border-radius:6px; font-size:12px; font-weight:600; cursor:pointer; margin-right:6px;">\u2713 Approve &amp; start</button>';
    // have_questions tickets shouldn't be approved until every question has a non-empty answer —
    // otherwise admin green-lights work the AI has flagged as ambiguous and we ship the wrong thing.
    const qCountForGate = Array.isArray(it.aiQuestions) ? it.aiQuestions.length : 0;
    const aCountForGate = Array.isArray(it.aiAnswers) ? it.aiAnswers.filter(a => a && a.trim()).length : 0;
    const hasUnansweredQuestions = it.aiTag === 'have_questions' && qCountForGate > aCountForGate;
    const approveBtnDisabled = '<button disabled title="Answer the AI\u2019s questions first" style="background:#E8E6DF; color:#9CA3AF; border:none; padding:6px 14px; border-radius:6px; font-size:12px; font-weight:600; cursor:not-allowed; margin-right:6px;">\u2713 Approve &amp; start</button>';
    const dismissBtn = '<button onclick="feedbackQueueDismissOpen(\'' + it.ptId + '\')" style="background:none; color:#B8342B; border:1px solid #f5c6c0; padding:6px 12px; border-radius:6px; font-size:12px; font-weight:500; cursor:pointer;">Dismiss</button>';
    const markDoneBtn = '<button onclick="feedbackQueueDoneOpen(\'' + it.ptId + '\')" style="background:#1F8A4C; color:#fff; border:none; padding:6px 14px; border-radius:6px; font-size:12px; font-weight:600; cursor:pointer; margin-right:6px;">\u2713 Mark done</button>';
    const startBtn = '<button onclick="feedbackQueueStart(\'' + it.ptId + '\')" style="background:none; color:#C7801B; border:1px solid #f0d9a5; padding:6px 12px; border-radius:6px; font-size:12px; font-weight:600; cursor:pointer; margin-right:6px;">\u25B6 Start</button>';

    let actionsHtml = '';
    if (it.status === 'open') {
      actionsHtml = startBtn + markDoneBtn + attachBtn + dismissBtn;
    } else if (it.status === 'triaging') {
      actionsHtml = '<span style="display:inline-block; padding:4px 10px; background:#e8f0fe; color:#1a3a5c; border-radius:12px; font-size:11px; font-weight:600; margin-right:8px;">\u2728 AI triaging\u2026</span>' + attachBtn + dismissBtn;
    } else if (it.status === 'awaiting_approval') {
      actionsHtml = (hasUnansweredQuestions ? approveBtnDisabled : approveBtn) + attachBtn + dismissBtn;
    } else if (it.status === 'in_progress') {
      actionsHtml =
        '<span style="display:inline-block; padding:4px 10px; background:#fff3cd; color:#856404; border-radius:12px; font-size:11px; font-weight:600; margin-right:8px;">\u25B6 In progress</span>' +
        (it.agentBuildJarSha
          ? '<span title="' + esc(it.agentBuildJarSha) + '" style="display:inline-block; padding:4px 10px; background:#e8f5e9; color:#1F8A4C; border-radius:12px; font-size:11px; font-weight:600; margin-right:8px;" >\uD83D\uDD27 Built ' + esc(it.agentBuildJarSha.substring(0,12)) + '\u2026</span>'
          : '') +
        markDoneBtn + attachBtn + dismissBtn;
    } else if (it.status === 'done') {
      const autoBadge = (it.resolvedBy === 'agent')
        ? '<span style="display:inline-block; padding:4px 10px; background:#e8f0fe; color:#1a3a5c; border-radius:12px; font-size:11px; font-weight:600; margin-left:6px;">\u2728 auto-resolved by deploy</span>' : '';
      // Estimated vs actual effort pill — only shown when we have both numbers.
      // Color encodes whether we hit (green), missed but were close (amber),
      // or blew past (red) the AI's estimate.
      let effortBadge = '';
      if (typeof it.aiEffortHours === 'number' && typeof it.actualHours === 'number') {
        const est = it.aiEffortHours;
        const act = it.actualHours;
        let bg = '#e8f5e9', fg = '#1F8A4C';
        if (act > est * 1.5) { bg = '#fdeaea'; fg = '#B8342B'; }
        else if (act > est)  { bg = '#fff8e1'; fg = '#856404'; }
        effortBadge = '<span title="Estimated vs actual agent time (pickup \u2192 build)" style="display:inline-block; padding:4px 10px; background:' + bg + '; color:' + fg + '; border-radius:12px; font-size:11px; font-weight:600; margin-left:6px;">\u23F1 Est ' + formatEffort(est) + ' \u00b7 Actual ' + formatEffort(act) + '</span>';
      }
      actionsHtml =
        '<span style="display:inline-block; padding:4px 10px; background:#e8f5e9; color:#1F8A4C; border-radius:12px; font-size:11px; font-weight:600;">\u2713 Done</span>' + autoBadge + effortBadge + ' ' +
        '<button onclick="feedbackQueueReopen(\'' + it.ptId + '\')" style="margin-left:6px; background:none; color:#6B7280; border:1px solid #E8E6DF; padding:4px 10px; border-radius:6px; font-size:11px; cursor:pointer;">Reopen</button>';
    } else if (it.status === 'dismissed') {
      actionsHtml =
        '<span style="display:inline-block; padding:4px 10px; background:#fdeaea; color:#B8342B; border-radius:12px; font-size:11px; font-weight:600;">Dismissed</span> ' +
        '<button onclick="feedbackQueueReopen(\'' + it.ptId + '\')" style="margin-left:6px; background:none; color:#6B7280; border:1px solid #E8E6DF; padding:4px 10px; border-radius:6px; font-size:11px; cursor:pointer;">Reopen</button>';
    }

    let resolvedRow = '';
    if (it.resolvedAt) {
      const resolvedTxt = formatDate(it.resolvedAt);
      const by = it.resolvedByDisplay || it.resolvedBy || 'unknown';
      const label =
        it.status === 'done' ? 'Marked done' :
        it.status === 'dismissed' ? 'Dismissed' :
        it.status === 'in_progress' ? 'Started' : 'Updated';
      resolvedRow = '<div style="font-size:11px; color:#6B7280; margin-top:8px;">' + label + ' by <strong>' + esc(by) + '</strong> · ' + resolvedTxt + '</div>';
    }

    let noteRow = '';
    if (it.adminNote) {
      noteRow = '<div style="margin-top:10px; background:#e8f5e9; border-left:4px solid #1F8A4C; border-radius:0 6px 6px 0; padding:8px 12px; font-size:12px;"><strong>Note from admin:</strong> ' + esc(it.adminNote) + '</div>';
    }
    if (it.dismissReason) {
      noteRow += '<div style="margin-top:10px; background:#fdeaea; border-left:4px solid #B8342B; border-radius:0 6px 6px 0; padding:8px 12px; font-size:12px;"><strong>Dismiss reason:</strong> ' + esc(it.dismissReason) + '</div>';
    }

    // PT-38: show one chip per attachment. Multi-attach items have
    // attachmentFilenames (array); legacy single-attach items still have just
    // attachmentFilename (string). Render either shape.
    let attachmentRow = '';
    const ptUrl = '/api/feedback/queue/' + encodeURIComponent(it.ptId);
    const chip = (label, href) => '<a href="' + href + '" target="_blank" style="display:inline-block; padding:4px 10px; background:#fff3cd; color:#856404; border-radius:12px; font-size:11px; text-decoration:none; margin-right:4px; margin-top:4px;">&#128206; ' + esc(label) + '</a>';
    if (Array.isArray(it.attachmentFilenames) && it.attachmentFilenames.length > 0) {
      const chips = it.attachmentFilenames.map((fn, i) => chip(fn, ptUrl + '/attachment' + (i === 0 ? '' : '?index=' + i))).join('');
      attachmentRow = '<div style="margin-top:8px;">' + chips + '</div>';
    } else if (it.attachmentFilename) {
      attachmentRow = '<div style="margin-top:8px;">' + chip(it.attachmentFilename, ptUrl + '/attachment') + '</div>';
    }

    return (
      '<div style="background:#fff; border:1px solid #E8E6DF; border-radius:8px; padding:18px 20px; margin-bottom:12px;">' +
        '<div style="font-size:11px; letter-spacing:0.08em; text-transform:uppercase; color:#6B7280; margin-bottom:6px;">' +
          tag + ' · ' + submittedTxt + ' · <span style="font-family:\'IBM Plex Mono\',Consolas,monospace;">' + esc(it.ptId) + '</span>' +
        '</div>' +
        '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:18px; font-weight:600; color:#0F1720; margin-bottom:10px; line-height:1.35;">' + esc(titleTxt) + '</div>' +
        aiTagHtml +
        '<div style="background:#FAFAF7; border-radius:6px; padding:10px 14px; font-size:13px; color:#0F1720; margin-bottom:12px; white-space:pre-wrap;">' + esc(it.text || '') + '</div>' +
        '<table style="width:100%; font-size:12px;">' +
          '<tr><td style="width:90px; color:#6B7280; padding:3px 0;">From</td><td style="padding:3px 0; color:#4a6fa5; font-weight:600;">' + esc(fromLabel) + '</td></tr>' +
          '<tr><td style="color:#6B7280; padding:3px 0;">Email</td><td style="padding:3px 0;">' + emailHtml + '</td></tr>' +
          '<tr><td style="color:#6B7280; padding:3px 0;">Category</td><td style="padding:3px 0;">' + esc(it.category || '') + '</td></tr>' +
        '</table>' +
        attachmentRow +
        noteRow +
        resolvedRow +
        '<div style="display:flex; justify-content:flex-end; margin-top:14px; padding-top:12px; border-top:1px solid #E8E6DF;">' + actionsHtml + '</div>' +
      '</div>'
    );
  }

  function formatEffort(hours) {
    if (hours == null) return '';
    if (hours < 1) return Math.round(hours * 60) + ' min';
    if (hours < 8) return hours + ' hr';
    return Math.round(hours / 8) + 'd';
  }

  window.feedbackQueueApprove = function (ptId) {
    appConfirm('Approve PT-' + ptId.replace(/^PT-/,'') + ' and start dev work? The agent will pick this up on its next poll cycle.', { okText: 'Approve & start' }).then(function (ok) {
      if (!ok) return;
      fetch('/api/feedback/queue/' + encodeURIComponent(ptId) + '/approve', {
        method: 'POST', credentials: 'same-origin'
      }).then(r => r.json()).then(d => {
        if (d.error) { appAlert(d.error); return; }
        feedbackQueueLoad();
      }).catch(e => appAlert('Approve failed: ' + e.message));
    });
  };

  window.feedbackQueueAttach = function (ptId) {
    // Lightweight inline file picker — no modal needed for a single attach.
    var input = document.createElement('input');
    input.type = 'file';
    input.style.display = 'none';
    input.onchange = function () {
      if (!input.files || !input.files[0]) return;
      var fd = new FormData();
      fd.append('file', input.files[0]);
      fetch('/api/feedback/queue/' + encodeURIComponent(ptId) + '/attach',
            { method: 'POST', credentials: 'same-origin', body: fd })
        .then(r => r.json())
        .then(d => {
          if (d.error) { appAlert(d.error); return; }
          feedbackQueueLoad();
        })
        .catch(e => appAlert('Attach failed: ' + e.message));
    };
    document.body.appendChild(input);
    input.click();
    setTimeout(function () { document.body.removeChild(input); }, 60000);
  };

  window.feedbackQueueShowQuestions = function (ptId) {
    var items = window._feedbackQueueItems || [];
    var it = items.find(function (x) { return x.ptId === ptId; });
    if (!it || !Array.isArray(it.aiQuestions) || !it.aiQuestions.length) {
      appAlert('No questions on file.');
      return;
    }
    var existing = document.getElementById('fqQuestionsModal');
    if (existing) existing.remove();
    var html =
      '<div id="fqQuestionsModal" style="position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:1200; display:flex; align-items:center; justify-content:center;">' +
        '<div style="background:#fff; width:96%; max-width:680px; border-radius:8px; border:1px solid #E8E6DF;">' +
          '<div style="padding:16px 20px; border-bottom:1px solid #E8E6DF;">' +
            '<div style="font-family:\'IBM Plex Serif\',Georgia,serif; font-size:18px; font-weight:600; color:#0F1720;">Claude has ' + it.aiQuestions.length + ' question(s) about ' + esc(ptId) + '</div>' +
            '<div style="font-size:12px; color:#6B7280; margin-top:4px;">Answers will go back to the AI; status will re-triage on the next poll cycle.</div>' +
          '</div>' +
          '<div id="fqQuestionsBody" style="padding:14px 20px; max-height:60vh; overflow:auto;">';
    it.aiQuestions.forEach(function (q, idx) {
      var existingAnswer = (Array.isArray(it.aiAnswers) && it.aiAnswers[idx]) ? it.aiAnswers[idx] : '';
      html +=
        '<div style="margin-bottom:14px;">' +
          '<div style="font-size:13px; color:#0F1720; margin-bottom:6px;"><strong>' + (idx + 1) + '.</strong> ' + esc(q) + '</div>' +
          '<textarea id="fqAns' + idx + '" rows="2" style="width:100%; padding:8px 10px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px; font-family:inherit;" placeholder="Your answer\u2026">' + esc(existingAnswer) + '</textarea>' +
        '</div>';
    });
    html +=
            // Multi-file attach (optional). Anything picked here uploads along
            // with the answers in one multipart POST — no separate Attach step.
            '<div style="margin-top:14px; padding-top:14px; border-top:1px solid #E8E6DF;">' +
              '<div style="font-size:11px; color:#6B7280; letter-spacing:0.5px; text-transform:uppercase; margin-bottom:6px;">\uD83D\uDCCE Attach files (optional, multiple)</div>' +
              '<input type="file" id="fqAnsFiles" multiple onchange="feedbackQueueRenderFileChips()" style="font-size:12px;">' +
              '<div id="fqAnsFileChips" style="margin-top:6px; display:flex; flex-wrap:wrap; gap:4px;"></div>' +
            '</div>' +
          '</div>' +
          '<div style="display:flex; gap:8px; padding:14px 20px; border-top:1px solid #E8E6DF; justify-content:flex-end;">' +
            '<button onclick="document.getElementById(\'fqQuestionsModal\').remove()" style="background:none; border:1px solid #E8E6DF; color:#6B7280; padding:8px 14px; border-radius:6px; font-size:13px; cursor:pointer;">Cancel</button>' +
            '<button onclick="feedbackQueueSendAnswers(\'' + ptId + '\')" style="background:#0F1720; color:#fff; border:none; padding:8px 16px; border-radius:6px; font-size:13px; font-weight:600; cursor:pointer;">Send answers</button>' +
          '</div>' +
        '</div>' +
      '</div>';
    var wrap = document.createElement('div');
    wrap.innerHTML = html;
    document.body.appendChild(wrap.firstChild);
  };

  window.feedbackQueueRenderFileChips = function () {
    var input = document.getElementById('fqAnsFiles');
    var chips = document.getElementById('fqAnsFileChips');
    if (!input || !chips) return;
    chips.innerHTML = '';
    if (!input.files || !input.files.length) return;
    Array.from(input.files).forEach(function (f) {
      var c = document.createElement('span');
      c.style.cssText = 'display:inline-block; padding:3px 10px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:12px; font-size:11px; color:#0F1720;';
      var sz = f.size < 1024 ? f.size + ' B' :
               f.size < 1024 * 1024 ? (f.size / 1024).toFixed(1) + ' KB' :
               (f.size / 1024 / 1024).toFixed(1) + ' MB';
      c.textContent = f.name + ' (' + sz + ')';
      chips.appendChild(c);
    });
  };

  window.feedbackQueueSendAnswers = function (ptId) {
    var items = window._feedbackQueueItems || [];
    var it = items.find(function (x) { return x.ptId === ptId; });
    if (!it) return;
    var answers = it.aiQuestions.map(function (_, idx) {
      var el = document.getElementById('fqAns' + idx);
      return el ? el.value.trim() : '';
    });
    // Always use multipart so the file picker just works whether or not the
    // user attached anything. Backend accepts both shapes.
    var fd = new FormData();
    fd.append('answers', JSON.stringify(answers));
    var input = document.getElementById('fqAnsFiles');
    if (input && input.files) {
      Array.from(input.files).forEach(function (f) { fd.append('files', f); });
    }
    fetch('/api/feedback/queue/' + encodeURIComponent(ptId) + '/answer', {
      method: 'POST', credentials: 'same-origin',
      body: fd
    }).then(r => r.json()).then(d => {
      if (d.error) { appAlert(d.error); return; }
      var m = document.getElementById('fqQuestionsModal');
      if (m) m.remove();
      feedbackQueueLoad();
    }).catch(e => appAlert('Send answers failed: ' + e.message));
  };

  function categoryTag(cat) {
    const c = (cat || '').toLowerCase();
    if (c.indexOf('feature') >= 0) return 'FEATURE REQUEST';
    if (c.indexOf('bug') >= 0) return 'BUG';
    if (c.indexOf('improve') >= 0) return 'IMPROVEMENT';
    if (c.indexOf('question') >= 0) return 'QUESTION';
    return (cat || 'GENERAL').toUpperCase();
  }

  // -------------------------------------------------------------------
  // Done modal
  // -------------------------------------------------------------------
  window.feedbackQueueDoneOpen = function (ptId) {
    _activePtId = ptId;
    const it = _all.find(x => x.ptId === ptId);
    document.getElementById('fbDoneSubtitle').textContent = ptId + ' · ' + (it && it.reporterDisplayName ? it.reporterDisplayName : '');
    document.getElementById('fbDoneNote').value = '';
    document.getElementById('fbDoneNoEmail').style.display = (it && it.reporterEmail) ? 'none' : 'block';
    // Reset the confirm button — a prior successful send leaves it in the
    // "Sending…" disabled state because we close the modal before resetting.
    const btn = document.querySelector('#fbDoneModal button[onclick="feedbackQueueDoneConfirm()"]');
    if (btn) { btn.disabled = false; btn.innerHTML = '&#10003; Mark done &amp; send'; }
    const m = document.getElementById('fbDoneModal');
    m.style.display = 'flex';
  };

  window.feedbackQueueDoneCancel = function () {
    document.getElementById('fbDoneModal').style.display = 'none';
    _activePtId = null;
  };

  window.feedbackQueueDoneConfirm = function () {
    if (!_activePtId) return;
    const note = document.getElementById('fbDoneNote').value;
    const ptId = _activePtId;
    const btn = document.querySelector('#fbDoneModal button[onclick="feedbackQueueDoneConfirm()"]');
    if (btn) { btn.disabled = true; btn.textContent = 'Sending…'; }
    fetch('/api/feedback/queue/' + encodeURIComponent(ptId) + '/done', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ adminNote: note })
    })
      .then(r => r.json().then(j => ({ ok: r.ok, body: j })))
      .then(({ ok, body }) => {
        if (!ok) throw new Error((body && body.error) || 'Failed');
        feedbackQueueDoneCancel();
        feedbackQueueLoad();
      })
      .catch(err => {
        appAlert('Failed: ' + err.message);
        if (btn) { btn.disabled = false; btn.textContent = '✓ Mark done & send'; }
      });
  };

  // -------------------------------------------------------------------
  // Dismiss modal
  // -------------------------------------------------------------------
  window.feedbackQueueDismissOpen = function (ptId) {
    _activePtId = ptId;
    document.getElementById('fbDismissSubtitle').textContent = ptId;
    document.getElementById('fbDismissReason').value = '';
    document.getElementById('fbDismissModal').style.display = 'flex';
  };

  window.feedbackQueueDismissCancel = function () {
    document.getElementById('fbDismissModal').style.display = 'none';
    _activePtId = null;
  };

  window.feedbackQueueDismissConfirm = function () {
    if (!_activePtId) return;
    const reason = document.getElementById('fbDismissReason').value;
    const ptId = _activePtId;
    fetch('/api/feedback/queue/' + encodeURIComponent(ptId) + '/dismiss', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: reason })
    })
      .then(r => r.json().then(j => ({ ok: r.ok, body: j })))
      .then(({ ok, body }) => {
        if (!ok) throw new Error((body && body.error) || 'Failed');
        feedbackQueueDismissCancel();
        feedbackQueueLoad();
      })
      .catch(err => appAlert('Failed: ' + err.message));
  };

  window.feedbackQueueStart = function (ptId) {
    fetch('/api/feedback/queue/' + encodeURIComponent(ptId) + '/start', {
      method: 'POST',
      credentials: 'same-origin'
    })
      .then(r => r.json().then(j => ({ ok: r.ok, body: j })))
      .then(({ ok, body }) => {
        if (!ok) throw new Error((body && body.error) || 'Failed');
        feedbackQueueLoad();
      })
      .catch(err => appAlert('Failed: ' + err.message));
  };

  window.feedbackQueueExport = function () {
    window.location = '/api/feedback/queue/export?status=' + encodeURIComponent(_filter);
  };

  window.feedbackQueueReopen = function (ptId) {
    appConfirm('Reopen ' + ptId + '?', { okText: 'Reopen' }).then(function (ok) {
      if (!ok) return;
      fetch('/api/feedback/queue/' + encodeURIComponent(ptId) + '/reopen', {
        method: 'POST',
        credentials: 'same-origin'
      })
        .then(r => r.json().then(j => ({ ok: r.ok, body: j })))
        .then(({ ok, body }) => {
          if (!ok) throw new Error((body && body.error) || 'Failed');
          feedbackQueueLoad();
        })
        .catch(err => appAlert('Failed: ' + err.message));
    });
  };

  // -------------------------------------------------------------------
  // "My feedback" dropdown modal — any logged-in user
  // -------------------------------------------------------------------
  window.myFeedbackOpen = function () {
    const dd = document.getElementById('tabPrefsDropdown');
    if (dd) dd.style.display = 'none';
    const m = document.getElementById('myFeedbackModal');
    if (!m) return;
    m.style.display = 'flex';
    const list = document.getElementById('myFeedbackList');
    list.innerHTML = '<div style="text-align:center; color:#6B7280; padding:30px;">Loading...</div>';
    fetch('/api/feedback/queue', { credentials: 'same-origin' })
      .then(r => r.json())
      .then(data => renderMyFeedback(data.items || []))
      .catch(err => { list.innerHTML = '<div style="color:#B8342B; padding:14px;">Failed to load: ' + err + '</div>'; });
  };

  window.myFeedbackClose = function () {
    const m = document.getElementById('myFeedbackModal');
    if (m) m.style.display = 'none';
  };

  function renderMyFeedback(items) {
    const list = document.getElementById('myFeedbackList');
    if (!list) return;
    if (items.length === 0) {
      list.innerHTML = '<div style="text-align:center; color:#6B7280; padding:30px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:8px;">' +
        'You have not submitted any feedback yet. Use the <strong>?</strong> help button at the bottom-left to send one.' +
        '</div>';
      return;
    }
    list.innerHTML = items.map(it => {
      const tag = categoryTag(it.category);
      const submitted = formatDate(it.submittedAt);
      const titleTxt = (it.text || '').length > 80 ? (it.text || '').substring(0, 80) + '…' : (it.text || '');
      let badge;
      if (it.status === 'done') {
        badge = '<span style="background:#e8f5e9; color:#1F8A4C; border-radius:12px; padding:2px 10px; font-size:11px; font-weight:600;">&#10003; Done</span>';
      } else if (it.status === 'dismissed') {
        badge = '<span style="background:#fdeaea; color:#B8342B; border-radius:12px; padding:2px 10px; font-size:11px; font-weight:600;">Dismissed</span>';
      } else if (it.status === 'in_progress') {
        const by = it.resolvedByDisplay || it.resolvedBy || 'someone';
        badge = '<span title="Being worked on by ' + esc(by) + '" style="background:#fff3cd; color:#856404; border-radius:12px; padding:2px 10px; font-size:11px; font-weight:600;">&#9654; In progress</span>';
      } else {
        badge = '<span style="background:#e8f0fe; color:#1a3a5c; border-radius:12px; padding:2px 10px; font-size:11px; font-weight:600;">Open</span>';
      }
      let extra = '';
      if (it.status === 'in_progress' && it.resolvedByDisplay) {
        extra = '<div style="margin-top:6px; background:#fff8e1; border-left:3px solid #C7801B; padding:6px 10px; font-size:12px; border-radius:0 4px 4px 0;"><strong>' + esc(it.resolvedByDisplay) + '</strong> is working on this.</div>';
      }
      if (it.adminNote) {
        extra += '<div style="margin-top:6px; background:#e8f5e9; border-left:3px solid #1F8A4C; padding:6px 10px; font-size:12px; border-radius:0 4px 4px 0;"><strong>Note:</strong> ' + esc(it.adminNote) + '</div>';
      }
      return (
        '<div style="border:1px solid #E8E6DF; border-radius:6px; padding:12px 14px; margin-bottom:10px;">' +
          '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">' +
            '<div style="font-size:10px; letter-spacing:0.08em; text-transform:uppercase; color:#6B7280;">' + tag + ' · ' + submitted + ' · <span style="font-family:\'IBM Plex Mono\',Consolas,monospace;">' + esc(it.ptId) + '</span></div>' +
            badge +
          '</div>' +
          '<div style="font-size:13px; color:#0F1720; line-height:1.4;">' + esc(titleTxt) + '</div>' +
          extra +
        '</div>'
      );
    }).join('');
  }

  // -------------------------------------------------------------------
  // Tab badge — count of open feedback for admins
  // -------------------------------------------------------------------
  function updateFeedbackTabBadge() {
    const badge = document.getElementById('permsSubTabFeedbackCount');
    if (!badge) return;
    // Count both open and in-progress as "needs attention" for the nav badge.
    const active = _all.filter(it => it.status === 'open' || it.status === 'in_progress').length;
    if (active > 0) {
      badge.style.display = 'inline-block';
      badge.textContent = String(active);
    } else {
      badge.style.display = 'none';
    }
  }

  // Light-touch global helper: when User Permissions tab is opened, ensure we
  // pre-fetch counts so the Feedback Queue badge appears even on the Users
  // sub-tab. Hooks into the existing switchTab flow without modifying it.
  let _initialised = false;
  document.addEventListener('click', function (e) {
    if (_initialised) return;
    if (!e || !e.target) return;
    const t = e.target;
    if (t.id === 'tabPermissions' || (t.getAttribute && t.getAttribute('onclick') || '').indexOf("'permissions'") >= 0) {
      _initialised = true;
      // Slight delay so the panel toggles first.
      setTimeout(() => feedbackQueueLoad(), 150);
    }
  }, true);

  // -------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------
  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function setText(id, txt) {
    const el = document.getElementById(id);
    if (el) el.textContent = txt;
  }

  function formatDate(iso) {
    if (!iso) return '';
    try {
      const d = new Date(iso);
      if (isNaN(d.getTime())) return iso;
      const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
      return months[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear() + ' ' +
             d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
    } catch (e) {
      return iso;
    }
  }
})();
