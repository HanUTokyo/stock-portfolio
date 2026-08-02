# Upgrade PostgreSQL from beta1 to beta2

This procedure preserves an existing beta1 database. Rehearse it against a restored copy before touching the production database.

## Prerequisites

- PostgreSQL 16 client tools (`pg_dump`, `pg_restore`, and `psql`)
- A beta1 database connection string with schema-changing privileges
- Enough disk space for a custom-format backup
- The beta2 backend stopped during the production migration

Do not place the connection string or backup inside the repository.

## 1. Inspect the migration order

```bash
scripts/migrate-beta1-to-beta2.sh --dry-run
```

The command lists the 16 scripts from `2026-05-04_quarterly_fundamentals.sql` through `2026-08-01_capital_allocation_history.sql`. The mobile-sync migration remains part of the sequence because the beta2 backend retains `/api/sync`.

## 2. Rehearse against a restored beta1 copy

Create a separate PostgreSQL database, restore a recent beta1 backup into it, and point `DATABASE_URL` at that copy. Never use the production URL for the first run.

```bash
export DATABASE_URL='postgresql://USER:PASSWORD@HOST:PORT/RESTORED_DATABASE'
export BACKUP_PATH='/absolute/path/outside-the-repo/restored-beta1-before-beta2.dump'
scripts/migrate-beta1-to-beta2.sh
```

The migration command creates the backup before applying SQL and refuses to overwrite an existing backup path. It stops on the first SQL error.

Start the beta2 backend against the migrated copy with schema validation:

```bash
export SPRING_JPA_HIBERNATE_DDL_AUTO=validate
cd backend
mvn spring-boot:run
```

Verify at minimum:

- Existing positions, transactions, dividends, cash adjustments, and notes are present.
- `/api/portfolio/summary` and `/api/portfolio/holdings` return expected totals.
- `/api/valuations/{symbol}` returns a valid response or an explicit applicability result.
- `/api/admin/data-review/summary` responds successfully.
- A repeated `/api/sync/mutations` request remains idempotent in the automated integration test.

## 3. Migrate production

Schedule downtime, stop the beta1/beta2 backend processes, set `DATABASE_URL` and a new `BACKUP_PATH`, and run the same script. Keep the generated dump until the upgraded deployment and business totals have been verified.

Deploy the beta2 backend with:

```bash
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
```

## Recovery

If migration or verification fails, stop the backend, create a fresh empty database, and restore the pre-migration dump with `pg_restore`. Do not restore over the partially migrated database.

```bash
createdb stock_portfolio_recovery
pg_restore --exit-on-error --no-owner --dbname=stock_portfolio_recovery "$BACKUP_PATH"
```

Point beta1 at the restored database only after confirming the restore completed successfully.
