import { useMemo, useState } from 'react';
import DateInput from '../components/DateInput';

const defaultSort = { key: 'date', direction: 'desc' };

function toNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function compareDividends(a, b, sort) {
  let result = 0;

  if (sort.key === 'date') {
    result = new Date(a.paidDate).getTime() - new Date(b.paidDate).getTime();
  } else if (sort.key === 'symbol') {
    result = String(a.symbol).localeCompare(String(b.symbol));
  } else if (sort.key === 'amount') {
    result = toNumber(a.amount) - toNumber(b.amount);
  }

  return sort.direction === 'asc' ? result : -result;
}

function buildDividendBars(monthlyDividends) {
  if (!monthlyDividends.length) {
    return { hasData: false };
  }

  const width = 900;
  const height = 320;
  const plotLeft = 30;
  const plotRight = width - 30;
  const plotTop = 20;
  const plotBottom = height - 44;
  const values = monthlyDividends.map((d) => toNumber(d.totalAmount));
  const maxValue = Math.max(...values, 1);
  const slot = (plotRight - plotLeft) / monthlyDividends.length;
  const barWidth = Math.max(Math.min(slot * 0.64, 44), 12);

  const bars = monthlyDividends.map((d, i) => {
    const value = toNumber(d.totalAmount);
    const h = (value / maxValue) * (plotBottom - plotTop);
    const x = plotLeft + i * slot + (slot - barWidth) / 2;
    const y = plotBottom - h;
    return {
      x,
      y,
      width: barWidth,
      height: h,
      value,
      monthLabel: d.month
    };
  });

  return {
    hasData: true,
    width,
    height,
    plotLeft,
    plotRight,
    plotBottom,
    bars
  };
}

export default function DividendsPage({
  monthlyDividends,
  dividends,
  dividendForm,
  setDividendForm,
  onAddDividend,
  onImportDividends
}) {
  const chart = useMemo(() => buildDividendBars(monthlyDividends), [monthlyDividends]);
  const [csvFile, setCsvFile] = useState(null);
  const [importResult, setImportResult] = useState(null);
  const [importLoading, setImportLoading] = useState(false);
  const [sort, setSort] = useState(defaultSort);
  const sortedDividends = useMemo(() => {
    return [...dividends].sort((a, b) => compareDividends(a, b, sort));
  }, [dividends, sort]);

  function toggleSort(key) {
    setSort((prev) => {
      if (prev.key === key) {
        return { key, direction: prev.direction === 'asc' ? 'desc' : 'asc' };
      }
      return { key, direction: key === 'date' ? 'desc' : 'asc' };
    });
  }

  function sortMark(key) {
    if (sort.key !== key) return '';
    return sort.direction === 'asc' ? ' ▲' : ' ▼';
  }

  async function handleImportCsv() {
    if (!csvFile) {
      return;
    }
    setImportLoading(true);
    try {
      const result = await onImportDividends(csvFile);
      setImportResult(result);
    } finally {
      setImportLoading(false);
    }
  }

  return (
    <>
      <section className="panel-grid">
        <article className="panel">
          <h2>Record Dividend</h2>
          <form className="stack-form" onSubmit={onAddDividend}>
            <input
              placeholder="Symbol"
              value={dividendForm.symbol}
              onChange={(e) => setDividendForm({ ...dividendForm, symbol: e.target.value })}
              required
            />
            <input
              type="number"
              min="0.0001"
              step="0.0001"
              placeholder="Amount"
              value={dividendForm.amount}
              onChange={(e) => setDividendForm({ ...dividendForm, amount: e.target.value })}
              required
            />
            <DateInput
              value={dividendForm.paidDate}
              onChange={(e) => setDividendForm({ ...dividendForm, paidDate: e.target.value })}
              required
            />
            <button type="submit">Save Dividend</button>
          </form>
        </article>

        <article className="panel">
          <h2>Import Dividend CSV</h2>
          <div className="stack-form">
            <input
              type="file"
              accept=".csv,text/csv"
              onChange={(e) => setCsvFile(e.target.files?.[0] ?? null)}
            />
            <button type="button" onClick={handleImportCsv} disabled={!csvFile || importLoading}>
              {importLoading ? 'Importing...' : 'Import CSV'}
            </button>
            {importResult ? (
              <div className="import-result">
                <p>Total rows: {importResult.totalRows}</p>
                <p>Imported: {importResult.importedRows}</p>
                <p>Skipped: {importResult.skippedRows}</p>
                <p>Failed: {importResult.failedRows}</p>
              </div>
            ) : null}
          </div>
        </article>
      </section>

      <section className="panel">
        <h2>Monthly Dividend Income</h2>
        {chart.hasData ? (
          <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart" role="img" aria-label="Monthly total dividends bar chart">
            <line x1={chart.plotLeft} y1={chart.plotBottom} x2={chart.plotRight} y2={chart.plotBottom} className="chart-axis-bottom" />
            {chart.bars.map((bar) => (
              <g key={bar.monthLabel}>
                <rect x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="3" className="dividend-bar" />
                <text x={bar.x + bar.width / 2} y={chart.plotBottom + 14} textAnchor="middle" className="chart-tick-bottom">
                  {bar.monthLabel.slice(5)}
                </text>
                <text x={bar.x + bar.width / 2} y={Math.max(bar.y - 6, 10)} textAnchor="middle" className="chart-tick-right">
                  ${bar.value.toFixed(2)}
                </text>
              </g>
            ))}
          </svg>
        ) : (
          <p className="muted">No dividends yet. Add records to generate the chart.</p>
        )}
      </section>

      <section className="panel">
        <h2>Dividend Records</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th role="button" tabIndex={0} onClick={() => toggleSort('date')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('date')}>Date{sortMark('date')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('symbol')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('symbol')}>Symbol{sortMark('symbol')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('amount')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('amount')}>Amount{sortMark('amount')}</th>
              </tr>
            </thead>
            <tbody>
              {sortedDividends.map((d) => (
                <tr key={d.id}>
                  <td>{new Date(d.paidDate).toLocaleDateString()}</td>
                  <td>{d.symbol}</td>
                  <td>${toNumber(d.amount).toFixed(4)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
