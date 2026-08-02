export function formatCurrency(value) {
  return Number(value).toFixed(2);
}

export function formatDateInput(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function buildAssetChart(assetCurve, options = {}) {
  if (!assetCurve.length) {
    return { hasData: false };
  }

  const measuredWidth = Number(options.width);
  const compact = Boolean(options.compact) || (Number.isFinite(measuredWidth) && measuredWidth < 560);
  const width = Number.isFinite(measuredWidth) && measuredWidth > 0
    ? Math.max(Math.round(measuredWidth), 320)
    : 900;
  const height = compact ? 250 : 320;
  const plotLeft = compact ? 10 : 14;
  const plotRight = width - (compact ? 10 : 110);
  const plotTop = compact ? 12 : 16;
  const plotBottom = height - (compact ? 34 : 46);

  const portfolioValues = assetCurve.map((point) => Number(point.totalAssets ?? 0));
  const costBasisValues = assetCurve.map((point) => Number(point.totalCostBasis ?? point.totalAssets ?? 0));
  const allValues = [...portfolioValues, ...costBasisValues];
  const minValue = Math.min(...allValues);
  const maxValue = Math.max(...allValues);

  const yTicksValues = buildNiceDollarTicks(minValue, maxValue);
  const yMin = yTicksValues[0];
  const yMax = yTicksValues[yTicksValues.length - 1];
  const ySpan = Math.max(yMax - yMin, 1);

  const timestamps = assetCurve.map((point) => new Date(point.timestamp).getTime());
  const minTime = Math.min(...timestamps);
  const maxTime = Math.max(...timestamps);
  const timeSpan = Math.max(maxTime - minTime, 1);

  const points = assetCurve.map((point, index) => {
    const x = plotLeft + ((timestamps[index] - minTime) / timeSpan) * (plotRight - plotLeft);
    const portfolioValue = Number(point.totalAssets ?? 0);
    const costBasisValue = Number(point.totalCostBasis ?? point.totalAssets ?? 0);
    const portfolioY = plotBottom - ((portfolioValue - yMin) / ySpan) * (plotBottom - plotTop);
    const costBasisY = plotBottom - ((costBasisValue - yMin) / ySpan) * (plotBottom - plotTop);
    return {
      x,
      portfolioY,
      costBasisY,
      timestamp: point.timestamp,
      portfolioValue,
      costBasisValue
    };
  });

  const portfolioLinePath = points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.portfolioY.toFixed(2)}`)
    .join(' ');

  const costBasisLinePath = points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.costBasisY.toFixed(2)}`)
    .join(' ');

  const first = points[0];
  const last = points[points.length - 1];
  const areaPath = `${portfolioLinePath} L ${last.x.toFixed(2)} ${plotBottom.toFixed(2)} L ${first.x.toFixed(2)} ${plotBottom.toFixed(2)} Z`;

  const chipWidth = 132;
  const chipHeight = 22;
  const chipGap = 6;
  const chipMinSeparation = chipHeight + chipGap;
  const chipX = Math.min(last.x + 8, plotRight - chipWidth);
  let portfolioChipY = Math.max(last.portfolioY - 20, plotTop + 2);
  let costChipY = Math.max(last.costBasisY + 6, plotTop + 2);

  // Keep end labels readable when the two lines finish near each other.
  if (Math.abs(portfolioChipY - costChipY) < chipMinSeparation) {
    if (portfolioChipY <= costChipY) {
      portfolioChipY = costChipY - chipMinSeparation;
    } else {
      costChipY = portfolioChipY - chipMinSeparation;
    }
  }

  const maxChipY = plotBottom - chipHeight - 2;
  portfolioChipY = Math.min(Math.max(portfolioChipY, plotTop + 2), maxChipY);
  costChipY = Math.min(Math.max(costChipY, plotTop + 2), maxChipY);

  const yTicks = yTicksValues.map((value) => {
    const y = plotBottom - ((value - yMin) / ySpan) * (plotBottom - plotTop);
    return { y, value };
  });

  const xTicks = preventEndTickOverlap(buildTimeTicks(minTime, maxTime, compact).map((timeValue) => {
    const x = plotLeft + ((timeValue - minTime) / timeSpan) * (plotRight - plotLeft);
    const date = new Date(timeValue);
    const isYearMark = date.getMonth() === 0;
    const label = isYearMark
      ? String(date.getFullYear())
      : date.toLocaleDateString('en-US', { month: 'short' });
    return { x, label, isYearMark };
  }), compact ? 42 : 54);

  const yearSeparators = buildYearSeparators(minTime, maxTime).map((timeValue) => {
    const x = plotLeft + ((timeValue - minTime) / timeSpan) * (plotRight - plotLeft);
    const year = new Date(timeValue).getFullYear();
    return { x, year };
  });

  return {
    hasData: true,
    width,
    height,
    plotLeft,
    plotRight,
    plotTop,
    plotBottom,
    portfolioLinePath,
    costBasisLinePath,
    areaPath,
    yTicks,
    xTicks,
    yearSeparators,
    lastX: last.x,
    lastPortfolioY: last.portfolioY,
    lastCostBasisY: last.costBasisY,
    chipX,
    portfolioChipY,
    costChipY,
    lastPortfolioValue: last.portfolioValue,
    lastCostBasisValue: last.costBasisValue,
    minValue: yMin,
    maxValue: yMax
  };
}

