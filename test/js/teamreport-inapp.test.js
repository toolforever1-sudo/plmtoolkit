'use strict';
const test = require('node:test');
const assert = require('node:assert');
const tri = require('../../src/main/resources/static/teamreport-inapp.js');

const FIXTURE = {
  month: 'May_2026',
  months: ['Jan', 'Feb', 'Mar', 'Apr', 'May'],
  pcms: ['Daeren Hong', 'Syauqie'],
  changes: {
    'Daeren Hong': { Jan: [1, 10, 2, 8], Feb: [0, 5, 1, 4], Mar: [2, 8, 0, 6], Apr: [1, 6, 1, 5], May: [0, 7, 2, 5] },
    'Syauqie':     { Jan: [0, 3, 1, 2], Feb: [1, 2, 0, 2], Mar: [0, 4, 1, 3], Apr: [0, 3, 0, 2], May: [1, 5, 1, 4] }
  },
  volume: {
    'Daeren Hong': { Jan: [10, 100, 20, 8], Feb: [0, 50, 10, 4], Mar: [20, 80, 0, 6], Apr: [10, 60, 10, 5], May: [0, 70, 20, 5] },
    'Syauqie':     { Jan: [0, 30, 10, 2], Feb: [10, 20, 0, 2], Mar: [0, 40, 10, 3], Apr: [0, 30, 0, 2], May: [10, 50, 10, 4] }
  },
  ytd: { 'Daeren Hong': [1536, 92, 75], 'Syauqie': [800, 60, 40] }
};

// triMonthTotals reads the module-global TRI_STATE; point it at the fixture for series tests.
if (tri.__setState) tri.__setState(FIXTURE);

test('triQuarterOf maps months to quarters', () => {
  assert.strictEqual(tri.triQuarterOf('Jan'), 'Q1');
  assert.strictEqual(tri.triQuarterOf('Mar'), 'Q1');
  assert.strictEqual(tri.triQuarterOf('Apr'), 'Q2');
  assert.strictEqual(tri.triQuarterOf('Dec'), 'Q4');
});

test('triMonthsInQuarter returns only available months for a quarter', () => {
  assert.deepStrictEqual(tri.triMonthsInQuarter('Q1', FIXTURE.months), ['Jan', 'Feb', 'Mar']);
  assert.deepStrictEqual(tri.triMonthsInQuarter('Q2', FIXTURE.months), ['Apr', 'May']); // Jun absent
  assert.deepStrictEqual(tri.triMonthsInQuarter('Q3', FIXTURE.months), []);
});

test('triQuartersInData lists quarters that have at least one month', () => {
  assert.deepStrictEqual(tri.triQuartersInData(FIXTURE.months), ['Q1', 'Q2']);
});

test('triPcmTotalsOverMonths sums a PCM across a month list', () => {
  // Daeren Hong Q1 changes: Jan[1,10,2,8]+Feb[0,5,1,4]+Mar[2,8,0,6] = [3,23,3,18]
  assert.deepStrictEqual(
    tri.triPcmTotalsOverMonths(FIXTURE.changes, 'Daeren Hong', ['Jan', 'Feb', 'Mar']),
    [3, 23, 3, 18]
  );
});

test('triQuarterOf returns null for unknown/empty/wrong-case month', () => {
  assert.strictEqual(tri.triQuarterOf('jan'), null); // case-sensitive by design
  assert.strictEqual(tri.triQuarterOf(''), null);
  assert.strictEqual(tri.triQuarterOf(null), null);
});

test('triMonthsInQuarter returns [] for an unknown quarter key', () => {
  assert.deepStrictEqual(tri.triMonthsInQuarter('Q9', FIXTURE.months), []);
});

test('triPcmTotalsOverMonths returns zeros for an unknown PCM', () => {
  assert.deepStrictEqual(tri.triPcmTotalsOverMonths(FIXTURE.changes, 'Nobody', ['Jan']), [0, 0, 0, 0]);
});

