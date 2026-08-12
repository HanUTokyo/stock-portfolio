import { useEffect, useMemo, useRef, useState } from 'react';
import { evaluateValuation, getForecastTemplate, getValuation, getWaccReferences, previewForecast, refreshWaccReferences, resetForecastSnapshot, resetValuationScenario, saveForecastSnapshot, saveValuationScenario } from '../api.js';
import { RichTextNotePanel, richNoteToMarkdown } from './RichTextEditor';

const tabs = ['OVERVIEW', 'SCENARIOS', 'FORECAST', 'CAPE', 'SENSITIVITY', 'DIAGNOSTICS'];
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

const scenarioTypes = ['BEAR', 'BASE', 'BULL'];

function asList(value) {
  if (Array.isArray(value)) return value.filter((item) => item != null && String(item).trim()).map(String);
  if (value == null || value === '') return [];
  return [String(value)];
}

function methodAvailability(method) {
  if (!method) return { available: false, status: 'NOT PROVIDED', reasons: ['No method payload returned.'] };
  const raw = method.availability;
  const nested = raw && typeof raw === 'object' ? raw : {};
  const status = typeof raw === 'string'
    ? raw
    : nested.status ?? (typeof method.status === 'string' ? method.status : null);
  const explicit = typeof raw === 'boolean'
    ? raw
    : typeof nested.available === 'boolean'
      ? nested.available
      : raw == null && typeof method.available === 'boolean'
        ? method.available
        : null;
  const normalizedStatus = status == null ? '' : String(status).trim().toUpperCase();
  const available = explicit ?? (!normalizedStatus
    ? Boolean(method.scenarios || method.overview)
    : ['AVAILABLE', 'INDICATIVE', 'REFERENCE', 'READY', 'READY_WITH_CAVEATS'].includes(normalizedStatus));
  const reasons = asList(
    nested.missingInputs ?? method.missingInputs
      ?? nested.missingReasons ?? method.missingReasons
      ?? nested.reasons ?? method.reasons
      ?? (!available ? method.warnings : null)
  );
  return {
    available,
    status: status || (available ? 'AVAILABLE' : 'UNAVAILABLE'),
    reasons
  };
}

function scenarioMap(method) {
  const raw = method?.scenarios;
  if (Array.isArray(raw)) {
    return Object.fromEntries(raw.map((item) => [String(item?.scenarioType || item?.type || '').toUpperCase(), item]));
  }
  if (raw && typeof raw === 'object') {
    return Object.fromEntries(Object.entries(raw).map(([key, item]) => [String(key).toUpperCase(), item]));
  }
  const overview = method?.overview;
  if (!overview) return {};
  return {
    BEAR: { intrinsicValuePerShare: overview.bearValue },
    BASE: { intrinsicValuePerShare: overview.baseValue },
    BULL: { intrinsicValuePerShare: overview.bullValue }
  };
}

function scenarioValue(scenario) {
  if (scenario == null) return null;
  if (typeof scenario === 'number' || typeof scenario === 'string') return Number.isFinite(Number(scenario)) ? Number(scenario) : null;
  return scenario.intrinsicValuePerShare ?? scenario.valuePerShare ?? scenario.perShareValue
    ?? scenario.equityValuePerShare ?? scenario.fairValuePerShare ?? scenario.value ?? null;
}

function methodRange(method, scenarios) {
  const overview = method?.overview || {};
  const low = overview.rangeLow ?? overview.lowValue ?? method?.rangeLow;
  const high = overview.rangeHigh ?? overview.highValue ?? method?.rangeHigh;
  if (low != null || high != null) return { low, high };
  const values = scenarioTypes.map((type) => scenarioValue(scenarios[type]))
    .map(Number).filter(Number.isFinite);
  return values.length ? { low: Math.min(...values), high: Math.max(...values) } : { low: null, high: null };
}

function methodOverview(method, scenarios, currentPrice) {
  const range = methodRange(method, scenarios);
  return {
    bearValue: scenarioValue(scenarios.BEAR),
    baseValue: scenarioValue(scenarios.BASE),
    bullValue: scenarioValue(scenarios.BULL),
    rangeLow: range.low,
    rangeHigh: range.high,
    currentPrice
  };
}

function reconciliationScenario(reconciliation, type) {
  const scenarios = reconciliation?.scenarios;
  if (Array.isArray(scenarios)) {
    return scenarios.find((item) => String(item?.scenarioType || item?.type || '').toUpperCase() === type) || null;
  }
  if (scenarios && typeof scenarios === 'object') return scenarios[type] ?? scenarios[type.toLowerCase()] ?? null;
  if (type === 'BASE') return reconciliation?.base ?? null;
  return null;
}