function buildNiceDollarTicks(minValue, maxValue) {
  if (minValue === maxValue) {
    const v = Math.round(minValue);
    return [v - 100, v, v + 100];
  }

  const range = maxValue - minValue;
  const rawStep = range / 6;
  const step = niceStep(rawStep);
  const start = Math.floor(minValue / step) * step;
  const end = Math.ceil(maxValue / step) * step;

  const ticks = [];
  for (let v = start; v <= end + step * 0.5; v += step) {
    ticks.push(Math.round(v));
  }
  return ticks;
}

function niceStep(rawStep) {
  const magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(rawStep, 1))));
  const normalized = rawStep / magnitude;
  if (normalized <= 1) return magnitude;
  if (normalized <= 2) return 2 * magnitude;
  if (normalized <= 2.5) return 2.5 * magnitude;
  if (normalized <= 5) return 5 * magnitude;
  return 10 * magnitude;
}

function buildTimeTicks(minTime, maxTime, compact = false) {
  const minDate = new Date(minTime);
  const maxDate = new Date(maxTime);

  const ticks = [];
  const cursor = new Date(minDate.getFullYear(), minDate.getMonth(), 1);
  const totalMonths = (maxDate.getFullYear() - minDate.getFullYear()) * 12 + (maxDate.getMonth() - minDate.getMonth());
  const monthStep = compact ? (totalMonths > 18 ? 6 : 3) : (totalMonths > 24 ? 3 : 2);

  while (cursor.getTime() <= maxTime) {
    ticks.push(cursor.getTime());
    cursor.setMonth(cursor.getMonth() + monthStep);
  }

  if (!ticks.length || ticks[0] > minTime) ticks.unshift(minTime);
  if (ticks[ticks.length - 1] < maxTime) ticks.push(maxTime);

  return ticks;
}

function preventEndTickOverlap(ticks, minGap) {
  if (ticks.length < 2) return ticks;
  const result = [...ticks];
  const last = result[result.length - 1];
  for (let i = result.length - 2; i >= 0; i -= 1) {
    if (Math.abs(last.x - result[i].x) >= minGap) break;
    result.splice(i, 1);
  }
  return result;
}

function buildYearSeparators(minTime, maxTime) {
  const start = new Date(minTime);
  const end = new Date(maxTime);
  const separators = [];
  const cursor = new Date(start.getFullYear() + 1, 0, 1);

  while (cursor.getTime() < maxTime) {
    separators.push(cursor.getTime());
    cursor.setFullYear(cursor.getFullYear() + 1);
  }

  if (start.getFullYear() !== end.getFullYear()) {
    const firstYearStart = new Date(start.getFullYear(), 0, 1).getTime();
    if (firstYearStart > minTime && firstYearStart < maxTime) {
      separators.unshift(firstYearStart);
    }
  }

  return separators;
}

