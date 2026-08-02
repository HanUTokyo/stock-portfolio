// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ValuationWorkspace from './ValuationWorkspace.jsx';
import { evaluateValuation, resetValuationScenario, saveValuationScenario } from '../api.js';

vi.mock('../api.js', () => ({
  evaluateValuation: vi.fn(),
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
  beforeEach(() => vi.clearAllMocks());
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
});
