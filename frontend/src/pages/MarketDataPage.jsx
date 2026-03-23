import { buildComparisonChart, formatCurrency } from '../utils/charts';

export default function MarketDataPage({
  historySymbol,
  setHistorySymbol,
  historyFrom,
  setHistoryFrom,
  historyTo,
  setHistoryTo,
  historyLoading,
  priceHistory,
  peHistory,
  onLoadHistory,
  onRefreshPrices,
  onSyncMarketClose
}) {
  const comparisonChart = buildComparisonChart(priceHistory, peHistory);

  return (
    <>
      <section className="panel-grid market-actions-grid">
        <article className="panel">
          <h2>Market Actions</h2>
          <div className="action-grid">
            <button type="button" onClick={onRefreshPrices}>Manual Price/PE Refresh</button>
            <button type="button" onClick={onSyncMarketClose}>Manual Market Close Sync</button>
          </div>
        </article>

        <article className="panel">
          <h2>History Filters</h2>
          <div className="history-controls">
            <input
              placeholder="Symbol"
              value={historySymbol}
              onChange={(e) => setHistorySymbol(e.target.value.toUpperCase())}
            />
            <input type="date" value={historyFrom} onChange={(e) => setHistoryFrom(e.target.value)} />
            <input type="date" value={historyTo} onChange={(e) => setHistoryTo(e.target.value)} />
            <button type="button" onClick={onLoadHistory}>Refresh History</button>
          </div>
        </article>
      </section>

      <section className="panel">
        <h2>Price vs PE</h2>
        {historyLoading ? <p>Loading history...</p> : null}
        {comparisonChart.hasData ? (
          <div className="chart-wrap">
            <svg
              viewBox={`0 0 ${comparisonChart.width} ${comparisonChart.height}`}
              className="asset-chart"
              role="img"
              aria-label="Price and PE comparison"
            >
              <line x1="28" y1="272" x2="732" y2="272" className="chart-axis" />
              <line x1="28" y1="28" x2="28" y2="272" className="chart-axis" />
              <path d={comparisonChart.pricePath} className="chart-line-price" />
              <path d={comparisonChart.pePath} className="chart-line-pe" />
            </svg>
            <div className="legend-row">
              <span><i className="dot dot-price" /> Price (scaled)</span>
              <span><i className="dot dot-pe" /> PE (scaled)</span>
            </div>
            <p className="chart-caption">
              {comparisonChart.firstDate} ~ {comparisonChart.lastDate} | Price: {formatCurrency(comparisonChart.priceMin)} - {formatCurrency(comparisonChart.priceMax)} | PE: {formatCurrency(comparisonChart.peMin)} - {formatCurrency(comparisonChart.peMax)}
            </p>
          </div>
        ) : (
          <p>No overlapping price/PE data in this range yet.</p>
        )}
      </section>
    </>
  );
}
