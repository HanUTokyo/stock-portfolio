import { useEffect, useMemo, useState } from 'react';

function normalizeSymbol(value) {
  return String(value || '').trim().toUpperCase();
}

function shortText(value, max = 80) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  if (!text) return '--';
  if (text.length <= max) return text;
  return `${text.slice(0, max)}...`;
}

export default function NotesPage({ transactions, holdings, stockNotes, onSaveStockNote }) {
  const [selectedSymbol, setSelectedSymbol] = useState('');
  const [draftNote, setDraftNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [filterText, setFilterText] = useState('');
  const [viewMode, setViewMode] = useState('ALL');

  const notesBySymbol = useMemo(() => {
    return (stockNotes || []).reduce((acc, item) => {
      const symbol = normalizeSymbol(item.symbol);
      if (symbol) acc[symbol] = item;
      return acc;
    }, {});
  }, [stockNotes]);

  const allSymbols = useMemo(() => {
    const set = new Set();
    (transactions || []).forEach((txn) => {
      const symbol = normalizeSymbol(txn.symbol);
      if (symbol) set.add(symbol);
    });
    (stockNotes || []).forEach((item) => {
      const symbol = normalizeSymbol(item.symbol);
      if (symbol) set.add(symbol);
    });
    return [...set].sort((a, b) => a.localeCompare(b));
  }, [transactions, stockNotes]);

  const currentHoldingSymbols = useMemo(() => {
    const set = new Set();
    (holdings || []).forEach((item) => {
      if (Number(item.quantity) > 0) {
        const symbol = normalizeSymbol(item.symbol);
        if (symbol) set.add(symbol);
      }
    });
    return set;
  }, [holdings]);

  const noteRows = useMemo(() => {
    return allSymbols.map((symbol) => {
      const noteItem = notesBySymbol[symbol];
      const content = noteItem?.note || '';
      const hasNote = content.trim().length > 0;
      return {
        symbol,
        note: content,
        hasNote,
        updatedAt: noteItem?.updatedAt || null,
        isCurrent: currentHoldingSymbols.has(symbol)
      };
    });
  }, [allSymbols, notesBySymbol, currentHoldingSymbols]);

  const filteredRows = useMemo(() => {
    const query = filterText.trim().toUpperCase();
    return noteRows
      .filter((row) => (viewMode === 'WITH_NOTE' ? row.hasNote : true))
      .filter((row) => (query ? row.symbol.includes(query) : true));
  }, [noteRows, viewMode, filterText]);

  const allHistoricalNotes = useMemo(() => {
    return noteRows
      .filter((row) => row.hasNote)
      .sort((a, b) => {
        const aTime = a.updatedAt ? new Date(a.updatedAt).getTime() : 0;
        const bTime = b.updatedAt ? new Date(b.updatedAt).getTime() : 0;
        if (aTime !== bTime) return bTime - aTime;
        return a.symbol.localeCompare(b.symbol);
      });
  }, [noteRows]);

  const selectedNote = notesBySymbol[selectedSymbol];
  const savedNote = selectedNote?.note || '';
  const isDirty = draftNote !== savedNote;

  useEffect(() => {
    if (!allSymbols.length) {
      setSelectedSymbol('');
      return;
    }
    if (!selectedSymbol || !allSymbols.includes(selectedSymbol)) {
      setSelectedSymbol(allSymbols[0]);
    }
  }, [allSymbols, selectedSymbol]);

  useEffect(() => {
    if (!selectedSymbol) {
      setDraftNote('');
      return;
    }
    setDraftNote(notesBySymbol[selectedSymbol]?.note || '');
  }, [selectedSymbol, notesBySymbol]);

  async function handleSave() {
    if (!selectedSymbol) return;
    setSaving(true);
    try {
      await onSaveStockNote(selectedSymbol, draftNote);
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <section className="panel-grid">
        <article className="panel">
          <h2>Stock Notes</h2>
          <div className="stack-form">
            <select value={selectedSymbol} onChange={(e) => setSelectedSymbol(e.target.value)} disabled={!allSymbols.length}>
              {!allSymbols.length ? <option value="">No Symbol</option> : null}
              {allSymbols.map((symbol) => (
                <option key={symbol} value={symbol}>{symbol}</option>
              ))}
            </select>

            <textarea
              rows={12}
              placeholder="Write your trading plan / risk controls / thesis updates..."
              value={draftNote}
              onChange={(e) => setDraftNote(e.target.value)}
              disabled={!selectedSymbol}
            />

            <div className="button-row">
              <button type="button" onClick={handleSave} disabled={!selectedSymbol || saving || !isDirty}>
                {saving ? 'Saving...' : 'Save Note'}
              </button>
              <button type="button" className="row-secondary-btn" onClick={() => setDraftNote(savedNote)} disabled={!selectedSymbol || !isDirty || saving}>
                Revert
              </button>
            </div>

            {selectedSymbol ? (
              <p className="muted">
                Status: {isDirty ? 'Unsaved changes' : 'Saved'} | Last updated: {selectedNote?.updatedAt ? new Date(selectedNote.updatedAt).toLocaleString() : '--'}
              </p>
            ) : (
              <p className="muted">No historical symbols yet. Add transactions first.</p>
            )}
          </div>
        </article>

        <article className="panel">
          <h2>Note Overview</h2>
          <div className="stack-form">
            <input
              placeholder="Filter symbol"
              value={filterText}
              onChange={(e) => setFilterText(e.target.value.toUpperCase())}
            />
            <div className="rank-filter-tabs">
              <button type="button" className={viewMode === 'ALL' ? 'rank-tab active' : 'rank-tab'} onClick={() => setViewMode('ALL')}>
                All ({noteRows.length})
              </button>
              <button type="button" className={viewMode === 'WITH_NOTE' ? 'rank-tab active' : 'rank-tab'} onClick={() => setViewMode('WITH_NOTE')}>
                With Notes ({noteRows.filter((r) => r.hasNote).length})
              </button>
            </div>

            <div className="table-wrap notes-overview-wrap">
              <table className="notes-overview-table">
                <thead>
                  <tr>
                    <th>Symbol</th>
                    <th>Preview</th>
                    <th>Updated</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredRows.map((row) => (
                    <tr
                      key={row.symbol}
                      className={`note-row-clickable ${row.symbol === selectedSymbol ? 'note-row-active' : ''}`}
                      onClick={() => setSelectedSymbol(row.symbol)}
                    >
                      <td className="notes-col-symbol">{row.symbol}</td>
                      <td className="notes-col-preview">{shortText(row.note, 110)}</td>
                      <td className="notes-col-updated">{row.updatedAt ? new Date(row.updatedAt).toLocaleString() : '--'}</td>
                    </tr>
                  ))}
                  {!filteredRows.length ? (
                    <tr>
                      <td colSpan={3} className="muted">No rows.</td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          </div>
        </article>
      </section>

      <section className="panel">
        <h2>All Historical Stock Notes</h2>
        {allHistoricalNotes.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>Status</th>
                  <th>Last Updated</th>
                  <th>Full Note</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {allHistoricalNotes.map((row) => (
                  <tr key={`history-note-${row.symbol}`}>
                    <td>{row.symbol}</td>
                    <td>{row.isCurrent ? 'Current' : 'Past'}</td>
                    <td>{row.updatedAt ? new Date(row.updatedAt).toLocaleString() : '--'}</td>
                    <td className="note-full-text">{row.note}</td>
                    <td>
                      <button type="button" className="row-secondary-btn" onClick={() => setSelectedSymbol(row.symbol)}>
                        Edit
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="muted">No saved stock notes yet.</p>
        )}
      </section>
    </>
  );
}
