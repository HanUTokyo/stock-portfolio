import test from 'node:test';
import assert from 'node:assert/strict';
import { calculateCapitalAllocationSummary } from './capitalAllocationAnalysis.js';

test('uses only actual SEC shares observations and preserves missing report periods', () => {
  const result = calculateCapitalAllocationSummary({
    shareRepurchases: [
      { fiscalPeriodEnd: '2025-03-29', amount: '100', ttmAmount: null },
      { fiscalPeriodEnd: '2025-06-28', amount: '120', ttmAmount: '420' }
    ],
    sharesOutstanding: [
      { asOfDate: '2024-06-29', sharesOutstanding: '1000' },
      { asOfDate: '2025-06-28', sharesOutstanding: '960' }
    ]
  });

  assert.equal(result.shares.length, 2);
  assert.equal(result.tableRows.length, 3);
  assert.equal(result.yoyNetChange, -40);
  assert.equal(result.latestRepurchase.ttmAmount, '420');
});

test('does not manufacture a share-change result when a comparable SEC observation is absent', () => {
  const result = calculateCapitalAllocationSummary({
    sharesOutstanding: [{ asOfDate: '2025-06-28', sharesOutstanding: '960' }]
  });

  assert.equal(result.yearAgoShares, null);
  assert.equal(result.yoyNetChange, null);
});
