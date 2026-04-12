import { useEffect, useMemo, useState } from 'react';
import { buildComparisonChart, formatCurrency } from '../utils/charts';
import DateInput from '../components/DateInput';

function buildHistoryRows(priceHistory, peHistory) {
  const byDate = new Map();

  priceHistory.forEach((point) => {
    const row = byDate.get(point.tradeDate) || { tradeDate: point.tradeDate, closePrice: null, trailingPe: null };
    row.closePrice = Number(point.closePrice);
    byDate.set(point.tradeDate, row);
  });

  peHistory.forEach((point) => {
    const row = byDate.get(point.tradeDate) || { tradeDate: point.tradeDate, closePrice: null, trailingPe: null };
    row.trailingPe = Number(point.trailingPe);
    byDate.set(point.tradeDate, row);
  });

  return [...byDate.values()].sort((a, b) => new Date(b.tradeDate) - new Date(a.tradeDate));
}

function normalizeSymbol(value) {
  return String(value || '').trim().toUpperCase();
}

function toNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function isoDate(value) {
  return String(value || '').slice(0, 10);
}

function calcPeMaxGapDays(peHistory) {
  if (peHistory.length < 2) return 0;
  const sorted = [...peHistory].sort((a, b) => new Date(a.tradeDate) - new Date(b.tradeDate));
  let maxGap = 0;
  for (let i = 1; i < sorted.length; i += 1) {
    const prev = new Date(`${sorted[i - 1].tradeDate}T00:00:00Z`).getTime();
    const curr = new Date(`${sorted[i].tradeDate}T00:00:00Z`).getTime();
    const gapDays = Math.round((curr - prev) / (24 * 60 * 60 * 1000));
    if (gapDays > maxGap) maxGap = gapDays;
  }
  return maxGap;
}

function buildHistoryAudit(historySymbol, historyFrom, historyTo, transactions, holdings, priceHistory, peHistory) {
  const symbol = normalizeSymbol(historySymbol);
  if (!symbol) return null;

  const symbolTransactions = transactions
    .filter((txn) => normalizeSymbol(txn.symbol) === symbol)
    .sort((a, b) => new Date(a.executedAt) - new Date(b.executedAt));

  const fromDate = historyFrom || '0001-01-01';
  const toDate = historyTo || '9999-12-31';
  const rangeTransactions = symbolTransactions.filter((txn) => {
    const date = isoDate(txn.executedAt);
    return date >= fromDate && date <= toDate;
  });

  let ledgerQuantity = 0;
  let averageCost = 0;
  let realizedGain = 0;
  let totalBoughtQty = 0;
  let totalSoldQty = 0;
  let totalBuyAmount = 0;
  let totalSellAmount = 0;

  symbolTransactions.forEach((txn) => {
    const quantity = toNumber(txn.quantity);
    const price = toNumber(txn.price);
    if (txn.type === 'BUY') {
      const nextQuantity = ledgerQuantity + quantity;
      averageCost = nextQuantity <= 0 ? 0 : ((ledgerQuantity * averageCost) + (quantity * price)) / nextQuantity;
      ledgerQuantity = nextQuantity;
      totalBoughtQty += quantity;
      totalBuyAmount += quantity * price;
      return;
    }

    realizedGain += quantity * (price - averageCost);
    ledgerQuantity = Math.max(ledgerQuantity - quantity, 0);
    if (ledgerQuantity === 0) averageCost = 0;
    totalSoldQty += quantity;
    totalSellAmount += quantity * price;
  });

  const currentHolding = holdings.find((item) => normalizeSymbol(item.symbol) === symbol);
  const holdingQuantity = toNumber(currentHolding?.quantity);
  const quantityDiff = holdingQuantity - ledgerQuantity;
  const isLedgerMatched = Math.abs(quantityDiff) < 0.0001;

  const priceDates = new Set(priceHistory.map((item) => item.tradeDate));
  const peDates = new Set(peHistory.map((item) => item.tradeDate));
  const txDatesInRange = [...new Set(rangeTransactions.map((txn) => isoDate(txn.executedAt)))];
  const missingPriceOnTxDate = txDatesInRange.filter((date) => !priceDates.has(date));
  const missingPeOnTxDate = txDatesInRange.filter((date) => !peDates.has(date));

  const invalidPeCount = peHistory.filter((item) => toNumber(item.trailingPe) <= 0).length;
  const peCoveragePct = priceHistory.length ? (peHistory.length / priceHistory.length) * 100 : 0;

  return {
    symbol,
    txCount: symbolTransactions.length,
    rangeTxCount: rangeTransactions.length,
    totalBoughtQty,
    totalSoldQty,
    totalBuyAmount,
    totalSellAmount,
    ledgerQuantity,
    holdingQuantity,
    quantityDiff,
    isLedgerMatched,
    averageCost,
    realizedGain,
    missingPriceOnTxDate,
    missingPeOnTxDate,
    peCoveragePct,
    invalidPeCount,
    peMaxGapDays: calcPeMaxGapDays(peHistory)
  };
}

