# Stock Portfolio Manager

Full-stack starter project for stock portfolio management.

## Tech Stack

- Backend: Spring Boot (Java 17)
- Frontend: React (Vite)
- Database: PostgreSQL

## Features Included

- Add stock position
- Track holdings
- Show portfolio summary
- Record transactions

## Project Structure

- `backend/`: Spring Boot REST API
- `frontend/`: React web app
- `docker-compose.yml`: PostgreSQL for local development

## Run Locally

### 1. Start PostgreSQL

```bash
docker compose up -d
```

### 2. Start Backend

```bash
cd backend
mvn spring-boot:run
```

Backend runs on `http://localhost:8080`.

### 3. Start Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend runs on `http://localhost:5173`.

## REST API Endpoints

### Positions

- `POST /api/positions` - add or update a stock position
- `GET /api/positions` - list positions

Example payload:

```json
{
  "symbol": "AAPL",
  "quantity": 10,
  "averageCost": 178.45
}
```

### Transactions

- `POST /api/transactions` - record BUY/SELL transaction
- `GET /api/transactions` - list transactions

Example payload:

```json
{
  "symbol": "AAPL",
  "type": "BUY",
  "quantity": 5,
  "price": 182.00
}
```

### Portfolio

- `GET /api/portfolio/holdings` - current holdings view
- `GET /api/portfolio/summary` - aggregate summary

## Environment Variables (Backend)

- `DB_HOST` (default: `localhost`)
- `DB_PORT` (default: `5432`)
- `DB_NAME` (default: `stock_portfolio`)
- `DB_USERNAME` (default: `stock_user`)
- `DB_PASSWORD` (default: `stock_password`)
- `SERVER_PORT` (default: `8080`)
