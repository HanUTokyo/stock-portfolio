import { useEffect, useMemo, useState } from 'react';
import {
  addPosition,
  getAssetCurve,
  getHoldings,
  getSummary,
  getTransactions,
  recordTransaction
} from './api';

const emptyPosition = { symbol: '', quantity: '', averageCost: '' };
const emptyTransaction = { symbol: '', type: 'BUY', quantity: '', price: '' };

function formatCurrency(value) {
  return Number(value).toFixed(2);
}

function buildChartData(assetCurve) {
  if (!assetCurve.length) {
    return { path: '', points: [] };
  }

  const width = 640;
  const height = 240;
  const padding = 24;
  const values = assetCurve.map((point) => Number(point.totalAssets));
  const minValue = Math.min(...values);
  const maxValue = Math.max(...values);
  const valueSpan = Math.max(maxValue - minValue, 1);

  const points = assetCurve.map((point, index) => {
    const x = padding + (index * (width - padding * 2)) / Math.max(assetCurve.length - 1, 1);
    const y = height - padding - ((Number(point.totalAssets) - minValue) / valueSpan) * (height - padding * 2);
    return {
      x,
      y,
      timestamp: point.timestamp,
      totalAssets: point.totalAssets
    };
  });

  const path = points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`)
    .join(' ');

  return { path, points, width, height, minValue, maxValue };
}

export default function App() {
  const [holdings, setHoldings] = useState([]);
  const [summary, setSummary] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [assetCurve, setAssetCurve] = useState([]);
  const [positionForm, setPositionForm] = useState(emptyPosition);
  const [transactionForm, setTransactionForm] = useState(emptyTransaction);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const chartData = useMemo(() => buildChartData(assetCurve), [assetCurve]);

  async function loadDashboard() {
    setLoading(true);
    setError('');

    try {
      const [holdingsData, summaryData, transactionsData, assetCurveData] = await Promise.all([
        getHoldings(),
        getSummary(),
        getTransactions(),
        getAssetCurve()
      ]);

      setHoldings(holdingsData);
      setSummary(summaryData);
      setTransactions(transactionsData);
      setAssetCurve(assetCurveData);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadDashboard();
  }, []);

  async function handleAddPosition(e) {
    e.preventDefault();
    setError('');

    try {
      await addPosition({
        symbol: positionForm.symbol,
        quantity: Number(positionForm.quantity),
        averageCost: Number(positionForm.averageCost)
      });
      setPositionForm(emptyPosition);
      await loadDashboard();
    } catch (err) {
      setError(err.message);
    }
  }

  async function handleRecordTransaction(e) {
    e.preventDefault();
    setError('');

    try {
      await recordTransaction({
        symbol: transactionForm.symbol,
        type: transactionForm.type,
        quantity: Number(transactionForm.quantity),
        price: Number(transactionForm.price)
      });
      setTransactionForm(emptyTransaction);
      await loadDashboard();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <main className="container">
      <h1>Stock Portfolio Manager</h1>

      {error && <p className="error">{error}</p>}
      {loading && <p>Loading...</p>}

      <section className="grid">
        <article className="card">
          <h2>Portfolio Summary</h2>
          {summary ? (
            <ul>
              <li>Total Positions: {summary.totalPositions}</li>
              <li>Total Units: {summary.totalUnits}</li>
              <li>Total Cost Basis: ${summary.totalCostBasis}</li>
            </ul>
          ) : (
            <p>No summary data yet.</p>
          )}
        </article>

        <article className="card">
          <h2>Add Stock Position</h2>
          <form onSubmit={handleAddPosition}>
            <input
              placeholder="Symbol (e.g., AAPL)"
              value={positionForm.symbol}
              onChange={(e) => setPositionForm({ ...positionForm, symbol: e.target.value })}
              required
            />
            <input
              type="number"
              min="0.0001"
              step="0.0001"
              placeholder="Quantity"
              value={positionForm.quantity}
              onChange={(e) => setPositionForm({ ...positionForm, quantity: e.target.value })}
              required
            />
            <input
              type="number"
              min="0"
              step="0.0001"
              placeholder="Average Cost"
              value={positionForm.averageCost}
              onChange={(e) => setPositionForm({ ...positionForm, averageCost: e.target.value })}
              required
            />
            <button type="submit">Add / Update Position</button>
          </form>
        </article>

        <article className="card">
          <h2>Record Transaction</h2>
          <form onSubmit={handleRecordTransaction}>
            <input
              placeholder="Symbol"
              value={transactionForm.symbol}
              onChange={(e) => setTransactionForm({ ...transactionForm, symbol: e.target.value })}
              required
            />
            <select
              value={transactionForm.type}
              onChange={(e) => setTransactionForm({ ...transactionForm, type: e.target.value })}
            >
              <option value="BUY">BUY</option>
              <option value="SELL">SELL</option>
            </select>
            <input
              type="number"
              min="0.0001"
              step="0.0001"
              placeholder="Quantity"
              value={transactionForm.quantity}
              onChange={(e) => setTransactionForm({ ...transactionForm, quantity: e.target.value })}
              required
            />
            <input
              type="number"
              min="0.0001"
              step="0.0001"
              placeholder="Price"
              value={transactionForm.price}
              onChange={(e) => setTransactionForm({ ...transactionForm, price: e.target.value })}
              required
            />
            <button type="submit">Record Transaction</button>
          </form>
        </article>
      </section>

      <section className="card">
        <h2>总资产曲线</h2>
        {chartData.path ? (
          <div className="chart-wrap">
            <svg viewBox={`0 0 ${chartData.width} ${chartData.height}`} className="asset-chart" role="img" aria-label="总资产曲线图">
              <line x1="24" y1="216" x2="616" y2="216" className="chart-axis" />
              <line x1="24" y1="24" x2="24" y2="216" className="chart-axis" />
              <path d={chartData.path} className="chart-line" />
            </svg>
            <p className="chart-caption">
              最低: ${formatCurrency(chartData.minValue)} / 最高: ${formatCurrency(chartData.maxValue)}
            </p>
          </div>
        ) : (
          <p>暂无交易数据，记录交易后将显示总资产曲线。</p>
        )}
      </section>

      <section className="card">
        <h2>Holdings</h2>
        <table>
          <thead>
            <tr>
              <th>Symbol</th>
              <th>Quantity</th>
              <th>Average Cost</th>
              <th>Cost Basis</th>
            </tr>
          </thead>
          <tbody>
            {holdings.map((holding) => (
              <tr key={holding.symbol}>
                <td>{holding.symbol}</td>
                <td>{holding.quantity}</td>
                <td>${holding.averageCost}</td>
                <td>${holding.costBasis}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="card">
        <h2>Transactions</h2>
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Symbol</th>
              <th>Type</th>
              <th>Quantity</th>
              <th>Price</th>
            </tr>
          </thead>
          <tbody>
            {transactions.map((txn) => (
              <tr key={txn.id}>
                <td>{new Date(txn.executedAt).toLocaleString()}</td>
                <td>{txn.symbol}</td>
                <td>{txn.type}</td>
                <td>{txn.quantity}</td>
                <td>${txn.price}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </main>
  );
}
