import assert from 'node:assert/strict';
import test from 'node:test';
import {
  calculateCagr,
  calculateCashFlowAnalysis,
  calculateFcfAcceleration,
  calculateFcfGrowth,
  calculateFcfStability,
  classifyCashFlowTrend
} from './cashFlowAnalysis.js';

const rows = [
  { asOfDate: '2021-12-31', cashFlow: 140, capex: 40, fcf: 100, adjustedFcf: 105, revenue: 500, netIncome: 80 },
  { asOfDate: '2022-03-31', cashFlow: 150, capex: 40, fcf: 110, adjustedFcf: 110, revenue: 520, netIncome: 90 },
  { asOfDate: '2022-06-30', cashFlow: 170, capex: 45, fcf: 125, adjustedFcf: 125, revenue: 540, netIncome: 100 },
  { asOfDate: '2022-09-30', cashFlow: 190, capex: 50, fcf: 140, adjustedFcf: 140, revenue: 560, netIncome: 110 },
  { asOfDate: '2022-12-31', cashFlow: 210, capex: 55, fcf: 155, adjustedFcf: 155, revenue: 580, netIncome: 120 },
  { asOfDate: '2023-03-31', cashFlow: 230, capex: 60, fcf: 170, adjustedFcf: 170, revenue: 600, netIncome: 130 },
  { asOfDate: '2023-06-30', cashFlow: 250, capex: 65, fcf: 185, adjustedFcf: 185, revenue: 620, netIncome: 140 },
  { asOfDate: '2023-09-30', cashFlow: 270, capex: 70, fcf: 200, adjustedFcf: 200, revenue: 640, netIncome: 150 },
  { asOfDate: '2023-12-31', cashFlow: 290, capex: 75, fcf: 215, adjustedFcf: 215, revenue: 660, netIncome: 160 }
];

test('FCF growth uses absolute previous FCF as denominator', () => {
  assert.equal(calculateFcfGrowth(120, 100), 20);
  assert.equal(calculateFcfGrowth(-80, -100), 20);
});

test('FCF acceleration is current growth minus previous growth', () => {
  assert.equal(calculateFcfAcceleration(8, 5), 3);
});

test('cash flow analysis calculates TTM quality metrics from last four quarters', () => {
  const analysis = calculateCashFlowAnalysis(rows);
  const latest = analysis.latest;
  assert.equal(latest.ttmAdjustedFcf, 770);
  assert.equal(Number(latest.fcfMargin.toFixed(6)), Number(((770 / 2520) * 100).toFixed(6)));
  assert.equal(Number(latest.capexIntensity.toFixed(6)), Number(((270 / 2520) * 100).toFixed(6)));
});

test('CAGR requires positive start and end values', () => {
  assert.equal(calculateCagr(100, 121, 2).toFixed(6), '10.000000');
  assert.equal(calculateCagr(-100, 121, 2), null);
});

test('FCF stability returns coefficient of variation', () => {
  assert.equal(calculateFcfStability([100, 100, 100, 100]), 0);
});

test('valuation metrics use market cap and TTM adjusted FCF when shares are available', () => {
  const analysis = calculateCashFlowAnalysis(rows, { latestPrice: 10, sharesOutstanding: 1000 });
  assert.equal(Number(analysis.valuation.fcfYield.toFixed(6)), 7.7);
  assert.equal(Number(analysis.valuation.priceToFcf.toFixed(6)), Number((10000 / 770).toFixed(6)));
});

test('TTM adjusted FCF series includes first and second derivatives', () => {
  const analysis = calculateCashFlowAnalysis(rows);
  assert.equal(analysis.ttm.at(-1).value, 770);
  assert.notEqual(analysis.ttm.at(-1).growth, null);
  assert.notEqual(analysis.ttm.at(-1).acceleration, null);
});

test('cash flow trend classification marks expansion, compression and flat', () => {
  assert.equal(classifyCashFlowTrend(10.3, 10), 'expansion');
  assert.equal(classifyCashFlowTrend(9.7, 10), 'compression');
  assert.equal(classifyCashFlowTrend(10.05, 10), 'flat');
});

test('cash flow analysis preserves missing quarter gaps and breaks QoQ derivatives', () => {
  const analysis = calculateCashFlowAnalysis([
    { asOfDate: '2025-03-31', adjustedFcf: 100, fcf: 100 },
    { asOfDate: '2025-06-30', missing: true },
    { asOfDate: '2025-09-30', adjustedFcf: 140, fcf: 140 }
  ]);

  assert.equal(analysis.quarterly.length, 3);
  assert.equal(analysis.quarterly[1].missing, true);
  assert.equal(analysis.quarterly[1].growth, null);
  assert.equal(analysis.quarterly[2].growth, null);
  assert.equal(analysis.ttm[1].value, null);
});
