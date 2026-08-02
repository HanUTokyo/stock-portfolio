import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  AlertTriangle,
  Check,
  ChevronLeft,
  ChevronRight,
  Filter,
  History,
  Layers3,
  Monitor,
  RefreshCw,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Undo2,
  XCircle
} from 'lucide-react';
import useIsMobile from '../hooks/useIsMobile';
import ConfirmDialog from '../components/ConfirmDialog';
import {
  approveDataReviewRow,
  batchUpdateDataReviewStatus,
  getDataReviewHistory,
  getDataReviewRows,
  getDataReviewSources,
  getDataReviewSummary,
  markDataReviewRowUncertain,
  patchDataReviewRow,
  previewDataReviewBatch,
  rejectDataReviewRow,
  rollbackDataReviewAuditLog
} from '../api';

function formatValue(value) {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'number') return Number.isFinite(value) ? value.toLocaleString(undefined, { maximumFractionDigits: 6 }) : '—';
  if (Array.isArray(value)) return value.join(', ');
  return String(value);
}

function compactValue(value, max = 42) {
  const text = formatValue(value);
  return text.length > max ? text.slice(0, max) + '…' : text;
}

function humanize(value) {
  return String(value || '')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/^./, (letter) => letter.toUpperCase());
}

function statusLabel(t, status) {
  return t('review.status.' + status, { defaultValue: humanize(status) });
}

function sourceLabel(t, source) {
  return t('review.source.' + source?.name, { defaultValue: source?.label || '' });
}

function fieldLabel(t, field) {
  return t('review.field.' + field, { defaultValue: humanize(field) });
}

function rowIdentity(row) {
  const values = row.effectiveValues || row.rawValues || {};
  const primary = values.symbol || values.name || values.companyName || ('#' + row.recordId);
  const secondary = values.tradeDate || values.asOfDate || values.sector || values.currencyCode || '';
  return { primary: formatValue(primary), secondary: formatValue(secondary) };
}

function draftFor(row, source) {
  const values = {};
  (source?.editableFields || []).forEach((field) => {
    values[field] = row.reviewedValues?.[field] ?? row.effectiveValues?.[field] ?? row.rawValues?.[field] ?? '';
  });
  return { values, note: row.note || '', reasonCode: row.reasonCode || 'manual_verification', status: row.reviewStatus || 'pending' };
}

const REVIEW_REASONS = [
  ['data_error', 'Data error'],
  ['source_conflict', 'Source conflict'],
  ['missing_or_stale', 'Missing or stale'],
  ['outlier', 'Outlier'],
  ['classification_fix', 'Classification fix'],
  ['manual_verification', 'Manual verification'],
  ['other', 'Other']
];

function differenceCount(row, draft, source) {
  return (source?.editableFields || []).filter((field) => {
    const raw = row.rawValues?.[field] ?? '';
    const next = draft.values?.[field] ?? '';
    return String(raw) !== String(next);
  }).length;
}

function sourceStats(summary, source) {
  return summary?.sources?.find((item) => item.name === source?.name) || null;
}