export function buildComparisonChart(priceHistory, peHistory, visiblePeSeries = {}) {
  const visibleSeries = {
    ttmPe: visiblePeSeries.ttmPe !== false,
    nonGaapTtmPe: visiblePeSeries.nonGaapTtmPe !== false,
    quarterlyPe: visiblePeSeries.quarterlyPe !== false,
    forwardPe: visiblePeSeries.forwardPe !== false
  };
  const priceMap = new Map(priceHistory.map((point) => [point.tradeDate, Number(point.closePrice)]));
  const peMap = new Map(peHistory.map((point) => [point.tradeDate, {
    ttmPe: toFiniteNumber(point.ttmPe),
    nonGaapTtmPe: toFiniteNumber(point.nonGaapTtmPe),
    quarterlyPe: toFiniteNumber(point.quarterlyPe),
    forwardPe: toFiniteNumber(point.forwardPe)
  }]));

  const dates = [...priceMap.keys()].sort((a, b) => new Date(a) - new Date(b));

  if (dates.length === 0) {
    return { hasData: false };
  }

  const width = 860;
  const height = 320;
  const plotLeft = 56;
  const plotRight = width - 56;
  const plotTop = 24;
  const plotBottom = height - 38;

  const priceValues = dates.map((date) => priceMap.get(date));
  const fillInternalPeGaps = (key) => {
    const firstIndex = dates.findIndex((date) => Number.isFinite(peMap.get(date)?.[key]));
    let lastIndex = -1;
    for (let index = dates.length - 1; index >= 0; index -= 1) {
      if (Number.isFinite(peMap.get(dates[index])?.[key])) {
        lastIndex = index;
        break;
      }
    }
    return new Map(dates.map((date, index) => {
      const pe = peMap.get(date);
      const value = pe?.[key];
      if (Number.isFinite(value)) return [date, value];
      if (firstIndex >= 0 && index > firstIndex && index < lastIndex) return [date, 0];
      return [date, NaN];
    }));
  };
  const ttmPeSeries = fillInternalPeGaps('ttmPe');
  const nonGaapTtmPeSeries = fillInternalPeGaps('nonGaapTtmPe');
  const quarterlyPeSeries = fillInternalPeGaps('quarterlyPe');

  const peValues = dates.flatMap((date) => {
    return [
      visibleSeries.ttmPe ? ttmPeSeries.get(date) : NaN,
      visibleSeries.nonGaapTtmPe ? nonGaapTtmPeSeries.get(date) : NaN,
      visibleSeries.quarterlyPe ? quarterlyPeSeries.get(date) : NaN
    ].filter(Number.isFinite);
  });
  const forwardPeSource = visibleSeries.forwardPe
    ? dates
      .map((date) => ({ date, value: peMap.get(date)?.forwardPe }))
      .filter((point) => Number.isFinite(point.value))
      .at(-1)
    : null;
  if (forwardPeSource) {
    peValues.push(forwardPeSource.value);
  }

  const priceMin = Math.min(...priceValues);
  const priceMax = Math.max(...priceValues);
  const peMin = peValues.length ? Math.min(...peValues) : 0;
  const peMax = peValues.length ? Math.max(...peValues) : 1;

  const priceSpan = Math.max(priceMax - priceMin, 1);
  const peSpan = Math.max(peMax - peMin, 1);
  const plotWidth = Math.max(plotRight - plotLeft, 1);
  const plotHeight = Math.max(plotBottom - plotTop, 1);
  const dateTimes = dates.map((date) => new Date(`${date}T00:00:00Z`).getTime());
  const minTime = Math.min(...dateTimes);
  const maxTime = Math.max(...dateTimes);
  const timeSpan = Math.max(maxTime - minTime, 1);
  const longRange = timeSpan > 366 * 24 * 60 * 60 * 1000;

  const points = dates.map((date, index) => {
    const x = plotLeft + ((dateTimes[index] - minTime) / timeSpan) * plotWidth;
    const priceY = plotBottom - ((priceMap.get(date) - priceMin) / priceSpan) * plotHeight;
    const ttmPe = ttmPeSeries.get(date);
    const nonGaapTtmPe = nonGaapTtmPeSeries.get(date);
    const quarterlyPe = quarterlyPeSeries.get(date);
    const ttmPeY = visibleSeries.ttmPe && Number.isFinite(ttmPe) ? plotBottom - ((ttmPe - peMin) / peSpan) * plotHeight : null;
    const nonGaapTtmPeY = visibleSeries.nonGaapTtmPe && Number.isFinite(nonGaapTtmPe) ? plotBottom - ((nonGaapTtmPe - peMin) / peSpan) * plotHeight : null;
    const quarterlyPeY = visibleSeries.quarterlyPe && Number.isFinite(quarterlyPe) ? plotBottom - ((quarterlyPe - peMin) / peSpan) * plotHeight : null;
    return { x, priceY, ttmPeY, nonGaapTtmPeY, quarterlyPeY, date };
  });

  const pricePath = points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.priceY.toFixed(2)}`)
    .join(' ');
  const ttmPePath = buildNullablePath(points, 'ttmPeY');
  const nonGaapTtmPePath = buildNullablePath(points, 'nonGaapTtmPeY');
  const quarterlyPePath = buildNullablePath(points, 'quarterlyPeY');
  const forwardPePoint = forwardPeSource
    ? {
      x: plotLeft + ((new Date(`${forwardPeSource.date}T00:00:00Z`).getTime() - minTime) / timeSpan) * plotWidth,
      y: plotBottom - ((forwardPeSource.value - peMin) / peSpan) * plotHeight,
      value: forwardPeSource.value,
      date: forwardPeSource.date
    }
    : null;

  const yTicksPrice = buildLinearTicks(priceMin, priceMax, 5).map((value) => ({
    value,
    y: plotBottom - ((value - priceMin) / priceSpan) * plotHeight
  }));
  const yTicksPe = peValues.length
    ? buildLinearTicks(peMin, peMax, 5).map((value) => ({
      value,
      y: plotBottom - ((value - peMin) / peSpan) * plotHeight
    }))
    : [];
  const xTicks = buildDateTicks(dates, 6, longRange).map((item) => ({
    ...item,
    x: plotLeft + ((new Date(`${item.time}T00:00:00Z`).getTime() - minTime) / timeSpan) * plotWidth
  }));
  const yearSeparators = buildComparisonYearSeparators(dates).map((date) => ({
    date,
    x: plotLeft + ((new Date(`${date}T00:00:00Z`).getTime() - minTime) / timeSpan) * plotWidth,
    year: String(date).slice(0, 4)
  }));

  return {
    hasData: true,
    width,
    height,
    plotLeft,
    plotRight,
    plotTop,
    plotBottom,
    pricePath,
    ttmPePath,
    nonGaapTtmPePath,
    quarterlyPePath,
    forwardPePoint,
    pricePoints: points.map((point) => ({ x: point.x, y: point.priceY })),
    ttmPePoints: points.filter((point) => point.ttmPeY != null).map((point) => ({ x: point.x, y: point.ttmPeY })),
    nonGaapTtmPePoints: points.filter((point) => point.nonGaapTtmPeY != null).map((point) => ({ x: point.x, y: point.nonGaapTtmPeY })),
    quarterlyPePoints: points.filter((point) => point.quarterlyPeY != null).map((point) => ({ x: point.x, y: point.quarterlyPeY })),
    yTicksPrice,
    yTicksPe,
    xTicks,
    yearSeparators,
    firstDate: dates[0],
    lastDate: dates[dates.length - 1],
    priceMin,
    priceMax,
    peMin,
    peMax,
    hasPeData: peValues.length > 0
  };
}

function toFiniteNumber(value) {
  if (value == null) return NaN;
  const n = Number(value);
  return Number.isFinite(n) ? n : NaN;
}

function buildNullablePath(points, key) {
  let open = false;
  return points
    .map((point) => {
      if (point[key] == null) {
        open = false;
        return '';
      }
      const prefix = open ? 'L' : 'M';
      open = true;
      return `${prefix} ${point.x.toFixed(2)} ${point[key].toFixed(2)}`;
    })
    .filter(Boolean)
    .join(' ');
}

function buildComparisonYearSeparators(dates) {
  const result = [];
  let prevYear = null;
  for (let i = 0; i < dates.length; i += 1) {
    const year = String(dates[i]).slice(0, 4);
    if (prevYear != null && year !== prevYear) {
      result.push(dates[i]);
    }
    prevYear = year;
  }
  return result;
}

function buildLinearTicks(minValue, maxValue, count) {
  if (minValue === maxValue) {
    const base = minValue;
    return [base - 1, base, base + 1];
  }

  const ticks = [];
  const step = (maxValue - minValue) / Math.max(count - 1, 1);
  for (let i = 0; i < count; i += 1) {
    ticks.push(minValue + step * i);
  }
  return ticks;
}

function buildDateTicks(dates, maxTicks, longRange = false) {
  if (longRange) {
    const yearly = [];
    let lastYear = '';

    for (const date of dates) {
      const year = String(date).slice(0, 4);
      if (year !== lastYear) {
        yearly.push({
          time: date,
          label: year
        });
        lastYear = year;
      }
    }

    if (!yearly.length) {
      return [];
    }

    const lastDate = dates[dates.length - 1];
    const lastYearLabel = String(lastDate).slice(0, 4);
    if (yearly[yearly.length - 1].time !== lastDate && yearly[yearly.length - 1].label !== lastYearLabel) {
      yearly.push({
        time: lastDate,
        label: lastYearLabel
      });
    }

    if (yearly.length <= maxTicks) {
      return yearly;
    }

    const sampled = [];
    const step = Math.max(Math.floor((yearly.length - 1) / (maxTicks - 1)), 1);
    for (let i = 0; i < yearly.length; i += step) {
      sampled.push(yearly[i]);
    }
    if (sampled[sampled.length - 1].time !== yearly[yearly.length - 1].time) {
      sampled.push(yearly[yearly.length - 1]);
    }
    return sampled;
  }

  const total = dates.length;
  if (total <= maxTicks) {
    return dates.map((date) => ({
      time: date,
      label: formatDateTick(date, longRange)
    }));
  }

  const result = [];
  const step = Math.max(Math.floor((total - 1) / (maxTicks - 1)), 1);
  for (let i = 0; i < total; i += step) {
    result.push({
      time: dates[i],
      label: formatDateTick(dates[i], longRange)
    });
  }

  if (result[result.length - 1].time !== dates[total - 1]) {
    result.push({
      time: dates[total - 1],
      label: formatDateTick(dates[total - 1], longRange)
    });
  }

  return result;
}

function formatDateTick(date, longRange = false) {
  if (longRange) {
    return new Date(`${date}T00:00:00Z`).toLocaleDateString('en-US', { year: '2-digit', month: 'short' });
  }
  return new Date(`${date}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: '2-digit' });
}
