const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    },
    ...options
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed with status ${response.status}`);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

export function getPositions() {
  return request('/positions');
}

export function addPosition(payload) {
  return request('/positions', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function getTransactions() {
  return request('/transactions');
}

export function recordTransaction(payload) {
  return request('/transactions', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function deleteTransaction(transactionId) {
  return request(`/transactions/${transactionId}`, {
    method: 'DELETE'
  });
}

export function updateTransaction(transactionId, payload) {
  return request(`/transactions/${transactionId}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function getStockNotes() {
  return request('/stock-notes');
}

export function updateStockNote(symbol, payload) {
  return request(`/stock-notes/${encodeURIComponent(symbol)}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function getHoldings() {
  return request('/portfolio/holdings');
}

export function getSummary() {
  return request('/portfolio/summary');
}

export function getAssetCurve() {
  return request('/portfolio/asset-curve');
}

export function refreshPrices() {
  return request('/portfolio/prices/refresh', {
    method: 'POST'
  });
}

export function syncMarketClose() {
  return request('/portfolio/market-close/sync', {
    method: 'POST'
  });
}

export function createCashAdjustment(payload) {
  return request('/cash-adjustments', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function getDividends() {
  return request('/dividends');
}

export function createDividend(payload) {
  return request('/dividends', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function getMonthlyDividends() {
  return request('/dividends/monthly');
}

export async function importDividendsCsv(file) {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch(`${API_BASE_URL}/dividends/import-csv`, {
    method: 'POST',
    body: formData
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed with status ${response.status}`);
  }

  return response.json();
}

export function getPriceHistory(symbol, from, to) {
  const params = new URLSearchParams({ symbol });
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  return request(`/portfolio/history/prices?${params.toString()}`);
}

export function getPeHistory(symbol, from, to) {
  const params = new URLSearchParams({ symbol });
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  return request(`/portfolio/history/pe?${params.toString()}`);
}

export async function importTransactionsCsv(file, dryRun = false) {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch(`${API_BASE_URL}/transactions/import-csv?dryRun=${dryRun}`, {
    method: 'POST',
    body: formData
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed with status ${response.status}`);
  }

  return response.json();
}

export async function downloadCsvImportErrors(file) {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch(`${API_BASE_URL}/transactions/import-csv/errors`, {
    method: 'POST',
    body: formData
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed with status ${response.status}`);
  }

  return response.blob();
}
