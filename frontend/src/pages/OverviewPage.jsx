import { useEffect, useMemo, useState } from 'react';
import { buildAssetChart, formatCurrency } from '../utils/charts';
import DateInput from '../components/DateInput';

function toNumber(value) {
  if (value == null || Number.isNaN(Number(value))) return 0;
  return Number(value);
}

function buildAllocation(holdings, dividends, cashBalance) {
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
        isCash: false,
        quantity: toNumber(h.quantity),
        averageCost: toNumber(h.averageCost),
        totalCost,
        marketValue: toNumber(h.marketValue),
        unrealizedPnl: toNumber(h.unrealizedPnl),
        latestPe: h.latestPe == null ? null : toNumber(h.latestPe),
        dividendIncome,
        yieldPct: totalCost > 0 ? (dividendIncome / totalCost) * 100 : 0,
        returnPct: totalCost > 0 ? (toNumber(h.unrealizedPnl) / totalCost) * 100 : 0
      };
    })
    .filter((h) => h.marketValue > 0);

  if (toNumber(cashBalance) > 0) {
    withValue.push({
      symbol: 'CASH',
      isCash: true,
      quantity: 0,
      averageCost: 0,
      totalCost: toNumber(cashBalance),
      marketValue: toNumber(cashBalance),
      unrealizedPnl: 0,
      latestPe: null,
      latestPeg: null,
      dividendIncome: 0,
      yieldPct: 0,
      returnPct: 0
    });
  }

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

function sortedTransactions(transactions) {
  return [...transactions].sort((a, b) => {
    const timeDiff = new Date(a.executedAt).getTime() - new Date(b.executedAt).getTime();
    if (timeDiff !== 0) return timeDiff;
    return toNumber(a.id) - toNumber(b.id);
  });
}

function buildPositionGroups(holdings, transactions) {
  const currentRows = holdings
    .filter((item) => toNumber(item.quantity) > 0)
    .map((item) => ({
      symbol: String(item.symbol || '').toUpperCase(),
      quantity: toNumber(item.quantity),
      averageCost: toNumber(item.averageCost),
      marketValue: toNumber(item.marketValue),
      unrealizedPnl: toNumber(item.unrealizedPnl)
    }))
    .sort((a, b) => a.symbol.localeCompare(b.symbol));

  const currentSymbols = new Set(currentRows.map((item) => item.symbol));
  const allSymbols = new Set(currentRows.map((item) => item.symbol));
  transactions.forEach((txn) => {
    const symbol = String(txn.symbol || '').toUpperCase();
    if (symbol) allSymbols.add(symbol);
  });

  const txBySymbol = transactions.reduce((acc, txn) => {
    const symbol = String(txn.symbol || '').toUpperCase();
    if (!symbol) return acc;
    if (!acc[symbol]) acc[symbol] = [];
    acc[symbol].push(txn);
    return acc;
  }, {});

  const pastRows = [...allSymbols]
    .filter((symbol) => !currentSymbols.has(symbol))
    .map((symbol) => {
      const symbolTx = txBySymbol[symbol] || [];
      const buyQty = symbolTx
        .filter((txn) => txn.type === 'BUY')
        .reduce((sum, txn) => sum + toNumber(txn.quantity), 0);
      const sellQty = symbolTx
        .filter((txn) => txn.type === 'SELL')
        .reduce((sum, txn) => sum + toNumber(txn.quantity), 0);
      const lastTradeAt = symbolTx.length
        ? symbolTx.reduce((latest, txn) => (new Date(txn.executedAt) > new Date(latest) ? txn.executedAt : latest), symbolTx[0].executedAt)
        : null;
      return { symbol, buyQty, sellQty, lastTradeAt };
    })
    .sort((a, b) => {
      if (!a.lastTradeAt && !b.lastTradeAt) return a.symbol.localeCompare(b.symbol);
      if (!a.lastTradeAt) return 1;
      if (!b.lastTradeAt) return -1;
      return new Date(b.lastTradeAt) - new Date(a.lastTradeAt);
    });

  return { currentRows, pastRows };
}

