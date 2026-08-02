import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildDividendsCsv,
  buildTransactionsImportCsv,
  formatImportDateTime
} from './csvExport.js';

test('transaction CSV export matches import column order', () => {
  const csv = buildTransactionsImportCsv([
    {
      executedAt: '2026-05-17T12:34:56.000Z',
      symbol: 'AAPL',
      type: 'BUY',
      quantity: 3,
      price: 181.23456,
      note: 'core, long "term"'
    }
  ]);

  const lines = csv.trim().split('\n');
  assert.equal(lines[0], 'executedAt,symbol,type,quantity,price,note');
  assert.match(lines[1], /^\d{4}-\d{2}-\d{2} 0:00:00,AAPL,BUY,3\.00000000,181\.2346,/);
  assert.match(lines[1], /"core, long ""term"""/);
});

test('dividend CSV export uses dividend import-compatible columns', () => {
  const csv = buildDividendsCsv([
    {
      paidDate: '2026-05-01',
      symbol: 'KO',
      amount: 12.5
    }
  ]);

  assert.equal(csv.trim(), [
    'symbol,type,amount,currency,note,paidDate',
    'KO,DIVIDEND,12.5000,USD,,2026-05-01'
  ].join('\n'));
});

test('invalid import datetime values export as blank', () => {
  assert.equal(formatImportDateTime('not-a-date'), '');
});
