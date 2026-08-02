const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '');

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

export function getPosition(symbol) {
  return request(`/positions/${encodeURIComponent(symbol)}`);
}

export function addPosition(payload) {
  return request('/positions', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function updateSharesOutstandingOverride(symbol, sharesOutstandingOverride) {
  return request(`/positions/${encodeURIComponent(symbol)}/shares-outstanding-override`, {
    method: 'PUT',
    body: JSON.stringify({ sharesOutstandingOverride })
  });
}

export function updatePositionMetadata(symbol, payload) {
  return request(`/positions/${encodeURIComponent(symbol)}/metadata`, {
    method: 'PUT',
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

export function getFundamentalNotes() {
  return request('/fundamental-notes');
}

export function updateFundamentalNote(symbol, payload) {
  return request(`/fundamental-notes/${encodeURIComponent(symbol)}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function getValuationNotes() {
  return request('/valuation-notes');
}

export function updateValuationNote(symbol, payload) {
  return request(`/valuation-notes/${encodeURIComponent(symbol)}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function getOverviewNotes() {
  return request('/overview-notes');
}

export function updateOverviewNote(noteType, payload) {
  return request(`/overview-notes/${encodeURIComponent(noteType)}`, {
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

export function exportPortfolioJson() {
  return request('/portfolio/export');
}

export function exportPortfolioJsonV2() {
  return request('/portfolio/export/v2');
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

export function getCashAdjustments() {
  return request('/cash-adjustments');
}

export function updateCashAdjustment(adjustmentId, payload) {
  return request(`/cash-adjustments/${adjustmentId}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function deleteCashAdjustment(adjustmentId) {
  return request(`/cash-adjustments/${adjustmentId}`, { method: 'DELETE' });
}

export async function importCashAdjustmentsCsv(file) {
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(`${API_BASE_URL}/cash-adjustments/import-csv`, { method: 'POST', body: formData });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed with status ${response.status}`);
  }
  return response.json();
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

export function updateDividend(dividendId, payload) {
  return request(`/dividends/${dividendId}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function deleteDividend(dividendId) {
  return request(`/dividends/${dividendId}`, {
    method: 'DELETE'
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

export function getQuarterlyFundamentals(symbol, from, to) {
  const params = new URLSearchParams({ symbol });
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  return request(`/portfolio/history/fundamentals?${params.toString()}`);
}

export function getCapitalAllocationHistory(symbol, from, to) {
  const params = new URLSearchParams({ symbol });
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  return request(`/portfolio/history/capital-allocation?${params.toString()}`);
}

export function getMarketAssumptions(symbol) {
  const params = new URLSearchParams({ symbol });
  return request(`/portfolio/market-assumptions?${params.toString()}`);
}

export function getValuation(symbol) {
  return request(`/valuations/${encodeURIComponent(symbol)}`);
}

export function evaluateValuation(symbol, scenarioType, assumptions) {
  return request(`/valuations/${encodeURIComponent(symbol)}/evaluate`, {
    method: 'POST',
    body: JSON.stringify({ scenarioType, assumptions })
  });
}

export function saveValuationScenario(symbol, scenarioType, assumptions) {
  return request(`/valuations/${encodeURIComponent(symbol)}/scenarios/${encodeURIComponent(scenarioType)}`, {
    method: 'PUT',
    body: JSON.stringify({ modelMode: 'AUTO', assumptions })
  });
}

export function resetValuationScenario(symbol, scenarioType) {
  return request(`/valuations/${encodeURIComponent(symbol)}/scenarios/${encodeURIComponent(scenarioType)}`, {
    method: 'DELETE'
  });
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

export function getDataReviewSources() {
  return request('/admin/data-review/sources');
}

export function getDataReviewSummary() {
  return request('/admin/data-review/summary');
}

export function getDataReviewRows(source, filters = {}) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, value);
    }
  });
  const suffix = params.toString() ? `?${params.toString()}` : '';
  return request(`/admin/data-review/${encodeURIComponent(source)}${suffix}`);
}

export function patchDataReviewRow(source, id, payload) {
  return request(`/admin/data-review/${encodeURIComponent(source)}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export function approveDataReviewRow(source, id, payload = {}) {
  return request(`/admin/data-review/${encodeURIComponent(source)}/${encodeURIComponent(id)}/approve`, {
    method: 'POST',
    body: JSON.stringify(typeof payload === 'string' ? { note: payload } : payload)
  });
}

export function rejectDataReviewRow(source, id, payload = {}) {
  return request(`/admin/data-review/${encodeURIComponent(source)}/${encodeURIComponent(id)}/reject`, {
    method: 'POST',
    body: JSON.stringify(typeof payload === 'string' ? { note: payload } : payload)
  });
}

export function markDataReviewRowUncertain(source, id, payload = {}) {
  return request(`/admin/data-review/${encodeURIComponent(source)}/${encodeURIComponent(id)}/uncertain`, {
    method: 'POST',
    body: JSON.stringify(typeof payload === 'string' ? { note: payload } : payload)
  });
}

export function batchUpdateDataReviewStatus(source, payload) {
  return request(`/admin/data-review/${encodeURIComponent(source)}/batch-status`, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function previewDataReviewBatch(source, payload) {
  return request(`/admin/data-review/${encodeURIComponent(source)}/batch-preview`, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function rollbackDataReviewAuditLog(source, id, auditId, payload = {}) {
  return request(`/admin/data-review/${encodeURIComponent(source)}/${encodeURIComponent(id)}/rollback/${encodeURIComponent(auditId)}`, {
    method: 'POST',
    body: JSON.stringify(typeof payload === 'string' ? { note: payload } : payload)
  });
}

export function getDataReviewHistory(source, id) {
  return request(`/admin/data-review/${encodeURIComponent(source)}/${encodeURIComponent(id)}/history`);
}
