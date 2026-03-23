export function formatCurrency(value) {
  return Number(value).toFixed(2);
}

export function formatDateInput(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function buildAssetChart(assetCurve) {
  if (!assetCurve.length) {
    return { hasData: false };
  }

  const width = 900;
  const height = 320;
  const plotLeft = 14;
  const plotRight = width - 110;
  const plotTop = 16;
  const plotBottom = height - 46;

  const values = assetCurve.map((point) => Number(point.totalAssets));
  const minValue = Math.min(...values);
  const maxValue = Math.max(...values);

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
    const y = plotBottom - ((Number(point.totalAssets) - yMin) / ySpan) * (plotBottom - plotTop);
    return { x, y, timestamp: point.timestamp, value: Number(point.totalAssets) };
  });

  const linePath = points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`)
    .join(' ');

  const first = points[0];
  const last = points[points.length - 1];
  const areaPath = `${linePath} L ${last.x.toFixed(2)} ${plotBottom.toFixed(2)} L ${first.x.toFixed(2)} ${plotBottom.toFixed(2)} Z`;

  const yTicks = yTicksValues.map((value) => {
    const y = plotBottom - ((value - yMin) / ySpan) * (plotBottom - plotTop);
    return { y, value };
  });

  const xTicks = buildTimeTicks(minTime, maxTime).map((timeValue) => {
    const x = plotLeft + ((timeValue - minTime) / timeSpan) * (plotRight - plotLeft);
    const date = new Date(timeValue);
    const isYearMark = date.getMonth() === 0;
    const label = isYearMark
      ? String(date.getFullYear())
      : date.toLocaleDateString(undefined, { month: 'short' });
    return { x, label, isYearMark };
  });

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
    linePath,
    areaPath,
    yTicks,
    xTicks,
    yearSeparators,
    lastX: last.x,
    lastY: last.y,
    lastValue: last.value,
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

function buildTimeTicks(minTime, maxTime) {
  const minDate = new Date(minTime);
  const maxDate = new Date(maxTime);

  const ticks = [];
  const cursor = new Date(minDate.getFullYear(), minDate.getMonth(), 1);
  const totalMonths = (maxDate.getFullYear() - minDate.getFullYear()) * 12 + (maxDate.getMonth() - minDate.getMonth());
  const monthStep = totalMonths > 24 ? 3 : 2;

  while (cursor.getTime() <= maxTime) {
    ticks.push(cursor.getTime());
    cursor.setMonth(cursor.getMonth() + monthStep);
  }

  if (!ticks.length || ticks[0] > minTime) ticks.unshift(minTime);
  if (ticks[ticks.length - 1] < maxTime) ticks.push(maxTime);

  return ticks;
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

export function buildComparisonChart(priceHistory, peHistory) {
  const priceMap = new Map(priceHistory.map((point) => [point.tradeDate, Number(point.closePrice)]));
  const peMap = new Map(peHistory.map((point) => [point.tradeDate, Number(point.trailingPe)]));

  const dates = [...priceMap.keys()]
    .filter((date) => peMap.has(date))
    .sort((a, b) => new Date(a) - new Date(b));

  if (dates.length === 0) {
    return { hasData: false };
  }

  const width = 760;
  const height = 300;
  const padding = 28;

  const priceValues = dates.map((date) => priceMap.get(date));
  const peValues = dates.map((date) => peMap.get(date));

  const priceMin = Math.min(...priceValues);
  const priceMax = Math.max(...priceValues);
  const peMin = Math.min(...peValues);
  const peMax = Math.max(...peValues);

  const priceSpan = Math.max(priceMax - priceMin, 1);
  const peSpan = Math.max(peMax - peMin, 1);

  const points = dates.map((date, index) => {
    const x = padding + (index * (width - padding * 2)) / Math.max(dates.length - 1, 1);
    const priceY = height - padding - ((priceMap.get(date) - priceMin) / priceSpan) * (height - padding * 2);
    const peY = height - padding - ((peMap.get(date) - peMin) / peSpan) * (height - padding * 2);
    return { x, priceY, peY };
  });

  const pricePath = points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.priceY.toFixed(2)}`)
    .join(' ');
  const pePath = points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.peY.toFixed(2)}`)
    .join(' ');

  return {
    hasData: true,
    width,
    height,
    pricePath,
    pePath,
    firstDate: dates[0],
    lastDate: dates[dates.length - 1],
    priceMin,
    priceMax,
    peMin,
    peMax
  };
}
