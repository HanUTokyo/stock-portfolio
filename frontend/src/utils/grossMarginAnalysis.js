import { parseQuarter } from './fundamentalTimeline.js';

function toNullableNumber(value) {
  if (value == null) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function quarterKey(year, quarter) {
  return `${year}-Q${quarter}`;
}

function ratioPct(numerator, denominator) {
  if (numerator == null || denominator == null || denominator <= 0) return null;
  return (numerator / denominator) * 100;
}

function normalizeFundamentalRows(rows) {
  return [...(rows || [])]
    .filter((row) => row && !row.forecast && row.asOfDate)
    .map((row) => {
      const parsed = parseQuarter(row.asOfDate);
      if (!parsed) return null;
      const revenue = toNullableNumber(row.revenue);
      const grossProfit = toNullableNumber(row.grossProfit);
      const grossMargin = toNullableNumber(row.grossMargin);
      return {
        ...row,
        date: row.asOfDate,
        missing: Boolean(row.missing),
        fiscalYear: row.fiscalYear ?? parsed.year,
        fiscalQuarter: row.fiscalQuarter ?? parsed.quarter,
        quarterIndex: parsed.index,
        quarterKey: quarterKey(row.fiscalYear ?? parsed.year, row.fiscalQuarter ?? parsed.quarter),
        revenue,
        grossProfit,
        grossMargin
      };
    })
    .filter(Boolean)
    .sort((a, b) => a.quarterIndex - b.quarterIndex || new Date(a.date) - new Date(b.date));
}

export function calculateQuarterlyGrossMargin(row) {
  const revenue = toNullableNumber(row?.revenue);
  const grossProfit = toNullableNumber(row?.grossProfit);
  const derived = ratioPct(grossProfit, revenue);
  if (derived != null) return derived;
  return toNullableNumber(row?.grossMargin);
}

export function calculateTTMGrossMargin(rows, endIndex = rows.length - 1) {
  const sorted = normalizeFundamentalRows(rows);
  if (endIndex < 0 || sorted.length < 4) return null;
  const window = sorted
    .slice(0, endIndex + 1)
    .filter((row) => row.revenue != null && row.grossProfit != null)
    .slice(-4);
  if (window.length < 4) return null;
  const revenue = window.reduce((sum, row) => sum + row.revenue, 0);
  const grossProfit = window.reduce((sum, row) => sum + row.grossProfit, 0);
  return ratioPct(grossProfit, revenue);
}

export function calculateAnnualGrossMargin(rows) {
  const byYear = new Map();
  normalizeFundamentalRows(rows).forEach((row) => {
    if (row.revenue == null || row.grossProfit == null) return;
    const current = byYear.get(row.fiscalYear) || { year: row.fiscalYear, revenue: 0, grossProfit: 0 };
    current.revenue += row.revenue;
    current.grossProfit += row.grossProfit;
    byYear.set(row.fiscalYear, current);
  });
  return [...byYear.values()]
    .sort((a, b) => a.year - b.year)
    .map((row) => ({ ...row, value: ratioPct(row.grossProfit, row.revenue) }));
}

export function calculateGrossMarginQoQChange(current, previous) {
  if (current == null || previous == null) return null;
  return current - previous;
}

export function calculateGrossMarginYoYChange(current, priorYearSameQuarter) {
  if (current == null || priorYearSameQuarter == null) return null;
  return current - priorYearSameQuarter;
}

export function calculateGrossMarginAcceleration(currentChange, previousChange) {
  if (currentChange == null || previousChange == null) return null;
  return currentChange - previousChange;
}

export function calculateIncrementalGrossMargin(current, comparison) {
  if (!current || !comparison) return null;
  if (current.grossProfit == null || comparison.grossProfit == null || current.revenue == null || comparison.revenue == null) return null;
  const revenueDelta = current.revenue - comparison.revenue;
  if (revenueDelta <= 0) return null;
  return ((current.grossProfit - comparison.grossProfit) / revenueDelta) * 100;
}

export function calculateGrossProfitGrowth(current, comparison) {
  if (!current || !comparison) return null;
  if (current.grossProfit == null || comparison.grossProfit == null || comparison.grossProfit === 0) return null;
  return ((current.grossProfit / comparison.grossProfit) - 1) * 100;
}

export function classifyGrossMarginTrend(value, tolerance = 0.1) {
  if (value == null) return 'insufficient-data';
  if (value > tolerance) return 'expansion';
  if (value < -tolerance) return 'compression';
  return 'flat';
}

function classifyAcceleration(value, tolerance = 0.1) {
  if (value == null) return 'insufficient-data';
  if (value > tolerance) return 'improving faster';
  if (value < -tolerance) return 'deteriorating faster';
  return 'stable pace';
}

function trendIcon(value, tolerance = 0.1) {
  if (value == null) return '⚪';
  if (value > tolerance) return '📈';
  if (value < -tolerance) return '📉';
  return '➖';
}

export function generateGrossMarginInterpretation(analysis) {
  const latest = analysis.latest;
  if (!latest) return '⚪ Gross margin analysis is unavailable because there is not enough fundamental data.';

  const ttmText = latest.ttmGrossMargin == null
    ? '⚪ Latest TTM gross margin is unavailable.'
    : `📊 Latest TTM gross margin: ${formatGrossMarginPercent(latest.ttmGrossMargin)}.`;
  const qoqTrend = classifyGrossMarginTrend(latest.qoqChange);
  const yoyTrend = classifyGrossMarginTrend(latest.yoyChange);
  const qoqText = `${trendIcon(latest.qoqChange)} QoQ movement: ${qoqTrend}${latest.qoqChange == null ? '' : ` (${formatPercentagePoints(latest.qoqChange)})`}.`;
  const yoyText = `${trendIcon(latest.yoyChange)} YoY movement: ${yoyTrend}${latest.yoyChange == null ? '' : ` (${formatPercentagePoints(latest.yoyChange)})`}.`;
  const qoqAccelText = `${trendIcon(latest.qoqAcceleration)} QoQ pace: ${classifyAcceleration(latest.qoqAcceleration)}${latest.qoqAcceleration == null ? '' : ` (${formatPercentagePoints(latest.qoqAcceleration)})`}.`;
  const yoyAccelText = `${trendIcon(latest.yoyAcceleration)} YoY pace: ${classifyAcceleration(latest.yoyAcceleration)}${latest.yoyAcceleration == null ? '' : ` (${formatPercentagePoints(latest.yoyAcceleration)})`}.`;

  let qualityText = '⚪ Gross profit growth data is insufficient.';
  if (latest.yoyChange != null && latest.grossProfitGrowthYoY != null) {
    if (latest.yoyChange < 0 && latest.grossProfitGrowthYoY > 0) {
      qualityText = '📉 Gross margin is down while gross profit dollars are still growing, which can indicate lower-margin expansion rather than broad deterioration.';
    } else if (latest.yoyChange > 0 && latest.grossProfitGrowthYoY > 0) {
      qualityText = '📈 Gross margin and gross profit dollars are both improving, a higher-quality combination.';
    } else if (latest.yoyChange < 0 && latest.grossProfitGrowthYoY < 0) {
      qualityText = '📉 Gross margin and gross profit dollars are both declining, indicating weaker profitability quality.';
    } else if (latest.yoyChange > 0 && latest.grossProfitGrowthYoY < 0) {
      qualityText = '📈 Gross margin is improving while gross profit dollars are declining, which can reflect mix improvement during revenue contraction.';
    }
  }

  return [
    ttmText,
    qoqText,
    yoyText,
    qoqAccelText,
    yoyAccelText,
    qualityText
  ].join('\n');
}

export function calculateGrossMarginAnalysis(rows) {
  const normalized = normalizeFundamentalRows(rows);
  const byQuarter = new Map(normalized.map((row) => [row.quarterKey, row]));

  const quarterly = normalized.map((row, index) => {
    const previous = normalized[index - 1] || null;
    const priorYear = byQuarter.get(quarterKey(row.fiscalYear - 1, row.fiscalQuarter)) || null;
    const value = calculateQuarterlyGrossMargin(row);
    const previousValue = previous ? calculateQuarterlyGrossMargin(previous) : null;
    const priorYearValue = priorYear ? calculateQuarterlyGrossMargin(priorYear) : null;
    const qoqChange = calculateGrossMarginQoQChange(value, previousValue);
    const yoyChange = calculateGrossMarginYoYChange(value, priorYearValue);
    return {
      ...row,
      value,
      label: `${String(row.fiscalYear).slice(2)} Q${row.fiscalQuarter}`,
      qoqChange,
      yoyChange,
      incrementalQoq: calculateIncrementalGrossMargin(row, previous),
      incrementalYoy: calculateIncrementalGrossMargin(row, priorYear),
      grossProfitGrowthQoq: calculateGrossProfitGrowth(row, previous),
      grossProfitGrowthYoy: calculateGrossProfitGrowth(row, priorYear)
    };
  });

  for (let i = 0; i < quarterly.length; i += 1) {
    quarterly[i].qoqAcceleration = calculateGrossMarginAcceleration(quarterly[i].qoqChange, quarterly[i - 1]?.qoqChange ?? null);
    quarterly[i].yoyAcceleration = calculateGrossMarginAcceleration(quarterly[i].yoyChange, quarterly[i - 1]?.yoyChange ?? null);
  }

  const ttm = normalized.map((row, index) => ({
    date: row.date,
    missing: row.missing,
    fiscalYear: row.fiscalYear,
    fiscalQuarter: row.fiscalQuarter,
    label: `${String(row.fiscalYear).slice(2)} Q${row.fiscalQuarter}`,
    value: row.missing ? null : calculateTTMGrossMargin(normalized, index)
  }));
  addSequentialDerivatives(ttm);

  const annual = calculateAnnualGrossMargin(normalized);
  addSequentialDerivatives(annual);
  const latestQuarter = [...quarterly].reverse().find((row) => row.value != null) || null;
  const latestTtm = ttm[ttm.length - 1] || null;
  const latest = latestQuarter ? {
    date: latestQuarter.date,
    quarterlyGrossMargin: latestQuarter.value,
    ttmGrossMargin: latestTtm?.value ?? null,
    qoqChange: latestQuarter.qoqChange,
    yoyChange: latestQuarter.yoyChange,
    qoqAcceleration: latestQuarter.qoqAcceleration,
    yoyAcceleration: latestQuarter.yoyAcceleration,
    incrementalQoq: latestQuarter.incrementalQoq,
    incrementalYoy: latestQuarter.incrementalYoy,
    grossProfitGrowthQoq: latestQuarter.grossProfitGrowthQoq,
    grossProfitGrowthYoY: latestQuarter.grossProfitGrowthYoy
  } : null;

  const analysis = {
    quarterly,
    ttm,
    annual,
    latest,
    unavailable: {
      segmentGrossMargin: true,
      adjustedGrossMargin: true
    }
  };
  return {
    ...analysis,
    interpretation: generateGrossMarginInterpretation(analysis)
  };
}

function addSequentialDerivatives(series) {
  for (let i = 0; i < series.length; i += 1) {
    const previous = series[i - 1];
    series[i].change = previous && series[i].value != null && previous.value != null
      ? series[i].value - previous.value
      : null;
    series[i].acceleration = i > 0
      ? calculateGrossMarginAcceleration(series[i].change, series[i - 1].change)
      : null;
  }
}

export function formatGrossMarginPercent(value) {
  return value == null ? 'N/A' : `${value.toFixed(1)}%`;
}

export function formatPercentagePoints(value) {
  if (value == null) return 'N/A';
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)} pp`;
}

export function formatGrowthPercent(value) {
  if (value == null) return 'N/A';
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)}%`;
}