function buildRealizedGainRecords(transactions) {
  const ordered = sortedTransactions(transactions);
  const snapshots = new Map();
  const records = [];
  let cumulativeGain = 0;

  ordered.forEach((txn) => {
    const symbol = String(txn.symbol || '').toUpperCase();
    const quantity = toNumber(txn.quantity);
    const price = toNumber(txn.price);
    if (!symbol || quantity <= 0) return;

    const current = snapshots.get(symbol) || { quantity: 0, averageCost: 0 };
    if (txn.type === 'BUY') {
      const newQty = current.quantity + quantity;
      const weightedAverage = newQty <= 0
        ? 0
        : ((current.quantity * current.averageCost) + (quantity * price)) / newQty;
      snapshots.set(symbol, { quantity: newQty, averageCost: weightedAverage });
      return;
    }

    const costPerShare = current.averageCost;
    const realizedGain = quantity * (price - costPerShare);
    cumulativeGain += realizedGain;
    const remainingQuantity = current.quantity - quantity;
    snapshots.set(symbol, {
      quantity: Math.max(remainingQuantity, 0),
      averageCost: remainingQuantity <= 0 ? 0 : current.averageCost
    });

    records.push({
      id: txn.id,
      executedAt: txn.executedAt,
      symbol,
      quantity,
      sellPrice: price,
      costPerShare,
      realizedGain,
      cumulativeGain
    });
  });

  return records.sort((a, b) => new Date(b.executedAt) - new Date(a.executedAt));
}

function onCardKeyDown(event, onEnter) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    onEnter();
  }
}

