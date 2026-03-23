import { useMemo, useState } from 'react';
import { buildAssetChart, formatCurrency } from '../utils/charts';

function toNumber(value) {
  if (value == null || Number.isNaN(Number(value))) return 0;
  return Number(value);
}

function buildAllocation(holdings, dividends) {
  const dividendBySymbol = dividends.reduce((acc, item) => {
    const key = String(item.symbol || '').toUpperCase();
    acc[key] = (acc[key] || 0) + toNumber(item.amount);
    return acc;
  }, {});

  const withValue = holdings
    .map((h) => {
      const symbol = String(h.symbol || '').toUpperCase();
      const totalCost = toNumber(h.costBasis);
      const dividendIncome = toNumber(dividendBySymbol[symbol] || 0);
      return {
        symbol,
        quantity: toNumber(h.quantity),
        averageCost: toNumber(h.averageCost),
        totalCost,
        marketValue: toNumber(h.marketValue),
        unrealizedPnl: toNumber(h.unrealizedPnl),
        dividendIncome,
        yieldPct: totalCost > 0 ? (dividendIncome / totalCost) * 100 : 0
      };
    })
    .filter((h) => h.marketValue > 0);

  const total = withValue.reduce((sum, h) => sum + h.marketValue, 0);
  const sorted = withValue.sort((a, b) => b.marketValue - a.marketValue);

  return sorted.map((item) => ({
    ...item,
    weight: total > 0 ? (item.marketValue / total) * 100 : 0
  }));
}

const defaultSort = { key: 'marketValue', direction: 'desc' };

function compareAllocation(a, b, sort) {
  let result = 0;
  if (sort.key === 'symbol') {
    result = a.symbol.localeCompare(b.symbol);
  } else {
    result = toNumber(a[sort.key]) - toNumber(b[sort.key]);
  }
  return sort.direction === 'asc' ? result : -result;
}