export default function DataReviewConsolePage() {
  const { t } = useTranslation();
  const isMobile = useIsMobile();
  const [sources, setSources] = useState([]);
  const [summary, setSummary] = useState(null);
  const [selectedSourceName, setSelectedSourceName] = useState('');
  const [rows, setRows] = useState([]);
  const [selectedRow, setSelectedRow] = useState(null);
  const [draft, setDraft] = useState(null);
  const [history, setHistory] = useState([]);
  const [workspaceTab, setWorkspaceTab] = useState('review');
  const [selectedIds, setSelectedIds] = useState(() => new Set());
  const [filters, setFilters] = useState({
    search: '',
    status: 'all',
    anomalyOnly: false,
    queue: 'attention',
    severity: 'all',
    sortBy: 'priority',
    sortDirection: 'asc'
  });
  const [appliedFilters, setAppliedFilters] = useState(filters);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [workspaceLoading, setWorkspaceLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [batchAction, setBatchAction] = useState('');
  const [batchPreview, setBatchPreview] = useState(null);
  const [batchReason, setBatchReason] = useState('manual_verification');
  const [onlyChanged, setOnlyChanged] = useState(false);
  const [rollbackTarget, setRollbackTarget] = useState(null);

  const activeSource = useMemo(
    () => sources.find((source) => source.name === selectedSourceName) || null,
    [sources, selectedSourceName]
  );
  const activeStats = sourceStats(summary, activeSource);
  const queueFields = useMemo(
    () => (activeSource?.displayFields || []).filter((field) => field !== 'id').slice(0, 4),
    [activeSource]
  );
  const editableFields = activeSource?.editableFields || [];
  const statusOptions = [
    { value: 'all', label: t('review.status.all') },
    { value: 'pending', label: t('review.status.pending') },
    { value: 'approved', label: t('review.status.approved') },
    { value: 'corrected', label: t('review.status.corrected') },
    { value: 'uncertain', label: t('review.status.uncertain') },
    { value: 'rejected', label: t('review.status.rejected') }
  ];
  const actionCopy = {
    approved: t('review.confirmRawValues'),
    corrected: t('review.applyCorrection'),
    uncertain: t('review.markUncertain'),
    rejected: t('review.rejectFromAnalysis')
  };
  const selectedCount = selectedIds.size;
  const draftChanges = selectedRow && draft ? differenceCount(selectedRow, draft, activeSource) : 0;
  const completedCount = (activeStats?.completed ?? ((activeStats?.approved || 0) + (activeStats?.corrected || 0) + (activeStats?.rejected || 0)));
  const completionRate = activeStats?.total ? Math.round((completedCount / activeStats.total) * 100) : 0;

  async function refreshOverview() {
    const result = await Promise.all([getDataReviewSources(), getDataReviewSummary()]);
    const sourceList = result[0] || [];
    setSources(sourceList);
    setSummary(result[1]);
    setSelectedSourceName((current) => current || sourceList[0]?.name || '');
  }

  useEffect(() => {
    if (isMobile) return undefined;
    let mounted = true;
    refreshOverview().catch((requestError) => {
      if (mounted) setError(requestError.message);
    });
    return () => { mounted = false; };
  }, [isMobile]);

  async function loadRows(nextPage = page) {
    if (!activeSource) return;
    setLoading(true);
    setError('');
    try {
      const result = await getDataReviewRows(activeSource.name, {
        page: nextPage,
        size,
        search: appliedFilters.search,
        status: appliedFilters.status,
        sortBy: appliedFilters.sortBy,
        sortDirection: appliedFilters.sortDirection,
        anomalyOnly: appliedFilters.anomalyOnly,
        queue: appliedFilters.queue,
        severity: appliedFilters.severity
      });
      setRows(result.rows || []);
      setPage(result.page || 0);
      setTotalElements(result.totalElements || 0);
      setTotalPages(result.totalPages || 0);
      setSelectedIds(new Set());
      setSelectedRow((current) => {
        if (!current) return null;
        return (result.rows || []).find((row) => row.recordId === current.recordId) || null;
      });
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (!isMobile && activeSource) void loadRows(0);
  }, [isMobile, selectedSourceName, size, appliedFilters]);

  function selectSource(source) {
    setSelectedSourceName(source.name);
    setSelectedRow(null);
    setDraft(null);
    setHistory([]);
    setWorkspaceTab('review');
    setPage(0);
    setFilters((current) => ({ ...current, sortBy: 'priority', sortDirection: 'asc', queue: 'attention' }));
    setAppliedFilters((current) => ({ ...current, sortBy: 'priority', sortDirection: 'asc', queue: 'attention' }));
  }

  async function openRow(row) {
    if (!activeSource) return;
    setSelectedRow(row);
    setDraft(draftFor(row, activeSource));
    setWorkspaceTab('review');
    setWorkspaceLoading(true);
    setError('');
    try {
      setHistory(await getDataReviewHistory(activeSource.name, row.recordId));
    } catch (requestError) {
      setError(requestError.message);
      setHistory([]);
    } finally {
      setWorkspaceLoading(false);
    }
  }

  function replaceRow(updated) {
    setRows((current) => current.map((row) => row.recordId === updated.recordId ? updated : row));
    if (selectedRow?.recordId === updated.recordId && activeSource) {
      setSelectedRow(updated);
      setDraft(draftFor(updated, activeSource));
    }
  }

  async function refreshSelectedHistory(row = selectedRow) {
    if (!activeSource || !row) return;
    setWorkspaceLoading(true);
    try {
      setHistory(await getDataReviewHistory(activeSource.name, row.recordId));
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setWorkspaceLoading(false);
    }
  }

  async function saveCorrection() {
    if (!activeSource || !selectedRow || !draft) return;
    const changes = {};
    editableFields.forEach((field) => {
      const raw = selectedRow.rawValues?.[field] ?? '';
      const next = draft.values?.[field] ?? '';
      if (String(raw) !== String(next)) changes[field] = next === '' ? null : next;
    });
    if (!Object.keys(changes).length && selectedRow.reviewStatus !== 'corrected') {
      setMessage(t('review.noCorrectionValues'));
      return;
    }
    setSaving(true);
    setError('');
    try {
      const updated = await patchDataReviewRow(activeSource.name, selectedRow.recordId, {
        changes,
        reviewStatus: 'corrected',
        note: draft.note,
        reasonCode: draft.reasonCode,
        expectedRevision: selectedRow.revision
      });
      replaceRow(updated);
      await refreshSelectedHistory(updated);
      await refreshOverview();
      setMessage(t('review.correctionApplied', { symbol: rowIdentity(updated).primary }));
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSaving(false);
    }
  }

  async function changeStatus(status) {
    if (!activeSource || !selectedRow || !draft) return;
    const calls = {
      approved: approveDataReviewRow,
      rejected: rejectDataReviewRow,
      uncertain: markDataReviewRowUncertain
    };
    setSaving(true);
    setError('');
    try {
      const updated = await calls[status](activeSource.name, selectedRow.recordId, {
        note: draft.note,
        reasonCode: draft.reasonCode,
        expectedRevision: selectedRow.revision
      });
      replaceRow(updated);
      await refreshSelectedHistory(updated);
      await refreshOverview();
      setMessage(t('review.statusApplied', { status: statusLabel(t, status), symbol: rowIdentity(updated).primary }));
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSaving(false);
    }
  }

  function toggleSelected(recordId) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(recordId)) next.delete(recordId);
      else next.add(recordId);
      return next;
    });
  }

  function toggleVisibleSelection() {
    setSelectedIds((current) => {
      const allVisible = rows.length > 0 && rows.every((row) => current.has(row.recordId));
      return allVisible ? new Set() : new Set(rows.map((row) => row.recordId));
    });
  }

  async function previewBatchAction() {
    if (!activeSource || !batchAction || !selectedIds.size) return;
    setSaving(true);
    setError('');
    try {
      const preview = await previewDataReviewBatch(activeSource.name, {
        recordIds: [...selectedIds],
        reviewStatus: batchAction,
        reasonCode: batchReason,
        note: t('review.batchNote', { status: statusLabel(t, batchAction).toLowerCase() }),
        expectedRevisions: Object.fromEntries(rows.filter((row) => selectedIds.has(row.recordId)).map((row) => [row.recordId, row.revision]))
      });
      setBatchPreview(preview);
      setBatchAction('');
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSaving(false);
    }
  }

  async function confirmBatchAction() {
    if (!activeSource || !batchPreview || !selectedIds.size) return;
    setSaving(true);
    setError('');
    try {
      const result = await batchUpdateDataReviewStatus(activeSource.name, {
        recordIds: [...selectedIds],
        reviewStatus: batchPreview.reviewStatus,
        reasonCode: batchPreview.reasonCode,
        note: t('review.batchNote', { status: statusLabel(t, batchPreview.reviewStatus).toLowerCase() }),
        expectedRevisions: Object.fromEntries(rows.filter((row) => selectedIds.has(row.recordId)).map((row) => [row.recordId, row.revision]))
      });
      const updatedById = new Map((result.rows || []).map((row) => [row.recordId, row]));
      setRows((current) => current.map((row) => updatedById.get(row.recordId) || row));
      setSelectedIds(new Set());
      setBatchPreview(null);
      await refreshOverview();
      setMessage(t('review.batchApplied', { count: result.updatedCount, status: statusLabel(t, result.reviewStatus).toLowerCase() }));
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSaving(false);
    }
  }

  async function confirmRollback() {
    if (!activeSource || !selectedRow || !rollbackTarget) return;
    setSaving(true);
    setError('');
    try {
      const updated = await rollbackDataReviewAuditLog(activeSource.name, selectedRow.recordId, rollbackTarget.id, {
        reasonCode: draft?.reasonCode || 'manual_verification',
        expectedRevision: selectedRow.revision
      });
      replaceRow(updated);
      await refreshSelectedHistory(updated);
      await refreshOverview();
      setMessage(t('review.valueRestored', { field: fieldLabel(t, rollbackTarget.fieldName) }));
      setRollbackTarget(null);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSaving(false);
    }
  }

  function applyFilters(event) {
    event.preventDefault();
    setPage(0);
    setAppliedFilters({ ...filters, search: filters.search.trim() });
  }

  function clearFilters() {
    const next = { search: '', status: 'all', anomalyOnly: false, queue: 'attention', severity: 'all', sortBy: 'priority', sortDirection: 'asc' };
    setFilters(next);
    setAppliedFilters(next);
    setPage(0);
  }

  if (isMobile) {
    return (
      <section className="data-review-page">
        <div className="panel data-review-mobile-unavailable">
          <div className="data-review-mobile-icon" aria-hidden="true"><Monitor size={28} /></div>
          <p className="eyebrow">{t('review.adminWorkspace')}</p>
          <h2>{t('review.desktopOnlyTitle')}</h2>
          <p className="muted">{t('review.desktopOnlyDescription')}</p>
        </div>
      </section>
    );
  }

  return (
    <section className="data-review-page data-review-workstation">
      <header className="page-toolbar data-review-page-toolbar">
        <div>
          <p className="eyebrow">{t('review.adminWorkspace')}</p>
          <h2>{t('review.title')}</h2>
          <p>{t('review.description')}</p>
        </div>
        <div className="page-toolbar-actions">
          <button type="button" className="row-secondary-btn" onClick={() => { void refreshOverview(); void loadRows(); }} disabled={loading}>
            <RefreshCw size={16} aria-hidden="true" /> {t('review.refresh')}
          </button>
        </div>
      </header>

      {error ? <p className="error">{error}</p> : null}
      {message ? <p className="info">{message}</p> : null}

      <section className="review-source-grid" aria-label={t('review.sources')}>
        {sources.map((source) => {
          const stats = sourceStats(summary, source);
          const active = activeSource?.name === source.name;
          const completed = stats?.completed ?? ((stats?.approved || 0) + (stats?.corrected || 0) + (stats?.rejected || 0));
          const completion = stats?.total ? Math.round((completed / stats.total) * 100) : 0;
          return (
            <button key={source.name} type="button" data-review-source-card={source.name} className={'review-source-card ' + (active ? 'is-active' : '')} onClick={() => selectSource(source)}>
              <span className="review-source-icon"><Layers3 size={18} aria-hidden="true" /></span>
              <span className="review-source-copy"><strong>{sourceLabel(t, source)}</strong><small>{t('review.records', { count: stats?.total ?? 0 })} · {completion ? `${completion}% complete` : 'Not reviewed yet'}</small>{completion > 0 ? <span className="review-source-progress"><i style={{ width: `${completion}%` }} /></span> : null}</span>
              <span className="review-source-stats">
                <span><small>Pending</small>{stats?.pending ?? 0}</span>
                <span className={(stats?.attention || stats?.anomalies || 0) > 0 ? 'review-anomaly-count' : ''}><small>Attention</small>{stats?.attention ?? stats?.anomalies ?? 0}</span>
                <span><small>Done</small>{completed}</span>
              </span>
            </button>
          );
        })}
      </section>

      <section className="review-workbench">
        <div className="review-queue panel">
          <div className="review-queue-header">
            <div>
              <p className="eyebrow">{t('review.queue')}</p>
              <h2>{activeSource ? sourceLabel(t, activeSource) : t('review.selectSource')}</h2>
              <p className="muted">{activeStats
                ? `${activeStats.total.toLocaleString()} source records · ${activeStats.corrected || 0} corrected · ${activeStats.uncertain || 0} uncertain`
                : t('review.chooseSource')}</p>
              {activeStats ? <div className="review-health-metrics" data-review-queue-health><span className="review-health-attention"><strong>{activeStats.attention ?? activeStats.anomalies}</strong><small>attention</small></span><span><strong>{activeStats.pending}</strong><small>pending</small></span><span><strong>{completionRate}%</strong><small>complete</small></span></div> : null}
            </div>
          </div>

          <form className="review-filter-bar" onSubmit={applyFilters} data-review-filter-bar>
            <div className="review-filter-primary">
              <label><span>Queue</span><select value={filters.queue} onChange={(event) => setFilters((current) => ({ ...current, queue: event.target.value }))}><option value="attention">Attention</option><option value="pending">Pending</option><option value="all">All records</option></select></label>
              <label className="review-search-input"><Search size={16} aria-hidden="true" /><input value={filters.search} onChange={(event) => setFilters((current) => ({ ...current, search: event.target.value }))} placeholder={t('review.searchPlaceholder')} /></label>
              <label><span>{t('review.statusLabel')}</span><select value={filters.status} onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value }))}>{statusOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
              <label><span>Risk</span><select value={filters.severity} onChange={(event) => setFilters((current) => ({ ...current, severity: event.target.value }))}><option value="all">All risks</option><option value="high">Urgent</option><option value="medium">High</option><option value="low">Normal</option></select></label>
              <label><span>{t('review.sort')}</span><select value={filters.sortBy} onChange={(event) => setFilters((current) => ({ ...current, sortBy: event.target.value }))}>{['priority', 'id', ...(activeSource?.displayFields || []), 'reviewStatus', 'updatedAt'].filter((value, index, list) => list.indexOf(value) === index).map((field) => <option key={field} value={field}>{field === 'priority' ? 'Risk priority' : fieldLabel(t, field)}</option>)}</select></label>
            </div>
            <div className="review-filter-secondary">
              <label><span>{t('review.direction')}</span><select value={filters.sortDirection} onChange={(event) => setFilters((current) => ({ ...current, sortDirection: event.target.value }))}><option value="asc">{t('review.ascending')}</option><option value="desc">{t('review.descending')}</option></select></label>
              <label className="review-flag-filter"><input type="checkbox" checked={filters.anomalyOnly} onChange={(event) => setFilters((current) => ({ ...current, anomalyOnly: event.target.checked }))} /> <span>{t('review.flagsOnly')}</span></label>
              <span className="review-results-count" aria-live="polite">{loading ? t('review.loadingQueue') : t('review.rowsCount', { count: totalElements })}</span>
              <button type="submit" className="review-filter-apply"><Filter size={15} aria-hidden="true" /> {t('review.apply')}</button>
              <button type="button" className="review-filter-clear" onClick={clearFilters}>Clear</button>
            </div>
          </form>

          {selectedCount ? (
            <div className="review-batch-bar">
              <strong>{t('review.selectedCount', { count: selectedCount })}</strong>
              <select aria-label="Batch review reason" value={batchReason} onChange={(event) => setBatchReason(event.target.value)}>{REVIEW_REASONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
              <button type="button" className="row-secondary-btn" onClick={() => setBatchAction('approved')}><Check size={15} aria-hidden="true" /> {t('review.approve')}</button>
              <button type="button" className="row-secondary-btn" onClick={() => setBatchAction('uncertain')}><AlertTriangle size={15} aria-hidden="true" /> {t('review.uncertain')}</button>
              <button type="button" className="row-danger-btn" onClick={() => setBatchAction('rejected')}><XCircle size={15} aria-hidden="true" /> {t('review.reject')}</button>
            </div>
          ) : null}

          <div className="table-wrap review-queue-table-wrap">
            <table className="review-queue-table">
              <thead>
                <tr>
                  <th className="review-select-cell"><input type="checkbox" aria-label={t('review.selectVisible')} checked={rows.length > 0 && rows.every((row) => selectedIds.has(row.recordId))} onChange={toggleVisibleSelection} /></th>
                  <th className="review-record-cell">{t('review.record')}</th>
                  {queueFields.map((field) => <th key={field}>{fieldLabel(t, field)}</th>)}
                  <th>{t('review.flags')}</th>
                  <th>{t('review.statusLabel')}</th>
                  <th>Open</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const identity = rowIdentity(row);
                  const active = selectedRow?.recordId === row.recordId;
                  return (
                    <tr key={row.recordId} className={active ? 'is-active' : ''}>
                      <td className="review-select-cell" onClick={(event) => event.stopPropagation()}><input type="checkbox" aria-label={t('review.selectRow', { row: identity.primary })} checked={selectedIds.has(row.recordId)} onChange={() => toggleSelected(row.recordId)} /></td>
                      <td className="review-record-cell"><strong>{identity.primary}</strong><span className="review-row-secondary">{identity.secondary || ('#' + row.recordId)}</span></td>
                      {queueFields.map((field) => <td key={field} title={formatValue(row.effectiveValues?.[field])}>{compactValue(row.effectiveValues?.[field])}</td>)}
                      <td>{row.anomalyFlags?.length ? <span className={'review-flag-pill risk-' + (row.riskLevel || 'normal')}><AlertTriangle size={13} aria-hidden="true" /> {row.riskLevel || 'normal'} · {row.anomalyFlags.length}</span> : <span className="muted">—</span>}</td>
                      <td><span className={'review-status review-status-' + row.reviewStatus}>{statusLabel(t, row.reviewStatus)}</span></td>
                      <td><button type="button" className="row-secondary-btn review-open-row" onClick={() => { void openRow(row); }}>Review</button></td>
                    </tr>
                  );
                })}
                {!loading && !rows.length ? <tr><td colSpan={queueFields.length + 5} className="muted">{t('review.noRows')}</td></tr> : null}
              </tbody>
            </table>
          </div>

          <footer className="review-pagination">
            <span>{loading ? t('review.loadingQueue') : t('review.rowsCount', { count: totalElements })}</span>
            <div>
              <button type="button" className="icon-button" title={t('review.previousPage')} aria-label={t('review.previousPage')} disabled={page <= 0 || loading} onClick={() => loadRows(page - 1)}><ChevronLeft size={17} /></button>
              <span>{t('review.page', { current: totalPages ? page + 1 : 0, total: totalPages })}</span>
              <button type="button" className="icon-button" title={t('review.nextPage')} aria-label={t('review.nextPage')} disabled={page + 1 >= totalPages || loading} onClick={() => loadRows(page + 1)}><ChevronRight size={17} /></button>
              <select value={size} onChange={(event) => { setSize(Number(event.target.value)); setPage(0); }}><option value={10}>10</option><option value={25}>25</option><option value={50}>50</option><option value={100}>100</option></select>
            </div>
          </footer>
        </div>

        <aside className="review-workspace panel">
          {!selectedRow || !activeSource || !draft ? (
            <div className="review-workspace-empty">
              <ShieldCheck size={28} aria-hidden="true" />
              <h2>{t('review.chooseRow')}</h2>
              <p className="muted">{t('review.chooseRowDescription')}</p>
            </div>
          ) : (
            <>
              <header className="review-workspace-header">
                <div><p className="eyebrow">{sourceLabel(t, activeSource)} · #{selectedRow.recordId}</p><h2>{rowIdentity(selectedRow).primary}</h2><p className="muted">{rowIdentity(selectedRow).secondary}</p></div>
                <span className={'review-status review-status-' + selectedRow.reviewStatus}>{statusLabel(t, selectedRow.reviewStatus)}</span>
              </header>

              <div className="review-workspace-tabs">
                <button type="button" className={workspaceTab === 'review' ? 'active' : ''} onClick={() => setWorkspaceTab('review')}><SlidersHorizontal size={15} aria-hidden="true" /> {t('review.review')}</button>
                <button type="button" className={workspaceTab === 'history' ? 'active' : ''} onClick={() => { setWorkspaceTab('history'); void refreshSelectedHistory(); }}><History size={15} aria-hidden="true" /> {t('review.history')} {history.length ? '(' + history.length + ')' : ''}</button>
              </div>

              {workspaceTab === 'review' ? (
                <>
                  <div className="review-workspace-body">
                  <section className={'review-evidence-section review-risk-banner risk-' + (selectedRow.riskLevel || 'normal')} data-review-risk-banner><div className="review-section-heading"><span>Evidence</span><small>{selectedRow.riskLevel || 'normal'} risk · {selectedRow.anomalyCount || 0} flags</small></div>{selectedRow.anomalies?.length ? <div className="review-anomaly-list">{selectedRow.anomalies.map((anomaly) => <p key={anomaly.code + '-' + anomaly.message} className={'severity-' + anomaly.severity}><AlertTriangle size={14} aria-hidden="true" /> {anomaly.message}</p>)}</div> : <p className="review-no-anomaly">No active anomaly flag. Review against source evidence.</p>}</section>
                  <div className="review-status-meaning"><span>{t('review.statusMeaning.pending')}</span><span>{t('review.statusMeaning.approved')}</span><span>{t('review.statusMeaning.corrected')}</span></div>
                  <div className="review-section-heading review-change-heading"><span>Correction draft</span><label><input type="checkbox" checked={onlyChanged} onChange={(event) => setOnlyChanged(event.target.checked)} /> Only changed</label></div>
                  <div className="review-field-list">
                    {editableFields.filter((field) => !onlyChanged || String(selectedRow.rawValues?.[field] ?? '') !== String(draft.values?.[field] ?? '')).map((field) => {
                      const raw = selectedRow.rawValues?.[field];
                      const effective = selectedRow.effectiveValues?.[field];
                      const next = draft.values?.[field] ?? '';
                      const changed = String(raw ?? '') !== String(next);
                      return (
                        <label key={field} className={'review-field ' + (changed ? 'is-changed' : '')}>
                          <span className="review-field-label">{fieldLabel(t, field)}</span>
                          <span className="review-field-raw">{t('review.raw')} <strong className={raw === null || raw === undefined || raw === '' ? 'is-missing' : ''}>{formatValue(raw)}</strong></span>
                          <span className="review-field-current">{t('review.effective')} <strong className={effective === null || effective === undefined || effective === '' ? 'is-missing' : ''}>{formatValue(effective)}</strong></span>
                          <input value={next} onChange={(event) => setDraft((current) => ({ ...current, values: { ...current.values, [field]: event.target.value } }))} />
                        </label>
                      );
                    })}
                  </div>
                </div>
                <section className="review-decision-panel" data-review-decision-panel>
                  <div className="review-decision-grid"><label><span>Review reason</span><select value={draft.reasonCode} onChange={(event) => setDraft((current) => ({ ...current, reasonCode: event.target.value }))}>{REVIEW_REASONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label className="review-note-field"><span>{t('review.reviewerNote')}</span><textarea rows={3} value={draft.note} onChange={(event) => setDraft((current) => ({ ...current, note: event.target.value }))} placeholder={t('review.notePlaceholder')} /></label></div>
                  <div className="review-workspace-actions">
                    <button type="button" onClick={saveCorrection} disabled={saving}>{draftChanges ? t('review.applyCorrections', { count: draftChanges }) : t('review.saveCorrected')}</button>
                    <button type="button" className="row-secondary-btn" onClick={() => changeStatus('approved')} disabled={saving}><Check size={15} aria-hidden="true" /> {t('review.approveRaw')}</button>
                    <button type="button" className="row-secondary-btn" onClick={() => changeStatus('uncertain')} disabled={saving}><AlertTriangle size={15} aria-hidden="true" /> {t('review.uncertain')}</button>
                    <button type="button" className="row-danger-btn" onClick={() => changeStatus('rejected')} disabled={saving}><XCircle size={15} aria-hidden="true" /> {t('review.reject')}</button>
                  </div>
                  </section>
                </>
              ) : (
                <div className="review-history-list">
                  {workspaceLoading ? <p className="muted">{t('review.loadingHistory')}</p> : null}
                  {!workspaceLoading && !history.length ? <p className="muted">{t('review.noHistory')}</p> : null}
                  {!workspaceLoading && history.map((event) => (
                    <article key={event.id} className="review-history-event">
                      <div><strong>{fieldLabel(t, event.fieldName === '_status' ? 'status' : event.fieldName)}</strong><span className={'review-status review-status-' + event.reviewStatus}>{statusLabel(t, event.reviewStatus)}</span></div>
                      <p><span>{formatValue(event.oldValue)}</span><span>→</span><strong>{formatValue(event.newValue)}</strong></p>
                      <small>{event.action} · {event.reasonCode ? humanize(event.reasonCode) + ' · ' : ''}{event.reviewer || t('review.manualAdmin')} · {event.createdAt ? new Date(event.createdAt).toLocaleString() : '—'}</small>
                      {event.note ? <small>{event.note}</small> : null}
                      <button type="button" className="row-secondary-btn" onClick={() => setRollbackTarget(event)} disabled={saving}><Undo2 size={14} aria-hidden="true" /> {t('review.restore')}</button>
                    </article>
                  ))}
                </div>
              )}
            </>
          )}
        </aside>
      </section>

      <ConfirmDialog
        open={Boolean(batchAction)}
        title={t('review.batchConfirmTitle', { action: actionCopy[batchAction] || t('review.update'), count: selectedCount })}
        description={batchAction === 'rejected'
          ? t('review.rejectedDescription')
          : t('review.batchDescription')}
        confirmLabel={actionCopy[batchAction] || t('review.confirm')}
        tone={batchAction === 'rejected' ? 'danger' : 'default'}
        pending={saving}
        onConfirm={previewBatchAction}
        onClose={() => setBatchAction('')}
      />

      <ConfirmDialog
        open={Boolean(batchPreview)}
        title={`Preview ${batchPreview?.affectedCount || 0} review updates`}
        description={batchPreview ? `This will mark ${batchPreview.affectedCount} rows as ${statusLabel(t, batchPreview.reviewStatus)}. Risk mix: ${Object.entries(batchPreview.riskCounts || {}).map(([risk, count]) => `${risk} ${count}`).join(', ') || 'none'}. This operation is all-or-nothing.` : ''}
        confirmLabel="Apply all updates"
        tone={batchPreview?.reviewStatus === 'rejected' ? 'danger' : 'default'}
        pending={saving}
        onConfirm={confirmBatchAction}
        onClose={() => setBatchPreview(null)}
      />

      <ConfirmDialog
        open={Boolean(rollbackTarget)}
        title={t('review.restoreTitle')}
        description={rollbackTarget ? t('review.restoreDescription', { field: fieldLabel(t, rollbackTarget.fieldName), value: formatValue(rollbackTarget.oldValue) }) : ''}
        confirmLabel={t('review.restoreValue')}
        tone="default"
        pending={saving}
        onConfirm={confirmRollback}
        onClose={() => setRollbackTarget(null)}
      />
    </section>
  );
}
