import { useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import {
  createCashAdjustment,
  createDividend,
  downloadCsvImportErrors,
  deleteTransaction,
  getDividends,
  getAssetCurve,
  getHoldings,
  getMonthlyDividends,
  getPeHistory,
  getPriceHistory,
  getStockNotes,
  getSummary,
  getTransactions,
  importDividendsCsv,
  importTransactionsCsv,
  recordTransaction,
  refreshPrices,
  syncMarketClose,
  updateStockNote,
  updateTransaction
} from './api';
import OverviewPage from './pages/OverviewPage';
import MarketDataPage from './pages/MarketDataPage';
import TransactionsPage from './pages/TransactionsPage';
import DividendsPage from './pages/DividendsPage';
import NotesPage from './pages/NotesPage';
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

function buildDefaultHistoryRange() {
  const to = new Date();
  const from = new Date(to);
  from.setFullYear(from.getFullYear() - 1);
  return {
    from: formatDateInput(from),
    to: formatDateInput(to)
  };
}

export default function App() {
  const defaultHistoryRange = buildDefaultHistoryRange();
  const [theme, setTheme] = useState(() => {
    const saved = window.localStorage.getItem('portfolio-theme');
    return saved === 'light' ? 'light' : 'dark';
  });
  const [holdings, setHoldings] = useState([]);
  const [summary, setSummary] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [stockNotes, setStockNotes] = useState([]);
  const [dividends, setDividends] = useState([]);
  const [monthlyDividends, setMonthlyDividends] = useState([]);
  const [assetCurve, setAssetCurve] = useState([]);
  const [priceHistory, setPriceHistory] = useState([]);
  const [peHistory, setPeHistory] = useState([]);
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

  async function loadDashboard() {
    setLoading(true);
    setError('');

    try {
      const [holdingsData, summaryData, transactionsData, assetCurveData, dividendsData, monthlyDividendsData, stockNotesData] = await Promise.all([
        getHoldings(),
        getSummary(),
        getTransactions(),
        getAssetCurve(),
        getDividends(),
        getMonthlyDividends(),
        getStockNotes()
      ]);

      setHoldings(holdingsData);
      setSummary(summaryData);
      setTransactions(transactionsData);
      setAssetCurve(assetCurveData);
      setDividends(dividendsData);
      setMonthlyDividends(monthlyDividendsData);
      setStockNotes(stockNotesData);

    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
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
      const [prices, pe] = await Promise.all([
        getPriceHistory(symbol, from, to),
        getPeHistory(symbol, from, to)
      ]);
      setPriceHistory(prices);
      setPeHistory(pe);
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
      await Promise.all([refreshPrices(), syncMarketClose()]);
      await loadDashboard();
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    loadDashboard();
    bootstrapMarketData();
  }, []);

  useEffect(() => {
    document.body.setAttribute('data-theme', theme);
    window.localStorage.setItem('portfolio-theme', theme);
  }, [theme]);

  async function handleRecordTransaction(e) {
    e.preventDefault();
    setError('');

    try {
      await recordTransaction({
        symbol: transactionForm.symbol,
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
      setActionResult(`Transaction ${transactionId} deleted.`);
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
      setActionResult(`Transaction ${transactionId} updated.`);
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
        symbol: dividendForm.symbol,
        amount: Number(dividendForm.amount),
        paidDate: dividendForm.paidDate
      });
      setDividendForm({ ...emptyDividend, paidDate: formatDateInput(new Date()) });
      setActionResult('Dividend recorded.');
      await loadDashboard();
    } catch (e) {
      setError(e.message);
    }
  }

  async function handleImportDividends(file) {
    setError('');
    setActionResult('');
    try {
      const result = await importDividendsCsv(file);
      setActionResult(`Dividend CSV imported: imported=${result.importedRows}, failed=${result.failedRows}, skipped=${result.skippedRows}`);
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
      setActionResult(`Saved stock note for ${saved.symbol}.`);
    } catch (e) {
      setError(e.message);
      throw e;
    }
  }

  async function handleCashAdjustment(type) {
    const amount = Number(cashAdjustmentForm.amount);
    if (!Number.isFinite(amount) || amount <= 0) {
      setError('Cash amount must be greater than 0.');
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
      setActionResult(type === 'DEPOSIT' ? 'Cash added.' : 'Cash reduced.');
      await loadDashboard();
    } catch (e) {
      setError(e.message);
    }
  }

  async function handleImportCsv(dryRun) {
    if (!importFile) {
      setError('Please choose a CSV file first.');
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
          ? `Dry-run complete: imported=${result.importedRows}, failed=${result.failedRows}`
          : `Import complete: imported=${result.importedRows}, failed=${result.failedRows}`
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
      setError('Please choose a CSV file first.');
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
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">P&F</p>
          <h1 className="brand">STOCK PORTFOLIO</h1>
        </div>
        <div className="topbar-actions">
          <nav className="tabs">
            <NavLink to="/overview" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Overview</NavLink>
            <NavLink to="/market" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Market Data</NavLink>
            <NavLink to="/transactions" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Transactions</NavLink>
            <NavLink to="/dividends" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Dividends</NavLink>
            <NavLink to="/notes" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Notes</NavLink>
          </nav>
          <button type="button" className="theme-toggle" onClick={() => setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'))}>
            {theme === 'dark' ? 'Switch to Light' : 'Switch to Dark'}
          </button>
        </div>
      </header>

      {error && <p className="error">{error}</p>}
      {actionResult && <p className="info">{actionResult}</p>}
      {loading && <p className="muted">Loading dashboard...</p>}

      <Routes>
        <Route
          path="/overview"
          element={
            <OverviewPage
              summary={summary}
              assetCurve={assetCurve}
              holdings={holdings}
              dividends={dividends}
              transactions={transactions}
              cashAdjustmentForm={cashAdjustmentForm}
              setCashAdjustmentForm={setCashAdjustmentForm}
              onSubmitCashAdjustment={handleCashAdjustment}
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
              transactions={transactions}
              holdings={holdings}
              onLoadHistory={loadHistory}
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
              onImportDividends={handleImportDividends}
            />
          }
        />
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
    </main>
  );
}
