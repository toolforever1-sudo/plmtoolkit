'use strict';
const test = require('node:test');
const assert = require('node:assert');
const G = require('../../src/main/resources/static/grade-logic.js');

const DEF = { graceDays: 0, includeNoOwner: true, missingPenalty: 6 };
function counts(o) {
  return Object.assign({ total: 65, overdue: [], unownedHi: [], soon: 0, missingCount: 0, onTrackBase: [] }, o);
}
function mkOverdue(n, days) { return Array.from({ length: n }, (_, i) => ({ id: 'ECN-' + (1000 + i), daysOverdue: days || 10, kind: 'overdue' })); }
function mkNo(n) { return Array.from({ length: n }, (_, i) => ({ id: 'ECN-' + (2000 + i), kind: 'noowner' })); }

test('letterFor bands', () => {
  assert.strictEqual(G.letterFor(95), 'A');
  assert.strictEqual(G.letterFor(68), 'D+');
  assert.strictEqual(G.letterFor(50), 'F');
});

test('baseline D+ 68 — 13 overdue + 4 no-owner + 25 missing of 65', () => {
  const g = G.computeGrade(counts({ overdue: mkOverdue(13), unownedHi: mkNo(4), missingCount: 25 }), DEF);
  assert.strictEqual(g.atRiskN, 17);
  assert.strictEqual(g.penalty, 32);       // round(17/65*100)=26, +6
  assert.strictEqual(g.score, 68);
  assert.strictEqual(g.letter, 'D+');
});

test('graceDays>0 moves sub-grace overdue out of at-risk', () => {
  const od = mkOverdue(10, 12).concat(mkOverdue(3, 4)); // 3 under 7d grace
  const g = G.computeGrade(counts({ overdue: od, unownedHi: mkNo(4), missingCount: 25 }), { graceDays: 7, includeNoOwner: true, missingPenalty: 6 });
  assert.strictEqual(g.atRiskN, 14);       // 10 overdue + 4 no-owner
});

test('includeNoOwner=false drops the no-owner bucket', () => {
  const g = G.computeGrade(counts({ overdue: mkOverdue(13), unownedHi: mkNo(4), missingCount: 25 }), { graceDays: 0, includeNoOwner: false, missingPenalty: 6 });
  assert.strictEqual(g.atRiskN, 13);
  assert.strictEqual(g.drivers.find(d => d.key === 'noowner').count, 0);
});

test('missingPenalty=0 makes missing fully informational', () => {
  const g = G.computeGrade(counts({ overdue: mkOverdue(13), unownedHi: mkNo(4), missingCount: 25 }), { graceDays: 0, includeNoOwner: true, missingPenalty: 0 });
  assert.strictEqual(g.penalty, 26);
});

test('score clamps to [0,100]', () => {
  const g = G.computeGrade(counts({ overdue: mkOverdue(65), missingCount: 0 }), DEF);
  assert.strictEqual(g.score, 0);
});

// --- Phase 2 Task 8: deterministic three-tier evaluator ---
const REC = { desc: 'Process-documentation update only. Business-led; Agile IT help not required.', itNotRequired: true };
const REC2 = { desc: 'IT-led; go-live slipped.', itNotRequired: false };

test('db-verified: IT-not-required claim matching the record → accept, no file', () => {
  const ev = G.evaluateDispute({ text: 'The ECN description says Agile IT help not required.', hasFile: false, record: REC });
  assert.strictEqual(ev.status, 'accept');
  assert.strictEqual(ev.verifiedBy, 'agile_record');
});
test('doc-claim with no record support → need_file', () => {
  const ev = G.evaluateDispute({ text: 'The description says this is not an IT change.', hasFile: false, record: REC2 });
  assert.strictEqual(ev.status, 'need_file');
});
test('doc-claim with attachment → accept (attachment)', () => {
  const ev = G.evaluateDispute({ text: 'The description says this is not an IT change.', hasFile: true, record: REC2 });
  assert.strictEqual(ev.status, 'accept');
  assert.strictEqual(ev.verifiedBy, 'attachment');
});
test('independent evidence (date/CCB/owner) → accept (cited_evidence)', () => {
  const ev = G.evaluateDispute({ text: 'Reassigned to A. Rivera on 6/15 per CCB-2026-114; UAT signed off.', hasFile: false, record: REC2 });
  assert.strictEqual(ev.status, 'accept');
  assert.strictEqual(ev.verifiedBy, 'cited_evidence');
});
test('weak opinion → reject', () => {
  const ev = G.evaluateDispute({ text: 'this is unfair', hasFile: false, record: REC2 });
  assert.strictEqual(ev.status, 'reject');
});
