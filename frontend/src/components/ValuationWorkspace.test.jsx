// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ValuationWorkspace from './ValuationWorkspace.jsx';
import { evaluateValuation, getForecastTemplate, getValuation, getWaccReferences, previewForecast, refreshWaccReferences, resetForecastSnapshot, resetValuationScenario, saveForecastSnapshot, saveValuationScenario } from '../api.js';

vi.mock('../api.js', () => ({
  evaluateValuation: vi.fn(),
  getForecastTemplate: vi.fn(),
  getValuation: vi.fn(),
  getWaccReferences: vi.fn(),
  refreshWaccReferences: vi.fn(),
  previewForecast: vi.fn(),
  saveForecastSnapshot: vi.fn(),
  resetForecastSnapshot: vi.fn(),
  saveValuationScenario: vi.fn(),
  resetValuationScenario: vi.fn()
}));

const assumptions = {
  baseCashFlow: 1000,
  initialGrowthRatePct: 8,
  discountRatePct: 10,
  terminalGrowthRatePct: 2.5,
  projectionYears: 10,
  marginOfSafetyPct: 20,
  taxRateOverridePct: null
};

function scenario(type, origin = 'DEFAULT') {
  return { scenarioType: type, origin, selectedModel: 'FCFF', assumptions, valid: true, intrinsicValuePerShare: 150, marginOfSafetyPrice: 120, terminalValueWeightPct: 60, warnings: [] };
}

const initialValue = {
  symbol: 'AAPL', engineVersion: 'valuation-java-1.0.0', priceDate: '2026-07-16', financialDate: '2026-03-31',
  applicability: { applicable: true, status: 'AVAILABLE', reasons: [] },
  dataQuality: { grade: 'High', reasons: [] },
  overview: { bearValue: 100, baseValue: 150, bullValue: 200, rangeLow: 100, rangeHigh: 200, currentPrice: 140 },
  scenarios: [scenario('BEAR'), scenario('BASE'), scenario('BULL')],
  cape: { status: 'AVAILABLE', realCape10y: 30, history: [], sampleCount: 20 }, diagnostics: [], missingFields: []
};

