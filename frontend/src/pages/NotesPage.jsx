import { useEffect, useMemo, useState } from 'react';
import { FileText, Search } from 'lucide-react';
import RowDetailSheet from '../components/RowDetailSheet';
import RichTextEditor, { RichTextActions, RichTextPreview, richNoteToMarkdown, richNoteToPlainText } from '../components/RichTextEditor';
import useIsMobile from '../hooks/useIsMobile';

function normalizeSymbol(value) {
  return String(value || '').trim().toUpperCase();
}

function shortText(value, max = 80) {
  const text = richNoteToPlainText(value).replace(/\s+/g, ' ').trim();
  if (!text) return '--';
  if (text.length <= max) return text;
  return `${text.slice(0, max)}...`;
}

export default function NotesPage({ transactions, holdings, stockNotes, onSaveStockNote }) {
  const isMobile = useIsMobile();
  const [selectedSymbol, setSelectedSymbol] = useState('');
  const [draftNote, setDraftNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [filterText, setFilterText] = useState('');
  const [viewMode, setViewMode] = useState('ALL');
  const [noteSheetOpen, setNoteSheetOpen] = useState(false);

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
  const savedNoteCount = noteRows.filter((row) => row.hasNote).length;
  const currentHoldingCount = noteRows.filter((row) => row.isCurrent).length;

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
      await onSaveStockNote(selectedSymbol, richNoteToMarkdown(draftNote));
      setIsEditing(false);
      if (isMobile) setNoteSheetOpen(false);
    } finally {
      setSaving(false);
    }
  }

  function openNote(symbol) {
    setSelectedSymbol(symbol);
    setIsEditing(false);
    if (isMobile) {
      setNoteSheetOpen(true);
    }
  }

  const editorFields = (
    <div className="notes-editor-fields">
      {isMobile ? (
        <label className="notes-symbol-control">
          <span>Symbol</span>
          <select value={selectedSymbol} onChange={(e) => setSelectedSymbol(e.target.value)} disabled={!allSymbols.length}>
            {!allSymbols.length ? <option value="">No Symbol</option> : null}
            {allSymbols.map((symbol) => <option key={symbol} value={symbol}>{symbol}</option>)}
          </select>
        </label>
      ) : null}
      <div className="notes-editor-copy">
        <span>Research note</span>
        {isEditing ? (
          <RichTextEditor
            placeholder="Thesis, catalysts, risk controls, position changes, and follow-up questions..."
            ariaLabel="Research note"
            value={draftNote}
            onChange={setDraftNote}
            autoFocus
          />
        ) : (
          <RichTextPreview value={savedNote} className="notes-read-preview" ariaLabel="Research note preview" />
        )}
      </div>
      {selectedSymbol ? (
        <p className={`notes-save-state ${isDirty ? 'is-dirty' : ''}`}>
          {isDirty ? 'Unsaved changes' : 'Saved'}
          <span>Last updated {selectedNote?.updatedAt ? new Date(selectedNote.updatedAt).toLocaleString() : 'never'}</span>
        </p>
      ) : <p className="muted">Add a transaction before creating a stock note.</p>}
    </div>
  );

  const editorActions = (
    <RichTextActions
      isEditing={isEditing}
      isDirty={isDirty}
      saving={saving}
      disabled={!selectedSymbol}
      editLabel="Edit note"
      onEdit={() => setIsEditing(true)}
      onCancel={() => { setDraftNote(savedNote); setIsEditing(false); }}
      onSave={handleSave}
    />
  );

  return (
    <>
      <section className="notes-workspace">
        <article className="panel notes-directory-panel">
          <header className="notes-directory-header">
            <div>
              <p className="eyebrow">Research workspace</p>
              <h2>Stock Notes</h2>
            </div>
            <div className="notes-summary-stats" aria-label="Notes summary">
              <span><strong>{savedNoteCount}</strong> saved</span>
              <span><strong>{currentHoldingCount}</strong> current</span>
            </div>
          </header>

          <div className="notes-directory-controls mobile-hide">
            <label className="notes-search-control">
              <Search size={16} aria-hidden="true" />
              <input placeholder="Search symbols" value={filterText} onChange={(e) => setFilterText(e.target.value)} />
            </label>
            <div className="rank-filter-tabs notes-inline-tabs">
              <button type="button" className={viewMode === 'ALL' ? 'rank-tab active' : 'rank-tab'} onClick={() => setViewMode('ALL')}>All {noteRows.length}</button>
              <button type="button" className={viewMode === 'WITH_NOTE' ? 'rank-tab active' : 'rank-tab'} onClick={() => setViewMode('WITH_NOTE')}>Saved {savedNoteCount}</button>
            </div>
          </div>

          <div className="notes-directory-list">
            {filteredRows.map((row) => (
              <button
                key={row.symbol}
                type="button"
                className={`notes-directory-item ${row.symbol === selectedSymbol ? 'is-active' : ''}`}
                onClick={() => openNote(row.symbol)}
              >
                <span className="notes-directory-item-head">
                  <strong>{row.symbol}</strong>
                  <span className={`notes-holding-badge ${row.isCurrent ? 'is-current' : ''}`}>{row.isCurrent ? 'Current' : 'Past'}</span>
                </span>
                <span className={`notes-directory-preview ${row.hasNote ? '' : 'is-empty'}`}>{row.hasNote ? shortText(row.note, 130) : 'No note yet'}</span>
                <span className="notes-directory-date">{row.updatedAt ? `Updated ${new Date(row.updatedAt).toLocaleDateString()}` : 'Ready to write'}</span>
              </button>
            ))}
            {!filteredRows.length ? <div className="notes-empty-state"><FileText size={22} aria-hidden="true" /><p>No matching symbols.</p></div> : null}
          </div>
        </article>

        <article className="panel notes-editor-workspace mobile-hide">
          <header className="notes-editor-header">
            <div>
              <p className="eyebrow">{selectedSymbol ? (currentHoldingSymbols.has(selectedSymbol) ? 'Current holding' : 'Past position') : 'Research note'}</p>
              <h2>{selectedSymbol || 'Select a symbol'}</h2>
            </div>
            {!isEditing && selectedSymbol ? (
              <RichTextActions isEditing={false} editLabel="Edit note" onEdit={() => setIsEditing(true)} />
            ) : null}
          </header>
          {editorFields}
          {isEditing ? <footer className="notes-editor-actions">{editorActions}</footer> : null}
        </article>
      </section>

      <RowDetailSheet
        open={noteSheetOpen}
        title={selectedSymbol ? `${selectedSymbol} Note` : 'Stock Note'}
        eyebrow={selectedSymbol && currentHoldingSymbols.has(selectedSymbol) ? 'Current holding' : 'Research workspace'}
        onClose={() => setNoteSheetOpen(false)}
        actions={editorActions}
        fullHeight
        initialFocusSelector={isEditing ? '.rich-text-surface' : undefined}
      >
        {editorFields}
      </RowDetailSheet>
    </>
  );
}
