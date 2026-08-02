import { useMemo, useRef, useState } from 'react';
import { Download, Pencil, Search, Tag, Trash2, Upload } from 'lucide-react';
import RowDetailSheet from '../components/RowDetailSheet';
import ConfirmDialog from '../components/ConfirmDialog';
import { buildClassificationsCsv, downloadCsv } from '../utils/csvExport';

function normalizeSymbol(symbol) {
  return String(symbol || '').trim().toUpperCase();
}

function emptyDraft(position = {}) {
  return {
    assetClass: position.assetClass || '',
    instrumentType: position.instrumentType || '',
    underlying: position.underlying || '',
    sector: position.sector || '',
    region: position.region || ''
  };
}

function mergeRows(positions, holdings, transactions) {
  const bySymbol = new Map();
  positions.forEach((position) => {
    const symbol = normalizeSymbol(position.symbol);
    if (symbol) bySymbol.set(symbol, { symbol, position });
  });
  holdings.forEach((holding) => {
    const symbol = normalizeSymbol(holding.symbol);
    if (symbol && !bySymbol.has(symbol)) bySymbol.set(symbol, { symbol, position: null });
  });
  transactions.forEach((transaction) => {
    const symbol = normalizeSymbol(transaction.symbol);
    if (symbol && !bySymbol.has(symbol)) bySymbol.set(symbol, { symbol, position: null });
  });
  return [...bySymbol.values()].sort((a, b) => a.symbol.localeCompare(b.symbol));
}

