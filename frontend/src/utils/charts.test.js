import assert from 'node:assert/strict';
import test from 'node:test';
import { buildComparisonChart } from './charts.js';

test('comparison chart fills internal PE gaps with zero after a PE series starts', () => {
  const chart = buildComparisonChart(
    [
      { tradeDate: '2025-01-02', closePrice: 10 },
      { tradeDate: '2025-01-03', closePrice: 11 },
      { tradeDate: '2025-01-06', closePrice: 12 }
    ],
    [
      { tradeDate: '2025-01-02', ttmPe: 20, quarterlyPe: null, forwardPe: null },
      { tradeDate: '2025-01-06', ttmPe: 22, quarterlyPe: null, forwardPe: null }
    ],
    { ttmPe: true, quarterlyPe: false, forwardPe: false }
  );

  assert.equal(chart.hasData, true);
  assert.equal(chart.pricePoints.length, 3);
  assert.equal(chart.ttmPePoints.length, 3);
  assert.doesNotMatch(chart.ttmPePath, /^M .+ M /);
});

test('comparison chart can render price-only ranges', () => {
  const chart = buildComparisonChart(
    [{ tradeDate: '2025-01-02', closePrice: 10 }],
    [],
    { ttmPe: true, quarterlyPe: true, forwardPe: true }
  );

  assert.equal(chart.hasData, true);
  assert.equal(chart.pricePoints.length, 1);
  assert.equal(chart.hasPeData, false);
});

test('comparison chart renders non-GAAP TTM PE as its own series', () => {
  const chart = buildComparisonChart(
    [
      { tradeDate: '2025-01-02', closePrice: 100 },
      { tradeDate: '2025-01-03', closePrice: 101 }
    ],
    [
      { tradeDate: '2025-01-02', ttmPe: 1200, nonGaapTtmPe: 55, quarterlyPe: null, forwardPe: null },
      { tradeDate: '2025-01-03', ttmPe: 1100, nonGaapTtmPe: 56, quarterlyPe: null, forwardPe: null }
    ],
    { ttmPe: false, nonGaapTtmPe: true, quarterlyPe: false, forwardPe: false }
  );

  assert.equal(chart.hasData, true);
  assert.equal(chart.nonGaapTtmPePoints.length, 2);
  assert.equal(chart.ttmPePoints.length, 0);
  assert.equal(chart.peMax, 56);
});
