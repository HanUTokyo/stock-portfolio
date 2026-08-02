import assert from 'node:assert/strict';
import test from 'node:test';
import {
  calculateCapitalEfficiencyAnalysis,
  calculateIncrementalRoic,
  calculateNopat,
  calculateRoe,
  calculateRoic,
  rollingAverage,
  safeDivide
} from './capitalEfficiencyAnalysis.js';

test('ROE calculation uses net income divided by equity', () => {
  assert.equal(calculateRoe(20, 100), 20);
});

test('ROIC calculation uses NOPAT divided by invested capital', () => {
  assert.equal(calculateRoic(15, 100), 15);
});

test('5Y average rolling window requires full 20 quarterly points', () => {
  const values = Array.from({ length: 21 }, (_, index) => index + 1);
  const averages = rollingAverage(values, 20);
  assert.equal(averages[18], null);
  assert.equal(averages[19], 10.5);
  assert.equal(averages[20], 11.5);
});

test('rolling average can tolerate sparse windows with minimum observation count', () => {
  const values = Array.from({ length: 20 }, (_, index) => (index < 2 ? null : index + 1));
  const averages = rollingAverage(values, 20, 16);
  assert.equal(averages[19], 11.5);
});

test('incremental ROIC calculates normal case', () => {
  const result = calculateIncrementalRoic(120, 100, 1200, 1000);
  assert.equal(result.status, 'normal');
  assert.equal(result.value, 10);
});

test('incremental ROIC rejects zero denominator', () => {
  const result = calculateIncrementalRoic(120, 100, 1000, 1000);
  assert.equal(result.value, null);
  assert.equal(result.status, 'abnormal');
});

test('incremental ROIC rejects negative invested capital change', () => {
  const result = calculateIncrementalRoic(120, 100, 900, 1000);
  assert.equal(result.value, null);
  assert.equal(result.status, 'abnormal');
});

test('missing NOPAT or invested capital returns unavailable', () => {
  const result = calculateIncrementalRoic(null, 100, 1200, 1000);
  assert.equal(result.value, null);
  assert.equal(result.status, 'unavailable');
});

test('safeDivide never returns NaN or Infinity', () => {
  assert.equal(safeDivide(10, 0), null);
  assert.equal(safeDivide(10, null), null);
});

test('capital efficiency analysis handles missing fields without NaN', () => {
  const analysis = calculateCapitalEfficiencyAnalysis([
    { asOfDate: '2026-03-31', roe: null, roic: null, revenue: null, netIncome: null }
  ]);
  assert.equal(analysis.latest, null);
  assert.equal(Number.isFinite(analysis.series[0].netProfitMargin), false);
});

test('capital efficiency analysis derives 3Y incremental ROIC', () => {
  const rows = Array.from({ length: 16 }, (_, index) => ({
    asOfDate: `${2023 + Math.floor(index / 4)}-${String((index % 4) * 3 + 3).padStart(2, '0')}-31`,
    operatingIncome: index < 4 ? 25 : 30,
    taxProvision: index < 4 ? 5 : 6,
    pretaxIncome: index < 4 ? 25 : 30,
    investedCapital: index < 12 ? 1000 : 1200,
    stockholdersEquity: 800,
    totalDebt: 250,
    cashAndEquivalents: 50,
    revenue: 100,
    netIncome: 20,
    adjustedFcf: 18
  }));
  const analysis = calculateCapitalEfficiencyAnalysis(rows);
  assert.equal(analysis.latest.incrementalRoic3y.status, 'normal');
  assert.equal(analysis.latest.incrementalRoic3y.value, 8);
});

test('NOPAT uses operating income and effective tax rate', () => {
  assert.equal(calculateNopat(100, 20, 100), 80);
});

test('NOPAT treats tax benefits as zero tax rate', () => {
  assert.equal(calculateNopat(100, -20, 100), 100);
});

test('NOPAT floors negative operating return at zero for capital return charts', () => {
  assert.equal(calculateNopat(-90, -203, -8), 0);
});

test('NOPAT floors negative operating return even when tax detail is missing', () => {
  assert.equal(calculateNopat(-90, null, null), 0);
});

test('NOPAT uses zero tax rate when operating income is positive but pretax income is not', () => {
  assert.equal(calculateNopat(100, -20, -10), 100);
});

test('NOPAT falls back to net income plus tax provision when pretax income is missing', () => {
  assert.equal(calculateNopat(100, 20, null, 80), 80);
});

test('capital efficiency analysis preserves missing quarter slots without deriving bars', () => {
  const analysis = calculateCapitalEfficiencyAnalysis([
    { asOfDate: '2025-03-31', roe: 12, roic: 9 },
    { asOfDate: '2025-06-30', missing: true },
    { asOfDate: '2025-09-30', roe: 14, roic: 10 }
  ]);

  assert.equal(analysis.series.length, 3);
  assert.equal(analysis.series[1].missing, true);
  assert.equal(analysis.series[1].roe, null);
  assert.equal(analysis.series[1].roic, null);
  assert.equal(analysis.latest.date, '2025-09-30');
});
