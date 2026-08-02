import { parseQuarter } from './fundamentalTimeline.js';

function toNullableNumber(value) {
  if (value == null) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function ratioPct(numerator, denominator) {
  if (numerator == null || denominator == null || denominator <= 0) return null;
  return (numerator / denominator) * 100;
}

function sumLastNonNull(rows, endIndex, key, count = 4) {
  let sum = 0;
  let found = 0;
  for (let i = endIndex; i >= 0 && found < count; i -= 1) {
    const value = rows[i]?.[key];
    if (value == null) continue;
    sum += value;
    found += 1;
  }
  return found === count ? sum : null;
}

function normalizeFundamentalRows(rows) {
  return [...(rows || [])]
    .filter((row) => row && !row.forecast && row.asOfDate)
    .map((row) => {
      const parsed = parseQuarter(row.asOfDate);
      if (!parsed) return null;
      const fcf = toNullableNumber(row.fcf);
      const adjustedFcf = toNullableNumber(row.adjustedFcf) ?? fcf;
      return {
        date: row.asOfDate,
        missing: Boolean(row.missing),
        fiscalYear: row.fiscalYear ?? parsed.year,
        fiscalQuarter: row.fiscalQuarter ?? parsed.quarter,
        quarterIndex: parsed.index,
        label: `${String(row.fiscalYear ?? parsed.year).slice(2)} Q${row.fiscalQuarter ?? parsed.quarter}`,
        operatingCashFlow: toNullableNumber(row.cashFlow),
        fcf,
        capex: toNullableNumber(row.capex),
        adjustedFcf,
        revenue: toNullableNumber(row.revenue),
        netIncome: toNullableNumber(row.netIncome),
        totalDebt: toNullableNumber(row.totalDebt),
        cashAndEquivalents: toNullableNumber(row.cashAndEquivalents)
      };
    })
    .filter(Boolean)
    .sort((a, b) => a.quarterIndex - b.quarterIndex || new Date(a.date) - new Date(b.date));
}

export function calculateFcfGrowth(currentFcf, previousFcf) {
  if (currentFcf == null || previousFcf == null || previousFcf === 0) return null;
  return ((currentFcf - previousFcf) / Math.abs(previousFcf)) * 100;
}

export function calculateFcfAcceleration(currentGrowth, previousGrowth) {
  if (currentGrowth == null || previousGrowth == null) return null;
  return currentGrowth - previousGrowth;
}

export function calculateCagr(startValue, endValue, years) {
  if (startValue == null || endValue == null || startValue <= 0 || endValue <= 0 || years <= 0) return null;
  return ((endValue / startValue) ** (1 / years) - 1) * 100;
}

export function calculateFcfStability(values) {
  const usable = (values || []).filter((value) => value != null);
  if (usable.length < 4) return null;
  const mean = usable.reduce((sum, value) => sum + value, 0) / usable.length;
  if (mean === 0) return null;
  const variance = usable.reduce((sum, value) => sum + (value - mean) ** 2, 0) / usable.length;
  return (Math.sqrt(variance) / Math.abs(mean)) * 100;
}

export function classifyCashFlowTrend(current, previous, tolerance = 0.1) {
  if (current == null || previous == null) return 'insufficient-data';
  const delta = current - previous;
  if (delta > tolerance) return 'expansion';
  if (delta < -tolerance) return 'compression';
  return 'flat';
}

function annualizeCashFlow(rows) {
  const byYear = new Map();
  rows.forEach((row) => {
    const value = row.adjustedFcf ?? row.fcf;
    if (value == null) return;
    const current = byYear.get(row.fiscalYear) || { year: row.fiscalYear, adjustedFcf: 0, quarters: 0 };
    current.adjustedFcf += value;
    current.quarters += 1;
    byYear.set(row.fiscalYear, current);
  });
  return [...byYear.values()]
    .filter((row) => row.quarters >= 3)
    .sort((a, b) => a.year - b.year);
}

function calculateRollingMetrics(rows) {
  const rolling = rows.map((row, index) => {
    if (row.missing) {
      return {
        ...row,
        ttmAdjustedFcf: null,
        ttmFcf: null,
        ttmOperatingCashFlow: null,
        ttmCapex: null,
        fcfMargin: null,
        fcfConversion: null,
        ocfConversion: null,
        capexIntensity: null
      };
    }
    const ttmAdjustedFcf = sumLastNonNull(rows, index, 'adjustedFcf');
    const ttmFcf = sumLastNonNull(rows, index, 'fcf');
    const ttmOperatingCashFlow = sumLastNonNull(rows, index, 'operatingCashFlow');
    const ttmCapex = sumLastNonNull(rows, index, 'capex');
    const ttmRevenue = sumLastNonNull(rows, index, 'revenue');
    const ttmNetIncome = sumLastNonNull(rows, index, 'netIncome');
    return {
      ...row,
      ttmAdjustedFcf,
      ttmFcf,
      ttmOperatingCashFlow,
      ttmCapex,
      fcfMargin: ratioPct(ttmAdjustedFcf, ttmRevenue),
      fcfConversion: ratioPct(ttmAdjustedFcf, ttmNetIncome),
      ocfConversion: ratioPct(ttmOperatingCashFlow, ttmNetIncome),
      capexIntensity: ratioPct(ttmCapex, ttmRevenue)
    };
  });
  for (let i = 0; i < rolling.length; i += 1) {
    const previous = rolling[i - 1] || null;
    rolling[i].ttmAdjustedFcfGrowth = calculateFcfGrowth(rolling[i].ttmAdjustedFcf, previous?.ttmAdjustedFcf ?? null);
    rolling[i].ttmAdjustedFcfAcceleration = calculateFcfAcceleration(
      rolling[i].ttmAdjustedFcfGrowth,
      previous?.ttmAdjustedFcfGrowth ?? null
    );
  }
  return rolling;
}

function calculateValuationMetrics(latest, latestPrice, sharesOutstanding) {
  const price = toNullableNumber(latestPrice);
  const shares = toNullableNumber(sharesOutstanding);
  const ttmFcf = latest?.ttmAdjustedFcf ?? latest?.ttmFcf ?? null;
  const marketCap = price != null && shares != null ? price * shares : null;
  const netDebt = latest?.totalDebt != null && latest?.cashAndEquivalents != null
    ? latest.totalDebt - latest.cashAndEquivalents
    : null;
  const enterpriseValue = marketCap != null && netDebt != null ? marketCap + netDebt : null;
  return {
    marketCap,
    enterpriseValue,
    fcfYield: ratioPct(ttmFcf, marketCap),
    priceToFcf: marketCap != null && ttmFcf != null && ttmFcf > 0 ? marketCap / ttmFcf : null,
    evToFcf: enterpriseValue != null && ttmFcf != null && ttmFcf > 0 ? enterpriseValue / ttmFcf : null
  };
}

export function calculateCashFlowAnalysis(rows, options = {}) {
  const normalized = normalizeFundamentalRows(rows);
  const quarterly = normalized.map((row, index) => {
    const previous = normalized[index - 1] || null;
    const growth = calculateFcfGrowth(row.adjustedFcf ?? row.fcf, previous?.adjustedFcf ?? previous?.fcf ?? null);
    return { ...row, growth };
  });
  for (let i = 0; i < quarterly.length; i += 1) {
    quarterly[i].acceleration = calculateFcfAcceleration(quarterly[i].growth, quarterly[i - 1]?.growth ?? null);
  }

  const rolling = calculateRollingMetrics(quarterly);
  const annual = annualizeCashFlow(rolling);
  const latest = [...rolling].reverse().find((row) => row.adjustedFcf != null || row.fcf != null) || null;
  const latestIndex = latest ? rolling.findIndex((row) => row.date === latest.date) : -1;
  const previousLatest = latestIndex > 0 ? rolling[latestIndex - 1] : null;
  const latestAnnual = annual[annual.length - 1] || null;
  const previousAnnual = annual[annual.length - 2] || null;
  const annualByYear = new Map(annual.map((row) => [row.year, row.adjustedFcf]));
  const cagr3 = latestAnnual ? calculateCagr(annualByYear.get(latestAnnual.year - 3), latestAnnual.adjustedFcf, 3) : null;
  const cagr5 = latestAnnual ? calculateCagr(annualByYear.get(latestAnnual.year - 5), latestAnnual.adjustedFcf, 5) : null;
  const stability = calculateFcfStability(rolling.slice(-8).map((row) => row.adjustedFcf ?? row.fcf));
  const previousStability = latestIndex > 0
    ? calculateFcfStability(rolling.slice(Math.max(0, latestIndex - 8), latestIndex).map((row) => row.adjustedFcf ?? row.fcf))
    : null;
  const valuation = calculateValuationMetrics(latest, options.latestPrice, options.sharesOutstanding);
  const previousValuation = calculateValuationMetrics(previousLatest, options.latestPrice, options.sharesOutstanding);
  const metricTrends = {
    fcfMargin: classifyCashFlowTrend(latest?.fcfMargin, previousLatest?.fcfMargin),
    fcfConversion: classifyCashFlowTrend(latest?.fcfConversion, previousLatest?.fcfConversion),
    ocfConversion: classifyCashFlowTrend(latest?.ocfConversion, previousLatest?.ocfConversion),
    capexIntensity: classifyCashFlowTrend(latest?.capexIntensity, previousLatest?.capexIntensity),
    stability: classifyCashFlowTrend(stability, previousStability),
    fcfYield: classifyCashFlowTrend(valuation.fcfYield, previousValuation.fcfYield),
    priceToFcf: classifyCashFlowTrend(valuation.priceToFcf, previousValuation.priceToFcf),
    evToFcf: classifyCashFlowTrend(valuation.evToFcf, previousValuation.evToFcf),
    cagr3: classifyCashFlowTrend(cagr3, previousAnnual ? calculateCagr(annualByYear.get(previousAnnual.year - 3), previousAnnual.adjustedFcf, 3) : null),
    cagr5: classifyCashFlowTrend(cagr5, previousAnnual ? calculateCagr(annualByYear.get(previousAnnual.year - 5), previousAnnual.adjustedFcf, 5) : null)
  };
  const ttm = rolling
    .map((row) => ({
      date: row.date,
      label: row.label,
      value: row.ttmAdjustedFcf,
      growth: row.ttmAdjustedFcfGrowth,
      acceleration: row.ttmAdjustedFcfAcceleration
    }));
  const byDate = new Map(rolling.map((row) => [row.date, row]));

  return {
    quarterly: rolling,
    ttm,
    annual,
    latest,
    cagr3,
    cagr5,
    stability,
    valuation,
    metricTrends,
    byDate
  };
}

export function formatCashFlowPercent(value) {
  return value == null ? 'N/A' : `${value.toFixed(1)}%`;
}

export function formatCashFlowMultiple(value) {
  return value == null ? 'N/A' : `${value.toFixed(1)}x`;
}

export function formatCashFlowPercentagePoints(value) {
  if (value == null) return 'N/A';
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)} pp`;
}
