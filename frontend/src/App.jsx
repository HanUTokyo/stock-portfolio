import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import {
  createCashAdjustment,
  createDividend,
  deleteCashAdjustment,
  deleteDividend,
  downloadCsvImportErrors,
  deleteTransaction,
  exportPortfolioJson,
  exportPortfolioJsonV2,
  getDividends,
  getAssetCurve,
  getCashAdjustments,
  getCapitalAllocationHistory,
  getFundamentalNotes,
  getHoldings,
  getMarketAssumptions,
  getValuation,
  getValuationNotes,
  getMonthlyDividends,
  getOverviewNotes,
  getPeHistory,
  getPositions,
  getPriceHistory,
  getQuarterlyFundamentals,
  getStockNotes,
  getSummary,
  getTransactions,
  importDividendsCsv,
  importCashAdjustmentsCsv,
  importTransactionsCsv,
  recordTransaction,
  refreshPrices,
  syncMarketClose,
  updateDividend,
  updateCashAdjustment,
  updateFundamentalNote,
  updateOverviewNote,
  updatePositionMetadata,
  updateSharesOutstandingOverride,
  updateStockNote,
  updateValuationNote,
  updateTransaction
} from './api';
import OverviewPage from './pages/OverviewPage';
import MarketDataPage from './pages/MarketDataPage';
import CashPage from './pages/CashPage';
import TransactionsPage from './pages/TransactionsPage';
import DividendsPage from './pages/DividendsPage';
import NotesPage from './pages/NotesPage';
import ClassificationsPage from './pages/ClassificationsPage';
import DataReviewConsolePage from './pages/DataReviewConsolePage';
import AppHeader from './components/AppHeader';
import BottomNav from './components/BottomNav';
import StatusToast from './components/StatusToast';
import { supportedLanguages } from './i18n';
import { useAutoTranslate } from './i18n/useAutoTranslate';
import { formatDateInput } from './utils/charts';

function createEmptyTransaction() {
  return {
    symbol: '',
    type: 'BUY',
    quantity: '',
    price: '',
    note: '',
    tradeDate: formatDateInput(new Date())
  };
}

const emptyDividend = { symbol: '', amount: '', paidDate: formatDateInput(new Date()) };
const emptyCashAdjustment = { amount: '', tradeDate: formatDateInput(new Date()) };
const dipHistoryLookbackDays = 45;
const autoSyncMarketData = import.meta.env.VITE_AUTO_SYNC_MARKET_DATA !== 'false';

function buildDefaultHistoryRange() {
  const to = new Date();
  const from = new Date(to);
  from.setFullYear(from.getFullYear() - 15);
  return {
    from: formatDateInput(from),
    to: formatDateInput(to)
  };
}

function buildDipHistoryRange() {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - dipHistoryLookbackDays);
  return {
    from: formatDateInput(from),
    to: formatDateInput(to)
  };
}

