import { useEffect, useState } from 'react';
import {
  addPosition,
  getHoldings,
  getSummary,
  getTransactions,
  recordTransaction
} from './api';

const emptyPosition = { symbol: '', quantity: '', averageCost: '' };
const emptyTransaction = { symbol: '', type: 'BUY', quantity: '', price: '' };

export default function App() {
  const [holdings, setHoldings] = useState([]);
  const [summary, setSummary] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [positionForm, setPositionForm] = useState(emptyPosition);
  const [transactionForm, setTransactionForm] = useState(emptyTransaction);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function loadDashboard() {
    setLoading(true);
    setError('');

    try {
      const [holdingsData, summaryData, transactionsData] = await Promise.all([
        getHoldings(),
        getSummary(),
        getTransactions()
      ]);

      setHoldings(holdingsData);
      setSummary(summaryData);
      setTransactions(transactionsData);
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