export default function ClassificationsPage({
  positions = [],
  holdings = [],
  transactions = [],
  onSaveMetadata
}) {
  const [draft, setDraft] = useState(emptyDraft());
  const [saving, setSaving] = useState(false);
  const [selectedRow, setSelectedRow] = useState(null);
  const [sheetMode, setSheetMode] = useState('view');
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [importing, setImporting] = useState(false);
  const [filterText, setFilterText] = useState('');
  const [viewMode, setViewMode] = useState('ALL');
  const importInputRef = useRef(null);
  const rows = useMemo(() => mergeRows(positions, holdings, transactions), [positions, holdings, transactions]);
  const classifiedCount = rows.filter((row) => Boolean(row.position?.assetClass)).length;
  const filteredRows = useMemo(() => {
    const query = filterText.trim().toUpperCase();
    return rows.filter((row) => {
      const item = row.position || {};
      const matchesView = viewMode === 'UNCLASSIFIED' ? !item.assetClass : true;
      const matchesQuery = !query || [row.symbol, item.assetClass, item.instrumentType, item.sector, item.region, item.underlying]
        .some((value) => String(value || '').toUpperCase().includes(query));
      return matchesView && matchesQuery;
    });
  }, [rows, filterText, viewMode]);

  function startEdit(row) {
    setDraft(emptyDraft(row.position || {}));
  }

  function openRow(row) {
    setSelectedRow(row);
    startEdit(row);
    setSheetMode('view');
  }

  function cancelEdit() {
    setDraft(emptyDraft());
  }

  async function saveEdit(symbol) {
    if (!symbol || !onSaveMetadata) return;
    setSaving(true);
    try {
      await onSaveMetadata(symbol, draft);
      cancelEdit();
      setSelectedRow(null);
    } finally {
      setSaving(false);
    }
  }

  function handleExportCsv() {
    const date = new Date().toISOString().slice(0, 10);
    downloadCsv(`classifications-${date}.csv`, buildClassificationsCsv(filteredRows));
  }

  async function handleImport(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !onSaveMetadata) return;
    setImporting(true);
    try {
      const text = await file.text();
      const lines = text.split(/\r?\n/).filter(Boolean);
      const [, ...records] = lines;
      for (const line of records) {
        const [symbol, assetClass = '', instrumentType = '', underlying = '', sector = '', region = ''] = line.split(',').map((cell) => cell.trim().replace(/^"|"$/g, '').replaceAll('""', '"'));
        if (!symbol) continue;
        await onSaveMetadata(normalizeSymbol(symbol), { assetClass, instrumentType, underlying, sector, region });
      }
    } finally {
      setImporting(false);
    }
  }

  async function clearMetadata() {
    if (!deleteTarget || !onSaveMetadata) return;
    setSaving(true);
    try {
      await onSaveMetadata(deleteTarget.symbol, emptyDraft());
      setDeleteTarget(null);
      setSelectedRow(null);
      cancelEdit();
    } finally {
      setSaving(false);
    }
  }

  function updateField(key, value) {
    setDraft((prev) => ({ ...prev, [key]: value }));
  }

  return (
    <section className="panel classifications-panel">
      <header className="classifications-header">
        <div className="classifications-title-group">
          <div>
            <p className="eyebrow">Portfolio taxonomy</p>
            <h2>Asset Classifications</h2>
          </div>
          <div className="classifications-summary" aria-label="Classification summary">
            <span><strong>{classifiedCount}</strong> classified</span>
            <span><strong>{rows.length - classifiedCount}</strong> pending</span>
          </div>
        </div>
        <div className="header-actions classification-actions">
          <button type="button" className="table-toggle" onClick={handleExportCsv} disabled={!filteredRows.length}><Download size={16} aria-hidden="true" />Export CSV</button>
          <button type="button" className="table-toggle" disabled={importing} onClick={() => importInputRef.current?.click()}><Upload size={16} aria-hidden="true" />{importing ? 'Importing...' : 'Import CSV'}</button>
          <input ref={importInputRef} className="classification-import-input" type="file" accept=".csv,text/csv" onChange={handleImport} />
        </div>
      </header>
      <div className="classifications-controls">
        <label className="classification-search">
          <Search size={16} aria-hidden="true" />
          <input placeholder="Search symbol or category" value={filterText} onChange={(event) => setFilterText(event.target.value)} />
        </label>
        <div className="rank-filter-tabs classification-tabs">
          <button type="button" className={viewMode === 'ALL' ? 'rank-tab active' : 'rank-tab'} onClick={() => setViewMode('ALL')}>All {rows.length}</button>
          <button type="button" className={viewMode === 'UNCLASSIFIED' ? 'rank-tab active' : 'rank-tab'} onClick={() => setViewMode('UNCLASSIFIED')}>Pending {rows.length - classifiedCount}</button>
        </div>
      </div>
      <div className="classification-mobile-list">
        {filteredRows.map((row) => {
          const item = row.position || {};
          return (
            <button key={`mobile-${row.symbol}`} type="button" className="classification-mobile-card" onClick={() => openRow(row)}>
              <span className="classification-card-header">
                <strong>{row.symbol}</strong>
                <span className={`classification-asset-badge ${item.assetClass ? '' : 'is-pending'}`}>{item.assetClass || 'Unclassified'}</span>
              </span>
              <span className="classification-card-primary">{item.instrumentType || 'Instrument not set'}<i aria-hidden="true">•</i>{item.sector || 'Sector not set'}</span>
              <span className="classification-card-details">
                <span><small>Underlying</small><strong>{item.underlying || '--'}</strong></span>
                <span><small>Region</small><strong>{item.region || '--'}</strong></span>
              </span>
            </button>
          );
        })}
        {!filteredRows.length ? <p className="classifications-empty"><Tag size={18} aria-hidden="true" /> No matching classifications.</p> : null}
      </div>
      <div className="table-wrap classification-table-wrap desktop-only-table">
        <table>
          <thead>
            <tr>
              <th>Symbol</th>
              <th>Asset Class</th>
              <th>Instrument Type</th>
              <th>Underlying</th>
              <th>Sector</th>
              <th>Region</th>
              <th>Last Updated</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {filteredRows.map((row) => {
              const item = row.position || {};
              return (
                <tr key={row.symbol} className="record-table-row" onClick={() => openRow(row)}>
                  <td className="symbol-cell">{row.symbol}</td>
                  <td>{item.assetClass || '--'}</td>
                  <td>{item.instrumentType || '--'}</td>
                  <td>{item.underlying || '--'}</td>
                  <td>{item.sector || '--'}</td>
                  <td>{item.region || '--'}</td>
                  <td>{item.metadataUpdatedAt ? new Date(item.metadataUpdatedAt).toLocaleString() : '--'}</td>
                  <td>
                    <button type="button" className="secondary-button" onClick={(event) => { event.stopPropagation(); openRow(row); }}>View</button>
                  </td>
                </tr>
              );
            })}
            {!filteredRows.length ? (
              <tr>
                <td colSpan={8} className="muted">No symbols yet.</td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>

      <RowDetailSheet
        open={Boolean(selectedRow)}
        title={selectedRow ? selectedRow.symbol : 'Classification'}
        eyebrow="Asset metadata"
        onClose={() => {
          setSelectedRow(null);
          cancelEdit();
        }}
        actions={selectedRow ? (sheetMode === 'edit' ? <><button type="button" disabled={saving} onClick={() => saveEdit(selectedRow.symbol)}>Save</button><button type="button" className="secondary-button" disabled={saving} onClick={() => setSheetMode('view')}>Cancel</button></> : <><button type="button" onClick={() => setSheetMode('edit')}><Pencil size={16} aria-hidden="true" /> Edit</button><button type="button" className="row-danger-btn" onClick={() => setDeleteTarget(selectedRow)}><Trash2 size={16} aria-hidden="true" /> Clear</button></>) : null}
      >
        {selectedRow ? (
          sheetMode === 'edit' ? <div className="classification-editor-form">
            <p className="classification-editor-intro">Define how this position appears across portfolio allocation, market context, and review screens.</p>
            <label><span>Asset class</span><input value={draft.assetClass} onChange={(event) => updateField('assetClass', event.target.value)} placeholder="EQUITY, BOND, CRYPTO..." /></label>
            <label><span>Instrument type</span><input value={draft.instrumentType} onChange={(event) => updateField('instrumentType', event.target.value)} placeholder="ETF, COMMON_STOCK, CALL_OPTION..." /></label>
            <label><span>Underlying</span><input value={draft.underlying} onChange={(event) => updateField('underlying', event.target.value)} placeholder="Optional underlying symbol" /></label>
            <label><span>Sector</span><input value={draft.sector} onChange={(event) => updateField('sector', event.target.value)} placeholder="Technology, Financials..." /></label>
            <label><span>Region</span><input value={draft.region} onChange={(event) => updateField('region', event.target.value)} placeholder="United States, Europe..." /></label>
          </div> : <div className="sheet-detail-list">
            <div><span>Asset class</span><strong>{selectedRow.position?.assetClass || '--'}</strong></div>
            <div><span>Instrument type</span><strong>{selectedRow.position?.instrumentType || '--'}</strong></div>
            <div><span>Underlying</span><strong>{selectedRow.position?.underlying || '--'}</strong></div>
            <div><span>Sector</span><strong>{selectedRow.position?.sector || '--'}</strong></div>
            <div><span>Region</span><strong>{selectedRow.position?.region || '--'}</strong></div>
            <div><span>Last updated</span><strong>{selectedRow.position?.metadataUpdatedAt ? new Date(selectedRow.position.metadataUpdatedAt).toLocaleString() : '--'}</strong></div>
          </div>
        ) : null}
      </RowDetailSheet>
      <ConfirmDialog open={Boolean(deleteTarget)} title="Clear classification metadata?" description={deleteTarget ? `This removes only the classification fields for ${deleteTarget.symbol}; its holdings and transactions stay intact.` : ''} confirmLabel="Clear metadata" onConfirm={clearMetadata} onClose={() => setDeleteTarget(null)} />
    </section>
  );
}