export default function MarketDataPage({
  historySymbol,
  historyFrom,
  historyTo,
  historyLoading,
  historyRequested,
  priceHistory,
  peHistory,
  transactions,
  holdings,
  onLoadHistory
}) {
  const [draftSymbol, setDraftSymbol] = useState(historySymbol || '');
  const [draftFrom, setDraftFrom] = useState(historyFrom || '');
  const [draftTo, setDraftTo] = useState(historyTo || '');
  const symbolOptions = useMemo(() => {
    const set = new Set();
    transactions.forEach((txn) => {
      const symbol = normalizeSymbol(txn.symbol);
      if (symbol) set.add(symbol);
    });
    return [...set].sort((a, b) => a.localeCompare(b));
  }, [transactions]);

  useEffect(() => {
    const nextSymbol = normalizeSymbol(historySymbol || '');
    if (nextSymbol && symbolOptions.includes(nextSymbol)) {
      setDraftSymbol(nextSymbol);
      return;
    }
    setDraftSymbol(symbolOptions[0] || '');
    setDraftFrom(historyFrom || '');
    setDraftTo(historyTo || '');
  }, [historySymbol, historyFrom, historyTo, symbolOptions]);

  const comparisonChart = useMemo(() => buildComparisonChart(priceHistory, peHistory), [priceHistory, peHistory]);
  const historyRows = useMemo(() => buildHistoryRows(priceHistory, peHistory), [priceHistory, peHistory]);
  const historyAudit = useMemo(
    () => buildHistoryAudit(historySymbol, historyFrom, historyTo, transactions, holdings, priceHistory, peHistory),
    [historySymbol, historyFrom, historyTo, transactions, holdings, priceHistory, peHistory]
  );

  return (
    <>
      <section className="panel">
        <h2>History Filters</h2>
        <div className="history-controls">
          <select
            value={draftSymbol}
            onChange={(e) => setDraftSymbol(e.target.value)}
          >
            {!symbolOptions.length ? <option value="">No Symbol</option> : null}
            {symbolOptions.map((symbol) => (
              <option key={symbol} value={symbol}>{symbol}</option>
            ))}
          </select>
          <DateInput value={draftFrom} onChange={(e) => setDraftFrom(e.target.value)} />
          <DateInput value={draftTo} onChange={(e) => setDraftTo(e.target.value)} />
          <button
            type="button"
            disabled={!draftSymbol}
            onClick={() => onLoadHistory({ symbol: draftSymbol, from: draftFrom, to: draftTo })}
          >
            Load History
          </button>
        </div>
      </section>

      <section className="panel">
        <h2>Price vs PE</h2>
        {!historyRequested ? <p className="muted">Set filters and click Load History to show chart and table.</p> : null}
        {historyRequested && historyLoading ? <p>Loading history...</p> : null}
        {historyRequested && !historyLoading && comparisonChart.hasData ? (
          <div className="chart-wrap">
            <svg
              viewBox={`0 0 ${comparisonChart.width} ${comparisonChart.height}`}
              className="asset-chart"
              role="img"
              aria-label="Price and PE comparison"
            >
              {comparisonChart.yTicksPrice.map((tick) => (
                <g key={`price-tick-${tick.value}`}>
                  <line x1={comparisonChart.plotLeft} y1={tick.y} x2={comparisonChart.plotRight} y2={tick.y} className="chart-grid" />
                  <text x={comparisonChart.plotLeft - 8} y={tick.y + 4} textAnchor="end" className="chart-tick">
                    {tick.value.toFixed(2)}
                  </text>
                </g>
              ))}

              {comparisonChart.xTicks.map((tick) => (
                <g key={`x-tick-${tick.time}`}>
                  <line x1={tick.x} y1={comparisonChart.plotTop} x2={tick.x} y2={comparisonChart.plotBottom} className="chart-grid vertical-grid" />
                  <text
                    x={tick.x}
                    y={comparisonChart.plotBottom + 18}
                    textAnchor="middle"
                    className={`chart-tick-bottom ${/^\d{4}$/.test(tick.label) ? 'year-tick' : ''}`}
                  >
                    {tick.label}
                  </text>
                </g>
              ))}

              {comparisonChart.yearSeparators.map((separator) => (
                <line
                  key={`comparison-year-${separator.year}-${separator.date}`}
                  x1={separator.x}
                  y1={comparisonChart.plotTop}
                  x2={separator.x}
                  y2={comparisonChart.plotBottom}
                  className="year-separator"
                />
              ))}

              <line x1={comparisonChart.plotLeft} y1={comparisonChart.plotBottom} x2={comparisonChart.plotRight} y2={comparisonChart.plotBottom} className="chart-axis" />
              <line x1={comparisonChart.plotLeft} y1={comparisonChart.plotTop} x2={comparisonChart.plotLeft} y2={comparisonChart.plotBottom} className="chart-axis" />
              <line x1={comparisonChart.plotRight} y1={comparisonChart.plotTop} x2={comparisonChart.plotRight} y2={comparisonChart.plotBottom} className="chart-axis" />

              {comparisonChart.yTicksPe.map((tick) => (
                <text key={`pe-tick-${tick.value}`} x={comparisonChart.plotRight + 8} y={tick.y + 4} className="chart-tick">
                  {tick.value.toFixed(2)}
                </text>
              ))}

              <path d={comparisonChart.pricePath} className="chart-line-price" />
              <path d={comparisonChart.pePath} className="chart-line-pe" />
            </svg>
            <div className="legend-row">
              <span><i className="dot dot-price" /> Price (left axis)</span>
              <span><i className="dot dot-pe" /> PE (right axis)</span>
            </div>
            <p className="chart-caption">
              {comparisonChart.firstDate} ~ {comparisonChart.lastDate} | Price: {formatCurrency(comparisonChart.priceMin)} - {formatCurrency(comparisonChart.priceMax)} | PE: {formatCurrency(comparisonChart.peMin)} - {formatCurrency(comparisonChart.peMax)}
            </p>
          </div>
        ) : null}
        {historyRequested && !historyLoading && !comparisonChart.hasData ? (
          <p>No overlapping price/PE data in this range yet.</p>
        ) : null}

        {historyRequested && !historyLoading ? (
          <div className="table-wrap history-table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Close Price</th>
                  <th>Trailing PE</th>
                </tr>
              </thead>
              <tbody>
                {historyRows.map((row) => (
                  <tr key={row.tradeDate}>
                    <td>{row.tradeDate}</td>
                    <td>{row.closePrice == null ? '--' : `$${row.closePrice.toFixed(4)}`}</td>
                    <td>{row.trailingPe == null ? '--' : row.trailingPe.toFixed(4)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </section>

      {historyRequested && !historyLoading && historyAudit ? (
        <section className="panel">
          <h2>Historical Checks</h2>
          <div className="table-wrap">
            <table>
              <tbody>
                <tr>
                  <th>Symbol</th>
                  <td>{historyAudit.symbol}</td>
                  <th>Transactions</th>
                  <td>{historyAudit.txCount} (in range: {historyAudit.rangeTxCount})</td>
                </tr>
                <tr>
                  <th>Ledger Qty</th>
                  <td>{historyAudit.ledgerQuantity.toFixed(4)}</td>
                  <th>Current Holding Qty</th>
                  <td className={historyAudit.isLedgerMatched ? 'positive' : 'negative'}>
                    {historyAudit.holdingQuantity.toFixed(4)} ({historyAudit.quantityDiff >= 0 ? '+' : ''}{historyAudit.quantityDiff.toFixed(4)})
                  </td>
                </tr>
                <tr>
                  <th>Total Bought / Sold</th>
                  <td>{historyAudit.totalBoughtQty.toFixed(4)} / {historyAudit.totalSoldQty.toFixed(4)}</td>
                  <th>Ledger Avg Cost</th>
                  <td>${historyAudit.averageCost.toFixed(4)}</td>
                </tr>
                <tr>
                  <th>Buy / Sell Amount</th>
                  <td>${historyAudit.totalBuyAmount.toFixed(2)} / ${historyAudit.totalSellAmount.toFixed(2)}</td>
                  <th>Realized Gain (ledger)</th>
                  <td className={historyAudit.realizedGain >= 0 ? 'positive' : 'negative'}>
                    {historyAudit.realizedGain >= 0 ? '+' : ''}{historyAudit.realizedGain.toFixed(2)}
                  </td>
                </tr>
                <tr>
                  <th>PE Coverage</th>
                  <td>{historyAudit.peCoveragePct.toFixed(2)}%</td>
                  <th>Invalid PE Count</th>
                  <td className={historyAudit.invalidPeCount > 0 ? 'negative' : 'positive'}>{historyAudit.invalidPeCount}</td>
                </tr>
                <tr>
                  <th>Max PE Gap Days</th>
                  <td>{historyAudit.peMaxGapDays}</td>
                  <th>Missing Price/PE on Tx Dates</th>
                  <td>
                    {historyAudit.missingPriceOnTxDate.length} / {historyAudit.missingPeOnTxDate.length}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p className="chart-caption">
            Missing price dates: {historyAudit.missingPriceOnTxDate.length ? historyAudit.missingPriceOnTxDate.join(', ') : 'none'} | Missing PE dates: {historyAudit.missingPeOnTxDate.length ? historyAudit.missingPeOnTxDate.join(', ') : 'none'}
          </p>
        </section>
      ) : null}
    </>
  );
}
