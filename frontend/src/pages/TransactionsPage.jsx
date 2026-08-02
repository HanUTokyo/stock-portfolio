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
import useIsMobile from '../hooks/useIsMobile';
import { buildTransactionsImportCsv, downloadCsv } from '../utils/csvExport';

const defaultSort = { key: 'date', direction: 'desc' };
const mobilePageSize = 30;

function toNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

const quantityStep = 0.00000001;
const quantityStepperStep = 0.0001;
const quantityMin = 0.00000001;

function formatDateOnly(value) {
  return new Date(value).toLocaleDateString();
}

function formatDateTime(value) {
  return new Date(value).toLocaleString();
}

function toDateInput(value) {
  const d = new Date(value);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function compareValues(a, b, sort) {
  let result = 0;

  if (sort.key === 'date') {
    result = new Date(a.executedAt).getTime() - new Date(b.executedAt).getTime();
  } else if (sort.key === 'symbol' || sort.key === 'type') {
    result = String(a[sort.key]).localeCompare(String(b[sort.key]));
  } else if (sort.key === 'quantity' || sort.key === 'price') {
    result = toNumber(a[sort.key]) - toNumber(b[sort.key]);
  } else if (sort.key === 'totalCost') {
    result = toNumber(a.quantity) * toNumber(a.price) - toNumber(b.quantity) * toNumber(b.price);
  }

  return sort.direction === 'asc' ? result : -result;
}

export default function TransactionsPage({
  transactions,
  onDeleteTransaction,
  onUpdateTransaction,
  transactionForm,
  setTransactionForm,
  onRecordTransaction,
  importFile,
  setImportFile,
  importLoading,
  importResult,
  onImportDryRun,
  onImportCsv,
  onDownloadImportErrors
}) {
  const { t } = useTranslation();
  const isMobile = useIsMobile();
  const [sort, setSort] = useState(defaultSort);
  const [deletingId, setDeletingId] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [transactionsCollapsed, setTransactionsCollapsed] = useState(false);
  const [editForm, setEditForm] = useState({ symbol: '', type: 'BUY', quantity: '', price: '', note: '', date: '' });
  const [mobileEntryOpen, setMobileEntryOpen] = useState(false);
  const [mobileEntryTab, setMobileEntryTab] = useState('record');
  const [selectedTransaction, setSelectedTransaction] = useState(null);
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [mobileVisibleCount, setMobileVisibleCount] = useState(mobilePageSize);
  const [filters, setFilters] = useState({ search: '', from: '', to: '' });

  const sortedTransactions = useMemo(() => {
    const search = filters.search.trim().toLowerCase();
    const from = filters.from ? new Date(`${filters.from}T00:00:00`).getTime() : null;
    const to = filters.to ? new Date(`${filters.to}T23:59:59.999`).getTime() : null;
    return transactions
      .filter((txn) => {
        const executedAt = new Date(txn.executedAt).getTime();
        const matchesSearch = !search || `${txn.symbol || ''} ${txn.note || ''}`.toLowerCase().includes(search);
        return matchesSearch
          && (from === null || executedAt >= from)
          && (to === null || executedAt <= to);
      })
      .sort((a, b) => compareValues(a, b, sort));
  }, [transactions, sort, filters]);
  const mobileTransactions = useMemo(() => sortedTransactions.slice(0, mobileVisibleCount), [sortedTransactions, mobileVisibleCount]);

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

  function adjustTransactionField(field, direction, step, min, decimals) {
    setTransactionForm((prev) => {
      const current = toNumber(prev[field] || 0);
      const next = Math.max(min, current + direction * step);
      const value = decimals > 0 ? next.toFixed(decimals) : String(Math.round(next));
      return { ...prev, [field]: value };
    });
  }

  function handleDelete(txn) {
    if (deletingId !== null || editingId !== null) return;
    setDeleteTarget(txn);
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeletingId(deleteTarget.id);
    try {
      await onDeleteTransaction(deleteTarget.id);
      setSelectedTransaction(null);
      setDeleteTarget(null);
    } finally {
      setDeletingId(null);
    }
  }

  function startEdit(txn) {
    setEditingId(txn.id);
    setEditForm({
      symbol: txn.symbol,
      type: txn.type,
      quantity: String(txn.quantity),
      price: String(txn.price),
      note: txn.note || '',
      date: toDateInput(txn.executedAt)
    });
  }

  function openTransaction(txn) {
    setSelectedTransaction(txn);
  }

  function cancelEdit() {
    setEditingId(null);
    setEditForm({ symbol: '', type: 'BUY', quantity: '', price: '', note: '', date: '' });
  }

  async function saveEdit(txnId) {
    const executedAt = new Date(`${editForm.date}T00:00:00`).toISOString();
    await onUpdateTransaction(txnId, {
      symbol: editForm.symbol,
      type: editForm.type,
      quantity: Number(editForm.quantity),
      price: Number(editForm.price),
      note: editForm.note,
      executedAt
    });
    cancelEdit();
    setSelectedTransaction(null);
  }

  function handleExportCsv() {
    const csv = buildTransactionsImportCsv(sortedTransactions);
    const date = new Date().toISOString().slice(0, 10);
    downloadCsv(`transactions-${date}.csv`, csv);
  }

  const recordTransactionForm = (
    <form onSubmit={(event) => {
      onRecordTransaction(event);
      if (isMobile) setMobileEntryOpen(false);
    }} className="transaction-record-form">
      <input
        placeholder="Symbol"
        value={transactionForm.symbol}
        onChange={(e) => setTransactionForm({ ...transactionForm, symbol: e.target.value })}
        onBlur={(e) => setTransactionForm({ ...transactionForm, symbol: e.currentTarget.value.trim().toUpperCase() })}
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
      <div className="stepper-field">
        <input
          type="number"
          min={quantityMin}
          step={quantityStep}
          placeholder="Quantity"
          value={transactionForm.quantity}
          onChange={(e) => setTransactionForm({ ...transactionForm, quantity: e.target.value })}
          required
        />
        <div className="stepper-buttons">
          <button type="button" className="stepper-btn" onClick={() => adjustTransactionField('quantity', 1, quantityStepperStep, quantityMin, 8)}>+</button>
          <button type="button" className="stepper-btn" onClick={() => adjustTransactionField('quantity', -1, quantityStepperStep, quantityMin, 8)}>-</button>
        </div>
      </div>
      <div className="stepper-field">
        <input
          type="number"
          min="0.01"
          step="0.01"
          placeholder="Price"
          value={transactionForm.price}
          onChange={(e) => setTransactionForm({ ...transactionForm, price: e.target.value })}
          required
        />
        <div className="stepper-buttons">
          <button type="button" className="stepper-btn" onClick={() => adjustTransactionField('price', 1, 0.01, 0.01, 2)}>+</button>
          <button type="button" className="stepper-btn" onClick={() => adjustTransactionField('price', -1, 0.01, 0.01, 2)}>-</button>
        </div>
      </div>
      <DateInput
        value={transactionForm.tradeDate}
        onChange={(e) => setTransactionForm({ ...transactionForm, tradeDate: e.target.value })}
        required
      />
      <textarea
        rows={3}
        placeholder="Transaction note (optional)"
        value={transactionForm.note || ''}
        onChange={(e) => setTransactionForm({ ...transactionForm, note: e.target.value })}
      />
      <button type="submit" className="quick-trade-submit">Record Transaction</button>
    </form>
  );

  const importTransactionPanel = (
    <div className="stack-form">
      <input
        type="file"
        accept=".csv,text/csv"
        onChange={(e) => setImportFile(e.target.files?.[0] || null)}
      />
      <div className="button-row">
        <button type="button" onClick={onImportDryRun} disabled={importLoading || !importFile}>Dry-Run Validate</button>
        <button type="button" onClick={onImportCsv} disabled={importLoading || !importFile}>
          {importLoading ? 'Importing...' : 'Import CSV'}
        </button>
        <button type="button" onClick={onDownloadImportErrors} disabled={!importFile}>Export Failed Rows</button>
      </div>
      {importResult ? (
        <div className="import-result">
          <p>
            dryRun={String(importResult.dryRun)} | total={importResult.totalRows} | imported={importResult.importedRows} | skipped={importResult.skippedRows} | failed={importResult.failedRows}
          </p>
          {importResult.sampleErrors?.length ? (
            <ul>
              {importResult.sampleErrors.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          ) : (
            <p>No sample errors.</p>
          )}
        </div>
      ) : null}
    </div>
  );

  const selectedEditing = selectedTransaction && editingId === selectedTransaction.id;

  return (
    <>
      <MobileActionBar
        className="transactions-mobile-actions"
        actions={[
          { key: 'record', label: 'Record', icon: Plus, onClick: () => { setMobileEntryTab('record'); setMobileEntryOpen(true); } }
        ]}
      />

      <section className="panel-grid transactions-entry-stack mobile-hide">
        <article className="panel">
          <h2>Record Transaction</h2>
          {recordTransactionForm}
        </article>

        <article className="panel">
          <h2>Import Transaction CSV</h2>
          {importTransactionPanel}
        </article>

      </section>

      <section className="panel">
        <div className="collapsible-header transactions-list-header">
          <h2>Transactions</h2>
          <div className="header-actions">
            <button type="button" className="table-toggle" onClick={handleExportCsv} disabled={!sortedTransactions.length}>
              <Download size={16} aria-hidden="true" />
              <span>Export CSV</span>
            </button>
            <button type="button" className="table-toggle" onClick={() => setTransactionsCollapsed((prev) => !prev)}>
              {transactionsCollapsed ? <Maximize2 size={16} aria-hidden="true" /> : <Minimize2 size={16} aria-hidden="true" />}
              <span>{transactionsCollapsed ? 'Expand' : 'Collapse'}</span>
            </button>
          </div>
        </div>
        {!transactionsCollapsed ? <>
          <div className="record-filter-bar" data-record-filter="transactions">
            <label className="record-filter-search"><span>Search</span><input value={filters.search} onChange={(event) => updateFilter('search', event.target.value)} placeholder="Symbol or note" /></label>
            <label><span>From</span><DateInput value={filters.from} onChange={(event) => updateFilter('from', event.target.value)} /></label>
            <label><span>To</span><DateInput value={filters.to} onChange={(event) => updateFilter('to', event.target.value)} /></label>
            <div className="record-filter-actions"><span aria-live="polite">{sortedTransactions.length} of {transactions.length} records</span><button type="button" className="row-secondary-btn" onClick={clearFilters} disabled={!filters.search && !filters.from && !filters.to}>Clear</button></div>
          </div>
          <div className="mobile-record-list">
            {mobileTransactions.map((txn) => (
              <button key={`mobile-transaction-${txn.id}`} type="button" className="record-card record-card-button transaction-record-card" onClick={() => openTransaction(txn)}>
                <span className="record-card-head">
                  <strong className="record-card-symbol">{txn.symbol}</strong>
                  <span className={`record-type-badge ${txn.type === 'BUY' ? 'is-buy' : 'is-sell'}`}>{txn.type}</span>
                </span>
                <span className="record-card-metrics">
                  <span><small>{formatDateOnly(txn.executedAt)}</small><strong>{txn.quantity} shares</strong></span>
                  <span><small>@ ${toNumber(txn.price).toFixed(4)}</small><strong>${(toNumber(txn.quantity) * toNumber(txn.price)).toFixed(2)}</strong></span>
                </span>
                {txn.note?.trim() ? <span className="record-card-note">{txn.note}</span> : null}
              </button>
            ))}
            {!sortedTransactions.length ? <p className="muted">{transactions.length ? 'No transactions match these filters.' : 'No transactions yet.'}</p> : null}
          </div>
          {mobileTransactions.length < sortedTransactions.length ? (
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
                <th role="button" tabIndex={0} onClick={() => toggleSort('type')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('type')}>Type{sortMark('type')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('quantity')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('quantity')}>Quantity{sortMark('quantity')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('price')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('price')}>Price{sortMark('price')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('totalCost')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('totalCost')}>Total Cost{sortMark('totalCost')}</th>
                <th>Note</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {sortedTransactions.map((txn) => (
                <tr key={txn.id} className="record-table-row" onClick={() => openTransaction(txn)}>
                  <td>{formatDateOnly(txn.executedAt)}</td>
                  <td className="symbol-cell">{txn.symbol}</td>
                  <td><span className={`record-type-badge ${txn.type === 'BUY' ? 'is-buy' : 'is-sell'}`}>{txn.type}</span></td>
                  <td>{txn.quantity}</td>
                  <td>${toNumber(txn.price).toFixed(4)}</td>
                  <td>${(toNumber(txn.quantity) * toNumber(txn.price)).toFixed(4)}</td>
                  <td><span className="cell-note" title={txn.note || ''}>{txn.note?.trim() ? txn.note : '--'}</span></td>
                  <td><button type="button" className="row-secondary-btn" onClick={(event) => { event.stopPropagation(); openTransaction(txn); }}>View</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </> : null}
      </section>

      <BottomSheet open={mobileEntryOpen} title="Transactions" onClose={() => setMobileEntryOpen(false)} fullHeight>
        <SegmentedControl
          label="Transaction action"
          value={mobileEntryTab}
          onChange={setMobileEntryTab}
          options={[
            { value: 'record', label: 'Record' },
            { value: 'import', label: 'Import CSV' }
          ]}
        />
        <div className="mobile-sheet-section">
          {mobileEntryTab === 'record' ? recordTransactionForm : importTransactionPanel}
        </div>
      </BottomSheet>

      <RowDetailSheet
        open={Boolean(selectedTransaction)}
        title={selectedTransaction ? `${selectedTransaction.symbol} ${selectedTransaction.type}` : 'Transaction'}
        eyebrow={selectedTransaction ? formatDateOnly(selectedTransaction.executedAt) : ''}
        onClose={() => {
          setSelectedTransaction(null);
          cancelEdit();
        }}
        actions={selectedTransaction ? (
          selectedEditing ? (
            <>
              <button type="button" onClick={() => saveEdit(selectedTransaction.id)}>Save</button>
              <button type="button" className="secondary-button" onClick={cancelEdit}>Cancel</button>
            </>
          ) : (
            <>
              <button type="button" onClick={() => startEdit(selectedTransaction)}>Edit</button>
              <button type="button" className="row-danger-btn" onClick={() => handleDelete(selectedTransaction)} disabled={deletingId === selectedTransaction.id}>
                {deletingId === selectedTransaction.id ? 'Deleting...' : 'Delete'}
              </button>
            </>
          )
        ) : null}
      >
        {selectedTransaction ? (
          selectedEditing ? (
            <div className="stack-form sheet-edit-form">
              <FormField label="Trade date">
                <DateInput value={editForm.date} onChange={(e) => setEditForm({ ...editForm, date: e.target.value })} />
              </FormField>
              <FormField label="Symbol" hint="Ticker or option contract symbol.">
                <input autoCapitalize="characters" value={editForm.symbol} onChange={(e) => setEditForm({ ...editForm, symbol: e.target.value })} />
              </FormField>
              <FormField label="Direction" hint="BUY adds units; SELL removes units.">
                <select value={editForm.type} onChange={(e) => setEditForm({ ...editForm, type: e.target.value })}>
                  <option value="BUY">BUY</option>
                  <option value="SELL">SELL</option>
                </select>
              </FormField>
              <FormField label="Quantity">
                <input type="number" min={quantityMin} step={quantityStep} inputMode="decimal" value={editForm.quantity} onChange={(e) => setEditForm({ ...editForm, quantity: e.target.value })} />
              </FormField>
              <FormField label="Price per unit (USD)">
                <input type="number" min="0.0001" step="0.01" inputMode="decimal" value={editForm.price} onChange={(e) => setEditForm({ ...editForm, price: e.target.value })} />
              </FormField>
              <FormField label="Note (optional)">
                <textarea rows={3} value={editForm.note} onChange={(e) => setEditForm({ ...editForm, note: e.target.value })} />
              </FormField>
            </div>
          ) : (
            <div className="sheet-detail-list transaction-detail-list">
              <div><span>Symbol</span><strong>{selectedTransaction.symbol}</strong></div>
              <div><span>Direction</span><strong className={selectedTransaction.type === 'BUY' ? 'positive' : 'negative'}>{selectedTransaction.type}</strong></div>
              <div><span>Executed at</span><strong>{formatDateTime(selectedTransaction.executedAt)}</strong></div>
              <div><span>Quantity</span><strong>{selectedTransaction.quantity} shares</strong></div>
              <div><span>Price per share</span><strong>${toNumber(selectedTransaction.price).toFixed(4)}</strong></div>
              <div><span>Gross value</span><strong>${(toNumber(selectedTransaction.quantity) * toNumber(selectedTransaction.price)).toFixed(4)}</strong></div>
              <div className="transaction-detail-note"><span>Note</span><strong>{selectedTransaction.note?.trim() ? selectedTransaction.note : '--'}</strong></div>
              <div><span>Transaction ID</span><strong>#{selectedTransaction.id}</strong></div>
            </div>
          )
        ) : null}
      </RowDetailSheet>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        title="Delete transaction?"
        description={deleteTarget ? `This will permanently remove ${deleteTarget.symbol} ${deleteTarget.type} on ${formatDateOnly(deleteTarget.executedAt)}.` : ''}
        confirmLabel="Delete transaction"
        pending={deletingId !== null}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </>
  );
}