test('triPcmQuarterRows builds one row per PCM plus a total, for a quarter', () => {
  const rows = tri.triPcmQuarterRows(FIXTURE, 'Q1');
  // Daeren Hong Q1 changes [3,23,3,18]; volume items over Jan-Mar:
  // Jan[10,100,20]+Feb[0,50,10]+Mar[20,80,0] => 10+100+20+0+50+10+20+80+0 = 290
  assert.deepStrictEqual(rows[0], {
    pcm: 'Daeren Hong', aml: 3, eco: 23, mco: 3, xco: 29, ecn: 18, items: 290
  });
  const total = rows[rows.length - 1];
  assert.strictEqual(total.__total, true);
  assert.strictEqual(total.pcm, 'All PCMs');
  // totals = Daeren[3,23,3] + Syauqie Q1 changes Jan[0,3,1]+Feb[1,2,0]+Mar[0,4,1]=[1,9,2]
  assert.strictEqual(total.aml, 4);
  assert.strictEqual(total.eco, 32);
  assert.strictEqual(total.mco, 5);
});

test('triPcmVolumeSeries returns xco/ecn/items totals per PCM across all months', () => {
  const s = tri.triPcmVolumeSeries(FIXTURE);
  const daeren = s.find(x => x.pcm === 'Daeren Hong');
  // changes xco = sum over months of (aml+eco+mco):
  // Jan 13, Feb 6, Mar 10, Apr 8, May 9 = 46 ; ecn = 8+4+6+5+5 = 28
  assert.strictEqual(daeren.xco, 46);
  assert.strictEqual(daeren.ecn, 28);
  // volume items = sum over months of (aml+eco+mco):
  // Jan 130, Feb 60, Mar 100, Apr 80, May 90 = 460
  assert.strictEqual(daeren.items, 460);
});

test('triMonthVolumeSeries returns xco/ecn/items per month', () => {
  const s = tri.triMonthVolumeSeries(FIXTURE);
  const jan = s.find(x => x.month === 'Jan');
  // changes Jan across PCMs: Daeren[1,10,2,8] + Syauqie[0,3,1,2] = [1,13,3,10]
  // xco = 1+13+3 = 17 ; ecn = 10
  assert.strictEqual(jan.xco, 17);
  assert.strictEqual(jan.ecn, 10);
  // volume Jan: Daeren[10,100,20] + Syauqie[0,30,10] => items 130+40 = 170
  assert.strictEqual(jan.items, 170);
  assert.strictEqual(s.length, 5);
});

test('triChangeTypeMonthSeries returns eco/aml/mco counts + items per month', () => {
  const s = tri.triChangeTypeMonthSeries(FIXTURE);
  const jan = s.find(x => x.month === 'Jan');
  // changes Jan across PCMs = [aml1, eco13, mco3, ecn10]
  assert.strictEqual(jan.aml, 1);
  assert.strictEqual(jan.eco, 13);
  assert.strictEqual(jan.mco, 3);
  assert.strictEqual(jan.items, 170);
});

test('triDonutStops builds cumulative conic-gradient stops with percentages', () => {
  const items = [{ name: 'A', count: 75 }, { name: 'B', count: 25 }];
  const r = tri.triDonutStops(items, ['#111', '#222']);
  assert.strictEqual(r.total, 100);
  assert.strictEqual(r.segments[0].pct, 75);
  assert.strictEqual(r.segments[1].pct, 25);
  assert.ok(r.gradient.includes('#111 0% 75%'));
  assert.ok(r.gradient.includes('#222 75% 100%'));
});

test('triDonutStops handles empty input without dividing by zero', () => {
  const r = tri.triDonutStops([], ['#111']);
  assert.strictEqual(r.total, 0);
  assert.deepStrictEqual(r.segments, []);
  assert.strictEqual(r.gradient, '#E8E6DF 0% 100%');
});

test('triTopNProductLines keeps top N by grand total and buckets the rest into Other', () => {
  const ecnByMonth = {
    Jan: { PLa: 10, PLb: 5, PLc: 2, PLd: 1 },
    Feb: { PLa: 8, PLb: 4, PLc: 3, PLe: 1 }
  };
  const r = tri.triTopNProductLines(ecnByMonth, ['Jan', 'Feb'], 2);
  assert.deepStrictEqual(r.lines, ['PLa', 'PLb', 'Other']);
  assert.strictEqual(r.byMonth.Jan.Other, 3);
  assert.strictEqual(r.byMonth.Feb.Other, 4);
  assert.strictEqual(r.byMonth.Jan.PLa, 10);
  const r2 = tri.triTopNProductLines({ Jan: { PLa: 1 } }, ['Jan'], 5);
  assert.deepStrictEqual(r2.lines, ['PLa']);
});

