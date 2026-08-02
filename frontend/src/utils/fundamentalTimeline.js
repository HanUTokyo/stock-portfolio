const DAY_MS = 24 * 60 * 60 * 1000;
const QUARTER_END_TOLERANCE_DAYS = 10;

function parseUtcDate(date) {
  const d = new Date(`${date}T00:00:00Z`);
  return Number.isNaN(d.getTime()) ? null : d;
}

function calendarQuarterEnds(year) {
  return [1, 2, 3, 4].map((quarter) => ({
    year,
    quarter,
    date: new Date(Date.UTC(year, quarter * 3, 0))
  }));
}

export function parseQuarter(date) {
  const d = parseUtcDate(date);
  if (!d) return null;

  const candidates = [
    ...calendarQuarterEnds(d.getUTCFullYear() - 1),
    ...calendarQuarterEnds(d.getUTCFullYear()),
    ...calendarQuarterEnds(d.getUTCFullYear() + 1)
  ];
  const nearest = candidates
    .map((candidate) => ({
      ...candidate,
      distanceDays: Math.abs(candidate.date.getTime() - d.getTime()) / DAY_MS
    }))
    .sort((a, b) => a.distanceDays - b.distanceDays)[0];

  if (nearest && nearest.distanceDays <= QUARTER_END_TOLERANCE_DAYS) {
    return {
      year: nearest.year,
      quarter: nearest.quarter,
      index: nearest.year * 4 + nearest.quarter - 1
    };
  }

  const year = d.getUTCFullYear();
  const quarter = Math.floor(d.getUTCMonth() / 3) + 1;
  return { year, quarter, index: year * 4 + quarter - 1 };
}

function quarterEndDateFromIndex(index) {
  const year = Math.floor(index / 4);
  const quarter = index % 4;
  const month = quarter * 3 + 3;
  return new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10);
}

export function fillQuarterlyFundamentalGaps(rows) {
  const sorted = [...(rows || [])]
    .filter((row) => row && row.asOfDate)
    .sort((a, b) => new Date(`${a.asOfDate}T00:00:00Z`) - new Date(`${b.asOfDate}T00:00:00Z`));
  if (sorted.length < 2) return sorted;

  const byQuarter = new Map();
  sorted.forEach((row) => {
    const parsed = parseQuarter(row.asOfDate);
    if (parsed && !byQuarter.has(parsed.index)) {
      byQuarter.set(parsed.index, row);
    }
  });

  const indexes = [...byQuarter.keys()].sort((a, b) => a - b);
  if (!indexes.length) return sorted;

  const filled = [];
  for (let index = indexes[0]; index <= indexes[indexes.length - 1]; index += 1) {
    const existing = byQuarter.get(index);
    filled.push(existing || {
      asOfDate: quarterEndDateFromIndex(index),
      forecast: false,
      missing: true
    });
  }
  return filled;
}
