# Investment Research & Portfolio Platform

Version `0.1.0-beta.2` is a Web-focused investment research and portfolio management platform. It combines a React client, a Spring Boot API, and PostgreSQL-backed portfolio, market-data, valuation, notes, and data-review workflows.

This release publishes the Web client and backend only. The backend keeps its additive mobile synchronization API, but native iOS and Android client source is intentionally outside this release.

## Highlights

- Portfolio holdings, allocation, performance, transactions, dividends, and cash adjustments
- CSV import/export and structured portfolio JSON export (`v1` and `v2`)
- Price, P/E, quarterly fundamentals, cash flow, margin, ROE/ROIC, and capital-allocation history
- Server-authoritative dual-track FCFF/FCFE DCF, per-method Reverse DCF and sensitivity,
  Bear/Base/Bull reconciliation, explicit operating forecasts, and real CAPE analysis
- Position classification metadata and stock, fundamental, valuation, and portfolio notes
- Data Review queues, corrections, batch decisions, audit history, and rollback
- Optional bearer-token API protection, configurable CORS, OpenAPI, and Swagger UI
- English, Simplified Chinese, and Japanese Web UI

## Stack and Requirements

- Backend: Java 21, Spring Boot 3.5, Maven
- Frontend: React 18, Vite 5, Node.js 20+
- Database: PostgreSQL 16
- Local database: Docker Compose (optional)

## Run Locally

Start PostgreSQL:

```bash
docker compose up -d
```

Start the backend:

```bash
cd backend
mvn spring-boot:run
```

Start the Web client in a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

The Web client runs at `http://localhost:5173` and proxies `/api` to `http://localhost:8080` by default. Override the development backend with `VITE_DEV_API_PROXY_TARGET`; production builds keep the same-origin `/api` default unless `VITE_API_BASE_URL` is explicitly set.

Example environment settings are provided in [`.env.example`](.env.example) and [`frontend/.env.example`](frontend/.env.example). These files are documentation templates; export the required variables through your shell or deployment platform.

## Database Upgrade

Existing beta1 PostgreSQL data must be backed up and migrated before starting beta2. Follow [the beta1-to-beta2 upgrade guide](docs/beta1-to-beta2-upgrade.md), which applies all 16 migrations from `2026-05-04` through `2026-08-01` in a fixed order.

Do not commit database dumps, snapshot SQL, exported portfolio data, or generated audit reports. After a successful production migration, set:

```bash
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | local PostgreSQL | Database connection |
| `DB_USERNAME`, `DB_PASSWORD` | local development credentials | Database authentication |
| `SERVER_PORT` | `8080` | Backend port |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `update` | Use `validate` after production migration |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | `*` | Allowed API origin patterns; restrict in production |
| `APP_AUTH_ENABLED` | `false` | Enables API bearer protection |
| `APP_API_TOKEN` | empty | Required when bearer protection is enabled |
| `PRICING_TIMEZONE` | `America/New_York` | Market scheduler timezone |
| `PRICING_OPEN_CRON`, `PRICING_CLOSE_CRON` | market-hours schedules | Price refresh schedules |
| `FUNDAMENTALS_BACKFILL_MISSING_ENABLED` | `true` | Enables scheduled fundamentals gap fill |
| `FUNDAMENTALS_BACKFILL_MISSING_YEARS` | `15` | Fundamentals history window |
| `FRED_CPI_CSV_URL` | keyless FRED CPI CSV | CPI source for real CAPE |
| `VALUATION_ENGINE_VERSION` | `valuation-java-2.1.0` | Valuation result provenance |
| `VALUATION_EQUITY_RISK_PREMIUM` | `5.0` | Default equity risk premium (%) |

Never expose `APP_API_TOKEN` through a `VITE_*` variable: Vite embeds such values in the browser bundle. When bearer protection is enabled for a hosted Web deployment, use a secure same-origin gateway or another server-side authentication layer.

## API Surface

All beta1 endpoints remain available. Beta2 adds API groups without removing the beta1 contracts:

- `/api/positions` and metadata/share-count overrides
- `/api/transactions`, `/api/dividends`, and `/api/cash-adjustments`
- `/api/portfolio` summaries, history, market assumptions, and JSON exports
- `/api/valuations` and `/api/valuation-notes`
- `POST /api/valuations/{symbol}/explicit-forecast` for the 10-year shared operating forecast and independent debt policy
- `POST /api/portfolio/history/fundamentals/rebuild-sec-debt-fields?dryRun=true` for audited SEC debt-field rebuilds
- `/api/stock-notes`, `/api/fundamental-notes`, and `/api/overview-notes`
- `/api/admin/data-review`
- `/api/sync` for additive backend synchronization support

OpenAPI JSON is available at `/v3/api-docs`; Swagger UI is available at `/swagger-ui.html`.

## Verification

```bash
cd backend
mvn test --batch-mode

cd ../frontend
npm ci
npm test
npm run build
```

Release changes are summarized in [`CHANGELOG.md`](CHANGELOG.md). Architecture details are in [`docs/architecture.md`](docs/architecture.md).
