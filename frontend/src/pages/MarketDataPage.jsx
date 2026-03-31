import { useEffect, useState } from 'react';
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

export default function MarketDataPage({
  historySymbol,
  historyFrom,
  historyTo,
  historyLoading,
  historyRequested,
  priceHistory,
  peHistory,
  onLoadHistory
}) {
  const [draftSymbol, setDraftSymbol] = useState(historySymbol || '');
  const [draftFrom, setDraftFrom] = useState(historyFrom || '');
  const [draftTo, setDraftTo] = useState(historyTo || '');

  useEffect(() => {
    setDraftSymbol(historySymbol || '');
    setDraftFrom(historyFrom || '');
    setDraftTo(historyTo || '');
  }, [historySymbol, historyFrom, historyTo]);

  const comparisonChart = buildComparisonChart(priceHistory, peHistory);
  const historyRows = buildHistoryRows(priceHistory, peHistory);

  return (
    <>
      <section className="panel">
        <h2>History Filters</h2>
        <div className="history-controls">
          <input
            placeholder="Symbol"
            value={draftSymbol}
            onChange={(e) => setDraftSymbol(e.target.value.toUpperCase())}
          />
          <DateInput value={draftFrom} onChange={(e) => setDraftFrom(e.target.value)} />
          <DateInput value={draftTo} onChange={(e) => setDraftTo(e.target.value)} />
          <button
            type="button"
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
                  <text x={tick.x} y={comparisonChart.plotBottom + 18} textAnchor="middle" className="chart-tick">
                    {tick.label}
                  </text>
                </g>
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
    </>
  );
}
