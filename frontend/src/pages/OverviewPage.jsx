import { useEffect, useMemo, useState } from 'react';
import {
  Download,
  MousePointerClick,
  NotebookPen
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { buildAssetChart, formatCurrency } from '../utils/charts';
import DateInput from '../components/DateInput';
import BottomSheet from '../components/BottomSheet';
import ChartFrame from '../components/ChartFrame';
import MobileLandscapeChart from '../components/MobileLandscapeChart';
import { RichTextNotePanel, richNoteToMarkdown } from '../components/RichTextEditor';
import ResponsiveDialog from '../components/ResponsiveDialog';
import useIsMobile from '../hooks/useIsMobile';

function toNumber(value) {
  if (value == null || Number.isNaN(Number(value))) return 0;
  return Number(value);
}

function formatQuantity(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '--';
  return n.toFixed(8).replace(/\.?0+$/, '');
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
        latestPrice: toNumber(h.latestPrice),
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
      latestPrice: 0,
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
const dipAlertMinDropPct = 5;
const dipAlertStrongDropPct = 10;
const dipAlertMaxWeightPct = 20;

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

function compareDescByDateThenId(a, b) {
  const timeDiff = new Date(b.executedAt).getTime() - new Date(a.executedAt).getTime();
  if (timeDiff !== 0) return timeDiff;
  return toNumber(b.id) - toNumber(a.id);
}

function findLastBuyTransaction(symbol, transactions) {
  return (transactions || [])
    .filter((txn) => String(txn.symbol || '').toUpperCase() === symbol && txn.type === 'BUY' && toNumber(txn.price) > 0)
    .sort(compareDescByDateThenId)[0] || null;
}

function findLastMonthReference(points) {
  if (!points?.length) return null;
  const target = new Date();
  target.setMonth(target.getMonth() - 1);
  target.setHours(23, 59, 59, 999);

  return [...points]
    .filter((point) => point?.tradeDate && toNumber(point.closePrice) > 0 && new Date(point.tradeDate).getTime() <= target.getTime())
    .sort((a, b) => new Date(b.tradeDate).getTime() - new Date(a.tradeDate).getTime())[0] || null;
}

function buildDipAlertSources(item, transactions, dipPriceHistoryBySymbol) {
  const symbol = item.symbol;
  const currentPrice = item.latestPrice > 0
    ? item.latestPrice
    : item.quantity > 0 ? item.marketValue / item.quantity : 0;
  if (!symbol || item.isCash || currentPrice <= 0) return [];

  const sources = [];
  const lastBuy = findLastBuyTransaction(symbol, transactions);
  if (lastBuy) {
    const referencePrice = toNumber(lastBuy.price);
    sources.push({
      key: 'last-buy',
      label: 'Last buy',
      referenceDate: lastBuy.executedAt,
      referencePrice,
      dropPct: ((referencePrice - currentPrice) / referencePrice) * 100
    });
  }

  const lastMonth = findLastMonthReference(dipPriceHistoryBySymbol?.[symbol] || []);
  if (lastMonth) {
    const referencePrice = toNumber(lastMonth.closePrice);
    sources.push({
      key: 'last-month',
      label: 'Last month',
      referenceDate: lastMonth.tradeDate,
      referencePrice,
      dropPct: ((referencePrice - currentPrice) / referencePrice) * 100
    });
  }

  return sources.filter((source) => Number.isFinite(source.dropPct));
}

function buildDipAlerts(allocation, transactions, dipPriceHistoryBySymbol) {
  return allocation.reduce((acc, item) => {
    if (item.isCash) return acc;
    const sources = buildDipAlertSources(item, transactions, dipPriceHistoryBySymbol);
    const inWeightLimit = item.weight <= dipAlertMaxWeightPct;
    const triggerSources = inWeightLimit
      ? sources.filter((source) => source.dropPct >= dipAlertMinDropPct)
      : [];
    const hasRecommendation = triggerSources.length > 0;
    const strongestObservedDrop = sources.length ? Math.max(...sources.map((source) => source.dropPct)) : null;
    const strongestDrop = hasRecommendation ? Math.max(...triggerSources.map((source) => source.dropPct)) : strongestObservedDrop;

    acc[item.symbol] = {
      symbol: item.symbol,
      severity: hasRecommendation ? (strongestDrop >= dipAlertStrongDropPct ? 'strong' : 'watch') : 'none',
      hasRecommendation,
      currentPrice: item.latestPrice > 0 ? item.latestPrice : item.quantity > 0 ? item.marketValue / item.quantity : 0,
      weight: item.weight,
      strongestDrop,
      triggerSources,
      allSources: sources,
      unavailableReason: !inWeightLimit
        ? `Position weight is above the ${dipAlertMaxWeightPct}% reminder limit.`
        : !sources.length
          ? 'No last BUY or one-month price reference is available yet.'
          : 'No yellow or red reminder threshold is currently met.'
    };
    return acc;
  }, {});
}

function onCardKeyDown(event, onEnter) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    onEnter();
  }
}

