import { useMemo, useState } from 'react';
import DateInput from '../components/DateInput';

const defaultSort = { key: 'date', direction: 'desc' };

function toNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function formatDateOnly(value) {
  return new Date(value).toLocaleDateString();
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
  const [sort, setSort] = useState(defaultSort);
  const [deletingId, setDeletingId] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState({ symbol: '', type: 'BUY', quantity: '', price: '', date: '' });

  const sortedTransactions = useMemo(() => {
    return [...transactions].sort((a, b) => compareValues(a, b, sort));
  }, [transactions, sort]);

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

  async function handleDelete(txn) {
    if (deletingId !== null || editingId !== null) return;
    const ok = window.confirm(`Delete transaction #${txn.id} (${txn.symbol} ${txn.type} ${txn.quantity} @ ${txn.price})?`);
    if (!ok) return;
    setDeletingId(txn.id);
    try {
      await onDeleteTransaction(txn.id);
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
      date: toDateInput(txn.executedAt)
    });
  }

  function cancelEdit() {
    setEditingId(null);
    setEditForm({ symbol: '', type: 'BUY', quantity: '', price: '', date: '' });
  }

  async function saveEdit(txnId) {
    const executedAt = new Date(`${editForm.date}T00:00:00`).toISOString();
    await onUpdateTransaction(txnId, {
      symbol: editForm.symbol,
      type: editForm.type,
      quantity: Number(editForm.quantity),
      price: Number(editForm.price),
      executedAt
    });
    cancelEdit();
  }

  return (
    <>
      <section className="panel-grid">
        <article className="panel">
          <h2>Record Transaction</h2>
          <form onSubmit={onRecordTransaction} className="stack-form">
            <input
              placeholder="Symbol"
              value={transactionForm.symbol}
              onChange={(e) => setTransactionForm({ ...transactionForm, symbol: e.target.value })}
              required
            />
            <select
              value={transactionForm.type}
              onChange={(e) => setTransactionForm({ ...transactionForm, type: e.target.value })}
            >
              <option value="BUY">BUY</option>
              <option value="SELL">SELL</option>
            </select>
            <div className="stepper-field">
              <input
                type="number"
                min="1"
                step="1"
                placeholder="Quantity"
                value={transactionForm.quantity}
                onChange={(e) => setTransactionForm({ ...transactionForm, quantity: e.target.value })}
                required
              />
              <div className="stepper-buttons">
                <button type="button" className="stepper-btn" onClick={() => adjustTransactionField('quantity', 1, 1, 1, 0)}>+</button>
                <button type="button" className="stepper-btn" onClick={() => adjustTransactionField('quantity', -1, 1, 1, 0)}>-</button>
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
            <button type="submit">Record Transaction</button>
          </form>
        </article>

        <article className="panel">
          <h2>Import Transaction CSV</h2>
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
        </article>
      </section>

      <section className="panel">
        <h2>Transactions</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th role="button" tabIndex={0} onClick={() => toggleSort('date')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('date')}>Date{sortMark('date')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('symbol')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('symbol')}>Symbol{sortMark('symbol')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('type')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('type')}>Type{sortMark('type')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('quantity')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('quantity')}>Quantity{sortMark('quantity')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('price')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('price')}>Price{sortMark('price')}</th>
                <th role="button" tabIndex={0} onClick={() => toggleSort('totalCost')} onKeyDown={(e) => e.key === 'Enter' && toggleSort('totalCost')}>Total Cost{sortMark('totalCost')}</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {sortedTransactions.map((txn) => {
                const editing = editingId === txn.id;
                return (
                  <tr key={txn.id}>
                    <td>{editing ? <DateInput value={editForm.date} onChange={(e) => setEditForm({ ...editForm, date: e.target.value })} /> : formatDateOnly(txn.executedAt)}</td>
                    <td>{editing ? <input value={editForm.symbol} onChange={(e) => setEditForm({ ...editForm, symbol: e.target.value })} /> : txn.symbol}</td>
                    <td>{editing ? (
                      <select value={editForm.type} onChange={(e) => setEditForm({ ...editForm, type: e.target.value })}>
                        <option value="BUY">BUY</option>
                        <option value="SELL">SELL</option>
                      </select>
                    ) : txn.type}</td>
                    <td>{editing ? <input type="number" min="1" step="1" value={editForm.quantity} onChange={(e) => setEditForm({ ...editForm, quantity: e.target.value })} /> : txn.quantity}</td>
                    <td>{editing ? <input type="number" min="0.0001" step="0.01" value={editForm.price} onChange={(e) => setEditForm({ ...editForm, price: e.target.value })} /> : `$${txn.price}`}</td>
                    <td>${(toNumber(editing ? editForm.quantity : txn.quantity) * toNumber(editing ? editForm.price : txn.price)).toFixed(4)}</td>
                    <td className="button-row">
                      {editing ? (
                        <>
                          <button type="button" className="row-secondary-btn" onClick={() => saveEdit(txn.id)}>Save</button>
                          <button type="button" className="row-secondary-btn" onClick={cancelEdit}>Cancel</button>
                        </>
                      ) : (
                        <>
                          <button type="button" className="row-secondary-btn" onClick={() => startEdit(txn)} disabled={deletingId === txn.id}>Edit</button>
                          <button
                            type="button"
                            className="row-danger-btn"
                            onClick={() => handleDelete(txn)}
                            disabled={deletingId === txn.id}
                          >
                            {deletingId === txn.id ? 'Deleting...' : 'Delete'}
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
