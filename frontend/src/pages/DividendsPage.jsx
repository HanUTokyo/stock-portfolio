import { useMemo, useState } from 'react';
import { Download, Minimize2, Maximize2, Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import DateInput from '../components/DateInput';
import FormField from '../components/FormField';
import BottomSheet from '../components/BottomSheet';
import MobileActionBar from '../components/MobileActionBar';
import RowDetailSheet from '../components/RowDetailSheet';
import SegmentedControl from '../components/SegmentedControl';
import ConfirmDialog from '../components/ConfirmDialog';
import ChartFrame from '../components/ChartFrame';
import MobileLandscapeChart from '../components/MobileLandscapeChart';
import CsvImportPanel from '../components/CsvImportPanel';
import useIsMobile from '../hooks/useIsMobile';
import { buildDividendsCsv, downloadCsv } from '../utils/csvExport';

const defaultSort = { key: 'date', direction: 'desc' };
const mobilePageSize = 30;

function toNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function toDateInput(value) {
  if (!value) return '';
  return String(value).slice(0, 10);
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

function buildDividendBars(monthlyDividends, requestedWidth = 900) {
  if (!monthlyDividends.length) {
    return { hasData: false };
  }

  const width = Math.max(320, Math.round(requestedWidth || 900));
  const compact = width < 560;
  const height = compact ? 248 : 320;
  const plotLeft = compact ? 12 : 30;
  const plotRight = width - (compact ? 12 : 30);
  const plotTop = 20;
  const plotBottom = height - 44;
  const values = monthlyDividends.map((d) => toNumber(d.totalAmount));
  const maxValue = Math.max(...values, 1);
  const slot = (plotRight - plotLeft) / monthlyDividends.length;
  const barWidth = Math.max(Math.min(slot * 0.64, 44), compact ? 2 : 12);
  const labelEvery = compact ? Math.max(1, Math.ceil(monthlyDividends.length / 5)) : 1;

  const bars = monthlyDividends.map((d, i) => {
    const value = toNumber(d.totalAmount);
    const h = (value / maxValue) * (plotBottom - plotTop);
    const x = plotLeft + i * slot + (slot - barWidth) / 2;
    const y = plotBottom - h;
    const year = String(d.month).slice(0, 4);
    const monthLabel = new Date(`${d.month}-01T00:00:00Z`).toLocaleDateString('en-US', { month: 'short' });
    const prevYear = i > 0 ? String(monthlyDividends[i - 1].month).slice(0, 4) : null;
    const isYearMark = i === 0 || prevYear !== year;
    return {
      x,
      y,
      width: barWidth,
      height: h,
      value,
      rawMonth: d.month,
      monthLabel: isYearMark ? year : monthLabel,
      isYearMark,
      showTick: isYearMark || i % labelEvery === 0,
      showValue: !compact || value === maxValue || i === monthlyDividends.length - 1
    };
  });

  const yearSeparators = bars
    .map((bar, i) => ({ bar, i }))
    .filter((item) => item.i > 0 && item.bar.isYearMark)
    .map((item) => ({
      x: plotLeft + item.i * slot,
      year: item.bar.monthLabel
    }));

  return {
    hasData: true,
    width,
    height,
    plotLeft,
    plotRight,
    plotBottom,
    bars,
    yearSeparators
  };
}

export default function DividendsPage({
  monthlyDividends,
  dividends,
  dividendForm,
  setDividendForm,
  onAddDividend,
  onUpdateDividend,
  onDeleteDividend,
  onImportDividends
}) {
  const { t } = useTranslation();
  const isMobile = useIsMobile();
  const chart = useMemo(() => buildDividendBars(monthlyDividends), [monthlyDividends]);
  const [csvFile, setCsvFile] = useState(null);
  const [importResult, setImportResult] = useState(null);
  const [importLoading, setImportLoading] = useState(false);
  const [sort, setSort] = useState(defaultSort);
  const [dividendsCollapsed, setDividendsCollapsed] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [deletingId, setDeletingId] = useState(null);
  const [editForm, setEditForm] = useState({ symbol: '', amount: '', paidDate: '' });
  const [mobileEntryOpen, setMobileEntryOpen] = useState(false);
  const [mobileEntryTab, setMobileEntryTab] = useState('record');
  const [selectedDividend, setSelectedDividend] = useState(null);
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [mobileVisibleCount, setMobileVisibleCount] = useState(mobilePageSize);
  const [filters, setFilters] = useState({ search: '', from: '', to: '' });
  const sortedDividends = useMemo(() => {
    const search = filters.search.trim().toLowerCase();
    const from = filters.from ? new Date(`${filters.from}T00:00:00`).getTime() : null;
    const to = filters.to ? new Date(`${filters.to}T23:59:59.999`).getTime() : null;
    return dividends
      .filter((dividend) => {
        const paidAt = new Date(dividend.paidDate).getTime();
        return (!search || String(dividend.symbol || '').toLowerCase().includes(search))
          && (from === null || paidAt >= from)
          && (to === null || paidAt <= to);
      })
      .sort((a, b) => compareDividends(a, b, sort));
  }, [dividends, sort, filters]);
  const mobileDividends = useMemo(() => sortedDividends.slice(0, mobileVisibleCount), [sortedDividends, mobileVisibleCount]);

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
    setMobileVisibleCount(mobilePageSize);
  }

  function clearFilters() {
    setFilters({ search: '', from: '', to: '' });
    setMobileVisibleCount(mobilePageSize);
  }

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

  function startEdit(dividend) {
    setEditingId(dividend.id);
    setEditForm({
      symbol: dividend.symbol,
      amount: String(dividend.amount),
      paidDate: toDateInput(dividend.paidDate)
    });
  }

  function openDividend(dividend) {
    setSelectedDividend(dividend);
  }

  function cancelEdit() {
    setEditingId(null);
    setEditForm({ symbol: '', amount: '', paidDate: '' });
  }

  async function saveEdit(dividendId) {
    await onUpdateDividend(dividendId, {
      symbol: editForm.symbol,
      amount: Number(editForm.amount),
      paidDate: editForm.paidDate
    });
    cancelEdit();
    setSelectedDividend(null);
  }

  function handleDelete(dividend) {
    if (deletingId !== null || editingId !== null) return;
    setDeleteTarget(dividend);
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeletingId(deleteTarget.id);
    try {
      await onDeleteDividend(deleteTarget.id);
      setSelectedDividend(null);
      setDeleteTarget(null);
    } finally {
      setDeletingId(null);
    }
  }

  function handleExportCsv() {
    const csv = buildDividendsCsv(sortedDividends);
    const date = new Date().toISOString().slice(0, 10);
    downloadCsv(`dividends-${date}.csv`, csv);
  }

  const recordDividendForm = (
    <form className="stack-form" onSubmit={(event) => {
      onAddDividend(event);
      if (isMobile) setMobileEntryOpen(false);
    }}>
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
  );

  const importDividendPanel = (
    <CsvImportPanel
      file={csvFile}
      onFileChange={setCsvFile}
      onImport={handleImportCsv}
      loading={importLoading}
      result={importResult}
      fileLabel="Choose dividend CSV file"
    />
  );

  const selectedEditing = selectedDividend && editingId === selectedDividend.id;

  return (
    <>
      <MobileActionBar
        className="dividends-mobile-actions"
        actions={[
          { key: 'record', label: 'Dividend', icon: Plus, onClick: () => { setMobileEntryTab('record'); setMobileEntryOpen(true); } }
        ]}
      />

      <section className="panel-grid mobile-hide">
        <article className="panel">
          <h2>Record Dividend</h2>
          {recordDividendForm}
        </article>

        <article className="panel">
          <h2>Import Dividend CSV</h2>
          {importDividendPanel}
        </article>
      </section>

      <section className="panel">
        <h2>Monthly Dividend Income</h2>
        {chart.hasData ? (
          <MobileLandscapeChart title="Monthly Dividend Income">
            <ChartFrame className="dividend-chart-wrap">
              {({ width }) => {
                const responsiveChart = buildDividendBars(monthlyDividends, width);
                return (
              <svg viewBox={`0 0 ${responsiveChart.width} ${responsiveChart.height}`} className="asset-chart chart-fluid" role="img" aria-label="Monthly total dividends bar chart">
              <line x1={responsiveChart.plotLeft} y1={responsiveChart.plotBottom} x2={responsiveChart.plotRight} y2={responsiveChart.plotBottom} className="chart-axis-bottom" />
              {responsiveChart.yearSeparators.map((separator) => (
                <line
                  key={`dividend-year-${separator.year}-${separator.x}`}
                  x1={separator.x}
                  y1={20}
                  x2={separator.x}
                  y2={responsiveChart.plotBottom}
                  className="year-separator"
                />
              ))}
              {responsiveChart.bars.map((bar) => (
                <g key={bar.rawMonth}>
                  <rect x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="3" className="dividend-bar" />
                  {bar.showTick ? (
                    <text
                      x={bar.x + bar.width / 2}
                      y={responsiveChart.plotBottom + 14}
                      textAnchor="middle"
                      className={`chart-tick-bottom ${bar.isYearMark ? 'year-tick' : ''}`}
                    >
                      {bar.monthLabel}
                    </text>
                  ) : null}
                  {bar.showValue ? (
                    <text x={bar.x + bar.width / 2} y={Math.max(bar.y - 6, 10)} textAnchor="middle" className="chart-tick-right">
                      ${bar.value.toFixed(2)}
                    </text>
                  ) : null}
                </g>
              ))}
              </svg>
                );
              }}
            </ChartFrame>
          </MobileLandscapeChart>
        ) : (
          <p className="muted">No dividends yet. Add records to generate the chart.</p>
        )}
      </section>

      <section className="panel">
        <div className="collapsible-header dividends-list-header">
          <h2>Dividend Records</h2>
          <div className="header-actions">
            <button type="button" className="table-toggle" onClick={handleExportCsv} disabled={!sortedDividends.length}>
              <Download size={16} aria-hidden="true" />
              <span>Export CSV</span>
            </button>
            <button type="button" className="table-toggle" onClick={() => setDividendsCollapsed((prev) => !prev)}>
              {dividendsCollapsed ? <Maximize2 size={16} aria-hidden="true" /> : <Minimize2 size={16} aria-hidden="true" />}
              <span>{dividendsCollapsed ? 'Expand' : 'Collapse'}</span>
            </button>
          </div>
        </div>
        {!dividendsCollapsed ? <>
          <div className="record-filter-bar record-filter-bar--dividends" data-record-filter="dividends">
            <label className="record-filter-search"><span>Search</span><input value={filters.search} onChange={(event) => updateFilter('search', event.target.value)} placeholder="Symbol" /></label>
            <label><span>From</span><DateInput value={filters.from} onChange={(event) => updateFilter('from', event.target.value)} /></label>
            <label><span>To</span><DateInput value={filters.to} onChange={(event) => updateFilter('to', event.target.value)} /></label>
            <div className="record-filter-actions"><span aria-live="polite">{sortedDividends.length} of {dividends.length} records</span><button type="button" className="row-secondary-btn" onClick={clearFilters} disabled={!filters.search && !filters.from && !filters.to}>Clear</button></div>
          </div>
          <div className="mobile-record-list">
            {mobileDividends.map((dividend) => (
              <button key={`mobile-dividend-${dividend.id}`} type="button" className="record-card record-card-button dividend-record-card" onClick={() => openDividend(dividend)}>
                <span className="record-card-head"><strong className="record-card-symbol">{dividend.symbol}</strong><strong className="positive">${toNumber(dividend.amount).toFixed(4)}</strong></span>
                <span className="record-card-foot">Paid {new Date(dividend.paidDate).toLocaleDateString()}</span>
              </button>
            ))}
            {!sortedDividends.length ? <p className="muted">{dividends.length ? 'No dividends match these filters.' : 'No dividends yet.'}</p> : null}
          </div>
          {mobileDividends.length < sortedDividends.length ? (
            <button type="button" className="mobile-records-more" onClick={() => setMobileVisibleCount((count) => count + mobilePageSize)}>
              {t('ui.showMoreRecords')}
            </button>
          ) : null}
          <div className="table-wrap desktop-only-table">
          <table>
            <thead>
              <tr>
                <th role="button" tabIndex={0} onClick={() => toggleSort('date')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('date')}>Date{sortMark('date')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('symbol')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('symbol')}>Symbol{sortMark('symbol')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('amount')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('amount')}>Amount{sortMark('amount')}</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {sortedDividends.map((d) => (
                <tr key={d.id} className="record-table-row" onClick={() => openDividend(d)}>
                  <td>{new Date(d.paidDate).toLocaleDateString()}</td>
                  <td className="symbol-cell">{d.symbol}</td>
                  <td>${toNumber(d.amount).toFixed(4)}</td>
                  <td><button type="button" className="row-secondary-btn" onClick={(event) => { event.stopPropagation(); openDividend(d); }}>View</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </> : null}
      </section>

      <BottomSheet open={mobileEntryOpen} title="Dividends" onClose={() => setMobileEntryOpen(false)}>
        <SegmentedControl
          label="Dividend action"
          value={mobileEntryTab}
          onChange={setMobileEntryTab}
          options={[
            { value: 'record', label: 'Record' },
            { value: 'import', label: 'Import CSV' }
          ]}
        />
        <div className="mobile-sheet-section">
          {mobileEntryTab === 'record' ? recordDividendForm : importDividendPanel}
        </div>
      </BottomSheet>

      <RowDetailSheet
        open={Boolean(selectedDividend)}
        title={selectedDividend ? selectedDividend.symbol : 'Dividend'}
        eyebrow={selectedDividend ? new Date(selectedDividend.paidDate).toLocaleDateString() : ''}
        onClose={() => {
          setSelectedDividend(null);
          cancelEdit();
        }}
        actions={selectedDividend ? (
          selectedEditing ? (
            <>
              <button type="button" onClick={() => saveEdit(selectedDividend.id)}>Save</button>
              <button type="button" className="secondary-button" onClick={cancelEdit}>Cancel</button>
            </>
          ) : (
            <>
              <button type="button" onClick={() => startEdit(selectedDividend)}>Edit</button>
              <button type="button" className="row-danger-btn" onClick={() => handleDelete(selectedDividend)} disabled={deletingId === selectedDividend.id}>
                {deletingId === selectedDividend.id ? 'Deleting...' : 'Delete'}
              </button>
            </>
          )
        ) : null}
      >
        {selectedDividend ? (
          selectedEditing ? (
            <div className="stack-form sheet-edit-form">
              <FormField label="Payment date">
                <DateInput value={editForm.paidDate} onChange={(e) => setEditForm({ ...editForm, paidDate: e.target.value })} />
              </FormField>
              <FormField label="Symbol" hint="Ticker that paid this dividend.">
                <input autoCapitalize="characters" value={editForm.symbol} onChange={(e) => setEditForm({ ...editForm, symbol: e.target.value })} />
              </FormField>
              <FormField label="Dividend amount (USD)">
                <input type="number" min="0.0001" step="0.0001" inputMode="decimal" value={editForm.amount} onChange={(e) => setEditForm({ ...editForm, amount: e.target.value })} />
              </FormField>
            </div>
          ) : (
            <div className="sheet-detail-list">
              <div><span>Amount</span><strong>${toNumber(selectedDividend.amount).toFixed(4)}</strong></div>
              <div><span>Paid Date</span><strong>{new Date(selectedDividend.paidDate).toLocaleDateString()}</strong></div>
            </div>
          )
        ) : null}
      </RowDetailSheet>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        title="Delete dividend?"
        description={deleteTarget ? `This will permanently remove the ${deleteTarget.symbol} dividend paid on ${new Date(deleteTarget.paidDate).toLocaleDateString()}.` : ''}
        confirmLabel="Delete dividend"
        pending={deletingId !== null}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </>
  );
}