export default function App() {
  const location = useLocation();
  const { t, i18n } = useTranslation();
  const autoTranslateRef = useAutoTranslate();
  const defaultHistoryRange = buildDefaultHistoryRange();
  const [theme, setTheme] = useState(() => {
    const saved = window.localStorage.getItem('portfolio-theme');
    return saved === 'light' ? 'light' : 'dark';
  });
  const [holdings, setHoldings] = useState([]);
  const [positions, setPositions] = useState([]);
  const [summary, setSummary] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [stockNotes, setStockNotes] = useState([]);
  const [fundamentalNotes, setFundamentalNotes] = useState([]);
  const [valuationNotes, setValuationNotes] = useState([]);
  const [overviewNotes, setOverviewNotes] = useState([]);
  const [dividends, setDividends] = useState([]);
  const [cashAdjustments, setCashAdjustments] = useState([]);
  const [monthlyDividends, setMonthlyDividends] = useState([]);
  const [assetCurve, setAssetCurve] = useState([]);
  const [dipPriceHistoryBySymbol, setDipPriceHistoryBySymbol] = useState({});
  const [priceHistory, setPriceHistory] = useState([]);
  const [peHistory, setPeHistory] = useState([]);
  const [quarterlyFundamentals, setQuarterlyFundamentals] = useState([]);
  const [capitalAllocationHistory, setCapitalAllocationHistory] = useState(null);
  const [marketAssumptions, setMarketAssumptions] = useState(null);
  const [valuation, setValuation] = useState(null);
  const [historySymbol, setHistorySymbol] = useState('');
  const [historyFrom, setHistoryFrom] = useState(defaultHistoryRange.from);
  const [historyTo, setHistoryTo] = useState(defaultHistoryRange.to);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyRequested, setHistoryRequested] = useState(false);
  const [transactionForm, setTransactionForm] = useState(createEmptyTransaction());
  const [dividendForm, setDividendForm] = useState(emptyDividend);
  const [cashAdjustmentForm, setCashAdjustmentForm] = useState(emptyCashAdjustment);
  const [importFile, setImportFile] = useState(null);
  const [importLoading, setImportLoading] = useState(false);
  const [importResult, setImportResult] = useState(null);
  const [actionResult, setActionResult] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [dashboardBootstrapped, setDashboardBootstrapped] = useState(false);

  useEffect(() => {
    if (!actionResult) return undefined;
    const timeoutId = window.setTimeout(() => setActionResult(''), 5000);
    return () => window.clearTimeout(timeoutId);
  }, [actionResult]);
  const dashboardRequestRef = useRef(0);
  const isDataReviewRoute = location.pathname.startsWith('/admin/data-review');

  async function loadDashboard() {
    const requestId = dashboardRequestRef.current + 1;
    dashboardRequestRef.current = requestId;
    setLoading(true);
    setError('');

    try {
      const [coreData, assetCurveData, fundamentalNotesData, valuationNotesData] = await Promise.all([
        Promise.all([
          getHoldings(),
          getPositions(),
          getSummary(),
          getTransactions(),
          getDividends(),
          getCashAdjustments(),
          getMonthlyDividends(),
          getStockNotes(),
          getOverviewNotes()
        ]),
        getAssetCurve().catch(() => []),
        getFundamentalNotes().catch(() => []),
        getValuationNotes().catch(() => [])
      ]);
      const [holdingsData, positionsData, summaryData, transactionsData, dividendsData, cashAdjustmentsData, monthlyDividendsData, stockNotesData, overviewNotesData] = coreData;
      if (requestId !== dashboardRequestRef.current) return;

      const symbolsForDipHistory = [...new Set((holdingsData || [])
        .filter((holding) => Number(holding.quantity) > 0)
        .map((holding) => String(holding.symbol || '').trim().toUpperCase())
        .filter(Boolean))];
      const dipHistoryRange = buildDipHistoryRange();

      setHoldings(holdingsData);
      setPositions(positionsData);
      setSummary(summaryData);
      setTransactions(transactionsData);
      setDividends(dividendsData);
      setCashAdjustments(cashAdjustmentsData);
      setMonthlyDividends(monthlyDividendsData);
      setStockNotes(stockNotesData);
      setOverviewNotes(overviewNotesData);
      setAssetCurve(assetCurveData);
      setFundamentalNotes(fundamentalNotesData);
      setValuationNotes(valuationNotesData);

      Promise.all(symbolsForDipHistory.map((symbol) => (
        getPriceHistory(symbol, dipHistoryRange.from, dipHistoryRange.to)
          .then((points) => [symbol, points])
          .catch(() => [symbol, []])
      )))
        .then((dipHistoryEntries) => {
          if (requestId === dashboardRequestRef.current) {
            setDipPriceHistoryBySymbol(Object.fromEntries(dipHistoryEntries));
          }
        })
        .catch(() => undefined);

    } catch (e) {
      if (requestId === dashboardRequestRef.current) setError(e.message);
    } finally {
      if (requestId === dashboardRequestRef.current) setLoading(false);
    }
  }

  async function loadHistory(filters = null) {
    const symbol = String(filters?.symbol ?? historySymbol ?? '').trim().toUpperCase();
    const from = filters?.from ?? historyFrom;
    const to = filters?.to ?? historyTo;

    if (!symbol) {
      return;
    }

    setHistorySymbol(symbol);
    setHistoryFrom(from);
    setHistoryTo(to);
    setHistoryLoading(true);
    setHistoryRequested(true);
    try {
      const [prices, pe, fundamentals, capitalAllocation, assumptions, valuationData] = await Promise.all([
        getPriceHistory(symbol, from, to),
        getPeHistory(symbol, from, to),
        getQuarterlyFundamentals(symbol, from, to),
        getCapitalAllocationHistory(symbol, from, to).catch(() => ({ symbol, shareRepurchases: [], sharesOutstanding: [] })),
        getMarketAssumptions(symbol).catch((e) => ({
          symbol,
          warning: e.message,
          riskFreeRate: null,
          beta: null
        })),
        getValuation(symbol).catch((e) => ({
          symbol,
          applicability: { applicable: false, status: 'ERROR', reasons: [e.message] },
          scenarios: [],
          diagnostics: []
        }))
      ]);
      setPriceHistory(prices);
      setPeHistory(pe);
      setQuarterlyFundamentals(fundamentals);
      setCapitalAllocationHistory(capitalAllocation);
      setMarketAssumptions(assumptions);
      setValuation(valuationData);
    } catch (e) {
      setError(e.message);
    } finally {
      setHistoryLoading(false);
    }
  }

  async function refreshHistoryIfRequested() {
    if (!historyRequested) {
      return;
    }
    await loadHistory();
  }

  async function bootstrapMarketData() {
    try {
      await loadDashboard();
      if (autoSyncMarketData) {
        // Both operations update the same position cache rows. Run them in
        // sequence so a page bootstrap never races its own market sync.
        await refreshPrices();
        await syncMarketClose();
        await loadDashboard();
      }
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    if (isDataReviewRoute || dashboardBootstrapped) {
      return;
    }
    setDashboardBootstrapped(true);
    void bootstrapMarketData();
  }, [isDataReviewRoute, dashboardBootstrapped]);

  useEffect(() => {
    if (
      isDataReviewRoute ||
      location.pathname !== '/market' ||
      historyRequested ||
      historyLoading ||
      !holdings.length
    ) {
      return;
    }
    const symbol = historySymbol || holdings[0]?.symbol;
    if (symbol) {
      void loadHistory({ symbol, from: historyFrom, to: historyTo });
    }
  }, [isDataReviewRoute, location.pathname, holdings, historyRequested, historyLoading]);

  useEffect(() => {
    document.body.setAttribute('data-theme', theme);
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.setAttribute('data-mantine-color-scheme', theme);
    window.localStorage.setItem('portfolio-theme', theme);
  }, [theme]);

  function handleLanguageChange(languageCode) {
    i18n.changeLanguage(languageCode);
    window.localStorage.setItem('portfolio-language', languageCode);
  }

  async function handleRecordTransaction(e) {
    e.preventDefault();
    setError('');

    try {
      await recordTransaction({
        symbol: String(transactionForm.symbol || '').trim().toUpperCase(),
        type: transactionForm.type,
        quantity: Number(transactionForm.quantity),
        price: Number(transactionForm.price),
        note: transactionForm.note,
        executedAt: new Date(`${transactionForm.tradeDate}T00:00:00`).toISOString()
      });
      setTransactionForm(createEmptyTransaction());
      await loadDashboard();
      await refreshHistoryIfRequested();
    } catch (err) {
      setError(err.message);
    }
  }

  async function handleDeleteTransaction(transactionId) {
    setError('');
    setActionResult('');

    try {
      await deleteTransaction(transactionId);
      setActionResult(t('message.transactionDeleted', { id: transactionId }));
      await loadDashboard();
      await refreshHistoryIfRequested();
    } catch (e) {
      setError(e.message);
    }
  }

  async function handleUpdateTransaction(transactionId, payload) {
    setError('');
    setActionResult('');

    try {
      await updateTransaction(transactionId, payload);
      setActionResult(t('message.transactionUpdated', { id: transactionId }));
      await loadDashboard();
      await refreshHistoryIfRequested();
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleAddDividend(e) {
    e.preventDefault();
    setError('');
    setActionResult('');

    try {
      await createDividend({
        symbol: String(dividendForm.symbol || '').trim().toUpperCase(),
        amount: Number(dividendForm.amount),
        paidDate: dividendForm.paidDate
      });
      setDividendForm({ ...emptyDividend, paidDate: formatDateInput(new Date()) });
      setActionResult(t('message.dividendRecorded'));
      await loadDashboard();
    } catch (e) {
      setError(e.message);
    }
  }

  async function handleUpdateDividend(dividendId, payload) {
    setError('');
    setActionResult('');

    try {
      await updateDividend(dividendId, payload);
      setActionResult(t('message.dividendUpdated', { id: dividendId }));
      await loadDashboard();
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleDeleteDividend(dividendId) {
    setError('');
    setActionResult('');

    try {
      await deleteDividend(dividendId);
      setActionResult(t('message.dividendDeleted', { id: dividendId }));
      await loadDashboard();
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleImportDividends(file) {
    setError('');
    setActionResult('');
    try {
      const result = await importDividendsCsv(file);
      setActionResult(t('message.dividendCsvImported', {
        imported: result.importedRows,
        failed: result.failedRows,
        skipped: result.skippedRows
      }));
      await loadDashboard();
      return result;
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleSaveStockNote(symbol, note) {
    setError('');
    setActionResult('');
    try {
      const saved = await updateStockNote(symbol, { note });
      setStockNotes((prev) => {
        const next = prev.filter((item) => item.symbol !== saved.symbol);
        next.push(saved);
        return next.sort((a, b) => a.symbol.localeCompare(b.symbol));
      });
      setActionResult(t('message.stockNoteSaved', { symbol: saved.symbol }));
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleSaveFundamentalNote(symbol, note) {
    setError('');
    setActionResult('');
    try {
      const saved = await updateFundamentalNote(symbol, { note });
      setFundamentalNotes((prev) => {
        const next = prev.filter((item) => item.symbol !== saved.symbol);
        next.push(saved);
        return next.sort((a, b) => a.symbol.localeCompare(b.symbol));
      });
      setActionResult(t('message.fundamentalNoteSaved', { symbol: saved.symbol }));
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleSaveValuationNote(symbol, note) {
    setError('');
    setActionResult('');
    try {
      const saved = await updateValuationNote(symbol, { note });
      setValuationNotes((prev) => {
        const next = prev.filter((item) => item.symbol !== saved.symbol);
        next.push(saved);
        return next.sort((a, b) => a.symbol.localeCompare(b.symbol));
      });
      setActionResult(`Valuation note saved for ${saved.symbol}.`);
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleSaveOverviewNote(noteType, note) {
    setError('');
    setActionResult('');
    try {
      const saved = await updateOverviewNote(noteType, { note });
      setOverviewNotes((prev) => {
        const next = prev.filter((item) => item.noteType !== saved.noteType);
        next.push(saved);
        return next.sort((a, b) => a.noteType.localeCompare(b.noteType));
      });
      setActionResult(saved.noteType === 'AI' ? t('message.aiNoteSaved') : t('message.tradingIdeaNoteSaved'));
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleExportPortfolioJson() {
    setError('');
    setActionResult('');
    try {
      const payload = await exportPortfolioJson();
      const date = String(payload.generatedAt || new Date().toISOString()).slice(0, 10);
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `portfolio-export-${date}.json`;
      a.click();
      window.URL.revokeObjectURL(url);
      setActionResult(t('message.portfolioJsonExported'));
    } catch (e) {
      setError(e.message);
    }
  }

  async function handleExportPortfolioJsonV2() {
    setError('');
    setActionResult('');
    try {
      const payload = await exportPortfolioJsonV2();
      const date = String(payload.generatedAt || new Date().toISOString()).slice(0, 10);
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `portfolio-export-v2-${date}.json`;
      a.click();
      window.URL.revokeObjectURL(url);
      setActionResult(t('message.portfolioJsonV2Exported'));
    } catch (e) {
      setError(e.message);
    }
  }

  async function handleUpdateSharesOutstandingOverride(symbol, value) {
    setError('');
    setActionResult('');
    try {
      const saved = await updateSharesOutstandingOverride(symbol, value);
      setPositions((prev) => {
        const next = prev.filter((item) => item.symbol !== saved.symbol);
        next.push(saved);
        return next.sort((a, b) => a.symbol.localeCompare(b.symbol));
      });
      setActionResult(value == null
        ? t('message.sharesOverrideCleared', { symbol: saved.symbol })
        : t('message.sharesOverrideSaved', { symbol: saved.symbol }));
      return saved;
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleUpdatePositionMetadata(symbol, payload) {
    setError('');
    setActionResult('');
    try {
      const saved = await updatePositionMetadata(symbol, payload);
      setPositions((prev) => {
        const next = prev.filter((item) => item.symbol !== saved.symbol);
        next.push(saved);
        return next.sort((a, b) => a.symbol.localeCompare(b.symbol));
      });
      setActionResult(t('message.classificationMetadataSaved', { symbol: saved.symbol }));
      return saved;
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleCashAdjustment(type) {
    const amount = Number(cashAdjustmentForm.amount);
    if (!Number.isFinite(amount) || amount <= 0) {
      setError(t('message.cashAmountRequired'));
      return;
    }

    setError('');
    setActionResult('');
    try {
      await createCashAdjustment({
        type,
        amount,
        occurredAt: new Date(`${cashAdjustmentForm.tradeDate}T00:00:00`).toISOString()
      });
      setCashAdjustmentForm(emptyCashAdjustment);
      setActionResult(type === 'DEPOSIT' ? t('message.cashAdded') : t('message.cashReduced'));
      await loadDashboard();
    } catch (e) {
      setError(e.message);
    }
  }

  async function handleUpdateCashAdjustment(adjustmentId, payload) {
    setError('');
    setActionResult('');
    try {
      await updateCashAdjustment(adjustmentId, payload);
      setActionResult('Cash adjustment updated.');
      await loadDashboard();
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleDeleteCashAdjustment(adjustmentId) {
    setError('');
    setActionResult('');
    try {
      await deleteCashAdjustment(adjustmentId);
      setActionResult('Cash adjustment deleted.');
      await loadDashboard();
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleImportCashAdjustments(file) {
    setError('');
    setActionResult('');
    try {
      const result = await importCashAdjustmentsCsv(file);
      setActionResult(`Cash import complete: ${result.importedRows} imported, ${result.failedRows} failed.`);
      await loadDashboard();
      return result;
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleImportCsv(dryRun) {
    if (!importFile) {
      setError(t('message.chooseCsv'));
      return;
    }

    setImportLoading(true);
    setError('');
    setActionResult('');

    try {
      const result = await importTransactionsCsv(importFile, dryRun);
      setImportResult(result);
      setActionResult(
        dryRun
          ? t('message.dryRunComplete', { imported: result.importedRows, failed: result.failedRows })
          : t('message.importComplete', { imported: result.importedRows, failed: result.failedRows })
      );

      if (!dryRun) {
        await loadDashboard();
        await refreshHistoryIfRequested();
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setImportLoading(false);
    }
  }

  async function handleDownloadErrors() {
    if (!importFile) {
      setError(t('message.chooseCsv'));
      return;
    }

    try {
      const blob = await downloadCsvImportErrors(importFile);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'failed-rows.csv';
      a.click();
      window.URL.revokeObjectURL(url);
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <main ref={autoTranslateRef} className={isDataReviewRoute ? 'app-shell data-review-shell' : 'app-shell'}>
      <AppHeader
        brand={t('app.brand')}
        languageLabel={t('app.language')}
        languages={supportedLanguages}
        currentLanguage={i18n.language}
        theme={theme}
        onLanguageChange={handleLanguageChange}
        onThemeToggle={() => setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'))}
      />

      <StatusToast error={error} message={actionResult} loading={loading} />

      <Routes>
        <Route
          path="/overview"
          element={
            <OverviewPage
              summary={summary}
              assetCurve={assetCurve}
              holdings={holdings}
              dipPriceHistoryBySymbol={dipPriceHistoryBySymbol}
              dividends={dividends}
              overviewNotes={overviewNotes}
              stockNotes={stockNotes}
              transactions={transactions}
              transactionForm={transactionForm}
              setTransactionForm={setTransactionForm}
              onRecordTransaction={handleRecordTransaction}
              cashAdjustmentForm={cashAdjustmentForm}
              setCashAdjustmentForm={setCashAdjustmentForm}
              onSubmitCashAdjustment={handleCashAdjustment}
              onUpdateCashAdjustment={handleUpdateCashAdjustment}
              onDeleteCashAdjustment={handleDeleteCashAdjustment}
              onImportCashAdjustments={handleImportCashAdjustments}
              onSaveOverviewNote={handleSaveOverviewNote}
              onExportPortfolioJson={handleExportPortfolioJson}
              onExportPortfolioJsonV2={handleExportPortfolioJsonV2}
            />
          }
        />
        <Route
          path="/market"
          element={
            <MarketDataPage
              historySymbol={historySymbol}
              historyFrom={historyFrom}
              historyTo={historyTo}
              historyLoading={historyLoading}
              historyRequested={historyRequested}
              priceHistory={priceHistory}
              peHistory={peHistory}
              quarterlyFundamentals={quarterlyFundamentals}
              capitalAllocationHistory={capitalAllocationHistory}
              marketAssumptions={marketAssumptions}
              valuation={valuation}
              transactions={transactions}
              positions={positions}
              fundamentalNotes={fundamentalNotes}
              valuationNotes={valuationNotes}
              onLoadHistory={loadHistory}
              onUpdateSharesOutstandingOverride={handleUpdateSharesOutstandingOverride}
              onSaveFundamentalNote={handleSaveFundamentalNote}
              onSaveValuationNote={handleSaveValuationNote}
            />
          }
        />
        <Route
          path="/classifications"
          element={
            <ClassificationsPage
              positions={positions}
              holdings={holdings}
              transactions={transactions}
              onSaveMetadata={handleUpdatePositionMetadata}
            />
          }
        />
        <Route
          path="/cash"
          element={
            <CashPage
              assetCurve={assetCurve}
              cashAdjustments={cashAdjustments}
              cashAdjustmentForm={cashAdjustmentForm}
              setCashAdjustmentForm={setCashAdjustmentForm}
              onSubmitCashAdjustment={handleCashAdjustment}
            />
          }
        />
        <Route
          path="/transactions"
          element={
            <TransactionsPage
              transactions={transactions}
              onDeleteTransaction={handleDeleteTransaction}
              onUpdateTransaction={handleUpdateTransaction}
              transactionForm={transactionForm}
              setTransactionForm={setTransactionForm}
              onRecordTransaction={handleRecordTransaction}
              importFile={importFile}
              setImportFile={setImportFile}
              importLoading={importLoading}
              importResult={importResult}
              onImportDryRun={() => handleImportCsv(true)}
              onImportCsv={() => handleImportCsv(false)}
              onDownloadImportErrors={handleDownloadErrors}
            />
          }
        />
        <Route
          path="/notes"
          element={(
            <NotesPage
              transactions={transactions}
              holdings={holdings}
              stockNotes={stockNotes}
              onSaveStockNote={handleSaveStockNote}
            />
          )}
        />
        <Route
          path="/dividends"
          element={
            <DividendsPage
              monthlyDividends={monthlyDividends}
              dividends={dividends}
              dividendForm={dividendForm}
              setDividendForm={setDividendForm}
              onAddDividend={handleAddDividend}
              onUpdateDividend={handleUpdateDividend}
              onDeleteDividend={handleDeleteDividend}
              onImportDividends={handleImportDividends}
            />
          }
        />
        <Route path="/admin/data-review" element={<DataReviewConsolePage />} />
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>

      <BottomNav />
    </main>
  );
}
