import { useEffect, useMemo, useState } from 'react';
import { Download, Filter, Maximize2, Minimize2, Pencil, Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import DateInput from '../components/DateInput';
import FormField from '../components/FormField';
import BottomSheet from '../components/BottomSheet';
import MobileActionBar from '../components/MobileActionBar';
import RowDetailSheet from '../components/RowDetailSheet';
import ConfirmDialog from '../components/ConfirmDialog';
import SegmentedControl from '../components/SegmentedControl';
import CsvImportPanel from '../components/CsvImportPanel';
import { buildCashAdjustmentsCsv, downloadCsv } from '../utils/csvExport';

const defaultSort = { key: 'occurredAt', direction: 'desc' };
const mobilePageSize = 30;

function toNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function formatCurrency(value) {
  return `$${toNumber(value).toFixed(2)}`;
}

function formatDateTime(value) {
  if (!value) return '--';
  return new Date(value).toLocaleString();
}

function toLocalDateKey(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function compareAdjustments(a, b, sort) {
  let result = 0;

  if (sort.key === 'occurredAt') {
    result = new Date(a.occurredAt).getTime() - new Date(b.occurredAt).getTime();
  } else if (sort.key === 'type') {
    result = String(a.type).localeCompare(String(b.type));
  } else if (sort.key === 'amount' || sort.key === 'signedAmount' || sort.key === 'runningBalance') {
    result = toNumber(a[sort.key]) - toNumber(b[sort.key]);
  }

  if (result === 0) {
    result = toNumber(a.id) - toNumber(b.id);
  }

  return sort.direction === 'asc' ? result : -result;
}

function buildLedgerRows(cashAdjustments) {
  const ordered = [...cashAdjustments].sort((a, b) => {
    const timeDiff = new Date(a.occurredAt).getTime() - new Date(b.occurredAt).getTime();
    if (timeDiff !== 0) return timeDiff;
    return toNumber(a.id) - toNumber(b.id);
  });

  let runningBalance = 0;
  return ordered.map((item) => {
    runningBalance += toNumber(item.signedAmount);
    return {
      ...item,
      runningBalance
    };
  });
}

export default function CashPage({
  assetCurve,
  cashAdjustments,
  cashAdjustmentForm,
  setCashAdjustmentForm,
  onSubmitCashAdjustment,
  onUpdateCashAdjustment,
  onDeleteCashAdjustment,
  onImportCashAdjustments
}) {
  const { t } = useTranslation();
  const [sort, setSort] = useState(defaultSort);
  const [typeFilter, setTypeFilter] = useState('ALL');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [cashSheetOpen, setCashSheetOpen] = useState(false);
  const [mobileEntryTab, setMobileEntryTab] = useState('record');
  const [filterSheetOpen, setFilterSheetOpen] = useState(false);
  const [selectedCashRow, setSelectedCashRow] = useState(null);
  const [editingCashRow, setEditingCashRow] = useState(false);
  const [cashEditForm, setCashEditForm] = useState({ type: 'DEPOSIT', amount: '', date: '' });
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [csvFile, setCsvFile] = useState(null);
  const [importResult, setImportResult] = useState(null);
  const [importing, setImporting] = useState(false);
  const [cashLedgerCollapsed, setCashLedgerCollapsed] = useState(false);
  const [mobileVisibleCount, setMobileVisibleCount] = useState(mobilePageSize);
  const latestAssetPoint = assetCurve?.length ? assetCurve[assetCurve.length - 1] : null;
  const currentCashBalance = toNumber(latestAssetPoint?.cashBalance);
  const totalAssets = toNumber(latestAssetPoint?.totalAssets);
  const cashWeight = totalAssets > 0 ? (currentCashBalance / totalAssets) * 100 : 0;

  const ledgerRows = useMemo(() => buildLedgerRows(cashAdjustments || []), [cashAdjustments]);
  const totals = useMemo(() => {
    return ledgerRows.reduce((acc, item) => {
      if (item.type === 'DEPOSIT') {
        acc.deposits += toNumber(item.amount);
      } else if (item.type === 'WITHDRAWAL') {
        acc.withdrawals += toNumber(item.amount);
      }
      acc.net += toNumber(item.signedAmount);
      return acc;
    }, { deposits: 0, withdrawals: 0, net: 0 });
  }, [ledgerRows]);

  const visibleRows = useMemo(() => {
    return ledgerRows
      .filter((item) => typeFilter === 'ALL' || item.type === typeFilter)
      .filter((item) => {
        const dateKey = toLocalDateKey(item.occurredAt);
        return (!fromDate || dateKey >= fromDate) && (!toDate || dateKey <= toDate);
      })
      .sort((a, b) => compareAdjustments(a, b, sort));
  }, [ledgerRows, sort, typeFilter, fromDate, toDate]);
  const mobileRows = useMemo(() => visibleRows.slice(0, mobileVisibleCount), [visibleRows, mobileVisibleCount]);

  useEffect(() => {
    setMobileVisibleCount(mobilePageSize);
  }, [typeFilter, sort, fromDate, toDate]);

  function updateFromDate(value) {
    setFromDate(value);
    if (toDate && value > toDate) setToDate(value);
  }

  function updateToDate(value) {
    setToDate(value);
    if (fromDate && value && value < fromDate) setFromDate(value);
  }

  function toggleSort(key) {
    setSort((prev) => {
      if (prev.key === key) {
        return { key, direction: prev.direction === 'asc' ? 'desc' : 'asc' };
      }
      return { key, direction: key === 'occurredAt' ? 'desc' : 'asc' };
    });
  }

  function sortMark(key) {
    if (sort.key !== key) return '';
    return sort.direction === 'asc' ? ' ▲' : ' ▼';
  }

  function openCashRow(row) {
    setSelectedCashRow(row);
    setEditingCashRow(false);
    setCashEditForm({ type: row.type, amount: String(row.amount), date: toLocalDateKey(row.occurredAt) });
  }

  function handleExportCsv() {
    const date = new Date().toISOString().slice(0, 10);
    downloadCsv(`cash-adjustments-${date}.csv`, buildCashAdjustmentsCsv(visibleRows));
  }

  async function handleImportCsv() {
    if (!csvFile || !onImportCashAdjustments) return;
    setImporting(true);
    try {
      const result = await onImportCashAdjustments(csvFile);
      setImportResult(result);
    } finally {
      setImporting(false);
    }
  }

  async function saveCashEdit() {
    if (!selectedCashRow || !onUpdateCashAdjustment) return;
    await onUpdateCashAdjustment(selectedCashRow.id, {
      type: cashEditForm.type,
      amount: Number(cashEditForm.amount),
      occurredAt: new Date(`${cashEditForm.date}T00:00:00`).toISOString()
    });
    setEditingCashRow(false);
    setSelectedCashRow(null);
  }

  async function confirmDelete() {
    if (!deleteTarget || !onDeleteCashAdjustment) return;
    await onDeleteCashAdjustment(deleteTarget.id);
    setDeleteTarget(null);
    setSelectedCashRow(null);
  }

  const cashForm = (
    <div className="stack-form">
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
      <div className="button-row">
        <button type="button" onClick={() => { onSubmitCashAdjustment('DEPOSIT'); setCashSheetOpen(false); }}>Add Cash</button>
        <button type="button" onClick={() => { onSubmitCashAdjustment('WITHDRAWAL'); setCashSheetOpen(false); }}>Reduce Cash</button>
      </div>
    </div>
  );

  const importCashPanel = (
    <CsvImportPanel
      file={csvFile}
      onFileChange={setCsvFile}
      onImport={handleImportCsv}
      loading={importing}
      result={importResult}
      fileLabel="Choose cash adjustments CSV file"
    />
  );

  return (
    <>
      <section className="kpi-grid">
        <article className="kpi-card">
          <p>Current Cash Balance</p>
          <h3 className={currentCashBalance >= 0 ? 'positive' : 'negative'}>{formatCurrency(currentCashBalance)}</h3>
          <span>Latest asset curve cash balance</span>
        </article>
        <article className="kpi-card">
          <p>Cash Weight</p>
          <h3>{cashWeight.toFixed(2)}%</h3>
          <span>Total Assets {formatCurrency(totalAssets)}</span>
        </article>
        <article className="kpi-card">
          <p>Total Deposits</p>
          <h3 className="positive">{formatCurrency(totals.deposits)}</h3>
          <span>Manual and transaction cash inflows</span>
        </article>
        <article className="kpi-card">
          <p>Total Withdrawals</p>
          <h3 className="negative">{formatCurrency(totals.withdrawals)}</h3>
          <span>Manual and transaction cash outflows</span>
        </article>
        <article className="kpi-card">
          <p>Net Manual Cash Flow</p>
          <h3 className={totals.net >= 0 ? 'positive' : 'negative'}>{formatCurrency(totals.net)}</h3>
          <span>Sum of signed cash adjustments</span>
        </article>
      </section>

      <MobileActionBar
        actions={[
          { key: 'cash', label: 'Record Cash', icon: Plus, onClick: () => { setMobileEntryTab('record'); setCashSheetOpen(true); } },
          { key: 'filter', label: t('ui.filter'), icon: Filter, variant: 'secondary', onClick: () => setFilterSheetOpen(true) }
        ]}
      />

      <section className="panel-grid">
        <article className="panel mobile-hide">
          <h2>Record Cash</h2>
          {cashForm}
        </article>
        <article className="panel cash-summary-panel mobile-hide">
          <div className="cash-ledger-filter-heading">
            <h2>Ledger View</h2>
            <div className="cash-visible-records" aria-live="polite">
              <span>Visible Records</span>
              <strong>{visibleRows.length}</strong>
            </div>
          </div>
          <div className="cash-ledger-controls">
            <label>
              <span>Type</span>
              <select value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)}>
                <option value="ALL">All</option>
                <option value="DEPOSIT">Deposits</option>
                <option value="WITHDRAWAL">Withdrawals</option>
              </select>
            </label>
            <label>
              <span>From</span>
              <DateInput value={fromDate} max={toDate || undefined} onChange={(e) => updateFromDate(e.target.value)} />
            </label>
            <label>
              <span>To</span>
              <DateInput value={toDate} min={fromDate || undefined} onChange={(e) => updateToDate(e.target.value)} />
            </label>
          </div>
        </article>
      </section>

      <section className="panel cash-import-section mobile-hide">
        <div className="cash-import-section-copy">
          <h2>Import Cash CSV</h2>
          <p className="muted">Add multiple deposits and withdrawals from a CSV file.</p>
        </div>
        {importCashPanel}
      </section>

      <section className="panel">
        <div className="collapsible-header cash-ledger-header">
          <div className="cash-ledger-title">
            <h2>Cash Ledger</h2>
            <span className="muted">{ledgerRows.length} total records</span>
          </div>
          <div className="header-actions">
            <button type="button" className="table-toggle" onClick={handleExportCsv} disabled={!visibleRows.length}><Download size={16} aria-hidden="true" /><span>Export CSV</span></button>
            <button
              type="button"
              className="table-toggle"
              aria-expanded={!cashLedgerCollapsed}
              aria-controls="cash-ledger-content"
              onClick={() => setCashLedgerCollapsed((collapsed) => !collapsed)}
            >
              {cashLedgerCollapsed ? <Maximize2 size={16} aria-hidden="true" /> : <Minimize2 size={16} aria-hidden="true" />}
              <span>{cashLedgerCollapsed ? 'Expand' : 'Collapse'}</span>
            </button>
          </div>
        </div>
        {!cashLedgerCollapsed ? <div id="cash-ledger-content">
          <div className="mobile-record-list">
          {mobileRows.map((row) => (
            <button key={`mobile-cash-${row.id}`} type="button" className="record-card record-card-button cash-ledger-record-card" onClick={() => openCashRow(row)}>
              <span className="record-card-head">
                <strong className={row.type === 'DEPOSIT' ? 'positive' : 'negative'}>{row.type === 'DEPOSIT' ? 'Deposit' : 'Withdrawal'}</strong>
                <span className="record-card-meta">{formatDateTime(row.occurredAt)}</span>
              </span>
              <span className="record-card-metrics">
                <span><small>Amount</small><strong>{formatCurrency(row.amount)}</strong></span>
                <span><small>Balance</small><strong>{formatCurrency(row.runningBalance)}</strong></span>
              </span>
            </button>
          ))}
          {!visibleRows.length ? <p className="muted">No cash adjustments match this filter.</p> : null}
          </div>
          {mobileRows.length < visibleRows.length ? (
            <button type="button" className="mobile-records-more" onClick={() => setMobileVisibleCount((count) => count + mobilePageSize)}>
              {t('ui.showMoreRecords')}
            </button>
          ) : null}
          <div className="table-wrap desktop-only-table">
            <table className="cash-ledger-table">
            <thead>
              <tr>
                <th role="button" tabIndex={0} onClick={() => toggleSort('occurredAt')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('occurredAt')}>Date{sortMark('occurredAt')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('type')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('type')}>Type{sortMark('type')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('amount')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('amount')}>Amount{sortMark('amount')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('signedAmount')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('signedAmount')}>Signed Amount{sortMark('signedAmount')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('runningBalance')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('runningBalance')}>Running Balance{sortMark('runningBalance')}</th>
              </tr>
            </thead>
            <tbody>
              {visibleRows.map((item) => (
                <tr key={item.id} className="mobile-click-row" onClick={() => openCashRow(item)}>
                  <td>{formatDateTime(item.occurredAt)}</td>
                  <td><span className={`cash-type-pill cash-type-${String(item.type).toLowerCase()}`}>{item.type}</span></td>
                  <td>{formatCurrency(item.amount)}</td>
                  <td className={toNumber(item.signedAmount) >= 0 ? 'positive' : 'negative'}>{formatCurrency(item.signedAmount)}</td>
                  <td className={toNumber(item.runningBalance) >= 0 ? 'positive' : 'negative'}>{formatCurrency(item.runningBalance)}</td>
                </tr>
              ))}
              {!visibleRows.length ? (
                <tr>
                  <td colSpan="5" className="muted">No cash adjustments match this filter.</td>
                </tr>
              ) : null}
            </tbody>
            </table>
          </div>
        </div> : null}
      </section>

      <BottomSheet open={cashSheetOpen} title="Record Cash" onClose={() => setCashSheetOpen(false)}>
        <SegmentedControl
          label="Cash action"
          value={mobileEntryTab}
          onChange={setMobileEntryTab}
          options={[
            { value: 'record', label: 'Record' },
            { value: 'import', label: 'Import CSV' }
          ]}
        />
        <div className="mobile-sheet-section">
          {mobileEntryTab === 'record' ? cashForm : importCashPanel}
        </div>
      </BottomSheet>

      <BottomSheet open={filterSheetOpen} title="Ledger Filter" onClose={() => setFilterSheetOpen(false)}>
        <div className="stack-form">
          <label>
            Type
            <select value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)}>
              <option value="ALL">All</option>
              <option value="DEPOSIT">Deposits</option>
              <option value="WITHDRAWAL">Withdrawals</option>
            </select>
          </label>
          <label>
            From
            <DateInput value={fromDate} max={toDate || undefined} onChange={(e) => updateFromDate(e.target.value)} />
          </label>
          <label>
            To
            <DateInput value={toDate} min={fromDate || undefined} onChange={(e) => updateToDate(e.target.value)} />
          </label>
        </div>
      </BottomSheet>

      <RowDetailSheet
        open={Boolean(selectedCashRow)}
        title={selectedCashRow ? selectedCashRow.type : 'Cash Record'}
        eyebrow={selectedCashRow ? formatDateTime(selectedCashRow.occurredAt) : ''}
        onClose={() => { setSelectedCashRow(null); setEditingCashRow(false); }}
        actions={selectedCashRow && !selectedCashRow.transactionId ? (
          editingCashRow ? <><button type="button" onClick={saveCashEdit}>Save</button><button type="button" className="secondary-button" onClick={() => setEditingCashRow(false)}>Cancel</button></> : <><button type="button" onClick={() => setEditingCashRow(true)}><Pencil size={16} aria-hidden="true" /> Edit</button><button type="button" className="row-danger-btn" onClick={() => setDeleteTarget(selectedCashRow)}>Delete</button></>
        ) : null}
      >
        {selectedCashRow ? (
          editingCashRow ? (
            <div className="stack-form sheet-edit-form">
              <FormField label="Cash type" hint="Choose whether this record adds to or reduces cash.">
                <select value={cashEditForm.type} onChange={(event) => setCashEditForm({ ...cashEditForm, type: event.target.value })}><option value="DEPOSIT">Deposit</option><option value="WITHDRAWAL">Withdrawal</option></select>
              </FormField>
              <FormField label="Amount (USD)">
                <input type="number" min="0.0001" step="0.01" inputMode="decimal" value={cashEditForm.amount} onChange={(event) => setCashEditForm({ ...cashEditForm, amount: event.target.value })} />
              </FormField>
              <FormField label="Record date">
                <DateInput value={cashEditForm.date} onChange={(event) => setCashEditForm({ ...cashEditForm, date: event.target.value })} />
              </FormField>
            </div>
          ) : <div className="sheet-detail-list">
            <div><span>Date & Time</span><strong>{formatDateTime(selectedCashRow.occurredAt)}</strong></div>
            <div><span>Type</span><strong className={selectedCashRow.type === 'DEPOSIT' ? 'positive' : 'negative'}>{selectedCashRow.type === 'DEPOSIT' ? 'Deposit' : 'Withdrawal'}</strong></div>
            <div><span>Amount</span><strong>{formatCurrency(selectedCashRow.amount)}</strong></div>
            <div><span>Signed Amount</span><strong className={toNumber(selectedCashRow.signedAmount) >= 0 ? 'positive' : 'negative'}>{formatCurrency(selectedCashRow.signedAmount)}</strong></div>
            <div><span>Running Balance</span><strong>{formatCurrency(selectedCashRow.runningBalance)}</strong></div>
            <div><span>Source</span><strong>{selectedCashRow.transactionId ? `Transaction #${selectedCashRow.transactionId}` : 'Manual cash adjustment'}</strong></div>
            <div><span>Record ID</span><strong>#{selectedCashRow.id}</strong></div>
          </div>
        ) : null}
      </RowDetailSheet>

      <ConfirmDialog open={Boolean(deleteTarget)} title="Delete cash adjustment?" description={deleteTarget ? `This will permanently remove the ${deleteTarget.type.toLowerCase()} of ${formatCurrency(deleteTarget.amount)}.` : ''} confirmLabel="Delete adjustment" onConfirm={confirmDelete} onClose={() => setDeleteTarget(null)} />
    </>
  );
}
