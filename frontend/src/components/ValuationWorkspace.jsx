import { useEffect, useMemo, useRef, useState } from 'react';
import { evaluateValuation, resetValuationScenario, saveValuationScenario } from '../api.js';
import { RichTextNotePanel, richNoteToMarkdown } from './RichTextEditor';

const tabs = ['OVERVIEW', 'SCENARIOS', 'CAPE', 'SENSITIVITY', 'DIAGNOSTICS'];
const fields = [
  ['terminalGrowthRatePct', 'Terminal Growth %', 'number'],
  ['projectionYears', 'Projection Years', 'number'],
  ['marginOfSafetyPct', 'Margin of Safety %', 'number'],
  ['taxRateOverridePct', 'Tax Rate Override %', 'number']
];

function money(value) {
  if (value == null || !Number.isFinite(Number(value))) return '--';
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(Number(value));
}

function number(value, digits = 2) {
  return value == null || !Number.isFinite(Number(value)) ? '--' : Number(value).toFixed(digits);
}

function draftFromScenario(scenario) {
  const source = scenario?.assumptions || {};
  const resolved = scenario?.resolvedAssumptions || source;
  return {
    baseCashFlowMode: source.baseCashFlowMode || 'MANUAL',
    growthMode: source.growthMode || 'CUSTOM_LINEAR',
    discountRateMode: source.discountRateMode || 'MANUAL_RATE',
    baseCashFlow: source.baseCashFlow ?? resolved.baseCashFlow ?? '',
    initialGrowthRatePct: source.initialGrowthRatePct ?? resolved.initialGrowthRatePct ?? '',
    discountRatePct: source.discountRatePct ?? resolved.discountRatePct ?? '',
    riskFreeRatePct: source.riskFreeRatePct ?? resolved.riskFreeRatePct ?? '',
    beta: source.beta ?? resolved.beta ?? '',
    equityRiskPremiumPct: source.equityRiskPremiumPct ?? resolved.equityRiskPremiumPct ?? '',
    annualGrowthRatesPct: source.annualGrowthRatesPct || resolved.annualGrowthRatesPct || [],
    ...Object.fromEntries(fields.map(([key]) => [key, source[key] ?? resolved[key] ?? '']))
  };
}

function payloadFromDraft(draft) {
  const payload = Object.fromEntries(fields.map(([key]) => {
    const raw = draft[key];
    if (raw === '' || raw == null) return [key, null];
    return [key, key === 'projectionYears' ? Math.round(Number(raw)) : Number(raw)];
  }));
  payload.baseCashFlowMode = draft.baseCashFlowMode || 'AUTO';
  payload.growthMode = draft.growthMode || 'AUTO_BLEND';
  payload.discountRateMode = draft.discountRateMode || 'AUTO';
  payload.baseCashFlow = payload.baseCashFlowMode === 'AUTO' ? null : Number(draft.baseCashFlow);
  payload.initialGrowthRatePct = payload.growthMode.startsWith('CUSTOM') ? Number(draft.initialGrowthRatePct) : null;
  payload.discountRatePct = payload.discountRateMode === 'MANUAL_RATE' ? Number(draft.discountRatePct) : null;
  payload.riskFreeRatePct = payload.discountRateMode === 'MANUAL_CAPM_COMPONENTS' ? Number(draft.riskFreeRatePct) : null;
  payload.beta = payload.discountRateMode === 'MANUAL_CAPM_COMPONENTS' ? Number(draft.beta) : null;
  payload.equityRiskPremiumPct = payload.discountRateMode === 'MANUAL_CAPM_COMPONENTS' ? Number(draft.equityRiskPremiumPct) : null;
  payload.annualGrowthRatesPct = payload.growthMode === 'CUSTOM_PATH'
    ? (draft.annualGrowthRatesPct || []).map(Number)
    : null;
  return payload;
}