export default function OverviewPage({
  summary,
  assetCurve,
  holdings,
  dividends,
  transactions,
  cashAdjustmentForm,
  setCashAdjustmentForm,
  onSubmitCashAdjustment
}) {
  const assetChart = buildAssetChart(assetCurve);
  const latestAssetPoint = useMemo(() => {
    if (!assetCurve?.length) return null;
    return assetCurve[assetCurve.length - 1];
  }, [assetCurve]);
  const latestCashBalance = useMemo(() => {
    return toNumber(latestAssetPoint?.cashBalance);
  }, [latestAssetPoint]);
  const latestTotalAssets = useMemo(() => {
    return toNumber(latestAssetPoint?.totalAssets);
  }, [latestAssetPoint]);
  const allocation = useMemo(() => buildAllocation(holdings, dividends, latestCashBalance), [holdings, dividends, latestCashBalance]);
  const [sort, setSort] = useState(defaultSort);
  const [topView, setTopView] = useState('ALL');
  const [showPositionModal, setShowPositionModal] = useState(false);
  const [showRealizedModal, setShowRealizedModal] = useState(false);
  const [positionView, setPositionView] = useState('CURRENT');
  const positionGroups = useMemo(() => buildPositionGroups(holdings, transactions), [holdings, transactions]);
  const realizedGainRecords = useMemo(() => buildRealizedGainRecords(transactions), [transactions]);

  useEffect(() => {
    if (!showPositionModal && !showRealizedModal) return undefined;
    function handleKeyDown(event) {
      if (event.key !== 'Escape') return;
      setShowPositionModal(false);
      setShowRealizedModal(false);
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [showPositionModal, showRealizedModal]);

  const sortedAllocation = useMemo(() => {
    return [...allocation].sort((a, b) => compareAllocation(a, b, sort));
  }, [allocation, sort]);

  const rankedByReturn = useMemo(() => {
    const ranked = allocation
      .filter((item) => !item.isCash)
      .sort((a, b) => b.returnPct - a.returnPct);
    if (topView === 'GAINERS') {
      return ranked.filter((item) => item.returnPct > 0);
    }
    if (topView === 'LOSERS') {
      return ranked.filter((item) => item.returnPct < 0).sort((a, b) => b.returnPct - a.returnPct);
    }
    return ranked;
  }, [allocation, topView]);
  const topScale = useMemo(() => {
    const maxAbs = rankedByReturn.reduce((max, item) => Math.max(max, Math.abs(item.returnPct)), 0);
    return Math.max(maxAbs, 1);
  }, [rankedByReturn]);

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
          <p>Total Assets</p>
          <h3>${latestTotalAssets || summary?.totalMarketValue || 0}</h3>
          <span>Stock MV ${summary?.totalMarketValue ?? 0}</span>
        </article>
        <article className="kpi-card">
          <p>Unrealized gain</p>
          <h3 className={toNumber(summary?.totalUnrealizedPnl) >= 0 ? 'positive' : 'negative'}>
            ${summary?.totalUnrealizedPnl ?? 0}
          </h3>
          <span>Cost Basis ${summary?.totalCostBasis ?? 0}</span>
        </article>
        <article
          className="kpi-card kpi-card-clickable"
          role="button"
          tabIndex={0}
          onClick={() => setShowRealizedModal(true)}
          onKeyDown={(event) => onCardKeyDown(event, () => setShowRealizedModal(true))}
        >
          <p>Realized gain</p>
          <h3 className={toNumber(summary?.totalRealizedGain) >= 0 ? 'positive' : 'negative'}>
            ${summary?.totalRealizedGain ?? 0}
          </h3>
          <span>Closed Transactions (Click for records)</span>
        </article>
        <article
          className="kpi-card kpi-card-clickable"
          role="button"
          tabIndex={0}
          onClick={() => setShowPositionModal(true)}
          onKeyDown={(event) => onCardKeyDown(event, () => setShowPositionModal(true))}
        >
          <p>Position Count</p>
          <h3>{summary?.currentHoldings ?? 0}</h3>
          <span>Historical Count {summary?.trackedSymbols ?? summary?.totalPositions ?? 0} (Click for details)</span>
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

              <path d={assetChart.costBasisLinePath} className="chart-line-cost-basis" />
              <path d={assetChart.portfolioLinePath} className="chart-line" />

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

              <circle cx={assetChart.lastX} cy={assetChart.lastPortfolioY} r="3.2" className="chart-end-dot" />
              <circle cx={assetChart.lastX} cy={assetChart.lastCostBasisY} r="2.6" className="chart-end-dot-cost-basis" />
              <g
                transform={`translate(${assetChart.chipX}, ${assetChart.portfolioChipY})`}
              >
                <rect rx="4" ry="4" width="132" height="22" className="line-end-chip" />
                <text x="8" y="15" className="line-end-chip-text">
                  Portfolio {formatCurrency(assetChart.lastPortfolioValue)}
                </text>
              </g>
              <g
                transform={`translate(${assetChart.chipX}, ${assetChart.costChipY})`}
              >
                <rect rx="4" ry="4" width="132" height="22" className="line-end-chip-cost-basis" />
                <text x="8" y="15" className="line-end-chip-text">
                  Cost {formatCurrency(assetChart.lastCostBasisValue)}
                </text>
              </g>
            </svg>
            <div className="legend-row">
              <span><i className="dot dot-portfolio" /> Portfolio</span>
              <span><i className="dot dot-cost-basis" /> Cost Basis</span>
            </div>
          </div>
        ) : (
          <p className="muted">No asset curve yet. Record transactions first.</p>
        )}
      </section>

      <section className="panel">
        <h2>Cash Position</h2>
        <p className={latestCashBalance >= 0 ? 'positive' : 'negative'}>
          Current Cash Balance: ${latestCashBalance.toFixed(2)}
        </p>
        <div className="cash-actions">
          <input
            type="number"
            min="0.01"
            step="0.01"
            placeholder="Amount"
            value={cashAdjustmentForm.amount}
            onChange={(e) => setCashAdjustmentForm({ ...cashAdjustmentForm, amount: e.target.value })}
          />
          <DateInput
            value={cashAdjustmentForm.tradeDate}
            onChange={(e) => setCashAdjustmentForm({ ...cashAdjustmentForm, tradeDate: e.target.value })}
          />
          <button type="button" onClick={() => onSubmitCashAdjustment('DEPOSIT')}>Add Cash</button>
          <button type="button" onClick={() => onSubmitCashAdjustment('WITHDRAWAL')}>Reduce Cash</button>
        </div>
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
                <th role="button" tabIndex={0} onClick={() => toggleSort('latestPe')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('latestPe')}>PE{sortMark('latestPe')}</th>
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
                  <td>{item.isCash ? '--' : item.quantity.toFixed(4)}</td>
                  <td>{item.isCash ? '--' : `$${item.averageCost.toFixed(4)}`}</td>
                  <td>${item.totalCost.toFixed(2)}</td>
                  <td>${item.marketValue.toFixed(2)}</td>
                  <td>{item.weight.toFixed(2)}%</td>
                  <td>{item.isCash ? '--' : `${item.yieldPct.toFixed(2)}%`}</td>
                  <td>{item.latestPe == null ? '--' : item.latestPe.toFixed(2)}</td>
                  <td className={item.unrealizedPnl >= 0 ? 'positive' : 'negative'}>
                    {item.isCash ? '--' : `${item.unrealizedPnl >= 0 ? '+' : ''}${item.unrealizedPnl.toFixed(2)}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <h2>Top Gainers & Losers</h2>
        <div className="rank-filter-tabs">
          <button type="button" className={topView === 'ALL' ? 'rank-tab active' : 'rank-tab'} onClick={() => setTopView('ALL')}>All</button>
          <button type="button" className={topView === 'GAINERS' ? 'rank-tab active' : 'rank-tab'} onClick={() => setTopView('GAINERS')}>Gainers</button>
          <button type="button" className={topView === 'LOSERS' ? 'rank-tab active' : 'rank-tab'} onClick={() => setTopView('LOSERS')}>Losers</button>
        </div>

        {rankedByReturn.length ? rankedByReturn.map((item) => {
          const percent = Math.abs(item.returnPct);
          const width = Math.max((percent / topScale) * 100, 1);
          const isPositive = item.returnPct >= 0;
          return (
            <div key={`rank-${item.symbol}`} className="rank-row">
              <span className="rank-symbol">{item.symbol}</span>
              <div className="rank-track">
                <div className={`rank-fill ${isPositive ? 'rank-fill-positive' : 'rank-fill-negative'}`} style={{ width: `${width}%` }} />
              </div>
              <strong className={isPositive ? 'positive' : 'negative'}>
                {isPositive ? '+' : ''}{item.returnPct.toFixed(2)}%
              </strong>
            </div>
          );
        }) : <p className="muted">No rows for selected filter.</p>}
      </section>

      {showPositionModal ? (
        <div className="modal-backdrop" onClick={() => setShowPositionModal(false)}>
          <div className="modal-card" role="dialog" aria-modal="true" aria-label="Position details" onClick={(event) => event.stopPropagation()}>
            <div className="modal-header">
              <h2>Position Count Details</h2>
              <button type="button" className="modal-close" onClick={() => setShowPositionModal(false)}>Close</button>
            </div>
            <div className="rank-filter-tabs">
              <button type="button" className={positionView === 'CURRENT' ? 'rank-tab active' : 'rank-tab'} onClick={() => setPositionView('CURRENT')}>
                Current Holdings ({positionGroups.currentRows.length})
              </button>
              <button type="button" className={positionView === 'PAST' ? 'rank-tab active' : 'rank-tab'} onClick={() => setPositionView('PAST')}>
                Past Holdings ({positionGroups.pastRows.length})
              </button>
            </div>
            {positionView === 'CURRENT' ? (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Symbol</th>
                      <th>Quantity</th>
                      <th>Average Cost</th>
                      <th>Market Value</th>
                      <th>Unrealized</th>
                    </tr>
                  </thead>
                  <tbody>
                    {positionGroups.currentRows.map((item) => (
                      <tr key={`current-${item.symbol}`}>
                        <td>{item.symbol}</td>
                        <td>{item.quantity.toFixed(4)}</td>
                        <td>${item.averageCost.toFixed(4)}</td>
                        <td>${item.marketValue.toFixed(2)}</td>
                        <td className={item.unrealizedPnl >= 0 ? 'positive' : 'negative'}>
                          {item.unrealizedPnl >= 0 ? '+' : ''}{item.unrealizedPnl.toFixed(2)}
                        </td>
                      </tr>
                    ))}
                    {!positionGroups.currentRows.length ? (
                      <tr>
                        <td colSpan={5} className="muted">No current holdings.</td>
                      </tr>
                    ) : null}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Symbol</th>
                      <th>Last Trade</th>
                      <th>Total Bought</th>
                      <th>Total Sold</th>
                    </tr>
                  </thead>
                  <tbody>
                    {positionGroups.pastRows.map((item) => (
                      <tr key={`past-${item.symbol}`}>
                        <td>{item.symbol}</td>
                        <td>{item.lastTradeAt ? new Date(item.lastTradeAt).toLocaleDateString() : '--'}</td>
                        <td>{item.buyQty.toFixed(4)}</td>
                        <td>{item.sellQty.toFixed(4)}</td>
                      </tr>
                    ))}
                    {!positionGroups.pastRows.length ? (
                      <tr>
                        <td colSpan={4} className="muted">No past holdings yet.</td>
                      </tr>
                    ) : null}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      ) : null}

      {showRealizedModal ? (
        <div className="modal-backdrop" onClick={() => setShowRealizedModal(false)}>
          <div className="modal-card" role="dialog" aria-modal="true" aria-label="Realized gain records" onClick={(event) => event.stopPropagation()}>
            <div className="modal-header">
              <h2>Realized Gain Records</h2>
              <button type="button" className="modal-close" onClick={() => setShowRealizedModal(false)}>Close</button>
            </div>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Symbol</th>
                    <th>Sold Qty</th>
                    <th>Sell Price</th>
                    <th>Cost/Share</th>
                    <th>Gain</th>
                    <th>Cumulative</th>
                  </tr>
                </thead>
                <tbody>
                  {realizedGainRecords.map((item) => (
                    <tr key={`realized-${item.id}`}>
                      <td>{new Date(item.executedAt).toLocaleDateString()}</td>
                      <td>{item.symbol}</td>
                      <td>{item.quantity.toFixed(4)}</td>
                      <td>${item.sellPrice.toFixed(4)}</td>
                      <td>${item.costPerShare.toFixed(4)}</td>
                      <td className={item.realizedGain >= 0 ? 'positive' : 'negative'}>
                        {item.realizedGain >= 0 ? '+' : ''}{item.realizedGain.toFixed(2)}
                      </td>
                      <td className={item.cumulativeGain >= 0 ? 'positive' : 'negative'}>
                        {item.cumulativeGain >= 0 ? '+' : ''}{item.cumulativeGain.toFixed(2)}
                      </td>
                    </tr>
                  ))}
                  {!realizedGainRecords.length ? (
                    <tr>
                      <td colSpan={7} className="muted">No realized gain records yet.</td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      ) : null}

    </>
  );
}
