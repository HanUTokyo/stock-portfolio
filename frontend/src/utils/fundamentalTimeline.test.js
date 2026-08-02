import assert from 'node:assert/strict';
import test from 'node:test';
import { fillQuarterlyFundamentalGaps, parseQuarter } from './fundamentalTimeline.js';

test('fills missing quarterly fundamental slots between first and last row', () => {
  const rows = fillQuarterlyFundamentalGaps([
    { asOfDate: '2025-03-31', basicEps: 1.1 },
    { asOfDate: '2025-09-30', basicEps: 1.3 }
  ]);

  assert.deepEqual(rows.map((row) => row.asOfDate), ['2025-03-31', '2025-06-30', '2025-09-30']);
  assert.equal(rows[1].missing, true);
  assert.equal(rows[1].basicEps, undefined);
});

test('does not add leading or trailing empty quarters outside loaded data', () => {
  const rows = fillQuarterlyFundamentalGaps([
    { asOfDate: '2025-06-30', basicEps: 1.2 },
    { asOfDate: '2025-09-30', basicEps: 1.3 }
  ]);

  assert.deepEqual(rows.map((row) => row.asOfDate), ['2025-06-30', '2025-09-30']);
});

test('maps near-quarter-end fiscal dates to the closest calendar quarter', () => {
  assert.deepEqual(parseQuarter('2023-04-01'), { year: 2023, quarter: 1, index: 2023 * 4 });
  assert.deepEqual(parseQuarter('2017-04-01'), { year: 2017, quarter: 1, index: 2017 * 4 });
  assert.deepEqual(parseQuarter('2026-03-28'), { year: 2026, quarter: 1, index: 2026 * 4 });
});

test('does not create false missing rows for fiscal quarter ends one day after calendar quarter end', () => {
  const rows = fillQuarterlyFundamentalGaps([
    { asOfDate: '2022-12-31', basicEps: 1.88 },
    { asOfDate: '2023-04-01', basicEps: 1.52 },
    { asOfDate: '2023-07-01', basicEps: 1.26 }
  ]);

  assert.deepEqual(rows.map((row) => row.asOfDate), ['2022-12-31', '2023-04-01', '2023-07-01']);
  assert.equal(rows.some((row) => row.asOfDate === '2023-03-31' && row.missing), false);
});
