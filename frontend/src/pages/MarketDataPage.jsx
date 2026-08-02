import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { buildComparisonChart, formatCurrency } from '../utils/charts';
import {
  calculateGrossMarginAnalysis,
  classifyGrossMarginTrend,
  formatGrossMarginPercent,
  formatGrowthPercent,
  formatPercentagePoints
} from '../utils/grossMarginAnalysis';
import {
  calculateCashFlowAnalysis,
  formatCashFlowMultiple,
  formatCashFlowPercent,
  formatCashFlowPercentagePoints
} from '../utils/cashFlowAnalysis';
import {
  calculateCapitalEfficiencyAnalysis,
  formatCapitalMultiple,
  formatCapitalPercent
} from '../utils/capitalEfficiencyAnalysis';
import { fillQuarterlyFundamentalGaps, parseQuarter } from '../utils/fundamentalTimeline.js';
import { calculateCapitalAllocationSummary } from '../utils/capitalAllocationAnalysis.js';
import DateInput from '../components/DateInput';
import BottomSheet from '../components/BottomSheet';
import ChartFrame from '../components/ChartFrame';
import MobileLandscapeChart from '../components/MobileLandscapeChart';
import { RichTextNotePanel, richNoteToMarkdown } from '../components/RichTextEditor';
import SegmentedControl from '../components/SegmentedControl';
import FundamentalMetricCard from '../components/FundamentalMetricCard';
import ValuationWorkspace from '../components/ValuationWorkspace';
import useIsMobile from '../hooks/useIsMobile';

function buildHistoryRows(priceHistory, peHistory) {
  const byDate = new Map();

  priceHistory.forEach((point) => {
    const row = byDate.get(point.tradeDate) || { tradeDate: point.tradeDate };
    row.closePrice = Number(point.closePrice);
    byDate.set(point.tradeDate, row);
  });

  peHistory.forEach((point) => {
    const row = byDate.get(point.tradeDate) || { tradeDate: point.tradeDate };
    row.ttmPe = point.ttmPe == null ? null : Number(point.ttmPe);
    row.nonGaapTtmPe = point.nonGaapTtmPe == null ? null : Number(point.nonGaapTtmPe);
    row.quarterlyPe = point.quarterlyPe == null ? null : Number(point.quarterlyPe);
    row.forwardPe = point.forwardPe == null ? null : Number(point.forwardPe);
    row.ttmPeStatus = point.ttmPeStatus;
    row.nonGaapTtmPeStatus = point.nonGaapTtmPeStatus;
    row.quarterlyPeStatus = point.quarterlyPeStatus;
    row.forwardPeStatus = point.forwardPeStatus;
    row.earningsAsOf = point.earningsAsOf;
    byDate.set(point.tradeDate, row);
  });

  return [...byDate.values()].sort((a, b) => new Date(b.tradeDate) - new Date(a.tradeDate));
}

function getMobileFundamentalMetrics(row, view, analyses, t) {
  if (view === 'EPS') {
    return [
      [t('auto.Quarterly EPS', { defaultValue: 'Quarterly EPS' }), formatMetric(row.forecast ? row.forwardEps : row.basicEps, 4)],
      [t('auto.TTM EPS', { defaultValue: 'TTM EPS' }), formatMetric(row.ttmEps, 4)],
      [t('auto.Forward EPS', { defaultValue: 'Forward EPS' }), formatMetric(row.forwardEps, 4)]
    ];
  }

  if (view === 'CASH_FLOW') {
    const metrics = analyses.cashFlow.byDate.get(row.asOfDate);
    return [
      [t('auto.FCF', { defaultValue: 'FCF' }), formatLarge(row.fcf)],
      [t('auto.FCF Margin', { defaultValue: 'FCF Margin' }), formatCashFlowPercent(metrics?.fcfMargin)],
      [t('auto.FCF Conversion', { defaultValue: 'FCF Conversion' }), formatCashFlowPercent(metrics?.fcfConversion)]
    ];
  }

  if (view === 'GROSS_MARGIN') {
    return [
      [t('auto.Quarterly Margin', { defaultValue: 'Quarterly Margin' }), formatGrossMarginPercent(analyses.grossMargin.quarterlyByDate.get(row.asOfDate))],
      [t('auto.TTM Margin', { defaultValue: 'TTM Margin' }), formatGrossMarginPercent(analyses.grossMargin.ttmByDate.get(row.asOfDate))],
      [t('auto.Annual Margin', { defaultValue: 'Annual Margin' }), formatGrossMarginPercent(analyses.grossMargin.annualByYear.get(row.year))]
    ];
  }

  if (view === 'CAPITAL_EFFICIENCY') {
    const metrics = analyses.capitalEfficiency.series.find((item) => item.date === row.asOfDate);
    return [
      [t('auto.TTM ROE', { defaultValue: 'TTM ROE' }), formatCapitalPercent(metrics?.roe ?? row.roe)],
      [t('auto.TTM ROIC', { defaultValue: 'TTM ROIC' }), formatCapitalPercent(metrics?.roic ?? row.roic)],
      [t('auto.Net Margin', { defaultValue: 'Net Margin' }), formatCapitalPercent(metrics?.netProfitMargin)]
    ];
  }

  return [];
}

function normalizeSymbol(value) {
  return String(value || '').trim().toUpperCase();
}

const nonCompanyAssetClasses = new Set([
  'adr',
  'cash',
  'crypto',
  'equity_option',
  'etf',
  'foreign_adr',
  'fund',
  'mutual_fund'
]);

function isOperatingCompanyPosition(position) {
  if (!position) return true;
  const assetClass = String(position.assetClass || '').trim().toLowerCase();
  const instrumentType = String(position.instrumentType || '').trim().toLowerCase();
  if (nonCompanyAssetClasses.has(assetClass)) return false;
  if (
    instrumentType.includes('etf')
    || instrumentType.includes('adr')
    || instrumentType.includes('fund')
    || instrumentType.includes('option')
    || instrumentType.includes('cash')
    || instrumentType.includes('crypto')
  ) {
    return false;
  }
  return true;
}

function formatInstrumentDescription(position) {
  const assetClass = position?.assetClass || 'unclassified asset';
  const instrumentType = position?.instrumentType || 'unclassified instrument';
  return `${assetClass} / ${instrumentType}`;
}

