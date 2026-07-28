/* Pure backlog-grade scoring + deterministic dispute evaluator. No window/
 * document refs so it can be unit-tested under `node --test`. Loaded in the
 * browser before meeting-mode.js (exposes window.GradeLogic); required directly
 * in tests. Mirrors imsreview-classify.js's dual-export wrapper. */
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') window.GradeLogic = api;
})(this, function () {
  'use strict';

  function letterFor(s) {
    return s >= 93 ? 'A' : s >= 90 ? 'A−' : s >= 87 ? 'B+' : s >= 83 ? 'B' : s >= 80 ? 'B−'
      : s >= 77 ? 'C+' : s >= 73 ? 'C' : s >= 70 ? 'C−' : s >= 65 ? 'D+' : s >= 55 ? 'D' : 'F';
  }
  function colorFor(s) { return s >= 80 ? 'var(--good)' : s >= 70 ? 'var(--warn)' : 'var(--bad)'; }

  // Score a backlog. `counts` is built by the host from the live item set
  // (see meeting-mode.buildCounts); `rule` is the runtime-mutable config.
  // "At risk" (the only real penalty) = live overdue + counted unowned-high-pri,
  // scaled to backlog size. Missing dates is a soft fixed penalty; going-live-
  // soon is momentum (no penalty). Closed items are excluded upstream.
  function computeGrade(counts, rule) {
    rule = rule || { graceDays: 0, includeNoOwner: true, missingPenalty: 6 };
    var total = counts.total || 1;
    var liveOver = (counts.overdue || []).filter(function (x) { return (x.daysOverdue || 0) >= rule.graceDays; });
    var graceOver = (counts.overdue || []).filter(function (x) { return (x.daysOverdue || 0) < rule.graceDays; });
    var liveNo = rule.includeNoOwner ? (counts.unownedHi || []) : [];
    var ruleNo = rule.includeNoOwner ? [] : (counts.unownedHi || []);
    var atRiskN = liveOver.length + liveNo.length;
    var onTrackN = (counts.onTrackBase || []).length + graceOver.length + ruleNo.length;
    var penalty = Math.round(atRiskN / total * 100) + (rule.missingPenalty || 0);
    var score = Math.max(0, Math.min(100, 100 - penalty));
    var pct = function (n) { return Math.round(n / total * 100); };
    // soon / missing may be passed as item arrays (so the Breakdown can drill down
    // to the ECNs) or as plain counts (unit tests); support both.
    var soonItems = Array.isArray(counts.soon) ? counts.soon : [];
    var soonN = Array.isArray(counts.soon) ? counts.soon.length : (counts.soon || 0);
    var missItems = Array.isArray(counts.missing) ? counts.missing : [];
    var missN = Array.isArray(counts.missing) ? counts.missing.length : (counts.missingCount || 0);
    var summary = liveOver.length + ' overdue and ' + liveNo.length + ' unowned high-priority item'
      + (liveNo.length === 1 ? '' : 's') + ' are the main risks'
      + (missN ? '; ' + missN + ' still need a target date (informational)' : '') + '.';
    var drivers = [
      { key: 'ontrack', label: 'On track', count: onTrackN, color: 'var(--good)', pct: pct(onTrackN), items: counts.onTrackBase || [] },
      { key: 'overdue', label: 'Overdue UAT / Go-Live', count: liveOver.length, color: 'var(--bad)', pct: pct(liveOver.length), items: liveOver },
      { key: 'noowner', label: 'High priority · no owner', count: liveNo.length, color: 'var(--bad)', pct: pct(liveNo.length), items: liveNo },
      { key: 'soon', label: 'Going live ≤ 7 days', count: soonN, color: 'var(--warn)', pct: pct(soonN), items: soonItems },
      { key: 'missing', label: 'Missing target dates', count: missN, color: 'var(--warn)', pct: pct(missN), items: missItems }
    ];
    return {
      letter: letterFor(score), score: score, penalty: penalty, atRiskN: atRiskN, total: total,
      scoreColor: colorFor(score), summary: summary, drivers: drivers
    };
  }

  function trend(score, lastScore, lastLetter) {
    if (lastScore == null) return { arrow: '—', text: 'no prior review', color: 'var(--ink-3)', bg: 'var(--surface-2)' };
    var d = score - lastScore;
    return {
      arrow: d > 0 ? '▲' : d < 0 ? '▼' : '—',
      text: (d >= 0 ? '+' : '') + d + ' vs last review · ' + lastLetter + ' ' + lastScore,
      color: d > 0 ? 'var(--good-ink)' : d < 0 ? 'var(--bad-ink)' : 'var(--ink-3)',
      bg: d > 0 ? 'var(--good-bg)' : d < 0 ? 'var(--bad-bg)' : 'var(--surface-2)'
    };
  }

  // ---- deterministic dispute evaluator (offline / on-prem fallback) ------
  // Three-tier acceptance gate; the server LLM path mirrors this and falls back
  // to the same logic. Returns { status, verifiedBy?, fieldQuote?, evidence, reason }.
  function _dbCheck(rec, t) {
    if (!rec) return { matched: false };
    var re = /(agile )?it\b[^.]*\b(not|no|isn'?t|never)\b[^.]*(need|requir|necess|involv)|no it (help|involv|support)|it (help|support) not (need|requir)|not an it (item|change|ecn)|business[- ]?(side|led|only)/;
    if (rec.itNotRequired && re.test(t)) return { matched: true, field: 'Description', quote: rec.desc };
    return { matched: false };
  }
  function evaluateDispute(o) {
    o = o || {};
    var text = (o.text || '').trim(), t = text.toLowerCase(), hasFile = !!o.hasFile, rec = o.record;
    var ev = [];
    if (/\b\d{1,2}\/\d{1,2}(\/\d{2,4})?\b/.test(t)) ev.push('a date');
    // NB: a bare ECN number is the subject of the dispute, not proof — it must
    // NOT count as evidence (else typing the ECN id alone would auto-accept).
    if (/\bccb\b/.test(t)) ev.push('a CCB decision');
    if (/owner|assign|reassign/.test(t)) ev.push('an ownership change');
    if (/verif|confirm|signed|approv|complete|closed|done/.test(t)) ev.push('a verification');
    if (/policy|sop|standard|process|cycle/.test(t)) ev.push('a policy/SOP');
    if (/http|link|ticket|jira|servicenow|snow/.test(t)) ev.push('a linked record');
    if (/uat|go-?live|deploy|release|prod/.test(t)) ev.push('a release/UAT status');
    var db = _dbCheck(rec, t);
    if (db.matched) return { status: 'accept', verifiedBy: 'agile_record', fieldQuote: db.quote, evidence: ev, reason: '' };
    if (hasFile) { ev.push('an attached screenshot of the ECN'); return { status: 'accept', verifiedBy: 'attachment', evidence: ev, reason: '' }; }
    var docClaim = /descriptio|\bnotes?\b|comment|\bfield\b|screenshot|attach|\bstates?\b|\bsays\b|not required|help not|per the ecn|agile (say|show|note)/.test(t);
    if (text.length >= 24 && ev.length >= 1) return { status: 'accept', verifiedBy: 'cited_evidence', evidence: ev, reason: '' };
    if (docClaim) return { status: 'need_file', evidence: ev, reason: "That rests on the ECN's own wording, so I can't take it on your word — upload a screenshot of the ECN description showing that and I'll verify it and re-grade." };
    return { status: 'reject', evidence: ev, reason: (text.length < 24
      ? "I can't override on this alone — give me at least a full sentence of context and one piece of proof: a date, an Agile/ECN reference, the new owner, a CCB/policy note, or a screenshot."
      : "There's no verifiable evidence in that. Cite a date, an Agile/ECN reference, the new owner, a CCB/policy decision, or attach a screenshot and I'll re-grade.") };
  }

  return {
    letterFor: letterFor, colorFor: colorFor, computeGrade: computeGrade,
    trend: trend, evaluateDispute: evaluateDispute
  };
});