export default function ValuationWorkspace({ symbol, initialValue, noteItem, onSaveNote }) {
  const [value, setValue] = useState(initialValue);
  const [tab, setTab] = useState('OVERVIEW');
  const [scenarioType, setScenarioType] = useState('BASE');
  const [draft, setDraft] = useState({});
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [noteDraft, setNoteDraft] = useState('');
  const [noteSaving, setNoteSaving] = useState(false);
  const [noteEditing, setNoteEditing] = useState(false);
  const requestId = useRef(0);
  const scenario = useMemo(
    () => value?.scenarios?.find((item) => item.scenarioType === scenarioType) || null,
    [value, scenarioType]
  );
  const draftPayload = useMemo(() => payloadFromDraft(draft), [draft]);
  const draftComplete = ['terminalGrowthRatePct', 'projectionYears', 'marginOfSafetyPct']
    .every((key) => draftPayload[key] != null && Number.isFinite(Number(draftPayload[key])))
    && (draftPayload.baseCashFlowMode === 'AUTO' || Number.isFinite(draftPayload.baseCashFlow))
    && (!draftPayload.growthMode.startsWith('CUSTOM') || Number.isFinite(draftPayload.initialGrowthRatePct))
    && (draftPayload.growthMode !== 'CUSTOM_PATH' || draftPayload.annualGrowthRatesPct?.length === draftPayload.projectionYears)
    && (draftPayload.discountRateMode !== 'MANUAL_RATE' || Number.isFinite(draftPayload.discountRatePct))
    && (draftPayload.discountRateMode !== 'MANUAL_CAPM_COMPONENTS'
      || ['riskFreeRatePct', 'beta', 'equityRiskPremiumPct'].every((key) => Number.isFinite(draftPayload[key])));
  const scenarioPayload = useMemo(() => payloadFromDraft(draftFromScenario(scenario)), [scenario]);
  const dirty = useMemo(() => JSON.stringify(draftPayload) !== JSON.stringify(scenarioPayload), [draftPayload, scenarioPayload]);
  const savedNote = noteItem?.note || '';
  const noteDirty = noteDraft !== savedNote;
  const baseValue = Number(value?.overview?.baseValue);
  const currentPrice = Number(value?.overview?.currentPrice);
  const priceVsBasePct = Number.isFinite(baseValue) && baseValue !== 0 && Number.isFinite(currentPrice)
    ? ((currentPrice / baseValue) - 1) * 100
    : null;

  useEffect(() => {
    setValue(initialValue);
    const nextScenario = initialValue?.scenarios?.find((item) => item.scenarioType === scenarioType) || null;
    setDraft(draftFromScenario(nextScenario));
    setPreview(null);
    setError('');
  }, [initialValue, symbol, scenarioType]);

  useEffect(() => {
    setDraft(draftFromScenario(scenario));
    setPreview(null);
    setError('');
  }, [scenarioType, scenario, symbol]);

  useEffect(() => {
    setNoteDraft(savedNote);
    setNoteEditing(false);
  }, [savedNote, symbol]);

  useEffect(() => {
    if (!dirty || !draftComplete || !symbol || value?.symbol !== symbol || !value?.applicability?.applicable) return undefined;
    const currentId = ++requestId.current;
    const timer = window.setTimeout(async () => {
      try {
        const result = await evaluateValuation(symbol, scenarioType, draftPayload);
        if (requestId.current === currentId) {
          setPreview(result);
          setError('');
        }
      } catch (e) {
        if (requestId.current === currentId) setError(e.message);
      }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [dirty, draftComplete, draftPayload, scenarioType, symbol, value?.symbol, value?.applicability?.applicable]);

  const activeScenario = preview?.scenario || scenario;

  const save = async () => {
    setBusy(true);
    try {
      const saved = await saveValuationScenario(symbol, scenarioType, draftPayload);
      setValue((previous) => ({
        ...previous,
        scenarios: (previous?.scenarios || []).map((item) => item.scenarioType === scenarioType ? saved : item)
      }));
      setPreview(null);
      setError('');
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const restoreDefault = async () => {
    setBusy(true);
    try {
      const restored = await resetValuationScenario(symbol, scenarioType);
      setValue((previous) => ({
        ...previous,
        scenarios: (previous?.scenarios || []).map((item) => item.scenarioType === scenarioType ? restored : item)
      }));
      setDraft(draftFromScenario(restored));
      setPreview(null);
      setError('');
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const saveNote = async () => {
    if (!symbol || !onSaveNote) return;
    setNoteSaving(true);
    try {
      await onSaveNote(symbol, richNoteToMarkdown(noteDraft));
      setNoteEditing(false);
    } finally {
      setNoteSaving(false);
    }
  };

  if (!value) return <p className="muted">Loading authoritative valuation…</p>;

  return (
    <div className="valuation-workspace">
      <div className="valuation-meta-row">
        <span className={`quality-badge quality-${String(value.dataQuality?.grade || 'unavailable').toLowerCase()}`}>
          {value.dataQuality?.grade || 'Unavailable'} quality
        </span>
        <span>Engine {value.engineVersion || '--'}</span>
        <span>Price {value.priceDate || '--'}</span>
        <span>Financials {value.financialDate || '--'}</span>
      </div>

      <div className="valuation-tabs-scroll" aria-label="Valuation sections">
        <div className="valuation-tabs">
          {tabs.map((item) => (
            <button key={item} type="button" className={tab === item ? 'rank-tab active' : 'rank-tab'} onClick={() => setTab(item)}>
              {item === 'CAPE' ? 'CAPE' : item[0] + item.slice(1).toLowerCase()}
            </button>
          ))}
        </div>
      </div>

      {!value.applicability?.applicable ? (
        <article className="fundamental-panel applicability-panel">
          <h3>Valuation unavailable</h3>
          {(value.applicability?.reasons || []).map((reason) => <p key={reason} className="muted">{reason}</p>)}
        </article>
      ) : null}

      {tab === 'OVERVIEW' ? (
        <>
          <section className="valuation-summary-strip" aria-label="Valuation summary">
            <div><span>Selected model</span><strong>{value.selectedModel || '--'}</strong></div>
            <div><span>Base vs. price</span><strong className={priceVsBasePct != null && priceVsBasePct > 0 ? 'valuation-negative' : 'valuation-positive'}>{priceVsBasePct == null ? '--' : `${priceVsBasePct > 0 ? '+' : ''}${number(priceVsBasePct, 1)}%`}</strong></div>
            <div><span>As of</span><strong>{value.priceDate || '--'}</strong><small>Price date</small></div>
          </section>
          <div className="valuation-overview-grid">
            <article className="dcf-result-card"><span>Bear</span><strong>{money(value.overview?.bearValue)}</strong></article>
            <article className="dcf-result-card highlight"><span>Base</span><strong>{money(value.overview?.baseValue)}</strong></article>
            <article className="dcf-result-card"><span>Bull</span><strong>{money(value.overview?.bullValue)}</strong></article>
            <article className="dcf-result-card"><span>Current Price</span><strong>{money(value.overview?.currentPrice)}</strong></article>
            <article className="fundamental-panel valuation-range-card">
              <h3>Bear–Bull Fair Value Range</h3>
              <strong>{money(value.overview?.rangeLow)} – {money(value.overview?.rangeHigh)}</strong>
              <p className="muted">Unweighted range; no subjective probability-weighted target price.</p>
            </article>
            <article className="fundamental-panel valuation-price-context-card">
              <h3>Price context</h3>
              <strong className={priceVsBasePct != null && priceVsBasePct > 0 ? 'valuation-negative' : 'valuation-positive'}>{priceVsBasePct == null ? '--' : `${Math.abs(priceVsBasePct).toFixed(1)}% ${priceVsBasePct > 0 ? 'above' : 'below'} Base`}</strong>
              <p className="muted">Compare the market price with the data-driven Base scenario; it is not a trade recommendation.</p>
            </article>
          </div>
        </>
      ) : null}

      {tab === 'SCENARIOS' ? (
        <>
          <div className="scenario-switcher">
            {['BEAR', 'BASE', 'BULL'].map((type) => (
              <button key={type} type="button" className={scenarioType === type ? 'rank-tab active' : 'rank-tab'} onClick={() => setScenarioType(type)}>{type}</button>
            ))}
            {dirty ? <span className="unsaved-badge">Unsaved</span> : null}
          </div>
          <div className="scenario-editor-grid">
            <label className="form-field">
              <span>Base Cash Flow Mode</span>
              <select value={draft.baseCashFlowMode || 'AUTO'} onChange={(event) => setDraft((previous) => ({ ...previous, baseCashFlowMode: event.target.value }))}>
                <option value="AUTO">Auto</option><option value="MANUAL">Manual</option>
              </select>
            </label>
            <label className="form-field">
              <span>Base Cash Flow</span>
              <input type="number" step="any" value={draft.baseCashFlow ?? ''} disabled={draft.baseCashFlowMode === 'AUTO'} onChange={(event) => setDraft((previous) => ({ ...previous, baseCashFlow: event.target.value }))} />
            </label>
            <label className="form-field">
              <span>Growth Mode</span>
              <select value={draft.growthMode || 'AUTO_BLEND'} onChange={(event) => setDraft((previous) => ({ ...previous, growthMode: event.target.value }))}>
                <option value="AUTO_BLEND">Historical + consensus</option>
                <option value="HISTORICAL">Historical only</option>
                <option value="CONSENSUS">Consensus only</option>
                <option value="CUSTOM_LINEAR">Custom linear fade</option>
                <option value="CUSTOM_PATH">Custom annual path</option>
              </select>
            </label>
            <label className="form-field">
              <span>Initial Growth %</span>
              <input type="number" step="any" value={draft.initialGrowthRatePct ?? ''} disabled={!String(draft.growthMode).startsWith('CUSTOM')} onChange={(event) => setDraft((previous) => ({ ...previous, initialGrowthRatePct: event.target.value }))} />
            </label>
            <label className="form-field">
              <span>Discount Rate Mode</span>
              <select value={draft.discountRateMode || 'AUTO'} onChange={(event) => setDraft((previous) => ({ ...previous, discountRateMode: event.target.value }))}>
                <option value="AUTO">Auto WACC / cost of equity</option>
                <option value="MANUAL_RATE">Manual rate</option>
                <option value="MANUAL_CAPM_COMPONENTS">Manual CAPM components</option>
              </select>
            </label>
            <label className="form-field">
              <span>Discount Rate %</span>
              <input type="number" step="any" value={draft.discountRatePct ?? ''} disabled={draft.discountRateMode !== 'MANUAL_RATE'} onChange={(event) => setDraft((previous) => ({ ...previous, discountRatePct: event.target.value }))} />
            </label>
            {draft.discountRateMode === 'MANUAL_CAPM_COMPONENTS' ? <>
              {[['riskFreeRatePct', 'Risk-free Rate %'], ['beta', 'Beta'], ['equityRiskPremiumPct', 'Equity Risk Premium %']].map(([key, label]) => (
                <label key={key} className="form-field"><span>{label}</span><input type="number" step="any" value={draft[key] ?? ''} onChange={(event) => setDraft((previous) => ({ ...previous, [key]: event.target.value }))} /></label>
              ))}
            </> : null}
            {fields.map(([key, label, type]) => (
              <label key={key} className="form-field">
                <span>{label}</span>
                <input type={type} value={draft[key] ?? ''} step="any" disabled={!value.applicability?.applicable} onChange={(event) => setDraft((previous) => ({ ...previous, [key]: event.target.value }))} />
              </label>
            ))}
          </div>
          {draft.growthMode === 'CUSTOM_PATH' ? (
            <div className="scenario-editor-grid annual-growth-path" aria-label="Annual growth path">
              {Array.from({ length: Number(draft.projectionYears) || 0 }, (_, index) => (
                <label key={index} className="form-field"><span>Year {index + 1} Growth %</span>
                  <input type="number" step="any" value={draft.annualGrowthRatesPct?.[index] ?? ''}
                    onChange={(event) => setDraft((previous) => {
                      const path = [...(previous.annualGrowthRatesPct || [])]; path[index] = event.target.value;
                      return { ...previous, annualGrowthRatesPct: path, initialGrowthRatePct: index === 0 ? event.target.value : previous.initialGrowthRatePct };
                    })} />
                </label>
              ))}
            </div>
          ) : null}
          <div className="growth-reference-grid">
            {(value.growthReferences || []).map((reference) => (
              <article className="fundamental-panel" key={reference.type}>
                <h3>{reference.type.replaceAll('_', ' ')}</h3>
                <strong>{reference.valuePct == null ? reference.status : `${number(reference.valuePct)}%`}</strong>
                <p className="muted">{reference.sourceName} · {reference.confidence} confidence · n={reference.sampleCount ?? 0}</p>
                <small>{reference.sourceDate || reference.status}</small>
              </article>
            ))}
          </div>
          {error ? <p className="error-text">{error}</p> : null}
          <div className="valuation-actions">
            <button type="button" disabled={!dirty || !draftComplete || busy || !value.applicability?.applicable} onClick={save}>Save</button>
            <button type="button" className="secondary-button" disabled={!dirty || busy || !value.applicability?.applicable} onClick={() => setDraft(draftFromScenario(scenario))}>Reset</button>
            <button type="button" className="secondary-button" disabled={busy || scenario?.origin !== 'SAVED'} onClick={restoreDefault}>Restore data-driven default</button>
          </div>
          <div className="valuation-overview-grid compact">
            <article className="dcf-result-card"><span>Model</span><strong>{activeScenario?.selectedModel || '--'}</strong></article>
            <article className="dcf-result-card"><span>Intrinsic / Share</span><strong>{money(activeScenario?.intrinsicValuePerShare)}</strong></article>
            <article className="dcf-result-card"><span>Safety Price</span><strong>{money(activeScenario?.marginOfSafetyPrice)}</strong></article>
            <article className="dcf-result-card"><span>Terminal Weight</span><strong>{number(activeScenario?.terminalValueWeightPct)}%</strong></article>
          </div>
        </>
      ) : null}

      {tab === 'CAPE' ? (
        <>
          <div className="valuation-overview-grid">
            <article className="dcf-result-card"><span>Real CAPE 10Y</span><strong>{value.cape?.realCape10y == null ? value.cape?.status || '--' : `${number(value.cape.realCape10y, 1)}x`}</strong></article>
            <article className="dcf-result-card"><span>Real Normalized PE 5Y</span><strong>{value.cape?.realNormalizedPe5y == null ? 'N/M' : `${number(value.cape.realNormalizedPe5y, 1)}x`}</strong></article>
            <article className="dcf-result-card"><span>Real Normalized PE 3Y</span><strong>{value.cape?.realNormalizedPe3y == null ? 'N/M' : `${number(value.cape.realNormalizedPe3y, 1)}x`}</strong></article>
            <article className="dcf-result-card"><span>Historical Percentile</span><strong>{value.cape?.percentile == null ? '--' : `${number(value.cape.percentile, 0)}%`}</strong><small>n={value.cape?.sampleCount ?? 0} · {value.cape?.rangeStart || '--'} – {value.cape?.rangeEnd || '--'}</small></article>
          </div>
          <div className="table-wrap valuation-wide-table">
            <table><thead><tr><th>Quarter</th><th>Real CAPE</th><th>EPS Quarters</th></tr></thead><tbody>
              {(value.cape?.history || []).map((row) => <tr key={row.asOfDate}><td>{row.asOfDate}</td><td>{number(row.cape, 2)}</td><td>{row.earningsQuarterCount}</td></tr>)}
            </tbody></table>
          </div>
        </>
      ) : null}

      {tab === 'SENSITIVITY' ? (
        <SensitivityPanel evaluation={preview} scenario={activeScenario} symbol={symbol} scenarioType={scenarioType} />
      ) : null}

      {tab === 'DIAGNOSTICS' ? (
        <div className="diagnostics-stack">
          <article className="fundamental-panel">
            <h3>Formula bridge</h3>
            <p><strong>{value.selectedModel || '--'}</strong> · TTM cash flow {money(value.cashFlow?.latestTtmCashFlow)} · Base {money(value.cashFlow?.baseCashFlow)}</p>
            <p className="muted">{value.selectedModel === 'FCFF' ? 'Enterprise value − net debt = equity value' : value.selectedModel === 'FCFE' ? 'FCFE discounted at cost of equity = equity value' : 'No legal cash-flow model selected'}</p>
            {value.cashFlow?.crossCheckDifferencePct != null ? <small>FCFF cross-check difference: {number(value.cashFlow.crossCheckDifferencePct)}%</small> : null}
          </article>
          {(value.diagnostics || []).map((item, index) => (
            <article key={`${item.code}-${index}`} className={`dcf-diagnostic-card dcf-diagnostic-${item.severity}`}>
              <strong>{item.code}</strong><p>{item.message}</p><small>{item.evidence}</small>
            </article>
          ))}
          {(value.scenarios || []).some((item) => item.manualOverrides?.length) ? (
            <article className="fundamental-panel"><h3>User overrides</h3>
              {(value.scenarios || []).filter((item) => item.manualOverrides?.length).map((item) => (
                <p key={item.scenarioType}><strong>{item.scenarioType}</strong>: {item.manualOverrides.join(', ')}{item.updatedAt ? ` · ${item.updatedAt}` : ''}</p>
              ))}
            </article>
          ) : null}
          {(value.missingFields || []).length ? <article className="fundamental-panel"><h3>Missing fields</h3><p>{value.missingFields.join(', ')}</p></article> : null}
          {Object.keys(value.fieldSources || {}).length ? (
            <div className="table-wrap valuation-wide-table">
              <table><thead><tr><th>Field</th><th>Source</th><th>Source date</th></tr></thead><tbody>
                {Object.entries(value.fieldSources).map(([field, source]) => (
                  <tr key={field}><td>{field}</td><td>{source.sourceName || source.sourceCode}</td><td>{source.sourceDate || '--'}</td></tr>
                ))}
              </tbody></table>
            </div>
          ) : null}
          {!value.diagnostics?.length && !value.missingFields?.length ? <p className="muted">No valuation diagnostics.</p> : null}
        </div>
      ) : null}

      {onSaveNote ? (
        <section className="valuation-note-panel">
          <RichTextNotePanel
            title="Valuation Notes"
            headingLevel={3}
            value={savedNote}
            draft={noteDraft}
            onChange={setNoteDraft}
            isEditing={noteEditing}
            isDirty={noteDirty}
            saving={noteSaving}
            disabled={!symbol}
            onEdit={() => setNoteEditing(true)}
            onCancel={() => { setNoteDraft(savedNote); setNoteEditing(false); }}
            onSave={saveNote}
            placeholder="Record valuation thesis, scenario changes, catalysts, margin-of-safety decisions, and reasons the market may differ from your estimate..."
            emptyText="No valuation note yet."
            autoFocus
            meta={symbol ? <>Symbol: {symbol} | Last updated: {noteItem?.updatedAt ? new Date(noteItem.updatedAt).toLocaleString() : '--'}</> : 'Load or select a symbol first.'}
          />
        </section>
      ) : null}
    </div>
  );
}

function SensitivityPanel({ evaluation, scenario, symbol, scenarioType }) {
  const [result, setResult] = useState(evaluation);
  useEffect(() => { setResult(evaluation); }, [evaluation]);
  useEffect(() => {
    if (result || !scenario?.assumptions || !symbol) return;
    evaluateValuation(symbol, scenarioType, scenario.assumptions).then(setResult).catch(() => undefined);
  }, [result, scenario, symbol, scenarioType]);
  if (!result) return <p className="muted">Preparing sensitivity matrix…</p>;
  return (
    <>
      <div className="valuation-overview-grid compact">
        <article className="dcf-result-card"><span>Implied Initial Growth</span><strong>{result.reverseDcf?.impliedInitialGrowthRatePct == null ? '--' : `${number(result.reverseDcf.impliedInitialGrowthRatePct)}%`}</strong></article>
        <article className="dcf-result-card"><span>Implied Discount Rate</span><strong>{result.reverseDcf?.impliedDiscountRatePct == null ? '--' : `${number(result.reverseDcf.impliedDiscountRatePct)}%`}</strong></article>
      </div>
      <div className="table-wrap valuation-wide-table"><table className="dcf-sensitivity-table"><thead><tr><th>g / r</th>{(result.sensitivity?.discountRatesPct || []).map((rate) => <th key={rate}>{number(rate)}%</th>)}</tr></thead><tbody>
        {(result.sensitivity?.terminalGrowthRatesPct || []).map((growth, rowIndex) => <tr key={growth}><th>{number(growth)}%</th>{(result.sensitivity?.intrinsicValues?.[rowIndex] || []).map((cell, columnIndex) => <td key={`${growth}-${columnIndex}`}>{money(cell)}</td>)}</tr>)}
      </tbody></table></div>
    </>
  );
}