function toNullableNumber(value) {
  if (value == null) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function formatMetric(value, decimals = 2, suffix = '') {
  const n = toNullableNumber(value);
  return n == null ? '--' : `${n.toFixed(decimals)}${suffix}`;
}

function formatLarge(value) {
  const n = toNullableNumber(value);
  if (n == null) return '--';
  const abs = Math.abs(n);
  if (abs >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(2)}B`;
  if (abs >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  return n.toFixed(2);
}

function buildQuarterLabelIndexSet(total, plotLeft, slot, minEndGap = 54) {
  const indexes = new Set();
  for (let i = 0; i < total; i += 1) {
    if (total <= 14 || i === 0 || i === total - 1 || i % 4 === 0) {
      indexes.add(i);
    }
  }
  if (total > 1 && indexes.has(total - 1)) {
    const lastLabelX = plotLeft + (total - 1) * slot + slot / 2;
    [...indexes].forEach((index) => {
      if (index === total - 1) return;
      const labelX = plotLeft + index * slot + slot / 2;
      if (Math.abs(lastLabelX - labelX) < minEndGap) {
        indexes.delete(index);
      }
    });
  }
  return indexes;
}

function buildSingleSeriesChart(rows, key, options = {}) {
  const data = rows
    .filter((row) => row?.asOfDate)
    .map((row) => ({ date: row.asOfDate, value: toNullableNumber(row[key]) }));
  const values = data.map((d) => d.value).filter((value) => value != null);
  if (!values.length) return { hasData: false };

  const width = 900;
  const height = options.height || 300;
  const plotLeft = 54;
  const plotRight = width - 30;
  const plotTop = 22;
  const plotBottom = height - 42;
  const maxValue = Math.max(...values, 0);
  const minValue = Math.min(...values, 0);
  const span = Math.max(maxValue - minValue, 1);
  const slot = (plotRight - plotLeft) / data.length;
  const barWidth = Math.max(Math.min(slot * 0.58, 48), 8);

  const bars = data.map((d, i) => {
    const x = plotLeft + i * slot + (slot - barWidth) / 2;
    if (d.value == null) {
      return {
        ...d,
        x,
        width: barWidth,
        label: formatQuarterLabel(d.date),
        showLabel: shouldShowQuarterLabel(d.date, i, data.length)
      };
    }
    const zeroY = plotBottom - ((0 - minValue) / span) * (plotBottom - plotTop);
    const valueY = plotBottom - ((d.value - minValue) / span) * (plotBottom - plotTop);
    return {
      ...d,
      x,
      y: Math.min(zeroY, valueY),
      width: barWidth,
      height: Math.max(Math.abs(zeroY - valueY), 1),
      label: formatQuarterLabel(d.date),
      showLabel: shouldShowQuarterLabel(d.date, i, data.length)
    };
  });

  return { hasData: true, width, height, plotLeft, plotRight, plotTop, plotBottom, minValue, maxValue, bars };
}

function buildSharesOutstandingChart(rows) {
  const data = [...(rows || [])]
    .filter((row) => row?.asOfDate)
    .map((row) => ({ date: row.asOfDate, value: toNullableNumber(row.sharesOutstanding) }))
    .sort((a, b) => new Date(`${a.date}T00:00:00Z`) - new Date(`${b.date}T00:00:00Z`));
  const values = data.map((row) => row.value).filter((value) => value != null);
  if (!values.length) return { hasData: false };

  const width = 900;
  const height = 300;
  const plotLeft = 54;
  const plotRight = width - 30;
  const plotTop = 22;
  const plotBottom = height - 42;
  const minValue = Math.min(...values);
  const maxValue = Math.max(...values);
  const padding = Math.max((maxValue - minValue) * 0.08, Math.max(Math.abs(maxValue), 1) * 0.01);
  const lower = minValue - padding;
  const upper = maxValue + padding;
  const span = Math.max(upper - lower, 1);
  const slot = data.length > 1 ? (plotRight - plotLeft) / (data.length - 1) : 0;
  const points = data.map((row, index) => ({
    ...row,
    x: data.length === 1 ? (plotLeft + plotRight) / 2 : plotLeft + index * slot,
    y: row.value == null ? null : plotBottom - ((row.value - lower) / span) * (plotBottom - plotTop),
    label: formatQuarterLabel(row.date),
    showLabel: shouldShowQuarterLabel(row.date, index, data.length)
  }));
  let open = false;
  const path = points.map((point, index) => {
    const prior = points[index - 1];
    const gapDays = prior ? Math.abs(new Date(`${point.date}T00:00:00Z`) - new Date(`${prior.date}T00:00:00Z`)) / 86400000 : 0;
    if (point.y == null) { open = false; return ''; }
    const command = !open || gapDays > 150 ? 'M' : 'L';
    open = true;
    return `${command} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`;
  }).filter(Boolean).join(' ');

  return { hasData: true, width, height, plotLeft, plotRight, plotTop, plotBottom, minValue: lower, maxValue: upper, points, path };
}

function buildDualSeriesChart(rows, leftKey, rightKey) {
  const data = rows
    .filter((row) => row?.asOfDate)
    .map((row) => ({
      date: row.asOfDate,
      left: toNullableNumber(row[leftKey]),
      right: toNullableNumber(row[rightKey])
    }));
  const values = data.flatMap((d) => [d.left, d.right].filter((v) => v != null));
  if (!values.length) return { hasData: false };

  const width = 900;
  const height = 300;
  const plotLeft = 54;
  const plotRight = width - 30;
  const plotTop = 22;
  const plotBottom = height - 42;
  const maxValue = Math.max(...values, 0);
  const minValue = Math.min(...values, 0);
  const span = Math.max(maxValue - minValue, 1);
  const slot = (plotRight - plotLeft) / data.length;
  const barWidth = Math.max(Math.min(slot * 0.28, 28), 6);
  const zeroY = plotBottom - ((0 - minValue) / span) * (plotBottom - plotTop);

  const groups = data.map((d, i) => {
    const baseX = plotLeft + i * slot + (slot - barWidth * 2 - 4) / 2;
    const makeBar = (value, offset) => {
      if (value == null) return null;
      const valueY = plotBottom - ((value - minValue) / span) * (plotBottom - plotTop);
      return {
        x: baseX + offset,
        y: Math.min(zeroY, valueY),
        width: barWidth,
        height: Math.max(Math.abs(zeroY - valueY), 1),
        value
      };
    };
    return {
      date: d.date,
      label: formatQuarterLabel(d.date),
      labelX: baseX + barWidth + 2,
      showLabel: shouldShowQuarterLabel(d.date, i, data.length),
      left: makeBar(d.left, 0),
      right: makeBar(d.right, barWidth + 4)
    };
  });

  return { hasData: true, width, height, plotLeft, plotRight, plotTop, plotBottom, minValue, maxValue, zeroY, groups };
}

function buildCashFlowDerivativeChartData(series) {
  const data = (series || [])
    .map((row) => ({
      date: row.date,
      label: row.label || formatQuarterLabel(row.date),
      operatingCashFlow: toNullableNumber(row.operatingCashFlow),
      fcf: toNullableNumber(row.fcf),
      adjustedFcf: toNullableNumber(row.adjustedFcf),
      capex: toNullableNumber(row.capex),
      growth: toNullableNumber(row.growth),
      acceleration: toNullableNumber(row.acceleration)
    }));

  const width = 900;
  const height = 430;
  const mainTop = 20;
  const mainBottom = 262;
  const subTop = 312;
  const subBottom = 392;
  const plotLeft = 58;
  const plotRight = width - 58;
  const barValues = data.flatMap((d) => [
    d.operatingCashFlow,
    d.fcf,
    d.adjustedFcf,
    d.capex == null ? null : -Math.abs(d.capex)
  ].filter((value) => value != null));
  if (!barValues.length) return { hasData: false, noDataText: 'No cash flow data in this range.' };
  const valueMax = Math.max(...barValues, 0);
  const valueMin = Math.min(...barValues, 0);
  const valueSpan = Math.max(valueMax - valueMin, 1);
  const growthValues = data.map((d) => d.growth).filter((value) => value != null);
  const accelerationValues = data.map((d) => d.acceleration).filter((value) => value != null);
  const growthMin = Math.min(...growthValues, 0);
  const growthMax = Math.max(...growthValues, 0);
  const growthSpan = Math.max(growthMax - growthMin, 1);
  const accelMin = Math.min(...accelerationValues, 0);
  const accelMax = Math.max(...accelerationValues, 0);
  const accelSpan = Math.max(accelMax - accelMin, 1);
  const slot = (plotRight - plotLeft) / data.length;
  const barWidth = Math.max(Math.min(slot * 0.16, 18), 4);
  const gap = Math.max(Math.min(slot * 0.035, 4), 1);
  const visibleLabelIndexes = buildQuarterLabelIndexSet(data.length, plotLeft, slot);
  const zeroY = mainBottom - ((0 - valueMin) / valueSpan) * (mainBottom - mainTop);

  const makeBar = (value, x, className) => {
    if (value == null) return null;
    const plotValue = className === 'cash-flow-bar-capex' ? -Math.abs(value) : value;
    const valueY = mainBottom - ((plotValue - valueMin) / valueSpan) * (mainBottom - mainTop);
    return {
      x,
      y: Math.min(zeroY, valueY),
      width: barWidth,
      height: Math.max(Math.abs(zeroY - valueY), 1),
      value,
      plotValue,
      className
    };
  };

  const groups = data.map((d, i) => {
    const totalWidth = barWidth * 4 + gap * 3;
    const baseX = plotLeft + i * slot + (slot - totalWidth) / 2;
    return {
      date: d.date,
      label: d.label,
      labelX: plotLeft + i * slot + slot / 2,
      showLabel: visibleLabelIndexes.has(i),
      bars: [
        makeBar(d.operatingCashFlow, baseX, 'cash-flow-bar-ocf'),
        makeBar(d.fcf, baseX + barWidth + gap, 'cash-flow-bar-fcf'),
        makeBar(d.adjustedFcf, baseX + (barWidth + gap) * 2, 'cash-flow-bar-adjusted-fcf'),
        makeBar(d.capex, baseX + (barWidth + gap) * 3, 'cash-flow-bar-capex')
      ].filter(Boolean)
    };
  });

  const growthPoints = data.map((d, i) => {
    if (d.growth == null) return null;
    return {
      x: plotLeft + i * slot + slot / 2,
      y: mainBottom - ((d.growth - growthMin) / growthSpan) * (mainBottom - mainTop),
      value: d.growth
    };
  });

  const accelerationBars = data.map((d, i) => {
    if (d.acceleration == null) return null;
    const x = plotLeft + i * slot + (slot - barWidth * 1.4) / 2;
    const zeroAccelY = subBottom - ((0 - accelMin) / accelSpan) * (subBottom - subTop);
    const valueY = subBottom - ((d.acceleration - accelMin) / accelSpan) * (subBottom - subTop);
    return {
      x,
      y: Math.min(zeroAccelY, valueY),
      width: barWidth * 1.4,
      height: Math.max(Math.abs(zeroAccelY - valueY), 1),
      value: d.acceleration
    };
  }).filter(Boolean);

  return {
    hasData: true,
    width,
    height,
    plotLeft,
    plotRight,
    mainTop,
    mainBottom,
    subTop,
    subBottom,
    zeroY,
    valueMin,
    valueMax,
    growthMin,
    growthMax,
    accelMin,
    accelMax,
    groups,
    growthPath: buildPointPath(growthPoints),
    growthPoints: growthPoints.filter(Boolean),
    accelerationBars
  };
}

function buildTtmCashFlowChartData(series) {
  const data = (series || [])
    .map((row) => ({
      date: row.date,
      label: row.label || formatQuarterLabel(row.date),
      value: toNullableNumber(row.value),
      growth: toNullableNumber(row.growth),
      acceleration: toNullableNumber(row.acceleration)
    }));
  const presentValues = data.map((d) => d.value).filter((value) => value != null);
  if (!presentValues.length) return { hasData: false, noDataText: 'No TTM cash flow data in this range.' };

  const width = 900;
  const height = 420;
  const mainTop = 20;
  const mainBottom = 258;
  const subTop = 308;
  const subBottom = 382;
  const plotLeft = 58;
  const plotRight = width - 58;
  const valueMax = Math.max(...presentValues, 0);
  const valueMin = Math.min(...presentValues, 0);
  const valueSpan = Math.max(valueMax - valueMin, 1);
  const growthValues = data.map((d) => d.growth).filter((value) => value != null);
  const accelerationValues = data.map((d) => d.acceleration).filter((value) => value != null);
  const growthMin = Math.min(...growthValues, 0);
  const growthMax = Math.max(...growthValues, 0);
  const growthSpan = Math.max(growthMax - growthMin, 1);
  const accelMin = Math.min(...accelerationValues, 0);
  const accelMax = Math.max(...accelerationValues, 0);
  const accelSpan = Math.max(accelMax - accelMin, 1);
  const slot = (plotRight - plotLeft) / data.length;
  const barWidth = Math.max(Math.min(slot * 0.56, 44), 8);
  const visibleLabelIndexes = buildQuarterLabelIndexSet(data.length, plotLeft, slot);
  const zeroY = mainBottom - ((0 - valueMin) / valueSpan) * (mainBottom - mainTop);

  const bars = data.map((d, i) => {
    const x = plotLeft + i * slot + (slot - barWidth) / 2;
    if (d.value == null) {
      return {
        ...d,
        x,
        width: barWidth,
        showLabel: visibleLabelIndexes.has(i)
      };
    }
    const valueY = mainBottom - ((d.value - valueMin) / valueSpan) * (mainBottom - mainTop);
    return {
      ...d,
      x,
      y: Math.min(zeroY, valueY),
      width: barWidth,
      height: Math.max(Math.abs(zeroY - valueY), 1),
      showLabel: visibleLabelIndexes.has(i)
    };
  });
  const growthPoints = data.map((d, i) => {
    if (d.growth == null) return null;
    return {
      x: plotLeft + i * slot + slot / 2,
      y: mainBottom - ((d.growth - growthMin) / growthSpan) * (mainBottom - mainTop),
      value: d.growth
    };
  });
  const accelerationBars = data.map((d, i) => {
    if (d.acceleration == null) return null;
    const x = plotLeft + i * slot + (slot - barWidth * 0.6) / 2;
    const zeroAccelY = subBottom - ((0 - accelMin) / accelSpan) * (subBottom - subTop);
    const valueY = subBottom - ((d.acceleration - accelMin) / accelSpan) * (subBottom - subTop);
    return {
      x,
      y: Math.min(zeroAccelY, valueY),
      width: barWidth * 0.6,
      height: Math.max(Math.abs(zeroAccelY - valueY), 1),
      value: d.acceleration
    };
  }).filter(Boolean);

  return {
    hasData: true,
    width,
    height,
    plotLeft,
    plotRight,
    mainTop,
    mainBottom,
    subTop,
    subBottom,
    valueMin,
    valueMax,
    growthMin,
    growthMax,
    accelMin,
    accelMax,
    bars,
    growthPath: buildPointPath(growthPoints),
    growthPoints: growthPoints.filter(Boolean),
    accelerationBars
  };
}

function buildGrossMarginDerivativeChartData(series, options = {}) {
  const data = (series || [])
    .map((row) => ({
      label: row.label || String(row.year || row.date || ''),
      value: toNullableNumber(row.value),
      change: toNullableNumber(row[options.changeKey || 'change']),
      acceleration: toNullableNumber(row[options.accelerationKey || 'acceleration'])
    }));
  const levelValues = data.map((d) => d.value).filter((value) => value != null);
  if (!levelValues.length) return { hasData: false, noDataText: '数据不足' };

  const width = 900;
  const height = 420;
  const mainTop = 20;
  const mainBottom = 258;
  const subTop = 308;
  const subBottom = 382;
  const plotLeft = 54;
  const plotRight = width - 54;
  const levelMax = Math.max(...levelValues, 0);
  const levelMin = Math.min(...levelValues, 0);
  const levelSpan = Math.max(levelMax - levelMin, 1);
  const changeValues = data.map((d) => d.change).filter((value) => value != null);
  const accelerationValues = data.map((d) => d.acceleration).filter((value) => value != null);
  const changeMin = Math.min(...changeValues, 0);
  const changeMax = Math.max(...changeValues, 0);
  const changeSpan = Math.max(changeMax - changeMin, 1);
  const accelMin = Math.min(...accelerationValues, 0);
  const accelMax = Math.max(...accelerationValues, 0);
  const accelSpan = Math.max(accelMax - accelMin, 1);
  const slot = (plotRight - plotLeft) / data.length;
  const barWidth = Math.max(Math.min(slot * 0.56, 44), 8);
  const visibleLabelIndexes = buildQuarterLabelIndexSet(data.length, plotLeft, slot);

  const bars = data.map((d, i) => {
    const x = plotLeft + i * slot + (slot - barWidth) / 2;
    if (d.value == null) {
      return {
        ...d,
        x,
        width: barWidth,
        showLabel: visibleLabelIndexes.has(i)
      };
    }
    const zeroY = mainBottom - ((0 - levelMin) / levelSpan) * (mainBottom - mainTop);
    const valueY = mainBottom - ((d.value - levelMin) / levelSpan) * (mainBottom - mainTop);
    return {
      ...d,
      x,
      y: Math.min(zeroY, valueY),
      width: barWidth,
      height: Math.max(Math.abs(zeroY - valueY), 1),
      showLabel: visibleLabelIndexes.has(i)
    };
  });

  const changePoints = data.map((d, i) => {
    if (d.change == null) return null;
    return {
      x: plotLeft + i * slot + slot / 2,
      y: mainBottom - ((d.change - changeMin) / changeSpan) * (mainBottom - mainTop),
      value: d.change
    };
  });

  const accelerationBars = data.map((d, i) => {
    if (d.acceleration == null) return null;
    const x = plotLeft + i * slot + (slot - barWidth * 0.6) / 2;
    const zeroY = subBottom - ((0 - accelMin) / accelSpan) * (subBottom - subTop);
    const valueY = subBottom - ((d.acceleration - accelMin) / accelSpan) * (subBottom - subTop);
    return {
      x,
      y: Math.min(zeroY, valueY),
      width: barWidth * 0.6,
      height: Math.max(Math.abs(zeroY - valueY), 1),
      value: d.acceleration
    };
  }).filter(Boolean);

  return {
    hasData: true,
    title: options.title,
    axisLabel: options.axisLabel,
    changeLabel: options.changeLabel,
    accelerationLabel: options.accelerationLabel,
    width,
    height,
    plotLeft,
    plotRight,
    mainTop,
    mainBottom,
    subTop,
    subBottom,
    levelMin,
    levelMax,
    changeMin,
    changeMax,
    accelMin,
    accelMax,
    bars,
    changePath: buildPointPath(changePoints),
    changePoints: changePoints.filter(Boolean),
    accelerationBars
  };
}

function buildCapitalReturnTrendChart(series, metricKey = 'roe', averageKey = 'avgRoe5y') {
  const data = (series || [])
    .map((row) => ({
      date: row.date,
      label: row.label || formatQuarterLabel(row.date),
      value: toNullableNumber(row[metricKey]),
      average: toNullableNumber(row[averageKey])
    }));
  const displayData = fillInternalNullCapitalReturnValues(data, ['value', 'average']);
  const values = displayData.flatMap((row) => [row.value, row.average].filter((value) => value != null));
  if (!values.length) return { hasData: false };

  const sortedValues = [...values].sort((a, b) => a - b);
  const percentile = (p) => {
    if (!sortedValues.length) return 0;
    const index = Math.min(sortedValues.length - 1, Math.max(0, Math.round((sortedValues.length - 1) * p)));
    return sortedValues[index];
  };

  const width = 900;
  const height = 320;
  const plotLeft = 56;
  const plotRight = width - 40;
  const plotTop = 22;
  const plotBottom = height - 44;
  const robustMin = Math.min(percentile(0.05), 0);
  const robustMax = Math.max(percentile(0.95), 0);
  const robustSpan = Math.max(robustMax - robustMin, 1);
  const padding = Math.max(robustSpan * 0.12, 5);
  const minValue = Math.max(robustMin - padding, -100);
  const maxValue = Math.min(robustMax + padding, 100);
  const span = Math.max(maxValue - minValue, 1);
  const slot = data.length > 1 ? (plotRight - plotLeft) / (data.length - 1) : 1;
  const visibleLabelIndexes = buildQuarterLabelIndexSet(data.length, plotLeft, slot);
  const pointFor = (value, index) => {
    if (value == null) return null;
    const displayValue = Math.min(Math.max(value, minValue), maxValue);
    return {
      x: data.length > 1 ? plotLeft + index * slot : (plotLeft + plotRight) / 2,
      y: plotBottom - ((displayValue - minValue) / span) * (plotBottom - plotTop),
      value,
      clipped: displayValue !== value
    };
  };
  const paths = {
    value: buildPointPath(displayData.map((row, index) => pointFor(row.value, index))),
    average: buildPointPath(displayData.map((row, index) => pointFor(row.average, index)))
  };
  const labels = data.map((row, index) => ({
    label: row.label,
    x: data.length > 1 ? plotLeft + index * slot : (plotLeft + plotRight) / 2,
    show: visibleLabelIndexes.has(index)
  }));

  return { hasData: true, width, height, plotLeft, plotRight, plotTop, plotBottom, minValue, maxValue, paths, labels };
}

function fillInternalNullCapitalReturnValues(data, keys) {
  return data.map((row, index) => {
    const filled = { ...row };
    for (const key of keys) {
      if (row[key] != null) continue;
      const first = data.findIndex((item) => item[key] != null);
      let last = -1;
      for (let i = data.length - 1; i >= 0; i -= 1) {
        if (data[i][key] != null) {
          last = i;
          break;
        }
      }
      if (first >= 0 && last > first && index > first && index < last) {
        filled[key] = 0;
      }
    }
    return filled;
  });
}

function buildIncrementalRoicChart(series) {
  const data = (series || [])
    .map((row) => ({
      date: row.date,
      label: row.label || formatQuarterLabel(row.date),
      value: toNullableNumber(row.incrementalRoic?.value),
      status: row.incrementalRoic?.status || 'unavailable',
      reason: row.incrementalRoic?.reason || ''
    }));
  const hasDisplayableData = data.some((row) => row.value != null || row.status === 'abnormal');
  if (!hasDisplayableData) return { hasData: false, noDataText: 'Incremental ROIC is unavailable for this range.' };

  const width = 900;
  const height = 300;
  const plotLeft = 56;
  const plotRight = width - 42;
  const plotTop = 24;
  const plotBottom = height - 44;
  const normalValues = data.map((row) => row.value).filter((value) => value != null);
  const rawMin = Math.min(...normalValues, 0);
  const rawMax = Math.max(...normalValues, 0);
  const minValue = Math.max(Math.min(rawMin, 0), -100);
  const maxValue = Math.min(Math.max(rawMax, 0), 100);
  const span = Math.max(maxValue - minValue, 1);
  const slot = (plotRight - plotLeft) / data.length;
  const barWidth = Math.max(Math.min(slot * 0.54, 38), 7);
  const visibleLabelIndexes = buildQuarterLabelIndexSet(data.length, plotLeft, slot);
  const zeroY = plotBottom - ((0 - minValue) / span) * (plotBottom - plotTop);
  const bars = data.map((row, index) => {
    const x = plotLeft + index * slot + (slot - barWidth) / 2;
    if (row.value == null && row.status === 'abnormal') {
      return {
        ...row,
        abnormal: true,
        x,
        y: zeroY - 5,
        width: barWidth,
        height: 10,
        labelX: plotLeft + index * slot + slot / 2,
        showLabel: visibleLabelIndexes.has(index)
      };
    }
    if (row.value == null) {
      return {
        ...row,
        missing: true,
        x,
        width: barWidth,
        labelX: plotLeft + index * slot + slot / 2,
        showLabel: visibleLabelIndexes.has(index)
      };
    }
    const clippedValue = Math.max(Math.min(row.value, 100), -100);
    const valueY = plotBottom - ((clippedValue - minValue) / span) * (plotBottom - plotTop);
    return {
      ...row,
      clipped: clippedValue !== row.value,
      x,
      y: Math.min(zeroY, valueY),
      width: barWidth,
      height: Math.max(Math.abs(zeroY - valueY), 1),
      labelX: plotLeft + index * slot + slot / 2,
      showLabel: visibleLabelIndexes.has(index)
    };
  });
  return { hasData: true, width, height, plotLeft, plotRight, plotTop, plotBottom, zeroY, minValue, maxValue, bars };
}

function buildCapeTermStructureChart(latest) {
  const data = [
    { label: 'TTM', value: toNullableNumber(latest?.ttmPe) },
    { label: '3Y', value: toNullableNumber(latest?.pe3y) },
    { label: '5Y', value: toNullableNumber(latest?.pe5y) },
    { label: '10Y', value: toNullableNumber(latest?.cape10y) }
  ];
  const values = data.map((row) => row.value).filter((value) => value != null);
  if (!values.length) return { hasData: false, noDataText: 'PE term structure is unavailable.' };

  const width = 720;
  const height = 280;
  const plotLeft = 58;
  const plotRight = width - 38;
  const plotTop = 24;
  const plotBottom = height - 48;
  const minValue = Math.min(...values, 0);
  const maxValue = Math.max(...values, 0);
  const span = Math.max(maxValue - minValue, 1);
  const slot = data.length > 1 ? (plotRight - plotLeft) / (data.length - 1) : 1;
  const points = data.map((row, index) => {
    if (row.value == null) {
      return {
        ...row,
        x: plotLeft + index * slot,
        missing: true
      };
    }
    return {
      ...row,
      x: plotLeft + index * slot,
      y: plotBottom - ((row.value - minValue) / span) * (plotBottom - plotTop)
    };
  });

  return {
    hasData: true,
    width,
    height,
    plotLeft,
    plotRight,
    plotTop,
    plotBottom,
    minValue,
    maxValue,
    path: buildPointPath(points.map((point) => (point.missing ? null : point))),
    points
  };
}

function buildHistoricalCapeChart(series) {
  const fillInternalPeGaps = (rows, key) => {
    const firstIndex = rows.findIndex((row) => row[key] != null);
    if (firstIndex < 0) return rows;
    let seenValue = false;
    return rows.map((row, index) => {
      if (index < firstIndex) return row;
      if (row[key] != null) {
        seenValue = true;
        return row;
      }
      return seenValue ? { ...row, [key]: 0 } : row;
    });
  };

  let data = (series || [])
    .filter((row) => row?.date)
    .map((row) => ({
      date: row.date,
      label: row.label || formatQuarterLabel(row.date),
      ttmPe: toNullableNumber(row.ttmPe),
      pe3y: toNullableNumber(row.pe3y),
      pe5y: toNullableNumber(row.pe5y),
      cape10y: toNullableNumber(row.cape10y)
    }));
  data = ['ttmPe', 'pe3y', 'pe5y', 'cape10y'].reduce(fillInternalPeGaps, data);
  const values = data.flatMap((row) => [row.ttmPe, row.pe3y, row.pe5y, row.cape10y].filter((value) => value != null));
  if (!values.length) return { hasData: false, noDataText: 'Historical CAPE chart is unavailable.' };

  const width = 900;
  const height = 340;
  const plotLeft = 58;
  const plotRight = width - 42;
  const plotTop = 24;
  const plotBottom = height - 48;
  const minValue = Math.min(...values, 0);
  const maxValue = Math.max(...values, 0);
  const span = Math.max(maxValue - minValue, 1);
  const slot = data.length > 1 ? (plotRight - plotLeft) / (data.length - 1) : 1;
  const visibleLabelIndexes = buildQuarterLabelIndexSet(data.length, plotLeft, slot);
  const pointFor = (key, index) => {
    const value = data[index][key];
    if (value == null) return null;
    return {
      x: data.length > 1 ? plotLeft + index * slot : (plotLeft + plotRight) / 2,
      y: plotBottom - ((value - minValue) / span) * (plotBottom - plotTop),
      value
    };
  };
  const labels = data.map((row, index) => ({
    label: row.label,
    x: data.length > 1 ? plotLeft + index * slot : (plotLeft + plotRight) / 2,
    show: visibleLabelIndexes.has(index)
  }));

  return {
    hasData: true,
    width,
    height,
    plotLeft,
    plotRight,
    plotTop,
    plotBottom,
    minValue,
    maxValue,
    labels,
    paths: {
      ttmPe: buildPointPath(data.map((_, index) => pointFor('ttmPe', index))),
      pe3y: buildPointPath(data.map((_, index) => pointFor('pe3y', index))),
      pe5y: buildPointPath(data.map((_, index) => pointFor('pe5y', index))),
      cape10y: buildPointPath(data.map((_, index) => pointFor('cape10y', index)))
    }
  };
}

function buildEpsChart(rows, mode = 'annualizedQuarterly') {
  const sourceRows = rows.map((row) => ({
    date: row.asOfDate,
    quarterlyEps: row.forecast ? toNullableNumber(row.forwardEps) : toNullableNumber(row.basicEps),
    ttmEps: toNullableNumber(row.ttmEps),
    forecast: Boolean(row.forecast)
  }));
  const data = mode === 'ttm'
    ? buildTtmEpsData(sourceRows)
    : sourceRows
      .map((row) => ({
        date: row.date,
        eps: row.quarterlyEps == null ? null : row.quarterlyEps * 4,
        forecast: row.forecast
      }));
  const epsValues = data.map((d) => d.eps).filter((value) => value != null);
  if (!epsValues.length) return { hasData: false };

  const width = 900;
  const height = 420;
  const mainTop = 20;
  const mainBottom = 258;
  const subTop = 308;
  const subBottom = 382;
  const plotLeft = 54;
  const plotRight = width - 54;
  const epsMax = Math.max(...epsValues, 0);
  const epsMin = Math.min(...epsValues, 0);
  const epsSpan = Math.max(epsMax - epsMin, 1);
  const growthValues = data.map((d, i) => {
    if (i === 0 || d.eps == null || data[i - 1].eps == null || data[i - 1].eps === 0) return null;
    return ((d.eps - data[i - 1].eps) / Math.abs(data[i - 1].eps)) * 100;
  });
  const accelerationValues = growthValues.map((value, i) => {
    if (i === 0 || value == null || growthValues[i - 1] == null) return null;
    return value - growthValues[i - 1];
  });
  const growthPresent = growthValues.filter((v) => v != null);
  const accelerationPresent = accelerationValues.filter((v) => v != null);
  const growthMin = Math.min(...growthPresent, 0);
  const growthMax = Math.max(...growthPresent, 0);
  const growthSpan = Math.max(growthMax - growthMin, 1);
  const accelMin = Math.min(...accelerationPresent, 0);
  const accelMax = Math.max(...accelerationPresent, 0);
  const accelSpan = Math.max(accelMax - accelMin, 1);
  const slot = (plotRight - plotLeft) / data.length;
  const barWidth = Math.max(Math.min(slot * 0.56, 44), 8);

  const bars = data.map((d, i) => {
    const x = plotLeft + i * slot + (slot - barWidth) / 2;
    if (d.eps == null) {
      return {
        ...d,
        x,
        width: barWidth,
        label: formatQuarterLabel(d.date),
        forecast: d.forecast,
        showLabel: shouldShowQuarterLabel(d.date, i, data.length, d.forecast)
      };
    }
    const zeroY = mainBottom - ((0 - epsMin) / epsSpan) * (mainBottom - mainTop);
    const valueY = mainBottom - ((d.eps - epsMin) / epsSpan) * (mainBottom - mainTop);
    return {
      ...d,
      x,
      y: Math.min(zeroY, valueY),
      width: barWidth,
      height: Math.max(Math.abs(zeroY - valueY), 1),
      label: formatQuarterLabel(d.date),
      forecast: d.forecast,
      showLabel: shouldShowQuarterLabel(d.date, i, data.length, d.forecast)
    };
  });

  const growthPoints = growthValues.map((value, i) => {
    if (value == null) return null;
    return {
      x: plotLeft + i * slot + slot / 2,
      y: mainBottom - ((value - growthMin) / growthSpan) * (mainBottom - mainTop),
      forecast: data[i].forecast
    };
  });
  const lastActualGrowthIndex = findLastActualGrowthIndex(growthPoints);
  const actualGrowthPath = buildPointPath(growthPoints.map((point, i) => (i <= lastActualGrowthIndex ? point : null)));
  const forecastGrowthPath = buildPointPath(growthPoints.map((point, i) => (
    i >= lastActualGrowthIndex && point ? point : null
  )));

  const accelerationBars = accelerationValues.map((value, i) => {
    if (value == null) return null;
    const x = plotLeft + i * slot + (slot - barWidth * 0.6) / 2;
    const zeroY = subBottom - ((0 - accelMin) / accelSpan) * (subBottom - subTop);
    const valueY = subBottom - ((value - accelMin) / accelSpan) * (subBottom - subTop);
    return {
      x,
      y: Math.min(zeroY, valueY),
      width: barWidth * 0.6,
      height: Math.max(Math.abs(zeroY - valueY), 1),
      value,
      forecast: data[i].forecast
    };
  }).filter(Boolean);

  return {
    hasData: true,
    noDataText: mode === 'ttm' ? 'No TTM EPS data in this range.' : 'No annualized quarterly EPS data in this range.',
    ariaLabel: mode === 'ttm'
      ? 'TTM EPS chart with growth and acceleration'
      : 'Annualized quarterly EPS chart with growth and acceleration',
    axisLabel: mode === 'ttm' ? 'TTM EPS per share ($)' : 'Annualized EPS per share ($)',
    actualLegend: mode === 'ttm' ? 'TTM EPS ($/share)' : 'Annualized Quarterly EPS ($/share)',
    forecastLegend: mode === 'ttm' ? 'Forward TTM EPS estimate ($/share)' : 'Annualized Forward EPS estimate ($/share)',
    growthLegend: mode === 'ttm' ? 'QoQ TTM EPS growth' : 'QoQ EPS growth',
    width,
    height,
    plotLeft,
    plotRight,
    mainTop,
    mainBottom,
    subTop,
    subBottom,
    epsMin,
    epsMax,
    growthMin,
    growthMax,
    accelMin,
    accelMax,
    bars,
    actualGrowthPath,
    forecastGrowthPath,
    growthPoints: growthPoints.filter(Boolean),
    accelerationBars
  };
}

function buildTtmEpsData(rows) {
  const quarterlyHistory = [];
  const data = [];
  rows.forEach((row) => {
    if (row.quarterlyEps != null) {
      quarterlyHistory.push(row.quarterlyEps);
    }
    let eps = row.forecast ? null : row.ttmEps;
    if (row.forecast && quarterlyHistory.length >= 4) {
      eps = quarterlyHistory.slice(-4).reduce((sum, value) => sum + value, 0);
    }
    data.push({
      date: row.date,
      eps,
      forecast: row.forecast
    });
  });
  return data;
}

function findLastActualGrowthIndex(points) {
  for (let i = points.length - 1; i >= 0; i -= 1) {
    if (points[i] && !points[i].forecast) return i;
  }
  return -1;
}

function buildPointPath(points) {
  let open = false;
  return points.map((point) => {
    if (!point) {
      open = false;
      return '';
    }
    const prefix = open ? 'L' : 'M';
    open = true;
    return `${prefix} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`;
  }).filter(Boolean).join(' ');
}

function formatQuarterLabel(date) {
  const parsed = parseQuarter(date);
  if (!parsed) return date;
  return `${String(parsed.year).slice(2)} Q${parsed.quarter}`;
}

function shouldShowQuarterLabel(date, index, total, forecast = false) {
  if (total <= 14) return true;
  const quarter = parseQuarter(date)?.quarter;
  if (index === total - 2) return false;
  if (index === 0 || index === total - 1 || forecast) return true;
  if (total <= 28) return index % 2 === 0;
  return quarter === 4;
}

function SingleSeriesChart({ chart, valueFormatter = formatMetric, barClass = 'fundamental-bar' }) {
  if (!chart.hasData) return <p className="muted">No data in this range.</p>;
  return (
    <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart" role="img">
      <line x1={chart.plotLeft} y1={chart.plotBottom} x2={chart.plotRight} y2={chart.plotBottom} className="chart-axis" />
      <line x1={chart.plotLeft} y1={chart.plotTop} x2={chart.plotLeft} y2={chart.plotBottom} className="chart-axis" />
      <text x={chart.plotLeft - 8} y={chart.plotTop + 4} textAnchor="end" className="chart-tick">{valueFormatter(chart.maxValue)}</text>
      <text x={chart.plotLeft - 8} y={chart.plotBottom} textAnchor="end" className="chart-tick">{valueFormatter(chart.minValue)}</text>
      {chart.bars.map((bar) => (
        <g key={`${bar.date}-${bar.value}`}>
          {bar.value != null ? <rect x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="3" className={barClass} /> : null}
          {bar.showLabel ? <text x={bar.x + bar.width / 2} y={chart.plotBottom + 16} textAnchor="middle" className="chart-tick-bottom">{bar.label}</text> : null}
        </g>
      ))}
    </svg>
  );
}

function SharesOutstandingChart({ chart }) {
  if (!chart.hasData) return <p className="muted">No SEC-reported shares outstanding in this range.</p>;
  return (
    <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart" role="img" aria-label="Split-adjusted shares outstanding history">
      <line x1={chart.plotLeft} y1={chart.plotBottom} x2={chart.plotRight} y2={chart.plotBottom} className="chart-axis" />
      <line x1={chart.plotLeft} y1={chart.plotTop} x2={chart.plotLeft} y2={chart.plotBottom} className="chart-axis" />
      <text x={chart.plotLeft - 8} y={chart.plotTop + 4} textAnchor="end" className="chart-tick">{formatLarge(chart.maxValue)}</text>
      <text x={chart.plotLeft - 8} y={chart.plotBottom} textAnchor="end" className="chart-tick">{formatLarge(chart.minValue)}</text>
      <path d={chart.path} className="capital-allocation-line" />
      {chart.points.map((point) => point.value != null ? <circle key={point.date} cx={point.x} cy={point.y} r="3" className="capital-allocation-dot" /> : null)}
      {chart.points.map((point) => point.showLabel ? (
        <text key={`${point.date}-label`} x={point.x} y={chart.plotBottom + 16} textAnchor="middle" className="chart-tick-bottom">{point.label}</text>
      ) : null)}
    </svg>
  );
}

function DualSeriesChart({ chart }) {
  if (!chart.hasData) return <p className="muted">No data in this range.</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart" role="img">
        <line x1={chart.plotLeft} y1={chart.zeroY} x2={chart.plotRight} y2={chart.zeroY} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.plotTop} x2={chart.plotLeft} y2={chart.plotBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.plotTop + 4} textAnchor="end" className="chart-tick">{formatLarge(chart.maxValue)}</text>
        <text x={chart.plotLeft - 8} y={chart.plotBottom} textAnchor="end" className="chart-tick">{formatLarge(chart.minValue)}</text>
        {chart.groups.map((group) => (
          <g key={group.date}>
            {group.left ? <rect x={group.left.x} y={group.left.y} width={group.left.width} height={group.left.height} rx="3" className="fundamental-bar" /> : null}
            {group.right ? <rect x={group.right.x} y={group.right.y} width={group.right.width} height={group.right.height} rx="3" className="fundamental-bar-secondary" /> : null}
            {group.showLabel ? <text x={group.labelX} y={chart.plotBottom + 16} textAnchor="middle" className="chart-tick-bottom">{group.label}</text> : null}
          </g>
        ))}
      </svg>
      <div className="legend-row">
        <span><i className="dot dot-fundamental" /> Operating Cash Flow</span>
        <span><i className="dot dot-fundamental-secondary" /> Free Cash Flow</span>
      </div>
    </>
  );
}

function CashFlowDerivativeChart({ chart }) {
  if (!chart.hasData) return <p className="muted">{chart.noDataText || 'No cash flow data in this range.'}</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart eps-chart cash-flow-derivative-chart" role="img" aria-label="Cash flow chart with FCF growth and acceleration">
        <line x1={chart.plotLeft} y1={chart.zeroY} x2={chart.plotRight} y2={chart.zeroY} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.mainTop} x2={chart.plotLeft} y2={chart.mainBottom} className="chart-axis" />
        <line x1={chart.plotRight} y1={chart.mainTop} x2={chart.plotRight} y2={chart.mainBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.mainTop + 4} textAnchor="end" className="chart-tick">{formatLarge(chart.valueMax)}</text>
        <text x={chart.plotLeft - 8} y={chart.mainBottom} textAnchor="end" className="chart-tick">{formatLarge(chart.valueMin)}</text>
        <text x={chart.plotLeft} y={chart.mainTop - 8} className="chart-label">Cash flow ($), CapEx inverted</text>
        <text x={chart.plotRight + 8} y={chart.mainTop + 4} className="chart-tick">{formatCashFlowPercent(chart.growthMax)}</text>
        <text x={chart.plotRight + 8} y={chart.mainBottom} className="chart-tick">{formatCashFlowPercent(chart.growthMin)}</text>
        {chart.groups.map((group) => (
          <g key={group.date}>
            {group.bars.map((bar) => (
              <rect key={`${group.date}-${bar.className}`} x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="2" className={bar.className} />
            ))}
            {group.showLabel ? <text x={group.labelX} y={chart.subBottom + 16} textAnchor="middle" className="chart-tick-bottom">{group.label}</text> : null}
          </g>
        ))}
        <path d={chart.growthPath} className="chart-line-growth" />
        {chart.growthPoints.map((point) => <circle key={`${point.x}-${point.y}`} cx={point.x} cy={point.y} r="3" className="growth-point" />)}
        <text x={chart.plotLeft} y={chart.subTop - 12} className="chart-label">FCF growth acceleration (percentage points)</text>
        <text x={chart.plotLeft - 8} y={chart.subTop + 4} textAnchor="end" className="chart-tick">{formatCashFlowPercentagePoints(chart.accelMax)}</text>
        <text x={chart.plotLeft - 8} y={chart.subBottom} textAnchor="end" className="chart-tick">{formatCashFlowPercentagePoints(chart.accelMin)}</text>
        <line x1={chart.plotLeft} y1={chart.subBottom} x2={chart.plotRight} y2={chart.subBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.subTop} x2={chart.plotLeft} y2={chart.subBottom} className="chart-axis" />
        {chart.accelerationBars.map((bar) => <rect key={`${bar.x}-${bar.value}`} x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="2" className={[
          'acceleration-bar',
          bar.value >= 0 ? 'positive-bar' : 'negative-bar',
        ].filter(Boolean).join(' ')} />)}
      </svg>
      <div className="legend-row">
        <span><i className="dot dot-fundamental" /> Operating Cash Flow</span>
        <span><i className="dot dot-fundamental-secondary" /> Free Cash Flow</span>
        <span><i className="dot dot-adjusted-fcf" /> Adjusted FCF</span>
        <span><i className="dot dot-capex" /> CapEx inverted</span>
        <span><i className="dot dot-growth" /> FCF growth</span>
        <span><i className="dot dot-acceleration" /> FCF growth acceleration</span>
      </div>
    </>
  );
}

function TtmCashFlowChart({ chart }) {
  if (!chart.hasData) return <p className="muted">{chart.noDataText || 'No TTM cash flow data in this range.'}</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart eps-chart ttm-cash-flow-chart" role="img" aria-label="TTM adjusted FCF chart with growth and acceleration">
        <line x1={chart.plotLeft} y1={chart.mainBottom} x2={chart.plotRight} y2={chart.mainBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.mainTop} x2={chart.plotLeft} y2={chart.mainBottom} className="chart-axis" />
        <line x1={chart.plotRight} y1={chart.mainTop} x2={chart.plotRight} y2={chart.mainBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.mainTop + 4} textAnchor="end" className="chart-tick">{formatLarge(chart.valueMax)}</text>
        <text x={chart.plotLeft - 8} y={chart.mainBottom} textAnchor="end" className="chart-tick">{formatLarge(chart.valueMin)}</text>
        <text x={chart.plotLeft} y={chart.mainTop - 8} className="chart-label">TTM Adjusted FCF</text>
        <text x={chart.plotRight + 8} y={chart.mainTop + 4} className="chart-tick">{formatCashFlowPercent(chart.growthMax)}</text>
        <text x={chart.plotRight + 8} y={chart.mainBottom} className="chart-tick">{formatCashFlowPercent(chart.growthMin)}</text>
        {chart.bars.map((bar) => (
          <g key={bar.date}>
            {bar.value != null ? <rect x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="3" className="cash-flow-bar-adjusted-fcf" /> : null}
            {bar.showLabel ? <text x={bar.x + bar.width / 2} y={chart.subBottom + 16} textAnchor="middle" className="chart-tick-bottom">{bar.label}</text> : null}
          </g>
        ))}
        <path d={chart.growthPath} className="chart-line-growth" />
        {chart.growthPoints.map((point) => <circle key={`${point.x}-${point.y}`} cx={point.x} cy={point.y} r="3" className="growth-point" />)}
        <text x={chart.plotLeft} y={chart.subTop - 12} className="chart-label">TTM FCF growth acceleration (percentage points)</text>
        <text x={chart.plotLeft - 8} y={chart.subTop + 4} textAnchor="end" className="chart-tick">{formatCashFlowPercentagePoints(chart.accelMax)}</text>
        <text x={chart.plotLeft - 8} y={chart.subBottom} textAnchor="end" className="chart-tick">{formatCashFlowPercentagePoints(chart.accelMin)}</text>
        <line x1={chart.plotLeft} y1={chart.subBottom} x2={chart.plotRight} y2={chart.subBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.subTop} x2={chart.plotLeft} y2={chart.subBottom} className="chart-axis" />
        {chart.accelerationBars.map((bar) => <rect key={`${bar.x}-${bar.value}`} x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="2" className={[
          'acceleration-bar',
          bar.value >= 0 ? 'positive-bar' : 'negative-bar',
        ].filter(Boolean).join(' ')} />)}
      </svg>
      <div className="legend-row">
        <span><i className="dot dot-adjusted-fcf" /> TTM Adjusted FCF</span>
        <span><i className="dot dot-growth" /> TTM FCF growth</span>
        <span><i className="dot dot-acceleration" /> TTM FCF growth acceleration</span>
      </div>
    </>
  );
}

function CashFlowAnalysisPanel({ analysis }) {
  const chart = buildCashFlowDerivativeChartData(analysis.quarterly);
  const ttmChart = buildTtmCashFlowChartData(analysis.ttm);
  const latest = analysis.latest;
  const cards = [
    ['Latest FCF', formatLarge(latest?.fcf), '$', latest?.growth == null ? 'insufficient-data' : latest.growth >= 0 ? 'expansion' : 'compression', 'Reported free cash flow for the latest quarter.'],
    ['Latest Adjusted FCF', formatLarge(latest?.adjustedFcf), '$', latest?.growth == null ? 'insufficient-data' : latest.growth >= 0 ? 'expansion' : 'compression', 'Adjusted free cash flow. Formula fallback: Operating Cash Flow - CapEx.'],
    ['Latest CapEx', formatLarge(latest?.capex), '$', 'level', 'Capital expenditure for the latest quarter, shown inverted in the chart.'],
    ['3Y FCF CAGR', formatCashFlowPercent(analysis.cagr3), '%', analysis.metricTrends.cagr3, 'Formula: compound annual growth rate of annual adjusted FCF over 3 years.'],
    ['5Y FCF CAGR', formatCashFlowPercent(analysis.cagr5), '%', analysis.metricTrends.cagr5, 'Formula: compound annual growth rate of annual adjusted FCF over 5 years.'],
    ['FCF Margin', formatCashFlowPercent(latest?.fcfMargin), '%', analysis.metricTrends.fcfMargin, 'FCF Margin = FCF / Revenue。每 1 美元收入能转化多少自由现金流。'],
    ['FCF Conversion', formatCashFlowPercent(latest?.fcfConversion), '%', analysis.metricTrends.fcfConversion, 'FCF Conversion = FCF / Net Income。净利润转化为现金的能力。'],
    ['OCF Conversion', formatCashFlowPercent(latest?.ocfConversion), '%', analysis.metricTrends.ocfConversion, 'OCF Conversion = Operating Cash Flow / Net Income。盈利现金含量。'],
    ['CapEx Intensity', formatCashFlowPercent(latest?.capexIntensity), '%', analysis.metricTrends.capexIntensity, 'CapEx Intensity = CapEx / Revenue。资本开支强度。'],
    ['FCF Stability', formatCashFlowPercent(analysis.stability), 'CV', analysis.metricTrends.stability, 'FCF Stability = 多期 FCF 波动程度。用最近多期 FCF 的变异系数衡量，越低通常越稳定。'],
    ['FCF Yield', formatCashFlowPercent(analysis.valuation.fcfYield), '%', analysis.metricTrends.fcfYield, 'FCF Yield = FCF / Market Cap。自由现金流收益率。'],
    ['P/FCF', formatCashFlowMultiple(analysis.valuation.priceToFcf), 'multiple', analysis.metricTrends.priceToFcf, 'P/FCF = Market Cap / FCF。股价相对于自由现金流的倍数。'],
    ['EV/FCF', formatCashFlowMultiple(analysis.valuation.evToFcf), 'multiple', analysis.metricTrends.evToFcf, 'EV/FCF = Enterprise Value / FCF。企业价值相对于自由现金流的倍数。']
  ];

  return (
    <div className="cash-flow-analysis-panel">
      <div className="gross-margin-summary-grid cash-flow-summary-grid">
        {cards.map(([title, value, unit, status, tooltip]) => (
          <FundamentalMetricCard key={title} title={title} value={value} unit={unit} status={status || 'N/A'} tooltip={tooltip} />
        ))}
      </div>
      <article className="fundamental-panel">
        <h3>Cash Flow</h3>
        <MobileLandscapeChart title="Cash Flow">
          <CashFlowDerivativeChart chart={chart} />
        </MobileLandscapeChart>
      </article>
      <article className="fundamental-panel">
        <h3>TTM Cash Flow</h3>
        <MobileLandscapeChart title="TTM Cash Flow">
          <TtmCashFlowChart chart={ttmChart} />
        </MobileLandscapeChart>
      </article>
    </div>
  );
}

function CapitalAllocationPanel({ history }) {
  const { repurchases, shares, latestRepurchase, latestShares, yearAgoShares, yoyNetChange, tableRows } = calculateCapitalAllocationSummary(history);
  const repurchaseChart = buildSingleSeriesChart(
    repurchases.map((row) => ({ asOfDate: row.fiscalPeriodEnd, amount: row.amount })),
    'amount'
  );
  const sharesChart = buildSharesOutstandingChart(shares);
  const cards = [
    ['Latest Quarterly Repurchase', formatLarge(latestRepurchase?.amount), 'cash paid for common-stock repurchases'],
    ['TTM Repurchases', formatLarge(latestRepurchase?.ttmAmount), 'only shown when four reported quarters are continuous'],
    ['Latest Shares Outstanding', formatLarge(latestShares?.sharesOutstanding), latestShares?.asOfDate ? `reported ${latestShares.asOfDate}` : 'SEC cover-page observation'],
    ['YoY Net Share Change', yoyNetChange == null ? '--' : `${yoyNetChange >= 0 ? '+' : ''}${formatLarge(yoyNetChange)}`, yearAgoShares ? `vs. ${yearAgoShares.asOfDate}` : 'a comparable observation is unavailable']
  ];
  return (
    <div className="cash-flow-analysis-panel capital-allocation-panel">
      <div className="gross-margin-summary-grid cash-flow-summary-grid">
        {cards.map(([title, value, detail]) => (
          <FundamentalMetricCard key={title} title={title} value={value} unit="" status="SEC" tooltip={detail} />
        ))}
      </div>
      <p className="muted chart-caption capital-allocation-disclaimer">
        Share-count changes reflect the net effect of repurchases, issuance, and employee equity compensation; they are not a direct buyback measure. Shares outstanding uses SEC-reported cover-page facts only and is adjusted to the current split basis. Missing reports remain gaps.
      </p>
      <article className="fundamental-panel">
        <h3>Share Repurchases</h3>
        <MobileLandscapeChart title="Share Repurchases">
          <SingleSeriesChart chart={repurchaseChart} valueFormatter={formatLarge} barClass="capital-allocation-bar" />
        </MobileLandscapeChart>
      </article>
      <article className="fundamental-panel">
        <h3>Shares Outstanding</h3>
        <MobileLandscapeChart title="Shares Outstanding">
          <SharesOutstandingChart chart={sharesChart} />
        </MobileLandscapeChart>
      </article>
      <div className="table-wrap history-table-wrap desktop-only-table capital-allocation-table">
        <table>
          <thead><tr><th>Quarter / report date</th><th>Repurchase cash</th><th>TTM repurchases</th><th>Shares report date</th><th>Split-adjusted shares</th><th>Source</th></tr></thead>
          <tbody>
            {tableRows.map((row) => (
              <tr key={row.date}>
                <td>{row.date}</td>
                <td>{formatLarge(row.repurchase?.amount)}</td>
                <td>{formatLarge(row.repurchase?.ttmAmount)}</td>
                <td>{row.shares?.asOfDate || '--'}</td>
                <td>{formatLarge(row.shares?.sharesOutstanding)}</td>
                <td>{row.shares?.source?.sourceName || row.repurchase?.source?.sourceName || '--'}</td>
              </tr>
            ))}
            {!tableRows.length ? <tr><td colSpan="6" className="muted">No SEC capital allocation observations in this range.</td></tr> : null}
          </tbody>
        </table>
      </div>
      <div className="market-mobile-records capital-allocation-mobile-records">
        <h3>Recent SEC observations</h3>
        <div className="mobile-record-list">
          {tableRows.slice(0, 12).map((row) => (
            <article key={`capital-allocation-${row.date}`} className="record-card">
              <div className="record-card-head"><span className="record-card-symbol">{row.date}</span><strong>{row.shares ? 'SEC shares' : 'SEC repurchase'}</strong></div>
              <div className="record-card-metrics">
                <span><small>Repurchase cash</small><strong>{formatLarge(row.repurchase?.amount)}</strong></span>
                <span><small>TTM repurchases</small><strong>{formatLarge(row.repurchase?.ttmAmount)}</strong></span>
                <span><small>Split-adjusted shares</small><strong>{formatLarge(row.shares?.sharesOutstanding)}</strong></span>
              </div>
            </article>
          ))}
        </div>
      </div>
    </div>
  );
}

function CapitalReturnTrendChart({ chart, title, lineClass, averageClass, dotClass, averageDotClass, description }) {
  if (!chart.hasData) return <p className="muted">No {title} data in this range.</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart capital-return-chart" role="img" aria-label={`${title} capital return trend`}>
        <line x1={chart.plotLeft} y1={chart.plotBottom} x2={chart.plotRight} y2={chart.plotBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.plotTop} x2={chart.plotLeft} y2={chart.plotBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.plotTop + 4} textAnchor="end" className="chart-tick">{formatCapitalPercent(chart.maxValue)}</text>
        <text x={chart.plotLeft - 8} y={chart.plotBottom} textAnchor="end" className="chart-tick">{formatCapitalPercent(chart.minValue)}</text>
        <text x={chart.plotLeft} y={chart.plotTop - 8} className="chart-label">{title}</text>
        <path d={chart.paths.value} className={lineClass} />
        <path d={chart.paths.average} className={averageClass} />
        {chart.labels.map((tick) => tick.show ? (
          <text key={`${tick.label}-${tick.x}`} x={tick.x} y={chart.plotBottom + 17} textAnchor="middle" className="chart-tick-bottom">{tick.label}</text>
        ) : null)}
      </svg>
      <div className="legend-row">
        <span><i className={`dot ${dotClass}`} /> {title}</span>
        <span><i className={`dot ${averageDotClass}`} /> 5Y Avg {title}</span>
      </div>
      <p className="muted chart-caption">
        {description}
      </p>
    </>
  );
}

function IncrementalRoicChart({ chart }) {
  if (!chart.hasData) return <p className="muted">{chart.noDataText}</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart incremental-roic-chart" role="img" aria-label="Incremental ROIC chart">
        <line x1={chart.plotLeft} y1={chart.zeroY} x2={chart.plotRight} y2={chart.zeroY} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.plotTop} x2={chart.plotLeft} y2={chart.plotBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.plotTop + 4} textAnchor="end" className="chart-tick">{formatCapitalPercent(chart.maxValue)}</text>
        <text x={chart.plotLeft - 8} y={chart.plotBottom} textAnchor="end" className="chart-tick">{formatCapitalPercent(chart.minValue)}</text>
        <text x={chart.plotLeft} y={chart.plotTop - 8} className="chart-label">Incremental ROIC = ΔNOPAT / ΔInvested Capital</text>
        {chart.bars.map((bar) => (
          <g key={`${bar.date}-${bar.status}`}>
            {bar.abnormal ? (
              <rect x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="2" className="incremental-roic-bar-abnormal">
                <title>{bar.reason}</title>
              </rect>
            ) : bar.value != null ? (
              <rect x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="3" className={[
                'incremental-roic-bar',
                bar.value >= 0 ? 'positive-bar' : 'negative-bar',
                bar.clipped ? 'incremental-roic-bar-clipped' : ''
              ].filter(Boolean).join(' ')}>
                <title>{bar.clipped ? `${formatCapitalPercent(bar.value)} clipped for scale` : formatCapitalPercent(bar.value)}</title>
              </rect>
            ) : null}
            {bar.showLabel ? <text x={bar.labelX} y={chart.plotBottom + 17} textAnchor="middle" className="chart-tick-bottom">{bar.label}</text> : null}
          </g>
        ))}
      </svg>
      <p className="muted chart-caption">
        Incremental ROIC can be unstable when change in invested capital is very small, negative, or affected by major balance-sheet adjustments.
      </p>
    </>
  );
}

function CapeTermStructureChart({ chart }) {
  if (!chart.hasData) return <p className="muted">{chart.noDataText}</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart cape-term-chart" role="img" aria-label="CAPE PE term structure">
        <line x1={chart.plotLeft} y1={chart.plotBottom} x2={chart.plotRight} y2={chart.plotBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.plotTop} x2={chart.plotLeft} y2={chart.plotBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.plotTop + 4} textAnchor="end" className="chart-tick">{formatCapeMultiple(chart.maxValue)}</text>
        <text x={chart.plotLeft - 8} y={chart.plotBottom} textAnchor="end" className="chart-tick">{formatCapeMultiple(chart.minValue)}</text>
        <text x={chart.plotLeft} y={chart.plotTop - 8} className="chart-label">PE</text>
        <path d={chart.path} className="cape-line-ttm" />
        {chart.points.map((point) => (
          <g key={point.label}>
            {point.value != null ? (
              <>
                <circle cx={point.x} cy={point.y} r="4" className="cape-point" />
                <text x={point.x} y={Math.max(point.y - 10, chart.plotTop + 12)} textAnchor="middle" className="chart-point-label">{formatCapeMultiple(point.value)}</text>
              </>
            ) : null}
            <text x={point.x} y={chart.plotBottom + 20} textAnchor="middle" className="chart-tick-bottom">{point.label}</text>
          </g>
        ))}
      </svg>
      <div className="legend-row">
        <span><i className="dot dot-cape-ttm" /> PE by earnings horizon</span>
      </div>
    </>
  );
}

function HistoricalCapeChart({ chart }) {
  if (!chart.hasData) return <p className="muted">{chart.noDataText}</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart historical-cape-chart" role="img" aria-label="Historical CAPE valuation chart">
        <line x1={chart.plotLeft} y1={chart.plotBottom} x2={chart.plotRight} y2={chart.plotBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.plotTop} x2={chart.plotLeft} y2={chart.plotBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.plotTop + 4} textAnchor="end" className="chart-tick">{formatCapeMultiple(chart.maxValue)}</text>
        <text x={chart.plotLeft - 8} y={chart.plotBottom} textAnchor="end" className="chart-tick">{formatCapeMultiple(chart.minValue)}</text>
        <text x={chart.plotLeft} y={chart.plotTop - 8} className="chart-label">Historical PE / CAPE</text>
        <path d={chart.paths.ttmPe} className="cape-line-ttm" />
        <path d={chart.paths.pe3y} className="cape-line-3y" />
        <path d={chart.paths.pe5y} className="cape-line-5y" />
        <path d={chart.paths.cape10y} className="cape-line-10y" />
        {chart.labels.map((tick) => tick.show ? (
          <text key={`${tick.label}-${tick.x}`} x={tick.x} y={chart.plotBottom + 17} textAnchor="middle" className="chart-tick-bottom">{tick.label}</text>
        ) : null)}
      </svg>
      <div className="legend-row">
        <span><i className="dot dot-cape-ttm" /> TTM PE</span>
        <span><i className="dot dot-cape-3y" /> 3Y PE</span>
        <span><i className="dot dot-cape-5y" /> 5Y PE</span>
        <span><i className="dot dot-cape-10y" /> 10Y CAPE</span>
      </div>
    </>
  );
}

function CapeAnalysisPanel({ analysis }) {
  const latest = analysis.latest;
  const termChart = buildCapeTermStructureChart(latest);
  const historicalChart = buildHistoricalCapeChart(analysis.series);
  const cycle = latest?.cyclePosition;
  const valuationSnapshot = [
    ['TTM PE', formatCapeMultiple(latest?.ttmPe)],
    ['3Y PE', formatCapeMultiple(latest?.pe3y)],
    ['5Y PE', formatCapeMultiple(latest?.pe5y)],
    ['10Y CAPE', formatCapeMultiple(latest?.cape10y)]
  ];
  const normalizedEarnings = [
    ['EPS TTM', latest?.epsTtm == null ? 'N/A' : `$${latest.epsTtm.toFixed(2)}`],
    ['EPS 3Y Avg', latest?.avgEps3y == null ? 'N/A' : `$${latest.avgEps3y.toFixed(2)}`],
    ['EPS 5Y Avg', latest?.avgEps5y == null ? 'N/A' : `$${latest.avgEps5y.toFixed(2)}`],
    ['EPS 10Y Avg', latest?.avgEps10y == null ? 'N/A' : `$${latest.avgEps10y.toFixed(2)}`],
    ['Earnings Cycle Ratio', formatCapeMultiple(latest?.earningsCycleRatio)]
  ];
  const percentileRows = [
    ['TTM PE', formatCapePercentile(analysis.percentiles.ttmPe)],
    ['3Y PE', formatCapePercentile(analysis.percentiles.pe3y)],
    ['5Y PE', formatCapePercentile(analysis.percentiles.pe5y)],
    ['10Y CAPE', formatCapePercentile(analysis.percentiles.cape10y)]
  ];
  const summaryRows = [
    ['Quality', analysis.summary.quality],
    ['Current Earnings', analysis.summary.currentEarnings],
    ['Valuation', analysis.summary.valuation],
    ['Cycle Position', analysis.summary.cyclePosition],
    ['Risk', analysis.summary.risk]
  ];
  const cards = [
    ['TTM PE', formatCapeMultiple(latest?.ttmPe), 'multiple', null, 'Current market cap divided by TTM net income.'],
    ['3Y PE', formatCapeMultiple(latest?.pe3y), 'multiple', null, 'Current market cap divided by average net income of the last 3 years.'],
    ['5Y PE', formatCapeMultiple(latest?.pe5y), 'multiple', null, 'Current market cap divided by average net income of the last 5 years.'],
    ['10Y CAPE', formatCapeMultiple(latest?.cape10y), 'multiple', null, 'Current market cap divided by average net income of the last 10 years.'],
    ['Cycle Ratio', formatCapeMultiple(latest?.earningsCycleRatio), 'EPS TTM / 10Y Avg EPS', cycle?.label, 'Measures current EPS against long-term normalized EPS.']
  ];

  return (
    <div className="cape-analysis-panel">
      <div className="gross-margin-summary-grid capital-summary-grid">
        {cards.map(([title, value, unit, status, tooltip]) => (
          <FundamentalMetricCard key={title} title={title} value={value} unit={unit} status={status} tooltip={tooltip} />
        ))}
      </div>

      <div className="capital-decomposition-grid">
        <article className="gross-margin-interpretation">
          <h3>Current Valuation Snapshot</h3>
          <table className="compact-metric-table">
            <tbody>{valuationSnapshot.map(([label, value]) => <tr key={label}><td>{label}</td><td>{value}</td></tr>)}</tbody>
          </table>
        </article>
        <article className="gross-margin-interpretation">
          <h3>Normalized Earnings</h3>
          <table className="compact-metric-table">
            <tbody>{normalizedEarnings.map(([label, value]) => <tr key={label}><td>{label}</td><td>{value}</td></tr>)}</tbody>
          </table>
        </article>
      </div>

      <article className="fundamental-panel">
        <h3>PE Term Structure</h3>
        <MobileLandscapeChart title="PE Term Structure">
          <CapeTermStructureChart chart={termChart} />
          <p className="muted chart-caption">{analysis.termStructureDiagnostic}</p>
        </MobileLandscapeChart>
      </article>

      <article className="gross-margin-interpretation cape-cycle-diagnostic">
        <h3>Cycle Position Diagnostics</h3>
        <span className={`cape-cycle-badge cape-cycle-badge-${cycle?.status || 'insufficient-data'}`}>{cycle?.label || 'N/A'}</span>
        <p>{cycle?.description || '10Y normalized EPS is unavailable.'}</p>
      </article>

      <article className="fundamental-panel">
        <h3>Historical CAPE Chart</h3>
        <MobileLandscapeChart title="Historical CAPE Chart">
          <HistoricalCapeChart chart={historicalChart} />
          <p className="muted chart-caption">{analysis.assumptions.historicalSharesNote}</p>
        </MobileLandscapeChart>
      </article>

      <div className="capital-decomposition-grid">
        <article className="gross-margin-interpretation">
          <h3>Percentile Analysis</h3>
          <table className="compact-metric-table">
            <tbody>{percentileRows.map(([label, value]) => <tr key={label}><td>{label}</td><td>{value}</td></tr>)}</tbody>
          </table>
        </article>
        <article className="gross-margin-interpretation">
          <h3>Investment Interpretation Summary</h3>
          <table className="compact-metric-table">
            <tbody>{summaryRows.map(([label, value]) => <tr key={label}><td>{label}</td><td>{value}</td></tr>)}</tbody>
          </table>
        </article>
      </div>
    </div>
  );
}

function CapitalEfficiencyPanel({ analysis }) {
  const roeChart = buildCapitalReturnTrendChart(analysis.series, 'roe', 'avgRoe5y');
  const roicChart = buildCapitalReturnTrendChart(analysis.series, 'roic', 'avgRoic5y');
  const latest = analysis.latest;
  const summaryCards = [
    ['Latest ROE', formatCapitalPercent(latest?.roe), '%', 'TTM net income divided by shareholders equity.'],
    ['Latest ROIC', formatCapitalPercent(latest?.roic), '%', 'TTM NOPAT divided by average invested capital when available.'],
    ['5Y Avg ROE', formatCapitalPercent(latest?.avgRoe5y), '%', 'Rolling 20-quarter average ROE.'],
    ['5Y Avg ROIC', formatCapitalPercent(latest?.avgRoic5y), '%', 'Rolling 20-quarter average ROIC.'],
    ['3Y Incremental ROIC', formatCapitalPercent(latest?.incrementalRoic3y?.value), '%', latest?.incrementalRoic3y?.reason || '3-year change in TTM NOPAT divided by 3-year change in invested capital.']
  ];
  const roeDecomposition = [
    ['Net Profit Margin', formatCapitalPercent(latest?.netProfitMargin), 'Net income / revenue.'],
    ['Asset Turnover', 'N/A', latest?.assetTurnoverNote || 'Total assets are not available.'],
    ['Equity Multiplier', formatCapitalMultiple(latest?.equityMultiplier), latest?.equityMultiplierNote || 'Average assets / average equity.']
  ];
  const roicDecomposition = [
    ['NOPAT Margin', formatCapitalPercent(latest?.nopatMargin), 'NOPAT / revenue.'],
    ['Invested Capital Turnover', formatCapitalMultiple(latest?.investedCapitalTurnover), 'Revenue / invested capital.']
  ];
  const unavailable = (reason) => <span className="metric-unavailable" title={reason}>Unavailable</span>;
  const diagnostics = [
    {
      group: 'Balance Sheet Leverage',
      rows: [
        ['Debt / Equity', formatCapitalMultiple(analysis.diagnostics.debtToEquity)],
        ['Net Debt / EBITDA', analysis.diagnostics.netDebtToEbitda == null
          ? unavailable('EBITDA is not available in the current market data feed.')
          : formatCapitalMultiple(analysis.diagnostics.netDebtToEbitda)]
      ]
    },
    {
      group: 'Debt Service Capacity',
      rows: [
        ['Interest Coverage', analysis.diagnostics.interestCoverage == null
          ? unavailable('Interest expense is not available in the current market data feed.')
          : formatCapitalMultiple(analysis.diagnostics.interestCoverage)]
      ]
    },
    {
      group: 'Cash Flow Quality',
      rows: [
        ['FCF Conversion', formatCapitalPercent(analysis.diagnostics.fcfConversion)]
      ]
    },
    {
      group: 'Capital Efficiency Stability',
      rows: [
        ['ROE Volatility', formatCapitalPercent(analysis.diagnostics.roeVolatility)],
        ['10Y Avg ROE', formatCapitalPercent(analysis.diagnostics.avgRoe10y)],
        ['10Y Avg ROIC', formatCapitalPercent(analysis.diagnostics.avgRoic10y)]
      ]
    },
    {
      group: 'Valuation / Hurdle Rate',
      rows: [
        ['ROIC - WACC Spread', unavailable('WACC is not available in the current market data feed.')]
      ]
    }
  ];

  return (
    <div className="capital-efficiency-panel">
      <div className="gross-margin-summary-grid capital-summary-grid">
        {summaryCards.map(([title, value, unit, tooltip]) => (
          <FundamentalMetricCard key={title} title={title} value={value} unit={unit} tooltip={tooltip} />
        ))}
      </div>

      <article className="fundamental-panel">
        <h3>ROE Trend</h3>
        <MobileLandscapeChart title="ROE Trend">
          <CapitalReturnTrendChart
            chart={roeChart}
            title="ROE"
            lineClass="capital-line-roe"
            averageClass="capital-line-roe-average"
            dotClass="dot-roe"
            averageDotClass="dot-roe-average"
            description="ROE measures return on shareholder equity."
          />
        </MobileLandscapeChart>
      </article>

      <article className="fundamental-panel">
        <h3>ROIC Trend</h3>
        <MobileLandscapeChart title="ROIC Trend">
          <CapitalReturnTrendChart
            chart={roicChart}
            title="ROIC"
            lineClass="capital-line-roic"
            averageClass="capital-line-roic-average"
            dotClass="dot-roic"
            averageDotClass="dot-roic-average"
            description="ROIC measures return on operating invested capital and is less affected by leverage."
          />
        </MobileLandscapeChart>
      </article>

      <div className="capital-decomposition-grid">
        <article className="gross-margin-interpretation">
          <h3>ROE Decomposition</h3>
          <table className="compact-metric-table">
            <tbody>
              {roeDecomposition.map(([label, value, tooltip]) => (
                <tr key={label} title={tooltip}><td>{label}</td><td>{value}</td></tr>
              ))}
            </tbody>
          </table>
        </article>
        <article className="gross-margin-interpretation">
          <h3>ROIC Decomposition</h3>
          <table className="compact-metric-table">
            <tbody>
              {roicDecomposition.map(([label, value, tooltip]) => (
                <tr key={label} title={tooltip}><td>{label}</td><td>{value}</td></tr>
              ))}
            </tbody>
          </table>
        </article>
      </div>

      <article className="gross-margin-interpretation">
        <h3>Interpretation</h3>
        <p>{analysis.interpretation}</p>
      </article>

      <article className="capital-diagnostics">
        <h3>Advanced Diagnostics</h3>
        <div className="capital-diagnostics-grid">
          {diagnostics.map((section) => (
            <section className="capital-diagnostics-group" key={section.group}>
              <h4>{section.group}</h4>
              <table className="compact-metric-table">
                <tbody>
                  {section.rows.map(([label, value]) => (
                    <tr key={label}><td>{label}</td><td>{value}</td></tr>
                  ))}
                </tbody>
              </table>
            </section>
          ))}
        </div>
      </article>
    </div>
  );
}

function EpsChart({ chart }) {
  if (!chart.hasData) return <p className="muted">{chart.noDataText || 'No EPS data in this range.'}</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart eps-chart" role="img" aria-label={chart.ariaLabel}>
        <line x1={chart.plotLeft} y1={chart.mainBottom} x2={chart.plotRight} y2={chart.mainBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.mainTop} x2={chart.plotLeft} y2={chart.mainBottom} className="chart-axis" />
        <line x1={chart.plotRight} y1={chart.mainTop} x2={chart.plotRight} y2={chart.mainBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.mainTop + 4} textAnchor="end" className="chart-tick">${chart.epsMax.toFixed(2)}</text>
        <text x={chart.plotLeft - 8} y={chart.mainBottom} textAnchor="end" className="chart-tick">${chart.epsMin.toFixed(2)}</text>
        <text x={chart.plotLeft} y={chart.mainTop - 8} className="chart-label">{chart.axisLabel}</text>
        <text x={chart.plotRight + 8} y={chart.mainTop + 4} className="chart-tick">{chart.growthMax.toFixed(1)}%</text>
        <text x={chart.plotRight + 8} y={chart.mainBottom} className="chart-tick">{chart.growthMin.toFixed(1)}%</text>
        {chart.bars.map((bar) => (
          <g key={bar.date}>
            {bar.eps != null ? <rect x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="3" className={bar.forecast ? 'fundamental-bar-forecast' : 'fundamental-bar'} /> : null}
            {bar.showLabel ? <text x={bar.x + bar.width / 2} y={chart.subBottom + 16} textAnchor="middle" className="chart-tick-bottom">{bar.label}</text> : null}
          </g>
        ))}
        <path d={chart.actualGrowthPath} className="chart-line-growth" />
        <path d={chart.forecastGrowthPath} className="chart-line-growth-forecast" />
        {chart.growthPoints.map((point) => <circle key={`${point.x}-${point.y}`} cx={point.x} cy={point.y} r="3" className={point.forecast ? 'growth-point-forecast' : 'growth-point'} />)}
        <text x={chart.plotLeft} y={chart.subTop - 12} className="chart-label">EPS growth acceleration (percentage points)</text>
        <text x={chart.plotLeft - 8} y={chart.subTop + 4} textAnchor="end" className="chart-tick">{chart.accelMax.toFixed(1)} pp</text>
        <text x={chart.plotLeft - 8} y={chart.subBottom} textAnchor="end" className="chart-tick">{chart.accelMin.toFixed(1)} pp</text>
        <line x1={chart.plotLeft} y1={chart.subBottom} x2={chart.plotRight} y2={chart.subBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.subTop} x2={chart.plotLeft} y2={chart.subBottom} className="chart-axis" />
        {chart.accelerationBars.map((bar) => <rect key={`${bar.x}-${bar.value}`} x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="2" className={[
          'acceleration-bar',
          bar.forecast ? 'acceleration-bar-forecast' : '',
          bar.value >= 0 ? 'positive-bar' : 'negative-bar',
        ].filter(Boolean).join(' ')} />)}
      </svg>
      <div className="legend-row">
        <span><i className="dot dot-fundamental" /> {chart.actualLegend}</span>
        <span><i className="dot dot-forecast" /> {chart.forecastLegend}</span>
        <span><i className="dot dot-growth" /> {chart.growthLegend}</span>
        <span><i className="dot dot-growth-forecast" /> Forecast growth</span>
        <span><i className="dot dot-acceleration" /> Growth acceleration</span>
      </div>
    </>
  );
}

function GrossMarginDerivativeChart({ chart }) {
  if (!chart.hasData) return <p className="muted">{chart.noDataText || '数据不足'}</p>;
  return (
    <>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} className="asset-chart fundamental-chart eps-chart gross-margin-derivative-chart" role="img" aria-label={chart.title}>
        <line x1={chart.plotLeft} y1={chart.mainBottom} x2={chart.plotRight} y2={chart.mainBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.mainTop} x2={chart.plotLeft} y2={chart.mainBottom} className="chart-axis" />
        <line x1={chart.plotRight} y1={chart.mainTop} x2={chart.plotRight} y2={chart.mainBottom} className="chart-axis" />
        <text x={chart.plotLeft - 8} y={chart.mainTop + 4} textAnchor="end" className="chart-tick">{formatGrossMarginPercent(chart.levelMax)}</text>
        <text x={chart.plotLeft - 8} y={chart.mainBottom} textAnchor="end" className="chart-tick">{formatGrossMarginPercent(chart.levelMin)}</text>
        <text x={chart.plotLeft} y={chart.mainTop - 8} className="chart-label">{chart.axisLabel}</text>
        <text x={chart.plotRight + 8} y={chart.mainTop + 4} className="chart-tick">{formatPercentagePoints(chart.changeMax)}</text>
        <text x={chart.plotRight + 8} y={chart.mainBottom} className="chart-tick">{formatPercentagePoints(chart.changeMin)}</text>
        {chart.bars.map((bar) => (
          <g key={`${bar.label}-${bar.value}`}>
            {bar.value != null ? <rect x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="3" className="fundamental-bar" /> : null}
            {bar.showLabel ? <text x={bar.x + bar.width / 2} y={chart.subBottom + 16} textAnchor="middle" className="chart-tick-bottom">{bar.label}</text> : null}
          </g>
        ))}
        <path d={chart.changePath} className="chart-line-growth" />
        {chart.changePoints.map((point) => <circle key={`${point.x}-${point.y}`} cx={point.x} cy={point.y} r="3" className="growth-point" />)}
        <text x={chart.plotLeft} y={chart.subTop - 12} className="chart-label">{chart.accelerationLabel}</text>
        <text x={chart.plotLeft - 8} y={chart.subTop + 4} textAnchor="end" className="chart-tick">{formatPercentagePoints(chart.accelMax)}</text>
        <text x={chart.plotLeft - 8} y={chart.subBottom} textAnchor="end" className="chart-tick">{formatPercentagePoints(chart.accelMin)}</text>
        <line x1={chart.plotLeft} y1={chart.subBottom} x2={chart.plotRight} y2={chart.subBottom} className="chart-axis" />
        <line x1={chart.plotLeft} y1={chart.subTop} x2={chart.plotLeft} y2={chart.subBottom} className="chart-axis" />
        {chart.accelerationBars.map((bar) => <rect key={`${bar.x}-${bar.value}`} x={bar.x} y={bar.y} width={bar.width} height={bar.height} rx="2" className={[
          'acceleration-bar',
          bar.value >= 0 ? 'positive-bar' : 'negative-bar',
        ].filter(Boolean).join(' ')} />)}
      </svg>
      <div className="legend-row">
        <span><i className="dot dot-fundamental" /> {chart.axisLabel}</span>
        <span><i className="dot dot-growth" /> {chart.changeLabel}</span>
        <span><i className="dot dot-acceleration" /> {chart.accelerationLabel}</span>
      </div>
    </>
  );
}

function GrossMarginAnalysisPanel({ analysis }) {
  const latest = analysis.latest;
  const charts = [
    {
      title: 'Quarterly Gross Margin',
      chart: buildGrossMarginDerivativeChartData(analysis.quarterly, {
        title: 'Quarterly Gross Margin chart with change and acceleration',
        axisLabel: 'Quarterly Gross Margin',
        changeLabel: 'QoQ GM Change',
        accelerationLabel: 'QoQ GM Change Acceleration',
        changeKey: 'qoqChange',
        accelerationKey: 'qoqAcceleration'
      })
    },
    {
      title: 'TTM Gross Margin',
      chart: buildGrossMarginDerivativeChartData(analysis.ttm, {
        title: 'TTM Gross Margin chart with change and acceleration',
        axisLabel: 'TTM Gross Margin',
        changeLabel: 'QoQ TTM GM Change',
        accelerationLabel: 'QoQ TTM GM Change Acceleration'
      })
    },
    {
      title: 'Annual Gross Margin',
      chart: buildGrossMarginDerivativeChartData(analysis.annual, {
        title: 'Annual Gross Margin chart with change and acceleration',
        axisLabel: 'Annual Gross Margin',
        changeLabel: 'YoY Annual GM Change',
        accelerationLabel: 'YoY Annual GM Change Acceleration'
      })
    }
  ];

  const summaryCards = [
    ['Latest Quarterly GM', formatGrossMarginPercent(latest?.quarterlyGrossMargin), '%', classifyGrossMarginTrend(latest?.qoqChange), 'Quarterly gross profit divided by quarterly revenue.'],
    ['Latest TTM GM', formatGrossMarginPercent(latest?.ttmGrossMargin), '%', 'level', 'Last four quarters gross profit divided by last four quarters revenue.'],
    ['QoQ GM Change', formatPercentagePoints(latest?.qoqChange), 'pp', classifyGrossMarginTrend(latest?.qoqChange), 'Current quarterly gross margin minus previous quarter.'],
    ['YoY GM Change', formatPercentagePoints(latest?.yoyChange), 'pp', classifyGrossMarginTrend(latest?.yoyChange), 'Current quarterly gross margin minus the same quarter last year.'],
    ['QoQ GM Acceleration', formatPercentagePoints(latest?.qoqAcceleration), 'pp', latest?.qoqAcceleration == null ? 'N/A' : latest.qoqAcceleration >= 0 ? 'improving faster' : 'slowing', 'Current QoQ change minus prior QoQ change.'],
    ['YoY GM Acceleration', formatPercentagePoints(latest?.yoyAcceleration), 'pp', latest?.yoyAcceleration == null ? 'N/A' : latest.yoyAcceleration >= 0 ? 'improving faster' : 'slowing', 'Current YoY change minus prior YoY change.'],
    ['Incremental GM', formatGrossMarginPercent(latest?.incrementalYoy ?? latest?.incrementalQoq), '%', latest?.incrementalYoy == null && latest?.incrementalQoq == null ? 'not meaningful' : 'available', 'Delta gross profit divided by delta revenue. Hidden when revenue delta is not positive.'],
    ['Gross Profit Growth', formatGrowthPercent(latest?.grossProfitGrowthYoY ?? latest?.grossProfitGrowthQoq), '%', 'growth', 'Gross profit growth versus comparable period.']
  ];

  return (
    <div className="gross-margin-analysis-panel">
      <div className="gross-margin-summary-grid">
        {summaryCards.map(([title, value, unit, status, tooltip]) => (
          <FundamentalMetricCard key={title} title={title} value={value} unit={unit} status={status || 'N/A'} tooltip={tooltip} />
        ))}
      </div>

      {charts.map((item) => (
        <article key={item.title} className="fundamental-panel">
          <h3>{item.title}</h3>
          <MobileLandscapeChart title={item.title}>
            <GrossMarginDerivativeChart chart={item.chart} />
          </MobileLandscapeChart>
        </article>
      ))}

      <article className="gross-margin-interpretation">
        <h3>Interpretation</h3>
        <p>{analysis.interpretation}</p>
      </article>
    </div>
  );
}

function formatDcfMoney(value) {
  const n = toNullableNumber(value);
  if (n == null) return '--';
  const prefix = n < 0 ? '-' : '';
  const abs = Math.abs(n);
  if (abs >= 1_000_000_000) return `${prefix}$${(abs / 1_000_000_000).toFixed(2)}B`;
  if (abs >= 1_000_000) return `${prefix}$${(abs / 1_000_000).toFixed(2)}M`;
  return `${prefix}$${abs.toFixed(2)}`;
}

function formatDcfPercent(value) {
  const n = toNullableNumber(value);
  return n == null ? '--' : `${n.toFixed(1)}%`;
}

function formatDcfRateDecimal(value, decimals = 1) {
  const n = toNullableNumber(value);
  return n == null ? '--' : `${(n * 100).toFixed(decimals)}%`;
}

function formatDcfMultiple(value) {
  const n = toNullableNumber(value);
  return n == null ? '--' : `${n.toFixed(1)}x`;
}

function formatDcfGapStatus(value) {
  if (value === 'below_market') return 'Model below market';
  if (value === 'above_market') return 'Model above market';
  if (value === 'near_market') return 'Near market';
  return 'Unavailable';
}

function formatDiagnosticSeverity(value) {
  if (value === 'critical') return 'Critical';
  if (value === 'warning') return 'Caution';
  return 'Info';
}

function sumLatestActual(rows, field, count = 4) {
  const values = [...(rows || [])]
    .filter((row) => !row.forecast && row[field] != null)
    .sort((a, b) => new Date(a.asOfDate) - new Date(b.asOfDate))
    .slice(-count)
    .map((row) => toNullableNumber(row[field]))
    .filter((value) => value != null);
  return values.length ? values.reduce((sum, value) => sum + value, 0) : null;
}

function averageLatestActual(rows, field, count = 4) {
  const values = [...(rows || [])]
    .filter((row) => !row.forecast && row[field] != null)
    .sort((a, b) => new Date(a.asOfDate) - new Date(b.asOfDate))
    .slice(-count)
    .map((row) => toNullableNumber(row[field]))
    .filter((value) => value != null);
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null;
}

function describeDcfGrowthPath(assumptions) {
  if (assumptions.growthMode === 'linearFade') {
    return `linear fade from ${formatDcfRateDecimal(assumptions.growthRate)} to ${formatDcfRateDecimal(assumptions.terminalGrowthRate)}`;
  }
  if (assumptions.growthMode === 'custom') {
    return 'custom year-by-year FCF growth path';
  }
  return `${formatDcfRateDecimal(assumptions.growthRate)} constant FCF growth`;
}

function DcfValuationPanel({
  assumptions,
  setAssumptions,
  setBaseTouched,
  setDebtTouched,
  setSharesTouched,
  defaults,
  netDebtDefault,
  result,
  latestPrice,
  activeSymbol,
  position,
  fundamentals,
  fcfModes,
  marketAssumptions,
  sharesSaving,
  onSaveSharesOverride,
  onClearSharesOverride
}) {
  const { t } = useTranslation();
  const isMobile = useIsMobile();
  const [mobileDcfSection, setMobileDcfSection] = useState('summary');
  const updateAssumption = (key, value) => {
    if (key === 'baseFreeCashFlow') setBaseTouched(true);
    if (key === 'netDebt') setDebtTouched(true);
    if (key === 'sharesOutstanding') setSharesTouched(true);
    setAssumptions((prev) => ({ ...prev, [key]: value }));
  };

  const updateBaseFreeCashFlow = (value) => {
    setBaseTouched(true);
    setAssumptions((prev) => ({ ...prev, baseFreeCashFlow: value, baseFcfMode: 'userOverride' }));
  };

  const resetBaseFreeCashFlow = () => {
    const computed = fcfModes?.modes?.latestTtm?.value ?? defaults.baseFreeCashFlow;
    if (computed == null) return;
    setBaseTouched(false);
    setAssumptions((prev) => ({
      ...prev,
      baseFcfMode: 'latestTtm',
      baseFreeCashFlow: formatDcfMoneyInput(computed)
    }));
  };

  const selectBaseFcfMode = (mode) => {
    if (mode === 'userOverride') {
      setBaseTouched(true);
      setAssumptions((prev) => ({ ...prev, baseFcfMode: mode }));
      return;
    }
    const value = fcfModes?.modes?.[mode]?.value;
    if (value == null) return;
    setBaseTouched(true);
    setAssumptions((prev) => ({
      ...prev,
      baseFcfMode: mode,
      baseFreeCashFlow: formatDcfMoneyInput(value)
    }));
  };

  const resetSharesOutstanding = () => {
    if (position?.effectiveSharesOutstanding == null) return;
    setSharesTouched(false);
    setAssumptions((prev) => ({ ...prev, sharesOutstanding: String(Number(Number(position.effectiveSharesOutstanding).toFixed(4))) }));
  };

  const saveSharesOverride = () => {
    onSaveSharesOverride?.(assumptions.sharesOutstanding);
  };

  const hasManualSharesOverride = position?.sharesOutstandingOverride != null;
  const sharesSource = hasManualSharesOverride ? 'Manual override' : position?.sharesOutstandingSource || 'Not fetched yet';
  const discountRates = buildDcfRateRange(result.assumptions.discountRate, [-0.02, -0.01, 0, 0.01, 0.02]);
  const terminalGrowthRates = buildDcfRateRange(result.assumptions.terminalGrowthRate, [-0.01, -0.005, 0, 0.005, 0.01]);
  const sensitivity = calculateDcfSensitivityMatrix(result.assumptions, discountRates, terminalGrowthRates, {
    currentPrice: latestPrice,
    baseDiscountRate: result.assumptions.discountRate,
    baseTerminalGrowthRate: result.assumptions.terminalGrowthRate
  });
  const parsedBaseFcf = parseDcfMoneyInput(assumptions.baseFreeCashFlow);
  const reverseDcf = calculateReverseDcf({ ...assumptions, currentPrice: latestPrice });
  const fcfModeKeys = ['latestTtm', 'avg3YearEndTtm', 'avg5YearEndTtm', 'median3YearEndTtm', 'userOverride'];
  const selectedFcfMode = assumptions.baseFcfMode || 'latestTtm';
  const dcfDiagnostics = buildDcfDiagnostics({
    dcfResult: result,
    assumptions,
    currentPrice: latestPrice,
    sharesOutstanding: result.assumptions.sharesOutstanding,
    netDebt: result.assumptions.netDebt,
    baseFcf: result.assumptions.baseFreeCashFlow,
    fcfMode: selectedFcfMode,
    symbol: activeSymbol,
    quoteCurrency: position?.quoteCurrency || position?.currency,
    financialCurrency: fundamentals?.find((row) => row?.currencyCode)?.currencyCode,
    sector: position?.sector,
    industry: position?.industry,
    marketAssumptions
  });
  const actualFundamentals = [...(fundamentals || [])]
    .filter((row) => !row.forecast)
    .sort((a, b) => new Date(a.asOfDate) - new Date(b.asOfDate));
  const latestDebtRow = [...actualFundamentals].reverse().find((row) => row.totalDebt != null);
  const latestGrossDebt = latestDebtRow ? toNullableNumber(latestDebtRow.totalDebt) : null;
  const marketEquity = latestPrice != null && result.assumptions.sharesOutstanding != null
    ? latestPrice * result.assumptions.sharesOutstanding
    : null;
  const wacc = calculateWaccEstimate({
    riskFreeRatePct: assumptions.riskFreeRatePctOverride || marketAssumptions?.riskFreeRate,
    equityRiskPremiumPct: assumptions.equityRiskPremiumPct,
    beta: assumptions.betaOverride || marketAssumptions?.beta,
    manualCostOfDebtPct: assumptions.manualCostOfDebtPct,
    manualTaxRatePct: assumptions.manualTaxRatePct,
    marketEquity,
    grossDebt: latestGrossDebt,
    averageGrossDebt: averageLatestActual(actualFundamentals, 'totalDebt'),
    interestExpense: sumLatestActual(actualFundamentals, 'interestExpense'),
    taxProvision: sumLatestActual(actualFundamentals, 'taxProvision'),
    pretaxIncome: sumLatestActual(actualFundamentals, 'pretaxIncome')
  });
  const fcfComparisons = fcfModeKeys
    .filter((mode) => mode !== 'userOverride')
    .map((mode) => {
      const modeValue = fcfModes?.modes?.[mode];
      const valuation = modeValue?.value == null
        ? null
        : calculateDcfValuation({
          ...assumptions,
          baseFreeCashFlow: formatDcfMoneyInput(modeValue.value),
          currentPrice: latestPrice
        });
      return { mode, modeValue, valuation };
    });
  const projectionYearCount = Math.max(1, Math.min(Number(assumptions.projectionYears) || 5, 20));
  const customGrowthRates = Array.isArray(assumptions.customGrowthRatesPct) ? assumptions.customGrowthRatesPct : [];
  const updateCustomGrowth = (index, value) => {
    setAssumptions((prev) => {
      const next = Array.from({ length: projectionYearCount }, (_, i) => prev.customGrowthRatesPct?.[i] ?? prev.growthRatePct ?? '8');
      next[index] = value;
      return { ...prev, growthMode: 'custom', customGrowthRatesPct: next };
    });
  };
  const useEstimatedWacc = () => {
    if (wacc.estimatedWacc == null) return;
    setAssumptions((prev) => ({ ...prev, discountRatePct: String(Number((wacc.estimatedWacc * 100).toFixed(2))) }));
  };
  const terminalDiscountFactor = result.valid
    ? Math.pow(1 + result.assumptions.discountRate, result.assumptions.projectionYears)
    : null;
  const activeGrowthPathLabel = describeDcfGrowthPath(result.assumptions);

  const snapshotRows = [
    ['Symbol', activeSymbol || '--'],
    ['Current Price', latestPrice == null ? '--' : `$${latestPrice.toFixed(2)}`],
    ['Base FCF', formatDcfMoney(result.assumptions.baseFreeCashFlow)],
    ['Base FCF Source', `${defaults.sourceLabel}${defaults.asOfDate ? ` (${defaults.asOfDate})` : ''}`],
    ['Net Debt', formatDcfMoney(result.assumptions.netDebt ?? netDebtDefault)],
    ['Effective Shares Outstanding', formatLarge(result.assumptions.sharesOutstanding)],
    ['Shares Source', sharesSource],
    ['Shares Updated At', position?.sharesOutstandingUpdatedAt ? String(position.sharesOutstandingUpdatedAt).slice(0, 10) : '--'],
    ['Currency', 'USD']
  ];
  const cards = [
    ['Intrinsic Value / Share', result.intrinsicValuePerShare == null ? '--' : `$${result.intrinsicValuePerShare.toFixed(2)}`, 'DCF equity value divided by effective shares outstanding.', 'highlight'],
    ['Current Price', latestPrice == null ? '--' : `$${latestPrice.toFixed(2)}`, 'Latest close price from loaded price history.', 'highlight'],
    ['Upside / Downside', formatDcfPercent(result.upsideDownsidePct), 'Intrinsic value per share divided by current price minus one.', 'highlight'],
    ['MOS Price', result.marginOfSafetyPrice == null ? '--' : `$${result.marginOfSafetyPrice.toFixed(2)}`, 'Intrinsic value after margin of safety.', 'highlight'],
    ['Enterprise Value', formatDcfMoney(result.enterpriseValue), 'PV of explicit FCF plus PV of terminal value.'],
    ['Equity Value', formatDcfMoney(result.equityValue), 'Equity Value = Enterprise Value - Net Debt.'],
    ['PV Explicit FCF', formatDcfMoney(result.presentValueOfExplicitCashFlows), 'Present value of forecast-period FCF.'],
    ['PV Terminal Value', formatDcfMoney(result.presentValueOfTerminalValue), 'Present value of perpetuity growth terminal value.'],
    ['Terminal Value Weight', formatDcfPercent(result.terminalValueWeightPct), 'Share of enterprise value from terminal value.']
  ];
  const mosFormula = result.intrinsicValuePerShare == null
    ? 'MOS Price = Intrinsic Value x (1 - Margin of Safety)'
    : `${formatDcfMoney(result.intrinsicValuePerShare)} x ${(1 - result.assumptions.marginOfSafety).toFixed(2)} = ${result.marginOfSafetyPrice == null ? '--' : `$${result.marginOfSafetyPrice.toFixed(2)}`}`;

  return (
    <>
      <h2>DCF</h2>
      {isMobile ? (
        <SegmentedControl
          label={t('ui.dcfSections')}
          value={mobileDcfSection}
          onChange={setMobileDcfSection}
          options={[
            { value: 'summary', label: t('ui.dcfSummary') },
            { value: 'assumptions', label: t('ui.dcfAssumptions') },
            { value: 'sensitivity', label: t('ui.dcfSensitivity') },
            { value: 'diagnostics', label: t('ui.dcfDiagnostics') }
          ]}
        />
      ) : null}
      <div className={`dcf-layout ${isMobile ? `is-mobile-dcf-${mobileDcfSection}` : ''}`}>
        <aside className="dcf-assumptions-panel dcf-mobile-assumptions">
          <div className="dcf-panel-header">
            <h3>Company / Data Snapshot</h3>
          </div>
          <table className="compact-metric-table dcf-snapshot-table">
            <tbody>
              {snapshotRows.map(([label, value]) => (
                <tr key={label}><td>{label}</td><td>{value}</td></tr>
              ))}
            </tbody>
          </table>

          <div className="dcf-panel-header">
            <h3>Assumptions</h3>
            <button type="button" className="secondary-button" onClick={resetBaseFreeCashFlow} disabled={defaults.baseFreeCashFlow == null}>
              Use Computed TTM FCF
            </button>
          </div>
          <p className="muted dcf-source">
            Base FCF source: {defaults.sourceLabel}{defaults.asOfDate ? ` (${defaults.asOfDate})` : ''}
          </p>
          <div className="dcf-input-grid">
            <label>
              <span>Base FCF Mode</span>
              <select value={selectedFcfMode} onChange={(event) => selectBaseFcfMode(event.target.value)}>
                {fcfModeKeys.map((mode) => (
                  <option key={mode} value={mode} disabled={mode !== 'userOverride' && fcfModes?.modes?.[mode]?.value == null}>
                    {DCF_FCF_MODE_LABELS[mode]}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>Base FCF</span>
              <input type="text" value={assumptions.baseFreeCashFlow} onChange={(event) => updateBaseFreeCashFlow(event.target.value)} placeholder="129.17B" />
            </label>
            <p className={`dcf-parse-note ${parsedBaseFcf.error ? 'dcf-parse-error' : ''}`}>
              {parsedBaseFcf.error || (parsedBaseFcf.value == null ? 'Parsed as: --' : `Parsed as: ${formatDcfMoney(parsedBaseFcf.value)}`)}
            </p>
            <div className="dcf-mode-comparison">
              {fcfComparisons.map(({ mode, modeValue, valuation }) => (
                <div key={mode}>
                  <span>{DCF_FCF_MODE_LABELS[mode]}</span>
                  <strong>{valuation?.intrinsicValuePerShare == null ? '--' : `$${valuation.intrinsicValuePerShare.toFixed(2)}`}</strong>
                  <small>{modeValue?.value == null ? 'Unavailable' : formatDcfMoney(modeValue.value)}</small>
                </div>
              ))}
            </div>
            <label>
              <span>FCF Growth Rate %</span>
              <input type="number" step="0.1" value={assumptions.growthRatePct} onChange={(event) => updateAssumption('growthRatePct', event.target.value)} />
            </label>
            <label>
              <span>Growth Mode</span>
              <select value={assumptions.growthMode || 'constant'} onChange={(event) => updateAssumption('growthMode', event.target.value)}>
                <option value="constant">Constant Growth</option>
                <option value="linearFade">Linear Fade to Terminal</option>
                <option value="custom">Custom Year-by-Year</option>
              </select>
            </label>
            {assumptions.growthMode === 'custom' ? (
              <div className="dcf-custom-growth-grid">
                {Array.from({ length: projectionYearCount }, (_, index) => (
                  <label key={index}>
                    <span>Y{index + 1}</span>
                    <input
                      type="number"
                      step="0.1"
                      value={customGrowthRates[index] ?? assumptions.growthRatePct}
                      onChange={(event) => updateCustomGrowth(index, event.target.value)}
                    />
                  </label>
                ))}
              </div>
            ) : null}
            <label>
              <span>Discount Rate %</span>
              <input type="number" step="0.1" value={assumptions.discountRatePct} onChange={(event) => updateAssumption('discountRatePct', event.target.value)} />
            </label>
            <label>
              <span>Terminal Growth %</span>
              <input type="number" step="0.1" value={assumptions.terminalGrowthRatePct} onChange={(event) => updateAssumption('terminalGrowthRatePct', event.target.value)} />
            </label>
            <label>
              <span>Projection Years</span>
              <input type="number" min="1" max="20" value={assumptions.projectionYears} onChange={(event) => updateAssumption('projectionYears', event.target.value)} />
            </label>
            <label>
              <span>Effective Shares Outstanding</span>
              <input type="number" value={assumptions.sharesOutstanding} onChange={(event) => updateAssumption('sharesOutstanding', event.target.value)} />
            </label>
            <div className="dcf-shares-tools">
              <button type="button" className="secondary-button" disabled={!activeSymbol || sharesSaving || assumptions.sharesOutstanding === ''} onClick={saveSharesOverride}>
                Save Override
              </button>
              <button type="button" className="secondary-button" disabled={!activeSymbol || sharesSaving || !hasManualSharesOverride} onClick={onClearSharesOverride}>
                Clear Override
              </button>
              <button type="button" className="secondary-button" disabled={position?.effectiveSharesOutstanding == null} onClick={resetSharesOutstanding}>
                Use DB
              </button>
            </div>
            <p className="muted dcf-source">
              Per-share valuation uses effective shares outstanding. Use override if you want diluted shares or another share-count assumption.
              <br />
              Shares source: {sharesSource}
              {position?.sharesOutstandingUpdatedAt ? ` (${String(position.sharesOutstandingUpdatedAt).slice(0, 10)})` : ''}
              {position?.sharesOutstanding != null ? ` | Yahoo: ${formatLarge(position.sharesOutstanding)}` : ''}
            </p>
            <label>
              <span>Net Debt</span>
              <input type="number" value={assumptions.netDebt} onChange={(event) => updateAssumption('netDebt', event.target.value)} />
            </label>
            <label>
              <span>Margin of Safety %</span>
              <input type="number" min="0" max="95" step="1" value={assumptions.marginOfSafetyPct} onChange={(event) => updateAssumption('marginOfSafetyPct', event.target.value)} />
            </label>
            <p className="muted dcf-formula-note">
              MOS Price = Intrinsic Value x (1 - Margin of Safety)
              <br />
              {mosFormula}
            </p>
          </div>

          <section className="dcf-helper-section">
            <h3>Discount Rate Builder</h3>
            <div className="dcf-input-grid">
              <label>
                <span>Risk-free Rate %</span>
                <input
                  type="number"
                  step="0.01"
                  value={assumptions.riskFreeRatePctOverride}
                  placeholder={marketAssumptions?.riskFreeRate == null ? 'Manual' : String(marketAssumptions.riskFreeRate)}
                  onChange={(event) => updateAssumption('riskFreeRatePctOverride', event.target.value)}
                />
              </label>
              <p className="muted dcf-source">
                {marketAssumptions?.riskFreeRate == null
                  ? 'Risk-free rate unavailable. Enter a manual value.'
                  : `${marketAssumptions.riskFreeMaturity || '10Y'} ${formatDcfRateDecimal(wacc.riskFreeRate)} as of ${marketAssumptions.riskFreeDate || '--'} (${marketAssumptions.riskFreeSource || 'U.S. Treasury 10Y par yield'})`}
              </p>
              <label>
                <span>Equity Risk Premium %</span>
                <input type="number" step="0.1" value={assumptions.equityRiskPremiumPct} onChange={(event) => updateAssumption('equityRiskPremiumPct', event.target.value)} />
              </label>
              <label>
                <span>Beta</span>
                <input
                  type="number"
                  step="0.01"
                  value={assumptions.betaOverride}
                  placeholder={marketAssumptions?.beta == null ? 'Manual' : String(marketAssumptions.beta)}
                  onChange={(event) => updateAssumption('betaOverride', event.target.value)}
                />
              </label>
              <p className="muted dcf-source">
                Beta source: {marketAssumptions?.beta == null ? 'Unavailable / manual fallback' : marketAssumptions.betaSource || 'Yahoo quoteSummary best effort'}
              </p>
              <label>
                <span>Manual Cost of Debt %</span>
                <input
                  type="number"
                  step="0.1"
                  value={assumptions.manualCostOfDebtPct}
                  placeholder="Auto"
                  onChange={(event) => updateAssumption('manualCostOfDebtPct', event.target.value)}
                />
              </label>
              <label>
                <span>Manual Tax Rate %</span>
                <input
                  type="number"
                  step="0.1"
                  value={assumptions.manualTaxRatePct}
                  placeholder="Auto"
                  onChange={(event) => updateAssumption('manualTaxRatePct', event.target.value)}
                />
              </label>
            </div>
            <div className="dcf-wacc-grid">
              <div><span>Cost of Equity</span><strong>{formatDcfRateDecimal(wacc.costOfEquity)}</strong></div>
              <div><span>Cost of Debt</span><strong>{formatDcfRateDecimal(wacc.afterTaxCostOfDebt)}</strong></div>
              <div><span>Tax Rate</span><strong>{formatDcfRateDecimal(wacc.taxRate)}</strong></div>
              <div><span>Debt / Capital</span><strong>{formatDcfRateDecimal(wacc.debtWeight)}</strong></div>
              <div><span>Equity / Capital</span><strong>{formatDcfRateDecimal(wacc.equityWeight)}</strong></div>
              <div><span>Estimated WACC</span><strong>{formatDcfRateDecimal(wacc.estimatedWacc)}</strong></div>
            </div>
            <button type="button" className="secondary-button" disabled={wacc.estimatedWacc == null} onClick={useEstimatedWacc}>
              Use WACC
            </button>
            <p className="muted dcf-source">
              WACC weights use market equity and gross debt. Net debt is only used in the EV-to-equity bridge.
              {wacc.costOfDebtWarning ? ` ${wacc.costOfDebtWarning}` : ''}
              {wacc.taxRateWarning ? ` ${wacc.taxRateWarning}` : ''}
            </p>
          </section>
          <p className="muted dcf-note">
            DCF is calculated in the browser from loaded fundamentals. No backend valuation logic is used.
            <br />
            {DCF_UNIT_NOTE}
            <br />
            {CAPEX_SIGN_NOTE}
            <br />
            Equity Value = Enterprise Value - Net Debt.
          </p>
        </aside>

        <div className="dcf-results-panel">
          <section className="dcf-section dcf-mobile-summary">
            <h3>Valuation Summary</h3>
            <div className="dcf-result-grid">
              {cards.map(([title, value, tooltip, emphasis]) => (
                <article key={title} className={`dcf-result-card ${emphasis ? 'dcf-result-card-highlight' : ''}`} title={tooltip}>
                  <span>{title}</span>
                  <strong>{value}</strong>
                </article>
              ))}
            </div>
            <div className="dcf-bridge">
              <div><span>PV Explicit FCF</span><strong>{formatDcfMoney(result.presentValueOfExplicitCashFlows)}</strong></div>
              <div><span>+ PV Terminal Value</span><strong>{formatDcfMoney(result.presentValueOfTerminalValue)}</strong></div>
              <div className="dcf-bridge-total"><span>= Enterprise Value</span><strong>{formatDcfMoney(result.enterpriseValue)}</strong></div>
              <div><span>- Net Debt</span><strong>{formatDcfMoney(result.assumptions.netDebt)}</strong></div>
              <div className="dcf-bridge-total"><span>= Equity Value</span><strong>{formatDcfMoney(result.equityValue)}</strong></div>
              <div><span>/ Shares</span><strong>{formatLarge(result.assumptions.sharesOutstanding)}</strong></div>
              <div className="dcf-bridge-final"><span>= Intrinsic / Share</span><strong>{result.intrinsicValuePerShare == null ? '--' : `$${result.intrinsicValuePerShare.toFixed(2)}`}</strong></div>
            </div>
          </section>

          <section className="dcf-section dcf-mobile-sensitivity">
          <h3>Projection Table</h3>
          <div className="table-wrap dcf-projection-table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Year</th>
                  <th>FCF</th>
                  <th>Growth Rate</th>
                  <th>Discount Factor</th>
                  <th>PV of FCF</th>
                </tr>
              </thead>
              <tbody>
                {result.projection.length ? result.projection.map((row) => (
                  <tr key={row.year}>
                    <td>{row.year}</td>
                    <td>{formatDcfMoney(row.freeCashFlow)}</td>
                    <td>{formatDcfPercent(row.growthRate * 100)}</td>
                    <td>{row.discountFactor.toFixed(3)}</td>
                    <td>{formatDcfMoney(row.presentValue)}</td>
                  </tr>
                )) : (
                  <tr><td colSpan="5">Enter a positive base FCF and valid discount assumptions.</td></tr>
                )}
              </tbody>
              {result.valid ? (
                <tfoot>
                  <tr>
                    <td>Terminal</td>
                    <td>{formatDcfMoney(result.terminalValue)}</td>
                    <td>{formatDcfPercent(result.assumptions.terminalGrowthRate * 100)}</td>
                    <td>{terminalDiscountFactor == null ? '--' : terminalDiscountFactor.toFixed(3)}</td>
                    <td>{formatDcfMoney(result.presentValueOfTerminalValue)}</td>
                  </tr>
                </tfoot>
              ) : null}
            </table>
          </div>
          </section>

          <section className="dcf-section dcf-mobile-sensitivity">
            <h3>Sensitivity Matrix</h3>
            <p className="muted dcf-source">
              Intrinsic value per share by discount rate and terminal growth rate. Current Price: {latestPrice == null ? '--' : `$${latestPrice.toFixed(2)}`}
              <br />
              Green means intrinsic value is greater than or equal to current market price; red means intrinsic value is below current market price. These are valuation comparison indicators, not buy/sell recommendations.
            </p>
            <div className="table-wrap dcf-sensitivity-wrap">
              <table className="dcf-sensitivity-table">
                <thead>
                  <tr>
                    <th>Terminal \\ Discount</th>
                    {sensitivity.discountRates.map((rate) => <th key={rate}>{formatDcfPercent(rate * 100)}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {sensitivity.rows.map((row) => (
                    <tr key={row.terminalGrowthRate}>
                      <td>{formatDcfPercent(row.terminalGrowthRate * 100)}</td>
                      {row.cells.map((cell) => (
                        <td
                          key={`${row.terminalGrowthRate}-${cell.discountRate}`}
                          className={`dcf-sensitivity-cell dcf-sensitivity-${cell.priceStatus} ${cell.baseCase ? 'dcf-sensitivity-base' : ''}`}
                        >
                          {cell.intrinsicValuePerShare == null ? '--' : `$${cell.intrinsicValuePerShare.toFixed(2)}`}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section className="dcf-section dcf-mobile-diagnostics">
            <h3>Market-Implied Assumptions</h3>
            <div className="dcf-reverse-grid">
              <article className="dcf-result-card">
                <span>Implied constant {result.assumptions.projectionYears}Y FCF CAGR</span>
                <strong>{reverseDcf.impliedGrowth.solvable ? formatDcfRateDecimal(reverseDcf.impliedGrowth.value) : reverseDcf.impliedGrowth.label}</strong>
                <small>Assumes {formatDcfRateDecimal(result.assumptions.discountRate)} discount rate and {formatDcfRateDecimal(result.assumptions.terminalGrowthRate)} terminal growth.</small>
              </article>
              <article className="dcf-result-card">
                <span>Implied Discount Rate</span>
                <strong>{reverseDcf.impliedDiscountRate.solvable ? formatDcfRateDecimal(reverseDcf.impliedDiscountRate.value) : reverseDcf.impliedDiscountRate.label}</strong>
                <small>Uses the current active FCF projection path: {activeGrowthPathLabel}.</small>
              </article>
            </div>
          </section>

          <section className="dcf-section dcf-mobile-diagnostics">
            <h3>DCF Diagnostics</h3>
            <p className="muted dcf-source">
              This section explains the gap between model-implied value and market price. It does not change the DCF result and is not a buy/sell recommendation.
            </p>
            <div className="dcf-diagnostics-metrics" aria-label="DCF diagnostic metrics">
              <div><span>Status</span><strong>{formatDcfGapStatus(dcfDiagnostics.valuationGap.status)}</strong></div>
              <div><span>Base FCF Yield</span><strong>{formatDcfRateDecimal(dcfDiagnostics.metrics.baseFcfYield)}</strong></div>
              <div><span>Market EV / FCF</span><strong>{formatDcfMultiple(dcfDiagnostics.metrics.marketEvToFcf)}</strong></div>
              <div><span>Model EV / FCF</span><strong>{formatDcfMultiple(dcfDiagnostics.metrics.modelEvToFcf)}</strong></div>
              <div><span>Terminal Value Weight</span><strong>{formatDcfPercent(dcfDiagnostics.metrics.terminalValueWeight)}</strong></div>
              <div><span>Terminal Spread</span><strong>{formatDcfRateDecimal(dcfDiagnostics.metrics.terminalSpread)}</strong></div>
            </div>
            <div className="dcf-diagnostics-list">
              {dcfDiagnostics.diagnostics.length ? dcfDiagnostics.diagnostics.map((item) => (
                <details key={item.id} className={`dcf-diagnostic-card dcf-diagnostic-${item.severity}`} open={item.severity !== 'info'}>
                  <summary>
                    <span className="dcf-diagnostic-badge">{formatDiagnosticSeverity(item.severity)}</span>
                    <strong>{item.title}</strong>
                  </summary>
                  <p>{item.message}</p>
                  <small>{item.evidence}</small>
                </details>
              )) : (
                <p className="muted">No diagnostic signals for the current DCF inputs.</p>
              )}
            </div>
          </section>

          <section className="dcf-section dcf-mobile-diagnostics">
            <h3>Warnings / Notes</h3>
            {result.warnings?.length ? (
              <ul className="dcf-warning-list">
                {result.warnings.map((warning) => <li key={warning}>{warning}</li>)}
              </ul>
            ) : (
              <p className="muted">No DCF warnings for the current assumptions.</p>
            )}
          </section>
        </div>
      </div>
    </>
  );
}

function FundamentalNotePanel({ symbol, noteItem, draftNote, setDraftNote, saving, onSave }) {
  const savedNote = noteItem?.note || '';
  const isDirty = draftNote !== savedNote;
  const [isEditing, setIsEditing] = useState(false);

  useEffect(() => {
    setIsEditing(false);
  }, [symbol, savedNote]);

  async function handleSave() {
    await onSave();
    setIsEditing(false);
  }

  function handleRevert() {
    setDraftNote(savedNote);
    setIsEditing(false);
  }

  return (
    <article className="fundamental-note-panel">
      <RichTextNotePanel
        title="Fundamental Notes"
        headingLevel={3}
        value={savedNote}
        draft={draftNote}
        onChange={setDraftNote}
        isEditing={isEditing}
        isDirty={isDirty}
        saving={saving}
        disabled={!symbol}
        onEdit={() => setIsEditing(true)}
        onCancel={handleRevert}
        onSave={handleSave}
        placeholder="Record one-time accounting items, restructuring charges, litigation settlements, inventory write-downs, tax effects, acquisition adjustments, or other manual context that affects the charts..."
        emptyText="No fundamental note yet."
        autoFocus
        meta={symbol ? <>Symbol: {symbol} | Last updated: {noteItem?.updatedAt ? new Date(noteItem.updatedAt).toLocaleString() : '--'}</> : 'Load or select a symbol first.'}
      />
    </article>
  );
}

export default function MarketDataPage({
  historySymbol,
  historyFrom,
  historyTo,
  historyLoading,
  historyRequested,
  priceHistory,
  peHistory,
  quarterlyFundamentals,
  capitalAllocationHistory,
  marketAssumptions,
  valuation,
  transactions,
  positions = [],
  fundamentalNotes = [],
  valuationNotes = [],
  onLoadHistory,
  onUpdateSharesOutstandingOverride,
  onSaveFundamentalNote,
  onSaveValuationNote
}) {
  const { t } = useTranslation();
  const isMobile = useIsMobile();
  const [draftSymbol, setDraftSymbol] = useState(historySymbol || '');
  const [draftFrom, setDraftFrom] = useState(historyFrom || '');
  const [draftTo, setDraftTo] = useState(historyTo || '');
  const [historyView, setHistoryView] = useState('PRICE_PE');
  const [fundamentalsView, setFundamentalsView] = useState('EPS');
  const [priceTableCollapsed, setPriceTableCollapsed] = useState(false);
  const [priceTablePage, setPriceTablePage] = useState(1);
  const [visiblePeLines, setVisiblePeLines] = useState({
    ttmPe: true,
    nonGaapTtmPe: true,
    quarterlyPe: false,
    forwardPe: true
  });
  const [fundamentalNoteDraft, setFundamentalNoteDraft] = useState('');
  const [fundamentalNoteSaving, setFundamentalNoteSaving] = useState(false);
  const [filterSheetOpen, setFilterSheetOpen] = useState(false);
  const [mobilePriceRecordCount, setMobilePriceRecordCount] = useState(8);
  const [mobileFundamentalRecordCount, setMobileFundamentalRecordCount] = useState(8);
  const symbolOptions = useMemo(() => {
    const set = new Set();
    transactions.forEach((txn) => {
      const symbol = normalizeSymbol(txn.symbol);
      if (symbol) set.add(symbol);
    });
    return [...set].sort((a, b) => a.localeCompare(b));
  }, [transactions]);

  useEffect(() => {
    const nextSymbol = normalizeSymbol(historySymbol || '');
    if (nextSymbol && symbolOptions.includes(nextSymbol)) {
      setDraftSymbol(nextSymbol);
      return;
    }
    setDraftSymbol(symbolOptions[0] || '');
    setDraftFrom(historyFrom || '');
    setDraftTo(historyTo || '');
  }, [historySymbol, historyFrom, historyTo, symbolOptions]);

  useEffect(() => {
    if (!isMobile || !historyRequested || historyLoading) return undefined;

    const scrollChartsToLatest = () => {
      const scrollContainers = new Set();
      document.querySelectorAll('.market-comparison-frame .asset-chart, .fundamental-panel .asset-chart, .dcf-section .asset-chart').forEach((chart) => {
        let container = chart.parentElement;
        while (container && container !== document.body) {
          const overflowX = window.getComputedStyle(container).overflowX;
          if ((overflowX === 'auto' || overflowX === 'scroll') && container.scrollWidth > container.clientWidth) {
            scrollContainers.add(container);
            break;
          }
          container = container.parentElement;
        }
      });

      scrollContainers.forEach((container) => {
        container.scrollLeft = Math.max(0, container.scrollWidth - container.clientWidth);
      });
    };

    const frame = window.requestAnimationFrame(scrollChartsToLatest);
    const settleTimer = window.setTimeout(scrollChartsToLatest, 120);
    return () => {
      window.cancelAnimationFrame(frame);
      window.clearTimeout(settleTimer);
    };
  }, [
    isMobile,
    historyRequested,
    historyLoading,
    historyView,
    fundamentalsView,
    historySymbol,
    draftSymbol,
    priceHistory.length,
    quarterlyFundamentals.length
  ]);

  const sortedFundamentals = useMemo(
    () => [...quarterlyFundamentals].sort((a, b) => new Date(a.asOfDate) - new Date(b.asOfDate)),
    [quarterlyFundamentals]
  );
  const filledFundamentals = useMemo(
    () => fillQuarterlyFundamentalGaps(sortedFundamentals),
    [sortedFundamentals]
  );
  const activeHistorySymbol = normalizeSymbol(historySymbol || draftSymbol || '');
  const selectedPosition = useMemo(
    () => positions.find((position) => normalizeSymbol(position.symbol) === activeHistorySymbol) || null,
    [positions, activeHistorySymbol]
  );
  const companyFundamentalsApplicable = isOperatingCompanyPosition(selectedPosition);
  const selectedFundamentalNote = useMemo(
    () => fundamentalNotes.find((item) => normalizeSymbol(item.symbol) === activeHistorySymbol) || null,
    [fundamentalNotes, activeHistorySymbol]
  );
  const selectedValuationNote = useMemo(
    () => valuationNotes.find((item) => normalizeSymbol(item.symbol) === activeHistorySymbol) || null,
    [valuationNotes, activeHistorySymbol]
  );
  useEffect(() => {
    setFundamentalNoteDraft(selectedFundamentalNote?.note || '');
  }, [selectedFundamentalNote, activeHistorySymbol]);

  useEffect(() => {
    if (fundamentalsView === 'ROE' || fundamentalsView === 'ROIC') {
      setFundamentalsView('CAPITAL_EFFICIENCY');
    }
  }, [fundamentalsView]);

  useEffect(() => {
    if (!companyFundamentalsApplicable && historyView === 'DCF') {
      setHistoryView('PRICE_PE');
    }
  }, [companyFundamentalsApplicable, historyView]);

  const saveFundamentalNote = async () => {
    if (!activeHistorySymbol || !onSaveFundamentalNote) return;
    setFundamentalNoteSaving(true);
    try {
      await onSaveFundamentalNote(activeHistorySymbol, richNoteToMarkdown(fundamentalNoteDraft));
    } finally {
      setFundamentalNoteSaving(false);
    }
  };

  const latestPrice = useMemo(() => {
    const rows = [...priceHistory]
      .map((row) => ({
        tradeDate: row.tradeDate,
        closePrice: toNullableNumber(row.closePrice)
      }))
      .filter((row) => row.closePrice != null)
      .sort((a, b) => new Date(a.tradeDate) - new Date(b.tradeDate));
    return rows.length ? rows[rows.length - 1].closePrice : null;
  }, [priceHistory]);
  const effectiveVisiblePeLines = useMemo(
    () => companyFundamentalsApplicable
      ? visiblePeLines
      : { ttmPe: false, nonGaapTtmPe: false, quarterlyPe: false, forwardPe: false },
    [companyFundamentalsApplicable, visiblePeLines]
  );
  const comparisonChart = useMemo(
    () => buildComparisonChart(priceHistory, peHistory, effectiveVisiblePeLines),
    [priceHistory, peHistory, effectiveVisiblePeLines]
  );
  const historyRows = useMemo(() => buildHistoryRows(priceHistory, peHistory), [priceHistory, peHistory]);
  const pagedHistoryRows = useMemo(() => historyRows.slice((priceTablePage - 1) * 50, priceTablePage * 50), [historyRows, priceTablePage]);
  const priceTablePageCount = Math.max(1, Math.ceil(historyRows.length / 50));
  const epsChart = useMemo(() => buildEpsChart(filledFundamentals, 'annualizedQuarterly'), [filledFundamentals]);
  const ttmEpsChart = useMemo(
    () => buildEpsChart(filledFundamentals, 'ttm'),
    [filledFundamentals]
  );
  const cashFlowAnalysis = useMemo(
    () => calculateCashFlowAnalysis(filledFundamentals, {
      latestPrice,
      sharesOutstanding: selectedPosition?.effectiveSharesOutstanding
    }),
    [filledFundamentals, latestPrice, selectedPosition?.effectiveSharesOutstanding]
  );
  const capitalEfficiencyAnalysis = useMemo(
    () => calculateCapitalEfficiencyAnalysis(filledFundamentals),
    [filledFundamentals]
  );
  const grossMarginAnalysis = useMemo(() => calculateGrossMarginAnalysis(filledFundamentals), [filledFundamentals]);
  const grossMarginTableValues = useMemo(() => {
    const quarterlyByDate = new Map(grossMarginAnalysis.quarterly.map((row) => [row.date, row.value]));
    const ttmByDate = new Map(grossMarginAnalysis.ttm.map((row) => [row.date, row.value]));
    const annualByYear = new Map(grossMarginAnalysis.annual.map((row) => [String(row.year), row.value]));
    return { quarterlyByDate, ttmByDate, annualByYear };
  }, [grossMarginAnalysis]);
  const fundamentalsTableRows = useMemo(
    () => [...filledFundamentals].reverse().map((row) => ({
      ...row,
      year: String(new Date(`${row.asOfDate}T00:00:00Z`).getUTCFullYear())
    })),
    [filledFundamentals]
  );
  const mobilePriceRows = useMemo(
    () => historyRows.slice(0, mobilePriceRecordCount),
    [historyRows, mobilePriceRecordCount]
  );
  const mobileFundamentalRows = useMemo(
    () => fundamentalsTableRows.slice(0, mobileFundamentalRecordCount),
    [fundamentalsTableRows, mobileFundamentalRecordCount]
  );
  const mobileFundamentalAnalyses = useMemo(() => ({
    cashFlow: cashFlowAnalysis,
    grossMargin: grossMarginTableValues,
    capitalEfficiency: capitalEfficiencyAnalysis
  }), [cashFlowAnalysis, grossMarginTableValues, capitalEfficiencyAnalysis]);

  useEffect(() => {
    setMobilePriceRecordCount(8);
    setMobileFundamentalRecordCount(8);
    setPriceTablePage(1);
  }, [activeHistorySymbol, fundamentalsView]);

  const fundamentalsTable = historyRequested && !historyLoading && filledFundamentals.length ? (
    <div className="table-wrap history-table-wrap desktop-only-table">
      {fundamentalsView === 'EPS' ? (
        <table>
          <thead>
            <tr>
              <th>Quarter</th>
              <th>Type</th>
              <th>Quarterly EPS</th>
              <th>Annualized Quarterly EPS</th>
              <th>TTM EPS</th>
              <th>Forward EPS</th>
            </tr>
          </thead>
          <tbody>
            {fundamentalsTableRows.map((row) => (
              <tr key={row.asOfDate}>
                <td>{row.asOfDate}</td>
                <td>{row.missing ? 'Missing' : row.forecast ? 'Estimate' : 'Actual'}</td>
                <td>{formatMetric(row.forecast ? row.forwardEps : row.basicEps, 4)}</td>
                <td>{formatMetric((row.forecast ? row.forwardEps : row.basicEps) == null ? null : Number(row.forecast ? row.forwardEps : row.basicEps) * 4, 4)}</td>
                <td>{formatMetric(row.ttmEps, 4)}</td>
                <td>{formatMetric(row.forwardEps, 4)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {fundamentalsView === 'CASH_FLOW' ? (
        <table>
          <thead>
            <tr>
              <th>Quarter</th>
              <th>Operating CF</th>
              <th>CapEx</th>
              <th>FCF</th>
              <th>Adjusted FCF</th>
              <th>FCF Margin</th>
              <th>FCF Conversion</th>
              <th>OCF Conversion</th>
              <th>CapEx Intensity</th>
            </tr>
          </thead>
          <tbody>
            {fundamentalsTableRows.map((row) => {
              const metrics = cashFlowAnalysis.byDate.get(row.asOfDate);
              return (
                <tr key={row.asOfDate}>
                  <td>{row.asOfDate}</td>
                  <td>{formatLarge(row.cashFlow)}</td>
                  <td>{formatLarge(row.capex)}</td>
                  <td>{formatLarge(row.fcf)}</td>
                  <td>{formatLarge(row.adjustedFcf ?? row.fcf)}</td>
                  <td>{formatCashFlowPercent(metrics?.fcfMargin)}</td>
                  <td>{formatCashFlowPercent(metrics?.fcfConversion)}</td>
                  <td>{formatCashFlowPercent(metrics?.ocfConversion)}</td>
                  <td>{formatCashFlowPercent(metrics?.capexIntensity)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : null}

      {fundamentalsView === 'GROSS_MARGIN' ? (
        <table>
          <thead>
            <tr>
              <th>Quarter</th>
              <th>Quarterly Gross Margin</th>
              <th>TTM Gross Margin</th>
              <th>Annual Gross Margin</th>
            </tr>
          </thead>
          <tbody>
            {fundamentalsTableRows.map((row) => (
              <tr key={row.asOfDate}>
                <td>{row.asOfDate}</td>
                <td>{formatGrossMarginPercent(grossMarginTableValues.quarterlyByDate.get(row.asOfDate))}</td>
                <td>{formatGrossMarginPercent(grossMarginTableValues.ttmByDate.get(row.asOfDate))}</td>
                <td>{formatGrossMarginPercent(grossMarginTableValues.annualByYear.get(row.year))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {fundamentalsView === 'CAPITAL_EFFICIENCY' ? (
        <table>
          <thead>
            <tr>
              <th>Quarter</th>
              <th>TTM ROE</th>
              <th>TTM ROIC</th>
              <th>5Y Avg ROE</th>
              <th>5Y Avg ROIC</th>
              <th>3Y Incremental ROIC</th>
              <th>Net Profit Margin</th>
              <th>NOPAT Margin</th>
              <th>Invested Capital Turnover</th>
            </tr>
          </thead>
          <tbody>
            {fundamentalsTableRows.map((row) => {
              const metrics = capitalEfficiencyAnalysis.series.find((item) => item.date === row.asOfDate);
              return (
                <tr key={row.asOfDate}>
                  <td>{row.asOfDate}</td>
                  <td>{formatCapitalPercent(metrics?.roe ?? row.roe)}</td>
                  <td>{formatCapitalPercent(metrics?.roic ?? row.roic)}</td>
                  <td>{formatCapitalPercent(metrics?.avgRoe5y)}</td>
                  <td>{formatCapitalPercent(metrics?.avgRoic5y)}</td>
                  <td>{formatCapitalPercent(metrics?.incrementalRoic3y?.value)}</td>
                  <td>{formatCapitalPercent(metrics?.netProfitMargin)}</td>
                  <td>{formatCapitalPercent(metrics?.nopatMargin)}</td>
                  <td>{formatCapitalMultiple(metrics?.investedCapitalTurnover)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : null}

    </div>
  ) : null;

  const historyFilterControls = (
    <div className="history-controls">
      <label className="history-symbol-field">
        <span>Symbol</span>
        <select value={draftSymbol} onChange={(e) => setDraftSymbol(e.target.value)}>
          {!symbolOptions.length ? <option value="">No Symbol</option> : null}
          {symbolOptions.map((symbol) => <option key={symbol} value={symbol}>{symbol}</option>)}
        </select>
      </label>
      <label className="history-date-range">
        <span>Date range</span>
        <div className="history-date-inputs">
          <DateInput aria-label="From date" value={draftFrom} onChange={(e) => setDraftFrom(e.target.value)} />
          <span className="history-range-separator" aria-hidden="true">to</span>
          <DateInput aria-label="To date" value={draftTo} onChange={(e) => setDraftTo(e.target.value)} />
        </div>
      </label>
      <button
        type="button"
        className="history-load-button"
        disabled={!draftSymbol}
        onClick={() => {
          onLoadHistory({ symbol: draftSymbol, from: draftFrom, to: draftTo });
          if (isMobile) setFilterSheetOpen(false);
        }}
      >
        Load History
      </button>
    </div>
  );

  return (
    <>
      <div className="mobile-market-summary">
        <div>
          <span>Symbol</span>
          <strong>{activeHistorySymbol || draftSymbol || '--'}</strong>
        </div>
        <div>
          <span>Range</span>
          <strong>{draftFrom || '--'} → {draftTo || '--'}</strong>
        </div>
        <button type="button" className="row-secondary-btn" onClick={() => setFilterSheetOpen(true)}>{t('ui.filter')}</button>
      </div>

      <section className="panel mobile-hide">
        <h2>History Filters</h2>
        {historyFilterControls}
      </section>

      <section className="panel">
        {selectedPosition?.profileReviewStatus === 'rejected' ? (
          <p className="reviewed-profile-warning">Company profile data for {activeHistorySymbol} is marked rejected. The holding remains visible, but profile fields should be treated as unavailable.</p>
        ) : null}
        <div className="market-subtabs mobile-hide">
          <button type="button" className={historyView === 'PRICE_PE' ? 'rank-tab active' : 'rank-tab'} onClick={() => setHistoryView('PRICE_PE')}>Price &amp; Multiples</button>
          <button type="button" className={historyView === 'FUNDAMENTALS' ? 'rank-tab active' : 'rank-tab'} onClick={() => setHistoryView('FUNDAMENTALS')}>Fundamentals</button>
          <button type="button" className={historyView === 'VALUATION' ? 'rank-tab active' : 'rank-tab'} onClick={() => setHistoryView('VALUATION')}>Valuation</button>
        </div>
        <div className="market-primary-tabs-scroll">
          <SegmentedControl
            label="Market view"
            value={historyView}
            onChange={setHistoryView}
            options={[
              { value: 'PRICE_PE', label: 'Price & Multiples' },
              { value: 'FUNDAMENTALS', label: 'Fundamentals' },
              { value: 'VALUATION', label: 'Valuation' }
            ]}
          />
        </div>

        {historyView === 'PRICE_PE' ? (
          <>
            <h2>Price vs PE</h2>
            {companyFundamentalsApplicable ? (
              <div className="chart-toggle-row mobile-chart-toolbar" aria-label="PE line visibility">
                <label className="chart-toggle">
                  <input
                    type="checkbox"
                    checked={visiblePeLines.ttmPe}
                    onChange={(event) => setVisiblePeLines((prev) => ({ ...prev, ttmPe: event.target.checked }))}
                  />
                  <span><i className="dot dot-pe" /> TTM PE</span>
                </label>
                <label className="chart-toggle">
                  <input
                    type="checkbox"
                    checked={visiblePeLines.nonGaapTtmPe}
                    onChange={(event) => setVisiblePeLines((prev) => ({ ...prev, nonGaapTtmPe: event.target.checked }))}
                  />
                  <span><i className="dot dot-non-gaap-pe" /> Non-GAAP TTM PE</span>
                </label>
                <label className="chart-toggle">
                  <input
                    type="checkbox"
                    checked={visiblePeLines.quarterlyPe}
                    onChange={(event) => setVisiblePeLines((prev) => ({ ...prev, quarterlyPe: event.target.checked }))}
                  />
                  <span><i className="dot dot-quarterly-pe" /> Annualized Quarterly PE</span>
                </label>
                <label className="chart-toggle">
                  <input
                    type="checkbox"
                    checked={visiblePeLines.forwardPe}
                    onChange={(event) => setVisiblePeLines((prev) => ({ ...prev, forwardPe: event.target.checked }))}
                  />
                  <span><i className="dot dot-forward-pe" /> Forward PE</span>
                </label>
              </div>
            ) : (
              <p className="muted applicability-note">
                Company PE lines are hidden for {formatInstrumentDescription(selectedPosition)}.
              </p>
            )}
            {!historyRequested ? (
              <div className="mobile-market-empty">
                <p className="muted">Preparing the latest available history for {draftSymbol || 'your first holding'}.</p>
              </div>
            ) : null}
            {historyRequested && historyLoading ? <p>Loading history...</p> : null}
            {historyRequested && !historyLoading && comparisonChart.hasData ? (
              <MobileLandscapeChart title="Price vs PE" className="market-comparison-landscape-chart">
                <ChartFrame className="market-comparison-frame" scrollable scrollKey={`${activeHistorySymbol}-${comparisonChart.lastDate || ''}-${historyView}`}>
                  <svg viewBox={`0 0 ${comparisonChart.width} ${comparisonChart.height}`} className="asset-chart" role="img" aria-label="Price and PE comparison">
                  {comparisonChart.yTicksPrice.map((tick) => (
                    <g key={`price-tick-${tick.value}`}>
                      <line x1={comparisonChart.plotLeft} y1={tick.y} x2={comparisonChart.plotRight} y2={tick.y} className="chart-grid" />
                      <text x={comparisonChart.plotLeft - 8} y={tick.y + 4} textAnchor="end" className="chart-tick">{tick.value.toFixed(2)}</text>
                    </g>
                  ))}
                  {comparisonChart.xTicks.map((tick) => (
                    <g key={`x-tick-${tick.time}`}>
                      <line x1={tick.x} y1={comparisonChart.plotTop} x2={tick.x} y2={comparisonChart.plotBottom} className="chart-grid vertical-grid" />
                      <text x={tick.x} y={comparisonChart.plotBottom + 18} textAnchor="middle" className={`chart-tick-bottom ${/^\d{4}$/.test(tick.label) ? 'year-tick' : ''}`}>{tick.label}</text>
                    </g>
                  ))}
                  {comparisonChart.yearSeparators.map((separator) => (
                    <line key={`comparison-year-${separator.year}-${separator.date}`} x1={separator.x} y1={comparisonChart.plotTop} x2={separator.x} y2={comparisonChart.plotBottom} className="year-separator" />
                  ))}
                  <line x1={comparisonChart.plotLeft} y1={comparisonChart.plotBottom} x2={comparisonChart.plotRight} y2={comparisonChart.plotBottom} className="chart-axis" />
                  <line x1={comparisonChart.plotLeft} y1={comparisonChart.plotTop} x2={comparisonChart.plotLeft} y2={comparisonChart.plotBottom} className="chart-axis" />
                  <line x1={comparisonChart.plotRight} y1={comparisonChart.plotTop} x2={comparisonChart.plotRight} y2={comparisonChart.plotBottom} className="chart-axis" />
                  {comparisonChart.yTicksPe.map((tick) => (
                    <text key={`pe-tick-${tick.value}`} x={comparisonChart.plotRight + 8} y={tick.y + 4} className="chart-tick">{tick.value.toFixed(2)}</text>
                  ))}
                  <path d={comparisonChart.pricePath} className="chart-line-price" />
                  {companyFundamentalsApplicable && visiblePeLines.ttmPe ? <path d={comparisonChart.ttmPePath} className="chart-line-pe" /> : null}
                  {companyFundamentalsApplicable && visiblePeLines.nonGaapTtmPe ? <path d={comparisonChart.nonGaapTtmPePath} className="chart-line-non-gaap-pe" /> : null}
                  {companyFundamentalsApplicable && visiblePeLines.quarterlyPe ? <path d={comparisonChart.quarterlyPePath} className="chart-line-quarterly-pe" /> : null}
                  {comparisonChart.forwardPePoint ? (
                    <g className="forward-pe-marker">
                      <circle cx={comparisonChart.forwardPePoint.x} cy={comparisonChart.forwardPePoint.y} r="5.2" className="chart-point-forward-pe" />
                      <text
                        x={Math.min(comparisonChart.forwardPePoint.x + 9, comparisonChart.plotRight - 86)}
                        y={Math.max(comparisonChart.forwardPePoint.y - 9, comparisonChart.plotTop + 13)}
                        className="chart-point-label"
                      >
                        Forward PE {comparisonChart.forwardPePoint.value.toFixed(2)}
                      </text>
                    </g>
                  ) : null}
                  </svg>
                </ChartFrame>
                <div className="market-chart-meta">
                  <div className="legend-row">
                    <span><i className="dot dot-price" /> Price (left axis)</span>
                    {companyFundamentalsApplicable && visiblePeLines.ttmPe ? <span><i className="dot dot-pe" /> TTM PE</span> : null}
                    {companyFundamentalsApplicable && visiblePeLines.nonGaapTtmPe ? <span><i className="dot dot-non-gaap-pe" /> Non-GAAP TTM PE</span> : null}
                    {companyFundamentalsApplicable && visiblePeLines.quarterlyPe ? <span><i className="dot dot-quarterly-pe" /> Annualized Quarterly PE</span> : null}
                    {companyFundamentalsApplicable && comparisonChart.forwardPePoint ? <span><i className="dot dot-forward-pe" /> Current Forward PE</span> : null}
                  </div>
                  <p className="chart-caption">
                    {comparisonChart.firstDate} ~ {comparisonChart.lastDate} | Price: {formatCurrency(comparisonChart.priceMin)} - {formatCurrency(comparisonChart.priceMax)}{comparisonChart.hasPeData ? ` | PE: ${formatCurrency(comparisonChart.peMin)} - ${formatCurrency(comparisonChart.peMax)}` : ' | PE hidden'}
                  </p>
                </div>
              </MobileLandscapeChart>
            ) : null}
            {historyRequested && !historyLoading && !comparisonChart.hasData ? <p>No overlapping price/PE data in this range yet.</p> : null}

            {historyRequested && !historyLoading ? (
              <>
                <div className="collapsible-header mobile-hide">
                  <h3>Price / PE Records</h3>
                  <button type="button" className="table-toggle" onClick={() => setPriceTableCollapsed((prev) => !prev)}>
                    {priceTableCollapsed ? 'Expand' : 'Collapse'}
                  </button>
                </div>
                {!priceTableCollapsed ? (
                  <>
                  <div className="market-mobile-records">
                    <h3>{t('ui.latestPricePeRecords')}</h3>
                    <div className="mobile-record-list">
                      {mobilePriceRows.map((row) => (
                        <article key={`mobile-price-${row.tradeDate}`} className="record-card">
                          <div className="record-card-head"><span className="record-card-symbol">{row.tradeDate}</span><strong>{row.closePrice == null ? '--' : `$${row.closePrice.toFixed(2)}`}</strong></div>
                          <div className="record-card-metrics">
                            <span><small>{t('auto.TTM PE', { defaultValue: 'TTM PE' })}</small><strong>{row.ttmPeStatus === 'NOT_MEANINGFUL' ? 'N/M' : row.ttmPe == null ? '--' : row.ttmPe.toFixed(2)}</strong></span>
                            <span><small>{t('auto.Forward PE', { defaultValue: 'Forward PE' })}</small><strong>{row.forwardPeStatus === 'NOT_MEANINGFUL' ? 'N/M' : row.forwardPe == null ? '--' : row.forwardPe.toFixed(2)}</strong></span>
                            <span><small>{t('auto.Annualized Quarterly PE', { defaultValue: 'Quarterly PE' })}</small><strong>{row.quarterlyPeStatus === 'NOT_MEANINGFUL' ? 'N/M' : row.quarterlyPe == null ? '--' : row.quarterlyPe.toFixed(2)}</strong></span>
                          </div>
                        </article>
                      ))}
                    </div>
                    {mobilePriceRows.length < historyRows.length ? (
                      <button type="button" className="mobile-records-more" onClick={() => setMobilePriceRecordCount((count) => count + 20)}>{t('ui.showMoreRecords')}</button>
                    ) : null}
                  </div>
                  <div className="table-wrap history-table-wrap desktop-only-table">
                    <table className="price-pe-records-table">
                      <colgroup>
                        <col className="price-pe-date-col" />
                        <col className="price-pe-price-col" />
                        <col className="price-pe-metric-col" />
                        <col className="price-pe-metric-col" />
                        <col className="price-pe-metric-col" />
                        <col className="price-pe-metric-col" />
                      </colgroup>
                      <thead>
                        <tr>
                          <th>Date</th>
                          <th>Close Price</th>
                          <th>TTM PE</th>
                          <th>Non-GAAP TTM PE</th>
                          <th>Annualized Quarterly PE</th>
                          <th>Forward PE</th>
                        </tr>
                      </thead>
                      <tbody>
                        {pagedHistoryRows.map((row) => (
                          <tr key={row.tradeDate}>
                            <td>{row.tradeDate}</td>
                            <td>{row.closePrice == null ? '--' : `$${row.closePrice.toFixed(4)}`}</td>
                            <td>{row.ttmPeStatus === 'NOT_MEANINGFUL' ? 'N/M' : row.ttmPe == null ? '--' : row.ttmPe.toFixed(4)}</td>
                            <td>{row.nonGaapTtmPeStatus === 'NOT_MEANINGFUL' ? 'N/M' : row.nonGaapTtmPe == null ? '--' : row.nonGaapTtmPe.toFixed(4)}</td>
                            <td>{row.quarterlyPeStatus === 'NOT_MEANINGFUL' ? 'N/M' : row.quarterlyPe == null ? '--' : row.quarterlyPe.toFixed(4)}</td>
                            <td>{row.forwardPeStatus === 'NOT_MEANINGFUL' ? 'N/M' : row.forwardPe == null ? '--' : row.forwardPe.toFixed(4)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <div className="table-pagination">
                      <button type="button" className="secondary-button" disabled={priceTablePage <= 1} onClick={() => setPriceTablePage((page) => page - 1)}>Previous</button>
                      <span>Page {priceTablePage} / {priceTablePageCount} · 50 rows</span>
                      <button type="button" className="secondary-button" disabled={priceTablePage >= priceTablePageCount} onClick={() => setPriceTablePage((page) => page + 1)}>Next</button>
                    </div>
                  </div>
                  </>
                ) : null}
              </>
            ) : null}
          </>
        ) : historyView === 'FUNDAMENTALS' ? (
          <>
            <h2>{t('ui.quarterlyFundamentals')}</h2>
            {companyFundamentalsApplicable ? (
              <div className="market-subtabs fundamentals-category-tabs mobile-hide">
                <button type="button" className={fundamentalsView === 'EPS' ? 'rank-tab active' : 'rank-tab'} onClick={() => setFundamentalsView('EPS')}>EPS</button>
                <button type="button" className={fundamentalsView === 'CASH_FLOW' ? 'rank-tab active' : 'rank-tab'} onClick={() => setFundamentalsView('CASH_FLOW')}>Cash Flow</button>
                <button type="button" className={fundamentalsView === 'GROSS_MARGIN' ? 'rank-tab active' : 'rank-tab'} onClick={() => setFundamentalsView('GROSS_MARGIN')}>Gross Margin</button>
                <button type="button" className={fundamentalsView === 'CAPITAL_EFFICIENCY' ? 'rank-tab active' : 'rank-tab'} onClick={() => setFundamentalsView('CAPITAL_EFFICIENCY')}>ROE & ROIC</button>
                <button type="button" className={fundamentalsView === 'CAPITAL_ALLOCATION' ? 'rank-tab active' : 'rank-tab'} onClick={() => setFundamentalsView('CAPITAL_ALLOCATION')}>Capital Allocation</button>
              </div>
            ) : null}
            {companyFundamentalsApplicable ? (
              <SegmentedControl
                label="Fundamentals view"
                value={fundamentalsView}
                onChange={setFundamentalsView}
                options={[
                  { value: 'EPS', label: 'EPS' },
                  { value: 'CASH_FLOW', label: 'Cash Flow' },
                  { value: 'GROSS_MARGIN', label: 'Margin' },
                  { value: 'CAPITAL_EFFICIENCY', label: 'ROIC' },
                  { value: 'CAPITAL_ALLOCATION', label: 'Capital' }
                ]}
              />
            ) : null}
            {historyRequested && historyLoading ? <p>Loading fundamentals...</p> : null}
            {historyRequested && !historyLoading && !companyFundamentalsApplicable ? (
              <article className="fundamental-panel applicability-panel">
                <h3>Company Fundamentals Not Applicable</h3>
                <p className="muted">
                  {activeHistorySymbol} is classified as {formatInstrumentDescription(selectedPosition)}, so EPS, CAPE, ROE, ROIC, and DCF-style operating-company diagnostics are not shown.
                </p>
              </article>
            ) : null}
            {historyRequested && !historyLoading && companyFundamentalsApplicable ? (
              <div className="fundamentals-stack">
                <FundamentalNotePanel
                  symbol={activeHistorySymbol}
                  noteItem={selectedFundamentalNote}
                  draftNote={fundamentalNoteDraft}
                  setDraftNote={setFundamentalNoteDraft}
                  saving={fundamentalNoteSaving}
                  onSave={saveFundamentalNote}
                />
                {fundamentalsView === 'EPS' ? (
                  <>
                    <article className="fundamental-panel">
                      <h3>Annualized Quarterly EPS</h3>
                      <MobileLandscapeChart title="Annualized Quarterly EPS"><EpsChart chart={epsChart} /></MobileLandscapeChart>
                    </article>
                    <article className="fundamental-panel">
                      <h3>TTM EPS</h3>
                      <MobileLandscapeChart title="TTM EPS"><EpsChart chart={ttmEpsChart} /></MobileLandscapeChart>
                    </article>
                  </>
                ) : null}
                {fundamentalsView === 'CASH_FLOW' ? <CashFlowAnalysisPanel analysis={cashFlowAnalysis} /> : null}
                {fundamentalsView === 'GROSS_MARGIN' ? <GrossMarginAnalysisPanel analysis={grossMarginAnalysis} /> : null}
                {fundamentalsView === 'CAPITAL_EFFICIENCY' ? <CapitalEfficiencyPanel analysis={capitalEfficiencyAnalysis} /> : null}
                {fundamentalsView === 'CAPITAL_ALLOCATION' ? <CapitalAllocationPanel history={capitalAllocationHistory} /> : null}
                {fundamentalsView !== 'CAPITAL_ALLOCATION' ? <div className="market-mobile-records">
                  <h3>{t('ui.recentFundamentals')}</h3>
                  <div className="mobile-record-list">
                    {mobileFundamentalRows.map((row) => {
                      const metrics = getMobileFundamentalMetrics(row, fundamentalsView, mobileFundamentalAnalyses, t);
                      return (
                        <article key={`mobile-fundamentals-${fundamentalsView}-${row.asOfDate}`} className="record-card">
                          <div className="record-card-head"><span className="record-card-symbol">{row.asOfDate}</span><strong>{row.missing ? t('ui.missing') : row.forecast ? t('ui.estimate') : t('ui.actual')}</strong></div>
                          <div className="record-card-metrics">
                            {metrics.map(([label, value]) => <span key={label}><small>{label}</small><strong>{value}</strong></span>)}
                          </div>
                        </article>
                      );
                    })}
                  </div>
                  {mobileFundamentalRows.length < fundamentalsTableRows.length ? (
                    <button type="button" className="mobile-records-more" onClick={() => setMobileFundamentalRecordCount((count) => count + 12)}>{t('ui.showMoreRecords')}</button>
                  ) : null}
                </div> : null}
                {fundamentalsView !== 'CAPITAL_ALLOCATION' ? fundamentalsTable : null}
              </div>
            ) : null}
          </>
        ) : (
          <ValuationWorkspace
            symbol={activeHistorySymbol}
            initialValue={valuation}
            noteItem={selectedValuationNote}
            onSaveNote={onSaveValuationNote}
          />
        )}
      </section>

      <BottomSheet open={filterSheetOpen} title="Market Filters" onClose={() => setFilterSheetOpen(false)}>
        {historyFilterControls}
      </BottomSheet>
    </>
  );
}