test('triYearSeries shapes the history payload into per-year rows', () => {
  const hist = { years: [
    { year: '2014', label: '2014 (Apr-Dec)', eco: 2049, mco: 595, aml: 388, affectedItems: 33550 },
    { year: '2015', label: '2015', eco: 4543, mco: 1312, aml: 318, affectedItems: 71431 }
  ]};
  const s = tri.triYearSeries(hist);
  assert.strictEqual(s.length, 2);
  assert.strictEqual(s[0].label, '2014 (Apr-Dec)');
  assert.strictEqual(s[0].eco, 2049);
  assert.strictEqual(s[0].items, 33550);
  assert.strictEqual(s[1].xco, 4543 + 1312 + 318);
});

test('triYearSeries returns [] for missing/empty history', () => {
  assert.deepStrictEqual(tri.triYearSeries(null), []);
  assert.deepStrictEqual(tri.triYearSeries({}), []);
});

test('triPcmWorkload ranks PCMs by xCO with avg + distribution %', () => {
  const d = {
    months: ['Jan', 'Feb'],
    pcms: ['A', 'B'],
    ytd: { A: [1000, 80, 70], B: [400, 20, 15] }
  };
  const w = tri.triPcmWorkload(d);
  assert.strictEqual(w.grandXco, 100);
  assert.strictEqual(w.rows[0].pcm, 'A');
  assert.strictEqual(w.rows[0].xco, 80);
  assert.strictEqual(w.rows[0].avgMonthly, 40);
  assert.strictEqual(w.rows[0].pct, 80);
  assert.strictEqual(w.rows[1].pct, 20);
});

test('triPcmWorkload returns empty shape for missing data', () => {
  assert.deepStrictEqual(tri.triPcmWorkload(null), { rows: [], grandXco: 0, months: 1 });
  assert.deepStrictEqual(tri.triPcmWorkload({ pcms: ['A'] }), { rows: [], grandXco: 0, months: 1 });
});

test('triPcmYearRows builds per-PCM rows across years, ranked by latest-year xco', () => {
  const hist = { byPcm: {
    "2024": { "A": {ecn:10,xco:12,items:100}, "B": {ecn:5,xco:6,items:50} },
    "2025": { "A": {ecn:8,xco:9,items:80},   "B": {ecn:7,xco:8,items:70} }
  }};
  const r = tri.triPcmYearRows(hist);
  assert.deepStrictEqual(r.years, ["2024","2025"]);
  // A latest xco 9, B latest xco 8 → A ranks first
  assert.strictEqual(r.rows[0].pcm, "A");
  assert.deepStrictEqual(r.rows[0].cells, [12, 9]);   // xco per year
  assert.deepStrictEqual(r.rows[1].cells, [6, 8]);
});

test('triPcmYearRows empty when no byPcm', () => {
  assert.deepStrictEqual(tri.triPcmYearRows({}), { years: [], rows: [] });
  assert.deepStrictEqual(tri.triPcmYearRows(null), { years: [], rows: [] });
});

test('triPlYearSeries top-N product lines across years + Other', () => {
  const hist = { byProductLine: {
    "2024": { "PLa": 10, "PLb": 5, "PLc": 2 },
    "2025": { "PLa": 8, "PLb": 4, "PLd": 3 }
  }};
  const s = tri.triPlYearSeries(hist, 2);
  assert.deepStrictEqual(s.years, ["2024","2025"]);
  // grand totals PLa=18, PLb=9, PLc=2, PLd=3 → top2 = PLa, PLb; rest→Other
  assert.deepStrictEqual(s.lines, ["PLa","PLb","Other"]);
  assert.deepStrictEqual(s.byLine.PLa, [10, 8]);
  assert.deepStrictEqual(s.byLine.Other, [2, 3]); // 2024 PLc=2; 2025 PLd=3
});

test('triPcmPrevYearXco returns the prior (second-latest) year xco for a PCM', () => {
  const hist = { byPcm: { "2025": { "A": {xco:760} }, "2026": { "A": {xco:534} } } };
  const r = tri.triPcmPrevYearXco(hist, "A");
  assert.deepStrictEqual(r, { year: "2025", xco: 760 });
  assert.strictEqual(tri.triPcmPrevYearXco({}, "A"), null);
});
