import { useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import {
  createDividend,
  downloadCsvImportErrors,
  deleteTransaction,
  getDividends,
  getAssetCurve,
  getHoldings,
  getMonthlyDividends,
  getPeHistory,
  getPriceHistory,
  getSummary,
  getTransactions,
  importDividendsCsv,
  importTransactionsCsv,
  recordTransaction,
  refreshPrices,
  syncMarketClose,
  updateTransaction
} from './api';
import OverviewPage from './pages/OverviewPage';
import MarketDataPage from './pages/MarketDataPage';
import TransactionsPage from './pages/TransactionsPage';
import DividendsPage from './pages/DividendsPage';
import { formatDateInput } from './utils/charts';

function createEmptyTransaction() {
  return {
    symbol: '',
    type: 'BUY',
    quantity: '',
    price: '',
    tradeDate: formatDateInput(new Date())
  };
}

const emptyDividend = { symbol: '', amount: '', paidDate: formatDateInput(new Date()) };

export default function App() {
  const [holdings, setHoldings] = useState([]);
  const [summary, setSummary] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [dividends, setDividends] = useState([]);
  const [monthlyDividends, setMonthlyDividends] = useState([]);
  const [assetCurve, setAssetCurve] = useState([]);
  const [priceHistory, setPriceHistory] = useState([]);
  const [peHistory, setPeHistory] = useState([]);
  const [historySymbol, setHistorySymbol] = useState('');
  const [historyFrom, setHistoryFrom] = useState(formatDateInput(new Date(Date.now() - 365 * 24 * 60 * 60 * 1000)));
  const [historyTo, setHistoryTo] = useState(formatDateInput(new Date()));
  const [historyLoading, setHistoryLoading] = useState(false);
  const [transactionForm, setTransactionForm] = useState(createEmptyTransaction());
  const [dividendForm, setDividendForm] = useState(emptyDividend);
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
      const [holdingsData, summaryData, transactionsData, assetCurveData, dividendsData, monthlyDividendsData] = await Promise.all([
        getHoldings(),
        getSummary(),
        getTransactions(),
        getAssetCurve(),
        getDividends(),
        getMonthlyDividends()
      ]);

      setHoldings(holdingsData);
      setSummary(summaryData);
      setTransactions(transactionsData);
      setAssetCurve(assetCurveData);
      setDividends(dividendsData);
      setMonthlyDividends(monthlyDividendsData);

      if (!historySymbol && holdingsData.length > 0) {
        setHistorySymbol(holdingsData[0].symbol);
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  async function loadHistory() {
    if (!historySymbol) {
      return;
    }

    setHistoryLoading(true);
    try {
      const [prices, pe] = await Promise.all([
        getPriceHistory(historySymbol, historyFrom, historyTo),
        getPeHistory(historySymbol, historyFrom, historyTo)
      ]);
      setPriceHistory(prices);
      setPeHistory(pe);
    } catch (e) {
      setError(e.message);
    } finally {
      setHistoryLoading(false);
    }
  }

  useEffect(() => {
    loadDashboard();
  }, []);

  useEffect(() => {
    loadHistory();
  }, [historySymbol, historyFrom, historyTo]);

  async function handleRecordTransaction(e) {
    e.preventDefault();
    setError('');

    try {
      await recordTransaction({
        symbol: transactionForm.symbol,
        type: transactionForm.type,
        quantity: Number(transactionForm.quantity),
        price: Number(transactionForm.price),
        executedAt: new Date(`${transactionForm.tradeDate}T00:00:00`).toISOString()
      });
      setTransactionForm(createEmptyTransaction());
      await loadDashboard();
      await loadHistory();
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
      await loadHistory();
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
      await loadHistory();
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

  async function handleRefreshPrices() {
    setActionResult('');
    setError('');

    try {
      const result = await refreshPrices();
      setActionResult(`Manual refresh done: ${result.updatedSymbols}/${result.scannedSymbols}`);
      await loadDashboard();
      await loadHistory();
    } catch (e) {
      setError(e.message);
    }
  }

  async function handleSyncClose() {
    setActionResult('');
    setError('');

    try {
      const result = await syncMarketClose();
      setActionResult(`Close sync done: success=${result.successfulSymbols}, failed=${result.failedSymbols}, skipped=${result.skippedSymbols}`);
      await loadDashboard();
      await loadHistory();
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
        await loadHistory();
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
          <p className="eyebrow">Stock Portfolio</p>
          <h1 className="brand">Control Center</h1>
        </div>
        <div className="topbar-actions">
          <nav className="tabs">
            <NavLink to="/overview" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Overview</NavLink>
            <NavLink to="/market" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Market Data</NavLink>
            <NavLink to="/transactions" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Transactions</NavLink>
            <NavLink to="/dividends" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Dividends</NavLink>
          </nav>
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
            />
          }
        />
        <Route
          path="/market"
          element={
            <MarketDataPage
              historySymbol={historySymbol}
              setHistorySymbol={setHistorySymbol}
              historyFrom={historyFrom}
              setHistoryFrom={setHistoryFrom}
              historyTo={historyTo}
              setHistoryTo={setHistoryTo}
              historyLoading={historyLoading}
              priceHistory={priceHistory}
              peHistory={peHistory}
              onLoadHistory={loadHistory}
              onRefreshPrices={handleRefreshPrices}
              onSyncMarketClose={handleSyncClose}
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
