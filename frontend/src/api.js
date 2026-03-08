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

export function getHoldings() {
  return request('/portfolio/holdings');
}

export function getSummary() {
  return request('/portfolio/summary');
}