export default function OverviewPage({ summary, assetCurve, holdings, dividends }) {
  const assetChart = buildAssetChart(assetCurve);
  const allocation = useMemo(() => buildAllocation(holdings, dividends), [holdings, dividends]);
  const [sort, setSort] = useState(defaultSort);

  const sortedAllocation = useMemo(() => {
    return [...allocation].sort((a, b) => compareAllocation(a, b, sort));
  }, [allocation, sort]);

  const winners = [...allocation]
    .filter((item) => item.unrealizedPnl > 0)
    .sort((a, b) => b.unrealizedPnl - a.unrealizedPnl)
    .slice(0, 4);
  const losers = [...allocation]
    .filter((item) => item.unrealizedPnl < 0)
    .sort((a, b) => a.unrealizedPnl - b.unrealizedPnl)
    .slice(0, 4);

  function toggleSort(key) {
    setSort((prev) => {
      if (prev.key === key) {
        return { key, direction: prev.direction === 'asc' ? 'desc' : 'asc' };
      }
      return { key, direction: key === 'symbol' ? 'asc' : 'desc' };
    });
  }

  function sortMark(key) {
    if (sort.key !== key) return '';
    return sort.direction === 'asc' ? ' ▲' : ' ▼';
  }

  return (
    <>
      <section className="kpi-grid">
        <article className="kpi-card">
          <p>Total Portfolio Value</p>
          <h3>${summary?.totalMarketValue ?? 0}</h3>
          <span>Total Units {summary?.totalUnits ?? 0}</span>
        </article>
        <article className="kpi-card">
          <p>Unrealized gain</p>
          <h3 className={toNumber(summary?.totalUnrealizedPnl) >= 0 ? 'positive' : 'negative'}>
            ${summary?.totalUnrealizedPnl ?? 0}
          </h3>
          <span>Cost Basis ${summary?.totalCostBasis ?? 0}</span>
        </article>
        <article className="kpi-card">
          <p>Realized gain</p>
          <h3 className={toNumber(summary?.totalRealizedGain) >= 0 ? 'positive' : 'negative'}>
            ${summary?.totalRealizedGain ?? 0}
          </h3>
          <span>Closed Transactions</span>
        </article>
        <article className="kpi-card">
          <p>Position Count</p>
          <h3>{summary?.totalPositions ?? 0}</h3>
          <span>Tracked Symbols</span>
        </article>
      </section>

      <section className="panel">
        <h2>Total Asset Curve</h2>
        {assetChart.hasData ? (
          <div className="chart-wrap">
            <svg
              viewBox={`0 0 ${assetChart.width} ${assetChart.height}`}
              className="asset-chart"
              role="img"
              aria-label="Total asset chart with time on x-axis and dollar on y-axis"
            >
              <defs>
                <linearGradient id="assetAreaGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#2f7bff" stopOpacity="0.34" />
                  <stop offset="75%" stopColor="#1f5fd1" stopOpacity="0.14" />
                  <stop offset="100%" stopColor="#123a82" stopOpacity="0.04" />
                </linearGradient>
              </defs>

              <path d={assetChart.areaPath} className="asset-area" />
              <path d={assetChart.linePath} className="chart-line" />

              {assetChart.yTicks.map((tick, index) => (
                <g key={`y-${index}`}>
                  <text x={assetChart.plotRight + 14} y={tick.y + 4} className="chart-tick-right">
                    {Math.round(tick.value).toLocaleString(undefined, { minimumFractionDigits: 0 })}.00
                  </text>
                </g>
              ))}

              {assetChart.yearSeparators.map((separator) => (
                <line
                  key={`year-sep-${separator.year}`}
                  x1={separator.x}
                  y1={assetChart.plotTop}
                  x2={separator.x}
                  y2={assetChart.plotBottom}
                  className="year-separator"
                />
              ))}

              {assetChart.xTicks.map((tick, index) => (
                <g key={`x-${index}`}>
                  <text x={tick.x} y={assetChart.plotBottom + 28} textAnchor="middle" className={`chart-tick-bottom ${tick.isYearMark ? 'year-tick' : ''}`}>
                    {tick.label}
                  </text>
                </g>
              ))}

              <line
                x1={assetChart.plotLeft}
                y1={assetChart.plotBottom}
                x2={assetChart.plotRight}
                y2={assetChart.plotBottom}
                className="chart-axis-bottom"
              />

              <circle cx={assetChart.lastX} cy={assetChart.lastY} r="3.2" className="chart-end-dot" />
              <g
                transform={`translate(${Math.min(assetChart.lastX + 8, assetChart.plotRight - 56)}, ${Math.max(assetChart.lastY - 20, assetChart.plotTop + 2)})`}
              >
                <rect rx="4" ry="4" width="128" height="22" className="line-end-chip" />
                <text x="8" y="15" className="line-end-chip-text">
                  Portfolio {formatCurrency(assetChart.lastValue)}
                </text>
              </g>
            </svg>
          </div>
        ) : (
          <p className="muted">No asset curve yet. Record transactions first.</p>
        )}
      </section>

      <section className="panel">
        <h2>Portfolio Allocation</h2>
        <div className="allocation-chart">
          {allocation.map((item, index) => (
            <div key={item.symbol} className="allocation-bar-row">
              <div className="allocation-meta">
                <span className="legend-dot" style={{ background: `var(--palette-${(index % 10) + 1})` }} />
                <strong>{item.symbol}</strong>
                <span className="muted">{item.weight.toFixed(2)}%</span>
              </div>
              <div className="allocation-track">
                <div
                  className="allocation-fill"
                  style={{
                    width: `${Math.max(item.weight, 1)}%`,
                    background: `linear-gradient(90deg, var(--palette-${(index % 10) + 1}), var(--palette-${(index % 10) + 1}))`
                  }}
                />
              </div>
              <span className="allocation-value">${item.marketValue.toFixed(2)}</span>
            </div>
          ))}
        </div>

        <h2 className="subsection-title">Allocation Breakdown</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th role="button" tabIndex={0} onClick={() => toggleSort('symbol')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('symbol')}>Symbol{sortMark('symbol')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('quantity')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('quantity')}>Quantity{sortMark('quantity')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('averageCost')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('averageCost')}>Average Price{sortMark('averageCost')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('totalCost')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('totalCost')}>Total Cost{sortMark('totalCost')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('marketValue')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('marketValue')}>Market Value{sortMark('marketValue')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('weight')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('weight')}>Weight{sortMark('weight')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('yieldPct')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('yieldPct')}>Yield %{sortMark('yieldPct')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('unrealizedPnl')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('unrealizedPnl')}>Unrealized{sortMark('unrealizedPnl')}</th>
              </tr>
            </thead>
            <tbody>
              {sortedAllocation.map((item, index) => (
                <tr key={item.symbol}>
                  <td>
                    <span className="legend-dot" style={{ background: `var(--palette-${(index % 10) + 1})` }} />
                    {item.symbol}
                  </td>
                  <td>{item.quantity.toFixed(4)}</td>
                  <td>${item.averageCost.toFixed(4)}</td>
                  <td>${item.totalCost.toFixed(2)}</td>
                  <td>${item.marketValue.toFixed(2)}</td>
                  <td>{item.weight.toFixed(2)}%</td>
                  <td>{item.yieldPct.toFixed(2)}%</td>
                  <td className={item.unrealizedPnl >= 0 ? 'positive' : 'negative'}>{item.unrealizedPnl >= 0 ? '+' : ''}{item.unrealizedPnl.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel split-panel">
        <div>
          <h2>Top Gainers</h2>
          {winners.length ? winners.map((w) => (
            <div key={w.symbol} className="bar-row">
              <span>{w.symbol}</span>
              <div className="bar-track"><div className="bar-fill positive-fill" style={{ width: `${Math.min(w.weight * 3, 100)}%` }} /></div>
              <strong className="positive">+${w.unrealizedPnl.toFixed(2)}</strong>
            </div>
          )) : <p className="muted">No positive positions yet.</p>}
        </div>
        <div>
          <h2>Top Losers</h2>
          {losers.length ? losers.map((l) => (
            <div key={l.symbol} className="bar-row">
              <span>{l.symbol}</span>
              <div className="bar-track"><div className="bar-fill negative-fill" style={{ width: `${Math.min(Math.abs(l.weight * 3), 100)}%` }} /></div>
              <strong className="negative">${l.unrealizedPnl.toFixed(2)}</strong>
            </div>
          )) : <p className="muted">No negative positions yet.</p>}
        </div>
      </section>

    </>
  );
}
