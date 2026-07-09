'use strict';
const test = require('node:test');
const assert = require('node:assert');
const C = require('../../src/main/resources/static/imsreview-classify.js');

// New-vs-Legacy is now queue-based (toolkit-handled), not date-based; the
// second arg is ignored but kept for call-site compatibility.
const ANCHOR = '2026-07-05';
function row(o) { return Object.assign({ owners: [], status: 'NOT_SENT' }, o); }

test('no DRR -> need_drr / drr_missing', () => {
  assert.deepStrictEqual(
    C.imsClassifyTile(row({ hasDrr: false }), ANCHOR),
    { group: 'need_drr', tile: 'drr_missing' });
});

test('has DRR but NOT_SENT (toolkit never handled) -> legacy', () => {
  // A pre-existing DRR the toolkit hasn't touched is Legacy, regardless of date.
  const r = row({ hasDrr: true, status: 'NOT_SENT', drrStatus: 'Pending', drrCreated: '2026-12-01', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'legacy.pending_response');
});

test('toolkit sent (SENT_TO_DO) -> new / pending_response', () => {
  const r = row({ hasDrr: true, status: 'SENT_TO_DO', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.pending_response');
});

test('toolkit sent to DM -> new / in_process', () => {
  const r = row({ hasDrr: true, status: 'SENT_TO_DM', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.in_process');
});

test('toolkit DO needs change -> new / in_process', () => {
  const r = row({ hasDrr: true, status: 'DO_NEEDS_CHANGE', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.in_process');
});

test('toolkit DCO present even if NOT_SENT -> new / in_process', () => {
  const r = row({ hasDrr: true, status: 'NOT_SENT', agileDco: 'DCO-1', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.in_process');
});

test('toolkit need help -> new / need_help', () => {
  const r = row({ hasDrr: true, status: 'DO_NEED_HELP', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.need_help');
});

test('toolkit approved/cancelled -> new / closed', () => {
  assert.strictEqual(C.imsTileKey(row({ hasDrr: true, status: 'DM_APPROVED', allOwnersLeft: false }), ANCHOR), 'new.closed');
  assert.strictEqual(C.imsTileKey(row({ hasDrr: true, status: 'CANCELLED', allOwnersLeft: false }), ANCHOR), 'new.closed');
});

test('toolkit doc, all owners left overrides status -> new / need_owner', () => {
  const r = row({ hasDrr: true, status: 'SENT_TO_DM', allOwnersLeft: true });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.need_owner');
});

// A Needs-Change DCO that reaches Implemented in Agile is closed, even though
// the toolkit queue status is still DO_NEEDS_CHANGE (the toolkit never sees the
// post-submit Agile workflow). The row carries the terminal Agile status in
// drrStatus / dcoStatus. See ImsReviewService (DCO-523133 / 03-32-WW-02-00094).
test('toolkit needs-change but DCO Implemented in Agile -> new / closed', () => {
  const r = row({ hasDrr: true, status: 'DO_NEEDS_CHANGE', agileDco: 'DCO-1', dcoStatus: 'Implemented', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.closed');
});

test('toolkit doc, DRR Implemented in Agile -> new / closed', () => {
  const r = row({ hasDrr: true, status: 'DO_NEEDS_CHANGE', agileDco: 'DCO-1', drrStatus: 'Implemented', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.closed');
});

test('Agile-closed beats owners-left -> new / closed', () => {
  const r = row({ hasDrr: true, status: 'DO_NEEDS_CHANGE', dcoStatus: 'Implemented', allOwnersLeft: true });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.closed');
});

test('toolkit DCO still in CCB (not implemented) stays -> new / in_process', () => {
  const r = row({ hasDrr: true, status: 'DO_NEEDS_CHANGE', agileDco: 'DCO-1', dcoStatus: 'CCB', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.in_process');
});

// Agile ladder: Pending -> Submit -> CCB -> Release -> Implemented. Only
// Implemented is closed; a Released-but-not-Implemented DCO stays In Process.
test('DCO Released (not Implemented) is NOT closed -> new / in_process', () => {
  const r = row({ hasDrr: true, status: 'DO_NEEDS_CHANGE', agileDco: 'DCO-1', dcoStatus: 'Release', allOwnersLeft: false });
  assert.strictEqual(C.imsAgileClosed(r), false);
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.in_process');
});

test('imsDcoReleasedPending: Release yes, Implemented/CCB no', () => {
  assert.strictEqual(C.imsDcoReleasedPending(row({ dcoStatus: 'Release' })), true);
  assert.strictEqual(C.imsDcoReleasedPending(row({ dcoStatus: 'Implemented' })), false);
  assert.strictEqual(C.imsDcoReleasedPending(row({ dcoStatus: 'CCB' })), false);
  assert.strictEqual(C.imsDcoReleasedPending(row({})), false);
});

test('isTerminalAgileStatus via imsAgileClosed: Implemented closed, Release/CCB not', () => {
  assert.strictEqual(C.imsAgileClosed(row({ drrStatus: 'Implemented' })), true);
  assert.strictEqual(C.imsAgileClosed(row({ dcoStatus: 'Release' })), false);
  assert.strictEqual(C.imsAgileClosed(row({ dcoStatus: 'CCB' })), false);
});

test('legacy DRR, Agile status Pending -> legacy / pending_response', () => {
  const r = row({ hasDrr: true, status: 'NOT_SENT', drrStatus: 'Pending', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'legacy.pending_response');
});

test('legacy DRR, Agile status CCB -> legacy / in_process', () => {
  const r = row({ hasDrr: true, status: 'NOT_SENT', drrStatus: 'CCB', allOwnersLeft: false });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'legacy.in_process');
});

test('legacy DRR, all owners left -> legacy / need_owner', () => {
  const r = row({ hasDrr: true, status: 'NOT_SENT', drrStatus: 'CCB', allOwnersLeft: true });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'legacy.need_owner');
});

test('multi-owner: any one valid owner means not need_owner (derives flags)', () => {
  const r = row({ hasDrr: true, status: 'SENT_TO_DO',
                  owners: [{ ldapStatus: 'NOT_FOUND' }, { ldapStatus: 'ACTIVE' }] });
  assert.strictEqual(C.imsTileKey(r, ANCHOR), 'new.pending_response');
});

test('imsTileCounts tallies per key', () => {
  const rows = [
    row({ hasDrr: false }),
    row({ hasDrr: true, status: 'SENT_TO_DO', allOwnersLeft: false }),
    row({ hasDrr: true, status: 'SENT_TO_DM', allOwnersLeft: false }),
    row({ hasDrr: true, status: 'NOT_SENT', drrStatus: 'Pending', allOwnersLeft: false })
  ];
  const counts = C.imsTileCounts(rows, ANCHOR);
  assert.strictEqual(counts['need_drr.drr_missing'], 1);
  assert.strictEqual(counts['new.pending_response'], 1);
  assert.strictEqual(counts['new.in_process'], 1);
  assert.strictEqual(counts['legacy.pending_response'], 1);
});

test('imsDrrStatusBucket: pending vs submit_ccb', () => {
  assert.strictEqual(C.imsDrrStatusBucket('Pending'), 'pending');
  assert.strictEqual(C.imsDrrStatusBucket(''), 'pending');
  assert.strictEqual(C.imsDrrStatusBucket('CCB'), 'submit_ccb');
  assert.strictEqual(C.imsDrrStatusBucket('Submit'), 'submit_ccb');
});

test('exempt doc with no DRR -> need_drr.no_drr_required', () => {
  const c = C.imsClassifyTile({ hasDrr: false, drrExempt: true });
  assert.deepEqual(c, { group: 'need_drr', tile: 'no_drr_required' });
});
test('non-exempt doc with no DRR still -> need_drr.drr_missing', () => {
  const c = C.imsClassifyTile({ hasDrr: false, drrExempt: false });
  assert.deepEqual(c, { group: 'need_drr', tile: 'drr_missing' });
});
test('exempt flag ignored once a DRR exists', () => {
  const c = C.imsClassifyTile({ hasDrr: true, drrExempt: true, status: 'SENT_TO_DO' });
  assert.equal(c.group, 'new');
});
