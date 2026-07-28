// =====================================================================
//  ask-ai.js — User-Ask sub-tab inside the AI Eval panel.
//  Any logged-in user picks a chatbot model, asks a question, grades
//  the answer. C/D/F items with explanations get filed as Bug Reports
//  in the Feedback Queue at session end.
// =====================================================================

(function () {
  'use strict';

  // Each item: { question, answer, model, source, latencyMs, grade, expectation, ptId }
  let _session = [];
  let _pendingExpectationIdx = -1;
  let _modelsLoaded = false;

  // -------------------------------------------------------------------
  // Sub-tab toggle
  // -------------------------------------------------------------------
  window.aieSwitchSub = function (which) {
    const ask = document.getElementById('aieSubsectionAsk');
    const run = document.getElementById('aieSubsectionRun');
    const tabAsk = document.getElementById('aieSubTabAsk');
    const tabRun = document.getElementById('aieSubTabRun');
    if (!ask || !run) return;
    if (which === 'run') {
      ask.style.display = 'none';
      run.style.display = '';
      tabAsk.style.borderBottomColor = 'transparent';
      tabAsk.style.color = '#6B7280';
      tabAsk.style.fontWeight = '500';
      tabRun.style.borderBottomColor = '#4a6fa5';
      tabRun.style.color = '#0F1720';
      tabRun.style.fontWeight = '600';
    } else {
      ask.style.display = '';
      run.style.display = 'none';
      tabAsk.style.borderBottomColor = '#4a6fa5';
      tabAsk.style.color = '#0F1720';
      tabAsk.style.fontWeight = '600';
      if (tabRun) {
        tabRun.style.borderBottomColor = 'transparent';
        tabRun.style.color = '#6B7280';
        tabRun.style.fontWeight = '500';
      }
      askEnsureModelsLoaded();
    }
  };

  // -------------------------------------------------------------------
  // Model picker — loaded once on first sub-tab open
  // -------------------------------------------------------------------
  function askEnsureModelsLoaded() {
    if (_modelsLoaded) return;
    fetch('/api/ai-eval/models', { credentials: 'same-origin' })
      .then(r => r.json())
      .then(data => {
        const sel = document.getElementById('askModel');
        if (!sel) return;
        sel.innerHTML = '';
        // "Production default" first so end users have a no-think option.
        const def = document.createElement('option');
        def.value = '';
        def.textContent = 'Production default (Claude Sonnet 4.6)';
        sel.appendChild(def);
        (data.groups || []).forEach(g => {
          const og = document.createElement('optgroup');
          og.label = g.label;
          (g.options || []).forEach(o => {
            const opt = document.createElement('option');
            opt.value = o.slug;
            opt.textContent = o.label;
            og.appendChild(opt);
          });
          sel.appendChild(og);
        });
        _modelsLoaded = true;
      })
      .catch(() => { /* silent */ });
  }

  // -------------------------------------------------------------------
  // Ask flow
  // -------------------------------------------------------------------
  window.askSubmit = function () {
    const qEl = document.getElementById('askQuestion');
    const modelEl = document.getElementById('askModel');
    const btn = document.getElementById('askBtn');
    if (!qEl || !modelEl || !btn) return;
    const question = (qEl.value || '').trim();
    if (!question) return;
    const model = modelEl.value || '';

    btn.disabled = true;
    btn.textContent = 'Asking…';

    // Append a "thinking" card so the user sees something immediately.
    const idx = _session.length;
    _session.push({ question: question, model: model, answer: null, source: null, latencyMs: 0, grade: null, expectation: null, ptId: null });
    renderThread();

    // Build follow-up history from prior turns in this session (last 3 Q&As) so
    // the LLM-fallback branch has context — "of those, how many were 32GB?"
    // can build on the previous answer.
    const history = [];
    const priorTurns = _session.slice(Math.max(0, idx - 3), idx)
      .filter(t => t && t.question && t.answer != null && !t.error);
    priorTurns.forEach(t => {
      history.push({ role: 'user', content: t.question });
      history.push({ role: 'assistant', content: stripHtml(t.answer) });
    });

    fetch('/api/ai-eval/ask/question', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: question, model: model, history: history })
    })
      .then(r => r.json().then(j => ({ ok: r.ok, body: j })))
      .then(({ ok, body }) => {
        const it = _session[idx];
        if (!ok) {
          it.answer = '⚠️ ' + ((body && (body.error || body.message)) || 'Request failed.');
          it.error = true;
        } else {
          it.answer = body.answer || '';
          it.latencyMs = body.latencyMs || 0;
          it.source = body.source || null;
        }
        renderThread();
        // Reset input
        qEl.value = '';
      })
      .catch(err => {
        const it = _session[idx];
        it.answer = '⚠️ ' + err.message;
        it.error = true;
        renderThread();
      })
      .finally(() => {
        btn.disabled = false;
        btn.textContent = 'Ask';
      });
  };

  function renderThread() {
    const thread = document.getElementById('askThread');
    if (!thread) return;
    if (_session.length === 0) {
      thread.innerHTML = '';
      document.getElementById('askSessionFooter').style.display = 'none';
      return;
    }
    // Newest first — keeps the input box reachable without scrolling.
    thread.innerHTML = _session
      .map((it, idx) => ({ it, idx }))
      .reverse()
      .map(({ it, idx }) => renderCard(it, idx))
      .join('');

    // Update session footer
    const footer = document.getElementById('askSessionFooter');
    const stats = document.getElementById('askSessionStats');
    const graded = _session.filter(x => x.grade).length;
    const failing = _session.filter(x => x.grade && ['C','D','F'].indexOf(x.grade) >= 0).length;
    const failingWithReason = _session.filter(x => x.grade && ['C','D','F'].indexOf(x.grade) >= 0 && x.expectation).length;
    if (graded > 0) {
      footer.style.display = '';
      stats.textContent = graded + ' graded · ' + failing + ' marked C/D/F · ' + failingWithReason + ' will be filed as Bug Reports';
    } else {
      footer.style.display = 'none';
    }
  }

  function renderCard(it, idx) {
    const modelLabel = it.model ? it.model.replace(/^@[^/]+\//, '') : 'production default';
    const latencyTxt = it.latencyMs ? ' · ' + it.latencyMs + ' ms' : '';
    const srcTxt = it.source ? ' · src=' + esc(it.source) : '';
    const gradeBtns = ['A','B','C','D','F'].map(g => {
      const isActive = it.grade === g;
      const colors = {
        A: { bg: '#1F8A4C', fg: '#fff' },
        B: { bg: '#3a8842', fg: '#fff' },
        C: { bg: '#C7801B', fg: '#fff' },
        D: { bg: '#C7801B', fg: '#fff' },
        F: { bg: '#B8342B', fg: '#fff' }
      };
      const inactiveStyle = 'background:#FAFAF7; color:#6B7280; border:1px solid #E8E6DF;';
      const activeStyle = 'background:' + colors[g].bg + '; color:' + colors[g].fg + '; border:1px solid ' + colors[g].bg + ';';
      return '<button onclick="askGrade(' + idx + ',\'' + g + '\')" style="' + (isActive ? activeStyle : inactiveStyle) + ' padding:4px 12px; border-radius:6px; font-size:12px; font-weight:600; cursor:pointer; margin-right:4px;">' + g + '</button>';
    }).join('');

    const answerHtml = it.answer == null
      ? '<div style="color:#6B7280; font-style:italic;">Thinking…</div>'
      : (it.error
          ? '<div style="color:#B8342B;">' + esc(it.answer) + '</div>'
          : '<div style="line-height:1.5;">' + it.answer + '</div>');

    const expectationBadge = it.expectation
      ? '<div style="margin-top:8px; background:#fdeaea; border-left:3px solid #B8342B; padding:6px 10px; font-size:12px; border-radius:0 4px 4px 0;"><strong>Your expectation:</strong> ' + esc(it.expectation) + '</div>'
      : '';

    return (
      '<div style="background:#fff; border:1px solid #E8E6DF; border-radius:8px; padding:14px 18px; margin-bottom:12px;">' +
        '<div style="font-size:11px; color:#6B7280; margin-bottom:6px;">Q' + (idx + 1) + ' · ' + esc(modelLabel) + esc(latencyTxt) + srcTxt + '</div>' +
        '<div style="font-weight:600; font-size:14px; color:#0F1720; margin-bottom:10px;">' + esc(it.question) + '</div>' +
        '<div style="background:#FAFAF7; border-radius:6px; padding:10px 12px; font-size:13px; color:#0F1720;">' + answerHtml + '</div>' +
        (it.answer != null && !it.error
          ? ('<div style="display:flex; align-items:center; margin-top:10px; gap:6px;">' +
               '<span style="font-size:11px; color:#6B7280; margin-right:8px;">Grade:</span>' + gradeBtns + '</div>' +
             expectationBadge)
          : '') +
      '</div>'
    );
  }

  // -------------------------------------------------------------------
  // Grade + expectation flow
  // -------------------------------------------------------------------
  window.askGrade = function (idx, grade) {
    const it = _session[idx];
    if (!it) return;
    it.grade = grade;
    if (grade === 'C' || grade === 'D' || grade === 'F') {
      _pendingExpectationIdx = idx;
      const subtitle = document.getElementById('askExpectationSubtitle');
      if (subtitle) subtitle.textContent = 'Q' + (idx + 1) + ' · graded ' + grade;
      const ta = document.getElementById('askExpectationText');
      if (ta) ta.value = it.expectation || '';
      askExpectationClearError();
      const m = document.getElementById('askExpectationModal');
      if (m) m.style.display = 'flex';
    } else {
      // A or B — clear any prior expectation.
      it.expectation = null;
      renderThread();
    }
  };

  function askExpectationClearError() {
    const err = document.getElementById('askExpectationError');
    if (err) { err.style.display = 'none'; err.textContent = ''; }
  }

  function askExpectationShowError(msg) {
    const err = document.getElementById('askExpectationError');
    if (err) { err.textContent = msg; err.style.display = ''; }
  }

  function askExpectationSetSaving(saving) {
    const btn = document.getElementById('askExpectationSaveBtn');
    if (!btn) return;
    btn.disabled = !!saving;
    btn.textContent = saving ? 'Checking…' : 'Save expectation';
    btn.style.opacity = saving ? '0.7' : '1';
  }

  function askExpectationFinalize(it, txt) {
    it.expectation = txt.length > 0 ? txt : null;
    const m = document.getElementById('askExpectationModal');
    if (m) m.style.display = 'none';
    _pendingExpectationIdx = -1;
    askExpectationClearError();
    askExpectationSetSaving(false);
    renderThread();
  }

  window.askExpectationClose = function (save) {
    // Cancel — close immediately, no validation, no save.
    if (!save) {
      const m = document.getElementById('askExpectationModal');
      if (m) m.style.display = 'none';
      _pendingExpectationIdx = -1;
      askExpectationClearError();
      askExpectationSetSaving(false);
      renderThread();
      return;
    }

    if (_pendingExpectationIdx < 0) return;
    const it = _session[_pendingExpectationIdx];
    const ta = document.getElementById('askExpectationText');
    if (!it || !ta) return;
    const txt = (ta.value || '').trim();

    // Empty input — nothing to validate, nothing to save. Close as before.
    if (txt.length === 0) {
      askExpectationFinalize(it, '');
      return;
    }

    askExpectationClearError();
    askExpectationSetSaving(true);

    fetch('/api/ai-eval/ask/validate-expectation', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question: it.question,
        answer: stripHtml(it.answer),
        expectation: txt
      })
    })
      .then(r => r.json().then(j => ({ ok: r.ok, body: j })))
      .then(({ ok, body }) => {
        if (!ok) {
          // HTTP error (e.g. 401, 500) — fail open, save anyway.
          console.warn('[ask validate] HTTP error, failing open', body);
          askExpectationFinalize(it, txt);
          return;
        }
        if (body && body.valid === false) {
          askExpectationSetSaving(false);
          askExpectationShowError(body.reason || 'Please describe what you expected the AI to say.');
          return;
        }
        askExpectationFinalize(it, txt);
      })
      .catch(err => {
        console.warn('[ask validate] network error, failing open', err);
        askExpectationFinalize(it, txt);
      });
  };

  // -------------------------------------------------------------------
  // End session — file Bug Reports for C/D/F + expectation
  // -------------------------------------------------------------------
  window.askEndSession = function () {
    if (_session.length === 0) return;
    // For each turn, attach up to 3 immediately-prior turns from the same
    // session so the bug report retains context for follow-up questions
    // (e.g. "of those, how many were 32GB?" — the prior block tells the
    // debugger what "those" referred to).
    const items = _session.map((it, idx) => {
      const priorRaw = _session.slice(Math.max(0, idx - 3), idx)
        .filter(t => t && t.question && t.answer != null && !t.error);
      const prior = priorRaw.map(t => ({
        question: t.question,
        answer: stripHtml(t.answer),
        source: t.source,
        grade: t.grade
      }));
      return {
        question: it.question,
        answer: it.answer,
        model: it.model,
        source: it.source,
        latencyMs: it.latencyMs,
        grade: it.grade,
        expectation: it.expectation,
        prior: prior
      };
    });
    fetch('/api/ai-eval/ask/submit-session', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ items: items })
    })
      .then(r => r.json())
      .then(data => {
        const filed = data.filed || 0;
        const ptIds = data.ptIds || [];
        if (filed > 0) {
          appAlert('Filed ' + filed + ' Bug Report' + (filed === 1 ? '' : 's') + ': ' + ptIds.join(', ') +
                '\n\nYou will get an email confirmation. The PLM admin team will follow up.');
        } else {
          appAlert('No bug reports filed (no C/D/F items had a reason). Session closed.');
        }
        // Reset session
        _session = [];
        renderThread();
      })
      .catch(err => appAlert('Failed to submit session: ' + err.message));
  };

  // -------------------------------------------------------------------
  // Bootstrap when AI Eval tab opens
  // -------------------------------------------------------------------
  function bootstrapForVisitor() {
    // Show the "Run Eval" sub-tab only for admins. Use existing /me.
    fetch('/api/permissions/me', { credentials: 'same-origin' })
      .then(r => r.json())
      .then(me => {
        if (me && me.isPlmAdmin) {
          const tab = document.getElementById('aieSubTabRun');
          if (tab) tab.style.display = 'inline-block';
        }
      })
      .catch(() => { /* silent */ });
    askEnsureModelsLoaded();
  }

  document.addEventListener('click', function (e) {
    if (!e || !e.target) return;
    const t = e.target;
    const onClickAttr = (t.getAttribute && t.getAttribute('onclick')) || '';
    if (t.id === 'tabAiEval' || onClickAttr.indexOf("'aieval'") >= 0) {
      setTimeout(bootstrapForVisitor, 150);
    }
  }, true);

  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function stripHtml(s) {
    if (s == null) return '';
    // Quick-and-cheap tag strip + whitespace normalize. Used to feed prior
    // assistant answers (which may contain <table>, <p>, etc. from interceptor
    // outputs) to the LLM as plain-text context.
    return String(s).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
  }
})();
