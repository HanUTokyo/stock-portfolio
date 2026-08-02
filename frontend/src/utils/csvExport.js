function toNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function csvCell(value) {
  if (value == null) return '';
  const text = String(value);
  if (!/[",\n\r]/.test(text)) return text;
  return `"${text.replaceAll('"', '""')}"`;
}

function csvLine(values) {
  return values.map(csvCell).join(',');
}

function localDateParts(value) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return null;
  return {
    year: date.getFullYear(),
    month: String(date.getMonth() + 1).padStart(2, '0'),
    day: String(date.getDate()).padStart(2, '0')
  };
}

export function formatImportDateTime(value) {
  const parts = localDateParts(value);
  if (!parts) return '';
  return `${parts.year}-${parts.month}-${parts.day} 0:00:00`;
}

export function formatDateOnly(value) {
  if (!value) return '';
  const raw = String(value);
  if (/^\d{4}-\d{2}-\d{2}/.test(raw)) return raw.slice(0, 10);
  const parts = localDateParts(value);
  return parts ? `${parts.year}-${parts.month}-${parts.day}` : '';
}

export function buildTransactionsImportCsv(transactions) {
  const rows = [
    csvLine(['executedAt', 'symbol', 'type', 'quantity', 'price', 'note'])
  ];

  transactions.forEach((txn) => {
    rows.push(csvLine([
      formatImportDateTime(txn.executedAt),
      txn.symbol,
      txn.type,
      toNumber(txn.quantity).toFixed(8),
      toNumber(txn.price).toFixed(4),
      txn.note || ''
    ]));
  });

  return `${rows.join('\n')}\n`;
}

export function buildDividendsCsv(dividends) {
  const rows = [
    csvLine(['symbol', 'type', 'amount', 'currency', 'note', 'paidDate'])
  ];

  dividends.forEach((dividend) => {
    rows.push(csvLine([
      dividend.symbol,
      'DIVIDEND',
      toNumber(dividend.amount).toFixed(4),
      'USD',
      '',
      formatDateOnly(dividend.paidDate)
    ]));
  });

  return `${rows.join('\n')}\n`;
}

export function buildCashAdjustmentsCsv(adjustments) {
  const rows = [csvLine(['occurredAt', 'type', 'amount'])];
  adjustments.forEach((adjustment) => {
    rows.push(csvLine([
      formatImportDateTime(adjustment.occurredAt),
      adjustment.type,
      toNumber(adjustment.amount).toFixed(4)
    ]));
  });
  return `${rows.join('\n')}\n`;
}

export function buildClassificationsCsv(rows) {
  const values = [csvLine(['symbol', 'assetClass', 'instrumentType', 'underlying', 'sector', 'region'])];
  rows.forEach((row) => {
    const position = row.position || {};
    values.push(csvLine([
      row.symbol,
      position.assetClass || '',
      position.instrumentType || '',
      position.underlying || '',
      position.sector || '',
      position.region || ''
    ]));
  });
  return `${values.join('\n')}\n`;
}

export function downloadCsv(filename, csv) {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  window.URL.revokeObjectURL(url);
}