function differenceValues(reconciliation, type, fcffValue, fcfeValue) {
  const row = reconciliationScenario(reconciliation, type) || {};
  const difference = row.differencePerShare ?? row.absoluteDifferencePerShare ?? row.deltaPerShare
    ?? row.difference ?? (fcffValue != null && fcfeValue != null ? Number(fcfeValue) - Number(fcffValue) : null);
  const denominator = fcffValue == null ? null : Math.abs(Number(fcffValue));
  const differencePct = row.differencePct ?? row.percentageDifferencePct ?? row.deltaPct
    ?? (type === 'BASE' ? reconciliation?.baseDifferencePct : null)
    ?? (difference != null && denominator ? (Number(difference) / denominator) * 100 : null);
  return { difference, differencePct };
}

function reconciliationMethodValue(row, method) {
  if (!row) return null;
  if (String(row.primaryMethod || '').toUpperCase() === method) return row.primaryIntrinsicValuePerShare;
  if (String(row.crossCheckMethod || '').toUpperCase() === method) return row.crossCheckIntrinsicValuePerShare;
  const prefix = method.toLowerCase();
  return row[`${prefix}ValuePerShare`] ?? row[`${prefix}PerShare`] ?? row[`${prefix}Value`] ?? null;
}

function sharedMethodPolicy(fcff, fcfe, field) {
  const fcffValue = fcff?.[field];
  const fcfeValue = fcfe?.[field];
  if (fcffValue == null) return fcfeValue;
  if (fcfeValue == null || String(fcffValue) === String(fcfeValue)) return fcffValue;
  return `FCFF: ${policyText(fcffValue)} · FCFE: ${policyText(fcfeValue)}`;
}

function readinessSummary(readiness) {
  if (!readiness) return null;
  if (typeof readiness === 'string') return { status: readiness, reasons: [] };
  return {
    status: readiness.status || readiness.readiness || readiness.verdict || 'UNKNOWN',
    reasons: asList(readiness.reasons ?? readiness.warnings ?? readiness.blockers)
  };
}

