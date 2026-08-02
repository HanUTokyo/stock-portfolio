import assert from 'node:assert/strict';
import test from 'node:test';
import {
  calculateGrossMarginAnalysis,
  calculateGrossMarginAcceleration,
  calculateGrossMarginQoQChange,
  calculateGrossMarginYoYChange,
  calculateIncrementalGrossMargin,
  calculateQuarterlyGrossMargin,
  calculateTTMGrossMargin,
  calculateGrossProfitGrowth,
  classifyGrossMarginTrend
} from './grossMarginAnalysis.js';

const rows = [
  { asOfDate: '2025-03-31', revenue: 100, grossProfit: 50 },
  { asOfDate: '2025-06-30', revenue: 200, grossProfit: 120 },
  { asOfDate: '2025-09-30', revenue: 300, grossProfit: 150 },
  { asOfDate: '2025-12-31', revenue: 400, grossProfit: 200 },
  { asOfDate: '2026-03-31', revenue: 500, grossProfit: 300 },
  { asOfDate: '2026-06-30', revenue: 550, grossProfit: 330 }
];

test('TTM gross margin uses summed gross profit and revenue, not average percentages', () => {
  assert.equal(calculateTTMGrossMargin(rows, 3), 52);
});

test('quarterly gross margin returns null when revenue is zero', () => {
  assert.equal(calculateQuarterlyGrossMargin({ revenue: 0, grossProfit: 10 }), null);
});

test('YoY change matches same quarter last year', () => {
  const analysis = calculateGrossMarginAnalysis(rows);
  const q1 = analysis.quarterly.find((row) => row.date === '2026-03-31');
  assert.equal(q1.yoyChange, 10);
  assert.equal(calculateGrossMarginYoYChange(60, 50), 10);
});

test('QoQ change matches previous quarter', () => {
  assert.equal(calculateGrossMarginQoQChange(60, 50), 10);
  const analysis = calculateGrossMarginAnalysis(rows);
  const q2 = analysis.quarterly.find((row) => row.date === '2025-06-30');
  assert.equal(q2.qoqChange, 10);
});

test('expansion, compression, and flat classification works', () => {
  assert.equal(classifyGrossMarginTrend(0.2), 'expansion');
  assert.equal(classifyGrossMarginTrend(-0.2), 'compression');
  assert.equal(classifyGrossMarginTrend(0.05), 'flat');
});

test('acceleration is current change minus previous change', () => {
  assert.equal(calculateGrossMarginAcceleration(3, 2), 1);
});

test('incremental gross margin returns null when revenue delta is not positive', () => {
  assert.equal(calculateIncrementalGrossMargin(
    { revenue: 90, grossProfit: 50 },
    { revenue: 100, grossProfit: 40 }
  ), null);
});

test('gross profit growth calculates percentage growth', () => {
  assert.equal(calculateGrossProfitGrowth(
    { grossProfit: 150 },
    { grossProfit: 100 }
  ), 50);
});

test('missing data falls back gracefully without throwing', () => {
  const analysis = calculateGrossMarginAnalysis([{ asOfDate: '2026-03-31', revenue: null, grossProfit: null }]);
  assert.equal(analysis.latest, null);
  assert.equal(analysis.quarterly[0].value, null);
});

test('TTM gross margin returns null when fewer than four quarters are available', () => {
  assert.equal(calculateTTMGrossMargin(rows.slice(0, 3), 2), null);
});

test('TTM gross margin skips rows without income statement amounts instead of breaking the series', () => {
  const rowsWithBalanceOnlyQuarter = [
    { asOfDate: '2025-03-31', revenue: 100, grossProfit: 50 },
    { asOfDate: '2025-06-30', revenue: 200, grossProfit: 120 },
    { asOfDate: '2025-09-30', revenue: 300, grossProfit: 150 },
    { asOfDate: '2025-12-31', revenue: 400, grossProfit: 200 },
    { asOfDate: '2026-03-31', revenue: null, grossProfit: null },
    { asOfDate: '2026-06-30', revenue: 500, grossProfit: 300 }
  ];

  assert.equal(calculateTTMGrossMargin(rowsWithBalanceOnlyQuarter, 4), 52);
  assert.equal(Number(calculateTTMGrossMargin(rowsWithBalanceOnlyQuarter, 5).toFixed(6)), 55);
});

test('TTM and annual gross margin series include first and second derivatives', () => {
  const analysis = calculateGrossMarginAnalysis([
    ...rows,
    { asOfDate: '2026-09-30', revenue: 600, grossProfit: 390 },
    { asOfDate: '2026-12-31', revenue: 650, grossProfit: 455 }
  ]);
  assert.notEqual(analysis.ttm.at(-1).change, null);
  assert.notEqual(analysis.ttm.at(-1).acceleration, null);
  assert.notEqual(analysis.annual.at(-1).change, null);
});

test('gross margin analysis keeps missing quarters as empty slots', () => {
  const analysis = calculateGrossMarginAnalysis([
    { asOfDate: '2025-03-31', revenue: 100, grossProfit: 50 },
    { asOfDate: '2025-06-30', missing: true },
    { asOfDate: '2025-09-30', revenue: 200, grossProfit: 120 }
  ]);

  assert.equal(analysis.quarterly.length, 3);
  assert.equal(analysis.quarterly[1].value, null);
  assert.equal(analysis.quarterly[2].qoqChange, null);
  assert.equal(analysis.ttm[1].value, null);
});
