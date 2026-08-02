// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DataReviewConsolePage from './DataReviewConsolePage.jsx';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, options) => options?.defaultValue || key })
}));

vi.mock('../hooks/useIsMobile', () => ({ default: () => false }));
vi.mock('../components/ConfirmDialog', () => ({ default: () => null }));
vi.mock('../api', () => ({
  approveDataReviewRow: vi.fn(),
  batchUpdateDataReviewStatus: vi.fn(),
  getDataReviewHistory: vi.fn(),
  getDataReviewRows: vi.fn(),
  getDataReviewSources: vi.fn(),
  getDataReviewSummary: vi.fn(),
  markDataReviewRowUncertain: vi.fn(),
  patchDataReviewRow: vi.fn(),
  previewDataReviewBatch: vi.fn(),
  rejectDataReviewRow: vi.fn(),
  rollbackDataReviewAuditLog: vi.fn()
}));

import { getDataReviewHistory, getDataReviewRows, getDataReviewSources, getDataReviewSummary } from '../api';

const source = {
  name: 'fundamentals',
  label: 'Fundamentals',
  displayFields: ['symbol', 'asOfDate', 'currencyCode', 'revenue'],
  editableFields: ['currencyCode', 'basicEps']
};

const row = {
  source: 'fundamentals',
  recordId: '2093',
  rawValues: { symbol: 'IAU', asOfDate: '2014-06-30', currencyCode: 'USD', revenue: null, basicEps: 0.21 },
  effectiveValues: { symbol: 'IAU', asOfDate: '2014-06-30', currencyCode: 'USD', revenue: null, basicEps: 0.21 },
  reviewedValues: {},
  reviewStatus: 'pending',
  reasonCode: null,
  anomalyFlags: [{ code: 'revenue_missing', message: 'revenue missing', severity: 'medium' }],
  anomalies: [{ code: 'revenue_missing', message: 'revenue missing', severity: 'medium' }],
  riskLevel: 'high',
  anomalyCount: 1,
  revision: '0'
};

describe('DataReviewConsolePage', () => {
  beforeEach(() => {
    window.matchMedia = vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() });
    getDataReviewSources.mockResolvedValue([source]);
    getDataReviewSummary.mockResolvedValue({ sources: [{ name: 'fundamentals', total: 1123, pending: 1123, attention: 283, anomalies: 283, approved: 0, corrected: 0, rejected: 0, uncertain: 0, completed: 0 }] });
    getDataReviewRows.mockResolvedValue({ source: 'fundamentals', page: 0, size: 25, totalElements: 283, totalPages: 12, rows: [row] });
    getDataReviewHistory.mockResolvedValue([]);
  });

  afterEach(() => cleanup());

  it('renders the compact risk workbench and keeps the decision rail visible after opening a row', async () => {
    render(<DataReviewConsolePage />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Review' })).toBeInTheDocument());
    expect(document.querySelector('[data-review-source-card="fundamentals"]')).toBeInTheDocument();
    expect(document.querySelector('[data-review-filter-bar]')).toBeInTheDocument();
    expect(document.querySelector('.review-queue-table .review-record-cell')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Review' }));

    await waitFor(() => expect(document.querySelector('[data-review-risk-banner]')).toBeInTheDocument());
    expect(screen.getByText('revenue missing')).toBeInTheDocument();
    expect(document.querySelector('[data-review-decision-panel]')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'review.saveCorrected' })).toBeInTheDocument();
  });
});