const overviewNoteColumns = [
  { noteType: 'USER', title: 'Monthly Ideas' },
  { noteType: 'AI', title: 'AI Suggestions' }
];

function buildOverviewNoteMap(overviewNotes) {
  return (overviewNotes || []).reduce((acc, item) => {
    acc[item.noteType] = item;
    return acc;
  }, {});
}

function buildStockNoteMap(stockNotes) {
  return (stockNotes || []).reduce((acc, item) => {
    const symbol = String(item.symbol || '').trim().toUpperCase();
    if (symbol) acc[symbol] = item;
    return acc;
  }, {});
}

function OverviewNotesPanel({ overviewNotes, onSaveOverviewNote, onExportPortfolioJsonV2 }) {
  const isMobile = useIsMobile();
  const noteMap = useMemo(() => buildOverviewNoteMap(overviewNotes), [overviewNotes]);
  const [drafts, setDrafts] = useState({ USER: '', AI: '' });
  const [savingType, setSavingType] = useState('');
  const [editingType, setEditingType] = useState('');
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    setDrafts({
      USER: noteMap.USER?.note || '',
      AI: noteMap.AI?.note || ''
    });
  }, [noteMap]);

  async function handleSave(noteType) {
    setSavingType(noteType);
    try {
      await onSaveOverviewNote(noteType, richNoteToMarkdown(drafts[noteType] || ''));
      setEditingType('');
    } finally {
      setSavingType('');
    }
  }

  async function handleExportJsonV2() {
    setSavingType('EXPORT_V2');
    try {
      for (const column of overviewNoteColumns) {
        const savedNote = noteMap[column.noteType];
        const draft = drafts[column.noteType] || '';
        if (draft !== (savedNote?.note || '')) {
          await onSaveOverviewNote(column.noteType, richNoteToMarkdown(draft));
        }
      }
      await onExportPortfolioJsonV2();
    } finally {
      setSavingType('');
    }
  }

  const noteColumns = overviewNoteColumns.map((column) => {
        const savedNote = noteMap[column.noteType];
        const isDirty = (drafts[column.noteType] || '') !== (savedNote?.note || '');
        const isSaving = savingType === column.noteType;
        const isExportingV2 = savingType === 'EXPORT_V2';
        const isEditing = editingType === column.noteType;
        return (
          <article key={column.noteType} className="panel overview-note-column">
            <RichTextNotePanel
              title={column.title}
              value={savedNote?.note || ''}
              draft={drafts[column.noteType] || ''}
              onChange={(value) => setDrafts((current) => ({ ...current, [column.noteType]: value }))}
              isEditing={isEditing}
              isDirty={isDirty}
              saving={isSaving}
              disabled={Boolean(savingType) && !isSaving}
              onEdit={() => setEditingType(column.noteType)}
              onCancel={() => {
                setDrafts((current) => ({ ...current, [column.noteType]: savedNote?.note || '' }));
                setEditingType('');
              }}
              onSave={() => handleSave(column.noteType)}
              placeholder={column.noteType === 'AI' ? 'Paste AI suggestions here...' : 'Write trading ideas here...'}
              meta={<>Last saved: {savedNote?.updatedAt ? new Date(savedNote.updatedAt).toLocaleString() : 'never'}</>}
              extraActions={column.noteType === 'AI' ? (
                <button type="button" className="overview-export-button" disabled={Boolean(savingType)} onClick={handleExportJsonV2}>
                  <Download size={16} aria-hidden="true" />
                  <span>{isExportingV2 ? 'Exporting' : 'Export JSON'}</span>
                </button>
              ) : null}
            />
          </article>
        );
      });

  if (!isMobile) {
    return <div className="overview-notes-grid overview-notes-panel">{noteColumns}</div>;
  }

  return (
    <>
      <section className="panel overview-notes-mobile-launcher">
        <div>
          <p className="eyebrow">Research log</p>
          <h2>Ideas &amp; AI notes</h2>
          <p className="muted">Keep the portfolio screen focused. Open your thinking when you need it.</p>
        </div>
        <button type="button" className="icon-button" onClick={() => setMobileOpen(true)} aria-label="Open ideas and AI notes" title="Open notes">
          <NotebookPen size={19} aria-hidden="true" />
        </button>
      </section>
      <BottomSheet open={mobileOpen} title="Ideas & AI notes" onClose={() => setMobileOpen(false)} fullHeight>
        <div className="overview-notes-grid overview-notes-sheet">{noteColumns}</div>
      </BottomSheet>
    </>
  );
}