describe('ValuationWorkspace', () => {
  beforeEach(() => { vi.clearAllMocks(); getValuation.mockResolvedValue(initialValue); getWaccReferences.mockResolvedValue({ systemWaccPct: 9, references: [] }); refreshWaccReferences.mockResolvedValue({ systemWaccPct: 9, references: [] }); });
  afterEach(() => cleanup());

  it('marks edits unsaved and saves only after Save is clicked', async () => {
    evaluateValuation.mockResolvedValue({ scenario: { ...scenario('BASE'), intrinsicValuePerShare: 160 } });
    saveValuationScenario.mockResolvedValue({ ...scenario('BASE', 'SAVED'), intrinsicValuePerShare: 160, updatedAt: '2026-07-16T00:00:00Z' });
    render(<ValuationWorkspace symbol="AAPL" initialValue={initialValue} />);

    fireEvent.click(screen.getByRole('button', { name: 'Scenarios' }));
    fireEvent.change(screen.getByLabelText('Initial Growth %'), { target: { value: '9' } });
    expect(screen.getByText('Unsaved')).toBeInTheDocument();
    expect(saveValuationScenario).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saveValuationScenario).toHaveBeenCalledWith('AAPL', 'BASE', expect.objectContaining({ initialGrowthRatePct: 9 })));
  });

  it('applies an external WACC snapshot only through the FCFF reference panel', async () => {
    getWaccReferences.mockResolvedValue({ systemWaccPct: 9, references: [
      { provider: 'SYSTEM_ESTIMATE', ratePct: 9, status: 'AVAILABLE', selectable: true },
      { provider: 'DEEPVIEWS', ratePct: 9.3, status: 'AVAILABLE', selectable: true, sourceUrl: 'https://example.test/wacc', retrievedAt: '2026-08-08T00:00:00Z' }
    ] });
    saveValuationScenario.mockResolvedValue(scenario('BASE', 'SAVED'));
    render(<ValuationWorkspace symbol="AAPL" initialValue={initialValue} />);
    fireEvent.click(screen.getByRole('button', { name: 'Scenarios' }));
    await screen.findByText('DEEPVIEWS');
    fireEvent.click(screen.getByRole('button', { name: 'Apply to FCFF' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saveValuationScenario).toHaveBeenCalledWith('AAPL', 'BASE', expect.objectContaining({
      fcffWaccSelection: expect.objectContaining({ provider: 'DEEPVIEWS', ratePct: 9.3 })
    })));
  });

  it('restores a saved scenario to the data-driven default', async () => {
    const savedValue = { ...initialValue, scenarios: [scenario('BEAR'), scenario('BASE', 'SAVED'), scenario('BULL')] };
    resetValuationScenario.mockResolvedValue(scenario('BASE', 'DEFAULT'));
    render(<ValuationWorkspace symbol="AAPL" initialValue={savedValue} />);

    fireEvent.click(screen.getByRole('button', { name: 'Scenarios' }));
    fireEvent.click(screen.getByRole('button', { name: 'Restore data-driven default' }));
    await waitFor(() => expect(resetValuationScenario).toHaveBeenCalledWith('AAPL', 'BASE'));
  });

  it('shows a clear gap instead of a misleading valuation for an inapplicable instrument', () => {
    const unavailable = {
      ...initialValue,
      applicability: { applicable: false, status: 'UNAVAILABLE', reasons: ['Instrument is not common stock.'] },
      dataQuality: { grade: 'Unavailable', reasons: ['Instrument is not common stock.'] },
      overview: { currentPrice: 100 },
      scenarios: [
        { ...scenario('BEAR'), valid: false, intrinsicValuePerShare: null },
        { ...scenario('BASE'), valid: false, intrinsicValuePerShare: null },
        { ...scenario('BULL'), valid: false, intrinsicValuePerShare: null }
      ]
    };
    render(<ValuationWorkspace symbol="QQQ" initialValue={unavailable} />);
    expect(screen.getByText('Valuation unavailable')).toBeInTheDocument();
    expect(screen.getByText('Instrument is not common stock.')).toBeInTheDocument();
    expect(screen.getAllByText('--').length).toBeGreaterThan(0);
  });

  it('surfaces evaluate request failures after the debounce', async () => {
    evaluateValuation.mockRejectedValue(new Error('Valuation service unavailable'));
    render(<ValuationWorkspace symbol="AAPL" initialValue={initialValue} />);
    fireEvent.click(screen.getByRole('button', { name: 'Scenarios' }));
    fireEvent.change(screen.getByLabelText('Initial Growth %'), { target: { value: '9' } });
    await waitFor(() => expect(screen.getByText('Valuation service unavailable')).toBeInTheDocument(), { timeout: 1200 });
  });

  it('shows company-specific growth evidence and supports a custom annual path', () => {
    const autoAssumptions = { ...assumptions, baseCashFlow: null, initialGrowthRatePct: null, discountRatePct: null,
      baseCashFlowMode: 'AUTO', growthMode: 'AUTO_BLEND', discountRateMode: 'AUTO' };
    const autoScenario = { ...scenario('BASE'), assumptions: autoAssumptions,
      resolvedAssumptions: { ...assumptions, baseCashFlowMode: 'AUTO', growthMode: 'AUTO_BLEND', discountRateMode: 'AUTO' } };
    const value = { ...initialValue,
      scenarios: [scenario('BEAR'), autoScenario, scenario('BULL')],
      growthReferences: [{ type: 'AUTO_BLEND', valuePct: 9.5, sourceName: '50% historical + 50% fresh consensus', confidence: 'High', sampleCount: 40, status: 'AVAILABLE' }] };
    render(<ValuationWorkspace symbol="AAPL" initialValue={value} />);
    fireEvent.click(screen.getByRole('button', { name: 'Scenarios' }));
    expect(screen.getByText('9.50%')).toBeInTheDocument();
    expect(screen.getByLabelText('Initial Growth %')).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Growth Mode'), { target: { value: 'CUSTOM_PATH' } });
    expect(screen.getByLabelText('Year 1 Growth %')).toBeInTheDocument();
    expect(screen.getByLabelText('Year 10 Growth %')).toBeInTheDocument();
  });

  it('shows FCFF and FCFE ranges, reconciliation, policies, and readiness from an additive payload', () => {
    const dualValue = {
      ...initialValue,
      calculationMode: 'DUAL_TRACK',
      valuationMethods: {
        fcff: {
          available: true,
          status: 'AVAILABLE',
          forecastMode: 'LEGACY_CASH_FLOW_FADE',
          debtPolicy: 'NET_DEBT_BRIDGE',
          scenarios: {
            BEAR: { intrinsicValuePerShare: 120 },
            BASE: { intrinsicValuePerShare: 150 },
            BULL: { intrinsicValuePerShare: 190 }
          }
        },
        fcfe: {
          availability: 'READY_WITH_CAVEATS',
          available: false,
          forecastMode: 'LEGACY_CASH_FLOW_FADE',
          debtPolicy: 'REPORTED_NET_BORROWING',
          missingInputs: ['Commercial paper history is estimated before 2024.'],
          scenarios: [
            { scenarioType: 'BEAR', valuePerShare: 115 },
            { scenarioType: 'BASE', valuePerShare: 145 },
            { scenarioType: 'BULL', valuePerShare: 180 }
          ]
        }
      },
      crossModelReconciliation: {
        readiness: 'READY_WITH_CAVEATS',
        baseDifferencePct: -3.3333,
        warnings: ['FCFE remains a cross-check because financing policy is active.'],
        scenarios: [
          { scenarioType: 'BEAR', primaryMethod: 'FCFF', primaryIntrinsicValuePerShare: 120, crossCheckMethod: 'FCFE', crossCheckIntrinsicValuePerShare: 115, differencePct: -4.1667 },
          { scenarioType: 'BASE', primaryMethod: 'FCFF', primaryIntrinsicValuePerShare: 150, crossCheckMethod: 'FCFE', crossCheckIntrinsicValuePerShare: 145, differencePct: -3.3333 },
          { scenarioType: 'BULL', primaryMethod: 'FCFF', primaryIntrinsicValuePerShare: 190, crossCheckMethod: 'FCFE', crossCheckIntrinsicValuePerShare: 180, differencePct: -5.2632 }
        ]
      }
    };

    render(<ValuationWorkspace symbol="AAPL" initialValue={dualValue} />);

    expect(screen.getByText('Mode DUAL TRACK')).toBeInTheDocument();
    expect(screen.getAllByText('READY WITH CAVEATS').length).toBeGreaterThan(0);
    expect(screen.getByText('FCFE remains a cross-check because financing policy is active.')).toBeInTheDocument();
    const comparison = screen.getByRole('region', { name: 'FCFF and FCFE valuation comparison' });
    expect(within(comparison).getByText('FCFF / FCFE comparison')).toBeInTheDocument();
    expect(within(comparison).getByText('$120.00 – $190.00')).toBeInTheDocument();
    expect(within(comparison).getByText('$115.00 – $180.00')).toBeInTheDocument();
    expect(within(comparison).getByText('Commercial paper history is estimated before 2024.')).toBeInTheDocument();
    expect(within(comparison).getByText('LEGACY CASH FLOW FADE')).toBeInTheDocument();
    expect(within(comparison).getByText('FCFF: NET DEBT BRIDGE · FCFE: REPORTED NET BORROWING')).toBeInTheDocument();
    const baseRow = within(comparison).getByRole('row', { name: /Base/ });
    expect(within(baseRow).getByText('$150.00')).toBeInTheDocument();
    expect(within(baseRow).getByText('$145.00')).toBeInTheDocument();
    expect(within(baseRow).getByText('-3.33%')).toBeInTheDocument();
    expect(within(comparison).getByText('Enterprise perspective: shareholders + creditors')).toBeInTheDocument();
    fireEvent.click(within(comparison).getByRole('button', { name: 'View FCFE result' }));
    expect(within(comparison).getByText('Equity perspective: shareholders')).toBeInTheDocument();
    expect(within(comparison).getByRole('button', { name: 'View FCFE result' })).toHaveAttribute('aria-pressed', 'true');
    const summary = screen.getByRole('region', { name: 'Valuation summary' });
    expect(within(summary).getByText('Viewing model')).toBeInTheDocument();
    expect(within(summary).getByText('FCFE')).toBeInTheDocument();
    expect(screen.getAllByText('$145.00').length).toBeGreaterThan(1);
  });

  it('keeps the legacy selected-model overview when dual-method fields are absent', () => {
    render(<ValuationWorkspace symbol="AAPL" initialValue={initialValue} />);

    expect(screen.getByText('Selected model')).toBeInTheDocument();
    expect(screen.getByText('FCFF')).toBeInTheDocument();
    expect(screen.getByText('$100.00')).toBeInTheDocument();
    expect(screen.getByText('$150.00')).toBeInTheDocument();
    expect(screen.getByText('$200.00')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'FCFF and FCFE valuation comparison' })).not.toBeInTheDocument();
  });

  it('shows the selected model reverse DCF and sensitivity matrix', async () => {
    const matrix = { discountRatesPct: [8, 9], terminalGrowthRatesPct: [2, 3], intrinsicValues: [[120, 110], [140, 130]] };
    const dualValue = {
      ...initialValue,
      valuationMethods: {
        fcff: { method: 'FCFF', available: true, status: 'AVAILABLE', cashFlowDefinition: 'Operating FCFF', discountRateType: 'WACC', reverseDcf: { impliedInitialGrowthRatePct: 12.5, impliedDiscountRatePct: 8.8 }, sensitivity: matrix },
        fcfe: { method: 'FCFE', available: true, status: 'AVAILABLE', cashFlowDefinition: 'Equity FCFE', discountRateType: 'COST_OF_EQUITY', reverseDcf: { impliedInitialGrowthRatePct: 9.5, impliedDiscountRatePct: 10.2 }, sensitivity: matrix }
      }
    };
    evaluateValuation.mockResolvedValue({ valuationMethods: dualValue.valuationMethods });
    render(<ValuationWorkspace symbol="AAPL" initialValue={dualValue} />);

    fireEvent.click(screen.getByRole('button', { name: 'Sensitivity' }));
    await waitFor(() => expect(screen.getByText('FCFF Reverse DCF')).toBeInTheDocument());
    expect(screen.getByText('Implied WACC')).toBeInTheDocument();
    expect(screen.getByText('12.50%')).toBeInTheDocument();
    expect(screen.queryByText('FCFE Reverse DCF')).not.toBeInTheDocument();
    expect(screen.getAllByText('Terminal g / discount rate')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Overview' }));
    fireEvent.click(screen.getByRole('button', { name: 'View FCFE result' }));
    fireEvent.click(screen.getByRole('button', { name: 'Sensitivity' }));
    await waitFor(() => expect(screen.getByText('FCFE Reverse DCF')).toBeInTheDocument());
    expect(screen.getByText('Implied Cost of Equity')).toBeInTheDocument();
    expect(screen.getByText('9.50%')).toBeInTheDocument();
  });

  it('loads an archetype template, supports a forecast preview, and keeps NWC caveats visible', async () => {
    const drivers = Array.from({ length: 5 }, () => ({ revenueGrowthRate: 0.08, ebitMargin: 0.3, taxRate: 0.21, depreciationAndAmortizationRate: 0.03, capexRate: 0.03, changeInNetWorkingCapitalRate: 0 }));
    getForecastTemplate.mockResolvedValue({ eligibility: 'AVAILABLE', suggestedArchetype: 'MATURE_TECH_PLATFORM', confidence: 'HIGH', reasons: ['Asset-light economics.'], nwcStatus: 'ASSUMPTION_REQUIRED', sharesPolicy: 'CURRENT_DILUTED_SHARES_NO_BUYBACK', sharesOutstanding: 100, templateVersion: 'forecast-archetype-3.0.0', templates: { MATURE_TECH_PLATFORM: { description: 'Mature platform', revenueDriverLabel: 'Revenue growth', debtFinancingPolicy: { type: 'TARGET_DEBT_FINANCING_RATIO', targetDebtFinancingRatio: 0.15 }, warnings: ['ΔNWC must be reviewed.'], scenarios: { BEAR: { explicitOperatingDrivers: drivers }, BASE: { explicitOperatingDrivers: drivers }, BULL: { explicitOperatingDrivers: drivers } } } } });
    previewForecast.mockResolvedValue({ readiness: 'READY_WITH_CAVEATS', missingInputs: ['changeInNetWorkingCapital is an explicit analyst assumption.'], scenarios: { BASE: { fcff: { enterpriseValue: 1000, equityValue: 900 }, fcfe: { equityValue: 850 } } } });
    render(<ValuationWorkspace symbol="AAPL" initialValue={initialValue} />);
    fireEvent.click(screen.getByRole('button', { name: 'Forecast' }));
    await waitFor(() => expect(screen.getByText('Suggested valuation mode: MATURE TECH PLATFORM')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Preview FCFF / FCFE' }));
    await waitFor(() => expect(previewForecast).toHaveBeenCalledWith('AAPL', expect.objectContaining({ archetype: 'MATURE_TECH_PLATFORM' })));
    expect(screen.getByText('Explicit forecast preview')).toBeInTheDocument();
    expect(screen.getByText('changeInNetWorkingCapital is an explicit analyst assumption.')).toBeInTheDocument();
  });
});