function policyText(policy) {
  if (policy == null) return null;
  if (typeof policy !== 'object') return String(policy).replaceAll('_', ' ');
  if (Array.isArray(policy)) return policy.map(policyText).filter(Boolean).join(' · ');
  const summary = policy.summary ?? policy.description ?? policy.policy ?? policy.method ?? policy.mode ?? policy.name;
  if (summary != null) return String(summary).replaceAll('_', ' ');
  const parts = Object.entries(policy)
    .filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value))
    .slice(0, 4)
    .map(([key, value]) => `${key.replace(/([A-Z])/g, ' $1').toLowerCase()}: ${value}`);
  return parts.length ? parts.join(' · ') : null;
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
    fcffWaccSelection: source.fcffWaccSelection ?? null,
    fcffCashInterestReference: source.fcffCashInterestReference ?? resolved.fcffCashInterestReference ?? '',
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
  payload.fcffWaccSelection = draft.fcffWaccSelection || null;
  payload.fcffCashInterestReference = draft.fcffCashInterestReference === '' || draft.fcffCashInterestReference == null
    ? null : Number(draft.fcffCashInterestReference);
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
  const [forecastTemplate, setForecastTemplate] = useState(null);
  const [forecastArchetype, setForecastArchetype] = useState(null);
  const [forecastPreview, setForecastPreview] = useState(null);
  const [forecastError, setForecastError] = useState('');
  const [forecastBusy, setForecastBusy] = useState(false);
  const [waccReferences, setWaccReferences] = useState(null);
  const [waccRefreshing, setWaccRefreshing] = useState(false);
  const [selectedMethod, setSelectedMethod] = useState('FCFF');
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
  const methodComparison = useMemo(() => {
    const methods = value?.valuationMethods;
    if (!methods || typeof methods !== 'object') return null;
    const fcff = methods.fcff ?? methods.FCFF ?? null;
    const fcfe = methods.fcfe ?? methods.FCFE ?? null;
    if (!fcff && !fcfe) return null;
    return {
      fcff,
      fcfe,
      reconciliation: value?.crossModelReconciliation || null,
      operatingForecast: value?.sharedOperatingForecast ?? value?.operatingForecast
        ?? value?.valuationPolicy?.operatingForecast ?? methods.sharedOperatingForecast ?? methods.operatingForecast
        ?? sharedMethodPolicy(fcff, fcfe, 'forecastMode') ?? null,
      debtPolicy: value?.sharedDebtPolicy ?? value?.debtPolicy
        ?? value?.valuationPolicy?.debtPolicy ?? methods.sharedDebtPolicy ?? methods.debtPolicy
        ?? sharedMethodPolicy(fcff, fcfe, 'debtPolicy') ?? null
    };
  }, [value]);
  const selectedValuationMethod = methodComparison
    ? (selectedMethod === 'FCFE' ? methodComparison.fcfe : methodComparison.fcff)
    : null;
  const selectedMethodScenarios = useMemo(
    () => selectedValuationMethod ? scenarioMap(selectedValuationMethod) : {},
    [selectedValuationMethod]
  );
  const selectedMethodScenario = selectedMethodScenarios[scenarioType] || null;
  const displayedSelectedModel = methodComparison
    ? selectedMethod
    : value?.selectedModel || value?.scenarios?.find((item) => item.scenarioType === 'BASE')?.selectedModel || null;
  const displayedOverview = methodComparison
    ? methodOverview(selectedValuationMethod, selectedMethodScenarios, value?.overview?.currentPrice)
    : value?.overview;
  const baseValue = Number(displayedOverview?.baseValue);
  const currentPrice = Number(displayedOverview?.currentPrice);
  const priceVsBasePct = Number.isFinite(baseValue) && baseValue !== 0 && Number.isFinite(currentPrice)
    ? ((currentPrice / baseValue) - 1) * 100
    : null;
  const readiness = useMemo(() => readinessSummary(value?.readiness ?? (value?.crossModelReconciliation?.readiness ? {
    status: value.crossModelReconciliation.readiness,
    reasons: value.crossModelReconciliation.warnings
  } : null)), [value]);

  useEffect(() => {
    setValue(initialValue);
    const nextScenario = initialValue?.scenarios?.find((item) => item.scenarioType === scenarioType) || null;
    setDraft(draftFromScenario(nextScenario));
    setPreview(null);
    setError('');
  }, [initialValue, symbol, scenarioType]);

  useEffect(() => {
    if (!methodComparison) return;
    if (selectedMethod === 'FCFF' && methodComparison.fcff) return;
    if (selectedMethod === 'FCFE' && methodComparison.fcfe) return;
    setSelectedMethod(methodComparison.fcff ? 'FCFF' : 'FCFE');
  }, [methodComparison, selectedMethod]);

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
    setForecastTemplate(null);
    setForecastArchetype(null);
    setForecastPreview(null);
    setForecastError('');
  }, [symbol]);

  useEffect(() => {
    if (!symbol) return;
    getWaccReferences(symbol).then(setWaccReferences).catch(() => setWaccReferences(null));
  }, [symbol]);

  const refreshReferences = async () => {
    if (!symbol) return;
    setWaccRefreshing(true);
    try { setWaccReferences(await refreshWaccReferences(symbol)); } catch (e) { setError(e.message); }
    finally { setWaccRefreshing(false); }
  };

  const applyFcffWacc = (reference) => {
    if (reference?.provider === 'SYSTEM_ESTIMATE') {
      setDraft((previous) => ({ ...previous, fcffWaccSelection: null }));
      return;
    }
    setDraft((previous) => ({ ...previous, fcffWaccSelection: {
      provider: reference.provider, ratePct: reference.ratePct, sourceUrl: reference.sourceUrl,
      providerAsOf: reference.providerAsOf, retrievedAt: reference.retrievedAt
    } }));
  };

  useEffect(() => {
    if (tab !== 'FORECAST' || !symbol || forecastTemplate) return;
    getForecastTemplate(symbol).then((template) => {
      setForecastTemplate(template);
      setForecastArchetype(template.savedSnapshot?.archetype || template.suggestedArchetype || Object.keys(template.templates || {})[0] || null);
    }).catch((e) => setForecastError(e.message));
  }, [tab, symbol, forecastTemplate]);

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
  const displayedScenario = methodComparison ? selectedMethodScenario : activeScenario;

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
        {value.calculationMode ? <span>Mode {String(value.calculationMode).replaceAll('_', ' ')}</span> : null}
        {readiness ? <span className={`readiness-badge readiness-${String(readiness.status).toLowerCase().replaceAll('_', '-')}`}>{String(readiness.status).replaceAll('_', ' ')}</span> : null}
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
          {readiness ? <ReadinessPanel readiness={readiness} /> : null}
          {methodComparison ? <CrossMethodComparison comparison={methodComparison} selectedMethod={selectedMethod} onSelectMethod={setSelectedMethod} /> : null}
          <section className="valuation-summary-strip" aria-label="Valuation summary">
            <div><span>{methodComparison ? 'Viewing model' : 'Selected model'}</span><strong>{displayedSelectedModel || '--'}</strong></div>
            <div><span>Base vs. price</span><strong className={priceVsBasePct != null && priceVsBasePct > 0 ? 'valuation-negative' : 'valuation-positive'}>{priceVsBasePct == null ? '--' : `${priceVsBasePct > 0 ? '+' : ''}${number(priceVsBasePct, 1)}%`}</strong></div>
            <div><span>As of</span><strong>{value.priceDate || '--'}</strong><small>Price date</small></div>
          </section>
          <div className="valuation-overview-grid">
            <article className="dcf-result-card"><span>Bear</span><strong>{money(displayedOverview?.bearValue)}</strong></article>
            <article className="dcf-result-card highlight"><span>Base</span><strong>{money(displayedOverview?.baseValue)}</strong></article>
            <article className="dcf-result-card"><span>Bull</span><strong>{money(displayedOverview?.bullValue)}</strong></article>
            <article className="dcf-result-card"><span>Current Price</span><strong>{money(displayedOverview?.currentPrice)}</strong></article>
            <article className="fundamental-panel valuation-range-card">
              <h3>Bear–Bull Fair Value Range</h3>
              <strong>{money(displayedOverview?.rangeLow)} – {money(displayedOverview?.rangeHigh)}</strong>
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
          <section className="fundamental-panel wacc-reference-panel" aria-label="FCFF WACC references">
            <div className="section-title-row"><div><h3>FCFF WACC references</h3><p className="muted">Select a reference only for FCFF. FCFE always uses Cost of Equity.</p></div><button type="button" className="secondary-button" disabled={waccRefreshing} onClick={refreshReferences}>{waccRefreshing ? 'Refreshing…' : 'Refresh references'}</button></div>
            {waccReferences?.references?.length ? <div className="table-wrap"><table><thead><tr><th>Source</th><th>WACC</th><th>vs system</th><th>Status</th><th>Retrieved</th><th /></tr></thead><tbody>{waccReferences.references.map((reference) => {
              const selected = draft.fcffWaccSelection?.provider === reference.provider || (!draft.fcffWaccSelection && reference.provider === 'SYSTEM_ESTIMATE');
              return <tr key={reference.provider}><th>{reference.sourceUrl ? <a href={reference.sourceUrl} target="_blank" rel="noreferrer">{reference.provider.replaceAll('_', ' ')}</a> : reference.provider.replaceAll('_', ' ')}</th><td>{reference.ratePct == null ? '--' : `${number(reference.ratePct)}%`}</td><td>{reference.differenceFromSystemPct == null ? '--' : `${number(reference.differenceFromSystemPct)} pp`}</td><td>{reference.status}</td><td>{reference.retrievedAt ? new Date(reference.retrievedAt).toLocaleDateString() : '--'}</td><td><button type="button" className="secondary-button" disabled={!reference.selectable || selected} onClick={() => applyFcffWacc(reference)}>{selected ? 'Applied' : reference.provider === 'SYSTEM_ESTIMATE' ? 'Use system' : 'Apply to FCFF'}</button></td></tr>;
            })}</tbody></table></div> : <p className="muted">References have not been loaded. Refresh to fetch public source pages manually.</p>}
            <label className="form-field"><span>TTM cash-interest reference (USD)</span><input aria-label="TTM cash-interest reference (USD)" type="number" min="0" step="any" value={draft.fcffCashInterestReference ?? ''} onChange={(event) => setDraft((previous) => ({ ...previous, fcffCashInterestReference: event.target.value }))} /></label>
            <p className="muted">Required with an external WACC to display an INDICATIVE Cash FCFF reference: CFO − capex + after-tax cash interest. This is a user assumption, does not affect FCFE, and never makes the economic FCFF bridge complete.</p>
            {draft.fcffWaccSelection ? <p className="muted">{draft.fcffWaccSelection.provider} at {number(draft.fcffWaccSelection.ratePct)}% will be saved as an immutable FCFF WACC snapshot. It never changes FCFE.</p> : <p className="muted">System WACC is active. External references are inputs only and do not change FCFF bridge readiness.</p>}
          </section>
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
            <article className="dcf-result-card"><span>Model</span><strong>{displayedScenario?.selectedModel || displayedSelectedModel || '--'}</strong></article>
            <article className="dcf-result-card"><span>Intrinsic / Share</span><strong>{money(displayedScenario?.intrinsicValuePerShare)}</strong></article>
            <article className="dcf-result-card"><span>Safety Price</span><strong>{money(displayedScenario?.marginOfSafetyPrice)}</strong></article>
            <article className="dcf-result-card"><span>Terminal Weight</span><strong>{number(displayedScenario?.terminalValueWeightPct)}%</strong></article>
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
        <SensitivityPanel evaluation={preview} valuationMethods={preview?.valuationMethods ?? value?.valuationMethods}
          scenario={activeScenario} symbol={symbol} scenarioType={scenarioType} selectedMethod={selectedMethod} />
      ) : null}

      {tab === 'FORECAST' ? <ForecastPanel symbol={symbol} template={forecastTemplate} archetype={forecastArchetype}
        onArchetypeChange={(next) => { setForecastArchetype(next); setForecastPreview(null); }} preview={forecastPreview}
        error={forecastError} busy={forecastBusy} onPreview={async (payload) => { if (!forecastArchetype) return; setForecastBusy(true); setForecastError(''); try { setForecastPreview(await previewForecast(symbol, payload)); } catch (e) { setForecastError(e.message); } finally { setForecastBusy(false); } }}
        onSave={async (payload) => { if (!forecastArchetype) return; setForecastBusy(true); setForecastError(''); try { setForecastPreview(await saveForecastSnapshot(symbol, payload)); setValue(await getValuation(symbol)); } catch (e) { setForecastError(e.message); } finally { setForecastBusy(false); } }}
        onReset={async () => { setForecastBusy(true); try { await resetForecastSnapshot(symbol); setForecastPreview(null); setValue(await getValuation(symbol)); } catch (e) { setForecastError(e.message); } finally { setForecastBusy(false); } }} /> : null}

      {tab === 'DIAGNOSTICS' ? (
        <div className="diagnostics-stack">
          {value.fundamentalsFreshness ? <article className={`fundamental-panel ${value.fundamentalsFreshness.status === 'STALE_FUNDAMENTALS' ? 'dcf-diagnostic-card dcf-diagnostic-critical' : ''}`}><h3>Fundamentals freshness</h3><p><strong>{String(value.fundamentalsFreshness.status).replaceAll('_', ' ')}</strong> · Financial period {value.fundamentalsFreshness.financialDate || '--'} ({value.fundamentalsFreshness.financialAgeDays ?? '--'} days) · Filing {value.fundamentalsFreshness.filingDate || '--'} ({value.fundamentalsFreshness.filingAgeDays ?? '--'} days)</p>{(value.fundamentalsFreshness.reasons || []).map((reason) => <p className="error-text" key={reason}>{reason}</p>)}</article> : null}
          <CashFlowBridgePanel bridge={value.cashFlowBridge} />
          <article className="fundamental-panel">
            <h3>Formula bridge</h3>
            <p><strong>{displayedSelectedModel || '--'}</strong> · TTM cash flow {money(selectedValuationMethod?.latestTtmCashFlow ?? value.cashFlow?.latestTtmCashFlow)} · Base {money(selectedValuationMethod?.normalizedBaseCashFlow ?? value.cashFlow?.baseCashFlow)}</p>
            <p className="muted">{displayedSelectedModel === 'FCFF' ? 'Enterprise value − net debt = equity value' : displayedSelectedModel === 'FCFE' ? 'FCFE discounted at cost of equity = equity value' : 'No legal cash-flow model selected'}</p>
            {(selectedValuationMethod?.definitionCrossCheckDifferencePct ?? value.cashFlow?.crossCheckDifferencePct) != null ? <small>FCFF cross-check difference: {number(selectedValuationMethod?.definitionCrossCheckDifferencePct ?? value.cashFlow.crossCheckDifferencePct)}%</small> : null}
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

function CashFlowBridgePanel({ bridge }) {
  if (!bridge) return null;
  const status = String(bridge.coverageStatus || 'UNAVAILABLE').replaceAll('_', ' ');
  return <article className="fundamental-panel cash-flow-bridge" aria-label="FCFF cash flow bridge">
    <div className="section-title-row"><div><h3>FCFF definition bridge</h3><p className="muted">Economic FCFF is the only FCFF DCF input once verified. Cash FCFF is a reference until the SEC indirect-CFO bridge is complete.</p></div><strong>{status}</strong></div>
    <div className="valuation-overview-grid compact">
      <article className="dcf-result-card"><span>Economic FCFF</span><strong>{money(bridge.economicFcff)}</strong></article>
      <article className="dcf-result-card"><span>Cash FCFF reference</span><strong>{money(bridge.cashFcffReferenceOnly)}</strong></article>
      <article className="dcf-result-card"><span>Unexplained residual</span><strong>{money(bridge.residual)}</strong></article>
      <article className="dcf-result-card"><span>Difference</span><strong>{bridge.reconciliationDifferencePct == null ? '--' : `${number(bridge.reconciliationDifferencePct)}%`}</strong></article>
    </div>
    <div className="table-wrap valuation-wide-table"><table><thead><tr><th>Bucket</th><th>Bridge item</th><th>Amount</th><th>SEC concept</th><th>Status</th></tr></thead><tbody>{(bridge.ledger || []).map((item) => <tr key={`${item.bucket}-${item.label}`}><th>{item.bucket}</th><td>{item.label}</td><td>{money(item.amount)}</td><td>{item.sourceConcept || '--'}</td><td>{String(item.status || '--').replaceAll('_', ' ')}</td></tr>)}</tbody></table></div>
    {(bridge.sourceAccessions || []).length ? <p className="muted">SEC filing accessions: {bridge.sourceAccessions.join(', ')}</p> : null}
    {(bridge.missingInputs || []).length ? <p className="error-text">Required bridge coverage: {bridge.missingInputs.join(', ')}</p> : null}
    {(bridge.warnings || []).map((warning) => <p className="muted" key={warning}>{warning}</p>)}
  </article>;
}

function ReadinessPanel({ readiness }) {
  return (
    <article className="fundamental-panel valuation-readiness-panel" aria-label="Valuation readiness">
      <div>
        <span>Decision readiness</span>
        <strong>{String(readiness.status).replaceAll('_', ' ')}</strong>
      </div>
      {readiness.reasons.length ? (
        <ul>{readiness.reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
      ) : <p className="muted">No additional readiness caveats were returned.</p>}
    </article>
  );
}

function MethodSummaryCard({ label, method, scenarios, selected, onSelect }) {
  const availability = methodAvailability(method);
  const range = methodRange(method, scenarios);
  return (
    <article className={`fundamental-panel valuation-method-card ${availability.available ? 'method-available' : 'method-unavailable'} ${selected ? 'method-selected' : ''}`}>
      <div className="valuation-method-card-head">
        <h3>{label}</h3>
        <span>{String(availability.status).replaceAll('_', ' ')}</span>
      </div>
      <strong>{availability.available ? `${money(range.low)} – ${money(range.high)}` : 'Not available'}</strong>
      {availability.status === 'REFERENCE' ? <p className="warning-text">Reference only — SEC audit evidence is incomplete; this is not a verified DCF result.</p> : null}
      <p className="muted">Bear–Bull intrinsic value per share</p>
      <p className="muted">{label === 'FCFF' ? 'Enterprise cash flow for shareholders + creditors, discounted at WACC.' : 'Shareholder cash flow, discounted at Cost of Equity; WACC references do not apply.'}</p>
      {availability.reasons.length ? (
        <ul className="valuation-method-reasons">
          {availability.reasons.map((reason) => <li key={reason}>{reason}</li>)}
        </ul>
      ) : null}
      <button type="button" className="secondary-button method-view-button" aria-pressed={selected} onClick={onSelect}>
        View {label} result
      </button>
    </article>
  );
}

function CrossMethodComparison({ comparison, selectedMethod, onSelectMethod }) {
  const fcffScenarios = scenarioMap(comparison.fcff);
  const fcfeScenarios = scenarioMap(comparison.fcfe);
  const selected = selectedMethod === 'FCFE' ? comparison.fcfe : comparison.fcff;
  const selectedScenarios = selectedMethod === 'FCFE' ? fcfeScenarios : fcffScenarios;
  const selectedBase = selectedScenarios.BASE;
  const operatingForecast = policyText(comparison.operatingForecast);
  const debtPolicy = policyText(comparison.debtPolicy);
  const baseGrowth = (comparison.fcff?.growthProvenance || []).find((item) => item.scenarioType === 'BASE')
    || (comparison.fcfe?.growthProvenance || []).find((item) => item.scenarioType === 'BASE');
  return (
    <section className="valuation-method-comparison" aria-label="FCFF and FCFE valuation comparison">
      <div className="valuation-method-heading">
        <div><span>Two valuation perspectives</span><h3>FCFF / FCFE comparison</h3></div>
        <p className="muted">{comparison.reconciliation?.comparabilityStatus === 'COMPARABLE_SHARED_FORECAST' ? 'Shared forecast: growth and terminal assumptions are identical across both methods.' : 'Forecast comparability is not established.'}</p>
      </div>
      <div className="valuation-method-grid">
        <MethodSummaryCard label="FCFF" method={comparison.fcff} scenarios={fcffScenarios} selected={selectedMethod === 'FCFF'} onSelect={() => onSelectMethod('FCFF')} />
        <MethodSummaryCard label="FCFE" method={comparison.fcfe} scenarios={fcfeScenarios} selected={selectedMethod === 'FCFE'} onSelect={() => onSelectMethod('FCFE')} />
      </div>
      <article className="valuation-method-perspective" aria-live="polite">
        <div><span>Viewing {selectedMethod}</span><h4>{selectedMethod === 'FCFF' ? 'Enterprise perspective: shareholders + creditors' : 'Equity perspective: shareholders'}</h4></div>
        <p className="muted">{selectedMethod === 'FCFF'
          ? 'Discount FCFF at WACC to obtain enterprise value, then apply the net-debt bridge to obtain equity value.'
          : 'Discount FCFE at cost of equity to obtain equity value directly; financing policy is part of the shareholder cash flow.'}</p>
        <div className="valuation-method-view-grid">
          <article><span>Cash flow</span><strong>{selected?.cashFlowDefinition || (selectedMethod === 'FCFF' ? 'FCFF' : 'FCFE')}</strong></article>
          <article><span>Discount rate</span><strong>{selected?.discountRateType || (selectedMethod === 'FCFF' ? 'WACC' : 'Cost of Equity')}</strong></article>
          <article><span>Base intrinsic / share</span><strong>{money(scenarioValue(selectedBase))}</strong></article>
        </div>
      </article>
      {operatingForecast || debtPolicy ? (
        <div className="valuation-policy-grid">
          {operatingForecast ? <article><span>Shared operating forecast</span><strong>{operatingForecast}</strong></article> : null}
          {debtPolicy ? <article><span>Debt policy</span><strong>{debtPolicy}</strong></article> : null}
        </div>
      ) : null}
      {baseGrowth ? <p className="muted">Forecast ID: {baseGrowth.forecastId} · Base growth {number(baseGrowth.initialGrowthRatePct)}% · {String(baseGrowth.source).replaceAll('_', ' ')}. {baseGrowth.selectionReason}</p> : null}
      <div className="table-wrap valuation-wide-table">
        <table className="valuation-method-table">
          <thead><tr><th>Scenario</th><th>FCFF / share</th><th>FCFE / share</th><th>Difference / share</th><th>Difference %</th></tr></thead>
          <tbody>
            {scenarioTypes.map((type) => {
              const reconciliation = reconciliationScenario(comparison.reconciliation, type) || {};
              const fcffValue = reconciliationMethodValue(reconciliation, 'FCFF') ?? scenarioValue(fcffScenarios[type]);
              const fcfeValue = reconciliationMethodValue(reconciliation, 'FCFE') ?? scenarioValue(fcfeScenarios[type]);
              const difference = differenceValues(comparison.reconciliation, type, fcffValue, fcfeValue);
              return (
                <tr key={type}>
                  <th>{type[0] + type.slice(1).toLowerCase()}</th>
                  <td>{money(fcffValue)}</td>
                  <td>{money(fcfeValue)}</td>
                  <td>{money(difference.difference)}</td>
                  <td>{difference.differencePct == null ? '--' : `${number(difference.differencePct)}%`}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const forecastFields = [
  ['revenueGrowthRate', 'Revenue growth'], ['ebitMargin', 'EBIT margin'], ['taxRate', 'Tax rate'],
  ['depreciationAndAmortizationRate', 'D&A / revenue'], ['capexRate', 'Capex / revenue'], ['changeInNetWorkingCapitalRate', 'ΔNWC / revenue']
];

function ForecastPanel({ symbol, template, archetype, onArchetypeChange, preview, error, busy, onPreview, onSave, onReset }) {
  const selected = template?.templates?.[archetype];
  const [draft, setDraft] = useState(null);
  useEffect(() => {
    const saved = template?.savedSnapshot?.archetype === archetype ? template.savedSnapshot?.scenarios : null;
    setDraft(saved || (selected?.scenarios ? JSON.parse(JSON.stringify(selected.scenarios)) : null));
  }, [selected, template?.savedSnapshot, archetype]);
  if (!template) return <p className="muted">Loading forecast architecture…</p>;
  if (template.eligibility !== 'AVAILABLE') return <section className="fundamental-panel"><h3>Explicit forecast unavailable</h3>{(template.reasons || []).map((reason) => <p className="error-text" key={reason}>{reason}</p>)}</section>;
  const updateDriver = (scenario, index, field, raw) => setDraft((previous) => {
    const next = JSON.parse(JSON.stringify(previous));
    next[scenario].explicitOperatingDrivers[index][field] = raw === '' ? null : Number(raw);
    return next;
  });
  const payload = { archetype, scenarios: draft, debtFinancingPolicy: selected?.debtFinancingPolicy };
  return <section className="forecast-architecture" aria-label="Growth forecast architecture">
    <article className="fundamental-panel">
      <h3>Suggested valuation mode: {String(template.suggestedArchetype || 'MANUAL').replaceAll('_', ' ')}</h3>
      <p className="muted">{template.confidence} confidence · {(template.reasons || []).join(' ')}</p>
      <p className="muted">{template.sharesPolicy?.replaceAll('_', ' ')} · shares {number(template.sharesOutstanding, 0)}</p>
      <div className="forecast-mode-picker">{Object.keys(template.templates || {}).map((item) => <button type="button" key={item} className={archetype === item ? 'rank-tab active' : 'rank-tab'} onClick={() => onArchetypeChange(item)}>{item.replaceAll('_', ' ')}</button>)}</div>
    </article>
    {selected && draft ? <>
      <article className="fundamental-panel"><h3>{String(archetype).replaceAll('_', ' ')}</h3><p>{selected.description}</p><p className="muted">{selected.revenueDriverLabel} · Template {template.templateVersion} · {template.snapshotStatus === 'SAVED_SNAPSHOT' ? 'Saved snapshot loaded' : 'New template preview'}</p>{(selected.warnings || []).map((warning) => <p className="muted" key={warning}>{warning}</p>)}</article>
      {['BEAR', 'BASE', 'BULL'].map((scenario) => <article className="fundamental-panel forecast-driver-card" key={scenario}><h3>{scenario} operating drivers</h3><div className="table-wrap"><table><thead><tr><th>Driver</th>{[1, 2, 3, 4, 5].map((year) => <th key={year}>Y{year}</th>)}</tr></thead><tbody>{forecastFields.map(([field, label]) => <tr key={field}><th>{label}</th>{draft[scenario].explicitOperatingDrivers.map((driver, index) => <td key={index}><input aria-label={`${scenario} Y${index + 1} ${label}`} type="number" step="0.001" value={driver[field] ?? ''} onChange={(event) => updateDriver(scenario, index, field, event.target.value)} /></td>)}</tr>)}</tbody></table></div></article>)}
      <div className="valuation-actions"><button type="button" disabled={busy} onClick={() => onPreview(payload)}>Preview FCFF / FCFE</button><button type="button" disabled={busy} onClick={() => onSave(payload)}>Save forecast snapshot</button><button type="button" className="secondary-button" disabled={busy} onClick={onReset}>Reset saved snapshot</button></div>
    </> : null}
    {error ? <p className="error-text">{error}</p> : null}
    {preview ? <ForecastPreviewResults preview={preview} /> : null}
  </section>;
}

function ForecastPreviewResults({ preview }) {
  return <section className="forecast-preview-results"><h3>Explicit forecast preview</h3><p className={`readiness-badge readiness-${String(preview.readiness || '').toLowerCase().replaceAll('_', '-')}`}>{String(preview.readiness || '').replaceAll('_', ' ')}</p>{(preview.missingInputs || []).map((item) => <p className="muted" key={item}>{item}</p>)}<div className="table-wrap valuation-wide-table"><table><thead><tr><th>Scenario</th><th>FCFF EV</th><th>FCFF Equity</th><th>FCFE Equity</th></tr></thead><tbody>{Object.entries(preview.scenarios || {}).map(([scenario, result]) => <tr key={scenario}><th>{scenario}</th><td>{money(result.fcff?.enterpriseValue)}</td><td>{money(result.fcff?.equityValue)}</td><td>{money(result.fcfe?.equityValue)}</td></tr>)}</tbody></table></div></section>;
}

function SensitivityMatrix({ sensitivity }) {
  if (!sensitivity?.discountRatesPct?.length || !sensitivity?.terminalGrowthRatesPct?.length) {
    return <p className="muted">Sensitivity matrix is unavailable for this method.</p>;
  }
  return <div className="table-wrap valuation-wide-table"><table className="dcf-sensitivity-table"><thead><tr><th>Terminal g / discount rate</th>{sensitivity.discountRatesPct.map((rate) => <th key={rate}>{number(rate)}%</th>)}</tr></thead><tbody>
    {sensitivity.terminalGrowthRatesPct.map((growth, rowIndex) => <tr key={growth}><th>{number(growth)}%</th>{(sensitivity.intrinsicValues?.[rowIndex] || []).map((cell, columnIndex) => <td key={`${growth}-${columnIndex}`}>{money(cell)}</td>)}</tr>)}
  </tbody></table></div>;
}

function ReverseDcfTrack({ method, result, legacy = false }) {
  const availability = methodAvailability(method);
  const reverseDcf = method?.reverseDcf ?? result?.reverseDcf;
  const sensitivity = method?.sensitivity ?? result?.sensitivity;
  const model = method?.method || (legacy ? 'Selected model' : '--');
  const discountLabel = method?.discountRateType === 'WACC' ? 'Implied WACC'
    : method?.discountRateType === 'COST_OF_EQUITY' ? 'Implied Cost of Equity' : 'Implied Discount Rate';
  return <section className="fundamental-panel sensitivity-method-panel">
    <div className="section-title-row"><div><h3>{model} Reverse DCF</h3><p className="muted">{method?.cashFlowDefinition || 'Compatibility valuation track'} · {method?.discountRateType || 'discount rate'}</p></div><strong>{availability.status}</strong></div>
    {!availability.available ? <p className="error-text">{availability.reasons.join(' ') || 'This valuation method is unavailable.'}</p> : <>
      <div className="valuation-overview-grid compact">
        <article className="dcf-result-card"><span>Implied Initial Growth</span><strong>{reverseDcf?.impliedInitialGrowthRatePct == null ? '--' : `${number(reverseDcf.impliedInitialGrowthRatePct)}%`}</strong></article>
        <article className="dcf-result-card"><span>{discountLabel}</span><strong>{reverseDcf?.impliedDiscountRatePct == null ? '--' : `${number(reverseDcf.impliedDiscountRatePct)}%`}</strong></article>
      </div>
      <SensitivityMatrix sensitivity={sensitivity} />
    </>}
  </section>;
}

function SensitivityPanel({ evaluation, valuationMethods, scenario, symbol, scenarioType, selectedMethod }) {
  const [result, setResult] = useState(evaluation);
  useEffect(() => { setResult(evaluation); }, [evaluation]);
  useEffect(() => {
    if (result || !scenario?.assumptions || !symbol) return;
    evaluateValuation(symbol, scenarioType, scenario.assumptions).then(setResult).catch(() => undefined);
  }, [result, scenario, symbol, scenarioType]);
  if (!result) return <p className="muted">Preparing sensitivity matrix…</p>;
  const methods = result.valuationMethods ?? valuationMethods;
  const fcff = methods?.fcff ?? methods?.FCFF;
  const fcfe = methods?.fcfe ?? methods?.FCFE;
  if (!fcff && !fcfe) return <ReverseDcfTrack result={result} legacy />;
  const selected = selectedMethod === 'FCFE' ? fcfe : fcff;
  return <div className="sensitivity-method-stack">
    <ReverseDcfTrack method={selected} result={result} />
  </div>;
}
