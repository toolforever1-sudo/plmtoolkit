// Tests for the pure month-math helpers behind the one-click
// "Generate Last Month's Report" button (volumereport.js).
// Run: node --test test/js/*.test.js
'use strict';

const test = require('node:test');
const assert = require('node:assert');

// volumereport.js registers a top-level document listener (drawer Esc) and
// stamps the one-click button label on load — stub just enough DOM for
// require() to succeed. Helpers under test are pure.
global.document = {
    addEventListener: function () {},
    getElementById: function () { return null; },
    readyState: 'complete'
};

const vr = require('../../src/main/resources/static/volumereport.js');

test('_trLastCompleteMonth: mid-month → previous month', () => {
    assert.strictEqual(vr._trLastCompleteMonth(new Date(2026, 6, 7)), 'Jun_2026');  // Jul 7
    assert.strictEqual(vr._trLastCompleteMonth(new Date(2026, 5, 30)), 'May_2026'); // Jun 30
});

test('_trLastCompleteMonth: first of month still means the month just ended', () => {
    assert.strictEqual(vr._trLastCompleteMonth(new Date(2026, 6, 1)), 'Jun_2026'); // Jul 1
});

test('_trLastCompleteMonth: January rolls back to December of prior year', () => {
    assert.strictEqual(vr._trLastCompleteMonth(new Date(2027, 0, 15)), 'Dec_2026');
    assert.strictEqual(vr._trLastCompleteMonth(new Date(2027, 0, 1)), 'Dec_2026');
});

test('_trMonthParse: valid values round-trip', () => {
    assert.deepStrictEqual(vr._trMonthParse('Jan_2026'), { y: 2026, mi: 0 });
    assert.deepStrictEqual(vr._trMonthParse('Dec_2030'), { y: 2030, mi: 11 });
});

test('_trMonthParse: rejects malformed input', () => {
    assert.strictEqual(vr._trMonthParse('June_2026'), null);   // full month name
    assert.strictEqual(vr._trMonthParse('Jun-2026'), null);    // wrong separator
    assert.strictEqual(vr._trMonthParse('Jun_20XX'), null);    // non-numeric year
    assert.strictEqual(vr._trMonthParse(''), null);
    assert.strictEqual(vr._trMonthParse(null), null);
    assert.strictEqual(vr._trMonthParse('Jun_2026_extra'), null);
});

test('_trMonthLabel: display form and fallbacks', () => {
    assert.strictEqual(vr._trMonthLabel('Jun_2026'), 'Jun 2026');
    assert.strictEqual(vr._trMonthLabel('garbage'), 'garbage'); // unparseable → echoed
    assert.strictEqual(vr._trMonthLabel(null), '');
});

// --- teamreport-inapp.js month ordering (the "stuck on May" bug) ---

const tri = require('../../src/main/resources/static/teamreport-inapp.js');

test('triMonthOrdinal: chronological, not lexicographic', () => {
    assert.ok(tri.triMonthOrdinal('Jun_2026') > tri.triMonthOrdinal('May_2026'));
    assert.ok(tri.triMonthOrdinal('Jan_2027') > tri.triMonthOrdinal('Dec_2026'));
    assert.strictEqual(tri.triMonthOrdinal('nope'), -1);
    assert.strictEqual(tri.triMonthOrdinal(null), -1);
});

test('months list sorted by triMonthOrdinal puts Jun_2026 before May_2026', () => {
    const months = ['Jan_2026', 'May_2026', 'Jun_2026', 'Feb_2026'];
    const sorted = months.slice().sort((a, b) => tri.triMonthOrdinal(b) - tri.triMonthOrdinal(a));
    assert.deepStrictEqual(sorted, ['Jun_2026', 'May_2026', 'Feb_2026', 'Jan_2026']);
    // the buggy form this replaces: lexicographic reverse ranks May first
    assert.strictEqual(months.slice().sort().reverse()[0], 'May_2026');
});

test('triLastCompleteMonth matches volumereport helper', () => {
    const d = new Date(2026, 6, 7); // Jul 7
    assert.strictEqual(tri.triLastCompleteMonth(d), 'Jun_2026');
    assert.strictEqual(tri.triLastCompleteMonth(d), vr._trLastCompleteMonth(d));
    assert.strictEqual(tri.triLastCompleteMonth(new Date(2027, 0, 2)), 'Dec_2026');
});
