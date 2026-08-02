import { parseQuarter } from './fundamentalTimeline.js';

function toNullableNumber(value) {
  if (value == null) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export function safeDivide(numerator, denominator, options = {}) {
  const n = toNullableNumber(numerator);
  const d = toNullableNumber(denominator);
  const minAbsDenominator = options.minAbsDenominator ?? 1e-9;
  if (n == null || d == null || Math.abs(d) <= minAbsDenominator) return null;
  if (options.positiveDenominator && d <= minAbsDenominator) return null;
  const value = n / d;
  return Number.isFinite(value) ? value : null;
}

export function safePercent(numerator, denominator, options = {}) {
  const ratio = safeDivide(numerator, denominator, options);
  return ratio == null ? null : ratio * 100;
}

export function rollingAverage(values, windowSize, minObservations = windowSize) {
  return values.map((_, index) => {
    const window = values.slice(Math.max(0, index - windowSize + 1), index + 1).filter((value) => value != null);
    if (window.length < minObservations) return null;
    return window.reduce((sum, value) => sum + value, 0) / window.length;
  });
}

export function calculateRoe(netIncome, shareholdersEquity) {
  return safePercent(netIncome, shareholdersEquity, { positiveDenominator: true });
}

export function calculateNopat(operatingIncome, taxProvision, pretaxIncome, netIncome = null) {
  const operating = toNullableNumber(operatingIncome);
  const tax = toNullableNumber(taxProvision);
  const reportedPretax = toNullableNumber(pretaxIncome);
  const net = toNullableNumber(netIncome);
  if (operating == null) return null;
  if (operating <= 0) return 0;
  const pretax = reportedPretax ?? (net != null && tax != null ? net + tax : null);
  if (tax == null || pretax == null) return null;
  if (pretax <= 0) return operating;
  const rawTaxRate = tax / pretax;
  if (!Number.isFinite(rawTaxRate) || rawTaxRate > 1) return null;
  const taxRate = Math.max(rawTaxRate, 0);
  return operating * (1 - taxRate);
}

export function calculateRoic(nopat, investedCapital) {
  return safePercent(nopat, investedCapital, { positiveDenominator: true });
}

export function isAbnormalInvestedCapitalDelta(delta, baseValue) {
  const d = toNullableNumber(delta);
  if (d == null || d <= 0) return true;
  const base = Math.abs(toNullableNumber(baseValue) ?? 0);
  if (base > 0 && d / base < 0.01) return true;
  return false;
}

export function calculateIncrementalRoic(currentNopat, previousNopat, currentInvestedCapital, previousInvestedCapital) {
  const current = toNullableNumber(currentNopat);
  const previous = toNullableNumber(previousNopat);
  const currentCapital = toNullableNumber(currentInvestedCapital);
  const previousCapital = toNullableNumber(previousInvestedCapital);
  if (current == null || previous == null || currentCapital == null || previousCapital == null) {
    return { value: null, status: 'unavailable', reason: 'Missing NOPAT or invested capital.' };
  }
  const investedCapitalDelta = currentCapital - previousCapital;
  if (isAbnormalInvestedCapitalDelta(investedCapitalDelta, previousCapital)) {
    return {
      value: null,
      status: 'abnormal',
      reason: 'Incremental ROIC can be unstable when change in invested capital is very small, negative, or affected by major balance-sheet adjustments.'
    };
  }
  return {
    value: ((current - previous) / investedCapitalDelta) * 100,
    status: 'normal',
    reason: null
  };
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

function latestNonNullAtOrBefore(rows, endIndex, key) {
  for (let i = endIndex; i >= 0; i -= 1) {
    const value = rows[i]?.[key];
    if (value != null) return value;
  }
  return null;
}

function normalizeFundamentalRows(rows) {
  return [...(rows || [])]
    .filter((row) => row && !row.forecast && row.asOfDate)
    .map((row) => {
      const parsed = parseQuarter(row.asOfDate);
      if (!parsed) return null;
      const equity = toNullableNumber(row.stockholdersEquity);
      const totalDebt = toNullableNumber(row.totalDebt);
      const cash = toNullableNumber(row.cashAndEquivalents);
      const investedCapital = toNullableNumber(row.investedCapital)
        ?? (totalDebt != null && equity != null ? totalDebt + equity - (cash ?? 0) : null);
      const capitalProxy = totalDebt != null && equity != null ? totalDebt + equity : null;
      return {
        date: row.asOfDate,
        missing: Boolean(row.missing),
        fiscalYear: row.fiscalYear ?? parsed.year,
        fiscalQuarter: row.fiscalQuarter ?? parsed.quarter,
        quarterIndex: parsed.index,
        label: `${String(row.fiscalYear ?? parsed.year).slice(2)} Q${row.fiscalQuarter ?? parsed.quarter}`,
        roe: toNullableNumber(row.roe),
        roic: toNullableNumber(row.roic),
        revenue: toNullableNumber(row.revenue),
        netIncome: toNullableNumber(row.netIncome),
        operatingIncome: toNullableNumber(row.operatingIncome),
        ebitda: toNullableNumber(row.ebitda),
        interestExpense: toNullableNumber(row.interestExpense),
        taxProvision: toNullableNumber(row.taxProvision),
        pretaxIncome: toNullableNumber(row.pretaxIncome),
        stockholdersEquity: equity,
        totalDebt,
        cashAndEquivalents: cash,
        investedCapital,
        capitalProxy,
        adjustedFcf: toNullableNumber(row.adjustedFcf) ?? toNullableNumber(row.fcf)
      };
    })
    .filter(Boolean)
    .sort((a, b) => a.quarterIndex - b.quarterIndex || new Date(a.date) - new Date(b.date));
}

function calculateVolatility(values) {
  const usable = values.filter((value) => value != null);
  if (usable.length < 4) return null;
  const mean = usable.reduce((sum, value) => sum + value, 0) / usable.length;
  const variance = usable.reduce((sum, value) => sum + (value - mean) ** 2, 0) / usable.length;
  return Math.sqrt(variance);
}

export function generateCapitalEfficiencyInterpretation(analysis) {
  const latest = analysis.latest;
  if (!latest) return 'Capital efficiency analysis is unavailable because there is not enough data.';
  const roe = latest.roe;
  const roic = latest.roic;
  const avgRoic = latest.avgRoic5y;
  const incremental = latest.incrementalRoic3y;
  const lines = [];

  if (roe != null && roic != null && roe >= 15 && roic >= 12) {
    lines.push('Strong shareholder and operating capital returns.');
  } else if (roe != null && roic != null && roe >= 15 && roic < 8) {
    lines.push('ROE may be supported by leverage or accounting effects rather than strong operating returns.');
  } else if (roic != null) {
    lines.push(`Latest ROIC is ${formatCapitalPercent(roic)}, showing current operating capital efficiency.`);
  }

  if (roic != null && avgRoic != null) {
    lines.push(roic > avgRoic
      ? 'ROIC is above its 5Y average, suggesting improving operating capital efficiency.'
      : 'ROIC is below its 5Y average, suggesting weaker recent capital efficiency.');
  }

  if (incremental?.value == null) {
    lines.push('3Y Incremental ROIC is unavailable or unstable due to insufficient change in invested capital.');
  } else if (roic != null && incremental.value < roic) {
    lines.push('3Y Incremental ROIC is below current ROIC, so recent capital may be earning lower returns.');
  } else if (roic != null) {
    lines.push('3Y Incremental ROIC is above current ROIC, indicating attractive returns on recently deployed capital.');
  }

  return lines.length ? lines.join('\n') : 'Capital efficiency signals are mixed or incomplete.';
}

export function calculateCapitalEfficiencyAnalysis(rows) {
  const normalized = normalizeFundamentalRows(rows);
  const derivedBase = normalized.map((row, index) => {
    if (row.missing) {
      return {
        ...row,
        roe: null,
        roic: null,
        ttmRevenue: null,
        ttmNetIncome: null,
        ttmOperatingIncome: null,
        ttmEbitda: null,
        ttmInterestExpense: null,
        ttmTaxProvision: null,
        ttmPretaxIncome: null,
        ttmAdjustedFcf: null,
        nopat: null,
        investedCapital: null
      };
    }
    const ttmRevenue = sumLastNonNull(normalized, index, 'revenue');
    const ttmNetIncome = sumLastNonNull(normalized, index, 'netIncome');
    const ttmOperatingIncome = sumLastNonNull(normalized, index, 'operatingIncome');
    const ttmEbitda = sumLastNonNull(normalized, index, 'ebitda');
    const ttmInterestExpense = sumLastNonNull(normalized, index, 'interestExpense');
    const ttmTaxProvision = sumLastNonNull(normalized, index, 'taxProvision');
    const ttmPretaxIncome = sumLastNonNull(normalized, index, 'pretaxIncome');
    const ttmAdjustedFcf = sumLastNonNull(normalized, index, 'adjustedFcf');
    const nopat = calculateNopat(ttmOperatingIncome, ttmTaxProvision, ttmPretaxIncome, ttmNetIncome);
    const investedCapital = latestNonNullAtOrBefore(normalized, index, 'investedCapital');
    const roe = row.roe ?? calculateRoe(ttmNetIncome, row.stockholdersEquity);
    const roic = row.roic ?? calculateRoic(nopat, investedCapital);

    return {
      ...row,
      roe,
      roic,
      ttmRevenue,
      ttmNetIncome,
      ttmOperatingIncome,
      ttmEbitda,
      ttmInterestExpense,
      ttmTaxProvision,
      ttmPretaxIncome,
      ttmAdjustedFcf,
      nopat,
      investedCapital
    };
  });
  const roeValues = derivedBase.map((row) => row.roe);
  const roicValues = derivedBase.map((row) => row.roic);
  const avgRoe5yValues = rollingAverage(roeValues, 20);
  const avgRoic5yValues = rollingAverage(roicValues, 20, 16);
  const avgRoe10yValues = rollingAverage(roeValues, 40);
  const avgRoic10yValues = rollingAverage(roicValues, 40, 32);

  const series = derivedBase.map((row, index) => {
    const prior = index > 0 ? normalized[index - 1] : null;
    const priorBase = index > 0 ? derivedBase[index - 1] : null;
    const prior3yBase = index >= 12 ? derivedBase[index - 12] : null;
    const incrementalRoic = calculateIncrementalRoic(row.nopat, priorBase?.nopat, row.investedCapital, prior?.investedCapital);
    const incrementalRoic3y = calculateIncrementalRoic(row.nopat, prior3yBase?.nopat, row.investedCapital, prior3yBase?.investedCapital);
    const netProfitMargin = safePercent(row.ttmNetIncome, row.ttmRevenue, { positiveDenominator: true });
    const capitalTurnoverProxy = safeDivide(row.ttmRevenue, row.capitalProxy, { positiveDenominator: true });
    const equityMultiplierProxy = safeDivide(row.capitalProxy, row.stockholdersEquity, { positiveDenominator: true });
    const nopatMargin = safePercent(row.nopat, row.ttmRevenue, { positiveDenominator: true });
    const investedCapitalTurnover = safeDivide(row.ttmRevenue, row.investedCapital, { positiveDenominator: true });
    const netDebt = row.totalDebt != null || row.cashAndEquivalents != null
      ? (row.totalDebt ?? 0) - (row.cashAndEquivalents ?? 0)
      : null;
    const netDebtToEbitda = safeDivide(netDebt, row.ttmEbitda, { positiveDenominator: true });
    const interestCoverage = safeDivide(row.ttmOperatingIncome, row.ttmInterestExpense, { positiveDenominator: true });

    return {
      ...row,
      avgRoe5y: avgRoe5yValues[index],
      avgRoic5y: avgRoic5yValues[index],
      avgRoe10y: avgRoe10yValues[index],
      avgRoic10y: avgRoic10yValues[index],
      incrementalRoic,
      incrementalRoic3y,
      netProfitMargin,
      assetTurnover: null,
      assetTurnoverNote: 'Total assets are not available in the current data, so classic asset turnover is unavailable.',
      equityMultiplier: equityMultiplierProxy,
      equityMultiplierNote: 'Uses total debt plus equity divided by equity as a leverage proxy because total assets are not available.',
      nopatMargin,
      investedCapitalTurnover,
      debtToEquity: safeDivide(row.totalDebt, row.stockholdersEquity, { positiveDenominator: true }),
      netDebtToEbitda,
      interestCoverage,
      fcfConversion: safePercent(row.ttmAdjustedFcf, row.ttmNetIncome, { positiveDenominator: true })
    };
  });

  const latest = [...series].reverse().find((row) => row.roe != null || row.roic != null) || null;
  const diagnostics = latest ? {
    debtToEquity: latest.debtToEquity,
    netDebtToEbitda: latest.netDebtToEbitda,
    interestCoverage: latest.interestCoverage,
    fcfConversion: latest.fcfConversion,
    bookValuePerShareGrowth: null,
    roicWaccSpread: null,
    roeVolatility: calculateVolatility(series.slice(-20).map((row) => row.roe)),
    avgRoe10y: latest.avgRoe10y,
    avgRoic10y: latest.avgRoic10y
  } : {};
  const analysis = {
    series,
    latest,
    diagnostics
  };
  return {
    ...analysis,
    interpretation: generateCapitalEfficiencyInterpretation(analysis)
  };
}

export function formatCapitalPercent(value) {
  return value == null ? 'N/A' : `${value.toFixed(1)}%`;
}

export function formatCapitalMultiple(value) {
  return value == null ? 'N/A' : `${value.toFixed(2)}x`;
}
