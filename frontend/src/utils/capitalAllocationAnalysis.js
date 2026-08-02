function finiteNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function sortByDate(rows, dateKey) {
  return [...(rows || [])]
    .filter((row) => row?.[dateKey])
    .sort((a, b) => new Date(`${a[dateKey]}T00:00:00Z`) - new Date(`${b[dateKey]}T00:00:00Z`));
}

export function calculateCapitalAllocationSummary(history) {
  const repurchases = sortByDate(history?.shareRepurchases, 'fiscalPeriodEnd');
  const shares = sortByDate(history?.sharesOutstanding, 'asOfDate');
  const latestRepurchase = repurchases.at(-1) || null;
  const latestShares = shares.at(-1) || null;
  const latestDate = latestShares?.asOfDate ? new Date(`${latestShares.asOfDate}T00:00:00Z`) : null;
  const yearAgoShares = latestDate
    ? [...shares].reverse().find((row) => {
      const days = (latestDate - new Date(`${row.asOfDate}T00:00:00Z`)) / 86400000;
      return days >= 300 && days <= 460;
    }) || null
    : null;
  const latestValue = finiteNumber(latestShares?.sharesOutstanding);
  const yearAgoValue = finiteNumber(yearAgoShares?.sharesOutstanding);
  const yoyNetChange = latestValue != null && yearAgoValue != null ? latestValue - yearAgoValue : null;
  const tableRows = [...new Set([...repurchases.map((row) => row.fiscalPeriodEnd), ...shares.map((row) => row.asOfDate)])]
    .sort((a, b) => new Date(`${b}T00:00:00Z`) - new Date(`${a}T00:00:00Z`))
    .map((date) => ({
      date,
      repurchase: repurchases.find((row) => row.fiscalPeriodEnd === date) || null,
      shares: shares.find((row) => row.asOfDate === date) || null
    }));

  return { repurchases, shares, latestRepurchase, latestShares, yearAgoShares, yoyNetChange, tableRows };
}