export default function OverviewPage({
  summary,
  assetCurve,
  holdings,
  dipPriceHistoryBySymbol,
  dividends,
  overviewNotes,
  stockNotes,
  transactions,
  transactionForm,
  setTransactionForm,
  onRecordTransaction,
  cashAdjustmentForm,
  setCashAdjustmentForm,
  onSubmitCashAdjustment,
  onSaveOverviewNote,
  onExportPortfolioJsonV2
}) {
  const { t } = useTranslation();
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
  const [showAllMobileAllocation, setShowAllMobileAllocation] = useState(false);
  const [activeDipAlert, setActiveDipAlert] = useState(null);
  const [positionView, setPositionView] = useState('CURRENT');
  const positionGroups = useMemo(() => buildPositionGroups(holdings, transactions), [holdings, transactions]);
  const realizedGainRecords = useMemo(() => buildRealizedGainRecords(transactions), [transactions]);
  const dipAlertsBySymbol = useMemo(
    () => buildDipAlerts(allocation, transactions, dipPriceHistoryBySymbol),
    [allocation, transactions, dipPriceHistoryBySymbol]
  );
  const stockNoteBySymbol = useMemo(() => buildStockNoteMap(stockNotes), [stockNotes]);
  const activeStockNote = activeDipAlert ? stockNoteBySymbol[activeDipAlert.symbol] : null;
  const activeStockNoteText = activeStockNote?.note?.trim() || '';

  const sortedAllocation = useMemo(() => {
    return [...allocation].sort((a, b) => compareAllocation(a, b, sort));
  }, [allocation, sort]);

  const mobileAllocation = useMemo(
    () => sortedAllocation.filter((item) => !item.isCash),
    [sortedAllocation]
  );

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
          <h3>${formatCurrency(latestTotalAssets || summary?.totalMarketValue || 0)}</h3>
          <span>Stock MV ${formatCurrency(summary?.totalMarketValue ?? 0)}</span>
        </article>
        <article className="kpi-card">
          <p>Unrealized gain</p>
          <h3 className={toNumber(summary?.totalUnrealizedPnl) >= 0 ? 'positive' : 'negative'}>
            ${formatCurrency(summary?.totalUnrealizedPnl ?? 0)}
          </h3>
          <span>Cost Basis ${formatCurrency(summary?.totalCostBasis ?? 0)}</span>
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
            ${formatCurrency(summary?.totalRealizedGain ?? 0)}
          </h3>
          <span className="kpi-detail-meta">
            Closed Transactions
            <MousePointerClick className="kpi-click-indicator" size={15} aria-hidden="true" />
          </span>
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
          <span className="kpi-detail-meta">
            Historical Count {summary?.trackedSymbols ?? summary?.totalPositions ?? 0}
            <MousePointerClick className="kpi-click-indicator" size={15} aria-hidden="true" />
          </span>
        </article>
      </section>

      <OverviewNotesPanel
        overviewNotes={overviewNotes}
        onSaveOverviewNote={onSaveOverviewNote}
        onExportPortfolioJsonV2={onExportPortfolioJsonV2}
      />

      <section className="panel">
        <h2>Total Asset Curve</h2>
        {assetChart.hasData ? (
          <MobileLandscapeChart title="Total Asset Curve">
            <ChartFrame
              className="overview-asset-frame"
              legend={(
                <>
                  <span><i className="dot dot-portfolio" /> Portfolio</span>
                  <span><i className="dot dot-cost-basis" /> Cost Basis</span>
                </>
              )}
            >
              {({ width, compact }) => {
                const chart = buildAssetChart(assetCurve, { width, compact });
                return (
                  <svg
                    viewBox={`0 0 ${chart.width} ${chart.height}`}
                    className="asset-chart chart-fluid"
                    role="img"
                    aria-label="Total asset chart with time on x-axis and dollar on y-axis"
                  >
              <path d={chart.costBasisLinePath} className="chart-line-cost-basis" />
              <path d={chart.portfolioLinePath} className="chart-line" />

              {chart.yTicks.map((tick, index) => (
                <g key={`y-${index}`}>
                  <text x={chart.plotRight + (compact ? -2 : 14)} y={tick.y + 4} textAnchor={compact ? 'end' : 'start'} className="chart-tick-right">
                    {Math.round(tick.value).toLocaleString(undefined, { minimumFractionDigits: 0 })}.00
                  </text>
                </g>
              ))}

              {chart.yearSeparators.map((separator) => (
                <line
                  key={`year-sep-${separator.year}`}
                  x1={separator.x}
                  y1={chart.plotTop}
                  x2={separator.x}
                  y2={chart.plotBottom}
                  className="year-separator"
                />
              ))}

              {chart.xTicks.map((tick, index) => (
                <g key={`x-${index}`}>
                  <text x={tick.x} y={chart.plotBottom + 24} textAnchor="middle" className={`chart-tick-bottom ${tick.isYearMark ? 'year-tick' : ''}`}>
                    {tick.label}
                  </text>
                </g>
              ))}

              <line
                x1={chart.plotLeft}
                y1={chart.plotBottom}
                x2={chart.plotRight}
                y2={chart.plotBottom}
                className="chart-axis-bottom"
              />

              <circle cx={chart.lastX} cy={chart.lastPortfolioY} r="3.2" className="chart-end-dot" />
              <circle cx={chart.lastX} cy={chart.lastCostBasisY} r="2.6" className="chart-end-dot-cost-basis" />
              {!compact ? (
                <>
                  <g transform={`translate(${chart.chipX}, ${chart.portfolioChipY})`}>
                    <rect rx="4" ry="4" width="132" height="22" className="line-end-chip" />
                    <text x="8" y="15" className="line-end-chip-text">Portfolio {formatCurrency(chart.lastPortfolioValue)}</text>
                  </g>
                  <g transform={`translate(${chart.chipX}, ${chart.costChipY})`}>
                    <rect rx="4" ry="4" width="132" height="22" className="line-end-chip-cost-basis" />
                    <text x="8" y="15" className="line-end-chip-text">Cost {formatCurrency(chart.lastCostBasisValue)}</text>
                  </g>
                </>
              ) : null}
                  </svg>
                );
              }}
            </ChartFrame>
          </MobileLandscapeChart>
        ) : (
          <p className="muted">No asset curve yet. Record transactions first.</p>
        )}
      </section>

      <section className="panel">
        <h2>Quick Transaction</h2>
        <form className="overview-transaction-form" onSubmit={onRecordTransaction}>
          <input
            placeholder="Symbol"
            value={transactionForm.symbol}
            onChange={(event) => setTransactionForm({ ...transactionForm, symbol: event.target.value })}
            onBlur={(event) => setTransactionForm({ ...transactionForm, symbol: event.currentTarget.value.trim().toUpperCase() })}
            required
          />
          <button
            type="button"
            className={`quick-trade-type-toggle ${transactionForm.type === 'SELL' ? 'is-sell' : 'is-buy'}`}
            aria-label={`Transaction type: ${transactionForm.type}. Activate to switch to ${transactionForm.type === 'BUY' ? 'SELL' : 'BUY'}`}
            aria-pressed={transactionForm.type === 'SELL'}
            onClick={() => setTransactionForm({ ...transactionForm, type: transactionForm.type === 'BUY' ? 'SELL' : 'BUY' })}
          >
            {transactionForm.type}
          </button>
          <input
            type="number"
            min="0.00000001"
            step="any"
            placeholder="Quantity"
            value={transactionForm.quantity}
            onChange={(event) => setTransactionForm({ ...transactionForm, quantity: event.target.value })}
            required
          />
          <input
            type="number"
            min="0.01"
            step="0.01"
            placeholder="Price"
            value={transactionForm.price}
            onChange={(event) => setTransactionForm({ ...transactionForm, price: event.target.value })}
            required
          />
          <button type="submit" className="quick-trade-submit">Record Trade</button>
        </form>
        <Link to="/transactions" className="cash-ledger-link">View transactions</Link>
      </section>

      <section className="panel">
        <h2>Cash Position</h2>
        <p className={latestCashBalance >= 0 ? 'positive' : 'negative'}>
          Current Cash Balance: ${latestCashBalance.toFixed(2)}
        </p>
        <div className="cash-actions overview-cash-actions">
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
        <div className="overview-section-links">
          <Link to="/cash" className="cash-ledger-link">View cash ledger</Link>
          <Link to="/dividends" className="cash-ledger-link">View dividends</Link>
        </div>
      </section>

      <section className="panel">
        <h2>Portfolio Allocation</h2>
        <div className="allocation-chart">
          {allocation.map((item, index) => (
            <div key={item.symbol} className="allocation-bar-row">
              <div className="allocation-meta">
                <span className="legend-dot" style={{ background: `var(--palette-${(index % 10) + 1})` }} />
                <strong className="allocation-symbol" title={item.symbol}>{item.symbol}</strong>
                <span className="muted allocation-weight">{item.weight.toFixed(2)}%</span>
              </div>
              <div className="allocation-track">
                <div
                  className="allocation-fill"
                  style={{
                    width: `${Math.max(item.weight, 1)}%`,
                    background: `var(--palette-${(index % 10) + 1})`
                  }}
                />
              </div>
              <span className="allocation-value">${item.marketValue.toFixed(2)}</span>
            </div>
          ))}
        </div>

        <div className="allocation-breakdown-header">
          <h2 className="subsection-title">Allocation Breakdown</h2>
          <Link to="/classifications" className="cash-ledger-link">View classifications</Link>
        </div>
        <div className="mobile-record-list allocation-mobile-list">
          {(showAllMobileAllocation ? mobileAllocation : mobileAllocation.slice(0, 5)).map((item, index) => {
            const dipAlert = dipAlertsBySymbol[item.symbol];
            return (
              <article
                key={`mobile-allocation-${item.symbol}`}
                className="record-card record-card-button allocation-record-card"
                role="button"
                tabIndex={0}
                onClick={() => setActiveDipAlert(dipAlert)}
                onKeyDown={(event) => onCardKeyDown(event, () => setActiveDipAlert(dipAlert))}
                aria-label={`View ${item.symbol} flexible buy context`}
              >
                <div className="record-card-head">
                  <span className="record-card-symbol"><span className="legend-dot" style={{ background: `var(--palette-${(index % 10) + 1})` }} />{item.symbol}</span>
                  <span className="allocation-card-value">
                    <strong>${item.marketValue.toFixed(2)}</strong>
                    {dipAlert?.hasRecommendation ? <span className={`dip-alert-bubble dip-alert-${dipAlert.severity}`} aria-label={`${item.symbol} flexible buy reminder`}>!</span> : null}
                  </span>
                </div>
                <div className="record-card-metrics">
                  <span><small>Weight</small><strong>{item.weight.toFixed(2)}%</strong></span>
                  <span><small>Cost Basis</small><strong>${item.totalCost.toFixed(2)}</strong></span>
                  {!item.isCash ? (
                    <>
                      <span><small>Unrealized</small><strong className={item.unrealizedPnl >= 0 ? 'positive' : 'negative'}>{item.unrealizedPnl >= 0 ? '+' : ''}${item.unrealizedPnl.toFixed(2)}</strong></span>
                      <span><small>Return</small><strong className={item.returnPct >= 0 ? 'positive' : 'negative'}>{item.returnPct >= 0 ? '+' : ''}{item.returnPct.toFixed(2)}%</strong></span>
                      <span><small>Quantity</small><strong>{formatQuantity(item.quantity)}</strong></span>
                      <span><small>Average Price</small><strong>${item.averageCost.toFixed(4)}</strong></span>
                      <span><small>Yield</small><strong>{item.yieldPct.toFixed(2)}%</strong></span>
                      <span><small>PE</small><strong>{item.latestPe == null ? '--' : item.latestPe.toFixed(2)}</strong></span>
                    </>
                  ) : null}
                </div>
              </article>
            );
          })}
        </div>
        {mobileAllocation.length > 5 ? (
          <button
            type="button"
            className="mobile-allocation-toggle"
            onClick={() => setShowAllMobileAllocation((current) => !current)}
          >
            {showAllMobileAllocation ? t('ui.showFewerPositions') : t('ui.showAllPositions', { count: mobileAllocation.length })}
          </button>
        ) : null}
        <div className="table-wrap desktop-only-table">
          <table>
            <thead>
              <tr>
                <th role="button" tabIndex={0} onClick={() => toggleSort('symbol')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('symbol')}>Symbol{sortMark('symbol')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('weight')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('weight')}>Weight{sortMark('weight')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('marketValue')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('marketValue')}>Market Value{sortMark('marketValue')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('yieldPct')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('yieldPct')}>Yield %{sortMark('yieldPct')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('latestPe')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('latestPe')}>PE{sortMark('latestPe')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('quantity')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('quantity')}>Quantity{sortMark('quantity')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('averageCost')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('averageCost')}>Average Price{sortMark('averageCost')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('totalCost')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('totalCost')}>Total Cost{sortMark('totalCost')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('unrealizedPnl')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('unrealizedPnl')}>Unrealized{sortMark('unrealizedPnl')}</th>
              </tr>
            </thead>
            <tbody>
              {sortedAllocation.map((item, index) => {
                const dipAlert = dipAlertsBySymbol[item.symbol];
                return (
                  <tr
                    key={item.symbol}
                    className={!item.isCash ? 'allocation-signal-row' : ''}
                    onClick={!item.isCash ? () => setActiveDipAlert(dipAlert) : undefined}
                    onKeyDown={!item.isCash ? (event) => onCardKeyDown(event, () => setActiveDipAlert(dipAlert)) : undefined}
                    role={!item.isCash ? 'button' : undefined}
                    tabIndex={!item.isCash ? 0 : undefined}
                    aria-label={!item.isCash ? `View ${item.symbol} flexible buy context` : undefined}
                  >
                    <td>
                      <span className="symbol-with-alert">
                        <span>
                          <span className="legend-dot" style={{ background: `var(--palette-${(index % 10) + 1})` }} />
                          {item.symbol}
                        </span>
                        {dipAlert?.hasRecommendation ? (
                          <span
                            className={`dip-alert-bubble dip-alert-${dipAlert.severity}`}
                            aria-label={`${item.symbol} flexible buy reminder`}
                            title={`${item.symbol} dropped ${dipAlert.strongestDrop.toFixed(1)}%.`}
                          >
                            !
                          </span>
                        ) : null}
                      </span>
                    </td>
                    <td>{item.weight.toFixed(2)}%</td>
                    <td>${item.marketValue.toFixed(2)}</td>
                    <td>{item.isCash ? '--' : `${item.yieldPct.toFixed(2)}%`}</td>
                    <td>{item.latestPe == null ? '--' : item.latestPe.toFixed(2)}</td>
                    <td>{item.isCash ? '--' : formatQuantity(item.quantity)}</td>
                    <td>{item.isCash ? '--' : `$${item.averageCost.toFixed(4)}`}</td>
                    <td>${item.totalCost.toFixed(2)}</td>
                    <td className={item.unrealizedPnl >= 0 ? 'positive' : 'negative'}>
                      {item.isCash ? '--' : `${item.unrealizedPnl >= 0 ? '+' : ''}${item.unrealizedPnl.toFixed(2)}`}
                    </td>
                  </tr>
                );
              })}
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
              <span className="rank-symbol" title={item.symbol}>{item.symbol}</span>
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

      <ResponsiveDialog
        open={showPositionModal}
        title="Position Count Details"
        eyebrow="Portfolio inventory"
        onClose={() => setShowPositionModal(false)}
        size="lg"
        fullHeight
      >
        <div className="rank-filter-tabs">
          <button type="button" className={positionView === 'CURRENT' ? 'rank-tab active' : 'rank-tab'} onClick={() => setPositionView('CURRENT')}>
            Current Holdings ({positionGroups.currentRows.length})
          </button>
          <button type="button" className={positionView === 'PAST' ? 'rank-tab active' : 'rank-tab'} onClick={() => setPositionView('PAST')}>
            Past Holdings ({positionGroups.pastRows.length})
          </button>
        </div>
        {positionView === 'CURRENT' ? (
          <>
          <div className="mobile-record-list">
            {positionGroups.currentRows.map((item) => (
              <article key={`current-card-${item.symbol}`} className="record-card">
                <div className="record-card-head"><span className="record-card-symbol">{item.symbol}</span><strong>${item.marketValue.toFixed(2)}</strong></div>
                <div className="record-card-metrics">
                  <span><small>Quantity</small><strong>{formatQuantity(item.quantity)}</strong></span>
                  <span><small>Average Cost</small><strong>${item.averageCost.toFixed(4)}</strong></span>
                  <span><small>Unrealized</small><strong className={item.unrealizedPnl >= 0 ? 'positive' : 'negative'}>{item.unrealizedPnl >= 0 ? '+' : ''}{item.unrealizedPnl.toFixed(2)}</strong></span>
                </div>
              </article>
            ))}
            {!positionGroups.currentRows.length ? <p className="muted">No current holdings.</p> : null}
          </div>
          <div className="table-wrap desktop-only-table">
            <table>
              <thead><tr><th>Symbol</th><th>Quantity</th><th>Average Cost</th><th>Market Value</th><th>Unrealized</th></tr></thead>
              <tbody>
                {positionGroups.currentRows.map((item) => (
                  <tr key={`current-${item.symbol}`}>
                    <td>{item.symbol}</td><td>{formatQuantity(item.quantity)}</td><td>${item.averageCost.toFixed(4)}</td><td>${item.marketValue.toFixed(2)}</td>
                    <td className={item.unrealizedPnl >= 0 ? 'positive' : 'negative'}>{item.unrealizedPnl >= 0 ? '+' : ''}{item.unrealizedPnl.toFixed(2)}</td>
                  </tr>
                ))}
                {!positionGroups.currentRows.length ? <tr><td colSpan={5} className="muted">No current holdings.</td></tr> : null}
              </tbody>
            </table>
          </div>
          </>
        ) : (
          <>
          <div className="mobile-record-list">
            {positionGroups.pastRows.map((item) => (
              <article key={`past-card-${item.symbol}`} className="record-card">
                <div className="record-card-head"><span className="record-card-symbol">{item.symbol}</span><strong>{item.lastTradeAt ? new Date(item.lastTradeAt).toLocaleDateString() : '--'}</strong></div>
                <div className="record-card-metrics">
                  <span><small>Total Bought</small><strong>{formatQuantity(item.buyQty)}</strong></span>
                  <span><small>Total Sold</small><strong>{formatQuantity(item.sellQty)}</strong></span>
                </div>
              </article>
            ))}
            {!positionGroups.pastRows.length ? <p className="muted">No past holdings yet.</p> : null}
          </div>
          <div className="table-wrap desktop-only-table">
            <table>
              <thead><tr><th>Symbol</th><th>Last Trade</th><th>Total Bought</th><th>Total Sold</th></tr></thead>
              <tbody>
                {positionGroups.pastRows.map((item) => (
                  <tr key={`past-${item.symbol}`}><td>{item.symbol}</td><td>{item.lastTradeAt ? new Date(item.lastTradeAt).toLocaleDateString() : '--'}</td><td>{formatQuantity(item.buyQty)}</td><td>{formatQuantity(item.sellQty)}</td></tr>
                ))}
                {!positionGroups.pastRows.length ? <tr><td colSpan={4} className="muted">No past holdings yet.</td></tr> : null}
              </tbody>
            </table>
          </div>
          </>
        )}
      </ResponsiveDialog>

      <ResponsiveDialog
        open={showRealizedModal}
        title="Realized Gain Records"
        eyebrow="Closed positions"
        onClose={() => setShowRealizedModal(false)}
        size="lg"
        fullHeight
      >
        <div className="mobile-record-list">
          {realizedGainRecords.map((item) => (
            <article key={`realized-card-${item.id}`} className="record-card">
              <div className="record-card-head"><span className="record-card-symbol">{item.symbol}</span><strong className={item.realizedGain >= 0 ? 'positive' : 'negative'}>{item.realizedGain >= 0 ? '+' : ''}${item.realizedGain.toFixed(2)}</strong></div>
              <div className="record-card-metrics">
                <span><small>Date</small><strong>{new Date(item.executedAt).toLocaleDateString()}</strong></span>
                <span><small>Sold Qty</small><strong>{formatQuantity(item.quantity)}</strong></span>
                <span><small>Cumulative</small><strong className={item.cumulativeGain >= 0 ? 'positive' : 'negative'}>{item.cumulativeGain >= 0 ? '+' : ''}${item.cumulativeGain.toFixed(2)}</strong></span>
              </div>
            </article>
          ))}
          {!realizedGainRecords.length ? <p className="muted">No realized gain records yet.</p> : null}
        </div>
        <div className="table-wrap desktop-only-table">
          <table>
            <thead><tr><th>Date</th><th>Symbol</th><th>Sold Qty</th><th>Sell Price</th><th>Cost/Share</th><th>Gain</th><th>Cumulative</th></tr></thead>
            <tbody>
              {realizedGainRecords.map((item) => (
                <tr key={`realized-${item.id}`}>
                  <td>{new Date(item.executedAt).toLocaleDateString()}</td><td>{item.symbol}</td><td>{formatQuantity(item.quantity)}</td><td>${item.sellPrice.toFixed(4)}</td><td>${item.costPerShare.toFixed(4)}</td>
                  <td className={item.realizedGain >= 0 ? 'positive' : 'negative'}>{item.realizedGain >= 0 ? '+' : ''}{item.realizedGain.toFixed(2)}</td>
                  <td className={item.cumulativeGain >= 0 ? 'positive' : 'negative'}>{item.cumulativeGain >= 0 ? '+' : ''}{item.cumulativeGain.toFixed(2)}</td>
                </tr>
              ))}
              {!realizedGainRecords.length ? <tr><td colSpan={7} className="muted">No realized gain records yet.</td></tr> : null}
            </tbody>
          </table>
        </div>
      </ResponsiveDialog>

      <ResponsiveDialog
        open={Boolean(activeDipAlert)}
        title={activeDipAlert ? `${activeDipAlert.symbol} Flexible Buy Reminder` : 'Flexible Buy Reminder'}
        eyebrow="Portfolio signal"
        onClose={() => setActiveDipAlert(null)}
        size="lg"
        className="dip-alert-modal"
        fullHeight
      >
        {activeDipAlert ? (
          <>
            <div className={`dip-alert-summary dip-alert-summary-${activeDipAlert.severity}`}>
              <strong>{activeDipAlert.hasRecommendation ? (activeDipAlert.severity === 'strong' ? 'Red reminder' : 'Yellow reminder') : 'No buy reminder'}</strong>
              <span>{activeDipAlert.hasRecommendation ? 'Consider whether this fits your flexible DCA plan.' : 'This item is available for review, but it does not currently produce a buy suggestion.'}</span>
            </div>
            <div className="dip-alert-detail-grid">
              <div>
                <span>Current Price</span>
                <strong>${activeDipAlert.currentPrice.toFixed(4)}</strong>
              </div>
              <div>
                <span>{activeDipAlert.hasRecommendation ? 'Largest Triggered Drop' : 'Largest Observed Drop'}</span>
                <strong>{activeDipAlert.strongestDrop == null ? '--' : `${activeDipAlert.strongestDrop.toFixed(2)}%`}</strong>
              </div>
              <div>
                <span>Portfolio Weight</span>
                <strong>{activeDipAlert.weight.toFixed(2)}%</strong>
              </div>
            </div>
            {!activeDipAlert.hasRecommendation ? <p className="dip-alert-no-recommendation">{activeDipAlert.unavailableReason}</p> : null}
            {activeDipAlert.allSources.length ? (
              <>
                <div className="mobile-record-list">
                  {activeDipAlert.allSources.map((source) => (
                    <article key={`source-card-${source.key}`} className="record-card">
                      <div className="record-card-head"><span className="record-card-symbol">{source.label}</span><strong className={source.dropPct >= dipAlertMinDropPct ? 'negative' : ''}>{source.dropPct.toFixed(2)}%</strong></div>
                      <div className="record-card-metrics">
                        <span><small>Reference Date</small><strong>{source.referenceDate ? new Date(source.referenceDate).toLocaleDateString() : '--'}</strong></span>
                        <span><small>Reference Price</small><strong>${source.referencePrice.toFixed(4)}</strong></span>
                      </div>
                    </article>
                  ))}
                </div>
                <div className="table-wrap desktop-only-table">
                  <table className="dip-alert-table">
                    <thead>
                      <tr>
                        <th>Trigger</th>
                        <th>Reference Date</th>
                        <th>Reference Price</th>
                        <th>Drop</th>
                      </tr>
                    </thead>
                    <tbody>
                      {activeDipAlert.allSources.map((source) => (
                        <tr key={source.key}>
                          <td>{source.label}</td>
                          <td>{source.referenceDate ? new Date(source.referenceDate).toLocaleDateString() : '--'}</td>
                          <td>${source.referencePrice.toFixed(4)}</td>
                          <td className={source.dropPct >= dipAlertMinDropPct ? 'negative' : ''}>
                            {source.dropPct.toFixed(2)}%
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            ) : null}
            <div className="dip-alert-stock-note">
              <div className="dip-alert-stock-note-header">
                <h3>Stock Note</h3>
                <Link to="/notes" className="cash-ledger-link" onClick={() => setActiveDipAlert(null)}>Edit in Notes</Link>
              </div>
              {activeStockNoteText ? (
                <>
                  <p className="dip-alert-stock-note-text">{activeStockNoteText}</p>
                  <p className="chart-caption">
                    Last updated: {activeStockNote?.updatedAt ? new Date(activeStockNote.updatedAt).toLocaleString() : '--'}
                  </p>
                </>
              ) : (
                <p className="muted">No saved stock note for {activeDipAlert.symbol} yet.</p>
              )}
            </div>
            <p className="chart-caption">
              Rule: last month or latest BUY down 5% shows yellow, down 10% shows red. Positions above 20% weight never receive a buy reminder.
            </p>
          </>
        ) : null}
      </ResponsiveDialog>

    </>
  );
}
